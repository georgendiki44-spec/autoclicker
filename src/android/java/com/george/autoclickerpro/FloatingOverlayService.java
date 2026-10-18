package com.george.autoclickerpro;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FloatingOverlayService extends Service {
    public static volatile boolean active = false;
    private static volatile FloatingOverlayService instance;
    private WindowManager wm;
    private WindowManager.LayoutParams panelParams, targetParams, chipParams;
    private LinearLayout panel;
    private TextView status, chip;
    private EditText intervalInput, countInput;
    private CheckBox continuous;
    private TextView visionStatus;
    private int maxTaps = 50;
    private View target;
    private boolean running = false, targetLocked = false, panelHidden = false;
    private int intervalMs = 20;
    private int tapCount = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable clickLoop = new Runnable() {
        @Override public void run() {
            if (!running || target == null) return;
            if (continuous != null && !continuous.isChecked() && tapCount >= maxTaps) {
                running = false;
                setStatus("Complete • " + tapCount + " taps");
                return;
            }
            AutoClickerAccessibilityService service = AutoClickerAccessibilityService.getInstance();
            if (service == null) {
                running = false;
                setStatus("Enable Accessibility service first");
                return;
            }
            int[] loc = new int[2];
            target.getLocationOnScreen(loc);
            float x = loc[0] + target.getWidth() / 2f;
            float y = loc[1] + target.getHeight() / 2f;
            boolean sent = service.tap(x, y, new android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(android.accessibilityservice.GestureDescription gesture) { }
                @Override public void onCancelled(android.accessibilityservice.GestureDescription gesture) {
                    if (running) setStatus("Tap cancelled by Android");
                }
            });
            if (!sent) {
                running = false;
                setStatus("Tap rejected. Check Accessibility permission.");
                return;
            }
            tapCount++;
            setStatus("Running • " + tapCount + " taps • " + intervalMs + " ms");
            handler.postDelayed(this, intervalMs);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        active = true;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("autoclicker_overlay",
                "Auto Clicker controls", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
            Notification notification = new Notification.Builder(this, "autoclicker_overlay")
                .setContentTitle("Auto Clicker Pro")
                .setContentText("Floating click controls are active")
                .setSmallIcon(android.R.drawable.ic_media_play).build();
            startForeground(4107, notification);
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            active = false; stopForeground(true); stopSelf(); return;
        }
        wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        createTarget();
        createPanel();
        createSideChip();
    }

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(14)); return d;
    }

    private WindowManager.LayoutParams params(int width, int height, int flags, int gravity) {
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        return new WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT);
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }

    private void createTarget() {
        target = new View(this);
        target.setBackground(rounded(Color.argb(220, 235, 45, 65)));
        targetParams = params(dp(34), dp(34), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, Gravity.TOP | Gravity.START);
        targetParams.gravity = Gravity.TOP | Gravity.START;
        targetParams.x = getResources().getDisplayMetrics().widthPixels / 2;
        targetParams.y = getResources().getDisplayMetrics().heightPixels / 2;
        wm.addView(target, targetParams);
        target.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float dx, dy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (targetLocked) return true;
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    sx = targetParams.x; sy = targetParams.y; dx = e.getRawX(); dy = e.getRawY(); return true;
                }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    targetParams.x = Math.max(0, sx + (int)(e.getRawX() - dx));
                    targetParams.y = Math.max(0, sy + (int)(e.getRawY() - dy));
                    try { wm.updateViewLayout(target, targetParams); } catch (Exception ignored) { }
                    return true;
                }
                return e.getAction() == MotionEvent.ACTION_UP;
            }
        });
    }

    private void createPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(6), dp(10), dp(6));
        panel.setBackground(rounded(Color.rgb(24, 27, 34)));

        status = new TextView(this);
        status.setTextColor(Color.WHITE); status.setTextSize(12);
        status.setText("Drag red target to the tap location");
        status.setPadding(dp(3), dp(4), dp(3), dp(8));
        panel.addView(status);

        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.HORIZONTAL);
        intervalInput = new EditText(this);
        intervalInput.setSingleLine(true); intervalInput.setInputType(2); intervalInput.setText("20"); intervalInput.setHint("Interval ms");
        styleInput(intervalInput);
        countInput = new EditText(this);
        countInput.setSingleLine(true); countInput.setInputType(2); countInput.setText("50"); countInput.setHint("Tap count");
        styleInput(countInput);
        settings.addView(intervalInput, new LinearLayout.LayoutParams(0, dp(42), 1));
        settings.addView(countInput, new LinearLayout.LayoutParams(0, dp(42), 1));
        panel.addView(settings);

        continuous = new CheckBox(this);
        continuous.setText("Continuous clicking"); continuous.setTextColor(Color.WHITE);
        panel.addView(continuous);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button lock = new Button(this); lock.setText("Lock target");
        Button start = new Button(this); start.setText("Start");
        Button stop = new Button(this); stop.setText("Stop");
        Button hide = new Button(this); hide.setText("Hide");
        row.addView(lock, new LinearLayout.LayoutParams(0, dp(46), 1));
        row.addView(start, new LinearLayout.LayoutParams(0, dp(46), 1));
        row.addView(stop, new LinearLayout.LayoutParams(0, dp(46), 1));
        row.addView(hide, new LinearLayout.LayoutParams(0, dp(46), 1));
        panel.addView(row);

        LinearLayout visionRow = new LinearLayout(this);
        visionRow.setOrientation(LinearLayout.HORIZONTAL);
        Button inspect = new Button(this); inspect.setText("Vision");
        Button next = new Button(this); next.setText("Next");
        Button autoNext = new Button(this); autoNext.setText("Auto next");
        Button autoLike = new Button(this); autoLike.setText("Auto like");
        visionRow.addView(inspect, new LinearLayout.LayoutParams(0, dp(44), 1));
        visionRow.addView(next, new LinearLayout.LayoutParams(0, dp(44), 1));
        visionRow.addView(autoNext, new LinearLayout.LayoutParams(0, dp(44), 1));
        visionRow.addView(autoLike, new LinearLayout.LayoutParams(0, dp(44), 1));
        panel.addView(visionRow);

        LinearLayout aiRow = new LinearLayout(this);
        aiRow.setOrientation(LinearLayout.HORIZONTAL);
        Button aiAnalyze = new Button(this); aiAnalyze.setText("AI Analyze");
        Button aiKey = new Button(this); aiKey.setText("Gemini Key");
        aiRow.addView(aiAnalyze, new LinearLayout.LayoutParams(0, dp(44), 1));
        aiRow.addView(aiKey, new LinearLayout.LayoutParams(0, dp(44), 1));
        panel.addView(aiRow);

        visionStatus = new TextView(this);
        visionStatus.setTextColor(Color.rgb(255, 190, 90));
        visionStatus.setTextSize(11);
        visionStatus.setText("Vision off • open TikTok");
        visionStatus.setPadding(dp(3), dp(4), dp(3), dp(3));
        panel.addView(visionStatus);

        aiKey.setOnClickListener(v -> showGeminiKeyDialog());
        aiAnalyze.setOnClickListener(v -> {
            AutoClickerAccessibilityService service = AutoClickerAccessibilityService.getInstance();
            if (service == null) { updateVisionStatus("Enable Accessibility service first"); return; }
            service.analyzeCurrentScreen();
        });

        inspect.setOnClickListener(v -> {
            AutoClickerAccessibilityService service = AutoClickerAccessibilityService.getInstance();
            if (service == null) {
                setStatus("Enable Accessibility service for screen vision");
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i);
            } else service.refreshVision();
        });
        next.setOnClickListener(v -> {
            AutoClickerAccessibilityService service = AutoClickerAccessibilityService.getInstance();
            if (service == null) { setStatus("Enable Accessibility service first"); return; }
            service.nextVideo();
        });
        autoNext.setOnClickListener(v -> {
            AutoClickerAccessibilityService service = AutoClickerAccessibilityService.getInstance();
            if (service == null) { setStatus("Enable Accessibility service first"); return; }
            boolean enabled = !service.isAutoNext();
            service.setAutoNext(enabled);
            autoNext.setText(enabled ? "Auto next ✓" : "Auto next");
        });
        autoLike.setOnClickListener(v -> {
            AutoClickerAccessibilityService service = AutoClickerAccessibilityService.getInstance();
            if (service == null) { setStatus("Enable Accessibility service first"); return; }
            boolean enabled = !service.isAutoLike();
            service.setAutoLike(enabled);
            autoLike.setText(enabled ? "Auto like ✓" : "Auto like");
        });

        panelParams = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, Gravity.TOP | Gravity.START);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = dp(8); panelParams.y = dp(80);
        wm.addView(panel, panelParams);

        // Drag the status/header area to move the entire controls panel.
        status.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float dx, dy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    sx = panelParams.x; sy = panelParams.y; dx = e.getRawX(); dy = e.getRawY(); return true;
                }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    panelParams.x = Math.max(0, sx + (int)(e.getRawX() - dx));
                    panelParams.y = Math.max(0, sy + (int)(e.getRawY() - dy));
                    try { wm.updateViewLayout(panel, panelParams); } catch (Exception ignored) { }
                    return true;
                }
                return e.getAction() == MotionEvent.ACTION_UP;
            }
        });

        lock.setOnClickListener(v -> {
            targetLocked = !targetLocked;
            targetParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                (targetLocked ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
            try { wm.updateViewLayout(target, targetParams); } catch (Exception ignored) { }
            lock.setText(targetLocked ? "Unlock" : "Lock target");
            setStatus(targetLocked ? "Target locked • Volume key stops" : "Move target to tap location");
        });
        start.setOnClickListener(v -> {
            if (!targetLocked) { setStatus("Position target, then Lock target"); return; }
            if (AutoClickerAccessibilityService.getInstance() == null) {
                setStatus("Enable Auto Clicker in Accessibility settings");
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return;
            }
            try {
                intervalMs = Math.max(20, Integer.parseInt(intervalInput.getText().toString().trim()));
                maxTaps = Math.max(1, Integer.parseInt(countInput.getText().toString().trim()));
            } catch (Exception ex) { setStatus("Enter valid interval and tap count"); return; }
            running = true; tapCount = 0; handler.removeCallbacks(clickLoop); handler.post(clickLoop);
        });
        stop.setOnClickListener(v -> stopClicking("Stopped"));
        hide.setOnClickListener(v -> setPanelHidden(true));
    }

    private void showGeminiKeyDialog() {
        EditText keyInput = new EditText(this);
        keyInput.setSingleLine(true);
        keyInput.setInputType(129);
        keyInput.setHint("Paste Gemini API key");
        keyInput.setText(getSharedPreferences("autoclicker_private", MODE_PRIVATE).getString("gemini_api_key", ""));
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Gemini API key")
            .setMessage("Stored privately on this device. Never share or commit this key.")
            .setView(keyInput)
            .setNegativeButton("Cancel", (d, w) -> { })
            .setNeutralButton("Clear key", (d, w) ->
                getSharedPreferences("autoclicker_private", MODE_PRIVATE).edit().remove("gemini_api_key").apply())
            .setPositiveButton("Save", (d, w) -> {
                String key = keyInput.getText().toString().trim();
                if (key.isEmpty()) {
                    getSharedPreferences("autoclicker_private", MODE_PRIVATE).edit().remove("gemini_api_key").apply();
                    updateVisionStatus("Gemini key cleared");
                } else {
                    getSharedPreferences("autoclicker_private", MODE_PRIVATE).edit().putString("gemini_api_key", key).apply();
                    updateVisionStatus("Gemini key saved on this device");
                }
            }).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setType(Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE);
        }
        dialog.show();
    }

    private void styleInput(EditText input) {
        input.setTextColor(Color.WHITE); input.setHintTextColor(Color.LTGRAY); input.setTextSize(13);
        input.setBackground(rounded(Color.rgb(55, 58, 66)));
    }

    private void createSideChip() {
        chip = new TextView(this);
        chip.setText("≡"); chip.setTextColor(Color.WHITE); chip.setTextSize(22);
        chip.setGravity(Gravity.CENTER); chip.setBackground(rounded(Color.rgb(190, 25, 45)));
        chipParams = params(dp(42), dp(54), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, Gravity.TOP | Gravity.START);
        chipParams.gravity = Gravity.TOP | Gravity.START;
        chipParams.x = 0; chipParams.y = getResources().getDisplayMetrics().heightPixels / 2;
        wm.addView(chip, chipParams);
        chip.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float dx, dy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    sx = chipParams.x; sy = chipParams.y; dx = e.getRawX(); dy = e.getRawY(); return true;
                }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    chipParams.x = Math.max(0, sx + (int)(e.getRawX() - dx));
                    chipParams.y = Math.max(0, sy + (int)(e.getRawY() - dy));
                    try { wm.updateViewLayout(chip, chipParams); } catch (Exception ignored) { }
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    float moved = Math.abs(e.getRawX() - dx) + Math.abs(e.getRawY() - dy);
                    if (moved < dp(10)) setPanelHidden(!panelHidden);
                    return true;
                }
                return true;
            }
        });
    }

    private void setPanelHidden(boolean hidden) {
        panelHidden = hidden;
        if (panel != null) panel.setVisibility(hidden ? View.GONE : View.VISIBLE);
        if (chip != null) chip.setText(hidden ? "≡" : "×");
    }

    public static void updateVisionStatus(String message) {
        FloatingOverlayService service = instance;
        if (service != null) service.handler.post(() -> { if (service.visionStatus != null) service.visionStatus.setText(message); });
    }

    public static void stopFromVolumeKey() {
        FloatingOverlayService service = instance;
        if (service != null) service.handler.post(() -> service.stopClicking("Stopped by volume key"));
    }

    public void stopClicking(String label) {
        running = false; handler.removeCallbacks(clickLoop);
        setStatus(label + " • " + tapCount + " taps");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override public void onDestroy() {
        running = false; active = false; if (instance == this) instance = null; handler.removeCallbacks(clickLoop);
        if (wm != null) {
            try { if (panel != null) wm.removeView(panel); } catch (Exception ignored) { }
            try { if (target != null) wm.removeView(target); } catch (Exception ignored) { }
            try { if (chip != null) wm.removeView(chip); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
