// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.frigate.api;

import android.os.Handler;
import me.jxl.kiosk.plugins.frigate.model.DaySummary;
import me.jxl.kiosk.plugins.frigate.model.FrigateEvent;
import me.jxl.kiosk.plugins.frigate.model.RecordingSegment;
import me.jxl.kiosk.plugins.frigate.model.ReviewSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Coordinates paginated Frigate event & review loading, severity enrichment, synthetic
 * review-only fallback items, tracked labels/day summaries, and camera recording chunks.
 */
public class FrigateEventsRepository {

    public interface Listener {
        void onTrackedLabelsLoaded(List<String> labels);
        void onDaySummariesUpdated();
        void onPageApplied(int querySeq, List<FrigateEvent> uniqueNewEvents, boolean hasMoreOlder, boolean isInitialPage);
        void onPageEmpty(int querySeq, boolean isInitialPage);
        void onPageError(int querySeq, boolean isInitialPage);
        void onRecordingsUpdated(int querySeq, List<RecordingSegment> recordings);
    }

    public static final class FilterQuery {
        public final Set<String> selectedCameras;
        public final Set<String> selectedLabels;
        public final String selectedSeverity;
        public final long[] dayBounds;

        public FilterQuery(Set<String> selectedCameras,
                           Set<String> selectedLabels,
                           String selectedSeverity,
                           long[] dayBounds) {
            this.selectedCameras = selectedCameras != null ? new LinkedHashSet<>(selectedCameras) : Collections.emptySet();
            this.selectedLabels = selectedLabels != null ? new LinkedHashSet<>(selectedLabels) : Collections.emptySet();
            this.selectedSeverity = (selectedSeverity != null && !selectedSeverity.isEmpty()) ? selectedSeverity : "all";
            this.dayBounds = dayBounds;
        }
    }

    private static final int PAGE_EVENTS_LIMIT = 120;
    private static final int PAGE_REVIEW_LIMIT = 60;
    private static final long RECORDING_CHUNK_SECONDS = 21600L; // 6h

    private final FrigateApiClient apiClient;
    private final Handler mainHandler;
    private final Listener listener;

    private final List<DaySummary> availableDaySummaries = new ArrayList<>();
    private final List<DaySummary> availableReviewDaySummaries = new ArrayList<>();
    private final Set<String> seenEventIds = new LinkedHashSet<>();

    private final List<RecordingSegment> loadedRecordings = new ArrayList<>();
    private final List<long[]> loadedRecordingRanges = new ArrayList<>();
    private boolean recordingsLoading = false;

    private int activeQuerySeq = 0;
    private boolean isLoadingPage = false;
    private boolean hasMoreOlder = true;
    private double oldestFetchedStartTime = 0.0;
    private volatile boolean isDestroyed = false;

    public FrigateEventsRepository(FrigateApiClient apiClient, Handler mainHandler, Listener listener) {
        this.apiClient = apiClient;
        this.mainHandler = mainHandler;
        this.listener = listener;
    }

    public int getActiveQuerySeq() {
        return activeQuerySeq;
    }

    public boolean isLoadingPage() {
        return isLoadingPage;
    }

    public boolean hasMoreOlder() {
        return hasMoreOlder;
    }

    public double getOldestFetchedStartTime() {
        return oldestFetchedStartTime;
    }

    public String getThumbnailUrl(String eventId) {
        return apiClient.getThumbnailUrl(eventId);
    }

    public void fetchConfiguredLabels() {
        apiClient.fetchTrackedLabels(new FrigateApiClient.Callback<List<String>>() {
            @Override
            public void onSuccess(List<String> result) {
                if (isDestroyed || result == null || result.isEmpty()) return;
                mainHandler.post(() -> {
                    if (isDestroyed) return;
                    listener.onTrackedLabelsLoaded(result);
                });
            }

            @Override
            public void onError(Exception e) {
                // Non-critical; labels from events remain available
            }
        });
    }

