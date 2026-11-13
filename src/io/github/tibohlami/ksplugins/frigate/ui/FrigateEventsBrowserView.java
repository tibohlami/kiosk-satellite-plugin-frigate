// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KsTheme;
import io.github.tibohlami.ksplugins.frigate.api.FrigateApiClient;
import io.github.tibohlami.ksplugins.frigate.api.FrigateEventsRepository;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import io.github.tibohlami.ksplugins.frigate.model.FrigateCamera;
import io.github.tibohlami.ksplugins.frigate.model.FrigateEvent;
import io.github.tibohlami.ksplugins.frigate.model.RecordingSegment;
import io.github.tibohlami.ksplugins.frigate.model.ReviewSegment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Full-screen chronological event browser with multi-criteria filtering
 * (event type/severity, object label, camera), day-grouped thumbnail cards,
 * and an interactive Frigate-style right-hand vertical timeline ruler.
 */
public class FrigateEventsBrowserView {

    private final Context context;
    private final KsTheme theme;
    private final FrigateConfig config;
    private final AsyncImageLoader imageLoader;
    private final FrigateExplorerOverlay.EventClickListener eventClickListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final FrigateCalendarModal calendarModal;
    private final FrigateEventsRepository repository;

    private FrameLayout rootView;
    private LinearLayout mainContentLayout;
    private LinearLayout typeCameraFilterRow;
    private LinearLayout objectFilterRow;
    private TextView summaryCountView;
    private TextView emptyOrStatusView;
    private ScrollView eventsScrollView;
    private LinearLayout eventsListCol;
    private TextView loadMoreBtn;
    private TextView timelineDateBtn;
    private FrigateTimelineRulerView timelineRuler;

    private String selectedSeverity = "all"; // "all", "alert", "detection"
    private final Set<String> selectedLabels = new LinkedHashSet<>();
    private final Set<String> selectedCameras = new LinkedHashSet<>();
    private String selectedDayKey = "all"; // "all" or "yyyy-MM-dd"
    private String highlightedEventId = null;

    private final List<FrigateCamera> availableCameras = new ArrayList<>();
    private final List<String> availableLabels = new ArrayList<>();
    private final SimpleDateFormat dayKeyFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final List<FrigateEvent> displayedEvents = new ArrayList<>();
    private final Map<String, View> cardViewMap = new LinkedHashMap<>();
    private final Map<String, View> cardHighlightMap = new LinkedHashMap<>();
    private final Map<String, String> cardSeverityMap = new HashMap<>();

    private volatile boolean isDestroyed = false;

    public FrigateEventsBrowserView(Context context,
                                    KsTheme theme,
                                    FrigateApiClient apiClient,
                                    FrigateConfig config,
                                    AsyncImageLoader imageLoader,
                                    FrigateExplorerOverlay.EventClickListener eventClickListener) {
        this.context = context;
        this.theme = theme;
        this.config = config;
        this.imageLoader = imageLoader;
        this.eventClickListener = eventClickListener;
        this.calendarModal = new FrigateCalendarModal(context, theme, config, dayKeyFmt);
        this.repository = new FrigateEventsRepository(apiClient, mainHandler,
                new FrigateEventsRepository.Listener() {
                    @Override
                    public void onTrackedLabelsLoaded(List<String> labels) {
                        if (isDestroyed || labels == null) return;
                        boolean changed = false;
                        for (String lbl : labels) {
                            if (!availableLabels.contains(lbl)) {
                                availableLabels.add(lbl);
                                changed = true;
                            }
                        }
                        if (changed) rebuildFilterChips();
                    }

                    @Override
                    public void onDaySummariesUpdated() {
                        if (!isDestroyed) rebuildFilterChips();
                    }

                    @Override
                    public void onPageApplied(int querySeq, List<FrigateEvent> uniqueNewEvents,
                                              boolean hasMoreOlder, boolean isInitialPage) {
                        if (isDestroyed || querySeq != repository.getActiveQuerySeq()) return;
                        applyLoadedPage(uniqueNewEvents, isInitialPage);
                    }

                    @Override
                    public void onPageEmpty(int querySeq, boolean isInitialPage) {
                        if (!isDestroyed && querySeq == repository.getActiveQuerySeq()) {
                            finishEmptyPage(isInitialPage);
                        }
                    }

                    @Override
                    public void onPageError(int querySeq, boolean isInitialPage) {
                        if (!isDestroyed && querySeq == repository.getActiveQuerySeq()) {
                            handleLoadError(isInitialPage);
                        }
                    }

                    @Override
                    public void onRecordingsUpdated(int querySeq, List<RecordingSegment> recordings) {
                        if (!isDestroyed && querySeq == repository.getActiveQuerySeq()
                                && selectedCameras.size() == 1 && timelineRuler != null) {
                            timelineRuler.setRecordings(recordings);
                        }
                    }
                });
    }

