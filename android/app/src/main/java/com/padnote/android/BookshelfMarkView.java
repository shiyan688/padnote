package com.padnote.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Small colored shelf mark used in the empty state and brand surfaces. */
final class BookshelfMarkView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    BookshelfMarkView(Context context) {
        super(context);
        setContentDescription("PadNote 书架");
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float h = getHeight();
        float left = w * .12f;
        float right = w * .88f;
        float bottom = h * .78f;
        float top = h * .26f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(40, 94, 168));
        rect.set(left, top + h * .13f, right, bottom);
        canvas.drawRoundRect(rect, 10f * d, 10f * d, paint);
        paint.setColor(Color.rgb(96, 167, 150));
        rect.set(left + w * .11f, top, right - w * .22f, bottom - h * .08f);
        canvas.drawRoundRect(rect, 10f * d, 10f * d, paint);
        paint.setColor(Color.rgb(239, 174, 72));
        rect.set(left + w * .26f, top + h * .16f, right - w * .07f, bottom - h * .18f);
        canvas.drawRoundRect(rect, 10f * d, 10f * d, paint);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(Math.max(2f * d, w * .035f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(w * .30f, h * .55f, w * .68f, h * .55f, paint);
        canvas.drawLine(w * .30f, h * .66f, w * .58f, h * .66f, paint);
    }
}
