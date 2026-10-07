package com.padnote.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure Android-side analogue of the iPad ContentOutlineSnapshot. */
final class ContentOutlineSnapshot {
    /** Read-only, short-lived view over canvas ink. create() consumes it synchronously and does not retain it. */
    interface InkSource {
        int strokeCount();
        int pointCount(int strokeIndex);
        double x(int strokeIndex, int pointIndex);
        double y(int strokeIndex, int pointIndex);
    }
    static final class Flow {
        final String id, format, source;
        final int anchorPageIndex;
        Flow(String id, String format, String source, int anchorPageIndex) {
            this.id = id; this.format = format; this.source = source == null ? "" : source;
            this.anchorPageIndex = anchorPageIndex;
        }
    }
    static final class Image {
        final String id;
        final int page;
        Image(String id, int page) { this.id = id; this.page = page; }
    }
    static final class Page {
        final int index;
        final List<Flow> textFlows;
        final List<String> imageIds;
        final boolean hasPdfBackground, hasHandwriting;
        Page(int index, List<Flow> flows, List<String> images, boolean pdf, boolean ink) {
            this.index = index; this.textFlows = immutable(flows); this.imageIds = immutable(images);
            this.hasPdfBackground = pdf; this.hasHandwriting = ink;
        }
    }

    final String title;
    final List<Page> pages;

    private ContentOutlineSnapshot(String title, List<Page> pages) {
        this.title = title == null ? "" : title;
        this.pages = immutable(pages);
    }

    static ContentOutlineSnapshot create(String title, int pageCount, int pdfPageCount,
            double pageWidth, double pageHeight, double pageGap, List<Flow> flows,
            List<Image> images, InkSource inkSource) {
        int count = Math.max(0, Math.min(500, pageCount));
        List<List<Flow>> flowsByPage = buckets(count);
        if (flows != null) for (Flow flow : flows) {
            if (flow != null && flow.anchorPageIndex >= 0 && flow.anchorPageIndex < count)
                flowsByPage.get(flow.anchorPageIndex).add(flow);
        }
        List<List<String>> imagesByPage = buckets(count);
        if (images != null) for (Image image : images) {
            if (image != null && image.id != null && image.page >= 0 && image.page < count)
                imagesByPage.get(image.page).add(image.id);
        }
        // Calculate flags now while the caller is on the canvas/UI thread. The immutable
        // returned snapshot retains only one boolean per page, never point/stroke copies.
        boolean[] ink = handwritingPages(inkSource, count, pageWidth, pageHeight, pageGap);
        List<Page> pages = new ArrayList<>(count);
        for (int page = 0; page < count; page++) {
            pages.add(new Page(page, flowsByPage.get(page), imagesByPage.get(page),
                    page < Math.max(0, pdfPageCount), ink[page]));
        }
        return new ContentOutlineSnapshot(title, pages);
    }

