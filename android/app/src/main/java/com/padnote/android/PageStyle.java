package com.padnote.android;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The paper a note is written on: ruling, orientation and aspect ratio.
 *
 * <p>Chosen when a note is created and fixed thereafter. Allowing a later change
 * would be a trap: strokes are stored in world coordinates and do not reflow, so
 * altering the page ratio under existing ink would move every stroke relative to
 * its page. Text flows would re-paginate but handwriting would not, and the two
 * would drift apart.
 *
 * <p>Aspect ratio is explicit because page size used to be derived purely from the
 * view bounds. A4 has to hold its proportion regardless of the device, otherwise a
 * future PDF export would re-wrap everything.
 */
final class PageStyle {

    /** Ruling drawn on the paper. */
    enum Paper {
        BLANK,
        RULED,
        GRID,
        DOTTED;

        static Paper fromStorage(String value) {
            if ("blank".equalsIgnoreCase(value)) {
                return BLANK;
            }
            if ("grid".equalsIgnoreCase(value)) {
                return GRID;
            }
            if ("dotted".equalsIgnoreCase(value)) {
                return DOTTED;
            }
            return RULED;
        }

        String storageValue() {
            switch (this) {
                case BLANK: return "blank";
                case GRID: return "grid";
                case DOTTED: return "dotted";
                default: return "ruled";
            }
        }

        String displayName() {
            switch (this) {
                case BLANK: return "白纸";
                case GRID: return "方格";
                case DOTTED: return "点阵";
                default: return "横线";
            }
        }
    }

    /** Page proportion. */
    enum Ratio {
        /** Fills the available view, as every note did before this existed. */
        SCREEN,
        /** ISO 216 short:long = 1:√2, held regardless of device size. */
        A4;

        static Ratio fromStorage(String value) {
            return "a4".equalsIgnoreCase(value) ? A4 : SCREEN;
        }

        String storageValue() {
            return this == A4 ? "a4" : "screen";
        }

        String displayName() {
            return this == A4 ? "A4 比例" : "适应屏幕";
        }
    }

    static final float A4_LONG_OVER_SHORT = 1.4142f;

    final Paper paper;
    final Ratio ratio;
    final boolean landscape;

    PageStyle(Paper paper, Ratio ratio, boolean landscape) {
        this.paper = paper == null ? Paper.RULED : paper;
        this.ratio = ratio == null ? Ratio.SCREEN : ratio;
        this.landscape = landscape;
    }

    /**
     * What notes created before page styles existed used: screen-filling ruled
     * paper. Migrated notes adopt this so their appearance does not change.
     */
    static PageStyle legacyDefault() {
        return new PageStyle(Paper.RULED, Ratio.SCREEN, false);
    }

    String describe() {
        return paper.displayName() + " · " + ratio.displayName() +
                " · " + (landscape ? "横向" : "竖向");
    }

    /**
     * Page size for the available viewport, in the same units as {@code available*}.
     *
     * <p>For {@link Ratio#A4} the proportion wins over filling the screen, so the
     * page may leave margins on the long side. That is the point: a page that
     * silently changed shape per device could not be exported faithfully.
     *
     * @return {@code {width, height}}
     */
    float[] resolveSize(float availableWidth, float availableHeight, float minimum) {
        float width = Math.max(minimum, availableWidth);
        float height = Math.max(minimum, availableHeight);
        if (ratio == Ratio.SCREEN) {
            return new float[]{width, height};
        }
        float longOverShort = A4_LONG_OVER_SHORT;
        if (landscape) {
            // Fit width first, then check the resulting height still fits.
            float candidateHeight = width / longOverShort;
            if (candidateHeight > height) {
                candidateHeight = height;
                width = candidateHeight * longOverShort;
            }
            return new float[]{Math.max(minimum, width), Math.max(minimum, candidateHeight)};
        }
        float candidateWidth = height / longOverShort;
        if (candidateWidth > width) {
            candidateWidth = width;
            height = candidateWidth * longOverShort;
        }
        return new float[]{Math.max(minimum, candidateWidth), Math.max(minimum, height)};
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("paper", paper.storageValue());
        json.put("ratio", ratio.storageValue());
        json.put("landscape", landscape);
        return json;
    }

    static PageStyle fromJson(JSONObject json) {
        if (json == null) {
            return legacyDefault();
        }
        return new PageStyle(
                Paper.fromStorage(json.optString("paper", "ruled")),
                Ratio.fromStorage(json.optString("ratio", "screen")),
                json.optBoolean("landscape", false));
    }
}
