package com.padnote.android;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import com.padnote.android.streaming.StreamingGroupStore;

/**
 * Storage for the knowledge base: one Markdown file per digitized handwritten
 * note, under the app-private {@code vault/} directory.
 *
 * <p>Files are plain Markdown with a small YAML front-matter block, so the
 * whole directory stays Obsidian-compatible — copying it out loses nothing.
 * The front-matter carries the source note id and the timestamp of the ink it
 * was generated from, which is what makes the "已过期" staleness badge possible
 * without any separate index database.
 *
 * <p>Implements {@link NoteTools.VaultReader} so the AI's knowledge-base tools
 * see exactly {@link #list()} and {@link #read(String)} — and no write path.
 */
final class VaultStore implements NoteTools.VaultReader {
    private static final Object GLOBAL_LOCK = new Object();

    static final class VaultNote {
        final String fileName;
        final String noteId;
        final String title;
        final int pageCount;
        final long digitizedAt;
        final long sourceModifiedAt;
        final boolean groupManaged;
        final String lineage, revision, digest, materialId;

        VaultNote(String fileName, String noteId, String title, int pageCount,
                  long digitizedAt, long sourceModifiedAt) {
            this(fileName,noteId,title,pageCount,digitizedAt,sourceModifiedAt,false,
                    null,null,null,null);
        }

        VaultNote(String fileName,String noteId,String title,int pageCount,long digitizedAt,
                  long sourceModifiedAt,String lineage,String revision,String digest,
                  String materialId) {
            this(fileName,noteId,title,pageCount,digitizedAt,sourceModifiedAt,true,
                    lineage,revision,digest,materialId);
        }

        private VaultNote(String fileName,String noteId,String title,int pageCount,long digitizedAt,
                  long sourceModifiedAt,boolean groupManaged,String lineage,String revision,String digest,
                  String materialId) {
            this.fileName=fileName;this.noteId=noteId;this.title=title;this.pageCount=pageCount;
            this.digitizedAt=digitizedAt;this.sourceModifiedAt=sourceModifiedAt;
            this.groupManaged=groupManaged;this.lineage=lineage;this.revision=revision;this.digest=digest;
            this.materialId=materialId;
        }
    }

    /** Immutable UI selection; grouped content and its CAS token come from one verified snapshot. */
    static final class VaultSelection {
        final String fileName,noteId,title,lineage,revision,digest,materialId;
        final boolean groupManaged;
        final byte[] markdown;
        VaultSelection(VaultNote note,boolean groupManaged,String lineage,String revision,String digest,
                       String materialId,byte[] markdown) {
            this.fileName=note.fileName;this.noteId=note.noteId;this.title=note.title;
            this.groupManaged=groupManaged;this.lineage=lineage;this.revision=revision;this.digest=digest;
            this.materialId=materialId;this.markdown=markdown.clone();
        }
    }

    private final File vaultDir;
    private final NoteStore.WriteFaultInjector writeFaults;
    private final Context authorityContext;

    VaultStore(Context context) {
        this(context, new File(context.getFilesDir(), "vault"), NoteStore.NO_WRITE_FAULTS);
    }

    VaultStore(File vaultDir) {
        this(vaultDir, NoteStore.NO_WRITE_FAULTS);
    }

    VaultStore(File vaultDir, NoteStore.WriteFaultInjector writeFaults) {
        this(null, vaultDir, writeFaults);
    }

    private VaultStore(Context context, File vaultDir, NoteStore.WriteFaultInjector writeFaults) {
        this.authorityContext = context;
        this.vaultDir = vaultDir;
        this.writeFaults = writeFaults == null ? NoteStore.NO_WRITE_FAULTS : writeFaults;
        if (!vaultDir.exists() && !vaultDir.mkdirs()) {
            // list()/write() surface the failure; constructor stays tolerant.
        }
    }

    public List<VaultNote> list() {
        List<NoteStore.Entry> entries=java.util.Collections.emptyList();
        if(authorityContext!=null&&android.os.Build.VERSION.SDK_INT>=27){
            try{entries=NoteStore.list(authorityContext);}catch(Exception unavailableShelf){
                // Preserve best-effort listing behavior; group reads still fail closed.
            }
        }
        return listForShelf(entries);
    }

    /** Uses the already loaded shelf catalog as IDs, then freshly verifies each group. */
    List<VaultNote> listForShelf(List<NoteStore.Entry> shelfEntries) {
        List<VaultNote> notes=new ArrayList<>();
        java.util.Set<String> groupManagedNotes=new java.util.HashSet<>();
        GroupAuthorityBridge.CatalogReader catalogReader=null;boolean readerAttempted=false;Exception readerFailure=null;
        if(authorityContext!=null&&android.os.Build.VERSION.SDK_INT>=27&&shelfEntries!=null){
            for(NoteStore.Entry entry:shelfEntries){
                if(Thread.currentThread().isInterrupted())return java.util.Collections.emptyList();
                com.padnote.android.streaming.StreamingGroupStore.Snapshot snapshot;
                try{
                    if(!readerAttempted){readerAttempted=true;try{catalogReader=GroupAuthorityBridge.catalogReader(authorityContext);}catch(Exception unavailable){readerFailure=unavailable;}}
                    if(readerFailure!=null)throw readerFailure;
                    snapshot=catalogReader.open(entry.id);
                }catch(Exception unavailableGroup){
                    groupManagedNotes.add(entry.id);
                    continue;
                }
                if(snapshot==null)continue;
                groupManagedNotes.add(entry.id);
                try{
                    for(GroupAuthorityBridge.VaultView view:GroupAuthorityBridge.vaultSummaries(snapshot))
                        notes.add(new VaultNote(view.fileName,view.noteId,view.title,
                                view.pageCount,view.digitizedAt,view.sourceModifiedAt,
                                snapshot.lineage,snapshot.revision,snapshot.digest,view.materialId));
                }catch(Exception invalidGroupVault){
                    // A committed group's own Vault members remain authoritative;
                    // never fall back to stale legacy files for that note.
                }
            }
        }
        try{recoverVaultFiles(groupManagedNotes,false);}catch(Exception unavailableRecovery){}
        appendLegacyVaultRows(notes,groupManagedNotes);
        notes.sort((a,b)->Long.compare(b.digitizedAt,a.digitizedAt));
        return java.util.Collections.unmodifiableList(notes);
    }

