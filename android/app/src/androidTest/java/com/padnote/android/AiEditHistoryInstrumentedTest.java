package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Real Android canvas checks for request-owned AI edits and ordinary history. */
@RunWith(AndroidJUnit4.class)
public final class AiEditHistoryInstrumentedTest {

    @Test
    public void consecutiveRoundsShareOneToolbarUndoAndRedoRestoresFinalStyle() {
        try (CanvasSession session = CanvasSession.open(1)) {
            session.scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = session.canvas;
                    String owner = "request-two-rounds";
                    NoteCanvasView.AiEditRecord record = canvas.newAiEditRecord(owner);
                    AtomicReference<String> flowId = new AtomicReference<>();

                    assertTrue(recordRound(canvas, record, owner, () ->
                            flowId.set(createFlow(canvas, "第一轮", 0, 48, 96, 520))));
                    assertTrue(recordRound(canvas, record, owner, () ->
                            canvas.createToolContext(null).styleTextFlow(
                                    flowId.get(), 22f, 1.6f, null)));

                    assertEquals(1, canvas.undoDepthForTest());
                    assertEquals(22f, flow(canvas, flowId.get()).fontSizeSp, 0.01f);
                    assertEquals(1.6f, flow(canvas, flowId.get()).lineHeight, 0.001f);
                    assertEquals(NoteCanvasView.AiEditState.APPLIED,
                            canvas.aiEditState(record));

                    canvas.undo();
                    assertNull(flow(canvas, flowId.get()));
                    assertEquals(NoteCanvasView.AiEditState.UNDONE,
                            canvas.aiEditState(record));

                    canvas.redo();
                    assertEquals(22f, flow(canvas, flowId.get()).fontSizeSp, 0.01f);
                    assertEquals(1.6f, flow(canvas, flowId.get()).lineHeight, 0.001f);
                    assertEquals(NoteCanvasView.AiEditState.APPLIED,
                            canvas.aiEditState(record));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
        }
    }

    @Test
    public void readRejectedAndSameValueToolsDoNotConsumeFullHistory() {
        try (CanvasSession session = CanvasSession.open(1)) {
            session.scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = session.canvas;
                    String retainedFlow = null;
                    for (int index = 0; index < 30; index++) {
                        final int turn = index;
                        String owner = "fill-history-" + index;
                        NoteCanvasView.AiEditRecord record = canvas.newAiEditRecord(owner);
                        AtomicReference<String> created = new AtomicReference<>();
                        assertTrue(recordRound(canvas, record, owner, () -> created.set(
                                createFlow(canvas, "历史 " + turn, 0, 48, 80, 520))));
                        retainedFlow = created.get();
                    }
                    assertEquals(30, canvas.undoDepthForTest());

                    NoteToolRegistry tools = NoteTools.createDefault();
                    NoteToolContext context = canvas.createToolContext(null);
                    String flowId = retainedFlow;

                    NoteCanvasView.AiEditRecord read = canvas.newAiEditRecord("pure-read");
                    assertFalse(recordRound(canvas, read, "pure-read", () -> {
                        NoteTool.Result result = tools.invoke("read_page_map", new JSONObject(),
                                context, NoteTool.Permission.READ_ONLY);
                        assertTrue(result.ok);
                        assertFalse(result.mutatedDocument);
                    }));

                    NoteCanvasView.AiEditRecord rejected = canvas.newAiEditRecord("rejected");
                    assertFalse(recordRound(canvas, rejected, "rejected", () -> {
                        NoteTool.Result result = tools.invoke("write_text",
                                new JSONObject().put("content", "不应写入")
                                        .put("placement", new JSONObject()
                                                .put("page", 1).put("slot", "largest_free")),
                                context, NoteTool.Permission.READ_ONLY);
                        assertFalse(result.ok);
                        assertFalse(result.mutatedDocument);
                    }));

                    TextFlow existing = flow(canvas, flowId);
                    assertNotNull(existing);
                    NoteCanvasView.AiEditRecord same = canvas.newAiEditRecord("same-style");
                    assertFalse(recordRound(canvas, same, "same-style", () -> {
                        NoteTool.Result result = tools.invoke("set_text_flow_style",
                                new JSONObject().put("flowId", flowId)
                                        .put("fontSizeSp", existing.fontSizeSp)
                                        .put("lineHeight", existing.lineHeight),
                                context, NoteTool.Permission.MODIFY_EXISTING);
                        assertTrue(result.ok);
                        assertFalse(result.mutatedDocument);
                    }));

                    assertEquals(30, canvas.undoDepthForTest());
                    canvas.undo();
                    assertNull(flow(canvas, flowId));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
        }
    }

