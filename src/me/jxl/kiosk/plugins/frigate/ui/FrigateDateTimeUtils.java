// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.frigate.ui;

import android.content.Context;
import android.os.Build;
import android.text.format.DateFormat;
import me.jxl.kiosk.plugins.frigate.config.FrigateConfig;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Helpers for formatting dates and times using the effective Locale
 * (tablet default or plugin language override) and 24-hour vs AM/PM preference.
 */
public final class FrigateDateTimeUtils {

    private static final ThreadLocal<Map<String, SimpleDateFormat>> FORMAT_CACHE =
            ThreadLocal.withInitial(HashMap::new);

    private FrigateDateTimeUtils() {}

    private static SimpleDateFormat getCachedFormat(Locale locale, String skeleton, String fallbackPattern) {
        Locale loc = (locale != null) ? locale : Locale.getDefault();
        String key = skeleton + "|" + fallbackPattern + "|" + loc.toLanguageTag();
        Map<String, SimpleDateFormat> map = FORMAT_CACHE.get();
        SimpleDateFormat cached = map.get(key);
        if (cached == null) {
            cached = createFormat(loc, skeleton, fallbackPattern);
            map.put(key, cached);
        }
        return cached;
    }

    public static boolean is24Hour(Context context) {
        return is24Hour(context, null);
    }

    public static boolean is24Hour(Context context, FrigateConfig config) {
        if (context != null) {
            return DateFormat.is24HourFormat(context);
        }
        Locale loc = FrigateI18n.getEffectiveLocale(null, config);
        java.text.DateFormat df = java.text.DateFormat.getTimeInstance(
                java.text.DateFormat.SHORT, loc);
        if (df instanceof SimpleDateFormat) {
            String pattern = ((SimpleDateFormat) df).toPattern();
            return pattern != null && !pattern.contains("a");
        }
        return true;
    }

    public static SimpleDateFormat createFormat(Locale locale, String skeleton, String fallbackPattern) {
        Locale loc = (locale != null) ? locale : Locale.getDefault();
        String pattern = fallbackPattern;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            String best = DateFormat.getBestDateTimePattern(loc, skeleton);
            if (best != null && !best.trim().isEmpty()) {
                pattern = best;
            }
        }
        return new SimpleDateFormat(pattern, loc);
    }

    public static SimpleDateFormat createTimeShortFormat(Context context) {
        return createTimeShortFormat(context, null);
    }

    public static SimpleDateFormat createTimeShortFormat(Context context, FrigateConfig config) {
        boolean use24 = is24Hour(context, config);
        Locale loc = FrigateI18n.getEffectiveLocale(context, config);
        return createFormat(loc, use24 ? "Hm" : "hm", use24 ? "HH:mm" : "h:mm a");
    }

    public static SimpleDateFormat createTimeWithSecondsFormat(Context context) {
        return createTimeWithSecondsFormat(context, null);
    }

    public static SimpleDateFormat createTimeWithSecondsFormat(Context context, FrigateConfig config) {
        boolean use24 = is24Hour(context, config);
        Locale loc = FrigateI18n.getEffectiveLocale(context, config);
        return createFormat(loc, use24 ? "Hms" : "hms", use24 ? "HH:mm:ss" : "h:mm:ss a");
    }

    public static SimpleDateFormat createShortMonthDayFormat(Context context, FrigateConfig config) {
        Locale loc = FrigateI18n.getEffectiveLocale(context, config);
        return createFormat(loc, "MMdd", "dd/MM");
    }

    public static String formatShortNumericDate(Context context, FrigateConfig config, long epochMs) {
        Locale loc = FrigateI18n.getEffectiveLocale(context, config);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return getCachedFormat(loc, "yyMMdd", "dd/MM/yy").format(new Date(epochMs));
        }
        if (context != null) {
            return DateFormat.getDateFormat(context).format(new Date(epochMs));
        }
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, loc)
                .format(new Date(epochMs));
    }

    public static String formatTimeShort(Context context, FrigateConfig config, long epochMs) {
        boolean use24 = is24Hour(context, config);
        Locale loc = FrigateI18n.getEffectiveLocale(context, config);
        return getCachedFormat(loc, use24 ? "Hm" : "hm", use24 ? "HH:mm" : "h:mm a")
                .format(new Date(epochMs));
    }

    public static String formatTimeWithSeconds(Context context, long epochMs) {
        return formatTimeWithSeconds(context, null, epochMs);
    }

    public static String formatTimeWithSeconds(Context context, FrigateConfig config, long epochMs) {
        boolean use24 = is24Hour(context, config);
        Locale loc = FrigateI18n.getEffectiveLocale(context, config);
        return getCachedFormat(loc, use24 ? "Hms" : "hms", use24 ? "HH:mm:ss" : "h:mm:ss a")
                .format(new Date(epochMs));
    }

    public static String formatShortMonthDayTime(Context context, FrigateConfig config, long epochMs) {
        boolean use24 = is24Hour(context, config);
        Locale loc = FrigateI18n.getEffectiveLocale(context, config);
        return getCachedFormat(loc, use24 ? "MMddHm" : "MMddhm", use24 ? "dd/MM HH:mm" : "MM/dd h:mm a")
                .format(new Date(epochMs));
    }

    public static String formatDayMonthTime(Context context, FrigateConfig config, long epochMs) {
        boolean use24 = is24Hour(context, config);
        Locale loc = FrigateI18n.getEffectiveLocale(context, config);
        return getCachedFormat(loc, use24 ? "MMMdHm" : "MMMdhm", use24 ? "d MMM HH:mm" : "MMM d h:mm a")
                .format(new Date(epochMs));
    }

    public static String formatWeekdayDateTime(Context context, FrigateConfig config, long epochMs) {
        boolean use24 = is24Hour(context, config);
        Locale loc = FrigateI18n.getEffectiveLocale(context, config);
        return getCachedFormat(loc, use24 ? "EEEMMMdHms" : "EEEMMMdhms",
                use24 ? "EEE d MMM HH:mm:ss" : "EEE, MMM d h:mm:ss a")
                .format(new Date(epochMs));
    }

    public static String formatFullWeekdayDayMonth(Context context, FrigateConfig config, long epochMs) {
        Locale loc = FrigateI18n.getEffectiveLocale(context, config);
        return getCachedFormat(loc, "EEEEMMMd", "EEEE d MMM").format(new Date(epochMs));
    }

    public static String formatMonthYear(Context context, FrigateConfig config, long epochMs) {
        Locale loc = FrigateI18n.getEffectiveLocale(context, config);
        return getCachedFormat(loc, "yyyyMMMM", "MMMM yyyy").format(new Date(epochMs));
    }
}
