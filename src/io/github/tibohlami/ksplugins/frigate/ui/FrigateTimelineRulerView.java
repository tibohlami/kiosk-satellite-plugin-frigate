// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import io.github.tibohlami.ksplugins.frigate.model.RecordingSegment;
import io.github.tibohlami.ksplugins.frigate.model.ReviewSegment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Frigate-style vertical scrollable timeline ruler with symmetrical motion activity waveform,
 * object detection highlights, centered red timestamp pill, and adaptive zoom levels.
 *
 * Newer timestamps are at the top, older timestamps scroll downward (drag UP to go back in time,
 * drag DOWN toward live).
 */
public class FrigateTimelineRulerView extends View {

    public interface OnScrubListener {
        void onScrubStart(long epochSeconds);
        void onScrubChanged(long epochSeconds);
        void onScrubEnd(long epochSeconds);
        default void onViewportChanged(long bottomEpochSeconds, long topEpochSeconds) {}
        default void onTapZone(long tapEpochSeconds, double hitToleranceSeconds) {}
    }

    private static final int[] ZOOM_WINDOWS_SECONDS = {
            86400, // 24h visible
            43200, // 12h visible
            28800, // 8h visible
            14400, // 4h visible
            7200,  // 2h visible
            3600,  // 1h visible (default)
            1800,  // 30m visible
            900,   // 15m visible
            300,   // 5m visible
            180    // 3m visible
    };

    private static final double MIN_WINDOW_SECONDS = 180.0;
    private static final double MAX_WINDOW_SECONDS = 86400.0;

    private static final int[] WAVEFORM_BUCKET_CANDIDATES = {
            2, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 300
    };

    private final float uiScale;
    private double visibleWindowSec = 3600.0; // 1h visible by default

    private long minEpochSec;
    private long maxEpochSec;
    private long cursorEpochSec;
    private boolean isLivePinned = true;
    private boolean eventBrowserMode = false;
    private double highlightedStartSec = 0.0;
    private double highlightedEndSec = 0.0;
    private boolean isDragging = false;
    private boolean isPinching = false;
    private boolean scrubNotified = false;
    private boolean hadMultiTouch = false;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private int secondaryPointerId = MotionEvent.INVALID_POINTER_ID;
    private float downX = 0f;
    private float downY = 0f;
    private float lastTouchY = 0f;
    private float pinchStartSpanY = 0f;
    private double pinchStartWindowSec = 3600.0;

    private List<RecordingSegment> segments = Collections.emptyList();
    private List<ReviewSegment> reviewSegments = Collections.emptyList();
    private final List<VisualBand> tmpVisualBands = new ArrayList<>();
    private final Map<Long, HourMotionStats> hourlyStats = new HashMap<>();
    private double globalNoiseFloor = 0.0;
    private double globalMotionSpan = 2500.0;

    private static final class VisualBand {
        final float top;
        final float bot;
        final byte sev;

        VisualBand(float top, float bot, byte sev) {
            this.top = top;
            this.bot = bot;
            this.sev = sev;
        }
    }

    private static final class HourMotionStats {
        final double noiseFloor;
        final double span;

        HourMotionStats(double noiseFloor, double span) {
            this.noiseFloor = noiseFloor;
            this.span = span;
        }
    }

    private OnScrubListener listener;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint recordedBandPaint = new Paint();
    private final Paint detectionBandPaint = new Paint();
    private final Paint alertBandPaint = new Paint();
    private final Paint detectionEdgePaint = new Paint();
    private final Paint alertEdgePaint = new Paint();
    private final Paint selectedBandPaint = new Paint();
    private final Paint selectedBorderPaint = new Paint();
    private final Paint clusterBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clusterBadgeBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clusterBadgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint();
    private final Paint dayTickPaint = new Paint();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dayLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint idleDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint motionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint futureTintPaint = new Paint();
    private final Paint redLinePaint = new Paint();
    private final Paint redPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint redPillTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pillRect = new RectF();
    private final RectF clusterRect = new RectF();
    private FrigateConfig config;
    private SimpleDateFormat timeHmFmt;
    private SimpleDateFormat timeHmsFmt;
    private SimpleDateFormat dayLabelFmt;

