package com.padnote.android;

import android.graphics.RectF;

import org.json.JSONObject;

/**
 * The narrow surface tools are allowed to touch.
 *
 * <p>Deliberately not the canvas view itself: tools get reading, measuring and a
 * small set of writes, and nothing else. In particular there is no way to reach
 * stroke geometry, so no tool can move or alter the user's handwriting.
 */
interface NoteToolContext {

    /** Structured description of the document; see {@code PageMap}. */
    JSONObject readPageMap(int focusPageIndex, boolean fullDetail);

    /**
     * Resolves a placement constraint against live page geometry.
     *
     * <p>Lives here rather than in the tools because resolving needs the canvas's
     * density, page metrics and anchor names — a tool holding its own resolver
     * would be guessing at all three.
     *
     * @throws PlacementResolver.Failure when the constraint cannot be honoured
     */
    PlacementResolver.Placement resolvePlacement(JSONObject placement);

    /** Page the current selection sits on, or -1 when there is no selection. */
    int selectionPageIndex();

    /** World bounds of the current selection, or {@code null}. */
    RectF selectionBounds();

    int pageCount();

    /**
     * Creates a text flow from a resolved placement.
     *
     * @return a report of what actually happened: pages occupied, whether a page
     *         was added, and which existing flows shifted.
     */
    JSONObject createTextFlow(String content, NoteTextBox.Format format,
                              PlacementResolver.Placement placement);

    /** Restyles an existing flow; returns the resulting page occupancy. */
    JSONObject styleTextFlow(String flowId, Float fontSizeSp, Float lineHeight,
                             Float width);

    /** Moves an existing flow to a resolved placement. */
    JSONObject moveTextFlow(String flowId, PlacementResolver.Placement placement);

    /** Measures how tall content would be, without inserting it. */
    float measureContentHeight(String content, NoteTextBox.Format format,
                               float widthDp, float fontSizeSp, float lineHeight);

    /** Page geometry, needed to turn free space into content-unit capacity. */
    float pageWidthDp();

    float pageHeightDp();
}
