package com.dino.holeshield;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A small Material-style switch used by the settings rows. The row itself owns
 * the click action, so the switch is purely visual and never steals D-pad focus.
 */
final class SwitchView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private boolean checked;
    private float progress;
    private ValueAnimator animator;

    SwitchView(Context context) {
        super(context);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setCheckedImmediate(boolean value) {
        checked = value;
        progress = value ? 1f : 0f;
        invalidate();
    }

    boolean isChecked() {
        return checked;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(Ui.dp(getContext(), 50), Ui.dp(getContext(), 32));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float trackH = Ui.dp(getContext(), 22);
        float pad = Ui.dp(getContext(), 3);
        trackRect.set(pad, (h - trackH) / 2f, w - pad, (h + trackH) / 2f);
        float radius = trackRect.height() / 2f;

        track.setStyle(Paint.Style.FILL);
        track.setColor(checked
                ? (Ui.dark ? Ui.PRIMARY_CONTAINER : Ui.PRIMARY)
                : Ui.SURFACE_CONTAINER_HIGHEST);
        canvas.drawRoundRect(trackRect, radius, radius, track);

        if (!checked) {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Ui.dp(getContext(), 1));
            stroke.setColor(Ui.OUTLINE_VARIANT);
            canvas.drawRoundRect(trackRect, radius, radius, stroke);
        }

        float thumbR = Ui.dp(getContext(), 8.5f);
        float travel = trackRect.width() - thumbR * 2f;
        float cx = trackRect.left + thumbR + travel * progress;
        float cy = trackRect.centerY();
        thumb.setStyle(Paint.Style.FILL);
        thumb.setColor(checked
                ? (Ui.dark ? Ui.TEXT : Ui.ON_PRIMARY)
                : (Ui.dark ? Ui.TEXT : Ui.OUTLINE));
        canvas.drawCircle(cx, cy, thumbR, thumb);
    }

    void animateTo(boolean value) {
        if (checked == value) return;
        checked = value;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(progress, value ? 1f : 0f);
        animator.setDuration(160L);
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }
}
