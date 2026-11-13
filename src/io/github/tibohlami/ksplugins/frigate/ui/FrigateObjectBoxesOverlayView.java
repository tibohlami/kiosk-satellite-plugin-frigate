// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import io.github.tibohlami.ksplugins.frigate.model.FrigateEvent;
import io.github.tibohlami.ksplugins.frigate.model.TimelineBoxEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Transparent overlay rendering interpolated Frigate object bounding boxes over recorded playback.
 */
public class FrigateObjectBoxesOverlayView extends View {

    private static final float MIN_BOX_DIMENSION = 0.01f;
    private static final double KEYFRAME_DEDUP_SEC = 0.05;

    public static final class BoxKeyframe {
        final double timestamp;
        final float x;
        final float y;
        final float w;
        final float h;

        BoxKeyframe(double timestamp, float x, float y, float w, float h) {
            this.timestamp = timestamp;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private static final class RenderBox {
        final float x;
        final float y;
        final float w;
        final float h;
        final String labelText;
        final int color;

        RenderBox(float x, float y, float w, float h, String labelText, int color) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.labelText = labelText;
            this.color = color;
        }
    }

    private final float density;
    private float uiScale = 1.35f;
    private FrigateConfig config;

    private final RectF videoRect = new RectF();
    private final RectF drawRect = new RectF();
    private final RectF badgeRect = new RectF();

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<RenderBox> activeBoxes = new ArrayList<>();
    private final Map<String, List<BoxKeyframe>> trajectoryCache = new HashMap<>();

    public FrigateObjectBoxesOverlayView(Context context) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);

        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        badgeBgPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    public void setUiScale(float uiScale) {
        this.uiScale = (uiScale >= 0.5f && uiScale <= 3.0f) ? uiScale : 1.35f;
        invalidate();
    }

    public void setConfig(FrigateConfig config) {
        this.config = config;
        invalidate();
    }

    /**
     * Aligns normalized [0..1] box coordinates to the aspect-fitted video rectangle inside container.
     */
    public void setVideoContentBounds(int containerW, int containerH, int videoW, int videoH, float pixelAspectRatio) {
        if (containerW <= 0 || containerH <= 0) {
            videoRect.setEmpty();
            invalidate();
            return;
        }
        if (videoW <= 0 || videoH <= 0) {
            videoRect.set(0f, 0f, containerW, containerH);
            invalidate();
            return;
        }
        float par = (pixelAspectRatio > 0f) ? pixelAspectRatio : 1.0f;
        float videoAspect = (videoW * par) / (float) videoH;
        float viewAspect = containerW / (float) containerH;
        float targetW = containerW;
        float targetH = containerH;
        if (videoAspect > viewAspect) {
            targetH = Math.max(1f, containerW / videoAspect);
        } else {
            targetW = Math.max(1f, containerH * videoAspect);
        }
        float left = (containerW - targetW) * 0.5f;
        float top = (containerH - targetH) * 0.5f;
        videoRect.set(left, top, left + targetW, top + targetH);
        invalidate();
    }

    public void invalidateEventTrajectory(String eventId) {
        if (eventId != null) {
            trajectoryCache.remove(eventId);
        }
    }

    public void clear() {
        if (!activeBoxes.isEmpty()) {
            activeBoxes.clear();
            invalidate();
        }
    }

    /**
     * Recomputes interpolated bounding boxes for all events active at {@code epochSeconds}.
     */
    public void updateForEpoch(double epochSeconds,
                               List<FrigateEvent> candidateEvents,
                               Map<String, List<TimelineBoxEntry>> timelineBoxesByEventId) {
        activeBoxes.clear();
        if (candidateEvents == null || candidateEvents.isEmpty()) {
            invalidate();
            return;
        }

        for (FrigateEvent ev : candidateEvents) {
            if (ev == null) continue;
            double start = ev.getStartTime();
            double end = Math.max(start, ev.getEndTime());
            if (start > 0.0 && (epochSeconds < start - 0.5 || epochSeconds > end + 0.5)) {
                continue;
            }
            List<TimelineBoxEntry> tlEntries =
                    (timelineBoxesByEventId != null && ev.getId() != null)
                            ? timelineBoxesByEventId.get(ev.getId())
                            : null;
            List<BoxKeyframe> track = getOrBuildTrajectory(ev, tlEntries);
            if (track.isEmpty()) continue;

            BoxKeyframe interpolated = interpolateTrajectory(track, epochSeconds);
            if (interpolated == null) continue;

            String label = formatBadgeLabel(getContext(), config, ev);
            int color = colorForLabel(ev.getLabel());
            activeBoxes.add(new RenderBox(interpolated.x, interpolated.y, interpolated.w, interpolated.h, label, color));
        }
        invalidate();
    }

