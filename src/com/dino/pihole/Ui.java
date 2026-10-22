package com.dino.pihole;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Small helpers for the programmatic, D-pad-first TV UI.
 *
 * The colour tokens follow Material 3. On Android 12+ they are taken from the
 * system's dynamic ("Material You") tonal palettes; older devices use a dark
 * Material 3 scheme generated from the Pi-hole green seed colour. The app is
 * deliberately always dark: it is a lean-back TV interface, not a phone app.
 */
final class Ui {

    // Material 3 dark scheme generated from the Pi-hole seed colour #34D399.
    private static final int F_PRIMARY = 0xFF8ED5B2;
    private static final int F_ON_PRIMARY = 0xFF003825;
    private static final int F_PRIMARY_CONTAINER = 0xFF005137;
    private static final int F_ON_PRIMARY_CONTAINER = 0xFFA9F2CD;
    private static final int F_SECONDARY = 0xFFB4CCBD;
    private static final int F_ON_SECONDARY = 0xFF1F352A;
    private static final int F_SECONDARY_CONTAINER = 0xFF364B40;
    private static final int F_ON_SECONDARY_CONTAINER = 0xFFCFE9D9;
    private static final int F_TERTIARY = 0xFFA5CCDE;
    private static final int F_ON_TERTIARY = 0xFF073543;
    private static final int F_TERTIARY_CONTAINER = 0xFF244C5A;
    private static final int F_ON_TERTIARY_CONTAINER = 0xFFC1E9FB;
    private static final int F_ERROR = 0xFFFFB4AB;
    private static final int F_ON_ERROR = 0xFF690005;
    private static final int F_ERROR_CONTAINER = 0xFF93000A;
    private static final int F_ON_ERROR_CONTAINER = 0xFFFFDAD6;
    private static final int F_SURFACE = 0xFF0F1511;
    private static final int F_ON_SURFACE = 0xFFDEE4DE;
    private static final int F_SURFACE_VARIANT = 0xFF404943;
    private static final int F_ON_SURFACE_VARIANT = 0xFFBFC9C1;
    private static final int F_SURFACE_LOWEST = 0xFF0A0F0C;
    private static final int F_SURFACE_LOW = 0xFF171D19;
    private static final int F_SURFACE_CONTAINER = 0xFF1B211D;
    private static final int F_SURFACE_HIGH = 0xFF252B28;
    private static final int F_SURFACE_HIGHEST = 0xFF303632;
    private static final int F_OUTLINE = 0xFF8A938C;
    private static final int F_OUTLINE_VARIANT = 0xFF404943;

    static int PRIMARY;
    static int ON_PRIMARY;
    static int PRIMARY_CONTAINER;
    static int ON_PRIMARY_CONTAINER;
    static int SECONDARY;
    static int ON_SECONDARY;
    static int SECONDARY_CONTAINER;
    static int ON_SECONDARY_CONTAINER;
    static int TERTIARY;
    static int ON_TERTIARY;
    static int TERTIARY_CONTAINER;
    static int ON_TERTIARY_CONTAINER;
    static int ERROR;
    static int ON_ERROR;
    static int ERROR_CONTAINER;
    static int ON_ERROR_CONTAINER;
    static int SURFACE;
    static int ON_SURFACE;
    static int SURFACE_VARIANT;
    static int ON_SURFACE_VARIANT;
    static int SURFACE_CONTAINER_LOWEST;
    static int SURFACE_CONTAINER_LOW;
    static int SURFACE_CONTAINER;
    static int SURFACE_CONTAINER_HIGH;
    static int SURFACE_CONTAINER_HIGHEST;
    static int OUTLINE;
    static int OUTLINE_VARIANT;

    // Friendly names used throughout the screens.
    static int BG;
    static int TEXT;
    static int DIM;
    static int FAINT;
    static int CARD;
    static int CARD_FOCUS;
    static int STROKE;
    static int STROKE_FOCUS;
    static int GREEN;       // primary / success
    static int GREEN_DARK;  // primary container
    static int BLUE;        // secondary
    static int AMBER;       // tertiary / paused
    static int RED;         // error

    private static String paletteKey = "";

    static {
        setFallback();
    }

    private Ui() { }

