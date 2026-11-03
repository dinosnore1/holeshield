package com.dino.holeshield;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * One small preferences file. The VPN service and the dashboard both read it
 * fresh, so there is never a stale in-memory copy to keep in sync.
 */
final class Prefs {

    private static final String FILE = "holeshield";

    /** True once the user has asked for protection; drives auto-start. */
    boolean protectionWanted = false;
    /** True once the in-app VpnService disclosure has been accepted. */
    boolean vpnDisclosureAccepted = false;
    boolean startOnBoot = true;

    /** Legacy external-Pi-hole fields; the built-in server is the default now. */
    boolean usePihole = false;
    String piholeHost = "";
    int piholePort = 80;
    String piholePassword = "";
    int apiVersion = 0;

    /** "doh", "plain" or "none" — upstream used for non-blocked lookups. */
    String fallback = "doh";
    String dohUrl = "https://cloudflare-dns.com/dns-query";

    boolean localBlocking = true;
    /** Also match subdomains of domains in gravity-style lists. */
    boolean blockSubdomains = true;
    /** Inspect CNAME targets in upstream answers as well as the question. */
    boolean blockCname = true;
    /** Route well-known public resolvers into the tunnel so hard-coded DNS
     *  (and DNS-over-TLS to those resolvers) cannot skip the filter. */
    boolean forceDns = true;

    /** Newline-separated hosts/gravity list URLs. */
    String blocklistUrls = Blocklists.presetText();
    int blocklistPresetVersion = 0;
    long lastUpdate = 0L;
    /** Unique domains counted per category during the last gravity update. */
    int adsDomains = 0;
    int trackerDomains = 0;
    int malwareDomains = 0;

    /** Newline separated custom entries.  "*." prefix = subdomains too;
     *  "/.../" = regular expression. */
    String customBlocked = "";
    String customAllowed = "";

    /** Global Pi-hole blocking switch, with optional auto-resume. */
    boolean blockingEnabled = true;
    long blockingUntil = 0L;

    /** Built-in web dashboard. */
    boolean webEnabled = true;
    int webPort = 8080;
    /** Optional dashboard username; enforced only when a password is set. */
    String webUsername = "";
    String webPassword = "";

    /** LAN DNS listener. Android cannot bind port 53 without root. */
    boolean lanDns = true;
    int lanDnsPort = 5354;

    static Prefs load(Context c) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Prefs s = new Prefs();
        s.protectionWanted = p.getBoolean("protectionWanted", s.protectionWanted);
        s.vpnDisclosureAccepted = p.getBoolean("vpnDisclosureAccepted",
                s.vpnDisclosureAccepted);
        s.startOnBoot = p.getBoolean("startOnBoot", s.startOnBoot);
        // The embedded resolver replaced the legacy external-Pi-hole mode.
        // Ignore an old true value so upgrades cannot route around the
        // on-device gravity engine.
        s.usePihole = false;
        s.piholeHost = p.getString("piholeHost", s.piholeHost);
        s.piholePort = p.getInt("piholePort", s.piholePort);
        s.piholePassword = p.getString("piholePassword", s.piholePassword);
        s.fallback = p.getString("fallback", s.fallback);
        s.dohUrl = p.getString("dohUrl", s.dohUrl);
        s.localBlocking = p.getBoolean("localBlocking", s.localBlocking);
        s.blockSubdomains = p.getBoolean("blockSubdomains", s.blockSubdomains);
        s.blockCname = p.getBoolean("blockCname", s.blockCname);
        s.forceDns = p.getBoolean("forceDns", s.forceDns);
        String legacyUrl = p.getString("blocklistUrl", null);
        s.blocklistUrls = p.getString("blocklistUrls",
                legacyUrl == null || legacyUrl.trim().isEmpty() ? s.blocklistUrls : legacyUrl);
        s.blocklistPresetVersion = p.getInt("blocklistPresetVersion", 0);
        s.lastUpdate = p.getLong("lastUpdate", s.lastUpdate);
        s.adsDomains = p.getInt("adsDomains", s.adsDomains);
        s.trackerDomains = p.getInt("trackerDomains", s.trackerDomains);
        s.malwareDomains = p.getInt("malwareDomains", s.malwareDomains);
        s.customBlocked = p.getString("customBlocked", s.customBlocked);
        s.customAllowed = p.getString("customAllowed", s.customAllowed);
        s.blockingEnabled = p.getBoolean("blockingEnabled", s.blockingEnabled);
        s.blockingUntil = p.getLong("blockingUntil", s.blockingUntil);
        s.webEnabled = p.getBoolean("webEnabled", s.webEnabled);
        s.webPort = p.getInt("webPort", s.webPort);
        s.webUsername = p.getString("webUsername", s.webUsername);
        s.webPassword = p.getString("webPassword", s.webPassword);
        s.lanDns = p.getBoolean("lanDns", s.lanDns);
        s.lanDnsPort = p.getInt("lanDnsPort", s.lanDnsPort);

