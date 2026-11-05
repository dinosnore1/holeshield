package com.dino.holeshield;

import java.net.InetAddress;

/**
 * Builds the IP/UDP packet that carries a DNS answer back into the tun device.
 * Pure Java so it can be verified off-device.
 */
final class IpPacket {

    private IpPacket() { }

    /** Source address of a tun packet, for the client list. */
    static String sourceAddress(byte[] packet, int length) {
        if (packet == null || length < 1) return "";
        int version = (packet[0] >> 4) & 0x0F;
        if (version == 4) {
            if (length < 20) return "";
            int ihl = (packet[0] & 0x0F) * 4;
            if (ihl < 20 || length < ihl) return "";
            return (packet[12] & 0xFF) + "." + (packet[13] & 0xFF) + "."
                    + (packet[14] & 0xFF) + "." + (packet[15] & 0xFF);
        }
        if (version == 6) {
            if (length < 40) return "";
            byte[] addr = new byte[16];
            System.arraycopy(packet, 8, addr, 0, 16);
            try {
                return InetAddress.getByAddress(addr).getHostAddress();
            } catch (Exception ignored) { }
        }
        return "";
    }

    /**
     * Returns the UDP payload of an IPv4 or IPv6 packet read from the tun.
     * The tun device hands us whole IP packets, but the DNS parser below
     * expects only the DNS message, so the IP and UDP headers are skipped.
     *
     * @return a fresh copy of the payload, or null when this is not a
     *         complete, unfragmented UDP packet
     */
    static byte[] udpPayload(byte[] request, int length) {
        if (request == null || length < 1) return null;
        int off;
        int version = (request[0] >> 4) & 0x0F;
        if (version == 4) {
            if (length < 20) return null;
            int ihl = (request[0] & 0x0F) * 4;
            if (ihl < 20 || length < ihl + 8) return null;
            if ((request[9] & 0xFF) != 17) return null;
            if ((DnsPacket.u16(request, 6) & 0x1FFF) != 0) return null;
            off = ihl + 8;
        } else if (version == 6) {
            if (length < 48) return null;
            int next = request[6] & 0xFF;
            off = 40;
            int guard = 0;
            while (next != 17) {
                if (++guard > 8) return null;
                if (next == 0 || next == 43 || next == 60) {
                    if (off + 2 > length) return null;
                    int hlen = ((request[off + 1] & 0xFF) + 1) * 8;
                    if (off + hlen > length) return null;
                    next = request[off] & 0xFF;
                    off += hlen;
                } else if (next == 44) {
                    if (off + 8 > length) return null;
                    next = request[off] & 0xFF;
                    off += 8;
                } else {
                    return null;
                }
            }
            if (off + 8 > length) return null;
            off += 8;
        } else {
            return null;
        }

        int udpLength = DnsPacket.u16(request, off - 4);
        if (udpLength < 8) return null;
        int end = off - 8 + udpLength;
        if (end > length) return null;
        byte[] payload = new byte[end - off];
        System.arraycopy(request, off, payload, 0, payload.length);
        return payload;
    }

    /**
     * @param request an IPv4 or IPv6 UDP packet read from the tun
     * @param length  how many bytes of {@code request} are valid
     * @param payload the DNS response
     * @return a reply packet with source/destination swapped, or null
     */
    static byte[] udpReply(byte[] request, int length, byte[] payload) {
        if (request == null || payload == null || length < 1) return null;
        int version = (request[0] >> 4) & 0x0F;
        if (version == 4) return ipv4Reply(request, length, payload);
        if (version == 6) return ipv6Reply(request, length, payload);
        return null;
    }

    private static byte[] ipv4Reply(byte[] req, int len, byte[] payload) {
        int ihl = (req[0] & 0x0F) * 4;
        if (ihl < 20 || len < ihl + 8) return null;
        if ((req[9] & 0xFF) != 17) return null;           // UDP
        int total = 20 + 8 + payload.length;
        if (total > 65535) return null;

        byte[] out = new byte[total];
        out[0] = 0x45;
        out[1] = 0;
        put16(out, 2, total);
        put16(out, 4, 0);
        put16(out, 6, 0x4000);                            // don't fragment
        out[8] = 64;                                      // TTL
        out[9] = 17;
        // swap addresses
        System.arraycopy(req, 16, out, 12, 4);
        System.arraycopy(req, 12, out, 16, 4);
        put16(out, 10, checksum(out, 0, 20));

        int u = 20;
        put16(out, u, DnsPacket.u16(req, ihl + 2));       // source port = request dest
        put16(out, u + 2, DnsPacket.u16(req, ihl));       // dest port = request source
        put16(out, u + 4, 8 + payload.length);
        put16(out, u + 6, 0);                             // checksum optional in IPv4
        System.arraycopy(payload, 0, out, u + 8, payload.length);
        return out;
    }

    private static byte[] ipv6Reply(byte[] req, int len, byte[] payload) {
        if (len < 48) return null;
        int next = req[6] & 0xFF;
        int off = 40;
        int guard = 0;
        while (next != 17) {
            if (++guard > 8) return null;
            if (next == 0 || next == 43 || next == 60) {  // hop-by-hop, routing, dest opts
                if (off + 2 > len) return null;
                int hlen = ((req[off + 1] & 0xFF) + 1) * 8;
                if (off + hlen > len) return null;
                next = req[off] & 0xFF;
                off += hlen;
            } else if (next == 44) {                      // fragment header
                if (off + 8 > len) return null;
                next = req[off] & 0xFF;
                off += 8;
            } else {
                return null;                              // not plain UDP
            }
        }
        if (off + 8 > len) return null;

        int total = 40 + 8 + payload.length;
        byte[] out = new byte[total];
        out[0] = 0x60;
        put16(out, 4, 8 + payload.length);
        out[6] = 17;
        out[7] = 64;
        System.arraycopy(req, 24, out, 8, 16);            // src = request dst
        System.arraycopy(req, 8, out, 24, 16);            // dst = request src

        int u = 40;
        put16(out, u, DnsPacket.u16(req, off + 2));
        put16(out, u + 2, DnsPacket.u16(req, off));
        put16(out, u + 4, 8 + payload.length);
        put16(out, u + 6, 0);
        System.arraycopy(payload, 0, out, u + 8, payload.length);

        int sum = 0;
        int ulen = 8 + payload.length;
        sum = add(sum, out, 8, 32);                       // pseudo-header addresses
        sum = add(sum, new byte[]{0, 0, (byte) (ulen >> 8), (byte) ulen}, 0, 4);
        sum = add(sum, new byte[]{0, 0, 0, 17}, 0, 4);    // next header (3 zero bytes + 17)
        sum = add(sum, out, u, ulen);
        int ck = fold(sum);
        put16(out, u + 6, ck == 0 ? 0xFFFF : ck);
        return out;
    }

    private static int checksum(byte[] d, int off, int len) {
        return fold(add(0, d, off, len));
    }

    /** Sum 16-bit big-endian words, padding an odd trailing byte. */
    private static int add(int initial, byte[] d, int off, int len) {
        int sum = initial;
        int end = off + len;
        int i = off;
        while (i + 1 < end) {
            sum += ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
            i += 2;
        }
        if (i < end) sum += (d[i] & 0xFF) << 8;
        return sum;
    }

    private static int fold(int sum) {
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (~sum) & 0xFFFF;
    }

    private static void put16(byte[] d, int off, int v) {
        d[off] = (byte) ((v >> 8) & 0xFF);
        d[off + 1] = (byte) (v & 0xFF);
    }
}
