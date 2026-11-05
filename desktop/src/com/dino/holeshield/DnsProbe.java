package com.dino.holeshield;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/** Tiny DNS client used by the VM smoke tests. */
public final class DnsProbe {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: DnsProbe PORT NAME");
            System.exit(2);
        }
        int port;
        try {
            port = Integer.parseInt(args[0]);
        } catch (Exception e) {
            System.err.println("invalid port");
            System.exit(2);
            return;
        }
        String name = args[1];
        try {
            byte[] query = DnsPacket.buildQuery(0x4d2, name, DnsPacket.TYPE_A);
            if (query == null) {
                System.err.println("invalid name");
                System.exit(2);
                return;
            }
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(5000);
                socket.send(new DatagramPacket(query, query.length,
                        InetAddress.getLoopbackAddress(), port));
                byte[] buffer = new byte[8192];
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                socket.receive(response);
                byte[] packet = new byte[response.getLength()];
                System.arraycopy(response.getData(), response.getOffset(), packet,
                        0, packet.length);
                if (packet.length < 12) {
                    System.err.println("short response");
                    System.exit(3);
                    return;
                }
                System.out.println("rcode=" + DnsPacket.rcodeName(
                        DnsPacket.rcode(packet, packet.length)));
                int answers = 0;
                for (InetAddress address : DnsPacket.addresses(packet, packet.length)) {
                    System.out.println(address.getHostAddress());
                    answers++;
                }
                if (answers == 0) {
                    System.err.println("no A/AAAA answers");
                    System.exit(3);
                }
            }
        } catch (Exception e) {
            System.err.println("dns probe failed: " + e.getMessage());
            System.exit(2);
        }
    }
}
