package io.github.tibohlami.ksplugins.frigate.api;

import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import me.jxl.kiosk.plugins.PluginHost;
import io.github.tibohlami.ksplugins.frigate.model.ConnectionTestResult;
import io.github.tibohlami.ksplugins.frigate.model.DaySummary;
import io.github.tibohlami.ksplugins.frigate.model.FrigateCamera;
import io.github.tibohlami.ksplugins.frigate.model.FrigateEvent;
import io.github.tibohlami.ksplugins.frigate.model.RecordingSegment;
import io.github.tibohlami.ksplugins.frigate.model.ReviewSegment;
import io.github.tibohlami.ksplugins.frigate.model.TimelineBoxEntry;
import io.github.tibohlami.ksplugins.frigate.ui.FrigateI18n;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import javax.net.ssl.SSLException;

/**
 * Lightweight, asynchronous REST API client for Frigate 0.18+.
 * Uses native Java HttpURLConnection and background thread executor.
 */
public class FrigateApiClient {

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(Exception error);
    }

    private final FrigateConfig config;
    private PluginHost host;
    private final ExecutorService executor;
    private final Object loginLock = new Object();
    private volatile boolean isShutdown = false;

    public FrigateApiClient(FrigateConfig config) {
        this(config, null);
    }

    public FrigateApiClient(FrigateConfig config, PluginHost host) {
        this.config = config;
        this.host = host;
        this.executor = Executors.newFixedThreadPool(3);
    }

    public void setHost(PluginHost host) {
        this.host = host;
    }

    private static String joinEncodedCsv(String rawCsvOrSingle) throws Exception {
        if (rawCsvOrSingle == null) return "";
        String[] parts = rawCsvOrSingle.split(",");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty() || "all".equalsIgnoreCase(trimmed)) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(URLEncoder.encode(trimmed, "UTF-8"));
        }
        return sb.toString();
    }

    public void shutdown() {
        isShutdown = true;
        executor.shutdownNow();
    }

    // --- URL Builders ---

    public String getThumbnailUrl(String eventId) {
        return config.getFrigateUrl() + "/api/events/" + eventId + "/thumbnail.jpg";
    }

    public String getReviewThumbnailUrl(ReviewSegment rev) {
        if (rev == null) return null;
        if (rev.thumbPath != null && !rev.thumbPath.isEmpty()) {
            String path = rev.thumbPath.trim();
            if (path.startsWith("http://") || path.startsWith("https://")) {
                return path;
            }
            if (path.startsWith("/media/frigate/")) {
                path = path.substring("/media/frigate".length());
            } else if (path.startsWith("media/frigate/")) {
                path = path.substring("media/frigate".length());
            }
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            return config.getFrigateUrl() + path;
        }
        if (rev.detectionIds != null && !rev.detectionIds.isEmpty()) {
            return getThumbnailUrl(rev.detectionIds.get(0));
        }
        return null;
    }

    public String getSnapshotUrl(String eventId) {
        return config.getFrigateUrl() + "/api/events/" + eventId + "/snapshot.jpg";
    }

    public String getClipUrl(String eventId) {
        return config.getFrigateUrl() + "/api/events/" + eventId + "/clip.mp4";
    }

    private static String encodePath(String segment) {
        if (segment == null) return "";
        try {
            return URLEncoder.encode(segment.trim(), "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return segment.trim();
        }
    }

    public String getLatestFrameUrl(String camera) {
        return config.getFrigateUrl() + "/api/" + encodePath(camera) + "/latest.jpg";
    }

    public String getLiveMjpegUrl(String camera, int fps, int height) {
        return config.getFrigateUrl() + "/api/" + encodePath(camera) + "?fps=" + fps + "&height=" + height;
    }

    public String getEventVodHlsUrl(String eventId) {
        return config.getFrigateUrl() + "/vod/event/" + encodePath(eventId) + "/master.m3u8";
    }

    public String getVodHlsUrl(String camera, long startSeconds, long endSeconds) {
        return config.getFrigateUrl() + "/vod/" + encodePath(camera) + "/start/" + startSeconds + "/end/" + endSeconds + "/master.m3u8";
    }

    public String getTimelineClipUrl(String camera, long startSeconds, long endSeconds) {
        return config.getFrigateUrl() + "/api/" + encodePath(camera) + "/start/" + startSeconds + "/end/" + endSeconds + "/clip.mp4";
    }

    public String getRecordingSnapshotUrl(String camera, long frameTimeSeconds, int height) {
        return getRecordingSnapshotUrl(camera, (double) frameTimeSeconds, height);
    }

    public String getRecordingSnapshotUrl(String camera, double frameTimeSeconds, int height) {
        String tsStr = (frameTimeSeconds == Math.rint(frameTimeSeconds))
                ? String.valueOf((long) frameTimeSeconds)
                : String.format(Locale.US, "%.6f", frameTimeSeconds);
        String url = config.getFrigateUrl() + "/api/" + encodePath(camera) + "/recordings/" + tsStr + "/snapshot.jpg";
        if (height > 0) {
            url += "?height=" + height;
        }
        return url;
    }

    public void fetchRecordings(String camera, long afterSeconds, long beforeSeconds,
                                Callback<List<RecordingSegment>> callback) {
        if (isShutdown || executor.isShutdown() || camera == null || camera.trim().isEmpty()) return;
        try {
            executor.execute(() -> {
                if (isShutdown) return;
                try {
                    String url = config.getFrigateUrl() + "/api/"
                            + URLEncoder.encode(camera.trim(), "UTF-8").replace("+", "%20")
                            + "/recordings?after=" + afterSeconds + "&before=" + beforeSeconds;
                    String response = executeGet(url);
                    if (isShutdown) return;
                    callback.onSuccess(FrigateJsonParser.parseRecordings(response));
                } catch (Throwable e) {
                    if (!isShutdown) {
                        callback.onError(e instanceof Exception ? (Exception) e : new Exception(e));
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    /**
     * Fetches Frigate review segments (alerts / detections with objects) in [afterSeconds, beforeSeconds].
     */
    public void fetchReviewSegments(String camera, long afterSeconds, long beforeSeconds,
                                    Callback<List<ReviewSegment>> callback) {
        fetchReviewSegmentsFiltered(camera, null, null, afterSeconds, beforeSeconds, 2000, callback);
    }

    /**
     * Fetches Frigate review segments with optional camera, label, severity, and time-range filters.
     */
    public void fetchReviewSegmentsFiltered(String camera, String label, String severity,
                                            long afterSeconds, long beforeSeconds, int limit,
                                            Callback<List<ReviewSegment>> callback) {
        if (isShutdown || executor.isShutdown()) return;
        try {
            executor.execute(() -> {
                if (isShutdown) return;
                try {
                    StringBuilder url = new StringBuilder(config.getFrigateUrl())
                            .append("/api/review?limit=").append(Math.max(1, limit));
                    String camCsv = joinEncodedCsv(camera);
                    if (!camCsv.isEmpty()) {
                        url.append("&cameras=").append(camCsv);
                    }
                    String lblCsv = joinEncodedCsv(label);
                    if (!lblCsv.isEmpty()) {
                        url.append("&labels=").append(lblCsv);
                    }
                    if (severity != null && !severity.trim().isEmpty() && !"all".equalsIgnoreCase(severity.trim())) {
                        url.append("&severity=").append(URLEncoder.encode(severity.trim(), "UTF-8"));
                    }
                    if (afterSeconds > 0L) {
                        url.append("&after=").append(afterSeconds);
                    }
                    if (beforeSeconds > 0L) {
                        url.append("&before=").append(beforeSeconds);
                    }
                    String response = executeGet(url.toString());
                    if (isShutdown) return;
                    long fallbackEnd = (beforeSeconds > 0L) ? beforeSeconds : (System.currentTimeMillis() / 1000L);
                    callback.onSuccess(FrigateJsonParser.parseReviewSegments(response, fallbackEnd));
                } catch (Throwable e) {
                    if (!isShutdown) {
                        callback.onError(e instanceof Exception ? (Exception) e : new Exception(e));
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    /**
     * Fetches tracked-object timeline milestones (including {@code data.box}) for an event.
     */
    public void fetchEventTimelineBoxes(String eventId, Callback<List<TimelineBoxEntry>> callback) {
        if (isShutdown || executor.isShutdown() || eventId == null || eventId.trim().isEmpty()) return;
        try {
            executor.execute(() -> {
                if (isShutdown) return;
                try {
                    String url = config.getFrigateUrl() + "/api/timeline?source_id="
                            + URLEncoder.encode(eventId.trim(), "UTF-8");
                    String response = executeGet(url);
                    if (isShutdown) return;
                    callback.onSuccess(FrigateJsonParser.parseEventTimelineBoxes(response, eventId));
                } catch (Throwable e) {
                    if (!isShutdown) {
                        callback.onError(e instanceof Exception ? (Exception) e : new Exception(e));
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    // --- API Calls ---

    /**
     * Asynchronously queries Frigate events.
     *
     * @param camera Optional camera filter, null or empty for all
     * @param label Optional detection label filter (e.g. "person", "car"), null or empty for all
     * @param limit Number of events to return
     * @param callback Callback delivering List<FrigateEvent>
     */
    public void fetchEvents(String camera, String label, int limit, Callback<List<FrigateEvent>> callback) {
        fetchEventsFiltered(camera, label, 0L, 0L, limit, callback);
    }

    /**
     * Asynchronously queries Frigate events with optional camera, label, and time-range filters.
     */
    public void fetchEventsFiltered(String camera, String label, long afterSeconds, long beforeSeconds,
                                    int limit, Callback<List<FrigateEvent>> callback) {
        if (isShutdown || executor.isShutdown()) return;
        try {
            StringBuilder urlBuilder = new StringBuilder(config.getFrigateUrl());
            urlBuilder.append("/api/events?limit=").append(limit > 0 ? limit : config.getMaxEvents());
            String camCsv = joinEncodedCsv(camera);
            if (!camCsv.isEmpty()) {
                urlBuilder.append("&cameras=").append(camCsv);
            }
            String lblCsv = joinEncodedCsv(label);
            if (!lblCsv.isEmpty()) {
                urlBuilder.append("&labels=").append(lblCsv);
            }
            if (afterSeconds > 0L) {
                urlBuilder.append("&after=").append(afterSeconds);
            }
            if (beforeSeconds > 0L) {
                urlBuilder.append("&before=").append(beforeSeconds);
            }
            fetchEventsInternal(urlBuilder.toString(), callback);
        } catch (Exception e) {
            if (!isShutdown) callback.onError(e);
        }
    }

    /**
     * Fetches events for a camera since afterSeconds.
     */
    public void fetchCameraEventsSince(String camera, long afterSeconds, int limit,
                                       Callback<List<FrigateEvent>> callback) {
        fetchCameraEventsRange(camera, afterSeconds, -1L, limit, callback);
    }

    /**
     * Fetches events for a camera within [afterSeconds, beforeSeconds] (or unbounded end if beforeSeconds <= 0).
     */
    public void fetchCameraEventsRange(String camera, long afterSeconds, long beforeSeconds, int limit,
                                       Callback<List<FrigateEvent>> callback) {
        if (isShutdown || executor.isShutdown() || camera == null || camera.trim().isEmpty()) return;
        try {
            StringBuilder url = new StringBuilder(config.getFrigateUrl())
                    .append("/api/events?camera=").append(URLEncoder.encode(camera.trim(), "UTF-8"))
                    .append("&after=").append(afterSeconds);
            if (beforeSeconds > 0L) {
                url.append("&before=").append(beforeSeconds);
            }
            url.append("&limit=").append(Math.max(1, limit));
            fetchEventsInternal(url.toString(), callback);
        } catch (Exception e) {
            if (!isShutdown) callback.onError(e);
        }
    }

    /**
     * Fetches currently active (in-progress) object detections on a camera.
     */
    public void fetchInProgressEvents(String camera, Callback<List<FrigateEvent>> callback) {
        if (isShutdown || executor.isShutdown() || camera == null || camera.trim().isEmpty()) return;
        try {
            String url = config.getFrigateUrl()
                    + "/api/events?camera=" + URLEncoder.encode(camera.trim(), "UTF-8")
                    + "&in_progress=1&limit=20";
            fetchEventsInternal(url, callback);
        } catch (Exception e) {
            if (!isShutdown) callback.onError(e);
        }
    }

    private void fetchEventsInternal(String url, Callback<List<FrigateEvent>> callback) {
        try {
            executor.execute(() -> {
                if (isShutdown) return;
                try {
                    String response = executeGet(url);
                    if (isShutdown) return;
                    callback.onSuccess(FrigateJsonParser.parseEvents(response));
                } catch (Throwable e) {
                    if (!isShutdown) {
                        callback.onError(e instanceof Exception ? (Exception) e : new Exception(e));
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    /**
     * Returns true for internal Frigate virtual cameras such as Debug Replay streams
     * ({@code _replay_<camera>}), which Frigate exposes in /api/stats without a boolean flag.
     */
    public static boolean isVirtualReplayCamera(String cameraName) {
        if (cameraName == null) return true;
        String normalized = cameraName.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || normalized.startsWith("_replay_");
    }

    /**
     * Discovers all cameras configured in Frigate by querying /api/stats.
     */
    public void fetchCameras(Callback<List<FrigateCamera>> callback) {
        if (isShutdown || executor.isShutdown()) return;
        try {
            executor.execute(() -> {
                if (isShutdown) return;
                try {
                    String response = executeGet(config.getFrigateUrl() + "/api/stats");
                    if (isShutdown) return;
                    callback.onSuccess(FrigateJsonParser.parseCamerasFromStats(response));
                } catch (Throwable e) {
                    if (!isShutdown) {
                        callback.onError(e instanceof Exception ? (Exception) e : new Exception(e));
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    /**
     * Fetches distinct tracked object labels configured across active Frigate cameras via /api/config.
     */
    public void fetchTrackedLabels(Callback<List<String>> callback) {
        if (isShutdown || executor.isShutdown()) return;
        try {
            executor.execute(() -> {
                if (isShutdown) return;
                try {
                    String response = executeGet(config.getFrigateUrl() + "/api/config");
                    if (isShutdown) return;
                    callback.onSuccess(FrigateJsonParser.parseTrackedLabelsFromConfig(response));
                } catch (Throwable e) {
                    if (!isShutdown) {
                        callback.onError(e instanceof Exception ? (Exception) e : new Exception(e));
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    /**
     * Fetches daily event counts across all retained days via {@code /api/events/summary}.
     */
    public void fetchEventsSummary(Callback<List<DaySummary>> callback) {
        if (isShutdown || executor.isShutdown()) return;
        try {
            executor.execute(() -> {
                if (isShutdown) return;
                try {
                    String tzId = TimeZone.getDefault().getID();
                    String url = config.getFrigateUrl() + "/api/events/summary?timezone="
                            + URLEncoder.encode(tzId, "UTF-8");
                    String response = executeGet(url);
                    if (isShutdown) return;
                    callback.onSuccess(FrigateJsonParser.parseEventsSummary(response));
                } catch (Throwable e) {
                    if (!isShutdown) {
                        callback.onError(e instanceof Exception ? (Exception) e : new Exception(e));
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    /**
     * Fetches daily review counts (alerts + detections) via {@code /api/review/summary}.
     * Used alongside {@link #fetchEventsSummary(Callback)} so days with retained review
     * segments still appear even when /api/events records have already been pruned.
     */
    public void fetchReviewSummary(Callback<List<DaySummary>> callback) {
        if (isShutdown || executor.isShutdown()) return;
        try {
            executor.execute(() -> {
                if (isShutdown) return;
                try {
                    String tzId = TimeZone.getDefault().getID();
                    String url = config.getFrigateUrl() + "/api/review/summary?timezone="
                            + URLEncoder.encode(tzId, "UTF-8");
                    String response = executeGet(url);
                    if (isShutdown) return;
                    callback.onSuccess(FrigateJsonParser.parseReviewSummary(response));
                } catch (Throwable e) {
                    if (!isShutdown) {
                        callback.onError(e instanceof Exception ? (Exception) e : new Exception(e));
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    /**
     * Executes an HTTP GET request with timeouts, automatic /api/login authentication,
     * and 401 token refresh retry.
     */
    private String executeGet(String endpointUrl) throws Exception {
        if (config.getAuthToken().isEmpty() && config.hasCredentials()) {
            ensureSessionToken(false);
        }
        HttpURLConnection conn = openGetConnection(endpointUrl);
        int code = conn.getResponseCode();
        if (code == 401 && config.hasCredentials()) {
            conn.disconnect();
            if (ensureSessionToken(true)) {
                conn = openGetConnection(endpointUrl);
                code = conn.getResponseCode();
            }
        }
        if (code >= 200 && code < 300) {
            try (InputStream in = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                String resp = sb.toString();
                long respBytes = resp.getBytes(StandardCharsets.UTF_8).length;
                FrigateHttpHelper.logApiDebug(host, config, "GET", endpointUrl, respBytes);
                return resp;
            } finally {
                conn.disconnect();
            }
        } else {
            String msg = conn.getResponseMessage();
            conn.disconnect();
            throw new Exception("HTTP " + code + " " + msg + " for " + endpointUrl);
        }
    }

    private HttpURLConnection openGetConnection(String endpointUrl) throws Exception {
        URL url = new URL(endpointUrl);
        HttpURLConnection conn = FrigateHttpHelper.openConnection(url, config);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Accept", "application/json");
        FrigateHttpHelper.applyAuthHeaders(conn, config.getAuthToken());
        return conn;
    }

    /**
     * Ensures a session token is obtained asynchronously (if credentials are configured),
     * then invokes onDone on the executor thread.
     */
    public void ensureAuthenticatedAsync(Runnable onDone) {
        if (isShutdown || executor.isShutdown()) return;
        if (!config.hasCredentials() || !config.getAuthToken().isEmpty()) {
            if (onDone != null) onDone.run();
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    if (isShutdown) return;
                    ensureSessionToken(false);
                    if (!isShutdown && onDone != null) {
                        onDone.run();
                    }
                } catch (Throwable ignored) {}
            });
        } catch (RejectedExecutionException ignored) {}
    }

    /**
     * Logs in to Frigate /api/login using configured username/password and caches the
     * returned frigate_token JWT in memory.
     */
    public boolean ensureSessionToken(boolean forceRefresh) {
        return performLogin(forceRefresh).ok;
    }

    private ConnectionTestResult performLogin(boolean forceRefresh) {
        if (!config.hasCredentials()) {
            return ConnectionTestResult.error(FrigateI18n.tr(null, config, "api_err_no_credentials"));
        }
        synchronized (loginLock) {
            if (!forceRefresh && !config.getAuthToken().isEmpty()) {
                return ConnectionTestResult.success(FrigateI18n.tr(null, config, "api_login_session_active"), 0);
            }
            HttpURLConnection conn = null;
            try {
                URL url = new URL(config.getFrigateUrl() + "/api/login");
                conn = FrigateHttpHelper.openConnection(url, config);
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");

                JSONObject body = new JSONObject();
                body.put("user", config.getUsername());
                body.put("password", config.getPassword());
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                try (java.io.OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                }

                int code = conn.getResponseCode();
                long respBytes = 0L;
                if (code >= 200 && code < 300) {
                    String token = extractFrigateTokenFromHeaders(conn.getHeaderFields());
                    if ((token == null || token.isEmpty())) {
                        try (InputStream in = conn.getInputStream();
                             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = r.readLine()) != null) sb.append(line);
                            String raw = sb.toString().trim();
                            respBytes = raw.getBytes(StandardCharsets.UTF_8).length;
                            if (raw.startsWith("{")) {
                                JSONObject json = new JSONObject(raw);
                                token = json.optString("token", json.optString("access_token", ""));
                            }
                        } catch (Exception ignored) {}
                    }
                    FrigateHttpHelper.logApiDebug(host, config, "POST", url.toString(), respBytes);
                    if (token != null && !token.isEmpty()) {
                        config.setSessionToken(token);
                        return ConnectionTestResult.success(FrigateI18n.tr(null, config, "api_login_succeeded"), 0);
                    }
                    config.clearSessionToken();
                    return ConnectionTestResult.error(FrigateI18n.tr(null, config, "api_login_no_token", code));
                }
                config.clearSessionToken();
                if (code == 401 || code == 403) {
                    return ConnectionTestResult.error(FrigateI18n.tr(null, config, "api_login_invalid_creds", code));
                }
                String reason = conn.getResponseMessage() != null ? conn.getResponseMessage() : "";
                return ConnectionTestResult.error(FrigateI18n.tr(null, config, "api_login_http_error", code, reason));
            } catch (Exception e) {
                config.clearSessionToken();
                return ConnectionTestResult.error(formatConnectionError(e, config));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
    }

    /**
     * Synchronously validates URL reachability, TLS/SSL acceptance, authentication
     * (username/password login or manual Bearer token), and Frigate /api/config access.
     */
    public ConnectionTestResult testConnection() {
        String baseUrl = config.getFrigateUrl();
        URL parsedBase;
        try {
            parsedBase = new URL(baseUrl);
            if (parsedBase.getHost() == null || parsedBase.getHost().trim().isEmpty()) {
                return ConnectionTestResult.error(FrigateI18n.tr(null, config, "api_err_invalid_url_host"));
            }
        } catch (Exception e) {
            return ConnectionTestResult.error(FrigateI18n.tr(null, config, "api_err_invalid_url", e.getMessage() != null ? e.getMessage() : ""));
        }

        boolean usingCredentials = !config.hasManualAuthToken() && config.hasCredentials();
        if (usingCredentials) {
            ConnectionTestResult loginRes = performLogin(true);
            if (!loginRes.ok) {
                return loginRes;
            }
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/api/config");
            conn = FrigateHttpHelper.openConnection(url, config);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept", "application/json");
            FrigateHttpHelper.applyAuthHeaders(conn, config.getAuthToken());

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                return ConnectionTestResult.error(FrigateI18n.tr(null, config, "api_err_auth_rejected", code));
            }
            if (code < 200 || code >= 300) {
                String reason = conn.getResponseMessage() != null ? conn.getResponseMessage() : "";
                return ConnectionTestResult.error(FrigateI18n.tr(null, config, "api_err_http_config", code, reason));
            }

            StringBuilder sb = new StringBuilder();
            try (InputStream in = conn.getInputStream();
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }
            long respBytes = sb.toString().getBytes(StandardCharsets.UTF_8).length;
            FrigateHttpHelper.logApiDebug(host, config, "GET", url.toString(), respBytes);

            JSONObject json = new JSONObject(sb.toString());
            JSONObject cameras = json.optJSONObject("cameras");
            int cameraCount = cameras != null ? cameras.length() : 0;

            String protocol = parsedBase.getProtocol().toLowerCase(Locale.US);
            String tlsLabel = "https".equals(protocol)
                    ? FrigateI18n.tr(null, config, config.isAllowInsecureSsl() ? "api_tls_https_untrusted" : "api_tls_https_verified")
                    : "HTTP";
            String authLabel = config.hasManualAuthToken()
                    ? FrigateI18n.tr(null, config, "api_auth_token")
                    : FrigateI18n.tr(null, config, usingCredentials ? "api_auth_login_ok" : "api_auth_open");

            String summary = FrigateI18n.tr(null, config,
                    cameraCount == 1 ? "api_conn_summary_singular" : "api_conn_summary_plural",
                    cameraCount, tlsLabel, authLabel);
            return ConnectionTestResult.success(summary, cameraCount);
        } catch (Exception e) {
            return ConnectionTestResult.error(formatConnectionError(e, config));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String formatConnectionError(Throwable error, FrigateConfig config) {
        boolean allowInsecureSsl = config != null && config.isAllowInsecureSsl();
        Throwable cur = error;
        while (cur != null) {
            if (cur instanceof SSLException || cur instanceof CertificateException) {
                String detail = cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName();
                String hint = allowInsecureSsl
                        ? ""
                        : FrigateI18n.tr(null, config, "api_err_ssl_hint");
                return truncateMessage(FrigateI18n.tr(null, config, "api_err_ssl", detail, hint));
            }
            if (cur instanceof SocketTimeoutException) {
                return truncateMessage(FrigateI18n.tr(null, config, "api_err_timeout",
                        cur.getMessage() != null ? cur.getMessage() : "timeout"));
            }
            if (cur instanceof UnknownHostException || cur instanceof ConnectException) {
                return truncateMessage(FrigateI18n.tr(null, config, "api_err_unreachable",
                        cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName()));
            }
            cur = cur.getCause();
        }
        String msg = error != null && error.getMessage() != null
                ? error.getMessage()
                : (error != null ? error.getClass().getSimpleName() : FrigateI18n.tr(null, config, "api_err_unknown"));
        return truncateMessage(FrigateI18n.tr(null, config, "api_err_conn_failed", msg));
    }

    private static String truncateMessage(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 397) + "..." : s;
    }

    private static String extractFrigateTokenFromHeaders(java.util.Map<String, List<String>> headers) {
        if (headers == null) return null;
        for (java.util.Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (key != null && "Set-Cookie".equalsIgnoreCase(key) && entry.getValue() != null) {
                for (String cookie : entry.getValue()) {
                    if (cookie == null) continue;
                    int idx = cookie.indexOf("frigate_token=");
                    if (idx >= 0) {
                        int start = idx + "frigate_token=".length();
                        int end = cookie.indexOf(';', start);
                        String val = (end >= 0 ? cookie.substring(start, end) : cookie.substring(start)).trim();
                        if (!val.isEmpty()) return val;
                    }
                }
            }
        }
        return null;
    }
}
