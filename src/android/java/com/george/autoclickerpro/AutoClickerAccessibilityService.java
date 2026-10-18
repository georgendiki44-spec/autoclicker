package com.george.autoclickerpro;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Locale;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public class AutoClickerAccessibilityService extends AccessibilityService {
    private static volatile AutoClickerAccessibilityService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
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
        boolean tikTok = pkg != null && isTikTokPackage(pkg.toString());
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

    private boolean isTikTokPackage(String packageName) {
        String p = packageName.toLowerCase(Locale.ROOT);
        return p.contains("tiktok") || p.contains("musically");
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


    /**
     * Captures the current TikTok screen and asks Gemini for one relevant comment idea.
     * Posting is intentionally left to the user so the app cannot publish unwanted comments.
     */
    public void analyzeCurrentScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            FloatingOverlayService.updateVisionStatus("AI screen analysis requires Android 11+");
            return;
        }
        if (!isTikTokForeground()) {
            FloatingOverlayService.updateVisionStatus("Open a TikTok video before AI Analyze");
            return;
        }
        String apiKey = getSharedPreferences("autoclicker_private", MODE_PRIVATE)
            .getString("gemini_api_key", "").trim();
        if (apiKey.isEmpty()) {
            FloatingOverlayService.updateVisionStatus("Tap Gemini Key and save your API key first");
            return;
        }
        FloatingOverlayService.updateVisionStatus("Capturing video screen for Gemini…");
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, command -> handler.post(command),
            new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    HardwareBuffer buffer = result.getHardwareBuffer();
                    Bitmap hardwareBitmap = null;
                    Bitmap bitmap = null;
                    try {
                        ColorSpace colorSpace = result.getColorSpace();
                        hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                        if (hardwareBitmap == null) {
                            FloatingOverlayService.updateVisionStatus("Could not read screenshot");
                            return;
                        }
                        bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
                        if (bitmap == null) {
                            FloatingOverlayService.updateVisionStatus("Could not prepare screenshot");
                            return;
                        }
                        final Bitmap captured = bitmap;
                        aiExecutor.execute(() -> sendFrameToGemini(captured, apiKey));
                    } catch (Exception e) {
                        FloatingOverlayService.updateVisionStatus("Screenshot processing failed");
                    } finally {
                        if (buffer != null) buffer.close();
                        if (hardwareBitmap != null) hardwareBitmap.recycle();
                    }
                }
                @Override public void onFailure(int errorCode) {
                    FloatingOverlayService.updateVisionStatus("Android screenshot failed (" + errorCode + ")");
                }
            });
    }

    private void sendFrameToGemini(Bitmap bitmap, String apiKey) {
        HttpURLConnection connection = null;
        try {
            ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 72, imageBytes);
            bitmap.recycle();
            String encodedImage = android.util.Base64.encodeToString(imageBytes.toByteArray(),
                android.util.Base64.NO_WRAP);

            JSONObject imageData = new JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", encodedImage);
            JSONArray parts = new JSONArray()
                .put(new JSONObject().put("text",
                    "You are helping a person write a genuine, concise comment about the TikTok video visible in this screenshot. " +
                    "Describe only what can reasonably be inferred from the visible frame. Do not claim to have watched unseen action, " +
                    "do not mention likes or engagement, and do not use generic spam. Return exactly one natural comment under 18 words."))
                .put(new JSONObject().put("inline_data", imageData));
            JSONObject requestBody = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("parts", parts)))
                .put("generationConfig", new JSONObject().put("temperature", 0.6).put("maxOutputTokens", 80));

            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("x-goog-api-key", apiKey);
            byte[] body = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder responseText = new StringBuilder();
            if (stream != null) {
                try (InputStream in = stream) {
                    byte[] chunk = new byte[4096];
                    int count;
                    while ((count = in.read(chunk)) != -1) responseText.append(new String(chunk, 0, count, StandardCharsets.UTF_8));
                }
            }
            if (status < 200 || status >= 300) {
                String message = status == 401 || status == 403 ? "Gemini rejected the key; check API key/project access"
                    : status == 429 ? "Gemini free quota/rate limit reached; wait and retry"
                    : "Gemini request failed (HTTP " + status + ")";
                FloatingOverlayService.updateVisionStatus(message);
                return;
            }
            JSONObject response = new JSONObject(responseText.toString());
            JSONArray candidates = response.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                FloatingOverlayService.updateVisionStatus("Gemini returned no comment suggestion");
                return;
            }
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            JSONArray responseParts = content == null ? null : content.optJSONArray("parts");
            String suggestion = "";
            if (responseParts != null) {
                for (int i = 0; i < responseParts.length(); i++) {
                    String partText = responseParts.getJSONObject(i).optString("text", "");
                    if (!partText.isEmpty()) { suggestion = partText.trim(); break; }
                }
            }
            if (suggestion.isEmpty()) {
                FloatingOverlayService.updateVisionStatus("Gemini returned an empty suggestion");
                return;
            }
            suggestion = suggestion.replaceAll("[\\r\\n]+", " ").trim();
            if (suggestion.length() > 220) suggestion = suggestion.substring(0, 217) + "...";
            final String finalSuggestion = suggestion;
            handler.post(() -> FloatingOverlayService.updateVisionStatus(
                "AI suggestion (review before posting): " + finalSuggestion));
        } catch (Exception e) {
            FloatingOverlayService.updateVisionStatus("AI request failed • check Internet and API key");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public boolean isAutoNext() { return autoNext; }
    public boolean isAutoLike() { return autoLike; }

    public void refreshVision() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null ||
            !isTikTokPackage(root.getPackageName().toString())) {
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
            isTikTokPackage(root.getPackageName().toString());
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
        aiExecutor.shutdownNow();
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