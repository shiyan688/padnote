package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Exercises the tool layer without a device or an API key.
 *
 * <p>End-to-end behaviour with a real model cannot be verified in this
 * environment, so these tests pin down the parts that are checkable: that every
 * tool publishes a usable schema, that permission gating actually refuses, that
 * an unfitting placement comes back as a rejection carrying alternatives rather
 * than an exception, and that unregistered names never execute.
 */
public class NoteToolRegistryTest {

    @Test
    public void everyToolPublishesAUsableSchema() throws JSONException {
        JSONArray described = NoteTools.createDefault().describe();
        assertEquals(5, described.length());
        for (int index = 0; index < described.length(); index++) {
            JSONObject tool = described.getJSONObject(index);
            assertFalse("tool needs a name", tool.optString("name").isEmpty());
            assertFalse("tool needs a description for the model",
                    tool.optString("description").isEmpty());
            JSONObject parameters = tool.optJSONObject("parameters");
            assertNotNull("tool needs a parameter schema", parameters);
            assertEquals("object", parameters.optString("type"));
            assertNotNull("schema needs properties", parameters.optJSONObject("properties"));
        }
    }

    /** The model must not be able to name a tool that was never registered. */
    @Test
    public void unregisteredToolIsRefused() {
        NoteTool.Result result = NoteTools.createDefault().invoke("delete_everything",
                new JSONObject(), new FakeContext(), NoteTool.Permission.MODIFY_EXISTING);
        assertFalse(result.ok);
        assertTrue(result.summary.contains("未注册"));
    }

    /** Read-only authority must not permit writing to the page. */
    @Test
    public void writingRequiresWritePermission() throws JSONException {
        JSONObject arguments = new JSONObject()
                .put("content", "测试内容")
                .put("placement", new JSONObject().put("relativeTo", "selection"));
        NoteTool.Result result = NoteTools.createDefault().invoke("write_text", arguments,
                new FakeContext(), NoteTool.Permission.READ_ONLY);
        assertFalse("read-only must not write", result.ok);
    }

    /** Reading is always allowed, even with no explicit grant. */
    @Test
    public void readingIsAllowedWithoutGrant() {
        NoteTool.Result result = NoteTools.createDefault().invoke("read_page_map",
                new JSONObject(), new FakeContext(), null);
        assertTrue(result.ok);
        assertNotNull(result.payload);
    }

    /**
     * An unresolvable placement is a refusal the model can act on, not a crash.
     * This is the behaviour that lets the engine own "does it fit" while the model
     * still chooses what to do about it.
     */
    @Test
    public void unresolvablePlacementBecomesActionableRejection() throws JSONException {
        FakeContext context = new FakeContext();
        context.failPlacement = true;
        JSONObject arguments = new JSONObject()
                .put("content", "测试内容")
                .put("placement", new JSONObject().put("relativeTo", "ink-does-not-exist"));
        NoteTool.Result result = NoteTools.createDefault().invoke("write_text", arguments,
                context, NoteTool.Permission.CREATE_IN_FREE_SPACE);
        assertFalse(result.ok);
        assertEquals("placement_unresolvable", result.payload.optString("error"));
        assertFalse("rejection should suggest a way forward",
                result.payload.optString("hint").isEmpty());
    }

    @Test
    public void emptyContentIsRejectedBeforeTouchingTheDocument() throws JSONException {
        FakeContext context = new FakeContext();
        JSONObject arguments = new JSONObject()
                .put("content", "   ")
                .put("placement", new JSONObject().put("relativeTo", "selection"));
        NoteTool.Result result = NoteTools.createDefault().invoke("write_text", arguments,
                context, NoteTool.Permission.CREATE_IN_FREE_SPACE);
        assertFalse(result.ok);
        assertEquals("no write should have been attempted", 0, context.createCalls);
    }

    /** A successful write reports where it landed, so the model need not predict. */
    @Test
    public void successfulWriteReportsLanding() throws JSONException {
        FakeContext context = new FakeContext();
        JSONObject arguments = new JSONObject()
                .put("content", "# 标题\n- 一条\n- 两条")
                .put("placement", new JSONObject()
                        .put("relativeTo", "selection")
                        .put("position", "below"));
        NoteTool.Result result = NoteTools.createDefault().invoke("write_text", arguments,
                context, NoteTool.Permission.CREATE_IN_FREE_SPACE);
        assertTrue(result.summary, result.ok);
        assertTrue("a write must be flagged as mutating", result.mutatedDocument);
        assertNotNull(result.payload.optJSONArray("occupiedPages"));
        assertEquals(1, context.createCalls);
    }

