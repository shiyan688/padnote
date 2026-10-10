package com.padnote.android;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Durable per-note restore journal. Only IDs recorded by this journal may be resumed. */
final class LibraryRestoreTransaction {
    interface Cancellation { boolean isCancelled(); }
    interface Progress { void onProgress(int completed, int total); }
    interface FaultInjector {
        void afterResourcesPromoted(String groupId) throws Exception;
        default void afterNoteIndexedBeforeVisibleCommit(String groupId) throws Exception { }
        default void afterRestoreOwnerTemporarySynced(String groupId) throws Exception { }
        default void afterGroupMembersInstalled(String groupId) throws Exception { }
        default void afterNoteOwnerCommittedBeforeGroupMarker(String groupId) throws Exception { }
        default void afterRollbackMemberProcessed(String groupId,String memberId) throws Exception { }
        default boolean allowCommitVerifier(String groupId,String memberId) throws Exception { return true; }
        default void afterCommitReservationDurable(String groupId) throws Exception { }
        default void afterCommitPathReservationDurable(String groupId) throws Exception { }
        default void afterCommitMarkerPayloadDurable(String groupId) throws Exception { }
        default void afterCommitDirectoryCreated(String groupId) throws Exception { }
        default void afterCommitDirectorySynced(String groupId) throws Exception { }
    }

    private final Context context;
    private final LibraryBackupArchive.StagedArchive staged;
    private final String transactionId;
    private final File root, journal;
    private final String archiveSha256;
    private final File stageDirectory;
    private String transferMarkerSha256;
    private final FaultInjector faults;
    private final Map<String, String> noteIds = new LinkedHashMap<>();
    private final Map<String, String> state = new LinkedHashMap<>();
    private final Map<String, Long> updatedAt = new LinkedHashMap<>();
    private final Map<String, String> noteSha256 = new LinkedHashMap<>();
    private final Map<String, String> pdfSha256 = new LinkedHashMap<>();
    private final Map<String, String> coverSha256 = new LinkedHashMap<>();
    private boolean v2Mode;
    private LibraryRestoreGroupV2 ledger;
    private final Map<String,V2Group> v2Groups=new LinkedHashMap<>();
    private RestoredLibraryMaterialStore materialStore;
    private static final class V2Group {
        final String sourceKey,noteItemId;
        final LibraryRestoreGroupV2.Plan plan;
        String state;
        V2Group(String key,String note,LibraryRestoreGroupV2.Plan p,String s){sourceKey=key;noteItemId=note;plan=p;state=s;}
    }

