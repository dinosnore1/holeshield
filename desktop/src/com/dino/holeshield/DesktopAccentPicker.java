package com.dino.holeshield;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.util.function.Consumer;

final class DesktopAccentPicker {
    static void show(Component parent, AccentPalette initial, Consumer<String> save) {
        Theme theme = Theme.get();
        int rgb = initial == AccentPalette.DEFAULT && !theme.dark
                ? 0x0E7C55 : initial.chosen(theme.dark);
        Field field = new Field(rgb);
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        JLabel preview = new JLabel();
        preview.setPreferredSize(new Dimension(32, 32));
        preview.setOpaque(true);
        preview.setBorder(BorderFactory.createLineBorder(theme.border));
        top.add(preview, BorderLayout.WEST);
        JTextField hex = new JTextField(AccentPalette.hex(rgb));
        hex.getAccessibleContext().setAccessibleName("Hex color");
        top.add(hex);
        body.add(top, BorderLayout.NORTH);
        body.add(field);
        JSlider brightness = new JSlider(0, 255, Math.round(field.hsv[2] * 255));
        brightness.setOpaque(false);
        brightness.setUI(new javax.swing.plaf.basic.BasicSliderUI(brightness) {
            @Override public void paintTrack(Graphics g) {
                if (trackRect.width <= 0) return;
                Graphics2D draw = (Graphics2D) g.create();
                draw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                draw.setPaint(new GradientPaint(trackRect.x, 0, Color.BLACK,
                        trackRect.x + trackRect.width, 0, brightness.getForeground()));
                int y = trackRect.y + (trackRect.height - 14) / 2;
                draw.fillRoundRect(trackRect.x, y, trackRect.width, 14, 14, 14);
                draw.dispose();
            }
        });
        brightness.getAccessibleContext().setAccessibleName("Brightness");
        body.add(brightness, BorderLayout.SOUTH);
        JButton apply = new JButton("Apply");
        JButton reset = new JButton("Reset");
        JButton cancel = new JButton("Cancel");
        JOptionPane pane = new JOptionPane(body, JOptionPane.PLAIN_MESSAGE,
                JOptionPane.DEFAULT_OPTION, null, new Object[]{reset, cancel, apply}, apply);
        JDialog dialog = pane.createDialog(parent, "Accent color");
        dialog.getRootPane().setDefaultButton(apply);
        apply.addActionListener(e -> {
            dialog.dispose();
            save.accept(AccentPalette.fromId(hex.getText()).id);
        });
        reset.addActionListener(e -> { dialog.dispose(); save.accept("default"); });
        cancel.addActionListener(e -> dialog.dispose());
        boolean[] syncing = {false};
        Runnable refresh = () -> {
            preview.setBackground(new Color(field.rgb()));
            brightness.setForeground(Color.getHSBColor(field.hsv[0], field.hsv[1], 1));
            field.repaint();
        };
        field.changed = () -> {
            syncing[0] = true;
            hex.setText(AccentPalette.hex(field.rgb()));
            brightness.setValue(Math.round(field.hsv[2] * 255));
            syncing[0] = false;
            apply.setEnabled(true);
            refresh.run();
        };
        hex.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { update(); }
            @Override public void removeUpdate(DocumentEvent e) { update(); }
            @Override public void changedUpdate(DocumentEvent e) { update(); }
            private void update() {
                if (syncing[0]) return;
                boolean valid = hex.getText().matches("#?[0-9a-fA-F]{6}");
                apply.setEnabled(valid);
                if (!valid) return;
                field.setRgb(AccentPalette.fromId(hex.getText()).chosen(theme.dark));
                syncing[0] = true;
                brightness.setValue(Math.round(field.hsv[2] * 255));
                syncing[0] = false;
                refresh.run();
            }
        });
        brightness.addChangeListener(e -> {
            if (syncing[0]) return;
            field.hsv[2] = brightness.getValue() / 255f;
            field.changed.run();
        });
        refresh.run();
        dialog.setVisible(true);
        dialog.dispose();
    }

    private static final class Field extends JPanel {
        final float[] hsv = new float[3];
        Runnable changed;

        Field(int rgb) {
            setRgb(rgb);
            setPreferredSize(new Dimension(320, 140));
            setFocusable(true);
            getAccessibleContext().setAccessibleName("Hue and saturation");
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { requestFocusInWindow(); update(e); }
                @Override public void mouseDragged(MouseEvent e) { update(e); }
                private void update(MouseEvent e) {
                    hsv[0] = Math.min(.99999f, clamp(e.getX() / (float) getWidth()));
                    hsv[1] = 1 - clamp(e.getY() / (float) getHeight());
                    changed.run();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_LEFT -> hsv[0] = (hsv[0] + .99f) % 1;
                        case KeyEvent.VK_RIGHT -> hsv[0] = (hsv[0] + .01f) % 1;
                        case KeyEvent.VK_UP -> hsv[1] = clamp(hsv[1] + .04f);
                        case KeyEvent.VK_DOWN -> hsv[1] = clamp(hsv[1] - .04f);
                        default -> { return; }
                    }
                    changed.run();
                    e.consume();
                }
            });
        }

        void setRgb(int rgb) { Color.RGBtoHSB((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, hsv); }
        int rgb() { return Color.HSBtoRGB(hsv[0], hsv[1], hsv[2]); }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int w = getWidth(), h = getHeight();
            if (w < 2 || h < 2) return;
            Graphics2D draw = (Graphics2D) g.create();
            draw.setPaint(new LinearGradientPaint(0, 0, w, 0,
                    new float[]{0, 1f/6, 2f/6, .5f, 4f/6, 5f/6, 1},
                    new Color[]{Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                            Color.BLUE, Color.MAGENTA, Color.RED}));
            draw.fillRect(0, 0, w, h);
            draw.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 0), 0, h, Color.WHITE));
            draw.fillRect(0, 0, w, h);
            int x = (int) ((w - 18) * hsv[0]), y = (int) ((h - 18) * (1 - hsv[1]));
            draw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            draw.setColor(Color.WHITE);
            draw.fillOval(x, y, 18, 18);
            draw.setColor(new Color(rgb()));
            draw.fillOval(x + 2, y + 2, 14, 14);
            draw.dispose();
        }

        private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }
    }
}
