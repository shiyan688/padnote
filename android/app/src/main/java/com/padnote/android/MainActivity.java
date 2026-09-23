package com.padnote.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

public final class MainActivity extends Activity implements NoteCanvasView.Listener {
    private static final int REQUEST_IMPORT_NOTE = 7101;
    private static final int REQUEST_EXPORT_NOTE = 7102;
    private static final int REQUEST_EXPORT_VAULT = 7103;
    private static final int REQUEST_IMPORT_COVER = 7104;
    private static final int REQUEST_IMPORT_IMAGE = 7105;
    private static final int REQUEST_EXPORT_PDF = 7106;
    private static final int REQUEST_EXPORT_VIDEO_TASK = 7107;
    private static final int SURFACE_COLOR = Color.rgb(244, 241, 232);
    private static final int TOOLBAR_COLOR = Color.rgb(238, 241, 242);
    private static final int INK_COLOR = Color.rgb(23, 33, 43);
    private static final float MIN_PEN_WIDTH_DP = 0.3f;
    private static final float MAX_PEN_WIDTH_DP = 8f;
    private static final float MIN_ERASER_SIZE_DP = 8f;
    private static final float MAX_ERASER_SIZE_DP = 64f;
    private static final int[] PRESET_COLORS = new int[]{
            Color.rgb(23, 33, 43),
            Color.rgb(40, 94, 168),
            Color.rgb(182, 74, 59),
            Color.rgb(47, 128, 91),
            Color.rgb(125, 79, 164)
    };
    private static final String[] PRESET_COLOR_NAMES = new String[]{
            "墨蓝色", "书写蓝", "朱红色", "墨绿色", "紫色"
    };

    // ---- 视觉系统：纸面工作室 ----
    // 书架与对话框共用一套令牌：暖纸底、纯白圆角卡、书写蓝主色、
    // 知识库用墨绿区分。改这里即可整体调色，不要在局部散落新颜色。
    private static final int CARD_COLOR = Color.WHITE;
    private static final int CARD_STROKE = Color.rgb(236, 233, 226);
    private static final int ACCENT_COLOR = Color.rgb(40, 94, 168);
    private static final int ACCENT_TINT = Color.rgb(232, 240, 250);
    private static final int SECONDARY_TEXT = Color.rgb(107, 118, 132);
    private static final int FAINT_TEXT = Color.rgb(137, 147, 155);
    private static final int VAULT_COLOR = Color.rgb(47, 128, 91);
    private static final int VAULT_TINT = Color.rgb(233, 243, 238);
    private static final int AMBER_TEXT = Color.rgb(164, 106, 42);
    private static final int AMBER_TINT = Color.rgb(250, 242, 230);
    private static final int BUTTON_PRIMARY = 0;
    private static final int BUTTON_TONAL = 1;
    private static final int BUTTON_QUIET = 2;
    private static final int BUTTON_DANGER = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService storageExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService digitizeExecutor = Executors.newSingleThreadExecutor();
    private final List<ColorSwatchButton> colorSwatches = new ArrayList<>();
    private final List<OpenAiCompatibleClient.Message> aiMessages = new ArrayList<>();

    private FrameLayout appFrame;
    private FrameLayout canvasFrame;
    private LinearLayout bookshelfList;
    /** Section heading for the note grid; renderBookshelf keeps its count current. */
    private TextView shelfSectionTitle;
    private NoteCanvasView canvasView;
    private NoteCanvasView.PdfExportSnapshot activePdfExportSnapshot;
    private FrameLayout activePdfExportHost;
    private AlertDialog activePdfExportDialog;
    private TextView statsView;
    private TextView saveStatusView;
    private TextView pressureView;
    private ToolIconButton penButton;
    private ToolIconButton textButton;
    private ToolIconButton eraserButton;
    private ToolIconButton lassoButton;
    private ToolIconButton aiButton;
    private ToolIconButton paletteButton;
    private ToolIconButton undoButton;
    private ToolIconButton redoButton;
    private ToolIconButton duplicateButton;
    private ToolIconButton deleteSelectionButton;
    private ToolIconButton cancelSelectionButton;
    private ToolIconButton penOnlyButton;
    private PopupWindow toolSettingsPopup;
    private LinearLayout aiCard;
    private LinearLayout aiCardBody;
    private LinearLayout aiConversationView;
    private ScrollView aiConversationScroll;
    private ImageView aiSelectionPreview;
    private TextView aiCardTitle;
    private TextView aiStatusView;
    private TextView aiMinimizeButton;
    private EditText aiInputView;
    private Button aiSendButton;
    private Button aiExplainButton;
    private Button aiMarkdownButton;
    private Button aiDiagramButton;
    private Button aiInlineOutputButton;
    private Button aiCardOutputButton;
    private TextView aiResizeHandle;
    private NoteCanvasView.AiSelectionSnapshot aiSelectionSnapshot;
    private RectF aiResultAnchorBounds;
    private AiConfigStore aiConfigStore;
    private VaultStore vaultStore;
    private AgentConnectionStore agentConnectionStore;
    private SharedPreferences preferences;
    private String currentNoteId;
    private String currentNoteTitle;
    private String pendingExportNoteId;
    private String pendingExportJson;
    private String pendingExportVaultFile;
    private String pendingVideoTaskFile;
    private String pendingVideoAudience;
    private String pendingVideoGoal;
    private int pendingVideoDuration;
    /** True while a whole-note digitization is running; guards re-entry. */
    private boolean digitizing = false;
    private boolean editorVisible = false;
    private int bookshelfLoadGeneration = 0;
    private float penWidthDp = 2f;
    private float highlighterWidthDp = 12f;
    private int highlighterColor = Color.rgb(255, 205, 45);
    private ToolIconButton highlighterButton;
    private ToolIconButton shapeButton;
    private float eraserSizeDp = 30f;
    private int selectedInkColor = INK_COLOR;
    private boolean penOnlyEnabled = true;
    private boolean aiCardMinimized = false;
    private boolean aiBusy = false;
    private boolean aiUploadConfirmed = false;
    private boolean aiOutputInline = true;
    private int aiSessionSerial = 0;
    /**
     * Tools the model may call; nothing outside this set can run. Created in
     * {@link #onCreate} because the knowledge-base readers need the vault store.
     */
    private NoteToolRegistry aiToolRegistry;
    /**
     * Authority for the current task. Tied to the existing page-output switch:
     * "仅卡片" withholds write access entirely, so the toggle now means
     * "may the model touch the note" rather than "where does the answer go".
     */
    private NoteTool.Permission aiGrantedPermission = NoteTool.Permission.READ_ONLY;
    /**
     * Most rounds of tool calls allowed per task. Bounds cost without preventing
     * the read-then-write workflow; requests are non-streaming with no cancel, so
     * an unbounded loop would spend the user's money.
     */
    private static final int MAX_AI_TOOL_ROUNDS = 4;
    /** Tool call ids already run, so a retried request cannot write twice. */
    private final Set<String> aiExecutedToolCallIds = new HashSet<>();
    /**
     * Whether this provider has ever honoured the tools field. BYOK endpoints are
     * arbitrary, so when a model never calls a tool the old paste-everything path
     * remains the only way page writing works at all.
     */
    private boolean aiToolsHonoured = false;
    /**
     * Transcript of the selection produced by the split route's first leg.
     * Lives for one selection session: follow-up questions reuse it instead of
     * re-sending (and re-paying for) the image, and it is dropped whenever the
     * selection or card closes.
     */
    private String aiSessionTranscript = null;
    /** Paper for a note being created; null when opening an existing note. */
    private PageStyle pendingPageStyle;
    private float aiDragStartRawX;
    private float aiDragStartRawY;
    private float aiDragStartCardX;
    private float aiDragStartCardY;
    private float aiResizeStartRawX;
    private float aiResizeStartRawY;
    private int aiResizeStartWidth;
    private int aiResizeStartHeight;
    private int aiCardExpandedWidthPx;
    private int aiCardExpandedHeightPx;
    private int strokeCount = 0;
    private int pointCount = 0;
    private int selectionCount = 0;
    private int currentPageNumber = 1;
    private int totalPageCount = 1;
    private int canvasZoomPercent = 100;
    private boolean restoreCompleted = false;
    private final Map<String, NoteTextBoxView> textBoxViews = new LinkedHashMap<>();
    /** Bookshelf entries from the last render; vault staleness checks read it. */
    private List<NoteStore.Entry> lastShelfEntries = new ArrayList<>();
    /** Decoded shelf covers keyed by note id; refreshed with the entry list. */
    private java.util.Map<String, Bitmap> lastShelfCovers = new java.util.HashMap<>();
    /** Cover-pick target; null while choosing for a note that does not exist yet. */
    private String pendingCoverNoteId;
    private Bitmap pendingNewNoteCover;
    private String pendingNewNoteTitle = "";
    private android.app.Dialog coverPickerDialog;
    private String pendingNewNoteCoverLabel = "";
    private TextView newNoteCoverStatus;
    private final Set<String> newlyInsertedTextBoxIds = new HashSet<>();
    private String selectedTextBoxId;
    private String selectedTextFlowId;

