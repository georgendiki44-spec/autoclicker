package com.george.autoclickerpro;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/**
 * Floating click controls. The app (Python/Kivy) configures this service through the
 * static API below; the actual taps are performed by {@link AutoClickerAccessibilityService}.
 */
public class FloatingOverlayService extends Service {
    public static final String ACTION_STOP_SERVICE = "com.george.autoclickerpro.STOP_SERVICE";
    public static final String ACTION_TOGGLE = "com.george.autoclickerpro.TOGGLE_CLICKING";
    public static final int GESTURE_TAP = 0, GESTURE_DOUBLE = 1, GESTURE_HOLD = 2;
    private static final int NOTIFICATION_ID = 4107;
    private static final String CHANNEL_ID = "autoclicker_overlay";
    private static final String PREFS = "autoclicker_private";

    /** True while the floating controls exist. */
    public static volatile boolean active = false;
    private static volatile FloatingOverlayService instance;

    // Configuration (set from the app; read by the click engine).
    private static volatile int cfgIntervalMs = 100;
    private static volatile int cfgCount = 50;
    private static volatile boolean cfgContinuous = false;
    private static volatile int cfgJitterPx = 0;
    private static volatile int cfgGesture = GESTURE_TAP;
    private static volatile int cfgStartDelayMs = 0;

    // Engine state (readable from the app).
    private static volatile boolean running = false;
    private static volatile boolean paused = false;
    private static volatile int tapCount = 0;
    private static volatile String latestVisionStatus = "";
    private static volatile String engineStatus = "Drag the target over the spot you want to tap";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private int runToken = 0;
    private int failedGestures = 0;

    private WindowManager wm;
    private WindowManager.LayoutParams panelParams, targetParams, chipParams;
    private LinearLayout panel;
    private View target;
    private TextView chip, statusView, visionView;
    private Button primaryButton, autoNextButton, autoLikeButton;
    private boolean panelHidden = false;

    // ------------------------------------------------------------------ static API

