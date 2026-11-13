// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.SystemClock;
import android.util.LruCache;
import io.github.tibohlami.ksplugins.frigate.api.FrigateApiClient;
import io.github.tibohlami.ksplugins.frigate.api.FrigateHttpHelper;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import me.jxl.kiosk.plugins.PluginHost;
import io.github.tibohlami.ksplugins.frigate.model.FrigateEvent;
import io.github.tibohlami.ksplugins.frigate.model.RecordingSegment;
import io.github.tibohlami.ksplugins.frigate.model.ReviewSegment;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages incremental timeline history loading (recording segments, review segments, events)
 * and low-res scrub preview thumbnail fetching/caching for {@link FrigatePlayerOverlay}.
 */
public class FrigatePlayerTimelineController {

    public interface Listener {
        boolean isClosed();
        boolean isLiveStreamMode();
        boolean isLivePaused();
        boolean isRulerScrubbing();
        String getCurrentCamera();
        FrigateTimelineRulerView getTimelineRuler();
        void onTimelineRecordingsUpdated(long maxEpochHint, List<RecordingSegment> segments);
        void onTimelineReviewsUpdated(List<ReviewSegment> reviews);
        void onTimelineEventsUpdated(List<FrigateEvent> events);
        void onScrubBitmapReady(Bitmap bitmap);
    }

    private static final long TIMELINE_CHUNK_SECONDS = 86400L;
    private static final long TIMELINE_PREFETCH_MARGIN_SECONDS = 43200L; // 12h lookahead margin
    private static final long TIMELINE_MAX_SINGLE_FETCH_SECONDS = 2L * 86400L;
    private static final long TIMELINE_LIVE_TAIL_MIN_GAP_SECONDS = 15L;
    private static final long TIMELINE_LIVE_TAIL_REFRESH_MS = 10000L;
    private static final long TIMELINE_RETRY_COOLDOWN_MS = 3000L;

    private final PluginHost host;
    private final FrigateApiClient apiClient;
    private final FrigateConfig config;
    private final Handler mainHandler;
    private final Listener listener;

    private final List<long[]> loadedTimelineRanges = new ArrayList<>();
    private final List<RecordingSegment> allRecordingSegments = new ArrayList<>();
    private final List<ReviewSegment> allReviewSegments = new ArrayList<>();
    private final Map<String, FrigateEvent> timelineEventMap = new LinkedHashMap<>();

    private volatile List<FrigateEvent> timelineEvents = Collections.emptyList();
    private volatile List<ReviewSegment> timelineReviews = Collections.emptyList();

    private boolean isHistoryChunkLoading = false;
    private boolean isLiveTailRefreshInFlight = false;
    private long lastLiveTailRefreshElapsedMs = 0L;
    private long requestedHistoryMinEpoch = Long.MAX_VALUE;
    private long requestedHistoryMaxEpoch = 0L;
    private long lastHistoryErrorMs = 0L;

