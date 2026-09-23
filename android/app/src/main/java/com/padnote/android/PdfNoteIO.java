package com.padnote.android;

import android.content.Context;
import android.graphics.pdf.PdfRenderer;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Streaming PDF / portable notebook import; never embeds binary PDF in JSON. */
final class PdfNoteIO {
    static final long MAX_PDF_BYTES = 100L * 1024 * 1024;

    static void copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[32768];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IOException("文件过大：PDF 上限 100 MB，笔记数据上限 50 MB");
            output.write(buffer, 0, count);
        }
    }

    static JSONObject pdfDocument(File file, String title) throws Exception {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            int count = renderer.getPageCount();
            if (count < 1 || count > 500) throw new IOException("PDF 页数必须在 1–500 之间");
            try (PdfRenderer.Page first = renderer.openPage(0)) {
                float width = 800f;
                float height = width * first.getHeight() / first.getWidth();
                return new JSONObject().put("schemaVersion", 7).put("title", title)
                        .put("pageWidth", width).put("pageHeight", height)
                        .put("pageCount", count).put("pdfPageCount", count)
                        .put("strokes", new JSONArray()).put("textFlows", new JSONArray())
                        .put("textBoxes", new JSONArray());
            }
        }
    }

    static NoteStore.Entry importFile(Context context, InputStream input, String title) throws Exception {
        BufferedInputStream buffered = new BufferedInputStream(input);
        buffered.mark(8);
        byte[] magic = new byte[5];
        int count = buffered.read(magic);
        buffered.reset();
        boolean pdf = count == 5 && "%PDF-".equals(new String(magic, StandardCharsets.US_ASCII));
        boolean zip = count >= 2 && magic[0] == 'P' && magic[1] == 'K';
        if (!pdf && !zip) return NoteStore.importDocument(context,
                new JSONObject(NoteStore.readExternalNote(buffered)), title);
        File source = File.createTempFile("pdf-import-", ".pdf", context.getCacheDir());
        try {
            JSONObject document;
            if (pdf) {
                try (OutputStream output = new FileOutputStream(source)) { copy(buffered, output, MAX_PDF_BYTES); }
                document = pdfDocument(source, title);
            } else {
                document = null;
                boolean foundPdf = false;
                try (ZipInputStream archive = new ZipInputStream(buffered)) {
                    ZipEntry entry;
                    while ((entry = archive.getNextEntry()) != null) {
                        if ("note.json".equals(entry.getName()) && document == null) {
                            ByteArrayOutputStream json = new ByteArrayOutputStream();
                            copy(archive, json, 50L * 1024 * 1024);
                            document = new JSONObject(json.toString("UTF-8"));
                        } else if ("source.pdf".equals(entry.getName()) && !foundPdf) {
                            try (OutputStream output = new FileOutputStream(source)) { copy(archive, output, MAX_PDF_BYTES); }
                            foundPdf = true;
                        } else throw new IOException("不是支持的 PadNote 包（未知或重复文件）");
                    }
                }
                if (document == null) throw new IOException("笔记包缺少 note.json");
                if (document.optInt("pdfPageCount", 0) > 0) {
                    if (!foundPdf) throw new IOException("笔记包缺少 PDF 原文");
                    int actual = pdfDocument(source, title).getInt("pdfPageCount");
                    if (actual != document.getInt("pdfPageCount")) throw new IOException("PDF 页数与笔记不匹配");
                } else if (foundPdf) throw new IOException("PDF 缺少对应的笔记元数据");
            }
            return NoteStore.importDocument(context, document, title,
                    document.optInt("pdfPageCount", 0) > 0 ? source : null);
        } finally {
            if (!source.delete()) source.deleteOnExit();
        }
    }

    static void exportFile(Context context, JSONObject document, OutputStream output) throws Exception {
        if (document.optInt("pdfPageCount", 0) == 0) {
            output.write(document.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (ZipOutputStream archive = new ZipOutputStream(output)) {
            archive.putNextEntry(new ZipEntry("note.json"));
            archive.write(document.toString().getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
            archive.putNextEntry(new ZipEntry("source.pdf"));
            try (InputStream input = new FileInputStream(NoteStore.pdfFile(context, document.getString("id")))) {
                copy(input, archive, MAX_PDF_BYTES);
            }
            archive.closeEntry();
        }
    }

    interface ProgressListener {
        void onPageRendered(int completedPages, int totalPages);
    }

    /**
     * Creates a flattened PDF from a frozen document snapshot.
     *
     * <p>The caller owns one worker thread for the complete PdfDocument lifecycle.
     * Only attached WebView rendering is dispatched to the main thread. Returning
     * means every page was finished and the supplied stream was flushed.
     */
    static void exportFlattenedPdf(NoteCanvasView.PdfExportSnapshot snapshot,
                                   FrameLayout attachedRenderHost, OutputStream output,
                                   Handler mainHandler, ProgressListener progress)
            throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("PDF 文件写入不能在主线程执行");
        }
        if (snapshot == null || attachedRenderHost == null || output == null ||
                mainHandler == null) {
            throw new IllegalArgumentException("PDF 导出参数不完整");
        }
        PdfDocument pdf = new PdfDocument();
        try {
            int pages = snapshot.getPageCount();
            for (int page = 0; page < pages; page++) {
                Bitmap bitmap = snapshot.renderBasePage(page, 1800);
                AtomicReference<Bitmap> rendered = new AtomicReference<>();
                AtomicReference<Exception> failure = new AtomicReference<>();
                AtomicBoolean accepting = new AtomicBoolean(true);
                CountDownLatch ready = new CountDownLatch(1);
                int pageIndex = page;
                mainHandler.post(() -> {
                    NoteCanvasView.PdfExportSnapshot.PageCallback callback =
                            new NoteCanvasView.PdfExportSnapshot.PageCallback() {
                            @Override
                            public void onRendered(Bitmap completePage) {
                                if (accepting.compareAndSet(true, false)) {
                                    rendered.set(completePage);
                                    ready.countDown();
                                }
                            }

                            @Override
                            public void onFailure(Exception error) {
                                if (accepting.compareAndSet(true, false)) {
                                    failure.set(error);
                                    ready.countDown();
                                }
                            }
                        };
                    try {
                        snapshot.renderTextForPage(pageIndex, bitmap,
                                attachedRenderHost, callback);
                    } catch (RuntimeException | OutOfMemoryError error) {
                        snapshot.close();
                        callback.onFailure(new IOException("第 " + (pageIndex + 1) +
                                " 页文字渲染无法启动", error));
                    }
                });
                boolean pageReady;
                try {
                    pageReady = ready.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    accepting.set(false);
                    snapshot.close();
                    throw new IOException("PDF 导出已中断", interrupted);
                }
                if (!pageReady) {
                    accepting.set(false);
                    snapshot.close();
                    // Do not recycle here: a compositor callback already running
                    // on a stalled main thread may still hold the bitmap. Closing
                    // the snapshot invalidates it before that callback can draw.
                    throw new IOException("第 " + (page + 1) + " 页文字渲染超时");
                }
                if (failure.get() != null) {
                    bitmap.recycle();
                    throw failure.get();
                }
                Bitmap completedPage = rendered.get();
                if (completedPage == null || completedPage.isRecycled()) {
                    if (!bitmap.isRecycled()) bitmap.recycle();
                    throw new IOException("第 " + (page + 1) + " 页未完成渲染");
                }
                PdfDocument.Page current = null;
                try {
                    current = pdf.startPage(new PdfDocument.PageInfo.Builder(
                            completedPage.getWidth(), completedPage.getHeight(), page + 1).create());
                    current.getCanvas().drawBitmap(completedPage, 0, 0, null);
                } finally {
                    if (current != null) pdf.finishPage(current);
                    completedPage.recycle();
                }
                if (progress != null) {
                    int complete = page + 1;
                    mainHandler.post(() -> progress.onPageRendered(complete, pages));
                }
            }
            pdf.writeTo(output);
            output.flush();
        } finally {
            pdf.close();
            snapshot.close();
        }
    }
}
