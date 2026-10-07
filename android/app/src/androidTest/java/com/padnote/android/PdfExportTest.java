package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Device regressions for the real flattened-PDF pipeline, including WebView text. */
@RunWith(AndroidJUnit4.class)
public final class PdfExportTest {
    private static final String TAG = "PdfExportTest";
    private static final int PAGE_WIDTH = 600;
    private static final int PAGE_HEIGHT = 800;
    private static final int PAGE_GAP = 24;

    @Test
    public void realBaseRendererCanDownsampleToBudgetedLongEdge() throws Exception {
        JSONObject document = basicDocument("pdf_budget_" + UUID.randomUUID()
                .toString().replace("-", ""), 1);
        try (ExportSession session = ExportSession.open(document, null)) {
            Bitmap rendered = session.snapshot.renderBasePage(0, 720);
            try {
                assertEquals(540, rendered.getWidth());
                assertEquals(720, rendered.getHeight());
                assertEquals(388_800L,
                        (long) rendered.getWidth() * rendered.getHeight());
            } finally {
                rendered.recycle();
            }
        }
    }

    @Test
    public void invalidDiagramFailsFlattenedExportInsteadOfReportingACompletePdf()
            throws Exception {
        JSONObject document = basicDocument("pdf_bad_diagram_" + UUID.randomUUID()
                .toString().replace("-", ""), 1);
        document.getJSONArray("textFlows").put(new TextFlow("bad-diagram",
                NoteTextBox.Format.MARKDOWN,
                "```mermaid\nflowchart TD\nA -->\n```", 16f, 1.35f,
                500f, 0, 50f, 60f).toJson());
        try (ExportSession session = ExportSession.open(document, null)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            IOException error = assertThrows(IOException.class, () ->
                    PdfNoteIO.exportFlattenedPdf(session.snapshot, session.renderHost,
                            output, new Handler(Looper.getMainLooper()), null));
            assertTrue("export failure did not identify the local display problem: " + error,
                    containsMessage(error, "语法") || containsMessage(error, "显示"));
            assertEquals("failed export returned a seemingly complete PDF", 0, output.size());
        }
    }

    @Test
    public void flattenedPdfContainsEveryLayerOnItsActualPageAndOcrPngStaysTextFree()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String noteId = "pdf_export_" + UUID.randomUUID().toString().replace("-", "");
        File sourcePdf = NoteStore.pdfFile(context, noteId);
        writeBackgroundPdf(sourcePdf);
        JSONObject document = richDocument(noteId);

