// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
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
import io.github.tibohlami.ksplugins.frigate.api.FrigateApiClient;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import io.github.tibohlami.ksplugins.frigate.model.FrigateEvent;
import io.github.tibohlami.ksplugins.frigate.model.PreviewSegment;
import io.github.tibohlami.ksplugins.frigate.model.PtzInfo;
import io.github.tibohlami.ksplugins.frigate.model.RecordingSegment;
import io.github.tibohlami.ksplugins.frigate.model.ReviewSegment;
import io.github.tibohlami.ksplugins.frigate.model.TimelineBoxEntry;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
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
    private KsTheme theme;
    private final me.jxl.kiosk.plugins.PluginHost host;
    private final FrigateApiClient apiClient;
    private final FrigateConfig config;
    private final FrigateEvent event;
    private final Runnable onClose;

    private FrameLayout videoContainerRef;
    private FrigateVideoZoomController videoZoomController;
    private FrigatePtzOverlayController ptzOverlayController;
    private PtzInfo ptzInfo = PtzInfo.UNSUPPORTED;
    private TextView ptzBtn;
    private View liveHighlightView;
    private LinearLayout liveOverlayBadgeRow;
    private TextView timelineDateBtn;
    private FrameLayout rulerWrapper;
    private FrigateTimelineRulerView timelineRuler;
    private final SimpleDateFormat dayKeyFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final FrigateCalendarModal calendarModal;
    private String selectedDayKey = "all";
    private long[] pendingCalendarJumpBounds = null;
    private ImageView scrubPreviewView;
    private TextureView scrubPreviewTextureView;
    private FrigateScrubPreviewPlayerController scrubPreviewPlayerController;
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
    private String lastTransportLabel;
    private String lastTransportTextHex;
    private String lastTransportBgHex;
    private String lastTransportStrokeHex;
    private int lastLiveFps = -1;
    private int lastExoFrames = -1;
    private long lastExoFpsTsMs = 0L;
    private LinearLayout headerObjectsContainer;
    private ImageView playPauseBtn;
    private TextView timeCurrentText;
    private TextView timeDurationText;
    private TextView muteBtn;
    private TextView bboxBtn;
    private TextView loopBtn;
    private SeekBar seekBar;
    private LinearLayout overlaySeekRow;
    private LinearLayout videoOverlayControls;
    private boolean videoOverlayVisible = true;
    private static final long OVERLAY_AUTO_HIDE_MS = 3000L;
    private final Runnable hideOverlayControlsRunnable = () -> hideVideoOverlayControls(true);
    private FrigateObjectBoxesOverlayView objectBoxesOverlay;
    private boolean showObjectBoxes = false;
    private LinearLayout modalCard;
    private TextView camBadge;
    private TextView playerCloseBtn;
    private ImageView zoomOutBtn;
    private ImageView zoomInBtn;
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
    private boolean isNoRecordingError = false;
    private String activePreviewFallbackUrl = null;
    private FrigateEvent activePreviewEvent = null;
    private long activePreviewClipStartMs = 0L;
    private long activePreviewClipEndMs = C.TIME_END_OF_SOURCE;
    private volatile boolean isExoPlayback = false;
    private volatile boolean exoFallbackTriggered = false;
    private FrameLayout rootLayout;
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
        this.calendarModal = new FrigateCalendarModal(context, theme, config, dayKeyFmt);

        this.objectDetectorController = new FrigatePlayerObjectDetectorController(
                context, host, theme, apiClient, config, progressHandler,
                new FrigatePlayerObjectDetectorController.HostBridge() {
                    @Override public boolean isClosed() { return isClosed; }
                    @Override public boolean isOverlayVisible() { return FrigatePlayerOverlay.this.isOverlayVisible(); }
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
                    @Override public View getLiveHighlightView() { return liveHighlightView; }
                    @Override public LinearLayout getLiveOverlayBadgeRow() { return liveOverlayBadgeRow; }
                });

        if (!isLiveStreamMode && event.getStartTime() > 0) {
            this.timelineStartEpoch = (long) event.getStartTime();
            if (event.getEndTime() > event.getStartTime()) {
                this.eventTotalDurationMs = (int) Math.round((event.getEndTime() - event.getStartTime()) * 1000);
            }
        } else {
            this.timelineStartEpoch = System.currentTimeMillis() / 1000L;
        }
        this.timelineController = new FrigatePlayerTimelineController(host, apiClient, config, progressHandler,
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
                        tryFinishPendingCalendarJump();
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
                        if (activePreviewFallbackUrl != null) {
                            exoController.startPreview(++streamSessionId, activePreviewFallbackUrl,
                                    activePreviewClipStartMs, activePreviewClipEndMs, 8.0f);
                        } else {
                            loadVideo();
                        }
                    }

                    @Override
                    public void onExoFallbackToHls(String errorMsg) {
                        isExoPlayback = false;
                        exoFallbackTriggered = true;
                        if (activePreviewFallbackUrl != null) {
                            long targetEpoch = timelineRuler != null ? timelineRuler.getCursorEpoch() : timelineStartEpoch;
                            showExpiredEventSnapshotFallback(activePreviewEvent, targetEpoch);
                        } else {
                            loadVideo();
                        }
                    }

                    @Override
                    public void onExoSourceNotFound() {
                        isExoPlayback = false;
                        long targetEpoch = timelineRuler != null ? timelineRuler.getCursorEpoch() : timelineStartEpoch;
                        if (activePreviewFallbackUrl != null) {
                            showExpiredEventSnapshotFallback(activePreviewEvent, targetEpoch);
                        } else {
                            showEventPreviewOrNoRecording(targetEpoch);
                        }
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
        float scale = (config != null) ? config.getUiScale() : 1.0f;
        return Math.max(1, Math.round(theme.px(Math.round(dp * scale))));
    }

    /**
     * Scales SP font sizes according to user-configured uiScale.
     */
    private float sp(float sp) {
        float scale = (config != null) ? config.getUiScale() : 1.0f;
        return sp * scale;
    }

    public View buildView() {
        // Semi-transparent dim background covering full screen
        FrameLayout root = new FrameLayout(context);
        this.rootLayout = root;
        root.setBackgroundColor(Color.argb(235, 8, 11, 16));
        root.setOnClickListener(v -> closePlayer());

        // Centered modern modal card
        LinearLayout card = new LinearLayout(context);
        this.modalCard = card;
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(FrigateTheme.surfaceContainer(theme));
        cardBg.setCornerRadius(s(FrigateTheme.radiusCard(theme)));
        cardBg.setStroke(s(1), FrigateTheme.outlineVariant(theme));
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

        // 2. Main Row: Left Column (Full-Height Video) + Right Full-Height Vertical Timeline Scrubber
        LinearLayout mainRow = new LinearLayout(context);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);

        mainRow.addView(buildVideoFrame(), new LinearLayout.LayoutParams(
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

    public void onThemeChanged(KsTheme newTheme) {
        if (newTheme == null) return;
        this.theme = newTheme;
        if (modalCard != null) {
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(FrigateTheme.surfaceContainer(theme));
            cardBg.setCornerRadius(s(FrigateTheme.radiusCard(theme)));
            cardBg.setStroke(s(1), FrigateTheme.outlineVariant(theme));
            modalCard.setBackground(cardBg);
        }
        if (camBadge != null) {
            MaterialIcons.setIconOnTextView(camBadge, MaterialIcons.CAMERA, FrigateTheme.onSurface(theme), s(18), s(8), theme);
            GradientDrawable cbBg = new GradientDrawable();
            cbBg.setColor(FrigateTheme.surfaceContainerHigh(theme));
            cbBg.setCornerRadius(s(FrigateTheme.radiusControl(theme)));
            cbBg.setStroke(s(1), FrigateTheme.outlineVariant(theme));
            camBadge.setBackground(cbBg);
            camBadge.setTextColor(FrigateTheme.onSurface(theme));
            camBadge.setTypeface(FrigateTheme.typeface(theme, 600));
        }
        if (playerCloseBtn != null) {
            int closeErr = FrigateTheme.error(theme);
            MaterialIcons.setIconOnTextView(playerCloseBtn, MaterialIcons.CLOSE, closeErr, s(16), s(6), theme);
            int errBgNormal = Color.argb(35, Color.red(closeErr), Color.green(closeErr), Color.blue(closeErr));
            int errBgPressed = Color.argb(85, Color.red(closeErr), Color.green(closeErr), Color.blue(closeErr));
            int errStrokeNormal = Color.argb(95, Color.red(closeErr), Color.green(closeErr), Color.blue(closeErr));
            FrigateUiUtils.applyCloseButtonFeedback(
                    playerCloseBtn,
                    errBgNormal,
                    errBgPressed,
                    errStrokeNormal,
                    closeErr,
                    s(FrigateTheme.radiusControl(theme)),
                    s(1)
            );
            playerCloseBtn.setTextColor(closeErr);
            playerCloseBtn.setTypeface(FrigateTheme.typeface(theme, 600));
        }
        if (zoomOutBtn != null) {
            updateZoomButtonStyle(zoomOutBtn, MaterialIcons.ZOOM_OUT);
        }
        if (zoomInBtn != null) {
            updateZoomButtonStyle(zoomInBtn, MaterialIcons.ZOOM_IN);
        }
        if (objectDetectorController != null) {
            objectDetectorController.onThemeChanged(newTheme);
        }
        if (timelineRuler != null) {
            timelineRuler.setTheme(newTheme);
        }
        if (calendarModal != null) {
            calendarModal.onThemeChanged(newTheme);
        }
        if (timelineDateBtn != null) {
            long cursor = (timelineRuler != null) ? timelineRuler.getCursorEpoch() : (System.currentTimeMillis() / 1000L);
            updateTimelineDateHeader(cursor);
        }
        if (rulerWrapper != null) {
            updateRulerWrapperTheme();
        }
        updateVideoContainerTheme();
        if (timeCurrentText != null) {
            timeCurrentText.setTextColor(FrigateTheme.onSurface(theme));
        }
        if (timeDurationText != null) {
            timeDurationText.setTextColor(FrigateTheme.onSurfaceVariant(theme));
        }
        if (seekBar != null) {
            theme.styleSlider(seekBar);
        }
        updateMuteButtonStyle();
        updateLoopButtonStyle();
        updateBboxButtonStyle();
        updatePtzButtonStyle();
        if (ptzOverlayController != null) {
            ptzOverlayController.onThemeChanged(theme);
        }
        if (lastTransportLabel != null) {
            updateTransportModeBadge(lastTransportLabel, lastTransportTextHex, lastTransportBgHex, lastTransportStrokeHex);
        }
        if (lastLiveFps >= 0) {
            updateLiveFpsBadge(lastLiveFps);
        }
    }

    private void updateVideoContainerTheme() {
        if (videoContainerRef == null) return;
        boolean isDark = FrigateTheme.isDark(theme);
        GradientDrawable frameBg = new GradientDrawable();
        int frameBgColor = isDark
                ? Color.parseColor("#000000")
                : FrigateTheme.surfaceContainer(theme);
        frameBg.setColor(frameBgColor);
        frameBg.setCornerRadius(s(12));
        videoContainerRef.setBackground(frameBg);
    }

    private void updateRulerWrapperTheme() {
        if (rulerWrapper == null) return;
        boolean isDark = FrigateTheme.isDark(theme);
        GradientDrawable rBg = new GradientDrawable();
        rBg.setColor(isDark ? Color.parseColor("#0B101A") : Color.parseColor("#EDF0F3"));
        rBg.setCornerRadius(s(12));
        rBg.setStroke(s(1), FrigateTheme.outlineVariant(theme));
        rulerWrapper.setBackground(rBg);
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
        camBadge = new TextView(context);
        String camName = (currentCamera != null && !currentCamera.isEmpty())
                ? currentCamera.substring(0, 1).toUpperCase(Locale.ROOT) + currentCamera.substring(1)
                : tr("common_camera_fallback");
        camBadge.setText(camName);
        MaterialIcons.setIconOnTextView(camBadge, MaterialIcons.CAMERA, FrigateTheme.onSurface(theme), s(18), s(8), theme);
        GradientDrawable cbBg = new GradientDrawable();
        cbBg.setColor(FrigateTheme.surfaceContainerHigh(theme));
        cbBg.setCornerRadius(s(FrigateTheme.radiusControl(theme)));
        cbBg.setStroke(s(1), FrigateTheme.outlineVariant(theme));
        camBadge.setBackground(cbBg);
        camBadge.setPadding(s(14), s(8), s(14), s(8));
        camBadge.setTextSize(sp(15));
        camBadge.setTextColor(FrigateTheme.onSurface(theme));
        camBadge.setTypeface(FrigateTheme.typeface(theme, 600));
        camBadge.setIncludeFontPadding(false);
        camBadge.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(camBadge);

        // Bright LIVE indicator pill in RED (visible in real-time stream mode)
        liveBadge = new TextView(context);
        liveBadge.setText(tr("common_live"));
        int liveErr = Color.parseColor("#EF4444");
        MaterialIcons.setIconOnTextView(liveBadge, MaterialIcons.LIVE, liveErr, s(14), s(6), theme);
        GradientDrawable lbBg = new GradientDrawable();
        lbBg.setColor(Color.argb(35, Color.red(liveErr), Color.green(liveErr), Color.blue(liveErr)));
        lbBg.setCornerRadius(s(FrigateTheme.radiusControl(theme)));
        lbBg.setStroke(s(1), liveErr);
        liveBadge.setBackground(lbBg);
        liveBadge.setPadding(s(12), s(8), s(12), s(8));
        liveBadge.setTextSize(sp(13));
        liveBadge.setTextColor(liveErr);
        liveBadge.setTypeface(FrigateTheme.typeface(theme, 600));
        liveBadge.setIncludeFontPadding(false);
        liveBadge.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lbLp.leftMargin = s(10);
        liveBadge.setVisibility(isLiveStreamMode ? View.VISIBLE : View.GONE);
        header.addView(liveBadge, lbLp);

        // Optional transport mode badge (HLS / MP4 / MSE / MJPEG)
        transportModeBadge = new TextView(context);
        transportModeBadge.setTextSize(sp(11));
        transportModeBadge.setTypeface(FrigateTheme.typeface(theme, 600));
        transportModeBadge.setSingleLine(true);
        transportModeBadge.setIncludeFontPadding(false);
        transportModeBadge.setGravity(Gravity.CENTER);
        transportModeBadge.setPadding(s(10), s(6), s(10), s(6));
        LinearLayout.LayoutParams tmLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tmLp.gravity = Gravity.CENTER_VERTICAL;
        tmLp.leftMargin = s(8);
        transportModeBadge.setVisibility(View.GONE);
        header.addView(transportModeBadge, tmLp);

        // Live FPS badge (MSE / MJPEG) - Fixed width for up to 2 digits to prevent jitter
        liveFpsBadge = new TextView(context);
        liveFpsBadge.setTextSize(sp(11));
        liveFpsBadge.setTypeface(FrigateTheme.typeface(theme, 600));
        liveFpsBadge.setSingleLine(true);
        liveFpsBadge.setIncludeFontPadding(false);
        liveFpsBadge.setGravity(Gravity.CENTER);
        liveFpsBadge.setPadding(0, s(6), 0, s(6));
        int fpsPillWidth = s(60);
        LinearLayout.LayoutParams fpsLp = new LinearLayout.LayoutParams(
                fpsPillWidth, LinearLayout.LayoutParams.WRAP_CONTENT);
        fpsLp.gravity = Gravity.CENTER_VERTICAL;
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

        // Flexible spacer pushing right-side action pills (Mute, Boxes, Close) to the end
        View headerSpacer = new View(context);
        header.addView(headerSpacer, new LinearLayout.LayoutParams(
                0, 1, 1.0f));

        // Mute / Unmute Audio Toggle Button (shown in Live MSE or VOD with audio)
        muteBtn = new TextView(context);
        muteBtn.setText(tr("player_sound_on"));
        muteBtn.setGravity(Gravity.CENTER);
        updateMuteButtonStyle();
        muteBtn.setPadding(s(14), s(8), s(14), s(8));
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
        header.addView(muteBtn, mLp);

        // Object Bounding Boxes Toggle Button (shown in Historical / Event modes only)
        bboxBtn = new TextView(context);
        bboxBtn.setText(tr("player_boxes"));
        bboxBtn.setGravity(Gravity.CENTER);
        updateBboxButtonStyle();
        bboxBtn.setPadding(s(14), s(8), s(14), s(8));
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
        header.addView(bboxBtn, bbLp);

        // PTZ Controls Toggle Button (shown in Live mode when camera supports ONVIF PTZ)
        ptzBtn = new TextView(context);
        ptzBtn.setText("PTZ");
        ptzBtn.setGravity(Gravity.CENTER);
        updatePtzButtonStyle();
        ptzBtn.setPadding(s(14), s(8), s(14), s(8));
        ptzBtn.setTextSize(sp(13));
        ptzBtn.setVisibility(View.GONE);
        ptzBtn.setOnClickListener(v -> {
            if (ptzOverlayController != null) {
                ptzOverlayController.toggle();
            }
        });
        LinearLayout.LayoutParams ptzLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ptzLp.rightMargin = s(8);
        header.addView(ptzBtn, ptzLp);
        fetchCameraPtzInfo();

        // Close Button with Material Close icon
        playerCloseBtn = new TextView(context);
        playerCloseBtn.setText(tr("common_close"));
        int closeErr = FrigateTheme.error(theme);
        MaterialIcons.setIconOnTextView(playerCloseBtn, MaterialIcons.CLOSE, closeErr, s(16), s(6), theme);
        int errBgNormal = Color.argb(35, Color.red(closeErr), Color.green(closeErr), Color.blue(closeErr));
        int errBgPressed = Color.argb(85, Color.red(closeErr), Color.green(closeErr), Color.blue(closeErr));
        int errStrokeNormal = Color.argb(95, Color.red(closeErr), Color.green(closeErr), Color.blue(closeErr));

        FrigateUiUtils.applyCloseButtonFeedback(
                playerCloseBtn,
                errBgNormal,
                errBgPressed,
                errStrokeNormal,
                closeErr,
                s(FrigateTheme.radiusControl(theme)),
                s(1)
        );
        playerCloseBtn.setPadding(s(16), s(8), s(16), s(8));
        playerCloseBtn.setTextSize(sp(14));
        playerCloseBtn.setTextColor(closeErr);
        playerCloseBtn.setTypeface(FrigateTheme.typeface(theme, 600));
        playerCloseBtn.setOnClickListener(v -> {
            if (isClosed) return;
            FrigateUiUtils.markButtonClosing(v);
            closePlayer();
        });
        header.addView(playerCloseBtn);

        return header;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private View buildVideoFrame() {
        FrameLayout videoContainer = new FrameLayout(context) {
            private boolean touchOwnedByHud = false;

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    if (isVideoOverlayControlsVisible()) {
                        scheduleOverlayAutoHide();
                    }
                    boolean tappedOverlayBtn = isVideoOverlayControlsVisible()
                            && isPointInsideView(videoOverlayControls, ev.getX(), ev.getY());
                    boolean tappedError = errorText != null
                            && errorText.getVisibility() == View.VISIBLE
                            && isPointInsideView(errorText, ev.getX(), ev.getY());
                    boolean tappedZoomReset = videoZoomController != null
                            && videoZoomController.getZoomResetPill().getVisibility() == View.VISIBLE
                            && isPointInsideView(videoZoomController.getZoomResetPill(), ev.getX(), ev.getY());
                    boolean tappedPtzPanel = ptzOverlayController != null
                            && ptzOverlayController.getPanelView().getVisibility() == View.VISIBLE
                            && isPointInsideView(ptzOverlayController.getPanelView(), ev.getX(), ev.getY());
                    touchOwnedByHud = tappedOverlayBtn || tappedError || tappedZoomReset || tappedPtzPanel;
                }

                if (touchOwnedByHud) {
                    boolean handled = super.dispatchTouchEvent(ev);
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        touchOwnedByHud = false;
                    }
                    return handled;
                }

                if (videoZoomController != null) {
                    return videoZoomController.onTouchEvent(ev);
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        videoContainer.setClickable(true);
        this.videoContainerRef = videoContainer;
        updateVideoContainerTheme();
        videoContainer.setClipToOutline(true);

        FrameLayout zoomViewport = new FrameLayout(context);
        videoContainer.addView(zoomViewport, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        this.videoZoomController = new FrigateVideoZoomController(
                context, theme, config, zoomViewport,
                new FrigateVideoZoomController.Listener() {
                    @Override
                    public void onSingleTapVideo() {
                        if (isVideoOverlayControlsVisible()) {
                            hideVideoOverlayControls(true);
                        } else {
                            showVideoOverlayControls(true);
                        }
                    }

                    @Override
                    public boolean isErrorVisible() {
                        return errorText != null && errorText.getVisibility() == View.VISIBLE;
                    }
                });

        // 0. Native Media3 ExoPlayer TextureView (primary hardware HLS VOD playback)
        zoomViewport.addView(exoController.createTextureView());
        videoContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                exoController.updateTextureSize();
                updateObjectBoxesBounds();
                if (videoZoomController != null) {
                    videoZoomController.onViewportSizeChanged();
                }
            }
        });

        // 1. Native Android VideoView (for recorded MP4 clips & history)
        videoView = new VideoView(context);
        FrameLayout.LayoutParams vvLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        vvLp.gravity = Gravity.CENTER;
        videoView.setLayoutParams(vvLp);
        videoView.setVisibility(View.GONE);
        zoomViewport.addView(videoView);

        // 2. HTML5 MSE WebSocket WebView (for live HD video + audio via Frigate go2rtc)
        zoomViewport.addView(webStreamController.createWebView(context), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 3. Real-time MJPEG Live Stream ImageView (fallback if go2rtc MSE is unavailable)
        liveImageView = new ImageView(context);
        liveImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        liveImageView.setVisibility(View.GONE);
        zoomViewport.addView(liveImageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 4. Fallback snapshot image view (displayed if video playback is unsupported or failed)
        fallbackImageView = new ImageView(context);
        fallbackImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fallbackImageView.setVisibility(View.GONE);
        zoomViewport.addView(fallbackImageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 4b. Real-time low-res snapshot & preview MP4 surfaces displayed while scrubbing vertical timeline
        scrubPreviewView = new ImageView(context);
        scrubPreviewView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        scrubPreviewView.setBackgroundColor(Color.TRANSPARENT);
        scrubPreviewView.setVisibility(View.GONE);
        zoomViewport.addView(scrubPreviewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        scrubPreviewTextureView = new TextureView(context);
        scrubPreviewTextureView.setVisibility(View.GONE);
        zoomViewport.addView(scrubPreviewTextureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        scrubPreviewPlayerController = new FrigateScrubPreviewPlayerController(
                context, host, config, apiClient, scrubPreviewTextureView,
                new FrigateScrubPreviewPlayerController.Listener() {
                    @Override
                    public void onScrubVideoFrameReady() {
                        if (scrubPreviewView != null) {
                            scrubPreviewView.setVisibility(View.GONE);
                        }
                    }

                    @Override
                    public void onScrubVideoFallbackNeeded(long epochSeconds) {
                        if (!isClosed && isRulerScrubbing) {
                            timelineController.requestScrubSnapshot(epochSeconds);
                        }
                    }
                });

        // 4c. Historical object bounding boxes overlay (interpolated along trajectory)
        objectBoxesOverlay = new FrigateObjectBoxesOverlayView(context);
        objectBoxesOverlay.setConfig(config);
        objectBoxesOverlay.setUiScale(config != null ? config.getUiScale() : 1.0f);
        objectBoxesOverlay.setVisibility((!isLiveStreamMode && showObjectBoxes) ? View.VISIBLE : View.GONE);
        zoomViewport.addView(objectBoxesOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 4c-ter. Zoom factor & one-tap reset pill (top-right, visible only when zoomed in)
        FrameLayout.LayoutParams zoomResetLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        zoomResetLp.gravity = Gravity.TOP | Gravity.RIGHT;
        int zoomResetMargin = s(12);
        zoomResetLp.setMargins(0, zoomResetMargin, zoomResetMargin, 0);
        videoContainer.addView(videoZoomController.getZoomResetPill(), zoomResetLp);

        // 4c-quater. Floating ONVIF PTZ control panel (center-right, toggled by header PTZ button)
        this.ptzOverlayController = new FrigatePtzOverlayController(
                context, theme, config,
                new FrigatePtzOverlayController.Listener() {
                    @Override
                    public void onPtzCommand(String command) {
                        objectDetectorController.sendPtzCommand(currentCamera, command);
                    }

                    @Override
                    public void onPtzVisibilityChanged(boolean visible) {
                        updatePtzButtonStyle();
                    }
                });
        if (ptzInfo != null && ptzInfo.isSupported()) {
            ptzOverlayController.setPtzInfo(ptzInfo);
        }
        FrameLayout.LayoutParams ptzPanelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        ptzPanelLp.gravity = Gravity.CENTER_VERTICAL | Gravity.RIGHT;
        ptzPanelLp.rightMargin = s(16);
        videoContainer.addView(ptzOverlayController.getPanelView(), ptzPanelLp);

        // 4c-bis. Live camera activity border highlight + overlay object pills (matching home screen)
        liveHighlightView = new View(context);
        liveHighlightView.setVisibility(View.GONE);
        liveHighlightView.setClickable(false);
        liveHighlightView.setFocusable(false);
        videoContainer.addView(liveHighlightView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        liveOverlayBadgeRow = new LinearLayout(context);
        liveOverlayBadgeRow.setOrientation(LinearLayout.HORIZONTAL);
        liveOverlayBadgeRow.setGravity(Gravity.CENTER_VERTICAL);
        liveOverlayBadgeRow.setVisibility(View.GONE);
        liveOverlayBadgeRow.setClickable(false);
        liveOverlayBadgeRow.setFocusable(false);
        FrameLayout.LayoutParams liveBadgeLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        liveBadgeLp.gravity = Gravity.TOP | Gravity.LEFT;
        int liveBadgeMargin = s(12);
        liveBadgeLp.setMargins(liveBadgeMargin, liveBadgeMargin, 0, 0);
        videoContainer.addView(liveOverlayBadgeRow, liveBadgeLp);

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
            if (isNoRecordingError) {
                openCalendarModal();
            } else if (isLiveStreamMode) {
                startLiveStream();
            } else {
                exoController.resetSoftwareDecoderPreference();
                exoFallbackTriggered = false;
                hlsFallbackTriggered = false;
                loadVideo();
            }
        });
        videoContainer.addView(errorText);

        // 7. Translucent auto-fading overlay transport controls (SeekBar, -10s, Play/Pause, +10s, Loop) over the video
        videoOverlayControls = buildVideoOverlayControls();
        FrameLayout.LayoutParams ovLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        ovLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        ovLp.bottomMargin = s(18);
        videoContainer.addView(videoOverlayControls, ovLp);
        updateSeekBarVisibility();
        videoOverlayVisible = true;
        scheduleOverlayAutoHide();

        setupVideoListeners();

        return videoContainer;
    }

    private LinearLayout buildVideoOverlayControls() {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(Color.argb(185, 11, 16, 26));
        barBg.setCornerRadius(s(24));
        barBg.setStroke(s(1), Color.argb(70, 255, 255, 255));
        container.setBackground(barBg);
        container.setPadding(s(16), s(10), s(16), s(10));

        // Top row: -10s, Play/Pause, +10s, and Loop toggle (horizontally centered)
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

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

        // Loop Toggle Button with Material Repeat vector icon (shown in Recorded Event mode)
        loopBtn = new TextView(context);
        loopBtn.setText(tr("player_loop"));
        loopBtn.setGravity(Gravity.CENTER);
        updateLoopButtonStyle();
        loopBtn.setPadding(s(14), s(8), s(14), s(8));
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
            showVideoOverlayControls(true);
        });
        LinearLayout.LayoutParams loopLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        loopLp.leftMargin = s(12);
        bar.addView(loopBtn, loopLp);

        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.CENTER_HORIZONTAL;
        container.addView(bar, barLp);

        // Bottom row: SeekBar with current and total duration labels (Event mode)
        overlaySeekRow = new LinearLayout(context);
        overlaySeekRow.setOrientation(LinearLayout.HORIZONTAL);
        overlaySeekRow.setGravity(Gravity.CENTER_VERTICAL);

        timeCurrentText = new TextView(context);
        timeCurrentText.setText(isLiveStreamMode ? tr("common_live") : "00:00");
        timeCurrentText.setTextSize(sp(13));
        timeCurrentText.setTextColor(Color.WHITE);
        timeCurrentText.setTypeface(null, android.graphics.Typeface.BOLD);
        timeCurrentText.setPadding(0, 0, s(10), 0);
        overlaySeekRow.addView(timeCurrentText);

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
                showVideoOverlayControls(false);
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
                scheduleOverlayAutoHide();
            }
        });
        overlaySeekRow.addView(seekBar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        timeDurationText = new TextView(context);
        timeDurationText.setText(isLiveStreamMode ? tr("common_live") : (initialDurationMs > 0 ? formatDuration(initialDurationMs) : "00:00"));
        timeDurationText.setTextSize(sp(13));
        timeDurationText.setTextColor(Color.argb(210, 226, 232, 240));
        timeDurationText.setPadding(s(10), 0, 0, 0);
        overlaySeekRow.addView(timeDurationText);

        LinearLayout.LayoutParams seekRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        seekRowLp.topMargin = s(8);
        container.addView(overlaySeekRow, seekRowLp);

        return container;
    }

    private void updateTransportModeBadge(String label, String textHex, String bgHex, String strokeHex) {
        if ("PREVIEW".equals(label)) {
            hideLiveFpsBadge();
        }
        this.lastTransportLabel = label;
        this.lastTransportTextHex = textHex;
        this.lastTransportBgHex = bgHex;
        this.lastTransportStrokeHex = strokeHex;

        if (transportModeBadge == null) return;
        if (config != null && !config.isShowStreamModePill()) {
            transportModeBadge.setVisibility(View.GONE);
            return;
        }

        boolean dark = FrigateTheme.isDark(theme);
        int textColor;
        int bgColor;
        int strokeColor;

        if (dark) {
            textColor = Color.parseColor(textHex);
            bgColor = Color.parseColor(bgHex);
            strokeColor = Color.parseColor(strokeHex);
        } else {
            textColor = getLightBadgeTextColor(strokeHex, textHex);
            bgColor = getLightBadgeBgColor(strokeHex);
            strokeColor = getLightBadgeStrokeColor(strokeHex);
        }

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(s(FrigateTheme.radiusControl(theme)));
        bg.setStroke(s(1), strokeColor);
        transportModeBadge.setBackground(bg);
        transportModeBadge.setText(label);
        transportModeBadge.setTextColor(textColor);
        transportModeBadge.setVisibility(View.VISIBLE);
    }

    private int getLightBadgeTextColor(String strokeHex, String fallbackTextHex) {
        if (strokeHex == null) return Color.parseColor(fallbackTextHex);
        switch (strokeHex.toUpperCase(Locale.US)) {
            case "#10B981": // Emerald (MSE)
                return Color.parseColor("#047857");
            case "#0284C7": // Sky (HLS / EXO)
                return Color.parseColor("#0369A1");
            case "#F59E0B": // Amber (MJPEG / MP4)
                return Color.parseColor("#B45309");
            case "#7C3AED": // Purple (EXO SW)
                return Color.parseColor("#6D28D9");
            case "#334155": // Slate (Neutral / Preview)
            default:
                return Color.parseColor("#475569");
        }
    }

    private int getLightBadgeBgColor(String strokeHex) {
        if (strokeHex == null) return Color.parseColor("#F1F5F9");
        switch (strokeHex.toUpperCase(Locale.US)) {
            case "#10B981":
                return Color.parseColor("#ECFDF5");
            case "#0284C7":
                return Color.parseColor("#F0F9FF");
            case "#F59E0B":
                return Color.parseColor("#FFFBEB");
            case "#7C3AED":
                return Color.parseColor("#F5F3FF");
            case "#334155":
            default:
                return Color.parseColor("#F1F5F9");
        }
    }

    private int getLightBadgeStrokeColor(String strokeHex) {
        if (strokeHex == null) return Color.parseColor("#CBD5E1");
        switch (strokeHex.toUpperCase(Locale.US)) {
            case "#10B981":
                return Color.parseColor("#A7F3D0");
            case "#0284C7":
                return Color.parseColor("#BAE6FD");
            case "#F59E0B":
                return Color.parseColor("#FDE68A");
            case "#7C3AED":
                return Color.parseColor("#DDD6FE");
            case "#334155":
            default:
                return Color.parseColor("#CBD5E1");
        }
    }

    private void updateLiveFpsBadge(int fps) {
        this.lastLiveFps = fps;
        if (liveFpsBadge == null) return;
        boolean active = isLiveStreamMode ? !isLivePaused : (isPlaying && (isExoPlayback || isHlsPlayback));
        if (!active || fps < 0) {
            liveFpsBadge.setVisibility(View.GONE);
            return;
        }

        boolean dark = FrigateTheme.isDark(theme);
        int textColor = dark ? Color.parseColor("#93C5FD") : Color.parseColor("#1D4ED8");
        int bgColor = dark ? Color.parseColor("#0F1E33") : Color.parseColor("#EFF6FF");
        int strokeColor = dark ? Color.parseColor("#3B82F6") : Color.parseColor("#BFDBFE");

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(s(FrigateTheme.radiusControl(theme)));
        bg.setStroke(s(1), strokeColor);
        liveFpsBadge.setBackground(bg);
        liveFpsBadge.setText(fps + " FPS");
        liveFpsBadge.setTextColor(textColor);
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
            mBg.setColor(FrigateTheme.surfaceContainerHigh(theme));
            mBg.setCornerRadius(s(12));
            mBg.setStroke(s(1), FrigateTheme.outlineVariant(theme));
            muteBtn.setBackground(mBg);
            muteBtn.setText(tr("player_sound_muted"));
            int inactiveText = FrigateTheme.onSurfaceVariant(theme);
            muteBtn.setTextColor(inactiveText);
            muteBtn.setTypeface(null, android.graphics.Typeface.NORMAL);
            iconColor = inactiveText;
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
            bg.setColor(FrigateTheme.surfaceContainerHigh(theme));
            bg.setCornerRadius(s(12));
            bg.setStroke(s(1), FrigateTheme.outlineVariant(theme));
            btn.setBackground(bg);
            int inactiveText = FrigateTheme.onSurfaceVariant(theme);
            btn.setTextColor(inactiveText);
            btn.setTypeface(null, android.graphics.Typeface.NORMAL);
            iconColor = inactiveText;
        }
        MaterialIcons.setIconOnTextView(btn, iconName, iconColor, s(18), s(6), theme);
    }

    private void updateLoopButtonStyle() {
        if (loopBtn == null) return;
        GradientDrawable bg = new GradientDrawable();
        if (isLooping) {
            bg.setColor(Color.parseColor("#2563EB"));
            bg.setCornerRadius(s(20));
            loopBtn.setBackground(bg);
            loopBtn.setTextColor(Color.WHITE);
            loopBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            bg.setColor(Color.argb(135, 30, 41, 59));
            bg.setCornerRadius(s(20));
            bg.setStroke(s(1), Color.argb(90, 148, 163, 184));
            loopBtn.setBackground(bg);
            loopBtn.setTextColor(Color.WHITE);
            loopBtn.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
        MaterialIcons.setIconOnTextView(loopBtn, MaterialIcons.REPEAT, Color.WHITE, s(18), s(6), theme);
    }

    private void updateBboxButtonStyle() {
        applyTogglePillStyle(bboxBtn, showObjectBoxes, MaterialIcons.BOUNDING_BOX);
    }

    private void updateBboxButtonVisibility() {
        if (bboxBtn != null) {
            bboxBtn.setVisibility(isLiveStreamMode ? View.GONE : View.VISIBLE);
        }
        updatePtzButtonVisibility();
    }

    private void fetchCameraPtzInfo() {
        if (apiClient == null || currentCamera == null || currentCamera.trim().isEmpty()) return;
        apiClient.fetchPtzInfo(currentCamera, new FrigateApiClient.Callback<PtzInfo>() {
            @Override
            public void onSuccess(PtzInfo result) {
                progressHandler.post(() -> {
                    if (isClosed) return;
                    ptzInfo = result != null ? result : PtzInfo.UNSUPPORTED;
                    if (ptzOverlayController != null) {
                        ptzOverlayController.setPtzInfo(ptzInfo);
                    }
                    updatePtzButtonVisibility();
                });
            }

            @Override
            public void onError(Exception error) {}
        });
    }

    private void updatePtzButtonStyle() {
        boolean active = ptzOverlayController != null && ptzOverlayController.isVisible();
        applyTogglePillStyle(ptzBtn, active, MaterialIcons.PTZ);
    }

    private void updatePtzButtonVisibility() {
        boolean show = isLiveStreamMode && ptzInfo != null && ptzInfo.isSupported();
        if (ptzBtn != null) {
            ptzBtn.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (!show && ptzOverlayController != null && ptzOverlayController.isVisible()) {
            ptzOverlayController.hide(false);
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
        if (overlaySeekRow != null) {
            overlaySeekRow.setVisibility(barVisibility);
        }
        if (timeCurrentText != null) {
            timeCurrentText.setVisibility(barVisibility);
        }
        if (seekBar != null) {
            seekBar.setVisibility(barVisibility);
        }
        if (timeDurationText != null) {
            timeDurationText.setVisibility(barVisibility);
        }
        if (videoOverlayControls != null && videoOverlayControls.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams ovLp = (FrameLayout.LayoutParams) videoOverlayControls.getLayoutParams();
            ovLp.width = hideSeekBar ? FrameLayout.LayoutParams.WRAP_CONTENT : FrameLayout.LayoutParams.MATCH_PARENT;
            ovLp.leftMargin = hideSeekBar ? 0 : s(24);
            ovLp.rightMargin = hideSeekBar ? 0 : s(24);
            videoOverlayControls.setLayoutParams(ovLp);
        }
    }

    // --- Vertical Frigate-Style Timeline & Real-Time Low-Res Snapshot Scrubbing ---

    private View buildVerticalTimelinePanel() {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);

        // Always visible current date header at the top of the timeline ruler, clickable to pick a day
        timelineDateBtn = new TextView(context);
        timelineDateBtn.setGravity(Gravity.CENTER);
        timelineDateBtn.setSingleLine(true);
        timelineDateBtn.setTextSize(sp(12));
        timelineDateBtn.setTextColor(FrigateTheme.onSurface(theme));
        timelineDateBtn.setTypeface(FrigateTheme.typeface(theme, 600));
        timelineDateBtn.setPadding(s(6), s(6), s(6), s(6));
        timelineDateBtn.setOnClickListener(v -> openCalendarModal());
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dateLp.bottomMargin = s(6);
        panel.addView(timelineDateBtn, dateLp);

        rulerWrapper = new FrameLayout(context);
        updateRulerWrapperTheme();
        rulerWrapper.setClipToOutline(true);

        float scale = (config != null) ? config.getUiScale() : 1.0f;
        timelineRuler = new FrigateTimelineRulerView(context, scale, theme);
        timelineRuler.setConfig(config);
        long now = System.currentTimeMillis() / 1000L;
        timelineRuler.setTimeRange(0L, now);
        long initCursor = isLiveStreamMode
                ? now
                : ((event != null && event.getStartTime() > 0) ? (long) event.getStartTime() : timelineStartEpoch);
        timelineRuler.setCursorEpoch(initCursor, isLiveStreamMode);
        updateTimelineDateHeader(initCursor);
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

        zoomOutBtn = buildZoomButton(MaterialIcons.ZOOM_OUT, tr("browser_zoom_out_desc"));
        zoomOutBtn.setOnClickListener(v -> {
            if (timelineRuler != null) timelineRuler.zoomOut();
        });
        zoomInBtn = buildZoomButton(MaterialIcons.ZOOM_IN, tr("browser_zoom_in_desc"));
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
        ImageView btn = MaterialIcons.createIconView(context, iconName, FrigateTheme.onSurface(theme), iconSize, theme);
        btn.setContentDescription(description);
        int pad = s(6);
        btn.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(FrigateTheme.surfaceContainerHigh(theme));
        bg.setCornerRadius(s(8));
        bg.setStroke(s(1), FrigateTheme.outlineVariant(theme));
        btn.setBackground(bg);
        return btn;
    }

    private void updateZoomButtonStyle(ImageView btn, String iconName) {
        if (btn == null) return;
        btn.setImageDrawable(MaterialIcons.getDrawable(iconName, FrigateTheme.onSurface(theme), s(18), theme));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(FrigateTheme.surfaceContainerHigh(theme));
        bg.setCornerRadius(s(8));
        bg.setStroke(s(1), FrigateTheme.outlineVariant(theme));
        btn.setBackground(bg);
    }

    private void updateTimelineDateHeader(long epochSeconds) {
        if (timelineDateBtn == null) return;
        boolean hasExplicitDay = selectedDayKey != null && !"all".equals(selectedDayKey);
        String label;
        if (hasExplicitDay) {
            long[] bounds = getDayBounds(selectedDayKey);
            long refMs = (bounds != null) ? (bounds[0] * 1000L) : System.currentTimeMillis();
            label = FrigateDateTimeUtils.formatShortNumericDate(context, config, refMs);
        } else {
            long tsSec = (epochSeconds > 0L) ? epochSeconds : (System.currentTimeMillis() / 1000L);
            label = FrigateDateTimeUtils.formatShortNumericDate(context, config, tsSec * 1000L);
        }
        timelineDateBtn.setText(label);
        GradientDrawable bg = new GradientDrawable();
        int calBg = hasExplicitDay ? FrigateTheme.primary(theme) : FrigateTheme.surfaceContainerHigh(theme);
        bg.setColor(calBg);
        bg.setCornerRadius(s(FrigateTheme.radiusControl(theme)));
        bg.setStroke(s(1), hasExplicitDay ? calBg : FrigateTheme.outlineVariant(theme));
        timelineDateBtn.setBackground(bg);
        int calText = hasExplicitDay ? FrigateTheme.onPrimary(theme) : FrigateTheme.onSurface(theme);
        timelineDateBtn.setTextColor(calText);
        MaterialIcons.setIconOnTextView(
                timelineDateBtn, MaterialIcons.CALENDAR,
                calText,
                s(13), s(5), theme);
    }

    private void openCalendarModal() {
        if (rootLayout == null || isClosed) return;
        long cursorEpoch = (timelineRuler != null)
                ? timelineRuler.getCursorEpoch()
                : (System.currentTimeMillis() / 1000L);
        Map<String, Integer> dayCounts = timelineController.getDayCountsForCurrentCamera();
        calendarModal.show(rootLayout, selectedDayKey, cursorEpoch, dayCounts,
                tr("common_live"), this::onCalendarDaySelected);
    }

    private void onCalendarDaySelected(String dayKey) {
        if (isClosed) return;
        String normalized = (dayKey == null || dayKey.trim().isEmpty()) ? "all" : dayKey.trim();
        if ("all".equals(normalized)) {
            pendingCalendarJumpBounds = null;
            selectedDayKey = "all";
            startLiveStream();
            return;
        }
        long[] bounds = getDayBounds(normalized);
        if (bounds == null) return;
        long now = System.currentTimeMillis() / 1000L;
        long dayStart = bounds[0];
        long dayEnd = Math.min(now, bounds[1]);
        if (dayEnd <= dayStart) {
            dayEnd = dayStart + 60L;
        }

        selectedDayKey = normalized;
        updateTimelineDateHeader(dayStart);

        if (timelineController.isRangeLoaded(dayStart, dayEnd)) {
            pendingCalendarJumpBounds = null;
            jumpToSelectedDayStart(dayStart, dayEnd);
        } else {
            pendingCalendarJumpBounds = new long[]{dayStart, dayEnd};
            if (timelineRuler != null) {
                timelineRuler.setCursorEpoch(dayStart, false);
            }
            timelineController.ensureTimelineCoverage(dayStart, dayEnd);
        }
    }

    private void tryFinishPendingCalendarJump() {
        long[] bounds = pendingCalendarJumpBounds;
        if (bounds == null || isClosed || isRulerScrubbing) return;
        long dayStart = bounds[0];
        long dayEnd = bounds[1];
        long firstRec = timelineController.findFirstRecordingEpochInRange(dayStart, dayEnd);
        if (firstRec >= 0L || timelineController.isRangeLoaded(dayStart, dayEnd)) {
            pendingCalendarJumpBounds = null;
            jumpToSelectedDayStart(dayStart, dayEnd);
        }
    }

    private void jumpToSelectedDayStart(long dayStart, long dayEnd) {
        long now = System.currentTimeMillis() / 1000L;
        long firstRec = timelineController.findFirstRecordingEpochInRange(dayStart, dayEnd);
        if (firstRec >= 0L) {
            long targetEpoch = firstRec;
            if (targetEpoch >= now - 4L) {
                selectedDayKey = "all";
                startLiveStream();
                return;
            }
            long windowSeconds = Math.max(30L, Math.min(1800L, Math.min(now, dayEnd) - targetEpoch));
            if (timelineRuler != null) {
                timelineRuler.setCursorEpoch(targetEpoch, false);
            }
            switchToRecordedMode(targetEpoch, windowSeconds);
            return;
        }
        long firstEvEpoch = timelineController.findFirstEventOrReviewEpochInRange(dayStart, dayEnd);
        if (firstEvEpoch >= 0L) {
            showEventPreviewOrNoRecording(firstEvEpoch);
            return;
        }
        showNoRecordingState(dayStart);
    }

    private void showEventPreviewOrNoRecording(long epochSeconds) {
        FrigateEvent ev = timelineController.findEventAtOrNearEpoch(epochSeconds);
        ReviewSegment rev = (ev == null) ? timelineController.findReviewAtOrNearEpoch(epochSeconds) : null;
        if (ev == null && rev == null) {
            showNoRecordingState(epochSeconds);
            return;
        }

        long targetEpoch = epochSeconds;
        long endEpoch = epochSeconds + 15L;
        String previewUrl = null;
        String snapshotUrl = null;
        if (ev != null) {
            if (ev.getStartTime() > 0) {
                targetEpoch = (long) Math.floor(ev.getStartTime());
                endEpoch = (ev.getEndTime() > ev.getStartTime())
                        ? (long) Math.ceil(ev.getEndTime())
                        : (targetEpoch + 15L);
            }
            previewUrl = apiClient.getEventPreviewClipUrl(ev);
            if (ev.getId() != null && !ev.getId().isEmpty() && !ev.isReviewFallback()) {
                snapshotUrl = ev.hasSnapshot()
                        ? apiClient.getSnapshotUrl(ev.getId())
                        : ev.getEffectiveThumbnailUrl(apiClient.getThumbnailUrl(ev.getId()));
            } else {
                snapshotUrl = ev.getEffectiveThumbnailUrl(null);
            }
        } else {
            if (rev.startTime > 0) {
                targetEpoch = (long) Math.floor(rev.startTime);
                endEpoch = (rev.endTime > rev.startTime)
                        ? (long) Math.ceil(rev.endTime)
                        : (targetEpoch + 15L);
            }
            previewUrl = (rev.id != null && !rev.id.isEmpty())
                    ? apiClient.getReviewPreviewUrl(rev.id)
                    : apiClient.getCameraPreviewMp4Url(currentCamera, targetEpoch, endEpoch);
            snapshotUrl = apiClient.getReviewThumbnailUrl(rev);
        }

        stopAllLiveStreams();
        final int sessionId = ++streamSessionId;
        stopExoPlayer();
        stopProgressUpdater();
        if (videoView != null) {
            try {
                videoView.stopPlayback();
            } catch (Exception ignored) {}
            videoView.setVisibility(View.GONE);
        }
        if (liveImageView != null) liveImageView.setVisibility(View.GONE);
        if (scrubPreviewView != null) scrubPreviewView.setVisibility(View.GONE);
        if (scrubHudPill != null) scrubHudPill.setVisibility(View.GONE);
        if (liveBadge != null) liveBadge.setVisibility(View.GONE);
        if (muteBtn != null) muteBtn.setVisibility(View.GONE);

        isLiveStreamMode = false;
        isLivePaused = false;
        isTimelineMode = true;
        isHistoryMode = true;
        isNoRecordingError = false;
        isExoPlayback = false;
        exoFallbackTriggered = false;
        isHlsPlayback = false;
        hlsFallbackTriggered = false;
        activePreviewFallbackUrl = previewUrl;
        activePreviewEvent = ev;

        long durationSec = Math.max(10L, endEpoch - targetEpoch);
        timelineStartEpoch = targetEpoch;
        timelineDurationSeconds = durationSec;
        eventTotalDurationMs = (int) (durationSec * 1000L);

        if (timelineRuler != null) {
            timelineRuler.setCursorEpoch(targetEpoch, false);
        }
        syncSelectedDayWithEpoch(targetEpoch);
        updateObjectBadgesForEpoch(targetEpoch);
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
        if (timeCurrentText != null) timeCurrentText.setText("00:00");
        if (timeDurationText != null) timeDurationText.setText(formatDuration(eventTotalDurationMs));

        if (snapshotUrl != null && !snapshotUrl.isEmpty() && fallbackImageView != null) {
            imageLoader.load(snapshotUrl, fallbackImageView);
            fallbackImageView.setVisibility(View.VISIBLE);
        }

        if (currentCamera != null && !currentCamera.isEmpty() && exoController.hasTextureView()) {
            if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
            if (errorText != null) errorText.setVisibility(View.GONE);
            updateTransportModeBadge(tr("common_preview_badge"), "#38BDF8", "#0C2135", "#0284C7");
            final long fTargetEpoch = targetEpoch;
            final long fEndEpoch = endEpoch;
            final FrigateEvent fEv = ev;
            apiClient.fetchPreviews(currentCamera, Math.max(0L, fTargetEpoch - 3600L), fEndEpoch + 3600L,
                    new FrigateApiClient.Callback<List<PreviewSegment>>() {
                        @Override
                        public void onSuccess(List<PreviewSegment> previews) {
                            progressHandler.post(() -> {
                                if (isClosed || sessionId != streamSessionId) return;
                                PreviewSegment best = selectBestPreviewSegment(previews, fTargetEpoch, fEndEpoch);
                                if (best == null) {
                                    showExpiredEventSnapshotFallback(fEv, fTargetEpoch);
                                    return;
                                }
                                String staticUrl = apiClient.getStaticPreviewUrl(best.src);
                                if (staticUrl == null || staticUrl.isEmpty()) {
                                    showExpiredEventSnapshotFallback(fEv, fTargetEpoch);
                                    return;
                                }
                                long clipStartMs = Math.max(0L, Math.round((fTargetEpoch - best.start - 4.0) * 1000.0));
                                long maxPreviewMs = Math.max(1000L, Math.round((best.end - best.start) * 1000.0));
                                long clipEndMs = Math.min(maxPreviewMs,
                                        Math.max(clipStartMs + 10000L, Math.round((fEndEpoch - best.start + 4.0) * 1000.0)));
                                activePreviewFallbackUrl = staticUrl;
                                activePreviewClipStartMs = clipStartMs;
                                activePreviewClipEndMs = clipEndMs;
                                if (config != null && config.isDebugLogging()) {
                                    android.util.Log.i("FrigatePlugin", "Streaming ExoPlayer static preview MP4: " + staticUrl
                                            + " [" + clipStartMs + "ms.." + clipEndMs + "ms]");
                                }
                                exoController.startPreview(sessionId, staticUrl, clipStartMs, clipEndMs, 8.0f);
                            });
                        }

                        @Override
                        public void onError(Exception error) {
                            progressHandler.post(() -> {
                                if (isClosed || sessionId != streamSessionId) return;
                                showExpiredEventSnapshotFallback(fEv, fTargetEpoch);
                            });
                        }
                    });
        } else {
            showExpiredEventSnapshotFallback(ev, targetEpoch);
        }
    }

    private static PreviewSegment selectBestPreviewSegment(List<PreviewSegment> previews, long startEpoch, long endEpoch) {
        if (previews == null || previews.isEmpty()) return null;
        PreviewSegment best = null;
        double bestOverlap = -1.0;
        for (PreviewSegment p : previews) {
            if (p == null || p.src.isEmpty()) continue;
            double overlap = Math.min(p.end, (double) endEpoch) - Math.max(p.start, (double) startEpoch);
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = p;
            }
        }
        return bestOverlap > 0.0 ? best : null;
    }

    private void showExpiredEventSnapshotFallback(FrigateEvent ev, long epochSeconds) {
        activePreviewFallbackUrl = null;
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        stopProgressUpdater();
        isPlaying = false;
        updatePlayPauseIcon();
        updateTransportModeBadge(tr("common_preview_badge"), "#94A3B8", "#161D2B", "#334155");
        if (errorText != null) {
            errorText.setText(tr("player_error_video_expired_kept"));
            errorText.setVisibility(View.VISIBLE);
        }
        if (ev != null && fallbackImageView != null && fallbackImageView.getVisibility() != View.VISIBLE) {
            String url = (!ev.isReviewFallback() && ev.getId() != null && !ev.getId().isEmpty())
                    ? (ev.hasSnapshot() ? apiClient.getSnapshotUrl(ev.getId())
                        : ev.getEffectiveThumbnailUrl(apiClient.getThumbnailUrl(ev.getId())))
                    : ev.getEffectiveThumbnailUrl(null);
            if (url != null && !url.isEmpty()) {
                imageLoader.load(url, fallbackImageView);
                fallbackImageView.setVisibility(View.VISIBLE);
            }
        }
    }

    private void showNoRecordingState(long epochSeconds) {
        ++streamSessionId;
        stopAllLiveStreams();
        stopExoPlayer();
        stopProgressUpdater();
        if (videoView != null) {
            try {
                videoView.stopPlayback();
            } catch (Exception ignored) {}
            videoView.setVisibility(View.GONE);
        }
        if (liveImageView != null) {
            liveImageView.setVisibility(View.GONE);
        }
        if (fallbackImageView != null) {
            fallbackImageView.setVisibility(View.GONE);
        }
        if (scrubPreviewView != null) {
            scrubPreviewView.setVisibility(View.GONE);
        }
        if (scrubHudPill != null) {
            scrubHudPill.setVisibility(View.GONE);
        }
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }
        if (liveBadge != null) {
            liveBadge.setVisibility(View.GONE);
        }
        if (muteBtn != null) {
            muteBtn.setVisibility(View.GONE);
        }

        isLiveStreamMode = false;
        isLivePaused = false;
        isTimelineMode = true;
        isHistoryMode = true;
        isPlaying = false;
        isNoRecordingError = true;
        activePreviewFallbackUrl = null;
        activePreviewEvent = null;
        timelineStartEpoch = epochSeconds;
        timelineDurationSeconds = 0L;
        eventTotalDurationMs = 0;

        if (timelineRuler != null) {
            timelineRuler.setCursorEpoch(epochSeconds, false);
        }
        syncSelectedDayWithEpoch(epochSeconds);
        updateObjectBadgesForEpoch(epochSeconds);
        updatePlayPauseIcon();
        updateLoopButtonVisibility();
        updateBboxButtonVisibility();
        updateSeekBarVisibility();
        setActiveVideoDimensions(0, 0, 1.0f);
        updateObjectBoxesOverlayVisibility();
        updateTransportModeBadge(tr("common_preview_badge"), "#94A3B8", "#161D2B", "#334155");
        if (errorText != null) {
            errorText.setText(tr("player_no_recording"));
            errorText.setVisibility(View.VISIBLE);
        }
    }

    private long[] getDayBounds(String dayKey) {
        if (dayKey == null || "all".equals(dayKey)) return null;
        try {
            Date parsed = dayKeyFmt.parse(dayKey);
            if (parsed == null) return null;
            Calendar startCal = Calendar.getInstance();
            startCal.setTime(parsed);
            startCal.set(Calendar.HOUR_OF_DAY, 0);
            startCal.set(Calendar.MINUTE, 0);
            startCal.set(Calendar.SECOND, 0);
            startCal.set(Calendar.MILLISECOND, 0);
            long startEpoch = startCal.getTimeInMillis() / 1000L;
            startCal.add(Calendar.DAY_OF_MONTH, 1);
            long endEpoch = startCal.getTimeInMillis() / 1000L;
            return new long[]{startEpoch, endEpoch};
        } catch (Exception e) {
            return null;
        }
    }

    private void syncSelectedDayWithEpoch(long epochSeconds) {
        if (selectedDayKey != null && !"all".equals(selectedDayKey)) {
            String cursorDayKey = dayKeyFmt.format(new Date(epochSeconds * 1000L));
            if (!selectedDayKey.equals(cursorDayKey)) {
                selectedDayKey = "all";
            }
        }
        updateTimelineDateHeader(epochSeconds);
    }

    private void onRulerScrubStart(long epochSeconds) {
        isRulerScrubbing = true;
        pendingCalendarJumpBounds = null;
        hideVideoOverlayControls(true);
        updateObjectBoxesOverlayVisibility();
        updateObjectBadgesForEpoch(epochSeconds);
        if (isExoPlayback && exoController.hasPlayer()) {
            try {
                exoController.setPlayWhenReady(false);
            } catch (Exception ignored) {}
        }
        updateScrubHud(epochSeconds);
        requestScrubPreview(epochSeconds);
    }

    private void onRulerScrubChanged(long epochSeconds) {
        syncSelectedDayWithEpoch(epochSeconds);
        updateObjectBadgesForEpoch(epochSeconds);
        updateScrubHud(epochSeconds);
        requestScrubPreview(epochSeconds);
    }

    private void requestScrubPreview(long epochSeconds) {
        PreviewSegment seg = timelineController.findPreviewSegmentAtEpoch(epochSeconds);
        if (seg != null && scrubPreviewPlayerController != null) {
            scrubPreviewPlayerController.seekToEpoch(seg, epochSeconds);
        } else {
            if (scrubPreviewPlayerController != null) {
                scrubPreviewPlayerController.hide();
            }
            timelineController.requestScrubSnapshot(epochSeconds);
        }
    }

    private void onRulerScrubEnd(long epochSeconds) {
        isRulerScrubbing = false;
        if (scrubHudPill != null) {
            scrubHudPill.setVisibility(View.GONE);
        }
        syncSelectedDayWithEpoch(epochSeconds);
        long now = System.currentTimeMillis() / 1000L;
        if (now - epochSeconds <= 4L) {
            startLiveStream();
            return;
        }
        long windowSeconds = Math.max(30L, Math.min(1800L, now - epochSeconds));
        if (timelineController.isRangeLoaded(epochSeconds, epochSeconds + windowSeconds)
                && timelineController.findFirstRecordingEpochInRange(epochSeconds, epochSeconds + windowSeconds) < 0L) {
            showEventPreviewOrNoRecording(epochSeconds);
            return;
        }
        switchToRecordedMode(epochSeconds, windowSeconds);
    }

    private void updateScrubHud(long epochSeconds) {
        long epochMs = epochSeconds * 1000L;
        if (scrubHudPill != null) {
            scrubHudPill.setText(tr("player_scrub_preview", FrigateDateTimeUtils.formatTimeWithSeconds(context, config, epochMs)));
            scrubHudPill.setVisibility(View.VISIBLE);
        }
    }

    private void hideScrubPreview() {
        if (scrubHudPill != null) {
            scrubHudPill.setVisibility(View.GONE);
        }
        if (scrubPreviewView != null && !isRulerScrubbing) {
            scrubPreviewView.setVisibility(View.GONE);
        }
        if (scrubPreviewPlayerController != null && !isRulerScrubbing) {
            scrubPreviewPlayerController.hide();
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
        isNoRecordingError = false;
        activePreviewFallbackUrl = null;
        activePreviewEvent = null;
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
            long liveNow = System.currentTimeMillis() / 1000L;
            selectedDayKey = "all";
            pendingCalendarJumpBounds = null;
            timelineRuler.setCursorEpoch(liveNow, true);
            updateTimelineDateHeader(liveNow);
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
        isNoRecordingError = false;
        activePreviewFallbackUrl = null;
        activePreviewEvent = null;
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
        if (timelineRuler != null) {
            timelineRuler.setCursorEpoch(startEpoch, false);
        }
        syncSelectedDayWithEpoch(startEpoch);

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
        isNoRecordingError = false;
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
            if (config != null && config.isDebugLogging()) {
                if (host != null) {
                    host.log("Frigate: Streaming ExoPlayer HLS VOD: " + hlsUrl);
                }
                android.util.Log.i("FrigatePlugin", "Streaming ExoPlayer HLS VOD: " + hlsUrl);
            }
            exoController.start(sessionId, hlsUrl, isMuted);
            return;
        }

        if (!hlsFallbackTriggered && webStreamController.hasWebView()) {
            stopExoPlayer();
            String hlsUrl = buildVodHlsUrl();
            isHlsPlayback = true;
            clipCacheManager.clearCurrentCachedFilePath();
            updateTransportModeBadge("HLS...", "#94A3B8", "#161D2B", "#334155");
            if (config != null && config.isDebugLogging()) {
                if (host != null) {
                    host.log("Frigate: Streaming hls.js VOD: " + hlsUrl);
                }
                android.util.Log.i("FrigatePlugin", "Streaming hls.js VOD: " + hlsUrl);
            }
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
                stopLiveObjectPoller();
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
                    long liveNow = System.currentTimeMillis() / 1000L;
                    timelineRuler.setCursorEpoch(liveNow, true);
                    updateTimelineDateHeader(liveNow);
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
            syncSelectedDayWithEpoch(wallEpoch);
            updateObjectBadgesForEpoch(wallEpoch);
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

    public boolean isOverlayVisible() {
        if (isClosed || rootLayout == null) return false;
        if (!rootLayout.isShown()) return false;
        if (rootLayout.getWindowVisibility() != View.VISIBLE) return false;
        if (!rootLayout.hasWindowFocus()) return false;
        return true;
    }

    private void releasePlayerResources() {
        if (isClosed) return;
        isClosed = true;
        if (calendarModal != null && rootLayout != null) {
            calendarModal.close(rootLayout);
        }
        stopLiveObjectPoller();
        stopAllLiveStreams();
        stopExoPlayer();
        stopProgressUpdater();
        progressHandler.removeCallbacksAndMessages(null);
        webStreamController.destroy();
        clipCacheManager.destroy();
        timelineController.destroy();
        if (scrubPreviewPlayerController != null) {
            scrubPreviewPlayerController.release();
        }
        if (videoZoomController != null) {
            videoZoomController.destroy();
        }
        if (ptzOverlayController != null) {
            ptzOverlayController.destroy();
        }
        if (videoView != null) {
            try {
                videoView.stopPlayback();
            } catch (Throwable ignored) {}
        }
    }

    private void closePlayer() {
        releasePlayerResources();
        if (onClose != null) {
            onClose.run();
        }
    }

    public void onDestroy() {
        // Release view and playback resources without notifying host that overlay should close,
        // allowing Kiosk Satellite to cleanly rebuild the overlay view if needed.
        releasePlayerResources();
    }

    public boolean isDestroyed() {
        return isClosed;
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
