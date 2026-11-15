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
    }

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final long PING_INTERVAL_MS = 25_000L;
    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    private static final long INITIAL_RETRY_MS = 2_000L;
    private static final long MAX_RETRY_MS = 15_000L;

    private final FrigateConfig config;
    private final PluginHost host;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();

    private volatile boolean closed = false;
    private volatile boolean connected = false;
    private volatile Socket activeSocket;
    private final Object writeLock = new Object();
    private Thread workerThread;

    public FrigateEventsWsClient(FrigateConfig config, PluginHost host, Listener listener) {
        this.config = config;
        this.host = host;
        this.listener = listener;
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
        mainHandler.removeCallbacksAndMessages(null);
        closeActiveSocket();
        if (workerThread != null) {
            workerThread.interrupt();
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
                        if (!closed && listener != null) listener.onWsDisconnected();
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
            if (!closed && listener != null) listener.onWsConnected();
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
        if (closed || listener == null || text == null || text.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(text);
            String topic = root.optString("topic", "");
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

            mainHandler.post(() -> {
                if (closed || listener == null) return;
                if ("events".equals(topic)) {
                    listener.onFrigateEventMessage(type, afterObj, beforeObj);
                } else if ("reviews".equals(topic)) {
                    listener.onFrigateReviewMessage(type, afterObj, beforeObj);
                }
            });
        } catch (Throwable ignored) {}
    }

    private boolean sendControlFrame(OutputStream out, int opcode, byte[] payload) {
        synchronized (writeLock) {
            try {
                int len = Math.min(payload != null ? payload.length : 0, 125);
                byte[] mask = new byte[4];
                random.nextBytes(mask);
                out.write(0x80 | (opcode & 0x0F));
                out.write(0x80 | len);
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
