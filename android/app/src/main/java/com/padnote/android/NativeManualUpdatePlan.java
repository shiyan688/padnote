package com.padnote.android;

import org.json.JSONObject;
import org.json.JSONArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.Base64;
import java.io.FileDescriptor;
import com.padnote.android.streaming.StreamingGroupStore;
import padnote.material.StorageAdapter;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Isolated manual-update v2 verifier bridge. This is not wired to app callers.
 * The immutable plan is a process-local authorization capability; the durable
 * group verifier is still mandatory for every later read/recovery.
 */
@android.annotation.TargetApi(27)
public final class NativeManualUpdatePlan {
    interface LegacySourceGuard { AutoCloseable lockAndVerify() throws Exception; }
    public enum SourceKind { NATIVE_IOS, NATIVE_ANDROID, TRANSPORTED }
    public enum Presence { ABSENT, PRESENT }
    public enum Role { PDF, COVER, VIDEO, VAULT }

    public record SelectedTarget(String localId, String lineage, String revision, String digest) {
        public SelectedTarget {
            // Existing NoteStore ids are opaque note-<hex> values, not UUIDs.
            requireId(localId, "target_local_id"); requireUuid(lineage, "target_lineage");
            requireUuid(revision, "target_revision"); requireHash(digest, "target_digest");
        }
    }
    public record Provenance(SourceKind kind, String sourceLineage, String sourceNoteId,
                             String sourceRevision, TypedNoteTimeProjection.Sidecar sidecar) {
        public Provenance {
            Objects.requireNonNull(kind); requireUuid(sourceLineage, "source_lineage");
            requireId(sourceNoteId, "source_note_id"); requireId(sourceRevision, "source_revision");
        }
    }
    public record Facts(long size, String sha256) {
        public Facts { if (size < 0) throw new IllegalArgumentException("content_size"); requireHash(sha256, "content_sha256"); }
    }
    public record Material(Role role, String sourceId, String destinationId, String mediaType,
                           String platform, String rawMetadata, StorageAdapter.Association association,
                           Facts content) {
        public Material {
            Objects.requireNonNull(role); requireId(sourceId, "source_material_id");
            requireId(destinationId, "destination_material_id"); requireUtf8(mediaType, 256, "media_type");
            if (role == Role.VIDEO || role == Role.VAULT) {
                Objects.requireNonNull(platform); Objects.requireNonNull(rawMetadata);
                Objects.requireNonNull(association); Objects.requireNonNull(content);
                if (!association.materialId().equals(sourceId)) throw new IllegalArgumentException("material_identity_mismatch");
            }
        }
    }
    private final SelectedTarget target;
    private final boolean legacyAdoption;
    private final LegacySourceGuard legacySourceGuard;
    private final TypedNoteTimeProjection.Projection timeProjection;
    private final Provenance provenance;
    private final byte[] bodyJson, bodyProjection, provenanceBytes;
    private final String bodySha256, sourceBodySha256;
    private final int bodyPdfPageCount;
    private final Presence pdfPresence, coverPresence;
    private final Facts pdf, cover;
    private final List<Material> videos, vault;
    private final Map<String,String> idMapping;
    private final List<StorageAdapter.Projection> materialProjections;
    private final String planDigest;

    private NativeManualUpdatePlan(SelectedTarget target, Provenance provenance, byte[] bodyJson,
                               TypedNoteTimeProjection.Projection bodyProjection, String sourceBodySha256, int pdfPages,
                               Presence pdfPresence, Facts pdf, Presence coverPresence, Facts cover,
                               List<Material> videos, List<Material> vault, Map<String,String> mapping,
                               List<StorageAdapter.Projection> projections,
                               byte[] provenanceBytes) {
        this(target,provenance,bodyJson,bodyProjection,sourceBodySha256,pdfPages,pdfPresence,pdf,coverPresence,cover,videos,vault,mapping,projections,provenanceBytes,false);
    }
    private NativeManualUpdatePlan(SelectedTarget target, Provenance provenance, byte[] bodyJson,
                               TypedNoteTimeProjection.Projection bodyProjection, String sourceBodySha256, int pdfPages,
                               Presence pdfPresence, Facts pdf, Presence coverPresence, Facts cover,
                               List<Material> videos, List<Material> vault, Map<String,String> mapping,
                               List<StorageAdapter.Projection> projections, byte[] provenanceBytes, boolean legacyAdoption) {
        this(target,provenance,bodyJson,bodyProjection,sourceBodySha256,pdfPages,pdfPresence,pdf,coverPresence,cover,videos,vault,mapping,projections,provenanceBytes,legacyAdoption,null);
    }
    private NativeManualUpdatePlan(SelectedTarget target, Provenance provenance, byte[] bodyJson,
                               TypedNoteTimeProjection.Projection bodyProjection, String sourceBodySha256, int pdfPages,
                               Presence pdfPresence, Facts pdf, Presence coverPresence, Facts cover,
                               List<Material> videos, List<Material> vault, Map<String,String> mapping,
                               List<StorageAdapter.Projection> projections, byte[] provenanceBytes, boolean legacyAdoption,
                               LegacySourceGuard legacySourceGuard) {
        this.target=target; this.provenance=provenance; this.legacyAdoption=legacyAdoption; this.legacySourceGuard=legacySourceGuard; this.timeProjection=bodyProjection; this.bodyJson=bodyJson.clone();
        this.bodyProjection=bodyProjection.bodyBytes.clone(); this.bodySha256=bodyProjection.rawSha256; this.sourceBodySha256=sourceBodySha256;
        this.bodyPdfPageCount=pdfPages; this.pdfPresence=pdfPresence; this.pdf=pdf;
        this.coverPresence=coverPresence; this.cover=cover; this.videos=Collections.unmodifiableList(new ArrayList<>(videos));
        this.vault=Collections.unmodifiableList(new ArrayList<>(vault)); this.idMapping=Collections.unmodifiableMap(new TreeMap<>(mapping));
        this.materialProjections=Collections.unmodifiableList(new ArrayList<>(projections));
        this.provenanceBytes=provenanceBytes.clone();
        this.planDigest=shaHex(canonicalPlanBytes());
    }

    private NativeManualUpdatePlan(NativeManualUpdatePlan base, boolean legacyAdoption) {
        this(base.target,base.provenance,base.bodyJson,base.timeProjection,
                base.sourceBodySha256,base.bodyPdfPageCount,base.pdfPresence,base.pdf,base.coverPresence,base.cover,
                base.videos,base.vault,base.idMapping,base.materialProjections,base.provenanceBytes,legacyAdoption,base.legacySourceGuard);
    }

    public AutoCloseable acquireLegacySourcePublicationGuard() throws Exception {
        if(!legacyAdoption||legacySourceGuard==null)throw new IOException("LEGACY_SOURCE_GUARD_REQUIRED");
        return legacySourceGuard.lockAndVerify();
    }

    NativeManualUpdatePlan withLegacySourceGuard(LegacySourceGuard guard) {
        if(!legacyAdoption||legacySourceGuard!=null||guard==null)throw new IllegalStateException("LEGACY_SOURCE_GUARD_BINDING_INVALID");
        return new NativeManualUpdatePlan(target,provenance,bodyJson,timeProjection,sourceBodySha256,bodyPdfPageCount,
                pdfPresence,pdf,coverPresence,cover,videos,vault,idMapping,materialProjections,provenanceBytes,true,guard);
    }

