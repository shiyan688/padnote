package com.padnote.android;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.json.JSONObject;
import padnote.material.StorageAdapter;
import padnote.material.MaterialCodec;
import com.padnote.android.streaming.LegacyFileCapture;
import com.padnote.android.streaming.StreamingFeatureGate;
import com.padnote.android.streaming.StreamingGroupStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Application facade for publishing and reading complete note groups. Editor save, bookshelf
 * rename, and non-destructive shelf retirement use whole-group compare-and-swap revisions.
 * Manual archive update/adoption remains isolated from production UI until all ordinary writers
 * and conflict flows are migrated and accepted.
 */
@android.annotation.TargetApi(27)
public final class NoteGroupFacade {
    public interface Confirm { boolean approve(CapturedLegacyNote oldNote, StreamingGroupStore.VerifiedGroup staged) throws Exception; }
    public static final class CapturedLegacyNote {
        public final String localId, fingerprint; public final long updatedAt; public final int pageCount;
        public final boolean pdfPresent, coverPresent; public final int videoCount, linkedVaultCount;
        private CapturedLegacyNote(String id,String hash,long time,int pages,boolean pdf,boolean cover,int videos,int vault){localId=id;fingerprint=hash;updatedAt=time;pageCount=pages;pdfPresent=pdf;coverPresent=cover;videoCount=videos;linkedVaultCount=vault;}
    }
    private static final class InputMaterial { final NativeManualUpdatePlan.Material plan; final StreamingGroupStore.ContentInput content; InputMaterial(NativeManualUpdatePlan.Material p,StreamingGroupStore.ContentInput c){plan=p;content=c;} }
    private static final class MediaMutation {
        enum Role { VIDEO, VAULT }
        enum Action { ADD, REPLACE, REMOVE }
        final Role role; final Action action; final String materialId;
        final VideoAttachmentStore.Attachment video; final LegacyFileCapture.Result videoContent;
        final byte[] vaultMarkdown;
        MediaMutation(Role role,Action action,String materialId,VideoAttachmentStore.Attachment video,
                LegacyFileCapture.Result videoContent,byte[] vaultMarkdown) {
            this.role=role;this.action=action;this.materialId=materialId;this.video=video;
            this.videoContent=videoContent;this.vaultMarkdown=vaultMarkdown==null?null:vaultMarkdown.clone();
        }
    }
    private static final class Capture {
        final String id,lineage,fingerprint; final byte[] body; final long updatedAt; final int pages;
        final NativeManualUpdatePlan.Facts pdf,cover; final StreamingGroupStore.ContentInput pdfInput,coverInput;
        final List<InputMaterial> videos,vault; final CapturedLegacyNote summary; final NativeManualUpdatePlan plan;
        Capture(String id,String lineage,String fingerprint,byte[] body,long updatedAt,int pages,NativeManualUpdatePlan.Facts pdf,StreamingGroupStore.ContentInput pdfInput,NativeManualUpdatePlan.Facts cover,StreamingGroupStore.ContentInput coverInput,List<InputMaterial> videos,List<InputMaterial> vault,CapturedLegacyNote summary,NativeManualUpdatePlan plan){this.id=id;this.lineage=lineage;this.fingerprint=fingerprint;this.body=body;this.updatedAt=updatedAt;this.pages=pages;this.pdf=pdf;this.pdfInput=pdfInput;this.cover=cover;this.coverInput=coverInput;this.videos=videos;this.vault=vault;this.summary=summary;this.plan=plan;}
    }
    public static final class CommittedScratchCleanupException extends IOException {
        private final StreamingGroupStore.Snapshot committedSnapshot;
        CommittedScratchCleanupException(StreamingGroupStore.Snapshot snapshot,IOException cause) {
            super("GROUP_EDITOR_SAVE_COMMITTED_SCRATCH_CLEANUP_FAILED",cause);committedSnapshot=snapshot;
        }
        public StreamingGroupStore.Snapshot committedSnapshot(){return committedSnapshot;}
    }

