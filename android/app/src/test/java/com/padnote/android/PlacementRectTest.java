package com.padnote.android;

import org.junit.Test;
import static org.junit.Assert.*;

/** Pure Java geometry suite: deliberately has no android.graphics dependency. */
public final class PlacementRectTest {
    @Test public void validatesEveryBoundAndClosedSafetyEdges() {
        PlacementRect a=new PlacementRect(0,0,10,10);
        assertTrue(PlacementRect.intersects(a,new PlacementRect(12,0,20,10),2));
        assertFalse(PlacementRect.intersects(a,new PlacementRect(12.01,0,20,10),2));
        assertTrue(PlacementRect.intersects(a,new PlacementRect(Double.NaN,0,1,1),0));
        assertTrue(PlacementRect.intersects(a,new PlacementRect(0,Double.POSITIVE_INFINITY,1,1),0));
        assertTrue(PlacementRect.intersects(a,new PlacementRect(0,0,Double.NEGATIVE_INFINITY,1),0));
        assertTrue(PlacementRect.intersects(a,new PlacementRect(2,2,1,3),0));
        assertTrue(PlacementRect.intersects(a,new PlacementRect(0,0,1,1),Double.NaN));
        assertTrue(PlacementRect.intersects(a,new PlacementRect(0,0,1,1),Double.POSITIVE_INFINITY));
    }

    @Test public void arithmeticOverflowFailsClosed() {
        assertTrue(PlacementRect.intersects(
                new PlacementRect(-Double.MAX_VALUE,0,0,1),
                new PlacementRect(10,0,11,1),Double.MAX_VALUE));
    }
}
