package com.dino.pihole;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** A drawn shield: green with a check when protected, slate with a pause bar when off. */
final class ShieldView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shield = new Path();
    private final Path check = new Path();
    private final RectF dot = new RectF();
    private boolean on = false;
    private int w;
    private int h;
    private long start;

    ShieldView(Context c) {
        super(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        stroke.setStyle(Paint.Style.STROKE);
        mark.setStyle(Paint.Style.STROKE);
        mark.setStrokeCap(Paint.Cap.ROUND);
        mark.setStrokeJoin(Paint.Join.ROUND);
        halo.setStyle(Paint.Style.STROKE);
        start = SystemClock.uptimeMillis();
    }

    void setOn(boolean value) {
        if (on == value) return;
        on = value;
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldw, int oldh) {
        super.onSizeChanged(width, height, oldw, oldh);
        w = Math.max(1, width);
        h = Math.max(1, height);
        float s = Math.min(w, h) * 0.86f;
        float ox = (w - s) / 2f;
        float oy = (h - s) / 2f;
        shield.reset();
        shield.moveTo(ox + s * 0.50f, oy + s * 0.05f);
        shield.lineTo(ox + s * 0.89f, oy + s * 0.18f);
        shield.lineTo(ox + s * 0.89f, oy + s * 0.47f);
        shield.cubicTo(ox + s * 0.89f, oy + s * 0.72f,
                ox + s * 0.70f, oy + s * 0.88f,
                ox + s * 0.50f, oy + s * 0.96f);
        shield.cubicTo(ox + s * 0.30f, oy + s * 0.88f,
                ox + s * 0.11f, oy + s * 0.72f,
                ox + s * 0.11f, oy + s * 0.47f);
        shield.lineTo(ox + s * 0.11f, oy + s * 0.18f);
        shield.close();

        check.reset();
        check.moveTo(ox + s * 0.32f, oy + s * 0.50f);
        check.lineTo(ox + s * 0.44f, oy + s * 0.62f);
        check.lineTo(ox + s * 0.69f, oy + s * 0.36f);

        float r = s * 0.035f;
        dot.set(ox + s * 0.50f - r, oy + s * 0.70f - r,
                ox + s * 0.50f + r, oy + s * 0.70f + r);

        int top = on ? 0xFF6EE7B7 : 0xFF64748B;
        int bottom = on ? 0xFF047857 : 0xFF1E293B;
        fill.setShader(new LinearGradient(0, oy, 0, oy + s, top, bottom, Shader.TileMode.CLAMP));
        stroke.setStrokeWidth(Math.max(2f, s * 0.012f));
        stroke.setColor(on ? 0x8034D399 : 0x3364748B);
        mark.setStrokeWidth(s * 0.085f);
        mark.setColor(0xFFF8FAFC);
        halo.setStrokeWidth(Math.max(2f, s * 0.010f));
        halo.setColor(Ui.GREEN);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float s = Math.min(w, h) * 0.86f;
        float cx = w / 2f;
        float cy = h / 2f;

        if (on) {
            long t = SystemClock.uptimeMillis() - start;
            float phase = (float) ((t % 2600) / 2600.0);
            halo.setAlpha((int) (55 * (1 - phase)));
            canvas.drawCircle(cx, cy, s * (0.48f + 0.16f * phase), halo);
            postInvalidateOnAnimation();
        }

        canvas.drawPath(shield, fill);
        canvas.drawPath(shield, stroke);

        if (on) {
            canvas.drawPath(check, mark);
        } else {
            canvas.drawLine(cx, cy - s * 0.15f, cx, cy + s * 0.05f, mark);
            canvas.drawCircle(cx, cy + s * 0.16f, s * 0.035f, mark);
        }
    }
}
