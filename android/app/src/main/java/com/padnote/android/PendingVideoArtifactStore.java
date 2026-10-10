package com.padnote.android;

import android.content.Context;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import com.padnote.android.streaming.LegacyFileCapture;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Durable, bounded operation records for verified video artifacts awaiting explicit review. */
@android.annotation.TargetApi(27)
public final class PendingVideoArtifactStore {
    private static final Object LOCK = new Object();
    private static final Set<String> NOT_DURABLE = new HashSet<>();
    private static final int MAX_RECORDS = 24;
    private static final long MAX_BYTES = 512L * 1024L * 1024L;
    private static final long VIDEO_CAP = 100L * 1024L * 1024L;
    private static final String AVAILABLE="AVAILABLE", INCOMPLETE="DOWNLOAD_INCOMPLETE",
            NEEDS_REVIEW="NEEDS_REVIEW", DELETE_INCOMPLETE="DELETE_INCOMPLETE";
    private final Context context;
    private final File root;

    public static final class Entry {
        public final String operationId;
        public final VideoAttachmentStore.Attachment attachment;
        public final String baseLineage, baseRevision, baseDigest;
        public final long createdAt;
        public final File videoFile, directory;
        public final String state;
        public final boolean available, hasUnknownChild, durable;
        private Entry(String operationId, VideoAttachmentStore.Attachment attachment,
                String baseLineage, String baseRevision, String baseDigest, long createdAt,
                File directory, File videoFile, String state, boolean available, boolean hasUnknownChild,
                boolean durable) {
            this.operationId=operationId;this.attachment=attachment;this.baseLineage=baseLineage;
            this.baseRevision=baseRevision;this.baseDigest=baseDigest;this.createdAt=createdAt;
            this.directory=directory;this.videoFile=videoFile;this.state=state;this.available=available;
            this.hasUnknownChild=hasUnknownChild;this.durable=durable;
        }
    }

    /** Narrow UI query for the one interrupted-delete state that supports resume-cleanup. */
    static boolean isDeleteIncomplete(Entry entry) {
        return entry != null && DELETE_INCOMPLETE.equals(entry.state);
    }

    public PendingVideoArtifactStore(Context context) {
        if (context == null) throw new IllegalArgumentException("APPLICATION_CONTEXT_REQUIRED");
        Context app=context.getApplicationContext();this.context=app==null?context:app;
        this.root=new File(this.context.getFilesDir(),"pending-video-artifacts");
    }

