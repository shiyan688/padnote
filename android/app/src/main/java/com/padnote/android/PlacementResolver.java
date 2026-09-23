package com.padnote.android;

import android.graphics.RectF;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Turns a model's placement <em>constraint</em> into real page geometry.
 *
 * <p>The model never sends coordinates. It cannot see rendered output or measure
 * heights, so any number it invents is wrong in a way it cannot detect; and a
 * literal offset would not survive a different page width anyway. Instead it
 * sends one of:
 *
 * <ul>
 *   <li>{@code relativeTo} — "below the selection", "right of ink-p2-c1". The
 *       direct translation of how a person says where to write.</li>
 *   <li>{@code page} + {@code bands} — a coarse slot from {@link PageMap}.</li>
 *   <li>{@code page} + {@code slot} — let the engine pick, e.g. the largest free
 *       region on that page.</li>
 * </ul>
 *
 * <p>All three are constraints the engine resolves and can reject with reasons,
 * which is what makes them safe to expose.
 */
final class PlacementResolver {

    /** A resolved position, in the page-relative terms {@link TextFlow} uses. */
    static final class Placement {
        final int pageIndex;
        final float xInPage;
        final float yInPage;
        final float width;
        /** How the request was interpreted, echoed back to the model. */
        final String interpretation;

        Placement(int pageIndex, float xInPage, float yInPage, float width,
                  String interpretation) {
            this.pageIndex = pageIndex;
            this.xInPage = xInPage;
            this.yInPage = yInPage;
            this.width = width;
            this.interpretation = interpretation;
        }
    }

    /** Why a constraint could not be honoured, with enough detail to retry. */
    static final class Failure extends RuntimeException {
        Failure(String message) {
            super(message);
        }
    }

    private final float pageWidth;
    private final float pageHeight;
    private final float pageGap;
    private final float density;
    private final int pageCount;

    PlacementResolver(float pageWidth, float pageHeight, float pageGap,
                      float density, int pageCount) {
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.pageGap = pageGap;
        this.density = density;
        this.pageCount = pageCount;
    }

    /**
     * @param anchors resolver for named anchors the model may reference
     */
    Placement resolve(JSONObject placement, AnchorLookup anchors) throws JSONException {
        if (placement == null) {
            throw new Failure("缺少 placement");
        }
        float margin = 16f * density;
        // Unspecified width means "use the available paper", not a legacy
        // 380dp text column. Explicit widthDp remains a stable authoring choice.
        float defaultWidth = pageWidth - margin * 2f;
        float width = placement.has("widthDp")
                ? clamp((float) placement.optDouble("widthDp") * density,
                        180f * density, pageWidth - margin * 2f)
                : defaultWidth;

        String relativeTo = placement.optString("relativeTo", "").trim();
        if (!relativeTo.isEmpty()) {
            return resolveRelative(relativeTo,
                    placement.optString("position", "below").trim(),
                    width, margin, anchors, placement.has("widthDp"));
        }
        if (placement.has("page")) {
            int pageIndex = clampPage(placement.optInt("page", 1) - 1);
            String slot = placement.optString("slot", "").trim();
            if (!slot.isEmpty()) {
                return resolveSlot(pageIndex, slot, width, margin, anchors);
            }
            String bands = placement.optString("bands", "").trim();
            if (!bands.isEmpty()) {
                return resolveBands(pageIndex, bands, width, margin);
            }
            return new Placement(pageIndex, margin, margin, width,
                    "第 " + (pageIndex + 1) + " 页顶部");
        }
        throw new Failure("placement 需要 relativeTo 或 page");
    }

