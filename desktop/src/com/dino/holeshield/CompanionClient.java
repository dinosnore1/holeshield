package com.dino.holeshield;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Logs in to a Hole Shield dashboard that is already running on the network.
 *
 * <p>Companion mode uses the same public API as the web dashboard: the client
 * authenticates against {@code /api/auth}, keeps the session id in the
 * {@code X-FTL-SID} header and reads the summary from
 * {@code /api/stats/summary}. Only JDK classes are used, and the account
 * password never leaves this computer except for the login request.</p>
 */
final class CompanionClient {

    /** Thrown when the remote host answered but rejected the credentials. */
    static final class AuthException extends IOException {
        AuthException(String message) {
            super(message);
        }
    }

    /** A point-in-time copy of /api/stats/summary. */
    static final class Snapshot {
        long queries;
        long blocked;
        long uniqueDomains;
        long forwarded;
        long cached;
        long errors;
        long gravityDomains;
        long lastUpdate;
        int clients;
        double rate;
        boolean blocking = true;
        boolean scheduleEnabled;
        String scheduleLabel = "";
        String version = "";
        String name = "";
        final Map<String, Long> categories = new LinkedHashMap<>();

        static Snapshot from(Map<String, Object> root) {
            Snapshot snap = new Snapshot();
            Map<String, Object> queries = object(root.get("queries"));
            snap.queries = number(queries.get("total"));
            snap.blocked = number(queries.get("blocked"));
            snap.uniqueDomains = number(queries.get("unique_domains"));
            snap.forwarded = number(queries.get("forwarded"));
            snap.cached = number(queries.get("cached"));
            snap.errors = number(queries.get("errors"));
            snap.rate = decimal(queries.get("percent_blocked"));

            Map<String, Object> gravity = object(root.get("gravity"));
            snap.gravityDomains = number(gravity.get("domains_being_blocked"));
            snap.lastUpdate = number(gravity.get("last_update_ms"));
            if (snap.lastUpdate <= 0) {
                long seconds = number(gravity.get("last_update"));
                if (seconds > 0) snap.lastUpdate = seconds * 1000L;
            }

            Map<String, Object> clients = object(root.get("clients"));
            snap.clients = (int) Math.min(Integer.MAX_VALUE,
                    number(clients.get("active")));

            snap.blocking = bool(root.get("blocking"), true);
            snap.scheduleEnabled = bool(root.get("schedule_enabled"), false);
            snap.scheduleLabel = text(root.get("schedule_label"));

            Map<String, Object> categories = object(root.get("categories"));
            for (Map.Entry<String, Object> entry : categories.entrySet()) {
                snap.categories.put(entry.getKey(), number(entry.getValue()));
            }
            return snap;
        }
    }

    /** One history bucket from /api/stats/history. */
    static final class Point {
        final long time;
        final long queries;
        final long blocked;

        Point(long time, long queries, long blocked) {
            this.time = time;
            this.queries = queries;
            this.blocked = blocked;
        }
    }

    /** One ranked row (top domain, client, type or upstream). */
    static final class Top {
        final String key;
        final long count;

        Top(String key, long count) {
            this.key = key;
            this.count = count;
        }
    }

    /** One row of the remote query log. */
    static final class Query {
        long id;
        long at;
        String client = "";
        String clientName = "";
        String domain = "";
        String type = "";
        String status = "";
        String upstream = "";
        String rule = "";
        String category = "";
        boolean blocked;
        double ms;
    }

    /** One remote blocklist source with its health. */
    static final class Source {
        int id;
        String address = "";
        boolean enabled = true;
        long count;
        boolean ok = true;
        String error = "";
    }

    /** One allow or block rule on the remote system. */
    static final class Rule {
        String domain = "";
        boolean allow;
        String kind = "exact";

        Rule(String domain, boolean allow, String kind) {
            this.domain = domain;
            this.allow = allow;
            this.kind = kind;
        }
    }

    /** Host summary from /api/info/host. */
    static final class Host {
        String hostname = "";
        String ip = "";
        String version = "";
        int webPort;
        int dnsPort;
        boolean dnsRunning;
        String dnsNote = "";
    }

