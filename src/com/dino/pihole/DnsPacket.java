package com.dino.pihole;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The small slice of DNS this app needs: parse a query, build a blocked
 * answer, and read the smallest TTL out of an upstream response.
 *
 * Deliberately free of Android imports so it can be unit-tested on a desktop
 * JVM (see tools/TestDns.java).
 */
final class DnsPacket {

    static final int TYPE_A = 1;
    static final int TYPE_AAAA = 28;
    static final int TYPE_SVCB = 64;
    static final int TYPE_HTTPS = 65;

    private DnsPacket() { }

    static final class Query {
        final byte[] raw;
        final int length;
        final int id;
        final int flags;
        final String name;
        final int type;
        final int qclass;
        final int questionEnd;
        final boolean recursionDesired;

        Query(byte[] raw, int length, int id, int flags, String name,
              int type, int qclass, int questionEnd) {
            this.raw = raw;
            this.length = length;
            this.id = id;
            this.flags = flags;
            this.name = name;
            this.type = type;
            this.qclass = qclass;
            this.questionEnd = questionEnd;
            this.recursionDesired = (flags & 0x0100) != 0;
        }
    }

    /** Returns null for anything that is not a well-formed, uncompressed query. */
    static Query parseQuery(byte[] data, int length) {
        if (data == null || length < 12) return null;
        int flags = u16(data, 2);
        if ((flags & 0x8000) != 0) return null;          // a response, not a query
        if ((flags & 0x7800) != 0) return null;          // only standard queries
        if (u16(data, 4) < 1) return null;               // no question

        int off = 12;
        StringBuilder name = new StringBuilder();
        int guard = 0;
        while (off < length) {
            if (++guard > 128) return null;
            int label = data[off] & 0xFF;
            if (label == 0) { off++; break; }
            if ((label & 0xC0) != 0) return null;        // compression in a question
            if (label > 63 || off + 1 + label > length) return null;
            if (name.length() + label + 1 > 253) return null;
            for (int i = 0; i < label; i++) {
                int ch = data[off + 1 + i] & 0xFF;
                if (ch >= 'A' && ch <= 'Z') ch += 32;
                name.append((char) ch);
            }
            name.append('.');
            off += 1 + label;
        }
        if (off + 4 > length) return null;
        int type = u16(data, off);
        int qclass = u16(data, off + 2);
        off += 4;
        String host = name.length() == 0 ? "" : name.substring(0, name.length() - 1);
        return new Query(data, length, u16(data, 0), flags, host, type, qclass, off);
    }

    /** True for record types where a sinkhole answer makes sense. */
    static boolean isBlockableType(int type) {
        return type == TYPE_A || type == TYPE_AAAA || type == TYPE_HTTPS
                || type == TYPE_SVCB || type == 255 /* ANY */;
    }

    static String typeName(int type) {
        switch (type) {
            case 1: return "A";
            case 2: return "NS";
            case 5: return "CNAME";
            case 6: return "SOA";
            case 12: return "PTR";
            case 15: return "MX";
            case 16: return "TXT";
            case 28: return "AAAA";
            case 33: return "SRV";
            case 35: return "NAPTR";
            case 43: return "DS";
            case 46: return "RRSIG";
            case 47: return "NSEC";
            case 48: return "DNSKEY";
            case 52: return "TLSA";
            case 64: return "SVCB";
            case 65: return "HTTPS";
            case 255: return "ANY";
            default: return "TYPE" + type;
        }
    }

    static int rcode(byte[] data, int length) {
        if (data == null || length < 4) return 2;
        return u16(data, 2) & 0x0F;
    }

    static String rcodeName(int rcode) {
        switch (rcode) {
            case 0: return "NOERROR";
            case 1: return "FORMERR";
            case 2: return "SERVFAIL";
            case 3: return "NXDOMAIN";
            case 4: return "NOTIMP";
            case 5: return "REFUSED";
            default: return "RCODE" + rcode;
        }
    }

