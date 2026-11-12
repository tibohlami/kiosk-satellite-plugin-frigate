// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.frigate.ui;

import android.annotation.SuppressLint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.MotionEvent;
import android.view.View;

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
}