    public void fetchDaySummaries() {
        apiClient.fetchEventsSummary(new FrigateApiClient.Callback<List<DaySummary>>() {
            @Override
            public void onSuccess(List<DaySummary> result) {
                if (isDestroyed || result == null) return;
                mainHandler.post(() -> {
                    if (isDestroyed) return;
                    availableDaySummaries.clear();
                    availableDaySummaries.addAll(result);
                    listener.onDaySummariesUpdated();
                });
            }

            @Override
            public void onError(Exception e) {
                // Non-critical; calendar & day chips remain usable
            }
        });
        apiClient.fetchReviewSummary(new FrigateApiClient.Callback<List<DaySummary>>() {
            @Override
            public void onSuccess(List<DaySummary> result) {
                if (isDestroyed || result == null) return;
                mainHandler.post(() -> {
                    if (isDestroyed) return;
                    availableReviewDaySummaries.clear();
                    availableReviewDaySummaries.addAll(result);
                    listener.onDaySummariesUpdated();
                });
            }

            @Override
            public void onError(Exception e) {
                // Non-critical on older Frigate versions without /api/review/summary
            }
        });
    }

    public Map<String, Integer> computeFilteredDayCounts(Set<String> selectedCameras, Set<String> selectedLabels) {
        Map<String, Integer> dayCounts = new TreeMap<>(Collections.reverseOrder());
        for (DaySummary ds : availableDaySummaries) {
            if (selectedCameras != null && !selectedCameras.isEmpty() && !selectedCameras.contains(ds.camera)) continue;
            if (selectedLabels != null && !selectedLabels.isEmpty() && !selectedLabels.contains(ds.label)) continue;
            Integer prev = dayCounts.get(ds.day);
            dayCounts.put(ds.day, (prev != null ? prev : 0) + ds.count);
        }
        if ((selectedCameras == null || selectedCameras.isEmpty())
                && (selectedLabels == null || selectedLabels.isEmpty())) {
            for (DaySummary rs : availableReviewDaySummaries) {
                if (!dayCounts.containsKey(rs.day) && rs.count > 0) {
                    dayCounts.put(rs.day, rs.count);
                }
            }
        }
        return dayCounts;
    }

    public int startReload(FilterQuery query) {
        final int querySeq = ++activeQuerySeq;
        isLoadingPage = true;
        hasMoreOlder = true;
        oldestFetchedStartTime = 0.0;

        seenEventIds.clear();
        loadedRecordings.clear();
        loadedRecordingRanges.clear();
        recordingsLoading = false;

        loadPageInternal(query, querySeq, 0L, true);
        return querySeq;
    }

    public boolean loadOlderPage(FilterQuery query) {
        if (isDestroyed || isLoadingPage || !hasMoreOlder || oldestFetchedStartTime <= 0.0) {
            return false;
        }
        isLoadingPage = true;
        long beforeEpoch = Math.max(1L, (long) Math.floor(oldestFetchedStartTime) - 1L);
        loadPageInternal(query, activeQuerySeq, beforeEpoch, false);
        return true;
    }

    public void ensureRecordingsForRange(Set<String> selectedCameras, long bottomEpoch, long topEpoch) {
        if (isDestroyed || selectedCameras == null || selectedCameras.size() != 1 || recordingsLoading) return;
        long reqStart = Math.max(0L, bottomEpoch - RECORDING_CHUNK_SECONDS / 2L);
        long reqEnd = Math.min(System.currentTimeMillis() / 1000L, topEpoch + 300L);
        if (reqEnd <= reqStart) return;

        for (long[] r : loadedRecordingRanges) {
            if (r[0] <= bottomEpoch && r[1] >= topEpoch) {
                return;
            }
        }
        recordingsLoading = true;
        final String cam = selectedCameras.iterator().next();
        final int seq = activeQuerySeq;
        apiClient.fetchRecordings(cam, reqStart, reqEnd, new FrigateApiClient.Callback<List<RecordingSegment>>() {
            @Override
            public void onSuccess(List<RecordingSegment> result) {
                if (isDestroyed || seq != activeQuerySeq) return;
                recordingsLoading = false;
                loadedRecordingRanges.add(new long[]{reqStart, reqEnd});
                if (result != null && !result.isEmpty()) {
                    loadedRecordings.addAll(result);
                    Collections.sort(loadedRecordings, Comparator.comparingDouble(r -> r.startTime));
                    final List<RecordingSegment> snapshot = new ArrayList<>(loadedRecordings);
                    mainHandler.post(() -> {
                        if (!isDestroyed && seq == activeQuerySeq) {
                            listener.onRecordingsUpdated(seq, snapshot);
                        }
                    });
                }
            }

            @Override
            public void onError(Exception e) {
                recordingsLoading = false;
            }
        });
    }

