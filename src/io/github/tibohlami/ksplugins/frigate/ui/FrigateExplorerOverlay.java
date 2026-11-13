// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.ui;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KsTheme;
import io.github.tibohlami.ksplugins.frigate.api.FrigateApiClient;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import io.github.tibohlami.ksplugins.frigate.model.FrigateCamera;
import io.github.tibohlami.ksplugins.frigate.model.FrigateEvent;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Modern, full-screen, opaque dashboard for Frigate Explorer.
 * Styled with crisp Material Design vector icons:
 * - Top title bar with Frigate Explorer branding, status subtitle, navigation tabs, refresh button, and close button.
 * - Home view: Horizontal scrollable "Last Events" and Live Cameras grid.
 * - Events view: Chronological event browser with filters and interactive timeline ruler.
 * - Full support for configurable UI scaling (`uiScale`).
 */
public class FrigateExplorerOverlay {

    public interface EventClickListener {
        void onEventClick(FrigateEvent event);
    }

    private final Context context;
    private final KsTheme theme;
    private final FrigateApiClient apiClient;
    private final FrigateConfig config;
    private final AsyncImageLoader imageLoader;
    private final Runnable onClose;
    private final EventClickListener eventClickListener;
    private final Handler mainHandler;
    private PlayerStateProvider playerStateProvider;

    public interface PlayerStateProvider {
        boolean isPlayerActive();
    }

    private LinearLayout mainPane;
    private TextView homeTabBtn;
    private TextView eventsTabBtn;
    private ScrollView homeScrollView;
    private View eventsBrowserRoot;
    private FrigateEventsBrowserView eventsBrowser;
    private LinearLayout recentEventsContainer;
    private LinearLayout camerasGridContainer;
    private TextView eventsSectionTitle;
    private TextView camerasSectionTitle;
    private TextView statusTextView;
    private ImageView refreshIconView;
    private ObjectAnimator refreshSpinAnimator;
    private long refreshSpinStartedAtMs = 0L;

    private boolean isEventsBrowserActive = false;
    private boolean eventsRefreshInFlight = false;
    private int currentCameraCardHeight = 0;
    private final List<FrigateCamera> discoveredCameras = new ArrayList<>();
    private final List<FrigateEvent> loadedEvents = new ArrayList<>();
    private final Map<String, ImageView> cameraPreviewMap = new LinkedHashMap<>();
    private volatile boolean isDestroyed = false;

    public boolean isOverlayVisible() {
        if (isDestroyed || mainPane == null) return false;
        if (playerStateProvider != null && playerStateProvider.isPlayerActive()) return false;
        if (!mainPane.isShown()) return false;
        if (mainPane.getWindowVisibility() != View.VISIBLE) return false;
        if (!mainPane.hasWindowFocus()) return false;
        return true;
    }

    public void setPlayerStateProvider(PlayerStateProvider provider) {
        this.playerStateProvider = provider;
    }

    public void onResumeFromPlayer() {
        if (isDestroyed || !isOverlayVisible()) return;
        refreshAllCameraSnapshots();
        loadEvents(true);
    }

    private final Runnable snapshotRefreshTask = new Runnable() {
        @Override
        public void run() {
            if (isDestroyed) return;
            if (isOverlayVisible() && !isEventsBrowserActive) {
                refreshAllCameraSnapshots();
            }
            mainHandler.postDelayed(this, 3000);
        }
    };

    private final Runnable eventsRefreshTask = new Runnable() {
        @Override
        public void run() {
            if (isDestroyed) return;
            if (isOverlayVisible() && !isEventsBrowserActive) {
                loadEvents(true);
            }
            mainHandler.postDelayed(this, 15000);
        }
    };

