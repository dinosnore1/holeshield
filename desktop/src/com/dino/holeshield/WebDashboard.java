package com.dino.holeshield;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Embedded dashboard and compatible JSON API for the desktop app. */
final class WebDashboard {

    private static final long SESSION_MS = 12L * 60L * 60L * 1000L;

    private final DesktopPrefs prefs;
    private final FilterEngine filter;
    private final StatsStore stats;
    private final DnsService dns;
    private final String version;

    private HttpServer server;
    private ExecutorService pool;
    private volatile int port;
    private volatile String error = "";
    private final ConcurrentHashMap<String, Long> sessions = new ConcurrentHashMap<>();
    private volatile long loginLockedUntil;
    private volatile int loginFailures;
    private String dashboard;

    WebDashboard(DesktopPrefs prefs, FilterEngine filter, StatsStore stats,
                 DnsService dns, String version) {
        this.prefs = prefs;
        this.filter = filter;
        this.stats = stats;
        this.dns = dns;
        this.version = version;
    }

    synchronized boolean start() {
        stop();
        int wanted = prefs.getInt("webPort", 8080);
        String bind = prefs.get("webBind", "0.0.0.0");
        try {
            InetSocketAddress address;
            if (bind == null || bind.isEmpty() || "0.0.0.0".equals(bind)
                    || "*".equals(bind)) {
                address = new InetSocketAddress(wanted);
            } else {
                address = new InetSocketAddress(InetAddress.getByName(bind), wanted);
            }
            server = HttpServer.create(address, 0);
            server.createContext("/", this::handle);
            pool = Executors.newFixedThreadPool(8, r -> {
                Thread t = new Thread(r, "dashboard");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(pool);
            server.start();
            port = server.getAddress().getPort();
            error = "";
            return true;
        } catch (Exception e) {
            error = "Could not start the dashboard on port " + wanted + ": "
                    + (e.getMessage() == null ? e.toString() : e.getMessage());
            server = null;
            return false;
        }
    }

    synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    boolean running() { return server != null; }
    int actualPort() { return port; }
    String error() { return error; }

    // ------------------------------------------------------------------ router

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                sendEmpty(exchange, 204);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) {
                redirect(exchange, "/admin/");
                return;
            }
            if ("/admin".equals(path)) {
                redirect(exchange, "/admin/");
                return;
            }
            if ("/admin/".equals(path) || "/admin/index.html".equals(path)) {
                sendHtml(exchange, 200, dashboard());
                return;
            }
            if ("/favicon.ico".equals(path)) {
                sendEmpty(exchange, 204);
                return;
            }
            if (path.startsWith("/api/")) {
                String rest = path.substring(5);
                if (!"auth".equals(rest) && !"health".equals(rest)
                        && !authorized(exchange)) {
                    sendJson(exchange, 401, new Json.Obj()
                            .put("session", new Json.Obj()
                                    .put("valid", false)
                                    .put("message", "authentication required")));
                    return;
                }
                routeApi(exchange, rest);
                return;
            }
            sendText(exchange, 404, "Not found");
        } catch (Exception e) {
            try {
                sendJson(exchange, 500, new Json.Obj()
                        .put("error", e.getMessage() == null ? "server error" : e.getMessage()));
            } catch (Exception ignored) { }
        } finally {
            exchange.close();
        }
    }

    private void routeApi(HttpExchange exchange, String rest) throws Exception {
        String method = exchange.getRequestMethod();
        if ("auth/logout".equals(rest)) {
            logout(exchange);
            return;
        }
        if ("auth".equals(rest)) {
            auth(exchange);
            return;
        }
        if ("health".equals(rest)) {
            sendJson(exchange, 200, new Json.Obj().put("status", "ok"));
            return;
        }
        if ("stats/history".equals(rest)) {
            sendJson(exchange, 200, history(exchange));
            return;
        }
        if ("settings/categories".equals(rest)) {
            categories(exchange);
            return;
        }
        if ("lists/health".equals(rest)) {
            Json.Arr lists = new Json.Arr();
            for (FilterEngine.Health h : filter.health()) {
                lists.add(new Json.Obj()
                        .put("address", h.url)
                        .put("ok", h.ok)
                        .put("count", h.count)
                        .put("error", h.error)
                        .put("at", h.at));
            }
            sendJson(exchange, 200, new Json.Obj()
                    .put("lists", lists)
                    .put("updating", filter.isUpdating()));
            return;
        }
        if ("queries/export".equals(rest)) {
            exportQueries(exchange);
            return;
        }
        if ("stats/summary".equals(rest)) {
            sendJson(exchange, 200, summary());
            return;
        }
        if ("stats/top_domains".equals(rest)) {
            sendJson(exchange, 200, topDomains(exchange));
            return;
        }
        if ("stats/top_clients".equals(rest)) {
            sendJson(exchange, 200, topClients(exchange));
            return;
        }
        if ("stats/query_types".equals(rest)) {
            sendJson(exchange, 200, queryTypes());
            return;
        }
        if ("stats/upstreams".equals(rest)) {
            sendJson(exchange, 200, upstreams());
            return;
        }
        if ("queries".equals(rest)) {
            sendJson(exchange, 200, queries(exchange));
            return;
        }
        if ("dns/blocking".equals(rest)) {
            sendJson(exchange, 200, blocking(exchange));
            return;
        }
        if (rest.startsWith("domains/")) {
            domains(exchange, rest.split("/"));
            return;
        }
        if ("lists".equals(rest) || rest.startsWith("lists/")) {
            lists(exchange, rest.split("/"));
            return;
        }
        if ("action/flush".equals(rest) || "cache/flush".equals(rest)) {
            dns.flushCache();
            sendJson(exchange, 200, new Json.Obj().put("flushed", true)
                    .put("took", 0.001));
            return;
        }
        if ("action/gravity".equals(rest)) {
            if (filter.isUpdating()) {
                sendJson(exchange, 409, new Json.Obj().put("started", false)
                        .put("message", "An update is already running"));
            } else {
                filter.updateBlocklists(null, null);
                sendJson(exchange, 202, new Json.Obj().put("started", true));
            }
            return;
        }
        if ("info/host".equals(rest)) {
            sendJson(exchange, 200, info());
            return;
        }
        if ("info/version".equals(rest)) {
            sendJson(exchange, 200, new Json.Obj()
                    .put("version", version)
                    .put("name", "Hole Shield Desktop")
                    .put("api", "v6-subset"));
            return;
        }
        sendJson(exchange, 404, new Json.Obj().put("error", "unknown endpoint"));
    }

    // --------------------------------------------------------------------- auth

    private void auth(HttpExchange exchange) throws Exception {
        if ("GET".equals(exchange.getRequestMethod())) {
            boolean required = WebAuth.passwordRequired(prefs.webPassword());
            boolean valid = !required || authorized(exchange);
            sendJson(exchange, 200, new Json.Obj().put("session", new Json.Obj()
                    .put("valid", valid)
                    .put("username_required", WebAuth.usernameRequired(prefs.webUsername()))
                    .put("password_required", required)));
            return;
        }
        long now = System.currentTimeMillis();
        if (now < loginLockedUntil) {
            long wait = Math.max(1L, (loginLockedUntil - now + 999L) / 1000L);
            sendJson(exchange, 429, new Json.Obj().put("session", new Json.Obj()
                    .put("valid", false)
                    .put("message", "Too many attempts. Try again in " + wait + "s.")));
            return;
        }
        Map<String, Object> body = Json.parseObject(readBody(exchange));
        String suppliedUser = string(body.get("username"));
        String suppliedPassword = string(body.get("password"));
        if (suppliedUser.isEmpty()) suppliedUser = param(exchange, "username");
        if (suppliedPassword.isEmpty()) suppliedPassword = param(exchange, "password");
        if (!WebAuth.valid(prefs.webUsername(), prefs.webPassword(),
                suppliedUser, suppliedPassword)) {
            loginFailures++;
            if (loginFailures >= 5) {
                long delay = 30000L * (1L << Math.min(5, loginFailures - 5));
                loginLockedUntil = now + Math.min(delay, 15 * 60 * 1000L);
            }
            sendJson(exchange, 401, new Json.Obj().put("session", new Json.Obj()
                    .put("valid", false)
                    .put("message", "username or password incorrect")));
            return;
        }
        loginFailures = 0;
        loginLockedUntil = 0L;
        long expires = now + SESSION_MS;
        sessions.entrySet().removeIf(e -> e.getValue() < now);
        String sid = UUID.randomUUID().toString().replace("-", "");
        sessions.put(sid, expires);
        exchange.getResponseHeaders().add("Set-Cookie",
                "sid=" + sid + "; Max-Age=" + (SESSION_MS / 1000)
                        + "; HttpOnly; SameSite=Strict; Path=/");
        sendJson(exchange, 200, new Json.Obj().put("session", new Json.Obj()
                .put("valid", true)
                .put("sid", sid)
                .put("csrf", UUID.randomUUID().toString().replace("-", ""))
                .put("validity", SESSION_MS / 1000))
                .put("took", 0.001));
    }

    private void logout(HttpExchange exchange) throws Exception {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, new Json.Obj().put("error", "POST required"));
            return;
        }
        String sid = exchange.getRequestHeaders().getFirst("X-FTL-SID");
        if (sid == null || sid.isEmpty()) sid = cookie(exchange, "sid");
        boolean all = "1".equals(param(exchange, "all"))
                || "true".equalsIgnoreCase(param(exchange, "all"));
        if (all) sessions.clear();
        else if (sid != null) sessions.remove(sid);
        exchange.getResponseHeaders().add("Set-Cookie",
                "sid=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/");
        sendJson(exchange, 200, new Json.Obj().put("loggedOut", true).put("all", all));
    }

    private boolean authorized(HttpExchange exchange) {
        if (!WebAuth.passwordRequired(prefs.webPassword())) return true;
        String sid = exchange.getRequestHeaders().getFirst("X-FTL-SID");
        if (sid == null || sid.isEmpty()) sid = cookie(exchange, "sid");
        if (sid == null || sid.isEmpty()) return false;
        Long expires = sessions.get(sid);
        if (expires == null) return false;
        if (expires < System.currentTimeMillis()) {
            sessions.remove(sid);
            return false;
        }
        return true;
    }

    private static String cookie(HttpExchange exchange, String name) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String header : headers) {
            for (String part : header.split(";")) {
                String value = part.trim();
                int equals = value.indexOf('=');
                if (equals > 0 && value.substring(0, equals).equals(name)) {
                    return value.substring(equals + 1);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ summary

    private Json.Obj summary() {
        Json.Obj root = new Json.Obj();
        root.put("queries", new Json.Obj()
                .put("total", stats.queries())
                .put("blocked", stats.blocked())
                .put("percent_blocked", stats.rate())
                .put("unique_domains", stats.uniqueDomains())
                .put("forwarded", stats.forwarded())
                .put("cached", stats.cached())
                .put("errors", stats.errors()));
        Json.Obj gravity = new Json.Obj()
                .put("domains_being_blocked", filter.blockedCount())
                .put("last_update", filter.lastUpdate() > 0
                        ? filter.lastUpdate() / 1000L : null);
        if (filter.lastUpdate() > 0) gravity.put("last_update_ms", filter.lastUpdate());
        root.put("gravity", gravity);
        root.put("categories", new Json.Obj()
                .put("ads", filter.adsCount())
                .put("trackers", filter.trackerCount())
                .put("malware", filter.malwareCount())
                .put("adult", filter.adultCount())
                .put("piracy", filter.piracyCount())
                .put("gambling", filter.gamblingCount())
                .put("dating", filter.datingCount())
                .put("social", filter.socialCount()));
        root.put("clients", new Json.Obj()
                .put("active", stats.clientCount())
                .put("total", stats.clientCount()));
        root.put("blocking", prefs.blockingActive());
        long left = prefs.pauseRemainingSeconds();
        root.put("blocking_timer", left == 0 ? 0 : left);
        root.put("schedule_enabled", false);
        root.put("schedule_start", "");
        root.put("schedule_end", "");
        root.put("schedule_active", true);
        root.put("schedule_label", "");
        root.put("safe_search", false);
        root.put("blocked_response", prefs.blockedResponseLabel());
        root.put("category_switches", new Json.Obj()
                .put("ads", prefs.getBool("categoryAds", true))
                .put("trackers", prefs.getBool("categoryTrackers", true))
                .put("malware", prefs.getBool("categoryMalware", true))
                .put("adult", prefs.getBool("categoryAdult", false))
                .put("piracy", prefs.getBool("categoryPiracy", false))
                .put("gambling", prefs.getBool("categoryGambling", false))
                .put("dating", prefs.getBool("categoryDating", false))
                .put("social", prefs.getBool("categorySocial", false)));
        root.put("took", 0.001);
        return root;
    }

    private Json.Obj history(HttpExchange exchange) {
        String range = param(exchange, "range").toLowerCase(Locale.US);
        if (range.isEmpty()) range = "24h";
        Json.Arr points = new Json.Arr();
        for (StatsStore.Bucket b : stats.chart(range)) {
            points.add(new Json.Obj()
                    .put("time", b.start / 1000.0)
                    .put("queries", b.queries)
                    .put("blocked", b.blocked));
        }
        Json.Arr categories = new Json.Arr();
        for (StatsStore.Top t : stats.categoryCounts()) {
            categories.add(new Json.Obj().put("category", t.key).put("count", t.count));
        }
        return new Json.Obj()
                .put("range", range)
                .put("points", points)
                .put("hours", points)
                .put("categories", categories)
                .put("took", 0.001);
    }

    private Json.Obj topDomains(HttpExchange exchange) {
        boolean blocked = !"false".equalsIgnoreCase(param(exchange, "blocked"));
        int count = clamp(parseInt(param(exchange, "count"), 10), 1, 100);
        Json.Arr arr = new Json.Arr();
        for (StatsStore.Top t : stats.topDomains(blocked, count)) {
            arr.add(new Json.Obj().put("domain", t.key).put("count", t.count));
        }
        return new Json.Obj()
                .put("domains", arr)
                .put("total_queries", blocked ? stats.blocked()
                        : stats.queries() - stats.blocked())
                .put("took", 0.001);
    }

    private Json.Obj topClients(HttpExchange exchange) {
        int count = clamp(parseInt(param(exchange, "count"), 10), 1, 100);
        Json.Arr arr = new Json.Arr();
        for (StatsStore.Top t : stats.topClients(count)) {
            arr.add(new Json.Obj()
                    .put("ip", t.key)
                    .put("name", DnsService.clientName(t.key))
                    .put("count", t.count));
        }
        return new Json.Obj().put("clients", arr).put("took", 0.001);
    }

    private Json.Obj queryTypes() {
        Json.Arr arr = new Json.Arr();
        for (StatsStore.Top t : stats.queryTypes()) {
            arr.add(new Json.Obj().put("type", t.key).put("count", t.count));
        }
        return new Json.Obj().put("types", arr).put("took", 0.001);
    }

    private Json.Obj upstreams() {
        Json.Arr arr = new Json.Arr();
        for (StatsStore.Top t : stats.topUpstreams(16)) {
            arr.add(new Json.Obj().put("upstream", t.key).put("count", t.count));
        }
        return new Json.Obj().put("upstreams", arr).put("took", 0.001);
    }

    private Json.Obj queries(HttpExchange exchange) {
        int start = clamp(parseInt(param(exchange, "start"), 0), 0, 100000);
        int length = clamp(parseInt(param(exchange, "length"), 100), 1, 500);
        String blocked = param(exchange, "blocked");
        String client = param(exchange, "client").toLowerCase(Locale.US);
        String domain = param(exchange, "domain").toLowerCase(Locale.US);
        String search = param(exchange, "search").toLowerCase(Locale.US);
        Json.Arr arr = new Json.Arr();
        int skipped = 0;
        int id = 0;
        for (StatsStore.Entry e : stats.all()) {
            id++;
            if (!blocked.isEmpty()) {
                boolean want = "true".equalsIgnoreCase(blocked) || "1".equals(blocked);
                if (want != e.blocked) continue;
            }
            if (!client.isEmpty()
                    && !e.client.toLowerCase(Locale.US).contains(client)) continue;
            if (!domain.isEmpty()
                    && !e.domain.toLowerCase(Locale.US).contains(domain)) continue;
            if (!search.isEmpty()
                    && !e.domain.toLowerCase(Locale.US).contains(search)
                    && !e.client.toLowerCase(Locale.US).contains(search)
                    && !DnsService.clientName(e.client).toLowerCase(Locale.US)
                    .contains(search)) continue;
            if (skipped++ < start) continue;
            if (arr.size() >= length) break;
            arr.add(new Json.Obj()
                    .put("id", id)
                    .put("time", e.at / 1000.0)
                    .put("type", e.typeName())
                    .put("domain", e.domain)
                    .put("client", new Json.Obj()
                            .put("ip", e.client)
                            .put("name", DnsService.clientName(e.client)))
                    .put("status", e.status)
                    .put("blocked", e.blocked)
                    .put("reply", new Json.Obj()
                            .put("type", "IP")
                            .put("time", e.ms / 1000.0))
                    .put("upstream", e.upstream)
                    .put("rcode", DnsPacket.rcodeName(e.rcode))
                    .put("rule", e.rule)
                    .put("category", e.category));
        }
        return new Json.Obj()
                .put("queries", arr)
                .put("cursor", start + arr.size())
                .put("took", 0.001);
    }

    private Json.Obj blocking(HttpExchange exchange) throws Exception {
        if ("POST".equals(exchange.getRequestMethod())) {
            Map<String, Object> body = Json.parseObject(readBody(exchange));
            boolean blocking = !body.containsKey("blocking")
                    || bool(body.get("blocking"));
            int timer = (int) number(body.get("timer"), 0);
            if (blocking) prefs.resume();
            else prefs.pause(timer);
            return new Json.Obj()
                    .put("blocking", prefs.blockingActive())
                    .put("timer", blocking || timer <= 0 ? null : timer)
                    .put("took", 0.001);
        }
        long left = prefs.pauseRemainingSeconds();
        return new Json.Obj()
                .put("blocking", prefs.blockingActive())
                .put("timer", left <= 0 ? null : left)
                .put("took", 0.001);
    }

    // ------------------------------------------------------------------ domains

    private void domains(HttpExchange exchange, String[] segments) throws Exception {
        if (segments.length < 3) {
            sendJson(exchange, 400, new Json.Obj().put("error",
                    "type required (allow|deny)"));
            return;
        }
        boolean allow;
        if ("allow".equals(segments[1])) allow = true;
        else if ("deny".equals(segments[1])) allow = false;
        else {
            sendJson(exchange, 400, new Json.Obj().put("error",
                    "type must be allow or deny"));
            return;
        }
        int kind = FilterEngine.EXACT;
        if (!segments[2].isEmpty()) {
            if ("wildcard".equals(segments[2])) kind = FilterEngine.WILDCARD;
            else if ("regex".equals(segments[2])) kind = FilterEngine.REGEX;
            else if (!"exact".equals(segments[2])) {
                sendJson(exchange, 400, new Json.Obj().put("error",
                        "kind must be exact, wildcard or regex"));
                return;
            }
        }
        String method = exchange.getRequestMethod();
        if ("GET".equals(method)) {
            Json.Arr arr = new Json.Arr();
            for (FilterEngine.Rule rule : filter.rules(allow)) {
                if (rule.kind != kind) continue;
                arr.add(new Json.Obj()
                        .put("domain", rule.value)
                        .put("type", allow ? "allow" : "deny")
                        .put("kind", rule.kindName())
                        .put("enabled", true)
                        .put("comment", ""));
            }
            sendJson(exchange, 200, new Json.Obj()
                    .put("domains", arr).put("took", 0.001));
            return;
        }
        if ("POST".equals(method)) {
            Map<String, Object> body = Json.parseObject(readBody(exchange));
            String domain = string(body.get("domain"));
            if (domain.isEmpty()) domain = param(exchange, "domain");
            if (domain.isEmpty()) {
                sendJson(exchange, 400, new Json.Obj().put("error", "domain required"));
                return;
            }
            if (!filter.addRule(allow, kind, domain)) {
                sendJson(exchange, 400, new Json.Obj().put("error",
                        "invalid domain or regex"));
                return;
            }
            sendJson(exchange, 201, new Json.Obj()
                    .put("domain", domain)
                    .put("type", allow ? "allow" : "deny")
                    .put("kind", kindName(kind))
                    .put("enabled", true));
            return;
        }
        if ("DELETE".equals(method)) {
            String domain = param(exchange, "domain");
            if (domain.isEmpty() && segments.length >= 4) {
                domain = decode(segments[3]);
            }
            if (domain.isEmpty()) {
                sendJson(exchange, 400, new Json.Obj().put("error", "domain required"));
                return;
            }
            if (!filter.removeRule(allow, kind, domain)) {
                sendJson(exchange, 404, new Json.Obj().put("error", "rule not found"));
                return;
            }
            sendJson(exchange, 200, new Json.Obj().put("removed", true));
            return;
        }
        sendJson(exchange, 405, new Json.Obj().put("error", "method not allowed"));
    }

    private static String kindName(int kind) {
        return kind == FilterEngine.WILDCARD ? "wildcard"
                : kind == FilterEngine.REGEX ? "regex" : "exact";
    }

    // -------------------------------------------------------------------- lists

    private void lists(HttpExchange exchange, String[] segments) throws Exception {
        String method = exchange.getRequestMethod();
        if ("GET".equals(method)) {
            Json.Arr arr = new Json.Arr();
            List<String> urls = filter.listUrls();
            for (int i = 0; i < urls.size(); i++) {
                arr.add(new Json.Obj()
                        .put("id", i)
                        .put("address", urls.get(i))
                        .put("type", "block")
                        .put("enabled", true)
                        .put("comment", ""));
            }
            sendJson(exchange, 200, new Json.Obj()
                    .put("lists", arr).put("took", 0.001));
            return;
        }
        if ("POST".equals(method)) {
            Map<String, Object> body = Json.parseObject(readBody(exchange));
            String address = string(body.get("address"));
            if (address.isEmpty()) address = param(exchange, "address");
            if (!address.startsWith("http://") && !address.startsWith("https://")) {
                sendJson(exchange, 400, new Json.Obj().put("error",
                        "http(s) URL required"));
                return;
            }
            if (filter.listUrls().contains(address)) {
                sendJson(exchange, 409, new Json.Obj().put("error", "list already exists"));
                return;
            }
            filter.addListUrl(address);
            sendJson(exchange, 201, new Json.Obj()
                    .put("address", address)
                    .put("type", "block")
                    .put("enabled", true));
            return;
        }
        if ("DELETE".equals(method)) {
            List<String> urls = filter.listUrls();
            int id = parseInt(segments.length >= 2 ? segments[1]
                    : param(exchange, "id"), -1);
            if (id < 0 || id >= urls.size()) {
                sendJson(exchange, 404, new Json.Obj().put("error", "list not found"));
                return;
            }
            filter.removeListUrl(id);
            sendJson(exchange, 200, new Json.Obj().put("removed", true));
            return;
        }
        sendJson(exchange, 405, new Json.Obj().put("error", "method not allowed"));
    }

    private void categories(HttpExchange exchange) throws Exception {
        if ("POST".equals(exchange.getRequestMethod())) {
            Map<String, Object> body = Json.parseObject(readBody(exchange));
            boolean needUpdate = false;
            for (String key : Blocklists.CATEGORY_KEYS) {
                if (!body.containsKey(key)) continue;
                Object value = body.get(key);
                String pref = categoryPref(key);
                if (value instanceof String) {
                    String level = String.valueOf(value).toLowerCase(Locale.ROOT);
                    if ("maximum".equals(level)) level = "max";
                    if (!"lite".equals(level) && !"medium".equals(level)
                            && !"max".equals(level)) {
                        continue;
                    }
                    prefs.setCategoryLevel(key, level);
                    if (prefs.getBool(pref, false)) {
                        prefs.set("blocklistUrls", Blocklists.applyLevel(
                                prefs.blocklistUrls(), key, level));
                        needUpdate = true;
                    }
                } else {
                    boolean on = bool(value);
                    prefs.setBool(pref, on);
                    if (on) {
                        String next = Blocklists.applyLevel(prefs.blocklistUrls(),
                                key, prefs.categoryLevel(key));
                        if (!next.equals(prefs.blocklistUrls())) {
                            prefs.set("blocklistUrls", next);
                            needUpdate = true;
                        }
                    }
                }
            }
            if (body.containsKey("other")) {
                prefs.setBool("categoryOther", bool(body.get("other")));
            }
            prefs.save();
            filter.reloadRules();
            if (needUpdate && !filter.isUpdating()) {
                filter.updateBlocklists(null, null);
            }
        }
        Json.Obj levels = new Json.Obj();
        for (String key : Blocklists.CATEGORY_KEYS) {
            levels.put(key, prefs.categoryLevel(key));
        }
        sendJson(exchange, 200, new Json.Obj()
                .put("ads", prefs.getBool("categoryAds", true))
                .put("trackers", prefs.getBool("categoryTrackers", true))
                .put("malware", prefs.getBool("categoryMalware", true))
                .put("adult", prefs.getBool("categoryAdult", false))
                .put("piracy", prefs.getBool("categoryPiracy", false))
                .put("gambling", prefs.getBool("categoryGambling", false))
                .put("dating", prefs.getBool("categoryDating", false))
                .put("social", prefs.getBool("categorySocial", false))
                .put("other", prefs.getBool("categoryOther", true))
                .put("category_levels", levels)
                .put("safe_search", false)
                .put("took", 0.001));
    }

    private static String categoryPref(String key) {
        return "category" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    // --------------------------------------------------------------------- info

    private Json.Obj info() {
        String dnsError = dns.bindError();
        if (dnsError.isEmpty()) dnsError = dns.tcpError();
        return new Json.Obj().put("host", new Json.Obj()
                .put("hostname", AppDirs.hostName())
                .put("ip", AppDirs.lanAddress())
                .put("web_port", port)
                .put("lan_dns_port", dns.actualPort())
                .put("lan_dns_running", dns.running())
                .put("lan_dns_error", dnsError)
                .put("lan_dns_note", "Bind port 53 with administrator rights to filter this computer.")
                .put("version", version)
                .put("blocking", prefs.blockingActive())
                .put("web_password", prefs.hasWebPassword())
                .put("web_username", WebAuth.usernameRequired(prefs.webUsername()))
                .put("blocked_response", prefs.blockedResponseLabel())
                .put("upstream", prefs.upstreamLabel())
                .put("safe_search", false)
                .put("schedule_enabled", false)
                .put("schedule_label", ""))
                .put("took", 0.001);
    }

    // ------------------------------------------------------------------- export

    private void exportQueries(HttpExchange exchange) throws Exception {
        String format = param(exchange, "format").toLowerCase(Locale.US);
        String blocked = param(exchange, "blocked");
        String search = param(exchange, "search");
        boolean filterBlocked = !blocked.isEmpty();
        boolean wantBlocked = "true".equalsIgnoreCase(blocked) || "1".equals(blocked);
        if ("json".equals(format)) {
            Json.Arr arr = stats.exportJson(wantBlocked, filterBlocked, search);
            sendDownload(exchange, "application/json; charset=utf-8",
                    "holeshield-queries.json", arr.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }
        String csv = stats.exportCsv(wantBlocked, filterBlocked, search);
        sendDownload(exchange, "text/csv; charset=utf-8", "holeshield-queries.csv",
                csv.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------- output

    private String dashboard() {
        if (dashboard != null) return dashboard;
        try (InputStream in = WebDashboard.class.getResourceAsStream("/dashboard.html")) {
            if (in != null) {
                dashboard = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return dashboard;
            }
        } catch (Exception ignored) { }
        dashboard = "<!doctype html><html><body><h1>Hole Shield</h1>"
                + "<p>Dashboard resource is missing from this build.</p></body></html>";
        return dashboard;
    }

    private static void sendJson(HttpExchange exchange, int code, Object value)
            throws IOException {
        byte[] body = value.toString().getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sendHtml(HttpExchange exchange, int code, String html)
            throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sendText(HttpExchange exchange, int code, String text)
            throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sendEmpty(HttpExchange exchange, int code) throws IOException {
        exchange.sendResponseHeaders(code, -1);
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private static void sendDownload(HttpExchange exchange, String type, String name,
                                     byte[] body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("Content-Disposition", "attachment; filename=\"" + name + "\"");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // -------------------------------------------------------------------- utils

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String param(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) return "";
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) continue;
            try {
                if (URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8)
                        .equals(key)) {
                    return URLDecoder.decode(pair.substring(equals + 1),
                            StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) { }
        }
        return "";
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean bool(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        return "true".equalsIgnoreCase(String.valueOf(value))
                || "1".equals(String.valueOf(value));
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Exception e) { return fallback; }
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); }
        catch (Exception e) { return fallback; }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
