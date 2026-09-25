package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Loads the constructed schema 1-8 fixtures through the production migration path. */
@RunWith(AndroidJUnit4.class)
public final class LegacyNoteCompatibilityTest {
    private static final String ASSET_ROOT = "legacy-notes/";

    @Test public void schemasOneThroughEightMigrateAndRoundTripTheirDistinctStructures()
            throws Exception {
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        JSONObject v1 = loadAndRoundTrip(testContext, targetContext, "schema1.json");
        assertCommon(v1, 1, "stroke-v1");
        assertEquals("ruled", v1.getJSONObject("pageStyle").getString("paper"));
        assertEquals(600d, v1.getDouble("pageWidth"), 0.01d);
        assertEquals(800d, v1.getDouble("pageHeight"), 0.01d);

        JSONObject v2 = loadAndRoundTrip(testContext, targetContext, "schema2.json");
        assertCommon(v2, 2, "stroke-v2");
        assertEquals(900d, v2.getJSONArray("strokes").getJSONObject(0)
                .getJSONArray("points").getJSONObject(0).getDouble("y"), 0.01d);

        JSONObject v3 = loadAndRoundTrip(testContext, targetContext, "schema3.json");
        assertCommon(v3, 2, "stroke-v3");
        JSONObject flow3 = onlyFlow(v3);
        assertEquals("legacy-box-v3", flow3.getString("id"));
        assertEquals("x_3^2 + y_3^2 = 1", flow3.getString("source"));
        assertEquals(0, flow3.getInt("anchorPageIndex"));
        assertEquals(48d, flow3.getDouble("anchorXInPage"), 0.01d);
        assertEquals(132d, flow3.getDouble("anchorYInPage"), 0.01d);

        JSONObject v4 = loadAndRoundTrip(testContext, targetContext, "schema4.json");
        assertCommon(v4, 3, "stroke-v4");
        JSONObject flow4 = onlyFlow(v4);
        assertEquals("legacy-flow-v4", flow4.getString("id"));
        assertTrue(flow4.getString("source").contains("第二页片段仍重复完整源码"));
        assertEquals(1, flow4.getInt("anchorPageIndex"));
        assertEquals(44d, flow4.getDouble("anchorXInPage"), 0.01d);
        assertEquals(100d, flow4.getDouble("anchorYInPage"), 0.01d);

        JSONObject v5 = loadAndRoundTrip(testContext, targetContext, "schema5.json");
        assertCommon(v5, 3, "stroke-v5");
        JSONObject flow5 = onlyFlow(v5);
        assertEquals("flow-v5", flow5.getString("id"));
        assertEquals(TextFlow.DEFAULT_LINE_HEIGHT, flow5.getDouble("lineHeight"), 0.001d);
        assertEquals(1, flow5.getInt("anchorPageIndex"));
        assertEquals(55d, flow5.getDouble("anchorXInPage"), 0.01d);
        assertEquals(85d, flow5.getDouble("anchorYInPage"), 0.01d);

        JSONObject v6 = loadAndRoundTrip(testContext, targetContext, "schema6.json");
        assertCommon(v6, 2, "stroke-v6");
        JSONObject style6 = v6.getJSONObject("pageStyle");
        assertEquals("grid", style6.getString("paper"));
        assertEquals("a4", style6.getString("ratio"));
        assertTrue(style6.getBoolean("landscape"));
        assertEquals("\\frac{6}{2}=3", onlyFlow(v6).getString("source"));

        JSONObject source7 = readJson(testContext, "schema7.json");
        File pdf7 = NoteStore.pdfFile(targetContext, source7.getString("id"));
        copyAsset(testContext, "schema7-source.pdf", pdf7);
        try {
            JSONObject v7 = loadAndRoundTrip(source7, targetContext);
            assertCommon(v7, 2, "stroke-v7");
            assertEquals(1, v7.getInt("pdfPageCount"));
            assertEquals(1, onlyFlow(v7).getInt("anchorPageIndex"));
            assertEquals("SCHEMA7_PDF_ANNOTATION", onlyFlow(v7).getString("source"));
        } finally {
            assertTrue("schema 7 test PDF cleanup failed", !pdf7.exists() || pdf7.delete());
        }

        JSONObject v8 = loadAndRoundTrip(testContext, targetContext, "schema8.json");
        assertCommon(v8, 2, "stroke-v8");
        assertEquals("dotted", v8.getJSONObject("pageStyle").getString("paper"));
        JSONArray images = v8.getJSONArray("images");
        assertEquals(1, images.length());
        JSONObject image = images.getJSONObject(0);
        assertEquals("image-v8", image.getString("id"));
        assertEquals(0, image.getInt("page"));
        assertEquals(420d, image.getDouble("x"), 0.01d);
        assertEquals(620d, image.getDouble("y"), 0.01d);
        assertFalse(image.getString("png").isEmpty());
        assertEquals(1, v8.getJSONArray("textFlows").getJSONObject(0)
                .getInt("anchorPageIndex"));
    }

