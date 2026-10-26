package com.dino.holeshield;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Curated blocklist presets.
 *
 * The maximum preset combines aggressively maintained ad/tracker lists with
 * dedicated TV and streaming-platform telemetry lists. It is intentionally
 * larger than the old single-list default; if a service breaks, add an exact
 * allow rule instead of disabling all filtering.
 */
final class Blocklists {

    /** Bump when the preset changes so existing installs can be migrated. */
    static final int PRESET_VERSION = 1;

    static final String LEGACY_DEFAULT =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";

    /**
     * Kept within the app's gravity cap. The lists overlap heavily, so the
     * deduplicated total is around 500k domains rather than the sum of sizes.
     */
    static final String[] MAXIMUM = {
            // Core ad, tracker and malware coverage.
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/ultimate-onlydomains.txt",
            "https://big.oisd.nl/",
            "https://o0.pages.dev/Xtra/hosts.txt",
            "https://raw.githubusercontent.com/AdguardTeam/cname-trackers/master/data/combined_disguised_ads_justdomains.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/popupads-onlydomains.txt",

            // Smart-TV, streaming-box and Android telemetry.
            "https://raw.githubusercontent.com/Perflyst/PiHoleBlocklist/master/SmartTV.txt",
            "https://raw.githubusercontent.com/Perflyst/PiHoleBlocklist/master/android-tracking.txt",
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

            // Classic hosts lists, kept for additional unique coverage.
            "https://adaway.org/hosts.txt",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
            "https://someonewhocares.org/hosts/zero/hosts",
            LEGACY_DEFAULT
    };

    private Blocklists() { }

    static int count() {
        return MAXIMUM.length;
    }

    /** Default source text for new installs and the "add all" action. */
    static String presetText() {
        StringBuilder out = new StringBuilder();
        out.append("# Pi-hole TV maximum blocklist preset\n");
        out.append("# Aggressive ad, tracker, malware and TV-telemetry lists.\n");
        out.append("# Tap \"Update now\" after changing this list.\n");
        for (String url : MAXIMUM) out.append(url).append('\n');
        return out.toString().trim();
    }

    /** Appends every preset URL that is not already configured. */
    static String mergeMissing(String text) {
        String current = text == null ? "" : text.trim();
        Set<String> have = new LinkedHashSet<>(urls(current));
        StringBuilder out = new StringBuilder(current);
        boolean added = false;
        for (String url : MAXIMUM) {
            if (have.contains(url)) continue;
            if (!added) {
                if (out.length() > 0) out.append('\n');
                out.append("# Maximum ad/tracker preset (added by Pi-hole TV)");
                added = true;
            }
            out.append('\n').append(url);
        }
        return out.toString();
    }

    static boolean hasAll(String text) {
        Set<String> have = new LinkedHashSet<>(urls(text));
        for (String url : MAXIMUM) {
            if (!have.contains(url)) return false;
        }
        return true;
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
}
