package com.dino.holeshield;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** Tonal Material 3 background with three very slow colour glows. No bitmaps. */
final class Backdrop extends View {

    private final Paint basePaint = new Paint();
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient base;
    private RadialGradient primaryGlow;
    private RadialGradient secondaryGlow;
    private RadialGradient tertiaryGlow;
    private int w;
    private int h;
    private long start;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            invalidate();
            postDelayed(this, 90);
        }
    };

    Backdrop(Context c) {
        super(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        glowPaint.setDither(true);
    }

    @Override protected void onSizeChanged(int width, int height, int oldw, int oldh) {
        super.onSizeChanged(width, height, oldw, oldh);
        w = Math.max(1, width);
        h = Math.max(1, height);
        base = new LinearGradient(0, 0, 0, h,
                new int[]{
                        Ui.mix(Ui.BG, Ui.PRIMARY_CONTAINER, 0.28f),
                        Ui.mix(Ui.BG, Ui.SURFACE_CONTAINER_LOW, 0.45f),
                        Ui.mix(Ui.BG, Ui.TERTIARY_CONTAINER, 0.16f)
                },
                new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP);
        basePaint.setShader(base);

        float r = Math.max(w, h) * 0.55f;
        primaryGlow = new RadialGradient(0, 0, r,
                new int[]{Ui.withAlpha(Ui.PRIMARY, 0x30),
                        Ui.withAlpha(Ui.PRIMARY, 0x14), Ui.withAlpha(Ui.PRIMARY, 0x00)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        secondaryGlow = new RadialGradient(0, 0, r * 0.9f,
                new int[]{Ui.withAlpha(Ui.SECONDARY, 0x26),
                        Ui.withAlpha(Ui.SECONDARY, 0x10), Ui.withAlpha(Ui.SECONDARY, 0x00)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        tertiaryGlow = new RadialGradient(0, 0, r * 0.8f,
                new int[]{Ui.withAlpha(Ui.TERTIARY, 0x22),
                        Ui.withAlpha(Ui.TERTIARY, 0x0D), Ui.withAlpha(Ui.TERTIARY, 0x00)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start = SystemClock.uptimeMillis();
        removeCallbacks(frame);
        postDelayed(frame, 90);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        removeCallbacks(frame);
        if (visibility == VISIBLE) postDelayed(frame, 90);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, w, h, basePaint);
        long t = SystemClock.uptimeMillis() - start;
        double a = t / 19000.0;
        double b = t / 27000.0;
        double c = t / 23000.0;

        drawGlow(canvas, primaryGlow,
                w * 0.20f + (float) Math.sin(a) * w * 0.05f,
                h * 0.10f + (float) Math.cos(a * 1.3) * h * 0.04f,
                Math.max(w, h) * 0.55f, 190);
        drawGlow(canvas, secondaryGlow,
                w * 0.82f + (float) Math.cos(b) * w * 0.05f,
                h * 0.22f + (float) Math.sin(b * 1.1) * h * 0.05f,
                Math.max(w, h) * 0.50f, 170);
        drawGlow(canvas, tertiaryGlow,
                w * 0.62f + (float) Math.sin(c * 1.2) * w * 0.06f,
                h * 0.95f + (float) Math.cos(c) * h * 0.03f,
                Math.max(w, h) * 0.45f, 150);
    }

    private void drawGlow(Canvas canvas, RadialGradient shader, float x, float y, float r, int alpha) {
        if (shader == null) return;
        glowPaint.setShader(shader);
        glowPaint.setAlpha(alpha);
        canvas.save();
        canvas.translate(x, y);
        canvas.drawCircle(0, 0, r, glowPaint);
        canvas.restore();
    }
}