    private static void setFallback() {
        PRIMARY = F_PRIMARY;
        ON_PRIMARY = F_ON_PRIMARY;
        PRIMARY_CONTAINER = F_PRIMARY_CONTAINER;
        ON_PRIMARY_CONTAINER = F_ON_PRIMARY_CONTAINER;
        SECONDARY = F_SECONDARY;
        ON_SECONDARY = F_ON_SECONDARY;
        SECONDARY_CONTAINER = F_SECONDARY_CONTAINER;
        ON_SECONDARY_CONTAINER = F_ON_SECONDARY_CONTAINER;
        TERTIARY = F_TERTIARY;
        ON_TERTIARY = F_ON_TERTIARY;
        TERTIARY_CONTAINER = F_TERTIARY_CONTAINER;
        ON_TERTIARY_CONTAINER = F_ON_TERTIARY_CONTAINER;
        ERROR = F_ERROR;
        ON_ERROR = F_ON_ERROR;
        ERROR_CONTAINER = F_ERROR_CONTAINER;
        ON_ERROR_CONTAINER = F_ON_ERROR_CONTAINER;
        SURFACE = F_SURFACE;
        ON_SURFACE = F_ON_SURFACE;
        SURFACE_VARIANT = F_SURFACE_VARIANT;
        ON_SURFACE_VARIANT = F_ON_SURFACE_VARIANT;
        SURFACE_CONTAINER_LOWEST = F_SURFACE_LOWEST;
        SURFACE_CONTAINER_LOW = F_SURFACE_LOW;
        SURFACE_CONTAINER = F_SURFACE_CONTAINER;
        SURFACE_CONTAINER_HIGH = F_SURFACE_HIGH;
        SURFACE_CONTAINER_HIGHEST = F_SURFACE_HIGHEST;
        OUTLINE = F_OUTLINE;
        OUTLINE_VARIANT = F_OUTLINE_VARIANT;
        syncAliases();
    }

    private static void syncAliases() {
        BG = SURFACE;
        TEXT = ON_SURFACE;
        DIM = ON_SURFACE_VARIANT;
        FAINT = OUTLINE;
        CARD = withAlpha(SURFACE_CONTAINER, 0xF2);
        CARD_FOCUS = SURFACE_CONTAINER_HIGH;
        STROKE = withAlpha(OUTLINE_VARIANT, 0x99);
        STROKE_FOCUS = withAlpha(PRIMARY, 0xCC);
        GREEN = PRIMARY;
        GREEN_DARK = PRIMARY_CONTAINER;
        BLUE = SECONDARY;
        AMBER = TERTIARY;
        RED = ERROR;
    }