    /** Strict archive view: a damaged vault file is reported instead of silently omitted. */
    List<VaultNote> listForBackupStrict() throws Exception {
        List<VaultNote> result=new ArrayList<>();java.util.Set<String> groupManaged=new java.util.HashSet<>();
        if(authorityContext!=null&&android.os.Build.VERSION.SDK_INT>=27){
            GroupAuthorityBridge.CatalogReader catalogReader=null;
            for(NoteStore.Entry entry:NoteStore.list(authorityContext)){
                if(catalogReader==null)catalogReader=GroupAuthorityBridge.catalogReader(authorityContext);
                com.padnote.android.streaming.StreamingGroupStore.Snapshot snapshot=catalogReader.open(entry.id);
                if(snapshot==null)continue;
                groupManaged.add(entry.id);
                for(GroupAuthorityBridge.VaultView view:GroupAuthorityBridge.vaultSummaries(snapshot))
                    result.add(new VaultNote(view.fileName,view.noteId,view.title,view.pageCount,view.digitizedAt,view.sourceModifiedAt));
            }
        }
        result.addAll(listLegacyForBackupStrict(groupManaged));
        result.sort((a,b)->Long.compare(b.digitizedAt,a.digitizedAt));return Collections.unmodifiableList(result);
    }

    /** Strict legacy-only rows, excluding notes whose pinned group snapshot is authoritative. */
    List<VaultNote> listLegacyForBackupStrict(java.util.Set<String> groupManagedNotes) throws Exception {
        recoverVaultFiles(groupManagedNotes,true);
        File[] files=vaultDir.listFiles((dir,name)->name.endsWith(".md"));
        if(files==null)throw new IOException("VAULT_DIRECTORY_UNREADABLE");
        java.util.Arrays.sort(files);
        List<VaultNote> result=new ArrayList<>();
        for(File file:files){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("vault_list_cancelled");
            try(LegacyGroupMutationLock.Lease mutationGate=LegacyGroupMutationLock.acquire()){
                String provenOwner=peekFamilyOwner(file.getName());
                if(provenOwner==null)throw new IOException("VAULT_OWNER_UNVERIFIED");
                if(groupManagedNotes!=null&&groupManagedNotes.contains(provenOwner))continue;
                String primaryOwner=readOwnerHint(file.getName());
                if(primaryOwner!=null&&!primaryOwner.equals(provenOwner))
                    throw new IOException("VAULT_OWNER_AMBIGUOUS");
                if(!isDefinitelyLegacyOwner(provenOwner,true))continue;
                synchronized(GLOBAL_LOCK){
                    File checked=checkedVaultFile(file.getName());
                    NoteStore.recoverAtomicFile(checked,VaultStore::validateAnyMarkdown);
                    if(!checked.isFile()||checked.length()>16L*1024*1024)
                        throw new IOException("VAULT_ENTRY_INVALID");
                    String head=readFileHead(checked,4096);
                    if(!head.startsWith("---\n")||!head.contains("\n---\n"))
                        throw new IOException("VAULT_ENTRY_INVALID");
                    String noteId=frontValue(head,"note-id");
                    if(noteId==null||!noteId.matches("[A-Za-z0-9_-]{1,160}"))throw new IOException("VAULT_SOURCE_ID_INVALID");
                    if(!provenOwner.equals(noteId))throw new IOException("VAULT_SOURCE_CHANGED");
                    if(groupManagedNotes!=null&&groupManagedNotes.contains(noteId))continue;
                    String pages=frontValue(head,"pages"),created=frontValue(head,"digitized-epoch"),modified=frontValue(head,"source-modified");
                    try{
                        int pageCount=Integer.parseInt(pages);long createdAt=Long.parseLong(created),sourceRevision=Long.parseLong(modified);
                        if(pageCount<0||createdAt<0||sourceRevision<0)throw new NumberFormatException();
                        readBoundedUnlocked(file.getName(),16*1024*1024);
                        result.add(new VaultNote(file.getName(),noteId,frontValue(head,"title"),pageCount,createdAt,sourceRevision));
                    }catch(NumberFormatException invalid){throw new IOException("VAULT_METADATA_INVALID");}
                }
            }
        }
        result.sort((a,b)->Long.compare(b.digitizedAt,a.digitizedAt));
        return result;
    }

    File fileForBackup(String fileName) throws Exception {
        if(authorityContext!=null&&android.os.Build.VERSION.SDK_INT>=27&&GroupAuthorityBridge.isVirtualVaultName(fileName)){
            File projected=GroupAuthorityBridge.vaultProjection(authorityContext,fileName);
            if(projected==null)throw new IOException("GROUP_VAULT_MARKER_MISSING");
            return projected;
        }
        return checkedVaultFile(fileName);
    }

