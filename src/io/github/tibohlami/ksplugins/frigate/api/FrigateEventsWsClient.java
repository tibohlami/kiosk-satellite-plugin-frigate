// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.api;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import me.jxl.kiosk.plugins.PluginHost;
import org.json.JSONObject;

/**
 * Lightweight RFC 6455 WebSocket client for Frigate's {@code /ws} endpoint.
 * Delivers real-time {@code events} and {@code reviews} messages to the UI thread
 * with automatic reconnection and TLS/auth support via {@link FrigateHttpHelper}.
 */
public final class FrigateEventsWsClient implements Closeable {

    public interface Listener {
        void onWsConnected();
        void onWsDisconnected();
        void onFrigateEventMessage(String type, JSONObject afterObj, JSONObject beforeObj);
        void onFrigateReviewMessage(String type, JSONObject afterObj, JSONObject beforeObj);
        default void onFrigateMotionMessage(String camera, boolean active) {}
    }

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final long PING_INTERVAL_MS = 25_000L;
    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    private static final long INITIAL_RETRY_MS = 2_000L;
    private static final long MAX_RETRY_MS = 15_000L;

    private static final Object SHARED_LOCK = new Object();
    private static FrigateEventsWsClient sharedInstance;

    public static FrigateEventsWsClient acquireShared(FrigateConfig config, PluginHost host, Listener listener) {
        if (listener == null) return null;
        synchronized (SHARED_LOCK) {
            if (sharedInstance == null || sharedInstance.closed) {
                sharedInstance = new FrigateEventsWsClient(config, host, null);
                sharedInstance.addListener(listener);
                sharedInstance.start();
            } else {
                sharedInstance.addListener(listener);
                final FrigateEventsWsClient instance = sharedInstance;
                if (instance.isConnected()) {
                    instance.mainHandler.post(() -> {
                        if (!instance.closed && instance.listeners.contains(listener)) {
                            listener.onWsConnected();
                        }
                    });
                }
            }
            return sharedInstance;
        }
    }

    public static void releaseShared(Listener listener) {
        if (listener == null) return;
        synchronized (SHARED_LOCK) {
            if (sharedInstance == null) return;
            sharedInstance.removeListener(listener);
            if (sharedInstance.listeners.isEmpty()) {
                sharedInstance.close();
                sharedInstance = null;
            }
        }
    }

    public static void shutdownShared() {
        synchronized (SHARED_LOCK) {
            if (sharedInstance != null) {
                sharedInstance.close();
                sharedInstance = null;
            }
        }
    }

    private final FrigateConfig config;
    private final PluginHost host;
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();

