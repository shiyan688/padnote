package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.MotionEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic in-memory Canvas tests for atomic manual text-flow editing. */
@RunWith(AndroidJUnit4.class)
public final class ManualTextEditingInstrumentedTest {
    private static final String FLOW_ID = "synthetic-manual-edit-flow";
    private static final String ORIGINAL = "Short editable text.";

    @Test public void failedCommitKeepsDocumentUndoAndNotificationsThenSuccessIsOneUndoRedo() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(context);
            AtomicInteger documentNotifications = new AtomicInteger();
            canvas.setListener(new NoteCanvasView.Listener() {
                @Override public void onInkStatsChanged(int strokes, int points, float pressure, String status) { }
                @Override public void onDocumentChanged() { documentNotifications.incrementAndGet(); }
                @Override public void onSelectionChanged(int selectedStrokes, boolean canUndo, boolean canRedo) { }
                @Override public void onViewportChanged(int currentPage, int pageCount, int zoomPercent, String status) { }
                @Override public void onTextBoxesChanged(List<NoteTextBox> textBoxes) { }
                @Override public void onTextBoxSelectionChanged(String textBoxId) { }
                @Override public void onTextInsertRequested(float worldX, float worldY) { }
            });
            try {
                canvas.layout(0, 0, 1200, 1000);
                canvas.loadJsonDocument(document());
                int pageCount = canvas.getPageCount();
                int undoDepth = canvas.undoDepthForTest();
                String before = canonicalState(canvas);
                int notifyCount = documentNotifications.get();
                String oversizedProtectedTable = "| id | detail |\n| --- | --- |\n| 1 | `" +
                        "protected-cell-content-".repeat(500) + "` |";

                String fragmentId = fragmentIdForFlow(canvas, FLOW_ID);
                NoteCanvasView.TextEditResult failed = canvas.updateTextBox(fragmentId,
                        NoteTextBox.Format.MARKDOWN, oversizedProtectedTable, 18f, 1.28f);
                assertFalse("unrenderable/protected source must be rejected", failed.committed);
                assertEquals(before, canonicalState(canvas));
                assertEquals(pageCount, canvas.getPageCount());
                assertEquals(undoDepth, canvas.undoDepthForTest());
                assertEquals(notifyCount, documentNotifications.get());

                NoteCanvasView.TextEditResult committed = canvas.updateTextBox(fragmentId,
                        NoteTextBox.Format.MARKDOWN, "A corrected retry succeeds.", 18f, 1.28f);
                assertTrue(committed.committed);
                assertEquals(undoDepth + 1, canvas.undoDepthForTest());
                assertEquals("A corrected retry succeeds.", flow(canvas).source);
                canvas.undo();
                assertEquals(ORIGINAL, flow(canvas).source);
                assertTrue(canvas.canRedo());
                canvas.redo();
                assertEquals("A corrected retry succeeds.", flow(canvas).source);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
    }

