package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Real Android PdfDocument/PdfRenderer checks for the physical-page export policy. */
@RunWith(AndroidJUnit4.class)
public final class PdfPhysicalPageSizeInstrumentedTest {
    private static final int EXPORT_LONG_EDGE = 1800;
    private static final int A4_SHORT = 595;
    private static final int A4_LONG = 842;

    @Test public void a4PortraitAndLandscapeUsePointsIndependentOfRasterPixels()
            throws Exception {
        assertA4Export(700f, 1000f, A4_SHORT, A4_LONG);
        assertA4Export(1000f, 700f, A4_LONG, A4_SHORT);
    }

    @Test public void screenAndLegacyNotesContainTheirRatioInsideA4Bounds()
            throws Exception {
        JSONObject screen = document("screen_" + id(), 1600f, 900f, "screen", 1);
        try (Fixture fixture = Fixture.open(screen, false)) {
            PdfPageGeometry geometry = fixture.geometry;
            assertTrue(geometry.mediaWidthPt <= A4_LONG);
            assertTrue(geometry.mediaHeightPt <= A4_SHORT);
            assertRatio(1600d / 900d,
                    geometry.mediaWidthPt / (double) geometry.mediaHeightPt, 0.004d);
            assertContentBoxFits(geometry);
            assertExportMatchesGeometry(fixture, geometry);
            assertCanonicalUnchanged(fixture);
        }

        JSONObject legacy = document("legacy_" + id(), 1200f, 1600f, null, 1)
                .put("schemaVersion", 5).put("textFlows", new JSONArray())
                .put("textBoxes", new JSONArray());
        legacy.remove("pageStyle");
        try (Fixture fixture = Fixture.open(legacy, false)) {
            PdfPageGeometry geometry = fixture.geometry;
            assertTrue(geometry.mediaWidthPt <= A4_SHORT);
            assertTrue(geometry.mediaHeightPt <= A4_LONG);
            assertRatio(0.75d,
                    geometry.mediaWidthPt / (double) geometry.mediaHeightPt, 0.003d);
            assertContentBoxFits(geometry);
            assertExportMatchesGeometry(fixture, geometry);
            assertCanonicalUnchanged(fixture);
        }
    }

    @Test public void sourcePdfUsesFirstPagePointsForEveryExportedPageAndRemainsUnchanged()
            throws Exception {
        String noteId = "source_pdf_" + id();
        JSONObject doc = document(noteId, 800f, 618.1818f, "screen", 2)
                .put("pdfPageCount", 2);
        try (Fixture fixture = Fixture.open(doc, true)) {
            assertEquals(792, fixture.geometry.mediaWidthPt);
            assertEquals(612, fixture.geometry.mediaHeightPt);
            assertContentBoxFits(fixture.geometry);
            String originalPdfSha = sha256(fixture.sourcePdf);
            byte[] exported = fixture.export();
            assertEquals("export mutated the original imported source PDF",
                    originalPdfSha, sha256(fixture.sourcePdf));
            assertCanonicalUnchanged(fixture);
            try (RenderedPdf pdf = RenderedPdf.open(fixture.writeExport(exported))) {
                assertEquals(2, pdf.pageCount());
                assertEquals(792, pdf.width(0));
                assertEquals(612, pdf.height(0));
                assertEquals("all note pages should use the first source page dimensions",
                        pdf.width(0), pdf.width(1));
                assertEquals(pdf.height(0), pdf.height(1));
                Bitmap first = pdf.render(0);
                Bitmap second = pdf.render(1);
                try {
                    assertTrue("first source page color/marker was lost by contain rendering",
                            countColor(first, Color.rgb(255, 230, 170)) > 10_000);
                    assertTrue("second mixed-size source page color/marker was lost",
                            countColor(second, Color.rgb(180, 225, 255)) > 1_000);
                    assertTrue("source first-page upper-left boundary marker disappeared",
                            countColorInRegion(first, Color.MAGENTA, 0f, 0f, 0.2f, 0.2f) > 100);
                    assertTrue("source first-page lower-right boundary marker disappeared",
                            countColorInRegion(first, Color.MAGENTA, 0.8f, 0.8f, 1f, 1f) > 100);
                    assertTrue("contained mixed-page upper-left marker disappeared",
                            countColorInRegion(second, Color.BLUE, 0.2f, 0f, 0.4f, 0.2f) > 100);
                    assertTrue("contained mixed-page lower-right marker disappeared",
                            countColorInRegion(second, Color.BLUE, 0.6f, 0.8f, 0.8f, 1f) > 100);
                } finally {
                    first.recycle();
                    second.recycle();
                }
            }
        }
    }

