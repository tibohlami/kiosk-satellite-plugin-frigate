package io.github.tibohlami.ksplugins.frigate.config;

import me.jxl.kiosk.plugins.PluginHost;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    public static final String KEY_SHOW_LATEST_EVENTS = "showLatestEvents";
    public static final String KEY_LATEST_EVENTS_SEVERITY = "latestEventsSeverity";
    public static final String KEY_PREVIEW_CLIPS_ON_SELECTION = "previewClipsOnSelection";
    public static final String KEY_HIGHLIGHT_ONGOING_EVENTS = "highlightOngoingEvents";
    public static final String KEY_SHOW_OBJECT_BOXES_BY_DEFAULT = "showObjectBoxesByDefault";
    public static final String KEY_BBOX_TIME_OFFSET_SEC = "bboxTimeOffsetSec";
    public static final String KEY_ALLOW_INSECURE_SSL = "allowInsecureSsl";
    public static final String KEY_MUTE_AUDIO_BY_DEFAULT = "muteAudioByDefault";
    public static final String KEY_DEBUG_LOGGING = "debugLogging";
    public static final String KEY_LANGUAGE = "language";
    public static final String KEY_PIP_DISPLAY_MODE = "pipDisplayMode";
    public static final String KEY_PIP_CAMERAS = "pipCameras";
    public static final String KEY_PIP_SEVERITY = "pipSeverity";
    public static final String KEY_PIP_OBJECTS = "pipObjects";
    public static final String KEY_PIP_POSITION = "pipPosition";
    public static final String KEY_PIP_SIZE = "pipSizePct";
    public static final String KEY_PIP_AUTO_HIDE_SEC = "pipAutoHideSec";
    public static final String KEY_PIP_ENABLE_AUDIO = "pipEnableAudio";

    public static final String DEFAULT_FRIGATE_URL = "http://frigate.local:5000";
    public static final int DEFAULT_MAX_EVENTS = 25;
    public static final float DEFAULT_UI_SCALE = 1.0f;
    public static final boolean DEFAULT_SHOW_STREAM_MODE_PILL = true;
    public static final boolean DEFAULT_SHOW_LATEST_EVENTS = true;
    public static final boolean DEFAULT_PREVIEW_CLIPS_ON_SELECTION = true;
    public static final boolean DEFAULT_HIGHLIGHT_ONGOING_EVENTS = true;
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

    public synchronized boolean isShowLatestEvents() {
        return getBooleanSetting(KEY_SHOW_LATEST_EVENTS, DEFAULT_SHOW_LATEST_EVENTS);
    }

    /** Severity shown in the main screen's Latest Events strip: {@code "alerts"} or {@code "all"}. */
    public synchronized String getLatestEventsSeverity() {
        String raw = getStringSetting(KEY_LATEST_EVENTS_SEVERITY, "Alerts & detections").toLowerCase(Locale.ROOT);
        return ("alerts".equals(raw) || "alerts only".equals(raw)) ? "alerts" : "all";
    }

    public synchronized boolean isPreviewClipsOnSelection() {
        return getBooleanSetting(KEY_PREVIEW_CLIPS_ON_SELECTION, DEFAULT_PREVIEW_CLIPS_ON_SELECTION);
    }

    public synchronized boolean isHighlightOngoingEvents() {
        return getBooleanSetting(KEY_HIGHLIGHT_ONGOING_EVENTS, DEFAULT_HIGHLIGHT_ONGOING_EVENTS);
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

    public synchronized String getPipDisplayMode() {
        String raw = getStringSetting(KEY_PIP_DISPLAY_MODE, "Disabled").toLowerCase(Locale.ROOT);
        if ("dashboard".equals(raw) || "dashboard only".equals(raw)) return "dashboard";
        if ("both".equals(raw) || raw.contains("screensaver")) return "both";
        return "none";
    }

    public synchronized Set<String> getPipCameras() {
        return extractListFieldSet(settings.get(KEY_PIP_CAMERAS), "camera", false);
    }

    public synchronized String getPipSeverity() {
        String raw = getStringSetting(KEY_PIP_SEVERITY, "Alerts & detections").toLowerCase(Locale.ROOT);
        return ("alerts".equals(raw) || "alerts only".equals(raw)) ? "alerts" : "all";
    }

    public synchronized Set<String> getPipObjects() {
        return extractListFieldSet(settings.get(KEY_PIP_OBJECTS), "object", true);
    }

    public synchronized String getPipPosition() {
        String raw = getStringSetting(KEY_PIP_POSITION, "Bottom right").toLowerCase(Locale.ROOT);
        if ("top-left".equals(raw) || "top left".equals(raw)) return "top-left";
        if ("top-right".equals(raw) || "top right".equals(raw)) return "top-right";
        if ("bottom-left".equals(raw) || "bottom left".equals(raw)) return "bottom-left";
        return "bottom-right";
    }

    public synchronized String getPipSize() {
        String raw = getStringSetting(KEY_PIP_SIZE, "Medium (35%)").toLowerCase(Locale.ROOT);
        if (raw.startsWith("small")) return "small";
        if (raw.startsWith("large")) return "large";
        return "medium";
    }

    public synchronized int getPipAutoHideSec() {
        Object val = settings.get(KEY_PIP_AUTO_HIDE_SEC);
        int sec = 30;
        if (val instanceof Number) {
            sec = ((Number) val).intValue();
        } else if (val != null) {
            try {
                sec = (int) Math.round(Double.parseDouble(String.valueOf(val).trim()));
            } catch (Exception ignored) {}
        }
        return Math.max(0, Math.min(600, sec));
    }

    public synchronized boolean isPipEnableAudio() {
        return getBooleanSetting(KEY_PIP_ENABLE_AUDIO, false);
    }

    private String getStringSetting(String key, String defaultValue) {
        Object val = settings.get(key);
        if (val == null) return defaultValue;
        String s = String.valueOf(val).trim();
        return s.isEmpty() ? defaultValue : s;
    }

    private static Set<String> extractListFieldSet(Object raw, String fieldKey, boolean lowercase) {
        if (!(raw instanceof List<?>)) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (Object item : (List<?>) raw) {
            String val = null;
            if (item instanceof Map<?, ?>) {
                Object fieldVal = ((Map<?, ?>) item).get(fieldKey);
                if (fieldVal != null) val = String.valueOf(fieldVal).trim();
            } else if (item != null) {
                val = String.valueOf(item).trim();
            }
            if (val != null && !val.isEmpty()) {
                out.add(lowercase ? val.toLowerCase(Locale.ROOT) : val);
            }
        }
        return out.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(out);
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
