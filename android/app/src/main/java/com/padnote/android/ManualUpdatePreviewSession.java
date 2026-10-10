package com.padnote.android;

import android.annotation.TargetApi;

import com.padnote.android.streaming.StreamingGroupStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only, process-local preparation for a future explicitly confirmed archive update. */
@TargetApi(27)
public final class ManualUpdatePreviewSession implements AutoCloseable {
    public static final class TargetToken {
        private final String localId,lineage,revision,digest;
        private TargetToken(StreamingGroupStore.Snapshot snapshot) {
            localId=snapshot.localId;lineage=snapshot.lineage;revision=snapshot.revision;digest=snapshot.digest;
        }
        private TargetToken(String localId,String lineage,String revision,String digest) {
            this.localId=localId;this.lineage=lineage;this.revision=revision;this.digest=digest;
        }
        static TargetToken from(StreamingGroupStore.Snapshot snapshot) throws IOException {
            NoteGroupFacade.requireActiveManualUpdateTarget(snapshot);return new TargetToken(snapshot);
        }
        /** Rehydrates a saved expectation; it grants no authority and must be compared to a fresh marker. */
        public static TargetToken restore(String localId,String lineage,String revision,String digest)throws IOException {
            try {
                new NativeManualUpdatePlan.SelectedTarget(localId,lineage,revision,digest);
                return new TargetToken(localId,lineage,revision,digest);
            } catch(RuntimeException invalid) { throw new IOException("UPDATE_TARGET_TOKEN_INVALID",invalid); }
        }
        static TargetToken capture(StreamingGroupStore.Store store,String localId)throws IOException {
            if(store==null||localId==null)throw new IOException("UPDATE_TARGET_REQUIRED");
            return from(store.readByLocalId(localId));
        }
        boolean matches(StreamingGroupStore.Snapshot snapshot) {
            return snapshot!=null&&localId.equals(snapshot.localId)&&lineage.equals(snapshot.lineage)
                    &&revision.equals(snapshot.revision)&&digest.equals(snapshot.digest);
        }
        public String localId(){return localId;}
        public String lineage(){return lineage;}
        public String revision(){return revision;}
        public String digest(){return digest;}
    }

    public static final class ResourceFact {
        public final String role,sourceId,destinationId,mediaType,sha256,metadataSha256,associationState;
        public final long size;
        public final long metadataSize;
        public final String sourceLineage,ownerLineage;
        public final String memberPath;
        final File stagedFile;
        ResourceFact(String role,String sourceId,String destinationId,String mediaType,long size,
                String sha256,String manifestResourceId,File stagedFile) {
            this(role,sourceId,destinationId,mediaType,size,sha256,manifestResourceId,stagedFile,
                    -1,null,null,null,null);
        }
        ResourceFact(String role,String sourceId,String destinationId,String mediaType,long size,
                String sha256,String manifestResourceId,File stagedFile,long metadataSize,
                String metadataSha256,String associationState,String sourceLineage,String ownerLineage) {
            this.role=role;this.sourceId=sourceId;this.destinationId=destinationId;this.mediaType=mediaType;
            this.size=size;this.sha256=sha256;this.memberPath=manifestResourceId;this.stagedFile=stagedFile;
            this.metadataSize=metadataSize;this.metadataSha256=metadataSha256;this.associationState=associationState;
            this.sourceLineage=sourceLineage;this.ownerLineage=ownerLineage;
        }
        public boolean isPresent(){return size>=0;}
    }

    /** Complete immutable input binding saved with an open preview; it carries no write authority. */
    public static final class PreviewBinding {
        private final String archiveSha256,sourceNoteItemId,sourceLineage,sourceRevision,sourceBodySha256;
        private final String destinationBodySha256,previewDigest,bindingDigest;
        private final TargetToken target;
        private final Map<String,String> mapping;