    /**
     * @param explicitWidth whether the caller pinned a width; when it did not, a
     *                      side placement that cannot fit falls back to below the
     *                      anchor rather than failing outright
     */
    private Placement resolveRelative(String anchorName, String position, float width,
                                      float margin, AnchorLookup anchors,
                                      boolean explicitWidth) {
        RectF anchor = anchors == null ? null : anchors.boundsOf(anchorName);
        if (anchor == null) {
            throw new Failure("找不到锚点：" + anchorName);
        }
        float minimumWidth = 120f * density;
        // A lasso near the page edge leaves no usable column beside it, which used
        // to compute a negative width and reject the whole write. Writing below the
        // selection is what the user wanted anyway, so prefer that over refusing.
        if (("right".equals(position) || "left".equals(position)) && !explicitWidth) {
            float available = "right".equals(position)
                    ? pageWidth - (anchor.right + 18f * density) - margin
                    : anchor.left - 18f * density - margin;
            if (available < minimumWidth) {
                position = "below";
            }
        }
        int pageIndex = pageIndexOf(anchor.centerY());
        float top = pageIndex * (pageHeight + pageGap);
        float gap = 18f * density;
        float xInPage;
        float yInPage;
        switch (position.isEmpty() ? "below" : position) {
            case "above":
                xInPage = anchor.left;
                yInPage = anchor.top - top - gap - 72f * density;
                break;
            case "right":
                xInPage = anchor.right + gap;
                yInPage = anchor.top - top;
                width = Math.min(width, pageWidth - xInPage - margin);
                break;
            case "left":
                xInPage = Math.max(margin, anchor.left - gap - width);
                yInPage = anchor.top - top;
                break;
            default:
                xInPage = anchor.left;
                yInPage = anchor.bottom - top + gap;
                break;
        }
        if (!explicitWidth && !("right".equals(position) || "left".equals(position))) {
            // Keep the relation to the source while using the rest of the line.
            // Starting at the anchor is useful for short annotations; the width
            // now follows the real page edge instead of an arbitrary constant.
            xInPage = clamp(xInPage, margin, pageWidth - margin - minimumWidth);
            width = pageWidth - margin - xInPage;
        }
        if (width < minimumWidth) {
            throw new Failure("锚点 " + anchorName + " 的 " + position +
                    " 侧空间不足（可用宽度约 " + Math.round(width / density) +
                    "dp），建议改用 position=below，或指定 page 与 bands");
        }
        return new Placement(pageIndex,
                clamp(xInPage, margin, Math.max(margin, pageWidth - width - margin)),
                clamp(yInPage, margin, Math.max(margin, pageHeight - margin)),
                width,
                anchorName + " 的" + positionLabel(position) + "（第 " +
                        (pageIndex + 1) + " 页）");
    }

    private Placement resolveBands(int pageIndex, String bands, float width,
                                   float margin) {
        int firstBand = parseFirstBand(bands);
        float bandHeight = pageHeight / PageMap.BAND_COUNT;
        float yInPage = clamp(firstBand * bandHeight, margin,
                Math.max(margin, pageHeight - margin));
        return new Placement(pageIndex, margin, yInPage, width,
                "第 " + (pageIndex + 1) + " 页 band " + bands);
    }

    private Placement resolveSlot(int pageIndex, String slot, float width, float margin,
                                  AnchorLookup anchors) {
        RectF region = anchors == null ? null : anchors.freeRegionOf(pageIndex, slot);
        if (region == null) {
            throw new Failure("第 " + (pageIndex + 1) + " 页没有匹配 " + slot + " 的空白区域");
        }
        float top = pageIndex * (pageHeight + pageGap);
        float regionLeft = clamp(region.left, margin, pageWidth - margin);
        float regionWidth = Math.min(region.right - regionLeft,
                pageWidth - regionLeft - margin);
        if (regionWidth < 120f * density) {
            throw new Failure("第 " + (pageIndex + 1) + " 页的 " + slot +
                    " 空白区域太窄");
        }
        return new Placement(pageIndex, regionLeft,
                clamp(region.top - top, margin, Math.max(margin, pageHeight - margin)),
                Math.min(width, regionWidth), "第 " + (pageIndex + 1) + " 页 " + slot);
    }

    private static int parseFirstBand(String bands) {
        String head = bands.contains("-") ? bands.substring(0, bands.indexOf('-')) : bands;
        try {
            return Math.max(0, Math.min(PageMap.BAND_COUNT - 1,
                    Integer.parseInt(head.trim()) - 1));
        } catch (NumberFormatException failure) {
            throw new Failure("bands 格式应为 \"3\" 或 \"3-5\"，收到：" + bands);
        }
    }

    private static String positionLabel(String position) {
        switch (position) {
            case "above": return "上方";
            case "right": return "右侧";
            case "left": return "左侧";
            default: return "下方";
        }
    }

    private int pageIndexOf(float worldY) {
        float stride = Math.max(1f, pageHeight + pageGap);
        return clampPage((int) (worldY / stride));
    }

    private int clampPage(int value) {
        return Math.max(0, Math.min(Math.max(0, pageCount - 1 + 1), Math.min(499, value)));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /** Resolves the names a model may reference in a placement. */
    interface AnchorLookup {
        /** "selection", a flow id, or an ink cluster id from {@link PageMap}. */
        RectF boundsOf(String name);

        /** Named free region on a page, e.g. "free.largest". */
        RectF freeRegionOf(int pageIndex, String slot);
    }
}
