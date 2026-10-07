package com.padnote.android;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Collections;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public final class Binary64NotePersistenceInstrumentedTest {
    private static final long LARGE_EXACT_INTEGER = 9007199254740993L;

    @Test public void geometryAndTimestampValuesSurviveRenderEditSaveAndReopen() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String noteId = NoteStore.create(context, "binary64 round trip").id;
        NoteCanvasView[] canvas = new NoteCanvasView[1];
        NoteCanvasView[] reopened = new NoteCanvasView[1];
        try {
            JSONObject[] edited = new JSONObject[1];
            runOnMain(() -> {
                canvas[0] = new NoteCanvasView(context);
                canvas[0].layout(0, 0, 800, 1000);
                canvas[0].loadJsonDocument(document(noteId));
                Bitmap renderTarget = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888);
                try { canvas[0].draw(new Canvas(renderTarget)); }
                finally { renderTarget.recycle(); }

                canvas[0].addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                        "an edit that must leave the imported geometry untouched", 80, 220);
                canvas[0].undo();
                assertEquals(1, canvas[0].toJsonDocument(noteId, "binary64 round trip")
                        .getJSONArray("textFlows").length());
                canvas[0].redo();
                assertEquals(2, canvas[0].toJsonDocument(noteId, "binary64 round trip")
                        .getJSONArray("textFlows").length());
                edited[0] = canvas[0].toJsonDocument(noteId, "binary64 round trip");
                exerciseCanonicalCopiesAndMovement(edited[0]);
            });
            assertPersistedValues(edited[0]);
            NoteStore.save(context, noteId, "binary64 round trip", NoteJsonCodec.stringify(edited[0]));

            JSONObject persisted = NoteStore.load(context, noteId);
            runOnMain(() -> {
                reopened[0] = new NoteCanvasView(context);
                reopened[0].layout(0, 0, 800, 1000);
                reopened[0].loadJsonDocument(persisted);
                assertPersistedValues(reopened[0].toJsonDocument(noteId, "binary64 round trip"));
            });
        } finally {
            runOnMain(() -> {
                if (reopened[0] != null) reopened[0].onDetachedFromWindow();
                if (canvas[0] != null) canvas[0].onDetachedFromWindow();
            });
            NoteStore.delete(context, noteId);
        }
    }



    @Test public void aiReceiptSaveAndUndoKeepsSignedZeroSourceGeometry() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String noteId = NoteStore.create(context, "AI binary64 receipt").id;
        NoteCanvasView[] canvas = new NoteCanvasView[1];
        NoteCanvasView[] cold = new NoteCanvasView[1];
        File aiRoot = new File(context.getCacheDir(), "binary64-ai-" + UUID.randomUUID());
        JSONObject[] afterEdit = new JSONObject[1];
        JSONObject[] undoneDocument = new JSONObject[1];
        AiConversationStore.VisibleEntry[] entries = new AiConversationStore.VisibleEntry[1];
        try {
            runOnMain(() -> {
                canvas[0] = new NoteCanvasView(context);
                canvas[0].layout(0, 0, 800, 1000);
                JSONObject source = document(noteId);
                source.getJSONArray("textFlows").getJSONObject(0).put("anchorXInPage", -0.0d);
                canvas[0].loadJsonDocument(source);
                NoteCanvasView.AiEditRecord record = canvas[0].newAiEditRecord("binary64-ai-owner");
                NoteCanvasView.AiEditSnapshot before = canvas[0].captureAiEditSnapshot();
                canvas[0].beginAiUndoTransaction(record.ownerId);
                boolean changed;
                try {
                    canvas[0].createToolContext(null).styleTextFlow("flow-binary64", 18.25f,
                            null, null);
                } finally {
                    changed = canvas[0].recordAiEditDelta(record, before);
                    canvas[0].endAiUndoTransaction(record.ownerId, changed);
                }
                if (!changed) throw new AssertionError("AI style edit did not change source flow");
                JSONObject receipt = canvas[0].serializeAiEditRecord(record);
                String receiptJson = AiConversationStore.encodeEditReceipt(receipt);
                entries[0] = new AiConversationStore.VisibleEntry(
                        "result", "assistant", "styled", "local", false,
                        record.changedFlowIds(), receipt.getString("digest"), receiptJson);
                afterEdit[0] = canvas[0].toJsonDocument(noteId, "AI binary64 receipt");
            });
            NoteStore.save(context, noteId, "AI binary64 receipt",
                    NoteJsonCodec.stringify(afterEdit[0]));
            AiConversationStore.Binding binding = new AiConversationStore.Binding(
                    "", "", "binary64-test-profile", 0, "", "");
            AiConversationStore store = new AiConversationStore(aiRoot, NoteStore.NO_WRITE_FAULTS);
            store.create(noteId, Collections.singletonList(entries[0]), Collections.emptyList(),
                    null, null, binding, "", false);
            AiConversationStore.Snapshot coldSnapshot = new AiConversationStore(
                    aiRoot, NoteStore.NO_WRITE_FAULTS).load(noteId);
            if (coldSnapshot == null || coldSnapshot.visibleTimeline.size() != 1) {
                throw new AssertionError("AI conversation file did not reopen");
            }
            AiConversationStore.VisibleEntry restoredEntry = coldSnapshot.visibleTimeline.get(0);
            JSONObject persistedAfterEdit = NoteStore.load(context, noteId);
            runOnMain(() -> {
                cold[0] = new NoteCanvasView(context);
                cold[0].layout(0, 0, 800, 1000);
                cold[0].loadJsonDocument(persistedAfterEdit);
                NoteCanvasView.AiEditRecord reopened = cold[0].restoreAiEditRecord(
                        new JSONObject(restoredEntry.receiptJson));
                if (reopened == null) throw new AssertionError("AI edit could not be reopened");
                if (cold[0].applyAiEdit(reopened, false).state != NoteCanvasView.AiEditState.UNDONE) {
                    throw new AssertionError("AI edit undo failed");
                }
                undoneDocument[0] = cold[0].toJsonDocument(noteId, "AI binary64 receipt");
                double anchor = undoneDocument[0].getJSONArray("textFlows").getJSONObject(0)
                        .getDouble("anchorXInPage");
                if (Double.doubleToRawLongBits(anchor) != Double.doubleToRawLongBits(-0.0d)) {
                    throw new AssertionError("AI undo lost negative-zero source geometry");
                }
            });
            NoteStore.save(context, noteId, "AI binary64 receipt",
                    NoteJsonCodec.stringify(undoneDocument[0]));
            JSONObject persisted = NoteStore.load(context, noteId);
            JSONObject flow = persisted.getJSONArray("textFlows").getJSONObject(0);
            assertExactDouble(flow.getDouble("anchorXInPage"), -0.0d);
        } finally {
            runOnMain(() -> {
                if (cold[0] != null) cold[0].onDetachedFromWindow();
                if (canvas[0] != null) canvas[0].onDetachedFromWindow();
            });
            NoteStore.delete(context, noteId);
            deleteOwnedTree(aiRoot);
        }
    }

    @Test public void explicitZeroAndSubFloatPageGapsSurviveLayoutAndDrawing() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String zeroId = NoteStore.create(context, "gap zero").id;
        String tinyId = NoteStore.create(context, "gap tiny").id;
        NoteCanvasView[] canvas = new NoteCanvasView[1];
        JSONObject[] documents = new JSONObject[2];
        try {
            runOnMain(() -> {
                canvas[0] = new NoteCanvasView(context);
                canvas[0].layout(0, 0, 800, 1000);
                JSONObject zero = document(zeroId);
                zero.put("pageGap", -0.0d);
                canvas[0].loadJsonDocument(zero);
                Bitmap target = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888);
                try { canvas[0].draw(new Canvas(target)); }
                finally { target.recycle(); }
                documents[0] = canvas[0].toJsonDocument(zeroId, "gap zero");
                assertExactDouble(documents[0].getDouble("pageGap"), -0.0d);

                JSONObject tiny = document(tinyId);
                tiny.put("pageGap", 1.0e-50d);
                canvas[0].loadJsonDocument(tiny);
                target = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888);
                try { canvas[0].draw(new Canvas(target)); }
                finally { target.recycle(); }
                documents[1] = canvas[0].toJsonDocument(tinyId, "gap tiny");
                assertExactDouble(documents[1].getDouble("pageGap"), 1.0e-50d);
            });
            NoteStore.save(context, zeroId, "gap zero", NoteJsonCodec.stringify(documents[0]));
            NoteStore.save(context, tinyId, "gap tiny", NoteJsonCodec.stringify(documents[1]));
            JSONObject zeroPersisted = NoteStore.load(context, zeroId);
            JSONObject tinyPersisted = NoteStore.load(context, tinyId);
            runOnMain(() -> {
                canvas[0].loadJsonDocument(zeroPersisted);
                assertExactDouble(canvas[0].toJsonDocument(zeroId, "gap zero reopened")
                        .getDouble("pageGap"), -0.0d);
                canvas[0].loadJsonDocument(tinyPersisted);
                assertExactDouble(canvas[0].toJsonDocument(tinyId, "gap tiny reopened")
                        .getDouble("pageGap"), 1.0e-50d);
            });
        } finally {
            runOnMain(() -> { if (canvas[0] != null) canvas[0].onDetachedFromWindow(); });
            NoteStore.delete(context, zeroId);
            NoteStore.delete(context, tinyId);
        }
    }

    @Test public void pageTopologyEditsTranslateCanonicalStrokeCoordinatesByExactStride() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String deleteId = NoteStore.create(context, "topology delete").id;
        String duplicateId = NoteStore.create(context, "topology duplicate").id;
        String moveId = NoteStore.create(context, "topology move").id;
        NoteCanvasView[] canvas = new NoteCanvasView[1];
        JSONObject[] results = new JSONObject[3];
        String[] pageZeroCopyId = new String[1];
        String[] pageZeroCopyFlowId = new String[1];
        double stride = 768.00001d + 3.0000001d;
        try {
            runOnMain(() -> {
                canvas[0] = new NoteCanvasView(context);
                canvas[0].layout(0, 0, 800, 1000);

                JSONObject deleted = topologyDocument(deleteId, stride, true);
                canvas[0].loadJsonDocument(deleted);
                if (!canvas[0].deletePage(1)) throw new AssertionError("page delete failed");
                assertPointY(canvas[0], "page-two", 2d * stride + 100d - stride);
                assertPointX(canvas[0], "page-two", -0.0d);
                assertPointY(canvas[0], "before-boundary", Math.nextDown(stride));
                results[0] = canvas[0].toJsonDocument(deleteId, "topology delete");

                JSONObject duplicated = topologyDocument(duplicateId, stride, true);
                canvas[0].loadJsonDocument(duplicated);
                if (!canvas[0].duplicatePage(0)) throw new AssertionError("page duplicate failed");
                assertPointY(canvas[0], "page-two", 2d * stride + 100d + stride);
                assertPointX(canvas[0], "page-two", -0.0d);
                assertPointY(canvas[0], "page-zero", 100d);
                pageZeroCopyId[0] = assertCopiedStroke(canvas[0], "page-zero",
                        40.123456789d, 100d + stride);
                pageZeroCopyFlowId[0] = assertCopiedFlow(canvas[0], "flow-source", 1);
                assertPointY(canvas[0], "before-boundary", Math.nextDown(stride));
                assertCopiedPointY(canvas[0], Math.nextDown(stride) + stride);
                results[1] = canvas[0].toJsonDocument(duplicateId, "topology duplicate");

                JSONObject moved = topologyDocument(moveId, stride, true);
                canvas[0].loadJsonDocument(moved);
                if (!canvas[0].movePage(0, 2)) throw new AssertionError("page move failed");
                assertPointY(canvas[0], "page-one", stride + 100d - stride);
                assertPointY(canvas[0], "page-two", 2d * stride + 100d - stride);
                assertPointX(canvas[0], "page-two", -0.0d);
                assertPointY(canvas[0], "before-boundary", Math.nextDown(stride) + 2d * stride);
                results[2] = canvas[0].toJsonDocument(moveId, "topology move");
            });
            NoteStore.save(context, deleteId, "topology delete", NoteJsonCodec.stringify(results[0]));
            NoteStore.save(context, duplicateId, "topology duplicate", NoteJsonCodec.stringify(results[1]));
            NoteStore.save(context, moveId, "topology move", NoteJsonCodec.stringify(results[2]));
            assertPersistedStroke(NoteStore.load(context, deleteId), "page-two",
                    2d * stride + 100d - stride);
            assertPersistedStroke(NoteStore.load(context, duplicateId), "page-two",
                    2d * stride + 100d + stride);
            assertPersistedCopiedStroke(NoteStore.load(context, duplicateId), pageZeroCopyId[0],
                    40.123456789d, 100d + stride);
            assertPersistedCopiedFlow(NoteStore.load(context, duplicateId), pageZeroCopyFlowId[0], 1);
            assertPersistedStroke(NoteStore.load(context, moveId), "page-two",
                    2d * stride + 100d - stride);
        } finally {
            runOnMain(() -> { if (canvas[0] != null) canvas[0].onDetachedFromWindow(); });
            NoteStore.delete(context, deleteId);
            NoteStore.delete(context, duplicateId);
            NoteStore.delete(context, moveId);
        }
    }

    @Test public void savedNoteExportFallbackUsesExactSerializerAfterReload() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String noteId = NoteStore.create(context, "binary64 export fallback").id;
        try {
            JSONObject source = document(noteId);
            NoteStore.save(context, noteId, "binary64 export fallback", NoteJsonCodec.stringify(source));
            JSONObject saved = NoteStore.load(context, noteId);
            // MainActivity.exportNoteToUri uses this same codec when it falls back to a saved note.
            String exportJson = NoteJsonCodec.stringify(saved);
            JSONObject exported = new JSONObject(exportJson);
            JSONObject negativeZero = exported.getJSONArray("strokes").getJSONObject(4)
                    .getJSONArray("points").getJSONObject(0);
            assertExactDouble(negativeZero.getDouble("x"), -0.0d);
            assertExactDouble(negativeZero.getDouble("y"), -0.0d);
        } finally {
            NoteStore.delete(context, noteId);
        }
    }

    private static JSONObject topologyDocument(String noteId, double stride,
                                                boolean includePageZero) throws Exception {
        JSONArray strokes = new JSONArray();
        if (includePageZero) strokes.put(topologyStroke("page-zero", 100d));
        strokes.put(topologyStroke("before-boundary", Math.nextDown(stride)));
        strokes.put(topologyStroke("page-one", stride + 100d));
        strokes.put(topologyStroke("page-two", 2d * stride + 100d));
        JSONArray flows = new JSONArray().put(new JSONObject().put("id", "flow-source")
                .put("format", "latex").put("source", "x")
                .put("fontSizeSp", 16.0000000001d).put("lineHeight", 1.3500000001d)
                .put("width", 120.00000001d).put("anchorPageIndex", 0)
                .put("anchorXInPage", -0.0d).put("anchorYInPage", 20.00000001d));
        return new JSONObject().put("schemaVersion", 8).put("id", noteId)
                .put("title", "topology binary64").put("pageWidth", 768.00001d)
                .put("pageHeight", 768.00001d).put("pageGap", 3.0000001d)
                .put("pageCount", 3).put("strokes", strokes)
                .put("textFlows", flows).put("images", new JSONArray())
                .put("textBoxes", new JSONArray());
    }

    private static String assertCopiedFlow(NoteCanvasView canvas, String sourceId, int copiedPage)
            throws Exception {
        JSONArray flows = canvas.toJsonDocument("topology", "topology").getJSONArray("textFlows");
        boolean sourceFound = false;
        String copyId = null;
        for (int index = 0; index < flows.length(); index++) {
            JSONObject flow = flows.getJSONObject(index);
            String id = flow.getString("id");
            if (id.equals(sourceId)) {
                sourceFound = true;
                assertFlowPrecision(flow, 0);
            } else if (flow.optInt("anchorPageIndex", -1) == copiedPage) {
                if (copyId != null) throw new AssertionError("multiple copied flows found");
                if (!java.util.UUID.fromString(id).toString().equals(id)) {
                    throw new AssertionError("copied flow identity is not a canonical UUID");
                }
                assertFlowPrecision(flow, copiedPage);
                copyId = id;
            }
        }
        if (!sourceFound || copyId == null || copyId.equals(sourceId)) {
            throw new AssertionError("duplicated text flow with exact persisted fields not found");
        }
        return copyId;
    }

    private static void assertPersistedCopiedFlow(JSONObject document, String id, int page)
            throws Exception {
        JSONArray flows = document.getJSONArray("textFlows");
        for (int index = 0; index < flows.length(); index++) {
            JSONObject flow = flows.getJSONObject(index);
            if (!flow.getString("id").equals(id)) continue;
            assertFlowPrecision(flow, page);
            return;
        }
        throw new AssertionError("persisted copied flow not found: " + id);
    }

    private static void assertFlowPrecision(JSONObject flow, int page) throws Exception {
        if (flow.getInt("anchorPageIndex") != page) {
            throw new AssertionError("flow page index mismatch");
        }
        assertExactDouble(flow.getDouble("fontSizeSp"), 16.0000000001d);
        assertExactDouble(flow.getDouble("lineHeight"), 1.3500000001d);
        assertExactDouble(flow.getDouble("width"), 120.00000001d);
        assertExactDouble(flow.getDouble("anchorXInPage"), -0.0d);
        assertExactDouble(flow.getDouble("anchorYInPage"), 20.00000001d);
    }

    private static JSONObject topologyStroke(String id, double y) throws Exception {
        double x = "page-two".equals(id) ? -0.0d : 40.123456789d;
        return stroke(id, 1725123456790L,
                new JSONObject().put("x", x).put("y", y)
                        .put("timestamp", 1725123456791L).put("pressure", 0.5d));
    }

    private static void assertPointX(NoteCanvasView canvas, String idPrefix, double expected)
            throws Exception {
        JSONArray values = canvas.toJsonDocument("topology", "topology")
                .getJSONArray("strokes");
        for (int index = 0; index < values.length(); index++) {
            JSONObject stroke = values.getJSONObject(index);
            if (stroke.getString("id").startsWith(idPrefix)) {
                assertExactDouble(stroke.getJSONArray("points").getJSONObject(0).getDouble("x"),
                        expected);
                return;
            }
        }
        throw new AssertionError("stroke not found: " + idPrefix);
    }

    private static String assertCopiedStroke(NoteCanvasView canvas, String sourceId,
                                             double expectedX, double expectedY) throws Exception {
        JSONArray values = canvas.toJsonDocument("topology", "topology")
                .getJSONArray("strokes");
        boolean sourceFound = false;
        String copyId = null;
        for (int index = 0; index < values.length(); index++) {
            JSONObject stroke = values.getJSONObject(index);
            String id = stroke.getString("id");
            JSONObject point = stroke.getJSONArray("points").getJSONObject(0);
            if (id.equals(sourceId)) {
                sourceFound = true;
                assertExactDouble(point.getDouble("x"), expectedX);
                assertExactDouble(point.getDouble("y"), 100d);
                continue;
            }
            if (Double.doubleToRawLongBits(point.getDouble("x"))
                    != Double.doubleToRawLongBits(expectedX)
                    || Double.doubleToRawLongBits(point.getDouble("y"))
                    != Double.doubleToRawLongBits(expectedY)) continue;
            if (copyId != null) throw new AssertionError("multiple copied strokes match source");
            if (id.equals("before-boundary") || id.equals("page-one") || id.equals("page-two")) {
                throw new AssertionError("translated stroke retained source identity");
            }
            if (!java.util.UUID.fromString(id).toString().equals(id)) {
                throw new AssertionError("copied stroke identity is not a canonical UUID");
            }
            copyId = id;
        }
        if (!sourceFound || copyId == null || copyId.equals(sourceId)) {
            throw new AssertionError("page duplicate with exact translated coordinates not found");
        }
        return copyId;
    }

    private static void assertCopiedPointY(NoteCanvasView canvas, double expected) throws Exception {
        JSONArray values = canvas.toJsonDocument("topology", "topology")
                .getJSONArray("strokes");
        int copies = 0;
        for (int index = 0; index < values.length(); index++) {
            JSONObject stroke = values.getJSONObject(index);
            String id = stroke.getString("id");
            if (id.equals("page-zero") || id.equals("before-boundary")
                    || id.equals("page-one") || id.equals("page-two")) continue;
            double actual = stroke.getJSONArray("points").getJSONObject(0).getDouble("y");
            if (Double.doubleToRawLongBits(actual) != Double.doubleToRawLongBits(expected)) continue;
            if (!java.util.UUID.fromString(id).toString().equals(id)) {
                throw new AssertionError("copied boundary stroke identity is not a canonical UUID");
            }
            copies += 1;
        }
        if (copies != 1) throw new AssertionError("expected one exact copied boundary stroke, found "
                + copies);
    }

    private static void assertPersistedCopiedStroke(JSONObject document, String id,
                                                     double expectedX, double expectedY)
            throws Exception {
        JSONArray values = document.getJSONArray("strokes");
        for (int index = 0; index < values.length(); index++) {
            JSONObject stroke = values.getJSONObject(index);
            if (!stroke.getString("id").equals(id)) continue;
            JSONObject point = stroke.getJSONArray("points").getJSONObject(0);
            assertExactDouble(point.getDouble("x"), expectedX);
            assertExactDouble(point.getDouble("y"), expectedY);
            return;
        }
        throw new AssertionError("persisted copied stroke not found: " + id);
    }

    private static void assertPersistedStroke(JSONObject document, String idPrefix, double expected)
            throws Exception {
        JSONArray values = document.getJSONArray("strokes");
        for (int index = 0; index < values.length(); index++) {
            JSONObject stroke = values.getJSONObject(index);
            if (stroke.getString("id").startsWith(idPrefix)) {
                JSONObject point = stroke.getJSONArray("points").getJSONObject(0);
                assertExactDouble(point.getDouble("y"), expected);
                assertExactDouble(point.getDouble("x"), -0.0d);
                return;
            }
        }
        throw new AssertionError("persisted stroke not found: " + idPrefix);
    }

    private static void assertPointY(NoteCanvasView canvas, String idPrefix, double expected)
            throws Exception {
        JSONArray values = canvas.toJsonDocument("topology", "topology")
                .getJSONArray("strokes");
        for (int index = 0; index < values.length(); index++) {
            JSONObject stroke = values.getJSONObject(index);
            if (stroke.getString("id").startsWith(idPrefix)) {
                assertExactDouble(stroke.getJSONArray("points").getJSONObject(0).getDouble("y"),
                        expected);
                return;
            }
        }
        throw new AssertionError("stroke not found: " + idPrefix);
    }

    private static JSONObject document(String noteId) throws Exception {
        JSONArray strokes = new JSONArray()
                .put(stroke("legacy-large-integer", LARGE_EXACT_INTEGER,
                        new JSONObject().put("x", 21.1234567890123d)
                                .put("y", 31.9876543210987d)
                                .put("timestamp", LARGE_EXACT_INTEGER)
                                .put("pressure", 0.625d)))
                .put(stroke("fractional-ipad-time", 456.125d,
                        new JSONObject().put("x", 41.0000000000001d)
                                .put("y", 51.0000000000002d)
                                .put("timestamp", 123.25d)
                                .put("pressure", 0.375d)))
                .put(stroke("ordinary-long-time", 1725123456789L,
                        new JSONObject().put("x", 61.25d).put("y", 71.75d)
                                .put("timestamp", 1725123456790L).put("pressure", 0.5d)))
                .put(stroke("sub-float-increments", 1725123456791L,
                        new JSONObject().put("x", 768.00001d).put("y", Math.nextUp(768.00001d))
                                .put("timestamp", 1725123456792L).put("pressure", 0.5d)))
                .put(stroke("negative-zero", 1725123456793L,
                        new JSONObject().put("x", -0.0d).put("y", -0.0d)
                                .put("timestamp", 1725123456794L).put("pressure", 0.5d)));
        JSONObject flow = new JSONObject().put("id", "flow-binary64")
                .put("format", "markdown").put("source", "preserved")
                .put("fontSizeSp", 16.1234567890123d).put("lineHeight", 1.234567890123d)
                .put("width", 320.1234567890123d).put("anchorPageIndex", 0)
                .put("anchorXInPage", 12.1234567890123d)
                .put("anchorYInPage", 80.9876543210987d);
        JSONObject image = new JSONObject().put("id", "image-binary64")
                .put("png", onePixelPng()).put("page", 0)
                .put("x", 12.1234567890123d).put("y", 22.2345678901234d)
                .put("width", 1.125d).put("height", 1.875d);
        return new JSONObject().put("schemaVersion", 8).put("id", noteId)
                .put("title", "binary64 round trip")
                .put("pageWidth", 900.1234567890123d)
                .put("pageHeight", 1040.9876543210987d)
                .put("pageGap", 24.1234567890123d).put("pageCount", 1)
                .put("viewportScale", 1.125d).put("viewportCenterX", 123.1234567890123d)
                .put("viewportCenterY", 456.9876543210987d)
                .put("strokes", strokes).put("textFlows", new JSONArray().put(flow))
                .put("textBoxes", new JSONArray()).put("images", new JSONArray().put(image));
    }

    private static JSONObject stroke(String id, Number createdAt, JSONObject point) throws Exception {
        return new JSONObject().put("id", id).put("color", "#FF17212B")
                .put("baseWidth", 2.1234567890123d).put("createdAt", createdAt)
                .put("points", new JSONArray().put(point));
    }

    private static String onePixelPng() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("PNG encoding failed");
            }
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } finally {
            bitmap.recycle();
        }
    }

    private static void exerciseCanonicalCopiesAndMovement(JSONObject edited) throws Exception {
        InkPoint horizontalOnly = new InkPoint(2.25d, -0.0d, 1725123456799L, 0.5d);
        horizontalOnly.translate(0.125d, 0d);
        assertExactDouble(horizontalOnly.x64, 2.375d);
        assertExactDouble(horizontalOnly.y64, -0.0d);

        JSONObject originalStroke = edited.getJSONArray("strokes").getJSONObject(0);
        InkStroke copiedStroke = InkStroke.fromJson(originalStroke).copyWithId("stroke-copy-binary64");
        copiedStroke.translate(0.25f, 0.5f);
        JSONObject copiedPoint = copiedStroke.toJson().getJSONArray("points").getJSONObject(0);
        assertExactLong(copiedPoint.get("timestamp"), LARGE_EXACT_INTEGER);
        assertExactDouble(copiedPoint.getDouble("x"), 21.1234567890123d + 0.25d);
        assertExactDouble(copiedPoint.getDouble("y"), 31.9876543210987d + 0.5d);
        edited.getJSONArray("strokes").put(copiedStroke.toJson());

        JSONObject imageJson = edited.getJSONArray("images").getJSONObject(0);
        NoteImage decodedImage = NoteImage.fromJson(imageJson);
        NoteImage copiedImage = decodedImage.copy(true);
        copiedImage.setGeometry(copiedImage.x64 + 0.125d, copiedImage.y64 + 0.25d,
                copiedImage.width64, copiedImage.height64);
        assertExactDouble(copiedImage.toJson().getDouble("x"), 12.1234567890123d + 0.125d);
        assertExactDouble(decodedImage.toJson().getDouble("x"), 12.1234567890123d);
        decodedImage.bitmap.recycle();

        TextFlow flow = TextFlow.fromJson(edited.getJSONArray("textFlows").getJSONObject(0));
        TextFlow flowCopy = flow.copy();
        flowCopy.setAnchor(flowCopy.anchorXInPage64 + 0.125d, flowCopy.anchorYInPage64);
        assertExactDouble(flow.toJson().getDouble("anchorXInPage"), 12.1234567890123d);
        assertExactDouble(flowCopy.toJson().getDouble("anchorXInPage"),
                12.1234567890123d + 0.125d);
    }

    private static void assertPersistedValues(JSONObject document) throws Exception {
        JSONArray strokes = document.getJSONArray("strokes");
        JSONObject large = strokes.getJSONObject(0);
        assertExactLong(large.get("createdAt"), LARGE_EXACT_INTEGER);
        assertExactDouble(large.getDouble("baseWidth"), 2.1234567890123d);
        JSONObject largePoint = large.getJSONArray("points").getJSONObject(0);
        assertExactLong(largePoint.get("timestamp"), LARGE_EXACT_INTEGER);
        assertExactDouble(largePoint.getDouble("x"), 21.1234567890123d);
        assertExactDouble(largePoint.getDouble("y"), 31.9876543210987d);
        assertExactDouble(largePoint.getDouble("pressure"), 0.625d);

        JSONObject fractional = strokes.getJSONObject(1);
        assertEquals(456.125d, fractional.getDouble("createdAt"), 0d);
        assertEquals(123.25d, fractional.getJSONArray("points")
                .getJSONObject(0).getDouble("timestamp"), 0d);
        assertExactLong(strokes.getJSONObject(2).get("createdAt"), 1725123456789L);

        assertExactDouble(document.getDouble("pageWidth"), 900.1234567890123d);
        assertExactDouble(document.getDouble("pageHeight"), 1040.9876543210987d);
        assertExactDouble(document.getDouble("pageGap"), 24.1234567890123d);
        JSONObject flow = document.getJSONArray("textFlows").getJSONObject(0);
        assertExactDouble(flow.getDouble("fontSizeSp"), 16.1234567890123d);
        assertExactDouble(flow.getDouble("lineHeight"), 1.234567890123d);
        assertExactDouble(flow.getDouble("width"), 320.1234567890123d);
        assertExactDouble(flow.getDouble("anchorXInPage"), 12.1234567890123d);
        assertExactDouble(flow.getDouble("anchorYInPage"), 80.9876543210987d);
        JSONObject image = document.getJSONArray("images").getJSONObject(0);
        assertExactDouble(image.getDouble("x"), 12.1234567890123d);
        assertExactDouble(image.getDouble("y"), 22.2345678901234d);
        assertExactDouble(image.getDouble("width"), 1.125d);
        assertExactDouble(image.getDouble("height"), 1.875d);
        JSONObject subFloat = strokes.getJSONObject(3).getJSONArray("points").getJSONObject(0);
        assertExactDouble(subFloat.getDouble("x"), 768.00001d);
        assertExactDouble(subFloat.getDouble("y"), Math.nextUp(768.00001d));
        JSONObject negativeZero = strokes.getJSONObject(4).getJSONArray("points").getJSONObject(0);
        assertExactDouble(negativeZero.getDouble("x"), -0.0d);
        assertExactDouble(negativeZero.getDouble("y"), -0.0d);
        JSONObject copiedPoint = strokes.getJSONObject(5).getJSONArray("points").getJSONObject(0);
        assertExactLong(copiedPoint.get("timestamp"), LARGE_EXACT_INTEGER);
    }

    private static void deleteOwnedTree(File root) {
        if (root == null || !root.getName().startsWith("binary64-ai-")) return;
        deleteTreeContents(root);
    }

    private static void deleteTreeContents(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTreeContents(child);
        if (file.exists()) file.delete();
    }

    private static void assertExactLong(Object actual, long expected) {
        if (!(actual instanceof Byte || actual instanceof Short || actual instanceof Integer
                || actual instanceof Long)) throw new AssertionError("timestamp is not an integer token");
        assertEquals(expected, ((Number) actual).longValue());
    }

    private static void assertExactDouble(double actual, double expected) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    private interface UiAction { void run() throws Exception; }

    private static void runOnMain(UiAction action) throws Exception {
        Throwable[] failure = new Throwable[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] instanceof Exception) throw (Exception) failure[0];
        if (failure[0] instanceof Error) throw (Error) failure[0];
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
}
