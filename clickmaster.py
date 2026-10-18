import json
import os
import random
import threading
import time

from kivy.app import App
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.lang import Builder
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.label import Label
from kivy.uix.modalview import ModalView
from kivy.uix.slider import Slider
from kivy.uix.scrollview import ScrollView
from kivy.uix.textinput import TextInput
from kivy.uix.togglebutton import ToggleButton

try:
    import pyautogui as pag
except ImportError:
    pag = None

def get_config_path():
    """Return a writable per-user settings path on desktop and Android."""
    app = App.get_running_app()
    base_dir = app.user_data_dir if app is not None else os.path.dirname(os.path.abspath(__file__))
    os.makedirs(base_dir, exist_ok=True)
    return os.path.join(base_dir, "clickmaster_settings.json")

KV = """
<AutoClickerRoot>:
    do_scroll_x: False
    do_scroll_y: True
    bar_width: '4dp'
    BoxLayout:
        size_hint_y: None
        height: self.minimum_height
        orientation: 'vertical'
        padding: '16dp'
        canvas.before:
            Color:
                rgba: (0.055, 0.065, 0.085, 1)
            Rectangle:
                pos: self.pos
                size: self.size
        ScrollView:
            do_scroll_x: False
            bar_width: '4dp'
            GridLayout:
                cols: 1
                spacing: '10dp'
                padding: '2dp'
                size_hint_y: None
                height: self.minimum_height
                Label:
                    text: 'AUTO CLICKER PRO'
                    bold: True
                    font_size: '24sp'
                    color: (1, 0.28, 0.30, 1)
                    size_hint_y: None
                    height: '48dp'
                Label:
                    text: 'Floating controls for other apps'
                    font_size: '14sp'
                    color: (0.78, 0.80, 0.84, 1)
                    size_hint_y: None
                    height: '30dp'
                Button:
                    text: '1. Allow display over other apps'
                    size_hint_y: None
                    height: '48dp'
                    on_release: app.open_overlay_settings()
                Button:
                    text: '2. Enable Auto Clicker accessibility'
                    size_hint_y: None
                    height: '48dp'
                    on_release: app.open_accessibility_settings()
                Button:
                    text: 'Show floating controls now'
                    size_hint_y: None
                    height: '52dp'
                    background_color: (0.78, 0.12, 0.16, 1)
                    on_release: app.start_native_overlay()
                Label:
                    text: 'Quick Settings tile'
                    bold: True
                    font_size: '18sp'
                    color: (1, 1, 1, 1)
                    size_hint_y: None
                    height: '32dp'
                Label:
                    text: 'After enabling both permissions, pull down Quick Settings, tap Edit, and add Auto Clicker. Tap the tile to open floating controls.'
                    text_size: self.width, None
                    halign: 'left'
                    valign: 'middle'
                    font_size: '14sp'
                    color: (0.82, 0.84, 0.87, 1)
                    size_hint_y: None
                    height: self.texture_size[1] + dp(18)
                Label:
                    text: 'AI VIDEO ASSISTANT'
                    bold: True
                    font_size: '20sp'
                    color: (1, 0.28, 0.30, 1)
                    size_hint_y: None
                    height: '38dp'
                Label:
                    text: 'Configure Gemini here, then analyze the video from the main app. Your key stays on this device.'
                    text_size: self.width, None
                    halign: 'left'
                    valign: 'middle'
                    font_size: '14sp'
                    color: (0.82, 0.84, 0.87, 1)
                    size_hint_y: None
                    height: self.texture_size[1] + dp(12)
                TextInput:
                    id: gemini_key_input
                    hint_text: 'Paste Gemini API key'
                    password: True
                    multiline: False
                    size_hint_y: None
                    height: '48dp'
                    background_color: (0.12, 0.14, 0.18, 1)
                    foreground_color: (1, 1, 1, 1)
                    hint_text_color: (0.65, 0.68, 0.72, 1)
                    padding: '12dp', '12dp'
                BoxLayout:
                    size_hint_y: None
                    height: '48dp'
                    spacing: '8dp'
                    Button:
                        text: 'Save API key'
                        background_color: (0.78, 0.12, 0.16, 1)
                        on_release: app.save_gemini_key()
                    Button:
                        text: 'AI Analyze'
                        on_release: app.analyze_current_screen()
                BoxLayout:
                    size_hint_y: None
                    height: '48dp'
                    spacing: '8dp'
                    Button:
                        id: auto_like_button
                        text: 'Auto Like: OFF'
                        on_release: app.toggle_native_feature('like')
                    Button:
                        id: auto_next_button
                        text: 'Auto Next: OFF'
                        on_release: app.toggle_native_feature('next')
                Button:
                    text: 'Next video'
                    size_hint_y: None
                    height: '46dp'
                    on_release: app.next_video()
                Label:
                    id: ai_status_label
                    text: 'AI status: Save your Gemini key to begin'
                    text_size: self.width, None
                    halign: 'left'
                    valign: 'middle'
                    font_size: '13sp'
                    color: (1, 0.72, 0.30, 1)
                    size_hint_y: None
                    height: self.texture_size[1] + dp(12)
                Label:
                    text: 'SMART VIDEO RULES'
                    bold: True
                    font_size: '20sp'
                    color: (1, 0.28, 0.30, 1)
                    size_hint_y: None
                    height: '38dp'
                Label:
                    text: 'Metrics are read from TikTok accessibility labels when available. Unknown metrics will not trigger metric-based actions.'
                    text_size: self.width, None
                    halign: 'left'
                    valign: 'middle'
                    font_size: '13sp'
                    color: (0.82, 0.84, 0.87, 1)
                    size_hint_y: None
                    height: self.texture_size[1] + dp(10)
                BoxLayout:
                    size_hint_y: None
                    height: '44dp'
                    spacing: '8dp'
                    Label:
                        text: 'Minimum likes'
                    TextInput:
                        id: min_likes_input
                        text: '1000'
                        input_filter: 'int'
                        multiline: False
                        halign: 'center'
                BoxLayout:
                    size_hint_y: None
                    height: '44dp'
                    spacing: '8dp'
                    Label:
                        text: 'Minimum comments'
                    TextInput:
                        id: min_comments_input
                        text: '10'
                        input_filter: 'int'
                        multiline: False
                        halign: 'center'
                BoxLayout:
                    size_hint_y: None
                    height: '44dp'
                    spacing: '8dp'
                    Label:
                        text: 'Comment cooldown (sec)'
                    TextInput:
                        id: comment_cooldown_input
                        text: '60'
                        input_filter: 'int'
                        multiline: False
                        halign: 'center'
                Label:
                    text: 'Reusable comment (up to 500 characters)'
                    size_hint_y: None
                    height: '28dp'
                    halign: 'left'
                TextInput:
                    id: reusable_comment_input
                    hint_text: 'Write a comment for qualifying videos'
                    multiline: True
                    size_hint_y: None
                    height: '76dp'
                BoxLayout:
                    size_hint_y: None
                    height: '48dp'
                    spacing: '8dp'
                    ToggleButton:
                        id: metric_mode_toggle
                        text: 'Metric skip: OFF'
                        state: 'normal'
                        on_release: app.toggle_metric_mode()
                    ToggleButton:
                        id: auto_comment_toggle
                        text: 'AI suggestions: OFF'
                        state: 'normal'
                        on_release: app.toggle_auto_comment()
                    ToggleButton:
                        id: template_comment_toggle
                        text: 'Reusable comment: OFF'
                        state: 'normal'
                        on_release: app.toggle_template_comment()
                Button:
                    text: 'Save video rules'
                    size_hint_y: None
                    height: '46dp'
                    background_color: (0.78, 0.12, 0.16, 1)
                    on_release: app.save_automation_rules()
                Label:
                    text: 'CLICK SETTINGS'
                    bold: True
                    font_size: '20sp'
                    color: (1, 0.28, 0.30, 1)
                    size_hint_y: None
                    height: '38dp'
                Label:
                    text: 'Desktop test settings'
                    bold: True
                    font_size: '17sp'
                    color: (1, 1, 1, 1)
                    size_hint_y: None
                    height: '30dp'
                BoxLayout:
                    size_hint_y: None
                    height: '42dp'
                    Label:
                        text: 'Tap count'
                    TextInput:
                        id: taps_input
                        text: '50'
                        input_filter: 'int'
                        multiline: False
                        halign: 'center'
                BoxLayout:
                    size_hint_y: None
                    height: '42dp'
                    Label:
                        text: 'Continuous'
                    ToggleButton:
                        id: infinite_toggle
                        text: 'OFF'
                        state: 'normal'
                BoxLayout:
                    size_hint_y: None
                    height: '42dp'
                    Label:
                        text: 'Delay (seconds)'
                    Slider:
                        id: delay_slider
                        min: 0.02
                        max: 2.0
                        value: 0.5
                        step: 0.01
                BoxLayout:
                    size_hint_y: None
                    height: '42dp'
                    Label:
                        text: 'Radius'
                    Slider:
                        id: radius_slider
                        min: 0
                        max: 50
                        value: 5
                        step: 1
                BoxLayout:
                    size_hint_y: None
                    height: '42dp'
                    Label:
                        text: 'Click type'
                    ToggleButton:
                        id: click_type_toggle
                        text: 'Single'
                        state: 'down'
                Label:
                    id: target_label
                    text: 'Target: not selected'
                    size_hint_y: None
                    height: '30dp'
                Label:
                    id: status_label
                    text: 'Ready'
                    size_hint_y: None
                    height: '30dp'
                Label:
                    id: counter_label
                    text: 'Clicks: 0'
                    size_hint_y: None
                    height: '30dp'
                BoxLayout:
                    size_hint_y: None
                    height: '46dp'
                    Button:
                        text: 'Save setup'
                        on_release: app.save_settings()
                    Button:
                        text: 'Load setup'
                        on_release: app.load_settings()
"""