    /** Everything the remote web dashboard shows, in one snapshot. */
    static final class Dashboard {
        final List<Point> history = new ArrayList<>();
        final List<Top> topBlocked = new ArrayList<>();
        final List<Top> topClients = new ArrayList<>();
        final List<Top> types = new ArrayList<>();
        final List<Top> upstreams = new ArrayList<>();
        final List<Query> queries = new ArrayList<>();
        final List<Source> sources = new ArrayList<>();
        final List<Rule> allowed = new ArrayList<>();
        final List<Rule> blocked = new ArrayList<>();
        final Map<String, Boolean> categories = new LinkedHashMap<>();
        final Map<String, String> levels = new LinkedHashMap<>();
        Host host = new Host();

        boolean isEmpty() {
            return history.isEmpty() && queries.isEmpty() && sources.isEmpty()
                    && categories.isEmpty() && host.hostname.isEmpty();
        }
    }

    private final String server;
    private final int port;
    private final String username;
    private final String password;
    private final HttpClient http;

    private volatile String base = "";
    private volatile String sid = "";
    private volatile boolean connected;
    private volatile String lastError = "";
    private volatile String version = "";
    private volatile String name = "";

    CompanionClient(String server, int port, String username, String password) {
        this.server = server == null ? "" : server.trim();
        this.port = port;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    String display() {
        String value = base;
        if (value.isEmpty()) {
            try {
                value = base(server, port);
            } catch (Exception e) {
                value = server;
            }
        }
        return value.replaceFirst("^https?://", "");
    }

    String version() { return version; }
    String name() { return name; }
    boolean connected() { return connected; }
    String lastError() { return lastError; }

    String dashboardUrl() {
        try {
            String root = base.isEmpty() ? base(server, port) : base;
            return root + "/admin/";
        } catch (Exception e) {
            return "";
        }
    }

    /** Resolves the configured address and signs in when the server asks for it. */
    synchronized void connect() throws IOException {
        String root = base(server, port);
        base = root;
        sid = "";
        connected = false;
        lastError = "";
        try {
            HttpResponse<String> probe = request("GET", "/api/auth", null);
            Map<String, Object> auth = Json.parseObject(probe.body());
            Map<String, Object> session = object(auth.get("session"));
            boolean valid = bool(session.get("valid"), false);
            boolean passwordRequired = bool(session.get("password_required"), false);

            if (!valid && (passwordRequired || probe.statusCode() == 401)) {
                String body = new Json.Obj()
                        .put("username", username)
                        .put("password", password)
                        .toString();
                HttpResponse<String> login = request("POST", "/api/auth", body);
                if (login.statusCode() == 429) {
                    throw new AuthException(message(login.body(),
                            "Too many login attempts. Wait a minute and try again."));
                }
                if (login.statusCode() != 200) {
                    throw new AuthException(message(login.body(),
                            "Username or password incorrect."));
                }
                Map<String, Object> loginRoot = Json.parseObject(login.body());
                Map<String, Object> loginSession = object(loginRoot.get("session"));
                String value = text(loginSession.get("sid"));
                if (value.isEmpty()) {
                    throw new AuthException("The server did not return a session.");
                }
                sid = value;
            } else if (!valid) {
                throw new AuthException("The server requires a login.");
            }

            readVersion();
            connected = true;
            lastError = "";
        } catch (AuthException e) {
            lastError = e.getMessage();
            throw e;
        } catch (IOException e) {
            lastError = e.getMessage();
            throw e;
        }
    }

    /** Reads the summary and re-authenticates once if the session expired. */
    Snapshot snapshot() throws IOException {
        Map<String, Object> root = getObject("/api/stats/summary");
        Snapshot snap = Snapshot.from(root);
        snap.version = version;
        snap.name = name;
        return snap;
    }

    boolean setBlocking(boolean blocking, int timerSeconds) throws IOException {
        String body = new Json.Obj()
                .put("blocking", blocking)
                .put("timer", Math.max(0, timerSeconds))
                .toString();
        Map<String, Object> root = sendObject("POST", "/api/dns/blocking", body);
        return bool(root.get("blocking"), blocking);
    }

    void flushCache() throws IOException {
        sendObject("POST", "/api/action/flush", "{}");
    }

    void updateGravity() throws IOException {
        sendObject("POST", "/api/action/gravity", "{}");
    }

    // ------------------------------------------------------- dashboard mirror

    private interface Fetch<T> {
        T load() throws IOException;
    }

    /** Runs one dashboard section, keeping the rest alive if it fails. */
    private <T> T section(Fetch<T> fetch, T fallback, IOException[] first)
            throws IOException {
        try {
            return fetch.load();
        } catch (AuthException e) {
            throw e;
        } catch (IOException e) {
            if (first[0] == null) first[0] = e;
            return fallback;
        }
    }

    /** Reads everything the remote web dashboard shows, section by section. */
    Dashboard dashboard() throws IOException {
        Dashboard dash = new Dashboard();
        IOException[] first = new IOException[1];
        dash.history.addAll(section(this::history, new ArrayList<Point>(), first));
        dash.topBlocked.addAll(section(() -> topDomains(true, 8),
                new ArrayList<Top>(), first));
        dash.topClients.addAll(section(() -> topClients(6),
                new ArrayList<Top>(), first));
        dash.types.addAll(section(this::queryTypes, new ArrayList<Top>(), first));
        dash.upstreams.addAll(section(this::upstreams, new ArrayList<Top>(), first));
        dash.queries.addAll(section(() -> queries(400), new ArrayList<Query>(), first));
        dash.sources.addAll(section(this::sources, new ArrayList<Source>(), first));
        dash.allowed.addAll(section(() -> rules(true), new ArrayList<Rule>(), first));
        dash.blocked.addAll(section(() -> rules(false), new ArrayList<Rule>(), first));
        try {
            readCategories(dash);
        } catch (AuthException e) {
            throw e;
        } catch (IOException e) {
            if (first[0] == null) first[0] = e;
        }
        dash.host = section(this::host, new Host(), first);
        if (first[0] != null && dash.isEmpty()) throw first[0];
        return dash;
    }

    List<Point> history() throws IOException {
        Map<String, Object> root = getObject("/api/stats/history?from=-86400");
        List<Point> out = new ArrayList<>();
        for (Object item : array(root.get("points"))) {
            Map<String, Object> p = object(item);
            out.add(new Point(number(p.get("time")) * 1000L,
                    number(p.get("queries")), number(p.get("blocked"))));
        }
        return out;
    }

    List<Top> topDomains(boolean blockedOnly, int count) throws IOException {
        Map<String, Object> root = getObject("/api/stats/top_domains?blocked="
                + blockedOnly + "&count=" + Math.max(1, count));
        List<Top> out = new ArrayList<>();
        for (Object item : array(root.get("domains"))) {
            Map<String, Object> d = object(item);
            out.add(new Top(text(d.get("domain")), number(d.get("count"))));
        }
        return out;
    }

    List<Top> topClients(int count) throws IOException {
        Map<String, Object> root = getObject("/api/stats/top_clients?count="
                + Math.max(1, count));
        List<Top> out = new ArrayList<>();
        for (Object item : array(root.get("clients"))) {
            Map<String, Object> c = object(item);
            String name = text(c.get("name"));
            String ip = text(c.get("ip"));
            out.add(new Top(name.isEmpty() || name.equals(ip)
                    ? ip : name + " · " + ip, number(c.get("count"))));
        }
        return out;
    }

    List<Top> queryTypes() throws IOException {
        Map<String, Object> root = getObject("/api/stats/query_types");
        List<Top> out = new ArrayList<>();
        for (Object item : array(root.get("types"))) {
            Map<String, Object> t = object(item);
            out.add(new Top(text(t.get("type")), number(t.get("count"))));
        }
        return out;
    }

    List<Top> upstreams() throws IOException {
        Map<String, Object> root = getObject("/api/stats/upstreams");
        List<Top> out = new ArrayList<>();
        for (Object item : array(root.get("upstreams"))) {
            Map<String, Object> u = object(item);
            out.add(new Top(text(u.get("upstream")), number(u.get("count"))));
        }
        return out;
    }

    List<Query> queries(int limit) throws IOException {
        Map<String, Object> root = getObject("/api/queries?length="
                + Math.max(1, limit));
        List<Query> out = new ArrayList<>();
        for (Object item : array(root.get("queries"))) {
            Map<String, Object> q = object(item);
            Query row = new Query();
            row.id = number(q.get("id"));
            row.at = Math.round(decimal(q.get("time")) * 1000.0);
            row.domain = text(q.get("domain"));
            row.type = text(q.get("type"));
            row.status = text(q.get("status"));
            row.blocked = bool(q.get("blocked"), false);
            row.upstream = text(q.get("upstream"));
            row.rule = text(q.get("rule"));
            row.category = text(q.get("category"));
            Map<String, Object> client = object(q.get("client"));
            row.client = text(client.get("ip"));
            row.clientName = text(client.get("name"));
            Map<String, Object> reply = object(q.get("reply"));
            row.ms = decimal(reply.get("time")) * 1000.0;
            out.add(row);
        }
        return out;
    }

    List<Source> sources() throws IOException {
        Map<String, Object> root = getObject("/api/lists");
        List<Source> out = new ArrayList<>();
        for (Object item : array(root.get("lists"))) {
            Map<String, Object> l = object(item);
            Source source = new Source();
            source.id = (int) number(l.get("id"));
            source.address = text(l.get("address"));
            source.enabled = bool(l.get("enabled"), true);
            out.add(source);
        }
        Map<String, Source> byAddress = new HashMap<>();
        for (Source source : out) byAddress.put(source.address, source);
        try {
            for (Object item : array(getObject("/api/lists/health").get("lists"))) {
                Map<String, Object> h = object(item);
                Source source = byAddress.get(text(h.get("address")));
                if (source == null) continue;
                source.count = number(h.get("count"));
                source.ok = bool(h.get("ok"), true);
                source.error = text(h.get("error"));
            }
        } catch (AuthException e) {
            throw e;
        } catch (IOException ignored) {
            // Counts are a bonus; the list itself is still usable.
        }
        return out;
    }

    List<Rule> rules(boolean allow) throws IOException {
        List<Rule> out = new ArrayList<>();
        for (String kind : new String[]{"exact", "wildcard", "regex"}) {
            Map<String, Object> root = getObject("/api/domains/"
                    + (allow ? "allow" : "deny") + "/" + kind);
            for (Object item : array(root.get("domains"))) {
                Map<String, Object> d = object(item);
                String domain = text(d.get("domain"));
                if (!domain.isEmpty()) out.add(new Rule(domain, allow, kind));
            }
        }
        return out;
    }

    private void readCategories(Dashboard dash) throws IOException {
        Map<String, Object> root = getObject("/api/settings/categories");
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            if (entry.getValue() instanceof Boolean) {
                dash.categories.put(entry.getKey(), (Boolean) entry.getValue());
            }
        }
        for (Map.Entry<String, Object> entry
                : object(root.get("category_levels")).entrySet()) {
            dash.levels.put(entry.getKey(), text(entry.getValue()));
        }
    }

