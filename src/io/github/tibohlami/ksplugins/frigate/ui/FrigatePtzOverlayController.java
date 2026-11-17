// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import io.github.tibohlami.ksplugins.frigate.model.PtzInfo;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * Floating semi-transparent ONVIF PTZ control panel (D-pad, zoom, presets)
 * with press-and-hold MOVE/ZOOM -> STOP dispatch and auto-dismiss.
 */
public class FrigatePtzOverlayController {

    public interface Listener {
        void onPtzCommand(String command);
        void onPtzVisibilityChanged(boolean visible);
    }

    private static final long AUTO_HIDE_MS = 12_000L;

    private final Context context;
    private KsTheme theme;
    private final FrigateConfig config;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final LinearLayout panelView;
    private PtzInfo ptzInfo = PtzInfo.UNSUPPORTED;
    private boolean panelVisible = false;
    private String activeHoldCommand = null;

    private final Runnable autoHideRunnable = () -> hide(true);

    public FrigatePtzOverlayController(Context context,
                                       KsTheme theme,
                                       FrigateConfig config,
                                       Listener listener) {
        this.context = context;
        this.theme = theme;
        this.config = config;
        this.listener = listener;

        this.panelView = new LinearLayout(context);
        this.panelView.setOrientation(LinearLayout.VERTICAL);
        this.panelView.setGravity(Gravity.CENTER_HORIZONTAL);
        this.panelView.setVisibility(View.GONE);
        this.panelView.setClickable(true);
        applyPanelBackground();
        int pad = s(12);
        this.panelView.setPadding(pad, pad, pad, pad);
    }

    public View getPanelView() {
        return panelView;
    }

    public boolean isVisible() {
        return panelVisible;
    }

    public void setPtzInfo(PtzInfo info) {
        this.ptzInfo = info != null ? info : PtzInfo.UNSUPPORTED;
        rebuildControls();
        if (!this.ptzInfo.isSupported() && panelVisible) {
            hide(false);
        }
    }

    public void onThemeChanged(KsTheme theme) {
        if (theme != null) {
            this.theme = theme;
            applyPanelBackground();
            rebuildControls();
        }
    }

    public void toggle() {
        if (panelVisible) {
            hide(true);
        } else {
            show();
        }
    }

    public void show() {
        if (!ptzInfo.isSupported()) return;
        panelVisible = true;
        panelView.animate().cancel();
        panelView.setVisibility(View.VISIBLE);
        panelView.setAlpha(0f);
        panelView.animate().alpha(1f).setDuration(160).start();
        scheduleAutoHide();
        if (listener != null) listener.onPtzVisibilityChanged(true);
    }

