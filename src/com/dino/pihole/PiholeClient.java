package com.dino.pihole;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A very small Pi-hole API client that speaks both generations:
 * v6 (session id in X-FTL-SID) and v5 (admin/api.php + API token).
 */
final class PiholeClient {

    private static final Object LOCK = new Object();
    private static String sid;
    private static String sidBase;
    private static String csrf;
    private static long sidAt;

    private PiholeClient() { }

    static final class Summary {
        boolean ok;
        boolean blocking = true;
        long queries;
        long blocked;
        double percent;
        long gravity;
        int version;
        String error = "";
        boolean retryV5;

        static Summary error(String message) {
            Summary s = new Summary();
            s.error = message;
            return s;
        }
    }

    private static final class Resp {
        final int code;
        final String body;
        Resp(int code, String body) { this.code = code; this.body = body; }
    }

    static Summary fetch(Prefs p) {
        synchronized (LOCK) {
            if (!p.usePihole) return Summary.error("Pi-hole upstream is off");
            String base = p.piholeBase();
            if (base.isEmpty()) return Summary.error("No Pi-hole address set");

            if (p.apiVersion != 5) {
                Summary v6 = fetchV6(p, base);
                if (v6 != null && v6.ok) return v6;
                if (v6 != null && !v6.retryV5) return v6;
                Summary v5 = fetchV5(p, base);
                if (v5.ok) return v5;
                return v6 != null ? v6 : v5;
            }
            return fetchV5(p, base);
        }
    }

    /** Returns null when the server clearly has no v6 API. */
    private static Summary fetchV6(Prefs p, String base) {
        try {
            Resp r = get(base + "/api/stats/summary", validSid(base) ? sid : null);
            if (r.code == 401) {
                if (p.piholePassword == null || p.piholePassword.isEmpty()) {
                    Summary s = Summary.error("Pi-hole wants a password");
                    s.retryV5 = true;
                    return s;
                }
                if (!login(base, p.piholePassword)) {
                    Summary s = Summary.error("Pi-hole login failed");
                    s.retryV5 = true;
                    return s;
                }
                r = get(base + "/api/stats/summary", sid);
            }
            if (r.code == 404) return null;
            if (r.code != 200) {
                Summary s = Summary.error("Pi-hole API returned HTTP " + r.code);
                s.retryV5 = true;
                return s;
            }

            JSONObject o = new JSONObject(r.body);
            Summary s = new Summary();
            s.ok = true;
            s.version = 6;
            p.apiVersion = 6;
            JSONObject q = o.optJSONObject("queries");
            if (q != null) {
                s.queries = q.optLong("total", 0);
                s.blocked = q.optLong("blocked", 0);
                s.percent = q.optDouble("percent_blocked", 0);
            }
            JSONObject g = o.optJSONObject("gravity");
            if (g != null) s.gravity = g.optLong("domains_being_blocked", 0);

            try {
                Resp b = get(base + "/api/dns/blocking", validSid(base) ? sid : null);
                if (b.code == 200) {
                    s.blocking = new JSONObject(b.body).optBoolean("blocking", true);
                }
            } catch (Exception ignored) { }
            return s;
        } catch (Exception e) {
            Summary s = Summary.error("Cannot reach " + base);
            s.retryV5 = true;
            return s;
        }
    }

    private static Summary fetchV5(Prefs p, String base) {
        try {
            String url = base + "/admin/api.php?summaryRaw";
            if (p.piholePassword != null && !p.piholePassword.isEmpty()) {
                url += "&auth=" + URLEncoder.encode(p.piholePassword, "UTF-8");
            }
            Resp r = get(url, null);
            if (r.code == 404) {
                return Summary.error("No Pi-hole API found at " + base);
            }
            if (r.code != 200) {
                return Summary.error("Pi-hole returned HTTP " + r.code);
            }
            String body = r.body == null ? "" : r.body.trim();
            if (body.toLowerCase(Locale.US).contains("unauthorized")) {
                return Summary.error("Pi-hole token rejected");
            }
            if (!body.startsWith("{")) {
                return Summary.error("Unexpected answer from Pi-hole");
            }
            JSONObject o = new JSONObject(body);
            Summary s = new Summary();
            s.ok = true;
            s.version = 5;
            p.apiVersion = 5;
            s.queries = o.optLong("dns_queries_today", 0);
            s.blocked = o.optLong("ads_blocked_today", 0);
            s.percent = o.optDouble("ads_percentage_today", 0);
            s.gravity = o.optLong("domains_being_blocked", 0);
            s.blocking = !"disabled".equalsIgnoreCase(o.optString("status", "enabled"));
            return s;
        } catch (Exception e) {
            return Summary.error("Cannot reach " + base);
        }
    }

