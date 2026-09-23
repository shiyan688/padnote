package com.padnote.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.view.View;

final class ToolIconButton extends View {
    enum Icon {
        PEN,
        HIGHLIGHTER,
        SHAPE,
        ERASER,
        LASSO,
        AI,
        PALETTE,
        UNDO,
        REDO,
        DUPLICATE,
        DELETE,
        CANCEL,
        CLEAR,
        SAVE,
        EXPORT,
        BOOKSHELF,
        PAGE_ADD,
        PAGE_MENU,
        IMAGE,
        TEXT_BOX,
        PEN_ONLY
    }

    private static final int INK_COLOR = Color.rgb(34, 45, 56);
    private static final int DISABLED_COLOR = Color.rgb(164, 172, 179);
    private static final int SELECTED_COLOR = Color.rgb(40, 94, 168);
    private static final int SELECTED_BACKGROUND_COLOR = Color.rgb(218, 230, 247);
    private static final int[] RAINBOW_COLORS = new int[]{
            Color.rgb(232, 72, 85),
            Color.rgb(192, 75, 185),
            Color.rgb(77, 102, 216),
            Color.rgb(55, 174, 210),
            Color.rgb(64, 176, 112),
            Color.rgb(235, 190, 65),
            Color.rgb(232, 72, 85)
    };

    private final Icon icon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint palettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF reusableRect = new RectF();
    private int accentColor = SELECTED_COLOR;

    ToolIconButton(Context context) {
        this(context, Icon.PEN, "画笔");
    }

    ToolIconButton(Context context, Icon icon, String description) {
        super(context);
        this.icon = icon;
        setContentDescription(description);
        setClickable(true);
        setFocusable(true);
        setMinimumWidth(dp(44));
        setMinimumHeight(dp(44));
        backgroundPaint.setColor(Color.WHITE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void setAccentColor(int color) {
        accentColor = color;
        invalidate();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        invalidate();
    }

    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width > 0 && height > 0) {
            palettePaint.setShader(new SweepGradient(width / 2f, height / 2f, RAINBOW_COLORS, null));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float centerX = width / 2f;
        float centerY = height / 2f;
        float unit = Math.min(width, height) / 44f;

        backgroundPaint.setColor(isSelected() ? SELECTED_BACKGROUND_COLOR : Color.WHITE);
        reusableRect.set(dp(2), dp(2), width - dp(2), height - dp(2));
        canvas.drawRoundRect(reusableRect, dp(11), dp(11), backgroundPaint);

        int foreground = !isEnabled() ? DISABLED_COLOR : (isSelected() ? SELECTED_COLOR : INK_COLOR);
        paint.setColor(foreground);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.1f * unit);
        paint.setPathEffect(null);
        path.reset();

        switch (icon) {
            case PEN:
                drawPen(canvas, centerX, centerY, unit);
                break;
            case HIGHLIGHTER:
                drawHighlighter(canvas, centerX, centerY, unit);
                break;
            case SHAPE:
                drawShape(canvas, centerX, centerY, unit);
                break;
            case ERASER:
                drawEraser(canvas, centerX, centerY, unit);
                break;
            case LASSO:
                drawLasso(canvas, centerX, centerY, unit);
                break;
            case AI:
                drawAi(canvas, centerX, centerY, unit);
                break;
            case PALETTE:
                drawPalette(canvas, centerX, centerY, unit);
                break;
            case UNDO:
                drawUndo(canvas, centerX, centerY, unit, false);
                break;
            case REDO:
                drawUndo(canvas, centerX, centerY, unit, true);
                break;
            case DUPLICATE:
                canvas.drawRoundRect(centerX - 10 * unit, centerY - 9 * unit,
                        centerX + 5 * unit, centerY + 7 * unit, 2 * unit, 2 * unit, paint);
                canvas.drawRoundRect(centerX - 4 * unit, centerY - 4 * unit,
                        centerX + 11 * unit, centerY + 12 * unit, 2 * unit, 2 * unit, paint);
                break;
            case DELETE:
                canvas.drawRoundRect(centerX - 7 * unit, centerY - 7 * unit,
                        centerX + 7 * unit, centerY + 11 * unit, 2 * unit, 2 * unit, paint);
                canvas.drawLine(centerX - 10 * unit, centerY - 11 * unit,
                        centerX + 10 * unit, centerY - 11 * unit, paint);
                canvas.drawLine(centerX - 4 * unit, centerY - 14 * unit,
                        centerX + 4 * unit, centerY - 14 * unit, paint);
                canvas.drawLine(centerX - 3 * unit, centerY - 3 * unit,
                        centerX - 3 * unit, centerY + 6 * unit, paint);
                canvas.drawLine(centerX + 3 * unit, centerY - 3 * unit,
                        centerX + 3 * unit, centerY + 6 * unit, paint);
                break;
            case CANCEL:
                canvas.drawLine(centerX - 9 * unit, centerY - 9 * unit,
                        centerX + 9 * unit, centerY + 9 * unit, paint);
                canvas.drawLine(centerX + 9 * unit, centerY - 9 * unit,
                        centerX - 9 * unit, centerY + 9 * unit, paint);
                break;
            case CLEAR:
                drawClear(canvas, centerX, centerY, unit);
                break;
            case SAVE:
                drawSave(canvas, centerX, centerY, unit);
                break;
            case EXPORT:
                drawExport(canvas, centerX, centerY, unit);
                break;
            case BOOKSHELF:
                drawBookshelf(canvas, centerX, centerY, unit);
                break;
            case PAGE_ADD:
                drawPageAdd(canvas, centerX, centerY, unit);
                break;
            case PAGE_MENU:
                drawPageMenu(canvas, centerX, centerY, unit);
                break;
            case IMAGE:
                drawImage(canvas, centerX, centerY, unit);
                break;
            case TEXT_BOX:
                drawTextBox(canvas, centerX, centerY, unit);
                break;
            case PEN_ONLY:
                drawPenOnly(canvas, centerX, centerY, unit);
                break;
        }
    }