    public void destroy() {
        isDestroyed = true;
        activeQuerySeq++;
    }

    private void loadPageInternal(FilterQuery query, int querySeq, long beforeEpoch, boolean isInitialPage) {
        final String camFilter = buildCsvFilter(query.selectedCameras);
        final String lblFilter = buildCsvFilter(query.selectedLabels);
        final String sevFilter = query.selectedSeverity;
        final long[] dayBounds = query.dayBounds;

        if (dayBounds != null && beforeEpoch > 0L && beforeEpoch <= dayBounds[0]) {
            mainHandler.post(() -> notifyEmptyPage(querySeq, isInitialPage));
            return;
        }

        final long queryAfter = (dayBounds != null) ? dayBounds[0] : 0L;
        final long queryBefore = (beforeEpoch > 0L)
                ? ((dayBounds != null) ? Math.min(beforeEpoch, dayBounds[1]) : beforeEpoch)
                : ((dayBounds != null) ? dayBounds[1] : 0L);

        if ("all".equals(sevFilter)) {
            apiClient.fetchEventsFiltered(camFilter, lblFilter, queryAfter, queryBefore, PAGE_EVENTS_LIMIT,
                    new FrigateApiClient.Callback<List<FrigateEvent>>() {
                        @Override
                        public void onSuccess(List<FrigateEvent> rawEvents) {
                            if (isDestroyed || querySeq != activeQuerySeq) return;
                            final List<FrigateEvent> pageEvents = rawEvents != null ? rawEvents : Collections.emptyList();
                            if (pageEvents.isEmpty()) {
                                // Fallback to /api/review when /api/events has no entries
                                apiClient.fetchReviewSegmentsFiltered(camFilter, lblFilter, null, queryAfter, queryBefore,
                                        PAGE_REVIEW_LIMIT, new FrigateApiClient.Callback<List<ReviewSegment>>() {
                                            @Override
                                            public void onSuccess(List<ReviewSegment> reviews) {
                                                if (isDestroyed || querySeq != activeQuerySeq) return;
                                                List<FrigateEvent> synthetic = mergeEventsAndReviews(
                                                        query, Collections.emptyList(), reviews, "all", dayBounds, true);
                                                if (synthetic.isEmpty()) {
                                                    mainHandler.post(() -> notifyEmptyPage(querySeq, isInitialPage));
                                                    return;
                                                }
                                                boolean more = reviews != null && reviews.size() >= PAGE_REVIEW_LIMIT;
                                                mainHandler.post(() -> deliverLoadedPage(querySeq, synthetic, more, isInitialPage));
                                            }

                                            @Override
                                            public void onError(Exception e) {
                                                if (isDestroyed || querySeq != activeQuerySeq) return;
                                                mainHandler.post(() -> notifyEmptyPage(querySeq, isInitialPage));
                                            }
                                        });
                                return;
                            }
                            double minStart = Double.MAX_VALUE;
                            double maxEnd = 0.0;
                            for (FrigateEvent ev : pageEvents) {
                                if (ev.getStartTime() > 0 && ev.getStartTime() < minStart) minStart = ev.getStartTime();
                                double end = Math.max(ev.getStartTime(), ev.getEndTime());
                                if (end > maxEnd) maxEnd = end;
                            }
                            long revAfter = (dayBounds != null && isInitialPage)
                                    ? queryAfter
                                    : Math.max(queryAfter, (long) Math.floor(minStart) - 60L);
                            long revBefore = (dayBounds != null && isInitialPage)
                                    ? queryBefore
                                    : ((queryBefore > 0L)
                                        ? Math.min(queryBefore + 60L, (long) Math.ceil(maxEnd) + 60L)
                                        : (long) Math.ceil(maxEnd) + 60L);

                            apiClient.fetchReviewSegmentsFiltered(camFilter, lblFilter, null, revAfter, revBefore, 400,
                                    new FrigateApiClient.Callback<List<ReviewSegment>>() {
                                        @Override
                                        public void onSuccess(List<ReviewSegment> reviews) {
                                            if (isDestroyed || querySeq != activeQuerySeq) return;
                                            List<FrigateEvent> enriched = mergeEventsAndReviews(
                                                    query, pageEvents, reviews, "all", dayBounds, dayBounds != null);
                                            boolean more = pageEvents.size() >= PAGE_EVENTS_LIMIT
                                                    || (reviews != null && reviews.size() >= 400);
                                            mainHandler.post(() -> deliverLoadedPage(querySeq, enriched, more, isInitialPage));
                                        }

                                        @Override
                                        public void onError(Exception e) {
                                            if (isDestroyed || querySeq != activeQuerySeq) return;
                                            List<FrigateEvent> fallback = mergeEventsAndReviews(
                                                    query, pageEvents, null, "all", dayBounds, false);
                                            mainHandler.post(() -> deliverLoadedPage(
                                                    querySeq, fallback, pageEvents.size() >= PAGE_EVENTS_LIMIT, isInitialPage));
                                        }
                                    });
                        }

                        @Override
                        public void onError(Exception e) {
                            if (isDestroyed || querySeq != activeQuerySeq) return;
                            mainHandler.post(() -> notifyLoadError(querySeq, isInitialPage));
                        }
                    });
        } else {
            // Filter by explicit severity ("alert" or "detection") via /api/review first
            apiClient.fetchReviewSegmentsFiltered(camFilter, lblFilter, sevFilter, queryAfter, queryBefore, PAGE_REVIEW_LIMIT,
                    new FrigateApiClient.Callback<List<ReviewSegment>>() {
                        @Override
                        public void onSuccess(List<ReviewSegment> reviews) {
                            if (isDestroyed || querySeq != activeQuerySeq) return;
                            if (reviews == null || reviews.isEmpty()) {
                                mainHandler.post(() -> notifyEmptyPage(querySeq, isInitialPage));
                                return;
                            }
                            double minStart = Double.MAX_VALUE;
                            double maxEnd = 0.0;
                            for (ReviewSegment rev : reviews) {
                                if (rev.startTime < minStart) minStart = rev.startTime;
                                if (rev.endTime > maxEnd) maxEnd = rev.endTime;
                            }
                            final double batchMinStart = minStart;
                            long evAfter = Math.max(queryAfter, (long) Math.floor(batchMinStart) - 10L);
                            long evBefore = (queryBefore > 0L)
                                    ? Math.min(queryBefore, (long) Math.ceil(maxEnd) + 10L)
                                    : (long) Math.ceil(maxEnd) + 10L;

                            apiClient.fetchEventsFiltered(camFilter, lblFilter, evAfter, evBefore, 1000,
                                    new FrigateApiClient.Callback<List<FrigateEvent>>() {
                                        @Override
                                        public void onSuccess(List<FrigateEvent> events) {
                                            if (isDestroyed || querySeq != activeQuerySeq) return;
                                            List<FrigateEvent> matched = mergeEventsAndReviews(
                                                    query, events, reviews, sevFilter, dayBounds, true);
                                            mainHandler.post(() -> {
                                                if (batchMinStart < Double.MAX_VALUE &&
                                                        (oldestFetchedStartTime <= 0.0 || batchMinStart < oldestFetchedStartTime)) {
                                                    oldestFetchedStartTime = batchMinStart;
                                                }
                                                deliverLoadedPage(querySeq, matched,
                                                        reviews.size() >= PAGE_REVIEW_LIMIT, isInitialPage);
                                            });
                                        }

                                        @Override
                                        public void onError(Exception e) {
                                            if (isDestroyed || querySeq != activeQuerySeq) return;
                                            List<FrigateEvent> fallback = mergeEventsAndReviews(
                                                    query, Collections.emptyList(), reviews, sevFilter, dayBounds, true);
                                            mainHandler.post(() -> {
                                                if (batchMinStart < Double.MAX_VALUE &&
                                                        (oldestFetchedStartTime <= 0.0 || batchMinStart < oldestFetchedStartTime)) {
                                                    oldestFetchedStartTime = batchMinStart;
                                                }
                                                deliverLoadedPage(querySeq, fallback,
                                                        reviews.size() >= PAGE_REVIEW_LIMIT, isInitialPage);
                                            });
                                        }
                                    });
                        }

                        @Override
                        public void onError(Exception e) {
                            if (isDestroyed || querySeq != activeQuerySeq) return;
                            mainHandler.post(() -> notifyLoadError(querySeq, isInitialPage));
                        }
                    });
        }
    }

