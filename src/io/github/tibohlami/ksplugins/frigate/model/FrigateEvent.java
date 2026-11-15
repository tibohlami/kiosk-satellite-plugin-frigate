package io.github.tibohlami.ksplugins.frigate.model;

import android.content.Context;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import io.github.tibohlami.ksplugins.frigate.ui.FrigateDateTimeUtils;
import io.github.tibohlami.ksplugins.frigate.ui.FrigateI18n;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Model representing a Frigate detection event or review item.
 * Supports both Frigate /api/events and /api/review formats.
 */
public class FrigateEvent {

    /**
     * Normalized [0..1] object trajectory point from Frigate {@code data.path_data}
     * representing bottom-center coordinates {@code (x, y)} at {@code timestamp}.
     */
    public static final class PathPoint {
        public final double timestamp;
        public final float x;
        public final float y;

        public PathPoint(double timestamp, float x, float y) {
            this.timestamp = timestamp;
            this.x = x;
            this.y = y;
        }
    }

    private final String id;
    private final String camera;
    private final String label;
    private final String subLabel;
    private final double topScore;
    private final double startTime;
    private final double endTime;
    private final boolean hasClip;
    private final boolean hasSnapshot;
    private final List<String> zones;
    private final String severity; // "alert", "detection", or null
    private final float[] snapshotBox; // [x, y, w, h] normalized [0..1], or null
    private final double snapshotFrameTime;
    private final List<PathPoint> pathData;
    private final String thumbnailUrlOverride;
    private final boolean reviewFallback;
    private final List<String> objects;
    private final List<String> detectionIds;
    private final String reviewId;

    public FrigateEvent(String id, String camera, String label, String subLabel,
                        double topScore, double startTime, double endTime,
                        boolean hasClip, boolean hasSnapshot, List<String> zones,
                        String severity) {
        this(id, camera, label, subLabel, topScore, startTime, endTime,
                hasClip, hasSnapshot, zones, severity, null, 0.0, null, null, false, null, null, null);
    }

    public FrigateEvent(String id, String camera, String label, String subLabel,
                        double topScore, double startTime, double endTime,
                        boolean hasClip, boolean hasSnapshot, List<String> zones,
                        String severity, float[] snapshotBox, double snapshotFrameTime,
                        List<PathPoint> pathData) {
        this(id, camera, label, subLabel, topScore, startTime, endTime,
                hasClip, hasSnapshot, zones, severity, snapshotBox, snapshotFrameTime, pathData,
                null, false, null, null, null);
    }

    public FrigateEvent(String id, String camera, String label, String subLabel,
                        double topScore, double startTime, double endTime,
                        boolean hasClip, boolean hasSnapshot, List<String> zones,
                        String severity, float[] snapshotBox, double snapshotFrameTime,
                        List<PathPoint> pathData, String thumbnailUrlOverride, boolean reviewFallback) {
        this(id, camera, label, subLabel, topScore, startTime, endTime,
                hasClip, hasSnapshot, zones, severity, snapshotBox, snapshotFrameTime, pathData,
                thumbnailUrlOverride, reviewFallback, null, null, null);
    }

    public FrigateEvent(String id, String camera, String label, String subLabel,
                        double topScore, double startTime, double endTime,
                        boolean hasClip, boolean hasSnapshot, List<String> zones,
                        String severity, float[] snapshotBox, double snapshotFrameTime,
                        List<PathPoint> pathData, String thumbnailUrlOverride, boolean reviewFallback,
                        List<String> objects, List<String> detectionIds, String reviewId) {
        this.id = id;
        this.camera = camera;
        this.label = label;
        this.subLabel = subLabel;
        this.topScore = topScore;
        this.startTime = startTime;
        this.endTime = endTime;
        this.hasClip = hasClip;
        this.hasSnapshot = hasSnapshot;
        this.zones = zones != null ? zones : new ArrayList<>();
        this.severity = severity;
        this.snapshotBox = (snapshotBox != null && snapshotBox.length == 4) ? snapshotBox : null;
        this.snapshotFrameTime = snapshotFrameTime;
        this.pathData = pathData != null ? Collections.unmodifiableList(pathData) : Collections.emptyList();
        this.thumbnailUrlOverride = (thumbnailUrlOverride != null && !thumbnailUrlOverride.trim().isEmpty())
                ? thumbnailUrlOverride.trim() : null;
        this.reviewFallback = reviewFallback;
        this.objects = normalizeObjects(objects, label);
        this.detectionIds = (detectionIds != null && !detectionIds.isEmpty())
                ? Collections.unmodifiableList(new ArrayList<>(detectionIds))
                : Collections.emptyList();
        this.reviewId = (reviewId != null && !reviewId.trim().isEmpty()) ? reviewId.trim() : null;
    }

