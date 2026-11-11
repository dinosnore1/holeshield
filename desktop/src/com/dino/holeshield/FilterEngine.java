package com.dino.holeshield;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Desktop gravity engine: compact hashed lists, custom allow/deny rules and
 * asynchronous blocklist downloads. The matching rules mirror the Android app.
 */
final class FilterEngine {

    static final int EXACT = 0;
    static final int WILDCARD = 1;
    static final int REGEX = 2;

    private static final int MAX_DOMAINS = 1510000;
    private static final int MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024;    private static final int MAX_REGEX = 500;
    private static final String GRAVITY_FILE = "gravity.bin";
    private static final String META_FILE = "gravity.properties";
    private static final String[] META_KEYS = {
            "ads", "trackers", "malware", "adult", "piracy",
            "gambling", "dating", "social", "other"
    };
    private static final int[] CATEGORY_BITS = {
            Gravity.ADS, Gravity.TRACKERS, Gravity.MALWARE, Gravity.ADULT,
            Gravity.PIRACY, Gravity.GAMBLING, Gravity.DATING, Gravity.SOCIAL,
            Gravity.OTHER
    };

    interface Progress {
        void progress(String message, int done, int total);
    }

    static final class Rule {
        final boolean allow;
        final int kind;
        final String value;
        Rule(boolean allow, int kind, String value) {
            this.allow = allow;
            this.kind = kind;
            this.value = value;
        }
        String kindName() {
            return kind == WILDCARD ? "wildcard" : kind == REGEX ? "regex" : "exact";
        }
    }

    static final class Decision {
        final boolean blocked;
        final boolean allowed;
        final String rule;
        final String source;
        final String category;
        Decision(boolean blocked, boolean allowed, String rule, String source,
                 String category) {
            this.blocked = blocked;
            this.allowed = allowed;
            this.rule = rule;
            this.source = source;
            this.category = category == null ? "" : category;
        }
        static final Decision NONE = new Decision(false, false, "", "", "");
        static Decision block(String rule, String source, String category) {
            return new Decision(true, false, rule, source, category);
        }
        static Decision allow(String rule) {
            return new Decision(false, true, rule, "allowlist", "Allow");
        }
    }

    static final class Health {
        final String url;
        final boolean ok;
        final int count;
        final String error;
        final long at;
        Health(String url, boolean ok, int count, String error, long at) {
            this.url = url;
            this.ok = ok;
            this.count = count;
            this.error = error == null ? "" : error;
            this.at = at;
        }
    }

    private static final class Compiled {
        final Pattern pattern;
        final String source;
        Compiled(Pattern pattern, String source) {
            this.pattern = pattern;
            this.source = source;
        }
    }

    private static final class ClientRules {
        final Set<String> blocked = new HashSet<>();
        final Set<String> allowed = new HashSet<>();
        final Set<String> wildcardBlocked = new HashSet<>();
        final Set<String> wildcardAllowed = new HashSet<>();
    }

    private static final class LongBuffer {
        private long[] data;
        private int size;

        LongBuffer(int capacity) {
            data = new long[Math.max(16, capacity)];
        }

        void add(long value) {
            if (size == data.length) {
                long[] bigger = new long[data.length + (data.length >> 1) + 16];
                System.arraycopy(data, 0, bigger, 0, size);
                data = bigger;
            }
            data[size++] = value;
        }

        long[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }

    private final DesktopPrefs prefs;
    private final File dir;

    private volatile Gravity gravity = Gravity.empty();
    private volatile Set<String> exactBlocked = Collections.emptySet();
    private volatile Set<String> exactAllowed = Collections.emptySet();
    private volatile Set<String> wildcardBlocked = Collections.emptySet();
    private volatile Set<String> wildcardAllowed = Collections.emptySet();
    private volatile List<Compiled> regexBlocked = Collections.emptyList();
    private volatile List<Compiled> regexAllowed = Collections.emptyList();
    private volatile Map<String, ClientRules> clientRules = Collections.emptyMap();
    private volatile boolean blockSubdomains = true;
    private volatile int categoryMask = Gravity.ALL;
    private volatile boolean updating;
    private volatile String lastError = "";
    private volatile long lastUpdate;
    private volatile int gravityCount;
    private volatile int adsCount;
    private volatile int trackerCount;
    private volatile int malwareCount;
    private volatile int adultCount;
    private volatile int piracyCount;
    private volatile int gamblingCount;
    private volatile int datingCount;
    private volatile int socialCount;
    private volatile int otherCount;
    private volatile List<Health> health = Collections.emptyList();