    public FrigateExplorerOverlay(Context context,
                                  KsTheme theme,
                                  FrigateApiClient apiClient,
                                  FrigateConfig config,
                                  AsyncImageLoader imageLoader,
                                  Runnable onClose,
                                  EventClickListener eventClickListener) {
        this.context = context;
        this.theme = theme;
        this.apiClient = apiClient;
        this.config = config;
        this.imageLoader = imageLoader;
        this.onClose = onClose;
        this.eventClickListener = eventClickListener;
        this.mainHandler = new Handler(Looper.getMainLooper());
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
        // Completely opaque, full screen root layout (no transparency)
        mainPane = new LinearLayout(context);
        mainPane.setOrientation(LinearLayout.VERTICAL);
        mainPane.setBackgroundColor(Color.parseColor("#0C0E14")); // 100% solid dark
        int pad = s(16);
        mainPane.setPadding(pad, pad, pad, pad);

        // Top Title Bar (branding, refresh, view tabs, status, close)
        mainPane.addView(buildHeaderBar());

        // Status view (Loading / Errors)
        statusTextView = new TextView(context);
        statusTextView.setVisibility(View.GONE);
        statusTextView.setGravity(Gravity.CENTER);
        statusTextView.setPadding(0, s(8), 0, s(8));
        statusTextView.setTextSize(sp(14));
        statusTextView.setTextColor(Color.parseColor("#94A3B8"));
        mainPane.addView(statusTextView);

        // Scrollable Home content (Recent Events + Cameras Grid)
        homeScrollView = new ScrollView(context);
        homeScrollView.setFillViewport(true);
        homeScrollView.setVerticalScrollBarEnabled(false);

        LinearLayout contentCol = new LinearLayout(context);
        contentCol.setOrientation(LinearLayout.VERTICAL);

        // --- SECTION 1: Last Events ---
        eventsSectionTitle = new TextView(context);
        eventsSectionTitle.setText(tr("section_last_events"));
        eventsSectionTitle.setTextSize(sp(18));
        eventsSectionTitle.setTextColor(Color.parseColor("#F1F5F9"));
        eventsSectionTitle.setPadding(0, s(4), 0, s(10));
        contentCol.addView(eventsSectionTitle);

        HorizontalScrollView eventsScroll = new HorizontalScrollView(context);
        eventsScroll.setHorizontalScrollBarEnabled(false);
        eventsScroll.setPadding(0, 0, 0, s(22));

        recentEventsContainer = new LinearLayout(context);
        recentEventsContainer.setOrientation(LinearLayout.HORIZONTAL);
        eventsScroll.addView(recentEventsContainer);
        contentCol.addView(eventsScroll);

        // --- SECTION 2: Cameras ---
        camerasSectionTitle = new TextView(context);
        camerasSectionTitle.setText(tr("section_cameras"));
        camerasSectionTitle.setTextSize(sp(18));
        camerasSectionTitle.setTextColor(Color.parseColor("#F1F5F9"));
        camerasSectionTitle.setPadding(0, 0, 0, s(12));
        contentCol.addView(camerasSectionTitle);

        camerasGridContainer = new LinearLayout(context);
        camerasGridContainer.setOrientation(LinearLayout.VERTICAL);
        contentCol.addView(camerasGridContainer);

        View.OnLayoutChangeListener gridFitListener =
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (top != oldTop || bottom != oldBottom || right != oldRight || left != oldLeft) {
                        adjustCameraGridHeightIfNeeded();
                    }
                };
        homeScrollView.addOnLayoutChangeListener(gridFitListener);
        camerasGridContainer.addOnLayoutChangeListener(gridFitListener);

