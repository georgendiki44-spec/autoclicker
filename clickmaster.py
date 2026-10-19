"""Auto Clicker Pro - Kivy control centre.

The app only configures things. Real taps are performed by the native Android
services in src/android/java (floating overlay + accessibility service), which this
module drives through pyjnius. On desktop the UI runs in preview mode.
"""
import json
import os

from kivy.animation import Animation
from kivy.app import App
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.lang import Builder
from kivy.properties import BooleanProperty, ListProperty, StringProperty
from kivy.uix.behaviors import ButtonBehavior, ToggleButtonBehavior
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.textinput import TextInput
from kivy.uix.widget import Widget
from kivy.utils import platform

APP_VERSION = "1.0"
PREFS = "autoclicker_private"
OVERLAY_CLS = "com.george.autoclickerpro.FloatingOverlayService"
ACCESS_CLS = "com.george.autoclickerpro.AutoClickerAccessibilityService"

GREEN = [0.125, 0.753, 0.478, 1]
AMBER = [0.961, 0.647, 0.141, 1]
RED = [1.0, 0.231, 0.322, 1]
MUTED = [0.545, 0.576, 0.655, 1]

DEFAULTS = {
    "interval_ms": 100, "count": 50, "continuous": False, "gesture": 0,
    "jitter": 3, "start_delay": 3,
    "dwell_s": 12, "metric_mode": False, "min_likes": 1000, "min_comments": 10,
    "comment_mode": "off", "comment_text": "", "cooldown": 60,
}

MIN_MS, MAX_MS = 20, 5000


def slider_to_ms(v):
    ms = MIN_MS + (MAX_MS - MIN_MS) * (float(v) ** 2.2)
    if ms < 200:
        step = 5
    elif ms < 1000:
        step = 10
    else:
        step = 50
    return int(max(MIN_MS, min(MAX_MS, round(ms / step) * step)))


def ms_to_slider(ms):
    ms = max(MIN_MS, min(MAX_MS, ms))
    return ((ms - MIN_MS) / float(MAX_MS - MIN_MS)) ** (1 / 2.2)


def to_int(text, default, lo=0, hi=10 ** 9):
    try:
        return max(lo, min(hi, int(str(text).strip())))
    except (TypeError, ValueError):
        return default


# --------------------------------------------------------------------------- native bridge

class Native:
    """Thin, failure-tolerant wrapper around the Java services (no-ops on desktop)."""

    def __init__(self):
        self.ok = False
        if platform != "android":
            return
        try:
            from jnius import autoclass
            self._auto = autoclass
            self.activity = autoclass("org.kivy.android.PythonActivity").mActivity
            self.overlay = autoclass(OVERLAY_CLS)
            self.access = autoclass(ACCESS_CLS)
            self.sdk = autoclass("android.os.Build$VERSION").SDK_INT
            self.ok = True
        except Exception as exc:  # pragma: no cover - Android only
            print("Native bridge unavailable:", exc)

    # ---- permissions
    def overlay_granted(self):
        try:
            return bool(self._auto("android.provider.Settings").canDrawOverlays(self.activity))
        except Exception:
            return False

    def service(self):
        try:
            return self.access.getInstance()
        except Exception:
            return None

    def notifications_granted(self):
        if self.sdk < 33:
            return True
        try:
            return self.activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == 0
        except Exception:
            return True

    def request_notifications(self):
        if self.sdk < 33 or self.notifications_granted():
            return
        try:
            from android.permissions import request_permissions
            request_permissions(["android.permission.POST_NOTIFICATIONS"])
        except Exception:
            pass

    # ---- navigation to system settings
    def _open(self, action, package=False):
        try:
            Intent = self._auto("android.content.Intent")
            intent = Intent(action)
            if package:
                intent.setData(self._auto("android.net.Uri").parse(
                    "package:" + str(self.activity.getPackageName())))
            self.activity.startActivity(intent)
            return True
        except Exception:
            return False

    def open_overlay_settings(self):
        return self._open("android.settings.action.MANAGE_OVERLAY_PERMISSION", True)

    def open_accessibility_settings(self):
        return self._open("android.settings.ACCESSIBILITY_SETTINGS")

    def open_notification_settings(self):
        return self._open("android.settings.APP_NOTIFICATION_SETTINGS", True) or self.open_app_info()

    def open_app_info(self):
        return self._open("android.settings.APPLICATION_DETAILS_SETTINGS", True)

    # ---- overlay / engine
    def overlay_active(self):
        try:
            return bool(self.overlay.isActive())
        except Exception:
            return False

    def launch_overlay(self):
        self.overlay.launch(self.activity)

    def close_overlay(self):
        self.overlay.shutdown(self.activity)

    def minimize(self, *_):
        try:
            self.activity.moveTaskToBack(True)
        except Exception:
            pass

    def engine(self):
        try:
            return {
                "active": bool(self.overlay.isActive()),
                "running": bool(self.overlay.isRunning()),
                "paused": bool(self.overlay.isPaused()),
                "taps": int(self.overlay.getTapCount()),
                "status": str(self.overlay.getEngineStatus() or ""),
                "vision": str(self.overlay.getVisionStatus() or ""),
            }
        except Exception:
            return None

    def configure_engine(self, s):
        try:
            self.overlay.configure(int(s["interval_ms"]), int(s["count"]), bool(s["continuous"]),
                                   int(s["jitter"]), int(s["gesture"]), int(s["start_delay"]) * 1000)
        except Exception:
            pass

    def toggle_pause(self):
        try:
            self.overlay.togglePause()
        except Exception:
            pass

    def stop_engine(self):
        try:
            self.overlay.stopClicking()
        except Exception:
            pass

    # ---- stored settings shared with the services
    def prefs(self):
        return self.activity.getSharedPreferences(PREFS, 0)

    def write_rules(self, s):
        try:
            e = self.prefs().edit()
            e.putInt("minimum_likes", int(s["min_likes"]))
            e.putInt("minimum_comments", int(s["min_comments"]))
            e.putInt("comment_cooldown_seconds", max(15, int(s["cooldown"])))
            e.putBoolean("metric_mode", bool(s["metric_mode"]))
            e.putBoolean("auto_comment_suggestions", s["comment_mode"] == "ai")
            e.putBoolean("template_comment_enabled", s["comment_mode"] == "text")
            e.putString("reusable_comment", str(s["comment_text"]))
            e.putInt("max_comments_per_session", 5)
            e.apply()
        except Exception:
            pass

    def push_rules(self, s):
        self.write_rules(s)
        svc = self.service()
        if svc is None:
            return False
        try:
            cooldown = max(15, min(86400, int(s["cooldown"])))
            svc.configureAutomationRules(int(s["min_likes"]), int(s["min_comments"]), cooldown,
                                         bool(s["metric_mode"]), s["comment_mode"] == "ai")
            svc.configureCommentAutomation(str(s["comment_text"]), s["comment_mode"] == "text",
                                           s["comment_mode"] == "ai")
            svc.setDwellSeconds(int(s["dwell_s"]))
            return True
        except Exception:
            return False

    def has_api_key(self):
        try:
            return len(str(self.prefs().getString("gemini_api_key", "") or "")) > 0
        except Exception:
            return False

    def set_api_key(self, key):
        e = self.prefs().edit()
        if key:
            e.putString("gemini_api_key", key)
        else:
            e.remove("gemini_api_key")
        e.apply()


