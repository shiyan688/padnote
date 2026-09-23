package com.padnote.android;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public class PlacementResolverTest {
    @Test public void unspecifiedWidthUsesPaperBetweenMargins() throws Exception {
        PlacementResolver resolver = new PlacementResolver(768f, 1024f, 24f, 1f, 1);

        PlacementResolver.Placement placement = resolver.resolve(
                new JSONObject().put("page", 1), null);

        assertEquals(16f, placement.xInPage, 0.01f);
        assertEquals(736f, placement.width, 0.01f);
    }

    @Test public void explicitWidthKeepsAuthoredMeaning() throws Exception {
        PlacementResolver resolver = new PlacementResolver(768f, 1024f, 24f, 1f, 1);

        PlacementResolver.Placement placement = resolver.resolve(
                new JSONObject().put("page", 1).put("widthDp", 320), null);

        assertEquals(320f, placement.width, 0.01f);
    }
}
