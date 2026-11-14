package com.dino.holeshield;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
