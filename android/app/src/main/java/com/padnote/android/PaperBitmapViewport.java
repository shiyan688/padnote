package com.padnote.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** Read-only viewport for one caller-owned paper snapshot bitmap. */
final class PaperBitmapViewport extends View {
    interface Listener { void onPaperTap(int pageIdentity, float xInPage, float yInPage); }
    private enum FitMode { PAGE, WIDTH, MANUAL }

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pageRect = new RectF();
    private final float touchSlop;
    private Bitmap bitmap; // borrowed; lifecycle and recycling remain with the caller
    private float paperWidth, paperHeight, scale, left, top, fitPageScale, fitWidthScale;
    private float zoomFactor = 1f, downX, downY, lastX, lastY, pinchSpan, pinchFocusX, pinchFocusY;
    private float pendingTapX = Float.NaN, pendingTapY = Float.NaN;
    private float markerX = Float.NaN, markerY = Float.NaN;
    private int pageIdentity = Integer.MIN_VALUE;
    private FitMode fitMode = FitMode.PAGE;
    private boolean moved, multiTouch, hasPaper;
    private Listener listener;

    PaperBitmapViewport(Context context) { this(context, null); }
    PaperBitmapViewport(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        markerPaint.setColor(0xffdc2828);
        setClickable(true);
        setFocusable(true);
    }

    void setListener(Listener value) { listener = value; }

    /** Replaces the displayed snapshot. This view never recycles either bitmap. */
    void setPaper(int identity, float width, float height, Bitmap value) {
        boolean validGeometry=Float.isFinite(width)&&Float.isFinite(height)&&width>0f&&height>0f;
        boolean changed = !hasPaper || identity != pageIdentity
                || Float.compare(width, paperWidth) != 0 || Float.compare(height, paperHeight) != 0;
        bitmap = value;
        hasPaper = validGeometry && value != null && !value.isRecycled();
        pageIdentity = identity; paperWidth = validGeometry?width:0f; paperHeight = validGeometry?height:0f;
        if (changed) {
            markerX = markerY = Float.NaN;
            fitMode = FitMode.PAGE; zoomFactor = 1f;
            applyFit();
        } else if (getWidth() > 0 && getHeight() > 0) {
            updatePageRect();
        }
        setEnabled(hasPaper&&isValidScale());
        invalidate();
    }

    void clearPaper() {
        bitmap = null; hasPaper = false; markerX = markerY = Float.NaN;
        setEnabled(false); invalidate();
    }

    void setMarker(float x, float y) { markerX = x; markerY = y; invalidate(); }
    void clearMarker() { markerX = markerY = Float.NaN; invalidate(); }
    boolean hasPaper() { return hasPaper; }
    boolean isReadyForInteraction() { return hasPaper&&isValidScale(); }
    int pageIdentity() { return pageIdentity; }
    float paperWidth() { return paperWidth; }
    float paperHeight() { return paperHeight; }
    float currentScale() { return scale; }
    float fitPageScale() { return fitPageScale; }
    float paperToViewX(float x) { return left + x * scale; }
    float paperToViewY(float y) { return top + y * scale; }
    float viewToPaperX(float x) { return isValidScale() ? (x - left) / scale : Float.NaN; }
    float viewToPaperY(float y) { return isValidScale() ? (y - top) / scale : Float.NaN; }

    void fitPage() { if(!isEnabled()||!hasPaper)return;fitMode = FitMode.PAGE; zoomFactor = 1f; applyFit(); }
    void fitWidth() { if(!isEnabled()||!hasPaper)return;fitMode = FitMode.WIDTH; zoomFactor = 1f; applyFit(); }
    void zoomIn() { if(isEnabled())zoomAround(1.25f, getWidth() / 2f, getHeight() / 2f); }
    void zoomOut() { if(isEnabled())zoomAround(1f / 1.25f, getWidth() / 2f, getHeight() / 2f); }

