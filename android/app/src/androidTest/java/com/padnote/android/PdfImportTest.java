package com.padnote.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.pdf.PdfDocument;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PdfImportTest {
    @Test public void importSelectOriginalAndPortableRoundTrip() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ByteArrayOutputStream pdfBytes = new ByteArrayOutputStream();
        PdfDocument pdf = new PdfDocument();
        try {
            for (int i = 0; i < 2; i++) {
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(300, 400, i).create());
                Paint red = new Paint(); red.setColor(Color.RED);
                page.getCanvas().drawRect(0, 0, 150, 200, red);
                pdf.finishPage(page);
            }
            pdf.writeTo(pdfBytes);
        } finally { pdf.close(); }
        NoteStore.Entry original = PdfNoteIO.importFile(context,
                new ByteArrayInputStream(pdfBytes.toByteArray()), "PDF 回归测试");
        NoteStore.Entry restored = null;
        try {
            JSONObject document = NoteStore.load(context, original.id);
            assertEquals(2, document.getInt("pdfPageCount"));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                NoteCanvasView canvas = new NoteCanvasView(context);
                try {
                    canvas.layout(0, 0, 800, 1000);
                    canvas.loadJsonDocument(document);
                    Field selection = NoteCanvasView.class.getDeclaredField("selectionMaskPoints");
                    selection.setAccessible(true);
                    @SuppressWarnings("unchecked") List<PointF> points = (List<PointF>) selection.get(canvas);
                    points.add(new PointF(50, 50)); points.add(new PointF(200, 50));
                    points.add(new PointF(200, 200)); points.add(new PointF(50, 200));
                    assertEquals(1, canvas.getSelectionCount());
                    NoteCanvasView.AiSelectionSnapshot snapshot = canvas.renderAiSelection(800);
                    assertNotNull(snapshot);
                    assertEquals(Color.RED, snapshot.bitmap.getPixel(snapshot.bitmap.getWidth() / 2,
                            snapshot.bitmap.getHeight() / 2));
                    snapshot.bitmap.recycle();
                    assertEquals(2, canvas.toJsonDocument(original.id, original.title).getInt("pdfPageCount"));
                } catch (Exception error) { throw new AssertionError(error); }
                finally { canvas.onDetachedFromWindow(); }
            });
            ByteArrayOutputStream packed = new ByteArrayOutputStream();
            PdfNoteIO.exportFile(context, document, packed);
            restored = PdfNoteIO.importFile(context, new ByteArrayInputStream(packed.toByteArray()), "还原");
            assertNotEquals(original.id, restored.id);
            assertEquals(2, NoteStore.load(context, restored.id).getInt("pdfPageCount"));
            assertTrue(NoteStore.pdfFile(context, restored.id).isFile());
        } finally {
            NoteStore.delete(context, original.id);
            if (restored != null) NoteStore.delete(context, restored.id);
        }
    }
}