# --------------------------------------------------------------------------- widgets

class Card(BoxLayout):
    pass


class Body(BoxLayout):
    pass


class Para(Label):
    pass


class RowLabel(Label):
    pass


class Btn(Button):
    tint = ListProperty([1.0, 0.231, 0.322, 1])
    is_toggle = BooleanProperty(False)


class Chip(Btn):
    pass


class ToggleBase(ToggleButtonBehavior, Btn):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.is_toggle = True


class Toggle(ToggleBase):
    pass


class Seg(ToggleBase):
    pass


class NavBtn(ToggleButtonBehavior, Label):
    pass


class Field(TextInput):
    pass


class Dot(Widget):
    ok = BooleanProperty(False)
    off_color = ListProperty([0.961, 0.647, 0.141, 1])


class SetupRow(ButtonBehavior, BoxLayout):
    ok = BooleanProperty(False)
    title = StringProperty("")
    sub = StringProperty("")


KV = r"""
#:import dp kivy.metrics.dp
#:import Window kivy.core.window.Window
#:import NoTransition kivy.uix.screenmanager.NoTransition
#:set BG (0.055, 0.067, 0.090, 1)
#:set CARD (0.090, 0.106, 0.141, 1)
#:set CARD2 (0.141, 0.161, 0.212, 1)
#:set ACCENT (1.0, 0.231, 0.322, 1)
#:set GREEN (0.125, 0.753, 0.478, 1)
#:set AMBER (0.961, 0.647, 0.141, 1)
#:set TEXT (0.91, 0.918, 0.94, 1)
#:set MUTED (0.545, 0.576, 0.655, 1)

<Card>:
    orientation: 'vertical'
    padding: dp(14)
    spacing: dp(10)
    size_hint_y: None
    height: self.minimum_height
    canvas.before:
        Color:
            rgba: CARD
        RoundedRectangle:
            pos: self.pos
            size: self.size
            radius: [dp(18)]

<Body>:
    orientation: 'vertical'
    padding: dp(14), dp(12), dp(14), dp(20)
    spacing: dp(12)
    size_hint_y: None
    height: self.minimum_height

<Para>:
    markup: True
    text_size: self.width, None
    size_hint_y: None
    height: self.texture_size[1]
    halign: 'left'
    valign: 'top'
    color: MUTED
    font_size: '13sp'
    line_height: 1.15

<H1@Para>:
    color: TEXT
    bold: True
    font_size: '19sp'

<H2@Para>:
    color: TEXT
    bold: True
    font_size: '16sp'

<RowLabel>:
    text_size: self.size
    halign: 'left'
    valign: 'middle'
    color: TEXT
    font_size: '15sp'

<Row@BoxLayout>:
    size_hint_y: None
    height: dp(46)
    spacing: dp(10)

<Btn>:
    background_normal: ''
    background_down: ''
    background_disabled_normal: ''
    background_disabled_down: ''
    background_color: 0, 0, 0, 0
    color: 1, 1, 1, 1
    bold: True
    font_size: '15sp'
    size_hint_y: None
    height: dp(48)
    opacity: .4 if self.disabled else 1
    canvas.before:
        Color:
            rgba: [self.tint[0] * .7, self.tint[1] * .7, self.tint[2] * .7, 1] if (self.state == 'down' and not self.is_toggle) else self.tint
        RoundedRectangle:
            pos: self.pos
            size: self.size
            radius: [dp(13)]

<Chip>:
    tint: CARD2
    height: dp(38)
    font_size: '13sp'

<Toggle>:
    tint: GREEN if self.state == 'down' else CARD2
    text: 'ON' if self.state == 'down' else 'OFF'
    size_hint_x: None
    width: dp(74)
    height: dp(40)

<Seg>:
    tint: ACCENT if self.state == 'down' else CARD2
    size_hint_x: 1
    height: dp(42)
    font_size: '14sp'

<NavBtn>:
    allow_no_selection: False
    color: ACCENT if self.state == 'down' else MUTED
    bold: self.state == 'down'
    font_size: '14sp'
    canvas.before:
        Color:
            rgba: ACCENT if self.state == 'down' else (0, 0, 0, 0)
        Rectangle:
            pos: self.x + self.width * .3, self.top - dp(3)
            size: self.width * .4, dp(3)

<Field>:
    multiline: False
    background_normal: ''
    background_active: ''
    background_color: CARD2
    foreground_color: TEXT
    cursor_color: ACCENT
    hint_text_color: MUTED
    selection_color: 1, .23, .32, .35
    size_hint_y: None
    height: dp(46)
    font_size: '15sp'
    write_tab: False
    padding: [dp(12), ((self.height - self.line_height) / 2) if not self.multiline else dp(10), dp(12), dp(8)]

<Dot>:
    canvas:
        Color:
            rgba: GREEN if self.ok else self.off_color
        Ellipse:
            pos: self.pos
            size: self.size

<SetupRow>:
    size_hint_y: None
    height: dp(62)
    spacing: dp(12)
    padding: dp(12), dp(8)
    canvas.before:
        Color:
            rgba: CARD2
        RoundedRectangle:
            pos: self.pos
            size: self.size
            radius: [dp(14)]
    Dot:
        ok: root.ok
        size_hint: None, None
        size: dp(14), dp(14)
        pos_hint: {'center_y': .5}
    BoxLayout:
        orientation: 'vertical'
        Label:
            text: root.title
            bold: True
            font_size: '15sp'
            color: TEXT
            text_size: self.size
            halign: 'left'
            valign: 'bottom'
        Label:
            text: root.sub
            font_size: '12sp'
            color: MUTED
            text_size: self.size
            halign: 'left'
            valign: 'top'
    Label:
        text: 'Ready' if root.ok else 'Set up'
        bold: True
        font_size: '13sp'
        color: GREEN if root.ok else AMBER
        size_hint_x: None
        width: dp(58)

FloatLayout:
    BoxLayout:
        orientation: 'vertical'
        canvas.before:
            Color:
                rgba: BG
            Rectangle:
                pos: self.pos
                size: self.size
        BoxLayout:
            size_hint_y: None
            height: dp(58)
            padding: dp(16), dp(8)
            spacing: dp(8)
            Label:
                text: 'Auto Clicker Pro'
                bold: True
                font_size: '20sp'
                color: TEXT
                text_size: self.size
                halign: 'left'
                valign: 'middle'
            BoxLayout:
                size_hint_x: None
                width: dp(112)
                padding: dp(10), dp(8)
                spacing: dp(8)
                canvas.before:
                    Color:
                        rgba: CARD
                    RoundedRectangle:
                        pos: self.pos
                        size: self.size
                        radius: [dp(16)]
                Dot:
                    ok: app.state_ok
                    off_color: MUTED
                    size_hint: None, None
                    size: dp(10), dp(10)
                    pos_hint: {'center_y': .5}
                Label:
                    text: app.state_text
                    bold: True
                    font_size: '12sp'
                    color: app.state_color
        ScreenManager:
            id: sm
            transition: NoTransition()
            Screen:
                name: 'home'
                ScrollView:
                    do_scroll_x: False
                    bar_width: dp(2)
                    Body:
                        Card:
                            Label:
                                text: app.state_text
                                bold: True
                                font_size: '34sp'
                                color: app.state_color
                                size_hint_y: None
                                height: dp(48)
                            Label:
                                text: app.tap_text
                                font_size: '16sp'
                                color: TEXT
                                size_hint_y: None
                                height: dp(24)
                            Para:
                                text: app.engine_status
                                halign: 'center'
                            Btn:
                                text: app.overlay_btn_text
                                tint: ACCENT if not app.overlay_on else CARD2
                                height: dp(54)
                                on_release: app.toggle_overlay()
                            Row:
                                Btn:
                                    text: app.pause_btn_text
                                    tint: AMBER
                                    disabled: not app.running
                                    on_release: app.pause_clicking()
                                Btn:
                                    text: 'Stop'
                                    tint: (0.78, 0.18, 0.24, 1)
                                    disabled: not app.running
                                    on_release: app.stop_clicking()
                        Card:
                            H2:
                                text: 'Setup'
                            SetupRow:
                                title: 'Display over other apps'
                                sub: 'Shows the floating controls'
                                ok: app.ok_overlay
                                on_release: app.open_overlay_settings()
                            SetupRow:
                                title: 'Accessibility service'
                                sub: 'Performs the taps and swipes'
                                ok: app.ok_access
                                on_release: app.open_accessibility_settings()
                            SetupRow:
                                title: 'Notifications'
                                sub: 'Keeps the controls alive in the background'
                                ok: app.ok_notif
                                on_release: app.open_notification_settings()
                            Para:
                                text: 'Accessibility switch greyed out? Open Settings > Apps > Auto Clicker Pro, tap the three-dot menu and choose [b]Allow restricted settings[/b].'
                        Card:
                            H2:
                                text: 'How to use'
                            Para:
                                text: '[b]1.[/b] Finish the setup above.\n[b]2.[/b] Tap [b]Show floating controls[/b]. The app steps aside.\n[b]3.[/b] Drag the red ring over the spot to tap.\n[b]4.[/b] Press [b]Start[/b] on the floating panel, or in the notification.\n\nPress a [b]volume key[/b] at any time to stop everything. Tune speed and style on the Clicker tab.'
            Screen:
                name: 'clicker'
                ScrollView:
                    do_scroll_x: False
                    bar_width: dp(2)
                    Body:
                        Card:
                            H2:
                                text: 'Speed'
                            Row:
                                size_hint_y: None
                                height: dp(30)
                                RowLabel:
                                    text: 'Time between taps'
                                RowLabel:
                                    id: interval_value
                                    text: '100 ms'
                                    halign: 'right'
                                    bold: True
                                    color: ACCENT
                            Slider:
                                id: interval_slider
                                min: 0
                                max: 1
                                value: .3
                                step: 0.001
                                size_hint_y: None
                                height: dp(36)
                                value_track: True
                                value_track_color: ACCENT
                                cursor_size: dp(26), dp(26)
                                on_value: app.on_interval_slider(self.value)
                            Row:
                                size_hint_y: None
                                height: dp(38)
                                spacing: dp(6)
                                Chip:
                                    text: '50 ms'
                                    on_release: app.set_interval(50)
                                Chip:
                                    text: '100 ms'
                                    on_release: app.set_interval(100)
                                Chip:
                                    text: '250 ms'
                                    on_release: app.set_interval(250)
                                Chip:
                                    text: '500 ms'
                                    on_release: app.set_interval(500)
                                Chip:
                                    text: '1 s'
                                    on_release: app.set_interval(1000)
                        Card:
                            H2:
                                text: 'How many taps'
                            Row:
                                RowLabel:
                                    text: 'Run until I stop it'
                                Toggle:
                                    id: continuous_toggle
                                    on_release: app.on_continuous(self.state == 'down')
                            Row:
                                disabled: continuous_toggle.state == 'down'
                                opacity: .4 if self.disabled else 1
                                RowLabel:
                                    text: 'Number of taps'
                                    size_hint_x: .6
                                Field:
                                    id: count_input
                                    text: '50'
                                    input_filter: 'int'
                                    halign: 'center'
                                    size_hint_x: .4
                                    on_text: app.on_count(self.text)
                        Card:
                            H2:
                                text: 'Gesture'
                            Row:
                                spacing: dp(6)
                                Seg:
                                    id: g0
                                    text: 'Tap'
                                    group: 'gesture'
                                    allow_no_selection: False
                                    on_release: app.set_gesture(0)
                                Seg:
                                    id: g1
                                    text: 'Double tap'
                                    group: 'gesture'
                                    allow_no_selection: False
                                    on_release: app.set_gesture(1)
                                Seg:
                                    id: g2
                                    text: 'Hold'
                                    group: 'gesture'
                                    allow_no_selection: False
                                    on_release: app.set_gesture(2)
                        Card:
                            H2:
                                text: 'Fine tuning'
                            Row:
                                size_hint_y: None
                                height: dp(30)
                                RowLabel:
                                    text: 'Random offset'
                                RowLabel:
                                    id: jitter_value
                                    text: '3 px'
                                    halign: 'right'
                                    bold: True
                                    color: ACCENT
                            Slider:
                                id: jitter_slider
                                min: 0
                                max: 60
                                step: 1
                                value: 3
                                size_hint_y: None
                                height: dp(36)
                                value_track: True
                                value_track_color: ACCENT
                                cursor_size: dp(26), dp(26)
                                on_value: app.on_jitter(self.value)
                            Row:
                                size_hint_y: None
                                height: dp(30)
                                RowLabel:
                                    text: 'Start delay'
                                RowLabel:
                                    id: delay_value
                                    text: '3 s'
                                    halign: 'right'
                                    bold: True
                                    color: ACCENT
                            Slider:
                                id: delay_slider
                                min: 0
                                max: 10
                                step: 1
                                value: 3
                                size_hint_y: None
                                height: dp(36)
                                value_track: True
                                value_track_color: ACCENT
                                cursor_size: dp(26), dp(26)
                                on_value: app.on_delay(self.value)
                            Para:
                                text: 'Settings save automatically and apply the next time you press Start.'
            Screen:
                name: 'tiktok'
                ScrollView:
                    do_scroll_x: False
                    bar_width: dp(2)
                    Body:
                        Card:
                            H2:
                                text: 'TikTok feed helpers'
                            Para:
                                text: 'Work only while TikTok is open and the Accessibility service is on.'
                            Row:
                                RowLabel:
                                    text: 'Auto like'
                                Toggle:
                                    id: like_toggle
                                    on_release: app.toggle_feature('like', self.state == 'down')
                            Row:
                                RowLabel:
                                    text: 'Auto next video'
                                Toggle:
                                    id: next_toggle
                                    on_release: app.toggle_feature('next', self.state == 'down')
                            Row:
                                size_hint_y: None
                                height: dp(30)
                                RowLabel:
                                    text: 'Watch each video for'
                                RowLabel:
                                    id: dwell_value
                                    text: '12 s'
                                    halign: 'right'
                                    bold: True
                                    color: ACCENT
                            Slider:
                                id: dwell_slider
                                min: 3
                                max: 60
                                step: 1
                                value: 12
                                size_hint_y: None
                                height: dp(36)
                                value_track: True
                                value_track_color: ACCENT
                                cursor_size: dp(26), dp(26)
                                on_value: app.on_dwell(self.value)
                            Btn:
                                text: 'Next video now'
                                tint: CARD2
                                on_release: app.next_video()
                        Card:
                            H2:
                                text: 'Skip weak videos'
                            Para:
                                text: 'When on, videos whose like or comment counts are below your minimums are skipped. If TikTok does not expose the counts, nothing is skipped.'
                            Row:
                                RowLabel:
                                    text: 'Skip by engagement'
                                Toggle:
                                    id: metric_toggle
                                    on_release: app.on_metric(self.state == 'down')
                            Row:
                                RowLabel:
                                    text: 'Minimum likes'
                                    size_hint_x: .6
                                Field:
                                    id: likes_input
                                    text: '1000'
                                    input_filter: 'int'
                                    halign: 'center'
                                    size_hint_x: .4
                                    on_text: app.on_min_likes(self.text)
                            Row:
                                RowLabel:
                                    text: 'Minimum comments'
                                    size_hint_x: .6
                                Field:
                                    id: comments_input
                                    text: '10'
                                    input_filter: 'int'
                                    halign: 'center'
                                    size_hint_x: .4
                                    on_text: app.on_min_comments(self.text)
                        Card:
                            H2:
                                text: 'Comments'
                            Para:
                                text: 'Fills the comment box for you. Posting stays manual unless you turn on Auto-post, which is capped at [b]5 comments per run[/b] with a cooldown. Automated posting can break TikTok rules, so use it sparingly.'
                            Row:
                                spacing: dp(6)
                                Seg:
                                    id: cm_off
                                    text: 'Off'
                                    group: 'cmode'
                                    allow_no_selection: False
                                    on_release: app.set_comment_mode('off')
                                Seg:
                                    id: cm_text
                                    text: 'My text'
                                    group: 'cmode'
                                    allow_no_selection: False
                                    on_release: app.set_comment_mode('text')
                                Seg:
                                    id: cm_ai
                                    text: 'AI'
                                    group: 'cmode'
                                    allow_no_selection: False
                                    on_release: app.set_comment_mode('ai')
                            Field:
                                id: comment_input
                                multiline: True
                                height: dp(92)
                                hint_text: 'Your comment (used in My text mode)'
                                on_text: app.on_comment_text(self.text)
                            Row:
                                RowLabel:
                                    text: 'Auto-post'
                                Toggle:
                                    id: post_toggle
                                    on_release: app.toggle_auto_post(self.state == 'down')
                            Row:
                                RowLabel:
                                    text: 'Cooldown (seconds)'
                                    size_hint_x: .6
                                Field:
                                    id: cooldown_input
                                    text: '60'
                                    input_filter: 'int'
                                    halign: 'center'
                                    size_hint_x: .4
                                    on_text: app.on_cooldown(self.text)
                            Btn:
                                text: 'Suggest a comment with AI'
                                tint: CARD2
                                on_release: app.analyze_screen()
                            Para:
                                text: app.ai_status
                                color: AMBER
            Screen:
                name: 'settings'
                ScrollView:
                    do_scroll_x: False
                    bar_width: dp(2)
                    Body:
                        Card:
                            H2:
                                text: 'Gemini API key'
                            Para:
                                text: 'Only needed for AI comment suggestions. The key is stored privately on this device and sent only to Google.'
                            Field:
                                id: key_input
                                password: True
                                hint_text: 'Paste your API key'
                            Row:
                                Btn:
                                    text: 'Save key'
                                    on_release: app.save_key()
                                Btn:
                                    text: 'Remove key'
                                    tint: CARD2
                                    on_release: app.clear_key()
                            Para:
                                text: app.key_status
                                color: AMBER
                        Card:
                            H2:
                                text: 'Safety'
                            Para:
                                text: 'Press a [b]volume key[/b] while something is running to stop everything instantly. Turn the Accessibility service off in Android settings to remove all access.'
                            Btn:
                                text: 'Stop everything now'
                                tint: (0.78, 0.18, 0.24, 1)
                                on_release: app.stop_everything()
                        Card:
                            H2:
                                text: 'More'
                            Btn:
                                text: 'Quick Settings tile help'
                                tint: CARD2
                                on_release: app.tile_help()
                            Btn:
                                text: 'Open app info'
                                tint: CARD2
                                on_release: app.open_app_info()
                            Btn:
                                text: 'Reset all settings'
                                tint: CARD2
                                on_release: app.reset_settings()
                            Para:
                                text: 'Auto Clicker Pro ' + app.version_text
                                halign: 'center'
        BoxLayout:
            size_hint_y: None
            height: dp(58)
            canvas.before:
                Color:
                    rgba: CARD
                Rectangle:
                    pos: self.pos
                    size: self.size
            NavBtn:
                id: nav_home
                text: 'Home'
                group: 'nav'
                state: 'down'
                on_release: app.go('home')
            NavBtn:
                id: nav_clicker
                text: 'Clicker'
                group: 'nav'
                on_release: app.go('clicker')
            NavBtn:
                id: nav_tiktok
                text: 'TikTok'
                group: 'nav'
                on_release: app.go('tiktok')
            NavBtn:
                id: nav_settings
                text: 'Settings'
                group: 'nav'
                on_release: app.go('settings')
    Label:
        id: snack
        text: ''
        opacity: 0
        size_hint: None, None
        text_size: Window.width - dp(64), None
        halign: 'center'
        size: self.texture_size[0] + dp(32), self.texture_size[1] + dp(22)
        pos_hint: {'center_x': .5}
        y: dp(76)
        color: TEXT
        canvas.before:
            Color:
                rgba: (0.2, 0.22, 0.29, 1)
            RoundedRectangle:
                pos: self.pos
                size: self.size
                radius: [dp(18)]
"""