    private void appendLegacyVaultRows(List<VaultNote> notes,java.util.Set<String> groupManagedNotes) {
        File[] files=vaultDir.listFiles((dir,name)->name.endsWith(".md"));
        if(files==null)return;
        java.util.Arrays.sort(files);
        for(File file:files){
            if(Thread.currentThread().isInterrupted())return;
            try(LegacyGroupMutationLock.Lease mutationGate=LegacyGroupMutationLock.acquire()){
                String provenOwner=readOwnerHint(file.getName());
                if(provenOwner==null||groupManagedNotes.contains(provenOwner))continue;
                if(!isDefinitelyLegacyOwner(provenOwner,false))continue;
                synchronized(GLOBAL_LOCK){
                    File checked=checkedVaultFile(file.getName());
                    NoteStore.recoverAtomicFile(checked,VaultStore::validateAnyMarkdown);
                    String head=readFileHead(checked,2048);
                    if(!head.startsWith("---\n")||!head.contains("\n---\n"))continue;
                    String noteId=frontValue(head,"note-id");
                    if(!noteId.matches("[A-Za-z0-9_-]{1,160}")||!provenOwner.equals(noteId))continue;
                    if(groupManagedNotes.contains(noteId))continue;
                    notes.add(new VaultNote(file.getName(),noteId,frontValue(head,"title"),
                            parseInt(frontValue(head,"pages"),0),
                            parseLong(frontValue(head,"digitized-epoch"),0L),
                            parseLong(frontValue(head,"source-modified"),0L)));
                }
            }catch(InterruptedException cancelled){Thread.currentThread().interrupt();return;}
            catch(Exception invalidOrUnverifiedOwner){
                // Unknown or damaged group ownership is never downgraded to a legacy row.
            }
        }
    }

    /** Returns true only after a complete group-marker lookup proves this owner is unmanaged. */
    private boolean isDefinitelyLegacyOwner(String noteId,boolean strict) throws Exception {
        if(authorityContext==null||android.os.Build.VERSION.SDK_INT<27)return true;
        if(noteId==null||!noteId.matches("[A-Za-z0-9_-]{1,160}")){
            if(strict)throw new IOException("VAULT_SOURCE_ID_INVALID");
            return false;
        }
        try{
            com.padnote.android.streaming.StreamingGroupStore.Snapshot snapshot=
                    new NoteGroupFacade(authorityContext).openGroupIncludingRetired(noteId);
            return snapshot==null;
        }catch(Exception unverifiedCatalog){
            if(strict)throw new IOException("VAULT_OWNER_UNVERIFIED",unverifiedCatalog);
            return false;
        }
    }

    /** Reads the owner header under the legacy mutation gate and GLOBAL_LOCK. */
    private String readOwnerHint(String fileName) throws Exception {
        synchronized(GLOBAL_LOCK){
            File checked=checkedVaultFile(fileName);
            if(!checked.isFile())return null;
            String head=readFileHead(checked,4096);
            if(!head.startsWith("---\n")||!head.contains("\n---\n"))return null;
            String noteId=frontValue(head,"note-id");
            return noteId!=null&&noteId.matches("[A-Za-z0-9_-]{1,160}")?noteId:null;
        }
    }

    public String read(String fileName) throws Exception {
        return readBounded(fileName, 16 * 1024 * 1024);
    }

    /** Captures the selected row without mixing a group token with bytes from another read. */
    VaultSelection captureSelection(VaultNote note) throws Exception {
        if(note==null)throw new IllegalArgumentException("VAULT_SELECTION_REQUIRED");
        if(note.groupManaged){
            if(authorityContext==null||android.os.Build.VERSION.SDK_INT<27
                    ||note.lineage==null||note.revision==null||note.digest==null
                    ||note.materialId==null)
                throw new IOException("GROUP_VAULT_SELECTION_INCOMPLETE");
            com.padnote.android.streaming.StreamingGroupStore.Snapshot snapshot=
                    GroupAuthorityBridge.open(authorityContext,note.noteId);
            if(snapshot==null)throw new IOException("GROUP_VAULT_MARKER_MISSING");
            if(!note.noteId.equals(snapshot.localId)||!note.lineage.equals(snapshot.lineage)
                    ||!note.revision.equals(snapshot.revision)||!note.digest.equals(snapshot.digest))
                throw new IOException("VAULT_SELECTION_CHANGED");
            GroupAuthorityBridge.VaultView view=GroupAuthorityBridge.vaultView(snapshot,note.materialId);
            if(!note.fileName.equals(view.fileName)||!note.noteId.equals(view.noteId))
                throw new IOException("GROUP_VAULT_SELECTION_IDENTITY_MISMATCH");
            return new VaultSelection(note,true,snapshot.lineage,snapshot.revision,snapshot.digest,
                    view.materialId,view.markdown);
        }
        if(authorityContext!=null&&android.os.Build.VERSION.SDK_INT>=27
                &&GroupAuthorityBridge.open(authorityContext,note.noteId)!=null)
            throw new IOException("VAULT_SELECTION_CHANGED");
        String current=readBounded(note.fileName,16*1024*1024);
        String currentHead=current.substring(0,Math.min(current.length(),4096));
        if(note.noteId==null||!note.noteId.equals(frontValue(currentHead,"note-id")))
            throw new IOException("VAULT_SELECTION_CHANGED");
        byte[] markdown=current.getBytes(StandardCharsets.UTF_8);
        return new VaultSelection(note,false,null,null,null,null,markdown);
    }

    /** Explicit rebase target: reopens the same material and returns its newly verified token. */
    VaultSelection captureCurrentSelection(VaultSelection prior) throws Exception {
        if(prior==null)throw new IllegalArgumentException("VAULT_SELECTION_REQUIRED");
        if(!prior.groupManaged){
            VaultNote legacy=new VaultNote(prior.fileName,prior.noteId,prior.title,0,0,0);
            return captureSelection(legacy);
        }
        if(authorityContext==null||android.os.Build.VERSION.SDK_INT<27)
            throw new IOException("GROUP_VAULT_REQUIRES_API_27");
        com.padnote.android.streaming.StreamingGroupStore.Snapshot snapshot=
                GroupAuthorityBridge.open(authorityContext,prior.noteId);
        if(snapshot==null)throw new IOException("GROUP_VAULT_MARKER_MISSING");
        if(!prior.noteId.equals(snapshot.localId))throw new IOException("GROUP_VAULT_SELECTION_IDENTITY_MISMATCH");
        if(!prior.lineage.equals(snapshot.lineage))throw new IOException("GROUP_VAULT_LINEAGE_CHANGED");
        GroupAuthorityBridge.VaultView view=GroupAuthorityBridge.vaultView(snapshot,prior.materialId);
        VaultNote current=new VaultNote(view.fileName,view.noteId,view.title,view.pageCount,
                view.digitizedAt,view.sourceModifiedAt,snapshot.lineage,snapshot.revision,
                snapshot.digest,view.materialId);
        return new VaultSelection(current,true,snapshot.lineage,snapshot.revision,snapshot.digest,
                view.materialId,view.markdown);
    }

