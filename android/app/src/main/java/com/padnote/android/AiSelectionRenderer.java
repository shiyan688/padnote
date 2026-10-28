package com.padnote.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Renders one frozen paper selection. Whole-page pixels remain local and are never returned. */
final class AiSelectionRenderer implements AutoCloseable {
    static final int MAX_PAGE_PIXELS = 8_000_000;
    static final int MAX_OUTPUT_EDGE = 1600;
    private static final long TEXT_RENDER_TIMEOUT_SECONDS = 30;
    private static final int PAPER_WHITE = Color.WHITE;

    interface Callback {
        void onReady(NoteCanvasView.AiSelectionSnapshot snapshot);
        void onFailure(Exception error);
    }

    private final Object lock = new Object();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "padnote-ai-selection-renderer");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Job> jobs = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean closed;

    Job start(NoteCanvasView.AiSelectionRenderRequest request, FrameLayout attachedHost,
              Callback callback) {
        if (request == null || attachedHost == null || callback == null) {
            throw new IllegalArgumentException("选区渲染参数不完整");
        }
        Job job = new Job(request, attachedHost, callback);
        synchronized (lock) {
            if (closed) {
                job.abortRequested.set(true);
                postUi(() -> finishFailureOnUi(job,
                        new IOException("选区渲染器已关闭")));
                return job;
            }
            if (!jobs.isEmpty()) {
                job.abortRequested.set(true);
                postUi(() -> finishFailureOnUi(job,
                        new IOException("已有纸面选区正在渲染，请稍后重试")));
                return job;
            }
            jobs.add(job);
        }
        try {
            worker.execute(job::run);
        } catch (RejectedExecutionException rejected) {
            job.abortRequested.set(true);
            postUi(() -> finishFailureOnUi(job,
                    new IOException("选区渲染任务无法启动", rejected)));
        }
        return job;
    }

    @Override
    public void close() {
        List<Job> pending;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            pending = new ArrayList<>(jobs);
        }
        // Graceful shutdown: queued jobs run their cancellation cleanup instead
        // of being discarded while they own a snapshot or a page bitmap.
        worker.shutdown();
        for (Job job : pending) job.cancel();
    }

    static int pageRasterLongEdge(float pageWidth, float pageHeight, int maxPixels) {
        if (!Float.isFinite(pageWidth) || !Float.isFinite(pageHeight) ||
                pageWidth <= 0f || pageHeight <= 0f || maxPixels < 1) return 0;
        float longest = Math.max(pageWidth, pageHeight);
        long dimensionLimit = Math.max(1L, (long) Math.floor((double) longest));
        int low = 1;
        int high = (int) Math.min((long) maxPixels, dimensionLimit);
        int best = 1;
        // Binary search with the same float scale/rounding as renderBasePage.
        // Pixel count is monotonic in longEdge, and this avoids a potentially
        // long decrement loop for unusual aspect ratios.
        while (low <= high) {
            int candidate = low + ((high - low) >>> 1);
            if (rasterPixels(pageWidth, pageHeight, candidate) <= maxPixels) {
                best = candidate;
                low = candidate + 1;
            } else {
                high = candidate - 1;
            }
        }
        return best;
    }

    private static long rasterPixels(float pageWidth, float pageHeight, int longEdge) {
        float longest = Math.max(pageWidth, pageHeight);
        float scale = Math.min(2f, Math.max(1f / longest,
                Math.max(1, longEdge) / (float) longest));
        int width = Math.max(1, Math.round(pageWidth * scale));
        int height = Math.max(1, Math.round(pageHeight * scale));
        return (long) width * height;
    }

    static int[] cropRasterSize(float cropWidth, float cropHeight, int maxDimensionPx) {
        if (!Float.isFinite(cropWidth) || !Float.isFinite(cropHeight) ||
                cropWidth <= 0f || cropHeight <= 0f || maxDimensionPx < 1) return null;
        float scale = Math.min(1f, maxDimensionPx / Math.max(cropWidth, cropHeight));
        return new int[]{Math.max(1, Math.round(cropWidth * scale)),
                Math.max(1, Math.round(cropHeight * scale))};
    }

    /** Explicit raster ownership so cancellation cannot recycle a page during UI WebView draw. */
    static final class PageBitmapLease {
        enum Owner { UNALLOCATED, WORKER, UI, RELEASED }
        private Owner owner = Owner.UNALLOCATED;

        synchronized boolean workerCreated() {
            if (owner != Owner.UNALLOCATED) return false;
            owner = Owner.WORKER;
            return true;
        }

        synchronized boolean transferToUi() {
            if (owner != Owner.WORKER) return false;
            owner = Owner.UI;
            return true;
        }

        synchronized boolean transferToWorker() {
            if (owner != Owner.UI) return false;
            owner = Owner.WORKER;
            return true;
        }

        synchronized boolean releaseFromUi() {
            if (owner != Owner.UI) return false;
            owner = Owner.RELEASED;
            return true;
        }

        synchronized boolean releaseFromWorker() {
            if (owner != Owner.WORKER) return false;
            owner = Owner.RELEASED;
            return true;
        }

        synchronized boolean workerOwns() { return owner == Owner.WORKER; }
        synchronized boolean uiOwns() { return owner == Owner.UI; }
        synchronized Owner owner() { return owner; }
    }

    final class Job {
        final NoteCanvasView.AiSelectionRenderRequest request;
        final FrameLayout host;
        final Callback callback;
        final AtomicBoolean abortRequested = new AtomicBoolean();
        final AtomicBoolean delivered = new AtomicBoolean();
        final CountDownLatch textReady = new CountDownLatch(1);
        final CountDownLatch textStartPosted = new CountDownLatch(1);
        final CountDownLatch workerFinished = new CountDownLatch(1);
        final PageBitmapLease pageLease = new PageBitmapLease();
        volatile Exception textFailure;
        /** Snapshot resources must stay alive while this worker paints the base page. */
        boolean baseRendering;
        /** Non-null only while UI or snapshot may still draw into it. */
        Bitmap pageBitmap;

        Job(NoteCanvasView.AiSelectionRenderRequest request, FrameLayout host, Callback callback) {
            this.request = request;
            this.host = host;
            this.callback = callback;
        }

        void cancel() {
            if (!abortRequested.compareAndSet(false, true)) return;
            postUi(() -> finishFailureOnUi(this,
                    new IOException("选区渲染已取消")));
        }

        void run() {
            Bitmap base = null;
            Bitmap output = null;
            try {
                if (abortRequested.get()) return;
                NoteCanvasView.PdfExportSnapshot snapshot = request.snapshot;
                int edge = pageRasterLongEdge(snapshot.getPageWidth(), snapshot.getPageHeight(),
                        MAX_PAGE_PIXELS);
                if (edge < 1) throw new IOException("纸面尺寸无效");
                synchronized (this) {
                    if (abortRequested.get()) return;
                    baseRendering = true;
                }
                try {
                    base = snapshot.renderBasePage(request.pageIndex, edge);
                } finally {
                    synchronized (this) { baseRendering = false; }
                }
                if (abortRequested.get()) {
                    recycle(base);
                    base = null;
                    postUi(() -> finishFailureOnUi(this,
                            new IOException("选区渲染已取消")));
                    return;
                }
                synchronized (this) {
                    if (abortRequested.get()) {
                        recycle(base);
                        base = null;
                        return;
                    }
                    pageBitmap = base;
                    if (!pageLease.workerCreated() || !pageLease.transferToUi()) {
                        pageBitmap = null;
                        throw new IOException("纸面位图所有权交接失败");
                    }
                    base = null; // ownership transfers to the UI/WebView phase
                }
                postUi(() -> startTextRender(this));
                textStartPosted.countDown();
                if (!textReady.await(TEXT_RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    abortRequested.set(true);
                    postUi(() -> finishFailureOnUi(this,
                            new IOException("选区文字渲染超时")));
                    return;
                }
                synchronized (this) {
                    if (pageLease.workerOwns()) {
                        base = pageBitmap;
                        pageBitmap = null;
                    }
                }
                if (abortRequested.get()) return;
                if (textFailure != null) throw textFailure;
                if (base == null || base.isRecycled()) {
                    throw new IOException("纸面渲染未完成");
                }
                output = cropAndMask(base, request, snapshot.getPageWidth(),
                        snapshot.getPageHeight());
                if (base != null) {
                    boolean release;
                    synchronized (this) { release = pageLease.releaseFromWorker(); }
                    if (release) recycle(base);
                }
                base = null;
                if (abortRequested.get()) {
                    recycle(output);
                    output = null;
                    return;
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (!output.compress(Bitmap.CompressFormat.PNG, 100, bytes)) {
                    throw new IOException("选区 PNG 编码失败");
                }
                if (abortRequested.get()) {
                    recycle(output);
                    output = null;
                    return;
                }
                NoteCanvasView.AiSelectionSnapshot result = new NoteCanvasView.AiSelectionSnapshot(
                        output, bytes.toByteArray(), new RectF(request.sourceBounds),
                        request.strokeCount, request.pointCount, request.lassoMaskApplied);
                output = null; // ownership transfers to Main only after the UI callback accepts it
                postUi(() -> finishReadyOnUi(this, result));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                abortRequested.set(true);
                postUi(() -> finishFailureOnUi(this,
                        new IOException("选区渲染中断", interrupted)));
            } catch (Exception | OutOfMemoryError error) {
                abortRequested.set(true);
                Exception failure = error instanceof Exception
                        ? (Exception) error : new IOException("选区渲染内存不足", error);
                postUi(() -> finishFailureOnUi(this, failure));
            } finally {
                // base is worker-owned only before it is handed to UI or after the
                // text callback has handed it back. Never recycle during WebView draw.
                synchronized (this) {
                    if (base == null && pageLease.workerOwns()) {
                        base = pageBitmap;
                        pageBitmap = null;
                    }
                }
                if (base != null) {
                    boolean release;
                    synchronized (this) { release = pageLease.releaseFromWorker(); }
                    if (release) recycle(base);
                }
                recycle(output);
                workerFinished.countDown();
            }
        }
    }

    private void startTextRender(Job job) {
        if (job.abortRequested.get() || job.delivered.get()) {
            finishFailureOnUi(job, new IOException("选区渲染已取消"));
            return;
        }
        Bitmap page;
        synchronized (job) { page = job.pageBitmap; }
        if (page == null || page.isRecycled()) {
            finishFailureOnUi(job, new IOException("纸面渲染未完成"));
            return;
        }
        try {
            job.request.snapshot.renderTextForPage(job.request.pageIndex, page, job.host,
                    new NoteCanvasView.PdfExportSnapshot.PageCallback() {
                        @Override public void onRendered(Bitmap completed) {
                            runOnUi(() -> {
                                synchronized (job) {
                                    if (job.pageBitmap == completed) {
                                        if (job.abortRequested.get() || job.delivered.get()) {
                                            job.pageBitmap = null;
                                            if (job.pageLease.releaseFromUi()) recycle(completed);
                                        } else {
                                            job.pageLease.transferToWorker();
                                        }
                                    }
                                }
                                job.textReady.countDown();
                            });
                        }

                        @Override public void onFailure(Exception error) {
                            runOnUi(() -> {
                                synchronized (job) {
                                    if (job.pageBitmap == page) {
                                        job.pageBitmap = null;
                                        if (job.pageLease.releaseFromUi()) recycle(page);
                                    }
                                }
                                job.textFailure = error;
                                job.textReady.countDown();
                            });
                        }
                    });
        } catch (RuntimeException | OutOfMemoryError error) {
            synchronized (job) {
                if (job.pageBitmap == page) {
                    job.pageBitmap = null;
                    if (job.pageLease.releaseFromUi()) recycle(page);
                }
            }
            job.textFailure = new IOException("文字渲染无法启动", error);
            job.textReady.countDown();
        }
    }

    private void finishReadyOnUi(Job job, NoteCanvasView.AiSelectionSnapshot result) {
        if (job.abortRequested.get() || isClosed()) {
            recycle(result.bitmap);
            finishFailureOnUi(job, new IOException("选区渲染已取消"));
            return;
        }
        if (!job.delivered.compareAndSet(false, true)) {
            recycle(result.bitmap);
            return;
        }
        cleanupOnUi(job);
        try {
            notifyReady(job.callback, result);
        } finally {
            removeJob(job);
        }
    }

    /** Ownership transfers to the callback only when it returns normally. */
    static boolean notifyReady(Callback callback, NoteCanvasView.AiSelectionSnapshot result) {
        try {
            callback.onReady(result);
            return true;
        } catch (RuntimeException rejected) {
            recycle(result.bitmap);
            return false;
        }
    }

    private void finishFailureOnUi(Job job, Exception failure) {
        job.abortRequested.set(true);
        // Release a worker waiting for UI dispatch even when the corresponding
        // render task has not started and therefore has no PageCallback to signal.
        job.textReady.countDown();
        if (job.delivered.get()) return;
        synchronized (job) {
            if (job.baseRendering) {
                // The snapshot owns copied PDF/image resources used by the
                // worker's base-page draw. Let that draw return before closing it.
                return;
            }
        }
        cleanupOnUi(job);
        if (!job.delivered.compareAndSet(false, true)) return;
        try {
            job.callback.onFailure(failure);
        } finally {
            removeJob(job);
        }
    }

    private void cleanupOnUi(Job job) {
        // close() on the UI thread tears down an active WebView first and reports
        // its callback synchronously; only then is the page bitmap safe to recycle.
        job.request.close();
        Bitmap page;
        synchronized (job) {
            page = job.pageBitmap;
            if (page != null && job.pageLease.releaseFromUi()) {
                job.pageBitmap = null;
            } else {
                page = null; // a completed callback has transferred it back to the worker
            }
        }
        recycle(page);
        if (job.host.getParent() instanceof ViewGroup) {
            ((ViewGroup) job.host.getParent()).removeView(job.host);
        }
    }

    private boolean isClosed() {
        synchronized (lock) { return closed; }
    }

    private void removeJob(Job job) {
        synchronized (lock) { jobs.remove(job); }
    }

    private static Bitmap cropAndMask(Bitmap page, NoteCanvasView.AiSelectionRenderRequest request,
                                      float pageWidth, float pageHeight) throws IOException {
        return cropAndMask(page, request.cropBounds, request.maskPoints,
                request.lassoMaskApplied, request.maxDimensionPx, pageWidth, pageHeight);
    }

    static Bitmap cropAndMask(Bitmap page, RectF cropBounds, List<android.graphics.PointF> maskPoints,
                              boolean useMask, int maxDimensionPx,
                              float pageWidth, float pageHeight) throws IOException {
        if (page == null || page.isRecycled() || cropBounds == null ||
                !Float.isFinite(pageWidth) || !Float.isFinite(pageHeight) ||
                pageWidth <= 0f || pageHeight <= 0f ||
                !Float.isFinite(cropBounds.left) || !Float.isFinite(cropBounds.top) ||
                !Float.isFinite(cropBounds.right) || !Float.isFinite(cropBounds.bottom) ||
                cropBounds.isEmpty()) {
            throw new IOException("选区裁剪范围无效");
        }
        if (useMask && (maskPoints == null || maskPoints.size() < 3)) {
            throw new IOException("选区遮罩无效");
        }
        if (useMask) {
            for (android.graphics.PointF point : maskPoints) {
                if (point == null || !Float.isFinite(point.x) || !Float.isFinite(point.y)) {
                    throw new IOException("选区遮罩坐标无效");
                }
            }
        }
        RectF crop = new RectF(cropBounds);
        if (!crop.intersect(0f, 0f, pageWidth, pageHeight)) {
            throw new IOException("选区裁剪范围超出纸面");
        }
        int[] dimensions = cropRasterSize(crop.width(), crop.height(),
                Math.min(MAX_OUTPUT_EDGE, maxDimensionPx));
        if (dimensions == null) throw new IOException("选区裁剪范围无效");
        Bitmap output;
        try {
            output = Bitmap.createBitmap(dimensions[0], dimensions[1], Bitmap.Config.ARGB_8888);
        } catch (RuntimeException | OutOfMemoryError error) {
            throw new IOException("选区图像创建失败", error);
        }
        try {
            Canvas canvas = new Canvas(output);
            canvas.drawColor(PAPER_WHITE);
            canvas.save();
            try {
                float scale = dimensions[0] / crop.width();
                canvas.scale(scale, scale);
                canvas.translate(-crop.left, -crop.top);
                if (useMask) {
                    Path mask = new Path();
                    mask.moveTo(maskPoints.get(0).x, maskPoints.get(0).y);
                    for (int index = 1; index < maskPoints.size(); index++) {
                        mask.lineTo(maskPoints.get(index).x, maskPoints.get(index).y);
                    }
                    mask.close();
                    canvas.clipPath(mask);
                }
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                canvas.drawBitmap(page, null, new RectF(0f, 0f, pageWidth, pageHeight), paint);
            } finally {
                canvas.restore();
            }
            return output;
        } catch (RuntimeException | OutOfMemoryError error) {
            recycle(output);
            throw new IOException("选区裁切与遮罩失败", error);
        }
    }

    private void postUi(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mainHandler.post(action);
    }

    private void runOnUi(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mainHandler.post(action);
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
}