    private final Context context; private final StreamingGroupStore.Store store;
    public NoteGroupFacade(Context context) throws Exception { this(context,null,null); }
    /** Test-only failure injection; application callers must use the no-observer constructor. */
    NoteGroupFacade(Context context,StreamingGroupStore.CommitObserver observer) throws Exception {
        this(context,observer,null);
    }
    /** Catalog profiling is opt-in and is passed only from one debuggable NoteStore.list call. */
    NoteGroupFacade(Context context,StreamingGroupStore.CommitObserver observer,
                    StreamingGroupStore.ReadDiagnostics readDiagnostics) throws Exception {
        if(context==null)throw new IllegalArgumentException("APPLICATION_CONTEXT_REQUIRED");Context app=context.getApplicationContext();this.context=app==null?context:app;
        if(!StreamingFeatureGate.isSupported())throw new UnsupportedOperationException("GROUP_FACADE_REQUIRES_API_27");
        File root=new File(this.context.getFilesDir(),"note-groups");
        StreamingGroupStore.ProjectionVerifier projection=new StreamingGroupStore.ProjectionVerifier(){
            @Override public void verifyBody(byte[] body)throws IOException { try { String json=strictUtf8(body); JSONObject value=NotePrecisionJsonParser.parseObject(json); NativeManualUpdatePlan.validateActualNote(value,value.getString("id")); } catch(Exception e){throw new IOException("GROUP_BODY_INVALID",e);} }
            @Override public void verifyMetadata(String role,String materialId,byte[] metadata)throws IOException { try { MaterialCodec.DescriptorSummary d=MaterialCodec.inspectDescriptor(metadata);String expected=role.equals("video")?"video":role.equals("vault")?"vault":"";if(!expected.equals(d.kind())||!materialId.equals(d.materialId()))throw new IOException("GROUP_MATERIAL_DESCRIPTOR_MISMATCH"); } catch(IOException e){throw e;}catch(Exception e){throw new IOException("GROUP_MATERIAL_DESCRIPTOR_INVALID",e);} }
        };
        this.store=StreamingFeatureGate.openStore(this.context,root,projection,NativeManualUpdatePlan.durableVerifier(),observer,readDiagnostics);
    }
    public StreamingGroupStore.Snapshot openGroup(String localNoteId)throws IOException {
        StreamingGroupStore.Snapshot snapshot=store.readByLocalId(localNoteId);
        if(snapshot!=null&&isRetired(snapshot))throw new IOException("GROUP_NOTE_RETIRED");
        return snapshot;
    }
    /** Captures the immutable target token when a user selects an existing managed note. */
    public ManualUpdatePreviewSession.TargetToken captureManualUpdateTarget(String localNoteId)throws IOException {
        StreamingGroupStore.Snapshot snapshot=openGroup(localNoteId);
        if(snapshot==null)throw new IOException("UPDATE_TARGET_REQUIRES_GROUP_ADOPTION");
        return ManualUpdatePreviewSession.TargetToken.from(snapshot);
    }
    /** Prepares strict archive facts against the target token captured at explicit selection. */
    public ManualUpdatePreviewSession prepareArchiveUpdatePreview(LibraryBackupArchive.StagedArchive staged,
            String sourceNoteItemId,Map<String,String> sourceToDestination,
            ManualUpdatePreviewSession.TargetToken selected)throws Exception {
        if(selected==null)throw new IOException("UPDATE_TARGET_TOKEN_REQUIRED");
        return ArchiveManualUpdateAdapter.preparePreview(context,staged,sourceNoteItemId,
                selected.localId(),sourceToDestination,selected,store);
    }
    /** Reopens a staged preview only for the exact archive and target expectation previously captured. */
    public ManualUpdatePreviewSession reopenArchiveUpdatePreview(LibraryBackupArchive.StagedArchive staged,
            ManualUpdatePreviewSession.PreviewBinding binding)throws Exception {
        if(binding==null)throw new IOException("UPDATE_PREVIEW_REOPEN_BINDING_REQUIRED");
        ManualUpdatePreviewSession prepared=ArchiveManualUpdateAdapter.preparePreview(context,staged,
                binding.sourceNoteItemId(),binding.targetToken().localId(),binding.explicitMapping(),
                binding.targetToken(),binding.archiveSha256(),store);
        if(!binding.matches(prepared)) {
            prepared.close();
            throw new IOException("UPDATE_PREVIEW_BINDING_CHANGED");
        }
        return prepared;
    }
    static void requireActiveManualUpdateTarget(StreamingGroupStore.Snapshot snapshot)throws IOException {
        if(snapshot==null)throw new IOException("UPDATE_TARGET_REQUIRES_GROUP_ADOPTION");
        if(isRetired(snapshot))throw new IOException("GROUP_NOTE_RETIRED");
    }
    /** Current committed snapshot for explicit history/recovery UI, including shelf tombstones. */
    public StreamingGroupStore.Snapshot openGroupIncludingRetired(String localNoteId)throws IOException {
        return store.readByLocalId(localNoteId);
    }
    /** Direct lookups can force a new member hash without sharing a prior object witness. */
    void resetCatalogReadWitnessesForFreshLookup(){store.resetObjectVerificationWitnessesForFreshRead();}
    /** Builds an invocation-local marker inventory; callers must discard it when their read scope ends. */
    StreamingGroupStore.Store.VisibleCatalog readVisibleCatalog(java.util.Set<String> requestedLocalIds,
            StreamingGroupStore.Store.CatalogProjector projector)throws IOException{return store.readVisibleCatalog(requestedLocalIds,projector);}
    /** Reads an immutable retained revision by its complete identity, not by the current marker. */
    public StreamingGroupStore.Snapshot openGroupRevision(String localNoteId,String revision,String digest)throws IOException {
        StreamingGroupStore.Snapshot current=store.readByLocalId(localNoteId);
        if(current==null)throw new IOException("UPDATE_TARGET_REQUIRES_GROUP_ADOPTION");
        return store.readRevision(current.lineage,revision,digest);
    }
    /** Isolated archive-to-existing-group seam; production callers remain unwired until route migration. */
    public StreamingGroupStore.Snapshot updateFromArchive(LibraryBackupArchive.StagedArchive staged,
            String sourceNoteItemId,String targetLocalNoteId,Map<String,String> mapping,
            ManualUpdateCommitter.Preview preview)throws Exception {
        openGroup(targetLocalNoteId);
        return ArchiveManualUpdateAdapter.update(context,staged,sourceNoteItemId,targetLocalNoteId,mapping,store,preview);
    }

    /** Renames a group only if the revision observed when the rename UI opened is still current. */
    public StreamingGroupStore.Snapshot renameGroup(String localNoteId,String expectedRevision,
            String expectedDigest,String requestedTitle)throws Exception {
        StreamingGroupStore.Snapshot base=store.readByLocalId(localNoteId);
        requireCurrent(base,expectedRevision,expectedDigest);
        if(isRetired(base))throw new IOException("GROUP_NOTE_RETIRED");
        JSONObject body=readBody(base);
        return saveEditorRevision(localNoteId,expectedRevision,expectedDigest,requestedTitle,body.toString());
    }

    /**
     * Hides a note by publishing a whole-group tombstone revision. Its previous immutable
     * revisions and every media member remain stored; this operation never purges member files.
     */
    public StreamingGroupStore.Snapshot retireGroup(String localNoteId,String expectedRevision,
            String expectedDigest)throws Exception {
        StreamingGroupStore.Snapshot base=store.readByLocalId(localNoteId);
        requireCurrent(base,expectedRevision,expectedDigest);
        if(isRetired(base))throw new IOException("GROUP_NOTE_RETIRED");
        JSONObject body=readBody(base);
        body.put("_padnoteShelfState","retired").put("_padnoteShelfRetiredAt",System.currentTimeMillis());
        return publishEditorRevision(localNoteId,expectedRevision,expectedDigest,
                body.optString("title",""),body.toString(),true);
    }

    /**
     * Publishes an editor body change as one compare-and-swap revision while carrying every
     * existing PDF, cover, video and linked Vault member forward unchanged. The editor's
     * captured revision/digest is mandatory; stale editors cannot overwrite a newer marker.
     */
    public StreamingGroupStore.Snapshot saveEditorRevision(String localNoteId,String expectedRevision,
            String expectedDigest,String requestedTitle,String canvasJson)throws Exception {
        return publishEditorRevision(localNoteId,expectedRevision,expectedDigest,requestedTitle,canvasJson,false,false,null);
    }

    /** Replaces only the assigned-cover member in a revision captured when the picker opened. */
    public StreamingGroupStore.Snapshot replaceCoverRevision(String localNoteId,String expectedRevision,
            String expectedDigest,byte[] pngBytes)throws Exception {
        if(pngBytes==null||pngBytes.length==0||pngBytes.length>StreamingGroupStore.COVER_MAX)
            throw new IOException("GROUP_COVER_INPUT_INVALID");
        byte[] stablePng=pngBytes.clone();
        StreamingGroupStore.Snapshot base=store.readByLocalId(localNoteId);
        requireCurrent(base,expectedRevision,expectedDigest);
        if(isRetired(base))throw new IOException("GROUP_NOTE_RETIRED");
        JSONObject body=readBody(base);
        return publishEditorRevision(localNoteId,expectedRevision,expectedDigest,
                body.optString("title",""),NoteJsonCodec.stringify(body),false,true,stablePng);
    }