    private void applyFit() {
        if (!hasPaper || getWidth() <= 0 || getHeight() <= 0) return;
        fitPageScale = Math.min(getWidth() / paperWidth, getHeight() / paperHeight);
        fitWidthScale = getWidth() / paperWidth;
        float maximumScale = maximumScale();
        if(!Float.isFinite(fitPageScale)||!Float.isFinite(fitWidthScale)||
                !Float.isFinite(maximumScale)||fitPageScale<=0f||fitWidthScale<=0f){
            scale=0f;setEnabled(false);return;
        }
        scale = fitMode == FitMode.WIDTH ? fitWidthScale : fitPageScale;
        zoomFactor = scale / fitPageScale;
        left = (getWidth() - paperWidth * scale) / 2f;
        top = (getHeight() - paperHeight * scale) / 2f;
        clampPan();setEnabled(hasPaper&&isValidScale());invalidate();
    }

    private void zoomAround(float factor, float focusX, float focusY) {
        if (!hasPaper || !isValidScale()) return;
        float anchorX = viewToPaperX(focusX), anchorY = viewToPaperY(focusY);
        float next = Math.max(fitPageScale, Math.min(maximumScale(), scale * factor));
        if (!Float.isFinite(next)||Math.abs(next-scale)<=Math.ulp(scale)*2f) return;
        scale = next; zoomFactor = scale / fitPageScale; fitMode = FitMode.MANUAL;
        left = focusX - anchorX * scale; top = focusY - anchorY * scale;
        clampPan();setEnabled(hasPaper&&isValidScale());invalidate();
    }

    private void updatePageRect() {
        pageRect.set(left, top, left + paperWidth * scale, top + paperHeight * scale);
    }

    private void clampPan() {
        if (!hasPaper || !isValidScale() || getWidth() <= 0 || getHeight() <= 0) return;
        float contentWidth=paperWidth*scale,contentHeight=paperHeight*scale;
        if(contentWidth<=getWidth())left=(getWidth()-contentWidth)/2f;
        else left=Math.max(getWidth()-contentWidth,Math.min(0f,left));
        if(contentHeight<=getHeight())top=(getHeight()-contentHeight)/2f;
        else top=Math.max(getHeight()-contentHeight,Math.min(0f,top));
        updatePageRect();
    }

    private boolean isValidScale(){return Float.isFinite(scale)&&scale>0f;}

