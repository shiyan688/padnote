package com.padnote.android;

/** Pure mapping from canonical canvas units to a physical PDF page in points. */
final class PdfPageGeometry {
    static final int A4_SHORT_EDGE_PT = 595;
    static final int A4_LONG_EDGE_PT = 842;
    static final int MAX_EDGE_PT = 14_400;

    final int mediaWidthPt;
    final int mediaHeightPt;
    final float contentLeftPt;
    final float contentTopPt;
    final float contentWidthPt;
    final float contentHeightPt;

    static final class Rect {
        final float leftPt;
        final float topPt;
        final float widthPt;
        final float heightPt;

        private Rect(float leftPt, float topPt, float widthPt, float heightPt) {
            this.leftPt = leftPt;
            this.topPt = topPt;
            this.widthPt = widthPt;
            this.heightPt = heightPt;
        }
    }

    private PdfPageGeometry(int mediaWidthPt, int mediaHeightPt,
                            float contentLeftPt, float contentTopPt,
                            float contentWidthPt, float contentHeightPt) {
        this.mediaWidthPt = mediaWidthPt;
        this.mediaHeightPt = mediaHeightPt;
        this.contentLeftPt = contentLeftPt;
        this.contentTopPt = contentTopPt;
        this.contentWidthPt = contentWidthPt;
        this.contentHeightPt = contentHeightPt;
    }

    /**
     * Resolves the physical page and a centered, aspect-preserving content box.
     * A source PDF page takes precedence over the A4 preset because imported
     * notes use one canonical canvas for every source page.
     */
    static PdfPageGeometry resolve(float canvasWidth, float canvasHeight,
                                   boolean a4Preset,
                                   int sourceWidthPt, int sourceHeightPt) {
        requireCanvasSize(canvasWidth, canvasHeight);
        boolean noSourcePage = sourceWidthPt == 0 && sourceHeightPt == 0;

        final int mediaWidth;
        final int mediaHeight;
        if (!noSourcePage) {
            requireSourceSize(sourceWidthPt, sourceHeightPt);
            mediaWidth = sourceWidthPt;
            mediaHeight = sourceHeightPt;
        } else if (a4Preset) {
            if (canvasWidth > canvasHeight) {
                mediaWidth = A4_LONG_EDGE_PT;
                mediaHeight = A4_SHORT_EDGE_PT;
            } else {
                mediaWidth = A4_SHORT_EDGE_PT;
                mediaHeight = A4_LONG_EDGE_PT;
            }
        } else {
            boolean landscape = canvasWidth > canvasHeight;
            int boundWidth = landscape ? A4_LONG_EDGE_PT : A4_SHORT_EDGE_PT;
            int boundHeight = landscape ? A4_SHORT_EDGE_PT : A4_LONG_EDGE_PT;
            double fit = Math.min(boundWidth / (double) canvasWidth,
                    boundHeight / (double) canvasHeight);
            mediaWidth = roundedPageDimension(canvasWidth * fit);
            mediaHeight = roundedPageDimension(canvasHeight * fit);
        }

        requireSourceSize(mediaWidth, mediaHeight);
        double fit = Math.min(mediaWidth / (double) canvasWidth,
                mediaHeight / (double) canvasHeight);
        float contentWidth = (float) (canvasWidth * fit);
        float contentHeight = (float) (canvasHeight * fit);
        float left = (mediaWidth - contentWidth) / 2f;
        float top = (mediaHeight - contentHeight) / 2f;
        if (!Float.isFinite(contentWidth) || !Float.isFinite(contentHeight) ||
                contentWidth <= 0f || contentHeight <= 0f ||
                !Float.isFinite(left) || !Float.isFinite(top)) {
            throw new IllegalArgumentException("Invalid PDF content geometry");
        }
        return new PdfPageGeometry(mediaWidth, mediaHeight, left, top,
                contentWidth, contentHeight);
    }

    /** Fits the final rounded raster into the content box without distortion. */
    Rect containRaster(int bitmapWidth, int bitmapHeight) {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) {
            throw new IllegalArgumentException("Invalid raster dimensions");
        }
        double scale = Math.min(contentWidthPt / (double) bitmapWidth,
                contentHeightPt / (double) bitmapHeight);
        float width = (float) (bitmapWidth * scale);
        float height = (float) (bitmapHeight * scale);
        float left = contentLeftPt + (contentWidthPt - width) / 2f;
        float top = contentTopPt + (contentHeightPt - height) / 2f;
        if (!Double.isFinite(scale) || scale <= 0d ||
                !Float.isFinite(width) || !Float.isFinite(height) ||
                !Float.isFinite(left) || !Float.isFinite(top) || width <= 0f || height <= 0f) {
            throw new IllegalArgumentException("Invalid raster contain geometry");
        }
        return new Rect(left, top, width, height);
    }

    private static int roundedPageDimension(double value) {
        if (!Double.isFinite(value) || value <= 0d || value > MAX_EDGE_PT + 0.5d) {
            throw new IllegalArgumentException("Invalid PDF page dimension");
        }
        return Math.max(1, (int) Math.round(value));
    }

    private static void requireCanvasSize(float width, float height) {
        if (!Float.isFinite(width) || !Float.isFinite(height) ||
                width <= 0f || height <= 0f) {
            throw new IllegalArgumentException("Invalid canvas page size");
        }
    }

    private static void requireSourceSize(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_EDGE_PT || height > MAX_EDGE_PT) {
            throw new IllegalArgumentException("PDF page size is outside the supported range");
        }
    }
}
