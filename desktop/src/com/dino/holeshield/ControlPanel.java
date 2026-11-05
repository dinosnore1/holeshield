package com.dino.holeshield;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
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
import java.util.function.Consumer;

/** Native Swing control panel for the desktop resolver. */
final class ControlPanel extends JFrame {

    private final DesktopApp app;
    private final DesktopPrefs prefs;
    private final FilterEngine filter;
    private final StatsStore stats;
    private final DnsService dns;

    private final Map<String, Widgets.NavItem> navItems = new LinkedHashMap<>();
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private JPanel content;
    private CardLayout cards;
    private String currentPage = "Overview";

    private JLabel statusText;
    private JLabel statusDot;
    private Widgets.PillButton startStop;
    private JLabel footerLeft;
    private JLabel footerRight;
    private JLabel versionLabel;
    private Widgets.RoundedPanel statusPill;
    private Timer timer;
    private int tick;

    ControlPanel(DesktopApp app) {
        super(DesktopApp.NAME);
        this.app = app;
        this.prefs = app.prefs();
        this.filter = app.filter();
        this.stats = app.stats();
        this.dns = app.dns();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1000, 680));
        setSize(1180, 820);
        setLocationRelativeTo(null);
        setIconImage(Icons.image("shield-fill", 64, Theme.get().accent));
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
            if (!isVisible()) return;
            tick++;
            refreshHeader();
            Page page = pages.get(currentPage);
            if (page != null) page.refresh();
        });
        timer.start();
    }

    // ------------------------------------------------------------------- shell

    private void build() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.get().bg);
        setContentPane(root);

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildNav(), BorderLayout.WEST);

        cards = new CardLayout();
        content = new JPanel(cards);
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(0, 0, 0, 0));

        OverviewPage overview = new OverviewPage();
        QueriesPage queries = new QueriesPage();
        ListsPage lists = new ListsPage();
        RulesPage rules = new RulesPage();
        SettingsPage settings = new SettingsPage();
        addPage("Overview", overview);
        addPage("Query log", queries);
        addPage("Blocklists", lists);
        addPage("Rules", rules);
        addPage("Settings", settings);
        root.add(content, BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        selectPage(currentPage);
    }

    private void addPage(String name, Page page) {
        pages.put(name, page);
        content.add(page.scroll(), name);
    }

    private JPanel buildHeader() {
        Theme t = Theme.get();
        JPanel header = new JPanel(new BorderLayout(18, 0));
        header.setBackground(t.bg);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, t.border),
                new EmptyBorder(14, 22, 14, 22)));

        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        brand.setOpaque(false);
        Widgets.RoundedPanel mark = new Widgets.RoundedPanel(new BorderLayout());
        mark.setFill(t.accent);
        mark.setBorderColor(t.accent);
        mark.setRadius(14);
        mark.setPreferredSize(new Dimension(42, 42));
        JLabel icon = new JLabel(Icons.icon("shield-check", 24, 4));
        icon.setHorizontalAlignment(SwingConstants.CENTER);
        mark.add(icon, BorderLayout.CENTER);
        brand.add(mark);

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setAlignmentX(Component.LEFT_ALIGNMENT);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(Widgets.label(DesktopApp.NAME, 17, Font.BOLD, 0));
        copy.add(Widgets.label("Desktop DNS filter", 11.5f, Font.PLAIN, 1));
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

        Widgets.IconButton dashboard = new Widgets.IconButton("external",
                "Open the web dashboard", Widgets.TONAL);
        dashboard.addActionListener(e -> app.openDashboard());
        right.add(dashboard);

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
        wrapper.setBorder(new EmptyBorder(18, 16, 18, 6));
        wrapper.setPreferredSize(new Dimension(222, 100));

        Widgets.RoundedPanel nav = new Widgets.RoundedPanel(new BorderLayout());
        nav.setBorder(new EmptyBorder(12, 10, 12, 10));
        JPanel items = new JPanel();
        items.setOpaque(false);
        items.setLayout(new BoxLayout(items, BoxLayout.Y_AXIS));
        items.add(Widgets.section("WORKSPACE"));
        String[][] entries = {
                {"Overview", "dashboard"},
                {"Query log", "list"},
                {"Blocklists", "shield"},
                {"Rules", "rules"},
                {"Settings", "settings"}
        };
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
        foot.add(Widgets.label("Local DNS · no cloud", 10.5f, Font.PLAIN, 2));
        nav.add(foot, BorderLayout.SOUTH);
        wrapper.add(nav, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel buildFooter() {
        Theme t = Theme.get();
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setBackground(t.bg);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, t.border),
                new EmptyBorder(8, 22, 8, 22)));
        footerLeft = Widgets.label("Starting…", 11, Font.PLAIN, 1);
        footerRight = Widgets.label("", 11, Font.PLAIN, 2);
        footer.add(footerLeft, BorderLayout.WEST);
        footer.add(footerRight, BorderLayout.EAST);
        return footer;
    }

    private void selectPage(String name) {
        if (!pages.containsKey(name)) return;
        currentPage = name;
        cards.show(content, name);
        for (Map.Entry<String, Widgets.NavItem> entry : navItems.entrySet()) {
            entry.getValue().setSelected(entry.getKey().equals(name));
        }
        Page page = pages.get(name);
        if (page != null) page.refresh();
    }

    // ----------------------------------------------------------------- refresh

    void refreshHeader() {
        boolean running = app.isDnsRunning();
        boolean blocking = app.isBlocking();
        Theme t = Theme.get();
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
            footerLeft.setText(app.isDnsRunning()
                    ? "DNS listening on " + app.bindSummary()
                    : app.dnsError());
        }
    }

    void setStatus(String text) {
        if (footerLeft != null) footerLeft.setText(text == null ? "" : text);
    }

    void openPage(String name) {
        if (name != null && pages.containsKey(name)) selectPage(name);
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
    }

    // ------------------------------------------------------------------- pages

    private abstract static class Page {
        abstract JScrollPane scroll();
        abstract void refresh();
    }

    private static final class VerticalPanel extends JPanel implements Scrollable {
        VerticalPanel() {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(new EmptyBorder(20, 22, 24, 22));
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

    // ------------------------------------------------------------------ overview

    private final class OverviewPage extends Page {
        private final JScrollPane scroll;
        private final JLabel heroTitle = Widgets.label("Hole Shield", 22, Font.BOLD, 0);
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
            Widgets.RoundedPanel card = card(20, 22, 20, 22);
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
            JPanel row = new JPanel(new GridLayout(1, 4, 12, 0));
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

        private JComponent chartCard() {
            Widgets.RoundedPanel card = card(17, 18, 12, 18);
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
            JPanel row = new JPanel(new GridLayout(1, 3, 12, 0));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(listCard("Top blocked", "Most blocked lookups", topBlocked));
            row.add(listCard("Top clients", "Devices using this resolver", topClients));
            row.add(listCard("Query types", "A, AAAA, HTTPS and more", types));
            row.setPreferredSize(new Dimension(100, 236));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 236));
            return row;
        }

        private JComponent listCard(String title, String subtitle,
                                    Widgets.BarList list) {
            Widgets.RoundedPanel card = card(16, 17, 12, 17);
            card.add(Widgets.header(title, subtitle, null), BorderLayout.NORTH);
            list.setBorder(new EmptyBorder(10, 0, 0, 0));
            card.add(list, BorderLayout.CENTER);
            return card;
        }

        private JComponent categoryCard() {
            Widgets.RoundedPanel card = card(16, 17, 14, 17);
            card.add(Widgets.header("Blocked by category",
                    "Categories matched by gravity and custom rules", null),
                    BorderLayout.NORTH);
            categories.setBorder(new EmptyBorder(10, 0, 0, 0));
            card.add(categories, BorderLayout.CENTER);
            card.setPreferredSize(new Dimension(100, 196));
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 210));
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
            upstreamChip.setText(prefs.upstreamLabel());
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
            if (++chartTick % 2 == 0) refreshChart();
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

    // ----------------------------------------------------------------- queries

    private final class QueriesPage extends Page {
        private final JScrollPane scroll;
        private final JTextField search = Widgets.field("Search domain or client", 24);
        private final JComboBox<String> filterCombo = Widgets.combo(
                new String[]{"All queries", "Blocked only", "Allowed only"}, "All queries");
        private final JLabel count = Widgets.label("", 11.5f, Font.PLAIN, 1);
        private final QueryModel model = new QueryModel();
        private final JTable table = new JTable(model);
        private final Timer debounce;
        private int filterMode;

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
            Widgets.styleTable(table);
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
            model.setRows(rows);
            count.setText(rows.size() + " shown");
        }
    }

    private static final class QueryModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Time", "Client", "Domain", "Category", "Type", "Status",
                "Upstream", "ms", "Rule"
        };
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
                case 0: return new java.text.SimpleDateFormat("HH:mm:ss",
                        Locale.US).format(new java.util.Date(e.at));
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

            Widgets.RoundedPanel updateCard = card(17, 18, 17, 18);
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
            updateCard.add(update, BorderLayout.EAST);
            updateCard.setPreferredSize(new Dimension(100, 100));
            updateCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
            page.add(updateCard);
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
            addCategory(rows, "Other", "categoryOther");
            cats.add(rows, BorderLayout.CENTER);
            cats.setPreferredSize(new Dimension(100, 282));
            cats.setMaximumSize(new Dimension(Integer.MAX_VALUE, 282));
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
            Widgets.ToggleSwitch toggle = new Widgets.ToggleSwitch(prefs.getBool(key, true));
            toggle.setOnChange(value -> {
                prefs.setBool(key, value);
                prefs.save();
                filter.reloadRules();
                refresh();
            });
            left.add(toggle);
            left.add(Widgets.label(label, 12.5f, Font.BOLD, 0));
            row.add(left, BorderLayout.WEST);
            JLabel count = Widgets.label("0 domains", 11, Font.PLAIN, 1);
            row.add(count, BorderLayout.EAST);
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

            JPanel columns = new JPanel(new GridLayout(1, 2, 12, 0));
            columns.setOpaque(false);
            columns.setAlignmentX(Component.LEFT_ALIGNMENT);
            columns.add(rulesCard("Allowed domains", "Never filtered", allowList,
                    allowCount, true));
            columns.add(rulesCard("Blocked domains", "Always sinkholed", blockList,
                    blockCount, false));
            columns.setPreferredSize(new Dimension(100, 500));
            columns.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
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
                    row.setBorder(new EmptyBorder(5, 0, 5, 0));
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
        private final Widgets.ToggleSwitch launchAtLogin =
                new Widgets.ToggleSwitch(prefs.getBool("launchAtLogin", false));
        private final Widgets.ToggleSwitch closeToTray =
                new Widgets.ToggleSwitch(prefs.getBool("closeToTray", true));

        SettingsPage() {
            VerticalPanel page = new VerticalPanel();

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
            if (AppDirs.isMac() || AppDirs.isLinux()) {
                Widgets.PillButton admin = new Widgets.PillButton(
                        "Restart as administrator", "lock", Widgets.OUTLINE);
                admin.addActionListener(e -> app.restartAsAdmin());
                actions.add(admin);
            }
            form.add(actions);
            resolver.add(form, BorderLayout.CENTER);
            resolver.setPreferredSize(new Dimension(100, 560));
            resolver.setMaximumSize(new Dimension(Integer.MAX_VALUE, 620));
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
                prefs.save();
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
            dashboard.setPreferredSize(new Dimension(100, 340));
            dashboard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 400));
            page.add(dashboard);
            page.add(Box.createVerticalStrut(14));

            // Appearance
            Widgets.RoundedPanel appearance = card(18, 18, 18, 18);
            appearance.add(Widgets.header("Appearance",
                    "Follow the operating system or pin a theme", null),
                    BorderLayout.NORTH);
            JPanel appearanceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 14));
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
                prefs.save();
                app.applyTheme();
            });
            appearanceRow.add(theme);
            appearance.add(appearanceRow, BorderLayout.CENTER);
            appearance.setPreferredSize(new Dimension(100, 106));
            appearance.setMaximumSize(new Dimension(Integer.MAX_VALUE, 106));
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
            Widgets.PillButton copy = new Widgets.PillButton("Copy DNS setup commands",
                    "copy", Widgets.TONAL);
            copy.addActionListener(e -> {
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
            system.setPreferredSize(new Dimension(100, 196));
            system.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));
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
                    "https://dinosnore1.github.io/holeshield-privacy/"));
            links.add(privacy);
            Widgets.PillButton releases = new Widgets.PillButton("Releases", "download",
                    Widgets.GHOST);
            releases.addActionListener(e -> AppDirs.browse(
                    "https://github.com/dinosnore1/pihole-tv/releases"));
            links.add(releases);
            aboutCopy.add(links);
            about.add(aboutCopy, BorderLayout.CENTER);
            about.setPreferredSize(new Dimension(100, 132));
            about.setMaximumSize(new Dimension(Integer.MAX_VALUE, 132));
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
        }

        private void apply() {
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
            SystemIntegration.setLaunchAtLogin(launchAtLogin.isSelected());
            char[] password = webPassword.getPassword();
            if (password.length > 0) {
                prefs.setWebPassword(new String(password));
                java.util.Arrays.fill(password, '\0');
                webPassword.setText("");
                loginState.setText("Login is on.");
            }
            prefs.save();
            app.applySettings();
            loadValues();
            setStatus("Settings applied.");
        }

        private void setPassword() {
            char[] password = webPassword.getPassword();
            if (password.length == 0) {
                setStatus("Enter a new password first.");
                return;
            }
            prefs.setWebPassword(new String(password));
            java.util.Arrays.fill(password, '\0');
            prefs.set("webUsername", webUser.getText().trim());
            prefs.save();
            webPassword.setText("");
            loginState.setText("Login is on.");
            setStatus("Dashboard password updated.");
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
