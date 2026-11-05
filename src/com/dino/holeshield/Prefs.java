package com.dino.holeshield;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

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
    /** True after the first-run setup screen has been completed. */
    boolean onboardingDone = false;
    boolean startOnBoot = true;
    /** Extra TV-only inset for displays that crop the outer edges. */
    int tvSafeArea = 0;
    /** "system", "dark" or "light". */
    String theme = "system";
    /** "stable", "beta" or "off". */
    String updateChannel = "stable";
    long lastUpdateCheck = 0L;

    /** "doh", "dot", "plain" or "none" — upstream used for non-blocked lookups. */
    String fallback = "doh";
    String dohUrl = "https://cloudflare-dns.com/dns-query";
    String dotHost = "one.one.one.one";
    int dotPort = 853;

    boolean localBlocking = true;
    /** Also match subdomains of domains in gravity-style lists. */
    boolean blockSubdomains = true;
    /** Inspect CNAME targets in upstream answers as well as the question. */
    boolean blockCname = true;
    /** Route well-known public resolvers into the tunnel so hard-coded DNS
     *  (and DNS-over-TLS to those resolvers) cannot skip the filter. */
    boolean forceDns = true;
    /** zero | nxdomain | nodata — the answer sent for a blocked lookup. */
    String blockedResponse = "zero";

    /** Newline-separated hosts/gravity list URLs. */
    String blocklistUrls = Blocklists.presetText();
    int blocklistPresetVersion = 0;
    long lastUpdate = 0L;
    /** Unique domains counted per category during the last gravity update. */
    int adsDomains = 0;
    int trackerDomains = 0;
    int malwareDomains = 0;
    int adultDomains = 0;
    /** Per-list download results, as "url|ok|count|error|at" lines. */
    String listHealth = "";

    /** Newline separated custom entries.  "*." prefix = subdomains too;
     *  "/.../" = regular expression. */
    String customBlocked = "";
    String customAllowed = "";
    /** Per-client rules: "ip domain" blocks, "ip !domain" allows. */
    String clientRules = "";

    /** Global blocking switch, with optional auto-resume. */
    boolean blockingEnabled = true;
    long blockingUntil = 0L;

    /** Blocking hours. Outside the window blocking is paused automatically. */
    boolean scheduleEnabled = false;
    int scheduleStart = 20 * 60;
    int scheduleEnd = 7 * 60;

    /** Runtime category switches. Other/custom lists follow Gravity blocking. */
    boolean categoryAds = true;
    boolean categoryTrackers = true;
    boolean categoryMalware = true;
    /** Parental filter: adds the adult-content list to gravity. */
    boolean safeSearch = false;

    /** Built-in web dashboard. Off until the user opts in on first run. */
    boolean webEnabled = false;
    int webPort = 8080;
    /** Optional dashboard username; enforced only when a password is set. */
    String webUsername = "";
    /** PBKDF2 record. Plaintext is migrated on load and never written back. */
    String webPasswordHash = "";
    /** Legacy plaintext field, only read during migration. */
    String webPassword = "";
    /** Bumped to invalidate every web session ("log out all"). */
    int webSessionEpoch = 0;

    /** LAN DNS listener. Android cannot bind port 53 without root. */
    boolean lanDns = false;
    int lanDnsPort = 5354;

    static Prefs load(Context c) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Prefs s = new Prefs();
        s.protectionWanted = p.getBoolean("protectionWanted", s.protectionWanted);
        s.vpnDisclosureAccepted = p.getBoolean("vpnDisclosureAccepted",
                s.vpnDisclosureAccepted);
        // Existing installs already went through the disclosure; do not show
        // the new setup screen again after an upgrade.
        s.onboardingDone = p.getBoolean("onboardingDone",
                p.contains("protectionWanted") || p.contains("blocklistPresetVersion"));
        s.startOnBoot = p.getBoolean("startOnBoot", s.startOnBoot);
        s.tvSafeArea = p.getInt("tvSafeArea2", s.tvSafeArea);
        s.theme = p.getString("theme", s.theme);
        s.updateChannel = p.getString("updateChannel", s.updateChannel);
        s.lastUpdateCheck = p.getLong("lastUpdateCheck", s.lastUpdateCheck);
        s.fallback = p.getString("fallback", s.fallback);
        s.dohUrl = p.getString("dohUrl", s.dohUrl);
        s.dotHost = p.getString("dotHost", s.dotHost);
        s.dotPort = p.getInt("dotPort", s.dotPort);
        s.localBlocking = p.getBoolean("localBlocking", s.localBlocking);
        s.blockSubdomains = p.getBoolean("blockSubdomains", s.blockSubdomains);
        s.blockCname = p.getBoolean("blockCname", s.blockCname);
        s.forceDns = p.getBoolean("forceDns", s.forceDns);
        s.blockedResponse = p.getString("blockedResponse", s.blockedResponse);
        String legacyUrl = p.getString("blocklistUrl", null);
        s.blocklistUrls = p.getString("blocklistUrls",
                legacyUrl == null || legacyUrl.trim().isEmpty() ? s.blocklistUrls : legacyUrl);
        s.blocklistPresetVersion = p.getInt("blocklistPresetVersion", 0);
        s.lastUpdate = p.getLong("lastUpdate", s.lastUpdate);
        s.adsDomains = p.getInt("adsDomains", s.adsDomains);
        s.trackerDomains = p.getInt("trackerDomains", s.trackerDomains);
        s.malwareDomains = p.getInt("malwareDomains", s.malwareDomains);
        s.adultDomains = p.getInt("adultDomains", s.adultDomains);
        s.listHealth = p.getString("listHealth", s.listHealth);
        s.customBlocked = p.getString("customBlocked", s.customBlocked);
        s.customAllowed = p.getString("customAllowed", s.customAllowed);
        s.clientRules = p.getString("clientRules", s.clientRules);
        s.blockingEnabled = p.getBoolean("blockingEnabled", s.blockingEnabled);
        s.blockingUntil = p.getLong("blockingUntil", s.blockingUntil);
        s.scheduleEnabled = p.getBoolean("scheduleEnabled", s.scheduleEnabled);
        s.scheduleStart = p.getInt("scheduleStart", s.scheduleStart);
        s.scheduleEnd = p.getInt("scheduleEnd", s.scheduleEnd);
        s.categoryAds = p.getBoolean("categoryAds", s.categoryAds);
        s.categoryTrackers = p.getBoolean("categoryTrackers", s.categoryTrackers);
        s.categoryMalware = p.getBoolean("categoryMalware", s.categoryMalware);
        s.safeSearch = p.getBoolean("safeSearch", s.safeSearch);
        s.webEnabled = p.getBoolean("webEnabled", s.webEnabled);
        s.webPort = p.getInt("webPort", s.webPort);
        s.webUsername = p.getString("webUsername", s.webUsername);
        s.webPasswordHash = p.getString("webPasswordHash", s.webPasswordHash);
        s.webSessionEpoch = p.getInt("webSessionEpoch", s.webSessionEpoch);
        s.lanDns = p.getBoolean("lanDns", s.lanDns);
        s.lanDnsPort = p.getInt("lanDnsPort", s.lanDnsPort);

        // Migrate the old plaintext password to a PBKDF2 record. The plaintext
        // value is dropped from the file on the next save.
        String legacyPassword = p.getString("webPassword", "");
        if (s.webPasswordHash == null || s.webPasswordHash.isEmpty()) {
            if (legacyPassword != null && !legacyPassword.isEmpty()) {
                s.webPasswordHash = WebAuth.hash(legacyPassword);
                s.save(c);
            }
        }
        s.webPassword = "";

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
                .putBoolean("onboardingDone", onboardingDone)
                .putBoolean("startOnBoot", startOnBoot)
                .putInt("tvSafeArea2", tvSafeArea)
                .putString("theme", theme)
                .putString("updateChannel", updateChannel)
                .putLong("lastUpdateCheck", lastUpdateCheck)
                .putString("fallback", fallback)
                .putString("dohUrl", dohUrl)
                .putString("dotHost", dotHost)
                .putInt("dotPort", dotPort)
                .putBoolean("localBlocking", localBlocking)
                .putBoolean("blockSubdomains", blockSubdomains)
                .putBoolean("blockCname", blockCname)
                .putBoolean("forceDns", forceDns)
                .putString("blockedResponse", blockedResponse)
                .putString("blocklistUrls", blocklistUrls)
                .putInt("blocklistPresetVersion", blocklistPresetVersion)
                .putLong("lastUpdate", lastUpdate)
                .putInt("adsDomains", adsDomains)
                .putInt("trackerDomains", trackerDomains)
                .putInt("malwareDomains", malwareDomains)
                .putInt("adultDomains", adultDomains)
                .putString("listHealth", listHealth)
                .putString("customBlocked", customBlocked)
                .putString("customAllowed", customAllowed)
                .putString("clientRules", clientRules)
                .putBoolean("blockingEnabled", blockingEnabled)
                .putLong("blockingUntil", blockingUntil)
                .putBoolean("scheduleEnabled", scheduleEnabled)
                .putInt("scheduleStart", scheduleStart)
                .putInt("scheduleEnd", scheduleEnd)
                .putBoolean("categoryAds", categoryAds)
                .putBoolean("categoryTrackers", categoryTrackers)
                .putBoolean("categoryMalware", categoryMalware)
                .putBoolean("safeSearch", safeSearch)
                .putBoolean("webEnabled", webEnabled)
                .putInt("webPort", webPort)
                .putString("webUsername", webUsername)
                .putString("webPasswordHash", webPasswordHash)
                .putInt("webSessionEpoch", webSessionEpoch)
                .putBoolean("lanDns", lanDns)
                .putInt("lanDnsPort", lanDnsPort)
                .remove("webPassword")
                .apply();
    }

    /** Resolves the appearance preference against the current system mode. */
    boolean darkTheme(Context c) {
        if ("dark".equals(theme)) return true;
        if ("light".equals(theme)) return false;
        int mode = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    // ------------------------------------------------------------------- login

    boolean hasWebPassword() {
        return webPasswordHash != null && !webPasswordHash.isEmpty();
    }

    void setWebPassword(String password) {
        webPasswordHash = WebAuth.hash(password);
        if (password == null || password.isEmpty()) webUsername = "";
        webSessionEpoch++;
    }

    boolean verifyWebPassword(String supplied) {
        return WebAuth.verify(webPasswordHash, supplied);
    }

    void logoutAllWebSessions() {
        webSessionEpoch++;
    }

    // --------------------------------------------------------------- filtering

    /** Category bits that are currently allowed to block. */
    int categoryMask() {
        int mask = Gravity.OTHER;
        if (categoryAds) mask |= Gravity.ADS;
        if (categoryTrackers) mask |= Gravity.TRACKERS;
        if (categoryMalware) mask |= Gravity.MALWARE;
        if (safeSearch) mask |= Gravity.ADULT;
        return mask;
    }

    /** 0 = 0.0.0.0, 1 = NXDOMAIN, 2 = NODATA. */
    int blockedResponseMode() {
        if ("nxdomain".equals(blockedResponse)) return 1;
        if ("nodata".equals(blockedResponse)) return 2;
        return 0;
    }

    /**
     * True while gravity rules should be applied. A timed pause stores its
     * end in blockingUntil and automatically becomes active again when it
     * passes; an indefinite pause stores zero and stays off. Blocking hours
     * pause automatically outside the configured window.
     */
    boolean blockingActive() {
        if (!blockingEnabled) {
            if (blockingUntil > 0L) {
                if (System.currentTimeMillis() < blockingUntil) return false;
            } else {
                return false;
            }
        }
        if (scheduleEnabled && !scheduleActiveNow()) return false;
        return true;
    }

    long blockingRemainingSeconds() {
        if (!blockingEnabled) {
            if (blockingUntil <= 0L) return 0L;
            long left = blockingUntil - System.currentTimeMillis();
            if (left > 0L) return (left + 999L) / 1000L;
        }
        if (scheduleEnabled && !scheduleActiveNow()) {
            return scheduleNextChangeSeconds();
        }
        return -1L;
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

    /** True inside the configured blocking window. Handles overnight ranges. */
    boolean scheduleActiveNow() {
        if (!scheduleEnabled) return true;
        int minute = minuteOfDay(System.currentTimeMillis());
        int start = clampMinute(scheduleStart);
        int end = clampMinute(scheduleEnd);
        if (start == end) return true;
        if (start < end) return minute >= start && minute < end;
        return minute >= start || minute < end;
    }

    /** Seconds until the schedule next flips, or 0 when it is off. */
    long scheduleNextChangeSeconds() {
        if (!scheduleEnabled) return 0L;
        int minute = minuteOfDay(System.currentTimeMillis());
        int start = clampMinute(scheduleStart);
        int end = clampMinute(scheduleEnd);
        if (start == end) return 0L;
        boolean active = scheduleActiveNow();
        int target = active ? end : start;
        int diff = target - minute;
        if (diff <= 0) diff += 24 * 60;
        long now = System.currentTimeMillis();
        long seconds = diff * 60L;
        // Account for the seconds already elapsed in this minute.
        long secondsIntoMinute = (now / 1000L) % 60L;
        return Math.max(1L, seconds - secondsIntoMinute);
    }

    private static int minuteOfDay(long millis) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60
                + c.get(java.util.Calendar.MINUTE);
    }

    private static int clampMinute(int value) {
        return Math.max(0, Math.min(24 * 60 - 1, value));
    }

    String scheduleLabel() {
        if (!scheduleEnabled) return "Off";
        return timeLabel(scheduleStart) + "–" + timeLabel(scheduleEnd);
    }

    static String timeLabel(int minutes) {
        int value = clampMinute(minutes);
        return String.format(java.util.Locale.US, "%02d:%02d",
                value / 60, value % 60);
    }

    static int parseTime(String text) {
        if (text == null) return -1;
        String value = text.trim();
        int colon = value.indexOf(':');
        if (colon <= 0) return -1;
        try {
            int hour = Integer.parseInt(value.substring(0, colon).trim());
            int minute = Integer.parseInt(value.substring(colon + 1).trim());
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return -1;
            return hour * 60 + minute;
        } catch (Exception e) {
            return -1;
        }
    }

    // -------------------------------------------------------------------- misc

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
        if ("doh".equals(fallback)) {
            return "encrypted DNS (" + hostOf(dohUrl) + ")";
        }
        if ("dot".equals(fallback)) {
            return "DNS-over-TLS (" + dotHost + ")";
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

}
