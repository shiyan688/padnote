package com.padnote.android;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MarkdownTypographyProfileTest {
    @Test public void profileTwoInlineAstMatchesSharedGoldenFixture() throws Exception {
        String fixture;
        try (InputStream input = getClass().getResourceAsStream("/com/padnote/android/TYPOGRAPHY_CASES_R1.json")) {
            assertNotNull("shared fixture must be copied unchanged", input);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) >= 0;) bytes.write(buffer, 0, count);
            fixture = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        JSONObject root = new JSONObject(fixture);
        assertEquals("padnote.typography-cases.r1", root.getString("schema"));
        JSONArray cases = root.getJSONArray("cases");
        int checked = 0;
        for (int i = 0; i < cases.length(); i++) {
            JSONObject item = cases.getJSONObject(i);
            if (item.optInt("profile") != 2) continue;
            JSONArray blocks = item.getJSONObject("expected").optJSONArray("blocks");
            if (blocks == null || blocks.length() != 1) continue;
            JSONObject block = blocks.optJSONObject(0);
            if (block == null || !"paragraph".equals(block.optString("type"))) continue;
            JSONArray expected = block.optJSONArray("children");
            if (expected == null) continue;
            assertJsonEquivalent(expected, astJson(
                    MarkdownV2Parser.parseInline(item.getString("markdown"))));
            checked++;
        }
        assertTrue("golden fixture should exercise several inline constructs", checked >= 5);
    }

    @Test public void strongScopeIncludesActualMathGlyphRendering() {
        String html = MarkdownV2Parser.inlineToHtml("**before $x^2$ after**");
        assertTrue(html.startsWith("<strong class=\"md-strong\">before "));
        assertTrue(html.contains("class=\"math-inline\" data-display=\"0\" data-tex=\"x^2\" data-bold=\"1\""));
        String renderer = CompiledTextWebView.buildHtml(NoteTextBox.Format.MARKDOWN,
                "**before $x^2$ after**", 16f, 1.35f, 2);
        assertTrue(renderer.contains("data-bold") || renderer.contains("getAttribute('data-bold')==='1'"));
        assertTrue(renderer.contains("\\\\boldsymbol{"));
    }

    @Test public void profileTwoDelimitersMatchWholeRunsAndKeepNestedScopes() throws Exception {
        String fixture;
        try (InputStream input = getClass().getResourceAsStream("/com/padnote/android/TYPOGRAPHY_CASES_R1.json")) {
            assertNotNull(input);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) >= 0;) bytes.write(buffer, 0, count);
            fixture = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        JSONArray cases = new JSONObject(fixture).getJSONArray("cases");
        int checked = 0;
        for (int i = 0; i < cases.length(); i++) {
            JSONObject item = cases.getJSONObject(i);
            String id = item.optString("id");
            if (!id.startsWith("nested-emphasis-math-") &&
                    !id.equals("triple-delimiter-strong-and-emphasis-around-math") &&
                    !id.startsWith("strong-crosses-tex-")) continue;
            JSONObject paragraph = item.getJSONObject("expected").getJSONArray("blocks").getJSONObject(0);
            assertJsonEquivalent(paragraph.getJSONArray("children"),
                    astJson(MarkdownV2Parser.parseInline(item.getString("markdown"))));
            checked++;
        }
        assertEquals("all newly shared nested/triple/TeX fixtures must be consumed", 5, checked);
    }

    @Test public void profileOneKeepsLegacyPipelineAndProfileTwoAddsSemanticRule() {
        String legacy = CompiledTextWebView.buildHtml(NoteTextBox.Format.MARKDOWN,
                "above\n\n---", 16f, 1.35f, 1);
        String updated = CompiledTextWebView.buildHtml(NoteTextBox.Format.MARKDOWN,
                "above\n\n---", 16f, 1.35f, 2);
        assertFalse(legacy.contains("class=\"thematic-break\""));
        assertTrue(updated.contains("<hr class=\"thematic-break\">"));
        assertTrue(CompiledTextWebView.isThematicBreak(" --- "));
        assertTrue(CompiledTextWebView.isThematicBreak("***"));
        assertTrue(CompiledTextWebView.isThematicBreak("___"));
        assertFalse(CompiledTextWebView.isThematicBreak("| :--- | ---: |"));
        assertNotEquals(CompiledTextWebView.inputDigest(NoteTextBox.Format.MARKDOWN,
                        "**same source**", 16f, 1.35f, 300, false, 1),
                CompiledTextWebView.inputDigest(NoteTextBox.Format.MARKDOWN,
                        "**same source**", 16f, 1.35f, 300, false, 2));
    }

    @Test public void schemaAndProfileNumbersUseExactIntegralNumericValueRules() throws Exception {
        assertEquals(10, NoteStore.schemaVersion(new JSONObject().put("schemaVersion", 10.0)));
        assertEquals(10, NoteStore.schemaVersion(new JSONObject().put("schemaVersion", 1e1)));
        assertEquals(9, NoteStore.schemaVersion(new JSONObject().put("schemaVersion", 9)));
        for (Object invalid : new Object[]{true, "10", 10.5}) {
            try {
                NoteStore.schemaVersion(new JSONObject().put("schemaVersion", invalid));
                fail("invalid schema value accepted: " + invalid);
            } catch (org.json.JSONException expected) { }
        }
        for (Object invalid : new Object[]{1, 1.0, 2.5, 3, true, "2", JSONObject.NULL}) {
            try {
                TextFlow.fromJson(flowJson(10, "markdown", invalid));
                fail("invalid profile accepted: " + invalid);
            } catch (org.json.JSONException expected) { }
        }
        assertEquals(1, TextFlow.fromJson(flowJson(10, "markdown", null))
                .effectiveMarkdownProfileVersion());
        assertEquals(2, TextFlow.fromJson(flowJson(10, "markdown", 2.0))
                .effectiveMarkdownProfileVersion());
        try {
            TextFlow.fromJson(flowJson(10, "latex", 2));
            fail("profile field on LaTeX flow was accepted");
        } catch (org.json.JSONException expected) { }
    }

    @Test public void schemaNineRejectsProfileFieldWhileSchemaTenPersistsIt() throws Exception {
        JSONObject versionNine = new JSONObject().put("schemaVersion", 9)
                .put("pageCount", 1).put("strokes", new JSONArray())
                .put("textFlows", new JSONArray().put(flowJson(9, "markdown", 2)));
        try { NoteStore.validateDocument(versionNine); fail("schema 9 accepted profile key"); }
        catch (IllegalArgumentException expected) { }
        JSONObject versionTen = new JSONObject().put("schemaVersion", 10)
                .put("pageCount", 1).put("strokes", new JSONArray())
                .put("textFlows", new JSONArray().put(flowJson(10, "markdown", 2)));
        NoteStore.validateDocument(versionTen);
        assertEquals(2, TextFlow.fromJson(versionTen.getJSONArray("textFlows").getJSONObject(0))
                .effectiveMarkdownProfileVersion());
    }

    @Test public void backupSchemaCeilingAcceptsTenAndIntegralJsonNumericForms() throws Exception {
        for (String value : new String[]{"10", "10.0", "1e1"}) {
            Object number = LibraryBackupJson.parse(value.getBytes(StandardCharsets.UTF_8), 32);
            assertEquals(10L, LibraryBackupJson.numericIntegerValue(number, 1, 10,
                    "NOTE_SCHEMA_VERSION"));
        }
        for (String value : new String[]{"10.5", "11", "\"10\"", "true"}) {
            Object number = LibraryBackupJson.parse(value.getBytes(StandardCharsets.UTF_8), 32);
            try {
                LibraryBackupJson.numericIntegerValue(number, 1, 10, "NOTE_SCHEMA_VERSION");
                fail("invalid backup note schema value accepted: " + value);
            } catch (java.io.IOException expected) { }
        }
    }

    private static JSONObject flowJson(int schema, String format, Object profile) throws Exception {
        JSONObject flow = new JSONObject().put("id", "flow-1").put("format", format)
                .put("source", "**sample**").put("fontSizeSp", 16).put("lineHeight", 1.35)
                .put("width", 300).put("anchorPageIndex", 0).put("anchorXInPage", 16)
                .put("anchorYInPage", 16);
        if (profile != null) flow.put("markdownProfileVersion", profile);
        return flow;
    }

    private static String plainText(List<MarkdownV2Parser.Node> nodes) {
        StringBuilder text = new StringBuilder();
        for (MarkdownV2Parser.Node node : nodes) {
            if (node.kind == MarkdownV2Parser.Kind.TEXT) text.append(node.value);
            else if (node.kind == MarkdownV2Parser.Kind.STRONG ||
                    node.kind == MarkdownV2Parser.Kind.EMPHASIS ||
                    node.kind == MarkdownV2Parser.Kind.STRIKE) text.append(plainText(node.children));
        }
        return text.toString();
    }

    private static void assertJsonEquivalent(Object expected, Object actual) throws Exception {
        if (expected instanceof JSONArray) {
            assertTrue(actual instanceof JSONArray);
            JSONArray left = (JSONArray) expected, right = (JSONArray) actual;
            assertEquals(left.length(), right.length());
            for (int i = 0; i < left.length(); i++) assertJsonEquivalent(left.get(i), right.get(i));
            return;
        }
        if (expected instanceof JSONObject) {
            assertTrue(actual instanceof JSONObject);
            JSONObject left = (JSONObject) expected, right = (JSONObject) actual;
            java.util.Set<String> keys = new java.util.HashSet<>();
            java.util.Iterator<String> iterator = left.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            java.util.Set<String> actualKeys = new java.util.HashSet<>();
            iterator = right.keys();
            while (iterator.hasNext()) actualKeys.add(iterator.next());
            assertEquals(keys, actualKeys);
            for (String key : keys) assertJsonEquivalent(left.get(key), right.get(key));
            return;
        }
        assertEquals(expected, actual);
    }

    private static JSONArray astJson(List<MarkdownV2Parser.Node> nodes) throws Exception {
        JSONArray result = new JSONArray();
        for (MarkdownV2Parser.Node node : nodes) {
            JSONObject value = new JSONObject();
            switch (node.kind) {
                case TEXT: value.put("type", "text").put("value", node.value); break;
                case CODE: value.put("type", "code").put("value", node.value); break;
                case MATH:
                    value.put("type", "math").put("value", node.value);
                    if (node.displayMath) value.put("display", true);
                    break;
                case STRONG: value.put("type", "strong").put("children", astJson(node.children)); break;
                case EMPHASIS: value.put("type", "emphasis").put("children", astJson(node.children)); break;
                case STRIKE: value.put("type", "strike").put("children", astJson(node.children)); break;
                case LINK: value.put("type", "link").put("label", plainText(node.children))
                        .put("destination", node.value); break;
            }
            result.put(value);
        }
        return result;
    }
}
