package com.padnote.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.View;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Original PDF stays on disk; only three visible-page bitmaps are retained. */
final class PdfBackground {
    private final File sourceFile;
    private final PdfRenderer renderer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<Integer, Bitmap> pages = new LruCache<Integer, Bitmap>(3) {
        @Override
        protected void entryRemoved(boolean evicted, Integer key, Bitmap oldValue,
                                    Bitmap newValue) {
            if (oldValue != null && oldValue != newValue && !oldValue.isRecycled()) {
                oldValue.recycle();
            }
        }
    };
    private final Set<Integer> pending = new HashSet<>();
    private final Set<Integer> failed = new HashSet<>();
    private final View owner;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF destination = new RectF();
    private volatile boolean closed;
    private final AtomicBoolean closeRequested = new AtomicBoolean();

    PdfBackground(File file, View owner) throws IOException {
        sourceFile = file;
        this.owner = owner;
        label.setColor(Color.GRAY);
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        try { renderer = new PdfRenderer(descriptor); }
        catch (IOException | RuntimeException error) { descriptor.close(); throw error; }
    }

    /** Opens an independent renderer so export survives the live canvas closing. */
    PdfBackground copyFor(View exportOwner) throws IOException {
        return new PdfBackground(sourceFile, exportOwner);
    }

    boolean hasPage(int index) { return index >= 0 && index < renderer.getPageCount(); }

    private synchronized Bitmap render(int index) {
        if (closed) throw new IllegalStateException("PDF 已关闭");
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            float scale = 1600f / Math.max(page.getWidth(), page.getHeight());
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth() * scale)),
                    Math.max(1, Math.round(page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        }
    }

    synchronized void draw(Canvas canvas, int index, RectF box, boolean immediate) {
        if (closeRequested.get() || closed || !hasPage(index)) return;
        Bitmap bitmap = pages.get(index);
        if (bitmap == null && immediate) {
            bitmap = render(index);
            pages.put(index, bitmap);
        }
        if (bitmap != null) {
            float scale = Math.min(box.width() / bitmap.getWidth(), box.height() / bitmap.getHeight());
            float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
            destination.set(box.centerX() - width / 2, box.centerY() - height / 2,
                    box.centerX() + width / 2, box.centerY() + height / 2);
            canvas.drawBitmap(bitmap, null, destination, paint);
        } else {
            label.setTextSize(box.width() / 35f);
            canvas.drawText(failed.contains(index) ? "PDF 页面渲染失败" : "正在加载 PDF…",
                    box.left + 20, box.top + 40, label);
            if (pending.contains(index) || failed.contains(index)) return;
            pending.add(index);
            worker.execute(() -> {
                try {
                    if (closed) return;
                    Bitmap result = render(index);
                    owner.post(() -> {
                        synchronized (PdfBackground.this) {
                            pending.remove(index);
                            if (!closed && !closeRequested.get()) {
                                pages.put(index, result);
                                owner.invalidate();
                            } else if (!result.isRecycled()) {
                                result.recycle();
                            }
                        }
                    });
                } catch (RuntimeException error) {
                    owner.post(() -> {
                        synchronized (PdfBackground.this) {
                            pending.remove(index);
                            if (!closed && !closeRequested.get()) {
                                failed.add(index);
                                Toast.makeText(owner.getContext(), "PDF 第 " + (index + 1)
                                        + " 页渲染失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                }
            });
        }
    }

    void close() {
        if (!closeRequested.compareAndSet(false, true)) return;
        // Closing never waits on the main thread. The worker acquires the same
        // monitor as draw(), so an in-progress export draw finishes before its
        // bitmap cache and renderer are released.
        worker.execute(() -> {
            synchronized (this) {
                closed = true;
                pages.evictAll();
                renderer.close();
            }
        });
        worker.shutdown();
    }
}
