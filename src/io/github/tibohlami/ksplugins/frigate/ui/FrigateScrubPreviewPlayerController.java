// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.ui;

import android.content.Context;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import io.github.tibohlami.ksplugins.frigate.api.FrigateApiClient;
import io.github.tibohlami.ksplugins.frigate.api.FrigateHttpHelper;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import io.github.tibohlami.ksplugins.frigate.model.PreviewSegment;
import java.util.Map;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Lightweight paused ExoPlayer controller that scrubs Frigate 0.14+ hourly preview MP4s
 * locally with adaptive frame-gated seeking.
 */
public class FrigateScrubPreviewPlayerController {

    public interface Listener {
        void onScrubVideoFrameReady();
        void onScrubVideoFallbackNeeded(long epochSeconds);
    }

    private static final long MIN_SEEK_INTERVAL_MS = 16L;
    private static final long SEEK_SAFETY_TIMEOUT_MS = 150L;

    private final Context context;
    private final PluginHost host;
    private final FrigateConfig config;
    private final FrigateApiClient apiClient;
    private final TextureView textureView;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ExoPlayer player;
    private String currentSegmentUrl;
    private double currentSegmentStartSec;
    private long currentTargetEpoch = -1L;
    private long pendingSeekMs = -1L;
    private long lastSeekElapsedMs = 0L;
    private long seekStartedElapsedMs = 0L;
    private long totalSeekLatencyMs = 0L;
    private int completedSeekCount = 0;
    private boolean seekScheduled = false;
    private boolean seekInFlight = false;
    private boolean firstFrameRendered = false;
    private boolean released = false;

    private int videoWidth = 0;
    private int videoHeight = 0;
    private float pixelAspectRatio = 1.0f;

    private final Runnable deferredSeekRunnable = new Runnable() {
        @Override
        public void run() {
            seekScheduled = false;
            if (released || player == null || seekInFlight || pendingSeekMs < 0L) return;
            executeSeek(pendingSeekMs);
        }
    };

