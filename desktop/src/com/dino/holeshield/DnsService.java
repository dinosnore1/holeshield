package com.dino.holeshield;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local DNS listener. UDP and TCP share the same port, exactly like a normal
 * resolver. The service keeps running when the dashboard or UI is closed.
 */
final class DnsService {

    interface Listener {
        void changed();
    }

    private final DesktopPrefs prefs;
    private final FilterEngine filter;
    private final StatsStore stats;
    private final Resolver resolver;

    private volatile boolean running;
    private volatile int port;
    private volatile String bindError = "";
    private volatile String tcpError = "";
    private DatagramSocket udp;
    private ServerSocket tcp;
    private Thread udpThread;
    private Thread tcpThread;
    private ExecutorService pool;
    private Listener listener;

    DnsService(DesktopPrefs prefs, FilterEngine filter, StatsStore stats,
               Resolver resolver) {
        this.prefs = prefs;
        this.filter = filter;
        this.stats = stats;
        this.resolver = resolver;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    synchronized boolean start() {
        stop();
        int configured = prefs.getInt("dnsPort", 53);
        String bind = prefs.get("bindAddress", "0.0.0.0");
        bindError = "";
        tcpError = "";
        boolean bound = bind(configured, bind);
        if (!bound && configured == 53) {
            bound = bind(5354, bind);
            if (bound) {
                bindError = "Port 53 needs administrator rights; listening on 5354 instead.";
            }
        }
        if (!bound) {
            if (bindError.isEmpty()) {
                bindError = "Could not bind DNS on port " + configured
                        + ". Another resolver may be using it.";
            }
            running = false;
            return false;
        }
        running = true;
        pool = Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "dns-worker");
            t.setDaemon(true);
            return t;
        });
        udpThread = new Thread(this::udpLoop, "dns-udp");
        udpThread.setDaemon(true);
        udpThread.start();
        tcpThread = new Thread(this::tcpLoop, "dns-tcp");
        tcpThread.setDaemon(true);
        tcpThread.start();
        if (listener != null) listener.changed();
        return true;
    }

    private boolean bind(int wanted, String address) {
        try {
            InetSocketAddress udpAddress;
            if (address == null || address.isEmpty() || "0.0.0.0".equals(address)
                    || "*".equals(address)) {
                udpAddress = new InetSocketAddress(wanted);
            } else {
                udpAddress = new InetSocketAddress(InetAddress.getByName(address), wanted);
            }
            DatagramSocket socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(udpAddress);
            socket.setReceiveBufferSize(256 * 1024);
            udp = socket;
            port = socket.getLocalPort();

            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            if (udpAddress.getAddress() == null) {
                server.bind(new InetSocketAddress(port));
            } else {
                server.bind(new InetSocketAddress(udpAddress.getAddress(), port));
            }
            tcp = server;
            return true;
        } catch (Exception e) {
            bindError = "Could not bind DNS: " + message(e);
            try { if (udp != null) udp.close(); } catch (Exception ignored) { }
            udp = null;
            return false;
        }
    }

    synchronized void stop() {
        running = false;
        try { if (udp != null) udp.close(); } catch (Exception ignored) { }
        try { if (tcp != null) tcp.close(); } catch (Exception ignored) { }
        udp = null;
        tcp = null;
        if (pool != null) pool.shutdownNow();
        pool = null;
        if (listener != null) listener.changed();
    }

    private void udpLoop() {
        byte[] buffer = new byte[4096];
        while (running && udp != null) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udp.receive(packet);
                byte[] payload = Arrays.copyOf(packet.getData(), packet.getLength());
                InetAddress address = packet.getAddress();
                int remotePort = packet.getPort();
                ExecutorService executor = pool;
                if (executor == null) continue;
                executor.submit(() -> {
                    try {
                        byte[] reply = resolve(payload, address.getHostAddress());
                        if (reply == null || udp == null) return;
                        udp.send(new DatagramPacket(reply, reply.length, address, remotePort));
                    } catch (Exception ignored) { }
                });
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    private void tcpLoop() {
        while (running && tcp != null) {
            try {
                Socket socket = tcp.accept();
                ExecutorService executor = pool;
                if (executor == null) {
                    socket.close();
                    continue;
                }
                executor.submit(() -> handleTcp(socket));
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    private void handleTcp(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(10000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            String client = s.getInetAddress().getHostAddress();
            while (running) {
                int length;
                try {
                    length = in.readUnsignedShort();
                } catch (EOFException | SocketTimeoutException e) {
                    break;
                }
                if (length < 12 || length > 65535) break;
                byte[] payload = new byte[length];
                in.readFully(payload);
                byte[] reply = resolve(payload, client);
                if (reply == null) break;
                out.writeShort(reply.length);
                out.write(reply);
                out.flush();
            }
        } catch (Exception ignored) {
            // A client closing early is normal.
        }
    }

    /** @return a DNS response, or null when the packet is not a query. */
    byte[] resolve(byte[] payload, String clientIp) {
        DnsPacket.Query q;
        try {
            q = DnsPacket.parseQuery(payload, payload.length);
        } catch (Exception e) {
            return null;
        }
        if (q == null || q.name.isEmpty()) return null;

        String client = clientIp == null || clientIp.isEmpty() ? "unknown" : clientIp;
        long start = System.nanoTime();
        boolean canBlock = prefs.blockingActive()
                && q.qclass == 1 && DnsPacket.isBlockableType(q.type);

        FilterEngine.Decision decision = canBlock
                ? filter.check(q.name, clientIp) : FilterEngine.Decision.NONE;

        if (decision.blocked) {
            byte[] reply = DnsPacket.blockedReply(q, prefs.blockedResponseMode());
            stats.record(new StatsStore.Entry(System.currentTimeMillis(), client,
                    q.name, q.type, "BLOCKED", decision.source, 0, elapsed(start),
                    decision.rule, decision.category, true, false));
            return reply;
        }

        Upstream.Result result = resolver.resolve(payload, q);
        if (result == null || result.packet == null) {
            byte[] reply = DnsPacket.errorReply(q, 2);
            stats.record(new StatsStore.Entry(System.currentTimeMillis(), client,
                    q.name, q.type, "ERROR", result == null ? "none" : result.upstream,
                    2, elapsed(start), decision.allowed ? decision.rule : "",
                    decision.category, false, false));
            return reply;
        }

        if (canBlock && prefs.getBool("blockCname", true) && !decision.allowed) {
            List<String> targets = DnsPacket.cnameTargets(result.packet,
                    result.packet.length);
            for (String target : targets) {
                FilterEngine.Decision cname = filter.check(target, clientIp);
                if (cname.blocked) {
                    byte[] reply = DnsPacket.blockedReply(q, prefs.blockedResponseMode());
                    stats.record(new StatsStore.Entry(System.currentTimeMillis(), client,
                            q.name, q.type, "BLOCKED", cname.source,
                            DnsPacket.rcode(result.packet, result.packet.length),
                            elapsed(start), "CNAME " + target, cname.category,
                            true, false));
                    return reply;
                }
            }
        }

        byte[] reply = result.packet.clone();
        DnsPacket.setTransactionId(reply, q.id);
        String status;
        if (decision.allowed) status = "ALLOWED";
        else if (result.cached) status = "CACHED";
        else status = "FORWARDED";
        stats.record(new StatsStore.Entry(System.currentTimeMillis(), client, q.name,
                q.type, status, result.upstream, DnsPacket.rcode(reply, reply.length),
                elapsed(start), decision.allowed ? decision.rule : "",
                decision.category, false, result.cached));
        return reply;
    }

    static String clientName(String ip) {
        if (ip == null || ip.isEmpty()) return "unknown";
        if ("127.0.0.1".equals(ip) || "::1".equals(ip)
                || "0:0:0:0:0:0:0:1".equals(ip)
                || ip.startsWith("127.")) {
            return "this computer";
        }
        return ip;
    }

    private static long elapsed(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        return Math.max(0, ms);
    }

    void flushCache() {
        if (resolver instanceof Upstream) ((Upstream) resolver).flush();
    }

    boolean running() { return running; }
    int actualPort() { return port; }
    String bindError() { return bindError; }
    String tcpError() { return tcpError; }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