    @Test public void invalidNewTextIsRejectedWithoutLeavingAFlowOrUndoEntry() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(context);
            try {
                canvas.layout(0, 0, 1200, 1000);
                canvas.loadJsonDocument(emptyDocument());
                canvas.setTool(NoteCanvasView.Tool.PEN);
                NoteTextBox placeholder = canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                        "", 120f, 120f);
                assertTrue(placeholder != null);
                int undoBefore = canvas.undoDepthForTest();
                NoteCanvasView.TextEditResult rejected = canvas.commitNewTextBox(
                        placeholder.id, NoteTextBox.Format.MARKDOWN,
                        "| id | detail |\n| --- | --- |\n| 1 | `" +
                                "protected-cell-content-".repeat(500) + "` |", 18f, 1.28f);
                assertFalse(rejected.committed);
                assertEquals("failed placeholder commit retains its empty draft flow", "",
                        canvas.getTextFlows().get(0).source);
                assertEquals(undoBefore, canvas.undoDepthForTest());
                canvas.discardNewTextBox(placeholder.id);
                assertTrue(canvas.getTextFlows().isEmpty());
                assertEquals(0, canvas.undoDepthForTest());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
    }

    @Test public void unrelatedStrokePreventsPlaceholderCancelFromPoppingItsUndoHistory() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(context);
            try {
                canvas.layout(0, 0, 1200, 1000);
                canvas.loadJsonDocument(emptyDocument());
                canvas.setTool(NoteCanvasView.Tool.PEN);
                canvas.setPenOnly(false);
                NoteTextBox placeholder = canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                        "", 120f, 120f);
                assertTrue(placeholder != null);
                int strokesBefore = canvas.toJsonDocument("synthetic-stroke-cancel", "Synthetic")
                        .getJSONArray("strokes").length();
                dispatchStroke(canvas);
                assertEquals("synthetic finger input must create one pen stroke", strokesBefore + 1,
                        canvas.toJsonDocument("synthetic-stroke-cancel", "Synthetic")
                                .getJSONArray("strokes").length());
                assertEquals(2, canvas.undoDepthForTest());

                canvas.discardNewTextBox(placeholder.id);
                JSONObject afterCancel = canvas.toJsonDocument("synthetic-stroke-cancel", "Synthetic");
                assertEquals("the unrelated stroke survives cancellation", 1,
                        afterCancel.getJSONArray("strokes").length());
                assertTrue(canvas.getTextFlows().isEmpty());
                assertEquals("safe deletion adds its own undo item", 3, canvas.undoDepthForTest());
                canvas.undo();
                assertEquals("undo restores the placeholder without undoing the stroke", 1,
                        canvas.toJsonDocument("synthetic-stroke-cancel", "Synthetic")
                                .getJSONArray("strokes").length());
                assertEquals(1, canvas.getTextFlows().size());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
    }

    @Test public void restoredPlaceholderCancelPreservesUnrelatedUndoHistory() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(context);
            try {
                canvas.layout(0, 0, 1200, 1000);
                canvas.loadJsonDocument(documentWithRestoredPlaceholder());
                NoteCanvasView.TextEditResult unrelated = canvas.updateTextBox(
                        fragmentIdForFlow(canvas, "unrelated-flow"),
                        NoteTextBox.Format.MARKDOWN, "Unrelated saved edit.", 18f, 1.28f);
                assertTrue(unrelated.committed);
                assertEquals(1, canvas.undoDepthForTest());
                NoteTextBox placeholder = null;
                for (NoteTextBox box : canvas.getTextBoxes()) {
                    if ("restored-placeholder-flow".equals(box.flowId)) placeholder = box;
                }
                assertTrue("restored placeholder is locatable", placeholder != null);

                canvas.discardNewTextBox(placeholder.id);
                assertEquals(2, canvas.undoDepthForTest());
                assertEquals("Unrelated saved edit.", findFlow(canvas, "unrelated-flow").source);
                assertTrue(canvas.getTextFlows().stream()
                        .noneMatch(flow -> "restored-placeholder-flow".equals(flow.id)));
                canvas.undo();
                assertEquals("Unrelated saved edit.", findFlow(canvas, "unrelated-flow").source);
                assertTrue(canvas.getTextFlows().stream()
                        .anyMatch(flow -> "restored-placeholder-flow".equals(flow.id)));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
    }

    @Test public void enlargingProtectedTableAtLastPageIsRejectedAtomically() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration stableConfiguration = new Configuration(context.getResources().getConfiguration());
        stableConfiguration.densityDpi = DisplayMetrics.DENSITY_DEFAULT;
        stableConfiguration.fontScale = 1f;
        Context stableCanvasContext = context.createConfigurationContext(stableConfiguration);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(stableCanvasContext);
            AtomicInteger changed = new AtomicInteger();
            canvas.setListener(listener(changed));
            try {
                canvas.layout(0, 0, 1200, 1000);
                String protectedCell = "`" + "protected-cell-".repeat(18) + "`";
                String source = "| id | details |\n| --- | --- |\n| 1 | " + protectedCell + " |";
                canvas.loadJsonDocument(documentWithFlow("protected-table-flow", source, 18d,
                        499, 500));
                TextFlow flow = findFlow(canvas, "protected-table-flow");
                assertTrue("fixture must begin as a fully laid out valid table", flow.lastLayoutComplete);
                assertEquals(500, canvas.getPageCount());
                int pageCount = canvas.getPageCount();
                int undoDepth = canvas.undoDepthForTest();
                String before = canonicalState(canvas);
                int notifications = changed.get();

                NoteCanvasView.TextEditResult failed = canvas.setTextFlowFontSize(
                        fragmentIdForFlow(canvas, flow.id), 32f);
                assertFalse("protected cell that cannot fit must reject the style transaction",
                        failed.committed);
                assertEquals(before, canonicalState(canvas));
                assertEquals(18f, findFlow(canvas, flow.id).fontSizeSp, 0.001f);
                assertEquals(pageCount, canvas.getPageCount());
                assertEquals(undoDepth, canvas.undoDepthForTest());
                assertEquals(notifications, changed.get());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
    }

    @Test public void movingLastPageFlowPastPage499IsRejectedAtomically() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(context);
            AtomicInteger changed = new AtomicInteger();
            canvas.setListener(listener(changed));
            try {
                canvas.layout(0, 0, 1200, 1000);
                canvas.loadJsonDocument(documentWithFlow("last-page-flow", "Still on page 500.",
                        18d, 499, 500));
                assertTrue(findFlow(canvas, "last-page-flow").lastLayoutComplete);
                int pageCount = canvas.getPageCount();
                int undoDepth = canvas.undoDepthForTest();
                String before = canonicalState(canvas);
                int notifications = changed.get();

                assertFalse(canvas.moveTextFlowByPages("last-page-flow", 1));
                assertEquals(before, canonicalState(canvas));
                assertEquals(499, findFlow(canvas, "last-page-flow").anchorPageIndex);
                assertEquals(pageCount, canvas.getPageCount());
                assertEquals(undoDepth, canvas.undoDepthForTest());
                assertEquals(notifications, changed.get());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
    }

    private static JSONObject document() throws Exception {
        return new JSONObject().put("schemaVersion", 8)
                .put("id", "synthetic-manual-edit")
                .put("title", "Synthetic manual edit")
                .put("pageWidth", 800d).put("pageHeight", 387.5556d).put("pageGap", 24d)
                .put("pageCount", 1).put("strokes", new JSONArray())
                .put("images", new JSONArray()).put("textBoxes", new JSONArray())
                .put("textFlows", new JSONArray().put(new JSONObject()
                        .put("id", FLOW_ID).put("format", "markdown").put("source", ORIGINAL)
                        .put("fontSizeSp", 18d).put("lineHeight", 1.28d).put("width", 700d)
                        .put("anchorPageIndex", 0).put("anchorXInPage", 24d)
                        .put("anchorYInPage", 24d)));
    }

    private static JSONObject emptyDocument() throws Exception {
        return document().put("id", "synthetic-empty-manual-insert")
                .put("title", "Synthetic empty manual insert")
                .put("textFlows", new JSONArray());
    }

    private static JSONObject documentWithRestoredPlaceholder() throws Exception {
        JSONObject result = document();
        result.put("id", "synthetic-restored-placeholder");
        result.put("textFlows", new JSONArray()
                .put(new JSONObject().put("id", "restored-placeholder-flow")
                        .put("format", "markdown").put("source", "").put("fontSizeSp", 18d)
                        .put("lineHeight", 1.28d).put("width", 700d)
                        .put("anchorPageIndex", 0).put("anchorXInPage", 24d)
                        .put("anchorYInPage", 24d))
                .put(new JSONObject().put("id", "unrelated-flow")
                        .put("format", "markdown").put("source", "Before unrelated edit.")
                        .put("fontSizeSp", 18d).put("lineHeight", 1.28d).put("width", 700d)
                        .put("anchorPageIndex", 0).put("anchorXInPage", 24d)
                        .put("anchorYInPage", 80d)));
        return result;
    }

    private static JSONObject documentWithFlow(String id, String source, double fontSize,
                                               int anchorPageIndex, int pageCount) throws Exception {
        return new JSONObject().put("schemaVersion", 8).put("id", "synthetic-boundary")
                .put("title", "Synthetic boundary")
                .put("pageWidth", 800d).put("pageHeight", 387.5556d).put("pageGap", 24d)
                .put("pageCount", pageCount).put("strokes", new JSONArray())
                .put("images", new JSONArray()).put("textBoxes", new JSONArray())
                .put("textFlows", new JSONArray().put(new JSONObject().put("id", id)
                        .put("format", "markdown").put("source", source)
                        .put("fontSizeSp", fontSize).put("lineHeight", 1.28d).put("width", 700d)
                        .put("anchorPageIndex", anchorPageIndex).put("anchorXInPage", 24d)
                        .put("anchorYInPage", 24d)));
    }

    private static void dispatchStroke(NoteCanvasView canvas) {
        long down = android.os.SystemClock.uptimeMillis();
        MotionEvent start = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 500f, 450f, 0);
        MotionEvent move = MotionEvent.obtain(down, down + 20, MotionEvent.ACTION_MOVE, 540f, 490f, 0);
        MotionEvent end = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, 580f, 530f, 0);
        try {
            canvas.onTouchEvent(start);
            canvas.onTouchEvent(move);
            canvas.onTouchEvent(end);
        } finally {
            start.recycle();
            move.recycle();
            end.recycle();
        }
    }

    private static NoteCanvasView.Listener listener(AtomicInteger changed) {
        return new NoteCanvasView.Listener() {
            @Override public void onInkStatsChanged(int strokes, int points, float pressure, String status) { }
            @Override public void onDocumentChanged() { changed.incrementAndGet(); }
            @Override public void onSelectionChanged(int selectedStrokes, boolean canUndo, boolean canRedo) { }
            @Override public void onViewportChanged(int currentPage, int pageCount, int zoomPercent, String status) { }
            @Override public void onTextBoxesChanged(List<NoteTextBox> textBoxes) { }
            @Override public void onTextBoxSelectionChanged(String textBoxId) { }
            @Override public void onTextInsertRequested(float worldX, float worldY) { }
        };
    }

    private static TextFlow flow(NoteCanvasView canvas) {
        for (TextFlow flow : canvas.getTextFlows()) if (FLOW_ID.equals(flow.id)) return flow;
        throw new AssertionError("synthetic flow missing");
    }

    private static TextFlow findFlow(NoteCanvasView canvas, String id) {
        for (TextFlow flow : canvas.getTextFlows()) if (id.equals(flow.id)) return flow;
        throw new AssertionError("synthetic flow missing: " + id);
    }

    private static String fragmentIdForFlow(NoteCanvasView canvas, String flowId) {
        for (NoteTextBox box : canvas.getTextBoxes()) {
            if (flowId.equals(box.flowId)) return box.id;
        }
        throw new AssertionError("synthetic flow has no renderer fragment: " + flowId);
    }

    private static String canonicalState(NoteCanvasView canvas) throws Exception {
        JSONObject json = canvas.toJsonDocument("synthetic-manual-edit", "Synthetic manual edit");
        json.remove("updatedAt");
        return json.toString();
    }
}
