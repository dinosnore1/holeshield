package com.dino.holeshield;

import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Small helpers for the programmatic, D-pad and touch UI.
 *
 * Material 3 roles with fixed palettes for consistent phone and TV styling.
 */
final class Ui {

    // Graphite and violet dark palette, also used by the web dashboard.
    private static final int F_SECONDARY = 0xFFA7BFFF;
    private static final int F_ON_SECONDARY = 0xFF152544;
    private static final int F_SECONDARY_CONTAINER = 0xFF222F52;
    private static final int F_ON_SECONDARY_CONTAINER = 0xFFDBE5FF;
    private static final int F_TERTIARY = 0xFFF5C98B;
    private static final int F_ON_TERTIARY = 0xFF422905;
    private static final int F_TERTIARY_CONTAINER = 0xFF493019;
    private static final int F_ON_TERTIARY_CONTAINER = 0xFFFFDDB1;
    private static final int F_ERROR = 0xFFFFACA9;
    private static final int F_ON_ERROR = 0xFF551A24;
    private static final int F_ERROR_CONTAINER = 0xFF4F212F;
    private static final int F_ON_ERROR_CONTAINER = 0xFFFFD9D7;
    private static final int F_SURFACE = 0xFF11111A;
    private static final int F_ON_SURFACE = 0xFFF2EDF9;
    private static final int F_SURFACE_VARIANT = 0xFF2C2B3D;
    private static final int F_ON_SURFACE_VARIANT = 0xFFBDB7CC;
    private static final int F_SURFACE_LOWEST = 0xFF0D0D14;
    private static final int F_SURFACE_LOW = 0xFF15151F;
    private static final int F_SURFACE_CONTAINER = 0xFF191923;
    private static final int F_SURFACE_HIGH = 0xFF202031;
    private static final int F_SURFACE_HIGHEST = 0xFF29293D;
    private static final int F_OUTLINE = 0xFF938AA9;
    private static final int F_OUTLINE_VARIANT = 0xFF36334A;

    // Keep the existing Material 3 light scheme.
    private static final int L_SECONDARY = 0xFF4D6357;
    private static final int L_ON_SECONDARY = 0xFFFFFFFF;
    private static final int L_SECONDARY_CONTAINER = 0xFFCFE9D9;
    private static final int L_ON_SECONDARY_CONTAINER = 0xFF0A1F16;
    private static final int L_TERTIARY = 0xFF3D6373;
    private static final int L_ON_TERTIARY = 0xFFFFFFFF;
    private static final int L_TERTIARY_CONTAINER = 0xFFC1E9FB;
    private static final int L_ON_TERTIARY_CONTAINER = 0xFF001F29;
    private static final int L_ERROR = 0xFFBA1A1A;
    private static final int L_ON_ERROR = 0xFFFFFFFF;
    private static final int L_ERROR_CONTAINER = 0xFFFFDAD6;
    private static final int L_ON_ERROR_CONTAINER = 0xFF410002;
    private static final int L_SURFACE = 0xFFF7FBF7;
    private static final int L_ON_SURFACE = 0xFF191C19;
    private static final int L_SURFACE_VARIANT = 0xFFDFE4DE;
    private static final int L_ON_SURFACE_VARIANT = 0xFF414942;
    private static final int L_SURFACE_LOWEST = 0xFFFFFFFF;
    private static final int L_SURFACE_LOW = 0xFFF1F5F1;
    private static final int L_SURFACE_CONTAINER = 0xFFEBEFEB;
    private static final int L_SURFACE_HIGH = 0xFFE5EAE5;
    private static final int L_SURFACE_HIGHEST = 0xFFDFE4DF;
    private static final int L_OUTLINE = 0xFF717971;
    private static final int L_OUTLINE_VARIANT = 0xFFC1C9C1;

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

    /** True when the current palette is dark. */
    static boolean dark = true;
    private static AccentPalette accent = AccentPalette.DEFAULT;

