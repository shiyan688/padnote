package com.padnote.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

final class SizePreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float sizeDp = 4f;
    private float maxSizeDp = 24f;
    private int color = Color.rgb(23, 33, 43);
    private boolean eraser;

    SizePreviewView(Context context) {
        super(context);
        setContentDescription("当前粗细预览");
        setMinimumWidth(dp(38));
        setMinimumHeight(dp(38));
    }

    void setPreview(float sizeDp, float maxSizeDp, int color, boolean eraser) {
        this.sizeDp = sizeDp;
        this.maxSizeDp = maxSizeDp;
        this.color = color;
        this.eraser = eraser;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.max(0.08f, Math.min(1f, sizeDp / maxSizeDp));
        float radius = dp(3) + scale * dp(12);
        paint.setColor(eraser ? Color.rgb(182, 74, 59) : color);
        paint.setStyle(eraser ? Paint.Style.STROKE : Paint.Style.FILL);
        paint.setStrokeWidth(dp(2));
        canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, paint);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