    /** @return true when the requested state was accepted. */
    static boolean setBlocking(Prefs p, boolean on, int seconds) {
        synchronized (LOCK) {
            if (!p.usePihole) return false;
            String base = p.piholeBase();
            if (base.isEmpty()) return false;

            if (p.apiVersion == 0) {
                Summary s = fetch(p);
                if (!s.ok) return false;
            }
            if (p.apiVersion == 5) {
                return setBlockingV5(p, base, on, seconds);
            }
            return setBlockingV6(p, base, on, seconds);
        }
    }

    private static boolean setBlockingV6(Prefs p, String base, boolean on, int seconds) {
        try {
            if (!validSid(base) || System.currentTimeMillis() - sidAt > 240000L) {
                if (p.piholePassword != null && !p.piholePassword.isEmpty()
                        && !login(base, p.piholePassword)) {
                    return false;
                }
            }
            String body = on
                    ? "{\"blocking\":true}"
                    : "{\"blocking\":false,\"timer\":" + Math.max(0, seconds) + "}";
            Resp r = post(base + "/api/dns/blocking", body, sid);
            if (r.code == 401 && p.piholePassword != null && !p.piholePassword.isEmpty()) {
                if (!login(base, p.piholePassword)) return false;
                r = post(base + "/api/dns/blocking", body, sid);
            }
            return r.code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean setBlockingV5(Prefs p, String base, boolean on, int seconds) {
        try {
            String url = base + "/admin/api.php?";
            url += on ? "enable" : "disable=" + Math.max(0, seconds);
            if (p.piholePassword != null && !p.piholePassword.isEmpty()) {
                url += "&auth=" + URLEncoder.encode(p.piholePassword, "UTF-8");
            }
            Resp r = get(url, null);
            if (r.code != 200) return false;
            JSONObject o = new JSONObject(r.body);
            String status = o.optString("status", "");
            return on ? "enabled".equalsIgnoreCase(status)
                    : "disabled".equalsIgnoreCase(status);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean validSid(String base) {
        return sid != null && !sid.isEmpty() && base.equals(sidBase);
    }

    private static boolean login(String base, String password) {
        try {
            String body = "{\"password\":" + JSONObject.quote(password) + "}";
            Resp r = post(base + "/api/auth", body, null);
            if (r.code != 200) return false;
            JSONObject o = new JSONObject(r.body);
            JSONObject session = o.optJSONObject("session");
            if (session == null) return false;
            String value = session.optString("sid", "");
            if (value.isEmpty()) return false;
            sid = value;
            csrf = session.optString("csrf", null);
            sidBase = base;
            sidAt = System.currentTimeMillis();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Resp get(String url, String session) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3500);
        c.setReadTimeout(5000);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "Pi-hole-TV/1.0");
        if (session != null) c.setRequestProperty("X-FTL-SID", session);
        return read(c);
    }

    private static Resp post(String url, String body, String session) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3500);
        c.setReadTimeout(6000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("User-Agent", "Pi-hole-TV/1.0");
        if (session != null) c.setRequestProperty("X-FTL-SID", session);
        if (session != null && csrf != null) c.setRequestProperty("X-FTL-CSRF", csrf);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) {
            out.write(bytes);
        }
        return read(c);
    }

    private static Resp read(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = "";
        if (in != null) {
            try (InputStream src = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = src.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 1024 * 1024) break;
                }
                body = out.toString("UTF-8");
            }
        }
        return new Resp(code, body);
    }
}
