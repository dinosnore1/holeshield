package com.dino.holeshield;

import android.content.Context;

import java.util.List;

/**
 * The embedded resolver: gravity/allow rules, pause state, CNAME
 * inspection, query logging and the DoH/plain upstream.
 */
final class DnsEngine {

    static final String VPN_CLIENT = "10.111.221.2";
    static final String VPN_CLIENT6 = "fd00:111:221::2";
    // InetAddress.getHostAddress() does not always compress zero groups.
    static final String VPN_CLIENT6_FULL = "fd00:111:221:0:0:0:0:2";

    private final Context context;
    private final DnsUpstream upstream;
    private volatile Prefs prefs;
    private volatile long prefsAt;

    DnsEngine(Context context, DnsUpstream.Protector protector, Prefs prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.prefsAt = System.currentTimeMillis();
        this.upstream = new DnsUpstream(this.context, protector, prefs);
    }

    private Prefs prefs() {
        long now = System.currentTimeMillis();
        if (now - prefsAt > 2000L) {
            prefs = Prefs.load(context);
            prefsAt = now;
        }
        return prefs;
    }

    /** @return a DNS response, or null when the packet is not a query. */
    byte[] resolve(byte[] payload, String clientIp) {
        Prefs p = prefs();
        DnsPacket.Query q;
        try {
            q = DnsPacket.parseQuery(payload, payload.length);
        } catch (Exception e) {
            return null;
        }
        if (q == null || q.name.isEmpty()) return null;

        String client = clientName(clientIp);
        long start = System.nanoTime();
        boolean canBlock = p.localBlocking && p.blockingActive()
                && q.qclass == 1 && DnsPacket.isBlockableType(q.type);

        Blocklist.Decision decision = canBlock
                ? Blocklist.check(q.name, clientIp) : Blocklist.Decision.NONE;

        if (decision.blocked) {
            byte[] reply = DnsPacket.blockedReply(q, p.blockedResponseMode());
            long ms = elapsed(start);
            Stats.onQuery(new Stats.Entry(System.currentTimeMillis(), client, q.name,
                    q.type, "BLOCKED", decision.source, 0, ms, decision.rule,
                    decision.category, true, false));
            return reply;
        }

        DnsUpstream.Result result = upstream.resolveResult(payload, q);
        if (result == null || result.packet == null) {
            byte[] reply = DnsPacket.errorReply(q, 2);
            Stats.onQuery(new Stats.Entry(System.currentTimeMillis(), client, q.name,
                    q.type, "ERROR", result == null ? "none" : result.upstream,
                    2, elapsed(start), decision.allowed ? decision.rule : "",
                    decision.category, false, false));
            return reply;
        }

        if (canBlock && p.blockCname && !decision.allowed) {
            List<String> targets = DnsPacket.cnameTargets(result.packet,
                    result.packet.length);
            for (String target : targets) {
                Blocklist.Decision cname = Blocklist.check(target, clientIp);
                if (cname.blocked) {
                    byte[] reply = DnsPacket.blockedReply(q, p.blockedResponseMode());
                    Stats.onQuery(new Stats.Entry(System.currentTimeMillis(), client,
                            q.name, q.type, "BLOCKED", cname.source,
                            DnsPacket.rcode(result.packet, result.packet.length),
                            elapsed(start), "CNAME " + target, cname.category,
                            true, false));
                    return reply;
                }
            }
        }

        String status;
        if (decision.allowed) status = "ALLOWED";
        else if (result.cached) status = "CACHED";
        else status = "FORWARDED";
        Stats.onQuery(new Stats.Entry(System.currentTimeMillis(), client, q.name,
                q.type, status, result.upstream, result.rcode, elapsed(start),
                decision.allowed ? decision.rule : "", decision.category,
                false, result.cached));
        return result.packet;
    }

    static String clientName(String ip) {
        if (ip == null || ip.isEmpty()) return "unknown";
        if (VPN_CLIENT.equals(ip) || "10.111.221.1".equals(ip)
                || VPN_CLIENT6.equals(ip) || VPN_CLIENT6_FULL.equals(ip)
                || "fd00:111:221::1".equals(ip)
                || "fd00:111:221:0:0:0:0:1".equals(ip)) {
            return "this device";
        }
        if ("127.0.0.1".equals(ip) || "::1".equals(ip)
                || "0:0:0:0:0:0:0:1".equals(ip)) return "localhost";
        return ip;
    }

    private static long elapsed(long startNanos) {
        long ns = System.nanoTime() - startNanos;
        long ms = ns / 1_000_000L;
        return ms < 0 ? 0 : ms;
    }

    void flushCache() {
        upstream.flushCache();
    }

    String upstreamStatus() {
        return upstream.statusLine();
    }
}