    @Test
    public void selectiveUndoAfterHandwritingKeepsInkAndToolbarRoundTrips() {
        try (CanvasSession session = CanvasSession.open(1)) {
            session.scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = session.canvas;
                    String owner = "ai-before-handwriting";
                    NoteCanvasView.AiEditRecord record = canvas.newAiEditRecord(owner);
                    AtomicReference<String> flowId = new AtomicReference<>();
                    assertTrue(recordRound(canvas, record, owner, () -> flowId.set(
                            createFlow(canvas, "AI 文字", 0, 48, 96, 520))));

                    drawStrokeAtWorld(canvas, 620, 760, 680, 800);
                    assertEquals(1, canvas.getStrokeCount());
                    NoteCanvasView.AiEditApplyResult undone = canvas.applyAiEdit(record, false);
                    assertEquals(1, undone.changedFlows);
                    assertNull(flow(canvas, flowId.get()));
                    assertEquals(1, canvas.getStrokeCount());

                    canvas.undo();
                    assertNotNull(flow(canvas, flowId.get()));
                    assertEquals(1, canvas.getStrokeCount());
                    assertEquals(NoteCanvasView.AiEditState.APPLIED,
                            canvas.aiEditState(record));

                    canvas.redo();
                    assertNull(flow(canvas, flowId.get()));
                    assertEquals(1, canvas.getStrokeCount());
                    assertEquals(NoteCanvasView.AiEditState.UNDONE,
                            canvas.aiEditState(record));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
        }
    }

    @Test
    public void editingOneTargetMakesWholeRequestConflictWithoutPartialUndo() {
        try (CanvasSession session = CanvasSession.open(1)) {
            session.scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = session.canvas;
                    String owner = "two-target-request";
                    NoteCanvasView.AiEditRecord record = canvas.newAiEditRecord(owner);
                    AtomicReference<String> first = new AtomicReference<>();
                    AtomicReference<String> second = new AtomicReference<>();
                    assertTrue(recordRound(canvas, record, owner, () -> {
                        first.set(createFlow(canvas, "目标 A", 0, 48, 96, 520));
                        second.set(createFlow(canvas, "目标 B", 0, 48, 300, 520));
                    }));

                    NoteTextBox fragment = firstFragment(canvas, first.get());
                    assertNotNull(fragment);
                    canvas.updateTextBox(fragment.id, NoteTextBox.Format.MARKDOWN,
                            "用户改写目标 A");
                    NoteCanvasView.AiEditApplyResult result = canvas.applyAiEdit(record, false);

                    assertEquals(NoteCanvasView.AiEditState.CONFLICT, result.state);
                    assertEquals(0, result.changedFlows);
                    assertEquals("用户改写目标 A", flow(canvas, first.get()).source);
                    assertEquals("目标 B", flow(canvas, second.get()).source);
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
        }
    }

