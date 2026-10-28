package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class AiPaperSelectionInstrumentedTest {
    private static final int RENDER_LIMIT = 1600;

    @Test public void typedOnlyFragmentIsAiSelectableWithoutChangingGeneralSelectionCount() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(context);
            NoteCanvasView.AiSelectionRenderRequest request = null;
            try {
                canvas.layout(0, 0, 800, 1000);
                NoteTextBox fragment = canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                        "Typed-only paper selection", 40f, 80f);
                assertNotNull(fragment);
                assertTrue(canvas.selectAiTextFragment(fragment.id));
                assertTrue(canvas.hasAiSelection());
                assertEquals(0, canvas.getSelectionCount());

                JSONObject before = canvas.toJsonDocument("paper-ai-test", "in-memory");
                before.remove("updatedAt");
                request = canvas.captureAiSelectionRenderRequest(RENDER_LIMIT);
                assertEquals(fragment.pageIndex, request.pageIndex);
                assertEquals(1, request.textFragmentIds.size());
                assertEquals(fragment.id, request.textFragmentIds.get(0));
                assertTrue(request.cropBounds.width() > 0f);
                assertTrue(request.cropBounds.height() > 0f);
                assertRoiBoundsMatch(request, canvas);
                JSONObject after = canvas.toJsonDocument("paper-ai-test", "in-memory");
                after.remove("updatedAt");
                assertEquals(before.toString(), after.toString());
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                if (request != null) request.close();
                canvas.onDetachedFromWindow();
            }
        });
    }

    @Test public void multiPageFlowCaptureKeepsOneFragmentAndItsPageBounds() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(context);
            NoteCanvasView.AiSelectionRenderRequest request = null;
            try {
                canvas.layout(0, 0, 800, 1000);
                NoteTextBox first = addMultiPageFlow(canvas);
                NoteTextBox later = null;
                for (NoteTextBox box : canvas.getTextBoxes()) {
                    if (box.flowId.equals(first.flowId) && box.pageIndex > first.pageIndex) {
                        later = box;
                        break;
                    }
                }
                assertNotNull("fixture must paginate onto another page", later);
                assertTrue(canvas.selectAiTextFragment(first.id));
                request = canvas.captureAiSelectionRenderRequest(RENDER_LIMIT);

                assertEquals(first.pageIndex, request.pageIndex);
                assertEquals(1, request.textFragmentIds.size());
                assertEquals(first.id, request.textFragmentIds.get(0));
                assertTrue("crop must not grow to the whole flow",
                        request.cropBounds.height() < later.y - first.y);
                assertRoiBoundsMatch(request, canvas);
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                if (request != null) request.close();
                canvas.onDetachedFromWindow();
            }
        });
    }

    @Test public void appendGesturePointMarksCrossPageLassoBeforeDroppingPoint() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(context);
            try {
                canvas.layout(0, 0, 800, 1000);
                canvas.addPage();
                float pageHeight = floatField(canvas, "pageHeight");
                float pageGap = floatField(canvas, "pageGap");
                float stride = pageHeight + pageGap;
                setField(canvas, "activeGestureTool", NoteCanvasView.Tool.LASSO);
                setField(canvas, "activeGesturePageIndex", 0);
                invokeAppendGesturePoint(canvas, new InkPoint(40f, 40f, 1L, 0.5f));
                invokeAppendGesturePoint(canvas,
                        new InkPoint(240f, stride + 40f, 2L, 0.5f));

                // This flag opens the AI capture path only so it can report the
                // invalid gesture; capture still refuses a payload, so it grants no submit scope.
                assertTrue(canvas.hasAiSelection());
                assertEquals(0, canvas.getSelectionCount());
                try {
                    NoteCanvasView.AiSelectionRenderRequest unexpected =
                            canvas.captureAiSelectionRenderRequest(RENDER_LIMIT);
                    unexpected.close();
                    fail("cross-page lasso must fail even with no first-page content");
                } catch (IOException expected) {
                    assertTrue(expected.getMessage().contains("单页"));
                }
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
    }

    @Test public void cancelledCrossPageLassoClearsOnlyInvalidGestureState() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(context);
            NoteCanvasView.AiSelectionRenderRequest request = null;
            try {
                canvas.layout(0, 0, 800, 1000);
                NoteTextBox fragment = canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                        "preserve existing fragment selection", 40f, 80f);
                assertTrue(canvas.selectAiTextFragment(fragment.id));
                long selectedRevision = canvas.getAiSelectionRevision();
                setField(canvas, "aiLassoCrossedPage", true);
                @SuppressWarnings("unchecked")
                List<android.graphics.PointF> points =
                        (List<android.graphics.PointF>) getField(canvas, "lassoPoints");
                points.add(new android.graphics.PointF(10f, 10f));

                invokeFinishLasso(canvas, true);

                assertFalse("cancelled cross-page flag must not remain as AI scope",
                        (Boolean) getField(canvas, "aiLassoCrossedPage"));
                assertTrue("cancelling the gesture cleared the prior valid fragment scope",
                        canvas.hasAiSelection());
                assertTrue(canvas.getAiSelectionRevision() > selectedRevision);
                request = canvas.captureAiSelectionRenderRequest(RENDER_LIMIT);
                assertEquals(1, request.textFragmentIds.size());
                assertEquals(fragment.id, request.textFragmentIds.get(0));
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                if (request != null) request.close();
                canvas.onDetachedFromWindow();
            }
        });
    }

    @Test public void reflowInvalidatesOldFragmentIdentityAndRevision() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(context);
            try {
                canvas.layout(0, 0, 800, 1000);
                NoteTextBox first = addMultiPageFlow(canvas);
                assertTrue(canvas.selectAiTextFragment(first.id));
                long selectedRevision = canvas.getAiSelectionRevision();

                assertTrue(canvas.moveTextFlowByPages(first.flowId, 1));

                assertTrue(canvas.getAiSelectionRevision() > selectedRevision);
                assertFalse(canvas.hasAiSelection());
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
    }

    private static NoteTextBox addMultiPageFlow(NoteCanvasView canvas) {
        StringBuilder source = new StringBuilder();
        for (int index = 0; index < 180; index++) {
            source.append("Paragraph ").append(index).append(" keeps this paper flow long enough ")
                    .append("to create multiple visible page fragments.\n\n");
        }
        NoteTextBox first = canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                source.toString(), 40f, 40f);
        assertNotNull(first);
        return first;
    }

    private static float floatField(Object target, String name) throws Exception {
        return ((Number) getField(target, name)).floatValue();
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invokeAppendGesturePoint(NoteCanvasView canvas, InkPoint point)
            throws Exception {
        java.lang.reflect.Method method = canvas.getClass().getDeclaredMethod(
                "appendGesturePoint", InkPoint.class);
        method.setAccessible(true);
        method.invoke(canvas, point);
    }

    private static void invokeFinishLasso(NoteCanvasView canvas, boolean canceled)
            throws Exception {
        java.lang.reflect.Method method = canvas.getClass().getDeclaredMethod(
                "finishLasso", boolean.class);
        method.setAccessible(true);
        method.invoke(canvas, canceled);
    }

    private static void assertRoiBoundsMatch(
            NoteCanvasView.AiSelectionRenderRequest request, NoteCanvasView canvas)
            throws Exception {
        float pageTop = request.pageIndex *
                (floatField(canvas, "pageHeight") + floatField(canvas, "pageGap"));
        assertEquals(request.cropBounds.left, request.sourceBounds.left, 0.001f);
        assertEquals(request.cropBounds.right, request.sourceBounds.right, 0.001f);
        assertEquals(request.cropBounds.top + pageTop, request.sourceBounds.top, 0.001f);
        assertEquals(request.cropBounds.bottom + pageTop,
                request.sourceBounds.bottom, 0.001f);
        assertTrue(request.sourceBounds.top >= pageTop);
        assertTrue(request.sourceBounds.bottom <= pageTop + floatField(canvas, "pageHeight"));
    }
}