        private PreviewBinding(String archiveSha256,String sourceNoteItemId,String sourceLineage,
                String sourceRevision,String sourceBodySha256,String destinationBodySha256,
                String previewDigest,TargetToken target,Map<String,String> mapping,String expectedDigest)
                throws IOException {
            if(!isSha(archiveSha256)||!isSha(sourceBodySha256)||!isSha(destinationBodySha256)
                    ||!isSha(previewDigest)||target==null||sourceNoteItemId==null||sourceLineage==null
                    ||sourceRevision==null||mapping==null)throw new IOException("UPDATE_PREVIEW_BINDING_INVALID");
            this.archiveSha256=archiveSha256;this.sourceNoteItemId=sourceNoteItemId;
            this.sourceLineage=sourceLineage;this.sourceRevision=sourceRevision;
            this.sourceBodySha256=sourceBodySha256;this.destinationBodySha256=destinationBodySha256;
            this.previewDigest=previewDigest;this.target=target;
            LinkedHashMap<String,String> ordered=new LinkedHashMap<>();
            List<String> keys=new ArrayList<>(mapping.keySet());Collections.sort(keys);
            for(String key:keys){String value=mapping.get(key);if(key==null||value==null||key.isEmpty()||value.isEmpty())
                throw new IOException("UPDATE_PREVIEW_BINDING_INVALID");ordered.put(key,value);}
            this.mapping=Collections.unmodifiableMap(ordered);
            this.bindingDigest=computeBindingDigest(archiveSha256,sourceNoteItemId,sourceLineage,sourceRevision,
                    sourceBodySha256,destinationBodySha256,previewDigest,target,this.mapping);
            if(expectedDigest!=null&&!expectedDigest.equals(this.bindingDigest))
                throw new IOException("UPDATE_PREVIEW_BINDING_CHANGED");
        }

        static PreviewBinding capture(ManualUpdatePreviewSession session)throws IOException {
            return new PreviewBinding(session.archiveSha256,session.sourceNoteItemId,session.sourceLineage,
                    session.sourceRevision,session.sourceBodySha256,session.destinationBodySha256,
                    session.previewDigest,session.target,session.mapping,null);
        }

        /** Restores the full saved binding and detects partial/stale field substitution. */
        public static PreviewBinding restore(String archiveSha256,String sourceNoteItemId,String sourceLineage,
                String sourceRevision,String sourceBodySha256,String destinationBodySha256,String previewDigest,
                TargetToken target,Map<String,String> mapping,String bindingDigest)throws IOException {
            return new PreviewBinding(archiveSha256,sourceNoteItemId,sourceLineage,sourceRevision,
                    sourceBodySha256,destinationBodySha256,previewDigest,target,mapping,bindingDigest);
        }

        /** Stable, complete persistence record for process recreation; no authority is serialized. */
        public JSONObject toJson()throws IOException {
            try {
                JSONObject targetJson=new JSONObject().put("local_id",target.localId()).put("lineage",target.lineage())
                        .put("revision",target.revision()).put("digest",target.digest());
                JSONArray pairs=new JSONArray();
                for(Map.Entry<String,String> entry:mapping.entrySet())pairs.put(new JSONArray().put(entry.getKey()).put(entry.getValue()));
                return new JSONObject().put("schema","padnote-import-preview-binding-v1")
                        .put("archive_sha256",archiveSha256).put("source_item_id",sourceNoteItemId)
                        .put("source_lineage",sourceLineage).put("source_revision",sourceRevision)
                        .put("source_body_sha256",sourceBodySha256).put("destination_body_sha256",destinationBodySha256)
                        .put("preview_digest",previewDigest).put("target",targetJson).put("mapping",pairs)
                        .put("binding_digest",bindingDigest);
            } catch(org.json.JSONException malformed){throw new IOException("UPDATE_PREVIEW_BINDING_SERIALIZE",malformed);}
        }

