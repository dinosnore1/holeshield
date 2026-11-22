package com.dino.holeshield;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

/** Dashboard counters and a bounded, persisted query log. */
final class Stats {

    private static final String FILE = "holeshield_stats";
    private static final String RECENT_KEY = "recent";
    private static final String LOG_DIR = "querylog";
    private static final String LOG_LEGACY = "query_log.tsv";
    private static final String HISTORY_KEY = "daily_history";
    private static final String HOURLY_KEY = "hourly_history";
    private static final int RECENT_MAX = 40;
    /** Entries kept in memory for the live chart and the recent list. */
    private static final int LOG_MAX = 2000;
    /** Upper bound for exports and web paging through the on-disk history. */
    static final int EXPORT_MAX = 20000;
    /** Keep the in-memory domain set bounded on multi-week runs. */
    private static final int DOMAIN_CAP = 5000;
    /** Rolling window for the counters and the daily chart, in days. */
    private static final int HISTORY_MAX = 30;
    /** Every hour in the rolling window, so the hourly chart never runs dry. */
    private static final int HOURLY_MAX = HISTORY_MAX * 24;
    private static final SimpleDateFormat DAY_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat HOUR_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd-HH", Locale.US);

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

    private static long queries;
    private static long blocked;
    private static long cached;
    private static long forwarded;
    private static long errors;
    private static String day = "";
    private static final ArrayDeque<Blocked> recent = new ArrayDeque<>();
    private static final ArrayDeque<Entry> log = new ArrayDeque<>();
    private static final Set<String> domains = new HashSet<>();
    private static final Set<String> clients = new HashSet<>();
    private static final Map<Integer, Integer> types = new HashMap<>();
    private static final LinkedHashMap<String, long[]> dailyHistory = new LinkedHashMap<>();
    private static final LinkedHashMap<String, long[]> hourlyHistory = new LinkedHashMap<>();
    private static long lastSave;
    private static long nextHistoryTrim;
    private static final ReverseLogReader.BoundaryCache BOUNDARY =
            new ReverseLogReader.BoundaryCache();
    private static boolean logDirty;
    private static File logDir;
    private static BufferedWriter logWriter;
    private static String logWriterDay = "";

    private Stats() { }

    private static String today() {
        return DAY_FORMAT.format(new Date());
    }

    static synchronized void load(Context c) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        String savedDay = p.getString("day", "");
        String now = today();
        recent.clear();
        log.clear();
        domains.clear();
        clients.clear();
        types.clear();
        StatsHistoryCodec.load(p.getString(HISTORY_KEY, ""), dailyHistory);
        StatsHistoryCodec.load(p.getString(HOURLY_KEY, ""), hourlyHistory);