    /** Manual target selection is explicit. Source lineage need not equal target lineage. */
    public static NativeManualUpdatePlan createManualUpdate(
            SelectedTarget target, Provenance source, byte[] exactBodyUtf8,
            Presence pdfPresence, Facts pdf, Presence coverPresence, Facts cover,
            List<Material> videos, List<Material> vault, Map<String,String> sourceToDestination) throws Exception {
        Objects.requireNonNull(target); Objects.requireNonNull(source); Objects.requireNonNull(exactBodyUtf8);
        Objects.requireNonNull(pdfPresence); Objects.requireNonNull(coverPresence);
        if ((pdfPresence == Presence.PRESENT) != (pdf != null)) throw new IllegalArgumentException("pdf_presence_mismatch");
        if ((coverPresence == Presence.PRESENT) != (cover != null)) throw new IllegalArgumentException("cover_presence_mismatch");
        if (videos == null || vault == null) throw new IllegalArgumentException("collections_must_be_present_even_when_empty");
        if (videos.size() + vault.size() > 10_000) throw new IllegalArgumentException("material_count_limit");
        if (sourceToDestination == null) throw new IllegalArgumentException("explicit_id_mapping_required");

        String raw = decodeStrictUtf8(exactBodyUtf8);
        TypedNoteTimeProjection.Sidecar sidecar = source.sidecar();
        if (source.kind() == SourceKind.NATIVE_ANDROID) {
            if (sidecar != null) throw new IllegalArgumentException("native_android_sidecar_not_expected");
        } else if (sidecar == null) throw new IllegalArgumentException("typed_provenance_required");
        String profile = switch (source.kind()) {
            case NATIVE_IOS -> "native-ios";
            case NATIVE_ANDROID -> "native-android";
            case TRANSPORTED -> sidecar == null ? "" : sidecar.source;
        };
        TypedNoteTimeProjection.Projection body = source.kind() == SourceKind.NATIVE_ANDROID
                ? TypedNoteTimeProjection.nativeAndroid(raw)
                : TypedNoteTimeProjection.transported(raw, sidecar);
        if (!body.noteId.equals(source.sourceNoteId())) throw new IllegalArgumentException("source_note_mismatch");
        if (!body.source.equals(profile)) throw new IllegalArgumentException("source_authority_mismatch");
        JSONObject sourceDoc = NotePrecisionJsonParser.parseObject(raw);
        requireUnicodeScalars(sourceDoc);
        validateActualNote(sourceDoc, source.sourceNoteId());
        int pages = NoteStore.pdfPageCountOrZero(sourceDoc);
        if (pages < 0 || pages > 500) throw new IllegalArgumentException("pdf_page_count");
        if ((pdfPresence == Presence.ABSENT && pages != 0) || (pdfPresence == Presence.PRESENT && pages == 0)) throw new IllegalArgumentException("pdf_state_body_mismatch");
        validateMappedMaterials(source, target, videos, vault, sourceToDestination);
        byte[] destinationJson = replaceTopLevelStringField(exactBodyUtf8, "id", target.localId());
        validateActualNote(NotePrecisionJsonParser.parseObject(decodeStrictUtf8(destinationJson)), target.localId());
        TypedNoteTimeProjection.Sidecar targetSidecar = sidecarForProjectedTimes(body, destinationJson, target.localId());
        TypedNoteTimeProjection.Projection destinationProjection = TypedNoteTimeProjection.transported(
                decodeStrictUtf8(destinationJson), targetSidecar);
        List<StorageAdapter.Projection> projections = new ArrayList<>();
        for (Material m : concat(videos, vault)) projections.add(projectMaterial(m, target, source));
        byte[] provenanceBytes = encodeProvenance(source, body, sourceToDestination, shaHex(destinationJson),concat(videos,vault),target);
        return new NativeManualUpdatePlan(target, source, destinationJson, destinationProjection, body.rawSha256, pages, pdfPresence, pdf,
                coverPresence, cover, videos, vault, sourceToDestination, projections,
                provenanceBytes);
    }

    /**
     * Creates the process-local plan for capturing a complete existing legacy Android note.
     * This is not a package import: the selected local ID and lineage are both the source ID,
     * and commit is allowed only when no marker-backed group exists for that local note.
     */
    public static NativeManualUpdatePlan createLegacyAdoption(
            String localId, String localLineage, byte[] exactBodyUtf8,
            Presence pdfPresence, Facts pdf, Presence coverPresence, Facts cover,
            List<Material> videos, List<Material> vault, Map<String,String> sourceToDestination) throws Exception {
        requireId(localId,"legacy_local_id"); requireUuid(localLineage,"legacy_local_lineage");
        String raw=decodeStrictUtf8(exactBodyUtf8); JSONObject body=NotePrecisionJsonParser.parseObject(raw);
        if(!localId.equals(body.getString("id")))throw new IllegalArgumentException("legacy_body_identity_mismatch");
        String seedRevision=UUID.randomUUID().toString(); String seedDigest=shaHex(exactBodyUtf8);
        SelectedTarget seed=new SelectedTarget(localId,localLineage,seedRevision,seedDigest);
        Provenance provenance=new Provenance(SourceKind.NATIVE_ANDROID,localLineage,localId,UUID.randomUUID().toString(),null);
        return new NativeManualUpdatePlan(createManualUpdate(seed,provenance,exactBodyUtf8,pdfPresence,pdf,coverPresence,cover,videos,vault,sourceToDestination),true);
    }

    public String planDigest() { return planDigest; }
    public String destinationLocalId() { return target.localId(); }
    public String destinationLineage() { return target.lineage(); }
    /** Exact destination body for read-only preview rendering; callers receive an isolated copy. */
    public byte[] previewBodyUtf8() { return bodyJson.clone(); }
    public String sourceBodySha256() { return sourceBodySha256; }
    public String destinationBodySha256() { return bodySha256; }
    public Presence pdfPresence() { return pdfPresence; }
    public Facts pdfFacts() { return pdf; }
    public Presence coverPresence() { return coverPresence; }
    public Facts coverFacts() { return cover; }
    public List<Material> previewVideos() { return videos; }
    public List<Material> previewVault() { return vault; }
    /** Exact projected descriptor facts for display; returned bytes are caller-owned. */
    public byte[] previewMaterialDescriptor(String sourceId) {
        List<Material> all=concat(videos,vault);
        for(int i=0;i<all.size();i++)if(all.get(i).sourceId().equals(sourceId))
            return materialProjections.get(i).descriptor().clone();
        throw new IllegalArgumentException("material_not_in_plan");
    }
    public boolean isLegacyAdoption() { return legacyAdoption; }
    public byte[] sourceBodySha256Bytes() { return sourceBodySha256.getBytes(StandardCharsets.US_ASCII); }
    public byte[] durableProvenanceMember() { return provenanceBytes.clone(); }
    public String sourceLineage() { return provenance.sourceLineage(); }
    public String targetLineage() { return target.lineage(); }
    public String targetBaseRevision() { return target.revision(); }
    public String targetBaseDigest() { return target.digest(); }
    public Map<String,String> sourceToDestinationIds() { return idMapping; }

    /** Binds a video byte source to this immutable plan; callers cannot invent descriptor metadata. */
    public StreamingGroupStore.MemberInput videoMemberInput(String sourceMaterialId,StreamingGroupStore.ContentInput content) {
        return boundMaterialInput(sourceMaterialId,Role.VIDEO,content);
    }
    /** Binds a Vault byte source to this immutable plan; callers cannot invent descriptor metadata. */
    public StreamingGroupStore.MemberInput vaultMemberInput(String sourceMaterialId,StreamingGroupStore.ContentInput content) {
        return boundMaterialInput(sourceMaterialId,Role.VAULT,content);
    }
    /** Returns the exact bounded Vault bytes expected for the selected destination note. */
    public byte[] destinationVaultContent(String sourceMaterialId) throws Exception {
        for (Material material : vault) {
            if (material.sourceId().equals(sourceMaterialId)) {
                if (material.platform().equals("ios")) {
                    return strictUtf8Bytes(new JSONObject(material.rawMetadata()).getString("markdown"));
                }
                return "linked_note".equals(material.association().sourceState())
                        ? androidVaultRebind(material.rawMetadata(), target.localId())
                        : strictUtf8Bytes(material.rawMetadata());
            }
        }
        throw new IllegalArgumentException("material_not_in_plan");
    }
    private StreamingGroupStore.MemberInput boundMaterialInput(String sourceId,Role role,StreamingGroupStore.ContentInput content) {
        if(content==null)throw new IllegalArgumentException("material_content_required");
        for(Material material:concat(videos,vault))if(material.role()==role&&material.sourceId().equals(sourceId)){
            Facts expected=destinationContentFacts(material,target.localId());
            if(content.size!=expected.size()||!expected.sha256().equalsIgnoreCase(content.sha256))throw new IllegalArgumentException("material_content_binding_mismatch");
            // MemberInput.role is the streaming store's lowercase wire role
            // ("video"/"vault"). Enum names are retained only inside the
            // typed provenance/material codec and are not interchangeable here.
            return new StreamingGroupStore.MemberInput(storeRole(role),material.destinationId(),material.mediaType(),descriptorFor(material),content);
        }
        throw new IllegalArgumentException("material_not_in_plan");
    }

    /** A non-subclassable concrete capability used by MANUAL_UPDATE_V2 on every store gate. */
    public static final class ConcreteCompleteGroupVerifier implements StreamingGroupStore.CompleteGroupVerifier {
        private static final ConcreteCompleteGroupVerifier INSTANCE = new ConcreteCompleteGroupVerifier();
        private ConcreteCompleteGroupVerifier() {}
        @Override public void verifyCompleteGroup(StreamingGroupStore.VerifiedGroup group, byte[] encoded) throws IOException {
            verifyDurableCompleteGroup(group, encoded);
        }
    }
    public static StreamingGroupStore.CompleteGroupVerifier durableVerifier() { return ConcreteCompleteGroupVerifier.INSTANCE; }

