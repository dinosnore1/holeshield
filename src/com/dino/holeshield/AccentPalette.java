package com.dino.holeshield;

/** User-selectable accent roles. Backgrounds and warning/error colors are separate. */
enum AccentPalette {
    DEFAULT("default", "Default", 0xC9B5FF, 0x23183D, 0x35275C, 0xEADFFF,
            0x1B7F5C, 0xFFFFFF, 0xA9F2CD, 0x002115),
    BLUE("blue", "Sky blue", 0x99CAFF, 0x0B2948, 0x203B61, 0xD3E8FF,
            0x205CA6, 0xFFFFFF, 0xD7E9FF, 0x102E52),
    ROSE("rose", "Rose", 0xFFB3DA, 0x3E1730, 0x572640, 0xFFDCF0,
            0x983568, 0xFFFFFF, 0xFADDED, 0x4C1735),
    SLATE("slate", "Slate", 0xC5CEDD, 0x1E293A, 0x333E50, 0xE1E9F7,
            0x465B75, 0xFFFFFF, 0xDFE8F4, 0x25364D);

    final String id;
    final String label;
    private final int[] dark;
    private final int[] light;

    AccentPalette(String id, String label, int... colors) {
        this.id = id;
        this.label = label;
        dark = java.util.Arrays.copyOfRange(colors, 0, 4);
        light = java.util.Arrays.copyOfRange(colors, 4, 8);
    }

    int primary(boolean darkMode) { return colors(darkMode)[0]; }
    int onPrimary(boolean darkMode) { return colors(darkMode)[1]; }
    int container(boolean darkMode) { return colors(darkMode)[2]; }
    int onContainer(boolean darkMode) { return colors(darkMode)[3]; }

    private int[] colors(boolean darkMode) { return darkMode ? dark : light; }

    static AccentPalette fromId(String id) {
        for (AccentPalette palette : values()) if (palette.id.equals(id)) return palette;
        return DEFAULT;
    }

    static String[] labels() {
        AccentPalette[] palettes = values();
        String[] labels = new String[palettes.length];
        for (int i = 0; i < palettes.length; i++) labels[i] = palettes[i].label;
        return labels;
    }
}