    @Test
    public void dependentRequestMustUndoBThenA() {
        try (CanvasSession session = CanvasSession.open(1)) {
            session.scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = session.canvas;
                    AtomicReference<String> flowId = new AtomicReference<>();
                    NoteCanvasView.AiEditRecord a = canvas.newAiEditRecord("request-a");
                    assertTrue(recordRound(canvas, a, "request-a", () -> flowId.set(
                            createFlow(canvas, "A 创建", 0, 48, 96, 520))));

                    NoteCanvasView.AiEditRecord b = canvas.newAiEditRecord("request-b");
                    assertTrue(recordRound(canvas, b, "request-b", () ->
                            canvas.createToolContext(null).styleTextFlow(
                                    flowId.get(), 24f, null, null)));

                    NoteCanvasView.AiEditApplyResult blocked = canvas.applyAiEdit(a, false);
                    assertEquals(NoteCanvasView.AiEditState.CONFLICT, blocked.state);
                    assertEquals(0, blocked.changedFlows);
                    assertEquals(24f, flow(canvas, flowId.get()).fontSizeSp, 0.01f);

                    assertEquals(NoteCanvasView.AiEditState.UNDONE,
                            canvas.applyAiEdit(b, false).state);
                    assertEquals(16f, flow(canvas, flowId.get()).fontSizeSp, 0.01f);
                    assertEquals(NoteCanvasView.AiEditState.UNDONE,
                            canvas.applyAiEdit(a, false).state);
                    assertNull(flow(canvas, flowId.get()));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
        }
    }

    @Test
    public void deletedAiCreationCanReturnButDeletedUserFlowCannotBeResurrected() {
        try (CanvasSession session = CanvasSession.open(1)) {
            session.scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = session.canvas;

                    NoteCanvasView.AiEditRecord created = canvas.newAiEditRecord("created-flow");
                    AtomicReference<String> createdId = new AtomicReference<>();
                    assertTrue(recordRound(canvas, created, "created-flow", () -> createdId.set(
                            createFlow(canvas, "AI 新建", 0, 48, 96, 520))));
                    canvas.deleteTextBox(firstFragment(canvas, createdId.get()).id);
                    assertEquals(NoteCanvasView.AiEditState.UNDONE,
                            canvas.aiEditState(created));
                    assertEquals(NoteCanvasView.AiEditState.APPLIED,
                            canvas.applyAiEdit(created, true).state);
                    assertNotNull(flow(canvas, createdId.get()));

                    NoteTextBox user = canvas.addTextBoxAt(
                            NoteTextBox.Format.MARKDOWN, "用户原文", 120, 500);
                    assertNotNull(user);
                    NoteCanvasView.AiEditRecord modified = canvas.newAiEditRecord("modified-flow");
                    assertTrue(recordRound(canvas, modified, "modified-flow", () ->
                            canvas.updateTextBox(user.id, NoteTextBox.Format.MARKDOWN,
                                    "AI 修改用户原文")));
                    canvas.deleteTextBox(firstFragment(canvas, user.flowId).id);
                    NoteCanvasView.AiEditApplyResult refused = canvas.applyAiEdit(modified, true);
                    assertEquals(NoteCanvasView.AiEditState.CONFLICT, refused.state);
                    assertEquals(0, refused.changedFlows);
                    assertNull(flow(canvas, user.flowId));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
        }
    }

