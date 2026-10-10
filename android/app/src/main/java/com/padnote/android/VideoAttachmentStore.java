package com.padnote.android;

import android.content.Context;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/** App-private, durable associations between a source note and verified video files. */
final class VideoAttachmentStore {
    interface SourceLookup { NoteStore.Entry find(String noteId) throws Exception; }

    static final class Attachment {
        final String id, noteId, taskId, remoteTaskId, connectionId, bridgeId, instanceId, artifactId, name, mediaType;
        final String sourceBundleSha256, taskPayloadSha256, certSha256, kind, transport, sha256, storedName;
        final String originKind, sourceState, sourceNoteId, digestKind, offlineState;
        final int sourceRevisionPrecisionMs;
        final long sourceRevision, connectionRevision, sizeBytes, createdAt;

        Attachment(String id, String noteId, long sourceRevision, String sourceBundleSha256,String taskPayloadSha256,
                   long connectionRevision,String kind,String transport,String certSha256,
                   String taskId, String remoteTaskId, String connectionId, String bridgeId,
                   String instanceId, String artifactId, String name, String mediaType,
                   long sizeBytes, String sha256, String storedName, long createdAt) {
            this(id,noteId,sourceRevision,sourceBundleSha256,taskPayloadSha256,connectionRevision,kind,transport,
                    certSha256,taskId,remoteTaskId,connectionId,bridgeId,instanceId,artifactId,name,mediaType,
                    sizeBytes,sha256,storedName,createdAt,"computer_task","linked_note",noteId,1,
                    "source_and_task_payload","verified_local_copy");
        }
        Attachment(String id, String noteId, long sourceRevision, String sourceBundleSha256,String taskPayloadSha256,
                   long connectionRevision,String kind,String transport,String certSha256,
                   String taskId, String remoteTaskId, String connectionId, String bridgeId,
                   String instanceId, String artifactId, String name, String mediaType,
                   long sizeBytes, String sha256, String storedName, long createdAt,
                   String originKind, String sourceState, String sourceNoteId, int sourceRevisionPrecisionMs,
                   String digestKind, String offlineState) {
            this.id=id; this.noteId=noteId; this.sourceRevision=sourceRevision;this.connectionRevision=connectionRevision;
            this.kind=kind;this.transport=transport;this.certSha256=certSha256;
            this.sourceBundleSha256=sourceBundleSha256; this.taskPayloadSha256=taskPayloadSha256; this.taskId=taskId;
            this.remoteTaskId=remoteTaskId; this.connectionId=connectionId;
            this.bridgeId=bridgeId; this.instanceId=instanceId;
            this.artifactId=artifactId; this.name=name; this.mediaType=mediaType;
            this.sizeBytes=sizeBytes; this.sha256=sha256; this.storedName=storedName;
            this.createdAt=createdAt;
            this.originKind=originKind;this.sourceState=sourceState;this.sourceNoteId=sourceNoteId;
            this.sourceRevisionPrecisionMs=sourceRevisionPrecisionMs;this.digestKind=digestKind;this.offlineState=offlineState;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("id",id).put("noteId",noteId)
                    .put("sourceRevision",sourceRevision).put("sourceBundleSha256",sourceBundleSha256)
                    .put("taskPayloadSha256",taskPayloadSha256)
                    .put("connectionRevision",connectionRevision).put("kind",kind)
                    .put("transport",transport).put("certSha256",certSha256)
                    .put("taskId",taskId).put("remoteTaskId",remoteTaskId)
                    .put("connectionId",connectionId).put("bridgeId",bridgeId).put("instanceId",instanceId)
                    .put("artifactId",artifactId).put("name",name)
                    .put("mediaType",mediaType).put("sizeBytes",sizeBytes).put("sha256",sha256)
                    .put("storedName",storedName).put("createdAt",createdAt)
                    .put("originKind",originKind).put("sourceState",sourceState).put("sourceNoteId",sourceNoteId)
                    .put("sourceRevisionPrecisionMs",sourceRevisionPrecisionMs).put("digestKind",digestKind)
                    .put("offlineState",offlineState);
        }
        static Attachment parse(JSONObject j) throws Exception {
            Attachment a=new Attachment(j.getString("id"),j.getString("noteId"),
                    j.getLong("sourceRevision"),j.getString("sourceBundleSha256"),nullable(j,"taskPayloadSha256"),
                    j.optLong("connectionRevision",0),nullable(j,"kind"),nullable(j,"transport"),nullable(j,"certSha256"),
                    nullable(j,"taskId"),nullable(j,"remoteTaskId"),nullable(j,"connectionId"),
                    nullable(j,"bridgeId"),nullable(j,"instanceId"),nullable(j,"artifactId"),j.getString("name"),
                    j.getString("mediaType"),j.getLong("sizeBytes"),j.getString("sha256"),
                    j.getString("storedName"),j.getLong("createdAt"),j.optString("originKind","computer_task"),
                    j.optString("sourceState","linked_note"),j.optString("sourceNoteId",j.getString("noteId")),
                    j.optInt("sourceRevisionPrecisionMs",1),j.optString("digestKind","source_and_task_payload"),
                    j.optString("offlineState","verified_local_copy"));
            validate(a); return a;
        }
    }