        public static PreviewBinding restore(JSONObject record)throws IOException {
            try {
                if(record==null||record.length()!=11
                        ||!"padnote-import-preview-binding-v1".equals(record.getString("schema")))
                    throw new IOException("UPDATE_PREVIEW_BINDING_INVALID");
                JSONObject targetJson=record.getJSONObject("target");
                if(targetJson.length()!=4)throw new IOException("UPDATE_PREVIEW_BINDING_INVALID");
                TargetToken target=TargetToken.restore(targetJson.getString("local_id"),targetJson.getString("lineage"),
                        targetJson.getString("revision"),targetJson.getString("digest"));
                JSONArray pairs=record.getJSONArray("mapping");Map<String,String> mapping=new LinkedHashMap<>();
                for(int i=0;i<pairs.length();i++) {
                    JSONArray pair=pairs.getJSONArray(i);if(pair.length()!=2)throw new IOException("UPDATE_PREVIEW_BINDING_INVALID");
                    String key=pair.getString(0),value=pair.getString(1);
                    if(mapping.put(key,value)!=null)throw new IOException("UPDATE_PREVIEW_BINDING_INVALID");
                }
                return restore(record.getString("archive_sha256"),record.getString("source_item_id"),
                        record.getString("source_lineage"),record.getString("source_revision"),
                        record.getString("source_body_sha256"),record.getString("destination_body_sha256"),
                        record.getString("preview_digest"),target,mapping,record.getString("binding_digest"));
            } catch(org.json.JSONException malformed){throw new IOException("UPDATE_PREVIEW_BINDING_INVALID",malformed);}
        }

        boolean matches(ManualUpdatePreviewSession session) {
            if(session==null)return false;
            try{return bindingDigest.equals(capture(session).bindingDigest)
                    &&archiveSha256.equals(session.archiveSha256)
                    &&sourceNoteItemId.equals(session.sourceNoteItemId)
                    &&sourceLineage.equals(session.sourceLineage)&&sourceRevision.equals(session.sourceRevision)
                    &&sourceBodySha256.equals(session.sourceBodySha256)
                    &&destinationBodySha256.equals(session.destinationBodySha256)
                    &&previewDigest.equals(session.previewDigest)&&target.matches(session.targetSnapshot)
                    &&mapping.equals(session.mapping);}
            catch(IOException invalid){return false;}
        }
        public String archiveSha256(){return archiveSha256;}
        public String sourceNoteItemId(){return sourceNoteItemId;}
        public String sourceLineage(){return sourceLineage;}
        public String sourceRevision(){return sourceRevision;}
        public String sourceBodySha256(){return sourceBodySha256;}
        public String destinationBodySha256(){return destinationBodySha256;}
        public String previewDigest(){return previewDigest;}
        public String bindingDigest(){return bindingDigest;}
        public TargetToken targetToken(){return target;}
        public Map<String,String> explicitMapping(){return mapping;}
    }

