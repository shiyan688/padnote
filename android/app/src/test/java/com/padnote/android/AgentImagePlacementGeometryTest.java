package com.padnote.android;

import org.junit.Test;

import static org.junit.Assert.*;

public final class AgentImagePlacementGeometryTest {
    private static final double PAGE_W = 612d;
    private static final double PAGE_H = 792d;
    private static final double MARGIN = 20d;
    private static final double EPSILON = 1e-9d;

    @Test public void portraitLandscapeTallAndWideImagesAspectFitInsideReadableBounds() {
        assertFit(720d, 1080d, PAGE_W, PAGE_H, 0.84d);
        assertFit(1200d, 800d, 842d, 595d, 0.84d);
        assertFit(300d, 2400d, PAGE_W, PAGE_H, 0.84d);
        assertFit(2400d, 300d, 842d, 595d, 0.84d);
    }

    @Test public void defaultFitUsesMostOfTheAvailablePaperWithoutTouchingMargins() {
        AgentImagePlacementGeometry.Placement placement = AgentImagePlacementGeometry.centeredFit(
                PAGE_W, PAGE_H, 720d, 1080d, MARGIN,
                AgentImagePlacementGeometry.DEFAULT_PAGE_FRACTION);
        double availableHeight = (PAGE_H - 2d * MARGIN) * 0.84d;
        assertEquals(availableHeight, placement.height, EPSILON);
        assertEquals(PAGE_W / 2d, placement.x + placement.width / 2d, EPSILON);
        assertEquals(PAGE_H / 2d, placement.y + placement.height / 2d, EPSILON);
        assertTrue(placement.x >= MARGIN);
        assertTrue(placement.y >= MARGIN);
    }

    @Test public void marginAndPlacementScaleWithPaperInsteadOfUsingAnAbsoluteFixedInset() {
        double margin = AgentImagePlacementGeometry.defaultMargin(PAGE_W, PAGE_H);
        assertEquals(Math.min(PAGE_W, PAGE_H) * 0.04d, margin, EPSILON);
        AgentImagePlacementGeometry.Placement base = AgentImagePlacementGeometry.centeredFit(
                PAGE_W, PAGE_H, 720d, 1080d, margin, AgentImagePlacementGeometry.DEFAULT_PAGE_FRACTION);
        for (double factor : new double[]{0.5d, 1.75d, 3d}) {
            double scaledPageWidth = PAGE_W * factor;
            double scaledPageHeight = PAGE_H * factor;
            double scaledMargin = AgentImagePlacementGeometry.defaultMargin(scaledPageWidth, scaledPageHeight);
            AgentImagePlacementGeometry.Placement scaled = AgentImagePlacementGeometry.centeredFit(
                    scaledPageWidth, scaledPageHeight, 720d * factor, 1080d * factor,
                    scaledMargin, AgentImagePlacementGeometry.DEFAULT_PAGE_FRACTION);
            assertEquals(base.x * factor, scaled.x, EPSILON);
            assertEquals(base.y * factor, scaled.y, EPSILON);
            assertEquals(base.width * factor, scaled.width, EPSILON);
            assertEquals(base.height * factor, scaled.height, EPSILON);
            assertTrue(scaled.x >= scaledMargin && scaled.y >= scaledMargin);
            assertTrue(scaled.x + scaled.width <= scaledPageWidth - scaledMargin + EPSILON);
            assertTrue(scaled.y + scaled.height <= scaledPageHeight - scaledMargin + EPSILON);
        }
    }

    @Test public void tinyPositivePaperStillFitsAnImageInsideProportionalMargins() {
        double pageWidth = 1d, pageHeight = 0.5d;
        double margin = AgentImagePlacementGeometry.defaultMargin(pageWidth, pageHeight);
        AgentImagePlacementGeometry.Placement placement = AgentImagePlacementGeometry.centeredFit(
                pageWidth, pageHeight, 100d, 50d, margin,
                AgentImagePlacementGeometry.DEFAULT_PAGE_FRACTION);
        assertTrue(placement.width > 0d && placement.height > 0d);
        assertTrue(placement.x >= margin && placement.y >= margin);
        assertTrue(placement.x + placement.width <= pageWidth - margin);
        assertTrue(placement.y + placement.height <= pageHeight - margin);
    }

    @Test public void sliderNeverGrowsPastFitEvenForOutOfRangeProgress() {
        assertEquals(0.25d, AgentImagePlacementGeometry.sliderScale(-1, 80), EPSILON);
        assertEquals(1d, AgentImagePlacementGeometry.sliderScale(80, 80), EPSILON);
        assertEquals(1d, AgentImagePlacementGeometry.sliderScale(100, 80), EPSILON);
        assertEquals(0.25d, AgentImagePlacementGeometry.sliderScale(0, 80), EPSILON);
    }

    @Test public void rejectsInvalidOrNonFiniteDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentImagePlacementGeometry.centeredFit(PAGE_W, PAGE_H, 0d, 100d, MARGIN, 0.84d));
        assertThrows(IllegalArgumentException.class,
                () -> AgentImagePlacementGeometry.centeredFit(PAGE_W, PAGE_H, Double.NaN, 100d, MARGIN, 0.84d));
        assertThrows(IllegalArgumentException.class,
                () -> AgentImagePlacementGeometry.centeredFit(40d, PAGE_H, 100d, 100d, MARGIN, 0.84d));
        assertThrows(IllegalArgumentException.class,
                () -> AgentImagePlacementGeometry.centeredFit(PAGE_W, PAGE_H, 100d, 100d, MARGIN, 1.01d));
        assertThrows(IllegalArgumentException.class,
                () -> AgentImagePlacementGeometry.sliderScale(1, 0));
    }

    private static void assertFit(double imageWidth, double imageHeight,
                                  double pageWidth, double pageHeight, double fraction) {
        AgentImagePlacementGeometry.Placement placement = AgentImagePlacementGeometry.centeredFit(
                pageWidth, pageHeight, imageWidth, imageHeight, MARGIN, fraction);
        assertTrue(placement.width > 0d && placement.height > 0d);
        assertTrue(placement.x >= MARGIN && placement.y >= MARGIN);
        assertTrue(placement.x + placement.width <= pageWidth - MARGIN + EPSILON);
        assertTrue(placement.y + placement.height <= pageHeight - MARGIN + EPSILON);
        assertTrue(placement.width <= (pageWidth - 2d * MARGIN) * fraction + EPSILON);
        assertTrue(placement.height <= (pageHeight - 2d * MARGIN) * fraction + EPSILON);
        assertEquals(imageWidth / imageHeight, placement.width / placement.height, EPSILON);
    }
}