    /** CNAME targets in the answer section, lowercased. */
    static List<String> cnameTargets(byte[] data, int length) {
        List<String> out = new ArrayList<>();
        if (data == null || length < 12 || (u16(data, 2) & 0x8000) == 0) return out;
        int qd = u16(data, 4);
        int an = u16(data, 6);
        int off = 12;
        for (int i = 0; i < qd; i++) {
            off = skipName(data, length, off);
            if (off < 0 || off + 4 > length) return out;
            off += 4;
        }
        for (int i = 0; i < an; i++) {
            off = skipName(data, length, off);
            if (off < 0 || off + 10 > length) break;
            int type = u16(data, off);
            int rdlen = u16(data, off + 8);
            int rdata = off + 10;
            if (rdata + rdlen > length) break;
            if (type == 5) {
                String target = readName(data, length, rdata);
                if (!target.isEmpty()) out.add(target);
            }
            off = rdata + rdlen;
        }
        return out;
    }

    private static String readName(byte[] d, int len, int off) {
        StringBuilder sb = new StringBuilder();
        int next = off;
        int guard = 0;
        while (next >= 0 && next < len) {
            if (++guard > 128) return "";
            int l = d[next] & 0xFF;
            if (l == 0) break;
            if ((l & 0xC0) == 0xC0) {
                if (next + 1 >= len) return "";
                next = ((l & 0x3F) << 8) | (d[next + 1] & 0xFF);
                continue;
            }
            if (l > 63 || next + 1 + l > len) return "";
            if (sb.length() + l + 1 > 253) return "";
            for (int i = 0; i < l; i++) {
                int ch = d[next + 1 + i] & 0xFF;
                if (ch >= 'A' && ch <= 'Z') ch += 32;
                sb.append((char) ch);
            }
            sb.append('.');
            next += 1 + l;
        }
        if (sb.length() == 0) return "";
        return sb.substring(0, sb.length() - 1);
    }

    /**
     * A 0.0.0.0 A record, NODATA for AAAA (so clients fall back to the A
     * query) and NXDOMAIN for HTTPS/SVCB and anything else.
     */
    static byte[] blockedReply(Query q) {
        int rcode = 0;
        boolean withAnswer = q.type == TYPE_A || q.type == 255;
        if (q.type == TYPE_AAAA) {
            withAnswer = false;
        } else if (!withAnswer) {
            rcode = 3;
        }

        int extra = withAnswer ? 16 : 0;
        byte[] out = new byte[q.questionEnd + extra];
        System.arraycopy(q.raw, 0, out, 0, q.questionEnd);

        int flags = 0x8000 | 0x0080;                     // response, recursion available
        if (q.recursionDesired) flags |= 0x0100;
        flags |= rcode;
        put16(out, 2, flags);
        put16(out, 4, 1);                                // QDCOUNT
        put16(out, 6, withAnswer ? 1 : 0);               // ANCOUNT
        put16(out, 8, 0);
        put16(out, 10, 0);

        if (withAnswer) {
            int o = q.questionEnd;
            out[o] = (byte) 0xC0;                        // pointer to the question name
            out[o + 1] = 0x0C;
            put16(out, o + 2, TYPE_A);
            put16(out, o + 4, 1);                        // IN
            put32(out, o + 6, 60);                       // TTL
            put16(out, o + 10, 4);
            // 0.0.0.0 already zeroed
        }
        return out;
    }

    static byte[] errorReply(Query q, int rcode) {
        byte[] out = new byte[q.questionEnd];
        System.arraycopy(q.raw, 0, out, 0, q.questionEnd);
        int flags = 0x8000 | 0x0080 | (q.recursionDesired ? 0x0100 : 0);
        flags |= (rcode & 0x0F);
        put16(out, 2, flags);
        put16(out, 4, 1);
        put16(out, 6, 0);
        put16(out, 8, 0);
        put16(out, 10, 0);
        return out;
    }

    /** Patch the transaction id of a cached/forwarded response. */
    static void setTransactionId(byte[] packet, int id) {
        if (packet == null || packet.length < 2) return;
        packet[0] = (byte) ((id >> 8) & 0xFF);
        packet[1] = (byte) (id & 0xFF);
    }