    FilterEngine(DesktopPrefs prefs, File dir) {
        this.prefs = prefs;
        this.dir = dir;
    }

    synchronized void load() {
        blockSubdomains = prefs.getBool("blockSubdomains", true);
        categoryMask = prefs.categoryMask();
        readRules();
        readGravity();
        readMeta();
    }

    synchronized void reloadRules() {
        blockSubdomains = prefs.getBool("blockSubdomains", true);
        categoryMask = prefs.categoryMask();
        readRules();
    }

    void reload() {
        load();
    }

    private void readRules() {
        HashSet<String> eb = new HashSet<>(128);
        HashSet<String> ea = new HashSet<>(64);
        HashSet<String> wb = new HashSet<>(128);
        HashSet<String> wa = new HashSet<>(64);
        ArrayList<Compiled> rb = new ArrayList<>();
        ArrayList<Compiled> ra = new ArrayList<>();
        readCustom(prefs.customBlocked(), false, eb, wb, rb);
        readCustom(prefs.customAllowed(), true, ea, wa, ra);
        for (String domain : Blocklists.SERVICE_ALLOW) ea.add(domain);
        clientRules = parseClientRules(prefs.clientRules());
        exactBlocked = eb;
        exactAllowed = ea;
        wildcardBlocked = wb;
        wildcardAllowed = wa;
        regexBlocked = rb;
        regexAllowed = ra;
    }

    private static void readCustom(String text, boolean allow, Set<String> exact,
                                   Set<String> wildcard, List<Compiled> regex) {
        if (text == null) return;
        for (String raw : text.split("\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("/") && line.endsWith("/") && line.length() > 1) {
                if (regex.size() >= MAX_REGEX) continue;
                String source = line.substring(1, line.length() - 1);
                try {
                    regex.add(new Compiled(Pattern.compile(source), source));
                } catch (Exception ignored) { }
                continue;
            }
            boolean wild = line.startsWith("*.");
            if (wild) line = line.substring(2);
            String domain = HostsParser.normalizeDomain(line);
            if (domain.isEmpty()) continue;
            if (wild) wildcard.add(domain);
            else exact.add(domain);
        }
    }

