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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private volatile boolean metricMode = false, autoCommentSuggestions = false, templateCommentEnabled = false;
    private volatile boolean autoPostingEnabled = false, postingWorkflowBusy = false, commentSubmitScheduled = false, aiCommentReady = false;
    private volatile int maxCommentsPerSession = 5, submittedCommentCount = 0;
    private volatile long lastCommentPostedAt = 0L;
    private volatile String activePostingSignature = "", lastPostedSignature = "";
    private volatile int minimumLikes = 1000, minimumComments = 10;
    private volatile long commentCooldownMs = 60000L, lastCommentSuggestionAt = 0L;
    private volatile String reusableComment = "", pendingComment = "";
    private volatile String lastQualifiedSignature = "";
    private String lastScreenSummary = "Vision idle";
    private long lastAutoLikeAt = 0L, lastAutoNextAt = 0L;

    public static AutoClickerAccessibilityService getInstance() { return instance; }
    @Override protected void onServiceConnected() {
        super.onServiceConnected(); instance = this;
        android.content.SharedPreferences prefs = getSharedPreferences("autoclicker_private", MODE_PRIVATE);
        minimumLikes = prefs.getInt("minimum_likes", 1000);
        minimumComments = prefs.getInt("minimum_comments", 10);
        commentCooldownMs = prefs.getInt("comment_cooldown_seconds", 60) * 1000L;
        metricMode = prefs.getBoolean("metric_mode", false);
        autoCommentSuggestions = prefs.getBoolean("auto_comment_suggestions", false);
        templateCommentEnabled = prefs.getBoolean("template_comment_enabled", false);
        reusableComment = prefs.getString("reusable_comment", "");
        autoPostingEnabled = prefs.getBoolean("auto_post_comments", false);
        maxCommentsPerSession = Math.max(1, Math.min(5, prefs.getInt("max_comments_per_session", 5)));
        submittedCommentCount = 0;
        FloatingOverlayService.updateVisionStatus("Vision ready • open TikTok • comments submitted this run: 0");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        boolean tikTok = pkg != null && isTikTokPackage(pkg.toString());
        if (!tikTok) { FloatingOverlayService.updateVisionStatus("Waiting for TikTok"); return; }
        String summary = describeScreen(root);
        String screenText = collectScreenText(root);
        int likes = extractMetric(screenText, "likes?");
        int comments = extractMetric(screenText, "comments?");
        if (metricMode && autoNext && (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) && likes >= 0 && comments >= 0 && (likes < minimumLikes || comments < minimumComments)) {
            long now = System.currentTimeMillis();
            if (now - lastAutoNextAt >= 4000L) { swipeNext(); lastAutoNextAt = now; }
        }
        // Auto-post only when both engagement metrics are positively detected and meet thresholds.
        boolean contentChanged = event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        String signature = screenText.replaceAll("(?i)[0-9,.]+\\s*[kmb]?\\s*(likes?|comments?)", "$1")
            .replaceAll("\\s+", " ").trim();
        if (signature.length() > 500) signature = signature.substring(0, 500);
        if (autoPostingEnabled && contentChanged && !postingWorkflowBusy &&
            submittedCommentCount < maxCommentsPerSession) {
            if (likes >= minimumLikes && comments >= minimumComments && likes >= 0 && comments >= 0) {
                long now = System.currentTimeMillis();
                if (!signature.isEmpty() && !signature.equals(lastPostedSignature) &&
                    now - lastCommentPostedAt >= Math.max(60000L, commentCooldownMs)) {
                    beginAutoComment(root, signature);
                }
            } else if (likes >= 0 && comments >= 0 && (likes < minimumLikes || comments < minimumComments)) {
                FloatingOverlayService.updateVisionStatus("Skipped: metrics below minimum • " + likes +
                    " likes / " + comments + " comments");
            } else {
                FloatingOverlayService.updateVisionStatus("Waiting: engagement metrics not clearly detected");
            }
        }
        if (!pendingComment.isEmpty()) fillCommentComposer(root);
        if (!summary.equals(lastScreenSummary)) {
            lastScreenSummary = summary;
            FloatingOverlayService.updateVisionStatus("TikTok detected • " + summary);
        }
        if (autoLike && event != null &&
            (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
             event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) &&
            (!metricMode || (likes >= minimumLikes && comments >= minimumComments))) {
            long now = System.currentTimeMillis();
            if (now - lastAutoLikeAt > 1800L && clickUnlikedLikeButton(root)) lastAutoLikeAt = now;
        }
    }

    private boolean isTikTokPackage(String packageName) {
        String p = packageName.toLowerCase(Locale.ROOT);
        return p.contains("tiktok") || p.contains("musically");
    }

    private String collectScreenText(AccessibilityNodeInfo root) {
        StringBuilder out = new StringBuilder(); collectLabels(root, out, 0); return out.toString();
    }

    private int extractMetric(String text, String metricWord) {
        if (text == null) return -1;
        Pattern pattern = Pattern.compile("(?i)([0-9]+(?:\\.[0-9]+)?\\s*[kmb]?)\\s*" + metricWord + "|" + metricWord + "\\s*[: ]\\s*([0-9]+(?:\\.[0-9]+)?\\s*[kmb]?)");
        Matcher matcher = pattern.matcher(text.replace(",", ""));
        if (!matcher.find()) return -1;
        String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        raw = raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        try {
            double multiplier = 1;
            if (raw.endsWith("K")) { multiplier = 1000; raw = raw.substring(0, raw.length()-1); }
            else if (raw.endsWith("M")) { multiplier = 1000000; raw = raw.substring(0, raw.length()-1); }
            else if (raw.endsWith("B")) { multiplier = 1000000000; raw = raw.substring(0, raw.length()-1); }
            return (int)Math.min(Integer.MAX_VALUE, Double.parseDouble(raw) * multiplier);
        } catch (Exception ignored) { return -1; }
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
            handler.post(() -> {
                pendingComment = finalSuggestion;
                aiCommentReady = true;
                AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
                if (autoPostingEnabled && postingWorkflowBusy) {
                    if (activeRoot != null && hasCommentComposer(activeRoot)) {
                        fillCommentComposer(activeRoot);
                    } else if (activeRoot != null && clickCommentsButton(activeRoot)) {
                        FloatingOverlayService.updateVisionStatus("AI comment ready • opening comments");
                        handler.postDelayed(this::continueAutoComment, 900L);
                    } else {
                        FloatingOverlayService.updateVisionStatus("AI comment ready • could not locate comments control");
                        resetPostingWorkflow("AI comment ready • workflow paused safely");
                    }
                } else {
                    boolean filled = activeRoot != null && fillCommentComposer(activeRoot);
                    FloatingOverlayService.updateVisionStatus(filled
                        ? "AI comment filled • review before posting"
                        : "AI comment ready • open TikTok comment box to fill it");
                }
            });
        } catch (Exception e) {
            FloatingOverlayService.updateVisionStatus("AI request failed • check Internet and API key");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private boolean fillCommentComposer(AccessibilityNodeInfo node) {
        if (node == null || pendingComment == null || pendingComment.trim().isEmpty()) return false;
        CharSequence className = node.getClassName();
        CharSequence hint = Build.VERSION.SDK_INT >= 26 ? node.getHintText() : null;
        CharSequence nodeText = node.getText();
        CharSequence description = node.getContentDescription();
        String fieldLabel = ((hint == null ? "" : hint.toString()) + " " +
            (nodeText == null ? "" : nodeText.toString()) + " " +
            (description == null ? "" : description.toString())).toLowerCase(Locale.ROOT);
        boolean editable = node.isEditable() || (className != null &&
            className.toString().toLowerCase(Locale.ROOT).contains("edittext"));
        boolean looksLikeCommentField = fieldLabel.contains("comment") || fieldLabel.contains("add a comment");
        if (editable && looksLikeCommentField && node.isEnabled() && node.isFocusable()) {
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pendingComment);
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                pendingComment = "";
                FloatingOverlayService.updateVisionStatus("Comment text entered");
                if (autoPostingEnabled && postingWorkflowBusy && !commentSubmitScheduled) {
                    commentSubmitScheduled = true;
                    handler.postDelayed(this::submitCommentAndAdvance, 1200L);
                }
                return true;
            }
        }
        for (int i=0; i<node.getChildCount(); i++) {
            if (fillCommentComposer(node.getChild(i))) return true;
        }
        return false;
    }

    private void beginAutoComment(AccessibilityNodeInfo root, String signature) {
        if (!autoPostingEnabled || postingWorkflowBusy || submittedCommentCount >= maxCommentsPerSession) return;
        if (!templateCommentEnabled && !autoCommentSuggestions) {
            FloatingOverlayService.updateVisionStatus("Choose reusable comment or AI mode first");
            return;
        }
        if (templateCommentEnabled && reusableComment.trim().isEmpty()) {
            FloatingOverlayService.updateVisionStatus("Reusable comment is empty");
            return;
        }
        postingWorkflowBusy = true;
        commentSubmitScheduled = false;
        activePostingSignature = signature;
        lastCommentSuggestionAt = System.currentTimeMillis();
        pendingComment = templateCommentEnabled ? reusableComment.trim() : "";
        aiCommentReady = false;
        FloatingOverlayService.updateVisionStatus("Qualifying post • " + (submittedCommentCount + 1) +
            "/" + maxCommentsPerSession);
        if (templateCommentEnabled) {
            if (hasCommentComposer(root)) continueAutoComment();
            else if (clickCommentsButton(root)) handler.postDelayed(this::continueAutoComment, 900L);
            else resetPostingWorkflow("Could not locate comments control; skipped safely");
        } else {
            // Capture the video before opening the comments sheet so Gemini sees the post, not the composer.
            analyzeCurrentScreen();
        }
    }

    private void continueAutoComment() {
        if (!postingWorkflowBusy || !autoPostingEnabled) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !isTikTokPackage(root.getPackageName().toString())) {
            resetPostingWorkflow("Posting paused: TikTok is no longer active");
            return;
        }
        if (!pendingComment.trim().isEmpty()) {
            if (hasCommentComposer(root)) {
                if (!fillCommentComposer(root)) handler.postDelayed(this::continueAutoComment, 700L);
            } else if (clickCommentsButton(root)) {
                handler.postDelayed(this::continueAutoComment, 900L);
            } else {
                handler.postDelayed(this::continueAutoComment, 700L);
            }
        } else if (autoCommentSuggestions && !aiCommentReady) {
            analyzeCurrentScreen();
        } else {
            resetPostingWorkflow("Comment text is unavailable; workflow stopped");
        }
    }

    private boolean hasCommentComposer(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence cls = node.getClassName();
        CharSequence hint = Build.VERSION.SDK_INT >= 26 ? node.getHintText() : null;
        CharSequence desc = node.getContentDescription();
        String label = ((hint == null ? "" : hint.toString()) + " " +
            (desc == null ? "" : desc.toString())).toLowerCase(Locale.ROOT);
        boolean editable = node.isEditable() || (cls != null && cls.toString().toLowerCase(Locale.ROOT).contains("edittext"));
        if (editable && (label.contains("comment") || label.contains("add a comment"))) return true;
        for (int i=0; i<node.getChildCount(); i++) if (hasCommentComposer(node.getChild(i))) return true;
        return false;
    }

    private boolean clickCommentsButton(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        CharSequence cls = node.getClassName();
        String label = ((text == null ? "" : text.toString()) + " " +
            (desc == null ? "" : desc.toString())).toLowerCase(Locale.ROOT).trim();
        boolean editable = node.isEditable() || (cls != null && cls.toString().toLowerCase(Locale.ROOT).contains("edittext"));
        boolean commentControl = !editable && (label.matches(".*\\bcomments?\\b.*") || label.contains("view comments"));
        if (commentControl && node.isEnabled() && node.isClickable() && !label.contains("post comment")) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        for (int i=0; i<node.getChildCount(); i++) if (clickCommentsButton(node.getChild(i))) return true;
        return false;
    }

    private boolean clickPostButton(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        CharSequence cls = node.getClassName();
        String label = ((text == null ? "" : text.toString()) + " " +
            (desc == null ? "" : desc.toString())).toLowerCase(Locale.ROOT).trim();
        String classLabel = cls == null ? "" : cls.toString().toLowerCase(Locale.ROOT);
        boolean buttonLike = node.isClickable() && (classLabel.contains("button") || node.isClickable());
        if (buttonLike && node.isEnabled() && (label.equals("post") || label.equals("post comment") ||
            label.equals("send") || label.equals("send comment"))) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        for (int i=0; i<node.getChildCount(); i++) if (clickPostButton(node.getChild(i))) return true;
        return false;
    }

    private void submitCommentAndAdvance() {
        commentSubmitScheduled = false;
        if (!autoPostingEnabled || !postingWorkflowBusy) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !isTikTokPackage(root.getPackageName().toString())) {
            resetPostingWorkflow("Posting cancelled: TikTok is no longer active");
            return;
        }
        if (!clickPostButton(root)) {
            resetPostingWorkflow("Post button not recognized • comment was not submitted");
            return;
        }
        submittedCommentCount++;
        lastPostedSignature = activePostingSignature;
        lastCommentPostedAt = System.currentTimeMillis();
        FloatingOverlayService.updateVisionStatus("Submit tapped • count " + submittedCommentCount + "/" +
            maxCommentsPerSession + " • moving to next post");
        postingWorkflowBusy = false;
        pendingComment = "";
        handler.postDelayed(() -> {
            if (!autoPostingEnabled) return;
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(() -> {
                if (!autoPostingEnabled) return;
                if (submittedCommentCount >= maxCommentsPerSession) {
                    autoPostingEnabled = false;
                    getSharedPreferences("autoclicker_private", MODE_PRIVATE).edit().putBoolean("auto_post_comments", false).apply();
                    FloatingOverlayService.updateVisionStatus("Session limit reached • " + submittedCommentCount + " comments submitted");
                } else {
                    swipeNext();
                    handler.postDelayed(() -> {
                        if (autoPostingEnabled) FloatingOverlayService.updateVisionStatus("Next post • submitted " +
                            submittedCommentCount + "/" + maxCommentsPerSession + " • checking metrics");
                    }, 900L);
                }
            }, 650L);
        }, 500L);
    }

    private void resetPostingWorkflow(String message) {
        postingWorkflowBusy = false;
        commentSubmitScheduled = false;
        aiCommentReady = false;
        pendingComment = "";
        FloatingOverlayService.updateVisionStatus(message);
    }

    public int getSubmittedCommentCount() { return submittedCommentCount; }
    public boolean isAutoPostingEnabled() { return autoPostingEnabled; }

    public void configureCommentPosting(boolean enabled, int maxPerSession) {
        autoPostingEnabled = enabled;
        maxCommentsPerSession = Math.max(1, Math.min(5, maxPerSession));
        if (!enabled) {
            postingWorkflowBusy = false;
            commentSubmitScheduled = false;
            aiCommentReady = false;
            pendingComment = "";
        } else {
            submittedCommentCount = 0;
            lastPostedSignature = "";
            lastCommentPostedAt = 0L;
        }
        getSharedPreferences("autoclicker_private", MODE_PRIVATE).edit()
            .putBoolean("auto_post_comments", autoPostingEnabled)
            .putInt("max_comments_per_session", maxCommentsPerSession).apply();
        FloatingOverlayService.updateVisionStatus(enabled
            ? "Auto-comment ON • max " + maxCommentsPerSession + " posts per run • stop anytime"
            : "Auto-comment OFF • submitted this run: " + submittedCommentCount);
    }

    public void configureCommentAutomation(String template, boolean useTemplate, boolean useAI) {
        reusableComment = template == null ? "" : template.trim();
        if (reusableComment.length() > 500) reusableComment = reusableComment.substring(0, 500);
        templateCommentEnabled = useTemplate;
        autoCommentSuggestions = useAI;
        getSharedPreferences("autoclicker_private", MODE_PRIVATE).edit()
            .putString("reusable_comment", reusableComment)
            .putBoolean("template_comment_enabled", templateCommentEnabled)
            .putBoolean("auto_comment_suggestions", autoCommentSuggestions).apply();
        FloatingOverlayService.updateVisionStatus("Comment mode saved • auto-post " + (autoPostingEnabled ? "ON" : "OFF"));
    }

    public boolean isAutoNext() { return autoNext; }
    public boolean isAutoLike() { return autoLike; }
    public boolean isMetricMode() { return metricMode; }
    public boolean isAutoCommentSuggestions() { return autoCommentSuggestions; }

    public void configureAutomationRules(int likes, int comments, int cooldownSeconds, boolean useMetrics, boolean suggestComments) {
        minimumLikes = Math.max(0, Math.min(likes, 1000000000));
        minimumComments = Math.max(0, Math.min(comments, 1000000000));
        commentCooldownMs = Math.max(15, Math.min(cooldownSeconds, 86400)) * 1000L;
        metricMode = useMetrics;
        autoCommentSuggestions = suggestComments;
        getSharedPreferences("autoclicker_private", MODE_PRIVATE).edit()
            .putInt("minimum_likes", minimumLikes).putInt("minimum_comments", minimumComments)
            .putInt("comment_cooldown_seconds", (int)(commentCooldownMs / 1000L))
            .putBoolean("metric_mode", metricMode).putBoolean("auto_comment_suggestions", autoCommentSuggestions).apply();
        FloatingOverlayService.updateVisionStatus("Rules saved • min likes " + minimumLikes + ", comments " + minimumComments);
    }

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
            if (!metricMode && isTikTokForeground() && now - lastAutoNextAt >= 12000L) {
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
            autoNext = false; autoLike = false; autoPostingEnabled = false; postingWorkflowBusy = false;
            handler.removeCallbacks(autoNextTask);
            getSharedPreferences("autoclicker_private", MODE_PRIVATE).edit().putBoolean("auto_post_comments", false).apply();
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