        try (ExportSession session = ExportSession.open(document, sourcePdf)) {
            NoteTextBox markdown = fragment(session.fragments, "markdown-page-0", 0);
            NoteTextBox formula = fragment(session.fragments, "formula-page-0", 0);
            NoteTextBox diagram = fragment(session.fragments, "diagram-page-1", 1);
            NoteTextBox overflow = tailFragment(session.fragments, "overflow-page-1",
                    "TAIL_END_42");
            List<NoteTextBox> overflowFragments = fragmentsForFlow(
                    session.fragments, "overflow-page-1");
            assertNotNull("Markdown fixture was not laid out on page 1", markdown);
            assertNotNull("LaTeX fixture was not laid out on page 1", formula);
            assertNotNull("Mermaid fixture was not laid out on non-current page 2", diagram);
            assertNotNull("long flow did not continue onto a later page", overflow);
            assertTrue("long flow was not paginated into readable fragments",
                    overflowFragments.size() > 1);
            assertTrue("heading was orphaned without its first body line",
                    overflowFragments.get(0).fragmentSource.contains("# 跨页记录") &&
                            overflowFragments.get(0).fragmentSource.contains("第 1 行"));
            int previousPage = -1;
            for (int index = 0; index < overflowFragments.size(); index++) {
                NoteTextBox part = overflowFragments.get(index);
                assertEquals("one flow changed authored font size between fragments",
                        16f, part.fontSizeSp, 0.01f);
                assertTrue("ordinary flow fragments must advance in page order",
                        part.pageIndex > previousPage);
                previousPage = part.pageIndex;
                String trimmed = part.fragmentSource.trim();
                assertFalse("fragment split after a bare Chinese ordinal prefix: " + trimmed,
                        trimmed.matches("(?s).*第\\s*\\d*$") || trimmed.endsWith("第"));
                if (index + 1 < overflowFragments.size()) {
                    assertTrue("intermediate prose fragment did not end at a sentence boundary: " +
                                    trimmed,
                            trimmed.endsWith("。") || trimmed.endsWith("！") ||
                                    trimmed.endsWith("？") || trimmed.endsWith(".") ||
                                    trimmed.endsWith("!") || trimmed.endsWith("?"));
                }
            }
            NoteTextBox finalPart = overflowFragments.get(overflowFragments.size() - 1);
            assertTrue("final flow line was left alone on a new page",
                    finalPart.fragmentSource.contains("尾段第二行仍需可见。") &&
                            finalPart.fragmentSource.contains("最终尾行 TAIL_END_42"));
            int highestFragmentPage = highestFragmentPage(session.fragments);
            assertEquals("the marked tail must be the final fragment of the long flow",
                    highestFragmentPage, overflow.pageIndex);
            assertEquals("document length must follow the furthest complete content fragment",
                    Math.max(5, highestFragmentPage + 1), session.pageCount);

            Bitmap ocrPage = BitmapFactory.decodeByteArray(session.ocrPageZero, 0,
                    session.ocrPageZero.length);
            assertNotNull("OCR page PNG did not decode", ocrPage);
            try {
                assertEquals("digitization PNG must still omit authored text flows", 0,
                        countDarkPixels(ocrPage, fragmentRect(markdown, ocrPage), 140));
                assertColorNear("OCR PNG lost its PDF background", Color.rgb(255, 242, 190),
                        sample(ocrPage, 580, 100), 35);
            } finally {
                ocrPage.recycle();
            }

            File output = new File(context.getCacheDir(), "pdf-export-regression.pdf");
            if (output.exists()) assertTrue("could not replace prior PDF fixture", output.delete());
            AtomicInteger progress = new AtomicInteger();
            try (FileOutputStream stream = new FileOutputStream(output)) {
                PdfNoteIO.exportFlattenedPdf(session.snapshot, session.renderHost, stream,
                        new Handler(Looper.getMainLooper()),
                        (completed, total) -> progress.set(completed));
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { });
            assertTrue("export did not create a real PDF", output.isFile() && output.length() > 1000L);
            assertEquals("progress did not reach every exported page", session.pageCount,
                    progress.get());
            Log.i(TAG, "flattened PDF fixture: " + output.getAbsolutePath());

            try (RenderedPdf rendered = RenderedPdf.open(output)) {
                assertEquals("flattened PDF dropped or added pages", session.pageCount,
                        rendered.pageCount());
                Bitmap first = rendered.render(0);
                Bitmap second = rendered.render(1);
                Bitmap later = rendered.render(overflow.pageIndex);
                try {
                    assertColorNear("page 1 PDF background missing", Color.rgb(255, 242, 190),
                            sample(first, 580, 100), 35);
                    assertColorNear("page 1 background marker missing", Color.MAGENTA,
                            sample(first, 35, 735), 35);
                    assertColorNear("inserted image missing", Color.rgb(0, 180, 70),
                            sample(first, 490, 690), 40);
                    assertColorNear("page 1 ink missing", Color.RED,
                            sample(first, 350, 620), 55);
                    assertTrue("Markdown text is absent from flattened PDF",
                            countDarkPixels(first, fragmentRect(markdown, first), 140) > 30);
                    assertTrue("formula is absent from flattened PDF",
                            countDarkPixels(first, fragmentRect(formula, first), 140) > 20);

                    assertColorNear("page 2 PDF background missing", Color.rgb(205, 240, 255),
                            sample(second, 590, 100), 35);
                    assertColorNear("page 2 background marker missing", Color.BLUE,
                            sample(second, 565, 745), 45);
                    assertColorNear("non-current-page ink missing", Color.rgb(0, 150, 80),
                            sample(second, 570, 380), 55);
                    assertTrue("Mermaid is absent from the non-current exported page",
                            countDarkPixels(second, fragmentRect(diagram, second), 160) > 25);
                    assertTrue("cross-page text continuation is absent from its later page",
                            countDarkPixels(later, fragmentRect(overflow, later), 140) > 20);
                } finally {
                    first.recycle();
                    second.recycle();
                    later.recycle();
                }
            }
        }
    }

    @Test
    public void lowMarkerMovesIntactAndExtendsDocumentByExactlyOnePage() throws Exception {
        JSONObject document = basicDocument("pdf_low_marker_" + UUID.randomUUID()
                .toString().replace("-", ""), 5);
        document.getJSONArray("textFlows").put(new TextFlow(
                "bottom-marker-overflow", NoteTextBox.Format.MARKDOWN,
                "BOTTOM_MARKER_VISIBLE", 18f, 1.35f, 300f, 4, 50f, 700f).toJson());
        try (ExportSession session = ExportSession.open(document, null)) {
            assertTrue("marker must not leave a clipped fragment on its requested page",
                    fragment(session.fragments, "bottom-marker-overflow", 4) == null);
            NoteTextBox moved = fragment(session.fragments, "bottom-marker-overflow", 5);
            assertNotNull("marker was lost instead of moving intact to the next page", moved);
            assertEquals("moving the marker must add exactly one physical page",
                    6, session.pageCount);
            float localTop = moved.y - moved.pageIndex * (PAGE_HEIGHT + PAGE_GAP);
            assertTrue("moved marker starts above the paper", localTop >= 0f);
            assertTrue("moved marker is still clipped at the page bottom",
                    localTop + moved.height <= PAGE_HEIGHT);
            assertEquals("marker source changed while moving pages",
                    "BOTTOM_MARKER_VISIBLE", moved.fragmentSource);
        }
    }

    @Test
    public void outputFailureThrowsInsteadOfReturningSuccess() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String noteId = "pdf_failure_" + UUID.randomUUID().toString().replace("-", "");
        JSONObject document = basicDocument(noteId, 1);
        try (ExportSession session = ExportSession.open(document, null)) {
            AtomicBoolean returnedSuccessfully = new AtomicBoolean(false);
            OutputStream broken = new OutputStream() {
                private int remaining = 128;
                @Override public void write(int value) throws IOException {
                    if (--remaining < 0) throw new IOException("intentional test write failure");
                }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (length > remaining) throw new IOException("intentional test write failure");
                    remaining -= length;
                }
            };
            try {
                PdfNoteIO.exportFlattenedPdf(session.snapshot, session.renderHost, broken,
                        new Handler(Looper.getMainLooper()), (completed, total) -> { });
                returnedSuccessfully.set(true);
                fail("a failed destination write must escape the export API");
            } catch (Exception expected) {
                assertTrue("export failed before exercising the destination write: " + expected,
                        containsMessage(expected, "intentional test write failure"));
            }
            assertFalse("caller could report success after a failed write",
                    returnedSuccessfully.get());
        }
    }

    @Test
    public void cancellationBeforeFirstPageWritesNoPdfBytes() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject document = basicDocument("pdf_cancel_" + UUID.randomUUID()
                .toString().replace("-", ""), 3);
        try (ExportSession session = ExportSession.open(document, null)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                PdfNoteIO.exportFlattenedPdf(session.snapshot, session.renderHost, output,
                        new Handler(Looper.getMainLooper()), (completed, total) -> { },
                        () -> true);
                fail("cancelled export must not report success");
            } catch (PdfNoteIO.ExportCancelledException expected) {
                assertEquals("PDF 导出已取消", expected.getMessage());
            }
            assertEquals("cancelled export wrote a misleading partial PDF", 0, output.size());
        }
    }

    @Test
    public void cancellationDuringBaseRenderReleasesSnapshotAndWritesNothing() throws Exception {
        JSONObject document = basicDocument("pdf_cancel_render_" + UUID.randomUUID()
                .toString().replace("-", ""), 3);
        try (ExportSession session = ExportSession.open(document, null)) {
            AtomicInteger checks = new AtomicInteger();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertThrows(PdfNoteIO.ExportCancelledException.class, () ->
                    PdfNoteIO.exportFlattenedPdf(session.snapshot, session.renderHost, output,
                            new Handler(Looper.getMainLooper()), (completed, total) -> { },
                            () -> checks.incrementAndGet() >= 2));
            assertEquals(0, output.size());
        }
    }

    @Test
    public void cancellationRaisedByDestinationWriteCannotReturnSuccess() throws Exception {
        JSONObject document = basicDocument("pdf_cancel_write_" + UUID.randomUUID()
                .toString().replace("-", ""), 1);
        try (ExportSession session = ExportSession.open(document, null)) {
            AtomicBoolean cancelled = new AtomicBoolean(false);
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            OutputStream destination = new OutputStream() {
                @Override public void write(int value) {
                    received.write(value);
                    cancelled.set(true);
                }
                @Override public void write(byte[] bytes, int offset, int length) {
                    received.write(bytes, offset, length);
                    cancelled.set(true);
                }
            };
            assertThrows(PdfNoteIO.ExportCancelledException.class, () ->
                    PdfNoteIO.exportFlattenedPdf(session.snapshot, session.renderHost,
                            destination, new Handler(Looper.getMainLooper()),
                            (completed, total) -> { }, cancelled::get));
            assertTrue("fixture must reach the destination write", received.size() > 0);
        }
    }

    private static JSONObject richDocument(String id) throws Exception {
        JSONObject document = basicDocument(id, 5).put("pdfPageCount", 2);
        JSONArray flows = document.getJSONArray("textFlows");
        flows.put(new TextFlow("markdown-page-0", NoteTextBox.Format.MARKDOWN,
                "# PDF 导出标题\n这段正文必须出现在最终 PDF。", 18f, 1.35f,
                500f, 0, 50f, 55f).toJson());
        flows.put(new TextFlow("formula-page-0", NoteTextBox.Format.LATEX,
                "\\displaystyle \\int_0^1 x^2\\,dx=\\frac{1}{3}", 22f, 1.35f,
                500f, 0, 50f, 300f).toJson());
        flows.put(new TextFlow("diagram-page-1", NoteTextBox.Format.MARKDOWN,
                "```mermaid\nflowchart LR\nA[输入] --> B[计算] --> C[结果]\n```", 16f, 1.35f,
                500f, 1, 50f, 60f).toJson());
        StringBuilder longText = new StringBuilder("# 跨页记录\n");
        for (int index = 1; index <= 42; index++) {
            longText.append("第 ").append(index).append(" 行必须在后续页面继续保留。\n");
        }
        longText.append("尾段第一行仍需可见。\n")
                .append("尾段第二行仍需可见。\n")
                .append("最终尾行 TAIL_END_42");
        flows.put(new TextFlow("overflow-page-1", NoteTextBox.Format.MARKDOWN,
                longText.toString(), 16f, 1.35f, 500f, 1, 50f, 500f).toJson());
        JSONArray strokes = document.getJSONArray("strokes");
        strokes.put(stroke("page-zero-ink", Color.RED, 18f,
                new float[][]{{300f, 620f}, {400f, 620f}}));
        float secondTop = PAGE_HEIGHT + PAGE_GAP;
        strokes.put(stroke("page-one-ink", Color.rgb(0, 150, 80), 18f,
                new float[][]{{570f, secondTop + 320f}, {570f, secondTop + 440f}}));

        Bitmap marker = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
        marker.eraseColor(Color.rgb(0, 180, 70));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(marker.compress(Bitmap.CompressFormat.PNG, 100, png));
        marker.recycle();
        String encoded = Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP);
        document.getJSONArray("images").put(new JSONObject()
                .put("id", "green-image").put("png", encoded).put("page", 0)
                .put("x", 450).put("y", 650).put("width", 80).put("height", 80));
        return document;
    }

    private static JSONObject basicDocument(String id, int pageCount) throws Exception {
        return new JSONObject().put("schemaVersion", 8).put("id", id)
                .put("title", "PDF 导出回归").put("pageWidth", PAGE_WIDTH)
                .put("pageHeight", PAGE_HEIGHT).put("pageGap", PAGE_GAP)
                .put("pageCount", pageCount).put("strokes", new JSONArray())
                .put("textFlows", new JSONArray()).put("images", new JSONArray())
                .put("textBoxes", new JSONArray()).put("pageStyle", new JSONObject()
                        .put("paper", "blank").put("ratio", "screen")
                        .put("landscape", false));
    }

    private static JSONObject stroke(String id, int color, float width, float[][] points)
            throws Exception {
        JSONArray values = new JSONArray();
        long now = System.currentTimeMillis();
        for (int index = 0; index < points.length; index++) {
            values.put(new JSONObject().put("x", points[index][0]).put("y", points[index][1])
                    .put("timestamp", now + index).put("pressure", 1));
        }
        return new JSONObject().put("id", id).put("color", String.format("#%08X", color))
                .put("baseWidth", width).put("createdAt", now).put("points", values);
    }

    private static void writeBackgroundPdf(File output) throws Exception {
        File parent = output.getParentFile();
        assertTrue(parent.isDirectory() || parent.mkdirs());
        PdfDocument pdf = new PdfDocument();
        try {
            for (int pageIndex = 0; pageIndex < 2; pageIndex++) {
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(
                        PAGE_WIDTH, PAGE_HEIGHT, pageIndex + 1).create());
                Canvas canvas = page.getCanvas();
                Paint paint = new Paint();
                paint.setColor(pageIndex == 0 ? Color.rgb(255, 242, 190)
                        : Color.rgb(205, 240, 255));
                canvas.drawRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT, paint);
                paint.setColor(pageIndex == 0 ? Color.MAGENTA : Color.BLUE);
                canvas.drawRect(pageIndex == 0 ? 10 : 540, 710,
                        pageIndex == 0 ? 60 : 590, 780, paint);
                pdf.finishPage(page);
            }
            try (FileOutputStream stream = new FileOutputStream(output)) {
                pdf.writeTo(stream);
            }
        } finally {
            pdf.close();
        }
    }

    private static NoteTextBox fragment(List<NoteTextBox> fragments, String flowId, int page) {
        for (NoteTextBox box : fragments) {
            if (flowId.equals(box.flowId) && box.pageIndex == page) return box;
        }
        return null;
    }

    private static NoteTextBox tailFragment(List<NoteTextBox> fragments, String flowId,
                                            String marker) {
        for (NoteTextBox box : fragments) {
            if (flowId.equals(box.flowId) && box.fragmentSource.contains(marker)) return box;
        }
        return null;
    }

    private static List<NoteTextBox> fragmentsForFlow(List<NoteTextBox> fragments,
                                                       String flowId) {
        List<NoteTextBox> matches = new ArrayList<>();
        for (NoteTextBox box : fragments) {
            if (flowId.equals(box.flowId)) matches.add(box);
        }
        return matches;
    }

    private static int highestFragmentPage(List<NoteTextBox> fragments) {
        int highest = -1;
        for (NoteTextBox box : fragments) highest = Math.max(highest, box.pageIndex);
        return highest;
    }

    private static Rect fragmentRect(NoteTextBox box, Bitmap page) {
        float scaleX = page.getWidth() / (float) PAGE_WIDTH;
        float scaleY = page.getHeight() / (float) PAGE_HEIGHT;
        float localY = box.y - box.pageIndex * (PAGE_HEIGHT + PAGE_GAP);
        return new Rect(Math.max(0, Math.round(box.x * scaleX)),
                Math.max(0, Math.round(localY * scaleY)),
                Math.min(page.getWidth(), Math.round((box.x + box.width) * scaleX)),
                Math.min(page.getHeight(), Math.round((localY + box.height) * scaleY)));
    }

    private static int countDarkPixels(Bitmap bitmap, Rect area, int threshold) {
        int count = 0;
        for (int y = area.top; y < area.bottom; y += 2) {
            for (int x = area.left; x < area.right; x += 2) {
                int color = bitmap.getPixel(x, y);
                int luminance = (Color.red(color) * 299 + Color.green(color) * 587 +
                        Color.blue(color) * 114) / 1000;
                if (Color.alpha(color) > 200 && luminance < threshold) count++;
            }
        }
        return count;
    }

    private static int sample(Bitmap bitmap, float pageX, float pageY) {
        int x = Math.min(bitmap.getWidth() - 1,
                Math.max(0, Math.round(pageX * bitmap.getWidth() / PAGE_WIDTH)));
        int y = Math.min(bitmap.getHeight() - 1,
                Math.max(0, Math.round(pageY * bitmap.getHeight() / PAGE_HEIGHT)));
        return bitmap.getPixel(x, y);
    }

    private static void assertColorNear(String message, int expected, int actual, int tolerance) {
        assertTrue(message + ": expected=" + Integer.toHexString(expected) +
                        " actual=" + Integer.toHexString(actual),
                Math.abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
                        Math.abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
                        Math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance);
    }

    private static boolean containsMessage(Throwable error, String expected) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            if (cursor.getMessage() != null && cursor.getMessage().contains(expected)) return true;
        }
        return false;
    }

    private static final class RenderedPdf implements AutoCloseable {
        private final ParcelFileDescriptor descriptor;
        private final PdfRenderer renderer;

        private RenderedPdf(ParcelFileDescriptor descriptor, PdfRenderer renderer) {
            this.descriptor = descriptor;
            this.renderer = renderer;
        }

        static RenderedPdf open(File file) throws Exception {
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_READ_ONLY);
            try {
                return new RenderedPdf(descriptor, new PdfRenderer(descriptor));
            } catch (Exception error) {
                descriptor.close();
                throw error;
            }
        }

        int pageCount() { return renderer.getPageCount(); }

        Bitmap render(int index) {
            try (PdfRenderer.Page page = renderer.openPage(index)) {
                Bitmap bitmap = Bitmap.createBitmap(page.getWidth(), page.getHeight(),
                        Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            }
        }

        @Override public void close() throws Exception {
            renderer.close();
            descriptor.close();
        }
    }

    private static final class ExportSession implements AutoCloseable {
        final ActivityScenario<MainActivity> scenario;
        final NoteCanvasView canvas;
        final FrameLayout renderHost;
        final NoteCanvasView.PdfExportSnapshot snapshot;
        final List<NoteTextBox> fragments;
        final byte[] ocrPageZero;
        final int pageCount;
        final File sourcePdf;

        private ExportSession(ActivityScenario<MainActivity> scenario, NoteCanvasView canvas,
                              FrameLayout renderHost,
                              NoteCanvasView.PdfExportSnapshot snapshot,
                              List<NoteTextBox> fragments, byte[] ocrPageZero,
                              int pageCount, File sourcePdf) {
            this.scenario = scenario;
            this.canvas = canvas;
            this.renderHost = renderHost;
            this.snapshot = snapshot;
            this.fragments = fragments;
            this.ocrPageZero = ocrPageZero;
            this.pageCount = pageCount;
            this.sourcePdf = sourcePdf;
        }

        static ExportSession open(JSONObject document, File sourcePdf) {
            ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
            AtomicReference<NoteCanvasView> canvasRef = new AtomicReference<>();
            AtomicReference<FrameLayout> hostRef = new AtomicReference<>();
            AtomicReference<NoteCanvasView.PdfExportSnapshot> snapshotRef =
                    new AtomicReference<>();
            AtomicReference<List<NoteTextBox>> fragmentsRef =
                    new AtomicReference<>(new ArrayList<>());
            AtomicReference<byte[]> ocrRef = new AtomicReference<>();
            AtomicInteger pageCount = new AtomicInteger();
            try {
                scenario.onActivity(activity -> {
                    try {
                        FrameLayout root = new FrameLayout(activity);
                        NoteCanvasView canvas = new NoteCanvasView(activity);
                        FrameLayout renderHost = new FrameLayout(activity);
                        renderHost.setVisibility(View.VISIBLE);
                        renderHost.setAlpha(0.01f);
                        renderHost.setClipChildren(false);
                        renderHost.setClipToPadding(false);
                        root.addView(canvas, new FrameLayout.LayoutParams(PAGE_WIDTH, PAGE_HEIGHT));
                        root.addView(renderHost, new FrameLayout.LayoutParams(1, 1));
                        activity.setContentView(root, new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        int width = Math.max(PAGE_WIDTH, activity.getResources()
                                .getDisplayMetrics().widthPixels);
                        int height = Math.max(PAGE_HEIGHT, activity.getResources()
                                .getDisplayMetrics().heightPixels);
                        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                        root.layout(0, 0, width, height);
                        canvas.loadJsonDocument(document);
                        canvasRef.set(canvas);
                        hostRef.set(renderHost);
                        fragmentsRef.set(canvas.getTextBoxes());
                        ocrRef.set(canvas.renderPagePng(0, 1600));
                        pageCount.set(canvas.getPageCount());
                        snapshotRef.set(canvas.createPdfExportSnapshot());
                    } catch (Exception error) {
                        throw new AssertionError("could not prepare real PDF export fixture", error);
                    }
                });
                assertNotNull("canvas was not attached", canvasRef.get());
                assertTrue("export renderer host must be attached",
                        hostRef.get() != null && hostRef.get().isAttachedToWindow());
                assertNotNull("OCR fixture page was not rendered", ocrRef.get());
                assertNotNull("PDF export snapshot was not created", snapshotRef.get());
                return new ExportSession(scenario, canvasRef.get(), hostRef.get(), snapshotRef.get(),
                        fragmentsRef.get(), ocrRef.get(), pageCount.get(), sourcePdf);
            } catch (RuntimeException | Error error) {
                NoteCanvasView.PdfExportSnapshot snapshot = snapshotRef.get();
                if (snapshot != null) {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(snapshot::close);
                }
                scenario.close();
                if (sourcePdf != null && sourcePdf.exists()) sourcePdf.delete();
                throw error;
            }
        }

        @Override public void close() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(snapshot::close);
            scenario.close();
            if (sourcePdf != null && sourcePdf.exists()) {
                assertTrue("could not remove source PDF fixture", sourcePdf.delete());
            }
        }
    }
}
