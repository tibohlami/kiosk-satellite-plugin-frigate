// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.frigate.ui;

import android.content.Context;
import android.os.Handler;
import android.view.View;
import android.widget.VideoView;
import me.jxl.kiosk.plugins.PluginHost;
import me.jxl.kiosk.plugins.frigate.api.FrigateHttpHelper;
import me.jxl.kiosk.plugins.frigate.config.FrigateConfig;
import me.jxl.kiosk.plugins.frigate.model.FrigateEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Manages Android {@link VideoView} listeners, local MP4 clip caching, background HTTP clip
 * downloads, and LRU cache pruning (up to 8 clips / 24h TTL) for {@link FrigatePlayerOverlay}.
 */
public class FrigateClipCacheManager {

    public interface Listener {
        boolean isClosed();
        boolean isLiveStreamMode();
        boolean isTimelineMode();
        boolean isHlsPlayback();
        boolean hasHlsFallbackTriggered();
        boolean isUserSeeking();
        boolean isLooping();
        boolean shouldLoopPlayback();
        FrigateEvent getEvent();
        String tr(String key, Object... args);
        void onHlsFallbackToMp4();
        void onVideoPrepared(int durationMs, int width, int height);
        void onVideoSizeChanged(int width, int height);
        void onVideoSeekCompleted(int posMs);
        void onVideoCompleted();
        void onVideoFatalError(String errorMessage);
    }

    private final Context context;
    private final PluginHost host;
    private final FrigateConfig config;
    private final Handler mainHandler;
    private final Listener listener;

    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private String currentCachedFilePath = null;
    private String originalEventClipPath = null;

    public FrigateClipCacheManager(Context context, PluginHost host, FrigateConfig config,
                                   Handler mainHandler, Listener listener) {
        this.context = context;
        this.host = host;
        this.config = config;
        this.mainHandler = mainHandler;
        this.listener = listener;
    }

    public String getCurrentCachedFilePath() {
        return currentCachedFilePath;
    }

    public void clearCurrentCachedFilePath() {
        this.currentCachedFilePath = null;
    }

    public boolean replayOriginalEventClipIfCached(VideoView videoView) {
        if (videoView != null && originalEventClipPath != null && new File(originalEventClipPath).exists()) {
            currentCachedFilePath = originalEventClipPath;
            if (host != null) {
                host.log("Frigate: Looping back to original event beginning (00:00)");
            }
            videoView.setVideoPath(originalEventClipPath);
            return true;
        }
        return false;
    }

    public void attachListeners(VideoView videoView) {
        if (videoView == null) return;
        videoView.setOnPreparedListener(mp -> {
            int dur = mp.getDuration();
            int vw = 0;
            int vh = 0;
            try {
                vw = mp.getVideoWidth();
                vh = mp.getVideoHeight();
                mp.setOnVideoSizeChangedListener((m, w, h) -> {
                    if (!listener.isClosed() && !listener.isLiveStreamMode() && w > 0 && h > 0) {
                        listener.onVideoSizeChanged(w, h);
                    }
                });
            } catch (Exception ignored) {}
            try {
                mp.setOnSeekCompleteListener(m -> {
                    if (listener.isClosed() || listener.isLiveStreamMode()) return;
                    listener.onVideoSeekCompleted(Math.max(0, videoView.getCurrentPosition()));
                });
            } catch (Exception ignored) {}
            try {
                mp.setLooping(false); // DO NOT loop in C++ layer; let OnCompletionListener handle looping to 00:00
            } catch (Exception ignored) {}
            listener.onVideoPrepared(dur, vw, vh);
        });

        videoView.setOnCompletionListener(mp -> {
            if (host != null) {
                host.log("Frigate: Video clip completed, isLooping=" + listener.isLooping());
            }
            listener.onVideoCompleted();
        });

        videoView.setOnErrorListener((mp, what, extra) -> {
            if (what == -38 || extra == -38) {
                // Non-fatal Android MediaPlayer state transition warning (e.g. at EOF/loop); ignore
                return true;
            }
            if (listener.isHlsPlayback() && !listener.hasHlsFallbackTriggered()) {
                if (host != null) {
                    host.log("Frigate: HLS VOD failed (what=" + what + ", extra=" + extra + "), falling back to clip.mp4");
                }
                android.util.Log.w("FrigatePlugin", "HLS VOD error " + what + "/" + extra + ", falling back to MP4");
                listener.onHlsFallbackToMp4();
                return true;
            }
            if (listener.isUserSeeking() || extra == -2147483648 || what == -2147483648) {
                if (host != null) {
                    host.log("Frigate: Recovering unseekable stream error, resetting player from local cache...");
                }
                android.util.Log.w("FrigatePlugin", "Recovering from stream error: " + what + "/" + extra);
                if (currentCachedFilePath != null) {
                    videoView.setVideoPath(currentCachedFilePath);
                }
                return true;
            }
            if (host != null) {
                host.log("Frigate: Video error (what=" + what + ", extra=" + extra + ")");
            }
            android.util.Log.e("FrigatePlugin", "Video error: what=" + what + ", extra=" + extra);

            String msg = listener.tr("player_error_video_code", what);
            FrigateEvent ev = listener.getEvent();
            if (extra == -1010) {
                msg = listener.tr("player_error_codec_1010");
            } else if (ev != null && (!ev.hasClip() || ev.isReviewFallback())) {
                msg = listener.tr("player_error_video_expired_avail");
            }
            listener.onVideoFatalError(msg);
            return true;
        });
    }

