package com.padnote.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.security.MessageDigest;

/** Bounded, credential-free, per-note persistence for the ordinary AI card. */
final class AiConversationStore {
    static final int SCHEMA_VERSION = 1;
    static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    static final int MAX_SELECTION_BYTES = 4 * 1024 * 1024;
    static final int MAX_TIMELINE_ENTRIES = 200;
    static final int MAX_WIRE_MESSAGES = 200;
    static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    private static final long MAX_COUNTER = Long.MAX_VALUE - 1024;
    private static final Object GLOBAL_LOCK = new Object();

    static final class VisibleEntry {
        final String kind;
        final String role;
        final String text;
        final String executorLabel;
        final boolean error;
        final List<String> receiptObjectIds;
        final String receiptDigest;
        final String receiptJson;
        final String adoptionId;
        final String adoptionSourceDigest;
        final String adoptionPdfDigest;
        final String adoptionPermission;
        final RectF adoptionAnchor;
        final boolean adopted;

        VisibleEntry(String kind, String role, String text, String executorLabel, boolean error) {
            this(kind, role, text, executorLabel, error,
                    Collections.emptyList(), "", "", "", "", "", "", null, false);
        }

        VisibleEntry(String kind, String role, String text, String executorLabel, boolean error,
                     List<String> receiptObjectIds, String receiptDigest, String receiptJson) {
            this(kind, role, text, executorLabel, error, receiptObjectIds, receiptDigest,
                    receiptJson, "", "", "", "", null, false);
        }

        VisibleEntry(String kind, String role, String text, String executorLabel, boolean error,
                     List<String> receiptObjectIds, String receiptDigest, String receiptJson,
                     String adoptionId, String adoptionSourceDigest, String adoptionPdfDigest,
                     String adoptionPermission, RectF adoptionAnchor, boolean adopted) {
            this.kind = kind == null ? "message" : kind;
            this.role = role == null ? "assistant" : role;
            this.text = text == null ? "" : text;
            this.executorLabel = executorLabel == null ? "" : executorLabel;
            this.error = error;
            this.receiptObjectIds = immutableCopy(receiptObjectIds);
            this.receiptDigest = receiptDigest == null ? "" : receiptDigest;
            this.receiptJson = receiptJson == null ? "" : receiptJson;
            this.adoptionId = safe(adoptionId);
            this.adoptionSourceDigest = safe(adoptionSourceDigest);
            this.adoptionPdfDigest = safe(adoptionPdfDigest);
            this.adoptionPermission = safe(adoptionPermission);
            this.adoptionAnchor = adoptionAnchor == null ? null : new RectF(adoptionAnchor);
            this.adopted = adopted;
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject().put("kind", kind).put("role", role).put("text", text)
                    .put("executorLabel", executorLabel).put("error", error)
                    .put("receiptDigest", receiptDigest)
                    .put("receipt", receiptJson)
                    .put("receiptObjectIds", new JSONArray(receiptObjectIds));
            if (!adoptionId.isEmpty() && adoptionAnchor != null) {
                json.put("adoption", new JSONObject().put("id", adoptionId)
                        .put("sourceDigest", adoptionSourceDigest)
                        .put("pdfDigest", adoptionPdfDigest)
                        .put("permission", adoptionPermission).put("adopted", adopted)
                        .put("left", adoptionAnchor.left).put("top", adoptionAnchor.top)
                        .put("right", adoptionAnchor.right).put("bottom", adoptionAnchor.bottom));
            }
            return json;
        }

