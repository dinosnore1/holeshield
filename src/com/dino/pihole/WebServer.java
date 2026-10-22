package com.dino.pihole;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Embedded HTTP server: serves the Pi-hole-style dashboard and a useful subset
 * of the Pi-hole v5/v6 APIs. No third-party HTTP or JSON libraries are used
 * beyond Android's built-in org.json.
 */
final class WebServer {

    interface Host {
        void flushCache();
        String lanIp();
        boolean lanRunning();
        String lanError();
        default void blockingChanged() { }
    }

    private static final String TAG = "PiholeTv";
    private static final int MAX_BODY = 1024 * 1024;
    private static final long SESSION_MS = 24 * 60 * 60 * 1000L;

    private final Context context;
    private final int port;
    private final Host host;
    private final ConcurrentHashMap<String, Long> sessions = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile String lastError = "";
    private volatile String dashboard;
    private ServerSocket server;
    private Thread acceptThread;
    private ThreadPoolExecutor pool;

    WebServer(Context context, int port, Host host) {
        this.context = context.getApplicationContext();
        this.port = port;
        this.host = host;
    }

    boolean start() {
        if (running) return true;
        if (port < 1 || port > 65535) {
            lastError = "Invalid web port";
            return false;
        }
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("0.0.0.0", port));
            server.setSoTimeout(1000);
        } catch (Exception e) {
            lastError = "Port " + port + ": " + message(e);
            closeQuietly();
            return false;
        }
        pool = new ThreadPoolExecutor(4, 8, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(64),
                new ThreadPoolExecutor.DiscardPolicy());
        running = true;
        acceptThread = new Thread(this::acceptLoop, "web-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "dashboard listening on 0.0.0.0:" + port);
        return true;
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = server.accept();
                if (pool != null) pool.execute(() -> serve(socket));
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            Request req = readRequest(in);
            if (req == null) {
                sendText(out, 400, "Bad request");
                return;
            }
            route(req, out);
        } catch (Exception e) {
            // Clients close connections all the time; only log real failures.
            if (running) Log.d(TAG, "web request failed: " + message(e));
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    private void route(Request r, OutputStream out) throws Exception {
        if ("OPTIONS".equals(r.method)) {
            sendEmpty(out, 204);
            return;
        }
        String path = r.path;
        if ("/".equals(path)) {
            redirect(out, "/admin/");
            return;
        }
        if ("/admin".equals(path)) {
            redirect(out, "/admin/");
            return;
        }
        if ("/admin/".equals(path) || "/admin/index.html".equals(path)) {
            sendHtml(out, 200, dashboard());
            return;
        }
        if ("/favicon.ico".equals(path)) {
            sendEmpty(out, 204);
            return;
        }
        Prefs p = Prefs.load(context);
        if ("/admin/api.php".equals(path)) {
            if (!v5Authorized(r, p)) {
                sendJson(out, 401, "{\"status\":\"unauthorized\"}");
                return;
            }
            handleV5(r, out, p);
            return;
        }
        if (path.startsWith("/api/")) {
            if (!"/api/auth".equals(path) && !"/api/health".equals(path)
                    && !authorized(r, p)) {
                sendJson(out, 401, "{\"session\":{\"valid\":false,"
                        + "\"message\":\"authentication required\"}}");
                return;
            }
            handleApi(r, out, p);
            return;
        }
        sendText(out, 404, "Not found");
    }

    // ------------------------------------------------------------------ API v6

    private void handleApi(Request r, OutputStream out, Prefs p) throws Exception {
        String rest = r.path.substring(5);
        String[] seg = rest.split("/");
        String get = r.method;

        if ("auth".equals(rest)) {
            auth(r, out, p);
            return;
        }
        if ("health".equals(rest)) {
            sendJson(out, 200, "{\"status\":\"ok\"}");
            return;
        }
        if ("stats/summary".equals(rest)) {
            sendJson(out, 200, summary(p).toString());
            return;
        }
        if ("stats/top_domains".equals(rest)) {
            sendJson(out, 200, topDomains(r).toString());
            return;
        }
        if ("stats/top_clients".equals(rest)) {
            sendJson(out, 200, topClients(r).toString());
            return;
        }
        if ("stats/query_types".equals(rest)) {
            sendJson(out, 200, queryTypes().toString());
            return;
        }
        if ("stats/upstreams".equals(rest)) {
            sendJson(out, 200, upstreams().toString());
            return;
        }
        if ("queries".equals(rest)) {
            sendJson(out, 200, queries(r).toString());
            return;
        }
        if ("dns/blocking".equals(rest)) {
            if ("POST".equals(get)) {
                sendJson(out, 200, setBlocking(r, out, p).toString());
            } else {
                JSONObject o = new JSONObject();
                o.put("blocking", p.blockingActive());
                long left = p.blockingRemainingSeconds();
                o.put("timer", left < 0 ? JSONObject.NULL : left);
                o.put("took", 0.001);
                sendJson(out, 200, o.toString());
            }
            return;
        }
        if (seg.length >= 1 && "domains".equals(seg[0])) {
            domains(r, out, seg);
            return;
        }
        if (seg.length >= 1 && "lists".equals(seg[0])) {
            lists(r, out, seg, p);
            return;
        }
        if ("action/flush".equals(rest) || "cache/flush".equals(rest)) {
            if (host != null) host.flushCache();
            sendJson(out, 200, "{\"flushed\":true,\"took\":0.001}");
            return;
        }
        if ("action/gravity".equals(rest)) {
            if (Blocklist.isUpdating()) {
                sendJson(out, 409, "{\"started\":false,\"message\":\"An update is already running\"}");
            } else {
                Blocklist.updateAsync(context, null);
                sendJson(out, 202, "{\"started\":true}");
            }
            return;
        }
        if ("info/host".equals(rest)) {
            sendJson(out, 200, info(p).toString());
            return;
        }
        if ("info/version".equals(rest)) {
            JSONObject o = new JSONObject();
            o.put("version", "2.0.0");
            o.put("name", "Pi-hole TV");
            o.put("api", "v6-subset");
            sendJson(out, 200, o.toString());
            return;
        }
        sendJson(out, 404, "{\"error\":\"unknown endpoint\"}");
    }

    private JSONObject summary(Prefs p) throws Exception {
        JSONObject root = new JSONObject();
        JSONObject q = new JSONObject();
        q.put("total", Stats.queries());
        q.put("blocked", Stats.blocked());
        q.put("percent_blocked", Stats.rate());
        q.put("unique_domains", Stats.uniqueDomains());
        q.put("forwarded", Stats.forwarded());
        q.put("cached", Stats.cached());
        q.put("errors", Stats.errors());

        JSONObject gravity = new JSONObject();
        gravity.put("domains_being_blocked", Blocklist.blockedCount());
        // Pi-hole's API uses Unix seconds. Keep a millisecond copy for our
        // own dashboard, which formats relative times.
        gravity.put("last_update", p.lastUpdate > 0 ? p.lastUpdate / 1000L : JSONObject.NULL);
        if (p.lastUpdate > 0) gravity.put("last_update_ms", p.lastUpdate);

        JSONObject clients = new JSONObject();
        clients.put("active", Stats.clientCount());
        clients.put("total", Stats.clientCount());

        root.put("queries", q);
        root.put("gravity", gravity);
        root.put("clients", clients);
        root.put("blocking", p.blockingActive());
        root.put("took", 0.001);
        return root;
    }

    private JSONObject topDomains(Request r) throws Exception {
        boolean blocked = !"false".equalsIgnoreCase(r.param("blocked", "true"));
        int count = clamp(parseInt(r.param("count", "10"), 10), 1, 100);
        JSONArray arr = new JSONArray();
        for (Stats.Top t : Stats.topDomains(blocked, count)) {
            JSONObject o = new JSONObject();
            o.put("domain", t.key);
            o.put("count", t.count);
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("domains", arr);
        root.put("total_queries", blocked ? Stats.blocked() : Stats.queries() - Stats.blocked());
        root.put("took", 0.001);
        return root;
    }

    private JSONObject topClients(Request r) throws Exception {
        int count = clamp(parseInt(r.param("count", "10"), 10), 1, 100);
        JSONArray arr = new JSONArray();
        for (Stats.Top t : Stats.topClients(count)) {
            JSONObject o = new JSONObject();
            o.put("ip", t.key);
            o.put("name", DnsEngine.clientName(t.key));
            o.put("count", t.count);
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("clients", arr);
        root.put("took", 0.001);
        return root;
    }

    private JSONObject queryTypes() throws Exception {
        JSONArray arr = new JSONArray();
        for (Stats.Top t : Stats.queryTypes()) {
            JSONObject o = new JSONObject();
            o.put("type", t.key);
            o.put("count", t.count);
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("types", arr);
        root.put("took", 0.001);
        return root;
    }

    private JSONObject upstreams() throws Exception {
        JSONArray arr = new JSONArray();
        for (Stats.Top t : Stats.topUpstreams(16)) {
            JSONObject o = new JSONObject();
            o.put("upstream", t.key);
            o.put("count", t.count);
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("upstreams", arr);
        root.put("took", 0.001);
        return root;
    }

    private JSONObject queries(Request r) throws Exception {
        int start = clamp(parseInt(r.param("start", "0"), 0), 0, 100000);
        int length = clamp(parseInt(r.param("length", "100"), 100), 1, 500);
        String blocked = r.param("blocked", "");
        String client = r.param("client", "");
        String domain = r.param("domain", "");
        String search = r.param("search", "").toLowerCase(Locale.US);
        List<Stats.Entry> all = Stats.log(2000);
        JSONArray arr = new JSONArray();
        int skipped = 0;
        int id = 0;
        for (Stats.Entry e : all) {
            id++;
            if (!blocked.isEmpty()) {
                boolean want = "true".equalsIgnoreCase(blocked) || "1".equals(blocked);
                if (want != e.blocked) continue;
            }
            if (!client.isEmpty() && !e.client.toLowerCase(Locale.US)
                    .contains(client.toLowerCase(Locale.US))) continue;
            if (!domain.isEmpty() && !e.domain.toLowerCase(Locale.US)
                    .contains(domain.toLowerCase(Locale.US))) continue;
            if (!search.isEmpty()
                    && !e.domain.toLowerCase(Locale.US).contains(search)
                    && !e.client.toLowerCase(Locale.US).contains(search)
                    && !DnsEngine.clientName(e.client).toLowerCase(Locale.US)
                    .contains(search)) continue;
            if (skipped++ < start) continue;
            if (arr.length() >= length) break;
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("time", e.at / 1000.0);
            o.put("type", e.typeName());
            o.put("domain", e.domain);
            JSONObject c = new JSONObject();
            c.put("ip", e.client);
            c.put("name", DnsEngine.clientName(e.client));
            o.put("client", c);
            o.put("status", e.status);
            JSONObject reply = new JSONObject();
            reply.put("type", "IP");
            reply.put("time", e.ms / 1000.0);
            o.put("reply", reply);
            o.put("upstream", e.upstream);
            o.put("rcode", DnsPacket.rcodeName(e.rcode));
            o.put("rule", e.rule);
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("queries", arr);
        root.put("cursor", start + arr.length());
        root.put("took", 0.001);
        return root;
    }

    private JSONObject setBlocking(Request r, OutputStream out, Prefs p) throws Exception {
        JSONObject body = bodyJson(r);
        boolean blocking = body.optBoolean("blocking", true);
        int timer = body.optInt("timer", 0);
        if (blocking) {
            p.resumeBlocking();
        } else {
            p.pauseBlocking(timer);
        }
        p.save(context);
        if (host != null) host.blockingChanged();
        JSONObject o = new JSONObject();
        o.put("blocking", p.blockingActive());
        o.put("timer", blocking || timer <= 0 ? JSONObject.NULL : timer);
        o.put("took", 0.001);
        return o;
    }

    private void auth(Request r, OutputStream out, Prefs p) throws Exception {
        String password = p.webPassword == null ? "" : p.webPassword;
        if ("GET".equals(r.method)) {
            boolean ok = password.isEmpty() || authorized(r, p);
            JSONObject s = new JSONObject();
            s.put("valid", ok);
            JSONObject root = new JSONObject();
            root.put("session", s);
            sendJson(out, ok ? 200 : 401, root.toString());
            return;
        }
        String supplied = "";
        JSONObject body = bodyJson(r);
        if (body.has("password")) supplied = body.optString("password", "");
        if (supplied.isEmpty()) supplied = r.param("password", "");
        if (!password.isEmpty() && !password.equals(supplied)) {
            sendJson(out, 401, "{\"session\":{\"valid\":false,"
                    + "\"message\":\"password incorrect\"}}");
            return;
        }
        String sid = UUID.randomUUID().toString().replace("-", "");
        String csrf = UUID.randomUUID().toString().replace("-", "");
        sessions.put(sid, System.currentTimeMillis() + SESSION_MS);
        JSONObject s = new JSONObject();
        s.put("valid", true);
        s.put("sid", sid);
        s.put("csrf", csrf);
        s.put("validity", SESSION_MS / 1000);
        JSONObject root = new JSONObject();
        root.put("session", s);
        root.put("took", 0.001);
        sendJson(out, 200, root.toString());
    }

    // --------------------------------------------------------------- domains

    private void domains(Request r, OutputStream out, String[] seg) throws Exception {
        if (seg.length < 2) {
            sendJson(out, 400, "{\"error\":\"type required (allow|deny)\"}");
            return;
        }
        boolean allow;
        if ("allow".equals(seg[1])) allow = true;
        else if ("deny".equals(seg[1])) allow = false;
        else {
            sendJson(out, 400, "{\"error\":\"type must be allow or deny\"}");
            return;
        }
        int kind = Blocklist.EXACT;
        if (seg.length >= 3 && !seg[2].isEmpty()) {
            if ("wildcard".equals(seg[2])) kind = Blocklist.WILDCARD;
            else if ("regex".equals(seg[2])) kind = Blocklist.REGEX;
            else if (!"exact".equals(seg[2])) {
                sendJson(out, 400, "{\"error\":\"kind must be exact, wildcard or regex\"}");
                return;
            }
        }
        if ("GET".equals(r.method)) {
            JSONArray arr = new JSONArray();
            for (Blocklist.Rule rule : Blocklist.rules(allow)) {
                if (rule.kind != kind) continue;
                JSONObject o = new JSONObject();
                o.put("domain", rule.value);
                o.put("type", allow ? "allow" : "deny");
                o.put("kind", rule.kindName());
                o.put("enabled", true);
                o.put("comment", "");
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("domains", arr);
            root.put("took", 0.001);
            sendJson(out, 200, root.toString());
            return;
        }
        if ("POST".equals(r.method)) {
            JSONObject body = bodyJson(r);
            String domain = body.optString("domain", r.param("domain", ""));
            if (domain.isEmpty()) {
                sendJson(out, 400, "{\"error\":\"domain required\"}");
                return;
            }
            boolean ok = Blocklist.addRule(context, allow, kind, domain);
            if (!ok) {
                sendJson(out, 400, "{\"error\":\"invalid domain or regex\"}");
                return;
            }
            JSONObject o = new JSONObject();
            o.put("domain", domain);
            o.put("type", allow ? "allow" : "deny");
            o.put("kind", kindName(kind));
            o.put("enabled", true);
            sendJson(out, 201, o.toString());
            return;
        }
        if ("DELETE".equals(r.method)) {
            String domain = r.param("domain", "");
            if (domain.isEmpty() && seg.length >= 4) domain = decode(seg[3], false);
            if (domain.isEmpty()) {
                sendJson(out, 400, "{\"error\":\"domain required\"}");
                return;
            }
            boolean ok = Blocklist.removeRule(context, allow, kind, domain);
            if (!ok) {
                sendJson(out, 404, "{\"error\":\"rule not found\"}");
                return;
            }
            sendJson(out, 200, "{\"removed\":true}");
            return;
        }
        sendJson(out, 405, "{\"error\":\"method not allowed\"}");
    }

    private static String kindName(int kind) {
        return kind == Blocklist.WILDCARD ? "wildcard"
                : kind == Blocklist.REGEX ? "regex" : "exact";
    }

    // ------------------------------------------------------------------ lists

    private void lists(Request r, OutputStream out, String[] seg, Prefs p)
            throws Exception {
        if ("GET".equals(r.method)) {
            JSONArray arr = new JSONArray();
            List<String> urls = listUrls(p.blocklistUrls);
            for (int i = 0; i < urls.size(); i++) {
                JSONObject o = new JSONObject();
                o.put("id", i);
                o.put("address", urls.get(i));
                o.put("type", "block");
                o.put("enabled", true);
                o.put("comment", "");
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("lists", arr);
            root.put("took", 0.001);
            sendJson(out, 200, root.toString());
            return;
        }
        if ("POST".equals(r.method)) {
            JSONObject body = bodyJson(r);
            String address = body.optString("address", r.param("address", "")).trim();
            if (!address.startsWith("http://") && !address.startsWith("https://")) {
                sendJson(out, 400, "{\"error\":\"http(s) URL required\"}");
                return;
            }
            String current = p.blocklistUrls == null ? "" : p.blocklistUrls.trim();
            if (listUrls(current).contains(address)) {
                sendJson(out, 409, "{\"error\":\"list already exists\"}");
                return;
            }
            p.blocklistUrls = current.isEmpty() ? address : current + "\n" + address;
            p.save(context);
            JSONObject o = new JSONObject();
            o.put("address", address);
            o.put("type", "block");
            o.put("enabled", true);
            sendJson(out, 201, o.toString());
            return;
        }
        if ("DELETE".equals(r.method)) {
            List<String> urls = listUrls(p.blocklistUrls);
            int id = parseInt(r.param("id", seg.length >= 2 ? seg[1] : "-1"), -1);
            if (id < 0 || id >= urls.size()) {
                sendJson(out, 404, "{\"error\":\"list not found\"}");
                return;
            }
            urls.remove(id);
            p.blocklistUrls = joinLines(urls);
            p.save(context);
            sendJson(out, 200, "{\"removed\":true}");
            return;
        }
        sendJson(out, 405, "{\"error\":\"method not allowed\"}");
    }

    private JSONObject info(Prefs p) throws Exception {
        JSONObject root = new JSONObject();
        JSONObject h = new JSONObject();
        h.put("hostname", "Pi-hole TV");
        h.put("ip", host == null ? NetInfo.lanIpv4(context) : host.lanIp());
        h.put("web_port", p.webPort);
        h.put("lan_dns_port", p.lanDnsPort);
        h.put("lan_dns_running", host != null && host.lanRunning());
        h.put("lan_dns_error", host == null ? "" : host.lanError());
        h.put("lan_dns_note", "Port 53 needs root; use the high port on this TV.");
        h.put("version", "2.0.0");
        h.put("blocking", p.blockingActive());
        h.put("web_password", !(p.webPassword == null || p.webPassword.isEmpty()));
        root.put("host", h);
        root.put("took", 0.001);
        return root;
    }

    // ------------------------------------------------------------------- v5 API

    private void handleV5(Request r, OutputStream out, Prefs p) throws Exception {
        if (r.has("enable")) {
            p.resumeBlocking();
            p.save(context);
            if (host != null) host.blockingChanged();
            sendJson(out, 200, "{\"status\":\"enabled\"}");
            return;
        }
        if (r.has("disable")) {
            int timer = parseInt(r.param("disable", "0"), 0);
            p.pauseBlocking(timer);
            p.save(context);
            if (host != null) host.blockingChanged();
            sendJson(out, 200, "{\"status\":\"disabled\"}");
            return;
        }
        if (r.has("status")) {
            sendJson(out, 200, "{\"status\":\""
                    + (p.blockingActive() ? "enabled" : "disabled") + "\"}");
            return;
        }
        if (r.has("versions")) {
            sendJson(out, 200, "{\"core\":{\"version\":\"v2.0\"},"
                    + "\"web\":{\"version\":\"v2.0\"},\"ftl\":{\"version\":\"embedded\"}}");
            return;
        }
        if (r.has("topItems")) {
            int n = clamp(parseInt(r.param("topItems", "10"), 10), 1, 100);
            JSONObject o = new JSONObject();
            JSONObject topQueries = new JSONObject();
            for (Stats.Top t : Stats.topDomains(false, n)) topQueries.put(t.key, t.count);
            JSONObject topAds = new JSONObject();
            for (Stats.Top t : Stats.topDomains(true, n)) topAds.put(t.key, t.count);
            o.put("top_queries", topQueries);
            o.put("top_ads", topAds);
            sendJson(out, 200, o.toString());
            return;
        }
        if (r.has("recentBlocked")) {
            List<Stats.Blocked> recent = Stats.recent();
            sendJson(out, 200, "\"" + (recent.isEmpty() ? "" : recent.get(0).name) + "\"");
            return;
        }
        JSONObject o = new JSONObject();
        o.put("domains_being_blocked", Blocklist.blockedCount());
        o.put("dns_queries_today", Stats.queries());
        o.put("ads_blocked_today", Stats.blocked());
        o.put("ads_percentage_today", Stats.rate());
        o.put("unique_domains", Stats.uniqueDomains());
        o.put("queries_forwarded", Stats.forwarded());
        o.put("queries_cached", Stats.cached());
        o.put("clients_ever_seen", Stats.clientCount());
        o.put("unique_clients", Stats.clientCount());
        o.put("status", p.blockingActive() ? "enabled" : "disabled");
        o.put("gravity_last_updated", p.lastUpdate / 1000.0);
        sendJson(out, 200, o.toString());
    }

    // ------------------------------------------------------------------ auth

    private boolean authorized(Request r, Prefs p) {
        String password = p.webPassword == null ? "" : p.webPassword;
        if (password.isEmpty()) return true;
        String sid = r.header("x-ftl-sid");
        if (sid == null || sid.isEmpty()) sid = r.param("sid", "");
        if (sid == null || sid.isEmpty()) sid = cookie(r, "sid");
        if (sid != null && !sid.isEmpty()) {
            Long until = sessions.get(sid);
            if (until != null && until > System.currentTimeMillis()) return true;
        }
        String auth = r.param("auth", "");
        return password.equals(auth);
    }

    private boolean v5Authorized(Request r, Prefs p) {
        String password = p.webPassword == null ? "" : p.webPassword;
        if (password.isEmpty()) return true;
        return password.equals(r.param("auth", ""));
    }

    private static String cookie(Request r, String name) {
        String raw = r.header("cookie");
        if (raw == null) return "";
        for (String part : raw.split(";")) {
            String s = part.trim();
            int eq = s.indexOf('=');
            if (eq > 0 && s.substring(0, eq).equals(name)) {
                return s.substring(eq + 1);
            }
        }
        return "";
    }

    // ------------------------------------------------------------ HTTP plumbing

    private static final class Request {
        String method = "GET";
        String path = "/";
        String query = "";
        String body = "";
        final Map<String, String> headers = new HashMap<>();
        final Map<String, String> params = new HashMap<>();

        String header(String name) { return headers.get(name); }
        String param(String name) { return params.get(name); }
        String param(String name, String fallback) {
            String v = params.get(name);
            return v == null ? fallback : v;
        }
        boolean has(String name) { return params.containsKey(name); }
    }

    private static Request readRequest(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2) return null;
        Request r = new Request();
        r.method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];
        int q = target.indexOf('?');
        if (q >= 0) {
            r.path = decode(target.substring(0, q), false);
            r.query = target.substring(q + 1);
        } else {
            r.path = decode(target, false);
        }
        parseParams(r.query, r.params);
        String header;
        int count = 0;
        while ((header = readLine(in)) != null && !header.isEmpty()) {
            if (++count > 100) throw new IOException("too many headers");
            int colon = header.indexOf(':');
            if (colon <= 0) continue;
            String key = header.substring(0, colon).trim().toLowerCase(Locale.US);
            String val = header.substring(colon + 1).trim();
            r.headers.put(key, val);
        }
        int len = 0;
        try {
            len = Integer.parseInt(r.header("content-length") == null
                    ? "0" : r.header("content-length"));
        } catch (Exception ignored) { }
        if (len < 0 || len > MAX_BODY) throw new IOException("body too large");
        if (len > 0) {
            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(body, off, len - off);
                if (n < 0) break;
                off += n;
            }
            r.body = new String(body, 0, off, StandardCharsets.UTF_8);
        }
        return r;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        boolean any = false;
        while ((c = in.read()) >= 0) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("line too long");
        }
        if (!any && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static void parseParams(String query, Map<String, String> into) {
        if (query == null || query.isEmpty()) return;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            into.put(decode(k, true), decode(v, true));
        }
    }

    private static String decode(String s, boolean plusAsSpace) {
        if (s == null || s.indexOf('%') < 0 && (!plusAsSpace || s.indexOf('+') < 0)) {
            return s == null ? "" : s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            if (plusAsSpace && c == '+') {
                out.write(' ');
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(bytes, 0, bytes.length);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private JSONObject bodyJson(Request r) {
        if (r.body == null || r.body.trim().isEmpty()) return new JSONObject();
        try {
            return new JSONObject(r.body);
        } catch (Exception e) {
            JSONObject o = new JSONObject();
            try {
                for (String pair : r.body.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) continue;
                    o.put(decode(pair.substring(0, eq), true),
                            decode(pair.substring(eq + 1), true));
                }
            } catch (Exception ignored) { }
            return o;
        }
    }

    private static void sendJson(OutputStream out, int code, String body) throws IOException {
        send(out, code, "application/json; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendHtml(OutputStream out, int code, String body) throws IOException {
        send(out, code, "text/html; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendText(OutputStream out, int code, String body) throws IOException {
        send(out, code, "text/plain; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendEmpty(OutputStream out, int code) throws IOException {
        send(out, code, "text/plain; charset=utf-8", new byte[0]);
    }

    private static void send(OutputStream out, int code, String type, byte[] body)
            throws IOException {
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: Content-Type, X-FTL-SID\r\n"
                + "Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n"
                + "X-Content-Type-Options: nosniff\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static void redirect(OutputStream out, String to) throws IOException {
        String head = "HTTP/1.1 302 Found\r\nLocation: " + to + "\r\n"
                + "Content-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 202: return "Accepted";
            case 204: return "No Content";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }

    private String dashboard() {
        String d = dashboard;
        if (d != null) return d;
        try (InputStream in = context.getAssets().open("dashboard.html")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            d = new String(b.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            d = "<!doctype html><meta charset=\"utf-8\"><title>Pi-hole TV</title>"
                    + "<body style=\"font-family:sans-serif;background:#111;color:#eee;"
                    + "padding:40px\"><h1>Pi-hole TV</h1><p>The dashboard asset is missing.</p>"
                    + "<p>API: <a style=\"color:#3c9\" href=\"/api/stats/summary\">"
                    + "/api/stats/summary</a></p></body>";
        }
        dashboard = d;
        return d;
    }

    private static List<String> listUrls(String text) {
        ArrayList<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\\n")) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) out.add(s);
        }
        return out;
    }

    private static String joinLines(List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (String s : lines) {
            if (b.length() > 0) b.append('\n');
            b.append(s);
        }
        return b.toString();
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return fallback; }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    void stop() {
        running = false;
        if (pool != null) {
            try { pool.shutdownNow(); } catch (Exception ignored) { }
            pool = null;
        }
        closeQuietly();
        acceptThread = null;
    }

    private void closeQuietly() {
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
        running = false;
    }

    String lastError() { return lastError; }
    int port() { return port; }
    boolean running() { return running; }
}