    /**
     * Smallest answer TTL in a response, clamped to something sane. Used to
     * decide how long the local cache may keep it. Returns 60 when in doubt.
     */
    static int minTtl(byte[] data, int length) {
        if (data == null || length < 12) return 60;
        int qd = u16(data, 4);
        int an = u16(data, 6);
        int off = 12;
        for (int i = 0; i < qd; i++) {
            off = skipName(data, length, off);
            if (off < 0 || off + 4 > length) return 60;
            off += 4;
        }
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < an; i++) {
            off = skipName(data, length, off);
            if (off < 0 || off + 10 > length) break;
            int type = u16(data, off);
            long ttl = u32(data, off + 4);
            int rdlen = u16(data, off + 8);
            off += 10;
            if (off + rdlen > length) break;
            if (type != 41 && ttl < min) min = (int) ttl;
            off += rdlen;
        }
        if (min == Integer.MAX_VALUE) return 60;
        if (min < 10) min = 10;
        if (min > 3600) min = 3600;
        return min;
    }

    static byte[] buildQuery(int id, String name, int type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((id >> 8) & 0xFF);
        out.write(id & 0xFF);
        out.write(0x01);                                 // RD
        out.write(0x00);
        out.write(0); out.write(1);                      // QDCOUNT
        for (int i = 0; i < 6; i++) out.write(0);
        String clean = name == null ? "" : name.trim().toLowerCase(Locale.US);
        if (clean.endsWith(".")) clean = clean.substring(0, clean.length() - 1);
        if (!clean.isEmpty()) {
            for (String label : clean.split("\\.")) {
                byte[] b = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                if (b.length == 0 || b.length > 63) return null;
                out.write(b.length);
                out.write(b, 0, b.length);
            }
        }
        out.write(0);
        out.write((type >> 8) & 0xFF);
        out.write(type & 0xFF);
        out.write(0); out.write(1);                      // IN
        return out.toByteArray();
    }

    /**
     * A/AAAA addresses in the answer section of a response. Used to resolve
     * the Pi-hole/DoH host through the TV's own LAN DNS server rather than
     * through this VPN (which may be circular).
     */
    static List<InetAddress> addresses(byte[] data, int length) {
        List<InetAddress> out = new ArrayList<>();
        if (data == null || length < 12 || (u16(data, 2) & 0x8000) == 0) return out;
        int qd = u16(data, 4);
        int an = u16(data, 6);
        int off = 12;
        for (int i = 0; i < qd; i++) {
            off = skipName(data, length, off);
            if (off < 0 || off + 4 > length) return out;
            off += 4;
        }
        for (int i = 0; i < an; i++) {
            off = skipName(data, length, off);
            if (off < 0 || off + 10 > length) break;
            int type = u16(data, off);
            int rdlen = u16(data, off + 8);
            off += 10;
            if (off + rdlen > length) break;
            if ((type == TYPE_A && rdlen == 4) || (type == TYPE_AAAA && rdlen == 16)) {
                byte[] raw = new byte[rdlen];
                System.arraycopy(data, off, raw, 0, rdlen);
                try {
                    out.add(InetAddress.getByAddress(raw));
                } catch (Exception ignored) { }
            }
            off += rdlen;
        }
        return out;
    }

    private static int skipName(byte[] d, int len, int off) {
        int guard = 0;
        while (off >= 0 && off < len) {
            if (++guard > 128) return -1;
            int l = d[off] & 0xFF;
            if (l == 0) return off + 1;
            if ((l & 0xC0) == 0xC0) return off + 2 <= len ? off + 2 : -1;
            if (l > 63) return -1;
            off += 1 + l;
        }
        return -1;
    }

    static int u16(byte[] d, int off) {
        return ((d[off] & 0xFF) << 8) | (d[off + 1] & 0xFF);
    }

    static long u32(byte[] d, int off) {
        return ((long) (d[off] & 0xFF) << 24)
                | ((long) (d[off + 1] & 0xFF) << 16)
                | ((long) (d[off + 2] & 0xFF) << 8)
                | (d[off + 3] & 0xFF);
    }

    static void put16(byte[] d, int off, int v) {
        d[off] = (byte) ((v >> 8) & 0xFF);
        d[off + 1] = (byte) (v & 0xFF);
    }

    static void put32(byte[] d, int off, long v) {
        d[off] = (byte) ((v >> 24) & 0xFF);
        d[off + 1] = (byte) ((v >> 16) & 0xFF);
        d[off + 2] = (byte) ((v >> 8) & 0xFF);
        d[off + 3] = (byte) (v & 0xFF);
    }
}
