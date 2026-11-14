// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.ui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * Material 3 theme token and styling helper consuming Kiosk Satellite native {@link KsTheme}.
 * Provides safe fallback colors when running outside host or when specific roles are omitted.
 */
public final class FrigateTheme {

    private FrigateTheme() {}

    /**
     * Reads a color role safely from the host theme or falls back to the default ARGB value.
     */
    public static int color(KsTheme theme, String role, int fallback) {
        if (theme != null) {
            try {
                return theme.color(role);
            } catch (Throwable ignored) {}
        }
        return fallback;
    }

    public static boolean isDark(KsTheme theme) {
        return theme == null || theme.isDark();
    }

    // --- Color Tokens ---

    public static int surface(KsTheme theme) {
        return color(theme, "surface", Color.parseColor("#0C0E14"));
    }

    public static int surfaceContainer(KsTheme theme) {
        return color(theme, "surfaceContainer", Color.parseColor("#171B26"));
    }

    public static int surfaceContainerHigh(KsTheme theme) {
        return color(theme, "surfaceContainerHigh", Color.parseColor("#1E273A"));
    }

    public static int surfaceContainerHighest(KsTheme theme) {
        return color(theme, "surfaceContainerHighest", Color.parseColor("#2A374F"));
    }

    public static int surfaceContainerLow(KsTheme theme) {
        return color(theme, "surfaceContainerLow", Color.parseColor("#131722"));
    }

    public static int onSurface(KsTheme theme) {
        return color(theme, "onSurface", Color.parseColor("#F1F5F9"));
    }

    public static int onSurfaceVariant(KsTheme theme) {
        return color(theme, "onSurfaceVariant", Color.parseColor("#94A3B8"));
    }

    public static int outlineVariant(KsTheme theme) {
        return color(theme, "outlineVariant", Color.parseColor("#2A374F"));
    }

    public static int outline(KsTheme theme) {
        return color(theme, "outline", Color.parseColor("#334155"));
    }

    public static int primary(KsTheme theme) {
        return color(theme, "primary", Color.parseColor("#E53935"));
    }

    public static int onPrimary(KsTheme theme) {
        return color(theme, "onPrimary", Color.WHITE);
    }

    public static int success(KsTheme theme) {
        return color(theme, "success", Color.parseColor("#10B981"));
    }

    public static int error(KsTheme theme) {
        return color(theme, "error", Color.parseColor("#EF4444"));
    }

    public static int onError(KsTheme theme) {
        return color(theme, "onError", Color.WHITE);
    }

    // --- Typography & Radii ---

    public static Typeface typeface(KsTheme theme, int weight) {
        if (theme != null) {
            try {
                return theme.typeface(weight);
            } catch (Throwable ignored) {}
        }
        return (weight >= 600) ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
    }

    public static void applyTypography(TextView view, KsTheme theme, int weight, int textColor) {
        if (view == null) return;
        view.setTypeface(typeface(theme, weight));
        view.setTextColor(textColor);
    }

    public static int radiusCard(KsTheme theme) {
        return (theme != null) ? theme.radiusCard() : 24;
    }

    public static int radiusRow(KsTheme theme) {
        return (theme != null) ? theme.radiusRow() : 14;
    }

    public static int radiusControl(KsTheme theme) {
        return (theme != null) ? theme.radiusControl() : 12;
    }

    /**
     * Builds a standard card background using theme tokens.
     */
    public static GradientDrawable createCardBackground(KsTheme theme, int cornerRadiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(surfaceContainer(theme));
        d.setCornerRadius(cornerRadiusPx);
        d.setStroke(Math.max(1, Math.round(cornerRadiusPx > 0 ? 1 : 0)), outlineVariant(theme));
        return d;
    }
}