class QuickSettingsOverlay(ModalView):
    def __init__(self, app, **kwargs):
        super().__init__(**kwargs)
        self.app = app
        self.size_hint = (None, None)
        self.size = (260, 80)
        self.auto_dismiss = True
        self.background_color = (0, 0, 0, 0.0)
        self.modal = False
        self.opacity = 0.96

        panel = FloatLayout(size=self.size)
        panel.canvas.before.clear()

        toolbar = BoxLayout(orientation='horizontal', padding=8, spacing=8, size_hint=(1, 1))
        toolbar.canvas.before.clear()

        self.quick_buttons = {
            'start': Button(text='▶', bold=True, background_color=(0.2, 0.6, 1, 1)),
            'pause': Button(text='⏸', bold=True, background_color=(1, 0.6, 0.1, 1)),
            'stop': Button(text='■', bold=True, background_color=(1, 0.2, 0.2, 1)),
            'mode': Button(text='⚡', bold=True, background_color=(0.4, 0.8, 0.4, 1)),
        }

        for key, button in self.quick_buttons.items():
            button.bind(on_release=lambda instance, k=key: self.on_action(k))
            toolbar.add_widget(button)

        panel.add_widget(toolbar)
        self.add_widget(panel)

    def on_action(self, key):
        if key == 'start':
            self.app.start_clicking()
        elif key == 'pause':
            self.app.pause_clicking()
        elif key == 'stop':
            self.app.stop_clicking()
        elif key == 'mode':
            self.app.infinite_mode = not self.app.infinite_mode
            self.app.infinite_toggle.state = 'down' if self.app.infinite_mode else 'normal'
            self.app.infinite_toggle.text = 'ON' if self.app.infinite_mode else 'OFF'
            self.app.show_status('Quick mode ' + ('ON' if self.app.infinite_mode else 'OFF'))

    def reposition(self):
        if not self.parent:
            return
        self.pos = (max((Window.width - self.width) / 2, 20), max(Window.height - self.height - 20, 20))


