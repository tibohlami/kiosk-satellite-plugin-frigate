// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.api;

import me.jxl.kiosk.plugins.PluginHost;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Discovers a reachable Frigate server URL when no custom URL has been configured yet.
 * Order:
 * 1. Home Assistant config_entries/get (domain=frigate; silently ignored if unauthorized)
 * 2. Home Assistant host from get_config (internal_url on ports 5000 / 8971)
 * 3. frigate.local (ports 5000 / 8971)
 */
public final class FrigateUrlAutoDetector {

    public interface Callback {
        void onDetected(String detectedUrl);
    }

    private static final int HA_CALL_TIMEOUT_SEC = 4;
    private static final int PROBE_CONNECT_TIMEOUT_MS = 1500;
    private static final int PROBE_READ_TIMEOUT_MS = 2000;

    private FrigateUrlAutoDetector() {}

    public static void detectAsync(final PluginHost host,
                                   final FrigateConfig config,
                                   final Callback callback) {
        if (host == null || config == null || callback == null) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String detected = detectSync(host, config);
                    if (detected != null && !detected.isEmpty()) {
                        callback.onDetected(detected);
                    }
                } catch (Throwable ignored) {}
            }
        }, "FrigateUrlAutoDetect");
        t.setDaemon(true);
        t.start();
    }

    private static String detectSync(PluginHost host, FrigateConfig config) {
        String fromEntries = tryFromConfigEntries(host, config);
        if (fromEntries != null) return fromEntries;

        String fromHaHost = tryFromHaHost(host, config);
        if (fromHaHost != null) return fromHaHost;

        return firstReachable(buildHostPortCandidates("frigate.local"), config);
    }

    private static String tryFromConfigEntries(PluginHost host, FrigateConfig config) {
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("type", "config_entries/get");
        cmd.put("domain", "frigate");

        Object data = callHaSync(host, cmd);
        if (!(data instanceof List<?>)) return null;

        List<String> enabledCandidates = new ArrayList<>();
        List<String> otherCandidates = new ArrayList<>();
        for (Object item : (List<?>) data) {
            if (!(item instanceof Map<?, ?>)) continue;
            Map<?, ?> entry = (Map<?, ?>) item;
            Object titleObj = entry.get("title");
            if (titleObj == null) continue;
            String title = String.valueOf(titleObj).trim();
            if (title.isEmpty()) continue;

            List<String> urls = buildCandidatesFromEntryTitle(title);
            if (entry.get("disabled_by") == null) {
                enabledCandidates.addAll(urls);
            } else {
                otherCandidates.addAll(urls);
            }
        }

        String found = firstReachable(enabledCandidates, config);
        if (found != null) return found;
        return firstReachable(otherCandidates, config);
    }

    private static String tryFromHaHost(PluginHost host, FrigateConfig config) {
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("type", "get_config");

        Object data = callHaSync(host, cmd);
        if (!(data instanceof Map<?, ?>)) return null;

        Map<?, ?> cfg = (Map<?, ?>) data;
        Set<String> hosts = new LinkedHashSet<>();
        addHostFromUrl(hosts, cfg.get("internal_url"));

        List<String> candidates = new ArrayList<>();
        for (String h : hosts) {
            candidates.addAll(buildHostPortCandidates(h));
        }
        return firstReachable(candidates, config);
    }

    private static Object callHaSync(PluginHost host, Map<String, Object> message) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Object> resultRef = new AtomicReference<>();
        try {
            host.haCall(message, new PluginHost.CommandCallback() {
                @Override
                public void onResult(boolean ok, Object data, String error) {
                    if (ok) {
                        resultRef.set(data);
                    }
                    latch.countDown();
                }
            });
            latch.await(HA_CALL_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
            // HA access unavailable, unauthorized, or interrupted: fall back silently.
        }
        return resultRef.get();
    }

    private static void addHostFromUrl(Set<String> hosts, Object rawUrl) {
        if (rawUrl == null) return;
        String s = String.valueOf(rawUrl).trim();
        if (s.isEmpty()) return;
        try {
            URL parsed = new URL(s);
            String h = parsed.getHost();
            if (h == null) return;
            h = h.trim();
            if (h.isEmpty() || isLoopbackHost(h)) return;
            hosts.add(h);
        } catch (Exception ignored) {}
    }

    private static boolean isLoopbackHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(lower) || "127.0.0.1".equals(lower) || "::1".equals(lower);
    }

    private static List<String> buildCandidatesFromEntryTitle(String title) {
        List<String> out = new ArrayList<>();
        String clean = title.trim();
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.isEmpty()) return out;

        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            try {
                URL parsed = new URL(clean);
                if (!isLoopbackHost(parsed.getHost())) {
                    out.add(clean);
                }
            } catch (Exception ignored) {}
            return out;
        }

        if (isLoopbackHost(clean)) return out;

        // Host:port without scheme (e.g. "192.168.1.50:5000")
        if (clean.matches("^[^/]+:\\d+$")) {
            if (clean.endsWith(":8971") || clean.endsWith(":443")) {
                out.add("https://" + clean);
                out.add("http://" + clean);
            } else {
                out.add("http://" + clean);
                out.add("https://" + clean);
            }
            return out;
        }

        // Bare host without port or scheme
        if (!clean.contains("/") && !clean.contains(" ")) {
            out.addAll(buildHostPortCandidates(clean));
        }
        return out;
    }

    private static List<String> buildHostPortCandidates(String host) {
        List<String> out = new ArrayList<>(2);
        String formattedHost = (host.contains(":") && !host.startsWith("[")) ? "[" + host + "]" : host;
        out.add("http://" + formattedHost + ":5000");
        out.add("https://" + formattedHost + ":8971");
        return out;
    }

    private static String firstReachable(List<String> candidates, FrigateConfig config) {
        if (candidates == null || candidates.isEmpty()) return null;
        Set<String> seen = new LinkedHashSet<>(candidates);
        for (String candidate : seen) {
            if (isFrigateReachable(candidate, config)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isFrigateReachable(String baseUrl, FrigateConfig config) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/api/version");
            conn = FrigateHttpHelper.openConnection(url, config);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(PROBE_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(PROBE_READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                return true;
            }
            if (code >= 200 && code < 300) {
                try (InputStream in = conn.getInputStream();
                     BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line = r.readLine();
                    return line != null && !line.trim().isEmpty() && !line.trim().startsWith("<");
                }
            }
        } catch (Exception ignored) {
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {}
            }
        }
        return false;
    }
}
