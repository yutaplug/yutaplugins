package com.github.yutaplug.bettermessagelogger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

final class ColorPickerView extends View {
    interface OnColorChangedListener {
        void onColorChanged(int color);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = new float[3];
    private int alpha;
    private final int hueBarHeight;
    private OnColorChangedListener listener;

    ColorPickerView(Context context, int color) {
        super(context);
        hueBarHeight = dp(32);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        Color.colorToHSV(color, hsv);
        alpha = Color.alpha(color);
    }

    void setOnColorChangedListener(OnColorChangedListener listener) {
        this.listener = listener;
    }

    int getColor() {
        return Color.HSVToColor(alpha, hsv);
    }

    void setColor(int color) {
        alpha = Color.alpha(color);
        Color.colorToHSV(color, hsv);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        int pickerHeight = Math.max(1, height - hueBarHeight);
        int hueColor = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});

        Shader saturation = new LinearGradient(0, 0, width, 0,
                Color.WHITE, hueColor, Shader.TileMode.CLAMP);
        paint.setShader(saturation);
        canvas.drawRect(0, 0, width, pickerHeight, paint);
        paint.setShader(null);
        for (int y = 0; y < pickerHeight; y++) {
            int fade = (int) (255f * y / pickerHeight);
            paint.setColor(Color.argb(fade, 0, 0, 0));
            canvas.drawRect(0, y, width, y + 1, paint);
        }

        paint.setShader(new LinearGradient(0, 0, width, 0, new int[]{
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                Color.BLUE, Color.MAGENTA, Color.RED
        }, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, pickerHeight, width, height, paint);
        paint.setShader(null);

        drawIndicator(canvas, hsv[1] * width, (1f - hsv[2]) * pickerHeight);
        drawHueIndicator(canvas, hsv[0] / 360f * width, pickerHeight, height);
    }

    private void drawIndicator(Canvas canvas, float x, float y) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, dp(8), paint);
        paint.setColor(Color.BLACK);
        paint.setStrokeWidth(dp(1));
        canvas.drawCircle(x, y, dp(9), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawHueIndicator(Canvas canvas, float x, int top, int bottom) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.WHITE);
        canvas.drawLine(x, top, x, bottom, paint);
        paint.setColor(Color.BLACK);
        paint.setStrokeWidth(dp(1));
        canvas.drawLine(x, top, x, bottom, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_DOWN
                && event.getAction() != MotionEvent.ACTION_MOVE) return true;

        int pickerHeight = Math.max(1, getHeight() - hueBarHeight);
        float x = clamp(event.getX() / Math.max(1, getWidth()), 0f, 1f);
        if (event.getY() >= pickerHeight) {
            hsv[0] = x * 360f;
        } else {
            hsv[1] = x;
            hsv[2] = 1f - clamp(event.getY() / pickerHeight, 0f, 1f);
        }
        invalidate();
        if (listener != null) listener.onColorChanged(getColor());
        return true;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
