package com.padnote.android;

import android.content.Context;

import com.padnote.android.streaming.StreamingGroupStore;
import padnote.material.StorageAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Converts one profile-validated archive note into a selected local target's complete update. */
@android.annotation.TargetApi(27)
public final class ArchiveManualUpdateAdapter {
    private ArchiveManualUpdateAdapter() { }

    private static final class PreparedUpdate implements AutoCloseable {
        final ManualUpdatePreviewSession session;
        final NativeManualUpdatePlan plan;
        final StreamingGroupStore.ContentInput pdf,cover;
        final List<StreamingGroupStore.MemberInput> videoInputs,vaultInputs;
        PreparedUpdate(ManualUpdatePreviewSession session,NativeManualUpdatePlan plan,
                StreamingGroupStore.ContentInput pdf,StreamingGroupStore.ContentInput cover,
                List<StreamingGroupStore.MemberInput> videoInputs,List<StreamingGroupStore.MemberInput> vaultInputs) {
            this.session=session;this.plan=plan;this.pdf=pdf;this.cover=cover;
            this.videoInputs=videoInputs;this.vaultInputs=vaultInputs;
        }
        @Override public void close(){session.close();}
    }

    public static StreamingGroupStore.Snapshot update(
            Context context, LibraryBackupArchive.StagedArchive staged,
            String sourceNoteItemId, String targetLocalNoteId,
            Map<String,String> explicitSourceToDestination,
            StreamingGroupStore.Store store, ManualUpdateCommitter.Preview preview) throws Exception {
        if(store==null||preview==null)throw new IllegalArgumentException("MANUAL_UPDATE_INPUT_REQUIRED");
        ManualUpdatePreviewSession.TargetToken selected=ManualUpdatePreviewSession.TargetToken.capture(store,targetLocalNoteId);
        try(PreparedUpdate prepared=prepareInternal(context,staged,sourceNoteItemId,targetLocalNoteId,
                explicitSourceToDestination,selected,null,store)) {
            return ManualUpdateCommitter.commit(store,prepared.plan,prepared.pdf,prepared.cover,
                    prepared.videoInputs,prepared.vaultInputs,preview);
        }
    }

    /** Prepares a read-only preview. It never calls the committer or publishes a marker. */
    public static ManualUpdatePreviewSession preparePreview(Context context,
            LibraryBackupArchive.StagedArchive staged,String sourceNoteItemId,String targetLocalNoteId,
            Map<String,String> explicitSourceToDestination,ManualUpdatePreviewSession.TargetToken selected,
            StreamingGroupStore.Store store) throws Exception {
        return preparePreview(context,staged,sourceNoteItemId,targetLocalNoteId,
                explicitSourceToDestination,selected,null,store);
    }

    public static ManualUpdatePreviewSession preparePreview(Context context,
            LibraryBackupArchive.StagedArchive staged,String sourceNoteItemId,String targetLocalNoteId,
            Map<String,String> explicitSourceToDestination,ManualUpdatePreviewSession.TargetToken selected,
            String expectedArchiveSha256,StreamingGroupStore.Store store) throws Exception {
        return prepareInternal(context,staged,sourceNoteItemId,targetLocalNoteId,
                explicitSourceToDestination,selected,expectedArchiveSha256,store).session;
    }

