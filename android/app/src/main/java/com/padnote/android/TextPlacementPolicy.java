package com.padnote.android;

/** Single-pointer placement gate shared by the canvas input path and JVM tests. */
final class TextPlacementPolicy {
    static final class Placement {
        final int pointerId;
        final int toolType;
        final float downX;
        final float downY;
        final float worldX;
        final float worldY;

        Placement(int pointerId, int toolType, float downX, float downY,
                  float worldX, float worldY) {
            this.pointerId = pointerId;
            this.toolType = toolType;
            this.downX = downX;
            this.downY = downY;
            this.worldX = worldX;
            this.worldY = worldY;
        }
    }

    private Placement pending;

    boolean begin(int pointerId, int toolType, float downX, float downY,
                  float worldX, float worldY) {
        if (pending != null) return false;
        pending = new Placement(pointerId, toolType, downX, downY, worldX, worldY);
        return true;
    }

    Placement current() {
        return pending;
    }

    boolean cancelIfMoved(int pointerId, float x, float y, float touchSlop) {
        if (pending == null || pending.pointerId != pointerId) return false;
        if (!Float.isFinite(x) || !Float.isFinite(y) ||
                !Float.isFinite(touchSlop) || touchSlop < 0f) {
            pending = null;
            return true;
        }
        float dx = x - pending.downX;
        float dy = y - pending.downY;
        if (dx * dx + dy * dy <= touchSlop * touchSlop) return false;
        pending = null;
        return true;
    }

    Placement takeForUp(int pointerId) {
        Placement result = pending;
        pending = null;
        return result != null && result.pointerId == pointerId ? result : null;
    }

    Placement cancel() {
        Placement result = pending;
        pending = null;
        return result;
    }
}