        static VisibleEntry fromJson(JSONObject json) throws JSONException {
            JSONArray ids = json.optJSONArray("receiptObjectIds");
            List<String> objectIds = new ArrayList<>();
            if (ids != null) {
                if (ids.length() > 64) throw new JSONException("AI 修改凭据对象过多");
                for (int index = 0; index < ids.length(); index++) {
                    objectIds.add(bounded(ids.getString(index), 160));
                }
            }
            String receiptJson = bounded(json.optString("receipt"), MAX_TEXT_BYTES);
            String receiptDigest = bounded(json.optString("receiptDigest"), 128);
            if (!receiptJson.isEmpty()) {
                JSONObject receipt = new JSONObject(receiptJson);
                String embedded = receipt.optString("digest");
                JSONObject unsigned = new JSONObject(receipt.toString());
                unsigned.remove("digest");
                if (!embedded.equals(receiptDigest)
                        || !embedded.equals(sha256(unsigned.toString()))) {
                    throw new JSONException("AI 修改凭据摘要不匹配");
                }
                JSONArray changes = receipt.optJSONArray("changes");
                if (changes == null || changes.length() != objectIds.size()) {
                    throw new JSONException("AI 修改凭据对象不匹配");
                }
                java.util.LinkedHashSet<String> receiptIds = new java.util.LinkedHashSet<>();
                for (int index = 0; index < changes.length(); index++) {
                    receiptIds.add(changes.getJSONObject(index).getString("id"));
                }
                if (receiptIds.size() != changes.length()
                        || !receiptIds.equals(new java.util.LinkedHashSet<>(objectIds))) {
                    throw new JSONException("AI 修改凭据对象不匹配");
                }
            }
            JSONObject adoption = json.optJSONObject("adoption");
            RectF anchor = null;
            String adoptionId = "";
            String sourceDigest = "";
            String pdfDigest = "";
            String permission = "";
            boolean adopted = false;
            if (adoption != null) {
                adoptionId = bounded(adoption.getString("id"), 160);
                sourceDigest = bounded(adoption.getString("sourceDigest"), 128);
                pdfDigest = bounded(adoption.optString("pdfDigest"), 128);
                permission = bounded(adoption.getString("permission"), 64);
                anchor = new RectF(finite(adoption, "left"), finite(adoption, "top"),
                        finite(adoption, "right"), finite(adoption, "bottom"));
                if (anchor.isEmpty()) throw new JSONException("AI 回答采用位置无效");
                adopted = adoption.optBoolean("adopted", false);
            }
            return new VisibleEntry(bounded(json.optString("kind", "message"), 32),
                    bounded(json.optString("role", "assistant"), 32),
                    bounded(json.getString("text"), 512 * 1024),
                    bounded(json.optString("executorLabel"), 512),
                    json.optBoolean("error", false), objectIds,
                    receiptDigest, receiptJson, adoptionId, sourceDigest, pdfDigest,
                    permission, anchor, adopted);
        }

        VisibleEntry withAdoption(String id, String sourceDigest, String pdfDigest,
                                  String permission, RectF anchor) {
            return new VisibleEntry(kind, role, text, executorLabel, error,
                    receiptObjectIds, receiptDigest, receiptJson, id, sourceDigest,
                    pdfDigest, permission, anchor, false);
        }

        VisibleEntry asAdopted() {
            return new VisibleEntry(kind, role, text, executorLabel, error,
                    receiptObjectIds, receiptDigest, receiptJson, adoptionId,
                    adoptionSourceDigest, adoptionPdfDigest, adoptionPermission,
                    adoptionAnchor, true);
        }
    }

    static final class Selection {
        final byte[] pngBytes;
        final RectF sourceBounds;
        final int strokeCount;
        final int pointCount;
        final boolean lassoMaskApplied;

        Selection(byte[] pngBytes, RectF sourceBounds, int strokeCount, int pointCount,
                  boolean lassoMaskApplied) {
            this.pngBytes = pngBytes == null ? new byte[0] : pngBytes.clone();
            this.sourceBounds = sourceBounds == null ? new RectF() : new RectF(sourceBounds);
            this.strokeCount = strokeCount;
            this.pointCount = pointCount;
            this.lassoMaskApplied = lassoMaskApplied;
        }

        static Selection fromCanvas(NoteCanvasView.AiSelectionSnapshot value) {
            return value == null ? null : new Selection(value.pngBytes, value.sourceBounds,
                    value.strokeCount, value.pointCount, value.lassoMaskApplied);
        }

