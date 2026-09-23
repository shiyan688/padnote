package com.padnote.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

final class ColorSwatchButton extends View {
    private static final int SELECTED_COLOR = Color.rgb(40, 94, 168);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int swatchColor;

    ColorSwatchButton(Context context) {
        this(context, Color.rgb(23, 33, 43), "使用墨蓝色");
    }

    ColorSwatchButton(Context context, int swatchColor, String description) {
        super(context);
        this.swatchColor = swatchColor;
        setContentDescription(description);
        setClickable(true);
        setFocusable(true);
        setMinimumWidth(dp(42));
        setMinimumHeight(dp(44));
    }

    int getSwatchColor() {
        return swatchColor;
    }

    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        if (isSelected()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(centerX, centerY, dp(14), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.5f));
            paint.setColor(SELECTED_COLOR);
            canvas.drawCircle(centerX, centerY, dp(14), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(swatchColor);
        canvas.drawCircle(centerX, centerY, dp(10), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.argb(70, 30, 40, 50));
        canvas.drawCircle(centerX, centerY, dp(10), paint);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
