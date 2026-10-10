package com.padnote.android;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public final class ArchiveUpdateProfileVaultIdentityTest {
    @Test public void archiveVaultProjectionIsDeterministicAndBoundToProfileRowAndBytes() throws Exception {
        ArchiveUpdateProfile profile=profile("a-11111111111111111111111111111111","source-note");
        LibraryBackupManifest.VaultEntry row=row("v-22222222222222222222222222222222","a-11111111111111111111111111111111","source-note","r-33333333333333333333333333333333");
        LibraryBackupManifest.Resource storage=storage("r-33333333333333333333333333333333","a".repeat(64));
        String first=ArchiveUpdateProfile.vaultSourceMaterialId(profile,row,storage);
        assertTrue(first.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        assertEquals(first,ArchiveUpdateProfile.vaultSourceMaterialId(profile,row,storage));
        assertNotEquals("archive row IDs are not storage UUIDs",row.itemId,first);
        assertNotEquals("different archive rows cannot alias",first,ArchiveUpdateProfile.vaultSourceMaterialId(profile,
                row("v-44444444444444444444444444444444",row.noteItemId,row.sourceNoteId,row.sourceStorageResourceId),storage));
        assertNotEquals("different source bytes produce a different projected identity",first,
                ArchiveUpdateProfile.vaultSourceMaterialId(profile,row,storage("r-33333333333333333333333333333333","b".repeat(64))));
        assertNotEquals("different profile revision produces a different projected identity",first,
                ArchiveUpdateProfile.vaultSourceMaterialId(profile("a-11111111111111111111111111111111","source-note","33333333-3333-4333-8333-333333333333"),row,storage));
    }

    @Test public void projectionRejectsResourceAndProfileCrossWiring() throws Exception {
        ArchiveUpdateProfile profile=profile("a-11111111111111111111111111111111","source-note");
        LibraryBackupManifest.VaultEntry row=row("v-22222222222222222222222222222222","a-11111111111111111111111111111111","source-note","r-33333333333333333333333333333333");
        try {
            ArchiveUpdateProfile.vaultSourceMaterialId(profile,row,storage("r-66666666666666666666666666666666","a".repeat(64)));
            fail("different resource identity must be rejected");
        } catch(java.io.IOException expected) { assertEquals("VAULT_STORAGE_IDENTITY_INPUT_INVALID",expected.getMessage()); }
        try {
            ArchiveUpdateProfile.vaultSourceMaterialId(profile,row("v-22222222222222222222222222222222","a-77777777777777777777777777777777","source-note","r-33333333333333333333333333333333"),storage("r-33333333333333333333333333333333","a".repeat(64)));
            fail("different profile note scope must be rejected");
        } catch(java.io.IOException expected) { assertEquals("VAULT_STORAGE_PROFILE_BINDING_MISMATCH",expected.getMessage()); }
    }

    @Test public void profileV1RemainsCopyOnlyWhileItsVaultBytesCanStillBeValidated() throws Exception {
        LibraryBackupManifest.VaultEntry row=row("v-22222222222222222222222222222222",
                "a-11111111111111111111111111111111","source-note","r-33333333333333333333333333333333");
        LibraryBackupManifest.Resource storage=storage("r-33333333333333333333333333333333","a".repeat(64));
        ArchiveUpdateProfile legacy=new ArchiveUpdateProfile(1,row.noteItemId,row.sourceNoteId,
                "11111111-1111-4111-8111-111111111111","22222222-2222-4222-8222-222222222222",
                "0".repeat(64),"1".repeat(64),java.util.List.of());
        try {
            ArchiveUpdateProfile.vaultSourceMaterialId(legacy,row,storage);
            fail("profile v1 cannot supply an update storage identity");
        } catch(java.io.IOException expected) { assertEquals("UPDATE_VAULT_STORAGE_PROFILE_REQUIRED_COPY_ONLY",expected.getMessage()); }
        assertTrue("legacy staging uses a scoped validation identity only",
                ArchiveUpdateProfile.vaultCopyValidationMaterialId(row,storage).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
    }

    @Test public void schemaTwoGroupDigestBindsExactVaultStorageResource() throws Exception {
        String noteId="i-11111111111111111111111111111111";
        LibraryBackupManifest.Note note=new LibraryBackupManifest.Note(noteId,"source-note",1700000000000L,8,
                "r-11111111111111111111111111111111",null,null,
                java.util.List.of("v-22222222222222222222222222222222"),java.util.List.of());
        LibraryBackupManifest.VaultEntry vault=row("v-22222222222222222222222222222222",noteId,"source-note",
                "r-33333333333333333333333333333333");
        java.util.Map<String,LibraryBackupManifest.Resource> resources=new java.util.HashMap<>();
        resources.put("r-11111111111111111111111111111111",new LibraryBackupManifest.Resource(
                "r-11111111111111111111111111111111","note_document","application/json",2,"1".repeat(64),
                "payload/r-11111111111111111111111111111111.bin"));
        resources.put(vault.resourceId,new LibraryBackupManifest.Resource(vault.resourceId,"vault_entry_json",
                "application/json",2,"2".repeat(64),"payload/"+vault.resourceId+".bin"));
        LibraryBackupManifest.Resource storage=storage(vault.sourceStorageResourceId,"a".repeat(64));
        resources.put(storage.resourceId,storage);
        String oldProfileDigest=ArchiveUpdateProfile.groupDigest(note,java.util.List.of(vault),java.util.List.of(),resources,1);
        assertEquals("the historical overload retains the schema-one profile contract",oldProfileDigest,
                ArchiveUpdateProfile.groupDigest(note,java.util.List.of(vault),java.util.List.of(),resources));
        String currentProfileDigest=ArchiveUpdateProfile.groupDigest(note,java.util.List.of(vault),java.util.List.of(),resources,2);
        assertNotEquals("v2 digest binds the storage-identity projection rule",oldProfileDigest,currentProfileDigest);
        resources.put(storage.resourceId,storage(storage.resourceId,"b".repeat(64)));
        assertNotEquals("v2 digest binds exact raw Vault storage bytes",currentProfileDigest,
                ArchiveUpdateProfile.groupDigest(note,java.util.List.of(vault),java.util.List.of(),resources,2));
        String oldProfileDigestWithChangedStorage=ArchiveUpdateProfile.groupDigest(note,java.util.List.of(vault),java.util.List.of(),resources,1);
        assertNotEquals("historical v1 digest already bound the storage resource hash",oldProfileDigest,oldProfileDigestWithChangedStorage);
        assertEquals("the historical overload retains schema-one handling after resources change",oldProfileDigestWithChangedStorage,
                ArchiveUpdateProfile.groupDigest(note,java.util.List.of(vault),java.util.List.of(),resources));
    }

    @Test public void videoArchiveSurrogateMapsToItsExistingCanonicalMetadataIdentity() {
        ArchiveUpdateProfile profile=profile("a-11111111111111111111111111111111","source-note");
        LibraryBackupManifest.VideoAttachment video=video("a-22222222222222222222222222222222");
        String projected=ArchiveManualUpdateAdapter.videoSourceMaterialId(video,profile);
        String existingMetadataId=java.util.UUID.nameUUIDFromBytes(("PadNote/archive-material/v2\0"
                +profile.sourceLineageId+"\0"+video.itemId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        assertEquals(existingMetadataId,projected);
        assertNotEquals(video.itemId,projected);
    }

    @Test public void projectedArchiveVideoMetadataPassesTheRealAndroidStorageValidator() throws Exception {
        ArchiveUpdateProfile profile=profile("a-11111111111111111111111111111111","source-note");
        LibraryBackupManifest.VideoAttachment video=video("a-22222222222222222222222222222222");
        byte[] bytes="synthetic mp4 bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha=hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        JSONObject row=new JSONObject().put("item_id",video.itemId).put("origin_kind","restored_archive")
                .put("connection_provenance",new JSONObject().put("connection_id","11111111-1111-4111-8111-111111111111")
                        .put("kind","HERMES").put("connection_revision",1).put("transport","BRIDGE")
                        .put("certificate_sha256",JSONObject.NULL).put("bridge_id",JSONObject.NULL)
                        .put("instance_id",JSONObject.NULL))
                .put("task_id","22222222-2222-4222-8222-222222222222")
                .put("remote_task_id","33333333-3333-4333-8333-333333333333")
                .put("artifact_id","44444444-4444-4444-8444-444444444444")
                .put("source_revision_ms",1700000000000L).put("source_bundle_sha256","a".repeat(64))
                .put("task_payload_sha256",JSONObject.NULL).put("display_name","fixture.mp4")
                .put("media_type","video/mp4").put("byte_length",bytes.length).put("sha256",sha)
                .put("created_at_ms",1700000000001L).put("source_state","linked_note")
                .put("source_note_id","source-note").put("source_revision_precision_ms",1)
                .put("digest_kind","source_and_task_payload").put("offline_state","verified_local_copy");
        String raw=ArchiveManualUpdateAdapter.videoMetadata(row,"source-note",profile.sourceLineageId);
        JSONObject metadata=new JSONObject(raw);
        String projected=ArchiveManualUpdateAdapter.videoSourceMaterialId(video,profile);
        assertEquals(projected,metadata.getString("id"));
        assertEquals("video-"+projected+".mp4",metadata.getString("storedName"));
        padnote.material.StorageAdapter.Association association=new padnote.material.StorageAdapter.Association(
                projected,profile.sourceLineageId,"linked_note",profile.sourceLineageId);
        padnote.material.StorageAdapter.Projection validated=padnote.material.StorageAdapter.videoJson(raw,"android",association,bytes);
        assertEquals("video",validated.kind());assertTrue(validated.descriptor().length>0);
    }

    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b));return out.toString();}

    private static ArchiveUpdateProfile profile(String note,String source){return profile(note,source,"22222222-2222-4222-8222-222222222222");}
    private static ArchiveUpdateProfile profile(String note,String source,String revision){return new ArchiveUpdateProfile(note,source,
            "11111111-1111-4111-8111-111111111111",revision,"0".repeat(64),"1".repeat(64),java.util.List.of());}
    private static LibraryBackupManifest.VaultEntry row(String id,String note,String source,String resource){return new LibraryBackupManifest.VaultEntry(
            id,note,"linked_note",source,1700000000000L,1690000000000L,"r-88888888888888888888888888888888",resource);}
    private static LibraryBackupManifest.Resource storage(String id,String sha){return new LibraryBackupManifest.Resource(id,
            "vault_storage_markdown","text/markdown",1,sha,"resources/"+id+".bin");}
    private static LibraryBackupManifest.VideoAttachment video(String id){return new LibraryBackupManifest.VideoAttachment(
            id,"i-11111111111111111111111111111111","linked_note","computer_task","source-note",1700000000000L,
            1,"a".repeat(64),"b".repeat(64),"sha256","verified_local_copy","task-1","remote-1",null,
            "artifact-1","fixture","video/mp4",1,"c".repeat(64),1690000000000L,
            "r-77777777777777777777777777777777");}
}
