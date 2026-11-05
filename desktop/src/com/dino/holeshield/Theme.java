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
    final Color onAccent;
    final Color accentSoft;
    final Color onAccentSoft;
    final Color danger;
    final Color dangerSoft;
    final Color success;
    final Color warn;
    final Color shadow;
    final Color selection;

    private Theme(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = new Color(0x0D1512);
            surface = new Color(0x111B16);
            surfaceAlt = new Color(0x16221C);
            card = new Color(0x17251E);
            cardHover = new Color(0x1D2E26);
            border = new Color(0x2A3C33);
            text = new Color(0xE8F3EC);
            muted = new Color(0x97AC9F);
            faint = new Color(0x6C8175);
            accent = new Color(0x8ED5B2);
            onAccent = new Color(0x003825);
            accentSoft = new Color(0x1C4A37);
            onAccentSoft = new Color(0xB9F4D6);
            danger = new Color(0xFFB4AB);
            dangerSoft = new Color(0x4E211E);
            success = new Color(0x8ED5B2);
            warn = new Color(0xF2D27D);
            shadow = new Color(0, 0, 0, 70);
            selection = new Color(0x8ED5B2, false);
        } else {
            bg = new Color(0xF2F6F3);
            surface = new Color(0xFFFFFF);
            surfaceAlt = new Color(0xF7FAF8);
            card = new Color(0xFFFFFF);
            cardHover = new Color(0xEFF6F1);
            border = new Color(0xD9E4DC);
            text = new Color(0x13221A);
            muted = new Color(0x5C6F64);
            faint = new Color(0x84968A);
            accent = new Color(0x1F6B4C);
            onAccent = new Color(0xFFFFFF);
            accentSoft = new Color(0xC9EBD9);
            onAccentSoft = new Color(0x07331F);
            danger = new Color(0xB3261E);
            dangerSoft = new Color(0xF9DEDC);
            success = new Color(0x1F6B4C);
            warn = new Color(0x7A5B00);
            shadow = new Color(30, 50, 40, 24);
            selection = new Color(0xC9EBD9, false);
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

    static Font mono(float size) {
        String mono = System.getProperty("os.name", "").toLowerCase().contains("mac")
                ? "Menlo" : "Consolas";
        if (!fontExists(mono)) mono = Font.MONOSPACED;
        return new Font(mono, Font.PLAIN, Math.round(size));
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
        UIManager.put("MenuItem.background", t.card);
        UIManager.put("MenuItem.foreground", t.text);
        UIManager.put("MenuItem.selectionBackground", t.accentSoft);
        UIManager.put("MenuItem.selectionForeground", t.onAccentSoft);
        UIManager.put("Menu.background", t.card);
        UIManager.put("Menu.foreground", t.text);
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
