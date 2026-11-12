// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.frigate.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import androidx.media3.common.C;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.frigate.api.FrigateApiClient;
import me.jxl.kiosk.plugins.frigate.config.FrigateConfig;
import me.jxl.kiosk.plugins.frigate.model.FrigateEvent;
import me.jxl.kiosk.plugins.frigate.model.RecordingSegment;
import me.jxl.kiosk.plugins.frigate.model.ReviewSegment;
import me.jxl.kiosk.plugins.frigate.model.TimelineBoxEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Modern native video player and real-time live stream overlay for Frigate cameras.
 * - Live Mode ("Direct"):
 *     1. Primary: Real-time MSE over WebSocket via Frigate go2rtc (/live/mse/api/ws) -> Full HD Video + Audio (<1s latency).
 *     2. Automatic fallback: Native MJPEG (/api/<camera>?fps=8&height=720) if go2rtc MSE is unavailable.
 * - Event / 24h History Mode: Hardware-accelerated MP4 playback with local caching, duration bounding & seeking.
 */
public class FrigatePlayerOverlay {

    private final Context context;
    private final KsTheme theme;
    private final me.jxl.kiosk.plugins.PluginHost host;
    private final FrigateApiClient apiClient;
    private final FrigateConfig config;
    private final FrigateEvent event;
    private final Runnable onClose;

    private FrameLayout videoContainerRef;
    private FrigateTimelineRulerView timelineRuler;
    private ImageView scrubPreviewView;
    private TextView scrubHudPill;
    private volatile boolean isRulerScrubbing = false;
    private final FrigatePlayerTimelineController timelineController;
    private final FrigateWebStreamController webStreamController;
    private final FrigateMjpegStreamController mjpegController;
    private final FrigateExoPlaybackController exoController;
    private final FrigateClipCacheManager clipCacheManager;
    private final FrigatePlayerObjectDetectorController objectDetectorController;
    private VideoView videoView;
    private ImageView liveImageView;
    private ImageView fallbackImageView;
    private final AsyncImageLoader imageLoader;
    private ProgressBar progressBar;
    private TextView errorText;
    private TextView liveBadge;
    private TextView transportModeBadge;
    private TextView liveFpsBadge;
    private int lastExoFrames = -1;
    private long lastExoFpsTsMs = 0L;
    private LinearLayout headerObjectsContainer;
    private TextView headerTimeView;
    private ImageView playPauseBtn;
    private TextView timeCurrentText;
    private TextView timeDurationText;
    private TextView muteBtn;
    private TextView bboxBtn;
    private TextView loopBtn;
    private SeekBar seekBar;
    private View transportSpacer;
    private LinearLayout videoOverlayControls;
    private boolean videoOverlayVisible = true;
    private static final long OVERLAY_AUTO_HIDE_MS = 3000L;
    private final Runnable hideOverlayControlsRunnable = () -> hideVideoOverlayControls(true);
    private FrigateObjectBoxesOverlayView objectBoxesOverlay;
    private boolean showObjectBoxes = false;
    private int activeVideoWidth = 0;
    private int activeVideoHeight = 0;
    private float activeVideoPixelAspect = 1.0f;

    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private long lastSyncedWallEpoch = -1L;
    private volatile boolean isClosed = false;
    private boolean isUserSeeking = false;
    private boolean wasPlayingBeforeSeek = false;
    private boolean isLooping = true;
    private boolean isPlaying = false;
    private boolean isTimelineMode = false;
    private boolean isHistoryMode = false;
    private volatile boolean isExoPlayback = false;
    private volatile boolean exoFallbackTriggered = false;
    private volatile boolean isHlsPlayback = false;
    private volatile boolean hlsFallbackTriggered = false;
    private volatile int hlsCurrentPosMs = 0;
    private volatile boolean isLiveStreamMode;
    private volatile boolean isLiveMseActive = false;
    private volatile boolean isLivePaused = false;
    private volatile boolean isMuted = false;
    private volatile int streamSessionId = 0;

    private int eventTotalDurationMs = 0;

    private String currentCamera;
    private long timelineStartEpoch;
    private long timelineDurationSeconds = 300; // 5 minute blocks for smooth playback

