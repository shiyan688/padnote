package com.padnote.android;

import org.junit.Test;

import com.padnote.android.streaming.StreamingGroupStore;
import java.nio.file.Files;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

import static org.junit.Assert.*;

/** Focused JVM checks for the isolated native MANUAL_UPDATE_V2 plan factory. */
public final class NativeManualUpdatePlanTest {
    private static final String BODY = "{\"schemaVersion\":8,\"id\":\"source-note\",\"title\":\"synthetic\",\"updatedAt\":16,\"pageWidth\":768.0,\"pageHeight\":1086.0,\"canvasWidth\":768.0,\"canvasHeight\":1086.0,\"pageGap\":24.0,\"pageCount\":1,\"pdfPageCount\":0,\"pageTopologyRevision\":0,\"authorPageEditSerial\":7,\"authorPageTopologySerial\":3,\"viewportZoom\":1.0,\"viewportCenterX\":384.0,\"viewportCenterY\":543.0,\"strokes\":[],\"textFlows\":[],\"images\":[],\"pageStyle\":{\"paper\":\"ruled\",\"ratio\":\"screen\",\"landscape\":false},\"textBoxes\":[]}";


    @Test public void editorRevisionProvenanceRebindsToCapturedParentAndCommittedBody() throws Exception {
        String localId="note-"+repeat("a",32), lineage=UUID.randomUUID().toString();
        String baseRevision=UUID.randomUUID().toString(), newerBase=UUID.randomUUID().toString();
        byte[] body=BODY.replace("source-note",localId).getBytes(StandardCharsets.UTF_8);
        NativeManualUpdatePlan first=editorPlan(localId,lineage,baseRevision,body);
        byte[] firstProvenance=first.durableProvenanceMember();
        assertEditorProvenanceHeader(firstProvenance,localId,lineage,baseRevision,body);
        byte[] updatedBody=BODY.replace("source-note",localId).replace("synthetic","edited")
                .getBytes(StandardCharsets.UTF_8);
        NativeManualUpdatePlan second=editorPlan(localId,lineage,newerBase,updatedBody);
        byte[] secondProvenance=second.durableProvenanceMember();
        assertEditorProvenanceHeader(secondProvenance,localId,lineage,newerBase,updatedBody);
        assertFalse("each manual edit derives a new provenance record from its captured revision/body",
                Arrays.equals(firstProvenance,secondProvenance));
        byte[] caller=second.durableProvenanceMember();caller[0]^=0x7f;
        assertFalse("the plan retains its original provenance bytes",Arrays.equals(caller,second.durableProvenanceMember()));
    }

    private static NativeManualUpdatePlan editorPlan(String localId,String lineage,String revision,byte[] body)throws Exception {
        NativeManualUpdatePlan.SelectedTarget target=new NativeManualUpdatePlan.SelectedTarget(
                localId,lineage,revision,sha("captured-parent-"+revision));
        NativeManualUpdatePlan.Provenance source=new NativeManualUpdatePlan.Provenance(
                NativeManualUpdatePlan.SourceKind.NATIVE_ANDROID,lineage,localId,revision,null);
        return NativeManualUpdatePlan.createManualUpdate(target,source,body,
                NativeManualUpdatePlan.Presence.ABSENT,null,NativeManualUpdatePlan.Presence.ABSENT,null,
                Collections.emptyList(),Collections.emptyList(),Collections.singletonMap(localId,localId));
    }

