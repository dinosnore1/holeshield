package com.dino.pihole;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Small helpers for the programmatic, D-pad-first TV UI. */
final class Ui {

    static final int BG = 0xFF05080D;
    static final int TEXT = 0xFFF1F5F9;
    static final int DIM = 0xFF94A3B8;
    static final int FAINT = 0xFF64748B;
    static final int CARD = 0xE60D1622;
    static final int CARD_FOCUS = 0xFF17293C;
    static final int STROKE = 0x1FFFFFFF;
    static final int STROKE_FOCUS = 0x6634D399;
    static final int GREEN = 0xFF34D399;
    static final int GREEN_DARK = 0xFF059669;
    static final int BLUE = 0xFF60A5FA;
    static final int AMBER = 0xFFFBBF24;
    static final int RED = 0xFFF87171;

    private Ui() { }

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
                rounded(c, CARD_FOCUS, radiusDp, 1, STROKE_FOCUS));
        s.addState(new int[]{android.R.attr.state_pressed},
                rounded(c, CARD_FOCUS, radiusDp, 1, STROKE_FOCUS));
        s.addState(new int[]{}, rounded(c, CARD, radiusDp, 1, STROKE));
        return s;
    }

    static void focusScale(final View v) {
        v.setOnFocusChangeListener((view, focused) -> {
            float scale = focused ? 1.025f : 1f;
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
        TextView b = text(c, label, 15, primary ? 0xFF04120C : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 26), dp(c, 13), dp(c, 26), dp(c, 13));
        b.setMinWidth(dp(c, 150));
        b.setFocusable(true);
        b.setClickable(true);
        StateListDrawable s = new StateListDrawable();
        if (primary) {
            s.addState(new int[]{android.R.attr.state_focused},
                    rounded(c, 0xFF6EE7B7, 999, 2, 0xFFFFFFFF));
            s.addState(new int[]{}, rounded(c, GREEN, 999, 2, GREEN));
        } else {
            s.addState(new int[]{android.R.attr.state_focused},
                    rounded(c, 0xFF203447, 999, 2, 0xAAFFFFFF));
            s.addState(new int[]{}, rounded(c, 0x1AFFFFFF, 999, 1, 0x33FFFFFF));
        }
        b.setBackground(s);
        focusScale(b);
        return b;
    }

    static TextView chip(Context c) {
        TextView t = text(c, "", 11, GREEN, true);
        t.setLetterSpacing(0.12f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 14), dp(c, 7), dp(c, 14), dp(c, 7));
        t.setBackground(rounded(c, 0x1A34D399, 999, 1, 0x5534D399));
        return t;
    }

    static TextView caption(Context c, String value) {
        TextView t = text(c, value, 12, FAINT, true);
        t.setLetterSpacing(0.14f);
        return t;
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

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
