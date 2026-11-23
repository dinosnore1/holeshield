package com.dino.holeshield;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Thread-safe query log, counters and chart history for the desktop app. */
final class StatsStore {

    /** Entries kept in memory for the live chart and the recent list. */
    static final int LOG_MAX = 2000;
    /** Upper bound for exports and web paging through the on-disk history. */
    static final int EXPORT_MAX = 20000;
    /** Rolling window for the counters and charts. */
    private static final int HISTORY_MAX = 30 * 24;
    private static final int DAILY_MAX = 30;
    private final SimpleDateFormat HOUR = new SimpleDateFormat("yyyy-MM-dd-HH", Locale.US);
    private final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

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
        Top(String key, int count) {
            this.key = key;
            this.count = count;
        }
    }

    private final File dir;
    private final ArrayDeque<Entry> log = new ArrayDeque<>();
    private final LinkedHashMap<String, long[]> hourly = new LinkedHashMap<>();
    private final LinkedHashMap<String, long[]> daily = new LinkedHashMap<>();
    private final HashMap<String, Integer> domainCounts = new HashMap<>();
    private final HashMap<String, Integer> clientCounts = new HashMap<>();
    private final HashMap<String, Integer> typeCounts = new HashMap<>();
    private final HashMap<String, Integer> upstreamCounts = new HashMap<>();
    private final HashMap<String, Integer> categoryCounts = new HashMap<>();

    private long legacyTotal;
    private long legacyBlocked;
    private long legacyCached;
    private long legacyForwarded;
    private long legacyErrors;
    private boolean legacySeen;
    private File logDir;
    private BufferedWriter logWriter;
    private String logWriterDay = "";
    private boolean logDirty;
    private final ReverseLogReader.BoundaryCache boundary = new ReverseLogReader.BoundaryCache();
    private boolean saveQueued;
    private ScheduledExecutorService saver;
    private static final Object WRITE_LOCK = new Object();
    private static final long SAVE_DELAY_MS = 5000L;

    StatsStore(File dir) {
        this.dir = dir;
    }

    synchronized void load() {
        AppDirs.ensureDir(dir);
        File stats = new File(dir, "stats.txt");
        int version = 1;
        if (stats.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(stats), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.split(" ", 4);
                    if (f.length < 2) continue;
                    if ("V".equals(f[0]) && f.length >= 2) {
                        version = (int) parseLong(f[1]);
                    } else if (("C".equals(f[0]) && version < 2) || "L".equals(f[0])) {
                        String[] n = f[1].split(",");
                        if (n.length >= 5) {
                            legacyTotal = parseLong(n[0]);
                            legacyBlocked = parseLong(n[1]);
                            legacyCached = parseLong(n[2]);
                            legacyForwarded = parseLong(n[3]);
                            legacyErrors = parseLong(n[4]);
                            legacySeen = true;
                        }
                    }
                }
            } catch (Exception ignored) { }
        }

        openLog();
        loadLogTail();
        rebuildFromRecords();
        trimHistory();
        if (saver == null) {
            startSaver();
            saver.scheduleWithFixedDelay(this::pruneLogFiles, 15, 15, TimeUnit.MINUTES);
        }
    }

    /**
     * Rebuilds the daily and hourly counters and the top lists from the
     * retained rows. Legacy aggregates without matching history must not
     * enter the rolling totals.
     */
    private void rebuildFromRecords() {
        HashMap<String, long[]> dayCounts = new HashMap<>();
        HashMap<String, long[]> hourCounts = new HashMap<>();
        daily.clear();
        hourly.clear();
        domainCounts.clear();
        clientCounts.clear();
        typeCounts.clear();
        upstreamCounts.clear();
        categoryCounts.clear();
        long cutoff = System.currentTimeMillis() - ReverseLogReader.RETENTION_MS;
        if (logDir != null) {
            for (File file : logFilesNewestFirst()) {
                scan(file, cutoff, e -> {
                    String dayKey = DAY.format(new Date(e.at));
                    long[] d = dayCounts.get(dayKey);
                    if (d == null) {
                        d = new long[5];
                        dayCounts.put(dayKey, d);
                    }
                    count(d, e);
                    String hourKey = HOUR.format(new Date(e.at));
                    long[] h = hourCounts.get(hourKey);
                    if (h == null) {
                        h = new long[2];
                        hourCounts.put(hourKey, h);
                    }
                    h[0]++;
                    if (e.blocked) h[1]++;
                    index(e);
                });
            }
        }
        daily.putAll(dayCounts);
        hourly.putAll(hourCounts);
    }

    private static void scan(File file, long cutoff,
                             java.util.function.Consumer<Entry> sink) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                file.getName().endsWith(".gz")
                        ? new GZIPInputStream(new FileInputStream(file))
                        : new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Entry e = parseLogLine(line);
                if (e != null && e.at >= cutoff) sink.accept(e);
            }
        } catch (Exception ignored) { }
    }

    synchronized void record(Entry entry) {
        if (Retention.expired(entry.at, System.currentTimeMillis())) return;
        log.addFirst(entry);
        while (log.size() > LOG_MAX) log.removeLast();
        index(entry);
        appendLog(entry);

        long now = entry.at;
        String hourKey = HOUR.format(new Date(now));
        long[] hour = hourly.get(hourKey);
        if (hour == null) {
            hour = new long[2];
            hourly.put(hourKey, hour);
        }
        hour[0]++;
        if (entry.blocked) hour[1]++;

        count(bucketForDay(DAY.format(new Date(now))), entry);

        queueSave();
    }

    private long[] bucketForDay(String key) {
        long[] existing = daily.get(key);
        if (existing == null) {
            existing = new long[5];
            daily.put(key, existing);
        }
        return existing;
    }

    private static void count(long[] bucket, Entry e) {
        bucket[0]++;
        if (e.blocked) bucket[1]++;
        if (e.cached) bucket[2]++;
        if (!e.blocked && !e.cached && !"ERROR".equals(e.status)) bucket[3]++;
        if ("ERROR".equals(e.status)) bucket[4]++;
    }

    /**
     * Writes soon on a background thread. Lookups call this for every query,
     * so it must never wait for the disk; bursts collapse into one write.
     */
    private void queueSave() {
        if (saveQueued) return;
        saveQueued = true;
        if (saver == null) startSaver();
        saver.schedule(this::save, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void startSaver() {
        saver = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "stats-save");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void index(Entry e) {
        if (!e.domain.isEmpty()) {
            if (domainCounts.size() < 40000) {
                domainCounts.merge(e.domain, 1, Integer::sum);
            }
        }
        if (!e.client.isEmpty()
                && (clientCounts.size() < 5000 || clientCounts.containsKey(e.client))) {
            clientCounts.merge(e.client, 1, Integer::sum);
        }
        typeCounts.merge(e.typeName(), 1, Integer::sum);
        if (!e.upstream.isEmpty()) upstreamCounts.merge(e.upstream, 1, Integer::sum);
        if (e.blocked) {
            String key = e.category == null || e.category.isEmpty() ? "Gravity" : e.category;
            categoryCounts.merge(key, 1, Integer::sum);
        }
    }

    private void trimHistory() {
        String dayCutoff = DAY.format(new Date(
                System.currentTimeMillis() - ReverseLogReader.RETENTION_MS));
        java.util.Iterator<String> days = daily.keySet().iterator();
        while (days.hasNext()) {
            if (days.next().compareTo(dayCutoff) >= 0) continue;
            days.remove();
        }

        java.util.Calendar hourCal = java.util.Calendar.getInstance();
        hourCal.add(java.util.Calendar.HOUR_OF_DAY, -(HISTORY_MAX - 1));
        String hourCutoff = HOUR.format(hourCal.getTime());
        java.util.Iterator<String> hours = hourly.keySet().iterator();
        while (hours.hasNext()) {
            if (hours.next().compareTo(hourCutoff) >= 0) continue;
            hours.remove();
        }
        while (hourly.size() > HISTORY_MAX) {
            String first = hourly.keySet().iterator().next();
            hourly.remove(first);
        }
    }

    private long windowSum(int index) {
        long cutoff = System.currentTimeMillis() - ReverseLogReader.RETENTION_MS;
        String firstDay;
        long sum = 0;
        List<File> files;
        synchronized (this) {
            firstDay = DAY.format(new Date(cutoff));
            for (Map.Entry<String, long[]> bucket : daily.entrySet()) {
                if (bucket.getKey().compareTo(firstDay) > 0) sum += bucket.getValue()[index];
            }
            if (!daily.containsKey(firstDay)) return sum;
            files = logFilesNewestFirst();
        }
        return sum + boundary.get(files, firstDay, cutoff, index,
                StatsStore::parseLogLine, e -> e.at, StatsStore::count);
    }

    long queries() { return windowSum(0); }
    long blocked() { return windowSum(1); }
    long cached() { return windowSum(2); }
    long forwarded() { return windowSum(3); }
    long errors() { return windowSum(4); }

    synchronized long todayQueries() {
        long[] value = daily.get(DAY.format(new Date()));
        return value == null ? 0 : value[0];
    }

    synchronized long todayBlocked() {
        long[] value = daily.get(DAY.format(new Date()));
        return value == null ? 0 : value[1];
    }

    synchronized int uniqueDomains() { return domainCounts.size(); }
    synchronized int clientCount() { return clientCounts.size(); }

    double rate() {
        long total = queries();
        return total == 0 ? 0.0 : blocked() * 100.0 / total;
    }

    synchronized List<Entry> log(int limit) {
        int max = Math.max(0, Math.min(EXPORT_MAX, limit));
        long cutoff = System.currentTimeMillis() - ReverseLogReader.RETENTION_MS;
        if (max <= log.size() || logDir == null) {
            ArrayList<Entry> out = new ArrayList<>(Math.min(max, log.size()));
            for (Entry e : log) {
                if (out.size() >= max) break;
                if (e.at < cutoff) continue;
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

    List<Entry> logPage(int start, int length, java.util.function.Predicate<Entry> filter) {
        List<File> files;
        long cutoff = System.currentTimeMillis() - ReverseLogReader.RETENTION_MS;
        synchronized (this) {
            flushLog();
            files = logFilesNewestFirst();
        }
        return ReverseLogReader.page(files, start, length, cutoff,
                StatsStore::parseLogLine, e -> e.at, filter);
    }

    synchronized List<Top> topDomains(boolean blockedOnly, int count) {
        HashMap<String, Integer> map = new HashMap<>();
        for (Entry e : log) {
            if (e.blocked != blockedOnly) continue;
            map.merge(e.domain, 1, Integer::sum);
        }
        return sort(map, count);
    }

    synchronized List<Top> topClients(int count) {
        return sort(clientCounts, count);
    }

    synchronized List<Top> queryTypes() {
        return sort(typeCounts, 16);
    }

    synchronized List<Top> topUpstreams(int count) {
        return sort(upstreamCounts, count);
    }

    synchronized List<Top> categoryCounts() {
        return sort(categoryCounts, 16);
    }

    private static List<Top> sort(Map<String, Integer> map, int count) {
        ArrayList<Map.Entry<String, Integer>> entries = new ArrayList<>(map.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        ArrayList<Top> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : entries) {
            if (out.size() >= Math.max(1, count)) break;
            out.add(new Top(e.getKey(), e.getValue()));
        }
        return out;
    }

    synchronized List<Bucket> chart(String range) {
        String value = range == null ? "24h" : range.toLowerCase(Locale.US);
        if ("live".equals(value)) return live(30);
        if ("week".equals(value)) return dailyBuckets(7);
        if ("month".equals(value)) return dailyBuckets(30);
        return hourlyBuckets(24);
    }

    private List<Bucket> live(int minutes) {
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

    private List<Bucket> hourlyBuckets(int hours) {
        int count = Math.max(1, Math.min(HISTORY_MAX, hours));
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        cal.add(java.util.Calendar.HOUR_OF_DAY, -(count - 1));
        ArrayList<Bucket> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = HOUR.format(cal.getTime());
            long[] value = hourly.get(key);
            out.add(new Bucket(cal.getTimeInMillis(),
                    value == null ? 0 : value[0], value == null ? 0 : value[1]));
            cal.add(java.util.Calendar.HOUR_OF_DAY, 1);
        }
        return out;
    }

    private List<Bucket> dailyBuckets(int days) {
        int count = Math.max(1, Math.min(DAILY_MAX, days));
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        cal.add(java.util.Calendar.DAY_OF_YEAR, -(count - 1));
        ArrayList<Bucket> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = DAY.format(cal.getTime());
            long[] value = daily.get(key);
            out.add(new Bucket(cal.getTimeInMillis(),
                    value == null ? 0 : value[0], value == null ? 0 : value[1]));
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        return out;
    }

    synchronized String exportCsv(boolean blockedFilter, boolean filterBlocked, String search) {
        int mode = filterBlocked ? (blockedFilter ? 1 : 2) : 0;
        return exportCsv(mode, search);
    }

    /** mode: 0 all, 1 blocked only, 2 allowed only. */
    synchronized String exportCsv(int mode, String search) {
        StringBuilder out = new StringBuilder();
        out.append("time,client,domain,type,status,upstream,rcode,ms,rule,category\n");
        String needle = search == null ? "" : search.toLowerCase(Locale.US);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        for (Entry e : log(EXPORT_MAX)) {
            if (mode == 1 && !e.blocked) continue;
            if (mode == 2 && e.blocked) continue;
            if (!needle.isEmpty() && !e.domain.toLowerCase(Locale.US).contains(needle)
                    && !e.client.toLowerCase(Locale.US).contains(needle)) continue;
            out.append(csv(fmt.format(new Date(e.at)))).append(',')
                    .append(csv(e.client)).append(',')
                    .append(csv(e.domain)).append(',')
                    .append(csv(e.typeName())).append(',')
                    .append(csv(e.status)).append(',')
                    .append(csv(e.upstream)).append(',')
                    .append(csv(DnsPacket.rcodeName(e.rcode))).append(',')
                    .append(e.ms).append(',')
                    .append(csv(e.rule)).append(',')
                    .append(csv(e.category)).append('\n');
        }
        return out.toString();
    }

    synchronized Json.Arr exportJson(boolean blockedFilter, boolean filterBlocked, String search) {
        Json.Arr arr = new Json.Arr();
        String needle = search == null ? "" : search.toLowerCase(Locale.US);
        for (Entry e : log(EXPORT_MAX)) {
            if (filterBlocked && e.blocked != blockedFilter) continue;
            if (!needle.isEmpty() && !e.domain.toLowerCase(Locale.US).contains(needle)
                    && !e.client.toLowerCase(Locale.US).contains(needle)) continue;
            Json.Obj o = new Json.Obj()
                    .put("time", e.at / 1000.0)
                    .put("client", e.client)
                    .put("domain", e.domain)
                    .put("type", e.typeName())
                    .put("status", e.status)
                    .put("upstream", e.upstream)
                    .put("rcode", DnsPacket.rcodeName(e.rcode))
                    .put("ms", e.ms)
                    .put("rule", e.rule)
                    .put("category", e.category)
                    .put("blocked", e.blocked);
            arr.add(o);
        }
        return arr;
    }

    private static String csv(String value) {
        String s = value == null ? "" : value;
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    void save() {
        String summary;
        synchronized (this) {
            saveQueued = false;
            trimHistory();
            flushLog();
            summary = summaryText();
        }
        synchronized (WRITE_LOCK) {
            AppDirs.ensureDir(dir);
            write(new File(dir, "stats.txt"), summary);
        }
    }

    private String summaryText() {
        StringBuilder out = new StringBuilder();
        out.append("V 2\n");
        // Keep undated legacy totals for reference without attributing them to today.
        if (legacySeen) {
            out.append("L ").append(legacyTotal).append(',').append(legacyBlocked).append(',')
                    .append(legacyCached).append(',').append(legacyForwarded).append(',')
                    .append(legacyErrors).append('\n');
        }
        for (Map.Entry<String, long[]> e : hourly.entrySet()) {
            out.append("H ").append(e.getKey()).append(' ')
                    .append(e.getValue()[0]).append(',').append(e.getValue()[1]).append('\n');
        }
        for (Map.Entry<String, long[]> e : daily.entrySet()) {
            long[] v = e.getValue();
            out.append("D ").append(e.getKey()).append(' ')
                    .append(v[0]).append(',').append(v[1]).append(',')
                    .append(v[2]).append(',').append(v[3]).append(',')
                    .append(v[4]).append('\n');
        }
        return out.toString();
    }

    /** Opens (and if needed migrates) the date-partitioned query log. */
    private void openLog() {
        closeLogWriter();
        logDir = new File(dir, "querylog");
        File legacy = new File(dir, "query_log.tsv");
        if (!ReverseLogReader.initialize(logDir, legacy,
                System.currentTimeMillis() - ReverseLogReader.RETENTION_MS,
                StatsStore::parseLogLine, e -> e.at, e -> DAY.format(new Date(e.at)),
                StatsStore::writeLogEntry)) {
            System.err.println("Hole Shield: could not initialize query history");
            logDir = null;
            return;
        }
        pruneLogFiles();
    }

    /** Fills the in-memory ring from the newest entries on disk. */
    private void loadLogTail() {
        log.clear();
        if (logDir == null) return;
        for (File file : logFilesNewestFirst()) {
            ArrayDeque<Entry> part = readNewest(file, LOG_MAX - log.size());
            if (part.isEmpty()) continue;
            for (Entry e : part) {
                log.addLast(e);
                index(e);
            }
            if (log.size() >= LOG_MAX) break;
        }
    }

    private void appendLog(Entry e) {
        if (logDir == null) return;
        String key = DAY.format(new Date(e.at));
        try {
            if (logWriter == null || !key.equals(logWriterDay)) {
                closeLogWriter();
                logWriter = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(new File(logDir, key + ".tsv"), true),
                        StandardCharsets.UTF_8));
                logWriterDay = key;
            }
            writeLogEntry(logWriter, e);
            logDirty = true;
        } catch (Exception ex) {
            closeLogWriter();
        }
    }

    private void flushLog() {
        if (logWriter == null || !logDirty) return;
        try {
            logWriter.flush();
            logDirty = false;
        } catch (Exception ignored) { }
    }

    private void closeLogWriter() {
        if (logWriter != null) {
            try {
                logWriter.close();
            } catch (Exception ignored) { }
            logWriter = null;
        }
        logWriterDay = "";
        logDirty = false;
    }

    private static void writeLogEntry(BufferedWriter w, Entry e) throws Exception {
        w.write(Long.toString(e.at)); w.write('\t');
        w.write(escape(e.client)); w.write('\t');
        w.write(escape(e.domain)); w.write('\t');
        w.write(Integer.toString(e.type)); w.write('\t');
        w.write(escape(e.status)); w.write('\t');
        w.write(escape(e.upstream)); w.write('\t');
        w.write(Integer.toString(e.rcode)); w.write('\t');
        w.write(Long.toString(e.ms)); w.write('\t');
        w.write(escape(e.rule)); w.write('\t');
        w.write(escape(e.category)); w.write('\t');
        w.write(e.blocked ? '1' : '0'); w.write('\t');
        w.write(e.cached ? '1' : '0'); w.write('\n');
    }

    private static Entry parseLogLine(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 12) return null;
        try {
            return new Entry(parseLong(f[0]), unescape(f[1]), unescape(f[2]),
                    (int) parseLong(f[3]), unescape(f[4]), unescape(f[5]),
                    (int) parseLong(f[6]), parseLong(f[7]), unescape(f[8]),
                    unescape(f[9]), "1".equals(f[10]), "1".equals(f[11]));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Newest day first. */
    private List<File> logFilesNewestFirst() {
        ArrayList<File> files = new ArrayList<>();
        if (logDir == null) return files;
        File[] all = logDir.listFiles();
        if (all == null) return files;
        TreeMap<String, File> byDay =
                new TreeMap<>(java.util.Collections.reverseOrder());
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
        return new ArrayDeque<>(ReverseLogReader.page(java.util.Collections.singletonList(file),
                0, limit, System.currentTimeMillis() - ReverseLogReader.RETENTION_MS,
                StatsStore::parseLogLine, e -> e.at, e -> true));
    }

    private static String dayFromFile(String name) {
        if (name.length() < 14) return null;
        if (!name.endsWith(".tsv") && !name.endsWith(".tsv.gz")) return null;
        return name.substring(0, 10);
    }

    /** Deletes expired days and trims the one partition spanning the cutoff. */
    private void pruneLogFiles() {
        List<File> files;
        synchronized (this) {
            trimHistory();
            files = logFilesNewestFirst();
        }
        long cutoff = Retention.cutoff(System.currentTimeMillis());
        String firstDay = Retention.dayKey(cutoff);
        for (File file : files) {
            String key = dayFromFile(file.getName());
            if (key.compareTo(firstDay) < 0) {
                synchronized (this) {
                    if (!key.equals(logWriterDay)) file.delete();
                }
            } else if (key.equals(firstDay)) {
                trimBoundaryFile(file, key, cutoff);
            }
        }
    }

    private void trimBoundaryFile(File file, String key, long cutoff) {
        java.nio.file.Path tmp = null;
        try {
            long length = file.length();
            boolean gzip = file.getName().endsWith(".gz");
            tmp = Files.createTempFile(file.getParentFile().toPath(), "query-trim-", ".tmp");
            boolean expired = false;
            try (FileInputStream in = new FileInputStream(file);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                         gzip ? new GZIPInputStream(in) : in, StandardCharsets.UTF_8));
                 java.io.OutputStream out = Files.newOutputStream(tmp);
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                         gzip ? new GZIPOutputStream(out) : out, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Entry entry = parseLogLine(line);
                    if (entry != null && entry.at < cutoff) {
                        expired = true;
                    } else {
                        writer.write(line);
                        writer.newLine();
                    }
                }
            }
            synchronized (this) {
                if (expired && !key.equals(logWriterDay) && file.length() == length) {
                    Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (java.io.IOException e) {
            System.err.println("Hole Shield: could not trim query partition " + file.getName());
        } finally {
            if (tmp != null) tmp.toFile().delete();
        }
    }

    private void deleteLogFiles() {
        if (logDir == null) return;
        File[] all = logDir.listFiles();
        if (all == null) return;
        for (File f : all) f.delete();
    }

    private static void write(File file, String text) {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(temp), StandardCharsets.UTF_8))) {
            w.write(text);
        } catch (Exception ignored) {
            return;
        }
        try {
            java.nio.file.Files.move(temp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                java.nio.file.Files.move(temp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) { }
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\t", "\\t")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char n = value.charAt(++i);
            if (n == 't') out.append('\t');
            else if (n == 'n') out.append('\n');
            else if (n == 'r') out.append('\r');
            else out.append(n);
        }
        return out.toString();
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value.trim()); } catch (Exception e) { return 0L; }
    }

    synchronized void clear() {
        closeLogWriter();
        deleteLogFiles();
        log.clear();
        hourly.clear();
        daily.clear();
        domainCounts.clear();
        clientCounts.clear();
        typeCounts.clear();
        upstreamCounts.clear();
        categoryCounts.clear();
        legacyTotal = legacyBlocked = legacyCached = legacyForwarded = legacyErrors = 0;
        legacySeen = false;
        save();
    }
}