    /** Removes only the assigned-cover member while preserving every other group member. */
    public StreamingGroupStore.Snapshot removeCoverRevision(String localNoteId,String expectedRevision,
            String expectedDigest)throws Exception {
        StreamingGroupStore.Snapshot base=store.readByLocalId(localNoteId);
        requireCurrent(base,expectedRevision,expectedDigest);
        if(isRetired(base))throw new IOException("GROUP_NOTE_RETIRED");
        JSONObject body=readBody(base);
        return publishEditorRevision(localNoteId,expectedRevision,expectedDigest,
                body.optString("title",""),NoteJsonCodec.stringify(body),false,true,null);
    }

    /** Adds a verified linked video to a captured whole-group revision. */
    public StreamingGroupStore.Snapshot attachVideoRevision(String localNoteId,String expectedRevision,
            String expectedDigest,VideoAttachmentStore.Attachment attachment,
            LegacyFileCapture.Result verifiedVideo)throws Exception {
        StreamingGroupStore.Snapshot base=store.readByLocalId(localNoteId);
        requireCurrent(base,expectedRevision,expectedDigest);
        if(isRetired(base))throw new IOException("GROUP_NOTE_RETIRED");
        if(attachment==null||verifiedVideo==null||!localNoteId.equals(attachment.noteId)
                ||!"linked_note".equals(attachment.sourceState)||!localNoteId.equals(attachment.sourceNoteId)
                ||!canonicalMaterialUuid(attachment.id)
                ||!("video-"+attachment.id+".mp4").equals(attachment.storedName))
            throw new IOException("GROUP_VIDEO_INPUT_IDENTITY_INVALID");
        try { VideoAttachmentStore.Attachment.parse(attachment.json()); }
        catch(Exception invalid) { throw new IOException("GROUP_VIDEO_METADATA_INVALID",invalid); }
        if(verifiedVideo.size<=0||verifiedVideo.size>StreamingGroupStore.VIDEO_MAX
                ||verifiedVideo.size!=attachment.sizeBytes
                ||!verifiedVideo.sha256.equalsIgnoreCase(attachment.sha256))
            throw new IOException("GROUP_VIDEO_INPUT_FACTS_MISMATCH");
        JSONObject body=readBody(base);
        return publishEditorRevision(localNoteId,expectedRevision,expectedDigest,
                body.optString("title",""),NoteJsonCodec.stringify(body),false,false,null,
                new MediaMutation(MediaMutation.Role.VIDEO,MediaMutation.Action.ADD,attachment.id,
                        attachment,verifiedVideo,null));
    }

    /** Removes a linked video member without changing any other media member. */
    public StreamingGroupStore.Snapshot removeVideoRevision(String localNoteId,String expectedRevision,
            String expectedDigest,String materialId)throws Exception {
        StreamingGroupStore.Snapshot base=store.readByLocalId(localNoteId);
        requireCurrent(base,expectedRevision,expectedDigest);
        if(isRetired(base))throw new IOException("GROUP_NOTE_RETIRED");
        if(!canonicalMaterialUuid(materialId))throw new IOException("GROUP_VIDEO_ID_INVALID");
        JSONObject body=readBody(base);
        return publishEditorRevision(localNoteId,expectedRevision,expectedDigest,
                body.optString("title",""),NoteJsonCodec.stringify(body),false,false,null,
                new MediaMutation(MediaMutation.Role.VIDEO,MediaMutation.Action.REMOVE,materialId,
                        null,null,null));
    }

    /** Publishes one new linked Vault item using its stable UUID and complete Markdown bytes. */
    public StreamingGroupStore.Snapshot publishVaultRevision(String localNoteId,String expectedRevision,
            String expectedDigest,String materialId,byte[] markdown)throws Exception {
        return mutateVaultRevision(localNoteId,expectedRevision,expectedDigest,materialId,markdown,
                MediaMutation.Action.ADD);
    }

    /** Replaces only one linked Vault item, preserving its material UUID. */
    public StreamingGroupStore.Snapshot replaceVaultRevision(String localNoteId,String expectedRevision,
            String expectedDigest,String materialId,byte[] markdown)throws Exception {
        return mutateVaultRevision(localNoteId,expectedRevision,expectedDigest,materialId,markdown,
                MediaMutation.Action.REPLACE);
    }

    /** Removes one linked Vault item while retaining the complete prior revision. */
    public StreamingGroupStore.Snapshot deleteVaultRevision(String localNoteId,String expectedRevision,
            String expectedDigest,String materialId)throws Exception {
        return mutateVaultRevision(localNoteId,expectedRevision,expectedDigest,materialId,null,
                MediaMutation.Action.REMOVE);
    }

    private StreamingGroupStore.Snapshot mutateVaultRevision(String localNoteId,String expectedRevision,
            String expectedDigest,String materialId,byte[] markdown,MediaMutation.Action action)throws Exception {
        StreamingGroupStore.Snapshot base=store.readByLocalId(localNoteId);
        requireCurrent(base,expectedRevision,expectedDigest);
        if(isRetired(base))throw new IOException("GROUP_NOTE_RETIRED");
        if(!canonicalMaterialUuid(materialId))throw new IOException("GROUP_VAULT_ID_INVALID");
        byte[] stableMarkdown=markdown==null?null:markdown.clone();
        if(action==MediaMutation.Action.REMOVE) {
            if(stableMarkdown!=null)throw new IOException("GROUP_VAULT_REMOVE_CONTENT_INVALID");
        } else {
            validateGroupVaultMarkdown(localNoteId,base.lineage,materialId,stableMarkdown);
        }
        JSONObject body=readBody(base);
        return publishEditorRevision(localNoteId,expectedRevision,expectedDigest,
                body.optString("title",""),NoteJsonCodec.stringify(body),false,false,null,
                new MediaMutation(MediaMutation.Role.VAULT,action,materialId,null,null,stableMarkdown));
    }

    private static void validateGroupVaultMarkdown(String localNoteId,String lineage,String materialId,byte[] markdown)
            throws IOException {
        if(markdown==null||markdown.length==0||markdown.length>StreamingGroupStore.VAULT_MAX)
            throw new IOException("GROUP_VAULT_INPUT_SIZE_INVALID");
        String text=strictUtf8(markdown);
        String header=text.substring(0,Math.min(text.length(),4096));
        if(!text.startsWith("---\n")||!localNoteId.equals(VaultStore.frontValue(header,"note-id")))
            throw new IOException("GROUP_VAULT_OWNER_MISMATCH");
        try {
            StorageAdapter.androidVaultMarkdown(markdown,
                    new StorageAdapter.Association(materialId,lineage,"linked_note",lineage));
        } catch(Exception invalid) { throw new IOException("GROUP_VAULT_MARKDOWN_INVALID",invalid); }
    }