    private static void assertEditorProvenanceHeader(byte[] provenance,String localId,String lineage,
            String parentRevision,byte[] body)throws Exception {
        byte[] domain="PadNote/UpdateSourceProvenance/v2\0".getBytes(StandardCharsets.US_ASCII);
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(provenance))){
            byte[] actual=new byte[domain.length];in.readFully(actual);assertArrayEquals(domain,actual);
            assertEquals("NATIVE_ANDROID",readText(in));assertEquals(lineage,readText(in));
            assertEquals(localId,readText(in));assertEquals(parentRevision,readText(in));
            assertEquals(sha(body),readText(in));assertEquals(sha(body),readText(in));
        }
    }
    private static String readText(DataInputStream in)throws Exception {
        int length=in.readInt();assertTrue(length>=0&&length<=1<<20);byte[] bytes=new byte[length];in.readFully(bytes);
        return new String(bytes,StandardCharsets.UTF_8);
    }
    private static String hex(byte[] bytes) { StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString(); }

    @Test public void absentPdfCountMeansZeroButPresentMetadataMustBeAnIntegerToken() throws Exception {
        assertEquals(0, NoteStore.pdfPageCountOrZero(new JSONObject()));
        assertEquals(4, NoteStore.pdfPageCountOrZero(new JSONObject().put("pdfPageCount", 4)));
        assertEquals(4, NoteStore.pdfPageCountOrZero(new JSONObject().put("pdfPageCount", 4L)));
        assertEquals("typed binary64 integral values remain valid", 4,
                NoteStore.pdfPageCountOrZero(new JSONObject().put("pdfPageCount", 4.0d)));
        assertThrows(IllegalArgumentException.class,
                () -> NoteStore.pdfPageCountOrZero(new JSONObject().put("pdfPageCount", "4")));
        assertThrows(IllegalArgumentException.class,
                () -> NoteStore.pdfPageCountOrZero(new JSONObject().put("pdfPageCount", 1.5d)));
        assertThrows(IllegalArgumentException.class,
                () -> NoteStore.pdfPageCountOrZero(new JSONObject().put("pdfPageCount", JSONObject.NULL)));
        assertThrows(IllegalArgumentException.class,
                () -> NoteStore.pdfPageCountOrZero(new JSONObject().put("pdfPageCount", 501)));
        assertThrows(IllegalArgumentException.class,
                () -> NoteStore.pdfPageCountOrZero(new JSONObject().put("pdfPageCount", -1)));
    }

    @Test public void absentPdfFieldFlowsThroughManualPlanWithoutBodyInjection() throws Exception {
        String noPdfBody = BODY.replace("\"pdfPageCount\":0,", "");
        NativeManualUpdatePlan.SelectedTarget target = new NativeManualUpdatePlan.SelectedTarget(
                "note-" + UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID().toString(), UUID.randomUUID().toString(), sha("base"));
        NativeManualUpdatePlan.Provenance source = new NativeManualUpdatePlan.Provenance(
                NativeManualUpdatePlan.SourceKind.NATIVE_ANDROID, UUID.randomUUID().toString(), "source-note", "source-r1", null);
        NativeManualUpdatePlan plan = NativeManualUpdatePlan.createManualUpdate(target, source,
                noPdfBody.getBytes(StandardCharsets.UTF_8), NativeManualUpdatePlan.Presence.ABSENT, null,
                NativeManualUpdatePlan.Presence.ABSENT, null, Collections.emptyList(), Collections.emptyList(),
                Collections.singletonMap("source-note", target.localId()));
        String destinationBody = new String(plan.envelope(UUID.randomUUID().toString(), null, null,
                Collections.emptyList(), Collections.emptyList()).bodyBytes(), StandardCharsets.UTF_8);
        assertFalse("plan preserves the missing optional field", destinationBody.contains("pdfPageCount"));

        String withPdf = BODY.replace("\"pdfPageCount\":0", "\"pdfPageCount\":1");
        assertThrows("PDF count must agree with absent PDF member", IllegalArgumentException.class,
                () -> NativeManualUpdatePlan.createManualUpdate(target, source, withPdf.getBytes(StandardCharsets.UTF_8),
                        NativeManualUpdatePlan.Presence.ABSENT, null, NativeManualUpdatePlan.Presence.ABSENT, null,
                        Collections.emptyList(), Collections.emptyList(), Collections.singletonMap("source-note", target.localId())));
        assertThrows("present PDF member cannot accompany a missing/zero count", IllegalArgumentException.class,
                () -> NativeManualUpdatePlan.createManualUpdate(target, source, noPdfBody.getBytes(StandardCharsets.UTF_8),
                        NativeManualUpdatePlan.Presence.PRESENT, new NativeManualUpdatePlan.Facts(1, sha("pdf")),
                        NativeManualUpdatePlan.Presence.ABSENT, null, Collections.emptyList(), Collections.emptyList(),
                        Collections.singletonMap("source-note", target.localId())));
    }

    @Test public void androidLegacyMaterialIdsStayBoundedWhileCanonicalMaterialIdsStayUuid() throws Exception {
        String materialId=UUID.randomUUID().toString(), noteId="note-0123456789abcdef0123456789abcdef";
        String metadata=new JSONObject().put("id",materialId).put("noteId",noteId)
                .put("sourceRevision",1).put("sourceBundleSha256",repeat("11",32))
                .put("taskPayloadSha256",JSONObject.NULL).put("connectionRevision",1)
                .put("kind","HERMES").put("transport","local").put("certSha256",JSONObject.NULL)
                .put("taskId","task-"+UUID.randomUUID()).put("remoteTaskId","remote")
                .put("connectionId","test-connection").put("bridgeId",JSONObject.NULL)
                .put("instanceId",JSONObject.NULL).put("artifactId","artifact")
                .put("name","clip.mp4").put("mediaType","video/mp4").put("sizeBytes",3)
                .put("sha256",sha("mp4")).put("storedName","video-"+materialId+".mp4")
                .put("createdAt",16).put("originKind","computer_task").put("sourceState","linked_note")
                .put("sourceNoteId",noteId).put("sourceRevisionPrecisionMs",1)
                .put("digestKind","source_and_task_payload").put("offlineState","verified_local_copy").toString();
        String lineage=UUID.randomUUID().toString();
        padnote.material.StorageAdapter.Association association=new padnote.material.StorageAdapter.Association(
                materialId,lineage,"linked_note",lineage);
        assertNotNull(padnote.material.StorageAdapter.videoJson(metadata,"android",association,3,sha("mp4")));
        String emptyCertificate=metadata.replace("\"certSha256\":null","\"certSha256\":\"\"");
        assertNotNull("the legacy store's empty optional certificate means absent",padnote.material.StorageAdapter.videoJson(
                emptyCertificate,"android",association,3,sha("mp4")));
        String malformedCertificate=metadata.replace("\"certSha256\":null","\"certSha256\":\"not-a-hash\"");
        assertThrows("nonempty malformed certificate hashes remain rejected",IllegalArgumentException.class,
                ()->padnote.material.StorageAdapter.videoJson(malformedCertificate,"android",association,3,sha("mp4")));
        assertThrows(IllegalArgumentException.class,()->padnote.material.StorageAdapter.videoJson(
                metadata.replace("test-connection","bad connection"),"android",association,3,sha("mp4")));
        assertThrows(IllegalArgumentException.class,()->padnote.material.StorageAdapter.videoJson(
                metadata.replace(materialId,"legacy-material-id"),"android",association,3,sha("mp4")));
    }

    @Test public void androidVaultStorageKeyMapsDeterministicallyToCanonicalMaterialUuid() throws Exception {
        String filename="note-"+repeat("ab",32)+".md";
        String id=padnote.material.StorageAdapter.androidVaultMaterialId(filename);
        assertEquals(id,padnote.material.StorageAdapter.androidVaultMaterialId(filename));
        assertEquals(UUID.fromString(id).toString(),id);
        assertNotEquals(filename,id);
        assertThrows(IllegalArgumentException.class,()->padnote.material.StorageAdapter.androidVaultMaterialId("../"+filename));

        String legacy=VaultStore.buildDigitizedMarkdown("note-fixture","旧格式",1,123L,
                Collections.singletonList("legacy"),456L);
        padnote.material.StorageAdapter.Association association=new padnote.material.StorageAdapter.Association(
                UUID.randomUUID().toString(),null,"independent",null);
        assertNotNull("the original six required headers remain accepted",
                padnote.material.StorageAdapter.androidVaultMarkdown(legacy.getBytes(StandardCharsets.UTF_8),association));
        String operation="00000000-0000-4000-8000-000000000091";
        String digitized=VaultStore.buildDigitizedMarkdown("note-fixture","新格式",1,123L,
                Collections.singletonList("published"),456L,operation);
        padnote.material.StorageAdapter.Projection projection=padnote.material.StorageAdapter.androidVaultMarkdown(
                digitized.getBytes(StandardCharsets.UTF_8),association);
        assertEquals("the recognized optional provenance remains bound by the exact raw hash",
                sha(digitized.getBytes(StandardCharsets.UTF_8)),projection.transportJsonSha256());
        assertThrows("malformed operation provenance is not accepted",IllegalArgumentException.class,
                ()->padnote.material.StorageAdapter.androidVaultMarkdown(
                        digitized.replace(operation,"not-a-uuid").getBytes(StandardCharsets.UTF_8),association));
        assertThrows("duplicate provenance is not accepted",IllegalArgumentException.class,
                ()->padnote.material.StorageAdapter.androidVaultMarkdown(
                        digitized.replace("---\n\n# 新格式","digitization-operation-id: "+operation+"\n---\n\n# 新格式")
                                .getBytes(StandardCharsets.UTF_8),association));
        assertThrows("unknown frontmatter remains rejected",IllegalArgumentException.class,
                ()->padnote.material.StorageAdapter.androidVaultMarkdown(
                        digitized.replace("---\n\n# 新格式","unrecognized-key: value\n---\n\n# 新格式")
                                .getBytes(StandardCharsets.UTF_8),association));
    }

    @Test public void ordinaryCreateSchemaUsesCanvasDefaultsWithoutRewritingSourceBody() throws Exception {
        JSONObject ordinary = new JSONObject().put("schemaVersion", 8).put("id", "source-note")
                .put("title", "Ordinary note").put("updatedAt", 16L)
                .put("canvasWidth", 0).put("canvasHeight", 0).put("pageWidth", 0)
                .put("pageHeight", 0).put("pageGap", 0).put("pageCount", 1)
                .put("strokes", new org.json.JSONArray()).put("textFlows", new org.json.JSONArray())
                .put("textBoxes", new org.json.JSONArray()).put("images", new org.json.JSONArray());
        assertFalse("NoteStore.emptyDocument omits PDF metadata", ordinary.has("pdfPageCount"));
        assertFalse("NoteStore.emptyDocument omits page style", ordinary.has("pageStyle"));
        assertFalse("NoteStore.emptyDocument omits topology revision", ordinary.has("pageTopologyRevision"));
        assertFalse("NoteStore.emptyDocument omits viewport state", ordinary.has("viewportZoom"));
        String raw = ordinary.toString();

        TypedNoteTimeProjection.Projection projection = TypedNoteTimeProjection.nativeAndroid(raw);
        JSONObject explicitCanvasDefault = new JSONObject(raw).put("pageStyle", new JSONObject()
                .put("paper", "ruled").put("ratio", "screen").put("landscape", false));
        assertArrayEquals("absent style projects exactly as the real canvas default",
                projection.bodyBytes, TypedNoteTimeProjection.nativeAndroid(explicitCanvasDefault.toString()).bodyBytes);

        String targetId = "note-" + UUID.randomUUID().toString().replace("-", "");
        NativeManualUpdatePlan.SelectedTarget target = new NativeManualUpdatePlan.SelectedTarget(
                targetId, UUID.randomUUID().toString(), UUID.randomUUID().toString(), sha("base"));
        NativeManualUpdatePlan.Provenance source = new NativeManualUpdatePlan.Provenance(
                NativeManualUpdatePlan.SourceKind.NATIVE_ANDROID, UUID.randomUUID().toString(),
                "source-note", "source-r1", null);
        NativeManualUpdatePlan plan = NativeManualUpdatePlan.createManualUpdate(target, source,
                raw.getBytes(StandardCharsets.UTF_8), NativeManualUpdatePlan.Presence.ABSENT, null,
                NativeManualUpdatePlan.Presence.ABSENT, null, Collections.emptyList(), Collections.emptyList(),
                Collections.singletonMap("source-note", targetId));
        String destinationBody = new String(plan.envelope(UUID.randomUUID().toString(), null, null,
                Collections.emptyList(), Collections.emptyList()).bodyBytes(), StandardCharsets.UTF_8);
        assertEquals("factory preserves body content while applying only the declared ID map",
                raw.replace("source-note", targetId), destinationBody);
        JSONObject output = new JSONObject(destinationBody);
        assertFalse(output.has("pageStyle"));
        assertFalse(output.has("pdfPageCount"));
        assertEquals(0, output.getInt("pageWidth"));
        assertEquals(0, output.getInt("pageHeight"));

        assertThrows("explicit null is not the same as an absent optional style",
                IllegalArgumentException.class, () -> TypedNoteTimeProjection.nativeAndroid(new JSONObject(raw)
                        .put("pageStyle", JSONObject.NULL).toString()));
        assertThrows("negative explicit dimensions remain invalid",
                IllegalArgumentException.class, () -> TypedNoteTimeProjection.nativeAndroid(new JSONObject(raw)
                        .put("pageWidth", -1).toString()));
        assertThrows("string dimensions are not numerically coerced",
                IllegalArgumentException.class, () -> TypedNoteTimeProjection.nativeAndroid(new JSONObject(raw)
                        .put("pageWidth", "0").toString()));
        assertThrows("unknown present style fields remain invalid",
                org.json.JSONException.class, () -> TypedNoteTimeProjection.nativeAndroid(new JSONObject(raw)
                        .put("pageStyle", new JSONObject().put("paper", "ruled")
                                .put("ratio", "screen").put("landscape", false).put("extra", true)).toString()));
    }

    @Test public void shelfRetirementIsStrictSystemMetadataOutsideCanvasProjectionAndPortableBody() throws Exception {
        JSONObject retired = new JSONObject(BODY).put("_padnoteShelfState", "retired")
                .put("_padnoteShelfRetiredAt", 1_797_000_000_123L);
        String retiredJson = retired.toString();
        TypedNoteTimeProjection.Projection canvasProjection = TypedNoteTimeProjection.nativeAndroid(retiredJson);
        TypedNoteTimeProjection.Projection ordinaryProjection = TypedNoteTimeProjection.nativeAndroid(BODY);
        assertEquals("retirement state is recognized in its dedicated system projection",
                "retired", canvasProjection.shelfState);
        assertEquals(1_797_000_000_123L, canvasProjection.shelfRetiredAt);
        assertArrayEquals("ordinary canvas encoding excludes system shelf metadata",
                ordinaryProjection.bodyBytes, canvasProjection.bodyBytes);

        String targetId = "note-" + UUID.randomUUID().toString().replace("-", "");
        NativeManualUpdatePlan.SelectedTarget target = new NativeManualUpdatePlan.SelectedTarget(
                targetId, UUID.randomUUID().toString(), UUID.randomUUID().toString(), sha("base"));
        NativeManualUpdatePlan.Provenance source = new NativeManualUpdatePlan.Provenance(
                NativeManualUpdatePlan.SourceKind.NATIVE_ANDROID, UUID.randomUUID().toString(),
                "source-note", "source-r1", null);
        NativeManualUpdatePlan plan = NativeManualUpdatePlan.createManualUpdate(target, source,
                retiredJson.getBytes(StandardCharsets.UTF_8), NativeManualUpdatePlan.Presence.ABSENT, null,
                NativeManualUpdatePlan.Presence.ABSENT, null, Collections.emptyList(), Collections.emptyList(),
                Collections.singletonMap("source-note", targetId));
        JSONObject portableBody = new JSONObject(new String(plan.envelope(UUID.randomUUID().toString(), null, null,
                Collections.emptyList(), Collections.emptyList()).bodyBytes(), StandardCharsets.UTF_8));
        assertEquals("the group/portable note body retains the exact retirement state",
                "retired", portableBody.getString("_padnoteShelfState"));
        assertEquals("the timestamp remains an exact integer in the stored body",
                1_797_000_000_123L, portableBody.getLong("_padnoteShelfRetiredAt"));
        assertEquals(targetId, portableBody.getString("id"));
        String laterRetirement = new JSONObject(retiredJson)
                .put("_padnoteShelfRetiredAt", 1_797_000_000_124L).toString();
        assertNotEquals("raw JSON authority includes the system timestamp",
                canvasProjection.rawSha256, TypedNoteTimeProjection.nativeAndroid(laterRetirement).rawSha256);
        NativeManualUpdatePlan laterPlan = NativeManualUpdatePlan.createManualUpdate(target, source,
                laterRetirement.getBytes(StandardCharsets.UTF_8), NativeManualUpdatePlan.Presence.ABSENT, null,
                NativeManualUpdatePlan.Presence.ABSENT, null, Collections.emptyList(), Collections.emptyList(),
                Collections.singletonMap("source-note", targetId));
        assertNotEquals("retirement metadata is bound by the exact body hash even though it is outside canvas bytes",
                plan.planDigest(), laterPlan.planDigest());

        assertThrows("system retirement fields must be an exact pair", IllegalArgumentException.class,
                () -> TypedNoteTimeProjection.nativeAndroid(new JSONObject(BODY)
                        .put("_padnoteShelfState", "retired").toString()));
        assertThrows("fractional retirement times are not integer metadata", IllegalArgumentException.class,
                () -> TypedNoteTimeProjection.nativeAndroid(new JSONObject(BODY)
                        .put("_padnoteShelfState", "retired").put("_padnoteShelfRetiredAt", 1.5).toString()));
        assertThrows("unknown canvas fields remain rejected", org.json.JSONException.class,
                () -> TypedNoteTimeProjection.nativeAndroid(retired.put("_padnoteUnexpected", true).toString()));
    }

    @Test public void shelfRetirementSystemMetadataRejectsNullAndInvalidValues() throws Exception {
        assertThrows("both explicit nulls are not the absent legacy pair", IllegalArgumentException.class,
                () -> TypedNoteTimeProjection.nativeAndroid(new JSONObject(BODY)
                        .put("_padnoteShelfState", JSONObject.NULL)
                        .put("_padnoteShelfRetiredAt", JSONObject.NULL).toString()));
        assertThrows("timestamp without its state is invalid", IllegalArgumentException.class,
                () -> TypedNoteTimeProjection.nativeAndroid(new JSONObject(BODY)
                        .put("_padnoteShelfRetiredAt", 123L).toString()));
        assertThrows("state without its timestamp is invalid", IllegalArgumentException.class,
                () -> TypedNoteTimeProjection.nativeAndroid(new JSONObject(BODY)
                        .put("_padnoteShelfState", "retired").toString()));
        assertThrows("null state is invalid", IllegalArgumentException.class,
                () -> TypedNoteTimeProjection.nativeAndroid(new JSONObject(BODY)
                        .put("_padnoteShelfState", JSONObject.NULL).put("_padnoteShelfRetiredAt", 123L).toString()));
        assertThrows("null timestamp is invalid", IllegalArgumentException.class,
                () -> TypedNoteTimeProjection.nativeAndroid(new JSONObject(BODY)
                        .put("_padnoteShelfState", "retired").put("_padnoteShelfRetiredAt", JSONObject.NULL).toString()));
        assertThrows("wrong state type is invalid", IllegalArgumentException.class,
                () -> TypedNoteTimeProjection.nativeAndroid(new JSONObject(BODY)
                        .put("_padnoteShelfState", 1).put("_padnoteShelfRetiredAt", 123L).toString()));
        assertThrows("only the recognized retired state is valid", IllegalArgumentException.class,
                () -> TypedNoteTimeProjection.nativeAndroid(new JSONObject(BODY)
                        .put("_padnoteShelfState", "active").put("_padnoteShelfRetiredAt", 123L).toString()));
        assertThrows("string timestamps are not numerically coerced", IllegalArgumentException.class,
                () -> TypedNoteTimeProjection.nativeAndroid(new JSONObject(BODY)
                        .put("_padnoteShelfState", "retired").put("_padnoteShelfRetiredAt", "123").toString()));
        assertThrows("fractional timestamps are invalid", IllegalArgumentException.class,
                () -> TypedNoteTimeProjection.nativeAndroid(new JSONObject(BODY)
                        .put("_padnoteShelfState", "retired").put("_padnoteShelfRetiredAt", 123.5).toString()));
        assertThrows("zero and negative timestamps are invalid", IllegalArgumentException.class,
                () -> TypedNoteTimeProjection.nativeAndroid(new JSONObject(BODY)
                        .put("_padnoteShelfState", "retired").put("_padnoteShelfRetiredAt", 0L).toString()));
        assertThrows("negative timestamps are invalid", IllegalArgumentException.class,
                () -> TypedNoteTimeProjection.nativeAndroid(new JSONObject(BODY)
                        .put("_padnoteShelfState", "retired").put("_padnoteShelfRetiredAt", -1L).toString()));

        long largeExactTimestamp = 9_007_199_254_740_993L;
        TypedNoteTimeProjection.Projection exact = TypedNoteTimeProjection.nativeAndroid(new JSONObject(BODY)
                .put("_padnoteShelfState", "retired").put("_padnoteShelfRetiredAt", largeExactTimestamp).toString());
        assertEquals("system timestamp remains exact beyond binary64 integer precision",
                largeExactTimestamp, exact.shelfRetiredAt);
    }

    @Test public void explicitDifferentSourceLineageProjectsActualSchema8AndBindsPlan() throws Exception {
        String targetId = "note-" + UUID.randomUUID().toString().replace("-", "");
        NativeManualUpdatePlan.SelectedTarget target = new NativeManualUpdatePlan.SelectedTarget(
                targetId, UUID.randomUUID().toString(), UUID.randomUUID().toString(), sha("current-target"));
        NativeManualUpdatePlan.Provenance source = new NativeManualUpdatePlan.Provenance(
                NativeManualUpdatePlan.SourceKind.NATIVE_ANDROID, UUID.randomUUID().toString(),
                "source-note", "source-r1", null);
        NativeManualUpdatePlan plan = NativeManualUpdatePlan.createManualUpdate(target, source,
                BODY.getBytes(StandardCharsets.UTF_8), NativeManualUpdatePlan.Presence.ABSENT, null,
                NativeManualUpdatePlan.Presence.ABSENT, null, Collections.emptyList(),
                Collections.emptyList(), Collections.singletonMap("source-note", targetId));

        assertNotEquals("manual selection permits a declared source lineage different from target",
                plan.sourceLineage(), plan.targetLineage());
        assertEquals(targetId, plan.sourceToDestinationIds().get("source-note"));
        assertTrue(plan.planDigest().matches("[0-9a-f]{64}"));
        assertArrayEquals(BODY.replace("source-note", targetId).getBytes(StandardCharsets.UTF_8),
                plan.envelope(UUID.randomUUID().toString(), null, null,
                        Collections.emptyList(), Collections.emptyList()).bodyBytes());
    }

    @Test public void canvasPageRevisionCountersAreValidatedAndPreservedInBody() throws Exception {
        String targetId="note-"+UUID.randomUUID().toString().replace("-","");
        NativeManualUpdatePlan.SelectedTarget target=new NativeManualUpdatePlan.SelectedTarget(
                targetId,UUID.randomUUID().toString(),UUID.randomUUID().toString(),sha("base"));
        NativeManualUpdatePlan.Provenance source=new NativeManualUpdatePlan.Provenance(
                NativeManualUpdatePlan.SourceKind.NATIVE_ANDROID,UUID.randomUUID().toString(),
                "source-note","source-r1",null);
        NativeManualUpdatePlan plan=NativeManualUpdatePlan.createManualUpdate(target,source,
                BODY.getBytes(StandardCharsets.UTF_8),NativeManualUpdatePlan.Presence.ABSENT,null,
                NativeManualUpdatePlan.Presence.ABSENT,null,Collections.emptyList(),Collections.emptyList(),
                Collections.singletonMap("source-note",targetId));
        JSONObject saved=new JSONObject(new String(plan.envelope(UUID.randomUUID().toString(),null,null,
                Collections.emptyList(),Collections.emptyList()).bodyBytes(),StandardCharsets.UTF_8));
        assertEquals("page edit serial survives the exact JSON body",7,saved.getLong("authorPageEditSerial"));
        assertEquals("page topology serial survives the exact JSON body",3,saved.getLong("authorPageTopologySerial"));

        assertThrows("negative page edit serial remains invalid",IllegalArgumentException.class,
                ()->TypedNoteTimeProjection.nativeAndroid(BODY.replace("\"authorPageEditSerial\":7",
                        "\"authorPageEditSerial\":-1")));
        assertThrows("fractional page topology serial remains invalid",IllegalArgumentException.class,
                ()->TypedNoteTimeProjection.nativeAndroid(BODY.replace("\"authorPageTopologySerial\":3",
                        "\"authorPageTopologySerial\":3.5")));
        assertThrows("page edit serial stays within the canvas reader's bound",IllegalArgumentException.class,
                ()->TypedNoteTimeProjection.nativeAndroid(BODY.replace("\"authorPageEditSerial\":7",
                        "\"authorPageEditSerial\":9223372036854775807")));
    }

    @Test public void canvasViewportScaleIsFiniteViewMetadataAndNotAuthorContent() throws Exception {
        String finiteScale = BODY.replace("\"viewportZoom\":1.0",
                "\"viewportScale\":1000.0,\"viewportZoom\":1.0");
        TypedNoteTimeProjection.Projection withoutScale = TypedNoteTimeProjection.nativeAndroid(BODY);
        TypedNoteTimeProjection.Projection withScale = TypedNoteTimeProjection.nativeAndroid(finiteScale);
        assertEquals("raw document digest binds the exact canvas JSON",sha(finiteScale),withScale.rawSha256);
        assertArrayEquals("viewport state stays outside the author-content projection",
                withoutScale.bodyBytes,withScale.bodyBytes);

        String nonnumeric = BODY.replace("\"viewportZoom\":1.0",
                "\"viewportScale\":\"1.5\",\"viewportZoom\":1.0");
        assertThrows("canvas viewport scale must be numeric",IllegalArgumentException.class,
                ()->TypedNoteTimeProjection.nativeAndroid(nonnumeric));
        String nonfinite = BODY.replace("\"viewportZoom\":1.0",
                "\"viewportScale\":1e400,\"viewportZoom\":1.0");
        assertThrows("canvas viewport scale must be finite",IllegalArgumentException.class,
                ()->TypedNoteTimeProjection.nativeAndroid(nonfinite));
    }

    @Test public void nonemptyOptionalComponentsRemainInThePlanForTheConcreteStoreGate() throws Exception {
        String withPdf = BODY.replace("\"pdfPageCount\":0", "\"pdfPageCount\":1");
        NativeManualUpdatePlan.SelectedTarget target = new NativeManualUpdatePlan.SelectedTarget(
                "note-" + UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID().toString(), UUID.randomUUID().toString(), sha("base"));
        NativeManualUpdatePlan.Provenance source = new NativeManualUpdatePlan.Provenance(
                NativeManualUpdatePlan.SourceKind.NATIVE_ANDROID, UUID.randomUUID().toString(), "source-note", "source-r1", null);
        NativeManualUpdatePlan pdfPlan = NativeManualUpdatePlan.createManualUpdate(target, source,
                withPdf.getBytes(StandardCharsets.UTF_8), NativeManualUpdatePlan.Presence.PRESENT,
                new NativeManualUpdatePlan.Facts(12, sha("pdf")), NativeManualUpdatePlan.Presence.ABSENT, null,
                Collections.emptyList(), Collections.emptyList(), Collections.singletonMap("source-note", target.localId()));
        assertNotNull(pdfPlan);
        NativeManualUpdatePlan coverPlan = NativeManualUpdatePlan.createManualUpdate(target, source,
                BODY.getBytes(StandardCharsets.UTF_8), NativeManualUpdatePlan.Presence.ABSENT, null,
                NativeManualUpdatePlan.Presence.PRESENT, new NativeManualUpdatePlan.Facts(8, sha("cover")),
                Collections.emptyList(), Collections.emptyList(), Collections.singletonMap("source-note", target.localId()));
        assertNotNull(coverPlan);
        assertTrue("MANUAL_UPDATE_V2 uses the final concrete verifier", NativeManualUpdatePlan.durableVerifier()
                instanceof NativeManualUpdatePlan.ConcreteCompleteGroupVerifier);
    }

    @Test public void invalidTypedIdentityAndIncompleteMappingAreRejected() {
        NativeManualUpdatePlan.SelectedTarget target = new NativeManualUpdatePlan.SelectedTarget(
                "note-" + UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID().toString(), UUID.randomUUID().toString(), sha("base"));
        NativeManualUpdatePlan.Provenance source = new NativeManualUpdatePlan.Provenance(
                NativeManualUpdatePlan.SourceKind.NATIVE_ANDROID, UUID.randomUUID().toString(),
                "source-note", "source-r1", null);
        assertThrows(IllegalArgumentException.class, () -> NativeManualUpdatePlan.createManualUpdate(target, source,
                BODY.getBytes(StandardCharsets.UTF_8), NativeManualUpdatePlan.Presence.ABSENT, null,
                NativeManualUpdatePlan.Presence.ABSENT, null, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyMap()));
        NativeManualUpdatePlan.Provenance missingSourceId = new NativeManualUpdatePlan.Provenance(
                NativeManualUpdatePlan.SourceKind.NATIVE_ANDROID, source.sourceLineage(), "other-note", "source-r1", null);
        assertThrows(IllegalArgumentException.class, () -> NativeManualUpdatePlan.createManualUpdate(target, missingSourceId,
                BODY.getBytes(StandardCharsets.UTF_8), NativeManualUpdatePlan.Presence.ABSENT, null,
                NativeManualUpdatePlan.Presence.ABSENT, null, Collections.emptyList(), Collections.emptyList(),
                Collections.singletonMap("source-note", target.localId())));
    }

    @Test public void durableMaterialAssociationStatesHaveDistinctOwnerRules() {
        String lineage = UUID.randomUUID().toString();
        assertTrue(NativeManualUpdatePlan.validSourceAssociation("linked_note", lineage, lineage, "source-note", lineage, "source-note"));
        assertFalse(NativeManualUpdatePlan.validSourceAssociation("linked_note", lineage, lineage, null, lineage, "source-note"));
        assertFalse(NativeManualUpdatePlan.validSourceAssociation("linked_note", lineage, lineage, "other-note", lineage, "source-note"));

        assertTrue(NativeManualUpdatePlan.validSourceAssociation("source_deleted", null, lineage, null, lineage, "source-note"));
        assertTrue(NativeManualUpdatePlan.validSourceAssociation("source_deleted", lineage, lineage, null, lineage, "source-note"));
        assertFalse(NativeManualUpdatePlan.validSourceAssociation("source_deleted", null, null, null, lineage, "source-note"));
        assertTrue(NativeManualUpdatePlan.validSourceAssociation("source_not_selected", lineage, lineage, null, lineage, "source-note"));
        assertFalse(NativeManualUpdatePlan.validSourceAssociation("source_not_selected", lineage, lineage, "source-note", lineage, "source-note"));

        assertTrue(NativeManualUpdatePlan.validSourceAssociation("independent", null, null, null, lineage, "source-note"));
        assertFalse(NativeManualUpdatePlan.validSourceAssociation("independent", null, lineage, null, lineage, "source-note"));
        assertFalse(NativeManualUpdatePlan.validSourceAssociation("unknown", null, null, null, lineage, "source-note"));
    }

    @Test public void nonemptyPlanFactoryUsesExactStreamingStoreRoleWireNames() throws Exception {
        String targetId="note-"+UUID.randomUUID().toString().replace("-","");
        String targetLineage=UUID.randomUUID().toString(),sourceLineage=UUID.randomUUID().toString();
        String sourceVideo=UUID.randomUUID().toString(),targetVideo=UUID.randomUUID().toString();
        String sourceVault=UUID.randomUUID().toString(),targetVault=UUID.randomUUID().toString();
        byte[] videoBytes="synthetic-video-content".getBytes(StandardCharsets.UTF_8);
        String sourceVaultText="---\ntitle: V\nnote-id: source-note\npages: 1\ndigitized: 100\ndigitized-epoch: 100\nsource-modified: 90\n---\nbody\n";
        byte[] vaultBytes=sourceVaultText.getBytes(StandardCharsets.UTF_8);
        byte[] destinationVault=sourceVaultText.replace("source-note",targetId).getBytes(StandardCharsets.UTF_8);
        Path owned=Files.createTempDirectory("manual-role-wire-");
        try {
            Path videoPath=owned.resolve("video.bin"),vaultPath=owned.resolve("vault.md");
            Files.write(videoPath,videoBytes);Files.write(vaultPath,destinationVault);
            JSONObject metadata=new JSONObject().put("id",sourceVideo).put("noteId","source-note")
                    .put("sourceRevision",1).put("sourceBundleSha256",repeat("11",32))
                    .put("taskPayloadSha256",JSONObject.NULL).put("connectionRevision",1)
                    .put("kind","HERMES").put("transport","local").put("certSha256",JSONObject.NULL)
                    .put("taskId",UUID.randomUUID().toString()).put("remoteTaskId","remote")
                    .put("connectionId",UUID.randomUUID().toString()).put("bridgeId",JSONObject.NULL)
                    .put("instanceId",JSONObject.NULL).put("artifactId","artifact")
                    .put("name","clip.mp4").put("mediaType","video/mp4").put("sizeBytes",videoBytes.length)
                    .put("sha256",sha(videoBytes)).put("storedName","video-"+sourceVideo+".mp4")
                    .put("createdAt",16).put("originKind","computer_task").put("sourceState","linked_note")
                    .put("sourceNoteId","source-note").put("sourceRevisionPrecisionMs",1)
                    .put("digestKind","source_and_task_payload").put("offlineState","verified_local_copy");
            padnote.material.StorageAdapter.Association videoAssociation=new padnote.material.StorageAdapter.Association(
                    sourceVideo,sourceLineage,"linked_note",sourceLineage);
            NativeManualUpdatePlan.Material video=new NativeManualUpdatePlan.Material(NativeManualUpdatePlan.Role.VIDEO,
                    sourceVideo,targetVideo,"video/mp4","android",metadata.toString(),videoAssociation,
                    new NativeManualUpdatePlan.Facts(videoBytes.length,sha(videoBytes)));
            padnote.material.StorageAdapter.Association vaultAssociation=new padnote.material.StorageAdapter.Association(
                    sourceVault,sourceLineage,"linked_note",sourceLineage);
            NativeManualUpdatePlan.Material vault=new NativeManualUpdatePlan.Material(NativeManualUpdatePlan.Role.VAULT,
                    sourceVault,targetVault,"text/markdown","android",sourceVaultText,vaultAssociation,
                    new NativeManualUpdatePlan.Facts(vaultBytes.length,sha(vaultBytes)));
            Map<String,String> mapping=new LinkedHashMap<>();mapping.put("source-note",targetId);
            mapping.put(sourceVideo,targetVideo);mapping.put(sourceVault,targetVault);
            NativeManualUpdatePlan plan=NativeManualUpdatePlan.createManualUpdate(
                    new NativeManualUpdatePlan.SelectedTarget(targetId,targetLineage,UUID.randomUUID().toString(),sha("base")),
                    new NativeManualUpdatePlan.Provenance(NativeManualUpdatePlan.SourceKind.NATIVE_ANDROID,
                            sourceLineage,"source-note","source-r1",null),BODY.getBytes(StandardCharsets.UTF_8),
                    NativeManualUpdatePlan.Presence.ABSENT,null,NativeManualUpdatePlan.Presence.ABSENT,null,
                    Collections.singletonList(video),Collections.singletonList(vault),mapping);
            byte[] exposedDescriptor=plan.previewMaterialDescriptor(sourceVideo);
            assertTrue("projected descriptor is nonempty",exposedDescriptor.length>0);
            byte[] originalDescriptor=exposedDescriptor.clone();
            exposedDescriptor[0]^=0x01;
            assertArrayEquals("mutating preview bytes cannot alter the immutable plan",originalDescriptor,
                    plan.previewMaterialDescriptor(sourceVideo));
            StreamingGroupStore.MemberInput videoInput=plan.videoMemberInput(sourceVideo,
                    new StreamingGroupStore.ContentInput(videoPath,videoBytes.length,sha(videoBytes),null));
            StreamingGroupStore.MemberInput vaultInput=plan.vaultMemberInput(sourceVault,
                    new StreamingGroupStore.ContentInput(vaultPath,destinationVault.length,sha(destinationVault),null));
            assertEquals("stageList stores the exact lowercase video wire role","video",videoInput.role);
            assertEquals("stageList stores the exact lowercase vault wire role","vault",vaultInput.role);
            StreamingGroupStore.Envelope envelope=plan.envelope(UUID.randomUUID().toString(),null,null,
                    Collections.singletonList(videoInput),Collections.singletonList(vaultInput));
            assertTrue("the nonempty real material envelope remains authorized",plan.authorizes(envelope));
            StreamingGroupStore.MemberInput wrongCase=new StreamingGroupStore.MemberInput("VIDEO",videoInput.materialId,
                    videoInput.mediaType,videoInput.metadata(),videoInput.content);
            assertThrows("uppercase codec enum spelling is not the stageList wire contract",IllegalArgumentException.class,
                    ()->plan.envelope(UUID.randomUUID().toString(),null,null,Collections.singletonList(wrongCase),
                            Collections.singletonList(vaultInput)));
        } finally {
            Files.deleteIfExists(owned.resolve("video.bin"));Files.deleteIfExists(owned.resolve("vault.md"));Files.deleteIfExists(owned);
        }
    }

    private static String repeat(String value,int count){StringBuilder out=new StringBuilder();for(int i=0;i<count;i++)out.append(value);return out.toString();}

    private static String sha(byte[] value) {
        try {
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder out=new StringBuilder();
            for(byte b:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            return out.toString();
        } catch(Exception error) { throw new AssertionError(error); }
    }

    private static String sha(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception error) { throw new AssertionError(error); }
    }
}