        // Upgrade the old single-list default without touching custom sources.
        if (s.blocklistPresetVersion < Blocklists.PRESET_VERSION) {
            if (Blocklists.isLegacyDefault(s.blocklistUrls)) {
                s.blocklistUrls = Blocklists.presetText();
                s.lastUpdate = 0L;
            }
            s.blocklistPresetVersion = Blocklists.PRESET_VERSION;
            s.save(c);
        }
        return s;
    }

    void save(Context c) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean("protectionWanted", protectionWanted)
                .putBoolean("vpnDisclosureAccepted", vpnDisclosureAccepted)
                .putBoolean("startOnBoot", startOnBoot)
                .putBoolean("usePihole", usePihole)
                .putString("piholeHost", piholeHost)
                .putInt("piholePort", piholePort)
                .putString("piholePassword", piholePassword)
                .putString("fallback", fallback)
                .putString("dohUrl", dohUrl)
                .putBoolean("localBlocking", localBlocking)
                .putBoolean("blockSubdomains", blockSubdomains)
                .putBoolean("blockCname", blockCname)
                .putBoolean("forceDns", forceDns)
                .putString("blocklistUrls", blocklistUrls)
                .putInt("blocklistPresetVersion", blocklistPresetVersion)
                .putLong("lastUpdate", lastUpdate)
                .putInt("adsDomains", adsDomains)
                .putInt("trackerDomains", trackerDomains)
                .putInt("malwareDomains", malwareDomains)
                .putString("customBlocked", customBlocked)
                .putString("customAllowed", customAllowed)
                .putBoolean("blockingEnabled", blockingEnabled)
                .putLong("blockingUntil", blockingUntil)
                .putBoolean("webEnabled", webEnabled)
                .putInt("webPort", webPort)
                .putString("webUsername", webUsername)
                .putString("webPassword", webPassword)
                .putBoolean("lanDns", lanDns)
                .putInt("lanDnsPort", lanDnsPort)
                .apply();
    }

    boolean hasPihole() {
        return usePihole && piholeHost != null && !piholeHost.trim().isEmpty();
    }

    /**
     * True while gravity rules should be applied. A timed pause stores its
     * end in blockingUntil and automatically becomes active again when it
     * passes; an indefinite pause stores zero and stays off.
     */
    boolean blockingActive() {
        if (blockingEnabled) return true;
        if (blockingUntil > 0L) {
            return System.currentTimeMillis() >= blockingUntil;
        }
        return false;
    }

    long blockingRemainingSeconds() {
        if (blockingEnabled) return -1L;
        if (blockingUntil <= 0L) return 0L;
        long left = blockingUntil - System.currentTimeMillis();
        return left <= 0L ? 0L : (left + 999L) / 1000L;
    }

    void pauseBlocking(int seconds) {
        blockingEnabled = false;
        blockingUntil = seconds > 0
                ? System.currentTimeMillis() + seconds * 1000L : 0L;
    }

    void resumeBlocking() {
        blockingEnabled = true;
        blockingUntil = 0L;
    }

    /** First configured list URL, for compact UI summaries. */
    String primaryListUrl() {
        if (blocklistUrls == null) return "";
        for (String line : blocklistUrls.split("\\n")) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) return s;
        }
        return "";
    }

    /** Human readable upstream, for the dashboard subtitle. */
    String upstreamLabel() {
        if (hasPihole()) {
            return "Pi-hole at " + piholeHost.trim();
        }
        if ("doh".equals(fallback)) {
            return "encrypted DNS (" + hostOf(dohUrl) + ")";
        }
        if ("plain".equals(fallback)) {
            return "plain DNS";
        }
        return "no upstream";
    }

    static String hostOf(String url) {
        if (url == null) return "";
        try {
            java.net.URL u = new java.net.URL(url);
            return u.getHost();
        } catch (Exception e) {
            return url;
        }
    }

    /** Base URL of the Pi-hole web/API server, without a trailing slash. */
    String piholeBase() {
        String host = piholeHost == null ? "" : piholeHost.trim();
        if (host.isEmpty()) return "";
        if (host.startsWith("http://") || host.startsWith("https://")) {
            while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
            if (host.endsWith("/admin")) host = host.substring(0, host.length() - 6);
            return host;
        }

        int port = piholePort <= 0 ? 80 : piholePort;
        if (host.startsWith("[")) {                       // [v6] or [v6]:port
            int close = host.indexOf(']');
            if (close > 0) {
                String rest = host.substring(close + 1);
                if (rest.startsWith(":")) {
                    int given = parsePort(rest.substring(1));
                    if (given > 0) port = given;
                }
                return "http://" + host.substring(0, close + 1)
                        + (port == 80 ? "" : ":" + port);
            }
            return "http://" + host;
        }

        int first = host.indexOf(':');
        if (first > 0 && host.indexOf(':', first + 1) < 0) {   // host:port
            int given = parsePort(host.substring(first + 1));
            if (given > 0) port = given;
            host = host.substring(0, first);
        } else if (first >= 0) {                             // bare IPv6
            host = "[" + host + "]";
        }
        return "http://" + host + (port == 80 ? "" : ":" + port);
    }

    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text.trim());
            return port >= 1 && port <= 65535 ? port : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    String piholeDnsHost() {
        String host = piholeHost == null ? "" : piholeHost.trim();
        if (host.startsWith("http://") || host.startsWith("https://")) {
            host = hostOf(host);
        }
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close > 0) return host.substring(1, close);
        }
        int colon = host.indexOf(':');
        if (colon > 0 && host.indexOf(':', colon + 1) < 0) {
            return host.substring(0, colon);
        }
        return host;
    }
}