    /** Reserve bounded space and persist all task/base metadata before network download starts. */
    public Entry reserve(VideoAttachmentStore.Attachment attachment,
            String baseLineage, String baseRevision, String baseDigest) throws IOException {
        if (attachment == null || attachment.sizeBytes <= 0 || attachment.sizeBytes > VIDEO_CAP
                || !canonicalUuid(attachment.id) || !safeNoteId(attachment.noteId))
            throw new IOException("PENDING_VIDEO_ARGUMENT_INVALID");
        try { VideoAttachmentStore.Attachment.parse(attachment.json()); }
        catch (Exception invalid) { throw new IOException("PENDING_VIDEO_METADATA_INVALID",invalid); }
        if ((baseLineage==null)!=(baseRevision==null)||(baseRevision==null)!=(baseDigest==null)
                ||baseLineage!=null&&(!canonicalUuid(baseLineage)||!canonicalUuid(baseRevision)
                ||!baseDigest.matches("[0-9a-fA-F]{64}")))
            throw new IOException("PENDING_VIDEO_BASE_INVALID");
        synchronized (LOCK) {
            ensureRoot();List<Entry> existing=listLocked(null);long total=attachment.sizeBytes;
            for(Entry entry:existing)if(entry.attachment!=null)total+=entry.attachment.sizeBytes;
            if(existing.size()>=MAX_RECORDS||total>MAX_BYTES)throw new IOException("PENDING_VIDEO_CAPACITY_REACHED");
            File staging=new File(root,".creating-"+attachment.id), published=operationDirectory(attachment.id);
            if(staging.exists()||published.exists())throw new IOException("PENDING_VIDEO_OPERATION_EXISTS");
            try {
                Os.mkdir(staging.getAbsolutePath(),0700);verifyDirectory(staging);
                JSONObject value=new JSONObject().put("schema_version",2).put("operation_id",attachment.id)
                        .put("note_id",attachment.noteId).put("base_lineage",baseLineage==null?JSONObject.NULL:baseLineage)
                        .put("base_revision",baseRevision==null?JSONObject.NULL:baseRevision)
                        .put("base_digest",baseDigest==null?JSONObject.NULL:baseDigest)
                        .put("created_at",System.currentTimeMillis()).put("size",attachment.sizeBytes)
                        .put("sha256",attachment.sha256.toLowerCase(java.util.Locale.ROOT))
                        .put("attachment",attachment.json());
                writeNewRecord(staging,value.toString().getBytes(StandardCharsets.UTF_8));
                syncDirectory(staging);
                if(published.exists()||!staging.renameTo(published))throw new IOException("PENDING_VIDEO_PUBLISH_RESERVATION");
                NOT_DURABLE.add(attachment.id);
                syncDirectory(root);
                NOT_DURABLE.remove(attachment.id);
                return readEntry(attachment.id,published,false);
            } catch(Exception failure) {
                // A crash before the final directory rename leaves `.creating-<uuid>`; listLocked
                // can promote a complete reservation. Never recursively delete unverified children.
                if(staging.exists())try{cleanupKnownDirectory(staging);}catch(Exception cleanup){failure.addSuppressed(cleanup);}
                if(failure instanceof IOException)throw(IOException)failure;
                throw new IOException("PENDING_VIDEO_RESERVATION_WRITE",failure);
            }
        }
    }

    /** Directory passed to the verifier so its temporary download remains under the reservation. */
    public File downloadDirectory(Entry entry)throws IOException {
        synchronized(LOCK){Entry current=verifiedCurrent(entry);if(current.state.equals(DELETE_INCOMPLETE)||current.attachment==null)throw new IOException("PENDING_VIDEO_ENTRY_INVALID");return current.directory;}
    }

    /** Promote a verifier-complete file; a crash before this call is recoverable by checking .part bytes. */
    public Entry complete(Entry entry,File verifiedDownload)throws IOException {
        if(verifiedDownload==null)throw new IOException("PENDING_VIDEO_DOWNLOAD_REQUIRED");
        synchronized(LOCK){
            Entry current=verifiedCurrent(entry);if(current.hasUnknownChild)throw new IOException("PENDING_VIDEO_UNKNOWN_CHILD");
            LegacyFileCapture.Result source=LegacyFileCapture.inspect(context,verifiedDownload,VIDEO_CAP);
            if(source.size!=current.attachment.sizeBytes||!source.sha256.equalsIgnoreCase(current.attachment.sha256))
                throw new IOException("PENDING_VIDEO_SOURCE_MISMATCH");
            ensureWithinOperation(current.directory,verifiedDownload);
            File payload=new File(current.directory,"payload.mp4");
            if(!verifiedDownload.equals(payload)){
                if(payload.exists()||!verifiedDownload.renameTo(payload))throw new IOException("PENDING_VIDEO_PAYLOAD_COMMIT");
            }
            ownedRegularState(payload);NOT_DURABLE.add(current.operationId);
            Entry result=readEntry(current.operationId,current.directory,false);
            if(!result.available)throw new IOException("PENDING_VIDEO_PAYLOAD_NOT_RECOVERABLE");
            return result;
        }
    }

    /** Remove only an interrupted reservation with no verified payload. */
    public void cancelIncomplete(Entry entry)throws IOException {
        synchronized(LOCK){Entry current=verifiedCurrent(entry);if(current.available)throw new IOException("PENDING_VIDEO_ALREADY_AVAILABLE");removeLocked(current);}
    }