    private void readGravity() {
        File file = new File(dir, GRAVITY_FILE);
        if (!file.isFile()) {
            gravity = seedGravity();
            gravityCount = gravity.size();
            adsCount = gravity.size();
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new FileInputStream(file)))) {
            byte[] magic = new byte[7];
            in.readFully(magic);
            String tag = new String(magic, StandardCharsets.US_ASCII);
            if (!"HSGRAV1".equals(tag) && !"HSGRAV2".equals(tag)) {
                gravity = seedGravity();
                gravityCount = gravity.size();
                return;
            }
            int count = in.readInt();
            if (count < 0 || count > 2_000_000) throw new IllegalArgumentException("count");
            long[] hashes = new long[count];
            for (int i = 0; i < count; i++) hashes[i] = in.readLong();
            int[] flags = new int[count];
            if ("HSGRAV1".equals(tag)) {
                byte[] old = new byte[count];
                in.readFully(old);
                for (int i = 0; i < count; i++) flags[i] = old[i] & 0xFF;
            } else {
                for (int i = 0; i < count; i++) flags[i] = in.readInt();
            }
            gravity = Gravity.fromHashes(hashes, flags);
            gravityCount = gravity.size();
        } catch (Exception e) {
            gravity = seedGravity();
            gravityCount = gravity.size();
            lastError = "Could not read the gravity database: " + e.getMessage();
        }
    }

    private Gravity seedGravity() {
        LongBuffer seed = new LongBuffer(512);
        try (InputStream in = FilterEngine.class.getResourceAsStream("/seed-blocklist.txt")) {
            if (in == null) return Gravity.empty();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String domain = HostsParser.parseLine(line);
                if (!domain.isEmpty()) seed.add(Gravity.hash(domain));
            }
        } catch (Exception ignored) { }
        long[] values = Gravity.clean(seed.toArray());
        if (values.length == 0) return Gravity.empty();
        return Gravity.build(new long[][]{values}, new int[]{Gravity.ADS}, MAX_DOMAINS);
    }

    private void readMeta() {
        File file = new File(dir, META_FILE);
        if (!file.isFile()) {
            if (gravityCount == 0) gravityCount = gravity.size();
            if (adsCount == 0) adsCount = gravity.size();
            return;
        }
        java.util.Properties meta = new java.util.Properties();
        try (InputStream in = new FileInputStream(file)) {
            meta.load(in);
            lastUpdate = parseLong(meta.getProperty("updated", "0"));
            gravityCount = (int) parseLong(meta.getProperty("total",
                    String.valueOf(gravity.size())));
            adsCount = (int) parseLong(meta.getProperty("ads", "0"));
            trackerCount = (int) parseLong(meta.getProperty("trackers", "0"));
            malwareCount = (int) parseLong(meta.getProperty("malware", "0"));
            adultCount = (int) parseLong(meta.getProperty("adult", "0"));
            piracyCount = (int) parseLong(meta.getProperty("piracy", "0"));
            gamblingCount = (int) parseLong(meta.getProperty("gambling", "0"));
            datingCount = (int) parseLong(meta.getProperty("dating", "0"));
            socialCount = (int) parseLong(meta.getProperty("social", "0"));
            otherCount = (int) parseLong(meta.getProperty("other", "0"));
            ArrayList<Health> list = new ArrayList<>();
            for (String key : meta.stringPropertyNames()) {
                if (!key.startsWith("health.")) continue;
                Health h = parseHealthLine(meta.getProperty(key));
                if (h != null) list.add(h);
            }
            list.sort((a, b) -> Long.compare(b.at, a.at));
            health = list;
        } catch (Exception ignored) { }
    }

    private int[] categoryCounts() {
        return new int[]{adsCount, trackerCount, malwareCount, adultCount,
                piracyCount, gamblingCount, datingCount, socialCount, otherCount};
    }

    private void saveMeta(int total, int[] counts, long updated, List<Health> results) {
        java.util.Properties meta = new java.util.Properties();
        meta.setProperty("updated", String.valueOf(updated));
        meta.setProperty("total", String.valueOf(total));
        for (int i = 0; i < META_KEYS.length; i++) {
            meta.setProperty(META_KEYS[i], String.valueOf(counts[i]));
        }
        int i = 0;
        for (Health h : results) {
            meta.setProperty("health." + (i++), h.url + "|" + (h.ok ? "1" : "0") + "|"
                    + h.count + "|" + h.error.replace("|", "/") + "|" + h.at);
        }
        File file = new File(dir, META_FILE);
        File temp = new File(dir, META_FILE + ".tmp");
        try (BufferedOutputStream out = new BufferedOutputStream(
                new FileOutputStream(temp))) {
            meta.store(out, "Hole Shield desktop gravity metadata");
        } catch (Exception ignored) {
            return;
        }
        try {
            java.nio.file.Files.move(temp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) { }
    }

    private void saveGravity(long[] hashes, int[] flags) {
        File file = new File(dir, GRAVITY_FILE);
        File temp = new File(dir, GRAVITY_FILE + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new FileOutputStream(temp)))) {
            out.write("HSGRAV2".getBytes(StandardCharsets.US_ASCII));
            out.writeInt(hashes.length);
            for (long hash : hashes) out.writeLong(hash);
            for (int flag : flags) out.writeInt(flag);
        } catch (Exception e) {
            lastError = "Could not save gravity: " + e.getMessage();
            return;
        }
        try {
            java.nio.file.Files.move(temp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) { }
    }

    Decision check(String host) {
        return check(host, null);
    }

    /** Full decision for one lookup. Allow rules win over block rules. */
    Decision check(String host, String client) {
        String h = normalize(host);
        if (h.isEmpty() || !h.contains(".")) return Decision.NONE;

        Decision clientDecision = checkClient(client, h);
        if (clientDecision != Decision.NONE) return clientDecision;

        String exactAllow = exactAllowed.contains(h) ? h : null;
        if (exactAllow != null) return Decision.allow(exactAllow);
        String wildAllow = findSubdomain(wildcardAllowed, h);
        if (wildAllow != null) return Decision.allow("*." + wildAllow);
        String reAllow = findRegex(regexAllowed, h);
        if (reAllow != null) return Decision.allow("/" + reAllow + "/");

        Gravity g = gravity;
        int mask = categoryMask;
        int flags = g.flagsOf(Gravity.hash(h));
        if ((flags & mask) != 0) {
            return Decision.block(h, "gravity", categoryName(flags));
        }
        if (blockSubdomains) {
            String parent = findGravitySuffix(g, mask, h);
            if (parent != null) {
                int parentFlags = g.flagsOf(Gravity.hash(parent));
                if ((parentFlags & mask) != 0) {
                    return Decision.block("*." + parent, "gravity (subdomain)",
                            categoryName(parentFlags));
                }
            }
        }
        String exactBlock = exactBlocked.contains(h) ? h : null;
        if (exactBlock != null) return Decision.block(exactBlock, "custom", "Custom");
        String wildBlock = findSubdomain(wildcardBlocked, h);
        if (wildBlock != null) return Decision.block("*." + wildBlock, "wildcard", "Custom");
        String reBlock = findRegex(regexBlocked, h);
        if (reBlock != null) return Decision.block("/" + reBlock + "/", "regex", "Custom");
        return Decision.NONE;
    }

    private Decision checkClient(String client, String host) {
        if (client == null || client.isEmpty()) return Decision.NONE;
        Map<String, ClientRules> all = clientRules;
        if (all.isEmpty()) return Decision.NONE;
        ClientRules rules = all.get(client);
        if (rules == null) return Decision.NONE;
        if (rules.allowed.contains(host)) {
            return Decision.allow("client " + client + " " + host);
        }
        String wildAllow = findSubdomain(rules.wildcardAllowed, host);
        if (wildAllow != null) return Decision.allow("client " + client + " *." + wildAllow);
        if (rules.blocked.contains(host)) {
            return Decision.block(host, "client " + client, "Client");
        }
        String wildBlock = findSubdomain(rules.wildcardBlocked, host);
        if (wildBlock != null) {
            return Decision.block("*." + wildBlock, "client " + client, "Client");
        }
        return Decision.NONE;
    }

    private static Map<String, ClientRules> parseClientRules(String text) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyMap();
        HashMap<String, ClientRules> out = new HashMap<>();
        for (String raw : text.split("\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 2) continue;
            String ip = parts[0].trim();
            if (ip.isEmpty()) continue;
            String value = parts[1].trim();
            boolean allow = value.startsWith("!");
            if (allow) value = value.substring(1);
            boolean wildcard = value.startsWith("*.");
            if (wildcard) value = value.substring(2);
            String domain = HostsParser.normalizeDomain(value);
            if (domain.isEmpty()) continue;
            ClientRules rules = out.get(ip);
            if (rules == null) {
                rules = new ClientRules();
                out.put(ip, rules);
            }
            if (allow) (wildcard ? rules.wildcardAllowed : rules.allowed).add(domain);
            else (wildcard ? rules.wildcardBlocked : rules.blocked).add(domain);
        }
        return out;
    }

    private static String findGravitySuffix(Gravity g, int mask, String host) {
        if (g.size() == 0) return null;
        String cur = host;
        while (cur.contains(".")) {
            int dot = cur.indexOf('.');
            if (dot < 0) break;
            cur = cur.substring(dot + 1);
            if (!cur.contains(".")) break;
            if ((g.flagsOf(Gravity.hash(cur)) & mask) != 0) return cur;
        }
        return null;
    }

    private static String findSubdomain(Set<String> set, String host) {
        if (set.isEmpty()) return null;
        String cur = host;
        while (cur.contains(".")) {
            int dot = cur.indexOf('.');
            if (dot < 0) break;
            cur = cur.substring(dot + 1);
            if (!cur.contains(".")) break;
            if (set.contains(cur)) return cur;
        }
        return null;
    }

    private static String findRegex(List<Compiled> list, String host) {
        for (Compiled c : list) {
            try {
                if (c.pattern.matcher(host).find()) return c.source;
            } catch (Exception ignored) { }
        }
        return null;
    }

    static String normalize(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase(Locale.US);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    static String categoryName(int flags) {
        if ((flags & Gravity.MALWARE) != 0) return "Malware";
        if ((flags & Gravity.TRACKERS) != 0) return "Trackers";
        if ((flags & Gravity.ADS) != 0) return "Ads";
        if ((flags & Gravity.ADULT) != 0) return "Adult";
        if ((flags & Gravity.OTHER) != 0) return "Other";
        return "Gravity";
    }

    // ------------------------------------------------------------- custom rules

    boolean addRule(boolean allow, int kind, String value) {
        String entry;
        if (kind == REGEX) {
            String s = value == null ? "" : value.trim();
            if (s.startsWith("/") && s.endsWith("/") && s.length() > 1) {
                s = s.substring(1, s.length() - 1);
            }
            if (s.isEmpty() || s.length() > 512) return false;
            try { Pattern.compile(s); } catch (Exception e) { return false; }
            entry = "/" + s + "/";
        } else {
            String d = HostsParser.normalizeDomain(value);
            if (d.isEmpty()) return false;
            entry = kind == WILDCARD ? "*." + d : d;
        }
        String current = allow ? prefs.customAllowed() : prefs.customBlocked();
        if (containsRule(current, entry)) return true;
        prefs.set(allow ? "customAllowed" : "customBlocked", append(current, entry));
        prefs.save();
        reloadRules();
        return true;
    }

    boolean removeRule(boolean allow, int kind, String value) {
        String entry;
        if (kind == REGEX) {
            String s = value == null ? "" : value.trim();
            if (s.startsWith("/") && s.endsWith("/") && s.length() > 1) {
                s = s.substring(1, s.length() - 1);
            }
            entry = "/" + s + "/";
        } else {
            String d = HostsParser.normalizeDomain(value);
            if (d.isEmpty()) return false;
            entry = kind == WILDCARD ? "*." + d : d;
        }
        String current = allow ? prefs.customAllowed() : prefs.customBlocked();
        String updated = removeLine(current, entry);
        if (updated.equals(current)) return false;
        prefs.set(allow ? "customAllowed" : "customBlocked", updated);
        prefs.save();
        reloadRules();
        return true;
    }

    private static boolean containsRule(String text, String entry) {
        if (text == null) return false;
        for (String line : text.split("[\\n,]+")) {
            if (line.trim().equalsIgnoreCase(entry)) return true;
        }
        return false;
    }

    private static String append(String text, String entry) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) return entry;
        return s + "\n" + entry;
    }

    private static String removeLine(String text, String entry) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.equalsIgnoreCase(entry)) continue;
            if (out.length() > 0) out.append('\n');
            out.append(s);
        }
        return out.toString();
    }

    List<Rule> rules(boolean allow) {
        ArrayList<Rule> out = new ArrayList<>();
        for (String d : allow ? exactAllowed : exactBlocked) {
            out.add(new Rule(allow, EXACT, d));
        }
        for (String d : allow ? wildcardAllowed : wildcardBlocked) {
            out.add(new Rule(allow, WILDCARD, d));
        }
        for (Compiled c : allow ? regexAllowed : regexBlocked) {
            out.add(new Rule(allow, REGEX, c.source));
        }
        return out;
    }

    // ---------------------------------------------------------------- counts

    int blockedCount() {
        return gravity.size() + exactBlocked.size() + wildcardBlocked.size()
                + regexBlocked.size();
    }

    int gravityCount() {
        return Math.max(gravityCount, gravity.size());
    }

    int allowedCount() {
        return exactAllowed.size() + wildcardAllowed.size() + regexAllowed.size();
    }

    int adsCount() { return adsCount; }
    int trackerCount() { return trackerCount; }
    int malwareCount() { return malwareCount; }
    int adultCount() { return adultCount; }
    int piracyCount() { return piracyCount; }
    int gamblingCount() { return gamblingCount; }
    int datingCount() { return datingCount; }
    int socialCount() { return socialCount; }
    int otherCount() { return otherCount; }

    long lastUpdate() { return lastUpdate; }

    boolean isUpdating() { return updating; }

    String lastError() { return lastError; }

    /** Ceiling for the merged index when the user disables the size limit. */
    private static int heapSafeLimit() {
        long heap = Runtime.getRuntime().maxMemory();
        long reserve = 256L * 1024 * 1024;
        long budget = Math.max(heap - reserve, 256L * 1024 * 1024);
        long limit = budget / 48;
        if (limit > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.max(1_000_000, limit);
    }

    List<Health> health() { return health; }

    List<String> listUrls() {
        return parseUrls(prefs.blocklistUrls());
    }

    static List<String> parseUrls(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text != null) {
            for (String raw : text.split("\\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("http://") || line.startsWith("https://")) out.add(line);
            }
        }
        return new ArrayList<>(out);
    }

    void addListUrl(String url) {
        String value = url == null ? "" : url.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) return;
        List<String> urls = listUrls();
        if (urls.contains(value)) return;
        urls.add(value);
        prefs.set("blocklistUrls", String.join("\n", urls));
        prefs.save();
    }

    void removeListUrl(int index) {
        List<String> urls = listUrls();
        if (index < 0 || index >= urls.size()) return;
        urls.remove(index);
        prefs.set("blocklistUrls", String.join("\n", urls));
        prefs.save();
    }

    // --------------------------------------------------------------- updates

    void updateBlocklists(Progress progress, Runnable done) {
        if (updating) return;
        updating = true;
        Thread worker = new Thread(() -> {
            try {
                runUpdate(progress);
            } catch (Throwable e) {
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                updating = false;
                if (done != null) done.run();
            }
        }, "gravity-update");
        worker.setDaemon(true);
        worker.start();
    }

    private void runUpdate(Progress progress) {
        lastError = "";
        // Keep the configured tier authoritative so preset updates and new
        // list sources are picked up by the next gravity refresh.
        for (String key : Blocklists.CATEGORY_KEYS) {
            if (prefs.categoryEnabled(key)
                    && Blocklists.hasAny(prefs.blocklistUrls(),
                            Blocklists.allUrls(key))) {
                prefs.set("blocklistUrls", Blocklists.applyLevel(
                        prefs.blocklistUrls(), key, prefs.categoryLevel(key)));
            }
        }
        prefs.save();
        List<String> urls = listUrls();
        if (urls.isEmpty()) {
            lastError = "Add at least one blocklist source first.";
            return;
        }
        int total = urls.size();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, total));
        AtomicInteger completed = new AtomicInteger();
        List<Future<SourceResult>> futures = new ArrayList<>();
        for (String url : urls) {
            futures.add(pool.submit((Callable<SourceResult>) () -> downloadSource(url)));
        }
        LongBuffer[] buffers = new LongBuffer[CATEGORY_BITS.length];
        for (int i = 0; i < buffers.length; i++) buffers[i] = new LongBuffer(4096);
        ArrayList<Health> results = new ArrayList<>();
        int succeeded = 0;
        try {
            for (int i = 0; i < futures.size(); i++) {
                SourceResult result = futures.get(i).get();
                completed.incrementAndGet();
                results.add(result.health);
                if (result.ok && result.hashes.length > 0) {
                    succeeded++;
                    int category = categoryIndex(Blocklists.categoryOf(result.url));
                    for (long hash : result.hashes) buffers[category].add(hash);
                }
                if (progress != null) {
                    progress.progress("Downloaded " + completed.get() + " of " + total,
                            completed.get(), total);
                }
            }
        } catch (Exception e) {
            lastError = "List download failed: " + e.getMessage();
        } finally {
            pool.shutdownNow();
        }

        if (succeeded == 0) {
            if (lastError.isEmpty()) lastError = "No blocklist source could be downloaded.";
            saveMeta(gravityCount(), categoryCounts(), lastUpdate, results);
            health = results;
            return;
        }

        // Merge the small bundled seed into the ads category. Keep it ahead of
        // the downloaded ad lists so the fair cap never truncates it.
        long[] seedHashes = new long[0];
        try (InputStream in = FilterEngine.class.getResourceAsStream("/seed-blocklist.txt")) {
            if (in != null) {
                LongBuffer seed = new LongBuffer(4096);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    String domain = HostsParser.parseLine(line);
                    if (!domain.isEmpty()) seed.add(Gravity.hash(domain));
                }
                seedHashes = seed.toArray();
            }
        } catch (Exception ignored) { }
        long[] downloadedAds = buffers[0].toArray();
        long[] adsWithSeed = new long[seedHashes.length + downloadedAds.length];
        System.arraycopy(seedHashes, 0, adsWithSeed, 0, seedHashes.length);
        System.arraycopy(downloadedAds, 0, adsWithSeed, seedHashes.length,
                downloadedAds.length);

        long[][] raw = {
                adsWithSeed, buffers[1].toArray(), buffers[2].toArray(),
                buffers[3].toArray(), buffers[4].toArray(), buffers[5].toArray(),
                buffers[6].toArray(), buffers[7].toArray(), buffers[8].toArray()
        };
        boolean capEnabled = prefs.getBool("gravityCapEnabled", true);
        int cap = prefs.getInt("gravityCap", 1510000);
        int limit = capEnabled && cap > 0 ? cap : heapSafeLimit();
        long[][] capped = capEnabled && cap > 0
                ? Gravity.fairCap(raw, cap) : Gravity.fairCap(raw, limit);
        for (int i = 0; i < capped.length; i++) {
            capped[i] = Gravity.clean(capped[i]);
        }
        long[] ads = capped[0];
        long[] trackers = capped[1];
        long[] malware = capped[2];
        long[] adult = capped[3];
        long[] piracy = capped[4];
        long[] gambling = capped[5];
        long[] dating = capped[6];
        long[] social = capped[7];
        long[] other = capped[8];

        Gravity fresh = Gravity.build(capped, CATEGORY_BITS, limit);

        long[][] merged = mergeForSave(ads, trackers, malware, adult, piracy,
                gambling, dating, social, other, limit);
        saveGravity(merged[0], toFlags(merged[0], merged[1], ads, trackers,
                malware, adult, piracy, gambling, dating, social, other));

        gravity = fresh;
        gravityCount = fresh.size();
        adsCount = ads.length;
        trackerCount = trackers.length;
        malwareCount = malware.length;
        adultCount = adult.length;
        piracyCount = piracy.length;
        gamblingCount = gambling.length;
        datingCount = dating.length;
        socialCount = social.length;
        otherCount = other.length;
        lastUpdate = System.currentTimeMillis();
        lastError = "";
        health = results;
        saveMeta(gravityCount, categoryCounts(), lastUpdate, results);
    }

    private static int categoryIndex(int category) {
        if (category == Blocklists.CATEGORY_TRACKERS) return 1;
        if (category == Blocklists.CATEGORY_MALWARE) return 2;
        if (category == Blocklists.CATEGORY_ADULT) return 3;
        if (category == Blocklists.CATEGORY_PIRACY) return 4;
        if (category == Blocklists.CATEGORY_GAMBLING) return 5;
        if (category == Blocklists.CATEGORY_DATING) return 6;
        if (category == Blocklists.CATEGORY_SOCIAL) return 7;
        if (category == Blocklists.CATEGORY_OTHER) return 8;
        return 0;
    }

    private static long[][] mergeForSave(long[] ads, long[] trackers, long[] malware,
                                         long[] adult, long[] piracy, long[] gambling,
                                         long[] dating, long[] social, long[] other,
                                         int max) {
        long[][] sources = {ads, trackers, malware, adult, piracy,
                gambling, dating, social, other};
        int total = 0;
        for (long[] source : sources) total += source.length;
        long[] hashes = new long[total];
        long[] categories = new long[total];
        int[] at = new int[sources.length];
        int written = 0;
        while (written < total) {
            long smallest = 0L;
            boolean found = false;
            for (int i = 0; i < sources.length; i++) {
                if (at[i] >= sources[i].length) continue;
                long value = sources[i][at[i]];
                if (!found || value < smallest) {
                    smallest = value;
                    found = true;
                }
            }
            if (!found) break;
            long merged = 0;
            for (int i = 0; i < sources.length; i++) {
                if (at[i] >= sources[i].length) continue;
                if (sources[i][at[i]] != smallest) continue;
                merged |= (1L << i);
                at[i]++;
                while (at[i] < sources[i].length && sources[i][at[i]] == smallest) at[i]++;
            }
            hashes[written] = smallest;
            categories[written] = merged;
            written++;
        }
        if (written < total || written > max) {
            int size = Math.min(written, max);
            hashes = Arrays.copyOf(hashes, size);
            categories = Arrays.copyOf(categories, size);
        }
        return new long[][]{hashes, categories};
    }

    private static int[] toFlags(long[] hashes, long[] categories, long[] ads,
                                 long[] trackers, long[] malware, long[] adult,
                                 long[] piracy, long[] gambling, long[] dating,
                                 long[] social, long[] other) {
        long[][] sources = {ads, trackers, malware, adult, piracy,
                gambling, dating, social, other};
        int[] flags = new int[hashes.length];
        for (int i = 0; i < hashes.length; i++) {
            int mask = 0;
            long category = categories[i];
            for (int c = 0; c < sources.length; c++) {
                if ((category & (1L << c)) != 0) mask |= CATEGORY_BITS[c];
            }
            flags[i] = mask;
        }
        return flags;
    }

    private static final class SourceResult {
        final String url;
        final boolean ok;
        final long[] hashes;
        final Health health;
        SourceResult(String url, boolean ok, long[] hashes, Health health) {
            this.url = url;
            this.ok = ok;
            this.hashes = hashes;
            this.health = health;
        }
    }

    private SourceResult downloadSource(String url) {
        long at = System.currentTimeMillis();
        LongBuffer buffer = new LongBuffer(4096);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Hole-Shield-Desktop/2.3.4");
            connection.setRequestProperty("Accept", "text/plain,*/*");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return new SourceResult(url, false, new long[0],
                        new Health(url, false, 0, "HTTP " + code, at));
            }
            InputStream raw = connection.getInputStream();
            String encoding = connection.getContentEncoding();
            if (encoding != null && encoding.toLowerCase(Locale.US).contains("gzip")) {
                raw = new GZIPInputStream(raw);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    raw, StandardCharsets.UTF_8));
            String line;
            int bytes = 0;
            while ((line = reader.readLine()) != null) {
                bytes += line.length() + 1;
                if (bytes > MAX_DOWNLOAD_BYTES) {
                    return new SourceResult(url, false, new long[0],
                            new Health(url, false, 0, "list too large", at));
                }
                String domain = HostsParser.parseLine(line);
                if (!domain.isEmpty()) buffer.add(Gravity.hash(domain));
            }
            long[] hashes = Gravity.clean(buffer.toArray());
            return new SourceResult(url, true, hashes,
                    new Health(url, true, hashes.length, "", at));
        } catch (Exception e) {
            return new SourceResult(url, false, new long[0],
                    new Health(url, false, 0,
                            e.getMessage() == null ? e.toString() : e.getMessage(), at));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Health parseHealthLine(String line) {
        if (line == null) return null;
        String[] f = line.split("\\|", -1);
        if (f.length < 5) return null;
        try {
            return new Health(f[0], "1".equals(f[1]), Integer.parseInt(f[2]),
                    f[3], Long.parseLong(f[4]));
        } catch (Exception e) {
            return null;
        }
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value.trim()); } catch (Exception e) { return 0L; }
    }
}
