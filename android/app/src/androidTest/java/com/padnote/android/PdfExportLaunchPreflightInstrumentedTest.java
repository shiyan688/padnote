package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class PdfExportLaunchPreflightInstrumentedTest {
    @Test public void incompleteLayoutDoesNotLaunchPickerAndReadyCanvasCanLaunch() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicInteger pickerLaunches = new AtomicInteger();
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicReference<Throwable> testFailure = new AtomicReference<>();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView unready = new NoteCanvasView(context);
            try {
                unready.layout(0, 0, 800, 1000);
                unready.loadJsonDocument(documentWithFlow());
                TextFlow flow = textFlow(unready, "preflight-flow");
                flow.lastLayoutComplete = false;

                boolean launched = MainActivity.launchPdfDocumentAfterPreflight(unready,
                        pickerLaunches::incrementAndGet, failure::set);
                assertFalse("an incomplete flow must not start SAF", launched);
                assertEquals("no destination picker may be created on preflight failure", 0,
                        pickerLaunches.get());
                assertNotNull("incomplete layout must report the preparation failure", failure.get());
            } catch (Throwable error) {
                testFailure.set(error);
            } finally {
                unready.onDetachedFromWindow();
            }

            NoteCanvasView ready = new NoteCanvasView(context);
            try {
                ready.layout(0, 0, 800, 1000);
                ready.loadJsonDocument(emptyDocument());
                failure.set(null);
                boolean launched = MainActivity.launchPdfDocumentAfterPreflight(ready,
                        pickerLaunches::incrementAndGet, failure::set);
                assertTrue("a ready canvas should proceed to the picker callback", launched);
                assertEquals("ready preflight invokes the picker exactly once", 1,
                        pickerLaunches.get());
                assertNull("ready preflight must not report an error", failure.get());
            } catch (Throwable error) {
                testFailure.compareAndSet(null, error);
            } finally {
                ready.onDetachedFromWindow();
            }
        });

        if (testFailure.get() != null) throw new AssertionError(testFailure.get());
    }

    private static TextFlow textFlow(NoteCanvasView canvas, String id) throws Exception {
        java.lang.reflect.Field field = NoteCanvasView.class.getDeclaredField("textFlows");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, TextFlow> flows = (java.util.Map<String, TextFlow>) field.get(canvas);
        TextFlow flow = flows.get(id);
        if (flow == null) throw new AssertionError("synthetic text flow did not load");
        return flow;
    }

    private static JSONObject documentWithFlow() throws Exception {
        JSONObject document = emptyDocument();
        document.getJSONArray("textFlows").put(new TextFlow("preflight-flow",
                NoteTextBox.Format.MARKDOWN, "Synthetic preflight text", 16d, 1.35d,
                500d, 0, 40d, 40d).toJson());
        return document;
    }

    private static JSONObject emptyDocument() throws Exception {
        return new JSONObject().put("schemaVersion", 8)
                .put("id", "synthetic-pdf-preflight")
                .put("title", "Synthetic PDF preflight")
                .put("pageWidth", 600).put("pageHeight", 800).put("pageGap", 24)
                .put("pageCount", 1).put("strokes", new JSONArray())
                .put("images", new JSONArray()).put("textBoxes", new JSONArray())
                .put("textFlows", new JSONArray());
    }
}