    private final Runnable delayedSave = () -> saveDocument(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("padnote-tools", MODE_PRIVATE);
        aiConfigStore = new AiConfigStore(this);
        vaultStore = new VaultStore(this);
        agentConnectionStore = new AgentConnectionStore(this);
        // Core document tools plus the read-only knowledge-base readers; the
        // registry stays the only execution path, so an unregistered name still
        // cannot run no matter what the model asks for.
        aiToolRegistry = NoteTools.createDefault(vaultStore);
        penWidthDp = Math.max(MIN_PEN_WIDTH_DP, Math.min(MAX_PEN_WIDTH_DP,
                preferences.getFloat("penWidthDp", 2f)));
        eraserSizeDp = preferences.getFloat("eraserSizeDp", 30f);
        highlighterWidthDp = Math.max(4f, Math.min(32f,
                preferences.getFloat("highlighterWidthDp", 12f)));
        highlighterColor = preferences.getInt("highlighterColor", Color.rgb(255, 205, 45));
        selectedInkColor = preferences.getInt("inkColor", INK_COLOR);
        penOnlyEnabled = preferences.getBoolean("penOnly", true);
        aiOutputInline = preferences.getBoolean("aiOutputInline", true);
        // Keep the tool grant consistent with the restored preference; otherwise a
        // user who left writing enabled would silently start read-only.
        aiGrantedPermission = aiOutputInline
                ? NoteTool.Permission.CREATE_IN_FREE_SPACE
                : NoteTool.Permission.READ_ONLY;
        configureWindow();
        showBookshelf();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (editorVisible) {
            commitInlineTextEditorExcept(null);
        }
        handler.removeCallbacks(delayedSave);
        if (editorVisible) {
            saveDocument(false);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (activePdfExportSnapshot != null) {
            activePdfExportSnapshot.close();
            activePdfExportSnapshot = null;
        }
        if (activePdfExportHost != null && activePdfExportHost.getParent() == appFrame) {
            appFrame.removeView(activePdfExportHost);
        }
        activePdfExportHost = null;
        if (activePdfExportDialog != null && activePdfExportDialog.isShowing()) {
            activePdfExportDialog.dismiss();
        }
        activePdfExportDialog = null;
        if (toolSettingsPopup != null) {
            toolSettingsPopup.dismiss();
        }
        closeAiCard();
        disposeTextBoxViews();
        storageExecutor.shutdown();
        aiExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onInkStatsChanged(int strokes, int points, float pressure, String inputStatus) {
        strokeCount = strokes;
        pointCount = points;
        updateStatsText();
        pressureView.setText(String.format(Locale.CHINA, "压力 %.3f · %s", pressure, inputStatus));
    }

    @Override
    public void onDocumentChanged() {
        saveStatusView.setText("等待自动保存");
        handler.removeCallbacks(delayedSave);
        handler.postDelayed(delayedSave, 600);
    }

    @Override
    public void onSelectionChanged(int selectedStrokes, boolean canUndo, boolean canRedo) {
        selectionCount = selectedStrokes;
        updateStatsText();
        setActionEnabled(undoButton, canUndo);
        setActionEnabled(redoButton, canRedo);
        boolean hasSelection = selectedStrokes > 0;
        setActionEnabled(aiButton, hasSelection);
        setActionEnabled(duplicateButton, hasSelection);
        setActionEnabled(deleteSelectionButton, hasSelection);
        setActionEnabled(cancelSelectionButton, hasSelection);
    }

    @Override
    public void onViewportChanged(int currentPage, int pageCount, int zoomPercent, String status) {
        currentPageNumber = currentPage;
        totalPageCount = pageCount;
        canvasZoomPercent = zoomPercent;
        updateStatsText();
        if (pressureView != null && status != null && !status.isEmpty()) {
            pressureView.setText(status);
        }
        if (canvasView != null) {
            updateTextBoxOverlays(canvasView.getTextBoxes());
        }
    }

    @Override
    public void onTextBoxesChanged(List<NoteTextBox> textBoxes) {
        updateTextBoxOverlays(textBoxes);
    }

    @Override
    public void onTextBoxSelectionChanged(String textFlowId) {
        selectedTextFlowId = textFlowId;
        selectedTextBoxId = null;
        if (canvasView != null) {
            updateTextBoxOverlays(canvasView.getTextBoxes());
        }
        if (pressureView != null && textFlowId != null) {
            pressureView.setText("套索已选中文字流 · 整组移动/删除 · 双击统一编辑");
        }
    }

    @Override
    public void onTextInsertRequested(float worldX, float worldY) {
        commitInlineTextEditorExcept(null);
        NoteTextBox inserted = canvasView.addTextBoxAt(
                NoteTextBox.Format.LATEX, "", worldX, worldY);
        if (inserted == null) {
            return;
        }
        newlyInsertedTextBoxIds.add(inserted.id);
        selectedTextBoxId = inserted.id;
        selectedTextFlowId = inserted.flowId;
        updateTextBoxOverlays(canvasView.getTextBoxes());
        beginInlineTextEditing(inserted.id);
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(SURFACE_COLOR);
        window.setNavigationBarColor(Color.rgb(240, 238, 232));
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private void showBookshelf() {
        handler.removeCallbacks(delayedSave);
        disposeTextBoxViews();
        editorVisible = false;
        restoreCompleted = false;
        bookshelfLoadGeneration += 1;
        if (toolSettingsPopup != null) {
            toolSettingsPopup.dismiss();
        }
        closeAiCard();
        setContentView(createBookshelfView());
        loadBookshelf(bookshelfLoadGeneration);
    }

    private View createBookshelfView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SURFACE_COLOR);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(30), dp(26), dp(30), dp(18));
        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        BookshelfMarkView brandMark = new BookshelfMarkView(this);
        LinearLayout.LayoutParams brandMarkParams = new LinearLayout.LayoutParams(dp(52), dp(42));
        brandMarkParams.rightMargin = dp(14);
        brandRow.addView(brandMark, brandMarkParams);
        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("PadNote", 28, INK_COLOR);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleGroup.addView(title);
        titleGroup.addView(text("我的书架  ·  本地多笔记", 14, Color.rgb(103, 116, 126)));
        brandRow.addView(titleGroup, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(brandRow, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = matchWrap();
        actionsParams.topMargin = dp(18);
        header.addView(actions, actionsParams);
        Button importButton = pillButton("导入笔记", BUTTON_QUIET);
        importButton.setOnClickListener(view -> launchImportNote());
        actions.addView(importButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
        Button agentButton = pillButton("电脑 Agent", BUTTON_QUIET);
        AgentConnectionStore.Config agent = agentConnectionStore.load();
        agentButton.setContentDescription(agent.connected ? "电脑 Agent 连接测试通过" : "设置电脑 Agent");
        agentButton.setOnClickListener(view -> showAgentConnectionDialog());
        LinearLayout.LayoutParams agentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        agentParams.leftMargin = dp(8);
        actions.addView(agentButton, agentParams);
        Button createButton = pillButton("＋ 新建笔记", BUTTON_PRIMARY);
        createButton.setOnClickListener(view -> showCreateNoteDialog());
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        createParams.setMargins(dp(10), 0, 0, 0);
        actions.addView(createButton, createParams);
        root.addView(header);

        TextView sectionTitle = text("最近笔记", 17, INK_COLOR);
        sectionTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sectionTitle.setPadding(dp(30), dp(18), dp(30), dp(12));
        shelfSectionTitle = sectionTitle;
        root.addView(sectionTitle);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        bookshelfList = new LinearLayout(this);
        bookshelfList.setOrientation(LinearLayout.VERTICAL);
        bookshelfList.setPadding(dp(20), dp(4), dp(20), dp(34));
        TextView loading = text("正在整理书架…", 14, Color.rgb(104, 116, 126));
        loading.setGravity(Gravity.CENTER);
        bookshelfList.addView(loading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(160)));
        scroll.addView(bookshelfList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    /**
     * The one button builder for chrome outside the canvas: filled pill for the
     * primary action, tinted pill for per-card actions, quiet text for secondary
     * ones. Replaces the old system-default buttons whose platform blue clashed
     * with the paper palette.
     */
    private Button pillButton(String label, int style) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setStateListAnimator(null);
        int fill = Color.TRANSPARENT;
        int stroke = Color.TRANSPARENT;
        float radius = 21f;
        switch (style) {
            case BUTTON_PRIMARY:
                button.setTextSize(14);
                button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                button.setTextColor(Color.WHITE);
                fill = ACCENT_COLOR;
                break;
            case BUTTON_TONAL:
                button.setTextSize(13);
                button.setTextColor(ACCENT_COLOR);
                fill = ACCENT_TINT;
                radius = 19f;
                break;
            case BUTTON_DANGER:
                button.setTextSize(13);
                button.setTextColor(Color.rgb(178, 58, 48));
                fill = Color.rgb(250, 238, 236);
                radius = 19f;
                break;
            default: // BUTTON_QUIET
                button.setTextSize(13);
                button.setTextColor(SECONDARY_TEXT);
                break;
        }
        button.setBackground(roundedBackground(fill, stroke, radius));
        applyPressFeedback(button, radius);
        button.setPadding(dp(16), 0, dp(16), 0);
        return button;
    }

    /** White rounded card surface used across the bookshelf and dialogs. */
    private LinearLayout cardSurface(float cornerRadiusDp, int paddingDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = roundedBackground(CARD_COLOR, CARD_STROKE,
                cornerRadiusDp);
        card.setBackground(background);
        card.setElevation(dp(2));
        card.setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp));
        return card;
    }

    private LinearLayout verticalPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(4), 0, dp(4), 0);
        return panel;
    }

    private LinearLayout labeledField(String label, EditText field) {
        LinearLayout group = verticalPanel();
        TextView caption = text(label, 12, SECONDARY_TEXT);
        group.addView(caption, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        group.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        group.setLayoutParams(params);
        return group;
    }

    private TextView sectionCaption(String value) {
        return text(value, 12, SECONDARY_TEXT);
    }

    private void loadBookshelf(int generation) {
        storageExecutor.execute(() -> {
            try {
                List<NoteStore.Entry> entries = NoteStore.list(this);
                java.util.Map<String, Bitmap> covers = new java.util.HashMap<>();
                for (NoteStore.Entry entry : entries) {
                    Bitmap cover = CoverStore.load(this, entry.id, 320);
                    if (cover != null) {
                        covers.put(entry.id, cover);
                    }
                }
                runOnUiThread(() -> {
                    if (!editorVisible && generation == bookshelfLoadGeneration) {
                        lastShelfCovers = covers;
                        renderBookshelf(entries);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!editorVisible && generation == bookshelfLoadGeneration) {
                        bookshelfList.removeAllViews();
                        TextView message = text("书架读取失败：" + safeError(error),
                                14, Color.rgb(143, 47, 43));
                        message.setGravity(Gravity.CENTER);
                        bookshelfList.addView(message, new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));
                    }
                });
            }
        });
    }

    private void refreshBookshelf() {
        if (!editorVisible) {
            bookshelfLoadGeneration += 1;
            loadBookshelf(bookshelfLoadGeneration);
        }
    }

    private void renderBookshelf(List<NoteStore.Entry> entries) {
        bookshelfList.removeAllViews();
        lastShelfEntries = entries;
        shelfSectionTitle.setText(entries.isEmpty() ? "最近笔记"
                : String.format(Locale.CHINA, "最近笔记 · %d", entries.size()));
        addFirstRunGuideCard();
        if (entries.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            BookshelfMarkView icon = new BookshelfMarkView(this);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(92), dp(76));
            iconParams.bottomMargin = dp(16);
            empty.addView(icon, iconParams);
            TextView message = text("书架还是空的\n新建第一本笔记，或导入 .padnote.json",
                    15, SECONDARY_TEXT);
            message.setGravity(Gravity.CENTER);
            empty.addView(message);
            Button create = pillButton("新建笔记", BUTTON_PRIMARY);
            create.setOnClickListener(view -> showCreateNoteDialog());
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
            buttonParams.setMargins(0, dp(14), 0, 0);
            empty.addView(create, buttonParams);
            bookshelfList.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
            renderVaultSection();
            return;
        }

        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        int columns = Math.max(2, Math.min(3, screenWidthDp / 340));
        for (int start = 0; start < entries.size(); start += columns) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            for (int column = 0; column < columns; column++) {
                int index = start + column;
                View child = index < entries.size() ? createNoteCard(entries.get(index)) : new View(this);
                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, dp(292), 1f);
                cardParams.setMargins(dp(10), dp(9), dp(10), dp(9));
                row.addView(child, cardParams);
            }
            bookshelfList.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        renderVaultSection();
    }

    /**
     * Dismissible onboarding card pinned to the top of the shelf. The knowledge
     * base lives inside a per-note overflow menu, so without this the feature is
     * effectively invisible to a new user; shown once until dismissed.
     */
    private void addFirstRunGuideCard() {
        if (preferences.getBoolean("guideDismissedV1", false)) {
            return;
        }
        LinearLayout card = cardSurface(20f, 18);
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(dp(0), dp(4), dp(0), dp(12));
        bookshelfList.addView(card, params);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("三步上手 PadNote", 16, INK_COLOR);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView dismiss = text("✕", 14, FAINT_TEXT);
        dismiss.setPadding(dp(12), dp(6), dp(4), dp(6));
        dismiss.setClickable(true);
        dismiss.setContentDescription("关闭新手引导");
        dismiss.setOnClickListener(view -> {
            preferences.edit().putBoolean("guideDismissedV1", true).apply();
            card.setVisibility(View.GONE);
        });
        head.addView(dismiss);
        card.addView(head);

        card.addView(guideStep("01", "写与圈选",
                "用笔书写，双指缩放画布；套索工具可圈选任意手写内容。", ACCENT_COLOR));
        card.addView(guideStep("02", "问 AI",
                "圈选后点 AI 图标：讲解、整理 Markdown 或自由提问——"
                        + "也可以问「我之前的笔记里…」。", ACCENT_COLOR));
        card.addView(guideStep("03", "转成格式笔记",
                "笔记卡片「更多 → 转为格式笔记」：整本变成 Obsidian 兼容的 "
                        + "Markdown 知识库，AI 提问时会自动检索它。", ACCENT_COLOR));
    }

    /** One icon + bold lead + caption line, used by every guide surface. */
    private View guideStep(String icon, String title, String detail, int iconColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(9), 0, 0);
        row.setGravity(Gravity.TOP);
        TextView badge = text(icon, 14, iconColor);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setMinWidth(dp(28));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeParams.rightMargin = dp(9);
        row.addView(badge, badgeParams);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(title, 14, INK_COLOR);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        column.addView(name);
        column.addView(text(detail, 12, SECONDARY_TEXT));
        row.addView(column, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /**
     * Knowledge-base section: one Markdown file per digitized note, plus the
     * guidance that makes the feature discoverable before the first conversion.
     */
    private void renderVaultSection() {
        List<VaultStore.VaultNote> vaultNotes = vaultStore.list();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6), dp(34), dp(6), dp(10));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView sectionTitle = text(vaultNotes.isEmpty()
                ? "知识库 · 格式笔记"
                : String.format(Locale.CHINA, "知识库 · 格式笔记 · %d", vaultNotes.size()),
                17, INK_COLOR);
        sectionTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titles.addView(sectionTitle);
        titles.addView(sectionCaption("手写笔记的 Markdown 版 · 可整体拷入 Obsidian"));
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button what = pillButton("这是什么？", BUTTON_QUIET);
        what.setOnClickListener(view -> showVaultGuideDialog());
        header.addView(what, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        bookshelfList.addView(header);

        if (vaultNotes.isEmpty()) {
            LinearLayout.LayoutParams guideParams = matchWrap();
            guideParams.setMargins(dp(0), dp(4), dp(0), dp(6));
            bookshelfList.addView(vaultEmptyGuide(), guideParams);
            return;
        }
        Map<String, Long> shelfModified = new LinkedHashMap<>();
        for (NoteStore.Entry entry : lastShelfEntries) {
            shelfModified.put(entry.id, entry.updatedAt);
        }
        for (VaultStore.VaultNote note : vaultNotes) {
            bookshelfList.addView(createVaultRow(note,
                    shelfModified.get(note.noteId)));
        }
    }

    /** Empty state that teaches the workflow instead of apologizing for it. */
    private View vaultEmptyGuide() {
        LinearLayout guide = new LinearLayout(this);
        guide.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(VAULT_TINT);
        background.setCornerRadius(dp(20));
        guide.setBackground(background);
        int pad = dp(18);
        guide.setPadding(pad, pad, pad, pad);
        // The caller attaches this view to bookshelfList; attaching here too
        // would give the view two parents and crash every shelf render (the
        // 0.17.0/0.17.1 launch-loop crash).

        TextView headline = text("把一本手写笔记，变成可检索的格式笔记", 14, INK_COLOR);
        headline.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        guide.addView(headline);
        guide.addView(guideStep("①", "打开一本写好的手写笔记",
                "公式推导、流程图都没问题。", VAULT_COLOR));
        guide.addView(guideStep("②", "点卡片「更多 → 转为格式笔记」",
                "需要先在 AI 卡片里配置一次模型。", VAULT_COLOR));
        guide.addView(guideStep("③", "转换结果出现在这里",
                "可阅读、导出 .md 拷入 Obsidian；圈选提问时 AI 也会检索它。",
                VAULT_COLOR));
        TextView privacy = text("上传说明：数字化会把整页内容发送给你配置的模型，"
                + "而不只是圈选区域；转换前会再次确认。", 11, AMBER_TEXT);
        privacy.setPadding(0, dp(10), 0, 0);
        guide.addView(privacy);
        return guide;
    }

    /** What the vault is, how it is produced, where it lives. */
    private void showVaultGuideDialog() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(14), dp(24), dp(8));
        body.addView(dialogParagraph("「格式笔记」是手写笔记的机器可读版本："
                + "逐页转写成 Markdown（公式 LaTeX、流程图 Mermaid），"
                + "人读美观、软件可读，整个目录可以直接拷进 Obsidian。", INK_COLOR));
        body.addView(dialogParagraph("生成方式：书架笔记卡片 → 更多 → 转为格式笔记。"
                + "每一页发给你配置的视觉模型转写后按页合并，原手写稿不受影响。", INK_COLOR));
        body.addView(dialogParagraph("与 AI 的关系：从 0.16 起，圈选提问时模型可以"
                + "检索并阅读整个知识库——试试问「我之前哪本笔记讲过…」。", INK_COLOR));
        body.addView(dialogParagraph("存储与隐私：文件保存在应用私有目录，"
                + "只有数字化和你的主动提问会把内容发送到模型端点。", SECONDARY_TEXT));
        new AlertDialog.Builder(this)
                .setTitle("知识库是什么")
                .setView(body)
                .setPositiveButton("知道了", null)
                .show();
    }

    private TextView dialogParagraph(String value, int color) {
        TextView paragraph = text(value, 13, color);
        paragraph.setPadding(0, dp(6), 0, dp(2));
        return paragraph;
    }

    private View createVaultRow(VaultStore.VaultNote note, Long sourceUpdatedAt) {
        LinearLayout row = cardSurface(18f, 14);
        applyPressFeedback(row, 18f);
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.setMargins(dp(0), dp(4), dp(0), dp(6));
        row.setLayoutParams(rowParams);
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription("打开格式笔记 " + note.title);
        row.setOnClickListener(view -> showVaultReader(note.fileName));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.TOP);
        TextView badge = text("MD", 12, VAULT_COLOR);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(roundedBackground(VAULT_TINT, Color.TRANSPARENT, 10f));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        badgeParams.rightMargin = dp(12);
        top.addView(badge, badgeParams);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(note.title, 15, INK_COLOR);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(1);
        column.addView(title);
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new Date(note.digitizedAt));
        column.addView(sectionCaption(note.pageCount + " 页 · 数字化于 " + date));
        boolean stale = sourceUpdatedAt != null && sourceUpdatedAt > note.sourceModifiedAt;
        if (stale) {
            TextView chip = text("原笔记已修改 · 建议重新生成", 11, AMBER_TEXT);
            chip.setBackgroundResource(0);
            GradientDrawable chipBackground = new GradientDrawable();
            chipBackground.setColor(AMBER_TINT);
            chipBackground.setCornerRadius(dp(9));
            chip.setBackground(chipBackground);
            chip.setPadding(dp(8), dp(3), dp(8), dp(3));
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipParams.topMargin = dp(5);
            column.addView(chip, chipParams);
        }
        top.addView(column, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(top);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button open = pillButton("打开", BUTTON_TONAL);
        open.setOnClickListener(view -> showVaultReader(note.fileName));
        actions.addView(open, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        Button more = pillButton("⋮", BUTTON_QUIET);
        more.setTextSize(16);
        more.setContentDescription("格式笔记更多操作：导出、删除");
        more.setOnClickListener(view -> showVaultMoreMenu(note));
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(40), dp(36));
        moreParams.leftMargin = dp(8);
        actions.addView(more, moreParams);
        LinearLayout.LayoutParams actionsParams = matchWrap();
        actionsParams.topMargin = dp(8);
        row.addView(actions, actionsParams);
        return row;
    }

    /** Per-vault-note overflow: export and delete live here, not on the card face. */
    private void showVaultMoreMenu(VaultStore.VaultNote note) {
        AgentConnectionStore.Config agent = agentConnectionStore.load();
        String[] actions = agent.connected
                ? new String[]{"导出 .md", "生成视频任务包", "删除"}
                : new String[]{"导出 .md", "删除"};
        new AlertDialog.Builder(this)
                .setTitle(note.title)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        launchExportVaultFile(note);
                    } else if (agent.connected && which == 1) {
                        showVideoTaskDialog(note);
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("删除格式笔记？")
                                .setMessage("将删除《" + note.title + "》。手写原稿不受影响。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("删除", (ignored, whichInner) -> {
                                    vaultStore.delete(note.fileName);
                                    refreshBookshelf();
                                })
                                .show();
                    }
                })
                .show();
    }

    /** Collects the small amount of human intent an explainer Agent cannot infer from notes. */
    private void showVideoTaskDialog(VaultStore.VaultNote note) {
        LinearLayout body = verticalPanel();
        EditText audience = new EditText(this);
        audience.setHint("例如：高中生");
        audience.setSingleLine(true);
        audience.setText("希望理解这份笔记的学习者");
        body.addView(labeledField("面向谁", audience));
        EditText goal = new EditText(this);
        goal.setHint("例如：看完后能解释核心概念");
        goal.setSingleLine(true);
        goal.setText("理解并记住这份笔记的核心概念");
        body.addView(labeledField("讲完要达到什么", goal));
        EditText duration = new EditText(this);
        duration.setInputType(InputType.TYPE_CLASS_NUMBER);
        duration.setSingleLine(true);
        duration.setText("120");
        body.addView(labeledField("目标时长（秒）", duration));
        new AlertDialog.Builder(this)
                .setTitle("生成讲解视频任务")
                .setMessage("先导出标准任务包，再交给电脑上的 Hermes、OpenClaw 或其他 Agent。任务包不含 API Key。")
                .setView(body)
                .setNegativeButton("取消", null)
                .setPositiveButton("导出任务包", (dialog, which) -> {
                    pendingVideoTaskFile = note.fileName;
                    pendingVideoAudience = audience.getText().toString().trim();
                    pendingVideoGoal = goal.getText().toString().trim();
                    pendingVideoDuration = parseVideoDuration(duration.getText().toString());
                    launchCreateVideoTaskDocument(note.title);
                })
                .show();
    }

    private void showAgentConnectionDialog() {
        AgentConnectionStore.Config current = agentConnectionStore.load();
        LinearLayout body = verticalPanel();
        Spinner kind = new Spinner(this);
        ArrayAdapter<String> kindAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Hermes Agent（HTTPS）", "OpenClaw（当前未支持）"});
        kindAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        kind.setAdapter(kindAdapter);
        kind.setSelection(current.kind == AgentConnectionStore.Kind.OPENCLAW ? 1 : 0);
        body.addView(labeledSpinner("Agent 类型", kind));
        EditText endpoint = new EditText(this);
        endpoint.setSingleLine(true);
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setHint("必须是平板可访问且证书受信任的 https:// 地址");
        endpoint.setText(current.endpoint);
        body.addView(labeledField("电脑 Agent 地址", endpoint));
        EditText token = new EditText(this);
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setHint(current.token.isEmpty() ? "粘贴本地连接令牌" : "已保存，留空保持不变");
        body.addView(labeledField("连接令牌", token));
        TextView hint = text("本版可测试 Hermes 连接；自动发送任务、处理审批和接收结果尚未实现。",
                12, SECONDARY_TEXT);
        hint.setPadding(dp(4), dp(4), dp(4), dp(8));
        body.addView(hint);

        LinearLayout guideRow = new LinearLayout(this);
        guideRow.setOrientation(LinearLayout.VERTICAL);
        TextView guideButton = text("如何连接另一台电脑？", 15, ACCENT_COLOR);
        guideButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        guideButton.setMinHeight(dp(48));
        guideButton.setPadding(dp(4), dp(10), dp(4), dp(4));
        guideButton.setClickable(true);
        guideButton.setFocusable(true);
        guideButton.setOnClickListener(view -> AgentConnectionGuide.show(this));
        guideRow.addView(guideButton, matchWrap());
        TextView guideSummary = text("离线查看 Windows / WSL2、macOS 和 Linux 的 Hermes 连接步骤。",
                12, SECONDARY_TEXT);
        guideSummary.setPadding(dp(4), 0, dp(4), dp(12));
        guideRow.addView(guideSummary, matchWrap());
        body.addView(guideRow, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(current.connected ? "电脑 Agent · 连接测试通过" : "连接电脑 Agent")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .setNeutralButton("断开", (ignored, which) -> {
                    agentConnectionStore.clear();
                    refreshBookshelf();
                })
                .setPositiveButton("保存并测试", null)
                .create();
        kind.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 1) {
                    hint.setText("当前版本尚不能连接 OpenClaw，请选择 Hermes。");
                } else {
                    hint.setText("本版可测试 Hermes 连接；自动发送任务、处理审批和接收结果尚未实现。");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    if (kind.getSelectedItemPosition() == 1) {
                        hint.setText("当前版本尚不能连接 OpenClaw，请选择 Hermes。");
                        return;
                    }
                    String address = endpoint.getText().toString().trim();
                    if (!isValidHttpsEndpoint(address)) {
                        endpoint.setError("请输入 HTTPS 地址");
                        return;
                    }
                    String secret = token.getText().toString().trim();
                    if (secret.isEmpty()) secret = current.token;
                    if (secret.isEmpty()) {
                        token.setError("请输入连接令牌");
                        return;
                    }
                    AgentConnectionStore.Kind selected = kind.getSelectedItemPosition() == 1
                            ? AgentConnectionStore.Kind.OPENCLAW : AgentConnectionStore.Kind.HERMES;
                    try {
                        agentConnectionStore.save(selected, address, secret);
                    } catch (Exception error) {
                        Toast.makeText(this, "连接信息保存失败", Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    hint.setText("正在检查电脑上的 Hermes 服务和连接令牌…");
                    AgentConnectionStore.Config saved = agentConnectionStore.load();
                    aiExecutor.execute(() -> {
                        try {
                            AgentConnectionClient.probe(saved);
                            agentConnectionStore.setConnected(true);
                            runOnUiThread(() -> {
                                dialog.dismiss();
                                Toast.makeText(this, "连接测试通过", Toast.LENGTH_SHORT).show();
                                refreshBookshelf();
                            });
                        } catch (Exception error) {
                            agentConnectionStore.setConnected(false);
                            runOnUiThread(() -> {
                                hint.setText("连接失败：" + safeError(error));
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            });
                        }
                    });
                }));
        dialog.show();
    }

    private LinearLayout labeledSpinner(String label, Spinner spinner) {
        LinearLayout group = verticalPanel();
        group.addView(text(label, 12, SECONDARY_TEXT));
        group.addView(spinner, matchWrap());
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(10);
        group.setLayoutParams(params);
        return group;
    }

    private int parseVideoDuration(String value) {
        try {
            return Math.max(30, Math.min(900, Integer.parseInt(value.trim())));
        } catch (NumberFormatException ignored) {
            return 120;
        }
    }

    private void launchCreateVideoTaskDocument(String title) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, safeFileName(title) + ".padnote-video.zip");
        startActivityForResult(intent, REQUEST_EXPORT_VIDEO_TASK);
    }

    private View createNoteCard(NoteStore.Entry entry) {
        LinearLayout card = cardSurface(20f, 20);
        applyPressFeedback(card, 20f);
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription("打开笔记 " + entry.title
                + "。长按可重命名或更换封面。");
        card.setOnClickListener(view -> openNote(entry));
        card.setOnLongClickListener(view -> {
            showNoteMoreMenu(entry);
            return true;
        });

        Bitmap cover = lastShelfCovers.get(entry.id);
        if (cover != null) {
            LinearLayout.LayoutParams plateParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(126));
            plateParams.setMargins(0, 0, 0, dp(16));
            card.addView(coverPlate(cover), plateParams);
        }

        TextView title = text(entry.title, 17, INK_COLOR);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(2);
        card.addView(title);
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new Date(entry.updatedAt));
        TextView meta = text(entry.pageCount + " 页 · " + entry.strokeCount + " 笔 · " + date,
                12, SECONDARY_TEXT);
        meta.setPadding(0, dp(4), 0, 0);
        card.addView(meta);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.BOTTOM);
        Button open = pillButton("打开", BUTTON_TONAL);
        open.setOnClickListener(view -> openNote(entry));
        actions.addView(open, new LinearLayout.LayoutParams(0, dp(38), 1f));
        Button more = pillButton("⋮", BUTTON_QUIET);
        more.setTextSize(16);
        more.setContentDescription("笔记更多操作：重命名、导出、转为格式笔记、删除");
        more.setOnClickListener(view -> showNoteMoreMenu(entry));
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(44), dp(38));
        moreParams.leftMargin = dp(8);
        actions.addView(more, moreParams);
        LinearLayout.LayoutParams actionsParams = matchWrap();
        actionsParams.topMargin = dp(16);
        card.addView(actions, actionsParams);
        return card;
    }

    /**
     * Asks for a title and the paper to use.
     *
     * <p>Paper is chosen here and not afterwards: strokes are stored in world
     * coordinates and never reflow, so changing the page ratio later would move
     * existing ink relative to its page while text re-paginated independently.
     */
    /**
     * Preset-cover chooser shared by the note more-menu and the new-note
     * dialog. A null {@code noteId} means the note does not exist yet; the
     * chosen bitmap is parked on {@link #pendingNewNoteCover} instead.
     */
    private void showCoverPicker(String noteId) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(14), dp(24), dp(6));

        body.addView(sectionCaption("内置封面"));
        body.addView(horizontalThumbStrip(CoverStore.builtinPresets(), noteId),
                matchWrap());

        List<CoverStore.Preset> userPresets = CoverStore.userPresets(this);
        if (!userPresets.isEmpty()) {
            TextView userLabel = sectionCaption("我的预设");
            userLabel.setPadding(0, dp(14), 0, 0);
            body.addView(userLabel);
            body.addView(horizontalThumbStrip(userPresets, noteId), matchWrap());
        }

        Button importButton = pillButton("从相册导入封面…", BUTTON_TONAL);
        importButton.setOnClickListener(view -> launchImportCover(noteId));
        LinearLayout.LayoutParams importParams = matchWrap();
        importParams.topMargin = dp(16);
        body.addView(importButton, importParams);
        if (noteId == null) {
            TextView hint = text("导入的图片可以在下一步加入预设，供以后新建时选用。",
                    11, FAINT_TEXT);
            hint.setPadding(0, dp(8), 0, 0);
            body.addView(hint);
        }

        android.app.Dialog dialog = new AlertDialog.Builder(this)
                .setTitle(noteId == null ? "为新笔记选封面" : "更换封面")
                .setView(body)
                .setNegativeButton("完成", null)
                .create();
        dialog.setOnDismissListener(ignored -> coverPickerDialog = null);
        coverPickerDialog = dialog;
        dialog.show();
    }

    private View horizontalThumbStrip(List<CoverStore.Preset> presets, String noteId) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int padding = dp(4);
        row.setPadding(0, padding, 0, 0);
        for (CoverStore.Preset preset : presets) {
            row.addView(coverThumb(preset.bitmap, preset.label, () -> {
                Bitmap copy = preset.bitmap.copy(preset.bitmap.getConfig(), false);
                applyCoverChoice(noteId, copy, preset.label);
                if (coverPickerDialog != null) {
                    coverPickerDialog.dismiss();
                }
            }));
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /** Small rounded preview used by every cover surface. */
    private View coverThumb(Bitmap bitmap, String label, Runnable onPicked) {
        FrameLayout frame = new FrameLayout(this);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), CARD_STROKE);
        frame.setBackground(background);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
            }
        });
        frame.setClipToOutline(true);
        ImageView image = new ImageView(this);
        image.setImageBitmap(bitmap);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription("封面：" + label);
        frame.setOnClickListener(view -> onPicked.run());
        frame.setForeground(applyThumbRipple());
        int pad = dp(2);
        frame.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(104), dp(66));
        params.rightMargin = dp(8);
        frame.setLayoutParams(params);
        return frame;
    }

    private RippleDrawable applyThumbRipple() {
        GradientDrawable mask = roundedBackground(Color.WHITE, Color.TRANSPARENT, 10f);
        return new RippleDrawable(
                ColorStateList.valueOf(Color.argb(30, 23, 33, 43)), null, mask);
    }

    private void applyCoverChoice(String noteId, Bitmap bitmap, String label) {
        if (noteId == null) {
            pendingNewNoteCover = bitmap;
            pendingNewNoteCoverLabel = label;
            updateNewNoteCoverStatus();
            Toast.makeText(this, "已选封面：" + label, Toast.LENGTH_SHORT).show();
            return;
        }
        storageExecutor.execute(() -> {
            try {
                CoverStore.assign(this, noteId, bitmap);
                runOnUiThread(this::refreshBookshelf);
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "封面保存失败：" + safeError(error), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void promptAddToPresets(Bitmap bitmap) {
        new AlertDialog.Builder(this)
                .setTitle("把这张图加入预设封面？")
                .setMessage("加入后，新建笔记和其他笔记都能直接选用它。")
                .setNegativeButton("不用", null)
                .setPositiveButton("加入预设", (dialog, which) ->
                        storageExecutor.execute(() -> {
                    try {
                        CoverStore.addToPresets(this, bitmap);
                        runOnUiThread(() -> Toast.makeText(this,
                                "已加入预设封面", Toast.LENGTH_SHORT).show());
                    } catch (Exception error) {
                        runOnUiThread(() -> Toast.makeText(this,
                                "加入预设失败：" + safeError(error), Toast.LENGTH_LONG).show());
                    }
                }))
                .show();
    }

    private void launchImportCover(String noteId) {
        pendingCoverNoteId = noteId;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_IMPORT_COVER);
    }

    private void launchImportImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_IMPORT_IMAGE);
    }

    /** Decodes a picked image URI with sampling so huge photos stay cheap. */
    private Bitmap decodeImageUri(android.net.Uri uri, int targetWidth) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (java.io.InputStream boundsStream = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(boundsStream, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1,
                bounds.outWidth / Math.max(1, targetWidth));
        try (java.io.InputStream stream = getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(stream, null, options);
        }
    }

    private void showCreateNoteDialog() {
        EditText input = createNoteTitleInput(
                pendingNewNoteTitle.isEmpty() ? "未命名笔记" : pendingNewNoteTitle,
                "例如：数学笔记");
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                pendingNewNoteTitle = s.toString();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
        PageStyleChoice choice = new PageStyleChoice();
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(createNoteDialogBodyWithStyle(input, choice), matchWrap());

        TextView coverHeader = text("封面", 12, SECONDARY_TEXT);
        coverHeader.setPadding(dp(24), dp(12), dp(24), 0);
        body.addView(coverHeader);

        HorizontalScrollView strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(20), dp(6), dp(4), 0);
        Button noneButton = pillButton("不用封面", pendingNewNoteCover == null
                ? BUTTON_TONAL : BUTTON_QUIET);
        noneButton.setOnClickListener(view -> {
            pendingNewNoteCover = null;
            pendingNewNoteCoverLabel = "";
            updateNewNoteCoverStatus();
        });
        LinearLayout.LayoutParams noneParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(66));
        noneParams.rightMargin = dp(8);
        row.addView(noneButton, noneParams);
        List<CoverStore.Preset> presets = CoverStore.builtinPresets();
        presets.addAll(CoverStore.userPresets(this));
        for (CoverStore.Preset preset : presets) {
            row.addView(coverThumb(preset.bitmap, preset.label, () -> {
                Bitmap copy = preset.bitmap.copy(preset.bitmap.getConfig(), false);
                pendingNewNoteCover = copy;
                pendingNewNoteCoverLabel = preset.label;
                updateNewNoteCoverStatus();
            }));
        }
        Button moreButton = pillButton("更多…", BUTTON_QUIET);
        moreButton.setOnClickListener(view -> showCoverPicker(null));
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(66));
        moreParams.leftMargin = dp(0);
        row.addView(moreButton, moreParams);
        strip.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(strip, matchWrap());

        newNoteCoverStatus = sectionCaption("封面：未使用");
        newNoteCoverStatus.setPadding(dp(24), dp(4), dp(24), dp(2));
        body.addView(newNoteCoverStatus);
        updateNewNoteCoverStatus();

        final Bitmap chosenCover = pendingNewNoteCover;
        new AlertDialog.Builder(this)
                .setTitle("新建笔记")
                .setView(body)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", (dialog, which) ->
                        createNote(input.getText().toString(), choice.build(), chosenCover))
                .show();
    }

    private void updateNewNoteCoverStatus() {
        if (newNoteCoverStatus == null) {
            return;
        }
        String shown;
        if (pendingNewNoteCover == null) {
            shown = "未使用";
        } else if (pendingNewNoteCoverLabel.isEmpty()) {
            shown = "自定义图片";
        } else {
            shown = pendingNewNoteCoverLabel;
        }
        newNoteCoverStatus.setText("封面：" + shown);
    }

    /** Mutable selection backing the new-note style picker. */
    private static final class PageStyleChoice {
        PageStyle.Paper paper = PageStyle.Paper.RULED;
        PageStyle.Ratio ratio = PageStyle.Ratio.SCREEN;
        boolean landscape;

        PageStyle build() {
            return new PageStyle(paper, ratio, landscape);
        }
    }

    private View createNoteDialogBodyWithStyle(EditText input, PageStyleChoice choice) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(12), dp(24), dp(10));
        body.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(14);
        body.addView(row, rowParams);

        PageStylePreviewView preview = new PageStylePreviewView(this);
        preview.setStyle(choice.build());
        LinearLayout.LayoutParams previewParams =
                new LinearLayout.LayoutParams(dp(104), dp(140));
        previewParams.rightMargin = dp(16);
        row.addView(preview, previewParams);

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        row.addView(options, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView styleLabel = text("纸张", 12, Color.rgb(103, 116, 126));
        options.addView(styleLabel);
        options.addView(styleSegmentedRow(
                new String[]{"横线", "白纸", "方格", "点阵"},
                index -> {
                    choice.paper = index == 1 ? PageStyle.Paper.BLANK
                            : index == 2 ? PageStyle.Paper.GRID
                            : index == 3 ? PageStyle.Paper.DOTTED
                            : PageStyle.Paper.RULED;
                    preview.setStyle(choice.build());
                }, 0));

        TextView ratioLabel = text("比例", 12, Color.rgb(103, 116, 126));
        ratioLabel.setPadding(0, dp(12), 0, 0);
        options.addView(ratioLabel);
        options.addView(styleSegmentedRow(
                new String[]{"适应屏幕", "A4"},
                index -> {
                    choice.ratio = index == 1 ? PageStyle.Ratio.A4 : PageStyle.Ratio.SCREEN;
                    preview.setStyle(choice.build());
                }, 0));

        TextView orientationLabel = text("方向", 12, Color.rgb(103, 116, 126));
        orientationLabel.setPadding(0, dp(12), 0, 0);
        options.addView(orientationLabel);
        options.addView(styleSegmentedRow(
                new String[]{"竖向", "横向"},
                index -> {
                    choice.landscape = index == 1;
                    preview.setStyle(choice.build());
                }, 0));

        TextView helper = text("创建后纸张样式不可更改", 12, Color.rgb(103, 116, 126));
        helper.setPadding(dp(4), dp(14), dp(4), dp(4));
        body.addView(helper);
        return body;
    }

    /** A row of mutually exclusive chips; reports the chosen index. */
    private View styleSegmentedRow(String[] labels, IntConsumer onSelected,
                                   int initialIndex) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView[] chips = new TextView[labels.length];
        for (int index = 0; index < labels.length; index++) {
            TextView chip = text(labels[index], 13, INK_COLOR);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(9), dp(6), dp(9), dp(6));
            chip.setClickable(true);
            chip.setFocusable(true);
            chips[index] = chip;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (index > 0) {
                params.leftMargin = dp(6);
            }
            row.addView(chip, params);
            final int selectedIndex = index;
            chip.setOnClickListener(view -> {
                for (int inner = 0; inner < chips.length; inner++) {
                    applyChipAppearance(chips[inner], inner == selectedIndex);
                }
                onSelected.accept(selectedIndex);
            });
        }
        for (int index = 0; index < chips.length; index++) {
            applyChipAppearance(chips[index], index == initialIndex);
        }
        return row;
    }

    private void applyChipAppearance(TextView chip, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(7));
        background.setColor(selected ? Color.argb(30, 40, 94, 168) : Color.TRANSPARENT);
        background.setStroke(dp(1), selected
                ? Color.rgb(40, 94, 168) : Color.rgb(205, 213, 220));
        chip.setBackground(background);
        chip.setTextColor(selected ? Color.rgb(40, 94, 168) : INK_COLOR);
    }

    private void createNote(String title, PageStyle style) {
        createNote(title, style, null);
    }

    /** Creates the note, then attaches an optional cover before first open. */
    private void createNote(String title, PageStyle style, Bitmap cover) {
        storageExecutor.execute(() -> {
            try {
                NoteStore.Entry entry = NoteStore.create(this, title);
                if (cover != null) {
                    CoverStore.assign(this, entry.id, cover);
                }
                runOnUiThread(() -> {
                    pendingNewNoteCover = null;
                    pendingNewNoteCoverLabel = "";
                    pendingNewNoteTitle = "";
                    openNote(entry, style);
                });
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "新建失败：" + safeError(error), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void openNote(NoteStore.Entry entry) {
        openNote(entry, null);
    }

    /**
     * @param styleForNewNote paper to apply to a freshly created note, or null when
     *                        opening an existing one, whose style comes from storage
     */
    private void openNote(NoteStore.Entry entry, PageStyle styleForNewNote) {
        bookshelfLoadGeneration += 1;
        currentNoteId = entry.id;
        currentNoteTitle = entry.title;
        editorVisible = true;
        restoreCompleted = false;
        pendingPageStyle = styleForNewNote;
        setContentView(createContentView());
        restoreDocument();
    }

    /** Inset, rounded plate showing a note's cover on its shelf card. */
    private View coverPlate(Bitmap cover) {
        FrameLayout plate = new FrameLayout(this);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(244, 241, 232));
        background.setCornerRadius(dp(12));
        plate.setBackground(background);
        plate.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
            }
        });
        plate.setClipToOutline(true);
        ImageView image = new ImageView(this);
        image.setImageBitmap(cover);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription("笔记封面");
        plate.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return plate;
    }

    private void showNoteMoreMenu(NoteStore.Entry entry) {
        // Long-press and the ⋮ button both land here; the list adapts to whether
        // the note currently has a cover.
        List<String> items = new ArrayList<>(java.util.Arrays.asList(
                "重命名", "更换封面", "导出", "转为格式笔记", "删除"));
        if (CoverStore.has(this, entry.id)) {
            items.add(2, "移除封面");
        }
        final List<String> labels = items;
        new AlertDialog.Builder(this)
                .setTitle(entry.title)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    String label = labels.get(which);
                    if ("重命名".equals(label)) {
                        showRenameNoteDialog(entry);
                    } else if ("更换封面".equals(label)) {
                        showCoverPicker(entry.id);
                    } else if ("移除封面".equals(label)) {
                        CoverStore.remove(this, entry.id);
                        refreshBookshelf();
                        Toast.makeText(this, "已移除封面", Toast.LENGTH_SHORT).show();
                    } else if ("导出".equals(label)) {
                        launchExportNote(entry);
                    } else if ("转为格式笔记".equals(label)) {
                        openNoteThenDigitize(entry);
                    } else {
                        confirmDeleteNote(entry);
                    }
                })
                .show();
    }

    private void showRenameNoteDialog(NoteStore.Entry entry) {
        EditText input = createNoteTitleInput(entry.title, "输入新的笔记名称");
        new AlertDialog.Builder(this)
                .setTitle("重命名笔记")
                .setView(createNoteTitleDialogBody(input, "当前名称：" + entry.title))
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String title = input.getText().toString();
                    storageExecutor.execute(() -> {
                        try {
                            NoteStore.rename(this, entry.id, title);
                            runOnUiThread(this::refreshBookshelf);
                        } catch (Exception error) {
                            runOnUiThread(() -> Toast.makeText(this,
                                    "重命名失败：" + safeError(error), Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .show();
    }

    private EditText createNoteTitleInput(String value, String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setHint(hint);
        input.setTextSize(18);
        input.setTextColor(INK_COLOR);
        input.setHintTextColor(Color.rgb(133, 143, 151));
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setMinHeight(dp(58));
        input.setPadding(dp(16), 0, dp(16), 0);
        input.setSelectAllOnFocus(true);
        input.setBackground(roundedBackground(Color.rgb(253, 252, 248),
                Color.rgb(188, 199, 207), 12));
        input.requestFocus();
        return input;
    }

    private View createNoteTitleDialogBody(EditText input, String helperText) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(12), dp(24), dp(10));
        body.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        TextView helper = text(helperText, 12, Color.rgb(103, 116, 126));
        helper.setPadding(dp(4), dp(10), dp(4), dp(4));
        helper.setMinHeight(dp(32));
        body.addView(helper, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return body;
    }

    private void confirmDeleteNote(NoteStore.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle("删除“" + entry.title + "”？")
                .setMessage("删除后将从本机书架移除，无法在应用内撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> storageExecutor.execute(() -> {
                    try {
                        CoverStore.remove(this, entry.id);
                        NoteStore.delete(this, entry.id);
                        runOnUiThread(this::refreshBookshelf);
                    } catch (Exception error) {
                        runOnUiThread(() -> Toast.makeText(this,
                                "删除失败：" + safeError(error), Toast.LENGTH_LONG).show());
                    }
                }))
                .show();
    }

    private View createContentView() {
        disposeTextBoxViews();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SURFACE_COLOR);

        root.addView(createHeader(), matchWrap());
        root.addView(createToolbar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        canvasView = new NoteCanvasView(this);
        canvasView.setEnabled(false);
        canvasFrame = new FrameLayout(this);
        canvasFrame.setClipChildren(true);
        canvasFrame.addView(canvasView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams canvasParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(canvasFrame, canvasParams);

        root.addView(createStatusBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        canvasView.setListener(this);
        canvasView.setSelectedColor(selectedInkColor);
        canvasView.setSelectedWidthDp(penWidthDp);
        canvasView.setEraserSizeDp(eraserSizeDp);
        canvasView.setPenOnly(penOnlyEnabled);
        paletteButton.setAccentColor(selectedInkColor);
        penButton.setAccentColor(selectedInkColor);
        updateSwatchSelection();
        penOnlyButton.setSelected(penOnlyEnabled);
        selectTool(NoteCanvasView.Tool.PEN);
        onInkStatsChanged(0, 0, 0, "画笔就绪");
        onSelectionChanged(0, false, false);
        onViewportChanged(1, 1, 100, "单指拖动画布 · 双指缩放");

        appFrame = new FrameLayout(this);
        appFrame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        aiCard = createAiCard();
        float savedWidthDp = Math.max(330f, Math.min(900f,
                preferences.getFloat("aiCardWidthDp", 420f)));
        float savedHeightDp = Math.max(380f, Math.min(1100f,
                preferences.getFloat("aiCardHeightDp", 540f)));
        aiCardExpandedWidthPx = dp(savedWidthDp);
        aiCardExpandedHeightPx = dp(savedHeightDp);
        FrameLayout.LayoutParams aiCardParams = new FrameLayout.LayoutParams(
                aiCardExpandedWidthPx, aiCardExpandedHeightPx);
        aiCardParams.gravity = Gravity.TOP | Gravity.START;
        appFrame.addView(aiCard, aiCardParams);
        aiCard.setVisibility(View.GONE);
        return appFrame;
    }

    private View createHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(12), dp(20), dp(10));

        ToolIconButton back = iconButton(ToolIconButton.Icon.BOOKSHELF,
                "返回书架", view -> leaveEditorToBookshelf());
        header.addView(back);

        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(currentNoteTitle == null ? "未命名笔记" : currentNoteTitle,
                22, INK_COLOR);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleGroup.addView(title);
        titleGroup.addView(text("PadNote · 单指移动 · 双指缩放 · 底部继续滚动加页",
                12, Color.rgb(116, 128, 139)));
        header.addView(titleGroup, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statsView = text("0 笔 · 0 点", 13, Color.rgb(89, 102, 114));
        header.addView(statsView);
        return header;
    }

    private View createToolbar() {
        colorSwatches.clear();
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(TOOLBAR_COLOR);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(6), dp(10), dp(6));

        penButton = iconButton(ToolIconButton.Icon.PEN, "画笔设置", view -> {
            selectTool(NoteCanvasView.Tool.PEN);
            showToolSettingsCard(NoteCanvasView.Tool.PEN, penButton);
        });
        textButton = iconButton(ToolIconButton.Icon.TEXT_BOX,
                "文字笔：点击页面插入", view -> selectTool(NoteCanvasView.Tool.TEXT));
        eraserButton = iconButton(ToolIconButton.Icon.ERASER, "局部橡皮设置", view -> {
            selectTool(NoteCanvasView.Tool.ERASER);
            showToolSettingsCard(NoteCanvasView.Tool.ERASER, eraserButton);
        });
        lassoButton = iconButton(ToolIconButton.Icon.LASSO, "套索选择", view -> selectTool(NoteCanvasView.Tool.LASSO));
        highlighterButton = iconButton(ToolIconButton.Icon.HIGHLIGHTER, "高亮笔设置", view -> {
            selectTool(NoteCanvasView.Tool.HIGHLIGHTER);
            showToolSettingsCard(NoteCanvasView.Tool.HIGHLIGHTER, highlighterButton);
        });
        highlighterButton.setAccentColor(highlighterColor);
        shapeButton = iconButton(ToolIconButton.Icon.SHAPE, "几何图形：拖动绘制矩形", view -> {
            selectTool(NoteCanvasView.Tool.SHAPE);
            new AlertDialog.Builder(this).setTitle("几何图形")
                    .setItems(new String[]{"矩形", "直线", "椭圆"}, (dialog, which) -> {
                        canvasView.setShapeType(which);
                        canvasView.setTool(NoteCanvasView.Tool.SHAPE);
                    }).show();
        });
        row.addView(penButton);
        row.addView(highlighterButton);
        row.addView(shapeButton);
        row.addView(textButton);
        row.addView(eraserButton);
        row.addView(lassoButton);
        aiButton = iconButton(ToolIconButton.Icon.AI, "打开 AI 对话卡", view -> openAiCardForSelection());
        row.addView(aiButton);
        row.addView(separator());

        paletteButton = iconButton(ToolIconButton.Icon.PALETTE, "打开调色盘", view -> showColorPalette());
        row.addView(paletteButton);
        for (int index = 0; index < PRESET_COLORS.length; index++) {
            int color = PRESET_COLORS[index];
            ColorSwatchButton swatch = new ColorSwatchButton(
                    this, color, "使用" + PRESET_COLOR_NAMES[index]);
            swatch.setOnClickListener(view -> applyInkColor(color));
            colorSwatches.add(swatch);
            LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(dp(42), dp(46));
            row.addView(swatch, swatchParams);
        }

        row.addView(separator());
        undoButton = iconButton(ToolIconButton.Icon.UNDO, "撤销", view -> canvasView.undo());
        redoButton = iconButton(ToolIconButton.Icon.REDO, "重做", view -> canvasView.redo());
        row.addView(undoButton);
        row.addView(redoButton);

        row.addView(separator());
        duplicateButton = iconButton(ToolIconButton.Icon.DUPLICATE, "复制选区", view -> canvasView.duplicateSelection());
        deleteSelectionButton = iconButton(ToolIconButton.Icon.DELETE, "删除选区", view -> canvasView.deleteSelection());
        cancelSelectionButton = iconButton(ToolIconButton.Icon.CANCEL, "取消选择", view -> canvasView.cancelSelection());
        row.addView(duplicateButton);
        row.addView(deleteSelectionButton);
        row.addView(cancelSelectionButton);

        row.addView(separator());
        row.addView(iconButton(ToolIconButton.Icon.CLEAR, "清空笔记", view -> confirmClear()));
        row.addView(iconButton(ToolIconButton.Icon.PAGE_ADD, "添加新页面", view -> canvasView.addPage()));
        row.addView(iconButton(ToolIconButton.Icon.PAGE_MENU, "页面管理", view -> showPageActions()));
        row.addView(iconButton(ToolIconButton.Icon.IMAGE, "插入图片", view -> launchImportImage()));
        row.addView(iconButton(ToolIconButton.Icon.SAVE, "立即保存", view -> {
            commitInlineTextEditorExcept(null);
            handler.removeCallbacks(delayedSave);
            saveDocument(true);
        }));
        row.addView(iconButton(ToolIconButton.Icon.EXPORT, "导出笔记", view -> exportCurrentNote()));
        penOnlyButton = iconButton(ToolIconButton.Icon.PEN_ONLY, "仅手写笔模式", view -> togglePenOnly());
        row.addView(penOnlyButton);

        scroll.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private void showPageActions() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(dp(12), dp(8), dp(12), dp(8));
        final AlertDialog[] pageDialog = new AlertDialog[1];
        for (int index = 0; index < canvasView.getPageCount(); index++) {
            final int page = index;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            byte[] png = canvasView.renderPagePng(page, 220);
            if (png != null) {
                ImageView preview = new ImageView(this);
                preview.setImageBitmap(BitmapFactory.decodeByteArray(png, 0, png.length));
                preview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                preview.setBackgroundColor(Color.rgb(245, 244, 239));
                preview.setContentDescription("第 " + (page + 1) + " 页缩略图");
                preview.setOnClickListener(view -> canvasView.goToPage(page));
                item.addView(preview, new LinearLayout.LayoutParams(dp(150), dp(190)));
            }
            TextView label = text("第 " + (page + 1) + " 页", 12, INK_COLOR);
            label.setGravity(Gravity.CENTER);
            item.addView(label, new LinearLayout.LayoutParams(dp(150), dp(30)));
            Button copy = pillButton("复制", BUTTON_TONAL);
            copy.setOnClickListener(view -> { canvasView.duplicatePage(page); pageDialog[0].dismiss(); showPageActions(); });
            Button delete = pillButton("删除", BUTTON_DANGER);
            delete.setOnClickListener(view -> { canvasView.deletePage(page); pageDialog[0].dismiss(); showPageActions(); });
            Button up = pillButton("↑", BUTTON_QUIET);
            up.setOnClickListener(view -> { canvasView.movePage(page, page - 1); pageDialog[0].dismiss(); showPageActions(); });
            Button down = pillButton("↓", BUTTON_QUIET);
            down.setOnClickListener(view -> { canvasView.movePage(page, page + 1); pageDialog[0].dismiss(); showPageActions(); });
            LinearLayout actions = new LinearLayout(this);
            actions.addView(copy, new LinearLayout.LayoutParams(0, dp(36), 1f));
            actions.addView(delete, new LinearLayout.LayoutParams(0, dp(36), 1f));
            item.addView(actions, new LinearLayout.LayoutParams(dp(150), dp(40)));
            LinearLayout reorder = new LinearLayout(this);
            reorder.addView(up, new LinearLayout.LayoutParams(0, dp(34), 1f));
            reorder.addView(down, new LinearLayout.LayoutParams(0, dp(34), 1f));
            item.addView(reorder, new LinearLayout.LayoutParams(dp(150), dp(38)));
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT);
            itemParams.setMargins(dp(4), 0, dp(4), 0);
            strip.addView(item, itemParams);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(strip);
        pageDialog[0] = new AlertDialog.Builder(this).setTitle("页面缩略图与管理").setView(scroll)
                .setPositiveButton("关闭", null).create();
        pageDialog[0].show();
    }

    private void showTextBoxEditor(NoteTextBox existing) {
        showTextBoxEditor(existing, false);
    }

    @SuppressLint({"SetTextI18n", "HardcodedText"})
    private void showTextBoxEditor(NoteTextBox existing, boolean newlyInserted) {
        if (!restoreCompleted || canvasView == null) {
            Toast.makeText(this, "笔记仍在载入，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        NoteTextBox.Format initialFormat = existing == null
                ? NoteTextBox.Format.LATEX : existing.format;
        NoteTextBox.Format[] selectedFormat = new NoteTextBox.Format[]{initialFormat};
        CompiledTextWebView[] previewView = new CompiledTextWebView[1];
        boolean[] insertionCompleted = new boolean[]{false};

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(8), dp(24), dp(10));
        body.setMinimumWidth(dp(560));

        TextView formatTitle = text("编译格式", 13, Color.rgb(89, 102, 114));
        body.addView(formatTitle);
        LinearLayout formatRow = new LinearLayout(this);
        formatRow.setOrientation(LinearLayout.HORIZONTAL);
        formatRow.setPadding(0, dp(7), 0, dp(10));
        Button latexButton = new Button(this);
        latexButton.setText("LaTeX（默认）");
        latexButton.setAllCaps(false);
        Button markdownButton = new Button(this);
        markdownButton.setText("Markdown");
        markdownButton.setAllCaps(false);
        formatRow.addView(latexButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams markdownParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        markdownParams.setMargins(dp(10), 0, 0, 0);
        formatRow.addView(markdownButton, markdownParams);
        body.addView(formatRow);

        EditText sourceInput = new EditText(this);
        sourceInput.setText(existing == null ? "" : existing.source);
        sourceInput.setHint(initialFormat == NoteTextBox.Format.LATEX
                ? "输入 LaTeX，例如：\\frac{a}{b} 或 f_n\\longrightarrow 0"
                : "输入 Markdown，可使用标题、列表、加粗、代码和 $...$ 公式");
        sourceInput.setTextSize(16);
        sourceInput.setTextColor(INK_COLOR);
        sourceInput.setHintTextColor(Color.rgb(133, 143, 151));
        sourceInput.setGravity(Gravity.TOP | Gravity.START);
        sourceInput.setTypeface(Typeface.MONOSPACE);
        sourceInput.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        sourceInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100_000)});
        sourceInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        sourceInput.setBackground(roundedBackground(Color.rgb(253, 252, 248),
                Color.rgb(188, 199, 207), 10));
        body.addView(sourceInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));

        LinearLayout previewTitleRow = new LinearLayout(this);
        previewTitleRow.setGravity(Gravity.CENTER_VERTICAL);
        previewTitleRow.setPadding(0, dp(8), 0, dp(5));
        previewTitleRow.addView(text("实时编译预览 · 输入时自动更新",
                13, Color.rgb(89, 102, 114)),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(previewTitleRow);

        FrameLayout previewHost = new FrameLayout(this);
        previewHost.setBackground(roundedBackground(Color.rgb(251, 250, 246),
                Color.rgb(200, 208, 214), 9));
        body.addView(previewHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(160)));

        Runnable updateFormatButtons = () -> {
            boolean latex = selectedFormat[0] == NoteTextBox.Format.LATEX;
            latexButton.setTextColor(latex ? Color.WHITE : INK_COLOR);
            markdownButton.setTextColor(latex ? INK_COLOR : Color.WHITE);
            latexButton.setBackground(roundedBackground(
                    latex ? Color.rgb(40, 94, 168) : Color.rgb(240, 242, 243),
                    Color.rgb(157, 173, 187), 9));
            markdownButton.setBackground(roundedBackground(
                    latex ? Color.rgb(240, 242, 243) : Color.rgb(40, 94, 168),
                    Color.rgb(157, 173, 187), 9));
            sourceInput.setHint(latex
                    ? "输入 LaTeX，例如：\\frac{a}{b} 或 f_n\\longrightarrow 0"
                    : "输入 Markdown，可使用标题、列表、加粗、代码和 $...$ 公式");
        };
        Runnable renderPreview = () -> {
            if (previewView[0] == null) {
                previewView[0] = new CompiledTextWebView(this, selectedFormat[0],
                        sourceInput.getText().toString());
                previewHost.addView(previewView[0], new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                previewView[0].render(selectedFormat[0], sourceInput.getText().toString());
            }
            if (existing != null) {
                NoteTextBoxView pageObject = textBoxViews.get(existing.id);
                if (pageObject != null) {
                    NoteTextBox draft = existing.copy();
                    draft.format = selectedFormat[0];
                    draft.source = sourceInput.getText().toString();
                    pageObject.showDraft(draft);
                }
            }
        };
        latexButton.setOnClickListener(view -> {
            selectedFormat[0] = NoteTextBox.Format.LATEX;
            updateFormatButtons.run();
            renderPreview.run();
        });
        markdownButton.setOnClickListener(view -> {
            selectedFormat[0] = NoteTextBox.Format.MARKDOWN;
            updateFormatButtons.run();
            renderPreview.run();
        });
        Runnable[] scheduledPreview = new Runnable[1];
        sourceInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (scheduledPreview[0] != null) {
                    handler.removeCallbacks(scheduledPreview[0]);
                }
                scheduledPreview[0] = renderPreview;
                handler.postDelayed(renderPreview, 90);
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });
        updateFormatButtons.run();
        renderPreview.run();

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this)
                .setTitle(newlyInserted ? "在此处插入文字" :
                        (existing == null ? "添加文字对象" : "编辑文字对象"))
                .setView(body)
                .setNegativeButton("取消", null)
                .setPositiveButton(newlyInserted ? "完成" :
                        (existing == null ? "添加" : "保存"), null);
        if (existing != null && !newlyInserted) {
            dialogBuilder.setNeutralButton("删除文字对象", null);
        }
        AlertDialog dialog = dialogBuilder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    String source = sourceInput.getText().toString().trim();
                    if (source.isEmpty()) {
                        Toast.makeText(this, "请输入要编译的内容", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (newlyInserted && existing != null) {
                        canvasView.commitNewTextBox(existing.id, selectedFormat[0], source);
                        insertionCompleted[0] = true;
                        selectedTextBoxId = existing.id;
                    } else if (existing == null) {
                        NoteTextBox created = canvasView.addTextBox(selectedFormat[0], source);
                        if (created != null) {
                            selectedTextBoxId = created.id;
                            updateTextBoxOverlays(canvasView.getTextBoxes());
                        }
                    } else {
                        canvasView.updateTextBox(existing.id, selectedFormat[0], source);
                        selectedTextBoxId = existing.id;
                    }
                    dialog.dismiss();
                });
            if (existing != null && !newlyInserted) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                    dialog.dismiss();
                    confirmDeleteTextBox(existing);
                });
            }
            sourceInput.requestFocus();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE |
                                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        });
        dialog.setOnDismissListener(ignored -> {
            if (scheduledPreview[0] != null) {
                handler.removeCallbacks(scheduledPreview[0]);
                scheduledPreview[0] = null;
            }
            if (previewView[0] != null) {
                previewView[0].destroy();
                previewView[0] = null;
            }
            if (newlyInserted && existing != null && !insertionCompleted[0]) {
                if (existing.id.equals(selectedTextBoxId)) {
                    selectedTextBoxId = null;
                }
                canvasView.discardNewTextBox(existing.id);
            }
            if (canvasView != null) {
                updateTextBoxOverlays(canvasView.getTextBoxes());
            }
        });
        dialog.show();
    }

    private void updateTextBoxOverlays(List<NoteTextBox> textBoxes) {
        if (canvasFrame == null || canvasView == null) {
            return;
        }
        // Each NoteTextBoxView owns a WebView, so materialising every fragment of
        // a long flow put dozens of them on screen at once. Only fragments on or
        // next to a visible page get a view; the rest are recycled below and
        // rebuilt when scrolled back into range.
        int firstPage = canvasView.getFirstVisiblePageIndex() - 1;
        int lastPage = canvasView.getLastVisiblePageIndex() + 1;
        Set<String> desiredIds = new HashSet<>();
        for (NoteTextBox textBox : textBoxes) {
            boolean nearViewport = textBox.pageIndex >= firstPage &&
                    textBox.pageIndex <= lastPage;
            // Keep the selected flow alive regardless, so an in-progress edit is
            // never torn down by scrolling.
            boolean keepForSelection = textBox.id.equals(selectedTextBoxId) ||
                    textBox.flowId.equals(selectedTextFlowId);
            if (!nearViewport && !keepForSelection) {
                continue;
            }
            desiredIds.add(textBox.id);
            NoteTextBoxView view = textBoxViews.get(textBox.id);
            if (view == null) {
                view = new NoteTextBoxView(this, new NoteTextBoxView.Listener() {
                    @Override
                    public void onSelect(NoteTextBox selected) {
                        commitInlineTextEditorExcept(selected.id);
                        selectedTextBoxId = selected.id;
                        selectedTextFlowId = selected.flowId;
                        updateTextBoxOverlays(canvasView.getTextBoxes());
                        if (pressureView != null) {
                            pressureView.setText("文字对象已选中 · 拖动移动 · 右下角缩放 · 双击直接输入");
                        }
                    }

                    @Override
                    public void onEditRequested(NoteTextBox selected) {
                        selectedTextBoxId = selected.id;
                        selectedTextFlowId = selected.flowId;
                        beginInlineTextEditing(selected.id);
                    }

                    @Override
                    public void onMove(NoteTextBox selected, float worldX, float worldY) {
                        canvasView.moveTextBox(selected.id, worldX, worldY);
                    }

                    @Override
                    public void onMeasuredHeight(NoteTextBox selected,
                                                 float measuredHeightDp) {
                        canvasView.applyMeasuredFragmentHeight(selected.id,
                                measuredHeightDp);
                    }

                    @Override
                    public void onScaleToArea(NoteTextBox selected, float worldWidth,
                                              float worldHeight) {
                        canvasView.scaleTextFlowToArea(selected.id, worldWidth,
                                worldHeight);
                    }

                    @Override
                    public void onDragPreview(NoteTextBox selected, float worldX,
                                              float worldY) {
                        canvasView.previewTextBoxDrag(selected.id, worldX, worldY);
                    }

                    @Override
                    public void onFontSizeChanged(NoteTextBox selected, float fontSizeSp) {
                        canvasView.setTextFlowFontSize(selected.id, fontSizeSp);
                    }

                    @Override
                    public void onDragPreviewEnded() {
                        canvasView.endTextBoxDragPreview();
                    }

                    @Override
                    public void onResize(NoteTextBox selected, float worldWidth,
                                         float worldHeight) {
                        canvasView.resizeTextBox(selected.id, worldWidth, worldHeight);
                    }

                    @Override
                    public void onCommit(NoteTextBox selected, NoteTextBox.Format format,
                                         String source, float fontSizeSp,
                                         float lineHeight) {
                        if (newlyInsertedTextBoxIds.remove(selected.id)) {
                            canvasView.commitNewTextBox(selected.id, format, source,
                                    fontSizeSp, lineHeight);
                        } else {
                            canvasView.updateTextBox(selected.id, format, source,
                                    fontSizeSp, lineHeight);
                        }
                        selectedTextBoxId = null;
                        selectedTextFlowId = null;
                        updateTextBoxOverlays(canvasView.getTextBoxes());
                        if (pressureView != null) {
                            pressureView.setText("文字已自动排版 · 用套索选中后可继续编辑");
                        }
                    }

                    @Override
                    public void onCancel(NoteTextBox selected) {
                        if (newlyInsertedTextBoxIds.remove(selected.id)) {
                            selectedTextBoxId = null;
                            selectedTextFlowId = null;
                            canvasView.discardNewTextBox(selected.id);
                        } else {
                            selectedTextBoxId = null;
                            selectedTextFlowId = null;
                            updateTextBoxOverlays(canvasView.getTextBoxes());
                        }
                    }

                    @Override
                    public void onDelete(NoteTextBox selected) {
                        if (newlyInsertedTextBoxIds.remove(selected.id)) {
                            selectedTextBoxId = null;
                            selectedTextFlowId = null;
                            canvasView.discardNewTextBox(selected.id);
                        } else {
                            confirmDeleteTextBox(selected);
                        }
                    }
                });
                textBoxViews.put(textBox.id, view);
                canvasFrame.addView(view, new FrameLayout.LayoutParams(dp(360), dp(180)));
            }
            view.bind(textBox, canvasView.getViewportScale(),
                    canvasView.getViewportPanX(), canvasView.getViewportPanY(),
                    textBox.id.equals(selectedTextBoxId) ||
                            textBox.flowId.equals(selectedTextFlowId));
        }
        List<String> removedIds = new ArrayList<>();
        for (String id : textBoxViews.keySet()) {
            if (!desiredIds.contains(id)) {
                removedIds.add(id);
            }
        }
        for (String id : removedIds) {
            NoteTextBoxView removed = textBoxViews.remove(id);
            if (removed != null) {
                canvasFrame.removeView(removed);
                removed.dispose();
            }
        }
        if (selectedTextBoxId != null && !desiredIds.contains(selectedTextBoxId)) {
            selectedTextBoxId = null;
        }
        if (selectedTextFlowId != null) {
            boolean flowExists = false;
            for (NoteTextBox textBox : textBoxes) {
                if (selectedTextFlowId.equals(textBox.flowId)) {
                    flowExists = true;
                    break;
                }
            }
            if (!flowExists) {
                selectedTextFlowId = null;
            }
        }
        newlyInsertedTextBoxIds.retainAll(desiredIds);
    }

    private void beginInlineTextEditing(String textBoxId) {
        commitInlineTextEditorExcept(textBoxId);
        canvasView.clearTextBoxSelection();
        selectedTextBoxId = textBoxId;
        NoteTextBoxView boundView = textBoxViews.get(textBoxId);
        NoteTextBox bound = boundView == null ? null : boundView.getBoundTextBox();
        selectedTextFlowId = bound == null ? null : bound.flowId;
        updateTextBoxOverlays(canvasView.getTextBoxes());
        NoteTextBoxView view = textBoxViews.get(textBoxId);
        if (view != null) {
            view.beginInlineEditing();
            if (pressureView != null) {
                pressureView.setText("页内输入 · 上方编辑源码 · 下方实时编译预览");
            }
        }
    }

    private void commitInlineTextEditorExcept(String excludedId) {
        List<NoteTextBoxView> views = new ArrayList<>(textBoxViews.values());
        for (NoteTextBoxView view : views) {
            NoteTextBox bound = view.getBoundTextBox();
            if (view.isInlineEditing() &&
                    (excludedId == null || bound == null || !excludedId.equals(bound.id))) {
                view.commitInlineEditing();
            }
        }
    }

    private void confirmDeleteTextBox(NoteTextBox textBox) {
        new AlertDialog.Builder(this)
                .setTitle(textBox.flowCount > 1
                        ? "删除整个跨页文字流？"
                        : "删除" + textBox.format.displayName() + "文本框？")
                .setMessage(textBox.flowCount > 1
                        ? "将删除这组文字在所有页面上的 " + textBox.flowCount +
                        " 个片段；删除后可以使用撤销恢复。"
                        : "删除后可以使用撤销恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    newlyInsertedTextBoxIds.remove(textBox.id);
                    if (textBox.id.equals(selectedTextBoxId)) {
                        selectedTextBoxId = null;
                    }
                    if (textBox.flowId.equals(selectedTextFlowId)) {
                        selectedTextFlowId = null;
                    }
                    canvasView.deleteTextBox(textBox.id);
                })
                .show();
    }

    private void disposeTextBoxViews() {
        for (NoteTextBoxView view : textBoxViews.values()) {
            if (canvasFrame != null) {
                canvasFrame.removeView(view);
            }
            view.dispose();
        }
        textBoxViews.clear();
        newlyInsertedTextBoxIds.clear();
        selectedTextBoxId = null;
        selectedTextFlowId = null;
        canvasFrame = null;
    }

    private View createStatusBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), 0, dp(16), 0);
        bar.setBackgroundColor(Color.rgb(240, 238, 232));

        saveStatusView = text("尚未书写", 12, Color.rgb(101, 113, 123));
        bar.addView(saveStatusView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pressureView = text("压力 0.000 · 画笔就绪", 12, Color.rgb(101, 113, 123));
        bar.addView(pressureView);
        return bar;
    }

    private ToolIconButton iconButton(ToolIconButton.Icon icon, String description,
                                      View.OnClickListener listener) {
        ToolIconButton button = new ToolIconButton(this, icon, description);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(46), dp(46));
        params.setMargins(dp(2), 0, dp(2), 0);
        button.setLayoutParams(params);
        return button;
    }

    private View separator() {
        View separator = new View(this);
        separator.setBackgroundColor(Color.rgb(208, 214, 218));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(1), dp(28));
        params.setMargins(dp(6), 0, dp(6), 0);
        separator.setLayoutParams(params);
        return separator;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void selectTool(NoteCanvasView.Tool tool) {
        commitInlineTextEditorExcept(null);
        canvasView.setTool(tool);
        canvasView.setHighlighterStyle(highlighterColor, highlighterWidthDp);
        penButton.setSelected(tool == NoteCanvasView.Tool.PEN);
        highlighterButton.setSelected(tool == NoteCanvasView.Tool.HIGHLIGHTER);
        shapeButton.setSelected(tool == NoteCanvasView.Tool.SHAPE);
        textButton.setSelected(tool == NoteCanvasView.Tool.TEXT);
        eraserButton.setSelected(tool == NoteCanvasView.Tool.ERASER);
        lassoButton.setSelected(tool == NoteCanvasView.Tool.LASSO);
    }

    private void showToolSettingsCard(NoteCanvasView.Tool tool, View anchor) {
        if (toolSettingsPopup != null) {
            toolSettingsPopup.dismiss();
        }
        boolean eraser = tool == NoteCanvasView.Tool.ERASER;
        boolean highlighter = tool == NoteCanvasView.Tool.HIGHLIGHTER;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(14));

        TextView valueLabel = text("", 15, INK_COLOR);
        valueLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(valueLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        StrokeWidthPreviewView preview = new StrokeWidthPreviewView(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72));
        previewParams.setMargins(0, dp(12), 0, dp(8));
        card.addView(preview, previewParams);

        SeekBar slider = new SeekBar(this);
        if (eraser) {
            slider.setMax(Math.round(MAX_ERASER_SIZE_DP - MIN_ERASER_SIZE_DP));
            slider.setProgress(Math.round(eraserSizeDp - MIN_ERASER_SIZE_DP));
            slider.setContentDescription("橡皮擦除直径");
        } else if (highlighter) {
            slider.setMax(28);
            slider.setProgress(Math.round(highlighterWidthDp - 4));
            slider.setContentDescription("高亮笔粗细");
        } else {
            slider.setMax(Math.round((MAX_PEN_WIDTH_DP - MIN_PEN_WIDTH_DP) * 10));
            slider.setProgress(Math.round((penWidthDp - MIN_PEN_WIDTH_DP) * 10));
            slider.setContentDescription("画笔粗细");
        }
        card.addView(slider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView hint = text("拖动滑杆调节 · 点卡片外关闭", 12, Color.rgb(108, 119, 128));
        hint.setGravity(Gravity.CENTER);
        card.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Runnable updateCard = () -> {
            if (eraser) {
                valueLabel.setText(String.format(Locale.CHINA, "橡皮直径 · %.0f", eraserSizeDp));
                preview.setPreview(eraserSizeDp, selectedInkColor, true);
            } else if (highlighter) {
                valueLabel.setText(String.format(Locale.CHINA, "高亮笔粗细 · %.0f", highlighterWidthDp));
                preview.setPreview(highlighterWidthDp, 0x69000000 | (highlighterColor & 0xffffff), false);
            } else {
                valueLabel.setText(String.format(Locale.CHINA, "画笔粗细 · %.1f", penWidthDp));
                preview.setPreview(penWidthDp, selectedInkColor, false);
            }
        };
        updateCard.run();

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (eraser) {
                    eraserSizeDp = MIN_ERASER_SIZE_DP + progress;
                    canvasView.setEraserSizeDp(eraserSizeDp);
                } else if (highlighter) {
                    highlighterWidthDp = 4 + progress;
                    canvasView.setHighlighterStyle(highlighterColor, highlighterWidthDp);
                } else {
                    penWidthDp = MIN_PEN_WIDTH_DP + progress / 10f;
                    canvasView.setSelectedWidthDp(penWidthDp);
                }
                updateCard.run();
                if (fromUser) {
                    preferences.edit()
                            .putFloat("penWidthDp", penWidthDp)
                            .putFloat("highlighterWidthDp", highlighterWidthDp)
                            .putFloat("eraserSizeDp", eraserSizeDp)
                            .apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(Color.rgb(253, 252, 249));
        cardBackground.setCornerRadius(dp(18));
        cardBackground.setStroke(dp(1), Color.rgb(216, 221, 224));
        card.setBackground(cardBackground);

        toolSettingsPopup = new PopupWindow(card, dp(340),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        toolSettingsPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        toolSettingsPopup.setOutsideTouchable(true);
        toolSettingsPopup.setElevation(dp(10));
        toolSettingsPopup.setOnDismissListener(() -> toolSettingsPopup = null);
        toolSettingsPopup.showAsDropDown(anchor, -dp(4), dp(3));
    }

    private void showColorPalette() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), 0);

        ColorWheelView wheel = new ColorWheelView(this);
        int activeColor = canvasView.getTool() == NoteCanvasView.Tool.HIGHLIGHTER
                ? highlighterColor : selectedInkColor;
        wheel.setColor(activeColor);
        content.addView(wheel, new LinearLayout.LayoutParams(dp(236), dp(236)));

        LinearLayout brightnessRow = new LinearLayout(this);
        brightnessRow.setOrientation(LinearLayout.HORIZONTAL);
        brightnessRow.setGravity(Gravity.CENTER_VERTICAL);
        SizePreviewView preview = new SizePreviewView(this);
        preview.setPreview(24, 24, selectedInkColor, false);
        brightnessRow.addView(preview, new LinearLayout.LayoutParams(dp(42), dp(42)));
        SeekBar brightness = new SeekBar(this);
        brightness.setMax(100);
        brightness.setProgress(Math.round(wheel.getBrightness() * 100));
        brightness.setContentDescription("颜色明度");
        brightnessRow.addView(brightness, new LinearLayout.LayoutParams(0, dp(48), 1f));
        content.addView(brightnessRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final int[] pendingColor = new int[]{activeColor};
        wheel.setListener(color -> {
            pendingColor[0] = color;
            preview.setPreview(24, 24, color, false);
        });
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    wheel.setBrightness(progress / 100f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("选择墨水颜色")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("使用", (dialog, which) -> applyInkColor(pendingColor[0]))
                .show();
    }

    private void applyInkColor(int color) {
        if (canvasView.getTool() == NoteCanvasView.Tool.HIGHLIGHTER) {
            highlighterColor = color;
            canvasView.setHighlighterStyle(color, highlighterWidthDp);
            highlighterButton.setAccentColor(color);
            preferences.edit().putInt("highlighterColor", color).apply();
            updateSwatchSelection();
            return;
        }
        selectedInkColor = color;
        canvasView.setSelectedColor(color);
        paletteButton.setAccentColor(color);
        penButton.setAccentColor(color);
        updateSwatchSelection();
        preferences.edit().putInt("inkColor", color).apply();
        selectTool(NoteCanvasView.Tool.PEN);
    }

    private void updateSwatchSelection() {
        for (ColorSwatchButton swatch : colorSwatches) {
            swatch.setSelected(swatch.getSwatchColor() == selectedInkColor);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private LinearLayout createAiCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setElevation(dp(14));
        GradientDrawable cardBackground = roundedBackground(
                Color.rgb(252, 251, 247), Color.rgb(190, 202, 214), 18);
        card.setBackground(cardBackground);
        card.setClipToOutline(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(7), dp(6), dp(7));
        header.setBackgroundColor(Color.rgb(40, 94, 168));

        aiCardTitle = text("AI 助手 · 拖动这里", 15, Color.WHITE);
        aiCardTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        aiCardTitle.setGravity(Gravity.CENTER_VERTICAL);
        aiCardTitle.setSingleLine(true);
        aiCardTitle.setOnTouchListener(this::handleAiCardDrag);
        header.addView(aiCardTitle, new LinearLayout.LayoutParams(0, dp(42), 1f));

        TextView settings = aiHeaderControl("⚙", "配置 AI API");
        settings.setOnClickListener(view -> showAiManagerDialog(null));
        header.addView(settings);
        aiMinimizeButton = aiHeaderControl("—", "最小化 AI 卡片");
        aiMinimizeButton.setOnClickListener(view -> setAiCardMinimized(!aiCardMinimized));
        header.addView(aiMinimizeButton);
        TextView close = aiHeaderControl("×", "关闭 AI 卡片");
        close.setOnClickListener(view -> closeAiCard());
        header.addView(close);
        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        aiCardBody = new LinearLayout(this);
        aiCardBody.setOrientation(LinearLayout.VERTICAL);
        aiCardBody.setPadding(dp(14), dp(12), dp(14), dp(12));

        aiSelectionPreview = new ImageView(this);
        aiSelectionPreview.setContentDescription("本次 AI 对话使用的圈选图片");
        aiSelectionPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        aiSelectionPreview.setBackgroundColor(Color.rgb(235, 236, 232));
        aiCardBody.addView(aiSelectionPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(105)));

        aiStatusView = text("等待选择笔记", 12, Color.rgb(93, 105, 115));
        aiStatusView.setPadding(dp(2), dp(7), dp(2), dp(5));
        aiCardBody.addView(aiStatusView);

        LinearLayout outputModeRow = new LinearLayout(this);
        outputModeRow.setOrientation(LinearLayout.HORIZONTAL);
        outputModeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView outputModeLabel = text("回答显示", 12, Color.rgb(93, 105, 115));
        outputModeRow.addView(outputModeLabel,
                new LinearLayout.LayoutParams(0, dp(38), 0.7f));
        aiInlineOutputButton = aiCardButton("写入页面");
        aiInlineOutputButton.setContentDescription("AI 回答默认写入圈选原文附近");
        aiInlineOutputButton.setOnClickListener(view -> setAiOutputInline(true, true));
        outputModeRow.addView(aiInlineOutputButton,
                new LinearLayout.LayoutParams(0, dp(38), 1f));
        aiCardOutputButton = aiCardButton("仅卡片");
        aiCardOutputButton.setContentDescription("AI 回答只显示在对话卡片中");
        aiCardOutputButton.setOnClickListener(view -> setAiOutputInline(false, true));
        LinearLayout.LayoutParams cardModeParams = new LinearLayout.LayoutParams(0, dp(38), 0.85f);
        cardModeParams.setMargins(dp(6), 0, 0, 0);
        outputModeRow.addView(aiCardOutputButton, cardModeParams);
        aiCardBody.addView(outputModeRow);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        aiExplainButton = aiCardButton("讲解");
        aiExplainButton.setOnClickListener(view -> requestAiMessage(
                "请分步骤讲解圈选笔记，保留公式和推导，并明确标记无法辨认的内容。"));
        aiMarkdownButton = aiCardButton("整理 Markdown");
        aiMarkdownButton.setOnClickListener(view -> requestAiMessage(
                "请把圈选笔记整理成结构清晰的 Markdown，保留原意、层级、公式和待办项。"));
        presets.addView(aiExplainButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams markdownParams = new LinearLayout.LayoutParams(0, dp(42), 1.35f);
        markdownParams.setMargins(dp(8), 0, 0, 0);
        presets.addView(aiMarkdownButton, markdownParams);
        aiCardBody.addView(presets);

        aiDiagramButton = aiCardButton("画示意图 · Beta");
        aiDiagramButton.setOnClickListener(view -> requestAiMessage(
                "请把圈选内容中最适合图解的关系画成一张简明示意图，调用 draw_diagram 写入笔记。"
                        + "先查看页面空白，默认放在相关原文下方；不要遮挡手写。标签用中文，辨认不清时先问我。"));
        aiCardBody.addView(aiDiagramButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        // The knowledge-base tools are invisible until used; a one-line hint in
        // the card is the only place users see that cross-note questions exist.
        TextView vaultHint = text("也可以问知识库：「我之前哪本笔记讲过…」",
                11, FAINT_TEXT);
        vaultHint.setPadding(dp(4), dp(5), dp(4), 0);
        aiCardBody.addView(vaultHint);

        aiConversationView = new LinearLayout(this);
        aiConversationView.setOrientation(LinearLayout.VERTICAL);
        aiConversationView.setPadding(0, dp(8), 0, dp(8));
        aiConversationScroll = new ScrollView(this);
        aiConversationScroll.setFillViewport(true);
        aiConversationScroll.addView(aiConversationView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.setMargins(0, dp(4), 0, dp(6));
        aiCardBody.addView(aiConversationScroll, scrollParams);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.BOTTOM);
        aiInputView = new EditText(this);
        aiInputView.setHint("继续提问或输入自定义要求…");
        aiInputView.setTextSize(14);
        aiInputView.setTextColor(INK_COLOR);
        aiInputView.setHintTextColor(Color.rgb(137, 147, 155));
        aiInputView.setGravity(Gravity.TOP | Gravity.START);
        aiInputView.setSingleLine(false);
        aiInputView.setMaxLines(3);
        aiInputView.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        inputRow.addView(aiInputView, new LinearLayout.LayoutParams(0, dp(62), 1f));
        aiSendButton = aiCardButton("发送");
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(74), dp(54));
        sendParams.setMargins(dp(8), 0, 0, 0);
        inputRow.addView(aiSendButton, sendParams);
        aiSendButton.setOnClickListener(view -> {
            String message = aiInputView.getText().toString().trim();
            if (!message.isEmpty()) {
                requestAiMessage(message);
            }
        });
        aiCardBody.addView(inputRow);

        card.addView(aiCardBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        aiResizeHandle = text("↘", 17, Color.rgb(79, 100, 118));
        aiResizeHandle.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        aiResizeHandle.setPadding(0, 0, dp(10), 0);
        aiResizeHandle.setContentDescription("拖动调整 AI 卡片宽高");
        aiResizeHandle.setClickable(true);
        aiResizeHandle.setFocusable(true);
        aiResizeHandle.setOnTouchListener(this::handleAiCardResize);
        card.addView(aiResizeHandle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));
        updateAiOutputModeButtons();
        return card;
    }

    private TextView aiHeaderControl(String symbol, String description) {
        TextView control = text(symbol, 22, Color.WHITE);
        control.setGravity(Gravity.CENTER);
        control.setContentDescription(description);
        control.setClickable(true);
        control.setFocusable(true);
        control.setMinWidth(dp(42));
        control.setMinHeight(dp(42));
        return control;
    }

    private Button aiCardButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(INK_COLOR);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }

    private void setAiOutputInline(boolean inline, boolean persist) {
        aiOutputInline = inline;
        // The switch now gates authority rather than destination: the model always
        // replies in the card, and this decides whether it may also write to the
        // note. Modifying content the user already arranged still needs a separate
        // grant, which v1 never issues.
        aiGrantedPermission = inline
                ? NoteTool.Permission.CREATE_IN_FREE_SPACE
                : NoteTool.Permission.READ_ONLY;
        updateAiOutputModeButtons();
        if (persist) {
            preferences.edit().putBoolean("aiOutputInline", inline).apply();
            if (aiConversationView != null && aiCard != null &&
                    aiCard.getVisibility() == View.VISIBLE) {
                addAiNotice(inline
                        ? "已允许 AI 写入笔记。它会自行判断哪些内容值得留在页面上，"
                        + "其余说明只显示在卡片里。"
                        : "AI 只能读取笔记结构，不会写入页面；回答只显示在卡片中。");
            }
        }
    }

    private void updateAiOutputModeButtons() {
        if (aiInlineOutputButton == null || aiCardOutputButton == null) {
            return;
        }
        styleAiOutputModeButton(aiInlineOutputButton, aiOutputInline);
        styleAiOutputModeButton(aiCardOutputButton, !aiOutputInline);
    }

    private void styleAiOutputModeButton(Button button, boolean selected) {
        button.setTextColor(selected ? Color.WHITE : INK_COLOR);
        button.setBackground(roundedBackground(
                selected ? Color.rgb(40, 94, 168) : Color.rgb(238, 241, 243),
                selected ? Color.rgb(40, 94, 168) : Color.rgb(188, 199, 208), 7));
    }

    private GradientDrawable roundedBackground(int fillColor, int strokeColor, float radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(radiusDp));
        background.setStroke(dp(1), strokeColor);
        return background;
    }

    /**
     * Bounded press ripple for surfaces whose custom background replaced the
     * platform one. Feedback must be instant and visible, or the surface feels
     * dead — a detail users cannot name but always notice (see
     * docs/DESIGN_LANGUAGE.md, rule 9).
     */
    private void applyPressFeedback(View view, float radiusDp) {
        // Content layer must stay null: a filled drawable here paints OVER the
        // view's children (0.17.1 shipped buttons whose labels vanished under
        // exactly such a white rect). The mask only bounds the ripple shape.
        GradientDrawable mask = roundedBackground(Color.WHITE, Color.TRANSPARENT, radiusDp);
        view.setForeground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(30, 23, 33, 43)), null, mask));
    }

    private boolean handleAiCardDrag(View view, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            aiCard.bringToFront();
            aiDragStartRawX = event.getRawX();
            aiDragStartRawY = event.getRawY();
            aiDragStartCardX = aiCard.getX();
            aiDragStartCardY = aiCard.getY();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            moveAiCardTo(aiDragStartCardX + event.getRawX() - aiDragStartRawX,
                    aiDragStartCardY + event.getRawY() - aiDragStartRawY);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            moveAiCardTo(aiCard.getX(), aiCard.getY());
            preferences.edit()
                    .putFloat("aiCardX", aiCard.getX())
                    .putFloat("aiCardY", aiCard.getY())
                    .apply();
            view.performClick();
            return true;
        }
        return false;
    }

    private boolean handleAiCardResize(View view, MotionEvent event) {
        if (aiCardMinimized || aiCard == null || appFrame == null) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            aiCard.bringToFront();
            aiResizeStartRawX = event.getRawX();
            aiResizeStartRawY = event.getRawY();
            aiResizeStartWidth = aiCard.getWidth();
            aiResizeStartHeight = aiCard.getHeight();
            view.getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            int availableWidth = Math.max(dp(240),
                    appFrame.getWidth() - Math.round(aiCard.getX()));
            int availableHeight = Math.max(dp(260),
                    appFrame.getHeight() - Math.round(aiCard.getY()));
            int minWidth = Math.min(dp(330), availableWidth);
            int minHeight = Math.min(dp(380), availableHeight);
            int requestedWidth = Math.round(aiResizeStartWidth +
                    event.getRawX() - aiResizeStartRawX);
            int requestedHeight = Math.round(aiResizeStartHeight +
                    event.getRawY() - aiResizeStartRawY);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) aiCard.getLayoutParams();
            params.width = Math.max(minWidth, Math.min(requestedWidth, availableWidth));
            params.height = Math.max(minHeight, Math.min(requestedHeight, availableHeight));
            aiCard.setLayoutParams(params);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            view.getParent().requestDisallowInterceptTouchEvent(false);
            aiCardExpandedWidthPx = aiCard.getWidth();
            aiCardExpandedHeightPx = aiCard.getHeight();
            float density = getResources().getDisplayMetrics().density;
            preferences.edit()
                    .putFloat("aiCardWidthDp", aiCardExpandedWidthPx / density)
                    .putFloat("aiCardHeightDp", aiCardExpandedHeightPx / density)
                    .apply();
            moveAiCardTo(aiCard.getX(), aiCard.getY());
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                view.performClick();
            }
            return true;
        }
        return false;
    }

    private void moveAiCardTo(float x, float y) {
        if (appFrame == null || aiCard == null) {
            return;
        }
        float maxX = Math.max(0, appFrame.getWidth() - aiCard.getWidth());
        float maxY = Math.max(0, appFrame.getHeight() - aiCard.getHeight());
        aiCard.setX(Math.max(0, Math.min(x, maxX)));
        aiCard.setY(Math.max(0, Math.min(y, maxY)));
    }

    private void openAiCardForSelection() {
        if (canvasView.getSelectionCount() == 0) {
            return;
        }
        NoteCanvasView.AiSelectionSnapshot snapshot = canvasView.renderAiSelection(1600);
        if (snapshot == null) {
            Toast.makeText(this, "无法生成当前选区", Toast.LENGTH_SHORT).show();
            return;
        }
        releaseAiSelectionSnapshot();
        aiSessionSerial += 1;
        aiExecutedToolCallIds.clear();
        aiSelectionSnapshot = snapshot;
        aiResultAnchorBounds = new RectF(snapshot.sourceBounds);
        aiMessages.clear();
        aiSessionTranscript = null;
        aiUploadConfirmed = false;
        setAiBusy(false);
        clearAiConversation();
        aiSelectionPreview.setImageBitmap(snapshot.bitmap);
        String mask = snapshot.lassoMaskApplied ? "套索遮罩已生效" : "矩形选区";
        aiStatusView.setText(String.format(Locale.CHINA,
                "%d × %d · %.1f KB · %s · 首次发送前会确认",
                snapshot.bitmap.getWidth(), snapshot.bitmap.getHeight(),
                snapshot.pngBytes.length / 1024f, mask));
        addAiNotice(aiOutputInline
                ? "选区已载入。回答默认写入原文附近并编译显示；也可切换为“仅卡片”。"
                : "选区已载入。回答当前只显示在对话卡片；可切换为“写入页面”。");
        aiCard.setVisibility(View.VISIBLE);
        setAiCardMinimized(false);
        aiCard.bringToFront();
        aiCard.post(() -> {
            fitExpandedAiCardToFrame();
            float savedX = preferences.getFloat("aiCardX", -1f);
            float savedY = preferences.getFloat("aiCardY", -1f);
            if (savedX < 0 || savedY < 0) {
                savedX = appFrame.getWidth() - aiCard.getWidth() - dp(18);
                savedY = dp(112);
            }
            moveAiCardTo(savedX, savedY);
        });
    }

    private void setAiCardMinimized(boolean minimized) {
        if (aiCard == null) {
            return;
        }
        if (minimized && !aiCardMinimized) {
            aiCardExpandedWidthPx = aiCard.getWidth();
            aiCardExpandedHeightPx = aiCard.getHeight();
        }
        aiCardMinimized = minimized;
        aiCardBody.setVisibility(minimized ? View.GONE : View.VISIBLE);
        aiResizeHandle.setVisibility(minimized ? View.GONE : View.VISIBLE);
        aiMinimizeButton.setText(minimized ? "□" : "—");
        aiMinimizeButton.setContentDescription(minimized ? "恢复 AI 卡片" : "最小化 AI 卡片");
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) aiCard.getLayoutParams();
        params.width = minimized ? dp(280) : Math.max(dp(330), aiCardExpandedWidthPx);
        params.height = minimized ? ViewGroup.LayoutParams.WRAP_CONTENT
                : Math.max(dp(380), aiCardExpandedHeightPx);
        aiCard.setLayoutParams(params);
        updateAiCardTitle();
        aiCard.post(() -> {
            if (!aiCardMinimized) {
                fitExpandedAiCardToFrame();
            }
            moveAiCardTo(aiCard.getX(), aiCard.getY());
        });
    }

    private void fitExpandedAiCardToFrame() {
        if (aiCard == null || appFrame == null || aiCardMinimized ||
                appFrame.getWidth() <= 0 || appFrame.getHeight() <= 0) {
            return;
        }
        int maximumWidth = appFrame.getWidth();
        int maximumHeight = appFrame.getHeight();
        int minimumWidth = Math.min(dp(330), maximumWidth);
        int minimumHeight = Math.min(dp(380), maximumHeight);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) aiCard.getLayoutParams();
        params.width = Math.max(minimumWidth,
                Math.min(Math.max(dp(330), aiCardExpandedWidthPx), maximumWidth));
        params.height = Math.max(minimumHeight,
                Math.min(Math.max(dp(380), aiCardExpandedHeightPx), maximumHeight));
        aiCard.setLayoutParams(params);
    }

    private void updateAiCardTitle() {
        if (aiCardTitle == null) {
            return;
        }
        if (aiBusy) {
            aiCardTitle.setText(R.string.ai_card_busy);
        } else if (aiCardMinimized) {
            aiCardTitle.setText(R.string.ai_card_minimized);
        } else {
            aiCardTitle.setText(R.string.ai_card_drag);
        }
    }

    private void closeAiCard() {
        aiSessionSerial += 1;
        aiExecutedToolCallIds.clear();
        aiMessages.clear();
        aiSessionTranscript = null;
        aiUploadConfirmed = false;
        aiBusy = false;
        releaseAiSelectionSnapshot();
        clearAiConversation();
        if (aiCard != null) {
            aiCard.setVisibility(View.GONE);
        }
    }

    private void releaseAiSelectionSnapshot() {
        if (aiSelectionPreview != null) {
            aiSelectionPreview.setImageDrawable(null);
        }
        if (aiSelectionSnapshot != null && !aiSelectionSnapshot.bitmap.isRecycled()) {
            aiSelectionSnapshot.bitmap.recycle();
        }
        aiSelectionSnapshot = null;
        aiResultAnchorBounds = null;
    }

    private void addAiNotice(String message) {
        TextView notice = text(message, 12, Color.rgb(94, 107, 117));
        notice.setGravity(Gravity.CENTER);
        notice.setPadding(dp(12), dp(8), dp(12), dp(8));
        aiConversationView.addView(notice, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollAiConversationToBottom();
    }

    private void addAiMessageBubble(String role, String message, boolean error) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity("user".equals(role) ? Gravity.END : Gravity.START);
        int fill = error ? Color.rgb(252, 232, 229)
                : ("user".equals(role) ? Color.rgb(218, 230, 247) : Color.WHITE);
        int stroke = error ? Color.rgb(222, 157, 151) : Color.rgb(214, 220, 224);
        View bubble;
        LinearLayout.LayoutParams bubbleParams;
        if (!error && "assistant".equals(role) && AiMathWebView.containsMath(message)) {
            AiMathWebView formulaView = new AiMathWebView(this, message);
            formulaView.setContentDescription("AI 回答；LaTeX 公式已本地可视化");
            formulaView.setBackground(roundedBackground(fill, stroke, 12));
            bubble = formulaView;
            bubbleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        } else {
            TextView textBubble = text(message, 13,
                    error ? Color.rgb(143, 47, 43) : INK_COLOR);
            textBubble.setTextIsSelectable(!"user".equals(role));
            textBubble.setPadding(dp(12), dp(9), dp(12), dp(9));
            textBubble.setBackground(roundedBackground(fill, stroke, 12));
            bubble = textBubble;
            bubbleParams = new LinearLayout.LayoutParams(
                    "assistant".equals(role) ? ViewGroup.LayoutParams.MATCH_PARENT
                            : ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        bubbleParams.setMargins(0, dp(4), 0, dp(4));
        row.addView(bubble, bubbleParams);
        aiConversationView.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollAiConversationToBottom();
    }

    private void presentAiAnswer(String answer) {
        if (!aiOutputInline || canvasView == null || aiSelectionSnapshot == null) {
            addAiMessageBubble("assistant", answer, false);
            aiStatusView.setText("回答完成 · 已显示在卡片 · 可继续追问");
            return;
        }
        RectF anchor = aiResultAnchorBounds == null
                ? aiSelectionSnapshot.sourceBounds : aiResultAnchorBounds;
        NoteTextBox inserted = canvasView.addAiResultTextBox(
                NoteTextBox.Format.MARKDOWN, answer, anchor);
        if (inserted == null) {
            addAiMessageBubble("assistant", answer, false);
            addAiNotice("页面正被操作，回答已暂时显示在卡片中；可稍后复制或重新发送。");
            aiStatusView.setText("页面写入忙 · 已回退到卡片显示");
            return;
        }
        selectedTextBoxId = null;
        selectedTextFlowId = null;
        aiResultAnchorBounds = new RectF(inserted.x, inserted.y,
                inserted.x + inserted.width, inserted.y + inserted.height);
        updateTextBoxOverlays(canvasView.getTextBoxes());
        addAiNotice("回答已写入原文附近并自动跨页排版 · 套索选中任一片段可统一编辑");
        aiStatusView.setText("回答完成 · 已写入页面文本流 · 可恢复卡片继续追问");
        setAiCardMinimized(true);
    }

    private void clearAiConversation() {
        if (aiConversationView == null) {
            return;
        }
        destroyMathViews(aiConversationView);
        aiConversationView.removeAllViews();
    }

    private void destroyMathViews(View view) {
        if (view instanceof AiMathWebView) {
            ((AiMathWebView) view).destroy();
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                destroyMathViews(group.getChildAt(index));
            }
        }
    }

    private void scrollAiConversationToBottom() {
        if (aiConversationScroll != null) {
            aiConversationScroll.post(() -> aiConversationScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void requestAiMessage(String prompt) {
        if (aiBusy || aiSelectionSnapshot == null || prompt.trim().isEmpty()) {
            return;
        }
        final AiConfigStore.Profile profile = aiConfigStore.activeProfile();
        if (profile == null || !profile.structurallyComplete()) {
            showAiManagerDialog(() -> requestAiMessage(prompt));
            return;
        }
        if (!aiUploadConfirmed) {
            showAiUploadConfirmation(profile, prompt);
            return;
        }
        executeAiMessage(profile, prompt);
    }

    private void showAiUploadConfirmation(AiConfigStore.Profile profile, String prompt) {
        String target;
        if (profile.split) {
            target = "转写：" + OpenAiCompatibleClient.resolveChatCompletionsUrl(
                    profile.transcribeEndpoint)
                    + "\n回答：" + OpenAiCompatibleClient.resolveChatCompletionsUrl(
                    profile.answerEndpoint);
        } else {
            target = OpenAiCompatibleClient.resolveChatCompletionsUrl(profile.directEndpoint);
        }
        new AlertDialog.Builder(this)
                .setTitle("确认发送圈选内容？")
                .setMessage(String.format(Locale.CHINA,
                        "将卡片中的 %d × %d PNG（%.1f KB）和本次对话发送至：\n\n%s\n\n只发送圈选遮罩内的笔记，不发送整页。此确认在当前对话内有效。",
                        aiSelectionSnapshot.bitmap.getWidth(), aiSelectionSnapshot.bitmap.getHeight(),
                        aiSelectionSnapshot.pngBytes.length / 1024f, target))
                .setNegativeButton("取消", null)
                .setPositiveButton("确认发送", (dialog, which) -> {
                    aiUploadConfirmed = true;
                    executeAiMessage(profile, prompt);
                })
                .show();
    }

    private void executeAiMessage(AiConfigStore.Profile profile, String prompt) {
        if (aiBusy || aiSelectionSnapshot == null) {
            return;
        }
        aiInputView.setText("");
        OpenAiCompatibleClient.Message userMessage =
                new OpenAiCompatibleClient.Message("user", prompt.trim());
        aiMessages.add(userMessage);
        addAiMessageBubble("user", userMessage.content, false);
        setAiBusy(true);

        int session = aiSessionSerial;
        byte[] pngBytes = aiSelectionSnapshot.pngBytes;
        // Layout travels with the request so the model does not have to spend a
        // round trip asking where it is; every exchange is a full non-streaming
        // request under BYOK.
        NoteToolContext toolContext = canvasView.createToolContext(
                aiSelectionSnapshot.sourceBounds);
        JSONObject pageMap = toolContext.readPageMap(
                Math.max(0, toolContext.selectionPageIndex()), true);
        JSONArray toolDescriptions;
        try {
            toolDescriptions = aiToolRegistry.describe();
        } catch (JSONException schemaFailure) {
            toolDescriptions = null;
        }
        JSONArray offeredTools = toolDescriptions;

        if (profile.split) {
            startTranscriptionLeg(profile, session, pngBytes, toolContext, pageMap,
                    offeredTools);
            return;
        }

        aiStatusView.setText("正在安全连接模型…");
        aiExecutor.execute(() -> {
            try {
                AiConfigStore.Config config = aiConfigStore.directConfig(profile);
                OpenAiCompatibleClient.Completion completion =
                        OpenAiCompatibleClient.completeWithTools(config, pngBytes,
                                new ArrayList<>(aiMessages), offeredTools, pageMap);
                runOnUiThread(() -> {
                    if (session != aiSessionSerial || aiSelectionSnapshot == null) {
                        return;
                    }
                    handleAiCompletion(config, completion, session, pngBytes, toolContext,
                            pageMap);
                });
            } catch (Exception error) {
                showAiRequestFailure(session, error);
            }
        });
    }

    /**
     * First leg of the split route: read the handwriting before reasoning about
     * it. The transcript is shown in the card so the user can catch recognition
     * errors, then kept for the whole session — follow-ups reason over text and
     * never re-send the image.
     */
    private void startTranscriptionLeg(AiConfigStore.Profile profile, int session,
                                       byte[] pngBytes, NoteToolContext toolContext,
                                       JSONObject pageMap, JSONArray offeredTools) {
        if (aiSessionTranscript != null) {
            startAnswerLeg(profile, session, toolContext, pageMap, offeredTools);
            return;
        }
        aiStatusView.setText("正在转写手写内容…");
        aiExecutor.execute(() -> {
            try {
                AiConfigStore.Config transcriber = aiConfigStore.transcribeConfig(profile);
                String transcript = OpenAiCompatibleClient.transcribe(transcriber, pngBytes);
                runOnUiThread(() -> {
                    if (session != aiSessionSerial || aiSelectionSnapshot == null) {
                        return;
                    }
                    aiSessionTranscript = transcript;
                    addAiNotice("两段式 · 转写完成，请核对识别结果：");
                    addAiMessageBubble("assistant", "【手写转写】\n" + transcript, false);
                    foldTranscriptIntoConversation();
                    startAnswerLeg(profile, session, toolContext, pageMap, offeredTools);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (session != aiSessionSerial || aiSelectionSnapshot == null) {
                        return;
                    }
                    rollbackLastUserTurn();
                    showAiRequestFailure(session, error);
                    addAiNotice("转写失败，本次未消耗回答模型调用。");
                });
            }
        });
    }

    /** The answer leg reasons over the transcript as plain text, with tools. */
    private void startAnswerLeg(AiConfigStore.Profile profile, int session,
                                NoteToolContext toolContext, JSONObject pageMap,
                                JSONArray offeredTools) {
        aiStatusView.setText("正在安全连接回答模型…");
        List<OpenAiCompatibleClient.Message> requestMessages = new ArrayList<>(aiMessages);
        aiExecutor.execute(() -> {
            try {
                AiConfigStore.Config config = aiConfigStore.answerConfig(profile);
                OpenAiCompatibleClient.Completion completion =
                        OpenAiCompatibleClient.completeWithTools(config, null,
                                requestMessages, offeredTools, pageMap);
                runOnUiThread(() -> {
                    if (session != aiSessionSerial) {
                        return;
                    }
                    handleAiCompletion(config, completion, session, null, toolContext,
                            pageMap);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (session != aiSessionSerial) {
                        return;
                    }
                    showAiRequestFailure(session, error);
                });
            }
        });
    }

    /**
     * Replaces the pending user turn's wire content with prompt + transcript.
     * The bubble keeps showing what the user typed; only the message sent to
     * the answer model carries the transcription.
     */
    private void foldTranscriptIntoConversation() {
        if (aiMessages.isEmpty()) {
            return;
        }
        int lastIndex = aiMessages.size() - 1;
        OpenAiCompatibleClient.Message last = aiMessages.get(lastIndex);
        if (!"user".equals(last.role)) {
            return;
        }
        aiMessages.set(lastIndex, new OpenAiCompatibleClient.Message("user",
                last.content + "\n\n【圈选手写内容的文字转写】\n" + aiSessionTranscript));
    }

    /** Removes an unanswered user turn so a retry does not duplicate it. */
    private void rollbackLastUserTurn() {
        if (!aiMessages.isEmpty()
                && "user".equals(aiMessages.get(aiMessages.size() - 1).role)) {
            aiMessages.remove(aiMessages.size() - 1);
        }
        if (aiConversationView.getChildCount() > 0) {
            aiConversationView.removeViewAt(aiConversationView.getChildCount() - 1);
        }
    }

    private void showAiRequestFailure(int session, Exception error) {
        String message = error.getMessage() == null ? "未知错误" : error.getMessage();
        runOnUiThread(() -> {
            if (session != aiSessionSerial || aiSelectionSnapshot == null) {
                return;
            }
            addAiMessageBubble("assistant", "请求失败：" + message, true);
            aiStatusView.setText("请求失败 · 可检查配置后重试");
            setAiBusy(false);
        });
    }

    /**
     * Routes a completion: prose to the card, tool calls to the document.
     *
     * <p>This split is what stops raw model output becoming note content. Earlier
     * versions pasted the entire answer onto the page, so acknowledgements and
     * "what I think you circled" commentary became permanent. Now only an explicit
     * {@code write_text} call reaches a page, which makes "does this belong in the
     * note" a decision the model makes rather than a side effect of replying.
     */
    private void handleAiCompletion(AiConfigStore.Config config,
                                    OpenAiCompatibleClient.Completion completion,
                                    int session, byte[] pngBytes,
                                    NoteToolContext toolContext, JSONObject pageMap) {
        if (!completion.toolCalls.isEmpty()) {
            aiMessages.add(new OpenAiCompatibleClient.Message("assistant",
                    completion.content, completion.toolCalls, null));
            // History keeps the raw text (reasoning echo-back); the bubble shows
            // only the visible part.
            if (!completion.displayContent.isEmpty()) {
                addAiMessageBubble("assistant", completion.displayContent, false);
            }
            aiToolsHonoured = true;
            runAiToolCalls(config, completion, session, pngBytes, toolContext, 1, pageMap);
            return;
        }
        aiMessages.add(new OpenAiCompatibleClient.Message("assistant", completion.content));
        setAiBusy(false);
        addAiMessageBubble("assistant", completion.displayContent, false);
        if (aiOutputInline && !aiToolsHonoured) {
            // A BYOK endpoint may ignore the tools field completely. The user has
            // explicitly allowed page writes, so fall back to the pre-tools
            // behaviour and say plainly that the model did not choose what to keep.
            presentAiAnswer(completion.displayContent);
            addAiNotice("当前模型没有使用笔记工具，已按旧方式把整段回答写入页面。"
                    + "若希望模型自行判断哪些内容值得留在笔记里，"
                    + "请改用支持 function calling 的模型。");
            return;
        }
        aiStatusView.setText("回答完成 · 已显示在卡片 · 可继续追问");
    }

    /**
     * Executes one round of tool calls, then asks the model to summarise.
     *
     * <p>Single round on purpose: requests are non-streaming with no cancel, so a
     * model that loops costs the user real money per iteration. Everything the
     * tools did is folded into one undo entry, so a single undo reverses the whole
     * AI action rather than peeling it back one write at a time.
     */
    /**
     * Runs tool calls and lets the model continue until it produces prose.
     *
     * <p>Tools stay available on every round. The natural workflow is to read the
     * page first and only then decide where to write, so offering tools just once
     * meant {@code read_page_map} consumed the only opportunity and
     * {@code write_text} could never be reached — the model was not disobeying,
     * it had nothing left to call with.
     *
     * <p>Cost is bounded by {@link #MAX_AI_TOOL_ROUNDS} instead. Requests are
     * non-streaming with no cancel, so the ceiling is what protects the user's
     * bill; withholding tools was the wrong lever.
     *
     * <p>Every write across every round folds into one undo entry, so a single
     * undo reverses the whole AI action.
     */
    private void runAiToolCalls(AiConfigStore.Config config,
                                OpenAiCompatibleClient.Completion completion,
                                int session, byte[] pngBytes,
                                NoteToolContext toolContext, int round,
                                JSONObject pageMap) {
        aiStatusView.setText(String.format(Locale.CHINA, "正在执行 %d 个笔记操作…",
                completion.toolCalls.size()));
        boolean mutated = false;
        canvasView.beginUndoTransaction();
        try {
            for (OpenAiCompatibleClient.ToolCall call : completion.toolCalls) {
                if (!aiExecutedToolCallIds.add(call.id)) {
                    // Same id twice means a retried request, not a second intent.
                    continue;
                }
                NoteTool.Result result = aiToolRegistry.invoke(call.name, call.arguments,
                        toolContext, aiGrantedPermission);
                mutated = mutated || result.mutatedDocument;
                aiMessages.add(OpenAiCompatibleClient.Message.toolResult(call.id,
                        result.payload.toString()));
                addAiNotice((result.ok ? "已执行 " : "未执行 ") + call.name
                        + "：" + result.summary);
            }
        } finally {
            canvasView.endUndoTransaction();
        }
        if (mutated) {
            updateTextBoxOverlays(canvasView.getTextBoxes());
        }

        boolean allowMoreTools = round < MAX_AI_TOOL_ROUNDS;
        JSONArray nextTools = null;
        if (allowMoreTools) {
            try {
                nextTools = aiToolRegistry.describe();
            } catch (JSONException schemaFailure) {
                nextTools = null;
            }
        } else {
            addAiNotice("已达到本次任务的操作轮次上限，接下来只做总结。");
        }
        JSONArray offered = nextTools;
        // The page changed, so later rounds must plan against the new layout
        // rather than the map captured before any writes happened.
        JSONObject refreshedMap = mutated && allowMoreTools
                ? toolContext.readPageMap(Math.max(0, toolContext.selectionPageIndex()), true)
                : pageMap;

        List<OpenAiCompatibleClient.Message> followUp = new ArrayList<>(aiMessages);
        aiExecutor.execute(() -> {
            try {
                OpenAiCompatibleClient.Completion next =
                        OpenAiCompatibleClient.completeWithTools(config, pngBytes,
                                followUp, offered, refreshedMap);
                runOnUiThread(() -> {
                    if (session != aiSessionSerial) {
                        return;
                    }
                    if (!next.toolCalls.isEmpty()) {
                        // The model wants to act again, e.g. write after reading.
                        aiMessages.add(new OpenAiCompatibleClient.Message("assistant",
                                next.content, next.toolCalls, null));
                        if (!next.content.isEmpty()) {
                            addAiMessageBubble("assistant", next.content, false);
                        }
                        runAiToolCalls(config, next, session, pngBytes, toolContext,
                                round + 1, refreshedMap);
                        return;
                    }
                    aiMessages.add(new OpenAiCompatibleClient.Message("assistant",
                            next.content));
                    setAiBusy(false);
                    if (!next.content.isEmpty()) {
                        addAiMessageBubble("assistant", next.content, false);
                    }
                    aiStatusView.setText("操作完成 · 可继续追问");
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? "未知错误" : error.getMessage();
                runOnUiThread(() -> {
                    if (session != aiSessionSerial) {
                        return;
                    }
                    setAiBusy(false);
                    // The writes already happened and are undoable; only the
                    // closing remark is missing, so say so instead of implying
                    // nothing occurred.
                    addAiNotice("笔记操作已完成，但收尾回复失败：" + message);
                    aiStatusView.setText("操作已完成 · 收尾回复失败");
                });
            }
        });
    }

    private void setAiBusy(boolean busy) {
        aiBusy = busy;
        if (aiSendButton != null) {
            aiSendButton.setEnabled(!busy);
            aiSendButton.setText(busy ? "等待…" : "发送");
            aiExplainButton.setEnabled(!busy);
            aiMarkdownButton.setEnabled(!busy);
            aiDiagramButton.setEnabled(!busy);
        }
        updateAiCardTitle();
    }

    /**
     * Profile manager in the style of cc-switch: every saved configuration is a
     * switchable slot, tapping a row makes it active for the next request, and
     * each row can be edited or deleted. The direct multimodal route and the
     * split transcribe+answer route are both just profiles here.
     */
    private void showAiManagerDialog(Runnable afterSave) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), 0);

        AiConfigStore.Profile active = aiConfigStore.activeProfile();
        content.addView(text("当前使用：" + (active == null ? "未配置" : active.name),
                13, Color.rgb(23, 33, 43)));
        content.addView(text("点击任意配置即可切换；直连与两段式可并存。",
                12, Color.rgb(91, 103, 113)));

        ScrollView listScroll = new ScrollView(this);
        LinearLayout profileList = new LinearLayout(this);
        profileList.setOrientation(LinearLayout.VERTICAL);
        profileList.setPadding(0, dp(8), 0, dp(4));
        listScroll.addView(profileList, matchWrap());
        content.addView(listScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

        Button newButton = new Button(this);
        newButton.setText("新建配置");
        content.addView(newButton, matchWrap());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("AI 配置")
                .setMessage("OpenAI-compatible 消息格式，只接受 HTTPS；Key 由 Android Keystore 加密，不写入笔记或日志。")
                .setView(content)
                .setNegativeButton("关闭", null)
                .setPositiveButton("完成", (ignored, which) -> {
                    if (afterSave != null) {
                        afterSave.run();
                    }
                })
                .create();
        newButton.setOnClickListener(view -> showProfileEditorDialog(null,
                () -> rebuildProfileListRows(profileList)));
        rebuildProfileListRows(profileList);
        dialog.show();
    }

    private void rebuildProfileListRows(LinearLayout container) {
        container.removeAllViews();
        List<AiConfigStore.Profile> profiles = aiConfigStore.listProfiles();
        String activeId = aiConfigStore.activeProfileId();
        if (profiles.isEmpty()) {
            TextView empty = text("还没有配置。点“新建配置”添加一个：\n选厂商预设 → 粘贴 API Key → 保存。",
                    12, Color.rgb(91, 103, 113));
            empty.setPadding(0, dp(16), 0, dp(8));
            container.addView(empty, matchWrap());
            return;
        }
        for (AiConfigStore.Profile profile : profiles) {
            container.addView(buildProfileRow(profile, profile.id.equals(activeId),
                    () -> rebuildProfileListRows(container)));
        }
    }

    private View buildProfileRow(AiConfigStore.Profile profile, boolean isActive,
                                 Runnable onChanged) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(8), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(10));
        background.setColor(isActive ? Color.rgb(218, 230, 247) : Color.rgb(245, 246, 247));
        background.setStroke(dp(1), isActive ? Color.rgb(40, 94, 168) : Color.rgb(214, 220, 224));
        row.setBackground(background);

        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.topMargin = dp(6);
        row.setLayoutParams(rowParams);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView nameView = text((isActive ? "● " : "") + profile.name, 14, Color.rgb(23, 33, 43));
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        info.addView(nameView);
        info.addView(text(profile.summary(), 12, Color.rgb(91, 103, 113)));
        row.addView(info);

        row.setOnClickListener(view -> {
            if (isActive) {
                return;
            }
            aiConfigStore.setActiveProfileId(profile.id);
            aiUploadConfirmed = false;
            Toast.makeText(this, "已切换到：" + profile.name, Toast.LENGTH_SHORT).show();
            onChanged.run();
        });

        Button editButton = new Button(this);
        editButton.setText("编辑");
        editButton.setTextSize(11);
        editButton.setMinWidth(dp(52));
        editButton.setOnClickListener(view -> showProfileEditorDialog(profile.id, onChanged));
        row.addView(editButton);

        Button deleteButton = new Button(this);
        deleteButton.setText("删除");
        deleteButton.setTextSize(11);
        deleteButton.setMinWidth(dp(52));
        deleteButton.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("删除配置？")
                .setMessage("将删除“" + profile.name + "”。已保存的 Key 会一并清除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (ignored, which) -> {
                    aiConfigStore.deleteProfile(profile.id);
                    onChanged.run();
                })
                .show());
        row.addView(deleteButton);
        return row;
    }

    private void showProfileEditorDialog(String editId, Runnable afterSave) {
        AiConfigStore.Profile existing = null;
        if (editId != null) {
            for (AiConfigStore.Profile candidate : aiConfigStore.listProfiles()) {
                if (candidate.id.equals(editId)) {
                    existing = candidate;
                    break;
                }
            }
        }
        final boolean creating = existing == null;
        final AiConfigStore.Profile current = existing != null ? existing : new AiConfigStore.Profile();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), 0);

        content.addView(text("厂商预设（自动填地址与推荐模型，不改 Key）", 12, Color.rgb(91, 103, 113)));
        Spinner presetSpinner = new Spinner(this);
        AiConfigStore.VendorPreset[] presets = AiConfigStore.vendorPresets();
        String[] presetLabels = new String[presets.length];
        for (int index = 0; index < presets.length; index++) {
            presetLabels[index] = presets[index].label;
        }
        ArrayAdapter<String> presetAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, presetLabels);
        presetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(presetAdapter);
        content.addView(presetSpinner, matchWrap());
        TextView presetHint = text(presets[0].hint, 12, Color.rgb(91, 103, 113));
        presetHint.setPadding(dp(4), dp(2), dp(4), 0);
        content.addView(presetHint, matchWrap());

        content.addView(text("配置名称", 12, Color.rgb(91, 103, 113)));
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint("例如：智谱免费 / DeepSeek 两段式");
        nameInput.setText(current.name);
        content.addView(nameInput, matchWrap());

        content.addView(text("路线", 12, Color.rgb(91, 103, 113)));
        Spinner modeSpinner = new Spinner(this);
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"直连多模态（一个模型看图并回答）",
                        "转写 + 回答（两段式）"});
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modeAdapter);
        modeSpinner.setSelection(current.split ? 1 : 0);
        content.addView(modeSpinner, matchWrap());

        LinearLayout directGroup = new LinearLayout(this);
        directGroup.setOrientation(LinearLayout.VERTICAL);
        content.addView(directGroup, matchWrap());
        LinearLayout splitGroup = new LinearLayout(this);
        splitGroup.setOrientation(LinearLayout.VERTICAL);
        splitGroup.addView(text("两段式：转写模型先把圈选手写转成文字，回答模型只读文字并执行笔记操作。",
                12, Color.rgb(91, 103, 113)));
        content.addView(splitGroup, matchWrap());

        EditText directEndpointInput = labeledInput(directGroup, "API 基址或完整 Chat Completions 地址",
                "https://example.com/v1", current.directEndpoint);
        EditText directModelInput = labeledInput(directGroup, "支持图像输入的模型名称",
                "vision-model-name", current.directModel);
        EditText directKeyInput = labeledInput(directGroup, "API Key（Android Keystore 加密）",
                current.hasDirectKey() ? "已安全保存；留空保持不变" : "请输入 API Key", "");

        EditText transcribeEndpointInput = labeledInput(splitGroup, "转写 API 基址",
                "https://example.com/v1", current.transcribeEndpoint);
        EditText transcribeModelInput = labeledInput(splitGroup, "转写模型名称（需支持图像）",
                "vision-or-ocr-model", current.transcribeModel);
        EditText transcribeKeyInput = labeledInput(splitGroup, "转写 API Key",
                current.hasTranscribeKey() ? "已安全保存；留空保持不变" : "请输入 API Key", "");
        EditText answerEndpointInput = labeledInput(splitGroup, "回答 API 基址",
                "https://example.com/v1", current.answerEndpoint);
        EditText answerModelInput = labeledInput(splitGroup, "回答模型名称（纯文本即可）",
                "text-model", current.answerModel);
        EditText answerKeyInput = labeledInput(splitGroup, "回答 API Key",
                current.hasAnswerKey() ? "已安全保存；留空保持不变" : "请输入 API Key", "");

        Runnable applyModeVisibility = () -> {
            boolean split = modeSpinner.getSelectedItemPosition() == 1;
            directGroup.setVisibility(split ? View.GONE : View.VISIBLE);
            splitGroup.setVisibility(split ? View.VISIBLE : View.GONE);
        };
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                applyModeVisibility.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                presetHint.setText(presets[position].hint);
                if (position <= 0) {
                    return;
                }
                AiConfigStore.VendorPreset preset = presets[position];
                boolean split = modeSpinner.getSelectedItemPosition() == 1;
                if (split) {
                    transcribeEndpointInput.setText(preset.baseUrl);
                    transcribeModelInput.setText(preset.transcribeModel);
                    answerEndpointInput.setText(preset.baseUrl);
                    answerModelInput.setText(preset.answerModel);
                } else {
                    directEndpointInput.setText(preset.baseUrl);
                    directModelInput.setText(preset.directModel);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(creating ? "新建 AI 配置" : "编辑 AI 配置")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("安全保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        nameInput.setError("请给配置起个名字");
                        return;
                    }
                    boolean split = modeSpinner.getSelectedItemPosition() == 1;
                    try {
                        AiConfigStore.Profile saved = new AiConfigStore.Profile();
                        saved.id = current.id;
                        saved.name = name;
                        saved.split = split;
                        String directKey = null;
                        String transcribeKey = null;
                        String answerKey = null;
                        if (!split) {
                            String endpoint = directEndpointInput.getText().toString().trim();
                            String model = directModelInput.getText().toString().trim();
                            if (!isValidHttpsEndpoint(endpoint)) {
                                directEndpointInput.setError("请输入有效的 HTTPS API 地址");
                                return;
                            }
                            if (model.isEmpty()) {
                                directModelInput.setError("请输入模型名称");
                                return;
                            }
                            String key = directKeyInput.getText().toString().trim();
                            if (key.isEmpty() && !current.hasDirectKey()) {
                                directKeyInput.setError("请输入 API Key");
                                return;
                            }
                            directKey = key;
                            saved.directEndpoint = endpoint;
                            saved.directModel = model;
                        } else {
                            String transcribeEndpoint = transcribeEndpointInput.getText().toString().trim();
                            String transcribeModel = transcribeModelInput.getText().toString().trim();
                            String answerEndpoint = answerEndpointInput.getText().toString().trim();
                            String answerModel = answerModelInput.getText().toString().trim();
                            if (!isValidHttpsEndpoint(transcribeEndpoint)) {
                                transcribeEndpointInput.setError("请输入有效的 HTTPS API 地址");
                                return;
                            }
                            if (transcribeModel.isEmpty()) {
                                transcribeModelInput.setError("请输入转写模型名称");
                                return;
                            }
                            if (!isValidHttpsEndpoint(answerEndpoint)) {
                                answerEndpointInput.setError("请输入有效的 HTTPS API 地址");
                                return;
                            }
                            if (answerModel.isEmpty()) {
                                answerModelInput.setError("请输入回答模型名称");
                                return;
                            }
                            String key = transcribeKeyInput.getText().toString().trim();
                            if (key.isEmpty() && !current.hasTranscribeKey()) {
                                transcribeKeyInput.setError("请输入转写 API Key");
                                return;
                            }
                            transcribeKey = key;
                            String answerKeyValue = answerKeyInput.getText().toString().trim();
                            if (answerKeyValue.isEmpty() && !current.hasAnswerKey()) {
                                answerKeyInput.setError("请输入回答 API Key");
                                return;
                            }
                            answerKey = answerKeyValue;
                            saved.transcribeEndpoint = transcribeEndpoint;
                            saved.transcribeModel = transcribeModel;
                            saved.answerEndpoint = answerEndpoint;
                            saved.answerModel = answerModel;
                        }
                        aiConfigStore.saveProfile(saved, directKey, transcribeKey, answerKey);
                        if (creating || saved.id.equals(aiConfigStore.activeProfileId())) {
                            aiConfigStore.setActiveProfileId(saved.id);
                        }
                        aiUploadConfirmed = false;
                        aiStatusView.setText(R.string.ai_config_saved);
                        dialog.dismiss();
                        if (afterSave != null) {
                            afterSave.run();
                        }
                    } catch (Exception error) {
                        Toast.makeText(this, "安全保存失败", Toast.LENGTH_LONG).show();
                    }
                }));
        dialog.show();
    }

    private EditText labeledInput(LinearLayout group, String label, String hint, String value) {
        group.addView(text(label, 12, Color.rgb(91, 103, 113)));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setInputType(label.contains("Key")
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(value == null ? "" : value);
        group.addView(input, matchWrap());
        return input;
    }

    private boolean isValidHttpsEndpoint(String endpoint) {
        try {
            java.net.URL url = new java.net.URL(
                    OpenAiCompatibleClient.resolveChatCompletionsUrl(endpoint));
            return "https".equalsIgnoreCase(url.getProtocol()) && !url.getHost().isEmpty();
        } catch (Exception error) {
            return false;
        }
    }

    private void setActionEnabled(ToolIconButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.38f);
    }

    // ===================== 知识库：手写 → 格式笔记 =====================

    /**
     * Digitization contract with the vision model: human-beautiful and
     * machine-readable Markdown, formulas as LaTeX, hand-drawn arrows and
     * flowcharts as inline arrow chains or Mermaid blocks.
     */
    private static final String DIGITIZE_SYSTEM_PROMPT =
            "你是手写笔记数字化器。把图片中的手写内容忠实转写为 Markdown 文本，"
                    + "要求人读美观、机器可读：\n"
                    + "- 文字用 Markdown：标题、列表、段落按原有结构与空间顺序；\n"
                    + "- 数学公式用标准 LaTeX：行间公式放在 \\[ 与 \\] 之间，"
                    + "行内公式放在 \\( 与 \\) 之间；\n"
                    + "- 手绘的箭头、推导链和流程图转为文本图形：简单关系写成一行"
                    + "「A → B → C」；多分支或循环流程用 mermaid 代码块（以 ```mermaid 开头，"
                    + "flowchart TD 语法），节点文字保持原文；\n"
                    + "- 无法辨认的字符用【无法辨认】标注，不要臆测；\n"
                    + "- 复杂插图无法用上述方式表达时，用一句以【图形】开头的文字概括；\n"
                    + "- 不要回答、讲解或补充图片之外的内容，只输出转写结果。";

    /** Opens the note and waits for its async restore before digitizing. */
    private void openNoteThenDigitize(NoteStore.Entry entry) {
        openNote(entry);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!editorVisible || !entry.id.equals(currentNoteId)) {
                    return;
                }
                if (!restoreCompleted) {
                    handler.postDelayed(this, 120);
                    return;
                }
                startDigitizationForCurrentNote();
            }
        }, 150);
    }

    private void startDigitizationForCurrentNote() {
        if (digitizing || !editorVisible || currentNoteId == null) {
            return;
        }
        AiConfigStore.Profile profile = aiConfigStore.activeProfile();
        if (profile == null || !profile.structurallyComplete()) {
            Toast.makeText(this, "请先配置 AI", Toast.LENGTH_SHORT).show();
            showAiManagerDialog(null);
            return;
        }
        int total = canvasView.getPageCount();
        if (total <= 0) {
            Toast.makeText(this, "当前笔记没有页面", Toast.LENGTH_SHORT).show();
            return;
        }
        String target = profile.split
                ? OpenAiCompatibleClient.resolveChatCompletionsUrl(profile.transcribeEndpoint)
                : OpenAiCompatibleClient.resolveChatCompletionsUrl(profile.directEndpoint);
        String model = profile.split ? profile.transcribeModel : profile.directModel;

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(10), dp(24), dp(4));
        TextView intro = text(String.format(Locale.CHINA,
                "将把《%s》共 %d 页逐页转写为 Markdown（公式 LaTeX、流程图 Mermaid），"
                        + "合并为一篇 Obsidian 兼容的格式笔记存入知识库。"
                        + "之后可以在书架阅读、导出，圈选提问时 AI 也会检索它。",
                currentNoteTitle, total), 13, INK_COLOR);
        body.addView(intro);
        TextView privacy = text("⚠ 上传范围变化：数字化会发送整页手写内容，"
                + "而不只是圈选区域。", 12, AMBER_TEXT);
        GradientDrawable privacyBackground = new GradientDrawable();
        privacyBackground.setColor(AMBER_TINT);
        privacyBackground.setCornerRadius(dp(10));
        privacy.setBackground(privacyBackground);
        privacy.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams privacyParams = matchWrap();
        privacyParams.setMargins(0, dp(12), 0, 0);
        body.addView(privacy, privacyParams);
        TextView meta = text(String.format(Locale.CHINA,
                "模型：%s\n目标：%s", model, target), 11, FAINT_TEXT);
        meta.setPadding(0, dp(12), 0, 0);
        body.addView(meta);

        new AlertDialog.Builder(this)
                .setTitle("转为格式笔记？")
                .setView(body)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始转换", (dialog, which) -> runDigitization(profile, total))
                .show();
    }

    private void runDigitization(AiConfigStore.Profile profile, int total) {
        digitizing = true;
        final String noteId = currentNoteId;
        final String title = currentNoteTitle;
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("正在转换为格式笔记")
                .setMessage("准备中…")
                .setCancelable(false)
                .create();
        progress.show();
        final List<String> sections = new ArrayList<>();
        for (int index = 0; index < total; index++) {
            sections.add("");
        }
        final List<TextFlow> flows = canvasView.getTextFlows();
        digitizePage(profile, noteId, title, total, 0, sections, flows, progress);
    }

    /** Renders one page on the UI thread, transcribes on the executor, then recurses. */
    private void digitizePage(AiConfigStore.Profile profile, String noteId, String title,
                              int total, int index, List<String> sections,
                              List<TextFlow> flows, AlertDialog progress) {
        if (!editorVisible || !noteId.equals(currentNoteId) || !digitizing) {
            digitizing = false;
            progress.dismiss();
            return;
        }
        if (index >= total) {
            finishDigitization(noteId, title, total, sections, progress);
            return;
        }
        byte[] png = canvasView.renderPagePng(index, 1600);
        if (png == null) {
            digitizePage(profile, noteId, title, total, index + 1, sections, flows, progress);
            return;
        }
        progress.setMessage(String.format(Locale.CHINA, "正在数字化 第 %d/%d 页…",
                index + 1, total));
        digitizeExecutor.execute(() -> {
            try {
                AiConfigStore.Config config = profile.split
                        ? aiConfigStore.transcribeConfig(profile)
                        : aiConfigStore.directConfig(profile);
                String transcript = OpenAiCompatibleClient.transcribeWithPrompt(
                        config, png, DIGITIZE_SYSTEM_PROMPT);
                runOnUiThread(() -> {
                    if (!editorVisible || !noteId.equals(currentNoteId) || !digitizing) {
                        return;
                    }
                    sections.set(index, mergePageContent(transcript, flows, index));
                    digitizePage(profile, noteId, title, total, index + 1, sections,
                            flows, progress);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    digitizing = false;
                    progress.dismiss();
                    Toast.makeText(this, "转换失败（第 " + (index + 1) + " 页）："
                            + safeError(error), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** Existing text flows are already the target format; embed them verbatim. */
    private String mergePageContent(String transcript, List<TextFlow> flows, int pageIndex) {
        StringBuilder merged = new StringBuilder();
        for (TextFlow flow : flows) {
            if (flow.anchorPageIndex != pageIndex) {
                continue;
            }
            String embedded = VaultStore.embedTextFlow(flow);
            if (!embedded.isEmpty()) {
                merged.append(embedded).append("\n\n");
            }
        }
        if (transcript != null && !transcript.trim().isEmpty()) {
            merged.append(transcript.trim());
        }
        return merged.toString().trim();
    }

    private void finishDigitization(String noteId, String title, int total,
                                    List<String> sections, AlertDialog progress) {
        progress.setMessage("正在保存…");
        digitizeExecutor.execute(() -> {
            try {
                long sourceModified = 0L;
                for (NoteStore.Entry entry : NoteStore.list(this)) {
                    if (entry.id.equals(noteId)) {
                        sourceModified = entry.updatedAt;
                        break;
                    }
                }
                vaultStore.write(noteId, title, total, sourceModified, sections);
                runOnUiThread(() -> {
                    digitizing = false;
                    progress.dismiss();
                    Toast.makeText(this, "已生成格式笔记《" + title + "》，"
                            + "可在书架“知识库”中查看", Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    digitizing = false;
                    progress.dismiss();
                    Toast.makeText(this, "保存格式笔记失败：" + safeError(error),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ===================== 知识库：阅读与导出 =====================

    private void showVaultReader(String fileName) {
        storageExecutor.execute(() -> {
            try {
                String markdown = vaultStore.read(fileName);
                runOnUiThread(() -> presentVaultReader(fileName, markdown));
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "读取失败：" + safeError(error), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void presentVaultReader(String fileName, String markdown) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        CompiledTextWebView webView = new CompiledTextWebView(this,
                NoteTextBox.Format.MARKDOWN, "");
        webView.renderDocument(markdown);
        scroll.addView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(fileName)
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void launchExportVaultFile(VaultStore.VaultNote note) {
        pendingExportVaultFile = note.fileName;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/markdown");
        intent.putExtra(Intent.EXTRA_TITLE, VaultStore.sanitizeTitle(note.title) + ".md");
        startActivityForResult(intent, REQUEST_EXPORT_VAULT);
    }

    private void exportVaultFileToUri(Intent data) {
        android.net.Uri uri = data.getData();
        String capturedFile = pendingExportVaultFile;
        pendingExportVaultFile = null;
        storageExecutor.execute(() -> {
            try {
                String markdown = vaultStore.read(capturedFile);
                try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) {
                        throw new IllegalStateException("无法打开所选位置");
                    }
                    output.write(markdown.getBytes(StandardCharsets.UTF_8));
                }
                runOnUiThread(() -> Toast.makeText(this, "已导出 Markdown",
                        Toast.LENGTH_SHORT).show());
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "导出失败：" + safeError(error), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void updateStatsText() {
        String selection = selectionCount > 0 ? " · 选中 " + selectionCount : "";
        statsView.setText(String.format(Locale.CHINA, "%d 笔 · %d 点 · %d/%d 页 · %d%%%s",
                strokeCount, pointCount, currentPageNumber, totalPageCount,
                canvasZoomPercent, selection));
    }

    private void togglePenOnly() {
        penOnlyEnabled = !canvasView.isPenOnly();
        canvasView.setPenOnly(penOnlyEnabled);
        penOnlyButton.setSelected(penOnlyEnabled);
        penOnlyButton.setContentDescription(penOnlyEnabled ? "仅手写笔模式已开启" : "仅手写笔模式已关闭");
        preferences.edit().putBoolean("penOnly", penOnlyEnabled).apply();
        Toast.makeText(this,
                penOnlyEnabled
                        ? "仅笔开启；单指移动画布，双指缩放，选区仍可手指拖动"
                        : "单指可以编辑；双指仍可移动和缩放",
                Toast.LENGTH_SHORT).show();
    }

    private void confirmClear() {
        if (canvasView.getStrokeCount() == 0) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空当前笔记？")
                .setMessage("清空后仍可使用撤销恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> canvasView.clearAll())
                .show();
    }

    private void leaveEditorToBookshelf() {
        if (!editorVisible) {
            return;
        }
        commitInlineTextEditorExcept(null);
        handler.removeCallbacks(delayedSave);
        saveDocument(false);
        showBookshelf();
    }

    private void launchImportNote() {
        Toast.makeText(this, "支持 PDF、PadNote JSON 和包含 PDF 的笔记 ZIP 包", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/json", "application/octet-stream", "text/plain", "application/pdf", "application/zip"
        });
        startActivityForResult(intent, REQUEST_IMPORT_NOTE);
    }

    private void exportCurrentNote() {
        if (!editorVisible || !restoreCompleted) {
            return;
        }
        commitInlineTextEditorExcept(null);
        new AlertDialog.Builder(this).setTitle("导出当前笔记")
                .setItems(new String[]{"PadNote 可编辑包", "标准 PDF（页面已合并）"},
                        (dialog, which) -> {
                            if (which == 1) launchCreatePdfDocument();
                            else {
                                try {
                                    pendingExportJson = canvasView.toJsonDocument(currentNoteId, currentNoteTitle).toString();
                                    pendingExportNoteId = null;
                                    launchCreateExportDocument(currentNoteTitle);
                                } catch (Exception error) {
                                    Toast.makeText(this, "生成导出数据失败", Toast.LENGTH_LONG).show();
                                }
                            }
                        }).show();
    }

    private void launchCreatePdfDocument() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_TITLE, safeFileName(currentNoteTitle) + ".pdf");
        startActivityForResult(intent, REQUEST_EXPORT_PDF);
    }

    private void launchExportNote(NoteStore.Entry entry) {
        pendingExportJson = null;
        pendingExportNoteId = entry.id;
        launchCreateExportDocument(entry.title);
    }

    private void launchCreateExportDocument(String title) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        boolean pdf;
        try {
            pdf = pendingExportJson != null
                    ? new JSONObject(pendingExportJson).optInt("pdfPageCount", 0) > 0
                    : NoteStore.pdfFile(this, pendingExportNoteId).exists();
        } catch (Exception error) {
            Toast.makeText(this, "无法准备导出：" + safeError(error), Toast.LENGTH_LONG).show();
            return;
        }
        intent.setType(pdf ? "application/zip" : "application/json");
        intent.putExtra(Intent.EXTRA_TITLE, safeFileName(title) + (pdf ? ".padnote.zip" : ".padnote.json"));
        startActivityForResult(intent, REQUEST_EXPORT_NOTE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQUEST_EXPORT_NOTE) {
                pendingExportJson = null;
                pendingExportNoteId = null;
            } else if (requestCode == REQUEST_EXPORT_VAULT) {
                pendingExportVaultFile = null;
            } else if (requestCode == REQUEST_EXPORT_VIDEO_TASK) {
                clearPendingVideoTask();
            }
            return;
        }
        if (requestCode == REQUEST_IMPORT_NOTE) {
            importNoteFromUri(data);
        } else if (requestCode == REQUEST_EXPORT_NOTE) {
            exportNoteToUri(data);
        } else if (requestCode == REQUEST_EXPORT_VAULT) {
            exportVaultFileToUri(data);
        } else if (requestCode == REQUEST_EXPORT_PDF) {
            exportPdfToUri(data.getData());
        } else if (requestCode == REQUEST_EXPORT_VIDEO_TASK) {
            exportVideoTaskToUri(data.getData());
        } else if (requestCode == REQUEST_IMPORT_COVER) {
            handleImportedCover(data.getData());
        } else if (requestCode == REQUEST_IMPORT_IMAGE) {
            handleImportedImage(data.getData());
        }
    }

    private void exportVideoTaskToUri(android.net.Uri uri) {
        String capturedFile = pendingVideoTaskFile;
        String audience = pendingVideoAudience;
        String goal = pendingVideoGoal;
        int duration = pendingVideoDuration;
        clearPendingVideoTask();
        storageExecutor.execute(() -> {
            try {
                String markdown = vaultStore.read(capturedFile);
                VaultStore.VaultNote source = null;
                for (VaultStore.VaultNote note : vaultStore.list()) {
                    if (note.fileName.equals(capturedFile)) {
                        source = note;
                        break;
                    }
                }
                if (source == null) {
                    throw new IllegalStateException("找不到格式笔记");
                }
                try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) {
                        throw new IllegalStateException("无法打开所选位置");
                    }
                    VideoTaskBundleIO.write(output, source.noteId, 1, source.title,
                            markdown, audience.isEmpty() ? "希望理解这份笔记的学习者" : audience,
                            goal.isEmpty() ? "理解并记住这份笔记的核心概念" : goal,
                            duration, "zh-CN-neutral", 1f);
                }
                runOnUiThread(() -> Toast.makeText(this,
                        "视频任务包已导出，可交给电脑端 Agent", Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "导出视频任务失败：" + safeError(error), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void clearPendingVideoTask() {
        pendingVideoTaskFile = null;
        pendingVideoAudience = null;
        pendingVideoGoal = null;
        pendingVideoDuration = 0;
    }

    private void exportPdfToUri(android.net.Uri uri) {
        final NoteCanvasView.PdfExportSnapshot snapshot;
        try {
            snapshot = canvasView.createPdfExportSnapshot();
        } catch (Exception error) {
            Toast.makeText(this, "PDF 导出失败：" + safeError(error), Toast.LENGTH_LONG).show();
            return;
        }
        activePdfExportSnapshot = snapshot;
        FrameLayout renderHost = new FrameLayout(this);
        renderHost.setVisibility(View.VISIBLE);
        renderHost.setAlpha(0.01f);
        renderHost.setClipChildren(false);
        renderHost.setClipToPadding(false);
        renderHost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        appFrame.addView(renderHost, new FrameLayout.LayoutParams(1, 1));
        activePdfExportHost = renderHost;
        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("正在导出 PDF")
                .setMessage("准备页面…")
                .setCancelable(false)
                .create();
        progressDialog.show();
        activePdfExportDialog = progressDialog;
        Handler exportMainHandler = new Handler(Looper.getMainLooper());
        Runnable exportTask = () -> {
            File temporary = null;
            Exception failure = null;
            boolean success = false;
            try {
                temporary = File.createTempFile("flattened-export-", ".pdf", getCacheDir());
                try (OutputStream tempOutput = new FileOutputStream(temporary)) {
                    PdfNoteIO.exportFlattenedPdf(snapshot, renderHost, tempOutput,
                            exportMainHandler, (complete, total) -> {
                                if (progressDialog.isShowing()) {
                                    progressDialog.setMessage("正在渲染第 " + complete + " / " + total + " 页");
                                }
                            });
                }
                // Open the user-selected destination only after the complete PDF
                // exists locally. Success is reported after this stream flushes
                // and closes, never after a partial render.
                try (InputStream input = new FileInputStream(temporary);
                     OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IllegalStateException("无法打开所选位置");
                    PdfNoteIO.copy(input, output, Long.MAX_VALUE);
                    output.flush();
                }
                success = true;
            } catch (Exception error) {
                failure = error;
            } catch (OutOfMemoryError error) {
                failure = new java.io.IOException("导出页面过大，内存不足", error);
            } finally {
                snapshot.close();
                if (temporary != null && !temporary.delete()) temporary.deleteOnExit();
                boolean completed = success;
                Exception result = failure == null
                        ? new java.io.IOException("PDF 导出未完成") : failure;
                exportMainHandler.post(() -> {
                    if (activePdfExportSnapshot == snapshot) {
                        activePdfExportSnapshot = null;
                    }
                    if (activePdfExportHost == renderHost) activePdfExportHost = null;
                    if (activePdfExportDialog == progressDialog) activePdfExportDialog = null;
                    if (renderHost.getParent() == appFrame) appFrame.removeView(renderHost);
                    if (progressDialog.isShowing()) progressDialog.dismiss();
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, completed ? "已导出标准 PDF" :
                                    "PDF 导出失败：" + safeError(result),
                            completed ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                });
            }
        };
        try {
            storageExecutor.execute(exportTask);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            if (activePdfExportSnapshot == snapshot) activePdfExportSnapshot = null;
            if (activePdfExportHost == renderHost) activePdfExportHost = null;
            if (activePdfExportDialog == progressDialog) activePdfExportDialog = null;
            snapshot.close();
            if (renderHost.getParent() == appFrame) appFrame.removeView(renderHost);
            if (progressDialog.isShowing()) progressDialog.dismiss();
            if (!isFinishing() && !isDestroyed()) {
                Toast.makeText(this, "PDF 导出失败：导出任务无法启动",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void handleImportedImage(android.net.Uri uri) {
        storageExecutor.execute(() -> {
            try {
                NoteImage image = NoteImage.read(getContentResolver(), uri);
                runOnUiThread(() -> {
                    try {
                        canvasView.insertImage(image, currentPageNumber - 1);
                        Toast.makeText(this, "图片已插入", Toast.LENGTH_SHORT).show();
                    } catch (RuntimeException error) {
                        image.bitmap.recycle();
                        Toast.makeText(this, "图片插入失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "图片读取失败：" + safeError(error), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** SAF result for a picked cover image; routes to note or new-note flow. */
    private void handleImportedCover(android.net.Uri uri) {
        String target = pendingCoverNoteId;
        storageExecutor.execute(() -> {
            try {
                Bitmap bitmap = decodeImageUri(uri, 1024);
                if (bitmap == null) {
                    throw new IllegalStateException("无法读取所选图片");
                }
                runOnUiThread(() -> {
                    if (target != null) {
                        applyCoverChoice(target, bitmap, "自定义图片");
                        promptAddToPresets(bitmap);
                    } else {
                        pendingNewNoteCover = bitmap;
                        pendingNewNoteCoverLabel = "";
                        updateNewNoteCoverStatus();
                        Toast.makeText(this, "封面已选择，创建时应用",
                                Toast.LENGTH_SHORT).show();
                    }
                    if (coverPickerDialog != null) {
                        coverPickerDialog.dismiss();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "导入封面失败：" + safeError(error), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void importNoteFromUri(Intent data) {
        android.net.Uri uri = data.getData();
        storageExecutor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new IllegalStateException("无法打开所选文件");
                }
                String name = uri.getLastPathSegment();
                try (android.database.Cursor cursor = getContentResolver().query(uri,
                        new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                }
                String fallback = importedFileTitle(name).replaceFirst("(?i)\\.pdf$", "");
                NoteStore.Entry imported = PdfNoteIO.importFile(this, input, fallback);
                runOnUiThread(() -> {
                    Toast.makeText(this, "已导入：" + imported.title,
                            Toast.LENGTH_SHORT).show();
                    refreshBookshelf();
                });
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "导入失败：" + safeError(error), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void exportNoteToUri(Intent data) {
        android.net.Uri uri = data.getData();
        String capturedJson = pendingExportJson;
        String capturedNoteId = pendingExportNoteId;
        pendingExportJson = null;
        pendingExportNoteId = null;
        storageExecutor.execute(() -> {
            try {
                String json = capturedJson;
                if (json == null && capturedNoteId != null) {
                    json = NoteStore.load(this, capturedNoteId).toString();
                }
                if (json == null) {
                    throw new IllegalStateException("没有可导出的笔记");
                }
                try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                    if (output == null) {
                        throw new IllegalStateException("无法写入目标文件");
                    }
                    PdfNoteIO.exportFile(this, new JSONObject(json), output);
                }
                runOnUiThread(() -> Toast.makeText(this,
                        "笔记已导出", Toast.LENGTH_SHORT).show());
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "导出失败：" + safeError(error), Toast.LENGTH_LONG).show());
            }
        });
    }

    private String importedFileTitle(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "导入的笔记";
        }
        String value = name;
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replace(".padnote.json", "").replace(".json", "");
        return value.trim().isEmpty() ? "导入的笔记" : value;
    }

    private String safeFileName(String title) {
        String safe = title == null ? "PadNote" : title.trim();
        safe = safe.replaceAll("[\\\\/:*?\"<>|]", "_");
        return safe.isEmpty() ? "PadNote" : safe;
    }

    private String safeError(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "未知错误" : message;
    }

    @Override
    public void onBackPressed() {
        if (editorVisible) {
            if (aiCard != null && aiCard.getVisibility() == View.VISIBLE) {
                closeAiCard();
            } else {
                leaveEditorToBookshelf();
            }
            return;
        }
        super.onBackPressed();
    }

    private void restoreDocument() {
        String noteId = currentNoteId;
        storageExecutor.execute(() -> {
            JSONObject restoredDocument = null;
            String restoreError = null;
            try {
                restoredDocument = NoteStore.load(this, noteId);
            } catch (Exception error) {
                restoreError = "本地笔记读取失败";
            }
            JSONObject document = restoredDocument;
            String errorMessage = restoreError;
            runOnUiThread(() -> {
                if (!editorVisible || !noteId.equals(currentNoteId)) {
                    return;
                }
                try {
                    if (document != null) {
                        canvasView.loadJsonDocument(document);
                        saveStatusView.setText("已恢复本地笔记");
                    } else if (errorMessage != null) {
                        saveStatusView.setText(errorMessage);
                    }
                } catch (Exception error) {
                    saveStatusView.setText("笔记格式不兼容");
                } finally {
                    // Applied after the load so it is not overwritten by the
                    // document's own (absent) style; only ever set for new notes.
                    if (pendingPageStyle != null) {
                        canvasView.applyPageStyleForNewNote(pendingPageStyle);
                        saveStatusView.setText(pendingPageStyle.describe());
                        pendingPageStyle = null;
                    }
                    restoreCompleted = true;
                    canvasView.setEnabled(true);
                }
            });
        });
    }

    private void saveDocument(boolean showToast) {
        if (!editorVisible || !restoreCompleted || currentNoteId == null) {
            return;
        }
        final String json;
        final String noteId = currentNoteId;
        final String noteTitle = currentNoteTitle;
        try {
            json = canvasView.toJsonDocument(noteId, noteTitle).toString();
        } catch (Exception error) {
            saveStatusView.setText("生成笔记数据失败");
            return;
        }
        saveStatusView.setText("正在保存…");
        storageExecutor.execute(() -> {
            try {
                NoteStore.save(this, noteId, noteTitle, json);
                runOnUiThread(() -> {
                    if (editorVisible && noteId.equals(currentNoteId)) {
                        saveStatusView.setText("已保存到本机");
                    }
                    if (showToast) {
                        Toast.makeText(this, "已保存到本机", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (editorVisible && noteId.equals(currentNoteId)) {
                        saveStatusView.setText("本地保存失败");
                    }
                    if (showToast) {
                        Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
