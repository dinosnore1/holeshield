package com.dino.holeshield;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app updater for the desktop app. It reads the release manifest, downloads
 * the installer for this platform and checks its SHA-256 before anything is
 * installed. The installer then runs from a small helper that waits for this
 * process to exit, installs the update and starts the app again.
 */
final class Updater {

    enum Phase {
        IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY,
        INSTALLING, FAILED, UNSUPPORTED
    }

    static final String MANIFEST_URL = "https://dinosnore1.github.io/holeshield/latest.json";

    static final class Release {
        final String version;
        final String notes;
        final String key;
        final String name;
        final String url;
        final String sha256;
        final long size;

        Release(String version, String notes, String key, String name, String url,
                String sha256, long size) {
            this.version = version;
            this.notes = notes;
            this.key = key;
            this.name = name;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    private final File dir;
    private final ExecutorService work = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "hs-updater");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Runnable listener;
    private volatile Phase phase = Phase.IDLE;
    private volatile String message = "Compares this version with the latest release.";
    private volatile Release release;
    private volatile long done;
    private volatile long total;
    private volatile long checkedAt;
    private volatile File installer;

    Updater(File dataDir) {
        this.dir = new File(dataDir, "updates");
    }

    void setListener(Runnable value) {
        listener = value;
    }

    Phase phase() { return phase; }
    String message() { return message; }
    String version() { return release == null ? "" : release.version; }
    String notes() { return release == null ? "" : release.notes; }
    String assetName() { return release == null ? "" : release.name; }
    long size() { return release == null ? 0L : release.size; }
    boolean hasRelease() { return release != null; }
    long done() { return done; }
    long total() { return total; }

    /** When the last manifest check settled, or 0 before the first one. */
    long checkedAt() { return checkedAt; }

    /** Reads the manifest and reports whether a newer installer is published for this platform. */
    void check() {
        if (busy()) return;
        set(Phase.CHECKING, "Checking the release manifest…");
        work.execute(() -> {
            try {
                String key = assetKey(System.getProperty("os.name"),
                        new File("/usr/bin/dpkg").isFile(), new File("/usr/bin/rpm").isFile());
                Release found = parse(fetchText(MANIFEST_URL), key);
                release = found;
                if (compareVersions(found.version, DesktopApp.VERSION) <= 0) {
                    finish(Phase.CURRENT, "Hole Shield " + DesktopApp.VERSION
                            + " is the latest release.");
                } else if (key.isEmpty()) {
                    finish(Phase.UNSUPPORTED, "No supported installer for this system. "
                            + "Its package manager is not recognised.");
                } else if (found.url.isEmpty() || found.sha256.isEmpty()) {
                    finish(Phase.UNSUPPORTED, "Version " + found.version
                            + " has no published installer for this system yet.");
                } else {
                    finish(Phase.AVAILABLE, "Version " + found.version
                            + " can be installed here.");
                }
            } catch (Exception e) {
                finish(Phase.FAILED, failure(e, "Could not check for updates"));
            }
        });
    }

    /** Downloads the installer found by {@link #check} and verifies it. */
    void prepare() {
        final Release wanted = release;
        if (busy() || wanted == null || wanted.url.isEmpty()) return;
        work.execute(() -> {
            try {
                download(wanted);
            } catch (Exception e) {
                installer = null;
                set(Phase.FAILED, failure(e, "The update could not be downloaded"));
            }
        });
    }

