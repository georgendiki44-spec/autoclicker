package com.george.autoclickerpro;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

public class AutoClickerAccessibilityService extends AccessibilityService {
    private static volatile AutoClickerAccessibilityService instance;

    public static AutoClickerAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Window contents are not read; this service only dispatches user-requested taps.
    }

    @Override
    public void onInterrupt() {
        // Android interrupted gesture dispatch.
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public boolean tap(float x, float y, GestureDescription.GestureResultCallback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isServiceConnected()) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 80))
            .build();
        return dispatchGesture(gesture, callback, null);
    }
}