        homeScrollView.addView(contentCol, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mainPane.addView(homeScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // Initial Data Loading
        loadCameras();
        loadEvents(false);
        mainHandler.postDelayed(snapshotRefreshTask, 3000);
        mainHandler.postDelayed(eventsRefreshTask, 15000);

        return mainPane;
    }

    public void onDestroy() {
        isDestroyed = true;
        mainHandler.removeCallbacks(snapshotRefreshTask);
        mainHandler.removeCallbacks(eventsRefreshTask);
        mainHandler.removeCallbacks(stopRefreshSpinnerRunnable);
        stopRefreshSpinnerNow();
        cameraPreviewMap.clear();
        if (eventsBrowser != null) {
            eventsBrowser.onDestroy();
        }
    }

    private void showHomeView() {
        isEventsBrowserActive = false;
        if (eventsBrowserRoot != null) {
            eventsBrowserRoot.setVisibility(View.GONE);
        }
        if (homeScrollView != null) {
            homeScrollView.setVisibility(View.VISIBLE);
        }
        updateHeaderTabSelection();
        loadEvents(true);
    }

    private void openEventsBrowser() {
        openEventsBrowser(null);
    }

    private void openEventsBrowser(String initialCameraFilter) {
        isEventsBrowserActive = true;
        if (statusTextView != null) {
            statusTextView.setVisibility(View.GONE);
        }
        if (homeScrollView != null) {
            homeScrollView.setVisibility(View.GONE);
        }
        if (eventsBrowser == null) {
            eventsBrowser = new FrigateEventsBrowserView(
                    context, theme, apiClient, config, imageLoader, eventClickListener);
            eventsBrowserRoot = eventsBrowser.buildView();
            eventsBrowser.setCameras(discoveredCameras);
            mainPane.addView(eventsBrowserRoot, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
        } else {
            eventsBrowserRoot.setVisibility(View.VISIBLE);
        }
        if (initialCameraFilter != null) {
            eventsBrowser.setCameraFilterAndRefresh(initialCameraFilter);
        } else {
            eventsBrowser.refresh();
        }
        updateHeaderTabSelection();
    }

    private void updateHeaderTabSelection() {
        styleHeaderTab(homeTabBtn, MaterialIcons.HOME, !isEventsBrowserActive);
        styleHeaderTab(eventsTabBtn, MaterialIcons.CALENDAR, isEventsBrowserActive);
    }

    private void styleHeaderTab(TextView btn, String iconName, boolean active) {
        if (btn == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(s(12));
        if (active) {
            bg.setColor(Color.parseColor("#E53935"));
        } else {
            bg.setColor(Color.parseColor("#182030"));
            bg.setStroke(s(1), Color.parseColor("#2A374F"));
        }
        btn.setBackground(bg);
        int textColor = active ? Color.WHITE : Color.parseColor("#CBD5E1");
        btn.setTextColor(textColor);
        btn.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        MaterialIcons.setIconOnTextView(btn, iconName, textColor, s(15), s(6), theme);
    }

    private View buildHeaderBar() {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, s(14));

        // 1. Compact Frigate badge icon + Title
        FrameLayout logoView = new FrameLayout(context);
        GradientDrawable logoBg = new GradientDrawable();
        logoBg.setColor(Color.parseColor("#E53935"));
        logoBg.setCornerRadius(s(10));
        logoView.setBackground(logoBg);
        int logoSize = s(36);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(logoSize, logoSize);
        logoLp.rightMargin = s(10);
        logoView.setLayoutParams(logoLp);
        int logoIconSize = s(20);
        ImageView logoIcon = MaterialIcons.createIconView(context, MaterialIcons.CAMERA, Color.WHITE, logoIconSize, theme);
        FrameLayout.LayoutParams liLp = new FrameLayout.LayoutParams(logoIconSize, logoIconSize);
        liLp.gravity = Gravity.CENTER;
        logoView.addView(logoIcon, liLp);
        header.addView(logoView);

        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setText(tr("app_name"));
        titleView.setTextSize(sp(20));
        titleView.setTextColor(Color.WHITE);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setMaxLines(1);
        titleCol.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView statusSubtitle = new TextView(context);
        statusSubtitle.setText(tr("common_connected"));
        statusSubtitle.setTextSize(sp(11));
        statusSubtitle.setTextColor(Color.parseColor("#10B981"));
        statusSubtitle.setSingleLine(true);
        statusSubtitle.setIncludeFontPadding(false);
        MaterialIcons.setIconOnTextView(statusSubtitle, MaterialIcons.LIVE, Color.parseColor("#10B981"), s(10), s(5), theme);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = s(2);
        titleCol.addView(statusSubtitle, subLp);

        header.addView(titleCol, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 2. Top navigation tabs
        homeTabBtn = buildHeaderTab(tr("tab_home"), v -> showHomeView());
        LinearLayout.LayoutParams homeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        homeLp.leftMargin = s(18);
        header.addView(homeTabBtn, homeLp);

        eventsTabBtn = buildHeaderTab(tr("tab_events"), v -> openEventsBrowser());
        LinearLayout.LayoutParams eventsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        eventsLp.leftMargin = s(8);
        header.addView(eventsTabBtn, eventsLp);
        updateHeaderTabSelection();

        // Flexible spacer
        View spacer = new View(context);
        header.addView(spacer, new LinearLayout.LayoutParams(0, s(1), 1.0f));

        // 3. Refresh icon button on the right, next to Close button
        int actionBtnSize = s(36);
        FrameLayout refreshBtn = new FrameLayout(context);
        FrigateUiUtils.applyCloseButtonFeedback(
                refreshBtn,
                Color.parseColor("#182030"),
                Color.parseColor("#26334D"),
                Color.parseColor("#2A374F"),
                Color.parseColor("#64748B"),
                s(10),
                s(1)
        );
        LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(actionBtnSize, actionBtnSize);
        refLp.rightMargin = s(10);
        refreshBtn.setLayoutParams(refLp);

        int refIconSize = s(20);
        refreshIconView = MaterialIcons.createIconView(
                context, MaterialIcons.REFRESH, Color.parseColor("#CBD5E1"), refIconSize, theme);
        FrameLayout.LayoutParams rflp = new FrameLayout.LayoutParams(refIconSize, refIconSize);
        rflp.gravity = Gravity.CENTER;
        refreshBtn.addView(refreshIconView, rflp);
        refreshBtn.setOnClickListener(v -> {
            loadCameras();
            if (isEventsBrowserActive && eventsBrowser != null) {
                eventsBrowser.refresh();
            } else {
                loadEvents(false);
            }
        });
        header.addView(refreshBtn);

        // 5. Close button at top-right
        FrameLayout closeBtn = new FrameLayout(context);
        FrigateUiUtils.applyCloseButtonFeedback(
                closeBtn,
                Color.parseColor("#261418"),
                Color.parseColor("#7F1D1D"),
                Color.parseColor("#5C1D24"),
                Color.parseColor("#EF4444"),
                s(10),
                s(1)
        );
        closeBtn.setLayoutParams(new LinearLayout.LayoutParams(actionBtnSize, actionBtnSize));

        int closeIconSize = s(20);
        ImageView closeIcon = MaterialIcons.createIconView(
                context, MaterialIcons.CLOSE, Color.parseColor("#EF4444"), closeIconSize, theme);
        FrameLayout.LayoutParams cllp = new FrameLayout.LayoutParams(closeIconSize, closeIconSize);
        cllp.gravity = Gravity.CENTER;
        closeBtn.addView(closeIcon, cllp);
        closeBtn.setOnClickListener(v -> {
            if (isDestroyed) return;
            FrigateUiUtils.markButtonClosing(v);
            if (onClose != null) onClose.run();
        });
        header.addView(closeBtn);

        return header;
    }

    private TextView buildHeaderTab(String label, View.OnClickListener onClick) {
        TextView tab = new TextView(context);
        tab.setText(label);
        tab.setTextSize(sp(13));
        tab.setSingleLine(true);
        tab.setGravity(Gravity.CENTER_VERTICAL);
        tab.setPadding(s(12), s(7), s(12), s(7));
        tab.setOnClickListener(onClick);
        return tab;
    }

    private void loadCameras() {
        apiClient.fetchCameras(new FrigateApiClient.Callback<List<FrigateCamera>>() {
            @Override
            public void onSuccess(List<FrigateCamera> cameras) {
                mainHandler.post(() -> {
                    discoveredCameras.clear();
                    discoveredCameras.addAll(cameras);
                    renderCamerasGrid();
                    if (eventsBrowser != null) {
                        eventsBrowser.setCameras(discoveredCameras);
                    }
                });
            }

            @Override
            public void onError(Exception error) {
                // Silently continue
            }
        });
    }

    private static final long REFRESH_SPIN_DURATION_MS = 600L;

    private final Runnable stopRefreshSpinnerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!eventsRefreshInFlight) {
                stopRefreshSpinnerNow();
            }
        }
    };

