package com.dino.holeshield;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

/**
 * Desktop checks for the pure-Java DNS and packet code.
 * Build/run with tools/test.sh — no Android device needed.
 */
public final class TestDns {

    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        queryParsing();
        blockedAnswers();
        ttl();
        answerAddresses();
        cnameParsing();
        metadata();
        ipv4Reply();
        ipv6Reply();
        sourceAddresses();
        hostsParsing();
        blocklistPreset();
        webAuth();
        gravityIndex();
        blockedModes();
        System.out.println("OK — " + passed + " checks passed");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAILED: " + what);
        passed++;
    }

    private static int kept(long[] source, Gravity g) {
        int n = 0;
        for (long hash : source) if (g.contains(hash)) n++;
        return n;
    }

    private static void queryParsing() {
        byte[] q = DnsPacket.buildQuery(0x1234, "Ads.DoubleClick.NET", DnsPacket.TYPE_A);
        DnsPacket.Query parsed = DnsPacket.parseQuery(q, q.length);
        check(parsed != null, "query parses");
        check(parsed.id == 0x1234, "id");
        check(parsed.name.equals("ads.doubleclick.net"), "name lowercased: " + parsed.name);
        check(parsed.type == DnsPacket.TYPE_A, "type");
        check(parsed.questionEnd == q.length, "question end");

        // A response must not be mistaken for a query.
        byte[] reply = DnsPacket.blockedReply(parsed);
        check(DnsPacket.parseQuery(reply, reply.length) == null, "response rejected as query");

        // Only standard queries (opcode 0) are handled.
        byte[] update = q.clone();
        update[2] = 0x28;                                // opcode 5, QR still 0
        check(DnsPacket.parseQuery(update, update.length) == null,
                "non-standard opcode rejected");
    }

    private static void blockedAnswers() {
        DnsPacket.Query a = parse("doubleclick.net", DnsPacket.TYPE_A);
        byte[] answer = DnsPacket.blockedReply(a);
        check((DnsPacket.u16(answer, 2) & 0x8000) != 0, "QR set");
        check(DnsPacket.u16(answer, 6) == 1, "one A answer");
        check(DnsPacket.u16(answer, answer.length - 6) == 4, "A rdata is 4 bytes");
        check(answer[answer.length - 4] == 0 && answer[answer.length - 3] == 0
                && answer[answer.length - 2] == 0 && answer[answer.length - 1] == 0, "0.0.0.0");

        DnsPacket.Query aaaa = parse("doubleclick.net", DnsPacket.TYPE_AAAA);
        byte[] noData = DnsPacket.blockedReply(aaaa);
        check(DnsPacket.u16(noData, 6) == 0, "AAAA gets NODATA");
        check((DnsPacket.u16(noData, 2) & 0x0F) == 0, "AAAA rcode 0");

        DnsPacket.Query https = parse("doubleclick.net", DnsPacket.TYPE_HTTPS);
        byte[] nx = DnsPacket.blockedReply(https);
        check((DnsPacket.u16(nx, 2) & 0x0F) == 3, "HTTPS gets NXDOMAIN");
    }

    private static void ttl() {
        DnsPacket.Query q = parse("example.com", DnsPacket.TYPE_A);
        byte[] answer = DnsPacket.blockedReply(q);
        check(DnsPacket.minTtl(answer, answer.length) == 60, "blocked TTL 60");

        // Two answers, TTLs 300 and 45.
        byte[] two = Arrays.copyOf(answer, answer.length + 16);
        DnsPacket.put16(two, 6, 2);
        DnsPacket.put32(two, answer.length - 10, 300);
        int o = answer.length;
        two[o] = (byte) 0xC0; two[o + 1] = 0x0C;
        DnsPacket.put16(two, o + 2, 1);
        DnsPacket.put16(two, o + 4, 1);
        DnsPacket.put32(two, o + 6, 45);
        DnsPacket.put16(two, o + 10, 4);
        two[o + 12] = 93; two[o + 13] = (byte) 184;
        two[o + 14] = (byte) 216; two[o + 15] = 34;
        check(DnsPacket.minTtl(two, two.length) == 45, "minimum TTL");
    }

    private static void answerAddresses() {
        DnsPacket.Query a = parse("example.com", DnsPacket.TYPE_A);
        byte[] answer = DnsPacket.blockedReply(a);
        List<InetAddress> v4 = DnsPacket.addresses(answer, answer.length);
        check(v4.size() == 1 && "0.0.0.0".equals(v4.get(0).getHostAddress()),
                "A answer parsed");

        DnsPacket.Query q6 = parse("example.com", DnsPacket.TYPE_AAAA);
        byte[] base = DnsPacket.blockedReply(q6);        // NODATA: no answers
        byte[] with6 = Arrays.copyOf(base, base.length + 28);
        DnsPacket.put16(with6, 6, 1);
        int o = base.length;
        with6[o] = (byte) 0xC0;
        with6[o + 1] = 0x0C;
        DnsPacket.put16(with6, o + 2, DnsPacket.TYPE_AAAA);
        DnsPacket.put16(with6, o + 4, 1);
        DnsPacket.put32(with6, o + 6, 60);
        DnsPacket.put16(with6, o + 10, 16);
        with6[o + 12] = 0x20;
        with6[o + 13] = 0x01;
        with6[o + 14] = 0x0d;
        with6[o + 15] = (byte) 0xb8;
        with6[o + 27] = 1;                               // 2001:db8::1
        List<InetAddress> v6 = DnsPacket.addresses(with6, with6.length);
        check(v6.size() == 1 && "2001:db8:0:0:0:0:0:1".equals(v6.get(0).getHostAddress()),
                "AAAA answer parsed");
    }

    private static void ipv4Reply() {
        byte[] query = DnsPacket.buildQuery(7, "doubleclick.net", DnsPacket.TYPE_A);
        DnsPacket.Query q = DnsPacket.parseQuery(query, query.length);
        byte[] dns = DnsPacket.blockedReply(q);

        int total = 20 + 8 + query.length;
        byte[] request = new byte[total];
        request[0] = 0x45;
        DnsPacket.put16(request, 2, total);
        request[8] = 64;
        request[9] = 17;
        // src 192.168.1.10, dst 10.111.221.1
        request[12] = (byte) 192; request[13] = 1; request[14] = 1; request[15] = 10;
        request[16] = 10; request[17] = 111; request[18] = (byte) 221; request[19] = 1;
        DnsPacket.put16(request, 20, 45678);
        DnsPacket.put16(request, 22, 53);
        DnsPacket.put16(request, 24, 8 + query.length);
        System.arraycopy(query, 0, request, 28, query.length);

        byte[] payload = IpPacket.udpPayload(request, request.length);
        check(payload != null, "ipv4 payload found");
        check(Arrays.equals(payload, query), "ipv4 payload intact");
        DnsPacket.Query parsed = DnsPacket.parseQuery(payload, payload.length);
        check(parsed != null && parsed.name.equals("doubleclick.net"),
                "ipv4 payload parses as DNS");

        byte[] reply = IpPacket.udpReply(request, request.length, dns);
        check(reply != null, "ipv4 reply built");
        check((reply[0] & 0xFF) == 0x45 && (reply[9] & 0xFF) == 17, "ipv4 header");
        check(u32(reply, 12) == u32(request, 16), "src swapped");
        check(u32(reply, 16) == u32(request, 12), "dst swapped");
        check(DnsPacket.u16(reply, 20) == 53, "source port 53");
        check(DnsPacket.u16(reply, 22) == 45678, "dest port");
        check(DnsPacket.u16(reply, 24) == 8 + dns.length, "udp length");
        check(java.util.Arrays.equals(
                Arrays.copyOfRange(reply, 28, reply.length), dns), "payload intact");
        check(headerChecksum(reply) == 0xFFFF, "ipv4 checksum");
    }

    private static void ipv6Reply() {
        byte[] query = DnsPacket.buildQuery(9, "ads.example.com", DnsPacket.TYPE_AAAA);
        DnsPacket.Query q = DnsPacket.parseQuery(query, query.length);
        byte[] dns = DnsPacket.blockedReply(q);

        int total = 40 + 8 + query.length;
        byte[] request = new byte[total];
        request[0] = 0x60;
        DnsPacket.put16(request, 4, 8 + query.length);
        request[6] = 17;
        request[7] = 64;
        byte[] src = ipv6("fd00::10");
        byte[] dst = ipv6("fd00::1");
        System.arraycopy(src, 0, request, 8, 16);
        System.arraycopy(dst, 0, request, 24, 16);
        DnsPacket.put16(request, 40, 5555);
        DnsPacket.put16(request, 42, 53);
        DnsPacket.put16(request, 44, 8 + query.length);
        System.arraycopy(query, 0, request, 48, query.length);

        byte[] payload = IpPacket.udpPayload(request, request.length);
        check(payload != null, "ipv6 payload found");
        check(Arrays.equals(payload, query), "ipv6 payload intact");
        check(DnsPacket.parseQuery(payload, payload.length) != null,
                "ipv6 payload parses as DNS");

        byte[] reply = IpPacket.udpReply(request, request.length, dns);
        check(reply != null, "ipv6 reply built");
        check((reply[0] & 0xF0) == 0x60 && (reply[6] & 0xFF) == 17, "ipv6 header");
        check(Arrays.equals(Arrays.copyOfRange(reply, 8, 24), dst), "v6 src swapped");
        check(Arrays.equals(Arrays.copyOfRange(reply, 24, 40), src), "v6 dst swapped");
        check(DnsPacket.u16(reply, 40) == 53, "v6 source port");
        check(DnsPacket.u16(reply, 42) == 5555, "v6 dest port");
        check(udp6Checksum(reply) == 0, "ipv6 udp checksum");
    }

    private static void cnameParsing() {
        byte[] query = DnsPacket.buildQuery(3, "www.example.com", DnsPacket.TYPE_A);
        DnsPacket.Query q = DnsPacket.parseQuery(query, query.length);
        byte[] target = wireName("tracker.example.net");
        int answerLen = 12 + target.length;
        byte[] response = Arrays.copyOf(query, query.length + answerLen);
        response[2] = (byte) 0x81;                       // response, recursion available
        response[3] = (byte) 0x80;
        DnsPacket.put16(response, 6, 1);                 // one answer
        int o = query.length;
        response[o] = (byte) 0xC0;                       // owner = question name
        response[o + 1] = 0x0C;
        DnsPacket.put16(response, o + 2, 5);             // CNAME
        DnsPacket.put16(response, o + 4, 1);
        DnsPacket.put32(response, o + 6, 60);
        DnsPacket.put16(response, o + 10, target.length);
        System.arraycopy(target, 0, response, o + 12, target.length);

        List<String> names = DnsPacket.cnameTargets(response, response.length);
        check(names.size() == 1 && names.get(0).equals("tracker.example.net"),
                "CNAME target parsed");
        check(DnsPacket.cnameTargets(query, query.length).isEmpty(),
                "queries have no CNAME targets");

        DnsPacket.Query qt = DnsPacket.parseQuery(DnsPacket.buildQuery(4,
                "example.com", DnsPacket.TYPE_A), 0);
        check(qt == null, "zero length query rejected");
        check(q != null && q.name.equals("www.example.com"), "CNAME question intact");
    }

    private static void metadata() {
        check(DnsPacket.typeName(1).equals("A"), "type A");
        check(DnsPacket.typeName(28).equals("AAAA"), "type AAAA");
        check(DnsPacket.typeName(65).equals("HTTPS"), "type HTTPS");
        check(DnsPacket.typeName(99).equals("TYPE99"), "unknown type");
        check(DnsPacket.rcodeName(0).equals("NOERROR"), "rcode NOERROR");
        check(DnsPacket.rcodeName(3).equals("NXDOMAIN"), "rcode NXDOMAIN");

        byte[] q = DnsPacket.buildQuery(5, "example.com", DnsPacket.TYPE_A);
        DnsPacket.Query parsed = DnsPacket.parseQuery(q, q.length);
        byte[] nx = DnsPacket.errorReply(parsed, 3);
        check(DnsPacket.rcode(nx, nx.length) == 3, "rcode parsed");
    }

    private static byte[] wireName(String name) {
        String[] labels = name.split("\\.");
        int len = 1;
        for (String label : labels) len += 1 + label.length();
        byte[] out = new byte[len];
        int o = 0;
        for (String label : labels) {
            out[o++] = (byte) label.length();
            for (int i = 0; i < label.length(); i++) out[o++] = (byte) label.charAt(i);
        }
        out[o] = 0;
        return out;
    }

    private static void sourceAddresses() {
        byte[] v4 = new byte[28];
        v4[0] = 0x45;
        v4[9] = 17;
        v4[12] = (byte) 192; v4[13] = (byte) 168; v4[14] = 1; v4[15] = 10;
        check(IpPacket.sourceAddress(v4, v4.length).equals("192.168.1.10"),
                "ipv4 source address");

        byte[] v6 = new byte[48];
        v6[0] = 0x60;
        System.arraycopy(ipv6("fd00::10"), 0, v6, 8, 16);
        check(IpPacket.sourceAddress(v6, v6.length).startsWith("fd00"),
                "ipv6 source address");
    }

    private static void hostsParsing() {
        check(HostsParser.parseLine("0.0.0.0 ads.example.com").equals("ads.example.com"),
                "hosts line");
        check(HostsParser.parseLine("127.0.0.1 localhost").isEmpty(),
                "localhost ignored");
        check(HostsParser.parseLine("255.255.255.255 broadcasthost").isEmpty(),
                "broadcast IP ignored");
        check(HostsParser.parseLine("0.0.0.0 1.2.3.4").isEmpty(),
                "IP target ignored");
        check(HostsParser.parseLine("127.0.0.1 localhost.localdomain").isEmpty(),
                "localdomain ignored");
        check(HostsParser.parseLine("||ads.example.com^$third-party")
                        .equals("ads.example.com"), "adblock domain rule");
        check(HostsParser.parseLine("*.tracker.example.com").equals("tracker.example.com"),
                "wildcard line");
        check(HostsParser.normalizeDomain("Example.COM.").equals("example.com"),
                "domain normalization");
    }

    private static void webAuth() {
        check(WebAuth.valid("", "", "", ""), "open dashboard without credentials");
        check(WebAuth.valid("", "secret", "", "secret"), "password-only login");
        check(!WebAuth.valid("", "secret", "", "wrong"), "wrong password rejected");
        check(!WebAuth.valid("", "secret", "", ""), "empty password rejected");

        check(WebAuth.valid("admin", "secret", "admin", "secret"),
                "username and password login");
        check(!WebAuth.valid("admin", "secret", "other", "secret"),
                "wrong username rejected");
        check(!WebAuth.valid("admin", "secret", "admin", "wrong"),
                "wrong password with username rejected");
        check(!WebAuth.valid("admin", "secret", "", "secret"),
                "missing username rejected");
        check(WebAuth.valid("admin", "", "", ""),
                "username without password leaves dashboard open");

        check(WebAuth.validAuthParam("", "secret", "secret"),
                "v5 password auth");
        check(!WebAuth.validAuthParam("", "secret", "wrong"),
                "v5 wrong password rejected");
        check(WebAuth.validAuthParam("admin", "secret", "admin:secret"),
                "v5 username:password auth");
        check(!WebAuth.validAuthParam("admin", "secret", "admin"),
                "v5 missing password separator rejected");
        check(!WebAuth.validAuthParam("admin", "secret", "other:secret"),
                "v5 wrong username rejected");
        check(WebAuth.validAuthParam("admin", "p:a", "admin:p:a"),
                "v5 password containing colon");
        check(WebAuth.validAuthParam("", "p:a", "p:a"),
                "v5 password-only with colon");
        check(!WebAuth.validAuthParam("admin", "secret", ""),
                "v5 empty auth rejected");

        String hashed = WebAuth.hash("secret");
        check(hashed.startsWith("pbkdf2$"), "password is stored as PBKDF2");
        check(WebAuth.isHashed(hashed), "hash record detected");
        check(WebAuth.verify(hashed, "secret"), "hashed password verifies");
        check(!WebAuth.verify(hashed, "wrong"), "wrong hashed password rejected");
        check(WebAuth.valid("", hashed, "", "secret"), "hashed login accepted");
        check(!WebAuth.valid("", hashed, "", "wrong"), "hashed login rejected");
        check(WebAuth.validAuthParam("", hashed, "secret"), "hashed v5 auth");
        check(!WebAuth.verify(WebAuth.hash("a"), "b"), "different hash rejected");
    }

    private static void gravityIndex() {
        long[] ads = Gravity.clean(new long[]{
                Gravity.hash("ads.example.com"),
                Gravity.hash("shared.example.com"),
                Gravity.hash("ads.example.com")
        });
        long[] malware = Gravity.clean(new long[]{
                Gravity.hash("bad.example.com"),
                Gravity.hash("shared.example.com")
        });
        Gravity g = Gravity.build(new long[][]{ads, malware},
                new int[]{Gravity.ADS, Gravity.MALWARE}, 100);
        check(g.size() == 3, "gravity merges duplicate domains");
        long shared = Gravity.hash("shared.example.com");
        check((g.flagsOf(shared) & Gravity.ADS) != 0, "shared domain keeps ads flag");
        check((g.flagsOf(shared) & Gravity.MALWARE) != 0, "shared domain keeps malware flag");
        check(g.contains(Gravity.hash("ads.example.com")), "gravity lookup finds a domain");
        check(!g.contains(Gravity.hash("missing.example.com")), "gravity lookup misses");
        int[] primary = g.primaryCounts(
                new int[]{Gravity.MALWARE, Gravity.ADS}, Gravity.ALL);
        check(primary[0] == 2 && primary[1] == 1,
                "primary category counts reconcile with the total");
        Gravity capped = Gravity.build(new long[][]{ads, malware},
                new int[]{Gravity.ADS, Gravity.MALWARE}, 2);
        check(capped.size() == 2, "gravity respects the domain cap");
        long[] adsOnly = {1, 2, 3, 4};
        long[] trackersOnly = {3, 4, 5, 6};
        int[] twoBits = {Gravity.ADS, Gravity.TRACKERS};
        int[] twoPriority = {Gravity.TRACKERS, Gravity.ADS};
        Gravity merged = Gravity.buildFair(
                new long[][]{adsOnly.clone(), trackersOnly.clone()},
                twoBits, 6, twoPriority);
        check(merged.size() == 6, "duplicates do not use the guard twice");
        check((merged.flagsOf(3) & Gravity.ADS) != 0
                        && (merged.flagsOf(3) & Gravity.TRACKERS) != 0,
                "merged domain keeps every category");
        Gravity sharedCap = Gravity.buildFair(
                new long[][]{adsOnly.clone(), trackersOnly.clone()},
                twoBits, 4, twoPriority);
        check(sharedCap.size() == 4, "fair merge caps the merged index");
        check(sharedCap.contains(1) && sharedCap.contains(2),
                "ads keeps its own share");
        check(!sharedCap.contains(5), "trackers share is spent on its own log");
        Gravity partial = Gravity.buildFair(new long[][]{
                {1, 2, 3, 4, 5, 6}, {7, 8, 9, 10}, {11, 12}, {}
        }, new int[]{Gravity.ADS, Gravity.TRACKERS, Gravity.MALWARE,
                Gravity.ADULT}, 6,
                new int[]{Gravity.MALWARE, Gravity.TRACKERS, Gravity.ADS,
                        Gravity.ADULT});
        check(partial.size() == 6,
                "disabled category gives its guard share to the enabled ones");

        long[][] six = new long[9][];
        for (int i = 0; i < 6; i++) {
            six[i] = new long[400];
            for (int j = 0; j < 400; j++) six[i][j] = (i + 1) * 1_000_000L + j;
        }
        Gravity eightSlot = Gravity.buildFair(six,
                new int[]{Gravity.ADS, Gravity.TRACKERS, Gravity.MALWARE,
                        Gravity.ADULT, Gravity.PIRACY, Gravity.GAMBLING,
                        Gravity.DATING, Gravity.SOCIAL, Gravity.OTHER},
                1500, new int[]{Gravity.ADS, Gravity.MALWARE,
                        Gravity.TRACKERS, Gravity.ADULT, Gravity.PIRACY,
                        Gravity.GAMBLING, Gravity.DATING, Gravity.SOCIAL,
                        Gravity.OTHER});
        check(eightSlot.size() == 1500, "six enabled categories split the guard");

        long[] smallA = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        long[] smallB = {11, 12, 13, 14, 15, 16, 17, 18, 19, 20};
        long[] adsMany = new long[500];
        long[] trackerMany = new long[500];
        for (int i = 0; i < 500; i++) {
            adsMany[i] = 1000L + i * 2;
            trackerMany[i] = 1001L + i * 2;
        }
        Gravity adFirst = Gravity.buildFair(
                new long[][]{adsMany, trackerMany, smallA, smallB},
                new int[]{Gravity.ADS, Gravity.TRACKERS, Gravity.ADULT,
                        Gravity.GAMBLING}, 300,
                new int[]{Gravity.ADS, Gravity.TRACKERS, Gravity.ADULT,
                        Gravity.GAMBLING});
        check(kept(adsMany, adFirst) == 205 && kept(trackerMany, adFirst) == 75,
                "ads takes the unused share before the other categories");
        check(Gravity.hash("Example.COM") == Gravity.hash("example.com"),
                "gravity hash is case-insensitive");
    }

    private static void blockedModes() {
        DnsPacket.Query q = parse("ads.example.com", DnsPacket.TYPE_A);
        byte[] zero = DnsPacket.blockedReply(q, 0);
        check((DnsPacket.u16(zero, 6)) == 1, "zero mode answers A");
        byte[] nx = DnsPacket.blockedReply(q, 1);
        check(DnsPacket.rcode(nx, nx.length) == 3, "nxdomain blocked response");
        check(DnsPacket.u16(nx, 6) == 0, "nxdomain has no answer");
        byte[] nodata = DnsPacket.blockedReply(q, 2);
        check(DnsPacket.rcode(nodata, nodata.length) == 0, "nodata rcode");
        check(DnsPacket.u16(nodata, 6) == 0, "nodata has no answer");
    }

    private static void blocklistPreset() {
        check(Blocklists.MAXIMUM.length >= 23, "maximum preset has a broad list set");
        check(Blocklists.byKey("adult").length > 0, "adult category list exists");
        check(Blocklists.byKey("piracy").length > 0, "piracy category list exists");
        check(Blocklists.byKey("gambling").length > 0, "gambling category list exists");
        check(Blocklists.byKey("dating").length > 0, "dating category list exists");
        check(Blocklists.byKey("social").length > 0, "social category list exists");
        check(Blocklists.TRACKERS.length >= 10, "tracker preset has telemetry lists");
        check(Blocklists.MALWARE.length >= 2, "malware preset has security lists");
        check(Blocklists.hasAll(Blocklists.presetText()), "preset text contains every list");
        check(Blocklists.hasAll(Blocklists.presetText(), Blocklists.TRACKERS),
                "preset text contains tracker lists");
        check(Blocklists.hasAll(Blocklists.presetText(), Blocklists.MALWARE),
                "preset text contains malware lists");
        check(!Blocklists.isLegacyDefault(Blocklists.presetText()),
                "maximum preset is not the legacy default");
        check(Blocklists.isLegacyDefault(Blocklists.LEGACY_DEFAULT),
                "legacy default recognized");
        check(!Blocklists.isLegacyDefault(""),
                "an intentionally empty source list is preserved");

        for (String url : Blocklists.TRACKERS) {
            check(Arrays.asList(Blocklists.MAXIMUM).contains(url),
                    "maximum includes tracker URL: " + url);
        }
        for (String url : Blocklists.MALWARE) {
            check(Arrays.asList(Blocklists.MAXIMUM).contains(url),
                    "maximum includes malware URL: " + url);
        }

        String merged = Blocklists.mergeMissing(
                "https://example.com/custom.txt\n" + Blocklists.MAXIMUM[0]);
        check(merged.contains("https://example.com/custom.txt"),
                "custom source preserved");
        check(Blocklists.hasAll(merged), "merge adds missing preset lists");
        check(merged.indexOf(Blocklists.MAXIMUM[0]) == merged.lastIndexOf(Blocklists.MAXIMUM[0]),
                "merge does not duplicate an existing preset URL");
        check(Blocklists.mergeMissing(Blocklists.presetText()).equals(Blocklists.presetText()),
                "merge is idempotent");

        String custom = "https://example.com/custom.txt";
        String withMalware = Blocklists.mergeMissing(custom, Blocklists.MALWARE);
        check(Blocklists.hasAll(withMalware, Blocklists.MALWARE),
                "malware category merge");
        check(withMalware.contains(custom), "category merge preserves custom source");
        String withoutMalware = Blocklists.removeAll(withMalware, Blocklists.MALWARE);
        check(!Blocklists.hasAll(withoutMalware, Blocklists.MALWARE),
                "malware category removal");
        check(withoutMalware.contains(custom),
                "category removal preserves custom source");
        String withTrackers = Blocklists.mergeMissing(custom, Blocklists.TRACKERS);
        check(Blocklists.hasAll(withTrackers, Blocklists.TRACKERS),
                "tracker category merge");
        check(!Blocklists.hasAll(withTrackers, Blocklists.MALWARE),
                "tracker merge does not add malware");

        for (String key : Blocklists.CATEGORY_KEYS) {
            String[] lite = Blocklists.urlsFor(key, "lite");
            String[] medium = Blocklists.urlsFor(key, "medium");
            String[] max = Blocklists.urlsFor(key, "max");
            check(lite.length > 0 && medium.length > 0 && max.length > 0,
                    key + " has lite, medium and maximum tiers");
            check(lite.length <= medium.length && medium.length <= max.length,
                    key + " tiers grow from lite to maximum");
            for (String url : max) {
                check(Arrays.asList(Blocklists.allUrls(key)).contains(url),
                        key + " maximum URL is in the category URL set");
            }
        }
        check(Arrays.asList(Blocklists.ADS_MAX)
                        .containsAll(Arrays.asList(Blocklists.byKey("ads"))),
                "ads maximum tier includes the core ad lists");
        String adsLite = Blocklists.applyLevel(Blocklists.presetText(), "ads", "lite");
        check(Blocklists.detectLevel(adsLite, "ads").equals("lite"),
                "ads lite level round-trips");
        check(!Blocklists.hasAll(adsLite, Blocklists.ADS_MAX),
                "ads lite level removes maximum URLs");
        String adsMedium = Blocklists.applyLevel(Blocklists.presetText(), "ads", "medium");
        check(Blocklists.detectLevel(adsMedium, "ads").equals("medium"),
                "ads medium level round-trips");
        String adsMax = Blocklists.applyLevel(adsLite, "ads", "max");
        check(Blocklists.detectLevel(adsMax, "ads").equals("max"),
                "ads maximum level round-trips");
        String gamblingLite = Blocklists.applyLevel("", "gambling", "lite");
        check(Blocklists.detectLevel(gamblingLite, "gambling").equals("lite"),
                "gambling lite uses the mini list");
        check(Blocklists.categoryOf(Blocklists.GAMBLING_LITE_TIER[0])
                        == Blocklists.CATEGORY_GAMBLING,
                "gambling mini list maps to the gambling category");
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
        check(Blocklists.hasAny(Blocklists.presetText(), Blocklists.byKey("ads")),
                "preset contains ads sources");
        check(!Blocklists.hasAny("https://example.com/custom.txt",
                        Blocklists.byKey("ads")),
                "custom-only list has no preset sources");
    }

    private static DnsPacket.Query parse(String name, int type) {
        byte[] q = DnsPacket.buildQuery(1, name, type);
        return DnsPacket.parseQuery(q, q.length);
    }

    private static int u32(byte[] d, int off) {
        return ((d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }

    private static int headerChecksum(byte[] d) {
        int sum = 0;
        for (int i = 0; i < 20; i += 2) {
            sum += ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
        }
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return sum;
    }

    private static byte[] ipv6(String text) {
        try {
            return java.net.InetAddress.getByName(text).getAddress();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static int udp6Checksum(byte[] reply) {
        int ulen = DnsPacket.u16(reply, 44);
        int sum = 0;
        for (int i = 8; i < 40; i += 2) sum += ((reply[i] & 0xFF) << 8) | (reply[i + 1] & 0xFF);
        sum += (ulen >> 16) & 0xFFFF;
        sum += ulen & 0xFFFF;
        sum += 17;
        int i = 40;
        int end = 40 + ulen;
        for (; i + 1 < end; i += 2) {
            sum += ((reply[i] & 0xFF) << 8) | (reply[i + 1] & 0xFF);
        }
        if (i < end) sum += (reply[i] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (~sum) & 0xFFFF;
    }
}