    public FrigatePlayerOverlay(Context context, KsTheme theme, me.jxl.kiosk.plugins.PluginHost host,
                                FrigateApiClient apiClient, FrigateConfig config, FrigateEvent event, Runnable onClose) {
        this.context = context;
        this.theme = theme;
        this.host = host;
        this.apiClient = apiClient;
        this.config = config;
        this.event = event;
        this.onClose = onClose;
        this.imageLoader = new AsyncImageLoader();
        this.imageLoader.setConfig(config);
        if (config != null && config.getAuthToken() != null) {
            this.imageLoader.setAuthToken(config.getAuthToken());
        }
        this.currentCamera = event != null ? event.getCamera() : config.getDefaultCamera();
        this.isLiveStreamMode = (event == null || event.getId() == null || event.getId().trim().isEmpty());
        this.showObjectBoxes = config != null && config.isShowObjectBoxesByDefault();
        this.isMuted = config != null && config.isMuteAudioByDefault();

        this.objectDetectorController = new FrigatePlayerObjectDetectorController(
                context, theme, apiClient, config, progressHandler,
                new FrigatePlayerObjectDetectorController.HostBridge() {
                    @Override public boolean isClosed() { return isClosed; }
                    @Override public boolean isLiveStreamMode() { return isLiveStreamMode; }
                    @Override public boolean isLivePaused() { return isLivePaused; }
                    @Override public boolean isRulerScrubbing() { return isRulerScrubbing; }
                    @Override public boolean isTimelineMode() { return isTimelineMode; }
                    @Override public boolean isShowObjectBoxes() { return showObjectBoxes; }
                    @Override public String getCurrentCamera() { return currentCamera; }
                    @Override public FrigateEvent getPrimaryEvent() { return event; }
                    @Override public double getCurrentPlaybackEpochDouble() { return FrigatePlayerOverlay.this.getCurrentPlaybackEpochDouble(); }
                    @Override public List<FrigateEvent> getTimelineEvents() { return timelineController.getTimelineEvents(); }
                    @Override public List<ReviewSegment> getTimelineReviews() { return timelineController.getTimelineReviews(); }
                    @Override public FrigateObjectBoxesOverlayView getObjectBoxesOverlay() { return objectBoxesOverlay; }
                    @Override public LinearLayout getHeaderObjectsContainer() { return headerObjectsContainer; }
                });

        if (!isLiveStreamMode && event.getStartTime() > 0) {
            this.timelineStartEpoch = (long) event.getStartTime();
            if (event.getEndTime() > event.getStartTime()) {
                this.eventTotalDurationMs = (int) Math.round((event.getEndTime() - event.getStartTime()) * 1000);
            }
        } else {
            this.timelineStartEpoch = System.currentTimeMillis() / 1000L;
        }
        this.timelineController = new FrigatePlayerTimelineController(apiClient, config, progressHandler,
                new FrigatePlayerTimelineController.Listener() {
                    @Override
                    public boolean isClosed() {
                        return isClosed;
                    }

                    @Override
                    public boolean isLiveStreamMode() {
                        return isLiveStreamMode;
                    }

                    @Override
                    public boolean isLivePaused() {
                        return isLivePaused;
                    }

                    @Override
                    public boolean isRulerScrubbing() {
                        return isRulerScrubbing;
                    }

                    @Override
                    public String getCurrentCamera() {
                        return currentCamera;
                    }

                    @Override
                    public FrigateTimelineRulerView getTimelineRuler() {
                        return timelineRuler;
                    }

                    @Override
                    public void onTimelineRecordingsUpdated(long maxEpochHint, List<RecordingSegment> segments) {
                        if (timelineRuler == null) return;
                        timelineRuler.setTimeRange(0L, maxEpochHint);
                        timelineRuler.setRecordings(segments);
                    }

                    @Override
                    public void onTimelineReviewsUpdated(List<ReviewSegment> reviews) {
                        if (timelineRuler != null) {
                            timelineRuler.setReviewSegments(reviews);
                            if (!isLiveStreamMode) {
                                updateObjectBadgesForEpoch(timelineRuler.getCursorEpoch());
                            }
                        }
                    }

                    @Override
                    public void onTimelineEventsUpdated(List<FrigateEvent> events) {
                        if (timelineRuler != null && !isLiveStreamMode) {
                            updateObjectBadgesForEpoch(timelineRuler.getCursorEpoch());
                        }
                        if (!isLiveStreamMode && showObjectBoxes && !isRulerScrubbing) {
                            updateObjectBoxesForEpoch(getCurrentPlaybackEpochDouble());
                        }
                    }

                    @Override
                    public void onScrubBitmapReady(Bitmap bitmap) {
                        if (scrubPreviewView != null) {
                            scrubPreviewView.setImageBitmap(bitmap);
                            scrubPreviewView.setVisibility(View.VISIBLE);
                        }
                    }
                });
        this.webStreamController = new FrigateWebStreamController(host, config, progressHandler,
                new FrigateWebStreamController.Listener() {
                    @Override
                    public boolean isClosed() {
                        return isClosed;
                    }

                    @Override
                    public boolean isLiveStreamMode() {
                        return isLiveStreamMode;
                    }

                    @Override
                    public boolean isHlsPlayback() {
                        return isHlsPlayback;
                    }

                    @Override
                    public int getStreamSessionId() {
                        return streamSessionId;
                    }

                    @Override
                    public void onLiveMseStarting() {
                        updateTransportModeBadge("MSE...", "#94A3B8", "#161D2B", "#334155");
                    }

                    @Override
                    public void onMsePlaying(int sessionId, boolean hasAudio) {
                        isLiveMseActive = true;
                        hideScrubPreview();
                        if (fallbackImageView != null) fallbackImageView.setVisibility(View.GONE);
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                        if (errorText != null) errorText.setVisibility(View.GONE);
                        if (muteBtn != null) {
                            muteBtn.setVisibility(hasAudio ? View.VISIBLE : View.GONE);
                            updateMuteButtonStyle();
                        }
                        updateTransportModeBadge(hasAudio ? "MSE • AUDIO" : "MSE", "#6EE7B7", "#0F2922", "#10B981");
                        if (host != null) {
                            host.log("Frigate: Live MSE playing (audio=" + hasAudio + ")");
                        }
                    }

                    @Override
                    public void onMseFps(int sessionId, int fps) {
                        updateLiveFpsBadge(fps);
                    }

                    @Override
                    public void onWebAutoMuted(int sessionId) {
                        isMuted = true;
                        if (muteBtn != null) {
                            muteBtn.setVisibility(View.VISIBLE);
                            updateMuteButtonStyle();
                        }
                    }

                    @Override
                    public void onMseFallback(int sessionId, String reason) {
                        if (host != null) {
                            host.log("Frigate: MSE unavailable (" + reason + "), falling back to native MJPEG");
                        }
                        stopLiveWebView();
                        startNativeMjpegStream(sessionId);
                    }

                    @Override
                    public void onHlsPlaying(int sessionId, int durationMs, boolean hasAudio) {
                        hideScrubPreview();
                        if (fallbackImageView != null) fallbackImageView.setVisibility(View.GONE);
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                        if (errorText != null) errorText.setVisibility(View.GONE);
                        if (durationMs > 0) {
                            eventTotalDurationMs = durationMs;
                            if (seekBar != null) seekBar.setMax(durationMs);
                            if (timeDurationText != null) timeDurationText.setText(formatDuration(durationMs));
                        }
                        if (muteBtn != null) {
                            muteBtn.setVisibility(hasAudio ? View.VISIBLE : View.GONE);
                            updateMuteButtonStyle();
                        }
                        isPlaying = true;
                        updatePlayPauseIcon();
                        updateTransportModeBadge("HLS", "#7DD3FC", "#0C2233", "#0284C7");
                        if (host != null) {
                            host.log("Frigate: hls.js VOD playing (duration=" + durationMs + "ms, audio=" + hasAudio + ")");
                        }
                    }

                    @Override
                    public void onHlsProgress(int sessionId, int currentMs, int durationMs) {
                        hlsCurrentPosMs = Math.max(0, currentMs);
                        if (durationMs > 0 && durationMs != eventTotalDurationMs) {
                            eventTotalDurationMs = durationMs;
                            if (seekBar != null) seekBar.setMax(durationMs);
                            if (timeDurationText != null) timeDurationText.setText(formatDuration(durationMs));
                        }
                        if (!isUserSeeking) {
                            if (seekBar != null) seekBar.setProgress(hlsCurrentPosMs);
                            if (timeCurrentText != null) timeCurrentText.setText(formatDuration(hlsCurrentPosMs));
                            if (!isRulerScrubbing) {
                                double baseEpoch = isTimelineMode
                                        ? (double) timelineStartEpoch
                                        : ((event != null && event.getStartTime() > 0) ? event.getStartTime() : (double) timelineStartEpoch);
                                syncPlaybackEpochUi(baseEpoch + (hlsCurrentPosMs / 1000.0));
                            }
                        }
                    }

                    @Override
                    public void onHlsFps(int sessionId, int fps) {
                        updateLiveFpsBadge(fps);
                    }

                    @Override
                    public void onHlsVideoSize(int sessionId, int width, int height) {
                        setActiveVideoDimensions(width, height, 1.0f);
                    }

                    @Override
                    public void onHlsEnded(int sessionId) {
                        isPlaying = false;
                        updatePlayPauseIcon();
                        if (eventTotalDurationMs > 0) {
                            hlsCurrentPosMs = eventTotalDurationMs;
                            if (seekBar != null) seekBar.setProgress(eventTotalDurationMs);
                            if (timeCurrentText != null) timeCurrentText.setText(formatDuration(eventTotalDurationMs));
                        }
                    }

                    @Override
                    public void onHlsFallback(int sessionId, String reason) {
                        if (host != null) {
                            host.log("Frigate: hls.js VOD failed (" + reason + "), falling back to clip.mp4");
                        }
                        android.util.Log.w("FrigatePlugin", "hls.js VOD error (" + reason + "), falling back to MP4");
                        stopLiveWebView();
                        isHlsPlayback = false;
                        hlsFallbackTriggered = true;
                        loadVideo();
                    }
                });
        this.mjpegController = new FrigateMjpegStreamController(apiClient, config, progressHandler,
                new FrigateMjpegStreamController.Listener() {
                    @Override
                    public boolean isClosed() {
                        return isClosed;
                    }

                    @Override
                    public boolean isLiveStreamMode() {
                        return isLiveStreamMode;
                    }

                    @Override
                    public boolean isLivePaused() {
                        return isLivePaused;
                    }

                    @Override
                    public int getStreamSessionId() {
                        return streamSessionId;
                    }

                    @Override
                    public void onMjpegStarting(int sessionId) {
                        isLiveMseActive = false;
                        hideScrubPreview();
                        updateTransportModeBadge("MJPEG", "#FCD34D", "#291D0B", "#F59E0B");
                        if (liveImageView != null) {
                            liveImageView.setVisibility(View.VISIBLE);
                        }
                        if (progressBar != null) {
                            progressBar.setVisibility(View.VISIBLE);
                        }
                    }

                    @Override
                    public void onMjpegFrame(int sessionId, Bitmap frame) {
                        if (fallbackImageView != null && fallbackImageView.getVisibility() != View.GONE) {
                            fallbackImageView.setVisibility(View.GONE);
                        }
                        if (progressBar != null && progressBar.getVisibility() != View.GONE) {
                            progressBar.setVisibility(View.GONE);
                        }
                        if (errorText != null && errorText.getVisibility() != View.GONE) {
                            errorText.setVisibility(View.GONE);
                        }
                        if (liveImageView != null) {
                            liveImageView.setImageBitmap(frame);
                        }
                    }

                    @Override
                    public void onMjpegFps(int sessionId, int fps) {
                        updateLiveFpsBadge(fps);
                    }

                    @Override
                    public void onMjpegError(int sessionId, String errorMessage) {
                        if (fallbackImageView != null) fallbackImageView.setVisibility(View.GONE);
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                        if (errorText != null) {
                            errorText.setText(tr("player_error_live_interrupted", errorMessage));
                            errorText.setVisibility(View.VISIBLE);
                        }
                    }
                });
        this.exoController = new FrigateExoPlaybackController(context, host, config,
                new FrigateExoPlaybackController.Listener() {
                    @Override
                    public boolean isClosed() {
                        return isClosed;
                    }

                    @Override
                    public int getStreamSessionId() {
                        return streamSessionId;
                    }

                    @Override
                    public boolean isExoPlayback() {
                        return isExoPlayback;
                    }

                    @Override
                    public boolean shouldLoopPlayback() {
                        return FrigatePlayerOverlay.this.shouldLoopPlayback();
                    }

                    @Override
                    public View getVideoContainer() {
                        return videoContainerRef;
                    }

                    @Override
                    public void onExoPreparingSurface() {
                        stopLiveWebView();
                        if (videoView != null) {
                            try {
                                videoView.stopPlayback();
                            } catch (Exception ignored) {}
                            videoView.setVisibility(View.GONE);
                        }
                        if (liveImageView != null) {
                            liveImageView.setVisibility(View.GONE);
                        }
                        if (muteBtn != null) {
                            muteBtn.setVisibility(View.GONE);
                        }
                        isExoPlayback = true;
                        isHlsPlayback = false;
                    }

                    @Override
                    public void onExoVideoSizeChanged(int width, int height, float pixelAspectRatio) {
                        setActiveVideoDimensions(width, height, pixelAspectRatio);
                    }

                    @Override
                    public void onExoFirstFrameRendered() {
                        hideScrubPreview();
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                        if (fallbackImageView != null) fallbackImageView.setVisibility(View.GONE);
                        if (errorText != null) errorText.setVisibility(View.GONE);
                    }

                    @Override
                    public void onExoAudioAvailabilityChanged(boolean hasAudio) {
                        if (muteBtn != null) {
                            muteBtn.setVisibility(hasAudio ? View.VISIBLE : View.GONE);
                        }
                    }

                    @Override
                    public void onExoIsPlayingChanged(boolean playing) {
                        isPlaying = playing;
                        updatePlayPauseIcon();
                        if (playing) {
                            startProgressUpdater();
                        }
                    }

                    @Override
                    public void onExoBuffering(long currentPosMs) {
                        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onExoReady(long rawDurationMs, boolean usingSoftwareDecoder, boolean playing) {
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                        if (fallbackImageView != null) fallbackImageView.setVisibility(View.GONE);
                        if (errorText != null) errorText.setVisibility(View.GONE);
                        if (rawDurationMs != C.TIME_UNSET && rawDurationMs > 0 && (!isTimelineMode || eventTotalDurationMs <= 0)) {
                            int durMs = (int) rawDurationMs;
                            eventTotalDurationMs = durMs;
                            if (seekBar != null) seekBar.setMax(durMs);
                            if (timeDurationText != null) timeDurationText.setText(formatDuration(durMs));
                        }
                        if (usingSoftwareDecoder) {
                            updateTransportModeBadge("EXO • SW", "#A78BFA", "#1E1633", "#7C3AED");
                        } else {
                            updateTransportModeBadge("EXO • HLS", "#38BDF8", "#0C2135", "#0284C7");
                        }
                        isPlaying = playing;
                        updatePlayPauseIcon();
                        startProgressUpdater();
                    }

                    @Override
                    public void onExoEnded() {
                        if (!FrigatePlayerOverlay.this.shouldLoopPlayback()) {
                            isPlaying = false;
                            updatePlayPauseIcon();
                            int maxDur = eventTotalDurationMs > 0 ? eventTotalDurationMs : (seekBar != null ? seekBar.getMax() : 0);
                            if (seekBar != null && maxDur > 0) seekBar.setProgress(maxDur);
                            if (timeCurrentText != null && maxDur > 0) timeCurrentText.setText(formatDuration(maxDur));
                            stopProgressUpdater();
                        }
                    }

                    @Override
                    public void onExoRetryWithSoftwareDecoder(String errorMsg) {
                        isExoPlayback = false;
                        loadVideo();
                    }

                    @Override
                    public void onExoFallbackToHls(String errorMsg) {
                        isExoPlayback = false;
                        exoFallbackTriggered = true;
                        loadVideo();
                    }
                });
        this.clipCacheManager = new FrigateClipCacheManager(context, host, config, progressHandler,
                new FrigateClipCacheManager.Listener() {
                    @Override
                    public boolean isClosed() {
                        return isClosed;
                    }

                    @Override
                    public boolean isLiveStreamMode() {
                        return isLiveStreamMode;
                    }

                    @Override
                    public boolean isTimelineMode() {
                        return isTimelineMode;
                    }

                    @Override
                    public boolean isHlsPlayback() {
                        return isHlsPlayback;
                    }

                    @Override
                    public boolean hasHlsFallbackTriggered() {
                        return hlsFallbackTriggered;
                    }

                    @Override
                    public boolean isUserSeeking() {
                        return isUserSeeking;
                    }

                    @Override
                    public boolean isLooping() {
                        return isLooping;
                    }

                    @Override
                    public boolean shouldLoopPlayback() {
                        return FrigatePlayerOverlay.this.shouldLoopPlayback();
                    }

                    @Override
                    public FrigateEvent getEvent() {
                        return event;
                    }

                    @Override
                    public String tr(String key, Object... args) {
                        return FrigatePlayerOverlay.this.tr(key, args);
                    }

                    @Override
                    public void onHlsFallbackToMp4() {
                        isHlsPlayback = false;
                        hlsFallbackTriggered = true;
                        loadVideo();
                    }

                    @Override
                    public void onVideoPrepared(int dur, int vw, int vh) {
                        hideScrubPreview();
                        if (dur > 0 && (!isTimelineMode || isHlsPlayback)) {
                            eventTotalDurationMs = dur;
                            if (seekBar != null) {
                                seekBar.setMax(dur);
                            }
                            if (timeDurationText != null) {
                                timeDurationText.setText(formatDuration(dur));
                            }
                        }
                        if (vw > 0 && vh > 0) {
                            setActiveVideoDimensions(vw, vh, 1.0f);
                        }
                        if (host != null) {
                            host.log("Frigate: Video prepared (" + (isHlsPlayback ? "HLS VOD" : "MP4") + "), duration=" + dur + "ms, totalEventDuration=" + eventTotalDurationMs + "ms");
                        }
                        if (isHlsPlayback) {
                            updateTransportModeBadge("HLS", "#7DD3FC", "#0C2233", "#0284C7");
                        } else {
                            updateTransportModeBadge("MP4", "#FCD34D", "#291D0B", "#F59E0B");
                        }
                        android.util.Log.i("FrigatePlugin", "Video clip prepared, duration=" + dur + "ms");
                        progressBar.setVisibility(View.GONE);
                        errorText.setVisibility(View.GONE);
                        if (videoView != null) {
                            videoView.start();
                        }
                        isPlaying = true;
                        updatePlayPauseIcon();
                        startProgressUpdater();
                    }

                    @Override
                    public void onVideoSizeChanged(int width, int height) {
                        setActiveVideoDimensions(width, height, 1.0f);
                    }

                    @Override
                    public void onVideoSeekCompleted(int posMs) {
                        if (!isUserSeeking && seekBar != null && timeCurrentText != null) {
                            seekBar.setProgress(posMs);
                            timeCurrentText.setText(formatDuration(posMs));
                        }
                    }

                    @Override
                    public void onVideoCompleted() {
                        if (FrigatePlayerOverlay.this.shouldLoopPlayback()) {
                            restartVideoFromBeginning();
                        } else {
                            isPlaying = false;
                            updatePlayPauseIcon();
                            stopProgressUpdater();
                            int totalDurationMs = seekBar.getMax();
                            if (totalDurationMs > 0) {
                                seekBar.setProgress(totalDurationMs);
                                timeCurrentText.setText(formatDuration(totalDurationMs));
                            }
                        }
                    }

                    @Override
                    public void onVideoFatalError(String errorMessage) {
                        progressBar.setVisibility(View.GONE);
                        errorText.setText(errorMessage);
                        errorText.setVisibility(View.VISIBLE);
                        loadEventFallbackThumbnail();
                        stopProgressUpdater();
                    }
                });
    }

    /**
     * Scales DP values according to user-configured uiScale.
     */
    private int s(float dp) {
        float scale = (config != null) ? config.getUiScale() : 1.35f;
        return Math.max(1, Math.round(theme.px(Math.round(dp * scale))));
    }

    /**
     * Scales SP font sizes according to user-configured uiScale.
     */
    private float sp(float sp) {
        float scale = (config != null) ? config.getUiScale() : 1.35f;
        return sp * scale;
    }

    public View buildView() {
        // Semi-transparent dim background covering full screen
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.argb(235, 8, 11, 16));
        root.setOnClickListener(v -> closePlayer());

        // Centered modern modal card
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#0F131D"));
        cardBg.setCornerRadius(s(16));
        cardBg.setStroke(s(1), Color.parseColor("#1E273A"));
        card.setBackground(cardBg);

        int pad = s(16);
        card.setPadding(pad, pad, pad, pad);
        card.setOnClickListener(v -> {}); // Prevent outside touch click

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        int margin = s(16);
        cardLp.setMargins(margin, margin, margin, margin);
        cardLp.gravity = Gravity.CENTER;
        card.setLayoutParams(cardLp);

        // 1. Header with title, badges, and close button
        card.addView(buildHeaderBar());

        // 2. Main Row: Left Column (Video + Transport Bar below video) + Right Full-Height Vertical Timeline Scrubber
        LinearLayout mainRow = new LinearLayout(context);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout leftCol = new LinearLayout(context);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.addView(buildVideoFrame(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
        leftCol.addView(buildTransportBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        mainRow.addView(leftCol, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));

        LinearLayout.LayoutParams rulerPanelLp = new LinearLayout.LayoutParams(
                s(84), LinearLayout.LayoutParams.MATCH_PARENT);
        rulerPanelLp.leftMargin = s(8);
        mainRow.addView(buildVerticalTimelinePanel(), rulerPanelLp);
        card.addView(mainRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // Add modal card into root layout
        root.addView(card, cardLp);

        // Load 24h recordings motion waveform for vertical timeline
        timelineController.loadTimelineRecordings();

        // Start playback in appropriate mode
        if (isLiveStreamMode) {
            startLiveStream();
        } else {
            loadVideo();
        }

        return root;
    }

    private String tr(String key, Object... args) {
        return FrigateI18n.tr(context, config, key, args);
    }

    private View buildHeaderBar() {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, s(14));

        // Camera Name Badge with Material Camera icon
        TextView camBadge = new TextView(context);
        String camName = (currentCamera != null && !currentCamera.isEmpty())
                ? currentCamera.substring(0, 1).toUpperCase(Locale.ROOT) + currentCamera.substring(1)
                : tr("common_camera_fallback");
        camBadge.setText(camName);
        MaterialIcons.setIconOnTextView(camBadge, MaterialIcons.CAMERA, Color.WHITE, s(18), s(8), theme);
        GradientDrawable cbBg = new GradientDrawable();
        cbBg.setColor(Color.parseColor("#1E273A"));
        cbBg.setCornerRadius(s(10));
        cbBg.setStroke(s(1), Color.parseColor("#334155"));
        camBadge.setBackground(cbBg);
        camBadge.setPadding(s(14), s(8), s(14), s(8));
        camBadge.setTextSize(sp(15));
        camBadge.setTextColor(Color.WHITE);
        camBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(camBadge);

        // Bright LIVE indicator pill (visible in real-time stream mode)
        liveBadge = new TextView(context);
        liveBadge.setText(tr("common_live"));
        MaterialIcons.setIconOnTextView(liveBadge, MaterialIcons.LIVE, Color.parseColor("#EF4444"), s(14), s(6), theme);
        GradientDrawable lbBg = new GradientDrawable();
        lbBg.setColor(Color.parseColor("#2A1215"));
        lbBg.setCornerRadius(s(10));
        lbBg.setStroke(s(1), Color.parseColor("#EF4444"));
        liveBadge.setBackground(lbBg);
        liveBadge.setPadding(s(12), s(8), s(12), s(8));
        liveBadge.setTextSize(sp(13));
        liveBadge.setTextColor(Color.parseColor("#FCA5A5"));
        liveBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lbLp.leftMargin = s(10);
        liveBadge.setVisibility(isLiveStreamMode ? View.VISIBLE : View.GONE);
        header.addView(liveBadge, lbLp);

        // Optional transport mode badge (HLS / MP4 / MSE / MJPEG)
        transportModeBadge = new TextView(context);
        transportModeBadge.setTextSize(sp(11));
        transportModeBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        transportModeBadge.setPadding(s(10), s(6), s(10), s(6));
        LinearLayout.LayoutParams tmLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tmLp.leftMargin = s(8);
        transportModeBadge.setVisibility(View.GONE);
        header.addView(transportModeBadge, tmLp);

        // Live FPS badge (MSE / MJPEG)
        liveFpsBadge = new TextView(context);
        liveFpsBadge.setTextSize(sp(11));
        liveFpsBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        liveFpsBadge.setPadding(s(10), s(6), s(10), s(6));
        LinearLayout.LayoutParams fpsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fpsLp.leftMargin = s(6);
        liveFpsBadge.setVisibility(View.GONE);
        header.addView(liveFpsBadge, fpsLp);

        // Dynamic Detection Label Pills with Material icons
        headerObjectsContainer = new LinearLayout(context);
        headerObjectsContainer.setOrientation(LinearLayout.HORIZONTAL);
        headerObjectsContainer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hoLp.leftMargin = s(10);
        headerObjectsContainer.setVisibility(View.GONE);
        header.addView(headerObjectsContainer, hoLp);
        if (!isLiveStreamMode && event != null && event.getLabel() != null && !event.getLabel().isEmpty()) {
            updateObjectBadgesFromEvents(Collections.singletonList(event));
        }

        // Event Timestamp / Live Status
        headerTimeView = new TextView(context);
        String formattedTime = isLiveStreamMode
                ? tr("common_realtime_live")
                : ((event != null && !event.getFormattedDateTime(context, config).isEmpty()) ? event.getFormattedDateTime(context, config) : getFormattedEventTime());
        headerTimeView.setText(formattedTime);
        headerTimeView.setTextSize(sp(14));
        headerTimeView.setTextColor(Color.parseColor("#94A3B8"));
        MaterialIcons.setIconOnTextView(headerTimeView, MaterialIcons.CALENDAR, Color.parseColor("#94A3B8"), s(18), s(8), theme);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        timeLp.leftMargin = s(14);
        header.addView(headerTimeView, timeLp);

        // Close Button with Material Close icon
        TextView closeBtn = new TextView(context);
        closeBtn.setText(tr("common_close"));
        MaterialIcons.setIconOnTextView(closeBtn, MaterialIcons.CLOSE, Color.parseColor("#EF4444"), s(16), s(6), theme);
        FrigateUiUtils.applyCloseButtonFeedback(
                closeBtn,
                Color.parseColor("#261418"),
                Color.parseColor("#7F1D1D"),
                Color.parseColor("#EF4444"),
                Color.parseColor("#FCA5A5"),
                s(10),
                s(1)
        );
        closeBtn.setPadding(s(16), s(8), s(16), s(8));
        closeBtn.setTextSize(sp(14));
        closeBtn.setTextColor(Color.parseColor("#EF4444"));
        closeBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        closeBtn.setOnClickListener(v -> {
            if (isClosed) return;
            FrigateUiUtils.markButtonClosing(v);
            closePlayer();
        });
        header.addView(closeBtn);

        return header;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private View buildVideoFrame() {
        FrameLayout videoContainer = new FrameLayout(context) {
            private float touchDownX;
            private float touchDownY;

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    touchDownX = ev.getX();
                    touchDownY = ev.getY();
                    if (isVideoOverlayControlsVisible()) {
                        scheduleOverlayAutoHide();
                    }
                } else if (action == MotionEvent.ACTION_UP) {
                    float dx = Math.abs(ev.getX() - touchDownX);
                    float dy = Math.abs(ev.getY() - touchDownY);
                    if (dx < s(12) && dy < s(12)) {
                        boolean tappedOverlayBtn = isVideoOverlayControlsVisible()
                                && isPointInsideView(videoOverlayControls, ev.getX(), ev.getY());
                        boolean tappedError = errorText != null
                                && errorText.getVisibility() == View.VISIBLE
                                && isPointInsideView(errorText, ev.getX(), ev.getY());
                        if (!tappedOverlayBtn && !tappedError && (errorText == null || errorText.getVisibility() != View.VISIBLE)) {
                            if (isVideoOverlayControlsVisible()) {
                                hideVideoOverlayControls(true);
                            } else {
                                showVideoOverlayControls(true);
                            }
                        }
                    }
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        videoContainer.setClickable(true);
        this.videoContainerRef = videoContainer;
        GradientDrawable frameBg = new GradientDrawable();
        frameBg.setColor(Color.BLACK);
        frameBg.setCornerRadius(s(12));
        videoContainer.setBackground(frameBg);
        videoContainer.setClipToOutline(true);

        // 0. Native Media3 ExoPlayer TextureView (primary hardware HLS VOD playback)
        videoContainer.addView(exoController.createTextureView());
        videoContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                exoController.updateTextureSize();
                updateObjectBoxesBounds();
            }
        });

        // 1. Native Android VideoView (for recorded MP4 clips & history)
        videoView = new VideoView(context);
        FrameLayout.LayoutParams vvLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        vvLp.gravity = Gravity.CENTER;
        videoView.setLayoutParams(vvLp);
        videoView.setVisibility(View.GONE);
        videoContainer.addView(videoView);

        // 2. HTML5 MSE WebSocket WebView (for live HD video + audio via Frigate go2rtc)
        videoContainer.addView(webStreamController.createWebView(context), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 3. Real-time MJPEG Live Stream ImageView (fallback if go2rtc MSE is unavailable)
        liveImageView = new ImageView(context);
        liveImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        liveImageView.setVisibility(View.GONE);
        videoContainer.addView(liveImageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 4. Fallback snapshot image view (displayed if video playback is unsupported or failed)
        fallbackImageView = new ImageView(context);
        fallbackImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fallbackImageView.setVisibility(View.GONE);
        videoContainer.addView(fallbackImageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 4b. Real-time low-res snapshot preview displayed while scrubbing vertical timeline
        scrubPreviewView = new ImageView(context);
        scrubPreviewView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        scrubPreviewView.setBackgroundColor(Color.BLACK);
        scrubPreviewView.setVisibility(View.GONE);
        videoContainer.addView(scrubPreviewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 4c. Historical object bounding boxes overlay (interpolated along trajectory)
        objectBoxesOverlay = new FrigateObjectBoxesOverlayView(context);
        objectBoxesOverlay.setConfig(config);
        objectBoxesOverlay.setUiScale(config != null ? config.getUiScale() : 1.35f);
        objectBoxesOverlay.setVisibility((!isLiveStreamMode && showObjectBoxes) ? View.VISIBLE : View.GONE);
        videoContainer.addView(objectBoxesOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 4d. Scrubbing timestamp HUD pill over preview
        scrubHudPill = new TextView(context);
        scrubHudPill.setVisibility(View.GONE);
        GradientDrawable shBg = new GradientDrawable();
        shBg.setColor(Color.argb(220, 15, 23, 42));
        shBg.setCornerRadius(s(14));
        shBg.setStroke(s(1), Color.parseColor("#EF4444"));
        scrubHudPill.setBackground(shBg);
        scrubHudPill.setPadding(s(14), s(6), s(14), s(6));
        scrubHudPill.setTextColor(Color.WHITE);
        scrubHudPill.setTextSize(sp(13));
        scrubHudPill.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        FrameLayout.LayoutParams shLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        shLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        shLp.topMargin = s(14);
        scrubHudPill.setLayoutParams(shLp);
        videoContainer.addView(scrubHudPill);

        // 5. Buffering / Loading Spinner
        progressBar = new ProgressBar(context);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        pbLp.gravity = Gravity.CENTER;
        progressBar.setLayoutParams(pbLp);
        videoContainer.addView(progressBar);

        // 6. Error message text view (hidden by default)
        errorText = new TextView(context);
        errorText.setVisibility(View.GONE);
        GradientDrawable errBg = new GradientDrawable();
        errBg.setColor(Color.argb(240, 18, 22, 28));
        errBg.setCornerRadius(s(10));
        errBg.setStroke(s(1), Color.argb(120, 255, 152, 0));
        errorText.setBackground(errBg);
        int padErrH = s(18);
        int padErrV = s(14);
        errorText.setPadding(padErrH, padErrV, padErrH, padErrV);
        errorText.setTextColor(Color.parseColor("#FFCC80"));
        errorText.setTextSize(sp(14));
        errorText.setGravity(Gravity.CENTER);
        errorText.setText(tr("player_error_default"));
        FrameLayout.LayoutParams errLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        errLp.gravity = Gravity.CENTER;
        int m = s(20);
        errLp.setMargins(m, m, m, m);
        errorText.setLayoutParams(errLp);
        errorText.setOnClickListener(v -> {
            if (isLiveStreamMode) {
                startLiveStream();
            } else {
                exoController.resetSoftwareDecoderPreference();
                exoFallbackTriggered = false;
                hlsFallbackTriggered = false;
                loadVideo();
            }
        });
        videoContainer.addView(errorText);

        // 7. Translucent auto-fading overlay transport controls (-10s, Play/Pause, +10s) over the video
        videoOverlayControls = buildVideoOverlayControls();
        FrameLayout.LayoutParams ovLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        ovLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        ovLp.bottomMargin = s(20);
        videoContainer.addView(videoOverlayControls, ovLp);
        videoOverlayVisible = true;
        scheduleOverlayAutoHide();

        setupVideoListeners();

        return videoContainer;
    }

    private LinearLayout buildVideoOverlayControls() {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(Color.argb(185, 11, 16, 26));
        barBg.setCornerRadius(s(34));
        barBg.setStroke(s(1), Color.argb(70, 255, 255, 255));
        bar.setBackground(barBg);
        bar.setPadding(s(12), s(8), s(12), s(8));

        // Rewind -10s Button
        TextView rewindBtn = new TextView(context);
        rewindBtn.setText(tr("player_rewind_10"));
        rewindBtn.setGravity(Gravity.CENTER);
        MaterialIcons.setIconOnTextView(rewindBtn, MaterialIcons.REWIND_10, Color.WHITE, s(20), s(6), theme);
        GradientDrawable rwBg = new GradientDrawable();
        rwBg.setColor(Color.argb(135, 30, 41, 59));
        rwBg.setCornerRadius(s(20));
        rwBg.setStroke(s(1), Color.argb(90, 148, 163, 184));
        rewindBtn.setBackground(rwBg);
        rewindBtn.setPadding(s(14), s(8), s(14), s(8));
        rewindBtn.setTextSize(sp(14));
        rewindBtn.setTextColor(Color.WHITE);
        rewindBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        rewindBtn.setOnClickListener(v -> {
            seekDelta(-10);
            showVideoOverlayControls(true);
        });
        bar.addView(rewindBtn);

        // Play / Pause Toggle Button (circular accent center button)
        playPauseBtn = new ImageView(context);
        playPauseBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        GradientDrawable ppBg = new GradientDrawable();
        ppBg.setColor(Color.argb(235, 229, 57, 53));
        ppBg.setCornerRadius(s(26));
        playPauseBtn.setBackground(ppBg);
        int btnSize = s(52);
        LinearLayout.LayoutParams ppLp = new LinearLayout.LayoutParams(btnSize, btnSize);
        ppLp.leftMargin = s(12);
        ppLp.rightMargin = s(12);
        playPauseBtn.setLayoutParams(ppLp);
        updatePlayPauseIcon();
        playPauseBtn.setOnClickListener(v -> togglePlayPause());
        bar.addView(playPauseBtn);

        // Forward +10s Button
        TextView forwardBtn = new TextView(context);
        forwardBtn.setText(tr("player_forward_10"));
        forwardBtn.setGravity(Gravity.CENTER);
        MaterialIcons.VectorDrawable fwdIcon = MaterialIcons.getDrawable(MaterialIcons.FORWARD_10, Color.WHITE, s(20), theme);
        if (fwdIcon != null) {
            fwdIcon.setBounds(0, 0, s(20), s(20));
            forwardBtn.setCompoundDrawables(null, null, fwdIcon, null);
            forwardBtn.setCompoundDrawablePadding(s(6));
        }
        GradientDrawable fwBg = new GradientDrawable();
        fwBg.setColor(Color.argb(135, 30, 41, 59));
        fwBg.setCornerRadius(s(20));
        fwBg.setStroke(s(1), Color.argb(90, 148, 163, 184));
        forwardBtn.setBackground(fwBg);
        forwardBtn.setPadding(s(14), s(8), s(14), s(8));
        forwardBtn.setTextSize(sp(14));
        forwardBtn.setTextColor(Color.WHITE);
        forwardBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        forwardBtn.setOnClickListener(v -> {
            seekDelta(10);
            showVideoOverlayControls(true);
        });
        bar.addView(forwardBtn);

        return bar;
    }

    private View buildTransportBar() {
        LinearLayout transport = new LinearLayout(context);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        transport.setPadding(0, s(10), 0, s(2));

        // Current Position Label
        timeCurrentText = new TextView(context);
        timeCurrentText.setText(isLiveStreamMode ? tr("common_live") : "00:00");
        timeCurrentText.setTextSize(sp(14));
        timeCurrentText.setTextColor(Color.parseColor("#F1F5F9"));
        timeCurrentText.setTypeface(null, android.graphics.Typeface.BOLD);
        int padH = s(12);
        timeCurrentText.setPadding(0, 0, padH, 0);
        transport.addView(timeCurrentText);

        // Playback Scrubber (SeekBar)
        seekBar = new SeekBar(context);
        theme.styleSlider(seekBar);
        int initialDurationMs = 0;
        if (!isLiveStreamMode && event != null && event.getEndTime() > event.getStartTime()) {
            initialDurationMs = (int) Math.round((event.getEndTime() - event.getStartTime()) * 1000);
        } else if (isTimelineMode) {
            initialDurationMs = (int) (timelineDurationSeconds * 1000);
        }
        seekBar.setMax(initialDurationMs > 0 ? initialDurationMs : 100);
        if (isLiveStreamMode) {
            seekBar.setEnabled(false);
            seekBar.setProgress(100);
        }
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && !isLiveStreamMode) {
                    timeCurrentText.setText(formatDuration(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                if (isLiveStreamMode) return;
                isUserSeeking = true;
                if (isExoPlayback && exoController.hasPlayer()) {
                    wasPlayingBeforeSeek = isPlaying;
                    exoController.setPlayWhenReady(false);
                } else if (isHlsPlayback && webStreamController.hasWebView()) {
                    wasPlayingBeforeSeek = isPlaying;
                    webStreamController.pauseVod();
                } else if (videoView != null && videoView.isPlaying()) {
                    wasPlayingBeforeSeek = true;
                    try {
                        videoView.pause();
                    } catch (Exception ignored) {}
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                if (isLiveStreamMode) return;
                int targetMs = sb.getProgress();
                if (isExoPlayback) {
                    seekExoToMs(targetMs);
                } else if (isHlsPlayback) {
                    seekHlsToMs(targetMs);
                } else {
                    seekToSeconds(targetMs / 1000);
                }
                isUserSeeking = false;
            }
        });
        transport.addView(seekBar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        // Total Duration Label
        timeDurationText = new TextView(context);
        timeDurationText.setText(isLiveStreamMode ? tr("common_live") : (initialDurationMs > 0 ? formatDuration(initialDurationMs) : "00:00"));
        timeDurationText.setTextSize(sp(14));
        timeDurationText.setTextColor(Color.parseColor("#94A3B8"));
        timeDurationText.setPadding(padH, 0, padH, 0);
        transport.addView(timeDurationText);

        // Flex spacer shown in History or Live mode when horizontal seekBar/duration labels are hidden
        transportSpacer = new View(context);
        transportSpacer.setVisibility(View.GONE);
        transport.addView(transportSpacer, new LinearLayout.LayoutParams(0, 1, 1.0f));
        updateSeekBarVisibility();

        // Mute / Unmute Audio Toggle Button (shown in Live MSE or VOD with audio)
        muteBtn = new TextView(context);
        muteBtn.setText(tr("player_sound_on"));
        muteBtn.setGravity(Gravity.CENTER);
        updateMuteButtonStyle();
        muteBtn.setPadding(s(14), s(10), s(14), s(10));
        muteBtn.setTextSize(sp(13));
        muteBtn.setVisibility(View.GONE);
        muteBtn.setOnClickListener(v -> {
            isMuted = !isMuted;
            updateMuteButtonStyle();
            if (isExoPlayback && exoController.hasPlayer()) {
                exoController.setMuted(isMuted);
            } else if (webStreamController.hasWebView() && (isLiveMseActive || isHlsPlayback)) {
                webStreamController.setMuted(isMuted);
            }
        });
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mLp.rightMargin = s(8);
        transport.addView(muteBtn, mLp);

        // Object Bounding Boxes Toggle Button (shown in Historical / Event modes only)
        bboxBtn = new TextView(context);
        bboxBtn.setText(tr("player_boxes"));
        bboxBtn.setGravity(Gravity.CENTER);
        updateBboxButtonStyle();
        bboxBtn.setPadding(s(14), s(10), s(14), s(10));
        bboxBtn.setTextSize(sp(13));
        updateBboxButtonVisibility();
        bboxBtn.setOnClickListener(v -> {
            showObjectBoxes = !showObjectBoxes;
            updateBboxButtonStyle();
            updateObjectBoxesOverlayVisibility();
        });
        LinearLayout.LayoutParams bbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bbLp.rightMargin = s(8);
        transport.addView(bboxBtn, bbLp);

        // Loop Toggle Button with Material Repeat vector icon (shown in Recorded mode)
        loopBtn = new TextView(context);
        loopBtn.setText(tr("player_loop"));
        loopBtn.setGravity(Gravity.CENTER);
        updateLoopButtonStyle();
        loopBtn.setPadding(s(14), s(10), s(14), s(10));
        loopBtn.setTextSize(sp(13));
        updateLoopButtonVisibility();
        loopBtn.setOnClickListener(v -> {
            isLooping = !isLooping;
            updateLoopButtonStyle();
            boolean activeLoop = shouldLoopPlayback();
            if (isExoPlayback && exoController.hasPlayer()) {
                exoController.setLooping(activeLoop);
            } else if (isHlsPlayback && webStreamController.hasWebView()) {
                webStreamController.setVodLoop(activeLoop);
            }
        });
        transport.addView(loopBtn);

        return transport;
    }

    private void updateTransportModeBadge(String label, String textHex, String bgHex, String strokeHex) {
        if ("PREVIEW".equals(label)) {
            hideLiveFpsBadge();
        }
        if (transportModeBadge == null) return;
        if (config != null && !config.isShowStreamModePill()) {
            transportModeBadge.setVisibility(View.GONE);
            return;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(bgHex));
        bg.setCornerRadius(s(8));
        bg.setStroke(s(1), Color.parseColor(strokeHex));
        transportModeBadge.setBackground(bg);
        transportModeBadge.setText(label);
        transportModeBadge.setTextColor(Color.parseColor(textHex));
        transportModeBadge.setVisibility(View.VISIBLE);
    }

    private void updateLiveFpsBadge(int fps) {
        if (liveFpsBadge == null) return;
        boolean active = isLiveStreamMode ? !isLivePaused : (isPlaying && (isExoPlayback || isHlsPlayback));
        if (!active || fps < 0) {
            liveFpsBadge.setVisibility(View.GONE);
            return;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#0F1E33"));
        bg.setCornerRadius(s(8));
        bg.setStroke(s(1), Color.parseColor("#3B82F6"));
        liveFpsBadge.setBackground(bg);
        liveFpsBadge.setText(fps + " FPS");
        liveFpsBadge.setTextColor(Color.parseColor("#93C5FD"));
        liveFpsBadge.setVisibility(View.VISIBLE);
    }

    private void hideLiveFpsBadge() {
        if (liveFpsBadge != null) {
            liveFpsBadge.setVisibility(View.GONE);
        }
    }

    private boolean isVideoOverlayControlsVisible() {
        return videoOverlayControls != null
                && videoOverlayVisible
                && videoOverlayControls.getVisibility() == View.VISIBLE;
    }

    private boolean isPointInsideView(View v, float x, float y) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        return x >= v.getLeft() && x <= v.getRight() && y >= v.getTop() && y <= v.getBottom();
    }

    private void showVideoOverlayControls(boolean autoHide) {
        if (videoOverlayControls == null || isClosed) return;
        progressHandler.removeCallbacks(hideOverlayControlsRunnable);
        videoOverlayControls.animate().cancel();
        videoOverlayVisible = true;
        if (videoOverlayControls.getVisibility() != View.VISIBLE) {
            videoOverlayControls.setAlpha(0f);
            videoOverlayControls.setVisibility(View.VISIBLE);
        }
        videoOverlayControls.animate()
                .alpha(1f)
                .setDuration(200)
                .withEndAction(null)
                .start();
        if (autoHide && isPlaying && !isLivePaused) {
            progressHandler.postDelayed(hideOverlayControlsRunnable, OVERLAY_AUTO_HIDE_MS);
        }
    }

    private void hideVideoOverlayControls(boolean animated) {
        if (videoOverlayControls == null) return;
        progressHandler.removeCallbacks(hideOverlayControlsRunnable);
        videoOverlayControls.animate().cancel();
        videoOverlayVisible = false;
        if (!animated) {
            videoOverlayControls.setAlpha(0f);
            videoOverlayControls.setVisibility(View.GONE);
            return;
        }
        videoOverlayControls.animate()
                .alpha(0f)
                .setDuration(260)
                .withEndAction(() -> {
                    if (!videoOverlayVisible && videoOverlayControls != null) {
                        videoOverlayControls.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private void scheduleOverlayAutoHide() {
        progressHandler.removeCallbacks(hideOverlayControlsRunnable);
        if (!isClosed && videoOverlayVisible && isPlaying && !isLivePaused) {
            progressHandler.postDelayed(hideOverlayControlsRunnable, OVERLAY_AUTO_HIDE_MS);
        }
    }

    private void updatePlayPauseIcon() {
        if (playPauseBtn == null) return;
        int iconSize = s(28);
        String iconName = isPlaying ? MaterialIcons.PAUSE : MaterialIcons.PLAY;
        playPauseBtn.setImageDrawable(MaterialIcons.getDrawable(iconName, Color.WHITE, iconSize, theme));
        if (!isPlaying || isLivePaused) {
            if (errorText == null || errorText.getVisibility() != View.VISIBLE) {
                showVideoOverlayControls(false);
            }
        } else {
            scheduleOverlayAutoHide();
        }
    }

    private void updateMuteButtonStyle() {
        if (muteBtn == null) return;
        GradientDrawable mBg = new GradientDrawable();
        int iconColor;
        if (!isMuted) {
            mBg.setColor(Color.parseColor("#2563EB")); // Active blue
            mBg.setCornerRadius(s(12));
            muteBtn.setBackground(mBg);
            muteBtn.setText(tr("player_sound_on"));
            muteBtn.setTextColor(Color.WHITE);
            muteBtn.setTypeface(null, android.graphics.Typeface.BOLD);
            iconColor = Color.WHITE;
        } else {
            mBg.setColor(Color.parseColor("#1A2234"));
            mBg.setCornerRadius(s(12));
            mBg.setStroke(s(1), Color.parseColor("#2D3B54"));
            muteBtn.setBackground(mBg);
            muteBtn.setText(tr("player_sound_muted"));
            muteBtn.setTextColor(Color.parseColor("#94A3B8"));
            muteBtn.setTypeface(null, android.graphics.Typeface.NORMAL);
            iconColor = Color.parseColor("#94A3B8");
        }
        MaterialIcons.setIconOnTextView(muteBtn, isMuted ? MaterialIcons.VOLUME_OFF : MaterialIcons.VOLUME_UP,
                iconColor, s(18), s(6), theme);
    }

    private void applyTogglePillStyle(TextView btn, boolean active, String iconName) {
        if (btn == null) return;
        GradientDrawable bg = new GradientDrawable();
        int iconColor;
        if (active) {
            bg.setColor(Color.parseColor("#2563EB"));
            bg.setCornerRadius(s(12));
            btn.setBackground(bg);
            btn.setTextColor(Color.WHITE);
            btn.setTypeface(null, android.graphics.Typeface.BOLD);
            iconColor = Color.WHITE;
        } else {
            bg.setColor(Color.parseColor("#1A2234"));
            bg.setCornerRadius(s(12));
            bg.setStroke(s(1), Color.parseColor("#2D3B54"));
            btn.setBackground(bg);
            btn.setTextColor(Color.parseColor("#64748B"));
            btn.setTypeface(null, android.graphics.Typeface.NORMAL);
            iconColor = Color.parseColor("#64748B");
        }
        MaterialIcons.setIconOnTextView(btn, iconName, iconColor, s(18), s(6), theme);
    }

    private void updateLoopButtonStyle() {
        applyTogglePillStyle(loopBtn, isLooping, MaterialIcons.REPEAT);
    }

    private void updateBboxButtonStyle() {
        applyTogglePillStyle(bboxBtn, showObjectBoxes, MaterialIcons.BOUNDING_BOX);
    }

    private void updateBboxButtonVisibility() {
        if (bboxBtn != null) {
            bboxBtn.setVisibility(isLiveStreamMode ? View.GONE : View.VISIBLE);
        }
    }

    private void setActiveVideoDimensions(int videoW, int videoH, float pixelAspect) {
        this.activeVideoWidth = Math.max(0, videoW);
        this.activeVideoHeight = Math.max(0, videoH);
        this.activeVideoPixelAspect = (pixelAspect > 0f) ? pixelAspect : 1.0f;
        updateObjectBoxesBounds();
    }

    private void updateObjectBoxesBounds() {
        if (objectBoxesOverlay == null || videoContainerRef == null) return;
        objectBoxesOverlay.setVideoContentBounds(
                videoContainerRef.getWidth(),
                videoContainerRef.getHeight(),
                activeVideoWidth,
                activeVideoHeight,
                activeVideoPixelAspect
        );
    }

    private void updateObjectBoxesOverlayVisibility() {
        if (objectBoxesOverlay == null) return;
        if (isLiveStreamMode || !showObjectBoxes || isRulerScrubbing) {
            objectBoxesOverlay.clear();
            objectBoxesOverlay.setVisibility(View.GONE);
        } else {
            objectBoxesOverlay.setVisibility(View.VISIBLE);
            updateObjectBoxesBounds();
            updateObjectBoxesForEpoch(getCurrentPlaybackEpochDouble());
        }
    }

    private boolean shouldLoopPlayback() {
        return !isLiveStreamMode && !isTimelineMode && isLooping;
    }

    private void updateLoopButtonVisibility() {
        if (loopBtn != null) {
            loopBtn.setVisibility((isLiveStreamMode || isTimelineMode) ? View.GONE : View.VISIBLE);
        }
    }

    private void updateSeekBarVisibility() {
        boolean hideSeekBar = isLiveStreamMode || isHistoryMode;
        int barVisibility = hideSeekBar ? View.GONE : View.VISIBLE;
        int spacerVisibility = hideSeekBar ? View.VISIBLE : View.GONE;
        if (timeCurrentText != null) {
            timeCurrentText.setVisibility(barVisibility);
        }
        if (seekBar != null) {
            seekBar.setVisibility(barVisibility);
        }
        if (timeDurationText != null) {
            timeDurationText.setVisibility(barVisibility);
        }
        if (transportSpacer != null) {
            transportSpacer.setVisibility(spacerVisibility);
        }
    }

    // --- Vertical Frigate-Style Timeline & Real-Time Low-Res Snapshot Scrubbing ---

    private View buildVerticalTimelinePanel() {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);

        FrameLayout rulerWrapper = new FrameLayout(context);
        GradientDrawable rBg = new GradientDrawable();
        rBg.setColor(Color.parseColor("#0B101A"));
        rBg.setCornerRadius(s(12));
        rBg.setStroke(s(1), Color.parseColor("#1E273A"));
        rulerWrapper.setBackground(rBg);
        rulerWrapper.setClipToOutline(true);

        float scale = (config != null) ? config.getUiScale() : 1.35f;
        timelineRuler = new FrigateTimelineRulerView(context, scale);
        timelineRuler.setConfig(config);
        long now = System.currentTimeMillis() / 1000L;
        timelineRuler.setTimeRange(0L, now);
        long initCursor = isLiveStreamMode
                ? now
                : ((event != null && event.getStartTime() > 0) ? (long) event.getStartTime() : timelineStartEpoch);
        timelineRuler.setCursorEpoch(initCursor, isLiveStreamMode);
        timelineRuler.setOnScrubListener(new FrigateTimelineRulerView.OnScrubListener() {
            @Override
            public void onScrubStart(long epochSeconds) {
                onRulerScrubStart(epochSeconds);
            }

            @Override
            public void onScrubChanged(long epochSeconds) {
                onRulerScrubChanged(epochSeconds);
            }

            @Override
            public void onScrubEnd(long epochSeconds) {
                onRulerScrubEnd(epochSeconds);
            }

            @Override
            public void onViewportChanged(long bottomEpochSeconds, long topEpochSeconds) {
                timelineController.ensureTimelineCoverage(bottomEpochSeconds, topEpochSeconds);
            }
        });
        rulerWrapper.addView(timelineRuler, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        panel.addView(rulerWrapper, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // Bottom zoom-out / zoom-in magnifying glass buttons matching Frigate history view
        LinearLayout zoomRow = new LinearLayout(context);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams zrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        zrLp.topMargin = s(6);

        ImageView zoomOutBtn = buildZoomButton(MaterialIcons.ZOOM_OUT, tr("browser_zoom_out_desc"));
        zoomOutBtn.setOnClickListener(v -> {
            if (timelineRuler != null) timelineRuler.zoomOut();
        });
        ImageView zoomInBtn = buildZoomButton(MaterialIcons.ZOOM_IN, tr("browser_zoom_in_desc"));
        zoomInBtn.setOnClickListener(v -> {
            if (timelineRuler != null) timelineRuler.zoomIn();
        });

        LinearLayout.LayoutParams zBtnLp = new LinearLayout.LayoutParams(0, s(32), 1.0f);
        zBtnLp.rightMargin = s(4);
        zoomRow.addView(zoomOutBtn, zBtnLp);
        zoomRow.addView(zoomInBtn, new LinearLayout.LayoutParams(0, s(32), 1.0f));

        panel.addView(zoomRow, zrLp);
        return panel;
    }

    private ImageView buildZoomButton(String iconName, String description) {
        int iconSize = s(18);
        ImageView btn = MaterialIcons.createIconView(context, iconName, Color.parseColor("#E2E8F0"), iconSize, theme);
        btn.setContentDescription(description);
        int pad = s(6);
        btn.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#161D2B"));
        bg.setCornerRadius(s(8));
        bg.setStroke(s(1), Color.parseColor("#2A374F"));
        btn.setBackground(bg);
        return btn;
    }

    private void onRulerScrubStart(long epochSeconds) {
        isRulerScrubbing = true;
        hideVideoOverlayControls(true);
        updateObjectBoxesOverlayVisibility();
        updateObjectBadgesForEpoch(epochSeconds);
        if (isExoPlayback && exoController.hasPlayer()) {
            try {
                exoController.setPlayWhenReady(false);
            } catch (Exception ignored) {}
        }
        updateScrubHud(epochSeconds);
        timelineController.requestScrubSnapshot(epochSeconds);
    }

    private void onRulerScrubChanged(long epochSeconds) {
        updateObjectBadgesForEpoch(epochSeconds);
        updateScrubHud(epochSeconds);
        timelineController.requestScrubSnapshot(epochSeconds);
    }

    private void onRulerScrubEnd(long epochSeconds) {
        isRulerScrubbing = false;
        if (scrubHudPill != null) {
            scrubHudPill.setVisibility(View.GONE);
        }
        long now = System.currentTimeMillis() / 1000L;
        if (now - epochSeconds <= 4L) {
            startLiveStream();
            return;
        }
        long windowSeconds = Math.max(30L, Math.min(1800L, now - epochSeconds));
        switchToRecordedMode(epochSeconds, windowSeconds);
    }

    private void updateScrubHud(long epochSeconds) {
        long epochMs = epochSeconds * 1000L;
        if (scrubHudPill != null) {
            scrubHudPill.setText(tr("player_scrub_preview", FrigateDateTimeUtils.formatTimeWithSeconds(context, config, epochMs)));
            scrubHudPill.setVisibility(View.VISIBLE);
        }
        if (headerTimeView != null) {
            headerTimeView.setText(FrigateDateTimeUtils.formatWeekdayDateTime(context, config, epochMs));
        }
    }

    private void hideScrubPreview() {
        if (scrubHudPill != null) {
            scrubHudPill.setVisibility(View.GONE);
        }
        if (scrubPreviewView != null && !isRulerScrubbing) {
            scrubPreviewView.setVisibility(View.GONE);
        }
        if (!isRulerScrubbing) {
            updateObjectBoxesOverlayVisibility();
        }
    }

    // --- Real-Time Live Stream (go2rtc MSE with Audio -> MJPEG Fallback) ---

    private void startLiveStream() {
        final int sessionId = ++streamSessionId;
        stopMjpegConnectionOnly();
        stopProgressUpdater();
        hideLiveFpsBadge();

        isLiveStreamMode = true;
        isLiveMseActive = true;
        isExoPlayback = false;
        exoController.resetSoftwareDecoderPreference();
        exoFallbackTriggered = false;
        isHlsPlayback = false;
        hlsFallbackTriggered = false;
        isLivePaused = false;
        isTimelineMode = false;
        isHistoryMode = false;
        isPlaying = true;

        stopExoPlayer();
        if (videoView != null) {
            try {
                videoView.stopPlayback();
            } catch (Exception ignored) {}
            videoView.setVisibility(View.GONE);
        }
        if (liveImageView != null) {
            liveImageView.setVisibility(View.GONE);
        }
        showLiveSnapshotPlaceholder();
        if (errorText != null) {
            errorText.setVisibility(View.GONE);
        }
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }
        if (liveBadge != null) {
            liveBadge.setVisibility(View.VISIBLE);
        }
        updateObjectBadgesFromEvents(Collections.emptyList());
        startLiveObjectPoller();
        if (headerTimeView != null) {
            headerTimeView.setText(tr("common_realtime_live"));
        }
        updateLoopButtonVisibility();
        updateBboxButtonVisibility();
        updateSeekBarVisibility();
        setActiveVideoDimensions(0, 0, 1.0f);
        updateObjectBoxesOverlayVisibility();
        if (muteBtn != null) {
            muteBtn.setVisibility(View.GONE);
        }
        if (seekBar != null) {
            seekBar.setEnabled(false);
            seekBar.setMax(100);
            seekBar.setProgress(100);
        }
        if (timeCurrentText != null) {
            timeCurrentText.setText(tr("common_live"));
        }
        if (timeDurationText != null) {
            timeDurationText.setText(tr("common_live"));
        }
        if (timelineRuler != null) {
            timelineRuler.setCursorEpoch(System.currentTimeMillis() / 1000L, true);
        }
        timelineController.resetLiveTailTimer();
        startProgressUpdater();
        updatePlayPauseIcon();

        if (config != null && config.hasCredentials() && config.getAuthToken().isEmpty()) {
            apiClient.ensureAuthenticatedAsync(() -> progressHandler.post(() -> {
                if (!isClosed && isLiveStreamMode && sessionId == streamSessionId) {
                    startLiveMseWebView(sessionId);
                }
            }));
        } else {
            startLiveMseWebView(sessionId);
        }
    }

    private void startLiveMseWebView(int sessionId) {
        webStreamController.startLiveMse(currentCamera, sessionId, isMuted);
    }

    private void showLiveSnapshotPlaceholder() {
        if (fallbackImageView == null || currentCamera == null || currentCamera.trim().isEmpty()) {
            return;
        }
        String baseLatestUrl = apiClient.getLatestFrameUrl(currentCamera);
        Bitmap cached = AsyncImageLoader.getLastKnownSnapshot(baseLatestUrl);
        if (cached != null && !cached.isRecycled()) {
            fallbackImageView.setImageBitmap(cached);
        } else {
            fallbackImageView.setImageDrawable(null);
        }
        fallbackImageView.setVisibility(View.VISIBLE);
        imageLoader.loadFresh(baseLatestUrl + "?t=" + System.currentTimeMillis(), fallbackImageView);
    }

    private void startHlsWebView(int sessionId, String hlsUrl) {
        if (!webStreamController.hasWebView() || isClosed) return;
        if (videoView != null) {
            try {
                videoView.stopPlayback();
            } catch (Exception ignored) {}
            videoView.setVisibility(View.GONE);
        }
        if (liveImageView != null) {
            liveImageView.setVisibility(View.GONE);
        }
        webStreamController.startHlsVod(hlsUrl, sessionId, isMuted, shouldLoopPlayback());
    }

    private void stopLiveWebView() {
        isLiveMseActive = false;
        if (muteBtn != null) {
            muteBtn.setVisibility(View.GONE);
        }
        webStreamController.stop();
    }

    private void startNativeMjpegStream(int sessionId) {
        mjpegController.start(currentCamera, sessionId);
    }

    private void stopAllLiveStreams() {
        streamSessionId++;
        stopLiveObjectPoller();
        stopMjpegConnectionOnly();
        stopLiveWebView();
        stopExoPlayer();
    }

    private void stopMjpegConnectionOnly() {
        mjpegController.stop();
    }

    private void switchToRecordedMode(long startEpoch, long durationSeconds) {
        stopAllLiveStreams();
        isLiveStreamMode = false;
        isLivePaused = false;
        isTimelineMode = true;
        isHistoryMode = true;
        isExoPlayback = false;
        exoController.resetSoftwareDecoderPreference();
        exoFallbackTriggered = false;
        isHlsPlayback = false;
        hlsFallbackTriggered = false;
        timelineStartEpoch = startEpoch;
        timelineDurationSeconds = durationSeconds;
        eventTotalDurationMs = (int) (durationSeconds * 1000);

        if (liveImageView != null) {
            liveImageView.setVisibility(View.GONE);
        }
        if (videoView != null) {
            videoView.setVisibility(View.VISIBLE);
        }
        if (liveBadge != null) {
            liveBadge.setVisibility(View.GONE);
        }
        updateObjectBadgesForEpoch(startEpoch);
        if (muteBtn != null) {
            muteBtn.setVisibility(View.GONE);
        }
        updateLoopButtonVisibility();
        updateBboxButtonVisibility();
        updateSeekBarVisibility();
        setActiveVideoDimensions(0, 0, 1.0f);
        updateObjectBoxesOverlayVisibility();
        if (seekBar != null) {
            seekBar.setEnabled(true);
            seekBar.setMax(eventTotalDurationMs);
            seekBar.setProgress(0);
        }
        if (timeCurrentText != null) {
            timeCurrentText.setText("00:00");
        }
        if (timeDurationText != null) {
            timeDurationText.setText(formatDuration(eventTotalDurationMs));
        }
        if (headerTimeView != null) {
            headerTimeView.setText(FrigateDateTimeUtils.formatWeekdayDateTime(context, config, startEpoch * 1000L));
        }
        if (timelineRuler != null) {
            timelineRuler.setCursorEpoch(startEpoch, false);
        }

        loadVideo();
    }

    private void seekDelta(int deltaSeconds) {
        if (isLiveStreamMode) {
            if (deltaSeconds < 0) {
                // Jump back 30s from live edge into recorded HLS VOD so user can replay what just happened
                long startEpoch = (System.currentTimeMillis() / 1000L) - 30;
                switchToRecordedMode(startEpoch, 30);
            } else if (isLivePaused) {
                startLiveStream();
            }
            return;
        }
        if (isExoPlayback && exoController.hasPlayer()) {
            int currentPos = (int) exoController.getCurrentPosition();
            long rawDur = exoController.getDuration();
            int duration = (rawDur != C.TIME_UNSET && rawDur > 0) ? (int) rawDur : eventTotalDurationMs;
            int targetMs = currentPos + (deltaSeconds * 1000);
            if (targetMs <= 0) {
                targetMs = 0;
            } else if (duration > 0 && targetMs >= duration) {
                targetMs = shouldLoopPlayback() ? 0 : Math.max(0, duration - 500);
            }
            seekExoToMs(targetMs);
            return;
        }
        if (isHlsPlayback && webStreamController.hasWebView()) {
            int currentPos = Math.max(0, hlsCurrentPosMs);
            int duration = eventTotalDurationMs;
            int targetMs = currentPos + (deltaSeconds * 1000);
            if (targetMs <= 0) {
                targetMs = 0;
            } else if (duration > 0 && targetMs >= duration) {
                targetMs = shouldLoopPlayback() ? 0 : Math.max(0, duration - 500);
            }
            seekHlsToMs(targetMs);
            return;
        }
        long baseEpoch = (event != null && event.getStartTime() > 0) ? (long) event.getStartTime() : timelineStartEpoch;
        long currentOffset = (timelineStartEpoch - baseEpoch) + (videoView != null ? (videoView.getCurrentPosition() / 1000) : 0);
        seekToSeconds(currentOffset + deltaSeconds);
    }

    private void seekExoToMs(int targetMs) {
        if (!exoController.hasPlayer()) return;
        long rawDur = exoController.getDuration();
        int dur = (rawDur != C.TIME_UNSET && rawDur > 0) ? (int) rawDur : eventTotalDurationMs;
        int clamped = Math.max(0, targetMs);
        if (dur > 0 && clamped >= dur) {
            clamped = Math.max(0, dur - 250);
        }
        boolean resume = wasPlayingBeforeSeek || isPlaying;
        wasPlayingBeforeSeek = false;
        isPlaying = resume;
        updatePlayPauseIcon();
        exoController.seekTo(clamped);
        exoController.setPlayWhenReady(resume);
        if (seekBar != null) {
            seekBar.setProgress(clamped);
        }
        if (timeCurrentText != null) {
            timeCurrentText.setText(formatDuration(clamped));
        }
        if (showObjectBoxes && !isLiveStreamMode && !isRulerScrubbing) {
            double baseEpoch = isTimelineMode
                    ? (double) timelineStartEpoch
                    : ((event != null && event.getStartTime() > 0) ? event.getStartTime() : (double) timelineStartEpoch);
            updateObjectBoxesForEpoch(baseEpoch + (clamped / 1000.0));
        }
    }

    private void seekHlsToMs(int targetMs) {
        if (!webStreamController.hasWebView()) return;
        int dur = eventTotalDurationMs;
        int clamped = Math.max(0, targetMs);
        if (dur > 0 && clamped >= dur) {
            clamped = Math.max(0, dur - 250);
        }
        hlsCurrentPosMs = clamped;
        boolean resume = wasPlayingBeforeSeek || isPlaying;
        wasPlayingBeforeSeek = false;
        isPlaying = resume;
        updatePlayPauseIcon();
        webStreamController.seekVodMs(clamped, resume);
        if (seekBar != null) {
            seekBar.setProgress(clamped);
        }
        if (timeCurrentText != null) {
            timeCurrentText.setText(formatDuration(clamped));
        }
        if (showObjectBoxes && !isLiveStreamMode && !isRulerScrubbing) {
            double baseEpoch = isTimelineMode
                    ? (double) timelineStartEpoch
                    : ((event != null && event.getStartTime() > 0) ? event.getStartTime() : (double) timelineStartEpoch);
            updateObjectBoxesForEpoch(baseEpoch + (clamped / 1000.0));
        }
    }

    private void restartVideoFromBeginning() {
        if (seekBar != null) {
            seekBar.setProgress(0);
        }
        if (timeCurrentText != null) {
            timeCurrentText.setText("00:00");
        }
        if (isExoPlayback && exoController.hasPlayer()) {
            if (host != null) {
                host.log("Frigate: Looping ExoPlayer VOD back to 00:00");
            }
            isPlaying = true;
            updatePlayPauseIcon();
            exoController.seekTo(0);
            exoController.setPlayWhenReady(true);
            return;
        }
        if (isHlsPlayback && webStreamController.hasWebView()) {
            if (host != null) {
                host.log("Frigate: Looping hls.js VOD back to 00:00");
            }
            hlsCurrentPosMs = 0;
            isPlaying = true;
            updatePlayPauseIcon();
            webStreamController.seekVodMs(0, true);
            return;
        }
        this.isTimelineMode = false;
        this.isHistoryMode = false;
        updateLoopButtonVisibility();
        updateBboxButtonVisibility();
        updateSeekBarVisibility();
        setActiveVideoDimensions(0, 0, 1.0f);
        updateObjectBoxesOverlayVisibility();
        if (event != null && event.getStartTime() > 0) {
            this.timelineStartEpoch = (long) event.getStartTime();
        }
        if (clipCacheManager.replayOriginalEventClipIfCached(videoView)) {
            return;
        }
        loadVideo();
    }

    private void seekToSeconds(long targetSeconds) {
        if (targetSeconds <= 0 && event != null && event.getId() != null) {
            restartVideoFromBeginning();
            return;
        }

        long baseEpoch = (event != null && event.getStartTime() > 0) ? (long) event.getStartTime() : timelineStartEpoch;
        long maxDuration = 300;
        if (eventTotalDurationMs > 0) {
            maxDuration = eventTotalDurationMs / 1000;
        } else if (event != null && event.getEndTime() > event.getStartTime()) {
            maxDuration = (long) Math.round(event.getEndTime() - event.getStartTime());
        }

        if (targetSeconds < 0) targetSeconds = 0;
        if (targetSeconds >= maxDuration) {
            restartVideoFromBeginning();
            return;
        }

        this.timelineStartEpoch = baseEpoch + targetSeconds;
        this.timelineDurationSeconds = Math.max(1, maxDuration - targetSeconds);
        this.isTimelineMode = true;

        if (host != null) {
            host.log("Frigate: Slicing clip from +" + targetSeconds + "s to +" + maxDuration + "s (duration " + timelineDurationSeconds + "s)");
        }
        loadVideo();
    }

    private void setupVideoListeners() {
        clipCacheManager.attachListeners(videoView);
    }

    private long getEventVodStartEpoch() {
        if (isTimelineMode || timelineStartEpoch > 0) {
            return timelineStartEpoch;
        }
        return (event != null && event.getStartTime() > 0) ? (long) event.getStartTime() : 0L;
    }

    private long getEventVodEndEpoch() {
        long start = getEventVodStartEpoch();
        if (isTimelineMode) {
            return start + timelineDurationSeconds;
        }
        if (event != null && event.getEndTime() > event.getStartTime()) {
            return Math.max(start + 5L, (long) Math.ceil(event.getEndTime()));
        }
        return start + Math.max(30L, timelineDurationSeconds);
    }

    private void showNoVideoFallback(String message) {
        stopExoPlayer();
        stopLiveWebView();
        if (videoView != null) {
            try {
                videoView.stopPlayback();
            } catch (Exception ignored) {}
            videoView.setVisibility(View.GONE);
        }
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }
        stopProgressUpdater();
        hideVideoOverlayControls(false);
        updateTransportModeBadge(tr("common_preview_badge"), "#94A3B8", "#161D2B", "#334155");
        if (errorText != null) {
            errorText.setText(message);
            errorText.setVisibility(View.VISIBLE);
        }
        loadEventFallbackThumbnail();
    }

    private void loadEventFallbackThumbnail() {
        if (fallbackImageView == null || event == null) return;
        String fallbackUrl = null;
        if (!event.isReviewFallback() && event.getId() != null && !event.getId().isEmpty()) {
            if (event.hasSnapshot()) {
                fallbackUrl = apiClient.getSnapshotUrl(event.getId());
            } else {
                fallbackUrl = event.getEffectiveThumbnailUrl(apiClient.getThumbnailUrl(event.getId()));
            }
        } else {
            fallbackUrl = event.getEffectiveThumbnailUrl(null);
        }
        if (fallbackUrl != null && !fallbackUrl.isEmpty()) {
            imageLoader.load(fallbackUrl, fallbackImageView);
            fallbackImageView.setVisibility(View.VISIBLE);
        }
    }

    private String buildVodHlsUrl() {
        if (!isTimelineMode && event != null && !event.isReviewFallback()
                && event.getId() != null && !event.getId().isEmpty()) {
            return apiClient.getEventVodHlsUrl(event.getId());
        }
        long start = getEventVodStartEpoch();
        long end = getEventVodEndEpoch();
        return apiClient.getVodHlsUrl(currentCamera, start, end);
    }

    private void stopExoPlayer() {
        isExoPlayback = false;
        exoController.stop();
    }

    private void loadVideo() {
        if (videoView == null) return;
        final int sessionId = ++streamSessionId;
        stopMjpegConnectionOnly();
        stopProgressUpdater();
        hlsCurrentPosMs = 0;

        progressBar.setVisibility(View.VISIBLE);
        errorText.setVisibility(View.GONE);
        if (fallbackImageView != null) {
            fallbackImageView.setVisibility(View.GONE);
        }

        if (config != null && config.hasCredentials() && config.getAuthToken().isEmpty()) {
            apiClient.ensureAuthenticatedAsync(() -> progressHandler.post(() -> {
                if (!isClosed && !isLiveStreamMode && sessionId == streamSessionId) {
                    startVideoPlayback(sessionId);
                }
            }));
        } else {
            startVideoPlayback(sessionId);
        }
    }

    private void startVideoPlayback(int sessionId) {
        if (videoView == null || isClosed) return;

        if (!isTimelineMode && !isHistoryMode && event != null && !event.isReviewFallback() && !event.hasClip()) {
            showNoVideoFallback(tr("player_error_video_expired_kept"));
            return;
        }

        if (!exoFallbackTriggered && exoController.hasTextureView()) {
            String hlsUrl = buildVodHlsUrl();
            clipCacheManager.clearCurrentCachedFilePath();
            updateTransportModeBadge("EXO...", "#38BDF8", "#0C2135", "#0284C7");
            if (host != null) {
                host.log("Frigate: Streaming ExoPlayer HLS VOD: " + hlsUrl);
            }
            android.util.Log.i("FrigatePlugin", "Streaming ExoPlayer HLS VOD: " + hlsUrl);
            exoController.start(sessionId, hlsUrl, isMuted);
            return;
        }

        if (!hlsFallbackTriggered && webStreamController.hasWebView()) {
            stopExoPlayer();
            String hlsUrl = buildVodHlsUrl();
            isHlsPlayback = true;
            clipCacheManager.clearCurrentCachedFilePath();
            updateTransportModeBadge("HLS...", "#94A3B8", "#161D2B", "#334155");
            if (host != null) {
                host.log("Frigate: Streaming hls.js VOD: " + hlsUrl);
            }
            android.util.Log.i("FrigatePlugin", "Streaming hls.js VOD: " + hlsUrl);
            startHlsWebView(sessionId, hlsUrl);
            return;
        }

        stopExoPlayer();
        stopLiveWebView();
        videoView.setVisibility(View.VISIBLE);
        videoView.stopPlayback();
        isHlsPlayback = false;
        updateTransportModeBadge("MP4...", "#FCD34D", "#291D0B", "#F59E0B");
        String videoUrl;
        String cleanKey;
        if (!isTimelineMode && event != null && !event.isReviewFallback()
                && event.getId() != null && !event.getId().isEmpty()) {
            videoUrl = apiClient.getClipUrl(event.getId());
            cleanKey = "frigate_event_" + event.getId().replaceAll("[^a-zA-Z0-9_-]", "_") + ".mp4";
        } else {
            long start = getEventVodStartEpoch();
            long end = getEventVodEndEpoch();
            videoUrl = apiClient.getTimelineClipUrl(currentCamera, start, end);
            cleanKey = "frigate_tl_" + currentCamera + "_" + start + "_" + end + ".mp4";
        }

        clipCacheManager.playOrDownloadMp4(videoView, videoUrl, cleanKey, !isTimelineMode && event != null);
    }

    private void togglePlayPause() {
        if (isLiveStreamMode) {
            if (isPlaying) {
                isLivePaused = true;
                if (isLiveMseActive && webStreamController.hasWebView()) {
                    webStreamController.pauseLiveStream();
                } else {
                    streamSessionId++;
                    stopMjpegConnectionOnly();
                }
                isPlaying = false;
                hideLiveFpsBadge();
                updatePlayPauseIcon();
            } else {
                startLiveStream();
            }
            return;
        }
        if (isExoPlayback && exoController.hasPlayer()) {
            if (isPlaying) {
                exoController.setPlayWhenReady(false);
                isPlaying = false;
                hideLiveFpsBadge();
                lastExoFrames = -1;
                lastExoFpsTsMs = 0L;
            } else {
                long current = exoController.getCurrentPosition();
                long rawDur = exoController.getDuration();
                long dur = (rawDur != C.TIME_UNSET && rawDur > 0) ? rawDur : eventTotalDurationMs;
                if (dur > 0 && current >= dur - 500) {
                    exoController.seekTo(0);
                    if (seekBar != null) seekBar.setProgress(0);
                    if (timeCurrentText != null) timeCurrentText.setText("00:00");
                }
                exoController.setPlayWhenReady(true);
                isPlaying = true;
                startProgressUpdater();
            }
            updatePlayPauseIcon();
            return;
        }
        if (isHlsPlayback && webStreamController.hasWebView()) {
            if (isPlaying) {
                webStreamController.pauseVod();
                isPlaying = false;
                hideLiveFpsBadge();
            } else {
                webStreamController.playVod();
                isPlaying = true;
            }
            updatePlayPauseIcon();
            return;
        }
        if (videoView == null) return;
        if (videoView.isPlaying()) {
            videoView.pause();
            isPlaying = false;
            updatePlayPauseIcon();
            stopProgressUpdater();
        } else {
            int current = videoView.getCurrentPosition();
            int duration = (eventTotalDurationMs > 0) ? eventTotalDurationMs : videoView.getDuration();
            if (isHlsPlayback) {
                if (duration > 0 && current >= duration - 500) {
                    videoView.seekTo(0);
                    if (seekBar != null) seekBar.setProgress(0);
                    if (timeCurrentText != null) timeCurrentText.setText("00:00");
                }
            } else if (clipCacheManager.getCurrentCachedFilePath() != null) {
                if ((duration > 0 && current >= duration - 500) || !isPlaying) {
                    videoView.setVideoPath(clipCacheManager.getCurrentCachedFilePath());
                    return;
                }
            }
            videoView.start();
            isPlaying = true;
            updatePlayPauseIcon();
            startProgressUpdater();
        }
    }

    private final Runnable progressUpdaterRunnable = new Runnable() {
        @Override
        public void run() {
            if (isClosed) return;
            if (!isLiveStreamMode) {
                    if (isExoPlayback && exoController.hasPlayer()) {
                        long rawDur = exoController.getDuration();
                        int durMs = (rawDur != C.TIME_UNSET && rawDur > 0) ? (int) rawDur : eventTotalDurationMs;
                        if (durMs > 0 && seekBar != null && durMs != seekBar.getMax()) {
                            seekBar.setMax(durMs);
                            timeDurationText.setText(formatDuration(durMs));
                        }
                        if (!isUserSeeking && exoController.isPlaying()) {
                            int frames = exoController.getRenderedVideoFrameCount();
                            if (frames >= 0) {
                                long nowMs = android.os.SystemClock.elapsedRealtime();
                                if (lastExoFrames >= 0 && lastExoFpsTsMs > 0L) {
                                    long dtMs = nowMs - lastExoFpsTsMs;
                                    if (dtMs >= 1000L) {
                                        int fps = Math.max(0, Math.round(((frames - lastExoFrames) * 1000f) / dtMs));
                                        updateLiveFpsBadge(fps);
                                        lastExoFrames = frames;
                                        lastExoFpsTsMs = nowMs;
                                    }
                                } else {
                                    lastExoFrames = frames;
                                    lastExoFpsTsMs = nowMs;
                                }
                            }
                            int pos = (int) exoController.getCurrentPosition();
                            if (durMs > 0 && pos > durMs) {
                                pos = durMs;
                            }
                            if (seekBar != null) seekBar.setProgress(pos);
                            if (timeCurrentText != null) timeCurrentText.setText(formatDuration(pos));
                            if (!isRulerScrubbing) {
                                syncPlaybackEpochUi(getExoPlaybackEpochDouble(pos));
                            }
                        }
                    } else if (isHlsPlayback && videoView != null) {
                        int durMs = (eventTotalDurationMs > 0) ? eventTotalDurationMs : videoView.getDuration();
                        if (durMs > 0 && durMs != seekBar.getMax()) {
                            seekBar.setMax(durMs);
                            timeDurationText.setText(formatDuration(durMs));
                        }
                        if (videoView.isPlaying() && !isUserSeeking) {
                            int pos = Math.max(0, videoView.getCurrentPosition());
                            if (durMs > 0 && pos > durMs) {
                                pos = durMs;
                            }
                            seekBar.setProgress(pos);
                            timeCurrentText.setText(formatDuration(pos));
                            if (!isRulerScrubbing) {
                                syncPlaybackEpochUi(getExoPlaybackEpochDouble(pos));
                            }
                        }
                    } else if (videoView != null) {
                        double baseEpochDouble = (event != null && event.getStartTime() > 0) ? event.getStartTime() : (double) timelineStartEpoch;
                        long baseEpoch = (long) baseEpochDouble;
                        long offsetSec = Math.max(0, timelineStartEpoch - baseEpoch);
                        long totalSec = 300;
                        if (eventTotalDurationMs > 0) {
                            totalSec = eventTotalDurationMs / 1000;
                        } else if (event != null && event.getEndTime() > event.getStartTime()) {
                            totalSec = (long) Math.round(event.getEndTime() - event.getStartTime());
                        } else if (isTimelineMode) {
                            totalSec = timelineDurationSeconds;
                        }

                        int totalDurationMs = (int) (totalSec * 1000);
                        if (totalDurationMs > 0 && totalDurationMs != seekBar.getMax()) {
                            seekBar.setMax(totalDurationMs);
                            timeDurationText.setText(formatDuration(totalDurationMs));
                        }

                        if (videoView.isPlaying() && !isUserSeeking) {
                            int currentPos = videoView.getCurrentPosition();
                            int elapsedMs = (int) (offsetSec * 1000) + currentPos;
                            if (eventTotalDurationMs > 0 && elapsedMs >= eventTotalDurationMs) {
                                if (shouldLoopPlayback()) {
                                    if (host != null) {
                                        host.log("Frigate: End of event boundary reached (" + elapsedMs + "/" + eventTotalDurationMs + "ms), looping to 00:00");
                                    }
                                    restartVideoFromBeginning();
                                } else {
                                    videoView.pause();
                                    isPlaying = false;
                                    updatePlayPauseIcon();
                                    seekBar.setProgress(eventTotalDurationMs);
                                    timeCurrentText.setText(formatDuration(eventTotalDurationMs));
                                    stopProgressUpdater();
                                }
                                return;
                            }
                            seekBar.setProgress(elapsedMs);
                            timeCurrentText.setText(formatDuration(elapsedMs));
                            if (!isRulerScrubbing) {
                                syncPlaybackEpochUi(baseEpochDouble + (elapsedMs / 1000.0));
                            }
                        }
                    }
                } else if (timelineRuler != null && !isLivePaused && !isRulerScrubbing) {
                    timelineRuler.setCursorEpoch(System.currentTimeMillis() / 1000L, true);
                    timelineController.maybeRefreshLiveTimelineTail();
                }
                long delayMs = (!isLiveStreamMode && showObjectBoxes && isPlaying && !isRulerScrubbing) ? 16L : 200L;
                progressHandler.postDelayed(this, delayMs);
        }
    };

    private double getExoPlaybackEpochDouble(long posMs) {
        double programEpoch = exoController.getCurrentProgramEpochSeconds();
        if (programEpoch > 0.0) {
            return programEpoch;
        }
        double baseEpoch = isTimelineMode
                ? (double) timelineStartEpoch
                : ((event != null && event.getStartTime() > 0) ? event.getStartTime() : (double) timelineStartEpoch);
        return baseEpoch + (posMs / 1000.0);
    }

    private double getCurrentPlaybackEpochDouble() {
        if (isLiveStreamMode) {
            return System.currentTimeMillis() / 1000.0;
        }
        if (isExoPlayback && exoController.hasPlayer()) {
            return getExoPlaybackEpochDouble(exoController.getCurrentPosition());
        }
        double baseEpoch = isTimelineMode
                ? (double) timelineStartEpoch
                : ((event != null && event.getStartTime() > 0) ? event.getStartTime() : (double) timelineStartEpoch);
        if (isHlsPlayback) {
            return baseEpoch + (Math.max(0, hlsCurrentPosMs) / 1000.0);
        }
        if (videoView != null) {
            long baseSec = (long) baseEpoch;
            long offsetSec = Math.max(0L, timelineStartEpoch - baseSec);
            int posMs = Math.max(0, videoView.getCurrentPosition());
            return baseEpoch + ((offsetSec * 1000L + posMs) / 1000.0);
        }
        return baseEpoch;
    }

    private void syncPlaybackEpochUi(double wallEpochSeconds) {
        long wallEpoch = (long) Math.floor(wallEpochSeconds);
        if (wallEpoch != lastSyncedWallEpoch) {
            lastSyncedWallEpoch = wallEpoch;
            if (timelineRuler != null) {
                timelineRuler.setCursorEpoch(wallEpoch, false);
            }
            updateObjectBadgesForEpoch(wallEpoch);
            if (isTimelineMode && headerTimeView != null) {
                headerTimeView.setText(FrigateDateTimeUtils.formatWeekdayDateTime(context, config, wallEpoch * 1000L));
            }
        }
        updateObjectBoxesForEpoch(wallEpochSeconds);
    }

    private void updateObjectBoxesForEpoch(double epochSeconds) {
        objectDetectorController.updateObjectBoxesForEpoch(epochSeconds);
    }

    private void startProgressUpdater() {
        progressHandler.removeCallbacks(progressUpdaterRunnable);
        progressHandler.post(progressUpdaterRunnable);
    }

    private void stopProgressUpdater() {
        progressHandler.removeCallbacks(progressUpdaterRunnable);
        lastExoFrames = -1;
        lastExoFpsTsMs = 0L;
    }

    private void startLiveObjectPoller() {
        objectDetectorController.startLiveObjectPoller();
    }

    private void stopLiveObjectPoller() {
        objectDetectorController.stopLiveObjectPoller();
    }

    private void updateObjectBadgesForEpoch(long epochSeconds) {
        objectDetectorController.updateObjectBadgesForEpoch(epochSeconds);
    }

    private void updateObjectBadgesFromEvents(List<FrigateEvent> events) {
        objectDetectorController.updateObjectBadgesFromEvents(events);
    }

    private void closePlayer() {
        if (isClosed) return;
        isClosed = true;
        stopLiveObjectPoller();
        stopAllLiveStreams();
        stopExoPlayer();
        stopProgressUpdater();
        progressHandler.removeCallbacksAndMessages(null);
        webStreamController.destroy();
        clipCacheManager.destroy();
        timelineController.destroy();
        if (videoView != null) {
            try {
                videoView.stopPlayback();
            } catch (Exception ignored) {}
        }
        if (onClose != null) {
            onClose.run();
        }
    }

    public void onDestroy() {
        closePlayer();
    }

    private String getFormattedEventTime() {
        long epoch = timelineStartEpoch;
        if (event != null && event.getStartTime() > 0) {
            epoch = (long) event.getStartTime();
        }
        return FrigateDateTimeUtils.formatWeekdayDateTime(context, config, epoch * 1000L);
    }

    private String formatDuration(int ms) {
        if (ms < 0) ms = 0;
        int totalSec = ms / 1000;
        int s = totalSec % 60;
        int m = (totalSec / 60) % 60;
        int h = totalSec / 3600;
        if (h > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.getDefault(), "%02d:%02d", m, s);
    }
}
