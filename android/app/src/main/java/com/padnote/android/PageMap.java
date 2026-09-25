package com.padnote.android;

import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A description of the note a language model can actually reason about.
 *
 * <p>Two design choices carry most of the value:
 *
 * <p><b>Free space is reported in content units, not pixels.</b> Telling a model
 * a region is 340x220dp forces it to convert to "how much can I write" using
 * line height, glyph widths and CJK advance rules it does not have. Reporting
 * "about 8 lines, roughly 120 Chinese characters" lets it plan in the units it
 * already thinks in. The conversion runs the paginator's own height estimate
 * backwards, so the numbers agree with what pagination will actually do.
 *
 * <p><b>Vertical position is quantised into bands.</b> Continuous coordinates
 * invite the model to invent precision it does not have, and a raw pixel offset
 * is meaningless on a differently sized device. {@value #BAND_COUNT} bands are
 * coarse enough to stay honest and fine enough to say "upper third".
 *
 * <p>Handwriting appears as clusters with bounding boxes and stroke counts but no
 * transcription: v1 has no recognition, so the model knows ink is <em>there</em>
 * without knowing what it says. That is enough to place an answer below what the
 * user circled, and not enough to reorganise a page — which is why v1 does not
 * offer that.
 */
final class PageMap {

    /** Vertical slices per page. Coarse on purpose; see the class comment. */
    static final int BAND_COUNT = 8;

    /** Ink separated by more than this is treated as a different cluster. */
    private static final float CLUSTER_GAP_DP = 34f;

    private PageMap() {
    }

    /**
     * Builds the map.
     *
     * @param focusPageIndex page the selection is on; it gets full detail
     * @param fullDetail     when false, even the focus page is summarised
     */
    static JSONObject build(List<InkStroke> strokes, List<NoteTextBox> fragments,
                            List<TextFlow> flows, int pageCount, float pageWidth,
                            float pageHeight, float pageGap, float density,
                            int focusPageIndex, RectF selectionBounds,
                            boolean fullDetail) throws JSONException {
        JSONObject map = new JSONObject();
        map.put("pageCount", pageCount);
        map.put("bandsPerPage", BAND_COUNT);
        map.put("pageSize", new JSONObject()
                .put("widthDp", Math.round(pageWidth / density))
                .put("heightDp", Math.round(pageHeight / density)));
        map.put("note", "位置用 band 表示，每页 " + BAND_COUNT +
                " 条，band 1 在页面顶部。空白容量以行数和中文字符数估算，不要用像素坐标。");

        if (selectionBounds != null && focusPageIndex >= 0) {
            JSONObject selection = new JSONObject();
            selection.put("pageIndex", focusPageIndex);
            selection.put("bands", bandRangeOf(selectionBounds, focusPageIndex,
                    pageHeight, pageGap));
            map.put("selection", selection);
        }

        JSONArray pages = new JSONArray();
        for (int index = 0; index < pageCount; index++) {
            boolean detailed = fullDetail &&
                    Math.abs(index - focusPageIndex) <= 1;
            pages.put(describePage(index, detailed, strokes, fragments, flows,
                    pageWidth, pageHeight, pageGap, density));
        }
        map.put("pages", pages);
        return map;
    }

    private static JSONObject describePage(int pageIndex, boolean detailed,
                                           List<InkStroke> strokes,
                                           List<NoteTextBox> fragments,
                                           List<TextFlow> flows,
                                           float pageWidth, float pageHeight,
                                           float pageGap, float density)
            throws JSONException {
        JSONObject page = new JSONObject();
        page.put("pageIndex", pageIndex);

        List<NoteTextBox> pageFragments = new ArrayList<>();
        for (NoteTextBox fragment : fragments) {
            if (fragment.pageIndex == pageIndex) {
                pageFragments.add(fragment);
            }
        }
        List<InkStroke> pageStrokes = strokesOnPage(strokes, pageIndex,
                pageHeight, pageGap);

        boolean[] occupied = new boolean[BAND_COUNT];
        markOccupied(occupied, pageFragments, pageIndex, pageHeight, pageGap);
        for (InkStroke stroke : pageStrokes) {
            RectF bounds = strokeBounds(stroke);
            if (bounds != null) {
                markBands(occupied, bounds, pageIndex, pageHeight, pageGap);
            }
        }

        if (!detailed) {
            // Summary form: enough for the model to know whether a page is worth
            // asking about, without spending tokens on every page of a long note.
            page.put("summary", true);
            page.put("textFlowCount", countDistinctFlows(pageFragments));
            page.put("inkStrokeCount", pageStrokes.size());
            page.put("freeBandCount", countFree(occupied));
            return page;
        }

        page.put("summary", false);
        JSONArray textArray = new JSONArray();
        List<String> seenFlows = new ArrayList<>();
        for (NoteTextBox fragment : pageFragments) {
            if (seenFlows.contains(fragment.flowId)) {
                continue;
            }
            seenFlows.add(fragment.flowId);
            JSONObject entry = new JSONObject();
            entry.put("flowId", fragment.flowId);
            entry.put("bands", bandRangeOf(fragmentBounds(fragment), pageIndex,
                    pageHeight, pageGap));
            entry.put("fragmentIndex", fragment.flowIndex);
            entry.put("fragmentCount", fragment.flowCount);
            entry.put("format", fragment.format.storageValue());
            entry.put("fontSizeSp", Math.round(fragment.fontSizeSp));
            entry.put("lineHeight", round1(fragment.lineHeight));
            textArray.put(entry);
        }
        page.put("textFlows", textArray);

        JSONArray inkArray = new JSONArray();
        List<List<InkStroke>> clusters = clusterStrokes(pageStrokes, density);
        for (int index = 0; index < clusters.size(); index++) {
            List<InkStroke> cluster = clusters.get(index);
            RectF bounds = clusterBounds(cluster);
            if (bounds == null) {
                continue;
            }
            JSONObject entry = new JSONObject();
            entry.put("clusterId", "ink-p" + pageIndex + "-c" + (index + 1));
            entry.put("bands", bandRangeOf(bounds, pageIndex, pageHeight, pageGap));
            entry.put("strokeCount", cluster.size());
            entry.put("recognized", false);
            inkArray.put(entry);
        }
        page.put("inkClusters", inkArray);

        page.put("freeRegions", describeFreeRegions(occupied, pageWidth, pageHeight,
                density));
        return page;
    }

    /**
     * Turns runs of empty bands into writable regions, each annotated with how
     * much text it holds at the default style.
     */
    private static JSONArray describeFreeRegions(boolean[] occupied, float pageWidth,
                                                 float pageHeight, float density)
            throws JSONException {
        JSONArray regions = new JSONArray();
        float bandHeightDp = (pageHeight / density) / BAND_COUNT;
        float usableWidthDp = pageWidth / density - 32f;
        int start = -1;
        for (int index = 0; index <= BAND_COUNT; index++) {
            boolean free = index < BAND_COUNT && !occupied[index];
            if (free && start < 0) {
                start = index;
            } else if (!free && start >= 0) {
                regions.put(freeRegion(start, index - 1, bandHeightDp, usableWidthDp));
                start = -1;
            }
        }
        return regions;
    }

    private static JSONObject freeRegion(int firstBand, int lastBand,
                                         float bandHeightDp, float usableWidthDp)
            throws JSONException {
        float heightDp = (lastBand - firstBand + 1) * bandHeightDp;
        JSONObject region = new JSONObject();
        region.put("bands", (firstBand + 1) + "-" + (lastBand + 1));
        region.put("position", positionLabel(firstBand, lastBand));
        region.put("capacity", capacityAt(heightDp, usableWidthDp, 16f,
                TextFlow.DEFAULT_LINE_HEIGHT));
        return region;
    }

    /**
     * Capacity in content units.
     *
     * <p>Runs the height estimate backwards: lines fit by leading, and characters
     * per line follow CJK advance widths, so "how much can I write here" is
     * answered in the model's own terms.
     */
    private static JSONObject capacityAt(float heightDp, float usableWidthDp,
                                         float fontSizeSp, float lineHeight)
            throws JSONException {
        float lineDp = fontSizeSp * lineHeight;
        int lines = Math.max(0, (int) Math.floor((heightDp - 20f) / Math.max(1f, lineDp)));
        // CJK glyphs advance about one em; this is the conservative case, so the
        // model under-promises rather than overflowing the region.
        int cjkPerLine = Math.max(1, (int) Math.floor(usableWidthDp / Math.max(1f, fontSizeSp)));
        JSONObject capacity = new JSONObject();
        capacity.put("lines", lines);
        capacity.put("cjkChars", lines * cjkPerLine);
        capacity.put("atFontSizeSp", Math.round(fontSizeSp));
        capacity.put("atLineHeight", round1(lineHeight));
        return capacity;
    }

    private static String positionLabel(int firstBand, int lastBand) {
        float middle = (firstBand + lastBand) / 2f;
        if (middle < BAND_COUNT / 3f) {
            return "upper";
        }
        if (middle < BAND_COUNT * 2f / 3f) {
            return "middle";
        }
        return "lower";
    }

    /**
     * Groups strokes that sit near each other into clusters, so the model sees a
     * few meaningful blocks of ink instead of hundreds of individual strokes.
     */
    private static List<List<InkStroke>> clusterStrokes(List<InkStroke> strokes,
                                                        float density) {
        List<List<InkStroke>> clusters = new ArrayList<>();
        List<RectF> bounds = new ArrayList<>();
        float gap = CLUSTER_GAP_DP * density;
        for (InkStroke stroke : strokes) {
            RectF strokeBox = strokeBounds(stroke);
            if (strokeBox == null) {
                continue;
            }
            int target = -1;
            for (int index = 0; index < bounds.size(); index++) {
                if (nearby(bounds.get(index), strokeBox, gap)) {
                    target = index;
                    break;
                }
            }
            if (target < 0) {
                List<InkStroke> cluster = new ArrayList<>();
                cluster.add(stroke);
                clusters.add(cluster);
                bounds.add(new RectF(strokeBox));
            } else {
                clusters.get(target).add(stroke);
                bounds.get(target).union(strokeBox);
            }
        }
        return clusters;
    }

    private static boolean nearby(RectF first, RectF second, float gap) {
        return first.left - gap <= second.right && second.left - gap <= first.right &&
                first.top - gap <= second.bottom && second.top - gap <= first.bottom;
    }

    private static List<InkStroke> strokesOnPage(List<InkStroke> strokes, int pageIndex,
                                                 float pageHeight, float pageGap) {
        List<InkStroke> onPage = new ArrayList<>();
        for (InkStroke stroke : strokes) {
            RectF bounds = strokeBounds(stroke);
            if (bounds == null) {
                continue;
            }
            if (pageIndexOf(bounds.centerY(), pageHeight, pageGap) == pageIndex) {
                onPage.add(stroke);
            }
        }
        return onPage;
    }

    private static RectF strokeBounds(InkStroke stroke) {
        if (stroke == null || stroke.points.isEmpty()) {
            return null;
        }
        RectF bounds = null;
        for (InkPoint point : stroke.points) {
            if (bounds == null) {
                bounds = new RectF(point.x, point.y, point.x, point.y);
            } else {
                bounds.union(point.x, point.y);
            }
        }
        return bounds;
    }

    private static RectF clusterBounds(List<InkStroke> cluster) {
        RectF bounds = null;
        for (InkStroke stroke : cluster) {
            RectF strokeBox = strokeBounds(stroke);
            if (strokeBox == null) {
                continue;
            }
            if (bounds == null) {
                bounds = new RectF(strokeBox);
            } else {
                bounds.union(strokeBox);
            }
        }
        return bounds;
    }

    private static RectF fragmentBounds(NoteTextBox fragment) {
        return new RectF(fragment.x, fragment.y,
                fragment.x + fragment.width, fragment.y + fragment.height);
    }

    private static void markOccupied(boolean[] occupied, List<NoteTextBox> fragments,
                                     int pageIndex, float pageHeight, float pageGap) {
        for (NoteTextBox fragment : fragments) {
            markBands(occupied, fragmentBounds(fragment), pageIndex, pageHeight, pageGap);
        }
    }

    private static void markBands(boolean[] occupied, RectF bounds, int pageIndex,
                                  float pageHeight, float pageGap) {
        int[] range = bandIndexRange(bounds, pageIndex, pageHeight, pageGap);
        for (int index = range[0]; index <= range[1]; index++) {
            occupied[index] = true;
        }
    }

    private static String bandRangeOf(RectF bounds, int pageIndex, float pageHeight,
                                      float pageGap) {
        int[] range = bandIndexRange(bounds, pageIndex, pageHeight, pageGap);
        return range[0] == range[1]
                ? String.valueOf(range[0] + 1)
                : (range[0] + 1) + "-" + (range[1] + 1);
    }

    private static int[] bandIndexRange(RectF bounds, int pageIndex, float pageHeight,
                                        float pageGap) {
        float top = pageIndex * (pageHeight + pageGap);
        float bandHeight = Math.max(1f, pageHeight / BAND_COUNT);
        int first = (int) Math.floor((bounds.top - top) / bandHeight);
        int last = (int) Math.floor((bounds.bottom - top) / bandHeight);
        first = Math.max(0, Math.min(BAND_COUNT - 1, first));
        last = Math.max(first, Math.min(BAND_COUNT - 1, last));
        return new int[]{first, last};
    }

    private static int pageIndexOf(float worldY, float pageHeight, float pageGap) {
        float stride = Math.max(1f, pageHeight + pageGap);
        return Math.max(0, (int) (worldY / stride));
    }

    private static int countDistinctFlows(List<NoteTextBox> fragments) {
        List<String> seen = new ArrayList<>();
        for (NoteTextBox fragment : fragments) {
            if (!seen.contains(fragment.flowId)) {
                seen.add(fragment.flowId);
            }
        }
        return seen.size();
    }

    private static int countFree(boolean[] occupied) {
        int free = 0;
        for (boolean band : occupied) {
            if (!band) {
                free += 1;
            }
        }
        return free;
    }

    private static float round1(float value) {
        return Math.round(value * 10f) / 10f;
    }
}