    private static PreparedUpdate prepareInternal(Context context,
            LibraryBackupArchive.StagedArchive staged,String sourceNoteItemId,String targetLocalNoteId,
            Map<String,String> explicitSourceToDestination,ManualUpdatePreviewSession.TargetToken selected,
            String expectedArchiveSha256,StreamingGroupStore.Store store) throws Exception {
        if(context==null||staged==null||sourceNoteItemId==null||targetLocalNoteId==null||store==null||selected==null)
            throw new IllegalArgumentException("MANUAL_UPDATE_INPUT_REQUIRED");
        if(!targetLocalNoteId.equals(selected.localId()))throw new IOException("UPDATE_TARGET_TOKEN_ID_MISMATCH");
        if(expectedArchiveSha256!=null&&!expectedArchiveSha256.equals(staged.archiveSha256()))
            throw new IOException("UPDATE_ARCHIVE_CHANGED");
        LibraryBackupManifest manifest=staged.manifest();
        if(manifest.formatVersion!=2)throw new IOException("UPDATE_PROFILE_REQUIRED_COPY_ONLY");
        if(!"android".equals(manifest.producer.platform))throw new IOException("UPDATE_SOURCE_PLATFORM_UNSUPPORTED_COPY_ONLY");
        LibraryBackupManifest.Note sourceNote=findNote(manifest,sourceNoteItemId);
        if(sourceNote==null||sourceNote.noteSchemaVersion!=8)throw new IOException("UPDATE_SOURCE_PROFILE_UNSUPPORTED");
        ArchiveUpdateProfile profile=findProfile(manifest,sourceNoteItemId);
        if(profile==null||!profile.sourceNoteId.equals(sourceNote.sourceNoteId))throw new IOException("UPDATE_PROFILE_REQUIRED_COPY_ONLY");
        Map<String,String> mapping=validateMapping(sourceNote,manifest,profile,targetLocalNoteId,explicitSourceToDestination);
        // Read current authority once and require the exact token captured at target selection.
        StreamingGroupStore.Snapshot base=store.readByLocalId(targetLocalNoteId);
        NoteGroupFacade.requireActiveManualUpdateTarget(base);
        if(!selected.matches(base))throw new IOException("BASE_CAS_CONFLICT");
        Map<String,LibraryBackupManifest.Resource> resources=new HashMap<>();
        for(LibraryBackupManifest.Resource r:manifest.resources)resources.put(r.resourceId,r);
        byte[] sourceBody=readVerified(staged,resources.get(sourceNote.noteResourceId),LibraryBackupManifest.MAX_NOTE_BYTES);
        if(!hex(sha(sourceBody)).equals(profile.bodySha256))throw new IOException("UPDATE_PROFILE_BODY_DIGEST");
        String rawBody=decodeStrict(sourceBody);
        JSONObject sourceNoteDocument=new JSONObject(rawBody);
        int sourcePageCount=sourceNoteDocument.optInt("pageCount",1);
        if(sourcePageCount<1)throw new IOException("UPDATE_SOURCE_PAGE_COUNT_INVALID");
        ArchiveUpdateProfile.requireExactTimes(rawBody,sourceNote.sourceNoteId,profile.timestamps);
        NativeManualUpdatePlan.SelectedTarget selectedTarget=new NativeManualUpdatePlan.SelectedTarget(
                targetLocalNoteId,base.lineage,base.revision,base.digest);
        NativeManualUpdatePlan.Provenance provenance=new NativeManualUpdatePlan.Provenance(
                NativeManualUpdatePlan.SourceKind.TRANSPORTED,profile.sourceLineageId,profile.sourceNoteId,
                profile.sourceRevisionId,profile.timeSidecar());

        LibraryBackupManifest.Resource pdfResource=sourceNote.pdfResourceId==null?null:resources.get(sourceNote.pdfResourceId);
        LibraryBackupManifest.Resource coverResource=sourceNote.coverResourceId==null?null:resources.get(sourceNote.coverResourceId);
        StreamingGroupStore.ContentInput pdf=content(staged,pdfResource,LibraryBackupManifest.MAX_PDF_BYTES);
        StreamingGroupStore.ContentInput cover=content(staged,coverResource,LibraryBackupManifest.MAX_PNG_BYTES);
        List<NativeManualUpdatePlan.Material> videoPlans=new ArrayList<>(),vaultPlans=new ArrayList<>();
        // Validate the user's explicit mapping in archive IDs first, then translate Vault
        // rows to the canonical material IDs required by the native storage verifier.
        Map<String,String> nativeMapping=new HashMap<>(mapping);
        List<StreamingGroupStore.ContentInput> videoContent=new ArrayList<>(),vaultContent=new ArrayList<>();
        List<File> scratchFiles=new ArrayList<>();
        boolean handedOff=false;
        try {
        JSONObject manifestJson=new JSONObject(decodeStrict(manifest.toJsonBytes()));
        JSONArray videoRows=manifestJson.getJSONArray("video_attachments");
        for(LibraryBackupManifest.VideoAttachment row:manifest.videoAttachments){
            if(!sourceNote.itemId.equals(row.noteItemId)||!"linked_note".equals(row.sourceState))continue;
            LibraryBackupManifest.Resource resource=resources.get(row.resourceId);
            StreamingGroupStore.ContentInput input=content(staged,resource,LibraryBackupManifest.MAX_VIDEO_BYTES);
            String sourceMaterialId=videoSourceMaterialId(row,profile);
            String rawMetadata=videoMetadata(findJsonRow(videoRows,row.itemId),profile.sourceNoteId,profile.sourceLineageId);
            if(nativeMapping.remove(row.itemId)==null||nativeMapping.put(sourceMaterialId,mapping.get(row.itemId))!=null)
                throw new IOException("UPDATE_VIDEO_ID_PROJECTION_COLLISION");
            StorageAdapter.Association association=new StorageAdapter.Association(sourceMaterialId,
                    profile.sourceLineageId,"linked_note",profile.sourceLineageId);
            videoPlans.add(new NativeManualUpdatePlan.Material(NativeManualUpdatePlan.Role.VIDEO,
                    sourceMaterialId,mapping.get(row.itemId),row.mediaType,manifest.producer.platform,rawMetadata,
                    association,new NativeManualUpdatePlan.Facts(input.size,input.sha256)));
            videoContent.add(input);
        }
        for(LibraryBackupManifest.VaultEntry row:manifest.vaultEntries){
            if(!sourceNote.itemId.equals(row.noteItemId)||!"linked_note".equals(row.sourceState))continue;
            if(row.sourceStorageResourceId==null)throw new IOException("UPDATE_VAULT_STORAGE_PROFILE_REQUIRED_COPY_ONLY");
            LibraryBackupManifest.Resource resource=resources.get(row.resourceId);
            byte[] archived=readVerified(staged,resource,LibraryBackupManifest.MAX_VAULT_BYTES);
            JSONObject payload=new JSONObject(decodeStrict(archived));
            LibraryBackupManifest.Resource storageResource=resources.get(row.sourceStorageResourceId);
            byte[] sourceMarkdown=readVerified(staged,storageResource,LibraryBackupManifest.MAX_VAULT_BYTES);
            String rawMetadata=decodeStrict(sourceMarkdown);
            String sourceMaterialId=ArchiveUpdateProfile.vaultSourceMaterialId(profile,row,storageResource);
            validateVaultSource(rawMetadata,payload,row,sourceMaterialId);
            if(nativeMapping.remove(row.itemId)==null||nativeMapping.put(sourceMaterialId,mapping.get(row.itemId))!=null)
                throw new IOException("UPDATE_VAULT_ID_PROJECTION_COLLISION");
            StreamingGroupStore.ContentInput input=writeBoundedScratch(staged,sourceMarkdown,scratchFiles);
            StorageAdapter.Association association=new StorageAdapter.Association(sourceMaterialId,
                    profile.sourceLineageId,"linked_note",profile.sourceLineageId);
            vaultPlans.add(new NativeManualUpdatePlan.Material(NativeManualUpdatePlan.Role.VAULT,
                    sourceMaterialId,mapping.get(row.itemId),"text/markdown",manifest.producer.platform,rawMetadata,
                    association,new NativeManualUpdatePlan.Facts(input.size,input.sha256)));
            vaultContent.add(input);
        }

        NativeManualUpdatePlan plan=NativeManualUpdatePlan.createManualUpdate(selectedTarget,provenance,sourceBody,
                pdf==null?NativeManualUpdatePlan.Presence.ABSENT:NativeManualUpdatePlan.Presence.PRESENT,
                facts(pdf),cover==null?NativeManualUpdatePlan.Presence.ABSENT:NativeManualUpdatePlan.Presence.PRESENT,
                facts(cover),videoPlans,vaultPlans,nativeMapping);
        StreamingGroupStore.ContentInput targetCover=cover;
        List<StreamingGroupStore.MemberInput> videoInputs=new ArrayList<>(),vaultInputs=new ArrayList<>();
        for(int i=0;i<videoPlans.size();i++)videoInputs.add(plan.videoMemberInput(videoPlans.get(i).sourceId(),videoContent.get(i)));
        for(int i=0;i<vaultPlans.size();i++){
            NativeManualUpdatePlan.Material material=vaultPlans.get(i);
            byte[] destination=plan.destinationVaultContent(material.sourceId());
            StreamingGroupStore.ContentInput projected=writeBoundedScratch(staged,destination,scratchFiles);
            vaultInputs.add(plan.vaultMemberInput(material.sourceId(),projected));
        }
        List<ManualUpdatePreviewSession.ResourceFact> incoming=new ArrayList<>();
        byte[] projectedBody=plan.previewBodyUtf8();
        incoming.add(new ManualUpdatePreviewSession.ResourceFact("body",sourceNote.noteResourceId,
                targetLocalNoteId,"application/json",projectedBody.length,plan.destinationBodySha256(),
                sourceNote.noteResourceId,null));
        incoming.add(previewFact("pdf",sourceNote.pdfResourceId,targetLocalNoteId,"application/pdf",pdf));
        incoming.add(previewFact("cover",sourceNote.coverResourceId,targetLocalNoteId,"image/png",cover));
        for(int i=0;i<videoPlans.size();i++){
            NativeManualUpdatePlan.Material material=videoPlans.get(i);StreamingGroupStore.ContentInput input=videoContent.get(i);
            byte[] descriptor=plan.previewMaterialDescriptor(material.sourceId());
            incoming.add(new ManualUpdatePreviewSession.ResourceFact("video",material.sourceId(),
                    material.destinationId(),material.mediaType(),input.size,input.sha256,null,input.source.toFile(),
                    descriptor.length,hex(sha(descriptor)),material.association().sourceState(),
                    material.association().sourceLineageId(),material.association().ownerLineageId()));
        }
        for(int i=0;i<vaultPlans.size();i++){
            NativeManualUpdatePlan.Material material=vaultPlans.get(i);StreamingGroupStore.ContentInput input=vaultInputs.get(i).content;
            byte[] descriptor=plan.previewMaterialDescriptor(material.sourceId());
            incoming.add(new ManualUpdatePreviewSession.ResourceFact("vault",material.sourceId(),
                    material.destinationId(),material.mediaType(),input.size,input.sha256,null,input.source.toFile(),
                    descriptor.length,hex(sha(descriptor)),material.association().sourceState(),
                    material.association().sourceLineageId(),material.association().ownerLineageId()));
        }
        ManualUpdatePreviewSession result=new ManualUpdatePreviewSession(staged,selected,base,sourceNoteItemId,
                profile.sourceLineageId,profile.sourceRevisionId,mapping,plan,scratchFiles,incoming);
        handedOff=true;
        return new PreparedUpdate(result,plan,pdf,targetCover,videoInputs,vaultInputs);
        } finally {
            if(!handedOff)for(File scratch:scratchFiles)try{staged.discardPreviewCopy(scratch);}catch(IOException ignored){/* archive stage retains cleanup ownership */}
        }
    }