    /** Captures a new explicit digitization destination without mixing rows and group tokens. */
    DigitizationStore.Target captureDigitizationTarget(String noteId,VaultNote selected)throws Exception {
        if(noteId==null||!noteId.matches("[A-Za-z0-9_-]{1,160}"))
            throw new IOException("VAULT_SOURCE_ID_INVALID");
        long publishTime=System.currentTimeMillis();
        if(selected!=null){
            if(!noteId.equals(selected.noteId))throw new IOException("DIGITIZATION_TARGET_NOTE_MISMATCH");
            VaultSelection selection=captureSelection(selected);
            String operationId=UUID.randomUUID().toString();
            if(selection.groupManaged)return new DigitizationStore.Target(noteId,
                    DigitizationStore.TargetKind.GROUP,DigitizationStore.TargetOperation.REPLACE,
                    operationId,selection.materialId,selection.lineage,selection.revision,selection.digest,
                    null,null,publishTime);
            return new DigitizationStore.Target(noteId,DigitizationStore.TargetKind.LEGACY,
                    DigitizationStore.TargetOperation.REPLACE,operationId,null,null,null,null,selection.fileName,
                    hexDigest(sha256(selection.markdown)),publishTime);
        }
        if(authorityContext!=null&&android.os.Build.VERSION.SDK_INT>=27){
            StreamingGroupStore.Snapshot snapshot=GroupAuthorityBridge.open(authorityContext,noteId);
            if(snapshot!=null){String operationId=UUID.randomUUID().toString();
                return new DigitizationStore.Target(noteId,DigitizationStore.TargetKind.GROUP,
                    DigitizationStore.TargetOperation.ADD,operationId,operationId,
                    snapshot.lineage,snapshot.revision,snapshot.digest,null,null,publishTime);}
            if(!isDefinitelyLegacyOwner(noteId,true))throw new IOException("VAULT_OWNER_UNVERIFIED");
        }
        String operationId=UUID.randomUUID().toString();
        return new DigitizationStore.Target(noteId,DigitizationStore.TargetKind.LEGACY,
                DigitizationStore.TargetOperation.ADD,operationId,null,null,null,null,
                DigitizationStore.legacyDigitizationFileName(noteId,operationId),null,publishTime);
    }

    /** Publishes against the target token frozen in the digitization checkpoint. */
    String publishDigitization(DigitizationStore.Target target,String title,int pageCount,
                               long sourceModifiedAt,List<String> pageSections)throws Exception {
        if(target==null)throw new IOException("DIGITIZATION_TARGET_REQUIRED");
        String markdown=buildDigitizedMarkdown(target.noteId,title,pageCount,sourceModifiedAt,
                pageSections,target.publishTimestamp,target.operationId);
        if(target.kind==DigitizationStore.TargetKind.GROUP){
            if(authorityContext==null||android.os.Build.VERSION.SDK_INT<27)
                throw new IOException("GROUP_VAULT_REQUIRES_API_27");
            byte[] bytes=markdown.getBytes(StandardCharsets.UTF_8);
            StreamingGroupStore.Snapshot current=GroupAuthorityBridge.open(authorityContext,target.noteId);
            if(current==null)throw new IOException("DIGITIZATION_TARGET_GROUP_MISSING");
            if(!target.lineage.equals(current.lineage))throw new IOException("DIGITIZATION_TARGET_LINEAGE_CHANGED");
            // A process death after commit but before checkpoint update is retried only when
            // a later generation contains the exact stable material ID and frozen bytes.
            // At the captured base generation, even an exact-content replacement must still
            // publish through CAS so the explicit operation creates its own revision.
            boolean stillAtCapturedBase=target.groupRevision.equals(current.revision)
                    &&target.groupDigest.equals(current.digest);
            if(!stillAtCapturedBase){
                try {
                    GroupAuthorityBridge.VaultView existing=GroupAuthorityBridge.vaultView(current,target.materialId);
                    if(target.operationId.equals(digitizationOperationId(existing.markdown))
                            &&MessageDigest.isEqual(existing.markdown,bytes))return existing.fileName;
                } catch(IOException absentOrInvalid){
                    if(!"GROUP_VAULT_MATERIAL_REMOVED".equals(absentOrInvalid.getMessage()))throw absentOrInvalid;
                }
            }
            if(!stillAtCapturedBase)
                throw new IOException("DIGITIZATION_TARGET_STALE");
            NoteGroupFacade facade=new NoteGroupFacade(authorityContext);
            StreamingGroupStore.Snapshot committed=target.operation==DigitizationStore.TargetOperation.ADD
                    ?facade.publishVaultRevision(target.noteId,target.groupRevision,target.groupDigest,target.materialId,bytes)
                    :facade.replaceVaultRevision(target.noteId,target.groupRevision,target.groupDigest,target.materialId,bytes);
            if(committed==null||!target.noteId.equals(committed.localId)||!target.lineage.equals(committed.lineage))
                throw new IOException("DIGITIZATION_PUBLISH_IDENTITY_MISMATCH");
            GroupAuthorityBridge.VaultView exact=GroupAuthorityBridge.vaultView(committed,target.materialId);
            if(!target.operationId.equals(digitizationOperationId(exact.markdown))
                    ||!MessageDigest.isEqual(exact.markdown,bytes))
                throw new IOException("DIGITIZATION_PUBLISH_BYTES_MISMATCH");
            return exact.fileName;
        }
        try(LegacyGroupMutationLock.Lease gate=LegacyGroupMutationLock.acquire()){
            if(authorityContext!=null&&!isDefinitelyLegacyOwner(target.noteId,true))
                throw new IOException("VAULT_OWNER_UNVERIFIED");
            requireLegacyMutationAllowed(target.noteId);
            synchronized(GLOBAL_LOCK){
                String name=target.legacyFileName;
                File selected=checkedVaultFile(name);
                if(selected.isFile()){
                    String existing=readBoundedUnlocked(name,16*1024*1024);
                    if(target.operationId.equals(digitizationOperationId(existing))&&existing.equals(markdown))
                        return name;
                    if(target.operation==DigitizationStore.TargetOperation.ADD)
                        throw new IOException("DIGITIZATION_TARGET_PATH_CONFLICT");
                    String digest=hexDigest(sha256(existing.getBytes(StandardCharsets.UTF_8)));
                    if(!target.legacyBaseSha256.equals(digest))throw new IOException("DIGITIZATION_TARGET_STALE");
                }else if(target.operation==DigitizationStore.TargetOperation.REPLACE){
                    throw new IOException("DIGITIZATION_TARGET_MISSING");
                }
                writeMarkdownAtNameUnlocked(target.noteId,name,markdown);
                return name;
            }
        }
    }

