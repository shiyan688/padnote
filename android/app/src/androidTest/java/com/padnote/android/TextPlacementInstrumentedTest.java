package com.padnote.android;

import android.content.Context;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.Espresso;
import androidx.test.espresso.action.ViewActions;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class TextPlacementInstrumentedTest {
    @Test
    public void activityTextPlacementWaitsForSingleUpAndCancelRemovesNothing() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String title = "Beta23 text placement " + UUID.randomUUID();
        NoteStore.Entry entry = NoteStore.create(context, title);
        ActivityScenario<MainActivity> scenario = null;
        try {
            scenario = ActivityScenario.launch(MainActivity.class);
            openSyntheticNote(context, title);
            awaitEditor(scenario);

            scenario.onActivity(activity -> {
                NoteCanvasView canvas = activity.canvasForTest();
                canvas.setTool(NoteCanvasView.Tool.TEXT);
                assertEquals(0, canvas.getTextBoxes().size());
                float x = canvas.getWidth() * 0.5f;
                float y = canvas.getHeight() * 0.5f;
                long down = SystemClock.uptimeMillis();

                dispatch(canvas, down, down, MotionEvent.ACTION_DOWN,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{x}, new float[]{y}, InputDevice.SOURCE_TOUCHSCREEN, 0);
                assertEquals("DOWN must not create an empty flow or open inline editing",
                        0, canvas.getTextBoxes().size());
                dispatch(canvas, down, down + 20, MotionEvent.ACTION_CANCEL,
                        new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{x}, new float[]{y}, InputDevice.SOURCE_TOUCHSCREEN, 0);
                assertEquals("CANCEL must leave the model untouched",
                        0, canvas.getTextBoxes().size());

                long tap = down + 50;
                dispatch(canvas, tap, tap, MotionEvent.ACTION_DOWN,
                        new int[]{3}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{x}, new float[]{y}, InputDevice.SOURCE_TOUCHSCREEN, 0);
                assertEquals(0, canvas.getTextBoxes().size());
                dispatch(canvas, tap, tap + 25, MotionEvent.ACTION_UP,
                        new int[]{3}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                        new float[]{x + 1}, new float[]{y + 1}, InputDevice.SOURCE_TOUCHSCREEN, 0);
                assertEquals("one valid tap invokes MainActivity's real insertion callback once",
                        1, canvas.getTextBoxes().size());

                NoteTextBoxView inline = firstTextBoxView(activity);
                assertNotNull(inline);
                assertTrue("the actual activity callback should open the inline editor",
                        inline.isInlineEditing());
                inline.cancelInlineEditing();
                assertEquals("Cancel removes the uncommitted empty placement",
                        0, canvas.getTextBoxes().size());
            });
        } finally {
            try {
                if (scenario != null) scenario.close();
            } finally {
                NoteStore.delete(context, entry.id);
            }
        }
    }

    @Test
    public void movementBeyondTouchSlopCancelsPlacementAndCannotRestartOnSameStream() {
        withCanvas((canvas, listener) -> {
            float x = canvas.getWidth() * 0.5f;
            float y = canvas.getHeight() * 0.5f;
            float slop = ViewConfiguration.get(canvas.getContext()).getScaledTouchSlop();
            long down = SystemClock.uptimeMillis();
            dispatch(canvas, down, down, MotionEvent.ACTION_DOWN,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                    new float[]{x}, new float[]{y}, InputDevice.SOURCE_TOUCHSCREEN, 0);
            assertEquals(0, listener.insertCount);
            dispatch(canvas, down, down + 10, MotionEvent.ACTION_UP,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                    new float[]{x + slop + 2}, new float[]{y}, InputDevice.SOURCE_TOUCHSCREEN, 0);
            assertEquals("UP itself must be checked if no MOVE was delivered", 0,
                    listener.insertCount);

            down += 50;
            dispatch(canvas, down, down, MotionEvent.ACTION_DOWN,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                    new float[]{x}, new float[]{y}, InputDevice.SOURCE_TOUCHSCREEN, 0);
            dispatch(canvas, down, down + 20, MotionEvent.ACTION_MOVE,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                    new float[]{x + slop + 2}, new float[]{y}, InputDevice.SOURCE_TOUCHSCREEN, 0);
            dispatch(canvas, down, down + 40, MotionEvent.ACTION_UP,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                    new float[]{x + 1}, new float[]{y}, InputDevice.SOURCE_TOUCHSCREEN, 0);
            assertEquals(0, listener.insertCount);
        });
    }

    @Test
    public void twoFingerTextModeNavigatesThroughRemainingFingerWithoutInsertion() {
        withCanvas((canvas, listener) -> {
            float cx = canvas.getWidth() * 0.5f;
            float cy = canvas.getHeight() * 0.5f;
            float scaleBefore = canvas.getViewportScale();
            long down = SystemClock.uptimeMillis();
            dispatch(canvas, down, down, MotionEvent.ACTION_DOWN,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                    new float[]{cx - 80}, new float[]{cy}, InputDevice.SOURCE_TOUCHSCREEN, 0);
            dispatch(canvas, down, down + 10,
                    MotionEvent.ACTION_POINTER_DOWN |
                            (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                            MotionEvent.TOOL_TYPE_FINGER},
                    new float[]{cx - 80, cx + 80}, new float[]{cy, cy},
                    InputDevice.SOURCE_TOUCHSCREEN, 0);
            assertTrue(canvas.isUserInteractionActive());
            dispatch(canvas, down, down + 30, MotionEvent.ACTION_MOVE,
                    new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                            MotionEvent.TOOL_TYPE_FINGER},
                    new float[]{cx - 110, cx + 110}, new float[]{cy, cy},
                    InputDevice.SOURCE_TOUCHSCREEN, 0);
            assertTrue(canvas.getViewportScale() > scaleBefore * 1.1f);
            float panBefore = canvas.getViewportPanX();
            dispatch(canvas, down, down + 40,
                    MotionEvent.ACTION_POINTER_UP |
                            (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                            MotionEvent.TOOL_TYPE_FINGER},
                    new float[]{cx - 110, cx + 110}, new float[]{cy, cy},
                    InputDevice.SOURCE_TOUCHSCREEN, 0);
            dispatch(canvas, down, down + 60, MotionEvent.ACTION_MOVE,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                    new float[]{cx - 125}, new float[]{cy + 20},
                    InputDevice.SOURCE_TOUCHSCREEN, 0);
            assertTrue(Math.abs(canvas.getViewportPanX() - panBefore) > 1f);
            dispatch(canvas, down, down + 70, MotionEvent.ACTION_UP,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                    new float[]{cx - 125}, new float[]{cy + 20},
                    InputDevice.SOURCE_TOUCHSCREEN, 0);
            assertFalse(canvas.isUserInteractionActive());
            assertEquals("remaining single finger is still navigation, never a text tap",
                    0, listener.insertCount);
        });
    }

    @Test
    public void mixedPointersCancelPlacementWithoutNavigationOrSecondPlacement() {
        withCanvas((canvas, listener) -> {
            float x = canvas.getWidth() * 0.5f;
            float y = canvas.getHeight() * 0.5f;
            long down = SystemClock.uptimeMillis();
            dispatch(canvas, down, down, MotionEvent.ACTION_DOWN,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                    new float[]{x}, new float[]{y}, InputDevice.SOURCE_TOUCHSCREEN, 0);
            dispatch(canvas, down, down + 10,
                    MotionEvent.ACTION_POINTER_DOWN |
                            (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                            MotionEvent.TOOL_TYPE_STYLUS},
                    new float[]{x, x + 30}, new float[]{y, y},
                    InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.BUTTON_STYLUS_PRIMARY);
            assertFalse(canvas.isUserInteractionActive());
            dispatch(canvas, down, down + 20, MotionEvent.ACTION_CANCEL,
                    new int[]{0, 1}, new int[]{MotionEvent.TOOL_TYPE_FINGER,
                            MotionEvent.TOOL_TYPE_STYLUS},
                    new float[]{x, x + 30}, new float[]{y, y},
                    InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.BUTTON_STYLUS_PRIMARY);
            assertEquals(0, listener.insertCount);
        });
    }

    @Test
    public void toolChangeCancelsPendingPlacementAndOldUpCannotInsert() {
        withCanvas((canvas, listener) -> {
            float x = canvas.getWidth() * 0.5f;
            float y = canvas.getHeight() * 0.5f;
            long down = SystemClock.uptimeMillis();
            dispatch(canvas, down, down, MotionEvent.ACTION_DOWN,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                    new float[]{x}, new float[]{y}, InputDevice.SOURCE_TOUCHSCREEN, 0);
            canvas.setTool(NoteCanvasView.Tool.PEN);
            assertEquals(NoteCanvasView.Tool.PEN, canvas.getTool());
            dispatch(canvas, down, down + 20, MotionEvent.ACTION_UP,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_FINGER},
                    new float[]{x}, new float[]{y}, InputDevice.SOURCE_TOUCHSCREEN, 0);
            assertEquals(0, listener.insertCount);
        });
    }

    @Test
    public void stylusAndMousePlacementWaitForUpAndPenOnlyMouseRemainsBlocked() {
        withCanvas((canvas, listener) -> {
            float x = canvas.getWidth() * 0.5f;
            float y = canvas.getHeight() * 0.5f;
            long stylusDown = SystemClock.uptimeMillis();
            dispatch(canvas, stylusDown, stylusDown, MotionEvent.ACTION_DOWN,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_STYLUS},
                    new float[]{x}, new float[]{y}, InputDevice.SOURCE_STYLUS,
                    MotionEvent.BUTTON_STYLUS_PRIMARY);
            assertEquals(0, listener.insertCount);
            dispatch(canvas, stylusDown, stylusDown + 20, MotionEvent.ACTION_UP,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_STYLUS},
                    new float[]{x}, new float[]{y}, InputDevice.SOURCE_STYLUS,
                    MotionEvent.BUTTON_STYLUS_PRIMARY);
            assertEquals(1, listener.insertCount);

            long eraserDown = stylusDown + 30;
            dispatch(canvas, eraserDown, eraserDown, MotionEvent.ACTION_DOWN,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_ERASER},
                    new float[]{x}, new float[]{y}, InputDevice.SOURCE_STYLUS,
                    MotionEvent.BUTTON_STYLUS_PRIMARY);
            assertEquals("eraser contact must preserve its original tool meaning",
                    1, listener.insertCount);
            dispatch(canvas, eraserDown, eraserDown + 20, MotionEvent.ACTION_UP,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_ERASER},
                    new float[]{x}, new float[]{y}, InputDevice.SOURCE_STYLUS,
                    MotionEvent.BUTTON_STYLUS_PRIMARY);

            long blockedMouse = eraserDown + 30;
            dispatch(canvas, blockedMouse, blockedMouse, MotionEvent.ACTION_DOWN,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_MOUSE},
                    new float[]{x}, new float[]{y}, InputDevice.SOURCE_MOUSE,
                    MotionEvent.BUTTON_PRIMARY);
            dispatch(canvas, blockedMouse, blockedMouse + 20, MotionEvent.ACTION_UP,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_MOUSE},
                    new float[]{x}, new float[]{y}, InputDevice.SOURCE_MOUSE,
                    MotionEvent.BUTTON_PRIMARY);
            assertEquals("pen-only still rejects mouse placement", 1, listener.insertCount);

            canvas.setPenOnly(false);
            long mouseDown = blockedMouse + 50;
            dispatch(canvas, mouseDown, mouseDown, MotionEvent.ACTION_DOWN,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_MOUSE},
                    new float[]{x}, new float[]{y}, InputDevice.SOURCE_MOUSE,
                    MotionEvent.BUTTON_PRIMARY);
            assertEquals(1, listener.insertCount);
            dispatch(canvas, mouseDown, mouseDown + 20, MotionEvent.ACTION_UP,
                    new int[]{0}, new int[]{MotionEvent.TOOL_TYPE_MOUSE},
                    new float[]{x}, new float[]{y}, InputDevice.SOURCE_MOUSE,
                    MotionEvent.BUTTON_PRIMARY);
            assertEquals(2, listener.insertCount);
        });
    }

    private static void openSyntheticNote(Context context, String title) throws Exception {
        String description = context.getString(R.string.core_note_card_open_desc, title);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            try {
                Espresso.onView(ViewMatchers.withContentDescription(description))
                        .perform(ViewActions.scrollTo(), ViewActions.click());
                return;
            } catch (RuntimeException | AssertionError unavailable) {
                last = unavailable;
                Thread.sleep(100);
            }
        }
        throw new AssertionError("synthetic note card did not appear", last);
    }

    private static void awaitEditor(ActivityScenario<MainActivity> scenario) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            AtomicBoolean ready = new AtomicBoolean();
            scenario.onActivity(activity -> ready.set(activity.canvasForTest() != null &&
                    activity.canvasForTest().isEnabled()));
            if (ready.get()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("synthetic note editor did not become ready");
    }

    @SuppressWarnings("unchecked")
    private static NoteTextBoxView firstTextBoxView(MainActivity activity) {
        try {
            Field field = MainActivity.class.getDeclaredField("textBoxViews");
            field.setAccessible(true);
            Map<String, NoteTextBoxView> views = (Map<String, NoteTextBoxView>) field.get(activity);
            return views.isEmpty() ? null : views.values().iterator().next();
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("cannot inspect synthetic inline editor", error);
        }
    }

    private static void withCanvas(CanvasTest body) {
        android.app.Instrumentation instrumentation =
                InstrumentationRegistry.getInstrumentation();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            NoteCanvasView canvas = null;
            try {
                Context context = instrumentation.getTargetContext();
                canvas = new NoteCanvasView(context);
                InsertListener listener = new InsertListener();
                canvas.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000,
                                android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(1000,
                                android.view.View.MeasureSpec.EXACTLY));
                canvas.layout(0, 0, 1000, 1000);
                canvas.setListener(listener);
                canvas.setPenOnly(true);
                canvas.setTool(NoteCanvasView.Tool.TEXT);
                body.run(canvas, listener);
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                if (canvas != null) {
                    try {
                        canvas.onDetachedFromWindow();
                    } catch (Throwable cleanupError) {
                        Throwable earlier = failure.get();
                        if (earlier == null) {
                            failure.set(cleanupError);
                        } else if (earlier != cleanupError) {
                            earlier.addSuppressed(cleanupError);
                        }
                    }
                }
            }
        });
        Throwable error = failure.get();
        if (error != null) {
            throw new AssertionError("UI-thread canvas test failed", error);
        }
    }

    private static void dispatch(NoteCanvasView canvas, long downTime, long eventTime,
                                 int action, int[] ids, int[] tools, float[] xs, float[] ys,
                                 int source, int buttons) {
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
            coordinates[index].pressure = 0.7f;
            coordinates[index].size = 0.12f;
        }
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, ids.length,
                properties, coordinates, 0, buttons, 1f, 1f, 0, 0, source, 0);
        try {
            assertTrue("canvas should receive synthetic UI event", canvas.dispatchTouchEvent(event));
        } finally {
            event.recycle();
        }
    }

    private interface CanvasTest {
        void run(NoteCanvasView canvas, InsertListener listener);
    }

    private static final class InsertListener implements NoteCanvasView.Listener {
        int insertCount;
        float insertedX;
        float insertedY;

        @Override public void onInkStatsChanged(int strokes, int points, float pressure,
                                               String inputStatus) {}
        @Override public void onDocumentChanged() {}
        @Override public void onSelectionChanged(int selectedStrokes, boolean canUndo,
                                                 boolean canRedo) {}
        @Override public void onViewportChanged(int currentPage, int pageCount,
                                                int zoomPercent, String status) {}
        @Override public void onTextBoxesChanged(java.util.List<NoteTextBox> textBoxes) {}
        @Override public void onTextBoxSelectionChanged(String textBoxId) {}
        @Override public void onTextInsertRequested(float worldX, float worldY) {
            insertCount++;
            insertedX = worldX;
            insertedY = worldY;
        }
    }
}