    private static void validateVaultSource(String markdown,JSONObject payload,
                                            LibraryBackupManifest.VaultEntry row,String sourceMaterialId)throws Exception {
        if(!row.sourceNoteId.equals(payload.getString("source_note_id"))
                ||row.sourceRevisionMs!=payload.getLong("source_revision_ms")
                ||row.createdAtMs!=payload.getLong("created_at_ms"))
            throw new IOException("UPDATE_VAULT_METADATA_MISMATCH");
        StorageAdapter.androidVaultMarkdown(markdown.getBytes(StandardCharsets.UTF_8),
                new StorageAdapter.Association(sourceMaterialId,null,"independent",null));
        if(!row.sourceNoteId.equals(StorageAdapter.activeNoteBindingId(markdown,"android","VAULT")))
            throw new IOException("UPDATE_VAULT_SOURCE_BINDING_MISMATCH");
        String head=markdown.substring(0,Math.min(markdown.length(),4096));
        if(!payload.getString("title").equals(VaultStore.frontValue(head,"title"))
                ||row.sourceRevisionMs!=parseVaultHeaderLong(VaultStore.frontValue(head,"source-modified"))
                ||row.createdAtMs!=parseVaultHeaderLong(VaultStore.frontValue(head,"digitized-epoch"))
                ||!payload.getString("markdown").equals(vaultBody(markdown)))
            throw new IOException("UPDATE_VAULT_SOURCE_METADATA_MISMATCH");
    }

