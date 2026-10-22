package com.dino.pihole;

import android.content.Context;
import android.content.SharedPreferences;

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
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pi-hole-style counters and a bounded, persisted query log. */
final class Stats {

    private static final String FILE = "pihole_tv_stats";
    private static final String RECENT_KEY = "recent";
    private static final String LOG_FILE = "query_log.tsv";
    private static final int RECENT_MAX = 40;
    private static final int LOG_MAX = 2000;
    private static final SimpleDateFormat DAY_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

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
        final boolean blocked;
        final boolean cached;
        Entry(long at, String client, String domain, int type, String status,
              String upstream, int rcode, long ms, String rule,
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
            this.blocked = blocked;
            this.cached = cached;
        }
        String typeName() {
            return DnsPacket.typeName(type);
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
    private static long lastSave;
    private static boolean logDirty;

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
        if (!now.equals(savedDay)) {
            queries = 0;
            blocked = 0;
            cached = 0;
            forwarded = 0;
            errors = 0;
            day = now;
            // Do not let yesterday's query log reappear if the service stops
            // before the first query of the new day is recorded.
            logDirty = true;
            save(c);
            return;
        }
        day = savedDay;
        queries = p.getLong("queries", 0);
        blocked = p.getLong("blocked", 0);
        cached = p.getLong("cached", 0);
        forwarded = p.getLong("forwarded", 0);
        errors = p.getLong("errors", 0);
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
        loadLog(c);
    }

    private static void loadLog(Context c) {
        File file = new File(c.getFilesDir(), LOG_FILE);
        if (!file.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length < 9) continue;
                try {
                    long at = Long.parseLong(f[0]);
                    int type = Integer.parseInt(f[3]);
                    int rcode = Integer.parseInt(f[6]);
                    long ms = Long.parseLong(f[7]);
                    boolean blockedFlag = "BLOCKED".equals(f[4]);
                    boolean cachedFlag = "CACHED".equals(f[4]);
                    Entry e = new Entry(at, f[1], f[2], type, f[4], f[5], rcode, ms,
                            f[8], blockedFlag, cachedFlag);
                    log.addLast(e);
                    domains.add(e.domain);
                    clients.add(e.client);
                    types.put(e.type, types.getOrDefault(e.type, 0) + 1);
                    if (log.size() > LOG_MAX) log.removeLast();
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
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
                .apply();
        saveLog(c);
        lastSave = System.currentTimeMillis();
    }

    private static void saveLog(Context c) {
        if (!logDirty) return;
        File tmp = new File(c.getFilesDir(), LOG_FILE + ".tmp");
        File dst = new File(c.getFilesDir(), LOG_FILE);
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            // Keep the newest entries first on disk; load reverses them back.
            for (Entry e : log) {
                w.write(Long.toString(e.at)); w.write('\t');
                w.write(e.client); w.write('\t');
                w.write(e.domain); w.write('\t');
                w.write(Integer.toString(e.type)); w.write('\t');
                w.write(e.status); w.write('\t');
                w.write(e.upstream); w.write('\t');
                w.write(Integer.toString(e.rcode)); w.write('\t');
                w.write(Long.toString(e.ms)); w.write('\t');
                w.write(e.rule); w.write('\n');
            }
        } catch (Exception e) {
            return;
        }
        if (!tmp.renameTo(dst)) {
            if (dst.exists()) dst.delete();
            if (!tmp.renameTo(dst)) return;
        }
        logDirty = false;
    }

    static synchronized void onQuery(Entry e) {
        if (e == null) return;
        String now = today();
        if (!now.equals(day)) {
            queries = 0;
            blocked = 0;
            cached = 0;
            forwarded = 0;
            errors = 0;
            day = now;
            recent.clear();
            log.clear();
            domains.clear();
            clients.clear();
            types.clear();
        }
        queries++;
        if (e.blocked) blocked++;
        if (e.cached) cached++;
        if ("FORWARDED".equals(e.status) || "ALLOWED".equals(e.status)) forwarded++;
        if ("ERROR".equals(e.status)) errors++;
        if (!e.domain.isEmpty()) domains.add(e.domain);
        if (!e.client.isEmpty()) clients.add(e.client);
        types.put(e.type, types.getOrDefault(e.type, 0) + 1);

        log.addFirst(e);
        while (log.size() > LOG_MAX) log.removeLast();
        logDirty = true;

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
        onQuery(new Entry(System.currentTimeMillis(), "this TV", name,
                DnsPacket.TYPE_A, wasBlocked ? "BLOCKED" : "FORWARDED",
                "local", 0, 0L, "", wasBlocked, false));
    }

    static synchronized long queries() { return queries; }
    static synchronized long blocked() { return blocked; }
    static synchronized long cached() { return cached; }
    static synchronized long forwarded() { return forwarded; }
    static synchronized long errors() { return errors; }

    static synchronized double rate() {
        return queries == 0 ? 0.0 : (100.0 * blocked / queries);
    }

    static synchronized int uniqueDomains() { return domains.size(); }
    static synchronized int clientCount() { return clients.size(); }

    static synchronized List<Blocked> recent() {
        return new ArrayList<>(recent);
    }

    static synchronized List<Entry> log(int limit) {
        int n = Math.min(Math.max(0, limit), log.size());
        ArrayList<Entry> out = new ArrayList<>(n);
        int i = 0;
        for (Entry e : log) {
            if (i++ >= n) break;
            out.add(e);
        }
        return out;
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
        log.clear();
        recent.clear();
        domains.clear();
        clients.clear();
        types.clear();
        logDirty = true;
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
        logDirty = true;
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
