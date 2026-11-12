// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.frigate;

import me.jxl.kiosk.plugins.frigate.ui.FrigateI18n;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;
import me.jxl.kiosk.plugins.frigate.api.FrigateApiClient;
import me.jxl.kiosk.plugins.frigate.api.FrigateUrlAutoDetector;
import me.jxl.kiosk.plugins.frigate.config.FrigateConfig;
import me.jxl.kiosk.plugins.frigate.model.ConnectionTestResult;
import me.jxl.kiosk.plugins.frigate.model.FrigateCamera;
import me.jxl.kiosk.plugins.frigate.model.FrigateEvent;
import me.jxl.kiosk.plugins.frigate.ui.AsyncImageLoader;
import me.jxl.kiosk.plugins.frigate.ui.FrigateExplorerOverlay;
import me.jxl.kiosk.plugins.frigate.ui.FrigatePlayerOverlay;
import me.jxl.kiosk.plugins.frigate.ui.MaterialIcons;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Main entry point for the Frigate NVR Kiosk Satellite plugin.
 */
public final class FrigatePlugin implements KioskPlugin {

    public static final String OVERLAY_EXPLORER = "frigate_explorer";
    public static final String OVERLAY_PLAYER = "frigate_player";
    private static final String ENTITY_CAMERA_SELECT = "active_camera";
    private static final String ENTITY_CAMERA_SELECT_NAME = "Active Camera";
    private static final String ENTITY_CAMERAS_SUMMARY = "cameras";
    private static final String ENTITY_CAMERAS_SUMMARY_NAME = "Cameras";
    private static final String DASHBOARD_OPEN_CAMERA_PREFIX = "ks://plugin/kiosk-satellite-frigate/openCamera/";
    private static final String SELECT_OPTION_CLOSED = "Closed";
    private static final int MAX_SELECT_OPTIONS = 32;
    private static final int MAX_CAMERA_LINK_SENSORS = 29; // Reserve slots for frigate_status, active_camera, cameras
    private static final int MAX_ENTITY_KEY_LENGTH = 40;
    private static final int MAX_ENTITY_NAME_LENGTH = 80;
    private static final int MAX_TEXT_SENSOR_STATE_LENGTH = 512;

    private PluginHost host;
    private FrigateConfig config;
    private FrigateApiClient apiClient;
    private AsyncImageLoader imageLoader;
    private boolean isExplorerOpen = false;
    private boolean isPlayerOpen = false;
    private long explorerOverlayGeneration = 0L;
    private long playerOverlayGeneration = 0L;
    private FrigateEvent lastPlayerEvent = null;
    private String[] cameraSelectOptions = null;
    private String activeSelectCamera = SELECT_OPTION_CLOSED;
    private final Map<String, String> publishedCameraSensors = new LinkedHashMap<>();
    private final Set<String> canceledSetupRuns = Collections.synchronizedSet(new HashSet<String>());

