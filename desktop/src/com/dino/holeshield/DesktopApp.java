package com.dino.holeshield;

import javax.swing.SwingUtilities;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Entry point for the Windows, Linux and macOS build. */
public final class DesktopApp {

    static final String VERSION = "2.3.6";
    static final String NAME = "Hole Shield";

    private final DesktopPrefs prefs;
    private FilterEngine filter;
    private StatsStore stats;
    private DnsService dns;
    private Upstream upstream;
    private WebDashboard web;
    private TrayController tray;
    private ControlPanel frame;
    private CompanionClient companion;
    private volatile CompanionClient.Snapshot companionSnapshot;
    private volatile CompanionClient.Dashboard companionDashboard;
    private volatile String companionRange = "24h";
    private volatile String companionError = "";
    private volatile boolean companionUpdating;
    private volatile int companionGeneration;
    private ScheduledExecutorService companionPoller;
    private long lastCompanionAttempt;
    private long lastDashboardAt;
    private boolean companionAuthFailed;
    private String lastCompanionStatus = "";
    private boolean headless;
    private boolean minimized;
    private boolean replace;
    private boolean openDashboardAtStart;
    private String initialPage = "Overview";
    private final AtomicBoolean applying = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final Updater updater = new Updater(AppDirs.dataDir());

    private DesktopApp(DesktopPrefs prefs) {
        this.prefs = prefs;
    }

    public static void main(String[] args) {
        boolean headless = false;
        boolean minimized = false;
        boolean replace = false;
        boolean openDashboard = false;
        File dataDir = null;
        Integer dnsPort = null;
        Integer webPort = null;
        String initialPage = "Overview";
        String companionTarget = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--headless".equals(arg)) headless = true;
            else if ("--minimized".equals(arg)) minimized = true;
            else if ("--replace".equals(arg)) replace = true;
            else if ("--open-dashboard".equals(arg)) openDashboard = true;
            else if ("--version".equals(arg)) {
                System.out.println(NAME + " " + VERSION);
                return;
            } else if (arg.startsWith("--data-dir=")) {
                dataDir = new File(arg.substring("--data-dir=".length()));
            } else if (arg.equals("--data-dir") && i + 1 < args.length) {
                dataDir = new File(args[++i]);
            } else if (arg.startsWith("--port=")) {
                dnsPort = parseInt(arg.substring("--port=".length()), 53);
            } else if (arg.equals("--port") && i + 1 < args.length) {
                dnsPort = parseInt(args[++i], 53);
            } else if (arg.startsWith("--page=")) {
                initialPage = arg.substring("--page=".length());
            } else if (arg.equals("--page") && i + 1 < args.length) {
                initialPage = args[++i];
            } else if (arg.startsWith("--web-port=")) {
                webPort = parseInt(arg.substring("--web-port=".length()), 8080);
            } else if (arg.equals("--web-port") && i + 1 < args.length) {
                webPort = parseInt(args[++i], 8080);
            } else if (arg.startsWith("--companion=")) {
                companionTarget = arg.substring("--companion=".length()).trim();
            } else if (arg.equals("--companion") && i + 1 < args.length) {
                companionTarget = args[++i].trim();
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                System.out.println(usage());
                return;
            }
        }

        if (dataDir != null) AppDirs.setDataDir(dataDir);
        DesktopPrefs prefs = new DesktopPrefs(AppDirs.dataDir());
        prefs.load();
        if (dnsPort != null) prefs.setInt("dnsPort", dnsPort);
        if (webPort != null) prefs.setInt("webPort", webPort);
        if (companionTarget != null && !companionTarget.isEmpty()) {
            prefs.setBool("companionMode", true);
            prefs.set("companionServer", companionTarget);
            prefs.save();
        }

        if (!replace && !AppDirs.acquireLock()) {
            String url;
            if (prefs.getBool("companionMode", false)) {
                try {
                    url = CompanionClient.base(prefs.get("companionServer", ""),
                            prefs.getInt("companionPort", 8080)) + "/admin/";
                } catch (Exception e) {
                    url = "the configured companion address";
                }
            } else {
                url = "http://127.0.0.1:" + prefs.getInt("webPort", 8080) + "/admin/";
            }
            if (headless) {
                System.out.println("Hole Shield is already running: " + url);
            } else {
                AppDirs.browse(url);
                System.out.println("Hole Shield is already running: " + url);
            }
            return;
        }