    public void playOrDownloadMp4(VideoView videoView, String videoUrl, String cleanKey, boolean isOriginalEvent) {
        if (videoView == null) return;
        File cacheDir = context.getCacheDir();
        File cachedFile = new File(cacheDir, cleanKey);

        if (cachedFile.exists() && cachedFile.length() > 1024) {
            currentCachedFilePath = cachedFile.getAbsolutePath();
            if (isOriginalEvent) {
                originalEventClipPath = currentCachedFilePath;
            }
            if (host != null) {
                host.log("Frigate: Playing cached clip: " + cachedFile.getName() + " (" + (cachedFile.length() / 1024) + " KB)");
            }
            android.util.Log.i("FrigatePlugin", "Playing cached clip: " + currentCachedFilePath);
            videoView.setVideoPath(currentCachedFilePath);
            return;
        }

        if (host != null) {
            host.log("Frigate: Downloading clip to local storage: " + videoUrl);
        }
        android.util.Log.i("FrigatePlugin", "Downloading clip to local storage: " + videoUrl);

        if (listener.isClosed() || downloadExecutor.isShutdown()) return;
        try {
            downloadExecutor.execute(() -> {
                HttpURLConnection conn = null;
                File tempFile = new File(cacheDir, cleanKey + ".part");
                try {
                    URL url = new URL(videoUrl);
                    conn = FrigateHttpHelper.openConnection(url, config);
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(20000);

                    FrigateHttpHelper.applyAuthHeaders(conn, config);

                    conn.connect();
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        throw new RuntimeException("HTTP " + code + " " + conn.getResponseMessage());
                    }

                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[32768];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            if (listener.isClosed() || listener.isLiveStreamMode()) {
                                tempFile.delete();
                                return;
                            }
                            out.write(buffer, 0, bytesRead);
                        }
                        out.flush();
                    }

                    if (tempFile.length() < 1024) {
                        tempFile.delete();
                        throw new RuntimeException(listener.tr("player_error_file_too_small", tempFile.length()));
                    }

                    tempFile.renameTo(cachedFile);
                    pruneOldClips(cacheDir);

                    if (listener.isClosed() || listener.isLiveStreamMode()) return;

                    mainHandler.post(() -> {
                        if (listener.isClosed() || listener.isLiveStreamMode()) return;
                        currentCachedFilePath = cachedFile.getAbsolutePath();
                        if (isOriginalEvent) {
                            originalEventClipPath = currentCachedFilePath;
                        }
                        if (host != null) {
                            host.log("Frigate: Clip downloaded (" + (cachedFile.length() / 1024) + " KB), playing from local storage");
                        }
                        android.util.Log.i("FrigatePlugin", "Clip downloaded: " + currentCachedFilePath);
                        videoView.setVideoPath(currentCachedFilePath);
                    });
                } catch (Exception e) {
                    if (tempFile.exists()) {
                        tempFile.delete();
                    }
                    if (listener.isClosed() || listener.isLiveStreamMode()) return;
                    mainHandler.post(() -> {
                        if (listener.isClosed() || listener.isLiveStreamMode()) return;
                        if (host != null) {
                            host.log("Frigate: Download failed: " + e.getMessage());
                        }
                        android.util.Log.e("FrigatePlugin", "Download failed", e);
                        FrigateEvent ev = listener.getEvent();
                        String msg = (ev != null && (!ev.hasClip() || ev.isReviewFallback()))
                                ? listener.tr("player_error_video_expired_avail")
                                : listener.tr("player_error_download_failed", e.getMessage() != null ? e.getMessage() : "");
                        listener.onVideoFatalError(msg);
                    });
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    private void pruneOldClips(File cacheDir) {
        try {
            File[] files = cacheDir.listFiles((dir, name) -> name.startsWith("frigate_") && (name.endsWith(".mp4") || name.endsWith(".part")));
            if (files == null) return;
            long now = System.currentTimeMillis();

            // 1. Delete abandoned temporary downloads (.part) older than 5 minutes
            // and clips older than 24 hours
            for (File f : files) {
                if (f.getName().endsWith(".part") && (now - f.lastModified() > 300_000)) {
                    f.delete();
                } else if (now - f.lastModified() > 86_400_000) {
                    f.delete();
                }
            }

            // 2. Keep strictly at most the 8 latest clips (~40-60 MB max)
            files = cacheDir.listFiles((dir, name) -> name.startsWith("frigate_") && name.endsWith(".mp4"));
            if (files != null && files.length > 8) {
                Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i < files.length - 8; i++) {
                    if (originalEventClipPath != null && files[i].getAbsolutePath().equals(originalEventClipPath)) {
                        continue;
                    }
                    files[i].delete();
                }
            }
        } catch (Exception ignored) {}
    }

    public void destroy() {
        try {
            downloadExecutor.shutdownNow();
        } catch (Exception ignored) {}
    }
}