    /**
     * Starts the platform helper. The caller quits the app afterwards so the
     * helper can install the update while nothing holds the installed files.
     */
    boolean startInstaller() {
        File file = installer;
        if (phase != Phase.READY || file == null || !file.isFile()) return false;
        try {
            long pid = ProcessHandle.current().pid();
            String command = ProcessHandle.current().info().command().orElse("");
            File log = new File(dir, "update.log");
            ProcessBuilder builder;
            if (AppDirs.isMac()) {
                File script = write("install-update.sh",
                        macScript(pid, file, bundleOf(command), log));
                builder = new ProcessBuilder("/bin/sh", script.getAbsolutePath());
            } else if (AppDirs.isWindows()) {
                File script = write("install-update.ps1",
                        windowsScript(pid, file, command, log));
                builder = new ProcessBuilder("powershell.exe", "-NoProfile",
                        "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                        "-File", script.getAbsolutePath());
            } else {
                String kind = file.getName().endsWith(".rpm") ? "rpm" : "deb";
                File script = write("install-update.sh",
                        linuxScript(pid, file, kind, SystemIntegration.launchCommand(), log));
                builder = new ProcessBuilder("/bin/sh", script.getAbsolutePath());
            }
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.start();
            set(Phase.INSTALLING, "Installing version " + version() + "…");
            return true;
        } catch (Exception e) {
            set(Phase.FAILED, failure(e, "The installer could not be started"));
            return false;
        }
    }

    // ------------------------------------------------------------ manifest

    /** Picks the installer key for this platform, or an empty string when none applies. */
    static String assetKey(String osName, boolean hasDpkg, boolean hasRpm) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac")) return "macos-pkg";
        if (os.contains("win")) return "windows-msi";
        if (os.contains("linux")) return hasDpkg ? "linux-deb" : hasRpm ? "linux-rpm" : "";
        return "";
    }

    static Release parse(String manifest, String key) {
        Map<String, Object> root = Json.parseObject(manifest);
        if (root == null || root.isEmpty()) {
            throw new IllegalStateException("The update manifest could not be read");
        }
        Map<String, Object> desktop = map(root.get("desktop"));
        String version = text(desktop.get("version"));
        if (version.isEmpty()) {
            throw new IllegalStateException("The update manifest has no desktop release");
        }
        Map<String, Object> asset = map(map(desktop.get("assets")).get(key));
        return new Release(version, text(desktop.get("notes")), key,
                safeName(text(asset.get("name"))), text(asset.get("url")),
                text(asset.get("sha256")), number(asset.get("size")));
    }

