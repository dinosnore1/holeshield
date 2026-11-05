package com.dino.holeshield;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Copies the shared master icon used by the desktop installers. */
public final class IconGenerator {

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "icon.png");
        try (InputStream in = IconGenerator.class.getResourceAsStream("/icon-master.png")) {
            if (in == null) {
                throw new IllegalStateException("icon-master.png is missing from the build resources");
            }
            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();
            Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        BufferedImage check = ImageIO.read(out);
        if (check == null || check.getWidth() != check.getHeight()) {
            throw new IllegalStateException("icon-master.png is not a square image");
        }
        System.out.println("wrote " + out.getAbsolutePath() + " (" + check.getWidth() + "px)");
    }
}
