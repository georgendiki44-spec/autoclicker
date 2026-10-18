package com.george.autoclickerpro;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Locale;

public class AutoClickerAccessibilityService extends AccessibilityService {
    private static volatile AutoClickerAccessibilityService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean autoNext = false, autoLike = false;
    private String lastScreenSummary = "Vision idle";
    private long lastAutoLikeAt = 0L, lastAutoNextAt = 0L;

    public static AutoClickerAccessibilityService getInstance() { return instance; }
    @Override protected void onServiceConnected() {
        super.onServiceConnected(); instance = this;
        FloatingOverlayService.updateVisionStatus("Vision ready • open TikTok");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        boolean tikTok = pkg != null && pkg.toString().toLowerCase(Locale.ROOT).contains("tiktok");
        if (!tikTok) { FloatingOverlayService.updateVisionStatus("Waiting for TikTok"); return; }
        String summary = describeScreen(root);
        if (!summary.equals(lastScreenSummary)) {
            lastScreenSummary = summary;
            FloatingOverlayService.updateVisionStatus("TikTok detected • " + summary);
        }
        if (autoLike && event != null &&
            (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
             event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
            long now = System.currentTimeMillis();
            if (now - lastAutoLikeAt > 1800L && clickUnlikedLikeButton(root)) lastAutoLikeAt = now;
        }
    }

    private String describeScreen(AccessibilityNodeInfo root) {
        StringBuilder out = new StringBuilder(); collectLabels(root, out, 0);
        String s = out.toString().toLowerCase(Locale.ROOT);
        if (s.contains("for you")) return "For You feed";
        if (s.contains("following")) return "Following feed";
        if (s.contains("comment")) return "Video controls visible";
        return "screen inspected";
    }

    private void collectLabels(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > 18 || out.length() > 2500) return;
        CharSequence text = node.getText(), desc = node.getContentDescription();
        if (text != null && text.length() > 0) out.append(text).append(' ');
        if (desc != null && desc.length() > 0) out.append(desc).append(' ');
        for (int i=0; i<node.getChildCount(); i++) collectLabels(node.getChild(i), out, depth+1);
    }

    private boolean clickUnlikedLikeButton(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence desc = node.getContentDescription(), text = node.getText();
        String label = ((desc == null ? "" : desc.toString()) + " " +
            (text == null ? "" : text.toString())).trim().toLowerCase(Locale.ROOT);
        if ((label.equals("like") || label.startsWith("like ") || label.contains(" like video")) &&
            !label.contains("unlike") && node.isClickable() && node.isEnabled()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        for (int i=0; i<node.getChildCount(); i++) {
            if (clickUnlikedLikeButton(node.getChild(i))) return true;
        }
        return false;
    }

    public boolean isAutoNext() { return autoNext; }
    public boolean isAutoLike() { return autoLike; }

    public void refreshVision() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null ||
            !root.getPackageName().toString().toLowerCase(Locale.ROOT).contains("tiktok")) {
            FloatingOverlayService.updateVisionStatus("Open TikTok to inspect screen");
            return;
        }
        FloatingOverlayService.updateVisionStatus("TikTok detected • " + describeScreen(root));
    }

    public void setAutoNext(boolean enabled) {
        autoNext = enabled; handler.removeCallbacks(autoNextTask);
        if (enabled) { lastAutoNextAt = System.currentTimeMillis(); handler.postDelayed(autoNextTask, 12000L); }
        FloatingOverlayService.updateVisionStatus(enabled ? "Auto-next enabled • TikTok only" : "Auto-next off");
    }

    public void setAutoLike(boolean enabled) {
        autoLike = enabled;
        FloatingOverlayService.updateVisionStatus(enabled ? "Auto-like enabled • TikTok only" : "Auto-like off");
    }

    public void nextVideo() {
        if (!isTikTokForeground()) { FloatingOverlayService.updateVisionStatus("Open TikTok first"); return; }
        swipeNext();
    }

    private final Runnable autoNextTask = new Runnable() {
        @Override public void run() {
            if (!autoNext) return;
            long now = System.currentTimeMillis();
            if (isTikTokForeground() && now - lastAutoNextAt >= 12000L) {
                swipeNext(); lastAutoNextAt = now;
            }
            handler.postDelayed(this, 1000L);
        }
    };

    private boolean isTikTokForeground() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && root.getPackageName() != null &&
            root.getPackageName().toString().toLowerCase(Locale.ROOT).contains("tiktok");
    }

    private void swipeNext() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            FloatingOverlayService.updateVisionStatus("Android 7+ required for swipe"); return;
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        Path path = new Path(); path.moveTo(dm.widthPixels*0.52f, dm.heightPixels*0.78f);
        path.lineTo(dm.widthPixels*0.52f, dm.heightPixels*0.28f);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 280)).build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                FloatingOverlayService.updateVisionStatus("Moved to next video");
            }
            @Override public void onCancelled(GestureDescription g) {
                FloatingOverlayService.updateVisionStatus("Swipe cancelled");
            }
        }, null);
        if (!accepted) FloatingOverlayService.updateVisionStatus("Swipe not accepted");
    }

    @Override public boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN &&
            (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP)) {
            autoNext = false; autoLike = false; handler.removeCallbacks(autoNextTask);
            FloatingOverlayService.stopFromVolumeKey();
            FloatingOverlayService.updateVisionStatus("Emergency stop • automation off");
            return true;
        }
        return super.onKeyEvent(event);
    }
    @Override public void onInterrupt() {
        autoNext = false; autoLike = false; handler.removeCallbacks(autoNextTask);
    }
    @Override public void onDestroy() {
        autoNext = false; autoLike = false; handler.removeCallbacksAndMessages(null);
        if (instance == this) instance = null; super.onDestroy();
    }
    public boolean tap(float x, float y, AccessibilityService.GestureResultCallback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        Path path = new Path(); path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 20)).build();
        return dispatchGesture(gesture, callback, null);
    }
}