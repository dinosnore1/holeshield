package com.dino.holeshield;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gravity engine with compact hashed lists.
 *
 * Gravity lists are kept as compact 64-bit hashes with a category bitmask, so
 * a 1.51M-domain list uses about 18 MB and categories can be
 * switched at runtime without rebuilding anything. Subdomains are matched too
 * when the "block subdomains" setting is on. Custom rules can be exact,
 * wildcard ("*.example.com") or regular expressions. Allow rules are checked
 * before block rules, and per-client rules are checked before global ones.
 */
final class Blocklist {

    static final int EXACT = 0;
    static final int WILDCARD = 1;
    static final int REGEX = 2;

    private static final String LEGACY_FILE = "blocklist_downloaded.txt";
    private static final String ADS_FILE = "blocklist_ads.txt";
    private static final String TRACKER_FILE = "blocklist_trackers.txt";
    private static final String MALWARE_FILE = "blocklist_malware.txt";
    private static final String ADULT_FILE = "blocklist_adult.txt";
    private static final String PIRACY_FILE = "blocklist_piracy.txt";
    private static final String GAMBLING_FILE = "blocklist_gambling.txt";
    private static final String DATING_FILE = "blocklist_dating.txt";
    private static final String SOCIAL_FILE = "blocklist_social.txt";
    private static final String OTHER_FILE = "blocklist_other.txt";
    private static final int MAX_DOWNLOAD_BYTES = 128 * 1024 * 1024;
    private static final int MAX_REGEX = 500;

    interface Done {
        void run(boolean ok, String message);
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
            long[] out = new long[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }

    /** Open-addressing set of 64-bit hashes; far smaller than a HashSet of strings. */
    private static final class LongSet {
        private static final long EMPTY = 0L;
        private long[] keys;
        private int size;
        private int mask;

        LongSet(int expected) {
            int cap = 8;
            while (cap < expected * 2) cap <<= 1;
            keys = new long[cap];
            mask = cap - 1;
        }

        private static long mix(long h) {
            h ^= (h >>> 33);
            h *= 0xff51afd7ed558ccdL;
            h ^= (h >>> 33);
            h *= 0xc4ceb9fe1a85ec53L;
            h ^= (h >>> 33);
            return h;
        }

        /** @return true if value was newly added, false if already present. */
        boolean add(long value) {
            if (value == EMPTY) value = 0x9e3779b97f4a7c15L;
            if (size * 2 >= keys.length) grow();
            int i = (int) (mix(value) & mask);
            while (true) {
                long k = keys[i];
                if (k == EMPTY) {
                    keys[i] = value;
                    size++;
                    return true;
                }
                if (k == value) return false;
                i = (i + 1) & mask;
            }
        }

        int size() {
            return size;
        }

        private void grow() {
            long[] old = keys;
            keys = new long[old.length << 1];
            mask = keys.length - 1;
            size = 0;
            for (long v : old) {
                if (v != EMPTY) insert(v);
            }
        }

        private void insert(long value) {
            int i = (int) (mix(value) & mask);
            while (keys[i] != EMPTY) i = (i + 1) & mask;
            keys[i] = value;
            size++;
        }
    }

    private static volatile Gravity gravity = Gravity.empty();
    private static volatile Set<String> exactBlocked = Collections.emptySet();
    private static volatile Set<String> exactAllowed = Collections.emptySet();
    private static volatile Set<String> wildcardBlocked = Collections.emptySet();
    private static volatile Set<String> wildcardAllowed = Collections.emptySet();
    private static volatile List<Compiled> regexBlocked = Collections.emptyList();
    private static volatile List<Compiled> regexAllowed = Collections.emptyList();
    private static volatile Map<String, ClientRules> clientRules = Collections.emptyMap();
    private static volatile boolean blockSubdomains = true;
    private static volatile int categoryMask = Gravity.ALL;
    /** Entries in {@link #gravity} that pass {@link #categoryMask}. */
    private static volatile int activeGravityCount;
    /**
     * Active entries counted once each under their primary category. The order
     * matches {@link #categoryName(int)} so a domain is counted under the same
     * category the query log shows.
     */
    private static final int[] CATEGORY_PRIORITY = {
            Gravity.MALWARE, Gravity.TRACKERS, Gravity.ADS, Gravity.ADULT,
            Gravity.PIRACY, Gravity.GAMBLING, Gravity.DATING, Gravity.SOCIAL,
            Gravity.OTHER
    };
    private static final String[] CATEGORY_PRIORITY_KEYS = {
            "malware", "trackers", "ads", "adult",
            "piracy", "gambling", "dating", "social", "other"
    };
    private static volatile int[] categoryCounts = new int[CATEGORY_PRIORITY.length];
    private static volatile boolean updating = false;
    private static final Object RELOAD_LOCK = new Object();
    private static volatile int revision;

