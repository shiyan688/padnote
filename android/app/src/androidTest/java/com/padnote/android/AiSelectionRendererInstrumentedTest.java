package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.content.Context;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory pixel-scope checks; these tests never open a note or touch NoteStore. */
@RunWith(AndroidJUnit4.class)
public final class AiSelectionRendererInstrumentedTest {
    @Test public void nonzeroOriginCropContainsOnlySelectedBluePixelsAndPngMatches()
            throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Bitmap page = coloredPage();
            Bitmap crop = null;
            Bitmap decoded = null;
            try {
                crop = AiSelectionRenderer.cropAndMask(page,
                        new RectF(30f, 20f, 70f, 60f), Collections.emptyList(),
                        false, 1600, 100f, 80f);
                assertEquals(40, crop.getWidth());
                assertEquals(40, crop.getHeight());
                assertAllPixels(crop, Color.BLUE);

                ByteArrayOutputStream png = new ByteArrayOutputStream();
                assertTrue(crop.compress(Bitmap.CompressFormat.PNG, 100, png));
                byte[] encoded = png.toByteArray();
                decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
                assertTrue("encoded crop did not decode", decoded != null);
                assertEquals(crop.getWidth(), decoded.getWidth());
                assertEquals(crop.getHeight(), decoded.getHeight());
                assertAllPixels(decoded, Color.BLUE);
            } catch (IOException error) {
                throw new AssertionError(error);
            } finally {
                if (decoded != null) decoded.recycle();
                if (crop != null) crop.recycle();
                page.recycle();
            }
        });
    }

    @Test public void triangleMaskMakesOutsideWhiteAndKeepsInsideBlueThroughPng()
            throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Bitmap page = coloredPage();
            Bitmap crop = null;
            Bitmap decoded = null;
            try {
                crop = AiSelectionRenderer.cropAndMask(page,
                        new RectF(20f, 20f, 80f, 80f),
                        Arrays.asList(new PointF(20f, 20f), new PointF(80f, 20f),
                                new PointF(20f, 80f)), true, 1600, 100f, 80f);
                assertEquals(Color.BLUE, crop.getPixel(10, 10)); // paper position (30,30)
                assertEquals(Color.WHITE, crop.getPixel(50, 50)); // paper position (70,70)
                assertEquals(Color.WHITE, crop.getPixel(55, 10)); // outside triangle
                assertNoRed(crop);

                ByteArrayOutputStream png = new ByteArrayOutputStream();
                assertTrue(crop.compress(Bitmap.CompressFormat.PNG, 100, png));
                byte[] encoded = png.toByteArray();
                decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
                assertTrue(decoded != null);
                assertEquals(Color.BLUE, decoded.getPixel(10, 10));
                assertEquals(Color.WHITE, decoded.getPixel(50, 50));
                assertEquals(Color.WHITE, decoded.getPixel(55, 10));
                assertNoRed(decoded);
            } catch (IOException error) {
                throw new AssertionError(error);
            } finally {
                if (decoded != null) decoded.recycle();
                if (crop != null) crop.recycle();
                page.recycle();
            }
        });
    }

    @Test public void emptyOutsideAndNanCropRejectWithoutProducingOutput() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Bitmap page = coloredPage();
            try {
                assertThrows(IOException.class, () -> AiSelectionRenderer.cropAndMask(page,
                        new RectF(10f, 10f, 10f, 20f), Collections.emptyList(),
                        false, 1600, 100f, 80f));
                assertThrows(IOException.class, () -> AiSelectionRenderer.cropAndMask(page,
                        new RectF(120f, 10f, 130f, 20f), Collections.emptyList(),
                        false, 1600, 100f, 80f));
                assertThrows(IOException.class, () -> AiSelectionRenderer.cropAndMask(page,
                        new RectF(Float.NaN, 10f, 30f, 20f), Collections.emptyList(),
                        false, 1600, 100f, 80f));
                assertFalse("input page should remain caller-owned", page.isRecycled());
            } finally {
                page.recycle();
            }
        });
    }

    @Test public void oversizedCropIsLimitedTo1600PixelLongEdge() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Bitmap page = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888);
            page.eraseColor(Color.BLUE);
            Bitmap crop = null;
            try {
                crop = AiSelectionRenderer.cropAndMask(page,
                        new RectF(200f, 100f, 2200f, 1600f), Collections.emptyList(),
                        false, 4000, 2400f, 1800f);
                assertEquals(1600, crop.getWidth());
                assertEquals(1200, crop.getHeight());
            } catch (IOException error) {
                throw new AssertionError(error);
            } finally {
                if (crop != null) crop.recycle();
                page.recycle();
            }
        });
    }

    @Test public void rejectedReadyCallbackDoesNotLeakItsBitmap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            NoteCanvasView.AiSelectionSnapshot result =
                    new NoteCanvasView.AiSelectionSnapshot(bitmap, new byte[]{1},
                            new RectF(0f, 0f, 2f, 2f), 0, 0, false);
            boolean accepted = AiSelectionRenderer.notifyReady(
                    new AiSelectionRenderer.Callback() {
                        @Override public void onReady(NoteCanvasView.AiSelectionSnapshot value) {
                            throw new IllegalStateException("consumer rejected result");
                        }
                        @Override public void onFailure(Exception error) { }
                    }, result);
            assertFalse(accepted);
            assertTrue("rejected callback retained an unowned bitmap", bitmap.isRecycled());
        });
    }

    @Test public void cancelBeforeQueuedUiTextRenderSignalsAndFinishesWorker() throws Exception {
        AtomicReference<AiSelectionRenderer> rendererRef = new AtomicReference<>();
        AtomicReference<AiSelectionRenderer.Job> jobRef = new AtomicReference<>();
        AtomicReference<NoteCanvasView> canvasRef = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
                NoteCanvasView canvas = new NoteCanvasView(context);
                canvasRef.set(canvas);
                AiSelectionRenderer renderer = new AiSelectionRenderer();
                rendererRef.set(renderer);
                try {
                    canvas.layout(0, 0, 800, 1000);
                    NoteTextBox fragment = canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                            "cancel-before-text-render", 40f, 80f);
                    assertTrue(canvas.selectAiTextFragment(fragment.id));
                    NoteCanvasView.AiSelectionRenderRequest request =
                            canvas.captureAiSelectionRenderRequest(1600);
                    AiSelectionRenderer.Job job = renderer.start(request,
                            new FrameLayout(context), new AiSelectionRenderer.Callback() {
                                @Override public void onReady(
                                        NoteCanvasView.AiSelectionSnapshot value) {
                                    callbacks.incrementAndGet();
                                    value.bitmap.recycle();
                                }
                                @Override public void onFailure(Exception error) {
                                    callbacks.incrementAndGet();
                                }
                            });
                    jobRef.set(job);

                    // Hold the UI queue after the worker posts startTextRender,
                    // so cancellation lands before that queued task can install
                    // a WebView or register its PageCallback.
                    try {
                        assertTrue("worker did not post the UI render phase",
                                job.textStartPosted.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    Bitmap page;
                    synchronized (job) { page = job.pageBitmap; }
                    assertTrue("base page was not handed to the UI owner", page != null);
                    job.cancel();
                    assertEquals("terminal cancellation must wake the worker's actual wait latch",
                            0, job.textReady.getCount());
                    assertTrue("UI cleanup did not recycle the cancelled intermediate page",
                            page.isRecycled());
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });

            AiSelectionRenderer.Job job = jobRef.get();
            assertTrue("worker remained blocked after early cancellation",
                    job.workerFinished.await(2, TimeUnit.SECONDS));
            assertEquals(1, callbacks.get());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                AiSelectionRenderer.Job job = jobRef.get();
                if (job != null) job.cancel();
                AiSelectionRenderer renderer = rendererRef.get();
                if (renderer != null) renderer.close();
                NoteCanvasView canvas = canvasRef.get();
                if (canvas != null) canvas.onDetachedFromWindow();
            });
        }
    }

    private static Bitmap coloredPage() {
        Bitmap page = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888);
        page.eraseColor(Color.RED);
        Canvas canvas = new Canvas(page);
        Paint paint = new Paint();
        paint.setColor(Color.BLUE);
        canvas.drawRect(20f, 10f, 80f, 70f, paint);
        return page;
    }

    private static void assertAllPixels(Bitmap bitmap, int expected) {
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                assertEquals("unexpected pixel at " + x + "," + y,
                        expected, bitmap.getPixel(x, y));
            }
        }
    }

    private static void assertNoRed(Bitmap bitmap) {
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                assertFalse("unselected red page content leaked at " + x + "," + y,
                        bitmap.getPixel(x, y) == Color.RED);
            }
        }
    }
}
