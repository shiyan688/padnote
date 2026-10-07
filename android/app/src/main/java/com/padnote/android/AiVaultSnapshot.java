package com.padnote.android;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Immutable, explicitly selected knowledge-base material for one AI context. */
final class AiVaultSnapshot implements NoteTools.VaultReader {
    static final int MAX_NOTE_BYTES = 512 * 1024;
    static final int MAX_TOTAL_BYTES = 2 * 1024 * 1024;
    static final int MAX_NOTES = 12;
    private final List<VaultStore.VaultNote> notes;
    private final Map<String, String> contentByFile;
    private final String digest;

    private AiVaultSnapshot(List<VaultStore.VaultNote> notes,
                            Map<String, String> contentByFile,
                            String digest) {
        this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
        this.contentByFile = Collections.unmodifiableMap(new LinkedHashMap<>(contentByFile));
        this.digest = digest;
    }

    static AiVaultSnapshot capture(NoteTools.VaultReader vault,
                                   Set<String> selectedNoteIds) throws Exception {
        if (vault == null) {
            throw new IllegalArgumentException("vault is required");
        }
        Set<String> requested = selectedNoteIds == null
                ? Collections.emptySet() : new LinkedHashSet<>(selectedNoteIds);
        if (requested.size() > MAX_NOTES) {
            throw new IllegalArgumentException("一次最多选择 " + MAX_NOTES + " 本额外材料");
        }
        List<VaultStore.VaultNote> selected = new ArrayList<>();
        Map<String, String> contents = new LinkedHashMap<>();
        Set<String> found = new LinkedHashSet<>();
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        int totalBytes = 0;
        for (VaultStore.VaultNote note : vault.list()) {
            if (note.noteId == null || note.noteId.isEmpty() || !requested.contains(note.noteId)) {
                continue;
            }
            if (!found.add(note.noteId)) {
                throw new IllegalArgumentException("知识库中存在重复的笔记 ID：" + note.noteId);
            }
            String content = vault.readBounded(note.fileName, MAX_NOTE_BYTES);
            int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (totalBytes > MAX_TOTAL_BYTES - contentBytes) {
                throw new IllegalArgumentException("已选材料总量超出 2 MiB，请缩小选择");
            }
            totalBytes += contentBytes;
            VaultStore.VaultNote frozen = new VaultStore.VaultNote(
                    note.fileName, note.noteId, note.title, note.pageCount,
                    note.digitizedAt, note.sourceModifiedAt);
            selected.add(frozen);
            contents.put(frozen.fileName, content);
            updateDigest(hash, frozen.noteId);
            updateDigest(hash, frozen.fileName);
            updateDigest(hash, String.valueOf(frozen.sourceModifiedAt));
            updateDigest(hash, content);
        }
        if (!found.equals(requested)) {
            Set<String> missing = new LinkedHashSet<>(requested);
            missing.removeAll(found);
            throw new IllegalArgumentException("找不到已选择的知识库笔记：" + missing);
        }
        return new AiVaultSnapshot(selected, contents, hex(hash.digest()));
    }

    boolean isEmpty() {
        return notes.isEmpty();
    }

    String digest() {
        return digest;
    }

    Set<String> noteIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (VaultStore.VaultNote note : notes) {
            ids.add(note.noteId);
        }
        return Collections.unmodifiableSet(ids);
    }

    List<String> titles() {
        List<String> titles = new ArrayList<>();
        for (VaultStore.VaultNote note : notes) {
            titles.add(note.title == null || note.title.isEmpty()
                    ? note.fileName.replaceAll("\\.md$", "") : note.title);
        }
        return Collections.unmodifiableList(titles);
    }

    @Override
    public List<VaultStore.VaultNote> list() {
        return new ArrayList<>(notes);
    }

    @Override
    public String read(String fileName) {
        String content = contentByFile.get(fileName);
        if (content == null) {
            throw new SecurityException("知识库笔记不在本次授权快照内");
        }
        return content;
    }

    JSONObject toJson() throws JSONException {
        JSONArray values = new JSONArray();
        for (VaultStore.VaultNote note : notes) {
            String content = contentByFile.get(note.fileName);
            values.put(new JSONObject()
                    .put("fileName", note.fileName)
                    .put("noteId", note.noteId)
                    .put("title", note.title)
                    .put("pageCount", note.pageCount)
                    .put("digitizedAt", note.digitizedAt)
                    .put("sourceModifiedAt", note.sourceModifiedAt)
                    .put("content", content == null ? "" : content));
        }
        return new JSONObject().put("digest", digest).put("notes", values);
    }

    static AiVaultSnapshot fromJson(JSONObject json) throws Exception {
        JSONArray values = json == null ? null : json.optJSONArray("notes");
        if (values == null || values.length() > MAX_NOTES) {
            throw new JSONException("AI 材料快照无效");
        }
        List<VaultStore.VaultNote> notes = new ArrayList<>();
        Map<String, String> contents = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        int totalBytes = 0;
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.getJSONObject(index);
            String fileName = value.getString("fileName");
            String noteId = value.getString("noteId");
            String content = value.getString("content");
            if (fileName.isEmpty() || noteId.isEmpty() || !ids.add(noteId)) {
                throw new JSONException("AI 材料标识无效");
            }
            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_NOTE_BYTES || totalBytes > MAX_TOTAL_BYTES - bytes) {
                throw new JSONException("AI 材料快照超过大小限制");
            }
            totalBytes += bytes;
            VaultStore.VaultNote note = new VaultStore.VaultNote(fileName, noteId,
                    value.optString("title"), Math.max(1, value.optInt("pageCount", 1)),
                    value.optLong("digitizedAt", 0), value.optLong("sourceModifiedAt", 0));
            notes.add(note);
            contents.put(fileName, content);
            updateDigest(hash, note.noteId);
            updateDigest(hash, note.fileName);
            updateDigest(hash, String.valueOf(note.sourceModifiedAt));
            updateDigest(hash, content);
        }
        String computed = hex(hash.digest());
        if (!computed.equals(json.optString("digest"))) {
            throw new JSONException("AI 材料快照摘要不匹配");
        }
        return new AiVaultSnapshot(notes, contents, computed);
    }

    private static void updateDigest(MessageDigest hash, String value) {
        hash.update(value.getBytes(StandardCharsets.UTF_8));
        hash.update((byte) 0);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