    private void addVaultPlan(String localNoteId,StreamingGroupStore.Snapshot base,String materialId,
            byte[] markdown,File scratchRoot,Map<String,String> mapping,
            List<NativeManualUpdatePlan.Material> plans,List<StreamingGroupStore.ContentInput> contents)
            throws Exception {
        validateGroupVaultMarkdown(localNoteId,base.lineage,materialId,markdown);
        File file=writeEditorScratch(scratchRoot,markdown);
        LegacyFileCapture.Result captured=LegacyFileCapture.inspect(context,file,StreamingGroupStore.VAULT_MAX);
        if(captured.size!=markdown.length)throw new IOException("GROUP_VAULT_INPUT_CHANGED");
        StorageAdapter.Association association=new StorageAdapter.Association(materialId,base.lineage,
                "linked_note",base.lineage);
        NativeManualUpdatePlan.Material material=new NativeManualUpdatePlan.Material(
                NativeManualUpdatePlan.Role.VAULT,materialId,materialId,"text/markdown","android",
                strictUtf8(markdown),association,new NativeManualUpdatePlan.Facts(captured.size,captured.sha256));
        if(mapping.put(materialId,materialId)!=null)
            throw new IOException("GROUP_EDITOR_SAVE_MATERIAL_ID_COLLISION");
        plans.add(material);contents.add(captured.content());
    }

    private StreamingGroupStore.Snapshot publishEditorRevision(String localNoteId,String expectedRevision,
            String expectedDigest,String requestedTitle,String canvasJson,boolean retirementTransition)throws Exception {
        return publishEditorRevision(localNoteId,expectedRevision,expectedDigest,requestedTitle,canvasJson,
                retirementTransition,false,null);
    }

    private StreamingGroupStore.Snapshot publishEditorRevision(String localNoteId,String expectedRevision,
            String expectedDigest,String requestedTitle,String canvasJson,boolean retirementTransition,
            boolean replaceCover,byte[] replacementCover)throws Exception {
        return publishEditorRevision(localNoteId,expectedRevision,expectedDigest,requestedTitle,canvasJson,
                retirementTransition,replaceCover,replacementCover,null);
    }