        DesktopApp app = new DesktopApp(prefs);
        app.headless = headless;
        app.minimized = minimized;
        app.replace = replace;
        app.openDashboardAtStart = openDashboard;
        app.initialPage = initialPage;
        if (headless) {
            System.setProperty("java.awt.headless", "true");
        } else {
            if (AppDirs.isMac()) {
                // Must be set before the look and feel loads, otherwise the
                // menu bar is placed inside the window instead of the screen.
                System.setProperty("apple.laf.useScreenMenuBar", "true");
            }
            Theme.install();
            Theme.setMode(prefs.get("theme", Theme.MODE_SYSTEM), prefs.get("accent", "default"));
            if (AppDirs.isMac()) {
                System.setProperty("apple.awt.application.name", NAME);
                System.setProperty("apple.awt.application.appearance",
                        Theme.get().dark ? "NSAppearanceNameDarkAqua"
                                : "NSAppearanceNameAqua");
            }
        }
        app.startServices();

        Runtime.getRuntime().addShutdownHook(new Thread(app::stopServices,
                "shutdown"));

        if (headless) {
            System.out.println(NAME + " " + VERSION + " desktop");
            if (app.isCompanion()) {
                System.out.println("Companion: " + app.companionStatus());
                System.out.println("Dashboard: " + app.companion().dashboardUrl());
            } else {
                System.out.println("DNS:       " + app.bindSummary());
                System.out.println("Dashboard: http://127.0.0.1:"
                        + app.webPort() + "/admin/");
            }
            System.out.println("Data:      " + AppDirs.dataDir().getAbsolutePath());
            try {
                Thread.currentThread().join();
            } catch (InterruptedException ignored) { }
            return;
        }

