package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TextPlacementPolicyTest {
    @Test
    public void placementCommitsOnlyForItsPointerAndKeepsDownWorldCoordinate() {
        TextPlacementPolicy policy = new TextPlacementPolicy();
        assertTrue(policy.begin(7, 1, 20f, 30f, 120f, 230f));
        assertFalse(policy.begin(8, 1, 40f, 50f, 140f, 250f));
        assertNull(policy.takeForUp(8));
        // A mismatched terminal also consumes the stale candidate.
        assertNull(policy.current());

        assertTrue(policy.begin(9, 1, 20f, 30f, 120f, 230f));
        TextPlacementPolicy.Placement placement = policy.takeForUp(9);
        assertEquals(9, placement.pointerId);
        assertEquals(120f, placement.worldX, 0f);
        assertEquals(230f, placement.worldY, 0f);
        assertNull(policy.current());
    }

    @Test
    public void movementBeyondSlopCancelsButMovementAtSlopRemainsTap() {
        TextPlacementPolicy policy = new TextPlacementPolicy();
        policy.begin(2, 1, 100f, 100f, 300f, 300f);
        assertFalse(policy.cancelIfMoved(2, 103f, 104f, 5f));
        assertTrue(policy.cancelIfMoved(2, 103.01f, 104f, 5f));
        assertNull(policy.current());
        assertNull(policy.takeForUp(2));
    }

    @Test
    public void cancelAndToolChangeResetPendingPlacement() {
        TextPlacementPolicy policy = new TextPlacementPolicy();
        policy.begin(4, 2, 10f, 11f, 210f, 211f);
        TextPlacementPolicy.Placement canceled = policy.cancel();
        assertNotNull(canceled);
        assertEquals(4, canceled.pointerId);
        assertEquals(2, canceled.toolType);
        assertNull(policy.current());
        assertFalse(policy.cancelIfMoved(4, 80f, 80f, 4f));
    }
}
