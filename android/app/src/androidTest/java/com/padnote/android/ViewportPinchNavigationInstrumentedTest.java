package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@RunWith(AndroidJUnit4.class)
public final class ViewportPinchNavigationInstrumentedTest {
    @Test public void longPaperPinchKeepsFocusAndDoesNotEditTheNote() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(context);
            try {
                canvas.layout(0, 0, 2800, 1403);
                NoteTextBox first = canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                        "PINCH-CONTENT-MUST-STAY-UNCHANGED", 40f, 80f);
                assertNotNull(first);

                setField(canvas, "pageWidth", 768f);
                setField(canvas, "pageHeight", 1086f);
                setField(canvas, "pageGap", 24f);
                setField(canvas, "pageCount", 18);
                setField(canvas, "viewportScale", 1f);
                setField(canvas, "viewportScale64", 1d);
                setField(canvas, "viewportPanY", 158.5f);
                setField(canvas, "navigationGesture", true);
                setField(canvas, "navigationLastFocusX", 400f);
                setField(canvas, "navigationLastFocusY", 699f);
                setField(canvas, "navigationLastSpan", 440f);

                JSONObject before = canvas.toJsonDocument("pinch-fixture", "pinch fixture");
                String originalFlows = before.getJSONArray("textFlows").toString();
                float focusY = 699f;

                MotionEvent zoomOut = twoFingerMove(focusY, 220f);
                try {
                    invokeUpdateNavigation(canvas, zoomOut);
                } finally {
                    zoomOut.recycle();
                }
                assertEquals(0.5f, floatField(canvas, "viewportScale"), 0.001f);
                assertEquals("focused pan must not be clipped at the 18dp margin",
                        428.75f, floatField(canvas, "viewportPanY"), 0.01f);

                MotionEvent zoomBack = twoFingerMove(focusY, 440f);
                try {
                    invokeUpdateNavigation(canvas, zoomBack);
                } finally {
                    zoomBack.recycle();
                }
                assertEquals(1f, floatField(canvas, "viewportScale"), 0.001f);
                assertEquals(158.5f, floatField(canvas, "viewportPanY"), 0.01f);

                JSONObject after = canvas.toJsonDocument("pinch-fixture", "pinch fixture");
                assertEquals(18, after.getInt("pageCount"));
                assertEquals(originalFlows, after.getJSONArray("textFlows").toString());
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
    }

    private static MotionEvent twoFingerMove(float focusY, float span) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
        for (int index = 0; index < 2; index++) {
            properties[index] = new MotionEvent.PointerProperties();
            properties[index].id = index;
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[index] = new MotionEvent.PointerCoords();
            coordinates[index].x = 400f;
            coordinates[index].y = focusY + (index == 0 ? -span / 2f : span / 2f);
            coordinates[index].pressure = 0.5f;
            coordinates[index].size = 0.02f;
        }
        return MotionEvent.obtain(1L, 2L, MotionEvent.ACTION_MOVE, 2,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private static void invokeUpdateNavigation(NoteCanvasView canvas, MotionEvent event)
            throws Exception {
        Method method = NoteCanvasView.class.getDeclaredMethod("updateNavigation", MotionEvent.class);
        method.setAccessible(true);
        method.invoke(canvas, event);
    }

    private static float floatField(NoteCanvasView canvas, String name) throws Exception {
        return ((Number) getField(canvas, name)).floatValue();
    }

    private static Object getField(NoteCanvasView canvas, String name) throws Exception {
        Field field = NoteCanvasView.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(canvas);
    }

    private static void setField(NoteCanvasView canvas, String name, Object value) throws Exception {
        Field field = NoteCanvasView.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(canvas, value);
    }
}
