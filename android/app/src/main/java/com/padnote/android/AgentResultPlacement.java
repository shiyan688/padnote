package com.padnote.android;

import android.graphics.RectF;

/** Runtime-only placement request and conservative page-space collision helpers. */
final class AgentResultPlacement {
    enum Mode { APPEND, POINT }

    final Mode mode;
    final int pageIndex;
    final double xInPage;
    final double yInPage;
    final double width;

    private AgentResultPlacement(Mode mode, int pageIndex, double x, double y, double width) {
        this.mode = mode;
        this.pageIndex = pageIndex;
        this.xInPage = finite(x, "xInPage");
        this.yInPage = finite(y, "yInPage");
        this.width = finite(width, "width");
        if (pageIndex < 0 || width < 0d) throw new IllegalArgumentException("placement out of range");
    }

    static AgentResultPlacement append() { return new AgentResultPlacement(Mode.APPEND, 0, 0, 0, 0); }
    static AgentResultPlacement point(int pageIndex, double x, double y, double width) {
        return new AgentResultPlacement(Mode.POINT, pageIndex, x, y, width);
    }

    static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
    private static double finite(double value, String name) {
        if (!finite(value)) throw new IllegalArgumentException(name + " must be finite");
        return value;
    }

    static boolean intersects(RectF a, RectF b, float safetyMargin) {
        if (a == null || b == null) return true;
        return PlacementRect.intersects(new PlacementRect(a.left,a.top,a.right,a.bottom),
                new PlacementRect(b.left,b.top,b.right,b.bottom), safetyMargin);
    }
}