    /** Smaller paddings and controls while running on a phone. */
    static boolean compact = false;

    static {
        applyPalette(true, AccentPalette.DEFAULT);
    }

    private Ui() { }

    static void setCompact(boolean on) {
        compact = on;
    }

    private static void setDark() {
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
    }

    private static void setLight() {
        SECONDARY = L_SECONDARY;
        ON_SECONDARY = L_ON_SECONDARY;
        SECONDARY_CONTAINER = L_SECONDARY_CONTAINER;
        ON_SECONDARY_CONTAINER = L_ON_SECONDARY_CONTAINER;
        TERTIARY = L_TERTIARY;
        ON_TERTIARY = L_ON_TERTIARY;
        TERTIARY_CONTAINER = L_TERTIARY_CONTAINER;
        ON_TERTIARY_CONTAINER = L_ON_TERTIARY_CONTAINER;
        ERROR = L_ERROR;
        ON_ERROR = L_ON_ERROR;
        ERROR_CONTAINER = L_ERROR_CONTAINER;
        ON_ERROR_CONTAINER = L_ON_ERROR_CONTAINER;
        SURFACE = L_SURFACE;
        ON_SURFACE = L_ON_SURFACE;
        SURFACE_VARIANT = L_SURFACE_VARIANT;
        ON_SURFACE_VARIANT = L_ON_SURFACE_VARIANT;
        SURFACE_CONTAINER_LOWEST = L_SURFACE_LOWEST;
        SURFACE_CONTAINER_LOW = L_SURFACE_LOW;
        SURFACE_CONTAINER = L_SURFACE_CONTAINER;
        SURFACE_CONTAINER_HIGH = L_SURFACE_HIGH;
        SURFACE_CONTAINER_HIGHEST = L_SURFACE_HIGHEST;
        OUTLINE = L_OUTLINE;
        OUTLINE_VARIANT = L_OUTLINE_VARIANT;
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
    static boolean init(boolean darkMode, String accentId) {
        AccentPalette next = AccentPalette.fromId(accentId);
        if (dark == darkMode && accent == next) return false;
        applyPalette(darkMode, next);
        return true;
    }

    private static void applyPalette(boolean darkMode, AccentPalette next) {
        if (darkMode) setDark();
        else setLight();
        dark = darkMode;
        accent = next;
        PRIMARY = 0xFF000000 | next.primary(dark);
        ON_PRIMARY = 0xFF000000 | next.onPrimary(dark);
        PRIMARY_CONTAINER = 0xFF000000 | next.container(dark);
        ON_PRIMARY_CONTAINER = 0xFF000000 | next.onContainer(dark);
        syncAliases();
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
        s.addState(new int[]{android.R.attr.state_focused}, focusLayer(c, radiusDp));
        s.addState(new int[]{android.R.attr.state_pressed}, focusLayer(c, radiusDp));
        s.addState(new int[]{}, rounded(c, CARD, radiusDp, 1, STROKE));
        return s;
    }

    /** Focused card: tinted surface, bright outline and a left accent bar. */
    private static LayerDrawable focusLayer(Context c, float radiusDp) {
        GradientDrawable base = rounded(c, mix(CARD, PRIMARY, 0.12f), radiusDp, 3, PRIMARY);
        GradientDrawable bar = rounded(c, PRIMARY, 99);
        LayerDrawable layer = new LayerDrawable(new Drawable[]{base, bar});
        layer.setLayerWidth(1, dp(c, 6));
        layer.setLayerGravity(1, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        layer.setLayerInset(1, 0, dp(c, 14), 0, dp(c, 14));
        return layer;
    }

    /** A short hardware-layer lift and scale for focus changes. */
    static void focusScale(final View v) {
        v.setOnFocusChangeListener((view, focused) -> {
            float z = focused ? dp(view.getContext(), 4) : 0f;
            float scale = focused ? 1.01f : 1f;
            view.animate()
                    .translationZ(z)
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(110)
                    .setInterpolator(new DecelerateInterpolator())
                    .withLayer()
                    .start();
        });
    }

    /** Slides a newly shown screen into place. Translation only: a full-screen layer here 
        dropped frames on slower devices. */
    static void animateIn(View view) {
        view.setTranslationY(dp(view.getContext(), 10));
        view.animate()
                .translationY(0f)
                .setDuration(160)
                .setInterpolator(new DecelerateInterpolator())
                .withLayer()
                .start();
    }

    /** A focusable card used for clickable rows and buttons. */
    static LinearLayout focusableCard(Context c, float radiusDp) {
        LinearLayout l = column(c);
        l.setBackground(focusableBg(c, radiusDp));
        l.setFocusable(true);
        l.setClickable(true);
        int h = compact ? 16 : 22;
        int v = compact ? 13 : 16;
        l.setPadding(dp(c, h), dp(c, v), dp(c, h), dp(c, v));
        focusScale(l);
        return l;
    }

    static TextView button(Context c, String label, boolean primary) {
        TextView b = text(c, label, compact ? 14 : 15,
                primary ? ON_PRIMARY : PRIMARY, true);
        b.setGravity(Gravity.CENTER);
        int h = compact ? 18 : 28;
        int v = compact ? 12 : 14;
        b.setPadding(dp(c, h), dp(c, v), dp(c, h), dp(c, v));
        b.setMinWidth(dp(c, compact ? 0 : 160));
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
        TextView t = text(c, "", compact ? 10.5f : 11, ON_PRIMARY_CONTAINER, true);
        t.setLetterSpacing(0.12f);
        t.setGravity(Gravity.CENTER);
        int h = compact ? 12 : 15;
        int v = compact ? 6 : 8;
        t.setPadding(dp(c, h), dp(c, v), dp(c, h), dp(c, v));
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
        TextView t = text(c, value, compact ? 11 : 12, FAINT, true);
        t.setLetterSpacing(compact ? 0.12f : 0.14f);
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
            super(c, dark ? R.style.Theme_HoleShield_Dialog
                    : R.style.Theme_HoleShield_Dialog_Light);
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
        if (window != null) {
            window.setDimAmount(0.68f);
            View decor = window.getDecorView();
            decor.setAlpha(0f);
            decor.setScaleX(0.96f);
            decor.setScaleY(0.96f);
            decor.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(160)
                    .setInterpolator(new DecelerateInterpolator())
                    .withLayer()
                    .start();
        }
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

    /** Short form for big counts that must fit a stat card, e.g. 1,357,708 to 1.36M. */
    static String compactCount(long n) {
        if (n >= 1000000) return String.format(java.util.Locale.US, "%.2fM", n / 1000000.0);
        if (n >= 100000) return (n / 1000) + "K";
        return count(n);
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

    /**
     * A rotating ring used in place of a button glyph while work is in flight.
     * Start it when the work begins and stop it before swapping the compound
     * drawable back to a static icon.
     */
    static final class SpinnerDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator animator;
        private final int size;
        private float angle;

        SpinnerDrawable(Context c, int color) {
            size = dp(c, 18);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(c, 2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(color);
            animator = ValueAnimator.ofFloat(0f, 360f);
            animator.setDuration(850);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(a -> {
                angle = (float) a.getAnimatedValue();
                invalidateSelf();
            });
        }

        void start() {
            if (!animator.isStarted()) animator.start();
        }

        void stop() {
            animator.cancel();
            angle = 0;
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float inset = Math.max(1.5f, size * 0.12f);
            canvas.drawArc(b.left + inset, b.top + inset,
                    b.right - inset, b.bottom - inset,
                    angle, 270, false, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }

        @Override public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

        @Override public int getIntrinsicWidth() { return size; }

        @Override public int getIntrinsicHeight() { return size; }
    }
}
