package com.dino.holeshield;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Curated blocklist presets, grouped so the UI can add or remove tracker and
 * malware/phishing coverage independently.
 *
 * The maximum preset combines ad, tracker, malware/phishing and dedicated
 * TV/streaming telemetry lists. Adult, piracy, gambling, dating and social
 * categories are separate opt-in lists. If a service breaks, add an exact
 * allow rule instead of disabling all filtering.
 */
final class Blocklists {

    /** Bump when the preset changes so existing installs can be migrated. */
    static final int PRESET_VERSION = 1;

    static final String LEGACY_DEFAULT =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";

    private static final String[] CORE = {
            // Core ad, tracker and malware coverage.
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/ultimate-onlydomains.txt",
            "https://big.oisd.nl/",
            "https://o0.pages.dev/Xtra/hosts.txt",
            "https://raw.githubusercontent.com/AdguardTeam/cname-trackers/master/data/combined_disguised_ads_justdomains.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/popupads-onlydomains.txt",
            "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
            "https://raw.githubusercontent.com/blocklistproject/Lists/master/ads.txt",
            "https://raw.githubusercontent.com/PolishFiltersTeam/KADhosts/master/KADhosts.txt"
    };

    /** Smart-TV, streaming-box and Android telemetry/tracker lists. */
    static final String[] TRACKERS = {
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/native.amazon-onlydomains.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/native.apple-onlydomains.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/native.huawei-onlydomains.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/native.lgwebos-onlydomains.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/native.oppo-realme-onlydomains.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/native.roku-onlydomains.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/native.samsung-onlydomains.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/native.tiktok-onlydomains.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/native.tiktok.extended-onlydomains.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/native.vivo-onlydomains.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/native.winoffice-onlydomains.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/native.xiaomi-onlydomains.txt",
            "https://raw.githubusercontent.com/AdguardTeam/cname-trackers/master/data/combined_disguised_trackers_justdomains.txt",
            "https://raw.githubusercontent.com/blocklistproject/Lists/master/tracking.txt",
            "https://raw.githubusercontent.com/blocklistproject/Lists/master/smart-tv.txt"
    };

    /** Malware, phishing and malicious-download lists. */
    static final String[] MALWARE = {
            "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-hosts.txt",
            "https://phishing.army/download/phishing_army_blocklist_extended.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/tif.mini-onlydomains.txt",
            "https://raw.githubusercontent.com/blocklistproject/Lists/master/malware.txt",
            "https://raw.githubusercontent.com/blocklistproject/Lists/master/phishing.txt",
            "https://raw.githubusercontent.com/blocklistproject/Lists/master/ransomware.txt"
    };

    /** Adult content. */
    static final String[] ADULT = {
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/nsfw-onlydomains.txt",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
            "https://raw.githubusercontent.com/ShadowWhisperer/BlockLists/master/Lists/Adult",
            "https://raw.githubusercontent.com/blocklistproject/Lists/master/porn.txt"
    };

    /** Copyright-infringing content and piracy services. */
    static final String[] PIRACY = {
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/anti.piracy-onlydomains.txt",
            "https://raw.githubusercontent.com/blocklistproject/Lists/master/piracy.txt",
            "https://raw.githubusercontent.com/olbat/ut1-blacklists/master/blacklists/warez/domains"
    };

    /** Gambling and betting sites. */
    static final String[] GAMBLING = {
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/gambling-onlydomains.txt",
            "https://raw.githubusercontent.com/ShadowWhisperer/BlockLists/master/Lists/Gambling",
            "https://raw.githubusercontent.com/blocklistproject/Lists/master/gambling.txt",
            "https://raw.githubusercontent.com/olbat/ut1-blacklists/master/blacklists/gambling/domains"
    };

    /** Dating sites and apps. */
    static final String[] DATING = {
            "https://raw.githubusercontent.com/ShadowWhisperer/BlockLists/master/Lists/Dating",
            "https://raw.githubusercontent.com/olbat/ut1-blacklists/master/blacklists/dating/domains"
    };

    /** Classic social networks. Messaging and streaming apps are not included. */
    static final String[] SOCIAL = {
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/social-onlydomains.txt",
            "https://raw.githubusercontent.com/olbat/ut1-blacklists/master/blacklists/social_networks/domains",
            "https://raw.githubusercontent.com/blocklistproject/Lists/master/facebook.txt",
            "https://raw.githubusercontent.com/blocklistproject/Lists/master/twitter.txt",
            "https://raw.githubusercontent.com/blocklistproject/Lists/master/tiktok.txt"
    };

    private static final String[] CLASSIC = {
            // Classic hosts lists, kept for additional unique coverage.
            "https://adaway.org/hosts.txt",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
            "https://someonewhocares.org/hosts/zero/hosts",
            LEGACY_DEFAULT
    };

    private static final String[] GAMBLING_LITE = {
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/gambling.mini-onlydomains.txt"
    };