# --------------------------------------------------------------------------- app

class AutoClickerApp(App):
    title = "Auto Clicker Pro"
    state_text = StringProperty("OFF")
    state_color = ListProperty(MUTED)
    state_ok = BooleanProperty(False)
    tap_text = StringProperty("No taps yet")
    engine_status = StringProperty("Show the floating controls to begin")
    overlay_on = BooleanProperty(False)
    overlay_btn_text = StringProperty("Show floating controls")
    running = BooleanProperty(False)
    pause_btn_text = StringProperty("Pause")
    ok_overlay = BooleanProperty(False)
    ok_access = BooleanProperty(False)
    ok_notif = BooleanProperty(False)
    ai_status = StringProperty("")
    key_status = StringProperty("")
    version_text = StringProperty(APP_VERSION)

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.native = Native()
        self.s = dict(DEFAULTS)
        self._loading = True
        self._tick = 0
        self._flush_event = None
        self._snack_anim = None
        self.current = "home"

    # ---- lifecycle
    def build(self):
        Window.softinput_mode = "below_target"
        self.root_widget = Builder.load_string(KV)
        self.ids = self.root_widget.ids
        self.load_settings()
        self.apply_to_widgets()
        self._loading = False
        Window.bind(on_keyboard=self._on_key)
        Clock.schedule_interval(self.refresh, 0.5)
        Clock.schedule_once(lambda dt: self.refresh(0), 0.1)
        Clock.schedule_once(lambda dt: self.push_native(), 0.5)
        return self.root_widget

    def on_pause(self):
        self.flush()
        return True

    def on_resume(self):
        self._tick = 0
        self.refresh(0)
        self.push_native()

    def on_stop(self):
        self.flush()

    def _on_key(self, window, key, *args):
        if key == 27:  # Android back button
            if self.current != "home":
                self.go("home")
                return True
            if self.native.ok:
                self.native.minimize()
                return True
        return False

    # ---- storage
    def _config_path(self):
        base = self.user_data_dir
        os.makedirs(base, exist_ok=True)
        return os.path.join(base, "clickmaster_settings.json")

    def load_settings(self):
        try:
            with open(self._config_path(), "r", encoding="utf-8") as fh:
                saved = json.load(fh)
            for key, default in DEFAULTS.items():
                if key in saved and isinstance(saved[key], type(default)):
                    self.s[key] = saved[key]
        except (OSError, ValueError):
            pass
        self.s["interval_ms"] = max(MIN_MS, min(600000, int(self.s["interval_ms"])))
        self.s["count"] = max(1, int(self.s["count"]))
        self.s["gesture"] = max(0, min(2, int(self.s["gesture"])))
        if self.s["comment_mode"] not in ("off", "text", "ai"):
            self.s["comment_mode"] = "off"

    def changed(self):
        if self._loading:
            return
        if self._flush_event is not None:
            self._flush_event.cancel()
        self._flush_event = Clock.schedule_once(lambda dt: self.flush(), 0.4)

    def flush(self):
        self._flush_event = None
        try:
            with open(self._config_path(), "w", encoding="utf-8") as fh:
                json.dump(self.s, fh)
        except OSError:
            pass
        self.push_native()

    def push_native(self):
        if self.native.ok:
            self.native.configure_engine(self.s)
            self.native.push_rules(self.s)

    def apply_to_widgets(self):
        s, ids = self.s, self.ids
        ids.interval_slider.value = ms_to_slider(s["interval_ms"])
        self._show_interval(s["interval_ms"])
        ids.count_input.text = str(s["count"])
        ids.continuous_toggle.state = "down" if s["continuous"] else "normal"
        self._select(["g0", "g1", "g2"], "g%d" % s["gesture"])
        ids.jitter_slider.value = s["jitter"]
        ids.jitter_value.text = "%d px" % s["jitter"]
        ids.delay_slider.value = s["start_delay"]
        ids.delay_value.text = "%d s" % s["start_delay"] if s["start_delay"] else "None"
        ids.dwell_slider.value = s["dwell_s"]
        ids.dwell_value.text = "%d s" % s["dwell_s"]
        ids.metric_toggle.state = "down" if s["metric_mode"] else "normal"
        ids.likes_input.text = str(s["min_likes"])
        ids.comments_input.text = str(s["min_comments"])
        self._select(["cm_off", "cm_text", "cm_ai"], "cm_" + s["comment_mode"])
        ids.comment_input.text = s["comment_text"]
        ids.cooldown_input.text = str(s["cooldown"])
        self.key_status = "A key is saved on this device." if self.native.has_api_key() else "No key saved yet."

    def reset_settings(self):
        self._loading = True
        self.s = dict(DEFAULTS)
        self.apply_to_widgets()
        self._loading = False
        self.flush()
        self.toast("Settings reset")

    # ---- navigation & feedback
    def go(self, name):
        self.current = name
        self.ids.sm.current = name
        self._select(["nav_home", "nav_clicker", "nav_tiktok", "nav_settings"], "nav_" + name)

    def _select(self, widget_ids, chosen):
        """Programmatic state changes do not release a ToggleButton group, so do it by hand."""
        for wid in widget_ids:
            self.ids[wid].state = "down" if wid == chosen else "normal"

    def toast(self, message, seconds=2.6):
        snack = self.ids.snack
        snack.text = message
        if self._snack_anim is not None:
            self._snack_anim.cancel(snack)
        anim = Animation(opacity=1, duration=0.15) + Animation(opacity=1, duration=seconds) + Animation(opacity=0, duration=0.3)
        self._snack_anim = anim
        anim.start(snack)

    def tile_help(self):
        self.toast("Swipe down twice, tap the pencil, then drag 'Auto Clicker' into your tiles.", 5)

    # ---- permissions & overlay
    def refresh_permissions(self):
        n = self.native
        if not n.ok:
            self.ok_overlay = self.ok_access = self.ok_notif = False
            return
        self.ok_overlay = n.overlay_granted()
        self.ok_access = n.service() is not None
        self.ok_notif = n.notifications_granted()

    def open_overlay_settings(self):
        if not self.native.ok or not self.native.open_overlay_settings():
            self.toast("Open Settings > Apps > Special access > Display over other apps")

    def open_accessibility_settings(self):
        if not self.native.ok or not self.native.open_accessibility_settings():
            self.toast("Open Settings > Accessibility and enable Auto Clicker Pro")

    def open_notification_settings(self):
        if self.native.ok and self.native.sdk >= 33 and not self.native.notifications_granted():
            self.native.request_notifications()
            Clock.schedule_once(lambda dt: self.refresh_permissions(), 1.0)
        elif not self.native.ok or not self.native.open_notification_settings():
            self.toast("Open Settings > Apps > Auto Clicker Pro > Notifications")

    def open_app_info(self):
        if not self.native.ok or not self.native.open_app_info():
            self.toast("Android only")

    def toggle_overlay(self):
        n = self.native
        if not n.ok:
            self.toast("Preview mode: floating controls only run on Android")
            return
        if n.overlay_active():
            n.close_overlay()
            self.toast("Floating controls closed")
            return
        if not n.overlay_granted():
            self.toast("Allow 'Display over other apps' first")
            n.open_overlay_settings()
            return
        if n.service() is None:
            self.toast("Now turn on Auto Clicker Pro under Accessibility")
            n.open_accessibility_settings()
            return
        n.request_notifications()
        self.push_native()
        n.launch_overlay()
        self.toast("Drag the red ring over your target, then press Start")
        Clock.schedule_once(n.minimize, 0.7)

    # ---- engine controls
    def pause_clicking(self):
        self.native.toggle_pause()

    def stop_clicking(self):
        self.native.stop_engine()

    def stop_everything(self):
        self.native.stop_engine()
        svc = self.native.service()
        if svc is not None:
            try:
                svc.setAutoLike(False)
                svc.setAutoNext(False)
                svc.configureCommentPosting(False, 5)
            except Exception:
                pass
        self.toast("Everything stopped")

    # ---- clicker settings
    def _show_interval(self, ms):
        rate = 1000.0 / ms
        text = "%d ms" % ms if ms < 1000 else "%.2f s" % (ms / 1000.0)
        self.ids.interval_value.text = "%s  (%.1f/s)" % (text, rate)

    def on_interval_slider(self, v):
        if self._loading:
            return
        ms = slider_to_ms(v)
        self.s["interval_ms"] = ms
        self._show_interval(ms)
        self.changed()

    def set_interval(self, ms):
        self.ids.interval_slider.value = ms_to_slider(ms)

    def on_count(self, text):
        if self._loading:
            return
        self.s["count"] = to_int(text, self.s["count"], 1, 1000000)
        self.changed()

    def on_continuous(self, on):
        self.s["continuous"] = bool(on)
        self.changed()

    def set_gesture(self, index):
        self.s["gesture"] = index
        self.changed()

    def on_jitter(self, v):
        self.ids.jitter_value.text = "%d px" % int(v)
        if not self._loading:
            self.s["jitter"] = int(v)
            self.changed()

    def on_delay(self, v):
        self.ids.delay_value.text = ("%d s" % int(v)) if int(v) else "None"
        if not self._loading:
            self.s["start_delay"] = int(v)
            self.changed()

    # ---- TikTok settings
    def on_dwell(self, v):
        self.ids.dwell_value.text = "%d s" % int(v)
        if not self._loading:
            self.s["dwell_s"] = int(v)
            self.changed()

    def on_metric(self, on):
        self.s["metric_mode"] = bool(on)
        self.changed()

    def on_min_likes(self, text):
        if not self._loading:
            self.s["min_likes"] = to_int(text, self.s["min_likes"])
            self.changed()

    def on_min_comments(self, text):
        if not self._loading:
            self.s["min_comments"] = to_int(text, self.s["min_comments"])
            self.changed()

    def set_comment_mode(self, mode):
        self.s["comment_mode"] = mode
        self.changed()

    def on_comment_text(self, text):
        if not self._loading:
            self.s["comment_text"] = text[:500]
            self.changed()

    def on_cooldown(self, text):
        if not self._loading:
            self.s["cooldown"] = to_int(text, self.s["cooldown"], 15, 86400)
            self.changed()

    def _need_service(self, toggle=None):
        svc = self.native.service()
        if svc is None:
            if toggle is not None:
                toggle.state = "normal"
            self.toast("Turn on the Accessibility service first")
            if self.native.ok:
                self.native.open_accessibility_settings()
        return svc

    def toggle_feature(self, feature, on):
        toggle = self.ids.like_toggle if feature == "like" else self.ids.next_toggle
        svc = self._need_service(toggle)
        if svc is None:
            return
        self.flush()
        if feature == "like":
            svc.setAutoLike(bool(on))
        else:
            svc.setAutoNext(bool(on))

    def toggle_auto_post(self, on):
        svc = self._need_service(self.ids.post_toggle)
        if svc is None:
            return
        if on and self.s["comment_mode"] == "off":
            self.ids.post_toggle.state = "normal"
            self.toast("Pick 'My text' or 'AI' first")
            return
        if on and self.s["comment_mode"] == "text" and not self.s["comment_text"].strip():
            self.ids.post_toggle.state = "normal"
            self.toast("Write your comment first")
            return
        self.flush()
        svc.configureCommentPosting(bool(on), 5)

    def next_video(self):
        svc = self._need_service()
        if svc is not None:
            svc.nextVideo()

    def analyze_screen(self):
        svc = self._need_service()
        if svc is None:
            return
        if not self.native.has_api_key():
            self.ai_status = "Save a Gemini API key in Settings first."
            return
        self.flush()
        self.ai_status = "Asking Gemini, one moment..."
        svc.analyzeCurrentScreen()

    # ---- API key
    def save_key(self):
        key = self.ids.key_input.text.strip()
        if not key:
            self.key_status = "Paste your key first."
            return
        if not self.native.ok:
            self.key_status = "Preview mode: keys are stored on Android only."
            return
        try:
            self.native.set_api_key(key)
            self.ids.key_input.text = ""
            self.key_status = "Key saved on this device."
        except Exception as exc:
            self.key_status = "Could not save the key: %s" % exc

    def clear_key(self):
        try:
            if self.native.ok:
                self.native.set_api_key("")
            self.ids.key_input.text = ""
            self.key_status = "Key removed."
        except Exception as exc:
            self.key_status = "Could not remove the key: %s" % exc

    # ---- live status
    def refresh(self, dt):
        n = self.native
        self._tick += 1
        if self._tick % 4 == 1:
            self.refresh_permissions()
        if not n.ok:
            self.state_text, self.state_color, self.state_ok = "PREVIEW", MUTED, False
            return
        st = n.engine()
        if st is None:
            return
        self.overlay_on = st["active"]
        self.running = st["running"]
        if st["running"] and st["paused"]:
            self.state_text, self.state_color, self.state_ok = "PAUSED", AMBER, True
        elif st["running"]:
            self.state_text, self.state_color, self.state_ok = "RUNNING", GREEN, True
        elif st["active"]:
            self.state_text, self.state_color, self.state_ok = "READY", GREEN, True
        else:
            self.state_text, self.state_color, self.state_ok = "OFF", MUTED, False
        self.overlay_btn_text = "Hide floating controls" if st["active"] else "Show floating controls"
        self.pause_btn_text = "Resume" if st["paused"] else "Pause"
        self.tap_text = ("%d taps" % st["taps"]) if (st["running"] or st["taps"]) else "No taps yet"
        if st["status"]:
            self.engine_status = st["status"]
        elif not st["active"]:
            self.engine_status = "Show the floating controls to begin"
        if st["vision"]:
            self.ai_status = st["vision"]
        svc = n.service()
        if svc is not None:
            try:
                self._sync_toggle(self.ids.like_toggle, bool(svc.isAutoLike()))
                self._sync_toggle(self.ids.next_toggle, bool(svc.isAutoNext()))
                self._sync_toggle(self.ids.post_toggle, bool(svc.isAutoPostingEnabled()))
            except Exception:
                pass
        else:
            for t in (self.ids.like_toggle, self.ids.next_toggle, self.ids.post_toggle):
                self._sync_toggle(t, False)

    @staticmethod
    def _sync_toggle(toggle, on):
        want = "down" if on else "normal"
        if toggle.state != want:
            toggle.state = want


if __name__ == "__main__":
    AutoClickerApp().run()