        NoteCanvasView.AiSelectionSnapshot toCanvas() throws JSONException {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.length, bounds);
            if (!validImageBounds(bounds.outWidth, bounds.outHeight)) {
                throw new JSONException("AI 圈选图片尺寸无效");
            }
            Bitmap bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.length);
            if (bitmap == null) throw new JSONException("AI 圈选图片无法解码");
            return new NoteCanvasView.AiSelectionSnapshot(bitmap, pngBytes.clone(),
                    new RectF(sourceBounds), strokeCount, pointCount, lassoMaskApplied);
        }

        JSONObject toJson() throws JSONException {
            if (pngBytes.length == 0 || pngBytes.length > MAX_SELECTION_BYTES) {
                throw new JSONException("AI 圈选图片超过大小限制");
            }
            return new JSONObject()
                    .put("png", Base64.encodeToString(pngBytes, Base64.NO_WRAP))
                    .put("left", sourceBounds.left).put("top", sourceBounds.top)
                    .put("right", sourceBounds.right).put("bottom", sourceBounds.bottom)
                    .put("strokeCount", strokeCount).put("pointCount", pointCount)
                    .put("lassoMaskApplied", lassoMaskApplied);
        }

        static Selection fromJson(JSONObject json) throws JSONException {
            byte[] png;
            try { png = Base64.decode(json.getString("png"), Base64.DEFAULT); }
            catch (IllegalArgumentException invalid) { throw new JSONException("AI 圈选图片编码无效"); }
            if (png.length == 0 || png.length > MAX_SELECTION_BYTES) {
                throw new JSONException("AI 圈选图片超过大小限制");
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
            if (!validImageBounds(bounds.outWidth, bounds.outHeight)) {
                throw new JSONException("AI 圈选图片尺寸无效");
            }
            RectF rect = new RectF(finite(json, "left"), finite(json, "top"),
                    finite(json, "right"), finite(json, "bottom"));
            if (rect.isEmpty()) throw new JSONException("AI 圈选范围无效");
            return new Selection(png, rect, Math.max(0, json.optInt("strokeCount")),
                    Math.max(0, json.optInt("pointCount")),
                    json.optBoolean("lassoMaskApplied", false));
        }
    }

    static final class Binding {
        final String semanticDigest;
        final String pdfDigest;
        final String profileId;
        final long profileRevision;
        final String permission;
        final String materialDigest;

        Binding(String semanticDigest, String pdfDigest, String profileId,
                long profileRevision, String permission, String materialDigest) {
            this.semanticDigest = safe(semanticDigest);
            this.pdfDigest = safe(pdfDigest);
            this.profileId = safe(profileId);
            this.profileRevision = Math.max(0, profileRevision);
            this.permission = safe(permission);
            this.materialDigest = safe(materialDigest);
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("semanticDigest", semanticDigest)
                    .put("pdfDigest", pdfDigest).put("profileId", profileId)
                    .put("profileRevision", profileRevision).put("permission", permission)
                    .put("materialDigest", materialDigest);
        }

        static Binding fromJson(JSONObject json) throws JSONException {
            long profileRevision = json.getLong("profileRevision");
            if (profileRevision < 0 || profileRevision > MAX_COUNTER) {
                throw new JSONException("AI 配置修订无效");
            }
            return new Binding(bounded(json.getString("semanticDigest"), 128),
                    bounded(json.optString("pdfDigest"), 128),
                    bounded(json.getString("profileId"), 160),
                    profileRevision,
                    bounded(json.getString("permission"), 64),
                    bounded(json.optString("materialDigest"), 128));
        }
    }

    static final class Snapshot {
        final String noteId;
        final String conversationId;
        final long generation;
        final long revision;
        final long updatedAt;
        final List<VisibleEntry> visibleTimeline;
        final List<OpenAiCompatibleClient.Message> wireHistory;
        final Selection selection;
        final AiVaultSnapshot vault;
        final Binding binding;
        final String transcript;
        final boolean uploadConfirmed;

        Snapshot(String noteId, String conversationId, long generation, long revision,
                 long updatedAt, List<VisibleEntry> visibleTimeline,
                 List<OpenAiCompatibleClient.Message> wireHistory, Selection selection,
                 AiVaultSnapshot vault, Binding binding, String transcript,
                 boolean uploadConfirmed) {
            this.noteId = noteId;
            this.conversationId = conversationId;
            this.generation = generation;
            this.revision = revision;
            this.updatedAt = updatedAt;
            this.visibleTimeline = immutableCopy(visibleTimeline);
            this.wireHistory = immutableCopy(wireHistory);
            this.selection = selection;
            this.vault = vault;
            this.binding = binding;
            this.transcript = transcript;
            this.uploadConfirmed = uploadConfirmed;
        }

        Snapshot next(List<VisibleEntry> visible, List<OpenAiCompatibleClient.Message> wire,
                      Selection nextSelection, AiVaultSnapshot nextVault, Binding nextBinding,
                      String nextTranscript, boolean confirmed) {
            if (revision >= MAX_COUNTER) throw new IllegalStateException("AI 会话修订已达上限");
            return new Snapshot(noteId, conversationId, generation, revision + 1,
                    System.currentTimeMillis(), visible, wire, nextSelection, nextVault,
                    nextBinding, nextTranscript, confirmed);
        }

        JSONObject toJson() throws Exception {
            return toJsonInternal(true);
        }

        /** Credential-free export of an in-memory result that exceeded persistence limits. */
        JSONObject toRecoveryJson() throws Exception {
            return toJsonInternal(false).put("recoveryExport", true);
        }

        private JSONObject toJsonInternal(boolean enforceConversationBudget) throws Exception {
            if (enforceConversationBudget
                    && visibleTimeline.size() + wireHistory.size() > MAX_TIMELINE_ENTRIES) {
                throw new JSONException("AI 会话消息数量超过限制");
            }
            JSONObject json = new JSONObject().put("schemaVersion", SCHEMA_VERSION)
                    .put("noteId", noteId).put("conversationId", conversationId)
                    .put("generation", generation).put("revision", revision)
                    .put("updatedAt", updatedAt).put("binding", binding.toJson())
                    .put("uploadConfirmed", uploadConfirmed);
            JSONArray visible = new JSONArray();
            for (VisibleEntry entry : visibleTimeline) visible.put(entry.toJson());
            JSONArray wire = new JSONArray();
            for (OpenAiCompatibleClient.Message message : wireHistory) wire.put(messageToJson(message));
            long encodedConversationBytes = (long) visible.toString()
                    .getBytes(StandardCharsets.UTF_8).length
                    + wire.toString().getBytes(StandardCharsets.UTF_8).length
                    + (transcript == null ? 0
                    : JSONObject.quote(transcript).getBytes(StandardCharsets.UTF_8).length);
            if (enforceConversationBudget && encodedConversationBytes > MAX_TEXT_BYTES) {
                throw new JSONException("AI 会话文字超过大小限制");
            }
            json.put("visibleTimeline", visible).put("wireHistory", wire);
            if (selection != null) json.put("selection", selection.toJson());
            if (vault != null && !vault.isEmpty()) json.put("vault", vault.toJson());
            if (transcript != null) json.put("transcript", transcript);
            return json;
        }

        static Snapshot fromJson(JSONObject json) throws Exception {
            if (json.getInt("schemaVersion") != SCHEMA_VERSION) {
                throw new JSONException("不支持的 AI 会话版本");
            }
            JSONArray visibleValues = json.getJSONArray("visibleTimeline");
            JSONArray wireValues = json.getJSONArray("wireHistory");
            if (visibleValues.length() + wireValues.length() > MAX_TIMELINE_ENTRIES) {
                throw new JSONException("AI 会话消息数量超过限制");
            }
            String encodedTranscript = json.has("transcript")
                    ? JSONObject.quote(json.getString("transcript")) : "";
            long encodedConversationBytes = (long) visibleValues.toString()
                    .getBytes(StandardCharsets.UTF_8).length
                    + wireValues.toString().getBytes(StandardCharsets.UTF_8).length
                    + encodedTranscript.getBytes(StandardCharsets.UTF_8).length;
            if (encodedConversationBytes > MAX_TEXT_BYTES) {
                throw new JSONException("AI 会话文字超过大小限制");
            }
            List<VisibleEntry> visible = new ArrayList<>();
            List<OpenAiCompatibleClient.Message> wire = new ArrayList<>();
            int textBytes = 0;
            for (int index = 0; index < visibleValues.length(); index++) {
                VisibleEntry entry = VisibleEntry.fromJson(visibleValues.getJSONObject(index));
                textBytes = addBytes(textBytes, entry.text);
                visible.add(entry);
            }
            for (int index = 0; index < wireValues.length(); index++) {
                OpenAiCompatibleClient.Message message = messageFromJson(
                        wireValues.getJSONObject(index));
                textBytes = addBytes(textBytes, message.content);
                wire.add(message);
            }
            String transcript = json.has("transcript")
                    ? bounded(json.getString("transcript"), 512 * 1024) : null;
            if (transcript != null) textBytes = addBytes(textBytes, transcript);
            if (textBytes > MAX_TEXT_BYTES) throw new JSONException("AI 会话文字超过大小限制");
            long generation = json.getLong("generation");
            long revision = json.getLong("revision");
            if (generation < 1 || generation > MAX_COUNTER
                    || revision < 1 || revision > MAX_COUNTER) {
                throw new JSONException("AI 会话计数器无效");
            }
            return new Snapshot(bounded(json.getString("noteId"), 160),
                    bounded(json.getString("conversationId"), 160),
                    generation, revision, json.optLong("updatedAt", 0),
                    visible, wire,
                    json.has("selection") ? Selection.fromJson(json.getJSONObject("selection")) : null,
                    json.has("vault") ? AiVaultSnapshot.fromJson(json.getJSONObject("vault")) : null,
                    Binding.fromJson(json.getJSONObject("binding")), transcript,
                    json.optBoolean("uploadConfirmed", false));
        }
    }

    private final File directory;
    private final NoteStore.WriteFaultInjector faults;

    AiConversationStore(Context context) {
        this(new File(context.getFilesDir(), "ai-conversations"), NoteStore.NO_WRITE_FAULTS);
    }

    AiConversationStore(File directory, NoteStore.WriteFaultInjector faults) {
        this.directory = directory;
        this.faults = faults == null ? NoteStore.NO_WRITE_FAULTS : faults;
    }

    Snapshot create(String noteId, List<VisibleEntry> visible,
                    List<OpenAiCompatibleClient.Message> wire, Selection selection,
                    AiVaultSnapshot vault, Binding binding, String transcript,
                    boolean uploadConfirmed) throws Exception {
        synchronized (GLOBAL_LOCK) {
            validateId(noteId);
            File target = sessionFile(noteId);
            if (familyExists(target)) {
                throw new IOException("已有 AI 会话或待恢复副本，拒绝覆盖");
            }
            long cleared = clearedGeneration(noteId);
            if (cleared >= MAX_COUNTER) throw new IOException("AI 会话代次已达上限");
            long generation = cleared + 1;
            Snapshot value = new Snapshot(noteId, "conversation-" + UUID.randomUUID(),
                    generation, 1, System.currentTimeMillis(), visible, wire, selection,
                    vault, binding, transcript, uploadConfirmed);
            write(value);
            return value;
        }
    }

    Snapshot save(Snapshot value) throws Exception {
        synchronized (GLOBAL_LOCK) {
            validate(value);
            if (value.generation <= clearedGeneration(value.noteId)) {
                throw new IOException("AI 会话已清空，拒绝晚到结果");
            }
            File target = sessionFile(value.noteId);
            NoteStore.recoverAtomicFile(target, AiConversationStore::validateJson);
            if (!target.isFile()) throw new IOException("AI 会话已不存在");
            Snapshot current = read(target);
            if (!current.conversationId.equals(value.conversationId)
                    || current.generation != value.generation
                    || value.revision <= current.revision) {
                throw new IOException("AI 会话修订冲突");
            }
            write(value);
            return value;
        }
    }

    Snapshot load(String noteId) throws Exception {
        synchronized (GLOBAL_LOCK) {
            validateId(noteId);
            File target = sessionFile(noteId);
            NoteStore.recoverAtomicFile(target, AiConversationStore::validateJson);
            if (!target.isFile()) return null;
            Snapshot value = read(target);
            if (!noteId.equals(value.noteId)) {
                throw new IOException("AI 会话笔记绑定不匹配");
            }
            if (value.generation <= clearedGeneration(noteId)) return null;
            return value;
        }
    }

    void clear(String noteId) throws Exception {
        synchronized (GLOBAL_LOCK) {
            validateId(noteId);
            long cleared;
            try { cleared = clearedGeneration(noteId); }
            catch (Exception corruptTombstone) {
                // Explicit clear is the one operation authorized to discard a
                // damaged deletion marker. The session family is still removed
                // only after the replacement marker is durable.
                deleteFamily(tombstoneFile(noteId));
                cleared = 0;
            }
            long base = Math.max(Math.max(cleared, generationOnDisk(noteId)),
                    System.currentTimeMillis());
            if (base >= MAX_COUNTER) throw new IOException("AI 会话代次已达上限");
            long generation = base + 1;
            JSONObject tombstone = new JSONObject().put("schemaVersion", 1)
                    .put("noteId", noteId).put("generation", generation)
                    .put("clearedAt", System.currentTimeMillis());
            NoteStore.writeAtomic(tombstoneFile(noteId), tombstone.toString(), value -> {
                JSONObject json = new JSONObject(value);
                long storedGeneration = json.getLong("generation");
                if (!noteId.equals(json.getString("noteId")) || storedGeneration < 1
                        || storedGeneration > MAX_COUNTER) {
                    throw new JSONException("AI 会话删除标记无效");
                }
            }, faults, 16 * 1024);
            deleteFamily(sessionFile(noteId));
        }
    }

    File recoveryFile(String noteId) throws Exception {
        synchronized (GLOBAL_LOCK) {
            validateId(noteId);
            ensureDirectory();
            File[] files = directory.listFiles();
            if (files == null) return null;
            String prefix = noteId + ".json";
            File newest = null;
            for (File file : files) {
                if (!file.isFile() || !file.getName().startsWith(prefix)) continue;
                if (newest == null || file.lastModified() > newest.lastModified()) newest = file;
            }
            return newest;
        }
    }

    void preserveUnsaved(Snapshot value) throws Exception {
        synchronized (GLOBAL_LOCK) {
            validateId(value.noteId);
            if (value.generation <= clearedGeneration(value.noteId)) {
                throw new IOException("AI 会话已清空，拒绝保留晚到副本");
            }
            String encoded = value.toJson().toString();
            NoteStore.writeAtomic(new File(sessionFile(value.noteId).getParentFile(),
                            value.noteId + ".json.unsaved"), encoded,
                    AiConversationStore::validateJson, faults, MAX_FILE_BYTES);
        }
    }

    private void write(Snapshot value) throws Exception {
        validate(value);
        String encoded = value.toJson().toString();
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
            throw new IOException("AI 会话超过 8 MiB，未覆盖上一份可读记录");
        }
        NoteStore.writeAtomic(sessionFile(value.noteId), encoded,
                AiConversationStore::validateJson, faults, MAX_FILE_BYTES);
    }

    private Snapshot read(File file) throws Exception {
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            return Snapshot.fromJson(new JSONObject(readLimited(input, MAX_FILE_BYTES)));
        }
    }

    private long generationOnDisk(String noteId) {
        try {
            File file = sessionFile(noteId);
            return file.isFile() ? read(file).generation : 0;
        } catch (Exception ignored) { return 0; }
    }

    private long clearedGeneration(String noteId) throws Exception {
        try {
            File file = tombstoneFile(noteId);
            NoteStore.recoverAtomicFile(file, value -> {
                JSONObject json = new JSONObject(value);
                if (!noteId.equals(json.getString("noteId"))
                        || json.getLong("generation") < 1
                        || json.getLong("generation") > MAX_COUNTER) {
                    throw new JSONException("AI 会话删除标记无效");
                }
            });
            if (!file.isFile()) return 0;
            String value;
            try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
                value = readLimited(input, 16 * 1024);
            }
            JSONObject json = new JSONObject(value);
            long generation = json.optLong("generation", 0);
            if (!noteId.equals(json.optString("noteId")) || generation < 1
                    || generation > MAX_COUNTER) {
                throw new IOException("AI 会话删除标记无效");
            }
            return generation;
        } catch (Exception invalid) {
            throw new IOException("AI 会话删除标记损坏，原文件已保留", invalid);
        }
    }

    private File sessionFile(String noteId) throws IOException {
        ensureDirectory();
        return new File(directory, noteId + ".json");
    }

    private File tombstoneFile(String noteId) throws IOException {
        ensureDirectory();
        return new File(directory, noteId + ".cleared.json");
    }

    private void ensureDirectory() throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建 AI 会话目录");
        }
    }

    private static void deleteFamily(File target) throws IOException {
        File[] files = target.getParentFile().listFiles();
        if (files == null) return;
        String prefix = target.getName();
        for (File file : files) {
            if ((file.getName().equals(prefix) || file.getName().startsWith(prefix + "."))
                    && !file.delete()) throw new IOException("无法清理 AI 会话文件");
        }
    }

    private static boolean familyExists(File target) {
        File[] files = target.getParentFile().listFiles();
        if (files == null) return false;
        String prefix = target.getName();
        for (File file : files) {
            if (file.isFile() && (file.getName().equals(prefix)
                    || file.getName().startsWith(prefix + "."))) return true;
        }
        return false;
    }

    private static void validateJson(String value) throws Exception {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
            throw new IOException("AI 会话超过大小限制");
        }
        validate(Snapshot.fromJson(new JSONObject(value)));
    }

    private static void validate(Snapshot value) throws Exception {
        validateId(value.noteId);
        if (value.conversationId == null || value.conversationId.length() > 160
                || !value.conversationId.startsWith("conversation-")
                || value.generation < 1 || value.generation > MAX_COUNTER
                || value.revision < 1 || value.revision > MAX_COUNTER
                || value.binding == null
                || value.visibleTimeline.size() + value.wireHistory.size()
                > MAX_TIMELINE_ENTRIES) {
            throw new JSONException("AI 会话元数据无效");
        }
        // Validate the final encoded representation, including base64 and JSON escaping.
        String encoded = value.toJson().toString();
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
            throw new IOException("AI 会话超过大小限制");
        }
    }

    private static JSONObject messageToJson(OpenAiCompatibleClient.Message message)
            throws JSONException {
        JSONObject json = new JSONObject().put("role", message.role)
                .put("content", message.content == null ? "" : message.content);
        if (message.toolCallId != null) json.put("toolCallId", message.toolCallId);
        JSONArray calls = new JSONArray();
        for (OpenAiCompatibleClient.ToolCall call : message.toolCalls) {
            calls.put(new JSONObject().put("id", call.id).put("name", call.name)
                    .put("arguments", call.arguments));
        }
        if (calls.length() > 0) json.put("toolCalls", calls);
        return json;
    }

    private static OpenAiCompatibleClient.Message messageFromJson(JSONObject json)
            throws JSONException {
        String role = bounded(json.getString("role"), 32);
        if (!role.equals("user") && !role.equals("assistant") && !role.equals("tool")) {
            throw new JSONException("AI 会话角色无效");
        }
        String content = bounded(json.optString("content"), 512 * 1024);
        JSONArray values = json.optJSONArray("toolCalls");
        List<OpenAiCompatibleClient.ToolCall> calls = new ArrayList<>();
        if (values != null) {
            if (values.length() > 8) throw new JSONException("工具调用数量无效");
            for (int index = 0; index < values.length(); index++) {
                JSONObject call = values.getJSONObject(index);
                calls.add(new OpenAiCompatibleClient.ToolCall(
                        bounded(call.getString("id"), 256),
                        bounded(call.getString("name"), 128), call.getJSONObject("arguments")));
            }
        }
        String toolCallId = json.has("toolCallId")
                ? bounded(json.getString("toolCallId"), 256) : null;
        return new OpenAiCompatibleClient.Message(role, content, calls, toolCallId);
    }

    private static int addBytes(int current, String value) throws JSONException {
        int added = value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
        if (current > MAX_TEXT_BYTES - added) throw new JSONException("AI 会话文字超过大小限制");
        return current + added;
    }

    private static String bounded(String value, int maxBytes) throws JSONException {
        String safe = value == null ? "" : value;
        if (safe.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new JSONException("AI 会话字段超过大小限制");
        }
        return safe;
    }

    private static float finite(JSONObject json, String key) throws JSONException {
        double value = json.getDouble(key);
        float result = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(result)) {
            throw new JSONException("AI 圈选范围无效");
        }
        return result;
    }

    private static boolean validImageBounds(int width, int height) {
        return width > 0 && height > 0 && width <= 2048 && height <= 2048
                && (long) width * height <= 4_000_000L;
    }

    private static String sha256(String value) throws JSONException {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : bytes) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (Exception error) {
            throw new JSONException("无法校验 AI 修改凭据");
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static String readLimited(java.io.InputStream input, int maximumBytes)
            throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (total > maximumBytes - count) throw new IOException("AI 会话文件超过大小限制");
            output.write(buffer, 0, count);
            total += count;
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return Collections.unmodifiableList(values == null
                ? new ArrayList<>() : new ArrayList<>(values));
    }

    private static void validateId(String noteId) {
        if (noteId == null || !noteId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("非法笔记 ID");
        }
    }
}
