package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class MarkdownTablePaginationTest {
    private static final String R3_TABLE = "| MIXED-ID | 类型 | 样本 | 状态 | 说明 | 校验标记 |\n" +
            "| --- | --- | --- | --- | --- | --- |\n" +
            "| T01 | 段落 | alpha | ready | 普通短单元格 | CELL-A |\n" +
            "| T02 | 列表 | beta | ready | 检查列表混排 | CELL-B |\n" +
            "| T03 | 数学 | gamma | queued | MIXED-LONGCELL-BEGIN：这是完全人工编写的长表格单元格，用来观察六列宽表格的换行、横向排版、跨页连续性和保存表现。内容不来自模型或用户笔记。MIXED-LONGCELL-END | CELL-C |\n" +
            "| T04 | 图片 | delta | ready | 色块图形 | CELL-D |\n" +
            "| T05 | 笔迹 | epsilon | ready | 红蓝标记 | CELL-E |\n" +
            "| T06 | PDF | zeta | source | MIXED-PDF-BACKGROUND-PAGE1 | CELL-F |";

    @Test public void fittingTableRemainsByteForByteUnchanged() {
        MarkdownTablePagination.Result result = MarkdownTablePagination.paginate(
                R3_TABLE, 100_000f, markdown -> 1f);

        assertEquals(MarkdownTablePagination.Status.FITS, result.status);
        assertEquals(1, result.fragments.size());
        assertEquals(R3_TABLE, result.fragments.get(0));
    }

    @Test public void r3WideTableRepeatsHeaderAndPreservesEveryBodyCellInOrder() {
        MarkdownTablePagination.HeightEstimator estimator = this::estimateLikePaperLayout;
        float pageCapacity = 335f;
        MarkdownTablePagination.Result result = MarkdownTablePagination.paginate(
                R3_TABLE, pageCapacity, estimator);

        assertEquals(MarkdownTablePagination.Status.PAGINATED, result.status);
        assertTrue("long table should be split into multiple page-safe groups",
                result.fragments.size() > 1);
        String[] sourceLines = R3_TABLE.split("\n");
        String[] expectedColumns = concatenateColumns(sourceLines, 2);
        String[] actualColumns = new String[6];
        for (int column = 0; column < actualColumns.length; column++) {
            actualColumns[column] = concatenateBodyColumn(result.fragments, column);
            assertEquals("body cell text in column " + column + " must be lossless and ordered",
                    expectedColumns[column], actualColumns[column]);
        }
        for (String fragment : result.fragments) {
            String[] lines = fragment.split("\n");
            assertEquals(sourceLines[0], lines[0]);
            assertEquals(sourceLines[1], lines[1]);
            assertTrue("each repeated-header group fits the supplied page budget",
                    estimator.estimate(fragment) <= pageCapacity);
        }
        assertTrue(actualColumns[4].contains("MIXED-LONGCELL-BEGIN"));
        assertTrue(actualColumns[4].contains("MIXED-LONGCELL-END"));
    }

    @Test public void escapedPipesInlineCodePipesEmptyCellsAndAlignmentSurviveGrouping() {
        String source = "| left | middle | right |\n" +
                "| :--- | ---: | :---: |\n" +
                "| A\\|B | `inline | pipe` |  |\n" +
                "| C | D | E |\n" +
                "| F | G | H |";
        MarkdownTablePagination.HeightEstimator estimator = markdown ->
                markdown.split("\n").length * 105f;
        MarkdownTablePagination.Result result = MarkdownTablePagination.paginate(
                source, 350f, estimator);

        assertEquals(MarkdownTablePagination.Status.PAGINATED, result.status);
        assertTrue(result.fragments.size() > 1);
        for (String fragment : result.fragments) {
            String[] lines = fragment.split("\n");
            assertEquals("| left | middle | right |", lines[0]);
            assertEquals("| :--- | ---: | :---: |", lines[1]);
        }
        List<String> bodyRows = new ArrayList<>();
        for (String fragment : result.fragments) {
            String[] lines = fragment.split("\n");
            for (int index = 2; index < lines.length; index++) bodyRows.add(lines[index]);
        }
        assertEquals("A\\|B row, inline-code pipe, empty cell, and later rows remain byte-exact",
                "| A\\|B | `inline | pipe` |  |\n| C | D | E |\n| F | G | H |",
                joinLines(bodyRows));
    }

    @Test public void escapedBackticksOutsideCodeAndBackslashesInsideCodeParseLiterally() {
        String source = "| id | note |\n| --- | --- |\n" +
                "| 1 | literal \\` tick |\n" +
                "| 2 | `code\\` |\n" +
                "| 3 | later row |";
        MarkdownTablePagination.HeightEstimator estimator = markdown ->
                markdown.split("\\n").length * 100f;
        MarkdownTablePagination.Result result = MarkdownTablePagination.paginate(
                source, 300f, estimator);

        assertEquals(MarkdownTablePagination.Status.PAGINATED, result.status);
        List<String> bodyRows = new ArrayList<>();
        for (String fragment : result.fragments) {
            String[] lines = fragment.split("\\n");
            assertEquals("| id | note |", lines[0]);
            assertEquals("| --- | --- |", lines[1]);
            for (int index = 2; index < lines.length; index++) bodyRows.add(lines[index]);
        }
        assertEquals("| 1 | literal \\` tick |\n| 2 | `code\\` |\n| 3 | later row |",
                joinLines(bodyRows));
    }

    @Test public void longCellSplitsOnUnicodeSafeBoundariesWithoutTrimmingWhitespace() {
        String cell = "  第一段 👩🏽‍💻🇺🇸e\u0301 words remain in order with spaces between every phrase " +
                "第二段 👩🏽‍💻🇨🇦e\u0301 still complete  ";
        String source = "| id | detail |\n| --- | --- |\n| A |" + cell + "|";
        MarkdownTablePagination.HeightEstimator estimator = markdown -> {
            int bodyLength = 0;
            String[] lines = markdown.split("\n");
            for (int index = 2; index < lines.length; index++) {
                bodyLength += lines[index].codePointCount(0, lines[index].length());
            }
            return 80f + bodyLength * 3f;
        };
        float capacity = 350f;
        MarkdownTablePagination.Result result = MarkdownTablePagination.paginate(
                source, capacity, estimator);

        assertEquals(MarkdownTablePagination.Status.PAGINATED, result.status);
        assertTrue(result.fragments.size() > 1);
        assertEquals(" A ", concatenateBodyColumn(result.fragments, 0));
        assertEquals(cell, concatenateBodyColumn(result.fragments, 1));
        for (String segment : bodyColumnSegments(result.fragments, 1)) {
            String visible = segment.trim();
            if (visible.isEmpty()) continue;
            int first = visible.codePointAt(0);
            int last = visible.codePointBefore(visible.length());
            assertFalse("a continuation cannot start with a combining/emoji extender",
                    isUnicodeExtender(first) || first == 0x200D);
            assertFalse("a continuation cannot end at a ZWJ boundary", last == 0x200D);
            if (visible.contains("👩") || visible.contains("🏽") ||
                    visible.contains("‍") || visible.contains("💻")) {
                assertTrue("emoji ZWJ sequence remains in one fragment",
                        visible.contains("👩🏽‍💻"));
            }
            if (visible.indexOf('\u0301') >= 0) {
                assertTrue("combining accent remains attached to its base",
                        visible.contains("e\u0301"));
            }
            assertRegionalIndicatorPairs(visible);
        }
        for (String fragment : result.fragments) {
            assertTrue(estimator.estimate(fragment) <= capacity);
        }
        assertFalse("no continuation marker or ellipsis may replace source text",
                concatenateBodyColumn(result.fragments, 1).contains("…"));
    }

    @Test public void unsafeOrMalformedOversizedTablesFailClosed() {
        String mismatched = "| a | b |\n| --- | --- |\n| one | two | three |";
        MarkdownTablePagination.Result malformed = MarkdownTablePagination.paginate(
                mismatched, 100f, markdown -> markdown.split("\n").length * 100f);
        assertEquals(MarkdownTablePagination.Status.UNSUPPORTED, malformed.status);
        assertTrue(malformed.fragments.isEmpty());

        String separateTables = "| a | b |\n| --- | --- |\n| 1 | 2 |\n\n| c | d |\n| --- | --- |\n| 3 | 4 |";
        MarkdownTablePagination.Result separated = MarkdownTablePagination.paginate(
                separateTables, 100f, markdown -> markdown.length() * 2f);
        assertEquals("separate tables cannot be merged by skipping the source blank line",
                MarkdownTablePagination.Status.UNSUPPORTED, separated.status);

        String protectedLongCell = "| a | b |\n| --- | --- |\n| 1 | `a very long inline code cell |";
        MarkdownTablePagination.Result protectedResult = MarkdownTablePagination.paginate(
                protectedLongCell, 100f, markdown -> markdown.length() * 3f);
        assertEquals(MarkdownTablePagination.Status.UNSUPPORTED, protectedResult.status);
        assertTrue(protectedResult.fragments.isEmpty());

        String protectedLink = "| a | b |\n| --- | --- |\n| 1 | https://example.invalid/this-is-a-long-link |";
        MarkdownTablePagination.Result linkResult = MarkdownTablePagination.paginate(
                protectedLink, 100f, markdown -> markdown.length() * 3f);
        assertEquals(MarkdownTablePagination.Status.UNSUPPORTED, linkResult.status);
        assertTrue(linkResult.fragments.isEmpty());

        String overlargeHeader = "| a very long header | b |\n| --- | --- |\n| x | y |";
        MarkdownTablePagination.Result headerResult = MarkdownTablePagination.paginate(
                overlargeHeader, 40f, markdown -> markdown.length() * 2f);
        assertEquals(MarkdownTablePagination.Status.UNSUPPORTED, headerResult.status);

        String twoOversizedCells = "| a | b |\n| --- | --- |\n| " + "left ".repeat(40) +
                " | " + "right ".repeat(40) + " |";
        MarkdownTablePagination.Result twoLongCells = MarkdownTablePagination.paginate(
                twoOversizedCells, 200f, markdown -> markdown.length() * 2f);
        assertEquals("two simultaneous over-height cells fail closed until coordinated splitting is supported",
                MarkdownTablePagination.Status.UNSUPPORTED, twoLongCells.status);
    }

    @Test public void rowExpansionBudgetReturnsPageLimitBeforeBuildingUnboundedFragments() {
        String source = "| a | b |\n| --- | --- |\n| 1 | " + "x".repeat(5000) + " |";
        MarkdownTablePagination.Result result = MarkdownTablePagination.paginate(
                source, 4f, markdown -> {
                    String[] lines = markdown.split("\\n");
                    return 3f + (lines.length > 2
                            ? lines[2].codePointCount(0, lines[2].length()) - 8 : 0f);
                });
        assertEquals(MarkdownTablePagination.Status.PAGE_LIMIT, result.status);
        assertEquals(MarkdownTablePagination.EXPANSION_LIMIT_REASON, result.reason);
        assertTrue(result.fragments.isEmpty());
    }

    @Test public void trueTablePageCapIsDistinctFromAnUnplaceableCell() {
        MarkdownTablePagination.Result capped = MarkdownTablePagination.paginate(
                R3_TABLE, 335f, 1, this::estimateLikePaperLayout);

        assertEquals(MarkdownTablePagination.Status.PAGE_LIMIT, capped.status);
        assertTrue(capped.fragments.isEmpty());
    }

    @Test public void nonTablesAreLeftToTheExistingPaginator() {
        MarkdownTablePagination.Result paragraph = MarkdownTablePagination.paginate(
                "A paragraph with | characters but no alignment row.", 1f,
                markdown -> 100f);
        assertEquals(MarkdownTablePagination.Status.NOT_TABLE, paragraph.status);

        MarkdownTablePagination.Result list = MarkdownTablePagination.paginate(
                "- first\n- second", 1f, markdown -> 100f);
        assertEquals(MarkdownTablePagination.Status.NOT_TABLE, list.status);

        MarkdownTablePagination.Result latex = MarkdownTablePagination.paginate(
                "\\frac{1}{3}", 1f, markdown -> 100f);
        assertEquals(MarkdownTablePagination.Status.NOT_TABLE, latex.status);
    }

    private float estimateLikePaperLayout(String markdown) {
        float height = 20f;
        for (String line : markdown.split("\n")) {
            int length = line.codePointCount(0, line.length());
            int lines = Math.max(1, (length + 23) / 24);
            height += lines * 42f;
        }
        return height;
    }

    private static String[] concatenateColumns(String[] lines, int firstBodyLine) {
        String[] columns = new String[6];
        StringBuilder[] output = new StringBuilder[columns.length];
        for (int index = 0; index < output.length; index++) output[index] = new StringBuilder();
        for (int lineIndex = firstBodyLine; lineIndex < lines.length; lineIndex++) {
            List<String> cells = simpleCells(lines[lineIndex]);
            for (int column = 0; column < output.length; column++) output[column].append(cells.get(column));
        }
        for (int index = 0; index < columns.length; index++) columns[index] = output[index].toString();
        return columns;
    }

    private static String concatenateBodyColumn(List<String> fragments, int column) {
        StringBuilder result = new StringBuilder();
        for (String fragment : fragments) {
            String[] lines = fragment.split("\n");
            for (int index = 2; index < lines.length; index++) {
                result.append(simpleCells(lines[index]).get(column));
            }
        }
        return result.toString();
    }

    private static List<String> bodyColumnSegments(List<String> fragments, int column) {
        List<String> result = new ArrayList<>();
        for (String fragment : fragments) {
            String[] lines = fragment.split("\n");
            for (int index = 2; index < lines.length; index++) {
                result.add(simpleCells(lines[index]).get(column));
            }
        }
        return result;
    }

    private static boolean isUnicodeExtender(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK ||
                type == Character.COMBINING_SPACING_MARK ||
                type == Character.ENCLOSING_MARK ||
                (codePoint >= 0x1F3FB && codePoint <= 0x1F3FF) ||
                (codePoint >= 0xFE00 && codePoint <= 0xFE0F);
    }

    private static void assertRegionalIndicatorPairs(String value) {
        int run = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF) {
                run++;
            } else {
                assertTrue("flag regional indicators remain paired", run == 0 || run == 2);
                run = 0;
            }
        }
        assertTrue("flag regional indicators remain paired", run == 0 || run == 2);
    }

    private static List<String> simpleCells(String row) {
        String value = row;
        if (value.startsWith("|")) value = value.substring(1);
        if (value.endsWith("|")) value = value.substring(0, value.length() - 1);
        String[] cells = value.split("\\|", -1);
        List<String> result = new ArrayList<>(cells.length);
        for (String cell : cells) result.add(cell);
        return result;
    }

    private static String joinLines(List<String> lines) {
        StringBuilder joined = new StringBuilder();
        for (String line : lines) {
            if (joined.length() > 0) joined.append('\n');
            joined.append(line);
        }
        return joined.toString();
    }
}
