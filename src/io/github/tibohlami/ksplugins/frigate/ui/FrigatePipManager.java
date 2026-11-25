// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import io.github.tibohlami.ksplugins.frigate.api.FrigateApiClient;
import io.github.tibohlami.ksplugins.frigate.api.FrigateEventsWsClient;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Coordinates the dashboard/screensaver Picture-in-Picture activity overlay (#15)
 * using the shared Frigate {@code /ws} connection and Kiosk Satellite {@link OverlaySpec}.
 */
public class FrigatePipManager {

    public interface HostBridge {
        void openCameraLive(String cameraName);
    }

    private static final String OVERLAY_PIP = "frigate_event_pip";

    private static final class ActiveEvent {
        String id;
        String camera;
        String label;
        String severity;
    }

    private static final class ActiveReview {
        String id;
        String camera;
        String severity;
        Set<String> objects = new LinkedHashSet<>();
    }

    private static final class CameraSummary {
        final String camera;
        boolean hasAlert = false;
        final Set<String> labels = new LinkedHashSet<>();
        final Set<String> activeIds = new LinkedHashSet<>();

        CameraSummary(String camera) {
            this.camera = camera;
        }
    }

    private final PluginHost host;
    private final FrigateConfig config;
    private final FrigateApiClient apiClient;
    private final HostBridge bridge;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Map<String, ActiveEvent> activeEventsById = new LinkedHashMap<String, ActiveEvent>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ActiveEvent> eldest) {
            return size() > 100;
        }
    };
    private final Map<String, ActiveReview> activeReviewsById = new LinkedHashMap<String, ActiveReview>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ActiveReview> eldest) {
            return size() > 100;
        }
    };
    private final Set<String> dismissedIds = new LinkedHashSet<>();

    private FrigateEventsWsClient.Listener wsListener;
    private volatile boolean started = false;
    private boolean suppressed = false;
    private String currentPipCamera = null;
    private String lastOverlaySignature = "";
    private FrigatePipOverlayView activePipView = null;
    private long pipGeneration = 0L;

    private final Runnable autoHideRunnable = this::dismissCurrentPip;

    public FrigatePipManager(PluginHost host,
                             FrigateConfig config,
                             FrigateApiClient apiClient,
                             HostBridge bridge) {
        this.host = host;
        this.config = config;
        this.apiClient = apiClient;
        this.bridge = bridge;
    }

    public void onConfigurationChanged() {
        mainHandler.post(() -> {
            if (host == null || config == null || host.isHeadless()) {
                stopOnMainThread();
                return;
            }
            String mode = config.getPipDisplayMode();
            if ("none".equals(mode)) {
                stopOnMainThread();
                return;
            }
            if (!started) {
                startOnMainThread();
            } else {
                lastOverlaySignature = "";
                evaluateState();
            }
        });
    }

    public void setSuppressed(final boolean suppressed) {
        mainHandler.post(() -> {
            if (this.suppressed == suppressed) return;
            this.suppressed = suppressed;
            if (suppressed) {
                hideOverlay();
            } else if (started) {
                evaluateState();
            }
        });
    }

    public void start() {
        mainHandler.post(this::startOnMainThread);
    }

    private void startOnMainThread() {
        if (started || host == null || host.isHeadless()) return;
        if ("none".equals(config.getPipDisplayMode())) return;
        started = true;
        logDebug("Frigate: Activity overlay listener started (mode=" + config.getPipDisplayMode() + ")");
        wsListener = new FrigateEventsWsClient.Listener() {
            @Override
            public void onWsConnected() {}

            @Override
            public void onWsDisconnected() {
                if (!started) return;
                activeEventsById.clear();
                activeReviewsById.clear();
                hideOverlay();
            }

            @Override
            public void onFrigateEventMessage(String type, JSONObject afterObj, JSONObject beforeObj) {
                if (!started) return;
                handleEventMessage(type, afterObj != null ? afterObj : beforeObj);
            }

            @Override
            public void onFrigateReviewMessage(String type, JSONObject afterObj, JSONObject beforeObj) {
                if (!started) return;
                handleReviewMessage(type, afterObj != null ? afterObj : beforeObj);
            }
        };
        FrigateEventsWsClient.acquireShared(config, host, wsListener);
    }

    public void stop() {
        started = false;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopOnMainThread();
        } else {
            mainHandler.post(this::stopOnMainThread);
        }
    }

    private void stopOnMainThread() {
        started = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (wsListener != null) {
            FrigateEventsWsClient.releaseShared(wsListener);
            wsListener = null;
        }
        activeEventsById.clear();
        activeReviewsById.clear();
        dismissedIds.clear();
        hideOverlay();
    }

    private void handleEventMessage(String type, JSONObject obj) {
        if (obj == null) return;
        String id = obj.optString("id", "");
        String cam = obj.optString("camera", "");
        if (id.isEmpty() || cam.isEmpty()) return;

        boolean ended = "end".equalsIgnoreCase(type)
                || obj.optBoolean("false_positive", false)
                || obj.optBoolean("stationary", false)
                || (!obj.isNull("end_time") && obj.optDouble("end_time", 0) > 0);
        if (ended) {
            dismissedIds.remove(id);
            if (activeEventsById.remove(id) != null) {
                evaluateState();
            }
            return;
        }

        String label = obj.optString("label", "").trim().toLowerCase(Locale.ROOT);
        if (label.isEmpty()) return;

        boolean isNewForCurrent = cam.equals(currentPipCamera) && !activeEventsById.containsKey(id);
        ActiveEvent ev = activeEventsById.get(id);
        if (ev == null) {
            ev = new ActiveEvent();
            ev.id = id;
            activeEventsById.put(id, ev);
        }
        ev.camera = cam;
        ev.label = label;
        ev.severity = obj.optString("severity", "detection");

        if (isNewForCurrent) {
            resetAutoHideTimer();
        }
        evaluateState();
    }

    private void handleReviewMessage(String type, JSONObject obj) {
        if (obj == null) return;
        String id = obj.optString("id", "");
        String cam = obj.optString("camera", "");
        if (id.isEmpty() || cam.isEmpty()) return;

        boolean ended = "end".equalsIgnoreCase(type)
                || (!obj.isNull("end_time") && obj.optDouble("end_time", 0) > 0);
        if (ended) {
            dismissedIds.remove(id);
            if (activeReviewsById.remove(id) != null) {
                evaluateState();
            }
            return;
        }

        ActiveReview rev = activeReviewsById.get(id);
        if (rev == null) {
            rev = new ActiveReview();
            rev.id = id;
            activeReviewsById.put(id, rev);
        }
        rev.camera = cam;
        rev.severity = obj.optString("severity", "detection");
        rev.objects.clear();
        JSONObject data = obj.optJSONObject("data");
        if (data != null) {
            JSONArray objs = data.optJSONArray("objects");
            if (objs != null) {
                for (int i = 0; i < objs.length(); i++) {
                    String lbl = objs.optString(i, "").trim().toLowerCase(Locale.ROOT);
                    if (!lbl.isEmpty()) rev.objects.add(lbl);
                }
            }
        }
        evaluateState();
    }

    private void evaluateState() {
        if (!started || suppressed || "none".equals(config.getPipDisplayMode())) {
            hideOverlay();
            return;
        }

        Map<String, CameraSummary> matching = buildMatchingSummaries();
        if (matching.isEmpty()) {
            hideOverlay();
            return;
        }

        CameraSummary chosen = (currentPipCamera != null) ? matching.get(currentPipCamera) : null;
        if (chosen == null) {
            chosen = matching.values().iterator().next();
        }
        int extraCameras = Math.max(0, matching.size() - 1);

        String specSig = config.getPipDisplayMode() + "|" + config.getPipPosition() + "|"
                + config.getPipSize() + "|" + config.isPipEnableAudio();

        if (chosen.camera.equals(currentPipCamera)
                && activePipView != null
                && specSig.equals(lastOverlaySignature)) {
            activePipView.updateActivity(chosen.hasAlert, chosen.labels, extraCameras);
        } else {
            showOverlayForSummary(chosen, extraCameras, specSig);
        }
    }

    private Map<String, CameraSummary> buildMatchingSummaries() {
        Set<String> camFilter = config.getPipCameras();
        boolean alertsOnly = "alerts".equals(config.getPipSeverity());
        Set<String> objFilter = config.getPipObjects();

        Set<String> alertCameras = new HashSet<>();
        for (ActiveReview rev : activeReviewsById.values()) {
            if (!dismissedIds.contains(rev.id) && "alert".equalsIgnoreCase(rev.severity)) {
                alertCameras.add(rev.camera);
            }
        }

        Map<String, CameraSummary> summaries = new LinkedHashMap<>();
        for (ActiveEvent ev : activeEventsById.values()) {
            if (dismissedIds.contains(ev.id)) continue;
            if (!camFilter.isEmpty() && !camFilter.contains(ev.camera)) continue;
            boolean isAlert = "alert".equalsIgnoreCase(ev.severity) || alertCameras.contains(ev.camera);
            if (alertsOnly && !isAlert) continue;
            if (!objFilter.isEmpty() && !objFilter.contains(ev.label)) continue;

            CameraSummary sum = summaries.get(ev.camera);
            if (sum == null) {
                sum = new CameraSummary(ev.camera);
                summaries.put(ev.camera, sum);
            }
            if (isAlert) sum.hasAlert = true;
            sum.labels.add(ev.label);
            sum.activeIds.add(ev.id);
        }

        return summaries;
    }

    private void showOverlayForSummary(CameraSummary summary, int extraCameras, String specSig) {
        if (host == null) return;
        currentPipCamera = summary.camera;
        lastOverlaySignature = specSig;
        final long gen = ++pipGeneration;

        float pct = 0.35f;
        String size = config.getPipSize();
        if ("small".equals(size)) {
            pct = 0.25f;
        } else if ("large".equals(size)) {
            pct = 0.45f;
        }
        android.util.DisplayMetrics dm = android.content.res.Resources.getSystem().getDisplayMetrics();
        float density = (dm != null && dm.density > 0f) ? dm.density : 1.0f;
        float screenWDp = (dm != null && dm.widthPixels > 0) ? (dm.widthPixels / density) : 1024f;
        float screenHDp = (dm != null && dm.heightPixels > 0) ? (dm.heightPixels / density) : 600f;
        int wDp = Math.round(screenWDp * pct);
        wDp = Math.max(220, Math.min(800, wDp));
        int hDp = Math.round(wDp * 9f / 16f);
        int maxHDp = Math.max(140, Math.round(screenHDp * 0.75f));
        if (hDp > maxHDp) {
            hDp = maxHDp;
            wDp = Math.round(hDp * 16f / 9f);
        }

        String layer = "both".equals(config.getPipDisplayMode())
                ? OverlaySpec.LAYER_TOP
                : OverlaySpec.LAYER_DASHBOARD;

        OverlaySpec spec = OverlaySpec.at(config.getPipPosition())
                .size(wDp, hDp)
                .inset(16)
                .layer(layer)
                .enter(OverlaySpec.SLIDE)
                .exit(OverlaySpec.SLIDE)
                .closeOnOutsideTouch(false)
                .closeOnBack(false);

        final boolean initialAlert = summary.hasAlert;
        final Set<String> initialLabels = new LinkedHashSet<>(summary.labels);
        final int initialExtra = extraCameras;
        final String targetCamera = summary.camera;

        logDebug("Frigate: Activity overlay show cam=" + targetCamera
                + " size=" + wDp + "x" + hDp + "dp layer=" + layer
                + " alert=" + initialAlert + " extra=" + initialExtra);
        host.showOverlay(OVERLAY_PIP, spec, new OverlayFactory() {
            @Override
            public View create(Context context, KsTheme theme) {
                FrigatePipOverlayView view = new FrigatePipOverlayView(
                        context, theme, host, apiClient, config, targetCamera,
                        new FrigatePipOverlayView.Listener() {
                            @Override
                            public void onOpenCamera(String cameraName) {
                                suppressed = true;
                                hideOverlay();
                                if (bridge != null) {
                                    bridge.openCameraLive(cameraName);
                                }
                            }

                            @Override
                            public void onDismiss() {
                                dismissCurrentPip();
                            }
                        });
                if (gen == pipGeneration) {
                    activePipView = view;
                }
                View root = view.buildView(initialAlert, initialLabels, initialExtra);
                root.setTag(view);
                return root;
            }

            @Override
            public void onThemeChanged(View view, KsTheme theme) {
                Object tag = view != null ? view.getTag() : null;
                if (tag instanceof FrigatePipOverlayView) {
                    ((FrigatePipOverlayView) tag).onThemeChanged(theme);
                }
            }

            @Override
            public void onDestroy(View view) {
                Object tag = view != null ? view.getTag() : null;
                if (tag instanceof FrigatePipOverlayView) {
                    FrigatePipOverlayView dead = (FrigatePipOverlayView) tag;
                    if (activePipView == dead) {
                        activePipView = null;
                    }
                    dead.destroy();
                }
            }
        });

        resetAutoHideTimer();
    }

    private void resetAutoHideTimer() {
        mainHandler.removeCallbacks(autoHideRunnable);
        int timeoutSec = config.getPipAutoHideSec();
        if (timeoutSec > 0 && started && !suppressed) {
            mainHandler.postDelayed(autoHideRunnable, timeoutSec * 1000L);
        }
    }

    private void dismissCurrentPip() {
        if (currentPipCamera != null) {
            logDebug("Frigate: Activity overlay dismissed for cam=" + currentPipCamera);
            for (ActiveEvent ev : activeEventsById.values()) {
                if (currentPipCamera.equals(ev.camera)) dismissedIds.add(ev.id);
            }
            for (ActiveReview rev : activeReviewsById.values()) {
                if (currentPipCamera.equals(rev.camera)) dismissedIds.add(rev.id);
            }
            while (dismissedIds.size() > 200) {
                String first = dismissedIds.iterator().next();
                dismissedIds.remove(first);
            }
        }
        currentPipCamera = null;
        evaluateState();
    }

    private void hideOverlay() {
        if (currentPipCamera != null || activePipView != null) {
            logDebug("Frigate: Activity overlay hidden");
        }
        mainHandler.removeCallbacks(autoHideRunnable);
        currentPipCamera = null;
        lastOverlaySignature = "";
        pipGeneration++;
        if (host != null) {
            try {
                host.hideOverlay(OVERLAY_PIP);
            } catch (Throwable ignored) {}
        }
        if (activePipView != null) {
            activePipView.destroy();
            activePipView = null;
        }
    }

    private void logDebug(String msg) {
        if (config != null && config.isDebugLogging()) {
            if (host != null) host.log(msg);
            android.util.Log.i("FrigatePlugin", msg);
        }
    }
}