    private StreamingGroupStore.Snapshot publishEditorRevision(String localNoteId,String expectedRevision,
            String expectedDigest,String requestedTitle,String canvasJson,boolean retirementTransition,
            boolean replaceCover,byte[] replacementCover,MediaMutation mediaMutation)throws Exception {
        if(localNoteId==null||expectedRevision==null||expectedDigest==null||canvasJson==null)
            throw new IllegalArgumentException("GROUP_EDITOR_SAVE_INPUT_REQUIRED");
        StreamingGroupStore.Snapshot base=store.readByLocalId(localNoteId);
        if(base==null||!base.revision.equals(expectedRevision)||!base.digest.equalsIgnoreCase(expectedDigest))
            throw new IOException("BASE_CAS_CONFLICT");
        if(isRetired(base))throw new IOException("GROUP_NOTE_RETIRED");

        File scratchRoot=createEditorScratchRoot();
        StreamingGroupStore.Snapshot committed=null;
        Throwable operationFailure=null;
        try {
            JSONObject body=NotePrecisionJsonParser.parseObject(canvasJson);
            NoteStore.validateDocument(body);
            boolean requestedRetirement="retired".equals(body.optString("_padnoteShelfState",""));
            if(requestedRetirement!=retirementTransition)
                throw new IOException("GROUP_RETIREMENT_REQUIRES_EXPLICIT_OPERATION");
            if(retirementTransition&&body.optLong("_padnoteShelfRetiredAt",0)<=0)
                throw new IOException("GROUP_RETIREMENT_TIMESTAMP_INVALID");
            String title=requestedTitle==null?"":requestedTitle.trim();
            if(title.isEmpty())title=body.optString("title","").trim();
            if(title.isEmpty())title="未命名笔记";
            if(title.length()>80)title=title.substring(0,80);
            long priorUpdated=body.optLong("updatedAt",0);
            body.put("id",localNoteId).put("title",title)
                    .put("updatedAt",Math.max(System.currentTimeMillis(),priorUpdated+1));
            NoteStore.validateDocument(body);
            byte[] exactBody=NoteJsonCodec.stringify(body).getBytes(StandardCharsets.UTF_8);

            Long pdfSize=base.memberSizes().get("pdf.bin");
            boolean pdfPresent=pdfSize!=null;
            StreamingGroupStore.ContentInput pdf=pdfPresent
                    ?captureSnapshotMember(base,"pdf.bin",scratchRoot,StreamingGroupStore.PDF_MAX):null;
            Long coverSize=base.memberSizes().get("cover.bin");
            boolean coverPresent;
            StreamingGroupStore.ContentInput cover;
            if(replaceCover) {
                coverPresent=replacementCover!=null;
                if(coverPresent) {
                    CoverStore.validatePng(replacementCover);
                    File replacementFile=writeEditorScratch(scratchRoot,replacementCover);
                    LegacyFileCapture.Result verified=LegacyFileCapture.inspect(context,replacementFile,
                            StreamingGroupStore.COVER_MAX);
                    if(verified.size!=replacementCover.length)throw new IOException("GROUP_COVER_SIZE_CHANGED");
                    cover=verified.content();
                } else cover=null;
            } else {
                coverPresent=coverSize!=null;
                cover=coverPresent
                        ?captureSnapshotMember(base,"cover.bin",scratchRoot,StreamingGroupStore.COVER_MAX):null;
            }

            Map<String,String> mapping=new HashMap<>();mapping.put(localNoteId,localNoteId);
            List<NativeManualUpdatePlan.Material> videoPlans=new ArrayList<>(),vaultPlans=new ArrayList<>();
            List<StreamingGroupStore.ContentInput> videoContents=new ArrayList<>(),vaultContents=new ArrayList<>();
            boolean mediaIdFound=false;
            for(VideoAttachmentStore.Attachment attachment:GroupAuthorityBridge.videoViews(base)){
                boolean selected=mediaMutation!=null&&mediaMutation.role==MediaMutation.Role.VIDEO
                        &&attachment.id.equals(mediaMutation.materialId);
                if(selected){
                    mediaIdFound=true;
                    if(mediaMutation.action==MediaMutation.Action.REMOVE)continue;
                    throw new IOException("GROUP_VIDEO_ID_ALREADY_PRESENT");
                }
                String member="video/"+attachment.id+"/content.bin";
                StreamingGroupStore.ContentInput content=captureSnapshotMember(base,member,scratchRoot,StreamingGroupStore.VIDEO_MAX);
                StorageAdapter.Association association=groupAssociation(attachment.id,base.lineage,attachment.sourceState);
                NativeManualUpdatePlan.Material material=new NativeManualUpdatePlan.Material(
                        NativeManualUpdatePlan.Role.VIDEO,attachment.id,attachment.id,attachment.mediaType,"android",
                        attachment.json().toString(),association,new NativeManualUpdatePlan.Facts(content.size,content.sha256));
                if(mapping.put(attachment.id,attachment.id)!=null)throw new IOException("GROUP_EDITOR_SAVE_MATERIAL_ID_COLLISION");
                videoPlans.add(material);videoContents.add(content);
            }
            if(mediaMutation!=null&&mediaMutation.role==MediaMutation.Role.VIDEO){
                if(mediaMutation.action==MediaMutation.Action.REMOVE&&!mediaIdFound)
                    throw new IOException("GROUP_VIDEO_MEMBER_NOT_FOUND");
                if(mediaMutation.action==MediaMutation.Action.ADD){
                    if(mediaIdFound)throw new IOException("GROUP_VIDEO_ID_ALREADY_PRESENT");
                    VideoAttachmentStore.Attachment attachment=mediaMutation.video;
                    if(attachment==null||mediaMutation.videoContent==null)
                        throw new IOException("GROUP_VIDEO_INPUT_REQUIRED");
                    LegacyFileCapture.Result stagedVideo=LegacyFileCapture.copyTo(context,
                            mediaMutation.videoContent,new File(scratchRoot,UUID.randomUUID()+".mp4"),
                            StreamingGroupStore.VIDEO_MAX);
                    if(stagedVideo.size!=attachment.sizeBytes
                            ||!stagedVideo.sha256.equalsIgnoreCase(attachment.sha256))
                        throw new IOException("GROUP_VIDEO_INPUT_FACTS_MISMATCH");
                    StreamingGroupStore.ContentInput content=stagedVideo.content();
                    StorageAdapter.Association association=groupAssociation(attachment.id,base.lineage,
                            attachment.sourceState);
                    NativeManualUpdatePlan.Material material=new NativeManualUpdatePlan.Material(
                            NativeManualUpdatePlan.Role.VIDEO,attachment.id,attachment.id,attachment.mediaType,"android",
                            attachment.json().toString(),association,new NativeManualUpdatePlan.Facts(content.size,content.sha256));
                    if(mapping.put(attachment.id,attachment.id)!=null)
                        throw new IOException("GROUP_EDITOR_SAVE_MATERIAL_ID_COLLISION");
                    videoPlans.add(material);videoContents.add(content);
                }
            }
            List<GroupAuthorityBridge.VaultView> vaultViews=GroupAuthorityBridge.vaultViews(base);
            for(GroupAuthorityBridge.VaultView vault:vaultViews){
                boolean selected=mediaMutation!=null&&mediaMutation.role==MediaMutation.Role.VAULT
                        &&vault.materialId.equals(mediaMutation.materialId);
                if(selected){
                    mediaIdFound=true;
                    if(mediaMutation.action==MediaMutation.Action.REMOVE)continue;
                    if(mediaMutation.action!=MediaMutation.Action.REPLACE)
                        throw new IOException("GROUP_VAULT_ID_ALREADY_PRESENT");
                    addVaultPlan(localNoteId,base,vault.materialId,mediaMutation.vaultMarkdown,
                            scratchRoot,mapping,vaultPlans,vaultContents);
                    continue;
                }
                String member="vault/"+vault.materialId+"/content.bin";
                StreamingGroupStore.ContentInput content=captureSnapshotMember(base,member,scratchRoot,StreamingGroupStore.VAULT_MAX);
                StorageAdapter.Association association=new StorageAdapter.Association(vault.materialId,base.lineage,"linked_note",base.lineage);
                NativeManualUpdatePlan.Material material=new NativeManualUpdatePlan.Material(
                        NativeManualUpdatePlan.Role.VAULT,vault.materialId,vault.materialId,"text/markdown","android",
                        new String(vault.markdown,StandardCharsets.UTF_8),association,
                        new NativeManualUpdatePlan.Facts(content.size,content.sha256));
                if(mapping.put(vault.materialId,vault.materialId)!=null)throw new IOException("GROUP_EDITOR_SAVE_MATERIAL_ID_COLLISION");
                vaultPlans.add(material);vaultContents.add(content);
            }
            if(mediaMutation!=null&&mediaMutation.role==MediaMutation.Role.VAULT){
                if(mediaMutation.action==MediaMutation.Action.ADD){
                    if(mediaIdFound)throw new IOException("GROUP_VAULT_ID_ALREADY_PRESENT");
                    addVaultPlan(localNoteId,base,mediaMutation.materialId,mediaMutation.vaultMarkdown,
                            scratchRoot,mapping,vaultPlans,vaultContents);
                }else if(!mediaIdFound){
                    throw new IOException("GROUP_VAULT_MEMBER_NOT_FOUND");
                }
            }
            NativeManualUpdatePlan.SelectedTarget selected=new NativeManualUpdatePlan.SelectedTarget(
                    localNoteId,base.lineage,base.revision,base.digest);
            NativeManualUpdatePlan.Provenance source=new NativeManualUpdatePlan.Provenance(
                    NativeManualUpdatePlan.SourceKind.NATIVE_ANDROID,base.lineage,localNoteId,base.revision,null);
            NativeManualUpdatePlan plan=NativeManualUpdatePlan.createManualUpdate(selected,source,exactBody,
                    pdfPresent?NativeManualUpdatePlan.Presence.PRESENT:NativeManualUpdatePlan.Presence.ABSENT,
                    facts(pdf),coverPresent?NativeManualUpdatePlan.Presence.PRESENT:NativeManualUpdatePlan.Presence.ABSENT,
                    facts(cover),videoPlans,vaultPlans,mapping);
            List<StreamingGroupStore.MemberInput> videoInputs=new ArrayList<>(),vaultInputs=new ArrayList<>();
            for(int i=0;i<videoPlans.size();i++)videoInputs.add(plan.videoMemberInput(videoPlans.get(i).sourceId(),videoContents.get(i)));
            for(int i=0;i<vaultPlans.size();i++){
                NativeManualUpdatePlan.Material material=vaultPlans.get(i);
                byte[] rebound=plan.destinationVaultContent(material.sourceId());
                File file=writeEditorScratch(scratchRoot,rebound);
                LegacyFileCapture.Result verified=LegacyFileCapture.inspect(context,file,StreamingGroupStore.VAULT_MAX);
                vaultInputs.add(plan.vaultMemberInput(material.sourceId(),verified.content()));
            }
            StreamingGroupStore.Snapshot published=ManualUpdateCommitter.commit(store,plan,pdf,cover,
                    videoInputs,vaultInputs,(current,incoming)->true);
            if(!localNoteId.equals(published.localId)||!base.lineage.equals(published.lineage)
                    ||published.revision.equals(base.revision))throw new IOException("GROUP_EDITOR_SAVE_RESULT_INVALID");
            committed=published;
            return committed;
        } catch(Throwable failure) {
            operationFailure=failure;
            if(failure instanceof Exception)throw (Exception)failure;
            if(failure instanceof Error)throw (Error)failure;
            throw new AssertionError(failure);
        } finally {
            try{removeEditorScratchRoot(scratchRoot);}
            catch(IOException cleanup){
                if(operationFailure!=null)operationFailure.addSuppressed(cleanup);
                else if(committed!=null)throw new CommittedScratchCleanupException(committed,cleanup);
                else throw cleanup;
            }
        }
    }