    public FrigateTimelineRulerView(Context context, float uiScale) {
        super(context);
        this.uiScale = Math.max(0.8f, uiScale);
        refreshDateFormats();
        long now = System.currentTimeMillis() / 1000L;
        this.maxEpochSec = now;
        this.minEpochSec = 0L;
        this.cursorEpochSec = now;

        bgPaint.setColor(Color.parseColor("#0B101A"));
        recordedBandPaint.setColor(Color.parseColor("#131C2B"));
        detectionBandPaint.setColor(Color.argb(62, 251, 146, 60));
        alertBandPaint.setColor(Color.argb(82, 244, 63, 94));
        detectionEdgePaint.setColor(Color.parseColor("#FB923C"));
        alertEdgePaint.setColor(Color.parseColor("#F43F5E"));
        selectedBandPaint.setColor(Color.argb(55, 255, 255, 255));
        selectedBorderPaint.setColor(Color.WHITE);
        selectedBorderPaint.setStyle(Paint.Style.STROKE);
        selectedBorderPaint.setStrokeWidth(dp(1.5f));

        clusterBadgeBorderPaint.setColor(Color.parseColor("#0B101A"));
        clusterBadgeBorderPaint.setStyle(Paint.Style.STROKE);
        clusterBadgeBorderPaint.setStrokeWidth(dp(1));
        clusterBadgeTextPaint.setColor(Color.WHITE);
        clusterBadgeTextPaint.setTextSize(dp(9.5f));
        clusterBadgeTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        clusterBadgeTextPaint.setTextAlign(Paint.Align.CENTER);

        tickPaint.setColor(Color.parseColor("#334155"));
        tickPaint.setStrokeWidth(dp(1));

        dayTickPaint.setColor(Color.parseColor("#475569"));
        dayTickPaint.setStrokeWidth(dp(1.2f));

        labelPaint.setColor(Color.parseColor("#CBD5E1"));
        labelPaint.setTextSize(dp(12.5f));
        labelPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));

        dayLabelPaint.setColor(Color.parseColor("#F1F5F9"));
        dayLabelPaint.setTextSize(dp(12.5f));
        dayLabelPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        idleDotPaint.setColor(Color.argb(170, 245, 158, 11));
        motionPaint.setColor(Color.parseColor("#F59E0B"));
        futureTintPaint.setColor(Color.argb(95, 6, 9, 14));

        redLinePaint.setColor(Color.parseColor("#EF4444"));
        redLinePaint.setStrokeWidth(dp(2));

        redPillPaint.setColor(Color.parseColor("#EF4444"));
        redPillTextPaint.setColor(Color.WHITE);
        redPillTextPaint.setTextSize(dp(12.5f));
        redPillTextPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        redPillTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setConfig(FrigateConfig config) {
        this.config = config;
        refreshDateFormats();
        invalidate();
    }

    private void refreshDateFormats() {
        Context ctx = getContext();
        timeHmFmt = FrigateDateTimeUtils.createTimeShortFormat(ctx, config);
        timeHmsFmt = FrigateDateTimeUtils.createTimeWithSecondsFormat(ctx, config);
        dayLabelFmt = FrigateDateTimeUtils.createShortMonthDayFormat(ctx, config);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshDateFormats();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshDateFormats();
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density * uiScale * 0.85f;
    }

    public void setOnScrubListener(OnScrubListener listener) {
        this.listener = listener;
    }

    public void setTimeRange(long minEpochSec, long maxEpochSec) {
        this.minEpochSec = Math.max(0L, minEpochSec);
        this.maxEpochSec = Math.max(this.minEpochSec + 60, maxEpochSec);
        if (cursorEpochSec < this.minEpochSec) cursorEpochSec = this.minEpochSec;
        if (cursorEpochSec > this.maxEpochSec) cursorEpochSec = this.maxEpochSec;
        invalidate();
    }

    public void setCursorEpoch(long epochSec, boolean livePinned) {
        if (isDragging) return;
        long now = System.currentTimeMillis() / 1000L;
        if (now > maxEpochSec) {
            maxEpochSec = now;
        }
        this.isLivePinned = livePinned;
        this.cursorEpochSec = Math.max(minEpochSec, Math.min(maxEpochSec, epochSec));
        notifyViewportChanged();
        invalidate();
    }

    public boolean isDragging() {
        return isDragging;
    }

    public long getCursorEpoch() {
        return cursorEpochSec;
    }

    public long getVisibleBottomEpoch() {
        return (long) Math.floor(cursorEpochSec - (visibleWindowSec / 2.0));
    }

    public long getVisibleTopEpoch() {
        return (long) Math.ceil(cursorEpochSec + (visibleWindowSec / 2.0));
    }

    private void notifyViewportChanged() {
        if (listener != null) {
            listener.onViewportChanged(getVisibleBottomEpoch(), getVisibleTopEpoch());
        }
    }

    public void setEventBrowserMode(boolean enabled) {
        this.eventBrowserMode = enabled;
        if (enabled) {
            this.isLivePinned = false;
        }
        invalidate();
    }

    public void setVisibleWindowSeconds(double windowSec) {
        this.visibleWindowSec = Math.max(MIN_WINDOW_SECONDS, Math.min(MAX_WINDOW_SECONDS, windowSec));
        notifyViewportChanged();
        invalidate();
    }

    public void setHighlightedRange(double startSec, double endSec) {
        this.highlightedStartSec = startSec;
        this.highlightedEndSec = endSec;
        invalidate();
    }

    public boolean zoomIn() {
        for (int preset : ZOOM_WINDOWS_SECONDS) {
            if (preset < visibleWindowSec * 0.92) {
                visibleWindowSec = preset;
                notifyViewportChanged();
                invalidate();
                return true;
            }
        }
        return false;
    }

    public boolean zoomOut() {
        for (int i = ZOOM_WINDOWS_SECONDS.length - 1; i >= 0; i--) {
            int preset = ZOOM_WINDOWS_SECONDS[i];
            if (preset > visibleWindowSec * 1.08) {
                visibleWindowSec = preset;
                notifyViewportChanged();
                invalidate();
                return true;
            }
        }
        return false;
    }

    public void setRecordings(List<RecordingSegment> list) {
        this.hourlyStats.clear();
        if (list == null || list.isEmpty()) {
            this.segments = Collections.emptyList();
            invalidate();
            return;
        }
        Collections.sort(list, Comparator.comparingDouble(s -> s.startTime));
        int nonZeroCount = 0;
        for (RecordingSegment seg : list) {
            if (seg.motion > 0) nonZeroCount++;
        }
        double gFloor = 0.0;
        double gPeak = 2500.0;
        if (nonZeroCount > 0) {
            int[] motions = new int[nonZeroCount];
            int idx = 0;
            for (RecordingSegment seg : list) {
                if (seg.motion > 0) motions[idx++] = seg.motion;
            }
            java.util.Arrays.sort(motions);
            gFloor = motions[Math.min(motions.length - 1, motions.length / 4)];
            int refIdx = Math.max(0, motions.length - 1 - Math.min(5, motions.length / 20));
            gPeak = motions[refIdx];
        }
        this.globalNoiseFloor = gFloor;
        this.globalMotionSpan = Math.max(500.0, gPeak - gFloor);

        // Compute per-hour noise floor & span (matches Frigate's 1h chunk baseline subtraction)
        int i = 0;
        while (i < list.size()) {
            long hourKey = (long) Math.floor(list.get(i).startTime / 3600.0);
            int j = i;
            int hourPositive = 0;
            while (j < list.size() && (long) Math.floor(list.get(j).startTime / 3600.0) == hourKey) {
                if (list.get(j).motion > 0) hourPositive++;
                j++;
            }
            if (hourPositive > 0) {
                int[] hm = new int[hourPositive];
                int hi = 0;
                for (int k = i; k < j; k++) {
                    int m = list.get(k).motion;
                    if (m > 0) hm[hi++] = m;
                }
                java.util.Arrays.sort(hm);
                double hFloor = hm[Math.min(hm.length - 1, hm.length / 4)];
                double hPeak = hm[Math.max(0, hm.length - 1 - Math.min(2, hm.length / 30))];
                double hSpan = Math.max(this.globalMotionSpan * 0.50, hPeak - hFloor);
                this.hourlyStats.put(hourKey, new HourMotionStats(hFloor, hSpan));
            }
            i = j;
        }

        this.segments = list;
        invalidate();
    }

    public void setReviewSegments(List<ReviewSegment> list) {
        if (list == null || list.isEmpty()) {
            this.reviewSegments = Collections.emptyList();
            invalidate();
            return;
        }
        Collections.sort(list, Comparator.comparingDouble(r -> r.startTime));
        this.reviewSegments = list;
        invalidate();
    }

    private long epochForY(float y, int viewHeight) {
        double secsPerPx = visibleWindowSec / (double) viewHeight;
        long deltaSec = Math.round(((viewHeight / 2.0) - y) * secsPerPx);
        return Math.max(minEpochSec, Math.min(maxEpochSec, cursorEpochSec + deltaSec));
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int h = getHeight();
        if (h <= 0) return false;
        long now = System.currentTimeMillis() / 1000L;
        if (now > maxEpochSec) {
            maxEpochSec = now;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            isDragging = true;
            isPinching = false;
            scrubNotified = false;
            hadMultiTouch = false;
            activePointerId = event.getPointerId(0);
            secondaryPointerId = MotionEvent.INVALID_POINTER_ID;
            downX = event.getX(0);
            downY = event.getY(0);
            lastTouchY = downY;
            invalidate();
            return true;
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            if (event.getPointerCount() >= 2) {
                hadMultiTouch = true;
                int newIdx = event.getActionIndex();
                int newId = event.getPointerId(newIdx);
                if (activePointerId == MotionEvent.INVALID_POINTER_ID || event.findPointerIndex(activePointerId) < 0) {
                    activePointerId = event.getPointerId(0);
                }
                secondaryPointerId = (newId != activePointerId)
                        ? newId
                        : event.getPointerId(newIdx == 0 ? 1 : 0);
                int idx0 = event.findPointerIndex(activePointerId);
                int idx1 = event.findPointerIndex(secondaryPointerId);
                if (idx0 >= 0 && idx1 >= 0) {
                    isPinching = true;
                    pinchStartSpanY = Math.max(dp(24), Math.abs(event.getY(idx0) - event.getY(idx1)));
                    pinchStartWindowSec = visibleWindowSec;
                    invalidate();
                }
            }
            return true;
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (isPinching) {
                int idx0 = event.findPointerIndex(activePointerId);
                int idx1 = event.findPointerIndex(secondaryPointerId);
                if (idx0 >= 0 && idx1 >= 0) {
                    float curSpanY = Math.max(dp(18), Math.abs(event.getY(idx0) - event.getY(idx1)));
                    double nextWindow = pinchStartWindowSec * (pinchStartSpanY / curSpanY);
                    visibleWindowSec = Math.max(MIN_WINDOW_SECONDS, Math.min(MAX_WINDOW_SECONDS, nextWindow));
                    notifyViewportChanged();
                    invalidate();
                }
                return true;
            }
            int idx = event.findPointerIndex(activePointerId);
            if (idx < 0) idx = 0;
            float y = event.getY(idx);
            if (!scrubNotified) {
                if (Math.abs(y - downY) <= dp(6)) {
                    return true;
                }
                scrubNotified = true;
                isLivePinned = false;
                if (listener != null) {
                    listener.onScrubStart(cursorEpochSec);
                }
                lastTouchY = y;
                invalidate();
                return true;
            }
            float dy = y - lastTouchY;
            lastTouchY = y;
            double secsPerPx = visibleWindowSec / (double) h;
            // Newer timestamps are at the top: dragging down pulls newer times toward center -> cursor increases.
            long deltaSec = Math.round(dy * secsPerPx);
            if (deltaSec == 0 && Math.abs(dy) >= 0.5f) {
                deltaSec = dy > 0 ? 1 : -1;
            }
            long next = Math.max(minEpochSec, Math.min(maxEpochSec, cursorEpochSec + deltaSec));
            if (next != cursorEpochSec) {
                cursorEpochSec = next;
                notifyViewportChanged();
                if (listener != null) {
                    listener.onScrubChanged(cursorEpochSec);
                }
                invalidate();
            }
            return true;
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            int liftedIndex = event.getActionIndex();
            int liftedId = event.getPointerId(liftedIndex);
            if (isPinching && (liftedId == activePointerId || liftedId == secondaryPointerId)) {
                int remainingIdx = -1;
                for (int i = 0; i < event.getPointerCount(); i++) {
                    if (i != liftedIndex) {
                        remainingIdx = i;
                        break;
                    }
                }
                if (remainingIdx >= 0) {
                    activePointerId = event.getPointerId(remainingIdx);
                    lastTouchY = event.getY(remainingIdx);
                    downX = event.getX(remainingIdx);
                    downY = lastTouchY;
                } else {
                    activePointerId = MotionEvent.INVALID_POINTER_ID;
                }
                secondaryPointerId = MotionEvent.INVALID_POINTER_ID;
                isPinching = false;
            } else if (liftedId == activePointerId) {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    if (i != liftedIndex) {
                        activePointerId = event.getPointerId(i);
                        lastTouchY = event.getY(i);
                        downX = event.getX(i);
                        downY = lastTouchY;
                        break;
                    }
                }
            }
            return true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            int tapIdx = (activePointerId != MotionEvent.INVALID_POINTER_ID)
                    ? event.findPointerIndex(activePointerId)
                    : 0;
            if (tapIdx < 0 && event.getPointerCount() > 0) tapIdx = 0;
            float tapX = (tapIdx >= 0) ? event.getX(tapIdx) : downX;
            float tapY = (tapIdx >= 0) ? event.getY(tapIdx) : downY;
            boolean wasTap = (action == MotionEvent.ACTION_UP)
                    && !scrubNotified
                    && !hadMultiTouch
                    && Math.abs(tapY - downY) <= dp(6)
                    && Math.abs(tapX - downX) <= dp(12)
                    && tapY >= 0f && tapY <= h;

            isDragging = false;
            isPinching = false;
            hadMultiTouch = false;
            activePointerId = MotionEvent.INVALID_POINTER_ID;
            secondaryPointerId = MotionEvent.INVALID_POINTER_ID;
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            if (scrubNotified) {
                scrubNotified = false;
                if (listener != null) {
                    listener.onScrubEnd(cursorEpochSec);
                }
            } else if (wasTap) {
                long targetEpoch = epochForY(tapY, h);
                performClick();
                if (eventBrowserMode) {
                    double snapUnitSec = computeSnapUnitSec(h);
                    double minHitSec = (dp(14) * visibleWindowSec) / h;
                    double hitToleranceSec = Math.max(6.0, Math.max(snapUnitSec, minHitSec));
                    if (listener != null) {
                        listener.onTapZone(targetEpoch, hitToleranceSec);
                    }
                } else {
                    cursorEpochSec = targetEpoch;
                    isLivePinned = (now - targetEpoch) <= 4L;
                    notifyViewportChanged();
                    if (listener != null) {
                        listener.onScrubEnd(targetEpoch);
                    }
                }
            }
            invalidate();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        long now = System.currentTimeMillis() / 1000L;
        if (isLivePinned && !isDragging && now > maxEpochSec) {
            maxEpochSec = now;
            cursorEpochSec = now;
        }

        double windowSec = visibleWindowSec;
        double halfWindow = windowSec / 2.0;
        double topEpoch = cursorEpochSec + halfWindow;
        double bottomEpoch = cursorEpochSec - halfWindow;
        double pxPerSec = h / windowSec;

        // 1. Base background
        canvas.drawRect(0, 0, w, h, bgPaint);

        float waveformCenterX = w * 0.68f;
        float maxBarHalfWidth = w * 0.27f;
        float labelRightX = w * 0.36f;

        // 2. Recorded segments, object severity highlights (alert/detection), & activity waveform
        if (!segments.isEmpty() || !reviewSegments.isEmpty()) {
            int bucketSec = pickWaveformBucketSec(dp(4.0f) / pxPerSec);
            double snapUnitSec = bucketSec * 2.0; // Frigate-style segmentDuration (2 waveform bars)
            boolean[] rowRecorded = new boolean[h];
            byte[] rowSeverity = new byte[h]; // 0 = none, 1 = detection (peach), 2 = alert (rose)

            if (!segments.isEmpty()) {
                int firstIdx = lowerBound(segments, bottomEpoch - snapUnitSec - 120.0);
                for (int i = firstIdx; i < segments.size(); i++) {
                    RecordingSegment seg = segments.get(i);
                    if (seg.startTime > topEpoch + snapUnitSec) break;
                    if (seg.endTime < bottomEpoch - snapUnitSec) continue;

                    if (seg.endTime >= bottomEpoch && seg.startTime <= topEpoch) {
                        int yTop = (int) Math.floor((topEpoch - Math.min(topEpoch, seg.endTime)) * pxPerSec);
                        int yBot = (int) Math.ceil((topEpoch - Math.max(bottomEpoch, seg.startTime)) * pxPerSec);
                        if (yTop < 0) yTop = 0;
                        if (yBot >= h) yBot = h - 1;
                        for (int y = yTop; y <= yBot; y++) {
                            rowRecorded[y] = true;
                        }
                    }

                    if (!eventBrowserMode && seg.objects > 0) {
                        double snappedStart = Math.floor(seg.startTime / snapUnitSec) * snapUnitSec;
                        double snappedEnd = Math.floor(Math.max(seg.startTime, seg.endTime - 0.001) / snapUnitSec) * snapUnitSec + snapUnitSec;
                        if (snappedEnd >= bottomEpoch && snappedStart <= topEpoch) {
                            int yTop = (int) Math.floor((topEpoch - Math.min(topEpoch, snappedEnd)) * pxPerSec);
                            int yBot = (int) Math.ceil((topEpoch - Math.max(bottomEpoch, snappedStart)) * pxPerSec);
                            if (yTop < 0) yTop = 0;
                            if (yBot >= h) yBot = h - 1;
                            byte fallbackSev = (byte) (reviewSegments.isEmpty() ? 2 : 1);
                            for (int y = yTop; y <= yBot; y++) {
                                if (fallbackSev > rowSeverity[y]) rowSeverity[y] = fallbackSev;
                            }
                        }
                    }
                }
            }

            if (!eventBrowserMode && !reviewSegments.isEmpty()) {
                int firstRev = lowerBoundReview(reviewSegments, bottomEpoch - snapUnitSec - 300.0);
                for (int i = firstRev; i < reviewSegments.size(); i++) {
                    ReviewSegment rev = reviewSegments.get(i);
                    if (rev.startTime > topEpoch + snapUnitSec) break;
                    double snappedStart = Math.floor(rev.startTime / snapUnitSec) * snapUnitSec;
                    double snappedEnd = Math.floor(Math.max(rev.startTime, rev.endTime - 0.001) / snapUnitSec) * snapUnitSec + snapUnitSec;
                    if (snappedEnd < bottomEpoch || snappedStart > topEpoch) continue;

                    int yTop = (int) Math.floor((topEpoch - Math.min(topEpoch, snappedEnd)) * pxPerSec);
                    int yBot = (int) Math.ceil((topEpoch - Math.max(bottomEpoch, snappedStart)) * pxPerSec);
                    if (yTop < 0) yTop = 0;
                    if (yBot >= h) yBot = h - 1;
                    byte sev = "alert".equalsIgnoreCase(rev.severity) ? (byte) 2 : (byte) 1;
                    for (int y = yTop; y <= yBot; y++) {
                        if (sev > rowSeverity[y]) rowSeverity[y] = sev;
                    }
                }
            }

            // Draw recorded background strip
            float bandLeft = w * 0.38f;
            float bandRight = w - dp(2);
            int y = 0;
            while (y < h) {
                if (!rowRecorded[y]) {
                    y++;
                    continue;
                }
                int startY = y;
                while (y < h && rowRecorded[y]) y++;
                canvas.drawRect(bandLeft, startY, bandRight, y, recordedBandPaint);
            }

            // Draw smooth full-width Frigate-style object severity bands (soft rose for alert, soft peach for detection)
            if (eventBrowserMode) {
                drawAggregatedEventBrowserBands(canvas, w, h, topEpoch, bottomEpoch, pxPerSec, snapUnitSec, rowSeverity);
            } else {
                y = 0;
                while (y < h) {
                    byte sev = rowSeverity[y];
                    if (sev == 0) {
                        y++;
                        continue;
                    }
                    int startY = y;
                    while (y < h && rowSeverity[y] == sev) y++;
                    Paint bandPaint = sev == 2 ? alertBandPaint : detectionBandPaint;
                    canvas.drawRect(0, startY, w, y, bandPaint);
                }
            }

            // Draw time-aligned activity waveform bars (~4.0dp pitch, Frigate linear + baseline-subtracted scaling)
            if (!segments.isEmpty()) {
                long firstBucket = ((long) Math.floor(bottomEpoch / bucketSec)) * bucketSec;
                long lastBucket = (long) Math.ceil(topEpoch);
                float barHalfH = dp(0.9f);
                float idleDotHalfW = dp(0.65f);
                float minActiveBar = dp(1.4f);
                int segScanIdx = lowerBound(segments, firstBucket - 120.0);

                for (long tStart = firstBucket; tStart < lastBucket; tStart += bucketSec) {
                    long tEnd = tStart + bucketSec;
                    int motionCount = 0;
                    int maxSegMotion = 0;

                    while (segScanIdx < segments.size() && segments.get(segScanIdx).endTime <= tStart) {
                        segScanIdx++;
                    }
                    for (int i = segScanIdx; i < segments.size(); i++) {
                        RecordingSegment seg = segments.get(i);
                        if (seg.startTime >= tEnd) break;
                        if (seg.endTime <= tStart) continue;
                        motionCount++;
                        if (seg.motion > maxSegMotion) maxSegMotion = seg.motion;
                    }

                    if (motionCount == 0) continue;
                    double midTime = tStart + bucketSec * 0.5;
                    long hourKey = (long) Math.floor(midTime / 3600.0);
                    HourMotionStats stats = hourlyStats.get(hourKey);
                    double floor = stats != null ? stats.noiseFloor : globalNoiseFloor;
                    double span = stats != null ? stats.span : globalMotionSpan;
                    float norm = maxSegMotion > floor
                            ? (float) Math.min(1.0, (maxSegMotion - floor) / span)
                            : 0f;

                    float cyBar = (float) ((topEpoch - midTime) * pxPerSec);
                    if (cyBar < -barHalfH || cyBar > h + barHalfH) continue;

                    if (norm > 0.03f) {
                        float halfW = Math.max(minActiveBar, norm * maxBarHalfWidth);
                        canvas.drawRoundRect(
                                waveformCenterX - halfW, cyBar - barHalfH,
                                waveformCenterX + halfW, cyBar + barHalfH,
                                barHalfH, barHalfH, motionPaint);
                    } else {
                        canvas.drawRoundRect(
                                waveformCenterX - idleDotHalfW, cyBar - barHalfH,
                                waveformCenterX + idleDotHalfW, cyBar + barHalfH,
                                barHalfH, barHalfH, idleDotPaint);
                    }
                }
            }
        }

        // 3. Shade future area (above maxEpochSec)
        if (topEpoch > maxEpochSec) {
            float futureBottomY = (float) ((topEpoch - maxEpochSec) * pxPerSec);
            canvas.drawRect(0, 0, w, Math.min(h, futureBottomY), futureTintPaint);
        }

        // 4. Time ticks & labels (aligned with local timezone + day boundary indicator at 00:00)
        int majorStep = pickMajorTickStep(windowSec);
        int minorStep = pickMinorTickStep(majorStep);
        long refTzOffsetSec = java.util.TimeZone.getDefault().getOffset(((long) cursorEpochSec) * 1000L) / 1000L;
        long firstMinor = ((long) Math.floor((bottomEpoch + refTzOffsetSec) / minorStep)) * minorStep - refTzOffsetSec;
        float majorLen = dp(10);
        float minorLen = dp(4);
        Paint.FontMetrics fm = labelPaint.getFontMetrics();
        float textCenterOffset = -(fm.ascent + fm.descent) / 2f;

        for (long t = firstMinor; t <= (long) Math.ceil(topEpoch); t += minorStep) {
            float y = (float) ((topEpoch - t) * pxPerSec);
            if (y < 0 || y > h) continue;
            long localT = t + (java.util.TimeZone.getDefault().getOffset(t * 1000L) / 1000L);
            boolean isMidnight = Math.floorMod(localT, 86400L) == 0;
            boolean isMajor = isMidnight || Math.floorMod(localT, majorStep) == 0;
            if (isMidnight) {
                canvas.drawLine(0, y, w, y, dayTickPaint);
            } else {
                canvas.drawLine(0, y, isMajor ? majorLen : minorLen, y, tickPaint);
            }
            if (isMajor && y > dp(12) && y < h - dp(12)) {
                Date tickDate = new Date(t * 1000L);
                String lbl = isMidnight ? dayLabelFmt.format(tickDate) : timeHmFmt.format(tickDate);
                canvas.drawText(lbl, dp(11), y + textCenterOffset, isMidnight ? dayLabelPaint : labelPaint);
            }
        }

        // 5. Center fixed red scrubber line + pill badge
        float cy = h / 2f;
        canvas.drawLine(0, cy, w, cy, redLinePaint);

        String pillText = (isLivePinned && !eventBrowserMode)
                ? FrigateI18n.tr(getContext(), config, "common_live")
                : timeHmsFmt.format(new Date(cursorEpochSec * 1000L));
        float pillW = Math.min(w - dp(4), Math.max(dp(58), redPillTextPaint.measureText(pillText) + dp(12)));
        float pillH = dp(20);
        pillRect.set((w - pillW) / 2f, cy - pillH / 2f, (w + pillW) / 2f, cy + pillH / 2f);
        canvas.drawRoundRect(pillRect, dp(10), dp(10), redPillPaint);

        Paint.FontMetrics pfm = redPillTextPaint.getFontMetrics();
        float pillTextY = cy - (pfm.ascent + pfm.descent) / 2f;
        canvas.drawText(pillText, w / 2f, pillTextY, redPillTextPaint);
    }

    private double computeSnapUnitSec(int viewHeight) {
        if (viewHeight <= 0) return 60.0;
        double pxPerSec = viewHeight / visibleWindowSec;
        int bucketSec = pickWaveformBucketSec(dp(4.0f) / pxPerSec);
        return bucketSec * 2.0;
    }

    /**
     * Renders aggregated event-browser severity zones using discrete Frigate-style time buckets:
     * adjacent/overlapping events merge cleanly with severity priority (alert > detection) and
     * display a compact count badge (e.g. "x3") when multiple events fall in the same cluster.
     */
    private void drawAggregatedEventBrowserBands(Canvas canvas, int w, int h,
                                                 double topEpoch, double bottomEpoch,
                                                 double pxPerSec, double snapUnitSec,
                                                 byte[] rowSeverity) {
        float minBandPx = dp(6);
        float edgeW = dp(3.5f);

        tmpVisualBands.clear();
        if (!reviewSegments.isEmpty()) {
            int firstRev = lowerBoundReview(reviewSegments, bottomEpoch - snapUnitSec - 300.0);
            for (int i = firstRev; i < reviewSegments.size(); i++) {
                ReviewSegment rev = reviewSegments.get(i);
                if (rev.startTime > topEpoch + snapUnitSec + 60.0) break;
                double snappedStart = Math.floor(rev.startTime / snapUnitSec) * snapUnitSec;
                double snappedEnd = Math.floor(Math.max(rev.startTime, rev.endTime - 0.001) / snapUnitSec)
                        * snapUnitSec + snapUnitSec;
                if (snappedEnd < bottomEpoch - 60.0 || snappedStart > topEpoch + 60.0) continue;

                float bandTop = (float) ((topEpoch - snappedEnd) * pxPerSec);
                float bandBot = (float) ((topEpoch - snappedStart) * pxPerSec);
                if (bandBot - bandTop < minBandPx) {
                    float mid = (bandTop + bandBot) * 0.5f;
                    bandTop = mid - minBandPx * 0.5f;
                    bandBot = mid + minBandPx * 0.5f;
                }
                if (bandBot < 0f || bandTop > h) continue;

                byte sev = "alert".equalsIgnoreCase(rev.severity) ? (byte) 2 : (byte) 1;
                tmpVisualBands.add(new VisualBand(bandTop, bandBot, sev));

                int yTop = Math.max(0, (int) Math.floor(bandTop));
                int yBot = Math.min(h - 1, (int) Math.ceil(bandBot));
                for (int y = yTop; y <= yBot; y++) {
                    if (sev > rowSeverity[y]) {
                        rowSeverity[y] = sev;
                    }
                }
            }

            // Draw contiguous severity strips without double-alpha stacking
            int y = 0;
            while (y < h) {
                byte sev = rowSeverity[y];
                if (sev == 0) {
                    y++;
                    continue;
                }
                int startY = y;
                while (y < h && rowSeverity[y] == sev) y++;
                boolean isAlert = (sev == 2);
                canvas.drawRect(0, startY, w, y, isAlert ? alertBandPaint : detectionBandPaint);
                canvas.drawRect(w - edgeW, startY, w, y, isAlert ? alertEdgePaint : detectionEdgePaint);
            }

            // Merge overlapping/adjacent bands and draw compact count pills for multi-event clusters
            if (tmpVisualBands.size() > 1) {
                Collections.sort(tmpVisualBands, (a, b) -> Float.compare(a.top, b.top));
                float mergeGapPx = dp(2.0f);
                float curTop = tmpVisualBands.get(0).top;
                float curBot = tmpVisualBands.get(0).bot;
                byte curSev = tmpVisualBands.get(0).sev;
                int curCount = 1;
                float lastBadgeBotY = -1000f;

                for (int i = 1; i < tmpVisualBands.size(); i++) {
                    VisualBand b = tmpVisualBands.get(i);
                    if (b.top <= curBot + mergeGapPx) {
                        curBot = Math.max(curBot, b.bot);
                        if (b.sev > curSev) curSev = b.sev;
                        curCount++;
                    } else {
                        lastBadgeBotY = maybeDrawClusterCountBadge(
                                canvas, w, h, edgeW, curTop, curBot, curCount, curSev, lastBadgeBotY);
                        curTop = b.top;
                        curBot = b.bot;
                        curSev = b.sev;
                        curCount = 1;
                    }
                }
                maybeDrawClusterCountBadge(
                        canvas, w, h, edgeW, curTop, curBot, curCount, curSev, lastBadgeBotY);
            }
        }

        if (highlightedEndSec > 0.0 && highlightedStartSec > 0.0
                && highlightedEndSec >= bottomEpoch - snapUnitSec && highlightedStartSec <= topEpoch + snapUnitSec) {
            float rawTop = (float) ((topEpoch - highlightedEndSec) * pxPerSec);
            float rawBot = (float) ((topEpoch - highlightedStartSec) * pxPerSec);
            float minSelPx = dp(7);
            if (rawBot - rawTop < minSelPx) {
                float mid = (rawTop + rawBot) * 0.5f;
                rawTop = mid - minSelPx * 0.5f;
                rawBot = mid + minSelPx * 0.5f;
            }
            float drawTop = Math.max(0f, rawTop);
            float drawBot = Math.min(h, rawBot);
            if (drawBot > drawTop) {
                canvas.drawRect(0, drawTop, w, drawBot, selectedBandPaint);
                canvas.drawRect(dp(1), drawTop, w - dp(1), drawBot, selectedBorderPaint);
            }
        }
    }

    private float maybeDrawClusterCountBadge(Canvas canvas, int w, int h, float edgeW,
                                              float clTop, float clBot, int count, byte maxSev,
                                              float lastBadgeBotY) {
        if (count < 2) return lastBadgeBotY;
        float visibleTop = Math.max(0f, clTop);
        float visibleBot = Math.min(h, clBot);
        if (visibleBot <= visibleTop) return lastBadgeBotY;

        float badgeH = dp(13);
        float midY = Math.max(badgeH * 0.5f + dp(2), Math.min(h - badgeH * 0.5f - dp(2), (visibleTop + visibleBot) * 0.5f));
        if (Math.abs(midY - h * 0.5f) < dp(15)) {
            return lastBadgeBotY;
        }
        float top = midY - badgeH * 0.5f;
        float bot = midY + badgeH * 0.5f;
        if (top < lastBadgeBotY + dp(2)) {
            return lastBadgeBotY;
        }

        String label = "\u00d7" + count;
        float textW = clusterBadgeTextPaint.measureText(label);
        float badgeW = Math.max(dp(18), textW + dp(7));
        float right = w - edgeW - dp(3);
        float left = Math.max(w * 0.44f, right - badgeW);
        if (right <= left + dp(8)) return lastBadgeBotY;

        clusterRect.set(left, top, right, bot);
        clusterBadgeBgPaint.setColor(maxSev == 2 ? Color.parseColor("#E11D48") : Color.parseColor("#EA580C"));
        canvas.drawRoundRect(clusterRect, dp(6.5f), dp(6.5f), clusterBadgeBgPaint);
        canvas.drawRoundRect(clusterRect, dp(6.5f), dp(6.5f), clusterBadgeBorderPaint);

        Paint.FontMetrics fm = clusterBadgeTextPaint.getFontMetrics();
        float textY = midY - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(label, (left + right) * 0.5f, textY, clusterBadgeTextPaint);
        return bot;
    }

    private static int lowerBound(List<RecordingSegment> list, double targetStart) {
        int lo = 0;
        int hi = list.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).endTime < targetStart) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static int lowerBoundReview(List<ReviewSegment> list, double targetStart) {
        int lo = 0;
        int hi = list.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).endTime < targetStart) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static int pickWaveformBucketSec(double targetSec) {
        int best = WAVEFORM_BUCKET_CANDIDATES[WAVEFORM_BUCKET_CANDIDATES.length - 1];
        double bestDiff = Double.MAX_VALUE;
        for (int candidate : WAVEFORM_BUCKET_CANDIDATES) {
            double diff = Math.abs(candidate - targetSec);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = candidate;
            }
        }
        return best;
    }

    private static int pickMajorTickStep(double windowSec) {
        if (windowSec > 50000.0) return 14400; // 4h
        if (windowSec > 18000.0) return 7200; // 2h
        if (windowSec > 9600.0) return 3600;  // 1h
        if (windowSec > 4800.0) return 1800;  // 30m
        if (windowSec > 2400.0) return 900;   // 15m
        if (windowSec > 1200.0) return 300;   // 5m
        if (windowSec > 480.0) return 180;    // 3m
        return 60;                            // 1m
    }

    private static int pickMinorTickStep(int majorStep) {
        switch (majorStep) {
            case 14400: return 3600; // 1h
            case 7200: return 1200;  // 20m
            case 3600: return 600;  // 10m
            case 1800: return 300;  // 5m
            case 900:  return 180;  // 3m
            case 300:  return 60;   // 1m
            case 180:  return 30;   // 30s
            default:   return 15;   // 15s
        }
    }
}
