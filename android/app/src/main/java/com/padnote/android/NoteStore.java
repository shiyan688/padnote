package com.padnote.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class NoteStore {
    interface StoredFileValidator {
        void validate(String value) throws Exception;
    }

    interface WriteFaultInjector {
        void after(WriteStage stage) throws Exception;
    }

    enum WriteStage {
        TEMP_SYNCED,
        OLD_VERSION_BACKED_UP,
        NEW_VERSION_PROMOTED
    }

    static final WriteFaultInjector NO_WRITE_FAULTS = stage -> { };

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
            return new JSONObject().put("id", id).put("title", title)
                    .put("updatedAt", updatedAt).put("strokeCount", strokeCount)
                    .put("pageCount", pageCount);
        }

        static Entry fromJson(JSONObject json) throws Exception {
            return new Entry(json.getString("id"),
                    json.optString("title", "未命名笔记"), json.optLong("updatedAt", 0),
                    json.optInt("strokeCount", 0), Math.max(1, json.optInt("pageCount", 1)));
        }
    }

    /** A retained file which the user can restore or export instead of losing silently. */
    static final class RecoveryEntry {
        final String noteId;
        final String label;
        final File file;
        final boolean unsavedDraft;

        RecoveryEntry(String noteId, String label, File file, boolean unsavedDraft) {
            this.noteId = noteId;
            this.label = label;
            this.file = file;
            this.unsavedDraft = unsavedDraft;
        }
    }

    private static final String NOTES_DIRECTORY = "notes";
    private static final String INDEX_FILE = "padnote-index.json";
    private static final String LEGACY_FILE = "padnote-current.json";
    private static final String UNSAVED_SUFFIX = ".unsaved.json";
    static final int MAX_NOTE_BYTES = 50 * 1024 * 1024;

    private static final StoredFileValidator NOTE_VALIDATOR = value ->
            validateDocument(new JSONObject(value));
    private static final StoredFileValidator INDEX_VALIDATOR = value ->
            validateIndex(new JSONObject(value));

    private NoteStore() { }

    /** Directory name for sibling files such as note covers (see CoverStore). */
    static String notesDirectoryName() { return NOTES_DIRECTORY; }

    static synchronized List<Entry> list(Context context) throws Exception {
        JSONObject index = loadIndex(context);
        migrateLegacyIfNeeded(context, index);
        if (reconcileIndex(context, index)) saveIndex(context, index);
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
        writeAtomic(noteFile(context, id), document.toString(), NOTE_VALIDATOR);
        Entry entry = new Entry(id, title, now, 0, 1);
        upsertEntry(index, entry);
        saveIndex(context, index);
        return entry;
    }

    static synchronized JSONObject load(Context context, String noteId) throws Exception {
        File file = noteFile(context, noteId);
        recoverAtomicFile(file, NOTE_VALIDATOR);
        if (!file.exists()) throw new IllegalStateException("笔记文件不存在");
        String value = readFile(file);
        NOTE_VALIDATOR.validate(value);
        return new JSONObject(value);
    }

    static synchronized Entry save(Context context, String noteId, String requestedTitle,
                                   String documentJson) throws Exception {
        requireWithinLimit(documentJson);
        JSONObject document = new JSONObject(documentJson);
        validateDocument(document);
        String title = normalizeTitle(requestedTitle,
                normalizeTitle(document.optString("title"), "未命名笔记"));
        long now = System.currentTimeMillis();
        document.put("id", noteId);
        document.put("title", title);
        document.put("updatedAt", now);
        String storedValue = document.toString();
        requireWithinLimit(storedValue);
        JSONArray strokes = document.getJSONArray("strokes");

        // The recovery draft becomes durable before the last readable revision is
        // replaced, and remains if either the note or shelf index fails to commit.
        writeAtomic(recoveryDraftFile(context, noteId), storedValue, NOTE_VALIDATOR);
        writeAtomic(noteFile(context, noteId), storedValue, NOTE_VALIDATOR);

        JSONObject index = loadIndex(context);
        Entry entry = new Entry(noteId, title, now, strokes.length(),
                Math.max(1, document.optInt("pageCount", 1)));
        upsertEntry(index, entry);
        saveIndex(context, index);
        JSONObject reopened = load(context, noteId);
        if (reopened.optLong("updatedAt", -1) != now) {
            throw new IOException("保存后的笔记修订无法重新读取");
        }
        clearRecoveryDraft(context, noteId);
        return entry;
    }

    static synchronized Entry rename(Context context, String noteId, String requestedTitle)
            throws Exception {
        JSONObject document = load(context, noteId);
        String title = normalizeTitle(requestedTitle, "未命名笔记");
        long now = System.currentTimeMillis();
        document.put("title", title);
        document.put("updatedAt", now);
        writeAtomic(noteFile(context, noteId), document.toString(), NOTE_VALIDATOR);

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
        if (tombstone.exists() && !tombstone.delete()) throw new IOException("无法清理旧删除标记");
        if (target.exists() && !target.renameTo(tombstone)) throw new IOException("无法移动待删除笔记");
        removeEntry(index, noteId);
        try {
            saveIndex(context, index);
        } catch (Exception error) {
            if (tombstone.exists()) tombstone.renameTo(target);
            throw error;
        }
        if (tombstone.exists()) tombstone.delete();
        File pdf = pdfFile(context, noteId);
        deleteFileFamily(target);
        deleteFileFamily(pdf);
        deleteFileFamily(recoveryDraftFile(context, noteId));
    }

    static synchronized Entry importDocument(Context context, JSONObject imported,
                                             String fallbackTitle) throws Exception {
        return importDocument(context, imported, fallbackTitle, null);
    }

    static synchronized Entry importDocument(Context context, JSONObject imported,
                                             String fallbackTitle, File pdfSource) throws Exception {
        validateDocument(imported);
        requireWithinLimit(imported.toString());
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
        imported.put("id", id).put("title", importedTitle).put("updatedAt", now);
        JSONArray strokes = imported.getJSONArray("strokes");
        if (pdfSource != null) {
            try (InputStream input = new FileInputStream(pdfSource);
                 FileOutputStream output = new FileOutputStream(pdfFile(context, id))) {
                PdfNoteIO.copy(input, output, PdfNoteIO.MAX_PDF_BYTES);
                output.flush();
                output.getFD().sync();
            }
        }
        try {
            writeAtomic(noteFile(context, id), imported.toString(), NOTE_VALIDATOR);
            Entry entry = new Entry(id, importedTitle, now, strokes.length(),
                    Math.max(1, imported.optInt("pageCount", 1)));
            upsertEntry(index, entry);
            saveIndex(context, index);
            return entry;
        } catch (Exception error) {
            // Keep a copied PDF when a JSON/index failure occurs; the orphan scanner
            // can expose it for manual recovery rather than pretending import succeeded.
            throw error;
        }
    }

    static String readExternalNote(InputStream input) throws Exception {
        return readLimited(input, MAX_NOTE_BYTES);
    }

    static synchronized List<RecoveryEntry> listRecovery(Context context) throws Exception {
        File[] files = notesDirectory(context).listFiles();
        List<RecoveryEntry> result = new ArrayList<>();
        if (files == null) return result;
        for (File file : files) {
            String name = file.getName();
            if (!file.isFile()) continue;
            if (name.endsWith(UNSAVED_SUFFIX)) {
                String noteId = name.substring(0, name.length() - UNSAVED_SUFFIX.length());
                result.add(new RecoveryEntry(noteId, "未完成保存", file, true));
            } else if (name.contains(".corrupt-") ||
                    (name.endsWith(".json") && !isReadableNote(file))) {
                result.add(new RecoveryEntry(noteIdFromRecoveryName(name),
                        "无法读取的原始文件", file, false));
            } else if (name.endsWith(".json")) {
                try {
                    JSONObject document = new JSONObject(readFile(file));
                    String noteId = name.substring(0, name.length() - 5);
                    if (document.optInt("pdfPageCount", 0) > 0 &&
                            !pdfFile(context, noteId).isFile()) {
                        result.add(new RecoveryEntry(noteId, "PDF 原文缺失", file, false));
                    }
                } catch (Exception ignored) {
                    // Invalid JSON was already added by the branch above.
                }
            }
        }
        result.sort((first, second) -> Long.compare(second.file.lastModified(), first.file.lastModified()));
        return result;
    }

    static synchronized Entry restoreDraft(Context context, String noteId) throws Exception {
        File draft = recoveryDraftFile(context, noteId);
        if (!draft.isFile()) throw new IllegalStateException("未保存副本不存在");
        String value = readFile(draft);
        NOTE_VALIDATOR.validate(value);
        JSONObject document = new JSONObject(value);
        File target = noteFile(context, noteId);
        try {
            recoverAtomicFile(target, NOTE_VALIDATOR);
        } catch (Exception noValidVersion) {
            quarantineInvalidCandidates(target, NOTE_VALIDATOR);
        }
        return save(context, noteId, document.optString("title", "恢复的笔记"), value);
    }

    static synchronized void clearRecoveryDraft(Context context, String noteId) throws Exception {
        deleteFileFamily(recoveryDraftFile(context, noteId));
    }

    private static JSONObject emptyDocument(String id, String title, long now) throws Exception {
        return new JSONObject().put("schemaVersion", 8).put("id", id).put("title", title)
                .put("updatedAt", now).put("canvasWidth", 0).put("canvasHeight", 0)
                .put("pageWidth", 0).put("pageHeight", 0).put("pageGap", 0)
                .put("pageCount", 1).put("strokes", new JSONArray())
                .put("textFlows", new JSONArray()).put("textBoxes", new JSONArray())
                .put("images", new JSONArray());
    }

    private static JSONObject loadIndex(Context context) throws Exception {
        File file = new File(context.getFilesDir(), INDEX_FILE);
        try {
            recoverAtomicFile(file, INDEX_VALIDATOR);
        } catch (Exception invalidIndex) {
            quarantineInvalidCandidates(file, INDEX_VALIDATOR);
        }
        if (!file.exists()) {
            return new JSONObject().put("schemaVersion", 1).put("legacyMigrated", false)
                    .put("notes", new JSONArray());
        }
        JSONObject index = new JSONObject(readFile(file));
        validateIndex(index);
        return index;
    }

    private static void migrateLegacyIfNeeded(Context context, JSONObject index) throws Exception {
        if (index.optBoolean("legacyMigrated", false)) return;
        File legacy = new File(context.getFilesDir(), LEGACY_FILE);
        recoverAtomicFile(legacy, NOTE_VALIDATOR);
        if (legacy.exists() && legacy.length() > 0) {
            JSONObject document = new JSONObject(readFile(legacy));
            validateDocument(document);
            String id = "note-legacy-" + System.currentTimeMillis();
            String title = normalizeTitle(document.optString("title"), "旧版笔记");
            if ("未命名笔记".equals(title)) title = "旧版笔记";
            long updatedAt = document.optLong("updatedAt", legacy.lastModified());
            document.put("id", id).put("title", title).put("updatedAt", updatedAt);
            JSONArray strokes = document.getJSONArray("strokes");
            writeAtomic(noteFile(context, id), document.toString(), NOTE_VALIDATOR);
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
        if (!replaced) entries.add(replacement);
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
        for (Entry entry : entries) notes.put(entry.toJson());
        index.put("notes", notes);
    }

    private static void saveIndex(Context context, JSONObject index) throws Exception {
        writeAtomic(new File(context.getFilesDir(), INDEX_FILE), index.toString(), INDEX_VALIDATOR);
    }

    private static File notesDirectory(Context context) throws Exception {
        File directory = new File(context.getFilesDir(), NOTES_DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法创建笔记目录");
        return directory;
    }

    private static File noteFile(Context context, String noteId) throws Exception {
        validateNoteId(noteId);
        return new File(notesDirectory(context), noteId + ".json");
    }

    private static File recoveryDraftFile(Context context, String noteId) throws Exception {
        validateNoteId(noteId);
        return new File(notesDirectory(context), noteId + UNSAVED_SUFFIX);
    }

    private static void validateNoteId(String noteId) {
        if (noteId == null || !noteId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("非法笔记 ID");
        }
    }

    static File pdfFile(Context context, String noteId) throws Exception {
        return new File(noteFile(context, noteId).getParentFile(), noteId + ".pdf");
    }

    static void validateDocument(JSONObject document) throws Exception {
        int version = document.optInt("schemaVersion", 0);
        if (version < 1 || version > 8) {
            throw new IllegalArgumentException("只支持 PadNote schemaVersion 1 至 8");
        }
        JSONArray strokes = document.optJSONArray("strokes");
        if (strokes == null) throw new IllegalArgumentException("笔记缺少 strokes 数据");
        int pages = document.optInt("pageCount", 1);
        if (pages < 1 || pages > 500) throw new IllegalArgumentException("笔记页数必须在 1–500 之间");
        int pdfPages = document.optInt("pdfPageCount", 0);
        if (pdfPages < 0 || pdfPages > pages || (pdfPages > 0 && version < 7)) {
            throw new IllegalArgumentException("PDF 页面元数据无效");
        }
        JSONArray boxes = document.optJSONArray("textBoxes");
        JSONArray flows = document.optJSONArray("textFlows");
        if (version >= 3 && version <= 4 && boxes == null) {
            throw new IllegalArgumentException("schemaVersion 3/4 笔记缺少 textBoxes 数据");
        }
        // Early schema 5-6 empty notes were created without textFlows. Preserve
        // those only when they also contain no legacy text; non-empty ambiguity is rejected.
        if (version >= 5 && flows == null && boxes != null && boxes.length() > 0) {
            throw new IllegalArgumentException("schemaVersion 5+ 笔记缺少 textFlows 数据");
        }
        for (int index = 0; index < strokes.length(); index++) {
            InkStroke.fromJson(strokes.getJSONObject(index));
        }
        Set<String> flowIds = new HashSet<>();
        if (flows != null) {
            for (int index = 0; index < flows.length(); index++) {
                TextFlow flow = TextFlow.fromJson(flows.getJSONObject(index));
                if (!flowIds.add(flow.id)) throw new IllegalArgumentException("文字流 ID 重复");
                if (flow.anchorPageIndex >= pages) throw new IllegalArgumentException("文字流页码超出范围");
            }
        } else if (boxes != null) {
            for (int index = 0; index < boxes.length(); index++) {
                JSONObject box = boxes.getJSONObject(index);
                if (box.optString("source", "").length() > TextFlow.MAX_SOURCE_LENGTH) {
                    throw new IllegalArgumentException("文字内容超过单个文字流限制");
                }
                requireFinite(box.optDouble("x", 0), "文字位置无效");
                requireFinite(box.optDouble("y", 0), "文字位置无效");
            }
        }
        JSONArray imageArray = document.optJSONArray("images");
        long imagePixels = 0;
        long imageEncoded = 0;
        if (imageArray != null) {
            for (int index = 0; index < imageArray.length(); index++) {
                JSONObject storedImage = imageArray.getJSONObject(index);
                NoteImage.StoredImageInfo info = NoteImage.inspectJson(storedImage);
                int page = storedImage.getInt("page");
                double width = storedImage.getDouble("width");
                double height = storedImage.getDouble("height");
                requireFinite(width, "图片尺寸无效");
                requireFinite(height, "图片尺寸无效");
                if (page < 0 || page >= pages || width <= 0 || height <= 0) {
                    throw new IllegalArgumentException("图片页码或尺寸无效");
                }
                imagePixels += (long) info.width * info.height;
                imageEncoded += info.encodedLength;
            }
        }
        if (imagePixels > NoteImage.MAX_DOCUMENT_PIXELS ||
                imageEncoded > NoteImage.MAX_DOCUMENT_ENCODED) {
            throw new IllegalArgumentException("笔记图片超过容量上限");
        }
    }

    private static void requireFinite(double value, String message) {
        if (Double.isNaN(value) || Double.isInfinite(value)) throw new IllegalArgumentException(message);
    }

    private static String normalizeTitle(String title, String fallback) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty()) normalized = fallback;
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
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
                if (total > maxBytes) throw new IllegalArgumentException("笔记文件超过 50 MB 限制");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void requireWithinLimit(String value) {
        if (!encodedLengthWithinLimit(value, MAX_NOTE_BYTES)) {
            throw new IllegalArgumentException("笔记文件超过 50 MB 限制");
        }
    }

    static boolean encodedLengthWithinLimit(String value, int maximumBytes) {
        return value != null && maximumBytes >= 0 &&
                value.getBytes(StandardCharsets.UTF_8).length <= maximumBytes;
    }

    private static void writeAtomic(File target, String value, StoredFileValidator validator)
            throws Exception {
        writeAtomic(target, value, validator, NO_WRITE_FAULTS);
    }

    /** Package-visible deterministic transaction used by fault-injection tests. */
    static void writeAtomic(File target, String value, StoredFileValidator validator,
                            WriteFaultInjector faults) throws Exception {
        writeAtomic(target, value, validator, faults, MAX_NOTE_BYTES);
    }

    static void writeAtomic(File target, String value, StoredFileValidator validator,
                            WriteFaultInjector faults, int maximumBytes) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建存储目录");
        }
        if (!encodedLengthWithinLimit(value, maximumBytes)) {
            throw new IllegalArgumentException("笔记文件超过 50 MB 限制");
        }
        validator.validate(value);
        recoverAtomicFile(target, validator);
        File temporary = new File(parent, target.getName() + ".tmp");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        validator.validate(readFile(temporary));
        faults.after(WriteStage.TEMP_SYNCED);

        File backup = new File(parent, target.getName() + ".bak");
        if (backup.exists() && !backup.delete()) throw new IOException("无法轮换上一份存储备份");
        boolean hadTarget = target.exists();
        if (hadTarget && !target.renameTo(backup)) throw new IOException("无法备份旧笔记文件");
        faults.after(WriteStage.OLD_VERSION_BACKED_UP);
        if (!temporary.renameTo(target)) {
            if (hadTarget) backup.renameTo(target);
            throw new IOException("无法提交笔记文件");
        }
        faults.after(WriteStage.NEW_VERSION_PROMOTED);
        try {
            validator.validate(readFile(target));
        } catch (Exception invalidCommit) {
            quarantine(target);
            if (hadTarget) backup.renameTo(target);
            throw new IOException("提交后的文件校验失败", invalidCommit);
        }
        // Keep .bak as the last validated revision until a later successful save.
    }

    static void recoverAtomicFile(File target, StoredFileValidator validator) throws Exception {
        File parent = target.getParentFile();
        if (parent == null) return;
        File temporary = new File(parent, target.getName() + ".tmp");
        File backup = new File(parent, target.getName() + ".bak");
        boolean targetValid = isValid(target, validator);
        boolean backupValid = isValid(backup, validator);
        boolean temporaryValid = isValid(temporary, validator);
        if (targetValid) {
            // A valid target is the committed revision. Preserve a malformed temp
            // because it may contain edits; a valid stale temp is safe to remove.
            if (temporary.exists()) {
                if (temporaryValid) {
                    if (!temporary.delete()) throw new IOException("无法清理已提交临时文件");
                } else {
                    quarantine(temporary);
                }
            }
            if (backup.exists() && !backupValid) quarantine(backup);
            return;
        }
        if (backupValid) {
            if (target.exists()) quarantine(target);
            if (!backup.renameTo(target)) throw new IOException("无法恢复存储备份");
            if (temporary.exists()) quarantine(temporary);
            return;
        }
        if (temporaryValid) {
            if (target.exists()) quarantine(target);
            if (backup.exists()) quarantine(backup);
            if (!temporary.renameTo(target)) throw new IOException("无法恢复未完成写入");
            return;
        }
        if (target.exists() || backup.exists() || temporary.exists()) {
            throw new IOException("没有可读取的存储版本；原文件已保留供恢复");
        }
    }

    private static boolean isValid(File file, StoredFileValidator validator) {
        if (!file.isFile()) return false;
        try {
            validator.validate(readFile(file));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isReadableNote(File file) { return isValid(file, NOTE_VALIDATOR); }

    private static void quarantineInvalidCandidates(File target, StoredFileValidator validator)
            throws Exception {
        File parent = target.getParentFile();
        if (parent == null) return;
        File[] candidates = new File[]{target, new File(parent, target.getName() + ".bak"),
                new File(parent, target.getName() + ".tmp")};
        for (File candidate : candidates) {
            if (candidate.exists() && !isValid(candidate, validator)) quarantine(candidate);
        }
    }

    private static void quarantine(File file) throws IOException {
        File destination = nextCorruptFile(file);
        if (!file.renameTo(destination)) throw new IOException("无法保留损坏文件：" + file.getName());
    }

    private static File nextCorruptFile(File file) {
        long suffix = System.currentTimeMillis();
        File candidate;
        do {
            candidate = new File(file.getParentFile(), file.getName() + ".corrupt-" + suffix++);
        } while (candidate.exists());
        return candidate;
    }

    private static void validateIndex(JSONObject index) throws Exception {
        if (index.optInt("schemaVersion", 0) != 1 || index.optJSONArray("notes") == null) {
            throw new IllegalStateException("笔记索引格式不兼容");
        }
        entriesFromIndex(index);
    }

    private static boolean reconcileIndex(Context context, JSONObject index) throws Exception {
        List<Entry> entries = entriesFromIndex(index);
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (Entry entry : entries) byId.put(entry.id, entry);
        File[] files = notesDirectory(context).listFiles((directory, name) ->
                name.endsWith(".json") && !name.endsWith(UNSAVED_SUFFIX));
        if (files == null) return false;
        boolean changed = false;
        for (File file : files) {
            String id = file.getName().substring(0, file.getName().length() - 5);
            if (!id.matches("[A-Za-z0-9_-]+")) continue;
            try {
                JSONObject document = load(context, id);
                Entry recovered = new Entry(id,
                        normalizeTitle(document.optString("title"), "恢复的笔记"),
                        document.optLong("updatedAt", file.lastModified()),
                        document.getJSONArray("strokes").length(),
                        Math.max(1, document.optInt("pageCount", 1)));
                Entry known = byId.get(id);
                if (known == null || known.updatedAt != recovered.updatedAt ||
                        !known.title.equals(recovered.title) || known.pageCount != recovered.pageCount ||
                        known.strokeCount != recovered.strokeCount) {
                    byId.put(id, recovered);
                    changed = true;
                }
            } catch (Exception ignored) {
                // The original file remains visible through listRecovery().
            }
        }
        if (changed) writeEntries(index, new ArrayList<>(byId.values()));
        return changed;
    }

    private static String noteIdFromRecoveryName(String name) {
        int marker = name.indexOf(".json");
        return marker > 0 ? name.substring(0, marker) : "unknown";
    }

    private static void deleteFileFamily(File target) throws IOException {
        File parent = target.getParentFile();
        if (parent == null) return;
        File[] files = parent.listFiles((directory, name) -> name.equals(target.getName()) ||
                name.equals(target.getName() + ".bak") || name.equals(target.getName() + ".tmp") ||
                name.equals(target.getName() + ".deleted") ||
                name.startsWith(target.getName() + ".corrupt-"));
        if (files == null) return;
        for (File file : files) {
            if (file.exists() && !file.delete()) {
                throw new IOException("无法清理已删除笔记的存储副本");
            }
        }
    }
}