    private int s(float dp) {
        float scale = (config != null) ? config.getUiScale() : 1.35f;
        return Math.max(1, Math.round(theme.px(Math.round(dp * scale))));
    }

    private float sp(float sp) {
        float scale = (config != null) ? config.getUiScale() : 1.35f;
        return sp * scale;
    }

    public View buildView() {
        rootView = new FrameLayout(context);

        mainContentLayout = new LinearLayout(context);
        mainContentLayout.setOrientation(LinearLayout.VERTICAL);

        // 1. Filter Panel (Camera, Type, Object, Day + counter)
        mainContentLayout.addView(buildFilterPanel());

        // 2. Main Split Body: Chronological Grid (Left) + Timeline Ruler (Right)
        LinearLayout splitRow = new LinearLayout(context);
        splitRow.setOrientation(LinearLayout.HORIZONTAL);

        // Left: Scrollable chronological cards container
        LinearLayout leftCol = new LinearLayout(context);
        leftCol.setOrientation(LinearLayout.VERTICAL);

        emptyOrStatusView = new TextView(context);
        emptyOrStatusView.setVisibility(View.GONE);
        emptyOrStatusView.setGravity(Gravity.CENTER);
        emptyOrStatusView.setPadding(0, s(24), 0, s(16));
        emptyOrStatusView.setTextSize(sp(14));
        emptyOrStatusView.setTextColor(Color.parseColor("#94A3B8"));
        leftCol.addView(emptyOrStatusView);

        eventsScrollView = new ScrollView(context);
        eventsScrollView.setVerticalScrollBarEnabled(true);
        eventsScrollView.setFillViewport(true);

        eventsListCol = new LinearLayout(context);
        eventsListCol.setOrientation(LinearLayout.VERTICAL);
        eventsListCol.setPadding(0, s(4), s(8), s(16));
        eventsScrollView.addView(eventsListCol, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        leftCol.addView(eventsScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        splitRow.addView(leftCol, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));

        // Right: Vertical Timeline Ruler Panel matching Frigate player look
        LinearLayout.LayoutParams rulerLp = new LinearLayout.LayoutParams(
                s(98), LinearLayout.LayoutParams.MATCH_PARENT);
        rulerLp.leftMargin = s(8);
        splitRow.addView(buildRightTimelinePanel(), rulerLp);

        LinearLayout.LayoutParams splitLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        splitLp.topMargin = s(10);
        mainContentLayout.addView(splitRow, splitLp);

        rootView.addView(mainContentLayout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        rebuildFilterChips();
        repository.fetchConfiguredLabels();
        repository.fetchDaySummaries();
        return rootView;
    }

    public void setCameras(List<FrigateCamera> cameras) {
        availableCameras.clear();
        Set<String> validNames = new LinkedHashSet<>();
        if (cameras != null) {
            availableCameras.addAll(cameras);
            for (FrigateCamera cam : cameras) {
                if (cam.getName() != null) validNames.add(cam.getName());
            }
        }
        selectedCameras.retainAll(validNames);
        if (rootView != null) {
            rebuildFilterChips();
        }
    }

    public void setObjectFilter(String label) {
        selectedLabels.clear();
        String normalized = (label == null || label.trim().isEmpty()) ? "all" : label.trim();
        if (!"all".equals(normalized)) {
            if (!availableLabels.contains(normalized)) {
                availableLabels.add(normalized);
            }
            selectedLabels.add(normalized);
        }
        if (rootView != null) {
            rebuildFilterChips();
            reloadFromTop();
        }
    }

    public void setCameraFilterAndRefresh(String camera) {
        selectedCameras.clear();
        String normalized = (camera == null || camera.trim().isEmpty()) ? "all" : camera.trim();
        if (!"all".equals(normalized)) {
            selectedCameras.add(normalized);
        }
        if (rootView != null) {
            rebuildFilterChips();
            refresh();
        }
    }

    public void refresh() {
        if (isDestroyed || rootView == null) return;
        repository.fetchConfiguredLabels();
        repository.fetchDaySummaries();
        reloadFromTop();
    }

    public void onDestroy() {
        isDestroyed = true;
        repository.destroy();
        calendarModal.close(rootView);
        mainHandler.removeCallbacksAndMessages(null);
        cardViewMap.clear();
        cardHighlightMap.clear();
    }

    // --- Filter Bar ---

    private View buildFilterPanel() {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#121622"));
        bg.setCornerRadius(s(12));
        bg.setStroke(s(1), Color.parseColor("#1E273A"));
        panel.setBackground(bg);
        int padH = s(12);
        int padV = s(8);
        panel.setPadding(padH, padV, padH, padV);

        // Row 1: Type + Camera
        HorizontalScrollView row1Scroll = new HorizontalScrollView(context);
        row1Scroll.setHorizontalScrollBarEnabled(false);
        typeCameraFilterRow = new LinearLayout(context);
        typeCameraFilterRow.setOrientation(LinearLayout.HORIZONTAL);
        typeCameraFilterRow.setGravity(Gravity.CENTER_VERTICAL);
        row1Scroll.addView(typeCameraFilterRow);
        panel.addView(row1Scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Row 2: Object chips + count label
        LinearLayout row2 = new LinearLayout(context);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r2Lp.topMargin = s(6);
        row2.setLayoutParams(r2Lp);

        HorizontalScrollView row2Scroll = new HorizontalScrollView(context);
        row2Scroll.setHorizontalScrollBarEnabled(false);
        objectFilterRow = new LinearLayout(context);
        objectFilterRow.setOrientation(LinearLayout.HORIZONTAL);
        objectFilterRow.setGravity(Gravity.CENTER_VERTICAL);
        row2Scroll.addView(objectFilterRow);
        row2.addView(row2Scroll, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        summaryCountView = new TextView(context);
        summaryCountView.setTextSize(sp(12));
        summaryCountView.setTextColor(Color.parseColor("#94A3B8"));
        summaryCountView.setPadding(s(10), 0, 0, 0);
        summaryCountView.setSingleLine(true);
        row2.addView(summaryCountView);

        panel.addView(row2);

        return panel;
    }

    private void rebuildFilterChips() {
        if (typeCameraFilterRow == null || objectFilterRow == null) return;
        typeCameraFilterRow.removeAllViews();
        objectFilterRow.removeAllViews();

        // Camera group FIRST (multi-select, no "Toutes" pill; empty selection = all cameras)
        addFilterGroupTitle(typeCameraFilterRow, tr("browser_filter_camera"));
        for (FrigateCamera cam : availableCameras) {
            final String camName = cam.getName();
            addFilterChip(typeCameraFilterRow, FrigateCamera.formatName(camName), null, 0,
                    selectedCameras.contains(camName), "#2563EB", v -> toggleCamera(camName));
        }

        addVerticalDivider(typeCameraFilterRow);

        // Type group SECOND (no "All" pill; no active chip = all types)
        addFilterGroupTitle(typeCameraFilterRow, tr("browser_filter_type"));
        addFilterChip(typeCameraFilterRow, tr("browser_type_alerts"), null, Color.parseColor("#F43F5E"),
                "alert".equals(selectedSeverity), "#E11D48", v -> selectSeverity("alert"));
        addFilterChip(typeCameraFilterRow, tr("browser_type_detections"), null, Color.parseColor("#FB923C"),
                "detection".equals(selectedSeverity), "#EA580C", v -> selectSeverity("detection"));

        // Object group (multi-select, no "Tous" pill; empty selection = all objects)
        addFilterGroupTitle(objectFilterRow, tr("browser_filter_object"));
        for (String lbl : availableLabels) {
            final String labelKey = lbl;
            addFilterChip(objectFilterRow, formatObjectLabel(labelKey),
                    FrigateEvent.getMaterialIconForLabel(labelKey), 0,
                    selectedLabels.contains(labelKey), "#0EA5E9", v -> toggleLabel(labelKey));
        }
    }

    private void openCalendarModal() {
        if (rootView == null || isDestroyed) return;
        long cursorEpoch = (timelineRuler != null)
                ? timelineRuler.getCursorEpoch()
                : (System.currentTimeMillis() / 1000L);
        Map<String, Integer> dayCounts = repository.computeFilteredDayCounts(selectedCameras, selectedLabels);
        calendarModal.show(rootView, selectedDayKey, cursorEpoch, dayCounts, this::selectDay);
    }

    private void addFilterGroupTitle(LinearLayout parent, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(sp(12));
        tv.setTextColor(Color.parseColor("#94A3B8"));
        tv.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = s(6);
        parent.addView(tv, lp);
    }

    private void addVerticalDivider(LinearLayout parent) {
        View div = new View(context);
        div.setBackgroundColor(Color.parseColor("#2A374F"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s(1), s(20));
        lp.leftMargin = s(6);
        lp.rightMargin = s(12);
        parent.addView(div, lp);
    }

    private void addFilterChip(LinearLayout parent, String title, String iconName, int dotColor,
                               boolean active, String activeColorHex, View.OnClickListener onClick) {
        TextView chip = new TextView(context);
        chip.setText(title);
        chip.setTextSize(sp(12));
        chip.setSingleLine(true);
        chip.setGravity(Gravity.CENTER_VERTICAL);

        int bgColor = active ? Color.parseColor(activeColorHex) : Color.parseColor("#1A2130");
        int strokeColor = active ? bgColor : Color.parseColor("#2A374F");
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(s(14));
        bg.setStroke(s(1), strokeColor);
        chip.setBackground(bg);

        int textColor = active ? Color.WHITE : Color.parseColor("#CBD5E1");
        chip.setTextColor(textColor);
        chip.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        chip.setPadding(s(11), s(5), s(11), s(5));

        if (iconName != null) {
            MaterialIcons.setIconOnTextView(chip, iconName, textColor, s(14), s(5), theme);
        } else if (dotColor != 0) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(active ? Color.WHITE : dotColor);
            int dotSize = s(8);
            dot.setBounds(0, 0, dotSize, dotSize);
            chip.setCompoundDrawables(dot, null, null, null);
            chip.setCompoundDrawablePadding(s(6));
        }

        chip.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = s(6);
        parent.addView(chip, lp);
    }

    private void selectSeverity(String severity) {
        selectedSeverity = severity.equals(selectedSeverity) ? "all" : severity;
        rebuildFilterChips();
        reloadFromTop();
    }

    private void toggleCamera(String camera) {
        if (camera == null || camera.trim().isEmpty()) return;
        if (selectedCameras.contains(camera)) {
            selectedCameras.remove(camera);
        } else {
            selectedCameras.add(camera);
        }
        rebuildFilterChips();
        reloadFromTop();
    }

    private void toggleLabel(String label) {
        if (label == null || label.trim().isEmpty()) return;
        if (selectedLabels.contains(label)) {
            selectedLabels.remove(label);
        } else {
            selectedLabels.add(label);
        }
        rebuildFilterChips();
        reloadFromTop();
    }

    private void selectDay(String dayKey) {
        String normalized = (dayKey == null || dayKey.trim().isEmpty()) ? "all" : dayKey.trim();
        if (normalized.equals(selectedDayKey)) return;
        selectedDayKey = normalized;
        if (timelineRuler != null) {
            timelineRuler.setVisibleWindowSeconds("all".equals(normalized) ? 14400.0 : 86400.0);
        }
        updateTimelineDateHeader(timelineRuler != null ? timelineRuler.getCursorEpoch() : (System.currentTimeMillis() / 1000L));
        rebuildFilterChips();
        reloadFromTop();
    }

    private long[] getSelectedDayBounds() {
        if (selectedDayKey == null || "all".equals(selectedDayKey)) return null;
        try {
            Date parsed = dayKeyFmt.parse(selectedDayKey);
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

    // --- Right Timeline Ruler Panel ---

    private View buildRightTimelinePanel() {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);

        // Always visible current date header at the top of the timeline ruler, clickable to pick a day
        timelineDateBtn = new TextView(context);
        timelineDateBtn.setGravity(Gravity.CENTER);
        timelineDateBtn.setSingleLine(true);
        timelineDateBtn.setTextSize(sp(12));
        timelineDateBtn.setTextColor(Color.parseColor("#F1F5F9"));
        timelineDateBtn.setTypeface(null, Typeface.BOLD);
        timelineDateBtn.setPadding(s(6), s(6), s(6), s(6));
        timelineDateBtn.setOnClickListener(v -> openCalendarModal());
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dateLp.bottomMargin = s(6);
        panel.addView(timelineDateBtn, dateLp);

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
        timelineRuler.setEventBrowserMode(true);
        timelineRuler.setVisibleWindowSeconds(14400.0); // 4h default overview
        long now = System.currentTimeMillis() / 1000L;
        timelineRuler.setTimeRange(0L, now);
        timelineRuler.setCursorEpoch(now - 3600L, false);
        updateTimelineDateHeader(now);
        timelineRuler.setOnScrubListener(new FrigateTimelineRulerView.OnScrubListener() {
            @Override
            public void onScrubStart(long epochSeconds) {}

            @Override
            public void onScrubChanged(long epochSeconds) {
                updateTimelineDateHeader(epochSeconds);
                maybeLoadMoreForViewport();
            }

            @Override
            public void onScrubEnd(long epochSeconds) {
                updateTimelineDateHeader(epochSeconds);
                maybeLoadMoreForViewport();
            }

            @Override
            public void onViewportChanged(long bottomEpochSeconds, long topEpochSeconds) {
                maybeEnsureRecordingsForRange(bottomEpochSeconds, topEpochSeconds);
            }

            @Override
            public void onTapZone(long tapEpochSeconds, double hitToleranceSeconds) {
                handleTimelineZoneTap(tapEpochSeconds, hitToleranceSeconds);
            }
        });
        rulerWrapper.addView(timelineRuler, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        panel.addView(rulerWrapper, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // Zoom out / zoom in buttons matching player timeline
        LinearLayout zoomRow = new LinearLayout(context);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams zrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        zrLp.topMargin = s(6);

        ImageView zoomOutBtn = buildZoomButton(MaterialIcons.ZOOM_OUT, tr("browser_zoom_out_desc"));
        zoomOutBtn.setOnClickListener(v -> {
            if (timelineRuler != null) {
                timelineRuler.zoomOut();
                maybeLoadMoreForViewport();
            }
        });
        ImageView zoomInBtn = buildZoomButton(MaterialIcons.ZOOM_IN, tr("browser_zoom_in_desc"));
        zoomInBtn.setOnClickListener(v -> {
            if (timelineRuler != null) {
                timelineRuler.zoomIn();
            }
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

    private void updateTimelineDateHeader(long epochSeconds) {
        if (timelineDateBtn == null) return;
        boolean hasExplicitDay = selectedDayKey != null && !"all".equals(selectedDayKey);
        String label;
        if (hasExplicitDay) {
            long[] bounds = getSelectedDayBounds();
            long refMs = (bounds != null) ? (bounds[0] * 1000L) : System.currentTimeMillis();
            label = FrigateDateTimeUtils.formatShortNumericDate(context, config, refMs);
        } else {
            long tsSec = (epochSeconds > 0L) ? epochSeconds : (System.currentTimeMillis() / 1000L);
            label = FrigateDateTimeUtils.formatShortNumericDate(context, config, tsSec * 1000L);
        }
        timelineDateBtn.setText(label);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(hasExplicitDay ? "#231942" : "#161D2B"));
        bg.setCornerRadius(s(10));
        bg.setStroke(s(1), Color.parseColor(hasExplicitDay ? "#8B5CF6" : "#2A374F"));
        timelineDateBtn.setBackground(bg);
        MaterialIcons.setIconOnTextView(
                timelineDateBtn, MaterialIcons.CALENDAR,
                Color.parseColor(hasExplicitDay ? "#DDD6FE" : "#CBD5E1"),
                s(13), s(5), theme);
    }

    // --- Data Loading ---

    private FrigateEventsRepository.FilterQuery buildCurrentFilterQuery() {
        return new FrigateEventsRepository.FilterQuery(
                selectedCameras, selectedLabels, selectedSeverity, getSelectedDayBounds());
    }

    private void reloadFromTop() {
        highlightedEventId = null;

        displayedEvents.clear();
        cardViewMap.clear();
        cardHighlightMap.clear();
        cardSeverityMap.clear();

        if (timelineRuler != null) {
            timelineRuler.setHighlightedRange(0.0, 0.0);
            timelineRuler.setReviewSegments(Collections.emptyList());
            timelineRuler.setRecordings(Collections.emptyList());
            long now = System.currentTimeMillis() / 1000L;
            timelineRuler.setTimeRange(0L, now);
        }

        renderEventCards(false);
        updateStatus(tr("browser_loading_events"), false);
        repository.startReload(buildCurrentFilterQuery());
    }

    private void loadOlderPage() {
        if (isDestroyed) return;
        if (repository.loadOlderPage(buildCurrentFilterQuery()) && loadMoreBtn != null) {
            loadMoreBtn.setText(tr("common_loading"));
            loadMoreBtn.setEnabled(false);
        }
    }

    private void finishEmptyPage(boolean isInitialPage) {
        if (isDestroyed) return;
        if (isInitialPage && displayedEvents.isEmpty()) {
            updateStatus(tr("browser_no_events_filters"), true);
        } else {
            updateStatus(null, false);
        }
        renderEventCards(false);
        syncTimelineRulerData(isInitialPage);
    }

    private void handleLoadError(boolean isInitialPage) {
        if (isDestroyed) return;
        if (isInitialPage && displayedEvents.isEmpty()) {
            updateStatus(tr("browser_load_failed"), true);
        }
        if (loadMoreBtn != null) {
            loadMoreBtn.setText(tr("browser_load_more"));
            loadMoreBtn.setEnabled(true);
        }
    }

    private void applyLoadedPage(List<FrigateEvent> uniqueNewEvents, boolean isInitialPage) {
        if (isDestroyed) return;

        boolean labelsAdded = false;
        if (uniqueNewEvents != null) {
            for (FrigateEvent ev : uniqueNewEvents) {
                displayedEvents.add(ev);
                if (ev.getLabel() != null && !ev.getLabel().isEmpty() && !availableLabels.contains(ev.getLabel())) {
                    availableLabels.add(ev.getLabel());
                    labelsAdded = true;
                }
            }
        }
        Collections.sort(displayedEvents, (a, b) -> Double.compare(b.getStartTime(), a.getStartTime()));

        if (labelsAdded) {
            rebuildFilterChips();
        }

        updateStatus(displayedEvents.isEmpty() ? tr("browser_no_events_filters") : null,
                displayedEvents.isEmpty());
        renderEventCards(!isInitialPage);
        syncTimelineRulerData(isInitialPage);
    }

    private void syncTimelineRulerData(boolean recenterToLatest) {
        if (timelineRuler == null) return;
        long now = System.currentTimeMillis() / 1000L;
        long minEpoch = 0L;
        timelineRuler.setTimeRange(minEpoch, now);

        List<ReviewSegment> rulerSegments = new ArrayList<>(displayedEvents.size());
        for (int i = displayedEvents.size() - 1; i >= 0; i--) {
            FrigateEvent ev = displayedEvents.get(i);
            double start = ev.getStartTime();
            if (start <= 0.0) continue;
            double end = Math.max(ev.getEndTime(), start + 1.0);
            String sev = (ev.getSeverity() != null && !ev.getSeverity().isEmpty()) ? ev.getSeverity() : "detection";
            rulerSegments.add(new ReviewSegment(
                    ev.getId(), ev.getCamera(), start, end, sev,
                    Collections.singletonList(ev.getLabel()), Collections.emptyList()));
        }
        timelineRuler.setReviewSegments(rulerSegments);

        if (recenterToLatest) {
            long[] dayBounds = getSelectedDayBounds();
            if (dayBounds != null) {
                String todayKey = dayKeyFmt.format(new Date(now * 1000L));
                long targetEpoch = todayKey.equals(selectedDayKey)
                        ? (!displayedEvents.isEmpty()
                            ? (long) displayedEvents.get(0).getStartTime()
                            : Math.max(dayBounds[0], now - 3600L))
                        : ((dayBounds[0] + dayBounds[1]) / 2L);
                timelineRuler.setCursorEpoch(Math.max(0L, Math.min(now, targetEpoch)), false);
            } else if (!displayedEvents.isEmpty()) {
                long latest = (long) displayedEvents.get(0).getStartTime();
                timelineRuler.setCursorEpoch(Math.max(0L, latest), false);
            }
        }
        updateTimelineDateHeader(timelineRuler.getCursorEpoch());

        if (selectedCameras.size() == 1) {
            maybeEnsureRecordingsForRange(timelineRuler.getVisibleBottomEpoch(), timelineRuler.getVisibleTopEpoch());
        }
    }

    private void maybeLoadMoreForViewport() {
        double oldest = repository.getOldestFetchedStartTime();
        if (timelineRuler == null || repository.isLoadingPage() || !repository.hasMoreOlder() || oldest <= 0.0) return;
        long bottom = timelineRuler.getVisibleBottomEpoch();
        if (bottom <= (long) oldest + 300L) {
            loadOlderPage();
        }
    }

    private void maybeEnsureRecordingsForRange(long bottomEpoch, long topEpoch) {
        repository.ensureRecordingsForRange(selectedCameras, bottomEpoch, topEpoch);
    }

    // --- Event Cards Grid & Highlighting ---

    private void updateStatus(String msg, boolean showEmpty) {
        if (summaryCountView != null) {
            summaryCountView.setText(tr(displayedEvents.size() > 1 ? "browser_count_plural" : "browser_count_singular",
                    displayedEvents.size()));
        }
        if (emptyOrStatusView != null) {
            if (msg != null && (showEmpty || displayedEvents.isEmpty())) {
                emptyOrStatusView.setText(msg);
                emptyOrStatusView.setVisibility(View.VISIBLE);
            } else {
                emptyOrStatusView.setVisibility(View.GONE);
            }
        }
    }

    private void renderEventCards(boolean preserveScrollPosition) {
        if (eventsListCol == null) return;
        final int prevScrollY = preserveScrollPosition && eventsScrollView != null ? eventsScrollView.getScrollY() : 0;

        eventsListCol.removeAllViews();
        cardViewMap.clear();
        cardHighlightMap.clear();
        cardSeverityMap.clear();
        loadMoreBtn = null;

        if (displayedEvents.isEmpty()) {
            return;
        }

        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int approxGridWidth = Math.max(s(360), dm.widthPixels - s(32) - s(106) - s(16));
        int columns = Math.max(2, Math.min(4, approxGridWidth / s(205)));
        int colGap = s(10);
        int cardWidth = Math.max(s(145), (approxGridWidth - (colGap * (columns - 1))) / columns);
        int cardHeight = Math.max(s(112), Math.round(cardWidth * 0.68f));

        String currentDayKey = null;
        LinearLayout currentRow = null;
        int colIdx = 0;

        for (FrigateEvent ev : displayedEvents) {
            String dayKey = formatDaySectionTitle(ev.getStartTime());
            if (!dayKey.equals(currentDayKey)) {
                padRemainingRowColumns(currentRow, colIdx, columns, colGap);
                currentDayKey = dayKey;
                colIdx = 0;
                currentRow = null;
                eventsListCol.addView(buildDayHeader(dayKey));
            }

            if (colIdx % columns == 0) {
                currentRow = new LinearLayout(context);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = s(10);
                eventsListCol.addView(currentRow, rowLp);
            }

            View card = buildEventCard(ev, cardHeight);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(0, cardHeight, 1.0f);
            if ((colIdx % columns) < (columns - 1)) {
                itemLp.rightMargin = colGap;
            }
            currentRow.addView(card, itemLp);
            colIdx++;
        }
        padRemainingRowColumns(currentRow, colIdx, columns, colGap);

        if (repository.hasMoreOlder()) {
            loadMoreBtn = new TextView(context);
            loadMoreBtn.setText(repository.isLoadingPage() ? tr("common_loading") : tr("browser_load_more"));
            loadMoreBtn.setEnabled(!repository.isLoadingPage());
            loadMoreBtn.setTextSize(sp(13));
            loadMoreBtn.setTextColor(Color.parseColor("#E2E8F0"));
            loadMoreBtn.setGravity(Gravity.CENTER);
            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setColor(Color.parseColor("#182030"));
            btnBg.setCornerRadius(s(10));
            btnBg.setStroke(s(1), Color.parseColor("#2A374F"));
            loadMoreBtn.setBackground(btnBg);
            loadMoreBtn.setPadding(s(16), s(10), s(16), s(10));
            LinearLayout.LayoutParams lmLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lmLp.topMargin = s(6);
            loadMoreBtn.setOnClickListener(v -> loadOlderPage());
            eventsListCol.addView(loadMoreBtn, lmLp);
        }

        updateCardHighlights();

        if (preserveScrollPosition && eventsScrollView != null && prevScrollY > 0) {
            eventsScrollView.post(() -> eventsScrollView.scrollTo(0, prevScrollY));
        }
    }

    private void padRemainingRowColumns(LinearLayout row, int colIdx, int columns, int colGap) {
        if (row == null) return;
        int rem = colIdx % columns;
        if (rem == 0) return;
        int missing = columns - rem;
        for (int i = 0; i < missing; i++) {
            View spacer = new View(context);
            LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, s(1), 1.0f);
            if (i < missing - 1) {
                spLp.rightMargin = colGap;
            }
            row.addView(spacer, spLp);
        }
    }

    private View buildDayHeader(String dayLabel) {
        TextView header = new TextView(context);
        header.setText(dayLabel);
        header.setTextSize(sp(14));
        header.setTextColor(Color.parseColor("#CBD5E1"));
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, s(8), 0, s(8));
        return header;
    }

    private View buildEventCard(FrigateEvent event, int cardHeight) {
        FrameLayout card = new FrameLayout(context);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#171B26"));
        cardBg.setCornerRadius(s(12));
        card.setBackground(cardBg);
        card.setClipToOutline(true);

        // 1. Thumbnail
        ImageView thumbView = new ImageView(context);
        thumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(thumbView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        String thumbUrl = event.getEffectiveThumbnailUrl(repository.getThumbnailUrl(event.getId()));
        if (thumbUrl != null && !thumbUrl.isEmpty()) {
            imageLoader.load(thumbUrl, thumbView);
        }

        // 2. Top-left detection badge
        int m = s(7);
        TextView badge = new TextView(context);
        int score = (int) Math.round(event.getTopScore() * 100);
        String labelDisplay = formatObjectLabel(event.getLabel());
        if (score > 0) labelDisplay += " " + score + "%";
        badge.setText(labelDisplay);
        badge.setTextSize(sp(11));
        badge.setTextColor(Color.WHITE);
        badge.setTypeface(null, Typeface.BOLD);
        MaterialIcons.setIconOnTextView(badge, event.getMaterialIconName(), Color.WHITE, s(13), s(4), theme);

        GradientDrawable bBg = new GradientDrawable();
        bBg.setColor(Color.argb(215, 15, 23, 42));
        bBg.setCornerRadius(s(8));
        badge.setBackground(bBg);
        badge.setPadding(s(7), s(3), s(7), s(3));
        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bLp.gravity = Gravity.TOP | Gravity.LEFT;
        bLp.setMargins(m, m, 0, 0);
        card.addView(badge, bLp);

        // 3. Top-right severity + duration pill
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
        typeBadge.setTypeface(null, Typeface.BOLD);
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

        // 4. Bottom overlay bar with Time & Camera
        LinearLayout bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(Color.argb(225, 10, 14, 22));
        barBg.setCornerRadius(s(8));
        bottomBar.setBackground(barBg);
        bottomBar.setPadding(s(8), s(4), s(8), s(4));

        TextView timeView = new TextView(context);
        timeView.setText(formatEventCardTime(event.getStartTime()));
        timeView.setTextSize(sp(12));
        timeView.setTextColor(Color.parseColor("#E2E8F0"));
        timeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
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

        // 5. Highlight border overlay (activated when clicking its zone on the timeline ruler)
        View highlightBorder = new View(context);
        highlightBorder.setVisibility(View.GONE);
        card.addView(highlightBorder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (event.getId() != null) {
            cardViewMap.put(event.getId(), card);
            cardHighlightMap.put(event.getId(), highlightBorder);
            cardSeverityMap.put(event.getId(), severity);
        }

        card.setOnClickListener(v -> {
            if (eventClickListener != null) {
                eventClickListener.onEventClick(event);
            }
        });

        return card;
    }

    private void handleTimelineZoneTap(long tapEpochSeconds, double hitToleranceSeconds) {
        if (displayedEvents.isEmpty()) return;

        List<FrigateEvent> candidates = new ArrayList<>();
        for (FrigateEvent ev : displayedEvents) {
            double start = ev.getStartTime();
            double end = Math.max(start, ev.getEndTime());
            if (tapEpochSeconds >= (start - hitToleranceSeconds)
                    && tapEpochSeconds <= (end + hitToleranceSeconds)) {
                candidates.add(ev);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }

        Collections.sort(candidates, (a, b) -> {
            double midA = (a.getStartTime() + Math.max(a.getStartTime(), a.getEndTime())) * 0.5;
            double midB = (b.getStartTime() + Math.max(b.getStartTime(), b.getEndTime())) * 0.5;
            return Double.compare(Math.abs(midA - tapEpochSeconds), Math.abs(midB - tapEpochSeconds));
        });

        FrigateEvent chosen = candidates.get(0);
        if (highlightedEventId != null && candidates.size() > 1) {
            for (int i = 0; i < candidates.size(); i++) {
                if (highlightedEventId.equals(candidates.get(i).getId())) {
                    chosen = candidates.get((i + 1) % candidates.size());
                    break;
                }
            }
        }

        highlightedEventId = chosen.getId();
        updateCardHighlights();

        if (timelineRuler != null) {
            double start = chosen.getStartTime();
            double end = Math.max(chosen.getEndTime(), start + 1.0);
            timelineRuler.setHighlightedRange(start, end);
            timelineRuler.setCursorEpoch((long) start, false);
            updateTimelineDateHeader((long) start);
        }

        scrollToHighlightedCard(chosen.getId());
    }

    private void updateCardHighlights() {
        for (Map.Entry<String, View> entry : cardHighlightMap.entrySet()) {
            String evId = entry.getKey();
            View overlay = entry.getValue();
            boolean isSelected = highlightedEventId != null && highlightedEventId.equals(evId);
            if (!isSelected) {
                overlay.setVisibility(View.GONE);
                continue;
            }
            boolean isAlert = "alert".equalsIgnoreCase(cardSeverityMap.get(evId));
            int accent = isAlert ? Color.parseColor("#F43F5E") : Color.parseColor("#F59E0B");
            GradientDrawable hlBg = new GradientDrawable();
            hlBg.setColor(Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent)));
            hlBg.setCornerRadius(s(12));
            hlBg.setStroke(s(3), accent);
            overlay.setBackground(hlBg);
            overlay.setVisibility(View.VISIBLE);
        }
    }

    private void scrollToHighlightedCard(String eventId) {
        if (eventsScrollView == null || eventId == null) return;
        View target = cardViewMap.get(eventId);
        if (target == null) return;
        eventsScrollView.post(() -> {
            if (isDestroyed || eventsScrollView == null) return;
            Rect rect = new Rect();
            target.getDrawingRect(rect);
            eventsScrollView.offsetDescendantRectToMyCoords(target, rect);
            int targetTop = Math.max(0, rect.top - Math.max(0, (eventsScrollView.getHeight() - rect.height()) / 3));
            eventsScrollView.smoothScrollTo(0, targetTop);
        });
    }

    // --- Formatting Helpers ---

    private String tr(String key, Object... args) {
        return FrigateI18n.tr(context, config, key, args);
    }

    private String formatObjectLabel(String raw) {
        return FrigateI18n.translateObjectLabel(context, config, raw);
    }

    private String formatEventCardTime(double epochSeconds) {
        if (epochSeconds <= 0) return "--:--";
        return FrigateDateTimeUtils.formatTimeWithSeconds(context, config, (long) (epochSeconds * 1000L));
    }

    private String formatDaySectionTitle(double epochSeconds) {
        if (epochSeconds <= 0) return tr("common_unknown_date");
        long epochMs = (long) (epochSeconds * 1000L);
        Calendar evCal = Calendar.getInstance();
        evCal.setTimeInMillis(epochMs);
        Calendar nowCal = Calendar.getInstance();

        if (evCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
                && evCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)) {
            return tr("common_today");
        }
        nowCal.add(Calendar.DAY_OF_YEAR, -1);
        if (evCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
                && evCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)) {
            return tr("common_yesterday");
        }
        Locale loc = FrigateI18n.getEffectiveLocale(context, config);
        String raw = FrigateDateTimeUtils.formatFullWeekdayDayMonth(context, config, epochMs);
        if (raw.length() > 0) {
            raw = raw.substring(0, 1).toUpperCase(loc) + raw.substring(1);
        }
        return raw;
    }
}