    public void hide(boolean animate) {
        handler.removeCallbacks(autoHideRunnable);
        releaseActiveHoldIfNeeded();
        if (!panelVisible && panelView.getVisibility() == View.GONE) return;
        panelVisible = false;
        panelView.animate().cancel();
        if (animate && panelView.getVisibility() == View.VISIBLE) {
            panelView.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> {
                        if (!panelVisible) {
                            panelView.setVisibility(View.GONE);
                        }
                    })
                    .start();
        } else {
            panelView.setAlpha(0f);
            panelView.setVisibility(View.GONE);
        }
        if (listener != null) listener.onPtzVisibilityChanged(false);
    }

    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        releaseActiveHoldIfNeeded();
        panelView.animate().cancel();
    }

    private void scheduleAutoHide() {
        handler.removeCallbacks(autoHideRunnable);
        handler.postDelayed(autoHideRunnable, AUTO_HIDE_MS);
    }

    private void releaseActiveHoldIfNeeded() {
        if (activeHoldCommand != null) {
            activeHoldCommand = null;
            if (listener != null) {
                listener.onPtzCommand("STOP");
            }
        }
    }

    private void applyPanelBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(195, 11, 16, 26));
        bg.setCornerRadius(s(18));
        bg.setStroke(s(1), Color.argb(75, 255, 255, 255));
        panelView.setBackground(bg);
    }

    private void rebuildControls() {
        panelView.removeAllViews();
        if (!ptzInfo.isSupported()) return;

        int btnSize = s(42);
        int gap = s(5);

        if (ptzInfo.hasPanTilt) {
            LinearLayout dpad = new LinearLayout(context);
            dpad.setOrientation(LinearLayout.VERTICAL);
            dpad.setGravity(Gravity.CENTER_HORIZONTAL);

            // Row 1: Up
            LinearLayout r1 = new LinearLayout(context);
            r1.setOrientation(LinearLayout.HORIZONTAL);
            r1.setGravity(Gravity.CENTER);
            r1.addView(buildHoldIconButton(MaterialIcons.ARROW_UP, "MOVE_UP", btnSize, s(22)));
            dpad.addView(r1);

            // Row 2: Left, Center, Right
            LinearLayout r2 = new LinearLayout(context);
            r2.setOrientation(LinearLayout.HORIZONTAL);
            r2.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            r2Lp.topMargin = gap;
            r2Lp.bottomMargin = gap;

            r2.addView(buildHoldIconButton(MaterialIcons.ARROW_LEFT, "MOVE_LEFT", btnSize, s(22)));
            View centerDot = new View(context);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(Color.argb(90, 148, 163, 184));
            centerDot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(s(10), s(10));
            dotLp.leftMargin = gap + s(16);
            dotLp.rightMargin = gap + s(16);
            dotLp.gravity = Gravity.CENTER_VERTICAL;
            r2.addView(centerDot, dotLp);
            r2.addView(buildHoldIconButton(MaterialIcons.ARROW_RIGHT, "MOVE_RIGHT", btnSize, s(22)));
            dpad.addView(r2, r2Lp);

            // Row 3: Down
            LinearLayout r3 = new LinearLayout(context);
            r3.setOrientation(LinearLayout.HORIZONTAL);
            r3.setGravity(Gravity.CENTER);
            r3.addView(buildHoldIconButton(MaterialIcons.ARROW_DOWN, "MOVE_DOWN", btnSize, s(22)));
            dpad.addView(r3);

            panelView.addView(dpad);
        }

        if (ptzInfo.hasZoom) {
            LinearLayout zoomRow = new LinearLayout(context);
            zoomRow.setOrientation(LinearLayout.HORIZONTAL);
            zoomRow.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams zrLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (ptzInfo.hasPanTilt) zrLp.topMargin = s(10);

            ImageView zOut = buildHoldIconButton(MaterialIcons.ZOOM_OUT, "ZOOM_OUT", s(58), s(20));
            ImageView zIn = buildHoldIconButton(MaterialIcons.ZOOM_IN, "ZOOM_IN", s(58), s(20));
            LinearLayout.LayoutParams ziLp = new LinearLayout.LayoutParams(s(58), btnSize);
            ziLp.leftMargin = s(8);
            zoomRow.addView(zOut, new LinearLayout.LayoutParams(s(58), btnSize));
            zoomRow.addView(zIn, ziLp);
            panelView.addView(zoomRow, zrLp);
        }

        if (!ptzInfo.presets.isEmpty()) {
            HorizontalScrollView scroll = new HorizontalScrollView(context);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                    s(146), LinearLayout.LayoutParams.WRAP_CONTENT);
            if (ptzInfo.hasPanTilt || ptzInfo.hasZoom) scLp.topMargin = s(10);

            LinearLayout presetRow = new LinearLayout(context);
            presetRow.setOrientation(LinearLayout.HORIZONTAL);
            presetRow.setGravity(Gravity.CENTER_VERTICAL);

            for (int i = 0; i < ptzInfo.presets.size(); i++) {
                final String preset = ptzInfo.presets.get(i);
                TextView pill = new TextView(context);
                pill.setText(preset);
                pill.setTextColor(Color.WHITE);
                pill.setTextSize(sp(12));
                pill.setTypeface(FrigateTheme.typeface(theme, 600));
                pill.setSingleLine(true);
                pill.setIncludeFontPadding(false);
                pill.setPadding(s(10), s(7), s(10), s(7));
                pill.setBackground(buildButtonBg(false));
                pill.setOnClickListener(v -> {
                    scheduleAutoHide();
                    if (listener != null) {
                        listener.onPtzCommand("preset_" + preset);
                    }
                });
                LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) pLp.leftMargin = s(6);
                presetRow.addView(pill, pLp);
            }
            scroll.addView(presetRow);
            panelView.addView(scroll, scLp);
        }
    }

    private ImageView buildHoldIconButton(String iconName, String command, int sizePx, int iconSizePx) {
        ImageView btn = new ImageView(context);
        btn.setImageDrawable(MaterialIcons.getDrawable(iconName, Color.WHITE, iconSizePx, theme));
        btn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        btn.setBackground(buildButtonBg(false));
        btn.setLayoutParams(new LinearLayout.LayoutParams(sizePx, s(42)));
        btn.setClickable(true);
        btn.setOnTouchListener((v, ev) -> {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.setBackground(buildButtonBg(true));
                activeHoldCommand = command;
                scheduleAutoHide();
                if (listener != null) listener.onPtzCommand(command);
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.setBackground(buildButtonBg(false));
                if (activeHoldCommand != null) {
                    activeHoldCommand = null;
                    scheduleAutoHide();
                    if (listener != null) listener.onPtzCommand("STOP");
                }
                return true;
            }
            return false;
        });
        return btn;
    }

    private GradientDrawable buildButtonBg(boolean pressed) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(pressed ? Color.argb(200, 56, 189, 248) : Color.argb(145, 30, 41, 59));
        bg.setCornerRadius(s(12));
        bg.setStroke(s(1), pressed ? Color.argb(220, 125, 211, 252) : Color.argb(95, 148, 163, 184));
        return bg;
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