    @Test
    public void diagramCreatesEditableMarkdownWithWritePermission() throws JSONException {
        FakeContext context = new FakeContext();
        JSONObject args = new JSONObject().put("code", "flowchart LR\nA[输入] --> B[输出]")
                .put("placement", new JSONObject().put("relativeTo", "selection"));
        NoteToolRegistry registry = NoteTools.createDefault();
        assertFalse(registry.invoke("draw_diagram", args, context, NoteTool.Permission.READ_ONLY).ok);
        assertEquals(0, context.createCalls);
        NoteTool.Result result = registry.invoke("draw_diagram", args, context,
                NoteTool.Permission.CREATE_IN_FREE_SPACE);
        assertTrue(result.summary, result.ok);
        assertTrue(result.mutatedDocument);
        assertEquals("```mermaid\nflowchart LR\nA[输入] --> B[输出]\n```", context.lastContent);
        assertEquals(NoteTextBox.Format.MARKDOWN, context.lastFormat);
    }

    @Test
    public void diagramRejectsUnsupportedCodeWithoutWriting() throws JSONException {
        FakeContext context = new FakeContext();
        for (String code : new String[]{"", "alert(1)", "flowchart LR\n%%{init: {}}%%\nA-->B",
                "flowchart LR\nA[<script>bad</script>]", "flowchart LR\nA-->B\nclick A href something"}) {
            NoteTool.Result result = NoteTools.createDefault().invoke("draw_diagram",
                    new JSONObject().put("code", code), context, NoteTool.Permission.CREATE_IN_FREE_SPACE);
            assertFalse(code, result.ok);
        }
        assertEquals(0, context.createCalls);
    }

    /** Restyling touches content the user made, so it needs the higher grant. */
    @Test
    public void restylingRequiresModifyPermission() throws JSONException {
        JSONObject arguments = new JSONObject().put("flowId", "flow-1");
        NoteToolRegistry registry = NoteTools.createDefault();
        assertFalse(registry.invoke("set_text_flow_style", arguments, new FakeContext(),
                NoteTool.Permission.CREATE_IN_FREE_SPACE).ok);
        assertTrue(registry.invoke("set_text_flow_style", arguments, new FakeContext(),
                NoteTool.Permission.MODIFY_EXISTING).ok);
    }

    /** No tool may reach handwriting: v1 registers nothing that can move ink. */
    @Test
    public void noToolCanModifyHandwriting() throws JSONException {
        JSONArray described = NoteTools.createDefault().describe();
        for (int index = 0; index < described.length(); index++) {
            String name = described.getJSONObject(index).optString("name");
            assertFalse("v1 must not expose ink mutation: " + name,
                    name.contains("ink") || name.contains("stroke"));
        }
    }

    /**
     * Test double for the canvas. Deliberately avoids android.graphics so these
     * run on the JVM; placement resolution is stubbed because the real resolver
     * needs live page geometry. Package-private so the vault tool tests reuse it.
     */
    static final class FakeContext implements NoteToolContext {
        boolean failPlacement;
        int createCalls;
        String lastContent;
        NoteTextBox.Format lastFormat;

        @Override
        public JSONObject readPageMap(int focusPageIndex, boolean fullDetail) {
            try {
                return new JSONObject().put("pageCount", 2).put("bandsPerPage",
                        PageMap.BAND_COUNT);
            } catch (JSONException failure) {
                return new JSONObject();
            }
        }

        @Override
        public PlacementResolver.Placement resolvePlacement(JSONObject placement) {
            if (failPlacement) {
                throw new PlacementResolver.Failure("找不到锚点：ink-does-not-exist");
            }
            return new PlacementResolver.Placement(0, 16f, 120f, 360f, "选区下方");
        }

        @Override
        public int selectionPageIndex() {
            return 0;
        }

        @Override
        public android.graphics.RectF selectionBounds() {
            return null;
        }

        @Override
        public int pageCount() {
            return 2;
        }

        @Override
        public JSONObject createTextFlow(String content, NoteTextBox.Format format,
                                         PlacementResolver.Placement placement) {
            createCalls += 1;
            lastContent = content;
            lastFormat = format;
            try {
                return new JSONObject()
                        .put("flowId", "flow-test")
                        .put("occupiedPages", new JSONArray().put(1))
                        .put("fragmentCount", 1)
                        .put("addedPage", false)
                        .put("shiftedFlows", new JSONArray());
            } catch (JSONException failure) {
                return new JSONObject();
            }
        }

        @Override
        public JSONObject styleTextFlow(String flowId, Float fontSizeSp, Float lineHeight,
                                        Float width) {
            return new JSONObject();
        }

        @Override
        public JSONObject moveTextFlow(String flowId,
                                       PlacementResolver.Placement placement) {
            return new JSONObject();
        }

        @Override
        public float measureContentHeight(String content, NoteTextBox.Format format,
                                          float widthDp, float fontSizeSp,
                                          float lineHeight) {
            return 120f;
        }

        @Override
        public float pageWidthDp() {
            return 360f;
        }

        @Override
        public float pageHeightDp() {
            return 640f;
        }
    }
}