    /** Installs a verified archive copy without creating a task or connection record. */
    Attachment restoreFromArchive(String localNoteId, LibraryBackupManifest.VideoAttachment source,
                                  File stagedVideo) throws Exception {
        try (LegacyGroupMutationLock.Lease mutationGate=LegacyGroupMutationLock.acquire()) {
        requireLegacyMutationAllowed(localNoteId);
        synchronized (LOCK) {
            ensureRoot();
            if (source == null || stagedVideo == null || !"verified_local_copy".equals(source.offlineState) ||
                    !"video/mp4".equals(source.mediaType) || source.byteLength <= 0 || source.byteLength > MAX_BYTES ||
                    !hex64(source.sha256) || !hex64(source.sourceBundleSha256) ||
                    source.noteItemId != null && (localNoteId == null || sources.find(localNoteId) == null))
                throw new IOException("ARCHIVE_VIDEO_INVALID");
            List<Attachment> existingRows=readAll();
            for(Attachment old:existingRows)if("restored_archive".equals(old.originKind)&&
                    old.noteId.equals(localNoteId==null?"restored-independent":localNoteId)&&
                    old.sourceNoteId.equals(source.sourceNoteId)&&old.sourceRevision==source.sourceRevisionMs&&
                    java.util.Objects.equals(old.artifactId,source.artifactId)&&
                    old.sha256.equalsIgnoreCase(source.sha256)){
                verifyFile(file(old),old.sizeBytes,old.sha256);
                if(!old.sourceState.equals(source.sourceState)||old.sourceRevisionPrecisionMs!=source.sourceRevisionPrecisionMs)
                    throw new IOException("ARCHIVE_VIDEO_IDENTITY_CONFLICT");
                return old;
            }
            String id=UUID.randomUUID().toString(), stored="video-"+id+".mp4";
            File target=new File(root,stored), temp=File.createTempFile(".restore-", ".part",root);
            try {
                copyVerified(stagedVideo,temp,source.byteLength,source.sha256);
                if (target.exists() || !temp.renameTo(target)) throw new IOException("ARCHIVE_VIDEO_PROMOTE_FAILED");
                Attachment restored=new Attachment(id,localNoteId==null?"restored-independent":localNoteId,
                        source.sourceRevisionMs,source.sourceBundleSha256,source.taskPayloadSha256,
                        source.connectionProvenance.connectionRevision==null?0:source.connectionProvenance.connectionRevision,
                        source.connectionProvenance.kind,source.connectionProvenance.transport,
                        source.connectionProvenance.certificateSha256,source.taskId,source.remoteTaskId,
                        source.connectionProvenance.connectionId,source.connectionProvenance.bridgeId,
                        source.connectionProvenance.instanceId,source.artifactId,source.displayName,source.mediaType,
                        source.byteLength,source.sha256,stored,source.createdAtMs,"restored_archive",source.sourceState,
                        source.sourceNoteId,source.sourceRevisionPrecisionMs,source.digestKind,source.offlineState);
                validate(restored);
                existingRows.add(restored); writeAll(existingRows);
                verifyFile(target,source.byteLength,source.sha256);
                return restored;
            } catch(Exception error) { if(temp.exists())temp.delete(); throw error; }
        }
        }
    }

    private static String nullable(JSONObject value,String key) {
        return value.isNull(key) ? null : value.optString(key,null);
    }

