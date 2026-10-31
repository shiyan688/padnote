package com.padnote.android;

/** Pure Java closed-edge bounds used by placement validation and JVM tests. */
final class PlacementRect {
    final double left, top, right, bottom;
    PlacementRect(double left, double top, double right, double bottom) {
        this.left=left; this.top=top; this.right=right; this.bottom=bottom;
    }
    static boolean intersects(PlacementRect a, PlacementRect b, double safetyMargin) {
        if (a == null || b == null || !finite(safetyMargin) || safetyMargin < 0d
                || !a.valid() || !b.valid()) return true;
        double left=a.left-safetyMargin, top=a.top-safetyMargin;
        double right=a.right+safetyMargin, bottom=a.bottom+safetyMargin;
        if (!finite(left)||!finite(top)||!finite(right)||!finite(bottom)) return true;
        return left <= b.right && right >= b.left && top <= b.bottom && bottom >= b.top;
    }
    boolean valid() {
        return finite(left)&&finite(top)&&finite(right)&&finite(bottom)&&left<=right&&top<=bottom;
    }
    private static boolean finite(double value) { return !Double.isNaN(value)&&!Double.isInfinite(value); }
}
