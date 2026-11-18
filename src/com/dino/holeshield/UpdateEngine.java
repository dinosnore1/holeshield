package com.dino.holeshield;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Checks for, downloads, verifies and installs app updates inside the app.
 * Installation goes through PackageInstaller, so nothing opens a browser; the
 * system only asks the user to confirm the install on screen.
 */
final class UpdateEngine {

    enum Phase {
        IDLE, OFF, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY,
        NEEDS_PERMISSION, INSTALLING, INSTALLED, FAILED
    }

    interface Listener {
        void onUpdateState();
    }

    static final String ACTION_STATUS = "com.dino.holeshield.UPDATE_STATUS";

    private static final int MUTABLE =
            Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
    private static final long PROMPT_TIMEOUT_MS = 120000L;
    private static final ExecutorService work = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable promptTimeout = () -> {
        if (phase() == Phase.INSTALLING) {
            set(Phase.FAILED, "No install prompt or result arrived. Try again, and check that "
                    + "this device allows Hole Shield to install apps.");
        }
    };

    private static volatile Listener listener;
    private static volatile Phase phase = Phase.IDLE;
    private static volatile String message = "";
    private static volatile UpdateChecker.Release release;
    private static volatile long done;
    private static volatile long total;

    private UpdateEngine() { }

    static void setListener(Listener value) {
        listener = value;
    }

    static Phase phase() { return phase; }
    static String message() { return message; }
    static UpdateChecker.Release release() { return release; }
    static long done() { return done; }
    static long total() { return total; }

    static boolean permissionGranted(Context context) {
        return Build.VERSION.SDK_INT < 26
                || context.getPackageManager().canRequestPackageInstalls();
    }