    /**
     * Effective index size. The user can disable the cap ("No limit"), but the
     * merged index must still fit in the process heap: when the cap is off we
     * fall back to a ceiling derived from {@link Runtime#maxMemory()} so a huge
     * list set cannot crash the app with an OutOfMemoryError.
     */
    private static int gravityLimit(Prefs p) {
        if (p.gravityCapEnabled && p.gravityCap > 0) return p.gravityCap;
        long heap = Runtime.getRuntime().maxMemory();
        // Reserve room for the app, VPN buffers, UI and the previous index.
        long reserve = 96L * 1024 * 1024;
        long budget = Math.max(heap - reserve, 128L * 1024 * 1024);
        // Peak cost is about 48 bytes per domain across the source arrays and
        // the final index, so leave comfortable headroom.
        long limit = budget / 48;
        if (limit > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.max(1_000_000, limit);
    }

    private Blocklist() { }

    static void load(Context c) {
        reload(c);
    }

    /** Loads gravity in the background so a large list never blocks the UI. */
    static void loadAsync(Context c, Runnable done) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                reload(app);
            } catch (Throwable ignored) {
                // The caller can still show the previous in-memory set.
            }
            if (done != null) new Handler(Looper.getMainLooper()).post(done);
        }, "gravity-load");
        t.setDaemon(true);
        t.start();
    }

    static void reload(Context c) {
        synchronized (RELOAD_LOCK) {
            reloadLocked(c);
            bumpRevision();
        }
    }

    /** Changes whenever lists, rules or settings change; screens cache against it. */
    static int revision() {
        return revision;
    }

    static synchronized void bumpRevision() {
        revision++;
    }

    private static void reloadLocked(Context c) {
        HashSet<String> eb = new HashSet<>(128);
        HashSet<String> ea = new HashSet<>(64);
        HashSet<String> wb = new HashSet<>(128);
        HashSet<String> wa = new HashSet<>(64);
        ArrayList<Compiled> rb = new ArrayList<>();
        ArrayList<Compiled> ra = new ArrayList<>();

        Prefs p = Prefs.load(c);
        blockSubdomains = p.blockSubdomains;
        categoryMask = p.categoryMask();
        readCustom(p.customBlocked, false, eb, wb, rb);
        readCustom(p.customAllowed, true, ea, wa, ra);
        for (String domain : Blocklists.SERVICE_ALLOW) ea.add(domain);
        Map<String, ClientRules> clients = parseClientRules(p.clientRules);

        Gravity fresh = loadGravity(c, p);
        gravity = fresh;
        refreshCounts();
        exactBlocked = eb;
        exactAllowed = ea;
        wildcardBlocked = wb;
        wildcardAllowed = wa;
        regexBlocked = rb;
        regexAllowed = ra;
        clientRules = clients;
    }

    private static Gravity loadGravity(Context c, Prefs p) {
        int limit = gravityLimit(p);
        long[] malware = readCategory(c, MALWARE_FILE, limit);
        long[] trackers = readCategory(c, TRACKER_FILE, limit);
        long[] ads = readCategory(c, ADS_FILE, limit);
        long[] adult = readCategory(c, ADULT_FILE, limit);
        long[] piracy = readCategory(c, PIRACY_FILE, limit);
        long[] gambling = readCategory(c, GAMBLING_FILE, limit);
        long[] dating = readCategory(c, DATING_FILE, limit);
        long[] social = readCategory(c, SOCIAL_FILE, limit);
        long[] other = readCategory(c, OTHER_FILE, limit);

        // Seed list shipped in the APK. It is ads-oriented.
        LongBuffer seed = new LongBuffer(131072);
        try (InputStream in = c.getAssets().open("blocklist.txt");
             BufferedReader r = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            readHostsHashed(r, seed, Integer.MAX_VALUE);
        } catch (Exception ignored) {
            // A missing seed list is not fatal.
        }

        boolean anyCategoryFile = malware.length > 0 || trackers.length > 0
                || ads.length > 0 || adult.length > 0 || other.length > 0;
        if (!anyCategoryFile) {
            // Upgrade path: the old build kept one merged file. Treat it as
            // "other" so it keeps blocking, but category switches cannot
            // separate it until the next gravity update rewrites the files.
            long[] legacy = readCategory(c, LEGACY_FILE, limit);
            if (legacy.length > 0) other = legacy;
        }

        long[] seedHashes = seed.toArray();
        long[] adsWithSeed = new long[ads.length + seedHashes.length];
        // Keep the bundled fallback domains ahead of the downloaded ad lists
        // so the fair cap never truncates them.
        System.arraycopy(seedHashes, 0, adsWithSeed, 0, seedHashes.length);
        System.arraycopy(ads, 0, adsWithSeed, seedHashes.length, ads.length);

        long[][] sources = {
                adsWithSeed, trackers, malware, adult, piracy,
                gambling, dating, social, other
        };
        ads = null;
        adsWithSeed = null;
        seedHashes = null;
        trackers = null;
        malware = null;
        adult = null;
        piracy = null;
        gambling = null;
        dating = null;
        social = null;
        other = null;
        long[][] capped = p.gravityCapEnabled && p.gravityCap > 0
                ? Gravity.fairCap(sources, p.gravityCap, sources.length) : sources;
        for (int i = 0; i < capped.length; i++) {
            capped[i] = Gravity.clean(capped[i]);
        }
        int[] bits = {
                Gravity.ADS, Gravity.TRACKERS, Gravity.MALWARE, Gravity.ADULT,
                Gravity.PIRACY, Gravity.GAMBLING, Gravity.DATING, Gravity.SOCIAL,
                Gravity.OTHER
        };
        return Gravity.build(capped, bits, limit);
    }

    private static long[] readCategory(Context c, String name, int limit) {
        LongBuffer out = new LongBuffer(131072);
        File file = new File(c.getFilesDir(), name);
        if (!file.exists()) return out.toArray();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            readHostsHashed(r, out, limit);
        } catch (Exception ignored) { }
        return out.toArray();
    }

    /**
     * True when a list for this category has not been stored yet. Used when a
     * category is switched back on and its sources are already configured, so
     * the download starts instead of waiting for the next manual update.
     */
    static boolean categoryFileMissing(Context c, String key) {
        String file = categoryFile(key);
        if (file == null) return false;
        File f = new File(c.getFilesDir(), file);
        return !f.exists() || f.length() == 0;
    }

    private static String categoryFile(String key) {
        if ("ads".equals(key)) return ADS_FILE;
        if ("trackers".equals(key)) return TRACKER_FILE;
        if ("malware".equals(key)) return MALWARE_FILE;
        if ("adult".equals(key)) return ADULT_FILE;
        if ("piracy".equals(key)) return PIRACY_FILE;
        if ("gambling".equals(key)) return GAMBLING_FILE;
        if ("dating".equals(key)) return DATING_FILE;
        if ("social".equals(key)) return SOCIAL_FILE;
        return null;
    }

    private static void readHostsHashed(BufferedReader r, LongBuffer into, int limit) {
        try {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n < limit) {
                String d = HostsParser.parseLine(line);
                if (!d.isEmpty()) {
                    into.add(Gravity.hash(d));
                    n++;
                }
            }
        } catch (Exception ignored) { }
    }

    /** Updates only the cheap custom-rule sets, leaving gravity untouched. */
    static void setBlockSubdomains(boolean on) {
        blockSubdomains = on;
    }

    static void setCategoryMask(int mask) {
        categoryMask = mask;
        refreshCounts();
    }

    static void applyPrefs(Prefs p) {
        if (p == null) return;
        blockSubdomains = p.blockSubdomains;
        categoryMask = p.categoryMask();
        refreshCounts();
        clientRules = parseClientRules(p.clientRules);
    }

    private static void refreshCounts() {
        Gravity g = gravity;
        int mask = categoryMask;
        activeGravityCount = g.activeSize(mask);
        categoryCounts = g.primaryCounts(CATEGORY_PRIORITY, mask);
    }

    static void clearCustomInMemory() {
        bumpRevision();
        exactBlocked = Collections.emptySet();
        exactAllowed = Collections.emptySet();
        wildcardBlocked = Collections.emptySet();
        wildcardAllowed = Collections.emptySet();
        regexBlocked = Collections.emptyList();
        regexAllowed = Collections.emptyList();
    }

    /** Applies a just-saved custom rule without rereading the 500k gravity file. */
    static void applyRuleInMemory(boolean allow, int kind, String value, boolean add) {
        bumpRevision();
        if (value == null || value.isEmpty()) return;
        if (kind == REGEX) {
            ArrayList<Compiled> copy = new ArrayList<>(allow ? regexAllowed : regexBlocked);
            boolean found = false;
            for (int i = copy.size() - 1; i >= 0; i--) {
                if (value.equals(copy.get(i).source)) {
                    found = true;
                    if (!add) copy.remove(i);
                }
            }
            if (add && !found) {
                try {
                    copy.add(new Compiled(Pattern.compile(value), value));
                } catch (Exception ignored) {
                    return;
                }
            }
            if (allow) regexAllowed = copy;
            else regexBlocked = copy;
            return;
        }
        Set<String> current = allow
                ? (kind == WILDCARD ? wildcardAllowed : exactAllowed)
                : (kind == WILDCARD ? wildcardBlocked : exactBlocked);
        HashSet<String> copy = new HashSet<>(current);
        if (add) copy.add(value);
        else copy.remove(value);
        if (allow) {
            if (kind == WILDCARD) wildcardAllowed = copy;
            else exactAllowed = copy;
        } else {
            if (kind == WILDCARD) wildcardBlocked = copy;
            else exactBlocked = copy;
        }
    }

    private static void readCustom(String text, boolean allow,
                                   Set<String> exact, Set<String> wildcard,
                                   List<Compiled> regex) {
        if (text == null) return;
        for (String raw : text.split("[\\n,]+")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            if (line.length() >= 2 && line.startsWith("/") && line.endsWith("/")) {
                addRegex(line.substring(1, line.length() - 1), regex);
            } else if (line.startsWith("*.")) {
                String d = HostsParser.normalizeDomain(line.substring(2));
                if (!d.isEmpty()) wildcard.add(d);
            } else {
                String d = HostsParser.parseLine(line);
                if (!d.isEmpty()) exact.add(d);
            }
        }
    }

    private static void addRegex(String source, List<Compiled> into) {
        if (into.size() >= MAX_REGEX) return;
        String s = source == null ? "" : source.trim();
        if (s.isEmpty() || s.length() > 512) return;
        try {
            into.add(new Compiled(Pattern.compile(s, Pattern.CASE_INSENSITIVE), s));
        } catch (Exception ignored) { }
    }

    static String normalize(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase(Locale.US);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    /** Full decision for one lookup. Allow rules win over block rules. */
    static Decision check(String host) {
        return check(host, null);
    }

    static Decision check(String host, String client) {
        String h = normalize(host);
        if (h.isEmpty() || !h.contains(".")) return Decision.NONE;

        Decision clientDecision = checkClient(client, h);
        if (clientDecision != Decision.NONE) return clientDecision;

        String exactAllow = findExact(exactAllowed, h);
        if (exactAllow != null) return Decision.allow(exactAllow);
        String wildAllow = findSubdomain(wildcardAllowed, h);
        if (wildAllow != null) return Decision.allow("*." + wildAllow);
        String reAllow = findRegex(regexAllowed, h);
        if (reAllow != null) return Decision.allow("/" + reAllow + "/");

        int flags = gravity.flagsOf(Gravity.hash(h));
        if ((flags & categoryMask) != 0) {
            return Decision.block(h, "gravity", categoryName(flags));
        }
        if (blockSubdomains) {
            String parent = findGravitySuffix(h);
            if (parent != null) {
                int parentFlags = gravity.flagsOf(Gravity.hash(parent));
                if ((parentFlags & categoryMask) != 0) {
                    return Decision.block("*." + parent, "gravity (subdomain)",
                            categoryName(parentFlags));
                }
            }
        }
        String exactBlock = findExact(exactBlocked, h);
        if (exactBlock != null) return Decision.block(exactBlock, "custom", "Custom");
        String wildBlock = findSubdomain(wildcardBlocked, h);
        if (wildBlock != null) return Decision.block("*." + wildBlock, "wildcard", "Custom");
        String reBlock = findRegex(regexBlocked, h);
        if (reBlock != null) return Decision.block("/" + reBlock + "/", "regex", "Custom");
        return Decision.NONE;
    }

    private static Decision checkClient(String client, String host) {
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
            if (allow) {
                (wildcard ? rules.wildcardAllowed : rules.allowed).add(domain);
            } else {
                (wildcard ? rules.wildcardBlocked : rules.blocked).add(domain);
            }
        }
        return out;
    }

    static boolean isBlocked(String host) {
        return check(host).blocked;
    }

    static boolean isAllowed(String host) {
        return check(host).allowed;
    }

    private static String findExact(Set<String> set, String host) {
        return set.contains(host) ? host : null;
    }

    /** Gravity parent lookup: returns the matched parent, never a bare TLD. */
    private static String findGravitySuffix(String host) {
        if (gravity.size() == 0) return null;
        int mask = categoryMask;
        String cur = host;
        while (cur.contains(".")) {
            int dot = cur.indexOf('.');
            if (dot < 0) break;
            cur = cur.substring(dot + 1);
            if (!cur.contains(".")) break;
            if ((gravity.flagsOf(Gravity.hash(cur)) & mask) != 0) return cur;
        }
        return null;
    }

    /** Strictly below the host: *.example.com does not match example.com. */
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

    static String categoryName(int flags) {
        if ((flags & Gravity.MALWARE) != 0) return "Malware";
        if ((flags & Gravity.TRACKERS) != 0) return "Trackers";
        if ((flags & Gravity.ADS) != 0) return "Ads";
        if ((flags & Gravity.ADULT) != 0) return "Adult";
        if ((flags & Gravity.PIRACY) != 0) return "Piracy";
        if ((flags & Gravity.GAMBLING) != 0) return "Gambling";
        if ((flags & Gravity.DATING) != 0) return "Dating";
        if ((flags & Gravity.SOCIAL) != 0) return "Social";
        if ((flags & Gravity.OTHER) != 0) return "Other";
        return "Gravity";
    }

    // ------------------------------------------------------------- custom rules

    /** Adds a custom rule, saves it and reloads the in-memory sets. */
    static boolean addRule(Context c, boolean allow, int kind, String value) {
        if (c == null) return false;
        String entry;
        if (kind == REGEX) {
            String s = value == null ? "" : value.trim();
            if (s.startsWith("/") && s.endsWith("/") && s.length() > 1) {
                s = s.substring(1, s.length() - 1);
            }
            if (s.isEmpty() || s.length() > 512) return false;
            try {
                Pattern.compile(s);
            } catch (Exception e) {
                return false;
            }
            entry = "/" + s + "/";
        } else {
            String d = HostsParser.normalizeDomain(value);
            if (d.isEmpty()) return false;
            entry = kind == WILDCARD ? "*." + d : d;
        }
        Prefs p = Prefs.load(c);
        String current = allow ? p.customAllowed : p.customBlocked;
        if (containsRule(current, entry)) return true;
        String merged = append(current, entry);
        if (allow) p.customAllowed = merged;
        else p.customBlocked = merged;
        p.save(c);
        if (kind == REGEX) {
            String source = entry.length() >= 2 ? entry.substring(1, entry.length() - 1) : entry;
            applyRuleInMemory(allow, kind, source, true);
        } else {
            applyRuleInMemory(allow, kind, entry.startsWith("*.") ? entry.substring(2) : entry, true);
        }
        return true;
    }

    static boolean removeRule(Context c, boolean allow, int kind, String value) {
        if (c == null) return false;
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
        Prefs p = Prefs.load(c);
        String current = allow ? p.customAllowed : p.customBlocked;
        String updated = removeLine(current, entry);
        if (updated.equals(current)) return false;
        if (allow) p.customAllowed = updated;
        else p.customBlocked = updated;
        p.save(c);
        if (kind == REGEX) {
            String source = entry.length() >= 2 ? entry.substring(1, entry.length() - 1) : entry;
            applyRuleInMemory(allow, kind, source, false);
        } else {
            applyRuleInMemory(allow, kind, entry.startsWith("*.") ? entry.substring(2) : entry, false);
        }
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

    /** Custom rules only; gravity is too large to enumerate in the API. */
    static List<Rule> rules(boolean allow) {
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

    static int blockedCount() {
        return activeGravityCount + exactBlocked.size() + wildcardBlocked.size()
                + regexBlocked.size();
    }

    /** Active gravity index size; custom rules are counted separately. */
    static int activeGravityDomains() {
        return activeGravityCount;
    }

    /** Active gravity domains for one category key (ads, trackers, ...). */
    static int categoryCount(String key) {
        int[] counts = categoryCounts;
        for (int i = 0; i < CATEGORY_PRIORITY_KEYS.length; i++) {
            if (CATEGORY_PRIORITY_KEYS[i].equals(key)) {
                return i < counts.length ? counts[i] : 0;
            }
        }
        return 0;
    }

    static int gravityCount() {
        return gravity.size();
    }

    static int allowedCount() {
        return exactAllowed.size() + wildcardAllowed.size() + regexAllowed.size();
    }

    static int regexCount() {
        return regexBlocked.size() + regexAllowed.size();
    }

    static boolean isUpdating() {
        return updating;
    }

    // -------------------------------------------------------------- health

    static List<Health> parseHealth(String text) {
        ArrayList<Health> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            String[] f = s.split("\\|", -1);
            if (f.length < 5) continue;
            try {
                out.add(new Health(f[0], "1".equals(f[1]),
                        Integer.parseInt(f[2]), f[3], Long.parseLong(f[4])));
            } catch (Exception ignored) { }
        }
        return out;
    }

    // -------------------------------------------------------------- downloading

    static void updateAsync(Context context, Done done) {
        if (updating) {
            post(done, false, "An update is already running");
            return;
        }
        updating = true;
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> {
            boolean ok = false;
            String message;
            try {
                Prefs p = Prefs.load(app);
                // Keep the configured tier authoritative so preset updates and
                // new list sources are picked up by the next gravity refresh.
                for (String key : Blocklists.CATEGORY_KEYS) {
                    if (p.categoryEnabled(key)
                            && Blocklists.hasAny(p.blocklistUrls,
                                    Blocklists.allUrls(key))) {
                        p.blocklistUrls = Blocklists.applyLevel(
                                p.blocklistUrls, key, p.categoryLevel(key));
                    }
                }
                List<String> urls = listUrls(p);
                if (urls.isEmpty()) throw new Exception("No blocklist URL is set");
                List<String> adsUrls = new ArrayList<>();
                List<String> trackerUrls = new ArrayList<>();
                List<String> malwareUrls = new ArrayList<>();
                List<String> adultUrls = new ArrayList<>();
                List<String> piracyUrls = new ArrayList<>();
                List<String> gamblingUrls = new ArrayList<>();
                List<String> datingUrls = new ArrayList<>();
                List<String> socialUrls = new ArrayList<>();
                List<String> otherUrls = new ArrayList<>();
                for (String url : urls) {
                    // A category the user switched off keeps its sources in the
                    // settings, but its list is neither downloaded nor counted.
                    if (!categoryOn(p, url)) continue;
                    switch (Blocklists.categoryOf(url)) {
                        case Blocklists.CATEGORY_TRACKERS:
                            trackerUrls.add(url);
                            break;
                        case Blocklists.CATEGORY_MALWARE:
                            malwareUrls.add(url);
                            break;
                        case Blocklists.CATEGORY_ADULT:
                            adultUrls.add(url);
                            break;
                        case Blocklists.CATEGORY_PIRACY:
                            piracyUrls.add(url);
                            break;
                        case Blocklists.CATEGORY_GAMBLING:
                            gamblingUrls.add(url);
                            break;
                        case Blocklists.CATEGORY_DATING:
                            datingUrls.add(url);
                            break;
                        case Blocklists.CATEGORY_SOCIAL:
                            socialUrls.add(url);
                            break;
                        case Blocklists.CATEGORY_ADS:
                            adsUrls.add(url);
                            break;
                        default:
                            otherUrls.add(url);
                            break;
                    }
                }
                ArrayList<Health> health = new ArrayList<>();
                int[] tally = new int[2];
                StringBuilder errors = new StringBuilder();
                // Security lists first so they survive the memory guard.
                int limit = gravityLimit(p);
                int malwareCount = downloadCategory(app, malwareUrls, MALWARE_FILE, health, tally, errors, limit);
                int trackerCount = downloadCategory(app, trackerUrls, TRACKER_FILE, health, tally, errors, limit);
                int adsCount = downloadCategory(app, adsUrls, ADS_FILE, health, tally, errors, limit);
                int adultCount = downloadCategory(app, adultUrls, ADULT_FILE, health, tally, errors, limit);
                int piracyCount = downloadCategory(app, piracyUrls, PIRACY_FILE, health, tally, errors, limit);
                int gamblingCount = downloadCategory(app, gamblingUrls, GAMBLING_FILE, health, tally, errors, limit);
                int datingCount = downloadCategory(app, datingUrls, DATING_FILE, health, tally, errors, limit);
                int socialCount = downloadCategory(app, socialUrls, SOCIAL_FILE, health, tally, errors, limit);
                int otherCount = downloadCategory(app, otherUrls, OTHER_FILE, health, tally, errors, limit);
                int listsOk = tally[0];
                int listsFailed = tally[1];
                int domains = adsCount + trackerCount + malwareCount + adultCount
                        + piracyCount + gamblingCount + datingCount + socialCount
                        + otherCount;
                if (listsOk == 0 || domains == 0) {
                    throw new Exception(errors.length() > 0 ? errors.toString()
                            : "No domains found");
                }
                Log.i("HoleShield", "gravity ads=" + adsCount + " trackers="
                        + trackerCount + " malware=" + malwareCount
                        + " adult=" + adultCount + " piracy=" + piracyCount
                        + " gambling=" + gamblingCount + " dating=" + datingCount
                        + " social=" + socialCount);
                File legacy = new File(app.getFilesDir(), LEGACY_FILE);
                if (legacy.exists()) legacy.delete();

                p.lastUpdate = System.currentTimeMillis();
                p.adsDomains = adsCount;
                p.trackerDomains = trackerCount;
                p.malwareDomains = malwareCount;
                p.adultDomains = adultCount;
                p.piracyDomains = piracyCount;
                p.gamblingDomains = gamblingCount;
                p.datingDomains = datingCount;
                p.socialDomains = socialCount;
                p.listHealth = joinHealth(health);
                p.save(app);
                reload(app);
                ok = true;
                message = "Blocking " + Blocklist.activeGravityDomains() + " domains from " + listsOk
                        + (listsOk == 1 ? " list" : " lists")
                        + (listsFailed > 0 ? " (" + listsFailed + " failed)" : "");
            } catch (Exception e) {
                message = e.getMessage() == null ? "Update failed" : e.getMessage();
            } finally {
                updating = false;
            }
            post(done, ok, message);
        }, "gravity-update");
        t.setDaemon(true);
        t.start();
    }

    private static int downloadCategory(Context app, List<String> urls, String file,
                                        List<Health> health, int[] tally,
                                        StringBuilder errors, int limit) {
        File dst = new File(app.getFilesDir(), file);
        if (urls.isEmpty()) return deleteCategory(app, file);
        File tmp = new File(app.getFilesDir(), file + ".tmp");
        LongSet seen = new LongSet(131072);
        int[] count = new int[1];
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            for (String url : urls) {
                if (count[0] >= limit) break;
                int before = count[0];
                try {
                    downloadInto(url, w, seen, count, limit);
                    int added = count[0] - before;
                    tally[0]++;
                    health.add(new Health(url, true, added, "",
                            System.currentTimeMillis()));
                } catch (Exception e) {
                    tally[1]++;
                    String error = e.getMessage() == null ? "failed" : e.getMessage();
                    health.add(new Health(url, false, 0, error,
                            System.currentTimeMillis()));
                    if (errors.length() < 180) {
                        if (errors.length() > 0) errors.append("; ");
                        errors.append(Prefs.hostOf(url)).append(": ").append(error);
                    }
                }
            }
        } catch (Exception e) {
            if (errors.length() < 180) {
                if (errors.length() > 0) errors.append("; ");
                errors.append(file).append(": ").append(e.getMessage());
            }
        }
        int c = count[0];
        if (c == 0) {
            tmp.delete();
            return deleteCategory(app, file);
        }
        if (!tmp.renameTo(dst)) {
            if (dst.exists()) dst.delete();
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                return deleteCategory(app, file);
            }
        }
        return c;
    }

    private static int deleteCategory(Context app, String file) {
        File dst = new File(app.getFilesDir(), file);
        if (dst.exists()) dst.delete();
        return 0;
    }

    private static String joinHealth(List<Health> health) {
        StringBuilder out = new StringBuilder();
        for (Health h : health) {
            if (out.length() > 0) out.append('\n');
            out.append(h.url.replace('|', '/')).append('|')
                    .append(h.ok ? '1' : '0').append('|')
                    .append(h.count).append('|')
                    .append(h.error.replace('|', '/').replace('\n', ' ')).append('|')
                    .append(h.at);
        }
        return out.toString();
    }

    private static void downloadInto(String url, BufferedWriter w, LongSet seen,
                                     int[] count, int limit) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(25000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Hole-Shield/2.3");
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        int read = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                read += line.length() + 1;
                if (read > MAX_DOWNLOAD_BYTES) throw new Exception("List is too large");
                if (count[0] >= limit) break;
                String d = HostsParser.parseLine(line);
                if (!d.isEmpty() && seen.add(Gravity.hash(d))) {
                    w.write(d);
                    w.write('\n');
                    count[0]++;
                }
            }
        }
    }

    private static List<String> listUrls(Prefs p) {
        return Blocklists.urls(p.blocklistUrls);
    }

    /** False when a configured source belongs to a category switched off. */
    private static boolean categoryOn(Prefs p, String url) {
        switch (Blocklists.categoryOf(url)) {
            case Blocklists.CATEGORY_ADS: return p.categoryEnabled("ads");
            case Blocklists.CATEGORY_TRACKERS: return p.categoryEnabled("trackers");
            case Blocklists.CATEGORY_MALWARE: return p.categoryEnabled("malware");
            case Blocklists.CATEGORY_ADULT: return p.categoryEnabled("adult");
            case Blocklists.CATEGORY_PIRACY: return p.categoryEnabled("piracy");
            case Blocklists.CATEGORY_GAMBLING: return p.categoryEnabled("gambling");
            case Blocklists.CATEGORY_DATING: return p.categoryEnabled("dating");
            case Blocklists.CATEGORY_SOCIAL: return p.categoryEnabled("social");
            default: return true;
        }
    }

    private static void post(Done done, boolean ok, String message) {
        if (done == null) return;
        new Handler(Looper.getMainLooper()).post(() -> done.run(ok, message));
    }
}