    LibraryRestoreTransaction(Context context, LibraryBackupArchive.StagedArchive staged) throws Exception {
        this(context,staged,null);
    }
    LibraryRestoreTransaction(Context context, LibraryBackupArchive.StagedArchive staged,FaultInjector faults) throws Exception {
        if (context == null || staged == null) throw new IllegalArgumentException("RESTORE_INPUT_INVALID");
        this.context = context.getApplicationContext();
        this.staged = staged;
        ensureNotesRoot(this.context);
        this.faults=faults;
        LibraryBackupManifest incoming=staged.manifest();
        this.v2Mode=true;
        this.transactionId = UUID.randomUUID().toString();
        this.archiveSha256=staged.archiveSha256();
        this.stageDirectory=staged.directory();
        File parent = new File(this.context.getFilesDir(), "library-backup/transactions");
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("RESTORE_JOURNAL_DIRECTORY");
        File canonicalParent=LibraryBackupArchive.AndroidSafeFiles.canonicalPrivateAndroidDirectory(parent);
        this.root = new File(parent, transactionId);
        if (!root.mkdir()) throw new IOException("RESTORE_JOURNAL_EXISTS");
        Os.chmod(root.getAbsolutePath(), 0700);
        if(!LibraryBackupArchive.AndroidSafeFiles.canonicalPrivateAndroidDirectory(root)
                .equals(new File(canonicalParent,transactionId)))throw new IOException("RESTORE_JOURNAL_DIRECTORY");
        this.journal = new File(root, "journal.json");
        this.ledger=new LibraryRestoreGroupV2(new File(this.context.getFilesDir(),"library-backup-v2/transactions"),new LibraryBackupArchive.AndroidSafeFiles(),new LibraryRestoreGroupV2.FaultInjector(){
            public void afterCommitReservationDurable(String groupId)throws Exception{if(LibraryRestoreTransaction.this.faults!=null)LibraryRestoreTransaction.this.faults.afterCommitReservationDurable(groupId);}
            @Override public void afterCommitPathReservationDurable(String groupId)throws Exception{if(LibraryRestoreTransaction.this.faults!=null)LibraryRestoreTransaction.this.faults.afterCommitPathReservationDurable(groupId);}
            @Override public void afterCommitMarkerPayloadDurable(String groupId)throws Exception{if(LibraryRestoreTransaction.this.faults!=null)LibraryRestoreTransaction.this.faults.afterCommitMarkerPayloadDurable(groupId);}
            @Override public void afterCommitDirectoryCreated(String groupId)throws Exception{if(LibraryRestoreTransaction.this.faults!=null)LibraryRestoreTransaction.this.faults.afterCommitDirectoryCreated(groupId);}
            @Override public void afterCommitDirectorySynced(String groupId)throws Exception{if(LibraryRestoreTransaction.this.faults!=null)LibraryRestoreTransaction.this.faults.afterCommitDirectorySynced(groupId);}
        });
        this.materialStore=new RestoredLibraryMaterialStore(this.context.getFilesDir());
        for (LibraryBackupManifest.Note note : staged.manifest().notes) {
            String localId="note-" + UUID.randomUUID().toString().replace("-", "");
            long fixedUpdatedAt=System.currentTimeMillis();
            JSONObject source=new JSONObject(new String(readStaged(staged,note.noteResourceId),java.nio.charset.StandardCharsets.UTF_8));
            JSONObject prepared=NoteStore.prepareRestoredDocument(source,source.optString("title","导入的笔记"),localId,fixedUpdatedAt);
            NoteStore.ensureRestoreTargetUnused(this.context,localId);
            noteIds.put(note.itemId, localId);
            updatedAt.put(note.itemId,fixedUpdatedAt);
            noteSha256.put(note.itemId,sha256(NoteJsonCodec.stringify(prepared).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            pdfSha256.put(note.itemId,note.pdfResourceId==null?null:staged.manifest().resourceById(note.pdfResourceId).sha256);
            coverSha256.put(note.itemId,note.coverResourceId==null?null:staged.manifest().resourceById(note.coverResourceId).sha256);
            state.put(note.itemId, "validated");
        }
        buildV2Plans(incoming);
        persist("validated");
        this.transferMarkerSha256=staged.transferOwnership(transactionId);
        persist("staged");
    }

    private LibraryRestoreTransaction(Context context, LibraryBackupArchive.StagedArchive staged,
            String transactionId, File root, String archiveSha256, String markerSha256,
            Map<String,String> ids, Map<String,String> states, Map<String,Long> updatedAt,
            Map<String,String> noteSha, Map<String,String> pdfSha, Map<String,String> coverSha) throws Exception {
        this.context=context.getApplicationContext();this.staged=staged;this.transactionId=transactionId;
        this.faults=null;
        this.root=root;this.journal=new File(root,"journal.json");this.archiveSha256=archiveSha256;
        this.stageDirectory=staged.directory();this.transferMarkerSha256=markerSha256;
        this.noteIds.putAll(ids);this.state.putAll(states);this.updatedAt.putAll(updatedAt);
        this.noteSha256.putAll(noteSha);this.pdfSha256.putAll(pdfSha);this.coverSha256.putAll(coverSha);
    }

    private void buildV2Plans(LibraryBackupManifest manifest)throws Exception {
        Map<String,Object> json=LibraryBackupJson.parseCheckedObject(manifest.toJsonBytes(),LibraryBackupManifest.MAX_MANIFEST_BYTES);
        Map<String,List<LibraryRestoreGroupV2.Request>> requests=new LinkedHashMap<>();
        Map<String,String> noteForGroup=new LinkedHashMap<>();
        for(LibraryBackupManifest.Note note:manifest.notes) {
            String key="note:"+note.itemId,local=noteIds.get(note.itemId);
            List<LibraryRestoreGroupV2.Request> rows=new ArrayList<>();requests.put(key,rows);noteForGroup.put(key,note.itemId);
            LibraryBackupManifest.Resource nr=manifest.resourceById(note.noteResourceId);
            byte[] prepared=preparedNoteBytes(note,local,updatedAt.get(note.itemId));
            rows.add(new LibraryRestoreGroupV2.Request(note.itemId,note.noteResourceId,null,local,
                    LibraryRestoreGroupV2.Kind.NOTE,prepared.length,noteSha256.get(note.itemId),
                    memberSemanticHash(json,"notes",note.itemId,note.noteResourceId,"NOTE",note.itemId,
                            noteSha256.get(note.itemId),updatedAt.get(note.itemId))));
            if(note.pdfResourceId!=null){LibraryBackupManifest.Resource r=manifest.resourceById(note.pdfResourceId);rows.add(new LibraryRestoreGroupV2.Request(note.itemId,r.resourceId,note.itemId,
                    LibraryRestoreGroupV2.Kind.PDF,r.byteLength,r.sha256,memberSemanticHash(json,"notes",note.itemId,r.resourceId,"PDF",note.itemId,noteSha256.get(note.itemId),updatedAt.get(note.itemId))));}
            if(note.coverResourceId!=null){LibraryBackupManifest.Resource r=manifest.resourceById(note.coverResourceId);rows.add(new LibraryRestoreGroupV2.Request(note.itemId,r.resourceId,note.itemId,
                    LibraryRestoreGroupV2.Kind.ASSIGNED_COVER,r.byteLength,r.sha256,memberSemanticHash(json,"notes",note.itemId,r.resourceId,"ASSIGNED_COVER",note.itemId,noteSha256.get(note.itemId),updatedAt.get(note.itemId))));}
        }
        for(LibraryBackupManifest.VaultEntry row:manifest.vaultEntries){
            boolean linked="linked_note".equals(row.sourceState)&&row.noteItemId!=null;String key=linked?"note:"+row.noteItemId:"detached:"+row.itemId;
            List<LibraryRestoreGroupV2.Request> group=requests.get(key);if(group==null){group=new ArrayList<>();requests.put(key,group);noteForGroup.put(key,null);}
            LibraryBackupManifest.Resource r=manifest.resourceById(row.resourceId);
            group.add(new LibraryRestoreGroupV2.Request(row.itemId,row.resourceId,linked?row.noteItemId:null,
                    LibraryRestoreGroupV2.Kind.VAULT,r.byteLength,r.sha256,memberSemanticHash(json,"vault_entries",row.itemId,row.resourceId,"VAULT",row.noteItemId,null,null)));
        }
        for(LibraryBackupManifest.VideoAttachment row:manifest.videoAttachments){
            boolean linked="linked_note".equals(row.sourceState)&&row.noteItemId!=null;String key=linked?"note:"+row.noteItemId:"detached:"+row.itemId;
            List<LibraryRestoreGroupV2.Request> group=requests.get(key);if(group==null){group=new ArrayList<>();requests.put(key,group);noteForGroup.put(key,null);}
            LibraryBackupManifest.Resource r=manifest.resourceById(row.resourceId);
            group.add(new LibraryRestoreGroupV2.Request(row.itemId,row.resourceId,linked?row.noteItemId:null,
                    LibraryRestoreGroupV2.Kind.VIDEO,r.byteLength,r.sha256,memberSemanticHash(json,"video_attachments",row.itemId,row.resourceId,"VIDEO",row.noteItemId,null,null)));
        }
        for(LibraryBackupManifest.CoverPreset row:manifest.coverPresets){
            String key="detached:"+row.itemId;List<LibraryRestoreGroupV2.Request> group=new ArrayList<>();requests.put(key,group);noteForGroup.put(key,null);
            LibraryBackupManifest.Resource r=manifest.resourceById(row.resourceId);
            group.add(new LibraryRestoreGroupV2.Request(row.itemId,row.resourceId,null,LibraryRestoreGroupV2.Kind.COVER_PRESET,
                    r.byteLength,r.sha256,memberSemanticHash(json,"cover_presets",row.itemId,row.resourceId,"COVER_PRESET",null,null,null)));
        }
        for(Map.Entry<String,List<LibraryRestoreGroupV2.Request>> entry:requests.entrySet()){
            LibraryRestoreGroupV2.Plan plan=ledger.ensurePlan(transactionId,archiveSha256,entry.getKey(),entry.getValue());
            v2Groups.put(entry.getKey(),new V2Group(entry.getKey(),noteForGroup.get(entry.getKey()),plan,"validated"));
        }
    }

    private byte[] preparedNoteBytes(LibraryBackupManifest.Note note,String local,long updated)throws Exception {
        JSONObject source=new JSONObject(new String(readStaged(staged,note.noteResourceId),StandardCharsets.UTF_8));
        JSONObject prepared=NoteStore.prepareRestoredDocument(source,source.optString("title","导入的笔记"),local,updated);
        return NoteJsonCodec.stringify(prepared).getBytes(StandardCharsets.UTF_8);
    }

    private static String memberSemanticHash(Map<String,Object> manifest,String arrayName,String itemId,String resourceId,
            String kind,String sourceNoteItemId,String preparedSha,Long updated)throws Exception {
        Map<String,Object> descriptor=findJsonRow(manifest,arrayName,"item_id",itemId);
        Map<String,Object> resource=findJsonRow(manifest,"resources","resource_id",resourceId);
        Map<String,Object> projection=new java.util.TreeMap<>();projection.put("schema_version",2);projection.put("kind",kind);
        projection.put("source_note_item_id",sourceNoteItemId);projection.put("descriptor",descriptor);projection.put("resource",resource);
        projection.put("prepared_note_sha256",preparedSha);projection.put("note_updated_at",updated);
        return sha256(LibraryBackupJson.encode(canonicalValue(projection)));
    }

    private static Map<String,Object> findJsonRow(Map<String,Object> object,String arrayName,String key,String value)throws IOException {
        for(Object raw:LibraryBackupJson.array(object.get(arrayName),"RESTORE_V2_BINDING_INVALID")){
            Map<String,Object> row=LibraryBackupJson.object(raw,"RESTORE_V2_BINDING_INVALID");
            if(value.equals(row.get(key)))return row;
        }
        throw new IOException("RESTORE_V2_BINDING_INVALID");
    }
    private static Object canonicalValue(Object value) {
        if(value instanceof Map){Map<String,Object> out=new java.util.TreeMap<>();for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet())out.put((String)e.getKey(),canonicalValue(e.getValue()));return out;}
        if(value instanceof List){List<Object> out=new ArrayList<>();for(Object item:(List<?>)value)out.add(canonicalValue(item));return out;}
        return value;
    }

    static LibraryRestoreTransaction resume(Context context, File transactionDirectory) throws Exception {
        File parent=new File(context.getFilesDir(),"library-backup/transactions");
        File canonicalParent=LibraryBackupArchive.AndroidSafeFiles.canonicalPrivateAndroidDirectory(parent);
        ensureNotesRoot(context);
        if(transactionDirectory==null||!LibraryBackupArchive.AndroidSafeFiles.canonicalPrivateAndroidDirectory(transactionDirectory)
                .equals(new File(canonicalParent,transactionDirectory.getName())))
            throw new IOException("RESTORE_JOURNAL_PATH_INVALID");
        File journal=new File(transactionDirectory,"journal.json");
        if(!regularOwned(journal)||journal.length()>2*1024*1024)throw new IOException("RESTORE_JOURNAL_INVALID");
        byte[] rawJournal=readNoFollow(journal,2*1024*1024);
        Map<String,Object> checked=LibraryBackupJson.parseCheckedObject(rawJournal,2*1024*1024);
        if(LibraryBackupJson.integer(checked.get("schema_version"),1,2,"RESTORE_JOURNAL_SCHEMA")==2)
            return resumeV2(context,transactionDirectory,checked);
        LibraryBackupJson.exactKeys(checked,"RESTORE_JOURNAL_FIELDS","schema_version","transaction_id","archive_sha256","stage_directory","transfer_marker_sha256","phase","groups");
        Object rawGroups=checked.get("groups");if(!(rawGroups instanceof java.util.List))throw new IOException("RESTORE_JOURNAL_GROUP_SET");
        for(Object row:(java.util.List<?>)rawGroups){if(!(row instanceof Map))throw new IOException("RESTORE_JOURNAL_INVALID");@SuppressWarnings("unchecked") Map<String,Object> fields=(Map<String,Object>)row;LibraryBackupJson.exactKeys(fields,"RESTORE_JOURNAL_GROUP_FIELDS","item_id","local_note_id","state","updated_at","note_sha256","pdf_sha256","cover_sha256");}
        JSONObject data=new JSONObject(new String(rawJournal,java.nio.charset.StandardCharsets.UTF_8));
        if(LibraryBackupJson.integer(checked.get("schema_version"),1,1,"RESTORE_JOURNAL_SCHEMA")!=1)throw new IOException("RESTORE_JOURNAL_SCHEMA");
        String phase=data.getString("phase");if(!("validated".equals(phase)||"staged".equals(phase)||"cancelled".equals(phase)||"resources_promoted".equals(phase)||"committed".equals(phase)||"complete".equals(phase)||"rolled_back".equals(phase)))throw new IOException("RESTORE_JOURNAL_PHASE");
        String tx=data.getString("transaction_id"), archiveSha=data.getString("archive_sha256");
        String marker=data.getString("transfer_marker_sha256");String stagePath=data.getString("stage_directory");
        if(!tx.matches("[0-9a-fA-F-]{36}")||!transactionDirectory.getName().equalsIgnoreCase(tx)||!archiveSha.matches("[0-9a-f]{64}")||!marker.matches("[0-9a-f]{64}"))
            throw new IOException("RESTORE_JOURNAL_INVALID");
        File stageDir=new File(stagePath);
        File stageParent=new File(context.getFilesDir(),"library-backup");
        File canonicalStageParent=LibraryBackupArchive.AndroidSafeFiles.canonicalPrivateAndroidDirectory(stageParent);
        if(!LibraryBackupArchive.AndroidSafeFiles.canonicalPrivateAndroidDirectory(stageDir)
                .equals(new File(canonicalStageParent,stageDir.getName())))
            throw new IOException("RESTORE_STAGE_PATH_INVALID");
        LibraryBackupArchive.StagedArchive staged=LibraryBackupArchive.openTransferred(stageDir,tx,archiveSha,marker);
        rejectUnsupported(staged.manifest());
        JSONArray rows=data.getJSONArray("groups");Map<String,String> ids=new LinkedHashMap<>(),states=new LinkedHashMap<>(),noteSha=new LinkedHashMap<>(),pdfSha=new LinkedHashMap<>(),coverSha=new LinkedHashMap<>();Map<String,Long> updated=new LinkedHashMap<>();java.util.Set<String> uniqueIds=new java.util.HashSet<>();
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String item=row.getString("item_id"),id=row.getString("local_note_id"),s=row.getString("state");
            if(!item.matches("i-[0-9a-f]{32}")||!id.matches("[A-Za-z0-9_-]{1,160}")||
                    !("validated".equals(s)||"staged".equals(s)||"resources_promoted".equals(s)||"committed".equals(s)||"rolled_back".equals(s))||ids.put(item,id)!=null||!uniqueIds.add(id))
                throw new IOException("RESTORE_JOURNAL_INVALID");states.put(item,s);}
        if(ids.size()!=staged.manifest().notes.size())throw new IOException("RESTORE_JOURNAL_GROUP_SET");
        java.util.List<?> checkedRows=(java.util.List<?>)rawGroups;
        for(Object raw:checkedRows){@SuppressWarnings("unchecked") Map<String,Object> fields=(Map<String,Object>)raw;String item=(String)fields.get("item_id");long time=LibraryBackupJson.integer(fields.get("updated_at"),1,Long.MAX_VALUE,"RESTORE_JOURNAL_INVALID");
            Object nh=fields.get("note_sha256"),ph=fields.get("pdf_sha256"),ch=fields.get("cover_sha256");if(!(nh instanceof String)||ph!=null&&!(ph instanceof String)||ch!=null&&!(ch instanceof String))throw new IOException("RESTORE_JOURNAL_INVALID");
            updated.put(item,time);noteSha.put(item,(String)nh);pdfSha.put(item,(String)ph);coverSha.put(item,(String)ch);}
        for(LibraryBackupManifest.Note note:staged.manifest().notes){String id=ids.get(note.itemId);Long time=updated.get(note.itemId);if(id==null||time==null)throw new IOException("RESTORE_JOURNAL_GROUP_SET");
            JSONObject source=new JSONObject(new String(readStaged(staged,note.noteResourceId),java.nio.charset.StandardCharsets.UTF_8));JSONObject prepared=NoteStore.prepareRestoredDocument(source,source.optString("title","导入的笔记"),id,time);
            String expectedSha=sha256(NoteJsonCodec.stringify(prepared).getBytes(java.nio.charset.StandardCharsets.UTF_8));String expectedPdf=note.pdfResourceId==null?null:staged.manifest().resourceById(note.pdfResourceId).sha256;String expectedCover=note.coverResourceId==null?null:staged.manifest().resourceById(note.coverResourceId).sha256;
            if(!expectedSha.equals(noteSha.get(note.itemId))||!java.util.Objects.equals(expectedPdf,pdfSha.get(note.itemId))||!java.util.Objects.equals(expectedCover,coverSha.get(note.itemId)))throw new IOException("RESTORE_JOURNAL_GROUP_MISMATCH");
            String ownerState=NoteStore.restoreOwnerState(context,id);String rowState=states.get(note.itemId);
            if(ownerState==null){if(!("validated".equals(rowState)||"staged".equals(rowState)||"rolled_back".equals(rowState)))throw new IOException("RESTORE_OWNER_MISSING");NoteStore.ensureRestoreTargetUnused(context,id);}
            else{NoteStore.requireRestoreOwner(context,tx,note.itemId,id,expectedSha,time,expectedPdf,expectedCover,ownerState);
                if("committed".equals(rowState)&&!"committed".equals(ownerState))throw new IOException("RESTORE_COMMIT_GATE_MISMATCH");
                if("rolled_back".equals(rowState)||"committed".equals(ownerState)&&!("committed".equals(rowState)||"resources_promoted".equals(rowState)))throw new IOException("RESTORE_COMMIT_GATE_MISMATCH");}
        }
        for(LibraryBackupManifest.Note note:staged.manifest().notes){
            if(!ids.containsKey(note.itemId))throw new IOException("RESTORE_JOURNAL_GROUP_SET");
            if("committed".equals(states.get(note.itemId)))verifyCommitted(context,tx,note,ids.get(note.itemId),noteSha.get(note.itemId),updated.get(note.itemId),pdfSha.get(note.itemId),coverSha.get(note.itemId));
        }
        return new LibraryRestoreTransaction(context,staged,tx,transactionDirectory,archiveSha,marker,ids,states,updated,noteSha,pdfSha,coverSha);
    }

