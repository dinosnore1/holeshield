package com.dino.holeshield;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Small Swing widget kit used by the desktop control panel. */
final class Widgets {

    private Widgets() { }

    // ------------------------------------------------------------------ labels

    static final class ThemedLabel extends JLabel {
        private final int role;
        ThemedLabel(String text, int role, int align) {
            super(text, align);
            this.role = role;
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);
        }

        @Override
        public Color getForeground() {
            Theme t = Theme.get();
            switch (role) {
                case 1: return t.muted;
                case 2: return t.faint;
                case 3: return t.accent;
                case 4: return t.danger;
                case 5: return t.onAccentSoft;
                case 6: return t.warn;
                default: return t.text;
            }
        }
    }

    static JLabel label(String text, float size, int style, int role) {
        JLabel label = new ThemedLabel(text, role, SwingConstants.LEFT);
        label.setFont(Theme.font(size, style));
        return label;
    }

    static JLabel title(String text) {
        JLabel label = label(text, 20, Font.BOLD, 0);
        return label;
    }

    static JLabel section(String text) {
        JLabel label = label(text, 11, Font.BOLD, 1);
        label.setBorder(new EmptyBorder(0, 1, 7, 0));
        return label;
    }

    static JLabel hint(String text) {
        JLabel label = label(text, 11.5f, Font.PLAIN, 1);
        label.setBorder(new EmptyBorder(4, 0, 0, 0));
        return label;
    }

    static JLabel muted(String text) {
        return label(text, 12, Font.PLAIN, 1);
    }

    // ------------------------------------------------------------------- cards

    static class RoundedPanel extends JPanel {
        private Color fill;
        private Color borderColor;
        private int radius = 18;
        private boolean shadow;

        RoundedPanel() {
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);
        }

        RoundedPanel(java.awt.LayoutManager layout) {
            super(layout);
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);
        }

        void setFill(Color color) { this.fill = color; }
        void setBorderColor(Color color) { this.borderColor = color; }
        void setRadius(int radius) { this.radius = radius; }
        void setShadow(boolean shadow) { this.shadow = shadow; }

        @Override
        protected void paintComponent(Graphics g) {
            Theme t = Theme.get();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            RoundRectangle2D shape = new RoundRectangle2D.Double(0.5, 0.5,
                    Math.max(0, w - 1), Math.max(0, h - 1), radius, radius);
            if (shadow) {
                g2.setColor(t.shadow);
                g2.fill(new RoundRectangle2D.Double(1, 2, Math.max(0, w - 2),
                        Math.max(0, h - 2), radius, radius));
            }
            g2.setColor(fill == null ? t.card : fill);
            g2.fill(shape);
            g2.setColor(borderColor == null ? t.border : borderColor);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(shape);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    static RoundedPanel card() {
        RoundedPanel panel = new RoundedPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(18, 18, 18, 18));
        return panel;
    }

    static RoundedPanel padded(java.awt.LayoutManager layout, int top, int left,
                               int bottom, int right) {
        RoundedPanel panel = new RoundedPanel(layout);
        panel.setBorder(new EmptyBorder(top, left, bottom, right));
        return panel;
    }

    static JPanel header(String title, String subtitle, JComponent right) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(label(title, 15, Font.BOLD, 0));
        if (subtitle != null && !subtitle.isEmpty()) {
            JLabel sub = hint(subtitle);
            sub.setBorder(new EmptyBorder(2, 0, 0, 0));
            copy.add(sub);
        }
        row.add(copy, BorderLayout.CENTER);
        if (right != null) row.add(right, BorderLayout.EAST);
        return row;
    }

    // ----------------------------------------------------------------- buttons

    static final int PRIMARY = 0;
    static final int TONAL = 1;
    static final int GHOST = 2;
    static final int DANGER = 3;
    static final int OUTLINE = 4;

    static class PillButton extends javax.swing.JButton {
        private final int variant;
        private final String iconName;
        private final int iconSize;
        private boolean selected;

        PillButton(String text, String iconName, int variant) {
            super(text);
            this.variant = variant;
            this.iconSize = 17;
            this.iconName = iconName;
            setFont(Theme.font(12.5f, Font.BOLD));
            setBorder(new EmptyBorder(8, 15, 8, 15));
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setOpaque(false);
            setPreferredSize(new Dimension(preferredWidth(), 36));
            setMinimumSize(new Dimension(36, 36));
        }

        PillButton(String text, String iconName) {
            this(text, iconName, TONAL);
        }

        private int preferredWidth() {
            FontMetrics fm = getFontMetrics(getFont());
            int width = fm.stringWidth(getText() == null ? "" : getText());
            if (iconName != null) width += iconSize + (getText().isEmpty() ? 0 : 8);
            return Math.max(36, width + 30);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(Math.max(size.width, preferredWidth()), 36);
        }

        void setSelectedState(boolean selected) {
            this.selected = selected;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme t = Theme.get();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            boolean hover = getModel().isRollover() && isEnabled();
            boolean down = getModel().isPressed() && isEnabled();
            Color fill;
            Color fg;
            switch (variant) {
                case PRIMARY:
                    fill = down ? Theme.mix(t.accent, t.onAccent, 0.18)
                            : hover ? Theme.mix(t.accent, t.onAccent, 0.08) : t.accent;
                    fg = t.onAccent;
                    break;
                case DANGER:
                    fill = down ? Theme.mix(t.dangerSoft, t.danger, 0.15)
                            : hover ? Theme.mix(t.dangerSoft, t.danger, 0.08) : t.dangerSoft;
                    fg = t.danger;
                    break;
                case GHOST:
                    if (selected) {
                        fill = down ? Theme.mix(t.accentSoft, t.accent, 0.10)
                                : hover ? Theme.mix(t.accentSoft, t.accent, 0.05)
                                : t.accentSoft;
                        fg = t.onAccentSoft;
                    } else {
                        fill = down ? t.cardHover : hover ? Theme.alpha(t.cardHover, 190)
                                : new Color(0, 0, 0, 0);
                        fg = t.text;
                    }
                    break;
                case OUTLINE:
                    fill = hover ? t.cardHover : new Color(0, 0, 0, 0);
                    fg = t.text;
                    break;
                default:
                    fill = down ? Theme.mix(t.accentSoft, t.accent, 0.12)
                            : hover ? Theme.mix(t.accentSoft, t.accent, 0.06) : t.accentSoft;
                    fg = t.onAccentSoft;
            }
            RoundRectangle2D shape = new RoundRectangle2D.Double(0, 0, w, h, h, h);
            g2.setColor(fill);
            g2.fill(shape);
            if (variant == OUTLINE) {
                g2.setColor(t.border);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Double(0.5, 0.5, w - 1, h - 1, h, h));
            }
            if (isFocusOwner()) {
                g2.setColor(Theme.alpha(t.accent, 170));
                g2.setStroke(new BasicStroke(2f));
                g2.draw(new RoundRectangle2D.Double(2, 2, w - 4, h - 4, h - 4, h - 4));
            }
            if (!isEnabled()) {
                g2.setColor(Theme.alpha(t.surface, 150));
                g2.fill(shape);
                fg = t.faint;
            }
            FontMetrics fm = g2.getFontMetrics(getFont());
            String text = getText() == null ? "" : getText();
            int iconW = iconName == null ? 0 : iconSize + (text.isEmpty() ? 0 : 8);
            int textW = fm.stringWidth(text);
            int total = iconW + textW;
            int x = (w - total) / 2;
            int baseline = (h - fm.getHeight()) / 2 + fm.getAscent();
            if (iconName != null) {
                Graphics2D iconG = (Graphics2D) g2.create();
                iconG.translate(x, (h - iconSize) / 2);
                Icons.paint(iconG, iconName, iconSize, fg);
                iconG.dispose();
                x += iconSize + (text.isEmpty() ? 0 : 8);
            }
            g2.setColor(fg);
            g2.setFont(getFont());
            g2.drawString(text, x, baseline);
            g2.dispose();
        }
    }

    static final class IconButton extends PillButton {
        IconButton(String iconName, String tooltip, int variant) {
            super("", iconName, variant);
            setToolTipText(tooltip);
            setPreferredSize(new Dimension(36, 36));
        }
    }

    // ------------------------------------------------------------------ toggle

    static final class ToggleSwitch extends JComponent {
        private boolean selected;
        private Consumer<Boolean> onChange;

        ToggleSwitch(boolean selected) {
            this.selected = selected;
            setPreferredSize(new Dimension(46, 26));
            setMinimumSize(new Dimension(46, 26));
            setMaximumSize(new Dimension(46, 26));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (!isEnabled()) return;
                    ToggleSwitch.this.selected = !ToggleSwitch.this.selected;
                    repaint();
                    if (onChange != null) onChange.accept(ToggleSwitch.this.selected);
                }
            });
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_SPACE
                            || e.getKeyCode() == KeyEvent.VK_ENTER) {
                        ToggleSwitch.this.selected = !ToggleSwitch.this.selected;
                        repaint();
                        if (onChange != null) onChange.accept(ToggleSwitch.this.selected);
                    }
                }
            });
        }

        void setOnChange(Consumer<Boolean> onChange) {
            this.onChange = onChange;
        }

        boolean isSelected() { return selected; }

        void setSelected(boolean selected) {
            this.selected = selected;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme t = Theme.get();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int h = Math.min(getHeight(), 26);
            int w = Math.min(getWidth(), 46);
            int y = (getHeight() - h) / 2;
            Color track = selected ? (isEnabled() ? t.accent : t.faint) : t.border;
            g2.setColor(track);
            g2.fill(new RoundRectangle2D.Double(0, y, w, h, h, h));
            int thumb = h - 6;
            int x = selected ? w - thumb - 3 : 3;
            g2.setColor(selected ? t.onAccent : Theme.mix(t.surface, t.text, 0.05));
            g2.fillOval(x, y + 3, thumb, thumb);
            if (isFocusOwner()) {
                g2.setColor(Theme.alpha(t.accent, 150));
                g2.setStroke(new BasicStroke(2f));
                g2.draw(new RoundRectangle2D.Double(1, y + 1, w - 2, h - 2, h, h));
            }
            g2.dispose();
        }
    }

    // --------------------------------------------------------------- navigation

    static final class NavItem extends JComponent {
        private final String title;
        private final Icon icon;
        private boolean selected;
        private boolean hover;
        private Runnable onClick;

        NavItem(String title, String iconName) {
            this.title = title;
            this.icon = Icons.icon(iconName, 19, 2);
            setPreferredSize(new Dimension(190, 44));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (onClick != null) onClick.run();
                }
            });
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER
                            || e.getKeyCode() == KeyEvent.VK_SPACE) {
                        if (onClick != null) onClick.run();
                    }
                }
            });
        }

        void setOnClick(Runnable onClick) { this.onClick = onClick; }

        void setSelected(boolean selected) {
            this.selected = selected;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme t = Theme.get();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            if (selected) {
                g2.setColor(t.accentSoft);
                g2.fill(new RoundRectangle2D.Double(0, 0, w, h, 14, 14));
                g2.setColor(t.accent);
                g2.fill(new RoundRectangle2D.Double(0, 11, 3, h - 22, 3, 3));
            } else if (hover) {
                g2.setColor(t.cardHover);
                g2.fill(new RoundRectangle2D.Double(0, 0, w, h, 14, 14));
            }
            Icon tinted = Icons.icon(selected ? "shield-check" : iconName(),
                    19, selected ? 5 : 2);
            if ("dashboard".equals(iconName()) || "list".equals(iconName())
                    || "rules".equals(iconName()) || "settings".equals(iconName())) {
                tinted = Icons.icon(iconName(), 19, selected ? 5 : 2);
            }
            tinted.paintIcon(this, g2, 15, (h - 19) / 2);
            g2.setColor(selected ? t.onAccentSoft : t.muted);
            g2.setFont(Theme.font(13f, selected ? Font.BOLD : Font.PLAIN));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(title, 44, (h - fm.getHeight()) / 2 + fm.getAscent());
            if (isFocusOwner()) {
                g2.setColor(Theme.alpha(t.accent, 130));
                g2.setStroke(new BasicStroke(2f));
                g2.draw(new RoundRectangle2D.Double(1, 1, w - 2, h - 2, 13, 13));
            }
            g2.dispose();
        }

        private String iconName() {
            if (icon == null) return "shield";
            if (title.equals("Overview")) return "dashboard";
            if (title.equals("Query log")) return "list";
            if (title.equals("Blocklists")) return "shield";
            if (title.equals("Rules")) return "rules";
            return "settings";
        }
    }

    // -------------------------------------------------------------- stat cards

    static final class StatCard extends RoundedPanel {
        private final String label;
        private final String iconName;
        private final int tint;
        private String value = "0";
        private String hint = "";

        StatCard(String label, String iconName, int tint) {
            this.label = label;
            this.iconName = iconName;
            this.tint = tint;
            setBorder(new EmptyBorder(15, 16, 15, 16));
            setPreferredSize(new Dimension(180, 104));
        }

        void setValue(String value) {
            this.value = value == null ? "0" : value;
            repaint();
        }

        void setHint(String hint) {
            this.hint = hint == null ? "" : hint;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Theme t = Theme.get();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            Color accent = tint == 1 ? t.danger : tint == 2 ? t.warn : t.accent;
            g2.setColor(Theme.alpha(accent, t.dark ? 34 : 28));
            g2.fill(new RoundRectangle2D.Double(14, 13, 30, 30, 10, 10));
            Graphics2D ig = (Graphics2D) g2.create();
            ig.translate(20, 19);
            Icons.paint(ig, iconName, 18, accent);
            ig.dispose();

            if (!hint.isEmpty()) {
                g2.setColor(t.faint);
                g2.setFont(Theme.font(10f, Font.PLAIN));
                int hintWidth = g2.getFontMetrics().stringWidth(hint);
                g2.drawString(hint, getWidth() - hintWidth - 14, 25);
            }
            g2.setColor(t.text);
            g2.setFont(Theme.font(27, Font.BOLD));
            g2.drawString(value, 14, 76);
            g2.setColor(t.muted);
            g2.setFont(Theme.font(11.5f, Font.PLAIN));
            g2.drawString(label, 14, 93);
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------- chart

    static final class ChartPanel extends JComponent {
        private List<StatsStore.Bucket> data = new ArrayList<>();
        private String range = "24h";
        private int hover = -1;

        ChartPanel() {
            setPreferredSize(new Dimension(100, 250));
            setMinimumSize(new Dimension(100, 190));
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int next = indexAt(e.getX());
                    if (next != hover) {
                        hover = next;
                        repaint();
                    }
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseExited(MouseEvent e) {
                    hover = -1;
                    repaint();
                }
            });
        }

        void setData(List<StatsStore.Bucket> data, String range) {
            this.data = data == null ? new ArrayList<>() : data;
            this.range = range;
            repaint();
        }

        private int indexAt(int x) {
            if (data.size() < 2) return data.isEmpty() ? -1 : 0;
            int left = 44;
            int right = 14;
            int width = Math.max(1, getWidth() - left - right);
            int index = (int) Math.round((x - left) * (data.size() - 1.0) / width);
            return Math.max(0, Math.min(data.size() - 1, index));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme t = Theme.get();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int left = 44;
            int right = 14;
            int top = 12;
            int bottom = 26;
            int plotW = Math.max(1, w - left - right);
            int plotH = Math.max(1, h - top - bottom);
            g2.setFont(Theme.font(10, Font.PLAIN));
            if (data.isEmpty()) {
                g2.setColor(t.faint);
                String text = "No queries yet.";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(text, (w - fm.stringWidth(text)) / 2,
                        top + plotH / 2);
                g2.dispose();
                return;
            }
            long maxValue = 1;
            for (StatsStore.Bucket b : data) {
                maxValue = Math.max(maxValue, Math.max(b.queries, b.blocked));
            }
            long nice = niceMax(maxValue);
            for (int i = 0; i <= 4; i++) {
                long value = nice * i / 4;
                int y = top + plotH - (int) (plotH * (double) value / nice);
                g2.setColor(Theme.alpha(t.border, 150));
                g2.drawLine(left, y, left + plotW, y);
                g2.setColor(t.faint);
                String label = compact(value);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(label, left - 7 - fm.stringWidth(label), y + 3);
            }
            double slot = plotW / Math.max(1.0, data.size() - 1.0);
            Path2D area = new Path2D.Double();
            Path2D queries = new Path2D.Double();
            Path2D blocked = new Path2D.Double();
            for (int i = 0; i < data.size(); i++) {
                double x = left + slot * i;
                double yq = top + plotH - plotH * (double) data.get(i).queries / nice;
                double yb = top + plotH - plotH * (double) data.get(i).blocked / nice;
                if (i == 0) {
                    queries.moveTo(x, yq);
                    blocked.moveTo(x, yb);
                    area.moveTo(x, yq);
                } else {
                    queries.lineTo(x, yq);
                    blocked.lineTo(x, yb);
                    area.lineTo(x, yq);
                }
            }
            area.lineTo(left + slot * (data.size() - 1), top + plotH);
            area.lineTo(left, top + plotH);
            area.closePath();
            g2.setPaint(new GradientPaint(left, top, Theme.alpha(t.accent, 100),
                    left, top + plotH, Theme.alpha(t.accent, 0)));
            g2.fill(area);
            g2.setStroke(new BasicStroke(2.1f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            g2.setColor(t.accent);
            g2.draw(queries);
            g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            g2.setColor(t.danger);
            g2.draw(blocked);

            g2.setFont(Theme.font(10, Font.PLAIN));
            g2.setColor(t.faint);
            String start = rangeLabel(0);
            String end = range.equals("live") ? "now"
                    : rangeLabel(data.size() - 1);
            g2.drawString(start, left, h - 8);
            int endWidth = g2.getFontMetrics().stringWidth(end);
            g2.drawString(end, left + plotW - endWidth, h - 8);

            if (hover >= 0 && hover < data.size()) {
                double x = left + slot * hover;
                g2.setColor(Theme.alpha(t.muted, 110));
                g2.setStroke(new BasicStroke(1f));
                g2.drawLine((int) x, top, (int) x, top + plotH);
                StatsStore.Bucket b = data.get(hover);
                double yq = top + plotH - plotH * (double) b.queries / nice;
                double yb = top + plotH - plotH * (double) b.blocked / nice;
                g2.setColor(t.accent);
                g2.fillOval((int) x - 4, (int) yq - 4, 8, 8);
                g2.setColor(t.danger);
                g2.fillOval((int) x - 3, (int) yb - 3, 6, 6);
                String label = rangeLabel(hover) + "  ·  " + b.queries
                        + " queries  ·  " + b.blocked + " blocked";
                g2.setFont(Theme.font(10.5f, Font.PLAIN));
                FontMetrics fm = g2.getFontMetrics();
                int labelW = fm.stringWidth(label) + 16;
                int lx = (int) Math.max(4, Math.min(w - labelW - 4, x - labelW / 2.0));
                g2.setColor(Theme.alpha(t.surface, 240));
                g2.fillRoundRect(lx, 2, labelW, 20, 8, 8);
                g2.setColor(t.text);
                g2.drawString(label, lx + 8, 16);
            }
            g2.dispose();
        }

        private String rangeLabel(int index) {
            if (index < 0 || index >= data.size()) return "";
            java.util.Date date = new java.util.Date(data.get(index).start);
            java.text.SimpleDateFormat fmt;
            if ("week".equals(range) || "month".equals(range)) {
                fmt = new java.text.SimpleDateFormat("MMM d", java.util.Locale.US);
            } else {
                fmt = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
            }
            return fmt.format(date);
        }

        private static long niceMax(long value) {
            if (value <= 4) return 4;
            double pow = Math.pow(10, Math.floor(Math.log10(value)));
            double n = value / pow;
            double step = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10;
            return (long) (step * pow);
        }

        private static String compact(long value) {
            if (value >= 1_000_000) return String.format(java.util.Locale.US, "%.1fM",
                    value / 1_000_000.0);
            if (value >= 1000) return String.format(java.util.Locale.US, "%.1fk",
                    value / 1000.0);
            return String.valueOf(value);
        }
    }

    // ------------------------------------------------------------------ bar list

    static final class BarList extends JComponent {
        private List<StatsStore.Top> rows = new ArrayList<>();
        private String empty = "Nothing yet.";
        private boolean danger;

        BarList() {
            setPreferredSize(new Dimension(100, 140));
        }

        void setData(List<StatsStore.Top> rows, String empty) {
            this.rows = rows == null ? new ArrayList<>() : rows;
            this.empty = empty;
            setPreferredSize(new Dimension(100, Math.max(54, rows.size() * 30 + 8)));
            revalidate();
            repaint();
        }

        void setDanger(boolean danger) { this.danger = danger; }

        @Override
        protected void paintComponent(Graphics g) {
            Theme t = Theme.get();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            if (rows.isEmpty()) {
                g2.setColor(t.faint);
                g2.setFont(Theme.font(11.5f, Font.PLAIN));
                g2.drawString(empty, 2, 22);
                g2.dispose();
                return;
            }
            int max = 1;
            for (StatsStore.Top row : rows) max = Math.max(max, row.count);
            int y = 4;
            for (StatsStore.Top row : rows) {
                g2.setFont(Theme.font(11.5f, Font.PLAIN));
                g2.setColor(t.text);
                String name = row.key == null ? "" : row.key;
                FontMetrics fm = g2.getFontMetrics();
                int nameW = Math.max(80, getWidth() * 42 / 100);
                String shown = name;
                while (fm.stringWidth(shown) > nameW - 8 && shown.length() > 4) {
                    shown = shown.substring(0, shown.length() - 2) + "…";
                }
                g2.drawString(shown, 0, y + 13);
                int barX = nameW;
                int barW = Math.max(30, getWidth() - nameW - 46);
                g2.setColor(t.surfaceAlt);
                g2.fillRoundRect(barX, y + 4, barW, 8, 8, 8);
                g2.setColor(danger ? t.danger : t.accent);
                int fill = Math.max(4, (int) (barW * (double) row.count / max));
                g2.fillRoundRect(barX, y + 4, fill, 8, 8, 8);
                String count = String.valueOf(row.count);
                g2.setColor(t.muted);
                int countW = fm.stringWidth(count);
                g2.drawString(count, getWidth() - countW, y + 13);
                y += 30;
            }
            g2.dispose();
        }
    }

    // -------------------------------------------------------------- segmented

    static final class SegmentedControl extends JPanel {
        private final List<PillButton> buttons = new ArrayList<>();
        private int selected;
        private Consumer<Integer> onChange;

        SegmentedControl(String[] options, int selected) {
            super(new FlowLayout(FlowLayout.LEFT, 4, 0));
            setOpaque(false);
            this.selected = selected;
            for (int i = 0; i < options.length; i++) {
                final int index = i;
                PillButton button = new PillButton(options[i], null, GHOST);
                button.setSelectedState(i == selected);
                button.setFont(Theme.font(11.5f, Font.BOLD));
                button.setPreferredSize(new Dimension(
                        button.getPreferredSize().width, 30));
                button.addActionListener(e -> select(index, true));
                buttons.add(button);
                add(button);
            }
        }

        void setOnChange(Consumer<Integer> onChange) {
            this.onChange = onChange;
        }

        void select(int index, boolean notify) {
            selected = index;
            rebuildVariants();
            if (notify && onChange != null) onChange.accept(index);
        }

        int selected() { return selected; }

        private void rebuildVariants() {
            // The visual selected state is painted through the button model.
            for (int i = 0; i < buttons.size(); i++) {
                buttons.get(i).setSelectedState(i == selected);
            }
        }
    }

    // ------------------------------------------------------------------- table

    static void styleTable(JTable table) {
        Theme t = Theme.get();
        table.setBackground(t.surface);
        table.setForeground(t.text);
        table.setFont(Theme.font(12f));
        table.setRowHeight(36);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.setGridColor(Theme.alpha(t.border, 120));
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setSelectionBackground(t.accentSoft);
        table.setSelectionForeground(t.onAccentSoft);
        table.getTableHeader().setBackground(t.surfaceAlt);
        table.getTableHeader().setForeground(t.muted);
        table.getTableHeader().setFont(Theme.font(11, Font.BOLD));
        table.getTableHeader().setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, t.border));
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setPreferredSize(new Dimension(100, 34));
        table.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean selected, boolean focus,
                                                           int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(table, value,
                        selected, focus, row, column);
                Theme theme = Theme.get();
                label.setOpaque(true);
                label.setBackground(theme.surfaceAlt);
                label.setForeground(theme.muted);
                label.setFont(Theme.font(10.5f, Font.BOLD));
                label.setBorder(new EmptyBorder(0, 11, 0, 8));
                return label;
            }
        });
        table.setAutoCreateRowSorter(false);
    }

    static JScrollPane scroll(Component view) {
        Theme t = Theme.get();
        JScrollPane pane = new JScrollPane(view);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.setOpaque(false);
        pane.getViewport().setOpaque(true);
        pane.getViewport().setBackground(t.bg);
        pane.getVerticalScrollBar().setUnitIncrement(18);
        pane.getHorizontalScrollBar().setUnitIncrement(18);
        pane.setBackground(t.bg);
        return pane;
    }

    static JScrollPane scrollSurface(Component view) {
        JScrollPane pane = scroll(view);
        pane.getViewport().setBackground(Theme.get().surface);
        return pane;
    }

    // ------------------------------------------------------------------- input

    static final class PlaceholderField extends JTextField {
        private String placeholder;

        PlaceholderField(int columns) {
            super(columns);
        }

        void setPlaceholder(String placeholder) {
            this.placeholder = placeholder;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (placeholder == null || placeholder.isEmpty()) return;
            if (getText() != null && !getText().isEmpty()) return;
            if (isFocusOwner()) return;
            Theme t = Theme.get();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(t.faint);
            g2.setFont(getFont());
            Insets insets = getInsets();
            java.awt.FontMetrics fm = g2.getFontMetrics();
            int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(placeholder, insets.left, y);
            g2.dispose();
        }
    }

    static JTextField field(String placeholder, int columns) {
        Theme t = Theme.get();
        PlaceholderField field = new PlaceholderField(columns);
        field.setPlaceholder(placeholder);
        field.setFont(Theme.font(12.5f));
        field.setBackground(t.surfaceAlt);
        field.setForeground(t.text);
        field.setCaretColor(t.accent);
        field.setSelectionColor(t.accentSoft);
        field.setSelectedTextColor(t.onAccentSoft);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(t.border),
                new EmptyBorder(7, 10, 7, 10)));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                field.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Theme.get().accent),
                        new EmptyBorder(7, 10, 7, 10)));
            }

            @Override
            public void focusLost(FocusEvent e) {
                field.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Theme.get().border),
                        new EmptyBorder(7, 10, 7, 10)));
            }
        });
        return field;
    }

    static JPasswordField passwordField(int columns) {
        Theme t = Theme.get();
        JPasswordField field = new JPasswordField(columns);
        field.setFont(Theme.font(12.5f));
        field.setBackground(t.surfaceAlt);
        field.setForeground(t.text);
        field.setCaretColor(t.accent);
        field.setSelectionColor(t.accentSoft);
        field.setSelectedTextColor(t.onAccentSoft);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(t.border),
                new EmptyBorder(7, 10, 7, 10)));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                field.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Theme.get().accent),
                        new EmptyBorder(7, 10, 7, 10)));
            }

            @Override
            public void focusLost(FocusEvent e) {
                field.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Theme.get().border),
                        new EmptyBorder(7, 10, 7, 10)));
            }
        });
        return field;
    }

    static JComboBox<String> combo(String[] items, String selected) {
        Theme t = Theme.get();
        JComboBox<String> combo = new JComboBox<>(items);
        combo.setFont(Theme.font(12.5f));
        combo.setBackground(t.surfaceAlt);
        combo.setForeground(t.text);
        combo.setFocusable(true);
        combo.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(t.border),
                new EmptyBorder(3, 8, 3, 8)));
        combo.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
            @Override
            protected javax.swing.JButton createArrowButton() {
                javax.swing.JButton button = new javax.swing.JButton(
                        Icons.icon("chevron-down", 12, 2));
                button.setBorder(BorderFactory.createEmptyBorder());
                button.setContentAreaFilled(false);
                button.setFocusable(false);
                button.setOpaque(false);
                button.setPreferredSize(new Dimension(22, 22));
                return button;
            }
        });
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean selected,
                                                          boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value,
                        index, selected, focus);
                Theme theme = Theme.get();
                label.setBorder(new EmptyBorder(5, 9, 5, 9));
                label.setFont(Theme.font(12.5f));
                label.setOpaque(true);
                label.setBackground(selected ? theme.accentSoft : theme.surface);
                label.setForeground(selected ? theme.onAccentSoft : theme.text);
                return label;
            }
        });
        if (selected != null) combo.setSelectedItem(selected);
        return combo;
    }

    static JPanel row(int gap, Component... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, gap, 0));
        row.setOpaque(false);
        for (Component component : components) row.add(component);
        return row;
    }

    static JPanel column(int gap, Component... components) {
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        for (int i = 0; i < components.length; i++) {
            if (i > 0) column.add(Box.createVerticalStrut(gap));
            column.add(components[i]);
        }
        return column;
    }

    static Component spacer(int width, int height) {
        return Box.createRigidArea(new Dimension(width, height));
    }

    static JPanel alignLeft(JComponent component) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.add(component, BorderLayout.WEST);
        return row;
    }

    static JLabel badge(String text, boolean danger) {
        JLabel label = new ThemedLabel(text, danger ? 4 : 3, SwingConstants.LEFT);
        label.setFont(Theme.font(10.5f, Font.BOLD));
        label.setOpaque(false);
        label.setBorder(new EmptyBorder(2, 7, 2, 7));
        return label;
    }

    static JPanel divider() {
        JPanel divider = new JPanel();
        divider.setOpaque(false);
        divider.setPreferredSize(new Dimension(1, 1));
        divider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        divider.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.get().border));
        return divider;
    }

    static void gap(JPanel panel, int height) {
        panel.add(Box.createVerticalStrut(height));
    }

    static void insets(JComponent component, int top, int left, int bottom, int right) {
        component.setBorder(new EmptyBorder(top, left, bottom, right));
    }

    static Insets insets(int all) {
        return new Insets(all, all, all, all);
    }
}
