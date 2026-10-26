package com.dino.holeshield;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pi-hole-style gravity engine.
 *
 * Gravity lists (the bundled seed and downloaded hosts/adblock files) are
 * exact domains. Subdomains are matched too when the "block subdomains"
 * setting is on, which is how most blocklists are meant to behave. Custom
 * rules can be exact, wildcard ("*.example.com") or regular expressions.
 * Allow rules are checked before block rules.
 */
final class Blocklist {

    static final int EXACT = 0;
    static final int WILDCARD = 1;
    static final int REGEX = 2;

    private static final String DOWNLOAD_FILE = "blocklist_downloaded.txt";
    private static final int MAX_DOMAINS = 600000;
    private static final int MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024;
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
        Decision(boolean blocked, boolean allowed, String rule, String source) {
            this.blocked = blocked;
            this.allowed = allowed;
            this.rule = rule;
            this.source = source;
        }
        static final Decision NONE = new Decision(false, false, "", "");
        static Decision block(String rule, String source) {
            return new Decision(true, false, rule, source);
        }
        static Decision allow(String rule) {
            return new Decision(false, true, rule, "allowlist");
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

    private static volatile Set<String> gravity = Collections.emptySet();
    private static volatile Set<String> exactBlocked = Collections.emptySet();
    private static volatile Set<String> exactAllowed = Collections.emptySet();
    private static volatile Set<String> wildcardBlocked = Collections.emptySet();
    private static volatile Set<String> wildcardAllowed = Collections.emptySet();
    private static volatile List<Compiled> regexBlocked = Collections.emptyList();
    private static volatile List<Compiled> regexAllowed = Collections.emptyList();
    private static volatile boolean blockSubdomains = true;
    private static volatile boolean updating = false;
    private static final Object RELOAD_LOCK = new Object();

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
        }
    }

    private static void reloadLocked(Context c) {
        HashSet<String> grav = new HashSet<>(131072);
        HashSet<String> eb = new HashSet<>(128);
        HashSet<String> ea = new HashSet<>(64);
        HashSet<String> wb = new HashSet<>(128);
        HashSet<String> wa = new HashSet<>(64);
        ArrayList<Compiled> rb = new ArrayList<>();
        ArrayList<Compiled> ra = new ArrayList<>();

        try (InputStream in = c.getAssets().open("blocklist.txt");
             BufferedReader r = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            readHosts(r, grav);
        } catch (Exception ignored) {
            // A missing seed list is not fatal.
        }

        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(new File(c.getFilesDir(), DOWNLOAD_FILE)),
                StandardCharsets.UTF_8))) {
            readHosts(r, grav);
        } catch (Exception ignored) { }

        Prefs p = Prefs.load(c);
        blockSubdomains = p.blockSubdomains;
        readCustom(p.customBlocked, false, eb, wb, rb);
        readCustom(p.customAllowed, true, ea, wa, ra);

        gravity = grav;
        exactBlocked = eb;
        exactAllowed = ea;
        wildcardBlocked = wb;
        wildcardAllowed = wa;
        regexBlocked = rb;
        regexAllowed = ra;
    }

    /** Updates only the cheap custom-rule sets, leaving gravity untouched. */
    static void setBlockSubdomains(boolean on) {
        blockSubdomains = on;
    }

    static void clearCustomInMemory() {
        exactBlocked = Collections.emptySet();
        exactAllowed = Collections.emptySet();
        wildcardBlocked = Collections.emptySet();
        wildcardAllowed = Collections.emptySet();
        regexBlocked = Collections.emptyList();
        regexAllowed = Collections.emptyList();
    }

    /** Applies a just-saved custom rule without rereading the 500k gravity file. */
    static void applyRuleInMemory(boolean allow, int kind, String value, boolean add) {
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

    private static void readHosts(BufferedReader r, Set<String> into) {
        try {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n < MAX_DOMAINS) {
                String d = HostsParser.parseLine(line);
                if (!d.isEmpty() && into.add(d)) n++;
            }
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
        String h = normalize(host);
        if (h.isEmpty() || !h.contains(".")) return Decision.NONE;

        String exactAllow = findExact(exactAllowed, h);
        if (exactAllow != null) return Decision.allow(exactAllow);
        String wildAllow = findSubdomain(wildcardAllowed, h);
        if (wildAllow != null) return Decision.allow("*." + wildAllow);
        String reAllow = findRegex(regexAllowed, h);
        if (reAllow != null) return Decision.allow("/" + reAllow + "/");

        if (gravity.contains(h)) return Decision.block(h, "gravity");
        if (blockSubdomains) {
            String parent = findSuffix(gravity, h);
            if (parent != null) return Decision.block("*." + parent, "gravity (subdomain)");
        }
        String exactBlock = findExact(exactBlocked, h);
        if (exactBlock != null) return Decision.block(exactBlock, "custom");
        String wildBlock = findSubdomain(wildcardBlocked, h);
        if (wildBlock != null) return Decision.block("*." + wildBlock, "wildcard");
        String reBlock = findRegex(regexBlocked, h);
        if (reBlock != null) return Decision.block("/" + reBlock + "/", "regex");
        return Decision.NONE;
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

    private static String findSuffix(Set<String> set, String host) {
        if (set.isEmpty()) return null;
        String cur = host;
        while (cur.contains(".")) {
            if (set.contains(cur)) return cur;
            int dot = cur.indexOf('.');
            if (dot < 0) break;
            cur = cur.substring(dot + 1);
            if (!cur.contains(".")) break;       // never match a bare TLD
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
        return gravity.size() + exactBlocked.size() + wildcardBlocked.size()
                + regexBlocked.size();
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
                List<String> urls = listUrls(p);
                if (urls.isEmpty()) throw new Exception("No blocklist URL is set");
                HashSet<String> fresh = new HashSet<>(131072);
                int listsOk = 0;
                int listsFailed = 0;
                StringBuilder errors = new StringBuilder();
                for (String url : urls) {
                    if (fresh.size() >= MAX_DOMAINS) break;
                    try {
                        downloadInto(url, fresh);
                        listsOk++;
                    } catch (Exception e) {
                        listsFailed++;
                        if (errors.length() < 180) {
                            if (errors.length() > 0) errors.append("; ");
                            errors.append(Prefs.hostOf(url)).append(": ")
                                    .append(e.getMessage() == null ? "failed" : e.getMessage());
                        }
                    }
                }
                if (listsOk == 0 || fresh.isEmpty()) {
                    throw new Exception(errors.length() > 0 ? errors.toString()
                            : "No domains found");
                }
                int domains = fresh.size();
                File tmp = new File(app.getFilesDir(), DOWNLOAD_FILE + ".tmp");
                try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
                    for (String d : fresh) {
                        w.write(d);
                        w.write('\n');
                    }
                }
                // Drop the download set before rebuilding the live gravity set.
                // At 500k+ domains, holding both sets plus the previous one can
                // exhaust the heap on a TV.
                fresh.clear();
                fresh = null;
                File dst = new File(app.getFilesDir(), DOWNLOAD_FILE);
                if (!tmp.renameTo(dst)) {
                    if (dst.exists()) dst.delete();
                    if (!tmp.renameTo(dst)) throw new Exception("Could not save gravity");
                }
                p.lastUpdate = System.currentTimeMillis();
                p.save(app);
                reload(app);
                ok = true;
                message = "Blocking " + domains + " domains from " + listsOk
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

    private static void downloadInto(String url, Set<String> into) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(25000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Pi-hole-TV/2.0");
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        int read = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                read += line.length() + 1;
                if (read > MAX_DOWNLOAD_BYTES) throw new Exception("List is too large");
                if (into.size() >= MAX_DOMAINS) break;
                String d = HostsParser.parseLine(line);
                if (!d.isEmpty()) into.add(d);
            }
        }
    }

    private static List<String> listUrls(Prefs p) {
        return Blocklists.urls(p.blocklistUrls);
    }

    private static void post(Done done, boolean ok, String message) {
        if (done == null) return;
        new Handler(Looper.getMainLooper()).post(() -> done.run(ok, message));
    }
}