    /** Reusable cold-read/recovery verifier. The store invokes it before returning any v2 group. */
    private static void verifyDurableCompleteGroup(StreamingGroupStore.VerifiedGroup group, byte[] encoded) throws IOException {
          try {
            DurableSource source=decodeDurableSource(encoded);
            if(!group.identity.originLineage().equals(source.lineage)||!group.identity.originLocalId().equals(source.noteId))
                throw new IOException("durable_source_identity_mismatch");
            Map<String,Long> sizes=group.memberSizes();
            TreeSet<String> expected=new TreeSet<>(Arrays.asList("body.bin","pdf.state","cover.state","video.state","vault.state","source-provenance.bin"));
            for(String optional:Arrays.asList("pdf","cover")){
                String state=new String(group.readSmall(optional+".state",16),StandardCharsets.US_ASCII);
                if(state.equals("present\n"))expected.add(optional+".bin");else if(!state.equals("absent\n"))throw new IOException("durable_optional_state_invalid");
            }
            for(String role:Arrays.asList("video","vault")){
                if(!Arrays.equals(group.readSmall(role+".state",16),"present\n".getBytes(StandardCharsets.US_ASCII)))throw new IOException("durable_collection_state_invalid");
                Set<String> ids=new HashSet<>();for(String name:sizes.keySet())if(name.startsWith(role+"/")){String[] parts=name.substring(role.length()+1).split("/",-1);if(parts.length!=2||!parts[0].matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")||!setOf("content.bin","media-type.txt","metadata.bin").contains(parts[1]))throw new IOException("durable_material_member_invalid");ids.add(parts[0]);}
                for(String id:ids)for(String suffix:Arrays.asList("content.bin","media-type.txt","metadata.bin"))expected.add(role+"/"+id+"/"+suffix);
            }
            if(!sizes.keySet().equals(expected))throw new IOException("durable_complete_member_set_mismatch");
            byte[] body=group.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX);
            if(!shaHex(body).equals(source.destinationBodySha))throw new IOException("durable_destination_body_hash_mismatch");
            String json=decodeStrictUtf8(body);JSONObject actualBody=NotePrecisionJsonParser.parseObject(json);validateActualNote(actualBody,group.identity.destinationLocalId());TypedNoteTimeProjection.Sidecar sidecar=new TypedNoteTimeProjection.Sidecar(source.projectionSource,source.destinationBodySha,group.identity.destinationLocalId(),source.times);
            TypedNoteTimeProjection.Projection projected=TypedNoteTimeProjection.transported(json,sidecar);
            if(!group.identity.destinationLocalId().equals(projected.noteId))throw new IOException("durable_destination_note_mismatch");
            if(!Objects.equals(source.mapping.get(source.noteId),group.identity.destinationLocalId()))throw new IOException("durable_note_mapping_mismatch");
            JSONObject doc=NotePrecisionJsonParser.parseObject(json);int pages=NoteStore.pdfPageCountOrZero(doc);
            String pdfState=new String(group.readSmall("pdf.state",16),StandardCharsets.US_ASCII);
            if(pdfState.equals("absent\n")&&pages!=0||pdfState.equals("present\n")&&pages==0)throw new IOException("durable_pdf_body_state_mismatch");
            boolean hasVideo=expected.stream().anyMatch(s->s.startsWith("video/")&&s.endsWith("/content.bin"));
            boolean hasVault=expected.stream().anyMatch(s->s.startsWith("vault/")&&s.endsWith("/content.bin"));
            boolean hasCover="present\n".equals(new String(group.readSmall("cover.state",16),StandardCharsets.US_ASCII));
            verifyRealMediaGroup(group,source,doc,pages,pdfState,hasCover,hasVideo,hasVault);
          } catch(IOException e) { throw e; }
            catch(Exception e) { throw new IOException("durable_complete_group_verification_failed",e); }
    }

    /** Concrete media decoders consume duplicated descriptors from the same content-addressed object lease. */
    private static void verifyRealMediaGroup(StreamingGroupStore.VerifiedGroup group, DurableSource source,
            JSONObject body, int pages, String pdfState, boolean hasCover, boolean hasVideo, boolean hasVault) throws Exception {
        JSONArray images=body.optJSONArray("images");
        if(images!=null){
            long pixels=0;
            for(int i=0;i<images.length();i++){
                NoteImage image=null;
                try{
                    FullMediaPreflight.checkInterrupted();
                    JSONObject imageJson=images.getJSONObject(i);
                    validateEmbeddedPng(imageJson);
                    image=NoteImage.fromJson(imageJson);
                    if(image.bitmap==null||image.bitmap.isRecycled())throw new IOException("BODY_IMAGE_DECODE_FAILED");
                    pixels=Math.addExact(pixels,(long)image.bitmap.getWidth()*image.bitmap.getHeight());
                    if(pixels>NoteImage.MAX_DOCUMENT_PIXELS)throw new IOException("BODY_IMAGE_PIXEL_BUDGET");
                    FullMediaPreflight.checkInterrupted();
                } catch(Exception error){throw new IOException("BODY_IMAGE_INVALID",error);}
                finally {if(image!=null&&image.bitmap!=null&&!image.bitmap.isRecycled())image.bitmap.recycle();}
            }
        }
        if("present\n".equals(pdfState))validatePdf(group,"pdf.bin",pages);
        if(hasCover)validatePng(group,"cover.bin",StreamingGroupStore.COVER_MAX,"COVER");

        Map<String,DurableMaterial> expected=new HashMap<>();
        for(DurableMaterial material:source.materials){
            String key=material.role.name()+":"+material.destinationId;
            if(expected.put(key,material)!=null)throw new IOException("DUPLICATE_DURABLE_MATERIAL");
        }
        int videos=0,vault=0;
        for(String member:group.memberSizes().keySet()){
            if(!member.startsWith("video/")&&!member.startsWith("vault/"))continue;
            String[] parts=member.split("/",-1);if(parts.length!=3)throw new IOException("MEDIA_MEMBER_SHAPE");
            if(!parts[2].equals("content.bin"))continue;
            String role=parts[0].equals("video")?"VIDEO":"VAULT";
            DurableMaterial material=expected.get(role+":"+parts[1]);
            if(material==null)throw new IOException("MEDIA_PROVENANCE_MISSING");
            validateStoredMaterial(group,source,material,role,parts[1]);
            if(role.equals("VIDEO"))videos++;else vault++;
        }
        long expectedVideos=source.materials.stream().filter(m->m.role==Role.VIDEO).count();
        long expectedVault=source.materials.stream().filter(m->m.role==Role.VAULT).count();
        if(videos!=expectedVideos||vault!=expectedVault||hasVideo!=(videos>0)||hasVault!=(vault>0))
            throw new IOException("MEDIA_COLLECTION_STATE_MISMATCH");
    }

    private static void validatePng(StreamingGroupStore.VerifiedGroup group,String member,long cap,String label)throws IOException{
        Long size=group.memberSizes().get(member);if(size==null||size<=0||size>cap)throw new IOException(label+"_SIZE_LIMIT");
        try(StreamingGroupStore.VerifiedContentLease lease=group.openVerifiedContentLease(member);
            ParcelFileDescriptor pfd=ParcelFileDescriptor.dup(lease.descriptor())){
            try(ParcelFileDescriptor headerPfd=ParcelFileDescriptor.dup(lease.descriptor());
                InputStream headerIn=new ParcelFileDescriptor.AutoCloseInputStream(headerPfd)){
                byte[] header=new byte[8];int offset=0;
                while(offset<header.length){int n=headerIn.read(header,offset,header.length-offset);if(n<0)break;if(n==0)continue;offset+=n;}
                if(offset!=header.length)throw new IOException(label+"_PNG_SIGNATURE_INVALID");
                FullMediaPreflight.requirePngSignature(header,label);
            }
            FullMediaPreflight.checkInterrupted();
            Os.lseek(pfd.getFileDescriptor(),0,OsConstants.SEEK_SET);
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(),null,bounds);
            FullMediaPreflight.requirePngMime(bounds.outMimeType,label);
            long pixels=Math.multiplyExact((long)bounds.outWidth,(long)bounds.outHeight);
            if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>NoteImage.MAX_EDGE||bounds.outHeight>NoteImage.MAX_EDGE||pixels>NoteImage.MAX_DOCUMENT_PIXELS)
                throw new IOException(label+"_DIMENSIONS_INVALID");
            FullMediaPreflight.checkInterrupted();
            Os.lseek(pfd.getFileDescriptor(),0,OsConstants.SEEK_SET);
            Bitmap bitmap=BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(),null,new BitmapFactory.Options());
            if(bitmap==null)throw new IOException(label+"_DECODE_FAILED");
            try{if(bitmap.getWidth()!=bounds.outWidth||bitmap.getHeight()!=bounds.outHeight)throw new IOException(label+"_DIMENSIONS_CHANGED");}
            finally{bitmap.recycle();}
            FullMediaPreflight.checkInterrupted();
            lease.verifyUnchanged();
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException(label+"_INVALID",e);}
    }

    private static void validatePdf(StreamingGroupStore.VerifiedGroup group,String member,int expectedPages)throws IOException{
        Long size=group.memberSizes().get(member);if(size==null||size<=0||size>StreamingGroupStore.PDF_MAX)throw new IOException("PDF_SIZE_LIMIT");
        try(StreamingGroupStore.VerifiedContentLease lease=group.openVerifiedContentLease(member);
            ParcelFileDescriptor pfd=ParcelFileDescriptor.dup(lease.descriptor())){
            final PdfRenderer renderer;
            try{renderer=new PdfRenderer(pfd);}
            catch(IOException|IllegalArgumentException decoderRejected){throw new IOException("PDF_INVALID_OR_UNSUPPORTED",decoderRejected);}
            try(renderer){
                int count=renderer.getPageCount();if(count!=expectedPages||count<1||count>500)throw new IOException("PDF_PAGE_COUNT_MISMATCH");
                long renderedPixels=0;
                for(int i=0;i<count;i++){FullMediaPreflight.checkInterrupted();try(PdfRenderer.Page page=renderer.openPage(i)){
                    int w=page.getWidth(),h=page.getHeight();if(w<=0||h<=0||w>32768||h>32768)throw new IOException("PDF_PAGE_BOUNDS");
                    int renderW=Math.min(256,w),renderH=Math.min(256,h);
                    renderedPixels=Math.addExact(renderedPixels,(long)renderW*renderH);
                    if(renderedPixels>32L*1024*1024)throw new IOException("PDF_RENDER_WORK_LIMIT");
                    Bitmap bitmap=Bitmap.createBitmap(renderW,renderH,Bitmap.Config.ARGB_8888);
                    try{page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);}finally{bitmap.recycle();}
                }FullMediaPreflight.checkInterrupted();}
                lease.verifyUnchanged();
            }
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("PDF_INVALID_OR_UNSUPPORTED",e);}
    }

    private static void validateStoredMaterial(StreamingGroupStore.VerifiedGroup group,DurableSource source,DurableMaterial material,String role,String id)throws IOException{
        String prefix=role.toLowerCase(Locale.ROOT)+"/"+id+"/";
        byte[] descriptor=group.readSmall(prefix+"metadata.bin",1<<20);
        if(!shaHex(descriptor).equals(material.destinationDescriptorSha))throw new IOException("MATERIAL_DESCRIPTOR_HASH_MISMATCH");
        final padnote.material.MaterialCodec.DescriptorSummary d;
        try{d=padnote.material.MaterialCodec.inspectDescriptor(descriptor);}catch(RuntimeException e){throw new IOException("MATERIAL_DESCRIPTOR_INVALID",e);}
        String kind=role.equals("VIDEO")?"video":"vault";
        String expectedOwner=material.sourceState.equals("independent")?null:group.identity.destinationLineage();
        String expectedSource=material.sourceState.equals("linked_note")?group.identity.destinationLineage():null;
        if(!kind.equals(d.kind())||!id.equals(d.materialId())||!Objects.equals(expectedOwner,d.ownerLineageId())
                ||!Objects.equals(expectedSource,d.sourceLineageId())||!material.sourceState.equals(d.sourceState())
                ||d.contentSize()!=material.destinationSize||!material.destinationSha.equals(d.contentSha256()))
            throw new IOException("MATERIAL_DESCRIPTOR_BINDING_MISMATCH");
        if("linked_note".equals(material.sourceState)&&!Objects.equals(source.mapping.get(material.sourceLinkedNoteId),group.identity.destinationLocalId()))throw new IOException("MATERIAL_LINKED_NOTE_MAPPING_MISMATCH");
        if(!"linked_note".equals(material.sourceState)&&material.sourceLinkedNoteId!=null)throw new IOException("DETACHED_MATERIAL_HAS_ACTIVE_LINK");
        if(role.equals("VIDEO")&&!Objects.equals(material.mediaType,d.mediaType()))throw new IOException("VIDEO_MEDIA_TYPE_MISMATCH");
        byte[] type=group.readSmall(prefix+"media-type.txt",256);
        if(!Arrays.equals(type,(material.mediaType+"\n").getBytes(StandardCharsets.UTF_8)))throw new IOException("MATERIAL_TYPE_MEMBER_MISMATCH");
        try(StreamingGroupStore.VerifiedContentLease lease=group.openVerifiedContentLease(prefix+"content.bin")){
            if(lease.size!=d.contentSize()||!lease.sha256.equals(d.contentSha256()))throw new IOException("MATERIAL_CONTENT_BINDING_MISMATCH");
            if(role.equals("VIDEO"))validateVideo(lease);
            else validateVault(lease,d,material.platform,descriptor);
            lease.verifyUnchanged();
        }
    }

    private static void validateVideo(StreamingGroupStore.VerifiedContentLease lease)throws IOException{
        try(ParcelFileDescriptor pfd=ParcelFileDescriptor.dup(lease.descriptor())){
            MediaExtractor extractor=new MediaExtractor();MediaMetadataRetriever retriever=new MediaMetadataRetriever();
            try{
                extractor.setDataSource(pfd.getFileDescriptor(),0,lease.size);
                int tracks=extractor.getTrackCount();if(tracks<1||tracks>16)throw new IOException("VIDEO_TRACK_COUNT");
                List<FullMediaPreflight.VideoTrackFacts> trackFacts=new ArrayList<>();
                for(int i=0;i<tracks;i++){
                    FullMediaPreflight.checkInterrupted();
                    MediaFormat format=extractor.getTrackFormat(i);String mime=format.getString(MediaFormat.KEY_MIME);
                    boolean video=mime!=null&&mime.startsWith("video/");
                    long duration=format.containsKey(MediaFormat.KEY_DURATION)?format.getLong(MediaFormat.KEY_DURATION):0;
                    int width=format.containsKey(MediaFormat.KEY_WIDTH)?format.getInteger(MediaFormat.KEY_WIDTH):0;
                    int height=format.containsKey(MediaFormat.KEY_HEIGHT)?format.getInteger(MediaFormat.KEY_HEIGHT):0;
                    trackFacts.add(new FullMediaPreflight.VideoTrackFacts(video,width,height,duration));
                }
                FullMediaPreflight.validateVideoTracks(trackFacts,tracks,NoteImage.MAX_DOCUMENT_PIXELS);
                int[] decodeSize=FullMediaPreflight.scaledFrameSize(trackFacts.stream().filter(t->t.video).findFirst().get().width,
                        trackFacts.stream().filter(t->t.video).findFirst().get().height);
                FullMediaPreflight.checkInterrupted();
                retriever.setDataSource(pfd.getFileDescriptor(),0,lease.size);
                Bitmap frame=retriever.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,decodeSize[0],decodeSize[1]);
                if(frame==null)throw new IOException("VIDEO_FRAME_DECODE_FAILED");
                try{FullMediaPreflight.requireDecodedFrame(frame.getWidth(),frame.getHeight());}
                finally{frame.recycle();}
                FullMediaPreflight.checkInterrupted();
            }catch(IOException e){throw e;}catch(Exception e){throw new IOException("VIDEO_INVALID_OR_UNSUPPORTED",e);}
            finally{try{extractor.release();}finally{retriever.release();}}
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("VIDEO_DESCRIPTOR_FAILED",e);}
    }

    private static void validateEmbeddedPng(JSONObject image)throws Exception{
        String encoded=image.getString("png");
        if(encoded.length()>NoteImage.MAX_DOCUMENT_ENCODED)throw new IOException("BODY_IMAGE_ENCODED_LIMIT");
        byte[] bytes=Base64.decode(encoded,Base64.DEFAULT);
        FullMediaPreflight.requirePngSignature(bytes,"BODY_IMAGE");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        FullMediaPreflight.requirePngMime(bounds.outMimeType,"BODY_IMAGE");
        if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>NoteImage.MAX_EDGE||bounds.outHeight>NoteImage.MAX_EDGE
                ||(long)bounds.outWidth*bounds.outHeight>NoteImage.MAX_DOCUMENT_PIXELS)throw new IOException("BODY_IMAGE_DIMENSIONS_INVALID");
    }

    private static void validateVault(StreamingGroupStore.VerifiedContentLease lease,padnote.material.MaterialCodec.DescriptorSummary descriptor,String platform,byte[] descriptorBytes)throws IOException{
        if(lease.size<1||lease.size>StreamingGroupStore.VAULT_MAX)throw new IOException("VAULT_SIZE_LIMIT");
        ByteArrayOutputStream out=new ByteArrayOutputStream((int)lease.size);byte[] chunk=new byte[8192];long count=0;
        try(ParcelFileDescriptor pfd=ParcelFileDescriptor.dup(lease.descriptor());InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(pfd)){
            for(int n;(n=in.read(chunk))!=-1;){if(n==0)continue;count=Math.addExact(count,n);if(count>StreamingGroupStore.VAULT_MAX)throw new IOException("VAULT_SIZE_LIMIT");out.write(chunk,0,n);}
        }catch(Exception e){if(e instanceof IOException)throw(IOException)e;throw new IOException("VAULT_READ_FAILED",e);}
        byte[] raw=out.toByteArray();try{decodeStrictUtf8(raw);}catch(Exception e){throw new IOException("VAULT_UTF8_INVALID",e);}
        if(!shaHex(raw).equals(descriptor.contentSha256())||count!=descriptor.contentSize())throw new IOException("VAULT_DESCRIPTOR_CONTENT_MISMATCH");
        if("android".equals(platform))try{
            padnote.material.StorageAdapter.Association association=new padnote.material.StorageAdapter.Association(
                    descriptor.materialId(),descriptor.ownerLineageId(),descriptor.sourceState(),descriptor.sourceLineageId());
            padnote.material.StorageAdapter.Projection projection=padnote.material.StorageAdapter.androidVaultMarkdown(raw,association);
            if(!Arrays.equals(descriptorBytes,projection.descriptor()))throw new IOException("VAULT_ANDROID_DESCRIPTOR_REPROJECTION_MISMATCH");
        }catch(RuntimeException e){throw new IOException("VAULT_ANDROID_PAYLOAD_INVALID",e);}
        else if(!"ios".equals(platform))throw new IOException("VAULT_PLATFORM_UNSUPPORTED");
    }

    private record DurableSource(String lineage,String noteId,String sourceRevision,String sourceBodySha,
                                 String destinationBodySha,String projectionSource,
                                 Map<String,TypedNoteTimeProjection.ProvenanceValue> times,Map<String,String> mapping,List<DurableMaterial> materials) {}
    private record DurableMaterial(Role role,String sourceId,String destinationId,String mediaType,String platform,
                                   String sourceState,String sourceLineage,String sourceOwnerLineage,long sourceSize,
                                   String sourceSha,long destinationSize,String destinationSha,String sourceMetadataSha,
                                   String sourceLinkedNoteId,String destinationDescriptorSha) {}
    static boolean validSourceAssociation(String state,String materialSourceLineage,String ownerLineage,
                                          String linkedNoteId,String sourceLineage,String sourceNoteId){
        if("linked_note".equals(state))return Objects.equals(ownerLineage,sourceLineage)
                &&Objects.equals(materialSourceLineage,sourceLineage)&&Objects.equals(linkedNoteId,sourceNoteId);
        if("source_deleted".equals(state)||"source_not_selected".equals(state))return Objects.equals(ownerLineage,sourceLineage)
                &&(materialSourceLineage==null||Objects.equals(materialSourceLineage,sourceLineage))&&linkedNoteId==null;
        if("independent".equals(state))return ownerLineage==null&&materialSourceLineage==null&&linkedNoteId==null;
        return false;
    }
    static void validateActualNote(JSONObject document,String expectedNoteId)throws Exception {
        requireUnicodeScalars(document);
        if(!document.getString("id").equals(expectedNoteId))throw new IllegalArgumentException("body_note_identity_mismatch");
        NoteStore.validateDocument(document);
    }
    private static DurableSource decodeDurableSource(byte[] bytes)throws IOException {
        try(DataInputStream d=new DataInputStream(new ByteArrayInputStream(bytes))){
            byte[] domain="PadNote/UpdateSourceProvenance/v2\0".getBytes(StandardCharsets.US_ASCII);
            if(!Arrays.equals(readN(d,domain.length),domain))throw new IOException("durable_provenance_domain");
            String kind=readText(d),lineage=readText(d),note=readText(d),revision=readText(d),sourceSha=readText(d),targetSha=readText(d);
            String projectionSource=switch(kind){case "NATIVE_IOS"->"native-ios";case "NATIVE_ANDROID"->"native-android";case "TRANSPORTED"->"transport";default->throw new IOException("durable_provenance_kind");};
            requireUuid(lineage,"durable_lineage");requireId(note,"durable_note");requireId(revision,"durable_revision");requireHash(sourceSha,"durable_source_sha");requireHash(targetSha,"durable_target_sha");
            int n=d.readInt();if(n<1||n>20_000)throw new IOException("durable_time_count");TreeMap<String,TypedNoteTimeProjection.ProvenanceValue> times=new TreeMap<>();
            for(int i=0;i<n;i++){String pointer=readText(d);int tag=d.readUnsignedByte();long value=d.readLong();TypedNoteTimeProjection.Kind timeKind=switch(tag){case 0->TypedNoteTimeProjection.Kind.I64;case 1->TypedNoteTimeProjection.Kind.F64;default->throw new IOException("durable_time_kind");};if(!pointer.startsWith("/")||times.put(pointer,new TypedNoteTimeProjection.ProvenanceValue(timeKind,timeKind==TypedNoteTimeProjection.Kind.I64?value:0,timeKind==TypedNoteTimeProjection.Kind.F64?value:0))!=null)throw new IOException("durable_time_pointer");}
            int m=d.readInt();if(m<1||m>10_000)throw new IOException("durable_mapping_count");TreeMap<String,String> mapping=new TreeMap<>();HashSet<String> dest=new HashSet<>();for(int i=0;i<m;i++){String from=readText(d),to=readText(d);requireId(from,"durable_mapping_source");requireId(to,"durable_mapping_target");if(mapping.put(from,to)!=null||!dest.add(to))throw new IOException("durable_mapping_duplicate");}
            int materialCount=d.readInt();if(materialCount<0||materialCount>10_000)throw new IOException("durable_material_count");ArrayList<DurableMaterial> materials=new ArrayList<>();HashSet<String> materialDestinations=new HashSet<>();
            for(int i=0;i<materialCount;i++){Role role;try{role=Role.valueOf(readText(d));}catch(RuntimeException e){throw new IOException("durable_material_role");}String from=readText(d),to=readText(d),media=readText(d),platform=readText(d),state=readText(d),sourceLineage=readNullable(d),sourceOwner=readNullable(d);long size=d.readLong();byte[] sh=readN(d,32);long targetSize=d.readLong();byte[] th=readN(d,32),mh=readN(d,32);String linkedNote=readNullable(d);byte[] descriptorSha=readN(d,32);requireId(from,"durable_material_source");requireId(to,"durable_material_target");if(!materialDestinations.add(role+":"+to)||size<0||targetSize<0||sh.length!=32||th.length!=32||mh.length!=32||descriptorSha.length!=32||!mapping.containsKey(from)||!mapping.get(from).equals(to))throw new IOException("durable_material_binding");if(!setOf("android","ios").contains(platform)||!setOf("linked_note","source_deleted","source_not_selected","independent").contains(state))throw new IOException("durable_material_profile");if(!validSourceAssociation(state,sourceLineage,sourceOwner,linkedNote,lineage,note))throw new IOException("durable_material_association_mismatch");materials.add(new DurableMaterial(role,from,to,media,platform,state,sourceLineage,sourceOwner,size,hex(sh),targetSize,hex(th),hex(mh),linkedNote,hex(descriptorSha)));}
            if(d.read()!=-1)throw new IOException("durable_provenance_trailing");
            return new DurableSource(lineage,note,revision,sourceSha,targetSha,projectionSource,Collections.unmodifiableMap(times),Collections.unmodifiableMap(mapping),Collections.unmodifiableList(new ArrayList<>(materials)));
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("durable_provenance_invalid",e);}
    }
    private static String readText(DataInputStream d)throws IOException {int n=d.readInt();if(n<0||n>1<<20)throw new IOException("durable_text_limit");byte[] b=readN(d,n);if(b.length!=n)throw new EOFException();try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();}catch(CharacterCodingException e){throw new IOException("durable_text_utf8",e);}}
    private static String readNullable(DataInputStream d)throws IOException{return d.readBoolean()?readText(d):null;}

    /** Build the only envelope accepted by Store.commitManualUpdateV2 for this plan. */
    public StreamingGroupStore.Envelope envelope(String newRevision,
            StreamingGroupStore.ContentInput pdfInput, StreamingGroupStore.ContentInput coverInput,
            List<StreamingGroupStore.MemberInput> videoInputs,
            List<StreamingGroupStore.MemberInput> vaultInputs) {
        requireUuid(newRevision,"new_revision");
        if ((pdfPresence==Presence.PRESENT)!=(pdfInput!=null)||(coverPresence==Presence.PRESENT)!=(coverInput!=null))
            throw new IllegalArgumentException("envelope_optional_component_mismatch");
        if (videoInputs==null||vaultInputs==null) throw new IllegalArgumentException("envelope_collections_required");
        checkContent(pdf,pdfInput,"pdf"); checkContent(cover,coverInput,"cover");
        checkMaterialInputs(videos,videoInputs,Role.VIDEO); checkMaterialInputs(vault,vaultInputs,Role.VAULT);
        StreamingGroupStore.ImportIdentity identity=new StreamingGroupStore.ImportIdentity(
                provenance.sourceLineage(),provenance.sourceNoteId(),target.localId(),target.lineage(),null,null);
        return new StreamingGroupStore.Envelope(identity,newRevision,target.revision(),target.revision(),target.digest(),
                StreamingGroupStore.Action.MANUAL_UPDATE_V2,bodyJson,pdfInput,coverInput,videoInputs,vaultInputs,provenanceBytes);
    }

    /** Envelope for the new-profile in-place legacy adoption action. */
    public StreamingGroupStore.Envelope legacyAdoptionEnvelope(String newRevision,
            StreamingGroupStore.ContentInput pdfInput, StreamingGroupStore.ContentInput coverInput,
            List<StreamingGroupStore.MemberInput> videoInputs, List<StreamingGroupStore.MemberInput> vaultInputs) {
        if(!legacyAdoption)throw new IllegalStateException("LEGACY_ADOPTION_PLAN_REQUIRED");
        requireUuid(newRevision,"new_revision");
        if ((pdfPresence==Presence.PRESENT)!=(pdfInput!=null)||(coverPresence==Presence.PRESENT)!=(coverInput!=null)) throw new IllegalArgumentException("envelope_optional_component_mismatch");
        if(videoInputs==null||vaultInputs==null)throw new IllegalArgumentException("envelope_collections_required");
        checkContent(pdf,pdfInput,"pdf");checkContent(cover,coverInput,"cover");checkMaterialInputs(videos,videoInputs,Role.VIDEO);checkMaterialInputs(vault,vaultInputs,Role.VAULT);
        StreamingGroupStore.ImportIdentity identity=new StreamingGroupStore.ImportIdentity(provenance.sourceLineage(),provenance.sourceNoteId(),target.localId(),target.lineage(),null,null);
        return new StreamingGroupStore.Envelope(identity,newRevision,null,null,null,StreamingGroupStore.Action.LEGACY_ADOPTION_V2,bodyJson,pdfInput,coverInput,videoInputs,vaultInputs,provenanceBytes);
    }

    public boolean authorizesLegacyAdoption(StreamingGroupStore.Envelope e) {
        if(!legacyAdoption||legacySourceGuard==null||e==null||e.action!=StreamingGroupStore.Action.LEGACY_ADOPTION_V2||e.identity==null||e.videos==null||e.vault==null)return false;
        boolean binding=target.localId().equals(e.identity.destinationLocalId())&&target.lineage().equals(e.identity.destinationLineage())
                &&provenance.sourceLineage().equals(e.identity.originLineage())&&provenance.sourceNoteId().equals(e.identity.originLocalId())
                &&target.localId().equals(e.identity.originLocalId())&&target.lineage().equals(e.identity.originLineage())
                &&e.revision!=null&&!e.revision.equals(target.revision())&&e.baseRevision==null&&e.baseDigest==null&&e.parentRevision==null
                &&Arrays.equals(bodyJson,e.bodyBytes())&&Arrays.equals(provenanceBytes,e.sourceProvenance());
        if(!binding)return false;
        try{checkContent(pdf,e.pdf,"pdf");checkContent(cover,e.cover,"cover");checkMaterialInputs(videos,e.videos,Role.VIDEO);checkMaterialInputs(vault,e.vault,Role.VAULT);return true;}
        catch(RuntimeException invalid){return false;}
    }

    public StreamingGroupStore.Preview verifyBeforeLegacyAdoptionPreview(StreamingGroupStore.Preview userPreview) {
        Objects.requireNonNull(userPreview);
        if(!legacyAdoption)throw new IllegalStateException("LEGACY_ADOPTION_PLAN_REQUIRED");
        return (current,sealed)->{if(current!=null)throw new IllegalArgumentException("LEGACY_GROUP_ALREADY_VISIBLE");verifyStaged(sealed);return userPreview.approve(null,sealed);};
    }

    /** Store checks this opaque plan at commit entry; callers cannot authorize by digest alone. */
    public boolean authorizes(StreamingGroupStore.Envelope e) {
        if(e==null||e.action!=StreamingGroupStore.Action.MANUAL_UPDATE_V2||e.identity==null||e.videos==null||e.vault==null)return false;
        boolean binding=target.localId().equals(e.identity.destinationLocalId())&&target.lineage().equals(e.identity.destinationLineage())
                &&provenance.sourceLineage().equals(e.identity.originLineage())&&provenance.sourceNoteId().equals(e.identity.originLocalId())
                &&e.revision!=null&&!e.revision.equals(target.revision())
                &&Objects.equals(target.revision(),e.baseRevision)&&Objects.equals(target.digest(),e.baseDigest)
                &&Objects.equals(target.revision(),e.parentRevision)&&Arrays.equals(bodyJson,e.bodyBytes())
                &&Arrays.equals(provenanceBytes,e.sourceProvenance());
        if(!binding)return false;
        try { checkContent(pdf,e.pdf,"pdf");checkContent(cover,e.cover,"cover");checkMaterialInputs(videos,e.videos,Role.VIDEO);checkMaterialInputs(vault,e.vault,Role.VAULT);return true; }
        catch(RuntimeException invalid){return false;}
    }

    private static void checkContent(Facts facts,StreamingGroupStore.ContentInput input,String name) {
        if(facts==null){if(input!=null)throw new IllegalArgumentException(name+"_unexpected");return;}
        if(input==null||input.size!=facts.size()||!facts.sha256().equalsIgnoreCase(input.sha256))throw new IllegalArgumentException(name+"_content_binding_mismatch");
    }
    private void checkMaterialInputs(List<Material> materials,List<StreamingGroupStore.MemberInput> inputs,Role role) {
        if(materials.size()!=inputs.size())throw new IllegalArgumentException(role+"_member_count_mismatch");
        Map<String,Material> expected=new HashMap<>();for(Material m:materials)expected.put(m.destinationId(),m);
        Set<String> seen=new HashSet<>();for(StreamingGroupStore.MemberInput in:inputs){if(in==null)throw new IllegalArgumentException(role+"_member_binding_mismatch");Material m=expected.get(in.materialId);if(m==null)throw new IllegalArgumentException(role+"_member_binding_mismatch");Facts destination=destinationContentFacts(m,target.localId());if(!seen.add(in.materialId)||!storeRole(role).equals(in.role)||!m.mediaType().equals(in.mediaType)||in.content==null||in.content.size!=destination.size()||!destination.sha256().equalsIgnoreCase(in.content.sha256)||!Arrays.equals(in.metadata(),descriptorFor(m)))throw new IllegalArgumentException(role+"_member_binding_mismatch");}
    }
    private static String storeRole(Role role) {
        return switch (role) {
            case VIDEO -> "video";
            case VAULT -> "vault";
            case PDF, COVER -> throw new IllegalArgumentException("component_not_a_material_list");
        };
    }
    private byte[] descriptorFor(Material material) {
        for(int i=0;i<concat(videos,vault).size();i++)if(concat(videos,vault).get(i).equals(material))return materialProjections.get(i).descriptor();
        throw new IllegalArgumentException("material_plan_missing");
    }
    private static Facts destinationContentFacts(Material material,String destinationNoteId) {
        if(material.role()==Role.VAULT&&"android".equals(material.platform())&&"linked_note".equals(material.association().sourceState())) {
            try { byte[] transformed=androidVaultRebind(material.rawMetadata(),destinationNoteId);return new Facts(transformed.length,shaHex(transformed)); }
            catch(Exception e){throw new IllegalArgumentException("vault_destination_rebind_invalid",e);}
        }
        return material.content();
    }
    private static byte[] androidVaultRebind(String raw,String targetNoteId)throws Exception {
        byte[] original=strictUtf8Bytes(raw);if(original.length>StreamingGroupStore.VAULT_MAX)throw new IllegalArgumentException("vault_size_limit");
        String[] lines=raw.split("\\n",-1);StringBuilder out=new StringBuilder(raw.length()+targetNoteId.length());int found=0;
        for(int i=0;i<lines.length;i++){String line=lines[i];if(line.startsWith("note-id: ")){line="note-id: "+targetNoteId;found++;}out.append(line);if(i+1<lines.length)out.append('\n');}
        if(found!=1)throw new IllegalArgumentException("vault_note_id_rebind_count");return strictUtf8Bytes(out.toString());
    }

    /**
     * Wrap the store preview so semantic checks happen after staging, hash verification,
     * immutability and before the user sees the complete preview. It does not publish or
     * commit anything. Existing R2 Envelope identity rules still do not implement this
     * manual cross-lineage plan; an app/core adapter is a separate required step.
     */
    public StreamingGroupStore.Preview verifyBeforePreview(StreamingGroupStore.Preview userPreview) {
        Objects.requireNonNull(userPreview);
        return (current, sealed) -> {
            if (current == null || !target.localId().equals(current.localId) || !target.lineage().equals(current.lineage)
                    || !target.revision().equals(current.revision) || !target.digest().equals(current.digest))
                throw new IllegalArgumentException("selected_target_base_changed");
            verifyStaged(sealed);
            return userPreview.approve(current, sealed);
        };
    }

    public void verifyStaged(StreamingGroupStore.VerifiedGroup group) throws Exception {
        if (group == null || !group.identity.destinationLocalId().equals(target.localId())
                || !group.identity.destinationLineage().equals(target.lineage()) || group.revision.equals(target.revision()))
            throw new IllegalArgumentException("staged_target_identity_mismatch");
        Map<String,Long> sizes = group.memberSizes();
        Set<String> expected = new TreeSet<>(Arrays.asList("body.bin", "pdf.state", "cover.state", "video.state", "vault.state", "source-provenance.bin"));
        if (pdfPresence == Presence.PRESENT) expected.add("pdf.bin");
        if (coverPresence == Presence.PRESENT) expected.add("cover.bin");
        for (Material m : concat(videos, vault)) {
            String role = m.role() == Role.VIDEO ? "video" : "vault";
            String id = m.destinationId(); expected.add(role+"/"+id+"/content.bin");
            expected.add(role+"/"+id+"/media-type.txt"); expected.add(role+"/"+id+"/metadata.bin");
        }
        if (!sizes.keySet().equals(expected)) throw new IllegalArgumentException("staged_member_set_mismatch");
        if (!Arrays.equals(readSmall(group,"source-provenance.bin",1<<20),provenanceBytes)) throw new IllegalArgumentException("staged_source_provenance_mismatch");
        byte[] stagedBody = readSmall(group, "body.bin", StreamingGroupStore.BODY_MAX);
        if (!shaHex(stagedBody).equals(bodySha256) || !Arrays.equals(stagedBody, bodyJson)) throw new IllegalArgumentException("staged_body_changed");
        if (!Arrays.equals(readSmall(group,"pdf.state",16), state(pdfPresence).getBytes(StandardCharsets.US_ASCII))) throw new IllegalArgumentException("staged_pdf_state");
        if (!Arrays.equals(readSmall(group,"cover.state",16), state(coverPresence).getBytes(StandardCharsets.US_ASCII))) throw new IllegalArgumentException("staged_cover_state");
        if (!Arrays.equals(readSmall(group,"video.state",16), "present\n".getBytes(StandardCharsets.US_ASCII))
                || !Arrays.equals(readSmall(group,"vault.state",16), "present\n".getBytes(StandardCharsets.US_ASCII))) throw new IllegalArgumentException("staged_collection_state");
        validateActualNote(NotePrecisionJsonParser.parseObject(decodeStrictUtf8(stagedBody)),target.localId());
        int pi=0;
        for (Material m : concat(videos, vault)) {
            String role=m.role()==Role.VIDEO?"video":"vault", prefix=role+"/"+m.destinationId()+"/";
            if (!Arrays.equals(readSmall(group,prefix+"media-type.txt",256),(m.mediaType()+"\n").getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException("staged_media_type_mismatch");
            if (!Arrays.equals(readSmall(group,prefix+"metadata.bin",1<<20),materialProjections.get(pi++).descriptor())) throw new IllegalArgumentException("staged_metadata_mismatch");
            verifyContent(group,prefix+"content.bin",destinationContentFacts(m,target.localId()));
        }
        // The store invokes verifyStaged once before preview and again under publication
        // locks immediately before replacing the marker. For legacy adoption this second
        // check re-captures the source tree after user confirmation, so a callback-time
        // NoteStore/media edit cannot be overwritten by the staged older snapshot.
        if(legacyAdoption){
            if(legacySourceGuard==null)throw new IOException("LEGACY_SOURCE_GUARD_REQUIRED");
            try(AutoCloseable ignored=legacySourceGuard.lockAndVerify()) { /* capture verified while writers are fenced */ }
        }
    }

    private static void verifyContent(StreamingGroupStore.VerifiedGroup g,String member,Facts facts)throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");long n=0;byte[] buf=new byte[StreamingGroupStore.COPY_BUFFER_BYTES];
        try(InputStream in=g.openContent(member)){for(int r;(r=in.read(buf))!=-1;){if(r==0)continue;n=Math.addExact(n,r);md.update(buf,0,r);}}
        if(n!=facts.size()||!hex(md.digest()).equalsIgnoreCase(facts.sha256()))throw new IllegalArgumentException("staged_content_digest_mismatch");
    }
    private static byte[] readSmall(StreamingGroupStore.VerifiedGroup g,String member,long cap)throws Exception { if(g.memberSizes().get(member)>cap)throw new IllegalArgumentException("staged_small_member_limit"); return g.readSmall(member,(int)cap); }
    private static String state(Presence p){return p==Presence.PRESENT?"present\n":"absent\n";}

    private static StorageAdapter.Projection projectMaterial(Material m, SelectedTarget target, Provenance source) throws Exception {
        requireUtf8(m.rawMetadata(),1<<20,"material_metadata_utf8");
        if (m.role()==Role.VAULT) {
            byte[] saved = m.platform().equals("ios") ? strictUtf8Bytes(new JSONObject(m.rawMetadata()).getString("markdown")) : strictUtf8Bytes(m.rawMetadata());
            if (saved.length != m.content().size() || !shaHex(saved).equalsIgnoreCase(m.content().sha256())) throw new IllegalArgumentException("vault_saved_content_mismatch");
        }
        StorageAdapter.Projection sourceProjection = switch(m.role()) {
            case VIDEO -> StorageAdapter.videoJson(m.rawMetadata(),m.platform(),m.association(),m.content().size(),m.content().sha256());
            case VAULT -> m.platform().equals("ios")?StorageAdapter.iosVaultJson(m.rawMetadata(),m.association()):StorageAdapter.androidVaultMarkdown(m.rawMetadata().getBytes(StandardCharsets.UTF_8),new StorageAdapter.Association(androidVaultSourceMaterialId(m),m.association().ownerLineageId(),m.association().sourceState(),m.association().sourceLineageId()));
            case PDF, COVER -> throw new IllegalArgumentException("binary_component_not_a_material_descriptor");
        };
        String destinationMetadata = rewriteMaterialMetadata(m,target,source);
        String state=m.association().sourceState();
        String destinationOwner="independent".equals(state)?null:target.lineage();
        String destinationSourceLineage="linked_note".equals(state)?target.lineage():null;
        StorageAdapter.Association destinationAssociation = new StorageAdapter.Association(m.destinationId(), destinationOwner, state, destinationSourceLineage);
        StorageAdapter.Projection destinationProjection = switch(m.role()) {
            case VIDEO -> StorageAdapter.videoJson(destinationMetadata,m.platform(),destinationAssociation,m.content().size(),m.content().sha256());
            case VAULT -> m.platform().equals("ios")?StorageAdapter.iosVaultJson(destinationMetadata,destinationAssociation):StorageAdapter.androidVaultMarkdown(destinationMetadata.getBytes(StandardCharsets.UTF_8),destinationAssociation);
            case PDF, COVER -> throw new IllegalArgumentException("binary_component_not_a_material_descriptor");
        };
        if (sourceProjection == null) throw new AssertionError();
        return destinationProjection;
    }
    private static String androidVaultSourceMaterialId(Material m) {
        return m.sourceId().matches("note-[0-9a-f]{64}\\.md")
                ?StorageAdapter.androidVaultMaterialId(m.sourceId()):m.association().materialId();
    }
    private static String rewriteMaterialMetadata(Material m, SelectedTarget target, Provenance source) throws Exception {
        if (m.role()==Role.VIDEO) {
            JSONObject j=new JSONObject(m.rawMetadata());
            if (m.platform().equals("android")) { j.put("id",m.destinationId()); j.put("storedName","video-"+m.destinationId()+".mp4"); if("linked_note".equals(m.association().sourceState()))j.put("noteId",target.localId()); }
            else { j.put("id",m.destinationId()); if("linked_note".equals(m.association().sourceState()))j.put("noteID",target.localId()); }
            return j.toString();
        }
        if (m.platform().equals("ios")) {
            JSONObject j=new JSONObject(m.rawMetadata()); j.put("id",m.destinationId());
            if ("linked_note".equals(m.association().sourceState())) j.put("archiveLinkedNoteID",target.localId());
            return j.toString();
        }
        return "linked_note".equals(m.association().sourceState())?decodeStrictUtf8(androidVaultRebind(m.rawMetadata(),target.localId())):m.rawMetadata();
    }
    private static void validateMappedMaterials(Provenance source,SelectedTarget target,List<Material> videos,List<Material> vault,Map<String,String> map) {
        if (!Objects.equals(map.get(source.sourceNoteId()),target.localId())) throw new IllegalArgumentException("source_note_mapping_required");
        Set<String> destinations=new HashSet<>(); for(Map.Entry<String,String> e:map.entrySet()){requireId(e.getKey(),"mapping_source_id");requireId(e.getValue(),"mapping_destination_id");if(!destinations.add(e.getValue()))throw new IllegalArgumentException("mapping_not_injective");}
        for(Material m:videos)if(m.role()!=Role.VIDEO)throw new IllegalArgumentException("video_collection_role");
        for(Material m:vault)if(m.role()!=Role.VAULT)throw new IllegalArgumentException("vault_collection_role");
        HashSet<String> inputIds=new HashSet<>();for(Material m:concat(videos,vault)){
            if(!inputIds.add(m.sourceId()))throw new IllegalArgumentException("duplicate_source_material_id");
            if(!Objects.equals(map.get(m.sourceId()),m.destinationId()))throw new IllegalArgumentException("material_mapping_mismatch");
            String state=m.association().sourceState();
            if(!setOf("linked_note","source_deleted","source_not_selected","independent").contains(state))throw new IllegalArgumentException("material_source_state_invalid");
            if("independent".equals(state)) { if(m.association().ownerLineageId()!=null||m.association().sourceLineageId()!=null)throw new IllegalArgumentException("material_independent_scope_mismatch"); }
            else if(!Objects.equals(m.association().ownerLineageId(),source.sourceLineage())||m.association().sourceLineageId()!=null&&!Objects.equals(m.association().sourceLineageId(),source.sourceLineage()))throw new IllegalArgumentException("material_owner_scope_mismatch");
            if("linked_note".equals(state)) { String linked=StorageAdapter.activeNoteBindingId(m.rawMetadata(),m.platform(),m.role().name()); if(!Objects.equals(linked,source.sourceNoteId()))throw new IllegalArgumentException("material_source_note_binding_mismatch"); }
            else if(!"independent".equals(state)&&m.role()==Role.VAULT&&m.platform().equals("ios")&&StorageAdapter.activeNoteBindingId(m.rawMetadata(),m.platform(),m.role().name())!=null)throw new IllegalArgumentException("material_detached_active_link");
        }
        if(!map.keySet().containsAll(inputIds))throw new IllegalArgumentException("mapping_incomplete");
    }
    private static List<Material> concat(List<Material>a,List<Material>b){ArrayList<Material>x=new ArrayList<>(a);x.addAll(b);return x;}
    private byte[] canonicalPlanBytes() {
        try { ByteArrayOutputStream b=new ByteArrayOutputStream(); DataOutputStream d=new DataOutputStream(b); d.write("PadNote/VerifiedUpdatePlan/manual-v1\0".getBytes(StandardCharsets.US_ASCII));
            put(d,target.localId());put(d,target.lineage());put(d,target.revision());put(d,target.digest());put(d,provenance.sourceLineage());put(d,provenance.sourceNoteId());put(d,provenance.sourceRevision());d.write(bodySha256.getBytes(StandardCharsets.US_ASCII));d.writeInt(bodyProjection.length);d.write(sha(bodyProjection));d.writeInt(provenanceBytes.length);d.write(sha(provenanceBytes));d.writeByte(pdfPresence.ordinal());d.writeByte(coverPresence.ordinal());
            for(Material m:concat(videos,vault)){put(d,m.role().name());put(d,m.sourceId());put(d,m.destinationId());put(d,m.mediaType());d.writeLong(m.content().size());d.write(unhex(m.content().sha256()));}
            for(Map.Entry<String,String> e:idMapping.entrySet()){put(d,e.getKey());put(d,e.getValue());} d.flush();return b.toByteArray();
        }catch(IOException e){throw new AssertionError(e);}
    }
    private static byte[] encodeProvenance(Provenance p,TypedNoteTimeProjection.Projection projection,Map<String,String> mapping,String destinationBodySha,List<Material> materials,SelectedTarget target) throws Exception {
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);d.write("PadNote/UpdateSourceProvenance/v2\0".getBytes(StandardCharsets.US_ASCII));put(d,p.kind().name());put(d,p.sourceLineage());put(d,p.sourceNoteId());put(d,p.sourceRevision());put(d,projection.rawSha256);put(d,destinationBodySha);d.writeInt(projection.times.size());for(TypedNoteTimeProjection.Time t:projection.times){put(d,t.pointer);d.writeByte(t.kind.ordinal());d.writeLong(t.kind==TypedNoteTimeProjection.Kind.I64?t.integer:t.doubleBits);}d.writeInt(mapping.size());for(Map.Entry<String,String> e:new TreeMap<>(mapping).entrySet()){put(d,e.getKey());put(d,e.getValue());}
        ArrayList<Material> ordered=new ArrayList<>(materials);ordered.sort(Comparator.comparing((Material m)->m.role().name()).thenComparing(Material::sourceId));d.writeInt(ordered.size());for(Material m:ordered){Facts destination=destinationContentFacts(m,target.localId());StorageAdapter.Projection projected=projectMaterial(m,target,p);put(d,m.role().name());put(d,m.sourceId());put(d,m.destinationId());put(d,m.mediaType());put(d,m.platform());put(d,m.association().sourceState());putNullable(d,m.association().sourceLineageId());putNullable(d,m.association().ownerLineageId());d.writeLong(m.content().size());d.write(unhex(m.content().sha256()));d.writeLong(destination.size());d.write(unhex(destination.sha256()));d.write(sha(strictUtf8Bytes(m.rawMetadata())));String linked="linked_note".equals(m.association().sourceState())?StorageAdapter.activeNoteBindingId(m.rawMetadata(),m.platform(),m.role().name()):null;putNullable(d,linked);d.write(sha(projected.descriptor()));}
        d.flush();if(b.size()>1<<20)throw new IllegalArgumentException("update_provenance_limit");return b.toByteArray();
    }
    private static String decodeStrictUtf8(byte[] bytes) throws Exception { if(bytes.length>StreamingGroupStore.BODY_MAX)throw new IllegalArgumentException("body_limit"); return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
    private static byte[] strictUtf8Bytes(String value) throws Exception { ByteBuffer b=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));byte[]out=new byte[b.remaining()];b.get(out);return out; }
    private static void put(DataOutputStream d,String s)throws IOException{byte[] b=s.getBytes(StandardCharsets.UTF_8);d.writeInt(b.length);d.write(b);}
    private static void putNullable(DataOutputStream d,String s)throws IOException{d.writeBoolean(s!=null);if(s!=null)put(d,s);}
    private static void requireUtf8(String s,int max,String code){try{ByteBuffer b=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(s));if(b.remaining()>max)throw new IllegalArgumentException(code);}catch(CharacterCodingException e){throw new IllegalArgumentException("invalid_unicode_scalar");}}
    private static void requireUnicodeScalars(Object value) throws Exception {
        if (value instanceof String s) { for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isHighSurrogate(c)){if(i+1>=s.length()||!Character.isLowSurrogate(s.charAt(++i)))throw new IllegalArgumentException("invalid_unicode_scalar");}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("invalid_unicode_scalar");} }
        else if(value instanceof JSONObject o){Iterator<String> keys=o.keys();while(keys.hasNext()){String k=keys.next();requireUnicodeScalars(k);requireUnicodeScalars(o.get(k));}}
        else if(value instanceof org.json.JSONArray a){for(int i=0;i<a.length();i++)requireUnicodeScalars(a.get(i));}
    }
    private static byte[] replaceTopLevelStringField(byte[] source,String field,String value) throws Exception {
        String raw=decodeStrictUtf8(source); int p=0; while(p<raw.length()&&Character.isWhitespace(raw.charAt(p)))p++;
        if(p>=raw.length()||raw.charAt(p++)!='{')throw new IllegalArgumentException("body_root_object");
        while(true){while(p<raw.length()&&Character.isWhitespace(raw.charAt(p)))p++;if(p<raw.length()&&raw.charAt(p)=='}')break;
            int keyStart=p;if(p>=raw.length()||raw.charAt(p++)!='\"')throw new IllegalArgumentException("body_key");boolean escape=false;
            while(p<raw.length()){char c=raw.charAt(p++);if(escape){escape=false;continue;}if(c=='\\'){escape=true;continue;}if(c=='\"')break;}
            String keyToken=raw.substring(keyStart,p);String key=keyToken.equals("\""+field+"\"")?field:"";while(p<raw.length()&&Character.isWhitespace(raw.charAt(p)))p++;if(p>=raw.length()||raw.charAt(p++)!=':')throw new IllegalArgumentException("body_colon");while(p<raw.length()&&Character.isWhitespace(raw.charAt(p)))p++;
            if(field.equals(key)){if(p>=raw.length()||raw.charAt(p)!='\"')throw new IllegalArgumentException("body_identity_string");int start=p++;escape=false;while(p<raw.length()){char c=raw.charAt(p++);if(escape){escape=false;continue;}if(c=='\\'){escape=true;continue;}if(c=='\"')break;}String replacement="\""+value+"\"";byte[] prefix=raw.substring(0,start).getBytes(StandardCharsets.UTF_8),middle=replacement.getBytes(StandardCharsets.UTF_8),suffix=raw.substring(p).getBytes(StandardCharsets.UTF_8);byte[] out=Arrays.copyOf(prefix,prefix.length+middle.length+suffix.length);System.arraycopy(middle,0,out,prefix.length,middle.length);System.arraycopy(suffix,0,out,prefix.length+middle.length,suffix.length);return out;}
            int depth=0;boolean inString=false;escape=false;while(p<raw.length()){char c=raw.charAt(p);if(inString){p++;if(escape){escape=false;continue;}if(c=='\\'){escape=true;continue;}if(c=='\"')inString=false;continue;}if(c=='\"'){inString=true;p++;continue;}if(c=='{'||c=='['){depth++;p++;continue;}if(c=='}'||c==']'){if(depth==0)break;depth--;p++;continue;}if(c==','&&depth==0)break;p++;}if(p<raw.length()&&raw.charAt(p)==','){p++;continue;}break;
        }throw new IllegalArgumentException("body_id_missing");
    }
    private static TypedNoteTimeProjection.Sidecar sidecarForProjectedTimes(TypedNoteTimeProjection.Projection p,byte[] json,String noteId){Map<String,TypedNoteTimeProjection.ProvenanceValue> values=new TreeMap<>();for(TypedNoteTimeProjection.Time t:p.times)values.put(t.pointer,new TypedNoteTimeProjection.ProvenanceValue(t.kind,t.integer,t.doubleBits));return new TypedNoteTimeProjection.Sidecar("transport",shaHex(json),noteId,values);}
    private static void requireUuid(String s,String code){if(s==null||!s.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))throw new IllegalArgumentException(code);}
    private static void requireId(String s,String code){if(s==null||!s.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))throw new IllegalArgumentException(code);}
    private static void requireHash(String s,String code){if(s==null||!s.matches("(?i)[0-9a-f]{64}"))throw new IllegalArgumentException(code);}
    private static byte[] sha(byte[] b){try{return MessageDigest.getInstance("SHA-256").digest(b);}catch(Exception e){throw new AssertionError(e);}}
    private static String shaHex(byte[] b){return hex(sha(b));}
    private static byte[] readN(DataInputStream input,int length)throws IOException{if(length<0)throw new IOException("negative_length");byte[] value=new byte[length];input.readFully(value);return value;}
    private static String hex(byte[]b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
    private static byte[] unhex(String s){byte[]b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16);return b;}
    @SafeVarargs private static <T> Set<T> setOf(T... values) { return new HashSet<>(Arrays.asList(values)); }
}