        long savedQueries = p.getLong("queries", 0);
        long savedBlocked = p.getLong("blocked", 0);
        long savedCached = p.getLong("cached", 0);
        long savedForwarded = p.getLong("forwarded", 0);
        long savedErrors = p.getLong("errors", 0);
        if (!now.equals(savedDay)) {
            if (!savedDay.isEmpty()) {
                archiveDay(savedDay, savedQueries, savedBlocked, savedCached,
                        savedForwarded, savedErrors);
            }
            queries = 0;
            blocked = 0;
            cached = 0;
            forwarded = 0;
            errors = 0;
            day = now;
            // The counters for the new day start at zero, but the rolling
            // window keeps the archived days and the existing query log.
            openLog(c);
            loadLogTail(c);
            pruneHistory();
            save(c);
            return;
        }
        day = savedDay;
        queries = savedQueries;
        blocked = savedBlocked;
        cached = savedCached;
        forwarded = savedForwarded;
        errors = savedErrors;
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
        openLog(c);
        loadLogTail(c);
        pruneHistory();
    }

    private static void archiveDay(String key, long q, long b, long c, long f, long e) {
        if (key == null || key.isEmpty()) return;
        dailyHistory.remove(key);
        dailyHistory.put(key, new long[]{q, b, c, f, e});
        pruneHistory();
    }

    /** Drops anything older than the rolling window. */
    private static void pruneHistory() {
        String dayCutoff = dayKey(System.currentTimeMillis() - ReverseLogReader.RETENTION_MS);
        Iterator<String> days = dailyHistory.keySet().iterator();
        while (days.hasNext()) {
            if (days.next().compareTo(dayCutoff) >= 0) continue;
            days.remove();
        }
        java.util.Calendar hourCal = java.util.Calendar.getInstance();
        hourCal.add(java.util.Calendar.HOUR_OF_DAY, -(HOURLY_MAX - 1));
        String hourCutoff = HOUR_FORMAT.format(hourCal.getTime());
        Iterator<String> hours = hourlyHistory.keySet().iterator();
        while (hours.hasNext()) {
            if (hours.next().compareTo(hourCutoff) >= 0) continue;
            hours.remove();
        }
        while (hourlyHistory.size() > HOURLY_MAX) {
            Iterator<String> it = hourlyHistory.keySet().iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();
        }
    }

    /** Opens (and if needed migrates) the date-partitioned query log. */
    private static void openLog(Context c) {
        closeLogWriter(false);
        logDir = new File(c.getFilesDir(), LOG_DIR);
        File legacy = new File(c.getFilesDir(), LOG_LEGACY);
        if (!ReverseLogReader.initialize(logDir, legacy,
                System.currentTimeMillis() - ReverseLogReader.RETENTION_MS,
                Stats::parseLogLine, e -> e.at, e -> dayKey(e.at), Stats::writeLogEntry)) {
            android.util.Log.w("HoleShield", "Could not initialize query history");
            logDir = null;
            return;
        }
        pruneLogFiles(false);
    }

    /** Fills the in-memory ring from the newest entries on disk. */
    private static void loadLogTail(Context c) {
        log.clear();
        if (logDir == null) return;
        for (File file : logFilesNewestFirst()) {
            ArrayDeque<Entry> part = readNewest(file, LOG_MAX - log.size());
            if (part.isEmpty()) continue;
            for (Entry e : part) {
                log.addLast(e);
                indexLogEntry(e);
            }
            if (log.size() >= LOG_MAX) break;
        }
    }

    private static void indexLogEntry(Entry e) {
        if (!e.domain.isEmpty() && domains.size() < DOMAIN_CAP) domains.add(e.domain);
        if (!e.client.isEmpty() && clients.size() < DOMAIN_CAP) clients.add(e.client);
        types.put(e.type, types.getOrDefault(e.type, 0) + 1);
    }

    /** Appends one live query to today's (or its own day's) file. */
    private static void appendLog(Entry e) {
        if (logDir == null) return;
        String key = dayKey(e.at);
        try {
            if (logWriter == null || !key.equals(logWriterDay)) {
                boolean rollover = logWriter != null;
                closeLogWriter(false);
                logWriter = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(new File(logDir, key + ".tsv"), true),
                        StandardCharsets.UTF_8));
                logWriterDay = key;
                if (rollover) pruneLogFiles(false);
            }
            writeLogEntry(logWriter, e);
            logDirty = true;
        } catch (Exception ex) {
            closeLogWriter(false);
        }
    }

    private static void flushLog() {
        if (logWriter == null || !logDirty) return;
        try {
            logWriter.flush();
            logDirty = false;
        } catch (Exception ignored) { }
    }

    private static void closeLogWriter(boolean compressClosedDay) {
        String finished = logWriterDay;
        if (logWriter != null) {
            try {
                logWriter.close();
            } catch (Exception ignored) { }
            logWriter = null;
        }
        logWriterDay = "";
        logDirty = false;
        if (compressClosedDay && finished != null && !finished.isEmpty()
                && !finished.equals(today())) {
            compressDay(finished);
        }
    }

    private static void writeLogEntry(BufferedWriter w, Entry e) throws Exception {
        w.write(Long.toString(e.at)); w.write('\t');
        w.write(e.client); w.write('\t');
        w.write(e.domain); w.write('\t');
        w.write(Integer.toString(e.type)); w.write('\t');
        w.write(e.status); w.write('\t');
        w.write(e.upstream); w.write('\t');
        w.write(Integer.toString(e.rcode)); w.write('\t');
        w.write(Long.toString(e.ms)); w.write('\t');
        w.write(e.rule); w.write('\t');
        w.write(e.category); w.write('\t');
        w.write(e.blocked ? '1' : '0'); w.write('\t');
        w.write(e.cached ? '1' : '0'); w.write('\n');
    }

    private static Entry parseLogLine(String line) {
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

    /** Newest day first. */
    private static List<File> logFilesNewestFirst() {
        ArrayList<File> files = new ArrayList<>();
        if (logDir == null) return files;
        File[] all = logDir.listFiles();
        if (all == null) return files;
        TreeMap<String, File> byDay = new TreeMap<>(Collections.reverseOrder());
        for (File f : all) {
            String name = f.getName();
            String key = dayFromFile(name);
            if (key == null) continue;
            byDay.put(key + (name.endsWith(".gz") ? "a" : "b"), f);
        }
        files.addAll(byDay.values());
        return files;
    }

    /** Newest-first entries from one day file, capped at {@code limit}. */
    private static ArrayDeque<Entry> readNewest(File file, int limit) {
        return new ArrayDeque<>(ReverseLogReader.page(Collections.singletonList(file), 0,
                limit, System.currentTimeMillis() - ReverseLogReader.RETENTION_MS,
                Stats::parseLogLine, e -> e.at, e -> true));
    }

    private static void appendLogDirect(Entry e) {
        if (logDir == null) return;
        File file = new File(logDir, dayKey(e.at) + ".tsv");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
            writeLogEntry(w, e);
        } catch (Exception ignored) { }
    }

    private static String dayKey(long at) {
        return DAY_FORMAT.format(new Date(at));
    }

    private static String dayFromFile(String name) {
        if (name.length() < 14) return null;
        if (!name.endsWith(".tsv") && !name.endsWith(".tsv.gz")) return null;
        return name.substring(0, 10);
    }

    /** Deletes expired days and compresses closed days. */
    private static void pruneLogFiles(boolean compress) {
        if (logDir == null) return;
        File[] all = logDir.listFiles();
        if (all == null) return;
        String cutoff = dayKey(System.currentTimeMillis() - ReverseLogReader.RETENTION_MS);
        String todayKey = today();
        for (File f : all) {
            String name = f.getName();
            String key = dayFromFile(name);
            if (key == null) {
                continue;
            }
            if (key.compareTo(cutoff) < 0) {
                f.delete();
            } else if (compress && key.compareTo(todayKey) < 0 && name.endsWith(".tsv")) {
                compressDay(key);
            }
        }
    }

    private static void compressDay(String key) {
        if (logDir == null) return;
        File plain = new File(logDir, key + ".tsv");
        if (!plain.isFile()) return;
        File gz = new File(logDir, key + ".tsv.gz");
        // Never replace an existing archive: the plain file may contain only
        // later entries for this day, not a complete replacement.
        if (gz.exists()) return;
        File tmp = new File(logDir, key + ".tsv.gz.tmp");
        try (FileInputStream in = new FileInputStream(plain);
             GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(tmp))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        } catch (Exception ex) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(gz)) {
            tmp.delete();
            return;
        }
        plain.delete();
    }

    private static void deleteLogFiles() {
        if (logDir == null) return;
        File[] all = logDir.listFiles();
        if (all == null) return;
        for (File f : all) f.delete();
    }

    static synchronized void save(Context c) {
        if (c == null) return;
        StringBuilder list = new StringBuilder();
        for (Blocked b : recent) {
            list.append(b.at).append('|').append(b.name).append('\n');
        }
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("day", day)
                .putLong("queries", queries)
                .putLong("blocked", blocked)
                .putLong("cached", cached)
                .putLong("forwarded", forwarded)
                .putLong("errors", errors)
                .putString(RECENT_KEY, list.toString())
                .putString(HISTORY_KEY, StatsHistoryCodec.encode(dailyHistory))
                .putString(HOURLY_KEY, StatsHistoryCodec.encode(hourlyHistory))
                .apply();
        flushLog();
        lastSave = System.currentTimeMillis();
    }

    static synchronized void onQuery(Entry e) {
        if (e == null) return;
        String now = today();
        if (!now.equals(day)) {
            archiveDay(day, queries, blocked, cached, forwarded, errors);
            queries = 0;
            blocked = 0;
            cached = 0;
            forwarded = 0;
            errors = 0;
            day = now;
            // Keep the recent log and the domain/client sets: the counters
            // roll over 30 days, they do not restart each morning.
        }
        queries++;
        if (e.blocked) blocked++;
        if (e.cached) cached++;
        if ("FORWARDED".equals(e.status) || "ALLOWED".equals(e.status)) forwarded++;
        if ("ERROR".equals(e.status)) errors++;
        if (!e.domain.isEmpty() && domains.size() < DOMAIN_CAP) domains.add(e.domain);
        if (!e.client.isEmpty() && clients.size() < DOMAIN_CAP) clients.add(e.client);
        types.put(e.type, types.getOrDefault(e.type, 0) + 1);

        String hourKey = HOUR_FORMAT.format(new Date(e.at));
        long[] hour = hourlyHistory.get(hourKey);
        if (hour == null) {
            hour = new long[5];
            hourlyHistory.put(hourKey, hour);
        }
        hour[0]++;
        if (e.blocked) hour[1]++;
        long wallTime = System.currentTimeMillis();
        if (wallTime >= nextHistoryTrim) {
            pruneHistory();
            nextHistoryTrim = wallTime + 60000L;
        }

        log.addFirst(e);
        while (log.size() > LOG_MAX) log.removeLast();
        appendLog(e);

        if (e.blocked && !e.domain.isEmpty()) {
            long nowMs = System.currentTimeMillis();
            Iterator<Blocked> it = recent.iterator();
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

    /** Compatibility helper for callers that only know block/allow. */
    static synchronized void onQuery(boolean wasBlocked, String name) {
        onQuery(new Entry(System.currentTimeMillis(), "this device", name,
                DnsPacket.TYPE_A, wasBlocked ? "BLOCKED" : "FORWARDED",
                "local", 0, 0L, "", wasBlocked, false));
    }

    /** Totals over the rolling window, today included. */
    static long queries() { return windowSum(0); }
    static long blocked() { return windowSum(1); }
    static long cached() { return windowSum(2); }
    static long forwarded() { return windowSum(3); }
    static long errors() { return windowSum(4); }

    private static long windowSum(int index) {
        long cutoff = System.currentTimeMillis() - ReverseLogReader.RETENTION_MS;
        String firstDay;
        long sum;
        List<File> files;
        synchronized (Stats.class) {
            firstDay = dayKey(cutoff);
            sum = day.compareTo(firstDay) > 0 ? todayValue(index) : 0;
            for (Map.Entry<String, long[]> bucket : dailyHistory.entrySet()) {
                if (bucket.getKey().compareTo(firstDay) > 0) sum += bucket.getValue()[index];
            }
            if (!day.equals(firstDay) && !dailyHistory.containsKey(firstDay)) return sum;
            files = logFilesNewestFirst();
        }
        return sum + BOUNDARY.get(files, firstDay, cutoff, index,
                Stats::parseLogLine, e -> e.at, Stats::countEntry);
    }

    private static void countEntry(long[] totals, Entry e) {
        totals[0]++;
        if (e.blocked) totals[1]++;
        if (e.cached) totals[2]++;
        if ("FORWARDED".equals(e.status) || "ALLOWED".equals(e.status)) totals[3]++;
        if ("ERROR".equals(e.status)) totals[4]++;
    }

    private static long todayValue(int index) {
        switch (index) {
            case 0: return queries;
            case 1: return blocked;
            case 2: return cached;
            case 3: return forwarded;
            default: return errors;
        }
    }

    static synchronized long todayQueries() { return today().equals(day) ? queries : 0; }
    static synchronized long todayBlocked() { return today().equals(day) ? blocked : 0; }

    static synchronized double todayRate() {
        return queries == 0 || !today().equals(day) ? 0.0 : (100.0 * blocked / queries);
    }

    static double rate() {
        long q = queries();
        return q == 0 ? 0.0 : (100.0 * blocked() / q);
    }

    static synchronized int uniqueDomains() { return domains.size(); }
    static synchronized int clientCount() { return clients.size(); }

    static synchronized List<Blocked> recent() {
        return new ArrayList<>(recent);
    }

    static synchronized List<Entry> log(int limit) {
        int max = Math.max(0, Math.min(EXPORT_MAX, limit));
        long cutoff = System.currentTimeMillis() - ReverseLogReader.RETENTION_MS;
        if (max <= log.size() || logDir == null) {
            int n = Math.min(max, log.size());
            ArrayList<Entry> out = new ArrayList<>(n);
            int i = 0;
            for (Entry e : log) {
                if (e.at < cutoff) continue;
                if (i++ >= n) break;
                out.add(e);
            }
            return out;
        }
        flushLog();
        ArrayList<Entry> out = new ArrayList<>(Math.min(max, 4096));
        for (File file : logFilesNewestFirst()) {
            ArrayDeque<Entry> part = readNewest(file, max - out.size());
            out.addAll(part);
            if (out.size() >= max) break;
        }
        return out;
    }

    static List<Entry> logPage(int start, int length, java.util.function.Predicate<Entry> filter) {
        List<File> files;
        long cutoff = System.currentTimeMillis() - ReverseLogReader.RETENTION_MS;
        synchronized (Stats.class) {
            flushLog();
            files = logFilesNewestFirst();
        }
        return ReverseLogReader.page(files, start, length, cutoff,
                Stats::parseLogLine, e -> e.at, filter);
    }

    static synchronized List<Top> topDomains(boolean blockedOnly, int limit) {
        Map<String, Integer> counts = new HashMap<>();
        for (Entry e : log) {
            if (e.domain.isEmpty()) continue;
            if (blockedOnly != e.blocked) continue;
            counts.put(e.domain, counts.getOrDefault(e.domain, 0) + 1);
        }
        return sort(counts, limit);
    }

    static synchronized List<Top> topClients(int limit) {
        Map<String, Integer> counts = new HashMap<>();
        for (Entry e : log) {
            if (e.client.isEmpty()) continue;
            counts.put(e.client, counts.getOrDefault(e.client, 0) + 1);
        }
        return sort(counts, limit);
    }

    static synchronized List<Top> queryTypes() {
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<Integer, Integer> e : types.entrySet()) {
            counts.put(DnsPacket.typeName(e.getKey()), e.getValue());
        }
        return sort(counts, 16);
    }

    static synchronized List<Top> topUpstreams(int limit) {
        Map<String, Integer> counts = new HashMap<>();
        for (Entry e : log) {
            String key = e.upstream == null || e.upstream.isEmpty() ? "none" : e.upstream;
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        return sort(counts, limit);
    }

    /** Queries and blocked counts per local hour, oldest first. */
    static synchronized List<Bucket> hourly(int hours) {
        int count = Math.max(1, Math.min(HOURLY_MAX, hours));
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long current = cal.getTimeInMillis();
        long hourMs = 3600000L;
        long start = current - (count - 1) * hourMs;
        ArrayList<Bucket> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long at = start + i * hourMs;
            long[] value = hourlyHistory.get(HOUR_FORMAT.format(new Date(at)));
            out.add(new Bucket(at, value == null ? 0 : value[0],
                    value == null ? 0 : value[1]));
        }
        return out;
    }

    /** Live chart: per-minute buckets for the last {@code minutes} minutes. */
    static synchronized List<Bucket> live(int minutes) {
        int count = Math.max(1, Math.min(120, minutes));
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long current = cal.getTimeInMillis();
        long minuteMs = 60000L;
        long start = current - (count - 1) * minuteMs;
        long[] q = new long[count];
        long[] b = new long[count];
        for (Entry e : log) {
            if (e.at < start || e.at > current + minuteMs) continue;
            int index = (int) ((e.at - start) / minuteMs);
            if (index < 0 || index >= count) continue;
            q[index]++;
            if (e.blocked) b[index]++;
        }
        ArrayList<Bucket> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new Bucket(start + i * minuteMs, q[i], b[i]));
        }
        return out;
    }

    /** Daily chart for the last {@code days} days, including today. */
    static synchronized List<Bucket> daily(int days) {
        int count = Math.max(1, Math.min(HISTORY_MAX, days));
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        cal.add(java.util.Calendar.DAY_OF_YEAR, -(count - 1));
        ArrayList<Bucket> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = DAY_FORMAT.format(cal.getTime());
            long[] value;
            if (i == count - 1) {
                value = today().equals(day)
                        ? new long[]{queries, blocked} : new long[]{0, 0};
            } else {
                value = dailyHistory.get(key);
            }
            out.add(new Bucket(cal.getTimeInMillis(),
                    value == null ? 0 : value[0], value == null ? 0 : value[1]));
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        return out;
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
        Map<String, Integer> counts = new HashMap<>();
        for (Entry e : log) {
            if (!e.blocked) continue;
            String key = e.category == null || e.category.isEmpty()
                    ? "Gravity" : e.category;
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        return sort(counts, 16);
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

    /** Replaces the query log from a backup. Entries stay newest first. */
    static synchronized void importLog(Context c, JSONArray arr) {
        if (c != null) openLog(c);
        closeLogWriter(false);
        deleteLogFiles();
        log.clear();
        domains.clear();
        clients.clear();
        types.clear();
        if (arr != null) {
            ArrayList<Entry> parsed = new ArrayList<>();
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
            // The backup is newest first; write oldest first so each day file
            // stays chronological.
            for (int i = parsed.size() - 1; i >= 0; i--) {
                appendLogDirect(parsed.get(i));
            }
            for (int i = 0; i < parsed.size() && log.size() < LOG_MAX; i++) {
                Entry e = parsed.get(i);
                log.addLast(e);
                indexLogEntry(e);
            }
        }
        pruneLogFiles(true);
        logDirty = false;
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
        if (c != null) logDir = new File(c.getFilesDir(), LOG_DIR);
        closeLogWriter(false);
        deleteLogFiles();
        log.clear();
        recent.clear();
        domains.clear();
        clients.clear();
        types.clear();
        logDirty = false;
        save(c);
    }

    static synchronized void reset(Context c) {
        queries = 0;
        blocked = 0;
        cached = 0;
        forwarded = 0;
        errors = 0;
        day = today();
        recent.clear();
        log.clear();
        domains.clear();
        clients.clear();
        types.clear();
        dailyHistory.clear();
        hourlyHistory.clear();
        closeLogWriter(false);
        deleteLogFiles();
        logDirty = false;
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
