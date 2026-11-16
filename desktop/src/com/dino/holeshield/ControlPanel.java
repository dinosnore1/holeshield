package com.dino.holeshield;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Native Swing control panel for the desktop resolver. */
final class ControlPanel extends JFrame {

    private final DesktopApp app;
    private final DesktopPrefs prefs;
    private FilterEngine filter;
    private StatsStore stats;
    private DnsService dns;

    private final Map<String, Widgets.NavItem> navItems = new LinkedHashMap<>();
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, JComponent> placeholders = new LinkedHashMap<>();
    private final java.util.List<String> pageNames = new java.util.ArrayList<>();
    private JPanel content;
    private CardLayout cards;
    private String currentPage = "Overview";
    private boolean warming;
    private int warmGeneration;
    private boolean builtCompanion;
    private boolean builtDark;

    private JLabel statusText;
    private JLabel statusDot;
    private Widgets.PillButton startStop;
    private JLabel footerLeft;
    private JLabel footerRight;
    private JLabel versionLabel;
    private Widgets.RoundedPanel statusPill;
    private Timer timer;
    private int tick;

    private static final int CAP_MIN = 100000;
    private static final int CAP_STEP = 10000;
    private static final int CAP_MAX = 5000000;
    private static final int CAP_STEPS = (CAP_MAX - CAP_MIN) / CAP_STEP;
    private static final int CAP_NO_LIMIT = CAP_STEPS + 1;

    private static int capProgress(int cap) {
        if (cap <= 0) return CAP_NO_LIMIT;
        int step = (cap - CAP_MIN) / CAP_STEP;
        return Math.max(0, Math.min(CAP_STEPS, step));
    }

    private static int capFromProgress(int progress) {
        if (progress >= CAP_NO_LIMIT) return -1;
        return CAP_MIN + Math.max(0, Math.min(CAP_STEPS, progress)) * CAP_STEP;
    }

    private static String capLabel(int cap) {
        if (cap <= 0) return "No limit";
        if (cap >= 1000000) {
            return String.format(java.util.Locale.US, "%.2fM", cap / 1000000.0);
        }
        return (cap / 1000) + "K";
    }

