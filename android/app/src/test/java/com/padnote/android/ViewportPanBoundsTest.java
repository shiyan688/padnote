package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ViewportPanBoundsTest {
    @Test public void longDocumentPreservesCenteredPagePinchAcrossHundredToHalfAndBack() {
        float viewportHeight = 1403f;
        float pageHeight = 1086f;
        float pageGap = 24f;
        float documentHeight = 18f * pageHeight + 17f * pageGap;
        float margin = 40.5f;
        float focusY = 699f;
        float initialPan = (viewportHeight - pageHeight) / 2f;
        float initialCenterY = (viewportHeight / 2f - initialPan);

        float maxAtOne = NoteCanvasView.maximumViewportPanYForGeometry(
                viewportHeight, pageHeight, documentHeight, 1f, margin);
        assertEquals(158.5f, maxAtOne, 0.001f);
        assertEquals(initialCenterY, 543f, 0.001f);
        assertTrue(initialPan <= maxAtOne);

        float anchorWorldY = (focusY - initialPan) / 1f;
        float panAtHalf = focusY - anchorWorldY * 0.5f;
        float maxAtHalf = NoteCanvasView.maximumViewportPanYForGeometry(
                viewportHeight, pageHeight, documentHeight, 0.5f, margin);
        assertEquals(430f, maxAtHalf, 0.001f);
        assertTrue("pinch pan must remain inside the top bound", panAtHalf <= maxAtHalf);
        float centerAtHalf = (viewportHeight / 2f - panAtHalf) / 0.5f;
        assertEquals(545.5f, centerAtHalf, 0.001f);

        float anchorAfterHalf = (focusY - panAtHalf) / 0.5f;
        float panAtOneAgain = focusY - anchorAfterHalf;
        assertTrue(panAtOneAgain <= maxAtOne);
        float finalCenterY = viewportHeight / 2f - panAtOneAgain;
        assertEquals(543f, finalCenterY, 0.001f);
    }

    @Test public void singlePageThatFitsKeepsItsExistingCenter() {
        assertEquals(158.5f,
                NoteCanvasView.maximumViewportPanYForGeometry(
                        1403f, 1086f, 1086f, 1f, 40.5f),
                0.001f);
    }

    @Test public void wholeMultiPageDocumentThatFitsRemainsCenteredAsOneDocument() {
        assertEquals(150f,
                NoteCanvasView.maximumViewportPanYForGeometry(
                        1000f, 300f, 700f, 1f, 20f),
                0.001f);
    }

    @Test public void pageTallerThanViewportRetainsTheMinimumTopMargin() {
        assertEquals(18f,
                NoteCanvasView.maximumViewportPanYForGeometry(
                        500f, 900f, 1800f, 1f, 18f),
                0.001f);
    }

    @Test public void nonFiniteOrInvalidGeometryIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                NoteCanvasView.maximumViewportPanYForGeometry(
                        Float.NaN, 100f, 1000f, 1f, 18f));
        assertThrows(IllegalArgumentException.class, () ->
                NoteCanvasView.maximumViewportPanYForGeometry(
                        1000f, 100f, Float.POSITIVE_INFINITY, 1f, 18f));
        assertThrows(IllegalArgumentException.class, () ->
                NoteCanvasView.maximumViewportPanYForGeometry(
                        1000f, 100f, 1000f, 0f, 18f));
    }
}