    VaultSelection replaceSelection(VaultSelection selection,String markdown) throws Exception {
        if(selection==null)throw new IllegalArgumentException("VAULT_SELECTION_REQUIRED");
        byte[] bytes=markdown.getBytes(StandardCharsets.UTF_8);
        if(selection.groupManaged){
            if(authorityContext==null||android.os.Build.VERSION.SDK_INT<27)
                throw new IOException("GROUP_VAULT_REQUIRES_API_27");
            com.padnote.android.streaming.StreamingGroupStore.Snapshot committed=
                    new NoteGroupFacade(authorityContext).replaceVaultRevision(selection.noteId,
                            selection.revision,selection.digest,selection.materialId,bytes);
            if(committed==null||!selection.noteId.equals(committed.localId)
                    ||!selection.lineage.equals(committed.lineage))
                throw new IOException("GROUP_VAULT_COMMIT_IDENTITY_MISMATCH");
            GroupAuthorityBridge.VaultView view=GroupAuthorityBridge.vaultView(committed,selection.materialId);
            VaultNote row=new VaultNote(view.fileName,view.noteId,view.title,view.pageCount,
                    view.digitizedAt,view.sourceModifiedAt,committed.lineage,committed.revision,
                    committed.digest,view.materialId);
            return new VaultSelection(row,true,committed.lineage,committed.revision,committed.digest,
                    view.materialId,view.markdown);
        }
        replaceLegacySelection(selection.fileName,selection.markdown,markdown);
        VaultNote legacy=new VaultNote(selection.fileName,selection.noteId,selection.title,0,0,0);
        return new VaultSelection(legacy,false,null,null,null,null,bytes);
    }

    void deleteSelection(VaultSelection selection) throws Exception {
        if(selection==null)throw new IllegalArgumentException("VAULT_SELECTION_REQUIRED");
        if(selection.groupManaged){
            if(authorityContext==null||android.os.Build.VERSION.SDK_INT<27)
                throw new IOException("GROUP_VAULT_REQUIRES_API_27");
            new NoteGroupFacade(authorityContext).deleteVaultRevision(selection.noteId,
                    selection.revision,selection.digest,selection.materialId);
        } else deleteChecked(selection.fileName,selection.markdown);
    }

    /** Replaces an explicitly edited Markdown source without changing its filename. */
    void replaceRaw(String fileName, String markdown) throws Exception {
        replaceLegacySelection(fileName,null,markdown);
    }

    private void replaceLegacySelection(String fileName,byte[] expectedBytes,String markdown)throws Exception {
        try (LegacyGroupMutationLock.Lease mutationGate=LegacyGroupMutationLock.acquire()) {
            String noteId=noteIdForMutation(fileName);
            requireLegacyMutationAllowed(noteId);
            synchronized (GLOBAL_LOCK) {
                File target=checkedVaultFile(fileName);
                NoteStore.recoverAtomicFile(target,VaultStore::validateAnyMarkdown);
                if(!target.isFile())throw new IllegalArgumentException("格式笔记不存在");
                String existing=readBoundedUnlocked(fileName,16*1024*1024);
                byte[] existingBytes=existing.getBytes(StandardCharsets.UTF_8);
                if(expectedBytes!=null&&!MessageDigest.isEqual(sha256(expectedBytes),sha256(existingBytes)))
                    throw new IOException("BASE_CONTENT_CONFLICT");
                String expectedNoteId=frontValue(existing.substring(0,Math.min(existing.length(),4096)),"note-id");
                if(!noteId.equals(expectedNoteId))throw new IOException("VAULT_SOURCE_CHANGED");
                validateMarkdown(markdown,expectedNoteId);
                NoteStore.writeAtomic(target,markdown,value->validateMarkdown(value,expectedNoteId),
                        writeFaults,16*1024*1024);
            }
        }
    }

