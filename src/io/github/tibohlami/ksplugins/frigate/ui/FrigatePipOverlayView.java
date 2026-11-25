// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.tibohlami.ksplugins.frigate.api.FrigateApiClient;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import io.github.tibohlami.ksplugins.frigate.model.FrigateEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Compact Picture-in-Picture live camera card displayed over the dashboard/screensaver
 * when matching Frigate activity is detected.
 */
public class FrigatePipOverlayView {

    public interface Listener {
        void onOpenCamera(String cameraName);
        void onDismiss();
    }

    private final Context context;
    private KsTheme theme;
    private final FrigateConfig config;
    private final String cameraName;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final FrigateWebStreamController webStreamController;
    private final FrigateMjpegStreamController mjpegController;

    private FrameLayout rootCard;
    private FrameLayout closeBtn;
    private WebView webView;
    private ImageView mjpegView;
    private View borderOverlay;
    private LinearLayout topBadgesRow;
    private String lastBorderKey = "";
    private String lastBadgesKey = "";
    private volatile boolean destroyed = false;
    private volatile int streamSessionId = 1;

    public FrigatePipOverlayView(Context context,
                                 KsTheme theme,
                                 PluginHost host,
                                 FrigateApiClient apiClient,
                                 FrigateConfig config,
                                 String cameraName,
                                 Listener listener) {
        this.context = context;
        this.theme = theme;
        this.config = config;
        this.cameraName = cameraName;
        this.listener = listener;

        this.webStreamController = new FrigateWebStreamController(
                host, config, mainHandler,
                new FrigateWebStreamController.Listener() {
                    @Override public boolean isClosed() { return destroyed; }
                    @Override public boolean isLiveStreamMode() { return true; }
                    @Override public boolean isHlsPlayback() { return false; }
                    @Override public int getStreamSessionId() { return streamSessionId; }
                    @Override public void onLiveMseStarting() {}

                    @Override
                    public void onMsePlaying(int sessionId, boolean hasAudio) {
                        if (destroyed || sessionId != streamSessionId) return;
                        if (mjpegView != null) mjpegView.setVisibility(View.GONE);
                        if (webView != null) webView.setVisibility(View.VISIBLE);
                    }

                    @Override public void onMseFps(int sessionId, int fps) {}
                    @Override public void onWebAutoMuted(int sessionId) {}

                    @Override
                    public void onMseFallback(int sessionId, String reason) {
                        if (destroyed || sessionId != streamSessionId) return;
                        startMjpegFallback();
                    }

                    @Override public void onHlsPlaying(int sessionId, int durationMs, boolean hasAudio) {}
                    @Override public void onHlsProgress(int sessionId, int currentMs, int durationMs) {}
                    @Override public void onHlsFps(int sessionId, int fps) {}
                    @Override public void onHlsVideoSize(int sessionId, int width, int height) {}
                    @Override public void onHlsEnded(int sessionId) {}
                    @Override public void onHlsFallback(int sessionId, String reason) {}
                });

        this.mjpegController = new FrigateMjpegStreamController(
                apiClient, config, mainHandler,
                new FrigateMjpegStreamController.Listener() {
                    @Override public boolean isClosed() { return destroyed; }
                    @Override public boolean isLiveStreamMode() { return true; }
                    @Override public boolean isLivePaused() { return false; }
                    @Override public int getStreamSessionId() { return streamSessionId; }
                    @Override public void onMjpegStarting(int sessionId) {}

                    @Override
                    public void onMjpegFrame(int sessionId, Bitmap frame) {
                        if (destroyed || sessionId != streamSessionId || mjpegView == null || frame == null) return;
                        mjpegView.setImageBitmap(frame);
                    }

                    @Override public void onMjpegFps(int sessionId, int fps) {}
                    @Override public void onMjpegError(int sessionId, String errorMessage) {}
                });
    }

    public String getCameraName() {
        return cameraName;
    }