    private List<BoxKeyframe> getOrBuildTrajectory(FrigateEvent ev,
                                                   List<TimelineBoxEntry> timelineEntries) {
        String key = ev.getId();
        boolean hasTimeline = timelineEntries != null && !timelineEntries.isEmpty();
        if (key != null && !key.isEmpty()) {
            List<BoxKeyframe> cached = trajectoryCache.get(key);
            if (cached != null) {
                return cached;
            }
        }

        List<BoxKeyframe> fullBoxes = new ArrayList<>();
        if (hasTimeline) {
            for (TimelineBoxEntry e : timelineEntries) {
                if (e != null && e.w >= MIN_BOX_DIMENSION && e.h >= MIN_BOX_DIMENSION) {
                    fullBoxes.add(new BoxKeyframe(e.timestamp, e.x, e.y, e.w, e.h));
                }
            }
        }
        float[] snapBox = ev.getSnapshotBox();
        if (snapBox != null && snapBox.length == 4 && snapBox[2] >= MIN_BOX_DIMENSION && snapBox[3] >= MIN_BOX_DIMENSION) {
            double snapTs = ev.getSnapshotFrameTime();
            if (snapTs <= 0.0) {
                snapTs = (ev.getStartTime() > 0.0)
                        ? (ev.getStartTime() + Math.max(ev.getStartTime(), ev.getEndTime())) * 0.5
                        : 0.0;
            }
            if (!hasTimestampNear(fullBoxes, snapTs)) {
                fullBoxes.add(new BoxKeyframe(snapTs, snapBox[0], snapBox[1], snapBox[2], snapBox[3]));
            }
        }
        Collections.sort(fullBoxes, (a, b) -> Double.compare(a.timestamp, b.timestamp));

        if (fullBoxes.isEmpty()) {
            return Collections.emptyList();
        }

        List<BoxKeyframe> combined = new ArrayList<>(fullBoxes);
        List<FrigateEvent.PathPoint> pathData = ev.getPathData();
        if (pathData != null && !pathData.isEmpty()) {
            for (FrigateEvent.PathPoint pt : pathData) {
                if (pt == null || pt.timestamp <= 0.0) continue;
                if (hasTimestampNear(combined, pt.timestamp)) continue;
                float[] wh = estimateDimensionsAt(fullBoxes, pt.timestamp);
                float left = clamp01(pt.x - (wh[0] * 0.5f));
                float top = clamp01(pt.y - wh[1]);
                float w = Math.min(wh[0], Math.max(MIN_BOX_DIMENSION, 1.0f - left));
                float h = Math.min(wh[1], Math.max(MIN_BOX_DIMENSION, 1.0f - top));
                combined.add(new BoxKeyframe(pt.timestamp, left, top, w, h));
            }
            Collections.sort(combined, (a, b) -> Double.compare(a.timestamp, b.timestamp));
        }

        List<BoxKeyframe> immutable = Collections.unmodifiableList(combined);
        if (key != null && !key.isEmpty()) {
            trajectoryCache.put(key, immutable);
        }
        return immutable;
    }

    private static boolean hasTimestampNear(List<BoxKeyframe> list, double ts) {
        for (BoxKeyframe kf : list) {
            if (Math.abs(kf.timestamp - ts) < KEYFRAME_DEDUP_SEC) {
                return true;
            }
        }
        return false;
    }

    private static float[] estimateDimensionsAt(List<BoxKeyframe> fullBoxes, double ts) {
        if (fullBoxes.size() == 1 || ts <= fullBoxes.get(0).timestamp) {
            BoxKeyframe first = fullBoxes.get(0);
            return new float[]{first.w, first.h};
        }
        int lastIdx = fullBoxes.size() - 1;
        if (ts >= fullBoxes.get(lastIdx).timestamp) {
            BoxKeyframe last = fullBoxes.get(lastIdx);
            return new float[]{last.w, last.h};
        }
        for (int i = 0; i < lastIdx; i++) {
            BoxKeyframe a = fullBoxes.get(i);
            BoxKeyframe b = fullBoxes.get(i + 1);
            if (ts >= a.timestamp && ts <= b.timestamp) {
                double span = b.timestamp - a.timestamp;
                float alpha = (span > 1e-6) ? (float) ((ts - a.timestamp) / span) : 0f;
                return new float[]{
                        lerp(a.w, b.w, alpha),
                        lerp(a.h, b.h, alpha)
                };
            }
        }
        BoxKeyframe fallback = fullBoxes.get(0);
        return new float[]{fallback.w, fallback.h};
    }

