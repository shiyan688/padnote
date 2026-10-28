package com.padnote.android;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Parent dispatch boundary for paper overlays and the canvas. */
final class PaperCanvasFrame extends FrameLayout {
    private final Map<Integer, NoteTextBoxView> pointerTextOwners = new LinkedHashMap<>();
    private final Set<Integer> blockedByInlineEditor = new LinkedHashSet<>();
    private final Set<Integer> seenFingerPointers = new LinkedHashSet<>();
    private PaperNavigationHandoff navigationHandoff;
    private boolean mixedToolStreamSeen;
    private boolean discardUntilTerminal;

    PaperCanvasFrame(Context context) {
        super(context);
        setMotionEventSplittingEnabled(true);
    }

    void setNavigationHandoff(PaperNavigationHandoff handoff) {
        navigationHandoff = handoff;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (discardUntilTerminal) {
            if (isTerminal(event)) {
                discardUntilTerminal = false;
                pointerTextOwners.clear();
                blockedByInlineEditor.clear();
                seenFingerPointers.clear();
                mixedToolStreamSeen = false;
            }
            return true;
        }
        if (navigationHandoff != null && navigationHandoff.isActive()) {
            navigationHandoff.dispatchFromFrame(this, event);
            if (isTerminal(event)) {
                pointerTextOwners.clear();
                blockedByInlineEditor.clear();
                seenFingerPointers.clear();
            }
            return true;
        }

        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN) {
            pointerTextOwners.clear();
            blockedByInlineEditor.clear();
            seenFingerPointers.clear();
            mixedToolStreamSeen = false;
        }
        for (int index = 0; index < event.getPointerCount(); index++) {
            if (event.getToolType(index) != MotionEvent.TOOL_TYPE_FINGER) {
                mixedToolStreamSeen = true;
            }
        }
        if (action == MotionEvent.ACTION_DOWN) {
            rememberTextOwner(event, actionIndex);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            // Origins belong to the pointer's initial DOWN only. An existing
            // canvas pointer that later crosses an inline editor must remain a
            // canvas pointer for this stream.
            rememberTextOwner(event, actionIndex);
            if (!mixedToolStreamSeen && PaperNavigationHandoff.qualifies(event) &&
                    blockedByInlineEditor.isEmpty() && !pointerTextOwners.isEmpty() &&
                    navigationHandoff != null) {
                Set<NoteTextBoxView> owners = new LinkedHashSet<>(pointerTextOwners.values());
                if (navigationHandoff.beginFromFrame(this, event, owners)) {
                    pointerTextOwners.clear();
                    return true;
                }
            }
        }

        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_POINTER_UP) {
            pointerTextOwners.remove(event.getPointerId(actionIndex));
            blockedByInlineEditor.remove(event.getPointerId(actionIndex));
            seenFingerPointers.remove(event.getPointerId(actionIndex));
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            pointerTextOwners.clear();
            blockedByInlineEditor.clear();
            seenFingerPointers.clear();
            mixedToolStreamSeen = false;
        }
        return handled;
    }

    /** Cancel every split child target before injecting navigation into the canvas. */
    void cancelChildTouchTargets(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        try {
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
    }

    void discardCurrentTouchStream() {
        discardUntilTerminal = true;
        pointerTextOwners.clear();
        blockedByInlineEditor.clear();
        seenFingerPointers.clear();
    }

    boolean isDiscardingTouchStream() {
        return discardUntilTerminal;
    }

    private void rememberTextOwner(MotionEvent event, int pointerIndex) {
        if (event.getToolType(pointerIndex) != MotionEvent.TOOL_TYPE_FINGER) return;
        int pointerId = event.getPointerId(pointerIndex);
        if (!seenFingerPointers.add(pointerId)) return;
        TextHit hit = textAt(event, pointerIndex);
        if (hit.inlineEditor) {
            blockedByInlineEditor.add(pointerId);
        } else if (hit.owner != null) {
            pointerTextOwners.put(pointerId, hit.owner);
        }
    }

    private TextHit textAt(MotionEvent event, int pointerIndex) {
        // Children are dispatched from topmost to bottommost; mirror that order so
        // an overlapping selected object matches the actual hit target.
        for (int childIndex = getChildCount() - 1; childIndex >= 0; childIndex--) {
            View child = getChildAt(childIndex);
            if (!(child instanceof NoteTextBoxView)) continue;
            NoteTextBoxView text = (NoteTextBoxView) child;
            if (text.getVisibility() != VISIBLE ||
                    (!text.isPaperNavigationTarget() && !text.isInlineEditing())) continue;
            MotionEvent local;
            try {
                local = PaperNavigationHandoff.transformToTarget(event, this, text);
            } catch (RuntimeException failure) {
                continue;
            }
            try {
                float x = local.getX(pointerIndex);
                float y = local.getY(pointerIndex);
                if (x >= 0f && x < text.getWidth() && y >= 0f && y < text.getHeight()) {
                    if (text.isInlineEditing()) return new TextHit(null, true);
                    if (text.isPaperNavigationTarget()) return new TextHit(text, false);
                }
            } finally {
                local.recycle();
            }
        }
        return TextHit.NONE;
    }

    private static final class TextHit {
        static final TextHit NONE = new TextHit(null, false);
        final NoteTextBoxView owner;
        final boolean inlineEditor;

        TextHit(NoteTextBoxView owner, boolean inlineEditor) {
            this.owner = owner;
            this.inlineEditor = inlineEditor;
        }
    }

    private static boolean isTerminal(MotionEvent event) {
        return event.getActionMasked() == MotionEvent.ACTION_UP ||
                event.getActionMasked() == MotionEvent.ACTION_CANCEL;
    }
}