    private static void requireCurrent(StreamingGroupStore.Snapshot base,String revision,String digest)throws IOException {
        if(base==null)throw new IOException("UPDATE_TARGET_REQUIRES_GROUP_ADOPTION");
        if(revision==null||digest==null||!base.revision.equals(revision)||!base.digest.equalsIgnoreCase(digest))
            throw new IOException("BASE_CAS_CONFLICT");
    }

    private static JSONObject readBody(StreamingGroupStore.Snapshot snapshot)throws IOException {
        try{return NotePrecisionJsonParser.parseObject(strictUtf8(snapshot.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX)));}
        catch(Exception failure){if(failure instanceof IOException)throw (IOException)failure;throw new IOException("GROUP_BODY_INVALID",failure);}
    }

    private static boolean isRetired(StreamingGroupStore.Snapshot snapshot)throws IOException {
        return isRetiredDocument(readBody(snapshot));
    }

    static boolean isRetiredDocument(JSONObject body){
        return body!=null&&"retired".equals(body.optString("_padnoteShelfState",""));
    }

    private StorageAdapter.Association groupAssociation(String materialId,String lineage,String sourceState)throws IOException {
        if("linked_note".equals(sourceState))return new StorageAdapter.Association(materialId,lineage,sourceState,lineage);
        if("independent".equals(sourceState))return new StorageAdapter.Association(materialId,null,sourceState,null);
        if("source_deleted".equals(sourceState)||"source_not_selected".equals(sourceState))
            return new StorageAdapter.Association(materialId,lineage,sourceState,null);
        throw new IOException("GROUP_EDITOR_SAVE_SOURCE_STATE_INVALID");
    }

    private StreamingGroupStore.ContentInput captureSnapshotMember(StreamingGroupStore.Snapshot snapshot,
            String member,File directory,long cap)throws Exception {
        Long expectedSize=snapshot.memberSizes().get(member);
        if(expectedSize==null||expectedSize<=0||expectedSize>cap)throw new IOException("GROUP_EDITOR_SAVE_MEMBER_INVALID");
        String expectedSha=snapshot.memberSha256(member);
        File file=writeSnapshotMember(snapshot,member,directory,cap,expectedSize,expectedSha);
        LegacyFileCapture.Result verified=LegacyFileCapture.inspect(context,file,cap);
        if(verified.size!=expectedSize||!verified.sha256.equals(expectedSha))throw new IOException("GROUP_EDITOR_SAVE_MEMBER_CHANGED");
        return verified.content();
    }

    private File writeSnapshotMember(StreamingGroupStore.Snapshot snapshot,String member,File directory,
            long cap,long expectedSize,String expectedSha)throws Exception {
        File file=new File(directory,UUID.randomUUID()+".bin");
        java.io.FileDescriptor descriptor;
        try{descriptor=Os.open(file.getAbsolutePath(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL
                |OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600);}
        catch(ErrnoException error){throw new IOException("GROUP_EDITOR_SAVE_SCRATCH_CREATE",error);}
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;byte[] buffer=new byte[32*1024];
        try(FileOutputStream output=OwnedFdStreams.output(descriptor);InputStream input=snapshot.openContent(member)){
            for(int n;(n=input.read(buffer))!=-1;){if(n==0)continue;count=Math.addExact(count,n);
                if(count>cap||count>expectedSize)throw new IOException("GROUP_EDITOR_SAVE_MEMBER_SIZE_CHANGED");
                digest.update(buffer,0,n);output.write(buffer,0,n);}
            output.flush();output.getFD().sync();
        }catch(ArithmeticException overflow){throw new IOException("GROUP_EDITOR_SAVE_MEMBER_SIZE_OVERFLOW",overflow);}
        String actual=hex(digest.digest());
        if(count!=expectedSize||!actual.equals(expectedSha))throw new IOException("GROUP_EDITOR_SAVE_MEMBER_HASH_CHANGED");
        return file;
    }

    private File writeEditorScratch(File directory,byte[] bytes)throws Exception {
        File file=new File(directory,UUID.randomUUID()+".bin");java.io.FileDescriptor descriptor;
        try{descriptor=Os.open(file.getAbsolutePath(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL
                |OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600);}
        catch(ErrnoException error){throw new IOException("GROUP_EDITOR_SAVE_SCRATCH_CREATE",error);}
        try(FileOutputStream output=OwnedFdStreams.output(descriptor)){output.write(bytes);output.flush();output.getFD().sync();}
        return file;
    }

    private File createEditorScratchRoot()throws Exception {
        File files=context.getFilesDir();if(files==null)throw new IOException("GROUP_EDITOR_SAVE_FILES_ROOT_MISSING");
        File directory=new File(files,".group-editor-save-"+UUID.randomUUID());
        if(!directory.mkdir())throw new IOException("GROUP_EDITOR_SAVE_SCRATCH_DIRECTORY_CREATE");
        try{Os.chmod(directory.getAbsolutePath(),0700);StructStat root=Os.lstat(directory.getAbsolutePath());
            if(!OsConstants.S_ISDIR(root.st_mode)||OsConstants.S_ISLNK(root.st_mode)||root.st_uid!=android.os.Process.myUid()
                    ||!directory.getCanonicalFile().getParentFile().equals(files.getCanonicalFile()))
                throw new IOException("GROUP_EDITOR_SAVE_SCRATCH_DIRECTORY_UNSAFE");
            return directory;
        }catch(Exception failure){try{Os.remove(directory.getAbsolutePath());}catch(Exception ignored){}throw failure;}
    }

    private void removeEditorScratchRoot(File directory)throws IOException {
        StructStat root;
        try{root=Os.lstat(directory.getAbsolutePath());}catch(ErrnoException missing){if(missing.errno==OsConstants.ENOENT)return;throw new IOException("GROUP_EDITOR_SAVE_SCRATCH_CLEANUP");}
        if(!OsConstants.S_ISDIR(root.st_mode)||OsConstants.S_ISLNK(root.st_mode)||root.st_uid!=android.os.Process.myUid())
            throw new IOException("GROUP_EDITOR_SAVE_SCRATCH_CLEANUP");
        File[] children=directory.listFiles();if(children==null)throw new IOException("GROUP_EDITOR_SAVE_SCRATCH_CLEANUP");
        for(File child:children){StructStat item;try{item=Os.lstat(child.getAbsolutePath());}catch(ErrnoException error){throw new IOException("GROUP_EDITOR_SAVE_SCRATCH_CLEANUP",error);}
            if(!OsConstants.S_ISREG(item.st_mode)||OsConstants.S_ISLNK(item.st_mode)||item.st_uid!=android.os.Process.myUid()||item.st_nlink!=1)
                throw new IOException("GROUP_EDITOR_SAVE_SCRATCH_CLEANUP");
            try{Os.remove(child.getAbsolutePath());}catch(ErrnoException error){throw new IOException("GROUP_EDITOR_SAVE_SCRATCH_CLEANUP",error);}}
        try{Os.remove(directory.getAbsolutePath());}catch(ErrnoException error){throw new IOException("GROUP_EDITOR_SAVE_SCRATCH_CLEANUP",error);}
    }

    private static NativeManualUpdatePlan.Facts facts(StreamingGroupStore.ContentInput input){
        return input==null?null:new NativeManualUpdatePlan.Facts(input.size,input.sha256);
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static boolean canonicalMaterialUuid(String value){
        if(value==null)return false;
        try{return UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException invalid){return false;}
    }
    /** Explicit history restore seam used by the candidate regression; caller must preview and CAS. */
    public StreamingGroupStore.Snapshot restoreGroupRevision(String localNoteId,String revision,String digest,
            String expectedCurrentRevision,String expectedCurrentDigest,ManualUpdateCommitter.Preview preview)throws Exception {
        StreamingGroupStore.Snapshot current=store.readByLocalId(localNoteId);
        if(current==null)throw new IOException("UPDATE_TARGET_REQUIRES_GROUP_ADOPTION");
        if(!current.revision.equals(expectedCurrentRevision)||!current.digest.equals(expectedCurrentDigest))throw new IOException("BASE_CAS_CONFLICT");
        return store.restore(current.lineage,revision,digest,expectedCurrentRevision,expectedCurrentDigest,preview::approve);
    }
    /** Explicit UUID recovery; adoption journals cannot publish without a fresh source preview. */
    public StreamingGroupStore.Phase recoverTransaction(String transactionId)throws IOException { return store.recover(transactionId); }
    public CapturedLegacyNote inspectLegacy(String localNoteId)throws Exception { return capture(localNoteId,UUID.randomUUID().toString()).summary; }

    /**
     * Captures and verifies the complete local legacy group, then atomically publishes one marker.
     * Existing NoteStore files are left in place. The preview callback must show every member and
     * return true only after explicit user approval.
     */
    public StreamingGroupStore.Snapshot adoptLegacyNote(String localNoteId,Confirm confirm)throws Exception {
        if(confirm==null)throw new IllegalArgumentException("EXPLICIT_PREVIEW_REQUIRED");
        StreamingGroupStore.Snapshot existing=store.readByLocalId(localNoteId);if(existing!=null)return existing;
        String lineage=UUID.randomUUID().toString();Capture c=capture(localNoteId,lineage);
        NativeManualUpdatePlan adoptionPlan=c.plan.withLegacySourceGuard(()->{
            LegacyGroupMutationLock.Lease lease=LegacyGroupMutationLock.acquire();
            try {
                Capture current=capture(localNoteId,lineage);
                if(!c.fingerprint.equals(current.fingerprint))throw new IOException("LEGACY_SOURCE_CHANGED_BEFORE_PUBLICATION");
                return lease;
            } catch(Exception failure) { lease.close();throw failure; }
        });
        List<StreamingGroupStore.MemberInput> videoInputs=new ArrayList<>(),vaultInputs=new ArrayList<>();
        for(InputMaterial m:c.videos)videoInputs.add(adoptionPlan.videoMemberInput(m.plan.sourceId(),m.content));
        for(InputMaterial m:c.vault)vaultInputs.add(adoptionPlan.vaultMemberInput(m.plan.sourceId(),m.content));
        StreamingGroupStore.Envelope envelope=adoptionPlan.legacyAdoptionEnvelope(UUID.randomUUID().toString(),c.pdfInput,c.coverInput,videoInputs,vaultInputs);
        StreamingGroupStore.Preview guarded=adoptionPlan.verifyBeforeLegacyAdoptionPreview((current,staged)->{
            if(!c.fingerprint.equals(capture(localNoteId,lineage).fingerprint))throw new IOException("LEGACY_SOURCE_CHANGED_DURING_PREVIEW");
            return confirm.approve(c.summary,staged);
        });
        StreamingGroupStore.Snapshot committed=store.commitLegacyAdoptionV2(envelope,guarded,adoptionPlan);
        if(!committed.localId.equals(localNoteId)||!committed.lineage.equals(lineage))throw new IOException("ADOPTION_RESULT_IDENTITY_MISMATCH");
        return committed;
    }

    private Capture capture(String id,String lineage)throws Exception {
        NoteStore.Entry entry=null;for(NoteStore.Entry candidate:NoteStore.list(context))if(candidate.id.equals(id)){entry=candidate;break;}
        if(entry==null)throw new IOException("LEGACY_NOTE_NOT_FOUND");
        File bodyFile=NoteStore.noteFileForBackup(context,id);LegacyFileCapture.Result bodySnapshot=LegacyFileCapture.read(context,bodyFile,StreamingGroupStore.BODY_MAX);byte[] body=bodySnapshot.bytes;
        JSONObject parsed=NotePrecisionJsonParser.parseObject(strictUtf8(body));NativeManualUpdatePlan.validateActualNote(parsed,id);
        int schema=parsed.optInt("schemaVersion",0);if(schema!=8)throw new IOException("LEGACY_SCHEMA_REQUIRES_EXPLICIT_MIGRATION:"+schema);
        int pages=NoteStore.pdfPageCountOrZero(parsed);long updated=parsed.optLong("updatedAt",entry.updatedAt);
        StringBuilder fp=new StringBuilder();append(fp,"body",bodySnapshot.sha256,bodySnapshot.size);
        File pdfFile=NoteStore.pdfFile(context,id);boolean pdfPresent=exists(pdfFile);if((pages>0)!=pdfPresent)throw new IOException("LEGACY_PDF_STATE_MISMATCH");
        LegacyFileCapture.Result pdf=pdfPresent?LegacyFileCapture.inspect(context,pdfFile,StreamingGroupStore.PDF_MAX):null;if(pdf!=null)append(fp,"pdf",pdf.sha256,pdf.size);
        File coverFile=CoverStore.coverFile(new File(context.getFilesDir(),NoteStore.notesDirectoryName()),id);boolean coverPresent=exists(coverFile);LegacyFileCapture.Result cover=coverPresent?LegacyFileCapture.inspect(context,coverFile,StreamingGroupStore.COVER_MAX):null;if(cover!=null)append(fp,"cover",cover.sha256,cover.size);
        Map<String,String> mapping=new LinkedHashMap<>();mapping.put(id,id);List<InputMaterial> videos=new ArrayList<>(),vault=new ArrayList<>();VideoAttachmentStore videoStore=new VideoAttachmentStore(context);
        for(VideoAttachmentStore.Attachment a:videoStore.listForNote(id)){
            File file=videoStore.openVerified(a);LegacyFileCapture.Result bytes=LegacyFileCapture.inspect(context,file,StreamingGroupStore.VIDEO_MAX);
            StorageAdapter.Association assoc=association(a.id,lineage,a.sourceState);String raw=a.json().toString();
            NativeManualUpdatePlan.Material material=new NativeManualUpdatePlan.Material(NativeManualUpdatePlan.Role.VIDEO,a.id,a.id,a.mediaType,"android",raw,assoc,new NativeManualUpdatePlan.Facts(bytes.size,bytes.sha256));
            uniqueMapping(mapping,a.id);videos.add(new InputMaterial(material,bytes.content()));append(fp,"video:"+a.id,bytes.sha256,bytes.size);append(fp,"video-meta:"+a.id,sha(raw.getBytes(StandardCharsets.UTF_8)),raw.getBytes(StandardCharsets.UTF_8).length);
        }
        VaultStore vaultStore=new VaultStore(context);for(VaultStore.VaultNote note:vaultStore.listForBackupStrict()){if(!id.equals(note.noteId))continue;
            File file=vaultStore.fileForBackup(note.fileName);LegacyFileCapture.Result bytes=LegacyFileCapture.read(context,file,StreamingGroupStore.VAULT_MAX);byte[] rawBytes=bytes.bytes;String raw=strictUtf8(rawBytes);
            String destinationId=StorageAdapter.androidVaultMaterialId(note.fileName);
            StorageAdapter.Association assoc=new StorageAdapter.Association(note.fileName,lineage,"linked_note",lineage);
            NativeManualUpdatePlan.Material material=new NativeManualUpdatePlan.Material(NativeManualUpdatePlan.Role.VAULT,note.fileName,destinationId,"text/markdown","android",raw,assoc,new NativeManualUpdatePlan.Facts(bytes.size,bytes.sha256));
            uniqueMapping(mapping,note.fileName,destinationId);vault.add(new InputMaterial(material,bytes.content()));append(fp,"vault:"+note.fileName,bytes.sha256,bytes.size);
        }
        NativeManualUpdatePlan plan=NativeManualUpdatePlan.createLegacyAdoption(id,lineage,body,pdfPresent?NativeManualUpdatePlan.Presence.PRESENT:NativeManualUpdatePlan.Presence.ABSENT,pdf==null?null:new NativeManualUpdatePlan.Facts(pdf.size,pdf.sha256),coverPresent?NativeManualUpdatePlan.Presence.PRESENT:NativeManualUpdatePlan.Presence.ABSENT,cover==null?null:new NativeManualUpdatePlan.Facts(cover.size,cover.sha256),materials(videos),materials(vault),mapping);
        // The native Android source authority is re-derived from NoteStore's actual JSON model by the plan factory.
        String fingerprint=sha(fp.toString().getBytes(StandardCharsets.UTF_8));CapturedLegacyNote summary=new CapturedLegacyNote(id,fingerprint,updated,pages,pdfPresent,coverPresent,videos.size(),vault.size());
        return new Capture(id,lineage,fingerprint,body,updated,pages,pdf==null?null:new NativeManualUpdatePlan.Facts(pdf.size,pdf.sha256),pdf==null?null:pdf.content(),cover==null?null:new NativeManualUpdatePlan.Facts(cover.size,cover.sha256),cover==null?null:cover.content(),Collections.unmodifiableList(videos),Collections.unmodifiableList(vault),summary,plan);
    }
    private static List<NativeManualUpdatePlan.Material> materials(List<InputMaterial> list){List<NativeManualUpdatePlan.Material> out=new ArrayList<>();for(InputMaterial m:list)out.add(m.plan);return out;}
    private static StorageAdapter.Association association(String id,String lineage,String state){if(!"linked_note".equals(state)&&!"source_deleted".equals(state)&&!"source_not_selected".equals(state)&&!"independent".equals(state))throw new IllegalArgumentException("VIDEO_ASSOCIATION_STATE_UNSUPPORTED");return "independent".equals(state)?new StorageAdapter.Association(id,null,state,null):new StorageAdapter.Association(id,lineage,state,lineage);}
    private static void uniqueMapping(Map<String,String> map,String id){uniqueMapping(map,id,id);}
    private static void uniqueMapping(Map<String,String> map,String source,String destination){if(map.containsKey(source)||map.containsValue(destination))throw new IllegalArgumentException("LEGACY_MATERIAL_ID_COLLISION");map.put(source,destination);}
    private static boolean exists(File file)throws IOException {return java.nio.file.Files.exists(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS);}
    private static void append(StringBuilder b,String key,String sha,long size){b.append(key).append('\0').append(size).append('\0').append(sha).append('\n');}
    private static String strictUtf8(byte[] b)throws IOException{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b)).toString();}
    private static String sha(byte[] bytes)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] h=d.digest(bytes);StringBuilder s=new StringBuilder();for(byte b:h)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
}