    private static final Object LOCK=new Object();
    private static final long MAX_BYTES=100L*1024L*1024L;
    private static final int MAX_INDEX_BYTES=4*1024*1024, MAX_ENTRIES=4000;
    private final File root, index, trustedParent;
    private final Context authorityContext;
    private final SourceLookup sources;

    VideoAttachmentStore(Context context) {
        this(context, new File(context.getFilesDir(),"video-attachments"), id -> {
            for (NoteStore.Entry entry:NoteStore.list(context)) if (entry.id.equals(id)) return entry;
            return null;
        });
    }
    /** Private files-root staging directory for a newly downloaded task artifact. */
    File downloadStagingDirectory() throws IOException {
        synchronized (LOCK) { ensureRoot(); return root; }
    }
    VideoAttachmentStore(File root, SourceLookup sources) {
        this(null, root, sources);
    }
    private VideoAttachmentStore(Context context, File root, SourceLookup sources) {
        this.authorityContext=context;
        this.root=root; this.index=new File(root,"index.json"); this.sources=sources;
        File parent=root==null?null:root.getParentFile();
        if(parent==null)throw new IllegalArgumentException("视频附件目录不安全");
        try{this.trustedParent=parent.getCanonicalFile();}
        catch(IOException error){throw new IllegalArgumentException("视频附件目录不安全",error);}
    }

    Attachment attach(String noteId,long sourceRevision,String sourceBundleSha256,
                      String taskPayloadSha256,String taskId,String remoteTaskId,String connectionId,long connectionRevision,
                      String kind,String transport,String bridgeId,String instanceId,String certSha256,
                      String artifactId,String name,String mediaType,
                      long sizeBytes,String sha256,File verifiedSource) throws Exception {
        try (LegacyGroupMutationLock.Lease mutationGate=LegacyGroupMutationLock.acquire()) {
        requireLegacyMutationAllowed(noteId);
        synchronized (LOCK) {
            if (sources.find(noteId)==null) throw new IOException("来源笔记已删除，不能关联视频");
            if (!"video/mp4".equals(mediaType)||sizeBytes<=0||sizeBytes>MAX_BYTES||
                    !hex64(sha256)||!hex64(sourceBundleSha256)||!hex64(taskPayloadSha256)||sourceRevision<=0||connectionRevision<=0||!safeId(noteId)||
                    !safeId(taskId)||!safeId(remoteTaskId)||!safeId(connectionId)||
                    !optionalId(bridgeId)||!optionalId(instanceId)||!optionalHex(certSha256)||
                    !validTransport(transport)||!validKind(kind)||!safeId(artifactId)||verifiedSource==null) {
                throw new IllegalArgumentException("视频附件信息无效");
            }
            List<Attachment> existing=readAll();
            for (Attachment a:existing) if (a.noteId.equals(noteId)&&a.taskId.equals(taskId)&&a.artifactId.equals(artifactId)) {
                if (!a.connectionId.equals(connectionId)||!a.bridgeId.equals(bridgeId)||
                        !a.instanceId.equals(instanceId)||a.connectionRevision!=connectionRevision||
                        !a.kind.equals(kind)||!a.transport.equals(transport)||!a.certSha256.equalsIgnoreCase(certSha256==null?"":certSha256)||
                        !a.remoteTaskId.equals(remoteTaskId)||a.sourceRevision!=sourceRevision||
                        !a.sha256.equalsIgnoreCase(sha256)||a.sizeBytes!=sizeBytes||
                        !a.sourceBundleSha256.equalsIgnoreCase(sourceBundleSha256)||
                        !a.taskPayloadSha256.equalsIgnoreCase(taskPayloadSha256))
                    throw new IOException("该任务产物已关联，但任务、来源或文件身份与当前记录不同");
                verifyFile(file(a),a.sizeBytes,a.sha256); return a;
            }
            ensureRoot();
            String id=UUID.randomUUID().toString();
            String stored="video-"+id+".mp4";
            File temp=File.createTempFile(".video-", ".part",root), target=new File(root,stored);
            boolean promoted=false,committed=false;
            try {
                copyVerified(verifiedSource,temp,sizeBytes,sha256);
                if (sources.find(noteId)==null) throw new IOException("来源笔记已删除，不能关联视频");
                if (target.exists()||!temp.renameTo(target)) throw new IOException("无法安全保存视频附件");
                promoted=true;
                Attachment added=new Attachment(id,noteId,sourceRevision,
                        sourceBundleSha256.toLowerCase(java.util.Locale.ROOT),taskPayloadSha256.toLowerCase(java.util.Locale.ROOT),
                        connectionRevision,kind,transport,certSha256==null?"":certSha256.toLowerCase(java.util.Locale.ROOT),
                        taskId,remoteTaskId,
                        connectionId,bridgeId,instanceId,artifactId,
                        displayName(name),mediaType,sizeBytes,sha256.toLowerCase(java.util.Locale.ROOT),
                        stored,System.currentTimeMillis());
                List<Attachment> next=new ArrayList<>(existing); next.add(added); writeAll(next);
                committed=true;
                return added;
            } finally {
                if (temp.exists()) temp.delete();
                if (promoted && !committed && target.exists()) target.delete();
            }
        }
        }
    }