    public List<Entry> listAll() throws IOException { synchronized(LOCK){return listLocked(null);} }
    public Entry findOperation(String operationId)throws IOException {
        if(!canonicalUuid(operationId))throw new IOException("PENDING_VIDEO_ID_INVALID");
        synchronized(LOCK){
            for(Entry entry:listLocked(null))if(operationId.equals(entry.operationId))return entry;
            return null;
        }
    }
    public List<Entry> listForNote(String noteId) throws IOException {
        if(noteId==null||noteId.isEmpty())throw new IOException("PENDING_VIDEO_NOTE_REQUIRED");
        synchronized (LOCK) { return listLocked(noteId); }
    }

    public Entry refresh(Entry entry)throws IOException {
        synchronized(LOCK){return verifiedCurrent(entry);}
    }

    public void remove(Entry entry) throws IOException {
        if(entry==null)throw new IOException("PENDING_VIDEO_ENTRY_REQUIRED");
        synchronized (LOCK) { removeLocked(verifiedCurrent(entry)); }
    }

    private void removeLocked(Entry current)throws IOException {
        if(current.attachment==null||current.hasUnknownChild)throw new IOException("PENDING_VIDEO_UNKNOWN_CHILD");
        File directory=current.directory;
        String name=directory.getName();
        if(name.startsWith(".deleting-")){finishDelete(directory);return;}
        File tombstone=new File(root,".deleting-"+current.operationId);
        if(tombstone.exists()||!directory.renameTo(tombstone))throw new IOException("PENDING_VIDEO_DELETE_MARKER");
        syncDirectory(root);
        finishDelete(tombstone);
    }

    private void finishDelete(File directory)throws IOException {
        StructStat directoryIdentity=ownedDirectoryState(directory);
        File[] children=directory.listFiles();if(children==null)throw new IOException("PENDING_VIDEO_DIRECTORY_UNREADABLE");
        File record=null;StructStat recordIdentity=null;List<File> deletable=new ArrayList<>();
        List<StructStat> deletableIdentities=new ArrayList<>();
        // Preflight every recognized child before mutating the tombstone. In particular, keep
        // record.json until all payloads/temps are safely removed so a foreign or malformed
        // sibling cannot erase the operation's recovery metadata.
        for(File child:children){
            String name=child.getName();
            if(!isKnownChild(name))continue;
            StructStat identity=ownedRegularState(child);
            if("record.json".equals(name)){record=child;recordIdentity=identity;}
            else{deletable.add(child);deletableIdentities.add(identity);}
        }
        for(int i=0;i<deletable.size();i++)unlinkOwned(deletable.get(i),deletableIdentities.get(i));
        children=directory.listFiles();if(children==null)throw new IOException("PENDING_VIDEO_DIRECTORY_UNREADABLE");
        for(File child:children){
            if(record==null||!"record.json".equals(child.getName())
                    ||!sameOwnedObject(recordIdentity,ownedRegularState(child)))
                throw new IOException("PENDING_VIDEO_DELETE_UNKNOWN_CHILD");
        }
        if(record!=null)unlinkOwned(record,recordIdentity);
        children=directory.listFiles();if(children==null||children.length!=0)throw new IOException("PENDING_VIDEO_DELETE_UNKNOWN_CHILD");
        removeOwnedEmptyDirectory(directory,directoryIdentity,"PENDING_VIDEO_DELETE_DIRECTORY",false);
        syncDirectory(root);
    }