    private float maximumScale(){return Math.max(fitPageScale*4f,fitWidthScale);}

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (!hasPaper || oldw <= 0 || oldh <= 0 || oldw == w && oldh == h) { applyFit(); return; }
        float centerPageX = viewToPaperX(oldw / 2f), centerPageY = viewToPaperY(oldh / 2f);
        fitPageScale = Math.min(w / paperWidth, h / paperHeight);
        fitWidthScale = w / paperWidth;
        float maximumScale=maximumScale();
        if(!Float.isFinite(fitPageScale)||!Float.isFinite(fitWidthScale)||
                !Float.isFinite(maximumScale)||fitPageScale<=0f||fitWidthScale<=0f){
            scale=0f;setEnabled(false);invalidate();return;
        }
        if (fitMode == FitMode.PAGE) scale = fitPageScale;
        else if (fitMode == FitMode.WIDTH) scale = fitWidthScale;
        else scale = Math.max(fitPageScale, Math.min(maximumScale, fitPageScale * zoomFactor));
        zoomFactor = scale / fitPageScale;
        left = w / 2f - centerPageX * scale; top = h / 2f - centerPageY * scale;
        clampPan();setEnabled(hasPaper&&isValidScale());invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xffe7e8e8);
        if (!hasPaper || bitmap == null || bitmap.isRecycled()) return;
        updatePageRect();
        canvas.save(); canvas.clipRect(pageRect);
        canvas.drawBitmap(bitmap, null, pageRect, bitmapPaint);
        if (Float.isFinite(markerX) && Float.isFinite(markerY)) {
            float x = paperToViewX(markerX), y = paperToViewY(markerY);
            float radius = 8f * getResources().getDisplayMetrics().density;
            canvas.drawCircle(x, y, radius, markerPaint);
            markerPaint.setStyle(Paint.Style.STROKE);
            markerPaint.setStrokeWidth(Math.max(1f, radius * 0.22f));
            canvas.drawCircle(x, y, radius + markerPaint.getStrokeWidth(), markerPaint);
            markerPaint.setStyle(Paint.Style.FILL);
        }
        canvas.restore();
    }

    @Override public boolean performClick() {
        if (!isEnabled()||!hasPaper||!isValidScale()) return false;
        super.performClick();
        if (hasPaper && listener != null) {
            float x = Float.isFinite(pendingTapX) ? pendingTapX : paperWidth / 2f;
            float y = Float.isFinite(pendingTapY) ? pendingTapY : paperHeight / 2f;
            pendingTapX = pendingTapY = Float.NaN;
            if (insidePaper(x, y)) listener.onPaperTap(pageIdentity, x, y);
        }
        return true;
    }

    private boolean insidePaper(float x, float y) {
        return x >= 0 && y >= 0 && x <= paperWidth && y <= paperHeight;
    }

    private float span(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0;
        float dx=e.getX(0)-e.getX(1), dy=e.getY(0)-e.getY(1);
        return (float)Math.hypot(dx,dy);
    }
    private float focusX(MotionEvent e) { return e.getPointerCount()<2?e.getX(0):(e.getX(0)+e.getX(1))/2f; }
    private float focusY(MotionEvent e) { return e.getPointerCount()<2?e.getY(0):(e.getY(0)+e.getY(1))/2f; }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()||!hasPaper||!isValidScale()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX=lastX=event.getX(); downY=lastY=event.getY(); moved=false; multiTouch=false;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount()>=2) {
                    multiTouch=true; moved=true; pinchSpan=span(event);
                    pinchFocusX=focusX(event); pinchFocusY=focusY(event);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount()>=2) {
                    multiTouch=true; moved=true;
                    float nextSpan=span(event), nextX=focusX(event), nextY=focusY(event);
                    if (pinchSpan>1f&&nextSpan>1f&&isValidScale()) {
                        float anchorX=(pinchFocusX-left)/scale,anchorY=(pinchFocusY-top)/scale;
                        float nextScale=Math.max(fitPageScale,Math.min(maximumScale(),scale*nextSpan/pinchSpan));
                        if(!Float.isFinite(nextScale))return true;
                        scale=nextScale;
                        zoomFactor=scale/fitPageScale;fitMode=FitMode.MANUAL;
                        left=nextX-anchorX*scale;top=nextY-anchorY*scale;
                    } else { left+=nextX-pinchFocusX;top+=nextY-pinchFocusY; }
                    clampPan();pinchSpan=nextSpan;pinchFocusX=nextX;pinchFocusY=nextY;invalidate();
                } else if (!multiTouch) {
                    float x=event.getX(),y=event.getY();
                    if (Math.hypot(x-downX,y-downY)>touchSlop) moved=true;
                    if (moved) {
                        float oldLeft=left,oldTop=top;
                        left+=x-lastX;top+=y-lastY;clampPan();
                        if(Float.compare(oldLeft,left)!=0||Float.compare(oldTop,top)!=0){
                            fitMode=FitMode.MANUAL;zoomFactor=scale/fitPageScale;
                        }
                        invalidate();
                    }
                    lastX=x;lastY=y;
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                multiTouch=true;moved=true;return true;
            case MotionEvent.ACTION_UP:
                if (!moved&&!multiTouch) {
                    float x=viewToPaperX(event.getX()),y=viewToPaperY(event.getY());
                    float startX=viewToPaperX(downX),startY=viewToPaperY(downY);
                    if (insidePaper(x,y)&&insidePaper(startX,startY)&&listener!=null) {
                        pendingTapX=x;pendingTapY=y;performClick();
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                moved=true;multiTouch=true;pendingTapX=pendingTapY=Float.NaN;return true;
            default:return true;
        }
    }
}
