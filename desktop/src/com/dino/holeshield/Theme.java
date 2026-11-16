package com.dino.holeshield;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Shared palette and typography for the desktop control panel. */
final class Theme {

    static final String MODE_SYSTEM = "system";
    static final String MODE_DARK = "dark";
    static final String MODE_LIGHT = "light";

    private static volatile Theme current;
    private static String family = "SansSerif";

    final boolean dark;
    final Color bg;
    final Color surface;
    final Color surfaceAlt;
    final Color card;
    final Color cardHover;
    final Color border;
    final Color text;
    final Color muted;
    final Color faint;
    final Color accent;
    final Color accentAlt;
    final Color onAccent;
    final Color accentSoft;
    final Color onAccentSoft;
    final Color danger;
    final Color dangerSoft;
    final Color success;
    final Color warn;
    final Color track;
    final Color shadow;
    final Color selection;

    private Theme(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = new Color(0x0A0F0C);
            surface = new Color(0x0F1512);
            surfaceAlt = new Color(0x141C17);
            card = new Color(0x121A15);
            cardHover = new Color(0x1A251D);
            border = new Color(0x23302A);
            text = new Color(0xE9F1EC);
            muted = new Color(0x93A69A);
            faint = new Color(0x6D8175);
            accent = new Color(0x3DDC97);
            accentAlt = new Color(0x2AC98A);
            onAccent = new Color(0x04241A);
            accentSoft = new Color(0x132F23);
            onAccentSoft = new Color(0xA7F3CE);
            danger = new Color(0xFF9E93);
            dangerSoft = new Color(0x3D1E1A);
            success = new Color(0x3DDC97);
            warn = new Color(0xF2CE7A);
            track = new Color(0x1B2620);
            shadow = new Color(0, 0, 0, 96);
            selection = new Color(0x3DDC97, false);
        } else {
            bg = new Color(0xF5F8F6);
            surface = new Color(0xFFFFFF);
            surfaceAlt = new Color(0xF1F6F2);
            card = new Color(0xFFFFFF);
            cardHover = new Color(0xEDF4EF);
            border = new Color(0xDFE8E1);
            text = new Color(0x0D1A13);
            muted = new Color(0x5A6B60);
            faint = new Color(0x82948A);
            accent = new Color(0x0E7C55);
            accentAlt = new Color(0x0A6B49);
            onAccent = new Color(0xFFFFFF);
            accentSoft = new Color(0xDCF3E7);
            onAccentSoft = new Color(0x07331F);
            danger = new Color(0xB42318);
            dangerSoft = new Color(0xFEE4E2);
            success = new Color(0x0E7C55);
            warn = new Color(0x8A6100);
            track = new Color(0xEDF2EE);
            shadow = new Color(24, 46, 34, 22);
            selection = new Color(0xDCF3E7, false);
        }
    }

    static Theme get() {
        Theme value = current;
        if (value == null) {
            value = new Theme(detectSystemDark());
            current = value;
        }
        return value;
    }

    static void setMode(String mode) {
        if (MODE_DARK.equals(mode)) current = new Theme(true);
        else if (MODE_LIGHT.equals(mode)) current = new Theme(false);
        else current = new Theme(detectSystemDark());
        applyDefaults();
    }

    static String modeLabel(String mode) {
        if (MODE_DARK.equals(mode)) return "Dark";
        if (MODE_LIGHT.equals(mode)) return "Light";
        return "System";
    }

    static Font font(float size) {
        return font(size, Font.PLAIN);
    }

    static Font font(float size, int style) {
        return new Font(family, style, Math.round(size));
    }

    private static String monoFamily;

    static Font mono(float size) {
        // Resolved once: listing every installed family is far too slow to
        // repeat for each table cell on every repaint.
        if (monoFamily == null) {
            String mono = System.getProperty("os.name", "").toLowerCase().contains("mac")
                    ? "Menlo" : "Consolas";
            monoFamily = fontExists(mono) ? mono : Font.MONOSPACED;
        }
        return new Font(monoFamily, Font.PLAIN, Math.round(size));
    }

    private static boolean fontExists(String name) {
        for (String candidate : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()) {
            if (candidate.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { }
        Font base = UIManager.getFont("Label.font");
        if (base != null) family = base.getFamily();
        if (family == null || family.isEmpty()) family = "SansSerif";
        applyDefaults();
    }

    static void applyDefaults() {
        Theme t = get();
        UIManager.put("Panel.background", t.bg);
        UIManager.put("Label.foreground", t.text);
        UIManager.put("Label.font", font(13));
        UIManager.put("TextField.background", t.surface);
        UIManager.put("TextField.foreground", t.text);
        UIManager.put("TextField.caretForeground", t.accent);
        UIManager.put("TextField.selectionBackground", t.accentSoft);
        UIManager.put("TextField.selectionForeground", t.onAccentSoft);
        UIManager.put("TextArea.background", t.surface);
        UIManager.put("TextArea.foreground", t.text);
        UIManager.put("ComboBox.background", t.surface);
        UIManager.put("ComboBox.foreground", t.text);
        UIManager.put("ComboBox.selectionBackground", t.accentSoft);
        UIManager.put("ComboBox.selectionForeground", t.onAccentSoft);
        UIManager.put("ComboBox.buttonBackground", t.surfaceAlt);
        UIManager.put("List.background", t.surface);
        UIManager.put("List.foreground", t.text);
        UIManager.put("Table.background", t.surface);
        UIManager.put("Table.foreground", t.text);
        UIManager.put("Table.selectionBackground", t.accentSoft);
        UIManager.put("Table.selectionForeground", t.onAccentSoft);
        UIManager.put("Table.gridColor", t.border);
        UIManager.put("TableHeader.background", t.surfaceAlt);
        UIManager.put("TableHeader.foreground", t.muted);
        UIManager.put("TableHeader.font", font(11.5f, Font.BOLD));
        UIManager.put("ScrollPane.background", t.bg);
        UIManager.put("Viewport.background", t.bg);
        UIManager.put("ScrollBar.thumb", t.border);
        UIManager.put("ScrollBar.track", t.bg);
        UIManager.put("ToolTip.background", t.card);
        UIManager.put("ToolTip.foreground", t.text);
        UIManager.put("ToolTip.border", javax.swing.BorderFactory.createLineBorder(t.border));
        UIManager.put("OptionPane.background", t.surface);
        UIManager.put("OptionPane.messageForeground", t.text);
        UIManager.put("Button.background", t.surfaceAlt);
        UIManager.put("Button.foreground", t.text);
        UIManager.put("PopupMenu.background", t.card);
        UIManager.put("PopupMenu.foreground", t.text);
        UIManager.put("PopupMenu.border",
                javax.swing.BorderFactory.createLineBorder(t.border));
        UIManager.put("MenuBar.background", t.surface);
        UIManager.put("MenuBar.foreground", t.text);
        UIManager.put("MenuBar.font", font(12.5f));
        UIManager.put("MenuBar.border",
                javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, t.border));
        UIManager.put("MenuItem.background", t.card);
        UIManager.put("MenuItem.foreground", t.text);
        UIManager.put("MenuItem.font", font(12.5f));
        UIManager.put("MenuItem.acceleratorForeground", t.faint);
        UIManager.put("MenuItem.selectionBackground", t.accentSoft);
        UIManager.put("MenuItem.selectionForeground", t.onAccentSoft);
        UIManager.put("MenuItem.acceleratorSelectionForeground", t.onAccentSoft);
        UIManager.put("Menu.background", t.card);
        UIManager.put("Menu.foreground", t.text);
        UIManager.put("Menu.selectionBackground", t.accentSoft);
        UIManager.put("Menu.selectionForeground", t.onAccentSoft);
        UIManager.put("Menu.acceleratorForeground", t.faint);
        UIManager.put("Menu.acceleratorSelectionForeground", t.onAccentSoft);
        UIManager.put("RadioButtonMenuItem.background", t.card);
        UIManager.put("RadioButtonMenuItem.foreground", t.text);
        UIManager.put("RadioButtonMenuItem.font", font(12.5f));
        UIManager.put("RadioButtonMenuItem.selectionBackground", t.accentSoft);
        UIManager.put("RadioButtonMenuItem.selectionForeground", t.onAccentSoft);
        UIManager.put("CheckBoxMenuItem.background", t.card);
        UIManager.put("CheckBoxMenuItem.foreground", t.text);
        UIManager.put("CheckBoxMenuItem.font", font(12.5f));
        UIManager.put("CheckBoxMenuItem.selectionBackground", t.accentSoft);
        UIManager.put("CheckBoxMenuItem.selectionForeground", t.onAccentSoft);
        UIManager.put("Separator.foreground", t.border);
        UIManager.put("ProgressBar.background", t.surfaceAlt);
        UIManager.put("ProgressBar.foreground", t.accent);
    }

    static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    static Color mix(Color a, Color b, double amount) {
        double t = Math.max(0, Math.min(1, amount));
        return new Color(
                (int) Math.round(a.getRed() * (1 - t) + b.getRed() * t),
                (int) Math.round(a.getGreen() * (1 - t) + b.getGreen() * t),
                (int) Math.round(a.getBlue() * (1 - t) + b.getBlue() * t));
    }

    /** Like {@link #mix} but also blends the alpha channel. */
    static Color mixAlpha(Color a, Color b, double amount) {
        double t = Math.max(0, Math.min(1, amount));
        return new Color(
                (int) Math.round(a.getRed() * (1 - t) + b.getRed() * t),
                (int) Math.round(a.getGreen() * (1 - t) + b.getGreen() * t),
                (int) Math.round(a.getBlue() * (1 - t) + b.getBlue() * t),
                (int) Math.round(a.getAlpha() * (1 - t) + b.getAlpha() * t));
    }

    private static boolean detectSystemDark() {
        try {
            if (AppDirs.isMac()) {
                Process p = new ProcessBuilder("defaults", "read", "-g",
                        "AppleInterfaceStyle").redirectErrorStream(true).start();
                String out = read(p);
                p.waitFor(800, TimeUnit.MILLISECONDS);
                return out.toLowerCase().contains("dark");
            }
            if (AppDirs.isWindows()) {
                Process p = new ProcessBuilder("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme").redirectErrorStream(true).start();
                String out = read(p);
                p.waitFor(800, TimeUnit.MILLISECONDS);
                return out.contains("0x0");
            }
            String gtk = System.getenv("GTK_THEME");
            if (gtk != null && gtk.toLowerCase().contains("dark")) return true;
            Process p = new ProcessBuilder("gsettings", "get",
                    "org.gnome.desktop.interface", "color-scheme")
                    .redirectErrorStream(true).start();
            String out = read(p);
            p.waitFor(800, TimeUnit.MILLISECONDS);
            if (out.toLowerCase().contains("dark")) return true;
        } catch (Exception ignored) { }
        Color panel = UIManager.getColor("Panel.background");
        return panel != null && panel.getRed() + panel.getGreen() + panel.getBlue() < 380;
    }

    private static String read(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
                if (out.length() > 4096) break;
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
