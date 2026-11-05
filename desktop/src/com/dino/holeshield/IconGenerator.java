package com.dino.holeshield;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/** Generates the application icon used by the installers. */
public final class IconGenerator {

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "icon.png");
        int size = 1024;
        BufferedImage image = new BufferedImage(size, size,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
        g.setColor(new Color(0x0D1512));
        g.fillRoundRect(0, 0, size, size, size / 5, size / 5);
        g.setColor(new Color(0x8ED5B2));
        g.fillRoundRect(size / 10, size / 10, size * 8 / 10, size * 8 / 10,
                size / 6, size / 6);
        g.setColor(new Color(0x0D1512));
        g.setStroke(new java.awt.BasicStroke(size / 28f,
                java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        int pad = size / 4;
        java.awt.geom.Path2D shield = new java.awt.geom.Path2D.Double();
        shield.moveTo(size / 2.0, pad);
        shield.lineTo(size - pad, pad + size * 0.10);
        shield.lineTo(size - pad, size * 0.52);
        shield.curveTo(size - pad, size * 0.74, size * 0.68, size * 0.86,
                size / 2.0, size * 0.93);
        shield.curveTo(size * 0.32, size * 0.86, pad, size * 0.74, pad,
                size * 0.52);
        shield.lineTo(pad, pad + size * 0.10);
        shield.closePath();
        g.draw(shield);
        java.awt.geom.Path2D check = new java.awt.geom.Path2D.Double();
        check.moveTo(size * 0.36, size * 0.52);
        check.lineTo(size * 0.46, size * 0.62);
        check.lineTo(size * 0.66, size * 0.40);
        g.draw(check);
        g.dispose();
        ImageIO.write(image, "png", out);
        System.out.println("wrote " + out.getAbsolutePath());
    }
}
