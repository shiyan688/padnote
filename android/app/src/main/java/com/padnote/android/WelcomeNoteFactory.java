package com.padnote.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/** Builds a validated, local-only welcome note document without performing I/O. */
final class WelcomeNoteFactory {
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_MARKDOWN_LENGTH = 4_000;

    private WelcomeNoteFactory() { }

    static JSONObject create(String title, String markdown, long now) throws Exception {
        if (title == null || title.trim().isEmpty() || title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("示例笔记标题为空或过长");
        }
        if (markdown == null || markdown.trim().isEmpty() || markdown.length() > MAX_MARKDOWN_LENGTH) {
            throw new IllegalArgumentException("示例笔记内容为空或过长");
        }

        JSONObject flow = new TextFlow(
                UUID.randomUUID().toString(), NoteTextBox.Format.MARKDOWN, markdown,
                16.0, 1.35, 688.0, 0, 16.0, 40.0).toJson();
        JSONObject document = new JSONObject()
                .put("schemaVersion", 8)
                .put("id", "welcome-template")
                .put("title", title.trim())
                .put("updatedAt", now)
                .put("canvasWidth", 720)
                .put("canvasHeight", 960)
                .put("pageWidth", 720)
                .put("pageHeight", 960)
                .put("pageGap", 24)
                .put("pageCount", 1)
                .put("pdfPageCount", 0)
                .put("strokes", new JSONArray())
                .put("images", new JSONArray())
                .put("textBoxes", new JSONArray())
                .put("textFlows", new JSONArray().put(flow));
        NoteStore.validateDocument(document);
        return document;
    }
}
