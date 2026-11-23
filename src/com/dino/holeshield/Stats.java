package com.dino.holeshield;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.dino.holeshield.ReverseLogReader.RETENTION_MS;

/**
 * Dashboard statistics. Query history lives in {@link QueryDb}; every counter,
 * chart and page is derived from those retained rows and covers the same
 * rolling 30-day window. Only the small "recently blocked" list is cached in
 * memory, because it feeds the home screen and notification.
 */
final class Stats {

    private static final String FILE = "holeshield_stats";
    private static final String RECENT_KEY = "recent";
    static final String LOG_DIR = "querylog";
    static final String LOG_LEGACY = "query_log.tsv";
    private static final int RECENT_MAX = 40;
    /** Upper bound for exports and web paging through the on-disk history. */
    static final int EXPORT_MAX = 20000;
    private static final int HISTORY_MAX = 30;
    private static final int HOURLY_MAX = HISTORY_MAX * 24;

    private static final long TOTAL_TTL = 1000L;
    private static final long CHART_TTL = 2000L;
    private static final long TOP_TTL = 10000L;

    private static long[] windowTotals;
    private static long windowAt;
    private static long[] dayTotals;
    private static long dayAt;
    private static final Map<String, Object> cached = new HashMap<>();
    private static final Map<String, Long> cachedAt = new HashMap<>();

    static final class Blocked {
        final String name;
        long at;
        Blocked(String name, long at) { this.name = name; this.at = at; }
    }

    static final class Entry {
        final long at;
        final String client;
        final String domain;
        final int type;
        final String status;
        final String upstream;
        final int rcode;
        final long ms;
        final String rule;
        final String category;
        final boolean blocked;
        final boolean cached;
        Entry(long at, String client, String domain, int type, String status,
              String upstream, int rcode, long ms, String rule,
              boolean blocked, boolean cached) {
            this(at, client, domain, type, status, upstream, rcode, ms, rule,
                    "", blocked, cached);
        }
        Entry(long at, String client, String domain, int type, String status,
              String upstream, int rcode, long ms, String rule, String category,
              boolean blocked, boolean cached) {
            this.at = at;
            this.client = client == null ? "" : client;
            this.domain = domain == null ? "" : domain;
            this.type = type;
            this.status = status == null ? "" : status;
            this.upstream = upstream == null ? "" : upstream;
            this.rcode = rcode;
            this.ms = ms;
            this.rule = rule == null ? "" : rule;
            this.category = category == null ? "" : category;
            this.blocked = blocked;
            this.cached = cached;
        }
        String typeName() {
            return DnsPacket.typeName(type);
        }
    }

    static final class Bucket {
        final long start;
        final long queries;
        final long blocked;
        Bucket(long start, long queries, long blocked) {
            this.start = start;
            this.queries = queries;
            this.blocked = blocked;
        }
    }

    static final class Top {
        final String key;
        final int count;
        Top(String key, int count) { this.key = key; this.count = count; }
    }

    private static final ArrayDeque<Blocked> recent = new ArrayDeque<>();
    private static long lastSave;

    private Stats() { }

