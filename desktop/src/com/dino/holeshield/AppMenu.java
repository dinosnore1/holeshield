package com.dino.holeshield;

import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Native menu bar with the same controls as the tray menu. macOS places it in
 * the screen menu bar; Windows and Linux show it above the window content.
 * Accelerators use the platform shortcut key, so every command is reachable
 * from the keyboard.
 */
final class AppMenu {

    private final DesktopApp app;
    private final ControlPanel panel;
    private final JMenuBar bar = new JMenuBar();
    private final Map<String, JRadioButtonMenuItem> pageItems = new LinkedHashMap<>();

    private final JMenuItem startStop;
    private final JMenuItem pause5;
    private final JMenuItem pause30;
    private final JMenuItem resume;
    private final JMenuItem update;
    private final JMenuItem flush;
    private final JMenuItem reconnect;

    AppMenu(DesktopApp app, ControlPanel panel) {
        this.app = app;
        this.panel = panel;

        bar.setOpaque(true);
        bar.setBorder(javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0,
                Theme.get().border));

        startStop = item("Start DNS", KeyEvent.VK_D, app::toggleDns);
        pause5 = item("Pause for 5 minutes", 0, () -> app.pause(5 * 60));
        pause30 = item("Pause for 30 minutes", 0, () -> app.pause(30 * 60));
        resume = item("Resume blocking", 0, app::resume);
        update = item("Update blocklists", KeyEvent.VK_U, app::updateLists);
        flush = item("Flush DNS cache", KeyEvent.VK_R, true, app::flushCache);
        reconnect = item("Reconnect to companion", 0, app::reconnectCompanion);

        bar.add(fileMenu());
        bar.add(viewMenu());
        bar.add(controlsMenu());
        bar.add(helpMenu());
        update();
    }

    JMenuBar component() {
        return bar;
    }

    // ------------------------------------------------------------------- menus

    private JMenu fileMenu() {
        JMenu menu = menu("File");
        menu.add(item("Show control panel", KeyEvent.VK_0, app::showWindow));
        menu.add(item("Open web dashboard", KeyEvent.VK_O, app::openDashboard));
        menu.add(item("Open data folder", KeyEvent.VK_O, true, app::openDataFolder));
        menu.addSeparator();
        menu.add(item("Close window", KeyEvent.VK_W, panel::hideWindow));
        menu.add(item("Quit " + DesktopApp.NAME, KeyEvent.VK_Q, app::shutdown));
        return menu;
    }

    private JMenu viewMenu() {
        JMenu menu = menu("View");
        ButtonGroup group = new ButtonGroup();
        int index = 1;
        for (String name : panel.pageNames()) {
            JRadioButtonMenuItem page = new JRadioButtonMenuItem(name);
            page.setAccelerator(shortcut(KeyEvent.VK_0 + index));
            page.addActionListener(e -> panel.openPage(name));
            group.add(page);
            menu.add(page);
            pageItems.put(name, page);
            index++;
        }
        menu.addSeparator();
        menu.add(item("Refresh now", KeyEvent.VK_R, () -> {
            panel.refreshAll();
            app.setStatus("Refreshed.");
        }));
        return menu;
    }

    private JMenu controlsMenu() {
        JMenu menu = menu("Controls");
        menu.add(startStop);
        menu.addSeparator();
        menu.add(pause5);
        menu.add(pause30);
        menu.add(resume);
        menu.addSeparator();
        menu.add(update);
        menu.add(flush);
        menu.add(reconnect);
        if (!app.isCompanion()) {
            menu.addSeparator();
            menu.add(item("Restart with administrator rights", 0,
                    app::restartAsAdmin));
        }
        return menu;
    }

    private JMenu helpMenu() {
        JMenu menu = menu("Help");
        menu.add(item("Documentation", 0,
                () -> AppDirs.browse("https://dinosnore1.github.io/holeshield/")));
        menu.add(item("Release notes", 0,
                () -> AppDirs.browse("https://github.com/dinosnore1/holeshield/releases")));
        menu.addSeparator();
        menu.add(item("About " + DesktopApp.NAME, 0, this::showAbout));
        return menu;
    }

    private void showAbout() {
        String mode = app.isCompanion()
                ? "Companion console for " + app.bindSummary()
                : "Desktop DNS filter on " + app.bindSummary();
        JOptionPane.showMessageDialog(panel,
                "<html><body style='width:320px'>"
                        + "<b>" + DesktopApp.NAME + " " + DesktopApp.VERSION + "</b><br>"
                        + "Local DNS filtering for Windows, Linux and macOS.<br><br>"
                        + mode + "<br>"
                        + "Data: " + AppDirs.dataDir().getAbsolutePath()
                        + "</body></html>",
                "About " + DesktopApp.NAME, JOptionPane.INFORMATION_MESSAGE);
    }

    // ------------------------------------------------------------------ state

    /** Mirrors the resolver state so menu labels and enablement stay truthful. */
    void update() {
        boolean companion = app.isCompanion();
        boolean connected = !companion || app.companionConnected();
        boolean blocking = app.isBlocking();
        boolean applying = app.isApplying();

        if (companion) {
            startStop.setText(blocking ? "Pause blocking" : "Resume blocking");
            startStop.setEnabled(connected && !applying);
            pause5.setEnabled(connected && blocking);
            pause30.setEnabled(connected && blocking);
            // The first item already resumes blocking in companion mode.
            resume.setVisible(false);
            reconnect.setEnabled(true);
        } else {
            boolean running = app.isDnsRunning();
            startStop.setText(running ? "Stop DNS" : "Start DNS");
            startStop.setEnabled(!applying);
            pause5.setEnabled(running && blocking);
            pause30.setEnabled(running && blocking);
            resume.setVisible(true);
            resume.setEnabled(running && !blocking);
            reconnect.setEnabled(false);
        }
        update.setEnabled(connected && !app.isUpdating());
        flush.setEnabled(connected);
        syncPage(panel.currentPageName());
    }

    /** Selects the menu entry for a page without firing its action. */
    void syncPage(String name) {
        JRadioButtonMenuItem page = pageItems.get(name);
        if (page != null && !page.isSelected()) page.setSelected(true);
    }

    // ---------------------------------------------------------------- helpers

    private static JMenu menu(String text) {
        JMenu menu = new JMenu(text);
        menu.setFont(Theme.font(12.5f, Font.PLAIN));
        return menu;
    }

    private static JMenuItem item(String text, int key, Runnable action) {
        return item(text, key, false, action);
    }

    private static JMenuItem item(String text, int key, boolean shift,
                                  Runnable action) {
        JMenuItem item = new JMenuItem(text);
        if (key != 0) {
            item.setAccelerator(shift
                    ? KeyStroke.getKeyStroke(key, shortcutMask()
                            | InputEvent.SHIFT_DOWN_MASK)
                    : shortcut(key));
        }
        item.addActionListener(e -> action.run());
        return item;
    }

    private static KeyStroke shortcut(int key) {
        return KeyStroke.getKeyStroke(key, shortcutMask());
    }

    private static int shortcutMask() {
        return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }
}
