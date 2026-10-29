package com.padnote.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Unit coverage for physical PDF points independently of raster pixel resolution. */
public final class PdfPageGeometryTest {
    @Test
    public void a4PresetUsesStandardPortraitAndLandscapePointBoxes() {
        PdfPageGeometry portrait = PdfPageGeometry.resolve(768f, 1086f, true, 0, 0);
        assertEquals(595, portrait.mediaWidthPt);
        assertEquals(842, portrait.mediaHeightPt);

        PdfPageGeometry landscape = PdfPageGeometry.resolve(1086f, 768f, true, 0, 0);
        assertEquals(842, landscape.mediaWidthPt);
        assertEquals(595, landscape.mediaHeightPt);
        assertContentFits(landscape);

        PdfPageGeometry square = PdfPageGeometry.resolve(800f, 800f, true, 0, 0);
        assertEquals("square canvas follows the portrait policy", 595, square.mediaWidthPt);
        assertEquals(842, square.mediaHeightPt);
    }

    @Test
    public void screenAndLegacyGeometryFitA4BoundsWithoutChangingAspectRatio() {
        PdfPageGeometry screen = PdfPageGeometry.resolve(1600f, 900f, false, 0, 0);
        assertTrue(screen.mediaWidthPt <= 842);
        assertTrue(screen.mediaHeightPt <= 595);
        assertTrue(screen.mediaWidthPt >= 1 && screen.mediaHeightPt >= 1);
        assertEquals(1600d / 900d,
                screen.mediaWidthPt / (double) screen.mediaHeightPt, 0.004d);
        assertContentFits(screen);

        // Legacy SCREEN notes use their persisted canvas ratio, not an assumed A4 shape.
        PdfPageGeometry legacy = PdfPageGeometry.resolve(1200f, 1600f, false, 0, 0);
        assertEquals(0.75d, legacy.mediaWidthPt / (double) legacy.mediaHeightPt, 0.002d);
        assertTrue(legacy.mediaWidthPt <= 595);
        assertTrue(legacy.mediaHeightPt <= 842);
        assertContentFits(legacy);
    }

    @Test
    public void firstSourcePdfPagePhysicalSizeOverridesNotePreset() {
        PdfPageGeometry geometry = PdfPageGeometry.resolve(600f, 800f, true, 792, 612);
        assertEquals(792, geometry.mediaWidthPt);
        assertEquals(612, geometry.mediaHeightPt);
        assertContentFits(geometry);
        assertEquals(600d / 800d,
                geometry.contentWidthPt / geometry.contentHeightPt, 0.002d);
    }

    @Test
    public void rasterContainmentUsesOneScaleAndCentersWithinFrozenContentBox() {
        PdfPageGeometry a4 = PdfPageGeometry.resolve(768f, 1086f, true, 0, 0);
        PdfPageGeometry.Rect raster = a4.containRaster(1273, 1800);
        assertEquals(raster.widthPt / 1273d, raster.heightPt / 1800d, 0.000001d);
        assertEquals(a4.contentLeftPt + (a4.contentWidthPt - raster.widthPt) / 2f,
                raster.leftPt, 0.001f);
        assertEquals(a4.contentTopPt + (a4.contentHeightPt - raster.heightPt) / 2f,
                raster.topPt, 0.001f);
        assertTrue(raster.leftPt >= a4.contentLeftPt && raster.topPt >= a4.contentTopPt);
        assertTrue(raster.leftPt + raster.widthPt <=
                a4.contentLeftPt + a4.contentWidthPt + 0.01f);
        assertTrue(raster.topPt + raster.heightPt <=
                a4.contentTopPt + a4.contentHeightPt + 0.01f);

        PdfPageGeometry custom = PdfPageGeometry.resolve(1001f, 777f, false, 0, 0);
        PdfPageGeometry.Rect oddRaster = custom.containRaster(2001, 999);
        assertEquals(oddRaster.widthPt / 2001d, oddRaster.heightPt / 999d, 0.000001d);
        assertEquals(custom.contentLeftPt + (custom.contentWidthPt - oddRaster.widthPt) / 2f,
                oddRaster.leftPt, 0.001f);
        assertEquals(custom.contentTopPt + (custom.contentHeightPt - oddRaster.heightPt) / 2f,
                oddRaster.topPt, 0.001f);
        assertContentFits(custom);
        assertThrows(IllegalArgumentException.class, () -> custom.containRaster(0, 999));
        assertThrows(IllegalArgumentException.class, () -> custom.containRaster(2001, -1));
    }

    @Test
    public void mappingRemainsFiniteAndPositiveAtSupportedEdge() {
        PdfPageGeometry geometry = PdfPageGeometry.resolve(1f, 14_400f, false, 0, 0);
        assertTrue(geometry.mediaWidthPt >= 1);
        assertTrue(geometry.mediaHeightPt >= 1);
        assertContentFits(geometry);
        PdfPageGeometry sourceAtCap = PdfPageGeometry.resolve(800f, 600f, false,
                14_400, 10_000);
        assertEquals(14_400, sourceAtCap.mediaWidthPt);
        assertEquals(10_000, sourceAtCap.mediaHeightPt);
        assertContentFits(sourceAtCap);
        assertThrows(IllegalArgumentException.class,
                () -> PdfPageGeometry.resolve(1f, 1f, false, 0, 14_401));
    }

    @Test
    public void invalidCanvasAndIncompleteOrInvalidSourceDimensionsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> PdfPageGeometry.resolve(Float.NaN, 100f, false, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> PdfPageGeometry.resolve(100f, Float.POSITIVE_INFINITY, false, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> PdfPageGeometry.resolve(0f, 100f, false, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> PdfPageGeometry.resolve(100f, 100f, false, 612, 0));
        assertThrows(IllegalArgumentException.class,
                () -> PdfPageGeometry.resolve(100f, 100f, false, -1, -1));
        assertThrows(IllegalArgumentException.class,
                () -> PdfPageGeometry.resolve(100f, 100f, false, 14_401, 792));
    }

    private static void assertContentFits(PdfPageGeometry geometry) {
        assertTrue(Float.isFinite(geometry.contentLeftPt));
        assertTrue(Float.isFinite(geometry.contentTopPt));
        assertTrue(Float.isFinite(geometry.contentWidthPt));
        assertTrue(Float.isFinite(geometry.contentHeightPt));
        assertTrue(geometry.contentLeftPt >= 0f);
        assertTrue(geometry.contentTopPt >= 0f);
        assertTrue(geometry.contentWidthPt > 0f);
        assertTrue(geometry.contentHeightPt > 0f);
        assertTrue(geometry.contentLeftPt + geometry.contentWidthPt <=
                geometry.mediaWidthPt + 0.01f);
        assertTrue(geometry.contentTopPt + geometry.contentHeightPt <=
                geometry.mediaHeightPt + 0.01f);
    }
}
