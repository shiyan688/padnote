package com.padnote.android;

/** Pure aspect-fit geometry for the initial Agent image placement preview. */
final class AgentImagePlacementGeometry {
    static final double DEFAULT_PAGE_FRACTION = 0.84d;
    static final int DEFAULT_SLIDER_MAX = 80;

    static final class Placement {
        final double x, y, width, height;

        Placement(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    private AgentImagePlacementGeometry() {}

    static double defaultMargin(double pageWidth, double pageHeight) {
        if (!finite(pageWidth) || !finite(pageHeight) || pageWidth <= 0d || pageHeight <= 0d) {
            throw new IllegalArgumentException("invalid_paper_dimensions");
        }
        double margin = Math.min(pageWidth, pageHeight) * 0.04d;
        if (!finite(margin) || margin <= 0d) throw new IllegalArgumentException("invalid_paper_margin");
        return margin;
    }

    static Placement centeredFit(double pageWidth, double pageHeight,
                                 double imageWidth, double imageHeight,
                                 double margin, double pageFraction) {
        if (!finite(pageWidth) || !finite(pageHeight) || !finite(imageWidth) || !finite(imageHeight)
                || !finite(margin) || !finite(pageFraction) || pageWidth <= 0d || pageHeight <= 0d
                || imageWidth <= 0d || imageHeight <= 0d || margin < 0d
                || pageFraction <= 0d || pageFraction > 1d
                || pageWidth <= 2d * margin || pageHeight <= 2d * margin) {
            throw new IllegalArgumentException("invalid_image_placement_geometry");
        }
        double availableWidth = (pageWidth - 2d * margin) * pageFraction;
        double availableHeight = (pageHeight - 2d * margin) * pageFraction;
        double fit = Math.min(availableWidth / imageWidth, availableHeight / imageHeight);
        double width = imageWidth * fit;
        double height = imageHeight * fit;
        double x = (pageWidth - width) / 2d;
        double y = (pageHeight - height) / 2d;
        if (!finite(width) || !finite(height) || !finite(x) || !finite(y)
                || width <= 0d || height <= 0d || x < margin || y < margin
                || x + width > pageWidth - margin || y + height > pageHeight - margin) {
            throw new IllegalArgumentException("image_placement_out_of_bounds");
        }
        return new Placement(x, y, width, height);
    }

    /** Slider values are bounded to the fitted size, even if a caller supplies an oversized progress value. */
    static double sliderScale(int progress, int maxProgress) {
        if (maxProgress <= 0) throw new IllegalArgumentException("invalid_slider_range");
        int bounded = Math.max(0, Math.min(maxProgress, progress));
        return 0.25d + 0.75d * ((double) bounded / (double) maxProgress);
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