    /**
     * Loads the current Material 3 palette. Returns true when the palette
     * differs from the one already applied to this process.
     */
    static boolean init(Context c) {
        int primary = systemColor(c, "system_accent1_800", F_PRIMARY);
        int primaryContainer = systemColor(c, "system_accent1_300", F_PRIMARY_CONTAINER);
        int secondary = systemColor(c, "system_accent2_800", F_SECONDARY);
        int secondaryContainer = systemColor(c, "system_accent2_300", F_SECONDARY_CONTAINER);
        int tertiary = systemColor(c, "system_accent3_800", F_TERTIARY);
        int tertiaryContainer = systemColor(c, "system_accent3_300", F_TERTIARY_CONTAINER);
        int surface = systemColor(c, "system_neutral1_50", F_SURFACE);
        int onSurface = systemColor(c, "system_neutral1_900", F_ON_SURFACE);

        String key = Integer.toHexString(primary) + ':'
                + Integer.toHexString(secondary) + ':'
                + Integer.toHexString(tertiary) + ':'
                + Integer.toHexString(surface) + ':'
                + Integer.toHexString(onSurface);
        boolean changed = !key.equals(paletteKey);

        PRIMARY = primary;
        ON_PRIMARY = systemColor(c, "system_accent1_200", F_ON_PRIMARY);
        PRIMARY_CONTAINER = primaryContainer;
        ON_PRIMARY_CONTAINER = systemColor(c, "system_accent1_900", F_ON_PRIMARY_CONTAINER);
        SECONDARY = secondary;
        ON_SECONDARY = systemColor(c, "system_accent2_200", F_ON_SECONDARY);
        SECONDARY_CONTAINER = secondaryContainer;
        ON_SECONDARY_CONTAINER = systemColor(c, "system_accent2_900", F_ON_SECONDARY_CONTAINER);
        TERTIARY = tertiary;
        ON_TERTIARY = systemColor(c, "system_accent3_200", F_ON_TERTIARY);
        TERTIARY_CONTAINER = tertiaryContainer;
        ON_TERTIARY_CONTAINER = systemColor(c, "system_accent3_900", F_ON_TERTIARY_CONTAINER);
        ERROR = systemColor(c, "system_error_800", F_ERROR);
        ON_ERROR = systemColor(c, "system_error_200", F_ON_ERROR);
        ERROR_CONTAINER = systemColor(c, "system_error_300", F_ERROR_CONTAINER);
        ON_ERROR_CONTAINER = systemColor(c, "system_error_900", F_ON_ERROR_CONTAINER);
        SURFACE = surface;
        ON_SURFACE = onSurface;
        SURFACE_VARIANT = systemColor(c, "system_neutral2_300", F_SURFACE_VARIANT);
        ON_SURFACE_VARIANT = systemColor(c, "system_neutral2_800", F_ON_SURFACE_VARIANT);
        SURFACE_CONTAINER_LOWEST = mix(surface, Color.BLACK, 0.32f);
        SURFACE_CONTAINER_LOW = systemColor(c, "system_neutral1_100",
                mix(surface, Color.WHITE, 0.035f));
        SURFACE_CONTAINER = systemColor(c, "system_neutral1_100",
                mix(surface, Color.WHITE, 0.065f));
        SURFACE_CONTAINER_HIGH = systemColor(c, "system_neutral1_200",
                mix(surface, Color.WHITE, 0.11f));
        SURFACE_CONTAINER_HIGHEST = systemColor(c, "system_neutral1_300",
                mix(surface, Color.WHITE, 0.16f));
        OUTLINE = systemColor(c, "system_neutral2_600", F_OUTLINE);
        OUTLINE_VARIANT = systemColor(c, "system_neutral2_300", F_OUTLINE_VARIANT);
        syncAliases();
        paletteKey = key;
        return changed;
    }

    private static int systemColor(Context c, String name, int fallback) {
        if (Build.VERSION.SDK_INT < 31 || c == null) return fallback;
        try {
            int id = c.getResources().getIdentifier(name, "color", "android");
            if (id == 0) return fallback;
            int color = c.getColor(id);
            return Color.alpha(color) == 0xFF ? color : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    static int dp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, String value, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (medium) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        } else {
            t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        return t;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, int l, int t, int r, int b) {
        p.setMargins(l, t, r, b);
        return p;
    }

    static View spacer(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
        return v;
    }

    static GradientDrawable rounded(Context c, int fill, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable rounded(Context c, int fill, float radiusDp, int strokeWidthDp, int stroke) {
        GradientDrawable d = rounded(c, fill, radiusDp);
        if (strokeWidthDp > 0) d.setStroke(dp(c, strokeWidthDp), stroke);
        return d;
    }

    static StateListDrawable focusableBg(Context c, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused},
                rounded(c, CARD_FOCUS, radiusDp, 2, STROKE_FOCUS));
        s.addState(new int[]{android.R.attr.state_pressed},
                rounded(c, CARD_FOCUS, radiusDp, 2, STROKE_FOCUS));
        s.addState(new int[]{}, rounded(c, CARD, radiusDp, 1, STROKE));
        return s;
    }

    static void focusScale(final View v) {
        v.setOnFocusChangeListener((view, focused) -> {
            float scale = focused ? 1.02f : 1f;
            view.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        });
    }

    /** A focusable card used for clickable rows and buttons. */
    static LinearLayout focusableCard(Context c, float radiusDp) {
        LinearLayout l = column(c);
        l.setBackground(focusableBg(c, radiusDp));
        l.setFocusable(true);
        l.setClickable(true);
        l.setPadding(dp(c, 22), dp(c, 16), dp(c, 22), dp(c, 16));
        focusScale(l);
        return l;
    }

    static TextView button(Context c, String label, boolean primary) {
        TextView b = text(c, label, 15, primary ? ON_PRIMARY : PRIMARY, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 28), dp(c, 14), dp(c, 28), dp(c, 14));
        b.setMinWidth(dp(c, 160));
        b.setFocusable(true);
        b.setClickable(true);

