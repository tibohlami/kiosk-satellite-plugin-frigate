// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.PluginHost;
import io.github.tibohlami.ksplugins.frigate.api.FrigateApiClient;
import io.github.tibohlami.ksplugins.frigate.api.FrigateEventsWsClient;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import io.github.tibohlami.ksplugins.frigate.model.FrigateEvent;
import io.github.tibohlami.ksplugins.frigate.model.ReviewSegment;
import io.github.tibohlami.ksplugins.frigate.model.TimelineBoxEntry;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Coordinates object-detection bounding-box lazy loading, live in-progress event polling,
 * and header object-pill rendering for {@link FrigatePlayerOverlay}.
 */
public class FrigatePlayerObjectDetectorController {

    public interface HostBridge {
        boolean isClosed();
        boolean isOverlayVisible();
        boolean isLiveStreamMode();
        boolean isLivePaused();
        boolean isRulerScrubbing();
        boolean isTimelineMode();
        boolean isShowObjectBoxes();
        String getCurrentCamera();
        FrigateEvent getPrimaryEvent();
        double getCurrentPlaybackEpochDouble();
        List<FrigateEvent> getTimelineEvents();
        List<ReviewSegment> getTimelineReviews();
        FrigateObjectBoxesOverlayView getObjectBoxesOverlay();
        LinearLayout getHeaderObjectsContainer();
        View getLiveHighlightView();
        LinearLayout getLiveOverlayBadgeRow();
    }

    private static final class ActiveLiveDetection {
        String id;
        String label;
        String severity;
        double score;
    }

    private final Context context;
    private final PluginHost host;
    private KsTheme theme;
    private final FrigateApiClient apiClient;
    private final FrigateConfig config;
    private final Handler mainHandler;
    private final HostBridge bridge;