    /** Idempotent legacy publication using the operation's pre-reserved material UUID. */
    Attachment attach(Attachment requested,File verifiedSource)throws Exception{
        if(requested==null||verifiedSource==null)throw new IOException("视频附件输入无效");
        try(LegacyGroupMutationLock.Lease mutationGate=LegacyGroupMutationLock.acquire()){
            requireLegacyMutationAllowed(requested.noteId);
            synchronized(LOCK){
                if(sources.find(requested.noteId)==null)throw new IOException("来源笔记已删除，不能关联视频");
                try{Attachment.parse(requested.json());}catch(Exception invalid){throw new IOException("视频附件信息无效",invalid);}
                List<Attachment> existing=readAll();
                Attachment byId=findById(existing,requested.id);
                if(byId!=null){
                    if(!PendingVideoArtifactStore.sameAttachment(byId,requested))throw new IOException("VIDEO_IDENTITY_CONFLICT");
                    verifyFile(file(byId),byId.sizeBytes,byId.sha256);return byId;
                }
                for(Attachment old:existing)if(old.noteId.equals(requested.noteId)&&old.taskId.equals(requested.taskId)
                        &&old.artifactId.equals(requested.artifactId))
                    throw new IOException("VIDEO_TASK_ARTIFACT_ALREADY_ASSOCIATED");
                ensureRoot();File target=file(requested);
                if(target.exists()){
                    // The immutable material file can outlive an index write whose fsync/error
                    // result was ambiguous. Reconcile only an exact size/hash match; never replace it.
                    verifyFile(target,requested.sizeBytes,requested.sha256);
                    List<Attachment> reconciled=new ArrayList<>(existing);reconciled.add(requested);
                    writeAll(reconciled);return requested;
                }
                File temp=File.createTempFile(".video-pending-",".part",root);
                StructStat tempIdentity=ownedTemporaryState(temp);
                try{
                    copyVerified(verifiedSource,temp,requested.sizeBytes,requested.sha256);
                    if(sources.find(requested.noteId)==null)throw new IOException("来源笔记已删除，不能关联视频");
                    if(target.exists()||!temp.renameTo(target))throw new IOException("无法安全保存视频附件");
                    List<Attachment> next=new ArrayList<>(existing);next.add(requested);writeAll(next);
                    verifyFile(target,requested.sizeBytes,requested.sha256);
                    return requested;
                }finally{
                    deleteTemporaryIfStillOwned(temp,tempIdentity);
                    // A target which appeared after the initial existence check was never created
                    // by this attempt. Never unlink it. Promoted bytes are also retained when
                    // index publication is ambiguous so the fixed UUID can be reconciled.
                }
            }
        }
    }

    /** Compatibility helper for store-only tests and legacy bridge identities. */
    Attachment attach(String noteId,long sourceRevision,String sourceBundleSha256,
                      String taskId,String remoteTaskId,String connectionId,String bridgeId,String instanceId,
                      String artifactId,String name,String mediaType,long sizeBytes,String sha256,
                      File verifiedSource)throws Exception{
        return attach(noteId,sourceRevision,sourceBundleSha256,sourceBundleSha256,taskId,remoteTaskId,
                connectionId,1,"HERMES","BRIDGE",bridgeId,instanceId,"",artifactId,name,mediaType,
                sizeBytes,sha256,verifiedSource);
    }

