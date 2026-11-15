// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import io.github.tibohlami.ksplugins.frigate.model.FrigateEvent;
import java.util.Collections;
import java.util.List;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * Shared UI interaction helpers (pressed states and touch feedback).
 */
public final class FrigateUiUtils {

    private FrigateUiUtils() {}

    /**
     * Applies a pressed-state {@link StateListDrawable} and immediate touch scale/alpha feedback
     * so close buttons visibly react while pressed and during overlay teardown.
     */
    @SuppressLint("ClickableViewAccessibility")
    public static void applyCloseButtonFeedback(View button,
                                                int normalFillColor,
                                                int pressedFillColor,
                                                int normalStrokeColor,
                                                int pressedStrokeColor,
                                                int cornerRadiusPx,
                                                int strokeWidthPx) {
        if (button == null) return;

        GradientDrawable normalBg = new GradientDrawable();
        normalBg.setColor(normalFillColor);
        normalBg.setCornerRadius(cornerRadiusPx);
        normalBg.setStroke(strokeWidthPx, normalStrokeColor);

        GradientDrawable pressedBg = new GradientDrawable();
        pressedBg.setColor(pressedFillColor);
        pressedBg.setCornerRadius(cornerRadiusPx);
        pressedBg.setStroke(Math.max(strokeWidthPx, strokeWidthPx * 2), pressedStrokeColor);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressedBg);
        states.addState(new int[]{}, normalBg);

        button.setBackground(states);
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnTouchListener((v, event) -> {
            if (!v.isEnabled()) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.setScaleX(0.93f);
                v.setScaleY(0.93f);
                v.setAlpha(0.85f);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.setScaleX(1.0f);
                v.setScaleY(1.0f);
                v.setAlpha(1.0f);
            }
            return false;
        });
    }

    /**
     * Marks a close button as actively closing to prevent duplicate clicks and keep visual feedback.
     */
    public static void markButtonClosing(View button) {
        if (button == null) return;
        button.setEnabled(false);
        button.setScaleX(0.96f);
        button.setScaleY(0.96f);
        button.setAlpha(0.6f);
    }

    /**
     * Populates a thumbnail header pill with icons for all detected objects in an event/review item
     * (falling back to label text only for unmapped objects) and an optional single-object score.
     */
    public static void populateEventObjectsBadge(LinearLayout badge, Context context, FrigateConfig config,
                                                 KsTheme theme, FrigateEvent event,
                                                 int iconSizePx, int gapPx, float textSizeSp) {
        if (badge == null || context == null || event == null) return;
        List<String> objs = event.getObjects();
        if (objs == null || objs.isEmpty()) {
            String fallback = event.getLabel();
            objs = (fallback != null && !fallback.isEmpty())
                    ? Collections.singletonList(fallback)
                    : Collections.singletonList("motion");
        }

        int maxIcons = 4;
        int shown = Math.min(objs.size(), maxIcons);
        int extra = objs.size() - shown;
        int score = (objs.size() == 1) ? (int) Math.round(event.getTopScore() * 100) : 0;

        for (int i = 0; i < shown; i++) {
            String objLabel = objs.get(i);
            String iconName = FrigateEvent.getMaterialIconForLabel(objLabel);
            boolean hasDedicatedIcon = !MaterialIcons.TAG.equals(iconName);
            boolean hasTextAfter = !hasDedicatedIcon || (i == shown - 1 && (score > 0 || extra > 0));
            boolean hasMoreIconsAfter = (i < shown - 1);

            ImageView iconView = MaterialIcons.createIconView(context, iconName, Color.WHITE, iconSizePx, theme);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSizePx, iconSizePx);
            iconLp.gravity = Gravity.CENTER_VERTICAL;
            if (hasTextAfter || hasMoreIconsAfter) {
                iconLp.rightMargin = gapPx;
            }
            badge.addView(iconView, iconLp);

            if (!hasDedicatedIcon) {
                String text = FrigateI18n.translateObjectLabel(context, config, objLabel);
                if (i == shown - 1 && score > 0) {
                    text += " " + score + "%";
                }
                TextView tv = createBadgeLabel(context, theme, text, textSizeSp);
                LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                tvLp.gravity = Gravity.CENTER_VERTICAL;
                if (hasMoreIconsAfter || (i == shown - 1 && extra > 0)) {
                    tvLp.rightMargin = gapPx;
                }
                badge.addView(tv, tvLp);
            } else if (i == shown - 1 && score > 0) {
                TextView tv = createBadgeLabel(context, theme, score + "%", textSizeSp);
                LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                tvLp.gravity = Gravity.CENTER_VERTICAL;
                if (extra > 0) {
                    tvLp.rightMargin = gapPx;
                }
                badge.addView(tv, tvLp);
            }
        }

        if (extra > 0) {
            TextView extraTv = createBadgeLabel(context, theme, "+" + extra, textSizeSp);
            LinearLayout.LayoutParams extraLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            extraLp.gravity = Gravity.CENTER_VERTICAL;
            badge.addView(extraTv, extraLp);
        }
    }

    private static TextView createBadgeLabel(Context context, KsTheme theme, String text, float textSizeSp) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(textSizeSp);
        tv.setTextColor(Color.WHITE);
        tv.setTypeface(FrigateTheme.typeface(theme, 600));
        tv.setIncludeFontPadding(false);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        return tv;
    }
}
