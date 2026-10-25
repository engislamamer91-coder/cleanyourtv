package com.islam.tv.matches;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Two short lines for the launcher's Home screen, read through content://com.islam.tv.matches.info/home:
 * line1 = the next prayer in Abu Dhabi and the temperature now, line2 = today's matches (or the next ones).
 * The launcher has no network permission; this app fetches, caches in SharedPreferences, and answers from
 * the cache at once. A query only starts a background refresh when the cache is stale.
 */
public class InfoProvider extends ContentProvider {
    private static final String TAG = "MatchesInfo";
    private static final String LAT = "24.4539", LON = "54.3773", METHOD = "16";
    private static final String[] PRAYER_KEYS = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final String[] PRAYER_AR = {"الفجر", "الظهر", "العصر", "المغرب", "العشاء"};
    private static final long PRAYER_RETRY_MS = 10 * 60_000L;
    private static final long WEATHER_MS = 30 * 60_000L;
    private static final long MATCHES_MS = 10 * 60_000L;
    private static final long LIVE_MS = 2 * 60_000L;

    private static final Object LOCK = new Object();
    private static boolean refreshing;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        Context ctx = getContext();
        if (ctx == null) return null;
        SharedPreferences p = ctx.getSharedPreferences("info", Context.MODE_PRIVATE);
        maybeRefresh(ctx, p);
        MatrixCursor c = new MatrixCursor(new String[] {"line1", "line2"});
        c.addRow(new Object[] {line1(ctx, p), p.getString("matches_line", "")});
        return c;
    }

    private static TimeZone zone(Context ctx) {
        return TimeZone.getTimeZone(ctx.getString(R.string.timezone));
    }

    private static String day(Context ctx, int offset) {
        SimpleDateFormat f = new SimpleDateFormat("dd-MM-yyyy", Locale.ROOT);
        f.setTimeZone(zone(ctx));
        Calendar c = Calendar.getInstance(zone(ctx));
        c.add(Calendar.DAY_OF_MONTH, offset);
        return f.format(c.getTime());
    }

    // ---- line 1: next prayer and temperature ----------------------------------------------------------

    private static String line1(Context ctx, SharedPreferences p) {
        StringBuilder b = new StringBuilder();
        String today = day(ctx, 0);
        if (today.equals(p.getString("prayer_day", ""))) {
            Calendar now = Calendar.getInstance(zone(ctx));
            int mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            int idx = -1;
            for (int i = 0; i < PRAYER_KEYS.length; i++) {
                if (toMinutes(p.getString("p_" + i, "")) > mins) { idx = i; break; }
            }
            if (idx >= 0) {
                String t = p.getString("p_" + idx, "");
                int left = toMinutes(t) - mins;
                b.append("الصلاة القادمة: ").append(PRAYER_AR[idx]).append(' ').append(t)
                        .append(" (بعد ").append(left >= 60 ? left / 60 + " س " : "").append(left % 60).append(" د)");
            } else {
                String fajr = p.getString("fajr_tomorrow", "");
                b.append("الصلاة القادمة: الفجر").append(fajr.isEmpty() ? "" : " " + fajr);
            }
        }
        String temp = p.getString("temp", "");
        if (!temp.isEmpty() && System.currentTimeMillis() - p.getLong("temp_at", 0) < 3 * WEATHER_MS) {
            if (b.length() > 0) b.append("   ·   ");
            b.append("أبوظبي ").append(temp).append("°");
        }
        return b.toString();
    }

    private static int toMinutes(String hhmm) {
        try {
            return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
        } catch (Exception e) {
            return -1;
        }
    }

    private static String cleanTime(String v) {
        v = v.trim();
        int sp = v.indexOf(' ');
        return sp > 0 ? v.substring(0, sp) : v;
    }

    // ---- refresh -----------------------------------------------------------------------------------------

    static void maybeRefresh(Context ctx, SharedPreferences p) {
        long now = System.currentTimeMillis();
        boolean prayer = !day(ctx, 0).equals(p.getString("prayer_day", ""))
                && now - p.getLong("prayer_try", 0) > PRAYER_RETRY_MS;
        boolean weather = now - p.getLong("temp_try", 0) > WEATHER_MS;
        long matchesEvery = p.getBoolean("live", false) ? LIVE_MS : MATCHES_MS;
        boolean matches = now - p.getLong("matches_try", 0) > matchesEvery;
        if (!prayer && !weather && !matches) return;
        synchronized (LOCK) {
            if (refreshing) return;
            refreshing = true;
        }
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                if (prayer) refreshPrayer(app, p);
                if (weather) refreshWeather(p);
                if (matches) refreshMatches(app, p);
            } finally {
                synchronized (LOCK) {
                    refreshing = false;
                }
            }
        }, "info-refresh").start();
    }

    private static void refreshPrayer(Context ctx, SharedPreferences p) {
        p.edit().putLong("prayer_try", System.currentTimeMillis()).apply();
        try {
            String base = "https://api.aladhan.com/v1/timings/%s?latitude=" + LAT + "&longitude=" + LON + "&method=" + METHOD;
            JSONObject data = new JSONObject(MainActivity.fetch(String.format(base, day(ctx, 0)))).getJSONObject("data");
            JSONObject t = data.getJSONObject("timings");
            try {
                JSONObject h = data.getJSONObject("date").getJSONObject("hijri");
                p.edit().putString("hijri", h.getString("day") + " " + h.getJSONObject("month").getString("ar") + " "
                        + h.getString("year") + " هـ").apply();
            } catch (Exception ignored) {
            }
            SharedPreferences.Editor e = p.edit();
            for (int i = 0; i < PRAYER_KEYS.length; i++) e.putString("p_" + i, cleanTime(t.getString(PRAYER_KEYS[i])));
            e.putString("sunrise", cleanTime(t.optString("Sunrise", "")));
            try {
                JSONObject t2 = new JSONObject(MainActivity.fetch(String.format(base, day(ctx, 1))))
                        .getJSONObject("data").getJSONObject("timings");
                e.putString("fajr_tomorrow", cleanTime(t2.getString("Fajr")));
            } catch (Exception ignored) {
                e.putString("fajr_tomorrow", "");
            }
            e.putString("prayer_day", day(ctx, 0)).apply();
        } catch (Exception ex) {
            Log.w(TAG, "prayer: " + ex);
        }
    }

    private static void refreshWeather(SharedPreferences p) {
        p.edit().putLong("temp_try", System.currentTimeMillis()).apply();
        try {
            JSONObject cur = new JSONObject(MainActivity.fetch("https://api.open-meteo.com/v1/forecast?latitude=" + LAT
                    + "&longitude=" + LON + "&current=temperature_2m")).getJSONObject("current");
            long t = Math.round(cur.getDouble("temperature_2m"));
            p.edit().putString("temp", String.valueOf(t)).putLong("temp_at", System.currentTimeMillis()).apply();
        } catch (Exception ex) {
            Log.w(TAG, "weather: " + ex);
        }
    }

    private static void refreshMatches(Context ctx, SharedPreferences p) {
        p.edit().putLong("matches_try", System.currentTimeMillis()).apply();
        TimeZone z = zone(ctx);
        SimpleDateFormat ymd = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        ymd.setTimeZone(z);
        Calendar c = Calendar.getInstance(z);
        String from = ymd.format(c.getTime());
        c.add(Calendar.DAY_OF_MONTH, 4);
        String to = ymd.format(c.getTime());
        List<MainActivity.Match> all = new ArrayList<>();
        int ok = 0;
        for (String entry : ctx.getResources().getStringArray(R.array.leagues)) {
            String[] parts = entry.split("\\|", -1);
            String code = parts[0].trim();
            String[] teams = parts.length > 1 && !parts[1].trim().isEmpty() ? parts[1].split(",") : new String[0];
            try {
                MainActivity.parse(code, teams, new JSONObject(MainActivity.fetch(
                        "https://site.api.espn.com/apis/site/v2/sports/soccer/" + code + "/scoreboard?dates="
                                + from + "-" + to + "&limit=200")), all);
                ok++;
            } catch (Exception ex) {
                Log.w(TAG, code + ": " + ex);
            }
        }
        if (ok == 0) return;   // offline: keep the last line
        Collections.sort(all, (a, b) -> a.when.compareTo(b.when));

        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);
        hm.setTimeZone(z);
        SimpleDateFormat dayName = new SimpleDateFormat("EEEE", new Locale("ar"));
        dayName.setTimeZone(z);
        String today = ymd.format(new Date());
        Calendar t = Calendar.getInstance(z);
        t.add(Calendar.DAY_OF_MONTH, 1);
        String tomorrow = ymd.format(t.getTime());

        // Live first, then today's, then the next upcoming ones; four at most so the line stays short.
        List<MainActivity.Match> pick = new ArrayList<>();
        boolean live = false;
        for (MainActivity.Match m : all) if ("in".equals(m.state)) { pick.add(m); live = true; }
        for (MainActivity.Match m : all) {
            if (pick.size() >= 4) break;
            if (!"in".equals(m.state) && today.equals(ymd.format(m.when)) && !pick.contains(m)) pick.add(m);
        }
        for (MainActivity.Match m : all) {
            if (pick.size() >= 4) break;
            if ("pre".equals(m.state) && !pick.contains(m)) pick.add(m);
        }

        StringBuilder b = new StringBuilder();
        String lastDay = "";
        for (MainActivity.Match m : pick) {
            if (b.length() > 0) b.append("   ·   ");
            String d = ymd.format(m.when);
            if (!"in".equals(m.state) && !d.equals(lastDay)) {
                b.append(d.equals(today) ? "اليوم: " : d.equals(tomorrow) ? "بكرة: " : dayName.format(m.when) + ": ");
                lastDay = d;
            }
            if ("in".equals(m.state)) {
                b.append("مباشر ").append(m.home).append(' ').append(m.homeScore).append('-').append(m.awayScore)
                        .append(' ').append(m.away);
            } else if ("post".equals(m.state)) {
                b.append(m.home).append(' ').append(m.homeScore).append('-').append(m.awayScore).append(' ').append(m.away);
            } else {
                b.append(m.home).append(" × ").append(m.away).append(' ').append(hm.format(m.when));
            }
        }
        if (b.length() == 0) b.append("لا توجد مباريات في الأيام القادمة");
        // The next matches as rows for the screensaver: live first, then upcoming, eight at most.
        org.json.JSONArray rows = new org.json.JSONArray();
        List<MainActivity.Match> next = new ArrayList<>();
        for (MainActivity.Match m : all) if ("in".equals(m.state)) next.add(m);
        for (MainActivity.Match m : all) if ("pre".equals(m.state) && next.size() < 8) next.add(m);
        for (MainActivity.Match m : next) {
            try {
                String d = ymd.format(m.when);
                JSONObject r = new JSONObject();
                r.put("league", MainActivity.LEAGUE_AR.containsKey(m.league) ? MainActivity.LEAGUE_AR.get(m.league) : m.league);
                r.put("home", m.home).put("away", m.away).put("state", m.state);
                r.put("score", m.homeScore + " - " + m.awayScore).put("detail", m.detail == null ? "" : m.detail);
                r.put("day", d.equals(today) ? "اليوم" : d.equals(tomorrow) ? "بكرة" : dayName.format(m.when));
                r.put("time", hm.format(m.when));
                rows.put(r);
            } catch (Exception ignored) {
            }
        }
        p.edit().putString("matches_line", b.toString()).putString("matches_rows", rows.toString())
                .putBoolean("live", live).apply();
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
