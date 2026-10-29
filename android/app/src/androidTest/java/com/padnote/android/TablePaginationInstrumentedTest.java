package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.RectF;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class TablePaginationInstrumentedTest {
    private static String repeatForTest(String value, int count) {
        if (count < 0) throw new IllegalArgumentException("count");
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
    private static final String TABLE_FLOW_ID = "synthetic-r3-table";
    private static final String TABLE_SOURCE =
            "| MIXED-ID | 类型 | 样本 | 状态 | 说明 | 校验标记 |\n" +
            "| --- | --- | --- | --- | --- | --- |\n" +
            "| T01 | 段落 | alpha | ready | 普通短单元格 | CELL-A |\n" +
            "| T02 | 列表 | beta | ready | 检查列表混排 | CELL-B |\n" +
            "| T03 | 数学 | gamma | queued | MIXED-LONGCELL-BEGIN：这是完全人工编写的长表格单元格，用来观察六列宽表格的换行、横向排版、跨页连续性和保存表现。内容不来自模型或用户笔记。MIXED-LONGCELL-END | CELL-C |\n" +
            "| T04 | 图片 | delta | ready | 色块图形 | CELL-D |\n" +
            "| T05 | 笔迹 | epsilon | ready | 红蓝标记 | CELL-E |\n" +
            "| T06 | PDF | zeta | source | MIXED-PDF-BACKGROUND-PAGE1 | CELL-F |";

    @Test public void realCanvasPaginatesR3TableAndKeepsCanonicalSources() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(context);
            try {
                canvas.layout(0, 0, 1200, 1000);
                canvas.loadJsonDocument(documentWithFlows());

                TextFlow table = findFlow(canvas, TABLE_FLOW_ID);
                assertEquals(TABLE_SOURCE, table.source);
                assertTrue("real canvas layout must complete", table.lastLayoutComplete);
                assertTrue("real canvas page count remains under the hard cap",
                        canvas.getPageCount() <= 500);

                List<NoteTextBox> tableFragments = fragmentsFor(canvas, TABLE_FLOW_ID);
                assertTrue("R3 table should produce more than one renderer fragment",
                        tableFragments.size() > 1);
                String header = "| MIXED-ID | 类型 | 样本 | 状态 | 说明 | 校验标记 |";
                String delimiter = "| --- | --- | --- | --- | --- | --- |";
                int completeTables = 0;
                for (NoteTextBox fragment : tableFragments) {
                    assertEquals(TABLE_SOURCE, fragment.source);
                    assertTrue(fragment.pageIndex >= 0 && fragment.pageIndex < canvas.getPageCount());
                    for (String block : fragment.fragmentSource.split("\\n\\s*\\n")) {
                        String trimmed = block.trim();
                        if (!trimmed.contains("MIXED-ID")) continue;
                        String[] lines = trimmed.split("\\n");
                        assertTrue("repeated header must start an independent Markdown table",
                                lines.length >= 3 && header.equals(lines[0]) &&
                                        delimiter.equals(lines[1]));
                        completeTables++;
                    }
                }
                assertTrue("each derived table group retains a header and body",
                        completeTables >= tableFragments.size());

                TextFlow paragraph = findFlow(canvas, "synthetic-paragraph-control");
                assertEquals("A short ordinary paragraph remains readable.", paragraph.source);
                assertTrue(paragraph.lastLayoutComplete);
                assertTrue(fragmentsFor(canvas, paragraph.id).stream()
                        .anyMatch(box -> box.fragmentSource.contains(paragraph.source)));

                TextFlow latex = findFlow(canvas, "synthetic-latex-control");
                assertEquals("\\frac{1}{3}", latex.source);
                assertTrue(latex.lastLayoutComplete);
                assertFalse(fragmentsFor(canvas, latex.id).isEmpty());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
    }

    @Test public void unsupportedAiTableIsRejectedWithoutPartialFlowOrUndoMutation() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(context);
            try {
                canvas.layout(0, 0, 1200, 1000);
                canvas.loadJsonDocument(emptyDocument());
                int pagesBefore = canvas.getPageCount();
                int undoBefore = canvas.undoDepthForTest();
                String oversizedCode = "| id | detail |\n| --- | --- |\n| 1 | `" +
                        repeatForTest("code-content-", 500) + "` |";
                try {
                    canvas.addAiResultTextBox(NoteTextBox.Format.MARKDOWN,
                            oversizedCode, new RectF(24f, 24f, 240f, 90f));
                    throw new AssertionError("unsupported oversized table must be rejected");
                } catch (TextFlowCapacity.Rejection expected) {
                    assertEquals(TextFlowCapacity.Failure.UNSUPPORTED_TABLE, expected.failure);
                    assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
                }
                assertTrue(canvas.getTextFlows().isEmpty());
                assertEquals(undoBefore, canvas.undoDepthForTest());
                assertEquals(pagesBefore, canvas.getPageCount());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
    }

    @Test public void adjacentIntroAndTrailingParagraphSurviveRealTablePagination() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(context);
            try {
                canvas.layout(0, 0, 1200, 1000);
                String intro = "Intro directly before the table.";
                String trailing = "Trailing paragraph directly after the table.";
                String source = intro + "\n" + TABLE_SOURCE + "\n" + trailing;
                canvas.loadJsonDocument(documentWithSingleFlow("synthetic-embedded-table", source));

                TextFlow flow = findFlow(canvas, "synthetic-embedded-table");
                assertEquals("canonical source, including no-blank-line boundaries, is unchanged",
                        source, flow.source);
                assertTrue(flow.lastLayoutComplete);
                assertTrue(canvas.getPageCount() <= 500);
                List<NoteTextBox> fragments = fragmentsFor(canvas, flow.id);
                assertTrue(fragments.size() > 1);
                StringBuilder visible = new StringBuilder();
                for (NoteTextBox fragment : fragments) {
                    if (visible.length() > 0) visible.append("\n\n");
                    visible.append(fragment.fragmentSource);
                }
                String derived = visible.toString();
                int introAt = derived.indexOf(intro);
                int headerAt = derived.indexOf("| MIXED-ID | 类型 | 样本 | 状态 | 说明 | 校验标记 |");
                int bodyEndAt = derived.lastIndexOf("MIXED-PDF-BACKGROUND-PAGE1");
                int trailingAt = derived.indexOf(trailing);
                assertTrue(introAt >= 0 && introAt < headerAt);
                assertTrue(headerAt >= 0 && bodyEndAt > headerAt && trailingAt > bodyEndAt);
                assertTrue("a long table is paginated with its repeated header",
                        occurrences(derived, "| MIXED-ID | 类型 | 样本 | 状态 | 说明 | 校验标记 |") > 1);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
    }

    private static JSONObject documentWithFlows() throws Exception {
        JSONArray flows = new JSONArray()
                .put(flow(TABLE_FLOW_ID, "markdown", TABLE_SOURCE, 18d, 1.28d, 700d))
                .put(flow("synthetic-paragraph-control", "markdown",
                        "A short ordinary paragraph remains readable.", 18d, 1.28d, 700d))
                .put(flow("synthetic-latex-control", "latex", "\\frac{1}{3}",
                        18d, 1.28d, 700d));
        return new JSONObject()
                .put("schemaVersion", 8)
                .put("id", "synthetic-table-pagination")
                .put("title", "Synthetic Table Pagination")
                .put("pageWidth", 800d)
                .put("pageHeight", 387.5556d)
                .put("pageGap", 24d)
                .put("pageCount", 1)
                .put("strokes", new JSONArray())
                .put("images", new JSONArray())
                .put("textBoxes", new JSONArray())
                .put("textFlows", flows);
    }

    private static JSONObject emptyDocument() throws Exception {
        return new JSONObject()
                .put("schemaVersion", 8)
                .put("id", "synthetic-table-insertion-rejection")
                .put("title", "Synthetic Table Insertion Rejection")
                .put("pageWidth", 800d)
                .put("pageHeight", 387.5556d)
                .put("pageGap", 24d)
                .put("pageCount", 1)
                .put("strokes", new JSONArray())
                .put("images", new JSONArray())
                .put("textBoxes", new JSONArray())
                .put("textFlows", new JSONArray());
    }

    private static JSONObject documentWithSingleFlow(String id, String source) throws Exception {
        return new JSONObject()
                .put("schemaVersion", 8)
                .put("id", "synthetic-table-with-surrounding-text")
                .put("title", "Synthetic Table Boundaries")
                .put("pageWidth", 800d)
                .put("pageHeight", 387.5556d)
                .put("pageGap", 24d)
                .put("pageCount", 1)
                .put("strokes", new JSONArray())
                .put("images", new JSONArray())
                .put("textBoxes", new JSONArray())
                .put("textFlows", new JSONArray().put(flow(id, "markdown", source,
                        18d, 1.28d, 700d)));
    }

    private static JSONObject flow(String id, String format, String source,
                                   double fontSize, double lineHeight, double width)
            throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("format", format)
                .put("source", source)
                .put("fontSizeSp", fontSize)
                .put("lineHeight", lineHeight)
                .put("width", width)
                .put("anchorPageIndex", 0)
                .put("anchorXInPage", 24d)
                .put("anchorYInPage", 24d);
    }

    private static TextFlow findFlow(NoteCanvasView canvas, String id) {
        for (TextFlow flow : canvas.getTextFlows()) {
            if (id.equals(flow.id)) return flow;
        }
        throw new AssertionError("missing synthetic flow " + id);
    }

    private static List<NoteTextBox> fragmentsFor(NoteCanvasView canvas, String flowId) {
        List<NoteTextBox> result = new ArrayList<>();
        for (NoteTextBox box : canvas.getTextBoxes()) {
            if (flowId.equals(box.flowId)) result.add(box);
        }
        return result;
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int at = 0;
        while ((at = text.indexOf(token, at)) >= 0) {
            count++;
            at += token.length();
        }
        return count;
    }
}
