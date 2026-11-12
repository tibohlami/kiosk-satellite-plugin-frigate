// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.frigate.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import me.jxl.kiosk.plugins.frigate.api.FrigateApiClient;
import me.jxl.kiosk.plugins.frigate.api.FrigateHttpHelper;
import me.jxl.kiosk.plugins.frigate.config.FrigateConfig;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streams real-time multipart MJPEG frames from Frigate over HTTP with zero-delay boundary parsing.
 * Used as the automatic live fallback when go2rtc MSE is unavailable.
 */
public class FrigateMjpegStreamController {

    public interface Listener {
        boolean isClosed();
        boolean isLiveStreamMode();
        boolean isLivePaused();
        int getStreamSessionId();
        void onMjpegStarting(int sessionId);
        void onMjpegFrame(int sessionId, Bitmap frame);
        void onMjpegFps(int sessionId, int fps);
        void onMjpegError(int sessionId, String errorMessage);
    }

    private final FrigateApiClient apiClient;
    private final FrigateConfig config;
    private final Handler mainHandler;
    private final Listener listener;

    private volatile Thread mjpegThread;
    private volatile HttpURLConnection activeMjpegConn;

    public FrigateMjpegStreamController(FrigateApiClient apiClient, FrigateConfig config,
                                        Handler mainHandler, Listener listener) {
        this.apiClient = apiClient;
        this.config = config;
        this.mainHandler = mainHandler;
        this.listener = listener;
    }

    public void start(String camera, int sessionId) {
        if (listener.isClosed() || !listener.isLiveStreamMode() || sessionId != listener.getStreamSessionId()) {
            return;
        }
        listener.onMjpegStarting(sessionId);
        final String streamUrl = apiClient.getLiveMjpegUrl(camera, 8, 720);
        Thread t = new Thread(() -> runMjpegLoop(sessionId, streamUrl), "FrigateLiveMJPEG");
        mjpegThread = t;
        t.start();
    }