    static synchronized void load(Context c) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        recent.clear();
        String saved = p.getString(RECENT_KEY, "");
        if (saved != null && !saved.isEmpty()) {
            for (String line : saved.split("\n")) {
                int bar = line.indexOf('|');
                if (bar <= 0 || bar + 1 >= line.length()) continue;
                try {
                    long at = Long.parseLong(line.substring(0, bar));
                    recent.addLast(new Blocked(line.substring(bar + 1), at));
                } catch (NumberFormatException ignored) { }
                if (recent.size() >= RECENT_MAX) break;
            }
        }
        QueryDb.open(c);
        invalidate();
    }

    /** Parses one TSV row; also used when importing the older log files. */
    static Entry parseLogLine(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 9) return null;
        try {
            return new Entry(Long.parseLong(f[0]), f[1], f[2],
                    Integer.parseInt(f[3]), f[4], f[5], Integer.parseInt(f[6]),
                    Long.parseLong(f[7]), f[8], f.length >= 10 ? f[9] : "",
                    f.length >= 12 ? "1".equals(f[10]) : "BLOCKED".equals(f[4]),
                    f.length >= 12 ? "1".equals(f[11]) : "CACHED".equals(f[4]));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void invalidate() {
        windowTotals = null;
        dayTotals = null;
        cached.clear();
        cachedAt.clear();
    }

    static synchronized void save(Context c) {
        if (c == null) return;
        StringBuilder list = new StringBuilder();
        synchronized (recent) {
            for (Blocked b : recent) {
                list.append(b.at).append('|').append(b.name).append('\n');
            }
        }
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString(RECENT_KEY, list.toString())
                .apply();
        lastSave = System.currentTimeMillis();
    }

    static void onQuery(Entry e) {
        if (e == null) return;
        QueryDb.insert(e);
        if (e.blocked && !e.domain.isEmpty()) {
            long nowMs = System.currentTimeMillis();
            synchronized (recent) {
                java.util.Iterator<Blocked> it = recent.iterator();
                while (it.hasNext()) {
                    Blocked old = it.next();
                    if (old.name.equals(e.domain) && nowMs - old.at < 60000L) {
                        it.remove();
                        break;
                    }
                }
                recent.addFirst(new Blocked(e.domain, nowMs));
                while (recent.size() > RECENT_MAX) recent.removeLast();
            }
        }
    }

    /** Compatibility helper for callers that only know block/allow. */
    static void onQuery(boolean wasBlocked, String name) {
        onQuery(new Entry(System.currentTimeMillis(), "this device", name,
                DnsPacket.TYPE_A, wasBlocked ? "BLOCKED" : "FORWARDED",
                "local", 0, 0L, "", wasBlocked, false));
    }

    private static synchronized long[] window() {
        long now = System.currentTimeMillis();
        if (windowTotals == null || now - windowAt > TOTAL_TTL) {
            windowTotals = QueryDb.totals(now - RETENTION_MS);
            windowAt = now;
        }
        return windowTotals;
    }

    private static synchronized long[] todayTotals() {
        long now = System.currentTimeMillis();
        if (dayTotals == null || now - dayAt > TOTAL_TTL) {
            dayTotals = QueryDb.totals(QueryDb.startOfDay(now));
            dayAt = now;
        }
        return dayTotals;
    }

    static long queries() { return window()[0]; }
    static long blocked() { return window()[1]; }
    static long cached() { return window()[2]; }
    static long forwarded() { return window()[3]; }
    static long errors() { return window()[4]; }

    static synchronized long todayQueries() { return todayTotals()[0]; }
    static synchronized long todayBlocked() { return todayTotals()[1]; }

    static synchronized double todayRate() {
        long[] t = todayTotals();
        return t[0] == 0 ? 0.0 : (100.0 * t[1] / t[0]);
    }

    static double rate() {
        long[] t = window();
        return t[0] == 0 ? 0.0 : (100.0 * t[1] / t[0]);
    }

    static synchronized int uniqueDomains() {
        return cache("unique:domain", TOP_TTL,
                () -> Collections.singletonList(new Top("", QueryDb.unique(
                        "domain", System.currentTimeMillis() - RETENTION_MS)))).get(0).count;
    }

    static synchronized int clientCount() {
        return cache("unique:client", TOP_TTL,
                () -> Collections.singletonList(new Top("", QueryDb.unique(
                        "client", System.currentTimeMillis() - RETENTION_MS)))).get(0).count;
    }

    static synchronized List<Blocked> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    static synchronized List<Entry> log(int limit) {
        int max = Math.max(0, Math.min(EXPORT_MAX, limit));
        return QueryDb.page(0, max, null);
    }

    static List<Entry> logPage(int start, int length, java.util.function.Predicate<Entry> filter) {
        return QueryDb.page(start, length, filter);
    }

    private static long topSince() {
        return System.currentTimeMillis() - RETENTION_MS;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> cache(String key, long ttl, java.util.function.Supplier<List<T>> load) {
        long now = System.currentTimeMillis();
        Long at = cachedAt.get(key);
        if (at != null && now - at <= ttl && cached.containsKey(key)) {
            return (List<T>) cached.get(key);
        }
        List<T> value = load.get();
        cached.put(key, value);
        cachedAt.put(key, now);
        return value;
    }

    static synchronized List<Top> topDomains(boolean blockedOnly, int limit) {
        return cache("domains:" + blockedOnly + ":" + limit, TOP_TTL, () -> QueryDb.top(
                "domain", "domain <> '' AND blocked = " + (blockedOnly ? "1" : "0"),
                topSince(), limit));
    }

    static synchronized List<Top> topClients(int limit) {
        return cache("clients:" + limit, TOP_TTL, () -> QueryDb.top(
                "client", "client <> ''", topSince(), limit));
    }

    static synchronized List<Top> queryTypes() {
        return cache("types", TOP_TTL, () -> {
            Map<String, Integer> counts = new HashMap<>();
            for (Top t : QueryDb.top("type", "1=1", topSince(), 64)) {
                try {
                    counts.merge(DnsPacket.typeName(Integer.parseInt(t.key)), t.count, Integer::sum);
                } catch (NumberFormatException ignored) { }
            }
            return sort(counts, 16);
        });
    }

    static synchronized List<Top> topUpstreams(int limit) {
        return cache("upstreams:" + limit, TOP_TTL, () -> QueryDb.top(
                "CASE WHEN upstream='' THEN 'none' ELSE upstream END", "1=1",
                topSince(), limit));
    }

    /** Queries and blocked counts per local hour, oldest first. */
    static synchronized List<Bucket> hourly(int hours) {
        int count = Math.max(1, Math.min(HOURLY_MAX, hours));
        return cache("hourly:" + count, CHART_TTL, () -> QueryDb.hourly(count));
    }

    /** Live chart: per-minute buckets for the last {@code minutes} minutes. */
    static synchronized List<Bucket> live(int minutes) {
        int count = Math.max(1, Math.min(120, minutes));
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long current = cal.getTimeInMillis();
        long start = current - (count - 1) * 60000L;
        return cache("live:" + current, 500L, () -> QueryDb.live(start, count));
    }

    /** Daily chart for the last {@code days} days, including today. */
    static synchronized List<Bucket> daily(int days) {
        int count = Math.max(1, Math.min(HISTORY_MAX, days));
        return cache("daily:" + count, CHART_TTL, () -> QueryDb.daily(count));
    }

    /** Chart buckets for the dashboard range selector. */
    static synchronized List<Bucket> chart(String range) {
        String value = range == null ? "24h" : range.toLowerCase(Locale.US);
        if ("live".equals(value)) return live(30);
        if ("week".equals(value)) return daily(7);
        if ("month".equals(value)) return daily(30);
        return hourly(24);
    }

    /** Blocked queries grouped by the category that matched. */
    static synchronized List<Top> categoryCounts() {
        return cache("categories", TOP_TTL, () -> QueryDb.top(
                "CASE WHEN category='' THEN 'Gravity' ELSE category END",
                "blocked = 1", topSince(), 16));
    }

    /** Newest-first query log as JSON, for backup/export. */
    static synchronized JSONArray exportLog() throws Exception {
        JSONArray arr = new JSONArray();
        for (Entry e : log(EXPORT_MAX)) {
            JSONObject o = new JSONObject();
            o.put("at", e.at);
            o.put("client", e.client);
            o.put("domain", e.domain);
            o.put("type", e.type);
            o.put("status", e.status);
            o.put("upstream", e.upstream);
            o.put("rcode", e.rcode);
            o.put("ms", e.ms);
            o.put("rule", e.rule);
            o.put("category", e.category);
            o.put("blocked", e.blocked);
            o.put("cached", e.cached);
            arr.put(o);
        }
        return arr;
    }

    /** Replaces the query history from a backup. Entries stay newest first. */
    static synchronized void importLog(Context c, JSONArray arr) {
        if (c != null) QueryDb.open(c);
        ArrayList<Entry> parsed = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                parsed.add(new Entry(o.optLong("at", System.currentTimeMillis()),
                        o.optString("client", ""), o.optString("domain", ""),
                        o.optInt("type", DnsPacket.TYPE_A),
                        o.optString("status", "FORWARDED"),
                        o.optString("upstream", ""), o.optInt("rcode", 0),
                        o.optLong("ms", 0L), o.optString("rule", ""),
                        o.optString("category", ""),
                        o.optBoolean("blocked", false),
                        o.optBoolean("cached", false)));
            }
        }
        QueryDb.replaceAll(parsed);
        recent.clear();
        invalidate();
        save(c);
    }

    private static List<Top> sort(Map<String, Integer> counts, int limit) {
        ArrayList<Top> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            list.add(new Top(e.getKey(), e.getValue()));
        }
        Collections.sort(list, new Comparator<Top>() {
            @Override public int compare(Top a, Top b) {
                if (a.count != b.count) return b.count - a.count;
                return a.key.compareTo(b.key);
            }
        });
        if (limit > 0 && list.size() > limit) {
            return new ArrayList<>(list.subList(0, limit));
        }
        return list;
    }

    static synchronized void clearLog(Context c) {
        if (c != null) QueryDb.open(c);
        QueryDb.deleteAll();
        recent.clear();
        invalidate();
        save(c);
    }

    static synchronized void reset(Context c) {
        if (c != null) QueryDb.open(c);
        QueryDb.deleteAll();
        recent.clear();
        invalidate();
        save(c);
    }

    static synchronized boolean maybeSave(Context c) {
        if (System.currentTimeMillis() - lastSave > 30000L) {
            save(c);
            return true;
        }
        return false;
    }
}
