package com.dino.holeshield;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/** Hand-drawn vector icons, so the desktop port needs no image assets. */
final class Icons {

    private Icons() { }

    static final class ThemedIcon implements Icon {
        final String name;
        final int size;
        final int color;

        ThemedIcon(String name, int size, int color) {
            this.name = name;
            this.size = size;
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.translate(x, y);
            Theme t = Theme.get();
            Color value;
            switch (color) {
                case 1: value = t.accent; break;
                case 2: value = t.muted; break;
                case 3: value = t.danger; break;
                case 4: value = t.onAccent; break;
                case 5: value = t.warn; break;
                default: value = t.text;
            }
            paint(g2, name, size, value);
            g2.dispose();
        }

        @Override
        public int getIconWidth() { return size; }

        @Override
        public int getIconHeight() { return size; }
    }

    static Icon icon(String name, int size, int color) {
        return new ThemedIcon(name, size, color);
    }

    static BufferedImage image(String name, int size, Color color) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        paint(g, name, size, color);
        g.dispose();
        return image;
    }

    static void paint(Graphics2D g, String name, int size, Color color) {
        if (name == null) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
        double scale = size / 24.0;
        g2.scale(scale, scale);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.85f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        switch (name) {
            case "shield": {
                Path2D shield = shieldPath();
                g2.draw(shield);
                break;
            }
            case "shield-check": {
                g2.draw(shieldPath());
                Path2D check = new Path2D.Double();
                check.moveTo(8.2, 12.0);
                check.lineTo(10.9, 14.7);
                check.lineTo(16.0, 9.2);
                g2.draw(check);
                break;
            }
            case "shield-fill": {
                g2.fill(shieldPath());
                break;
            }
            case "dashboard": {
                Path2D p = new Path2D.Double();
                p.moveTo(3.5, 10.5);
                p.lineTo(12, 3.6);
                p.lineTo(20.5, 10.5);
                p.lineTo(20.5, 20.0);
                p.lineTo(14.5, 20.0);
                p.lineTo(14.5, 14.2);
                p.lineTo(9.5, 14.2);
                p.lineTo(9.5, 20.0);
                p.lineTo(3.5, 20.0);
                p.closePath();
                g2.draw(p);
                break;
            }
            case "list": {
                g2.drawLine(4, 6, 20, 6);
                g2.drawLine(4, 12, 20, 12);
                g2.drawLine(4, 18, 14, 18);
                break;
            }
            case "rules": {
                g2.drawLine(4, 7, 20, 7);
                g2.drawLine(4, 17, 20, 17);
                g2.fillOval(14, 4, 6, 6);
                g2.fillOval(5, 14, 6, 6);
                break;
            }
            case "settings": {
                g2.drawOval(9, 9, 6, 6);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4.0;
                    double x1 = 12 + Math.cos(a) * 7.2;
                    double y1 = 12 + Math.sin(a) * 7.2;
                    double x2 = 12 + Math.cos(a) * 9.4;
                    double y2 = 12 + Math.sin(a) * 9.4;
                    g2.draw(new java.awt.geom.Line2D.Double(x1, y1, x2, y2));
                }
                break;
            }
            case "play": {
                Path2D p = new Path2D.Double();
                p.moveTo(8, 5.5);
                p.lineTo(18.5, 12);
                p.lineTo(8, 18.5);
                p.closePath();
                g2.fill(p);
                break;
            }
            case "stop": {
                g2.fillRoundRect(6, 6, 12, 12, 3, 3);
                break;
            }
            case "pause": {
                g2.fillRoundRect(7, 5, 4, 14, 2, 2);
                g2.fillRoundRect(13, 5, 4, 14, 2, 2);
                break;
            }
            case "refresh": {
                g2.drawArc(4, 4, 16, 16, 40, 280);
                Path2D p = new Path2D.Double();
                p.moveTo(18.4, 3.4);
                p.lineTo(19.2, 8.4);
                p.lineTo(14.2, 7.4);
                g2.draw(p);
                break;
            }
            case "external": {
                g2.drawRoundRect(4, 7, 12, 13, 2, 2);
                g2.drawLine(13, 4, 20, 4);
                g2.drawLine(20, 4, 20, 11);
                g2.drawLine(20, 4, 11, 13);
                break;
            }
            case "search": {
                g2.drawOval(4, 4, 12, 12);
                g2.draw(new java.awt.geom.Line2D.Double(15.2, 15.2, 20.5, 20.5));
                break;
            }
            case "plus": {
                g2.drawLine(12, 5, 12, 19);
                g2.drawLine(5, 12, 19, 12);
                break;
            }
            case "trash": {
                g2.drawLine(4, 7, 20, 7);
                g2.draw(new java.awt.geom.Line2D.Double(9, 7, 9.8, 4.5));
                g2.draw(new java.awt.geom.Line2D.Double(15, 7, 14.2, 4.5));
                Path2D p = new Path2D.Double();
                p.moveTo(6, 7);
                p.lineTo(7.2, 20);
                p.lineTo(16.8, 20);
                p.lineTo(18, 7);
                g2.draw(p);
                g2.drawLine(10, 11, 10, 16);
                g2.drawLine(14, 11, 14, 16);
                break;
            }
            case "check": {
                Path2D p = new Path2D.Double();
                p.moveTo(5, 12.5);
                p.lineTo(9.8, 17.3);
                p.lineTo(19, 7.2);
                g2.draw(p);
                break;
            }
            case "x": {
                g2.drawLine(6, 6, 18, 18);
                g2.drawLine(18, 6, 6, 18);
                break;
            }
            case "chevron": {
                Path2D p = new Path2D.Double();
                p.moveTo(9, 5);
                p.lineTo(16, 12);
                p.lineTo(9, 19);
                g2.draw(p);
                break;
            }
            case "chevron-down": {
                Path2D p = new Path2D.Double();
                p.moveTo(5, 9);
                p.lineTo(12, 16);
                p.lineTo(19, 9);
                g2.draw(p);
                break;
            }
            case "globe": {
                g2.drawOval(3, 3, 18, 18);
                g2.draw(new java.awt.geom.Ellipse2D.Double(8.2, 3, 7.6, 18));
                g2.drawLine(3, 12, 21, 12);
                break;
            }
            case "lock": {
                g2.drawRoundRect(5, 10, 14, 10, 3, 3);
                g2.drawArc(8, 4, 8, 11, 0, 180);
                g2.fillOval(11, 14, 2, 2);
                break;
            }
            case "moon": {
                Path2D p = new Path2D.Double();
                p.moveTo(20, 14.2);
                p.curveTo(18.6, 15.1, 17, 15.5, 15.3, 15.5);
                p.curveTo(10.3, 15.5, 6.3, 11.5, 6.3, 6.5);
                p.curveTo(6.3, 5.3, 6.5, 4.1, 7.0, 3.0);
                p.curveTo(4.0, 4.6, 2.0, 7.8, 2.0, 11.5);
                p.curveTo(2.0, 16.7, 6.2, 21.0, 11.5, 21.0);
                p.curveTo(15.1, 21.0, 18.2, 18.9, 20.0, 14.2);
                p.closePath();
                g2.draw(p);
                break;
            }
            case "sun": {
                g2.draw(new java.awt.geom.Ellipse2D.Double(8.5, 8.5, 7, 7));
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4.0;
                    double x1 = 12 + Math.cos(a) * 8.0;
                    double y1 = 12 + Math.sin(a) * 8.0;
                    double x2 = 12 + Math.cos(a) * 10.0;
                    double y2 = 12 + Math.sin(a) * 10.0;
                    g2.draw(new java.awt.geom.Line2D.Double(x1, y1, x2, y2));
                }
                break;
            }
            case "monitor": {
                g2.drawRoundRect(3, 4, 18, 12, 2, 2);
                g2.drawLine(9, 20, 15, 20);
                g2.drawLine(12, 16, 12, 20);
                break;
            }
            case "folder": {
                Path2D p = new Path2D.Double();
                p.moveTo(3, 7);
                p.lineTo(9, 7);
                p.lineTo(11, 9);
                p.lineTo(21, 9);
                p.lineTo(21, 19);
                p.lineTo(3, 19);
                p.closePath();
                g2.draw(p);
                break;
            }
            case "power": {
                g2.drawArc(4, 5, 16, 16, -50, 280);
                g2.drawLine(12, 3, 12, 11);
                break;
            }
            case "copy": {
                g2.drawRoundRect(8, 8, 12, 12, 2, 2);
                g2.drawRoundRect(4, 4, 12, 12, 2, 2);
                break;
            }
            case "download": {
                g2.drawLine(12, 3, 12, 15);
                Path2D p = new Path2D.Double();
                p.moveTo(7, 10.5);
                p.lineTo(12, 15.5);
                p.lineTo(17, 10.5);
                g2.draw(p);
                g2.drawLine(4, 20, 20, 20);
                break;
            }
            case "clock": {
                g2.drawOval(3, 3, 18, 18);
                g2.drawLine(12, 7, 12, 12);
                g2.drawLine(12, 12, 16, 14);
                break;
            }
            case "chart": {
                g2.drawLine(4, 20, 20, 20);
                g2.fillRoundRect(6, 12, 3, 6, 1, 1);
                g2.fillRoundRect(11, 8, 3, 10, 1, 1);
                g2.fillRoundRect(16, 4, 3, 14, 1, 1);
                break;
            }
            case "wifi": {
                g2.drawArc(-2, 2, 28, 24, 35, 110);
                g2.drawArc(2, 7, 20, 18, 35, 110);
                g2.fill(new java.awt.geom.Ellipse2D.Double(10.5, 17.5, 3, 3));
                break;
            }
            case "server": {
                g2.drawRoundRect(3, 4, 18, 6, 2, 2);
                g2.drawRoundRect(3, 14, 18, 6, 2, 2);
                g2.fill(new java.awt.geom.Ellipse2D.Double(6, 6.5, 1.6, 1.6));
                g2.fill(new java.awt.geom.Ellipse2D.Double(6, 16.5, 1.6, 1.6));
                break;
            }
            case "filter": {
                Path2D p = new Path2D.Double();
                p.moveTo(3, 5);
                p.lineTo(21, 5);
                p.lineTo(14, 13);
                p.lineTo(14, 20);
                p.lineTo(10, 18);
                p.lineTo(10, 13);
                p.closePath();
                g2.draw(p);
                break;
            }
            case "info": {
                g2.drawOval(3, 3, 18, 18);
                g2.fillOval(11, 7, 2, 2);
                g2.drawLine(12, 11, 12, 17);
                break;
            }
            case "alert": {
                Path2D p = new Path2D.Double();
                p.moveTo(12, 3);
                p.lineTo(22, 20.5);
                p.lineTo(2, 20.5);
                p.closePath();
                g2.draw(p);
                g2.drawLine(12, 9, 12, 14);
                g2.fill(new java.awt.geom.Ellipse2D.Double(11, 16.5, 2, 2));
                break;
            }
            default:
                g2.drawOval(4, 4, 16, 16);
        }
        g2.dispose();
    }

    private static Path2D shieldPath() {
        Path2D p = new Path2D.Double();
        p.moveTo(12, 2.4);
        p.lineTo(20, 5.4);
        p.lineTo(20, 11.4);
        p.curveTo(20, 16.3, 16.7, 20.1, 12, 21.7);
        p.curveTo(7.3, 20.1, 4, 16.3, 4, 11.4);
        p.lineTo(4, 5.4);
        p.closePath();
        return p;
    }
}
