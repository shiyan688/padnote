package com.padnote.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
}
