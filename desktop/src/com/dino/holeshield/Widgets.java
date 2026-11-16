package com.dino.holeshield;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSliderUI;
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
import java.awt.Rectangle;
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
        private int radius = 16;
        private boolean shadow;
        private boolean clipChildren;

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

        /** Keeps square children (tables, scroll panes) inside the rounded edge. */
        void setClipChildren(boolean clipChildren) { this.clipChildren = clipChildren; }

        @Override
        protected void paintChildren(Graphics g) {
            if (!clipChildren) {
                super.paintChildren(g);
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.clip(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(),
                    radius, radius));
            super.paintChildren(g2);
            g2.dispose();
        }

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
                // Two stacked offsets give a soft edge without a blur filter.
                for (int i = 2; i >= 1; i--) {
                    g2.setColor(Theme.alpha(t.shadow, t.shadow.getAlpha() / (i * 2)));
                    g2.fill(new RoundRectangle2D.Double(0.5, i + 0.5,
                            Math.max(0, w - 1), Math.max(0, h - 1), radius, radius));
                }
            }
            g2.setColor(fill == null ? t.card : fill);
            g2.fill(shape);
            g2.setColor(borderColor == null ? Theme.alpha(t.border, 210) : borderColor);
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
        private double hoverAmt;
        private double pressAmt;
        private double selectAmt;
        private double hoverTarget;
        private double pressTarget;
        private double selectTarget;
        private Motion.Tween hoverTween;
        private Motion.Tween pressTween;
        private Motion.Tween selectTween;

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
            getModel().addChangeListener(e -> syncStates());
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
            return new Dimension(Math.max(size.width, preferredWidth()), 38);
        }

        void setSelectedState(boolean selected) {
            this.selected = selected;
            animateSelect(selected ? 1 : 0);
            repaint();
        }

        /** Eases the hover/press/selection fills so state changes glide. */
        private void syncStates() {
            boolean on = isEnabled();
            animateHover(on && getModel().isRollover() ? 1 : 0);
            animatePress(on && getModel().isPressed() ? 1 : 0);
        }

        private void animateHover(double target) {
            if (Math.abs(target - hoverTarget) < 0.001) return;
            hoverTarget = target;
            if (hoverTween != null) hoverTween.stop();
            if (!isShowing()) {
                hoverAmt = target;
                return;
            }
            hoverTween = Motion.tween(hoverAmt, target, 130, Motion.EASE_OUT,
                    value -> { hoverAmt = value; repaint(); });
        }

        private void animatePress(double target) {
            if (Math.abs(target - pressTarget) < 0.001) return;
            pressTarget = target;
            if (pressTween != null) pressTween.stop();
            if (!isShowing()) {
                pressAmt = target;
                return;
            }
            pressTween = Motion.tween(pressAmt, target, 90, Motion.EASE_OUT,
                    value -> { pressAmt = value; repaint(); });
        }

        private void animateSelect(double target) {
            if (Math.abs(target - selectTarget) < 0.001) return;
            selectTarget = target;
            if (selectTween != null) selectTween.stop();
            if (!isShowing()) {
                selectAmt = target;
                return;
            }
            selectTween = Motion.tween(selectAmt, target, 150, Motion.EASE_OUT,
                    value -> { selectAmt = value; repaint(); });
        }

        /** Fill for the current animated state, blended from the base colours. */
        private Color fillFor(double hover, double down, double sel) {
            Theme t = Theme.get();
            switch (variant) {
                case PRIMARY:
                    return Theme.mix(Theme.mix(t.accent,
                                    Theme.mix(t.accent, t.onAccent, 0.07), hover),
                            Theme.mix(t.accentAlt, t.onAccent, 0.16), down);
                case DANGER:
                    return Theme.mix(Theme.mix(t.dangerSoft,
                                    Theme.mix(t.dangerSoft, t.danger, 0.08), hover),
                            Theme.mix(t.dangerSoft, t.danger, 0.15), down);
                case GHOST: {
                    Color off = Theme.mixAlpha(Theme.mixAlpha(
                                    new Color(0, 0, 0, 0),
                                    Theme.alpha(t.cardHover, 190), hover),
                            t.cardHover, down);
                    Color on = Theme.mix(Theme.mix(t.accentSoft,
                                    Theme.mix(t.accentSoft, t.accent, 0.05), hover),
                            Theme.mix(t.accentSoft, t.accent, 0.10), down);
                    return Theme.mixAlpha(off, on, sel);
                }
                case OUTLINE:
                    return Theme.mixAlpha(new Color(0, 0, 0, 0), t.cardHover, hover);
                default:
                    return Theme.mix(Theme.mix(t.accentSoft,
                                    Theme.mix(t.accentSoft, t.accent, 0.06), hover),
                            Theme.mix(t.accentSoft, t.accent, 0.12), down);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme t = Theme.get();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            Color fill = fillFor(hoverAmt * (1 - pressAmt), pressAmt, selectAmt);
            Color fg;
            switch (variant) {
                case PRIMARY:
                    fg = t.onAccent;
                    break;
                case DANGER:
                    fg = t.danger;
                    break;
                case GHOST:
                    fg = Theme.mix(t.text, t.onAccentSoft, selectAmt);
                    break;
                default:
                    fg = variant == OUTLINE ? t.text : t.onAccentSoft;
            }
            RoundRectangle2D shape = new RoundRectangle2D.Double(0, 0, w, h, h, h);
            if (variant == PRIMARY && isEnabled()) {
                g2.setColor(Theme.alpha(t.shadow, Math.min(60, t.shadow.getAlpha())));
                g2.fill(new RoundRectangle2D.Double(0, 1.5, w, h, h, h));
                g2.setPaint(new GradientPaint(0, 0, fill, 0, h,
                        Theme.mix(fill, t.accentAlt, 0.55)));
            } else {
                g2.setColor(fill);
            }
            g2.fill(shape);
            if (variant == OUTLINE) {
                g2.setColor(Theme.alpha(t.border, 230));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Double(0.5, 0.5, w - 1, h - 1, h, h));
            }
            if (isFocusOwner()) {
                g2.setColor(Theme.alpha(t.accent, 120));
                g2.setStroke(new BasicStroke(1.6f));
                g2.draw(new RoundRectangle2D.Double(1.6, 1.6, w - 3.2, h - 3.2,
                        h - 3.2, h - 3.2));
            }
            if (!isEnabled()) {
                g2.setColor(Theme.alpha(t.surface, 140));
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
        private double pos;
        private Motion.Tween knobTween;

        ToggleSwitch(boolean selected) {
            this.selected = selected;
            this.pos = selected ? 1 : 0;
            setPreferredSize(new Dimension(46, 28));
            setMinimumSize(new Dimension(46, 28));
            setMaximumSize(new Dimension(46, 28));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (!isEnabled()) return;
                    setSelected(!ToggleSwitch.this.selected);
                    if (onChange != null) onChange.accept(ToggleSwitch.this.selected);
                }
            });
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_SPACE
                            || e.getKeyCode() == KeyEvent.VK_ENTER) {
                        setSelected(!ToggleSwitch.this.selected);
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
            if (this.selected == selected) return;
            this.selected = selected;
            if (knobTween != null) knobTween.stop();
            double target = selected ? 1 : 0;
            if (!isShowing()) {
                pos = target;
                repaint();
                return;
            }
            knobTween = Motion.tween(pos, target, 170, Motion.EASE_OUT,
                    value -> { pos = value; repaint(); });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme t = Theme.get();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int h = Math.min(getHeight(), 28);
            int w = Math.min(getWidth(), 46);
            int y = (getHeight() - h) / 2;
            Color offTrack = Theme.mix(t.surfaceAlt, t.border, 0.85);
            Color onTrack = isEnabled() ? t.accent : t.faint;
            Color track = Theme.mix(offTrack, onTrack, pos);
            g2.setColor(track);
            g2.fill(new RoundRectangle2D.Double(0, y, w, h, h, h));
            if (pos < 0.999) {
                g2.setColor(Theme.alpha(t.border, (int) Math.round(240 * (1 - pos))));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Double(0.5, y + 0.5, w - 1, h - 1, h, h));
            }
            int thumb = h - 6;
            int x = (int) Math.round(3 + (w - thumb - 6) * pos);
            g2.setColor(Theme.alpha(t.shadow, 46));
            g2.fillOval(x, y + 4, thumb, thumb);
            g2.setColor(Theme.mix(Theme.mix(t.surface, t.text, 0.30), t.onAccent, pos));
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
        private double selAmt;
        private double hovAmt;
        private double selTarget;
        private double hovTarget;
        private Motion.Tween selTween;
        private Motion.Tween hovTween;
        private Runnable onClick;

        NavItem(String title, String iconName) {
            this.title = title;
            this.icon = Icons.icon(iconName, 18, 2);
            setPreferredSize(new Dimension(196, 42));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) { animateHover(1); }
                @Override
                public void mouseExited(MouseEvent e) { animateHover(0); }
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
            animateSelect(selected ? 1 : 0);
        }

        private void animateSelect(double target) {
            if (Math.abs(target - selTarget) < 0.001) return;
            selTarget = target;
            if (selTween != null) selTween.stop();
            if (!isShowing()) {
                selAmt = target;
                repaint();
                return;
            }
            selTween = Motion.tween(selAmt, target, 170, Motion.EASE_OUT,
                    value -> { selAmt = value; repaint(); });
        }

        private void animateHover(double target) {
            hover = target > 0.5;
            if (Math.abs(target - hovTarget) < 0.001) return;
            hovTarget = target;
            if (hovTween != null) hovTween.stop();
            if (!isShowing()) {
                hovAmt = target;
                repaint();
                return;
            }
            hovTween = Motion.tween(hovAmt, target, 120, Motion.EASE_OUT,
                    value -> { hovAmt = value; repaint(); });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme t = Theme.get();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            if (selAmt > 0.001) {
                g2.setColor(Theme.alpha(t.accentSoft,
                        (int) Math.round(255 * Math.min(1, selAmt))));
                g2.fill(new RoundRectangle2D.Double(0, 0, w, h, 11, 11));
                g2.setColor(Theme.alpha(t.accent,
                        (int) Math.round(255 * Math.min(1, selAmt))));
                g2.fill(new RoundRectangle2D.Double(0, h / 2.0 - 8, 3, 16, 3, 3));
            }
            double hov = hovAmt * (1 - Math.min(1, selAmt));
            if (hov > 0.001) {
                g2.setColor(Theme.alpha(t.cardHover, (int) Math.round(210 * hov)));
                g2.fill(new RoundRectangle2D.Double(0, 0, w, h, 11, 11));
            }
            boolean active = selAmt > 0.5;
            Icons.icon(iconName(), 18, active ? 1 : 2)
                    .paintIcon(this, g2, 15, (h - 18) / 2);
            g2.setColor(Theme.mix(t.muted, t.onAccentSoft, Math.min(1, selAmt)));
            g2.setFont(Theme.font(12.5f, active ? Font.BOLD : Font.PLAIN));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(title, 43, (h - fm.getHeight()) / 2 + fm.getAscent());
            if (isFocusOwner()) {
                g2.setColor(Theme.alpha(t.accent, 120));
                g2.setStroke(new BasicStroke(1.5f));
                g2.draw(new RoundRectangle2D.Double(1, 1, w - 2, h - 2, 10, 10));
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
            setBorder(new EmptyBorder(16, 16, 16, 16));
            setPreferredSize(new Dimension(180, 112));
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
            g2.setColor(Theme.alpha(accent, t.dark ? 34 : 26));
            g2.fill(new RoundRectangle2D.Double(16, 16, 32, 32, 11, 11));
            Graphics2D ig = (Graphics2D) g2.create();
            ig.translate(23, 23);
            Icons.paint(ig, iconName, 18, accent);
            ig.dispose();

            if (!hint.isEmpty()) {
                g2.setColor(t.faint);
                g2.setFont(Theme.font(10f, Font.PLAIN));
                int hintWidth = g2.getFontMetrics().stringWidth(hint);
                g2.drawString(hint, getWidth() - hintWidth - 16, 29);
            }
            g2.setColor(t.text);
            g2.setFont(Theme.font(29, Font.BOLD));
            g2.drawString(value, 16, 79);
            g2.setColor(t.muted);
            g2.setFont(Theme.font(11.5f, Font.PLAIN));
            g2.drawString(label, 16, 98);
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------- chart

    static final class ChartPanel extends JComponent {
        private List<StatsStore.Bucket> data = new ArrayList<>();
        private String range = "24h";
        private int hover = -1;
        private double[] fromQueries;
        private double[] fromBlocked;
        private double morph = 1;
        private Motion.Tween morphTween;

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
            List<StatsStore.Bucket> next = data == null ? new ArrayList<>() : data;
            this.range = range;
            boolean animate = next.size() == this.data.size() && next.size() > 1
                    && !this.data.isEmpty();
            if (!animate) {
                if (morphTween != null) morphTween.stop();
                fromQueries = null;
                fromBlocked = null;
                morph = 1;
                this.data = next;
                repaint();
                return;
            }
            // Freeze the series where it is on screen, then ease to the new one.
            double[] prevQueries = fromQueries;
            double[] prevBlocked = fromBlocked;
            double progress = morph;
            fromQueries = new double[next.size()];
            fromBlocked = new double[next.size()];
            for (int i = 0; i < next.size(); i++) {
                long oldQueries = this.data.get(i).queries;
                long oldBlocked = this.data.get(i).blocked;
                fromQueries[i] = prevQueries == null ? oldQueries
                        : prevQueries[i] + (oldQueries - prevQueries[i]) * progress;
                fromBlocked[i] = prevBlocked == null ? oldBlocked
                        : prevBlocked[i] + (oldBlocked - prevBlocked[i]) * progress;
            }
            this.data = next;
            morph = 0;
            if (morphTween != null) morphTween.stop();
            if (!isShowing()) {
                morph = 1;
                fromQueries = null;
                fromBlocked = null;
                repaint();
                return;
            }
            morphTween = Motion.tween(0, 1, 260, Motion.EASE_OUT,
                    value -> { morph = value; repaint(); });
        }

        private double series(double[] from, long target, int index) {
            if (from == null || index >= from.length) return target;
            return from[index] + (target - from[index]) * morph;
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
            // Interpolate towards the live series so data changes glide in.
            double[] queriesAt = new double[data.size()];
            double[] blockedAt = new double[data.size()];
            long maxValue = 1;
            for (int i = 0; i < data.size(); i++) {
                queriesAt[i] = series(fromQueries, data.get(i).queries, i);
                blockedAt[i] = series(fromBlocked, data.get(i).blocked, i);
                maxValue = Math.max(maxValue, (long) Math.ceil(
                        Math.max(queriesAt[i], blockedAt[i])));
            }
            long nice = niceMax(maxValue);
            for (int i = 0; i <= 4; i++) {
                long value = nice * i / 4;
                int y = top + plotH - (int) (plotH * (double) value / nice);
                g2.setColor(Theme.alpha(t.border, 130));
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER, 10f, new float[]{2f, 4f}, 0f));
                g2.drawLine(left, y, left + plotW, y);
                g2.setColor(t.faint);
                String label = compact(value);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(label, left - 7 - fm.stringWidth(label), y + 3);
            }
            double slot = plotW / Math.max(1.0, data.size() - 1.0);
            double[] xs = new double[data.size()];
            double[] yq = new double[data.size()];
            double[] yb = new double[data.size()];
            for (int i = 0; i < data.size(); i++) {
                xs[i] = left + slot * i;
                yq[i] = top + plotH - plotH * queriesAt[i] / nice;
                yb[i] = top + plotH - plotH * blockedAt[i] / nice;
            }
            Path2D queries = smooth(xs, yq);
            Path2D blocked = smooth(xs, yb);
            Path2D area = (Path2D) queries.clone();
            area.lineTo(xs[xs.length - 1], top + plotH);
            area.lineTo(xs[0], top + plotH);
            area.closePath();
            g2.setPaint(new GradientPaint(left, top, Theme.alpha(t.accent, 110),
                    left, top + plotH, Theme.alpha(t.accent, 0)));
            g2.fill(area);
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            g2.setColor(t.accent);
            g2.draw(queries);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND,
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
                double x = xs[hover];
                g2.setColor(Theme.alpha(t.muted, 90));
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER, 10f, new float[]{3f, 3f}, 0f));
                g2.drawLine((int) x, top, (int) x, top + plotH);
                g2.setColor(t.card);
                g2.fillOval((int) x - 5, (int) yq[hover] - 5, 10, 10);
                g2.setColor(t.accent);
                g2.fillOval((int) x - 4, (int) yq[hover] - 4, 8, 8);
                g2.setColor(t.danger);
                g2.fillOval((int) x - 3, (int) yb[hover] - 3, 6, 6);
                String label = rangeLabel(hover) + "  ·  "
                        + Math.round(queriesAt[hover])
                        + " queries  ·  " + Math.round(blockedAt[hover]) + " blocked";
                g2.setFont(Theme.font(10.5f, Font.PLAIN));
                FontMetrics fm = g2.getFontMetrics();
                int labelW = fm.stringWidth(label) + 18;
                int lx = (int) Math.max(4, Math.min(w - labelW - 4, x - labelW / 2.0));
                g2.setColor(Theme.alpha(t.shadow, 40));
                g2.fillRoundRect(lx, 2, labelW, 24, 10, 10);
                g2.setColor(Theme.alpha(t.card, 248));
                g2.fillRoundRect(lx, 1, labelW, 24, 10, 10);
                g2.setColor(Theme.alpha(t.border, 220));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(lx, 1, labelW, 24, 10, 10);
                g2.setColor(t.text);
                g2.drawString(label, lx + 9, 17);
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

        /** Catmull-Rom smoothing so the series read as trends, not zig-zags. */
        private static Path2D smooth(double[] xs, double[] ys) {
            Path2D path = new Path2D.Double();
            if (xs.length == 0) return path;
            path.moveTo(xs[0], ys[0]);
            if (xs.length == 1) return path;
            for (int i = 0; i < xs.length - 1; i++) {
                double x0 = i > 0 ? xs[i - 1] : xs[i];
                double y0 = i > 0 ? ys[i - 1] : ys[i];
                double x3 = i + 2 < xs.length ? xs[i + 2] : xs[i + 1];
                double y3 = i + 2 < xs.length ? ys[i + 2] : ys[i + 1];
                double c1x = xs[i] + (xs[i + 1] - x0) / 6.0;
                double c1y = ys[i] + (ys[i + 1] - y0) / 6.0;
                double c2x = xs[i + 1] - (x3 - xs[i]) / 6.0;
                double c2y = ys[i + 1] - (y3 - ys[i]) / 6.0;
                path.curveTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1]);
            }
            return path;
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
            setPreferredSize(new Dimension(100, Math.max(54, rows.size() * 32 + 6)));
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
                String text = empty;
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(text, Math.max(2, (getWidth() - fm.stringWidth(text)) / 2),
                        Math.max(18, getHeight() / 2 + 4));
                g2.dispose();
                return;
            }
            int max = 1;
            for (StatsStore.Top row : rows) max = Math.max(max, row.count);
            g2.setFont(Theme.font(11.5f, Font.BOLD));
            FontMetrics countMetrics = g2.getFontMetrics();
            int countW = 0;
            for (StatsStore.Top row : rows) {
                countW = Math.max(countW, countMetrics.stringWidth(String.valueOf(row.count)));
            }
            int y = 2;
            for (StatsStore.Top row : rows) {
                g2.setFont(Theme.font(11.8f, Font.PLAIN));
                g2.setColor(t.text);
                String name = row.key == null ? "" : row.key;
                FontMetrics fm = g2.getFontMetrics();
                int nameW = Math.max(80, getWidth() * 45 / 100);
                String shown = name;
                while (fm.stringWidth(shown) > nameW - 8 && shown.length() > 4) {
                    shown = shown.substring(0, shown.length() - 2) + "…";
                }
                g2.drawString(shown, 0, y + 14);
                int barX = nameW;
                int barW = Math.max(30, getWidth() - nameW - countW - 14);
                g2.setColor(t.track);
                g2.fillRoundRect(barX, y + 8, barW, 6, 6, 6);
                g2.setColor(danger ? t.danger : t.accent);
                int fill = Math.max(6, (int) (barW * (double) row.count / max));
                g2.fillRoundRect(barX, y + 8, fill, 6, 6, 6);
                String count = String.valueOf(row.count);
                g2.setColor(t.muted);
                g2.setFont(Theme.font(11.5f, Font.BOLD));
                g2.drawString(count, getWidth() - countMetrics.stringWidth(count), y + 15);
                y += 32;
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
        pane.getVerticalScrollBar().setUI(new SlimScrollBarUI());
        pane.getHorizontalScrollBar().setUI(new SlimScrollBarUI());
        pane.getVerticalScrollBar().setOpaque(false);
        pane.getHorizontalScrollBar().setOpaque(false);
        pane.getVerticalScrollBar().setPreferredSize(new Dimension(11, 10));
        pane.getHorizontalScrollBar().setPreferredSize(new Dimension(10, 11));
        return pane;
    }

    /** Thin, chrome-free scrollbar that only shows a thumb when needed. */
    static final class SlimScrollBarUI extends BasicScrollBarUI {
        private static final int THUMB_IDLE_ALPHA = 85;
        private static final int THUMB_ACTIVE_ALPHA = 140;
        private double thumbAmt;
        private double thumbTarget;
        private Motion.Tween thumbTween;

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }

        private JButton zeroButton() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension(0, 0));
            button.setMinimumSize(new Dimension(0, 0));
            button.setMaximumSize(new Dimension(0, 0));
            return button;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle track) {
            // The surface behind the bar already provides the track colour.
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle thumb) {
            if (thumb.isEmpty() || !scrollbar.isEnabled()) return;
            animateThumb(isDragging || isThumbRollover() ? 1 : 0);
            Theme t = Theme.get();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int alpha = (int) Math.round(THUMB_IDLE_ALPHA
                    + (THUMB_ACTIVE_ALPHA - THUMB_IDLE_ALPHA) * thumbAmt);
            g2.setColor(Theme.alpha(t.muted, alpha));
            int pad = 2;
            g2.fillRoundRect(thumb.x + pad, thumb.y + pad,
                    Math.max(2, thumb.width - pad * 2),
                    Math.max(2, thumb.height - pad * 2), 8, 8);
            g2.dispose();
        }

        /** Fades the thumb between its resting and hover alpha. */
        private void animateThumb(double target) {
            if (Math.abs(target - thumbTarget) < 0.001) return;
            thumbTarget = target;
            if (thumbTween != null) thumbTween.stop();
            if (!scrollbar.isShowing()) {
                thumbAmt = target;
                return;
            }
            thumbTween = Motion.tween(thumbAmt, target, 130, Motion.EASE_OUT,
                    value -> { thumbAmt = value; scrollbar.repaint(); });
        }
    }

    static JScrollPane scrollSurface(Component view) {
        JScrollPane pane = scroll(view);
        pane.setBackground(Theme.get().surface);
        pane.getViewport().setBackground(Theme.get().surface);
        return pane;
    }

    // ------------------------------------------------------------------ slider

    static void styleSlider(JSlider slider) {
        slider.setOpaque(false);
        slider.setUI(new SlimSliderUI(slider));
    }

    /** Flat 5px rail with an outlined knob; the platform UI draws a glossy bar. */
    static final class SlimSliderUI extends BasicSliderUI {
        SlimSliderUI(JSlider slider) {
            super(slider);
        }

        @Override
        protected Dimension getThumbSize() {
            return new Dimension(16, 16);
        }

        @Override
        public void paintFocus(Graphics g) {
        }

        @Override
        public void paintTrack(Graphics g) {
            Theme t = Theme.get();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int h = 5;
            int y = trackRect.y + (trackRect.height - h) / 2;
            g2.setColor(t.track);
            g2.fillRoundRect(trackRect.x, y, trackRect.width, h, h, h);
            int filled = thumbRect.x + thumbRect.width / 2 - trackRect.x;
            if (filled > 0) {
                g2.setColor(slider.isEnabled() ? t.accent : t.faint);
                g2.fillRoundRect(trackRect.x, y, Math.min(filled, trackRect.width),
                        h, h, h);
            }
            g2.dispose();
        }

        @Override
        public void paintThumb(Graphics g) {
            Theme t = Theme.get();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int size = 16;
            int x = thumbRect.x + (thumbRect.width - size) / 2;
            int y = thumbRect.y + (thumbRect.height - size) / 2;
            g2.setColor(Theme.alpha(t.shadow, 46));
            g2.fillOval(x, y + 1, size, size);
            g2.setColor(t.card);
            g2.fillOval(x, y, size, size);
            g2.setColor(slider.isEnabled() ? t.accent : t.faint);
            g2.setStroke(new BasicStroke(2f));
            g2.drawOval(x + 1, y + 1, size - 2, size - 2);
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------- input

    static final class RoundedBorder extends AbstractBorder {
        private Color fill;
        private Color line;
        private final int radius;
        private final Insets pad;

        RoundedBorder(Color fill, Color line, int radius, Insets pad) {
            this.fill = fill;
            this.line = line;
            this.radius = radius;
            this.pad = pad;
        }

        void setColors(Color fill, Color line) {
            this.fill = fill;
            this.line = line;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            if (w <= 0 || h <= 0) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            RoundRectangle2D shape = new RoundRectangle2D.Double(x + 0.5, y + 0.5,
                    Math.max(0, w - 1), Math.max(0, h - 1), radius, radius);
            if (fill != null) {
                g2.setColor(fill);
                g2.fill(shape);
            }
            g2.setColor(line);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(shape);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(pad.top, pad.left, pad.bottom, pad.right);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(pad.top, pad.left, pad.bottom, pad.right);
            return insets;
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }

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
            paintInputSurface(g, this);
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
        field.setForeground(t.text);
        field.setCaretColor(t.accent);
        field.setSelectionColor(t.accentSoft);
        field.setSelectedTextColor(t.onAccentSoft);
        field.setOpaque(false);
        field.setBorder(inputBorder(t, inputLine(t)));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                field.setBorder(inputBorder(Theme.get(), Theme.get().accent));
            }

            @Override
            public void focusLost(FocusEvent e) {
                field.setBorder(inputBorder(Theme.get(), inputLine(Theme.get())));
            }
        });
        return field;
    }

    private static RoundedBorder inputBorder(Theme t, Color line) {
        return new RoundedBorder(null, line, 10, new Insets(8, 11, 8, 11));
    }

    /** Rounded surface painted beneath text; a border fill would cover the text. */
    private static void paintInputSurface(Graphics g, JComponent c) {
        Theme t = Theme.get();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(t.surfaceAlt);
        g2.fill(new RoundRectangle2D.Double(0.5, 0.5,
                Math.max(0, c.getWidth() - 1), Math.max(0, c.getHeight() - 1),
                10, 10));
        g2.dispose();
    }

    /** Dark surfaces need a touch more contrast than the hairline divider. */
    private static Color inputLine(Theme t) {
        return t.dark ? Theme.mix(t.border, t.muted, 0.32) : t.border;
    }

    static final class RoundedPasswordField extends JPasswordField {
        RoundedPasswordField(int columns) {
            super(columns);
        }

        @Override
        protected void paintComponent(Graphics g) {
            paintInputSurface(g, this);
            super.paintComponent(g);
        }
    }

    static JPasswordField passwordField(int columns) {
        Theme t = Theme.get();
        JPasswordField field = new RoundedPasswordField(columns);
        field.setFont(Theme.font(12.5f));
        field.setForeground(t.text);
        field.setCaretColor(t.accent);
        field.setSelectionColor(t.accentSoft);
        field.setSelectedTextColor(t.onAccentSoft);
        field.setOpaque(false);
        field.setBorder(inputBorder(t, inputLine(t)));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                field.setBorder(inputBorder(Theme.get(), Theme.get().accent));
            }

            @Override
            public void focusLost(FocusEvent e) {
                field.setBorder(inputBorder(Theme.get(), inputLine(Theme.get())));
            }
        });
        return field;
    }

    static JComboBox<String> combo(String[] items, String selected) {
        Theme t = Theme.get();
        JComboBox<String> combo = new JComboBox<>(items) {
            @Override
            protected void paintComponent(Graphics g) {
                paintInputSurface(g, this);
                super.paintComponent(g);
            }
        };
        combo.setFont(Theme.font(12.5f));
        combo.setBackground(t.surfaceAlt);
        combo.setForeground(t.text);
        combo.setFocusable(true);
        combo.setOpaque(false);
        combo.setBorder(new RoundedBorder(null, inputLine(t), 10,
                new Insets(1, 11, 1, 2)));
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

            @Override
            public void paintCurrentValueBackground(Graphics g,
                                                    java.awt.Rectangle bounds,
                                                    boolean hasFocus) {
                // The rounded border paints the field surface.
            }

            @Override
            protected Dimension getDefaultSize() {
                return new Dimension(super.getDefaultSize().width, 28);
            }
        });
        combo.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                combo.setBorder(new RoundedBorder(null,
                        Theme.get().accent, 10, new Insets(1, 11, 1, 2)));
            }

            @Override
            public void focusLost(FocusEvent e) {
                Theme theme = Theme.get();
                combo.setBorder(new RoundedBorder(null,
                        inputLine(theme), 10, new Insets(1, 11, 1, 2)));
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
                // index -1 is the closed combo: keep it flat, like a text field.
                boolean inList = index >= 0;
                label.setBorder(inList ? new EmptyBorder(5, 9, 5, 9)
                        : new EmptyBorder(0, 3, 0, 3));
                label.setFont(Theme.font(12.5f));
                label.setOpaque(inList);
                label.setBackground(inList && selected ? theme.accentSoft : theme.surface);
                label.setForeground(inList && selected ? theme.onAccentSoft : theme.text);
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
