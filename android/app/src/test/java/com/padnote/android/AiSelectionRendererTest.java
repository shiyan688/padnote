package com.padnote.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public final class AiSelectionRendererTest {
    @Test public void normalPageUsesNativeLongEdgeWhenWithinEightMegapixels() {
        assertEquals(2000, AiSelectionRenderer.pageRasterLongEdge(1200f, 2000f,
                AiSelectionRenderer.MAX_PAGE_PIXELS));
    }

    @Test public void oversizedPageIsReducedToBoundedRaster() {
        int edge = AiSelectionRenderer.pageRasterLongEdge(4000f, 3000f,
                AiSelectionRenderer.MAX_PAGE_PIXELS);
        assertTrue(edge > 0);
        long pixels = renderedPagePixels(4000f, 3000f, edge);
        assertTrue("page raster must respect the hard pixel ceiling", pixels <= 8_000_000L);
        assertTrue("page raster should use most of the bounded resolution", pixels > 7_000_000L);
    }

    @Test public void longThinPageStillRespectsPixelBudget() {
        int edge = AiSelectionRenderer.pageRasterLongEdge(12000f, 800f,
                AiSelectionRenderer.MAX_PAGE_PIXELS);
        assertTrue(edge > 0);
        long pixels = renderedPagePixels(12000f, 800f, edge);
        assertTrue(pixels <= AiSelectionRenderer.MAX_PAGE_PIXELS);
    }

    @Test public void actualFloatRasterFormulaStaysWithinBudgetAtBoundariesAndRandomSizes() {
        float[] boundaries = {Float.MIN_VALUE, 0.1f, 0.49f, 0.5f, 0.5001f, 1f, 1.01f,
                719.99994f, 720f, 1199.9999f, 1200f, 3265.98f, 4000f, 8000f,
                12000f, 100000f, 1.0e7f, Float.MAX_VALUE};
        for (float width : boundaries) {
            for (float height : boundaries) {
                assertWithinBudget(width, height);
            }
        }

        Random random = new Random(0x21A15E1L);
        for (int index = 0; index < 5000; index++) {
            float width = Math.scalb(0.5f + random.nextFloat(), random.nextInt(42) - 20);
            float height = Math.scalb(0.5f + random.nextFloat(), random.nextInt(42) - 20);
            assertWithinBudget(width, height);
        }
    }

    @Test public void cropOutputKeepsAspectAndNeverExceeds1600Edge() {
        assertArrayEquals(new int[]{1600, 800},
                AiSelectionRenderer.cropRasterSize(3200f, 1600f, 1600));
        assertArrayEquals(new int[]{700, 350},
                AiSelectionRenderer.cropRasterSize(700f, 350f, 1600));
    }


    @Test public void workerCannotRecycleBitmapWhileUiRendersText() {
        AiSelectionRenderer.PageBitmapLease lease = new AiSelectionRenderer.PageBitmapLease();
        assertTrue(lease.workerCreated());
        assertTrue(lease.transferToUi());
        assertEquals(AiSelectionRenderer.PageBitmapLease.Owner.UI, lease.owner());
        assertTrue("timeout/cancel on worker must not release a bitmap used by WebView",
                !lease.releaseFromWorker());
        assertEquals(AiSelectionRenderer.PageBitmapLease.Owner.UI, lease.owner());
        assertTrue("UI cleanup can release only after it closes the renderer", lease.releaseFromUi());
        assertEquals(AiSelectionRenderer.PageBitmapLease.Owner.RELEASED, lease.owner());
    }

    @Test public void completedUiRenderTransfersBitmapBackBeforeWorkerCrop() {
        AiSelectionRenderer.PageBitmapLease lease = new AiSelectionRenderer.PageBitmapLease();
        assertTrue(lease.workerCreated());
        assertTrue(lease.transferToUi());
        assertTrue(lease.transferToWorker());
        assertEquals(AiSelectionRenderer.PageBitmapLease.Owner.WORKER, lease.owner());
        assertTrue(lease.releaseFromWorker());
        assertEquals(AiSelectionRenderer.PageBitmapLease.Owner.RELEASED, lease.owner());
    }

    @Test public void invalidDimensionsFailClosed() {
        assertEquals(0, AiSelectionRenderer.pageRasterLongEdge(0f, 200f, 10));
        assertEquals(null, AiSelectionRenderer.cropRasterSize(Float.NaN, 2f, 100));
    }

    private static void assertWithinBudget(float width, float height) {
        int edge = AiSelectionRenderer.pageRasterLongEdge(width, height,
                AiSelectionRenderer.MAX_PAGE_PIXELS);
        assertTrue("valid page size returned no raster edge: " + width + "x" + height,
                edge > 0);
        long actual = renderedPagePixels(width, height, edge);
        assertTrue("actual float renderer exceeds 8MP: " + width + "x" + height +
                        " edge=" + edge + " pixels=" + actual,
                actual <= AiSelectionRenderer.MAX_PAGE_PIXELS);
    }

    private static long renderedPagePixels(float pageWidth, float pageHeight, int edge) {
        float longest = Math.max(pageWidth, pageHeight);
        float scale = Math.min(2f, Math.max(1f / longest,
                Math.max(1, edge) / (float) longest));
        int width = Math.max(1, Math.round(pageWidth * scale));
        int height = Math.max(1, Math.round(pageHeight * scale));
        return (long) width * height;
    }
}
