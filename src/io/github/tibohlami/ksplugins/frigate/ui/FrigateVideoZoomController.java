// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import java.util.Locale;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * Digital pinch-to-zoom (1.0x-5.0x), double-tap zoom (2.5x), and bounded pan controller
 * operating on a dedicated media viewport inside {@link FrigatePlayerOverlay}.
 */
public class FrigateVideoZoomController {

    public interface Listener {
        void onSingleTapVideo();
        boolean isErrorVisible();
    }

    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 5.0f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;
    private static final float SNAP_RESET_THRESHOLD = 1.02f;

    private final Context context;
    private final FrigateConfig config;
    private final FrameLayout zoomViewport;
    private final LinearLayout zoomResetPill;
    private TextView zoomResetLabel;
    private final Listener listener;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    private float zoomScale = MIN_SCALE;
    private float panX = 0f;
    private float panY = 0f;
    private boolean isScaling = false;
    private ValueAnimator zoomAnimator;

    public FrigateVideoZoomController(Context context,
                                      KsTheme theme,
                                      FrigateConfig config,
                                      FrameLayout zoomViewport,
                                      Listener listener) {
        this.context = context;
        this.config = config;
        this.zoomViewport = zoomViewport;
        this.listener = listener;

        this.zoomResetPill = buildZoomResetPill(theme);

        this.scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                cancelAnimator();
                isScaling = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                if (Float.isNaN(factor) || Float.isInfinite(factor) || factor <= 0f) return false;
                float targetScale = clamp(zoomScale * factor, MIN_SCALE, MAX_SCALE);
                setZoomAroundPoint(targetScale, detector.getFocusX(), detector.getFocusY());
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                isScaling = false;
                if (zoomScale <= SNAP_RESET_THRESHOLD) {
                    reset();
                }
            }
        });
        this.scaleDetector.setQuickScaleEnabled(false);

        this.gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null && !listener.isErrorVisible()) {
                    listener.onSingleTapVideo();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (listener != null && listener.isErrorVisible()) return false;
                if (zoomScale > SNAP_RESET_THRESHOLD) {
                    animateZoomTo(MIN_SCALE, zoomViewport.getWidth() / 2f, zoomViewport.getHeight() / 2f);
                } else {
                    animateZoomTo(DOUBLE_TAP_SCALE, e.getX(), e.getY());
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (isScaling || zoomScale <= MIN_SCALE) return false;
                panX -= distanceX;
                panY -= distanceY;
                applyTransform();
                return true;
            }
        });
    }

    public View getZoomResetPill() {
        return zoomResetPill;
    }

    public boolean isZoomed() {
        return zoomScale > MIN_SCALE;
    }

    public boolean onTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        gestureDetector.onTouchEvent(ev);
        return true;
    }

    public void onViewportSizeChanged() {
        applyTransform();
    }

    public void reset() {
        cancelAnimator();
        zoomScale = MIN_SCALE;
        panX = 0f;
        panY = 0f;
        applyTransform();
    }

    public void destroy() {
        cancelAnimator();
    }

    private void animateZoomTo(final float targetScale, final float focusX, final float focusY) {
        cancelAnimator();
        final float startScale = zoomScale;
        final float startPanX = panX;
        final float startPanY = panY;

        int w = zoomViewport.getWidth();
        int h = zoomViewport.getHeight();
        float targetPanX = 0f;
        float targetPanY = 0f;
        if (targetScale > MIN_SCALE && w > 0 && h > 0) {
            float cx = focusX - (w / 2f);
            float cy = focusY - (h / 2f);
            float k = targetScale / Math.max(MIN_SCALE, startScale);
            float maxTx = (w * (targetScale - 1f)) / 2f;
            float maxTy = (h * (targetScale - 1f)) / 2f;
            targetPanX = clamp(cx - (cx - startPanX) * k, -maxTx, maxTx);
            targetPanY = clamp(cy - (cy - startPanY) * k, -maxTy, maxTy);
        }

        final float endPanX = targetPanX;
        final float endPanY = targetPanY;
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f);
        zoomAnimator.setDuration(200L);
        zoomAnimator.setInterpolator(new DecelerateInterpolator());
        zoomAnimator.addUpdateListener(anim -> {
            float t = (Float) anim.getAnimatedValue();
            zoomScale = startScale + (targetScale - startScale) * t;
            panX = startPanX + (endPanX - startPanX) * t;
            panY = startPanY + (endPanY - startPanY) * t;
            if (targetScale <= MIN_SCALE && t >= 0.999f) {
                zoomScale = MIN_SCALE;
                panX = 0f;
                panY = 0f;
            }
            applyTransform();
        });
        zoomAnimator.start();
    }

    private void setZoomAroundPoint(float newScale, float focusX, float focusY) {
        float oldScale = zoomScale;
        zoomScale = clamp(newScale, MIN_SCALE, MAX_SCALE);
        if (zoomScale <= MIN_SCALE) {
            panX = 0f;
            panY = 0f;
        } else {
            int w = zoomViewport.getWidth();
            int h = zoomViewport.getHeight();
            if (w > 0 && h > 0 && oldScale > 0f) {
                float cx = focusX - (w / 2f);
                float cy = focusY - (h / 2f);
                float k = zoomScale / oldScale;
                panX = cx - (cx - panX) * k;
                panY = cy - (cy - panY) * k;
            }
        }
        applyTransform();
    }

    private void applyTransform() {
        if (zoomViewport == null) return;
        int w = zoomViewport.getWidth();
        int h = zoomViewport.getHeight();
        if (w > 0 && h > 0) {
            zoomViewport.setPivotX(w / 2f);
            zoomViewport.setPivotY(h / 2f);
            if (zoomScale <= MIN_SCALE) {
                panX = 0f;
                panY = 0f;
            } else {
                float maxTx = (w * (zoomScale - 1f)) / 2f;
                float maxTy = (h * (zoomScale - 1f)) / 2f;
                panX = clamp(panX, -maxTx, maxTx);
                panY = clamp(panY, -maxTy, maxTy);
            }
        }
        zoomViewport.setScaleX(zoomScale);
        zoomViewport.setScaleY(zoomScale);
        zoomViewport.setTranslationX(panX);
        zoomViewport.setTranslationY(panY);
        updateZoomResetPill();
    }

    private void updateZoomResetPill() {
        if (zoomResetPill == null || zoomResetLabel == null) return;
        if (zoomScale > SNAP_RESET_THRESHOLD) {
            zoomResetLabel.setText(String.format(Locale.US, "%.1fx", zoomScale));
            if (zoomResetPill.getVisibility() != View.VISIBLE) {
                zoomResetPill.setVisibility(View.VISIBLE);
            }
        } else if (zoomResetPill.getVisibility() != View.GONE) {
            zoomResetPill.setVisibility(View.GONE);
        }
    }

    private LinearLayout buildZoomResetPill(KsTheme theme) {
        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setVisibility(View.GONE);
        pill.setPadding(s(10), s(6), s(8), s(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(215, 15, 23, 42));
        bg.setCornerRadius(s(FrigateTheme.radiusControl(theme)));
        bg.setStroke(s(1), Color.argb(120, 255, 255, 255));
        pill.setBackground(bg);

        zoomResetLabel = new TextView(context);
        zoomResetLabel.setTextColor(Color.WHITE);
        zoomResetLabel.setTextSize(sp(12));
        zoomResetLabel.setTypeface(FrigateTheme.typeface(theme, 700));
        zoomResetLabel.setIncludeFontPadding(false);
        zoomResetLabel.setGravity(Gravity.CENTER_VERTICAL);
        pill.addView(zoomResetLabel);

        ImageView closeIcon = new ImageView(context);
        closeIcon.setImageDrawable(MaterialIcons.getDrawable(MaterialIcons.CLOSE, Color.WHITE, s(14), theme));
        closeIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(s(14), s(14));
        iconLp.gravity = Gravity.CENTER_VERTICAL;
        iconLp.leftMargin = s(5);
        pill.addView(closeIcon, iconLp);

        pill.setOnClickListener(v -> animateZoomTo(MIN_SCALE,
                zoomViewport != null ? zoomViewport.getWidth() / 2f : 0f,
                zoomViewport != null ? zoomViewport.getHeight() / 2f : 0f));
        return pill;
    }

    private void cancelAnimator() {
        if (zoomAnimator != null) {
            zoomAnimator.cancel();
            zoomAnimator = null;
        }
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

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }
}
