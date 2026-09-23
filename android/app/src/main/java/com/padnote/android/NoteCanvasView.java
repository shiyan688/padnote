package com.padnote.android;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NoteCanvasView extends View {
    enum Tool {
        PEN,
        HIGHLIGHTER,
        SHAPE,
        ERASER,
        LASSO,
        TEXT
    }

    interface Listener {
        void onInkStatsChanged(int strokes, int points, float pressure, String inputStatus);

        void onDocumentChanged();

        void onSelectionChanged(int selectedStrokes, boolean canUndo, boolean canRedo);

        void onViewportChanged(int currentPage, int pageCount, int zoomPercent, String status);

        void onTextBoxesChanged(List<NoteTextBox> textBoxes);

        void onTextBoxSelectionChanged(String textBoxId);

        void onTextInsertRequested(float worldX, float worldY);
    }

    static final class AiSelectionSnapshot {
        final Bitmap bitmap;
        final byte[] pngBytes;
        final RectF sourceBounds;
        final int strokeCount;
        final int pointCount;
        final boolean lassoMaskApplied;

        AiSelectionSnapshot(Bitmap bitmap, byte[] pngBytes, RectF sourceBounds, int strokeCount,
                            int pointCount, boolean lassoMaskApplied) {
            this.bitmap = bitmap;
            this.pngBytes = pngBytes;
            this.sourceBounds = sourceBounds;
            this.strokeCount = strokeCount;
            this.pointCount = pointCount;
            this.lassoMaskApplied = lassoMaskApplied;
        }
    }

    /**
     * Undo state. Flows are the fact source, so a snapshot stores flows and
     * rebuilds fragments on restore; keeping fragments here would let undo
     * resurrect geometry that disagrees with its own flow.
     */
    private static final class DocumentSnapshot {
        final List<InkStroke> strokes;
        final List<TextFlow> flows;
        final int pageCount;
        List<NoteImage> images = new ArrayList<>();

        DocumentSnapshot(List<InkStroke> strokes, List<TextFlow> flows, int pageCount) {
            this.strokes = strokes;
            this.flows = flows;
            this.pageCount = pageCount;
        }
    }

    private static final class TextFragmentLayout {
        final String source;
        final int pageIndex;
        final float x;
        final float y;
        final float width;
        final float height;

        TextFragmentLayout(String source, int pageIndex, float x, float y,
                           float width, float height) {
            this.source = source;
            this.pageIndex = pageIndex;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * Applied to estimated block heights. Reserving slightly more than the
     * measured height keeps content from being clipped when device font metrics
     * differ from the calibration baseline; the cost of overshooting is only a
     * little unused space at the bottom of a fragment.
     */
    private static final float BLOCK_HEIGHT_SAFETY = 1.06f;

    /** How far measured height may exceed the reservation before correcting. */
    private static final float MEASURED_HEIGHT_TOLERANCE = 1.01f;
    /** Ceiling on the learned correction, so one odd fragment cannot balloon a flow. */
    private static final float MAX_HEIGHT_CORRECTION = 2.0f;
    /** Bounds the measure-reflow loop; without it a fragment could reflow forever. */
    private static final int MAX_HEIGHT_CORRECTION_PASSES = 3;

    private static final int PAPER_COLOR = Color.rgb(251, 250, 246);
    private static final int GUIDE_COLOR = Color.rgb(220, 227, 232);
    private static final int DEFAULT_INK_COLOR = Color.rgb(23, 33, 43);
    private static final int SELECTION_COLOR = Color.rgb(40, 94, 168);
    private static final int WORKSPACE_COLOR = Color.rgb(225, 229, 232);
    private static final int MAX_HISTORY = 30;
    private static final float MIN_VIEWPORT_SCALE = 0.45f;
    private static final float MAX_VIEWPORT_SCALE = 4f;

    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint renderPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eraserCursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pageShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pageNumberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotGuidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dragPreviewFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dragPreviewStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dragPreviewLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previewPageBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previewPageLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pullIndicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pullIndicatorTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pullTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<InkStroke> strokes = new ArrayList<>();
    private final List<NoteImage> images = new ArrayList<>();
    private final List<NoteImage> selectedImages = new ArrayList<>();
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF imageBounds = new RectF();
    private NoteImage resizingImage;
    /**
     * Authoring state for page text, keyed by flow id. This is the fact source;
     * {@link #textBoxes} holds only what the layout pass derived from it.
     */
    /** Paper style. Defaults to the pre-styles appearance for older notes. */
    private PageStyle pageStyle = PageStyle.legacyDefault();
    private PdfBackground pdfBackground;
    private final RectF pdfPageBounds = new RectF();
    private int pdfPageCount;

    private final Map<String, TextFlow> textFlows = new LinkedHashMap<>();
    private final List<NoteTextBox> textBoxes = new ArrayList<>();

    /**
     * Nesting depth of {@link #beginUndoTransaction()}. Above zero, individual
     * mutations stop pushing their own snapshots so a multi-step action stays one
     * undo step.
     */
    private int undoTransactionDepth;

    /** Where the flow being dragged would land; empty when no drag is active. */
    private final List<RectF> dragPreviewRects = new ArrayList<>();
    private String dragPreviewFlowId;
    private int dragPreviewPageLabel;
    /** How long a drag must rest against a viewport edge before turning the page. */
    private static final long EDGE_HOLD_MS = 500L;
    private int edgeHoldDirection;
    private long edgeHoldStartedAt;
    private final List<InkStroke> selectedStrokes = new ArrayList<>();
    private final List<PointF> lassoPoints = new ArrayList<>();
    private final List<PointF> selectionMaskPoints = new ArrayList<>();
    private final Deque<DocumentSnapshot> undoStack = new ArrayDeque<>();
    private final Deque<DocumentSnapshot> redoStack = new ArrayDeque<>();
    private String selectedTextBoxId;

    private InkStroke currentStroke;

    /**
     * Offscreen cache of every finished stroke, in screen space.
     *
     * <p>Without it {@code onDraw} redrew all ink every frame, and because line
     * width follows pen pressure each stroke costs one {@code drawLine} per
     * sample. A page of notes is tens of thousands of draw calls per frame, so
     * latency grew with everything previously written. Finished ink now goes here
     * once and only the stroke under the pen is drawn live, making frame cost
     * independent of how much the page already holds.
     *
     * <p>The cache holds the viewport transform it was built with; any pan, zoom
     * or page change invalidates it.
     */
    private Bitmap inkCache;
    private Canvas inkCacheCanvas;
    private boolean inkCacheDirty = true;
    private float inkCacheScale = Float.NaN;
    private float inkCachePanX = Float.NaN;
    private float inkCachePanY = Float.NaN;
    private final Paint inkCachePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** Cached display density; resolving it per frame showed up in the hot path. */
    private float cachedDensity;
    private Listener listener;
    private Tool selectedTool = Tool.PEN;
    private Tool activeGestureTool = Tool.PEN;
    private int selectedColor = DEFAULT_INK_COLOR;
    private float selectedWidth;
    private float highlighterWidth;
    private int highlighterColor = Color.rgb(255, 205, 45);
    private int shapeType = 0; // 0 rectangle, 1 line, 2 ellipse
    private final Path highlighterPath = new Path();
    private float eraserRadius;
    private boolean penOnly = true;
    private boolean movingSelection = false;
    private boolean documentChangedInGesture = false;
    private boolean showEraserCursor = false;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private int activeToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
    private int activeGesturePageIndex = 0;
    private int pointCount = 0;
    private long strokeSerial = 0;
    private float lastGestureX = 0;
    private float lastGestureY = 0;
    private int pageCount = 1;
    private float pageWidth = 0;
    private float pageHeight = 0;
    private float pageGap = 0;
    private float viewportScale = 1f;
    private float viewportPanX = 0;
    private float viewportPanY = 0;
    private boolean viewportInitialized = false;
    private boolean restoreViewportCenter = false;
    private float restoredViewportZoom = 0;
    private float restoredViewportCenterX = 0;
    private float restoredViewportCenterY = 0;
    private boolean navigationGesture = false;
    private float navigationLastFocusX = 0;
    private float navigationLastFocusY = 0;
    private float navigationLastSpan = 0;
    private float navigationRawPanY = 0;
    private float bottomPullDistance = 0;
    private ValueAnimator viewportReturnAnimator;

    public NoteCanvasView(Context context) {
        this(context, null);
    }

    public NoteCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        selectedWidth = dp(2);
        highlighterWidth = dp(12);
        eraserRadius = dp(15);

        guidePaint.setColor(GUIDE_COLOR);
        guidePaint.setStrokeWidth(dp(0.6f));

        renderPaint.setStrokeCap(Paint.Cap.ROUND);
        renderPaint.setStrokeJoin(Paint.Join.ROUND);

        selectionPaint.setColor(SELECTION_COLOR);
        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setStrokeWidth(dp(1.8f));
        selectionPaint.setPathEffect(new DashPathEffect(new float[]{dp(7), dp(5)}, 0));

        selectionFillPaint.setColor(Color.argb(22, 40, 94, 168));
        selectionFillPaint.setStyle(Paint.Style.FILL);

        eraserCursorPaint.setColor(Color.argb(190, 182, 74, 59));
        eraserCursorPaint.setStyle(Paint.Style.STROKE);
        eraserCursorPaint.setStrokeWidth(dp(1.5f));

        pagePaint.setColor(PAPER_COLOR);
        pagePaint.setStyle(Paint.Style.FILL);
        pageShadowPaint.setColor(Color.argb(42, 45, 57, 66));
        pageShadowPaint.setStyle(Paint.Style.FILL);
        pageNumberPaint.setColor(Color.rgb(132, 143, 151));
        pageNumberPaint.setTextSize(dp(11));
        pageNumberPaint.setTextAlign(Paint.Align.RIGHT);
        dotGuidePaint.setStyle(Paint.Style.FILL);
        dotGuidePaint.setColor(GUIDE_COLOR);
        dragPreviewFillPaint.setStyle(Paint.Style.FILL);
        dragPreviewFillPaint.setColor(Color.argb(38, 40, 94, 168));
        dragPreviewStrokePaint.setStyle(Paint.Style.STROKE);
        dragPreviewStrokePaint.setStrokeWidth(dp(1.5f));
        dragPreviewStrokePaint.setColor(Color.argb(150, 40, 94, 168));
        dragPreviewStrokePaint.setPathEffect(
                new DashPathEffect(new float[]{dp(7), dp(5)}, 0));
        dragPreviewLabelPaint.setColor(Color.argb(200, 40, 94, 168));
        dragPreviewLabelPaint.setTextSize(dp(11));
        previewPageBorderPaint.setColor(Color.argb(180, 40, 94, 168));
        previewPageBorderPaint.setStyle(Paint.Style.STROKE);
        previewPageBorderPaint.setStrokeWidth(dp(1.5f));
        previewPageLabelPaint.setColor(Color.rgb(57, 91, 132));
        previewPageLabelPaint.setTextSize(dp(12));
        previewPageLabelPaint.setTextAlign(Paint.Align.CENTER);
        pullIndicatorPaint.setColor(SELECTION_COLOR);
        pullIndicatorPaint.setStyle(Paint.Style.STROKE);
        pullIndicatorPaint.setStrokeCap(Paint.Cap.ROUND);
        pullIndicatorPaint.setStrokeWidth(dp(4));
        pullIndicatorTrackPaint.setColor(Color.argb(65, 40, 94, 168));
        pullIndicatorTrackPaint.setStyle(Paint.Style.STROKE);
        pullIndicatorTrackPaint.setStrokeWidth(dp(4));
        pullTextPaint.setTextSize(dp(12));
        pullTextPaint.setTextAlign(Paint.Align.CENTER);

        setBackgroundColor(WORKSPACE_COLOR);
        setFocusable(true);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setTool(Tool tool) {
        if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
            return;
        }
        selectedTool = tool;
        lassoPoints.clear();
        showEraserCursor = false;
        if (tool != Tool.LASSO) {
            clearSelectionInternal();
        }
        invalidate();
        dispatchSelectionState();
        dispatchStats(0, toolStatus(tool));
    }

    Tool getTool() {
        return selectedTool;
    }

    void clearTextBoxSelection() {
        if (selectedTextBoxId == null) {
            return;
        }
        selectedTextBoxId = null;
        dispatchSelectionState();
    }

    void setSelectedColor(int color) {
        selectedColor = color;
    }

    void setSelectedWidthDp(float widthDp) {
        selectedWidth = dp(widthDp);
    }

    void setHighlighterStyle(int color, float widthDp) {
        highlighterColor = color;
        highlighterWidth = dp(Math.max(4, Math.min(32, widthDp)));
    }

    void setShapeType(int type) { shapeType = Math.max(0, Math.min(2, type)); }

    void setEraserSizeDp(float diameterDp) {
        eraserRadius = dp(diameterDp) / 2f;
        invalidate();
    }

    void setPenOnly(boolean enabled) {
        penOnly = enabled;
    }

    boolean isPenOnly() {
        return penOnly;
    }

    int getStrokeCount() {
        return strokes.size();
    }

    int getPointCount() {
        return pointCount;
    }

    int getSelectionCount() {
        return selectedStrokes.size() + selectedImages.size() + (hasPdfSelection() ? 1 : 0);
    }

    private boolean hasMovableSelection() {
        return !selectedStrokes.isEmpty() || !selectedImages.isEmpty();
    }

    void insertImage(NoteImage image, int page) {
        ensurePageGeometry();
        checkImageBudget(image);
        pushUndoSnapshot();
        image.page = Math.max(0, Math.min(pageCount - 1, page));
        float scale = Math.min((pageWidth - dp(32)) * 0.65f / image.bitmap.getWidth(),
                (pageHeight - dp(32)) * 0.65f / image.bitmap.getHeight());
        image.width = image.bitmap.getWidth() * scale;
        image.height = image.bitmap.getHeight() * scale;
        image.x = (pageWidth - image.width) / 2f;
        image.y = (pageHeight - image.height) / 2f;
        images.add(image);
        clearSelectionInternal();
        selectedImages.add(image);
        selectedTool = Tool.LASSO;
        invalidate();
        dispatchSelectionState();
        dispatchStats(0, "图片已插入 · 拖动移动，右下角调大小，套索可重新选中");
        notifyDocumentChanged();
    }

    private void checkImageBudget(NoteImage extra) {
        long pixels = extra == null ? 0 : (long) extra.bitmap.getWidth() * extra.bitmap.getHeight();
        long encoded = extra == null ? 0 : extra.png.length();
        for (NoteImage image : images) {
            pixels += (long) image.bitmap.getWidth() * image.bitmap.getHeight();
            encoded += image.png.length();
        }
        if (pixels > NoteImage.MAX_DOCUMENT_PIXELS || encoded > NoteImage.MAX_DOCUMENT_ENCODED) {
            throw new IllegalArgumentException("本笔记图片已达容量上限，请分成另一本笔记");
        }
    }

    private void drawImages(Canvas canvas, int pageIndex) {
        for (NoteImage image : images) {
            if (image.page != pageIndex) continue;
            imageBounds.set(image.x, pageTop(image.page) + image.y,
                    image.x + image.width, pageTop(image.page) + image.y + image.height);
            canvas.drawBitmap(image.bitmap, null, imageBounds, imagePaint);
        }
    }

    private boolean hasPdfSelection() {
        return pdfBackground != null && selectionMaskPoints.size() >= 3
                && pageIndexForWorldY(pointBounds(selectionMaskPoints).centerY()) < pdfPageCount;
    }

    int getPageCount() {
        return pageCount;
    }

    int getCurrentPage() {
        return currentPageIndex() + 1;
    }

    void goToPage(int pageIndex) {
        ensurePageGeometry();
        scrollViewportToPage(Math.max(0, Math.min(pageCount - 1, pageIndex)));
        invalidate();
    }

    int getZoomPercent() {
        return Math.round(viewportScale * 100f);
    }

    float getViewportScale() {
        return viewportScale;
    }

    float getViewportPanX() {
        return viewportPanX;
    }

    float getViewportPanY() {
        return viewportPanY;
    }

    List<NoteTextBox> getTextBoxes() {
        return copyTextBoxes(textBoxes);
    }

    NoteTextBox addTextBox(NoteTextBox.Format format, String source) {
        if (activePointerId != MotionEvent.INVALID_POINTER_ID || navigationGesture) {
            return null;
        }
        float centerWorldX = screenToWorldX(getWidth() / 2f);
        float centerWorldY = screenToWorldY(getHeight() / 2f);
        return addTextBoxAt(format, source, centerWorldX, centerWorldY);
    }

    NoteTextBox addTextBoxAt(NoteTextBox.Format format, String source,
                             float requestedX, float requestedY) {
        if (activePointerId != MotionEvent.INVALID_POINTER_ID || navigationGesture) {
            return null;
        }
        ensurePageGeometry();
        if (!pointInsideAnyPage(requestedX, requestedY)) {
            dispatchStats(0, "请点击页面内部插入文字");
            return null;
        }
        pushUndoSnapshot();
        float width = Math.min(pageWidth - dp(32), dp(380));
        float height = Math.min(pageHeight - dp(32), dp(180));
        int pageIndex = pageIndexForWorldY(requestedY);
        float top = pageTop(pageIndex);
        float x = clamp(requestedX, dp(16),
                Math.max(dp(16), pageWidth - width - dp(16)));
        float y = clamp(requestedY, top + dp(16),
                Math.max(top + dp(16), top + pageHeight - height - dp(16)));
        TextFlow flow = new TextFlow(
                "flow-" + UUID.randomUUID().toString().replace("-", ""),
                format, source, 16f, TextFlow.DEFAULT_LINE_HEIGHT, width,
                pageIndex, x, y - top);
        textFlows.put(flow.id, flow);
        NoteTextBox first = reflowFlow(flow);
        dispatchTextBoxesChanged();
        dispatchSelectionState();
        dispatchStats(0, "已添加 " + flow.format.displayName() + " 文本框");
        notifyDocumentChanged();
        return first;
    }

    NoteTextBox addAiResultTextBox(NoteTextBox.Format format, String source, RectF anchorBounds) {
        if (activePointerId != MotionEvent.INVALID_POINTER_ID || navigationGesture) {
            return null;
        }
        ensurePageGeometry();
        String normalizedSource = source == null ? "" : source.trim();
        if (normalizedSource.isEmpty()) {
            return null;
        }
        RectF anchor = anchorBounds == null || anchorBounds.isEmpty()
                ? new RectF(pageWidth * 0.35f, screenToWorldY(getHeight() * 0.42f),
                pageWidth * 0.65f, screenToWorldY(getHeight() * 0.58f))
                : new RectF(anchorBounds);
        int pageIndex = Math.max(0, Math.min(pageCount - 1,
                pageIndexForWorldY(anchor.centerY())));
        float top = pageTop(pageIndex);
        float margin = dp(16);
        float gap = dp(22);
        float pageRight = pageWidth - margin;
        float pageBottom = top + pageHeight - margin;
        anchor.left = clamp(anchor.left, margin, pageRight);
        anchor.right = clamp(anchor.right, anchor.left, pageRight);
        anchor.top = clamp(anchor.top, top + margin, pageBottom);
        anchor.bottom = clamp(anchor.bottom, anchor.top, pageBottom);

        float desiredWidth = Math.min(dp(430), pageWidth - margin * 2f);
        int visualLines = normalizedSource.split("\\r?\\n", -1).length +
                normalizedSource.length() / 64;
        float desiredHeight = clamp(dp(118 + Math.min(16, visualLines) * 18),
                dp(180), dp(410));
        float minimumWidth = Math.min(dp(240), desiredWidth);
        float minimumHeight = Math.min(dp(150), desiredHeight);
        float x;
        float y;
        float width = desiredWidth;
        float height = desiredHeight;

        float rightSpace = pageRight - anchor.right - gap;
        float belowSpace = pageBottom - anchor.bottom - gap;
        float aboveSpace = anchor.top - (top + margin) - gap;
        float leftSpace = anchor.left - margin - gap;
        if (rightSpace >= minimumWidth) {
            width = Math.min(desiredWidth, rightSpace);
            x = anchor.right + gap;
            y = clamp(anchor.top, top + margin, pageBottom - height);
        } else if (belowSpace >= minimumHeight) {
            height = Math.min(desiredHeight, belowSpace);
            x = clamp(anchor.left, margin, pageRight - width);
            y = anchor.bottom + gap;
        } else if (aboveSpace >= minimumHeight) {
            height = Math.min(desiredHeight, aboveSpace);
            x = clamp(anchor.left, margin, pageRight - width);
            y = anchor.top - gap - height;
        } else if (leftSpace >= minimumWidth) {
            width = Math.min(desiredWidth, leftSpace);
            x = anchor.left - gap - width;
            y = clamp(anchor.top, top + margin, pageBottom - height);
        } else {
            x = clamp(anchor.left, margin, pageRight - width);
            y = clamp(anchor.bottom + gap, top + margin, pageBottom - height);
        }

        pushUndoSnapshot();
        TextFlow flow = new TextFlow(
                "flow-ai-" + UUID.randomUUID().toString().replace("-", ""),
                format == null ? NoteTextBox.Format.MARKDOWN : format,
                normalizedSource, 16f, TextFlow.DEFAULT_LINE_HEIGHT, width,
                pageIndex, x, y - top);
        textFlows.put(flow.id, flow);
        NoteTextBox firstFragment = reflowFlow(flow);
        dispatchTextBoxesChanged();
        dispatchSelectionState();
        dispatchViewportState("AI 回答已自动跨页排版");
        dispatchStats(0, "AI 回答已自动排版 · 套索任一片段可统一编辑");
        notifyDocumentChanged();
        List<NoteTextBox> laidOutFlow = findTextFlow(flow.id);
        return laidOutFlow.isEmpty() ? firstFragment
                : laidOutFlow.get(laidOutFlow.size() - 1).copy();
    }

    void commitNewTextBox(String id, NoteTextBox.Format format, String source) {
        NoteTextBox existing = findTextBox(id);
        commitNewTextBox(id, format, source,
                existing == null ? 16f : existing.fontSizeSp,
                existing == null ? TextFlow.DEFAULT_LINE_HEIGHT : existing.lineHeight);
    }

    void commitNewTextBox(String id, NoteTextBox.Format format, String source,
                          float fontSizeSp, float lineHeight) {
        NoteTextBox textBox = findTextBox(id);
        if (textBox == null) {
            return;
        }
        TextFlow flow = textFlows.get(textBox.flowId);
        if (flow == null) {
            return;
        }
        NoteTextBox.Format normalizedFormat = format == null
                ? NoteTextBox.Format.LATEX : format;
        flow.format = normalizedFormat;
        flow.source = source == null ? "" : source;
        flow.fontSizeSp = TextFlow.clampFontSize(fontSizeSp);
        flow.lineHeight = TextFlow.clampLineHeight(lineHeight);
        NoteTextBox firstFragment = reflowFlow(flow);
        dispatchTextBoxesChanged();
        dispatchSelectionState();
        dispatchViewportState("文字已自动排版");
        dispatchStats(0, "已插入 " + normalizedFormat.displayName() +
                (firstFragment != null && firstFragment.flowCount > 1
                        ? " 跨页文本流" : " 文字对象"));
        notifyDocumentChanged();
    }

    void discardNewTextBox(String id) {
        NoteTextBox textBox = findTextBox(id);
        if (textBox == null) {
            return;
        }
        textBoxes.removeAll(findTextFlow(textBox.flowId));
        textFlows.remove(textBox.flowId);
        if (!undoStack.isEmpty()) {
            undoStack.pop();
        }
        redoStack.clear();
        dispatchTextBoxesChanged();
        dispatchSelectionState();
        dispatchStats(0, toolStatus(selectedTool));
        notifyDocumentChanged();
    }

    void updateTextBox(String id, NoteTextBox.Format format, String source) {
        NoteTextBox existing = findTextBox(id);
        updateTextBox(id, format, source,
                existing == null ? 16f : existing.fontSizeSp,
                existing == null ? TextFlow.DEFAULT_LINE_HEIGHT : existing.lineHeight);
    }

    void updateTextBox(String id, NoteTextBox.Format format, String source,
                       float fontSizeSp, float lineHeight) {
        NoteTextBox textBox = findTextBox(id);
        if (textBox == null) {
            return;
        }
        TextFlow flow = textFlows.get(textBox.flowId);
        if (flow == null) {
            return;
        }
        String normalizedSource = source == null ? "" : source;
        NoteTextBox.Format normalizedFormat = format == null ? NoteTextBox.Format.LATEX : format;
        float normalizedFont = TextFlow.clampFontSize(fontSizeSp);
        float normalizedLeading = TextFlow.clampLineHeight(lineHeight);
        if (flow.format == normalizedFormat && flow.source.equals(normalizedSource) &&
                Math.abs(flow.fontSizeSp - normalizedFont) < 0.01f &&
                Math.abs(flow.lineHeight - normalizedLeading) < 0.001f) {
            return;
        }
        pushUndoSnapshot();
        flow.format = normalizedFormat;
        flow.source = normalizedSource;
        flow.fontSizeSp = normalizedFont;
        flow.lineHeight = normalizedLeading;
        // The correction was learned from the previous content, so keeping it would
        // over-reserve space for simpler text. Relearn from the new render.
        flow.heightCorrection = 1f;
        flow.heightCorrectionPasses = 0;
        NoteTextBox firstFragment = reflowFlow(flow);
        dispatchTextBoxesChanged();
        dispatchSelectionState();
        dispatchViewportState("文本流已重新排版");
        dispatchStats(0, "已更新 " + normalizedFormat.displayName() +
                (firstFragment != null && firstFragment.flowCount > 1
                        ? " 跨页文本流" : " 文字对象"));
        notifyDocumentChanged();
    }

    /**
     * Moves a whole flow by dragging any one of its fragments.
     *
     * <p>The gesture reports where the dragged fragment now sits. That is
     * translated into a shift of the flow's anchor, so dragging the third
     * fragment moves the entire run rather than detaching that page's slice.
     * Because the anchor is page relative, dragging across a page boundary is
     * expressed as a page index change and cannot land "between" pages.
     */
    void moveTextBox(String id, float requestedX, float requestedY) {
        NoteTextBox fragment = findTextBox(id);
        if (fragment == null) {
            return;
        }
        TextFlow flow = textFlows.get(fragment.flowId);
        if (flow == null) {
            return;
        }
        TextFlowAnchor target = anchorForDraggedFragment(flow, fragment,
                requestedX, requestedY);
        if (target.pageIndex == flow.anchorPageIndex &&
                Math.abs(target.xInPage - flow.anchorXInPage) < 0.5f &&
                Math.abs(target.yInPage - flow.anchorYInPage) < 0.5f) {
            dispatchTextBoxesChanged();
            return;
        }
        pushUndoSnapshot();
        flow.anchorPageIndex = target.pageIndex;
        flow.anchorXInPage = target.xInPage;
        flow.anchorYInPage = target.yInPage;
        reflowFlow(flow);
        int fragmentCount = findTextFlow(flow.id).size();
        dispatchTextBoxesChanged();
        dispatchSelectionState();
        dispatchViewportState("文本流已重新排版");
        dispatchStats(0, fragmentCount > 1
                ? "已移动并重排跨页文本流 · 共 " + fragmentCount + " 页"
                : "已移动文字对象");
        notifyDocumentChanged();
    }

    /** A candidate flow anchor, in page-relative coordinates. */
    private static final class TextFlowAnchor {
        final int pageIndex;
        final float xInPage;
        final float yInPage;

        TextFlowAnchor(int pageIndex, float xInPage, float yInPage) {
            this.pageIndex = pageIndex;
            this.xInPage = xInPage;
            this.yInPage = yInPage;
        }
    }

    /**
     * Converts "this fragment was dropped here" into "the flow starts there".
     *
     * <p>The dragged fragment keeps its offset within the flow, so the fragment
     * under the finger is the one that ends up at the drop point.
     */
    private TextFlowAnchor anchorForDraggedFragment(TextFlow flow, NoteTextBox fragment,
                                                    float requestedX, float requestedY) {
        float margin = dp(16);
        int droppedPage = clampPageIndex(pageIndexForWorldY(
                requestedY + Math.min(fragment.height, pageHeight) / 2f));
        float yInDroppedPage = requestedY - pageTop(droppedPage);
        // Shift the anchor by however many pages the dragged fragment sits after
        // the flow start, so the flow head moves the same number of pages.
        int anchorPage = clampPageIndex(droppedPage - fragment.flowIndex);
        float yInPage = fragment.flowIndex == 0 ? yInDroppedPage : margin;
        return new TextFlowAnchor(
                anchorPage,
                clamp(requestedX, margin, Math.max(margin, pageWidth - flow.width - margin)),
                clamp(yInPage, margin, Math.max(margin, pageHeight - margin)));
    }

    private int clampPageIndex(int value) {
        return Math.max(0, Math.min(499, value));
    }

    /**
     * Shows where a flow would land if the in-progress drag were released.
     *
     * <p>Runs the layout on a copy, so the document is untouched until release.
     * Also arms the edge-hold timer that scrolls to the neighbouring page when the
     * dragged fragment is parked against the top or bottom of the viewport.
     */
    void previewTextBoxDrag(String id, float requestedX, float requestedY) {
        NoteTextBox fragment = findTextBox(id);
        if (fragment == null) {
            return;
        }
        TextFlow flow = textFlows.get(fragment.flowId);
        if (flow == null) {
            return;
        }
        TextFlowAnchor target = anchorForDraggedFragment(flow, fragment,
                requestedX, requestedY);
        int savedPageCount = pageCount;
        TextFlow candidate = flow.copy();
        candidate.anchorPageIndex = target.pageIndex;
        candidate.anchorXInPage = target.xInPage;
        candidate.anchorYInPage = target.yInPage;
        dragPreviewRects.clear();
        for (TextFragmentLayout layout : layoutFlow(candidate)) {
            dragPreviewRects.add(new RectF(layout.x, layout.y,
                    layout.x + layout.width, layout.y + layout.height));
        }
        // layoutFlow grows pageCount when content overflows; a preview must not
        // change the document, so put it back.
        pageCount = savedPageCount;
        dragPreviewFlowId = flow.id;
        dragPreviewPageLabel = target.pageIndex + 1;
        updateEdgeHoldScroll(requestedY);
        invalidate();
    }

    void endTextBoxDragPreview() {
        dragPreviewRects.clear();
        dragPreviewFlowId = null;
        cancelEdgeHoldScroll();
        invalidate();
    }

    /**
     * Scrolls to the adjacent page when a drag is held against a viewport edge,
     * so a flow can be moved to a page that is not currently visible.
     */
    private void updateEdgeHoldScroll(float worldY) {
        if (getHeight() <= 0) {
            return;
        }
        float screenY = worldY * viewportScale + viewportPanY;
        float threshold = dp(56);
        int direction = 0;
        if (screenY < threshold) {
            direction = -1;
        } else if (screenY > getHeight() - threshold) {
            direction = 1;
        }
        if (direction == 0) {
            cancelEdgeHoldScroll();
            return;
        }
        if (direction != edgeHoldDirection) {
            edgeHoldDirection = direction;
            edgeHoldStartedAt = System.currentTimeMillis();
            return;
        }
        if (System.currentTimeMillis() - edgeHoldStartedAt < EDGE_HOLD_MS) {
            return;
        }
        edgeHoldStartedAt = System.currentTimeMillis();
        int currentPage = clampPageIndex(pageIndexForWorldY(worldY));
        int nextPage = clampPageIndex(currentPage + edgeHoldDirection);
        if (nextPage == currentPage) {
            return;
        }
        if (nextPage >= pageCount) {
            return;
        }
        scrollViewportToPage(nextPage);
    }

    /** First page touching the viewport; used to virtualise fragment views. */
    int getFirstVisiblePageIndex() {
        if (pageHeight <= 0) {
            return 0;
        }
        return clampPageIndex(pageIndexForWorldY(screenToWorldY(0)));
    }

    /** Last page touching the viewport; used to virtualise fragment views. */
    int getLastVisiblePageIndex() {
        if (pageHeight <= 0) {
            return 0;
        }
        return clampPageIndex(pageIndexForWorldY(screenToWorldY(getHeight())));
    }

    private void cancelEdgeHoldScroll() {
        edgeHoldDirection = 0;
        edgeHoldStartedAt = 0L;
    }

    private void scrollViewportToPage(int pageIndex) {
        viewportPanY = dp(18) - pageTop(clampPageIndex(pageIndex)) * viewportScale;
        navigationRawPanY = viewportPanY;
        clampViewport(false);
        dispatchViewportState("已滚动到第 " + (pageIndex + 1) + " 页");
    }

    /** Moves a flow to an adjacent page, keeping its position within the page. */
    boolean moveTextFlowByPages(String flowId, int pageDelta) {
        TextFlow flow = textFlows.get(flowId);
        if (flow == null || pageDelta == 0) {
            return false;
        }
        int target = flow.anchorPageIndex + pageDelta;
        if (target < 0) {
            return false;
        }
        pushUndoSnapshot();
        flow.anchorPageIndex = clampPageIndex(target);
        if (flow.anchorPageIndex >= pageCount) {
            pageCount = flow.anchorPageIndex + 1;
        }
        reflowFlow(flow);
        dispatchTextBoxesChanged();
        dispatchSelectionState();
        dispatchViewportState("文本流已移动到第 " + (flow.anchorPageIndex + 1) + " 页");
        dispatchStats(0, "已移动到第 " + (flow.anchorPageIndex + 1) + " 页");
        notifyDocumentChanged();
        return true;
    }

    /**
     * Sets the font size for a whole flow and re-paginates it.
     *
     * <p>Separate from {@link #updateTextBox} so restyling does not require an
     * editing session; this is also the shape a {@code set_text_flow_style} tool
     * call will use.
     */
    void setTextFlowFontSize(String id, float fontSizeSp) {
        NoteTextBox fragment = findTextBox(id);
        if (fragment == null) {
            return;
        }
        TextFlow flow = textFlows.get(fragment.flowId);
        if (flow == null) {
            return;
        }
        float normalized = TextFlow.clampFontSize(fontSizeSp);
        if (Math.abs(flow.fontSizeSp - normalized) < 0.01f) {
            return;
        }
        pushUndoSnapshot();
        flow.fontSizeSp = normalized;
        // Height scales with size, so the old correction no longer describes this
        // content; relearn it from the next render.
        flow.heightCorrection = 1f;
        flow.heightCorrectionPasses = 0;
        reflowFlow(flow);
        int fragmentCount = findTextFlow(flow.id).size();
        dispatchTextBoxesChanged();
        dispatchSelectionState();
        dispatchViewportState("字号已调整为 " + Math.round(normalized) + "sp");
        dispatchStats(0, fragmentCount > 1
                ? "字号 " + Math.round(normalized) + "sp · 已重排 " + fragmentCount + " 页"
                : "字号 " + Math.round(normalized) + "sp");
        notifyDocumentChanged();
    }

    /**
     * Fits the type to the box the user drew with the corner handle.
     *
     * <p>Font size is searched for rather than computed. Height is not a clean
     * quadratic function of size: line counts are integers, and headings, block gaps
     * and formulas each round differently. Measured across 10-32sp and three widths,
     * {@code size / sqrt(area per character)} varied by a factor of 1.4, so a closed
     * form would be wrong precisely in the mixed content that matters. A short
     * binary search over the estimator — pure and cheap — finds the largest size
     * that still fits.
     *
     * <p>The result lands in the same {@code fontSizeSp} that {@code A−/A+} and
     * {@code set_text_flow_style} write, so dragging and tapping are two inputs to
     * one value rather than competing sources of truth.
     */
    void scaleTextFlowToArea(String id, float requestedWidth, float requestedHeight) {
        NoteTextBox fragment = findTextBox(id);
        if (fragment == null) {
            return;
        }
        TextFlow flow = textFlows.get(fragment.flowId);
        if (flow == null) {
            return;
        }
        ensurePageGeometry();
        float margin = dp(16);
        float width = clamp(requestedWidth, dp(180), Math.max(dp(180), pageWidth - dp(32)));
        float targetHeight = Math.max(dp(72), requestedHeight);

        float low = 10f;
        float high = 32f;
        float best = low;
        for (int pass = 0; pass < 8; pass++) {
            float candidate = (low + high) / 2f;
            if (measuredFlowHeight(flow, width, candidate) <= targetHeight) {
                best = candidate;
                low = candidate;
            } else {
                high = candidate;
            }
        }
        // Floor, not round: the search guarantees `best` fits, and rounding up can
        // land on the next whole size that does not, reintroducing the overflow the
        // search just avoided.
        float chosen = TextFlow.clampFontSize((float) Math.floor(best));
        // Line height rides along proportionally, so tightening the box tightens
        // leading too instead of leaving the old spacing around smaller type.
        float leadingRatio = chosen / Math.max(1f, flow.fontSizeSp);
        float chosenLeading = TextFlow.clampLineHeight(
                1f + (flow.lineHeight - 1f) * leadingRatio);
        if (Math.abs(chosen - flow.fontSizeSp) < 0.01f &&
                Math.abs(width - flow.width) < 0.5f) {
            dispatchTextBoxesChanged();
            return;
        }

        pushUndoSnapshot();
        flow.width = width;
        flow.fontSizeSp = chosen;
        flow.lineHeight = chosenLeading;
        flow.anchorXInPage = clamp(flow.anchorXInPage, margin,
                Math.max(margin, pageWidth - width - margin));
        // Learned at the previous size, so it no longer describes this content.
        flow.heightCorrection = 1f;
        flow.heightCorrectionPasses = 0;
        reflowFlow(flow);
        int fragmentCount = findTextFlow(flow.id).size();
        dispatchTextBoxesChanged();
        dispatchSelectionState();
        dispatchViewportState("已按框体大小调整字号为 " + Math.round(chosen) + "sp");
        dispatchStats(0, fragmentCount > 1
                ? "字号 " + Math.round(chosen) + "sp · 跨 " + fragmentCount + " 页"
                : "字号 " + Math.round(chosen) + "sp");
        notifyDocumentChanged();
    }

    /** Total laid-out height of a flow at a candidate width and size, in world px. */
    private float measuredFlowHeight(TextFlow flow, float width, float fontSizeSp) {
        TextFlow probe = flow.copy();
        probe.width = width;
        probe.fontSizeSp = fontSizeSp;
        probe.heightCorrection = 1f;
        int savedPageCount = pageCount;
        float total = 0f;
        for (TextFragmentLayout layout : layoutFlow(probe)) {
            total += layout.height;
        }
        pageCount = savedPageCount;
        return total;
    }

    void resizeTextBox(String id, float requestedWidth, float requestedHeight) {
        NoteTextBox textBox = findTextBox(id);
        if (textBox == null) {
            return;
        }
        TextFlow flow = textFlows.get(textBox.flowId);
        if (flow == null) {
            return;
        }
        float margin = dp(16);
        float width = clamp(requestedWidth, dp(180), Math.max(dp(180), pageWidth - dp(16)));
        if (Math.abs(flow.width - width) < 0.5f) {
            dispatchTextBoxesChanged();
            return;
        }
        pushUndoSnapshot();
        flow.width = width;
        flow.anchorXInPage = clamp(flow.anchorXInPage, margin,
                Math.max(margin, pageWidth - width - margin));
        reflowFlow(flow);
        dispatchTextBoxesChanged();
        dispatchSelectionState();
        dispatchViewportState("宽度已调整 · 文本流已重新排版");
        dispatchStats(0, "已调整文字流宽度并自动重排");
        notifyDocumentChanged();
    }

    void deleteTextBox(String id) {
        NoteTextBox textBox = findTextBox(id);
        if (textBox == null) {
            return;
        }
        pushUndoSnapshot();
        List<NoteTextBox> flow = findTextFlow(textBox.flowId);
        textBoxes.removeAll(flow);
        textFlows.remove(textBox.flowId);
        if (textBox.flowId.equals(selectedTextBoxId)) {
            selectedTextBoxId = null;
        }
        dispatchTextBoxesChanged();
        dispatchSelectionState();
        dispatchStats(0, flow.size() > 1 ? "已删除整个跨页文本流" : "已删除文本框");
        notifyDocumentChanged();
    }

    /**
     * Rebuilds every fragment of one flow from the flow's own state.
     *
     * <p>This is the only path that creates {@link NoteTextBox} instances for a
     * flow, so callers mutate the {@link TextFlow} and then reflow; they never
     * edit fragment geometry directly.
     */
    private NoteTextBox reflowFlow(TextFlow flow) {
        if (flow == null) {
            return null;
        }
        List<NoteTextBox> existing = findTextFlow(flow.id);
        int insertionIndex = textBoxes.size();
        for (NoteTextBox fragment : existing) {
            int at = textBoxes.indexOf(fragment);
            if (at >= 0) {
                insertionIndex = Math.min(insertionIndex, at);
            }
        }

        List<TextFragmentLayout> layouts = layoutFlow(flow);
        int count = Math.max(1, layouts.size());
        textBoxes.removeAll(existing);
        List<NoteTextBox> replacements = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            TextFragmentLayout layout = layouts.get(index);
            String fragmentId = index < existing.size() ? existing.get(index).id
                    : "text-flow-" + UUID.randomUUID().toString().replace("-", "");
            replacements.add(new NoteTextBox(fragmentId, flow.id, index, count,
                    flow.format, flow.source, layout.source, flow.fontSizeSp,
                    flow.lineHeight, layout.pageIndex, layout.x, layout.y,
                    layout.width, layout.height));
        }
        textBoxes.addAll(Math.max(0, Math.min(insertionIndex, textBoxes.size())),
                replacements);
        return replacements.get(0).copy();
    }

    /**
     * Pure layout: given a flow and the current page geometry, returns where each
     * fragment lands. Adds pages when content overflows the document, but has no
     * other side effects — callers can run it on a candidate anchor to preview a
     * drag without committing anything.
     */
    private List<TextFragmentLayout> layoutFlow(TextFlow flow) {
        float margin = dp(16);
        float width = clamp(flow.width, dp(180), Math.max(dp(180), pageWidth - margin * 2f));
        List<String> rawBlocks = splitTextFlowBlocks(flow.format, flow.source.trim());
        List<String> sourceBlocks = rawBlocks.isEmpty()
                ? Collections.singletonList("") : rawBlocks;
        List<String> blocks = expandOversizedTextBlocks(flow.format, sourceBlocks, width,
                flow.fontSizeSp, flow.lineHeight, pageHeight - margin * 2f);
        List<TextFragmentLayout> layouts = new ArrayList<>();

        int pageIndex = Math.max(0, flow.anchorPageIndex);
        float x = clamp(flow.anchorXInPage, margin,
                Math.max(margin, pageWidth - width - margin));
        // Anchor Y is page relative, so a flow keeps its position on the page it
        // was placed on instead of drifting when pages are added above it.
        float yInPage = clamp(flow.anchorYInPage, margin,
                Math.max(margin, pageHeight - margin));
        float y = pageTop(pageIndex) + yInPage;
        // Starting a flow in the last sliver of a page produced an empty-looking
        // first fragment, so push it to the next page — but only if that will not
        // strand the anchor, which is what previously fought the move clamp.
        float remaining = pageHeight - margin - yInPage;
        if (remaining < dp(96) && pageIndex < 499) {
            pageIndex += 1;
            y = pageTop(pageIndex) + margin;
        }

        int blockIndex = 0;
        pages: while (blockIndex < blocks.size() && pageIndex < 500) {
            float pageBottom = pageTop(pageIndex) + pageHeight - margin;
            float capacity = Math.max(dp(72), pageBottom - y);
            boolean finalAllowedPage = pageIndex == 499;
            StringBuilder fragment = new StringBuilder();
            // Each fragment renders in its own WebView, so it pays the
            // .content padding (10px top + 10px bottom) exactly once.
            float usedHeight = dp(20);
            BlockKind previousKind = null;
            while (blockIndex < blocks.size()) {
                String block = blocks.get(blockIndex);
                float blockHeight = estimateTextBlockHeight(flow.format, block, width,
                        flow.fontSizeSp, flow.lineHeight, previousKind)
                        * flow.heightCorrection;
                if (!finalAllowedPage && fragment.length() == 0
                        && flow.format == NoteTextBox.Format.MARKDOWN
                        && block.trim().matches("(?is)^```mermaid\\s.*")
                        && usedHeight + blockHeight > capacity
                        && y > pageTop(pageIndex) + margin + 1f) {
                    pageIndex += 1;
                    y = pageTop(pageIndex) + margin;
                    continue pages;
                }
                if (!finalAllowedPage && fragment.length() > 0 &&
                        usedHeight + blockHeight > capacity) {
                    break;
                }
                if (fragment.length() > 0) {
                    fragment.append("\n\n");
                }
                fragment.append(block);
                usedHeight += blockHeight;
                previousKind = blockKindOf(flow.format, block);
                blockIndex += 1;
                if (!finalAllowedPage && usedHeight >= capacity) {
                    break;
                }
            }
            float height = Math.min(capacity, Math.max(dp(72), usedHeight));
            layouts.add(new TextFragmentLayout(fragment.toString(), pageIndex,
                    x, y, width, height));
            pageCount = Math.max(pageCount, pageIndex + 1);
            if (blockIndex >= blocks.size()) {
                break;
            }
            pageIndex += 1;
            if (pageIndex >= 500) {
                break;
            }
            pageCount = Math.max(pageCount, pageIndex + 1);
            y = pageTop(pageIndex) + margin;
        }
        if (layouts.isEmpty()) {
            layouts.add(new TextFragmentLayout("", pageIndex, x, y, width, dp(72)));
        }
        return layouts;
    }

    private List<String> splitTextFlowBlocks(NoteTextBox.Format format, String source) {
        List<String> blocks = new ArrayList<>();
        String normalized = source == null ? ""
                : source.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) {
            blocks.add(normalized);
            return blocks;
        }
        if (format == NoteTextBox.Format.LATEX) {
            for (String block : normalized.split("\\n\\s*\\n")) {
                if (!block.trim().isEmpty()) {
                    blocks.add(block.trim());
                }
            }
            if (blocks.isEmpty()) {
                blocks.add(normalized);
            }
            return blocks;
        }
        StringBuilder current = new StringBuilder();
        boolean fencedCode = false;
        String mathClose = null;
        for (String line : normalized.split("\n", -1)) {
            String trimmed = line.trim();
            if (fencedCode) {
                appendBlockLine(current, line);
                if (trimmed.startsWith("```")) {
                    fencedCode = false;
                    flushTextFlowBlock(blocks, current);
                }
                continue;
            }
            if (mathClose != null) {
                appendBlockLine(current, line);
                if (trimmed.contains(mathClose)) {
                    mathClose = null;
                    flushTextFlowBlock(blocks, current);
                }
                continue;
            }
            if (trimmed.startsWith("```")) {
                flushTextFlowBlock(blocks, current);
                appendBlockLine(current, line);
                fencedCode = true;
                continue;
            }
            if ((trimmed.startsWith("\\[") && !trimmed.contains("\\]")) ||
                    (trimmed.startsWith("$$") && trimmed.indexOf("$$", 2) < 0)) {
                flushTextFlowBlock(blocks, current);
                appendBlockLine(current, line);
                mathClose = trimmed.startsWith("\\[") ? "\\]" : "$$";
                continue;
            }
            if (trimmed.isEmpty()) {
                flushTextFlowBlock(blocks, current);
                continue;
            }
            boolean independentLine = trimmed.startsWith("#") || trimmed.startsWith(">") ||
                    trimmed.matches("[-*+]\\s+.*") || trimmed.matches("\\d+[.)]\\s+.*");
            if (independentLine) {
                flushTextFlowBlock(blocks, current);
                blocks.add(line);
            } else {
                appendBlockLine(current, line);
            }
        }
        flushTextFlowBlock(blocks, current);
        if (blocks.isEmpty()) {
            blocks.add(normalized);
        }
        return blocks;
    }

    private void appendBlockLine(StringBuilder block, String line) {
        if (block.length() > 0) {
            block.append('\n');
        }
        block.append(line);
    }

    private void flushTextFlowBlock(List<String> blocks, StringBuilder block) {
        if (block.length() == 0) {
            return;
        }
        blocks.add(block.toString());
        block.setLength(0);
    }

    /**
     * Block kinds recognised by the paginator. Each maps to the CSS box that
     * {@link CompiledTextWebView} actually emits, so estimated and rendered
     * heights stay in agreement.
     */
    private enum BlockKind {
        PARAGRAPH, LIST_ITEM, HEADING, QUOTE, CODE, MATH
    }

    private static BlockKind blockKindOf(NoteTextBox.Format format, String source) {
        String trimmed = source == null ? "" : source.trim();
        if (format == NoteTextBox.Format.LATEX) {
            return BlockKind.MATH;
        }
        if (trimmed.startsWith("```")) {
            return BlockKind.CODE;
        }
        if (trimmed.startsWith("\\[") || trimmed.startsWith("$$")) {
            return BlockKind.MATH;
        }
        if (trimmed.matches("#{1,6}\\s+.*")) {
            return BlockKind.HEADING;
        }
        if (trimmed.startsWith(">")) {
            return BlockKind.QUOTE;
        }
        if (trimmed.matches("[-*+]\\s+.*") || trimmed.matches("\\d+[.)]\\s+.*")) {
            return BlockKind.LIST_ITEM;
        }
        return BlockKind.PARAGRAPH;
    }

    /**
     * Estimated height of one rendered block, in device pixels.
     *
     * <p>The numbers below mirror the stylesheet in {@link CompiledTextWebView}:
     * paragraphs use {@code line-height:1.55}, headings {@code 1.25} with the UA
     * font scale, {@code blockquote} adds 5px padding top and bottom, {@code pre}
     * adds 9px, and KaTeX display math contributes its own {@code margin:1em 0}
     * on top of {@code .math-display}. Heights were calibrated against real DOM
     * measurements of the bundled KaTeX assets across 10-32sp; a small safety
     * factor is applied because under-reserving clips content while
     * over-reserving only wastes a little space.
     *
     * @param previous kind of the preceding block, or {@code null} for the first
     *                 block in a fragment, used to collapse adjacent margins the
     *                 way CSS does.
     */
    private float estimateTextBlockHeight(NoteTextBox.Format format, String source,
                                          float width, float fontSizeSp,
                                          float flowLineHeight, BlockKind previous) {
        String normalized = source == null ? "" : source;
        String trimmed = normalized.trim();
        BlockKind kind = blockKindOf(format, normalized);
        // Mirrors CompiledTextWebView.buildHtml: block gaps scale with leading.
        float blockGap = Math.max(2f, Math.round(fontSizeSp * (flowLineHeight - 1f) * 0.55f));

        if (format == NoteTextBox.Format.MARKDOWN && trimmed.matches("(?is)^```mermaid\\s.*")) {
            return Math.max(0f, width - dp(24)) * 0.75f + dp(blockGap * 2f);
        }

        if (kind == BlockKind.MATH) {
            String tex = stripMathDelimiters(trimmed);
            float em = displayMathEm(tex);
            return dp(em * fontSizeSp + 2f * fontSizeSp + (blockGap + 2f) * 2f);
        }

        float fontSize = fontSizeSp;
        float lineHeightRatio = flowLineHeight;
        float extra = 0f;
        float indent = 0f;
        String body = trimmed;

        switch (kind) {
            case HEADING:
                int level = 1;
                while (level < trimmed.length() && trimmed.charAt(level) == '#' && level < 6) {
                    level += 1;
                }
                fontSize = fontSizeSp * headingScale(level);
                // Headings keep their own tight leading regardless of body leading.
                lineHeightRatio = 1.22f;
                body = trimmed.replaceFirst("^#{1,6}\\s+", "");
                break;
            case LIST_ITEM:
                indent = dp(Math.round(fontSizeSp * 1.5f));
                body = trimmed.replaceFirst("^([-*+]|\\d+[.)])\\s+", "");
                break;
            case QUOTE:
                extra = dp(blockGap * 2f);
                body = trimmed.replaceFirst("^>\\s*", "");
                break;
            case CODE:
                extra = dp(16);
                fontSize = fontSizeSp * 1.2f;
                lineHeightRatio = Math.min(flowLineHeight, 1.35f);
                body = normalized.replaceAll("(?m)^```.*$", "");
                break;
            default:
                body = normalized;
                break;
        }

        int visualLines = countVisualLines(body, width - dp(24) - indent, fontSize);
        float height = (visualLines * fontSize * lineHeightRatio) * BLOCK_HEIGHT_SAFETY;
        height = dp(height) + extra * BLOCK_HEIGHT_SAFETY;
        if (kind == BlockKind.LIST_ITEM) {
            height += dp(4);
        }
        return collapsedTopMargin(kind, previous, blockGap) + height;
    }

    private static float headingScale(int level) {
        switch (level) {
            case 1: return 2.0f;
            case 2: return 1.5f;
            case 3: return 1.17f;
            case 5: return 0.83f;
            case 6: return 0.67f;
            default: return 1.0f;
        }
    }

    /** Collapses the gap between two adjacent blocks the way CSS margins do. */
    private float collapsedTopMargin(BlockKind kind, BlockKind previous, float blockGap) {
        float own = ownTopMargin(kind, blockGap);
        if (previous == null) {
            return dp(own);
        }
        if (kind == BlockKind.LIST_ITEM && previous == BlockKind.LIST_ITEM) {
            return 0f;
        }
        return dp(Math.max(ownTopMargin(previous, blockGap), own));
    }

    /** Mirrors the margins emitted by {@link CompiledTextWebView#buildHtml}. */
    private static float ownTopMargin(BlockKind kind, float blockGap) {
        switch (kind) {
            case HEADING: return blockGap + 2f;
            case QUOTE: return blockGap + 2f;
            case MATH: return blockGap + 2f;
            default: return blockGap;
        }
    }

    private static String stripMathDelimiters(String trimmed) {
        String value = trimmed;
        if (value.startsWith("\\[")) {
            value = value.substring(2);
        } else if (value.startsWith("$$")) {
            value = value.substring(2);
        }
        if (value.endsWith("\\]")) {
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("$$")) {
            value = value.substring(0, Math.max(0, value.length() - 2));
        }
        return value.trim();
    }

    /** Rendered height of a display formula, expressed in em of the base font. */
    private static float displayMathEm(String tex) {
        String value = tex == null ? "" : tex;
        float em = 1.3f;
        if (value.contains("\\frac") || value.contains("\\dfrac") ||
                value.contains("\\binom")) {
            em += 0.5f;
        }
        if (value.contains("\\int") || value.contains("\\sum") ||
                value.contains("\\prod") || value.contains("\\oint")) {
            em += 0.6f;
        }
        if (value.contains("\\lim") || value.contains("\\max") ||
                value.contains("\\min") || value.contains("\\sup") ||
                value.contains("\\inf")) {
            em += 0.5f;
        }
        if (value.contains("matrix")) {
            int rows = 1;
            int cursor = value.indexOf("\\\\");
            while (cursor >= 0) {
                rows += 1;
                cursor = value.indexOf("\\\\", cursor + 2);
            }
            em += rows * 1.2f;
        }
        if (value.contains("\\sqrt")) {
            em += 0.2f;
        }
        if (value.indexOf('_') >= 0 || value.indexOf('^') >= 0) {
            em += 0.3f;
        }
        return em;
    }

    /**
     * Counts wrapped visual lines using real advance widths: CJK glyphs occupy
     * roughly a full em, Latin about half. Inline formulas are compressed first
     * because rendered KaTeX is far narrower than its LaTeX source.
     */
    private int countVisualLines(String block, float usableWidthPx, float fontSizeSp) {
        float usable = Math.max(dp(24), usableWidthPx);
        int lines = 0;
        for (String raw : (block == null ? "" : block).split("\n", -1)) {
            String line = compressInlineMath(raw);
            float used = 0f;
            for (int index = 0; index < line.length(); ) {
                int codePoint = line.codePointAt(index);
                index += Character.charCount(codePoint);
                used += isWideGlyph(codePoint) ? fontSizeSp : fontSizeSp * 0.5f;
            }
            lines += Math.max(1, (int) Math.ceil(dp(used) / usable));
        }
        return Math.max(1, lines);
    }

    private static boolean isWideGlyph(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x115F) ||
                (codePoint >= 0x2E80 && codePoint <= 0xA4CF) ||
                (codePoint >= 0xAC00 && codePoint <= 0xD7A3) ||
                (codePoint >= 0xF900 && codePoint <= 0xFAFF) ||
                (codePoint >= 0xFE30 && codePoint <= 0xFE6F) ||
                (codePoint >= 0xFF00 && codePoint <= 0xFF60) ||
                (codePoint >= 0xFFE0 && codePoint <= 0xFFE6) ||
                (codePoint >= 0x20000 && codePoint <= 0x3FFFD);
    }

    /**
     * Replaces inline {@code $...$} and {@code \(...\)} spans with a proxy of
     * comparable rendered width, so wrapping estimates are not inflated by
     * LaTeX control sequences that collapse into single glyphs.
     */
    private static String compressInlineMath(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(line.length());
        int cursor = 0;
        while (cursor < line.length()) {
            int open = -1;
            String closeToken = null;
            int parenIndex = line.indexOf("\\(", cursor);
            int dollarIndex = line.indexOf('$', cursor);
            if (parenIndex >= 0 && (dollarIndex < 0 || parenIndex < dollarIndex)) {
                open = parenIndex;
                closeToken = "\\)";
            } else if (dollarIndex >= 0) {
                open = dollarIndex;
                closeToken = "$";
            }
            if (open < 0) {
                out.append(line, cursor, line.length());
                break;
            }
            int contentStart = open + (closeToken.equals("$") ? 1 : 2);
            int close = line.indexOf(closeToken, contentStart);
            if (close < 0) {
                out.append(line, cursor, line.length());
                break;
            }
            out.append(line, cursor, open);
            out.append(mathWidthProxy(line.substring(contentStart, close)));
            cursor = close + closeToken.length();
        }
        return out.toString();
    }

    private static String mathWidthProxy(String tex) {
        float atoms = 0f;
        StringBuilder rest = new StringBuilder();
        for (int index = 0; index < tex.length(); ) {
            char value = tex.charAt(index);
            if (value == '\\' && index + 1 < tex.length() &&
                    Character.isLetter(tex.charAt(index + 1))) {
                int end = index + 1;
                while (end < tex.length() && Character.isLetter(tex.charAt(end))) {
                    end += 1;
                }
                atoms += 1.2f;
                index = end;
                continue;
            }
            if (value == '{' || value == '}') {
                index += 1;
                continue;
            }
            if ((value == '_' || value == '^') && index + 1 < tex.length()) {
                atoms += 0.6f;
                index += 2;
                continue;
            }
            if (!Character.isWhitespace(value)) {
                rest.append(value);
            }
            index += 1;
        }
        atoms += rest.length();
        int width = Math.max(1, Math.round(atoms * 1.1f));
        StringBuilder proxy = new StringBuilder(width);
        for (int index = 0; index < width; index++) {
            proxy.append('x');
        }
        return proxy.toString();
    }

    /** Mean glyph advance of a block, in em, weighting CJK as full width. */
    private static float averageGlyphEm(String block) {
        if (block == null || block.isEmpty()) {
            return 0.5f;
        }
        int wide = 0;
        int narrow = 0;
        for (int index = 0; index < block.length(); ) {
            int codePoint = block.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                continue;
            }
            if (isWideGlyph(codePoint)) {
                wide += 1;
            } else {
                narrow += 1;
            }
        }
        int total = wide + narrow;
        if (total == 0) {
            return 0.5f;
        }
        return (wide * 1.0f + narrow * 0.5f) / total;
    }

    private List<String> expandOversizedTextBlocks(NoteTextBox.Format format,
                                                    List<String> blocks, float width,
                                                    float fontSizeSp, float lineHeight,
                                                    float capacity) {
        List<String> expanded = new ArrayList<>();
        for (String block : blocks) {
            String trimmed = block.trim();
            boolean atomic = format == NoteTextBox.Format.LATEX ||
                    trimmed.startsWith("\\[") || trimmed.startsWith("$$") ||
                    trimmed.startsWith("```") || trimmed.contains("\n|");
            if (atomic || estimateTextBlockHeight(format, block, width, fontSizeSp,
                    lineHeight, null) <= capacity) {
                expanded.add(block);
                continue;
            }
            // Average advance width of this specific block, so CJK text is not
            // assumed to pack as densely as Latin text.
            float characterWidth = Math.max(dp(5), dp(fontSizeSp * averageGlyphEm(block)));
            int charactersPerLine = Math.max(10,
                    (int) ((width - dp(24)) / Math.max(1f, characterWidth)));
            int linesPerPage = Math.max(3,
                    (int) (capacity / Math.max(1f, dp(fontSizeSp * lineHeight))) - 2);
            int targetCharacters = Math.max(80, charactersPerLine * linesPerPage);
            int cursor = 0;
            while (cursor < block.length()) {
                int preferredEnd = Math.min(block.length(), cursor + targetCharacters);
                int end = preferredEnd;
                if (preferredEnd < block.length()) {
                    int minimumEnd = cursor + Math.max(40, targetCharacters / 2);
                    for (int index = preferredEnd; index >= minimumEnd; index--) {
                        char value = block.charAt(index - 1);
                        if (value == '。' || value == '！' || value == '？' ||
                                value == '.' || value == '!' || value == '?' ||
                                Character.isWhitespace(value)) {
                            end = index;
                            break;
                        }
                    }
                }
                if (end <= cursor) {
                    end = preferredEnd;
                }
                expanded.add(block.substring(cursor, end).trim());
                cursor = end;
                while (cursor < block.length() && Character.isWhitespace(block.charAt(cursor))) {
                    cursor += 1;
                }
            }
        }
        return expanded.isEmpty() ? blocks : expanded;
    }

    void addPage() {
        if (activePointerId != MotionEvent.INVALID_POINTER_ID || navigationGesture) {
            return;
        }
        addPageInternal(true, true);
    }

    AiSelectionSnapshot renderAiSelection(int maxDimensionPx) {
        if (getSelectionCount() == 0 || getWidth() <= 0 || getHeight() <= 0) {
            return null;
        }
        boolean useLassoMask = selectionMaskPoints.size() >= 3;
        RectF cropBounds = useLassoMask
                ? pointBounds(selectionMaskPoints)
                : selectionBounds(false);
        cropBounds.inset(-dp(12), -dp(12));
        int pageIndex = pageIndexForWorldY(cropBounds.centerY());
        float pageTop = pageTop(pageIndex);
        cropBounds.left = Math.max(0, cropBounds.left);
        cropBounds.top = Math.max(pageTop, cropBounds.top);
        cropBounds.right = Math.min(pageWidth, cropBounds.right);
        cropBounds.bottom = Math.min(pageTop + pageHeight, cropBounds.bottom);
        if (cropBounds.width() < 1 || cropBounds.height() < 1) {
            return null;
        }

        float longestSide = Math.max(cropBounds.width(), cropBounds.height());
        float scale = Math.min(1f, Math.max(1, maxDimensionPx) / longestSide);
        int bitmapWidth = Math.max(1, Math.round(cropBounds.width() * scale));
        int bitmapHeight = Math.max(1, Math.round(cropBounds.height() * scale));
        Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        Canvas exportCanvas = new Canvas(bitmap);
        exportCanvas.drawColor(PAPER_COLOR);
        exportCanvas.save();
        exportCanvas.scale(scale, scale);
        exportCanvas.translate(-cropBounds.left, -cropBounds.top);
        if (useLassoMask) {
            Path maskPath = new Path();
            maskPath.moveTo(selectionMaskPoints.get(0).x, selectionMaskPoints.get(0).y);
            for (int index = 1; index < selectionMaskPoints.size(); index++) {
                maskPath.lineTo(selectionMaskPoints.get(index).x, selectionMaskPoints.get(index).y);
            }
            maskPath.close();
            exportCanvas.clipPath(maskPath);
        }

        if (pdfBackground != null) {
            pdfBackground.draw(exportCanvas, pageIndex,
                    new RectF(0, pageTop, pageWidth, pageTop + pageHeight), true);
        }
        Paint exportPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        exportPaint.setStrokeCap(Paint.Cap.ROUND);
        exportPaint.setStrokeJoin(Paint.Join.ROUND);
        for (NoteImage image : selectedImages) {
            RectF rect = new RectF(image.x, pageTop(image.page),
                    image.x + image.width, pageTop(image.page) + image.height);
            exportCanvas.drawBitmap(image.bitmap, null, rect, imagePaint);
        }
        int selectedPointCount = 0;
        for (InkStroke stroke : selectedStrokes) {
            selectedPointCount += stroke.points.size();
            drawStrokeOnCanvas(exportCanvas, exportPaint, stroke);
        }
        exportCanvas.restore();
        ByteArrayOutputStream pngOutput = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngOutput)) {
            bitmap.recycle();
            return null;
        }
        return new AiSelectionSnapshot(bitmap, pngOutput.toByteArray(), new RectF(cropBounds),
                selectedStrokes.size(), selectedPointCount, useLassoMask);
    }

    /**
     * Renders one full page — paper ruling plus every ink stroke on it — to PNG
     * for offline digitization.
     *
     * <p>Must be called on the UI thread: it iterates the live stroke list, and
     * the digitization flow guarantees exclusivity with a modal progress dialog.
     * Unlike {@link #renderAiSelection}, upscaling past world size is allowed
     * (capped at 2x, device-pixel parity) because recognition quality depends on
     * giving the model dense pixels of small handwriting.
     *
     * <p>Text flows are deliberately absent: they are already Markdown/LaTeX and
     * are embedded verbatim during assembly instead of being sent through OCR.
     */
    byte[] renderPagePng(int pageIndex, int maxDimensionPx) {
        if (pageIndex < 0 || pageIndex >= pageCount || pageWidth <= 0 || pageHeight <= 0) {
            return null;
        }
        float top = pageTop(pageIndex);
        float longestSide = Math.max(pageWidth, pageHeight);
        float scale = Math.min(2f, Math.max(1f,
                Math.max(1, maxDimensionPx) / longestSide));
        int bitmapWidth = Math.max(1, Math.round(pageWidth * scale));
        int bitmapHeight = Math.max(1, Math.round(pageHeight * scale));
        Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        Canvas exportCanvas = new Canvas(bitmap);
        exportCanvas.drawColor(PAPER_COLOR);
        exportCanvas.scale(scale, scale);
        exportCanvas.translate(0, -top);
        exportCanvas.clipRect(0, top, pageWidth, top + pageHeight);
        drawPaperRuling(exportCanvas, top, dp(32));
        if (pdfBackground != null) {
            pdfBackground.draw(exportCanvas, pageIndex,
                    new RectF(0, top, pageWidth, top + pageHeight), true);
        }
        drawImages(exportCanvas, pageIndex);

        Paint exportPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        exportPaint.setStrokeCap(Paint.Cap.ROUND);
        exportPaint.setStrokeJoin(Paint.Join.ROUND);
        for (InkStroke stroke : strokes) {
            drawStrokeOnCanvas(exportCanvas, exportPaint, stroke);
        }
        ByteArrayOutputStream pngOutput = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngOutput)) {
            bitmap.recycle();
            return null;
        }
        bitmap.recycle();
        return pngOutput.toByteArray();
    }

    /** Snapshot of the fact-source flows for vault assembly; call on UI thread. */
    public List<TextFlow> getTextFlows() {
        return new ArrayList<>(textFlows.values());
    }

    boolean canUndo() {
        return !undoStack.isEmpty();
    }

    boolean canRedo() {
        return !redoStack.isEmpty();
    }

    void undo() {
        if (activePointerId != MotionEvent.INVALID_POINTER_ID || undoStack.isEmpty()) {
            return;
        }
        pushBounded(redoStack, snapshotDocument());
        restoreSnapshot(undoStack.pop());
        clearSelectionInternal();
        rebuildInkBitmap();
        clampViewport(false);
        dispatchStats(0, "已撤销");
        dispatchSelectionState();
        dispatchTextBoxesChanged();
        dispatchViewportState("已撤销");
        notifyDocumentChanged();
    }

    void redo() {
        if (activePointerId != MotionEvent.INVALID_POINTER_ID || redoStack.isEmpty()) {
            return;
        }
        pushBounded(undoStack, snapshotDocument());
        restoreSnapshot(redoStack.pop());
        clearSelectionInternal();
        rebuildInkBitmap();
        clampViewport(false);
        dispatchStats(0, "已重做");
        dispatchSelectionState();
        dispatchTextBoxesChanged();
        dispatchViewportState("已重做");
        notifyDocumentChanged();
    }

    void clearAll() {
        if (activePointerId != MotionEvent.INVALID_POINTER_ID ||
                (strokes.isEmpty() && textBoxes.isEmpty() && images.isEmpty())) {
            return;
        }
        pushUndoSnapshot();
        strokes.clear();
        textBoxes.clear();
        textFlows.clear();
        pointCount = 0;
        images.clear();
        clearSelectionInternal();
        clearInkBitmap();
        invalidate();
        dispatchStats(0, "已清空，可撤销");
        dispatchSelectionState();
        dispatchTextBoxesChanged();
        notifyDocumentChanged();
    }

    void deleteSelection() {
        if (activePointerId != MotionEvent.INVALID_POINTER_ID || !hasMovableSelection()) {
            return;
        }
        pushUndoSnapshot();
        strokes.removeAll(selectedStrokes);
        images.removeAll(selectedImages);
        recountPoints();
        clearSelectionInternal();
        rebuildInkBitmap();
        dispatchStats(0, "已删除选中笔画");
        dispatchSelectionState();
        notifyDocumentChanged();
    }

    void duplicateSelection() {
        if (activePointerId != MotionEvent.INVALID_POINTER_ID || !hasMovableSelection()) {
            return;
        }
        pushUndoSnapshot();
        float offset = dp(18);
        RectF bounds = selectionBounds(false);
        int pageIndex = pageIndexForWorldY(bounds.centerY());
        float pageTop = pageTop(pageIndex);
        if (bounds.right + offset > pageWidth || bounds.bottom + offset > pageTop + pageHeight) {
            offset = -dp(18);
        }
        List<InkStroke> copies = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (InkStroke stroke : selectedStrokes) {
            strokeSerial += 1;
            InkStroke copy = stroke.copyWithId("stroke-copy-" + now + '-' + strokeSerial);
            copy.translate(offset, offset);
            copies.add(copy);
        }
        strokes.addAll(copies);
        selectedStrokes.clear();
        selectedStrokes.addAll(copies);
        List<NoteImage> imageCopies = new ArrayList<>();
        for (NoteImage image : selectedImages) {
            NoteImage copy = image.copy(true);
            copy.x = clamp(copy.x + offset, 0, pageWidth - copy.width);
            copy.y = clamp(copy.y + offset, 0, pageHeight - copy.height);
            imageCopies.add(copy);
        }
        images.addAll(imageCopies);
        selectedImages.clear();
        selectedImages.addAll(imageCopies);
        for (PointF point : selectionMaskPoints) {
            point.offset(offset, offset);
        }
        recountPoints();
        rebuildInkBitmap();
        dispatchStats(0, "已复制选区，可拖动");
        dispatchSelectionState();
        notifyDocumentChanged();
    }

    void cancelSelection() {
        lassoPoints.clear();
        movingSelection = false;
        clearSelectionInternal();
        invalidate();
        dispatchStats(0, toolStatus(selectedTool));
        dispatchSelectionState();
    }

    JSONObject toJsonDocument() throws JSONException {
        return toJsonDocument("note-current", "未命名笔记");
    }

    JSONObject toJsonDocument(String noteId, String title) throws JSONException {
        ensurePageGeometry();
        JSONObject document = new JSONObject();
        document.put("schemaVersion", 8);
        if (pdfPageCount > 0) document.put("pdfPageCount", pdfPageCount);
        document.put("id", noteId);
        document.put("title", title);
        document.put("updatedAt", System.currentTimeMillis());
        document.put("canvasWidth", pageWidth);
        document.put("canvasHeight", pageHeight);
        document.put("pageWidth", pageWidth);
        document.put("pageHeight", pageHeight);
        document.put("pageGap", pageGap);
        document.put("pageCount", pageCount);
        document.put("viewportScale", viewportScale);
        document.put("viewportZoom", viewportScale / Math.max(0.001f, fittedPageScale()));
        document.put("viewportCenterX", screenToWorldX(getWidth() / 2f));
        document.put("viewportCenterY", screenToWorldY(getHeight() / 2f));
        JSONArray strokeArray = new JSONArray();
        for (InkStroke stroke : strokes) {
            strokeArray.put(stroke.toJson());
        }
        document.put("strokes", strokeArray);
        // schemaVersion 5 stores flows only. Fragments are derived, so writing
        // them would duplicate the full source once per page — the file-size and
        // memory blowup that schemaVersion 4 had on long answers.
        JSONArray flowArray = new JSONArray();
        for (TextFlow flow : textFlows.values()) {
            flowArray.put(flow.toJson());
        }
        document.put("textFlows", flowArray);
        document.put("pageStyle", pageStyle.toJson());
        JSONArray imageArray = new JSONArray();
        for (NoteImage image : images) imageArray.put(image.toJson());
        document.put("images", imageArray);
        // Kept as an empty array so a reader expecting schema 3/4 fails its own
        // validation cleanly rather than hitting a missing key.
        document.put("textBoxes", new JSONArray());
        return document;
    }

    void loadJsonDocument(JSONObject document) throws JSONException {
        int schemaVersion = document.optInt("schemaVersion", 0);
        if (schemaVersion < 1 || schemaVersion > 8) {
            throw new JSONException("Unsupported PadNote schema version");
        }
        if (pdfBackground != null) { pdfBackground.close(); pdfBackground = null; }
        pdfPageCount = document.optInt("pdfPageCount", 0);
        if (pdfPageCount > 0) {
            try {
                pdfBackground = new PdfBackground(NoteStore.pdfFile(getContext(), document.getString("id")), this);
            } catch (Exception error) {
                throw new JSONException("无法打开 PDF 原文：" + error.getMessage());
            }
        }
        strokes.clear();
        textBoxes.clear();
        textFlows.clear();
        selectedStrokes.clear();
        lassoPoints.clear();
        selectionMaskPoints.clear();
        undoStack.clear();
        redoStack.clear();
        pageWidth = positiveFloat(document.optDouble("pageWidth",
                document.optDouble("canvasWidth", 0)));
        pageHeight = positiveFloat(document.optDouble("pageHeight",
                document.optDouble("canvasHeight", 0)));
        pageGap = positiveFloat(document.optDouble("pageGap", 0));
        pageCount = Math.max(1, Math.min(500, document.optInt("pageCount", 1)));
        images.clear();
        selectedImages.clear();
        JSONArray imageArray = document.optJSONArray("images");
        if (imageArray != null) {
            for (int index = 0; index < imageArray.length(); index++) {
                NoteImage image = NoteImage.fromJson(imageArray.getJSONObject(index));
                if (image.page < 0 || image.page >= pageCount || image.width <= 0 || image.height <= 0
                        || image.x + image.width > pageWidth + 1 || image.y + image.height > pageHeight + 1) {
                    throw new JSONException("图片超出页面范围");
                }
                checkImageBudget(image);
                images.add(image);
            }
        }
        viewportScale = clamp((float) document.optDouble("viewportScale", 1),
                MIN_VIEWPORT_SCALE, MAX_VIEWPORT_SCALE);
        restoredViewportZoom = positiveFloat(document.optDouble("viewportZoom", 0));
        restoreViewportCenter = document.has("viewportCenterX") &&
                document.has("viewportCenterY");
        restoredViewportCenterX = (float) document.optDouble("viewportCenterX", pageWidth / 2f);
        restoredViewportCenterY = (float) document.optDouble("viewportCenterY", pageHeight / 2f);
        viewportInitialized = false;
        JSONArray strokeArray = document.optJSONArray("strokes");
        if (strokeArray != null) {
            for (int index = 0; index < strokeArray.length(); index++) {
                strokes.add(InkStroke.fromJson(strokeArray.getJSONObject(index)));
            }
        }
        // Notes written before page styles existed keep the appearance they had.
        pageStyle = schemaVersion >= 6
                ? PageStyle.fromJson(document.optJSONObject("pageStyle"))
                : PageStyle.legacyDefault();
        if (schemaVersion >= 5) {
            JSONArray flowArray = document.optJSONArray("textFlows");
            if (flowArray != null) {
                for (int index = 0; index < flowArray.length(); index++) {
                    TextFlow flow = TextFlow.fromJson(flowArray.getJSONObject(index));
                    textFlows.put(flow.id, flow);
                }
            }
        } else {
            migrateLegacyTextBoxes(document.optJSONArray("textBoxes"));
        }
        recountPoints();
        ensurePageGeometry();
        initializeViewportIfReady(schemaVersion == 1);
        // Fragments are not stored; rebuild them once page geometry is known.
        rebuildAllFlows();
        rebuildInkBitmap();
        dispatchStats(0, "已恢复本地笔记");
        dispatchSelectionState();
        dispatchTextBoxesChanged();
        dispatchViewportState(schemaVersion == 1 ? "旧笔记已升级为分页画布" : "已恢复页面位置");
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) {
            return;
        }
        ensurePageGeometry();
        initializeViewportIfReady(false);
        clampViewport(false);
        invalidate();
        dispatchViewportState("手指拖动画布 · 双指缩放");
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ensurePageGeometry();
        refreshInkCacheIfNeeded();
        canvas.drawColor(WORKSPACE_COLOR);
        canvas.save();
        canvas.translate(viewportPanX, viewportPanY);
        canvas.scale(viewportScale, viewportScale);

        float shadowOffset = dp(3) / viewportScale;
        float guideGap = dp(32);
        int firstVisiblePage = pageIndexForWorldY(screenToWorldY(0));
        int lastVisiblePage = pageIndexForWorldY(screenToWorldY(getHeight()));
        for (int pageIndex = firstVisiblePage; pageIndex <= lastVisiblePage; pageIndex++) {
            float top = pageTop(pageIndex);
            canvas.drawRect(shadowOffset, top + shadowOffset,
                    pageWidth + shadowOffset, top + pageHeight + shadowOffset, pageShadowPaint);
            canvas.drawRect(0, top, pageWidth, top + pageHeight, pagePaint);
            drawPaperRuling(canvas, top, guideGap);
            if (pdfBackground != null) {
                pdfPageBounds.set(0, top, pageWidth, top + pageHeight);
                pdfBackground.draw(canvas, pageIndex, pdfPageBounds, false);
            }
            canvas.drawText((pageIndex + 1) + " / " + pageCount,
                    pageWidth - dp(14), top + pageHeight - dp(12), pageNumberPaint);
            drawImages(canvas, pageIndex);
        }

        if (bottomPullDistance > 0) {
            float previewTop = pageTop(pageCount);
            canvas.drawRect(shadowOffset, previewTop + shadowOffset,
                    pageWidth + shadowOffset, previewTop + pageHeight + shadowOffset,
                    pageShadowPaint);
            canvas.drawRect(0, previewTop, pageWidth, previewTop + pageHeight, pagePaint);
            drawPaperRuling(canvas, previewTop, guideGap);
            float borderInset = dp(1.5f) / Math.max(0.001f, viewportScale);
            previewPageBorderPaint.setStrokeWidth(dp(1.5f) /
                    Math.max(0.001f, viewportScale));
            canvas.drawRect(borderInset, previewTop + borderInset,
                    pageWidth - borderInset, previewTop + pageHeight - borderInset,
                    previewPageBorderPaint);
            canvas.drawText("第 " + (pageCount + 1) + " 页预览 · 拉出 1/3 松手添加",
                    pageWidth / 2f, previewTop + dp(22), previewPageLabelPaint);
        }

        // Finished ink and the current stroke are appended to the screen-space
        // cache. A frame therefore costs one bitmap blit instead of replaying the
        // entire current stroke from its first sample.
        if (inkCache != null) {
            // The cache holds screen-space pixels, so drop the world transform to
            // blit it 1:1, then restore it for the live stroke and overlays.
            canvas.restore();
            canvas.drawBitmap(inkCache, 0f, 0f, inkCachePaint);
            canvas.save();
            canvas.translate(viewportPanX, viewportPanY);
            canvas.scale(viewportScale, viewportScale);
        } else {
            // No cache (allocation failed): correctness over speed.
            for (InkStroke stroke : strokes) {
                if (stroke.points.isEmpty()) {
                    continue;
                }
                int strokePage = pageIndexForWorldY(stroke.points.get(0).y);
                if (strokePage < firstVisiblePage || strokePage > lastVisiblePage) {
                    continue;
                }
                float strokePageTop = pageTop(strokePage);
                canvas.save();
                canvas.clipRect(0, strokePageTop, pageWidth, strokePageTop + pageHeight);
                drawStrokeOnCanvas(canvas, renderPaint, stroke);
                canvas.restore();
            }
        }
        if (inkCache != null && currentStroke != null
                && (currentStroke.highlighter || activeGestureTool == Tool.SHAPE)) {
            canvas.save();
            float top = pageTop(activeGesturePageIndex);
            canvas.clipRect(0, top, pageWidth, top + pageHeight);
            drawStrokeOnCanvas(canvas, renderPaint, currentStroke);
            canvas.restore();
        }
        drawSelectionOverlay(canvas);
        drawLassoOverlay(canvas);
        drawDragPreview(canvas);
        if (showEraserCursor) {
            canvas.drawCircle(lastGestureX, lastGestureY, eraserRadius, eraserCursorPaint);
        }
        canvas.restore();
        drawBottomPullIndicator(canvas);
    }

    /**
     * Draws the translucent outline of where each fragment will land once the
     * drag is released. The fragment under the finger keeps following the touch,
     * so this is what tells the user the content will be re-flowed elsewhere.
     */
    private void drawDragPreview(Canvas canvas) {
        if (dragPreviewFlowId == null || dragPreviewRects.isEmpty()) {
            return;
        }
        float radius = dp(10);
        for (RectF rect : dragPreviewRects) {
            canvas.drawRoundRect(rect, radius, radius, dragPreviewFillPaint);
            canvas.drawRoundRect(rect, radius, radius, dragPreviewStrokePaint);
        }
        RectF first = dragPreviewRects.get(0);
        String label = dragPreviewRects.size() > 1
                ? "第 " + dragPreviewPageLabel + " 页起 · 共 " + dragPreviewRects.size() + " 段"
                : "第 " + dragPreviewPageLabel + " 页";
        canvas.drawText(label, first.left + dp(8), first.top - dp(6), dragPreviewLabelPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        final int actionIndex = event.getActionIndex();

        if (action == MotionEvent.ACTION_DOWN) {
            requestUnbufferedDispatch(event);
            int toolType = event.getToolType(actionIndex);
            if (toolType == MotionEvent.TOOL_TYPE_FINGER && isLikelyPalm(event, actionIndex)) {
                dispatchStats(0, "手掌触控已拦截");
                return true;
            }
            if (toolType == MotionEvent.TOOL_TYPE_FINGER && shouldNavigateWithFinger(event, actionIndex)) {
                beginNavigation(event, -1);
                return true;
            }
            if (!acceptsInput(event, actionIndex)) {
                dispatchStats(0, toolLabel(event.getToolType(actionIndex)) + "已拦截");
                return true;
            }
            beginEditingPointer(event, actionIndex);
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            int toolType = event.getToolType(actionIndex);
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS ||
                    toolType == MotionEvent.TOOL_TYPE_ERASER) {
                if (navigationGesture) {
                    finishNavigation(true);
                }
                if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                    cancelActiveEditingGesture();
                }
                beginEditingPointer(event, actionIndex);
                return true;
            }
            if (toolType == MotionEvent.TOOL_TYPE_FINGER && !isLikelyPalm(event, actionIndex) &&
                    activeToolType != MotionEvent.TOOL_TYPE_STYLUS &&
                    activeToolType != MotionEvent.TOOL_TYPE_ERASER) {
                if (!navigationGesture) {
                    cancelActiveEditingGesture();
                    beginNavigation(event, -1);
                } else {
                    resetNavigationReference(event, -1);
                }
            }
            return true;
        }

        if (navigationGesture) {
            if (action == MotionEvent.ACTION_MOVE) {
                updateNavigation(event);
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_UP) {
                int remainingFingers = countFingerPointers(event, actionIndex);
                if (remainingFingers == 0) {
                    finishNavigation(false);
                } else {
                    resetNavigationReference(event, actionIndex);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                finishNavigation(action == MotionEvent.ACTION_CANCEL);
                if (action == MotionEvent.ACTION_UP) {
                    performClick();
                }
                return true;
            }
            return true;
        }

        if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
            return true;
        }

        int pointerIndex = event.findPointerIndex(activePointerId);
        if (pointerIndex < 0) {
            finishGesture(true);
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            for (int historyIndex = 0; historyIndex < event.getHistorySize(); historyIndex++) {
                appendGesturePoint(pointFromEvent(event, pointerIndex, true, historyIndex));
            }
            appendGesturePoint(pointFromEvent(event, pointerIndex, false, 0));
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_UP &&
                event.getPointerId(actionIndex) == activePointerId) {
            appendGesturePoint(pointFromEvent(event, actionIndex, false, 0));
            finishGesture(false);
            performClick();
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP) {
                appendGesturePoint(pointFromEvent(event, pointerIndex, false, 0));
            }
            finishGesture(action == MotionEvent.ACTION_CANCEL);
            if (action == MotionEvent.ACTION_UP) {
                performClick();
            }
            return true;
        }

        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void beginEditingPointer(MotionEvent event, int pointerIndex) {
        activePointerId = event.getPointerId(pointerIndex);
        activeToolType = event.getToolType(pointerIndex);
        activeGestureTool = activeToolType == MotionEvent.TOOL_TYPE_ERASER
                ? Tool.ERASER : selectedTool;
        InkPoint point = pointFromEvent(event, pointerIndex, false, 0);
        if (!pointInsideAnyPage(point.x, point.y)) {
            activePointerId = MotionEvent.INVALID_POINTER_ID;
            activeToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
            dispatchStats(0, "请在页面内书写");
            return;
        }
        activeGesturePageIndex = pageIndexForWorldY(point.y);
        if (activeGestureTool == Tool.TEXT) {
            activePointerId = MotionEvent.INVALID_POINTER_ID;
            activeToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
            if (listener != null) {
                listener.onTextInsertRequested(point.x, point.y);
            }
            dispatchStats(0, "文字笔：已选择插入位置");
            return;
        }
        beginGesture(point);
    }

    private boolean shouldNavigateWithFinger(MotionEvent event, int pointerIndex) {
        if (selectedTool == Tool.TEXT) {
            return false;
        }
        if (!penOnly) {
            return false;
        }
        if (selectedTool == Tool.LASSO && hasMovableSelection()) {
            float worldX = screenToWorldX(event.getX(pointerIndex));
            float worldY = screenToWorldY(event.getY(pointerIndex));
            if (selectionBounds(true).contains(worldX, worldY)) {
                return false;
            }
        }
        return true;
    }

    private void beginNavigation(MotionEvent event, int excludedPointerIndex) {
        cancelViewportReturnAnimation();
        navigationGesture = true;
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        activeToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
        showEraserCursor = false;
        resetNavigationReference(event, excludedPointerIndex);
        navigationRawPanY = viewportPanY;
        bottomPullDistance = Math.max(0, minimumViewportPanY() - viewportPanY);
        dispatchViewportState(countFingerPointers(event, excludedPointerIndex) >= 2
                ? "双指缩放画布" : "手指拖动画布");
    }

    private void resetNavigationReference(MotionEvent event, int excludedPointerIndex) {
        float[] focus = fingerFocus(event, excludedPointerIndex);
        navigationLastFocusX = focus[0];
        navigationLastFocusY = focus[1];
        navigationLastSpan = fingerSpan(event, excludedPointerIndex);
        navigationRawPanY = viewportPanY;
    }

    private void updateNavigation(MotionEvent event) {
        int fingerCount = countFingerPointers(event, -1);
        if (fingerCount <= 0) {
            return;
        }
        float[] focus = fingerFocus(event, -1);
        float focusX = focus[0];
        float focusY = focus[1];
        float span = fingerSpan(event, -1);
        if (fingerCount >= 2 && span > 1 && navigationLastSpan > 1) {
            float oldScale = viewportScale;
            float requestedScale = clamp(oldScale * span / navigationLastSpan,
                    MIN_VIEWPORT_SCALE, MAX_VIEWPORT_SCALE);
            float anchorWorldX = (navigationLastFocusX - viewportPanX) / oldScale;
            float anchorWorldY = (navigationLastFocusY - viewportPanY) / oldScale;
            viewportScale = requestedScale;
            viewportPanX = focusX - anchorWorldX * requestedScale;
            viewportPanY = focusY - anchorWorldY * requestedScale;
            navigationRawPanY = viewportPanY;
            bottomPullDistance = 0;
            clampViewport(false);
            dispatchViewportState("双指缩放 · " + getZoomPercent() + "%");
        } else if (fingerCount == 1) {
            float dx = focusX - navigationLastFocusX;
            float dy = focusY - navigationLastFocusY;
            viewportPanX += dx;
            float minimum = minimumViewportPanY();
            boolean turningTowardEarlierPages = dy > 0;
            if (turningTowardEarlierPages) {
                // Global direction rule: moving toward any earlier page is always 1:1 from the
                // currently visible position. It must never enter (or repay) new-page resistance,
                // regardless of page number, interrupted rebound, or stale raw pull distance.
                viewportPanY = Math.min(maximumViewportPanY(), viewportPanY + dy);
                navigationRawPanY = viewportPanY;
                bottomPullDistance = Math.max(0, minimum - viewportPanY);
            } else {
                navigationRawPanY += dy;
                applyNavigationPanY();
            }
            clampViewportX();
            dispatchViewportState(turningTowardEarlierPages
                    ? "向上翻页 · 1:1 跟手"
                    : (bottomPullDistance > 0 ? bottomPullStatus() : "手指拖动画布"));
        }
        navigationLastFocusX = focusX;
        navigationLastFocusY = focusY;
        navigationLastSpan = span;
        invalidate();
    }

    private void finishNavigation(boolean canceled) {
        if (!navigationGesture) {
            return;
        }
        navigationGesture = false;
        boolean newPageCreationPull = isNewPageCreationPullActive();
        boolean shouldAddPage = !canceled && newPageCreationPull && pageCount < 500 &&
                nextPageRevealFraction() >= nextPageCommitFraction();
        if (shouldAddPage) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            // The provisional paper becomes the real page at the same viewport position.
            // Keeping the current pan prevents a visible jump at the moment of commitment.
            addPageInternal(true, false);
            navigationRawPanY = viewportPanY;
        } else if (canceled) {
            bottomPullDistance = 0;
            clampViewport(false);
            dispatchViewportState("画布导航已取消");
            invalidate();
        } else if (newPageCreationPull) {
            animateViewportBackToDocument();
        } else {
            // Normal navigation ends exactly where the user releases. Spring-back is reserved
            // exclusively for a provisional new page that did not reach its commit threshold.
            bottomPullDistance = 0;
            clampViewport(false);
            dispatchViewportState("手指拖动画布 · 双指缩放");
            invalidate();
        }
    }

    private void cancelActiveEditingGesture() {
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
            return;
        }
        finishGesture(true);
    }

    private void beginGesture(InkPoint point) {
        documentChangedInGesture = false;
        lastGestureX = point.x;
        lastGestureY = point.y;
        if (activeGestureTool == Tool.PEN || activeGestureTool == Tool.HIGHLIGHTER || activeGestureTool == Tool.SHAPE) {
            beginStroke(point);
            return;
        }
        if (activeGestureTool == Tool.ERASER) {
            showEraserCursor = true;
            eraseAt(point.x, point.y);
            invalidate();
            dispatchStats(point.pressure, "局部擦除");
            return;
        }
        beginLasso(point.x, point.y);
    }

    private void appendGesturePoint(InkPoint point) {
        if (!pointInsideAnyPage(point.x, point.y)) {
            return;
        }
        if (pageIndexForWorldY(point.y) != activeGesturePageIndex) {
            return;
        }
        if (activeGestureTool == Tool.PEN || activeGestureTool == Tool.HIGHLIGHTER || activeGestureTool == Tool.SHAPE) {
            appendStrokePoint(point);
            return;
        }
        if (activeGestureTool == Tool.ERASER) {
            float fromX = lastGestureX;
            float fromY = lastGestureY;
            lastGestureX = point.x;
            lastGestureY = point.y;
            eraseAlongPath(fromX, fromY, point.x, point.y);
            invalidate();
            dispatchStats(point.pressure, "局部擦除");
            return;
        }
        appendLassoPoint(point.x, point.y);
    }

    private void finishGesture(boolean canceled) {
        if (canceled) {
            if (documentChangedInGesture && !undoStack.isEmpty()) {
                restoreSnapshot(undoStack.pop());
            }
            currentStroke = null;
            showEraserCursor = false;
            lassoPoints.clear();
            clearSelectionInternal();
            movingSelection = false;
            documentChangedInGesture = false;
            activePointerId = MotionEvent.INVALID_POINTER_ID;
            activeToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
            activeGesturePageIndex = 0;
            rebuildInkBitmap();
            dispatchStats(0, toolStatus(selectedTool));
            dispatchSelectionState();
            return;
        }
        if (activeGestureTool == Tool.PEN || activeGestureTool == Tool.HIGHLIGHTER || activeGestureTool == Tool.SHAPE) {
            if (activeGestureTool == Tool.SHAPE && currentStroke != null) {
                normalizeShape(currentStroke);
                inkCacheDirty = true;
            }
            if (currentStroke != null && currentStroke.highlighter && inkCacheCanvas != null
                    && !inkCacheDirty) {
                inkCacheCanvas.save();
                inkCacheCanvas.translate(viewportPanX, viewportPanY);
                inkCacheCanvas.scale(viewportScale, viewportScale);
                float top = pageTop(activeGesturePageIndex);
                inkCacheCanvas.clipRect(0, top, pageWidth, top + pageHeight);
                drawStrokeOnCanvas(inkCacheCanvas, renderPaint, currentStroke);
                inkCacheCanvas.restore();
            }
            currentStroke = null;
            if (inkCacheCanvas == null || inkCacheDirty) {
                rebuildInkBitmap();
            }
        } else if (activeGestureTool == Tool.ERASER) {
            showEraserCursor = false;
        } else {
            finishLasso(canceled);
        }

        activePointerId = MotionEvent.INVALID_POINTER_ID;
        activeToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
        activeGesturePageIndex = 0;
        movingSelection = false;
        resizingImage = null;
        invalidate();

        if (documentChangedInGesture) {
            dispatchStats(0, "等待自动保存");
            notifyDocumentChanged();
        } else if (activeGestureTool != Tool.LASSO) {
            dispatchStats(0, toolStatus(selectedTool));
        }
        dispatchSelectionState();
        documentChangedInGesture = false;
    }

    private void beginStroke(InkPoint point) {
        pushUndoSnapshot();
        clearSelectionInternal();
        refreshInkCacheIfNeeded();
        strokeSerial += 1;
        long now = System.currentTimeMillis();
        boolean highlight = activeGestureTool == Tool.HIGHLIGHTER;
        currentStroke = new InkStroke("stroke-" + now + '-' + strokeSerial,
                highlight ? (0x69000000 | (highlighterColor & 0xffffff)) : selectedColor,
                highlight ? highlighterWidth : selectedWidth, now, highlight);
        currentStroke.points.add(point);
        strokes.add(currentStroke);
        pointCount += 1;
        appendLivePointToInkCache(null, point, currentStroke);
        documentChangedInGesture = true;
        postInvalidateOnAnimation();
        dispatchStats(point.pressure, "书写中 · " + toolLabel(activeToolType));
    }

    private void normalizeShape(InkStroke stroke) {
        if (stroke.points.size() < 2) return;
        InkPoint first = stroke.points.get(0), last = stroke.points.get(stroke.points.size() - 1);
        float left = Math.min(first.x, last.x), right = Math.max(first.x, last.x);
        float top = Math.min(first.y, last.y), bottom = Math.max(first.y, last.y);
        List<InkPoint> points = new ArrayList<>();
        if (shapeType == 1) { points.add(first); points.add(last); }
        else if (shapeType == 2) {
            float cx = (left + right) / 2f, cy = (top + bottom) / 2f;
            for (int i = 0; i <= 24; i++) { double a = Math.PI * 2 * i / 24; points.add(new InkPoint(cx + (right-left)/2f*(float)Math.cos(a), cy + (bottom-top)/2f*(float)Math.sin(a), last.timestamp, last.pressure)); }
        } else {
            points.add(new InkPoint(left, top, first.timestamp, first.pressure));
            points.add(new InkPoint(right, top, last.timestamp, last.pressure));
            points.add(new InkPoint(right, bottom, last.timestamp, last.pressure));
            points.add(new InkPoint(left, bottom, last.timestamp, last.pressure));
            points.add(new InkPoint(left, top, last.timestamp, last.pressure));
        }
        stroke.points.clear(); stroke.points.addAll(points);
    }

    private void appendStrokePoint(InkPoint point) {
        if (currentStroke == null) {
            return;
        }
        InkPoint previous = currentStroke.points.get(currentStroke.points.size() - 1);
        float dx = point.x - previous.x;
        float dy = point.y - previous.y;
        float threshold = dp(0.35f) / viewportScale;
        if (dx * dx + dy * dy < threshold * threshold) {
            return;
        }
        currentStroke.points.add(point);
        pointCount += 1;
        appendLivePointToInkCache(previous, point, currentStroke);
        postInvalidateOnAnimation();
    }

    private void appendLivePointToInkCache(InkPoint from, InkPoint to, InkStroke stroke) {
        // A marker is one translucent path; overlapping per-sample segments would darken it.
        if (stroke.highlighter || activeGestureTool == Tool.SHAPE) return;
        if (inkCacheCanvas == null || inkCacheDirty) {
            return;
        }
        int pageIndex = pageIndexForWorldY(to.y);
        float top = pageTop(pageIndex);
        inkCacheCanvas.save();
        inkCacheCanvas.translate(viewportPanX, viewportPanY);
        inkCacheCanvas.scale(viewportScale, viewportScale);
        inkCacheCanvas.clipRect(0, top, pageWidth, top + pageHeight);
        renderPaint.setColor(stroke.color);
        if (from == null) {
            renderPaint.setStyle(Paint.Style.FILL);
            inkCacheCanvas.drawCircle(to.x, to.y,
                    pressureWidth(to, stroke.baseWidth) / 2f, renderPaint);
        } else {
            renderPaint.setStyle(Paint.Style.STROKE);
            renderPaint.setStrokeWidth((pressureWidth(from, stroke.baseWidth) +
                    pressureWidth(to, stroke.baseWidth)) / 2f);
            inkCacheCanvas.drawLine(from.x, from.y, to.x, to.y, renderPaint);
        }
        inkCacheCanvas.restore();
    }

    private void eraseAlongPath(float fromX, float fromY, float toX, float toY) {
        float distance = (float) Math.sqrt(squaredDistance(fromX, fromY, toX, toY));
        float stepLength = Math.max(dp(1.5f), eraserRadius * 0.35f);
        int steps = Math.max(1, (int) Math.ceil(distance / stepLength));
        for (int step = 1; step <= steps; step++) {
            float ratio = step / (float) steps;
            eraseAt(fromX + (toX - fromX) * ratio, fromY + (toY - fromY) * ratio);
        }
    }

    private void eraseAt(float x, float y) {
        boolean changed = false;
        List<InkStroke> replacement = new ArrayList<>();
        for (InkStroke stroke : strokes) {
            float effectiveRadius = eraserRadius + stroke.baseWidth * 0.7f;
            if (!strokeNearPoint(stroke, x, y, effectiveRadius * effectiveRadius)) {
                replacement.add(stroke);
                continue;
            }
            changed = true;
            replacement.addAll(clipStrokeOutsideCircle(stroke, x, y, effectiveRadius));
        }
        if (!changed) {
            return;
        }
        if (!documentChangedInGesture) {
            pushUndoSnapshot();
            documentChangedInGesture = true;
        }
        strokes.clear();
        strokes.addAll(replacement);
        clearSelectionInternal();
        recountPoints();
        rebuildInkBitmap();
        dispatchSelectionState();
    }

    private List<InkStroke> clipStrokeOutsideCircle(InkStroke stroke, float centerX,
                                                     float centerY, float radius) {
        List<InkStroke> fragments = new ArrayList<>();
        if (stroke.points.isEmpty()) {
            return fragments;
        }
        float radiusSquared = radius * radius;
        if (stroke.points.size() == 1) {
            InkPoint point = stroke.points.get(0);
            if (squaredDistance(point.x, point.y, centerX, centerY) > radiusSquared) {
                fragments.add(stroke);
            }
            return fragments;
        }

        List<List<InkPoint>> pointFragments = new ArrayList<>();
        List<InkPoint> currentFragment = null;
        for (int index = 1; index < stroke.points.size(); index++) {
            InkPoint from = stroke.points.get(index - 1);
            InkPoint to = stroke.points.get(index);
            List<Float> breaks = segmentCircleBreaks(from, to, centerX, centerY, radius);
            for (int interval = 1; interval < breaks.size(); interval++) {
                float startRatio = breaks.get(interval - 1);
                float endRatio = breaks.get(interval);
                if (endRatio - startRatio < 0.00001f) {
                    continue;
                }
                float middleRatio = (startRatio + endRatio) / 2f;
                InkPoint middle = interpolatePoint(from, to, middleRatio);
                boolean outside = squaredDistance(middle.x, middle.y, centerX, centerY) >= radiusSquared;
                if (!outside) {
                    if (currentFragment != null && currentFragment.size() > 1) {
                        pointFragments.add(currentFragment);
                    }
                    currentFragment = null;
                    continue;
                }

                InkPoint start = interpolatePoint(from, to, startRatio);
                InkPoint end = interpolatePoint(from, to, endRatio);
                if (currentFragment == null) {
                    currentFragment = new ArrayList<>();
                    currentFragment.add(start);
                } else if (!samePosition(currentFragment.get(currentFragment.size() - 1), start)) {
                    if (currentFragment.size() > 1) {
                        pointFragments.add(currentFragment);
                    }
                    currentFragment = new ArrayList<>();
                    currentFragment.add(start);
                }
                if (!samePosition(currentFragment.get(currentFragment.size() - 1), end)) {
                    currentFragment.add(end);
                }
            }
        }
        if (currentFragment != null && currentFragment.size() > 1) {
            pointFragments.add(currentFragment);
        }

        boolean firstFragment = true;
        for (List<InkPoint> points : pointFragments) {
            String id;
            if (firstFragment) {
                id = stroke.id;
                firstFragment = false;
            } else {
                strokeSerial += 1;
                id = "stroke-erase-" + System.currentTimeMillis() + '-' + strokeSerial;
            }
            InkStroke fragment = new InkStroke(id, stroke.color, stroke.baseWidth, stroke.createdAt, stroke.highlighter);
            fragment.points.addAll(points);
            fragments.add(fragment);
        }
        return fragments;
    }

    private List<Float> segmentCircleBreaks(InkPoint from, InkPoint to, float centerX,
                                             float centerY, float radius) {
        List<Float> breaks = new ArrayList<>();
        breaks.add(0f);
        float dx = to.x - from.x;
        float dy = to.y - from.y;
        float offsetX = from.x - centerX;
        float offsetY = from.y - centerY;
        float a = dx * dx + dy * dy;
        if (a > 0.000001f) {
            float b = 2f * (offsetX * dx + offsetY * dy);
            float c = offsetX * offsetX + offsetY * offsetY - radius * radius;
            float discriminant = b * b - 4f * a * c;
            if (discriminant > 0f) {
                float root = (float) Math.sqrt(discriminant);
                addInteriorBreak(breaks, (-b - root) / (2f * a));
                addInteriorBreak(breaks, (-b + root) / (2f * a));
            }
        }
        breaks.add(1f);
        Collections.sort(breaks);
        return breaks;
    }

    private void addInteriorBreak(List<Float> breaks, float ratio) {
        if (ratio > 0.00001f && ratio < 0.99999f) {
            for (float existing : breaks) {
                if (Math.abs(existing - ratio) < 0.00001f) {
                    return;
                }
            }
            breaks.add(ratio);
        }
    }

    private InkPoint interpolatePoint(InkPoint from, InkPoint to, float ratio) {
        return new InkPoint(
                from.x + (to.x - from.x) * ratio,
                from.y + (to.y - from.y) * ratio,
                from.timestamp + Math.round((to.timestamp - from.timestamp) * ratio),
                from.pressure + (to.pressure - from.pressure) * ratio
        );
    }

    private boolean samePosition(InkPoint first, InkPoint second) {
        return squaredDistance(first.x, first.y, second.x, second.y) < 0.0001f;
    }

    private boolean strokeNearPoint(InkStroke stroke, float x, float y, float radiusSquared) {
        if (stroke.points.isEmpty()) {
            return false;
        }
        if (stroke.points.size() == 1) {
            InkPoint point = stroke.points.get(0);
            return squaredDistance(point.x, point.y, x, y) <= radiusSquared;
        }
        for (int index = 1; index < stroke.points.size(); index++) {
            InkPoint from = stroke.points.get(index - 1);
            InkPoint to = stroke.points.get(index);
            if (pointToSegmentDistanceSquared(x, y, from.x, from.y, to.x, to.y) <= radiusSquared) {
                return true;
            }
        }
        return false;
    }

    private void beginLasso(float x, float y) {
        RectF bounds = selectionBounds(true);
        if (hasMovableSelection() && bounds.contains(x, y)) {
            if (selectedImages.size() == 1 && selectedStrokes.isEmpty()) {
                NoteImage image = selectedImages.get(0);
                float radius = dp(18) / viewportScale;
                if (Math.abs(x - image.x - image.width) <= radius
                        && Math.abs(y - pageTop(image.page) - image.y - image.height) <= radius) {
                    pushUndoSnapshot();
                    resizingImage = image;
                    documentChangedInGesture = true;
                }
            }
            movingSelection = true;
            lastGestureX = x;
            lastGestureY = y;
            dispatchStats(0, "拖动选区");
            return;
        }
        clearSelectionInternal();
        lassoPoints.clear();
        lassoPoints.add(new PointF(x, y));
        dispatchSelectionState();
        dispatchStats(0, "圈住要选择的笔画");
        invalidate();
    }

    private void appendLassoPoint(float x, float y) {
        if (movingSelection) {
            moveSelection(x, y);
            return;
        }
        if (lassoPoints.isEmpty()) {
            lassoPoints.add(new PointF(x, y));
            return;
        }
        PointF previous = lassoPoints.get(lassoPoints.size() - 1);
        float threshold = dp(2.5f) / viewportScale;
        if (squaredDistance(previous.x, previous.y, x, y) < threshold * threshold) {
            return;
        }
        lassoPoints.add(new PointF(x, y));
        invalidate();
    }

    private void moveSelection(float x, float y) {
        if (resizingImage != null) {
            float ratio = resizingImage.bitmap.getHeight() / (float) resizingImage.bitmap.getWidth();
            float max = Math.min(pageWidth - resizingImage.x, (pageHeight - resizingImage.y) / ratio);
            resizingImage.width = clamp(x - resizingImage.x, Math.min(dp(32), max), max);
            resizingImage.height = resizingImage.width * ratio;
            invalidate();
            return;
        }
        float dx = x - lastGestureX;
        float dy = y - lastGestureY;
        lastGestureX = x;
        lastGestureY = y;
        if (Math.abs(dx) < 0.01f && Math.abs(dy) < 0.01f) {
            return;
        }
        RectF bounds = selectionBounds(false);
        int pageIndex = pageIndexForWorldY(bounds.centerY());
        float pageTop = pageTop(pageIndex);
        dx = Math.max(-bounds.left, Math.min(dx, pageWidth - bounds.right));
        dy = Math.max(pageTop - bounds.top,
                Math.min(dy, pageTop + pageHeight - bounds.bottom));
        if (Math.abs(dx) < 0.01f && Math.abs(dy) < 0.01f) {
            return;
        }
        if (!documentChangedInGesture) {
            pushUndoSnapshot();
            List<InkStroke> movableCopies = new ArrayList<>();
            for (InkStroke selected : selectedStrokes) {
                InkStroke copy = selected.copy();
                strokes.set(strokes.indexOf(selected), copy);
                movableCopies.add(copy);
            }
            selectedStrokes.clear();
            selectedStrokes.addAll(movableCopies);
            documentChangedInGesture = true;
        }
        for (InkStroke stroke : selectedStrokes) {
            stroke.translate(dx, dy);
        }
        for (NoteImage image : selectedImages) {
            image.x += dx;
            image.y += dy;
        }
        for (PointF point : selectionMaskPoints) {
            point.offset(dx, dy);
        }
        rebuildInkBitmap();
        dispatchStats(0, "正在移动选区");
    }

    private void finishLasso(boolean canceled) {
        if (movingSelection) {
            dispatchStats(0, documentChangedInGesture ? "选区已移动" : "选区未移动");
            return;
        }
        if (canceled || lassoPoints.size() < 3) {
            lassoPoints.clear();
            dispatchStats(0, "套索已取消");
            return;
        }
        selectStrokesInsideLasso();
        selectedImages.clear();
        for (NoteImage image : images) {
            RectF bounds = new RectF(image.x, pageTop(image.page) + image.y,
                    image.x + image.width, pageTop(image.page) + image.y + image.height);
            if (textBoxIntersectsLasso(bounds)) selectedImages.add(image);
        }
        if (!selectedImages.isEmpty() && selectionMaskPoints.isEmpty()) {
            for (PointF point : lassoPoints) selectionMaskPoints.add(new PointF(point.x, point.y));
        }
        selectTextBoxInsideLasso();
        lassoPoints.clear();
        if (!selectedImages.isEmpty()) {
            dispatchStats(0, "已选中图片 · 拖动移动，右下角调大小，工具栏删除或复制");
        } else if (hasPdfSelection()) {
            dispatchStats(0, "已圈选 PDF 原文 · 点击 AI 提问");
        } else if (selectedStrokes.isEmpty() && selectedTextBoxId == null) {
            dispatchStats(0, "没有选中笔画或文字对象");
        } else if (selectedTextBoxId != null && selectedStrokes.isEmpty()) {
            dispatchStats(0, "已选中文字对象 · 可移动、缩放或删除");
        } else if (selectedTextBoxId != null) {
            dispatchStats(0, "已选中 " + selectedStrokes.size() + " 笔和文字对象");
        } else {
            dispatchStats(0, "已选中 " + selectedStrokes.size() + " 笔，可拖动");
        }
    }

    private void selectStrokesInsideLasso() {
        selectedStrokes.clear();
        selectionMaskPoints.clear();
        RectF lassoBounds = pointBounds(lassoPoints);
        for (InkStroke stroke : strokes) {
            RectF strokeBounds = strokeBounds(stroke, true);
            if (!rectanglesOverlap(lassoBounds, strokeBounds)) {
                continue;
            }
            if (strokeIntersectsLasso(stroke)) {
                selectedStrokes.add(stroke);
            }
        }
        if (!selectedStrokes.isEmpty() || (pdfBackground != null
                && pageIndexForWorldY(lassoBounds.centerY()) < pdfPageCount)) {
            for (PointF point : lassoPoints) {
                selectionMaskPoints.add(new PointF(point.x, point.y));
            }
        }
    }

    private void selectTextBoxInsideLasso() {
        selectedTextBoxId = null;
        RectF lassoBounds = pointBounds(lassoPoints);
        for (int index = textBoxes.size() - 1; index >= 0; index--) {
            NoteTextBox textBox = textBoxes.get(index);
            RectF bounds = new RectF(textBox.x, textBox.y,
                    textBox.x + textBox.width, textBox.y + textBox.height);
            if (rectanglesOverlap(lassoBounds, bounds) && textBoxIntersectsLasso(bounds)) {
                selectedTextBoxId = textBox.flowId;
                return;
            }
        }
    }

    private boolean textBoxIntersectsLasso(RectF bounds) {
        float[][] samples = new float[][]{
                {bounds.centerX(), bounds.centerY()},
                {bounds.left, bounds.top}, {bounds.right, bounds.top},
                {bounds.right, bounds.bottom}, {bounds.left, bounds.bottom}
        };
        for (float[] sample : samples) {
            if (pointInsidePolygon(sample[0], sample[1], lassoPoints)) {
                return true;
            }
        }
        for (PointF point : lassoPoints) {
            if (bounds.contains(point.x, point.y)) {
                return true;
            }
        }
        float[][] edges = new float[][]{
                {bounds.left, bounds.top, bounds.right, bounds.top},
                {bounds.right, bounds.top, bounds.right, bounds.bottom},
                {bounds.right, bounds.bottom, bounds.left, bounds.bottom},
                {bounds.left, bounds.bottom, bounds.left, bounds.top}
        };
        for (int lassoIndex = 0; lassoIndex < lassoPoints.size(); lassoIndex++) {
            PointF from = lassoPoints.get(lassoIndex);
            PointF to = lassoPoints.get((lassoIndex + 1) % lassoPoints.size());
            for (float[] edge : edges) {
                if (segmentsIntersect(from.x, from.y, to.x, to.y,
                        edge[0], edge[1], edge[2], edge[3])) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean strokeIntersectsLasso(InkStroke stroke) {
        for (InkPoint point : stroke.points) {
            if (pointInsidePolygon(point.x, point.y, lassoPoints)) {
                return true;
            }
        }
        if (stroke.points.size() < 2) {
            return false;
        }
        for (int strokeIndex = 1; strokeIndex < stroke.points.size(); strokeIndex++) {
            InkPoint strokeFrom = stroke.points.get(strokeIndex - 1);
            InkPoint strokeTo = stroke.points.get(strokeIndex);
            for (int lassoIndex = 0; lassoIndex < lassoPoints.size(); lassoIndex++) {
                PointF lassoFrom = lassoPoints.get(lassoIndex);
                PointF lassoTo = lassoPoints.get((lassoIndex + 1) % lassoPoints.size());
                if (segmentsIntersect(
                        strokeFrom.x, strokeFrom.y, strokeTo.x, strokeTo.y,
                        lassoFrom.x, lassoFrom.y, lassoTo.x, lassoTo.y)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void drawSelectionOverlay(Canvas canvas) {
        if (hasPdfSelection()) {
            canvas.drawRoundRect(pointBounds(selectionMaskPoints), dp(6), dp(6), selectionPaint);
        }
        if (!hasMovableSelection()) {
            return;
        }
        RectF bounds = selectionBounds(true);
        canvas.drawRoundRect(bounds, dp(6), dp(6), selectionFillPaint);
        canvas.drawRoundRect(bounds, dp(6), dp(6), selectionPaint);
        if (selectedImages.size() == 1 && selectedStrokes.isEmpty()) {
            NoteImage image = selectedImages.get(0);
            canvas.drawCircle(image.x + image.width, pageTop(image.page) + image.y + image.height,
                    dp(7) / viewportScale, selectionPaint);
        }
    }

    private void drawLassoOverlay(Canvas canvas) {
        if (lassoPoints.isEmpty()) {
            return;
        }
        Path path = new Path();
        path.moveTo(lassoPoints.get(0).x, lassoPoints.get(0).y);
        for (int index = 1; index < lassoPoints.size(); index++) {
            path.lineTo(lassoPoints.get(index).x, lassoPoints.get(index).y);
        }
        if (lassoPoints.size() > 2) {
            path.close();
        }
        canvas.drawPath(path, selectionPaint);
    }

    private RectF selectionBounds(boolean padded) {
        if (!hasMovableSelection()) {
            return new RectF();
        }
        RectF bounds = new RectF();
        boolean initialized = false;
        for (InkStroke stroke : selectedStrokes) {
            RectF strokeBounds = strokeBounds(stroke, false);
            if (!initialized) {
                bounds.set(strokeBounds);
                initialized = true;
            } else {
                bounds.left = Math.min(bounds.left, strokeBounds.left);
                bounds.top = Math.min(bounds.top, strokeBounds.top);
                bounds.right = Math.max(bounds.right, strokeBounds.right);
                bounds.bottom = Math.max(bounds.bottom, strokeBounds.bottom);
            }
        }
        for (NoteImage image : selectedImages) {
            RectF rect = new RectF(image.x, pageTop(image.page) + image.y,
                    image.x + image.width, pageTop(image.page) + image.y + image.height);
            if (!initialized) { bounds.set(rect); initialized = true; }
            else bounds.union(rect);
        }
        if (padded) {
            bounds.inset(-dp(10), -dp(10));
        }
        return bounds;
    }

    private RectF strokeBounds(InkStroke stroke, boolean padded) {
        if (stroke.points.isEmpty()) {
            return new RectF();
        }
        float left = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (InkPoint point : stroke.points) {
            left = Math.min(left, point.x);
            top = Math.min(top, point.y);
            right = Math.max(right, point.x);
            bottom = Math.max(bottom, point.y);
        }
        float padding = padded ? Math.max(stroke.baseWidth, dp(2)) : 0;
        return new RectF(left - padding, top - padding, right + padding, bottom + padding);
    }

    private RectF pointBounds(List<PointF> points) {
        float left = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (PointF point : points) {
            left = Math.min(left, point.x);
            top = Math.min(top, point.y);
            right = Math.max(right, point.x);
            bottom = Math.max(bottom, point.y);
        }
        return new RectF(left, top, right, bottom);
    }

    private boolean rectanglesOverlap(RectF first, RectF second) {
        return first.left <= second.right && first.right >= second.left &&
                first.top <= second.bottom && first.bottom >= second.top;
    }

    private boolean pointInsidePolygon(float x, float y, List<PointF> polygon) {
        boolean inside = false;
        for (int current = 0, previous = polygon.size() - 1;
             current < polygon.size(); previous = current++) {
            PointF currentPoint = polygon.get(current);
            PointF previousPoint = polygon.get(previous);
            boolean crosses = (currentPoint.y > y) != (previousPoint.y > y) &&
                    x < (previousPoint.x - currentPoint.x) * (y - currentPoint.y) /
                            (previousPoint.y - currentPoint.y) + currentPoint.x;
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    private boolean segmentsIntersect(float ax, float ay, float bx, float by,
                                      float cx, float cy, float dx, float dy) {
        float direction1 = direction(cx, cy, dx, dy, ax, ay);
        float direction2 = direction(cx, cy, dx, dy, bx, by);
        float direction3 = direction(ax, ay, bx, by, cx, cy);
        float direction4 = direction(ax, ay, bx, by, dx, dy);
        boolean crosses = ((direction1 > 0 && direction2 < 0) || (direction1 < 0 && direction2 > 0)) &&
                ((direction3 > 0 && direction4 < 0) || (direction3 < 0 && direction4 > 0));
        if (crosses) {
            return true;
        }
        float epsilon = 0.001f;
        return (Math.abs(direction1) <= epsilon && pointOnSegment(ax, ay, cx, cy, dx, dy)) ||
                (Math.abs(direction2) <= epsilon && pointOnSegment(bx, by, cx, cy, dx, dy)) ||
                (Math.abs(direction3) <= epsilon && pointOnSegment(cx, cy, ax, ay, bx, by)) ||
                (Math.abs(direction4) <= epsilon && pointOnSegment(dx, dy, ax, ay, bx, by));
    }

    private float direction(float ax, float ay, float bx, float by, float cx, float cy) {
        return (cx - ax) * (by - ay) - (cy - ay) * (bx - ax);
    }

    private boolean pointOnSegment(float px, float py, float ax, float ay, float bx, float by) {
        float epsilon = 0.001f;
        return px >= Math.min(ax, bx) - epsilon && px <= Math.max(ax, bx) + epsilon &&
                py >= Math.min(ay, by) - epsilon && py <= Math.max(ay, by) + epsilon;
    }

    private float pointToSegmentDistanceSquared(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        if (dx == 0 && dy == 0) {
            return squaredDistance(px, py, ax, ay);
        }
        float position = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy);
        position = Math.max(0, Math.min(1, position));
        float closestX = ax + position * dx;
        float closestY = ay + position * dy;
        return squaredDistance(px, py, closestX, closestY);
    }

    private float squaredDistance(float firstX, float firstY, float secondX, float secondY) {
        float dx = firstX - secondX;
        float dy = firstY - secondY;
        return dx * dx + dy * dy;
    }

    private void ensurePageGeometry() {
        if (pageGap <= 0) {
            pageGap = dp(28);
        }
        if ((pageWidth <= 0 || pageHeight <= 0) && getWidth() > 0 && getHeight() > 0) {
            // Size comes from the page style, so an A4 note keeps its proportion
            // instead of stretching to whatever the device happens to be.
            float[] size = pageStyle.resolveSize(getWidth() - dp(40),
                    getHeight() - dp(40), dp(320));
            if (pageWidth <= 0) {
                pageWidth = size[0];
            }
            if (pageHeight <= 0) {
                pageHeight = size[1];
            }
        }
    }

    /**
     * Draws the ruling for one page.
     *
     * <p>Spacing is derived from the page height rather than fixed in dp, so an A4
     * landscape page does not end up with denser lines than a portrait one.
     */
    private void drawPaperRuling(Canvas canvas, float top, float preferredGap) {
        if (pdfBackground != null && pageIndexForWorldY(top) < pdfPageCount) return;
        if (pageStyle.paper == PageStyle.Paper.BLANK) {
            return;
        }
        float gap = rulingGap(preferredGap);
        switch (pageStyle.paper) {
            case GRID:
                for (float y = top + gap; y < top + pageHeight; y += gap) {
                    canvas.drawLine(0, y, pageWidth, y, guidePaint);
                }
                for (float x = gap; x < pageWidth; x += gap) {
                    canvas.drawLine(x, top, x, top + pageHeight, guidePaint);
                }
                break;
            case DOTTED:
                float radius = Math.max(dp(0.7f), gap * 0.035f);
                for (float y = top + gap; y < top + pageHeight; y += gap) {
                    for (float x = gap; x < pageWidth; x += gap) {
                        canvas.drawCircle(x, y, radius, dotGuidePaint);
                    }
                }
                break;
            default:
                for (float y = top + gap; y < top + pageHeight; y += gap) {
                    canvas.drawLine(0, y, pageWidth, y, guidePaint);
                }
                break;
        }
    }

    /** Ruling spacing: proportional to page height, bounded to stay legible. */
    private float rulingGap(float preferredGap) {
        if (pageHeight <= 0) {
            return preferredGap;
        }
        float proportional = pageHeight / 26f;
        return clamp(proportional, dp(18), dp(44));
    }

    /** Paper style for this note; fixed at creation. */
    PageStyle getPageStyle() {
        return pageStyle;
    }

    /**
     * Applies a style to a note that has no content yet.
     *
     * <p>Only safe before anything is drawn: strokes live in world coordinates and
     * do not reflow, so changing the page ratio afterwards would shift every stroke
     * relative to its page while text flows re-paginated independently.
     */
    void applyPageStyleForNewNote(PageStyle style) {
        if (style == null) {
            return;
        }
        pageStyle = style;
        pageWidth = 0;
        pageHeight = 0;
        ensurePageGeometry();
        rebuildInkBitmap();
        rebuildAllFlows();
        dispatchTextBoxesChanged();
        invalidate();
    }

    private void initializeViewportIfReady(boolean forceFit) {
        if (viewportInitialized || getWidth() <= 0 || getHeight() <= 0 ||
                pageWidth <= 0 || pageHeight <= 0) {
            return;
        }
        if (restoredViewportZoom > 0) {
            viewportScale = clamp(fittedPageScale() * restoredViewportZoom,
                    MIN_VIEWPORT_SCALE, MAX_VIEWPORT_SCALE);
        }
        if (restoreViewportCenter && !forceFit) {
            viewportPanX = getWidth() / 2f - restoredViewportCenterX * viewportScale;
            viewportPanY = getHeight() / 2f - restoredViewportCenterY * viewportScale;
        } else {
            viewportScale = fittedPageScale();
            viewportPanX = (getWidth() - pageWidth * viewportScale) / 2f;
            viewportPanY = (getHeight() - pageHeight * viewportScale) / 2f;
        }
        restoreViewportCenter = false;
        restoredViewportZoom = 0;
        viewportInitialized = true;
        clampViewport(false);
    }

    private void addPageInternal(boolean addToHistory, boolean navigateToNewPage) {
        ensurePageGeometry();
        if (pageCount >= 500) {
            dispatchViewportState("单本笔记最多 500 页");
            return;
        }
        if (addToHistory) {
            pushUndoSnapshot();
        }
        pageCount += 1;
        bottomPullDistance = 0;
        if (navigateToNewPage) {
            float targetTop = pageTop(pageCount - 1);
            viewportPanY = dp(18) - targetTop * viewportScale;
            navigationRawPanY = viewportPanY;
            clampViewport(false);
        }
        invalidate();
        dispatchStats(0, "已添加第 " + pageCount + " 页");
        dispatchViewportState("已添加第 " + pageCount + " 页");
        notifyDocumentChanged();
    }

    /** Removes a page and its content, shifting later pages upward. */
    boolean deletePage(int pageIndex) {
        ensurePageGeometry();
        if (pageCount <= 1 || pageIndex < 0 || pageIndex >= pageCount) return false;
        pushUndoSnapshot();
        float stride = pageHeight + pageGap;
        for (int i = strokes.size() - 1; i >= 0; i--) {
            InkStroke stroke = strokes.get(i);
            int strokePage = pageIndexForWorldY(stroke.points.isEmpty() ? 0 : stroke.points.get(0).y);
            if (strokePage == pageIndex) strokes.remove(i);
            else if (strokePage > pageIndex) stroke.translate(0, -stride);
        }
        List<String> removeFlows = new ArrayList<>();
        for (TextFlow flow : textFlows.values()) {
            if (flow.anchorPageIndex == pageIndex) removeFlows.add(flow.id);
            else if (flow.anchorPageIndex > pageIndex) flow.anchorPageIndex -= 1;
        }
        for (int i = images.size() - 1; i >= 0; i--) {
            NoteImage image = images.get(i);
            if (image.page == pageIndex) images.remove(i);
            else if (image.page > pageIndex) image.page -= 1;
        }
        for (String id : removeFlows) textFlows.remove(id);
        pageCount -= 1;
        selectedStrokes.clear();
        selectedTextBoxId = null;
        inkCacheDirty = true;
        clampViewport(false);
        invalidate();
        notifyDocumentChanged();
        dispatchViewportState("已删除第 " + (pageIndex + 1) + " 页");
        return true;
    }

    /** Inserts a copy immediately after the requested page. */
    boolean duplicatePage(int pageIndex) {
        ensurePageGeometry();
        if (pageIndex < 0 || pageIndex >= pageCount || pageCount >= 500) return false;
        pushUndoSnapshot();
        float stride = pageHeight + pageGap;
        for (InkStroke stroke : strokes) {
            int strokePage = pageIndexForWorldY(stroke.points.isEmpty() ? 0 : stroke.points.get(0).y);
            if (strokePage > pageIndex) stroke.translate(0, stride);
        }
        for (TextFlow flow : textFlows.values()) {
            if (flow.anchorPageIndex > pageIndex) flow.anchorPageIndex += 1;
        }
        for (NoteImage image : images) if (image.page > pageIndex) image.page += 1;
        List<InkStroke> copies = new ArrayList<>();
        for (InkStroke stroke : strokes) {
            int strokePage = pageIndexForWorldY(stroke.points.isEmpty() ? 0 : stroke.points.get(0).y);
            if (strokePage == pageIndex) {
                InkStroke copy = stroke.copyWithId(UUID.randomUUID().toString());
                copy.translate(0, stride);
                copies.add(copy);
            }
        }
        strokes.addAll(copies);
        List<TextFlow> flowCopies = new ArrayList<>();
        for (TextFlow flow : textFlows.values()) {
            if (flow.anchorPageIndex == pageIndex) {
                TextFlow copy = flow.copy();
                copy.anchorPageIndex = pageIndex + 1;
                flowCopies.add(copy);
            }
        }
        for (TextFlow copy : flowCopies) {
            TextFlow unique = new TextFlow(UUID.randomUUID().toString(), copy.format, copy.source,
                    copy.fontSizeSp, copy.lineHeight, copy.width, copy.anchorPageIndex,
                    copy.anchorXInPage, copy.anchorYInPage);
            textFlows.put(unique.id, unique);
        }
        pageCount += 1;
        inkCacheDirty = true;
        invalidate();
        notifyDocumentChanged();
        dispatchViewportState("已复制第 " + (pageIndex + 1) + " 页");
        return true;
    }

    /** Reorders a page while preserving all page-relative content. */
    boolean movePage(int fromPage, int toPage) {
        ensurePageGeometry();
        if (fromPage < 0 || toPage < 0 || fromPage >= pageCount || toPage >= pageCount
                || fromPage == toPage) return false;
        pushUndoSnapshot();
        int[] mapping = new int[pageCount];
        for (int old = 0; old < pageCount; old++) {
            if (old == fromPage) mapping[old] = toPage;
            else if (fromPage < toPage && old > fromPage && old <= toPage) mapping[old] = old - 1;
            else if (toPage < fromPage && old >= toPage && old < fromPage) mapping[old] = old + 1;
            else mapping[old] = old;
        }
        float stride = pageHeight + pageGap;
        for (InkStroke stroke : strokes) {
            int oldPage = pageIndexForWorldY(stroke.points.isEmpty() ? 0 : stroke.points.get(0).y);
            int newPage = mapping[Math.max(0, Math.min(pageCount - 1, oldPage))];
            stroke.translate(0, (newPage - oldPage) * stride);
        }
        for (TextFlow flow : textFlows.values()) {
            flow.anchorPageIndex = mapping[Math.max(0, Math.min(pageCount - 1, flow.anchorPageIndex))];
        }
        for (NoteImage image : images) image.page = mapping[Math.max(0,
                Math.min(pageCount - 1, image.page))];
        inkCacheDirty = true;
        invalidate();
        notifyDocumentChanged();
        dispatchViewportState("已移动页面");
        return true;
    }

    private float fittedPageScale() {
        if (getWidth() <= 0 || getHeight() <= 0 || pageWidth <= 0 || pageHeight <= 0) {
            return 1f;
        }
        float margin = dp(18);
        float fitX = (getWidth() - margin * 2f) / pageWidth;
        float fitY = (getHeight() - margin * 2f) / pageHeight;
        return clamp(Math.min(1f, Math.min(fitX, fitY)),
                MIN_VIEWPORT_SCALE, MAX_VIEWPORT_SCALE);
    }

    private float pageTop(int pageIndex) {
        return Math.max(0, pageIndex) * (pageHeight + pageGap);
    }

    private float totalDocumentHeight() {
        return pageCount * pageHeight + Math.max(0, pageCount - 1) * pageGap;
    }

    private int pageIndexForWorldY(float worldY) {
        if (pageHeight <= 0) {
            return 0;
        }
        int index = (int) Math.floor(Math.max(0, worldY) / (pageHeight + pageGap));
        return Math.max(0, Math.min(pageCount - 1, index));
    }

    private boolean pointInsideAnyPage(float worldX, float worldY) {
        if (worldX < 0 || worldX > pageWidth || worldY < 0) {
            return false;
        }
        int index = pageIndexForWorldY(worldY);
        float localY = worldY - pageTop(index);
        return index >= 0 && index < pageCount && localY >= 0 && localY <= pageHeight;
    }

    private int currentPageIndex() {
        if (getHeight() <= 0) {
            return 0;
        }
        return pageIndexForWorldY(screenToWorldY(getHeight() / 2f));
    }

    private float screenToWorldX(float screenX) {
        return (screenX - viewportPanX) / Math.max(0.001f, viewportScale);
    }

    private float screenToWorldY(float screenY) {
        return (screenY - viewportPanY) / Math.max(0.001f, viewportScale);
    }

    private void clampViewport(boolean retainBottomPull) {
        if (getWidth() <= 0 || getHeight() <= 0 || pageWidth <= 0 || pageHeight <= 0) {
            return;
        }
        clampViewportX();
        float minimum = minimumViewportPanY();
        float maximum = maximumViewportPanY();
        if (minimum > maximum) {
            viewportPanY = (getHeight() - totalDocumentHeight() * viewportScale) / 2f;
        } else if (!retainBottomPull || bottomPullDistance <= 0) {
            viewportPanY = clamp(viewportPanY, minimum, maximum);
        }
        navigationRawPanY = viewportPanY;
    }

    private void clampViewportX() {
        if (getWidth() <= 0 || pageWidth <= 0) {
            return;
        }
        float margin = dp(18);
        float scaledWidth = pageWidth * viewportScale;
        if (scaledWidth + margin * 2f <= getWidth()) {
            viewportPanX = (getWidth() - scaledWidth) / 2f;
            return;
        }
        float minimum = getWidth() - scaledWidth - margin;
        viewportPanX = clamp(viewportPanX, minimum, margin);
    }

    private float minimumViewportPanY() {
        float margin = dp(18);
        float scaledHeight = totalDocumentHeight() * viewportScale;
        if (scaledHeight + margin * 2f <= getHeight()) {
            return (getHeight() - scaledHeight) / 2f;
        }
        return getHeight() - scaledHeight - margin;
    }

    private float maximumViewportPanY() {
        float margin = dp(18);
        float scaledHeight = totalDocumentHeight() * viewportScale;
        if (scaledHeight + margin * 2f <= getHeight()) {
            return (getHeight() - scaledHeight) / 2f;
        }
        return margin;
    }

    private void applyNavigationPanY() {
        float minimum = minimumViewportPanY();
        float maximum = maximumViewportPanY();
        if (navigationRawPanY < minimum) {
            if (pageCount >= 500) {
                bottomPullDistance = 0;
                viewportPanY = minimum;
                dispatchViewportState("单本笔记最多 500 页");
                return;
            }
            float rawOverscroll = minimum - navigationRawPanY;
            float pageScreenHeight = Math.max(dp(240), pageHeight * viewportScale);
            float freeNavigationDistance = nextPageRevealStartDistance(minimum);
            float creationPull = Math.max(0, rawOverscroll - freeNavigationDistance);
            float resistedCreationPull = creationPull * 0.82f /
                    (1f + creationPull / (pageScreenHeight * 1.8f));
            float maximumPreviewTravel = freeNavigationDistance + pageScreenHeight + dp(96);
            // Moving through existing content and up to the next paper is 1:1. Rubber-band
            // resistance starts only after the provisional paper is actually being created.
            bottomPullDistance = Math.min(maximumPreviewTravel,
                    Math.min(rawOverscroll, freeNavigationDistance) + resistedCreationPull);
            viewportPanY = minimum - bottomPullDistance;
            return;
        }
        bottomPullDistance = 0;
        viewportPanY = Math.min(navigationRawPanY, maximum);
    }

    private boolean isNewPageCreationPullActive() {
        return pageCount < 500 && bottomPullDistance > 0 &&
                viewportPanY < minimumViewportPanY();
    }

    private float nextPageCommitFraction() {
        return 1f / 3f;
    }

    private float nextPageRevealStartDistance(float minimumPanY) {
        float previewTopAtDocumentBottom = pageTop(pageCount) * viewportScale + minimumPanY;
        return Math.max(0, previewTopAtDocumentBottom - getHeight());
    }

    private float nextPageRevealFraction() {
        if (bottomPullDistance <= 0 || pageHeight <= 0 || viewportScale <= 0) {
            return 0;
        }
        float previewTopOnScreen = pageTop(pageCount) * viewportScale + viewportPanY;
        float revealedPixels = clamp(getHeight() - previewTopOnScreen,
                0, pageHeight * viewportScale);
        return revealedPixels / Math.max(1f, pageHeight * viewportScale);
    }

    private float bottomPullCommitProgress() {
        return Math.min(1f, nextPageRevealFraction() / nextPageCommitFraction());
    }

    private String bottomPullStatus() {
        int revealPercent = Math.round(nextPageRevealFraction() * 100f);
        if (nextPageRevealFraction() >= nextPageCommitFraction()) {
            return "已拉出 " + revealPercent + "% · 松手添加第 " +
                    (pageCount + 1) + " 页";
        }
        return "下一页已拉出 " + revealPercent + "% · 拉到 33% 松手添加";
    }

    private void drawBottomPullIndicator(Canvas canvas) {
        if (bottomPullDistance <= 0 || nextPageRevealFraction() <= 0 ||
                getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        float progress = bottomPullCommitProgress();
        float width = Math.min(dp(190), getWidth() * 0.42f);
        float fromX = (getWidth() - width) / 2f;
        float toX = fromX + width;
        float lineY = getHeight() - dp(18);
        canvas.drawLine(fromX, lineY, toX, lineY, pullIndicatorTrackPaint);
        canvas.drawLine(fromX, lineY, fromX + width * progress, lineY, pullIndicatorPaint);
        pullTextPaint.setColor(progress >= 1f ? SELECTION_COLOR : Color.rgb(92, 107, 118));
        canvas.drawText(progress >= 1f ? "松手添加新页面" : "继续上滑添加下一页",
                getWidth() / 2f, lineY - dp(9), pullTextPaint);
    }

    private void animateViewportBackToDocument() {
        cancelViewportReturnAnimation();
        final float startPanY = viewportPanY;
        final float targetPanY = minimumViewportPanY();
        if (Math.abs(startPanY - targetPanY) < 0.5f) {
            viewportPanY = targetPanY;
            navigationRawPanY = targetPanY;
            bottomPullDistance = 0;
            invalidate();
            dispatchViewportState("手指拖动画布 · 双指缩放");
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(startPanY, targetPanY);
        viewportReturnAnimator = animator;
        animator.setDuration(240);
        animator.setInterpolator(new DecelerateInterpolator(1.45f));
        animator.addUpdateListener(valueAnimator -> {
            viewportPanY = (float) valueAnimator.getAnimatedValue();
            bottomPullDistance = Math.max(0, targetPanY - viewportPanY);
            navigationRawPanY = viewportPanY;
            invalidate();
            dispatchViewportState(bottomPullDistance > 0
                    ? bottomPullStatus() : "手指拖动画布 · 双指缩放");
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;

            @Override
            public void onAnimationCancel(Animator animation) {
                canceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (viewportReturnAnimator == animation) {
                    viewportReturnAnimator = null;
                }
                if (!canceled) {
                    viewportPanY = targetPanY;
                    navigationRawPanY = targetPanY;
                    bottomPullDistance = 0;
                    invalidate();
                    dispatchViewportState("手指拖动画布 · 双指缩放");
                }
            }
        });
        animator.start();
    }

    private void cancelViewportReturnAnimation() {
        if (viewportReturnAnimator != null) {
            ValueAnimator animator = viewportReturnAnimator;
            viewportReturnAnimator = null;
            animator.cancel();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelViewportReturnAnimation();
        if (pdfBackground != null) { pdfBackground.close(); pdfBackground = null; }
        super.onDetachedFromWindow();
    }

    private int countFingerPointers(MotionEvent event, int excludedPointerIndex) {
        int count = 0;
        for (int index = 0; index < event.getPointerCount(); index++) {
            if (index != excludedPointerIndex &&
                    event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER &&
                    !isLikelyPalm(event, index)) {
                count += 1;
            }
        }
        return count;
    }

    private float[] fingerFocus(MotionEvent event, int excludedPointerIndex) {
        float x = 0;
        float y = 0;
        int count = 0;
        for (int index = 0; index < event.getPointerCount(); index++) {
            if (index == excludedPointerIndex ||
                    event.getToolType(index) != MotionEvent.TOOL_TYPE_FINGER ||
                    isLikelyPalm(event, index)) {
                continue;
            }
            x += event.getX(index);
            y += event.getY(index);
            count += 1;
        }
        if (count == 0) {
            return new float[]{navigationLastFocusX, navigationLastFocusY};
        }
        return new float[]{x / count, y / count};
    }

    private float fingerSpan(MotionEvent event, int excludedPointerIndex) {
        int first = -1;
        int second = -1;
        for (int index = 0; index < event.getPointerCount(); index++) {
            if (index == excludedPointerIndex ||
                    event.getToolType(index) != MotionEvent.TOOL_TYPE_FINGER ||
                    isLikelyPalm(event, index)) {
                continue;
            }
            if (first < 0) {
                first = index;
            } else {
                second = index;
                break;
            }
        }
        if (first < 0 || second < 0) {
            return 0;
        }
        return (float) Math.sqrt(squaredDistance(
                event.getX(first), event.getY(first), event.getX(second), event.getY(second)));
    }

    private boolean isLikelyPalm(MotionEvent event, int pointerIndex) {
        float touchMajor = event.getTouchMajor(pointerIndex);
        return touchMajor > 0 && touchMajor > dp(54);
    }

    private float positiveFloat(double value) {
        float converted = (float) value;
        return Float.isFinite(converted) && converted > 0 ? converted : 0;
    }

    private float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private boolean acceptsInput(MotionEvent event, int pointerIndex) {
        int toolType = event.getToolType(pointerIndex);
        if (selectedTool == Tool.TEXT && toolType == MotionEvent.TOOL_TYPE_FINGER) {
            return !isLikelyPalm(event, pointerIndex);
        }
        if (!penOnly) {
            return true;
        }
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            return true;
        }
        if (toolType != MotionEvent.TOOL_TYPE_FINGER || selectedTool != Tool.LASSO ||
                !hasMovableSelection()) {
            return false;
        }
        return !isLikelyPalm(event, pointerIndex) && selectionBounds(true).contains(
                screenToWorldX(event.getX(pointerIndex)),
                screenToWorldY(event.getY(pointerIndex)));
    }

    private String toolLabel(int toolType) {
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
            return "手写笔";
        }
        if (toolType == MotionEvent.TOOL_TYPE_ERASER) {
            return "笔尾橡皮";
        }
        if (toolType == MotionEvent.TOOL_TYPE_FINGER) {
            return "手指";
        }
        if (toolType == MotionEvent.TOOL_TYPE_MOUSE) {
            return "鼠标";
        }
        return "未知输入";
    }

    private String toolStatus(Tool tool) {
        if (tool == Tool.HIGHLIGHTER) {
            return "高亮笔：半透明宽笔划";
        }
        if (tool == Tool.SHAPE) return "几何图形：拖动绘制矩形";
        if (tool == Tool.ERASER) {
            return "局部橡皮：只擦除覆盖区域";
        }
        if (tool == Tool.LASSO) {
            return "套索：圈选后可用笔或手指拖动";
        }
        if (tool == Tool.TEXT) {
            return "文字笔：点击页面位置插入文字对象";
        }
        return "画笔就绪";
    }

    private InkPoint pointFromEvent(MotionEvent event, int pointerIndex, boolean historical, int historyIndex) {
        if (historical) {
            return new InkPoint(
                    screenToWorldX(event.getHistoricalX(pointerIndex, historyIndex)),
                    screenToWorldY(event.getHistoricalY(pointerIndex, historyIndex)),
                    event.getHistoricalEventTime(historyIndex),
                    event.getHistoricalPressure(pointerIndex, historyIndex)
            );
        }
        return new InkPoint(
                screenToWorldX(event.getX(pointerIndex)),
                screenToWorldY(event.getY(pointerIndex)),
                event.getEventTime(),
                event.getPressure(pointerIndex)
        );
    }

    private float pressureWidth(InkPoint point, float baseWidth) {
        float pressure = point.pressure > 0 ? Math.min(point.pressure, 1f) : 0.5f;
        return baseWidth * (0.45f + pressure * 0.95f);
    }

    private void drawStrokeOnCanvas(Canvas canvas, Paint paint, InkStroke stroke) {
        if (stroke.points.isEmpty()) {
            return;
        }
        paint.setColor(stroke.color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        if (stroke.highlighter) {
            InkPoint first = stroke.points.get(0);
            highlighterPath.rewind();
            highlighterPath.moveTo(first.x, first.y);
            if (stroke.points.size() == 1) highlighterPath.lineTo(first.x + 0.01f, first.y);
            for (int index = 1; index < stroke.points.size(); index++) {
                InkPoint point = stroke.points.get(index);
                highlighterPath.lineTo(point.x, point.y);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke.baseWidth);
            canvas.drawPath(highlighterPath, paint);
            return;
        }
        if (stroke.points.size() == 1) {
            InkPoint point = stroke.points.get(0);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(point.x, point.y,
                    pressureWidth(point, stroke.baseWidth) / 2f, paint);
            return;
        }
        paint.setStyle(Paint.Style.STROKE);
        for (int index = 1; index < stroke.points.size(); index++) {
            InkPoint from = stroke.points.get(index - 1);
            InkPoint to = stroke.points.get(index);
            paint.setStrokeWidth((pressureWidth(from, stroke.baseWidth) +
                    pressureWidth(to, stroke.baseWidth)) / 2f);
            canvas.drawLine(from.x, from.y, to.x, to.y, paint);
        }
    }

    /** Marks the ink cache stale; it is rebuilt on the next frame. */
    private void rebuildInkBitmap() {
        inkCacheDirty = true;
        invalidate();
    }

    private void clearInkBitmap() {
        inkCacheDirty = true;
        if (inkCacheCanvas != null) {
            inkCacheCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        }
        invalidate();
    }

    /**
     * Redraws finished strokes into the offscreen cache.
     *
     * <p>Runs at most once per frame and only when something actually changed:
     * strokes added or removed, an erase, an undo, or a viewport move. The stroke
     * currently under the pen is excluded so it can be drawn live at full
     * responsiveness.
     */
    private void refreshInkCacheIfNeeded() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        boolean viewportMoved = inkCacheScale != viewportScale ||
                inkCachePanX != viewportPanX || inkCachePanY != viewportPanY;
        boolean sizeChanged = inkCache == null ||
                inkCache.getWidth() != width || inkCache.getHeight() != height;
        if (!inkCacheDirty && !viewportMoved && !sizeChanged) {
            return;
        }
        if (sizeChanged) {
            if (inkCache != null) {
                inkCache.recycle();
            }
            try {
                inkCache = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError exhausted) {
                // Fall back to drawing every stroke directly; slower but correct.
                inkCache = null;
                inkCacheCanvas = null;
                inkCacheDirty = false;
                return;
            }
            inkCacheCanvas = new Canvas(inkCache);
        }
        inkCacheCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        inkCacheCanvas.save();
        inkCacheCanvas.translate(viewportPanX, viewportPanY);
        inkCacheCanvas.scale(viewportScale, viewportScale);
        int firstVisiblePage = pageIndexForWorldY(screenToWorldY(0));
        int lastVisiblePage = pageIndexForWorldY(screenToWorldY(height));
        for (InkStroke stroke : strokes) {
            if (stroke == currentStroke || stroke.points.isEmpty()) {
                continue;
            }
            int strokePage = pageIndexForWorldY(stroke.points.get(0).y);
            if (strokePage < firstVisiblePage || strokePage > lastVisiblePage) {
                continue;
            }
            float strokePageTop = pageTop(strokePage);
            inkCacheCanvas.save();
            inkCacheCanvas.clipRect(0, strokePageTop, pageWidth, strokePageTop + pageHeight);
            drawStrokeOnCanvas(inkCacheCanvas, renderPaint, stroke);
            inkCacheCanvas.restore();
        }
        inkCacheCanvas.restore();
        inkCacheScale = viewportScale;
        inkCachePanX = viewportPanX;
        inkCachePanY = viewportPanY;
        inkCacheDirty = false;
    }

    private void pushUndoSnapshot() {
        if (undoTransactionDepth > 0) {
            // Inside a transaction the opening snapshot already captured the state
            // before the change; pushing more would make one logical action need
            // several undos to reverse.
            return;
        }
        pushBounded(undoStack, snapshotDocument());
        redoStack.clear();
        dispatchSelectionState();
    }

    /**
     * Groups every change until {@link #endUndoTransaction()} into one undo step.
     *
     * <p>Needed for model-driven edits: a single AI action may write more than one
     * flow, and a user pressing undo expects that whole action reversed rather than
     * one write peeled off at a time, leaving the note half-changed.
     */
    void beginUndoTransaction() {
        if (undoTransactionDepth == 0) {
            pushBounded(undoStack, snapshotDocument());
            redoStack.clear();
            dispatchSelectionState();
        }
        undoTransactionDepth += 1;
    }

    void endUndoTransaction() {
        undoTransactionDepth = Math.max(0, undoTransactionDepth - 1);
    }

    private void pushBounded(Deque<DocumentSnapshot> stack, DocumentSnapshot snapshot) {
        if (stack.size() >= MAX_HISTORY) {
            stack.removeLast();
        }
        stack.push(snapshot);
    }

    /**
     * Converts schemaVersion 1-4 text boxes into flows.
     *
     * <p>Those versions stored one entry per rendered fragment, each repeating the
     * whole unified source, with geometry in world coordinates. Fragments of one
     * flow are grouped by {@code flowId}; the lowest {@code flowIndex} supplies
     * the source and the anchor, whose world Y becomes a page-relative offset.
     * Anything derived is discarded and recomputed, so a note saved by an older
     * build also picks up later paginator fixes.
     */
    private void migrateLegacyTextBoxes(JSONArray textBoxArray) throws JSONException {
        if (textBoxArray == null) {
            return;
        }
        Map<String, JSONObject> heads = new LinkedHashMap<>();
        for (int index = 0; index < textBoxArray.length(); index++) {
            JSONObject entry = textBoxArray.getJSONObject(index);
            String id = entry.optString("id", "").trim();
            if (id.isEmpty()) {
                continue;
            }
            String flowId = entry.optString("flowId", id).trim();
            if (flowId.isEmpty()) {
                flowId = id;
            }
            JSONObject known = heads.get(flowId);
            if (known == null ||
                    entry.optInt("flowIndex", 0) < known.optInt("flowIndex", 0)) {
                heads.put(flowId, entry);
            }
        }
        for (Map.Entry<String, JSONObject> entry : heads.entrySet()) {
            JSONObject head = entry.getValue();
            String source = head.optString("source", "");
            if (source.length() > TextFlow.MAX_SOURCE_LENGTH) {
                throw new JSONException("Text flow source is too large");
            }
            float worldY = (float) head.optDouble("y", 0);
            float worldX = (float) head.optDouble("x", 0);
            if (Double.isNaN(worldY) || Double.isInfinite(worldY) ||
                    Double.isNaN(worldX) || Double.isInfinite(worldX)) {
                throw new JSONException("Text box geometry must be finite");
            }
            int pageIndex = 0;
            float yInPage = worldY;
            if (pageHeight > 0) {
                float stride = pageHeight + Math.max(0f, pageGap);
                if (stride > 0) {
                    pageIndex = Math.max(0, Math.min(499, (int) (worldY / stride)));
                    yInPage = worldY - pageIndex * stride;
                }
            }
            textFlows.put(entry.getKey(), new TextFlow(
                    entry.getKey(),
                    NoteTextBox.Format.fromStorage(head.optString("format", "latex")),
                    source,
                    TextFlow.clampFontSize((float) head.optDouble("fontSizeSp", 16)),
                    // Pre-0.10.1 notes had no stored leading; they pick up the
                    // tighter default, which is also what the estimator now assumes.
                    TextFlow.DEFAULT_LINE_HEIGHT,
                    Math.max(80f, (float) head.optDouble("width", 360)),
                    pageIndex, worldX, yInPage));
        }
    }

    /** Recomputes fragments for every flow, e.g. after load or a size change. */
    private void rebuildAllFlows() {
        textBoxes.clear();
        for (TextFlow flow : textFlows.values()) {
            reflowFlow(flow);
        }
    }

    private DocumentSnapshot snapshotDocument() {
        // Completed strokes are immutable except while moving a selection. That
        // path copies the selected strokes before translating them, so undo can
        // share every untouched stroke instead of cloning every sampled point on
        // each pen-down. Restore still copies the snapshot before making it live.
        DocumentSnapshot snapshot = new DocumentSnapshot(new ArrayList<>(strokes), copyFlows(), pageCount);
        for (NoteImage image : images) snapshot.images.add(image.copy(false));
        return snapshot;
    }

    private List<TextFlow> copyFlows() {
        List<TextFlow> copy = new ArrayList<>();
        for (TextFlow flow : textFlows.values()) {
            copy.add(flow.copy());
        }
        return copy;
    }

    /** Replaces all flows and rebuilds every fragment from them. */
    private void replaceFlows(List<TextFlow> replacement) {
        textFlows.clear();
        textBoxes.clear();
        if (replacement == null) {
            return;
        }
        for (TextFlow flow : replacement) {
            textFlows.put(flow.id, flow.copy());
        }
        for (TextFlow flow : textFlows.values()) {
            reflowFlow(flow);
        }
    }

    private List<InkStroke> copyStrokes(List<InkStroke> source) {
        List<InkStroke> copy = new ArrayList<>();
        for (InkStroke stroke : source) {
            copy.add(stroke.copy());
        }
        return copy;
    }

    private List<NoteTextBox> copyTextBoxes(List<NoteTextBox> source) {
        List<NoteTextBox> copy = new ArrayList<>();
        for (NoteTextBox textBox : source) {
            copy.add(textBox.copy());
        }
        return copy;
    }

    private NoteTextBox findTextBox(String id) {
        if (id == null) {
            return null;
        }
        for (NoteTextBox textBox : textBoxes) {
            if (id.equals(textBox.id)) {
                return textBox;
            }
        }
        return null;
    }

    private List<NoteTextBox> findTextFlow(String flowId) {
        List<NoteTextBox> flow = new ArrayList<>();
        if (flowId == null) {
            return flow;
        }
        for (NoteTextBox textBox : textBoxes) {
            if (flowId.equals(textBox.flowId)) {
                flow.add(textBox);
            }
        }
        flow.sort((first, second) -> Integer.compare(first.flowIndex, second.flowIndex));
        return flow;
    }

    private void restoreSnapshot(DocumentSnapshot replacement) {
        strokes.clear();
        strokes.addAll(copyStrokes(replacement.strokes));
        pageCount = Math.max(1, replacement.pageCount);
        images.clear();
        for (NoteImage image : replacement.images) images.add(image.copy(false));
        replaceFlows(replacement.flows);
        recountPoints();
        // Invalidated here rather than trusting every caller to remember:
        // restoring replaces all strokes, so a stale cache would keep painting ink
        // the document no longer contains.
        inkCacheDirty = true;
    }

    private void recountPoints() {
        pointCount = 0;
        for (InkStroke stroke : strokes) {
            pointCount += stroke.points.size();
        }
    }

    private void clearSelectionInternal() {
        selectedStrokes.clear();
        selectedImages.clear();
        resizingImage = null;
        lassoPoints.clear();
        selectionMaskPoints.clear();
        selectedTextBoxId = null;
    }

    private void dispatchStats(float pressure, String inputStatus) {
        if (listener != null) {
            listener.onInkStatsChanged(strokes.size(), pointCount, pressure, inputStatus);
        }
    }

    private void dispatchSelectionState() {
        if (listener != null) {
            listener.onSelectionChanged(getSelectionCount(), canUndo(), canRedo());
            listener.onTextBoxSelectionChanged(selectedTextBoxId);
        }
    }

    private void dispatchViewportState(String status) {
        if (listener != null) {
            listener.onViewportChanged(getCurrentPage(), pageCount, getZoomPercent(), status);
        }
    }

    private void dispatchTextBoxesChanged() {
        if (listener != null) {
            listener.onTextBoxesChanged(copyTextBoxes(textBoxes));
        }
    }

    private void notifyDocumentChanged() {
        if (listener != null) {
            listener.onDocumentChanged();
        }
    }

    private float dp(float value) {
        if (cachedDensity <= 0f) {
            cachedDensity = getResources().getDisplayMetrics().density;
        }
        return value * cachedDensity;
    }

    private float densityScale() {
        return Math.max(0.1f, dp(1f));
    }

    /**
     * Exposes the canvas to model tools through a deliberately narrow surface.
     *
     * <p>Handwriting is readable (as clustered bounding boxes) but never writable:
     * strokes carry spatial relationships the user drew by hand, and without
     * recognition a model has no basis for deciding where they belong.
     *
     * @param taskSelection selection this task refers to, captured when the
     *                      request was sent so later edits cannot move the anchor
     *                      midway through a task
     */
    NoteToolContext createToolContext(RectF taskSelection) {
        return new CanvasToolContext(taskSelection);
    }

    private final class CanvasToolContext implements NoteToolContext {
        private final RectF selection;

        CanvasToolContext(RectF selection) {
            this.selection = selection == null ? null : new RectF(selection);
        }

        @Override
        public JSONObject readPageMap(int focusPageIndex, boolean fullDetail) {
            ensurePageGeometry();
            try {
                return PageMap.build(strokes, textBoxes, copyFlows(), pageCount,
                        pageWidth, pageHeight, pageGap, densityScale(),
                        focusPageIndex, selection, fullDetail);
            } catch (JSONException failure) {
                return new JSONObject();
            }
        }

        @Override
        public PlacementResolver.Placement resolvePlacement(JSONObject placement) {
            ensurePageGeometry();
            PlacementResolver resolver = new PlacementResolver(pageWidth, pageHeight,
                    pageGap, densityScale(), pageCount);
            try {
                PlacementResolver.Placement resolved = resolver.resolve(placement, new PlacementResolver.AnchorLookup() {
                    @Override
                    public RectF boundsOf(String name) {
                        return resolveAnchorBounds(name, selection);
                    }

                    @Override
                    public RectF freeRegionOf(int pageIndex, String slot) {
                        return largestFreeRegion(pageIndex);
                    }
                });
                if (resolved.pageIndex < pdfPageCount) {
                    if (pageCount >= 500) throw new PlacementResolver.Failure("笔记已达 500 页，请在已有空白页放置解释");
                    return new PlacementResolver.Placement(pageCount, dp(16), dp(16), resolved.width,
                            "PDF 原文不覆盖；已改为第 " + (pageCount + 1) + " 页空白附注页");
                }
                return resolved;
            } catch (JSONException failure) {
                throw new PlacementResolver.Failure("placement 解析失败");
            }
        }

        @Override
        public int selectionPageIndex() {
            return selection == null ? -1
                    : clampPageIndex(pageIndexForWorldY(selection.centerY()));
        }

        @Override
        public RectF selectionBounds() {
            return selection == null ? null : new RectF(selection);
        }

        @Override
        public int pageCount() {
            return pageCount;
        }

        @Override
        public JSONObject createTextFlow(String content, NoteTextBox.Format format,
                                         PlacementResolver.Placement placement) {
            if (activePointerId != MotionEvent.INVALID_POINTER_ID || navigationGesture) {
                return null;
            }
            ensurePageGeometry();
            if (format == NoteTextBox.Format.MARKDOWN && content.trim().startsWith("```mermaid\n")) {
                // Only automatic insertion avoids obstacles. A later manual drag
                // remains under the user's control, just like other note objects.
                float margin = dp(16);
                float width = clamp(placement.width, dp(180), pageWidth - margin * 2f);
                float x = clamp(placement.xInPage, margin, pageWidth - width - margin);
                float height = Math.max(dp(72), dp(20) + estimateTextBlockHeight(format,
                        content, width, 16f, TextFlow.DEFAULT_LINE_HEIGHT, null));
                List<RectF> obstacles = new ArrayList<>();
                for (InkStroke stroke : strokes) {
                    if (!stroke.points.isEmpty()) obstacles.add(strokeBounds(stroke, true));
                }
                for (NoteTextBox box : textBoxes) {
                    obstacles.add(new RectF(box.x, box.y, box.x + box.width, box.y + box.height));
                }
                int page = placement.pageIndex;
                float localY = Math.max(margin, placement.yInPage);
                boolean found = false;
                while (page < 500) {
                    if (localY + height > pageHeight - margin) {
                        page++;
                        localY = margin;
                        continue;
                    }
                    float y = pageTop(page) + localY;
                    RectF candidate = new RectF(x, y, x + width, y + height);
                    float nextY = y;
                    for (RectF obstacle : obstacles) {
                        if (RectF.intersects(candidate, obstacle)) {
                            nextY = Math.max(nextY, obstacle.bottom + dp(12));
                        }
                    }
                    if (nextY == y) {
                        found = true;
                        break;
                    }
                    localY = nextY - pageTop(page);
                }
                if (!found) throw new PlacementResolver.Failure("没有可容纳整张示意图的空间，请缩小宽度或精简图表");
                placement = new PlacementResolver.Placement(page, x, localY, width,
                        placement.interpretation + "；避让后放在第 " + (page + 1) + " 页空白处");
            }
            int pagesBefore = pageCount;
            Map<String, Integer> anchorsBefore = flowAnchorPages();
            pushUndoSnapshot();
            TextFlow flow = new TextFlow(
                    "flow-ai-" + UUID.randomUUID().toString().replace("-", ""),
                    format, content, 16f, TextFlow.DEFAULT_LINE_HEIGHT,
                    placement.width, placement.pageIndex, placement.xInPage,
                    placement.yInPage);
            textFlows.put(flow.id, flow);
            reflowFlow(flow);
            dispatchTextBoxesChanged();
            dispatchSelectionState();
            notifyDocumentChanged();
            JSONObject report = landingReport(flow.id, pagesBefore, anchorsBefore);
            try {
                report.put("interpretedPlacement", placement.interpretation);
            } catch (JSONException ignored) {
                // The measured part of the report still stands.
            }
            return report;
        }

        @Override
        public JSONObject styleTextFlow(String flowId, Float fontSizeSp, Float lineHeight,
                                        Float width) {
            TextFlow flow = textFlows.get(flowId);
            if (flow == null) {
                return null;
            }
            ensurePageGeometry();
            int pagesBefore = pageCount;
            Map<String, Integer> anchorsBefore = flowAnchorPages();
            pushUndoSnapshot();
            if (fontSizeSp != null) {
                flow.fontSizeSp = TextFlow.clampFontSize(fontSizeSp);
            }
            if (lineHeight != null) {
                flow.lineHeight = TextFlow.clampLineHeight(lineHeight);
            }
            if (fontSizeSp != null || lineHeight != null || width != null) {
                flow.heightCorrection = 1f;
                flow.heightCorrectionPasses = 0;
            }
            if (width != null) {
                flow.width = clamp(width * densityScale(), dp(180),
                        Math.max(dp(180), pageWidth - dp(32)));
            }
            reflowFlow(flow);
            dispatchTextBoxesChanged();
            dispatchSelectionState();
            notifyDocumentChanged();
            return landingReport(flowId, pagesBefore, anchorsBefore);
        }

        @Override
        public JSONObject moveTextFlow(String flowId,
                                       PlacementResolver.Placement placement) {
            TextFlow flow = textFlows.get(flowId);
            if (flow == null) {
                return null;
            }
            ensurePageGeometry();
            int pagesBefore = pageCount;
            Map<String, Integer> anchorsBefore = flowAnchorPages();
            pushUndoSnapshot();
            flow.anchorPageIndex = clampPageIndex(placement.pageIndex);
            flow.anchorXInPage = placement.xInPage;
            flow.anchorYInPage = placement.yInPage;
            if (flow.anchorPageIndex >= pageCount) {
                pageCount = flow.anchorPageIndex + 1;
            }
            reflowFlow(flow);
            dispatchTextBoxesChanged();
            dispatchSelectionState();
            notifyDocumentChanged();
            return landingReport(flowId, pagesBefore, anchorsBefore);
        }

        @Override
        public float measureContentHeight(String content, NoteTextBox.Format format,
                                          float widthDp, float fontSizeSp,
                                          float lineHeight) {
            ensurePageGeometry();
            TextFlow probe = new TextFlow("probe", format, content, fontSizeSp,
                    lineHeight, widthDp * densityScale(), 0, dp(16), dp(16));
            int savedPageCount = pageCount;
            float total = 0f;
            for (TextFragmentLayout layout : layoutFlow(probe)) {
                total += layout.height;
            }
            pageCount = savedPageCount;
            return total / densityScale();
        }

        @Override
        public float pageWidthDp() {
            ensurePageGeometry();
            return pageWidth / densityScale();
        }

        @Override
        public float pageHeightDp() {
            ensurePageGeometry();
            return pageHeight / densityScale();
        }
    }

    /**
     * Corrects a fragment whose real height exceeded the estimate.
     *
     * <p>The paginator sizes fragments from {@code estimateTextBlockHeight}, which
     * is calibrated but cannot be exact: a dense matrix, an unbreakable token or
     * unusual device font metrics can all render taller than predicted, and the
     * fragment then clips its own tail with no indication. The rendered WebView
     * reports what it actually needed, and the flow records a correction factor so
     * the next layout reserves enough room.
     *
     * <p>Corrections only ever grow the reservation. Shrinking on a low measurement
     * would let the factor oscillate between two layouts that each look wrong.
     */
    void applyMeasuredFragmentHeight(String fragmentId, float measuredHeightDp) {
        NoteTextBox fragment = findTextBox(fragmentId);
        if (fragment == null) {
            return;
        }
        TextFlow flow = textFlows.get(fragment.flowId);
        if (flow == null) {
            return;
        }
        float measured = dp(measuredHeightDp);
        float reserved = fragment.height;
        if (measured <= reserved * MEASURED_HEIGHT_TOLERANCE) {
            return;
        }
        if (flow.heightCorrectionPasses >= MAX_HEIGHT_CORRECTION_PASSES) {
            // Give up rather than reflow forever; the safety factor still applies.
            return;
        }
        float needed = measured / Math.max(1f, reserved);
        float correction = Math.min(MAX_HEIGHT_CORRECTION,
                flow.heightCorrection * Math.max(1.02f, needed));
        if (Math.abs(correction - flow.heightCorrection) < 0.01f) {
            return;
        }
        flow.heightCorrection = correction;
        flow.heightCorrectionPasses += 1;
        reflowFlow(flow);
        dispatchTextBoxesChanged();
        dispatchStats(0, "已按实际渲染高度修正排版");
    }

    /** Anchor page of every flow, for detecting what a write pushed around. */
    private Map<String, Integer> flowAnchorPages() {
        Map<String, Integer> anchors = new LinkedHashMap<>();
        for (NoteTextBox fragment : textBoxes) {
            Integer known = anchors.get(fragment.flowId);
            if (known == null || fragment.pageIndex < known) {
                anchors.put(fragment.flowId, fragment.pageIndex);
            }
        }
        return anchors;
    }

    /**
     * Reports what a write actually did.
     *
     * <p>This is the half of the loop that lets the model stop guessing. Instead of
     * predicting whether a page will overflow, it writes and then reads back which
     * pages were used, whether a page was created, and which existing flows moved.
     */
    private JSONObject landingReport(String flowId, int pagesBefore,
                                     Map<String, Integer> anchorsBefore) {
        JSONObject report = new JSONObject();
        try {
            report.put("flowId", flowId);
            JSONArray occupied = new JSONArray();
            int fragmentCount = 0;
            for (NoteTextBox fragment : findTextFlow(flowId)) {
                fragmentCount += 1;
                int pageNumber = fragment.pageIndex + 1;
                boolean seen = false;
                for (int index = 0; index < occupied.length(); index++) {
                    if (occupied.optInt(index) == pageNumber) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) {
                    occupied.put(pageNumber);
                }
            }
            report.put("occupiedPages", occupied);
            report.put("fragmentCount", fragmentCount);
            report.put("addedPage", pageCount > pagesBefore);
            report.put("pageCount", pageCount);
            // Existing flows whose starting page moved are content the user
            // arranged, so the model is told and can decide whether to react.
            JSONArray shifted = new JSONArray();
            Map<String, Integer> anchorsAfter = flowAnchorPages();
            for (Map.Entry<String, Integer> entry : anchorsBefore.entrySet()) {
                if (entry.getKey().equals(flowId)) {
                    continue;
                }
                Integer after = anchorsAfter.get(entry.getKey());
                if (after != null && !after.equals(entry.getValue())) {
                    shifted.put(new JSONObject()
                            .put("flowId", entry.getKey())
                            .put("fromPage", entry.getValue() + 1)
                            .put("toPage", after + 1));
                }
            }
            report.put("shiftedFlows", shifted);
        } catch (JSONException ignored) {
            // A partial report beats none.
        }
        return report;
    }

    /** Resolves the anchor names a model may reference in a placement. */
    private RectF resolveAnchorBounds(String name, RectF selection) {
        if (name == null) {
            return null;
        }
        if ("selection".equals(name)) {
            return selection;
        }
        List<NoteTextBox> flow = findTextFlow(name);
        if (!flow.isEmpty()) {
            NoteTextBox last = flow.get(flow.size() - 1);
            return new RectF(last.x, last.y, last.x + last.width, last.y + last.height);
        }
        return inkClusterBounds(name);
    }

    /**
     * Finds an ink cluster by the id {@link PageMap} generated. Recomputed rather
     * than cached, because ids are positional and the map is rebuilt per request.
     */
    private RectF inkClusterBounds(String clusterId) {
        try {
            JSONObject map = PageMap.build(strokes, textBoxes, copyFlows(), pageCount,
                    pageWidth, pageHeight, pageGap, densityScale(), 0, null, true);
            JSONArray pages = map.optJSONArray("pages");
            if (pages == null) {
                return null;
            }
            for (int index = 0; index < pages.length(); index++) {
                JSONObject page = pages.getJSONObject(index);
                JSONArray clusters = page.optJSONArray("inkClusters");
                if (clusters == null) {
                    continue;
                }
                for (int inner = 0; inner < clusters.length(); inner++) {
                    JSONObject cluster = clusters.getJSONObject(inner);
                    if (clusterId.equals(cluster.optString("clusterId"))) {
                        return bandsToBounds(page.optInt("pageIndex"),
                                cluster.optString("bands"));
                    }
                }
            }
        } catch (JSONException ignored) {
            // Falls through to an unknown anchor.
        }
        return null;
    }

    private RectF bandsToBounds(int pageIndex, String bands) {
        if (bands == null || bands.isEmpty()) {
            return null;
        }
        String[] parts = bands.split("-");
        try {
            int first = Integer.parseInt(parts[0].trim()) - 1;
            int last = parts.length > 1 ? Integer.parseInt(parts[1].trim()) - 1 : first;
            float bandHeight = pageHeight / PageMap.BAND_COUNT;
            float top = pageTop(pageIndex) + first * bandHeight;
            return new RectF(dp(16), top, pageWidth - dp(16),
                    top + (last - first + 1) * bandHeight);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /** Largest run of unoccupied bands on a page, as world bounds. */
    private RectF largestFreeRegion(int pageIndex) {
        ensurePageGeometry();
        boolean[] occupied = new boolean[PageMap.BAND_COUNT];
        float bandHeight = pageHeight / PageMap.BAND_COUNT;
        float top = pageTop(pageIndex);
        for (NoteTextBox fragment : textBoxes) {
            if (fragment.pageIndex == pageIndex) {
                markOccupiedBands(occupied, fragment.y - top,
                        fragment.y + fragment.height - top, bandHeight);
            }
        }
        for (InkStroke stroke : strokes) {
            RectF bounds = strokeWorldBounds(stroke);
            if (bounds == null || pageIndexForWorldY(bounds.centerY()) != pageIndex) {
                continue;
            }
            markOccupiedBands(occupied, bounds.top - top, bounds.bottom - top, bandHeight);
        }
        int bestStart = -1;
        int bestLength = 0;
        int start = -1;
        for (int index = 0; index <= PageMap.BAND_COUNT; index++) {
            boolean free = index < PageMap.BAND_COUNT && !occupied[index];
            if (free && start < 0) {
                start = index;
            } else if (!free && start >= 0) {
                if (index - start > bestLength) {
                    bestLength = index - start;
                    bestStart = start;
                }
                start = -1;
            }
        }
        if (bestStart < 0) {
            return null;
        }
        return new RectF(dp(16), top + bestStart * bandHeight, pageWidth - dp(16),
                top + (bestStart + bestLength) * bandHeight);
    }

    private void markOccupiedBands(boolean[] occupied, float topInPage,
                                   float bottomInPage, float bandHeight) {
        int first = (int) Math.floor(topInPage / Math.max(1f, bandHeight));
        int last = (int) Math.floor(bottomInPage / Math.max(1f, bandHeight));
        first = Math.max(0, Math.min(PageMap.BAND_COUNT - 1, first));
        last = Math.max(first, Math.min(PageMap.BAND_COUNT - 1, last));
        for (int index = first; index <= last; index++) {
            occupied[index] = true;
        }
    }

    private RectF strokeWorldBounds(InkStroke stroke) {
        if (stroke == null || stroke.points.isEmpty()) {
            return null;
        }
        RectF bounds = null;
        for (InkPoint point : stroke.points) {
            if (bounds == null) {
                bounds = new RectF(point.x, point.y, point.x, point.y);
            } else {
                bounds.union(point.x, point.y);
            }
        }
        return bounds;
    }
}
