package com.padnote.android;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for one run of page text.
 *
 * <p>A flow owns the authoring state — source, format, font size, line width —
 * and an anchor saying where it starts. Everything visible on a page is derived
 * from this by {@code NoteCanvasView.layoutTextFlow}; fragments never own
 * content of their own. That separation exists so a programmatic caller (and,
 * later, a model tool call) has exactly one place to write.
 *
 * <p>The anchor is deliberately <em>page relative</em> rather than a world
 * coordinate. "Move to the next page" then reduces to incrementing
 * {@link #anchorPageIndex}, which is the same operation whether it comes from a
 * drag gesture or a tool call, and it cannot silently mean "somewhere between
 * two pages" the way a raw world Y can.
 */
final class TextFlow {
    /** Longest source accepted from storage or import, in characters. */
    static final int MAX_SOURCE_LENGTH = 100_000;

    /**
     * Default line height. Kept fairly tight because handwritten notes sit next
     * to this text and loose leading made typed blocks look detached from the
     * surrounding ink.
     *
     * <p>Whatever value a flow carries is used by both the renderer's CSS and the
     * paginator's height estimate. Changing one without the other reintroduces
     * the page-bottom whitespace that the estimator calibration removed.
     */
    static final float DEFAULT_LINE_HEIGHT = 1.35f;
    static final float MIN_LINE_HEIGHT = 1.1f;
    static final float MAX_LINE_HEIGHT = 2.0f;

    final String id;
    NoteTextBox.Format format;
    String source;
    float fontSizeSp;
    float lineHeight;
    float width;
    int anchorPageIndex;
    float anchorXInPage;
    float anchorYInPage;
    /**
     * Multiplier applied to estimated block heights for this flow, learned from
     * what the renderer actually produced. Starts at 1 and only grows; see
     * {@code NoteCanvasView.applyMeasuredFragmentHeight}. Not persisted, because it
     * describes this device's font metrics rather than the document.
     */
    transient float heightCorrection = 1f;
    /** Corrections applied so far, bounding the measure-reflow loop. */
    transient int heightCorrectionPasses;
    /** Width-fitted Mermaid block heights, excluding the fragment content padding. */
    private final transient Map<String, Float> measuredMermaidHeights = new LinkedHashMap<>();

    TextFlow(String id, NoteTextBox.Format format, String source, float fontSizeSp,
             float lineHeight, float width, int anchorPageIndex, float anchorXInPage,
             float anchorYInPage) {
        this.id = id;
        this.format = format == null ? NoteTextBox.Format.LATEX : format;
        this.source = source == null ? "" : source;
        this.fontSizeSp = clampFontSize(fontSizeSp);
        this.lineHeight = clampLineHeight(lineHeight);
        this.width = Math.max(80f, width);
        this.anchorPageIndex = Math.max(0, anchorPageIndex);
        this.anchorXInPage = anchorXInPage;
        this.anchorYInPage = anchorYInPage;
    }

    static float clampFontSize(float value) {
        return Math.max(10f, Math.min(32f, value));
    }

    static float clampLineHeight(float value) {
        if (Float.isNaN(value) || value <= 0f) {
            return DEFAULT_LINE_HEIGHT;
        }
        return Math.max(MIN_LINE_HEIGHT, Math.min(MAX_LINE_HEIGHT, value));
    }

    TextFlow copy() {
        TextFlow duplicate = new TextFlow(id, format, source, fontSizeSp, lineHeight,
                width, anchorPageIndex, anchorXInPage, anchorYInPage);
        duplicate.heightCorrection = heightCorrection;
        duplicate.heightCorrectionPasses = heightCorrectionPasses;
        duplicate.measuredMermaidHeights.putAll(measuredMermaidHeights);
        return duplicate;
    }

    float measuredMermaidHeight(String blockSource) {
        Float measured = measuredMermaidHeights.get(mermaidKey(blockSource));
        return measured == null ? 0f : measured;
    }

    void recordMeasuredMermaidHeight(String blockSource, float height) {
        if (height > 0f && !Float.isNaN(height) && !Float.isInfinite(height)) {
            measuredMermaidHeights.put(mermaidKey(blockSource), height);
        }
    }

    void clearMeasuredMermaidHeights() {
        measuredMermaidHeights.clear();
    }

    private static String mermaidKey(String blockSource) {
        return blockSource == null ? "" : blockSource.trim();
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("format", format.storageValue());
        json.put("source", source);
        json.put("fontSizeSp", fontSizeSp);
        json.put("lineHeight", lineHeight);
        json.put("width", width);
        json.put("anchorPageIndex", anchorPageIndex);
        json.put("anchorXInPage", anchorXInPage);
        json.put("anchorYInPage", anchorYInPage);
        return json;
    }

    static TextFlow fromJson(JSONObject json) throws JSONException {
        String id = json.optString("id", "").trim();
        if (id.isEmpty() || id.length() > 120) {
            throw new JSONException("Invalid text flow id");
        }
        String source = json.optString("source", "");
        if (source.length() > MAX_SOURCE_LENGTH) {
            throw new JSONException("Text flow source is too large");
        }
        return new TextFlow(
                id,
                NoteTextBox.Format.fromStorage(json.optString("format", "latex")),
                source,
                clampFontSize(finiteFloat(json.optDouble("fontSizeSp", 16))),
                clampLineHeight(finiteFloat(
                        json.optDouble("lineHeight", DEFAULT_LINE_HEIGHT))),
                Math.max(80f, finiteFloat(json.optDouble("width", 360))),
                Math.max(0, json.optInt("anchorPageIndex", 0)),
                finiteFloat(json.optDouble("anchorXInPage", 0)),
                finiteFloat(json.optDouble("anchorYInPage", 0))
        );
    }

    private static float finiteFloat(double value) throws JSONException {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new JSONException("Text flow geometry must be finite");
        }
        return (float) value;
    }
}