    private static LibraryRestoreTransaction resumeV2(Context context,File dir,Map<String,Object> checked)throws Exception {
        LibraryBackupJson.exactKeys(checked,"RESTORE_JOURNAL_FIELDS","schema_version","transaction_id","archive_sha256","stage_directory","transfer_marker_sha256","phase","groups");
        String tx=LibraryBackupJson.string(checked.get("transaction_id"),"RESTORE_JOURNAL_INVALID");
        String archive=LibraryBackupJson.string(checked.get("archive_sha256"),"RESTORE_JOURNAL_INVALID");
        String marker=LibraryBackupJson.string(checked.get("transfer_marker_sha256"),"RESTORE_JOURNAL_INVALID");
        String phase=LibraryBackupJson.string(checked.get("phase"),"RESTORE_JOURNAL_INVALID");
        if(!tx.matches("[0-9a-fA-F-]{36}")||!dir.getName().equalsIgnoreCase(tx)||!archive.matches("[0-9a-f]{64}")||!marker.matches("[0-9a-f]{64}")||
                !("validated".equals(phase)||"staged".equals(phase)||"cancelled".equals(phase)||"restore_in_progress".equals(phase)||"rollback_in_progress".equals(phase)||"complete".equals(phase)||"rolled_back".equals(phase)))
            throw new IOException("RESTORE_JOURNAL_INVALID");
        String stagePath=LibraryBackupJson.string(checked.get("stage_directory"),"RESTORE_JOURNAL_INVALID");
        File stageDir=new File(stagePath),stageParent=new File(context.getFilesDir(),"library-backup");
        File canonicalStageParent=LibraryBackupArchive.AndroidSafeFiles.canonicalPrivateAndroidDirectory(stageParent);
        if(!LibraryBackupArchive.AndroidSafeFiles.canonicalPrivateAndroidDirectory(stageDir).equals(new File(canonicalStageParent,stageDir.getName())))throw new IOException("RESTORE_STAGE_PATH_INVALID");
        LibraryBackupArchive.StagedArchive staged=LibraryBackupArchive.openTransferred(stageDir,tx,archive,marker);
        Object raw=checked.get("groups");if(!(raw instanceof List))throw new IOException("RESTORE_JOURNAL_GROUP_SET");
        Map<String,String> ids=new LinkedHashMap<>(),states=new LinkedHashMap<>(),noteSha=new LinkedHashMap<>(),pdfSha=new LinkedHashMap<>(),coverSha=new LinkedHashMap<>();
        Map<String,Long> updated=new LinkedHashMap<>();Map<String,Map<String,Object>> savedGroups=new LinkedHashMap<>();
        for(Object entry:(List<?>)raw){Map<String,Object> row=LibraryBackupJson.object(entry,"RESTORE_JOURNAL_INVALID");
            LibraryBackupJson.exactKeys(row,"RESTORE_JOURNAL_GROUP_FIELDS","source_group_key","group_id","state","note_item_id","local_note_id","updated_at","note_sha256","pdf_sha256","cover_sha256","members");
            String key=LibraryBackupJson.string(row.get("source_group_key"),"RESTORE_JOURNAL_INVALID"),groupId=LibraryBackupJson.string(row.get("group_id"),"RESTORE_JOURNAL_INVALID");
            String state=LibraryBackupJson.string(row.get("state"),"RESTORE_JOURNAL_INVALID");if(!("validated".equals(state)||"installing".equals(state)||"commit_reserved".equals(state)||"ready_to_commit".equals(state)||"committed".equals(state)||"rollback_in_progress".equals(state)||"rolled_back".equals(state)))throw new IOException("RESTORE_JOURNAL_INVALID");
            if(savedGroups.put(key,row)!=null||!groupId.matches("[0-9a-fA-F-]{36}"))throw new IOException("RESTORE_JOURNAL_INVALID");states.put(key,state);
            Object noteValue=row.get("note_item_id");if(noteValue==null){if(row.get("local_note_id")!=null||row.get("updated_at")!=null||row.get("note_sha256")!=null||row.get("pdf_sha256")!=null||row.get("cover_sha256")!=null)throw new IOException("RESTORE_JOURNAL_INVALID");}
            else {if(!(noteValue instanceof String)||!(row.get("local_note_id") instanceof String)||!(row.get("note_sha256") instanceof String))throw new IOException("RESTORE_JOURNAL_INVALID");
                String item=(String)noteValue,id=(String)row.get("local_note_id");if(!item.matches("i-[0-9a-f]{32}")||!id.matches("note-[0-9a-f]{32}")||ids.put(item,id)!=null)throw new IOException("RESTORE_JOURNAL_INVALID");
                updated.put(item,LibraryBackupJson.integer(row.get("updated_at"),1,Long.MAX_VALUE,"RESTORE_JOURNAL_INVALID"));noteSha.put(item,(String)row.get("note_sha256"));
                Object pdf=row.get("pdf_sha256"),cover=row.get("cover_sha256");if(pdf!=null&&!(pdf instanceof String)||cover!=null&&!(cover instanceof String))throw new IOException("RESTORE_JOURNAL_INVALID");pdfSha.put(item,(String)pdf);coverSha.put(item,(String)cover);}
            if(!(row.get("members") instanceof List))throw new IOException("RESTORE_JOURNAL_INVALID");
        }
        if(ids.size()!=staged.manifest().notes.size())throw new IOException("RESTORE_JOURNAL_GROUP_SET");
        for(LibraryBackupManifest.Note note:staged.manifest().notes)if(!ids.containsKey(note.itemId))throw new IOException("RESTORE_JOURNAL_GROUP_SET");
        LibraryRestoreTransaction result=new LibraryRestoreTransaction(context,staged,tx,dir,archive,marker,ids,new LinkedHashMap<>(),updated,noteSha,pdfSha,coverSha);
        result.v2Mode=true;result.ledger=new LibraryRestoreGroupV2(new File(context.getFilesDir(),"library-backup-v2/transactions"),new LibraryBackupArchive.AndroidSafeFiles());result.materialStore=new RestoredLibraryMaterialStore(context.getFilesDir());
        result.buildV2Plans(staged.manifest());
        if(result.v2Groups.size()!=savedGroups.size())throw new IOException("RESTORE_JOURNAL_GROUP_SET");
        for(V2Group group:result.v2Groups.values()){
            Map<String,Object> row=savedGroups.get(group.sourceKey);if(row==null||!group.plan.groupId.equals(row.get("group_id")))throw new IOException("RESTORE_JOURNAL_GROUP_MISMATCH");
            group.state=(String)row.get("state");if(group.noteItemId!=null)result.state.put(group.noteItemId,group.state);
            List<?> members=(List<?>)row.get("members");if(members.size()!=group.plan.members.size())throw new IOException("RESTORE_JOURNAL_GROUP_MISMATCH");
            Map<String,Map<String,Object>> by=new LinkedHashMap<>();for(Object value:members){Map<String,Object> m=LibraryBackupJson.object(value,"RESTORE_JOURNAL_INVALID");LibraryBackupJson.exactKeys(m,"RESTORE_JOURNAL_MEMBER_FIELDS","member_id","source_item_id","source_resource_id","source_note_item_id","local_object_id","current_local_note_id","kind","byte_length","payload_sha256","semantic_binding_sha256");String id=LibraryBackupJson.string(m.get("member_id"),"RESTORE_JOURNAL_INVALID");if(by.put(id,m)!=null)throw new IOException("RESTORE_JOURNAL_INVALID");}
            for(LibraryRestoreGroupV2.Member member:group.plan.members){Map<String,Object> m=by.get(member.memberId);if(m==null||!member.sourceItemId.equals(m.get("source_item_id"))||!member.sourceResourceId.equals(m.get("source_resource_id"))||!java.util.Objects.equals(member.sourceNoteItemId,m.get("source_note_item_id"))||!member.localObjectId.equals(m.get("local_object_id"))||!java.util.Objects.equals(member.currentLocalNoteId,m.get("current_local_note_id"))||!member.kind.name().equals(m.get("kind"))||LibraryBackupJson.integer(m.get("byte_length"),0,Long.MAX_VALUE,"RESTORE_JOURNAL_INVALID")!=member.byteLength||!member.payloadSha256.equals(m.get("payload_sha256"))||!member.semanticBindingSha256.equals(m.get("semantic_binding_sha256")))throw new IOException("RESTORE_JOURNAL_GROUP_MISMATCH");}
        }
        return result;
    }

