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
import me.jxl.kiosk.plugins.PluginHost;
import io.github.tibohlami.ksplugins.frigate.api.FrigateApiClient;
import io.github.tibohlami.ksplugins.frigate.api.FrigateHttpHelper;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import io.github.tibohlami.ksplugins.frigate.model.FrigateCamera;
import io.github.tibohlami.ksplugins.frigate.model.FrigateEvent;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
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

    private static final int RECENT_EVENT_CARD_WIDTH_DP = 180;
    private static final int RECENT_EVENT_CARD_HEIGHT_DP = 135;

    public interface EventClickListener {
        void onEventClick(FrigateEvent event);
    }

    private final Context context;
    private KsTheme theme;
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
    private LinearLayout homeTabBtn;
    private LinearLayout eventsTabBtn;
    private ScrollView homeScrollView;
    private View eventsBrowserRoot;
    private FrigateEventsBrowserView eventsBrowser;
    private LinearLayout recentEventsContainer;
    private LinearLayout camerasGridContainer;
    private TextView eventsSectionTitle;
    private TextView camerasSectionTitle;
    private TextView statusTextView;
    private TextView headerTitleView;
    private View statusDotView;
    private TextView statusSubtitleView;
    private FrameLayout refreshBtn;
    private ImageView refreshIconView;
    private FrameLayout closeBtn;
    private ImageView closeIcon;
    private ObjectAnimator refreshSpinAnimator;
    private long refreshSpinStartedAtMs = 0L;

    private boolean isEventsBrowserActive = false;
    private boolean eventsRefreshInFlight = false;

    private final FrigatePreviewPlayerController recentPreviewController;
    private final Map<String, View> recentHighlightMap = new HashMap<>();
    private final Map<String, String> recentSeverityMap = new HashMap<>();
    private String selectedRecentEventId = null;
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
        this.recentPreviewController = new FrigatePreviewPlayerController(context, config, apiClient);
        this.mainHandler = new Handler(Looper.getMainLooper());
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
        // Completely opaque, full screen root layout (no transparency)
        mainPane = new LinearLayout(context);
        mainPane.setOrientation(LinearLayout.VERTICAL);
        mainPane.setBackgroundColor(FrigateTheme.surface(theme));
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
        statusTextView.setTextColor(FrigateTheme.onSurfaceVariant(theme));
        statusTextView.setTypeface(FrigateTheme.typeface(theme, 400));
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
        eventsSectionTitle.setTextColor(FrigateTheme.onSurface(theme));
        eventsSectionTitle.setTypeface(FrigateTheme.typeface(theme, 600));
        eventsSectionTitle.setIncludeFontPadding(false);
        eventsSectionTitle.setPadding(0, 0, 0, s(4));
        contentCol.addView(eventsSectionTitle);

        HorizontalScrollView eventsScroll = new HorizontalScrollView(context);
        eventsScroll.setHorizontalScrollBarEnabled(false);
        eventsScroll.setPadding(0, 0, 0, s(20));

        recentEventsContainer = new LinearLayout(context);
        recentEventsContainer.setOrientation(LinearLayout.HORIZONTAL);
        eventsScroll.addView(recentEventsContainer);
        contentCol.addView(eventsScroll);

        // --- SECTION 2: Cameras ---
        camerasSectionTitle = new TextView(context);
        camerasSectionTitle.setText(tr("section_cameras"));
        camerasSectionTitle.setTextSize(sp(18));
        camerasSectionTitle.setTextColor(FrigateTheme.onSurface(theme));
        camerasSectionTitle.setTypeface(FrigateTheme.typeface(theme, 600));
        camerasSectionTitle.setIncludeFontPadding(false);
        camerasSectionTitle.setPadding(0, 0, 0, s(4));
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
        recentPreviewController.release();
        recentHighlightMap.clear();
        recentSeverityMap.clear();
        if (eventsBrowser != null) {
            eventsBrowser.onDestroy();
        }
    }

    public boolean isDestroyed() {
        return isDestroyed;
    }

    private void showHomeView() {
        isEventsBrowserActive = false;
        if (eventsBrowser != null) {
            eventsBrowser.detachPreview();
        }
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
        recentPreviewController.detach();
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

    public void onThemeChanged(KsTheme newTheme) {
        if (newTheme == null || isDestroyed) return;
        this.theme = newTheme;
        if (eventsBrowser != null) {
            eventsBrowser.onThemeChanged(newTheme);
        }
        applyTheme();
    }

    private void applyTheme() {
        if (mainPane != null) {
            mainPane.setBackgroundColor(FrigateTheme.surface(theme));
        }
        if (headerTitleView != null) {
            headerTitleView.setTextColor(FrigateTheme.onSurface(theme));
            headerTitleView.setTypeface(FrigateTheme.typeface(theme, 700));
        }
        if (statusDotView != null) {
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(FrigateTheme.success(theme));
            statusDotView.setBackground(dotBg);
        }
        if (statusSubtitleView != null) {
            statusSubtitleView.setTextColor(FrigateTheme.success(theme));
            statusSubtitleView.setTypeface(FrigateTheme.typeface(theme, 500));
        }
        if (refreshBtn != null) {
            FrigateUiUtils.applyCloseButtonFeedback(
                    refreshBtn,
                    FrigateTheme.surfaceContainerHigh(theme),
                    FrigateTheme.surfaceContainerHighest(theme),
                    FrigateTheme.outlineVariant(theme),
                    FrigateTheme.outline(theme),
                    s(FrigateTheme.radiusControl(theme)),
                    s(1)
            );
        }
        if (refreshIconView != null) {
            refreshIconView.setImageDrawable(
                    MaterialIcons.getDrawable(MaterialIcons.REFRESH, FrigateTheme.onSurfaceVariant(theme), s(20), theme));
        }
        if (closeBtn != null) {
            int errColor = FrigateTheme.error(theme);
            int errBgNormal = Color.argb(35, Color.red(errColor), Color.green(errColor), Color.blue(errColor));
            int errBgPressed = Color.argb(85, Color.red(errColor), Color.green(errColor), Color.blue(errColor));
            int errStrokeNormal = Color.argb(95, Color.red(errColor), Color.green(errColor), Color.blue(errColor));
            FrigateUiUtils.applyCloseButtonFeedback(
                    closeBtn,
                    errBgNormal,
                    errBgPressed,
                    errStrokeNormal,
                    errColor,
                    s(FrigateTheme.radiusControl(theme)),
                    s(1)
            );
        }
        if (closeIcon != null) {
            closeIcon.setImageDrawable(
                    MaterialIcons.getDrawable(MaterialIcons.CLOSE, FrigateTheme.error(theme), s(20), theme));
        }
        if (statusTextView != null) {
            statusTextView.setTextColor(FrigateTheme.onSurfaceVariant(theme));
            statusTextView.setTypeface(FrigateTheme.typeface(theme, 400));
        }
        if (eventsSectionTitle != null) {
            eventsSectionTitle.setTextColor(FrigateTheme.onSurface(theme));
            eventsSectionTitle.setTypeface(FrigateTheme.typeface(theme, 600));
        }
        if (camerasSectionTitle != null) {
            camerasSectionTitle.setTextColor(FrigateTheme.onSurface(theme));
            camerasSectionTitle.setTypeface(FrigateTheme.typeface(theme, 600));
        }
        updateHeaderTabSelection();
        renderRecentEvents();
        renderCamerasGrid();
    }

    private static class HeaderTabViews {
        final ImageView iv;
        final TextView tv;
        final GradientDrawable bg;
        final String iconName;

        HeaderTabViews(ImageView iv, TextView tv, GradientDrawable bg, String iconName) {
            this.iv = iv;
            this.tv = tv;
            this.bg = bg;
            this.iconName = iconName;
        }
    }

    private void updateHeaderTabSelection() {
        styleHeaderTab(homeTabBtn, !isEventsBrowserActive);
        styleHeaderTab(eventsTabBtn, isEventsBrowserActive);
    }

    private void styleHeaderTab(LinearLayout tab, boolean active) {
        if (tab == null) return;
        Object tag = tab.getTag();
        if (!(tag instanceof HeaderTabViews)) return;
        HeaderTabViews views = (HeaderTabViews) tag;
        int textColor;
        if (active) {
            views.bg.setColor(FrigateTheme.primary(theme));
            views.bg.setStroke(0, 0);
            textColor = FrigateTheme.onPrimary(theme);
            views.tv.setTypeface(FrigateTheme.typeface(theme, 600));
        } else {
            views.bg.setColor(FrigateTheme.surfaceContainerHigh(theme));
            views.bg.setStroke(s(1), FrigateTheme.outlineVariant(theme));
            textColor = FrigateTheme.onSurfaceVariant(theme);
            views.tv.setTypeface(FrigateTheme.typeface(theme, 500));
        }
        tab.setBackground(views.bg);
        views.iv.setImageDrawable(MaterialIcons.getDrawable(views.iconName, textColor, s(16), theme));
        views.tv.setTextColor(textColor);
    }

    private View buildHeaderBar() {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, s(14));

        // 1. Compact Frigate badge icon + Title (keeps original RED badge)
        FrameLayout logoView = new FrameLayout(context);
        GradientDrawable logoBg = new GradientDrawable();
        logoBg.setColor(Color.parseColor("#E53935"));
        logoBg.setCornerRadius(s(10));
        logoView.setBackground(logoBg);
        int logoSize = s(40);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(logoSize, logoSize);
        logoLp.rightMargin = s(12);
        logoView.setLayoutParams(logoLp);
        int logoIconSize = s(26);
        ImageView logoIcon = MaterialIcons.createIconView(context, MaterialIcons.CAMERA, Color.WHITE, logoIconSize, theme);
        FrameLayout.LayoutParams liLp = new FrameLayout.LayoutParams(logoIconSize, logoIconSize);
        liLp.gravity = Gravity.CENTER;
        logoView.addView(logoIcon, liLp);
        header.addView(logoView);

        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(context);
        this.headerTitleView = titleView;
        titleView.setText(tr("app_name"));
        titleView.setTextSize(sp(18));
        titleView.setTextColor(FrigateTheme.onSurface(theme));
        titleView.setTypeface(FrigateTheme.typeface(theme, 700));
        titleView.setMaxLines(1);
        titleView.setIncludeFontPadding(false);
        titleCol.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Perfectly aligned "Connected" status row
        LinearLayout statusRow = new LinearLayout(context);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        int succColor = FrigateTheme.success(theme);
        View dot = new View(context);
        this.statusDotView = dot;
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(succColor);
        dot.setBackground(dotBg);
        int dotSize = s(7);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
        dotLp.gravity = Gravity.CENTER_VERTICAL;
        dotLp.rightMargin = s(6);
        statusRow.addView(dot, dotLp);

        TextView statusSubtitle = new TextView(context);
        this.statusSubtitleView = statusSubtitle;
        statusSubtitle.setText(tr("common_connected"));
        statusSubtitle.setTextSize(sp(11));
        statusSubtitle.setTextColor(succColor);
        statusSubtitle.setTypeface(FrigateTheme.typeface(theme, 500));
        statusSubtitle.setSingleLine(true);
        statusSubtitle.setIncludeFontPadding(false);
        statusSubtitle.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.gravity = Gravity.CENTER_VERTICAL;
        statusRow.addView(statusSubtitle, subLp);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = s(3);
        titleCol.addView(statusRow, rowLp);

        header.addView(titleCol, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 2. Top navigation tabs
        homeTabBtn = buildHeaderTab(tr("tab_home"), MaterialIcons.HOME, !isEventsBrowserActive, v -> showHomeView());
        LinearLayout.LayoutParams homeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        homeLp.leftMargin = s(18);
        header.addView(homeTabBtn, homeLp);

        eventsTabBtn = buildHeaderTab(tr("tab_events"), MaterialIcons.CALENDAR, isEventsBrowserActive, v -> openEventsBrowser());
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
        this.refreshBtn = refreshBtn;
        FrigateUiUtils.applyCloseButtonFeedback(
                refreshBtn,
                FrigateTheme.surfaceContainerHigh(theme),
                FrigateTheme.surfaceContainerHighest(theme),
                FrigateTheme.outlineVariant(theme),
                FrigateTheme.outline(theme),
                s(FrigateTheme.radiusControl(theme)),
                s(1)
        );
        LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(actionBtnSize, actionBtnSize);
        refLp.rightMargin = s(10);
        refreshBtn.setLayoutParams(refLp);

        int refIconSize = s(20);
        refreshIconView = MaterialIcons.createIconView(
                context, MaterialIcons.REFRESH, FrigateTheme.onSurfaceVariant(theme), refIconSize, theme);
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
        int errColor = FrigateTheme.error(theme);
        int errBgNormal = Color.argb(35, Color.red(errColor), Color.green(errColor), Color.blue(errColor));
        int errBgPressed = Color.argb(85, Color.red(errColor), Color.green(errColor), Color.blue(errColor));
        int errStrokeNormal = Color.argb(95, Color.red(errColor), Color.green(errColor), Color.blue(errColor));

        FrameLayout closeBtn = new FrameLayout(context);
        this.closeBtn = closeBtn;
        FrigateUiUtils.applyCloseButtonFeedback(
                closeBtn,
                errBgNormal,
                errBgPressed,
                errStrokeNormal,
                errColor,
                s(FrigateTheme.radiusControl(theme)),
                s(1)
        );
        closeBtn.setLayoutParams(new LinearLayout.LayoutParams(actionBtnSize, actionBtnSize));

        int closeIconSize = s(20);
        ImageView closeIcon = MaterialIcons.createIconView(
                context, MaterialIcons.CLOSE, errColor, closeIconSize, theme);
        this.closeIcon = closeIcon;
        FrameLayout.LayoutParams cllp = new FrameLayout.LayoutParams(closeIconSize, closeIconSize);
        cllp.gravity = Gravity.CENTER;
        closeBtn.addView(closeIcon, cllp);
        closeBtn.setOnClickListener(v -> {
            FrigateUiUtils.markButtonClosing(v);
            if (onClose != null) onClose.run();
        });
        header.addView(closeBtn);

        return header;
    }

    private LinearLayout buildHeaderTab(String label, String iconName, boolean active, View.OnClickListener onClick) {
        LinearLayout tab = new LinearLayout(context);
        tab.setOrientation(LinearLayout.HORIZONTAL);
        tab.setGravity(Gravity.CENTER_VERTICAL);
        tab.setPadding(s(13), s(8), s(13), s(8));
        tab.setOnClickListener(onClick);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(s(FrigateTheme.radiusControl(theme)));
        int textColor = active ? FrigateTheme.onPrimary(theme) : FrigateTheme.onSurfaceVariant(theme);
        if (active) {
            bg.setColor(FrigateTheme.primary(theme));
        } else {
            bg.setColor(FrigateTheme.surfaceContainerHigh(theme));
            bg.setStroke(s(1), FrigateTheme.outlineVariant(theme));
        }
        tab.setBackground(bg);

        ImageView iv = MaterialIcons.createIconView(context, iconName, textColor, s(16), theme);
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(s(16), s(16));
        ivLp.gravity = Gravity.CENTER_VERTICAL;
        ivLp.rightMargin = s(7);
        tab.addView(iv, ivLp);

        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextSize(sp(13));
        tv.setTextColor(textColor);
        tv.setTypeface(FrigateTheme.typeface(theme, active ? 600 : 500));
        tv.setSingleLine(true);
        tv.setIncludeFontPadding(false);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvLp.gravity = Gravity.CENTER_VERTICAL;
        tab.addView(tv, tvLp);

        tab.setTag(new HeaderTabViews(iv, tv, bg, iconName));
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

        apiClient.fetchRecentEventsEnriched(30, new FrigateApiClient.Callback<List<FrigateEvent>>() {
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
        if (current.isEmpty()) return true;
        if (current.size() != next.size()) return true;
        for (int i = 0; i < current.size(); i++) {
            FrigateEvent a = current.get(i);
            FrigateEvent b = next.get(i);
            if (!String.valueOf(a.getId()).equals(String.valueOf(b.getId()))
                    || Math.abs(a.getEndTime() - b.getEndTime()) > 0.01
                    || a.hasClip() != b.hasClip()
                    || a.hasSnapshot() != b.hasSnapshot()
                    || !String.valueOf(a.getSeverity()).equals(String.valueOf(b.getSeverity()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renders the top horizontal scroll row of recent events cards (matching Frigate Web UI).
     */
    private void renderRecentEvents() {
        recentPreviewController.detach();
        recentHighlightMap.clear();
        recentSeverityMap.clear();
        selectedRecentEventId = null;
        recentEventsContainer.removeAllViews();
        eventsSectionTitle.setText(tr("section_last_events_count", loadedEvents.size()));

        if (loadedEvents.isEmpty()) {
            TextView emptyText = new TextView(context);
            emptyText.setText(tr("explorer_no_events_filter"));
            emptyText.setTextSize(sp(14));
            emptyText.setTextColor(FrigateTheme.onSurfaceVariant(theme));
            emptyText.setTypeface(FrigateTheme.typeface(theme, 400));
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
        int cardWidth = s(RECENT_EVENT_CARD_WIDTH_DP);
        int cardHeight = s(RECENT_EVENT_CARD_HEIGHT_DP);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(cardWidth, cardHeight);
        cardLp.rightMargin = s(12);
        card.setLayoutParams(cardLp);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(FrigateTheme.surfaceContainer(theme));
        cardBg.setCornerRadius(s(FrigateTheme.radiusControl(theme)));
        cardBg.setStroke(s(1), FrigateTheme.outlineVariant(theme));
        card.setBackground(cardBg);
        card.setClipToOutline(true);

        // 1. Thumbnail image filling card
        ImageView thumbView = new ImageView(context);
        thumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(thumbView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        imageLoader.load(event.getEffectiveThumbnailUrl(apiClient.getThumbnailUrl(event.getId())), thumbView);

        // 2. Top-left Detection Badge matching Events page dimensions
        int m = s(7);
        LinearLayout badge = new LinearLayout(context);
        badge.setOrientation(LinearLayout.HORIZONTAL);
        badge.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bBg = new GradientDrawable();
        bBg.setColor(Color.argb(215, 15, 23, 42));
        bBg.setCornerRadius(s(8));
        badge.setBackground(bBg);
        badge.setPadding(s(7), s(3), s(7), s(3));

        ImageView bIcon = MaterialIcons.createIconView(context, event.getMaterialIconName(), Color.WHITE, s(13), theme);
        LinearLayout.LayoutParams bIconLp = new LinearLayout.LayoutParams(s(13), s(13));
        bIconLp.gravity = Gravity.CENTER_VERTICAL;
        bIconLp.rightMargin = s(4);
        badge.addView(bIcon, bIconLp);

        TextView bText = new TextView(context);
        int score = (int) Math.round(event.getTopScore() * 100);
        String labelDisplay = FrigateI18n.translateObjectLabel(context, config, event.getLabel());
        if (score > 0) labelDisplay += " " + score + "%";
        bText.setText(labelDisplay);
        bText.setTextSize(sp(11));
        bText.setTextColor(Color.WHITE);
        bText.setTypeface(FrigateTheme.typeface(theme, 600));
        bText.setIncludeFontPadding(false);
        bText.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bTextLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bTextLp.gravity = Gravity.CENTER_VERTICAL;
        badge.addView(bText, bTextLp);

        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bLp.gravity = Gravity.TOP | Gravity.LEFT;
        bLp.setMargins(m, m, 0, 0);
        card.addView(badge, bLp);

        // 2b. Top-right Severity + Duration Pill (exact same as Events page)
        String severity = (event.getSeverity() != null && !event.getSeverity().isEmpty())
                ? event.getSeverity()
                : "detection";
        boolean isAlert = "alert".equalsIgnoreCase(severity);
        TextView typeBadge = new TextView(context);
        String typeText = tr(isAlert ? "browser_badge_alert" : "browser_badge_detection");
        String dur = event.getFormattedDuration(context, config);
        if (dur != null && !dur.isEmpty()) {
            typeText += " · " + dur;
        }
        if (!event.hasClip()) {
            typeText += " · " + tr("browser_badge_no_video");
        }
        typeBadge.setText(typeText);
        typeBadge.setTextSize(sp(10));
        typeBadge.setTextColor(Color.WHITE);
        typeBadge.setTypeface(FrigateTheme.typeface(theme, 600));
        typeBadge.setIncludeFontPadding(false);
        typeBadge.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable tbBg = new GradientDrawable();
        tbBg.setColor(isAlert ? Color.argb(225, 225, 29, 72) : Color.argb(215, 217, 119, 6));
        tbBg.setCornerRadius(s(8));
        typeBadge.setBackground(tbBg);
        typeBadge.setPadding(s(6), s(3), s(6), s(3));
        FrameLayout.LayoutParams tbLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        tbLp.gravity = Gravity.TOP | Gravity.RIGHT;
        tbLp.setMargins(0, m, m, 0);
        card.addView(typeBadge, tbLp);

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
        timeView.setTextColor(Color.parseColor("#F1F5F9"));
        timeView.setSingleLine(true);
        timeView.setIncludeFontPadding(false);
        bottomBar.addView(timeView);

        TextView camView = new TextView(context);
        camView.setText(FrigateCamera.formatName(event.getCamera()));
        camView.setTextSize(sp(11));
        camView.setTextColor(Color.parseColor("#94A3B8"));
        camView.setSingleLine(true);
        camView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        camView.setGravity(Gravity.END);
        camView.setIncludeFontPadding(false);
        camView.setPadding(s(6), 0, 0, 0);
        bottomBar.addView(camView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        FrameLayout.LayoutParams bbLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bbLp.gravity = Gravity.BOTTOM;
        bbLp.setMargins(m, 0, m, m);
        card.addView(bottomBar, bbLp);

        // 4. Highlight border overlay
        View highlightBorder = new View(context);
        highlightBorder.setVisibility(View.GONE);
        card.addView(highlightBorder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (event.getId() != null) {
            recentHighlightMap.put(event.getId(), highlightBorder);
            recentSeverityMap.put(event.getId(), severity);
        }

        card.setOnClickListener(v -> {
            if (config != null && config.isPreviewClipsOnSelection()) {
                handleRecentEventClickWithPreview(event, card);
            } else {
                if (eventClickListener != null) {
                    eventClickListener.onEventClick(event);
                }
            }
        });

        return card;
    }

    private void handleRecentEventClickWithPreview(FrigateEvent event, FrameLayout card) {
        if (event == null) return;
        String evId = event.getId();

        if (selectedRecentEventId != null && selectedRecentEventId.equals(evId) && recentPreviewController.isAttachedTo(card)) {
            if (eventClickListener != null) {
                eventClickListener.onEventClick(event);
            }
            return;
        }

        selectedRecentEventId = evId;
        updateRecentEventHighlights();

        String clipUrl = apiClient.getEventPreviewClipUrl(event);
        String clipPath = FrigateHttpHelper.getUrlPath(clipUrl);
        PluginHost host = (config != null) ? config.getHost() : null;
        FrigateHttpHelper.logDebug(host, config, "Frigate: [RecentEvents] selected " + evId + " clip=" + clipPath);

        if (clipUrl != null && !clipUrl.isEmpty()) {
            recentPreviewController.attachTo(card, event, clipUrl);
        } else {
            recentPreviewController.detach();
        }
    }

    private void updateRecentEventHighlights() {
        for (Map.Entry<String, View> entry : recentHighlightMap.entrySet()) {
            String evId = entry.getKey();
            View overlay = entry.getValue();
            boolean isSelected = selectedRecentEventId != null && selectedRecentEventId.equals(evId);
            if (!isSelected) {
                overlay.setVisibility(View.GONE);
                continue;
            }
            boolean isAlert = "alert".equalsIgnoreCase(recentSeverityMap.get(evId));
            int accent = isAlert ? Color.parseColor("#F43F5E") : Color.parseColor("#F59E0B");
            GradientDrawable hlBg = new GradientDrawable();
            hlBg.setColor(Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent)));
            hlBg.setCornerRadius(s(FrigateTheme.radiusControl(theme)));
            hlBg.setStroke(s(3), accent);
            overlay.setBackground(hlBg);
            overlay.setVisibility(View.VISIBLE);
        }
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
        int minH = s(RECENT_EVENT_CARD_HEIGHT_DP); // Camera cards must be at least the height of recent event thumbnails
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
        int cardBgColor = FrigateTheme.isDark(theme)
                ? Color.parseColor("#000000")
                : FrigateTheme.surfaceContainer(theme);
        cardBg.setColor(cardBgColor); // Clean aspect-ratio backdrop adapted to dark/light theme
        cardBg.setCornerRadius(s(FrigateTheme.radiusRow(theme)));
        cardBg.setStroke(s(1), FrigateTheme.outlineVariant(theme));
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
        LinearLayout nameBadge = new LinearLayout(context);
        nameBadge.setOrientation(LinearLayout.HORIZONTAL);
        nameBadge.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable nbBg = new GradientDrawable();
        nbBg.setColor(Color.argb(225, 15, 23, 42));
        nbBg.setCornerRadius(s(10));
        nameBadge.setBackground(nbBg);
        nameBadge.setPadding(s(10), s(6), s(12), s(6));

        ImageView nbIcon = MaterialIcons.createIconView(context, MaterialIcons.CAMERA, Color.WHITE, s(16), theme);
        LinearLayout.LayoutParams nbIconLp = new LinearLayout.LayoutParams(s(16), s(16));
        nbIconLp.gravity = Gravity.CENTER_VERTICAL;
        nbIconLp.rightMargin = s(6);
        nameBadge.addView(nbIcon, nbIconLp);

        TextView nbText = new TextView(context);
        nbText.setText(camera.getFriendlyName());
        nbText.setTextSize(sp(13));
        nbText.setTextColor(Color.WHITE);
        nbText.setTypeface(FrigateTheme.typeface(theme, 600));
        nbText.setIncludeFontPadding(false);
        nbText.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams nbTextLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nbTextLp.gravity = Gravity.CENTER_VERTICAL;
        nameBadge.addView(nbText, nbTextLp);

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
        LinearLayout eventsBtn = new LinearLayout(context);
        eventsBtn.setOrientation(LinearLayout.HORIZONTAL);
        eventsBtn.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable ebBg = new GradientDrawable();
        ebBg.setColor(Color.argb(225, 30, 41, 59));
        ebBg.setCornerRadius(s(10));
        eventsBtn.setBackground(ebBg);
        eventsBtn.setPadding(s(10), s(6), s(12), s(6));

        ImageView ebIcon = MaterialIcons.createIconView(context, MaterialIcons.MOVIE, Color.WHITE, s(15), theme);
        LinearLayout.LayoutParams ebIconLp = new LinearLayout.LayoutParams(s(15), s(15));
        ebIconLp.gravity = Gravity.CENTER_VERTICAL;
        ebIconLp.rightMargin = s(6);
        eventsBtn.addView(ebIcon, ebIconLp);

        TextView ebText = new TextView(context);
        ebText.setText(tr("camera_btn_events"));
        ebText.setTextSize(sp(12));
        ebText.setTextColor(Color.WHITE);
        ebText.setTypeface(FrigateTheme.typeface(theme, 600));
        ebText.setIncludeFontPadding(false);
        ebText.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ebTextLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ebTextLp.gravity = Gravity.CENTER_VERTICAL;
        eventsBtn.addView(ebText, ebTextLp);

        eventsBtn.setOnClickListener(v -> openEventsBrowser(camera.getName()));
        actionsRow.addView(eventsBtn);

        // Live stream button (kept in RED #E53935 per user requirement)
        LinearLayout playBtn = new LinearLayout(context);
        playBtn.setOrientation(LinearLayout.HORIZONTAL);
        playBtn.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pbBg = new GradientDrawable();
        pbBg.setColor(Color.parseColor("#E53935"));
        pbBg.setCornerRadius(s(10));
        playBtn.setBackground(pbBg);
        playBtn.setPadding(s(11), s(6), s(13), s(6));

        ImageView pbIcon = MaterialIcons.createIconView(context, MaterialIcons.PLAY, Color.WHITE, s(16), theme);
        LinearLayout.LayoutParams pbIconLp = new LinearLayout.LayoutParams(s(16), s(16));
        pbIconLp.gravity = Gravity.CENTER_VERTICAL;
        pbIconLp.rightMargin = s(6);
        playBtn.addView(pbIcon, pbIconLp);

        TextView pbText = new TextView(context);
        pbText.setText(tr("camera_btn_live"));
        pbText.setTextSize(sp(12));
        pbText.setTextColor(Color.WHITE);
        pbText.setTypeface(FrigateTheme.typeface(theme, 600));
        pbText.setIncludeFontPadding(false);
        pbText.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pbTextLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pbTextLp.gravity = Gravity.CENTER_VERTICAL;
        playBtn.addView(pbText, pbTextLp);

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
