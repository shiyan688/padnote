package com.padnote.android;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AgentResultPlacementTest {
    @Test public void pointRequestRequiresFiniteGeometryAndKeepsPageCoordinates() {
        AgentResultPlacement placement = AgentResultPlacement.point(3, 42.5, 80, 190);
        assertEquals(AgentResultPlacement.Mode.POINT, placement.mode);
        assertEquals(3, placement.pageIndex);
        assertEquals(42.5, placement.xInPage, 0d);
        assertThrows(IllegalArgumentException.class,
                () -> AgentResultPlacement.point(0, Double.NaN, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> AgentResultPlacement.point(-1, 1, 1, 1));
    }

    @Test public void previewHandleCancellationIsMonotonic() {
        NoteCanvasView.AgentPreviewHandle handle=new NoteCanvasView.AgentPreviewHandle();
        assertFalse(handle.isCancelled());
        handle.cancel();
        assertTrue(handle.isCancelled());
        handle.cancel();
        assertTrue(handle.isCancelled());
    }


}
