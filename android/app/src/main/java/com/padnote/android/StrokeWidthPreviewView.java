package com.padnote.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

final class StrokeWidthPreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF backgroundBounds = new RectF();
    private float sizeDp = 4f;
    private int color = Color.rgb(23, 33, 43);
    private boolean eraser;

    StrokeWidthPreviewView(Context context) {
        super(context);
        setContentDescription("工具粗细预览");
        setMinimumHeight(dp(68));
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setPreview(float sizeDp, int color, boolean eraser) {
        this.sizeDp = sizeDp;
        this.color = color;
        this.eraser = eraser;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        backgroundBounds.set(0, 0, getWidth(), getHeight());
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(248, 247, 243));
        canvas.drawRoundRect(backgroundBounds, dp(12), dp(12), paint);

        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        if (eraser) {
            float radius = Math.min(dp(sizeDp) / 2f, getHeight() / 2f - dp(8));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(35, 182, 74, 59));
            canvas.drawCircle(centerX, centerY, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.rgb(182, 74, 59));
            canvas.drawCircle(centerX, centerY, radius, paint);
            return;
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.min(dp(sizeDp), getHeight() - dp(16)));
        paint.setColor(color);
        canvas.drawLine(dp(28), centerY, getWidth() - dp(28), centerY, paint);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