    private void drawPageMenu(Canvas canvas, float x, float y, float unit) {
        paint.setColor(isEnabled() ? accentColor : DISABLED_COLOR);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f * unit);
        canvas.drawRect(x - 10 * unit, y - 11 * unit, x + 7 * unit, y + 10 * unit, paint);
        canvas.drawLine(x - 6 * unit, y - 6 * unit, x + 3 * unit, y - 6 * unit, paint);
        canvas.drawLine(x - 6 * unit, y - 1 * unit, x + 3 * unit, y - 1 * unit, paint);
        canvas.drawLine(x - 6 * unit, y + 4 * unit, x + 3 * unit, y + 4 * unit, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(x + 9 * unit, y + 8 * unit, 3 * unit, paint);
    }

    private void drawImage(Canvas canvas, float x, float y, float unit) {
        paint.setColor(isEnabled() ? accentColor : DISABLED_COLOR);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f * unit);
        canvas.drawRect(x - 11 * unit, y - 9 * unit, x + 11 * unit, y + 9 * unit, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(x - 5 * unit, y - 4 * unit, 2 * unit, paint);
        path.reset();
        path.moveTo(x - 9 * unit, y + 6 * unit);
        path.lineTo(x - 2 * unit, y - 1 * unit);
        path.lineTo(x + 2 * unit, y + 3 * unit);
        path.lineTo(x + 6 * unit, y - 2 * unit);
        path.lineTo(x + 10 * unit, y + 6 * unit);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawHighlighter(Canvas canvas, float x, float y, float unit) {
        paint.setColor(isEnabled() ? accentColor : DISABLED_COLOR);
        paint.setStrokeWidth(5 * unit);
        paint.setStrokeCap(Paint.Cap.SQUARE);
        canvas.drawLine(x - 9 * unit, y + 8 * unit, x + 8 * unit, y - 9 * unit, paint);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    private void drawShape(Canvas canvas, float x, float y, float unit) {
        paint.setColor(isEnabled() ? accentColor : DISABLED_COLOR);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * unit);
        canvas.drawRect(x - 10 * unit, y - 8 * unit, x + 10 * unit, y + 8 * unit, paint);
        canvas.drawLine(x - 8 * unit, y + 5 * unit, x + 8 * unit, y - 5 * unit, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawPen(Canvas canvas, float x, float y, float unit) {
        paint.setColor(isEnabled() ? accentColor : DISABLED_COLOR);
        canvas.save();
        canvas.rotate(-42, x, y);
        canvas.drawRoundRect(x - 4 * unit, y - 13 * unit, x + 4 * unit, y + 8 * unit,
                2 * unit, 2 * unit, paint);
        path.moveTo(x - 4 * unit, y + 8 * unit);
        path.lineTo(x, y + 14 * unit);
        path.lineTo(x + 4 * unit, y + 8 * unit);
        canvas.drawPath(path, paint);
        canvas.drawLine(x - 4 * unit, y - 7 * unit, x + 4 * unit, y - 7 * unit, paint);
        canvas.restore();
    }

    private void drawEraser(Canvas canvas, float x, float y, float unit) {
        canvas.save();
        canvas.rotate(-38, x, y);
        canvas.drawRoundRect(x - 8 * unit, y - 12 * unit, x + 8 * unit, y + 10 * unit,
                3 * unit, 3 * unit, paint);
        canvas.drawLine(x - 8 * unit, y + 2 * unit, x + 8 * unit, y + 2 * unit, paint);
        canvas.restore();
        canvas.drawLine(x - 11 * unit, y + 14 * unit, x + 12 * unit, y + 14 * unit, paint);
    }

    private void drawLasso(Canvas canvas, float x, float y, float unit) {
        paint.setPathEffect(new DashPathEffect(new float[]{4 * unit, 3 * unit}, 0));
        reusableRect.set(x - 13 * unit, y - 10 * unit, x + 13 * unit, y + 9 * unit);
        canvas.drawOval(reusableRect, paint);
        paint.setPathEffect(null);
        path.moveTo(x + 8 * unit, y + 7 * unit);
        path.cubicTo(x + 13 * unit, y + 12 * unit, x + 7 * unit, y + 16 * unit,
                x + 2 * unit, y + 12 * unit);
        canvas.drawPath(path, paint);
    }

    private void drawPalette(Canvas canvas, float x, float y, float unit) {
        palettePaint.setStyle(Paint.Style.STROKE);
        palettePaint.setStrokeWidth(6 * unit);
        canvas.drawCircle(x, y, 10 * unit, palettePaint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accentColor);
        canvas.drawCircle(x, y, 5.2f * unit, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f * unit);
        paint.setColor(Color.rgb(245, 242, 234));
        canvas.drawCircle(x, y, 5.2f * unit, paint);
    }

    private void drawAi(Canvas canvas, float x, float y, float unit) {
        path.moveTo(x, y - 14 * unit);
        path.lineTo(x + 3 * unit, y - 4 * unit);
        path.lineTo(x + 12 * unit, y);
        path.lineTo(x + 3 * unit, y + 4 * unit);
        path.lineTo(x, y + 14 * unit);
        path.lineTo(x - 3 * unit, y + 4 * unit);
        path.lineTo(x - 12 * unit, y);
        path.lineTo(x - 3 * unit, y - 4 * unit);
        path.close();
        canvas.drawPath(path, paint);
        canvas.drawLine(x + 10 * unit, y - 11 * unit, x + 10 * unit, y - 5 * unit, paint);
        canvas.drawLine(x + 7 * unit, y - 8 * unit, x + 13 * unit, y - 8 * unit, paint);
    }

    private void drawTextBox(Canvas canvas, float x, float y, float unit) {
        reusableRect.set(x - 14 * unit, y - 11 * unit, x + 14 * unit, y + 11 * unit);
        canvas.drawRoundRect(reusableRect, 3 * unit, 3 * unit, paint);
        paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(20 * unit);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawText("T", x, y + 7 * unit, paint);
        paint.setTypeface(null);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.1f * unit);
    }

    private void drawUndo(Canvas canvas, float x, float y, float unit, boolean mirror) {
        canvas.save();
        if (mirror) {
            canvas.scale(-1, 1, x, y);
        }
        path.moveTo(x - 12 * unit, y - 2 * unit);
        path.lineTo(x - 5 * unit, y - 9 * unit);
        path.moveTo(x - 12 * unit, y - 2 * unit);
        path.lineTo(x - 5 * unit, y + 5 * unit);
        path.moveTo(x - 11 * unit, y - 2 * unit);
        path.cubicTo(x - 1 * unit, y - 10 * unit, x + 12 * unit, y - 5 * unit,
                x + 10 * unit, y + 8 * unit);
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    private void drawClear(Canvas canvas, float x, float y, float unit) {
        canvas.save();
        canvas.rotate(-35, x, y);
        canvas.drawLine(x, y - 14 * unit, x, y + 4 * unit, paint);
        path.moveTo(x - 7 * unit, y + 3 * unit);
        path.lineTo(x + 7 * unit, y + 3 * unit);
        path.lineTo(x + 10 * unit, y + 13 * unit);
        path.lineTo(x - 10 * unit, y + 13 * unit);
        path.close();
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    private void drawSave(Canvas canvas, float x, float y, float unit) {
        canvas.drawRoundRect(x - 12 * unit, y - 13 * unit, x + 12 * unit, y + 13 * unit,
                2 * unit, 2 * unit, paint);
        canvas.drawRect(x - 7 * unit, y - 13 * unit, x + 6 * unit, y - 4 * unit, paint);
        canvas.drawRect(x - 7 * unit, y + 3 * unit, x + 7 * unit, y + 13 * unit, paint);
    }

    private void drawExport(Canvas canvas, float x, float y, float unit) {
        canvas.drawRoundRect(x - 12 * unit, y - 1 * unit, x + 12 * unit, y + 13 * unit,
                2 * unit, 2 * unit, paint);
        canvas.drawLine(x, y + 6 * unit, x, y - 13 * unit, paint);
        canvas.drawLine(x, y - 13 * unit, x - 6 * unit, y - 7 * unit, paint);
        canvas.drawLine(x, y - 13 * unit, x + 6 * unit, y - 7 * unit, paint);
    }

    private void drawBookshelf(Canvas canvas, float x, float y, float unit) {
        canvas.drawRect(x - 12 * unit, y - 11 * unit, x - 5 * unit, y + 10 * unit, paint);
        canvas.drawRect(x - 3 * unit, y - 8 * unit, x + 4 * unit, y + 10 * unit, paint);
        canvas.save();
        canvas.rotate(-12, x + 8 * unit, y);
        canvas.drawRect(x + 5 * unit, y - 10 * unit, x + 12 * unit, y + 10 * unit, paint);
        canvas.restore();
        canvas.drawLine(x - 14 * unit, y + 13 * unit, x + 14 * unit, y + 13 * unit, paint);
    }

    private void drawPageAdd(Canvas canvas, float x, float y, float unit) {
        canvas.drawRoundRect(x - 11 * unit, y - 13 * unit,
                x + 7 * unit, y + 11 * unit, 2 * unit, 2 * unit, paint);
        canvas.drawLine(x - 6 * unit, y - 8 * unit,
                x + 2 * unit, y - 8 * unit, paint);
        canvas.drawLine(x - 6 * unit, y - 3 * unit,
                x + 1 * unit, y - 3 * unit, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(253, 252, 248));
        canvas.drawCircle(x + 9 * unit, y + 9 * unit, 8 * unit, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(isEnabled() ? SELECTED_COLOR : DISABLED_COLOR);
        canvas.drawCircle(x + 9 * unit, y + 9 * unit, 8 * unit, paint);
        canvas.drawLine(x + 5 * unit, y + 9 * unit,
                x + 13 * unit, y + 9 * unit, paint);
        canvas.drawLine(x + 9 * unit, y + 5 * unit,
                x + 9 * unit, y + 13 * unit, paint);
    }

    private void drawPenOnly(Canvas canvas, float x, float y, float unit) {
        path.moveTo(x - 10 * unit, y + 9 * unit);
        path.lineTo(x + 7 * unit, y - 8 * unit);
        path.lineTo(x + 11 * unit, y - 4 * unit);
        path.lineTo(x - 6 * unit, y + 13 * unit);
        path.close();
        canvas.drawPath(path, paint);
        canvas.drawLine(x - 13 * unit, y - 13 * unit, x + 13 * unit, y + 13 * unit, paint);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