    ControlPanel(DesktopApp app) {
        super(DesktopApp.NAME);
        this.app = app;
        this.prefs = app.prefs();
        this.filter = app.filter();
        this.stats = app.stats();
        this.dns = app.dns();
        // Land on the requested page so only one page is built at startup.
        this.currentPage = app.initialPage();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1000, 680));
        setSize(1180, 820);
        setLocationRelativeTo(null);
        setIconImage(Icons.image("shield-fill", 64, Theme.get().accent));
        styleWindowChrome();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (prefs.getBool("closeToTray", true)
                        && java.awt.SystemTray.isSupported()) {
                    setVisible(false);
                } else {
                    app.shutdown();
                }
            }
        });
        build();
        refreshAll();
        timer = new Timer(1500, e -> {
            if (!isVisible() || app.isApplying()) return;
            tick++;
            refreshHeader();
            Page page = pages.get(currentPage);
            if (page != null) page.refresh();
        });
        timer.start();
    }

    // ------------------------------------------------------------------- shell

    /** Space kept free above the header for the macOS traffic lights. */
    private static final int MERGED_TITLE_INSET = AppDirs.isMac() ? 32 : 13;

    /**
     * Merges the native title bar into the app header on macOS: the window
     * content paints behind the traffic lights and no title text is drawn, so
     * the top of the window reads as one continuous surface.
     */
    private void styleWindowChrome() {
        if (!AppDirs.isMac()) return;
        JRootPane pane = getRootPane();
        pane.putClientProperty("apple.awt.fullWindowContent", Boolean.TRUE);
        pane.putClientProperty("apple.awt.transparentTitleBar", Boolean.TRUE);
        pane.putClientProperty("apple.awt.windowTitleVisible", Boolean.FALSE);
    }

    private void build() {
        filter = app.filter();
        stats = app.stats();
        dns = app.dns();
        pages.clear();
        placeholders.clear();
        pageNames.clear();
        navItems.clear();
        warming = false;
        warmGeneration++;
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.get().bg);
        setContentPane(root);

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildNav(), BorderLayout.WEST);

        cards = new CardLayout();
        content = new JPanel(cards);
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(0, 0, 0, 0));

        String[] names = app.isCompanion()
                ? new String[]{"Companion", "Query log", "Blocklists", "Rules", "Settings"}
                : new String[]{"Overview", "Query log", "Blocklists", "Rules", "Settings"};
        for (String name : names) {
            pageNames.add(name);
            // Pages are built on demand: only the landing page is constructed
            // here, the rest are filled in after the window is on screen.
            JPanel placeholder = new JPanel();
            placeholder.setOpaque(false);
            placeholders.put(name, placeholder);
            content.add(placeholder, name);
        }
        if (!pageNames.contains(currentPage)) currentPage = pageNames.get(0);
        builtCompanion = app.isCompanion();
        builtDark = Theme.get().dark;
        root.add(content, BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        selectPage(currentPage);
    }

    private Page createPage(String name) {
        if (app.isCompanion()) {
            switch (name) {
                case "Companion": return new CompanionPage();
                case "Query log": return new CompanionQueriesPage();
                case "Blocklists": return new CompanionListsPage();
                case "Rules": return new CompanionRulesPage();
                case "Settings": return new SettingsPage();
                default: return null;
            }
        }
        switch (name) {
            case "Companion": return new CompanionPage();
            case "Overview": return new OverviewPage();
            case "Query log": return new QueriesPage();
            case "Blocklists": return new ListsPage();
            case "Rules": return new RulesPage();
            case "Settings": return new SettingsPage();
            default: return null;
        }
    }

    /** Builds a page the first time it is needed and swaps it in. */
    private Page ensurePage(String name) {
        Page page = pages.get(name);
        if (page != null) return page;
        page = createPage(name);
        if (page == null) return null;
        JComponent placeholder = placeholders.remove(name);
        if (placeholder != null) content.remove(placeholder);
        pages.put(name, page);
        content.add(page.scroll(), name);
        content.revalidate();
        content.repaint();
        return page;
    }

    /**
     * Builds the pages that were skipped at startup, one per event-loop turn,
     * so the window appears immediately and never blocks for long.
     */
    void warmPages() {
        if (warming || !isShowing()) return;
        warming = true;
        warmNext(new java.util.ArrayList<>(pageNames), warmGeneration);
    }

    private void warmNext(java.util.List<String> queue, int generation) {
        if (generation != warmGeneration) return;
        if (queue.isEmpty()) {
            warming = false;
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (generation != warmGeneration) return;
            String name = queue.remove(0);
            if (!pages.containsKey(name)) ensurePage(name);
            warmNext(queue, generation);
        });
    }

    private JPanel buildHeader() {
        Theme t = Theme.get();
        JPanel header = new JPanel(new BorderLayout(18, 0));
        header.setBackground(t.surface);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, t.border),
                new EmptyBorder(MERGED_TITLE_INSET, 24, 13, 24)));

        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        brand.setOpaque(false);
        Widgets.RoundedPanel mark = new Widgets.RoundedPanel(new BorderLayout());
        mark.setFill(t.accent);
        mark.setBorderColor(t.accent);
        mark.setRadius(13);
        mark.setPreferredSize(new Dimension(40, 40));
        JLabel icon = new JLabel(Icons.icon("shield-check", 24, 4));
        icon.setHorizontalAlignment(SwingConstants.CENTER);
        mark.add(icon, BorderLayout.CENTER);
        brand.add(mark);

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setAlignmentX(Component.LEFT_ALIGNMENT);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(Widgets.label(DesktopApp.NAME, 17, Font.BOLD, 0));
        copy.add(Widgets.label(app.isCompanion() ? "Companion console"
                : "Desktop DNS filter", 11.5f, Font.PLAIN, 1));
        brand.add(copy);
        header.add(brand, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);

        statusPill = new Widgets.RoundedPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        statusPill.setRadius(18);
        statusPill.setFill(t.accentSoft);
        statusPill.setBorderColor(t.accentSoft);
        statusPill.setBorder(new EmptyBorder(7, 13, 7, 13));
        statusDot = new JLabel("●");
        statusDot.setFont(Theme.font(9));
        statusDot.setForeground(t.accent);
        statusText = Widgets.label("Starting…", 11.5f, Font.BOLD, 5);
        statusPill.add(statusDot);
        statusPill.add(statusText);
        right.add(statusPill);

        // Companion mode already offers a labelled "Open dashboard" action in
        // the console, so the header only carries the icon in local mode.
        if (!app.isCompanion()) {
            Widgets.IconButton dashboard = new Widgets.IconButton("external",
                    "Open the web dashboard", Widgets.TONAL);
            dashboard.addActionListener(e -> app.openDashboard());
            right.add(dashboard);
        }

        startStop = new Widgets.PillButton("Start DNS", "power", Widgets.PRIMARY);
        startStop.addActionListener(e -> app.toggleDns());
        right.add(startStop);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JPanel buildNav() {
        Theme t = Theme.get();
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(t.bg);
        wrapper.setBorder(new EmptyBorder(16, 18, 16, 6));
        wrapper.setPreferredSize(new Dimension(224, 100));

        Widgets.RoundedPanel nav = new Widgets.RoundedPanel(new BorderLayout());
        nav.setShadow(true);
        nav.setBorder(new EmptyBorder(14, 10, 12, 10));
        JPanel items = new JPanel();
        items.setOpaque(false);
        items.setLayout(new BoxLayout(items, BoxLayout.Y_AXIS));
        items.add(Widgets.section("WORKSPACE"));
        String[][] entries = app.isCompanion()
                ? new String[][]{
                        {"Companion", "server"},
                        {"Query log", "list"},
                        {"Blocklists", "shield"},
                        {"Rules", "rules"},
                        {"Settings", "settings"}}
                : new String[][]{
                        {"Overview", "dashboard"},
                        {"Query log", "list"},
                        {"Blocklists", "shield"},
                        {"Rules", "rules"},
                        {"Settings", "settings"}};
        for (String[] entry : entries) {
            Widgets.NavItem item = new Widgets.NavItem(entry[0], entry[1]);
            item.setOnClick(() -> selectPage(entry[0]));
            navItems.put(entry[0], item);
            items.add(item);
            items.add(Box.createVerticalStrut(4));
        }
        items.add(Box.createVerticalGlue());
        nav.add(items, BorderLayout.CENTER);

        JPanel foot = new JPanel();
        foot.setOpaque(false);
        foot.setLayout(new BoxLayout(foot, BoxLayout.Y_AXIS));
        foot.setBorder(new EmptyBorder(12, 4, 2, 4));
        foot.add(Widgets.divider());
        foot.add(Box.createVerticalStrut(8));
        versionLabel = Widgets.label("Version " + DesktopApp.VERSION, 10.5f,
                Font.PLAIN, 1);
        foot.add(versionLabel);
        foot.add(Widgets.label(app.isCompanion() ? "Remote dashboard"
                : "Local DNS · no cloud", 10.5f, Font.PLAIN, 2));
        nav.add(foot, BorderLayout.SOUTH);
        wrapper.add(nav, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel buildFooter() {
        Theme t = Theme.get();
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setBackground(t.surface);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, t.border),
                new EmptyBorder(8, 24, 8, 24)));
        footerLeft = Widgets.label("Starting…", 11, Font.PLAIN, 1);
        footerRight = Widgets.label("", 11, Font.PLAIN, 2);
        footer.add(footerLeft, BorderLayout.WEST);
        footer.add(footerRight, BorderLayout.EAST);
        return footer;
    }

    private void selectPage(String name) {
        if (!pageNames.contains(name)) return;
        ensurePage(name);
        currentPage = name;
        cards.show(content, name);
        for (Map.Entry<String, Widgets.NavItem> entry : navItems.entrySet()) {
            entry.getValue().setSelected(entry.getKey().equals(name));
        }
        Page page = pages.get(name);
        if (page != null) {
            java.awt.Component view = page.scroll().getViewport().getView();
            if (view instanceof VerticalPanel) ((VerticalPanel) view).playEnter();
            page.refresh();
        }
    }

    // ----------------------------------------------------------------- refresh

    void refreshHeader() {
        Theme t = Theme.get();
        if (app.isCompanion()) {
            boolean connected = app.companionConnected();
            boolean healthy = connected && app.companionError().isEmpty();
            boolean blocking = app.isBlocking();
            if (startStop != null) {
                startStop.setText(blocking ? "Pause blocking" : "Resume blocking");
                startStop.setEnabled(connected);
            }
            if (statusPill != null) {
                statusPill.setFill(healthy ? t.accentSoft : t.dangerSoft);
                statusPill.setBorderColor(healthy ? t.accentSoft : t.dangerSoft);
            }
            if (statusDot != null) {
                statusDot.setForeground(!healthy ? t.danger
                        : blocking ? t.accent : t.warn);
            }
            if (statusText != null) {
                statusText.setText(!connected ? "Companion offline"
                        : !healthy ? "Companion error"
                        : blocking ? "Companion connected" : "Remote blocking paused");
            }
            if (footerRight != null) {
                footerRight.setText("Remote " + app.bindSummary()
                        + "   ·   Companion mode   ·   v" + DesktopApp.VERSION);
            }
            if (versionLabel != null) {
                versionLabel.setText("Version " + DesktopApp.VERSION);
            }
            return;
        }
        boolean running = app.isDnsRunning();
        boolean blocking = app.isBlocking();
        if (startStop != null) {
            startStop.setText(running ? "Stop DNS" : "Start DNS");
        }
        if (statusPill != null) {
            statusPill.setFill(blocking ? t.accentSoft : t.dangerSoft);
            statusPill.setBorderColor(blocking ? t.accentSoft : t.dangerSoft);
        }
        if (statusDot != null) {
            statusDot.setForeground(!running ? t.danger : blocking ? t.accent : t.warn);
        }
        if (statusText != null) {
            String text = !running ? "DNS stopped"
                    : blocking ? "Blocking enabled" : "Blocking paused";
            statusText.setText(text);
        }
        if (footerRight != null) {
            footerRight.setText("DNS " + app.bindSummary() + "   ·   Dashboard "
                    + app.webPort() + "   ·   v" + DesktopApp.VERSION);
        }
        if (versionLabel != null) {
            versionLabel.setText("Version " + DesktopApp.VERSION);
        }
        Page page = pages.get(currentPage);
        if (page instanceof OverviewPage) ((OverviewPage) page).refreshHeaderOnly();
    }

    void refreshAll() {
        refreshHeader();
        Page page = pages.get(currentPage);
        if (page != null) page.refresh();
        if (footerLeft != null && footerLeft.getText().isEmpty()) {
            footerLeft.setText(app.isCompanion() ? app.companionStatus()
                    : app.isDnsRunning()
                    ? "DNS listening on " + app.bindSummary()
                    : app.dnsError());
        }
    }

    void setStatus(String text) {
        if (footerLeft != null) footerLeft.setText(text == null ? "" : text);
    }

    void openPage(String name) {
        if (name != null && pageNames.contains(name)) selectPage(name);
    }

    void rebuild() {
        String page = currentPage;
        Dimension size = getSize();
        getContentPane().removeAll();
        build();
        setSize(size);
        selectPage(page);
        refreshAll();
        revalidate();
        repaint();
        warmPages();
    }

    /**
     * Updates the window after settings are applied. A rebuild is only needed
     * when the window structure or the palette changed; otherwise refreshing
     * keeps the current page, scroll position and selection intact.
     */
    void applySettingsChanged() {
        if (builtCompanion != app.isCompanion() || builtDark != Theme.get().dark) {
            rebuild();
            return;
        }
        refreshAll();
        revalidate();
        repaint();
    }

    // ------------------------------------------------------------------- pages

    private abstract static class Page {
        abstract JScrollPane scroll();
        abstract void refresh();
    }

    private static final class VerticalPanel extends JPanel implements Scrollable {
        /** Distance the page starts below its resting slot when it enters. */
        private static final double ENTER_DISTANCE = 12;
        private double enterOffset;
        private Motion.Tween enterTween;

        VerticalPanel() {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(new EmptyBorder(22, 24, 28, 24));
        }

        /** Eases the page content up into place after a navigation change. */
        void playEnter() {
            if (enterTween != null) enterTween.stop();
            if (!isShowing()) {
                enterOffset = 0;
                repaint();
                return;
            }
            enterOffset = ENTER_DISTANCE;
            enterTween = Motion.tween(ENTER_DISTANCE, 0, 220, Motion.EASE_OUT,
                    value -> { enterOffset = value; repaint(); });
        }

        @Override
        protected void paintChildren(Graphics g) {
            if (enterOffset < 0.05) {
                super.paintChildren(g);
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.translate(0, enterOffset);
            super.paintChildren(g2);
            g2.dispose();
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect,
                                              int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect,
                                               int orientation, int direction) {
            return Math.max(100, visibleRect.height - 40);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static JScrollPane pageScroll(VerticalPanel panel) {
        JScrollPane scroll = Widgets.scroll(panel);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    private static void add(VerticalPanel page, JComponent card, int height, int gap) {
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (height > 0) {
            card.setPreferredSize(new Dimension(100, height));
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
            card.setMinimumSize(new Dimension(100, height));
        } else {
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                    card.getPreferredSize().height));
        }
        page.add(card);
        if (gap > 0) page.add(Box.createVerticalStrut(gap));
    }

    private static Widgets.RoundedPanel card(int top, int left, int bottom, int right) {
        Widgets.RoundedPanel panel = new Widgets.RoundedPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(top, left, bottom, right));
        return panel;
    }

    /** Pins a fully built card to its natural height so its rows cannot be clipped. */
    private static void fitCard(Widgets.RoundedPanel card) {
        int height = card.getPreferredSize().height;
        card.setPreferredSize(new Dimension(100, height));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        relayout(card);
    }

    /** Revalidates after the current event so a grown card is never clipped. */
    private static void relayout(JComponent card) {
        SwingUtilities.invokeLater(() -> {
            card.revalidate();
            if (card.getParent() != null) card.getParent().revalidate();
            card.repaint();
        });
    }

    // ------------------------------------------------------------------ overview

    private final class OverviewPage extends Page {
        private final JScrollPane scroll;
        private final JLabel heroTitle = Widgets.label("Hole Shield", 24, Font.BOLD, 0);
        private final JLabel heroSub = Widgets.label("", 12, Font.PLAIN, 1);
        private final JLabel upstreamChip = Widgets.label("", 10.5f, Font.BOLD, 1);
        private final JLabel portChip = Widgets.label("", 10.5f, Font.BOLD, 1);
        private final JLabel scheduleChip = Widgets.label("Always on", 10.5f, Font.BOLD, 1);
        private final Widgets.PillButton pauseButton =
                new Widgets.PillButton("Pause", "pause", Widgets.TONAL);
        private final Widgets.StatCard totalCard =
                new Widgets.StatCard("Queries", "chart", 0);
        private final Widgets.StatCard blockedCard =
                new Widgets.StatCard("Blocked", "shield-check", 1);
        private final Widgets.StatCard rateCard =
                new Widgets.StatCard("Blocked rate", "filter", 2);
        private final Widgets.StatCard gravityCard =
                new Widgets.StatCard("Domains blocked", "server", 0);
        private final Widgets.ChartPanel chart = new Widgets.ChartPanel();
        private final JLabel chartTitle = Widgets.label("Query activity", 15, Font.BOLD, 0);
        private final JLabel chartSummary = Widgets.label("", 11.5f, Font.PLAIN, 1);
        private final Widgets.BarList topBlocked = new Widgets.BarList();
        private final Widgets.BarList topClients = new Widgets.BarList();
        private final Widgets.BarList types = new Widgets.BarList();
        private final Widgets.BarList categories = new Widgets.BarList();
        private JPanel bottomRow;
        private Widgets.RoundedPanel categoryPanel;
        private String range = "24h";
        private int chartTick;

        OverviewPage() {
            VerticalPanel page = new VerticalPanel();
            page.add(hero());
            page.add(Box.createVerticalStrut(14));
            page.add(stats());
            page.add(Box.createVerticalStrut(14));
            page.add(chartCard());
            page.add(Box.createVerticalStrut(14));
            page.add(bottom());
            page.add(Box.createVerticalStrut(14));
            page.add(categoryCard());
            scroll = pageScroll(page);
        }

        private JComponent hero() {
            Widgets.RoundedPanel card = card(22, 24, 22, 32);
            card.setShadow(true);
            JPanel left = new JPanel();
            left.setOpaque(false);
            left.setAlignmentX(Component.LEFT_ALIGNMENT);
            left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
            heroTitle.setHorizontalAlignment(SwingConstants.LEFT);
            heroSub.setHorizontalAlignment(SwingConstants.LEFT);
            left.add(flowLeft(heroTitle));
            heroSub.setBorder(new EmptyBorder(4, 0, 12, 0));
            left.add(flowLeft(heroSub));
            JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
            chips.setOpaque(false);
            chips.add(chip("lock", upstreamChip));
            chips.add(chip("server", portChip));
            chips.add(chip("clock", scheduleChip));
            left.add(chips);
            card.add(left, BorderLayout.CENTER);

            JPanel actions = new JPanel();
            actions.setOpaque(false);
            actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
            actions.setBorder(new EmptyBorder(4, 0, 0, 0));
            pauseButton.setAlignmentX(Component.RIGHT_ALIGNMENT);
            pauseButton.addActionListener(e -> pauseAction());
            actions.add(pauseButton);
            card.add(actions, BorderLayout.EAST);
            return card;
        }

        private JComponent flowLeft(JComponent component) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            row.setOpaque(false);
            row.add(component);
            return row;
        }

        private JComponent chip(String icon, JLabel label) {
            Widgets.RoundedPanel chip = new Widgets.RoundedPanel(
                    new FlowLayout(FlowLayout.LEFT, 6, 0));
            chip.setRadius(14);
            chip.setFill(Theme.get().surfaceAlt);
            chip.setBorderColor(Theme.get().border);
            chip.setBorder(new EmptyBorder(5, 10, 5, 10));
            chip.add(new JLabel(Icons.icon(icon, 13, 2)));
            chip.add(label);
            return chip;
        }

        private JComponent stats() {
            JPanel row = new JPanel(new GridLayout(1, 4, 14, 0));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(totalCard);
            row.add(blockedCard);
            row.add(rateCard);
            row.add(gravityCard);
            row.setPreferredSize(new Dimension(100, 112));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 112));
            return row;
        }

        private JComponent chartCard() {
            Widgets.RoundedPanel card = card(18, 20, 14, 20);
            JPanel head = new JPanel(new BorderLayout(10, 0));
            head.setOpaque(false);
            JPanel copy = new JPanel();
            copy.setOpaque(false);
            copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
            copy.add(chartTitle);
            chartSummary.setBorder(new EmptyBorder(2, 0, 0, 0));
            copy.add(chartSummary);
            head.add(copy, BorderLayout.CENTER);

            Widgets.SegmentedControl ranges = new Widgets.SegmentedControl(
                    new String[]{"Live", "24h", "Week", "Month"}, 1);
            ranges.setOnChange(index -> {
                range = index == 0 ? "live" : index == 1 ? "24h"
                        : index == 2 ? "week" : "month";
                refreshChart();
            });
            head.add(ranges, BorderLayout.EAST);
            card.add(head, BorderLayout.NORTH);
            chart.setBorder(new EmptyBorder(8, 0, 0, 0));
            card.add(chart, BorderLayout.CENTER);
            card.setPreferredSize(new Dimension(100, 300));
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 320));
            return card;
        }

        private JComponent bottom() {
            JPanel row = new JPanel(new GridLayout(1, 3, 14, 0));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(listCard("Top blocked", "Most blocked lookups", topBlocked));
            row.add(listCard("Top clients", "Devices using this resolver", topClients));
            row.add(listCard("Query types", "A, AAAA, HTTPS and more", types));
            row.setPreferredSize(new Dimension(100, 236));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 236));
            bottomRow = row;
            return row;
        }

        private JComponent listCard(String title, String subtitle,
                                    Widgets.BarList list) {
            Widgets.RoundedPanel card = card(18, 20, 14, 20);
            card.add(Widgets.header(title, subtitle, null), BorderLayout.NORTH);
            list.setBorder(new EmptyBorder(10, 0, 0, 0));
            card.add(list, BorderLayout.CENTER);
            return card;
        }

        private JComponent categoryCard() {
            Widgets.RoundedPanel card = card(18, 20, 16, 20);
            card.add(Widgets.header("Blocked by category",
                    "Categories matched by gravity and custom rules", null),
                    BorderLayout.NORTH);
            categories.setBorder(new EmptyBorder(10, 0, 0, 0));
            card.add(categories, BorderLayout.CENTER);
            fitCard(card);
            categoryPanel = card;
            return card;
        }

        private void pauseAction() {
            if (!app.isBlocking()) {
                app.resume();
                return;
            }
            JPopupMenu menu = new JPopupMenu();
            addPause(menu, "Pause 5 minutes", 5 * 60);
            addPause(menu, "Pause 30 minutes", 30 * 60);
            addPause(menu, "Pause 1 hour", 60 * 60);
            addPause(menu, "Pause until resumed", -1);
            menu.show(pauseButton, 0, pauseButton.getHeight() + 4);
        }

        private void addPause(JPopupMenu menu, String label, long seconds) {
            JMenuItem item = new JMenuItem(label);
            item.addActionListener(e -> {
                if (seconds < 0) app.pause(0);
                else app.pause(seconds);
            });
            menu.add(item);
        }

        void refreshHeaderOnly() {
            boolean running = app.isDnsRunning();
            boolean blocking = app.isBlocking();
            if (!running) {
                heroTitle.setText("DNS is stopped");
                heroSub.setText("Start the resolver to filter lookups on this network.");
            } else if (blocking) {
                heroTitle.setText("Hole Shield is blocking");
                heroSub.setText("Every lookup is checked against gravity and your rules.");
            } else {
                heroTitle.setText("Blocking is paused");
                heroSub.setText("DNS still resolves, but filtering is temporarily off.");
            }
            pauseButton.setText(blocking ? "Pause" : "Resume");
            pauseButton.setEnabled(running);
            String warning = app.upstreamWarning();
            upstreamChip.setText(warning.isEmpty() ? prefs.upstreamLabel()
                    : "Plain DNS fallback");
            upstreamChip.setToolTipText(warning.isEmpty() ? null : warning);
            portChip.setText("Port " + app.dnsPort());
            scheduleChip.setText("Always on");
        }

        @Override
        JScrollPane scroll() {
            return scroll;
        }

        @Override
        void refresh() {
            refreshHeaderOnly();
            totalCard.setValue(DesktopApp.formatNumber(stats.queries()));
            blockedCard.setValue(DesktopApp.formatNumber(stats.blocked()));
            rateCard.setValue(String.format(Locale.US, "%.1f%%", stats.rate()));
            gravityCard.setValue(DesktopApp.formatNumber(filter.blockedCount()));
            totalCard.setHint(stats.uniqueDomains() + " domains");
            blockedCard.setHint(stats.cached() + " cached");
            rateCard.setHint(stats.errors() + " errors");
            gravityCard.setHint(filter.listUrls().size() + " sources");
            topBlocked.setData(stats.topDomains(true, 6), "No blocked lookups yet.");
            topBlocked.setDanger(true);
            List<StatsStore.Top> clients = new ArrayList<>();
            for (StatsStore.Top top : stats.topClients(6)) {
                String name = DnsService.clientName(top.key);
                clients.add(new StatsStore.Top(name.equals(top.key)
                        ? top.key : name + " · " + top.key, top.count));
            }
            topClients.setData(clients, "No clients yet.");
            types.setData(stats.queryTypes(), "No queries yet.");
            categories.setData(stats.categoryCounts(), "No blocked lookups yet.");
            resizeListCards();
            if (++chartTick % 2 == 0) refreshChart();
        }

        /** Keeps the bar cards tall enough for every row they now show. */
        private void resizeListCards() {
            if (bottomRow != null) {
                int height = 236;
                for (Component child : bottomRow.getComponents()) {
                    if (child instanceof Widgets.RoundedPanel) {
                        Widgets.RoundedPanel card = (Widgets.RoundedPanel) child;
                        fitCard(card);
                        height = Math.max(height, card.getPreferredSize().height);
                    }
                }
                bottomRow.setPreferredSize(new Dimension(100, height));
                bottomRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
                bottomRow.revalidate();
                if (bottomRow.getParent() != null) bottomRow.getParent().revalidate();
            }
            if (categoryPanel != null) fitCard(categoryPanel);
        }

        private void refreshChart() {
            List<StatsStore.Bucket> data = stats.chart(range);
            chart.setData(data, range);
            long total = 0;
            long blocked = 0;
            for (StatsStore.Bucket bucket : data) {
                total += bucket.queries;
                blocked += bucket.blocked;
            }
            chartTitle.setText(range.equals("live") ? "Live · last 30 minutes"
                    : range.equals("week") ? "Last 7 days"
                    : range.equals("month") ? "Last 30 days" : "Last 24 hours");
            chartSummary.setText(DesktopApp.formatNumber(total) + " queries · "
                    + DesktopApp.formatNumber(blocked) + " blocked · "
                    + String.format(Locale.US, "%.1f%%",
                    total == 0 ? 0 : blocked * 100.0 / total));
        }
    }

    // ---------------------------------------------------------------- companion

    private final class CompanionPage extends Page {
        private final JScrollPane scroll;
        private final JLabel heroTitle = Widgets.label("Companion console", 22, Font.BOLD, 0);
        private final JLabel heroSub = Widgets.label("", 12, Font.PLAIN, 1);
        private final JLabel serverChip = Widgets.label("", 10.5f, Font.BOLD, 1);
        private final JLabel accountChip = Widgets.label("", 10.5f, Font.BOLD, 1);
        private final JLabel modeChip = Widgets.label("Remote dashboard", 10.5f, Font.BOLD, 1);
        private final Widgets.StatCard totalCard =
                new Widgets.StatCard("Queries", "chart", 0);
        private final Widgets.StatCard blockedCard =
                new Widgets.StatCard("Blocked", "shield-check", 1);
        private final Widgets.StatCard rateCard =
                new Widgets.StatCard("Blocked rate", "filter", 2);
        private final Widgets.StatCard gravityCard =
                new Widgets.StatCard("Domains blocked", "server", 0);
        private final Widgets.PillButton blockingButton =
                new Widgets.PillButton("Pause blocking", "pause", Widgets.TONAL);
        private final Widgets.PillButton updateButton =
                new Widgets.PillButton("Update blocklists", "refresh", Widgets.OUTLINE);
        private final Widgets.BarList categories = new Widgets.BarList();
        private Widgets.RoundedPanel companionCategoryPanel;
        private final JLabel details = Widgets.label("", 12, Font.PLAIN, 1);
        private final JLabel state = Widgets.label("", 11.5f, Font.PLAIN, 2);
        private final Widgets.ChartPanel chart = new Widgets.ChartPanel();
        private final JLabel chartSummary = Widgets.label("", 11.5f, Font.PLAIN, 1);
        private final Widgets.BarList topBlocked = new Widgets.BarList();
        private final Widgets.BarList topClients = new Widgets.BarList();
        private final Widgets.BarList types = new Widgets.BarList();
        private final Widgets.BarList upstreams = new Widgets.BarList();
        private final JLabel coverage = Widgets.label("", 11.5f, Font.PLAIN, 1);
        private Widgets.RoundedPanel coveragePanel;
        private JPanel topsRowPanel;

        CompanionPage() {
            VerticalPanel page = new VerticalPanel();
            page.add(hero());
            page.add(Box.createVerticalStrut(14));
            page.add(stats());
            page.add(Box.createVerticalStrut(14));
            page.add(controls());
            page.add(Box.createVerticalStrut(14));
            page.add(chartCard());
            page.add(Box.createVerticalStrut(14));
            page.add(topsRow());
            page.add(Box.createVerticalStrut(14));
            page.add(categoryCard());
            page.add(Box.createVerticalStrut(14));
            page.add(coverageCard());
            page.add(Box.createVerticalStrut(14));
            page.add(detailsCard());
            scroll = pageScroll(page);
        }

        private JComponent chartCard() {
            Widgets.RoundedPanel card = card(18, 20, 14, 20);
            JPanel head = new JPanel(new BorderLayout(10, 0));
            head.setOpaque(false);
            JPanel copy = new JPanel();
            copy.setOpaque(false);
            copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
            copy.add(Widgets.label("Query activity", 15, Font.BOLD, 0));
            chartSummary.setBorder(new EmptyBorder(2, 0, 0, 0));
            copy.add(chartSummary);
            head.add(copy, BorderLayout.CENTER);
            head.add(Widgets.label("Last 24 hours", 11.5f, Font.PLAIN, 1),
                    BorderLayout.EAST);
            card.add(head, BorderLayout.NORTH);
            card.add(chart, BorderLayout.CENTER);
            card.setPreferredSize(new Dimension(100, 330));
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 340));
            return card;
        }

        private JComponent topsRow() {
            JPanel row = new JPanel(new GridLayout(1, 4, 14, 0));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(barCard("Top blocked", "Most blocked lookups", topBlocked, true));
            row.add(barCard("Top clients", "Devices using the resolver", topClients, false));
            row.add(barCard("Query types", "A, AAAA, HTTPS and more", types, false));
            row.add(barCard("Upstreams", "Where lookups were answered", upstreams, false));
            row.setPreferredSize(new Dimension(100, 236));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 236));
            topsRowPanel = row;
            return row;
        }

        /** Grows the four top cards to fit their longest list, so no row is cut off. */
        private void fitTops() {
            if (topsRowPanel == null) return;
            int tallest = 0;
            for (Widgets.BarList list : new Widgets.BarList[]{
                    topBlocked, topClients, types, upstreams}) {
                tallest = Math.max(tallest, list.getPreferredSize().height);
            }
            int height = Math.max(236, tallest + 96);
            topsRowPanel.setPreferredSize(new Dimension(100, height));
            topsRowPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
            relayout(topsRowPanel);
        }

        private JComponent barCard(String title, String sub, Widgets.BarList list,
                                   boolean danger) {
            Widgets.RoundedPanel card = card(16, 17, 14, 17);
            card.add(Widgets.header(title, sub, null), BorderLayout.NORTH);
            list.setDanger(danger);
            list.setBorder(new EmptyBorder(8, 0, 0, 0));
            card.add(list, BorderLayout.CENTER);
            return card;
        }

        private JComponent coverageCard() {
            coveragePanel = card(16, 18, 16, 18);
            coveragePanel.add(Widgets.header("List coverage",
                    "Remote blocklist sources and their last download", null),
                    BorderLayout.NORTH);
            coverage.setBorder(new EmptyBorder(8, 2, 0, 0));
            coverage.setVerticalAlignment(SwingConstants.TOP);
            coveragePanel.add(coverage, BorderLayout.CENTER);
            coveragePanel.setPreferredSize(new Dimension(100, 150));
            coveragePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 320));
            return coveragePanel;
        }

        private JComponent hero() {
            Widgets.RoundedPanel card = card(22, 24, 22, 32);
            card.setShadow(true);
            JPanel left = new JPanel();
            left.setOpaque(false);
            left.setAlignmentX(Component.LEFT_ALIGNMENT);
            left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
            left.add(Box.createVerticalGlue());
            heroTitle.setHorizontalAlignment(SwingConstants.LEFT);
            heroSub.setHorizontalAlignment(SwingConstants.LEFT);
            left.add(flowLeft(heroTitle));
            heroSub.setBorder(new EmptyBorder(4, 0, 12, 0));
            left.add(flowLeft(heroSub));
            JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
            chips.setOpaque(false);
            chips.add(chip("server", serverChip));
            chips.add(chip("lock", accountChip));
            chips.add(chip("external", modeChip));
            left.add(chips);
            left.add(Box.createVerticalGlue());
            card.add(left, BorderLayout.CENTER);

            JPanel actions = new JPanel();
            actions.setOpaque(false);
            actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
            actions.setBorder(new EmptyBorder(4, 0, 0, 0));
            Widgets.PillButton open = new Widgets.PillButton("Open dashboard",
                    "external", Widgets.PRIMARY);
            Dimension openSize = open.getPreferredSize();
            openSize.width += 24;
            open.setPreferredSize(openSize);
            open.setMinimumSize(openSize);
            open.setMaximumSize(openSize);
            open.setAlignmentX(Component.RIGHT_ALIGNMENT);
            open.addActionListener(e -> app.openDashboard());
            actions.add(Box.createVerticalGlue());
            actions.add(open);
            actions.add(Box.createVerticalStrut(8));
            Widgets.PillButton reconnect = new Widgets.PillButton("Reconnect",
                    "refresh", Widgets.OUTLINE);
            Dimension reconnectSize = reconnect.getPreferredSize();
            reconnectSize.width += 24;
            reconnect.setPreferredSize(reconnectSize);
            reconnect.setMinimumSize(reconnectSize);
            reconnect.setMaximumSize(reconnectSize);
            reconnect.setAlignmentX(Component.RIGHT_ALIGNMENT);
            reconnect.addActionListener(e -> app.reconnectCompanion());
            actions.add(reconnect);
            actions.add(Box.createVerticalGlue());
            card.add(actions, BorderLayout.EAST);
            // Two stacked buttons need more height than the hero text, or the
            // top pill ends up hard against the rounded corner.
            card.setPreferredSize(new Dimension(100, 170));
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 170));
            return card;
        }

        private JComponent flowLeft(JComponent component) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            row.setOpaque(false);
            row.add(component);
            return row;
        }

        private JComponent chip(String icon, JLabel label) {
            Widgets.RoundedPanel chip = new Widgets.RoundedPanel(
                    new FlowLayout(FlowLayout.LEFT, 6, 0));
            chip.setRadius(14);
            chip.setFill(Theme.get().surfaceAlt);
            chip.setBorderColor(Theme.get().border);
            chip.setBorder(new EmptyBorder(5, 10, 5, 10));
            chip.add(new JLabel(Icons.icon(icon, 13, 2)));
            chip.add(label);
            return chip;
        }

        private JComponent stats() {
            JPanel row = new JPanel(new GridLayout(1, 4, 14, 0));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(totalCard);
            row.add(blockedCard);
            row.add(rateCard);
            row.add(gravityCard);
            row.setPreferredSize(new Dimension(100, 108));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 108));
            return row;
        }

        private JComponent controls() {
            Widgets.RoundedPanel card = card(17, 18, 16, 18);
            card.add(Widgets.header("Remote controls",
                    "Changes are sent to the connected system, not to this computer",
                    null), BorderLayout.NORTH);
            JPanel body = new JPanel();
            body.setOpaque(false);
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setBorder(new EmptyBorder(12, 0, 0, 0));
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            row.setOpaque(false);
            blockingButton.addActionListener(e -> {
                if (app.isBlocking()) app.pause(0);
                else app.resume();
            });
            row.add(blockingButton);
            Widgets.PillButton flush = new Widgets.PillButton("Flush DNS cache",
                    "trash", Widgets.OUTLINE);
            flush.addActionListener(e -> app.flushCache());
            row.add(flush);
            updateButton.addActionListener(e -> app.updateLists());
            row.add(updateButton);
            body.add(row);
            state.setBorder(new EmptyBorder(10, 4, 0, 0));
            body.add(state);
            card.add(body, BorderLayout.CENTER);
            card.setPreferredSize(new Dimension(100, 156));
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 176));
            return card;
        }

        private JComponent categoryCard() {
            Widgets.RoundedPanel card = card(16, 17, 14, 17);
            companionCategoryPanel = card;
            card.add(Widgets.header("Blocked by category",
                    "Category counts reported by the remote dashboard", null),
                    BorderLayout.NORTH);
            categories.setDanger(true);
            categories.setBorder(new EmptyBorder(10, 0, 0, 0));
            card.add(categories, BorderLayout.CENTER);
            card.setPreferredSize(new Dimension(100, 210));
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 230));
            return card;
        }

        private JComponent detailsCard() {
            Widgets.RoundedPanel card = card(18, 20, 18, 20);
            card.add(Widgets.header("Connected system",
                    "Live details from the remote Hole Shield", null),
                    BorderLayout.NORTH);
            details.setBorder(new EmptyBorder(10, 2, 0, 0));
            card.add(details, BorderLayout.CENTER);
            card.setPreferredSize(new Dimension(100, 176));
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 190));
            return card;
        }

        @Override
        JScrollPane scroll() {
            return scroll;
        }

        @Override
        void refresh() {
            CompanionClient client = app.companion();
            CompanionClient.Snapshot snapshot = app.companionSnapshot();
            boolean connected = app.companionConnected();
            String address = client == null ? "not configured"
                    : client.display().isEmpty() ? "not configured" : client.display();
            serverChip.setText(address);
            accountChip.setText(connected ? "signed in" : "not signed in");
            modeChip.setText("Remote dashboard");
            boolean blocking = app.isBlocking();
            blockingButton.setText(blocking ? "Pause blocking" : "Resume blocking");
            blockingButton.setEnabled(connected);
            updateButton.setEnabled(connected && !app.isUpdating());

            CompanionClient.Dashboard dash = app.companionDashboard();
            refreshMirror(dash);

            if (snapshot == null) {
                heroTitle.setText(connected ? "Companion console" : "Companion offline");
                String error = app.companionError();
                heroSub.setText(error.isEmpty()
                        ? "Connecting to the Hole Shield system on your network…"
                        : error);
                totalCard.setValue("—");
                blockedCard.setValue("—");
                rateCard.setValue("—");
                gravityCard.setValue("—");
                totalCard.setHint("");
                blockedCard.setHint("");
                rateCard.setHint("");
                gravityCard.setHint("");
                categories.setData(new ArrayList<>(), "Waiting for the remote dashboard…");
                details.setText("No summary yet.");
                state.setText(connected ? "Connected, waiting for the first summary."
                        : error.isEmpty() ? "Connecting…" : error);
                return;
            }

            heroTitle.setText(blocking ? "Companion is connected"
                    : "Remote blocking is paused");
            String liveError = app.companionError();
            heroSub.setText(liveError.isEmpty()
                    ? "This console mirrors " + address
                    + ". Local DNS and the local dashboard stay off in companion mode."
                    : liveError);
            totalCard.setValue(DesktopApp.formatNumber(snapshot.queries));
            blockedCard.setValue(DesktopApp.formatNumber(snapshot.blocked));
            rateCard.setValue(String.format(Locale.US, "%.1f%%", snapshot.rate));
            gravityCard.setValue(DesktopApp.formatNumber(snapshot.gravityDomains));
            totalCard.setHint(snapshot.uniqueDomains + " domains");
            blockedCard.setHint(snapshot.cached + " cached");
            rateCard.setHint(snapshot.errors + " errors");
            gravityCard.setHint(snapshot.categories.size() + " categories");

            List<StatsStore.Top> rows = new ArrayList<>();
            String[] order = {"ads", "malware", "trackers", "adult",
                    "piracy", "gambling", "dating", "social", "other"};
            for (String key : order) {
                Long count = snapshot.categories.get(key);
                if (count == null) continue;
                rows.add(new StatsStore.Top(categoryLabel(key),
                        (int) Math.min(Integer.MAX_VALUE, count)));
            }
            categories.setData(rows, "No category counts reported.");
            int categoryHeight = Math.max(210, categories.getPreferredSize().height + 90);
            if (companionCategoryPanel.getPreferredSize().height != categoryHeight) {
                companionCategoryPanel.setPreferredSize(new Dimension(100, categoryHeight));
                companionCategoryPanel.setMaximumSize(
                        new Dimension(Integer.MAX_VALUE, categoryHeight));
                relayout(companionCategoryPanel);
            }

            String updated = snapshot.lastUpdate > 0
                    ? relative(snapshot.lastUpdate) : "never";
            CompanionClient.Host host = dash == null ? null : dash.host;
            StringBuilder info = new StringBuilder("<html><body style='width:520px'>")
                    .append("Server: <b>").append(html(address)).append("</b><br>")
                    .append("Software: ").append(html(snapshot.name.isEmpty()
                    ? "Hole Shield" : snapshot.name))
                    .append(snapshot.version.isEmpty() ? "" : " " + html(snapshot.version))
                    .append("<br>")
                    .append("Clients seen: ").append(snapshot.clients)
                    .append(" · Gravity updated: ").append(updated).append("<br>")
                    .append("Schedule: ").append(snapshot.scheduleEnabled
                    ? html(snapshot.scheduleLabel) : "Always on")
                    .append(" · Blocking: ").append(blocking ? "enabled" : "paused");
            if (host != null && !host.hostname.isEmpty()) {
                info.append("<br>Host: ").append(html(host.hostname))
                        .append(host.ip.isEmpty() ? "" : " · " + html(host.ip))
                        .append(" · web ").append(host.webPort)
                        .append(" · DNS ").append(host.dnsPort)
                        .append(host.dnsRunning ? " (running)" : " (stopped)");
                if (!host.dnsNote.isEmpty()) {
                    info.append("<br>").append(html(host.dnsNote));
                }
            }
            details.setText(info.append("</body></html>").toString());
            state.setText(app.isUpdating() ? "Remote blocklist update in progress…"
                    : !liveError.isEmpty() ? liveError
                    : "Live view · refreshes every two seconds.");
        }

        private String categoryLabel(String key) {
            if (key.isEmpty()) return "Other";
            return Character.toUpperCase(key.charAt(0)) + key.substring(1);
        }

        /** Draws the chart, top lists, coverage and host rows of the mirror. */
        private void refreshMirror(CompanionClient.Dashboard dash) {
            List<StatsStore.Bucket> points = new ArrayList<>();
            List<StatsStore.Top> blockedRows = new ArrayList<>();
            List<StatsStore.Top> clientRows = new ArrayList<>();
            List<StatsStore.Top> typeRows = new ArrayList<>();
            List<StatsStore.Top> upstreamRows = new ArrayList<>();
            long total = 0;
            long blockedCount = 0;
            if (dash != null) {
                for (CompanionClient.Point point : dash.history) {
                    points.add(new StatsStore.Bucket(point.time, point.queries, point.blocked));
                    total += point.queries;
                    blockedCount += point.blocked;
                }
                blockedRows = tops(dash.topBlocked);
                clientRows = tops(dash.topClients);
                typeRows = tops(dash.types);
                upstreamRows = tops(dash.upstreams);
            }
            chart.setData(points, "24h");
            chartSummary.setText(DesktopApp.formatNumber(total) + " queries · "
                    + DesktopApp.formatNumber(blockedCount) + " blocked · "
                    + String.format(Locale.US, "%.1f%%",
                    total == 0 ? 0 : blockedCount * 100.0 / total));
            topBlocked.setData(blockedRows, "No blocked lookups yet.");
            topClients.setData(clientRows, "No clients yet.");
            types.setData(typeRows, "No queries yet.");
            upstreams.setData(upstreamRows, "No upstream data yet.");
            coverage.setText(dash == null
                    ? "Waiting for the remote dashboard…"
                    : coverageHtml(dash.sources));
            fitTops();
            if (coveragePanel != null) {
                int lines = dash == null ? 1 : Math.min(8, dash.sources.size())
                        + (dash.sources.size() > 8 ? 1 : 0);
                int height = 104 + Math.max(1, lines) * 18;
                coveragePanel.setPreferredSize(new Dimension(100, height));
                coveragePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
                relayout(coveragePanel);
            }
        }

        private List<StatsStore.Top> tops(List<CompanionClient.Top> source) {
            List<StatsStore.Top> rows = new ArrayList<>();
            for (CompanionClient.Top top : source) {
                rows.add(new StatsStore.Top(top.key,
                        (int) Math.min(Integer.MAX_VALUE, top.count)));
            }
            return rows;
        }

        private String coverageHtml(List<CompanionClient.Source> sources) {
            if (sources.isEmpty()) return "No blocklist sources reported.";
            StringBuilder out = new StringBuilder("<html><body style='width:560px'>");
            int shown = 0;
            for (CompanionClient.Source source : sources) {
                if (shown++ >= 8) break;
                String host = source.address.replaceFirst("^https?://", "");
                if (host.length() > 58) host = host.substring(0, 57) + "…";
                out.append(source.ok ? "<span style='color:#3fd391'>✓</span> "
                        : "<span style='color:#ff6b6b'>✕</span> ").append(html(host));
                if (source.count > 0) {
                    out.append(" · ").append(DesktopApp.formatNumber(source.count));
                }
                if (!source.ok && !source.error.isEmpty()) {
                    out.append(" · ").append(html(source.error));
                }
                out.append("<br>");
            }
            if (sources.size() > 8) {
                out.append("+ ").append(sources.size() - 8).append(" more sources");
            }
            return out.append("</body></html>").toString();
        }

        private String html(String value) {
            return value == null ? "" : value.replace("&", "&amp;")
                    .replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    // ------------------------------------------------------ companion queries

    /** Remote query log, mirroring the web dashboard's query table. */
    private final class CompanionQueriesPage extends Page {
        private final JScrollPane scroll;
        private final JTextField search = Widgets.field("Search domain or client", 24);
        private final JComboBox<String> filterCombo = Widgets.combo(
                new String[]{"All queries", "Blocked only", "Allowed only"}, "All queries");
        private final JLabel count = Widgets.label("", 11.5f, Font.PLAIN, 1);
        private final CompanionQueryModel model = new CompanionQueryModel();
        private int filterMode;
        private final JTable table = new JTable(model);
        private final Timer debounce;

        CompanionQueriesPage() {
            VerticalPanel page = new VerticalPanel();
            Widgets.RoundedPanel toolbar = card(14, 16, 14, 16);
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            left.setOpaque(false);
            left.add(search);
            left.add(filterCombo);
            Widgets.PillButton refresh = new Widgets.PillButton("Refresh", "refresh",
                    Widgets.OUTLINE);
            refresh.addActionListener(e -> refresh());
            left.add(refresh);
            toolbar.add(left, BorderLayout.WEST);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            right.setOpaque(false);
            right.add(count);
            Widgets.PillButton export = new Widgets.PillButton("Export", "download",
                    Widgets.OUTLINE);
            export.addActionListener(e -> export());
            right.add(export);
            toolbar.add(right, BorderLayout.EAST);
            page.add(toolbar);
            page.add(Box.createVerticalStrut(14));

            Widgets.RoundedPanel tableCard = card(8, 10, 8, 10);
            tableCard.setShadow(true);
            Widgets.styleTable(table);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
            int[] widths = {82, 128, 250, 84, 62, 112, 132, 58, 160};
            for (int i = 0; i < table.getColumnCount(); i++) {
                table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
                table.getColumnModel().getColumn(i).setCellRenderer(new QueryRenderer(i));
            }
            JScrollPane inner = Widgets.scroll(table);
            inner.setPreferredSize(new Dimension(100, 540));
            tableCard.add(inner, BorderLayout.CENTER);
            tableCard.setPreferredSize(new Dimension(100, 560));
            tableCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 560));
            page.add(tableCard);
            scroll = pageScroll(page);

            debounce = new Timer(280, e -> refresh());
            debounce.setRepeats(false);
            search.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { debounce.restart(); }
                @Override public void removeUpdate(DocumentEvent e) { debounce.restart(); }
                @Override public void changedUpdate(DocumentEvent e) { debounce.restart(); }
            });
            filterCombo.addActionListener(e -> {
                filterMode = filterCombo.getSelectedIndex();
                refresh();
            });
        }

        @Override
        JScrollPane scroll() {
            return scroll;
        }

        @Override
        void refresh() {
            CompanionClient.Dashboard dash = app.companionDashboard();
            List<CompanionClient.Query> rows = new ArrayList<>();
            if (dash != null) {
                String needle = search.getText().trim().toLowerCase(Locale.US);
                for (CompanionClient.Query row : dash.queries) {
                    if (filterMode == 1 && !row.blocked) continue;
                    if (filterMode == 2 && row.blocked) continue;
                    if (!needle.isEmpty()
                            && !row.domain.toLowerCase(Locale.US).contains(needle)
                            && !row.client.toLowerCase(Locale.US).contains(needle)
                            && !row.clientName.toLowerCase(Locale.US).contains(needle)) continue;
                    rows.add(row);
                }
            }
            model.setRows(rows);
            count.setText(rows.size() + " shown");
        }

        private void export() {
            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(new File("holeshield-remote-queries.csv"));
            if (chooser.showSaveDialog(ControlPanel.this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            StringBuilder csv = new StringBuilder(
                    "Time,Client,Domain,Category,Type,Status,Upstream,ms,Rule\n");
            java.text.SimpleDateFormat clock =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            for (CompanionClient.Query row : model.rows) {
                csv.append(clock.format(new java.util.Date(row.at))).append(',')
                        .append(csv(row.client)).append(',')
                        .append(csv(row.domain)).append(',')
                        .append(csv(row.category)).append(',')
                        .append(csv(row.type)).append(',')
                        .append(csv(row.status)).append(',')
                        .append(csv(row.upstream)).append(',')
                        .append(String.format(Locale.US, "%.1f", row.ms)).append(',')
                        .append(csv(row.rule)).append('\n');
            }
            try {
                Files.write(chooser.getSelectedFile().toPath(),
                        csv.toString().getBytes(StandardCharsets.UTF_8));
                setStatus("Exported " + chooser.getSelectedFile().getName());
            } catch (Exception e) {
                SystemIntegration.showError(ControlPanel.this,
                        "Could not write the export: " + e.getMessage());
            }
        }

        private String csv(String value) {
            if (value == null) return "";
            if (value.indexOf(',') < 0 && value.indexOf('"') < 0) return value;
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
    }

    /** Table model over the remote query log. */
    private static final class CompanionQueryModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Time", "Client", "Domain", "Category", "Type", "Status",
                "Upstream", "ms", "Rule"
        };
        private static final java.text.SimpleDateFormat CLOCK =
                new java.text.SimpleDateFormat("HH:mm:ss", Locale.US);
        private List<CompanionClient.Query> rows = new ArrayList<>();

        void setRows(List<CompanionClient.Query> rows) {
            List<CompanionClient.Query> next = rows == null
                    ? new ArrayList<>() : rows;
            if (sameIds(next)) return;
            this.rows = next;
            fireTableDataChanged();
        }

        /** Keeps the table still while the same rows are on screen. */
        private boolean sameIds(List<CompanionClient.Query> next) {
            if (next.size() != rows.size()) return false;
            for (int i = 0; i < next.size(); i++) {
                if (next.get(i).id != rows.get(i).id) return false;
            }
            return true;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Object getValueAt(int row, int column) {
            CompanionClient.Query q = rows.get(row);
            switch (column) {
                case 0: return CLOCK.format(new java.util.Date(q.at));
                case 1: return q.clientName.isEmpty() || q.clientName.equals(q.client)
                        ? q.client : q.clientName;
                case 2: return q.domain;
                case 3: return q.category == null || q.category.isEmpty()
                        ? (q.blocked ? "Gravity" : "—") : q.category;
                case 4: return q.type;
                case 5: return q.status;
                case 6: return q.upstream.isEmpty() ? "—" : q.upstream;
                case 7: return String.format(Locale.US, "%.1f", q.ms);
                case 8: return q.rule.isEmpty() ? "—" : q.rule;
                default: return "";
            }
        }
    }

    // --------------------------------------------------- companion blocklists

    /** Remote blocklists: gravity, categories and sources. */
    private final class CompanionListsPage extends Page {
        private final JScrollPane scroll;
        private final JLabel gravityLine = Widgets.label("", 12, Font.PLAIN, 1);
        private final JLabel gravityHint = Widgets.label("", 11, Font.PLAIN, 6);
        private final Widgets.PillButton update =
                new Widgets.PillButton("Update now", "refresh", Widgets.PRIMARY);
        private final JPanel categoryRows = new JPanel();
        private final SourceModel sourceModel = new SourceModel();
        private final JTable sourceTable = new JTable(sourceModel);
        private final Map<String, Widgets.ToggleSwitch> categoryToggles =
                new LinkedHashMap<>();
        private final Map<String, JComboBox<String>> categoryLevels =
                new LinkedHashMap<>();
        private final JTextField newSource = Widgets.field("https://example.com/hosts.txt", 30);
        private String sourceSignature = "";
        private boolean updatingCategories;

        CompanionListsPage() {
            VerticalPanel page = new VerticalPanel();
            page.add(gravityCard());
            page.add(Box.createVerticalStrut(14));
            page.add(categoriesCard());
            page.add(Box.createVerticalStrut(14));
            page.add(sourcesCard());
            page.add(Box.createVerticalStrut(14));
            page.add(addSourceCard());
            scroll = pageScroll(page);
        }

        private JComponent gravityCard() {
            Widgets.RoundedPanel card = card(18, 18, 18, 26);
            card.add(Widgets.header("Gravity database",
                    "The remote system's merged blocklist index", null),
                    BorderLayout.NORTH);
            JPanel body = new JPanel(new BorderLayout(10, 0));
            body.setOpaque(false);
            body.setBorder(new EmptyBorder(12, 0, 0, 0));
            JPanel copy = new JPanel();
            copy.setOpaque(false);
            copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
            copy.add(gravityLine);
            gravityHint.setBorder(new EmptyBorder(4, 0, 0, 0));
            copy.add(gravityHint);
            body.add(copy, BorderLayout.CENTER);
            update.addActionListener(e -> app.updateLists());
            body.add(update, BorderLayout.EAST);
            card.add(body, BorderLayout.CENTER);
            card.setPreferredSize(new Dimension(100, 120));
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
            return card;
        }

        private JComponent categoriesCard() {
            Widgets.RoundedPanel card = card(18, 18, 18, 18);
            card.add(Widgets.header("Categories",
                    "Turn a remote category on or off without rebuilding the index", null),
                    BorderLayout.NORTH);
            categoryRows.setOpaque(false);
            categoryRows.setLayout(new BoxLayout(categoryRows, BoxLayout.Y_AXIS));
            categoryRows.setBorder(new EmptyBorder(10, 0, 0, 0));
            String[] labels = {"Ads", "Trackers", "Malware", "Adult",
                    "Piracy", "Gambling", "Dating", "Social media"};
            for (int i = 0; i < Blocklists.CATEGORY_KEYS.length; i++) {
                String key = Blocklists.CATEGORY_KEYS[i];
                Widgets.ToggleSwitch toggle = new Widgets.ToggleSwitch(false);
                toggle.setOnChange(value -> app.companionSetCategory(key, value));
                categoryToggles.put(key, toggle);
                JComboBox<String> level = Widgets.combo(
                        new String[]{"Lite", "Medium", "Maximum"}, "Maximum");
                level.setPreferredSize(new Dimension(118, 28));
                level.addActionListener(e -> {
                    if (updatingCategories) return;
                    Object item = level.getSelectedItem();
                    String choice = item == null ? "Maximum" : item.toString();
                    app.companionSetCategoryLevel(key,
                            "Lite".equals(choice) ? "lite"
                                    : "Medium".equals(choice) ? "medium" : "max");
                });
                categoryLevels.put(key, level);

                JPanel row = new JPanel(new BorderLayout(10, 0));
                row.setOpaque(false);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
                row.setBorder(new EmptyBorder(5, 0, 5, 0));
                JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
                left.setOpaque(false);
                left.add(toggle);
                left.add(Widgets.label(labels[i], 12.5f, Font.BOLD, 0));
                row.add(left, BorderLayout.WEST);
                row.add(level, BorderLayout.EAST);
                categoryRows.add(row);
            }
            card.add(categoryRows, BorderLayout.CENTER);
            card.setPreferredSize(new Dimension(100, 400));
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 420));
            return card;
        }

        private JComponent sourcesCard() {
            Widgets.RoundedPanel card = card(18, 18, 18, 18);
            card.add(Widgets.header("Blocklist sources",
                    "Lists the remote system downloads", null), BorderLayout.NORTH);
            Widgets.styleTable(sourceTable);
            int[] widths = {620, 90, 80};
            for (int i = 0; i < sourceTable.getColumnCount(); i++) {
                sourceTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
                sourceTable.getColumnModel().getColumn(i)
                        .setCellRenderer(new SourceRenderer(i));
            }
            JScrollPane list = Widgets.scroll(sourceTable);
            list.setPreferredSize(new Dimension(100, 280));
            card.add(list, BorderLayout.CENTER);
            JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
            footer.setOpaque(false);
            Widgets.PillButton remove = new Widgets.PillButton("Remove selected",
                    "trash", Widgets.GHOST);
            remove.addActionListener(e -> removeSelectedSource());
            footer.add(remove);
            card.add(footer, BorderLayout.SOUTH);
            card.setPreferredSize(new Dimension(100, 390));
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 390));
            return card;
        }

        private void removeSelectedSource() {
            int row = sourceTable.getSelectedRow();
            if (row < 0) {
                setStatus("Select a blocklist source first.");
                return;
            }
            CompanionClient.Source source = sourceModel.rows.get(
                    sourceTable.convertRowIndexToModel(row));
            app.companionRemoveList(source.id, source.address);
        }

        /** The add field lives in its own card so a long list cannot clip it. */
        private JComponent addSourceCard() {
            Widgets.RoundedPanel card = card(18, 18, 18, 18);
            card.add(Widgets.header("Add a source",
                    "Downloads start on the next update", null), BorderLayout.NORTH);
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 10));
            row.setOpaque(false);
            row.add(newSource);
            Widgets.PillButton add = new Widgets.PillButton("Add source", "plus",
                    Widgets.TONAL);
            add.addActionListener(e -> {
                String value = newSource.getText().trim();
                if (!value.startsWith("http://") && !value.startsWith("https://")) {
                    setStatus("Enter an http(s) blocklist URL.");
                    return;
                }
                app.companionAddList(value);
                newSource.setText("");
            });
            row.add(add);
            card.add(row, BorderLayout.CENTER);
            card.setPreferredSize(new Dimension(100, 118));
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 128));
            return card;
        }

        @Override
        JScrollPane scroll() {
            return scroll;
        }

        @Override
        void refresh() {
            CompanionClient.Dashboard dash = app.companionDashboard();
            CompanionClient.Snapshot snapshot = app.companionSnapshot();
            update.setEnabled(app.companionConnected() && !app.isUpdating());
            if (snapshot != null) {
                gravityLine.setText(DesktopApp.formatNumber(snapshot.gravityDomains)
                        + " domains · " + snapshot.categories.size() + " categories");
                gravityHint.setText(snapshot.lastUpdate > 0
                        ? "Last updated " + relative(snapshot.lastUpdate)
                        : "No update has run yet.");
            } else {
                gravityLine.setText("Waiting for the remote dashboard…");
                gravityHint.setText("");
            }
            if (dash == null) return;
            for (Map.Entry<String, Widgets.ToggleSwitch> entry : categoryToggles.entrySet()) {
                Boolean on = dash.categories.get(entry.getKey());
                entry.getValue().setSelected(on != null && on);
            }
            updatingCategories = true;
            try {
                for (Map.Entry<String, JComboBox<String>> entry : categoryLevels.entrySet()) {
                    String level = dash.levels.get(entry.getKey());
                    String label = "lite".equals(level) ? "Lite"
                            : "medium".equals(level) ? "Medium" : "Maximum";
                    JComboBox<String> combo = entry.getValue();
                    if (!label.equals(combo.getSelectedItem())) {
                        combo.setSelectedItem(label);
                    }
                }
            } finally {
                updatingCategories = false;
            }
            String signature = signature(dash.sources);
            if (!signature.equals(sourceSignature)) {
                sourceSignature = signature;
                sourceModel.setRows(dash.sources);
            }
        }

        private String signature(List<CompanionClient.Source> sources) {
            StringBuilder out = new StringBuilder();
            for (CompanionClient.Source source : sources) {
                out.append(source.id).append(source.address).append(source.ok)
                        .append(source.count).append('|');
            }
            return out.toString();
        }
    }

    /** Table model over the remote blocklist sources. */
    private static final class SourceModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Address", "Domains", "Status"};
        private List<CompanionClient.Source> rows = new ArrayList<>();

        void setRows(List<CompanionClient.Source> rows) {
            this.rows = rows == null ? new ArrayList<>() : rows;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Object getValueAt(int row, int column) {
            CompanionClient.Source source = rows.get(row);
            switch (column) {
                case 0: return source.address.replaceFirst("^https?://", "");
                case 1: return DesktopApp.formatNumber(source.count);
                case 2: return source.ok ? "OK" : "FAIL";
                default: return "";
            }
        }
    }

    private static final class SourceRenderer extends DefaultTableCellRenderer {
        private final int column;

        SourceRenderer(int column) {
            this.column = column;
            setOpaque(true);
            setBorder(new EmptyBorder(0, 11, 0, 8));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean selected, boolean focus,
                                                       int row, int col) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, col);
            Theme t = Theme.get();
            setBackground(selected ? t.accentSoft
                    : (row % 2 == 0 ? t.surface : t.surfaceAlt));
            setForeground(t.text);
            setFont(column == 0 ? Theme.mono(11.5f) : Theme.font(11.5f));
            if (column == 1) setForeground(t.muted);
            if (column == 2) {
                setFont(Theme.font(10.5f, Font.BOLD));
                String status = value == null ? "" : value.toString();
                setForeground("OK".equals(status) ? t.accent : t.danger);
            }
            return this;
        }
    }

    // -------------------------------------------------------- companion rules

    /** Remote allow and block rules. */
    private final class CompanionRulesPage extends Page {
        private final JScrollPane scroll;
        private final JTextField domain = Widgets.field("example.com", 24);
        private final JComboBox<String> kind = Widgets.combo(
                new String[]{"Exact domain", "Wildcard", "Regex"}, "Exact domain");
        private final JComboBox<String> action = Widgets.combo(
                new String[]{"Block", "Allow"}, "Block");
        private final JPanel allowRows = new JPanel();
        private final JPanel blockRows = new JPanel();
        private String ruleSignature = "";
        private Widgets.RoundedPanel allowPanel;
        private Widgets.RoundedPanel blockPanel;

        CompanionRulesPage() {
            VerticalPanel page = new VerticalPanel();
            page.add(addCard());
            page.add(Box.createVerticalStrut(14));
            page.add(listsRow());
            scroll = pageScroll(page);
        }

        private JComponent addCard() {
            Widgets.RoundedPanel card = card(18, 18, 18, 18);
            card.add(Widgets.header("Add a rule",
                    "Rules are applied on the remote system", null),
                    BorderLayout.NORTH);
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 10));
            row.setOpaque(false);
            row.add(domain);
            row.add(kind);
            row.add(action);
            Widgets.PillButton add = new Widgets.PillButton("Add rule", "plus",
                    Widgets.PRIMARY);
            add.addActionListener(e -> {
                String value = domain.getText().trim();
                if (value.isEmpty()) {
                    setStatus("Enter a domain or pattern first.");
                    return;
                }
                app.companionAddRule("Allow".equals(action.getSelectedItem()),
                        kindOf(), value);
                domain.setText("");
            });
            row.add(add);
            card.add(row, BorderLayout.CENTER);
            fitCard(card);
            return card;
        }

        private String kindOf() {
            Object item = kind.getSelectedItem();
            String value = item == null ? "" : item.toString();
            return "Wildcard".equals(value) ? "wildcard"
                    : "Regex".equals(value) ? "regex" : "exact";
        }

        private JComponent listsRow() {
            JPanel row = new JPanel(new GridLayout(1, 2, 14, 0));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            allowPanel = ruleCard("Allowed domains", "Never filtered", allowRows);
            blockPanel = ruleCard("Blocked domains", "Always sinkholed", blockRows);
            row.add(allowPanel);
            row.add(blockPanel);
            row.setPreferredSize(new Dimension(100, 360));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 380));
            return row;
        }

        private Widgets.RoundedPanel ruleCard(String title, String sub, JPanel rows) {
            Widgets.RoundedPanel card = card(18, 18, 18, 18);
            card.add(Widgets.header(title, sub, null), BorderLayout.NORTH);
            rows.setOpaque(false);
            rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
            rows.setBorder(new EmptyBorder(10, 0, 0, 0));
            card.add(rows, BorderLayout.CENTER);
            return card;
        }

        @Override
        JScrollPane scroll() {
            return scroll;
        }

        @Override
        void refresh() {
            CompanionClient.Dashboard dash = app.companionDashboard();
            if (dash == null) return;
            String signature = signature(dash);
            if (signature.equals(ruleSignature)) return;
            ruleSignature = signature;
            rebuild(allowRows, dash.allowed, true);
            rebuild(blockRows, dash.blocked, false);
            if (allowPanel != null) fitCard(allowPanel);
            if (blockPanel != null) fitCard(blockPanel);
        }

        private String signature(CompanionClient.Dashboard dash) {
            StringBuilder out = new StringBuilder();
            for (CompanionClient.Rule rule : dash.allowed) {
                out.append('a').append(rule.kind).append(rule.domain).append('|');
            }
            for (CompanionClient.Rule rule : dash.blocked) {
                out.append('b').append(rule.kind).append(rule.domain).append('|');
            }
            return out.toString();
        }

        private void rebuild(JPanel container, List<CompanionClient.Rule> rules,
                             boolean allow) {
            container.removeAll();
            if (rules.isEmpty()) {
                container.add(Widgets.hint(allow
                        ? "No allow rules." : "No block rules."));
            }
            for (CompanionClient.Rule rule : rules) {
                JPanel row = new JPanel(new BorderLayout(8, 0));
                row.setOpaque(false);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
                row.setBorder(new EmptyBorder(3, 0, 3, 0));
                JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
                left.setOpaque(false);
                left.add(Widgets.label(rule.kind, 10, Font.BOLD, 0));
                left.add(Widgets.label(rule.domain, 11.5f, Font.PLAIN, 0));
                row.add(left, BorderLayout.CENTER);
                Widgets.PillButton remove = new Widgets.PillButton("", "trash",
                        Widgets.GHOST);
                remove.setPreferredSize(new Dimension(34, 30));
                remove.addActionListener(e -> app.companionRemoveRule(allow,
                        rule.kind, rule.domain));
                row.add(remove, BorderLayout.EAST);
                container.add(row);
            }
            container.revalidate();
            container.repaint();
        }
    }

    // ----------------------------------------------------------------- queries

    /** Table that explains itself when there is nothing to show. */
    private final class QueryTable extends JTable {
        private final java.util.function.BooleanSupplier unfiltered;

        QueryTable(QueryModel model, java.util.function.BooleanSupplier unfiltered) {
            super(model);
            this.unfiltered = unfiltered;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getRowCount() > 0) return;
            Theme t = Theme.get();
            String message = unfiltered.getAsBoolean()
                    ? "No queries yet. Lookups appear here while DNS is running."
                    : "No queries match this filter.";
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(Theme.font(12f));
            g2.setColor(t.faint);
            FontMetrics fm = g2.getFontMetrics();
            int y = Math.max(fm.getAscent() + 8,
                    getHeight() / 2 - fm.getHeight() / 2 + fm.getAscent());
            g2.drawString(message, Math.max(12, (getWidth() - fm.stringWidth(message)) / 2), y);
            g2.dispose();
        }
    }

    private final class QueriesPage extends Page {
        private final JScrollPane scroll;
        private final JTextField search = Widgets.field("Search domain or client", 24);
        private final JComboBox<String> filterCombo = Widgets.combo(
                new String[]{"All queries", "Blocked only", "Allowed only"}, "All queries");
        private final JLabel count = Widgets.label("", 11.5f, Font.PLAIN, 1);
        private final QueryModel model = new QueryModel();
        private int filterMode;
        private final JTable table = new QueryTable(model,
                () -> search.getText().trim().isEmpty() && filterMode == 0);
        private final Timer debounce;

        QueriesPage() {
            VerticalPanel page = new VerticalPanel();
            Widgets.RoundedPanel toolbar = card(14, 16, 14, 16);
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            left.setOpaque(false);
            left.add(search);
            left.add(filterCombo);
            Widgets.PillButton refresh = new Widgets.PillButton("Refresh", "refresh",
                    Widgets.TONAL);
            refresh.addActionListener(e -> refresh());
            left.add(refresh);
            toolbar.add(left, BorderLayout.WEST);
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            right.setOpaque(false);
            right.add(count);
            Widgets.PillButton export = new Widgets.PillButton("Export", "download",
                    Widgets.OUTLINE);
            export.addActionListener(e -> export());
            right.add(export);
            toolbar.add(right, BorderLayout.EAST);
            toolbar.setPreferredSize(new Dimension(100, 68));
            toolbar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 68));
            page.add(toolbar);
            page.add(Box.createVerticalStrut(12));

            Widgets.RoundedPanel tableCard = card(0, 0, 0, 0);
            tableCard.setClipChildren(true);
            Widgets.styleTable(table);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
            table.getColumnModel().getColumn(0).setPreferredWidth(82);
            table.getColumnModel().getColumn(1).setPreferredWidth(130);
            table.getColumnModel().getColumn(2).setPreferredWidth(250);
            table.getColumnModel().getColumn(3).setPreferredWidth(92);
            table.getColumnModel().getColumn(4).setPreferredWidth(62);
            table.getColumnModel().getColumn(5).setPreferredWidth(90);
            table.getColumnModel().getColumn(6).setPreferredWidth(96);
            table.getColumnModel().getColumn(7).setPreferredWidth(58);
            table.getColumnModel().getColumn(8).setPreferredWidth(160);
            for (int i = 0; i < table.getColumnCount(); i++) {
                table.getColumnModel().getColumn(i).setCellRenderer(new QueryRenderer(i));
            }
            JScrollPane tableScroll = Widgets.scrollSurface(table);
            tableCard.add(tableScroll, BorderLayout.CENTER);
            tableCard.setPreferredSize(new Dimension(100, 520));
            tableCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            page.add(tableCard);

            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) { maybePopup(e); }
                @Override
                public void mouseReleased(MouseEvent e) { maybePopup(e); }
            });
            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) copySelectedDomain();
                }
            });

            debounce = new Timer(280, e -> refresh());
            debounce.setRepeats(false);
            search.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { debounce.restart(); }
                @Override public void removeUpdate(DocumentEvent e) { debounce.restart(); }
                @Override public void changedUpdate(DocumentEvent e) { debounce.restart(); }
            });
            filterCombo.addActionListener(e -> {
                filterMode = filterCombo.getSelectedIndex();
                refresh();
            });
            scroll = pageScroll(page);
        }

        private void maybePopup(MouseEvent e) {
            if (!e.isPopupTrigger()) return;
            int row = table.rowAtPoint(e.getPoint());
            if (row < 0) return;
            table.setRowSelectionInterval(row, row);
            StatsStore.Entry entry = model.rows.get(table.convertRowIndexToModel(row));
            JPopupMenu menu = new JPopupMenu();
            JMenuItem allow = new JMenuItem("Allow " + entry.domain);
            allow.addActionListener(a -> {
                filter.addRule(true, FilterEngine.EXACT, entry.domain);
                setStatus("Allowed " + entry.domain);
                refresh();
            });
            JMenuItem block = new JMenuItem("Block " + entry.domain);
            block.addActionListener(a -> {
                filter.addRule(false, FilterEngine.EXACT, entry.domain);
                setStatus("Blocked " + entry.domain);
                refresh();
            });
            JMenuItem copy = new JMenuItem("Copy domain");
            copy.addActionListener(a -> SystemIntegration.copy(entry.domain));
            menu.add(allow);
            menu.add(block);
            menu.addSeparator();
            menu.add(copy);
            menu.show(table, e.getX(), e.getY());
        }

        private void copySelectedDomain() {
            int row = table.getSelectedRow();
            if (row < 0) return;
            StatsStore.Entry entry = model.rows.get(table.convertRowIndexToModel(row));
            SystemIntegration.copy(entry.domain);
            setStatus("Copied " + entry.domain);
        }

        private void export() {
            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(new File("holeshield-queries.csv"));
            if (chooser.showSaveDialog(ControlPanel.this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            File file = chooser.getSelectedFile();
            try {
                String csv = stats.exportCsv(filterMode, search.getText().trim());
                Files.write(file.toPath(), csv.getBytes(StandardCharsets.UTF_8));
                setStatus("Exported " + file.getName());
            } catch (Exception e) {
                SystemIntegration.showError(ControlPanel.this,
                        "Could not write the export: " + e.getMessage());
            }
        }

        @Override
        JScrollPane scroll() {
            return scroll;
        }

        @Override
        void refresh() {
            String needle = search.getText().trim().toLowerCase(Locale.US);
            List<StatsStore.Entry> rows = new ArrayList<>();
            for (StatsStore.Entry entry : stats.log(600)) {
                if (filterMode == 1 && !entry.blocked) continue;
                if (filterMode == 2 && entry.blocked) continue;
                if (!needle.isEmpty()
                        && !entry.domain.toLowerCase(Locale.US).contains(needle)
                        && !entry.client.toLowerCase(Locale.US).contains(needle)
                        && !DnsService.clientName(entry.client).toLowerCase(Locale.US)
                        .contains(needle)) continue;
                rows.add(entry);
            }
            if (!sameRows(rows)) {
                StatsStore.Entry selected = selectedEntry();
                model.setRows(rows);
                restoreSelection(selected);
            }
            count.setText(rows.size() + " shown");
        }

        /** Entries are shared with the log, so identity tells us what changed. */
        private boolean sameRows(List<StatsStore.Entry> rows) {
            List<StatsStore.Entry> current = model.rows;
            if (current.size() != rows.size()) return false;
            for (int i = 0; i < rows.size(); i++) {
                if (current.get(i) != rows.get(i)) return false;
            }
            return true;
        }

        private StatsStore.Entry selectedEntry() {
            int row = table.getSelectedRow();
            if (row < 0) return null;
            return model.rows.get(table.convertRowIndexToModel(row));
        }

        private void restoreSelection(StatsStore.Entry entry) {
            if (entry == null) return;
            for (int i = 0; i < model.rows.size(); i++) {
                if (model.rows.get(i) == entry) {
                    int view = table.convertRowIndexToView(i);
                    table.setRowSelectionInterval(view, view);
                    return;
                }
            }
        }
    }

    private static final class QueryModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Time", "Client", "Domain", "Category", "Type", "Status",
                "Upstream", "ms", "Rule"
        };
        private static final java.text.SimpleDateFormat CLOCK =
                new java.text.SimpleDateFormat("HH:mm:ss", Locale.US);
        private List<StatsStore.Entry> rows = new ArrayList<>();

        void setRows(List<StatsStore.Entry> rows) {
            this.rows = rows == null ? new ArrayList<>() : rows;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Object getValueAt(int row, int column) {
            StatsStore.Entry e = rows.get(row);
            switch (column) {
                case 0: return CLOCK.format(new java.util.Date(e.at));
                case 1: {
                    String name = DnsService.clientName(e.client);
                    return name.equals(e.client) ? e.client : name;
                }
                case 2: return e.domain;
                case 3: return e.category == null || e.category.isEmpty()
                        ? (e.blocked ? "Gravity" : "—") : e.category;
                case 4: return e.typeName();
                case 5: return e.status;
                case 6: return e.upstream.isEmpty() ? "—" : e.upstream;
                case 7: return String.format(Locale.US, "%.1f", e.ms / 1000.0);
                case 8: return e.rule.isEmpty() ? "—" : e.rule;
                default: return "";
            }
        }
    }

    private static final class QueryRenderer extends DefaultTableCellRenderer {
        private final int column;
        QueryRenderer(int column) {
            this.column = column;
            setOpaque(true);
            setBorder(new EmptyBorder(0, 11, 0, 8));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean selected, boolean focus,
                                                       int row, int col) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, col);
            Theme t = Theme.get();
            setBackground(selected ? t.accentSoft : (row % 2 == 0 ? t.surface : t.surfaceAlt));
            setForeground(t.text);
            setFont(Theme.font(12f));
            if (column == 0 || column == 7) {
                setFont(Theme.mono(11f));
                setForeground(t.muted);
            } else if (column == 2) {
                setFont(Theme.mono(11.5f));
            } else if (column == 3 || column == 4 || column == 8) {
                setFont(Theme.font(10.5f, Font.BOLD));
                setForeground(t.muted);
            } else if (column == 5) {
                String status = value == null ? "" : value.toString();
                setFont(Theme.font(10.5f, Font.BOLD));
                if ("BLOCKED".equals(status)) setForeground(t.danger);
                else if ("ALLOWED".equals(status)) setForeground(t.accent);
                else if ("ERROR".equals(status)) setForeground(t.warn);
                else setForeground(t.muted);
            } else if (column == 1) {
                setForeground(t.muted);
            }
            return this;
        }
    }

    // ------------------------------------------------------------------- lists

    private final class ListsPage extends Page {
        private final JScrollPane scroll;
        private final JLabel sourceCount = Widgets.label("", 11, Font.PLAIN, 1);
        private final JLabel updateLabel = Widgets.label("", 11, Font.PLAIN, 1);
        private final JProgressBar progress = new JProgressBar();
        private final Widgets.PillButton update =
                new Widgets.PillButton("Update now", "refresh", Widgets.PRIMARY);
        private final JTextField url = Widgets.field("https://example.com/hosts.txt", 30);
        private final JPanel sources = new JPanel();
        private final JPanel health = new JPanel();
        private final Map<String, Widgets.ToggleSwitch> categorySwitches =
                new LinkedHashMap<>();
        private final Map<String, JLabel> categoryCounts = new LinkedHashMap<>();
        private String signature = "";

        ListsPage() {
            VerticalPanel page = new VerticalPanel();

            Widgets.RoundedPanel updateCard = card(17, 18, 17, 26);
            JPanel left = new JPanel();
            left.setOpaque(false);
            left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
            left.add(Widgets.label("Gravity database", 15, Font.BOLD, 0));
            updateLabel.setBorder(new EmptyBorder(2, 0, 0, 0));
            left.add(updateLabel);
            progress.setIndeterminate(true);
            progress.setVisible(false);
            progress.setPreferredSize(new Dimension(280, 6));
            progress.setBorderPainted(false);
            progress.setMaximumSize(new Dimension(280, 6));
            JPanel progressWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 8));
            progressWrap.setOpaque(false);
            progressWrap.add(progress);
            left.add(progressWrap);
            updateCard.add(left, BorderLayout.CENTER);
            update.addActionListener(e -> app.updateLists());
            JPanel updateWrap = new JPanel(new GridBagLayout());
            updateWrap.setOpaque(false);
            updateWrap.add(update);
            updateCard.add(updateWrap, BorderLayout.EAST);
            updateCard.setPreferredSize(new Dimension(100, 100));
            updateCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
            page.add(updateCard);
            page.add(Box.createVerticalStrut(14));

            Widgets.RoundedPanel capCard = card(16, 17, 14, 24);
            JPanel capBox = new JPanel();
            capBox.setOpaque(false);
            capBox.setLayout(new BoxLayout(capBox, BoxLayout.Y_AXIS));

            JPanel capHead = new JPanel(new BorderLayout(10, 0));
            capHead.setOpaque(false);
            JPanel capText = new JPanel();
            capText.setOpaque(false);
            capText.setLayout(new BoxLayout(capText, BoxLayout.Y_AXIS));
            capText.add(Widgets.label("Limit gravity size", 13.5f, Font.BOLD, 0));
            capText.add(Widgets.hint("Cap the loaded index. Run Update now to apply."));
            Widgets.ToggleSwitch capToggle = new Widgets.ToggleSwitch(
                    prefs.getBool("gravityCapEnabled", true));
            capHead.add(capText, BorderLayout.CENTER);
            capHead.add(capToggle, BorderLayout.EAST);
            capBox.add(capHead);

            JPanel sliderRow = new JPanel(new BorderLayout(10, 0));
            sliderRow.setOpaque(false);
            sliderRow.setBorder(new EmptyBorder(8, 0, 0, 0));
            int capNow = prefs.getInt("gravityCap", 1510000);
            JSlider capSlider = new JSlider(0, CAP_NO_LIMIT, capProgress(capNow));
            Widgets.styleSlider(capSlider);
            capSlider.setPaintTicks(false);
            capSlider.setPaintLabels(false);
            JLabel capValue = Widgets.label(capLabel(capNow), 12, Font.BOLD, 0);
            capValue.setPreferredSize(new Dimension(72, 20));
            capValue.setHorizontalAlignment(SwingConstants.RIGHT);
            sliderRow.add(capSlider, BorderLayout.CENTER);
            sliderRow.add(capValue, BorderLayout.EAST);
            boolean capOn = prefs.getBool("gravityCapEnabled", true);
            sliderRow.setVisible(capOn);
            capBox.add(sliderRow);
            capCard.add(capBox, BorderLayout.CENTER);
            capCard.setPreferredSize(new Dimension(100, capOn ? 128 : 82));
            capCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, capOn ? 140 : 92));

            capToggle.setOnChange(value -> {
                prefs.setBool("gravityCapEnabled", value);
                if (value && prefs.getInt("gravityCap", 1510000) <= 0) {
                    prefs.setInt("gravityCap", 1510000);
                    capSlider.setValue(capProgress(1510000));
                    capValue.setText(capLabel(1510000));
                }
                app.inBackground(prefs::save, null);
                sliderRow.setVisible(value);
                capCard.setPreferredSize(new Dimension(100, value ? 128 : 82));
                capCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, value ? 140 : 92));
                capCard.revalidate();
                capCard.repaint();
                setStatus(value
                        ? "Gravity limit enabled. Run Update now to apply."
                        : "Gravity limit off. Run Update now to load every domain.");
            });
            capSlider.addChangeListener(e -> {
                int cap = capFromProgress(capSlider.getValue());
                prefs.setInt("gravityCap", cap);
                capValue.setText(capLabel(cap));
                if (!capSlider.getValueIsAdjusting()) {
                    app.inBackground(prefs::save, null);
                }
            });

            page.add(capCard);
            page.add(Box.createVerticalStrut(14));

            Widgets.RoundedPanel cats = card(16, 17, 14, 17);
            cats.add(Widgets.header("Categories",
                    "Turn a category off without rebuilding the database", null),
                    BorderLayout.NORTH);
            JPanel rows = new JPanel();
            rows.setOpaque(false);
            rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
            rows.setBorder(new EmptyBorder(10, 0, 0, 0));
            addCategory(rows, "Ads", "categoryAds");
            addCategory(rows, "Trackers", "categoryTrackers");
            addCategory(rows, "Malware", "categoryMalware");
            addCategory(rows, "Adult", "categoryAdult");
            addCategory(rows, "Piracy", "categoryPiracy");
            addCategory(rows, "Gambling", "categoryGambling");
            addCategory(rows, "Dating", "categoryDating");
            addCategory(rows, "Social media", "categorySocial");
            addCategory(rows, "Other", "categoryOther");
            cats.add(rows, BorderLayout.CENTER);
            cats.setPreferredSize(new Dimension(100, 482));
            cats.setMaximumSize(new Dimension(Integer.MAX_VALUE, 520));
            page.add(cats);
            page.add(Box.createVerticalStrut(14));

            Widgets.RoundedPanel addCard = card(16, 17, 16, 17);
            JPanel addCopy = new JPanel();
            addCopy.setOpaque(false);
            addCopy.setLayout(new BoxLayout(addCopy, BoxLayout.Y_AXIS));
            addCopy.add(Widgets.label("Blocklist sources", 15, Font.BOLD, 0));
            sourceCount.setBorder(new EmptyBorder(2, 0, 0, 0));
            addCopy.add(sourceCount);
            JPanel addTop = new JPanel();
            addTop.setOpaque(false);
            addTop.setLayout(new BoxLayout(addTop, BoxLayout.Y_AXIS));
            addTop.add(addCopy);
            JPanel addRow = new JPanel(new BorderLayout(8, 0));
            addRow.setOpaque(false);
            addRow.setBorder(new EmptyBorder(12, 0, 12, 0));
            addRow.add(url, BorderLayout.CENTER);
            Widgets.PillButton add = new Widgets.PillButton("Add source", "plus",
                    Widgets.TONAL);
            add.addActionListener(e -> addSource());
            addRow.add(add, BorderLayout.EAST);
            addTop.add(addRow);
            addCard.add(addTop, BorderLayout.NORTH);
            sources.setOpaque(false);
            sources.setLayout(new BoxLayout(sources, BoxLayout.Y_AXIS));
            JScrollPane sourceScroll = Widgets.scroll(sources);
            sourceScroll.setPreferredSize(new Dimension(100, 190));
            addCard.add(sourceScroll, BorderLayout.CENTER);
            addCard.setPreferredSize(new Dimension(100, 360));
            addCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 420));
            page.add(addCard);
            page.add(Box.createVerticalStrut(14));

            Widgets.RoundedPanel healthCard = card(16, 17, 14, 17);
            healthCard.add(Widgets.header("Download health",
                    "Results from the most recent gravity update", null),
                    BorderLayout.NORTH);
            health.setOpaque(false);
            health.setLayout(new BoxLayout(health, BoxLayout.Y_AXIS));
            health.setBorder(new EmptyBorder(10, 0, 0, 0));
            JScrollPane healthScroll = Widgets.scroll(health);
            healthScroll.setPreferredSize(new Dimension(100, 190));
            healthCard.add(healthScroll, BorderLayout.CENTER);
            healthCard.setPreferredSize(new Dimension(100, 260));
            healthCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 320));
            page.add(healthCard);
            scroll = pageScroll(page);
        }

        private void addCategory(JPanel parent, String label, String key) {
            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(6, 0, 6, 0));
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 9, 0));
            left.setOpaque(false);
            boolean fallback = !(key.equals("categoryAdult")
                    || key.equals("categoryPiracy")
                    || key.equals("categoryGambling")
                    || key.equals("categoryDating")
                    || key.equals("categorySocial"));
            String levelKey = key.substring("category".length()).toLowerCase(Locale.ROOT);
            boolean supportsLevels = false;
            for (String candidate : Blocklists.CATEGORY_KEYS) {
                if (candidate.equals(levelKey)) supportsLevels = true;
            }
            final boolean hasLevels = supportsLevels;
            Widgets.ToggleSwitch toggle = new Widgets.ToggleSwitch(
                    prefs.getBool(key, fallback));
            toggle.setOnChange(value -> {
                prefs.setBool(key, value);
                if (value && hasLevels) {
                    prefs.set("blocklistUrls", Blocklists.applyLevel(
                            prefs.blocklistUrls(), levelKey,
                            prefs.categoryLevel(levelKey)));
                }
                app.inBackground(() -> {
                    prefs.save();
                    filter.reloadRules();
                }, () -> {
                    refresh();
                    if (value) app.updateLists();
                });
            });
            left.add(toggle);
            left.add(Widgets.label(label, 12.5f, Font.BOLD, 0));
            row.add(left, BorderLayout.WEST);
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            right.setOpaque(false);
            JLabel count = Widgets.label("0 domains", 11, Font.PLAIN, 1);
            right.add(count);
            if (hasLevels) {
                String current = prefs.categoryLevel(levelKey);
                String selected = "lite".equals(current) ? "Lite"
                        : "medium".equals(current) ? "Medium" : "Maximum";
                JComboBox<String> level = Widgets.combo(
                        new String[]{"Lite", "Medium", "Maximum"}, selected);
                level.setPreferredSize(new Dimension(118, 28));
                level.addActionListener(e -> {
                    Object item = level.getSelectedItem();
                    String choice = item == null ? "Maximum" : item.toString();
                    String value = "Lite".equals(choice) ? "lite"
                            : "Medium".equals(choice) ? "medium" : "max";
                    prefs.setCategoryLevel(levelKey, value);
                    if (prefs.getBool(key, fallback)) {
                        prefs.set("blocklistUrls", Blocklists.applyLevel(
                                prefs.blocklistUrls(), levelKey, value));
                    }
                    boolean enabled = prefs.getBool(key, fallback);
                    app.inBackground(() -> {
                        prefs.save();
                        filter.reloadRules();
                    }, () -> {
                        refresh();
                        if (enabled && !filter.isUpdating()) {
                            app.updateLists();
                        }
                    });
                });
                right.add(level);
            }
            row.add(right, BorderLayout.EAST);
            categorySwitches.put(key, toggle);
            categoryCounts.put(key, count);
            parent.add(row);
        }

        private void addSource() {
            String value = url.getText().trim();
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                setStatus("Enter an http(s) blocklist URL.");
                return;
            }
            if (filter.listUrls().contains(value)) {
                setStatus("That source is already configured.");
                return;
            }
            filter.addListUrl(value);
            url.setText("");
            signature = "";
            setStatus("Source added. Run Update now to download it.");
            refresh();
        }

        @Override
        JScrollPane scroll() {
            return scroll;
        }

        @Override
        void refresh() {
            sourceCount.setText(filter.listUrls().size() + " configured sources · "
                    + DesktopApp.formatNumber(filter.gravityCount()) + " domains");
            updateLabel.setText(filter.isUpdating() ? "Downloading lists…"
                    : filter.lastUpdate() == 0 ? "No update has run yet."
                    : "Updated " + relative(filter.lastUpdate()) + " · "
                    + DesktopApp.formatNumber(filter.gravityCount()) + " domains");
            progress.setVisible(filter.isUpdating());
            update.setEnabled(!filter.isUpdating());
            update.setText(filter.isUpdating() ? "Updating…" : "Update now");
            categoryCounts.get("categoryAds").setText(
                    DesktopApp.formatNumber(filter.adsCount()) + " domains");
            categoryCounts.get("categoryTrackers").setText(
                    DesktopApp.formatNumber(filter.trackerCount()) + " domains");
            categoryCounts.get("categoryMalware").setText(
                    DesktopApp.formatNumber(filter.malwareCount()) + " domains");
            categoryCounts.get("categoryAdult").setText(
                    DesktopApp.formatNumber(filter.adultCount()) + " domains");
            categoryCounts.get("categoryPiracy").setText(
                    DesktopApp.formatNumber(filter.piracyCount()) + " domains");
            categoryCounts.get("categoryGambling").setText(
                    DesktopApp.formatNumber(filter.gamblingCount()) + " domains");
            categoryCounts.get("categoryDating").setText(
                    DesktopApp.formatNumber(filter.datingCount()) + " domains");
            categoryCounts.get("categorySocial").setText(
                    DesktopApp.formatNumber(filter.socialCount()) + " domains");
            categoryCounts.get("categoryOther").setText(
                    DesktopApp.formatNumber(filter.otherCount()) + " domains");

            String next = String.join("|", filter.listUrls());
            if (!next.equals(signature)) {
                signature = next;
                rebuildSources();
            }
            rebuildHealth();
        }

        private void rebuildSources() {
            sources.removeAll();
            List<String> urls = filter.listUrls();
            for (int i = 0; i < urls.size(); i++) {
                final int index = i;
                String value = urls.get(i);
                JPanel row = new JPanel(new BorderLayout(8, 0));
                row.setOpaque(false);
                row.setBorder(new EmptyBorder(5, 0, 5, 0));
                JLabel label = Widgets.label(value, 11.5f, Font.PLAIN, 1);
                label.setToolTipText(value);
                row.add(label, BorderLayout.CENTER);
                Widgets.IconButton remove = new Widgets.IconButton("trash",
                        "Remove source", Widgets.GHOST);
                remove.addActionListener(e -> {
                    filter.removeListUrl(index);
                    signature = "";
                    setStatus("Source removed.");
                    refresh();
                });
                row.add(remove, BorderLayout.EAST);
                sources.add(row);
            }
            if (urls.isEmpty()) {
                sources.add(Widgets.label("No sources configured.", 11.5f,
                        Font.PLAIN, 2));
            }
            sources.revalidate();
            sources.repaint();
        }

        private void rebuildHealth() {
            health.removeAll();
            List<FilterEngine.Health> rows = filter.health();
            if (rows.isEmpty()) {
                health.add(Widgets.label("No download results yet.", 11.5f,
                        Font.PLAIN, 2));
            } else {
                for (FilterEngine.Health h : rows) {
                    JPanel row = new JPanel(new BorderLayout(8, 0));
                    row.setOpaque(false);
                    row.setBorder(new EmptyBorder(5, 0, 5, 0));
                    JLabel badge = Widgets.label(h.ok ? "OK" : "FAIL", 10.5f,
                            Font.BOLD, h.ok ? 3 : 4);
                    badge.setPreferredSize(new Dimension(46, 20));
                    row.add(badge, BorderLayout.WEST);
                    JLabel name = Widgets.label(stripScheme(h.url), 11.5f,
                            Font.PLAIN, 1);
                    name.setToolTipText(h.url);
                    row.add(name, BorderLayout.CENTER);
                    row.add(Widgets.label(h.ok ? DesktopApp.formatNumber(h.count)
                            + " domains" : h.error, 10.5f, Font.PLAIN, 2),
                            BorderLayout.EAST);
                    health.add(row);
                }
            }
            health.revalidate();
            health.repaint();
        }
    }

    // ------------------------------------------------------------------- rules

    private final class RulesPage extends Page {
        private final JScrollPane scroll;
        private final JTextField domain = Widgets.field("example.com", 26);
        private final JComboBox<String> kind = Widgets.combo(
                new String[]{"Exact domain", "Wildcard subdomains", "Regular expression"},
                "Exact domain");
        private final JComboBox<String> action = Widgets.combo(
                new String[]{"Block", "Allow"}, "Block");
        private final JPanel allowList = new JPanel();
        private final JPanel blockList = new JPanel();
        private final JLabel allowCount = Widgets.label("", 11, Font.PLAIN, 1);
        private final JLabel blockCount = Widgets.label("", 11, Font.PLAIN, 1);

        RulesPage() {
            VerticalPanel page = new VerticalPanel();
            Widgets.RoundedPanel addCard = card(16, 17, 16, 17);
            addCard.add(Widgets.header("Add a rule",
                    "Allow rules always win. Wildcards match subdomains only.", null),
                    BorderLayout.NORTH);
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(12, 0, 0, 0));
            row.add(domain, BorderLayout.CENTER);
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
            controls.setOpaque(false);
            controls.add(kind);
            controls.add(action);
            Widgets.PillButton add = new Widgets.PillButton("Add rule", "plus",
                    Widgets.PRIMARY);
            add.addActionListener(e -> addRule());
            controls.add(add);
            row.add(controls, BorderLayout.EAST);
            addCard.add(row, BorderLayout.CENTER);
            addCard.setPreferredSize(new Dimension(100, 116));
            addCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 116));
            page.add(addCard);
            page.add(Box.createVerticalStrut(14));

            JPanel columns = new JPanel(new GridLayout(1, 2, 14, 0));
            columns.setOpaque(false);
            columns.setAlignmentX(Component.LEFT_ALIGNMENT);
            columns.add(rulesCard("Allowed domains", "Never filtered", allowList,
                    allowCount, true));
            columns.add(rulesCard("Blocked domains", "Always sinkholed", blockList,
                    blockCount, false));
            columns.setPreferredSize(new Dimension(100, 470));
            columns.setMaximumSize(new Dimension(Integer.MAX_VALUE, 620));
            page.add(columns);
            scroll = pageScroll(page);
        }

        private JComponent rulesCard(String title, String subtitle, JPanel list,
                                     JLabel count, boolean allow) {
            Widgets.RoundedPanel card = card(16, 17, 14, 17);
            card.add(Widgets.header(title, subtitle, count), BorderLayout.NORTH);
            list.setOpaque(false);
            list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
            list.setBorder(new EmptyBorder(10, 0, 0, 0));
            JScrollPane listScroll = Widgets.scroll(list);
            listScroll.setPreferredSize(new Dimension(100, 380));
            card.add(listScroll, BorderLayout.CENTER);
            return card;
        }

        private void addRule() {
            String value = domain.getText().trim();
            if (value.isEmpty()) {
                setStatus("Enter a domain or pattern first.");
                return;
            }
            int kindValue = kind.getSelectedIndex() == 1 ? FilterEngine.WILDCARD
                    : kind.getSelectedIndex() == 2 ? FilterEngine.REGEX
                    : FilterEngine.EXACT;
            boolean allow = action.getSelectedIndex() == 1;
            if (!filter.addRule(allow, kindValue, value)) {
                setStatus("That domain or pattern is not valid.");
                return;
            }
            domain.setText("");
            setStatus((allow ? "Allowed " : "Blocked ") + value);
            refresh();
        }

        @Override
        JScrollPane scroll() {
            return scroll;
        }

        @Override
        void refresh() {
            rebuild(allowList, filter.rules(true), true);
            rebuild(blockList, filter.rules(false), false);
            allowCount.setText(filter.rules(true).size() + " rules");
            blockCount.setText(filter.rules(false).size() + " rules");
        }

        private void rebuild(JPanel panel, List<FilterEngine.Rule> rules, boolean allow) {
            panel.removeAll();
            if (rules.isEmpty()) {
                panel.add(Widgets.label(allow ? "No allow rules." : "No block rules.",
                        11.5f, Font.PLAIN, 2));
            } else {
                for (FilterEngine.Rule rule : rules) {
                    JPanel row = new JPanel(new BorderLayout(8, 0));
                    row.setOpaque(false);
                    row.setAlignmentX(Component.LEFT_ALIGNMENT);
                    row.setBorder(new EmptyBorder(5, 0, 5, 0));
                    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
                    JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
                    left.setOpaque(false);
                    left.add(Widgets.label(rule.kindName(), 10, Font.BOLD, 2));
                    left.add(Widgets.label(rule.value, 11.5f, Font.PLAIN, 0));
                    row.add(left, BorderLayout.CENTER);
                    Widgets.IconButton remove = new Widgets.IconButton("trash",
                            "Remove rule", Widgets.GHOST);
                    remove.addActionListener(e -> {
                        filter.removeRule(allow, rule.kind, rule.value);
                        setStatus("Rule removed.");
                        refresh();
                    });
                    row.add(remove, BorderLayout.EAST);
                    panel.add(row);
                }
            }
            panel.revalidate();
            panel.repaint();
        }
    }

    // ---------------------------------------------------------------- settings

    private final class SettingsPage extends Page {
        private final JScrollPane scroll;
        private final JTextField dnsPort = Widgets.field("53", 7);
        private final JTextField bind = Widgets.field("0.0.0.0", 14);
        private final JComboBox<String> upstream = Widgets.combo(
                new String[]{"Encrypted DoH", "Plain DNS"}, "Encrypted DoH");
        private final JTextField dohUrl = Widgets.field("https://cloudflare-dns.com/dns-query", 28);
        private final JTextField plain = Widgets.field("1.1.1.1", 16);
        private final JComboBox<String> blockedResponse = Widgets.combo(
                new String[]{"0.0.0.0 sinkhole", "NXDOMAIN", "NODATA"}, "0.0.0.0 sinkhole");
        private final Widgets.ToggleSwitch blockSubdomains =
                new Widgets.ToggleSwitch(prefs.getBool("blockSubdomains", true));
        private final Widgets.ToggleSwitch blockCname =
                new Widgets.ToggleSwitch(prefs.getBool("blockCname", true));
        private final JTextField webPort = Widgets.field("8080", 7);
        private final JTextField webBind = Widgets.field("0.0.0.0", 14);
        private final JTextField webUser = Widgets.field("optional username", 16);
        private final JPasswordField webPassword = Widgets.passwordField(16);
        private final JLabel loginState = Widgets.label("", 11, Font.PLAIN, 1);
        private final JLabel upstreamNote = Widgets.label("", 11, Font.PLAIN, 6);
        private final Widgets.ToggleSwitch companionMode =
                new Widgets.ToggleSwitch(prefs.getBool("companionMode", false));
        private final JTextField companionServer = Widgets.field("192.168.2.133", 18);
        private final JTextField companionPort = Widgets.field("8080", 7);
        private final JTextField companionUser = Widgets.field("optional username", 16);
        private final JPasswordField companionPassword = Widgets.passwordField(16);
        private final JLabel companionState = Widgets.label("", 11, Font.PLAIN, 1);
        private volatile long companionMessageAt;
        private final Widgets.ToggleSwitch launchAtLogin =
                new Widgets.ToggleSwitch(prefs.getBool("launchAtLogin", false));
        private final Widgets.ToggleSwitch closeToTray =
                new Widgets.ToggleSwitch(prefs.getBool("closeToTray", true));

        SettingsPage() {
            VerticalPanel page = new VerticalPanel();

            // Companion
            Widgets.RoundedPanel companionCard = card(18, 18, 18, 18);
            companionCard.add(Widgets.header("Companion mode",
                    "Log in to a Hole Shield system that is already running on your network",
                    null), BorderLayout.NORTH);
            JPanel companionForm = new JPanel();
            companionForm.setOpaque(false);
            companionForm.setLayout(new BoxLayout(companionForm, BoxLayout.Y_AXIS));
            companionForm.setBorder(new EmptyBorder(12, 0, 0, 0));
            companionForm.add(toggleRow("Use companion mode", companionMode,
                    "This console mirrors another system and does not start a local resolver or dashboard."));
            JPanel connectionFields = new JPanel();
            connectionFields.setOpaque(false);
            connectionFields.setLayout(new BoxLayout(connectionFields, BoxLayout.Y_AXIS));
            connectionFields.add(formRow("Server", companionServer,
                    "Host, host:port, or a full http:// address."));
            connectionFields.add(formRow("Port", companionPort,
                    "Used when the server address has no port of its own."));
            connectionFields.add(formRow("Username", companionUser,
                    "Only needed when the remote dashboard has a login."));
            connectionFields.add(formRow("Password", companionPassword,
                    "Stored in this computer's local settings file."));
            companionForm.add(connectionFields);
            JPanel companionActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 10));
            companionActions.setOpaque(false);
            Widgets.PillButton test = new Widgets.PillButton("Test connection",
                    "wifi", Widgets.TONAL);
            test.addActionListener(e -> testCompanion());
            companionActions.add(test);
            Widgets.PillButton applyCompanion = new Widgets.PillButton(
                    "Apply settings", "check", Widgets.PRIMARY);
            applyCompanion.addActionListener(e -> apply());
            companionActions.add(applyCompanion);
            Widgets.PillButton openCompanion = new Widgets.PillButton("Open dashboard",
                    "external", Widgets.OUTLINE);
            openCompanion.addActionListener(e -> app.openDashboard());
            companionActions.add(openCompanion);
            companionForm.add(companionActions);
            Runnable updateCompanionFields = () -> {
                boolean enabled = companionMode.isSelected();
                connectionFields.setVisible(enabled);
                test.setVisible(enabled);
                openCompanion.setVisible(enabled);
                applyCompanion.setVisible(enabled);
                companionActions.setVisible(enabled);
                companionCard.setPreferredSize(null);
                fitCard(companionCard);
                updateCompanionState();
            };
            companionMode.setOnChange(selected -> {
                if (!selected && prefs.getBool("companionMode", false)) {
                    if (app.isApplying()) {
                        companionMode.setSelected(true);
                        setStatus("Settings are still applying. Try again in a moment.");
                    } else {
                        prefs.setBool("companionMode", false);
                        app.applySettings(prefs::save);
                    }
                }
                updateCompanionFields.run();
            });
            companionState.setBorder(new EmptyBorder(0, 4, 0, 0));
            companionForm.add(companionState);
            companionCard.add(companionForm, BorderLayout.CENTER);
            updateCompanionFields.run();
            page.add(companionCard);
            page.add(Box.createVerticalStrut(14));

            // Resolver
            Widgets.RoundedPanel resolver = card(18, 18, 18, 18);
            resolver.add(Widgets.header("DNS resolver",
                    "Local UDP and TCP DNS with a DoH or plain upstream", null),
                    BorderLayout.NORTH);
            JPanel form = new JPanel();
            form.setOpaque(false);
            form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
            form.setBorder(new EmptyBorder(12, 0, 0, 0));
            form.add(formRow("DNS port", dnsPort,
                    "Port 53 filters this computer; 5354 needs no admin rights."));
            form.add(formRow("Bind address", bind,
                    "0.0.0.0 serves the LAN. 127.0.0.1 is local only."));
            form.add(formRow("Upstream", upstream, null));
            upstreamNote.setBorder(new EmptyBorder(0, 156, 6, 0));
            upstreamNote.setVisible(false);
            form.add(upstreamNote);
            form.add(formRow("DoH URL", dohUrl, null));
            form.add(formRow("Plain DNS", plain, null));
            form.add(formRow("Blocked response", blockedResponse, null));
            form.add(toggleRow("Block subdomains", blockSubdomains,
                    "Match a listed parent for every subdomain."));
            form.add(toggleRow("Inspect CNAME targets", blockCname,
                    "Block a response whose CNAME points at a listed domain."));
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 10));
            actions.setOpaque(false);
            Widgets.PillButton apply = new Widgets.PillButton("Apply settings", "check",
                    Widgets.PRIMARY);
            apply.addActionListener(e -> apply());
            actions.add(apply);
            if (!app.isCompanion() && (AppDirs.isMac() || AppDirs.isLinux())) {
                Widgets.PillButton admin = new Widgets.PillButton(
                        "Restart as administrator", "lock", Widgets.OUTLINE);
                admin.addActionListener(e -> app.restartAsAdmin());
                actions.add(admin);
            }
            form.add(actions);
            resolver.add(form, BorderLayout.CENTER);
            fitCard(resolver);
            page.add(resolver);
            page.add(Box.createVerticalStrut(14));

            // Dashboard
            Widgets.RoundedPanel dashboard = card(18, 18, 18, 18);
            dashboard.add(Widgets.header("Web dashboard",
                    "Optional login for the embedded LAN dashboard", null),
                    BorderLayout.NORTH);
            JPanel dashForm = new JPanel();
            dashForm.setOpaque(false);
            dashForm.setLayout(new BoxLayout(dashForm, BoxLayout.Y_AXIS));
            dashForm.setBorder(new EmptyBorder(12, 0, 0, 0));
            dashForm.add(formRow("Dashboard port", webPort, null));
            dashForm.add(formRow("Bind address", webBind,
                    "Use 127.0.0.1 to keep the dashboard on this computer."));
            dashForm.add(formRow("Username", webUser, "Only enforced when a password is set."));
            dashForm.add(formRow("New password", webPassword,
                    "Leave blank to keep the current password."));
            JPanel dashActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 10));
            dashActions.setOpaque(false);
            Widgets.PillButton setPassword = new Widgets.PillButton("Set password",
                    "lock", Widgets.TONAL);
            setPassword.addActionListener(e -> setPassword());
            dashActions.add(setPassword);
            Widgets.PillButton removePassword = new Widgets.PillButton("Remove password",
                    "x", Widgets.GHOST);
            removePassword.addActionListener(e -> {
                prefs.set("webPassword", "");
                app.inBackground(prefs::save, null);
                webPassword.setText("");
                loginState.setText("Dashboard login is off.");
                setStatus("Dashboard password removed.");
            });
            dashActions.add(removePassword);
            Widgets.PillButton open = new Widgets.PillButton("Open dashboard",
                    "external", Widgets.OUTLINE);
            open.addActionListener(e -> app.openDashboard());
            dashActions.add(open);
            dashForm.add(dashActions);
            loginState.setBorder(new EmptyBorder(0, 4, 0, 0));
            dashForm.add(loginState);
            dashboard.add(dashForm, BorderLayout.CENTER);
            fitCard(dashboard);
            page.add(dashboard);
            page.add(Box.createVerticalStrut(14));

            // Appearance
            Widgets.RoundedPanel appearance = card(18, 18, 18, 18);
            appearance.add(Widgets.header("Appearance",
                    "Follow the operating system or pin a theme", null),
                    BorderLayout.NORTH);
            JPanel appearanceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 10));
            appearanceRow.setOpaque(false);
            String[] themes = {"System", "Dark", "Light"};
            int selectedTheme = prefs.get("theme", Theme.MODE_SYSTEM)
                    .equals(Theme.MODE_DARK) ? 1
                    : prefs.get("theme", Theme.MODE_SYSTEM).equals(Theme.MODE_LIGHT) ? 2 : 0;
            Widgets.SegmentedControl theme = new Widgets.SegmentedControl(themes, selectedTheme);
            theme.setOnChange(index -> {
                String mode = index == 1 ? Theme.MODE_DARK
                        : index == 2 ? Theme.MODE_LIGHT : Theme.MODE_SYSTEM;
                prefs.set("theme", mode);
                app.inBackground(prefs::save, null);
                app.applyTheme();
            });
            appearanceRow.add(theme);
            appearance.add(appearanceRow, BorderLayout.CENTER);
            fitCard(appearance);
            page.add(appearance);
            page.add(Box.createVerticalStrut(14));

            // System
            Widgets.RoundedPanel system = card(18, 18, 18, 18);
            system.add(Widgets.header("System",
                    "Startup, tray and platform helpers", null), BorderLayout.NORTH);
            JPanel systemForm = new JPanel();
            systemForm.setOpaque(false);
            systemForm.setLayout(new BoxLayout(systemForm, BoxLayout.Y_AXIS));
            systemForm.setBorder(new EmptyBorder(12, 0, 0, 0));
            systemForm.add(toggleRow("Launch at login", launchAtLogin,
                    "Starts minimized in the tray when you sign in."));
            systemForm.add(toggleRow("Keep running when the window closes", closeToTray,
                    "The DNS resolver and dashboard keep serving."));
            JPanel systemActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 10));
            systemActions.setOpaque(false);
            Widgets.PillButton copy = app.isCompanion()
                    ? new Widgets.PillButton("Copy dashboard address", "copy",
                    Widgets.TONAL)
                    : new Widgets.PillButton("Copy DNS setup commands", "copy",
                    Widgets.TONAL);
            copy.addActionListener(e -> {
                if (app.isCompanion()) {
                    CompanionClient client = app.companion();
                    String url = client == null ? "" : client.dashboardUrl();
                    if (url.isEmpty()) {
                        setStatus("Enter the companion server address first.");
                        return;
                    }
                    SystemIntegration.copy(url);
                    setStatus("Dashboard address copied.");
                    return;
                }
                SystemIntegration.copy(SystemIntegration.dnsCommandText(app.dnsPort()));
                setStatus("DNS setup commands copied.");
            });
            systemActions.add(copy);
            Widgets.PillButton folder = new Widgets.PillButton("Open data folder",
                    "folder", Widgets.OUTLINE);
            folder.addActionListener(e -> app.openDataFolder());
            systemActions.add(folder);
            systemForm.add(systemActions);
            system.add(systemForm, BorderLayout.CENTER);
            fitCard(system);
            page.add(system);
            page.add(Box.createVerticalStrut(14));

            // About
            Widgets.RoundedPanel about = card(18, 18, 18, 18);
            JPanel aboutCopy = new JPanel();
            aboutCopy.setOpaque(false);
            aboutCopy.setLayout(new BoxLayout(aboutCopy, BoxLayout.Y_AXIS));
            aboutCopy.add(Widgets.label(DesktopApp.NAME + " " + DesktopApp.VERSION,
                    15, Font.BOLD, 0));
            aboutCopy.add(Widgets.hint("Local DNS filtering for Windows, Linux and macOS. "
                    + "The desktop port uses the same gravity format as the Android app."));
            JPanel links = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
            links.setOpaque(false);
            Widgets.PillButton privacy = new Widgets.PillButton("Privacy", "globe",
                    Widgets.GHOST);
            privacy.addActionListener(e -> AppDirs.browse(
                    "https://dinosnore1.github.io/holeshield/"));
            links.add(privacy);
            Widgets.PillButton releases = new Widgets.PillButton("Releases", "download",
                    Widgets.GHOST);
            releases.addActionListener(e -> AppDirs.browse(
                    "https://github.com/dinosnore1/holeshield/releases"));
            links.add(releases);
            aboutCopy.add(links);
            about.add(aboutCopy, BorderLayout.CENTER);
            fitCard(about);
            page.add(about);

            scroll = pageScroll(page);
            loadValues();
        }

        private JComponent formRow(String label, JComponent field, String hint) {
            JPanel row = new JPanel(new BorderLayout(14, 0));
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(6, 0, 6, 0));
            JLabel name = Widgets.label(label, 12, Font.PLAIN, 1);
            name.setPreferredSize(new Dimension(142, 30));
            row.add(name, BorderLayout.WEST);
            JPanel value = new JPanel();
            value.setOpaque(false);
            value.setLayout(new BoxLayout(value, BoxLayout.Y_AXIS));
            field.setAlignmentX(Component.LEFT_ALIGNMENT);
            field.setMaximumSize(new Dimension(420, 34));
            value.setAlignmentX(Component.LEFT_ALIGNMENT);
            value.add(field);
            if (hint != null) {
                JLabel note = Widgets.label(hint, 10.5f, Font.PLAIN, 2);
                note.setBorder(new EmptyBorder(3, 1, 0, 0));
                value.add(note);
            }
            row.add(value, BorderLayout.CENTER);
            return row;
        }

        private JComponent toggleRow(String label, Widgets.ToggleSwitch toggle,
                                     String hint) {
            JPanel row = new JPanel(new BorderLayout(14, 0));
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(6, 0, 6, 0));
            JPanel copy = new JPanel();
            copy.setOpaque(false);
            copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
            copy.add(Widgets.label(label, 12, Font.PLAIN, 0));
            if (hint != null) copy.add(Widgets.label(hint, 10.5f, Font.PLAIN, 2));
            row.add(copy, BorderLayout.CENTER);
            JPanel control = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 2));
            control.setOpaque(false);
            control.add(toggle);
            row.add(control, BorderLayout.EAST);
            return row;
        }

        private void loadValues() {
            dnsPort.setText(String.valueOf(prefs.getInt("dnsPort", 53)));
            bind.setText(prefs.get("bindAddress", "0.0.0.0"));
            upstream.setSelectedIndex("plain".equals(prefs.get("upstreamMode", "doh"))
                    ? 1 : 0);
            dohUrl.setText(prefs.get("dohUrl", DesktopPrefs.DEFAULT_DOH));
            plain.setText(prefs.get("plainUpstream", DesktopPrefs.DEFAULT_PLAIN));
            blockedResponse.setSelectedIndex(prefs.blockedResponseMode());
            webPort.setText(String.valueOf(prefs.getInt("webPort", 8080)));
            webBind.setText(prefs.get("webBind", "0.0.0.0"));
            webUser.setText(prefs.webUsername());
            webPassword.setText("");
            loginState.setText(prefs.hasWebPassword()
                    ? "Login is on" + (prefs.webUsername().isEmpty() ? " (password only)"
                    : " (user + password)") : "Dashboard login is off.");
            companionMode.setSelected(prefs.getBool("companionMode", false));
            companionServer.setText(prefs.get("companionServer", ""));
            companionPort.setText(String.valueOf(prefs.getInt("companionPort", 8080)));
            companionUser.setText(prefs.get("companionUser", ""));
            companionPassword.setText(prefs.get("companionPassword", ""));
            updateCompanionState();
        }

        private void updateCompanionState() {
            boolean saved = prefs.getBool("companionMode", false);
            boolean selected = companionMode.isSelected();
            if (selected != saved) {
                companionState.setText(selected
                        ? "Companion mode will start when you apply settings."
                        : "Companion mode will stop when you apply settings.");
            } else if (selected) {
                companionState.setText(app.companionStatus());
            } else {
                companionState.setText("Companion mode is off. "
                        + "Turn it on and apply settings to mirror another system.");
            }
        }

        private void apply() {
            if (app.isApplying()) {
                setStatus("Settings are still applying. Try again in a moment.");
                return;
            }
            int dnsValue = parsePort(dnsPort.getText(), 53);
            int webValue = parsePort(webPort.getText(), 8080);
            if (dnsValue < 1 || dnsValue > 65535) {
                setStatus("DNS port must be between 1 and 65535.");
                return;
            }
            if (webValue < 1 || webValue > 65535) {
                setStatus("Dashboard port must be between 1 and 65535.");
                return;
            }
            int companionValue = parsePort(companionPort.getText(), 8080);
            if (companionValue < 1 || companionValue > 65535) {
                setStatus("Companion port must be between 1 and 65535.");
                return;
            }
            String companionAddress = companionServer.getText().trim();
            if (companionMode.isSelected() && companionAddress.isEmpty()) {
                setStatus("Enter the address of the Hole Shield system.");
                return;
            }
            prefs.setBool("companionMode", companionMode.isSelected());
            prefs.set("companionServer", companionAddress);
            prefs.setInt("companionPort", companionValue);
            prefs.set("companionUser", companionUser.getText().trim());
            char[] companionSecret = companionPassword.getPassword();
            prefs.set("companionPassword", new String(companionSecret));
            java.util.Arrays.fill(companionSecret, '\0');
            prefs.setInt("dnsPort", dnsValue);
            prefs.set("bindAddress", bind.getText().trim().isEmpty()
                    ? "0.0.0.0" : bind.getText().trim());
            prefs.set("upstreamMode", upstream.getSelectedIndex() == 1 ? "plain" : "doh");
            prefs.set("dohUrl", dohUrl.getText().trim());
            prefs.set("plainUpstream", plain.getText().trim());
            prefs.set("blockedResponse", String.valueOf(blockedResponse.getSelectedIndex()));
            prefs.setBool("blockSubdomains", blockSubdomains.isSelected());
            prefs.setBool("blockCname", blockCname.isSelected());
            prefs.setInt("webPort", webValue);
            prefs.set("webBind", webBind.getText().trim().isEmpty()
                    ? "0.0.0.0" : webBind.getText().trim());
            prefs.set("webUsername", webUser.getText().trim());
            prefs.setBool("launchAtLogin", launchAtLogin.isSelected());
            prefs.setBool("closeToTray", closeToTray.isSelected());
            boolean launchNow = launchAtLogin.isSelected();
            app.inBackground(() -> SystemIntegration.setLaunchAtLogin(launchNow), null);
            char[] password = webPassword.getPassword();
            String newPassword = password.length > 0 ? new String(password) : null;
            java.util.Arrays.fill(password, '\0');
            if (newPassword != null) {
                webPassword.setText("");
                loginState.setText("Login is on.");
            }
            app.applySettings(() -> {
                if (newPassword != null) prefs.setWebPassword(newPassword);
                prefs.save();
            });
            loadValues();
        }

        private void testCompanion() {
            final String server = companionServer.getText().trim();
            final int port = parsePort(companionPort.getText(), 8080);
            if (server.isEmpty()) {
                companionState.setText("Enter the server address first.");
                return;
            }
            if (port < 1 || port > 65535) {
                companionState.setText("Companion port must be between 1 and 65535.");
                return;
            }
            final String user = companionUser.getText().trim();
            final String password = new String(companionPassword.getPassword());
            companionState.setText("Connecting to " + server + "…");
            new Thread(() -> {
                String message;
                try {
                    CompanionClient client = new CompanionClient(server, port,
                            user, password);
                    client.connect();
                    CompanionClient.Snapshot snapshot = client.snapshot();
                    message = "Connected to " + client.display()
                            + (client.version().isEmpty() ? ""
                            : " · Hole Shield " + client.version())
                            + " · " + DesktopApp.formatNumber(snapshot.gravityDomains)
                            + " domains blocked"
                            + (prefs.getBool("companionMode", false) ? ""
                            : " · Press Apply settings to use it");
                } catch (Exception e) {
                    message = e.getMessage();
                }
                final String text = message;
                SwingUtilities.invokeLater(() -> {
                    companionMessageAt = System.currentTimeMillis();
                    companionState.setText(text);
                });
            }, "companion-test").start();
        }

        private void setPassword() {
            char[] password = webPassword.getPassword();
            if (password.length == 0) {
                setStatus("Enter a new password first.");
                return;
            }
            String value = new String(password);
            java.util.Arrays.fill(password, '\0');
            String user = webUser.getText().trim();
            setStatus("Saving dashboard password…");
            app.inBackground(() -> {
                prefs.setWebPassword(value);
                prefs.set("webUsername", user);
                prefs.save();
            }, () -> {
                webPassword.setText("");
                loginState.setText("Login is on.");
                setStatus("Dashboard password updated.");
            });
        }

        private int parsePort(String value, int fallback) {
            try { return Integer.parseInt(value.trim()); }
            catch (Exception e) { return fallback; }
        }

        @Override
        JScrollPane scroll() {
            return scroll;
        }

        @Override
        void refresh() {
            // Settings are form fields; do not overwrite what the user is typing.
            String warning = app.upstreamWarning();
            upstreamNote.setText(warning);
            upstreamNote.setVisible(!warning.isEmpty());
            if (System.currentTimeMillis() - companionMessageAt > 30000L) {
                updateCompanionState();
            }
            if (!loginState.getText().isEmpty()) return;
            loadValues();
        }
    }

    // -------------------------------------------------------------------- utils

    private static String stripScheme(String url) {
        if (url == null) return "";
        return url.replaceFirst("^https?://", "");
    }

    private static String relative(long at) {
        long seconds = Math.max(0, (System.currentTimeMillis() - at) / 1000);
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        if (seconds < 86400) return (seconds / 3600) + "h ago";
        return (seconds / 86400) + "d ago";
    }
}