    /** Compares dotted numeric versions such as 2.3.5 and 2.10.0. */
    static int compareVersions(String a, String b) {
        String[] left = (a == null ? "" : a).split("\\.");
        String[] right = (b == null ? "" : b).split("\\.");
        int count = Math.max(left.length, right.length);
        for (int i = 0; i < count; i++) {
            long x = i < left.length ? numberAt(left[i]) : 0;
            long y = i < right.length ? numberAt(right[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    /**
     * Keeps a manifest file name to a plain basename so it can never escape the
     * download folder or break the quoting of an install script.
     */
    static String safeName(String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        StringBuilder out = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' || c == '+';
            out.append(plain ? c : '-');
        }
        String clean = out.toString();
        return clean.equals(".") || clean.equals("..") ? "" : clean;
    }

    static boolean matches(String expected, String actual) {
        return expected != null && !expected.isEmpty()
                && actual != null && expected.trim().equalsIgnoreCase(actual.trim());
    }

    static String sha256Hex(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    // ------------------------------------------------------------ helper scripts

    static String macScript(long pid, File pkg, String bundle, File log) {
        return "#!/bin/sh\n"
                + "PKG=" + shQuote(pkg.getAbsolutePath()) + "\n"
                + "APP=" + shQuote(bundle) + "\n"
                + "LOG=" + shQuote(log.getAbsolutePath()) + "\n"
                + "while kill -0 " + pid + " 2>/dev/null; do sleep 0.5; done\n"
                + "echo \"installing $PKG\" >> \"$LOG\"\n"
                + "/usr/bin/osascript -e \"do shell script \\\"/usr/sbin/installer -pkg '$PKG' -target /\\\""
                + " with administrator privileges\" >> \"$LOG\" 2>&1\n"
                + "echo \"installer exit $?\" >> \"$LOG\"\n"
                + "/usr/bin/open \"$APP\"\n"
                + "rm -f \"$0\"\n";
    }

    static String windowsScript(long pid, File msi, String exe, File log) {
        return "$appPid = " + pid + "\n"
                + "$msi = " + psQuote(msi.getAbsolutePath()) + "\n"
                + "$exe = " + psQuote(exe) + "\n"
                + "$log = " + psQuote(log.getAbsolutePath()) + "\n"
                + "while (Get-Process -Id $appPid -ErrorAction SilentlyContinue) {"
                + " Start-Sleep -Milliseconds 500 }\n"
                + "Add-Content -Path $log -Value ('installing ' + $msi)\n"
                + "$p = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/i', ('\"' + $msi + '\"'), '/passive')"
                + " -Verb RunAs -PassThru -Wait\n"
                + "Add-Content -Path $log -Value ('msiexec exit ' + $p.ExitCode)\n"
                + "if ($exe -and (Test-Path -LiteralPath $exe)) { Start-Process -FilePath $exe }\n"
                + "Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force\n";
    }

    static String linuxScript(long pid, File pkg, String kind, String relaunch, File log) {
        String install = "rpm".equals(kind)
                ? "pkexec rpm -U --replacepkgs \"$PKG\""
                : "pkexec apt-get install -y \"$PKG\"";
        return "#!/bin/sh\n"
                + "PKG=" + shQuote(pkg.getAbsolutePath()) + "\n"
                + "LOG=" + shQuote(log.getAbsolutePath()) + "\n"
                + "while kill -0 " + pid + " 2>/dev/null; do sleep 0.5; done\n"
                + "echo \"installing $PKG\" >> \"$LOG\"\n"
                + install + " >> \"$LOG\" 2>&1\n"
                + "echo \"installer exit $?\" >> \"$LOG\"\n"
                + "nohup " + relaunch + " >/dev/null 2>&1 &\n"
                + "rm -f \"$0\"\n";
    }

    // ------------------------------------------------------------ internals

    private void download(Release wanted) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No storage for the download");
        File target = new File(dir, wanted.name);
        File part = new File(dir, wanted.name + ".part");
        if (target.isFile() && matches(wanted.sha256, sha256Hex(target))) {
            installer = target;
            set(Phase.READY, "Version " + wanted.version + " is verified and ready to install.");
            return;
        }
        File[] old = dir.listFiles();
        if (old != null) {
            for (File file : old) {
                if (!file.equals(target) && !file.equals(new File(dir, "update.log"))) {
                    file.delete();
                }
            }
        }
        HttpURLConnection connection = open(wanted.url);
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("Download server returned HTTP " + code);
            total = wanted.size > 0 ? wanted.size : connection.getContentLengthLong();
            done = 0;
            set(Phase.DOWNLOADING, "Downloading version " + wanted.version + "…");
            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                long lastPost = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastPost > 100) {
                        lastPost = now;
                        notifyListener();
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
        if (wanted.size > 0 && done != wanted.size) {
            part.delete();
            throw new IOException("The download was incomplete");
        }
        set(Phase.VERIFYING, "Checking the download…");
        if (!matches(wanted.sha256, sha256Hex(part))) {
            part.delete();
            throw new IOException("The download did not match the published checksum. "
                    + "Nothing was installed.");
        }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        installer = target;
        set(Phase.READY, "Version " + wanted.version + " is verified and ready to install.");
    }

    private static String fetchText(String address) throws IOException {
        HttpURLConnection connection = open(address);
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("Update server returned HTTP " + code);
            try (InputStream in = connection.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 128 * 1024) throw new IOException("Update manifest is too large");
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json, application/octet-stream");
        connection.setRequestProperty("User-Agent", "Hole-Shield-Desktop/" + DesktopApp.VERSION);
        return connection;
    }

    private File write(String name, String text) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No storage for the installer");
        File file = new File(dir, name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        file.setExecutable(true);
        return file;
    }

    private boolean busy() {
        switch (phase) {
            case CHECKING:
            case DOWNLOADING:
            case VERIFYING:
            case INSTALLING:
                return true;
            default:
                return false;
        }
    }

    /** Settles a check, stamping the time the manifest was last read. */
    private void finish(Phase next, String text) {
        checkedAt = System.currentTimeMillis();
        set(next, text);
    }

    private void set(Phase next, String text) {
        phase = next;
        message = text;
        notifyListener();
    }

    private void notifyListener() {
        Runnable current = listener;
        if (current != null) current.run();
    }

    private static String bundleOf(String command) {
        int at = command.indexOf(".app/Contents/MacOS/");
        return at > 0 ? command.substring(0, at + 4) : "/Applications/Hole Shield.app";
    }

    private static String shQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String psQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String failure(Exception e, String fallback) {
        String text = e.getMessage();
        return text == null || text.isEmpty() ? fallback : text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static long numberAt(String part) {
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) end++;
        if (end == 0) return 0;
        try {
            return Long.parseLong(part.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
