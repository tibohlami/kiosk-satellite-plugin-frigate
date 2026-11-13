package io.github.tibohlami.ksplugins.frigate.api;

import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Localhost-only WebSocket TCP/TLS relay used when allowInsecureSsl is enabled
 * with an HTTPS Frigate server, because Android Chromium WebView blocks wss://
 * handshakes with self-signed certificates without invoking onReceivedSslError.
 */
public final class WsTlsRelay implements Closeable {

    private static final int MAX_HEADER_BYTES = 16384;

    private final FrigateConfig config;
    private final String upstreamScheme;
    private final String upstreamHost;
    private final int upstreamPort;
    private final String hostHeader;
    private final String upstreamPathPrefix;
    private final boolean useTls;
    private final String secret;
    private final ServerSocket serverSocket;
    private final Set<Socket> activeSockets = Collections.synchronizedSet(new HashSet<Socket>());
    private volatile boolean closed = false;

    public WsTlsRelay(FrigateConfig config, String baseUrl) throws IOException {
        this.config = config;
        URL upstream = new URL(baseUrl);
        this.upstreamScheme = upstream.getProtocol().toLowerCase();
        this.useTls = "https".equals(this.upstreamScheme);
        this.upstreamHost = upstream.getHost();
        this.upstreamPort = upstream.getPort() > 0 ? upstream.getPort() : (this.useTls ? 443 : 80);
        this.hostHeader = upstream.getPort() > 0 ? (this.upstreamHost + ":" + this.upstreamPort) : this.upstreamHost;
        String rawPath = upstream.getPath() != null ? upstream.getPath().replaceAll("/+$", "") : "";
        this.upstreamPathPrefix = rawPath;
        this.secret = UUID.randomUUID().toString().replace("-", "");

        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        this.serverSocket = new ServerSocket(0, 4, loopback);

        Thread acceptThread = new Thread(() -> {
            try {
                acceptLoop();
            } catch (Throwable ignored) {}
        }, "FrigateWsRelay-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private static final int MAX_ACTIVE_CLIENTS = 2;

    public String getLocalWsUrl(String relativePathAndQuery) {
        String rel = relativePathAndQuery != null ? relativePathAndQuery : "";
        if (!rel.isEmpty() && !rel.startsWith("/")) {
            rel = "/" + rel;
        }
        return "ws://127.0.0.1:" + serverSocket.getLocalPort() + "/" + secret + upstreamPathPrefix + rel;
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                final Socket client = serverSocket.accept();
                if (closed) {
                    closeQuietly(client);
                    break;
                }
                if (activeSockets.size() >= MAX_ACTIVE_CLIENTS) {
                    closeQuietly(client);
                    continue;
                }
                activeSockets.add(client);
                Thread connThread = new Thread(() -> {
                    try {
                        handleClient(client);
                    } catch (Throwable ignored) {}
                }, "FrigateWsRelay-Conn");
                connThread.setDaemon(true);
                connThread.start();
            } catch (Throwable e) {
                if (!closed) {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) { break; }
                }
            }
        }
    }

    private void handleClient(Socket client) {
        Socket remote = null;
        try {
            client.setTcpNoDelay(true);
            InputStream clientIn = client.getInputStream();
            OutputStream clientOut = client.getOutputStream();

            String rawHeaders = readHttpHeaderBlock(clientIn);
            if (rawHeaders == null) {
                return;
            }
            String[] lines = rawHeaders.split("\r\n");
            if (lines.length == 0) {
                return;
            }
            String[] reqParts = lines[0].split(" ");
            if (reqParts.length < 2 || !"GET".equalsIgnoreCase(reqParts[0])) {
                clientOut.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                clientOut.flush();
                return;
            }

            String expectedPrefix = "/" + secret;
            String reqTarget = reqParts[1];
            if (!reqTarget.startsWith(expectedPrefix)) {
                clientOut.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                clientOut.flush();
                return;
            }
            String upstreamPath = reqTarget.substring(expectedPrefix.length());
            if (upstreamPath.isEmpty()) {
                upstreamPath = "/";
            }

            // Strictly restrict forwarded requests to Frigate's MSE WebSocket endpoint
            String pathOnly = upstreamPath.contains("?") ? upstreamPath.substring(0, upstreamPath.indexOf('?')) : upstreamPath;
            String allowedPrefix = upstreamPathPrefix + "/live/mse/api/ws";
            if (!pathOnly.equals(allowedPrefix) && !pathOnly.equals("/live/mse/api/ws")) {
                clientOut.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                clientOut.flush();
                return;
            }

            StringBuilder rebuilt = new StringBuilder();
            rebuilt.append("GET ").append(upstreamPath).append(" HTTP/1.1\r\n");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String nameLower = line.substring(0, colon).trim().toLowerCase();
                if ("host".equals(nameLower)
                        || "origin".equals(nameLower)
                        || "cookie".equals(nameLower)
                        || "authorization".equals(nameLower)) {
                    continue;
                }
                rebuilt.append(line).append("\r\n");
            }
            rebuilt.append("Host: ").append(hostHeader).append("\r\n");
            rebuilt.append("Origin: ").append(upstreamScheme).append("://").append(hostHeader).append("\r\n");

            String token = config != null ? config.getAuthToken() : "";
            if (token != null && !token.trim().isEmpty()) {
                String t = token.trim();
                if (t.startsWith("Bearer ") || t.startsWith("Basic ")) {
                    rebuilt.append("Authorization: ").append(t).append("\r\n");
                } else {
                    rebuilt.append("Authorization: Bearer ").append(t).append("\r\n");
                    rebuilt.append("Cookie: frigate_token=").append(t).append("\r\n");
                }
            }
            rebuilt.append("\r\n");

            boolean allowInsecure = config != null && config.isAllowInsecureSsl();
            remote = FrigateHttpHelper.openSocket(upstreamHost, upstreamPort, useTls, allowInsecure, upstreamHost, 8000);
            remote.setTcpNoDelay(true);
            activeSockets.add(remote);

            final Socket finalRemote = remote;
            OutputStream remoteOut = remote.getOutputStream();
            InputStream remoteIn = remote.getInputStream();

            remoteOut.write(rebuilt.toString().getBytes(StandardCharsets.US_ASCII));
            remoteOut.flush();

            Thread upstreamToClient = new Thread(() -> {
                try {
                    pump(remoteIn, clientOut);
                } catch (Throwable ignored) {
                } finally {
                    closeQuietly(client);
                    closeQuietly(finalRemote);
                }
            }, "FrigateWsRelay-Down");
            upstreamToClient.setDaemon(true);
            upstreamToClient.start();

            pump(clientIn, remoteOut);
        } catch (Throwable ignored) {
        } finally {
            if (remote != null) {
                activeSockets.remove(remote);
                closeQuietly(remote);
            }
            activeSockets.remove(client);
            closeQuietly(client);
        }
    }

    private static String readHttpHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0; // tracks \r\n\r\n
        while (buf.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b == -1) return null;
            buf.write(b);
            if ((state == 0 || state == 2) && b == '\r') {
                state++;
            } else if ((state == 1 || state == 3) && b == '\n') {
                state++;
                if (state == 4) {
                    return new String(buf.toByteArray(), 0, buf.size() - 4, StandardCharsets.US_ASCII);
                }
            } else {
                state = (b == '\r') ? 1 : 0;
            }
        }
        return null;
    }

    private void pump(InputStream in, OutputStream out) {
        byte[] buffer = new byte[16384];
        try {
            int n;
            while (!closed && (n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {}
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(serverSocket);
        synchronized (activeSockets) {
            for (Socket s : activeSockets) {
                closeQuietly(s);
            }
            activeSockets.clear();
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try { c.close(); } catch (IOException ignored) {}
        }
    }
}