    private void startRefreshSpinner() {
        if (refreshIconView == null || isDestroyed) return;
        mainHandler.removeCallbacks(stopRefreshSpinnerRunnable);
        if (refreshSpinAnimator == null) {
            refreshSpinAnimator = ObjectAnimator.ofFloat(refreshIconView, "rotation", 0f, 360f);
            refreshSpinAnimator.setDuration(REFRESH_SPIN_DURATION_MS);
            refreshSpinAnimator.setRepeatCount(ObjectAnimator.INFINITE);
            refreshSpinAnimator.setInterpolator(new LinearInterpolator());
        }
        if (!refreshSpinAnimator.isStarted()) {
            refreshSpinStartedAtMs = android.os.SystemClock.elapsedRealtime();
            refreshSpinAnimator.start();
        }
    }

    private void stopRefreshSpinner() {
        if (isDestroyed) {
            stopRefreshSpinnerNow();
            return;
        }
        long elapsed = android.os.SystemClock.elapsedRealtime() - refreshSpinStartedAtMs;
        long remaining = (elapsed < REFRESH_SPIN_DURATION_MS)
                ? (REFRESH_SPIN_DURATION_MS - elapsed)
                : (REFRESH_SPIN_DURATION_MS - (elapsed % REFRESH_SPIN_DURATION_MS));
        mainHandler.removeCallbacks(stopRefreshSpinnerRunnable);
        mainHandler.postDelayed(stopRefreshSpinnerRunnable, Math.max(0L, remaining));
    }