    private static BoxKeyframe interpolateTrajectory(List<BoxKeyframe> track, double ts) {
        if (track.isEmpty()) return null;
        if (track.size() == 1 || ts <= track.get(0).timestamp) {
            return track.get(0);
        }
        int lastIdx = track.size() - 1;
        if (ts >= track.get(lastIdx).timestamp) {
            return track.get(lastIdx);
        }
        for (int i = 0; i < lastIdx; i++) {
            BoxKeyframe a = track.get(i);
            BoxKeyframe b = track.get(i + 1);
            if (ts >= a.timestamp && ts <= b.timestamp) {
                double span = b.timestamp - a.timestamp;
                float alpha = (span > 1e-6) ? (float) ((ts - a.timestamp) / span) : 0f;
                return new BoxKeyframe(
                        ts,
                        clamp01(lerp(a.x, b.x, alpha)),
                        clamp01(lerp(a.y, b.y, alpha)),
                        Math.max(MIN_BOX_DIMENSION, lerp(a.w, b.w, alpha)),
                        Math.max(MIN_BOX_DIMENSION, lerp(a.h, b.h, alpha))
                );
            }
        }
        return track.get(0);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (activeBoxes.isEmpty() || videoRect.width() <= 0f || videoRect.height() <= 0f) {
            return;
        }

        float strokePx = Math.max(2f, 2.2f * density * uiScale);
        float radiusPx = 5f * density * uiScale;
        float textSizePx = 11.5f * density * uiScale;
        float padH = 6f * density * uiScale;
        float padV = 3f * density * uiScale;

        strokePaint.setStrokeWidth(strokePx);
        textPaint.setTextSize(textSizePx);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float badgeH = (fm.descent - fm.ascent) + (padV * 2f);

        for (RenderBox box : activeBoxes) {
            float left = videoRect.left + (box.x * videoRect.width());
            float top = videoRect.top + (box.y * videoRect.height());
            float right = Math.min(videoRect.right, left + (box.w * videoRect.width()));
            float bottom = Math.min(videoRect.bottom, top + (box.h * videoRect.height()));
            if (right <= left + 2f || bottom <= top + 2f) continue;

            drawRect.set(left, top, right, bottom);

            fillPaint.setColor(Color.argb(28, Color.red(box.color), Color.green(box.color), Color.blue(box.color)));
            canvas.drawRoundRect(drawRect, radiusPx, radiusPx, fillPaint);

            strokePaint.setColor(box.color);
            canvas.drawRoundRect(drawRect, radiusPx, radiusPx, strokePaint);

            if (box.labelText != null && !box.labelText.isEmpty()) {
                float textW = textPaint.measureText(box.labelText);
                float badgeW = textW + (padH * 2f);
                float bLeft = Math.max(videoRect.left, Math.min(left, videoRect.right - badgeW));
                float bTop = (top - badgeH >= videoRect.top) ? (top - badgeH) : top;
                badgeRect.set(bLeft, bTop, bLeft + badgeW, bTop + badgeH);

                badgeBgPaint.setColor(Color.argb(225, Color.red(box.color), Color.green(box.color), Color.blue(box.color)));
                canvas.drawRoundRect(badgeRect, radiusPx * 0.7f, radiusPx * 0.7f, badgeBgPaint);

                // Ensure strong contrast for dark text on bright colors vs white text on dark colors
                textPaint.setColor(isBrightColor(box.color) ? Color.parseColor("#0F172A") : Color.WHITE);
                canvas.drawText(box.labelText, bLeft + padH, bTop + padV - fm.ascent, textPaint);
            }
        }
    }

    private static boolean isBrightColor(int color) {
        double lum = (0.299 * Color.red(color)) + (0.587 * Color.green(color)) + (0.114 * Color.blue(color));
        return lum > 155.0;
    }

    private static float lerp(float a, float b, float t) {
        return a + ((b - a) * t);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    public static int colorForLabel(String rawLabel) {
        if (rawLabel == null) return Color.parseColor("#38BDF8");
        switch (rawLabel.trim().toLowerCase(Locale.ROOT)) {
            case "person":
                return Color.parseColor("#22C55E");
            case "car":
            case "vehicle":
            case "truck":
            case "motorcycle":
            case "bus":
                return Color.parseColor("#38BDF8");
            case "dog":
            case "cat":
            case "bird":
            case "animal":
                return Color.parseColor("#F59E0B");
            case "package":
                return Color.parseColor("#A78BFA");
            case "bicycle":
                return Color.parseColor("#2DD4BF");
            default:
                return Color.parseColor("#F472B6");
        }
    }

    private static String formatBadgeLabel(Context context, FrigateConfig config, FrigateEvent ev) {
        String raw = ev.getLabel();
        if (raw == null || raw.trim().isEmpty()) return "";
        String name = FrigateI18n.translateObjectLabel(context, config, raw);
        String sub = ev.getSubLabel();
        if (sub != null && !sub.trim().isEmpty() && !"null".equalsIgnoreCase(sub.trim())) {
            name = name + " (" + sub.trim() + ")";
        }
        int pct = (int) Math.round(ev.getTopScore() * 100.0);
        return (pct > 0) ? (name + " " + pct + "%") : name;
    }
}