    @Test public void corruptTailRejectsWholeLoadAndKeepsPreviousLiveDocument() throws Exception {
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject valid = readJson(testContext, "schema5.json");
        JSONObject corrupt = readJson(testContext, "schema8-corrupt-tail.json");

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(targetContext);
            try {
                canvas.layout(0, 0, 800, 1000);
                canvas.loadJsonDocument(valid);
                JSONObject before = canvas.toJsonDocument("fixture-before", "before");
                try {
                    canvas.loadJsonDocument(corrupt);
                    fail("invalid tail flow must reject the complete document");
                } catch (Exception expected) {
                    // The failure is the assertion target; compare the live document below.
                }
                JSONObject after = canvas.toJsonDocument("fixture-after", "after");
                assertEquals(before.getJSONArray("strokes").toString(),
                        after.getJSONArray("strokes").toString());
                assertEquals(before.getJSONArray("textFlows").toString(),
                        after.getJSONArray("textFlows").toString());
                assertEquals(before.getInt("pageCount"), after.getInt("pageCount"));
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
    }

    private static JSONObject loadAndRoundTrip(Context testContext, Context targetContext,
                                               String assetName) throws Exception {
        return loadAndRoundTrip(readJson(testContext, assetName), targetContext);
    }

    private static JSONObject loadAndRoundTrip(JSONObject input, Context targetContext)
            throws Exception {
        JSONObject[] result = new JSONObject[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(targetContext);
            try {
                canvas.layout(0, 0, 800, 1000);
                canvas.loadJsonDocument(input);
                assertEquals(input.getInt("pageCount"), canvas.getPageCount());
                List<TextFlow> flows = canvas.getTextFlows();
                result[0] = canvas.toJsonDocument(input.getString("id"),
                        input.getString("title"));
                assertEquals(flows.size(), result[0].getJSONArray("textFlows").length());
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
        return result[0];
    }

    private static void assertCommon(JSONObject migrated, int pageCount, String strokeId)
            throws Exception {
        assertEquals(8, migrated.getInt("schemaVersion"));
        assertEquals(pageCount, migrated.getInt("pageCount"));
        assertEquals(strokeId,
                migrated.getJSONArray("strokes").getJSONObject(0).getString("id"));
        assertEquals(0, migrated.getJSONArray("textBoxes").length());
    }

    private static JSONObject onlyFlow(JSONObject document) throws Exception {
        JSONArray flows = document.getJSONArray("textFlows");
        assertEquals(1, flows.length());
        return flows.getJSONObject(0);
    }

    private static JSONObject readJson(Context testContext, String name) throws Exception {
        try (InputStream input = testContext.getAssets().open(ASSET_ROOT + name)) {
            return new JSONObject(new String(readFully(input), StandardCharsets.UTF_8));
        }
    }

    private static void copyAsset(Context testContext, String name, File destination)
            throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("cannot create fixture PDF directory");
        }
        try (InputStream input = testContext.getAssets().open(ASSET_ROOT + name);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
    }

    private static byte[] readFully(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