    private void stopRefreshSpinnerNow() {
        if (refreshSpinAnimator != null) {
            refreshSpinAnimator.cancel();
        }
        if (refreshIconView != null) {
            refreshIconView.setRotation(0f);
        }
    }

    private void loadEvents(final boolean silent) {
        if (eventsRefreshInFlight || isDestroyed) return;
        eventsRefreshInFlight = true;
        startRefreshSpinner();

        if (!silent && loadedEvents.isEmpty()) {
            statusTextView.setVisibility(View.VISIBLE);
            statusTextView.setText(tr("explorer_loading_events"));
        }

        apiClient.fetchEvents(null, null, 30, new FrigateApiClient.Callback<List<FrigateEvent>>() {
            @Override
            public void onSuccess(List<FrigateEvent> events) {
                mainHandler.post(() -> {
                    eventsRefreshInFlight = false;
                    stopRefreshSpinner();
                    if (isDestroyed) return;
                    statusTextView.setVisibility(View.GONE);
                    if (haveEventsChanged(loadedEvents, events)) {
                        loadedEvents.clear();
                        if (events != null) {
                            loadedEvents.addAll(events);
                        }
                        renderRecentEvents();
                    }
                });
            }

            @Override
            public void onError(Exception error) {
                mainHandler.post(() -> {
                    eventsRefreshInFlight = false;
                    stopRefreshSpinner();
                    if (isDestroyed) return;
                    if (!silent && loadedEvents.isEmpty()) {
                        statusTextView.setVisibility(View.VISIBLE);
                        statusTextView.setText(tr("explorer_error_loading", error.getMessage()));
                    }
                });
            }
        });
    }

