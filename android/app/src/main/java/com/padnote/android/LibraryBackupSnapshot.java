package com.padnote.android;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Frozen, app-private resource snapshot used by the library archive writer. */
final class LibraryBackupSnapshot implements AutoCloseable {
    interface Cancellation { boolean isCancelled(); }
    interface Progress { void onProgress(long completed, long total, String phase); }

    static final class Issue {
        final String itemId;
        final String code;
        Issue(String itemId, String code) { this.itemId = itemId; this.code = code; }
    }

    final LibraryBackupManifest manifest;
    final Map<String, File> resourceFiles;
    final File directory;
    final List<Issue> issues;
    final long totalBytes;
    private boolean closed;

    private LibraryBackupSnapshot(LibraryBackupManifest manifest, Map<String, File> files,
                                  File directory, List<Issue> issues, long totalBytes) {
        this.manifest = manifest;
        this.resourceFiles = Collections.unmodifiableMap(new LinkedHashMap<>(files));
        this.directory = directory;
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
        this.totalBytes = totalBytes;
    }

    LibraryBackupArchive.ArchiveResult write(File destination, Cancellation cancellation,
                                              Progress progress) throws IOException {
        ensureOpen();
        return LibraryBackupArchive.write(manifest, resourceFiles, destination,
                cancellation == null ? null : cancellation::isCancelled,
                progress == null ? null : (completed, total) ->
                        progress.onProgress(completed, total, "写入归档"));
    }

    private synchronized void ensureOpen() throws IOException {
        if (closed) throw new IOException("SNAPSHOT_CLOSED");
    }