    /** Compatibility helper for store-only tests and legacy bridge identities. */
    Attachment attach(String noteId,long sourceRevision,String sourceBundleSha256,
                      String taskId,String artifactId,String name,String mediaType,
                      long sizeBytes,String sha256,File verifiedSource)throws Exception{
        return attach(noteId,sourceRevision,sourceBundleSha256,sourceBundleSha256,taskId,taskId,
                "test-connection",1,"HERMES","BRIDGE","","","",artifactId,name,mediaType,
                sizeBytes,sha256,verifiedSource);
    }

    List<Attachment> listForNote(String noteId) throws Exception {
        synchronized (LOCK) {
            if(authorityContext!=null&&android.os.Build.VERSION.SDK_INT>=27){
                List<Attachment> group=GroupAuthorityBridge.videoViews(authorityContext,noteId);
                if(group!=null)return group;
            }
            List<Attachment> result=new ArrayList<>();
            for (Attachment a:readAll()) if (a.noteId.equals(noteId)) result.add(a);
            result.sort((a,b)->Long.compare(b.createdAt,a.createdAt));
            return Collections.unmodifiableList(result);
        }
    }

    /** Legacy-only read after the caller has verified that no group marker owns this note. */
    List<Attachment> listLegacyForNote(String noteId) throws Exception {
        synchronized (LOCK) {
            List<Attachment> result=new ArrayList<>();
            for(Attachment attachment:readAll())if(attachment.noteId.equals(noteId))result.add(attachment);
            result.sort((a,b)->Long.compare(b.createdAt,a.createdAt));
            return Collections.unmodifiableList(result);
        }
    }

    /** Read-only complete index view for archive snapshots, including orphan provenance. */
    List<Attachment> listAllForBackup() throws Exception {
        synchronized (LOCK) {
            if(authorityContext==null||android.os.Build.VERSION.SDK_INT<27)return Collections.unmodifiableList(readAll());
            List<NoteStore.Entry> notes=NoteStore.list(authorityContext);Set<String> managed=new HashSet<>();
            List<Attachment> result=new ArrayList<>();GroupAuthorityBridge.CatalogReader catalogReader=null;
            for(NoteStore.Entry note:notes){
                if(catalogReader==null)catalogReader=GroupAuthorityBridge.catalogReader(authorityContext);
                com.padnote.android.streaming.StreamingGroupStore.Snapshot snapshot=catalogReader.open(note.id);
                if(snapshot==null)continue;
                managed.add(note.id);result.addAll(GroupAuthorityBridge.videoViews(snapshot));
            }
            for(Attachment legacy:readAll())if(!managed.contains(legacy.noteId))result.add(legacy);
            result.sort((a,b) -> Long.compare(b.createdAt, a.createdAt));
            return Collections.unmodifiableList(result);
        }
    }

    /** Legacy-only archive rows for a caller that has already pinned group snapshots. */
    List<Attachment> listLegacyForBackup(Set<String> groupManagedNotes)throws Exception {
        synchronized(LOCK){
            List<Attachment> result=new ArrayList<>();for(Attachment row:readAll())
                if(groupManagedNotes==null||!groupManagedNotes.contains(row.noteId))result.add(row);
            result.sort((a,b)->Long.compare(b.createdAt,a.createdAt));return Collections.unmodifiableList(result);
        }
    }

    File openVerified(Attachment attachment) throws Exception {
        synchronized (LOCK) {
            if(authorityContext!=null&&attachment!=null&&android.os.Build.VERSION.SDK_INT>=27){
                com.padnote.android.streaming.StreamingGroupStore.Snapshot snapshot=GroupAuthorityBridge.open(authorityContext,attachment.noteId);
                if(snapshot!=null){
                    File projected=GroupAuthorityBridge.videoProjection(authorityContext,snapshot,attachment);
                    verifyFile(projected,attachment.sizeBytes,attachment.sha256);return projected;
                }
            }
            Attachment found=findById(readAll(),attachment==null?"":attachment.id);
            if (found==null) throw new IOException("视频附件不存在");
            File file=file(found); verifyFile(file,found.sizeBytes,found.sha256); return file;
        }
    }

