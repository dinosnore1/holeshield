package com.dino.holeshield;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Settings, rules, blocklist sources and query history as one JSON document.
 * Used by the in-app backup/restore actions; no storage permission is needed
 * because the activity uses the system document picker.
 */
final class Backup {

    private static final int FORMAT = 1;

    private Backup() { }

    static JSONObject export(Context c, Prefs p) throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", "Hole Shield");
        root.put("format", FORMAT);
        root.put("version", Device.versionName(c));
        root.put("exportedAt", System.currentTimeMillis());

        JSONObject s = new JSONObject();
        s.put("protectionWanted", p.protectionWanted);
        s.put("vpnDisclosureAccepted", p.vpnDisclosureAccepted);
        s.put("onboardingDone", true);
        s.put("startOnBoot", p.startOnBoot);
        s.put("tvSafeArea", p.tvSafeArea);
        s.put("theme", p.theme);
        s.put("updateChannel", p.updateChannel);
        s.put("lastUpdateCheck", p.lastUpdateCheck);
        s.put("fallback", p.fallback);
        s.put("dohUrl", p.dohUrl);
        s.put("dotHost", p.dotHost);
        s.put("dotPort", p.dotPort);
        s.put("localBlocking", p.localBlocking);
        s.put("blockSubdomains", p.blockSubdomains);
        s.put("capEnabled", p.capEnabled);
        s.put("blockCname", p.blockCname);
        s.put("forceDns", p.forceDns);
        s.put("blockedResponse", p.blockedResponse);
        s.put("blocklistUrls", p.blocklistUrls);
        s.put("blocklistPresetVersion", p.blocklistPresetVersion);
        s.put("lastUpdate", p.lastUpdate);
        s.put("adsDomains", p.adsDomains);
        s.put("trackerDomains", p.trackerDomains);
        s.put("malwareDomains", p.malwareDomains);
        s.put("adultDomains", p.adultDomains);
        s.put("piracyDomains", p.piracyDomains);
        s.put("gamblingDomains", p.gamblingDomains);
        s.put("datingDomains", p.datingDomains);
        s.put("socialDomains", p.socialDomains);
        s.put("listHealth", p.listHealth);
        s.put("customBlocked", p.customBlocked);
        s.put("customAllowed", p.customAllowed);
        s.put("clientRules", p.clientRules);
        s.put("blockingEnabled", p.blockingEnabled);
        s.put("blockingUntil", p.blockingUntil);
        s.put("scheduleEnabled", p.scheduleEnabled);
        s.put("scheduleStart", p.scheduleStart);
        s.put("scheduleEnd", p.scheduleEnd);
        s.put("categoryAds", p.categoryAds);
        s.put("categoryTrackers", p.categoryTrackers);
        s.put("categoryMalware", p.categoryMalware);
        s.put("categoryAdult", p.categoryAdult);
        s.put("categoryPiracy", p.categoryPiracy);
        s.put("categoryGambling", p.categoryGambling);
        s.put("categoryDating", p.categoryDating);
        s.put("categorySocial", p.categorySocial);
        s.put("levelAds", p.levelAds);
        s.put("levelTrackers", p.levelTrackers);
        s.put("levelMalware", p.levelMalware);
        s.put("levelAdult", p.levelAdult);
        s.put("levelPiracy", p.levelPiracy);
        s.put("levelGambling", p.levelGambling);
        s.put("levelDating", p.levelDating);
        s.put("levelSocial", p.levelSocial);
        s.put("safeSearch", p.safeSearch);
        s.put("webEnabled", p.webEnabled);
        s.put("webPort", p.webPort);
        s.put("webUsername", p.webUsername);
        s.put("webPasswordHash", p.webPasswordHash);
        s.put("lanDns", p.lanDns);
        s.put("lanDnsPort", p.lanDnsPort);
        root.put("settings", s);
        root.put("log", Stats.exportLog());
        return root;
    }

    /** Applies a backup and reloads gravity. Returns the restored log size. */
    static int restore(Context c, JSONObject root) throws Exception {
        if (root == null) throw new Exception("Empty backup");
        if (!root.has("settings")) throw new Exception("Not a Hole Shield backup");
        JSONObject s = root.getJSONObject("settings");
        Prefs p = Prefs.load(c);
        p.protectionWanted = s.optBoolean("protectionWanted", p.protectionWanted);
        p.vpnDisclosureAccepted = s.optBoolean("vpnDisclosureAccepted", true);
        p.onboardingDone = true;
        p.startOnBoot = s.optBoolean("startOnBoot", p.startOnBoot);
        p.tvSafeArea = s.optInt("tvSafeArea", p.tvSafeArea);
        p.theme = s.optString("theme", p.theme);
        p.updateChannel = s.optString("updateChannel", p.updateChannel);
        p.lastUpdateCheck = s.optLong("lastUpdateCheck", p.lastUpdateCheck);
        p.fallback = s.optString("fallback", p.fallback);
        p.dohUrl = s.optString("dohUrl", p.dohUrl);
        p.dotHost = s.optString("dotHost", p.dotHost);
        p.dotPort = s.optInt("dotPort", p.dotPort);
        p.localBlocking = s.optBoolean("localBlocking", p.localBlocking);
        p.blockSubdomains = s.optBoolean("blockSubdomains", p.blockSubdomains);
        p.capEnabled = s.optBoolean("capEnabled", p.capEnabled);
        p.blockCname = s.optBoolean("blockCname", p.blockCname);
        p.forceDns = s.optBoolean("forceDns", p.forceDns);
        p.blockedResponse = s.optString("blockedResponse", p.blockedResponse);
        p.blocklistUrls = s.optString("blocklistUrls", p.blocklistUrls);
        p.blocklistPresetVersion = s.optInt("blocklistPresetVersion",
                p.blocklistPresetVersion);
        p.lastUpdate = s.optLong("lastUpdate", p.lastUpdate);
        p.adsDomains = s.optInt("adsDomains", p.adsDomains);
        p.trackerDomains = s.optInt("trackerDomains", p.trackerDomains);
        p.malwareDomains = s.optInt("malwareDomains", p.malwareDomains);
        p.adultDomains = s.optInt("adultDomains", p.adultDomains);
        p.listHealth = s.optString("listHealth", p.listHealth);
        p.customBlocked = s.optString("customBlocked", p.customBlocked);
        p.customAllowed = s.optString("customAllowed", p.customAllowed);
        p.clientRules = s.optString("clientRules", p.clientRules);
        p.blockingEnabled = s.optBoolean("blockingEnabled", p.blockingEnabled);
        p.blockingUntil = s.optLong("blockingUntil", p.blockingUntil);
        p.scheduleEnabled = s.optBoolean("scheduleEnabled", p.scheduleEnabled);
        p.scheduleStart = s.optInt("scheduleStart", p.scheduleStart);
        p.scheduleEnd = s.optInt("scheduleEnd", p.scheduleEnd);
        p.categoryAds = s.optBoolean("categoryAds", p.categoryAds);
        p.categoryTrackers = s.optBoolean("categoryTrackers", p.categoryTrackers);
        p.categoryMalware = s.optBoolean("categoryMalware", p.categoryMalware);
        p.categoryAdult = s.optBoolean("categoryAdult", p.categoryAdult);
        p.categoryPiracy = s.optBoolean("categoryPiracy", p.categoryPiracy);
        p.categoryGambling = s.optBoolean("categoryGambling", p.categoryGambling);
        p.categoryDating = s.optBoolean("categoryDating", p.categoryDating);
        p.categorySocial = s.optBoolean("categorySocial", p.categorySocial);
        p.levelAds = s.optString("levelAds", p.levelAds);
        p.levelTrackers = s.optString("levelTrackers", p.levelTrackers);
        p.levelMalware = s.optString("levelMalware", p.levelMalware);
        p.levelAdult = s.optString("levelAdult", p.levelAdult);
        p.levelPiracy = s.optString("levelPiracy", p.levelPiracy);
        p.levelGambling = s.optString("levelGambling", p.levelGambling);
        p.levelDating = s.optString("levelDating", p.levelDating);
        p.levelSocial = s.optString("levelSocial", p.levelSocial);
        p.safeSearch = s.optBoolean("safeSearch", p.safeSearch);
        p.webEnabled = s.optBoolean("webEnabled", p.webEnabled);
        p.webPort = s.optInt("webPort", p.webPort);
        p.webUsername = s.optString("webUsername", p.webUsername);
        String hash = s.optString("webPasswordHash", "");
        if (!hash.isEmpty() && WebAuth.isHashed(hash)) {
            p.webPasswordHash = hash;
            p.webSessionEpoch++;
        }
        p.lanDns = s.optBoolean("lanDns", p.lanDns);
        p.lanDnsPort = s.optInt("lanDnsPort", p.lanDnsPort);
        p.save(c);

        JSONArray log = root.optJSONArray("log");
        Stats.importLog(c, log);
        Blocklist.reload(c);
        return log == null ? 0 : log.length();
    }
}