    static LibraryBackupSnapshot create(Context context, Set<String> selectedNoteIds,
                                         Cancellation cancellation, Progress progress) throws Exception {
        if (context == null || selectedNoteIds == null)
            throw new IllegalArgumentException("SNAPSHOT_INPUT_INVALID");
        File parent = new File(context.getFilesDir(), "library-backup");
        ensurePrivateDirectory(context.getFilesDir(), parent);
        try { Os.chmod(parent.getAbsolutePath(), 0700); }
        catch (android.system.ErrnoException error) { throw new IOException("BACKUP_DIRECTORY_UNSAFE"); }
        File stage = new File(parent, "snapshot-" + UUID.randomUUID().toString());
        if (!stage.mkdir()) throw new IOException("SNAPSHOT_CREATE_FAILED");
        try { ensurePrivateDirectory(parent, stage); }
        catch (Exception error) { deleteOwnedDirectory(stage); throw error; }
        try { Os.chmod(stage.getAbsolutePath(), 0700); }
        catch (android.system.ErrnoException error) { deleteOwnedDirectory(stage); throw new IOException("SNAPSHOT_CREATE_FAILED"); }
        List<LibraryBackupManifest.Note> notes = new ArrayList<>();
        List<LibraryBackupManifest.VaultEntry> vaults = new ArrayList<>();
        List<LibraryBackupManifest.VideoAttachment> videos = new ArrayList<>();
        List<LibraryBackupManifest.CoverPreset> presets = new ArrayList<>();
        List<LibraryBackupManifest.Resource> resources = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        Map<String, File> files = new LinkedHashMap<>();
        Map<String, String> noteItems = new HashMap<>();
        long[] total = {0};
        try {
            File notesDirectory = new File(context.getFilesDir(), NoteStore.notesDirectoryName());
            ensurePrivateDirectory(context.getFilesDir(), notesDirectory);
            List<NoteStore.Entry> shelf = NoteStore.list(context);
            Map<String, NoteStore.Entry> byId = new HashMap<>();
            for (NoteStore.Entry entry : shelf) byId.put(entry.id, entry);
            List<String> ids = new ArrayList<>(selectedNoteIds);
            Collections.sort(ids);
            for (String noteId : ids) {
                checkCancelled(cancellation);
                NoteStore.Entry entry = byId.get(noteId);
                if (entry == null) throw new IOException("NOTE_SELECTION_CHANGED");
                JSONObject document = NoteStore.load(context, noteId);
                byte[] noteBytes = readRegular(NoteStore.noteFileForBackup(context, noteId),
                        LibraryBackupManifest.MAX_NOTE_BYTES);
                JSONObject exactDocument = new JSONObject(new String(noteBytes, StandardCharsets.UTF_8));
                NoteStore.validateDocument(exactDocument);
                if (!noteId.equals(exactDocument.optString("id")) ||
                        exactDocument.optLong("updatedAt", -1) != document.optLong("updatedAt", -2))
                    throw new IOException("NOTE_CHANGED_DURING_SNAPSHOT");
                long revision = exactDocument.optLong("updatedAt", -1);
                if (revision < 0) throw new IOException("NOTE_REVISION_INVALID");
                String itemId = id('i');
                noteItems.put(noteId, itemId);
                String noteResource = addBytes(stage, "note_document", "application/json",
                        noteBytes, resources, files, total);

                String pdfResource = null;
                if (exactDocument.optInt("pdfPageCount", 0) > 0) {
                    File pdf = NoteStore.pdfFile(context, noteId);
                    CopyResult copied = addFile(stage, pdf, "pdf_original", "application/pdf",
                            LibraryBackupManifest.MAX_PDF_BYTES, resources, files, total);
                    JSONObject checked = PdfNoteIO.pdfDocument(copied.file, entry.title);
                    if (checked.optInt("pdfPageCount", -1) != exactDocument.optInt("pdfPageCount", -2))
                        throw new IOException("PDF_PAGE_COUNT_MISMATCH");
                    pdfResource = copied.resourceId;
                }
                String coverResource = null;
                File cover = CoverStore.coverFile(notesDirectory, noteId);
                if (existsNoFollow(cover)) {
                    CopyResult copied = addFile(stage, cover, "assigned_cover_png", "image/png",
                            LibraryBackupManifest.MAX_PNG_BYTES, resources, files, total);
                    checkPng(copied.file);
                    coverResource = copied.resourceId;
                }
                notes.add(new LibraryBackupManifest.Note(itemId, noteId, revision,
                        exactDocument.optInt("schemaVersion", 0), noteResource, pdfResource,
                        coverResource, new ArrayList<>(), new ArrayList<>()));
            }

            Map<String, NoteStore.Entry> currentNotes = new HashMap<>(byId);
            File vaultDirectory = new File(context.getFilesDir(), "vault");
            ensurePrivateDirectory(context.getFilesDir(), vaultDirectory);
            VaultStore vaultStore = new VaultStore(vaultDirectory);
            for (VaultStore.VaultNote source : vaultStore.listForBackupStrict()) {
                checkCancelled(cancellation);
                boolean exists = currentNotes.containsKey(source.noteId);
                boolean selected = noteItems.containsKey(source.noteId);
                String state = selected ? "linked_note" : (exists ? "source_not_selected" : "source_deleted");
                String noteItemId = selected ? noteItems.get(source.noteId) : null;
                String markdown = strictUtf8(readRegular(vaultStore.fileForBackup(source.fileName),
                        LibraryBackupManifest.MAX_VAULT_BYTES));
                String body = vaultBody(markdown);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("schema_version", 1);
                payload.put("title", source.title);
                payload.put("markdown", body);
                payload.put("source_note_id", source.noteId);
                payload.put("source_revision_ms", Math.max(0L, source.sourceModifiedAt));
                payload.put("created_at_ms", Math.max(0L, source.digitizedAt));
                byte[] payloadBytes = LibraryBackupJson.encode(payload);
                String resourceId = addBytes(stage, "vault_entry_json", "application/json",
                        payloadBytes, resources, files, total);
                String itemId = id('v');
                vaults.add(new LibraryBackupManifest.VaultEntry(itemId, noteItemId, state,
                        source.noteId, Math.max(0L, source.sourceModifiedAt),
                        Math.max(0L, source.digitizedAt), resourceId));
                if (selected) addReference(notes, noteItemId, itemId, true);
            }

            VideoAttachmentStore attachmentStore = new VideoAttachmentStore(context);
            for (VideoAttachmentStore.Attachment attachment : attachmentStore.listAllForBackup()) {
                checkCancelled(cancellation);
                boolean exists = currentNotes.containsKey(attachment.noteId);
                boolean selected = noteItems.containsKey(attachment.noteId);
                boolean independent = "independent".equals(attachment.sourceState);
                String state = independent ? "independent" :
                        (selected ? "linked_note" : (exists ? "source_not_selected" : "source_deleted"));
                String noteItemId = independent ? null : (selected ? noteItems.get(attachment.noteId) : null);
                File verified = attachmentStore.openVerified(attachment);
                CopyResult copied = addFile(stage, verified, "video_attachment_mp4", "video/mp4",
                        LibraryBackupManifest.MAX_VIDEO_BYTES, resources, files, total);
                if (copied.length != attachment.sizeBytes || !copied.sha256.equals(attachment.sha256))
                    throw new IOException("VIDEO_CHANGED_DURING_SNAPSHOT");
                LibraryBackupManifest.ConnectionProvenance provenance =
                        new LibraryBackupManifest.ConnectionProvenance(attachment.connectionId,
                                attachment.connectionRevision, attachment.kind, attachment.transport,
                                attachment.bridgeId, attachment.instanceId, emptyToNull(attachment.certSha256));
                String itemId = id('a');
                videos.add(new LibraryBackupManifest.VideoAttachment(itemId, noteItemId, state,
                        attachment.originKind, attachment.sourceNoteId, attachment.sourceRevision,
                        attachment.sourceRevisionPrecisionMs,
                        attachment.sourceBundleSha256, attachment.taskPayloadSha256,
                        attachment.digestKind, attachment.offlineState, attachment.taskId,
                        attachment.remoteTaskId, provenance, attachment.artifactId, attachment.name,
                        "video/mp4", copied.length, copied.sha256, attachment.createdAt,
                        copied.resourceId));
                if (selected) addReference(notes, noteItemId, itemId, false);
            }

            File presetDirectory = context.getDir("covers", Context.MODE_PRIVATE);
            ensurePrivateDirectory(context.getFilesDir(), presetDirectory);
            File[] presetFiles = presetDirectory.listFiles();
            if (presetFiles != null) {
                java.util.Arrays.sort(presetFiles);
                for (File preset : presetFiles) {
                    if (!preset.getName().startsWith("preset-") || !preset.getName().endsWith(".png")) continue;
                    checkCancelled(cancellation);
                    CopyResult copied = addFile(stage, preset, "user_cover_preset_png", "image/png",
                            LibraryBackupManifest.MAX_PNG_BYTES, resources, files, total);
                    checkPng(copied.file);
                    presets.add(new LibraryBackupManifest.CoverPreset(id('c'), preset.getName(),
                            copied.resourceId));
                }
            }

            appendCommittedRestoredMaterials(context, stage, cancellation, currentNotes, noteItems,
                    notes, vaults, videos, presets, resources, files, total);

            if (notes.isEmpty() && vaults.isEmpty() && videos.isEmpty() && presets.isEmpty())
                throw new IOException("SNAPSHOT_EMPTY");
            String appVersion = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0).versionName;
            LibraryBackupManifest manifest = new LibraryBackupManifest(System.currentTimeMillis(),
                    new LibraryBackupManifest.Producer("android", appVersion),
                    LibraryBackupManifest.Scope.allSelected(), notes, vaults, videos, presets, resources);
            checkCancelled(cancellation);
            return new LibraryBackupSnapshot(manifest, files, stage, issues, total[0]);
        } catch (Exception error) {
            deleteOwnedDirectory(stage);
            throw error;
        }
    }

    private static void appendCommittedRestoredMaterials(Context context, File stage, Cancellation cancellation,
            Map<String,NoteStore.Entry> currentNotes, Map<String,String> noteItems,
            List<LibraryBackupManifest.Note> notes, List<LibraryBackupManifest.VaultEntry> vaults,
            List<LibraryBackupManifest.VideoAttachment> videos, List<LibraryBackupManifest.CoverPreset> presets,
            List<LibraryBackupManifest.Resource> resources, Map<String,File> files, long[] total) throws Exception {
        RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(context.getFilesDir());
        RestoredLibraryMaterialStore.CommitGate gate=RestoredLibraryMaterialStore.committedV2Gate(context.getFilesDir());
        RestoredLibraryMaterialStore.ListResult result=store.listDetailed(gate);
        if(!result.diagnostics.isEmpty())throw new IOException("RESTORED_MATERIAL_SNAPSHOT_INVALID");
        for(RestoredLibraryMaterialStore.Record row:result.records){
            checkCancelled(cancellation);
            String state=restoredSourceState(row,currentNotes,noteItems);
            String noteItemId="linked_note".equals(state)?noteItems.get(row.currentLocalNoteId):null;
            if(row.kind==RestoredLibraryMaterialStore.Kind.VAULT){
                byte[] payload=store.readVault(row.localId,gate);
                String resource=addBytes(stage,"vault_entry_json","application/json",payload,resources,files,total);
                String item=id('v');
                vaults.add(new LibraryBackupManifest.VaultEntry(item,noteItemId,state,
                        row.historicalSourceNoteId,row.sourceRevisionMs,row.createdAtMs,resource));
                if(noteItemId!=null)addReference(notes,noteItemId,item,true);
            }else{
                File extracted=new File(stage,"material-export-"+UUID.randomUUID()+".tmp");
                try{
                    store.copyForShare(row.localId,extracted,gate);
                    if(row.kind==RestoredLibraryMaterialStore.Kind.VIDEO){
                        CopyResult copied=addFile(stage,extracted,"video_attachment_mp4","video/mp4",
                                LibraryBackupManifest.MAX_VIDEO_BYTES,resources,files,total);
                        if(copied.length!=row.byteLength||!copied.sha256.equals(row.sha256))throw new IOException("RESTORED_VIDEO_CHANGED");
                        LibraryBackupManifest.ConnectionProvenance cp=new LibraryBackupManifest.ConnectionProvenance(
                                row.connectionId,row.connectionRevision,row.connectionKind,row.transport,row.bridgeId,
                                row.instanceId,row.certificateSha256);
                        String item=id('a');
                        videos.add(new LibraryBackupManifest.VideoAttachment(item,noteItemId,state,"restored_archive",
                                row.historicalSourceNoteId,row.sourceRevisionMs,row.sourceRevisionPrecisionMs,
                                row.sourceBundleSha256,row.taskPayloadSha256,row.digestKind,row.offlineState,
                                row.taskId,row.remoteTaskId,cp,row.artifactId,row.displayName,row.mediaType,
                                copied.length,copied.sha256,row.createdAtMs,copied.resourceId));
                        if(noteItemId!=null)addReference(notes,noteItemId,item,false);
                    }else{
                        CopyResult copied=addFile(stage,extracted,"user_cover_preset_png","image/png",
                                LibraryBackupManifest.MAX_PNG_BYTES,resources,files,total);
                        checkPng(copied.file);
                        presets.add(new LibraryBackupManifest.CoverPreset(id('c'),row.displayName,copied.resourceId));
                    }
                }finally{if(existsNoFollow(extracted))Os.remove(extracted.getAbsolutePath());}
            }
        }
    }

    private static String restoredSourceState(RestoredLibraryMaterialStore.Record row,
            Map<String,NoteStore.Entry> currentNotes,Map<String,String> noteItems) {
        if(row.currentLocalNoteId!=null&&noteItems.containsKey(row.currentLocalNoteId))return "linked_note";
        if(row.currentLocalNoteId!=null)return currentNotes.containsKey(row.currentLocalNoteId)?"source_not_selected":"source_deleted";
        if("source_deleted".equals(row.sourceState)||"source_not_selected".equals(row.sourceState)||
                "independent".equals(row.sourceState))return row.sourceState;
        return "source_deleted";
    }

    private static void addReference(List<LibraryBackupManifest.Note> notes, String noteItemId,
                                     String childId, boolean vault) {
        for (int i = 0; i < notes.size(); i++) {
            LibraryBackupManifest.Note note = notes.get(i);
            if (!note.itemId.equals(noteItemId)) continue;
            List<String> vaults = new ArrayList<>(note.vaultEntryIds);
            List<String> videos = new ArrayList<>(note.videoAttachmentIds);
            (vault ? vaults : videos).add(childId);
            notes.set(i, new LibraryBackupManifest.Note(note.itemId, note.sourceNoteId,
                    note.sourceRevisionMs, note.noteSchemaVersion, note.noteResourceId,
                    note.pdfResourceId, note.coverResourceId, vaults, videos));
            return;
        }
    }

    private static String addBytes(File directory, String role, String mediaType, byte[] bytes,
                                   List<LibraryBackupManifest.Resource> resources,
                                   Map<String, File> files, long[] total) throws Exception {
        File source = File.createTempFile("payload-", ".source", directory);
        try (FileOutputStream out = new FileOutputStream(source)) { out.write(bytes); out.getFD().sync(); }
        CopyResult result = addFile(directory, source, role, mediaType,
                limitForRole(role), resources, files, total);
        if (!source.delete()) throw new IOException("SNAPSHOT_TEMP_CLEANUP_FAILED");
        return result.resourceId;
    }

    private static CopyResult addFile(File directory, File source, String role, String mediaType,
                                      long maximum, List<LibraryBackupManifest.Resource> resources,
                                      Map<String, File> files, long[] total) throws Exception {
        String resourceId = resourceId();
        File destination = new File(directory, resourceId + ".bin");
        CopyResult copied = copyRegular(source, destination, maximum);
        if (copied.length > LibraryBackupManifest.MAX_TOTAL_RESOURCE_BYTES - total[0])
            throw new IOException("TOTAL_RESOURCE_LIMIT");
        total[0] += copied.length;
        resources.add(new LibraryBackupManifest.Resource(resourceId, role, mediaType,
                copied.length, copied.sha256, "payload/" + resourceId + ".bin"));
        files.put(resourceId, destination);
        copied.resourceId = resourceId;
        return copied;
    }

    private static long limitForRole(String role) throws IOException {
        switch (role) {
            case "note_document": return LibraryBackupManifest.MAX_NOTE_BYTES;
            case "pdf_original": return LibraryBackupManifest.MAX_PDF_BYTES;
            case "assigned_cover_png": case "user_cover_preset_png": return LibraryBackupManifest.MAX_PNG_BYTES;
            case "vault_entry_json": return LibraryBackupManifest.MAX_VAULT_BYTES;
            case "video_attachment_mp4": return LibraryBackupManifest.MAX_VIDEO_BYTES;
            default: throw new IOException("RESOURCE_ROLE_INVALID");
        }
    }

    private static CopyResult copyRegular(File source, File destination, long maximum) throws Exception {
        if (source == null || destination == null)
            throw new IOException("SNAPSHOT_RESOURCE_CREATE_FAILED");
        FileDescriptor descriptor = null;
        try {
            StructStat link = Os.lstat(source.getAbsolutePath());
            if (!regular(link) || link.st_nlink != 1 || link.st_size < 0 || link.st_size > maximum)
                throw new IOException("SOURCE_FILE_UNSAFE");
            descriptor = Os.open(source.getAbsolutePath(), OsConstants.O_RDONLY | AndroidFileCompat.O_CLOEXEC |
                    OsConstants.O_NOFOLLOW, 0);
            StructStat before = Os.fstat(descriptor);
            if (!sameIdentity(link, before) || !regular(before) || before.st_nlink != 1 ||
                    before.st_size < 0 || before.st_size > maximum) throw new IOException("SOURCE_FILE_CHANGED");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try (FileInputStream input = new FileInputStream(descriptor)) {
                FileDescriptor outputDescriptor = Os.open(destination.getAbsolutePath(),
                        OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL |
                                AndroidFileCompat.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0600);
                try (FileOutputStream output = new FileOutputStream(outputDescriptor)) {
                byte[] buffer = new byte[32768];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (count > maximum - read) throw new IOException("RESOURCE_SIZE_LIMIT");
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    count += read;
                }
                output.flush();
                output.getFD().sync();
                StructStat after = Os.fstat(input.getFD());
                if (!sameSnapshot(before, after) || count != before.st_size)
                    throw new IOException("SOURCE_FILE_CHANGED");
                }
            }
            return new CopyResult(destination, count, hex(digest.digest()));
        } catch (Exception error) {
            try {
                StructStat stat = Os.lstat(destination.getAbsolutePath());
                if (regular(stat) && stat.st_nlink == 1) Os.remove(destination.getAbsolutePath());
            } catch (Exception ignored) { }
            throw error;
        }
    }

    private static byte[] readRegular(File source, long maximum) throws Exception {
        if (source == null) throw new IOException("SOURCE_FILE_MISSING");
        StructStat link = Os.lstat(source.getAbsolutePath());
        if (!regular(link) || link.st_nlink != 1 || link.st_size < 0 || link.st_size > maximum)
            throw new IOException("SOURCE_FILE_UNSAFE");
        FileDescriptor descriptor = Os.open(source.getAbsolutePath(), OsConstants.O_RDONLY |
                AndroidFileCompat.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
        try (FileInputStream input = new FileInputStream(descriptor);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream((int) link.st_size)) {
            StructStat before = Os.fstat(input.getFD());
            if (!sameIdentity(link, before) || !regular(before) || before.st_nlink != 1)
                throw new IOException("SOURCE_FILE_CHANGED");
            byte[] buffer = new byte[32768];
            long count = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (count > maximum - read) throw new IOException("RESOURCE_SIZE_LIMIT");
                output.write(buffer, 0, read); count += read;
            }
            if (count != before.st_size || !sameSnapshot(before, Os.fstat(input.getFD())))
                throw new IOException("SOURCE_FILE_CHANGED");
            return output.toByteArray();
        }
    }

    private static void checkPng(File file) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0 ||
                (long) options.outWidth * options.outHeight > 12_000_000L)
            throw new IOException("PNG_INVALID_OR_TOO_LARGE");
    }

    private static String vaultBody(String markdown) throws IOException {
        if (markdown == null || !markdown.startsWith("---\n")) throw new IOException("VAULT_FORMAT_INVALID");
        int end = markdown.indexOf("\n---\n", 4);
        if (end < 0) throw new IOException("VAULT_FORMAT_INVALID");
        return markdown.substring(end + 5);
    }

    private static String strictUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new IOException("VAULT_UTF8_INVALID");
        }
    }

    private static String sourceState(boolean selected, boolean exists) {
        return selected ? "linked_note" : (exists ? "source_not_selected" : "source_deleted");
    }

    private static void checkCancelled(Cancellation cancellation) throws IOException {
        if (cancellation != null && cancellation.isCancelled()) throw new IOException("BACKUP_CANCELLED");
    }

    private static void ensurePrivateDirectory(File trustedParent, File directory) throws IOException {
        StructStat parentStat;
        try { parentStat = Os.lstat(trustedParent.getAbsolutePath()); }
        catch (android.system.ErrnoException error) { throw new IOException("BACKUP_PARENT_INVALID"); }
        if (!regularDirectory(parentStat)) throw new IOException("BACKUP_PARENT_INVALID");
        if (!directory.exists() && !directory.mkdir()) throw new IOException("BACKUP_DIRECTORY_CREATE_FAILED");
        StructStat root;
        try { root = Os.lstat(directory.getAbsolutePath()); }
        catch (android.system.ErrnoException error) { throw new IOException("BACKUP_DIRECTORY_UNSAFE"); }
        if (!regularDirectory(root) || !directory.getCanonicalFile().equals(
                new File(trustedParent.getCanonicalFile(), directory.getName())))
            throw new IOException("BACKUP_DIRECTORY_UNSAFE");
    }

    private static boolean existsNoFollow(File file) throws IOException {
        try { Os.lstat(file.getAbsolutePath()); return true; }
        catch (android.system.ErrnoException error) {
            if (error.errno == OsConstants.ENOENT) return false;
            throw new IOException("SOURCE_STAT_FAILED");
        }
    }

    private static boolean regular(StructStat stat) {
        return (stat.st_mode & OsConstants.S_IFMT) == OsConstants.S_IFREG;
    }
    private static boolean regularDirectory(StructStat stat) {
        return (stat.st_mode & OsConstants.S_IFMT) == OsConstants.S_IFDIR;
    }
    private static boolean sameIdentity(StructStat a, StructStat b) { return a.st_dev == b.st_dev && a.st_ino == b.st_ino; }
    private static boolean sameSnapshot(StructStat a, StructStat b) {
        return sameIdentity(a,b) && a.st_size == b.st_size && a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime;
    }

    private static String id(char type) { return type + "-" + UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT); }
    private static String resourceId() { return id('r'); }
    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(bytes.length * 2); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString(); }

    private static void deleteOwnedDirectory(File directory) {
        if (directory == null) return;
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) {
            try {
                StructStat stat = Os.lstat(child.getAbsolutePath());
                if (regularDirectory(stat)) deleteOwnedDirectory(child);
                else if (regular(stat) && stat.st_nlink == 1) child.delete();
            } catch (Exception ignored) { }
        }
        try { if (regularDirectory(Os.lstat(directory.getAbsolutePath()))) directory.delete(); }
        catch (Exception ignored) { }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        deleteOwnedDirectory(directory);
    }

    private static final class CopyResult {
        final File file; final long length; final String sha256; String resourceId;
        CopyResult(File file, long length, String sha256) { this.file=file; this.length=length; this.sha256=sha256; }
    }
}