    static final String[] ADS_MAX = concat(CORE, CLASSIC);
    static final String[] ADS_MEDIUM = {CORE[0], CORE[1], CORE[4], CORE[5]};
    static final String[] ADS_LITE = {LEGACY_DEFAULT};

    static final String[] TRACKERS_MAX = TRACKERS;
    static final String[] TRACKERS_MEDIUM = take(TRACKERS, 8);
    static final String[] TRACKERS_LITE = take(TRACKERS, 3);

    static final String[] MALWARE_MAX = MALWARE;
    static final String[] MALWARE_MEDIUM = take(MALWARE, 3);
    static final String[] MALWARE_LITE = take(MALWARE, 2);

    static final String[] ADULT_MAX = ADULT;
    static final String[] ADULT_MEDIUM = take(ADULT, 2);
    static final String[] ADULT_LITE = take(ADULT, 1);

    static final String[] PIRACY_MAX = PIRACY;
    static final String[] PIRACY_MEDIUM = take(PIRACY, 2);
    static final String[] PIRACY_LITE = take(PIRACY, 1);

    static final String[] GAMBLING_MAX = GAMBLING;
    static final String[] GAMBLING_MEDIUM = take(GAMBLING, 2);
    static final String[] GAMBLING_LITE_TIER = GAMBLING_LITE;

    static final String[] DATING_MAX = DATING;
    static final String[] DATING_MEDIUM = take(DATING, 1);
    static final String[] DATING_LITE = take(DATING, 1);

    static final String[] SOCIAL_MAX = SOCIAL;
    static final String[] SOCIAL_MEDIUM = take(SOCIAL, 2);
    static final String[] SOCIAL_LITE = take(SOCIAL, 1);

    /**
     * Kept within the app's gravity cap. The lists overlap heavily; the
     * deduplicated total is capped at 1M domains rather than the sum of sizes.
     */
    static final String[] MAXIMUM = concat(CORE, TRACKERS, MALWARE, CLASSIC);

    /** Category keys that support Lite / Medium / Maximum levels. */
    static final String[] CATEGORY_KEYS = {
            "ads", "trackers", "malware", "adult",
            "piracy", "gambling", "dating", "social"
    };

    static final int CATEGORY_ADS = 0;
    static final int CATEGORY_TRACKERS = 1;
    static final int CATEGORY_MALWARE = 2;
    static final int CATEGORY_ADULT = 3;
    static final int CATEGORY_PIRACY = 4;
    static final int CATEGORY_GAMBLING = 5;
    static final int CATEGORY_DATING = 6;
    static final int CATEGORY_SOCIAL = 7;
    static final int CATEGORY_OTHER = 8;

    private Blocklists() { }

    /** Category array for a dashboard/runtime switch key. */
    static String[] byKey(String key) {
        if ("ads".equals(key)) return CORE;
        if ("trackers".equals(key)) return TRACKERS;
        if ("malware".equals(key)) return MALWARE;
        if ("adult".equals(key)) return ADULT;
        if ("piracy".equals(key)) return PIRACY;
        if ("gambling".equals(key)) return GAMBLING;
        if ("dating".equals(key)) return DATING;
        if ("social".equals(key)) return SOCIAL;
        return new String[0];
    }

    static String[] urlsFor(String key, String level) {
        String value = level == null ? "max" : level;
        if ("ads".equals(key)) {
            return "lite".equals(value) ? ADS_LITE : "medium".equals(value) ? ADS_MEDIUM : ADS_MAX;
        }
        if ("trackers".equals(key)) {
            return "lite".equals(value) ? TRACKERS_LITE : "medium".equals(value) ? TRACKERS_MEDIUM : TRACKERS_MAX;
        }
        if ("malware".equals(key)) {
            return "lite".equals(value) ? MALWARE_LITE : "medium".equals(value) ? MALWARE_MEDIUM : MALWARE_MAX;
        }
        if ("adult".equals(key)) {
            return "lite".equals(value) ? ADULT_LITE : "medium".equals(value) ? ADULT_MEDIUM : ADULT_MAX;
        }
        if ("piracy".equals(key)) {
            return "lite".equals(value) ? PIRACY_LITE : "medium".equals(value) ? PIRACY_MEDIUM : PIRACY_MAX;
        }
        if ("gambling".equals(key)) {
            return "lite".equals(value) ? GAMBLING_LITE_TIER : "medium".equals(value) ? GAMBLING_MEDIUM : GAMBLING_MAX;
        }
        if ("dating".equals(key)) {
            return "lite".equals(value) ? DATING_LITE : "medium".equals(value) ? DATING_MEDIUM : DATING_MAX;
        }
        if ("social".equals(key)) {
            return "lite".equals(value) ? SOCIAL_LITE : "medium".equals(value) ? SOCIAL_MEDIUM : SOCIAL_MAX;
        }
        return new String[0];
    }

    static String[] allUrls(String key) {
        String[] max = urlsFor(key, "max");
        String[] medium = urlsFor(key, "medium");
        String[] lite = urlsFor(key, "lite");
        if ("gambling".equals(key)) return concat(max, GAMBLING_LITE);
        return concat(max, medium, lite);
    }

