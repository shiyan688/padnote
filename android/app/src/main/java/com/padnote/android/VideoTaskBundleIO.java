package com.padnote.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds the portable input bundle consumed by the PadNote video Agent Skill. */
final class VideoTaskBundleIO {
    private VideoTaskBundleIO() {
    }

    static void write(OutputStream destination, String noteId, long noteRevision,
                      String title, String markdown, String audience,
                      String learningGoal, int durationSeconds,
                      String voiceProfile, float speed) throws Exception {
        byte[] content = markdown.getBytes(StandardCharsets.UTF_8);
        String contentHash = hex(MessageDigest.getInstance("SHA-256").digest(content));
        String manifestLine = "input/content.md\0" + content.length + "\0"
                + contentHash + "\n";
        String bundleHash = hex(MessageDigest.getInstance("SHA-256")
                .digest(manifestLine.getBytes(StandardCharsets.UTF_8)));
        String taskId = "padnote-" + UUID.randomUUID().toString().replace("-", "");

        JSONObject manifestEntry = new JSONObject()
                .put("path", "input/content.md")
                .put("media_type", "text/markdown")
                .put("size_bytes", content.length)
                .put("sha256", contentHash);
        JSONObject manifest = new JSONObject()
                .put("schema_version", "1.0")
                .put("files", new JSONArray().put(manifestEntry));
        JSONObject request = new JSONObject()
                .put("schema_version", "1.0")
                .put("task_type", "video.explain.v1")
                .put("task_id", taskId)
                .put("source", new JSONObject()
                        .put("note_id", noteId)
                        .put("note_revision", Math.max(1L, noteRevision))
                        .put("title", title)
                        .put("language", "zh-CN")
                        .put("entrypoint", "input/content.md")
                        .put("bundle_sha256", bundleHash))
                .put("brief", new JSONObject()
                        .put("audience", audience)
                        .put("learning_goal", learningGoal)
                        .put("prerequisites", new JSONArray())
                        .put("target_duration_sec", Math.max(1, durationSeconds))
                        .put("aspect_ratio", "9:16")
                        .put("style_preset", "clean-academic"))
                .put("research", new JSONObject().put("external_research", false))
                .put("review", new JSONObject().put("storyboard_required", true))
                .put("voice", new JSONObject()
                        .put("profile", voiceProfile)
                        .put("speed", Math.max(0.5f, Math.min(2f, speed))));

        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            put(zip, "request.json", request.toString(2).getBytes(StandardCharsets.UTF_8));
            put(zip, "input/manifest.json", manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            put(zip, "input/content.md", content);
            put(zip, "work/.keep", new byte[0]);
            put(zip, "output/.keep", new byte[0]);
        }
    }

    private static void put(ZipOutputStream zip, String path, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