    private List<Entry> listLocked(String noteId)throws IOException {
        ensureRoot();File[] children=root.listFiles();if(children==null)throw new IOException("PENDING_VIDEO_DIRECTORY_UNREADABLE");
        if(children.length>MAX_RECORDS*4+64)throw new IOException("PENDING_VIDEO_CHILD_LIMIT");
        List<Entry> result=new ArrayList<>();long bytes=0;int records=0;
        for(File child:children){String name=child.getName();
            if(name.startsWith(".deleting-")&&canonicalUuid(name.substring(10))){
                try{finishDelete(child);}catch(IOException incomplete){
                    Entry damaged=readEntrySafe(name.substring(10),child,DELETE_INCOMPLETE,false);
                    if(noteId==null||damaged.attachment!=null&&noteId.equals(damaged.attachment.noteId))result.add(damaged);
                }
                continue;
            }
            boolean creating=name.startsWith(".creating-");String id=creating?name.substring(10):name;
            if(!canonicalUuid(id))continue; // preserve unknown root children without letting one hide valid records.
            records++;
            if(records>MAX_RECORDS){
                result.add(new Entry(id,null,null,null,null,0,child,null,NEEDS_REVIEW,false,true,false));
                continue;
            }
            try{
                verifyDirectory(child);
                if(creating&&!new File(child,"record.json").exists()){
                    if(cleanupUnpublishedReservation(child)){syncDirectory(root);continue;}
                    result.add(new Entry(id,null,null,null,null,0,child,null,NEEDS_REVIEW,false,true,false));
                    continue;
                }
                if(creating){File target=operationDirectory(id);if(target.exists()||!child.renameTo(target))continue;syncDirectory(root);child=target;}
                Entry entry=readEntry(id,child,false);
                if(entry.attachment!=null)bytes+=entry.attachment.sizeBytes;
                if(bytes>MAX_BYTES)throw new IOException("PENDING_VIDEO_TOTAL_LIMIT");
                if(noteId==null||entry.attachment!=null&&noteId.equals(entry.attachment.noteId))result.add(entry);
            }catch(IOException invalid){
                Entry damaged=readEntrySafe(id,child,NEEDS_REVIEW,true);
                if(noteId==null||damaged.attachment!=null&&noteId.equals(damaged.attachment.noteId))result.add(damaged);
            }
        }
        result.sort(Comparator.comparingLong((Entry e)->e.createdAt).reversed());return java.util.Collections.unmodifiableList(result);
    }

    private Entry readEntrySafe(String id,File directory,String state,boolean unknown)throws IOException {
        try{return readEntry(id,directory,unknown,state);}catch(IOException invalid){
            return new Entry(id,null,null,null,null,0,directory,null,state,false,true,false);
        }
    }

    private Entry readEntry(String id,File directory,boolean forceUnknown)throws IOException {
        return readEntry(id,directory,forceUnknown,null);
    }