    static Intent permissionIntent(Context context) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
    }

    /** Reads the manifest and reports whether a newer release is published. */
    static void check(Context context) {
        final Context app = context.getApplicationContext();
        final String channel = Prefs.load(app).updateChannel;
        if ("off".equals(channel)) {
            set(Phase.OFF, "Updates are turned off for this device.");
            return;
        }
        set(Phase.CHECKING, "Checking for updates…");
        work.execute(() -> {
            try {
                UpdateChecker.Release found = UpdateChecker.check(channel);
                release = found;
                recordCheck(app);
                int installed = Device.versionCode(app);
                if (!UpdateChecker.isNewer(found, installed)) {
                    set(Phase.CURRENT, "Hole Shield " + Device.versionName(app)
                            + " is the latest " + found.channel + " release.");
                } else if (found.sha256.isEmpty() || found.apk.isEmpty()) {
                    set(Phase.FAILED, "Version " + found.version + " has no published "
                            + "checksum, so it cannot be installed safely.");
                } else {
                    set(Phase.AVAILABLE, "Version " + found.version + " is ready to install.");
                }
            } catch (Exception e) {
                set(Phase.FAILED, failure(e, "Could not check for updates"));
            }
        });
    }

    /** Downloads, verifies and installs the release found by {@link #check}. */
    static void install(Context context) {
        final Context app = context.getApplicationContext();
        final UpdateChecker.Release wanted = release;
        if (wanted == null || !UpdateChecker.isNewer(wanted, Device.versionCode(app))) {
            check(app);
            return;
        }
        work.execute(() -> {
            try {
                File apk = cachedUpdate(app, wanted);
                if (apk == null) {
                    apk = download(app, wanted);
                    set(Phase.VERIFYING, "Checking the download…");
                    if (!UpdateVerifier.matches(wanted.sha256, UpdateVerifier.sha256Hex(apk))) {
                        apk.delete();
                        set(Phase.FAILED, "The download did not match the published checksum. "
                                + "Nothing was installed.");
                        return;
                    }
                    if (!isHoleShieldBuild(app, apk, wanted)) {
                        apk.delete();
                        set(Phase.FAILED, "The download is not a newer Hole Shield build. "
                                + "Nothing was installed.");
                        return;
                    }
                }
                set(Phase.READY, "Version " + wanted.version + " verified.");
                startInstall(app, apk);
            } catch (Exception e) {
                set(Phase.FAILED, failure(e, "The update could not be installed"));
            }
        });
    }

    /** Called by UpdateReceiver with the result of a PackageInstaller commit. */
    static void onInstallStatus(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String text = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        main.removeCallbacks(promptTimeout);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                } catch (Exception e) {
                    set(Phase.FAILED, "Could not show the install prompt.");
                    return;
                }
            }
            set(Phase.INSTALLING, "Confirm the install on screen to finish.");
            main.postDelayed(promptTimeout, PROMPT_TIMEOUT_MS);
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            set(Phase.INSTALLED, "Hole Shield was updated.");
        } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            set(Phase.FAILED, "The install was cancelled. Nothing was changed.");
        } else {
            set(Phase.FAILED, text == null || text.isEmpty()
                    ? "The install did not finish (code " + status + ")."
                    : "The install did not finish: " + text);
        }
    }

    private static void startInstall(Context app, File apk) throws IOException {
        if (!permissionGranted(app)) {
            set(Phase.NEEDS_PERMISSION, "Allow Hole Shield to install apps, then return here.");
            return;
        }
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream out = session.openWrite("base.apk", 0, apk.length());
                 InputStream in = new FileInputStream(apk)) {
                copy(in, out);
                session.fsync(out);
            }
            Intent status = new Intent(app, UpdateReceiver.class).setAction(ACTION_STATUS);
            PendingIntent pending = PendingIntent.getBroadcast(app, id, status,
                    PendingIntent.FLAG_UPDATE_CURRENT | MUTABLE);
            session.commit(pending.getIntentSender());
        }
        set(Phase.INSTALLING, "Installing…");
    }

    private static File download(Context app, UpdateChecker.Release r) throws IOException {
        File dir = new File(app.getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No storage for the download");
        File target = new File(dir, "holeshield-" + r.version + ".apk");
        File part = new File(dir, target.getName() + ".part");
        File[] old = dir.listFiles();
        if (old != null) {
            for (File file : old) {
                if (!file.equals(target)) file.delete();
            }
        }
        HttpURLConnection c = (HttpURLConnection) new URL(r.apk).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Hole-Shield-Updater");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Download server returned HTTP " + code);
            total = r.size > 0 ? r.size : c.getContentLengthLong();
            done = 0;
            set(Phase.DOWNLOADING, "Downloading version " + r.version + "…");
            try (InputStream in = c.getInputStream();
                 OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[32 * 1024];
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
            if (r.size > 0 && done != r.size) {
                part.delete();
                throw new IOException("The download was incomplete");
            }
            if (target.exists() && !target.delete()) throw new IOException("Could not replace the old download");
            if (!part.renameTo(target)) throw new IOException("Could not save the download");
            return target;
        } finally {
            c.disconnect();
        }
    }

    /** A previously downloaded build, returned only if it still matches the manifest. */
    private static File cachedUpdate(Context app, UpdateChecker.Release r) {
        File apk = new File(new File(app.getCacheDir(), "updates"),
                "holeshield-" + r.version + ".apk");
        if (!apk.isFile()) return null;
        try {
            if (UpdateVerifier.matches(r.sha256, UpdateVerifier.sha256Hex(apk))
                    && isHoleShieldBuild(app, apk, r)) {
                return apk;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static boolean isHoleShieldBuild(Context app, File apk, UpdateChecker.Release r) {
        PackageInfo info = app.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
        if (info == null || !app.getPackageName().equals(info.packageName)) return false;
        return info.versionCode > Device.versionCode(app)
                && (r.versionCode == 0 || info.versionCode == r.versionCode);
    }

    private static void recordCheck(Context app) {
        Prefs prefs = Prefs.load(app);
        prefs.lastUpdateCheck = System.currentTimeMillis();
        prefs.save(app);
    }

    private static String failure(Exception e, String fallback) {
        String text = e.getMessage();
        return text == null || text.isEmpty() ? fallback : text;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static void set(Phase next, String text) {
        phase = next;
        message = text;
        notifyListener();
    }

    private static void notifyListener() {
        final Listener current = listener;
        if (current != null) main.post(current::onUpdateState);
    }
}