    private void notifyEmptyPage(int querySeq, boolean isInitialPage) {
        if (isDestroyed || querySeq != activeQuerySeq) return;
        isLoadingPage = false;
        hasMoreOlder = false;
        listener.onPageEmpty(querySeq, isInitialPage);
    }

    private void notifyLoadError(int querySeq, boolean isInitialPage) {
        if (isDestroyed || querySeq != activeQuerySeq) return;
        isLoadingPage = false;
        listener.onPageError(querySeq, isInitialPage);
    }

    private void deliverLoadedPage(int querySeq, List<FrigateEvent> batch,
                                   boolean moreAvailable, boolean isInitialPage) {
        if (isDestroyed || querySeq != activeQuerySeq) return;
        isLoadingPage = false;
        hasMoreOlder = moreAvailable;

        List<FrigateEvent> uniqueNew = new ArrayList<>();
        if (batch != null) {
            for (FrigateEvent ev : batch) {
                if (ev.getId() != null && !seenEventIds.add(ev.getId())) continue;
                uniqueNew.add(ev);
                if (ev.getStartTime() > 0.0 && (oldestFetchedStartTime <= 0.0 || ev.getStartTime() < oldestFetchedStartTime)) {
                    oldestFetchedStartTime = ev.getStartTime();
                }
            }
        }
        listener.onPageApplied(querySeq, uniqueNew, hasMoreOlder, isInitialPage);
    }