    public FrigatePlugin() {}

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) throws Exception {
        this.host = host;
        FrigateI18n.init(host);
        this.config = new FrigateConfig(host);
        configure(settings);
        this.imageLoader = new AsyncImageLoader();
        this.imageLoader.setConfig(config);
        this.imageLoader.setAuthToken(config.getAuthToken());
        this.apiClient = new FrigateApiClient(config);
        this.apiClient.ensureAuthenticatedAsync(() -> refreshCameraSelectAsync());

        try {
            host.status(FrigateI18n.tr(null, config, "plugin_status_connected_url", config.getFrigateUrl()), false);
            host.publishTextSensor("frigate_status", "Frigate Status", FrigateI18n.tr(null, config, "plugin_sensor_connected"));
            host.publishTextSensor(ENTITY_CAMERAS_SUMMARY, ENTITY_CAMERAS_SUMMARY_NAME, FrigateI18n.tr(null, config, "plugin_sensor_loading"));
        } catch (Exception ignored) {}

        maybeAutoDetectFrigateUrl();
    }

    private void maybeAutoDetectFrigateUrl() {
        final PluginHost activeHost = this.host;
        final FrigateConfig activeConfig = this.config;
        if (activeHost == null || activeConfig == null || !activeConfig.isDefaultOrEmptyFrigateUrl()) {
            return;
        }
        FrigateUrlAutoDetector.detectAsync(activeHost, activeConfig, new FrigateUrlAutoDetector.Callback() {
            @Override
            public void onDetected(String detectedUrl) {
                onFrigateUrlAutoDetected(activeHost, activeConfig, detectedUrl);
            }
        });
    }

    private synchronized void onFrigateUrlAutoDetected(PluginHost activeHost,
                                                       FrigateConfig activeConfig,
                                                       String detectedUrl) {
        if (this.host != activeHost || this.config != activeConfig || this.host == null || this.config == null) {
            return;
        }
        if (detectedUrl == null || detectedUrl.trim().isEmpty() || !this.config.isDefaultOrEmptyFrigateUrl()) {
            return;
        }
        String cleanUrl = detectedUrl.trim();
        if (cleanUrl.equalsIgnoreCase(this.config.getFrigateUrl())) {
            return;
        }
        Map<String, Object> patch = Collections.<String, Object>singletonMap(FrigateConfig.KEY_FRIGATE_URL, cleanUrl);
        this.config.updateFrom(patch);
        try {
            this.host.updateSettings(patch);
        } catch (Exception ignored) {}
        try {
            this.host.log("Auto-detected Frigate URL: " + cleanUrl);
            this.host.status(FrigateI18n.tr(null, this.config, "plugin_status_connected_url", cleanUrl), false);
        } catch (Exception ignored) {}
        if (this.apiClient != null) {
            this.apiClient.ensureAuthenticatedAsync(() -> refreshCameraSelectAsync());
        }
    }

    @Override
    public synchronized void configure(Map<String, Object> settings) {
        if (settings == null) return;
        boolean uiScaleChanged = false;
        boolean pillChanged = false;
        boolean languageChanged = false;
        if (config != null) {
            float prevScale = config.getUiScale();
            boolean prevPill = config.isShowStreamModePill();
            String prevLanguage = config.getLanguage();
            // Mirror host settings in memory without calling host.saveSettings(),
            // which would overwrite/drop host-persisted keys.
            config.updateFrom(settings);
            uiScaleChanged = Math.abs(config.getUiScale() - prevScale) > 0.001f;
            pillChanged = (config.isShowStreamModePill() != prevPill);
            languageChanged = !config.getLanguage().equals(prevLanguage);
            if (imageLoader != null) {
                imageLoader.setConfig(config);
                imageLoader.setAuthToken(config.getAuthToken());
            }
            if (apiClient != null) {
                apiClient.ensureAuthenticatedAsync(() -> refreshCameraSelectAsync());
            }
        }
        if (languageChanged && host != null && config != null) {
            try {
                host.status(FrigateI18n.tr(null, config, "plugin_status_connected_url", config.getFrigateUrl()), false);
            } catch (Exception ignored) {}
        }
        if (uiScaleChanged || languageChanged) {
            if (isExplorerOpen) {
                showExplorerModal();
            }
            if (isPlayerOpen && lastPlayerEvent != null) {
                showPlayerModal(lastPlayerEvent);
            }
        } else if (pillChanged && isPlayerOpen && lastPlayerEvent != null) {
            showPlayerModal(lastPlayerEvent);
        }
    }

    @Override
    public synchronized void execute(String command, Map<String, Object> arguments) {
        if ("showExplorer".equals(command)) {
            showExplorerModal();
        } else if ("hideExplorer".equals(command)) {
            hideExplorerModal();
        } else if ("hidePlayer".equals(command)) {
            hidePlayerModal();
        } else if ("openCamera".equals(command)) {
            openLiveCamera(extractCameraArgument(arguments));
        }
    }

    private String extractCameraArgument(Map<String, Object> arguments) {
        if (arguments == null) {
            return null;
        }
        Object argsObj = arguments.get("args");
        if (argsObj instanceof List<?>) {
            List<?> list = (List<?>) argsObj;
            if (!list.isEmpty() && list.get(0) != null) {
                String arg0 = String.valueOf(list.get(0)).trim();
                if (!arg0.isEmpty()) {
                    return arg0;
                }
            }
        }
        Object camObj = arguments.get("camera");
        if (camObj != null) {
            String cam = String.valueOf(camObj).trim();
            if (!cam.isEmpty()) {
                return cam;
            }
        }
        return null;
    }

    private synchronized void openLiveCamera(String requestedCamera) {
        if (config == null || host == null) return;
        String cam = (requestedCamera != null) ? requestedCamera.trim() : "";
        if (cam.isEmpty()) {
            cam = config.getDefaultCamera();
        }
        if ((cam == null || cam.isEmpty()) && cameraSelectOptions != null && cameraSelectOptions.length > 1) {
            cam = cameraSelectOptions[1];
        }
        if (cam == null || cam.isEmpty()) {
            showExplorerModal();
            return;
        }
        showPlayerModal(new FrigateEvent(null, cam, "Live", null, 1.0,
                0, 0, true, true, null, null));
    }

    @Override
    public synchronized void onEvent(String event, Map<String, Object> payload) {
        if ("overlay.closed".equals(event)) {
            if (payload != null) {
                Object key = payload.get("key");
                if (OVERLAY_EXPLORER.equals(key)) {
                    isExplorerOpen = false;
                } else if (OVERLAY_PLAYER.equals(key)) {
                    isPlayerOpen = false;
                    activeSelectCamera = SELECT_OPTION_CLOSED;
                    publishCurrentCameraSelectState();
                }
            }
        } else if (("select." + ENTITY_CAMERA_SELECT).equals(event) && payload != null) {
            Object optObj = payload.get("option");
            if (optObj instanceof String) {
                String option = ((String) optObj).trim();
                if (SELECT_OPTION_CLOSED.equals(option)) {
                    hidePlayerModal();
                } else if (containsOption(cameraSelectOptions, option)) {
                    openLiveCamera(option);
                } else {
                    publishCurrentCameraSelectState();
                }
            }
        } else if ("settings.button".equals(event) && payload != null) {
            Object idObj = payload.get("id");
            Object runObj = payload.get("run");
            if ("testConnection".equals(idObj)) {
                if (runObj instanceof String) {
                    runConnectionTest((String) runObj);
                }
            } else if ("openExplorer".equals(idObj)) {
                showExplorerModal();
                if (runObj instanceof String && host != null) {
                    reportSetupResult(host, (String) runObj, "success",
                            FrigateI18n.tr(null, config, "plugin_explorer_opened"));
                }
            }
        } else if ("settings.cancel".equals(event) && payload != null) {
            Object runObj = payload.get("run");
            if (runObj instanceof String) {
                canceledSetupRuns.add((String) runObj);
            }
        }
    }

    private void runConnectionTest(final String runId) {
        final PluginHost currentHost = this.host;
        final FrigateConfig currentConfig = this.config;
        final FrigateApiClient currentClient = this.apiClient;
        final AsyncImageLoader currentLoader = this.imageLoader;
        if (currentHost == null || currentConfig == null || currentClient == null) {
            return;
        }
        if (!reportSetupResult(currentHost, runId, "running",
                FrigateI18n.tr(null, currentConfig, "plugin_testing_connection", currentConfig.getFrigateUrl()))) {
            return;
        }
        Thread t = new Thread(() -> {
            ConnectionTestResult result = currentClient.testConnection();
            if (canceledSetupRuns.remove(runId)) {
                return;
            }
            if (currentLoader != null) {
                currentLoader.setAuthToken(currentConfig.getAuthToken());
            }
            reportSetupResult(currentHost, runId, result.ok ? "success" : "error", result.message);
            try {
                currentHost.status(result.message, !result.ok);
                currentHost.publishTextSensor("frigate_status", "Frigate Status",
                        FrigateI18n.tr(null, currentConfig, result.ok ? "plugin_sensor_connected" : "plugin_sensor_error"));
            } catch (Exception ignored) {}
            if (result.ok) {
                refreshCameraSelectAsync();
            }
        }, "FrigatePlugin-ConnectTest");
        t.setDaemon(true);
        t.start();
    }

    private void refreshCameraSelectAsync() {
        final FrigateApiClient client = this.apiClient;
        if (client == null) return;
        client.fetchCameras(new FrigateApiClient.Callback<List<FrigateCamera>>() {
            @Override
            public void onSuccess(List<FrigateCamera> cameras) {
                updateCameraSelectOptions(cameras);
            }

            @Override
            public void onError(Exception error) {
                onCameraRefreshError();
            }
        });
    }

    private synchronized void onCameraRefreshError() {
        if (host == null) return;
        // Keep previously published cameras on transient errors; show Unavailable only before first load.
        if (cameraSelectOptions == null) {
            try {
                host.publishTextSensor(ENTITY_CAMERAS_SUMMARY, ENTITY_CAMERAS_SUMMARY_NAME,
                        FrigateI18n.tr(null, config, "plugin_sensor_unavailable"));
            } catch (UnsupportedOperationException | LinkageError | IllegalArgumentException | IllegalStateException ignored) {}
        }
    }

    private synchronized void updateCameraSelectOptions(List<FrigateCamera> cameras) {
        if (host == null || cameras == null) return;
        publishCameraReadings(cameras);
        if (cameras.isEmpty()) return;
        Set<String> unique = new LinkedHashSet<>();
        unique.add(SELECT_OPTION_CLOSED);
        for (FrigateCamera cam : cameras) {
            if (unique.size() >= MAX_SELECT_OPTIONS) break;
            if (cam == null || cam.getName() == null) continue;
            String name = cam.getName().trim();
            if (!name.isEmpty() && name.length() <= 80 && !SELECT_OPTION_CLOSED.equals(name)) {
                unique.add(name);
            }
        }
        if (unique.size() <= 1) return;
        String[] nextOptions = unique.toArray(new String[0]);
        boolean optionsChanged = !Arrays.equals(this.cameraSelectOptions, nextOptions);
        this.cameraSelectOptions = nextOptions;
        if (!isPlayerOpen || !containsOption(nextOptions, activeSelectCamera)) {
            activeSelectCamera = SELECT_OPTION_CLOSED;
        }
        if (optionsChanged) {
            publishCurrentCameraSelectState();
        }
    }

    private synchronized void publishCameraReadings(List<FrigateCamera> cameras) {
        if (host == null) return;
        List<String> cameraNames = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();
        for (FrigateCamera cam : cameras) {
            if (cam == null || cam.getName() == null) continue;
            String name = cam.getName().trim();
            if (!name.isEmpty() && seenNames.add(name)) {
                cameraNames.add(name);
            }
        }

        String summary;
        if (cameraNames.isEmpty()) {
            summary = FrigateI18n.tr(null, config, "plugin_sensor_none");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(cameraNames.size()).append(": ");
            for (int i = 0; i < cameraNames.size(); i++) {
                String entry = (i > 0 ? ", " : "") + cameraNames.get(i);
                if (sb.length() + entry.length() + 3 > MAX_TEXT_SENSOR_STATE_LENGTH) {
                    sb.append("...");
                    break;
                }
                sb.append(entry);
            }
            summary = sb.toString();
        }
        try {
            host.publishTextSensor(ENTITY_CAMERAS_SUMMARY, ENTITY_CAMERAS_SUMMARY_NAME, summary);
        } catch (UnsupportedOperationException | LinkageError | IllegalArgumentException | IllegalStateException ignored) {}

        Map<String, String> nextSensors = new LinkedHashMap<>();
        Map<String, String> nextNames = new LinkedHashMap<>();
        Set<String> usedKeys = new HashSet<>();
        usedKeys.add("frigate_status");
        usedKeys.add(ENTITY_CAMERA_SELECT);
        usedKeys.add(ENTITY_CAMERAS_SUMMARY);

        for (String camName : cameraNames) {
            if (nextSensors.size() >= MAX_CAMERA_LINK_SENSORS) break;
            String link = DASHBOARD_OPEN_CAMERA_PREFIX + encodePathSegment(camName);
            if (link.length() > MAX_TEXT_SENSOR_STATE_LENGTH) continue;
            String key = toCameraEntityKey(camName, usedKeys);
            String entityName = truncate("Camera: " + camName, MAX_ENTITY_NAME_LENGTH);
            nextSensors.put(key, link);
            nextNames.put(key, entityName);
        }

        Iterator<Map.Entry<String, String>> existingIt = publishedCameraSensors.entrySet().iterator();
        while (existingIt.hasNext()) {
            Map.Entry<String, String> entry = existingIt.next();
            if (!nextSensors.containsKey(entry.getKey())) {
                try {
                    host.removeTextSensor(entry.getKey());
                } catch (UnsupportedOperationException | LinkageError | IllegalStateException ignored) {}
                existingIt.remove();
            }
        }

        for (Map.Entry<String, String> entry : nextSensors.entrySet()) {
            String key = entry.getKey();
            String link = entry.getValue();
            String entityName = nextNames.get(key);
            String signature = entityName + "\n" + link;
            if (!signature.equals(publishedCameraSensors.get(key))) {
                try {
                    host.publishTextSensor(key, entityName, link);
                    publishedCameraSensors.put(key, signature);
                } catch (UnsupportedOperationException | LinkageError | IllegalArgumentException | IllegalStateException ignored) {}
            }
        }
    }

    private static String toCameraEntityKey(String camName, Set<String> usedKeys) {
        String lower = camName.toLowerCase(Locale.US);
        StringBuilder sanitized = new StringBuilder("cam_");
        boolean lastUnderscore = true;
        for (int i = 0; i < lower.length() && sanitized.length() < MAX_ENTITY_KEY_LENGTH; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sanitized.append(c);
                lastUnderscore = false;
            } else if (!lastUnderscore) {
                sanitized.append('_');
                lastUnderscore = true;
            }
        }
        while (sanitized.length() > 4 && sanitized.charAt(sanitized.length() - 1) == '_') {
            sanitized.setLength(sanitized.length() - 1);
        }
        if (sanitized.length() <= 4) {
            sanitized.append(Integer.toHexString(camName.hashCode() & 0x7fffffff));
        }
        String base = truncate(sanitized.toString(), MAX_ENTITY_KEY_LENGTH);
        String candidate = base;
        int suffix = 2;
        while (usedKeys.contains(candidate)) {
            String suffixStr = "_" + suffix++;
            candidate = truncate(base, MAX_ENTITY_KEY_LENGTH - suffixStr.length()) + suffixStr;
        }
        usedKeys.add(candidate);
        return candidate;
    }

    private static String encodePathSegment(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, Math.max(0, maxLength));
    }

    private synchronized void publishCurrentCameraSelectState() {
        if (host == null || cameraSelectOptions == null || cameraSelectOptions.length == 0) return;
        String state = containsOption(cameraSelectOptions, activeSelectCamera)
                ? activeSelectCamera
                : SELECT_OPTION_CLOSED;
        try {
            host.publishSelect(ENTITY_CAMERA_SELECT, ENTITY_CAMERA_SELECT_NAME, cameraSelectOptions, state);
        } catch (UnsupportedOperationException | LinkageError | IllegalArgumentException | IllegalStateException ignored) {}
    }

    private static boolean containsOption(String[] options, String target) {
        if (options == null || target == null) return false;
        for (String opt : options) {
            if (target.equals(opt)) return true;
        }
        return false;
    }

    private boolean reportSetupResult(PluginHost h, String runId, String state, String message) {
        try {
            return h.setupResult(runId, state, message);
        } catch (UnsupportedOperationException | LinkageError ignored) {
            return false;
        }
    }

    @Override
    public synchronized void stop() {
        if (host != null) {
            try {
                host.removeTextSensor("frigate_status");
            } catch (Exception ignored) {}
            host.hideOverlay(OVERLAY_PLAYER);
            host.hideOverlay(OVERLAY_EXPLORER);
        }
        if (apiClient != null) {
            apiClient.shutdown();
            apiClient = null;
        }
        if (imageLoader != null) {
            imageLoader.shutdown();
            imageLoader = null;
        }
        this.host = null;
        this.config = null;
        this.isExplorerOpen = false;
        this.isPlayerOpen = false;
        this.explorerOverlayGeneration = 0L;
        this.playerOverlayGeneration = 0L;
        this.lastPlayerEvent = null;
        this.cameraSelectOptions = null;
        this.activeSelectCamera = SELECT_OPTION_CLOSED;
        this.publishedCameraSensors.clear();
        this.canceledSetupRuns.clear();
    }

    public FrigateConfig getConfig() {
        return config;
    }

    public FrigateApiClient getApiClient() {
        return apiClient;
    }

    public AsyncImageLoader getImageLoader() {
        return imageLoader;
    }

    /**
     * Toggles the full Frigate explorer overlay.
     */
    public synchronized void toggleExplorer() {
        if (host == null) return;

        if (isExplorerOpen) {
            hideExplorerModal();
        } else {
            showExplorerModal();
        }
    }

    private void showExplorerModal() {
        if (host == null) return;
        isExplorerOpen = true;
        final long generation = ++explorerOverlayGeneration;

        OverlaySpec spec = OverlaySpec.fullScreen()
                .layer(OverlaySpec.LAYER_TOP)
                .touchable(true);

        host.showOverlay(OVERLAY_EXPLORER, spec, new OverlayFactory() {
            private FrigateExplorerOverlay overlay;

            @Override
            public View create(Context context, KsTheme theme) {
                overlay = new FrigateExplorerOverlay(
                        context,
                        theme,
                        apiClient,
                        config,
                        imageLoader,
                        () -> toggleExplorer(),
                        event -> onEventSelected(event)
                );
                return overlay.buildView();
            }

            @Override
            public void onDestroy(View view) {
                if (overlay != null) {
                    overlay.onDestroy();
                    overlay = null;
                }
                synchronized (FrigatePlugin.this) {
                    if (generation == explorerOverlayGeneration) {
                        isExplorerOpen = false;
                    }
                }
            }
        });
    }

    private void hideExplorerModal() {
        if (host == null) return;
        explorerOverlayGeneration++;
        host.hideOverlay(OVERLAY_EXPLORER);
        isExplorerOpen = false;
    }

    /**
     * Opens the native video player modal for the selected event or camera clip.
     */
    public synchronized void showPlayerModal(FrigateEvent event) {
        if (host == null) return;
        isPlayerOpen = true;
        lastPlayerEvent = event;
        final long generation = ++playerOverlayGeneration;
        String eventCam = (event != null && event.getCamera() != null) ? event.getCamera().trim() : "";
        if (!eventCam.isEmpty() && containsOption(cameraSelectOptions, eventCam)) {
            activeSelectCamera = eventCam;
            publishCurrentCameraSelectState();
        }

        OverlaySpec spec = OverlaySpec.fullScreen()
                .layer(OverlaySpec.LAYER_TOP)
                .touchable(true);

        host.showOverlay(OVERLAY_PLAYER, spec, new OverlayFactory() {
            private FrigatePlayerOverlay player;

            @Override
            public View create(Context context, KsTheme theme) {
                player = new FrigatePlayerOverlay(
                        context,
                        theme,
                        host,
                        apiClient,
                        config,
                        event,
                        () -> hidePlayerModal()
                );
                return player.buildView();
            }

            @Override
            public void onDestroy(View view) {
                if (player != null) {
                    player.onDestroy();
                    player = null;
                }
                synchronized (FrigatePlugin.this) {
                    if (generation == playerOverlayGeneration) {
                        isPlayerOpen = false;
                        lastPlayerEvent = null;
                        activeSelectCamera = SELECT_OPTION_CLOSED;
                        publishCurrentCameraSelectState();
                    }
                }
            }
        });
    }

    public synchronized void hidePlayerModal() {
        if (host == null) return;
        playerOverlayGeneration++;
        host.hideOverlay(OVERLAY_PLAYER);
        isPlayerOpen = false;
        lastPlayerEvent = null;
        activeSelectCamera = SELECT_OPTION_CLOSED;
        publishCurrentCameraSelectState();
    }

    /**
     * Invoked when an event card is tapped in the explorer.
     */
    public void onEventSelected(FrigateEvent event) {
        showPlayerModal(event);
    }
}