    public void stop() {
        final HttpURLConnection conn = activeMjpegConn;
        activeMjpegConn = null;
        if (conn != null) {
            new Thread(() -> {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {}
            }).start();
        }
        Thread t = mjpegThread;
        mjpegThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private boolean isStreamActive(int sessionId) {
        return !listener.isClosed()
                && listener.isLiveStreamMode()
                && !listener.isLivePaused()
                && sessionId == listener.getStreamSessionId();
    }

    private void runMjpegLoop(int sessionId, String streamUrl) {
        int consecutiveErrors = 0;
        while (isStreamActive(sessionId)) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(streamUrl);
                conn = FrigateHttpHelper.openConnection(url, config);
                activeMjpegConn = conn;
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setUseCaches(false);
                conn.setRequestProperty("Cache-Control", "no-cache");

                FrigateHttpHelper.applyAuthHeaders(conn, config);

                conn.connect();
                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new IOException("HTTP " + code + " " + conn.getResponseMessage());
                }

                consecutiveErrors = 0;
                BufferedInputStream bis = new BufferedInputStream(conn.getInputStream(), 65536);
                int fpsFrameCount = 0;
                long fpsWindowStartMs = android.os.SystemClock.elapsedRealtime();
                final AtomicReference<Bitmap> latestFrame = new AtomicReference<>();

                while (isStreamActive(sessionId)) {
                    byte[] jpegBytes = readNextJpegFrame(bis);
                    if (jpegBytes == null) {
                        break; // EOF
                    }
                    final Bitmap bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
                    if (bmp != null) {
                        fpsFrameCount++;
                        long nowMs = android.os.SystemClock.elapsedRealtime();
                        long elapsedMs = nowMs - fpsWindowStartMs;
                        final int computedFps = (elapsedMs >= 1000L)
                                ? Math.round((fpsFrameCount * 1000f) / elapsedMs)
                                : -1;
                        if (computedFps >= 0) {
                            fpsFrameCount = 0;
                            fpsWindowStartMs = nowMs;
                        }
                        // Coalesce pending frames so slow UI threads drop stale frames instead of queueing lag.
                        boolean wasEmpty = (latestFrame.getAndSet(bmp) == null);
                        if (wasEmpty || computedFps >= 0) {
                            mainHandler.post(() -> {
                                if (!isStreamActive(sessionId)) {
                                    latestFrame.set(null);
                                    return;
                                }
                                Bitmap next = latestFrame.getAndSet(null);
                                if (next != null) {
                                    listener.onMjpegFrame(sessionId, next);
                                }
                                if (computedFps >= 0) {
                                    listener.onMjpegFps(sessionId, computedFps);
                                }
                            });
                        }
                    }
                }
            } catch (Exception e) {
                consecutiveErrors++;
                if (!isStreamActive(sessionId)) {
                    break;
                }
                if (consecutiveErrors >= 3) {
                    final String errMsg = e.getMessage();
                    mainHandler.post(() -> {
                        if (listener.isClosed() || sessionId != listener.getStreamSessionId()) return;
                        listener.onMjpegError(sessionId, errMsg != null ? errMsg : "");
                    });
                    break;
                }
                try {
                    Thread.sleep(800);
                } catch (InterruptedException ie) {
                    break;
                }
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Exception ignored) {}
                }
                if (activeMjpegConn == conn) {
                    activeMjpegConn = null;
                }
            }
        }
    }

    /**
     * Extracts the next JPEG frame from a multipart/x-mixed-replace MJPEG stream with zero trailing-frame wait.
     * Supports both Content-Length headers and raw JPEG SOI (FF D8) .. Scan EOI (FF D9) framing.
     */
    private static byte[] readNextJpegFrame(BufferedInputStream bis) throws IOException {
        int contentLength = -1;
        StringBuilder lineBuf = new StringBuilder();
        boolean sawBlankAfterHeader = false;

        while (true) {
            bis.mark(4);
            int b1 = bis.read();
            if (b1 == -1) return null;
            if (b1 == 0xFF) {
                int b2 = bis.read();
                if (b2 == 0xD8) {
                    bis.reset();
                    break;
                }
                bis.reset();
                b1 = bis.read();
            }

            if (b1 == '\n') {
                String line = lineBuf.toString().trim();
                lineBuf.setLength(0);
                if (line.isEmpty()) {
                    if (sawBlankAfterHeader || contentLength > 0) {
                        break;
                    }
                } else {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("content-length:")) {
                        try {
                            contentLength = Integer.parseInt(lower.substring("content-length:".length()).trim());
                        } catch (NumberFormatException ignored) {}
                    }
                    if (lower.startsWith("content-type:") || lower.startsWith("content-length:") || lower.startsWith("--")) {
                        sawBlankAfterHeader = true;
                    }
                }
            } else if (b1 != '\r') {
                if (lineBuf.length() < 512) {
                    lineBuf.append((char) b1);
                }
            }
        }

        if (contentLength > 0 && contentLength < 8 * 1024 * 1024) {
            byte[] buf = new byte[contentLength];
            int offset = 0;
            while (offset < contentLength) {
                int read = bis.read(buf, offset, contentLength - offset);
                if (read == -1) return null;
                offset += read;
            }
            return buf;
        }

        // Fallback: Scan SOI (FF D8) -> SOS (FF DA) -> EOI (FF D9)
        int prev = -1;
        boolean foundSoi = false;
        while (true) {
            int cur = bis.read();
            if (cur == -1) return null;
            if (prev == 0xFF && cur == 0xD8) {
                foundSoi = true;
                break;
            }
            prev = cur;
        }
        if (!foundSoi) return null;

        ByteArrayOutputStream frameOut = new ByteArrayOutputStream(65536);
        frameOut.write(0xFF);
        frameOut.write(0xD8);

        prev = -1;
        boolean inScanData = false;
        while (frameOut.size() < 4 * 1024 * 1024) {
            int cur = bis.read();
            if (cur == -1) return null;
            frameOut.write(cur);
            if (prev == 0xFF) {
                if (cur == 0xDA) { // Start Of Scan (SOS): compressed entropy data follows
                    inScanData = true;
                } else if (cur == 0xD9 && inScanData) { // End Of Image (EOI)
                    break;
                }
            }
            prev = cur;
        }
        return frameOut.toByteArray();
    }
}
