package com.padnote.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** A page-anchored PPT-style text object with an editor embedded inside the object. */
@SuppressLint("ViewConstructor")
final class NoteTextBoxView extends FrameLayout {
    interface Listener {
        void onSelect(NoteTextBox textBox);

        void onEditRequested(NoteTextBox textBox);

        void onMove(NoteTextBox textBox, float worldX, float worldY);

        /**
         * Fired continuously while dragging so the canvas can show where the flow
         * would land. Must not mutate the document — only the release commits.
         */
        void onDragPreview(NoteTextBox textBox, float worldX, float worldY);

        /** Fired when a drag ends or is cancelled, to clear any preview. */
        void onDragPreviewEnded();

        void onResize(NoteTextBox textBox, float worldWidth, float worldHeight);

        /**
         * Corner handle released: fit the type to the box the user drew.
         *
         * <p>Distinct from {@link #onResize}, which keeps the type size and only
         * changes the column width.
         */
        void onScaleToArea(NoteTextBox textBox, float worldWidth, float worldHeight);

        void onCommit(NoteTextBox textBox, NoteTextBox.Format format, String source,
                      float fontSizeSp, float lineHeight);

        /**
         * Font size changed from the selected-state controls, without entering the
         * source editor. Kept separate from {@link #onCommit} so restyling does not
         * have to round-trip through an editing session.
         */
        void onFontSizeChanged(NoteTextBox textBox, float fontSizeSp);

        /**
         * Height the rendered content actually needed, in world dp.
         *
         * <p>Lets the paginator correct an estimate that came in low instead of
         * silently clipping the tail of a fragment.
         */
        void onMeasuredHeight(NoteTextBox textBox, float measuredHeightDp);

        void onCancel(NoteTextBox textBox);

        void onDelete(NoteTextBox textBox);
    }

    private static final int ACCENT = Color.rgb(40, 94, 168);
    private static final int INK = Color.rgb(23, 33, 43);
    private static final long DOUBLE_TAP_MS = 320L;
    private static final long LIVE_PREVIEW_DELAY_MS = 90L;

    private final Listener listener;
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Inner dot marking the corner handle apart from the edge handle. */
    private final Paint cornerHandleInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** True while the corner handle is scaling type to a target area. */
    private boolean scalingByArea;
    private final int touchSlop;
    private final CompiledTextWebView compiledView;
    private final LinearLayout editorPanel;
    private final LinearLayout editorToolbar;
    private final EditText sourceEditor;
    private final TextView latexButton;
    private final TextView markdownButton;
    private final TextView fontDecreaseButton;
    private final TextView fontIncreaseButton;
    private final TextView lineHeightDecreaseButton;
    private final TextView lineHeightIncreaseButton;
    private final TextView selectedDeleteButton;
    /** Restyle controls shown on selection, without entering the source editor. */
    private final LinearLayout selectedStyleBar;
    private final TextView selectedFontDecreaseButton;
    private final TextView selectedFontIncreaseButton;
    private final TextView selectedFontSizeLabel;
    private final Runnable renderDraftRunnable = this::renderDraftNow;

    private NoteTextBox model;
    private NoteTextBox.Format draftFormat = NoteTextBox.Format.LATEX;
    private String draftSource = "";
    private float draftFontSizeSp = 16f;
    private float draftLineHeight = TextFlow.DEFAULT_LINE_HEIGHT;
    private NoteTextBox.Format renderedFormat;
    private String renderedSource;
    private float renderedFontSizeSp = -1f;
    private float renderedLineHeight = -1f;
    /** Canvas scale the current render was produced at. */
    private float renderedScale = -1f;
    private float pendingRenderScale = 1f;
    /** Quiet period before re-rendering, so a pinch does not recompile per frame. */
    private static final long CRISP_RERENDER_DELAY_MS = 140L;
    private final Runnable crispRerenderRunnable = this::renderAtSettledScale;
    private float viewportScale = 1f;
    private float viewportPanX;
    private float viewportPanY;
    private boolean selected;
    private boolean inlineEditing;
    private boolean suppressSourceWatcher;
    private boolean resizing;
    /** True while a touch is being routed to selection chrome rather than a drag. */
    private boolean controlGesture;
    private boolean moved;
    private float downRawX;
    private float downRawY;
    private float startScreenX;
    private float startScreenY;
    private int startScreenWidth;
    private int startScreenHeight;
    private long lastTapTime;
    private float lastTapRawX;
    private float lastTapRawY;

