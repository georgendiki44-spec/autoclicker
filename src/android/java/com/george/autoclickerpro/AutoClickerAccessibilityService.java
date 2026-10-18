package com.george.autoclickerpro;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class AutoClickerAccessibilityService extends AccessibilityService {
    private static volatile AutoClickerAccessibilityService instance;
    public static AutoClickerAccessibilityService getInstance() { return instance; }

    @Override protected void onServiceConnected() { super.onServiceConnected(); instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override public boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN &&
            (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN ||
             event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP)) {
            FloatingOverlayService.stopFromVolumeKey();
            return true;
        }
        return super.onKeyEvent(event);
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public boolean tap(float x, float y, AccessibilityService.GestureResultCallback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        Path path = new Path(); path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 20)).build();
        return dispatchGesture(gesture, callback, null);
    }
}