    private Entry readEntry(String id,File directory,boolean forceUnknown,String forcedState)throws IOException {
        if(!canonicalUuid(id))throw new IOException("PENDING_VIDEO_ID_INVALID");verifyDirectory(directory);
        // Payload data must be synchronized through the same stable no-follow descriptor that
        // hashes it before a successful parent-directory sync can make it durable.
        File manifest=new File(directory,"record.json");LegacyFileCapture.Result raw=LegacyFileCapture.read(context,manifest,16384);
        final JSONObject value;try{value=new JSONObject(StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(raw.bytes)).toString());}
        catch(Exception invalid){throw new IOException("PENDING_VIDEO_RECORD_INVALID",invalid);}
        final VideoAttachmentStore.Attachment attachment;
        try{
            if(value.getInt("schema_version")!=2||!id.equals(value.getString("operation_id")))throw new IOException("PENDING_VIDEO_RECORD_INVALID");
            String noteId=value.getString("note_id"),sha=value.getString("sha256");long size=value.getLong("size"),created=value.getLong("created_at");
            if(size<=0||size>VIDEO_CAP||!sha.matches("[0-9a-f]{64}"))throw new IOException("PENDING_VIDEO_RECORD_INVALID");
            attachment=VideoAttachmentStore.Attachment.parse(value.getJSONObject("attachment"));
            if(!id.equals(attachment.id)||!noteId.equals(attachment.noteId)||size!=attachment.sizeBytes||!sha.equalsIgnoreCase(attachment.sha256))throw new IOException("PENDING_VIDEO_RECORD_BINDING_INVALID");
            String lineage=nullable(value,"base_lineage"),revision=nullable(value,"base_revision"),digest=nullable(value,"base_digest");
            if((lineage==null)!=(revision==null)||(revision==null)!=(digest==null))throw new IOException("PENDING_VIDEO_BASE_INVALID");
            if(lineage!=null&&(!canonicalUuid(lineage)||!canonicalUuid(revision)||!digest.matches("[0-9a-fA-F]{64}")))throw new IOException("PENDING_VIDEO_BASE_INVALID");
            File[] children=directory.listFiles();if(children==null)throw new IOException("PENDING_VIDEO_DIRECTORY_UNREADABLE");
            boolean unknown=forceUnknown;List<File> payloads=new ArrayList<>();
            for(File child:children){String childName=child.getName();
                if("record.json".equals(childName)||childName.startsWith(".record-")){
                    if(!"record.json".equals(childName)){
                        if(!isRecordTemp(childName))throw new IOException("PENDING_VIDEO_UNKNOWN_CHILD");
                        ownedRegularState(child);
                    }
                }else if("payload.mp4".equals(childName)||isDownloaderPart(childName))payloads.add(child);
                else unknown=true;
            }
            File matched=null;int exact=0;
            boolean payloadSynced=false;
            for(File candidate:payloads){
                LegacyFileCapture.Result actual;boolean fileSyncSucceeded;
                try{actual=LegacyFileCapture.inspectAndSync(context,candidate,VIDEO_CAP);fileSyncSucceeded=true;}
                catch(Exception syncUncertain){
                    fileSyncSucceeded=false;
                    // A sync/identity failure is not a reason to destroy still-readable bytes.
                    // Reverify them for export, but preserve the uncertain durability state.
                    try{actual=LegacyFileCapture.inspect(context,candidate,VIDEO_CAP);}
                    catch(Exception incompleteOrReplaced){continue;}
                }
                if(actual.size==size&&actual.sha256.equalsIgnoreCase(sha)){
                    matched=candidate;exact++;
                    if(exact==1)payloadSynced=fileSyncSucceeded;else payloadSynced=false;
                }
            }
            boolean available=exact==1;String state=forcedState!=null?forcedState:unknown?NEEDS_REVIEW:available?AVAILABLE:exact>1?NEEDS_REVIEW:INCOMPLETE;
            boolean directoriesSynced=false;
            try{syncDirectory(directory);syncDirectory(root);directoriesSynced=true;}
            catch(IOException stillUncertain){/* preserve bytes; durability remains unconfirmed */}
            boolean durable=directoriesSynced&&(!available||payloadSynced);
            if(durable)NOT_DURABLE.remove(id);else NOT_DURABLE.add(id);
            return new Entry(id,attachment,nullable(value,"base_lineage"),nullable(value,"base_revision"),
                    nullable(value,"base_digest"),value.getLong("created_at"),directory,available?matched:null,
                    state,available,unknown||exact>1,durable);
        }catch(IOException failure){throw failure;}catch(Exception invalid){throw new IOException("PENDING_VIDEO_RECORD_INVALID",invalid);}
    }

    private Entry verifiedCurrent(Entry entry)throws IOException {
        if(entry==null||!canonicalUuid(entry.operationId))throw new IOException("PENDING_VIDEO_ENTRY_REQUIRED");
        ensureRoot();
        File directory=operationDirectory(entry.operationId);
        if(!directory.exists()){
            File deleting=new File(root,".deleting-"+entry.operationId);
            if(deleting.exists())return readEntrySafe(entry.operationId,deleting,DELETE_INCOMPLETE,false);
            throw new IOException("PENDING_VIDEO_ENTRY_MISSING");
        }
        Entry current=readEntry(entry.operationId,directory,false);
        if(entry.attachment!=null&&(current.attachment==null||!sameAttachment(entry.attachment,current.attachment)))
            throw new IOException("PENDING_VIDEO_IDENTITY_CHANGED");
        return current;
    }

    private void ensureRoot()throws IOException {
        File parent=context.getFilesDir();if(parent==null)throw new IOException("PENDING_VIDEO_FILES_ROOT_MISSING");
        try {
            File canonicalParent=parent.getCanonicalFile();if(!root.getAbsoluteFile().getParentFile().getCanonicalFile().equals(canonicalParent))throw new IOException("PENDING_VIDEO_ROOT_SCOPE");
            if(!root.exists())Os.mkdir(root.getAbsolutePath(),0700);
            StructStat st=Os.lstat(root.getAbsolutePath());if(!OsConstants.S_ISDIR(st.st_mode)||OsConstants.S_ISLNK(st.st_mode)||st.st_uid!=Process.myUid())throw new IOException("PENDING_VIDEO_ROOT_UNSAFE");
            if(!root.getCanonicalFile().equals(new File(canonicalParent,"pending-video-artifacts")))throw new IOException("PENDING_VIDEO_ROOT_SCOPE");
            if((st.st_mode&0077)!=0)Os.chmod(root.getAbsolutePath(),0700);
            // The operation root itself must survive a restart before its child record can be
            // considered durable. Sync the app-files parent as well as each operation/root dir.
            syncDirectory(canonicalParent);
        }catch(ErrnoException error){throw new IOException("PENDING_VIDEO_ROOT_IO",error);}catch(IOException error){throw error;}catch(Exception error){throw new IOException("PENDING_VIDEO_ROOT_IO",error);}
    }

    private void writeNewRecord(File directory,byte[] bytes)throws IOException {
        if(bytes.length>16384)throw new IOException("PENDING_VIDEO_RECORD_LIMIT");
        File record=new File(directory,"record.json"),temp=File.createTempFile(".record-",".pending",directory);
        StructStat identity=ownedRegularState(temp);
        try(FileOutputStream out=new FileOutputStream(temp,false)){out.write(bytes);out.flush();out.getFD().sync();}
        if(record.exists()||!temp.renameTo(record))throw new IOException("PENDING_VIDEO_RECORD_COMMIT");
        ownedRegularState(record);syncDirectory(directory);
    }
    private void syncDirectory(File directory)throws IOException {
        java.io.FileDescriptor fd=null;try{fd=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);Os.fsync(fd);}
        catch(ErrnoException error){throw new IOException("PENDING_VIDEO_SYNC",error);}finally{if(fd!=null)try{Os.close(fd);}catch(ErrnoException error){throw new IOException("PENDING_VIDEO_SYNC_CLOSE",error);}}
    }
    private StructStat ownedDirectoryState(File directory)throws IOException {
        try{StructStat state=Os.lstat(directory.getAbsolutePath());if(!OsConstants.S_ISDIR(state.st_mode)||OsConstants.S_ISLNK(state.st_mode)||state.st_uid!=Process.myUid()||(state.st_mode&0077)!=0)throw new IOException("PENDING_VIDEO_DIRECTORY_UNSAFE");return state;}
        catch(ErrnoException error){throw new IOException("PENDING_VIDEO_DIRECTORY_IDENTITY",error);}
    }
    private void verifyDirectory(File directory)throws IOException {ownedDirectoryState(directory);}
    private static boolean sameOwnedDirectory(StructStat first,StructStat second){
        return first!=null&&second!=null&&first.st_dev==second.st_dev&&first.st_ino==second.st_ino
                &&first.st_uid==second.st_uid&&first.st_nlink==second.st_nlink
                &&(first.st_mode&OsConstants.S_IFMT)==(second.st_mode&OsConstants.S_IFMT)
                &&(first.st_mode&0077)==0&&(second.st_mode&0077)==0;
    }
    /** Remove only the same private directory captured before cleanup, after proving it is empty. */
    private void removeOwnedEmptyDirectory(File directory,StructStat expected,String failureCode,boolean allowMissing)throws IOException {
        StructStat current;
        try{current=ownedDirectoryState(directory);}
        catch(IOException error){if(allowMissing&&hasErrno(error,OsConstants.ENOENT))return;throw error;}
        if(!sameOwnedDirectory(expected,current))throw new IOException("PENDING_VIDEO_DIRECTORY_REPLACED");
        File[] children=directory.listFiles();
        if(children==null||children.length!=0)throw new IOException("PENDING_VIDEO_UNKNOWN_CHILD");
        try{current=ownedDirectoryState(directory);}
        catch(IOException error){if(allowMissing&&hasErrno(error,OsConstants.ENOENT))return;throw error;}
        if(!sameOwnedDirectory(expected,current))throw new IOException("PENDING_VIDEO_DIRECTORY_REPLACED");
        try{Os.remove(directory.getAbsolutePath());}
        catch(ErrnoException error){if(allowMissing&&error.errno==OsConstants.ENOENT)return;throw new IOException(failureCode,error);}
    }
    private static boolean hasErrno(IOException error,int errno){
        Throwable cause=error.getCause();return cause instanceof ErrnoException&&((ErrnoException)cause).errno==errno;
    }
    private StructStat ownedRegularState(File file)throws IOException {
        try{StructStat state=Os.lstat(file.getAbsolutePath());if(!OsConstants.S_ISREG(state.st_mode)||OsConstants.S_ISLNK(state.st_mode)||state.st_uid!=Process.myUid()||state.st_nlink!=1||(state.st_mode&0077)!=0)throw new IOException("PENDING_VIDEO_FILE_UNSAFE");return state;}
        catch(ErrnoException error){throw new IOException("PENDING_VIDEO_FILE_IDENTITY",error);}
    }
    private static boolean sameOwnedObject(StructStat first,StructStat second){
        return first!=null&&second!=null&&first.st_dev==second.st_dev&&first.st_ino==second.st_ino
                &&first.st_uid==second.st_uid&&first.st_nlink==1&&second.st_nlink==1
                &&(first.st_mode&OsConstants.S_IFMT)==(second.st_mode&OsConstants.S_IFMT);
    }
    private void unlinkOwned(File file)throws IOException {unlinkOwned(file,null);}
    private void unlinkOwned(File file,StructStat expected)throws IOException {
        StructStat current=ownedRegularState(file);
        if(expected!=null&&!sameOwnedObject(expected,current))throw new IOException("PENDING_VIDEO_FILE_REPLACED");
        try{Os.remove(file.getAbsolutePath());}catch(ErrnoException error){if(error.errno!=OsConstants.ENOENT)throw new IOException("PENDING_VIDEO_DELETE",error);}
    }
    private void cleanupKnownDirectory(File directory)throws IOException {
        StructStat directoryIdentity=ownedDirectoryState(directory);File[] children=directory.listFiles();if(children==null)throw new IOException("PENDING_VIDEO_DIRECTORY_UNREADABLE");
        for(File child:children){if(isKnownChild(child.getName()))unlinkOwned(child);}
        children=directory.listFiles();if(children==null||children.length!=0)throw new IOException("PENDING_VIDEO_UNKNOWN_CHILD");
        removeOwnedEmptyDirectory(directory,directoryIdentity,"PENDING_VIDEO_DELETE_DIRECTORY",true);
    }
    /**
     * A reservation directory without its published record cannot contain a downloaded result:
     * reserve() returns only after record rename and parent sync, and downloading starts later.
     * Remove only its generated record-temp files. Any other child keeps the directory visible.
     */
    private boolean cleanupUnpublishedReservation(File directory)throws IOException {
        StructStat directoryIdentity=ownedDirectoryState(directory);File[] children=directory.listFiles();
        if(children==null)throw new IOException("PENDING_VIDEO_DIRECTORY_UNREADABLE");
        for(File child:children)if(!isRecordTemp(child.getName()))return false;
        for(File child:children)unlinkOwned(child);
        removeOwnedEmptyDirectory(directory,directoryIdentity,"PENDING_VIDEO_RESERVATION_RECOVERY",true);
        return true;
    }
    private void ensureWithinOperation(File directory,File file)throws IOException {
        try{String d=directory.getCanonicalPath()+File.separator;String f=file.getCanonicalPath();if(!f.startsWith(d))throw new IOException("PENDING_VIDEO_DOWNLOAD_SCOPE");}
        catch(IOException error){throw error;}catch(Exception error){throw new IOException("PENDING_VIDEO_DOWNLOAD_SCOPE",error);}
    }
    static boolean sameAttachment(VideoAttachmentStore.Attachment a,VideoAttachmentStore.Attachment b){
        try{return a.id.equals(b.id)&&a.noteId.equals(b.noteId)&&a.sourceRevision==b.sourceRevision
                &&eq(a.sourceBundleSha256,b.sourceBundleSha256)&&eq(a.taskPayloadSha256,b.taskPayloadSha256)
                &&a.connectionRevision==b.connectionRevision&&eq(a.kind,b.kind)&&eq(a.transport,b.transport)
                &&eqOptionalEmptyHash(a.certSha256,b.certSha256)&&eq(a.taskId,b.taskId)&&eq(a.remoteTaskId,b.remoteTaskId)
                &&eq(a.connectionId,b.connectionId)&&eq(a.bridgeId,b.bridgeId)&&eq(a.instanceId,b.instanceId)
                &&eq(a.artifactId,b.artifactId)&&eq(a.name,b.name)&&eq(a.mediaType,b.mediaType)
                &&a.sizeBytes==b.sizeBytes&&eq(a.sha256,b.sha256)&&eq(a.storedName,b.storedName)
                &&a.createdAt==b.createdAt&&eq(a.originKind,b.originKind)&&eq(a.sourceState,b.sourceState)
                &&eq(a.sourceNoteId,b.sourceNoteId)&&a.sourceRevisionPrecisionMs==b.sourceRevisionPrecisionMs
                &&eq(a.digestKind,b.digestKind)&&eq(a.offlineState,b.offlineState);}
        catch(RuntimeException invalid){return false;}
    }
    private static boolean eq(String a,String b){return a==null?b==null:a.equals(b);}
    /** An absent optional certificate hash may be represented as null or empty by legacy metadata codecs. */
    private static boolean eqOptionalEmptyHash(String a,String b){
        boolean absentA=a==null||a.isEmpty(),absentB=b==null||b.isEmpty();
        return absentA||absentB?absentA&&absentB:a.equals(b);
    }

    private static boolean isKnownChild(String name){return "record.json".equals(name)||"payload.mp4".equals(name)||isRecordTemp(name)||isDownloaderPart(name);}
    private static boolean isRecordTemp(String name){return name!=null&&name.matches("\\.record-[A-Za-z0-9]{3,}\\.pending");}
    private static boolean isDownloaderPart(String name){return name.startsWith("agent-artifact-")&&name.endsWith(".part")&&name.length()<100;}
    private File operationDirectory(String id){return new File(root,id);}
    private static String nullable(JSONObject object,String key)throws Exception {Object value=object.get(key);return value==JSONObject.NULL?null:(String)value;}
    private static boolean canonicalUuid(String value){try{return UUID.fromString(value).toString().equals(value);}catch(Exception invalid){return false;}}
    /** NoteStore uses opaque filename-safe local IDs (including note-<uuid>); operation IDs stay UUID-only. */
    private static boolean safeNoteId(String value){return value!=null&&value.matches("[A-Za-z0-9_-]{1,160}");}
}
