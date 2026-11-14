package io.github.tibohlami.ksplugins.frigate.ui;

import android.content.Context;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import io.github.tibohlami.ksplugins.frigate.api.FrigateApiClient;
import io.github.tibohlami.ksplugins.frigate.api.FrigateHttpHelper;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import io.github.tibohlami.ksplugins.frigate.model.FrigateEvent;
import me.jxl.kiosk.plugins.PluginHost;

import java.util.Map;

/**
 * Manages a single shared lightweight ExoPlayer instance for looping video thumbnail
 * previews on event cards, guaranteeing that at most 1 hardware video decoder instance
 * is active at any time.
 */
public class FrigatePreviewPlayerController {

    private static final String TAG = "FrigatePlugin";

    private final Context context;
    private final FrigateConfig config;
    private final FrigateApiClient apiClient;

    private ExoPlayer player;
    private TextureView textureView;
    private FrameLayout currentCard;
    private String currentUrl;
    private FrigateEvent currentEvent;
    private boolean triedFallback = false;

    public FrigatePreviewPlayerController(Context context, FrigateConfig config, FrigateApiClient apiClient) {
        this.context = context;
        this.config = config;
        this.apiClient = apiClient;
    }

    private PluginHost getHost() {
        return (config != null) ? config.getHost() : null;
    }

    private void log(String message) {
        if (config != null && config.isDebugLogging()) {
            PluginHost host = getHost();
            if (host != null) {
                host.log("Frigate: [PREVIEW] " + message);
            }
            Log.d(TAG, "[PREVIEW] " + message);
        }
    }

    private void logError(String message, Throwable t) {
        if (config != null && config.isDebugLogging()) {
            PluginHost host = getHost();
            if (host != null) {
                host.log("Frigate: [PREVIEW] [ERROR] " + message + (t != null ? " (" + t.getMessage() + ")" : ""));
            }
            if (t != null) {
                Log.w(TAG, "[PREVIEW] " + message, t);
            } else {
                Log.w(TAG, "[PREVIEW] " + message);
            }
        }
    }

    private void ensurePlayer() {
        if (player != null) return;

        try {
            textureView = new TextureView(context);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            lp.gravity = Gravity.CENTER;
            textureView.setLayoutParams(lp);
            // Must be VISIBLE so Android creates the hardware SurfaceTexture, but alpha=0 until first frame
            textureView.setAlpha(0f);

            DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
            renderersFactory.setEnableDecoderFallback(true);

            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1500, 10000, 500, 1000)
                    .build();

            player = new ExoPlayer.Builder(context, renderersFactory)
                    .setLoadControl(loadControl)
                    .build();

            player.setVideoTextureView(textureView);
            player.setRepeatMode(Player.REPEAT_MODE_ALL);
            player.setVolume(0f);

            player.addListener(new Player.Listener() {
                @Override
                public void onRenderedFirstFrame() {
                    if (textureView != null) {
                        textureView.setAlpha(1f);
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    logError("Preview player error: " + error.getMessage() + " (code=" + error.errorCode + ")", error);

                    // If preview.mp4 failed and we haven't tried fallback yet, try full event clip if available
                    if (!triedFallback && currentEvent != null && currentUrl != null
                            && currentUrl.contains("/preview.mp4") && currentEvent.hasClip()
                            && apiClient != null && currentEvent.getId() != null
                            && !currentEvent.getId().startsWith("rev:")) {
                        triedFallback = true;
                        String fallbackUrl = apiClient.getClipUrl(currentEvent.getId());
                        log("Retrying preview with event clip: " + FrigateHttpHelper.getUrlPath(fallbackUrl));
                        loadMediaSource(fallbackUrl);
                        return;
                    }

                    detach();
                }

                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_READY) {
                        log("Preview player STATE_READY");
                    } else if (playbackState == Player.STATE_BUFFERING) {
                        log("Preview player STATE_BUFFERING");
                    } else if (playbackState == Player.STATE_ENDED) {
                        log("Preview player STATE_ENDED");
                    }
                }
            });
        } catch (Throwable t) {
            logError("Failed to initialize preview ExoPlayer", t);
            player = null;
            textureView = null;
        }
    }

    public synchronized void attachTo(FrameLayout cardContainer, FrigateEvent event, String videoUrl) {
        if (cardContainer == null || videoUrl == null || videoUrl.trim().isEmpty()) {
            detach();
            return;
        }

        if (currentCard == cardContainer && videoUrl.equals(currentUrl)) {
            return;
        }

        detach();
        ensurePlayer();

        if (player == null || textureView == null) {
            logError("Cannot attach preview: player or textureView is null", null);
            return;
        }

        currentCard = cardContainer;
        currentUrl = videoUrl;
        currentEvent = event;
        triedFallback = false;

        // Insert textureView at index 1 (between snapshot at index 0 and overlays/badges)
        textureView.setAlpha(0f);
        ViewParent existingParent = textureView.getParent();
        if (existingParent instanceof ViewGroup) {
            ((ViewGroup) existingParent).removeView(textureView);
        }
        int childCount = cardContainer.getChildCount();
        int insertIndex = Math.min(1, childCount);
        cardContainer.addView(textureView, insertIndex);

        loadMediaSource(videoUrl);
    }

    private void loadMediaSource(String url) {
        if (player == null || url == null || url.trim().isEmpty()) return;
        try {
            Map<String, String> authHeaders = FrigateHttpHelper.buildAuthHeaders(config);
            HttpDataSource.Factory httpFactory = new FrigateExoHttpDataSourceFactory(config)
                    .setConnectTimeoutMs(8000)
                    .setReadTimeoutMs(15000)
                    .setDefaultRequestProperties(authHeaders);

            ProgressiveMediaSource mediaSource = new ProgressiveMediaSource.Factory(httpFactory)
                    .createMediaSource(MediaItem.fromUri(url.trim()));

            player.setMediaSource(mediaSource);
            player.prepare();
            player.setPlayWhenReady(true);
            log("Preview player prepared for: " + FrigateHttpHelper.getUrlPath(url));
        } catch (Throwable t) {
            logError("Failed to prepare preview MediaSource for: " + FrigateHttpHelper.getUrlPath(url), t);
            detach();
        }
    }

    public synchronized void detach() {
        if (player != null) {
            try {
                player.stop();
                player.clearMediaItems();
            } catch (Throwable ignored) {}
        }
        if (textureView != null) {
            textureView.setAlpha(0f);
            ViewParent parent = textureView.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(textureView);
            }
        }
        currentCard = null;
        currentUrl = null;
        currentEvent = null;
        triedFallback = false;
    }

    public synchronized boolean isAttachedTo(FrameLayout cardContainer) {
        return currentCard != null && currentCard == cardContainer;
    }

    public synchronized String getActiveEventId() {
        return (currentEvent != null) ? currentEvent.getId() : null;
    }

    public synchronized void release() {
        detach();
        if (player != null) {
            try {
                player.release();
            } catch (Throwable ignored) {}
            player = null;
        }
        textureView = null;
    }
}
