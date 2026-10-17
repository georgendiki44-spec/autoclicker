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
    orientation: 'vertical'
    spacing: 12
    padding: 18
    canvas.before:
        Color:
            rgba: (0.06, 0.08, 0.12, 1)
        Rectangle:
            pos: self.pos
            size: self.size

    BoxLayout:
        size_hint_y: None
        height: '52dp'
        Label:
            text: 'Auto Clicker Pro'
            bold: True
            font_size: '22sp'
            color: (0.3, 0.7, 1, 1)

    BoxLayout:
        orientation: 'vertical'
        spacing: 10
        size_hint_y: None
        height: '200dp'
        canvas.before:
            Color:
                rgba: (0.12, 0.15, 0.22, 1)
            RoundedRectangle:
                pos: self.pos
                size: self.size
                radius: [14, 14, 14, 14]

        BoxLayout:
            size_hint_y: None
            height: '42dp'
            Label:
                text: 'Target clicks:'
                size_hint_x: 0.45
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
                text: 'Infinite mode'
                size_hint_x: 0.75
            ToggleButton:
                id: infinite_toggle
                text: 'OFF'
                state: 'normal'
                group: 'infinite'
        BoxLayout:
            size_hint_y: None
            height: '44dp'
            Label:
                text: 'Delay (s):'
                size_hint_x: 0.45
            Slider:
                id: delay_slider
                min: 0.02
                max: 2.0
                value: 0.5
                step: 0.01
        BoxLayout:
            size_hint_y: None
            height: '44dp'
            Label:
                text: 'Radius:'
                size_hint_x: 0.45
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
                text: 'Click type:'
                size_hint_x: 0.45
            ToggleButton:
                id: click_type_toggle
                text: 'Single'
                state: 'down'
                group: 'click_type'

    BoxLayout:
        orientation: 'vertical'
        spacing: 10
        size_hint_y: None
        height: '102dp'
        canvas.before:
            Color:
                rgba: (0.12, 0.15, 0.22, 1)
            RoundedRectangle:
                pos: self.pos
                size: self.size
                radius: [14, 14, 14, 14]

        Button:
            id: target_button
            text: 'Select target position'
            on_release: app.select_target()

        Label:
            id: target_label
            text: 'Target: not selected'
            color: (0.8, 0.8, 0.8, 1)

    BoxLayout:
        spacing: 10
        size_hint_y: None
        height: '60dp'
        Button:
            id: start_button
            text: 'Start'
            on_release: app.start_clicking()
        Button:
            id: pause_button
            text: 'Pause'
            on_release: app.pause_clicking()
        Button:
            id: stop_button
            text: 'Stop'
            on_release: app.stop_clicking()

    BoxLayout:
        size_hint_y: None
        height: '70dp'
        Label:
            id: status_label
            text: 'Ready'
            bold: True
            font_size: '18sp'
            color: (0.8, 0.8, 0.8, 1)

    BoxLayout:
        size_hint_y: None
        height: '70dp'
        Label:
            id: counter_label
            text: 'Clicks: 0'
            font_size: '16sp'
            color: (0.8, 0.8, 0.8, 1)

    BoxLayout:
        size_hint_y: None
        height: '52dp'
        Button:
            text: 'Save setup'
            on_release: app.save_settings()
        Button:
            text: 'Load setup'
            on_release: app.load_settings()

    BoxLayout:
        size_hint_y: None
        height: '52dp'
        Button:
            text: 'Quick overlay'
            on_release: app.toggle_quick_panel()
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


class AutoClickerRoot(BoxLayout):
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