    private final Map<String, List<TimelineBoxEntry>> eventTimelineBoxCache = new LinkedHashMap<>();
    private final Set<String> pendingTimelineBoxFetches = new HashSet<>();
    private final Handler liveObjectsHandler = new Handler(Looper.getMainLooper());
    private boolean liveObjectPollerRunning = false;
    private volatile boolean liveObjectPollInFlight = false;
    private FrigateEventsWsClient liveWsClient;
    private final Map<String, ActiveLiveDetection> liveDetectionsById = new LinkedHashMap<>();
    private final Map<String, String> activeReviewSeverityById = new LinkedHashMap<>();
    private final Map<String, String> eventSeverityFromReview = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 200;
        }
    };
    private String lastLiveBadgeKey = "";
    private String lastLiveBorderKey = "";
    private Map<String, String> lastOverlaySeverityByLabel = Collections.emptyMap();
    private String lastObjectBadgesKey = "";
    private Map<String, Double> lastActiveLabels = Collections.emptyMap();

    public FrigatePlayerObjectDetectorController(Context context,
                                                 PluginHost host,
                                                 KsTheme theme,
                                                 FrigateApiClient apiClient,
                                                 FrigateConfig config,
                                                 Handler mainHandler,
                                                 HostBridge bridge) {
        this.context = context;
        this.host = host;
        this.theme = theme;
        this.apiClient = apiClient;
        this.config = config;
        this.mainHandler = mainHandler;
        this.bridge = bridge;
    }

    public void onThemeChanged(KsTheme theme) {
        if (theme != null) {
            this.theme = theme;
            lastObjectBadgesKey = "";
            lastLiveBadgeKey = "";
            lastLiveBorderKey = "";
            if (lastActiveLabels != null && !lastActiveLabels.isEmpty()) {
                renderObjectBadges(lastActiveLabels);
            }
            if (bridge.isLiveStreamMode()) {
                renderLiveActivityOverlay();
            } else if (lastOverlaySeverityByLabel != null && !lastOverlaySeverityByLabel.isEmpty()) {
                renderOverlayPills(lastOverlaySeverityByLabel);
            }
        }
    }

    private int s(float dp) {
        float scale = (config != null) ? config.getUiScale() : 1.0f;
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density * scale);
    }

    private float sp(float sp) {
        float scale = (config != null) ? config.getUiScale() : 1.0f;
        return sp * scale;
    }

    public void updateObjectBoxesForEpoch(double epochSeconds) {
        FrigateObjectBoxesOverlayView overlay = bridge.getObjectBoxesOverlay();
        if (overlay == null) return;
        if (bridge.isLiveStreamMode() || !bridge.isShowObjectBoxes() || bridge.isRulerScrubbing()) {
            overlay.clear();
            return;
        }
        double targetEpoch = epochSeconds + (config != null ? config.getBboxTimeOffsetSec() : 0.0);
        List<FrigateEvent> candidates = new ArrayList<>();
        List<FrigateEvent> evList = bridge.getTimelineEvents();
        if (evList != null) {
            for (FrigateEvent ev : evList) {
                if (ev == null) continue;
                double end = Math.max(ev.getStartTime(), ev.getEndTime());
                if (targetEpoch >= ev.getStartTime() - 0.5 && targetEpoch <= end + 0.5) {
                    candidates.add(ev);
                }
            }
        }
        FrigateEvent primaryEvent = bridge.getPrimaryEvent();
        if (!bridge.isTimelineMode() && primaryEvent != null) {
            boolean alreadyIncluded = false;
            String targetId = primaryEvent.getId();
            if (targetId != null && !targetId.isEmpty()) {
                for (FrigateEvent c : candidates) {
                    if (targetId.equals(c.getId())) {
                        alreadyIncluded = true;
                        break;
                    }
                }
            }
            if (!alreadyIncluded) {
                double evEnd = Math.max(primaryEvent.getStartTime(), primaryEvent.getEndTime());
                if (primaryEvent.getStartTime() <= 0
                        || (targetEpoch >= primaryEvent.getStartTime() - 0.5 && targetEpoch <= evEnd + 0.5)) {
                    candidates.add(primaryEvent);
                }
            }
        }

        for (FrigateEvent cand : candidates) {
            final String eid = cand.getId();
            if (eid == null || eid.isEmpty()) continue;
            if (!eventTimelineBoxCache.containsKey(eid)
                    && !pendingTimelineBoxFetches.contains(eid)
                    && pendingTimelineBoxFetches.size() < 6) {
                pendingTimelineBoxFetches.add(eid);
                apiClient.fetchEventTimelineBoxes(eid, new FrigateApiClient.Callback<List<TimelineBoxEntry>>() {
                    @Override
                    public void onSuccess(List<TimelineBoxEntry> result) {
                        mainHandler.post(() -> {
                            pendingTimelineBoxFetches.remove(eid);
                            if (bridge.isClosed()) return;
                            eventTimelineBoxCache.put(eid, result != null ? result : Collections.emptyList());
                            FrigateObjectBoxesOverlayView curOverlay = bridge.getObjectBoxesOverlay();
                            if (curOverlay != null) {
                                curOverlay.invalidateEventTrajectory(eid);
                                if (!bridge.isLiveStreamMode() && bridge.isShowObjectBoxes() && !bridge.isRulerScrubbing()) {
                                    updateObjectBoxesForEpoch(bridge.getCurrentPlaybackEpochDouble());
                                }
                            }
                        });
                    }

                    @Override
                    public void onError(Exception error) {
                        mainHandler.post(() -> {
                            pendingTimelineBoxFetches.remove(eid);
                            if (!bridge.isClosed()) {
                                eventTimelineBoxCache.put(eid, Collections.emptyList());
                            }
                        });
                    }
                });
            }
        }

        overlay.updateForEpoch(targetEpoch, candidates, eventTimelineBoxCache);
    }

    public void startLiveObjectPoller() {
        stopLiveObjectPoller();
        liveObjectPollerRunning = true;
        // Hide header pills in live mode; live detections render as video overlay pills + border.
        LinearLayout headerContainer = bridge.getHeaderObjectsContainer();
        if (headerContainer != null) {
            headerContainer.removeAllViews();
            headerContainer.setVisibility(View.GONE);
            lastObjectBadgesKey = "";
        }
        startLiveWsClient();
        liveObjectsHandler.post(liveObjectsRunnable);
    }

    public void stopLiveObjectPoller() {
        liveObjectPollerRunning = false;
        liveObjectsHandler.removeCallbacksAndMessages(null);
        stopLiveWsClient();
        liveDetectionsById.clear();
        activeReviewSeverityById.clear();
        eventSeverityFromReview.clear();
        clearLiveActivityOverlay();
    }

    private void startLiveWsClient() {
        stopLiveWsClient();
        liveWsClient = new FrigateEventsWsClient(config, host, new FrigateEventsWsClient.Listener() {
            @Override
            public void onWsConnected() {
                if (!liveObjectPollerRunning || bridge.isClosed() || !bridge.isLiveStreamMode()) return;
                pollInProgressEventsOnce();
            }

            @Override
            public void onWsDisconnected() {
                if (!liveObjectPollerRunning || bridge.isClosed() || !bridge.isLiveStreamMode()) return;
                liveObjectsHandler.removeCallbacks(liveObjectsRunnable);
                liveObjectsHandler.postDelayed(liveObjectsRunnable, 2500);
            }

            @Override
            public void onFrigateEventMessage(String type, JSONObject afterObj, JSONObject beforeObj) {
                if (!liveObjectPollerRunning || bridge.isClosed() || !bridge.isLiveStreamMode()) return;
                handleWsEvent(type, afterObj != null ? afterObj : beforeObj);
            }

            @Override
            public void onFrigateReviewMessage(String type, JSONObject afterObj, JSONObject beforeObj) {
                if (!liveObjectPollerRunning || bridge.isClosed() || !bridge.isLiveStreamMode()) return;
                handleWsReview(type, afterObj != null ? afterObj : beforeObj);
            }
        });
        liveWsClient.start();
    }

    private void stopLiveWsClient() {
        if (liveWsClient != null) {
            try {
                liveWsClient.close();
            } catch (Throwable ignored) {}
            liveWsClient = null;
        }
    }

    public boolean sendPtzCommand(String camera, String command) {
        if (liveWsClient == null || camera == null || camera.trim().isEmpty()
                || command == null || command.trim().isEmpty()) {
            return false;
        }
        return liveWsClient.sendTopicPayload(camera.trim() + "/ptz", command.trim());
    }

    private void handleWsEvent(String type, JSONObject obj) {
        if (obj == null) return;
        String cam = bridge.getCurrentCamera();
        String evCam = obj.optString("camera", "");
        if (cam == null || !cam.equals(evCam)) return;

        String id = obj.optString("id", "");
        if (id.isEmpty()) return;

        boolean ended = "end".equalsIgnoreCase(type)
                || obj.optBoolean("false_positive", false)
                || (!obj.isNull("end_time") && obj.optDouble("end_time", 0) > 0);
        if (ended) {
            eventSeverityFromReview.remove(id);
            if (liveDetectionsById.remove(id) != null) {
                renderLiveActivityOverlay();
            }
            return;
        }

        String label = obj.optString("label", "");
        if (label.trim().isEmpty()) return;
        ActiveLiveDetection det = liveDetectionsById.get(id);
        if (det == null) {
            det = new ActiveLiveDetection();
            det.id = id;
            liveDetectionsById.put(id, det);
        }
        det.label = label.trim().toLowerCase(Locale.ROOT);
        det.score = obj.optDouble("top_score", obj.optDouble("score", 0.0));
        String sev = obj.optString("severity", "");
        if (sev.isEmpty()) {
            sev = eventSeverityFromReview.get(id);
        }
        if (sev != null && !sev.isEmpty()) {
            det.severity = sev;
        } else if (det.severity == null) {
            det.severity = "detection";
        }
        renderLiveActivityOverlay();
    }

    private void handleWsReview(String type, JSONObject obj) {
        if (obj == null) return;
        String cam = bridge.getCurrentCamera();
        String revCam = obj.optString("camera", "");
        if (cam == null || !cam.equals(revCam)) return;

        String revId = obj.optString("id", "");
        if (revId.isEmpty()) return;

        boolean ended = "end".equalsIgnoreCase(type)
                || (!obj.isNull("end_time") && obj.optDouble("end_time", 0) > 0);
        if (ended) {
            activeReviewSeverityById.remove(revId);
            renderLiveActivityOverlay();
            return;
        }

        String severity = obj.optString("severity", "detection");
        activeReviewSeverityById.put(revId, severity);

        JSONObject data = obj.optJSONObject("data");
        if (data != null) {
            JSONArray detections = data.optJSONArray("detections");
            if (detections != null) {
                for (int i = 0; i < detections.length(); i++) {
                    String evId = detections.optString(i, "");
                    if (!evId.isEmpty()) {
                        eventSeverityFromReview.put(evId, severity);
                        ActiveLiveDetection det = liveDetectionsById.get(evId);
                        if (det != null) {
                            det.severity = severity;
                        }
                    }
                }
            }
        }
        renderLiveActivityOverlay();
    }

    private final Runnable liveObjectsRunnable = new Runnable() {
        @Override
        public void run() {
            if (!liveObjectPollerRunning || bridge.isClosed()) return;
            if (!bridge.isOverlayVisible()) {
                liveObjectsHandler.postDelayed(this, 2500);
                return;
            }
            if (!bridge.isLiveStreamMode()) return;
            pollInProgressEventsOnce();
            long nextDelayMs = (liveWsClient != null && liveWsClient.isConnected()) ? 15000L : 2500L;
            liveObjectsHandler.postDelayed(this, nextDelayMs);
        }
    };

    private void pollInProgressEventsOnce() {
        String cam = bridge.getCurrentCamera();
        if (bridge.isLivePaused() || bridge.isRulerScrubbing() || liveObjectPollInFlight || cam == null) return;
        liveObjectPollInFlight = true;
        apiClient.fetchInProgressEvents(cam, new FrigateApiClient.Callback<List<FrigateEvent>>() {
            @Override
            public void onSuccess(List<FrigateEvent> result) {
                liveObjectPollInFlight = false;
                liveObjectsHandler.post(() -> {
                    if (bridge.isClosed() || !bridge.isLiveStreamMode() || bridge.isRulerScrubbing()) return;
                    reconcileInProgressEvents(result);
                });
            }

            @Override
            public void onError(Exception error) {
                liveObjectPollInFlight = false;
            }
        });
    }

    private void reconcileInProgressEvents(List<FrigateEvent> result) {
        liveDetectionsById.clear();
        if (result != null) {
            for (FrigateEvent ev : result) {
                if (ev == null || ev.getLabel() == null || ev.getLabel().trim().isEmpty()) continue;
                String id = ev.getId();
                if (id == null || id.isEmpty()) {
                    id = ev.getLabel() + "_" + ev.getStartTime();
                }
                ActiveLiveDetection det = new ActiveLiveDetection();
                det.id = id;
                det.label = ev.getLabel().trim().toLowerCase(Locale.ROOT);
                det.score = ev.getTopScore();
                String sev = ev.getSeverity();
                if (sev == null || sev.isEmpty()) {
                    sev = eventSeverityFromReview.get(id);
                }
                det.severity = (sev != null && !sev.isEmpty()) ? sev : "detection";
                liveDetectionsById.put(id, det);
            }
        }
        if (liveDetectionsById.isEmpty()) {
            activeReviewSeverityById.clear();
        }
        renderLiveActivityOverlay();
    }

    private void clearLiveActivityOverlay() {
        lastLiveBadgeKey = "";
        lastLiveBorderKey = "";
        View highlightView = bridge.getLiveHighlightView();
        if (highlightView != null && highlightView.getVisibility() != View.GONE) {
            highlightView.setVisibility(View.GONE);
        }
        LinearLayout badgeRow = bridge.getLiveOverlayBadgeRow();
        if (badgeRow != null) {
            if (badgeRow.getVisibility() != View.GONE) badgeRow.setVisibility(View.GONE);
            badgeRow.removeAllViews();
        }
    }

    private void renderLiveActivityOverlay() {
        if (bridge.isClosed() || !bridge.isLiveStreamMode() || bridge.isRulerScrubbing() || bridge.isLivePaused()) {
            clearLiveActivityOverlay();
            return;
        }
        if (liveDetectionsById.isEmpty()) {
            clearLiveActivityOverlay();
            return;
        }

        boolean hasAlert = false;
        for (String sev : activeReviewSeverityById.values()) {
            if ("alert".equalsIgnoreCase(sev)) {
                hasAlert = true;
                break;
            }
        }
        if (!hasAlert) {
            for (ActiveLiveDetection det : liveDetectionsById.values()) {
                if ("alert".equalsIgnoreCase(det.severity)) {
                    hasAlert = true;
                    break;
                }
            }
        }

        View highlightView = bridge.getLiveHighlightView();
        boolean highlightEnabled = config == null || config.isHighlightOngoingEvents();
        if (highlightView != null) {
            if (!highlightEnabled) {
                if (highlightView.getVisibility() != View.GONE) {
                    highlightView.setVisibility(View.GONE);
                }
                lastLiveBorderKey = "";
            } else {
                String borderKey = hasAlert ? "alert" : "detection";
                if (!borderKey.equals(lastLiveBorderKey) || highlightView.getVisibility() != View.VISIBLE) {
                    lastLiveBorderKey = borderKey;
                    int borderAccent = hasAlert ? Color.parseColor("#F43F5E") : Color.parseColor("#F59E0B");
                    int strokePx = s(3);
                    GradientDrawable hlBg = new GradientDrawable();
                    hlBg.setColor(Color.argb(35, Color.red(borderAccent), Color.green(borderAccent), Color.blue(borderAccent)));
                    hlBg.setCornerRadius(s(12));
                    hlBg.setStroke(strokePx, borderAccent);
                    highlightView.setBackground(hlBg);
                    highlightView.setVisibility(View.VISIBLE);
                }
            }
        }

        Map<String, String> severityByLabel = new LinkedHashMap<>();
        for (ActiveLiveDetection det : liveDetectionsById.values()) {
            mergeOverlayLabel(severityByLabel, det.label, det.severity);
        }
        renderOverlayPills(severityByLabel);
    }

    private static void mergeOverlayLabel(Map<String, String> target, String rawLabel, String rawSeverity) {
        if (rawLabel == null) return;
        String key = rawLabel.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty() || "motion".equals(key)) return;
        String sev = (rawSeverity != null && !rawSeverity.isEmpty()) ? rawSeverity : "detection";
        String existing = target.get(key);
        if (existing == null || ("alert".equalsIgnoreCase(sev) && !"alert".equalsIgnoreCase(existing))) {
            target.put(key, sev);
        }
    }

    private void renderOverlayPills(Map<String, String> severityByLabel) {
        this.lastOverlaySeverityByLabel = severityByLabel != null ? severityByLabel : Collections.emptyMap();
        LinearLayout badgeRow = bridge.getLiveOverlayBadgeRow();
        if (badgeRow == null) return;

        if (severityByLabel == null || severityByLabel.isEmpty()) {
            badgeRow.removeAllViews();
            badgeRow.setVisibility(View.GONE);
            lastLiveBadgeKey = "";
            return;
        }

        List<Map.Entry<String, String>> sortedEntries = new ArrayList<>(severityByLabel.entrySet());
        Collections.sort(sortedEntries, (a, b) -> {
            boolean aAlert = "alert".equalsIgnoreCase(a.getValue());
            boolean bAlert = "alert".equalsIgnoreCase(b.getValue());
            if (aAlert != bAlert) return aAlert ? -1 : 1;
            return 0;
        });

        StringBuilder keyBuilder = new StringBuilder();
        int cap = 0;
        for (Map.Entry<String, String> item : sortedEntries) {
            if (cap++ >= 3) break;
            keyBuilder.append(item.getKey()).append(':').append(item.getValue()).append('|');
        }
        String newKey = keyBuilder.toString();
        if (newKey.equals(lastLiveBadgeKey) && badgeRow.getVisibility() == View.VISIBLE) {
            return;
        }
        lastLiveBadgeKey = newKey;

        badgeRow.removeAllViews();
        int shown = 0;
        for (Map.Entry<String, String> item : sortedEntries) {
            if (shown >= 3) break;
            String label = item.getKey();
            boolean isAlert = "alert".equalsIgnoreCase(item.getValue());
            int pillAccent = isAlert ? Color.parseColor("#F43F5E") : Color.parseColor("#F59E0B");

            FrameLayout pill = new FrameLayout(context);
            pill.setPadding(s(8), s(6), s(8), s(6));

            GradientDrawable pillBg = new GradientDrawable();
            int bgTint = Color.argb(230,
                    (int) (15 * 0.65 + Color.red(pillAccent) * 0.35),
                    (int) (23 * 0.65 + Color.green(pillAccent) * 0.35),
                    (int) (42 * 0.65 + Color.blue(pillAccent) * 0.35));
            pillBg.setColor(bgTint);
            pillBg.setCornerRadius(s(FrigateTheme.radiusControl(theme)));
            pillBg.setStroke(s(1.5f), pillAccent);
            pill.setBackground(pillBg);

            String iconName = FrigateEvent.getMaterialIconForLabel(label);
            ImageView iconView = new ImageView(context);
            iconView.setImageDrawable(MaterialIcons.getDrawable(iconName, pillAccent, s(18), theme));
            iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            pill.addView(iconView, new FrameLayout.LayoutParams(s(18), s(18), Gravity.CENTER));

            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (shown > 0) {
                pLp.leftMargin = s(6);
            }
            badgeRow.addView(pill, pLp);
            shown++;
        }
        badgeRow.setVisibility(View.VISIBLE);
    }

    public void updateObjectBadgesForEpoch(long epochSeconds) {
        Map<String, String> severityByLabel = new LinkedHashMap<>();
        double t = epochSeconds + (config != null ? config.getBboxTimeOffsetSec() : 0.0);

        String overlapReviewSev = null;
        List<ReviewSegment> revList = bridge.getTimelineReviews();
        if (revList != null) {
            for (ReviewSegment rev : revList) {
                if (rev == null) continue;
                if (t >= rev.startTime - 1.0 && t <= rev.endTime + 1.0) {
                    if ("alert".equalsIgnoreCase(rev.severity)) {
                        overlapReviewSev = "alert";
                    } else if (overlapReviewSev == null) {
                        overlapReviewSev = rev.severity;
                    }
                }
            }
        }

        List<FrigateEvent> evList = bridge.getTimelineEvents();
        if (evList != null) {
            for (FrigateEvent ev : evList) {
                if (ev == null || ev.getLabel() == null || ev.getLabel().isEmpty()) continue;
                double end = Math.max(ev.getStartTime(), ev.getEndTime());
                if (t >= ev.getStartTime() - 1.0 && t <= end + 1.0) {
                    String sev = (ev.getSeverity() != null && !ev.getSeverity().isEmpty())
                            ? ev.getSeverity()
                            : overlapReviewSev;
                    mergeOverlayLabel(severityByLabel, ev.getLabel(), sev);
                }
            }
        }
        FrigateEvent primaryEvent = bridge.getPrimaryEvent();
        if (!bridge.isTimelineMode() && !bridge.isRulerScrubbing() && primaryEvent != null
                && primaryEvent.getLabel() != null && !primaryEvent.getLabel().isEmpty()) {
            double evEnd = Math.max(primaryEvent.getStartTime(), primaryEvent.getEndTime());
            if (primaryEvent.getStartTime() <= 0 || (t >= primaryEvent.getStartTime() - 1.0 && t <= evEnd + 1.0)) {
                String sev = (primaryEvent.getSeverity() != null && !primaryEvent.getSeverity().isEmpty())
                        ? primaryEvent.getSeverity()
                        : overlapReviewSev;
                mergeOverlayLabel(severityByLabel, primaryEvent.getLabel(), sev);
            }
        }
        if (severityByLabel.isEmpty() && revList != null) {
            for (ReviewSegment rev : revList) {
                if (rev == null || rev.objects.isEmpty()) continue;
                if (t >= rev.startTime - 1.0 && t <= rev.endTime + 1.0) {
                    for (String lbl : rev.objects) {
                        mergeOverlayLabel(severityByLabel, lbl, rev.severity);
                    }
                }
            }
        }

        LinearLayout headerContainer = bridge.getHeaderObjectsContainer();
        if (headerContainer != null && headerContainer.getVisibility() != View.GONE) {
            headerContainer.removeAllViews();
            headerContainer.setVisibility(View.GONE);
            lastObjectBadgesKey = "";
        }
        renderOverlayPills(severityByLabel);
    }

    public void updateObjectBadgesFromEvents(List<FrigateEvent> events) {
        Map<String, Double> active = new LinkedHashMap<>();
        if (events != null) {
            for (FrigateEvent ev : events) {
                if (ev == null || ev.getLabel() == null || ev.getLabel().isEmpty()) continue;
                mergeDetectedLabel(active, ev.getLabel(), ev.getTopScore());
            }
        }
        renderObjectBadges(active);
    }

    private static void mergeDetectedLabel(Map<String, Double> target, String rawLabel, double score) {
        if (rawLabel == null) return;
        String key = rawLabel.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty() || "motion".equals(key)) return;
        Double existing = target.get(key);
        if (existing == null || score > existing) {
            target.put(key, score);
        }
    }

    private void renderObjectBadges(Map<String, Double> activeLabels) {
        this.lastActiveLabels = activeLabels;
        LinearLayout container = bridge.getHeaderObjectsContainer();
        if (container == null) return;
        StringBuilder keyBuilder = new StringBuilder();
        int cap = 0;
        for (Map.Entry<String, Double> e : activeLabels.entrySet()) {
            if (cap++ >= 3) break;
            int pct = (int) Math.round(e.getValue() * 100.0);
            keyBuilder.append(e.getKey()).append(':').append(pct).append('|');
        }
        String newKey = keyBuilder.toString();
        if (newKey.equals(lastObjectBadgesKey)) return;
        lastObjectBadgesKey = newKey;

        container.removeAllViews();
        if (activeLabels.isEmpty()) {
            container.setVisibility(View.GONE);
            return;
        }

        boolean dark = FrigateTheme.isDark(theme);
        int textColor = dark ? Color.parseColor("#E9D5FF") : Color.parseColor("#6D28D9");
        int bgColor = dark ? Color.parseColor("#1E1633") : Color.parseColor("#F5F3FF");
        int strokeColor = dark ? Color.parseColor("#7C3AED") : Color.parseColor("#DDD6FE");

        int shown = 0;
        for (Map.Entry<String, Double> e : activeLabels.entrySet()) {
            if (shown >= 3) break;
            String raw = e.getKey();
            String text = FrigateI18n.translateObjectLabel(context, config, raw);
            int pct = (int) Math.round(e.getValue() * 100.0);
            if (pct > 0) {
                text += " (" + pct + "%)";
            }
            TextView pill = new TextView(context);
            pill.setText(text);
            pill.setTextSize(sp(11));
            pill.setTypeface(FrigateTheme.typeface(theme, 600));
            pill.setSingleLine(true);
            pill.setIncludeFontPadding(false);
            pill.setGravity(Gravity.CENTER);
            pill.setTextColor(textColor);

            MaterialIcons.setIconOnTextView(pill, FrigateEvent.getMaterialIconForLabel(raw),
                    textColor, s(13), s(5), theme);

            GradientDrawable lpBg = new GradientDrawable();
            lpBg.setColor(bgColor);
            lpBg.setCornerRadius(s(FrigateTheme.radiusControl(theme)));
            lpBg.setStroke(s(1), strokeColor);
            pill.setBackground(lpBg);
            pill.setPadding(s(10), s(6), s(10), s(6));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_VERTICAL;
            if (shown > 0) lp.leftMargin = s(6);
            container.addView(pill, lp);
            shown++;
        }
        container.setVisibility(View.VISIBLE);
    }
}