    void remove(String attachmentId) throws Exception {
        try (LegacyGroupMutationLock.Lease mutationGate=LegacyGroupMutationLock.acquire()) {
        synchronized (LOCK) {
            List<Attachment> all=readAll(); Attachment found=findById(all,attachmentId);
            if (found==null) { requireNoGroupAttachment(attachmentId); return; }
            requireLegacyMutationAllowed(found.noteId);
            File target=file(found);
            if (target.exists()&&!target.delete()) throw new IOException("本地视频仍在，关联未移除；请重试");
            all.remove(found); writeAll(all);
        }
        }
    }

    int countForNote(String noteId) throws Exception { return listForNote(noteId).size(); }

    static boolean isSourceStale(NoteStore.Entry source,Attachment attachment){
        return source==null||attachment==null||source.updatedAt!=attachment.sourceRevision;
    }

    void removeForNote(String noteId) throws Exception {
        try (LegacyGroupMutationLock.Lease mutationGate=LegacyGroupMutationLock.acquire()) {
        requireLegacyMutationAllowed(noteId);
        synchronized (LOCK) {
            List<Attachment> all=readAll(); List<Attachment> keep=new ArrayList<>();
            List<Attachment> failed=new ArrayList<>();
            for (Attachment a:all) {
                if (!a.noteId.equals(noteId)) { keep.add(a); continue; }
                File f=file(a);
                if(f.exists()&&!f.delete())failed.add(a);
            }
            keep.addAll(failed);
            writeAll(keep);
            if(!failed.isEmpty())throw new IOException("仍有 "+failed.size()+" 个本地视频文件未删除；附件记录已保留以便重试");
        }
        }
    }

    private void requireLegacyMutationAllowed(String noteId) throws Exception {
        if (authorityContext != null && noteId != null && !noteId.isEmpty())
            NoteStore.requireNoteMaterialAccess(authorityContext, noteId);
    }

    private void requireNoGroupAttachment(String attachmentId)throws Exception {
        if(authorityContext==null||android.os.Build.VERSION.SDK_INT<27)return;
        for(NoteStore.Entry note:NoteStore.list(authorityContext)){
            List<Attachment> rows=GroupAuthorityBridge.videoViews(authorityContext,note.id);
            if(rows==null)continue;
            for(Attachment row:rows)if(row.id.equals(attachmentId))
                throw new IOException("GROUP_NOTE_REQUIRES_FULL_REVISION_SAVE");
        }
    }

