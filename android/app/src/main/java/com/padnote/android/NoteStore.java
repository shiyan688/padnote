package com.padnote.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class NoteStore {
    static final class Entry {
        final String id;
        final String title;
        final long updatedAt;
        final int strokeCount;
        final int pageCount;

        Entry(String id, String title, long updatedAt, int strokeCount, int pageCount) {
            this.id = id;
            this.title = title;
            this.updatedAt = updatedAt;
            this.strokeCount = strokeCount;
            this.pageCount = pageCount;
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("title", title);
            json.put("updatedAt", updatedAt);
            json.put("strokeCount", strokeCount);
            json.put("pageCount", pageCount);
            return json;
        }

        static Entry fromJson(JSONObject json) throws Exception {
            return new Entry(
                    json.getString("id"),
                    json.optString("title", "未命名笔记"),
                    json.optLong("updatedAt", 0),
                    json.optInt("strokeCount", 0),
                    Math.max(1, json.optInt("pageCount", 1))
            );
        }
    }

    private static final String NOTES_DIRECTORY = "notes";

    /** Directory name for sibling files such as note covers (see CoverStore). */
    static String notesDirectoryName() {
        return NOTES_DIRECTORY;
    }
    private static final String INDEX_FILE = "padnote-index.json";
    private static final String LEGACY_FILE = "padnote-current.json";
    private static final int MAX_NOTE_BYTES = 50 * 1024 * 1024;

    private NoteStore() {
    }

    static synchronized List<Entry> list(Context context) throws Exception {
        JSONObject index = loadIndex(context);
        migrateLegacyIfNeeded(context, index);
        List<Entry> entries = entriesFromIndex(index);
        Collections.sort(entries, (first, second) -> Long.compare(second.updatedAt, first.updatedAt));
        return entries;
    }

    static synchronized Entry create(Context context, String requestedTitle) throws Exception {
        JSONObject index = loadIndex(context);
        migrateLegacyIfNeeded(context, index);
        String id = "note-" + UUID.randomUUID().toString().replace("-", "");
        String title = normalizeTitle(requestedTitle, "未命名笔记");
        long now = System.currentTimeMillis();
        JSONObject document = emptyDocument(id, title, now);
        writeAtomic(noteFile(context, id), document.toString());
        Entry entry = new Entry(id, title, now, 0, 1);
        upsertEntry(index, entry);
        saveIndex(context, index);
        return entry;
    }

    static synchronized JSONObject load(Context context, String noteId) throws Exception {
        File file = noteFile(context, noteId);
        recoverAtomicFile(file);
        if (!file.exists()) {
            throw new IllegalStateException("笔记文件不存在");
        }
        return new JSONObject(readFile(file));
    }

    static synchronized Entry save(Context context, String noteId, String requestedTitle,
                                   String documentJson) throws Exception {
        JSONObject document = new JSONObject(documentJson);
        validateDocument(document);
        String title = normalizeTitle(requestedTitle,
                normalizeTitle(document.optString("title"), "未命名笔记"));
        long now = System.currentTimeMillis();
        document.put("id", noteId);
        document.put("title", title);
        document.put("updatedAt", now);
        JSONArray strokes = document.getJSONArray("strokes");
        writeAtomic(noteFile(context, noteId), document.toString());

        JSONObject index = loadIndex(context);
        Entry entry = new Entry(noteId, title, now, strokes.length(),
                Math.max(1, document.optInt("pageCount", 1)));
        upsertEntry(index, entry);
        saveIndex(context, index);
        return entry;
    }

    static synchronized Entry rename(Context context, String noteId, String requestedTitle)
            throws Exception {
        JSONObject document = load(context, noteId);
        String title = normalizeTitle(requestedTitle, "未命名笔记");
        long now = System.currentTimeMillis();
        document.put("title", title);
        document.put("updatedAt", now);
        writeAtomic(noteFile(context, noteId), document.toString());

        JSONObject index = loadIndex(context);
        Entry entry = new Entry(noteId, title, now,
                document.optJSONArray("strokes") == null ? 0 : document.getJSONArray("strokes").length(),
                Math.max(1, document.optInt("pageCount", 1)));
        upsertEntry(index, entry);
        saveIndex(context, index);
        return entry;
    }

    static synchronized void delete(Context context, String noteId) throws Exception {
        JSONObject index = loadIndex(context);
        File target = noteFile(context, noteId);
        File tombstone = new File(target.getParentFile(), target.getName() + ".deleted");
        if (tombstone.exists() && !tombstone.delete()) {
            throw new IllegalStateException("无法清理旧删除标记");
        }
        if (target.exists() && !target.renameTo(tombstone)) {
            throw new IllegalStateException("无法移动待删除笔记");
        }
        removeEntry(index, noteId);
        try {
            saveIndex(context, index);
        } catch (Exception error) {
            if (tombstone.exists()) {
                tombstone.renameTo(target);
            }
            throw error;
        }
        if (tombstone.exists()) {
            tombstone.delete();
        }
        File pdf = pdfFile(context, noteId);
        if (pdf.exists() && !pdf.delete()) throw new IllegalStateException("笔记已删除，但 PDF 原文清理失败");
    }

    static synchronized Entry importDocument(Context context, JSONObject imported,
                                             String fallbackTitle) throws Exception {
        return importDocument(context, imported, fallbackTitle, null);
    }

    static synchronized Entry importDocument(Context context, JSONObject imported,
                                             String fallbackTitle, File pdfSource) throws Exception {
        validateDocument(imported);
        if (imported.optInt("pdfPageCount", 0) > 0 && pdfSource == null) {
            throw new IllegalArgumentException("PDF 笔记请导入包含原文的 .padnote.zip 包");
        }
        JSONObject index = loadIndex(context);
        migrateLegacyIfNeeded(context, index);
        String id = "note-" + UUID.randomUUID().toString().replace("-", "");
        String importedTitle = normalizeTitle(imported.optString("title"), "");
        if (importedTitle.isEmpty() || "未命名笔记".equals(importedTitle)) {
            importedTitle = normalizeTitle(fallbackTitle, "导入的笔记");
        }
        long now = System.currentTimeMillis();
        imported.put("id", id);
        imported.put("title", importedTitle);
        imported.put("updatedAt", now);
        JSONArray strokes = imported.getJSONArray("strokes");
        if (pdfSource != null) {
            try (InputStream input = new FileInputStream(pdfSource);
                 FileOutputStream output = new FileOutputStream(pdfFile(context, id))) {
                PdfNoteIO.copy(input, output, PdfNoteIO.MAX_PDF_BYTES);
            }
        }
        writeAtomic(noteFile(context, id), imported.toString());
        Entry entry = new Entry(id, importedTitle, now, strokes.length(),
                Math.max(1, imported.optInt("pageCount", 1)));
        upsertEntry(index, entry);
        saveIndex(context, index);
        return entry;
    }

    static String readExternalNote(InputStream input) throws Exception {
        return readLimited(input, MAX_NOTE_BYTES);
    }

    private static JSONObject emptyDocument(String id, String title, long now) throws Exception {
        JSONObject document = new JSONObject();
        document.put("schemaVersion", 6);
        document.put("id", id);
        document.put("title", title);
        document.put("updatedAt", now);
        document.put("canvasWidth", 0);
        document.put("canvasHeight", 0);
        document.put("pageWidth", 0);
        document.put("pageHeight", 0);
        document.put("pageGap", 0);
        document.put("pageCount", 1);
        document.put("strokes", new JSONArray());
        document.put("textBoxes", new JSONArray());
        return document;
    }

    private static JSONObject loadIndex(Context context) throws Exception {
        File file = new File(context.getFilesDir(), INDEX_FILE);
        recoverAtomicFile(file);
        if (!file.exists()) {
            JSONObject index = new JSONObject();
            index.put("schemaVersion", 1);
            index.put("legacyMigrated", false);
            index.put("notes", new JSONArray());
            return index;
        }
        JSONObject index = new JSONObject(readFile(file));
        if (index.optInt("schemaVersion", 0) != 1 || index.optJSONArray("notes") == null) {
            throw new IllegalStateException("笔记索引格式不兼容");
        }
        return index;
    }

    private static void migrateLegacyIfNeeded(Context context, JSONObject index) throws Exception {
        if (index.optBoolean("legacyMigrated", false)) {
            return;
        }
        File legacy = new File(context.getFilesDir(), LEGACY_FILE);
        recoverAtomicFile(legacy);
        if (legacy.exists() && legacy.length() > 0) {
            JSONObject document = new JSONObject(readFile(legacy));
            validateDocument(document);
            String id = "note-legacy-" + System.currentTimeMillis();
            String title = normalizeTitle(document.optString("title"), "旧版笔记");
            if ("未命名笔记".equals(title)) {
                title = "旧版笔记";
            }
            long updatedAt = document.optLong("updatedAt", legacy.lastModified());
            document.put("id", id);
            document.put("title", title);
            document.put("updatedAt", updatedAt);
            JSONArray strokes = document.getJSONArray("strokes");
            writeAtomic(noteFile(context, id), document.toString());
            upsertEntry(index, new Entry(id, title, updatedAt, strokes.length(),
                    Math.max(1, document.optInt("pageCount", 1))));
        }
        index.put("legacyMigrated", true);
        saveIndex(context, index);
    }

    private static List<Entry> entriesFromIndex(JSONObject index) throws Exception {
        List<Entry> entries = new ArrayList<>();
        JSONArray notes = index.getJSONArray("notes");
        for (int position = 0; position < notes.length(); position++) {
            entries.add(Entry.fromJson(notes.getJSONObject(position)));
        }
        return entries;
    }

    private static void upsertEntry(JSONObject index, Entry replacement) throws Exception {
        List<Entry> entries = entriesFromIndex(index);
        boolean replaced = false;
        for (int position = 0; position < entries.size(); position++) {
            if (entries.get(position).id.equals(replacement.id)) {
                entries.set(position, replacement);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            entries.add(replacement);
        }
        writeEntries(index, entries);
    }

    private static void removeEntry(JSONObject index, String noteId) throws Exception {
        List<Entry> entries = entriesFromIndex(index);
        entries.removeIf(entry -> entry.id.equals(noteId));
        writeEntries(index, entries);
    }

    private static void writeEntries(JSONObject index, List<Entry> entries) throws Exception {
        entries.sort(Comparator.comparingLong((Entry entry) -> entry.updatedAt).reversed());
        JSONArray notes = new JSONArray();
        for (Entry entry : entries) {
            notes.put(entry.toJson());
        }
        index.put("notes", notes);
    }

    private static void saveIndex(Context context, JSONObject index) throws Exception {
        writeAtomic(new File(context.getFilesDir(), INDEX_FILE), index.toString());
    }

    private static File noteFile(Context context, String noteId) throws Exception {
        if (noteId == null || !noteId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("非法笔记 ID");
        }
        File directory = new File(context.getFilesDir(), NOTES_DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建笔记目录");
        }
        return new File(directory, noteId + ".json");
    }

    static File pdfFile(Context context, String noteId) throws Exception {
        return new File(noteFile(context, noteId).getParentFile(), noteId + ".pdf");
    }

    private static void validateDocument(JSONObject document) throws Exception {
        int version = document.optInt("schemaVersion", 0);
        if (version < 1 || version > 8) {
            throw new IllegalArgumentException("只支持 PadNote schemaVersion 1 至 8");
        }
        if (document.optJSONArray("strokes") == null) {
            throw new IllegalArgumentException("笔记缺少 strokes 数据");
        }
        int pages = document.optInt("pageCount", 1);
        if (pages < 1 || pages > 500) {
            throw new IllegalArgumentException("笔记页数必须在 1–500 之间");
        }
        int pdfPages = document.optInt("pdfPageCount", 0);
        if (pdfPages < 0 || pdfPages > pages || (pdfPages > 0 && version < 7)) {
            throw new IllegalArgumentException("PDF 页面元数据无效");
        }
        // schema 3/4 stored content per fragment in textBoxes; schema 5 moved it
        // into textFlows and derives fragments at load time.
        if (version >= 3 && version <= 4 && document.optJSONArray("textBoxes") == null) {
            throw new IllegalArgumentException("schemaVersion 3/4 笔记缺少 textBoxes 数据");
        }
        if (version >= 5 && document.optJSONArray("textFlows") == null) {
            throw new IllegalArgumentException("schemaVersion 5 笔记缺少 textFlows 数据");
        }
    }

    private static String normalizeTitle(String title, String fallback) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty()) {
            normalized = fallback;
        }
        if (normalized.length() > 80) {
            normalized = normalized.substring(0, 80);
        }
        return normalized;
    }

    private static String readFile(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            return readLimited(input, MAX_NOTE_BYTES);
        }
    }

    private static String readLimited(InputStream input, int maxBytes) throws Exception {
        byte[] buffer = new byte[8192];
        int total = 0;
        try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalArgumentException("笔记文件超过 50 MB 限制");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeAtomic(File target, String value) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建存储目录");
        }
        recoverAtomicFile(target);
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        File backup = new File(target.getParentFile(), target.getName() + ".bak");
        if (backup.exists() && !backup.delete()) {
            throw new IllegalStateException("无法清理旧存储备份");
        }
        boolean hadTarget = target.exists();
        if (hadTarget && !target.renameTo(backup)) {
            throw new IllegalStateException("无法备份旧笔记文件");
        }
        if (!temporary.renameTo(target)) {
            if (hadTarget) {
                backup.renameTo(target);
            }
            throw new IllegalStateException("无法提交笔记文件");
        }
        if (backup.exists()) {
            backup.delete();
        }
    }

    private static void recoverAtomicFile(File target) throws Exception {
        File parent = target.getParentFile();
        if (parent == null) {
            return;
        }
        File temporary = new File(parent, target.getName() + ".tmp");
        File backup = new File(parent, target.getName() + ".bak");
        if (target.exists()) {
            if (backup.exists() && !backup.delete()) {
                throw new IllegalStateException("无法清理存储备份");
            }
            if (temporary.exists() && !temporary.delete()) {
                throw new IllegalStateException("无法清理未完成写入");
            }
            return;
        }
        if (backup.exists()) {
            if (!backup.renameTo(target)) {
                throw new IllegalStateException("无法恢复存储备份");
            }
            if (temporary.exists() && !temporary.delete()) {
                throw new IllegalStateException("无法清理未完成写入");
            }
            return;
        }
        if (temporary.exists() && !temporary.renameTo(target)) {
            throw new IllegalStateException("无法恢复未完成写入");
        }
    }
}