        SwingUtilities.invokeLater(() -> app.startUi());
    }

    private void startServices() {
        if (isCompanion()) {
            startCompanion();
            return;
        }
        if (filter == null) {
            filter = new FilterEngine(prefs, AppDirs.dataDir());
            filter.load();
        }
        if (stats == null) {
            stats = new StatsStore(AppDirs.dataDir());
            stats.load();
        }
        if (dns == null) {
            DnsCache cache = new DnsCache(prefs.getInt("cacheEntries", 5000));
            upstream = new Upstream(prefs, cache);
            dns = new DnsService(prefs, filter, stats, upstream);
            dns.setListener(() -> {
                if (frame != null) SwingUtilities.invokeLater(() -> frame.refreshHeader());
                if (tray != null) SwingUtilities.invokeLater(tray::update);
            });
        }
        dns.start();
        if (web == null) {
            web = new WebDashboard(prefs, filter, stats, dns, VERSION);
        }
        web.start();
        if (!dns.bindError().isEmpty()) {
            System.err.println(dns.bindError());
        }
        if (!web.error().isEmpty()) {
            System.err.println(web.error());
        }
    }

    private void startUi() {
        frame = new ControlPanel(this);
        frame.openPage(initialPage);
        tray = new TrayController(this);
        if (AppDirs.isMac() && java.awt.Desktop.isDesktopSupported()) {
            try {
                java.awt.Desktop.getDesktop().addAppEventListener(
                        (java.awt.desktop.AppReopenedListener) event -> showWindow());
            } catch (UnsupportedOperationException | SecurityException ignored) {
                // The tray menu remains available when macOS reopen is unsupported.
            }
        }
        frame.setVisible(!minimized || tray == null);
        // Fill in the pages that startup skipped, once the window is up.
        SwingUtilities.invokeLater(frame::warmPages);
        SwingUtilities.invokeLater(this::autoCheckUpdates);
        if (openDashboardAtStart) openDashboard();
        if (isCompanion()) {
            frame.setStatus(companionConnected()
                    ? "Companion connected to " + companion.display()
                    : companionStatus());
        } else if (!dns.bindError().isEmpty()) {
            frame.setStatus(dns.bindError());
        } else {
            frame.setStatus("DNS listening on " + bindSummary());
        }
    }

    // ------------------------------------------------------------- app actions

    void toggleDns() {
        if (isCompanion()) {
            setBlocking(!isBlocking(), 0);
            return;
        }
        if (applying.get()) {
            setStatus("Settings are still applying. Try again in a moment.");
            return;
        }
        boolean stopping = dns.running();
        setStatus(stopping ? "Stopping DNS…" : "Starting DNS…");
        inBackground(() -> {
            if (stopping) dns.stop();
            else dns.start();
        }, () -> {
            setStatus(stopping ? "DNS stopped" : dns.running()
                    ? "DNS listening on " + bindSummary() : dns.bindError());
            refreshAll();
        });
    }

    /**
     * Runs slow work (disk, sockets, process spawns, list parsing) off the
     * Swing thread and then calls {@code done} back on it.
     */
    void inBackground(Runnable work, Runnable done) {
        Thread thread = new Thread(() -> {
            try {
                work.run();
            } finally {
                if (done != null) SwingUtilities.invokeLater(done);
            }
        }, "hs-background");
        thread.setDaemon(true);
        thread.start();
    }

    boolean isApplying() {
        return applying.get();
    }

    void pause(long seconds) {
        if (isCompanion()) {
            setBlocking(false, seconds);
            return;
        }
        prefs.pause(seconds);
        refreshAll();
    }

    void resume() {
        if (isCompanion()) {
            setBlocking(true, 0);
            return;
        }
        prefs.resume();
        refreshAll();
    }

    void updateLists() {
        if (isCompanion()) {
            updateCompanionLists();
            return;
        }
        if (filter.isUpdating()) return;
        setStatus("Updating blocklists…");
        filter.updateBlocklists(
                (message, done, total) -> SwingUtilities.invokeLater(
                        () -> setStatus(message)),
                () -> SwingUtilities.invokeLater(() -> {
                    refreshAll();
                    setStatus(filter.lastError().isEmpty()
                            ? "Blocklists updated · " + formatNumber(filter.gravityCount())
                            + " domains"
                            : filter.lastError());
                    if (tray != null) {
                        tray.showMessage(NAME, filter.lastError().isEmpty()
                                ? "Blocklists updated · " + formatNumber(filter.gravityCount())
                                + " domains"
                                : filter.lastError());
                    }
                }));
        refreshAll();
    }

    void openDashboard() {
        if (isCompanion()) {
            CompanionClient client = companion;
            if (client == null) {
                setStatus("Companion mode is not configured yet.");
                return;
            }
            String url = client.dashboardUrl();
            if (url.isEmpty()) {
                setStatus("Enter the address of the Hole Shield system first.");
                return;
            }
            AppDirs.browse(url);
            return;
        }
        if (!web.running()) {
            setStatus(web.error());
            return;
        }
        String bind = prefs.get("webBind", "0.0.0.0");
        String host = bind == null || bind.isEmpty() || "0.0.0.0".equals(bind)
                || "*".equals(bind) ? "127.0.0.1"
                : "::".equals(bind) ? "[::1]" : bind;
        AppDirs.browse("http://" + host + ":" + web.actualPort() + "/admin/");
    }

    void flushCache() {
        if (isCompanion()) {
            CompanionClient client = companion;
            if (client == null) return;
            setStatus("Flushing the remote DNS cache…");
            new Thread(() -> {
                String message;
                try {
                    client.flushCache();
                    message = "Remote DNS cache flushed.";
                } catch (Exception e) {
                    message = "Companion: " + e.getMessage();
                }
                final String text = message;
                SwingUtilities.invokeLater(() -> {
                    setStatus(text);
                    refreshAll();
                });
            }, "companion-flush").start();
            return;
        }
        if (dns != null) {
            dns.flushCache();
            setStatus("DNS cache flushed.");
        }
    }

    void showWindow() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::showWindow);
            return;
        }
        if (frame == null) return;
        frame.setVisible(true);
        frame.setExtendedState(frame.getExtendedState() & ~java.awt.Frame.ICONIFIED);
        frame.toFront();
        frame.requestFocus();
        frame.warmPages();
        frame.refreshHeader();
    }

    Updater updater() {
        return updater;
    }

    void checkForUpdates() {
        if (frame != null) frame.openPage("About");
        updater.check();
    }

    /** Records when the manifest was last read, so the pane can show it. */
    void noteUpdateCheck(long at) {
        prefs.set("lastUpdateCheck", String.valueOf(at));
        inBackground(prefs::save, null);
    }

    /** Runs the daily check promised by the Updates pane, unless it is turned off. */
    private void autoCheckUpdates() {
        if (!prefs.getBool("autoUpdateCheck", true)) return;
        if (updater.phase() != Updater.Phase.IDLE) return;
        long last = prefs.getLong("lastUpdateCheck", 0L);
        if (last > 0 && System.currentTimeMillis() - last < 24L * 60L * 60L * 1000L) return;
        updater.check();
    }

    void installUpdate() {
        int answer = javax.swing.JOptionPane.showConfirmDialog(frame,
                "Hole Shield will close, install version " + updater.version()
                        + " and open again.",
                NAME, javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.QUESTION_MESSAGE);
        if (answer != javax.swing.JOptionPane.OK_OPTION) return;
        if (updater.startInstaller()) shutdown();
    }

    void openDataFolder() {
        AppDirs.open(AppDirs.dataDir());
    }

    void applyTheme() {
        Theme.setMode(prefs.get("theme", Theme.MODE_SYSTEM), prefs.get("accent", "default"));
        if (AppDirs.isMac()) {
            System.setProperty("apple.awt.application.appearance",
                    Theme.get().dark ? "NSAppearanceNameDarkAqua"
                            : "NSAppearanceNameAqua");
        }
        if (frame != null) frame.rebuild();
        if (tray != null) tray.update();
    }

    void applySettings() {
        applySettings(null);
    }

    /**
     * Restarts the services with the saved settings. {@code beforeRestart}
     * runs first on the background thread, for slow work such as hashing a
     * new password that must land before the dashboard restarts.
     */
    void applySettings(Runnable beforeRestart) {
        if (!applying.compareAndSet(false, true)) {
            setStatus("Settings are still applying. Try again in a moment.");
            return;
        }
        Theme.setMode(prefs.get("theme", Theme.MODE_SYSTEM), prefs.get("accent", "default"));
        if (AppDirs.isMac()) {
            System.setProperty("apple.awt.application.appearance",
                    Theme.get().dark ? "NSAppearanceNameDarkAqua"
                            : "NSAppearanceNameAqua");
        }
        boolean companionMode = isCompanion();
        setStatus("Applying settings…");
        inBackground(() -> {
            if (beforeRestart != null) beforeRestart.run();
            restartServices(companionMode);
        }, () -> {
            try {
                if (frame != null) {
                    frame.applySettingsChanged();
                    frame.setStatus(companionMode
                            ? (companionConnected() ? "Companion connected to "
                            + companion.display() : companionStatus())
                            : dns.running() ? "Settings applied · DNS on "
                            + bindSummary() : dns.bindError());
                }
                if (tray != null) tray.update();
            } finally {
                applying.set(false);
            }
        });
    }

    private void restartServices(boolean companionMode) {
        stopCompanion();
        stopNetworkServices();
        if (upstream != null) upstream.reset();
        if (companionMode) {
            // Companion mode is a console for another system; release the
            // local gravity index so it does not sit in memory unused.
            filter = null;
            stats = null;
            dns = null;
            web = null;
        } else if (filter != null) {
            filter.reloadRules();
        }
        startServices();
    }

    // ------------------------------------------------------ companion console

    boolean isCompanion() {
        return prefs.getBool("companionMode", false);
    }

    CompanionClient companion() { return companion; }
    CompanionClient.Snapshot companionSnapshot() { return companionSnapshot; }
    CompanionClient.Dashboard companionDashboard() { return companionDashboard; }

    String companionRange() { return companionRange; }

    void setCompanionRange(String range) { companionRange = range; }
    String companionError() { return companionError; }
    boolean companionConnected() {
        return companion != null && companion.connected();
    }

    String companionStatus() {
        if (companion == null) return "Companion is not configured.";
        if (!companionError.isEmpty()) return "Companion: " + companionError;
        return companionConnected() ? "Companion connected to " + companion.display()
                : "Companion is offline.";
    }

    void reconnectCompanion() {
        if (!isCompanion()) return;
        startCompanion();
        if (frame != null) {
            frame.applySettingsChanged();
            frame.setStatus(companionStatus());
        }
    }

    void setBlocking(boolean blocking, long seconds) {
        if (!isCompanion()) {
            if (blocking) prefs.resume();
            else prefs.pause(seconds);
            refreshAll();
            return;
        }
        CompanionClient client = companion;
        if (client == null) return;
        final int generation = companionGeneration;
        setStatus(blocking ? "Resuming remote blocking…"
                : seconds > 0 ? "Pausing remote blocking for "
                + formatDuration(seconds) + "…" : "Pausing remote filtering…");
        new Thread(() -> {
            String message;
            try {
                client.setBlocking(blocking, (int) Math.min(Integer.MAX_VALUE,
                        Math.max(0, seconds)));
                if (generation != companionGeneration) return;
                companionSnapshot = client.snapshot();
                companionError = "";
                message = client.display() + (blocking
                        ? " is blocking again." : " paused filtering.");
            } catch (Exception e) {
                if (generation != companionGeneration) return;
                companionError = e.getMessage();
                message = "Companion: " + e.getMessage();
            }
            final String text = message;
            SwingUtilities.invokeLater(() -> {
                if (generation != companionGeneration) return;
                setStatus(text);
                refreshAll();
            });
        }, "companion-blocking").start();
    }

    // ------------------------------------------------- remote edits (mirror)

    private interface RemoteEdit {
        void run(CompanionClient client) throws Exception;
    }

    /** Applies an edit on the remote, then reloads the mirror. */
    private void remoteEdit(RemoteEdit work, String success) {
        CompanionClient client = companion;
        if (client == null) {
            setStatus("Companion mode is not configured yet.");
            return;
        }
        final int generation = companionGeneration;
        setStatus("Sending to " + client.display() + "…");
        inBackground(() -> {
            String message;
            try {
                work.run(client);
                CompanionClient.Dashboard dashboard = client.dashboard(companionRange);
                if (generation == companionGeneration) companionDashboard = dashboard;
                message = success;
            } catch (Exception e) {
                message = "Companion: " + e.getMessage();
            }
            final String text = message;
            SwingUtilities.invokeLater(() -> {
                if (generation != companionGeneration) return;
                setStatus(text);
                refreshAll();
            });
        }, null);
    }

    void companionAddRule(boolean allow, String kind, String domain) {
        remoteEdit(client -> client.addRule(allow, kind, domain),
                (allow ? "Allowed " : "Blocked ") + domain + " on the remote system.");
    }

    void companionRemoveRule(boolean allow, String kind, String domain) {
        remoteEdit(client -> client.removeRule(allow, kind, domain),
                "Removed the " + (allow ? "allow" : "block") + " rule for " + domain + ".");
    }

    void companionAddList(String address) {
        remoteEdit(client -> client.addList(address),
                "Added " + address + " to the remote blocklists.");
    }

    void companionRemoveList(int id, String address) {
        remoteEdit(client -> client.removeList(id),
                "Removed " + address + " from the remote blocklists.");
    }

    void companionSetCategory(String key, boolean enabled) {
        remoteEdit(client -> client.setCategory(key, enabled),
                (enabled ? "Enabled " : "Disabled ") + key + " on the remote system.");
    }

    void companionSetCategoryLevel(String key, String level) {
        remoteEdit(client -> client.setCategoryLevel(key, level),
                "Set " + key + " to " + level + " on the remote system.");
    }

    private void updateCompanionLists() {
        CompanionClient client = companion;
        if (client == null || companionUpdating) return;
        final int generation = companionGeneration;
        companionUpdating = true;
        setStatus("Asking " + client.display() + " to update blocklists…");
        new Thread(() -> {
            String message;
            try {
                client.updateGravity();
                if (generation != companionGeneration) return;
                message = "Remote blocklist update started on " + client.display() + ".";
                companionError = "";
            } catch (Exception e) {
                if (generation != companionGeneration) return;
                companionError = e.getMessage();
                message = "Companion: " + e.getMessage();
            }
            final String text = message;
            companionUpdating = false;
            SwingUtilities.invokeLater(() -> {
                if (generation != companionGeneration) return;
                setStatus(text);
                refreshAll();
            });
        }, "companion-gravity").start();
    }

    private void startCompanion() {
        stopCompanion();
        final int generation = ++companionGeneration;
        companion = new CompanionClient(
                prefs.get("companionServer", ""),
                prefs.getInt("companionPort", 8080),
                prefs.get("companionUser", ""),
                prefs.get("companionPassword", ""));
        companionSnapshot = null;
        companionDashboard = null;
        companionError = "";
        companionUpdating = false;
        lastCompanionAttempt = 0L;
        lastDashboardAt = 0L;
        companionAuthFailed = false;
        try {
            // Try once on the calling thread so the window and the headless
            // banner show a real state immediately; the poller retries later.
            companion.connect();
        } catch (CompanionClient.AuthException e) {
            companionAuthFailed = true;
            lastCompanionAttempt = System.currentTimeMillis();
            companionError = e.getMessage();
        } catch (Exception e) {
            companionError = e.getMessage();
        }
        companionPoller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "companion-poll");
            thread.setDaemon(true);
            return thread;
        });
        companionPoller.scheduleWithFixedDelay(
                () -> pollCompanion(generation, companion), 0, 2, TimeUnit.SECONDS);
    }

    private void pollCompanion(int generation, CompanionClient client) {
        if (generation != companionGeneration || client == null) return;
        long now = System.currentTimeMillis();
        try {
            if (!client.connected()) {
                // A rejected password waits for the user to press Reconnect or
                // apply new settings; retrying would keep the remote account
                // locked out.
                if (companionAuthFailed) return;
                if (now - lastCompanionAttempt < 5000L) return;
                lastCompanionAttempt = now;
                client.connect();
                companionAuthFailed = false;
            }
            CompanionClient.Snapshot snapshot = client.snapshot();
            if (generation != companionGeneration) return;
            companionSnapshot = snapshot;
            companionError = "";
            // The full dashboard mirror changes slowly; refresh it every few
            // seconds so the console does not hammer the remote.
            if (now - lastDashboardAt >= 4000L || (companionDashboard != null
                    && !companionRange.equals(companionDashboard.range))) {
                lastDashboardAt = now;
                try {
                    CompanionClient.Dashboard dashboard = client.dashboard(companionRange);
                    if (generation == companionGeneration) {
                        companionDashboard = dashboard;
                    }
                } catch (CompanionClient.AuthException e) {
                    throw e;
                } catch (Exception ignored) {
                    // Keep the previous mirror; the summary already succeeded.
                }
            }
        } catch (CompanionClient.AuthException e) {
            if (generation != companionGeneration) return;
            companionAuthFailed = true;
            lastCompanionAttempt = now;
            companionError = e.getMessage();
        } catch (Exception e) {
            if (generation != companionGeneration) return;
            companionError = e.getMessage();
        }
        SwingUtilities.invokeLater(() -> {
            if (generation != companionGeneration) return;
            if (frame != null) {
                frame.refreshAll();
                // Only speak up when the connection state actually changes, so a
                // result message from an action is not overwritten every two seconds.
                String status = companionStatus();
                if (!status.equals(lastCompanionStatus)) {
                    lastCompanionStatus = status;
                    frame.setStatus(status);
                }
            }
            if (tray != null) tray.update();
        });
    }

    private void stopCompanion() {
        companionGeneration++;
        lastCompanionStatus = "";
        if (companionPoller != null) {
            companionPoller.shutdownNow();
            companionPoller = null;
        }
        companion = null;
        companionSnapshot = null;
        companionError = "";
        companionUpdating = false;
    }

    private static String formatDuration(long seconds) {
        if (seconds <= 0) return "until resumed";
        if (seconds % 3600 == 0) return (seconds / 3600) + "h";
        if (seconds % 60 == 0) return (seconds / 60) + " min";
        return seconds + "s";
    }

    void restartAsAdmin() {
        int answer = javax.swing.JOptionPane.showConfirmDialog(frame,
                "Restart Hole Shield with administrator rights so it can bind port 53?",
                NAME, javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.QUESTION_MESSAGE);
        if (answer != javax.swing.JOptionPane.OK_OPTION) return;
        stopServices();
        AppDirs.releaseLock();
        boolean ok = SystemIntegration.relaunchAsAdmin("--port=" + prefs.getInt("dnsPort", 53));
        if (!ok) {
            AppDirs.acquireLock();
            stopping.set(false);
            startServices();
            setStatus("This platform does not support automatic elevation. Run the app from a terminal with sudo.");
            return;
        }
        System.exit(0);
    }

    void shutdown() {
        stopServices();
        AppDirs.releaseLock();
        System.exit(0);
    }

    /**
     * Stops background services exactly once. The tray icon is intentionally
     * left to the OS: removing it from a shutdown hook waits on the event
     * thread and deadlocks the quit.
     */
    private void stopServices() {
        if (!stopping.compareAndSet(false, true)) return;
        stopCompanion();
        stopNetworkServices();
    }

    private void stopNetworkServices() {
        if (dns != null) dns.stop();
        if (web != null) web.stop();
        if (stats != null) stats.save();
        if (prefs != null) prefs.save();
    }

    private void refreshAll() {
        if (frame != null) frame.refreshAll();
        if (tray != null) tray.update();
    }

    void setStatus(String text) {
        if (frame != null) frame.setStatus(text);
    }

    // ----------------------------------------------------------------- getters

    DesktopPrefs prefs() { return prefs; }
    FilterEngine filter() { return filter; }
    StatsStore stats() { return stats; }
    DnsService dns() { return dns; }
    WebDashboard web() { return web; }

    boolean isDnsRunning() { return !isCompanion() && dns != null && dns.running(); }

    /** Non-empty when DoH is failing and plain DNS is being used instead. */
    String upstreamWarning() {
        return dns == null ? "" : dns.upstreamWarning();
    }
    boolean isBlocking() {
        if (isCompanion()) {
            CompanionClient.Snapshot snapshot = companionSnapshot;
            return snapshot == null || snapshot.blocking;
        }
        return prefs.blockingActive();
    }
    boolean isUpdating() {
        if (isCompanion()) return companionUpdating;
        return filter != null && filter.isUpdating();
    }
    long blockedToday() {
        if (isCompanion()) {
            CompanionClient.Snapshot snapshot = companionSnapshot;
            return snapshot == null ? 0 : snapshot.blocked;
        }
        return stats == null ? 0 : stats.todayBlocked();
    }
    int dnsPort() {
        if (isCompanion()) {
            try {
                java.net.URI uri = java.net.URI.create(CompanionClient.base(
                        prefs.get("companionServer", ""),
                        prefs.getInt("companionPort", 8080)));
                if (uri.getPort() > 0) return uri.getPort();
            } catch (Exception ignored) { }
            return prefs.getInt("companionPort", 8080);
        }
        return dns != null && dns.actualPort() > 0 ? dns.actualPort()
                : prefs.getInt("dnsPort", 53);
    }
    int webPort() {
        if (isCompanion()) return prefs.getInt("companionPort", 8080);
        return web != null && web.actualPort() > 0 ? web.actualPort()
                : prefs.getInt("webPort", 8080);
    }
    String dnsError() {
        if (isCompanion()) return companionError;
        return dns == null ? "" : dns.bindError();
    }
    String webError() {
        if (isCompanion()) return companionError;
        return web == null ? "" : web.error();
    }
    String bindSummary() {
        if (isCompanion()) {
            CompanionClient client = companion;
            return client == null ? "" : client.display();
        }
        String bind = prefs.get("bindAddress", "0.0.0.0");
        return (bind == null || bind.isEmpty() ? "0.0.0.0" : bind)
                + ":" + dnsPort();
    }

    String initialPage() { return initialPage; }
    boolean headless() { return headless; }
    boolean replace() { return replace; }

    static String formatNumber(long value) {
        return String.format(java.util.Locale.US, "%,d", value);
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); }
        catch (Exception e) { return fallback; }
    }

    private static String usage() {
        return "Hole Shield desktop " + VERSION + "\n\n"
                + "Usage: holeshield [options]\n"
                + "  --port N            DNS port (default 53; falls back to 5354)\n"
                + "  --web-port N        Dashboard port (default 8080)\n"
                + "  --data-dir DIR      Settings and gravity directory\n"
                + "  --headless          Run DNS and dashboard without a window\n"
                + "  --minimized         Start hidden in the tray\n"
                + "  --open-dashboard    Open the web dashboard on launch\n"
                + "  --page NAME         Start on Overview, Query log, Blocklists, Rules or Settings\n"
                + "  --companion HOST    Log in to a Hole Shield dashboard on the network\n"
                + "  --replace           Start even if another instance is running\n"
                + "  --version           Print the version\n";
    }
}
