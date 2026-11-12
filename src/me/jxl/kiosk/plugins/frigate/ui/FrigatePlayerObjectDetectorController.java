// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.frigate.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.frigate.api.FrigateApiClient;
import me.jxl.kiosk.plugins.frigate.config.FrigateConfig;
import me.jxl.kiosk.plugins.frigate.model.FrigateEvent;
import me.jxl.kiosk.plugins.frigate.model.ReviewSegment;
import me.jxl.kiosk.plugins.frigate.model.TimelineBoxEntry;

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
    }

    private final Context context;
    private final KsTheme theme;
    private final FrigateApiClient apiClient;
    private final FrigateConfig config;
    private final Handler mainHandler;
    private final HostBridge bridge;

    private final Map<String, List<TimelineBoxEntry>> eventTimelineBoxCache = new LinkedHashMap<>();
    private final Set<String> pendingTimelineBoxFetches = new HashSet<>();
    private final Handler liveObjectsHandler = new Handler(Looper.getMainLooper());
    private boolean liveObjectPollerRunning = false;
    private volatile boolean liveObjectPollInFlight = false;
    private String lastObjectBadgesKey = "";

    public FrigatePlayerObjectDetectorController(Context context,
                                                 KsTheme theme,
                                                 FrigateApiClient apiClient,
                                                 FrigateConfig config,
                                                 Handler mainHandler,
                                                 HostBridge bridge) {
        this.context = context;
        this.theme = theme;
        this.apiClient = apiClient;
        this.config = config;
        this.mainHandler = mainHandler;
        this.bridge = bridge;
    }

    private int s(float dp) {
        float scale = (config != null) ? config.getUiScale() : 1.35f;
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density * scale);
    }

    private float sp(float sp) {
        float scale = (config != null) ? config.getUiScale() : 1.35f;
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
        liveObjectsHandler.post(liveObjectsRunnable);
    }

    public void stopLiveObjectPoller() {
        liveObjectPollerRunning = false;
        liveObjectsHandler.removeCallbacksAndMessages(null);
    }

    private final Runnable liveObjectsRunnable = new Runnable() {
        @Override
        public void run() {
            if (!liveObjectPollerRunning || bridge.isClosed() || !bridge.isLiveStreamMode()) return;
            String cam = bridge.getCurrentCamera();
            if (!bridge.isLivePaused() && !bridge.isRulerScrubbing() && !liveObjectPollInFlight && cam != null) {
                liveObjectPollInFlight = true;
                apiClient.fetchInProgressEvents(cam, new FrigateApiClient.Callback<List<FrigateEvent>>() {
                    @Override
                    public void onSuccess(List<FrigateEvent> result) {
                        liveObjectPollInFlight = false;
                        liveObjectsHandler.post(() -> {
                            if (bridge.isClosed() || !bridge.isLiveStreamMode() || bridge.isRulerScrubbing()) return;
                            updateObjectBadgesFromEvents(result);
                        });
                    }

                    @Override
                    public void onError(Exception error) {
                        liveObjectPollInFlight = false;
                    }
                });
            }
            liveObjectsHandler.postDelayed(this, 2500);
        }
    };

    public void updateObjectBadgesForEpoch(long epochSeconds) {
        Map<String, Double> active = new LinkedHashMap<>();
        double t = epochSeconds + (config != null ? config.getBboxTimeOffsetSec() : 0.0);
        List<FrigateEvent> evList = bridge.getTimelineEvents();
        if (evList != null) {
            for (FrigateEvent ev : evList) {
                if (ev == null || ev.getLabel() == null || ev.getLabel().isEmpty()) continue;
                double end = Math.max(ev.getStartTime(), ev.getEndTime());
                if (t >= ev.getStartTime() - 1.0 && t <= end + 1.0) {
                    mergeDetectedLabel(active, ev.getLabel(), ev.getTopScore());
                }
            }
        }
        FrigateEvent primaryEvent = bridge.getPrimaryEvent();
        if (!bridge.isTimelineMode() && !bridge.isRulerScrubbing() && primaryEvent != null
                && primaryEvent.getLabel() != null && !primaryEvent.getLabel().isEmpty()) {
            double evEnd = Math.max(primaryEvent.getStartTime(), primaryEvent.getEndTime());
            if (primaryEvent.getStartTime() <= 0 || (t >= primaryEvent.getStartTime() - 1.0 && t <= evEnd + 1.0)) {
                mergeDetectedLabel(active, primaryEvent.getLabel(), primaryEvent.getTopScore());
            }
        }
        if (active.isEmpty()) {
            List<ReviewSegment> revList = bridge.getTimelineReviews();
            if (revList != null) {
                for (ReviewSegment rev : revList) {
                    if (rev == null || rev.objects.isEmpty()) continue;
                    if (t >= rev.startTime - 1.0 && t <= rev.endTime + 1.0) {
                        for (String lbl : rev.objects) {
                            mergeDetectedLabel(active, lbl, 0.0);
                        }
                    }
                }
            }
        }
        renderObjectBadges(active);
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
            MaterialIcons.setIconOnTextView(pill, FrigateEvent.getMaterialIconForLabel(raw),
                    Color.parseColor("#E9D5FF"), s(18), s(8), theme);
            GradientDrawable lpBg = new GradientDrawable();
            lpBg.setColor(Color.parseColor("#231B2B"));
            lpBg.setCornerRadius(s(10));
            lpBg.setStroke(s(1), Color.parseColor("#6B21A8"));
            pill.setBackground(lpBg);
            pill.setPadding(s(14), s(8), s(14), s(8));
            pill.setTextSize(sp(14));
            pill.setTextColor(Color.parseColor("#E9D5FF"));
            pill.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (shown > 0) lp.leftMargin = s(6);
            container.addView(pill, lp);
            shown++;
        }
        container.setVisibility(View.VISIBLE);
    }
}
