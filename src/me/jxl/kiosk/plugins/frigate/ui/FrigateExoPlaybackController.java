// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.frigate.ui;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaFormat;
import android.os.Handler;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import me.jxl.kiosk.plugins.PluginHost;
import me.jxl.kiosk.plugins.frigate.api.FrigateHttpHelper;
import me.jxl.kiosk.plugins.frigate.config.FrigateConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Native Media3 ExoPlayer HLS VOD playback controller with bogus-FPS codec workarounds
 * and automatic HW -> SW decoder retry before falling back to WebView hls.js.
 */
public class FrigateExoPlaybackController {

    public interface Listener {
        boolean isClosed();
        int getStreamSessionId();
        boolean isExoPlayback();
        boolean shouldLoopPlayback();
        View getVideoContainer();
        void onExoPreparingSurface();
        void onExoVideoSizeChanged(int width, int height, float pixelAspectRatio);
        void onExoFirstFrameRendered();
        void onExoAudioAvailabilityChanged(boolean hasAudio);
        void onExoIsPlayingChanged(boolean playing);
        void onExoBuffering(long currentPosMs);
        void onExoReady(long rawDurationMs, boolean usingSoftwareDecoder, boolean playing);
        void onExoEnded();
        void onExoRetryWithSoftwareDecoder(String errorMsg);
        void onExoFallbackToHls(String errorMsg);
    }

    private final Context context;
    private final PluginHost host;
    private final FrigateConfig config;
    private final Listener listener;

    private TextureView exoTextureView;
    private ExoPlayer exoPlayer;
    private volatile boolean exoPreferSoftware = false;
    private int exoVideoWidth = 0;
    private int exoVideoHeight = 0;
    private float exoPixelAspectRatio = 1.0f;
    private final Timeline.Window timelineWindow = new Timeline.Window();

    public FrigateExoPlaybackController(Context context, PluginHost host,
                                        FrigateConfig config, Listener listener) {
        this.context = context;
        this.host = host;
        this.config = config;
        this.listener = listener;
    }

    public TextureView createTextureView() {
        exoTextureView = new TextureView(context);
        FrameLayout.LayoutParams exoLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        exoLp.gravity = Gravity.CENTER;
        exoTextureView.setLayoutParams(exoLp);
        exoTextureView.setVisibility(View.GONE);
        return exoTextureView;
    }

    public boolean hasTextureView() {
        return exoTextureView != null;
    }

    public boolean hasPlayer() {
        return exoPlayer != null;
    }

    public void resetSoftwareDecoderPreference() {
        this.exoPreferSoftware = false;
    }

    public long getCurrentPosition() {
        return exoPlayer != null ? Math.max(0L, exoPlayer.getCurrentPosition()) : 0L;
    }

    /**
     * Returns the exact wall-clock epoch seconds from HLS {@code #EXT-X-PROGRAM-DATE-TIME}
     * when available, or {@code -1.0} if the playlist does not expose a program start time.
     */
    public double getCurrentProgramEpochSeconds() {
        if (exoPlayer == null) return -1.0;
        Timeline tl = exoPlayer.getCurrentTimeline();
        if (tl == null || tl.isEmpty()) return -1.0;
        int idx = exoPlayer.getCurrentMediaItemIndex();
        if (idx < 0 || idx >= tl.getWindowCount()) return -1.0;
        tl.getWindow(idx, timelineWindow);
        if (timelineWindow.windowStartTimeMs == C.TIME_UNSET) {
            return -1.0;
        }
        return (timelineWindow.windowStartTimeMs + Math.max(0L, exoPlayer.getCurrentPosition())) / 1000.0;
    }

    public long getDuration() {
        return exoPlayer != null ? exoPlayer.getDuration() : C.TIME_UNSET;
    }

    public boolean isPlaying() {
        return exoPlayer != null && exoPlayer.isPlaying();
    }

    public int getRenderedVideoFrameCount() {
        if (exoPlayer == null) return -1;
        DecoderCounters counters = exoPlayer.getVideoDecoderCounters();
        return counters != null ? counters.renderedOutputBufferCount : -1;
    }

    public void seekTo(long posMs) {
        if (exoPlayer != null) {
            exoPlayer.seekTo(posMs);
        }
    }