    public static void launch(Context context) {
        Intent intent = new Intent(context, FloatingOverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void shutdown(Context context) {
        context.stopService(new Intent(context, FloatingOverlayService.class));
    }

    public static void configure(int intervalMs, int count, boolean continuous,
                                 int jitterPx, int gesture, int startDelayMs) {
        cfgIntervalMs = Math.max(20, Math.min(600000, intervalMs));
        cfgCount = Math.max(1, Math.min(1000000, count));
        cfgContinuous = continuous;
        cfgJitterPx = Math.max(0, Math.min(200, jitterPx));
        cfgGesture = Math.max(0, Math.min(2, gesture));
        cfgStartDelayMs = Math.max(0, Math.min(60000, startDelayMs));
        final FloatingOverlayService s = instance;
        if (s != null) s.handler.post(s::refreshUi);
    }

    public static boolean isActive() { return active; }
    public static boolean isRunning() { return running; }
    public static boolean isPaused() { return paused; }
    public static int getTapCount() { return tapCount; }
    public static String getEngineStatus() { return engineStatus; }
    public static String getVisionStatus() { return latestVisionStatus; }

    public static void startClicking() {
        final FloatingOverlayService s = instance;
        if (s != null) s.handler.post(s::beginRun);
    }

    public static void stopClicking() {
        final FloatingOverlayService s = instance;
        if (s != null) s.handler.post(() -> s.endRun("Stopped"));
        else { running = false; paused = false; }
    }

    public static void togglePause() {
        final FloatingOverlayService s = instance;
        if (s != null) s.handler.post(s::pauseOrResume);
    }

    public static void stopFromVolumeKey() {
        final FloatingOverlayService s = instance;
        if (s != null) s.handler.post(() -> s.endRun("Stopped by volume key"));
    }

    public static void updateVisionStatus(String message) {
        latestVisionStatus = message == null ? "" : message;
        final FloatingOverlayService s = instance;
        if (s != null) s.handler.post(() -> {
            if (s.visionView != null) s.visionView.setText(latestVisionStatus);
        });
    }

    // ------------------------------------------------------------------ lifecycle

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        enterForeground();
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            engineStatus = "Allow 'Display over other apps' first";
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            createTarget();
            createPanel();
            createChip();
            active = true;
            refreshUi();
        } catch (Exception e) {
            engineStatus = "Could not show floating controls";
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP_SERVICE.equals(action)) {
            stopSelf();
        } else if (ACTION_TOGGLE.equals(action)) {
            if (!running) beginRun(); else pauseOrResume();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        running = false; paused = false; active = false;
        runToken++;
        handler.removeCallbacksAndMessages(null);
        if (instance == this) instance = null;
        removeView(panel); removeView(target); removeView(chip);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void removeView(View v) {
        if (wm == null || v == null) return;
        try { wm.removeView(v); } catch (Exception ignored) { }
    }

    private void enterForeground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    "Auto Clicker controls", NotificationManager.IMPORTANCE_LOW));
                b = new Notification.Builder(this, CHANNEL_ID);
            } else {
                b = new Notification.Builder(this);
            }
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            Intent stop = new Intent(this, FloatingOverlayService.class).setAction(ACTION_STOP_SERVICE);
            Intent toggle = new Intent(this, FloatingOverlayService.class).setAction(ACTION_TOGGLE);
            PendingIntent stopPi = PendingIntent.getService(this, 1, stop, piFlags);
            PendingIntent togglePi = PendingIntent.getService(this, 2, toggle, piFlags);
            b.setContentTitle("Auto Clicker Pro")
             .setContentText("Floating controls are on")
             .setSmallIcon(android.R.drawable.ic_media_play)
             .setOngoing(true)
             .addAction(android.R.drawable.ic_media_play, "Start / Pause", togglePi)
             .addAction(android.R.drawable.ic_delete, "Close", stopPi);
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch != null) b.setContentIntent(PendingIntent.getActivity(this, 0, launch, piFlags));
            Notification n = b.build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception ignored) {
            // The overlay still works without the foreground notification.
        }
    }

    // ------------------------------------------------------------------ click engine

    private void beginRun() {
        if (!active) return;
        AutoClickerAccessibilityService service = AutoClickerAccessibilityService.getInstance();
        if (service == null) {
            setEngineStatus("Turn on Auto Clicker in Accessibility settings");
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); } catch (Exception ignored) { }
            return;
        }
        runToken++;
        final int token = runToken;
        handler.removeCallbacksAndMessages(null);
        tapCount = 0; failedGestures = 0; paused = false; running = true;
        setTargetLocked(true);
        refreshUi();
        if (cfgStartDelayMs > 0) countdown(token, cfgStartDelayMs);
        else tick(token);
    }

    private void countdown(final int token, final int remainingMs) {
        if (token != runToken || !running) return;
        if (remainingMs <= 0) { tick(token); return; }
        setEngineStatus("Starting in " + ((remainingMs + 999) / 1000) + "s – switch to your app");
        handler.postDelayed(() -> countdown(token, remainingMs - 500), 500);
    }

    private void tick(final int token) {
        if (token != runToken || !running) return;
        if (paused) { handler.postDelayed(() -> tick(token), 150); return; }
        if (!cfgContinuous && tapCount >= cfgCount) { endRun("Done"); return; }
        AutoClickerAccessibilityService service = AutoClickerAccessibilityService.getInstance();
        if (service == null) { endRun("Accessibility service was turned off"); return; }

        float[] p = targetPoint();
        final long started = SystemClock.uptimeMillis();
        boolean sent = service.performGesture(p[0], p[1], cfgGesture,
            new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) {
                    if (token != runToken || !running) return;
                    failedGestures = 0;
                    tapCount++;
                    setEngineStatus((paused ? "Paused" : "Running") + " • " + tapCount + " taps");
                    scheduleNext(token, started);
                }
                @Override public void onCancelled(GestureDescription g) {
                    if (token != runToken || !running) return;
                    if (++failedGestures >= 5) { endRun("Android keeps cancelling the taps"); return; }
                    scheduleNext(token, started);
                }
            });
        if (!sent) endRun("Android rejected the tap. Re-enable the Accessibility service");
    }

    private void scheduleNext(final int token, long started) {
        long elapsed = SystemClock.uptimeMillis() - started;
        long delay = Math.max(0L, cfgIntervalMs - elapsed);
        handler.postDelayed(() -> tick(token), delay);
    }

    private float[] targetPoint() {
        int[] loc = new int[2];
        float cx, cy;
        if (target != null && target.isAttachedToWindow()) {
            target.getLocationOnScreen(loc);
            cx = loc[0] + target.getWidth() / 2f;
            cy = loc[1] + target.getHeight() / 2f;
        } else {
            cx = targetParams.x; cy = targetParams.y;
        }
        if (cfgJitterPx > 0) {
            cx += random.nextInt(cfgJitterPx * 2 + 1) - cfgJitterPx;
            cy += random.nextInt(cfgJitterPx * 2 + 1) - cfgJitterPx;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        cx = Math.max(1f, Math.min(dm.widthPixels - 1f, cx));
        cy = Math.max(1f, Math.min(dm.heightPixels - 1f, cy));
        return new float[]{cx, cy};
    }

    private void pauseOrResume() {
        if (!running) return;
        paused = !paused;
        setEngineStatus((paused ? "Paused" : "Running") + " • " + tapCount + " taps");
        refreshUi();
    }

    private void endRun(String label) {
        runToken++;
        handler.removeCallbacksAndMessages(null);
        boolean wasRunning = running;
        running = false; paused = false;
        setTargetLocked(false);
        setEngineStatus(label + (wasRunning || tapCount > 0 ? " • " + tapCount + " taps" : ""));
        refreshUi();
    }

    private void setEngineStatus(String message) {
        engineStatus = message;
        if (statusView != null) statusView.setText(message);
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private StateListDrawable buttonBg(int color) {
        int dark = Color.rgb((int) (Color.red(color) * 0.7f), (int) (Color.green(color) * 0.7f),
            (int) (Color.blue(color) * 0.7f));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(dark, 12));
        s.addState(new int[]{}, rounded(color, 12));
        return s;
    }

    private Button makeButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setTextSize(13); b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(4), 0, dp(4), 0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setBackground(buttonBg(color));
        return b;
    }

    private LinearLayout.LayoutParams weight(int heightDp, int marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(heightDp), 1f);
        lp.setMargins(dp(marginDp), 0, dp(marginDp), 0);
        return lp;
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                           : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private WindowManager.LayoutParams windowParams(int w, int h, int flags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h, overlayType(), flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    private void attachDrag(View handle, final View moved, final WindowManager.LayoutParams lp,
                            final Runnable onTap) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float dx, dy; boolean dragged;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = lp.x; sy = lp.y; dx = e.getRawX(); dy = e.getRawY(); dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - dx) + Math.abs(e.getRawY() - dy) > dp(8)) dragged = true;
                        lp.x = Math.max(0, sx + (int) (e.getRawX() - dx));
                        lp.y = Math.max(0, sy + (int) (e.getRawY() - dy));
                        try { wm.updateViewLayout(moved, lp); } catch (Exception ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragged && onTap != null) onTap.run();
                        else if (moved == target) saveTargetPosition();
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void createTarget() {
        int size = dp(44);
        target = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.argb(70, 255, 60, 80));
        ring.setStroke(dp(3), Color.argb(235, 255, 60, 80));
        target.setBackground(ring);
        targetParams = windowParams(size, size,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        targetParams.x = prefs.getInt("target_x", dm.widthPixels / 2 - size / 2);
        targetParams.y = prefs.getInt("target_y", dm.heightPixels / 2 - size / 2);
        targetParams.x = Math.max(0, Math.min(dm.widthPixels - size, targetParams.x));
        targetParams.y = Math.max(0, Math.min(dm.heightPixels - size, targetParams.y));
        wm.addView(target, targetParams);
        attachDrag(target, target, targetParams, null);
    }

    private void saveTargetPosition() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("target_x", targetParams.x).putInt("target_y", targetParams.y).apply();
    }

    private void setTargetLocked(boolean locked) {
        if (target == null || targetParams == null) return;
        targetParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | (locked ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
        target.setAlpha(locked ? 0.45f : 1f);
        try { wm.updateViewLayout(target, targetParams); } catch (Exception ignored) { }
    }

    private void createPanel() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = Math.min(dp(320), dm.widthPixels - dp(16));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(10));
        panel.setBackground(rounded(Color.rgb(22, 25, 33), 18));

        TextView title = new TextView(this);
        title.setText("⠿  Auto Clicker  (drag here)");
        title.setTextColor(Color.rgb(160, 168, 184)); title.setTextSize(11);
        title.setPadding(dp(4), dp(2), dp(4), dp(4));
        panel.addView(title);

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE); statusView.setTextSize(14);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setPadding(dp(4), 0, dp(4), dp(8));
        statusView.setText(engineStatus);
        panel.addView(statusView);

        LinearLayout row1 = new LinearLayout(this);
        primaryButton = makeButton("▶  Start", Color.rgb(32, 150, 92));
        Button stop = makeButton("■  Stop", Color.rgb(200, 45, 60));
        Button hide = makeButton("–  Hide", Color.rgb(70, 76, 92));
        row1.addView(primaryButton, weight(46, 3));
        row1.addView(stop, weight(46, 3));
        row1.addView(hide, weight(46, 3));
        panel.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);
        Button next = makeButton("Next", Color.rgb(52, 90, 160));
        autoNextButton = makeButton("Auto next", Color.rgb(52, 90, 160));
        autoLikeButton = makeButton("Auto like", Color.rgb(52, 90, 160));
        Button ai = makeButton("AI", Color.rgb(52, 90, 160));
        row2.addView(next, weight(40, 3));
        row2.addView(autoNextButton, weight(40, 3));
        row2.addView(autoLikeButton, weight(40, 3));
        row2.addView(ai, weight(40, 3));
        panel.addView(row2, rowLp);

        visionView = new TextView(this);
        visionView.setTextColor(Color.rgb(255, 190, 90)); visionView.setTextSize(11);
        visionView.setPadding(dp(4), dp(8), dp(4), 0);
        visionView.setText(latestVisionStatus);
        panel.addView(visionView);

        primaryButton.setOnClickListener(v -> {
            if (!running) beginRun(); else pauseOrResume();
        });
        stop.setOnClickListener(v -> endRun("Stopped"));
        hide.setOnClickListener(v -> setPanelHidden(true));
        next.setOnClickListener(v -> withService(s -> s.nextVideo()));
        autoNextButton.setOnClickListener(v -> withService(s -> s.setAutoNext(!s.isAutoNext())));
        autoLikeButton.setOnClickListener(v -> withService(s -> s.setAutoLike(!s.isAutoLike())));
        ai.setOnClickListener(v -> withService(s -> s.analyzeCurrentScreen()));

        panelParams = windowParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        panelParams.x = dp(8); panelParams.y = dp(90);
        wm.addView(panel, panelParams);
        attachDrag(title, panel, panelParams, null);
    }

    private interface ServiceAction { void run(AutoClickerAccessibilityService s); }

    private void withService(ServiceAction action) {
        AutoClickerAccessibilityService s = AutoClickerAccessibilityService.getInstance();
        if (s == null) { setEngineStatus("Turn on Auto Clicker in Accessibility settings"); return; }
        action.run(s);
        handler.postDelayed(this::refreshUi, 150);
    }

    private void createChip() {
        chip = new TextView(this);
        chip.setTextColor(Color.WHITE); chip.setTextSize(20);
        chip.setGravity(Gravity.CENTER);
        chipParams = windowParams(dp(44), dp(52),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        chipParams.x = 0;
        chipParams.y = getResources().getDisplayMetrics().heightPixels / 3;
        wm.addView(chip, chipParams);
        attachDrag(chip, chip, chipParams, () -> setPanelHidden(!panelHidden));
    }

    private void setPanelHidden(boolean hidden) {
        panelHidden = hidden;
        if (panel != null) panel.setVisibility(hidden ? View.GONE : View.VISIBLE);
        refreshUi();
    }

    private void refreshUi() {
        if (primaryButton != null) {
            primaryButton.setText(!running ? "▶  Start" : paused ? "▶  Resume" : "⏸  Pause");
            primaryButton.setBackground(buttonBg(!running || paused
                ? Color.rgb(32, 150, 92) : Color.rgb(214, 140, 30)));
        }
        AutoClickerAccessibilityService s = AutoClickerAccessibilityService.getInstance();
        if (autoNextButton != null) autoNextButton.setText(s != null && s.isAutoNext() ? "Auto next ✓" : "Auto next");
        if (autoLikeButton != null) autoLikeButton.setText(s != null && s.isAutoLike() ? "Auto like ✓" : "Auto like");
        if (statusView != null) statusView.setText(engineStatus);
        if (chip != null) {
            chip.setText(panelHidden ? (running ? (paused ? "⏸" : "▶") : "≡") : "×");
            chip.setBackground(rounded(!running ? Color.rgb(190, 25, 45)
                : paused ? Color.rgb(214, 140, 30) : Color.rgb(32, 150, 92), 14));
        }
    }
}
