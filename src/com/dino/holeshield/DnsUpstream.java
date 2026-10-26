package com.dino.holeshield;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.VpnService;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Answers DNS queries from the tun: local cache, Pi-hole over UDP, then an
 * encrypted (DoH) or plain fallback. All sockets are protected so the VPN
 * cannot capture its own upstream traffic.
 *
 * Host names are re-resolved in the background. That matters most when the
 * service starts at boot: Wi-Fi is often not ready yet, so the first lookup
 * fails and the resolver has to try again later instead of staying broken
 * until the user restarts protection.
 */
final class DnsUpstream {

    private static final String TAG = "PiholeTv";
    private static final int DNS_PORT = 53;
    private static final int CACHE_MAX = 1024;
    private static final long RESOLVE_RETRY_MS = 30000L;
    private static final long RESOLVE_OK_MS = 10 * 60 * 1000L;
    private static final long DOH_SKIP_MS = 30000L;

    interface Protector {
        boolean protect(DatagramSocket socket);
        boolean protect(Socket socket);
    }

    static final class Result {
        final byte[] packet;
        final boolean cached;
        final String upstream;
        final int rcode;
        Result(byte[] packet, boolean cached, String upstream, int rcode) {
            this.packet = packet;
            this.cached = cached;
            this.upstream = upstream == null ? "" : upstream;
            this.rcode = rcode;
        }
    }

    private final Context context;
    private final Protector protector;
    private final boolean usePihole;
    private final boolean useDoh;
    private final String piholeHost;
    private volatile List<InetAddress> piholeIps = Collections.emptyList();
    private final String dohHost;
    private final String dohPath;
    private final int dohPort;
    private volatile List<InetAddress> dohIps = Collections.emptyList();
    private final InetAddress[] plainIps;

    private final Object resolveLock = new Object();
    private boolean resolvingPihole;
    private boolean resolvingDoh;
    private volatile long piholeResolveAt;
    private volatile long dohResolveAt;