        ColorStateList colors;
        StateListDrawable s = new StateListDrawable();
        if (primary) {
            colors = new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_focused},
                            new int[]{android.R.attr.state_pressed},
                            new int[]{}},
                    new int[]{ON_PRIMARY, ON_PRIMARY, ON_PRIMARY});
            s.addState(new int[]{android.R.attr.state_focused},
                    rounded(c, PRIMARY, 999, 2, withAlpha(ON_PRIMARY, 0xCC)));
            s.addState(new int[]{android.R.attr.state_pressed},
                    rounded(c, PRIMARY, 999, 2, ON_PRIMARY));
            s.addState(new int[]{}, rounded(c, PRIMARY, 999, 1, PRIMARY));
        } else {
            colors = new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_focused},
                            new int[]{android.R.attr.state_pressed},
                            new int[]{}},
                    new int[]{ON_PRIMARY_CONTAINER, ON_SECONDARY_CONTAINER, PRIMARY});
            s.addState(new int[]{android.R.attr.state_focused},
                    rounded(c, PRIMARY_CONTAINER, 999, 2, PRIMARY));
            s.addState(new int[]{android.R.attr.state_pressed},
                    rounded(c, SECONDARY_CONTAINER, 999, 2, SECONDARY));
            s.addState(new int[]{}, rounded(c, Color.TRANSPARENT, 999, 1, OUTLINE));
        }
        b.setTextColor(colors);
        b.setBackground(s);
        focusScale(b);
        return b;
    }

    static TextView chip(Context c) {
        TextView t = text(c, "", 11, ON_PRIMARY_CONTAINER, true);
        t.setLetterSpacing(0.12f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 15), dp(c, 8), dp(c, 15), dp(c, 8));
        t.setBackground(rounded(c, PRIMARY_CONTAINER, 999, 1,
                withAlpha(PRIMARY, 0x66)));
        return t;
    }

    static void setChip(TextView chip, String label, int foreground, int background, int stroke) {
        chip.setText(label);
        chip.setTextColor(foreground);
        chip.setBackground(rounded(chip.getContext(), background, 999, 1, stroke));
    }

    static TextView caption(Context c, String value) {
        TextView t = text(c, value, 12, FAINT, true);
        t.setLetterSpacing(0.14f);
        return t;
    }

    static void styleInput(EditText input) {
        input.setTextColor(TEXT);
        input.setHintTextColor(FAINT);
        input.setHighlightColor(withAlpha(PRIMARY, 0x44));
        input.setBackgroundTintList(ColorStateList.valueOf(PRIMARY));
    }

    static AlertDialog.Builder builder(Context c) {
        return new ThemedBuilder(c);
    }

    /** A builder whose show() also matches the dialog text to the dynamic palette. */
    private static final class ThemedBuilder extends AlertDialog.Builder {
        ThemedBuilder(Context c) {
            super(c, R.style.Theme_PiholeTV_Dialog);
        }

        @Override public AlertDialog show() {
            AlertDialog dialog = super.show();
            tintDialog(dialog);
            return dialog;
        }
    }

    static void tintDialog(AlertDialog dialog) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window != null) window.setDimAmount(0.68f);
        int titleId = dialog.getContext().getResources()
                .getIdentifier("alertTitle", "id", "android");
        TextView title = titleId == 0 ? null : dialog.findViewById(titleId);
        if (title != null) title.setTextColor(TEXT);
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) message.setTextColor(DIM);
        int[] buttons = {android.R.id.button1, android.R.id.button2, android.R.id.button3};
        for (int id : buttons) {
            TextView v = dialog.findViewById(id);
            if (v != null) v.setTextColor(PRIMARY);
        }
    }

    static int mix(int a, int b, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    static void toast(Context c, String message) {
        Toast.makeText(c, message, Toast.LENGTH_SHORT).show();
    }

    static String count(long n) {
        return String.format(java.util.Locale.US, "%,d", n);
    }

    static String percent(double p) {
        return String.format(java.util.Locale.US, "%.1f%%", p);
    }

    static String ago(long millis) {
        if (millis <= 0) return "never";
        long s = Math.max(0, (System.currentTimeMillis() - millis) / 1000L);
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        return (s / 86400) + " d ago";
    }
}
