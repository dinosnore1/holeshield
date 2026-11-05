import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Draws the Hole Shield master icon and every launcher/banner size.
 * Run with tools/make-icons.sh.
 */
public final class IconDesigner {

    private static final Color BG_TOP = new Color(0x12261C);
    private static final Color BG_BOTTOM = new Color(0x07100C);
    private static final Color MINT_TOP = new Color(0xB9F4D6);
    private static final Color MINT_BOTTOM = new Color(0x69C295);
    private static final Color INK = new Color(0x07130D);

    private IconDesigner() { }

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".");
        BufferedImage master = master(1024);
        write(new File(root, "desktop/resources/icon-master.png"), master);

        int[] sizes = {48, 72, 96, 144, 192};
        String[] dirs = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        for (int i = 0; i < sizes.length; i++) {
            write(new File(root, "res/mipmap-" + dirs[i] + "/ic_launcher.png"),
                    resize(master, sizes[i]));
        }
        write(new File(root, "res/drawable/ic_launcher.png"), resize(master, 192));
        write(new File(root, "res/drawable/ic_launcher_foreground.png"),
                foreground(432));
        write(new File(root, "res/drawable/banner.png"), resize(banner(), 320, 180));
        System.out.println("wrote icons under " + root.getAbsolutePath());
    }

    private static BufferedImage master(int size) {
        BufferedImage image = new BufferedImage(size, size,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        float s = size;
        RoundRectangle2D background = new RoundRectangle2D.Double(
                0, 0, s, s, s * 0.22, s * 0.22);
        g.setPaint(new LinearGradientPaint(0, 0, 0, s,
                new float[]{0f, 1f}, new Color[]{BG_TOP, BG_BOTTOM}));
        g.fill(background);

        g.setClip(background);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(s * 0.34, s * 0.22),
                s * 0.72f, new float[]{0f, 1f},
                new Color[]{new Color(142, 213, 178, 44),
                        new Color(142, 213, 178, 0)}));
        g.fillRect(0, 0, size, size);
        g.setClip(null);

        g.setColor(new Color(255, 255, 255, 18));
        g.setStroke(new BasicStroke(s * 0.006f));
        g.draw(new RoundRectangle2D.Double(s * 0.012, s * 0.012,
                s * 0.976, s * 0.976, s * 0.205, s * 0.205));

        drawShield(g, s * 0.5, s * 0.5, s * 0.56, s * 0.64, s * 0.035);
        g.dispose();
        return image;
    }

    private static BufferedImage foreground(int size) {
        BufferedImage image = new BufferedImage(size, size,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        float s = size;
        drawShield(g, s * 0.5, s * 0.5, s * 0.60, s * 0.68, s * 0.03);
        g.dispose();
        return image;
    }

    private static void drawShield(Graphics2D g, double cx, double cy,
                                   double w, double h, double shadow) {
        Path2D shield = shield(cx, cy, w, h);

        g.setColor(new Color(0, 0, 0, 82));
        g.translate(0, shadow);
        g.fill(shield);
        g.translate(0, -shadow);

        g.setPaint(new LinearGradientPaint((float) (cx - w / 2), (float) (cy - h / 2),
                (float) (cx + w / 2), (float) (cy + h / 2),
                new float[]{0f, 0.45f, 1f},
                new Color[]{MINT_TOP, new Color(0x8ED5B2), MINT_BOTTOM}));
        g.fill(shield);

        g.setColor(new Color(255, 255, 255, 46));
        g.setStroke(new BasicStroke((float) (w * 0.012)));
        g.draw(shield);

        Path2D check = new Path2D.Double();
        check.moveTo(cx - w * 0.22, cy + h * 0.01);
        check.lineTo(cx - w * 0.04, cy + h * 0.19);
        check.lineTo(cx + w * 0.26, cy - h * 0.17);
        g.setColor(INK);
        g.setStroke(new BasicStroke((float) (w * 0.115), BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        g.draw(check);

    }

    private static Path2D shield(double cx, double cy, double w, double h) {
        double left = cx - w / 2;
        double right = cx + w / 2;
        double top = cy - h / 2;
        double bottom = cy + h / 2;
        double shoulder = top + h * 0.13;
        Path2D p = new Path2D.Double();
        p.moveTo(cx, top);
        p.curveTo(cx + w * 0.24, top + h * 0.02,
                right - w * 0.03, shoulder - h * 0.01, right, shoulder);
        p.lineTo(right, cy + h * 0.01);
        p.curveTo(right, cy + h * 0.30, cx + w * 0.30, cy + h * 0.43, cx, bottom);
        p.curveTo(cx - w * 0.30, cy + h * 0.43, left, cy + h * 0.30, left,
                cy + h * 0.01);
        p.lineTo(left, shoulder);
        p.curveTo(left + w * 0.03, shoulder - h * 0.01,
                cx - w * 0.24, top + h * 0.02, cx, top);
        p.closePath();
        return p;
    }

    private static BufferedImage banner() {
        int w = 640;
        int h = 360;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        g.setPaint(new LinearGradientPaint(0, 0, w, h,
                new float[]{0f, 1f},
                new Color[]{new Color(0x0F1C16), new Color(0x07100C)}));
        g.fillRect(0, 0, w, h);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(120, 40), 300,
                new float[]{0f, 1f},
                new Color[]{new Color(142, 213, 178, 34),
                        new Color(142, 213, 178, 0)}));
        g.fillRect(0, 0, w, h);

        BufferedImage icon = resize(master(512), 190);
        g.drawImage(icon, 38, 85, null);

        g.setColor(new Color(0xE8F3EC));
        g.setFont(new Font("SansSerif", Font.BOLD, 52));
        g.drawString("HOLE SHIELD", 252, 170);
        g.setColor(new Color(0x8ED5B2));
        g.setFont(new Font("SansSerif", Font.BOLD, 24));
        g.drawString("TV · DNS FILTER", 255, 212);
        g.setColor(new Color(0x93A89C));
        g.setFont(new Font("SansSerif", Font.PLAIN, 17));
        g.drawString("BLOCK ADS · TRACKERS · TELEMETRY", 255, 247);

        g.setColor(new Color(142, 213, 178, 90));
        g.setStroke(new BasicStroke(2f));
        g.drawLine(255, 270, 588, 270);
        g.dispose();
        return image;
    }

    private static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    private static BufferedImage resize(BufferedImage source, int size) {
        return resize(source, size, size);
    }

    private static BufferedImage resize(BufferedImage source, int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        g.drawImage(source, 0, 0, w, h, null);
        g.dispose();
        return image;
    }

    private static void write(File file, BufferedImage image) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        ImageIO.write(image, "png", file);
    }
}
