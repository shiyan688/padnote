package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NoteDocumentRecoveryTest {
    @Test public void recoveryExportRecapturesEditsMadeAfterOlderSaveSnapshot() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(context);
            try {
                canvas.layout(0, 0, 800, 1000);
                canvas.loadJsonDocument(document("initial-stroke"));
                String olderSavingRevision = MainActivity.captureRecoveryExportJson(
                        canvas, "note-latest-recovery", "最新恢复测试");
                canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                        "EDIT_AFTER_OLD_SAVE_FAILED", 80, 220);

                String exportedAfterFailure = MainActivity.captureRecoveryExportJson(
                        canvas, "note-latest-recovery", "最新恢复测试");
                assertEquals(0, new JSONObject(olderSavingRevision)
                        .getJSONArray("textFlows").length());
                assertEquals("EDIT_AFTER_OLD_SAVE_FAILED", new JSONObject(exportedAfterFailure)
                        .getJSONArray("textFlows").getJSONObject(0).getString("source"));
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
    }

    @Test public void lateParseFailureDoesNotReplaceLiveDocument() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(context);
            try {
                canvas.layout(0, 0, 800, 1000);
                JSONObject original = document("kept-stroke");
                canvas.loadJsonDocument(original);
                JSONObject before = canvas.toJsonDocument("note-transaction", "事务测试");

                JSONObject broken = document("partial-stroke");
                broken.getJSONArray("textFlows").put(new JSONObject()
                        .put("id", "")
                        .put("format", "markdown")
                        .put("source", "must fail after strokes")
                        .put("width", 300)
                        .put("anchorPageIndex", 0));
                try {
                    canvas.loadJsonDocument(broken);
                    fail("late invalid flow must reject the whole document");
                } catch (Exception expected) {
                    // Expected: no live collection was changed.
                }

                JSONObject after = canvas.toJsonDocument("note-transaction", "事务测试");
                assertEquals(before.getJSONArray("strokes").toString(),
                        after.getJSONArray("strokes").toString());
                assertEquals(before.getJSONArray("textFlows").toString(),
                        after.getJSONArray("textFlows").toString());
                assertEquals(1, canvas.getStrokeCount());
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
    }

    private static JSONObject document(String strokeId) throws Exception {
        JSONObject stroke = new JSONObject()
                .put("id", strokeId)
                .put("color", "#FF17212B")
                .put("baseWidth", 2)
                .put("createdAt", 1)
                .put("points", new JSONArray().put(new JSONObject()
                        .put("x", 10).put("y", 20).put("timestamp", 1).put("pressure", 0.5)));
        return new JSONObject()
                .put("schemaVersion", 8)
                .put("id", "note-transaction")
                .put("title", "事务测试")
                .put("pageWidth", 736)
                .put("pageHeight", 1040)
                .put("pageGap", 24)
                .put("pageCount", 1)
                .put("strokes", new JSONArray().put(stroke))
                .put("textFlows", new JSONArray())
                .put("textBoxes", new JSONArray())
                .put("images", new JSONArray());
    }
}