    private static void rejectUnsupported(LibraryBackupManifest manifest)throws IOException{
        if(!manifest.vaultEntries.isEmpty()||!manifest.videoAttachments.isEmpty()||!manifest.coverPresets.isEmpty())throw new IOException("RESTORE_GROUP_HAS_UNSUPPORTED_CHILDREN");
        for(LibraryBackupManifest.Note note:manifest.notes)if(!note.vaultEntryIds.isEmpty()||!note.videoAttachmentIds.isEmpty())throw new IOException("RESTORE_GROUP_HAS_UNSUPPORTED_CHILDREN");
    }
    private static void ensureNotesRoot(Context context)throws Exception{
        File root=new File(context.getFilesDir(),NoteStore.notesDirectoryName());
        if(!root.exists()&&!root.mkdir())throw new IOException("RESTORE_NOTES_DIRECTORY");
        LibraryBackupArchive.AndroidSafeFiles.canonicalPrivateAndroidDirectory(root);
    }
    private static void verifyCommitted(Context context,String tx,LibraryBackupManifest.Note note,String localId,
            String noteSha,long updatedAt,String pdfSha,String coverSha)throws Exception{
        NoteStore.requireRestoreOwner(context,tx,note.itemId,localId,noteSha,updatedAt,pdfSha,coverSha,"committed");
        // Once visible, later user edits belong to the user and must survive resume.
        NoteStore.load(context,localId);
    }
    private static byte[] readStaged(LibraryBackupArchive.StagedArchive staged,String id)throws Exception{
        LibraryBackupManifest.Resource r=staged.manifest().resourceById(id);File f=staged.resourceFile(id);
        if(r==null||r.byteLength>LibraryBackupManifest.MAX_NOTE_BYTES||!regularOwned(f)||f.length()!=r.byteLength)throw new IOException("RESTORE_RESOURCE_INVALID");
        byte[] bytes=new byte[(int)r.byteLength];Struct before=inspect(f);try(FileInputStream in=new FileInputStream(f)){int off=0;while(off<bytes.length){int n=in.read(bytes,off,bytes.length-off);if(n<0)throw new IOException("RESTORE_RESOURCE_INVALID");off+=n;}if(in.read()!=-1||!before.equals(inspect(f)))throw new IOException("RESTORE_RESOURCE_CHANGED");}
        if(!r.sha256.equals(sha256(bytes)))throw new IOException("RESTORE_RESOURCE_HASH_MISMATCH");return bytes;
    }

