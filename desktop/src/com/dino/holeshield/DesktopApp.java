package com.dino.holeshield;

import javax.swing.SwingUtilities;
import java.io.File;

/** Entry point for the Windows, Linux and macOS build. */
public final class DesktopApp {

    static final String VERSION = "2.3.4";
    static final String NAME = "Hole Shield";

    private final DesktopPrefs prefs;
    private FilterEngine filter;
    private StatsStore stats;
    private DnsService dns;
    private WebDashboard web;
    private TrayController tray;
    private ControlPanel frame;
    private boolean headless;
    private boolean minimized;
    private boolean replace;
    private boolean openDashboardAtStart;
    private String initialPage = "Overview";

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

        if (!replace && !AppDirs.acquireLock()) {
            String url = "http://127.0.0.1:" + prefs.getInt("webPort", 8080) + "/admin/";
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
            Theme.install();
            Theme.setMode(prefs.get("theme", Theme.MODE_SYSTEM));
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
            System.out.println("DNS:       " + app.bindSummary());
            System.out.println("Dashboard: http://127.0.0.1:"
                    + app.webPort() + "/admin/");
            System.out.println("Data:      " + AppDirs.dataDir().getAbsolutePath());
            try {
                Thread.currentThread().join();
            } catch (InterruptedException ignored) { }
            return;
        }

        SwingUtilities.invokeLater(() -> app.startUi());
    }

    private void startServices() {
        filter = new FilterEngine(prefs, AppDirs.dataDir());
        filter.load();
        stats = new StatsStore(AppDirs.dataDir());
        stats.load();
        DnsCache cache = new DnsCache(prefs.getInt("cacheEntries", 5000));
        Upstream upstream = new Upstream(prefs, cache);
        dns = new DnsService(prefs, filter, stats, upstream);
        dns.setListener(() -> {
            if (frame != null) SwingUtilities.invokeLater(() -> frame.refreshHeader());
            if (tray != null) SwingUtilities.invokeLater(tray::update);
        });
        dns.start();
        web = new WebDashboard(prefs, filter, stats, dns, VERSION);
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
        frame.setVisible(!minimized || tray == null);
        if (openDashboardAtStart) openDashboard();
        if (!dns.bindError().isEmpty()) {
            frame.setStatus(dns.bindError());
        } else {
            frame.setStatus("DNS listening on " + bindSummary());
        }
    }

    // ------------------------------------------------------------- app actions

    void toggleDns() {
        if (dns.running()) {
            dns.stop();
            setStatus("DNS stopped");
        } else {
            dns.start();
            setStatus(dns.running() ? "DNS listening on " + bindSummary()
                    : dns.bindError());
        }
        refreshAll();
    }

    void pause(long seconds) {
        prefs.pause(seconds);
        refreshAll();
    }

    void resume() {
        prefs.resume();
        refreshAll();
    }

    void updateLists() {
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

    void showWindow() {
        if (frame == null) return;
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
    }

    void openDataFolder() {
        AppDirs.open(AppDirs.dataDir());
    }

    void applyTheme() {
        Theme.setMode(prefs.get("theme", Theme.MODE_SYSTEM));
        if (AppDirs.isMac()) {
            System.setProperty("apple.awt.application.appearance",
                    Theme.get().dark ? "NSAppearanceNameDarkAqua"
                            : "NSAppearanceNameAqua");
        }
        if (frame != null) frame.rebuild();
        if (tray != null) tray.update();
    }

    void applySettings() {
        Theme.setMode(prefs.get("theme", Theme.MODE_SYSTEM));
        if (AppDirs.isMac()) {
            System.setProperty("apple.awt.application.appearance",
                    Theme.get().dark ? "NSAppearanceNameDarkAqua"
                            : "NSAppearanceNameAqua");
        }
        filter.reloadRules();
        dns.stop();
        dns.start();
        web.stop();
        web.start();
        if (frame != null) {
            frame.rebuild();
            frame.setStatus(dns.running() ? "Settings applied · DNS on "
                    + bindSummary() : dns.bindError());
        }
        if (tray != null) tray.update();
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

    private void stopServices() {
        if (dns != null) dns.stop();
        if (web != null) web.stop();
        if (stats != null) stats.save();
        if (prefs != null) prefs.save();
        if (tray != null) tray.remove();
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

    boolean isDnsRunning() { return dns != null && dns.running(); }
    boolean isBlocking() { return prefs.blockingActive(); }
    boolean isUpdating() { return filter != null && filter.isUpdating(); }
    long blockedToday() { return stats == null ? 0 : stats.blocked(); }
    int dnsPort() {
        return dns != null && dns.actualPort() > 0 ? dns.actualPort()
                : prefs.getInt("dnsPort", 53);
    }
    int webPort() {
        return web != null && web.actualPort() > 0 ? web.actualPort()
                : prefs.getInt("webPort", 8080);
    }
    String dnsError() { return dns == null ? "" : dns.bindError(); }
    String webError() { return web == null ? "" : web.error(); }
    String bindSummary() {
        String bind = prefs.get("bindAddress", "0.0.0.0");
        return (bind == null || bind.isEmpty() ? "0.0.0.0" : bind)
                + ":" + dnsPort();
    }

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
                + "  --replace           Start even if another instance is running\n"
                + "  --version           Print the version\n";
    }
}
