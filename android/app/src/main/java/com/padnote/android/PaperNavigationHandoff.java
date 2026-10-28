package com.padnote.android;

import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/** Routes a complete paper-frame finger stream into the existing canvas navigation state machine. */
final class PaperNavigationHandoff {
    private final NoteCanvasView canvas;
    private final Set<String> ownerIds = new LinkedHashSet<>();
    private MotionEvent lastCanvasEvent;
    private boolean active;

    PaperNavigationHandoff(NoteCanvasView canvas) {
        this.canvas = canvas;
    }

    static boolean qualifies(MotionEvent event) {
        if (event == null || event.getActionMasked() != MotionEvent.ACTION_POINTER_DOWN) {
            return false;
        }
        for (int index = 0; index < event.getPointerCount(); index++) {
            if (event.getToolType(index) != MotionEvent.TOOL_TYPE_FINGER) {
                // Preserve the canvas/text behaviour for mixed stylus, eraser,
                // mouse, and finger streams.
                return false;
            }
        }
        return event.getPointerCount() >= 2;
    }

    /** Map events through the actual View matrices, offsets, and parent scroll positions. */
    static MotionEvent transformToTarget(MotionEvent event, View source, View target) {
        if (event == null || source == null || target == null) {
            throw new IllegalArgumentException("event/source/target required");
        }
        MotionEvent copy = MotionEvent.obtain(event);
        try {
            View common = commonAncestor(source, target);
            if (common == null) throw new IllegalArgumentException("views do not share a View ancestor");
            Matrix sourceToCommon = localToAncestor(source, common);
            Matrix targetToCommon = localToAncestor(target, common);
            Matrix commonToTarget = new Matrix();
            if (!targetToCommon.invert(commonToTarget)) {
                throw new IllegalArgumentException("target transform is not invertible");
            }
            Matrix sourceToTarget = new Matrix();
            sourceToTarget.setConcat(commonToTarget, sourceToCommon);
            copy.transform(sourceToTarget);
            return copy;
        } catch (RuntimeException failure) {
            copy.recycle();
            throw failure;
        }
    }

    boolean beginFromFrame(PaperCanvasFrame frame, MotionEvent event,
                           Set<NoteTextBoxView> participatingOwners) {
        if (active || canvas == null || frame == null || participatingOwners == null ||
                participatingOwners.isEmpty()) return false;
        // The frame-to-canvas mapping is captured before cancelling children;
        // cancellation restores a dragged text view to its model geometry.
        MotionEvent mapped;
        try {
            mapped = transformToTarget(event, frame, canvas);
        } catch (RuntimeException invalidTransform) {
            // Fail closed: a gesture that crossed the navigation threshold must
            // not fall back to committing the pending text drag or button press.
            for (NoteTextBoxView owner : participatingOwners) {
                if (owner != null) owner.resetTapSequenceForNavigation();
            }
            frame.cancelChildTouchTargets(event);
            frame.discardCurrentTouchStream();
            return true;
        }
        active = true;
        for (NoteTextBoxView owner : participatingOwners) {
            NoteTextBox bound = owner == null ? null : owner.getBoundTextBox();
            if (bound != null) ownerIds.add(bound.id);
        }
        if (ownerIds.isEmpty()) {
            clearActive();
            mapped.recycle();
            frame.cancelChildTouchTargets(event);
            frame.discardCurrentTouchStream();
            return true;
        }
        try {
            for (NoteTextBoxView owner : participatingOwners) {
                if (owner != null) owner.resetTapSequenceForNavigation();
            }
            frame.cancelChildTouchTargets(event);
            dispatchMapped(mapped);
            return true;
        } catch (RuntimeException failure) {
            cancelActive();
            frame.discardCurrentTouchStream();
            return true;
        } finally {
            mapped.recycle();
        }
    }

    void dispatchFromFrame(PaperCanvasFrame frame, MotionEvent event) {
        if (!active || frame == null || canvas == null) return;
        MotionEvent mapped;
        try {
            mapped = transformToTarget(event, frame, canvas);
        } catch (RuntimeException invalidTransform) {
            cancelActive();
            frame.discardCurrentTouchStream();
            return;
        }
        try {
            dispatchMapped(mapped);
        } finally {
            mapped.recycle();
        }
        if (isTerminal(event)) clearActive();
    }

    void cancelActive() {
        if (!active) return;
        MotionEvent cancel = lastCanvasEvent == null ? null : MotionEvent.obtain(lastCanvasEvent);
        // Clear ownership before calling into the canvas: its listener can
        // synchronously update or dispose the overlay hierarchy.
        clearActive();
        if (cancel != null) {
            try {
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                canvas.onTouchEvent(cancel);
            } finally {
                cancel.recycle();
            }
        }
    }

    boolean owns(NoteTextBoxView view) {
        NoteTextBox bound = view == null ? null : view.getBoundTextBox();
        return active && bound != null && ownerIds.contains(bound.id);
    }

    boolean isActive() {
        return active;
    }

    private void dispatchMapped(MotionEvent mapped) {
        MotionEvent previous = lastCanvasEvent;
        lastCanvasEvent = MotionEvent.obtain(mapped);
        if (previous != null) previous.recycle();
        canvas.onTouchEvent(mapped);
    }

    private void clearActive() {
        active = false;
        ownerIds.clear();
        if (lastCanvasEvent != null) {
            lastCanvasEvent.recycle();
            lastCanvasEvent = null;
        }
    }

    private static boolean isTerminal(MotionEvent event) {
        return event.getActionMasked() == MotionEvent.ACTION_UP ||
                event.getActionMasked() == MotionEvent.ACTION_CANCEL;
    }

    private static View commonAncestor(View first, View second) {
        IdentityHashMap<View, Boolean> firstAncestors = new IdentityHashMap<>();
        View current = first;
        while (current != null) {
            firstAncestors.put(current, Boolean.TRUE);
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        current = second;
        while (current != null) {
            if (firstAncestors.containsKey(current)) return current;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /** Local coordinates to an ancestor using APIs present since before minSdk 24. */
    private static Matrix localToAncestor(View source, View ancestor) {
        Matrix result = new Matrix();
        View current = source;
        while (current != ancestor) {
            ViewParent parentValue = current.getParent();
            if (!(parentValue instanceof View)) {
                throw new IllegalArgumentException("ancestor is not in the source View chain");
            }
            View parent = (View) parentValue;
            Matrix step = new Matrix(current.getMatrix());
            // View.getMatrix() includes pivot, scale, rotation and translation;
            // left/top placement and parent scrolling are separate draw steps.
            step.postTranslate(current.getLeft() - parent.getScrollX(),
                    current.getTop() - parent.getScrollY());
            Matrix combined = new Matrix();
            combined.setConcat(step, result);
            result = combined;
            current = parent;
        }
        return result;
    }
}
