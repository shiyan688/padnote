package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
public final class BottomPullNavigationInstrumentedTest {
    private static final int LONG_NOTE_PAGE_COUNT = 18;
    private static final float VIEWPORT_HEIGHT = 1403f;
    private static final float PAGE_HEIGHT = 1086f;
    private static final float PAGE_GAP = 24f;
    private static final float PAGE_WIDTH = 768f;

    @Test
    public void belowThresholdUpAndAboveThresholdCancelDoNotCommit() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView below = createLongNoteCanvas(context);
            NoteCanvasView canceled = createLongNoteCanvas(context);
            try {
                String belowContent = contentFingerprint(below);
                int belowHistory = below.undoDepthForTest();
                sendPull(below, 160f, MotionEvent.ACTION_UP, 100L);
                assertEquals(LONG_NOTE_PAGE_COUNT, below.getPageCount());
                assertEquals(belowHistory, below.undoDepthForTest());
                assertEquals(belowContent, contentFingerprint(below));

                String cancelContent = contentFingerprint(canceled);
                int cancelHistory = canceled.undoDepthForTest();
                sendPull(canceled, 900f, MotionEvent.ACTION_CANCEL, 1000L);
                assertEquals(LONG_NOTE_PAGE_COUNT, canceled.getPageCount());
                assertEquals(cancelHistory, canceled.undoDepthForTest());
                assertEquals(cancelContent, contentFingerprint(canceled));
                assertFalse(booleanField(canceled, "navigationGesture"));
                assertEquals(0f, floatField(canceled, "bottomPullDistance"), 0.001f);
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                below.onDetachedFromWindow();
                canceled.onDetachedFromWindow();
            }
        });
    }

    @Test
    public void committedPullAddsExactlyOnePageAndOneUndoRestoresBaseline() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = createLongNoteCanvas(context);
            try {
                String originalContent = contentFingerprint(canvas);
                String originalPaperContent = contentFingerprintWithoutPageCount(canvas);
                int originalHistory = canvas.undoDepthForTest();

                sendPull(canvas, 900f, MotionEvent.ACTION_UP, 2000L);
                assertEquals(LONG_NOTE_PAGE_COUNT + 1, canvas.getPageCount());
                assertEquals(originalHistory + 1, canvas.undoDepthForTest());
                assertEquals(originalPaperContent, contentFingerprintWithoutPageCount(canvas));

                canvas.undo();
                assertEquals(LONG_NOTE_PAGE_COUNT, canvas.getPageCount());
                assertEquals(originalHistory, canvas.undoDepthForTest());
                assertEquals(originalContent, contentFingerprint(canvas));
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
    }

    @Test
    public void earlierPagePanIsOneToOneAndReversesAnActivePull() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView reversing = createLongNoteCanvas(context);
            NoteCanvasView earlier = createLongNoteCanvas(context);
            try {
                int reversingPages = reversing.getPageCount();
                String reversingContent = contentFingerprint(reversing);
                int reversingHistory = reversing.undoDepthForTest();
                float startPan = floatField(reversing, "viewportPanY");
                sendDown(reversing, 3000L, 1200f);
                sendMove(reversing, 3000L, 3010L, 300f);
                float pulledPan = floatField(reversing, "viewportPanY");
                assertTrue("upward pull should reveal provisional paper", pulledPan < startPan);
                sendMove(reversing, 3000L, 3020L, 360f);
                assertEquals("positive dy must repay the pull 1:1", 60f,
                        floatField(reversing, "viewportPanY") - pulledPan, 0.01f);
                assertEquals(reversingPages, reversing.getPageCount());
                sendCancel(reversing, 3000L, 3030L, 360f);
                assertEquals(reversingPages, reversing.getPageCount());
                assertEquals(reversingHistory, reversing.undoDepthForTest());
                assertEquals(reversingContent, contentFingerprint(reversing));

                int earlierPages = earlier.getPageCount();
                float earlierPan = floatField(earlier, "viewportPanY");
                sendDown(earlier, 4000L, 900f);
                sendMove(earlier, 4000L, 4010L, 1020f);
                assertEquals("downward movement toward earlier pages must stay 1:1", 120f,
                        floatField(earlier, "viewportPanY") - earlierPan, 0.01f);
                assertEquals(earlierPages, earlier.getPageCount());
                sendUp(earlier, 4000L, 4020L, 1020f);
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                reversing.onDetachedFromWindow();
                earlier.onDetachedFromWindow();
            }
        });
    }

    private static NoteCanvasView createLongNoteCanvas(Context context) {
        NoteCanvasView canvas = new NoteCanvasView(context);
        canvas.layout(0, 0, 2800, (int) VIEWPORT_HEIGHT);
        canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                "BOTTOM-PULL-SYNTHETIC-CONTENT-MUST-STAY-UNCHANGED", 40f, 80f);
        canvas.setTool(NoteCanvasView.Tool.PEN);
        canvas.setPenOnly(true);
        setField(canvas, "pageWidth", PAGE_WIDTH);
        setField(canvas, "pageHeight", PAGE_HEIGHT);
        setField(canvas, "pageGap", PAGE_GAP);
        setField(canvas, "pageCount", LONG_NOTE_PAGE_COUNT);
        setField(canvas, "viewportScale", 1f);
        setField(canvas, "viewportScale64", 1d);
        float minimumPan = invokeMinimumViewportPanY(canvas);
        setField(canvas, "viewportPanY", minimumPan);
        setField(canvas, "navigationRawPanY", minimumPan);
        return canvas;
    }

    private static void sendPull(NoteCanvasView canvas, float distance, int endAction,
                                 long downTime) {
        float startY = 1200f;
        sendDown(canvas, downTime, startY);
        sendMove(canvas, downTime, downTime + 10L, startY - distance);
        float revealFraction = invokeNextPageRevealFraction(canvas);
        if (distance < 200f) {
            assertTrue("below-threshold pull fixture must remain below 1/3",
                    revealFraction < 1f / 3f);
        } else {
            assertTrue("commit/cancel pull fixture must reach 1/3",
                    revealFraction >= 1f / 3f);
        }
        if (endAction == MotionEvent.ACTION_CANCEL) {
            dispatch(canvas, downTime, downTime + 20L, endAction, startY - distance);
        } else {
            sendUp(canvas, downTime, downTime + 20L, startY - distance);
        }
    }

    private static void sendDown(NoteCanvasView canvas, long downTime, float y) {
        dispatch(canvas, downTime, downTime, MotionEvent.ACTION_DOWN, y);
    }

    private static void sendMove(NoteCanvasView canvas, long downTime, long eventTime, float y) {
        dispatch(canvas, downTime, eventTime, MotionEvent.ACTION_MOVE, y);
    }

    private static void sendUp(NoteCanvasView canvas, long downTime, long eventTime, float y) {
        dispatch(canvas, downTime, eventTime, MotionEvent.ACTION_UP, y);
    }

    private static void sendCancel(NoteCanvasView canvas, long downTime, long eventTime, float y) {
        dispatch(canvas, downTime, eventTime, MotionEvent.ACTION_CANCEL, y);
    }

    private static void dispatch(NoteCanvasView canvas, long downTime, long eventTime,
                                 int action, float y) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.x = 400f;
        coordinates.y = y;
        coordinates.pressure = 1f;
        coordinates.size = 0.1f;
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, 1,
                new MotionEvent.PointerProperties[]{properties},
                new MotionEvent.PointerCoords[]{coordinates}, 0, 0, 1f, 1f,
                1, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            assertTrue(canvas.dispatchTouchEvent(event));
        } finally {
            event.recycle();
        }
    }

    private static String contentFingerprint(NoteCanvasView canvas) throws JSONException {
        JSONObject document = canvas.toJsonDocument("bottom-pull-fixture", "synthetic");
        return document.getInt("pageCount") + "|" + document.getJSONArray("textFlows") +
                "|" + document.getJSONArray("strokes") + "|" + document.optJSONArray("images");
    }

    private static String contentFingerprintWithoutPageCount(NoteCanvasView canvas)
            throws JSONException {
        JSONObject document = canvas.toJsonDocument("bottom-pull-fixture", "synthetic");
        return document.getJSONArray("textFlows") + "|" + document.getJSONArray("strokes") +
                "|" + document.optJSONArray("images");
    }

    private static float floatField(NoteCanvasView canvas, String name) throws Exception {
        return ((Number) field(canvas, name)).floatValue();
    }

    private static boolean booleanField(NoteCanvasView canvas, String name) throws Exception {
        return (Boolean) field(canvas, name);
    }

    private static float invokeMinimumViewportPanY(NoteCanvasView canvas) {
        return invokeFloat(canvas, "minimumViewportPanY");
    }

    private static float invokeNextPageRevealFraction(NoteCanvasView canvas) {
        return invokeFloat(canvas, "nextPageRevealFraction");
    }

    private static float invokeFloat(NoteCanvasView canvas, String methodName) {
        try {
            java.lang.reflect.Method method = NoteCanvasView.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            return ((Number) method.invoke(canvas)).floatValue();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("canvas fixture method unavailable: " + methodName, failure);
        }
    }

    private static Object field(NoteCanvasView canvas, String name) throws Exception {
        Field field = NoteCanvasView.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(canvas);
    }

    private static void setField(NoteCanvasView canvas, String name, Object value) {
        try {
            Field field = NoteCanvasView.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(canvas, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("canvas fixture field unavailable: " + name, failure);
        }
    }
}