    private static List<String> normalizeObjects(List<String> rawObjects, String primaryLabel) {
        Set<String> unique = new LinkedHashSet<>();
        if (rawObjects != null) {
            for (String obj : rawObjects) {
                if (obj != null) {
                    String trimmed = obj.trim();
                    if (!trimmed.isEmpty()) {
                        unique.add(trimmed);
                    }
                }
            }
        }
        if (unique.isEmpty() && primaryLabel != null && !primaryLabel.trim().isEmpty()) {
            unique.add(primaryLabel.trim());
        }
        return unique.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(unique));
    }

    /**
     * Creates an aggregated FrigateEvent backed by a /api/review segment.
     */
    public static FrigateEvent fromReviewSegment(ReviewSegment rev, String thumbnailUrlOverride,
                                                  double topScore, String subLabel, boolean hasClip) {
        String cam = (rev.camera != null && !rev.camera.trim().isEmpty()) ? rev.camera.trim() : "unknown";
        String revSev = (rev.severity != null && !rev.severity.trim().isEmpty()) ? rev.severity.trim() : "detection";
        List<String> objs = normalizeObjects(rev.objects, null);
        String primaryLabel = !objs.isEmpty() ? objs.get(0) : "motion";
        if (objs.isEmpty()) {
            objs = Collections.singletonList(primaryLabel);
        }
        String synthId = (rev.id != null && !rev.id.trim().isEmpty())
                ? ("rev:" + rev.id.trim())
                : ("rev:" + cam + ":" + Math.round(rev.startTime * 1000.0));
        boolean hasThumb = thumbnailUrlOverride != null && !thumbnailUrlOverride.trim().isEmpty();
        return new FrigateEvent(synthId, cam, primaryLabel, subLabel, topScore,
                rev.startTime, rev.endTime, hasClip, hasThumb, Collections.emptyList(), revSev,
                null, 0.0, null, thumbnailUrlOverride, true, objs, rev.detectionIds, rev.id);
    }

    /**
     * Creates a synthetic FrigateEvent backed by a /api/review segment when the corresponding
     * /api/events database record is unavailable or expired.
     */
    public static FrigateEvent fromReviewFallback(String id, String camera, String label,
                                                  double startTime, double endTime,
                                                  String severity, String thumbnailUrlOverride,
                                                  boolean hasClip) {
        String safeLabel = (label != null && !label.trim().isEmpty()) ? label.trim() : "motion";
        boolean hasThumb = thumbnailUrlOverride != null && !thumbnailUrlOverride.trim().isEmpty();
        return new FrigateEvent(id, camera, safeLabel, null, 0.0, startTime, endTime,
                hasClip, hasThumb, Collections.emptyList(), severity, null, 0.0, null,
                thumbnailUrlOverride, true, Collections.singletonList(safeLabel), null, null);
    }

    public static FrigateEvent fromJson(JSONObject json) {
        String id = json.optString("id", "");
        String camera = json.optString("camera", "unknown");

        // Label handling (supports /api/events "label" or /api/review "data.objects")
        String label = json.optString("label", "");
        if (label.isEmpty() && json.has("data")) {
            JSONObject data = json.optJSONObject("data");
            if (data != null && data.has("objects")) {
                JSONArray objects = data.optJSONArray("objects");
                if (objects != null && objects.length() > 0) {
                    label = objects.optString(0, "motion");
                }
            }
        }
        if (label.isEmpty()) {
            label = "motion";
        }

        String subLabel = json.isNull("sub_label") ? null : json.optString("sub_label", null);
        double topScore = json.optDouble("top_score", json.optDouble("score", 0.0));
        double startTime = json.optDouble("start_time", 0.0);
        double endTime = json.isNull("end_time")
                ? (System.currentTimeMillis() / 1000.0)
                : json.optDouble("end_time", startTime);
        boolean hasClip = json.optBoolean("has_clip", true);
        boolean hasSnapshot = json.optBoolean("has_snapshot", true);

        // Zones: collect from zones, current_zones, and entered_zones (both root and inside data)
        List<String> zones = new ArrayList<>();
        collectZones(json.optJSONArray("zones"), zones);
        collectZones(json.optJSONArray("current_zones"), zones);
        collectZones(json.optJSONArray("entered_zones"), zones);
        JSONObject dataObj = json.optJSONObject("data");
        if (dataObj != null) {
            collectZones(dataObj.optJSONArray("zones"), zones);
            collectZones(dataObj.optJSONArray("current_zones"), zones);
            collectZones(dataObj.optJSONArray("entered_zones"), zones);
        }

        String severity = json.optString("severity", null);
        if (severity == null && dataObj != null) {
            severity = dataObj.optString("severity", null);
        }
        if (severity == null || severity.trim().isEmpty()) {
            severity = (!zones.isEmpty()) ? "alert" : "detection";
        }

        float[] snapshotBox = null;
        double snapshotFrameTime = 0.0;
        List<PathPoint> pathData = null;
        if (dataObj != null) {
            JSONArray boxArr = dataObj.optJSONArray("box");
            if (boxArr != null && boxArr.length() == 4) {
                float bx = (float) boxArr.optDouble(0, -1.0);
                float by = (float) boxArr.optDouble(1, -1.0);
                float bw = (float) boxArr.optDouble(2, -1.0);
                float bh = (float) boxArr.optDouble(3, -1.0);
                if (bw > 0f && bh > 0f && bx >= 0f && by >= 0f) {
                    snapshotBox = new float[]{bx, by, bw, bh};
                }
            }
            snapshotFrameTime = dataObj.optDouble("snapshot_frame_time", 0.0);
            JSONArray pathArr = dataObj.optJSONArray("path_data");
            if (pathArr != null && pathArr.length() > 0) {
                pathData = new ArrayList<>(pathArr.length());
                for (int i = 0; i < pathArr.length(); i++) {
                    JSONArray entry = pathArr.optJSONArray(i);
                    if (entry == null || entry.length() < 2) continue;
                    JSONArray coords = entry.optJSONArray(0);
                    double ts = entry.optDouble(1, 0.0);
                    if (coords != null && coords.length() >= 2 && ts > 0.0) {
                        float px = (float) coords.optDouble(0, -1.0);
                        float py = (float) coords.optDouble(1, -1.0);
                        if (px >= 0f && px <= 1.2f && py >= 0f && py <= 1.2f) {
                            pathData.add(new PathPoint(ts, px, py));
                        }
                    }
                }
            }
        }

        return new FrigateEvent(id, camera, label, subLabel, topScore,
                startTime, endTime, hasClip, hasSnapshot, zones, severity,
                snapshotBox, snapshotFrameTime, pathData);
    }

    public String getId() { return id; }
    public String getCamera() { return camera; }
    public String getLabel() { return label; }
    public String getSubLabel() { return subLabel; }
    public double getTopScore() { return topScore; }
    public double getStartTime() { return startTime; }
    public double getEndTime() { return endTime; }
    public boolean hasClip() { return hasClip; }
    public boolean hasSnapshot() { return hasSnapshot; }
    public List<String> getZones() { return zones; }
    public String getSeverity() { return severity; }
    public float[] getSnapshotBox() { return snapshotBox; }
    public double getSnapshotFrameTime() { return snapshotFrameTime; }
    public List<PathPoint> getPathData() { return pathData; }
    public String getThumbnailUrlOverride() { return thumbnailUrlOverride; }
    public boolean isReviewFallback() { return reviewFallback; }
    public List<String> getObjects() { return objects; }
    public List<String> getDetectionIds() { return detectionIds; }
    public String getReviewId() { return reviewId; }

    public String getEffectiveThumbnailUrl(String defaultThumbnailUrl) {
        return (thumbnailUrlOverride != null && !thumbnailUrlOverride.isEmpty())
                ? thumbnailUrlOverride
                : defaultThumbnailUrl;
    }

    public FrigateEvent withSeverity(String newSeverity) {
        if (newSeverity == null || newSeverity.equals(this.severity)) {
            return this;
        }
        return new FrigateEvent(id, camera, label, subLabel, topScore, startTime, endTime,
                hasClip, hasSnapshot, zones, newSeverity, snapshotBox, snapshotFrameTime, pathData,
                thumbnailUrlOverride, reviewFallback, objects, detectionIds, reviewId);
    }

    public double getDurationSeconds() {
        return Math.max(0.0, endTime - startTime);
    }

    /**
     * Formats duration in a friendly string, e.g. "12s" or "1m 30s".
     */
    public String getFormattedDuration() {
        return getFormattedDuration(null, null);
    }

    public String getFormattedDuration(Context context, FrigateConfig config) {
        long sec = Math.round(getDurationSeconds());
        if (sec < 60) {
            return FrigateI18n.tr(context, config, "duration_seconds", sec);
        }
        long min = sec / 60;
        long rem = sec % 60;
        return FrigateI18n.tr(context, config, "duration_minutes_seconds", min, rem);
    }

    /**
     * Emoji icon matching detection label.
     */
    public String getLabelIcon() {
        switch (label.toLowerCase()) {
            case "person":
                return "👤";
            case "car":
            case "vehicle":
            case "truck":
            case "motorcycle":
                return "🚗";
            case "dog":
            case "cat":
            case "animal":
                return "🐾";
            case "package":
                return "📦";
            case "bicycle":
                return "🚲";
            default:
                return "📹";
        }
    }

    /**
     * Material Design icon identifier matching detection label.
     */
    public String getMaterialIconName() {
        return getMaterialIconForLabel(label);
    }

    /**
     * Material Design icon identifier matching any detection label string.
     */
    public static String getMaterialIconForLabel(String rawLabel) {
        if (rawLabel == null) return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.TAG;
        String norm = rawLabel.toLowerCase(Locale.ROOT);
        if (norm.endsWith("-plate")) {
            return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.LICENSE_PLATE;
        }
        switch (norm) {
            case "person":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.PERSON;
            case "car":
            case "vehicle":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.CAR;
            case "motorcycle":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.MOTORCYCLE;
            case "bus":
            case "school_bus":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.BUS;
            case "truck":
            case "garbage_truck":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.TRUCK;
            case "bicycle":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.BICYCLE;
            case "boat":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.BOAT;
            case "cat":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.CAT;
            case "dog":
            case "bark":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.DOG;
            case "bird":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.BIRD;
            case "horse":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.HORSE;
            case "cow":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.COW;
            case "rabbit":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.RABBIT;
            case "bear":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.BEAR;
            case "mouse":
            case "rat":
            case "squirrel":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.MOUSE;
            case "animal":
            case "deer":
            case "fox":
            case "goat":
            case "kangaroo":
            case "possum":
            case "raccoon":
            case "skunk":
            case "sheep":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.PETS;
            case "package":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.PACKAGE;
            case "license_plate":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.LICENSE_PLATE;
            case "baby":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.BABY;
            case "baby_stroller":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.STROLLER;
            case "umbrella":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.UMBRELLA;
            case "fire":
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.FIRE;
            default:
                return io.github.tibohlami.ksplugins.frigate.ui.MaterialIcons.TAG;
        }
    }

    /**
     * Formats date & time in human readable format respecting the tablet's locale & 24h/12h setting.
     */
    public String getFormattedDateTime() {
        return getFormattedDateTime(null);
    }

    /**
     * Formats date & time in human readable format respecting the tablet's locale & 24h/12h setting.
     */
    public String getFormattedDateTime(Context context) {
        return getFormattedDateTime(context, null);
    }

    public String getFormattedDateTime(Context context, FrigateConfig config) {
        if (startTime <= 0) return "";
        long epochMs = (long) (startTime * 1000L);
        Calendar eventCal = Calendar.getInstance();
        eventCal.setTimeInMillis(epochMs);

        Calendar nowCal = Calendar.getInstance();
        String timeStr = FrigateDateTimeUtils.formatTimeWithSeconds(context, config, epochMs);

        if (eventCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
                && eventCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)) {
            return FrigateI18n.tr(context, config, "event_today_time", timeStr);
        }

        nowCal.add(Calendar.DAY_OF_YEAR, -1);
        if (eventCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
                && eventCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)) {
            return FrigateI18n.tr(context, config, "event_yesterday_time", timeStr);
        }

        return FrigateDateTimeUtils.formatDayMonthTime(context, config, epochMs);
    }

    private static void collectZones(JSONArray array, List<String> target) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            String z = array.optString(i, null);
            if (z != null && !z.trim().isEmpty() && !target.contains(z.trim())) {
                target.add(z.trim());
            }
        }
    }
}
