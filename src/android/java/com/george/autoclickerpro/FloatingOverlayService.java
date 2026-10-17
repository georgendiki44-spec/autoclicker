package com.george.autoclickerpro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
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
    private WindowManager wm;
    private WindowManager.LayoutParams panelParams, targetParams;
    private LinearLayout panel;
    private TextView status;
    private EditText intervalInput, countInput;
    private CheckBox continuous;
    private int maxTaps = 50;
    private View target;
    private boolean running = false, targetLocked = false;
    private int intervalMs = 500;
    private int tapCount = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable clickLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (continuous != null && !continuous.isChecked() && tapCount >= maxTaps) {
                running = false;
                status.setText("Complete • " + tapCount + " taps");
                return;
            }
            AutoClickerAccessibilityService service = AutoClickerAccessibilityService.getInstance();
            if (service == null) {
                status.setText("Enable Accessibility service first");
                running = false;
                return;
            }
            int[] loc = new int[2];
            target.getLocationOnScreen(loc);
            float tapX = loc[0] + target.getWidth() / 2f;
            float tapY = loc[1] + target.getHeight() / 2f;
            boolean dispatched = service.tap(tapX, tapY,
                new android.accessibilityservice.GestureDescription.GestureResultCallback() {
                    @Override public void onCompleted(
                            android.accessibilityservice.GestureDescription gestureDescription) {
                        if (running && status != null) {
                            status.setText("Tap sent • " + tapCount + " taps");
                        }
                    }

                    @Override public void onCancelled(
                            android.accessibilityservice.GestureDescription gestureDescription) {
                        if (running && status != null) {
                            status.setText("Tap cancelled by Android");
                        }
                    }
                });
            if (!dispatched) {
                running = false;
                status.setText("Android rejected tap. Re-enable Accessibility service.");
                return;
            }
            tapCount++;
            status.setText("Running • " + tapCount + " taps");
            handler.postDelayed(this, intervalMs);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        active = true;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("autoclicker_overlay",
                "Auto Clicker controls", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
            Notification notification = new Notification.Builder(this, "autoclicker_overlay")
                .setContentTitle("Auto Clicker Pro")
                .setContentText("Floating click controls are active")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
            startForeground(4107, notification);
        }
        if (!Settings.canDrawOverlays(this)) {
            active = false;
            stopForeground(true);
            stopSelf();
            return;
        }
        wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        createTarget();
        createPanel();
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(14));
        return d;
    }

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private WindowManager.LayoutParams params(int width, int height, int flags, int gravity) {
        int type = Build.VERSION.SDK_INT >= 26 ?
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
            WindowManager.LayoutParams.TYPE_PHONE;
        return new WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT);
    }

    private void createTarget() {
        target = new View(this);
        target.setBackground(rounded(Color.argb(190, 235, 45, 65)));
        targetParams = params(dp(34), dp(34),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, Gravity.TOP | Gravity.START);
        targetParams.gravity = Gravity.TOP | Gravity.START;
        targetParams.x = getResources().getDisplayMetrics().widthPixels / 2;
        targetParams.y = getResources().getDisplayMetrics().heightPixels / 2;
        wm.addView(target, targetParams);
        target.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float downX, downY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (targetLocked) return false;
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = targetParams.x; startY = targetParams.y;
                    downX = e.getRawX(); downY = e.getRawY();
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    targetParams.x = startX + (int)(e.getRawX() - downX);
                    targetParams.y = startY + (int)(e.getRawY() - downY);
                    wm.updateViewLayout(target, targetParams);
                    return true;
                }
                return e.getAction() == MotionEvent.ACTION_UP;
            }
        });
    }

    private void createPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(8));
        panel.setBackground(rounded(Color.rgb(24, 27, 34)));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(12);
        status.setText("Drag red target onto a button");
        panel.addView(status);
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.HORIZONTAL);
        intervalInput = new EditText(this);
        intervalInput.setSingleLine(true); intervalInput.setInputType(2); intervalInput.setText("500"); intervalInput.setHint("Interval ms");
        intervalInput.setTextColor(Color.WHITE); intervalInput.setHintTextColor(Color.LTGRAY); intervalInput.setTextSize(13);
        intervalInput.setBackground(rounded(Color.rgb(55,58,66)));
        countInput = new EditText(this);
        countInput.setSingleLine(true); countInput.setInputType(2); countInput.setText("50"); countInput.setHint("Tap count");
        countInput.setTextColor(Color.WHITE); countInput.setHintTextColor(Color.LTGRAY); countInput.setTextSize(13);
        countInput.setBackground(rounded(Color.rgb(55,58,66)));
        settings.addView(intervalInput, new LinearLayout.LayoutParams(0, dp(44), 1));
        settings.addView(countInput, new LinearLayout.LayoutParams(0, dp(44), 1));
        panel.addView(settings);
        continuous = new CheckBox(this); continuous.setText("Continuous clicking"); continuous.setTextColor(Color.WHITE);
        panel.addView(continuous);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button move = new Button(this); move.setText("Lock target");
        Button start = new Button(this); start.setText("Start");
        Button stop = new Button(this); stop.setText("Stop");
        Button close = new Button(this); close.setText("×");
        row.addView(move, new LinearLayout.LayoutParams(0, dp(48), 1));
        row.addView(start, new LinearLayout.LayoutParams(0, dp(48), 1));
        row.addView(stop, new LinearLayout.LayoutParams(0, dp(48), 1));
        row.addView(close, new LinearLayout.LayoutParams(0, dp(48), 0.6f));
        panel.addView(row);
        panelParams = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        panelParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        panelParams.y = dp(80);
        wm.addView(panel, panelParams);

        move.setOnClickListener(v -> {
            targetLocked = !targetLocked;
            targetParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                (targetLocked ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
            wm.updateViewLayout(target, targetParams);
            move.setText(targetLocked ? "Move target" : "Lock target");
            status.setText(targetLocked ? "Target locked" : "Drag red target to position");
        });
        start.setOnClickListener(v -> {
            if (!targetLocked) {
                status.setText("Position target, then tap Lock target");
                return;
            }
            if (AutoClickerAccessibilityService.getInstance() == null) {
                status.setText("Enable Auto Clicker in Accessibility settings");
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            }
            try {
                intervalMs = Math.max(80, Integer.parseInt(intervalInput.getText().toString().trim()));
                maxTaps = Math.max(1, Integer.parseInt(countInput.getText().toString().trim()));
            } catch (Exception ex) {
                status.setText("Enter a valid interval and tap count"); return;
            }
            running = true; tapCount = 0; handler.removeCallbacks(clickLoop); handler.post(clickLoop);
        });
        stop.setOnClickListener(v -> {
            running = false; handler.removeCallbacks(clickLoop); status.setText("Stopped • " + tapCount + " taps");
        });
        close.setOnClickListener(v -> stopSelf());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        active = false;
        handler.removeCallbacks(clickLoop);
        if (wm != null) {
            try { if (panel != null) wm.removeView(panel); } catch (Exception ignored) { }
            try { if (target != null) wm.removeView(target); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