    Host host() throws IOException {
        Map<String, Object> h = object(getObject("/api/info/host").get("host"));
        Host host = new Host();
        host.hostname = text(h.get("hostname"));
        host.ip = text(h.get("ip"));
        host.version = text(h.get("version"));
        host.webPort = (int) number(h.get("web_port"));
        host.dnsPort = (int) number(h.get("lan_dns_port"));
        host.dnsRunning = bool(h.get("lan_dns_running"), false);
        host.dnsNote = text(h.get("lan_dns_note"));
        return host;
    }

    // ------------------------------------------------------ remote mutations

    void addRule(boolean allow, String kind, String domain) throws IOException {
        sendObject("POST", domainPath(allow, kind),
                new Json.Obj().put("domain", domain).toString());
    }

    void removeRule(boolean allow, String kind, String domain) throws IOException {
        sendObject("DELETE", domainPath(allow, kind) + "?domain="
                + java.net.URLEncoder.encode(domain, StandardCharsets.UTF_8), null);
    }

    private static String domainPath(boolean allow, String kind) {
        String value = kind == null ? "exact" : kind.toLowerCase(Locale.US);
        if (!"wildcard".equals(value) && !"regex".equals(value)) value = "exact";
        return "/api/domains/" + (allow ? "allow" : "deny") + "/" + value;
    }