    private List<Attachment> readAll() throws Exception {
        ensureRoot(); File backup=new File(root,"index.bak");
        if (!index.exists()&&!backup.exists()) return new ArrayList<>();
        Exception primaryFailure=null;
        if(index.exists())try{return readIndex(index);}catch(Exception e){primaryFailure=e;}
        if(backup.exists())try{
            List<Attachment> recovered=readIndex(backup);
            File restore=new File(root,"index-recovery-"+UUID.randomUUID()+".tmp");
            try(FileInputStream in=new FileInputStream(backup);FileOutputStream out=new FileOutputStream(restore,false)){
                byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))>=0)if(n>0)out.write(buffer,0,n);out.flush();out.getFD().sync();}
            if(index.exists()&&!index.delete())throw new IOException("无法替换损坏的视频附件索引");
            if(!restore.renameTo(index))throw new IOException("无法恢复视频附件索引");
            backup.delete();return recovered;
        }catch(Exception recoveryFailure){if(primaryFailure!=null)recoveryFailure.addSuppressed(primaryFailure);throw new IOException("视频附件索引和备份均无法安全恢复",recoveryFailure);}
        if(primaryFailure!=null)throw new IOException("视频附件索引损坏且没有可恢复备份",primaryFailure);
        throw new IOException("视频附件索引无法安全读取");
    }

    private List<Attachment> readIndex(File candidate)throws Exception{
        if (!regularOwned(candidate)||candidate.length()>MAX_INDEX_BYTES||candidate.length()>Integer.MAX_VALUE)
            throw new IOException("视频附件索引无法安全读取");
        byte[] raw; try(FileInputStream in=new FileInputStream(candidate)) { raw=readBounded(in,MAX_INDEX_BYTES); }
        JSONObject doc=new JSONObject(new String(raw,StandardCharsets.UTF_8));
        if (doc.getInt("schemaVersion")!=1) throw new IOException("视频附件索引版本不支持");
        JSONArray values=doc.getJSONArray("attachments");
        if(values.length()>MAX_ENTRIES)throw new IOException("视频附件记录过多");
        List<Attachment> result=new ArrayList<>(); java.util.HashSet<String> ids=new java.util.HashSet<>();
        for(int i=0;i<values.length();i++){Attachment a=Attachment.parse(values.getJSONObject(i));
            if(!ids.add(a.id))throw new IOException("视频附件索引重复");result.add(a);}
        return result;
    }

    private void writeAll(List<Attachment> attachments) throws Exception {
        if(attachments.size()>MAX_ENTRIES)throw new IOException("视频附件记录过多");
        JSONArray values=new JSONArray();for(Attachment a:attachments)values.put(a.json());
        byte[] bytes=new JSONObject().put("schemaVersion",1).put("attachments",values)
                .toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_INDEX_BYTES)throw new IOException("视频附件索引过大");
        File temp=new File(root,"index-"+UUID.randomUUID()+".tmp"), backup=new File(root,"index.bak");
        try(FileOutputStream out=new FileOutputStream(temp,false)){out.write(bytes);out.flush();out.getFD().sync();}
        if(index.exists()&&(!backup.delete()&&backup.exists())){temp.delete();throw new IOException("无法准备索引备份");}
        boolean backed=index.exists()&&index.renameTo(backup);
        if(index.exists()&&!backed){temp.delete();throw new IOException("无法备份视频附件索引");}
        if(!temp.renameTo(index)){if(backed)backup.renameTo(index);temp.delete();throw new IOException("无法提交视频附件索引");}
        if(backup.exists())backup.delete();
    }

    private File file(Attachment a) throws IOException {
        if(!a.storedName.matches("video-[0-9a-fA-F-]{36}\\.mp4"))throw new IOException("附件路径无效");
        File f=new File(root,a.storedName);if(!f.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()))throw new IOException("附件路径越界");return f;
    }
    private void ensureRoot() throws IOException {
        File parent=root.getParentFile();
        if(parent==null||!parent.isDirectory())throw new IOException("视频附件目录不安全");
        File canonicalParent=parent.getCanonicalFile();
        if(!canonicalParent.equals(trustedParent))throw new IOException("视频附件目录不安全");
        File expectedRoot=new File(trustedParent,root.getName());
        if(!root.exists()&&!root.mkdirs())throw new IOException("无法创建视频附件目录");
        if(!root.isDirectory()||!root.getCanonicalFile().equals(expectedRoot))throw new IOException("视频附件目录不安全");
    }
    private static void copyVerified(File source,File target,long expected,String sha) throws Exception {
        if(!regularOwned(source))throw new IOException("下载视频不是安全的普通文件");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;byte[] buffer=new byte[64*1024];
        try(FileInputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(target,false)){
            int n;while((n=in.read(buffer))>=0){if(n==0)continue;total+=n;if(total>expected||total>MAX_BYTES)throw new IOException("视频文件超出声明大小");digest.update(buffer,0,n);out.write(buffer,0,n);}out.flush();out.getFD().sync();}
        if(total!=expected||!MessageDigest.isEqual(hex(digest.digest()).getBytes(StandardCharsets.US_ASCII),sha.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII)))throw new IOException("视频大小或SHA-256校验失败");
    }
    private static void verifyFile(File file,long size,String sha) throws Exception {
        if(!regularOwned(file)||file.length()!=size)throw new IOException("视频附件文件缺失或大小改变");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;byte[] buffer=new byte[64*1024];
        try(FileInputStream in=new FileInputStream(file)){int n;while((n=in.read(buffer))>=0){if(n==0)continue;total+=n;if(total>size||total>MAX_BYTES)throw new IOException("视频附件超出声明大小");digest.update(buffer,0,n);}}
        if(total!=size||!MessageDigest.isEqual(hex(digest.digest()).getBytes(StandardCharsets.US_ASCII),sha.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII)))throw new IOException("视频附件SHA-256校验失败");
    }
    private static byte[] readBounded(FileInputStream in,int max)throws Exception{java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))>=0){if(n==0)continue;if(out.size()+n>max)throw new IOException("索引超限");out.write(b,0,n);}return out.toByteArray();}
    private static Attachment findById(List<Attachment> all,String id){for(Attachment a:all)if(a.id.equals(id))return a;return null;}
    private static boolean contains(List<Attachment> all,String id){return findById(all,id)!=null;}
    private static boolean safeId(String s){return s!=null&&s.matches("[A-Za-z0-9_-]{1,160}");}
    private static boolean hex64(String s){return s!=null&&s.matches("[a-fA-F0-9]{64}");}
    private static String displayName(String value){String n=value==null?"video.mp4":value.replace('\\','/');int i=n.lastIndexOf('/');if(i>=0)n=n.substring(i+1);StringBuilder clean=new StringBuilder();for(int p=0;p<n.length();){int cp=n.codePointAt(p);p+=Character.charCount(cp);if(cp<32||cp==127||cp==':'||cp=='/'||cp=='\\')continue;clean.appendCodePoint(cp);}n=clean.toString().trim();if(n.isEmpty())n="video.mp4";return n.length()>120?n.substring(n.length()-120):n;}
    private static void validate(Attachment a)throws Exception{boolean restored="restored_archive".equals(a.originKind);if(!safeId(a.id)||!safeId(a.noteId)||(!restored&&(!safeId(a.taskId)||!safeId(a.remoteTaskId)||!safeId(a.connectionId)||a.connectionRevision<=0||!validKind(a.kind)||!validTransport(a.transport)||!safeId(a.artifactId)||!hex64(a.taskPayloadSha256)))||!optionalId(a.bridgeId)||!optionalId(a.instanceId)||!optionalHex(a.certSha256)||!hex64(a.sourceBundleSha256)||!hex64(a.sha256)||a.sourceRevision<=0||!"video/mp4".equals(a.mediaType)||a.sizeBytes<=0||a.sizeBytes>MAX_BYTES||!a.storedName.matches("video-[0-9a-fA-F-]{36}\\.mp4")||!("computer_task".equals(a.originKind)||restored)||!("linked_note".equals(a.sourceState)||"source_deleted".equals(a.sourceState)||"source_not_selected".equals(a.sourceState)||"independent".equals(a.sourceState))||!safeHistorySource(a.sourceNoteId)||!(a.sourceRevisionPrecisionMs==1||a.sourceRevisionPrecisionMs==1000)||!"verified_local_copy".equals(a.offlineState))throw new IOException("视频附件记录无效");}
    private static boolean safeHistorySource(String value){return value!=null&&!value.isEmpty()&&value.getBytes(StandardCharsets.UTF_8).length<=4096&&!value.contains("\u0000");}
    private static boolean optionalId(String s){return s!=null&&(s.isEmpty()||safeId(s));}
    private static boolean optionalHex(String s){return s!=null&&(s.isEmpty()||hex64(s));}
    private static boolean validKind(String s){for(AgentConnectionStore.Kind kind:AgentConnectionStore.Kind.values())if(kind.name().equals(s))return true;return false;}
    private static boolean validTransport(String s){for(AgentConnectionStore.Transport value:AgentConnectionStore.Transport.values())if(value.name().equals(s))return true;return false;}
    private static String hex(byte[] bytes){StringBuilder b=new StringBuilder(bytes.length*2);for(byte v:bytes)b.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return b.toString();}
    private static boolean regularOwned(File file){try{
        File parent=file.getParentFile();
        return parent!=null&&file.isFile()&&file.getCanonicalFile().equals(new File(parent.getCanonicalFile(),file.getName()));
    }catch(IOException e){return false;}}

    private static StructStat ownedTemporaryState(File file)throws IOException {
        try{
            StructStat state=Os.lstat(file.getAbsolutePath());
            if(!OsConstants.S_ISREG(state.st_mode)||state.st_uid!=Process.myUid()||state.st_nlink!=1)
                throw new IOException("VIDEO_TEMP_UNSAFE");
            return state;
        }catch(ErrnoException error){throw new IOException("VIDEO_TEMP_UNSAFE",error);}
    }
    private static void deleteTemporaryIfStillOwned(File file,StructStat created){
        if(file==null||created==null)return;
        try{
            StructStat current=Os.lstat(file.getAbsolutePath());
            if(OsConstants.S_ISREG(current.st_mode)&&current.st_uid==created.st_uid&&current.st_nlink==1
                    &&current.st_dev==created.st_dev&&current.st_ino==created.st_ino
                    &&(current.st_mode&OsConstants.S_IFMT)==(created.st_mode&OsConstants.S_IFMT))
                Os.remove(file.getAbsolutePath());
        }catch(Exception ignored){/* Preserve replaced or unverifiable paths. */}
    }
}