    NoteTextBoxView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setWillNotDraw(false);
        setClipChildren(true);
        setClipToPadding(true);
        setFocusable(true);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.5f));
        borderPaint.setColor(ACCENT);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(ACCENT);
        cornerHandleInnerPaint.setStyle(Paint.Style.FILL);
        cornerHandleInnerPaint.setColor(Color.WHITE);

        compiledView = new CompiledTextWebView(context, NoteTextBox.Format.LATEX, "");
        compiledView.setHeightListener(heightDp -> {
            if (model == null || inlineEditing) {
                return;
            }
            // The render carries the canvas zoom, so divide it back out to get a
            // world-space height comparable with the paginator's estimate.
            float worldHeight = heightDp / Math.max(0.01f, renderedScale);
            listener.onMeasuredHeight(model.copy(), worldHeight);
        });
        addView(compiledView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        editorPanel = new LinearLayout(context);
        editorPanel.setOrientation(LinearLayout.VERTICAL);
        editorPanel.setVisibility(GONE);
        editorPanel.setBackground(roundedBackground(Color.rgb(253, 252, 248),
                Color.rgb(169, 188, 207), 8));

        editorToolbar = new LinearLayout(context);
        editorToolbar.setOrientation(LinearLayout.HORIZONTAL);
        editorToolbar.setGravity(Gravity.CENTER_VERTICAL);
        editorToolbar.setPadding(dp(6), dp(4), dp(6), dp(4));
        editorPanel.addView(editorToolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(43)));

        latexButton = actionButton("LaTeX", "使用 LaTeX 编译");
        markdownButton = actionButton("MD", "使用 Markdown 编译");
        editorToolbar.addView(latexButton, new LinearLayout.LayoutParams(dp(64), dp(35)));
        LinearLayout.LayoutParams mdParams = new LinearLayout.LayoutParams(dp(48), dp(35));
        mdParams.setMargins(dp(5), 0, 0, 0);
        editorToolbar.addView(markdownButton, mdParams);
        fontDecreaseButton = actionButton("A−", "减小文字字号");
        fontIncreaseButton = actionButton("A+", "增大文字字号");
        LinearLayout.LayoutParams decreaseParams = new LinearLayout.LayoutParams(dp(40), dp(35));
        decreaseParams.setMargins(dp(5), 0, 0, 0);
        editorToolbar.addView(fontDecreaseButton, decreaseParams);
        LinearLayout.LayoutParams increaseParams = new LinearLayout.LayoutParams(dp(40), dp(35));
        increaseParams.setMargins(dp(4), 0, 0, 0);
        editorToolbar.addView(fontIncreaseButton, increaseParams);
        // Line height lives only here, behind the double tap: it is a typographic
        // detail most edits never touch, so it should not compete for space with
        // the selected-state controls.
        lineHeightDecreaseButton = actionButton("⇱", "减小行距");
        lineHeightIncreaseButton = actionButton("⇲", "增大行距");
        LinearLayout.LayoutParams leadingDownParams =
                new LinearLayout.LayoutParams(dp(38), dp(35));
        leadingDownParams.setMargins(dp(8), 0, 0, 0);
        editorToolbar.addView(lineHeightDecreaseButton, leadingDownParams);
        LinearLayout.LayoutParams leadingUpParams =
                new LinearLayout.LayoutParams(dp(38), dp(35));
        leadingUpParams.setMargins(dp(4), 0, 0, 0);
        editorToolbar.addView(lineHeightIncreaseButton, leadingUpParams);

        View spacer = new View(context);
        editorToolbar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView deleteButton = actionButton("删", "删除文字对象");
        TextView cancelButton = actionButton("取消", "取消本次编辑");
        TextView doneButton = actionButton("完成", "完成文字输入");
        editorToolbar.addView(deleteButton, new LinearLayout.LayoutParams(dp(42), dp(35)));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(dp(52), dp(35));
        cancelParams.setMargins(dp(4), 0, 0, 0);
        editorToolbar.addView(cancelButton, cancelParams);
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(dp(52), dp(35));
        doneParams.setMargins(dp(4), 0, 0, 0);
        editorToolbar.addView(doneButton, doneParams);

        sourceEditor = new EditText(context);
        sourceEditor.setTextSize(15);
        sourceEditor.setTextColor(INK);
        sourceEditor.setHintTextColor(Color.rgb(127, 139, 149));
        sourceEditor.setGravity(Gravity.TOP | Gravity.START);
        sourceEditor.setTypeface(Typeface.MONOSPACE);
        sourceEditor.setSingleLine(false);
        sourceEditor.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        sourceEditor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100_000)});
        sourceEditor.setPadding(dp(11), dp(8), dp(11), dp(8));
        sourceEditor.setBackground(roundedBackground(Color.WHITE,
                Color.rgb(204, 211, 217), 6));
        LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sourceParams.setMargins(dp(6), 0, dp(6), dp(6));
        editorPanel.addView(sourceEditor, sourceParams);

        addView(editorPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(172), Gravity.TOP));

        selectedDeleteButton = new TextView(context);
        selectedDeleteButton.setText("×");
        selectedDeleteButton.setTextSize(23);
        selectedDeleteButton.setTextColor(Color.WHITE);
        selectedDeleteButton.setGravity(Gravity.CENTER);
        selectedDeleteButton.setContentDescription("删除这个文字框");
        selectedDeleteButton.setClickable(true);
        selectedDeleteButton.setFocusable(true);
        GradientDrawable deleteBackground = new GradientDrawable();
        deleteBackground.setShape(GradientDrawable.OVAL);
        deleteBackground.setColor(Color.rgb(205, 67, 61));
        deleteBackground.setStroke(dp(1), Color.WHITE);
        selectedDeleteButton.setBackground(deleteBackground);
        selectedDeleteButton.setVisibility(GONE);
        selectedDeleteButton.setElevation(dp(3));
        FrameLayout.LayoutParams deleteParams = new FrameLayout.LayoutParams(
                dp(36), dp(36), Gravity.TOP | Gravity.END);
        deleteParams.setMargins(0, dp(3), dp(3), 0);
        addView(selectedDeleteButton, deleteParams);

        // Font size belongs on the selected state, not only inside the source
        // editor: changing it is a restyle, and requiring a double tap into a
        // full editor (which also forces the box to 390x300dp and opens the
        // keyboard) made it effectively undiscoverable.
        selectedStyleBar = new LinearLayout(context);
        selectedStyleBar.setOrientation(LinearLayout.HORIZONTAL);
        selectedStyleBar.setGravity(Gravity.CENTER_VERTICAL);
        selectedStyleBar.setPadding(dp(4), dp(3), dp(4), dp(3));
        GradientDrawable styleBarBackground = new GradientDrawable();
        styleBarBackground.setCornerRadius(dp(9));
        styleBarBackground.setColor(Color.argb(240, 255, 255, 255));
        styleBarBackground.setStroke(dp(1), Color.argb(90, 40, 94, 168));
        selectedStyleBar.setBackground(styleBarBackground);
        selectedStyleBar.setElevation(dp(3));
        selectedStyleBar.setVisibility(GONE);

        selectedFontDecreaseButton = actionButton("A−", "减小文字字号");
        selectedFontSizeLabel = new TextView(context);
        selectedFontSizeLabel.setTextSize(12);
        selectedFontSizeLabel.setTextColor(INK);
        selectedFontSizeLabel.setGravity(Gravity.CENTER);
        selectedFontIncreaseButton = actionButton("A+", "增大文字字号");
        selectedStyleBar.addView(selectedFontDecreaseButton,
                new LinearLayout.LayoutParams(dp(40), dp(33)));
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(dp(34), dp(33));
        labelParams.setMargins(dp(3), 0, dp(3), 0);
        selectedStyleBar.addView(selectedFontSizeLabel, labelParams);
        selectedStyleBar.addView(selectedFontIncreaseButton,
                new LinearLayout.LayoutParams(dp(40), dp(33)));

        FrameLayout.LayoutParams styleBarParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        styleBarParams.setMargins(dp(3), dp(3), 0, 0);
        addView(selectedStyleBar, styleBarParams);

        selectedFontDecreaseButton.setOnClickListener(view -> adjustSelectedFontSize(-1f));
        selectedFontIncreaseButton.setOnClickListener(view -> adjustSelectedFontSize(1f));

        latexButton.setOnClickListener(view -> setDraftFormat(NoteTextBox.Format.LATEX));
        markdownButton.setOnClickListener(view -> setDraftFormat(NoteTextBox.Format.MARKDOWN));
        fontDecreaseButton.setOnClickListener(view -> adjustDraftFontSize(-1f));
        fontIncreaseButton.setOnClickListener(view -> adjustDraftFontSize(1f));
        lineHeightDecreaseButton.setOnClickListener(view -> adjustDraftLineHeight(-0.1f));
        lineHeightIncreaseButton.setOnClickListener(view -> adjustDraftLineHeight(0.1f));
        deleteButton.setOnClickListener(view -> deleteInlineObject());
        selectedDeleteButton.setOnClickListener(view -> {
            if (model != null && selected && !inlineEditing) {
                listener.onDelete(model.copy());
            }
        });
        cancelButton.setOnClickListener(view -> cancelInlineEditing());
        doneButton.setOnClickListener(view -> commitInlineEditing());
        sourceEditor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (suppressSourceWatcher || !inlineEditing) {
                    return;
                }
                draftSource = text == null ? "" : text.toString();
                scheduleDraftRender();
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
    }

    void bind(NoteTextBox replacement, float scale, float panX, float panY,
              boolean isSelected) {
        if (replacement == null) {
            return;
        }
        boolean sameObject = model != null && model.id.equals(replacement.id);
        model = replacement.copy();
        viewportScale = Math.max(0.01f, scale);
        viewportPanX = panX;
        viewportPanY = panY;
        selected = isSelected || inlineEditing;
        boolean showSelectionChrome = selected && !inlineEditing;
        selectedDeleteButton.setVisibility(showSelectionChrome ? VISIBLE : GONE);
        // Only the fragment that starts the flow carries the restyle bar; the size
        // applies to the whole flow, so repeating it on every page would suggest
        // per-page control that does not exist.
        selectedStyleBar.setVisibility(
                showSelectionChrome && model.isFlowStart() ? VISIBLE : GONE);
        updateSelectedFontSizeLabel();

        if (!inlineEditing || !sameObject) {
            draftFormat = model.format;
            draftSource = model.source;
            draftFontSizeSp = model.fontSizeSp;
            draftLineHeight = model.lineHeight;
            renderIfChanged(model.format, model.displaySource(), model.fontSizeSp,
                    model.lineHeight);
        }
        applyScreenGeometry();
        // Runs after geometry so it reads the box's final on-screen size. Typed
        // text is ink on paper: zooming the canvas must magnify it exactly like
        // strokes. Strokes ride the canvas matrix, but this overlay is a real View
        // whose WebView renders at a fixed CSS size, so the zoom must be applied
        // here or the text would keep its on-screen size and re-wrap, breaking its
        // relationship to the page.
        applyZoomToCompiledText();
        invalidate();
    }

    void beginInlineEditing() {
        if (model == null || inlineEditing) {
            return;
        }
        inlineEditing = true;
        selected = true;
        draftFormat = model.format;
        draftSource = model.source;
        draftFontSizeSp = model.fontSizeSp;
        suppressSourceWatcher = true;
        sourceEditor.setText(draftSource);
        sourceEditor.setSelection(sourceEditor.length());
        suppressSourceWatcher = false;
        editorPanel.setVisibility(VISIBLE);
        selectedDeleteButton.setVisibility(GONE);
        selectedStyleBar.setVisibility(GONE);
        updateFormatButtons();
        applyScreenGeometry();
        renderDraftNow();
        sourceEditor.requestFocus();
        sourceEditor.post(() -> {
            InputMethodManager manager = (InputMethodManager) getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.showSoftInput(sourceEditor, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        invalidate();
    }

    boolean isInlineEditing() {
        return inlineEditing;
    }

    NoteTextBox getBoundTextBox() {
        return model == null ? null : model.copy();
    }

    /** Compatibility for the former dialog preview; current interaction edits in-place. */
    void showDraft(NoteTextBox draft) {
        if (draft != null && model != null && model.id.equals(draft.id)) {
            renderIfChanged(draft.format, draft.displaySource(), draft.fontSizeSp,
                    draft.lineHeight);
        }
    }

    void commitInlineEditing() {
        if (!inlineEditing || model == null) {
            return;
        }
        String source = sourceEditor.getText().toString();
        if (source.trim().isEmpty()) {
            Toast.makeText(getContext(), "空文字对象已取消", Toast.LENGTH_SHORT).show();
            cancelInlineEditing();
            return;
        }
        finishInlineUi();
        listener.onCommit(model.copy(), draftFormat, source, draftFontSizeSp,
                draftLineHeight);
    }

    void cancelInlineEditing() {
        if (!inlineEditing || model == null) {
            return;
        }
        NoteTextBox canceled = model.copy();
        finishInlineUi();
        renderIfChanged(canceled.format, canceled.displaySource(), canceled.fontSizeSp,
                canceled.lineHeight);
        listener.onCancel(canceled);
    }

    void dispose() {
        removeCallbacks(renderDraftRunnable);
        hideKeyboard();
        compiledView.destroy();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // Finished text is visual page content, not a permanent invisible touch shield.
        // Only a lasso-selected object (or its inline editor) may consume input.
        if (!selected && !inlineEditing) {
            return false;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (inlineEditing) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            // Taps landing on selection chrome must reach those buttons instead of
            // starting a drag of the whole flow.
            controlGesture = touchInside(selectedDeleteButton, event) ||
                    touchInside(selectedStyleBar, event);
        }
        if (controlGesture) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                    event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                controlGesture = false;
            }
            return false;
        }
        return true;
    }

    private boolean touchInside(View control, MotionEvent event) {
        return control.getVisibility() == VISIBLE &&
                event.getX() >= control.getLeft() && event.getX() <= control.getRight() &&
                event.getY() >= control.getTop() && event.getY() <= control.getBottom();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (model == null || inlineEditing) {
            return inlineEditing || super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                listener.onSelect(model.copy());
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                startScreenX = getX();
                startScreenY = getY();
                startScreenWidth = getWidth();
                startScreenHeight = getHeight();
                boolean nearRightEdge = event.getX() >= getWidth() - dp(38);
                scalingByArea = selected && nearRightEdge &&
                        event.getY() >= getHeight() - dp(38);
                resizing = selected && nearRightEdge && !scalingByArea &&
                        Math.abs(event.getY() - getHeight() / 2f) <= dp(34);
                moved = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!moved && Math.hypot(dx, dy) > touchSlop) {
                    moved = true;
                }
                if (scalingByArea) {
                    // Corner handle: both dimensions follow the finger, and the
                    // font size is solved from the resulting area on release.
                    ViewGroup.LayoutParams params = getLayoutParams();
                    params.width = Math.max(dp(140), Math.round(startScreenWidth + dx));
                    params.height = Math.max(dp(80), Math.round(startScreenHeight + dy));
                    setLayoutParams(params);
                } else if (resizing) {
                    int width = Math.max(dp(140), Math.round(startScreenWidth + dx));
                    ViewGroup.LayoutParams params = getLayoutParams();
                    params.width = width;
                    // Edge handle changes only the readable line width; pagination
                    // still owns the height, so it is left alone here.
                    params.height = startScreenHeight;
                    setLayoutParams(params);
                } else {
                    setX(startScreenX + dx);
                    setY(startScreenY + dy);
                    if (moved) {
                        listener.onDragPreview(model.copy(),
                                (getX() - viewportPanX) / viewportScale,
                                (getY() - viewportPanY) / viewportScale);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                getParent().requestDisallowInterceptTouchEvent(false);
                if (moved) {
                    if (scalingByArea) {
                        listener.onScaleToArea(model.copy(), getWidth() / viewportScale,
                                getHeight() / viewportScale);
                    } else if (resizing) {
                        listener.onResize(model.copy(), getWidth() / viewportScale,
                                getHeight() / viewportScale);
                    } else {
                        listener.onDragPreviewEnded();
                        listener.onMove(model.copy(),
                                (getX() - viewportPanX) / viewportScale,
                                (getY() - viewportPanY) / viewportScale);
                    }
                } else if (!resizing && !scalingByArea) {
                    performClick();
                    long now = System.currentTimeMillis();
                    float tapDistance = (float) Math.hypot(
                            event.getRawX() - lastTapRawX, event.getRawY() - lastTapRawY);
                    if (now - lastTapTime <= DOUBLE_TAP_MS && tapDistance <= dp(32)) {
                        lastTapTime = 0L;
                        listener.onEditRequested(model.copy());
                    } else {
                        lastTapTime = now;
                        lastTapRawX = event.getRawX();
                        lastTapRawY = event.getRawY();
                    }
                }
                resizing = false;
                scalingByArea = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                listener.onDragPreviewEnded();
                applyScreenGeometry();
                resizing = false;
                scalingByArea = false;
                moved = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!selected && !inlineEditing) {
            return;
        }
        float inset = dp(1.5f);
        RectF bounds = new RectF(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawRoundRect(bounds, dp(7), dp(7), borderPaint);
        if (!inlineEditing) {
            // Two handles with distinct meanings, following the convention in
            // GoodNotes and Figma: the edge handle changes the container and lets
            // text re-wrap, the corner handle scales the type itself. One handle
            // doing both would make "narrow this column" and "make this smaller"
            // impossible to express separately.
            float handle = dp(13);
            // Right edge midpoint: width only.
            canvas.drawCircle(getWidth() - handle, getHeight() / 2f, dp(6), handlePaint);
            // Bottom-right corner: solve font size from the target area.
            float corner = getWidth() - handle;
            float cornerY = getHeight() - handle;
            canvas.drawCircle(corner, cornerY, dp(7), handlePaint);
            canvas.drawCircle(corner, cornerY, dp(3.5f), cornerHandleInnerPaint);
        }
    }

    private void setDraftFormat(NoteTextBox.Format format) {
        if (!inlineEditing || format == draftFormat) {
            return;
        }
        draftFormat = format;
        updateFormatButtons();
        updateSourceHint();
        scheduleDraftRender();
    }

    /**
     * Changes font size straight from the selected state and commits it, so the
     * whole flow re-paginates immediately at the new size.
     */
    private void adjustSelectedFontSize(float delta) {
        if (model == null || inlineEditing || !selected) {
            return;
        }
        float replacement = Math.max(10f, Math.min(32f, model.fontSizeSp + delta));
        if (Math.abs(replacement - model.fontSizeSp) < 0.01f) {
            return;
        }
        model.fontSizeSp = replacement;
        draftFontSizeSp = replacement;
        updateSelectedFontSizeLabel();
        renderIfChanged(model.format, model.displaySource(), replacement, model.lineHeight);
        listener.onFontSizeChanged(model.copy(), replacement);
    }

    /**
     * Keeps compiled text magnified in step with the canvas.
     *
     * <p>During a pinch the already-rendered content is stretched, which is cheap
     * and cannot re-wrap mid-gesture. When the scale stops changing, the content is
     * re-rendered at the new effective size so it is sharp again and the stretch is
     * reset. The visual size is the same either way; only crispness differs.
     */
    private void applyZoomToCompiledText() {
        if (model == null) {
            return;
        }
        float target = Math.max(0.01f, viewportScale);
        if (renderedScale <= 0f) {
            renderedScale = target;
        }
        float stretch = target / renderedScale;
        compiledView.setPivotX(0f);
        compiledView.setPivotY(0f);
        compiledView.setScaleX(stretch);
        compiledView.setScaleY(stretch);
        // The WebView keeps its unscaled layout size, so widen it by the inverse of
        // the stretch; otherwise stretching would crop the right and bottom edges.
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) compiledView.getLayoutParams();
        int unscaledWidth = Math.max(1, Math.round(getWidth() / Math.max(0.01f, stretch)));
        int unscaledHeight = Math.max(1, Math.round(getHeight() / Math.max(0.01f, stretch)));
        if (params.width != unscaledWidth || params.height != unscaledHeight) {
            params.width = unscaledWidth;
            params.height = unscaledHeight;
            compiledView.setLayoutParams(params);
        }
        scheduleCrispRerender(target);
    }

    /**
     * Re-renders at the settled scale after a short quiet period, so a continuous
     * pinch does not trigger a recompile on every frame.
     */
    private void scheduleCrispRerender(float target) {
        if (Math.abs(target - renderedScale) < 0.01f) {
            return;
        }
        removeCallbacks(crispRerenderRunnable);
        pendingRenderScale = target;
        postDelayed(crispRerenderRunnable, CRISP_RERENDER_DELAY_MS);
    }

    private void renderAtSettledScale() {
        if (model == null || inlineEditing) {
            return;
        }
        renderedScale = Math.max(0.01f, pendingRenderScale);
        compiledView.setScaleX(1f);
        compiledView.setScaleY(1f);
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) compiledView.getLayoutParams();
        params.width = FrameLayout.LayoutParams.MATCH_PARENT;
        params.height = inlineEditing
                ? params.height : FrameLayout.LayoutParams.MATCH_PARENT;
        compiledView.setLayoutParams(params);
        // Force a render even though source and font size are unchanged: only the
        // effective scale moved.
        renderedScale = pendingRenderScale;
        renderedSource = null;
        renderIfChanged(model.format, model.displaySource(), model.fontSizeSp,
                model.lineHeight);
    }

    private void updateSelectedFontSizeLabel() {
        if (model == null) {
            return;
        }
        int size = Math.round(model.fontSizeSp);
        selectedFontSizeLabel.setText(String.valueOf(size));
        selectedFontDecreaseButton.setContentDescription("减小文字字号，当前 " + size);
        selectedFontIncreaseButton.setContentDescription("增大文字字号，当前 " + size);
        selectedFontDecreaseButton.setEnabled(model.fontSizeSp > 10.01f);
        selectedFontIncreaseButton.setEnabled(model.fontSizeSp < 31.99f);
        selectedFontDecreaseButton.setAlpha(model.fontSizeSp > 10.01f ? 1f : 0.4f);
        selectedFontIncreaseButton.setAlpha(model.fontSizeSp < 31.99f ? 1f : 0.4f);
    }

    private void adjustDraftLineHeight(float delta) {
        if (!inlineEditing) {
            return;
        }
        float replacement = TextFlow.clampLineHeight(draftLineHeight + delta);
        if (Math.abs(replacement - draftLineHeight) < 0.001f) {
            return;
        }
        draftLineHeight = replacement;
        String label = String.format(java.util.Locale.US, "%.1f", replacement);
        lineHeightDecreaseButton.setContentDescription("减小行距，当前 " + label);
        lineHeightIncreaseButton.setContentDescription("增大行距，当前 " + label);
        lineHeightDecreaseButton.setEnabled(replacement > TextFlow.MIN_LINE_HEIGHT + 0.001f);
        lineHeightIncreaseButton.setEnabled(replacement < TextFlow.MAX_LINE_HEIGHT - 0.001f);
        scheduleDraftRender();
    }

    private void adjustDraftFontSize(float delta) {
        if (!inlineEditing) {
            return;
        }
        float replacement = Math.max(10f, Math.min(32f, draftFontSizeSp + delta));
        if (Math.abs(replacement - draftFontSizeSp) < 0.01f) {
            return;
        }
        draftFontSizeSp = replacement;
        fontDecreaseButton.setContentDescription("减小文字字号，当前 " +
                Math.round(draftFontSizeSp));
        fontIncreaseButton.setContentDescription("增大文字字号，当前 " +
                Math.round(draftFontSizeSp));
        scheduleDraftRender();
    }

    private void scheduleDraftRender() {
        removeCallbacks(renderDraftRunnable);
        postDelayed(renderDraftRunnable, LIVE_PREVIEW_DELAY_MS);
    }

    private void renderDraftNow() {
        if (!inlineEditing) {
            return;
        }
        removeCallbacks(renderDraftRunnable);
        draftSource = sourceEditor.getText().toString();
        renderIfChanged(draftFormat, draftSource, draftFontSizeSp, draftLineHeight);
    }

    private void renderIfChanged(NoteTextBox.Format format, String source,
                                 float fontSizeSp, float lineHeight) {
        NoteTextBox.Format normalizedFormat = format == null
                ? NoteTextBox.Format.LATEX : format;
        String normalizedSource = source == null ? "" : source;
        float normalizedFontSize = TextFlow.clampFontSize(fontSizeSp);
        float normalizedLineHeight = TextFlow.clampLineHeight(lineHeight);
        if (renderedFormat == normalizedFormat && normalizedSource.equals(renderedSource) &&
                Math.abs(renderedFontSizeSp - normalizedFontSize) < 0.01f &&
                Math.abs(renderedLineHeight - normalizedLineHeight) < 0.001f) {
            return;
        }
        renderedFormat = normalizedFormat;
        renderedSource = normalizedSource;
        renderedFontSizeSp = normalizedFontSize;
        renderedLineHeight = normalizedLineHeight;
        if (renderedScale <= 0f) {
            renderedScale = Math.max(0.01f, viewportScale);
        }
        // Render at the zoomed size so magnified text stays sharp instead of being
        // a stretched bitmap. Line height is a ratio, so it needs no scaling.
        compiledView.render(normalizedFormat, normalizedSource,
                normalizedFontSize * renderedScale, normalizedLineHeight);
    }

    private void updateFormatButtons() {
        styleFormatButton(latexButton, draftFormat == NoteTextBox.Format.LATEX);
        styleFormatButton(markdownButton, draftFormat == NoteTextBox.Format.MARKDOWN);
        fontDecreaseButton.setContentDescription("减小文字字号，当前 " +
                Math.round(draftFontSizeSp));
        fontIncreaseButton.setContentDescription("增大文字字号，当前 " +
                Math.round(draftFontSizeSp));
        updateSourceHint();
    }

    private void updateSourceHint() {
        sourceEditor.setHint(draftFormat == NoteTextBox.Format.LATEX
                ? "直接输入 LaTeX，例如：\\frac{a}{b}"
                : "直接输入 Markdown，可包含 $...$ 公式");
    }

    private void styleFormatButton(TextView button, boolean active) {
        button.setTextColor(active ? Color.WHITE : INK);
        button.setBackground(roundedBackground(
                active ? ACCENT : Color.rgb(235, 239, 242),
                active ? ACCENT : Color.rgb(188, 198, 207), 6));
    }

    private TextView actionButton(String label, String description) {
        TextView button = new TextView(getContext());
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(INK);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(roundedBackground(Color.rgb(239, 242, 244),
                Color.rgb(190, 200, 208), 6));
        return button;
    }

    private void deleteInlineObject() {
        if (!inlineEditing || model == null) {
            return;
        }
        NoteTextBox deleted = model.copy();
        finishInlineUi();
        renderIfChanged(deleted.format, deleted.displaySource(), deleted.fontSizeSp,
                deleted.lineHeight);
        listener.onDelete(deleted);
    }

    private void finishInlineUi() {
        removeCallbacks(renderDraftRunnable);
        hideKeyboard();
        sourceEditor.clearFocus();
        editorPanel.setVisibility(GONE);
        inlineEditing = false;
        selectedDeleteButton.setVisibility(selected ? VISIBLE : GONE);
        selectedStyleBar.setVisibility(
                selected && model != null && model.isFlowStart() ? VISIBLE : GONE);
        updateSelectedFontSizeLabel();
        applyScreenGeometry();
        invalidate();
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    private void applyScreenGeometry() {
        if (model == null) {
            return;
        }
        int normalWidth = Math.max(1, Math.round(model.width * viewportScale));
        int normalHeight = Math.max(1, Math.round(model.height * viewportScale));
        int screenWidth = inlineEditing ? Math.max(normalWidth, dp(390)) : normalWidth;
        int screenHeight = inlineEditing ? Math.max(normalHeight, dp(300)) : normalHeight;
        ViewGroup.LayoutParams current = getLayoutParams();
        FrameLayout.LayoutParams params;
        if (current instanceof FrameLayout.LayoutParams) {
            params = (FrameLayout.LayoutParams) current;
        } else {
            params = new FrameLayout.LayoutParams(screenWidth, screenHeight);
        }
        params.width = screenWidth;
        params.height = screenHeight;
        setLayoutParams(params);
        setX(model.x * viewportScale + viewportPanX);
        setY(model.y * viewportScale + viewportPanY);

        FrameLayout.LayoutParams compiledParams = (FrameLayout.LayoutParams)
                compiledView.getLayoutParams();
        if (inlineEditing) {
            int editorHeight = Math.max(dp(155), Math.round(screenHeight * 0.56f));
            FrameLayout.LayoutParams panelParams = (FrameLayout.LayoutParams)
                    editorPanel.getLayoutParams();
            panelParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            panelParams.height = editorHeight;
            panelParams.gravity = Gravity.TOP;
            editorPanel.setLayoutParams(panelParams);
            compiledParams.topMargin = editorHeight + dp(2);
            compiledParams.height = Math.max(dp(90), screenHeight - compiledParams.topMargin);
        } else {
            compiledParams.topMargin = 0;
            compiledParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        compiledParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        compiledView.setLayoutParams(compiledParams);
    }

    private GradientDrawable roundedBackground(int fill, int stroke, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setStroke(dp(1), stroke);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(float value) {
        return Math.max(1, Math.round(value * getResources().getDisplayMetrics().density));
    }
}