    public View buildView(boolean hasAlert, Set<String> labels, int extraActiveCameras) {
        rootCard = new FrameLayout(context) {
            private float downX;
            private float downY;
            private boolean tappedClose = false;

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    downX = ev.getX();
                    downY = ev.getY();
                    tappedClose = closeBtn != null
                            && ev.getX() >= closeBtn.getLeft() - s(6)
                            && ev.getX() <= closeBtn.getRight() + s(6)
                            && ev.getY() >= closeBtn.getTop() - s(6)
                            && ev.getY() <= closeBtn.getBottom() + s(6);
                    return true;
                } else if (action == MotionEvent.ACTION_UP) {
                    float dx = Math.abs(ev.getX() - downX);
                    float dy = Math.abs(ev.getY() - downY);
                    if (dx < s(16) && dy < s(16) && !destroyed && listener != null) {
                        if (tappedClose) {
                            listener.onDismiss();
                        } else {
                            listener.onOpenCamera(cameraName);
                        }
                    }
                    tappedClose = false;
                    return true;
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    tappedClose = false;
                    return true;
                }
                return true;
            }
        };
        rootCard.setClipToOutline(true);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#0B101A"));
        cardBg.setCornerRadius(s(14));
        rootCard.setBackground(cardBg);
        rootCard.setClickable(true);

        // 1. Live WebView (go2rtc MSE)
        webView = webStreamController.createWebView(context);
        rootCard.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 2. MJPEG Fallback ImageView
        mjpegView = new ImageView(context);
        mjpegView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mjpegView.setVisibility(View.GONE);
        rootCard.addView(mjpegView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 3. Activity Border Highlight
        borderOverlay = new View(context);
        borderOverlay.setClickable(false);
        borderOverlay.setFocusable(false);
        rootCard.addView(borderOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 4. Top-left object pills + "+N" multi-camera indicator
        topBadgesRow = new LinearLayout(context);
        topBadgesRow.setOrientation(LinearLayout.HORIZONTAL);
        topBadgesRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams badgesLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        badgesLp.gravity = Gravity.TOP | Gravity.LEFT;
        badgesLp.setMargins(s(10), s(10), s(44), 0);
        rootCard.addView(topBadgesRow, badgesLp);

        // 5. Top-right dismiss button
        closeBtn = new FrameLayout(context);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(Color.argb(215, 15, 23, 42));
        closeBg.setCornerRadius(s(14));
        closeBg.setStroke(s(1), Color.argb(110, 255, 255, 255));
        closeBtn.setBackground(closeBg);
        ImageView closeIcon = MaterialIcons.createIconView(context, MaterialIcons.CLOSE, Color.WHITE, s(16), theme);
        closeBtn.addView(closeIcon, new FrameLayout.LayoutParams(s(16), s(16), Gravity.CENTER));
        closeBtn.setOnClickListener(v -> {
            if (!destroyed && listener != null) {
                listener.onDismiss();
            }
        });
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(s(28), s(28));
        closeLp.gravity = Gravity.TOP | Gravity.RIGHT;
        closeLp.setMargins(0, s(10), s(10), 0);
        rootCard.addView(closeBtn, closeLp);

        // 6. Bottom-left camera name pill
        LinearLayout nameBadge = new LinearLayout(context);
        nameBadge.setOrientation(LinearLayout.HORIZONTAL);
        nameBadge.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable nbBg = new GradientDrawable();
        nbBg.setColor(Color.argb(215, 15, 23, 42));
        nbBg.setCornerRadius(s(8));
        nameBadge.setBackground(nbBg);
        nameBadge.setPadding(s(8), s(4), s(8), s(4));
        ImageView camIcon = MaterialIcons.createIconView(context, MaterialIcons.CAMERA, Color.WHITE, s(13), theme);
        LinearLayout.LayoutParams ciLp = new LinearLayout.LayoutParams(s(13), s(13));
        ciLp.rightMargin = s(5);
        nameBadge.addView(camIcon, ciLp);

        TextView nameText = new TextView(context);
        nameText.setText(cameraName);
        nameText.setTextColor(Color.WHITE);
        nameText.setTextSize(sp(11));
        nameText.setTypeface(FrigateTheme.typeface(theme, 600));
        nameText.setIncludeFontPadding(false);
        nameText.setSingleLine(true);
        nameBadge.addView(nameText);

        FrameLayout.LayoutParams nbLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        nbLp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        nbLp.setMargins(s(10), 0, s(10), s(10));
        rootCard.addView(nameBadge, nbLp);

        updateActivity(hasAlert, labels, extraActiveCameras);
        startLiveStream();
        return rootCard;
    }

    public void updateActivity(boolean hasAlert, Set<String> labels, int extraActiveCameras) {
        if (destroyed) return;
        String borderKey = hasAlert ? "alert" : "detection";
        if (borderOverlay != null && !borderKey.equals(lastBorderKey)) {
            lastBorderKey = borderKey;
            int borderAccent = hasAlert ? Color.parseColor("#F43F5E") : Color.parseColor("#F59E0B");
            GradientDrawable hlBg = new GradientDrawable();
            hlBg.setColor(Color.TRANSPARENT);
            hlBg.setCornerRadius(s(14));
            hlBg.setStroke(s(3), borderAccent);
            borderOverlay.setBackground(hlBg);
        }

        if (topBadgesRow != null) {
            List<String> sorted = labels != null ? new ArrayList<>(labels) : Collections.emptyList();
            StringBuilder kb = new StringBuilder();
            int cap = 0;
            for (String lbl : sorted) {
                if (cap++ >= 3) break;
                kb.append(lbl).append('|');
            }
            kb.append("extra=").append(extraActiveCameras);
            String newKey = kb.toString();
            if (newKey.equals(lastBadgesKey)) return;
            lastBadgesKey = newKey;

            topBadgesRow.removeAllViews();
            int pillAccent = hasAlert ? Color.parseColor("#F43F5E") : Color.parseColor("#F59E0B");
            int shown = 0;
            for (String label : sorted) {
                if (shown >= 3) break;
                String clean = label != null ? label.trim().toLowerCase(Locale.ROOT) : "";
                if (clean.isEmpty() || "motion".equals(clean)) continue;

                FrameLayout pill = new FrameLayout(context);
                pill.setPadding(s(6), s(5), s(6), s(5));
                GradientDrawable pillBg = new GradientDrawable();
                int bgTint = Color.argb(230,
                        (int) (15 * 0.65 + Color.red(pillAccent) * 0.35),
                        (int) (23 * 0.65 + Color.green(pillAccent) * 0.35),
                        (int) (42 * 0.65 + Color.blue(pillAccent) * 0.35));
                pillBg.setColor(bgTint);
                pillBg.setCornerRadius(s(8));
                pillBg.setStroke(s(1.5f), pillAccent);
                pill.setBackground(pillBg);

                ImageView iconView = new ImageView(context);
                iconView.setImageDrawable(MaterialIcons.getDrawable(
                        FrigateEvent.getMaterialIconForLabel(clean), pillAccent, s(15), theme));
                iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                pill.addView(iconView, new FrameLayout.LayoutParams(s(15), s(15), Gravity.CENTER));

                LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (shown > 0) pLp.leftMargin = s(5);
                topBadgesRow.addView(pill, pLp);
                shown++;
            }

            if (extraActiveCameras > 0) {
                TextView morePill = new TextView(context);
                morePill.setText("+" + extraActiveCameras);
                morePill.setTextColor(Color.WHITE);
                morePill.setTextSize(sp(11));
                morePill.setTypeface(FrigateTheme.typeface(theme, 700));
                morePill.setIncludeFontPadding(false);
                morePill.setGravity(Gravity.CENTER);
                morePill.setPadding(s(7), s(4), s(7), s(4));
                GradientDrawable moreBg = new GradientDrawable();
                moreBg.setColor(Color.argb(230, 15, 23, 42));
                moreBg.setCornerRadius(s(8));
                moreBg.setStroke(s(1.5f), Color.argb(180, 255, 255, 255));
                morePill.setBackground(moreBg);
                LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (shown > 0) mLp.leftMargin = s(5);
                topBadgesRow.addView(morePill, mLp);
            }
        }
    }

    public void onThemeChanged(KsTheme theme) {
        if (theme != null) {
            this.theme = theme;
        }
    }

    public void destroy() {
        destroyed = true;
        streamSessionId++;
        mainHandler.removeCallbacksAndMessages(null);
        webStreamController.destroy();
        mjpegController.stop();
        if (mjpegView != null) {
            mjpegView.setImageDrawable(null);
        }
    }

    private void startLiveStream() {
        boolean muted = config == null || !config.isPipEnableAudio();
        webStreamController.startLiveMse(cameraName, streamSessionId, muted);
    }

    private void startMjpegFallback() {
        if (destroyed || mjpegView == null) return;
        mjpegView.setVisibility(View.VISIBLE);
        if (webView != null) webView.setVisibility(View.GONE);
        mjpegController.start(cameraName, streamSessionId);
    }

    private int s(float dp) {
        float scale = (config != null) ? config.getUiScale() : 1.0f;
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density * scale);
    }

    private float sp(float sp) {
        float scale = (config != null) ? config.getUiScale() : 1.0f;
        return sp * scale;
    }
}
