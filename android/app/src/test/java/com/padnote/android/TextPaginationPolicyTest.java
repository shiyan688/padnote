package com.padnote.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class TextPaginationPolicyTest {
    @Test public void headingAtPageTailMovesWithItsFirstBlock() {
        assertTrue(NoteCanvasView.shouldKeepHeadingWithNext(
                false, true, false, 540f, 42f, 84f, 620f));
    }

    @Test public void headingStartedMidPageMovesWhenPairDoesNotFit() {
        assertTrue(NoteCanvasView.shouldKeepHeadingWithNext(
                false, false, false, 20f, 42f, 84f, 120f));
    }

    @Test public void oversizedPairAtFreshPageTopStillMakesProgress() {
        assertFalse(NoteCanvasView.shouldKeepHeadingWithNext(
                false, false, true, 20f, 90f, 700f, 620f));
    }

    @Test public void fittingHeadingPairStaysOnCurrentPage() {
        assertFalse(NoteCanvasView.shouldKeepHeadingWithNext(
                false, true, false, 400f, 42f, 84f, 620f));
    }

    @Test public void headingAndMeasuredDiagramUseSeparateFragmentsOnSamePage() {
        float capacity = 620f;
        float headingFragmentHeight = 84f;
        float measuredDiagramHeight = 280f;
        assertFalse(NoteCanvasView.shouldKeepHeadingWithNext(
                false, false, false, 20f, 42f, measuredDiagramHeight, capacity));

        NoteCanvasView.FragmentContinuation diagram = NoteCanvasView.nextFragmentPlacement(
                2, 100f, headingFragmentHeight, 720f, 752f, 72f, true);
        assertEquals(2, diagram.pageIndex);
        assertEquals(184f, diagram.y, 0.01f);
        assertTrue(diagram.y + measuredDiagramHeight <= 720f);
    }

    @Test public void isolatedDiagramFitIncludesItsOwnContentPadding() {
        // The old comparison saw 80 + 280 == 360 and kept the heading here.
        // The diagram's separate WebView also needs 20px content padding, so the
        // complete 300px fragment must move with the heading instead.
        assertEquals(300f, NoteCanvasView.isolatedFragmentHeight(
                280f, 20f, 72f), 0.01f);
        assertFalse(NoteCanvasView.canPlaceIsolatedFragmentOnSamePage(
                80f, 280f, 360f, 20f, 72f));
        assertTrue(NoteCanvasView.canPlaceIsolatedFragmentOnSamePage(
                80f, 280f, 380f, 20f, 72f));
    }

    @Test public void isolatedDiagramMovesOnlyWhenPageTailCannotHoldIt() {
        NoteCanvasView.FragmentContinuation diagram = NoteCanvasView.nextFragmentPlacement(
                2, 610f, 60f, 720f, 752f, 72f, true);
        assertEquals(3, diagram.pageIndex);
        assertEquals(752f, diagram.y, 0.01f);
    }

    @Test public void measuredDiagramHeightsArePerBlockShrinkableAndDeepCopied() {
        TextFlow flow = new TextFlow("flow", NoteTextBox.Format.MARKDOWN, "source",
                16f, 1.35f, 700f, 0, 16f, 16f);
        String horizontal = "```mermaid\nflowchart LR\nA-->B\n```";
        String vertical = "```mermaid\nflowchart TD\nA-->B\nB-->C\nC-->D\n```";

        flow.recordMeasuredMermaidHeight(horizontal, 96f);
        flow.recordMeasuredMermaidHeight(vertical, 420f);
        assertEquals(96f, flow.measuredMermaidHeight(horizontal), 0.01f);
        assertEquals(420f, flow.measuredMermaidHeight(vertical), 0.01f);

        flow.recordMeasuredMermaidHeight(horizontal, 72f);
        TextFlow copy = flow.copy();
        flow.clearMeasuredMermaidHeights();
        assertEquals(0f, flow.measuredMermaidHeight(horizontal), 0.01f);
        assertEquals(72f, copy.measuredMermaidHeight(horizontal), 0.01f);
        assertEquals(420f, copy.measuredMermaidHeight(vertical), 0.01f);
    }

    @Test public void pdfGateDistinguishesPageCapFromUnplaceableBlock() {
        TextFlow pageCap = new TextFlow("cap", NoteTextBox.Format.MARKDOWN, "x",
                16f, 1.35f, 700f, 0, 16f, 16f);
        pageCap.lastLayoutComplete = false;
        pageCap.lastLayoutPageCount = 499;
        pageCap.lastLayoutFailureReason = TextFlow.LayoutFailureReason.PAGE_LIMIT;
        assertTrue(NoteCanvasView.isFlowPageLimitFailure(pageCap));

        TextFlow oversizedBlock = new TextFlow("block", NoteTextBox.Format.MARKDOWN, "x",
                16f, 1.35f, 700f, 0, 16f, 16f);
        oversizedBlock.lastLayoutComplete = false;
        oversizedBlock.lastLayoutPageCount = 500;
        oversizedBlock.lastLayoutFailureReason = TextFlow.LayoutFailureReason.UNPLACEABLE_BLOCK;
        assertFalse(NoteCanvasView.isFlowPageLimitFailure(oversizedBlock));

        TextFlow unsupportedTable = new TextFlow("table", NoteTextBox.Format.MARKDOWN, "x",
                16f, 1.35f, 700f, 0, 16f, 16f);
        unsupportedTable.lastLayoutComplete = false;
        unsupportedTable.lastLayoutFailureReason = TextFlow.LayoutFailureReason.UNSUPPORTED_TABLE;
        assertFalse(NoteCanvasView.isFlowPageLimitFailure(unsupportedTable));
    }

    @Test public void flowCapacityMapsLayoutFailuresWithoutMaskingSourceLimits() {
        assertEquals(TextFlowCapacity.Failure.PAGE_LIMIT,
                TextFlowCapacity.checkLayout("x", 500, false,
                        TextFlow.LayoutFailureReason.PAGE_LIMIT));
        assertEquals(TextFlowCapacity.Failure.UNPLACEABLE_BLOCK,
                TextFlowCapacity.checkLayout("x", 3, false,
                        TextFlow.LayoutFailureReason.UNPLACEABLE_BLOCK));
        assertEquals(TextFlowCapacity.Failure.UNSUPPORTED_TABLE,
                TextFlowCapacity.checkLayout("x", 3, false,
                        TextFlow.LayoutFailureReason.UNSUPPORTED_TABLE));
        assertEquals(TextFlowCapacity.Failure.TABLE_EXPANSION_LIMIT,
                TextFlowCapacity.checkLayout("x", 3, false,
                        TextFlow.LayoutFailureReason.TABLE_EXPANSION_LIMIT));
        assertEquals("the physical page cap has priority over a narrower cause",
                TextFlowCapacity.Failure.PAGE_LIMIT,
                TextFlowCapacity.checkLayout("x", 501, false,
                        TextFlow.LayoutFailureReason.UNPLACEABLE_BLOCK));
        assertEquals(TextFlowCapacity.Failure.PAGE_LIMIT,
                TextFlowCapacity.checkLayout("x", 3, false,
                        TextFlow.LayoutFailureReason.NONE));
        assertEquals(TextFlowCapacity.Failure.SOURCE_TOO_LONG,
                TextFlowCapacity.checkLayout("x".repeat(TextFlow.MAX_SOURCE_LENGTH + 1),
                        501, false, TextFlow.LayoutFailureReason.TABLE_EXPANSION_LIMIT));
        TextFlowCapacity.Rejection localized = new TextFlowCapacity.Rejection(
                TextFlowCapacity.Failure.UNSUPPORTED_TABLE, "localized table message");
        assertEquals(TextFlowCapacity.Failure.UNSUPPORTED_TABLE, localized.failure);
        assertEquals("localized table message", localized.getMessage());
    }

    @Test public void aBlockThatFitsFreshPaperButNotPage500TailIsPageLimit() {
        assertEquals(TextFlow.LayoutFailureReason.PAGE_LIMIT,
                NoteCanvasView.classifyNoFitBlockFailure(true,
                        360f, 300f, 36f, 20f, 220f, 315f,
                        TextFlow.LayoutFailureReason.UNPLACEABLE_BLOCK));
        assertEquals("a block larger than a complete fresh page remains unsupported",
                TextFlow.LayoutFailureReason.UNSUPPORTED_TABLE,
                NoteCanvasView.classifyNoFitBlockFailure(true,
                        360f, 300f, 36f, 20f, 400f, 315f,
                        TextFlow.LayoutFailureReason.UNSUPPORTED_TABLE));
        assertEquals("the same tail on a non-final page is not a page-cap failure",
                TextFlow.LayoutFailureReason.UNPLACEABLE_BLOCK,
                NoteCanvasView.classifyNoFitBlockFailure(false,
                        360f, 300f, 36f, 20f, 220f, 315f,
                        TextFlow.LayoutFailureReason.UNPLACEABLE_BLOCK));
    }

    @Test public void adjacentIntroAndMarkdownTableAreSeparatedWithoutSplittingPlainPipes() {
        String intro = "Intro immediately before table.";
        String table = "| id | detail |\n| --- | --- |\n| A | first row |\n| B | second row |";
        String trailing = "Trailing paragraph remains after the table.";
        List<String> blocks = NoteCanvasView.splitTextFlowBlocks(NoteTextBox.Format.MARKDOWN,
                intro + "\n" + table + "\n" + trailing);
        assertEquals(Arrays.asList(intro, table, trailing), blocks);

        List<String> plainPipe = NoteCanvasView.splitTextFlowBlocks(
                NoteTextBox.Format.MARKDOWN, "A plain sentence | with a pipe\nand more prose.");
        assertEquals(Arrays.asList("A plain sentence | with a pipe\nand more prose."), plainPipe);

        List<String> fenced = NoteCanvasView.splitTextFlowBlocks(NoteTextBox.Format.MARKDOWN,
                "```text\n| header | body |\n| --- | --- |\n```\nAfter fence.");
        assertEquals(Arrays.asList("```text\n| header | body |\n| --- | --- |\n```",
                "After fence."), fenced);
    }

    @Test public void exportNeverShrinksOrdinaryProsePerFragment() {
        NoteTextBox prose = new NoteTextBox("part", "flow", 1, 3,
                NoteTextBox.Format.MARKDOWN, "source", "第 13 行完整保留。",
                16f, 1.35f, 2, 16f, 16f, 500f, 700f);
        NoteTextBox diagram = new NoteTextBox("diagram", "diagram-flow", 0, 1,
                NoteTextBox.Format.MARKDOWN, "source",
                "```mermaid\nflowchart TD\nA-->B\n```", 16f, 1.35f,
                0, 16f, 16f, 500f, 300f);
        NoteTextBox formula = new NoteTextBox("formula", "formula-flow", 0, 1,
                NoteTextBox.Format.LATEX, "x^2", "x^2", 16f, 1.35f,
                0, 16f, 16f, 500f, 100f);

        assertFalse(NoteCanvasView.PdfExportSnapshot.exportFragmentMayScale(prose));
        assertTrue(NoteCanvasView.PdfExportSnapshot.exportFragmentMayScale(diagram));
        assertTrue(NoteCanvasView.PdfExportSnapshot.exportFragmentMayScale(formula));
    }

    @Test public void finalReadableUnitDoesNotStartANewPageAlone() {
        assertTrue(NoteCanvasView.shouldMovePenultimateForWidow(
                true, 540f, 36f, 36f, 610f));
        assertFalse("a fresh page must still make progress",
                NoteCanvasView.shouldMovePenultimateForWidow(
                        false, 20f, 400f, 400f, 610f));
        assertFalse("keep both when they already fit",
                NoteCanvasView.shouldMovePenultimateForWidow(
                        true, 500f, 36f, 36f, 610f));
    }
}
