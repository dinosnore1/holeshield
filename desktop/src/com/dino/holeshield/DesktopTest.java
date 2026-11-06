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
            stats();
            dns();
            web();
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
        FilterEngine filter = new FilterEngine(prefs, home);
        filter.load();
        check(filter.gravityCount() > 100, "seed gravity loaded");
        check(filter.check("doubleclick.net").blocked, "seed domain blocked");
        eq("Ads", filter.check("doubleclick.net").category,
                "seed domain keeps the ads category flag");
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

    private static void updates() throws Exception {
        File home = new File(dir, "updates");
        home.mkdirs();
        DesktopPrefs prefs = new DesktopPrefs(home);
        prefs.load();
        FilterEngine filter = new FilterEngine(prefs, home);
        filter.load();

        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
                .create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/list.txt", exchange -> {
            byte[] body = ("# local test list\n0.0.0.0 local-block.example\n"
                    + "0.0.0.0 sub.local-block.example\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        prefs.set("blocklistUrls", "http://127.0.0.1:" + port + "/list.txt");
        filter.updateBlocklists(null, null);
        for (int i = 0; i < 100 && filter.isUpdating(); i++) {
            Thread.sleep(50);
        }
        check(!filter.isUpdating(), "list update finished");
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

    private static void stats() {
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
        eq(2, loaded.uniqueDomains(), "persisted domains");
        eq(2, loaded.clientCount(), "persisted clients");
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

    private static void persistence() {
        File home = new File(dir, "persist");
        home.mkdirs();
        DesktopPrefs prefs = new DesktopPrefs(home);
        prefs.load();
        prefs.set("dnsPort", "5353");
        prefs.setBool("categoryTrackers", false);
        prefs.setWebPassword("secret");
        prefs.save();
        DesktopPrefs loaded = new DesktopPrefs(home);
        loaded.load();
        eq(5353, loaded.getInt("dnsPort", 53), "persisted port");
        check(!loaded.getBool("categoryTrackers", true), "persisted toggle");
        check(loaded.hasWebPassword(), "persisted password");
        check(WebAuth.verify(loaded.webPassword(), "secret"), "password verifies");
        check(!WebAuth.verify(loaded.webPassword(), "wrong"), "wrong password fails");
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