    @Override
    public String readBounded(String fileName, int maximumBytes) throws Exception {
        if(authorityContext!=null){
            byte[] virtual=GroupAuthorityBridge.readVirtualVault(authorityContext,fileName,maximumBytes);
            if(virtual!=null)return new String(virtual,StandardCharsets.UTF_8);
        }
        try(LegacyGroupMutationLock.Lease mutationGate=LegacyGroupMutationLock.acquire()){
            boolean requireVerifiedOwner=authorityContext!=null&&android.os.Build.VERSION.SDK_INT>=27;
            String owner=recoverVerifiedLegacyFamily(fileName,requireVerifiedOwner);
            if(requireVerifiedOwner&&owner==null)
                throw new IOException("GROUP_VAULT_STALE_LEGACY_SIDECAR");
            synchronized(GLOBAL_LOCK){return readBoundedUnlocked(fileName,maximumBytes);}
        }
    }

    private String readBoundedUnlocked(String fileName, int maximumBytes) throws Exception {
        if(authorityContext!=null){
            byte[] group=GroupAuthorityBridge.readVirtualVault(authorityContext,fileName,maximumBytes);
            if(group!=null)return new String(group,StandardCharsets.UTF_8);
        }
        File file = checkedVaultFile(fileName);
        NoteStore.recoverAtomicFile(file, VaultStore::validateAnyMarkdown);
        long size = file.length();
        if (maximumBytes < 0 || size > maximumBytes || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("格式笔记超出单项材料上限");
        }
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) size];
            int read = 0;
            while (read < buffer.length) {
                int chunk = input.read(buffer, read, buffer.length - read);
                if (chunk < 0) {
                    break;
                }
                read += chunk;
            }
            if (input.read() != -1) {
                throw new IllegalArgumentException("格式笔记在读取时变大，请重新选择");
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
    }

    /** Writes one digitized note atomically, then retires older files for this source id. */
    String write(String noteId, String title, int pageCount, long sourceModifiedAt,
                 List<String> pageSections) throws Exception {
        try (LegacyGroupMutationLock.Lease mutationGate=LegacyGroupMutationLock.acquire()) {
        requireLegacyMutationAllowed(noteId);
        synchronized (GLOBAL_LOCK) {
            return writeUnlocked(noteId, title, pageCount, sourceModifiedAt, pageSections);
        }
        }
    }

    private String writeUnlocked(String noteId, String title, int pageCount,
                                 long sourceModifiedAt, List<String> pageSections)
            throws Exception {
        if (noteId == null || !noteId.matches("[A-Za-z0-9_-]{1,160}")) {
            throw new IllegalArgumentException("noteId is required");
        }
        return writeMarkdownUnlocked(noteId,buildDigitizedMarkdown(noteId,title,pageCount,
                sourceModifiedAt,pageSections,System.currentTimeMillis()));
    }

    private String writeMarkdownUnlocked(String noteId,String markdown)throws Exception {
        if(noteId==null||!noteId.matches("[A-Za-z0-9_-]{1,160}"))
            throw new IllegalArgumentException("noteId is required");
        List<VaultNote> beforeCommit = legacyRowsForSourceUnlocked(noteId);
        String fileName = "note-" + stableNoteSuffix(noteId) + ".md";
        File target = new File(vaultDir, fileName);
        NoteStore.StoredFileValidator validator = value -> validateMarkdown(value, noteId);
        NoteStore.writeAtomic(target, markdown, validator, writeFaults,
                16 * 1024 * 1024);
        validateMarkdown(readBoundedUnlocked(fileName, 16 * 1024 * 1024), noteId);

        // The replacement is now durable and readable. Only now may an older
        // title-based or renamed file for this same source be removed.
        for (VaultNote existing : beforeCommit) {
            if (existing.noteId.equals(noteId) && !existing.fileName.equals(fileName)) {
                deleteOldFamily(existing.fileName);
            }
        }
        return fileName;
    }


    private static boolean safeFileName(String value) {
        return value.matches("[A-Za-z0-9_\\-\u4e00-\u9fff.]+") && !value.contains("..");
    }
    private void writeMarkdownAtNameUnlocked(String noteId,String fileName,String markdown)throws Exception {
        if(noteId==null||!noteId.matches("[A-Za-z0-9_-]{1,160}")||fileName==null||!safeFileName(fileName))
            throw new IOException("DIGITIZATION_TARGET_PATH_INVALID");
        File target=checkedVaultFile(fileName);
        NoteStore.StoredFileValidator validator=value->validateMarkdown(value,noteId);
        NoteStore.writeAtomic(target,markdown,validator,writeFaults,16*1024*1024);
        validateMarkdown(readBoundedUnlocked(fileName,16*1024*1024),noteId);
    }

    static String buildDigitizedMarkdown(String noteId,String title,int pageCount,long sourceModifiedAt,
                                         List<String> pageSections,long publishedAt)throws Exception {
        return buildDigitizedMarkdown(noteId,title,pageCount,sourceModifiedAt,pageSections,publishedAt,null);
    }

    static String buildDigitizedMarkdown(String noteId,String title,int pageCount,long sourceModifiedAt,
                                         List<String> pageSections,long publishedAt,String operationId)throws Exception {
        if(noteId==null||!noteId.matches("[A-Za-z0-9_-]{1,160}")||publishedAt<=0)
            throw new IllegalArgumentException("DIGITIZATION_MARKDOWN_IDENTITY_INVALID");
        if(operationId!=null&&!operationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("DIGITIZATION_OPERATION_ID_INVALID");
        String safeTitle=sanitizeTitle(title);
        StringBuilder markdown=new StringBuilder();
        markdown.append("---\n").append("title: ").append(oneLine(safeTitle)).append('\n')
                .append("note-id: ").append(oneLine(noteId)).append('\n')
                .append("pages: ").append(Math.max(0,pageCount)).append('\n')
                .append("digitized: ").append(digitizedDateFormat().format(new Date(publishedAt))).append('\n')
                .append("digitized-epoch: ").append(publishedAt).append('\n')
                .append("source-modified: ").append(Math.max(0L,sourceModifiedAt)).append('\n');
        if(operationId!=null)markdown.append("digitization-operation-id: ").append(operationId).append('\n');
        markdown.append("---\n\n").append("# ").append(oneLine(safeTitle)).append("\n\n");
        for(int index=0;index<pageSections.size();index++){
            String section=pageSections.get(index);
            if(section==null||section.trim().isEmpty())continue;
            markdown.append("## 第 ").append(index+1).append(" 页\n\n")
                    .append(section.trim()).append("\n\n");
        }
        return markdown.toString();
    }

    private static String digitizationOperationId(byte[] markdown){
        return markdown==null?null:digitizationOperationId(new String(markdown,StandardCharsets.UTF_8));
    }
    private static String digitizationOperationId(String markdown){
        if(markdown==null||!markdown.startsWith("---\n"))return null;
        String value=frontValue(markdown.substring(0,Math.min(markdown.length(),4096)),"digitization-operation-id");
        return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")?value:null;
    }

    private static SimpleDateFormat digitizedDateFormat(){
        SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format;
    }

    /** Checked UI delete; unlike the old compatibility method, every refusal is observable. */
    void deleteChecked(String fileName) throws Exception { deleteChecked(fileName,null); }

    private void deleteChecked(String fileName,byte[] expectedBytes) throws Exception {
        try (LegacyGroupMutationLock.Lease mutationGate=LegacyGroupMutationLock.acquire()) {
            String noteId=noteIdForMutation(fileName);
            requireLegacyMutationAllowed(noteId);
            synchronized (GLOBAL_LOCK) {
                File primary=checkedVaultFile(fileName);
                if(!primary.isFile())throw new IOException("VAULT_ENTRY_MISSING");
                if(expectedBytes!=null){
                    byte[] current=readBoundedUnlocked(fileName,16*1024*1024).getBytes(StandardCharsets.UTF_8);
                    if(!MessageDigest.isEqual(sha256(expectedBytes),sha256(current)))
                        throw new IOException("BASE_CONTENT_CONFLICT");
                }
                deleteFamily(fileName,true);
            }
        }
    }

    void delete(String fileName) {
        try { deleteChecked(fileName); }
        catch (Exception ignored) { /* Compatibility API; user-facing routes call deleteChecked. */ }
    }

    private String noteIdForMutation(String fileName) throws Exception {
        String noteId=recoverVerifiedLegacyFamily(fileName,true);
        if(noteId==null||!noteId.matches("[A-Za-z0-9_-]{1,160}"))
            throw new IOException("VAULT_SOURCE_ID_INVALID");
        return noteId;
    }

    /**
     * Recovers a sidecar-only family only after every readable owner agrees and
     * the complete marker lookup proves the family is legacy. Callers hold the
     * shared legacy mutation gate; GLOBAL_LOCK protects the atomic-file repair.
     */
    private String recoverVerifiedLegacyFamily(String fileName,boolean requireOwner)throws Exception {
        String owner=peekFamilyOwner(fileName);
        if(requireOwner&&owner==null)return null;
        boolean markerAuthority=authorityContext!=null&&android.os.Build.VERSION.SDK_INT>=27;
        if(markerAuthority&&(owner==null||!isDefinitelyLegacyOwner(owner,false)))return null;
        synchronized(GLOBAL_LOCK){
            File target=checkedVaultFile(fileName);
            NoteStore.recoverAtomicFile(target,VaultStore::validateAnyMarkdown);
            String primaryOwner=readOwnerHint(fileName);
            if(owner!=null){
                if(primaryOwner==null||!owner.equals(primaryOwner))
                    throw new IOException("VAULT_SOURCE_CHANGED");
            }else if(requireOwner){
                return null;
            }
            return owner!=null?owner:primaryOwner;
        }
    }

    private void requireLegacyMutationAllowed(String noteId) throws Exception {
        if (authorityContext != null)
            NoteStore.requireNoteMaterialAccess(authorityContext, noteId);
    }

    boolean hasNoteForSource(String noteId) {
        for (VaultNote note : list()) {
            if (note.noteId.equals(noteId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Embeds an existing text flow into the digitized document. Markdown flows
     * are already in the target format and pass through verbatim; LaTeX flows
     * are wrapped in display math so they compile like any other formula.
     */
    static String embedTextFlow(TextFlow flow) {
        String source = flow.source == null ? "" : flow.source.trim();
        if (source.isEmpty()) {
            return "";
        }
        if (flow.format == NoteTextBox.Format.MARKDOWN) {
            return source;
        }
        String bare = CompiledTextWebView.normalizeLatexSource(source);
        if (bare.isEmpty()) {
            return "";
        }
        return "\\[\n" + bare + "\n\\]";
    }

    static String sanitizeTitle(String title) {
        String cleaned = (title == null ? "" : title).trim()
                .replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            cleaned = "未命名笔记";
        }
        if (cleaned.length() > 60) {
            cleaned = cleaned.substring(0, 60).trim();
        }
        return cleaned;
    }

    private static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    private static String hexDigest(byte[] digest){
        StringBuilder out=new StringBuilder(digest.length*2);
        for(byte value:digest)out.append(String.format(Locale.ROOT,"%02x",value&255));
        return out.toString();
    }

    private static String stableNoteSuffix(String noteId) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(noteId.getBytes(StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < digest.length; index++) {
            value.append(String.format(Locale.ROOT, "%02x", digest[index] & 255));
        }
        return value.toString();
    }

    private static void validateMarkdown(String value, String expectedNoteId) {
        if (value == null || !value.startsWith("---\n")) {
            throw new IllegalArgumentException("格式笔记缺少元数据");
        }
        String noteId = frontValue(value.substring(0, Math.min(value.length(), 4096)), "note-id");
        if (!expectedNoteId.equals(noteId)) {
            throw new IllegalArgumentException("格式笔记来源 ID 不一致");
        }
    }

    private static void validateAnyMarkdown(String value) {
        if (value == null || !value.startsWith("---\n")) {
            throw new IllegalArgumentException("格式笔记缺少元数据");
        }
        String noteId = frontValue(value.substring(0, Math.min(value.length(), 4096)), "note-id");
        if (!noteId.matches("[A-Za-z0-9_-]{1,160}")) {
            throw new IllegalArgumentException("格式笔记来源 ID 无效");
        }
    }

    private void recoverVaultFiles(java.util.Set<String> verifiedGroupOwners,boolean strict)throws Exception {
        File[] files=vaultDir.listFiles();
        if(files==null){if(strict)throw new IOException("VAULT_DIRECTORY_UNREADABLE");return;}
        java.util.TreeSet<String> names=new java.util.TreeSet<>();
        for(File file:files){
            String name=file.getName();int marker=name.indexOf(".md");
            if(marker>=0)names.add(name.substring(0,marker+3));
        }
        for(String name:names){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("vault_recovery_cancelled");
            try(LegacyGroupMutationLock.Lease mutationGate=LegacyGroupMutationLock.acquire()){
                String owner=peekFamilyOwner(name);
                if(owner==null){if(strict)throw new IOException("VAULT_OWNER_UNVERIFIED");continue;}
                if(verifiedGroupOwners!=null&&verifiedGroupOwners.contains(owner))continue;
                if(!isDefinitelyLegacyOwner(owner,strict))continue;
                synchronized(GLOBAL_LOCK){
                    File checked=checkedVaultFile(name);
                    NoteStore.recoverAtomicFile(checked,VaultStore::validateAnyMarkdown);
                }
            }catch(InterruptedException cancelled){throw cancelled;}
            catch(Exception unverifiedOrDamaged){if(strict)throw unverifiedOrDamaged;}
        }
    }

    /** Read-only bounded owner discovery across a primary file and its atomic candidates. */
    private String peekFamilyOwner(String fileName)throws Exception {
        String found=null;
        for(String candidate:new String[]{fileName,fileName+".tmp",fileName+".bak"}){
            String owner=readOwnerHint(candidate);
            if(owner==null)continue;
            if(found!=null&&!found.equals(owner))throw new IOException("VAULT_OWNER_AMBIGUOUS");
            found=owner;
        }
        return found;
    }


    /** Existing physical families for this already-guarded legacy owner, with GLOBAL_LOCK held. */
    private List<VaultNote> legacyRowsForSourceUnlocked(String noteId) throws Exception {
        List<VaultNote> rows=new ArrayList<>();
        File[] files=vaultDir.listFiles((dir,name)->name.endsWith(".md"));
        if(files==null)throw new IOException("VAULT_DIRECTORY_UNREADABLE");
        java.util.Arrays.sort(files);
        for(File file:files){
            File checked=checkedVaultFile(file.getName());
            if(!checked.isFile()||checked.length()>16L*1024*1024)continue;
            String head=readFileHead(checked,4096);
            if(!head.startsWith("---\n")||!head.contains("\n---\n"))continue;
            if(!noteId.equals(frontValue(head,"note-id")))continue;
            rows.add(new VaultNote(file.getName(),noteId,frontValue(head,"title"),
                    parseInt(frontValue(head,"pages"),0),parseLong(frontValue(head,"digitized-epoch"),0L),
                    parseLong(frontValue(head,"source-modified"),0L)));
        }
        return rows;
    }

    private void deleteOldFamily(String fileName) throws Exception {
        deleteFamily(fileName, true);
    }

    private void deleteFamily(String fileName, boolean requireDeletion) throws Exception {
        checkedVaultFile(fileName);
        for (String suffix : new String[]{"", ".tmp", ".bak"}) {
            File file = checkedVaultFile(fileName + suffix);
            if (file.exists() && !file.delete() && requireDeletion) {
                throw new java.io.IOException("新格式笔记已保存，但无法清理旧来源文件");
            }
        }
    }

    private File checkedVaultFile(String fileName) throws Exception {
        if (fileName == null || fileName.isEmpty()
                || fileName.getBytes(StandardCharsets.UTF_8).length > 240
                || fileName.equals(".") || fileName.equals("..") || fileName.contains("..")
                || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0
                || fileName.indexOf('\0') >= 0 || !fileName.endsWith(".md")
                        && !fileName.endsWith(".md.tmp") && !fileName.endsWith(".md.bak")) {
            throw new IllegalArgumentException("知识库文件名无效");
        }
        File root = vaultDir.getCanonicalFile();
        File candidate = new File(root, fileName);
        File canonical = candidate.getCanonicalFile();
        if (!root.equals(canonical.getParentFile())
                || !candidate.getAbsoluteFile().equals(canonical)) {
            throw new IllegalArgumentException("知识库文件路径无效");
        }
        return candidate;
    }

    private static String oneLine(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String readFileHead(File file, int limit) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[Math.min(limit, (int) file.length())];
            int read = 0;
            while (read < buffer.length) {
                int chunk = input.read(buffer, read, buffer.length - read);
                if (chunk < 0) {
                    break;
                }
                read += chunk;
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
    }

    /** Front-matter value for {@code key}, or "" — also used by the vault tools. */
    static String frontValue(String head, String key) {
        boolean inFrontMatter = false;
        for (String line : head.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.equals("---")) {
                if (inFrontMatter) {
                    break;
                }
                inFrontMatter = true;
                continue;
            }
            if (!inFrontMatter) {
                continue;
            }
            int separator = trimmed.indexOf(':');
            if (separator > 0 && key.equals(trimmed.substring(0, separator).trim())) {
                return trimmed.substring(separator + 1).trim();
            }
        }
        return "";
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception malformed) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception malformed) {
            return fallback;
        }
    }
}
