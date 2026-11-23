package com.dino.holeshield;

import java.util.Locale;

/** Accent color and derived contrast roles, independent of UI toolkit. */
final class AccentPalette {
    static final AccentPalette DEFAULT = new AccentPalette("default", -1);
    final String id;
    private final int rgb;

    private AccentPalette(String id, int rgb) {
        this.id = id;
        this.rgb = rgb;
    }

    static AccentPalette fromId(String value) {
        if (value == null) return DEFAULT;
        switch (value) {
            case "blue": value = "#99CAFF"; break;
            case "rose": value = "#FFB3DA"; break;
            case "slate": value = "#C5CEDD"; break;
            default: break;
        }
        if (!value.matches("#?[0-9a-fA-F]{6}")) return DEFAULT;
        String hex = value.charAt(0) == '#' ? value : "#" + value;
        return new AccentPalette(hex.toUpperCase(Locale.ROOT), Integer.parseInt(hex.substring(1), 16));
    }

    int chosen(boolean dark) { return rgb < 0 ? dark ? 0xC9B5FF : 0x1B7F5C : rgb; }

    int primary(boolean dark) {
        int color = chosen(dark);
        int bg = dark ? 0x262626 : 0xFFFFFF;
        int toward = dark ? 0xFFFFFF : 0;
        while (contrast(color, bg) < 4.5) color = mix(color, toward, .12);
        return color;
    }

    int onPrimary(boolean dark) {
        if (this == DEFAULT) return dark ? 0x23183D : 0xFFFFFF;
        int color = primary(dark);
        return contrast(color, 0) >= contrast(color, 0xFFFFFF) ? 0 : 0xFFFFFF;
    }

    int container(boolean dark) {
        if (this == DEFAULT) return dark ? 0x35275C : 0xA9F2CD;
        return mix(dark ? 0x202020 : 0xFFFFFF, chosen(dark), .24);
    }

    int onContainer(boolean dark) {
        if (this == DEFAULT) return dark ? 0xEADFFF : 0x002115;
        return dark ? 0xFFFFFF : 0x171717;
    }

    static String hex(int rgb) { return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF); }

    private static int mix(int a, int b, double fraction) {
        int color = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int x = (a >> shift) & 255, y = (b >> shift) & 255;
            color |= (int) Math.round(x + (y - x) * fraction) << shift;
        }
        return color;
    }

    private static double contrast(int a, int b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }

    private static double luminance(int color) {
        return .2126 * linear(color >> 16) + .7152 * linear(color >> 8) + .0722 * linear(color);
    }

    private static double linear(int channel) {
        double x = (channel & 255) / 255.0;
        return x <= .04045 ? x / 12.92 : Math.pow((x + .055) / 1.055, 2.4);
    }
}
