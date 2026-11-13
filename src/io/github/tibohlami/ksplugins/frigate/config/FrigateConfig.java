package io.github.tibohlami.ksplugins.frigate.config;

import me.jxl.kiosk.plugins.PluginHost;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration manager for the Frigate Kiosk Satellite plugin.
 * Keeps in-memory settings synchronized with host configure() updates.
 */
public class FrigateConfig {

    public static final String KEY_FRIGATE_URL = "frigateUrl";
    public static final String KEY_USERNAME = "username";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_AUTH_TOKEN = "authToken";
    public static final String KEY_DEFAULT_CAMERA = "defaultCamera";
    public static final String KEY_MAX_EVENTS = "maxEvents";
    public static final String KEY_UI_SCALE = "uiScaleFactor";
    public static final String KEY_LEGACY_UI_SCALE = "uiScale";
    public static final String KEY_SHOW_STREAM_MODE_PILL = "showStreamModePill";
    public static final String KEY_SHOW_OBJECT_BOXES_BY_DEFAULT = "showObjectBoxesByDefault";
    public static final String KEY_BBOX_TIME_OFFSET_SEC = "bboxTimeOffsetSec";
    public static final String KEY_ALLOW_INSECURE_SSL = "allowInsecureSsl";
    public static final String KEY_MUTE_AUDIO_BY_DEFAULT = "muteAudioByDefault";
    public static final String KEY_DEBUG_LOGGING = "debugLogging";
    public static final String KEY_LANGUAGE = "language";

    public static final String DEFAULT_FRIGATE_URL = "http://frigate.local:5000";
    public static final int DEFAULT_MAX_EVENTS = 25;
    public static final float DEFAULT_UI_SCALE = 1.35f;
    public static final boolean DEFAULT_SHOW_STREAM_MODE_PILL = true;
    public static final boolean DEFAULT_SHOW_OBJECT_BOXES_BY_DEFAULT = false;
    public static final double DEFAULT_BBOX_TIME_OFFSET_SEC = 1.0;
    public static final boolean DEFAULT_ALLOW_INSECURE_SSL = false;
    public static final boolean DEFAULT_MUTE_AUDIO_BY_DEFAULT = false;
    public static final boolean DEFAULT_DEBUG_LOGGING = false;
    public static final String DEFAULT_LANGUAGE = "auto";

    private final PluginHost host;
    private final Map<String, Object> settings = new LinkedHashMap<>();
    private volatile String sessionToken = "";

    public FrigateConfig(PluginHost host) {
        this.host = host;
    }

    public PluginHost getHost() {
        return host;
    }

    public synchronized void updateFrom(Map<String, Object> newSettings) {
        if (newSettings != null) {
            String prevUrl = getFrigateUrl();
            String prevUser = getUsername();
            String prevPass = getPassword();
            settings.putAll(newSettings);
            if (!prevUrl.equals(getFrigateUrl())
                    || !prevUser.equals(getUsername())
                    || !prevPass.equals(getPassword())) {
                sessionToken = "";
            }
        }
    }