    public void setPlayWhenReady(boolean play) {
        if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(play);
        }
    }

    public void setMuted(boolean muted) {
        if (exoPlayer != null) {
            exoPlayer.setVolume(muted ? 0f : 1f);
        }
    }

    public void setLooping(boolean loop) {
        if (exoPlayer != null) {
            exoPlayer.setRepeatMode(loop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        }
    }

    public void updateTextureSize() {
        View container = listener.getVideoContainer();
        if (exoTextureView == null || container == null) return;
        int cw = container.getWidth();
        int ch = container.getHeight();
        if (cw <= 0 || ch <= 0 || exoVideoWidth <= 0 || exoVideoHeight <= 0) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) exoTextureView.getLayoutParams();
            if (lp.width != FrameLayout.LayoutParams.MATCH_PARENT || lp.height != FrameLayout.LayoutParams.MATCH_PARENT) {
                lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
                lp.height = FrameLayout.LayoutParams.MATCH_PARENT;
                lp.gravity = Gravity.CENTER;
                exoTextureView.setLayoutParams(lp);
            }
            return;
        }
        float videoAspect = (exoVideoWidth * exoPixelAspectRatio) / (float) exoVideoHeight;
        float viewAspect = cw / (float) ch;
        int targetW = cw;
        int targetH = ch;
        if (videoAspect > viewAspect) {
            targetH = Math.max(1, Math.round(cw / videoAspect));
        } else {
            targetW = Math.max(1, Math.round(ch * videoAspect));
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) exoTextureView.getLayoutParams();
        if (lp.width != targetW || lp.height != targetH) {
            lp.width = targetW;
            lp.height = targetH;
            lp.gravity = Gravity.CENTER;
            exoTextureView.setLayoutParams(lp);
        }
    }

    public void stop() {
        exoVideoWidth = 0;
        exoVideoHeight = 0;
        exoPixelAspectRatio = 1.0f;
        if (exoPlayer != null) {
            try {
                exoPlayer.clearVideoTextureView(exoTextureView);
                exoPlayer.release();
            } catch (Exception ignored) {}
            exoPlayer = null;
        }
        if (exoTextureView != null) {
            exoTextureView.setSurfaceTextureListener(null);
            exoTextureView.setVisibility(View.GONE);
        }
    }

    public void start(final int sessionId, final String hlsUrl, final boolean muted) {
        stop();
        listener.onExoPreparingSurface();
        if (exoTextureView != null) {
            exoTextureView.setVisibility(View.VISIBLE);
            updateTextureSize();
        }

        if (exoTextureView != null && !exoTextureView.isAvailable()) {
            exoTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    if (exoTextureView != null) {
                        exoTextureView.setSurfaceTextureListener(null);
                    }
                    if (!listener.isClosed() && sessionId == listener.getStreamSessionId() && listener.isExoPlayback()) {
                        launchExoPlayer(sessionId, hlsUrl, muted);
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
            });
            return;
        }
        launchExoPlayer(sessionId, hlsUrl, muted);
    }

    private void launchExoPlayer(final int sessionId, final String hlsUrl, final boolean muted) {
        try {
            Map<String, String> authHeaders = FrigateHttpHelper.buildAuthHeaders(config);
            HttpDataSource.Factory httpFactory;
            if (config != null && config.isAllowInsecureSsl()) {
                httpFactory = new FrigateExoHttpDataSourceFactory(config)
                        .setConnectTimeoutMs(8000)
                        .setReadTimeoutMs(15000)
                        .setDefaultRequestProperties(authHeaders);
            } else {
                httpFactory = new DefaultHttpDataSource.Factory()
                        .setDefaultRequestProperties(authHeaders)
                        .setConnectTimeoutMs(8000)
                        .setReadTimeoutMs(15000)
                        .setAllowCrossProtocolRedirects(true);
            }
            HlsMediaSource hlsMediaSource = new HlsMediaSource.Factory(httpFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(MediaItem.fromUri(hlsUrl));
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15000, 60000, 500, 1500)
                    .build();

            DefaultRenderersFactory renderersFactory = createNormalizedRenderersFactory();
            renderersFactory.setEnableDecoderFallback(true);
            if (exoPreferSoftware) {
                renderersFactory.setMediaCodecSelector((mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
                    List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                            mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                    if (!MimeTypes.isVideo(mimeType)) {
                        return all;
                    }
                    List<MediaCodecInfo> sw = new ArrayList<>();
                    for (MediaCodecInfo info : all) {
                        if (info.softwareOnly || !info.hardwareAccelerated) {
                            sw.add(info);
                        }
                    }
                    return sw.isEmpty() ? all : sw;
                });
            }

            final ExoPlayer player = new ExoPlayer.Builder(context, renderersFactory)
                    .setLoadControl(loadControl)
                    .build();
            this.exoPlayer = player;
            player.setVideoTextureView(exoTextureView);
            player.setRepeatMode(listener.shouldLoopPlayback() ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            player.setVolume(muted ? 0f : 1f);
            attachPlayerListeners(player, sessionId);

            player.setMediaSource(hlsMediaSource);
            player.prepare();
            player.setPlayWhenReady(true);
            listener.onExoIsPlayingChanged(true);
        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            if (host != null) {
                host.log("Frigate: ExoPlayer init failed (" + msg + "), falling back to hls.js");
            }
            android.util.Log.w("FrigatePlugin", "ExoPlayer init failed, falling back to hls.js", t);
            stop();
            listener.onExoFallbackToHls(msg);
        }
    }

    private void attachPlayerListeners(final ExoPlayer player, final int sessionId) {
        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                if (listener.isClosed() || sessionId != listener.getStreamSessionId() || player != exoPlayer) return;
                exoVideoWidth = videoSize.width;
                exoVideoHeight = videoSize.height;
                exoPixelAspectRatio = videoSize.pixelWidthHeightRatio > 0 ? videoSize.pixelWidthHeightRatio : 1.0f;
                updateTextureSize();
                listener.onExoVideoSizeChanged(exoVideoWidth, exoVideoHeight, exoPixelAspectRatio);
            }

            @Override
            public void onRenderedFirstFrame() {
                if (listener.isClosed() || sessionId != listener.getStreamSessionId() || player != exoPlayer) return;
                listener.onExoFirstFrameRendered();
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                if (listener.isClosed() || sessionId != listener.getStreamSessionId() || player != exoPlayer) return;
                listener.onExoAudioAvailabilityChanged(tracks.containsType(C.TRACK_TYPE_AUDIO));
                for (int g = 0; g < tracks.getGroups().size(); g++) {
                    Tracks.Group group = tracks.getGroups().get(g);
                    int type = group.getType();
                    String typeName = (type == C.TRACK_TYPE_VIDEO) ? "VIDEO"
                            : (type == C.TRACK_TYPE_AUDIO ? "AUDIO"
                            : (type == C.TRACK_TYPE_TEXT ? "TEXT" : "TYPE_" + type));
                    for (int t = 0; t < group.length; t++) {
                        Format fmt = group.getTrackFormat(t);
                        String support = C.getFormatSupportString(group.getTrackSupport(t));
                        String msg = "Frigate: [EXO] track[" + g + "." + t + "] " + typeName
                                + " selected=" + group.isTrackSelected(t)
                                + " format_supported=" + support
                                + " format=" + fmt;
                        if (host != null) host.log(msg);
                        android.util.Log.i("FrigatePlugin", msg);
                    }
                }
            }

            @Override
            public void onIsPlayingChanged(boolean playingNow) {
                if (listener.isClosed() || sessionId != listener.getStreamSessionId()
                        || player != exoPlayer || !listener.isExoPlayback()) {
                    return;
                }
                listener.onExoIsPlayingChanged(playingNow);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (listener.isClosed() || sessionId != listener.getStreamSessionId()
                        || player != exoPlayer || !listener.isExoPlayback()) {
                    return;
                }
                if (playbackState == Player.STATE_BUFFERING) {
                    listener.onExoBuffering(Math.max(0, player.getCurrentPosition()));
                    android.util.Log.d("FrigatePlugin", "Frigate: [EXO] buffering at " + Math.max(0, player.getCurrentPosition()) + "ms");
                } else if (playbackState == Player.STATE_READY) {
                    listener.onExoReady(player.getDuration(), exoPreferSoftware, player.isPlaying());
                } else if (playbackState == Player.STATE_ENDED) {
                    listener.onExoEnded();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (listener.isClosed() || sessionId != listener.getStreamSessionId() || player != exoPlayer) return;
                String msg = error != null ? (error.getErrorCodeName() + ": " + error.getMessage()) : "unknown";
                if (!exoPreferSoftware) {
                    exoPreferSoftware = true;
                    if (host != null) {
                        host.log("Frigate: ExoPlayer HW decoder failed (" + msg + "), retrying with SW video decoder");
                    }
                    android.util.Log.w("FrigatePlugin", "ExoPlayer HW decoder failed (" + msg + "), retrying with SW video decoder", error);
                    stop();
                    listener.onExoRetryWithSoftwareDecoder(msg);
                    return;
                }
                if (host != null) {
                    host.log("Frigate: ExoPlayer HLS error (" + msg + "), falling back to hls.js");
                }
                android.util.Log.w("FrigatePlugin", "ExoPlayer HLS error (" + msg + "), falling back to hls.js", error);
                stop();
                listener.onExoFallbackToHls(msg);
            }
        });

        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onVideoDecoderInitialized(AnalyticsListener.EventTime eventTime,
                                                  String decoderName,
                                                  long initializedTimestampMs,
                                                  long initializationDurationMs) {
                if (listener.isClosed() || sessionId != listener.getStreamSessionId() || player != exoPlayer) return;
                String msg = "Frigate: [EXO] video decoder=" + decoderName
                        + " (sw=" + exoPreferSoftware + ", init=" + initializationDurationMs + "ms)";
                if (host != null) host.log(msg);
                android.util.Log.i("FrigatePlugin", msg);
            }

            @Override
            public void onVideoInputFormatChanged(AnalyticsListener.EventTime eventTime,
                                                  Format format,
                                                  DecoderReuseEvaluation decoderReuseEvaluation) {
                if (listener.isClosed() || sessionId != listener.getStreamSessionId() || player != exoPlayer) return;
                String msg = "Frigate: [EXO] video input format=" + format;
                if (host != null) host.log(msg);
                android.util.Log.i("FrigatePlugin", msg);
            }

            @Override
            public void onAudioDecoderInitialized(AnalyticsListener.EventTime eventTime,
                                                  String decoderName,
                                                  long initializedTimestampMs,
                                                  long initializationDurationMs) {
                if (listener.isClosed() || sessionId != listener.getStreamSessionId() || player != exoPlayer) return;
                String msg = "Frigate: [EXO] audio decoder=" + decoderName
                        + " (init=" + initializationDurationMs + "ms)";
                if (host != null) host.log(msg);
                android.util.Log.i("FrigatePlugin", msg);
            }
        });
    }

    private DefaultRenderersFactory createNormalizedRenderersFactory() {
        return new DefaultRenderersFactory(context) {
            @Override
            protected void buildVideoRenderers(Context ctx,
                                               int extensionRendererMode,
                                               MediaCodecSelector mediaCodecSelector,
                                               boolean enableDecoderFallback,
                                               Handler eventHandler,
                                               VideoRendererEventListener eventListener,
                                               long allowedVideoJoiningTimeMs,
                                               ArrayList<Renderer> out) {
                out.add(new MediaCodecVideoRenderer(
                        ctx,
                        getCodecAdapterFactory(),
                        mediaCodecSelector,
                        allowedVideoJoiningTimeMs,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY) {
                    @Override
                    protected int supportsFormat(MediaCodecSelector selector, Format format)
                            throws MediaCodecUtil.DecoderQueryException {
                        return super.supportsFormat(selector, normalizeBogusHighFps(format));
                    }

                    @Override
                    protected List<MediaCodecInfo> getDecoderInfos(MediaCodecSelector selector,
                                                                   Format format,
                                                                   boolean requiresSecureDecoder)
                            throws MediaCodecUtil.DecoderQueryException {
                        return super.getDecoderInfos(selector, normalizeBogusHighFps(format), requiresSecureDecoder);
                    }

                    @Override
                    protected CodecMaxValues getCodecMaxValues(MediaCodecInfo codecInfo,
                                                               Format format,
                                                               Format[] streamFormats) {
                        return super.getCodecMaxValues(codecInfo, normalizeBogusHighFps(format),
                                normalizeBogusHighFpsArray(streamFormats));
                    }

                    @Override
                    protected float getCodecOperatingRateV23(float targetPlaybackSpeed,
                                                             Format format,
                                                             Format[] streamFormats) {
                        return super.getCodecOperatingRateV23(targetPlaybackSpeed,
                                normalizeBogusHighFps(format), normalizeBogusHighFpsArray(streamFormats));
                    }

                    @Override
                    protected MediaFormat getMediaFormat(Format format,
                                                         String codecMimeType,
                                                         CodecMaxValues codecMaxValues,
                                                         float codecOperatingRate,
                                                         boolean deviceNeedsNoPostProcessWorkaround,
                                                         int tunnelingAudioSessionId) {
                        Format norm = normalizeBogusHighFps(format);
                        float safeOpRate = (codecOperatingRate > 60.0f) ? 30.0f : codecOperatingRate;
                        MediaFormat mf = super.getMediaFormat(norm, codecMimeType, codecMaxValues,
                                safeOpRate, deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId);
                        if (mf != null && mf.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            try {
                                float fr = mf.getFloat(MediaFormat.KEY_FRAME_RATE);
                                if (fr > 60.0f) {
                                    mf.setFloat(MediaFormat.KEY_FRAME_RATE, 30.0f);
                                }
                            } catch (Exception ignored) {}
                        }
                        return mf;
                    }
                });
            }
        };
    }

    private static Format normalizeBogusHighFps(Format format) {
        if (format != null && format.frameRate > 60.0f) {
            return format.buildUpon().setFrameRate(30.0f).build();
        }
        return format;
    }

    private static Format[] normalizeBogusHighFpsArray(Format[] formats) {
        if (formats == null) return null;
        Format[] out = null;
        for (int i = 0; i < formats.length; i++) {
            Format norm = normalizeBogusHighFps(formats[i]);
            if (norm != formats[i]) {
                if (out == null) {
                    out = formats.clone();
                }
                out[i] = norm;
            }
        }
        return out != null ? out : formats;
    }
}