class AutoClickerRoot(ScrollView):
    pass


class AutoClickerApp(App):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.clicking = False
        self.paused = False
        self.target_x = None
        self.target_y = None
        self.click_count = 0
        self.infinite_mode = False
        self.lock = threading.Lock()
        self.current_pattern = 'single'
        self.use_android_vibration = False
        self.android_vibrator = None
        self.quick_panel = None
        self.torch_enabled = False
        self.wifi_enabled = False

    def build(self):
        # Register the KV class rule, then instantiate the actual root widget.
        Builder.load_string(KV)
        self.root_widget = AutoClickerRoot()
        self.bind_widgets()
        self.load_settings()
        self.configure_android()
        Window.bind(on_resize=self._on_window_resize)
        return self.root_widget

    def bind_widgets(self):
        self.taps_input = self.root_widget.ids.taps_input
        self.infinite_toggle = self.root_widget.ids.infinite_toggle
        self.delay_slider = self.root_widget.ids.delay_slider
        self.radius_slider = self.root_widget.ids.radius_slider
        self.click_type_toggle = self.root_widget.ids.click_type_toggle
        self.target_label = self.root_widget.ids.target_label
        self.status_label = self.root_widget.ids.status_label
        self.counter_label = self.root_widget.ids.counter_label
        self.gemini_key_input = self.root_widget.ids.gemini_key_input
        self.ai_status_label = self.root_widget.ids.ai_status_label
        self.auto_like_button = self.root_widget.ids.auto_like_button
        self.auto_next_button = self.root_widget.ids.auto_next_button
        self.min_likes_input = self.root_widget.ids.min_likes_input
        self.min_comments_input = self.root_widget.ids.min_comments_input
        self.comment_cooldown_input = self.root_widget.ids.comment_cooldown_input
        self.metric_mode_toggle = self.root_widget.ids.metric_mode_toggle
        self.auto_comment_toggle = self.root_widget.ids.auto_comment_toggle
        self.reusable_comment_input = self.root_widget.ids.reusable_comment_input
        self.template_comment_toggle = self.root_widget.ids.template_comment_toggle

        self.infinite_toggle.bind(on_release=self.on_toggle_infinite)
        self.click_type_toggle.bind(on_release=self.on_toggle_click_type)

    def on_toggle_infinite(self, instance):
        self.infinite_mode = instance.state == 'down'
        instance.text = 'ON' if self.infinite_mode else 'OFF'

    def on_toggle_click_type(self, instance):
        # This is a single button, so cycle through the supported patterns.
        patterns = [('single', 'Single'), ('double', 'Double'), ('press', 'Press')]
        current_index = next(
            (index for index, (pattern, _) in enumerate(patterns)
             if pattern == self.current_pattern),
            0,
        )
        self.current_pattern, instance.text = patterns[(current_index + 1) % len(patterns)]
        instance.state = 'down'

    def open_overlay_settings(self):
        try:
            from jnius import autoclass
            activity = autoclass('org.kivy.android.PythonActivity').mActivity
            Intent = autoclass('android.content.Intent')
            Uri = autoclass('android.net.Uri')
            Settings = autoclass('android.provider.Settings')
            intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.setData(Uri.parse('package:' + str(activity.getPackageName())))
            activity.startActivity(intent)
        except Exception as exc:
            self.set_status(f'Open Android Settings manually: {exc}', (1, 0.3, 0.3, 1))

    def open_accessibility_settings(self):
        try:
            from jnius import autoclass
            activity = autoclass('org.kivy.android.PythonActivity').mActivity
            Intent = autoclass('android.content.Intent')
            activity.startActivity(Intent('android.settings.ACCESSIBILITY_SETTINGS'))
        except Exception as exc:
            self.set_status(f'Open Accessibility settings manually: {exc}', (1, 0.3, 0.3, 1))

    def start_native_overlay(self):
        try:
            from jnius import autoclass
            activity = autoclass('org.kivy.android.PythonActivity').mActivity
            Intent = autoclass('android.content.Intent')
            service = autoclass('com.george.autoclickerpro.FloatingOverlayService')
            activity.startService(Intent(activity, service))
            self.show_status('Opening floating controls')
        except Exception as exc:
            self.set_status(f'Allow overlay permission first: {exc}', (1, 0.3, 0.3, 1))

    def save_gemini_key(self):
        key = self.gemini_key_input.text.strip()
        if not key:
            self.ai_status_label.text = 'AI status: Enter your Gemini API key first'
            return
        try:
            from jnius import autoclass
            activity = autoclass('org.kivy.android.PythonActivity').mActivity
            prefs = activity.getSharedPreferences('autoclicker_private', 0)
            editor = prefs.edit()
            editor.putString('gemini_api_key', key)
            editor.apply()
            self.ai_status_label.text = 'AI status: API key saved privately on this device'
            self.gemini_key_input.text = ''
        except Exception as exc:
            self.ai_status_label.text = 'AI status: Could not save key on Android: ' + str(exc)

    def analyze_current_screen(self):
        try:
            from jnius import autoclass
            activity = autoclass('org.kivy.android.PythonActivity').mActivity
            service = autoclass('com.george.autoclickerpro.AutoClickerAccessibilityService')
            instance = service.getInstance()
            if instance is None:
                self.ai_status_label.text = 'AI status: Enable Auto Clicker in Accessibility settings first'
                return
            self.ai_status_label.text = 'AI status: Capturing screen and asking Gemini…'
            instance.analyzeCurrentScreen()
            Clock.schedule_interval(self.refresh_ai_status, 1.0)
        except Exception as exc:
            self.ai_status_label.text = 'AI status: ' + str(exc)

    def refresh_ai_status(self, dt):
        try:
            from jnius import autoclass
            overlay = autoclass('com.george.autoclickerpro.FloatingOverlayService')
            status = str(overlay.getVisionStatus())
            if status and status != 'null':
                self.ai_status_label.text = 'AI status: ' + status
        except Exception:
            pass
        return True

    def toggle_native_feature(self, feature):
        try:
            from jnius import autoclass
            service = autoclass('com.george.autoclickerpro.AutoClickerAccessibilityService')
            instance = service.getInstance()
            if instance is None:
                self.ai_status_label.text = 'Enable Accessibility service first'
                return
            if feature == 'like':
                enabled = not bool(instance.isAutoLike())
                instance.setAutoLike(enabled)
                self.auto_like_button.text = 'Auto Like: ' + ('ON' if enabled else 'OFF')
            elif feature == 'next':
                enabled = not bool(instance.isAutoNext())
                instance.setAutoNext(enabled)
                self.auto_next_button.text = 'Auto Next: ' + ('ON' if enabled else 'OFF')
        except Exception as exc:
            self.ai_status_label.text = 'Could not change setting: ' + str(exc)

    def _native_accessibility(self):
        from jnius import autoclass
        service = autoclass('com.george.autoclickerpro.AutoClickerAccessibilityService')
        return service.getInstance()

    def save_automation_rules(self):
        try:
            likes = max(0, int(self.min_likes_input.text or '1000'))
            comments = max(0, int(self.min_comments_input.text or '10'))
            cooldown = max(15, min(86400, int(self.comment_cooldown_input.text or '60')))
            self._save_local_rule_defaults(likes, comments, cooldown)
            service = self._native_accessibility()
            if service is None:
                self.ai_status_label.text = 'Rules saved in app. Enable Accessibility to activate automation.'
                return
            service.configureAutomationRules(likes, comments, cooldown,
                self.metric_mode_toggle.state == 'down',
                self.auto_comment_toggle.state == 'down')
            service.configureCommentAutomation(self.reusable_comment_input.text,
                self.template_comment_toggle.state == 'down',
                self.auto_comment_toggle.state == 'down')
            self.ai_status_label.text = 'Video and comment rules saved • posting stays manual'
        except Exception as exc:
            self.ai_status_label.text = 'Could not save video rules: ' + str(exc)

    def _save_local_rule_defaults(self, likes, comments, cooldown):
        try:
            path = get_config_path()
            try:
                with open(path, 'r', encoding='utf-8') as fh:
                    payload = json.load(fh)
            except (OSError, json.JSONDecodeError):
                payload = {}
            payload.update({'minimum_likes': likes, 'minimum_comments': comments,
                'comment_cooldown_seconds': cooldown,
                'metric_mode': self.metric_mode_toggle.state == 'down',
                'auto_comment_suggestions': self.auto_comment_toggle.state == 'down',
                'reusable_comment': self.reusable_comment_input.text,
                'template_comment_enabled': self.template_comment_toggle.state == 'down'})
            with open(path, 'w', encoding='utf-8') as fh:
                json.dump(payload, fh)
        except Exception:
            pass

    def toggle_metric_mode(self):
        enabled = self.metric_mode_toggle.state == 'down'
        self.metric_mode_toggle.text = 'Metric skip: ' + ('ON' if enabled else 'OFF')
        self.save_automation_rules()

    def toggle_auto_comment(self):
        enabled = self.auto_comment_toggle.state == 'down'
        self.auto_comment_toggle.text = 'AI suggestions: ' + ('ON' if enabled else 'OFF')
        self.save_automation_rules()

    def toggle_template_comment(self):
        enabled = self.template_comment_toggle.state == 'down'
        self.template_comment_toggle.text = 'Reusable comment: ' + ('ON' if enabled else 'OFF')
        self.save_automation_rules()

    def next_video(self):
        try:
            from jnius import autoclass
            service = autoclass('com.george.autoclickerpro.AutoClickerAccessibilityService')
            instance = service.getInstance()
            if instance is None:
                self.ai_status_label.text = 'Enable Accessibility service first'
                return
            instance.nextVideo()
            self.ai_status_label.text = 'AI status: Requested next video'
        except Exception as exc:
            self.ai_status_label.text = 'Could not skip video: ' + str(exc)

    def configure_android(self):
        try:
            from jnius import autoclass
            self.android_vibrator = autoclass('android.os.Vibrator')
            self.use_android_vibration = True
        except Exception:
            self.use_android_vibration = False

    def _on_window_resize(self, *args):
        if self.quick_panel is not None and self.quick_panel.parent is not None:
            self.quick_panel.reposition()

    def toggle_quick_panel(self):
        if self.quick_panel is None:
            self.quick_panel = QuickSettingsOverlay(self)
            self.quick_panel.reposition()
            self.quick_panel.open()
        elif self.quick_panel.parent is not None:
            self.quick_panel.dismiss()
        else:
            self.quick_panel.reposition()
            self.quick_panel.open()

    def save_settings(self):
        payload = {
            'taps': self.taps_input.text,
            'infinite': self.infinite_mode,
            'delay': self.delay_slider.value,
            'radius': self.radius_slider.value,
            'click_type': self.current_pattern,
            'target': [self.target_x, self.target_y],
            'minimum_likes': int(self.min_likes_input.text or '1000'),
            'minimum_comments': int(self.min_comments_input.text or '10'),
            'comment_cooldown_seconds': int(self.comment_cooldown_input.text or '60'),
            'metric_mode': self.metric_mode_toggle.state == 'down',
            'auto_comment_suggestions': self.auto_comment_toggle.state == 'down',
        }
        try:
            with open(get_config_path(), 'w', encoding='utf-8') as fh:
                json.dump(payload, fh)
        except (OSError, TypeError, ValueError) as exc:
            self.set_status(f'Could not save settings: {exc}', (1, 0.3, 0.3, 1))
            return
        self.show_status('Settings saved')

    def load_settings(self):
        try:
            with open(get_config_path(), 'r', encoding='utf-8') as fh:
                payload = json.load(fh)
        except FileNotFoundError:
            return
        except (OSError, json.JSONDecodeError) as exc:
            self.set_status(f'Could not load settings: {exc}', (1, 0.3, 0.3, 1))
            return

        self.taps_input.text = str(payload.get('taps', 50))
        self.infinite_mode = bool(payload.get('infinite', False))
        self.infinite_toggle.state = 'down' if self.infinite_mode else 'normal'
        self.infinite_toggle.text = 'ON' if self.infinite_mode else 'OFF'
        self.delay_slider.value = float(payload.get('delay', 0.5))
        self.radius_slider.value = float(payload.get('radius', 5))
        saved_type = payload.get('click_type', 'single')
        self.current_pattern = saved_type
        self.click_type_toggle.text = 'Single' if saved_type == 'single' else 'Double' if saved_type == 'double' else 'Press'
        target = payload.get('target')
        if target and len(target) == 2:
            self.target_x, self.target_y = target
            self.target_label.text = f'Target: ({self.target_x}, {self.target_y})'
        self.min_likes_input.text = str(payload.get('minimum_likes', 1000))
        self.min_comments_input.text = str(payload.get('minimum_comments', 10))
        self.comment_cooldown_input.text = str(payload.get('comment_cooldown_seconds', 60))
        metric_mode = bool(payload.get('metric_mode', False))
        auto_comments = bool(payload.get('auto_comment_suggestions', False))
        self.reusable_comment_input.text = str(payload.get('reusable_comment', ''))
        template_enabled = bool(payload.get('template_comment_enabled', False))
        self.template_comment_toggle.state = 'down' if template_enabled else 'normal'
        self.template_comment_toggle.text = 'Reusable comment: ' + ('ON' if template_enabled else 'OFF')
        self.metric_mode_toggle.state = 'down' if metric_mode else 'normal'
        self.metric_mode_toggle.text = 'Metric skip: ' + ('ON' if metric_mode else 'OFF')
        self.auto_comment_toggle.state = 'down' if auto_comments else 'normal'
        self.auto_comment_toggle.text = 'AI suggestions: ' + ('ON' if auto_comments else 'OFF')
        self.show_status('Settings loaded')

    def show_status(self, message):
        self.status_label.text = message

    def set_status(self, message, color=(0.8, 0.8, 0.8, 1)):
        self.status_label.text = message
        self.status_label.color = color

    def vibrate(self, ms=50):
        if self.use_android_vibration:
            try:
                service = self.android_vibrator.maybeCreateVibrator()
                if service:
                    service.vibrate(ms)
            except Exception:
                pass

    def select_target(self):
        self.set_status('Tap anywhere on the screen to set target', (0.3, 0.9, 0.7, 1))
        self.root_widget.bind(on_touch_down=self._handle_target_touch)

    def _handle_target_touch(self, widget, touch):
        self.target_x = int(touch.x)
        self.target_y = int(touch.y)
        self.target_label.text = f'Target: ({self.target_x}, {self.target_y})'
        self.root_widget.unbind(on_touch_down=self._handle_target_touch)
        self.set_status('Target selected', (0.3, 0.9, 0.7, 1))

    def start_clicking(self):
        if self.target_x is None or self.target_y is None:
            self.set_status('Please select a target first', (1, 0.6, 0.2, 1))
            return

        try:
            taps = int(self.taps_input.text)
            if taps <= 0:
                raise ValueError
        except ValueError:
            self.set_status('Enter a valid tap count', (1, 0.3, 0.3, 1))
            return

        with self.lock:
            self.click_count = 0
            self.clicking = True
            self.paused = False

        self.set_status('Running', (0.3, 0.9, 0.5, 1))
        self.counter_label.text = f'Clicks: {self.click_count}'
        self._click_loop(taps)

    def _click_loop(self, target_taps):
        try:
            delay = max(float(self.delay_slider.value), 0.02)
            radius = int(self.radius_slider.value)
            infinite = self.infinite_mode

            def trigger(dt):
                if not self.clicking:
                    return False
                if self.paused:
                    return True

                if self.target_x is None or self.target_y is None:
                    return False

                click_x = self.target_x + random.randint(-radius, radius)
                click_y = self.target_y + random.randint(-radius, radius)

                if pag is not None:
                    try:
                        if self.current_pattern == 'double':
                            pag.click(click_x, click_y)
                            time.sleep(0.05)
                            pag.click(click_x, click_y)
                        elif self.current_pattern == 'press':
                            pag.mouseDown(click_x, click_y)
                            time.sleep(0.12)
                            pag.mouseUp(click_x, click_y)
                        else:
                            pag.click(click_x, click_y)
                    except Exception:
                        pass
                else:
                    print(f'Virtual tap at ({click_x}, {click_y})')

                self.click_count += 1
                self.counter_label.text = f'Clicks: {self.click_count}' if not infinite else f'Clicks: {self.click_count} (∞)'

                if not infinite and self.click_count >= target_taps:
                    self.clicking = False
                    self.set_status('Complete', (0.3, 0.9, 0.5, 1))
                    self.vibrate(80)
                    return False

                return True

            self._click_event = Clock.schedule_interval(trigger, delay)
        except Exception as exc:
            self.set_status(f'Error: {exc}', (1, 0.3, 0.3, 1))

    def pause_clicking(self):
        if not self.clicking:
            return

        self.paused = not self.paused
        if self.paused:
            self.set_status('Paused', (1, 0.7, 0.2, 1))
        else:
            self.set_status('Running', (0.3, 0.9, 0.5, 1))

    def stop_clicking(self):
        self.clicking = False
        self.paused = False
        if hasattr(self, '_click_event'):
            self._click_event.cancel()
        self.set_status('Stopped', (0.8, 0.8, 0.8, 1))
        self.counter_label.text = f'Clicks: {self.click_count}'


if __name__ == '__main__':
    AutoClickerApp().run()