    private final Map<String, Cache> cache = new LinkedHashMap<String, Cache>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cache> eldest) {
            return size() > CACHE_MAX;
        }
    };

    private int piholeFailures;
    private volatile long piholeSkipUntil;
    private int dohFailures;
    private volatile long dohSkipUntil;
    private volatile String lastError = "";

    private static final class Cache {
        final byte[] packet;
        final long expires;
        Cache(byte[] packet, long expires) {
            this.packet = packet;
            this.expires = expires;
        }
    }

    DnsUpstream(Context context, Protector protector, Prefs p) {
        this.context = context;
        this.protector = protector;
        this.usePihole = p.usePihole;
        this.piholeHost = p.piholeDnsHost();

        if (usePihole && !isBlank(piholeHost)) {
            resolvePihole(false);                 // synchronous: VPN is not up yet
        }

        String url = p.dohUrl == null ? "" : p.dohUrl.trim();
        this.useDoh = "doh".equals(p.fallback);
        List<InetAddress> ips = new ArrayList<>();
        String host = "";
        String path = "/dns-query";
        int port = 443;
        if (useDoh) {
            try {
                URL u = new URL(url);
                host = u.getHost();
                path = u.getFile();
                if (path == null || path.isEmpty()) path = "/dns-query";
                if (u.getPort() > 0) port = u.getPort();
            } catch (Exception e) {
                lastError = "Cannot resolve " + url;
            }
        }
        this.dohHost = host;
        this.dohPath = path;
        this.dohPort = port;
        if (useDoh && !host.isEmpty()) {
            // Avoid a blocking DNS round-trip during startup for the built-in
            // endpoints; their anycast addresses are stable. Custom DoH hosts
            // are resolved synchronously here because the system resolver is
            // still usable while the service is being constructed.
            seedDohIps(host);
            if (dohIps.isEmpty()) resolveDoh(false);
        }

        if ("plain".equals(p.fallback) || "doh".equals(p.fallback)) {
            plainIps = new InetAddress[]{
                    ip("1.1.1.1"), ip("8.8.8.8"), ip("9.9.9.9")
            };
        } else {
            plainIps = new InetAddress[0];
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static InetAddress ip(String s) {
        try {
            return InetAddress.getByName(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The engine is created after the tun is established, so the system
     * resolver would ask this VPN before its reader is running. Seed the
     * well-known DoH endpoints with their anycast addresses to break that
     * circular dependency.
     */
    private void seedDohIps(String host) {
        if (!dohIps.isEmpty()) return;
        ArrayList<InetAddress> ips = new ArrayList<>();
        if ("cloudflare-dns.com".equalsIgnoreCase(host)) {
            addIp(ips, "1.1.1.1");
            addIp(ips, "1.0.0.1");
        } else if ("dns.google".equalsIgnoreCase(host)) {
            addIp(ips, "8.8.8.8");
            addIp(ips, "8.8.4.4");
        } else if ("dns.quad9.net".equalsIgnoreCase(host)) {
            addIp(ips, "9.9.9.9");
            addIp(ips, "149.112.112.112");
        }
        if (!ips.isEmpty()) dohIps = Collections.unmodifiableList(ips);
    }

    private static void addIp(List<InetAddress> out, String text) {
        InetAddress a = ip(text);
        if (a != null) out.add(a);
    }

    String statusLine() {
        if (!piholeIps.isEmpty()) return "Pi-hole " + piholeHost;
        if (useDoh && !dohIps.isEmpty()) return "Encrypted fallback";
        if (!lastError.isEmpty()) return lastError;
        if (plainIps.length > 0) return "Plain fallback";
        return "No upstream";
    }

    byte[] resolve(byte[] query, DnsPacket.Query q) {
        return resolveResult(query, q).packet;
    }

    Result resolveResult(byte[] query, DnsPacket.Query q) {
        String key = null;
        if (q.name != null && !q.name.isEmpty()) {
            key = q.name + "|" + q.type;
            synchronized (cache) {
                Cache c = cache.get(key);
                if (c != null) {
                    if (c.expires > System.currentTimeMillis()) {
                        byte[] copy = c.packet.clone();
                        DnsPacket.setTransactionId(copy, q.id);
                        int rcode = copy.length >= 4 ? (DnsPacket.u16(copy, 2) & 0x0F) : 0;
                        return new Result(copy, true, "cache", rcode);
                    }
                    cache.remove(key);
                }
            }
        }

        maybeResolvePihole();
        maybeResolveDoh();

        byte[] answer = null;
        String via = "none";
        if (usePihole && System.currentTimeMillis() >= piholeSkipUntil) {
            for (InetAddress ip : piholeIps) {
                answer = udpQuery(ip, query, q.id, 900);
                if (answer != null) break;
            }
            if (answer != null) {
                via = "pihole";
                piholeFailures = 0;
            } else if (!piholeIps.isEmpty()) {
                if (++piholeFailures >= 2) {
                    piholeSkipUntil = System.currentTimeMillis() + 45000L;
                }
                if (piholeFailures >= 3) {
                    // The address may have moved (DHCP) or DNS was not ready.
                    piholeResolveAt = 0L;
                    maybeResolvePihole();
                }
            }
        }

        if (answer == null && useDoh && System.currentTimeMillis() >= dohSkipUntil) {
            answer = dohQuery(query, q.id);
            if (answer != null) {
                via = "doh";
                dohFailures = 0;
            } else if (++dohFailures >= 2) {
                dohSkipUntil = System.currentTimeMillis() + DOH_SKIP_MS;
                dohResolveAt = 0L;
                maybeResolveDoh();
            }
        }
        if (answer == null && plainIps.length > 0) {
            answer = plainQuery(query, q.id);
            if (answer != null) via = "plain";
        }
        if (answer == null) return new Result(null, false, "none", 2);

        int rcode = answer.length >= 4 ? (DnsPacket.u16(answer, 2) & 0x0F) : 0;
        if (key != null && rcode != 2) {                     // never cache SERVFAIL
            int ttl = DnsPacket.minTtl(answer, answer.length);
            Cache c = new Cache(answer.clone(), System.currentTimeMillis() + ttl * 1000L);
            synchronized (cache) {
                cache.put(key, c);
            }
        }
        return new Result(answer, false, via, rcode);
    }

    void flushCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    // ---------------------------------------------------------- host resolution

    private void maybeResolvePihole() {
        if (!usePihole || isBlank(piholeHost)) return;
        if (System.currentTimeMillis() < piholeResolveAt) return;
        resolvePihole(true);
    }

    private void maybeResolveDoh() {
        if (!useDoh || isBlank(dohHost)) return;
        if (System.currentTimeMillis() < dohResolveAt) return;
        resolveDoh(true);
    }

    private void resolvePihole(boolean async) {
        synchronized (resolveLock) {
            if (resolvingPihole) return;
            resolvingPihole = true;
        }
        Runnable work = () -> {
            try {
                List<InetAddress> found = async ? retryLookup(piholeHost) : lookup(piholeHost);
                if (!found.isEmpty()) {
                    piholeIps = found;
                    lastError = "";
                    piholeResolveAt = System.currentTimeMillis() + RESOLVE_OK_MS;
                } else {
                    piholeResolveAt = System.currentTimeMillis() + RESOLVE_RETRY_MS;
                }
            } catch (Exception e) {
                lastError = "Cannot resolve Pi-hole host " + piholeHost;
                piholeResolveAt = System.currentTimeMillis() + RESOLVE_RETRY_MS;
            } finally {
                synchronized (resolveLock) { resolvingPihole = false; }
            }
        };
        if (async) {
            Thread t = new Thread(work, "pihole-resolve");
            t.setDaemon(true);
            t.start();
        } else {
            work.run();
        }
    }

    private void resolveDoh(boolean async) {
        synchronized (resolveLock) {
            if (resolvingDoh) return;
            resolvingDoh = true;
        }
        Runnable work = () -> {
            try {
                List<InetAddress> found = async ? retryLookup(dohHost) : lookup(dohHost);
                if (!found.isEmpty()) {
                    dohIps = found;
                    dohResolveAt = System.currentTimeMillis() + RESOLVE_OK_MS;
                } else {
                    dohResolveAt = System.currentTimeMillis() + RESOLVE_RETRY_MS;
                }
            } catch (Exception e) {
                lastError = "Cannot resolve " + dohHost;
                dohResolveAt = System.currentTimeMillis() + RESOLVE_RETRY_MS;
            } finally {
                synchronized (resolveLock) { resolvingDoh = false; }
            }
        };
        if (async) {
            Thread t = new Thread(work, "doh-resolve");
            t.setDaemon(true);
            t.start();
        } else {
            work.run();
        }
    }

    private static List<InetAddress> lookup(String host) throws Exception {
        InetAddress[] all = InetAddress.getAllByName(host);
        ArrayList<InetAddress> list = new ArrayList<>();
        for (InetAddress a : all) {
            if (a != null) list.add(a);
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * A retry runs while the VPN is up, so the system resolver would ask this
     * very service (circular for names like {@code pi.hole}). Ask the LAN DNS
     * servers directly through protected sockets first, then fall back.
     */
    private List<InetAddress> retryLookup(String host) {
        List<InetAddress> direct = directLookup(host);
        if (!direct.isEmpty()) return direct;
        try {
            return lookup(host);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<InetAddress> directLookup(String host) {
        if (isIpLiteral(host)) {
            try {
                return lookup(host);
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }
        Set<InetAddress> servers = new LinkedHashSet<>();
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network network : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps == null
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        continue;
                    }
                    LinkProperties lp = cm.getLinkProperties(network);
                    if (lp == null) continue;
                    for (InetAddress dns : lp.getDnsServers()) addServer(servers, dns);
                    for (RouteInfo route : lp.getRoutes()) {
                        if (route.isDefaultRoute()) addServer(servers, route.getGateway());
                    }
                }
            }
        } catch (Exception ignored) { }

        for (InetAddress server : servers) {
            List<InetAddress> found = queryServer(server, host);
            if (!found.isEmpty()) return found;
        }
        return Collections.emptyList();
    }

    private static void addServer(Set<InetAddress> out, InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return;
        }
        out.add(address);
    }

    private List<InetAddress> queryServer(InetAddress server, String host) {
        List<InetAddress> found = new ArrayList<>();
        for (int type : new int[]{DnsPacket.TYPE_A, DnsPacket.TYPE_AAAA}) {
            int id = (int) (System.nanoTime() & 0xFFFF);
            byte[] query = DnsPacket.buildQuery(id, host, type);
            if (query == null) continue;
            byte[] answer = udpQuery(server, query, id, 1200);
            if (answer == null) continue;
            for (InetAddress address : DnsPacket.addresses(answer, answer.length)) {
                if (!address.isAnyLocalAddress()) found.add(address);
            }
            if (!found.isEmpty()) break;
        }
        return found;
    }

    private static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) return false;
        if (host.indexOf(':') >= 0) return true;
        boolean dot = false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') { dot = true; continue; }
            if (c < '0' || c > '9') return false;
        }
        return dot;
    }

    // ------------------------------------------------------------------ queries

    private byte[] udpQuery(InetAddress address, byte[] query, int id, int timeoutMs) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            if (protector != null && !protector.protect(socket)) return null;
            socket.setSoTimeout(timeoutMs);
            socket.send(new DatagramPacket(query, query.length, address, DNS_PORT));
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs + 300L;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                socket.receive(in);
                if (in.getLength() >= 12 && in.getPort() == DNS_PORT
                        && address.equals(in.getAddress())
                        && validResponse(buf, in.getLength(), id)) {
                    byte[] out = new byte[in.getLength()];
                    System.arraycopy(buf, 0, out, 0, in.getLength());
                    return out;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (socket != null) socket.close();
        }
    }

    private byte[] plainQuery(byte[] query, int id) {
        for (InetAddress ip : plainIps) {
            if (ip == null) continue;
            byte[] answer = udpQuery(ip, query, id, 2000);
            if (answer != null) return answer;
        }
        return null;
    }

    private static boolean validResponse(byte[] packet, int len, int id) {
        if (len < 12) return false;
        if (DnsPacket.u16(packet, 0) != id) return false;
        return (DnsPacket.u16(packet, 2) & 0x8000) != 0;
    }

    private byte[] dohQuery(byte[] query, int id) {
        List<InetAddress> ips = dohIps;
        if (!useDoh || ips.isEmpty() || dohHost.isEmpty()) return null;
        for (InetAddress ip : ips) {
            byte[] answer = dohOnce(ip, query, id);
            if (answer != null) return answer;
        }
        return null;
    }

    private byte[] dohOnce(InetAddress ip, byte[] query, int id) {
        Socket raw = new Socket();
        SSLSocket ssl = null;
        try {
            // A fresh TCP Socket has no file descriptor until it is bound or
            // connected, and VpnService.protect(Socket) needs that descriptor.
            // Bind an ephemeral local port first so the socket can be protected
            // before its first packet is routed into our own tun.
            raw.bind(new InetSocketAddress(0));
            if (protector != null && !protector.protect(raw)) {
                Log.w(TAG, "DoH " + ip + " could not protect the socket");
                return null;
            }
            raw.connect(new InetSocketAddress(ip, dohPort), 3500);
            raw.setSoTimeout(5000);
            ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, dohHost, dohPort, true);
            SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            try {
                params.setServerNames(Collections.singletonList(new SNIHostName(dohHost)));
            } catch (Exception ignored) { }
            ssl.setSSLParameters(params);
            ssl.setSoTimeout(5000);
            ssl.startHandshake();

            StringBuilder head = new StringBuilder();
            head.append("POST ").append(dohPath).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(dohHost);
            if (dohPort != 443) head.append(':').append(dohPort);
            head.append("\r\n");
            head.append("Accept: application/dns-message\r\n");
            head.append("Content-Type: application/dns-message\r\n");
            head.append("Content-Length: ").append(query.length).append("\r\n");
            head.append("User-Agent: Pi-hole-TV/1.0\r\n");
            head.append("Connection: close\r\n\r\n");
            OutputStream out = ssl.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(query);
            out.flush();

            ByteArrayOutputStream all = new ByteArrayOutputStream(1024);
            InputStream in = ssl.getInputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) {
                all.write(buf, 0, n);
                if (all.size() > 131072) break;
            }
            byte[] answer = parseHttpBody(all.toByteArray(), id);
            if (answer == null) {
                Log.w(TAG, "DoH " + ip + " returned an unusable response ("
                        + all.size() + " bytes)");
            }
            return answer;
        } catch (Exception e) {
            Log.w(TAG, "DoH " + ip + " failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()));
            return null;
        } finally {
            try {
                if (ssl != null) ssl.close();
            } catch (Exception ignored) { }
            try {
                raw.close();
            } catch (Exception ignored) { }
        }
    }

    private static byte[] parseHttpBody(byte[] response, int id) {
        int headEnd = -1;
        for (int i = 0; i + 3 < response.length; i++) {
            if (response[i] == '\r' && response[i + 1] == '\n'
                    && response[i + 2] == '\r' && response[i + 3] == '\n') {
                headEnd = i;
                break;
            }
        }
        if (headEnd < 0) return null;
        String headText = new String(response, 0, headEnd, StandardCharsets.ISO_8859_1);
        String[] lines = headText.split("\r\n");
        if (lines.length == 0 || !lines[0].contains(" 200")) return null;

        boolean chunked = false;
        int contentLength = -1;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            if (name.equals("content-length")) {
                try { contentLength = Integer.parseInt(value); } catch (Exception ignored) { }
            } else if (name.equals("transfer-encoding")
                    && value.toLowerCase(Locale.US).contains("chunked")) {
                chunked = true;
            }
        }

        int bodyOff = headEnd + 4;
        byte[] body;
        if (chunked) {
            body = dechunk(response, bodyOff);
        } else if (contentLength >= 0 && bodyOff + contentLength <= response.length) {
            body = new byte[contentLength];
            System.arraycopy(response, bodyOff, body, 0, contentLength);
        } else {
            body = new byte[response.length - bodyOff];
            System.arraycopy(response, bodyOff, body, 0, body.length);
        }
        if (body == null || !validResponse(body, body.length, id)) return null;
        return body;
    }

    private static byte[] dechunk(byte[] data, int off) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        int p = off;
        while (p < data.length) {
            int lineEnd = -1;
            for (int i = p; i + 1 < data.length; i++) {
                if (data[i] == '\r' && data[i + 1] == '\n') { lineEnd = i; break; }
            }
            if (lineEnd < 0) return null;
            String sizeText = new String(data, p, lineEnd - p, StandardCharsets.US_ASCII).trim();
            int semi = sizeText.indexOf(';');
            if (semi >= 0) sizeText = sizeText.substring(0, semi);
            int size;
            try { size = Integer.parseInt(sizeText, 16); } catch (Exception e) { return null; }
            p = lineEnd + 2;
            if (size == 0) break;
            if (p + size > data.length) return null;
            out.write(data, p, size);
            p += size + 2;
        }
        return out.toByteArray();
    }
}