    private boolean haveEventsChanged(List<FrigateEvent> current, List<FrigateEvent> next) {
        if (next == null) return !current.isEmpty();
        if (current.size() != next.size()) return true;
        for (int i = 0; i < current.size(); i++) {
            FrigateEvent a = current.get(i);
            FrigateEvent b = next.get(i);
            if (!String.valueOf(a.getId()).equals(String.valueOf(b.getId()))
                    || Math.abs(a.getEndTime() - b.getEndTime()) > 0.01
                    || a.hasClip() != b.hasClip()
                    || a.hasSnapshot() != b.hasSnapshot()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renders the top horizontal scroll row of recent events cards (matching Frigate Web UI).
     */
    private void renderRecentEvents() {
        recentEventsContainer.removeAllViews();
        eventsSectionTitle.setText(tr("section_last_events_count", loadedEvents.size()));

        if (loadedEvents.isEmpty()) {
            TextView emptyText = new TextView(context);
            emptyText.setText(tr("explorer_no_events_filter"));
            emptyText.setTextSize(sp(14));
            emptyText.setTextColor(Color.parseColor("#64748B"));
            emptyText.setPadding(0, s(16), 0, s(16));
            recentEventsContainer.addView(emptyText);
            return;
        }

        for (FrigateEvent event : loadedEvents) {
            recentEventsContainer.addView(buildRecentEventCard(event));
        }
    }

    private View buildRecentEventCard(FrigateEvent event) {
        FrameLayout card = new FrameLayout(context);
        int cardWidth = s(180);
        int cardHeight = s(135);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(cardWidth, cardHeight);
        cardLp.rightMargin = s(12);
        card.setLayoutParams(cardLp);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#171B26"));
        cardBg.setCornerRadius(s(12));
        card.setBackground(cardBg);
        card.setClipToOutline(true);

        // 1. Thumbnail image filling card
        ImageView thumbView = new ImageView(context);
        thumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(thumbView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        imageLoader.load(event.getEffectiveThumbnailUrl(apiClient.getThumbnailUrl(event.getId())), thumbView);

        // 2. Top-left Detection Badge with Material vector icon
        TextView badge = new TextView(context);
        int score = (int) Math.round(event.getTopScore() * 100);
        String labelDisplay = FrigateI18n.translateObjectLabel(context, config, event.getLabel());
        if (score > 0) labelDisplay += " " + score + "%";
        badge.setText(labelDisplay);
        badge.setTextSize(sp(11));
        badge.setTextColor(Color.WHITE);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        MaterialIcons.setIconOnTextView(badge, event.getMaterialIconName(), Color.WHITE, s(14), s(5), theme);

        GradientDrawable bBg = new GradientDrawable();
        bBg.setColor(Color.argb(210, 15, 23, 42));
        bBg.setCornerRadius(s(8));
        badge.setBackground(bBg);
        badge.setPadding(s(8), s(4), s(8), s(4));
        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bLp.gravity = Gravity.TOP | Gravity.LEFT;
        int m = s(8);
        bLp.setMargins(m, m, 0, 0);
        card.addView(badge, bLp);

        // 2b. Top-right Duration Badge (when event has a valid duration)
        if (event.getEndTime() > event.getStartTime() && Math.round(event.getDurationSeconds()) > 0) {
            TextView durBadge = new TextView(context);
            durBadge.setText(event.getFormattedDuration(context, config));
            durBadge.setTextSize(sp(11));
            durBadge.setTextColor(Color.parseColor("#E2E8F0"));
            durBadge.setTypeface(null, android.graphics.Typeface.BOLD);
            GradientDrawable dBg = new GradientDrawable();
            dBg.setColor(Color.argb(210, 15, 23, 42));
            dBg.setCornerRadius(s(8));
            durBadge.setBackground(dBg);
            durBadge.setPadding(s(7), s(4), s(7), s(4));
            FrameLayout.LayoutParams dLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            dLp.gravity = Gravity.TOP | Gravity.RIGHT;
            dLp.setMargins(0, m, m, 0);
            card.addView(durBadge, dLp);
        }

        // 3. Bottom Gradient overlay bar with Camera & Time
        LinearLayout bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(Color.argb(225, 10, 14, 22));
        barBg.setCornerRadius(s(8));
        bottomBar.setBackground(barBg);
        bottomBar.setPadding(s(8), s(4), s(8), s(4));

        TextView timeView = new TextView(context);
        timeView.setText(formatShortTime(event.getStartTime()));
        timeView.setTextSize(sp(12));
        timeView.setTextColor(Color.parseColor("#CBD5E1"));
        timeView.setSingleLine(true);
        bottomBar.addView(timeView);

        TextView camView = new TextView(context);
        camView.setText(FrigateCamera.formatName(event.getCamera()));
        camView.setTextSize(sp(11));
        camView.setTextColor(Color.parseColor("#94A3B8"));
        camView.setSingleLine(true);
        camView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        camView.setGravity(Gravity.END);
        camView.setPadding(s(6), 0, 0, 0);
        bottomBar.addView(camView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        FrameLayout.LayoutParams bbLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bbLp.gravity = Gravity.BOTTOM;
        bbLp.setMargins(m, 0, m, m);
        card.addView(bottomBar, bbLp);

        card.setOnClickListener(v -> {
            if (eventClickListener != null) {
                eventClickListener.onEventClick(event);
            }
        });

        return card;
    }

    /**
     * Renders the grid of cameras with latest snapshots, sized so at least 2 rows fit without scrolling.
     */
    private void renderCamerasGrid() {
        camerasGridContainer.removeAllViews();
        cameraPreviewMap.clear();
        camerasSectionTitle.setText(tr("section_cameras_count", discoveredCameras.size()));

        if (discoveredCameras.isEmpty()) {
            currentCameraCardHeight = 0;
            TextView emptyText = new TextView(context);
            emptyText.setText(tr("explorer_searching_cameras"));
            emptyText.setTextSize(sp(14));
            emptyText.setTextColor(Color.parseColor("#64748B"));
            camerasGridContainer.addView(emptyText);
            return;
        }

        int columns = 2; // 2 cameras per row for landscape screens
        int rowGap = s(12);
        int availGridHeight = getMeasuredAvailableCameraGridHeight();
        int cardHeight = computeTargetCameraCardHeight(availGridHeight);
        currentCameraCardHeight = cardHeight;

        LinearLayout currentRow = null;

        for (int i = 0; i < discoveredCameras.size(); i++) {
            if (i % columns == 0) {
                currentRow = new LinearLayout(context);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) {
                    rowLp.topMargin = rowGap;
                }
                currentRow.setLayoutParams(rowLp);
                camerasGridContainer.addView(currentRow);
            }

            FrigateCamera camera = discoveredCameras.get(i);
            View cameraCard = buildCameraCard(camera, cardHeight);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    0, cardHeight, 1.0f);
            if (i % columns > 0) {
                itemLp.leftMargin = s(16);
            }
            currentRow.addView(cameraCard, itemLp);
        }

        // Pad incomplete final row with invisible spacers so single cards stay aligned in their column
        int remainder = discoveredCameras.size() % columns;
        if (remainder != 0 && currentRow != null) {
            int missing = columns - remainder;
            for (int m = 0; m < missing; m++) {
                View emptySlot = new View(context);
                LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(
                        0, cardHeight, 1.0f);
                emptyLp.leftMargin = s(16);
                currentRow.addView(emptySlot, emptyLp);
            }
        }

        camerasGridContainer.post(this::adjustCameraGridHeightIfNeeded);
    }

    private int getMeasuredAvailableCameraGridHeight() {
        if (homeScrollView == null || camerasGridContainer == null) {
            return -1;
        }
        int scrollH = homeScrollView.getHeight();
        int gridTop = camerasGridContainer.getTop();
        if (scrollH <= 0 || gridTop < 0) {
            return -1;
        }
        return scrollH - gridTop;
    }

    private int computeTargetCameraCardHeight(int availGridHeight) {
        int columns = 2;
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int gridWidth = (camerasGridContainer != null && camerasGridContainer.getWidth() > 0)
                ? camerasGridContainer.getWidth()
                : (dm.widthPixels - s(32));
        int colWidth = Math.max(s(220), (gridWidth - s(16)) / columns);
        int ideal16x9Height = Math.round(colWidth * 9f / 16f);

        int rowGap = s(12);
        int usableGridHeight = availGridHeight;
        if (usableGridHeight <= 0) {
            int estChrome = s(32) + s(54) + s(38) + s(157) + s(36);
            usableGridHeight = Math.max(s(240), dm.heightPixels - estChrome);
        }
        int twoRowFitHeight = (usableGridHeight - rowGap) / 2;
        int minH = s(110);
        return Math.max(minH, Math.min(ideal16x9Height, twoRowFitHeight));
    }

    private void adjustCameraGridHeightIfNeeded() {
        if (isDestroyed || isEventsBrowserActive || camerasGridContainer == null || discoveredCameras.isEmpty()) {
            return;
        }
        int availGridHeight = getMeasuredAvailableCameraGridHeight();
        if (availGridHeight <= 0) {
            return;
        }
        int targetHeight = computeTargetCameraCardHeight(availGridHeight);
        if (Math.abs(targetHeight - currentCameraCardHeight) > 2) {
            mainHandler.post(() -> {
                if (!isDestroyed && !isEventsBrowserActive) {
                    applyCameraCardHeight(targetHeight);
                }
            });
        }
    }

    private void applyCameraCardHeight(int newHeight) {
        if (camerasGridContainer == null || newHeight <= 0) {
            return;
        }
        currentCameraCardHeight = newHeight;
        for (int r = 0; r < camerasGridContainer.getChildCount(); r++) {
            View rowView = camerasGridContainer.getChildAt(r);
            if (!(rowView instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) rowView;
            for (int c = 0; c < row.getChildCount(); c++) {
                View cell = row.getChildAt(c);
                android.view.ViewGroup.LayoutParams lp = cell.getLayoutParams();
                if (lp != null && lp.height != newHeight) {
                    lp.height = newHeight;
                    cell.setLayoutParams(lp);
                }
            }
        }
        camerasGridContainer.requestLayout();
    }

    private View buildCameraCard(FrigateCamera camera, int cardHeight) {
        FrameLayout card = new FrameLayout(context);
        card.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, cardHeight));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#000000")); // Black backdrop for clean aspect-ratio letterboxing
        cardBg.setCornerRadius(s(14));
        cardBg.setStroke(s(1), Color.parseColor("#1E2536"));
        card.setBackground(cardBg);
        card.setClipToOutline(true);

        // 1. Live Snapshot image with FIT_CENTER so nothing is cropped or shrinked!
        ImageView camPreview = new ImageView(context);
        camPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(camPreview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        cameraPreviewMap.put(camera.getName(), camPreview);
        imageLoader.loadFresh(apiClient.getLatestFrameUrl(camera.getName()) + "?t=" + System.currentTimeMillis(), camPreview);

        // 2. Camera Name Pill on Bottom-Left with Material Camera icon
        TextView nameBadge = new TextView(context);
        nameBadge.setText(camera.getFriendlyName());
        nameBadge.setTextSize(sp(13));
        nameBadge.setTextColor(Color.WHITE);
        nameBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        MaterialIcons.setIconOnTextView(nameBadge, MaterialIcons.CAMERA, Color.WHITE, s(16), s(6), theme);

        GradientDrawable nbBg = new GradientDrawable();
        nbBg.setColor(Color.argb(225, 15, 23, 42));
        nbBg.setCornerRadius(s(10));
        nameBadge.setBackground(nbBg);
        nameBadge.setPadding(s(12), s(6), s(12), s(6));
        FrameLayout.LayoutParams nbLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        nbLp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        int m = s(12);
        nbLp.setMargins(m, 0, 0, m);
        card.addView(nameBadge, nbLp);

        // 3. Action Buttons on Bottom-Right with Material Movie & Play icons
        LinearLayout actionsRow = new LinearLayout(context);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);

        // Filter events button
        TextView eventsBtn = new TextView(context);
        eventsBtn.setText(tr("camera_btn_events"));
        eventsBtn.setTextSize(sp(12));
        eventsBtn.setTextColor(Color.WHITE);
        eventsBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        MaterialIcons.setIconOnTextView(eventsBtn, MaterialIcons.MOVIE, Color.WHITE, s(15), s(6), theme);
        GradientDrawable ebBg = new GradientDrawable();
        ebBg.setColor(Color.argb(225, 30, 41, 59));
        ebBg.setCornerRadius(s(10));
        eventsBtn.setBackground(ebBg);
        eventsBtn.setPadding(s(12), s(6), s(12), s(6));
        eventsBtn.setOnClickListener(v -> openEventsBrowser(camera.getName()));
        actionsRow.addView(eventsBtn);

        // Live stream button
        TextView playBtn = new TextView(context);
        playBtn.setText(tr("camera_btn_live"));
        playBtn.setTextSize(sp(12));
        playBtn.setTextColor(Color.WHITE);
        playBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        MaterialIcons.setIconOnTextView(playBtn, MaterialIcons.PLAY, Color.WHITE, s(16), s(6), theme);
        GradientDrawable pbBg = new GradientDrawable();
        pbBg.setColor(Color.parseColor("#E53935")); // Frigate red
        pbBg.setCornerRadius(s(10));
        playBtn.setBackground(pbBg);
        playBtn.setPadding(s(14), s(6), s(14), s(6));
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pbLp.leftMargin = s(8);
        actionsRow.addView(playBtn, pbLp);

        playBtn.setOnClickListener(v -> openLivePlayer(camera.getName()));

        FrameLayout.LayoutParams actLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        actLp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        actLp.setMargins(0, 0, m, m);
        card.addView(actionsRow, actLp);

        card.setOnClickListener(v -> openLivePlayer(camera.getName()));

        return card;
    }

    private void refreshAllCameraSnapshots() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ImageView> entry : cameraPreviewMap.entrySet()) {
            imageLoader.loadFresh(apiClient.getLatestFrameUrl(entry.getKey()) + "?h=480&t=" + now, entry.getValue());
        }
    }

    private void openLivePlayer(String cameraName) {
        if (eventClickListener != null) {
            FrigateEvent liveCamEvent = new FrigateEvent(
                    null, cameraName, "Live", null, 1.0,
                    0, 0, true, true, null, null);
            eventClickListener.onEventClick(liveCamEvent);
        }
    }

    private String tr(String key, Object... args) {
        return FrigateI18n.tr(context, config, key, args);
    }

    private String formatShortTime(double epochSeconds) {
        if (epochSeconds <= 0) return "--:--";
        long epochMs = (long) (epochSeconds * 1000L);
        Calendar eventCal = Calendar.getInstance();
        eventCal.setTimeInMillis(epochMs);
        Calendar nowCal = Calendar.getInstance();

        String timeStr = FrigateDateTimeUtils.formatTimeShort(context, config, epochMs);
        if (eventCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
                && eventCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)) {
            return timeStr;
        }

        nowCal.add(Calendar.DAY_OF_YEAR, -1);
        if (eventCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
                && eventCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)) {
            return tr("event_yesterday_time", timeStr);
        }

        return FrigateDateTimeUtils.formatShortMonthDayTime(context, config, epochMs);
    }
}
