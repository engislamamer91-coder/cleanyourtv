package com.islam.tv.matches;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Football fixtures for the next few days from ESPN's public scoreboard (no account or key), in the
 * time zone from res/values/config.xml. Leagues and team filters are in config.xml too.
 */
public class MainActivity extends Activity {
    private static final String TAG = "Matches";
    private static final long REFRESH_MS = 5 * 60_000L;
    static final Map<String, String> LEAGUE_AR = new HashMap<>();
    static {
        LEAGUE_AR.put("eng.1", "الدوري الإنجليزي");
        LEAGUE_AR.put("esp.1", "الدوري الإسباني");
        LEAGUE_AR.put("uefa.champions", "دوري أبطال أوروبا");
        LEAGUE_AR.put("egy.1", "الدوري المصري");
        LEAGUE_AR.put("caf.champions", "أبطال أفريقيا");
        LEAGUE_AR.put("caf.confed", "الكونفدرالية الأفريقية");
    }
    private static final Map<String, Integer> LEAGUE_COLOR = new HashMap<>();
    static {
        LEAGUE_COLOR.put("eng.1", 0xFF8E44AD);
        LEAGUE_COLOR.put("esp.1", 0xFFE74C3C);
        LEAGUE_COLOR.put("uefa.champions", 0xFF2E86DE);
        LEAGUE_COLOR.put("egy.1", 0xFFC0392B);
        LEAGUE_COLOR.put("caf.champions", 0xFF27AE60);
        LEAGUE_COLOR.put("caf.confed", 0xFF16A085);
    }

    static final class Match {
        String league, home, away, homeScore, awayScore, state, detail;
        Date when;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Typeface font;
    private int accent;
    private TimeZone zone;
    private LinearLayout list;
    private ScrollView scroll;
    private TextView status;
    private boolean loading;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            load();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        font = getResources().getFont(R.font.outfit);
        accent = getColor(R.color.accent);
        zone = TimeZone.getTimeZone(getString(R.string.timezone));

