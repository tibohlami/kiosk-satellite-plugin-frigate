package me.jxl.kiosk.plugins.frigate.api;

import me.jxl.kiosk.plugins.frigate.config.FrigateConfig;

import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Shared HTTP/TLS connection helper applying optional untrusted-certificate
 * acceptance strictly to connections opened by this plugin when enabled in config.
 */
public final class FrigateHttpHelper {

    private static volatile SSLSocketFactory trustAllSocketFactory;

    private static final HostnameVerifier TRUST_ALL_HOSTNAMES = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    private FrigateHttpHelper() {}

    public static HttpURLConnection openConnection(URL url, FrigateConfig config) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        applyTls(conn, config);
        return conn;
    }

    public static void applyTls(HttpURLConnection conn, FrigateConfig config) {
        if (conn instanceof HttpsURLConnection && config != null && config.isAllowInsecureSsl()) {
            HttpsURLConnection https = (HttpsURLConnection) conn;
            SSLSocketFactory sf = getTrustAllSocketFactory();
            if (sf != null) {
                https.setSSLSocketFactory(sf);
            }
            https.setHostnameVerifier(TRUST_ALL_HOSTNAMES);
        }
    }

    public static Socket openSocket(String host, int port, boolean useTls, boolean allowInsecure, int timeoutMs) throws IOException {
        if (!useTls) {
            Socket plain = new Socket();
            plain.connect(new InetSocketAddress(host, port), timeoutMs);
            return plain;
        }
        SSLSocketFactory sf = allowInsecure
                ? getTrustAllSocketFactory()
                : (SSLSocketFactory) SSLSocketFactory.getDefault();
        if (sf == null) {
            throw new IOException("TLS socket factory unavailable");
        }
        SSLSocket sslSocket = (SSLSocket) sf.createSocket();
        sslSocket.connect(new InetSocketAddress(host, port), timeoutMs);
        sslSocket.startHandshake();
        if (!allowInsecure) {
            HostnameVerifier defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
            if (defaultVerifier != null && !defaultVerifier.verify(host, sslSocket.getSession())) {
                try { sslSocket.close(); } catch (IOException ignored) {}
                throw new IOException("Hostname verification failed for " + host);
            }
        }
        return sslSocket;
    }

    public static Map<String, String> buildAuthHeaders(FrigateConfig config) {
        return buildAuthHeaders(config != null ? config.getAuthToken() : null);
    }

    public static Map<String, String> buildAuthHeaders(String token) {
        Map<String, String> headers = new HashMap<>();
        if (token == null) return headers;
        String trimmed = token.trim();
        if (trimmed.isEmpty()) return headers;
        if (trimmed.startsWith("Bearer ") || trimmed.startsWith("Basic ")) {
            headers.put("Authorization", trimmed);
        } else {
            headers.put("Authorization", "Bearer " + trimmed);
            headers.put("Cookie", "frigate_token=" + trimmed);
        }
        return headers;
    }

    public static void applyAuthHeaders(HttpURLConnection conn, FrigateConfig config) {
        applyAuthHeaders(conn, config != null ? config.getAuthToken() : null);
    }

    public static void applyAuthHeaders(HttpURLConnection conn, String token) {
        if (conn == null) return;
        for (Map.Entry<String, String> entry : buildAuthHeaders(token).entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
    }

    public static SSLSocketFactory getTrustAllSocketFactory() {
        SSLSocketFactory existing = trustAllSocketFactory;
        if (existing != null) {
            return existing;
        }
        synchronized (FrigateHttpHelper.class) {
            if (trustAllSocketFactory != null) {
                return trustAllSocketFactory;
            }
            try {
                TrustManager[] trustAll = new TrustManager[]{
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAll, new SecureRandom());
                trustAllSocketFactory = sc.getSocketFactory();
                return trustAllSocketFactory;
            } catch (Exception e) {
                return null;
            }
        }
    }
}