    @Test public void oversizedSourcePdfBlocksPhysicalExportButNotPaperRasterCapture()
            throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String noteId = "oversized_pdf_" + id();
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<File> isolatedRoot = new AtomicReference<>();
        AtomicReference<Boolean> fixtureSupported = new AtomicReference<>(true);
        AtomicReference<String> originalSha = new AtomicReference<>();
        AtomicReference<String> afterSha = new AtomicReference<>();
        AtomicReference<String> beforeCanonical = new AtomicReference<>();
        AtomicReference<String> afterCanonical = new AtomicReference<>();
        AtomicReference<String> localizedFailure = new AtomicReference<>();
        AtomicReference<String> expectedLocalizedFailure = new AtomicReference<>();
        AtomicReference<Integer> rasterWidth = new AtomicReference<>();
        AtomicReference<Integer> rasterHeight = new AtomicReference<>();
        AtomicReference<Integer> backgroundPixels = new AtomicReference<>(0);
        AtomicReference<Integer> undoBefore = new AtomicReference<>();
        AtomicReference<Integer> undoAfter = new AtomicReference<>();
        try {
            scenario.onActivity(activity -> {
                NoteCanvasView.PdfExportSnapshot rasterSnapshot = null;
                try {
                    File work = new File(target.getCacheDir(), "pdf-overcap-" + id());
                    if (!work.mkdirs()) throw new java.io.IOException("isolated test directory unavailable");
                    isolatedRoot.set(work);
                    Context isolated = new ContextWrapper(activity) {
                        @Override public File getFilesDir() { return work; }
                        @Override public File getCacheDir() { return work; }
                    };
                    File source = NoteStore.pdfFile(isolated, noteId);
                    if (!source.getCanonicalPath().startsWith(work.getCanonicalPath() + File.separator)) {
                        throw new java.io.IOException("synthetic PDF escaped its private test directory");
                    }
                    try {
                        writeOversizedSourcePdf(source);
                    } catch (IllegalArgumentException | IllegalStateException unsupported) {
                        fixtureSupported.set(false);
                        return;
                    }
                    if (!source.isFile() || source.length() < 100 || !hasPdfHeader(source)) {
                        throw new java.io.IOException("synthetic oversized PDF was not fully written");
                    }
                    RenderedPdf generated = null;
                    try {
                        generated = RenderedPdf.open(source);
                        if (generated.pageCount() != 1 ||
                                generated.width(0) <= PdfPageGeometry.MAX_EDGE_PT) {
                            fixtureSupported.set(false);
                            return;
                        }
                    } catch (Exception rendererCannotReadOversizedPage) {
                        // The generated file is non-empty and has a PDF header; only the
                        // platform parser's inability to handle its unusual page size skips it.
                        fixtureSupported.set(false);
                        return;
                    } finally {
                        if (generated != null) generated.close();
                    }
                    originalSha.set(sha256(source));

                    JSONObject document = oversizedCanvasDocument(noteId);
                    FrameLayout root = new FrameLayout(activity);
                    NoteCanvasView canvas = new NoteCanvasView(isolated);
                    root.addView(canvas, new FrameLayout.LayoutParams(800, 100));
                    activity.setContentView(root, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    root.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY));
                    root.layout(0, 0, 900, 300);
                    canvas.loadJsonDocument(document);
                    expectedLocalizedFailure.set(
                            isolated.getString(R.string.canvas_pdf_page_size_invalid));
                    beforeCanonical.set(canonicalContent(canvas.toJsonDocument(noteId, "overcap")));
                    undoBefore.set(canvas.undoDepthForTest());
                    try {
                        NoteCanvasView.PdfExportSnapshot unexpected = canvas.createPdfExportSnapshot();
                        unexpected.close();
                        throw new AssertionError("over-limit PDF physical dimensions were accepted");
                    } catch (java.io.IOException expected) {
                        localizedFailure.set(expected.getMessage());
                    }

                    rasterSnapshot = canvas.createPaperRasterSnapshot();
                    Bitmap page = rasterSnapshot.renderBasePage(0, EXPORT_LONG_EDGE);
                    try {
                        rasterWidth.set(page.getWidth());
                        rasterHeight.set(page.getHeight());
                        backgroundPixels.set(countColor(page, Color.rgb(30, 190, 70)));
                    } finally {
                        page.recycle();
                    }
                    afterCanonical.set(canonicalContent(canvas.toJsonDocument(noteId, "overcap")));
                    undoAfter.set(canvas.undoDepthForTest());
                    afterSha.set(sha256(source));
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    if (rasterSnapshot != null) rasterSnapshot.close();
                }
            });
            if (failure.get() != null) throw new AssertionError("over-limit PDF regression", failure.get());
            Assume.assumeTrue("PdfDocument cannot create a page beyond the product point cap",
                    fixtureSupported.get());
            assertNotNull(localizedFailure.get());
            assertEquals("snapshot failure must use the canvas locale's size message",
                    expectedLocalizedFailure.get(), localizedFailure.get());
            assertEquals(originalSha.get(), afterSha.get());
            assertEquals(beforeCanonical.get(), afterCanonical.get());
            assertEquals(undoBefore.get(), undoAfter.get());
            assertTrue(rasterWidth.get() > 0 && rasterHeight.get() > 0);
            assertTrue("raster-only snapshot did not render the synthetic PDF background",
                    backgroundPixels.get() > 100);
        } finally {
            scenario.close();
            deleteTree(isolatedRoot.get());
        }
    }

    private static void assertA4Export(float width, float height, int mediaWidth, int mediaHeight)
            throws Exception {
        JSONObject doc = document("a4_" + id(), width, height, "a4", 1);
        try (Fixture fixture = Fixture.open(doc, false)) {
            assertEquals(mediaWidth, fixture.geometry.mediaWidthPt);
            assertEquals(mediaHeight, fixture.geometry.mediaHeightPt);
            assertContentBoxFits(fixture.geometry);
            Bitmap raster = fixture.snapshot.renderBasePage(0, EXPORT_LONG_EDGE);
            try {
                assertEquals("raster long edge must remain the independent pixel budget",
                        EXPORT_LONG_EDGE, Math.max(raster.getWidth(), raster.getHeight()));
                assertNotEquals("PDF points must not reuse bitmap pixel dimensions",
                        Math.max(raster.getWidth(), raster.getHeight()),
                        Math.max(mediaWidth, mediaHeight));
            } finally {
                raster.recycle();
            }
            assertExportMatchesGeometry(fixture, fixture.geometry);
            assertCanonicalUnchanged(fixture);
        }
    }

    private static void assertExportMatchesGeometry(Fixture fixture, PdfPageGeometry geometry)
            throws Exception {
        try (RenderedPdf pdf = RenderedPdf.open(fixture.writeExport(fixture.export()))) {
            assertEquals(geometry.mediaWidthPt, pdf.width(0));
            assertEquals(geometry.mediaHeightPt, pdf.height(0));
            assertEquals(fixture.pageCount, pdf.pageCount());
        }
    }

    private static void assertCanonicalUnchanged(Fixture fixture) throws Exception {
        AtomicReference<JSONObject> after = new AtomicReference<>();
        fixture.scenario.onActivity(activity -> {
            try {
                after.set(fixture.canvas.toJsonDocument(fixture.noteId, "physical size test"));
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        });
        assertNotNull(after.get());
        JSONObject before = fixture.before;
        JSONObject now = after.get();
        for (String key : new String[]{"schemaVersion", "id", "pageWidth", "pageHeight",
                "pageGap", "pageCount", "pdfPageCount", "pageStyle", "strokes",
                "textFlows", "textBoxes", "images", "authorPageEditSerial",
                "authorPageTopologySerial"}) {
            if (before.has(key) || now.has(key)) {
                assertEquals("PDF export changed canonical field " + key,
                        canonicalValue(before.opt(key)), canonicalValue(now.opt(key)));
            }
        }
        assertEquals("PDF export changed Undo history", fixture.undoDepth,
                fixture.canvas.undoDepthForTest());
    }

    private static void assertContentBoxFits(PdfPageGeometry geometry) {
        assertTrue(geometry.contentLeftPt >= 0f && geometry.contentTopPt >= 0f);
        assertTrue(geometry.contentWidthPt > 0f && geometry.contentHeightPt > 0f);
        assertTrue(geometry.contentLeftPt + geometry.contentWidthPt <= geometry.mediaWidthPt + 0.01f);
        assertTrue(geometry.contentTopPt + geometry.contentHeightPt <= geometry.mediaHeightPt + 0.01f);
    }

    private static void assertRatio(double expected, double actual, double tolerance) {
        assertTrue("aspect ratio changed: expected " + expected + " actual " + actual,
                Math.abs(expected - actual) <= tolerance);
    }

    private static String canonicalValue(Object value) {
        return value == null ? "<absent>" : value.toString();
    }

    private static String canonicalContent(JSONObject document) throws Exception {
        JSONObject selected = new JSONObject();
        for (String key : new String[]{"schemaVersion", "id", "pageWidth", "pageHeight",
                "pageGap", "pageCount", "pdfPageCount", "pageStyle", "strokes",
                "textFlows", "textBoxes", "images", "authorPageEditSerial",
                "authorPageTopologySerial"}) {
            if (document.has(key)) selected.put(key, document.opt(key));
        }
        return selected.toString();
    }

    private static JSONObject document(String noteId, float width, float height,
                                      String ratio, int pages) throws Exception {
        long createdAt = System.currentTimeMillis();
        JSONArray points = new JSONArray()
                .put(new JSONObject().put("x", 80).put("y", 80)
                        .put("timestamp", createdAt).put("pressure", 1))
                .put(new JSONObject().put("x", 140).put("y", 100)
                        .put("timestamp", createdAt + 1).put("pressure", 1));
        JSONArray strokes = new JSONArray().put(new JSONObject().put("id", "geometry-ink")
                .put("color", "#FFFF0000").put("baseWidth", 8).put("createdAt", createdAt)
                .put("points", points));
        Bitmap image = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.rgb(20, 170, 70));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            if (!image.compress(Bitmap.CompressFormat.PNG, 100, png)) {
                throw new IllegalStateException("could not encode PNG fixture");
            }
        } finally {
            image.recycle();
        }
        JSONArray images = new JSONArray().put(new JSONObject().put("id", "geometry-image")
                .put("png", Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP))
                .put("page", 0).put("x", 200).put("y", 220).put("width", 48).put("height", 48));
        JSONArray flows = new JSONArray().put(new TextFlow("geometry-flow",
                NoteTextBox.Format.MARKDOWN, "Geometry content marker", 16f, 1.35f,
                260f, 0, 80f, 130f).toJson());
        JSONObject doc = new JSONObject().put("schemaVersion", 8).put("id", noteId)
                .put("title", "PDF physical geometry test").put("pageWidth", width)
                .put("pageHeight", height).put("pageGap", 24).put("pageCount", pages)
                .put("strokes", strokes).put("textFlows", flows)
                .put("textBoxes", new JSONArray()).put("images", images);
        if (ratio != null) {
            doc.put("pageStyle", new JSONObject().put("paper", "blank")
                    .put("ratio", ratio).put("landscape", width > height));
        }
        return doc;
    }

    private static JSONObject oversizedCanvasDocument(String noteId) throws Exception {
        long now=System.currentTimeMillis();
        JSONArray points=new JSONArray()
                .put(new JSONObject().put("x",10).put("y",10).put("timestamp",now).put("pressure",1))
                .put(new JSONObject().put("x",30).put("y",20).put("timestamp",now+1).put("pressure",1));
        JSONArray strokes=new JSONArray().put(new JSONObject().put("id","overcap-ink")
                .put("color","#FF2040C0").put("baseWidth",2).put("createdAt",now).put("points",points));
        return new JSONObject().put("schemaVersion",8).put("id",noteId)
                .put("title","Oversized source physical geometry QA")
                .put("pageWidth",800).put("pageHeight",34).put("pageGap",24)
                .put("pageCount",1).put("pdfPageCount",1).put("strokes",strokes)
                .put("textFlows",new JSONArray()).put("textBoxes",new JSONArray())
                .put("images",new JSONArray()).put("pageStyle",new JSONObject()
                        .put("paper","blank").put("ratio","screen").put("landscape",true));
    }

    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }

    private static int countColor(Bitmap bitmap, int target) {
        int count = 0;
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        for (int pixel : pixels) {
            if (Math.abs(Color.red(pixel) - Color.red(target)) <= 24 &&
                    Math.abs(Color.green(pixel) - Color.green(target)) <= 24 &&
                    Math.abs(Color.blue(pixel) - Color.blue(target)) <= 24) count++;
        }
        return count;
    }

    private static int countColorInRegion(Bitmap bitmap,int target,float leftFraction,
                                          float topFraction,float rightFraction,float bottomFraction) {
        int left=Math.max(0,Math.round(bitmap.getWidth()*leftFraction));
        int top=Math.max(0,Math.round(bitmap.getHeight()*topFraction));
        int right=Math.min(bitmap.getWidth(),Math.round(bitmap.getWidth()*rightFraction));
        int bottom=Math.min(bitmap.getHeight(),Math.round(bitmap.getHeight()*bottomFraction));
        int count=0;
        for(int y=top;y<bottom;y++) for(int x=left;x<right;x++) {
            int pixel=bitmap.getPixel(x,y);
            if(Math.abs(Color.red(pixel)-Color.red(target))<=24 &&
                    Math.abs(Color.green(pixel)-Color.green(target))<=24 &&
                    Math.abs(Color.blue(pixel)-Color.blue(target))<=24) count++;
        }
        return count;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static boolean hasPdfHeader(File file) throws Exception {
        byte[] header=new byte[5];
        try(java.io.FileInputStream input=new java.io.FileInputStream(file)) {
            return input.read(header)==header.length && header[0]=='%' && header[1]=='P' &&
                    header[2]=='D' && header[3]=='F' && header[4]=='-';
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ActivityScenario<MainActivity> scenario;
        final NoteCanvasView canvas;
        final FrameLayout renderHost;
        final NoteCanvasView.PdfExportSnapshot snapshot;
        final PdfPageGeometry geometry;
        final JSONObject before;
        final int undoDepth;
        final String noteId;
        final int pageCount;
        final File workDir;
        final File sourcePdf;
        final Context isolatedContext;

        private Fixture(ActivityScenario<MainActivity> scenario, NoteCanvasView canvas,
                        FrameLayout renderHost, NoteCanvasView.PdfExportSnapshot snapshot,
                        JSONObject before, String noteId, int pageCount, File workDir,
                        File sourcePdf, Context isolatedContext) throws Exception {
            this.scenario=scenario; this.canvas=canvas; this.renderHost=renderHost;
            this.snapshot=snapshot; this.geometry=snapshot.getPdfPageGeometry(); this.before=before;
            this.noteId=noteId; this.pageCount=pageCount; this.workDir=workDir;
            this.sourcePdf=sourcePdf; this.isolatedContext=isolatedContext;
            this.undoDepth=canvas.undoDepthForTest();
        }

        static Fixture open(JSONObject document, boolean withSourcePdf) throws Exception {
            ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class);
            AtomicReference<NoteCanvasView> canvasRef=new AtomicReference<>();
            AtomicReference<FrameLayout> hostRef=new AtomicReference<>();
            AtomicReference<NoteCanvasView.PdfExportSnapshot> snapshotRef=new AtomicReference<>();
            AtomicReference<JSONObject> beforeRef=new AtomicReference<>();
            AtomicReference<File> workRef=new AtomicReference<>();
            AtomicReference<File> sourceRef=new AtomicReference<>();
            AtomicReference<Context> contextRef=new AtomicReference<>();
            String noteId=document.optString("id");
            try {
                scenario.onActivity(activity -> {
                    try {
                        File work = new File(activity.getCacheDir(), "pdf-size-" + id());
                        if (!work.mkdirs()) throw new java.io.IOException("cannot create isolated test directory");
                        workRef.set(work);
                        Context isolated = new ContextWrapper(activity) {
                            @Override public File getFilesDir() { return work; }
                            @Override public File getCacheDir() { return work; }
                        };
                        File source = null;
                        if (withSourcePdf) {
                            source=NoteStore.pdfFile(isolated,noteId);
                            String workPath=work.getCanonicalPath()+File.separator;
                            if (!source.getCanonicalPath().startsWith(workPath)) {
                                throw new java.io.IOException("PDF fixture path escaped its isolated test directory");
                            }
                            File parent=source.getParentFile();
                            if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                                throw new java.io.IOException("cannot create private source directory");
                            writeMixedSourcePdf(source);
                        }
                        FrameLayout root=new FrameLayout(activity);
                        NoteCanvasView canvas=new NoteCanvasView(isolated);
                        FrameLayout host=new FrameLayout(activity);
                        host.setVisibility(View.VISIBLE); host.setAlpha(0.01f);
                        host.setClipChildren(false); host.setClipToPadding(false);
                        root.addView(canvas,new FrameLayout.LayoutParams(800,820));
                        root.addView(host,new FrameLayout.LayoutParams(1,1));
                        activity.setContentView(root,new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
                        root.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.EXACTLY));
                        root.layout(0,0,1000,1200);
                        canvas.loadJsonDocument(document);
                        JSONObject before=canvas.toJsonDocument(noteId,"physical size test");
                        NoteCanvasView.PdfExportSnapshot snapshot=canvas.createPdfExportSnapshot();
                        canvasRef.set(canvas); hostRef.set(host); snapshotRef.set(snapshot);
                        beforeRef.set(before); sourceRef.set(source); contextRef.set(isolated);
                    } catch (Exception error) { throw new AssertionError("fixture setup failed",error); }
                });
                assertNotNull(canvasRef.get()); assertNotNull(snapshotRef.get());
                assertTrue("renderer host must be attached",hostRef.get().isAttachedToWindow());
                return new Fixture(scenario,canvasRef.get(),hostRef.get(),snapshotRef.get(),
                        beforeRef.get(),noteId,document.optInt("pageCount",1),workRef.get(),
                        sourceRef.get(),contextRef.get());
            } catch (Throwable failure) {
                scenario.close();
                deleteTree(workRef.get());
                throw failure;
            }
        }

        byte[] export() throws Exception {
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            PdfNoteIO.exportFlattenedPdf(snapshot,renderHost,output,
                    new Handler(Looper.getMainLooper()),null);
            return output.toByteArray();
        }

        File writeExport(byte[] bytes) throws Exception {
            File file=new File(workDir,"output-"+id()+".pdf");
            try(FileOutputStream output=new FileOutputStream(file)){output.write(bytes);}
            return file;
        }

        @Override public void close() {
            try { snapshot.close(); }
            finally {
                try { scenario.close(); }
                finally { deleteTree(workDir); }
            }
        }
    }

    private static void writeMixedSourcePdf(File file) throws Exception {
        PdfDocument document=new PdfDocument();
        try {
            int[] widths={792,700}; int[] heights={612,1000}; int[] colors={
                    Color.rgb(255,230,170),Color.rgb(180,225,255)};
            int[] markers={Color.MAGENTA,Color.BLUE};
            for(int index=0;index<2;index++) {
                PdfDocument.Page page=document.startPage(new PdfDocument.PageInfo.Builder(
                        widths[index],heights[index],index+1).create());
                Canvas canvas=page.getCanvas(); Paint paint=new Paint();
                paint.setColor(colors[index]); canvas.drawColor(colors[index]);
                paint.setColor(markers[index]);
                canvas.drawRect(20,20,120,120,paint);
                canvas.drawRect(widths[index]-120,heights[index]-120,
                        widths[index]-20,heights[index]-20,paint);
                document.finishPage(page);
            }
            try(FileOutputStream output=new FileOutputStream(file)){document.writeTo(output);}
        } finally { document.close(); }
    }

    private static void writeOversizedSourcePdf(File file) throws Exception {
        PdfDocument document = new PdfDocument();
        try {
            PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(
                    PdfPageGeometry.MAX_EDGE_PT + 1, 612, 1).create());
            page.getCanvas().drawColor(Color.rgb(30, 190, 70));
            document.finishPage(page);
            try (FileOutputStream output = new FileOutputStream(file)) {
                document.writeTo(output);
            }
        } finally {
            document.close();
        }
    }

    private static void deleteTree(File path) {
        if (path == null || !path.exists()) return;
        File[] children=path.listFiles();
        if (children!=null) for(File child:children) deleteTree(child);
        // The path is always a UUID-owned child of this test's cache directory.
        path.delete();
    }

    private static final class RenderedPdf implements AutoCloseable {
        final ParcelFileDescriptor descriptor;
        final PdfRenderer renderer;
        private RenderedPdf(ParcelFileDescriptor descriptor,PdfRenderer renderer) {
            this.descriptor=descriptor; this.renderer=renderer;
        }
        static RenderedPdf open(File file) throws Exception {
            ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
            try{return new RenderedPdf(descriptor,new PdfRenderer(descriptor));}
            catch(Exception error){descriptor.close();throw error;}
        }
        int pageCount(){return renderer.getPageCount();}
        int width(int index){try(PdfRenderer.Page page=renderer.openPage(index)){return page.getWidth();}}
        int height(int index){try(PdfRenderer.Page page=renderer.openPage(index)){return page.getHeight();}}
        Bitmap render(int index){
            try(PdfRenderer.Page page=renderer.openPage(index)){
                Bitmap bitmap=Bitmap.createBitmap(page.getWidth(),page.getHeight(),Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);return bitmap;
            }
        }
        @Override public void close() throws Exception {renderer.close();descriptor.close();}
    }
}