    private volatile boolean closed = false;
    private volatile boolean connected = false;
    private volatile Socket activeSocket;
    private volatile OutputStream activeOutputStream;
    private final Object writeLock = new Object();
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FrigateEventsWs-Send");
        t.setDaemon(true);
        return t;
    });
    private Thread workerThread;

    public FrigateEventsWsClient(FrigateConfig config, PluginHost host, Listener listener) {
        this.config = config;
        this.host = host;
        if (listener != null) {
            this.listeners.add(listener);
        }
    }

    public void addListener(Listener listener) {
        if (listener != null && !closed) {
            this.listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        if (listener != null) {
            this.listeners.remove(listener);
        }
    }

    public boolean isConnected() {
        return connected && !closed;
    }

    public void start() {
        if (closed || workerThread != null) return;
        workerThread = new Thread(this::runLoop, "FrigateEventsWs");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    @Override
    public void close() {
        closed = true;
        connected = false;
        activeOutputStream = null;
        listeners.clear();
        mainHandler.removeCallbacksAndMessages(null);
        sendExecutor.shutdownNow();
        closeActiveSocket();
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    public boolean sendTopicPayload(String topic, String payload) {
        if (closed || !connected || topic == null || topic.isEmpty()) return false;
        final OutputStream out = activeOutputStream;
        if (out == null) return false;
        try {
            JSONObject msg = new JSONObject();
            msg.put("topic", topic);
            msg.put("payload", payload != null ? payload : "");
            final byte[] bytes = msg.toString().getBytes(StandardCharsets.UTF_8);
            sendExecutor.execute(() -> {
                if (!closed && connected && activeOutputStream == out) {
                    sendFrame(out, 0x1, bytes);
                }
            });
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void closeActiveSocket() {
        Socket s = activeSocket;
        activeSocket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {}
        }
    }

    private void runLoop() {
        long retryDelayMs = INITIAL_RETRY_MS;
        while (!closed) {
            boolean wasConnected = false;
            try {
                connectAndRead();
                wasConnected = true;
                retryDelayMs = INITIAL_RETRY_MS;
            } catch (Throwable t) {
                if (!closed) {
                    logDebug("Frigate: /ws disconnected (" + t.getMessage() + ")");
                }
            } finally {
                if (connected || wasConnected) {
                    connected = false;
                    mainHandler.post(() -> {
                        if (!closed) {
                            for (Listener l : listeners) l.onWsDisconnected();
                        }
                    });
                }
                closeActiveSocket();
            }

            if (closed) break;
            try {
                Thread.sleep(retryDelayMs);
            } catch (InterruptedException ignored) {
                if (closed) break;
            }
            retryDelayMs = Math.min(MAX_RETRY_MS, retryDelayMs * 2);
        }
    }

    private void connectAndRead() throws IOException {
        String frigateUrl = (config != null) ? config.getFrigateUrl() : null;
        if (frigateUrl == null || frigateUrl.isEmpty()) {
            throw new IOException("Missing Frigate URL");
        }
        URL base = new URL(frigateUrl);
        String scheme = base.getProtocol().toLowerCase();
        boolean useTls = "https".equals(scheme);
        String targetHost = base.getHost();
        int port = base.getPort() > 0 ? base.getPort() : (useTls ? 443 : 80);
        String hostHeader = base.getPort() > 0 ? (targetHost + ":" + port) : targetHost;
        String basePath = base.getPath() != null ? base.getPath().replaceAll("/+$", "") : "";
        String wsPath = basePath + "/ws";

        Socket socket = FrigateHttpHelper.openSocket(
                targetHost, port, useTls, config.isAllowInsecureSsl(), targetHost, CONNECT_TIMEOUT_MS);
        activeSocket = socket;
        if (closed) {
            closeActiveSocket();
            return;
        }
        socket.setSoTimeout(READ_TIMEOUT_MS);

        InputStream in = new BufferedInputStream(socket.getInputStream(), 16384);
        OutputStream out = socket.getOutputStream();
        activeOutputStream = out;

        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        String wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP);

        StringBuilder req = new StringBuilder();
        req.append("GET ").append(wsPath).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(hostHeader).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        Map<String, String> authHeaders = FrigateHttpHelper.buildAuthHeaders(config);
        for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
            req.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        req.append("\r\n");
        out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String statusLine = readLineCrlf(in);
        if (statusLine == null || !statusLine.contains("101")) {
            throw new IOException("WebSocket upgrade failed: " + statusLine);
        }
        for (int headerCount = 0; headerCount < 64; headerCount++) {
            String line = readLineCrlf(in);
            if (line == null || line.isEmpty()) break;
        }

        connected = true;
        logDebug("Frigate: /ws connected (" + wsPath + ")");
        mainHandler.post(() -> {
            if (!closed) {
                for (Listener l : listeners) l.onWsConnected();
            }
        });

        Thread pingThread = new Thread(() -> {
            while (!closed && connected && activeSocket == socket) {
                try {
                    Thread.sleep(PING_INTERVAL_MS);
                } catch (InterruptedException ignored) {
                    break;
                }
                if (closed || !connected || activeSocket != socket) break;
                if (!sendControlFrame(out, 0x9, new byte[0])) {
                    closeActiveSocket();
                    break;
                }
            }
        }, "FrigateEventsWs-Ping");
        pingThread.setDaemon(true);
        pingThread.start();

        try {
            readFrames(in, out);
        } finally {
            pingThread.interrupt();
        }
    }

    private void readFrames(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream fragBuffer = null;
        int fragOpcode = -1;

        while (!closed) {
            int b0 = in.read();
            if (b0 < 0) break;
            int b1 = in.read();
            if (b1 < 0) break;

            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;

            if (len == 126) {
                int hi = in.read();
                int lo = in.read();
                if (hi < 0 || lo < 0) throw new EOFException();
                len = ((hi & 0xFF) << 8) | (lo & 0xFF);
            } else if (len == 127) {
                long v = 0;
                for (int i = 0; i < 8; i++) {
                    int b = in.read();
                    if (b < 0) throw new EOFException();
                    v = (v << 8) | (b & 0xFFL);
                }
                len = v;
            }

            if (len < 0 || len > MAX_FRAME_BYTES) {
                throw new IOException("WebSocket frame too large: " + len);
            }

            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask, 4);
            }

            byte[] payload = new byte[(int) len];
            if (len > 0) {
                readFully(in, payload, (int) len);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i & 3];
                    }
                }
            }

            if (opcode == 0x8) {
                sendControlFrame(out, 0x8, new byte[0]);
                break;
            } else if (opcode == 0x9) {
                sendControlFrame(out, 0xA, payload);
                continue;
            } else if (opcode == 0xA) {
                continue;
            }

            if (opcode == 0x1 || opcode == 0x2) {
                if (fin) {
                    if (opcode == 0x1) {
                        handleTextMessage(new String(payload, StandardCharsets.UTF_8));
                    }
                } else {
                    fragOpcode = opcode;
                    fragBuffer = new ByteArrayOutputStream();
                    fragBuffer.write(payload);
                }
            } else if (opcode == 0x0 && fragBuffer != null) {
                if (fragBuffer.size() + payload.length > MAX_FRAME_BYTES) {
                    throw new IOException("Fragmented WebSocket message too large");
                }
                fragBuffer.write(payload);
                if (fin) {
                    if (fragOpcode == 0x1) {
                        handleTextMessage(new String(fragBuffer.toByteArray(), StandardCharsets.UTF_8));
                    }
                    fragBuffer = null;
                    fragOpcode = -1;
                }
            }
        }
    }

    private void handleTextMessage(String text) {
        if (closed || listeners.isEmpty() || text == null || text.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(text);
            String topic = root.optString("topic", "");
            if (topic.endsWith("/motion")) {
                final String cam = topic.substring(0, topic.length() - "/motion".length());
                final String raw = root.optString("payload", "");
                final boolean active = "ON".equalsIgnoreCase(raw.trim()) || "true".equalsIgnoreCase(raw.trim());
                if (!cam.isEmpty()) {
                    logDebug("Frigate: /ws motion cam=" + cam + " active=" + active);
                    mainHandler.post(() -> {
                        if (!closed) {
                            for (Listener l : listeners) l.onFrigateMotionMessage(cam, active);
                        }
                    });
                }
                return;
            }
            if (!"events".equals(topic) && !"reviews".equals(topic)) return;

            JSONObject payload = root.optJSONObject("payload");
            if (payload == null) {
                String payloadStr = root.optString("payload", "");
                if (payloadStr.isEmpty() || !payloadStr.trim().startsWith("{")) return;
                payload = new JSONObject(payloadStr);
            }
            final String type = payload.optString("type", "");
            final JSONObject afterObj = payload.optJSONObject("after");
            final JSONObject beforeObj = payload.optJSONObject("before");
            if (config != null && config.isDebugLogging()) {
                JSONObject ref = afterObj != null ? afterObj : beforeObj;
                String cam = ref != null ? ref.optString("camera", "") : "";
                String id = ref != null ? ref.optString("id", "") : "";
                if ("events".equals(topic)) {
                    String lbl = ref != null ? ref.optString("label", "") : "";
                    String sev = ref != null ? ref.optString("severity", "") : "";
                    logDebug("Frigate: /ws event " + type + " cam=" + cam + " id=" + id + " label=" + lbl + " sev=" + sev);
                } else {
                    String sev = ref != null ? ref.optString("severity", "") : "";
                    logDebug("Frigate: /ws review " + type + " cam=" + cam + " id=" + id + " sev=" + sev);
                }
            }

            mainHandler.post(() -> {
                if (closed) return;
                if ("events".equals(topic)) {
                    for (Listener l : listeners) l.onFrigateEventMessage(type, afterObj, beforeObj);
                } else if ("reviews".equals(topic)) {
                    for (Listener l : listeners) l.onFrigateReviewMessage(type, afterObj, beforeObj);
                }
            });
        } catch (Throwable ignored) {}
    }

    private boolean sendControlFrame(OutputStream out, int opcode, byte[] payload) {
        int len = Math.min(payload != null ? payload.length : 0, 125);
        byte[] capped = payload;
        if (payload != null && payload.length > 125) {
            capped = new byte[125];
            System.arraycopy(payload, 0, capped, 0, 125);
        }
        return sendFrame(out, opcode, capped);
    }

    private boolean sendFrame(OutputStream out, int opcode, byte[] payload) {
        synchronized (writeLock) {
            try {
                int len = payload != null ? payload.length : 0;
                if (len > 65535) return false;
                byte[] mask = new byte[4];
                random.nextBytes(mask);
                out.write(0x80 | (opcode & 0x0F));
                if (len <= 125) {
                    out.write(0x80 | len);
                } else {
                    out.write(0x80 | 126);
                    out.write((len >> 8) & 0xFF);
                    out.write(len & 0xFF);
                }
                out.write(mask);
                for (int i = 0; i < len; i++) {
                    out.write(payload[i] ^ mask[i & 3]);
                }
                out.flush();
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private static String readLineCrlf(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
            if (buf.size() > 8192) throw new IOException("Header line too long");
        }
        if (b == -1 && buf.size() == 0) return null;
        return new String(buf.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static void readFully(InputStream in, byte[] dst, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(dst, off, len - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }

    private void logDebug(String msg) {
        if (config != null && config.isDebugLogging()) {
            if (host != null) host.log(msg);
            android.util.Log.i("FrigatePlugin", msg);
        }
    }
}
