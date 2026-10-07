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
    /** Canonical persisted values; float members below are Android layout projections. */
    double fontSizeSp64;
    double lineHeight64;
    double width64;
    int anchorPageIndex;
    double anchorXInPage64;
    double anchorYInPage64;
    float fontSizeSp;
    float lineHeight;
    float width;
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
    /** False when the page cap stopped layout before every source block was placed. */
    transient boolean lastLayoutComplete = true;
    transient int lastLayoutPageCount;
    transient boolean lastRenderHeightUnresolved;
    /** Incremented whenever the committed fragment set changes; rejects stale WebView callbacks. */
    transient int renderLayoutEpoch;
    /** Current-epoch fragment ids whose measured height still exceeds their reservation. */
    private final transient Map<String, Boolean> unresolvedRenderFragments = new LinkedHashMap<>();
    private transient int renderLayoutFragmentCount = 1;
    /** Once clipping is observed, require every fragment in the replacement layout to pass. */
    private transient boolean renderHeightValidationRequired;
    /** Width-fitted Mermaid block heights, excluding the fragment content padding. */
    private final transient Map<String, Float> measuredMermaidHeights = new LinkedHashMap<>();

    TextFlow(String id, NoteTextBox.Format format, String source, double fontSizeSp,
             double lineHeight, double width, int anchorPageIndex, double anchorXInPage,
             double anchorYInPage) {
        this.id = id;
        this.format = format == null ? NoteTextBox.Format.LATEX : format;
        this.source = source == null ? "" : source;
        this.fontSizeSp64 = clampFontSize64(fontSizeSp);
        this.lineHeight64 = clampLineHeight64(lineHeight);
        this.width64 = Math.max(80d, finite(width, "width"));
        this.anchorPageIndex = Math.max(0, anchorPageIndex);
        this.anchorXInPage64 = finite(anchorXInPage, "anchorXInPage");
        this.anchorYInPage64 = finite(anchorYInPage, "anchorYInPage");
        projectPersistentValues();
    }

    static float clampFontSize(float value) {
        return Math.max(10f, Math.min(32f, value));
    }

    static double clampFontSize64(double value) {
        return Math.max(10d, Math.min(32d, finite(value, "fontSizeSp")));
    }

    static float clampLineHeight(float value) {
        if (Float.isNaN(value) || value <= 0f) return DEFAULT_LINE_HEIGHT;
        return Math.max(MIN_LINE_HEIGHT, Math.min(MAX_LINE_HEIGHT, value));
    }

    static double clampLineHeight64(double value) {
        finite(value, "lineHeight");
        if (value <= 0d) return DEFAULT_LINE_HEIGHT;
        return Math.max(1.1d, Math.min(2.0d, value));
    }

    private static double finite(double value, String name) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }

    private void projectPersistentValues() {
        fontSizeSp = PersistedGeometry.renderFloat(fontSizeSp64);
        lineHeight = PersistedGeometry.renderFloat(lineHeight64);
        width = PersistedGeometry.renderFloat(width64);
        anchorXInPage = PersistedGeometry.renderFloat(anchorXInPage64);
        anchorYInPage = PersistedGeometry.renderFloat(anchorYInPage64);
    }

    void setStyle(double fontSize, double leading, double newWidth) {
        fontSizeSp64 = clampFontSize64(fontSize);
        lineHeight64 = clampLineHeight64(leading);
        width64 = Math.max(80d, finite(newWidth, "width"));
        projectPersistentValues();
    }

    void setWidth(double newWidth) {
        width64 = Math.max(80d, finite(newWidth, "width"));
        projectPersistentValues();
    }

    void setAnchor(double x, double y) {
        anchorXInPage64 = finite(x, "anchorXInPage");
        anchorYInPage64 = finite(y, "anchorYInPage");
        projectPersistentValues();
    }

    TextFlow copy() {
        TextFlow duplicate = new TextFlow(id, format, source, fontSizeSp64, lineHeight64,
                width64, anchorPageIndex, anchorXInPage64, anchorYInPage64);
        duplicate.heightCorrection = heightCorrection;
        duplicate.heightCorrectionPasses = heightCorrectionPasses;
        duplicate.lastLayoutComplete = lastLayoutComplete;
        duplicate.lastLayoutPageCount = lastLayoutPageCount;
        duplicate.lastRenderHeightUnresolved = lastRenderHeightUnresolved;
        duplicate.renderLayoutEpoch = renderLayoutEpoch;
        duplicate.unresolvedRenderFragments.putAll(unresolvedRenderFragments);
        duplicate.renderLayoutFragmentCount = renderLayoutFragmentCount;
        duplicate.renderHeightValidationRequired = renderHeightValidationRequired;
        duplicate.measuredMermaidHeights.putAll(measuredMermaidHeights);
        return duplicate;
    }

    void beginRenderLayout() {
        renderLayoutEpoch += 1;
        unresolvedRenderFragments.clear();
        renderHeightValidationRequired |= lastRenderHeightUnresolved;
        lastRenderHeightUnresolved = renderHeightValidationRequired;
    }

    /** Clears a failure learned from prior content/style after a real visual edit. */
    void clearRenderHeightFailure() {
        unresolvedRenderFragments.clear();
        renderHeightValidationRequired = false;
        lastRenderHeightUnresolved = false;
    }

    void setRenderLayoutFragmentCount(int count) {
        renderLayoutFragmentCount = Math.max(1, count);
    }

    void recordRenderFragmentHeight(String fragmentId, boolean unresolved) {
        unresolvedRenderFragments.put(fragmentId, unresolved);
        if (unresolved) renderHeightValidationRequired = true;
        boolean anyUnresolved = unresolvedRenderFragments.containsValue(Boolean.TRUE);
        boolean allResolved = unresolvedRenderFragments.size() >= renderLayoutFragmentCount;
        if (renderHeightValidationRequired && allResolved && !anyUnresolved) {
            renderHeightValidationRequired = false;
        }
        lastRenderHeightUnresolved = anyUnresolved || renderHeightValidationRequired;
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
        json.put("fontSizeSp", fontSizeSp64);
        json.put("lineHeight", lineHeight64);
        json.put("width", width64);
        json.put("anchorPageIndex", anchorPageIndex);
        json.put("anchorXInPage", anchorXInPage64);
        json.put("anchorYInPage", anchorYInPage64);
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
                clampFontSize64(finite(json.optDouble("fontSizeSp", 16), "fontSizeSp")),
                clampLineHeight64(finite(json.optDouble("lineHeight", DEFAULT_LINE_HEIGHT), "lineHeight")),
                Math.max(80d, finite(json.optDouble("width", 360), "width")),
                Math.max(0, json.optInt("anchorPageIndex", 0)),
                finite(json.optDouble("anchorXInPage", 0), "anchorXInPage"),
                finite(json.optDouble("anchorYInPage", 0), "anchorYInPage")
        );
    }

}