    private final ExecutorService scrubExecutor = Executors.newSingleThreadExecutor();
    private final Object scrubLock = new Object();
    private long scrubPendingEpoch = -1L;
    private boolean scrubWorkerScheduled = false;
    private final LruCache<Long, Bitmap> scrubBitmapCache = new LruCache<Long, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return value != null ? value.getByteCount() : 0;
        }
    };

    public FrigatePlayerTimelineController(PluginHost host, FrigateApiClient apiClient, FrigateConfig config,
                                           Handler mainHandler, Listener listener) {
        this.host = host;
        this.apiClient = apiClient;
        this.config = config;
        this.mainHandler = mainHandler;
        this.listener = listener;
    }

    public List<FrigateEvent> getTimelineEvents() {
        return timelineEvents;
    }

    public List<ReviewSegment> getTimelineReviews() {
        return timelineReviews;
    }

    public void resetLiveTailTimer() {
        lastLiveTailRefreshElapsedMs = 0L;
    }

    public void loadTimelineRecordings() {
        String camera = listener.getCurrentCamera();
        if (camera == null || camera.isEmpty()) return;
        long now = System.currentTimeMillis() / 1000L;
        requestedHistoryMinEpoch = Math.min(requestedHistoryMinEpoch, Math.max(0L, now - TIMELINE_CHUNK_SECONDS));
        requestedHistoryMaxEpoch = Math.max(requestedHistoryMaxEpoch, now);
        FrigateTimelineRulerView ruler = listener.getTimelineRuler();
        if (ruler != null) {
            ensureTimelineCoverage(ruler.getVisibleBottomEpoch(), ruler.getVisibleTopEpoch());
        } else {
            pumpTimelineChunkQueue();
        }
    }

    public void ensureTimelineCoverage(long viewBottomEpoch, long viewTopEpoch) {
        String camera = listener.getCurrentCamera();
        if (listener.isClosed() || camera == null || camera.isEmpty()) return;
        long now = System.currentTimeMillis() / 1000L;
        long span = Math.max(3600L, viewTopEpoch - viewBottomEpoch);
        long margin = Math.max(TIMELINE_PREFETCH_MARGIN_SECONDS, span);
        long reqStart = Math.max(0L, viewBottomEpoch - margin);
        long reqEnd = Math.min(now, Math.max(viewTopEpoch + TIMELINE_PREFETCH_MARGIN_SECONDS, viewBottomEpoch + span));
        if (reqStart < requestedHistoryMinEpoch) {
            requestedHistoryMinEpoch = reqStart;
        }
        if (reqEnd > requestedHistoryMaxEpoch) {
            requestedHistoryMaxEpoch = reqEnd;
        }
        pumpTimelineChunkQueue();
    }

    public void maybeRefreshLiveTimelineTail() {
        if (listener.isClosed() || !listener.isLiveStreamMode()
                || listener.isLivePaused() || listener.isRulerScrubbing()) {
            return;
        }
        if (isLiveTailRefreshInFlight || isHistoryChunkLoading || loadedTimelineRanges.isEmpty()) return;
        String camera = listener.getCurrentCamera();
        if (camera == null || camera.isEmpty()) return;

        long elapsed = SystemClock.elapsedRealtime();
        if (lastLiveTailRefreshElapsedMs > 0L && (elapsed - lastLiveTailRefreshElapsedMs) < TIMELINE_LIVE_TAIL_REFRESH_MS) {
            return;
        }

        long now = System.currentTimeMillis() / 1000L;
        long maxLoaded = loadedTimelineRanges.get(loadedTimelineRanges.size() - 1)[1];
        // Re-query the last ~2 minutes (or back to maxLoaded - 15s, capped at 30m) so newly flushed 10s segments appear promptly.
        long tailAfter = Math.max(0L, Math.max(now - 1800L, Math.min(now - 120L, maxLoaded - 15L)));
        if (now <= tailAfter) return;

        isLiveTailRefreshInFlight = true;
        lastLiveTailRefreshElapsedMs = elapsed;
        requestedHistoryMaxEpoch = Math.max(requestedHistoryMaxEpoch, now);

        fetchTimelineWindow(camera, tailAfter, now, () -> {
            isLiveTailRefreshInFlight = false;
            markTimelineRangeLoaded(tailAfter, now);
            pumpTimelineChunkQueue();
        }, () -> isLiveTailRefreshInFlight = false);
    }

    private void pumpTimelineChunkQueue() {
        String camera = listener.getCurrentCamera();
        if (listener.isClosed() || isHistoryChunkLoading || camera == null || camera.isEmpty()) return;
        if (lastHistoryErrorMs > 0L && (SystemClock.elapsedRealtime() - lastHistoryErrorMs) < TIMELINE_RETRY_COOLDOWN_MS) {
            return;
        }
        long now = System.currentTimeMillis() / 1000L;
        long targetStart = Math.max(0L, requestedHistoryMinEpoch);
        long targetEnd = Math.min(now, requestedHistoryMaxEpoch);
        if (targetEnd <= targetStart) return;

        long[] chunk = findNextUncoveredChunk(targetStart, targetEnd, now);
        if (chunk == null) return;

        final long chunkAfter = chunk[0];
        final long chunkBefore = chunk[1];
        if (chunkBefore <= chunkAfter) return;

        isHistoryChunkLoading = true;
        fetchTimelineWindow(camera, chunkAfter, chunkBefore, () -> {
            isHistoryChunkLoading = false;
            lastHistoryErrorMs = 0L;
            if (loadedTimelineRanges.isEmpty() || chunkBefore >= requestedHistoryMaxEpoch - 5L) {
                lastLiveTailRefreshElapsedMs = SystemClock.elapsedRealtime();
            }
            markTimelineRangeLoaded(chunkAfter, chunkBefore);
            pumpTimelineChunkQueue();
        }, () -> {
            isHistoryChunkLoading = false;
            lastHistoryErrorMs = SystemClock.elapsedRealtime();
        });
    }

    private void fetchTimelineWindow(final String camera, final long chunkAfter, final long chunkBefore,
                                     final Runnable onCompleteOk, final Runnable onCompleteError) {
        final AtomicInteger pendingCalls = new AtomicInteger(3);
        final AtomicBoolean recordingsOk = new AtomicBoolean(false);

        Runnable onSubCallFinished = () -> {
            if (pendingCalls.decrementAndGet() == 0) {
                if (recordingsOk.get()) {
                    if (onCompleteOk != null) onCompleteOk.run();
                } else {
                    if (onCompleteError != null) onCompleteError.run();
                }
            }
        };

        apiClient.fetchRecordings(camera, chunkAfter, chunkBefore, new FrigateApiClient.Callback<List<RecordingSegment>>() {
            @Override
            public void onSuccess(List<RecordingSegment> result) {
                mainHandler.post(() -> {
                    if (listener.isClosed() || listener.getTimelineRuler() == null) return;
                    recordingsOk.set(true);
                    mergeRecordingSegments(result, Math.max(chunkBefore, System.currentTimeMillis() / 1000L));
                    onSubCallFinished.run();
                });
            }

            @Override
            public void onError(Exception error) {
                android.util.Log.d("FrigatePlugin", "Failed to load recordings chunk [" + chunkAfter + "," + chunkBefore + "]: " + error.getMessage());
                mainHandler.post(() -> {
                    if (!listener.isClosed()) onSubCallFinished.run();
                });
            }
        });

        apiClient.fetchReviewSegments(camera, chunkAfter, chunkBefore, new FrigateApiClient.Callback<List<ReviewSegment>>() {
            @Override
            public void onSuccess(List<ReviewSegment> result) {
                mainHandler.post(() -> {
                    if (listener.isClosed() || listener.getTimelineRuler() == null) return;
                    mergeReviewSegments(result);
                    onSubCallFinished.run();
                });
            }

            @Override
            public void onError(Exception error) {
                android.util.Log.d("FrigatePlugin", "Failed to load review segments chunk [" + chunkAfter + "," + chunkBefore + "]: " + error.getMessage());
                mainHandler.post(() -> {
                    if (!listener.isClosed()) onSubCallFinished.run();
                });
            }
        });

        apiClient.fetchCameraEventsRange(camera, chunkAfter, chunkBefore, 2000, new FrigateApiClient.Callback<List<FrigateEvent>>() {
            @Override
            public void onSuccess(List<FrigateEvent> result) {
                mainHandler.post(() -> {
                    if (listener.isClosed() || listener.getTimelineRuler() == null) return;
                    mergeTimelineEvents(result);
                    onSubCallFinished.run();
                });
            }

            @Override
            public void onError(Exception error) {
                android.util.Log.d("FrigatePlugin", "Failed to load camera events chunk [" + chunkAfter + "," + chunkBefore + "]: " + error.getMessage());
                mainHandler.post(() -> {
                    if (!listener.isClosed()) onSubCallFinished.run();
                });
            }
        });
    }

    private long[] findNextUncoveredChunk(long targetStart, long targetEnd, long now) {
        long clampedEnd = Math.min(now, targetEnd);
        if (clampedEnd <= targetStart) {
            return null;
        }
        if (loadedTimelineRanges.isEmpty()) {
            long firstStart = Math.max(targetStart, clampedEnd - TIMELINE_CHUNK_SECONDS);
            return new long[]{firstStart, clampedEnd};
        }

        // First scan uncovered history below maxLoaded, ignoring tiny live-edge drift above maxLoaded
        // (handled periodically by maybeRefreshLiveTimelineTail) so scrolling >24h never gets blocked.
        long maxLoaded = loadedTimelineRanges.get(loadedTimelineRanges.size() - 1)[1];
        long histEnd = Math.min(clampedEnd, maxLoaded);
        if (histEnd > targetStart) {
            long cursorEnd = histEnd;
            long gapStart = -1L;
            long gapEnd = -1L;
            for (int i = loadedTimelineRanges.size() - 1; i >= 0; i--) {
                long[] r = loadedTimelineRanges.get(i);
                if (r[1] <= targetStart) break;
                if (r[0] >= cursorEnd) continue;
                if (r[1] < cursorEnd) {
                    gapStart = Math.max(targetStart, r[1]);
                    gapEnd = cursorEnd;
                    break;
                }
                cursorEnd = Math.min(cursorEnd, r[0]);
                if (cursorEnd <= targetStart) break;
            }
            if (gapEnd < 0 && cursorEnd > targetStart) {
                gapStart = targetStart;
                gapEnd = cursorEnd;
            }
            if (gapEnd > gapStart) {
                // If user jumped straight to an old event (> 2 days gap), load around targetStart first.
                if ((gapEnd - gapStart) > TIMELINE_MAX_SINGLE_FETCH_SECONDS) {
                    long chunkEnd = Math.min(gapEnd, gapStart + TIMELINE_CHUNK_SECONDS);
                    return new long[]{gapStart, chunkEnd};
                }
                // Otherwise load a contiguous 24h chunk backwards from gapEnd.
                long chunkStart = Math.max(0L, gapEnd - TIMELINE_CHUNK_SECONDS);
                return new long[]{chunkStart, gapEnd};
            }
        }

        // All requested older history is covered; fill live-edge drift if >= 15s.
        if (clampedEnd > maxLoaded && (clampedEnd - maxLoaded) >= TIMELINE_LIVE_TAIL_MIN_GAP_SECONDS) {
            return new long[]{Math.max(0L, maxLoaded - 15L), clampedEnd};
        }
        return null;
    }

    private void markTimelineRangeLoaded(long start, long end) {
        if (end <= start) return;
        loadedTimelineRanges.add(new long[]{start, end});
        Collections.sort(loadedTimelineRanges, (a, b) -> {
            int cmp = Long.compare(a[0], b[0]);
            return (cmp != 0) ? cmp : Long.compare(a[1], b[1]);
        });
        List<long[]> merged = new ArrayList<>(loadedTimelineRanges.size());
        for (long[] cur : loadedTimelineRanges) {
            if (merged.isEmpty()) {
                merged.add(new long[]{cur[0], cur[1]});
            } else {
                long[] prev = merged.get(merged.size() - 1);
                if (cur[0] <= prev[1]) {
                    prev[1] = Math.max(prev[1], cur[1]);
                } else {
                    merged.add(new long[]{cur[0], cur[1]});
                }
            }
        }
        loadedTimelineRanges.clear();
        loadedTimelineRanges.addAll(merged);
    }

    private void mergeRecordingSegments(List<RecordingSegment> incoming, long maxEpochHint) {
        if (incoming != null && !incoming.isEmpty()) {
            allRecordingSegments.addAll(incoming);
            Collections.sort(allRecordingSegments, Comparator.comparingDouble(s -> s.startTime));
            List<RecordingSegment> dedup = new ArrayList<>(allRecordingSegments.size());
            RecordingSegment prev = null;
            for (RecordingSegment cur : allRecordingSegments) {
                if (cur == null) continue;
                if (prev != null
                        && Math.abs(prev.startTime - cur.startTime) < 0.001
                        && Math.abs(prev.endTime - cur.endTime) < 0.001) {
                    continue;
                }
                dedup.add(cur);
                prev = cur;
            }
            allRecordingSegments.clear();
            allRecordingSegments.addAll(dedup);
        }
        listener.onTimelineRecordingsUpdated(maxEpochHint, new ArrayList<>(allRecordingSegments));
    }

    private void mergeReviewSegments(List<ReviewSegment> incoming) {
        if (incoming != null && !incoming.isEmpty()) {
            allReviewSegments.addAll(incoming);
            Collections.sort(allReviewSegments, Comparator.comparingDouble(r -> r.startTime));
            List<ReviewSegment> dedup = new ArrayList<>(allReviewSegments.size());
            ReviewSegment prev = null;
            for (ReviewSegment cur : allReviewSegments) {
                if (cur == null) continue;
                if (prev != null
                        && Math.abs(prev.startTime - cur.startTime) < 0.001
                        && Math.abs(prev.endTime - cur.endTime) < 0.001
                        && prev.severity.equals(cur.severity)) {
                    continue;
                }
                dedup.add(cur);
                prev = cur;
            }
            allReviewSegments.clear();
            allReviewSegments.addAll(dedup);
        }
        timelineReviews = new ArrayList<>(allReviewSegments);
        listener.onTimelineReviewsUpdated(timelineReviews);
    }

    private void mergeTimelineEvents(List<FrigateEvent> incoming) {
        if (incoming != null) {
            for (FrigateEvent ev : incoming) {
                if (ev == null) continue;
                String key = (ev.getId() != null && !ev.getId().isEmpty())
                        ? ev.getId()
                        : (ev.getCamera() + ":" + ev.getStartTime() + ":" + ev.getLabel());
                timelineEventMap.put(key, ev);
            }
        }
        timelineEvents = new ArrayList<>(timelineEventMap.values());
        listener.onTimelineEventsUpdated(timelineEvents);
    }

    public void requestScrubSnapshot(long epochSeconds) {
        String camera = listener.getCurrentCamera();
        if (listener.isClosed() || camera == null || camera.isEmpty()) return;
        Bitmap cached = scrubBitmapCache.get(epochSeconds);
        if (cached != null) {
            listener.onScrubBitmapReady(cached);
            return;
        }
        synchronized (scrubLock) {
            scrubPendingEpoch = epochSeconds;
            if (scrubWorkerScheduled) return;
            scrubWorkerScheduled = true;
        }
        if (scrubExecutor.isShutdown()) return;
        try {
            scrubExecutor.execute(() -> {
                try {
                    while (!listener.isClosed()) {
                        final long targetEpoch;
                        synchronized (scrubLock) {
                            if (scrubPendingEpoch < 0) {
                                scrubWorkerScheduled = false;
                                return;
                            }
                            targetEpoch = scrubPendingEpoch;
                            scrubPendingEpoch = -1L;
                        }
                        Bitmap cachedInner = scrubBitmapCache.get(targetEpoch);
                        if (cachedInner != null && !cachedInner.isRecycled()) {
                            mainHandler.post(() -> {
                                if (!listener.isClosed() && listener.isRulerScrubbing()) {
                                    listener.onScrubBitmapReady(cachedInner);
                                }
                            });
                            continue;
                        }
                        Bitmap bmp = fetchRecordingSnapshotBitmap(camera, targetEpoch);
                        if (bmp != null && !listener.isClosed()) {
                            scrubBitmapCache.put(targetEpoch, bmp);
                            mainHandler.post(() -> {
                                if (!listener.isClosed() && listener.isRulerScrubbing()) {
                                    listener.onScrubBitmapReady(bmp);
                                }
                            });
                        }
                    }
                } catch (Throwable t) {
                    // Shield background thread from unhandled errors
                } finally {
                    synchronized (scrubLock) {
                        scrubWorkerScheduled = false;
                    }
                }
            });
        } catch (Throwable ignored) {
            synchronized (scrubLock) {
                scrubWorkerScheduled = false;
            }
        }
    }

    private Bitmap fetchRecordingSnapshotBitmap(String camera, long epochSeconds) {
        HttpURLConnection conn = null;
        try {
            String urlStr = apiClient.getRecordingSnapshotUrl(camera, epochSeconds, 360);
            URL url = new URL(urlStr);
            conn = FrigateHttpHelper.openConnection(url, config);
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(3500);
            FrigateHttpHelper.applyAuthHeaders(conn, config);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                byte[] data;
                try (InputStream is = conn.getInputStream();
                     java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        bos.write(buf, 0, r);
                    }
                    data = bos.toByteArray();
                }
                FrigateHttpHelper.logImageDebug(host, config, "GET", urlStr, data.length);
                return AsyncImageLoader.decodeSampledBitmap(data, 640, 360);
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    public void destroy() {
        try {
            scrubExecutor.shutdownNow();
        } catch (Exception ignored) {}
        try {
            scrubBitmapCache.evictAll();
        } catch (Exception ignored) {}
    }
}
