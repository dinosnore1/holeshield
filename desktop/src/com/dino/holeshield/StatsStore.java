package com.dino.holeshield;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Thread-safe query log, counters and chart history for the desktop app. */
final class StatsStore {

    static final int LOG_MAX = 2000;
    private static final int HISTORY_MAX = 72;
    private static final int DAILY_MAX = 40;
    private static final SimpleDateFormat HOUR = new SimpleDateFormat("yyyy-MM-dd-HH", Locale.US);
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

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

    private long total;
    private long blocked;
    private long cached;
    private long forwarded;
    private long errors;
    private int dirty;
    private long lastSave;

    StatsStore(File dir) {
        this.dir = dir;
    }

    synchronized void load() {
        AppDirs.ensureDir(dir);
        File stats = new File(dir, "stats.txt");
        if (stats.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(stats), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.split(" ", 4);
                    if (f.length < 2) continue;
                    if ("C".equals(f[0]) && f.length >= 2) {
                        String[] n = f[1].split(",");
                        if (n.length >= 5) {
                            total = parseLong(n[0]);
                            blocked = parseLong(n[1]);
                            cached = parseLong(n[2]);
                            forwarded = parseLong(n[3]);
                            errors = parseLong(n[4]);
                        }
                    } else if ("H".equals(f[0]) && f.length >= 3) {
                        String[] n = f[2].split(",");
                        if (n.length >= 2) {
                            hourly.put(f[1], new long[]{parseLong(n[0]), parseLong(n[1])});
                        }
                    } else if ("D".equals(f[0]) && f.length >= 3) {
                        String[] n = f[2].split(",");
                        if (n.length >= 2) {
                            daily.put(f[1], new long[]{parseLong(n[0]), parseLong(n[1])});
                        }
                    }
                }
            } catch (Exception ignored) { }
        }

        File logFile = new File(dir, "query_log.tsv");
        if (logFile.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(logFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null && log.size() < LOG_MAX) {
                    String[] f = line.split("\t", -1);
                    if (f.length < 12) continue;
                    Entry e = new Entry(parseLong(f[0]), unescape(f[1]), unescape(f[2]),
                            (int) parseLong(f[3]), unescape(f[4]), unescape(f[5]),
                            (int) parseLong(f[6]), parseLong(f[7]), unescape(f[8]),
                            unescape(f[9]), "1".equals(f[10]), "1".equals(f[11]));
                    log.addLast(e);
                    index(e);
                }
            } catch (Exception ignored) { }
        }
        if (total == 0 && !log.isEmpty()) {
            // Recover counters if an older build left a log without totals.
            for (Entry e : log) {
                total++;
                if (e.blocked) blocked++;
                if (e.cached) cached++;
                if ("ERROR".equals(e.status)) errors++;
                if (!e.blocked && !e.cached && !"ERROR".equals(e.status)) forwarded++;
            }
        }
        trimHistory();
        lastSave = System.currentTimeMillis();
    }

    synchronized void record(Entry entry) {
        log.addFirst(entry);
        while (log.size() > LOG_MAX) log.removeLast();
        index(entry);
        total++;
        if (entry.blocked) blocked++;
        if (entry.cached) cached++;
        if ("ERROR".equals(entry.status)) errors++;
        if (!entry.blocked && !entry.cached && !"ERROR".equals(entry.status)) forwarded++;

        long now = entry.at;
        String hourKey = HOUR.format(new Date(now));
        long[] hour = hourly.get(hourKey);
        if (hour == null) {
            hour = new long[2];
            hourly.put(hourKey, hour);
        }
        hour[0]++;
        if (entry.blocked) hour[1]++;

        String dayKey = DAY.format(new Date(now));
        long[] day = daily.get(dayKey);
        if (day == null) {
            day = new long[2];
            daily.put(dayKey, day);
        }
        day[0]++;
        if (entry.blocked) day[1]++;

        dirty++;
        long elapsed = System.currentTimeMillis() - lastSave;
        if (dirty >= 40 || elapsed > 60000L) save();
    }

    private void index(Entry e) {
        if (!e.domain.isEmpty()) {
            if (domainCounts.size() < 40000) {
                domainCounts.merge(e.domain, 1, Integer::sum);
            }
        }
        if (!e.client.isEmpty()) clientCounts.merge(e.client, 1, Integer::sum);
        typeCounts.merge(e.typeName(), 1, Integer::sum);
        if (!e.upstream.isEmpty()) upstreamCounts.merge(e.upstream, 1, Integer::sum);
        if (e.blocked) {
            String key = e.category == null || e.category.isEmpty() ? "Gravity" : e.category;
            categoryCounts.merge(key, 1, Integer::sum);
        }
    }

    private void trimHistory() {
        while (hourly.size() > HISTORY_MAX) {
            String first = hourly.keySet().iterator().next();
            hourly.remove(first);
        }
        while (daily.size() > DAILY_MAX) {
            String first = daily.keySet().iterator().next();
            daily.remove(first);
        }
    }

    synchronized long queries() { return total; }
    synchronized long blocked() { return blocked; }
    synchronized long cached() { return cached; }
    synchronized long forwarded() { return forwarded; }
    synchronized long errors() { return errors; }
    synchronized int uniqueDomains() { return domainCounts.size(); }
    synchronized int clientCount() { return clientCounts.size(); }

    synchronized double rate() {
        return total == 0 ? 0.0 : blocked * 100.0 / total;
    }

    synchronized List<Entry> log(int limit) {
        int max = Math.max(1, Math.min(LOG_MAX, limit));
        ArrayList<Entry> out = new ArrayList<>(Math.min(max, log.size()));
        for (Entry e : log) {
            if (out.size() >= max) break;
            out.add(e);
        }
        return out;
    }

    synchronized List<Entry> all() {
        return new ArrayList<>(log);
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
        for (Entry e : log) {
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
        for (Entry e : log) {
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

    synchronized void save() {
        AppDirs.ensureDir(dir);
        StringBuilder out = new StringBuilder();
        out.append("V 1\n");
        out.append("C ").append(total).append(',').append(blocked).append(',')
                .append(cached).append(',').append(forwarded).append(',')
                .append(errors).append('\n');
        for (Map.Entry<String, long[]> e : hourly.entrySet()) {
            out.append("H ").append(e.getKey()).append(' ')
                    .append(e.getValue()[0]).append(',').append(e.getValue()[1]).append('\n');
        }
        for (Map.Entry<String, long[]> e : daily.entrySet()) {
            out.append("D ").append(e.getKey()).append(' ')
                    .append(e.getValue()[0]).append(',').append(e.getValue()[1]).append('\n');
        }
        write(new File(dir, "stats.txt"), out.toString());

        StringBuilder logText = new StringBuilder();
        for (Entry e : log) {
            logText.append(e.at).append('\t')
                    .append(escape(e.client)).append('\t')
                    .append(escape(e.domain)).append('\t')
                    .append(e.type).append('\t')
                    .append(escape(e.status)).append('\t')
                    .append(escape(e.upstream)).append('\t')
                    .append(e.rcode).append('\t')
                    .append(e.ms).append('\t')
                    .append(escape(e.rule)).append('\t')
                    .append(escape(e.category)).append('\t')
                    .append(e.blocked ? '1' : '0').append('\t')
                    .append(e.cached ? '1' : '0').append('\n');
        }
        write(new File(dir, "query_log.tsv"), logText.toString());
        dirty = 0;
        lastSave = System.currentTimeMillis();
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
        log.clear();
        hourly.clear();
        daily.clear();
        domainCounts.clear();
        clientCounts.clear();
        typeCounts.clear();
        upstreamCounts.clear();
        categoryCounts.clear();
        total = blocked = cached = forwarded = errors = 0;
        save();
    }
}
