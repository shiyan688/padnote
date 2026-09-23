package com.padnote.android;

/**
 * One rendered piece of a {@link TextFlow} on one page.
 *
 * <p>Fragments are <em>derived</em>: {@code NoteCanvasView.layoutTextFlow}
 * recreates the whole set whenever a flow changes, and nothing outside the
 * layout pass may edit their geometry. They are not persisted — schemaVersion 5
 * stores only flows, and fragments are rebuilt on load. That is what keeps a
 * long answer from writing its full source once per page.
 *
 * <p>{@link #source} is a reference to the owning flow's string, not a copy, so
 * holding it on every fragment costs one pointer each. It is exposed because the
 * inline editor edits the whole flow from whichever fragment was tapped.
 */
final class NoteTextBox {
    enum Format {
        LATEX,
        MARKDOWN;

        static Format fromStorage(String value) {
            return "markdown".equalsIgnoreCase(value) ? MARKDOWN : LATEX;
        }

        String storageValue() {
            return this == MARKDOWN ? "markdown" : "latex";
        }

        String displayName() {
            return this == MARKDOWN ? "Markdown" : "LaTeX";
        }
    }

    final String id;
    final String flowId;
    int flowIndex;
    int flowCount;
    Format format;
    /** The owning flow's full source, shared by reference. */
    String source;
    /** Just the slice of {@link #source} rendered on this page. */
    String fragmentSource;
    float fontSizeSp;
    /** Leading, shared with the renderer's CSS and the paginator's estimate. */
    float lineHeight;
    /** Page this fragment sits on; geometry below is world coordinates. */
    int pageIndex;
    float x;
    float y;
    float width;
    float height;

    NoteTextBox(String id, String flowId, int flowIndex, int flowCount,
                Format format, String source, String fragmentSource, float fontSizeSp,
                float lineHeight, int pageIndex, float x, float y, float width,
                float height) {
        this.id = id;
        this.flowId = flowId == null || flowId.trim().isEmpty() ? id : flowId;
        this.flowIndex = Math.max(0, flowIndex);
        this.flowCount = Math.max(1, flowCount);
        this.format = format == null ? Format.LATEX : format;
        this.source = source == null ? "" : source;
        this.fragmentSource = fragmentSource == null ? this.source : fragmentSource;
        this.fontSizeSp = TextFlow.clampFontSize(fontSizeSp);
        this.lineHeight = TextFlow.clampLineHeight(lineHeight);
        this.pageIndex = Math.max(0, pageIndex);
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    NoteTextBox copy() {
        return new NoteTextBox(id, flowId, flowIndex, flowCount, format, source,
                fragmentSource, fontSizeSp, lineHeight, pageIndex, x, y, width, height);
    }

    String displaySource() {
        return fragmentSource == null ? source : fragmentSource;
    }

    boolean isFlowStart() {
        return flowIndex == 0;
    }
}
