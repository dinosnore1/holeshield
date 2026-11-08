package com.dino.holeshield;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Persistent desktop settings. Values live in a plain properties file under the
 * per-user application data directory, so they can be inspected and edited.
 */
final class DesktopPrefs {

    static final String DEFAULT_DOH = "https://cloudflare-dns.com/dns-query";
    static final String DEFAULT_PLAIN = "1.1.1.1";

    private final File file;
    private final Properties values = new Properties();
    private boolean loaded;

    DesktopPrefs(File dir) {
        this.file = new File(dir, "settings.properties");
    }

    synchronized void load() {
        if (loaded) return;
        loaded = true;
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                values.load(in);
            } catch (Exception ignored) { }
        }
        if (!values.containsKey("blocklistUrls")) {
            set("blocklistUrls", Blocklists.presetText());
        }
        if (!values.containsKey("dnsPort")) set("dnsPort", "53");
        if (!values.containsKey("webPort")) set("webPort", "8080");
        if (!values.containsKey("bindAddress")) set("bindAddress", "0.0.0.0");
        if (!values.containsKey("webBind")) set("webBind", "0.0.0.0");
        if (!values.containsKey("upstreamMode")) set("upstreamMode", "doh");
        if (!values.containsKey("dohUrl")) set("dohUrl", DEFAULT_DOH);
        if (!values.containsKey("plainUpstream")) set("plainUpstream", DEFAULT_PLAIN);
        if (!values.containsKey("theme")) set("theme", "system");
        if (!values.containsKey("blockedResponse")) set("blockedResponse", "0");
        if (!values.containsKey("blockSubdomains")) set("blockSubdomains", "true");
        if (!values.containsKey("blockCname")) set("blockCname", "true");
        if (!values.containsKey("categoryAds")) set("categoryAds", "true");
        if (!values.containsKey("categoryTrackers")) set("categoryTrackers", "true");
        if (!values.containsKey("categoryMalware")) set("categoryMalware", "true");
        if (!values.containsKey("categoryAdult")) set("categoryAdult", "false");
        if (!values.containsKey("categoryPiracy")) set("categoryPiracy", "false");
        if (!values.containsKey("categoryGambling")) set("categoryGambling", "false");
        if (!values.containsKey("categoryDating")) set("categoryDating", "false");
        if (!values.containsKey("categorySocial")) set("categorySocial", "false");
        if (!values.containsKey("categoryOther")) set("categoryOther", "true");
        if (!values.containsKey("capEnabled")) set("capEnabled", "true");
        String urls = blocklistUrls();
        for (String key : Blocklists.CATEGORY_KEYS) {
            String name = "level" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
            if (!values.containsKey(name)) set(name, Blocklists.detectLevel(urls, key));
        }
        if (!values.containsKey("filtering")) set("filtering", "true");
        if (!values.containsKey("pauseUntil")) set("pauseUntil", "0");
        if (!values.containsKey("launchAtLogin")) set("launchAtLogin", "false");
        if (!values.containsKey("closeToTray")) set("closeToTray", "true");
        if (!values.containsKey("logQueries")) set("logQueries", "true");
    }

    synchronized String get(String key, String fallback) {
        load();
        String value = values.getProperty(key);
        return value == null ? fallback : value;
    }

    synchronized int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, String.valueOf(fallback)).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    synchronized long getLong(String key, long fallback) {
        try {
            return Long.parseLong(get(key, String.valueOf(fallback)).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    synchronized boolean getBool(String key, boolean fallback) {
        String value = get(key, String.valueOf(fallback));
        return "true".equalsIgnoreCase(value) || "1".equals(value)
                || "yes".equalsIgnoreCase(value);
    }

    synchronized void set(String key, String value) {
        load();
        if (value == null) values.remove(key);
        else values.setProperty(key, value);
    }

    synchronized void setBool(String key, boolean value) {
        set(key, value ? "true" : "false");
    }

    synchronized void setInt(String key, int value) {
        set(key, String.valueOf(value));
    }

    synchronized String blocklistUrls() {
        return get("blocklistUrls", Blocklists.presetText());
    }

    synchronized String customBlocked() {
        return get("customBlocked", "");
    }

    synchronized String customAllowed() {
        return get("customAllowed", "");
    }

    synchronized String clientRules() {
        return get("clientRules", "");
    }

    synchronized boolean categoryEnabled(String key) {
        String name = "category" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
        return getBool(name, false);
    }

    synchronized String categoryLevel(String key) {
        String name = "level" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
        return get(name, Blocklists.detectLevel(blocklistUrls(), key));
    }

    synchronized void setCategoryLevel(String key, String level) {
        String name = "level" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
        set(name, level == null ? "max" : level);
    }

    synchronized int categoryMask() {
        int mask = 0;
        if (getBool("categoryAds", true)) mask |= Gravity.ADS;
        if (getBool("categoryTrackers", true)) mask |= Gravity.TRACKERS;
        if (getBool("categoryMalware", true)) mask |= Gravity.MALWARE;
        if (getBool("categoryAdult", false)) mask |= Gravity.ADULT;
        if (getBool("categoryPiracy", false)) mask |= Gravity.PIRACY;
        if (getBool("categoryGambling", false)) mask |= Gravity.GAMBLING;
        if (getBool("categoryDating", false)) mask |= Gravity.DATING;
        if (getBool("categorySocial", false)) mask |= Gravity.SOCIAL;
        if (getBool("categoryOther", true)) mask |= Gravity.OTHER;
        return mask;
    }

    synchronized boolean blockingActive() {
        if (!getBool("filtering", true)) return false;
        long until = getLong("pauseUntil", 0L);
        if (until < 0) return false;
        if (until > 0 && System.currentTimeMillis() >= until) {
            set("pauseUntil", "0");
            save();
            return true;
        }
        return until == 0;
    }

    synchronized void pause(long seconds) {
        if (seconds <= 0) set("pauseUntil", "-1");
        else set("pauseUntil", String.valueOf(System.currentTimeMillis() + seconds * 1000L));
        save();
    }

    synchronized void resume() {
        set("pauseUntil", "0");
        save();
    }

    synchronized long pauseRemainingSeconds() {
        long until = getLong("pauseUntil", 0L);
        if (until <= 0) return until < 0 ? -1 : 0;
        long left = (until - System.currentTimeMillis()) / 1000L;
        return Math.max(0, left);
    }

    synchronized int blockedResponseMode() {
        try {
            int mode = Integer.parseInt(get("blockedResponse", "0"));
            return mode < 0 || mode > 2 ? 0 : mode;
        } catch (Exception e) {
            return 0;
        }
    }

    synchronized String blockedResponseLabel() {
        int mode = blockedResponseMode();
        return mode == 1 ? "NXDOMAIN" : mode == 2 ? "NODATA" : "0.0.0.0";
    }

    synchronized String upstreamLabel() {
        if ("plain".equals(get("upstreamMode", "doh"))) {
            return "Plain DNS · " + get("plainUpstream", DEFAULT_PLAIN);
        }
        return "Encrypted DoH · " + hostOf(get("dohUrl", DEFAULT_DOH));
    }

    private static String hostOf(String url) {
        try {
            String value = url.replaceFirst("^https?://", "");
            int slash = value.indexOf('/');
            return slash < 0 ? value : value.substring(0, slash);
        } catch (Exception e) {
            return url;
        }
    }

    synchronized boolean hasWebPassword() {
        return !get("webPassword", "").isEmpty();
    }

    synchronized String webUsername() {
        return get("webUsername", "");
    }

    synchronized String webPassword() {
        return get("webPassword", "");
    }

    synchronized void setWebPassword(String plain) {
        set("webPassword", WebAuth.hash(plain));
        save();
    }

    synchronized void save() {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        Path target = file.toPath();
        Path temp = new File(parent, file.getName() + ".tmp").toPath();
        try (OutputStream out = new FileOutputStream(temp.toFile())) {
            values.store(out, "Hole Shield desktop settings");
        } catch (Exception ignored) {
            return;
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) { }
        }
    }

    File file() {
        return file;
    }
}
