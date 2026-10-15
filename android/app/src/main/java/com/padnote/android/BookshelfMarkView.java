package com.padnote.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Displays the generated transparent PadNote mark without its launcher background. */
final class BookshelfMarkView extends View {
    private static volatile Bitmap markBitmap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    BookshelfMarkView(Context context) {
        super(context);
        setContentDescription(context.getString(R.string.app_name));
        getMark(context);
    }

    private static Bitmap getMark(Context context) {
        Bitmap cached = markBitmap;
        if (cached != null) return cached;
        synchronized (BookshelfMarkView.class) {
            if (markBitmap == null) {
                markBitmap = BitmapFactory.decodeResource(
                        context.getResources(), R.drawable.padnote_mark);
            }
            return markBitmap;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap mark = getMark(getContext());
        float side = Math.min(getWidth(), getHeight());
        float left = (getWidth() - side) / 2f;
        float top = (getHeight() - side) / 2f;
        canvas.drawBitmap(mark, null, new RectF(left, top, left + side, top + side), paint);
    }
}
