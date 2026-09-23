package com.padnote.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

final class ColorWheelView extends View {
    interface Listener {
        void onColorChanged(int color);
    }

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = new float[]{0f, 0f, 1f};
    private Bitmap wheelBitmap;
    private Listener listener;
    private float radius;
    private float centerX;
    private float centerY;

    ColorWheelView(Context context) {
        super(context);
        setContentDescription("HSV 颜色调色盘");
        setMinimumWidth(dp(220));
        setMinimumHeight(dp(220));
        markerPaint.setStyle(Paint.Style.STROKE);
        markerPaint.setStrokeWidth(dp(2));
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setColor(int color) {
        Color.colorToHSV(color, hsv);
        rebuildWheel();
        invalidate();
    }

    int getColor() {
        return Color.HSVToColor(hsv);
    }

    void setBrightness(float brightness) {
        hsv[2] = Math.max(0.08f, Math.min(1f, brightness));
        rebuildWheel();
        invalidate();
        notifyColorChanged();
    }

    float getBrightness() {
        return hsv[2];
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desired = dp(236);
        int width = resolveSize(desired, widthMeasureSpec);
        int height = resolveSize(desired, heightMeasureSpec);
        int size = Math.min(width, height);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        centerX = width / 2f;
        centerY = height / 2f;
        radius = Math.max(1, Math.min(width, height) / 2f - dp(8));
        rebuildWheel();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (wheelBitmap != null) {
            canvas.drawBitmap(wheelBitmap, 0, 0, bitmapPaint);
        }
        double angle = Math.toRadians(hsv[0]);
        float markerX = centerX + (float) Math.cos(angle) * radius * hsv[1];
        float markerY = centerY + (float) Math.sin(angle) * radius * hsv[1];
        markerPaint.setColor(Color.WHITE);
        markerPaint.setStrokeWidth(dp(4));
        canvas.drawCircle(markerX, markerY, dp(7), markerPaint);
        markerPaint.setColor(Color.rgb(35, 45, 55));
        markerPaint.setStrokeWidth(dp(1.5f));
        canvas.drawCircle(markerX, markerY, dp(7), markerPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN ||
                event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            updateColorFromPoint(event.getX(), event.getY());
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            updateColorFromPoint(event.getX(), event.getY());
            performClick();
            return true;
        }
        return event.getActionMasked() == MotionEvent.ACTION_CANCEL || super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void updateColorFromPoint(float x, float y) {
        float dx = x - centerX;
        float dy = y - centerY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance > radius) {
            float scale = radius / distance;
            dx *= scale;
            dy *= scale;
            distance = radius;
        }
        float hue = (float) Math.toDegrees(Math.atan2(dy, dx));
        if (hue < 0) {
            hue += 360f;
        }
        hsv[0] = hue;
        hsv[1] = Math.max(0f, Math.min(1f, distance / radius));
        invalidate();
        notifyColorChanged();
    }

    private void rebuildWheel() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (wheelBitmap != null) {
            wheelBitmap.recycle();
        }
        wheelBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        float edge = dp(1.5f);
        for (int y = 0; y < height; y++) {
            float dy = y - centerY;
            for (int x = 0; x < width; x++) {
                float dx = x - centerX;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                if (distance > radius + edge) {
                    continue;
                }
                float hue = (float) Math.toDegrees(Math.atan2(dy, dx));
                if (hue < 0) {
                    hue += 360f;
                }
                float saturation = Math.min(1f, distance / radius);
                int color = Color.HSVToColor(new float[]{hue, saturation, hsv[2]});
                if (distance > radius) {
                    int alpha = Math.max(0, Math.min(255,
                            Math.round(255f * (radius + edge - distance) / edge)));
                    color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
                }
                pixels[y * width + x] = color;
            }
        }
        wheelBitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    private void notifyColorChanged() {
        if (listener != null) {
            listener.onColorChanged(getColor());
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
