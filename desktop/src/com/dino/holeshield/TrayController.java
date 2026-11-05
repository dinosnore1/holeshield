package com.dino.holeshield;

import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionListener;

/** Menu-bar / notification-area controls. */
final class TrayController {

    private final DesktopApp app;
    private TrayIcon trayIcon;
    private MenuItem status;
    private MenuItem startStop;
    private MenuItem pause5;
    private MenuItem pause30;
    private MenuItem resume;
    private MenuItem update;

    TrayController(DesktopApp app) {
        this.app = app;
        install();
    }

    private void install() {
        if (!SystemTray.isSupported()) return;
        PopupMenu menu = new PopupMenu();

        MenuItem open = new MenuItem("Open dashboard");
        open.addActionListener(e -> app.openDashboard());
        menu.add(open);

        MenuItem show = new MenuItem("Show control panel");
        show.addActionListener(e -> app.showWindow());
        menu.add(show);

        menu.addSeparator();
        status = new MenuItem("DNS starting…");
        status.setEnabled(false);
        menu.add(status);

        startStop = new MenuItem("Start DNS");
        startStop.addActionListener(e -> app.toggleDns());
        menu.add(startStop);

        pause5 = new MenuItem("Pause 5 minutes");
        pause5.addActionListener(e -> app.pause(5 * 60));
        menu.add(pause5);

        pause30 = new MenuItem("Pause 30 minutes");
        pause30.addActionListener(e -> app.pause(30 * 60));
        menu.add(pause30);

        resume = new MenuItem("Resume blocking");
        resume.addActionListener(e -> app.resume());
        menu.add(resume);

        menu.addSeparator();
        update = new MenuItem("Update blocklists");
        update.addActionListener(e -> app.updateLists());
        menu.add(update);

        menu.addSeparator();
        MenuItem quit = new MenuItem("Quit Hole Shield");
        quit.addActionListener(e -> app.shutdown());
        menu.add(quit);

        trayIcon = new TrayIcon(Icons.image("shield-fill", 22,
                Theme.get().accent), "Hole Shield", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> app.showWindow());
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception e) {
            trayIcon = null;
        }
        update();
    }

    void update() {
        if (trayIcon == null) return;
        boolean running = app.isDnsRunning();
        boolean blocking = app.isBlocking();
        status.setLabel(running ? "DNS on port " + app.dnsPort()
                : "DNS stopped" + (app.dnsError().isEmpty() ? "" : " · " + app.dnsError()));
        startStop.setLabel(running ? "Stop DNS" : "Start DNS");
        pause5.setEnabled(blocking);
        pause30.setEnabled(blocking);
        resume.setEnabled(!blocking);
        update.setEnabled(!app.isUpdating());
        trayIcon.setToolTip("Hole Shield · " + (blocking ? "blocking" : "paused")
                + " · " + app.blockedToday() + " blocked");
    }

    void showMessage(String title, String text) {
        if (trayIcon == null) return;
        try {
            trayIcon.displayMessage(title, text, TrayIcon.MessageType.INFO);
        } catch (Exception ignored) { }
    }

    void remove() {
        if (trayIcon != null) {
            try { SystemTray.getSystemTray().remove(trayIcon); } catch (Exception ignored) { }
            trayIcon = null;
        }
    }
}