    static String vaultBody(String markdown)throws IOException {
        if(markdown==null||!markdown.startsWith("---\n"))throw new IOException("VAULT_FORMAT_INVALID");
        int end=markdown.indexOf("\n---\n",4);
        if(end<0)throw new IOException("VAULT_FORMAT_INVALID");
        return markdown.substring(end+5);
    }

    private static long parseVaultHeaderLong(String value)throws IOException {
        try{long parsed=Long.parseLong(value);if(parsed<0)throw new NumberFormatException();return parsed;}
        catch(NumberFormatException invalid){throw new IOException("UPDATE_VAULT_SOURCE_TIME_INVALID",invalid);}
    }

    private static Map<String,String> validateMapping(LibraryBackupManifest.Note note,LibraryBackupManifest manifest,
            ArchiveUpdateProfile profile,String target,Map<String,String> given)throws IOException {
        if(given==null)throw new IOException("UPDATE_MAPPING_REQUIRED");
        Set<String> sources=new HashSet<>();sources.add(profile.sourceNoteId);
        for(LibraryBackupManifest.VideoAttachment v:manifest.videoAttachments)if(note.itemId.equals(v.noteItemId)&&"linked_note".equals(v.sourceState))sources.add(v.itemId);
        for(LibraryBackupManifest.VaultEntry v:manifest.vaultEntries)if(note.itemId.equals(v.noteItemId)&&"linked_note".equals(v.sourceState))sources.add(v.itemId);
        if(!given.keySet().equals(sources)||!target.equals(given.get(profile.sourceNoteId)))throw new IOException("UPDATE_MAPPING_SET_MISMATCH");
        Set<String> destinations=new HashSet<>();for(String value:given.values())if(value==null||value.isEmpty()||!destinations.add(value))throw new IOException("UPDATE_MAPPING_NOT_ONE_TO_ONE");
        for(LibraryBackupManifest.VideoAttachment v:manifest.videoAttachments)if(note.itemId.equals(v.noteItemId)&&"linked_note".equals(v.sourceState)
                &&!given.get(v.itemId).matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw new IOException("UPDATE_VIDEO_DESTINATION_UUID_REQUIRED");
        for(LibraryBackupManifest.VaultEntry v:manifest.vaultEntries)if(note.itemId.equals(v.noteItemId)&&"linked_note".equals(v.sourceState)
                &&!given.get(v.itemId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw new IOException("UPDATE_VAULT_DESTINATION_UUID_REQUIRED");
        return Collections.unmodifiableMap(new HashMap<>(given));
    }
    private static LibraryBackupManifest.Note findNote(LibraryBackupManifest manifest,String id){for(LibraryBackupManifest.Note n:manifest.notes)if(n.itemId.equals(id))return n;return null;}
    private static ArchiveUpdateProfile findProfile(LibraryBackupManifest manifest,String note){for(ArchiveUpdateProfile p:manifest.updateProfiles)if(p.noteItemId.equals(note))return p;return null;}
    private static JSONObject findJsonRow(JSONArray rows,String itemId)throws Exception {for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(itemId.equals(row.getString("item_id")))return row;}throw new IOException("UPDATE_VIDEO_DESCRIPTOR_MISSING");}
    static String videoMetadata(JSONObject row,String sourceNoteId,String sourceLineage)throws Exception {
        JSONObject cp=row.getJSONObject("connection_provenance");String id=row.getString("item_id");
        String origin=row.getString("origin_kind");
        if(row.isNull("task_id")||row.isNull("artifact_id")||cp.isNull("connection_id")||cp.isNull("kind"))
            throw new IOException("UPDATE_VIDEO_METADATA_UNSUPPORTED");
        if(cp.isNull("connection_revision")&&!("restored_archive".equals(origin)))
            throw new IOException("UPDATE_VIDEO_METADATA_UNSUPPORTED");
        String persistedId=UUID.nameUUIDFromBytes(("PadNote/archive-material/v2\0"+sourceLineage+"\0"+id).getBytes(StandardCharsets.UTF_8)).toString();
        JSONObject result=new JSONObject().put("id",persistedId).put("noteId",sourceNoteId)
                .put("sourceRevision",row.getLong("source_revision_ms"))
                .put("sourceBundleSha256",row.getString("source_bundle_sha256"))
                .put("taskPayloadSha256",row.isNull("task_payload_sha256")?JSONObject.NULL:row.getString("task_payload_sha256"))
                .put("connectionRevision",cp.isNull("connection_revision")?0:cp.getLong("connection_revision"))
                .put("kind",cp.getString("kind"))
                .put("transport",cp.isNull("transport")?JSONObject.NULL:cp.getString("transport"))
                .put("certSha256",cp.isNull("certificate_sha256")?JSONObject.NULL:cp.getString("certificate_sha256"))
                .put("taskId",row.getString("task_id"))
                .put("remoteTaskId",row.isNull("remote_task_id")?"":row.getString("remote_task_id"))
                .put("connectionId",cp.getString("connection_id"))
                .put("bridgeId",cp.isNull("bridge_id")?JSONObject.NULL:cp.getString("bridge_id"))
                .put("instanceId",cp.isNull("instance_id")?JSONObject.NULL:cp.getString("instance_id"))
                .put("artifactId",row.getString("artifact_id"))
                .put("name",row.getString("display_name")).put("mediaType",row.getString("media_type"))
                .put("sizeBytes",row.getLong("byte_length")).put("sha256",row.getString("sha256"))
                .put("storedName","video-"+persistedId+".mp4").put("createdAt",row.getLong("created_at_ms"))
                .put("originKind",origin).put("sourceState",row.getString("source_state"))
                .put("sourceNoteId",row.getString("source_note_id"))
                .put("sourceRevisionPrecisionMs",row.getInt("source_revision_precision_ms"))
                .put("digestKind",row.getString("digest_kind")).put("offlineState",row.getString("offline_state"));
        return result.toString();
    }
    static String videoSourceMaterialId(LibraryBackupManifest.VideoAttachment row,ArchiveUpdateProfile profile) {
        return UUID.nameUUIDFromBytes(("PadNote/archive-material/v2\0"+profile.sourceLineageId+"\0"+row.itemId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static StreamingGroupStore.ContentInput content(LibraryBackupArchive.StagedArchive staged,LibraryBackupManifest.Resource r,long cap)throws Exception {
        if(r==null)return null;File file=staged.resourceFile(r.resourceId);if(r.byteLength>cap||!file.isFile()||file.length()!=r.byteLength)throw new IOException("UPDATE_RESOURCE_INVALID");
        byte[] digest=shaFile(file,r.byteLength);if(!hex(digest).equals(r.sha256))throw new IOException("UPDATE_RESOURCE_HASH_MISMATCH");
        return new StreamingGroupStore.ContentInput(file.toPath(),r.byteLength,r.sha256,null);
    }
    private static ManualUpdatePreviewSession.ResourceFact previewFact(String role,String sourceId,
            String destinationId,String mediaType,StreamingGroupStore.ContentInput input) {
        return input==null
                ?new ManualUpdatePreviewSession.ResourceFact(role,sourceId,destinationId,mediaType,-1,null,null,null)
                :new ManualUpdatePreviewSession.ResourceFact(role,sourceId,destinationId,mediaType,
                        input.size,input.sha256,sourceId,input.source.toFile());
    }
    private static byte[] readVerified(LibraryBackupArchive.StagedArchive staged,LibraryBackupManifest.Resource r,long cap)throws Exception {
        if(r==null||r.byteLength>cap||r.byteLength>Integer.MAX_VALUE)throw new IOException("UPDATE_RESOURCE_INVALID");
        File f=staged.resourceFile(r.resourceId);if(!f.isFile()||f.length()!=r.byteLength)throw new IOException("UPDATE_RESOURCE_INVALID");
        byte[] bytes=Files.readAllBytes(f.toPath());if(bytes.length!=r.byteLength||!hex(sha(bytes)).equals(r.sha256))throw new IOException("UPDATE_RESOURCE_HASH_MISMATCH");return bytes;
    }
    private static StreamingGroupStore.ContentInput writeBoundedScratch(LibraryBackupArchive.StagedArchive staged,byte[] bytes,List<File> scratchFiles)throws Exception {
        if(bytes==null||bytes.length>LibraryBackupManifest.MAX_VAULT_BYTES)
            throw new IOException("UPDATE_SCRATCH_SIZE_INVALID");
        String hash=hex(sha(bytes));File scratch=staged.createPreviewScratch(bytes,hash);
        scratchFiles.add(scratch);
        return new StreamingGroupStore.ContentInput(scratch.toPath(),bytes.length,hash,null);
    }
    private static NativeManualUpdatePlan.Facts facts(StreamingGroupStore.ContentInput input){return input==null?null:new NativeManualUpdatePlan.Facts(input.size,input.sha256);}
    private static byte[] shaFile(File file,long expected)throws Exception {MessageDigest d=MessageDigest.getInstance("SHA-256");try(java.io.InputStream in=new java.io.FileInputStream(file)){byte[] b=new byte[65536];long n=0;for(int r;(r=in.read(b))!=-1;){if(r==0)continue;n+=r;if(n>expected)throw new IOException("UPDATE_RESOURCE_GREW");d.update(b,0,r);}if(n!=expected)throw new IOException("UPDATE_RESOURCE_TRUNCATED");}return d.digest();}
    private static byte[] sha(byte[] bytes)throws Exception{return MessageDigest.getInstance("SHA-256").digest(bytes);}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String decodeStrict(byte[] bytes)throws CharacterCodingException{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
}
