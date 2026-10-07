package com.padnote.android;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
        List<Entry> entries = new ArrayList<>();
        for (Entry entry : entriesFromIndex(index)) {
            try {
                RestoreOwner owner = readRestoreOwner(context, entry.id);
                if ((owner == null || "committed".equals(owner.state)) && restoreGroupVisible(context, entry.id)) entries.add(entry);
            } catch (Exception invalidRestoreMarker) {
                // Keep an untrusted imported row out of the shelf without blocking valid notes.
                // listRecovery() reports the affected note; its files and marker remain untouched.
            }
        }
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
        writeAtomic(noteFile(context, id), NoteJsonCodec.stringify(document), NOTE_VALIDATOR);
        Entry entry = new Entry(id, title, now, 0, 1);
        upsertEntry(index, entry);
        saveIndex(context, index);
        return entry;
    }

    static synchronized JSONObject load(Context context, String noteId) throws Exception {
        RestoreOwner owner = readRestoreOwner(context, noteId);
        if ((owner != null && "pending".equals(owner.state)) || !restoreGroupVisible(context,noteId))
            throw new IllegalStateException("RESTORE_NOTE_PENDING");
        File file = noteFile(context, noteId);
        recoverAtomicFile(file, NOTE_VALIDATOR);
        if (!file.exists()) throw new IllegalStateException("笔记文件不存在");
        String value = readFile(file);
        NOTE_VALIDATOR.validate(value);
        return new JSONObject(value);
    }

    static synchronized Entry save(Context context, String noteId, String requestedTitle,
                                   String documentJson) throws Exception {
        requireNotPendingRestore(context, noteId);
        requireWithinLimit(documentJson);
        JSONObject document = new JSONObject(documentJson);
        validateDocument(document);
        String title = normalizeTitle(requestedTitle,
                normalizeTitle(document.optString("title"), "未命名笔记"));
        long now = System.currentTimeMillis();
        document.put("id", noteId);
        document.put("title", title);
        document.put("updatedAt", now);
        String storedValue = NoteJsonCodec.stringify(document);
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
        requireNotPendingRestore(context, noteId);
        JSONObject document = load(context, noteId);
        String title = normalizeTitle(requestedTitle, "未命名笔记");
        long now = System.currentTimeMillis();
        document.put("title", title);
        document.put("updatedAt", now);
        writeAtomic(noteFile(context, noteId), NoteJsonCodec.stringify(document), NOTE_VALIDATOR);

        JSONObject index = loadIndex(context);
        Entry entry = new Entry(noteId, title, now,
                document.optJSONArray("strokes") == null ? 0 : document.getJSONArray("strokes").length(),
                Math.max(1, document.optInt("pageCount", 1)));
        upsertEntry(index, entry);
        saveIndex(context, index);
        return entry;
    }

    static synchronized void delete(Context context, String noteId) throws Exception {
        requireNotPendingRestore(context, noteId);
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
        File owner = restoreOwnerFile(context, noteId);
        if (owner.exists()) { Os.remove(owner.getAbsolutePath()); syncParent(owner); }
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
            writeAtomic(noteFile(context, id), NoteJsonCodec.stringify(imported), NOTE_VALIDATOR);
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

    /** Imports a validated archive copy under a pre-journaled local ID for idempotent restore. */
    static synchronized Entry restoreDocumentWithId(Context context, JSONObject imported,
                                                     String fallbackTitle, File pdfSource,
                                                     String localId) throws Exception {
        return restoreDocumentWithId(context, imported, fallbackTitle, pdfSource, localId, false);
    }

    static synchronized Entry restoreDocumentWithId(Context context, JSONObject imported,
                                                     String fallbackTitle, File pdfSource,
                                                     String localId, boolean allowIdenticalPromotedPdf) throws Exception {
        validateNoteId(localId);
        validateDocument(imported);
        if (imported.optInt("pdfPageCount", 0) > 0 && pdfSource == null)
            throw new IllegalArgumentException("PDF 笔记缺少已验证原文");
        JSONObject index = loadIndex(context);
        migrateLegacyIfNeeded(context, index);
        for (Entry entry : list(context)) if (entry.id.equals(localId))
            throw new IOException("RESTORE_TARGET_EXISTS");
        File target = noteFile(context, localId);
        if (target.exists()) throw new IOException("RESTORE_TARGET_EXISTS");
        File targetPdf=pdfFile(context,localId);
        if (targetPdf.exists() && !(allowIdenticalPromotedPdf && pdfSource!=null && sameFileBytes(targetPdf,pdfSource)))
            throw new IOException("RESTORE_TARGET_EXISTS");
        JSONObject restored = prepareRestoredDocument(imported, fallbackTitle, localId,
                System.currentTimeMillis());
        return restorePreparedDocument(context, restored, pdfSource, allowIdenticalPromotedPdf,
                null, null, null, null, null);
    }

    static synchronized JSONObject prepareRestoredDocument(JSONObject imported, String fallbackTitle,
                                                             String localId, long updatedAt) throws Exception {
        validateNoteId(localId);
        validateDocument(imported);
        JSONObject restored = new JSONObject(NoteJsonCodec.stringify(imported));
        String title = normalizeTitle(restored.optString("title"), normalizeTitle(fallbackTitle, "导入的笔记"));
        restored.put("id", localId).put("title", title).put("updatedAt", updatedAt);
        validateDocument(restored);
        requireWithinLimit(restored.toString());
        return restored;
    }

    static synchronized Entry restorePreparedDocument(Context context, JSONObject restored,
            File pdfSource, boolean allowIdenticalPromotedPdf, String transactionId, String groupId,
            String expectedNoteSha, String expectedPdfSha, String expectedCoverSha) throws Exception {
        String localId = restored.optString("id", "");
        validateNoteId(localId);
        validateDocument(restored);
        byte[] noteBytes = NoteJsonCodec.stringify(restored).getBytes(StandardCharsets.UTF_8);
        String noteSha = sha256(noteBytes);
        if (expectedNoteSha != null && !expectedNoteSha.equals(noteSha)) throw new IOException("RESTORE_NOTE_HASH_MISMATCH");
        JSONArray strokes = restored.getJSONArray("strokes");
        String title = restored.optString("title", "导入的笔记");
        long now = restored.getLong("updatedAt");
        if (transactionId != null) requireRestoreOwner(context, transactionId, groupId, localId,
                noteSha, now, expectedPdfSha, expectedCoverSha, "pending");
        JSONObject index = loadIndex(context);
        migrateLegacyIfNeeded(context, index);
        File target = noteFile(context, localId);
        File targetPdf = pdfFile(context, localId);
        boolean existingNote = target.exists();
        if (existingNote) {
            if (!allowIdenticalPromotedPdf || !fileHasSha(target, noteSha)) throw new IOException("RESTORE_TARGET_EXISTS");
        } else {
            for (Entry entry : list(context)) if (entry.id.equals(localId)) throw new IOException("RESTORE_TARGET_EXISTS");
        }
        if (targetPdf.exists() && !(allowIdenticalPromotedPdf && pdfSource != null && sameFileBytes(targetPdf,pdfSource)))
            throw new IOException("RESTORE_TARGET_EXISTS");
        boolean pdfCreated = false;
        try {
            if (pdfSource != null && !targetPdf.exists()) {
                try (InputStream input = new FileInputStream(pdfSource);
                     FileOutputStream output = new FileOutputStream(pdfFile(context, localId), false)) {
                    PdfNoteIO.copy(input, output, PdfNoteIO.MAX_PDF_BYTES);
                    output.flush(); output.getFD().sync(); pdfCreated = true;
                }
            }
            if (!existingNote) writeAtomic(target, new String(noteBytes, StandardCharsets.UTF_8), NOTE_VALIDATOR);
            Entry entry = new Entry(localId, title, now, strokes.length(),
                    Math.max(1, restored.optInt("pageCount", 1)));
            upsertEntry(index, entry);
            saveIndex(context, index);
            if (!fileHasSha(target, noteSha)) throw new IOException("RESTORE_NOTE_VERIFY_FAILED");
            return entry;
        } catch (Exception error) {
            if (pdfCreated) {
                File pdf = pdfFile(context, localId);
                if (pdf.exists() && !pdf.delete()) error.addSuppressed(new IOException("RESTORE_PDF_CLEANUP_FAILED"));
            }
            throw error;
        }
    }

    static synchronized void beginRestoreOwnership(Context context, String transactionId, String groupId,
            String localId, String noteSha, long updatedAt, String pdfSha, String coverSha) throws Exception {
        validateNoteId(localId);
        if (transactionId == null || !transactionId.matches("[0-9a-fA-F-]{36}") ||
                groupId == null || !groupId.matches("i-[0-9a-f]{32}") ||
                !isSha256(noteSha) || (pdfSha != null && !isSha256(pdfSha)) ||
                (coverSha != null && !isSha256(coverSha)) || updatedAt <= 0)
            throw new IOException("RESTORE_OWNER_INVALID");
        File note = noteFile(context, localId), pdf = pdfFile(context, localId);
        File marker = restoreOwnerFile(context, localId);
        JSONObject index = loadIndex(context);
        for (Entry entry : entriesFromIndex(index)) if (entry.id.equals(localId)) throw new IOException("RESTORE_TARGET_EXISTS");
        if (note.exists() || pdf.exists() || marker.exists() || coverSha != null && CoverStore.coverFile(note.getParentFile(), localId).exists())
            throw new IOException("RESTORE_TARGET_EXISTS");
        JSONObject value = restoreOwnerJson(transactionId, groupId, localId, noteSha, updatedAt, pdfSha, coverSha, "pending");
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        FileDescriptor fd = Os.open(marker.getAbsolutePath(), OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW, 0600);
        try (FileOutputStream out = new FileOutputStream(fd)) { out.write(bytes); out.flush(); out.getFD().sync(); }
        syncParent(marker);
    }

    /** Adds the v2 group visibility reference before the v1-compatible note owner reservation. */
    static synchronized void beginRestoreOwnershipV2(Context context, String transactionId, String sourceGroupId,
            String groupId, String memberId, String localId, String noteSha, long updatedAt,
            String pdfSha, String coverSha, String sourcePayloadSha, String semanticBindingSha,
            String storageBindingSha) throws Exception {
        validateNoteId(localId);
        if(groupId==null||!groupId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||
                memberId==null||!memberId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IOException("RESTORE_V2_OWNER_INVALID");
        requireHashValue(sourcePayloadSha);requireHashValue(semanticBindingSha);requireHashValue(storageBindingSha);
        File marker=restoreV2ReferenceFile(context,localId);byte[] expected=restoreV2ReferenceBytes(transactionId,groupId,memberId,localId,sourcePayloadSha,semanticBindingSha,storageBindingSha);
        StructStat existing=lstatOrNull(marker);
        if(existing==null){
            ensureRestoreTargetUnused(context,localId);
            FileDescriptor fd=Os.open(marker.getAbsolutePath(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600);
            try(FileOutputStream out=new FileOutputStream(fd)){out.write(expected);out.flush();out.getFD().sync();}syncParent(marker);
        } else {
            byte[] actual=readNoFollow(marker,4096);if(!MessageDigest.isEqual(sha256(expected).getBytes(StandardCharsets.US_ASCII),sha256(actual).getBytes(StandardCharsets.US_ASCII)))throw new IOException("RESTORE_V2_OWNER_CHANGED");
        }
        String old=restoreOwnerState(context,localId);
        if(old==null)beginRestoreOwnership(context,transactionId,sourceGroupId,localId,noteSha,updatedAt,pdfSha,coverSha);
        else requireRestoreOwner(context,transactionId,sourceGroupId,localId,noteSha,updatedAt,pdfSha,coverSha,old);
    }

    static synchronized void requireRestoreGroupV2Visible(Context context,String localId)throws Exception {
        if(!restoreGroupVisible(context,localId))throw new IllegalStateException("RESTORE_NOTE_PENDING");
    }
    static synchronized void verifyRestoreOwnershipV2(Context context,String tx,String sourceGroupId,String groupId,
            String memberId,String localId,String noteSha,long updatedAt,String pdfSha,String coverSha,
            String sourcePayloadSha,String semanticBindingSha,String storageBindingSha,String expectedState)throws Exception {
        File reference=restoreV2ReferenceFile(context,localId);
        byte[] expected=restoreV2ReferenceBytes(tx,groupId,memberId,localId,sourcePayloadSha,semanticBindingSha,storageBindingSha);
        if(!MessageDigest.isEqual(sha256(expected).getBytes(StandardCharsets.US_ASCII),
                sha256(readNoFollow(reference,4096)).getBytes(StandardCharsets.US_ASCII)))throw new IOException("RESTORE_V2_OWNER_CHANGED");
        requireRestoreOwner(context,tx,sourceGroupId,localId,noteSha,updatedAt,pdfSha,coverSha,expectedState);
    }
    static synchronized void verifyRestoreV2MemberAbsent(Context context,String localId,LibraryRestoreGroupV2.Kind kind)throws Exception {
        validateNoteId(localId);File note=noteFile(context,localId),pdf=pdfFile(context,localId),cover=CoverStore.coverFile(note.getParentFile(),localId);
        if(kind==LibraryRestoreGroupV2.Kind.NOTE){verifyRestoreV2Absent(context,localId);return;}
        File target=kind==LibraryRestoreGroupV2.Kind.PDF?pdf:kind==LibraryRestoreGroupV2.Kind.ASSIGNED_COVER?cover:null;
        if(target==null||lstatOrNull(target)!=null)throw new IOException("RESTORE_V2_RESIDUE");
    }
    static synchronized void requireNoteMaterialAccess(Context context,String localId)throws Exception {
        requireNotPendingRestore(context,localId);
    }

    static synchronized Entry restorePreparedDocumentV2(Context context,JSONObject restored,File pdfSource,
            String transactionId,String sourceGroupId,String localId,String noteSha,long updatedAt,
            String pdfSha,String coverSha)throws Exception {
        String state=restoreOwnerState(context,localId);
        if("pending".equals(state))return restorePreparedDocument(context,restored,pdfSource,true,transactionId,sourceGroupId,noteSha,pdfSha,coverSha);
        if(!"committed".equals(state))throw new IOException("RESTORE_V2_OWNER_MISSING");
        requireRestoreOwner(context,transactionId,sourceGroupId,localId,noteSha,updatedAt,pdfSha,coverSha,"committed");
        File note=noteFile(context,localId),pdf=pdfFile(context,localId);
        if(!fileHasSha(note,noteSha)||(pdfSha!=null&&!fileHasSha(pdf,pdfSha))||(pdfSha==null&&lstatOrNull(pdf)!=null))throw new IOException("RESTORE_V2_CONTENT_CHANGED");
        for(Entry entry:entriesFromIndex(loadIndex(context)))if(entry.id.equals(localId))return entry;
        throw new IOException("RESTORE_V2_INDEX_MISSING");
    }
    static synchronized void finishRestoreOwnershipV2(Context context,String tx,String sourceGroupId,String localId,
            String noteSha,long updatedAt,String pdfSha,String coverSha)throws Exception {
        finishRestoreOwnershipV2(context,tx,sourceGroupId,localId,noteSha,updatedAt,pdfSha,coverSha,null);
    }
    static synchronized void finishRestoreOwnershipV2(Context context,String tx,String sourceGroupId,String localId,
            String noteSha,long updatedAt,String pdfSha,String coverSha,RestoreCommitFault fault)throws Exception {
        String state=restoreOwnerState(context,localId);
        if("pending".equals(state))commitRestoreOwnership(context,tx,sourceGroupId,localId,noteSha,updatedAt,pdfSha,coverSha,fault);
        else requireRestoreOwner(context,tx,sourceGroupId,localId,noteSha,updatedAt,pdfSha,coverSha,"committed");
    }

    static synchronized void rollbackRestoreOwnershipV2(Context context,String transactionId,String sourceGroupId,
            String groupId,String memberId,String localId,String noteSha,long updatedAt,String pdfSha,String coverSha,
            String sourcePayloadSha,String semanticBindingSha,String storageBindingSha)throws Exception {
        File ref=restoreV2ReferenceFile(context,localId);byte[] expected=restoreV2ReferenceBytes(transactionId,groupId,memberId,localId,sourcePayloadSha,semanticBindingSha,storageBindingSha);
        byte[] actual=readNoFollow(ref,4096);if(!MessageDigest.isEqual(sha256(expected).getBytes(StandardCharsets.US_ASCII),sha256(actual).getBytes(StandardCharsets.US_ASCII)))throw new IOException("RESTORE_V2_OWNER_CHANGED");
        String ownerState=restoreOwnerState(context,localId);
        if(!"pending".equals(ownerState)&&!"committed".equals(ownerState))throw new IOException("RESTORE_V2_OWNER_CHANGED");
        rollbackOwnedRestore(context,transactionId,sourceGroupId,localId,noteSha,updatedAt,pdfSha,coverSha,ownerState);
        StructStat current=lstatOrNull(ref);if(current==null||!OsConstants.S_ISREG(current.st_mode)||current.st_nlink!=1||!MessageDigest.isEqual(sha256(expected).getBytes(StandardCharsets.US_ASCII),sha256(readNoFollow(ref,4096)).getBytes(StandardCharsets.US_ASCII)))throw new IOException("RESTORE_V2_OWNER_CHANGED");
        Os.remove(ref.getAbsolutePath());syncParent(ref);
    }
    static synchronized void verifyRestoreV2Absent(Context context,String localId)throws Exception {
        File note=noteFile(context,localId),pdf=pdfFile(context,localId),cover=CoverStore.coverFile(note.getParentFile(),localId);
        if(lstatOrNull(note)!=null||lstatOrNull(pdf)!=null||lstatOrNull(cover)!=null||lstatOrNull(restoreOwnerFile(context,localId))!=null||lstatOrNull(restoreV2ReferenceFile(context,localId))!=null)throw new IOException("RESTORE_V2_RESIDUE");
        for(Entry entry:entriesFromIndex(loadIndex(context)))if(entry.id.equals(localId))throw new IOException("RESTORE_V2_RESIDUE");
    }
    static synchronized boolean restoreV2ReferenceAbsent(Context context,String localId)throws Exception {
        return lstatOrNull(restoreV2ReferenceFile(context,localId))==null;
    }

    static synchronized String restoreOwnerState(Context context,String localId)throws Exception{
        RestoreOwner owner=readRestoreOwner(context,localId);return owner==null?null:owner.state;
    }

    static synchronized void ensureRestoreTargetUnused(Context context,String localId)throws Exception{
        validateNoteId(localId);File note=noteFile(context,localId),pdf=pdfFile(context,localId),owner=restoreOwnerFile(context,localId);
        File cover=CoverStore.coverFile(note.getParentFile(),localId);
        if(note.exists()||pdf.exists()||cover.exists()||owner.exists())throw new IOException("RESTORE_TARGET_EXISTS");
        for(Entry entry:entriesFromIndex(loadIndex(context)))if(entry.id.equals(localId))throw new IOException("RESTORE_TARGET_EXISTS");
    }

    static synchronized void requireRestoreOwner(Context context, String transactionId, String groupId,
            String localId, String noteSha, long updatedAt, String pdfSha, String coverSha, String state) throws Exception {
        RestoreOwner owner = readRestoreOwner(context, localId);
        if (owner == null || !owner.matches(transactionId, groupId, localId, noteSha, updatedAt, pdfSha, coverSha) ||
                !state.equals(owner.state)) throw new IOException("RESTORE_OWNER_MISMATCH");
    }

    static synchronized void commitRestoreOwnership(Context context, String transactionId, String groupId,
            String localId, String noteSha, long updatedAt, String pdfSha, String coverSha) throws Exception {
        commitRestoreOwnership(context, transactionId, groupId, localId, noteSha, updatedAt, pdfSha, coverSha, null);
    }

    interface RestoreCommitFault { void afterTemporarySynced() throws Exception; }

    static synchronized void commitRestoreOwnership(Context context, String transactionId, String groupId,
            String localId, String noteSha, long updatedAt, String pdfSha, String coverSha,
        RestoreCommitFault fault) throws Exception {
        StructStat pendingIdentity=requireExactRestoreOwner(context,transactionId,groupId,localId,noteSha,updatedAt,pdfSha,coverSha,"pending");
        if (!fileHasSha(noteFile(context, localId), noteSha)) throw new IOException("RESTORE_NOTE_HASH_MISMATCH");
        if (pdfSha != null && !fileHasSha(pdfFile(context, localId), pdfSha)) throw new IOException("RESTORE_PDF_HASH_MISMATCH");
        File cover = CoverStore.coverFile(noteFile(context, localId).getParentFile(), localId);
        if (coverSha != null && !fileHasSha(cover, coverSha)) throw new IOException("RESTORE_COVER_HASH_MISMATCH");
        JSONObject index = loadIndex(context); boolean found = false;
        for (Entry entry : entriesFromIndex(index)) if (entry.id.equals(localId)) { found = true; break; }
        if (!found) throw new IOException("RESTORE_INDEX_MISSING");
        File marker = restoreOwnerFile(context, localId), temp = new File(marker.getParentFile(), marker.getName()+".tmp");
        StructStat tempStat = lstatOrNull(temp);
        StructStat publishIdentity;
        if (tempStat == null) {
            FileDescriptor fd = Os.open(temp.getAbsolutePath(), OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW, 0600);
            try (FileOutputStream out = new FileOutputStream(fd)) {
                out.write(restoreOwnerJson(transactionId,groupId,localId,noteSha,updatedAt,pdfSha,coverSha,"committed").toString().getBytes(StandardCharsets.UTF_8));
                out.flush(); out.getFD().sync();
            }
            syncParent(temp);
            publishIdentity=Os.lstat(temp.getAbsolutePath());
            if(!OsConstants.S_ISREG(publishIdentity.st_mode)||publishIdentity.st_nlink!=1||publishIdentity.st_size>4096)
                throw new IOException("RESTORE_OWNER_TEMP_INVALID");
            if (fault != null) fault.afterTemporarySynced();
        } else {
            publishIdentity=verifyCommittedOwnerTemp(temp,transactionId,groupId,localId,noteSha,updatedAt,pdfSha,coverSha);
        }
        try(LibraryBackupArchive.SafeFiles.PublishLock ignored=new LibraryBackupArchive.AndroidSafeFiles().lockPublish(marker)){
            StructStat finalTemp=verifyCommittedOwnerTemp(temp,transactionId,groupId,localId,noteSha,updatedAt,pdfSha,coverSha);
            if(!sameFileStat(publishIdentity,finalTemp))throw new IOException("RESTORE_OWNER_TEMP_CHANGED");
            StructStat finalPendingIdentity;
            try{finalPendingIdentity=requireExactRestoreOwner(context,transactionId,groupId,localId,noteSha,updatedAt,pdfSha,coverSha,"pending");}
            catch(Exception changed){throw new IOException("RESTORE_OWNER_CHANGED",changed);}
            if(!sameFileStat(pendingIdentity,finalPendingIdentity))throw new IOException("RESTORE_OWNER_CHANGED");
            if (!fileHasSha(noteFile(context, localId), noteSha)) throw new IOException("RESTORE_NOTE_HASH_MISMATCH");
            if (pdfSha != null && !fileHasSha(pdfFile(context, localId), pdfSha)) throw new IOException("RESTORE_PDF_HASH_MISMATCH");
            if (coverSha != null && !fileHasSha(cover, coverSha)) throw new IOException("RESTORE_COVER_HASH_MISMATCH");
            Os.rename(temp.getAbsolutePath(), marker.getAbsolutePath()); syncParent(marker);
        }
    }

    static synchronized void rollbackPendingRestore(Context context, String transactionId, String groupId,
            String localId, String noteSha, long updatedAt, String pdfSha, String coverSha) throws Exception {
        rollbackOwnedRestore(context,transactionId,groupId,localId,noteSha,updatedAt,pdfSha,coverSha,"pending");
    }
    private static synchronized void rollbackOwnedRestore(Context context, String transactionId, String groupId,
            String localId, String noteSha, long updatedAt, String pdfSha, String coverSha,String ownerState) throws Exception {
        File ownerMarker=restoreOwnerFile(context,localId);
        try(LibraryBackupArchive.SafeFiles.PublishLock ignored=new LibraryBackupArchive.AndroidSafeFiles().lockPublish(ownerMarker)){
        StructStat ownerIdentity=requireExactRestoreOwner(context,transactionId,groupId,localId,noteSha,updatedAt,pdfSha,coverSha,ownerState);
        File ownerTemp=new File(ownerMarker.getParentFile(),localId+".restore-owner.tmp");
        StructStat ownerTempStat=lstatOrNull(ownerTemp);
        if(ownerTempStat!=null){
            StructStat verified=verifyCommittedOwnerTemp(ownerTemp,transactionId,groupId,localId,noteSha,updatedAt,pdfSha,coverSha);
            StructStat current=Os.lstat(ownerTemp.getAbsolutePath());
            if(!sameFileStat(verified,current))throw new IOException("RESTORE_OWNER_TEMP_CHANGED");
            Os.remove(ownerTemp.getAbsolutePath());syncParent(ownerTemp);
        }
        if(!sameFileStat(ownerIdentity,requireExactRestoreOwner(context,transactionId,groupId,localId,noteSha,updatedAt,pdfSha,coverSha,ownerState)))
            throw new IOException("RESTORE_OWNER_CHANGED");
        File note=noteFile(context,localId),pdf=pdfFile(context,localId),cover=CoverStore.coverFile(note.getParentFile(),localId);
        if(note.exists()&&!fileHasSha(note,noteSha))throw new IOException("RESTORE_ROLLBACK_OWNERSHIP_MISMATCH");
        if(pdf.exists()&&(pdfSha==null||!fileHasSha(pdf,pdfSha)))throw new IOException("RESTORE_ROLLBACK_OWNERSHIP_MISMATCH");
        if(cover.exists()&&(coverSha==null||!fileHasSha(cover,coverSha)))throw new IOException("RESTORE_ROLLBACK_OWNERSHIP_MISMATCH");
        File tombstone=new File(note.getParentFile(),note.getName()+".deleted");
        if(tombstone.exists())throw new IOException("RESTORE_ROLLBACK_OWNERSHIP_MISMATCH");
        if(!sameFileStat(ownerIdentity,requireExactRestoreOwner(context,transactionId,groupId,localId,noteSha,updatedAt,pdfSha,coverSha,ownerState)))
            throw new IOException("RESTORE_OWNER_CHANGED");
        if(note.exists()&&!note.renameTo(tombstone))throw new IOException("RESTORE_ROLLBACK_DELETE_FAILED");
        JSONObject index=loadIndex(context);removeEntry(index,localId);
        try{saveIndex(context,index);}catch(Exception e){if(tombstone.exists())tombstone.renameTo(note);throw e;}
        if(tombstone.exists()&&!tombstone.delete())throw new IOException("RESTORE_ROLLBACK_DELETE_FAILED");
        File recovery=recoveryDraftFile(context,localId);if(recovery.exists()&&!recovery.delete())throw new IOException("RESTORE_ROLLBACK_DELETE_FAILED");
        if(pdf.exists()&&!pdf.delete())throw new IOException("RESTORE_ROLLBACK_DELETE_FAILED");
        if(cover.exists())Os.remove(cover.getAbsolutePath());
        if(!sameFileStat(ownerIdentity,requireExactRestoreOwner(context,transactionId,groupId,localId,noteSha,updatedAt,pdfSha,coverSha,ownerState)))
            throw new IOException("RESTORE_OWNER_CHANGED");
        Os.remove(ownerMarker.getAbsolutePath());syncParent(ownerMarker);
        }
    }

    private static JSONObject restoreOwnerJson(String tx,String group,String id,String noteSha,long updatedAt,
            String pdfSha,String coverSha,String state)throws Exception{
        return new JSONObject().put("schema_version",1).put("transaction_id",tx).put("group_id",group)
                .put("local_note_id",id).put("note_sha256",noteSha).put("updated_at",updatedAt)
                .put("pdf_sha256",pdfSha==null?JSONObject.NULL:pdfSha).put("cover_sha256",coverSha==null?JSONObject.NULL:coverSha)
                .put("state",state);
    }
    private static File restoreOwnerFile(Context context,String id)throws Exception{return new File(noteFile(context,id).getParentFile(),id+".restore-owner");}
    private static StructStat requireExactRestoreOwner(Context context,String tx,String group,String id,String noteSha,
            long updatedAt,String pdfSha,String coverSha,String state)throws Exception{
        File marker=restoreOwnerFile(context,id);StructStat before=Os.lstat(marker.getAbsolutePath());
        byte[] expected=restoreOwnerJson(tx,group,id,noteSha,updatedAt,pdfSha,coverSha,state).toString().getBytes(StandardCharsets.UTF_8);
        byte[] actual=readNoFollow(marker,4096);
        if(!java.security.MessageDigest.isEqual(sha256(expected).getBytes(StandardCharsets.US_ASCII),
                sha256(actual).getBytes(StandardCharsets.US_ASCII)))throw new IOException("RESTORE_OWNER_MISMATCH");
        RestoreOwner parsed=parseRestoreOwnerBytes(actual,id);
        if(!parsed.matches(tx,group,id,noteSha,updatedAt,pdfSha,coverSha)||!state.equals(parsed.state))throw new IOException("RESTORE_OWNER_MISMATCH");
        StructStat after=Os.lstat(marker.getAbsolutePath());
        if(!sameFileStat(before,after))throw new IOException("RESTORE_OWNER_CHANGED");
        return after;
    }
    private static RestoreOwner readRestoreOwner(Context context,String id)throws Exception{
        File marker=restoreOwnerFile(context,id);
        if(lstatOrNull(marker)==null)return null;
        return readRestoreOwnerFile(marker,id);
    }
    private static RestoreOwner readRestoreOwnerFile(File marker,String id)throws Exception{
        StructStat st=Os.lstat(marker.getAbsolutePath());if(!OsConstants.S_ISREG(st.st_mode)||st.st_nlink!=1||st.st_size>4096)throw new IOException("RESTORE_OWNER_INVALID");
        return parseRestoreOwnerBytes(readNoFollow(marker,4096),id);
    }
    private static RestoreOwner parseRestoreOwnerBytes(byte[] bytes,String id)throws Exception{
        Map<String,Object> fields=LibraryBackupJson.parseCheckedObject(bytes,4096);
        LibraryBackupJson.exactKeys(fields,"RESTORE_OWNER_FIELDS","schema_version","transaction_id","group_id","local_note_id","note_sha256","updated_at","pdf_sha256","cover_sha256","state");
        String tx=stringField(fields,"transaction_id"),group=stringField(fields,"group_id"),local=stringField(fields,"local_note_id"),sha=stringField(fields,"note_sha256"),state=stringField(fields,"state");
        long updated=strictLong(fields.get("updated_at"));String pdf=nullableString(fields.get("pdf_sha256")),cover=nullableString(fields.get("cover_sha256"));
        if(LibraryBackupJson.integer(fields.get("schema_version"),1,1,"RESTORE_OWNER_INVALID")!=1||!tx.matches("[0-9a-fA-F-]{36}")||!group.matches("i-[0-9a-f]{32}")||!local.equals(id)||!isSha256(sha)||(pdf!=null&&!isSha256(pdf))||(cover!=null&&!isSha256(cover))||updated<=0||!("pending".equals(state)||"committed".equals(state)))throw new IOException("RESTORE_OWNER_INVALID");
        return new RestoreOwner(tx,group,local,sha,updated,pdf,cover,state);
    }
    private static StructStat verifyCommittedOwnerTemp(File temp,String tx,String group,String id,String noteSha,
            long updatedAt,String pdfSha,String coverSha)throws Exception{
        StructStat before=Os.lstat(temp.getAbsolutePath());
        if(!OsConstants.S_ISREG(before.st_mode)||before.st_nlink!=1||before.st_size>4096)throw new IOException("RESTORE_OWNER_TEMP_INVALID");
        byte[] expected=restoreOwnerJson(tx,group,id,noteSha,updatedAt,pdfSha,coverSha,"committed")
                .toString().getBytes(StandardCharsets.UTF_8);
        byte[] actual=readNoFollow(temp,4096);
        if(!java.security.MessageDigest.isEqual(sha256(expected).getBytes(StandardCharsets.US_ASCII),
                sha256(actual).getBytes(StandardCharsets.US_ASCII)))throw new IOException("RESTORE_OWNER_TEMP_MISMATCH");
        RestoreOwner parsed=parseRestoreOwnerBytes(actual,id);
        if(!parsed.matches(tx,group,id,noteSha,updatedAt,pdfSha,coverSha)||!"committed".equals(parsed.state))
            throw new IOException("RESTORE_OWNER_TEMP_MISMATCH");
        StructStat after=Os.lstat(temp.getAbsolutePath());
        if(!sameFileStat(before,after))throw new IOException("RESTORE_OWNER_TEMP_CHANGED");
        return after;
    }
    private static StructStat lstatOrNull(File file)throws IOException{
        try{return Os.lstat(file.getAbsolutePath());}
        catch(android.system.ErrnoException error){if(error.errno==OsConstants.ENOENT)return null;throw new IOException("RESTORE_OWNER_INVALID");}
    }
    private static String stringField(Map<String,Object> f,String key)throws IOException{Object v=f.get(key);if(!(v instanceof String))throw new IOException("RESTORE_OWNER_INVALID");return(String)v;}
    private static String nullableString(Object v)throws IOException{if(v==null)return null;if(!(v instanceof String))throw new IOException("RESTORE_OWNER_INVALID");return(String)v;}
    private static long strictLong(Object v)throws IOException{return LibraryBackupJson.integer(v,1,Long.MAX_VALUE,"RESTORE_OWNER_INVALID");}
    private static boolean isSha256(String s){return s!=null&&s.matches("[0-9a-f]{64}");}
    private static String sha256(byte[] b)throws Exception{return hex(java.security.MessageDigest.getInstance("SHA-256").digest(b));}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder(b.length*2);for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return s.toString();}
    static boolean fileHasSha(File f,String sha)throws Exception{if(!isRegularOwned(f))return false;java.security.MessageDigest d=java.security.MessageDigest.getInstance("SHA-256");hashFile(f,d);return sha.equals(hex(d.digest()));}
    private static boolean regularOwnedFile(File f)throws Exception{StructStat s=Os.lstat(f.getAbsolutePath());return OsConstants.S_ISREG(s.st_mode)&&s.st_nlink==1;}
    private static void syncParent(File file)throws Exception{FileDescriptor fd=Os.open(file.getParentFile().getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC,0);try{Os.fsync(fd);}finally{Os.close(fd);}}
    private static void requireNotPendingRestore(Context context,String id)throws Exception{RestoreOwner owner=readRestoreOwner(context,id);if(owner!=null&&"pending".equals(owner.state)||!restoreGroupVisible(context,id))throw new IllegalStateException("RESTORE_NOTE_PENDING");}
    private static File restoreV2ReferenceFile(Context context,String id)throws Exception{return new File(noteFile(context,id).getParentFile(),id+".restore-v2");}
    private static byte[] restoreV2ReferenceBytes(String tx,String group,String member,String local,String payload,String semantic,String storage)throws Exception{return new JSONObject().put("schema_version",2).put("transaction_id",tx).put("group_id",group).put("member_id",member).put("local_object_id",local).put("kind","NOTE").put("payload_sha256",payload).put("semantic_binding_sha256",semantic).put("storage_binding_sha256",storage).toString().getBytes(StandardCharsets.UTF_8);}
    private static boolean restoreGroupVisible(Context context,String id)throws Exception{
        File reference=restoreV2ReferenceFile(context,id);StructStat st=lstatOrNull(reference);if(st==null)return true;
        if(!OsConstants.S_ISREG(st.st_mode)||st.st_nlink!=1||st.st_size<0||st.st_size>4096)return false;
        Map<String,Object> fields=LibraryBackupJson.parseCheckedObject(readNoFollow(reference,4096),4096);
        LibraryBackupJson.exactKeys(fields,"RESTORE_V2_OWNER_INVALID","schema_version","transaction_id","group_id","member_id","local_object_id","kind","payload_sha256","semantic_binding_sha256","storage_binding_sha256");
        if(LibraryBackupJson.integer(fields.get("schema_version"),2,2,"RESTORE_V2_OWNER_INVALID")!=2||!id.equals(LibraryBackupJson.string(fields.get("local_object_id"),"RESTORE_V2_OWNER_INVALID"))||!"NOTE".equals(LibraryBackupJson.string(fields.get("kind"),"RESTORE_V2_OWNER_INVALID")))return false;
        String tx=LibraryBackupJson.string(fields.get("transaction_id"),"RESTORE_V2_OWNER_INVALID"),group=LibraryBackupJson.string(fields.get("group_id"),"RESTORE_V2_OWNER_INVALID"),member=LibraryBackupJson.string(fields.get("member_id"),"RESTORE_V2_OWNER_INVALID");
        String payload=LibraryBackupJson.string(fields.get("payload_sha256"),"RESTORE_V2_OWNER_INVALID"),semantic=LibraryBackupJson.string(fields.get("semantic_binding_sha256"),"RESTORE_V2_OWNER_INVALID"),storage=LibraryBackupJson.string(fields.get("storage_binding_sha256"),"RESTORE_V2_OWNER_INVALID");
        return LibraryRestoreGroupV2.isCommittedReference(new File(context.getFilesDir(),"library-backup-v2/transactions"),tx,group,member,id,LibraryRestoreGroupV2.Kind.NOTE,payload,semantic,storage);
    }
    private static void requireHashValue(String value)throws IOException{if(value==null||!value.matches("[0-9a-f]{64}"))throw new IOException("RESTORE_V2_OWNER_INVALID");}
    private static byte[] readNoFollow(File file,int limit)throws Exception{
        StructStat before=regularStat(file);if(before.st_size>limit)throw new IOException("RESTORE_OWNER_LIMIT");
        FileDescriptor fd=Os.open(file.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);
        try(FileInputStream in=new FileInputStream(fd);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            StructStat opened=Os.fstat(fd);if(!sameFileStat(before,opened))throw new IOException("RESTORE_OWNER_CHANGED");
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()>limit-n)throw new IOException("RESTORE_OWNER_LIMIT");out.write(b,0,n);}
            if(!sameFileStat(opened,Os.fstat(fd))||!sameFileStat(opened,regularStat(file)))throw new IOException("RESTORE_OWNER_CHANGED");return out.toByteArray();}
    }
    private static final class RestoreOwner{final String tx,group,id,noteSha,pdfSha,coverSha,state;final long updatedAt;RestoreOwner(String t,String g,String i,String n,long u,String p,String c,String s){tx=t;group=g;id=i;noteSha=n;updatedAt=u;pdfSha=p;coverSha=c;state=s;}boolean matches(String t,String g,String i,String n,long u,String p,String c){return tx.equals(t)&&group.equals(g)&&id.equals(i)&&noteSha.equals(n)&&updatedAt==u&&java.util.Objects.equals(pdfSha,p)&&java.util.Objects.equals(coverSha,c);}}

    private static boolean sameFileBytes(File left,File right)throws Exception{
        java.security.MessageDigest a=java.security.MessageDigest.getInstance("SHA-256"),b=java.security.MessageDigest.getInstance("SHA-256");
        long al=hashFile(left,a),bl=hashFile(right,b);
        return al==bl&&java.security.MessageDigest.isEqual(a.digest(),b.digest());
    }
    private static long hashFile(File file,java.security.MessageDigest digest)throws Exception{
        StructStat before=regularStat(file);FileDescriptor fd=Os.open(file.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);
        try(FileInputStream in=new FileInputStream(fd)){StructStat opened=Os.fstat(fd);if(!sameFileStat(before,opened))throw new IOException("RESTORE_FILE_CHANGED");
            long total=0;byte[] buffer=new byte[32*1024];int n;while((n=in.read(buffer))!=-1){total+=n;if(total>PdfNoteIO.MAX_PDF_BYTES)throw new IOException("RESTORE_FILE_TOO_LARGE");digest.update(buffer,0,n);}
            if(!sameFileStat(opened,Os.fstat(fd))||!sameFileStat(opened,regularStat(file)))throw new IOException("RESTORE_FILE_CHANGED");return total;}
    }
    private static StructStat regularStat(File file)throws Exception{StructStat s=Os.lstat(file.getAbsolutePath());if(!OsConstants.S_ISREG(s.st_mode)||s.st_nlink!=1)throw new IOException("RESTORE_FILE_UNSAFE");return s;}
    private static boolean isRegularOwned(File file)throws Exception{try{regularStat(file);return true;}catch(android.system.ErrnoException e){if(e.errno==OsConstants.ENOENT)return false;throw e;}}
    private static boolean sameFileStat(StructStat a,StructStat b){return a.st_dev==b.st_dev&&a.st_ino==b.st_ino&&a.st_mode==b.st_mode&&a.st_nlink==b.st_nlink&&a.st_size==b.st_size&&a.st_mtime==b.st_mtime&&a.st_ctime==b.st_ctime;}

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
                    try {
                        RestoreOwner owner=readRestoreOwner(context,noteId);
                        if(owner!=null&&"pending".equals(owner.state))continue;
                    } catch(Exception invalidRestoreMarker) {
                        result.add(new RecoveryEntry(noteId,"导入状态需要检查",file,false));
                        continue;
                    }
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
            writeAtomic(noteFile(context, id), NoteJsonCodec.stringify(document), NOTE_VALIDATOR);
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

    /** Private source path for the library snapshot reader; noteId is validated first. */
    static File noteFileForBackup(Context context, String noteId) throws Exception {
        return noteFile(context, noteId);
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
