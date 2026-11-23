package com.dino.holeshield;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import java.util.function.Consumer;

final class AccentPicker {
    static void show(Context context, AccentPalette initial, Consumer<String> save) {
        LinearLayout body = Ui.column(context);
        int pad = Ui.dp(context, 20);
        body.setPadding(pad, pad / 2, pad, pad / 2);
        LinearLayout top = Ui.row(context);
        View swatch = new View(context);
        int size = Ui.dp(context, 32);
        top.addView(swatch, Ui.lp(size, size));
        EditText hex = new EditText(context);
        hex.setSingleLine(true);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setTextColor(Ui.TEXT);
        hex.setContentDescription("Hex color");
        top.addView(hex, Ui.margins(Ui.lp(0, -2, 1), pad / 2, 0, 0, 0));
        body.addView(top);
        Field field = new Field(context);
        body.addView(field, Ui.margins(Ui.lp(-1, Ui.dp(context, 134)), 0, pad / 2, 0, 0));
        SeekBar brightness = new SeekBar(context);
        brightness.setMax(255);
        brightness.setContentDescription("Brightness");
        body.addView(brightness, Ui.lp(-1, -2));
        if (Device.isTv(context)) {
            body.addView(Ui.text(context, "Select the color field to adjust with arrows. Select again to exit.",
                    12, Ui.DIM, false));
        }

        AlertDialog dialog = Ui.builder(context).setTitle("Accent color").setView(body)
                .setNegativeButton("Cancel", null).setNeutralButton("Reset", (d, w) -> save.accept("default"))
                .setPositiveButton("Apply", (d, w) -> save.accept(AccentPalette.fromId(hex.getText().toString()).id))
                .create();
        boolean[] syncing = {false};
        Runnable refresh = () -> {
            int color = Color.HSVToColor(field.hsv);
            swatch.setBackground(Ui.rounded(context, color, 16, 1, Ui.OUTLINE));
            int bright = Color.HSVToColor(new float[]{field.hsv[0], field.hsv[1], 1});
            GradientDrawable track = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{Color.BLACK, bright});
            track.setCornerRadius(Ui.dp(context, 12));
            brightness.setProgressDrawable(track);
            brightness.setThumbTintList(ColorStateList.valueOf(Ui.TEXT));
            field.invalidate();
        };
        field.changed = () -> {
            syncing[0] = true;
            hex.setText(AccentPalette.hex(Color.HSVToColor(field.hsv)));
            brightness.setProgress(Math.round(field.hsv[2] * 255));
            syncing[0] = false;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            refresh.run();
        };
        hex.setText(AccentPalette.hex(initial.chosen(Ui.dark)));
        Color.colorToHSV(0xFF000000 | initial.chosen(Ui.dark), field.hsv);
        brightness.setProgress(Math.round(field.hsv[2] * 255));
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (syncing[0]) return;
                AccentPalette palette = AccentPalette.fromId(s.toString());
                boolean valid = s.toString().matches("#?[0-9a-fA-F]{6}");
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(valid);
                if (!valid) return;
                Color.colorToHSV(0xFF000000 | palette.chosen(Ui.dark), field.hsv);
                brightness.setProgress(Math.round(field.hsv[2] * 255));
                refresh.run();
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                field.hsv[2] = progress / 255f;
                field.changed.run();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        dialog.show();
        Ui.tintDialog(dialog);
        brightness.setNextFocusUpId(field.getId());
        field.requestFocus();
        refresh.run();
    }

    private static final class Field extends View {
        final float[] hsv = new float[3];
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Runnable changed;
        boolean adjusting;

        Field(Context context) {
            super(context);
            setId(View.generateViewId());
            setFocusable(true);
            setContentDescription("Hue and saturation. Press Select to adjust with arrow keys.");
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            paint.setShader(new LinearGradient(0, 0, w, 0, new int[]{
                    Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
            }, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(new LinearGradient(0, 0, 0, h, 0x00FFFFFF, Color.WHITE, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            float r = Ui.dp(getContext(), 9);
            float x = r + (w - 2 * r) * hsv[0] / 360;
            float y = r + (h - 2 * r) * (1 - hsv[1]);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, r, paint);
            paint.setColor(Color.HSVToColor(hsv));
            canvas.drawCircle(x, y, r - 2, paint);
            if (hasFocus()) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(adjusting ? 5 : 2);
                paint.setColor(Ui.PRIMARY);
                canvas.drawRect(2, 2, w - 2, h - 2, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                requestFocus();
                hsv[0] = Math.min(359.99f, 360 * clamp(event.getX() / getWidth()));
                hsv[1] = 1 - clamp(event.getY() / getHeight());
                changed.run();
                return true;
            }
            if (action == MotionEvent.ACTION_UP) { performClick(); return true; }
            return action == MotionEvent.ACTION_CANCEL;
        }

        @Override public boolean performClick() { super.performClick(); return true; }

        @Override public boolean onKeyDown(int key, KeyEvent event) {
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (event.getRepeatCount() == 0) { adjusting = !adjusting; invalidate(); }
                return true;
            }
            if (adjusting) {
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) hsv[0] = (hsv[0] + 355) % 360;
                else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) hsv[0] = (hsv[0] + 5) % 360;
                else if (key == KeyEvent.KEYCODE_DPAD_UP) hsv[1] = clamp(hsv[1] + .05f);
                else if (key == KeyEvent.KEYCODE_DPAD_DOWN) hsv[1] = clamp(hsv[1] - .05f);
                else return super.onKeyDown(key, event);
                changed.run();
                return true;
            }
            return super.onKeyDown(key, event);
        }

        private static float clamp(float f) { return Math.max(0, Math.min(1, f)); }
    }
}