    String transactionId() { return transactionId; }
    File transactionDirectory(){return root;}
    Map<String,String> localNoteIds(){return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(noteIds));}

    /** Removes only uncommitted resources whose byte identity still matches this journal. */
    synchronized void rollbackUncommitted() throws Exception {
        if(v2Mode){rollbackV2();return;}
        for(LibraryBackupManifest.Note note:staged.manifest().notes){
            String current=state.get(note.itemId),id=noteIds.get(note.itemId);if("committed".equals(current)||"rolled_back".equals(current))continue;
            String ownerState=NoteStore.restoreOwnerState(context,id);
            if("committed".equals(ownerState)){state.put(note.itemId,"committed");persist("committed");continue;}
            if("pending".equals(ownerState))NoteStore.rollbackPendingRestore(context,transactionId,note.itemId,id,
                    noteSha256.get(note.itemId),updatedAt.get(note.itemId),pdfSha256.get(note.itemId),coverSha256.get(note.itemId));
            else NoteStore.ensureRestoreTargetUnused(context,id);
            state.put(note.itemId,"rolled_back");persist("rolled_back");
        }
        staged.cleanupTransferred();
    }

    /** Commits complete note/PDF/assigned-cover groups. Existing notes are never overwritten. */
    synchronized Map<String, String> restoreNotes(Cancellation cancellation, Progress progress) throws Exception {
        if(v2Mode)return restoreNotesV2(cancellation,progress);
        int done = 0, total = noteIds.size();
        for (LibraryBackupManifest.Note note : staged.manifest().notes) {
            if ("committed".equals(state.get(note.itemId))) { done++; continue; }
            if (cancellation != null && cancellation.isCancelled()) { persist("cancelled"); break; }
            String id = noteIds.get(note.itemId);
            JSONObject document = new JSONObject(new String(readResource(note.noteResourceId), java.nio.charset.StandardCharsets.UTF_8));
            document=NoteStore.prepareRestoredDocument(document,document.optString("title","导入的笔记"),id,updatedAt.get(note.itemId));
            String ownerState=NoteStore.restoreOwnerState(context,id);
            if("committed".equals(ownerState)){
                NoteStore.requireRestoreOwner(context,transactionId,note.itemId,id,noteSha256.get(note.itemId),updatedAt.get(note.itemId),pdfSha256.get(note.itemId),coverSha256.get(note.itemId),"committed");
                state.put(note.itemId,"committed");persist("committed");done++;continue;
            }
            if(ownerState==null){
                NoteStore.beginRestoreOwnership(context,transactionId,note.itemId,id,noteSha256.get(note.itemId),updatedAt.get(note.itemId),pdfSha256.get(note.itemId),coverSha256.get(note.itemId));
            }else NoteStore.requireRestoreOwner(context,transactionId,note.itemId,id,noteSha256.get(note.itemId),updatedAt.get(note.itemId),pdfSha256.get(note.itemId),coverSha256.get(note.itemId),"pending");
            File pdf = null;
            if (note.pdfResourceId != null) pdf = staged.resourceFile(note.pdfResourceId);
            File targetNote=NoteStore.noteFileForBackup(context,id);
            File targetPdf=NoteStore.pdfFile(context,id);
            if(targetNote.exists()&&!NoteStore.fileHasSha(targetNote,noteSha256.get(note.itemId)))throw new IOException("RESTORE_TARGET_CONFLICT");
            if(state.get(note.itemId).equals("resources_promoted")&&targetNote.exists()){
                // The document/index may have reached disk before a crash; the pending marker still hides it.
                NoteStore.restorePreparedDocument(context,document,pdf,true,transactionId,note.itemId,noteSha256.get(note.itemId),pdfSha256.get(note.itemId),coverSha256.get(note.itemId));
                if(faults!=null)faults.afterNoteIndexedBeforeVisibleCommit(note.itemId);
                NoteStore.commitRestoreOwnership(context,transactionId,note.itemId,id,noteSha256.get(note.itemId),updatedAt.get(note.itemId),pdfSha256.get(note.itemId),coverSha256.get(note.itemId),
                        faults==null?null:()->faults.afterRestoreOwnerTemporarySynced(note.itemId));
                state.put(note.itemId,"committed");persist("committed");done++;continue;
            }
            if (note.coverResourceId != null) {
                LibraryBackupManifest.Resource cover=staged.manifest().resourceById(note.coverResourceId);
                CoverStore.restoreRaw(context,id,staged.resourceFile(note.coverResourceId),cover.byteLength,cover.sha256);
            }
            if(note.pdfResourceId!=null){LibraryBackupManifest.Resource pdfInfo=staged.manifest().resourceById(note.pdfResourceId);
                if(!targetPdf.exists())promoteResource(staged.resourceFile(note.pdfResourceId),targetPdf,pdfInfo.byteLength,pdfInfo.sha256);
                else if(!CoverStore.matchesRaw(targetPdf,pdfInfo.byteLength,pdfInfo.sha256))throw new IOException("RESTORE_TARGET_CONFLICT");}
            state.put(note.itemId, "resources_promoted"); persist("resources_promoted");
            if(faults!=null)faults.afterResourcesPromoted(note.itemId);
            NoteStore.Entry entry = NoteStore.restorePreparedDocument(context, document,pdf,true,
                    transactionId,note.itemId,noteSha256.get(note.itemId),pdfSha256.get(note.itemId),coverSha256.get(note.itemId));
            if(faults!=null)faults.afterNoteIndexedBeforeVisibleCommit(note.itemId);
            NoteStore.commitRestoreOwnership(context,transactionId,note.itemId,id,noteSha256.get(note.itemId),updatedAt.get(note.itemId),pdfSha256.get(note.itemId),coverSha256.get(note.itemId),
                    faults==null?null:()->faults.afterRestoreOwnerTemporarySynced(note.itemId));
            state.put(note.itemId, "committed"); persist("committed");
            done++;
            if (progress != null) progress.onProgress(done, total);
        }
        boolean allCommitted=true;for(String group:state.values())if(!"committed".equals(group))allCommitted=false;
        if(allCommitted){persist("complete");staged.cleanupTransferred();}
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(noteIds));
    }

    private Map<String,String> restoreNotesV2(Cancellation cancellation,Progress progress)throws Exception {
        if(v2Groups.isEmpty())throw new IOException("RESTORE_EMPTY");
        int done=0,total=v2Groups.size();
        for(V2Group group:v2Groups.values()){
            if("committed".equals(group.state)){done++;continue;}
            if(cancellation!=null&&cancellation.isCancelled()){persist("cancelled");break;}
            try(LibraryRestoreGroupV2.Lease lease=ledger.acquire(group.plan)){
                LibraryRestoreGroupV2.RecoveryPhase phase=lease.recoveryStatus();
                if(phase==LibraryRestoreGroupV2.RecoveryPhase.COMMITTED){group.state="committed";markNoteState(group,"committed");persist("restore_in_progress");done++;continue;}
                if(phase==LibraryRestoreGroupV2.RecoveryPhase.ROLLBACK_IN_PROGRESS||phase==LibraryRestoreGroupV2.RecoveryPhase.ROLLED_BACK)
                    throw new IOException("RESTORE_V2_ROLLBACK_RECOVERY_REQUIRED");
                if(phase==LibraryRestoreGroupV2.RecoveryPhase.COMMIT_RESERVED){
                    group.state="commit_reserved";persist("restore_in_progress");
                    lease.commit((member,proof)->{if((faults!=null&&!faults.allowCommitVerifier(group.sourceKey,member.memberId))||!verifyV2Installed(group,member,proof))throw new IOException("RESTORE_V2_MEMBER_VERIFY_FAILED");});
                }else{
                    group.state="installing";persist("restore_in_progress");
                    for(LibraryRestoreGroupV2.Member member:orderedMembers(group.plan)){
                        if(cancellation!=null&&cancellation.isCancelled()){persist("cancelled");return Collections.unmodifiableMap(new LinkedHashMap<>(noteIds));}
                        LibraryRestoreGroupV2.MemberProof proof=lease.proof(member.memberId);
                        if(proof==null)ensureV2MemberAbsent(member,group);
                        LibraryRestoreGroupV2.MemberProof expected=expectedV2Proof(group,member);
                        if(proof!=null&&!sameMemberProof(proof,expected))throw new IOException("RESTORE_V2_PROOF_CHANGED");
                        if(proof==null)lease.registerBeforeInstall(expected);
                        installV2Member(group,member,expected);
                        if(member.kind!=LibraryRestoreGroupV2.Kind.NOTE&&!verifyV2Installed(group,member,expected))throw new IOException("RESTORE_V2_MEMBER_VERIFY_FAILED");
                        persist("restore_in_progress");
                    }
                    if(faults!=null){faults.afterGroupMembersInstalled(group.sourceKey);faults.afterResourcesPromoted(group.sourceKey);}
                    if(group.noteItemId!=null){
                        LibraryBackupManifest.Note note=findNote(group.noteItemId);
                        LibraryRestoreGroupV2.Member member=group.plan.member(group.noteItemId,LibraryRestoreGroupV2.Kind.NOTE);
                        LibraryRestoreGroupV2.MemberProof proof=lease.proof(member.memberId);
                        if(faults!=null)faults.afterNoteIndexedBeforeVisibleCommit(group.sourceKey);
                        NoteStore.finishRestoreOwnershipV2(context,transactionId,note.itemId,
                                member.localObjectId,noteSha256.get(note.itemId),updatedAt.get(note.itemId),pdfSha256.get(note.itemId),coverSha256.get(note.itemId),
                                faults==null?null:()->faults.afterRestoreOwnerTemporarySynced(group.sourceKey));
                        if(faults!=null)faults.afterNoteOwnerCommittedBeforeGroupMarker(group.sourceKey);
                        if(!verifyV2Installed(group,member,proof))throw new IOException("RESTORE_V2_NOTE_VERIFY_FAILED");
                    }
                    group.state="ready_to_commit";persist("restore_in_progress");
                    lease.commit((member,proof)->{if((faults!=null&&!faults.allowCommitVerifier(group.sourceKey,member.memberId))||!verifyV2Installed(group,member,proof))throw new IOException("RESTORE_V2_MEMBER_VERIFY_FAILED");});
                }
                group.state="committed";markNoteState(group,"committed");persist("restore_in_progress");
            }
            done++;if(progress!=null)progress.onProgress(done,total);
        }
        boolean doneAll=true;for(V2Group group:v2Groups.values())if(!"committed".equals(group.state)&&!"rolled_back".equals(group.state))doneAll=false;
        if(doneAll){persist("complete");staged.cleanupTransferred();}
        return Collections.unmodifiableMap(new LinkedHashMap<>(noteIds));
    }

    private void ensureV2MemberAbsent(LibraryRestoreGroupV2.Member member,V2Group group)throws Exception {
        switch(member.kind){
            case NOTE: case PDF: case ASSIGNED_COVER:
                NoteStore.verifyRestoreV2MemberAbsent(context,member.localObjectId,member.kind);break;
            case VAULT: case VIDEO: case COVER_PRESET:
                RestoredLibraryMaterialStore.Kind kind=RestoredLibraryMaterialStore.Kind.valueOf(member.kind.name());
                if(!materialStore.isMemberNamespaceAbsent(transactionId,group.plan.groupId,member.localObjectId,kind))
                    throw new IOException("RESTORE_V2_RESIDUE");
                break;
        }
    }

    private LibraryRestoreGroupV2.MemberProof expectedV2Proof(V2Group group,LibraryRestoreGroupV2.Member member)throws Exception {
        String storage;
        if(member.kind==LibraryRestoreGroupV2.Kind.VAULT||member.kind==LibraryRestoreGroupV2.Kind.VIDEO||member.kind==LibraryRestoreGroupV2.Kind.COVER_PRESET){
            RestoredLibraryMaterialStore.CommitProof proof=previewMaterial(group,member);
            if(!member.payloadSha256.equals(proof.payloadSha256)||!java.util.Objects.equals(member.currentLocalNoteId,proof.currentLocalNoteId))
                throw new IOException("RESTORE_V2_MATERIAL_BINDING_CHANGED");
            storage=proof.bindingSha256;
        }else{
            Map<String,Object> binding=new java.util.TreeMap<>();binding.put("schema_version",2);binding.put("transaction_id",transactionId);
            binding.put("group_id",group.plan.groupId);binding.put("member_id",member.memberId);binding.put("kind",member.kind.name());
            binding.put("local_object_id",member.localObjectId);binding.put("current_local_note_id",member.currentLocalNoteId);
            binding.put("payload_sha256",member.payloadSha256);binding.put("semantic_binding_sha256",member.semanticBindingSha256);
            storage=sha256(LibraryBackupJson.encode(binding));
        }
        return new LibraryRestoreGroupV2.MemberProof(member.memberId,transactionId,group.plan.groupId,member.localObjectId,
                member.currentLocalNoteId,member.kind,member.payloadSha256,member.semanticBindingSha256,storage);
    }

    private void installV2Member(V2Group group,LibraryRestoreGroupV2.Member member,LibraryRestoreGroupV2.MemberProof proof)throws Exception {
        switch(member.kind){
            case NOTE:{
                LibraryBackupManifest.Note note=findNote(member.sourceItemId);
                NoteStore.beginRestoreOwnershipV2(context,transactionId,note.itemId,group.plan.groupId,member.memberId,
                        member.localObjectId,member.payloadSha256,updatedAt.get(note.itemId),pdfSha256.get(note.itemId),
                        coverSha256.get(note.itemId),member.payloadSha256,member.semanticBindingSha256,proof.storageBindingSha256);
                JSONObject source=new JSONObject(new String(readResource(note.noteResourceId),StandardCharsets.UTF_8));
                JSONObject prepared=NoteStore.prepareRestoredDocument(source,source.optString("title","导入的笔记"),
                        member.localObjectId,updatedAt.get(note.itemId));
                NoteStore.restorePreparedDocumentV2(context,prepared,null,transactionId,note.itemId,member.localObjectId,
                        noteSha256.get(note.itemId),updatedAt.get(note.itemId),pdfSha256.get(note.itemId),coverSha256.get(note.itemId));
                break;
            }
            case PDF:{
                LibraryBackupManifest.Note note=findNote(member.sourceItemId);LibraryBackupManifest.Resource r=staged.manifest().resourceById(member.sourceResourceId);
                File target=NoteStore.pdfFile(context,member.localObjectId);
                if(!NoteStore.fileHasSha(target,r.sha256))CoverStore.restoreRawToFile(staged.resourceFile(r.resourceId),target,r.byteLength,r.sha256);
                syncDirectory(target.getParentFile());break;
            }
            case ASSIGNED_COVER:{
                LibraryBackupManifest.Resource r=staged.manifest().resourceById(member.sourceResourceId);
                CoverStore.restoreRaw(context,member.localObjectId,staged.resourceFile(r.resourceId),r.byteLength,r.sha256);break;
            }
            case VAULT: case VIDEO: case COVER_PRESET:{
                RestoredLibraryMaterialStore.Prepared prepared=prepareMaterial(group,member);
                if(!sameMaterialProof(RestoredLibraryMaterialStore.proofFor(prepared.record),materialProof(proof)))
                    throw new IOException("RESTORE_V2_MATERIAL_BINDING_CHANGED");
                materialStore.promote(prepared);break;
            }
        }
    }

    private boolean verifyV2Installed(V2Group group,LibraryRestoreGroupV2.Member member,LibraryRestoreGroupV2.MemberProof proof)throws Exception {
        switch(member.kind){
            case NOTE:{
                LibraryBackupManifest.Note note=findNote(member.sourceItemId);
                NoteStore.verifyRestoreOwnershipV2(context,transactionId,note.itemId,group.plan.groupId,member.memberId,
                        member.localObjectId,noteSha256.get(note.itemId),updatedAt.get(note.itemId),pdfSha256.get(note.itemId),
                        coverSha256.get(note.itemId),member.payloadSha256,member.semanticBindingSha256,proof.storageBindingSha256,"committed");
                return NoteStore.fileHasSha(NoteStore.noteFileForBackup(context,member.localObjectId),noteSha256.get(note.itemId));
            }
            case PDF:{
                LibraryBackupManifest.Resource r=staged.manifest().resourceById(member.sourceResourceId);
                return NoteStore.fileHasSha(NoteStore.pdfFile(context,member.localObjectId),r.sha256);
            }
            case ASSIGNED_COVER:{
                LibraryBackupManifest.Resource r=staged.manifest().resourceById(member.sourceResourceId);
                return CoverStore.matchesRaw(CoverStore.coverFile(NoteStore.noteFileForBackup(context,member.localObjectId).getParentFile(),member.localObjectId),r.byteLength,r.sha256);
            }
            case VAULT: case VIDEO: case COVER_PRESET:
                return materialStore.verifyPromotedProof(materialProof(proof));
            default: throw new IOException("RESTORE_V2_MEMBER_KIND");
        }
    }

    private RestoredLibraryMaterialStore.CommitProof previewMaterial(V2Group group,LibraryRestoreGroupV2.Member m)throws Exception {
        RestoredLibraryMaterialStore.Kind kind=RestoredLibraryMaterialStore.Kind.valueOf(m.kind.name());
        if(kind==RestoredLibraryMaterialStore.Kind.VAULT)return materialStore.previewVaultV2(transactionId,group.plan.groupId,m.localObjectId,m.currentLocalNoteId,m.sourceItemId,staged);
        if(kind==RestoredLibraryMaterialStore.Kind.VIDEO)return materialStore.previewVideoV2(transactionId,group.plan.groupId,m.localObjectId,m.currentLocalNoteId,m.sourceItemId,staged);
        return materialStore.previewPresetV2(transactionId,group.plan.groupId,m.localObjectId,m.sourceItemId,staged);
    }
    private RestoredLibraryMaterialStore.Prepared prepareMaterial(V2Group group,LibraryRestoreGroupV2.Member m)throws Exception {
        RestoredLibraryMaterialStore.Kind kind=RestoredLibraryMaterialStore.Kind.valueOf(m.kind.name());
        if(kind==RestoredLibraryMaterialStore.Kind.VAULT)return materialStore.prepareVaultV2(transactionId,group.plan.groupId,m.localObjectId,m.currentLocalNoteId,m.sourceItemId,staged);
        if(kind==RestoredLibraryMaterialStore.Kind.VIDEO)return materialStore.prepareVideoV2(transactionId,group.plan.groupId,m.localObjectId,m.currentLocalNoteId,m.sourceItemId,staged);
        return materialStore.preparePresetV2(transactionId,group.plan.groupId,m.localObjectId,m.sourceItemId,staged);
    }
    private static RestoredLibraryMaterialStore.CommitProof materialProof(LibraryRestoreGroupV2.MemberProof p){
        return new RestoredLibraryMaterialStore.CommitProof(p.transactionId,p.groupId,p.localObjectId,
                RestoredLibraryMaterialStore.Kind.valueOf(p.kind.name()),p.payloadSha256,p.storageBindingSha256,p.currentLocalNoteId);
    }
    private static boolean sameMaterialProof(RestoredLibraryMaterialStore.CommitProof a,RestoredLibraryMaterialStore.CommitProof b){
        return a.transactionId.equals(b.transactionId)&&a.groupId.equals(b.groupId)&&a.localId.equals(b.localId)&&a.kind==b.kind&&
                a.payloadSha256.equals(b.payloadSha256)&&a.bindingSha256.equals(b.bindingSha256)&&
                java.util.Objects.equals(a.currentLocalNoteId,b.currentLocalNoteId);
    }
    private static boolean sameMemberProof(LibraryRestoreGroupV2.MemberProof a,LibraryRestoreGroupV2.MemberProof b){
        return a.memberId.equals(b.memberId)&&a.transactionId.equals(b.transactionId)&&a.groupId.equals(b.groupId)&&
                a.localObjectId.equals(b.localObjectId)&&a.kind==b.kind&&a.payloadSha256.equals(b.payloadSha256)&&
                a.semanticBindingSha256.equals(b.semanticBindingSha256)&&a.storageBindingSha256.equals(b.storageBindingSha256)&&
                java.util.Objects.equals(a.currentLocalNoteId,b.currentLocalNoteId);
    }
    private List<LibraryRestoreGroupV2.Member> orderedMembers(LibraryRestoreGroupV2.Plan plan){
        List<LibraryRestoreGroupV2.Member> out=new ArrayList<>(plan.members);
        out.sort((a,b)->Integer.compare(memberOrder(a.kind),memberOrder(b.kind)));return out;
    }
    private static int memberOrder(LibraryRestoreGroupV2.Kind kind){
        switch(kind){case NOTE:return 0;case PDF:return 1;case ASSIGNED_COVER:return 2;default:return 3;}
    }
    private LibraryBackupManifest.Note findNote(String itemId)throws IOException {
        for(LibraryBackupManifest.Note note:staged.manifest().notes)if(note.itemId.equals(itemId))return note;
        throw new IOException("RESTORE_V2_NOTE_MISSING");
    }
    private static void syncDirectory(File dir)throws Exception{
        FileDescriptor fd=Os.open(dir.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC,0);
        try{Os.fsync(fd);}finally{Os.close(fd);}
    }
    private void markNoteState(V2Group group,String value){if(group.noteItemId!=null)state.put(group.noteItemId,value);}

    private void rollbackV2()throws Exception {
        for(V2Group group:v2Groups.values()){
            if("committed".equals(group.state)||"rolled_back".equals(group.state))continue;
            try(LibraryRestoreGroupV2.Lease lease=ledger.acquire(group.plan)){
                LibraryRestoreGroupV2.RecoveryPhase phase=lease.recoveryStatus();
                if(phase==LibraryRestoreGroupV2.RecoveryPhase.COMMITTED){group.state="committed";markNoteState(group,"committed");persist("rollback_in_progress");continue;}
                if(phase==LibraryRestoreGroupV2.RecoveryPhase.ROLLED_BACK){group.state="rolled_back";persist("rollback_in_progress");continue;}
                if(phase==LibraryRestoreGroupV2.RecoveryPhase.COMMIT_RESERVED)throw new IOException("RESTORE_V2_COMMIT_RECOVERY_REQUIRED");
                lease.markRollbackIntent();group.state="rollback_in_progress";persist("rollback_in_progress");
                for(LibraryRestoreGroupV2.Member member:orderedMembers(group.plan)){
                    LibraryRestoreGroupV2.MemberProof proof=lease.proof(member.memberId);
                    if(proof==null){ensureV2MemberAbsent(member,group);continue;}
                    if(!sameMemberProof(proof,expectedV2Proof(group,member)))throw new IOException("RESTORE_V2_PROOF_CHANGED");
                    if(member.kind==LibraryRestoreGroupV2.Kind.VAULT||member.kind==LibraryRestoreGroupV2.Kind.VIDEO||member.kind==LibraryRestoreGroupV2.Kind.COVER_PRESET)
                        materialStore.rollback(materialProof(proof),candidate->lease.gate().isCommitted(candidate),lease.pendingRollbackGate());
                    else if(member.kind==LibraryRestoreGroupV2.Kind.ASSIGNED_COVER)
                        CoverStore.rollbackRestoredRaw(context,member.localObjectId,member.payloadSha256,lease,proof);
                    else if(member.kind==LibraryRestoreGroupV2.Kind.NOTE){
                        if(NoteStore.restoreV2ReferenceAbsent(context,member.localObjectId)){
                            NoteStore.verifyRestoreV2MemberAbsent(context,member.localObjectId,LibraryRestoreGroupV2.Kind.NOTE);
                        } else NoteStore.rollbackRestoreOwnershipV2(context,transactionId,group.noteItemId,group.plan.groupId,member.memberId,
                                member.localObjectId,noteSha256.get(group.noteItemId),updatedAt.get(group.noteItemId),pdfSha256.get(group.noteItemId),
                                coverSha256.get(group.noteItemId),member.payloadSha256,member.semanticBindingSha256,proof.storageBindingSha256);
                    }
                    if(faults!=null)faults.afterRollbackMemberProcessed(group.sourceKey,member.memberId);
                }
                lease.markRolledBack(member->ensureV2MemberAbsent(member,group));
                group.state="rolled_back";markNoteState(group,"rolled_back");persist("rollback_in_progress");
            }
        }
        persist("rolled_back");staged.cleanupTransferred();
    }

    private static void promoteResource(File source,File target,long size,String sha)throws Exception{
        if(target.exists())throw new IOException("RESTORE_TARGET_CONFLICT");
        File temp=new File(target.getParentFile(),".restore-"+UUID.randomUUID()+".tmp");
        try{
            CoverStore.restoreRawToFile(source,temp,size,sha);
            if(target.exists()||!temp.renameTo(target))throw new IOException("RESTORE_RESOURCE_PROMOTE_FAILED");
            FileDescriptor dir=Os.open(target.getParentFile().getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC,0);
            try{Os.fsync(dir);}finally{Os.close(dir);}
        }catch(Exception e){if(temp.exists()&&CoverStore.matchesRaw(temp,size,sha))temp.delete();throw e;}
    }

    private byte[] readResource(String resourceId) throws Exception {
        LibraryBackupManifest.Resource metadata = staged.manifest().resourceById(resourceId);
        if (metadata == null || metadata.byteLength > LibraryBackupManifest.MAX_NOTE_BYTES)
            throw new IOException("RESTORE_RESOURCE_INVALID");
        File file = staged.resourceFile(resourceId);
        Struct identity = inspect(file);
        if (!identity.regular || identity.links != 1 || identity.size != metadata.byteLength)
            throw new IOException("RESTORE_RESOURCE_INVALID");
        byte[] bytes = new byte[(int) identity.size];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IOException("RESTORE_RESOURCE_CHANGED");
                offset += count;
            }
            if (input.read() != -1 || !identity.equals(inspect(file))) throw new IOException("RESTORE_RESOURCE_CHANGED");
        }
        return bytes;
    }

    private void persist(String phase) throws Exception {
        if(v2Mode){persistV2(phase);return;}
        JSONArray groups = new JSONArray();
        for (String item : noteIds.keySet()) groups.put(new JSONObject().put("item_id", item)
                .put("local_note_id", noteIds.get(item)).put("state", state.get(item))
                .put("updated_at",updatedAt.get(item)).put("note_sha256",noteSha256.get(item))
                .put("pdf_sha256",pdfSha256.get(item)==null?JSONObject.NULL:pdfSha256.get(item))
                .put("cover_sha256",coverSha256.get(item)==null?JSONObject.NULL:coverSha256.get(item)));
        JSONObject value = new JSONObject().put("schema_version", 1).put("transaction_id", transactionId)
                .put("archive_sha256", archiveSha256).put("stage_directory",stageDirectory.getAbsolutePath())
                .put("transfer_marker_sha256",transferMarkerSha256==null?JSONObject.NULL:transferMarkerSha256)
                .put("phase", phase).put("groups", groups);
        File temp = new File(root, "journal-"+UUID.randomUUID()+".tmp");
        FileDescriptor tempFd=Os.open(temp.getAbsolutePath(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600);
        try (FileOutputStream output = OwnedFdStreams.output(tempFd)) {
            output.write(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.flush(); output.getFD().sync();
        }
        FileDescriptor dir = Os.open(root.getAbsolutePath(), OsConstants.O_RDONLY | AndroidFileCompat.O_CLOEXEC, 0);
        try { Os.fsync(dir); } finally { Os.close(dir); }
        File target = new File(root, "journal.json");
        Os.rename(temp.getAbsolutePath(),target.getAbsolutePath());
        dir=Os.open(root.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC,0);
        try{Os.fsync(dir);}finally{Os.close(dir);}
    }

    private void persistV2(String phase)throws Exception {
        List<Object> rows=new ArrayList<>();
        for(V2Group group:v2Groups.values()){
            List<Object> members=new ArrayList<>();
            for(LibraryRestoreGroupV2.Member m:group.plan.members){
                Map<String,Object> member=new java.util.TreeMap<>();member.put("member_id",m.memberId);
                member.put("source_item_id",m.sourceItemId);member.put("source_resource_id",m.sourceResourceId);
                member.put("source_note_item_id",m.sourceNoteItemId);member.put("local_object_id",m.localObjectId);
                member.put("current_local_note_id",m.currentLocalNoteId);member.put("kind",m.kind.name());
                member.put("byte_length",m.byteLength);member.put("payload_sha256",m.payloadSha256);
                member.put("semantic_binding_sha256",m.semanticBindingSha256);members.add(member);
            }
            Map<String,Object> row=new java.util.TreeMap<>();row.put("source_group_key",group.sourceKey);
            row.put("group_id",group.plan.groupId);row.put("state",group.state);row.put("note_item_id",group.noteItemId);
            row.put("local_note_id",group.noteItemId==null?null:noteIds.get(group.noteItemId));
            row.put("updated_at",group.noteItemId==null?null:updatedAt.get(group.noteItemId));
            row.put("note_sha256",group.noteItemId==null?null:noteSha256.get(group.noteItemId));
            row.put("pdf_sha256",group.noteItemId==null?null:pdfSha256.get(group.noteItemId));
            row.put("cover_sha256",group.noteItemId==null?null:coverSha256.get(group.noteItemId));
            row.put("members",members);rows.add(row);
        }
        Map<String,Object> value=new java.util.TreeMap<>();value.put("schema_version",2);value.put("transaction_id",transactionId);
        value.put("archive_sha256",archiveSha256);value.put("stage_directory",stageDirectory.getAbsolutePath());
        value.put("transfer_marker_sha256",transferMarkerSha256);value.put("phase",phase);value.put("groups",rows);
        byte[] bytes=LibraryBackupJson.encode(value);File temp=new File(root,"journal-"+UUID.randomUUID()+".tmp");
        FileDescriptor fd=Os.open(temp.getAbsolutePath(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600);
        try(FileOutputStream out=OwnedFdStreams.output(fd)){out.write(bytes);out.flush();out.getFD().sync();}
        File target=new File(root,"journal.json");FileDescriptor dir=Os.open(root.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC,0);
        try{Os.fsync(dir);}finally{Os.close(dir);}Os.rename(temp.getAbsolutePath(),target.getAbsolutePath());
        dir=Os.open(root.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC,0);try{Os.fsync(dir);}finally{Os.close(dir);}
    }

    private static boolean regularOwned(File file)throws Exception{android.system.StructStat s=Os.lstat(file.getAbsolutePath());return (s.st_mode&OsConstants.S_IFMT)==OsConstants.S_IFREG&&s.st_nlink==1;}
    private static byte[] readNoFollow(File file,int max)throws Exception{
        FileDescriptor fd=Os.open(file.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);
        try(FileInputStream in=OwnedFdStreams.input(fd);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()>max-n)throw new IOException("RESTORE_JOURNAL_LIMIT");out.write(b,0,n);}return out.toByteArray();}
    }

    private static Struct inspect(File path) throws Exception {
        android.system.StructStat st = Os.lstat(path.getAbsolutePath());
        return new Struct((st.st_mode & OsConstants.S_IFMT) == OsConstants.S_IFREG,
                st.st_nlink, st.st_size, st.st_dev, st.st_ino, st.st_mtime, st.st_ctime);
    }
    private static String sha256(byte[] bytes)throws Exception{
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder(hash.length*2);
        for(byte value:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();
    }
    private static final class Struct {
        final boolean regular; final long links,size,dev,ino,mtime,ctime;
        Struct(boolean r,long l,long s,long d,long i,long m,long c){regular=r;links=l;size=s;dev=d;ino=i;mtime=m;ctime=c;}
        @Override public boolean equals(Object o){if(!(o instanceof Struct))return false;Struct x=(Struct)o;return regular==x.regular&&links==x.links&&size==x.size&&dev==x.dev&&ino==x.ino&&mtime==x.mtime&&ctime==x.ctime;}
    }
}
