package com.padnote.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A small thumbnail of the paper a new note will use.
 *
 * <p>Draws its own miniature rather than embedding a real canvas: the picker only
 * needs to convey proportion and ruling, and a live canvas would drag in page
 * geometry, ink caching and WebView overlays for a decoration.
 *
 * <p>Ruling density is expressed as a fraction of the thumbnail height, matching
 * how {@code NoteCanvasView} derives it from page height, so the preview reads the
 * same as the page it stands for.
 */
final class PageStylePreviewView extends View {

    /** Lines across the page height; mirrors NoteCanvasView.rulingGap. */
    private static final int LINES_PER_PAGE = 26;

    private final Paint paperPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private PageStyle style = PageStyle.legacyDefault();
    /** Reused across draws; allocating here would add GC pressure per frame. */
    private final RectF pageBounds = new RectF();

    PageStylePreviewView(Context context) {
        super(context);
        paperPaint.setStyle(Paint.Style.FILL);
        paperPaint.setColor(Color.rgb(251, 250, 246));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1f));
        borderPaint.setColor(Color.rgb(196, 205, 214));
        rulingPaint.setStyle(Paint.Style.STROKE);
        rulingPaint.setStrokeWidth(dp(0.7f));
        rulingPaint.setColor(Color.rgb(214, 223, 231));
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(Color.rgb(200, 210, 219));
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(Color.argb(28, 0, 0, 0));
    }

    void setStyle(PageStyle replacement) {
        if (replacement == null) {
            return;
        }
        style = replacement;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        float inset = dp(6f);
        float[] size = style.resolveSize(getWidth() - inset * 2f,
                getHeight() - inset * 2f, dp(24f));
        float pageWidth = size[0];
        float pageHeight = size[1];
        float left = (getWidth() - pageWidth) / 2f;
        float top = (getHeight() - pageHeight) / 2f;
        RectF page = pageBounds;
        page.set(left, top, left + pageWidth, top + pageHeight);

        float shift = dp(2f);
        canvas.drawRect(page.left + shift, page.top + shift,
                page.right + shift, page.bottom + shift, shadowPaint);
        canvas.drawRect(page, paperPaint);

        float gap = pageHeight / LINES_PER_PAGE;
        switch (style.paper) {
            case BLANK:
                break;
            case GRID:
                for (float y = page.top + gap; y < page.bottom; y += gap) {
                    canvas.drawLine(page.left, y, page.right, y, rulingPaint);
                }
                for (float x = page.left + gap; x < page.right; x += gap) {
                    canvas.drawLine(x, page.top, x, page.bottom, rulingPaint);
                }
                break;
            case DOTTED:
                float radius = Math.max(dp(0.6f), gap * 0.07f);
                for (float y = page.top + gap; y < page.bottom; y += gap) {
                    for (float x = page.left + gap; x < page.right; x += gap) {
                        canvas.drawCircle(x, y, radius, dotPaint);
                    }
                }
                break;
            default:
                for (float y = page.top + gap; y < page.bottom; y += gap) {
                    canvas.drawLine(page.left, y, page.right, y, rulingPaint);
                }
                break;
        }
        canvas.drawRect(page, borderPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
