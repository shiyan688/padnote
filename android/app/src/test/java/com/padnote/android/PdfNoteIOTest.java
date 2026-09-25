package com.padnote.android;

import android.content.Context;
import android.content.ContextWrapper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import static org.junit.Assert.*;

public class PdfNoteIOTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder(new File("build"));
    private Context context() {
        return new ContextWrapper(null) {
            @Override public File getFilesDir() { return temporary.getRoot(); }
            @Override public File getCacheDir() { return temporary.getRoot(); }
        };
    }
    private JSONObject document() throws Exception {
        return new JSONObject().put("schemaVersion", 6).put("title", "测试笔记")
                .put("pageCount", 1).put("strokes", new JSONArray()).put("textFlows", new JSONArray());
    }
    @Test public void boundedCopyRejectsOversizedContent() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfNoteIO.copy(new ByteArrayInputStream(new byte[16]), out, 16);
        assertEquals(16, out.size());
        assertThrows(IOException.class, () -> PdfNoteIO.copy(new ByteArrayInputStream(new byte[17]), out, 16));
    }
    @Test public void existingJsonImportAndExportStillWork() throws Exception {
        Context context = context();
        NoteStore.Entry entry = PdfNoteIO.importFile(context,
                new ByteArrayInputStream(document().toString().getBytes(StandardCharsets.UTF_8)), "fallback");
        assertEquals("测试笔记", entry.title);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfNoteIO.exportFile(context, NoteStore.load(context, entry.id), out);
        assertEquals(entry.id, new JSONObject(out.toString("UTF-8")).getString("id"));
    }
    @Test public void pdfMetadataWithoutOriginalIsRejected() throws Exception {
        JSONObject doc = document().put("schemaVersion", 7).put("pdfPageCount", 1);
        assertThrows(IllegalArgumentException.class, () -> NoteStore.importDocument(context(), doc, "test"));
    }
    @Test public void archivePathsAreNotExtracted() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("../escaped.pdf"));
            zip.write(new byte[]{1});
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> PdfNoteIO.importFile(context(),
                new ByteArrayInputStream(out.toByteArray()), "test"));
        assertEquals(0, temporary.getRoot().listFiles().length);
    }

    @Test public void flattenedExportResolutionHonorsTotalPixelBudget() throws Exception {
        assertEquals(PdfNoteIO.PREFERRED_EXPORT_LONG_EDGE,
                PdfNoteIO.chooseRasterLongEdge(5, 600, 800));
        int manyPages = PdfNoteIO.chooseRasterLongEdge(500, 600, 800);
        assertTrue(manyPages >= PdfNoteIO.MIN_EXPORT_LONG_EDGE);
        assertTrue(manyPages < PdfNoteIO.PREFERRED_EXPORT_LONG_EDGE);
        assertThrows(IOException.class,
                () -> PdfNoteIO.chooseRasterLongEdge(500, 800, 800));
    }
}