    private static boolean isSha(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static String computeBindingDigest(String archive,String item,String lineage,String revision,
            String sourceBody,String destinationBody,String preview,TargetToken target,Map<String,String> mapping)
            throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            updateField(digest,"padnote-import-preview-binding-v1");updateField(digest,archive);
            updateField(digest,item);updateField(digest,lineage);updateField(digest,revision);
            updateField(digest,sourceBody);updateField(digest,destinationBody);updateField(digest,preview);
            updateField(digest,target.localId());updateField(digest,target.lineage());
            updateField(digest,target.revision());updateField(digest,target.digest());
            updateField(digest,Integer.toString(mapping.size()));
            for(Map.Entry<String,String> entry:mapping.entrySet()){
                updateField(digest,entry.getKey());updateField(digest,entry.getValue());
            }
            return hex(digest.digest());
        } catch(NoSuchAlgorithmException unavailable){throw new IOException("UPDATE_PREVIEW_BINDING_DIGEST_UNAVAILABLE",unavailable);}
    }
    private static void updateField(MessageDigest digest,String value) {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte value:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}

    private final LibraryBackupArchive.StagedArchive staged;
    private final TargetToken target;
    private final StreamingGroupStore.Snapshot targetSnapshot;
    private final String archiveSha256,sourceNoteItemId,sourceLineage,sourceRevision,sourceBodySha256;
    private final String destinationBodySha256,previewDigest;
    private final Map<String,String> mapping;
    private final byte[] destinationBody;
    private final List<ResourceFact> incoming,targetMembers,removedTargetMembers;
    private final List<File> scratchFiles;
    private volatile boolean closed;

    ManualUpdatePreviewSession(LibraryBackupArchive.StagedArchive staged,TargetToken target,
            StreamingGroupStore.Snapshot targetSnapshot,String sourceNoteItemId,String sourceLineage,
            String sourceRevision,Map<String,String> mapping,NativeManualUpdatePlan plan,
            List<File> scratchFiles,List<ResourceFact> incoming) throws Exception {
        this.staged=staged;this.target=target;this.targetSnapshot=targetSnapshot;
        this.archiveSha256=staged.archiveSha256();this.sourceNoteItemId=sourceNoteItemId;
        this.sourceLineage=sourceLineage;this.sourceRevision=sourceRevision;
        this.mapping=Collections.unmodifiableMap(new LinkedHashMap<>(mapping));
        this.destinationBody=plan.previewBodyUtf8();this.sourceBodySha256=plan.sourceBodySha256();
        this.destinationBodySha256=plan.destinationBodySha256();this.previewDigest=plan.planDigest();
        this.scratchFiles=Collections.unmodifiableList(new ArrayList<>(scratchFiles));
        this.incoming=Collections.unmodifiableList(new ArrayList<>(incoming));
        List<ResourceFact> existing=new ArrayList<>();
        for(StreamingGroupStore.MemberSummary m:targetSnapshot.memberSummaries())
            existing.add(new ResourceFact(m.role,m.materialId,m.materialId,m.role,m.size,m.sha256,m.member,null));
        this.targetMembers=Collections.unmodifiableList(existing);
        Set<String> incomingKeys=new HashSet<>();
        for(ResourceFact r:incoming)if(!"body".equals(r.role))incomingKeys.add(key(r.role,r.destinationId));
        List<ResourceFact> removed=new ArrayList<>();
        for(ResourceFact r:existing) {
            if("pdf".equals(r.role)||"cover".equals(r.role)) {
                if(!incoming.stream().anyMatch(x->r.role.equals(x.role)&&x.isPresent()))removed.add(r);
            } else if(("video".equals(r.role)||"vault".equals(r.role))
                    && !incomingKeys.contains(key(r.role,r.destinationId))) {
                removed.add(r);
            }
            // body, source-provenance and state members are internal group bookkeeping,
            // not target-only user resources to present as removals.
        }
        this.removedTargetMembers=Collections.unmodifiableList(removed);
    }

    private static String key(String role,String id){return role+"\0"+(id==null?"":id);}
    private void requireOpen()throws IOException {
        if(closed)throw new IOException("UPDATE_PREVIEW_SESSION_CLOSED");
        // resourceFile checks that the borrowed stage is still owned and open.
        LibraryBackupManifest.Note n=null;
        for(LibraryBackupManifest.Note candidate:staged.manifest().notes)
            if(candidate.itemId.equals(sourceNoteItemId)){n=candidate;break;}
        if(n==null)throw new IOException("UPDATE_PREVIEW_SOURCE_MISSING");
        staged.resourceFile(n.noteResourceId);
    }
    public String archiveSha256(){return archiveSha256;}
    public String sourceNoteItemId(){return sourceNoteItemId;}
    public String sourceLineage(){return sourceLineage;}
    public String sourceRevision(){return sourceRevision;}
    public String sourceBodySha256(){return sourceBodySha256;}
    public String destinationBodySha256(){return destinationBodySha256;}
    public String previewDigest(){return previewDigest;}
    public PreviewBinding binding()throws IOException {requireOpen();return PreviewBinding.capture(this);}
    public TargetToken targetToken(){return target;}
    public Map<String,String> explicitMapping(){return mapping;}
    /** Exact destination note JSON (including its full text and handwriting arrays), defensively copied. */
    public byte[] destinationBodyUtf8()throws IOException {requireOpen();return destinationBody.clone();}
    public List<ResourceFact> incomingResources()throws IOException {requireOpen();return incoming;}
    public List<ResourceFact> targetResources()throws IOException {requireOpen();return targetMembers;}
    /** Members absent from the incoming replacement that the future confirmation must show as removals. */
    public List<ResourceFact> targetOnlyResources()throws IOException {requireOpen();return removedTargetMembers;}

    /** Rechecks the selected local group but never replaces the captured token with its new version. */
    public void requireSelectedTargetStillCurrent(NoteGroupFacade facade)throws IOException {
        requireOpen();if(facade==null)throw new IOException("UPDATE_TARGET_REQUIRED");
        StreamingGroupStore.Snapshot current=facade.openGroup(target.localId());
        if(!target.matches(current))throw new IOException("BASE_CAS_CONFLICT");
    }

    /** Copies one incoming resource only after bounded size and SHA-256 verification. */
    public void copyIncomingResource(ResourceFact fact,OutputStream destination,long maxBytes)throws IOException {
        requireOpen();if(destination==null||fact==null||!incoming.contains(fact)||fact.stagedFile==null||maxBytes<0||fact.size>maxBytes)
            throw new IOException("UPDATE_PREVIEW_RESOURCE_LIMIT");
        File verified=staged.createVerifiedPreviewCopy(fact.stagedFile,fact.size,fact.sha256);
        IOException primary=null;
        try {
            staged.copyVerifiedPreviewFile(verified,fact.size,fact.sha256,destination);
        } catch(IOException failure) {
            primary=failure;throw failure;
        } finally {
            try{staged.discardPreviewCopy(verified);}
            catch(IOException cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}
        }
    }
    /** Copies an immutable selected-target member through the snapshot's verified reader. */
    public void copyTargetMember(String member,OutputStream destination,long maxBytes)throws IOException {
        requireOpen();if(destination==null)throw new IOException("UPDATE_PREVIEW_OUTPUT_REQUIRED");
        if(maxBytes<0)throw new IOException("UPDATE_PREVIEW_RESOURCE_LIMIT");
        Long size=targetSnapshot.memberSizes().get(member);
        if(size==null||size>maxBytes)throw new IOException("UPDATE_PREVIEW_RESOURCE_LIMIT");
        try(InputStream in=targetSnapshot.openContent(member)) {
            byte[] b=new byte[32*1024];long count=0;
            for(int n;(n=in.read(b))!=-1;){if(n==0)continue;count=Math.addExact(count,n);if(count>size)throw new IOException("UPDATE_PREVIEW_MEMBER_GREW");destination.write(b,0,n);}
            if(count!=size)throw new IOException("UPDATE_PREVIEW_MEMBER_TRUNCATED");
        }
    }
    public void copyTargetResource(ResourceFact fact,OutputStream destination,long maxBytes)throws IOException {
        requireOpen();if(fact==null||!targetMembers.contains(fact)||fact.memberPath==null)
            throw new IOException("UPDATE_PREVIEW_TARGET_MEMBER_REQUIRED");
        copyTargetMember(fact.memberPath,destination,maxBytes);
    }

    @Override public synchronized void close() {
        if(closed)return;closed=true;
        for(File scratch:scratchFiles)try{staged.discardPreviewCopy(scratch);}catch(IOException ignored){/* stage owner performs final bounded cleanup */}
        // The staged archive is borrowed; its external owner decides when to close or reopen it.
    }
}