    private final Runnable seekTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            seekInFlight = false;
            dispatchPendingSeekIfReady();
        }
    };

    private final Runnable frameRenderedRunnable = new Runnable() {
        @Override
        public void run() {
            if (!released) {
                onSeekFrameRendered();
            }
        }
    };

    public FrigateScrubPreviewPlayerController(Context context, PluginHost host, FrigateConfig config,
                                               FrigateApiClient apiClient, TextureView textureView,
                                               Listener listener) {
        this.context = context;
        this.host = host;
        this.config = config;
        this.apiClient = apiClient;
        this.textureView = textureView;
        this.listener = listener;
        if (this.textureView != null) {
            this.textureView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
                if ((r - l) != (or_ - ol) || (b - t) != (ob - ot)) {
                    updateTextureAspectFit();
                }
            });
        }
    }

    public boolean isSegmentActive(PreviewSegment seg) {
        if (seg == null || apiClient == null) return false;
        String url = apiClient.getStaticPreviewUrl(seg.src);
        return url != null && url.equals(currentSegmentUrl);
    }

    public void seekToEpoch(PreviewSegment seg, long epochSeconds) {
        if (released || seg == null || apiClient == null || textureView == null) return;
        String url = apiClient.getStaticPreviewUrl(seg.src);
        if (url == null || url.isEmpty()) {
            if (listener != null) listener.onScrubVideoFallbackNeeded(epochSeconds);
            return;
        }

        ensurePlayer();
        if (player == null) {
            if (listener != null) listener.onScrubVideoFallbackNeeded(epochSeconds);
            return;
        }

        currentTargetEpoch = epochSeconds;
        long offsetMs = Math.max(0L, Math.round((epochSeconds - seg.start) * 1000.0));

        if (!url.equals(currentSegmentUrl) || Math.abs(seg.start - currentSegmentStartSec) > 0.5) {
            currentSegmentUrl = url;
            currentSegmentStartSec = seg.start;
            firstFrameRendered = false;
            pendingSeekMs = -1L;
            mainHandler.removeCallbacks(deferredSeekRunnable);
            mainHandler.removeCallbacks(seekTimeoutRunnable);
            seekScheduled = false;
            seekInFlight = false;

            textureView.setAlpha(0f);
            textureView.setVisibility(View.VISIBLE);
            logInfo("Frigate: Scrub preview MP4 loading: " + seg.src);
            player.setMediaItem(MediaItem.fromUri(url));
            player.setPlayWhenReady(false);
            executeSeek(offsetMs);
            player.prepare();
        } else {
            if (textureView.getVisibility() != View.VISIBLE) {
                textureView.setAlpha(firstFrameRendered ? 1f : 0f);
                textureView.setVisibility(View.VISIBLE);
            }
            scheduleAdaptiveSeek(offsetMs);
        }

        if (firstFrameRendered && listener != null) {
            listener.onScrubVideoFrameReady();
        }
    }

    public void hide() {
        mainHandler.removeCallbacks(deferredSeekRunnable);
        mainHandler.removeCallbacks(seekTimeoutRunnable);
        mainHandler.removeCallbacks(frameRenderedRunnable);
        seekScheduled = false;
        seekInFlight = false;
        pendingSeekMs = -1L;
        if (completedSeekCount > 0) {
            long avgMs = totalSeekLatencyMs / completedSeekCount;
            logDebug("Frigate: Scrub preview stats: " + completedSeekCount + " seeks, avg=" + avgMs + "ms");
            totalSeekLatencyMs = 0L;
            completedSeekCount = 0;
        }
        if (textureView != null) {
            textureView.setAlpha(0f);
            textureView.setVisibility(View.GONE);
        }
    }

    public void release() {
        released = true;
        hide();
        currentSegmentUrl = null;
        if (player != null) {
            try {
                player.setVideoFrameMetadataListener(null);
                player.setVideoTextureView(null);
                player.release();
            } catch (Throwable ignored) {}
            player = null;
        }
    }

    private void ensurePlayer() {
        if (player != null || released) return;
        try {
            FrigateExoHttpDataSourceFactory dsFactory = new FrigateExoHttpDataSourceFactory(config);
            Map<String, String> authHeaders = FrigateHttpHelper.buildAuthHeaders(config);
            if (!authHeaders.isEmpty()) {
                dsFactory.setDefaultRequestProperties(authHeaders);
            }
            player = new ExoPlayer.Builder(context)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(dsFactory))
                    .build();
            player.setPlayWhenReady(false);
            player.setVolume(0f);
            player.setVideoTextureView(textureView);
            player.setVideoFrameMetadataListener(new VideoFrameMetadataListener() {
                @Override
                public void onVideoFrameAboutToBeRendered(long presentationTimeUs, long releaseTimeNs,
                                                          Format format, MediaFormat mediaFormat) {
                    if (!released) {
                        mainHandler.post(frameRenderedRunnable);
                    }
                }
            });
            player.addListener(new Player.Listener() {
                @Override
                public void onVideoSizeChanged(VideoSize size) {
                    if (released) return;
                    videoWidth = size.width;
                    videoHeight = size.height;
                    pixelAspectRatio = (size.pixelWidthHeightRatio > 0f) ? size.pixelWidthHeightRatio : 1.0f;
                    updateTextureAspectFit();
                }

                @Override
                public void onRenderedFirstFrame() {
                    if (released || textureView == null) return;
                    if (!firstFrameRendered) {
                        logDebug("Frigate: Scrub preview MP4 ready (" + videoWidth + "x" + videoHeight + ")");
                    }
                    firstFrameRendered = true;
                    if (currentTargetEpoch >= 0L) {
                        textureView.setAlpha(1f);
                        textureView.setVisibility(View.VISIBLE);
                        if (listener != null) listener.onScrubVideoFrameReady();
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    if (released) return;
                    String code = (error != null) ? error.getErrorCodeName() : "UNKNOWN";
                    logWarn("Frigate: Scrub preview MP4 error (" + code + "), falling back to snapshots", error);
                    currentSegmentUrl = null;
                    firstFrameRendered = false;
                    hide();
                    if (listener != null && currentTargetEpoch >= 0L) {
                        listener.onScrubVideoFallbackNeeded(currentTargetEpoch);
                    }
                }
            });
        } catch (Throwable t) {
            logWarn("Frigate: Scrub preview ExoPlayer init failed: " + t.getMessage(), t);
            player = null;
        }
    }

    private void logInfo(String msg) {
        if (host != null) host.log(msg);
        android.util.Log.i("FrigatePlugin", msg);
    }

    private void logDebug(String msg) {
        if (config != null && config.isDebugLogging()) {
            if (host != null) host.log(msg);
            android.util.Log.i("FrigatePlugin", msg);
        }
    }

    private void logWarn(String msg, Throwable t) {
        if (host != null) host.log(msg);
        android.util.Log.w("FrigatePlugin", msg, t);
    }

    private void scheduleAdaptiveSeek(long offsetMs) {
        pendingSeekMs = offsetMs;
        if (!seekInFlight) {
            dispatchPendingSeekIfReady();
        }
    }

    private void onSeekFrameRendered() {
        if (!seekInFlight) return;
        long latency = Math.max(0L, SystemClock.elapsedRealtime() - seekStartedElapsedMs);
        totalSeekLatencyMs += latency;
        completedSeekCount++;
        seekInFlight = false;
        mainHandler.removeCallbacks(seekTimeoutRunnable);
        dispatchPendingSeekIfReady();
    }

    private void dispatchPendingSeekIfReady() {
        if (released || player == null || seekInFlight || pendingSeekMs < 0L) return;
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - lastSeekElapsedMs;
        if (elapsed >= MIN_SEEK_INTERVAL_MS) {
            mainHandler.removeCallbacks(deferredSeekRunnable);
            seekScheduled = false;
            executeSeek(pendingSeekMs);
        } else if (!seekScheduled) {
            seekScheduled = true;
            mainHandler.postDelayed(deferredSeekRunnable, MIN_SEEK_INTERVAL_MS - elapsed);
        }
    }

    private void executeSeek(long offsetMs) {
        if (player == null || released) return;
        lastSeekElapsedMs = SystemClock.elapsedRealtime();
        seekStartedElapsedMs = lastSeekElapsedMs;
        seekInFlight = true;
        pendingSeekMs = -1L;
        mainHandler.removeCallbacks(seekTimeoutRunnable);
        mainHandler.postDelayed(seekTimeoutRunnable, SEEK_SAFETY_TIMEOUT_MS);
        try {
            player.seekTo(offsetMs);
        } catch (Throwable ignored) {
            seekInFlight = false;
            mainHandler.removeCallbacks(seekTimeoutRunnable);
        }
    }

    private void updateTextureAspectFit() {
        if (textureView == null || videoWidth <= 0 || videoHeight <= 0) return;
        View parent = (textureView.getParent() instanceof View) ? (View) textureView.getParent() : null;
        if (parent == null) return;
        int pw = parent.getWidth();
        int ph = parent.getHeight();
        if (pw <= 0 || ph <= 0) return;

        float videoAspect = (videoWidth * pixelAspectRatio) / (float) videoHeight;
        float parentAspect = pw / (float) ph;
        int targetW;
        int targetH;
        if (videoAspect > parentAspect) {
            targetW = pw;
            targetH = Math.max(1, Math.round(pw / videoAspect));
        } else {
            targetH = ph;
            targetW = Math.max(1, Math.round(ph * videoAspect));
        }
        ViewGroup.LayoutParams rawLp = textureView.getLayoutParams();
        if (rawLp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) rawLp;
            if (lp.width != targetW || lp.height != targetH || lp.gravity != Gravity.CENTER) {
                lp.width = targetW;
                lp.height = targetH;
                lp.gravity = Gravity.CENTER;
                textureView.setLayoutParams(lp);
            }
        }
    }
}