    static boolean[] handwritingPages(InkSource strokes, int pageCount,
            double pageWidth, double pageHeight, double pageGap) {
        int count = Math.max(0, Math.min(500, pageCount));
        boolean[] result = new boolean[count];
        if (count == 0 || !finitePositive(pageWidth) || !finitePositive(pageHeight) ||
                !Double.isFinite(pageGap) || pageGap < 0) return result;
        double stride = pageHeight + pageGap;
        double maxY = (count - 1d) * stride + pageHeight;
        if (!finitePositive(stride) || !Double.isFinite(maxY)) return result;
        if (strokes == null) return result;
        int markedPages = 0;
        for (int s = 0; s < strokes.strokeCount(); s++) {
            int points = Math.max(0, strokes.pointCount(s));
            if (points == 1) {
                double x = strokes.x(s, 0), y = strokes.y(s, 0);
                if (finite(x) && finite(y) && x >= 0 && x <= pageWidth && y >= 0 && y <= maxY) {
                    int page = clampedPageIndex(Math.floor(y / stride), count);
                    if (y <= page * stride + pageHeight && !result[page]) {
                        result[page] = true;
                        markedPages++;
                    }
                }
                if (markedPages == count) return result;
                continue;
            }
            for (int i = 1; i < points; i++) {
                double ax = strokes.x(s, i - 1), ay = strokes.y(s, i - 1);
                double bx = strokes.x(s, i), by = strokes.y(s, i);
                if (!finite(ax) || !finite(ay) || !finite(bx) || !finite(by)) continue;
                if (Math.max(ax, bx) < 0 || Math.min(ax, bx) > pageWidth ||
                        Math.max(ay, by) < 0 || Math.min(ay, by) > maxY) continue;
                // Clamp the floating-point page interval before conversion. Converting
                // a huge finite page number then adding +/-1 can overflow an int.
                int first = clampedPageIndex(Math.floor(Math.min(ay, by) / stride) - 1d, count);
                int last = clampedPageIndex(Math.floor(Math.max(ay, by) / stride) + 1d, count);
                for (int page = first; page <= last; page++) {
                    double top = page * stride;
                    if (segmentIntersects(ax, ay, bx, by, 0, top, pageWidth, top + pageHeight) && !result[page]) {
                        result[page] = true;
                        markedPages++;
                    }
                    if (markedPages == count) return result;
                }
            }
        }
        return result;
    }

    String markdown() {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(title).append("\n\n> 文字对象按起始页归组；长对象内容可能延伸到后续页面。\n");
        for (Page page : pages) {
            out.append("\n## 第 ").append(page.index + 1).append(" 页 · 文字对象起始页");
            if (page.hasPdfBackground) out.append("\n\n> PDF 原文背景；没有提取文字。");
            for (Flow flow : page.textFlows) {
                out.append("\n\n");
                if ("latex".equalsIgnoreCase(flow.format)) {
                    String fence = codeFence(flow.source);
                    out.append("### 公式（LaTeX 源码）\n").append(fence).append("latex\n")
                            .append(flow.source).append('\n').append(fence);
                } else {
                    out.append("### 正文（Markdown）\n").append(flow.source);
                }
            }
            for (String ignored : page.imageIds)
                out.append("\n- 图片（未添加说明；图片文件未包含在此 Markdown 中）");
            if (page.hasHandwriting) out.append("\n> 手写笔迹尚未识别。");
            if (page.textFlows.isEmpty() && page.imageIds.isEmpty() &&
                    !page.hasHandwriting && !page.hasPdfBackground)
                out.append("\n> 此页没有可导出的文字对象。");
            out.append('\n');
        }
        return out.toString();
    }

    static String codeFence(String source) {
        int longest = 0, run = 0;
        if (source != null) for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '`') { run++; longest = Math.max(longest, run); }
            else run = 0;
        }
        StringBuilder fence = new StringBuilder();
        for (int i = 0; i < Math.max(3, longest + 1); i++) fence.append('`');
        return fence.toString();
    }

    private static boolean segmentIntersects(double ax, double ay, double bx, double by, double left, double top,
            double right, double bottom) {
        double dx = bx - ax, dy = by - ay;
        if (!finite(dx) || !finite(dy)) return false;
        double t0 = 0, t1 = 1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {ax - left, right - ax, ay - top, bottom - ay};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) { if (q[i] < 0) return false; continue; }
            double ratio = q[i] / p[i];
            if (!finite(ratio)) return false;
            if (p[i] < 0) t0 = Math.max(t0, ratio); else t1 = Math.min(t1, ratio);
            if (t0 > t1) return false;
        }
        return true;
    }

    private static boolean finitePositive(double value) { return finite(value) && value > 0; }
    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static int clampedPageIndex(double page, int count) {
        if (Double.isNaN(page) || page <= 0) return 0;
        if (page >= count - 1d) return count - 1;
        return (int) page;
    }
    private static <T> List<List<T>> buckets(int count) {
        List<List<T>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) rows.add(new ArrayList<>());
        return rows;
    }
    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source == null ? Collections.emptyList() : source));
    }
}