        FrameLayout root = new FrameLayout(this);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {(accent & 0x00FFFFFF) | 0x55000000, 0xFF050607, 0xFF050607}));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(80), dp(40), dp(80), dp(28));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        TextView title = text(40, 500, 0xFFFFFFFF);
        title.setText("مواعيد المباريات");
        col.addView(title);
        TextView sub = text(18, 400, 0xB3FFFFFF);
        sub.setText("بتوقيت أبوظبي · الأيام القادمة");
        sub.setPadding(0, dp(6), 0, dp(18));
        col.addView(sub);

        scroll = new ScrollView(this);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        scroll.setVerticalScrollBarEnabled(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        status = text(15, 400, 0x80FFFFFF);
        status.setPadding(0, dp(12), 0, 0);
        status.setText("جاري التحميل…");
        col.addView(status);

        setContentView(root);
        scroll.requestFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Page through the list with the D-pad: a ScrollView with no focusable children only nudges a little.
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { scroll.smoothScrollBy(0, dp(220)); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { scroll.smoothScrollBy(0, -dp(220)); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.removeCallbacks(refresh);
        main.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(refresh);
    }

    private void load() {
        if (loading) return;
        loading = true;
        final String[] leagues = getResources().getStringArray(R.array.leagues);
        final int days = getResources().getInteger(R.integer.days);
        SimpleDateFormat ymd = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        ymd.setTimeZone(zone);
        Calendar c = Calendar.getInstance(zone);
        final String from = ymd.format(c.getTime());
        c.add(Calendar.DAY_OF_MONTH, days);
        final String to = ymd.format(c.getTime());
        new Thread(() -> {
            List<Match> all = new ArrayList<>();
            int failed = 0;
            for (String entry : leagues) {
                String[] parts = entry.split("\\|", -1);
                String code = parts[0].trim();
                String[] teams = parts.length > 1 && !parts[1].trim().isEmpty() ? parts[1].split(",") : new String[0];
                try {
                    String url = "https://site.api.espn.com/apis/site/v2/sports/soccer/" + code
                            + "/scoreboard?dates=" + from + "-" + to + "&limit=200";
                    parse(code, teams, new JSONObject(fetch(url)), all);
                } catch (Exception e) {
                    failed++;
                    Log.w(TAG, code + ": " + e);
                }
            }
            Collections.sort(all, (a, b) -> a.when.compareTo(b.when));
            final int f = failed;
            final int total = leagues.length;
            main.post(() -> {
                loading = false;
                if (f == total) {
                    status.setText("تعذّر الاتصال بالإنترنت. سيُعاد المحاولة تلقائياً.");
                    return;
                }
                render(all);
                SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);
                hm.setTimeZone(zone);
                status.setText("المصدر: ESPN · آخر تحديث " + hm.format(new Date())
                        + (f > 0 ? " · تعذّر تحميل " + f + " من البطولات" : ""));
            });
        }).start();
    }

    static void parse(String code, String[] teams, JSONObject json, List<Match> out) throws Exception {
        JSONArray events = json.optJSONArray("events");
        if (events == null) return;
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.getJSONObject(i);
            JSONArray comps = ev.optJSONArray("competitions");
            if (comps == null || comps.length() == 0) continue;
            JSONArray sides = comps.getJSONObject(0).optJSONArray("competitors");
            if (sides == null || sides.length() < 2) continue;
            Match m = new Match();
            m.league = code;
            for (int s = 0; s < sides.length(); s++) {
                JSONObject side = sides.getJSONObject(s);
                JSONObject team = side.optJSONObject("team");
                String name = team == null ? "?" : team.optString("shortDisplayName", team.optString("displayName", "?"));
                if (name.isEmpty() && team != null) name = team.optString("displayName", "?");
                String score = side.optString("score", "");
                if ("away".equals(side.optString("homeAway"))) { m.away = name; m.awayScore = score; }
                else { m.home = name; m.homeScore = score; }
            }
            if (m.home == null || m.away == null) continue;
            if (teams.length > 0) {
                boolean keep = false;
                for (String t : teams) {
                    String k = t.trim().toLowerCase(Locale.ROOT);
                    if (k.isEmpty()) continue;
                    if (m.home.toLowerCase(Locale.ROOT).contains(k) || m.away.toLowerCase(Locale.ROOT).contains(k)) keep = true;
                }
                if (!keep) continue;
            }
            m.when = parseDate(ev.optString("date", ""));
            if (m.when == null) continue;
            JSONObject st = ev.optJSONObject("status");
            JSONObject type = st == null ? null : st.optJSONObject("type");
            m.state = type == null ? "pre" : type.optString("state", "pre");
            m.detail = type == null ? "" : type.optString("shortDetail", "");
            out.add(m);
        }
    }

    static Date parseDate(String s) {
        String[] patterns = {"yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.ROOT);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                f.setLenient(false);
                return f.parse(s);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void render(List<Match> matches) {
        list.removeAllViews();
        if (matches.isEmpty()) {
            TextView none = text(24, 400, 0xB3FFFFFF);
            none.setText("لا توجد مباريات في الأيام القادمة.");
            list.addView(none);
            return;
        }
        SimpleDateFormat dayKey = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        dayKey.setTimeZone(zone);
        SimpleDateFormat dayName = new SimpleDateFormat("EEEE d MMMM", new Locale("ar"));
        dayName.setTimeZone(zone);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);
        hm.setTimeZone(zone);
        Calendar c = Calendar.getInstance(zone);
        String today = dayKey.format(c.getTime());
        c.add(Calendar.DAY_OF_MONTH, 1);
        String tomorrow = dayKey.format(c.getTime());

        String lastDay = "";
        for (Match m : matches) {
            String key = dayKey.format(m.when);
            if (!key.equals(lastDay)) {
                lastDay = key;
                TextView h = text(24, 500, accent);
                String label = key.equals(today) ? "اليوم" : key.equals(tomorrow) ? "بكرة" : "";
                String name = dayName.format(m.when);
                h.setText(label.isEmpty() ? name : label + " · " + name);
                h.setPadding(0, dp(list.getChildCount() == 0 ? 0 : 22), 0, dp(10));
                list.addView(h);
            }
            list.addView(row(m, hm));
        }
    }

    private View row(Match m, SimpleDateFormat hm) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(12), dp(20), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        boolean live = "in".equals(m.state);
        bg.setColor(live ? 0x33E74C3C : 0x14FFFFFF);
        r.setBackground(bg);

        TextView league = text(14, 500, 0xFFFFFFFF);
        league.setText(LEAGUE_AR.containsKey(m.league) ? LEAGUE_AR.get(m.league) : m.league);
        league.setGravity(Gravity.CENTER);
        league.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(12));
        Integer col = LEAGUE_COLOR.get(m.league);
        chip.setColor(col == null ? 0xFF555555 : col);
        league.setBackground(chip);
        r.addView(league, new LinearLayout.LayoutParams(dp(190), -2));

        TextView home = text(22, 400, 0xFFFFFFFF);
        home.setText(m.home);
        home.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        home.setSingleLine(true);
        r.addView(home, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView mid = text(22, 500, live ? 0xFFFF6B6B : 0xFFFFFFFF);
        mid.setGravity(Gravity.CENTER);
        if ("pre".equals(m.state)) mid.setText(hm.format(m.when));
        else mid.setText(m.homeScore + " - " + m.awayScore);
        mid.setTextDirection(View.TEXT_DIRECTION_LTR);
        r.addView(mid, new LinearLayout.LayoutParams(dp(130), -2));

        TextView away = text(22, 400, 0xFFFFFFFF);
        away.setText(m.away);
        away.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        away.setSingleLine(true);
        r.addView(away, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView state = text(15, 400, live ? 0xFFFF6B6B : 0x99FFFFFF);
        state.setGravity(Gravity.CENTER);
        state.setText("pre".equals(m.state) ? "" : "in".equals(m.state) ? "مباشر " + m.detail : "انتهت");
        r.addView(state, new LinearLayout.LayoutParams(dp(130), -2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
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