    private List<FrigateEvent> mergeEventsAndReviews(FilterQuery query,
                                                     List<FrigateEvent> rawEvents,
                                                     List<ReviewSegment> reviews,
                                                     String requiredSeverity,
                                                     long[] dayBounds,
                                                     boolean includeSyntheticReviews) {
        Map<String, String> byIdSeverity = new HashMap<>();
        if (reviews != null) {
            for (ReviewSegment rev : reviews) {
                for (String detId : rev.detectionIds) {
                    String existing = byIdSeverity.get(detId);
                    if (!"alert".equals(existing)) {
                        byIdSeverity.put(detId, rev.severity);
                    }
                }
            }
        }

        List<FrigateEvent> out = new ArrayList<>();
        Set<String> representedEventIds = new LinkedHashSet<>();
        boolean filterBySeverity = requiredSeverity != null && !"all".equals(requiredSeverity);

        if (rawEvents != null) {
            for (FrigateEvent ev : rawEvents) {
                if (dayBounds != null && (ev.getStartTime() < dayBounds[0] || ev.getStartTime() >= dayBounds[1])) {
                    continue;
                }
                String resolved = byIdSeverity.get(ev.getId());
                if (resolved == null && reviews != null) {
                    double evStart = ev.getStartTime();
                    double evEnd = Math.max(evStart, ev.getEndTime());
                    for (ReviewSegment rev : reviews) {
                        if (rev.camera != null && !rev.camera.isEmpty() && !rev.camera.equals(ev.getCamera())) {
                            continue;
                        }
                        if (evEnd >= (rev.startTime - 2.0) && evStart <= (rev.endTime + 2.0)) {
                            if (rev.objects.isEmpty() || rev.objects.contains(ev.getLabel())) {
                                resolved = rev.severity;
                                if ("alert".equals(resolved)) break;
                            }
                        }
                    }
                }
                if (resolved == null && ev.getSeverity() != null && !ev.getSeverity().isEmpty()) {
                    resolved = ev.getSeverity();
                }
                if (filterBySeverity) {
                    if (!requiredSeverity.equalsIgnoreCase(resolved)) {
                        continue;
                    }
                } else if (resolved == null) {
                    resolved = "detection";
                }
                FrigateEvent withSev = ev.withSeverity(resolved);
                out.add(withSev);
                if (ev.getId() != null && !ev.getId().isEmpty()) {
                    representedEventIds.add(ev.getId());
                }
            }
        }

        if (includeSyntheticReviews && reviews != null && !reviews.isEmpty()) {
            for (ReviewSegment rev : reviews) {
                if (rev.startTime <= 0.0) continue;
                if (dayBounds != null && (rev.startTime < dayBounds[0] || rev.startTime >= dayBounds[1])) {
                    continue;
                }
                if (!query.selectedCameras.isEmpty() && (rev.camera == null || !query.selectedCameras.contains(rev.camera))) {
                    continue;
                }
                String revSev = (rev.severity != null && !rev.severity.isEmpty()) ? rev.severity : "detection";
                if (filterBySeverity && !requiredSeverity.equalsIgnoreCase(revSev)) {
                    continue;
                }
                String matchedLabel = pickMatchingReviewLabel(query.selectedLabels, rev);
                if (matchedLabel == null) {
                    continue;
                }
                boolean alreadyCovered = false;
                for (String detId : rev.detectionIds) {
                    if (representedEventIds.contains(detId) || seenEventIds.contains(detId)) {
                        alreadyCovered = true;
                        break;
                    }
                }
                if (!alreadyCovered) {
                    for (FrigateEvent existing : out) {
                        if (rev.camera != null && !rev.camera.isEmpty() && !rev.camera.equals(existing.getCamera())) {
                            continue;
                        }
                        double exStart = existing.getStartTime();
                        double exEnd = Math.max(exStart, existing.getEndTime());
                        if (exEnd >= (rev.startTime - 2.0) && exStart <= (rev.endTime + 2.0)
                                && (rev.objects.isEmpty() || rev.objects.contains(existing.getLabel()))) {
                            alreadyCovered = true;
                            break;
                        }
                    }
                }
                if (alreadyCovered) continue;

                String synthId = (rev.id != null && !rev.id.isEmpty())
                        ? ("rev:" + rev.id)
                        : ("rev:" + (rev.camera != null ? rev.camera : "cam") + ":" + Math.round(rev.startTime * 1000.0));
                String thumbUrl = apiClient.getReviewThumbnailUrl(rev);
                out.add(FrigateEvent.fromReviewFallback(
                        synthId, rev.camera != null ? rev.camera : "unknown",
                        matchedLabel, rev.startTime, rev.endTime, revSev, thumbUrl, false));
            }
        }
        return out;
    }

    private static String pickMatchingReviewLabel(Set<String> selectedLabels, ReviewSegment rev) {
        if (selectedLabels != null && !selectedLabels.isEmpty()) {
            for (String objLbl : rev.objects) {
                if (selectedLabels.contains(objLbl)) {
                    return objLbl;
                }
            }
            return null;
        }
        if (!rev.objects.isEmpty()) {
            return rev.objects.get(0);
        }
        return "motion";
    }

    private static String buildCsvFilter(Set<String> items) {
        if (items == null || items.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (item != null && !item.trim().isEmpty()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(item.trim());
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
}