    static String detectLevel(String text, String key) {
        if (hasAll(text, urlsFor(key, "max"))) return "max";
        if (hasAll(text, urlsFor(key, "medium"))) return "medium";
        if (hasAll(text, urlsFor(key, "lite"))) return "lite";
        return "max";
    }

    static String applyLevel(String text, String key, String level) {
        String base = removeAll(text, allUrls(key));
        if (level == null || "off".equals(level)) return base;
        Set<String> have = new LinkedHashSet<>(urls(base));
        StringBuilder out = new StringBuilder(base);
        for (String url : urlsFor(key, level)) {
            if (!have.add(url)) continue;
            if (out.length() > 0) out.append('\n');
            out.append(url);
        }
        return out.toString();
    }

    private static String[] take(String[] values, int count) {
        int n = Math.max(0, Math.min(count, values.length));
        String[] out = new String[n];
        System.arraycopy(values, 0, out, 0, n);
        return out;
    }

    static int categoryOf(String url) {
        if (contains(TRACKERS, url)) return CATEGORY_TRACKERS;
        if (contains(MALWARE, url)) return CATEGORY_MALWARE;
        if (contains(ADULT, url)) return CATEGORY_ADULT;
        if (contains(PIRACY, url)) return CATEGORY_PIRACY;
        if (contains(GAMBLING, url)) return CATEGORY_GAMBLING;
        if (contains(DATING, url)) return CATEGORY_DATING;
        if (contains(SOCIAL, url)) return CATEGORY_SOCIAL;
        if (contains(GAMBLING_LITE, url)) return CATEGORY_GAMBLING;
        if (contains(CORE, url) || contains(CLASSIC, url)) return CATEGORY_ADS;
        return CATEGORY_OTHER;
    }

    private static boolean contains(String[] values, String value) {
        if (value == null) return false;
        for (String item : values) {
            if (item.equals(value)) return true;
        }
        return false;
    }

    static int count() {
        return MAXIMUM.length;
    }

    /** Default source text for new installs and the "add all" action. */
    static String presetText() {
        StringBuilder out = new StringBuilder();
        out.append("# Hole Shield maximum blocklist preset\n");
        out.append("# Ads, trackers, malware/phishing and TV-telemetry lists.\n");
        out.append("# Adult, piracy, gambling, dating and social lists are opt-in.\n");
        out.append("# Tap \"Update now\" after changing this list.\n");
        for (String url : MAXIMUM) out.append(url).append('\n');
        return out.toString().trim();
    }

    /** Appends every preset URL that is not already configured. */
    static String mergeMissing(String text) {
        return mergeMissing(text, MAXIMUM, "Maximum ads, trackers, malware and telemetry preset");
    }

    /** Appends every URL from one category that is not already configured. */
    static String mergeMissing(String text, String[] category) {
        return mergeMissing(text, category, "Security list");
    }

    private static String mergeMissing(String text, String[] category, String label) {
        String current = text == null ? "" : text.trim();
        Set<String> have = new LinkedHashSet<>(urls(current));
        StringBuilder out = new StringBuilder(current);
        boolean added = false;
        for (String url : category) {
            if (have.contains(url)) continue;
            if (!added) {
                if (out.length() > 0) out.append('\n');
                out.append("# ").append(label).append(" (added by Hole Shield)");
                added = true;
            }
            out.append('\n').append(url);
        }
        return out.toString();
    }

    static boolean hasAll(String text) {
        return hasAll(text, MAXIMUM);
    }

    static boolean hasAll(String text, String[] category) {
        Set<String> have = new LinkedHashSet<>(urls(text));
        for (String url : category) {
            if (!have.contains(url)) return false;
        }
        return true;
    }

    /** Removes every URL from one category, preserving custom sources. */
    static String removeAll(String text, String[] category) {
        if (text == null || text.isEmpty()) return "";
        Set<String> drop = new LinkedHashSet<>();
        for (String url : category) drop.add(url);
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\\n")) {
            String value = line.trim();
            if (!value.isEmpty() && drop.contains(value)) continue;
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString().trim();
    }

    /** True when the old single StevenBlack default is still in use. */
    static boolean isLegacyDefault(String text) {
        List<String> urls = urls(text);
        return urls.size() == 1 && LEGACY_DEFAULT.equals(urls.get(0));
    }

    /** Non-comment source URLs in their original order. */
    static List<String> urls(String text) {
        ArrayList<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\\n")) {
            String value = line.trim();
            if (!value.isEmpty() && !value.startsWith("#")) out.add(value);
        }
        return out;
    }

    private static String[] concat(String[]... arrays) {
        int total = 0;
        for (String[] array : arrays) total += array.length;
        String[] out = new String[total];
        int index = 0;
        for (String[] array : arrays) {
            for (String value : array) out[index++] = value;
        }
        return out;
    }
}
