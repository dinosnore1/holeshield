package com.dino.holeshield;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/** DoH and plain-UDP upstream resolution with a small TTL cache. */
interface Resolver {
    Upstream.Result resolve(byte[] payload, DnsPacket.Query query);
}

final class Upstream implements Resolver {

    static final class Result {
        final byte[] packet;
        final int rcode;
        final boolean cached;
        final String upstream;
        Result(byte[] packet, int rcode, boolean cached, String upstream) {
            this.packet = packet;
            this.rcode = rcode;
            this.cached = cached;
            this.upstream = upstream == null ? "" : upstream;
        }
    }

    private final DesktopPrefs prefs;
    private final DnsCache cache;
    private final HttpClient http;
    private volatile String lastError = "";

    Upstream(DesktopPrefs prefs, DnsCache cache) {
        this.prefs = prefs;
        this.cache = cache;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    DnsCache cache() {
        return cache;
    }

    void flush() {
        cache.clear();
    }

    String statusLine() {
        if (!lastError.isEmpty()) {
            return prefs.upstreamLabel() + " · " + lastError;
        }
        return prefs.upstreamLabel();
    }

    @Override
    public Result resolve(byte[] payload, DnsPacket.Query query) {
        String key = DnsCache.key(query.name, query.type, query.qclass);
        byte[] hit = cache.get(key);
        if (hit != null) {
            return new Result(hit.clone(), DnsPacket.rcode(hit, hit.length),
                    true, "cache");
        }

        boolean encrypted = !"plain".equals(prefs.get("upstreamMode", "doh"));
        Result result = null;
        if (encrypted) {
            result = resolveDoh(payload, query);
            if (result == null) {
                result = resolvePlain(payload, query);
            }
        } else {
            result = resolvePlain(payload, query);
        }

        if (result == null) {
            return new Result(null, 2, false, encrypted ? "DoH" : "plain");
        }

        if (result.packet != null) {
            int rcode = DnsPacket.rcode(result.packet, result.packet.length);
            if (rcode == 0 || rcode == 3) {
                cache.put(key, result.packet.clone(),
                        DnsPacket.minTtl(result.packet, result.packet.length));
            }
        }
        return result;
    }

    private Result resolveDoh(byte[] payload, DnsPacket.Query query) {
        String url = prefs.get("dohUrl", DesktopPrefs.DEFAULT_DOH);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/dns-message")
                    .header("Accept", "application/dns-message")
                    .header("User-Agent", "Hole-Shield-Desktop/2.3.4")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<byte[]> response = http.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                lastError = "DoH HTTP " + response.statusCode();
                return null;
            }
            byte[] body = response.body();
            if (body == null || body.length < 12 || (body[2] & 0x80) == 0) {
                lastError = "DoH returned an invalid packet";
                return null;
            }
            lastError = "";
            return new Result(body, DnsPacket.rcode(body, body.length), false, "DoH");
        } catch (Exception e) {
            lastError = "DoH unavailable";
            return null;
        }
    }

    private Result resolvePlain(byte[] payload, DnsPacket.Query query) {
        String target = prefs.get("plainUpstream", DesktopPrefs.DEFAULT_PLAIN).trim();
        String host = target;
        int port = 53;
        int colon = target.lastIndexOf(':');
        if (colon > 0 && target.indexOf(':') == colon) {
            host = target.substring(0, colon);
            try {
                port = Integer.parseInt(target.substring(colon + 1));
            } catch (Exception e) {
                port = 53;
            }
        }
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(4000);
            InetAddress address = InetAddress.getByName(host);
            socket.send(new DatagramPacket(payload, payload.length, address, port));
            byte[] buffer = new byte[8192];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            byte[] packet = new byte[response.getLength()];
            System.arraycopy(response.getData(), response.getOffset(), packet, 0,
                    response.getLength());
            if (packet.length < 12 || (packet[2] & 0x80) == 0) {
                lastError = "Plain DNS returned an invalid packet";
                return null;
            }
            lastError = "";
            String label = "plain " + host;
            return new Result(packet, DnsPacket.rcode(packet, packet.length),
                    false, label);
        } catch (Exception e) {
            lastError = "Plain DNS unavailable";
            return null;
        }
    }

    static String describe(DesktopPrefs prefs) {
        if ("plain".equals(prefs.get("upstreamMode", "doh"))) {
            return "Plain DNS · " + prefs.get("plainUpstream", DesktopPrefs.DEFAULT_PLAIN);
        }
        String url = prefs.get("dohUrl", DesktopPrefs.DEFAULT_DOH);
        String host = url.replaceFirst("^https?://", "");
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        return "Encrypted DoH · " + host.toLowerCase(Locale.US);
    }
}
