package com.dino.holeshield;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Headless checks for the desktop core. Run with tools/test.sh. */
public final class DesktopTest {

    private static int checks;
    private static File dir;

    public static void main(String[] args) throws Exception {
        dir = Files.createTempDirectory("holeshield-desktop-test").toFile();
        try {
            packets();
            parsers();
            gravity();
            filter();
            updates();
            updater();
            stats();
            motion();
            dns();
            upstream();
            web();
            companion();
            persistence();
            System.out.println("OK — " + checks + " desktop checks passed");
        } catch (Throwable e) {
            e.printStackTrace();
            delete(dir);
            System.exit(1);
        }
        delete(dir);
        System.exit(0);
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + name);
    }

    private static void eq(Object expected, Object actual, String name) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("FAILED: " + name + " expected=" + expected
                    + " actual=" + actual);
        }
    }

    // ------------------------------------------------------------------ packets

    private static void packets() {
        byte[] query = DnsPacket.buildQuery(0x1234, "Example.COM", DnsPacket.TYPE_A);
        check(query != null, "build query");
        DnsPacket.Query parsed = DnsPacket.parseQuery(query, query.length);
        check(parsed != null, "parse query");
        eq("example.com", parsed.name, "query name lowercased");
        eq(0x1234, parsed.id, "query id");
        eq(DnsPacket.TYPE_A, parsed.type, "query type");
        check(parsed.recursionDesired, "recursion desired");

        byte[] response = query.clone();
        response[2] |= (byte) 0x80;
        check(DnsPacket.parseQuery(response, response.length) == null,
                "responses are not queries");

        byte[] blocked = DnsPacket.blockedReply(parsed, 0);
        eq(0, DnsPacket.rcode(blocked, blocked.length), "sinkhole rcode");
        List<InetAddress> addresses = DnsPacket.addresses(blocked, blocked.length);
        eq(1, addresses.size(), "sinkhole answer");
        eq("0.0.0.0", addresses.get(0).getHostAddress(), "sinkhole address");

        byte[] nx = DnsPacket.blockedReply(parsed, 1);
        eq(3, DnsPacket.rcode(nx, nx.length), "NXDOMAIN mode");
        byte[] nodata = DnsPacket.blockedReply(parsed, 2);
        eq(0, DnsPacket.rcode(nodata, nodata.length), "NODATA mode");
        eq(0, DnsPacket.addresses(nodata, nodata.length).size(), "NODATA answer");

        byte[] aaaa = DnsPacket.buildQuery(1, "example.com", DnsPacket.TYPE_AAAA);
        DnsPacket.Query aaaaQuery = DnsPacket.parseQuery(aaaa, aaaa.length);
        byte[] aaaaReply = DnsPacket.blockedReply(aaaaQuery, 0);
        eq(0, DnsPacket.addresses(aaaaReply, aaaaReply.length).size(),
                "AAAA sinkhole is NODATA");
        eq("A", DnsPacket.typeName(1), "type A name");
        eq("HTTPS", DnsPacket.typeName(65), "type HTTPS name");
    }

    private static void parsers() {
        eq("ads.example.com", HostsParser.parseLine("0.0.0.0 ads.example.com"),
                "hosts line");
        eq("tracker.example.com",
                HostsParser.parseLine("||tracker.example.com^$third-party"),
                "adblock line");
        eq("wild.example.com", HostsParser.parseLine("*.wild.example.com"),
                "wildcard line");
        eq("", HostsParser.parseLine("# comment"), "comment");
        eq("", HostsParser.parseLine("127.0.0.1"), "ip only");
        eq("", HostsParser.parseLine("not a domain"), "space");
        eq("sub.example.com", HostsParser.normalizeDomain(" Sub.Example.COM. "),
                "normalize");
        check(HostsParser.isIpLiteral("1.2.3.4"), "ipv4 literal");
        check(HostsParser.isIpLiteral("fe80::1"), "ipv6 literal");
        check(!HostsParser.isIpLiteral("example.com"), "domain is not ip");
    }

    private static void gravity() {
        eq(Gravity.hash("Example.com"), Gravity.hash("example.com"),
                "hash is case insensitive");
        long[] values = {5, 3, 3, 9, 1};
        long[] clean = Gravity.clean(values);
        eq(4, clean.length, "clean deduplicates");
        eq(1L, clean[0], "clean sorts");
        Gravity g = Gravity.build(new long[][]{{1, 3, 5}, {3, 7}},
                new int[]{Gravity.ADS, Gravity.TRACKERS}, 100);
        eq(4, g.size(), "gravity merges");
        eq(4, g.activeSize(Gravity.ALL), "all categories count");
        eq(3, g.activeSize(Gravity.ADS), "ads-only count");
        eq(2, g.activeSize(Gravity.TRACKERS), "trackers-only count");
        eq(4, g.activeSize(Gravity.ADS | Gravity.TRACKERS),
                "shared domain counts once");
        eq(0, g.activeSize(Gravity.PIRACY), "disabled categories count nothing");
        int[] primary = g.primaryCounts(
                new int[]{Gravity.MALWARE, Gravity.TRACKERS, Gravity.ADS}, Gravity.ALL);
        eq(0, primary[0], "no malware domains");
        eq(2, primary[1], "shared domains count under the higher category");
        eq(2, primary[2], "ads-only domains count for ads");
        eq(Gravity.ADS, g.flagsOf(1) & Gravity.ADS, "ads flag");
        eq(Gravity.ADS | Gravity.TRACKERS, g.flagsOf(3),
                "overlapping category flags");
        Gravity restored = Gravity.fromHashes(new long[]{1, 3},
                new int[]{Gravity.ADS, Gravity.MALWARE});
        eq(Gravity.MALWARE, restored.flagsOf(3), "fromHashes keeps flags");
        Gravity multi = Gravity.build(new long[][]{{1}, {1}},
                new int[]{Gravity.PIRACY, Gravity.SOCIAL}, 100);
        eq(Gravity.PIRACY | Gravity.SOCIAL, multi.flagsOf(1),
                "new categories merge");
        check(Blocklists.byKey("adult").length > 0, "adult list preset");
        check(Blocklists.byKey("piracy").length > 0, "piracy list preset");
        check(Blocklists.byKey("gambling").length > 0, "gambling list preset");
        check(Blocklists.byKey("dating").length > 0, "dating list preset");
        check(Blocklists.byKey("social").length > 0, "social list preset");
        for (String key : Blocklists.CATEGORY_KEYS) {
            check(Blocklists.urlsFor(key, "lite").length > 0,
                    key + " lite tier");
            check(Blocklists.urlsFor(key, "medium").length
                            >= Blocklists.urlsFor(key, "lite").length,
                    key + " medium tier covers lite");
            check(Blocklists.urlsFor(key, "max").length
                            >= Blocklists.urlsFor(key, "medium").length,
                    key + " maximum tier covers medium");
            check(Blocklists.allUrls(key).length
                            >= Blocklists.urlsFor(key, "max").length,
                    key + " URL set covers maximum");
        }
        String lite = Blocklists.applyLevel(Blocklists.presetText(), "trackers", "lite");
        check(Blocklists.detectLevel(lite, "trackers").equals("lite"),
                "tracker lite level round-trips");
        String max = Blocklists.applyLevel(lite, "trackers", "max");
        check(Blocklists.detectLevel(max, "trackers").equals("max"),
                "tracker maximum level round-trips");
        check(Blocklists.byKey("ads").length >= 13, "ads preset expanded");
        check(Blocklists.TRACKERS.length >= 21, "tracker preset expanded");
        check(Blocklists.MALWARE.length >= 18, "malware preset expanded");
        check(Blocklists.ADULT.length >= 5, "adult preset expanded");
        check(Blocklists.PIRACY.length >= 5, "piracy preset expanded");
        check(Blocklists.GAMBLING.length >= 5, "gambling preset expanded");
        check(Blocklists.DATING.length >= 3, "dating preset expanded");
        check(Blocklists.SOCIAL.length >= 7, "social preset expanded");
        String gamblingMedium = Blocklists.applyLevel("", "gambling", "medium");
        check(Blocklists.detectLevel(gamblingMedium, "gambling").equals("medium"),
                "gambling medium level round-trips");
        check(Blocklists.categoryOf(Blocklists.GAMBLING_MEDIUM_TIER[0])
                        == Blocklists.CATEGORY_GAMBLING,
                "gambling medium list maps to the gambling category");
    }

    private static void filter() {
        File home = new File(dir, "filter");
        home.mkdirs();
        DesktopPrefs prefs = new DesktopPrefs(home);
        prefs.load();
        check(prefs.getBool("gravityCapEnabled", true), "gravity cap enabled by default");
        eq(1510000, prefs.getInt("gravityCap", 1510000), "gravity cap default");
        FilterEngine filter = new FilterEngine(prefs, home);
        filter.load();
        check(filter.gravityCount() > 100, "seed gravity loaded");
        check(filter.check("doubleclick.net").blocked, "seed domain blocked");
        eq("Ads", filter.check("doubleclick.net").category,
                "seed domain keeps the ads category flag");
        check(filter.check("bifrost-api.hotstar.com").allowed,
                "Hotstar tracking endpoint is allowed");
        check(filter.check("ads.doubleclick.net").blocked, "seed subdomain blocked");
        check(!filter.check("example.com").blocked, "unlisted domain allowed");
        check(filter.check("localhost").blocked == false, "single label ignored");

        check(filter.addRule(true, FilterEngine.EXACT, "doubleclick.net"),
                "add allow rule");
        check(filter.check("doubleclick.net").allowed, "allow rule wins");
        check(filter.removeRule(true, FilterEngine.EXACT, "doubleclick.net"),
                "remove allow rule");
        check(filter.check("doubleclick.net").blocked, "blocking returns");

        check(filter.addRule(false, FilterEngine.WILDCARD, "*.ads.example"),
                "add wildcard rule");
        check(filter.check("x.ads.example").blocked, "wildcard matches");
        check(!filter.check("ads.example").blocked, "wildcard does not match parent");
        check(filter.removeRule(false, FilterEngine.WILDCARD, "*.ads.example"),
                "remove wildcard");

        check(filter.addRule(false, FilterEngine.REGEX, "tracker[0-9]+\\.example"),
                "add regex rule");
        check(filter.check("tracker42.example").blocked, "regex matches");
        check(filter.removeRule(false, FilterEngine.REGEX, "tracker[0-9]+\\.example"),
                "remove regex");

        prefs.setBool("categoryAds", false);
        filter.reloadRules();
        check(!filter.check("doubleclick.net").blocked,
                "category switch disables ads");
        prefs.setBool("categoryAds", true);
        filter.reloadRules();
        check(filter.check("doubleclick.net").blocked, "category switch restores");
    }

    private static void updater() throws Exception {
        eq("macos-pkg", Updater.assetKey("Mac OS X", false, false), "macOS installer key");
        eq("windows-msi", Updater.assetKey("Windows 11", false, false), "Windows installer key");
        eq("linux-deb", Updater.assetKey("Linux", true, false), "Debian installer key");
        eq("linux-rpm", Updater.assetKey("Linux", false, true), "RPM installer key");
        eq("", Updater.assetKey("Linux", false, false), "no supported package manager");
        eq("", Updater.assetKey("FreeBSD", true, true), "unknown system has no installer");

        check(Updater.compareVersions("2.3.5", "2.3.6") < 0, "patch release is newer");
        check(Updater.compareVersions("2.10.0", "2.9.9") > 0, "numeric version order");
        check(Updater.compareVersions("2.3.5", "2.3.5") == 0, "same version");

        String manifest = "{\"desktop\":{\"version\":\"2.3.6\",\"notes\":\"Fixes\","
                + "\"assets\":{\"linux-deb\":{\"name\":\"hs.deb\",\"url\":\"https://x/hs.deb\","
                + "\"sha256\":\"abc\",\"size\":12}}}}";
        Updater.Release release = Updater.parse(manifest, "linux-deb");
        eq("2.3.6", release.version, "manifest version");
        eq("Fixes", release.notes, "manifest notes");
        eq("hs.deb", release.name, "manifest asset name");
        eq("https://x/hs.deb", release.url, "manifest asset url");
        eq(12L, release.size, "manifest asset size");
        Updater.Release missing = Updater.parse(manifest, "windows-msi");
        eq("", missing.url, "missing platform asset is empty");
        boolean rejected = false;
        try {
            Updater.parse("{\"stable\":{}}", "linux-deb");
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        check(rejected, "manifest without a desktop release is rejected");

        eq("Hole-Shield-2.3.6.pkg", Updater.safeName("Hole-Shield-2.3.6.pkg"),
                "plain installer name kept");
        eq("hs.deb", Updater.safeName("../../etc/hs.deb"), "path separators stripped");
        eq("hs-rm--rf.deb", Updater.safeName("hs;rm -rf.deb"), "shell metacharacters replaced");
        eq("", Updater.safeName(".."), "parent directory name refused");
        eq("", Updater.safeName(null), "missing name is empty");

        File file = new File(dir, "hash-check.txt");
        Files.write(file.toPath(), "hole shield".getBytes(StandardCharsets.UTF_8));
        String hash = Updater.sha256Hex(file);
        eq("a62812623d66c8db3996ad2f355fe00f04e938f6f7804ee1a2b00a0a16846d8e", hash, "sha256 digest");
        check(Updater.matches(hash, hash.toUpperCase()), "checksum compare ignores case");
        check(!Updater.matches("", hash), "empty checksum never matches");
        check(!Updater.matches(hash, hash.substring(1) + "0"), "wrong checksum rejected");

        File macPkg = new File(dir, "it's here/Hole Shield.pkg");
        String mac = Updater.macScript(42, macPkg,
                "/Applications/Hole Shield.app", new File(dir, "log.txt"));
        String quotedPkg = macPkg.getAbsolutePath().replace("'", "'\\''");
        check(mac.contains("PKG='" + quotedPkg + "'"), "macOS script quotes apostrophes");
        check(mac.contains("while kill -0 42"), "macOS script waits for this process");
        check(mac.contains("/usr/sbin/installer -pkg '$PKG' -target /"), "macOS script runs the installer");
        check(mac.contains("/usr/bin/open \"$APP\""), "macOS script reopens the app");

        String windows = Updater.windowsScript(42, new File("C:\\it's\\Hole.msi"),
                "C:\\Program Files\\Hole Shield\\Hole Shield.exe", new File("C:\\log.txt"));
        check(windows.contains("it''s\\Hole.msi'"), "Windows script doubles apostrophes");
        check(windows.contains("msiexec.exe"), "Windows script uses msiexec");
        check(windows.contains("-Id $appPid"), "Windows script waits for this process");
        check(windows.contains("Start-Process -FilePath $exe"), "Windows script reopens the app");

        String linux = Updater.linuxScript(42, new File("/tmp/hs.deb"), "deb",
                "\"/opt/Hole Shield/bin/holeshield\"", new File("/tmp/log"));
        check(linux.contains("pkexec apt-get install -y \"$PKG\""), "Debian script uses apt");
        check(linux.contains("kill -0 42"), "Linux script waits for this process");
        check(linux.contains("nohup \"/opt/Hole Shield/bin/holeshield\""), "Linux script reopens the app");
        String rpm = Updater.linuxScript(7, new File("/tmp/hs.rpm"), "rpm", "app", new File("/tmp/log"));
        check(rpm.contains("pkexec rpm -U --replacepkgs"), "RPM script uses rpm");
    }

    private static void updates() throws Exception {
        File home = new File(dir, "updates");
        home.mkdirs();
        DesktopPrefs prefs = new DesktopPrefs(home);
        prefs.load();
        FilterEngine filter = new FilterEngine(prefs, home);
        filter.load();

        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
                .create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var fetching = new java.util.concurrent.CountDownLatch(1);
        var respond = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/list.txt", exchange -> {
            fetching.countDown();
            try {
                respond.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = ("# local test list\n0.0.0.0 local-block.example\n"
                    + "0.0.0.0 sub.local-block.example\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        String listUrl = "http://127.0.0.1:" + port + "/list.txt";
        prefs.set("blocklistUrls", listUrl);
        filter.updateBlocklists(null, null);
        try {
            check(fetching.await(5, java.util.concurrent.TimeUnit.SECONDS), "list server received request");
            eq(ListFetchProgress.State.FETCHING, filter.fetchProgress().get(listUrl),
                    "only the active list is fetching");
        } finally {
            respond.countDown();
        }
        for (int i = 0; i < 100 && filter.isUpdating(); i++) {
            Thread.sleep(50);
        }
        check(!filter.isUpdating(), "list update finished");
        check(filter.fetchProgress().isEmpty(), "transient list progress cleared");
        check(filter.lastError().isEmpty(), "list update succeeded: " + filter.lastError());
        check(filter.check("local-block.example").blocked, "downloaded domain blocked");
        check(filter.check("x.local-block.example").blocked, "downloaded subdomain blocked");
        eq(1, filter.health().size(), "list health recorded");
        check(filter.health().get(0).ok, "list health ok");
        check(new File(home, "gravity.bin").isFile(), "gravity database saved");
        server.stop(0);

        FilterEngine reloaded = new FilterEngine(prefs, home);
        reloaded.load();
        check(reloaded.check("local-block.example").blocked,
                "gravity database reloaded");
    }

    // ------------------------------------------------------------------- motion

    private static void motion() {
        eq(0.0, Motion.EASE_OUT.apply(0), "ease out starts at zero");
        eq(1.0, Motion.EASE_OUT.apply(1), "ease out ends at one");
        check(Motion.EASE_OUT.apply(0.5) > 0.5, "ease out leads early");
        eq(0.0, Motion.EASE_IN_OUT.apply(0), "ease in-out starts at zero");
        eq(1.0, Motion.EASE_IN_OUT.apply(1), "ease in-out ends at one");
        double[] value = {-1};
        Motion.Tween tween = Motion.tween(3, 9, 0, Motion.EASE_OUT,
                v -> value[0] = v);
        eq(9.0, value[0], "zero duration tween applies target");
        tween.stop();
        Motion.tween(2, 2, 200, Motion.EASE_OUT, v -> value[0] = v);
        eq(2.0, value[0], "tween without distance applies target");
    }

    private static void stats() throws Exception {
        File home = new File(dir, "stats");
        StatsStore store = new StatsStore(home);
        store.load();
        long now = System.currentTimeMillis();
        store.record(new StatsStore.Entry(now, "192.168.2.10", "ads.example.com",
                DnsPacket.TYPE_A, "BLOCKED", "gravity", 0, 2, "ads.example.com",
                "Ads", true, false));
        store.record(new StatsStore.Entry(now, "192.168.2.10", "example.com",
                DnsPacket.TYPE_A, "FORWARDED", "DoH", 0, 12, "", "", false, false));
        store.record(new StatsStore.Entry(now, "192.168.2.11", "ads.example.com",
                DnsPacket.TYPE_AAAA, "CACHED", "cache", 0, 0, "", "", false, true));
        eq(3L, store.queries(), "total queries");
        eq(1L, store.blocked(), "blocked queries");
        eq(1L, store.cached(), "cached queries");
        eq(1L, store.forwarded(), "forwarded queries");
        eq(2L, store.allowed(), "forwarded and cached outcomes count as allowed");
        eq(2, store.clientCount(), "client count");
        eq(2, store.uniqueDomains(), "unique domains");
        eq("ads.example.com", store.topDomains(true, 1).get(0).key,
                "top blocked domain");
        eq("Ads", store.categoryCounts().get(0).key, "category count");
        eq(24, store.chart("24h").size(), "24h chart");
        eq(30, store.chart("live").size(), "live chart");
        eq(7, store.chart("week").size(), "week chart");
        eq(30, store.chart("month").size(), "month chart");
        check(store.exportCsv(false, false, "").contains("ads.example.com"),
                "CSV export");
        check(store.exportJson(false, false, "").toString().contains("\"blocked\":true"),
                "JSON export");
        store.save();
        StatsStore loaded = new StatsStore(home);
        loaded.load();
        eq(3L, loaded.queries(), "persisted queries");
        eq(1L, loaded.blocked(), "persisted blocked");
        eq(2L, loaded.allowed(), "persisted allowed");
        eq(2, loaded.uniqueDomains(), "persisted domains");
        eq(2, loaded.clientCount(), "persisted clients");
        eq(3L, loaded.todayQueries(), "today queries");
        eq(3, loaded.log(10).size(), "query log tail reloaded");
        eq("ads.example.com", loaded.log(1).get(0).domain,
                "newest query is first");

        File windowDir = new File(dir, "stats-window");
        StatsStore window = new StatsStore(windowDir);
        window.load();
        long day = 24L * 60L * 60L * 1000L;
        window.record(new StatsStore.Entry(now - 29L * day, "192.168.2.10",
                "old.example.com", DnsPacket.TYPE_A, "BLOCKED", "gravity", 0, 1,
                "", "Ads", true, false));
        window.record(new StatsStore.Entry(now - 31L * day, "192.168.2.10",
                "expired.example.com", DnsPacket.TYPE_A, "BLOCKED", "gravity", 0, 1,
                "", "Ads", true, false));
        window.save();
        StatsStore windowLoaded = new StatsStore(windowDir);
        windowLoaded.load();
        eq(1L, windowLoaded.queries(), "31-day-old query expires");
        eq(1L, windowLoaded.blocked(), "31-day-old block expires");
        eq(0L, windowLoaded.todayQueries(), "expired entry is not today");
        File boundaryDir = new File(dir, "stats-rolling-boundary");
        StatsStore boundary = new StatsStore(boundaryDir);
        boundary.load();
        boundary.record(new StatsStore.Entry(now - 30L * day + 60000L,
                "192.168.2.10", "boundary.example.com", DnsPacket.TYPE_A,
                "BLOCKED", "gravity", 0, 1, "", "Ads", true, false));
        boundary.record(new StatsStore.Entry(now - 30L * day - 60000L,
                "192.168.2.10", "expired-boundary.example.com", DnsPacket.TYPE_A,
                "BLOCKED", "gravity", 0, 1, "", "Ads", true, false));
        boundary.record(new StatsStore.Entry(now - 30L * day + 60000L,
                "client", "allowed-boundary.example.com", 1,
                "ALLOWED", "DoH", 3, 1, "", "", false, false));
        boundary.record(new StatsStore.Entry(now - 30L * day + 60000L,
                "client", "failure-boundary.example.com", 1,
                "FORWARDED", "DoH", 2, 1, "", "", false, false));
        boundary.record(new StatsStore.Entry(now - 30L * day + 60000L,
                "client", "error-boundary.example.com", 1,
                "ERROR", "DoH", 2, 1, "", "", false, false));
        boundary.save();
        File boundaryLog = new File(boundaryDir, "querylog");
        String cutoffDay = Retention.dayKey(now - 30L * day);
        // Inject a row that was once retained but has since aged out.
        long expiredAt = now - 30L * day - 1000;
        if (!Retention.dayKey(expiredAt).equals(cutoffDay)) expiredAt = now - 31L * day;
        File oldPartition = new File(boundaryLog, Retention.dayKey(expiredAt) + ".tsv");
        Files.writeString(oldPartition.toPath(),
                expiredAt + "\tclient\tstartup-expired.example.com\t1\tBLOCKED\tgravity\t0\t1\t\tAds\t1\t0\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        StatsStore boundaryLoaded = new StatsStore(boundaryDir);
        boundaryLoaded.load();
        eq(4L, boundaryLoaded.queries(),
                "rolling boundary retains only unexpired queries after restart");
        eq(1L, boundaryLoaded.allowed(), "allowed includes NXDOMAIN but excludes failures and blocks");
        check(!oldPartition.exists()
                        || !Files.readString(oldPartition.toPath()).contains("startup-expired.example.com"),
                "startup physically removes expired rows, including partial days");
        String expiredKey = new java.text.SimpleDateFormat("yyyy-MM-dd",
                java.util.Locale.US).format(new java.util.Date(now - 31L * day));
        check(!new File(new File(windowDir, "querylog"), expiredKey + ".tsv").exists(),
                "expired query log day deleted");
        eq(1, windowLoaded.log(10).size(), "expired query log entry gone");

        File pagedDir = new File(dir, "stats-paged");
        StatsStore paged = new StatsStore(pagedDir);
        paged.load();
        for (int i = 0; i < 21500; i++) {
            paged.record(new StatsStore.Entry(now + i, "192.168.2.10",
                    "q" + i + ".example.com", DnsPacket.TYPE_A, "FORWARDED",
                    "DoH", 0, 1, "", "", false, false));
        }
        paged.save();
        StatsStore pagedLoaded = new StatsStore(pagedDir);
        pagedLoaded.load();
        eq(21500L, pagedLoaded.queries(), "all burst rows count after restart");
        List<StatsStore.Entry> olderPage = pagedLoaded.log(2500);
        eq(2500, olderPage.size(), "paged log reaches older days");
        eq("q21499.example.com", olderPage.get(0).domain,
                "paged log starts with newest");
        eq("q19000.example.com", olderPage.get(2499).domain,
                "paged log ends with oldest");
        eq(StatsStore.EXPORT_MAX, pagedLoaded.log(100000).size(),
                "export snapshot stays bounded");
        eq("q0.example.com", pagedLoaded.logPage(21499, 1, e -> true).get(0).domain,
                "paging reaches past the export cap");
        eq("q1.example.com", pagedLoaded.logPage(1, 2,
                e -> e.domain.equals("q1.example.com") || e.domain.equals("q2.example.com"))
                .get(0).domain, "paging applies filters before offset");
        File restoredDir = new File(dir, "stats-restored");
        File restoredLog = new File(restoredDir, "querylog");
        check(restoredLog.mkdirs(), "create desktop backup restore directory");
        File[] partitions = new File(pagedDir, "querylog").listFiles();
        for (File partition : partitions) {
            Files.copy(partition.toPath(), new File(restoredLog, partition.getName()).toPath());
        }
        StatsStore restored = new StatsStore(restoredDir);
        restored.load();
        eq(21500L, restored.queries(), "desktop partition backup restores without summary file");
        eq("q0.example.com", restored.logPage(21499, 1, e -> true).get(0).domain,
                "desktop partition backup preserves older history");

        File derivedDir = new File(dir, "stats-derived");
        derivedDir.mkdirs();
        String derivedToday = new java.text.SimpleDateFormat("yyyy-MM-dd",
                java.util.Locale.US).format(new java.util.Date(now));
        File derivedLog = new File(derivedDir, "querylog");
        derivedLog.mkdirs();
        Files.write(new File(derivedDir, "stats.txt").toPath(),
                ("V 2\nD " + derivedToday + " 999,999,999,999,999\n")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(new File(derivedLog, derivedToday + ".tsv").toPath(),
                ((now + "\t192.168.2.10\ta.example.com\t1\tBLOCKED\tgravity\t0\t1\t\tAds\t1\t0\n")
                        + (now + 1 + "\t192.168.2.10\tb.example.com\t1\tFORWARDED\tDoH\t0\t2\t\t\t0\t0\n"))
                        .getBytes(StandardCharsets.UTF_8));
        StatsStore derived = new StatsStore(derivedDir);
        derived.load();
        eq(2L, derived.queries(), "counters rebuild from retained records");
        eq(1L, derived.blocked(), "blocked count rebuilds from records");
        eq(1L, derived.forwarded(), "forwarded count rebuilds from records");

        File legacyDir = new File(dir, "stats-legacy");
        legacyDir.mkdirs();
        String yesterday = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date(now - day));
        Files.write(new File(legacyDir, "stats.txt").toPath(),
                ("V 1\nC 100,10,5,80,1\nD " + yesterday + " 10,3\n")
                        .getBytes(StandardCharsets.UTF_8));
        StatsStore legacy = new StatsStore(legacyDir);
        legacy.load();
        eq(0L, legacy.queries(), "legacy queries without retained rows are excluded");
        eq(0L, legacy.blocked(), "legacy blocks without retained rows are excluded");
        eq(0L, legacy.todayQueries(), "undated lifetime queries are not attributed to today");
        legacy.save();
        check(Files.readString(new File(legacyDir, "stats.txt").toPath())
                .contains("L 100,10,5,80,1"), "undated legacy totals preserved separately");
        StatsStore legacyAgain = new StatsStore(legacyDir);
        legacyAgain.load();
        eq(0L, legacyAgain.queries(), "legacy totals stay excluded after restart");
        eq(0L, legacyAgain.blocked(), "legacy blocks stay excluded after restart");

        File migrateDir = new File(dir, "stats-log-migrate");
        migrateDir.mkdirs();
        String todayKey = new java.text.SimpleDateFormat("yyyy-MM-dd",
                java.util.Locale.US).format(new java.util.Date(now));
        String yesterdayKey = new java.text.SimpleDateFormat("yyyy-MM-dd",
                java.util.Locale.US).format(new java.util.Date(now - day));
        Files.write(new File(migrateDir, "query_log.tsv").toPath(),
                ((now + "\t192.168.2.10\ttoday.example.com\t1\tBLOCKED\tgravity\t0\t1\t\tAds\t1\t0\n")
                        + (now - day + "\t192.168.2.10\tyesterday.example.com\t1\tFORWARDED\tDoH\t0\t9\t\t\t0\t0\n"))
                        .getBytes(StandardCharsets.UTF_8));
        StatsStore migrated = new StatsStore(migrateDir);
        migrated.load();
        check(!new File(migrateDir, "query_log.tsv").exists(),
                "legacy log file removed after migration");
        check(new File(new File(migrateDir, "querylog"), todayKey + ".tsv").isFile(),
                "legacy log split into today");
        check(new File(new File(migrateDir, "querylog"), yesterdayKey + ".tsv").isFile(),
                "legacy log split into yesterday");
        eq(2, migrated.log(10).size(), "legacy log entries reloaded");
        eq("today.example.com", migrated.log(1).get(0).domain,
                "legacy log keeps newest first");
        migrated.clear();
        File[] left = new File(migrateDir, "querylog").listFiles();
        check(left != null && left.length == 0, "clear removes query log days");
    }

    private static void dns() throws Exception {
        File home = new File(dir, "dns");
        home.mkdirs();
        DesktopPrefs prefs = new DesktopPrefs(home);
        prefs.load();
        prefs.setInt("dnsPort", 0);
        prefs.set("bindAddress", "127.0.0.1");
        prefs.set("upstreamMode", "plain");
        FilterEngine filter = new FilterEngine(prefs, home);
        filter.load();
        StatsStore store = new StatsStore(new File(home, "stats"));
        store.load();

        Resolver stub = (payload, query) -> {
            byte[] response = payload.clone();
            response[2] = (byte) (response[2] | 0x80);
            response[3] = (byte) (response[3] & 0x0F);
            DnsPacket.put16(response, 6, 0);
            DnsPacket.put16(response, 8, 0);
            DnsPacket.put16(response, 10, 0);
            return new Upstream.Result(response, 0, false, "stub");
        };
        DnsService service = new DnsService(prefs, filter, store, stub);
        check(service.start(), "dns service starts");
        check(service.actualPort() > 0, "dns binds a port");

        byte[] query = DnsPacket.buildQuery(0x4242, "doubleclick.net", DnsPacket.TYPE_A);
        byte[] blocked = udp(service.actualPort(), query);
        check(blocked != null, "udp blocked reply");
        eq(0x4242, DnsPacket.u16(blocked, 0), "udp transaction id");
        eq(1, DnsPacket.addresses(blocked, blocked.length).size(),
                "udp blocked answer");
        eq(1L, store.blocked(), "blocked query logged");

        byte[] allowed = udp(service.actualPort(),
                DnsPacket.buildQuery(0x4343, "example.com", DnsPacket.TYPE_A));
        check(allowed != null, "udp forwarded reply");
        eq(0x4343, DnsPacket.u16(allowed, 0), "forwarded transaction id");
        eq(2L, store.queries(), "forwarded query logged");

        byte[] tcp = tcp(service.actualPort(),
                DnsPacket.buildQuery(0x4444, "doubleclick.net", DnsPacket.TYPE_A));
        check(tcp != null, "tcp reply");
        eq(0x4444, DnsPacket.u16(tcp, 0), "tcp transaction id");
        eq(3L, store.queries(), "tcp query logged");
        service.stop();
    }

    private static void upstream() throws Exception {
        File home = new File(dir, "upstream");
        home.mkdirs();
        DesktopPrefs encrypted = new DesktopPrefs(home);
        encrypted.load();
        encrypted.set("upstreamMode", "doh");
        encrypted.set("dohUrl", "http://127.0.0.1:1/dns-query");
        encrypted.set("plainUpstream", "127.0.0.1:1");
        Upstream fallback = new Upstream(encrypted, new DnsCache(32));
        byte[] probe = DnsPacket.buildQuery(0x5151, "example.com", DnsPacket.TYPE_A);
        fallback.resolve(probe, DnsPacket.parseQuery(probe, probe.length));
        check(fallback.warning().contains("plain DNS"),
                "DoH downgrade is reported: " + fallback.warning());
        fallback.reset();
        check(fallback.warning().isEmpty(), "DoH downgrade resets");

        encrypted.set("upstreamMode", "plain");
        check(fallback.warning().isEmpty(), "explicit plain DNS has no warning");
    }

    private static void web() throws Exception {
        File home = new File(dir, "web");
        home.mkdirs();
        DesktopPrefs prefs = new DesktopPrefs(home);
        prefs.load();
        prefs.setInt("webPort", 0);
        prefs.set("webBind", "127.0.0.1");
        prefs.setInt("dnsPort", 0);
        prefs.set("bindAddress", "127.0.0.1");
        FilterEngine filter = new FilterEngine(prefs, home);
        filter.load();
        StatsStore store = new StatsStore(new File(home, "stats"));
        store.load();
        Resolver stub = (payload, query) -> null;
        DnsService dns = new DnsService(prefs, filter, store, stub);
        dns.start();
        WebDashboard web = new WebDashboard(prefs, filter, store, dns, DesktopApp.VERSION);
        check(web.start(), "web starts");
        String base = "http://127.0.0.1:" + web.actualPort();

        HttpResponse<String> page = get(base + "/admin/", null);
        eq(200, page.statusCode(), "dashboard status");
        check(page.body().contains("Hole Shield"), "dashboard body");
        check(get(base + "/api/health", null).body().contains("ok"), "health");

        HttpResponse<String> summary = get(base + "/api/stats/summary", null);
        eq(200, summary.statusCode(), "summary status");
        check(summary.body().contains("\"percent_blocked\""), "summary shape");
        check(get(base + "/api/info/host", null).body().contains("\"web_port\""),
                "info host");

        HttpResponse<String> created = request("POST",
                base + "/api/domains/deny/exact", null,
                "{\"domain\":\"blocked.example\"}");
        eq(201, created.statusCode(), "create rule");
        check(get(base + "/api/domains/deny/exact", null).body()
                .contains("blocked.example"), "list rules");
        eq(200, request("DELETE",
                base + "/api/domains/deny/exact?domain=blocked.example", null, "")
                .statusCode(), "delete rule");

        eq(200, request("POST", base + "/api/dns/blocking", null,
                "{\"blocking\":false,\"timer\":0}").statusCode(), "pause api");
        check(!prefs.blockingActive(), "pause state");
        request("POST", base + "/api/dns/blocking", null,
                "{\"blocking\":true}");
        check(prefs.blockingActive(), "resume state");

        eq(201, request("POST", base + "/api/lists", null,
                "{\"address\":\"https://example.com/hosts.txt\"}").statusCode(),
                "add list");
        check(get(base + "/api/lists", null).body().contains("example.com/hosts.txt"),
                "list sources");
        eq(200, request("DELETE", base + "/api/lists/0", null, "").statusCode(),
                "delete list");

        prefs.setWebPassword("secret");
        prefs.set("webUsername", "admin");
        eq(401, get(base + "/api/stats/summary", null).statusCode(),
                "auth required");
        eq(401, request("POST", base + "/api/auth", null,
                "{\"username\":\"admin\",\"password\":\"wrong\"}").statusCode(),
                "wrong password");
        HttpResponse<String> login = request("POST", base + "/api/auth", null,
                "{\"username\":\"admin\",\"password\":\"secret\"}");
        eq(200, login.statusCode(), "login");
        Map<String, Object> body = Json.parseObject(login.body());
        Map<String, Object> session = (Map<String, Object>) body.get("session");
        String sid = String.valueOf(session.get("sid"));
        check(!sid.isEmpty() && !"null".equals(sid), "session id");
        eq(200, get(base + "/api/stats/summary", sid).statusCode(),
                "authorized summary");
        eq(200, request("POST", base + "/api/auth/logout", sid, "").statusCode(),
                "logout");
        eq(401, get(base + "/api/stats/summary", sid).statusCode(),
                "session revoked");

        web.stop();
        dns.stop();
    }

    private static void companion() throws Exception {
        File home = new File(dir, "companion");
        home.mkdirs();
        DesktopPrefs prefs = new DesktopPrefs(home);
        prefs.load();
        prefs.setInt("webPort", 0);
        prefs.set("webBind", "127.0.0.1");
        prefs.setInt("dnsPort", 0);
        prefs.set("bindAddress", "127.0.0.1");
        prefs.set("webUsername", "admin");
        prefs.setWebPassword("companion-secret");
        FilterEngine filter = new FilterEngine(prefs, home);
        filter.load();
        StatsStore store = new StatsStore(new File(home, "stats"));
        store.load();
        Resolver stub = (payload, query) -> null;
        DnsService dns = new DnsService(prefs, filter, store, stub);
        dns.start();
        WebDashboard web = new WebDashboard(prefs, filter, store, dns,
                DesktopApp.VERSION);
        check(web.start(), "companion web starts");
        try {
            int port = web.actualPort();
            String address = "127.0.0.1:" + port;
            eq("http://127.0.0.1:" + port, CompanionClient.base(address, 8080),
                    "companion base adds scheme");
            eq("http://127.0.0.1:" + port,
                    CompanionClient.base("http://127.0.0.1:" + port, 9),
                    "companion base keeps explicit port");
            check(CompanionClient.base("https://example.test", 8443)
                    .equals("https://example.test:8443"), "companion base adds port");

            CompanionClient client = new CompanionClient(address, 8080,
                    "admin", "companion-secret");
            client.connect();
            check(client.connected(), "companion connects");
            eq(DesktopApp.VERSION, client.version(), "companion version");
            check(client.dashboardUrl().endsWith(":" + port + "/admin/"),
                    "companion dashboard url");
            CompanionClient.Snapshot snapshot = client.snapshot();
            check(snapshot.gravityDomains >= 0, "companion summary gravity");
            check(snapshot.blocking, "companion starts blocking");

            check(!client.setBlocking(false, 0), "companion pause accepted");
            CompanionClient.Snapshot paused = client.snapshot();
            check(!paused.blocking, "companion pause visible");
            check(client.setBlocking(true, 0), "companion resume accepted");
            check(client.snapshot().blocking, "companion resume visible");
            client.flushCache();

            store.record(new StatsStore.Entry(System.currentTimeMillis(),
                    "192.168.2.9", "ads.example.com", DnsPacket.TYPE_A, "BLOCKED",
                    "gravity", 0, 2, "", "Ads", true, false));
            CompanionClient.Dashboard dash = client.dashboard();
            eq(24, dash.history.size(), "companion history buckets");
            check(!dash.queries.isEmpty(), "companion query log");
            check(dash.queries.get(0).blocked, "companion query status");
            check(dash.categories.containsKey("ads"), "companion categories");
            check(dash.levels.containsKey("ads"), "companion category levels");
            check(!dash.sources.isEmpty(), "companion list sources");
            check(!dash.host.hostname.isEmpty(), "companion host info");

            client.addRule(true, "exact", "allowed.example");
            client.addRule(false, "wildcard", "*.ads.example");
            CompanionClient.Dashboard ruled = client.dashboard();
            check(ruled.allowed.stream().anyMatch(r -> r.domain.equals("allowed.example")),
                    "companion adds allow rule");
            check(ruled.blocked.stream().anyMatch(r ->
                            r.domain.equals("ads.example") && r.kind.equals("wildcard")),
                    "companion adds block rule");
            client.removeRule(true, "exact", "allowed.example");
            check(client.dashboard().allowed.stream()
                            .noneMatch(r -> r.domain.equals("allowed.example")),
                    "companion removes rule");

            client.addList("https://example.test/hosts.txt");
            CompanionClient.Source added = client.dashboard().sources.stream()
                    .filter(s -> s.address.equals("https://example.test/hosts.txt"))
                    .findFirst().orElse(null);
            check(added != null, "companion adds list");
            client.removeList(added.id);
            check(client.dashboard().sources.stream()
                            .noneMatch(s -> s.address.equals("https://example.test/hosts.txt")),
                    "companion removes list");

            client.setCategory("ads", false);
            eq(Boolean.FALSE, client.dashboard().categories.get("ads"),
                    "companion disables category");

            CompanionClient wrong = new CompanionClient(address, 8080,
                    "admin", "not-the-password");
            boolean rejected = false;
            try {
                wrong.connect();
            } catch (CompanionClient.AuthException e) {
                rejected = true;
            }
            check(rejected, "companion rejects a bad password");
            check(!wrong.connected(), "companion stays offline after a bad login");

            // The Android server answers /api/auth with 401 when a password is
            // required; the client must still read the body and log in.
            com.sun.net.httpserver.HttpServer android =
                    com.sun.net.httpserver.HttpServer.create(
                            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            android.createContext("/api/auth", exchange -> {
                boolean probe = "GET".equals(exchange.getRequestMethod());
                byte[] reply = (probe
                        ? "{\"session\":{\"valid\":false,\"password_required\":true}}"
                        : "{\"session\":{\"valid\":true,\"sid\":\"android-session\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(probe ? 401 : 200, reply.length);
                try (java.io.OutputStream out = exchange.getResponseBody()) {
                    out.write(reply);
                }
            });
            android.createContext("/api/stats/summary", exchange -> {
                boolean allowed = "android-session".equals(
                        exchange.getRequestHeaders().getFirst("X-FTL-SID"));
                byte[] reply = (allowed
                        ? "{\"queries\":{\"total\":7,\"blocked\":3,"
                        + "\"percent_blocked\":42.9},"
                        + "\"gravity\":{\"domains_being_blocked\":11},"
                        + "\"categories\":{\"ads\":4},\"blocking\":true}"
                        : "{\"session\":{\"valid\":false}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(allowed ? 200 : 401, reply.length);
                try (java.io.OutputStream out = exchange.getResponseBody()) {
                    out.write(reply);
                }
            });
            android.start();
            try {
                CompanionClient androidClient = new CompanionClient(
                        "127.0.0.1:" + android.getAddress().getPort(), 8080,
                        "admin", "pw");
                androidClient.connect();
                check(androidClient.connected(), "companion logs in after 401 probe");
                CompanionClient.Snapshot androidSnapshot = androidClient.snapshot();
                eq(7L, androidSnapshot.queries, "companion android summary");
                eq(3L, androidSnapshot.blocked, "companion android blocked");
                eq(11L, androidSnapshot.gravityDomains,
                        "companion android gravity");
                eq(42.9, androidSnapshot.rate, "companion android rate");
            } finally {
                android.stop(0);
            }
        } finally {
            web.stop();
            dns.stop();
            store.save();
        }
    }

    private static void persistence() {
        File home = new File(dir, "persist");
        home.mkdirs();
        DesktopPrefs prefs = new DesktopPrefs(home);
        prefs.load();
        prefs.set("dnsPort", "5353");
        prefs.set("accent", "rose");
        prefs.setBool("categoryTrackers", false);
        prefs.setWebPassword("secret");
        prefs.save();
        DesktopPrefs loaded = new DesktopPrefs(home);
        loaded.load();
        eq(5353, loaded.getInt("dnsPort", 53), "persisted port");
        eq("rose", loaded.get("accent", "default"), "persisted accent");
        Theme.setMode(Theme.MODE_DARK, loaded.get("accent", "default"));
        eq(new java.awt.Color(AccentPalette.ROSE.primary(true)), Theme.get().accent,
                "custom dark accent applied");
        eq(new java.awt.Color(0x11111A), Theme.get().surface, "accent leaves dark surface unchanged");
        Theme.setMode(Theme.MODE_LIGHT, "blue");
        eq(new java.awt.Color(AccentPalette.BLUE.primary(false)), Theme.get().accent,
                "custom light accent applied");
        Theme.setMode(Theme.MODE_DARK, "unknown");
        eq(new java.awt.Color(AccentPalette.DEFAULT.primary(true)), Theme.get().accent,
                "unknown accent falls back to default");
        check(!loaded.getBool("categoryTrackers", true), "persisted toggle");
        check(loaded.hasWebPassword(), "persisted password");
        check(WebAuth.verify(loaded.webPassword(), "secret"), "password verifies");
        check(!WebAuth.verify(loaded.webPassword(), "wrong"), "wrong password fails");
        check(loaded.getBool("autoUpdateCheck", false), "update check defaults on");
        check(loaded.getBool("showUpdateNotes", false), "update notes default on");
        loaded.set("lastUpdateCheck", "1700000000000");
        loaded.save();
        DesktopPrefs reread = new DesktopPrefs(home);
        reread.load();
        eq(1700000000000L, reread.getLong("lastUpdateCheck", 0L),
                "last update check persists");
    }

    // ------------------------------------------------------------------- helpers

    private static byte[] udp(int port, byte[] query) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(3000);
            socket.send(new DatagramPacket(query, query.length,
                    InetAddress.getLoopbackAddress(), port));
            byte[] buffer = new byte[4096];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            byte[] packet = new byte[response.getLength()];
            System.arraycopy(response.getData(), 0, packet, 0, packet.length);
            return packet;
        }
    }

    private static byte[] tcp(int port, byte[] query) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(3000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            out.writeShort(query.length);
            out.write(query);
            out.flush();
            int length = in.readUnsignedShort();
            byte[] packet = new byte[length];
            in.readFully(packet);
            return packet;
        }
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static HttpResponse<String> get(String url, String sid) throws Exception {
        return request("GET", url, sid, null);
    }

    private static HttpResponse<String> request(String method, String url, String sid,
                                                String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(5))
                .header("Accept", "application/json");
        if (sid != null) builder.header("X-FTL-SID", sid);
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
