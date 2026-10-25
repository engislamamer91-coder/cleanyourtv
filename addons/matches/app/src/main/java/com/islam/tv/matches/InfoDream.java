package com.islam.tv.matches;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Screensaver: the clock, the Hijri and Gregorian dates, the five prayer times for Abu Dhabi with the next one
 * highlighted and a countdown, the temperature, and the next matches. Data comes from InfoProvider's cache,
 * which this refreshes when it is stale. The whole block drifts a little every minute against burn-in.
 */
public class InfoDream extends DreamService {
    private static final String[] PRAYER_AR = {"الفجر", "الظهر", "العصر", "المغرب", "العشاء"};
    private static final long TICK_MS = 30_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Typeface font;
    private int accent;
    private TimeZone zone;
    private LinearLayout block;
    private TextView clock, dates, next, temp, status;
    private LinearLayout prayers, matches;
    private int drift;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            main.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        font = getResources().getFont(R.font.outfit);
        accent = getColor(R.color.accent);
        zone = TimeZone.getTimeZone(getString(R.string.timezone));

        FrameLayout root = new FrameLayout(this);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF06121F, 0xFF020407, 0xFF020407}));

        block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(90), dp(50), dp(90), dp(40));
        root.addView(block, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.BOTTOM);
        clock = text(96, 200, 0xFFFFFFFF);
        clock.setTextDirection(View.TEXT_DIRECTION_LTR);
        top.addView(clock);
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(36), 0, 0, dp(16));
        dates = text(22, 400, 0xB3FFFFFF);
        temp = text(22, 400, 0xB3FFFFFF);
        temp.setPadding(0, dp(6), 0, 0);
        side.addView(dates);
        side.addView(temp);
        top.addView(side);
        block.addView(top);

        next = text(34, 400, accent);
        next.setPadding(0, dp(14), 0, dp(22));
        block.addView(next);

        prayers = new LinearLayout(this);
        prayers.setOrientation(LinearLayout.HORIZONTAL);
        block.addView(prayers, new LinearLayout.LayoutParams(-1, dp(150)));

        TextView title = text(22, 500, 0xCCFFFFFF);
        title.setText("أهم المباريات القادمة");
        title.setPadding(0, dp(30), 0, dp(10));
        block.addView(title);
        matches = new LinearLayout(this);
        matches.setOrientation(LinearLayout.VERTICAL);
        block.addView(matches, new LinearLayout.LayoutParams(-1, 0, 1f));

        status = text(14, 400, 0x66FFFFFF);
        block.addView(status);

        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        main.post(tick);
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        main.removeCallbacks(tick);
    }

    private void render() {
        SharedPreferences p = getSharedPreferences("info", Context.MODE_PRIVATE);
        InfoProvider.maybeRefresh(this, p);

        Calendar now = Calendar.getInstance(zone);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);
        hm.setTimeZone(zone);
        clock.setText(hm.format(now.getTime()));
        SimpleDateFormat g = new SimpleDateFormat("EEEE d MMMM", new Locale("ar"));
        g.setTimeZone(zone);
        String hijri = p.getString("hijri", "");
        dates.setText(g.format(now.getTime()) + (hijri.isEmpty() ? "" : "  ·  " + hijri));
        String t = p.getString("temp", "");
        temp.setText(t.isEmpty() ? "أبوظبي" : "أبوظبي  " + t + "°");

        // Prayers.
        int mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int idx = -1;
        String[] times = new String[5];
        for (int i = 0; i < 5; i++) {
            times[i] = p.getString("p_" + i, "--:--");
            if (idx < 0 && minutes(times[i]) > mins) idx = i;
        }
        if (idx >= 0) {
            int left = minutes(times[idx]) - mins;
            next.setText("الصلاة القادمة: " + PRAYER_AR[idx] + "  ·  بعد "
                    + (left >= 60 ? left / 60 + " ساعة و " : "") + left % 60 + " دقيقة");
        } else {
            String fajr = p.getString("fajr_tomorrow", "");
            next.setText("الصلاة القادمة: الفجر" + (fajr.isEmpty() ? "" : " " + fajr));
            idx = 0;
            if (fajr.isEmpty()) idx = -1;
        }
        prayers.removeAllViews();
        for (int i = 0; i < 5; i++) {
            boolean hl = i == idx;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(22));
            bg.setColor(hl ? (accent & 0x00FFFFFF) | 0xCC000000 : 0x14FFFFFF);
            card.setBackground(bg);
            TextView name = text(26, 400, hl ? 0xFFFFFFFF : 0xCCFFFFFF);
            name.setText(PRAYER_AR[i]);
            name.setGravity(Gravity.CENTER);
            card.addView(name);
            TextView time = text(40, hl ? 500 : 300, 0xFFFFFFFF);
            time.setText(times[i]);
            time.setGravity(Gravity.CENTER);
            time.setTextDirection(View.TEXT_DIRECTION_LTR);
            time.setPadding(0, dp(8), 0, 0);
            card.addView(time);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            lp.setMargins(dp(8), 0, dp(8), 0);
            prayers.addView(card, lp);
        }

        // Matches.
        matches.removeAllViews();
        try {
            JSONArray rows = new JSONArray(p.getString("matches_rows", "[]"));
            for (int i = 0; i < Math.min(rows.length(), 6); i++) {
                JSONObject r = rows.getJSONObject(i);
                boolean live = "in".equals(r.optString("state"));
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(5), 0, dp(5));
                TextView when = text(18, 400, live ? 0xFFFF6B6B : 0x99FFFFFF);
                when.setText(live ? "مباشر " + r.optString("detail") : r.optString("day") + "  " + r.optString("time"));
                row.addView(when, new LinearLayout.LayoutParams(dp(220), -2));
                TextView teams = text(22, 400, 0xFFFFFFFF);
                teams.setSingleLine(true);
                teams.setText(r.optString("home") + (live ? "  " + r.optString("score") + "  " : "  ×  ") + r.optString("away"));
                row.addView(teams, new LinearLayout.LayoutParams(0, -2, 1f));
                TextView league = text(16, 400, 0x80FFFFFF);
                league.setText(r.optString("league"));
                row.addView(league);
                matches.addView(row);
            }
            if (rows.length() == 0) {
                TextView none = text(18, 400, 0x80FFFFFF);
                none.setText("جاري تحميل المباريات…");
                matches.addView(none);
            }
        } catch (Exception ignored) {
        }
        status.setText("المواقيت: Aladhan (دبي)  ·  المباريات: ESPN  ·  الطقس: Open-Meteo");

        // Drift a few pixels each tick so nothing sits still on the panel.
        drift = (drift + 1) % 6;
        block.setTranslationX(dp((drift % 3) * 6 - 6));
        block.setTranslationY(dp((drift / 3) * 8 - 4));
    }

    private static int minutes(String hhmm) {
        try {
            return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
        } catch (Exception e) {
            return -1;
        }
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(font, weight, false) : font);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