    @Test
    public void tailPageAndReapplyOccupancyProtectLaterHandwriting() {
        try (CanvasSession emptyTail = CanvasSession.open(1)) {
            emptyTail.scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = emptyTail.canvas;
                    NoteCanvasView.AiEditRecord record = canvas.newAiEditRecord("empty-tail");
                    assertTrue(recordRound(canvas, record, "empty-tail", () ->
                            createFlow(canvas, "尾页 AI", 1, 48, 96, 520)));
                    assertEquals(2, canvas.getPageCount());
                    canvas.applyAiEdit(record, false);
                    assertEquals(1, canvas.getPageCount());
                } catch (Exception error) { throw new AssertionError(error); }
            });
        }

        try (CanvasSession occupiedTail = CanvasSession.open(1)) {
            occupiedTail.scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = occupiedTail.canvas;
                    NoteCanvasView.AiEditRecord record = canvas.newAiEditRecord("occupied-tail");
                    assertTrue(recordRound(canvas, record, "occupied-tail", () ->
                            createFlow(canvas, "尾页 AI", 1, 48, 96, 520)));
                    canvas.goToPage(1);
                    drawStrokeAtWorld(canvas, 620, pageTop(1) + 760,
                            680, pageTop(1) + 800);
                    canvas.applyAiEdit(record, false);
                    assertEquals(2, canvas.getPageCount());
                    assertEquals(1, canvas.getStrokeCount());
                } catch (Exception error) { throw new AssertionError(error); }
            });
        }

        try (CanvasSession userAddedTail = CanvasSession.open(1)) {
            userAddedTail.scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = userAddedTail.canvas;
                    NoteCanvasView.AiEditRecord record = canvas.newAiEditRecord("user-tail");
                    AtomicReference<String> flowId = new AtomicReference<>();
                    float density = activity.getResources().getDisplayMetrics().density;
                    String lateMeasuredSource = "第一段。\n\n第二段。\n\n第三段。";
                    float initialHeightDp = canvas.createToolContext(null).measureContentHeight(
                            lateMeasuredSource, NoteTextBox.Format.MARKDOWN,
                            520f / density, 16f, TextFlow.DEFAULT_LINE_HEIGHT);
                    float lateHeightAnchor = 1100f - 16f * density
                            - initialHeightDp * density * 1.4f;
                    assertTrue(recordRound(canvas, record, "user-tail", () ->
                            flowId.set(createFlow(canvas, lateMeasuredSource, 0, 48,
                                    lateHeightAnchor, 520))));
                    assertEquals(1, canvas.getPageCount());

                    // The user owns this page: it was explicitly added after the
                    // request completed and before the renderer's late height report.
                    canvas.addPage();
                    assertEquals(2, canvas.getPageCount());
                    NoteTextBox initial = firstFragment(canvas, flowId.get());
                    assertNotNull(initial);
                    float initialHeight = initial.height;
                    canvas.applyMeasuredFragmentHeight(initial.id,
                            initialHeight * 2.4f / density);
                    assertTrue(hasFragmentOnPage(canvas, flowId.get(), 1));
                    canvas.refreshAiEditFootprint(record);

                    assertEquals(NoteCanvasView.AiEditState.UNDONE,
                            canvas.applyAiEdit(record, false).state);
                    assertEquals(2, canvas.getPageCount());
                } catch (Exception error) { throw new AssertionError(error); }
            });
        }

        try (CanvasSession blocked = CanvasSession.open(1)) {
            blocked.scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = blocked.canvas;
                    NoteCanvasView.AiEditRecord record = canvas.newAiEditRecord("blocked-reapply");
                    AtomicReference<String> id = new AtomicReference<>();
                    assertTrue(recordRound(canvas, record, "blocked-reapply", () -> id.set(
                            createFlow(canvas, "原位置", 0, 48, 96, 520))));
                    RectF target = bounds(firstFragment(canvas, id.get()));
                    canvas.applyAiEdit(record, false);
                    drawStrokeAtWorld(canvas, target.centerX() - 10, target.centerY(),
                            target.centerX() + 10, target.centerY());
                    NoteCanvasView.AiEditApplyResult result = canvas.applyAiEdit(record, true);
                    assertEquals(NoteCanvasView.AiEditState.CONFLICT, result.state);
                    assertEquals(0, result.changedFlows);
                    assertNull(flow(canvas, id.get()));
                } catch (Exception error) { throw new AssertionError(error); }
            });
        }

        try (CanvasSession otherPage = CanvasSession.open(1)) {
            otherPage.scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = otherPage.canvas;
                    NoteCanvasView.AiEditRecord record = canvas.newAiEditRecord("other-page");
                    AtomicReference<String> id = new AtomicReference<>();
                    assertTrue(recordRound(canvas, record, "other-page", () -> id.set(
                            createFlow(canvas, "第一页", 0, 48, 96, 520))));
                    canvas.applyAiEdit(record, false);
                    canvas.addPage();
                    drawStrokeAtWorld(canvas, 160, pageTop(1) + 200,
                            240, pageTop(1) + 240);
                    NoteCanvasView.AiEditApplyResult result = canvas.applyAiEdit(record, true);
                    assertEquals(NoteCanvasView.AiEditState.APPLIED, result.state);
                    assertNotNull(flow(canvas, id.get()));
                    assertEquals(1, canvas.getStrokeCount());
                } catch (Exception error) { throw new AssertionError(error); }
            });
        }
    }

    @Test
    public void measuredExpansionBecomesPartOfReapplyOccupancy() {
        try (CanvasSession session = CanvasSession.open(1)) {
            session.scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = session.canvas;
                    NoteCanvasView.AiEditRecord record = canvas.newAiEditRecord("measured-flow");
                    AtomicReference<String> flowId = new AtomicReference<>();
                    assertTrue(recordRound(canvas, record, "measured-flow", () -> flowId.set(
                            createFlow(canvas,
                                    "测高第一段。\n\n测高第二段。\n\n测高第三段。\n\n测高第四段。",
                                    0, 48, 96, 520))));

                    NoteTextBox initial = firstFragment(canvas, flowId.get());
                    assertNotNull(initial);
                    float density = activity.getResources().getDisplayMetrics().density;
                    float initialY = initial.y;
                    float initialHeight = initial.height;
                    canvas.applyMeasuredFragmentHeight(initial.id,
                            initialHeight * 2.4f / density);
                    NoteTextBox expanded = firstFragment(canvas, flowId.get());
                    assertNotNull(expanded);
                    assertTrue(expanded.height > initialHeight + 8f);
                    canvas.refreshAiEditFootprint(record);

                    float newOnlyY = Math.min(expanded.y + expanded.height - 4f,
                            initialY + initialHeight +
                                    (expanded.height - initialHeight) / 2f);
                    assertTrue(newOnlyY > initialY + initialHeight);
                    assertEquals(NoteCanvasView.AiEditState.UNDONE,
                            canvas.applyAiEdit(record, false).state);
                    drawStrokeAtWorld(canvas, expanded.x + 30f, newOnlyY,
                            expanded.x + 100f, newOnlyY);

                    NoteCanvasView.AiEditApplyResult result = canvas.applyAiEdit(record, true);
                    assertEquals(NoteCanvasView.AiEditState.CONFLICT, result.state);
                    assertEquals(0, result.changedFlows);
                    assertNull(flow(canvas, flowId.get()));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
        }
    }

    @Test
    public void activeStrokeRejectsTextMutationsAndUndoRemovesWholeStroke() {
        try (CanvasSession session = CanvasSession.open(1)) {
            session.scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = session.canvas;
                    NoteTextBox existing = canvas.addTextBoxAt(
                            NoteTextBox.Format.MARKDOWN, "用户文字", 120, 420);
                    assertNotNull(existing);
                    TextFlow before = flow(canvas, existing.flowId).copy();

                    canvas.setPenOnly(false);
                    canvas.setTool(NoteCanvasView.Tool.PEN);
                    float scale = canvas.getViewportScale();
                    float x1 = 520f * scale + canvas.getViewportPanX();
                    float y1 = 720f * scale + canvas.getViewportPanY();
                    float x2 = 600f * scale + canvas.getViewportPanX();
                    float y2 = 760f * scale + canvas.getViewportPanY();
                    long down = SystemClock.uptimeMillis();
                    dispatch(canvas, down, down, MotionEvent.ACTION_DOWN, x1, y1);
                    dispatch(canvas, down, down + 8, MotionEvent.ACTION_MOVE, x2, y2);
                    assertEquals(1, canvas.getStrokeCount());
                    assertTrue(canvas.isUserInteractionActive());
                    int historyWhileDrawing = canvas.undoDepthForTest();

                    NoteToolContext context = canvas.createToolContext(null);
                    assertNull(context.styleTextFlow(existing.flowId, 24f, 1.8f, null));
                    assertNull(context.moveTextFlow(existing.flowId,
                            new PlacementResolver.Placement(0, 220, 160, 520,
                                    "书写期间不应移动")));
                    TextFlow still = flow(canvas, existing.flowId);
                    assertNotNull(still);
                    assertEquals(before.source, still.source);
                    assertEquals(before.fontSizeSp, still.fontSizeSp, 0.01f);
                    assertEquals(before.lineHeight, still.lineHeight, 0.001f);
                    assertEquals(before.anchorPageIndex, still.anchorPageIndex);
                    assertEquals(before.anchorXInPage, still.anchorXInPage, 0.01f);
                    assertEquals(before.anchorYInPage, still.anchorYInPage, 0.01f);
                    assertEquals(historyWhileDrawing, canvas.undoDepthForTest());

                    dispatch(canvas, down, down + 16, MotionEvent.ACTION_MOVE,
                            x2 + 30f, y2 + 20f);
                    dispatch(canvas, down, down + 24, MotionEvent.ACTION_UP,
                            x2 + 30f, y2 + 20f);
                    assertFalse(canvas.isUserInteractionActive());
                    assertEquals(1, canvas.getStrokeCount());
                    assertTrue(canvas.getPointCount() >= 3);

                    canvas.undo();
                    assertEquals(0, canvas.getStrokeCount());
                    assertEquals(0, canvas.getPointCount());
                    assertNotNull(flow(canvas, existing.flowId));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
        }
    }

    @Test
    public void pageDeleteDuplicateAndMoveUndoRestoreOriginalInkCoordinates() throws Exception {
        try (CanvasSession session = CanvasSession.open(strokedDocument())) {
            session.scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = session.canvas;
                    Map<String, Float> original = strokeYById(canvas.toJsonDocument());

                    assertTrue(canvas.deletePage(0));
                    canvas.undo();
                    assertEquals(original, strokeYById(canvas.toJsonDocument()));

                    assertTrue(canvas.duplicatePage(1));
                    canvas.undo();
                    assertEquals(original, strokeYById(canvas.toJsonDocument()));

                    assertTrue(canvas.movePage(0, 2));
                    canvas.undo();
                    assertEquals(original, strokeYById(canvas.toJsonDocument()));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
        }
    }

    private interface Mutation { void run() throws Exception; }

    private static boolean recordRound(NoteCanvasView canvas,
                                       NoteCanvasView.AiEditRecord record,
                                       String owner, Mutation mutation) throws Exception {
        NoteCanvasView.AiEditSnapshot before = canvas.captureAiEditSnapshot();
        canvas.beginAiUndoTransaction(owner);
        boolean changed = false;
        try {
            mutation.run();
        } finally {
            changed = canvas.recordAiEditDelta(record, before);
            canvas.endAiUndoTransaction(owner, changed);
        }
        return changed;
    }

    private static String createFlow(NoteCanvasView canvas, String source, int page,
                                     float x, float y, float width) throws Exception {
        JSONObject report = canvas.createToolContext(null).createTextFlow(
                source, NoteTextBox.Format.MARKDOWN,
                new PlacementResolver.Placement(page, x, y, width, "测试位置"));
        assertNotNull(report);
        return report.getString("flowId");
    }

    private static TextFlow flow(NoteCanvasView canvas, String id) {
        for (TextFlow flow : canvas.getTextFlows()) if (id.equals(flow.id)) return flow;
        return null;
    }

    private static NoteTextBox firstFragment(NoteCanvasView canvas, String flowId) {
        for (NoteTextBox box : canvas.getTextBoxes()) if (flowId.equals(box.flowId)) return box;
        return null;
    }

    private static boolean hasFragmentOnPage(NoteCanvasView canvas, String flowId, int page) {
        for (NoteTextBox box : canvas.getTextBoxes()) {
            if (flowId.equals(box.flowId) && box.pageIndex == page) return true;
        }
        return false;
    }

    private static RectF bounds(NoteTextBox box) {
        assertNotNull(box);
        return new RectF(box.x, box.y, box.x + box.width, box.y + box.height);
    }

    private static void drawStrokeAtWorld(NoteCanvasView canvas, float x1, float y1,
                                          float x2, float y2) {
        int strokesBefore = canvas.getStrokeCount();
        canvas.setPenOnly(false);
        canvas.setTool(NoteCanvasView.Tool.PEN);
        float scale = canvas.getViewportScale();
        float sx1 = x1 * scale + canvas.getViewportPanX();
        float sy1 = y1 * scale + canvas.getViewportPanY();
        float sx2 = x2 * scale + canvas.getViewportPanX();
        float sy2 = y2 * scale + canvas.getViewportPanY();
        long down = SystemClock.uptimeMillis();
        dispatch(canvas, down, down, MotionEvent.ACTION_DOWN, sx1, sy1);
        dispatch(canvas, down, down + 8, MotionEvent.ACTION_MOVE, sx2, sy2);
        dispatch(canvas, down, down + 16, MotionEvent.ACTION_UP, sx2, sy2);
        assertEquals(strokesBefore + 1, canvas.getStrokeCount());
    }

    private static void dispatch(NoteCanvasView canvas, long downTime, long eventTime,
                                 int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        canvas.onTouchEvent(event);
        event.recycle();
    }

    private static float pageTop(int page) { return page * (1100f + 24f); }

    private static JSONObject emptyDocument(int pages) throws Exception {
        return new JSONObject().put("schemaVersion", 8).put("id", "history-fixture")
                .put("title", "AI 历史夹具").put("pageWidth", 800)
                .put("pageHeight", 1100).put("pageGap", 24).put("pageCount", pages)
                .put("strokes", new JSONArray()).put("images", new JSONArray())
                .put("textFlows", new JSONArray()).put("textBoxes", new JSONArray());
    }

    private static JSONObject strokedDocument() throws Exception {
        JSONObject document = emptyDocument(3);
        JSONArray strokes = document.getJSONArray("strokes");
        for (int page = 0; page < 3; page++) {
            float top = pageTop(page);
            strokes.put(new JSONObject().put("id", "stroke-page-" + page)
                    .put("color", "#FF17212B").put("baseWidth", 3)
                    .put("createdAt", page + 1)
                    .put("points", new JSONArray()
                            .put(new JSONObject().put("x", 100).put("y", top + 120)
                                    .put("timestamp", 1).put("pressure", 0.5))
                            .put(new JSONObject().put("x", 180).put("y", top + 180)
                                    .put("timestamp", 2).put("pressure", 0.5))));
        }
        return document;
    }

    private static Map<String, Float> strokeYById(JSONObject document) throws Exception {
        Map<String, Float> values = new HashMap<>();
        JSONArray strokes = document.getJSONArray("strokes");
        for (int index = 0; index < strokes.length(); index++) {
            JSONObject stroke = strokes.getJSONObject(index);
            values.put(stroke.getString("id"), (float) stroke.getJSONArray("points")
                    .getJSONObject(0).getDouble("y"));
        }
        return values;
    }

    private static final class CanvasSession implements AutoCloseable {
        final ActivityScenario<MainActivity> scenario;
        final NoteCanvasView canvas;

        private CanvasSession(ActivityScenario<MainActivity> scenario, NoteCanvasView canvas) {
            this.scenario = scenario;
            this.canvas = canvas;
        }

        static CanvasSession open(int pages) {
            try {
                return open(emptyDocument(pages));
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }

        static CanvasSession open(JSONObject document) {
            ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
            AtomicReference<NoteCanvasView> result = new AtomicReference<>();
            try {
                scenario.onActivity(activity -> {
                    try {
                        int width = dp(activity, 800);
                        int height = dp(activity, 1100);
                        FrameLayout host = new FrameLayout(activity);
                        NoteCanvasView canvas = new NoteCanvasView(activity);
                        host.addView(canvas, new FrameLayout.LayoutParams(width, height));
                        activity.setContentView(host, new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        canvas.measure(
                                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                        canvas.layout(0, 0, width, height);
                        canvas.loadJsonDocument(new JSONObject(document.toString()));
                        result.set(canvas);
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
                assertNotNull(result.get());
                return new CanvasSession(scenario, result.get());
            } catch (RuntimeException | Error error) {
                scenario.close();
                throw error;
            }
        }

        @Override public void close() { scenario.close(); }
    }

    private static int dp(MainActivity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