    public synchronized String getFrigateUrl() {
        Object val = settings.get(KEY_FRIGATE_URL);
        if (val == null || String.valueOf(val).trim().isEmpty()) {
            return DEFAULT_FRIGATE_URL;
        }
        String url = String.valueOf(val).trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public synchronized boolean isDefaultOrEmptyFrigateUrl() {
        Object val = settings.get(KEY_FRIGATE_URL);
        if (val == null) return true;
        String url = String.valueOf(val).trim();
        if (url.isEmpty()) return true;
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return DEFAULT_FRIGATE_URL.equalsIgnoreCase(url);
    }

    public synchronized String getUsername() {
        Object val = settings.get(KEY_USERNAME);
        return val != null ? String.valueOf(val).trim() : "";
    }

    public synchronized String getPassword() {
        Object val = settings.get(KEY_PASSWORD);
        return val != null ? String.valueOf(val) : "";
    }

    public synchronized boolean hasCredentials() {
        return !getUsername().isEmpty() && !getPassword().isEmpty();
    }

    public synchronized boolean hasManualAuthToken() {
        Object val = settings.get(KEY_AUTH_TOKEN);
        return val != null && !String.valueOf(val).trim().isEmpty();
    }

    public void setSessionToken(String token) {
        this.sessionToken = token != null ? token.trim() : "";
    }

    public void clearSessionToken() {
        this.sessionToken = "";
    }

    public synchronized String getAuthToken() {
        Object val = settings.get(KEY_AUTH_TOKEN);
        String manual = val != null ? String.valueOf(val).trim() : "";
        if (!manual.isEmpty()) {
            return manual;
        }
        return sessionToken != null ? sessionToken : "";
    }

    public synchronized String getDefaultCamera() {
        Object val = settings.get(KEY_DEFAULT_CAMERA);
        return val != null ? String.valueOf(val).trim() : "";
    }

    public synchronized int getMaxEvents() {
        Object val = settings.get(KEY_MAX_EVENTS);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        if (val != null) {
            try {
                return Integer.parseInt(String.valueOf(val).trim());
            } catch (Exception ignored) {}
        }
        return DEFAULT_MAX_EVENTS;
    }

    public synchronized float getUiScale() {
        Object val = settings.get(KEY_UI_SCALE);
        if (val == null) {
            val = settings.get(KEY_LEGACY_UI_SCALE);
        }
        if (val instanceof Number) {
            float s = ((Number) val).floatValue();
            if (s >= 0.5f && s <= 3.0f) {
                return s;
            }
            return DEFAULT_UI_SCALE;
        }
        if (val != null) {
            try {
                float s = Float.parseFloat(String.valueOf(val).trim());
                if (s >= 0.5f && s <= 3.0f) {
                    return s;
                }
            } catch (Exception ignored) {}
        }
        return DEFAULT_UI_SCALE;
    }

    public synchronized boolean isShowStreamModePill() {
        return getBooleanSetting(KEY_SHOW_STREAM_MODE_PILL, DEFAULT_SHOW_STREAM_MODE_PILL);
    }

    public synchronized boolean isShowObjectBoxesByDefault() {
        return getBooleanSetting(KEY_SHOW_OBJECT_BOXES_BY_DEFAULT, DEFAULT_SHOW_OBJECT_BOXES_BY_DEFAULT);
    }

    public synchronized boolean isMuteAudioByDefault() {
        return getBooleanSetting(KEY_MUTE_AUDIO_BY_DEFAULT, DEFAULT_MUTE_AUDIO_BY_DEFAULT);
    }

    public synchronized boolean isAllowInsecureSsl() {
        return getBooleanSetting(KEY_ALLOW_INSECURE_SSL, DEFAULT_ALLOW_INSECURE_SSL);
    }

    public synchronized double getBboxTimeOffsetSec() {
        Object val = settings.get(KEY_BBOX_TIME_OFFSET_SEC);
        double offset = DEFAULT_BBOX_TIME_OFFSET_SEC;
        if (val instanceof Number) {
            offset = ((Number) val).doubleValue();
        } else if (val != null) {
            try {
                offset = Double.parseDouble(String.valueOf(val).trim());
            } catch (Exception ignored) {}
        }
        return Math.max(-5.0, Math.min(5.0, offset));
    }

    public synchronized String getLanguage() {
        Object val = settings.get(KEY_LANGUAGE);
        if (val == null) return DEFAULT_LANGUAGE;
        String s = String.valueOf(val).trim().toLowerCase(java.util.Locale.ROOT);
        if ("fr".equals(s) || "français".equals(s) || "francais".equals(s)) {
            return "fr";
        }
        if ("en".equals(s) || "english".equals(s)) {
            return "en";
        }
        if ("auto".equals(s) || "default".equals(s)) {
            return DEFAULT_LANGUAGE;
        }
        return DEFAULT_LANGUAGE;
    }

    public synchronized boolean isDebugLogging() {
        return getBooleanSetting(KEY_DEBUG_LOGGING, DEFAULT_DEBUG_LOGGING);
    }

    private boolean getBooleanSetting(String key, boolean defaultValue) {
        Object val = settings.get(key);
        if (val instanceof Boolean) {
            return (Boolean) val;
        }
        if (val != null) {
            String s = String.valueOf(val).trim().toLowerCase();
            if ("true".equals(s)) return true;
            if ("false".equals(s)) return false;
        }
        return defaultValue;
    }
}
