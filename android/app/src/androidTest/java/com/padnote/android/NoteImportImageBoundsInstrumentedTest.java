package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(AndroidJUnit4.class)
public final class NoteImportImageBoundsInstrumentedTest {
    @Test public void pdfArchiveAtCanvasOneUnitToleranceImportsAndOpens() throws Exception {
        Fixture fixture = newFixture();
        NoteStore.Entry entry = null;
        try {
            JSONObject document = imageDocument("PDF image bounds at tolerance", 600, 800,
                    520, 721, 80, 80, true);
            entry = PdfNoteIO.importFile(fixture.context,
                    new ByteArrayInputStream(padnote(document, onePagePdf())),
                    "PDF image bounds at tolerance");

            JSONObject stored = NoteStore.load(fixture.context, entry.id);
            assertEquals(1, stored.getInt("pdfPageCount"));
            assertEquals(801d, stored.getJSONArray("images").getJSONObject(0).getDouble("y")
                    + stored.getJSONArray("images").getJSONObject(0).getDouble("height"), 0.0001d);
            assertTrue(NoteStore.pdfFile(fixture.context, entry.id).isFile());
            assertTrue(new File(fixture.files, "padnote-index.json").isFile());

            assertCanvasLoads(fixture.context, stored, "Canvas must accept its +1 edge tolerance");
            assertNotNull(entry);
        } finally {
            closeFixture(fixture, entry);
        }
    }

    @Test public void pdfArchivePastCanvasToleranceLeavesNoIndexOrFiles() throws Exception {
        Fixture fixture = newFixture();
        try {
            JSONObject document = imageDocument("PDF image out of bounds", 600, 800,
                    520, 722, 80, 80, true);
            try {
                PdfNoteIO.importFile(fixture.context,
                        new ByteArrayInputStream(padnote(document, onePagePdf())),
                        "PDF image out of bounds");
                fail("out-of-bounds image import must be rejected before commit");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("图片超出页面范围"));
            }

            assertFalse(new File(fixture.files, "padnote-index.json").exists());
            File notes = new File(fixture.files, NoteStore.notesDirectoryName());
            assertFalse("note directory must not be created by a rejected import", notes.exists());
            assertEquals(0, fixture.cache.list().length);
            assertEquals(0, fixture.files.list().length);
        } finally {
            fixture.close();
        }
    }

    @Test public void legacyCanvasDimensionFallbackStillImportsBoundedImage() throws Exception {
        Fixture fixture = newFixture();
        NoteStore.Entry entry = null;
        try {
            JSONObject document = imageDocument("Legacy canvas dimensions", 600, 800,
                    520, 720, 80, 80, false);
            document.remove("pageWidth");
            document.remove("pageHeight");
            entry = PdfNoteIO.importFile(fixture.context,
                    new ByteArrayInputStream(padnote(document, null)),
                    "Legacy canvas dimensions");
            JSONObject stored = NoteStore.load(fixture.context, entry.id);
            assertEquals(600d, stored.getDouble("canvasWidth"), 0d);
            assertEquals(800d, stored.getDouble("canvasHeight"), 0d);
            assertCanvasLoads(fixture.context, stored,
                    "Canvas should use the legacy canvasWidth/canvasHeight fallback");
        } finally {
            closeFixture(fixture, entry);
        }
    }

    private static JSONObject imageDocument(String title, int width, int height,
                                            int x, int y, int imageWidth, int imageHeight,
                                            boolean pdf) throws Exception {
        JSONObject document = new JSONObject().put("schemaVersion", 8)
                .put("id", "fixture-import-image-bounds")
                .put("title", title).put("updatedAt", 1)
                .put("canvasWidth", width).put("canvasHeight", height)
                .put("pageWidth", width).put("pageHeight", height)
                .put("pageGap", 24).put("pageCount", 1)
                .put("strokes", new JSONArray()).put("textFlows", new JSONArray())
                .put("textBoxes", new JSONArray());
        if (pdf) document.put("pdfPageCount", 1);
        byte[] png = tinyPng();
        String encoded = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP);
        document.put("images", new JSONArray().put(new JSONObject()
                .put("id", "synthetic-image")
                .put("png", encoded).put("page", 0).put("x", x).put("y", y)
                .put("width", imageWidth).put("height", imageHeight)));
        return document;
    }

    private static byte[] tinyPng() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.MAGENTA);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static void assertCanvasLoads(Context context, JSONObject document, String message) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NoteCanvasView canvas = new NoteCanvasView(context);
            try {
                canvas.layout(0, 0, 800, 1000);
                canvas.loadJsonDocument(document);
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                canvas.onDetachedFromWindow();
            }
        });
        if (failure.get() != null) throw new AssertionError(message, failure.get());
    }

    private static byte[] onePagePdf() throws Exception {
        PdfDocument pdf = new PdfDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(600, 800, 1).create());
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.LTGRAY);
            page.getCanvas().drawRect(0, 0, 600, 800, paint);
            paint.setColor(Color.BLUE);
            page.getCanvas().drawCircle(300, 400, 100, paint);
            pdf.finishPage(page);
            pdf.writeTo(output);
            return output.toByteArray();
        } finally {
            pdf.close();
        }
    }

    private static byte[] padnote(JSONObject document, byte[] pdf) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(output)) {
            put(archive, "note.json", document.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (pdf != null) put(archive, "source.pdf", pdf);
        }
        return output.toByteArray();
    }

    private static void put(ZipOutputStream archive, String name, byte[] bytes) throws Exception {
        archive.putNextEntry(new ZipEntry(name));
        archive.write(bytes);
        archive.closeEntry();
    }

    private static Fixture newFixture() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context test = InstrumentationRegistry.getInstrumentation().getContext();
        File root = new File(test.getCacheDir(), "note-import-image-bounds-" + UUID.randomUUID());
        File files = new File(root, "files");
        File cache = new File(root, "cache");
        assertTrue(root.mkdirs());
        assertTrue(files.mkdirs());
        assertTrue(cache.mkdirs());
        return new Fixture(root, files, cache, new IsolatedContext(target, files, cache));
    }

    private static final class Fixture implements AutoCloseable {
        final File root;
        final File files;
        final File cache;
        final Context context;
        Fixture(File root, File files, File cache, Context context) {
            this.root = root; this.files = files; this.cache = cache; this.context = context;
        }
        @Override public void close() throws Exception { deleteTree(root); }
    }

    private static void closeFixture(Fixture fixture, NoteStore.Entry entry) throws Exception {
        try {
            if (entry != null) NoteStore.delete(fixture.context, entry.id);
        } finally {
            fixture.close();
        }
    }

    private static final class IsolatedContext extends ContextWrapper {
        private final File files;
        private final File cache;
        IsolatedContext(Context base, File files, File cache) { super(base); this.files = files; this.cache = cache; }
        @Override public File getFilesDir() { return files; }
        @Override public File getCacheDir() { return cache; }
        @Override public File getDir(String name, int mode) {
            File directory = new File(files, "app_" + name);
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("fixture_dir_create");
            return directory;
        }
        @Override public Context getApplicationContext() { return this; }
    }

    private static void deleteTree(File root) throws Exception {
        if (!root.exists()) return;
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
            for (java.nio.file.Path path : paths.sorted(Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new)) {
                Files.deleteIfExists(path);
            }
        }
    }
}
