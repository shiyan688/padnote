package com.padnote.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.CheckBox;
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

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class MainActivity extends Activity implements NoteCanvasView.Listener {
    private static final int REQUEST_IMPORT_NOTE = 7101;
    private static final int REQUEST_EXPORT_NOTE = 7102;
    private static final int REQUEST_EXPORT_VAULT = 7103;
    private static final int REQUEST_IMPORT_COVER = 7104;
    private static final int REQUEST_IMPORT_IMAGE = 7105;
    private static final int REQUEST_EXPORT_PDF = 7106;
    private static final int REQUEST_EXPORT_VIDEO_TASK = 7107;
    private static final int REQUEST_EXPORT_AGENT_ARTIFACT = 7108;
    private static final int REQUEST_EXPORT_RECOVERY = 7109;
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
    private Future<?> activePdfExportFuture;
    private AtomicBoolean activePdfExportCancelled;
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
    private TextView aiSettingsButton;
    private TextView aiMaterialsSummary;
    private Button aiMaterialsButton;
    private Button aiMaterialsPreviewButton;
    private EditText aiInputView;
    private Button aiSendButton;
    private Button aiRetryButton;
    private CheckBox aiReviewTranscriptCheck;
    private Button aiExplainButton;
    private Button aiMarkdownButton;
    private Button aiDiagramButton;
    private Button aiInlineOutputButton;
    private Button aiCardOutputButton;
    private TextView aiResizeHandle;
    private NoteCanvasView.AiSelectionSnapshot aiSelectionSnapshot;
    private RectF aiResultAnchorBounds;
    private AiConfigStore aiConfigStore;
    private AiConversationStore aiConversationStore;
    private AiConversationStore.Snapshot aiConversationSnapshot;
    private final List<AiConversationStore.VisibleEntry> aiVisibleTimeline = new ArrayList<>();
    private boolean renderingAiTimeline;
    private boolean aiConversationLoading;
    private int aiConversationLoadSerial;
    private boolean aiConversationSaveFailureShown;
    private String aiPdfDigest = "";
    private boolean aiPdfDigestAvailable = true;
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
    private String pendingAgentArtifactTaskId;
    private String pendingAgentArtifactId;
    private File pendingRecoveryFile;
    private AiConversationStore.Snapshot pendingAiConversationRecoverySnapshot;
    private String pendingRecoveryName;
    private DigitizationController digitizationController;
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
    private boolean aiMaterialLoading = false;
    private boolean aiUploadConfirmed = false;
    private boolean aiOutputInline = true;
    /** Session diagnostic; fallback decisions use each frozen request's own marker. */
    private boolean aiToolsHonoured = false;
    private int aiSessionSerial = 0;
    private int aiRequestSerial = 0;
    private AiPendingRequest activeAiRequest;
    private AiPendingRequest retryableAiRequest;
    private AlertDialog aiTranscriptReviewDialog;
    private final List<AiEditCardBinding> aiEditCards = new ArrayList<>();
    /** Immutable material authority for the current selection/profile context. */
    private AiReadScope aiReadScope;
    /**
     * Authority for the current task. Tied to the existing page-output switch:
     * "仅卡片" withholds write access entirely, so the toggle now means
     * "may the model touch the note" rather than "where does the answer go".
     */
    private NoteTool.Permission aiGrantedPermission = NoteTool.Permission.READ_ONLY;
    /**
     * Most rounds of tool calls allowed per task. Bounds cost without preventing
     * the read-then-write workflow; even cancellable non-streaming calls may
     * already have reached the provider, so an unbounded loop could spend money.
     */
    private static final int MAX_AI_TOOL_ROUNDS = 4;
    enum SplitResumeStep { TRANSCRIBE, REVIEW, ANSWER }
    /** Tool call ids already run, so a retried request cannot write twice. */
    private final Set<String> aiExecutedToolCallIds = new HashSet<>();
    /**
     * Transcript of the selection produced by the split route's first leg.
     * Lives for one selection session: follow-up questions reuse it instead of
     * re-sending (and re-paying for) the image, and it is dropped whenever the
     * selection or card closes.
     */
    private String aiSessionTranscript = null;

    /** Frozen authority, recipient and payload for one user turn and its retries. */
    private static final class AiPendingRequest {
        final int id;
        final int session;
        final String noteId;
        final String recipientProfileId;
        final long recipientProfileRevision;
        final String transcriptionExecutorLabel;
        final String answerExecutorLabel;
        final boolean split;
        final byte[] pngBytes;
        final AiConfigStore.Config directConfig;
        final AiConfigStore.Config transcribeConfig;
        final AiConfigStore.Config answerConfig;
        final NoteToolContext toolContext;
        final JSONObject pageMap;
        final JSONArray offeredTools;
        final NoteToolRegistry tools;
        final NoteTool.Permission permission;
        final List<OpenAiCompatibleClient.Message> messages;
        final boolean reviewTranscript;
        OpenAiCompatibleClient.Cancellation cancellation;
        String transcript;
        boolean transcriptFolded;
        boolean transcriptAccepted;
        boolean toolsStarted;
        boolean toolsHonoured;
        final Set<String> executedToolCallIds = new HashSet<>();
        final ToolCallReplayCache toolReplay = new ToolCallReplayCache();
        final NoteCanvasView.AiEditRecord edits;
        AiEditCardBinding editBinding;
        long expectedDocumentRevision;

        AiPendingRequest(int id, int session, String noteId, String recipientProfileId,
                         long recipientProfileRevision,
                         String transcriptionExecutorLabel, String answerExecutorLabel,
                         long expectedDocumentRevision,
                         boolean split, byte[] pngBytes,
                         AiConfigStore.Config directConfig,
                         AiConfigStore.Config transcribeConfig,
                         AiConfigStore.Config answerConfig,
                         NoteToolContext toolContext, JSONObject pageMap,
                         JSONArray offeredTools, NoteToolRegistry tools,
                         NoteTool.Permission permission,
                         List<OpenAiCompatibleClient.Message> messages,
                         String transcript, boolean reviewTranscript) {
            this.id = id;
            this.session = session;
            this.noteId = noteId;
            this.recipientProfileId = recipientProfileId == null ? "" : recipientProfileId;
            this.recipientProfileRevision = recipientProfileRevision;
            this.transcriptionExecutorLabel = transcriptionExecutorLabel == null
                    ? "" : transcriptionExecutorLabel;
            this.answerExecutorLabel = answerExecutorLabel == null ? "" : answerExecutorLabel;
            this.expectedDocumentRevision = expectedDocumentRevision;
            this.split = split;
            this.pngBytes = pngBytes;
            this.directConfig = directConfig;
            this.transcribeConfig = transcribeConfig;
            this.answerConfig = answerConfig;
            this.toolContext = toolContext;
            this.pageMap = pageMap;
            this.offeredTools = offeredTools;
            this.tools = tools;
            this.permission = permission;
            this.messages = messages;
            this.transcript = transcript;
            this.reviewTranscript = reviewTranscript;
            this.transcriptFolded = transcript != null;
            this.transcriptAccepted = transcript != null;
            this.edits = new NoteCanvasView.AiEditRecord(
                    "ai-request-" + session + "-" + id);
        }

        boolean canRetry() { return !toolsStarted; }
    }

    static final class CachedToolResult {
        final String signature;
        final String payload;
        final String summary;
        final boolean ok;

        CachedToolResult(String signature, NoteTool.Result result) {
            this.signature = signature;
            this.payload = result.payload.toString();
            this.summary = result.summary;
            this.ok = result.ok;
        }

        boolean matches(OpenAiCompatibleClient.ToolCall call) {
            return signature.equals(toolCallSignature(call));
        }
    }

    static final class ToolCallReplayCache {
        private final Map<String, CachedToolResult> values = new LinkedHashMap<>();

        CachedToolResult find(OpenAiCompatibleClient.ToolCall call) {
            return call == null ? null : values.get(call.id);
        }

        void record(OpenAiCompatibleClient.ToolCall call, NoteTool.Result result) {
            values.put(call.id, new CachedToolResult(toolCallSignature(call), result));
        }
    }

    private static final class AiEditCardBinding {
        final String noteId;
        final NoteCanvasView.AiEditRecord record;
        LinearLayout card;
        TextView status;
        Button action;
        Button locate;

        AiEditCardBinding(String noteId, NoteCanvasView.AiEditRecord record) {
            this.noteId = noteId;
            this.record = record;
        }
    }
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
    private long documentRevision;
    private long lastSavedRevision;
    private boolean saveInFlight;
    private boolean saveQueued;
    private boolean pendingLeaveAfterSave;
    private boolean pendingSaveToast;
    private String pendingUnsavedJson;
    private final Map<String, NoteTextBoxView> textBoxViews = new LinkedHashMap<>();
    /** Bookshelf entries from the last render; vault staleness checks read it. */
    private List<NoteStore.Entry> lastShelfEntries = new ArrayList<>();
    private List<NoteStore.RecoveryEntry> lastRecoveryEntries = new ArrayList<>();
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
        aiConversationStore = new AiConversationStore(this);
        vaultStore = new VaultStore(this);
        agentConnectionStore = new AgentConnectionStore(this);
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
        if (digitizationController != null) digitizationController.pause();
        if (editorVisible) {
            commitInlineTextEditorExcept(null);
        }
        handler.removeCallbacks(delayedSave);
        if (editorVisible) {
            saveDocument(false);
            persistAiConversation();
        }
    }

    @Override
    protected void onDestroy() {
        if (digitizationController != null) digitizationController.close();
        handler.removeCallbacksAndMessages(null);
        if (activePdfExportCancelled != null) activePdfExportCancelled.set(true);
        if (activePdfExportFuture != null) activePdfExportFuture.cancel(true);
        activePdfExportFuture = null;
        activePdfExportCancelled = null;
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
        collapseAiCard(false);
        releaseAiSelectionSnapshot();
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
        documentRevision += 1;
        refreshAiEditCards();
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
        setActionEnabled(aiButton, hasSelection || aiConversationSnapshot != null
                || aiConversationLoading);
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
        if (canvasView != null) {
            for (AiEditCardBinding binding : aiEditCards) {
                canvasView.refreshAiEditFootprint(binding.record);
            }
            refreshAiEditCards();
        }
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
        if (digitizationController != null) digitizationController.pause();
        handler.removeCallbacks(delayedSave);
        disposeTextBoxViews();
        editorVisible = false;
        restoreCompleted = false;
        pendingLeaveAfterSave = false;
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
        List<AgentConnectionStore.Config> agentProfiles = agentConnectionStore.listSummaries();
        long verifiedAgents = agentProfiles.stream().filter(AgentConnectionStore.Config::verified).count();
        agentButton.setContentDescription(agentProfiles.isEmpty() ? "设置电脑 Agent" :
                "已保存 " + agentProfiles.size() + " 个电脑 Agent，其中 " + verifiedAgents + " 个已验证");
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
                List<NoteStore.RecoveryEntry> recoveryEntries = NoteStore.listRecovery(this);
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
                        renderBookshelf(entries, recoveryEntries);
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

    private void renderBookshelf(List<NoteStore.Entry> entries,
                                 List<NoteStore.RecoveryEntry> recoveryEntries) {
        bookshelfList.removeAllViews();
        lastShelfEntries = entries;
        lastRecoveryEntries = recoveryEntries;
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
            renderRecoverySection(recoveryEntries);
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
        renderRecoverySection(recoveryEntries);
        renderVaultSection();
    }

    private void renderRecoverySection(List<NoteStore.RecoveryEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        TextView heading = text("需要恢复 · " + entries.size(), 17, Color.rgb(143, 47, 43));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(dp(30), dp(24), dp(30), dp(8));
        bookshelfList.addView(heading, matchWrap());
        for (NoteStore.RecoveryEntry entry : entries) {
            LinearLayout card = cardSurface(16f, 18);
            card.addView(text(entry.label + " · " + entry.noteId, 14, INK_COLOR));
            card.addView(text("原文件仍保留。可先导出；未保存副本可确认恢复。",
                    12, SECONDARY_TEXT));
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            if (entry.unsavedDraft) {
                Button restore = pillButton("恢复副本", BUTTON_TONAL);
                restore.setOnClickListener(view -> restoreRecoveryDraft(entry));
                actions.addView(restore, new LinearLayout.LayoutParams(0, dp(40), 1f));
            }
            Button export = pillButton("导出原文件", BUTTON_QUIET);
            export.setOnClickListener(view -> launchRecoveryExport(entry));
            LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
            exportParams.leftMargin = dp(8);
            actions.addView(export, exportParams);
            LinearLayout.LayoutParams actionParams = matchWrap();
            actionParams.topMargin = dp(10);
            card.addView(actions, actionParams);
            LinearLayout.LayoutParams cardParams = matchWrap();
            cardParams.setMargins(dp(30), dp(6), dp(30), dp(6));
            bookshelfList.addView(card, cardParams);
        }
    }

    private void restoreRecoveryDraft(NoteStore.RecoveryEntry recovery) {
        storageExecutor.execute(() -> {
            try {
                NoteStore.Entry restored = NoteStore.restoreDraft(this, recovery.noteId);
                runOnUiThread(() -> {
                    Toast.makeText(this, "未保存修改已恢复", Toast.LENGTH_SHORT).show();
                    openNote(restored);
                });
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "恢复失败：" + safeError(error), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void launchRecoveryExport(NoteStore.RecoveryEntry recovery) {
        pendingRecoveryFile = recovery.file;
        pendingAiConversationRecoverySnapshot = null;
        pendingRecoveryName = recovery.noteId + "-recovery.json";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, pendingRecoveryName);
        startActivityForResult(intent, REQUEST_EXPORT_RECOVERY);
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
                        + "Markdown 知识库；在 AI 卡片明确选定后才会读取。", ACCENT_COLOR));
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
                "可阅读、导出 .md 拷入 Obsidian；也可在 AI 卡片选它作为本次额外材料。",
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
        body.addView(dialogParagraph("与 AI 的关系：知识库默认不会发给模型。"
                + "圈选后可在 AI 卡片选择具体格式笔记，预览并冻结本次可读副本。", INK_COLOR));
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
        String[] actions = new String[]{"导出 .md", "生成视频任务包", "删除"};
        new AlertDialog.Builder(this)
                .setTitle(note.title)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        launchExportVaultFile(note);
                    } else if (which == 1) {
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
                .setMessage("可直接发送到已连接的电脑，也可离线导出标准任务包。任务包不含 API Key。")
                .setView(body)
                .setNegativeButton("取消", null)
                .setNeutralButton("发送到电脑", (dialog, which) -> {
                    String selectedAudience = audience.getText().toString().trim();
                    String selectedGoal = goal.getText().toString().trim();
                    int selectedDuration = parseVideoDuration(duration.getText().toString());
                    AgentTaskDialogs tasks = new AgentTaskDialogs(this,
                            agentConnectionStore, aiExecutor, this::launchCreateAgentArtifactDocument);
                    tasks.chooseAndSubmitBundle("生成讲解视频 · " + note.title,
                            "请按任务包 request.json 先生成分镜和审阅产物，等待用户确认，不开始完整视频渲染。",
                            note.noteId, Math.max(1L, note.sourceModifiedAt / 1000L), () -> {
                                String markdown = vaultStore.read(note.fileName);
                                java.io.ByteArrayOutputStream output =
                                        new java.io.ByteArrayOutputStream();
                                VideoTaskBundleIO.write(output, note.noteId,
                                        Math.max(1L, note.sourceModifiedAt / 1000L), note.title,
                                        markdown, selectedAudience.isEmpty()
                                                ? "希望理解这份笔记的学习者" : selectedAudience,
                                        selectedGoal.isEmpty()
                                                ? "理解并记住这份笔记的核心概念" : selectedGoal,
                                        selectedDuration, "zh-CN-neutral", 1f);
                                return output.toByteArray();
                            });
                })
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
        new AgentConnectionDialogs(this, agentConnectionStore, aiExecutor,
                this::refreshBookshelf, this::launchAgentPairingQr,
                this::launchCreateAgentArtifactDocument).show();
    }

    private void launchAgentPairingQr() {
        new IntentIntegrator(this).setDesiredBarcodeFormats(
                        java.util.Collections.singletonList(IntentIntegrator.QR_CODE))
                .setPrompt("扫描电脑连接助手显示的二维码")
                .setBeepEnabled(false).setOrientationLocked(false).initiateScan();
    }

    private void launchCreateAgentArtifactDocument(AgentTaskStore.Task task,
                                                   AgentTaskStore.Artifact artifact) {
        pendingAgentArtifactTaskId = task.clientTaskId;
        pendingAgentArtifactId = artifact.id;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(artifact.mediaType == null || artifact.mediaType.isEmpty()
                ? "application/octet-stream" : artifact.mediaType);
        intent.putExtra(Intent.EXTRA_TITLE, safeFileName(artifact.name));
        startActivityForResult(intent, REQUEST_EXPORT_AGENT_ARTIFACT);
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
        releaseAiSelectionSnapshot();
        aiConversationLoadSerial += 1;
        aiConversationSnapshot = null;
        aiConversationSaveFailureShown = false;
        aiVisibleTimeline.clear();
        aiMessages.clear();
        aiReadScope = null;
        aiSessionTranscript = null;
        aiUploadConfirmed = false;
        aiPdfDigest = "";
        aiPdfDigestAvailable = false;
        currentNoteId = entry.id;
        currentNoteTitle = entry.title;
        editorVisible = true;
        restoreCompleted = false;
        documentRevision = 0;
        lastSavedRevision = 0;
        saveInFlight = false;
        saveQueued = false;
        pendingLeaveAfterSave = false;
        pendingSaveToast = false;
        pendingUnsavedJson = null;
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
                        aiConversationStore.clear(entry.id);
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

        aiSettingsButton = aiHeaderControl("⚙", "配置 AI API");
        aiSettingsButton.setOnClickListener(view -> showAiManagerDialog(null));
        header.addView(aiSettingsButton);
        aiMinimizeButton = aiHeaderControl("—", "最小化 AI 卡片");
        aiMinimizeButton.setOnClickListener(view -> setAiCardMinimized(!aiCardMinimized));
        header.addView(aiMinimizeButton);
        TextView clearConversation = aiHeaderControl("⌫", "清空本笔记 AI 对话");
        clearConversation.setOnClickListener(view -> confirmClearAiConversation());
        header.addView(clearConversation);
        TextView close = aiHeaderControl("×", "关闭 AI 卡片");
        close.setOnClickListener(view -> closeAiCard());
        header.addView(close);
        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        aiCardBody = new LinearLayout(this);
        aiCardBody.setOrientation(LinearLayout.VERTICAL);
        aiCardBody.setPadding(dp(14), dp(12), dp(14), dp(12));
        aiConversationScroll = new ScrollView(this);
        aiConversationScroll.setFillViewport(false);
        LinearLayout scrollContent = new LinearLayout(this);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        aiConversationScroll.addView(scrollContent, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        aiSelectionPreview = new ImageView(this);
        aiSelectionPreview.setContentDescription("本次 AI 对话使用的圈选图片");
        aiSelectionPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        aiSelectionPreview.setBackgroundColor(Color.rgb(235, 236, 232));
        scrollContent.addView(aiSelectionPreview, new LinearLayout.LayoutParams(
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
        aiInlineOutputButton = aiCardButton("允许工具写入");
        aiInlineOutputButton.setContentDescription("允许 AI 使用笔记工具在空白处写入");
        aiInlineOutputButton.setOnClickListener(view -> setAiOutputInline(true, true));
        outputModeRow.addView(aiInlineOutputButton,
                new LinearLayout.LayoutParams(0, dp(38), 1f));
        aiCardOutputButton = aiCardButton("仅卡片");
        aiCardOutputButton.setContentDescription("AI 回答只显示在对话卡片中");
        aiCardOutputButton.setOnClickListener(view -> setAiOutputInline(false, true));
        LinearLayout.LayoutParams cardModeParams = new LinearLayout.LayoutParams(0, dp(38), 0.85f);
        cardModeParams.setMargins(dp(6), 0, 0, 0);
        outputModeRow.addView(aiCardOutputButton, cardModeParams);
        scrollContent.addView(outputModeRow);

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
        scrollContent.addView(presets);

        aiDiagramButton = aiCardButton("画示意图 · Beta");
        aiDiagramButton.setOnClickListener(view -> requestAiMessage(
                "请把圈选内容中最适合图解的关系画成一张简明示意图，调用 draw_diagram 写入笔记。"
                        + "先查看页面空白，默认放在相关原文下方；不要遮挡手写。标签用中文，辨认不清时先问我。"));
        scrollContent.addView(aiDiagramButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        aiMaterialsSummary = text("额外材料：未选择（默认不读取知识库）", 11, FAINT_TEXT);
        aiMaterialsSummary.setPadding(dp(4), dp(7), dp(4), dp(2));
        scrollContent.addView(aiMaterialsSummary);
        LinearLayout materialRow = new LinearLayout(this);
        materialRow.setOrientation(LinearLayout.HORIZONTAL);
        aiMaterialsButton = aiCardButton("选择额外材料");
        aiMaterialsButton.setOnClickListener(view -> showAiMaterialPicker());
        materialRow.addView(aiMaterialsButton, new LinearLayout.LayoutParams(0, dp(38), 1f));
        aiMaterialsPreviewButton = aiCardButton("预览");
        aiMaterialsPreviewButton.setEnabled(false);
        aiMaterialsPreviewButton.setOnClickListener(view -> showAiMaterialPreview());
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(0, dp(38), 0.55f);
        previewParams.setMargins(dp(6), 0, 0, 0);
        materialRow.addView(aiMaterialsPreviewButton, previewParams);
        scrollContent.addView(materialRow);

        aiReviewTranscriptCheck = new CheckBox(this);
        aiReviewTranscriptCheck.setText("两段式转写后先校对");
        aiReviewTranscriptCheck.setTextSize(12);
        aiReviewTranscriptCheck.setTextColor(SECONDARY_TEXT);
        aiReviewTranscriptCheck.setChecked(
                preferences.getBoolean("aiReviewTranscription", false));
        aiReviewTranscriptCheck.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean("aiReviewTranscription", checked).apply());
        aiReviewTranscriptCheck.setContentDescription(
                "可选：先编辑手写转写，再交给回答模型；默认直接继续");
        scrollContent.addView(aiReviewTranscriptCheck,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        aiConversationView = new LinearLayout(this);
        aiConversationView.setOrientation(LinearLayout.VERTICAL);
        aiConversationView.setPadding(0, dp(8), 0, dp(8));
        scrollContent.addView(aiConversationView, new LinearLayout.LayoutParams(
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
            if (aiBusy) {
                cancelActiveAiRequest(true);
                return;
            }
            String message = aiInputView.getText().toString().trim();
            if (!message.isEmpty()) {
                requestAiMessage(message);
            }
        });
        aiRetryButton = aiCardButton("重试");
        aiRetryButton.setVisibility(View.GONE);
        aiRetryButton.setContentDescription("使用原请求、材料范围和接收地址重试");
        aiRetryButton.setOnClickListener(view -> retryAiRequest());
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(dp(68), dp(54));
        retryParams.setMargins(dp(6), 0, 0, 0);
        inputRow.addView(aiRetryButton, retryParams);
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

    void setAiOutputInline(boolean inline, boolean persist) {
        if (aiBusy || aiMaterialLoading) {
            Toast.makeText(this, aiMaterialLoading
                            ? "正在冻结材料，请完成或取消后再修改写入权限"
                            : "当前请求已冻结写入权限；完成或取消后再修改",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        boolean changed = aiOutputInline != inline;
        aiOutputInline = inline;
        // The switch now gates authority rather than destination: the model always
        // replies in the card, and this decides whether it may also write to the
        // note. Modifying content the user already arranged still needs a separate
        // grant, which v1 never issues.
        aiGrantedPermission = inline
                ? NoteTool.Permission.CREATE_IN_FREE_SPACE
                : NoteTool.Permission.READ_ONLY;
        if (changed) {
            rotateAiWireContextPreservingMaterials();
        }
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

    /** Starts a fresh wire context while keeping the visible audit trail. */
    private void rotateAiWireContextPreservingMaterials() {
        AiVaultSnapshot retainedVault = aiReadScope == null
                ? null : aiReadScope.vaultSnapshot();
        boolean sourceStillCurrent = aiConversationSnapshot == null || (canvasView != null
                && aiConversationSnapshot.binding.semanticDigest.equals(
                canvasView.aiConversationFingerprint())
                && aiConversationSnapshot.binding.pdfDigest.equals(aiPdfDigest));
        clearAiRequestState();
        aiSessionSerial += 1;
        aiMessages.clear();
        aiExecutedToolCallIds.clear();
        aiSessionTranscript = null;
        aiToolsHonoured = false;
        aiUploadConfirmed = false;
        if (!sourceStillCurrent) {
            releaseAiSelectionSnapshot();
            aiReadScope = null;
        } else if (aiReadScope != null) {
            AiConfigStore.Profile active = aiConfigStore.activeProfile();
            aiReadScope = AiReadScope.selectionOnly(currentNoteId, aiSessionSerial,
                    aiProfileIdentity(active)).withVault(retainedVault);
        }
        appendAiBoundary("发送范围或权限已变化；此前聊天仅保留供查看，不会继续发送。");
        refreshAiRecipientBindingPreservingSource();
        updateAiMaterialsUi();
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
            if (aiConversationSnapshot != null || !aiVisibleTimeline.isEmpty()) {
                showAiCard();
            } else if (aiConversationLoading) {
                Toast.makeText(this, "正在恢复本笔记的 AI 对话", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "请先圈选内容，或打开已有对话", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (aiConversationSnapshot != null && aiSelectionSnapshot != null) {
            new AlertDialog.Builder(this)
                    .setTitle("继续旧材料，还是使用当前圈选？")
                    .setMessage("继续会保留可发送上下文。使用当前圈选会保留可见聊天，"
                            + "但旧聊天和旧图片不会继续发送。")
                    .setNegativeButton("取消", null)
                    .setNeutralButton("继续旧材料", (dialog, which) -> showAiCard())
                    .setPositiveButton("使用当前圈选", (dialog, which) ->
                            replaceAiSelectionWithCurrent())
                    .show();
            return;
        }
        replaceAiSelectionWithCurrent();
    }

    private void replaceAiSelectionWithCurrent() {
        NoteCanvasView.AiSelectionSnapshot snapshot = canvasView.renderAiSelection(1600);
        if (snapshot == null) {
            Toast.makeText(this, "无法生成当前选区", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean replacingContext = aiConversationSnapshot != null || !aiVisibleTimeline.isEmpty();
        clearAiRequestState();
        releaseAiSelectionSnapshot();
        aiSessionSerial += 1;
        aiExecutedToolCallIds.clear();
        aiSelectionSnapshot = snapshot;
        aiResultAnchorBounds = new RectF(snapshot.sourceBounds);
        aiMessages.clear();
        aiSessionTranscript = null;
        aiToolsHonoured = false;
        aiUploadConfirmed = false;
        AiConfigStore.Profile activeProfile = aiConfigStore.activeProfile();
        aiReadScope = AiReadScope.selectionOnly(currentNoteId, aiSessionSerial,
                aiProfileIdentity(activeProfile));
        updateAiMaterialsUi();
        setAiBusy(false);
        clearAiConversationViews();
        renderAiTimeline();
        aiSelectionPreview.setImageBitmap(snapshot.bitmap);
        String mask = snapshot.lassoMaskApplied ? "套索遮罩已生效" : "矩形选区";
        aiStatusView.setText(String.format(Locale.CHINA,
                "%d × %d · %.1f KB · %s · 首次发送前会确认",
                snapshot.bitmap.getWidth(), snapshot.bitmap.getHeight(),
                snapshot.pngBytes.length / 1024f, mask));
        addAiNotice(replacingContext
                ? "已用当前圈选开始新的发送上下文；旧聊天仅保留供查看，不会发送给模型。"
                : (aiOutputInline
                ? "选区已载入。已允许模型按需调用笔记工具；普通文字回答仍先留在卡片。"
                : "选区已载入。回答只显示在对话卡片，模型不能写入页面。"));
        initializeAiConversationForSelection(activeProfile);
        showAiCard();
    }

    private void showAiCard() {
        if (aiCard == null) return;
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

    private void updateAiMaterialsUi() {
        if (aiMaterialsSummary == null || aiMaterialsButton == null
                || aiMaterialsPreviewButton == null) {
            return;
        }
        List<String> titles = aiReadScope == null
                ? java.util.Collections.emptyList() : aiReadScope.vaultTitles();
        if (titles.isEmpty()) {
            aiMaterialsSummary.setText("额外材料：未选择（默认不读取知识库）");
            aiMaterialsButton.setEnabled(!aiBusy && !aiMaterialLoading);
            aiMaterialsPreviewButton.setEnabled(false);
            return;
        }
        StringBuilder names = new StringBuilder();
        for (int index = 0; index < titles.size(); index++) {
            if (index > 0) {
                names.append("、");
            }
            names.append(titles.get(index));
        }
        aiMaterialsSummary.setText("额外材料：" + titles.size() + " 本 · " + names);
        aiMaterialsButton.setEnabled(!aiBusy && !aiMaterialLoading);
        aiMaterialsPreviewButton.setEnabled(!aiBusy && !aiMaterialLoading);
    }

    /** Lets the user freeze an explicit subset; merely opening the list grants nothing. */
    private void showAiMaterialPicker() {
        if (aiBusy || aiMaterialLoading) {
            Toast.makeText(this, "请等待当前回答完成", Toast.LENGTH_SHORT).show();
            return;
        }
        if (aiReadScope == null || aiSelectionSnapshot == null) {
            Toast.makeText(this, "请先圈选笔记", Toast.LENGTH_SHORT).show();
            return;
        }
        List<VaultStore.VaultNote> available = vaultStore.list();
        if (available.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("选择额外材料")
                    .setMessage("知识库还没有格式笔记。请先在书架将具体笔记转为格式笔记。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        String[] labels = new String[available.size()];
        boolean[] checked = new boolean[available.size()];
        Set<String> selected = aiReadScope.vaultNoteIds();
        for (int index = 0; index < available.size(); index++) {
            VaultStore.VaultNote note = available.get(index);
            labels[index] = note.title == null || note.title.isEmpty()
                    ? note.fileName.replaceAll("\\.md$", "") : note.title;
            checked[index] = selected.contains(note.noteId);
        }
        createAiMaterialPickerDialog(available, labels, checked, ids -> {
                    final AiReadScope sourceScope = aiReadScope;
                    final int sourceSession = aiSessionSerial;
                    if (ids.isEmpty()) {
                        replaceAiReadScope(sourceScope.withVault(null),
                                "已取消额外材料权限，新对话只读取圈选内容。");
                        return;
                    }
                    aiStatusView.setText("正在冻结已选材料…");
                    setAiMaterialLoading(true);
                    storageExecutor.execute(() -> {
                        try {
                            AiVaultSnapshot snapshot = AiVaultSnapshot.capture(vaultStore, ids);
                            runOnUiThread(() -> {
                                if (aiBusy || sourceSession != aiSessionSerial
                                        || aiReadScope != sourceScope) {
                                    return;
                                }
                                setAiMaterialLoading(false);
                                replaceAiReadScope(sourceScope.withVault(snapshot),
                                        "已冻结 " + snapshot.titles().size()
                                                + " 本额外材料，新对话仅可读取这些副本。");
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> {
                                if (sourceSession != aiSessionSerial) {
                                    return;
                                }
                                aiStatusView.setText("额外材料未加入");
                                setAiMaterialLoading(false);
                                Toast.makeText(this, error.getMessage() == null
                                                ? "读取材料失败" : error.getMessage(),
                                        Toast.LENGTH_LONG).show();
                            });
                        }
                    });
                }).show();
    }

    /** Production dialog factory kept package-visible for an ActivityScenario UI assertion. */
    AlertDialog createAiMaterialPickerDialog(List<VaultStore.VaultNote> available,
                                               String[] labels, boolean[] checked,
                                               Consumer<Set<String>> onConfirm) {
        return new AlertDialog.Builder(this)
                .setTitle("选择本次可读材料（改变后开始新对话）")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                        checked[which] = isChecked)
                .setNegativeButton("取消", null)
                .setPositiveButton("冻结选定材料", (dialog, which) -> {
                    Set<String> ids = new java.util.LinkedHashSet<>();
                    for (int index = 0; index < available.size(); index++) {
                        if (checked[index]) {
                            ids.add(available.get(index).noteId);
                        }
                    }
                    onConfirm.accept(ids);
                })
                .create();
    }

    private void showAiMaterialPreview() {
        if (aiBusy || aiMaterialLoading || aiReadScope == null || !aiReadScope.hasVaultNotes()) {
            return;
        }
        AiVaultSnapshot snapshot = aiReadScope.vaultSnapshot();
        StringBuilder preview = new StringBuilder();
        for (VaultStore.VaultNote note : snapshot.list()) {
            if (preview.length() > 0) {
                preview.append("\n\n──────\n\n");
            }
            preview.append("【").append(note.title).append("】\n");
            String content = snapshot.read(note.fileName);
            int limit = Math.min(content.length(), 8000);
            preview.append(content, 0, limit);
            if (limit < content.length()) {
                preview.append("\n…（预览已截断）");
            }
        }
        TextView content = text(preview.toString(), 12, INK_COLOR);
        content.setTextIsSelectable(true);
        content.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, matchWrap());
        new AlertDialog.Builder(this)
                .setTitle("本次可读材料预览")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** Scope changes create a fresh context so old model/tool text cannot cross the boundary. */
    void replaceAiReadScope(AiReadScope next, String notice) {
        if (aiBusy || next == null) {
            return;
        }
        clearAiRequestState();
        if (aiConversationSnapshot != null && canvasView != null
                && (!aiConversationSnapshot.binding.semanticDigest.equals(
                canvasView.aiConversationFingerprint())
                || !aiConversationSnapshot.binding.pdfDigest.equals(aiPdfDigest))) {
            releaseAiSelectionSnapshot();
            next = null;
        }
        aiSessionSerial += 1;
        aiReadScope = next;
        aiMessages.clear();
        aiExecutedToolCallIds.clear();
        aiSessionTranscript = null;
        aiToolsHonoured = false;
        aiUploadConfirmed = false;
        appendAiBoundary(notice);
        refreshAiRecipientBindingPreservingSource();
        if (aiStatusView != null) {
            aiStatusView.setText("材料范围已更新 · 发送前会重新确认");
        }
        updateAiMaterialsUi();
    }

    void resetAiContextForProfileChange(AiConfigStore.Profile profile) {
        if (aiBusy || aiMaterialLoading) {
            return;
        }
        clearAiRequestState();
        if (aiReadScope != null) {
            replaceAiReadScope(aiReadScope.withProfile(aiProfileIdentity(profile)),
                    "AI 配置已变更，已开始新对话并保留当前已选材料副本。");
            return;
        }
        aiSessionSerial += 1;
        aiMessages.clear();
        aiExecutedToolCallIds.clear();
        aiSessionTranscript = null;
        aiToolsHonoured = false;
        aiUploadConfirmed = false;
        if (aiConversationSnapshot != null) {
            appendAiBoundary("AI 配置已变更；旧聊天仅保留供查看，不会发送给新的接收模型。");
            refreshAiRecipientBindingPreservingSource();
        }
    }

    boolean aiRecipientChangeAllowed() {
        return !aiBusy && !aiMaterialLoading;
    }

    private boolean requireAiRecipientChangeAllowed() {
        if (aiRecipientChangeAllowed()) return true;
        String message = aiMaterialLoading
                ? "正在冻结材料，请完成或取消后再修改 AI 配置"
                : "AI 请求进行中，请完成或取消后再修改接收模型";
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (aiStatusView != null) aiStatusView.setText(message);
        return false;
    }

    boolean activateAiProfile(AiConfigStore.Profile profile) {
        if (profile == null || profile.id == null || profile.id.isEmpty()) return false;
        if (profile.id.equals(aiConfigStore.activeProfileId())) return true;
        if (!requireAiRecipientChangeAllowed()) return false;
        aiConfigStore.setActiveProfileId(profile.id);
        resetAiContextForProfileChange(profile);
        return true;
    }

    boolean deleteAiProfile(AiConfigStore.Profile profile) {
        if (profile == null || profile.id == null || profile.id.isEmpty()) return false;
        boolean removedActive = profile.id.equals(aiConfigStore.activeProfileId());
        if (removedActive && !requireAiRecipientChangeAllowed()) return false;
        aiConfigStore.deleteProfile(profile.id);
        if (removedActive) resetAiContextForProfileChange(aiConfigStore.activeProfile());
        return true;
    }

    boolean saveAiProfile(AiConfigStore.Profile profile, String directKey,
                          String transcribeKey, String answerKey,
                          boolean activate) throws Exception {
        if (profile == null) return false;
        boolean affectsActive = activate
                || java.util.Objects.equals(profile.id, aiConfigStore.activeProfileId());
        if (affectsActive && !requireAiRecipientChangeAllowed()) return false;
        aiConfigStore.saveProfile(profile, directKey, transcribeKey, answerKey);
        if (activate) aiConfigStore.setActiveProfileId(profile.id);
        if (affectsActive) {
            resetAiContextForProfileChange(profile);
        }
        return true;
    }

    private static String aiProfileIdentity(AiConfigStore.Profile profile) {
        if (profile == null) {
            return "";
        }
        return profile.id + "\n" + profile.split + "\n"
                + profile.directEndpoint + "\n" + profile.directModel + "\n"
                + profile.transcribeEndpoint + "\n" + profile.transcribeModel + "\n"
                + profile.answerEndpoint + "\n" + profile.answerModel;
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
        if (aiBusy || aiMaterialLoading) {
            aiCardTitle.setText(R.string.ai_card_busy);
        } else if (aiCardMinimized) {
            aiCardTitle.setText(R.string.ai_card_minimized);
        } else {
            aiCardTitle.setText(R.string.ai_card_drag);
        }
    }

    private void closeAiCard() {
        collapseAiCard(true);
    }

    private void collapseAiCard(boolean userVisible) {
        boolean cancelledRequest = activeAiRequest != null;
        clearAiRequestState();
        aiSessionSerial += 1;
        aiMaterialLoading = false;
        setAiBusy(false);
        if (cancelledRequest) {
            addAiNotice("收起卡片时已停止本机等待；请求若已到达服务商，处理或计费可能仍继续。");
        }
        persistAiConversation();
        if (aiCard != null) {
            aiCard.setVisibility(View.GONE);
        }
        if (userVisible && saveStatusView != null) {
            saveStatusView.setText("AI 对话已收起，可从工具栏恢复");
        }
    }

    private void confirmClearAiConversation() {
        if (aiBusy || aiMaterialLoading) {
            Toast.makeText(this, "请先取消当前请求，再清空对话", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("清空本笔记的 AI 对话？")
                .setMessage("可见聊天、发送上下文和冻结材料都会从本机删除。笔记内容不会改变。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> clearAiConversationExplicitly())
                .show();
    }

    private void clearAiConversationExplicitly() {
        clearAiRequestState();
        aiSessionSerial += 1;
        // A first-session create may still be running on the storage executor.
        // Invalidate its UI callback before enqueueing the durable tombstone so
        // the completed create cannot repopulate memory after an explicit clear.
        aiConversationLoadSerial += 1;
        aiConversationLoading = false;
        updateAiInteractionEnabled();
        String noteId = currentNoteId;
        int clearSession = aiSessionSerial;
        AiConversationStore.Snapshot previous = aiConversationSnapshot;
        aiConversationSnapshot = null;
        aiConversationSaveFailureShown = false;
        aiVisibleTimeline.clear();
        aiMessages.clear();
        aiExecutedToolCallIds.clear();
        aiSessionTranscript = null;
        aiToolsHonoured = false;
        aiUploadConfirmed = false;
        aiReadScope = null;
        releaseAiSelectionSnapshot();
        clearAiConversationViews();
        updateAiMaterialsUi();
        aiStatusView.setText("对话已清空 · 圈选内容可开始新对话");
        if (noteId != null) storageExecutor.execute(() -> {
            try { aiConversationStore.clear(noteId); }
            catch (Exception error) { runOnUiThread(() -> {
                if (clearSession != aiSessionSerial || !noteId.equals(currentNoteId)) return;
                if (previous != null) {
                    aiConversationSnapshot = previous;
                    aiVisibleTimeline.clear();
                    aiVisibleTimeline.addAll(previous.visibleTimeline);
                    aiMessages.clear();
                    aiMessages.addAll(previous.wireHistory);
                    aiSessionTranscript = previous.transcript;
                    aiUploadConfirmed = previous.uploadConfirmed;
                    try {
                        aiSelectionSnapshot = previous.selection == null
                                ? null : previous.selection.toCanvas();
                        if (aiSelectionSnapshot != null) {
                            aiSelectionPreview.setImageBitmap(aiSelectionSnapshot.bitmap);
                            aiReadScope = AiReadScope.selectionOnly(currentNoteId,
                                    aiSessionSerial, aiProfileIdentity(aiConfigStore.activeProfile()))
                                    .withVault(previous.vault);
                        }
                    } catch (Exception ignored) { aiSelectionSnapshot = null; }
                    renderAiTimeline();
                }
                aiStatusView.setText("对话清空失败，原记录仍保留：" + safeError(error));
            }); }
        });
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
        if (!renderingAiTimeline) {
            aiVisibleTimeline.add(new AiConversationStore.VisibleEntry(
                    "notice", "assistant", message, "", false));
            persistAiConversation();
        }
        // Recipient/material changes can rotate the wire context while the AI
        // card is not inflated (for example, from the profile manager). Keep
        // the audit entry above; rendering will replay it when the card opens.
        if (aiConversationView == null) return;
        TextView notice = text(message, 12, Color.rgb(94, 107, 117));
        notice.setGravity(Gravity.CENTER);
        notice.setPadding(dp(12), dp(8), dp(12), dp(8));
        aiConversationView.addView(notice, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollAiConversationToBottom();
    }

    private void ensureAiEditCard(AiPendingRequest request) {
        if (request == null || !request.edits.hasChanges()) return;
        if (request.editBinding == null) {
            String receipt = "";
            String receiptDigest = "";
            try {
                JSONObject receiptJson = canvasView.serializeAiEditRecord(request.edits);
                receipt = receiptJson.toString();
                receiptDigest = receiptJson.optString("digest");
            } catch (Exception ignored) { }
            aiVisibleTimeline.add(new AiConversationStore.VisibleEntry("result", "assistant",
                    "本轮已写入 " + request.edits.changedFlowCount() + " 处文字修改",
                    request.answerExecutorLabel, false, request.edits.changedFlowIds(),
                    receiptDigest, receipt));
            persistAiConversation();
            request.editBinding = new AiEditCardBinding(request.noteId, request.edits);
            installAiEditCard(request.editBinding);
        }
        refreshAiEditCard(request.editBinding);
    }

    private void installAiEditCard(AiEditCardBinding binding) {
        if (binding == null || aiConversationView == null || binding.card != null) return;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(roundedBackground(Color.rgb(241, 246, 250),
                Color.rgb(180, 197, 210), 10));
        TextView status = text("", 12, INK_COLOR);
        status.setContentDescription("AI 修改状态");
        card.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button locate = aiCardButton("定位结果");
        locate.setContentDescription("定位本次 AI 修改结果");
        locate.setOnClickListener(view -> {
            if (canvasView != null && canvasView.locateAiEdit(binding.record)) {
                aiStatusView.setText("已定位本次 AI 修改");
            }
        });
        actions.addView(locate, new LinearLayout.LayoutParams(0, dp(40), 0.7f));
        Button action = aiCardButton("撤销本次 AI 修改");
        action.setContentDescription("撤销或重新应用本次 AI 修改");
        action.setOnClickListener(view -> applyAiEditCard(binding));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        actionParams.setMargins(dp(6), 0, 0, 0);
        actions.addView(action, actionParams);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        actionsParams.setMargins(0, dp(8), 0, 0);
        card.addView(actions, actionsParams);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, dp(5), 0, dp(5));
        aiConversationView.addView(card, cardParams);
        binding.card = card;
        binding.status = status;
        binding.action = action;
        binding.locate = locate;
        aiEditCards.add(binding);
        refreshAiEditCard(binding);
        scrollAiConversationToBottom();
    }

    private void applyAiEditCard(AiEditCardBinding binding) {
        if (binding == null || canvasView == null || aiBusy) return;
        if (!java.util.Objects.equals(binding.noteId, currentNoteId)) {
            binding.status.setText("这项结果属于另一份笔记，无法在当前页面操作");
            binding.action.setEnabled(false);
            return;
        }
        NoteCanvasView.AiEditState state = canvasView.aiEditState(binding.record);
        boolean reapply = state == NoteCanvasView.AiEditState.UNDONE;
        NoteCanvasView.AiEditApplyResult result = canvasView.applyAiEdit(
                binding.record, reapply);
        if (result.changedFlows > 0) {
            updateTextBoxOverlays(canvasView.getTextBoxes());
        } else if (result.state == NoteCanvasView.AiEditState.CONFLICT) {
            addAiNotice("相关文字已被编辑或删除。为保护当前内容，本次操作没有修改任何对象。");
        } else if (result.state == NoteCanvasView.AiEditState.BUSY) {
            addAiNotice("请先结束当前书写或拖动，再操作本次 AI 修改。");
        }
        refreshAiEditCards();
    }

    private void refreshAiEditCards() {
        if (canvasView == null || aiEditCards.isEmpty()) return;
        for (AiEditCardBinding binding : new ArrayList<>(aiEditCards)) {
            refreshAiEditCard(binding);
        }
    }

    private void refreshAiEditCard(AiEditCardBinding binding) {
        if (binding == null || binding.status == null || binding.action == null
                || binding.locate == null) return;
        if (!java.util.Objects.equals(binding.noteId, currentNoteId)) {
            binding.status.setText("本次 AI 修改属于另一份笔记");
            binding.action.setEnabled(false);
            binding.locate.setEnabled(false);
            return;
        }
        NoteCanvasView.AiEditState state = canvasView.aiEditState(binding.record);
        int count = binding.record.changedFlowCount();
        binding.locate.setEnabled(!aiBusy && canvasView.hasAiEditTarget(binding.record));
        switch (state) {
            case APPLIED:
                binding.status.setText("本次 AI 修改已写入笔记 · " + count + "处文字修改");
                binding.action.setText("撤销本次 AI 修改");
                binding.action.setEnabled(!aiBusy);
                break;
            case UNDONE:
                binding.status.setText("本次 AI 修改已撤销");
                binding.action.setText("重新应用本次 AI 修改");
                binding.action.setEnabled(!aiBusy);
                break;
            case MIXED:
                binding.status.setText("本次 AI 修改已被普通撤销部分改变");
                binding.action.setText("撤销剩余 AI 修改");
                binding.action.setEnabled(!aiBusy);
                break;
            case CONFLICT:
                binding.status.setText("相关文字已被编辑或删除 · 已保护当前内容");
                binding.action.setText("无法安全覆盖");
                binding.action.setEnabled(false);
                break;
            case BUSY:
                binding.status.setText("请先结束当前书写或拖动");
                binding.action.setEnabled(false);
                break;
            case EMPTY:
            default:
                binding.status.setText("本次请求没有修改笔记");
                binding.action.setVisibility(View.GONE);
                break;
        }
    }

    /** Test-only fixture hook: attaches a local edit record without a network request. */
    void installAiEditRecordForTest(String noteId, NoteCanvasView.AiEditRecord record) {
        currentNoteId = noteId;
        editorVisible = true;
        if (aiCard != null) aiCard.setVisibility(View.VISIBLE);
        AiEditCardBinding binding = new AiEditCardBinding(noteId, record);
        installAiEditCard(binding);
    }

    NoteCanvasView canvasForTest() { return canvasView; }

    private void addAiMessageBubble(String role, String message, boolean error) {
        addAiMessageBubble(role, message, error, "");
    }

    private void addAiMessageBubble(String role, String message, boolean error,
                                    String executorLabel) {
        if (!renderingAiTimeline) {
            aiVisibleTimeline.add(new AiConversationStore.VisibleEntry(
                    "message", role, message, executorLabel, error));
            persistAiConversation();
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity("user".equals(role) ? Gravity.END : Gravity.START);
        if (executorLabel != null && !executorLabel.isEmpty()) {
            TextView executor = text(executorLabel, 10, Color.rgb(102, 113, 122));
            executor.setContentDescription("本轮实际执行者");
            row.addView(executor, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        int fill = error ? Color.rgb(252, 232, 229)
                : ("user".equals(role) ? Color.rgb(218, 230, 247) : Color.WHITE);
        int stroke = error ? Color.rgb(222, 157, 151) : Color.rgb(214, 220, 224);
        View bubble;
        LinearLayout.LayoutParams bubbleParams;
        if (!error && "assistant".equals(role) && AiMathWebView.containsMath(message)) {
            AiMathWebView formulaView = new AiMathWebView(this, message, false);
            AiMathWebView[] formulaHolder = new AiMathWebView[]{formulaView};
            formulaView.setContentDescription("AI 回答；LaTeX 公式已本地可视化");
            LinearLayout formulaContainer = new LinearLayout(this);
            formulaContainer.setOrientation(LinearLayout.VERTICAL);
            formulaContainer.setBackground(roundedBackground(fill, stroke, 12));
            formulaContainer.addView(formulaView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
            LinearLayout recovery = new LinearLayout(this);
            recovery.setOrientation(LinearLayout.VERTICAL);
            recovery.setPadding(dp(10), dp(7), dp(10), dp(9));
            recovery.setVisibility(View.GONE);
            recovery.setContentDescription("AI 回答公式显示失败");
            TextView recoveryStatus = text("显示失败", 12, Color.rgb(143, 47, 43));
            recovery.addView(recoveryStatus, matchWrap());
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            Button viewSource = pillButton("查看源码", BUTTON_QUIET);
            Button copySource = pillButton("复制", BUTTON_QUIET);
            Button editDisplay = pillButton("编辑", BUTTON_QUIET);
            Button retry = pillButton("重新显示", BUTTON_TONAL);
            actions.addView(viewSource, new LinearLayout.LayoutParams(0, dp(38), 1f));
            actions.addView(copySource, new LinearLayout.LayoutParams(0, dp(38), .7f));
            actions.addView(editDisplay, new LinearLayout.LayoutParams(0, dp(38), .7f));
            actions.addView(retry, new LinearLayout.LayoutParams(0, dp(38), 1f));
            recovery.addView(actions, matchWrap());
            formulaContainer.addView(recovery, matchWrap());
            viewSource.setOnClickListener(view -> showLocalSource(
                    "AI 回答源码", formulaHolder[0].displaySource()));
            copySource.setOnClickListener(view -> copyLocalSource(
                    "PadNote AI 回答源码", formulaHolder[0].displaySource()));
            editDisplay.setOnClickListener(view -> editLocalDisplaySource(
                    "编辑本次显示副本", formulaHolder[0].displaySource(),
                    edited -> formulaHolder[0].renderDisplayCopy(edited),
                    "只修改本次本地显示，不会改变对话记录，也不会再次请求模型。"));
            retry.setOnClickListener(view -> {
                retry.setEnabled(false);
                recoveryStatus.setText("正在重新显示…");
                formulaHolder[0].retryCurrentRender();
            });
            configureAiMathRecovery(formulaView, formulaHolder, formulaContainer,
                    recovery, recoveryStatus, retry);
            formulaView.post(() -> {
                if (formulaHolder[0] == formulaView && formulaView.getParent() != null) {
                    formulaView.renderDisplayCopy(message);
                }
            });
            bubble = formulaContainer;
            bubbleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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

    private void configureAiMathRecovery(AiMathWebView view, AiMathWebView[] holder,
                                         LinearLayout container, LinearLayout recovery,
                                         TextView status, Button retry) {
        view.setRenderStateListener(state -> {
            if (holder[0] != view) return;
            retry.setEnabled(true);
            if (state.isReady()) {
                recovery.setVisibility(View.GONE);
                return;
            }
            status.setText("显示失败 · " + state.message);
            recovery.setVisibility(View.VISIBLE);
            if (state.failure == CompiledTextWebView.FailureKind.PROCESS_GONE) {
                String source = view.displaySource();
                container.post(() -> {
                    if (holder[0] != view || container.getParent() == null) return;
                    container.removeView(view);
                    // A renderer crash is an error state, not permission to start
                    // another attempt. Attach a fresh blank renderer and wait for
                    // the user's explicit "重新显示" action.
                    AiMathWebView replacement = new AiMathWebView(this, source, false);
                    replacement.setContentDescription("AI 回答；LaTeX 公式本地显示");
                    holder[0] = replacement;
                    container.addView(replacement, 0, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
                    configureAiMathRecovery(replacement, holder, container,
                            recovery, status, retry);
                });
            }
        });
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

    private void clearAiConversationViews() {
        if (aiConversationView == null) {
            return;
        }
        destroyMathViews(aiConversationView);
        aiConversationView.removeAllViews();
        aiEditCards.clear();
    }

    private void renderAiTimeline() {
        if (aiConversationView == null) return;
        clearAiConversationViews();
        renderingAiTimeline = true;
        try {
            String liveAdoptionDigest = null;
            for (int index = 0; index < aiVisibleTimeline.size(); index++) {
                AiConversationStore.VisibleEntry entry = aiVisibleTimeline.get(index);
                if ("notice".equals(entry.kind)) addAiNotice(entry.text);
                else if ("result".equals(entry.kind)) addColdAiResultCard(entry);
                else {
                    addAiMessageBubble(entry.role, entry.text, entry.error,
                            entry.executorLabel);
                    if (!entry.adoptionId.isEmpty()) {
                        if (liveAdoptionDigest == null && canvasView != null) {
                            liveAdoptionDigest = canvasView.aiConversationFingerprint();
                        }
                        addAnswerAdoptionAction(entry, index, liveAdoptionDigest);
                    }
                }
            }
        } finally {
            renderingAiTimeline = false;
        }
    }

    private void addColdAiResultCard(AiConversationStore.VisibleEntry entry) {
        if (canvasView != null && !entry.receiptJson.isEmpty()) {
            try {
                NoteCanvasView.AiEditRecord record = canvasView.restoreAiEditRecord(
                        new JSONObject(entry.receiptJson));
                if (record != null) {
                    installAiEditCard(new AiEditCardBinding(currentNoteId, record));
                    return;
                }
            } catch (Exception ignored) { }
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(roundedBackground(Color.rgb(241, 246, 250),
                Color.rgb(180, 197, 210), 10));
        card.setContentDescription("历史 AI 修改结果卡");
        TextView title = text(entry.text, 12, INK_COLOR);
        card.addView(title, matchWrap());
        TextView status = text("重新打开后仅供核对；未保存可完整验证的对象凭据，"
                + "因此不会开放撤销或重新应用。", 11, Color.rgb(94, 107, 117));
        status.setContentDescription("历史 AI 修改操作不可用");
        status.setPadding(0, dp(5), 0, 0);
        card.addView(status, matchWrap());
        aiConversationView.addView(card, matchWrap());
    }

    private void addAnswerAdoptionAction(AiConversationStore.VisibleEntry entry,
                                         int timelineIndex, String liveDigest) {
        if (aiConversationView == null || entry == null || entry.adoptionAnchor == null) return;
        Button apply = aiCardButton("写入笔记");
        apply.setContentDescription("将这条回答写入笔记");
        boolean available = canAdoptAnswer(entry, liveDigest);
        apply.setEnabled(available);
        if (!available) apply.setText("来源已变化，不能写入");
        apply.setOnClickListener(view -> applyAdoptableAnswer(entry.adoptionId,
                timelineIndex, apply));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        params.setMargins(0, 0, 0, dp(5));
        aiConversationView.addView(apply, params);
    }

    private boolean canAdoptAnswer(AiConversationStore.VisibleEntry entry) {
        return canAdoptAnswer(entry, canvasView == null
                ? null : canvasView.aiConversationFingerprint());
    }

    private boolean canAdoptAnswer(AiConversationStore.VisibleEntry entry,
                                   String liveDigest) {
        if (entry == null || entry.adoptionId.isEmpty() || entry.adoptionAnchor == null
                || entry.adopted || canvasView == null || canvasView.isUserInteractionActive()
                || !aiPdfDigestAvailable || currentNoteId == null) return false;
        if (!entry.adoptionSourceDigest.equals(liveDigest)
                || !entry.adoptionPdfDigest.equals(aiPdfDigest)) return false;
        try {
            NoteTool.Permission frozen = NoteTool.Permission.valueOf(entry.adoptionPermission);
            return frozen != NoteTool.Permission.READ_ONLY && frozen == aiGrantedPermission;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private void applyAdoptableAnswer(String adoptionId, int suggestedIndex, Button button) {
        int index = -1;
        if (suggestedIndex >= 0 && suggestedIndex < aiVisibleTimeline.size()
                && adoptionId.equals(aiVisibleTimeline.get(suggestedIndex).adoptionId)) {
            index = suggestedIndex;
        } else {
            for (int candidate = 0; candidate < aiVisibleTimeline.size(); candidate++) {
                if (adoptionId.equals(aiVisibleTimeline.get(candidate).adoptionId)) {
                    index = candidate;
                    break;
                }
            }
        }
        if (index < 0) return;
        AiConversationStore.VisibleEntry entry = aiVisibleTimeline.get(index);
        if (aiBusy || aiMaterialLoading || !canAdoptAnswer(entry)) {
            aiStatusView.setText("来源、权限或页面已变化，未写入旧回答");
            button.setEnabled(false);
            return;
        }
        NoteCanvasView.AiEditRecord record = canvasView.newAiEditRecord(
                "answer-adoption-" + entry.adoptionId);
        NoteCanvasView.AiEditSnapshot before = canvasView.captureAiEditSnapshot();
        canvasView.beginAiUndoTransaction(record.ownerId);
        NoteTextBox inserted = null;
        RuntimeException insertionFailure = null;
        boolean changed;
        try {
            inserted = canvasView.addAiResultTextBox(NoteTextBox.Format.MARKDOWN,
                    entry.text, entry.adoptionAnchor);
        } catch (RuntimeException failure) {
            insertionFailure = failure;
        } finally {
            changed = finishAiMutation(record, before);
        }
        if (!changed) {
            aiStatusView.setText(insertionFailure == null
                    ? "页面正被操作或没有可用位置，回答未写入"
                    : "回答写入失败，原回答仍保留在卡片中");
            button.setEnabled(false);
            return;
        }
        selectedTextBoxId = null;
        selectedTextFlowId = null;
        if (inserted != null) {
            aiResultAnchorBounds = new RectF(inserted.x, inserted.y,
                    inserted.x + inserted.width, inserted.y + inserted.height);
        }
        updateTextBoxOverlays(canvasView.getTextBoxes());
        // Queue the note write before the durable result receipt. If the process
        // stops between them, the frozen pre-write source digest still prevents
        // a document that was saved from accepting this answer a second time.
        saveDocument(false);
        recordCompletedAiEdit(record, entry.executorLabel, true);
        aiStatusView.setText(insertionFailure == null
                ? "回答已写入笔记，可从结果卡撤销"
                : "回答部分写入后显示异常，可从结果卡安全撤销");
        button.setEnabled(false);
    }

    private void appendAiBoundary(String message) {
        if (message == null || message.trim().isEmpty()) return;
        addAiNotice("上下文边界 · " + message);
    }

    private AiConversationStore.Binding currentAiBinding(AiConfigStore.Profile profile,
                                                          AiVaultSnapshot vault) {
        String material = vault == null ? "" : vault.digest();
        return new AiConversationStore.Binding(canvasView == null ? ""
                : canvasView.aiConversationFingerprint(), aiPdfDigest,
                profile == null ? "" : profile.id, profile == null ? 0 : profile.revision,
                aiGrantedPermission.name(), material);
    }

    private void initializeAiConversationForSelection(AiConfigStore.Profile profile) {
        if (currentNoteId == null || aiSelectionSnapshot == null) return;
        if (!aiPdfDigestAvailable) {
            aiStatusView.setText("PDF 原文无法校验，未建立可发送上下文");
            return;
        }
        AiVaultSnapshot vault = aiReadScope == null ? null : aiReadScope.vaultSnapshot();
        AiConversationStore.Binding binding = currentAiBinding(profile, vault);
        List<AiConversationStore.VisibleEntry> visible = new ArrayList<>(aiVisibleTimeline);
        AiConversationStore.Selection selection =
                AiConversationStore.Selection.fromCanvas(aiSelectionSnapshot);
        if (aiConversationSnapshot != null) {
            aiConversationSnapshot = aiConversationSnapshot.next(visible,
                    new ArrayList<>(), selection, vault, binding, null, false);
            persistAiConversationSnapshot(aiConversationSnapshot);
            return;
        }
        String noteId = currentNoteId;
        int loadOperation = ++aiConversationLoadSerial;
        aiConversationLoading = true;
        updateAiInteractionEnabled();
        storageExecutor.execute(() -> {
            try {
                AiConversationStore.Snapshot created = aiConversationStore.create(noteId,
                        visible, new ArrayList<>(), selection, vault, binding, null, false);
                runOnUiThread(() -> {
                    if (loadOperation != aiConversationLoadSerial
                            || !noteId.equals(currentNoteId)) return;
                    aiConversationSnapshot = created;
                    aiConversationLoading = false;
                    updateAiInteractionEnabled();
                    setActionEnabled(aiButton, true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (loadOperation != aiConversationLoadSerial
                            || !noteId.equals(currentNoteId)) return;
                    aiConversationLoading = false;
                    updateAiInteractionEnabled();
                    aiStatusView.setText("对话未保存：" + safeError(error));
                });
            }
        });
    }

    private void refreshAiConversationBinding() {
        if (aiConversationSnapshot == null || canvasView == null) return;
        AiConfigStore.Profile profile = aiConfigStore.activeProfile();
        AiVaultSnapshot vault = aiReadScope == null ? null : aiReadScope.vaultSnapshot();
        aiConversationSnapshot = aiConversationSnapshot.next(aiVisibleTimeline,
                aiMessages, AiConversationStore.Selection.fromCanvas(aiSelectionSnapshot),
                vault, currentAiBinding(profile, vault), aiSessionTranscript,
                aiUploadConfirmed);
        persistAiConversationSnapshot(aiConversationSnapshot);
    }

    private void refreshAiRecipientBindingPreservingSource() {
        if (aiConversationSnapshot == null) return;
        AiConfigStore.Profile profile = aiConfigStore.activeProfile();
        AiVaultSnapshot vault = aiReadScope == null ? null : aiReadScope.vaultSnapshot();
        AiConversationStore.Binding previous = aiConversationSnapshot.binding;
        AiConversationStore.Binding binding = new AiConversationStore.Binding(
                previous.semanticDigest, previous.pdfDigest,
                profile == null ? "" : profile.id, profile == null ? 0 : profile.revision,
                aiGrantedPermission.name(), vault == null ? "" : vault.digest());
        aiConversationSnapshot = aiConversationSnapshot.next(aiVisibleTimeline, aiMessages,
                AiConversationStore.Selection.fromCanvas(aiSelectionSnapshot), vault, binding,
                aiSessionTranscript, false);
        persistAiConversationSnapshot(aiConversationSnapshot);
    }

    private void persistAiConversation() {
        if (renderingAiTimeline || aiConversationSnapshot == null || canvasView == null) return;
        AiVaultSnapshot vault = aiReadScope == null ? null : aiReadScope.vaultSnapshot();
        aiConversationSnapshot = aiConversationSnapshot.next(aiVisibleTimeline,
                aiMessages, AiConversationStore.Selection.fromCanvas(aiSelectionSnapshot),
                vault, aiConversationSnapshot.binding, aiSessionTranscript,
                aiUploadConfirmed);
        persistAiConversationSnapshot(aiConversationSnapshot);
    }

    private void persistAiConversationSnapshot(AiConversationStore.Snapshot value) {
        storageExecutor.execute(() -> {
            try {
                aiConversationStore.save(value);
                runOnUiThread(() -> {
                    if (aiConversationSnapshot != null
                            && aiConversationSnapshot.conversationId.equals(value.conversationId)
                            && aiConversationSnapshot.revision >= value.revision) {
                        aiConversationSaveFailureShown = false;
                    }
                });
            }
            catch (Exception error) {
                try { aiConversationStore.preserveUnsaved(value); }
                catch (Exception ignored) { }
                runOnUiThread(() -> {
                if (aiConversationSnapshot != null
                        && aiConversationSnapshot.conversationId.equals(value.conversationId)
                        && aiConversationSnapshot.revision == value.revision
                        && aiStatusView != null) {
                    aiStatusView.setText("对话未保存：" + safeError(error));
                    if (!aiConversationSaveFailureShown) {
                        aiConversationSaveFailureShown = true;
                        new AlertDialog.Builder(this).setTitle("AI 对话未能保存")
                                .setMessage("当前卡片仍保留在内存中，磁盘上的上一份记录没有被覆盖。"
                                        + "可先导出恢复文件；空间不足时导出也可能失败。")
                                .setNegativeButton("继续查看", null)
                                .setPositiveButton("导出当前未保存对话", (dialog, which) ->
                                        launchAiConversationRecoveryExport(value))
                                .show();
                    }
                }
            }); }
        });
    }

    private boolean canContinueAiContext() {
        return aiPdfDigestAvailable && aiConversationSnapshot != null
                && aiSelectionSnapshot != null && canvasView != null
                && aiConversationSnapshot.binding.semanticDigest.equals(
                        canvasView.aiConversationFingerprint())
                && aiConversationSnapshot.binding.pdfDigest.equals(aiPdfDigest);
    }

    private void advanceAiConversationBaseline() {
        if (aiConversationSnapshot == null || canvasView == null) return;
        refreshAiConversationBinding();
    }

    private String executorLabel(AiConfigStore.Profile profile, boolean transcription) {
        if (profile == null) return "";
        String model = profile.split
                ? (transcription ? profile.transcribeModel : profile.answerModel)
                : profile.directModel;
        return (transcription ? "转写模型" : "回答模型") + " · "
                + profile.name + " · " + model;
    }

    // Package-visible hooks exercise the production state/store paths without a paid request.
    void startAiConversationForTest(NoteCanvasView.AiSelectionSnapshot snapshot) {
        clearAiRequestState();
        releaseAiSelectionSnapshot();
        aiSessionSerial += 1;
        aiSelectionSnapshot = snapshot;
        aiResultAnchorBounds = new RectF(snapshot.sourceBounds);
        aiSelectionPreview.setImageBitmap(snapshot.bitmap);
        AiConfigStore.Profile profile = aiConfigStore.activeProfile();
        aiReadScope = AiReadScope.selectionOnly(currentNoteId, aiSessionSerial,
                aiProfileIdentity(profile));
        aiMessages.clear();
        aiSessionTranscript = null;
        aiUploadConfirmed = false;
        addAiNotice("测试夹具已通过生产会话初始化路径载入圈选。");
        initializeAiConversationForSelection(profile);
        showAiCard();
    }

    void addCompletedAiTurnForTest(String user, String assistant, String executor,
                                   boolean validToolCall) {
        recordCompletedAiTurn(user, assistant, executor, validToolCall);
    }

    void addCompletedAiEditForTest(NoteCanvasView.AiEditRecord record, String executor) {
        recordCompletedAiEdit(record, executor, true);
    }

    boolean addAdoptableAnswerForTest(String answer, String executor) {
        commitAssistantMessage(null, answer, answer, executor);
        int index = registerAdoptableAnswer(answer, aiGrantedPermission, true);
        if (index < 0) return false;
        renderAiTimeline();
        return true;
    }

    private void recordCompletedAiEdit(NoteCanvasView.AiEditRecord record, String executor,
                                       boolean render) {
        if (record == null || !record.hasChanges()) return;
        String receipt = safeAiEditReceipt(record);
        String digest = "";
        try { digest = new JSONObject(receipt).optString("digest"); }
        catch (Exception ignored) { }
        aiVisibleTimeline.add(new AiConversationStore.VisibleEntry("result", "assistant",
                "本轮已写入 " + record.changedFlowCount() + " 处文字修改",
                executor, false, record.changedFlowIds(), digest, receipt));
        persistAiConversation();
        if (render) renderAiTimeline();
    }

    private String safeAiEditReceipt(NoteCanvasView.AiEditRecord record) {
        try { return canvasView.serializeAiEditRecord(record).toString(); }
        catch (Exception ignored) { return ""; }
    }

    private void recordCompletedAiTurn(String user, String assistant, String executor,
                                       boolean validToolCall) {
        OpenAiCompatibleClient.Message userMessage =
                new OpenAiCompatibleClient.Message("user", user);
        aiMessages.add(userMessage);
        addAiMessageBubble("user", user, false);
        commitAssistantMessage(null, assistant, assistant, executor);
        if (validToolCall) {
            recordToolEvidenceForTest(new OpenAiCompatibleClient.Completion("", "",
                    java.util.Collections.singletonList(new OpenAiCompatibleClient.ToolCall(
                            "test-read-page-map", "read_page_map", new JSONObject())), true));
        }
    }

    AiConversationStore.Snapshot conversationForTest() { return aiConversationSnapshot; }

    List<OpenAiCompatibleClient.Message> nextWireHistoryForTest() {
        return new ArrayList<>(aiMessages);
    }

    void collapseAiCardForTest() { collapseAiCard(false); }

    void clearAiConversationForTest() { clearAiConversationExplicitly(); }

    boolean aiConversationLoadingForTest() { return aiConversationLoading; }

    void awaitAiConversationStorageForTest() {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        storageExecutor.execute(done::countDown);
        try { done.await(10, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    void reloadAiConversationForTest() {
        String noteId = currentNoteId;
        aiConversationLoading = true;
        storageExecutor.execute(() -> {
            AiConversationStore.Snapshot restored = null;
            String error = null;
            try { restored = aiConversationStore.load(noteId); }
            catch (Exception failure) { error = safeError(failure); }
            AiConversationStore.Snapshot value = restored;
            String failureMessage = error;
            runOnUiThread(() -> {
                if (noteId.equals(currentNoteId)) {
                    applyRestoredAiConversation(value, failureMessage);
                }
            });
        });
    }

    void rotateAiContextForTest(String reason) {
        rotateAiWireContextPreservingMaterials();
        appendAiBoundary(reason);
    }

    void advanceAiBaselineForTest() { advanceAiConversationBaseline(); }

    boolean canContinueAiWritesForTest() { return canContinueAiContext(); }

    AiConfigStore.ToolCapability capabilityForTest() {
        AiConfigStore.Profile profile = aiConfigStore.activeProfile();
        return profile == null ? AiConfigStore.ToolCapability.UNKNOWN : profile.toolCapability;
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
        if (aiBusy || aiMaterialLoading || aiConversationLoading
                || aiSelectionSnapshot == null || prompt.trim().isEmpty()) {
            return;
        }
        if (!canContinueAiContext()) {
            rotateAiWireContextPreservingMaterials();
            releaseAiSelectionSnapshot();
            aiReadScope = null;
            aiStatusView.setText("笔记内容已变化 · 请重新圈选后发送");
            appendAiBoundary("笔记内容或 PDF 原文已变化；旧发送上下文已隔离。");
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
        List<String> materialTitles = aiReadScope == null
                ? java.util.Collections.emptyList() : aiReadScope.vaultTitles();
        String materials = materialTitles.isEmpty()
                ? "额外材料：无（不读取知识库）"
                : "额外材料：" + materialTitles.size() + " 本已冻结（"
                        + joinChinese(materialTitles) + "）";
        new AlertDialog.Builder(this)
                .setTitle("确认发送圈选内容？")
                .setMessage(String.format(Locale.CHINA,
                        "将卡片中的 %d × %d PNG（%.1f KB）、本次对话和以下明确材料发送至：\n\n%s\n\n%s\n\n布局地图不含文字正文；不发送整页或未选的知识库笔记。此确认在当前对话内有效。",
                        aiSelectionSnapshot.bitmap.getWidth(), aiSelectionSnapshot.bitmap.getHeight(),
                        aiSelectionSnapshot.pngBytes.length / 1024f, target, materials))
                .setNegativeButton("取消", null)
                .setPositiveButton("确认发送", (dialog, which) -> {
                    aiUploadConfirmed = true;
                    executeAiMessage(profile, prompt);
                })
                .show();
    }

    private void executeAiMessage(AiConfigStore.Profile profile, String prompt) {
        if (aiBusy || aiSelectionSnapshot == null || aiReadScope == null) {
            return;
        }
        if (!aiTurnFitsPersistenceBudget(prompt)) return;
        final AiConfigStore.Config directConfig;
        final AiConfigStore.Config transcribeConfig;
        final AiConfigStore.Config answerConfig;
        try {
            directConfig = profile.split ? null : aiConfigStore.directConfig(profile);
            transcribeConfig = profile.split ? aiConfigStore.transcribeConfig(profile) : null;
            answerConfig = profile.split ? aiConfigStore.answerConfig(profile) : null;
        } catch (Exception error) {
            addAiMessageBubble("assistant", "无法读取当前 AI 配置：" + safeError(error), true);
            aiStatusView.setText("AI 配置不可用");
            return;
        }

        clearRetryableAiRequest();
        aiInputView.setText("");
        OpenAiCompatibleClient.Message userMessage =
                new OpenAiCompatibleClient.Message("user", prompt.trim());
        aiMessages.add(userMessage);
        addAiMessageBubble("user", userMessage.content, false);

        int session = aiSessionSerial;
        byte[] pngBytes = aiSelectionSnapshot.pngBytes.clone();
        final AiReadScope requestScope = aiReadScope;
        final NoteToolRegistry requestTools = requestScope.createToolRegistry();
        NoteToolContext toolContext = canvasView.createToolContext(
                aiSelectionSnapshot.sourceBounds);
        JSONObject pageMap = toolContext.readPageMap(
                Math.max(0, toolContext.selectionPageIndex()), true);
        JSONArray offeredTools;
        try {
            offeredTools = requestTools.describe();
        } catch (JSONException schemaFailure) {
            offeredTools = null;
        }
        AiPendingRequest request = new AiPendingRequest(++aiRequestSerial, session,
                currentNoteId, profile.id, profile.revision,
                profile.split ? executorLabel(profile, true) : "",
                executorLabel(profile, false), documentRevision, profile.split, pngBytes,
                directConfig, transcribeConfig, answerConfig,
                toolContext, pageMap, offeredTools, requestTools, aiGrantedPermission,
                new ArrayList<>(aiMessages), aiSessionTranscript,
                aiReviewTranscriptCheck != null && aiReviewTranscriptCheck.isChecked());
        startAiAttempt(request, false);
    }

    private boolean aiTurnFitsPersistenceBudget(String prompt) {
        if (aiConversationSnapshot == null) return false;
        if (aiVisibleTimeline.size() > AiConversationStore.MAX_TIMELINE_ENTRIES - 4
                || aiMessages.size() > AiConversationStore.MAX_WIRE_MESSAGES - 16) {
            aiStatusView.setText("本次对话已达容量上限 · 请明确清空后开始新上下文");
            return false;
        }
        try {
            List<AiConversationStore.VisibleEntry> visible =
                    new ArrayList<>(aiVisibleTimeline);
            visible.add(new AiConversationStore.VisibleEntry(
                    "message", "user", prompt.trim(), "", false));
            List<OpenAiCompatibleClient.Message> wire = new ArrayList<>(aiMessages);
            wire.add(new OpenAiCompatibleClient.Message("user", prompt.trim()));
            AiVaultSnapshot vault = aiReadScope.vaultSnapshot();
            String encoded = aiConversationSnapshot.next(visible, wire,
                    AiConversationStore.Selection.fromCanvas(aiSelectionSnapshot), vault,
                    aiConversationSnapshot.binding, aiSessionTranscript,
                    aiUploadConfirmed).toJson().toString();
            if (encoded.getBytes(StandardCharsets.UTF_8).length
                    > AiConversationStore.MAX_FILE_BYTES) {
                throw new IllegalStateException("AI 会话超过 8 MiB");
            }
            return true;
        } catch (Exception tooLarge) {
            aiStatusView.setText("本次内容超过会话保存上限，未发送；请清空后开始新上下文");
            return false;
        }
    }

    /** Starts or explicitly retries one frozen user turn without appending it again. */
    private void startAiAttempt(AiPendingRequest request, boolean retry) {
        if (request == null || request.session != aiSessionSerial ||
                aiSelectionSnapshot == null || request.toolsStarted) {
            return;
        }
        if (retry) {
            addAiNotice("正在使用原请求、原材料范围和原接收地址重试；不会重复添加提问。");
        }
        retryableAiRequest = null;
        activeAiRequest = request;
        request.cancellation = new OpenAiCompatibleClient.Cancellation();
        updateAiRetryButton();
        setAiBusy(true);
        if (request.split) {
            switch (splitResumeStep(request.transcript, request.transcriptAccepted,
                    request.reviewTranscript)) {
                case TRANSCRIBE:
                    startTranscriptionLeg(request, request.cancellation);
                    break;
                case REVIEW:
                    showTranscriptReview(request, request.cancellation, request.transcript);
                    break;
                case ANSWER:
                    startAnswerLeg(request, request.cancellation);
                    break;
            }
        } else {
            aiStatusView.setText("正在安全连接模型…");
            startCompletionRequest(request, request.directConfig, request.pngBytes,
                    request.pageMap, request.offeredTools, 0, request.cancellation);
        }
    }

    /** First split leg. Its output is reused by correction and answer retries. */
    private void startTranscriptionLeg(AiPendingRequest request,
                                       OpenAiCompatibleClient.Cancellation cancellation) {
        if (!ensureAiRecipientCurrent(request)) return;
        aiStatusView.setText("正在转写手写内容…");
        aiExecutor.execute(() -> {
            try {
                if (!aiRecipientMatchesCurrent(request)) {
                    runOnUiThread(() -> {
                        if (acceptsAiCallback(request, cancellation)) {
                            ensureAiRecipientCurrent(request);
                        }
                    });
                    return;
                }
                String transcript = OpenAiCompatibleClient.transcribe(
                        request.transcribeConfig, request.pngBytes, cancellation);
                runOnUiThread(() -> {
                    if (!acceptsAiCallback(request, cancellation)) return;
                    request.transcript = transcript;
                    if (request.reviewTranscript) {
                        showTranscriptReview(request, cancellation, transcript);
                    } else {
                        acceptTranscriptAndAnswer(request, cancellation, transcript, false);
                    }
                });
            } catch (Exception error) {
                failAiRequest(request, cancellation, error,
                        "转写失败，本次未消耗回答模型调用。");
            }
        });
    }

    /** Optional one-time local correction; confirming does not call vision again. */
    private void showTranscriptReview(AiPendingRequest request,
                                      OpenAiCompatibleClient.Cancellation cancellation,
                                      String transcript) {
        if (!acceptsAiCallback(request, cancellation)) return;
        AlertDialog dialog = createAiTranscriptReviewDialog(transcript, corrected -> {
            aiTranscriptReviewDialog = null;
            acceptTranscriptAndAnswer(request, cancellation, corrected, true);
        }, () -> {
            aiTranscriptReviewDialog = null;
            cancelActiveAiRequest(true);
        });
        aiTranscriptReviewDialog = dialog;
        aiStatusView.setText("转写完成 · 等待本地校对");
        dialog.show();
    }

    /** Package-visible factory for a real UI regression without making a model call. */
    AlertDialog createAiTranscriptReviewDialog(String transcript,
                                               Consumer<String> onConfirm,
                                               Runnable onCancel) {
        EditText editor = new EditText(this);
        editor.setText(transcript);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setMinLines(8);
        editor.setMaxLines(16);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setContentDescription("可编辑的手写转写文本");
        AtomicBoolean resolved = new AtomicBoolean();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("校对手写转写")
                .setMessage("修改后会直接交给回答模型，不会重新识别图片。")
                .setView(editor)
                .setNegativeButton("取消本次", null)
                .setPositiveButton("使用校对文本并继续", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
                if (resolved.compareAndSet(false, true)) onCancel.run();
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String corrected = editor.getText().toString().trim();
                if (corrected.isEmpty()) {
                    editor.setError("转写不能为空");
                    return;
                }
                if (resolved.compareAndSet(false, true)) onConfirm.accept(corrected);
                dialog.dismiss();
            });
        });
        dialog.setOnCancelListener(ignored -> {
            if (resolved.compareAndSet(false, true)) onCancel.run();
        });
        return dialog;
    }

    private void acceptTranscriptAndAnswer(AiPendingRequest request,
                                           OpenAiCompatibleClient.Cancellation cancellation,
                                           String transcript, boolean corrected) {
        if (!acceptsAiCallback(request, cancellation)) return;
        request.transcript = transcript;
        request.transcriptAccepted = true;
        aiSessionTranscript = transcript;
        if (!request.transcriptFolded) {
            addAiNotice(corrected ? "已采用校对后的转写，正在交给回答模型。"
                    : "两段式 · 转写完成，已直接交给回答模型：");
            addAiMessageBubble("assistant", "【手写转写】\n" + transcript, false,
                    request.transcriptionExecutorLabel);
            foldTranscriptIntoConversation(request, transcript);
            request.transcriptFolded = true;
            persistAiConversation();
        }
        startAnswerLeg(request, cancellation);
    }

    /** The answer leg reasons over the frozen transcript as plain text. */
    private void startAnswerLeg(AiPendingRequest request,
                                OpenAiCompatibleClient.Cancellation cancellation) {
        aiStatusView.setText("正在安全连接回答模型…");
        startCompletionRequest(request, request.answerConfig, null, request.pageMap,
                request.offeredTools, 0, cancellation);
    }

    private void startCompletionRequest(AiPendingRequest request,
                                        AiConfigStore.Config config, byte[] pngBytes,
                                        JSONObject pageMap, JSONArray offeredTools, int round,
                                        OpenAiCompatibleClient.Cancellation cancellation) {
        if (!ensureAiRecipientCurrent(request)) return;
        List<OpenAiCompatibleClient.Message> wireMessages =
                new ArrayList<>(request.messages);
        aiExecutor.execute(() -> {
            try {
                if (!aiRecipientMatchesCurrent(request)) {
                    runOnUiThread(() -> {
                        if (acceptsAiCallback(request, cancellation)) {
                            ensureAiRecipientCurrent(request);
                        }
                    });
                    return;
                }
                OpenAiCompatibleClient.Completion completion =
                        OpenAiCompatibleClient.completeWithTools(config, pngBytes,
                                wireMessages, offeredTools, pageMap, cancellation);
                runOnUiThread(() -> {
                    if (!acceptsAiCallback(request, cancellation)) return;
                    handleAiCompletion(request, config, pngBytes, completion, round,
                            pageMap, cancellation);
                });
            } catch (Exception error) {
                failAiRequest(request, cancellation, error, null);
            }
        });
    }

    /** Last gate before a network leg. Includes credentials without persisting or displaying them. */
    private boolean ensureAiRecipientCurrent(AiPendingRequest request) {
        if (request != null && aiRecipientMatchesCurrent(request)) return true;
        rotateAiWireContextPreservingMaterials();
        aiStatusView.setText("接收模型已变化 · 本次未发送");
        addAiNotice("AI 配置或凭据已变化。旧对话不会发送给新的接收模型，请重新发送。");
        return false;
    }

    private boolean aiRecipientMatchesCurrent(AiPendingRequest request) {
        AiConfigStore.Profile current = aiConfigStore.activeProfile();
        if (current == null || !java.util.Objects.equals(
                request.recipientProfileId, current.id)
                || request.recipientProfileRevision != current.revision
                || request.split != current.split) {
            return false;
        }
        try {
            if (request.split) {
                return sameAiConfig(request.transcribeConfig,
                        aiConfigStore.transcribeConfig(current))
                        && sameAiConfig(request.answerConfig,
                        aiConfigStore.answerConfig(current));
            }
            return sameAiConfig(request.directConfig, aiConfigStore.directConfig(current));
        } catch (Exception unreadable) {
            return false;
        }
    }

    static boolean sameAiConfig(AiConfigStore.Config left, AiConfigStore.Config right) {
        if (left == right) return true;
        return left != null && right != null
                && java.util.Objects.equals(left.endpoint, right.endpoint)
                && java.util.Objects.equals(left.model, right.model)
                && java.util.Objects.equals(left.apiKey, right.apiKey);
    }

    /** Adds the transcript to the frozen wire turn and live history exactly once. */
    private void foldTranscriptIntoConversation(AiPendingRequest request, String transcript) {
        replaceLastUserTurn(request.messages, transcript);
        replaceLastUserTurn(aiMessages, transcript);
    }

    static void replaceLastUserTurn(List<OpenAiCompatibleClient.Message> messages,
                                    String transcript) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            OpenAiCompatibleClient.Message message = messages.get(index);
            if ("user".equals(message.role)) {
                messages.set(index, new OpenAiCompatibleClient.Message("user",
                        message.content + "\n\n【圈选手写内容的文字转写】\n" + transcript));
                return;
            }
        }
    }

    static SplitResumeStep splitResumeStep(String transcript, boolean accepted,
                                           boolean reviewRequested) {
        if (transcript == null) return SplitResumeStep.TRANSCRIBE;
        if (!accepted && reviewRequested) return SplitResumeStep.REVIEW;
        return SplitResumeStep.ANSWER;
    }

    private boolean acceptsAiCallback(AiPendingRequest request,
                                      OpenAiCompatibleClient.Cancellation cancellation) {
        return activeAiRequest == request && request.cancellation == cancellation &&
                !cancellation.isCancelled() && request.session == aiSessionSerial &&
                aiSelectionSnapshot != null;
    }

    private void failAiRequest(AiPendingRequest request,
                               OpenAiCompatibleClient.Cancellation cancellation,
                               Exception error, String extraNotice) {
        runOnUiThread(() -> {
            if (!acceptsAiCallback(request, cancellation)) return;
            activeAiRequest = null;
            boolean cancelled = error instanceof OpenAiCompatibleClient.RequestCancelledException
                    || cancellation.isCancelled();
            if (error instanceof OpenAiCompatibleClient.ToolParameterRejectedException) {
                aiConfigStore.recordToolCapability(request.recipientProfileId,
                        request.recipientProfileRevision,
                        AiConfigStore.ToolCapability.EXPLICITLY_REJECTED);
            }
            retryableAiRequest = request.canRetry() ? request : null;
            setAiBusy(false);
            if (cancelled) {
                aiStatusView.setText("已在本机取消 · 服务商可能仍已计费");
                addAiNotice("已停止等待并断开本地请求；已发送到服务商的内容或计费无法撤回。");
            } else if (request.toolsStarted) {
                aiStatusView.setText("工具结果已保留 · 后续回复失败");
                addAiNotice("工具结果已保留，但后续回复失败：" + safeError(error)
                        + "。为避免重复执行，本次不能重放；可继续提问。");
            } else {
                String failedExecutor = request.split && request.transcript == null
                        ? request.transcriptionExecutorLabel : request.answerExecutorLabel;
                addAiMessageBubble("assistant", "请求失败：" + safeError(error), true,
                        failedExecutor);
                aiStatusView.setText("请求失败 · 可用原快照重试");
                if (extraNotice != null) addAiNotice(extraNotice);
            }
            updateAiRetryButton();
        });
    }

    private void retryAiRequest() {
        if (aiBusy || retryableAiRequest == null) return;
        AiPendingRequest request = retryableAiRequest;
        if (!mayRetryAiRequest(request.toolsStarted, request.session, aiSessionSerial,
                aiSelectionSnapshot != null)) {
            clearRetryableAiRequest();
            aiStatusView.setText("原请求上下文已变化，请重新发送");
            return;
        }
        if (!matchesAiDocument(request)) {
            clearRetryableAiRequest();
            aiStatusView.setText("笔记已变化 · 原请求不能安全重试");
            addAiNotice("失败后笔记内容已变化。为避免旧范围或旧位置覆盖新内容，"
                    + "请按当前页面重新发送。");
            return;
        }
        startAiAttempt(request, true);
    }

    static boolean mayRetryAiRequest(boolean toolsStarted, int requestSession,
                                     int currentSession, boolean hasSelection) {
        return !toolsStarted && requestSession == currentSession && hasSelection;
    }

    private void cancelActiveAiRequest(boolean userVisible) {
        AiPendingRequest request = activeAiRequest;
        if (request == null) return;
        activeAiRequest = null;
        OpenAiCompatibleClient.Cancellation cancellation = request.cancellation;
        if (cancellation != null) cancellation.cancel();
        if (aiTranscriptReviewDialog != null) {
            AlertDialog dialog = aiTranscriptReviewDialog;
            aiTranscriptReviewDialog = null;
            if (dialog.isShowing()) dialog.dismiss();
        }
        retryableAiRequest = request.canRetry() ? request : null;
        setAiBusy(false);
        if (userVisible) {
            if (request.toolsStarted) {
                aiStatusView.setText("已停止后续请求 · 工具结果已保留");
                addAiNotice("已停止后续模型请求；本轮工具结果已保留且不会自动重放。"
                        + "其中若有笔记修改，可用撤销恢复。");
            } else {
                aiStatusView.setText("已在本机取消 · 服务商可能仍已计费");
                addAiNotice("已停止等待并断开本地请求；已发送到服务商的内容或计费无法撤回。");
            }
        }
        updateAiRetryButton();
    }

    private void clearRetryableAiRequest() {
        retryableAiRequest = null;
        updateAiRetryButton();
    }

    private void clearAiRequestState() {
        cancelActiveAiRequest(false);
        retryableAiRequest = null;
        if (aiTranscriptReviewDialog != null) {
            AlertDialog dialog = aiTranscriptReviewDialog;
            aiTranscriptReviewDialog = null;
            if (dialog.isShowing()) dialog.dismiss();
        }
        updateAiRetryButton();
    }

    private void updateAiRetryButton() {
        if (aiRetryButton != null) {
            aiRetryButton.setVisibility(!aiBusy && retryableAiRequest != null &&
                    retryableAiRequest.canRetry() ? View.VISIBLE : View.GONE);
        }
    }

    /** Routes prose to the card and explicitly requested tools to the document. */
    private void handleAiCompletion(AiPendingRequest request,
                                    AiConfigStore.Config config, byte[] pngBytes,
                                    OpenAiCompatibleClient.Completion completion, int round,
                                    JSONObject pageMap,
                                    OpenAiCompatibleClient.Cancellation cancellation) {
        if (!completion.toolCalls.isEmpty()) {
            if (!completion.displayContent.isEmpty()) {
                addAiMessageBubble("assistant", completion.displayContent, false,
                        request.answerExecutorLabel);
            }
            request.toolsHonoured = true;
            aiToolsHonoured = true;
            recordToolCapabilityEvidence(request.recipientProfileId,
                    request.recipientProfileRevision, completion, request.tools);
            if (!matchesAiDocument(request)) {
                // Do not retain an assistant tool-call turn without matching
                // tool results; that would make the next provider request an
                // invalid conversation. Preserve any prose as an ordinary turn.
                if (!completion.content.isEmpty()) {
                    appendAiHistory(request, new OpenAiCompatibleClient.Message(
                            "assistant", completion.content));
                }
                request.toolsStarted = true;
                activeAiRequest = null;
                retryableAiRequest = null;
                setAiBusy(false);
                addAiNotice("请求期间笔记内容已变化，为避免把旧布局操作写到错误位置，"
                        + "本次工具调用未执行；回答文字仍保留在卡片中。请重新发送。");
                aiStatusView.setText("笔记已变化 · 未执行旧请求操作");
                return;
            }
            appendAiHistory(request, new OpenAiCompatibleClient.Message("assistant",
                    completion.content, completion.toolCalls, null));
            runAiToolCalls(request, config, pngBytes, completion, round + 1,
                    pageMap, cancellation);
            return;
        }
        commitAssistantMessage(request, completion.content, completion.displayContent,
                request.answerExecutorLabel);
        activeAiRequest = null;
        retryableAiRequest = null;
        setAiBusy(false);
        boolean sameDocument = matchesAiDocument(request);
        boolean wantsLegacyFallback = request.permission != NoteTool.Permission.READ_ONLY &&
                !request.toolsHonoured;
        if (wantsLegacyFallback) {
            addLegacyWriteOffer(request, completion.displayContent, sameDocument);
            return;
        }
        aiStatusView.setText(round > 0 ? "操作完成 · 可继续追问"
                : "回答完成 · 已显示在卡片 · 可继续追问");
    }

    static boolean mayApplyLegacyFallback(NoteTool.Permission frozenPermission,
                                          boolean toolsHonoured,
                                          boolean sameDocument) {
        return frozenPermission != NoteTool.Permission.READ_ONLY && !toolsHonoured &&
                sameDocument;
    }

    private void addLegacyWriteOffer(AiPendingRequest request, String answer,
                                     boolean sameDocument) {
        int answerIndex = registerAdoptableAnswer(answer, request.permission, sameDocument);
        addAiNotice("模型返回了普通文字；这不能证明它不支持工具。回答未自动写入笔记。若需要，可明确选择写入。");
        if (answerIndex >= 0) {
            addAnswerAdoptionAction(aiVisibleTimeline.get(answerIndex), answerIndex,
                    canvasView.aiConversationFingerprint());
            aiStatusView.setText("回答完成 · 可明确选择写入笔记");
        } else {
            aiStatusView.setText("回答完成 · 当前来源或权限不允许写入");
        }
    }

    private int registerAdoptableAnswer(String answer, NoteTool.Permission permission,
                                        boolean sameDocument) {
        if (sameDocument && permission != NoteTool.Permission.READ_ONLY
                && aiPdfDigestAvailable && canvasView != null && aiResultAnchorBounds != null) {
            for (int index = aiVisibleTimeline.size() - 1; index >= 0; index--) {
                AiConversationStore.VisibleEntry candidate = aiVisibleTimeline.get(index);
                if ("message".equals(candidate.kind) && "assistant".equals(candidate.role)
                        && answer.equals(candidate.text) && candidate.adoptionId.isEmpty()) {
                    AiConversationStore.VisibleEntry adoptable = candidate.withAdoption(
                            UUID.randomUUID().toString(), canvasView.aiConversationFingerprint(),
                            aiPdfDigest, permission.name(), aiResultAnchorBounds);
                    aiVisibleTimeline.set(index, adoptable);
                    persistAiConversation();
                    return index;
                }
            }
        }
        return -1;
    }

    private void appendAiHistory(AiPendingRequest request,
                                 OpenAiCompatibleClient.Message message) {
        request.messages.add(message);
        aiMessages.add(message);
        persistAiConversation();
    }

    private boolean finishAiMutation(NoteCanvasView.AiEditRecord record,
                                     NoteCanvasView.AiEditSnapshot before) {
        boolean changed = canvasView.recordAiEditDelta(record, before);
        canvasView.endAiUndoTransaction(record.ownerId, changed);
        if (changed) advanceAiConversationBaseline();
        return changed;
    }

    private boolean recordToolCapabilityEvidence(String profileId, long profileRevision,
                                                 OpenAiCompatibleClient.Completion completion,
                                                 NoteToolRegistry registry) {
        if (completion == null || registry == null) return false;
        for (OpenAiCompatibleClient.ToolCall call : completion.toolCalls) {
            if (call != null && call.id != null && !call.id.trim().isEmpty()
                    && call.id.length() <= 256 && registry.find(call.name) != null) {
                return aiConfigStore.recordToolCapability(profileId, profileRevision,
                        AiConfigStore.ToolCapability.CONFIRMED);
            }
        }
        return false;
    }

    boolean recordToolEvidenceForTest(OpenAiCompatibleClient.Completion completion) {
        AiConfigStore.Profile profile = aiConfigStore.activeProfile();
        NoteToolRegistry registry = aiReadScope == null
                ? NoteTools.createDefault() : aiReadScope.createToolRegistry();
        return profile != null && recordToolCapabilityEvidence(profile.id,
                profile.revision, completion, registry);
    }

    NoteTool.Result applyAiToolForTest(String name, JSONObject arguments,
                                       NoteCanvasView.AiEditRecord record) {
        NoteCanvasView.AiEditSnapshot before = canvasView.captureAiEditSnapshot();
        canvasView.beginAiUndoTransaction(record.ownerId);
        NoteTool.Result result;
        try {
            NoteToolRegistry registry = aiReadScope == null
                    ? NoteTools.createDefault() : aiReadScope.createToolRegistry();
            result = registry.invoke(name, arguments,
                    canvasView.createToolContext(aiSelectionSnapshot == null
                            ? new RectF() : aiSelectionSnapshot.sourceBounds),
                    aiGrantedPermission);
        } finally {
            finishAiMutation(record, before);
        }
        return result;
    }

    private void commitAssistantMessage(AiPendingRequest request, String wireContent,
                                        String displayContent, String executor) {
        OpenAiCompatibleClient.Message message =
                new OpenAiCompatibleClient.Message("assistant", wireContent);
        if (request == null) {
            aiMessages.add(message);
            persistAiConversation();
        } else {
            appendAiHistory(request, message);
        }
        addAiMessageBubble("assistant", displayContent, false, executor);
    }

    private boolean matchesAiDocument(AiPendingRequest request) {
        return sameAiDocument(request.noteId, currentNoteId,
                request.expectedDocumentRevision, documentRevision);
    }

    static boolean sameAiDocument(String expectedId, String currentId,
                                  long expectedRevision, long currentRevision) {
        return java.util.Objects.equals(expectedId, currentId) &&
                expectedRevision == currentRevision;
    }

    /** Executes tools once, then continues with the same cancellable request chain. */
    private void runAiToolCalls(AiPendingRequest request,
                                AiConfigStore.Config config, byte[] pngBytes,
                                OpenAiCompatibleClient.Completion completion, int round,
                                JSONObject pageMap,
                                OpenAiCompatibleClient.Cancellation cancellation) {
        request.toolsStarted = true;
        retryableAiRequest = null;
        updateAiRetryButton();
        aiStatusView.setText(String.format(Locale.CHINA, "正在执行 %d 个笔记操作…",
                completion.toolCalls.size()));
        if (canvasView.isUserInteractionActive()) {
            for (OpenAiCompatibleClient.ToolCall call : completion.toolCalls) {
                CachedToolResult cached = request.toolReplay.find(call);
                String payload;
                if (cached != null && cached.matches(call)) {
                    // This call already ran before the pointer became active.
                    // Replaying its original result is required for protocol
                    // consistency and must never imply that it is safe to rerun.
                    payload = cached.payload;
                } else if (cached != null) {
                    payload = toolErrorPayload(
                            "同一 tool_call_id 的工具或参数发生变化，未重复执行");
                } else {
                    NoteTool.Result rejected = NoteTool.Result.error(
                            "用户正在书写或拖动，本批工具未执行");
                    request.toolReplay.record(call, rejected);
                    payload = rejected.payload.toString();
                }
                appendAiHistory(request, OpenAiCompatibleClient.Message.toolResult(
                        call.id, payload));
            }
            addAiNotice("你正在书写或拖动：新笔记操作未执行，已完成的调用仍回放原结果。");
            startCompletionRequest(request, config, pngBytes, pageMap,
                    round < MAX_AI_TOOL_ROUNDS ? request.offeredTools : null,
                    round, cancellation);
            return;
        }
        NoteCanvasView.AiEditSnapshot before = canvasView.captureAiEditSnapshot();
        boolean mutated;
        canvasView.beginAiUndoTransaction(request.edits.ownerId);
        try {
            for (OpenAiCompatibleClient.ToolCall call : completion.toolCalls) {
                CachedToolResult cached = request.toolReplay.find(call);
                if (cached != null) {
                    String payload;
                    String summary;
                    boolean ok;
                    if (cached.matches(call)) {
                        payload = cached.payload;
                        summary = "已复用先前结果：" + cached.summary;
                        ok = cached.ok;
                    } else {
                        payload = toolErrorPayload(
                                "同一 tool_call_id 的工具或参数发生变化，未重复执行");
                        summary = "调用编号重复但内容不同，已拒绝执行";
                        ok = false;
                    }
                    appendAiHistory(request,
                            OpenAiCompatibleClient.Message.toolResult(call.id, payload));
                    addAiNotice((ok ? "已执行 " : "未执行 ") + call.name + "：" + summary);
                    continue;
                }
                request.executedToolCallIds.add(call.id);
                aiExecutedToolCallIds.add(request.id + "\u0000" + call.id);
                NoteTool.Result result = request.tools.invoke(call.name, call.arguments,
                        request.toolContext, request.permission);
                request.toolReplay.record(call, result);
                appendAiHistory(request, OpenAiCompatibleClient.Message.toolResult(call.id,
                        result.payload.toString()));
                addAiNotice((result.ok ? "已执行 " : "未执行 ") + call.name
                        + "：" + result.summary);
            }
        } finally {
            mutated = finishAiMutation(request.edits, before);
        }
        if (mutated) {
            updateTextBoxOverlays(canvasView.getTextBoxes());
            ensureAiEditCard(request);
            // These changes belong to this same frozen request. Later tool rounds
            // may use the refreshed layout, but unrelated user edits still fail
            // the revision guard above.
            request.expectedDocumentRevision = documentRevision;
        }

        boolean allowMoreTools = round < MAX_AI_TOOL_ROUNDS;
        JSONArray nextTools = null;
        if (allowMoreTools) {
            try {
                nextTools = request.tools.describe();
            } catch (JSONException ignored) {
                nextTools = null;
            }
        } else {
            addAiNotice("已达到本次任务的操作轮次上限，接下来只做总结。");
        }
        JSONObject refreshedMap = mutated && allowMoreTools
                ? request.toolContext.readPageMap(
                        Math.max(0, request.toolContext.selectionPageIndex()), true)
                : pageMap;
        startCompletionRequest(request, config, pngBytes, refreshedMap, nextTools,
                round, cancellation);
    }

    static String toolCallSignature(OpenAiCompatibleClient.ToolCall call) {
        return (call == null ? "" : call.name) + "\u0000"
                + (call == null || call.arguments == null ? "{}" : call.arguments.toString());
    }

    private static String toolErrorPayload(String message) {
        try {
            return new JSONObject().put("error", message).toString();
        } catch (JSONException ignored) {
            return "{\"error\":\"tool call rejected\"}";
        }
    }

    private void setAiBusy(boolean busy) {
        aiBusy = busy;
        updateAiInteractionEnabled();
        updateAiCardTitle();
        updateAiRetryButton();
        refreshAiEditCards();
    }

    private void setAiMaterialLoading(boolean loading) {
        aiMaterialLoading = loading;
        updateAiInteractionEnabled();
        updateAiCardTitle();
    }

    private void updateAiInteractionEnabled() {
        boolean enabled = !aiBusy && !aiMaterialLoading && !aiConversationLoading;
        if (aiSendButton != null) {
            // The primary action becomes a real local cancel while a request is
            // active. It stays usable even though all request-shaping controls
            // are frozen until this turn ends.
            aiSendButton.setEnabled(!aiMaterialLoading);
            aiSendButton.setText(aiBusy ? "取消" : "发送");
            aiExplainButton.setEnabled(enabled);
            aiMarkdownButton.setEnabled(enabled);
            aiDiagramButton.setEnabled(enabled);
        }
        if (aiInputView != null) aiInputView.setEnabled(enabled);
        if (aiReviewTranscriptCheck != null) aiReviewTranscriptCheck.setEnabled(enabled);
        if (aiInlineOutputButton != null) aiInlineOutputButton.setEnabled(enabled);
        if (aiCardOutputButton != null) aiCardOutputButton.setEnabled(enabled);
        if (aiSettingsButton != null) {
            aiSettingsButton.setEnabled(enabled);
        }
        if (aiMaterialsButton != null) {
            aiMaterialsButton.setEnabled(enabled);
        }
        if (aiMaterialsPreviewButton != null) {
            aiMaterialsPreviewButton.setEnabled(enabled && aiReadScope != null
                    && aiReadScope.hasVaultNotes());
        }
    }

    private static String joinChinese(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append("、");
            }
            result.append(value);
        }
        return result.toString();
    }

    /**
     * Profile manager in the style of cc-switch: every saved configuration is a
     * switchable slot, tapping a row makes it active for the next request, and
     * each row can be edited or deleted. The direct multimodal route and the
     * split transcribe+answer route are both just profiles here.
     */
    private void showAiManagerDialog(Runnable afterSave) {
        if (aiBusy || aiMaterialLoading) {
            Toast.makeText(this, "请等待当前回答完成", Toast.LENGTH_SHORT).show();
            return;
        }
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
        String capability;
        switch (profile.toolCapability) {
            case CONFIRMED: capability = "工具能力：已由真实调用确认"; break;
            case EXPLICITLY_REJECTED: capability = "工具能力：服务端明确拒绝工具参数"; break;
            default: capability = "工具能力：尚未确认";
        }
        info.addView(text(profile.summary() + " · " + capability,
                12, Color.rgb(91, 103, 113)));
        row.addView(info);

        row.setOnClickListener(view -> {
            if (isActive) {
                return;
            }
            if (!activateAiProfile(profile)) return;
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
                    if (!deleteAiProfile(profile)) return;
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
                        boolean changesActive = creating
                                || saved.id.equals(aiConfigStore.activeProfileId());
                        if (!saveAiProfile(saved, directKey, transcribeKey, answerKey,
                                changesActive)) return;
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
        if (!editorVisible || !restoreCompleted || currentNoteId == null) return;
        commitInlineTextEditorExcept(null);
        if (digitizationController == null) digitizationController = new DigitizationController(this, vaultStore,
                () -> showAiManagerDialog(null));
        AiConfigStore.Profile profile = aiConfigStore.activeProfile();
        AiConfigStore.Config config = null;
        try {
            if (profile != null && profile.structurallyComplete()) {
                config = profile.split ? aiConfigStore.transcribeConfig(profile) : aiConfigStore.directConfig(profile);
            }
        } catch (Exception error) {
            Toast.makeText(this, "模型凭据无法读取，可先查看已保存的整理稿", Toast.LENGTH_LONG).show();
        }
        long sourceUpdatedAt = System.currentTimeMillis();
        if (documentRevision == lastSavedRevision) {
            for (NoteStore.Entry entry : lastShelfEntries) {
                if (currentNoteId.equals(entry.id)) { sourceUpdatedAt = entry.updatedAt; break; }
            }
        }
        digitizationController.open(canvasView, currentNoteId, currentNoteTitle,
                profile == null ? "" : profile.id, config, sourceUpdatedAt);
    }

    // ===================== 知识库：阅读与导出 =====================

    private void showLocalSource(String title, String sourceValue) {
        TextView source = text(sourceValue == null ? "" : sourceValue, 13, INK_COLOR);
        source.setTypeface(Typeface.MONOSPACE);
        source.setTextIsSelectable(true);
        source.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(source, matchWrap());
        new AlertDialog.Builder(this).setTitle(title).setView(scroll)
                .setNegativeButton("关闭", null)
                .setPositiveButton("复制", (dialog, which) ->
                        copyLocalSource(title, sourceValue)).show();
    }

    private void copyLocalSource(String label, String source) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label,
                    source == null ? "" : source));
            Toast.makeText(this, "源码已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void editLocalDisplaySource(String title, String source, Consumer<String> onApply,
                                        String explanation) {
        EditText editor = new EditText(this);
        editor.setText(source == null ? "" : source);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setMinLines(10);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500_000)});
        new AlertDialog.Builder(this).setTitle(title).setMessage(explanation).setView(editor)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存并重新显示", (dialog, which) ->
                        onApply.accept(editor.getText().toString())).show();
    }

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
        String[] currentSource = new String[]{markdown};
        AtomicBoolean readerClosed = new AtomicBoolean(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        CompiledTextWebView webView = new CompiledTextWebView(this);
        CompiledTextWebView[] webHolder = new CompiledTextWebView[]{webView};
        scroll.addView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout recovery = new LinearLayout(this);
        recovery.setOrientation(LinearLayout.VERTICAL);
        recovery.setPadding(dp(12), dp(8), dp(12), dp(8));
        recovery.setVisibility(View.GONE);
        recovery.setContentDescription("格式笔记显示失败");
        TextView status = text("显示失败", 12, Color.rgb(143, 47, 43));
        recovery.addView(status, matchWrap());
        LinearLayout actions = new LinearLayout(this);
        Button viewSource = pillButton("查看源码", BUTTON_QUIET);
        Button copySource = pillButton("复制", BUTTON_QUIET);
        Button editSource = pillButton("编辑", BUTTON_QUIET);
        Button retry = pillButton("重新显示", BUTTON_TONAL);
        actions.addView(viewSource, new LinearLayout.LayoutParams(0, dp(40), 1f));
        actions.addView(copySource, new LinearLayout.LayoutParams(0, dp(40), .7f));
        actions.addView(editSource, new LinearLayout.LayoutParams(0, dp(40), .7f));
        actions.addView(retry, new LinearLayout.LayoutParams(0, dp(40), 1f));
        recovery.addView(actions, matchWrap());
        body.addView(recovery, matchWrap());
        viewSource.setOnClickListener(view -> showLocalSource(
                "格式笔记源码", currentSource[0]));
        copySource.setOnClickListener(view -> copyLocalSource(
                "PadNote 格式笔记源码", currentSource[0]));
        editSource.setOnClickListener(view -> editLocalDisplaySource(
                "编辑格式笔记源码", currentSource[0], edited -> {
                    status.setText("正在保存源码…");
                    storageExecutor.execute(() -> {
                        try {
                            vaultStore.replaceRaw(fileName, edited);
                            runOnUiThread(() -> {
                                if (readerClosed.get()) return;
                                currentSource[0] = edited;
                                recovery.setVisibility(View.GONE);
                                webHolder[0].renderDocument(edited);
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> {
                                if (readerClosed.get()) return;
                                status.setText("源码保存失败 · 原文件未改动");
                                Toast.makeText(this, "源码保存失败：" + safeError(error),
                                        Toast.LENGTH_LONG).show();
                            });
                        }
                    });
                }, "修改会保存到这份格式笔记；不会请求模型。"));
        retry.setOnClickListener(view -> {
            retry.setEnabled(false);
            status.setText("正在重新显示…");
            webHolder[0].renderDocument(currentSource[0]);
        });
        configureVaultRenderRecovery(webView, webHolder, scroll, recovery, status, retry,
                readerClosed);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(fileName)
                .setView(body)
                .setPositiveButton("关闭", null)
                .create();
        dialog.setOnDismissListener(ignored -> {
            readerClosed.set(true);
            webHolder[0].destroy();
        });
        dialog.show();
        webView.renderDocument(markdown);
    }

    private void configureVaultRenderRecovery(CompiledTextWebView view,
                                               CompiledTextWebView[] holder,
                                               ScrollView scroll, LinearLayout recovery,
                                               TextView status, Button retry,
                                               AtomicBoolean readerClosed) {
        view.setRenderStateListener(state -> {
            if (holder[0] != view) return;
            retry.setEnabled(true);
            if (state.isReady()) {
                recovery.setVisibility(View.GONE);
                return;
            }
            status.setText("显示失败 · " + state.message);
            recovery.setVisibility(View.VISIBLE);
            if (state.failure == CompiledTextWebView.FailureKind.PROCESS_GONE) {
                scroll.post(() -> {
                    if (readerClosed.get() || holder[0] != view
                            || scroll.getParent() == null) return;
                    scroll.removeView(view);
                    CompiledTextWebView replacement = new CompiledTextWebView(this);
                    holder[0] = replacement;
                    scroll.addView(replacement, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    configureVaultRenderRecovery(replacement, holder, scroll,
                            recovery, status, retry, readerClosed);
                });
            }
        });
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
        if (!restoreCompleted) {
            showBookshelf();
            return;
        }
        if (documentRevision == lastSavedRevision && !saveInFlight) {
            showBookshelf();
            return;
        }
        pendingLeaveAfterSave = true;
        saveDocument(false);
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
        if (digitizationController != null && digitizationController.activityResult(requestCode, resultCode, data)) return;
        IntentResult scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scan != null) {
            if (scan.getContents() != null) {
                new AgentConnectionDialogs(this, agentConnectionStore, aiExecutor,
                        this::refreshBookshelf, this::launchAgentPairingQr,
                        this::launchCreateAgentArtifactDocument)
                        .showPairingPayload(scan.getContents());
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQUEST_EXPORT_NOTE) {
                pendingExportJson = null;
                pendingExportNoteId = null;
            } else if (requestCode == REQUEST_EXPORT_VAULT) {
                pendingExportVaultFile = null;
            } else if (requestCode == REQUEST_EXPORT_VIDEO_TASK) {
                clearPendingVideoTask();
            } else if (requestCode == REQUEST_EXPORT_AGENT_ARTIFACT) {
                clearPendingAgentArtifact();
            } else if (requestCode == REQUEST_EXPORT_RECOVERY) {
                pendingRecoveryFile = null;
                pendingAiConversationRecoverySnapshot = null;
                pendingRecoveryName = null;
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
        } else if (requestCode == REQUEST_EXPORT_AGENT_ARTIFACT) {
            exportAgentArtifactToUri(data.getData());
        } else if (requestCode == REQUEST_EXPORT_RECOVERY) {
            exportRecoveryToUri(data.getData());
        } else if (requestCode == REQUEST_IMPORT_COVER) {
            handleImportedCover(data.getData());
        } else if (requestCode == REQUEST_IMPORT_IMAGE) {
            handleImportedImage(data.getData());
        }
    }

    private void exportRecoveryToUri(android.net.Uri uri) {
        File source = pendingRecoveryFile;
        AiConversationStore.Snapshot conversation = pendingAiConversationRecoverySnapshot;
        pendingRecoveryFile = null;
        pendingAiConversationRecoverySnapshot = null;
        pendingRecoveryName = null;
        storageExecutor.execute(() -> {
            try {
                if (conversation == null && (source == null || !source.isFile())) {
                    throw new IllegalStateException("恢复文件已不存在");
                }
                try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                    if (output == null) throw new IllegalStateException("无法写入目标文件");
                    if (conversation != null) {
                        output.write(conversation.toRecoveryJson().toString()
                                .getBytes(StandardCharsets.UTF_8));
                    } else {
                        try (InputStream input = new FileInputStream(source)) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = input.read(buffer)) >= 0) {
                                output.write(buffer, 0, read);
                            }
                        }
                    }
                    output.flush();
                }
                runOnUiThread(() -> Toast.makeText(this,
                        "恢复文件已导出", Toast.LENGTH_SHORT).show());
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "恢复文件导出失败：" + safeError(error), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void exportAgentArtifactToUri(android.net.Uri uri) {
        String taskId = pendingAgentArtifactTaskId;
        String artifactId = pendingAgentArtifactId;
        clearPendingAgentArtifact();
        aiExecutor.execute(() -> {
            File temporary = null;
            boolean saved = false;
            try {
                AgentTaskStore.Task task = new AgentTaskStore(this).get(taskId);
                if (task == null) throw new IllegalStateException("任务记录不存在");
                AgentTaskStore.Artifact found = null;
                for (AgentTaskStore.Artifact candidate : task.artifacts) {
                    if (candidate.id.equals(artifactId)) { found = candidate; break; }
                }
                if (found == null) throw new IllegalStateException("产物已不在当前任务清单中");
                AgentTaskStore.Artifact artifact = found;
                AgentConnectionStore.Config connection = agentConnectionStore.get(task.connectionId);
                temporary = new AgentArtifactDownloader().download(task, connection, artifact,
                        new File(getCacheDir(), "agent-artifacts"));
                try (InputStream input = new FileInputStream(temporary);
                     OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IllegalStateException("无法打开所选位置");
                    byte[] buffer = new byte[64 * 1024]; int count;
                    while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
                    output.flush();
                }
                saved = true;
                runOnUiThread(() -> Toast.makeText(this, "产物已校验并保存", Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this, "保存产物失败：" + safeError(error),
                        Toast.LENGTH_LONG).show());
            } finally {
                if (temporary != null) temporary.delete();
                if (!saved) try { getContentResolver().delete(uri, null, null); }
                catch (Exception ignored) { }
            }
        });
    }

    private void clearPendingAgentArtifact() {
        pendingAgentArtifactTaskId = null;
        pendingAgentArtifactId = null;
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
        NoteCanvasView.PdfExportSnapshot preparedSnapshot = null;
        try {
            preparedSnapshot = canvasView.createPdfExportSnapshot();
            PdfNoteIO.chooseRasterLongEdge(preparedSnapshot.getPageCount(),
                    preparedSnapshot.getPageWidth(), preparedSnapshot.getPageHeight());
        } catch (Exception error) {
            if (preparedSnapshot != null) preparedSnapshot.close();
            Toast.makeText(this, "PDF 导出失败：" + safeError(error), Toast.LENGTH_LONG).show();
            return;
        }
        final NoteCanvasView.PdfExportSnapshot snapshot = preparedSnapshot;
        activePdfExportSnapshot = snapshot;
        AtomicBoolean cancellation = new AtomicBoolean(false);
        AtomicBoolean taskStarted = new AtomicBoolean(false);
        AtomicBoolean uiFinished = new AtomicBoolean(false);
        AtomicBoolean committed = new AtomicBoolean(false);
        activePdfExportCancelled = cancellation;
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
                .setNegativeButton("取消", null)
                .setCancelable(false)
                .create();
        progressDialog.setOnShowListener(ignored -> progressDialog
                .getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
                    synchronized (cancellation) {
                        if (committed.get() || cancellation.get()) return;
                        cancellation.set(true);
                    }
                    progressDialog.setMessage("正在取消并清理临时文件…");
                    progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                    snapshot.close();
                    Future<?> future = activePdfExportFuture;
                    if (future != null && future.cancel(true) && !taskStarted.get()) {
                        finishPdfExportUi(snapshot, renderHost, progressDialog, uiFinished,
                                false, true, null);
                    }
                }));
        progressDialog.show();
        activePdfExportDialog = progressDialog;
        Handler exportMainHandler = new Handler(Looper.getMainLooper());
        Runnable exportTask = () -> {
            taskStarted.set(true);
            File temporary = null;
            Exception failure = null;
            boolean success = false;
            boolean destinationTouched = false;
            try {
                if (cancellation.get()) throw new PdfNoteIO.ExportCancelledException();
                temporary = File.createTempFile("flattened-export-", ".pdf", getCacheDir());
                try (OutputStream tempOutput = new FileOutputStream(temporary)) {
                    PdfNoteIO.exportFlattenedPdf(snapshot, renderHost, tempOutput,
                            exportMainHandler, (complete, total) -> {
                                if (!cancellation.get() && progressDialog.isShowing()) {
                                    progressDialog.setMessage("正在渲染第 " + complete + " / " + total + " 页");
                                }
                            }, cancellation::get);
                }
                if (cancellation.get()) throw new PdfNoteIO.ExportCancelledException();
                // The destination is opened only after the complete local PDF exists.
                // If copying fails or is cancelled, best-effort delete prevents a
                // partial file from looking like a successful export.
                try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IllegalStateException("无法打开所选位置");
                    destinationTouched = true;
                    try (InputStream input = new FileInputStream(temporary)) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = input.read(buffer)) >= 0) {
                            if (cancellation.get() || Thread.currentThread().isInterrupted()) {
                                throw new PdfNoteIO.ExportCancelledException();
                            }
                            if (count > 0) output.write(buffer, 0, count);
                        }
                        output.flush();
                    }
                }
                synchronized (cancellation) {
                    if (cancellation.get()) throw new PdfNoteIO.ExportCancelledException();
                    committed.set(true);
                }
                success = true;
            } catch (Exception error) {
                failure = error;
            } catch (OutOfMemoryError error) {
                failure = new java.io.IOException("导出页面过大，内存不足", error);
            } finally {
                snapshot.close();
                if (temporary != null && !temporary.delete()) temporary.deleteOnExit();
                boolean cancelled = cancellation.get() ||
                        failure instanceof PdfNoteIO.ExportCancelledException;
                if (!success && destinationTouched) {
                    try { getContentResolver().delete(uri, null, null); }
                    catch (Exception ignored) { }
                }
                boolean completed = success;
                Exception result = failure == null
                        ? new java.io.IOException(cancelled ? "PDF 导出已取消" : "PDF 导出未完成")
                        : failure;
                exportMainHandler.post(() -> finishPdfExportUi(snapshot, renderHost,
                        progressDialog, uiFinished, completed, cancelled, result));
            }
        };
        try {
            activePdfExportFuture = storageExecutor.submit(exportTask);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            cancellation.set(true);
            snapshot.close();
            finishPdfExportUi(snapshot, renderHost, progressDialog, uiFinished,
                    false, false, new java.io.IOException("导出任务无法启动", rejected));
        }
    }

    private void finishPdfExportUi(NoteCanvasView.PdfExportSnapshot snapshot,
                                   FrameLayout renderHost, AlertDialog progressDialog,
                                   AtomicBoolean uiFinished, boolean success,
                                   boolean cancelled, Exception failure) {
        if (!uiFinished.compareAndSet(false, true)) return;
        if (activePdfExportSnapshot == snapshot) activePdfExportSnapshot = null;
        if (activePdfExportHost == renderHost) activePdfExportHost = null;
        if (activePdfExportDialog == progressDialog) activePdfExportDialog = null;
        activePdfExportFuture = null;
        activePdfExportCancelled = null;
        if (renderHost.getParent() == appFrame) appFrame.removeView(renderHost);
        if (progressDialog.isShowing()) progressDialog.dismiss();
        if (isFinishing() || isDestroyed()) return;
        String message = success ? "已导出标准 PDF" : cancelled ? "已取消 PDF 导出"
                : "PDF 导出失败：" + safeError(failure == null
                ? new java.io.IOException("PDF 导出未完成") : failure);
        Toast.makeText(this, message, success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
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
        aiConversationLoading = true;
        storageExecutor.execute(() -> {
            JSONObject restoredDocument = null;
            String restoreError = null;
            AiConversationStore.Snapshot restoredConversation = null;
            String conversationError = null;
            String pdfDigest = "";
            try {
                restoredDocument = NoteStore.load(this, noteId);
            } catch (Exception error) {
                restoreError = "本地笔记读取失败：" + safeError(error);
            }
            if (restoredDocument != null && restoredDocument.optInt("pdfPageCount", 0) > 0) {
                try {
                    File pdf = NoteStore.pdfFile(this, noteId);
                    if (!pdf.isFile()) throw new java.io.IOException("PDF 原文缺失");
                    pdfDigest = DigitizationStore.sha256File(pdf, PdfNoteIO.MAX_PDF_BYTES);
                } catch (Exception error) {
                    pdfDigest = "!unavailable:" + safeError(error);
                }
            }
            try {
                restoredConversation = aiConversationStore.load(noteId);
            } catch (Exception error) {
                conversationError = safeError(error);
            }
            JSONObject document = restoredDocument;
            String errorMessage = restoreError;
            AiConversationStore.Snapshot conversation = restoredConversation;
            String conversationLoadError = conversationError;
            String restoredPdfDigest = pdfDigest;
            runOnUiThread(() -> {
                if (!editorVisible || !noteId.equals(currentNoteId)) return;
                if (document == null) {
                    showRestoreFailure(errorMessage == null ? "本地笔记读取失败" : errorMessage);
                    return;
                }
                try {
                    canvasView.loadJsonDocument(document);
                    restoreCompleted = true;
                    canvasView.setEnabled(true);
                    lastSavedRevision = documentRevision;
                    aiPdfDigest = restoredPdfDigest;
                    aiPdfDigestAvailable = !restoredPdfDigest.startsWith("!unavailable:");
                    applyRestoredAiConversation(conversation, conversationLoadError);
                    saveStatusView.setText("已恢复本地笔记");
                    // Applied only after a successful load. A failed load never turns
                    // into an editable blank document that could overwrite the source.
                    if (pendingPageStyle != null) {
                        canvasView.applyPageStyleForNewNote(pendingPageStyle);
                        saveStatusView.setText(pendingPageStyle.describe());
                        pendingPageStyle = null;
                    }
                } catch (Exception error) {
                    showRestoreFailure("笔记内容校验失败：" + safeError(error));
                }
            });
        });
    }

    private void applyRestoredAiConversation(AiConversationStore.Snapshot restored,
                                             String loadError) {
        aiConversationLoading = false;
        aiConversationSaveFailureShown = false;
        aiConversationSnapshot = restored;
        aiVisibleTimeline.clear();
        aiMessages.clear();
        aiReadScope = null;
        aiSessionTranscript = null;
        aiUploadConfirmed = false;
        releaseAiSelectionSnapshot();
        if (restored == null) {
            clearAiConversationViews();
            if (loadError != null && aiStatusView != null) {
                aiStatusView.setText("AI 对话损坏，原文件已保留：" + loadError);
                new AlertDialog.Builder(this).setTitle("AI 对话无法安全恢复")
                        .setMessage("笔记仍可正常编辑。损坏或超限的会话文件没有被覆盖，"
                                + "可先导出原文件再明确清空。")
                        .setNegativeButton("保留", null)
                        .setNeutralButton("清空损坏对话", (dialog, which) ->
                                clearAiConversationExplicitly())
                        .setPositiveButton("导出原文件", (dialog, which) ->
                                launchAiConversationRecoveryExport(currentNoteId))
                        .show();
            }
            updateAiInteractionEnabled();
            return;
        }
        aiVisibleTimeline.addAll(restored.visibleTimeline);
        AiConfigStore.Profile profile = aiConfigStore.activeProfile();
        String currentSemantic = canvasView.aiConversationFingerprint();
        boolean sourceMatches = restored.binding.semanticDigest.equals(currentSemantic)
                && restored.binding.pdfDigest.equals(aiPdfDigest);
        boolean recipientMatches = profile != null
                && restored.binding.profileId.equals(profile.id)
                && restored.binding.profileRevision == profile.revision;
        boolean permissionMatches = restored.binding.permission.equals(
                aiGrantedPermission.name());
        String restoredMaterialDigest = restored.vault == null ? "" : restored.vault.digest();
        boolean materialMatches = restored.binding.materialDigest.equals(restoredMaterialDigest);
        boolean wireValid = sourceMatches && recipientMatches && permissionMatches
                && materialMatches && restored.selection != null;
        try {
            if (restored.selection != null && sourceMatches) {
                aiSelectionSnapshot = restored.selection.toCanvas();
                aiResultAnchorBounds = new RectF(aiSelectionSnapshot.sourceBounds);
                aiSelectionPreview.setImageBitmap(aiSelectionSnapshot.bitmap);
                aiReadScope = AiReadScope.selectionOnly(currentNoteId, ++aiSessionSerial,
                        aiProfileIdentity(profile)).withVault(restored.vault);
            }
        } catch (Exception invalidSelection) {
            wireValid = false;
            aiSelectionSnapshot = null;
        }
        if (wireValid) {
            aiMessages.addAll(restored.wireHistory);
            aiSessionTranscript = restored.transcript;
            aiUploadConfirmed = restored.uploadConfirmed;
            aiStatusView.setText("已恢复本笔记的 AI 对话 · 可继续追问");
        } else {
            aiMessages.clear();
            aiSessionTranscript = null;
            aiUploadConfirmed = false;
            String reason = !sourceMatches
                    ? "笔记或 PDF 原文已变化，请重新圈选；旧聊天未发送。"
                    : !recipientMatches ? "接收模型配置已变化；旧聊天未发送。"
                    : !permissionMatches ? "写入权限已变化；旧聊天未发送。"
                    : !materialMatches ? "冻结材料校验失败；旧聊天未发送。"
                    : "冻结圈选无法恢复，请重新圈选。";
            aiVisibleTimeline.add(new AiConversationStore.VisibleEntry(
                    "notice", "assistant", "上下文边界 · " + reason, "", false));
            AiVaultSnapshot retained = restored.vault;
            AiConversationStore.Binding newBinding = currentAiBinding(profile, retained);
            aiConversationSnapshot = restored.next(aiVisibleTimeline, aiMessages,
                    sourceMatches ? restored.selection : null, retained, newBinding,
                    null, false);
            persistAiConversationSnapshot(aiConversationSnapshot);
            aiStatusView.setText(reason);
        }
        renderAiTimeline();
        updateAiMaterialsUi();
        updateAiInteractionEnabled();
        setActionEnabled(aiButton, true);
    }

    private void launchAiConversationRecoveryExport(String noteId) {
        try {
            File recovery = aiConversationStore.recoveryFile(noteId);
            if (recovery == null) {
                Toast.makeText(this, "没有可导出的 AI 会话原文件", Toast.LENGTH_SHORT).show();
                return;
            }
            pendingRecoveryFile = recovery;
            pendingAiConversationRecoverySnapshot = null;
            pendingRecoveryName = noteId + "-ai-conversation-recovery.json";
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, pendingRecoveryName);
            startActivityForResult(intent, REQUEST_EXPORT_RECOVERY);
        } catch (Exception error) {
            Toast.makeText(this, "无法准备 AI 会话导出：" + safeError(error),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void launchAiConversationRecoveryExport(AiConversationStore.Snapshot snapshot) {
        if (snapshot == null) return;
        pendingRecoveryFile = null;
        pendingAiConversationRecoverySnapshot = snapshot;
        pendingRecoveryName = snapshot.noteId + "-ai-conversation-unsaved.json";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, pendingRecoveryName);
        startActivityForResult(intent, REQUEST_EXPORT_RECOVERY);
    }

    private void showRestoreFailure(String message) {
        restoreCompleted = false;
        if (canvasView != null) canvasView.setEnabled(false);
        if (saveStatusView != null) saveStatusView.setText("读取失败 · 原文件未改动");
        new AlertDialog.Builder(this)
                .setTitle("无法安全打开这本笔记")
                .setMessage(message + "\n\nPadNote 已停止编辑和自动保存。原文件及可用备份仍保留，"
                        + "可返回书架从“需要恢复”导出。").setCancelable(false)
                .setPositiveButton("返回书架", (dialog, which) -> showBookshelf())
                .show();
    }

    private void saveDocument(boolean showToast) {
        if (!editorVisible || !restoreCompleted || currentNoteId == null) return;
        pendingSaveToast |= showToast;
        if (saveInFlight) {
            saveQueued = true;
            return;
        }
        final String json;
        final String noteId = currentNoteId;
        final String noteTitle = currentNoteTitle;
        final long savingRevision = documentRevision;
        try {
            json = canvasView.toJsonDocument(noteId, noteTitle).toString();
            pendingUnsavedJson = json;
        } catch (Exception error) {
            saveStatusView.setText("生成未保存副本失败：" + safeError(error));
            pendingLeaveAfterSave = false;
            if (showToast) Toast.makeText(this, "无法生成保存数据", Toast.LENGTH_LONG).show();
            return;
        }
        saveInFlight = true;
        saveQueued = false;
        saveStatusView.setText("正在保存…");
        storageExecutor.execute(() -> {
            try {
                NoteStore.save(this, noteId, noteTitle, json);
                runOnUiThread(() -> onSaveSucceeded(noteId, savingRevision));
            } catch (Exception error) {
                String message = safeError(error);
                runOnUiThread(() -> onSaveFailed(noteId, message));
            }
        });
    }

    private void onSaveSucceeded(String noteId, long savedRevision) {
        if (!noteId.equals(currentNoteId)) return;
        saveInFlight = false;
        lastSavedRevision = Math.max(lastSavedRevision, savedRevision);
        if (savedRevision == documentRevision) {
            pendingUnsavedJson = null;
            saveStatusView.setText("已保存到本机 · 可重新打开");
            if (pendingSaveToast) {
                Toast.makeText(this, "已保存到本机", Toast.LENGTH_SHORT).show();
                pendingSaveToast = false;
            }
            if (pendingLeaveAfterSave) {
                showBookshelf();
                return;
            }
        }
        if (saveQueued || savedRevision < documentRevision) {
            saveQueued = false;
            saveDocument(false);
        }
    }

    private void onSaveFailed(String noteId, String message) {
        if (!noteId.equals(currentNoteId)) return;
        saveInFlight = false;
        saveQueued = false;
        saveStatusView.setText("保存失败 · 未保存修改仍可重试或导出");
        boolean needsDialog = pendingLeaveAfterSave || pendingSaveToast;
        pendingLeaveAfterSave = false;
        pendingSaveToast = false;
        if (needsDialog) showSaveFailure(message);
    }

    private void showSaveFailure(String message) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("修改尚未保存")
                .setMessage(message + "\n\n仍停留在当前笔记。请重试，或把内存中的未保存副本导出。")
                .setNegativeButton("继续编辑", null)
                .setNeutralButton("导出副本", null)
                .setPositiveButton("重试保存", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                dialog.dismiss();
                saveDocument(true);
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                try {
                    commitInlineTextEditorExcept(null);
                    pendingUnsavedJson = captureRecoveryExportJson(canvasView,
                            currentNoteId, currentNoteTitle);
                } catch (Exception error) {
                    Toast.makeText(this, "生成当前未保存副本失败：" + safeError(error),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                pendingExportJson = pendingUnsavedJson;
                pendingExportNoteId = null;
                launchCreateExportDocument((currentNoteTitle == null ? "PadNote" : currentNoteTitle)
                        + "-未保存副本");
            });
        });
        dialog.show();
    }

    /** Always snapshots the live canvas; an older failed-save payload may be stale. */
    static String captureRecoveryExportJson(NoteCanvasView canvas, String noteId,
                                            String noteTitle) throws Exception {
        if (canvas == null || noteId == null) throw new IllegalStateException("当前笔记不可用");
        return canvas.toJsonDocument(noteId, noteTitle).toString();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