    void addList(String address) throws IOException {
        sendObject("POST", "/api/lists",
                new Json.Obj().put("address", address).toString());
    }

    void removeList(int id) throws IOException {
        sendObject("DELETE", "/api/lists/" + id, null);
    }

    void setCategory(String key, boolean enabled) throws IOException {
        sendObject("POST", "/api/settings/categories",
                new Json.Obj().put(key, enabled).toString());
    }

    void setCategoryLevel(String key, String level) throws IOException {
        sendObject("POST", "/api/settings/categories",
                new Json.Obj().put(key, level).toString());
    }

    private void readVersion() {
        try {
            Map<String, Object> root = getObject("/api/info/version");
            version = text(root.get("version"));
            name = text(root.get("name"));
        } catch (Exception ignored) {
            version = "";
            name = "";
        }
    }

    private Map<String, Object> getObject(String path) throws IOException {
        HttpResponse<String> response = request("GET", path, null);
        if (response.statusCode() == 401) {
            connect();
            response = request("GET", path, null);
        }
        if (response.statusCode() != 200) {
            throw new IOException(message(response.body(),
                    "The dashboard returned HTTP " + response.statusCode() + "."));
        }
        return Json.parseObject(response.body());
    }

    private Map<String, Object> sendObject(String method, String path,
                                           String body) throws IOException {
        HttpResponse<String> response = request(method, path, body);
        if (response.statusCode() == 401) {
            connect();
            response = request(method, path, body);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(message(response.body(),
                    "The dashboard returned HTTP " + response.statusCode() + "."));
        }
        return Json.parseObject(response.body());
    }

