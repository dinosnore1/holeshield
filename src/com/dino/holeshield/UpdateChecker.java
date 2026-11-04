package com.dino.holeshield;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Reads the public release manifest used by the in-app update check. The app
 * never installs an update by itself; it only reports a newer version and opens
 * the release page.
 */
final class UpdateChecker {

    static final String MANIFEST_URL =
            "https://dinosnore1.github.io/holeshield-privacy/latest.json";

    static final class Release {
        final String channel;
        final String version;
        final int versionCode;
        final String url;
        final String notes;

        Release(String channel, String version, int versionCode, String url, String notes) {
            this.channel = channel == null ? "stable" : channel;
            this.version = version == null ? "" : version;
            this.versionCode = versionCode;
            this.url = url == null ? "" : url;
            this.notes = notes == null ? "" : notes;
        }
    }

    private UpdateChecker() { }

    static Release check(String channel) throws Exception {
        String wanted = channel == null || channel.isEmpty() ? "stable" : channel;
        HttpURLConnection c = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "Hole-Shield/" + "2.3.1");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("Update server returned HTTP " + code);
            byte[] body;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 128 * 1024) throw new Exception("Update manifest is too large");
                }
                body = out.toByteArray();
            }
            JSONObject root = new JSONObject(new String(body, StandardCharsets.UTF_8));
            JSONObject item = root.optJSONObject(wanted);
            if (item == null) item = root.optJSONObject("stable");
            if (item == null) throw new Exception("No release information is available");
            return new Release(wanted,
                    item.optString("version", ""),
                    item.optInt("versionCode", 0),
                    item.optString("url", ""),
                    item.optString("notes", ""));
        } finally {
            c.disconnect();
        }
    }

    static boolean isNewer(Release release, int installedVersionCode) {
        return release != null && release.versionCode > installedVersionCode;
    }
}
