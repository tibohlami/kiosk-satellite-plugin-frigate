// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.frigate.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.frigate.config.FrigateConfig;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Full-screen monthly calendar modal allowing the user to pick a specific date
 * (with per-day event count indicators) or reset to all days.
 */
public class FrigateCalendarModal {

    public interface DaySelectionListener {
        void onDaySelected(String dayKey);
    }

    private final Context context;
    private final KsTheme theme;
    private final FrigateConfig config;
    private final SimpleDateFormat dayKeyFmt;

    private FrameLayout calendarModalOverlay;

    public FrigateCalendarModal(Context context, KsTheme theme, FrigateConfig config, SimpleDateFormat dayKeyFmt) {
        this.context = context;
        this.theme = theme;
        this.config = config;
        this.dayKeyFmt = dayKeyFmt;
    }

    private int s(float dp) {
        float scale = (config != null) ? config.getUiScale() : 1.35f;
        return Math.max(1, Math.round(theme.px(Math.round(dp * scale))));
    }

    private float sp(float sp) {
        float scale = (config != null) ? config.getUiScale() : 1.35f;
        return sp * scale;
    }

    private String tr(String key, Object... args) {
        return FrigateI18n.tr(context, config, key, args);
    }

    public void show(FrameLayout rootView,
                     String selectedDayKey,
                     long fallbackCursorEpochSeconds,
                     Map<String, Integer> dayCounts,
                     DaySelectionListener listener) {
        if (rootView == null) return;
        close(rootView);

        final Calendar todayCal = Calendar.getInstance();
        todayCal.set(Calendar.HOUR_OF_DAY, 0);
        todayCal.set(Calendar.MINUTE, 0);
        todayCal.set(Calendar.SECOND, 0);
        todayCal.set(Calendar.MILLISECOND, 0);
        final String todayKey = dayKeyFmt.format(todayCal.getTime());

        String initDay = (selectedDayKey != null && !"all".equals(selectedDayKey))
                ? selectedDayKey
                : dayKeyFmt.format(new Date(fallbackCursorEpochSeconds * 1000L));
        final String[] pendingDayRef = new String[]{initDay};

        final Calendar displayMonth = Calendar.getInstance();
        try {
            Date d = dayKeyFmt.parse(initDay);
            if (d != null) displayMonth.setTime(d);
        } catch (Exception ignored) {}
        displayMonth.set(Calendar.DAY_OF_MONTH, 1);
        displayMonth.set(Calendar.HOUR_OF_DAY, 0);
        displayMonth.set(Calendar.MINUTE, 0);
        displayMonth.set(Calendar.SECOND, 0);
        displayMonth.set(Calendar.MILLISECOND, 0);

        calendarModalOverlay = new FrameLayout(context);
        calendarModalOverlay.setBackgroundColor(Color.argb(190, 5, 8, 16));
        calendarModalOverlay.setClickable(true);
        calendarModalOverlay.setOnClickListener(v -> close(rootView));

        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int cardWidth = Math.min(dm.widthPixels - s(48), s(450));

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#0F172A"));
        cardBg.setCornerRadius(s(16));
        cardBg.setStroke(s(1), Color.parseColor("#334155"));
        card.setBackground(cardBg);
        card.setPadding(s(20), s(18), s(20), s(18));
        card.setClickable(true);

        // Modal title
        TextView titleView = new TextView(context);
        titleView.setText(tr("browser_calendar_title"));
        titleView.setTextSize(sp(17));
        titleView.setTextColor(Color.WHITE);
        titleView.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.bottomMargin = s(12);
        card.addView(titleView, tLp);

        // Month navigation header (<  Novembre 2026  >)
        LinearLayout monthRow = new LinearLayout(context);
        monthRow.setOrientation(LinearLayout.HORIZONTAL);
        monthRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mrLp.bottomMargin = s(12);

        TextView prevBtn = buildCalendarNavButton("\u2039");
        final TextView monthLabel = new TextView(context);
        monthLabel.setGravity(Gravity.CENTER);
        monthLabel.setTextSize(sp(15));
        monthLabel.setTextColor(Color.parseColor("#E2E8F0"));
        monthLabel.setTypeface(null, Typeface.BOLD);
        TextView nextBtn = buildCalendarNavButton("\u203a");

        monthRow.addView(prevBtn, new LinearLayout.LayoutParams(s(42), s(38)));
        monthRow.addView(monthLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        monthRow.addView(nextBtn, new LinearLayout.LayoutParams(s(42), s(38)));
        card.addView(monthRow, mrLp);

        // Weekday names
        LinearLayout weekRow = new LinearLayout(context);
        weekRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] weekDays = {
                tr("browser_weekday_mon"), tr("browser_weekday_tue"), tr("browser_weekday_wed"),
                tr("browser_weekday_thu"), tr("browser_weekday_fri"), tr("browser_weekday_sat"),
                tr("browser_weekday_sun")
        };
        for (String wd : weekDays) {
            TextView wtv = new TextView(context);
            wtv.setText(wd);
            wtv.setGravity(Gravity.CENTER);
            wtv.setTextSize(sp(12));
            wtv.setTextColor(Color.parseColor("#94A3B8"));
            wtv.setTypeface(null, Typeface.BOLD);
            weekRow.addView(wtv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        }
        LinearLayout.LayoutParams wrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wrLp.bottomMargin = s(8);
        card.addView(weekRow, wrLp);

        // 6x7 days grid container
        final LinearLayout gridContainer = new LinearLayout(context);
        gridContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(gridContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final Runnable renderMonth = new Runnable() {
            @Override
            public void run() {
                Locale loc = FrigateI18n.getEffectiveLocale(context, config);
                String mStr = FrigateDateTimeUtils.formatMonthYear(context, config, displayMonth.getTimeInMillis());
                if (mStr.length() > 0) {
                    mStr = mStr.substring(0, 1).toUpperCase(loc) + mStr.substring(1);
                }
                monthLabel.setText(mStr);

                boolean atCurrentMonth = displayMonth.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR)
                        && displayMonth.get(Calendar.MONTH) == todayCal.get(Calendar.MONTH);
                nextBtn.setEnabled(!atCurrentMonth);
                nextBtn.setAlpha(atCurrentMonth ? 0.35f : 1.0f);

                gridContainer.removeAllViews();

                Calendar cursor = (Calendar) displayMonth.clone();
                int dow = cursor.get(Calendar.DAY_OF_WEEK); // Sun=1..Sat=7
                int mondayOffset = (dow + 5) % 7; // Mon=0..Sun=6
                cursor.add(Calendar.DAY_OF_MONTH, -mondayOffset);

                int curMonth = displayMonth.get(Calendar.MONTH);
                int cellH = s(44);

                for (int r = 0; r < 6; r++) {
                    LinearLayout row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    rowLp.bottomMargin = s(4);

                    for (int c = 0; c < 7; c++) {
                        final String cellKey = dayKeyFmt.format(cursor.getTime());
                        final boolean inMonth = cursor.get(Calendar.MONTH) == curMonth;
                        final boolean isFuture = cursor.after(todayCal);
                        final boolean isToday = cellKey.equals(todayKey);
                        final boolean isSelected = cellKey.equals(pendingDayRef[0]);
                        final int evCount = (dayCounts != null && dayCounts.containsKey(cellKey)) ? dayCounts.get(cellKey) : 0;

                        LinearLayout cell = new LinearLayout(context);
                        cell.setOrientation(LinearLayout.VERTICAL);
                        cell.setGravity(Gravity.CENTER);

                        GradientDrawable cellBg = new GradientDrawable();
                        cellBg.setCornerRadius(s(10));
                        if (isSelected) {
                            cellBg.setColor(Color.parseColor("#8B5CF6"));
                        } else if (isToday) {
                            cellBg.setColor(Color.parseColor("#1E293B"));
                            cellBg.setStroke(s(1.5f), Color.parseColor("#38BDF8"));
                        } else if (!isFuture && evCount > 0 && inMonth) {
                            cellBg.setColor(Color.parseColor("#1A2338"));
                        } else {
                            cellBg.setColor(Color.TRANSPARENT);
                        }
                        cell.setBackground(cellBg);

                        TextView numView = new TextView(context);
                        numView.setText(String.valueOf(cursor.get(Calendar.DAY_OF_MONTH)));
                        numView.setGravity(Gravity.CENTER);
                        numView.setTextSize(sp(14));
                        if (isSelected) {
                            numView.setTextColor(Color.WHITE);
                            numView.setTypeface(null, Typeface.BOLD);
                        } else if (isFuture) {
                            numView.setTextColor(Color.parseColor("#334155"));
                        } else if (!inMonth) {
                            numView.setTextColor(Color.parseColor("#475569"));
                        } else {
                            numView.setTextColor(evCount > 0 ? Color.parseColor("#F8FAFC") : Color.parseColor("#94A3B8"));
                            numView.setTypeface(null, evCount > 0 ? Typeface.BOLD : Typeface.NORMAL);
                        }
                        cell.addView(numView);

                        View dot = new View(context);
                        GradientDrawable dotBg = new GradientDrawable();
                        dotBg.setShape(GradientDrawable.OVAL);
                        dotBg.setColor(isSelected ? Color.WHITE : Color.parseColor("#38BDF8"));
                        dot.setBackground(dotBg);
                        dot.setVisibility((!isFuture && evCount > 0) ? View.VISIBLE : View.INVISIBLE);
                        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(s(5), s(5));
                        dotLp.topMargin = s(2);
                        cell.addView(dot, dotLp);

                        if (!isFuture) {
                            cell.setOnClickListener(v -> {
                                pendingDayRef[0] = cellKey;
                                if (!inMonth) {
                                    try {
                                        Date cd = dayKeyFmt.parse(cellKey);
                                        if (cd != null) {
                                            displayMonth.setTime(cd);
                                            displayMonth.set(Calendar.DAY_OF_MONTH, 1);
                                        }
                                    } catch (Exception ignored) {}
                                }
                                this.run();
                            });
                        }

                        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, cellH, 1.0f);
                        cellLp.leftMargin = s(2);
                        cellLp.rightMargin = s(2);
                        row.addView(cell, cellLp);
                        cursor.add(Calendar.DAY_OF_MONTH, 1);
                    }
                    gridContainer.addView(row, rowLp);
                }
            }
        };

        prevBtn.setOnClickListener(v -> {
            displayMonth.add(Calendar.MONTH, -1);
            renderMonth.run();
        });
        nextBtn.setOnClickListener(v -> {
            displayMonth.add(Calendar.MONTH, 1);
            renderMonth.run();
        });
        renderMonth.run();

        // Action footer with generous spacing between buttons
        LinearLayout actionsRow = new LinearLayout(context);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        arLp.topMargin = s(18);

        TextView allDaysBtn = buildCalendarActionButton(tr("browser_calendar_all_days"), "#1E293B", "#334155", Color.parseColor("#CBD5E1"), false);
        allDaysBtn.setOnClickListener(v -> {
            close(rootView);
            if (listener != null) listener.onDaySelected("all");
        });
        actionsRow.addView(allDaysBtn);

        actionsRow.addView(new View(context), new LinearLayout.LayoutParams(0, s(1), 1.0f));

        TextView cancelBtn = buildCalendarActionButton(tr("common_cancel"), "#1E293B", "#334155", Color.parseColor("#E2E8F0"), false);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cancelLp.rightMargin = s(16);
        cancelBtn.setOnClickListener(v -> close(rootView));
        actionsRow.addView(cancelBtn, cancelLp);

        TextView okBtn = buildCalendarActionButton(tr("common_ok"), "#8B5CF6", "#8B5CF6", Color.WHITE, true);
        okBtn.setOnClickListener(v -> {
            String chosen = pendingDayRef[0];
            close(rootView);
            if (listener != null) listener.onDaySelected(chosen);
        });
        actionsRow.addView(okBtn);

        card.addView(actionsRow, arLp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                cardWidth, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        calendarModalOverlay.addView(card, cardLp);

        rootView.addView(calendarModalOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    public void close(FrameLayout rootView) {
        if (calendarModalOverlay != null && rootView != null) {
            rootView.removeView(calendarModalOverlay);
            calendarModalOverlay = null;
        }
    }

    private TextView buildCalendarNavButton(String symbol) {
        TextView btn = new TextView(context);
        btn.setText(symbol);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(sp(20));
        btn.setTextColor(Color.parseColor("#E2E8F0"));
        btn.setTypeface(null, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1E293B"));
        bg.setCornerRadius(s(10));
        bg.setStroke(s(1), Color.parseColor("#334155"));
        btn.setBackground(bg);
        return btn;
    }

    private TextView buildCalendarActionButton(String text, String fillHex, String strokeHex,
                                               int textColor, boolean bold) {
        TextView btn = new TextView(context);
        btn.setText(text);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(sp(14));
        btn.setTextColor(textColor);
        btn.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
        btn.setPadding(s(20), s(10), s(20), s(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(fillHex));
        bg.setCornerRadius(s(10));
        bg.setStroke(s(1), Color.parseColor(strokeHex));
        btn.setBackground(bg);
        return btn;
    }
}
