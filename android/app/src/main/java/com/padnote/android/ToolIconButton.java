package com.padnote.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;

/** PadNote's 24dp feature glyphs. The shared path data also drives the preview contact sheet. */
final class ToolIconButton extends View {
    enum Icon { PEN, HIGHLIGHTER, SHAPE, ERASER, LASSO, AI, PALETTE, UNDO, REDO, DUPLICATE,
        DELETE, CANCEL, CLEAR, SAVE, EXPORT, BOOKSHELF, PAGE_ADD, PAGE_MENU, IMAGE, TEXT_BOX, PEN_ONLY }

    private static final int INK_COLOR = Color.rgb(31, 41, 51);
    private static final int DISABLED_COLOR = Color.rgb(137, 147, 155);
    private static final int SELECTED_COLOR = PadNoteUi.ACCENT;
    private static final int SELECTED_BACKGROUND_COLOR = PadNoteUi.ACCENT_TINT;
    private static final int[] RAINBOW_COLORS = {0xFFE84855, 0xFFC04BB9, 0xFF4D66D8,
            0xFF37AED2, 0xFF40B070, 0xFFEBBE41, 0xFFE84855};

    private final Icon icon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint palettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path[] paths;
    private int accentColor = SELECTED_COLOR;

    ToolIconButton(Context context) { this(context, Icon.PEN, context.getString(R.string.core_pen_settings)); }

    ToolIconButton(Context context, Icon icon, String description) {
        super(context);
        this.icon = icon;
        this.paths = PadNoteFeatureIconPaths.forIcon(icon);
        setContentDescription(description);
        setClickable(true);
        setFocusable(true);
        setMinimumWidth(dp(48));
        setMinimumHeight(dp(48));
        backgroundPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(0.8f));
        borderPaint.setColor(Color.rgb(236, 233, 226));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(11));
        setForeground(new RippleDrawable(ColorStateList.valueOf(Color.argb(34, 53, 95, 210)), null, mask));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void setAccentColor(int color) { accentColor = color; invalidate(); }
    @Override public void setEnabled(boolean enabled) { super.setEnabled(enabled); invalidate(); }
    @Override public void setSelected(boolean selected) { super.setSelected(selected); invalidate(); }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (w > 0 && h > 0) palettePaint.setShader(new SweepGradient(w / 2f, h / 2f, RAINBOW_COLORS, null));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
        backgroundPaint.setColor(isSelected() ? SELECTED_BACKGROUND_COLOR : Color.WHITE);
        rect.set(dp(2), dp(2), w - dp(2), h - dp(2));
        canvas.drawRoundRect(rect, dp(11), dp(11), backgroundPaint);
        borderPaint.setColor(isSelected() ? SELECTED_COLOR : Color.rgb(236, 233, 226));
        rect.inset(dp(0.5f), dp(0.5f));
        canvas.drawRoundRect(rect, dp(11), dp(11), borderPaint);

        int foreground = !isEnabled() ? DISABLED_COLOR : (isSelected() ? SELECTED_COLOR : INK_COLOR);
        paint.setColor(foreground);
        paint.setStyle(Paint.Style.STROKE);
        // 24 source units occupy 24dp; stroke is 1.8dp at any density.
        float unit = Math.min(w, h) / 48f;
        paint.setStrokeWidth(1.8f);
        paint.setPathEffect(null);
        if (icon == Icon.PALETTE) { drawPalette(canvas, cx, cy, unit); return; }
        switch (icon) {
            case SHAPE:
            case PAGE_MENU:
            case IMAGE:
                paint.setColor(isEnabled() ? accentColor : DISABLED_COLOR);
                break;
            case PAGE_ADD:
                paint.setColor(isEnabled() ? SELECTED_COLOR : DISABLED_COLOR);
                break;
            default:
                break;
        }
        if (icon == Icon.LASSO) paint.setPathEffect(new DashPathEffect(new float[]{2.4f, 2f}, 0));
        canvas.save();
        canvas.translate(cx - 12f * unit, cy - 12f * unit);
        canvas.scale(unit, unit);
        for (int i = 0; i < paths.length; i++) {
            if (!isEnabled()) {
                paint.setColor(DISABLED_COLOR);
            } else if ((icon == Icon.PEN && i == 2) || (icon == Icon.HIGHLIGHTER && i == 2)) {
                paint.setColor(accentColor);
            } else if (icon == Icon.PAGE_ADD) {
                paint.setColor(SELECTED_COLOR);
            } else if (icon == Icon.SHAPE || icon == Icon.PAGE_MENU || icon == Icon.IMAGE) {
                paint.setColor(accentColor);
            } else {
                paint.setColor(foreground);
            }
            if (icon == Icon.LASSO && i > 0) paint.setPathEffect(null);
            canvas.drawPath(paths[i], paint);
        }
        canvas.restore();
        paint.setPathEffect(null);
    }

    private void drawPalette(Canvas canvas, float cx, float cy, float unit) {
        palettePaint.setStyle(Paint.Style.STROKE);
        palettePaint.setStrokeWidth(5 * unit);
        canvas.drawCircle(cx, cy, 9 * unit, palettePaint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(isEnabled() ? accentColor : DISABLED_COLOR);
        canvas.drawCircle(cx, cy, 3.8f * unit, paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