    private HttpResponse<String> request(String method, String path, String body)
            throws IOException {
        String root = base;
        if (root == null || root.isEmpty()) root = base(server, port);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(root + path))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json");
            if (!sid.isEmpty()) builder.header("X-FTL-SID", sid);
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(
                                body, StandardCharsets.UTF_8));
            }
            return http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException e) {
            throw new IOException("Cannot reach " + display()
                    + ". Is Hole Shield running on that address?", e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException(display() + " did not answer in time.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("The connection was interrupted.", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("That server address is not valid.", e);
        }
    }

    /** Accepts host, host:port, or a full http(s) URL. */
    static String base(String server, int port) throws IOException {
        String text = server == null ? "" : server.trim();
        if (text.isEmpty()) {
            throw new IOException("Enter the address of the Hole Shield system.");
        }
        if (!text.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*")) {
            text = "http://" + text;
        }
        URI uri;
        try {
            uri = URI.create(text);
        } catch (Exception e) {
            throw new IOException("That server address is not valid.");
        }
        String scheme = uri.getScheme();
        String authority = uri.getRawAuthority();
        if (scheme == null || authority == null || authority.isEmpty()) {
            throw new IOException("That server address is not valid.");
        }
        if (uri.getPort() < 0 && port > 0) {
            authority = authority + ":" + port;
        }
        return scheme.toLowerCase(java.util.Locale.US) + "://" + authority;
    }

    private static String message(String body, String fallback) {
        Map<String, Object> root = Json.parseObject(body);
        Map<String, Object> session = object(root.get("session"));
        String message = text(session.get("message"));
        if (message.isEmpty()) message = text(root.get("message"));
        if (message.isEmpty()) message = text(root.get("error"));
        return message.isEmpty() ? fallback : message;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (value instanceof Map) return (Map<String, Object>) value;
        return new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value) {
        if (value instanceof List) return (List<Object>) value;
        return new ArrayList<>();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try { return Long.parseLong(((String) value).trim()); }
            catch (Exception ignored) { }
        }
        return 0;
    }

    private static double decimal(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try { return Double.parseDouble(((String) value).trim()); }
            catch (Exception ignored) { }
        }
        return 0;
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) {
            String text = ((String) value).trim();
            if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
            if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
        }
        if (value instanceof Number) return ((Number) value).longValue() != 0;
        return fallback;
    }
}
