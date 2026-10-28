package com.padnote.android;

import android.content.Context;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class TextOverlayNavigationInstrumentedTest {
    @Test
    public void selectedTextPinchCancelsDragAndRemainingFingerStillPansCanvas() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            try {
                int undoDepthBefore = fixture.canvas.undoDepthForTest();
                long editSerialBefore = fixture.editSerial();
                long firstTap = SystemClock.uptimeMillis();
                fixture.send(firstTap, firstTap, MotionEvent.ACTION_DOWN,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{170}, new float[]{100});
                fixture.send(firstTap, firstTap + 10, MotionEvent.ACTION_UP,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{170}, new float[]{100});
                assertEquals(0, fixture.listener.editCount);

                long down = firstTap + 50;
                fixture.send(down, down, MotionEvent.ACTION_DOWN,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{120}, new float[]{100});
                fixture.send(down, down + 20, MotionEvent.ACTION_MOVE,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{130}, new float[]{100});
                assertTrue(fixture.listener.previewCount > 0);

                fixture.send(down, down + 30,
                        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{130, 230}, new float[]{100, 100});

                assertTrue(fixture.handoff.isActive());
                assertTrue(fixture.handoff.owns(fixture.view));
                assertEquals(0, fixture.listener.moveCount);
                assertEquals(0, fixture.listener.resizeCount);
                assertEquals(0, fixture.listener.scaleCount);
                assertEquals(0, fixture.listener.fontChangeCount);
                assertTrue(fixture.listener.previewEndedCount > 0);
                assertEquals(fixture.originalSource, fixture.model.source);
                assertEquals(fixture.model.x, fixture.view.getX(), 0.01f);
                assertEquals("a canceled object gesture must not become a double tap",
                        0, fixture.listener.editCount);

                // The overlay is deliberately transformed and offset from the
                // canvas. A 20px local span increase at scaleX=1.5 reaches the
                // canvas as a 30px span increase (150 -> 180), i.e. 1.2x zoom.
                fixture.send(down, down + 50, MotionEvent.ACTION_MOVE,
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{120, 240}, new float[]{100, 100});
                assertEquals(1.2f, fixture.canvas.getViewportScale(), 0.03f);
                float panBeforeOneFinger = fixture.canvas.getViewportPanX();

                fixture.send(down, down + 60,
                        MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{120, 240}, new float[]{100, 100});
                fixture.send(down, down + 80, MotionEvent.ACTION_MOVE,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{130}, new float[]{110});
                assertTrue("remaining finger must stay in canvas navigation",
                        Math.abs(fixture.canvas.getViewportPanX() - panBeforeOneFinger) > 1f);
                assertTrue(fixture.handoff.isActive());
                assertEquals(0, fixture.listener.moveCount);

                fixture.send(down, down + 90, MotionEvent.ACTION_UP,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{130}, new float[]{110});
                assertFalse(fixture.handoff.isActive());
                assertFalse(fixture.handoff.owns(fixture.view));
                assertFalse(fixture.canvas.isUserInteractionActive());
                assertEquals(fixture.originalSource, fixture.model.source);
                assertEquals(0, fixture.listener.moveCount);
                assertEquals(0, fixture.listener.resizeCount);
                assertEquals(0, fixture.listener.scaleCount);
                assertEquals(undoDepthBefore, fixture.canvas.undoDepthForTest());
                assertEquals(editSerialBefore, fixture.editSerial());
                long afterNavigationTap = down + 100;
                fixture.send(afterNavigationTap, afterNavigationTap, MotionEvent.ACTION_DOWN,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{170}, new float[]{100});
                fixture.send(afterNavigationTap, afterNavigationTap + 10, MotionEvent.ACTION_UP,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{170}, new float[]{100});
                assertEquals(0, fixture.listener.editCount);
            } finally {
                fixture.close();
            }
        });
    }

    @Test
    public void cancellationInlineEditingAndMixedToolsDoNotLeakHandoff() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            try {
                long down = SystemClock.uptimeMillis();
                fixture.send(down, down, MotionEvent.ACTION_DOWN,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{170}, new float[]{100});
                fixture.send(down, down + 10,
                        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{170, 250}, new float[]{100, 100});
                assertTrue(fixture.handoff.owns(fixture.view));
                fixture.send(down, down + 20, MotionEvent.ACTION_CANCEL,
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{170, 250}, new float[]{100, 100});
                assertFalse(fixture.handoff.isActive());
                assertFalse(fixture.handoff.owns(fixture.view));
                assertFalse(fixture.canvas.isUserInteractionActive());

                for (int mixedType : new int[]{MotionEvent.TOOL_TYPE_STYLUS,
                        MotionEvent.TOOL_TYPE_ERASER, MotionEvent.TOOL_TYPE_MOUSE}) {
                    long mixedDown = SystemClock.uptimeMillis();
                    fixture.send(mixedDown, mixedDown, MotionEvent.ACTION_DOWN,
                            new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                            new float[]{170}, new float[]{100});
                    fixture.send(mixedDown, mixedDown + 10,
                            MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                            new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER, mixedType},
                            new float[]{170, 250}, new float[]{100, 100});
                    fixture.send(mixedDown, mixedDown + 20,
                            MotionEvent.ACTION_POINTER_DOWN | (2 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                            new int[]{0, 1, 2}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                    mixedType, MotionEvent.TOOL_TYPE_FINGER},
                            new float[]{170, 250, 300}, new float[]{100, 100, 100});
                    fixture.send(mixedDown, mixedDown + 25,
                            MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                            new int[]{0, 1, 2}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                    mixedType, MotionEvent.TOOL_TYPE_FINGER},
                            new float[]{170, 250, 300}, new float[]{100, 100, 100});
                    fixture.send(mixedDown, mixedDown + 27,
                            MotionEvent.ACTION_POINTER_DOWN | (2 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                            new int[]{0, 2, 3}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                    MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_FINGER},
                            new float[]{170, 300, 340}, new float[]{100, 100, 100});
                    assertFalse(fixture.handoff.isActive());
                    fixture.send(mixedDown, mixedDown + 30, MotionEvent.ACTION_CANCEL,
                            new int[]{0, 1, 2}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                    mixedType, MotionEvent.TOOL_TYPE_FINGER},
                            new float[]{170, 250, 300}, new float[]{100, 100, 100});
                }

                fixture.view.beginInlineEditing();
                long editorDown = SystemClock.uptimeMillis();
                fixture.send(editorDown, editorDown, MotionEvent.ACTION_DOWN,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{170}, new float[]{100});
                fixture.send(editorDown, editorDown + 10,
                        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{170, 250}, new float[]{100, 100});
                assertFalse(fixture.handoff.isActive());
                assertFalse(fixture.handoff.owns(fixture.view));
                fixture.send(editorDown, editorDown + 20, MotionEvent.ACTION_CANCEL,
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{170, 250}, new float[]{100, 100});
            } finally {
                fixture.close();
            }
        });
    }

    @Test
    public void secondFingerCancelsPressedFontControlBeforeItCanClick() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            try {
                float[] plusPoint = fixture.fontPlusCenter();
                float plusX = plusPoint[0];
                float plusY = plusPoint[1];
                float density = fixture.context.getResources().getDisplayMetrics().density;
                long down = SystemClock.uptimeMillis();
                fixture.sendFrame(down, down, MotionEvent.ACTION_DOWN,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{plusX}, new float[]{plusY});
                fixture.sendFrame(down, down + 20,
                        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{plusX, plusX + 30f * density},
                        new float[]{plusY, plusY});
                fixture.sendFrame(down, down + 40, MotionEvent.ACTION_MOVE,
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{plusX - 5f * density, plusX + 35f * density},
                        new float[]{plusY, plusY});
                fixture.sendFrame(down, down + 50,
                        MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{plusX - 5f * density, plusX + 35f * density},
                        new float[]{plusY, plusY});
                fixture.sendFrame(down, down + 60, MotionEvent.ACTION_UP,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{plusX - 5f * density}, new float[]{plusY});
                assertEquals(0, fixture.listener.fontChangeCount);
                assertEquals(0, fixture.listener.moveCount);
                assertEquals(fixture.originalSource, fixture.model.source);
            } finally {
                fixture.close();
            }
        });
    }

    @Test
    public void streamSplitBetweenTwoTextFragmentsTracksBothOriginsUntilCancel() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            NoteTextBoxView second = fixture.addSecondTextView(650f);
            try {
                float[] firstPoint = fixture.pointInFrame(fixture.view, 170f, 100f);
                float[] secondPoint = fixture.pointInFrame(second, 100f, 100f);
                long down = SystemClock.uptimeMillis();
                fixture.sendFrame(down, down, MotionEvent.ACTION_DOWN,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{firstPoint[0]}, new float[]{firstPoint[1]});
                fixture.sendFrame(down, down + 10,
                        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{firstPoint[0], secondPoint[0]},
                        new float[]{firstPoint[1], secondPoint[1]});
                assertTrue(fixture.handoff.isActive());
                assertTrue(fixture.handoff.owns(fixture.view));
                assertTrue(fixture.handoff.owns(second));
                fixture.sendFrame(down, down + 20, MotionEvent.ACTION_MOVE,
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{firstPoint[0] - 30f, secondPoint[0] + 30f},
                        new float[]{firstPoint[1], secondPoint[1]});
                assertTrue(fixture.canvas.getViewportScale() > 1.05f);
                fixture.sendFrame(down, down + 30, MotionEvent.ACTION_CANCEL,
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{firstPoint[0] - 30f, secondPoint[0] + 30f},
                        new float[]{firstPoint[1], secondPoint[1]});
                assertFalse(fixture.handoff.isActive());
                assertFalse(fixture.canvas.isUserInteractionActive());
                assertEquals(fixture.originalSource, fixture.model.source);
                assertEquals(0, fixture.listener.moveCount);
            } finally {
                second.dispose();
                fixture.frame.removeView(second);
                fixture.close();
            }
        });
    }

    @Test
    public void streamSplitBetweenSelectedTextAndBareCanvasStillNavigates() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            try {
                float[] textPoint = fixture.pointInFrame(fixture.view, 170f, 100f);
                long down = SystemClock.uptimeMillis();
                fixture.sendFrame(down, down, MotionEvent.ACTION_DOWN,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{textPoint[0]}, new float[]{textPoint[1]});
                fixture.sendFrame(down, down + 10,
                        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{textPoint[0], 800f}, new float[]{textPoint[1], 600f});
                assertTrue(fixture.handoff.isActive());
                assertTrue(fixture.handoff.owns(fixture.view));
                fixture.sendFrame(down, down + 20, MotionEvent.ACTION_MOVE,
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{textPoint[0], 850f}, new float[]{textPoint[1], 650f});
                assertTrue(fixture.canvas.getViewportScale() > 1.01f);
                fixture.sendFrame(down, down + 30, MotionEvent.ACTION_CANCEL,
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{textPoint[0], 850f}, new float[]{textPoint[1], 650f});
                assertFalse(fixture.handoff.isActive());
                assertFalse(fixture.canvas.isUserInteractionActive());
                assertEquals(fixture.originalSource, fixture.model.source);
                assertEquals(0, fixture.listener.moveCount);
            } finally {
                fixture.close();
            }
        });
    }

    @Test
    public void streamCanStartOnCanvasThenReachSelectedTextOverlay() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            try {
                float[] textPoint = fixture.pointInFrame(fixture.view, 170f, 100f);
                long down = SystemClock.uptimeMillis();
                fixture.sendFrame(down, down, MotionEvent.ACTION_DOWN,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{800f}, new float[]{600f});
                assertTrue(fixture.canvas.isUserInteractionActive());
                fixture.sendFrame(down, down + 10,
                        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{800f, textPoint[0]}, new float[]{600f, textPoint[1]});
                assertTrue(fixture.handoff.isActive());
                assertTrue(fixture.handoff.owns(fixture.view));
                fixture.sendFrame(down, down + 20, MotionEvent.ACTION_MOVE,
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{820f, textPoint[0] - 20f},
                        new float[]{620f, textPoint[1]});
                assertTrue(fixture.canvas.getViewportScale() > 1f);
                fixture.sendFrame(down, down + 30, MotionEvent.ACTION_CANCEL,
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{820f, textPoint[0] - 20f},
                        new float[]{620f, textPoint[1]});
                assertFalse(fixture.handoff.isActive());
                assertFalse(fixture.canvas.isUserInteractionActive());
                assertEquals(fixture.originalSource, fixture.model.source);
                assertEquals(0, fixture.listener.moveCount);
            } finally {
                fixture.close();
            }
        });
    }

    @Test
    public void inlineTextPointerBlocksCrossChildHandoff() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            NoteTextBoxView second = fixture.addSecondTextView(750f);
            try {
                fixture.view.beginInlineEditing();
                float[] inlinePoint = fixture.pointInFrame(fixture.view, 170f, 100f);
                float[] secondPoint = fixture.pointInFrame(second, 100f, 100f);
                long down = SystemClock.uptimeMillis();
                fixture.sendFrame(down, down, MotionEvent.ACTION_DOWN,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{inlinePoint[0]}, new float[]{inlinePoint[1]});
                fixture.sendFrame(down, down + 10,
                        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{inlinePoint[0], secondPoint[0]},
                        new float[]{inlinePoint[1], secondPoint[1]});
                assertFalse(fixture.handoff.isActive());
                fixture.sendFrame(down, down + 20, MotionEvent.ACTION_CANCEL,
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{inlinePoint[0], secondPoint[0]},
                        new float[]{inlinePoint[1], secondPoint[1]});
                assertFalse(fixture.handoff.isActive());
            } finally {
                second.dispose();
                fixture.frame.removeView(second);
                fixture.close();
            }
        });
    }

    @Test
    public void singularCanvasTransformFailsClosedWithoutCommittingTextGesture() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            try {
                int undoDepthBefore = fixture.canvas.undoDepthForTest();
                long editSerialBefore = fixture.editSerial();
                float originalX = fixture.view.getX();
                float originalY = fixture.view.getY();
                fixture.canvas.setScaleX(0f);

                long down = SystemClock.uptimeMillis();
                fixture.send(down, down, MotionEvent.ACTION_DOWN,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{120}, new float[]{100});
                fixture.send(down, down + 20, MotionEvent.ACTION_MOVE,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{135}, new float[]{105});
                assertTrue("fixture must create an uncommitted drag preview",
                        fixture.listener.previewCount > 0);

                fixture.send(down, down + 30,
                        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{135, 235}, new float[]{105, 105});

                assertFalse(fixture.handoff.isActive());
                assertTrue("singular frame-to-canvas map must discard through terminal",
                        fixture.frame.isDiscardingTouchStream());
                assertEquals(originalX, fixture.view.getX(), 0.01f);
                assertEquals(originalY, fixture.view.getY(), 0.01f);
                assertTrue(fixture.listener.previewEndedCount > 0);
                assertEquals(0, fixture.listener.moveCount);
                assertEquals(0, fixture.listener.resizeCount);
                assertEquals(0, fixture.listener.fontChangeCount);
                assertEquals(0, fixture.listener.editCount);

                fixture.sendFrame(down, down + 40, MotionEvent.ACTION_MOVE,
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{130, 250}, new float[]{100, 105});
                fixture.sendFrame(down, down + 50,
                        MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{130, 250}, new float[]{100, 105});
                fixture.sendFrame(down, down + 60, MotionEvent.ACTION_UP,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{130}, new float[]{100});

                assertFalse(fixture.frame.isDiscardingTouchStream());
                assertFalse(fixture.handoff.isActive());
                assertEquals(fixture.originalSource, fixture.model.source);
                assertEquals(0, fixture.listener.moveCount);
                assertEquals(0, fixture.listener.resizeCount);
                assertEquals(0, fixture.listener.fontChangeCount);
                assertEquals(0, fixture.listener.editCount);
                assertEquals(undoDepthBefore, fixture.canvas.undoDepthForTest());
                assertEquals(editSerialBefore, fixture.editSerial());
            } finally {
                fixture.close();
            }
        });
    }

    @Test
    public void canvasOriginPointerMovingAcrossInlineEditorDoesNotBecomeBlocked() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            NoteTextBoxView second = fixture.addSecondTextView(750f);
            try {
                fixture.view.beginInlineEditing();
                float[] inlinePoint = fixture.pointInFrame(fixture.view, 170f, 100f);
                float[] selectedPoint = fixture.pointInFrame(second, 100f, 100f);
                long down = SystemClock.uptimeMillis();
                fixture.sendFrame(down, down, MotionEvent.ACTION_DOWN,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{800f}, new float[]{600f});
                fixture.sendFrame(down, down + 10, MotionEvent.ACTION_MOVE,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{inlinePoint[0]}, new float[]{inlinePoint[1]});
                fixture.sendFrame(down, down + 20,
                        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{inlinePoint[0], selectedPoint[0]},
                        new float[]{inlinePoint[1], selectedPoint[1]});

                assertTrue("originating on canvas must stay eligible after crossing inline text",
                        fixture.handoff.isActive());
                assertTrue(fixture.handoff.owns(second));
                fixture.sendFrame(down, down + 30, MotionEvent.ACTION_MOVE,
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{inlinePoint[0] - 20f, selectedPoint[0] + 20f},
                        new float[]{inlinePoint[1], selectedPoint[1]});
                assertTrue(fixture.canvas.getViewportScale() > 1.01f);
                fixture.sendFrame(down, down + 40, MotionEvent.ACTION_CANCEL,
                        new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                                MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{inlinePoint[0] - 20f, selectedPoint[0] + 20f},
                        new float[]{inlinePoint[1], selectedPoint[1]});
                assertFalse(fixture.handoff.isActive());
                assertFalse(fixture.canvas.isUserInteractionActive());
                assertEquals(fixture.originalSource, fixture.model.source);
                assertEquals(0, fixture.listener.moveCount);
            } finally {
                second.dispose();
                fixture.frame.removeView(second);
                fixture.close();
            }
        });
    }

    private static final class Fixture {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final FrameLayout root = new FrameLayout(context);
        final PaperCanvasFrame frame = new PaperCanvasFrame(context);
        final NoteCanvasView canvas = new NoteCanvasView(context);
        final ListenerSpy listener = new ListenerSpy();
        final NoteTextBox model = new NoteTextBox("fragment-1", "flow-1", 0, 1,
                NoteTextBox.Format.MARKDOWN, "Synthetic paragraph for navigation QA.",
                "Synthetic paragraph for navigation QA.", 16f,
                TextFlow.DEFAULT_LINE_HEIGHT, 0, 120f, 140f, 350f, 220f);
        final String originalSource = model.source;
        final NoteTextBoxView view = new NoteTextBoxView(context, listener);
        final PaperNavigationHandoff handoff = new PaperNavigationHandoff(canvas);

        Fixture() {
            canvas.setListener(new CanvasListenerSpy());
            canvas.setEnabled(false);
            root.addView(frame, new FrameLayout.LayoutParams(1000, 900));
            frame.addView(canvas, new FrameLayout.LayoutParams(900, 800));
            frame.addView(view, new FrameLayout.LayoutParams(350, 220));
            root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1000, 900);
            frame.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
            frame.layout(0, 0, 1000, 900);
            frame.setX(70f);
            frame.setY(90f);
            canvas.setX(40f);
            canvas.setY(50f);
            view.bind(model, 1f, 0f, 0f, true);
            view.measure(View.MeasureSpec.makeMeasureSpec(350, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, 350, 220);
            view.setScaleX(1.5f);
            view.setScaleY(1.25f);
            view.setPivotX(0f);
            view.setPivotY(0f);
            frame.setNavigationHandoff(handoff);
        }

        void send(long down, long at, int action, int[] ids, int[] tools,
                  float[] xs, float[] ys) {
            MotionEvent local = event(down, at, action, ids, tools, xs, ys);
            MotionEvent parent = PaperNavigationHandoff.transformToTarget(local, view, frame);
            try {
                assertTrue(frame.dispatchTouchEvent(parent));
            } finally {
                local.recycle();
                parent.recycle();
            }
        }

        void sendFrame(long down, long at, int action, int[] ids, int[] tools,
                       float[] xs, float[] ys) {
            MotionEvent event = event(down, at, action, ids, tools, xs, ys);
            try {
                assertTrue(frame.dispatchTouchEvent(event));
            } finally {
                event.recycle();
            }
        }

        void close() {
            handoff.cancelActive();
            view.dispose();
            frame.removeView(view);
            frame.removeView(canvas);
            root.removeView(frame);
        }

        float[] fontPlusCenter() {
            try {
                java.lang.reflect.Field barField = NoteTextBoxView.class
                        .getDeclaredField("selectedStyleBar");
                java.lang.reflect.Field buttonField = NoteTextBoxView.class
                        .getDeclaredField("selectedFontIncreaseButton");
                barField.setAccessible(true);
                buttonField.setAccessible(true);
                View bar = (View) barField.get(view);
                View button = (View) buttonField.get(view);
                float localX = bar.getLeft() + button.getLeft() + button.getWidth() / 2f;
                float localY = bar.getTop() + button.getTop() + button.getHeight() / 2f;
                return new float[]{view.getX() + localX * view.getScaleX(),
                        view.getY() + localY * view.getScaleY()};
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("font control fixture unavailable", failure);
            }
        }

        NoteTextBoxView addSecondTextView(float x) {
            NoteTextBox secondModel = new NoteTextBox("fragment-2", "flow-2", 0, 1,
                    NoteTextBox.Format.MARKDOWN, "Second synthetic fragment.",
                    "Second synthetic fragment.", 16f, TextFlow.DEFAULT_LINE_HEIGHT,
                    0, x, 140f, 350f, 220f);
            NoteTextBoxView second = new NoteTextBoxView(context, listener);
            frame.addView(second, new FrameLayout.LayoutParams(350, 220));
            second.bind(secondModel, 1f, 0f, 0f, true);
            second.measure(View.MeasureSpec.makeMeasureSpec(350, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY));
            second.layout(0, 0, 350, 220);
            second.setPivotX(0f);
            second.setPivotY(0f);
            return second;
        }

        float[] pointInFrame(NoteTextBoxView source, float x, float y) {
            float[] point = new float[]{x, y};
            Matrix matrix = new Matrix(source.getMatrix());
            matrix.mapPoints(point);
            point[0] += source.getLeft() - frame.getScrollX();
            point[1] += source.getTop() - frame.getScrollY();
            return point;
        }

        long editSerial() {
            try {
                return canvas.toJsonDocument("synthetic-navigation", "synthetic")
                        .getLong("authorPageEditSerial");
            } catch (org.json.JSONException failure) {
                throw new AssertionError("canvas document serialization failed", failure);
            }
        }
    }

    private static MotionEvent event(long down, long at, int action, int[] ids,
                                     int[] tools, float[] xs, float[] ys) {
        MotionEvent.PointerProperties[] properties =
                new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[ids.length];
        for (int index = 0; index < ids.length; index++) {
            properties[index] = new MotionEvent.PointerProperties();
            properties[index].id = ids[index];
            properties[index].toolType = tools[index];
            coordinates[index] = new MotionEvent.PointerCoords();
            coordinates[index].x = xs[index];
            coordinates[index].y = ys[index];
            coordinates[index].pressure = 1f;
            coordinates[index].size = 0.1f;
        }
        return MotionEvent.obtain(down, at, action, ids.length, properties, coordinates,
                0, 0, 1f, 1f, 1, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private static final class ListenerSpy implements NoteTextBoxView.Listener {
        int previewCount;
        int moveCount;
        int resizeCount;
        int scaleCount;
        int fontChangeCount;
        int editCount;
        int previewEndedCount;

        @Override public void onSelect(NoteTextBox textBox) {}
        @Override public void onEditRequested(NoteTextBox textBox) { editCount++; }
        @Override public void onMove(NoteTextBox textBox, float x, float y) { moveCount++; }
        @Override public void onDragPreview(NoteTextBox textBox, float x, float y) { previewCount++; }
        @Override public void onDragPreviewEnded() { previewEndedCount++; }
        @Override public void onResize(NoteTextBox textBox, float width, float height) { resizeCount++; }
        @Override public void onScaleToArea(NoteTextBox textBox, float width, float height) { scaleCount++; }
        @Override public void onCommit(NoteTextBox textBox, NoteTextBox.Format format,
                                       String source, float fontSize, float lineHeight) {}
        @Override public void onFontSizeChanged(NoteTextBox textBox, float fontSize) { fontChangeCount++; }
        @Override public void onMeasuredHeight(NoteTextBox textBox, float height) {}
        @Override public void onCancel(NoteTextBox textBox) {}
        @Override public void onDelete(NoteTextBox textBox) {}
    }

    private static final class CanvasListenerSpy implements NoteCanvasView.Listener {
        @Override public void onInkStatsChanged(int strokes, int points, float pressure, String status) {}
        @Override public void onDocumentChanged() {}
        @Override public void onSelectionChanged(int selectedStrokes, boolean canUndo, boolean canRedo) {}
        @Override public void onViewportChanged(int currentPage, int pageCount, int zoomPercent, String status) {}
        @Override public void onTextBoxesChanged(List<NoteTextBox> textBoxes) {}
        @Override public void onTextBoxSelectionChanged(String textFlowId) {}
        @Override public void onTextInsertRequested(float worldX, float worldY) {}
    }
}
