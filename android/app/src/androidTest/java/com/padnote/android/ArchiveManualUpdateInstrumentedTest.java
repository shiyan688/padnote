package com.padnote.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.util.Base64;
import android.system.Os;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.padnote.android.streaming.StreamingGroupStore;
import padnote.material.StorageAdapter;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Candidate-only archive-v2 -> explicit full-group update coverage; no product UI route is wired. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 27)
public final class ArchiveManualUpdateInstrumentedTest {
    @Test public void applicationValidFixtureReexportsAsTypedV2WhileV1StaysCopyOnly() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root=new File(target.getCacheDir(),"archive-v2-fixture-"+UUID.randomUUID());assertTrue(root.mkdir());
        Context context=new FixtureContext(target,root);File stageParent=new File(context.getFilesDir(),"stage");assertTrue(stageParent.mkdirs());
        File input=new File(root,"application-valid-v1.zip"),output=new File(root,"application-valid-v2.zip");
        try {
            try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("library-backup-r1/valid-library.zip");FileOutputStream out=new FileOutputStream(input)){
                byte[] b=new byte[16384];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);out.flush();out.getFD().sync();
            }
            Map<String,String> ids;
            try(LibraryBackupArchive.StagedArchive source=LibraryBackupArchive.stage(input,stageParent,null,null)){
                assertEquals(1,source.manifest().formatVersion);
                assertEquals("ios",source.manifest().producer.platform);
                assertEquals(2,source.manifest().notes.size());
                assertTrue(source.manifest().resources.stream().anyMatch(r->"pdf_original".equals(r.role)));
                assertTrue(source.manifest().resources.stream().anyMatch(r->"assigned_cover_png".equals(r.role)));
                assertTrue(source.manifest().resources.stream().anyMatch(r->"video_attachment_mp4".equals(r.role)&&r.byteLength>0));
                assertTrue(source.manifest().resources.stream().anyMatch(r->"vault_entry_json".equals(r.role)));
                for(LibraryBackupManifest.Note note:source.manifest().notes){
                    JSONObject document=new JSONObject(new String(Files.readAllBytes(source.resourceFile(note.noteResourceId).toPath()),StandardCharsets.UTF_8));
                    assertTrue("fixture preserves real handwriting rows",document.getJSONArray("strokes").length()>0);
                    assertTrue("fixture preserves real text-flow content",document.getJSONArray("textFlows").length()>0);
                }
                ids=new LibraryRestoreTransaction(context,source).restoreNotes(null,null);
            }
            assertEquals(2,ids.size());
            try(LibraryBackupSnapshot snapshot=LibraryBackupSnapshot.createForManualUpdate(context,new HashSet<>(ids.values()),null,null)){
                assertEquals(2,snapshot.manifest.formatVersion);
                assertEquals(2,snapshot.manifest.updateProfiles.size());
                assertEquals("android",snapshot.manifest.producer.platform);
                snapshot.write(output,null,null);
            }
            try(LibraryBackupArchive.StagedArchive roundtrip=LibraryBackupArchive.stage(output,stageParent,null,null)){
                assertEquals(2,roundtrip.manifest().formatVersion);
                assertEquals("android",roundtrip.manifest().producer.platform);
                assertEquals(2,roundtrip.manifest().updateProfiles.size());
                assertTrue(roundtrip.manifest().resources.stream().anyMatch(r->"pdf_original".equals(r.role)));
                assertTrue(roundtrip.manifest().resources.stream().anyMatch(r->"assigned_cover_png".equals(r.role)));
                assertTrue(roundtrip.manifest().resources.stream().anyMatch(r->"video_attachment_mp4".equals(r.role)&&r.byteLength>0));
                assertTrue(roundtrip.manifest().resources.stream().anyMatch(r->"vault_entry_json".equals(r.role)));
                assertProfileRejectsUnsafeVariants(roundtrip.manifest());
            }
            try(LibraryBackupArchive.StagedArchive legacy=LibraryBackupArchive.stage(input,stageParent,null,null)){
                NoteGroupFacade facade=new NoteGroupFacade(context);
                LibraryBackupManifest.Note note=legacy.manifest().notes.get(0);
                try {
                    facade.updateFromArchive(legacy,note.itemId,"note-never-selected",Collections.emptyMap(),(a,b)->true);
                    fail("archive v1 has no update authority and must remain copy-only");
                } catch(java.io.IOException expected) { assertEquals("UPDATE_PROFILE_REQUIRED_COPY_ONLY",expected.getMessage()); }
            }
        } finally { deleteTree(root); }
    }

    @Test public void completeArchiveUpdateConflictsDuringPreviewAndRestoresWholeOldRevision() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root=new File(target.getCacheDir(),"archive-v2-update-"+UUID.randomUUID());assertTrue(root.mkdir());
        Context context=new FixtureContext(target,root);File archiveStage=new File(context.getFilesDir(),"stage");assertTrue(archiveStage.mkdirs());
        File incoming=new File(root,"incoming.padnote-library.zip"),winner=new File(root,"winner.padnote-library.zip");
        String localId=null;
        try {
            NoteStore.Entry entry=NoteStore.create(context,"incoming full group");localId=entry.id;
            assertTrue("canonical virtual Vault locator parses the UUID suffix",
                    GroupAuthorityBridge.isVirtualVaultName(GroupAuthorityBridge.virtualVaultName(entry.id,"9f6c2f11-428e-4a88-9d44-6d8ac77fd220")));
            assertFalse("virtual Vault locator rejects a noncanonical UUID",GroupAuthorityBridge.isVirtualVaultName("group-vault-bm90ZS0x-9F6c2f11-428e-4a88-9d44-6d8ac77fd220.md"));
            assertFalse("virtual Vault locator rejects malformed Base64URL",GroupAuthorityBridge.isVirtualVaultName("group-vault-%%%-9f6c2f11-428e-4a88-9d44-6d8ac77fd220.md"));
            assertFalse("virtual Vault locator rejects a missing separator",GroupAuthorityBridge.isVirtualVaultName("group-vault-bm90ZS0x9f6c2f11-428e-4a88-9d44-6d8ac77fd220.md"));
            JSONObject body=NoteStore.load(context,entry.id);body.put("title","incoming full group");body.put("pageCount",2);body.put("pdfPageCount",1);
            JSONArray strokes=body.getJSONArray("strokes");strokes.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("color","#FF123456")
                    .put("baseWidth",2.25).put("createdAt",1700000000001L).put("highlighter",false)
                    .put("points",new JSONArray().put(new JSONObject().put("x",12.5).put("y",33.25).put("pressure",0.5).put("timestamp",1700000000002L))));
            NoteStore.save(context,entry.id,"incoming full group",NoteJsonCodec.stringify(body));
            File pdf=NoteStore.pdfFile(context,entry.id);writePdf(pdf);
            Bitmap cover=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);cover.eraseColor(Color.CYAN);CoverStore.assign(context,entry.id,cover);cover.recycle();
            File sourceVideo=new File(context.getFilesDir(),"input.mp4");
            try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("video/roundtrip.mp4");FileOutputStream out=new FileOutputStream(sourceVideo)){
                byte[] b=new byte[16384];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);out.flush();out.getFD().sync();
            }
            byte[] videoBytes=Files.readAllBytes(sourceVideo.toPath());String videoSha=sha(videoBytes);
            new VideoAttachmentStore(context).attach(entry.id,entry.updatedAt,videoSha,videoSha,
                    "task-"+UUID.randomUUID(),"remote-"+UUID.randomUUID(),"connection-"+UUID.randomUUID(),1,
                    "HERMES","BRIDGE","bridge-"+UUID.randomUUID(),"instance-"+UUID.randomUUID(),sha("fixture-certificate".getBytes(StandardCharsets.UTF_8)),
                    "artifact-"+UUID.randomUUID(),"Update fixture","video/mp4",videoBytes.length,videoSha,sourceVideo);
            new VaultStore(context).write(entry.id,"Update fixture Vault",1,entry.updatedAt,Collections.singletonList("source markdown"));
            writeSnapshot(context,entry.id,incoming);

            JSONObject competing=NoteStore.load(context,entry.id);competing.put("title","preview winner");NoteStore.save(context,entry.id,"preview winner",competing.toString());
            writeSnapshot(context,entry.id,winner);
            JSONObject baseline=NoteStore.load(context,entry.id);baseline.put("title","old complete target");NoteStore.save(context,entry.id,"old complete target",baseline.toString());
            JSONObject baselineStored=NoteStore.load(context,entry.id);

            NoteGroupFacade facade=new NoteGroupFacade(context);
            StreamingGroupStore.Snapshot old=facade.adoptLegacyNote(entry.id,(captured,staged)->{
                assertTrue(captured.pdfPresent);assertTrue(captured.coverPresent);assertEquals(1,captured.videoCount);assertEquals(1,captured.linkedVaultCount);
                assertTrue(staged.memberSizes().containsKey("pdf.bin"));assertTrue(staged.memberSizes().containsKey("cover.bin"));
                assertEquals(1,contentMemberCount(staged,"video/"));assertEquals(1,contentMemberCount(staged,"vault/"));return true;
            });
            byte[] expectedPdf=Files.readAllBytes(pdf.toPath());
            byte[] expectedCover=Files.readAllBytes(CoverStore.coverFile(new File(context.getFilesDir(),NoteStore.notesDirectoryName()),entry.id).toPath());
            byte[] expectedVault=null;
            for(VaultStore.VaultNote note:new VaultStore(context).listForBackupStrict())if(entry.id.equals(note.noteId))expectedVault=Files.readAllBytes(new VaultStore(context).fileForBackup(note.fileName).toPath());
            assertNotNull("the fixture has one linked Vault body",expectedVault);
            final byte[] expectedVaultBytes=expectedVault;
            assertStoredNoteBody(old,baselineStored,entry.id);assertMemberBytes(old,"pdf.bin",expectedPdf);assertMemberBytes(old,"cover.bin",expectedCover);
            assertRoleBytes(old,"video/",videoBytes);assertRoleBytes(old,"vault/",expectedVaultBytes);

            try(LibraryBackupArchive.StagedArchive source=LibraryBackupArchive.stage(incoming,archiveStage,null,null)){
                LibraryBackupManifest.Note note=onlyNote(source.manifest());Map<String,String> map=explicitMap(note,source.manifest(),entry.id);
                LibraryBackupManifest.VaultEntry linkedVault=source.manifest().vaultEntries.get(0);
                Map<String,String> invalidVaultDestination=new LinkedHashMap<>(map);
                invalidVaultDestination.put(linkedVault.itemId,"note-"+UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","")+".md");
                try{facade.updateFromArchive(source,note.itemId,entry.id,invalidVaultDestination,(a,b)->true);fail("Vault destinations must use canonical material UUIDs");}
                catch(java.io.IOException expected){assertEquals("UPDATE_VAULT_DESTINATION_UUID_REQUIRED",expected.getMessage());}
                Map<String,String> duplicateDestination=new LinkedHashMap<>(map);
                String firstMaterial=null;for(String key:duplicateDestination.keySet())if(!key.equals(note.sourceNoteId)){firstMaterial=key;break;}
                assertNotNull(firstMaterial);duplicateDestination.put(firstMaterial,entry.id);
                try{facade.updateFromArchive(source,note.itemId,entry.id,duplicateDestination,(a,b)->true);fail("duplicate destination mapping must be rejected");}
                catch(java.io.IOException expected){assertEquals("UPDATE_MAPPING_NOT_ONE_TO_ONE",expected.getMessage());}
                ManualUpdatePreviewSession.TargetToken captured=facade.captureManualUpdateTarget(entry.id);
                String capturedRevision=captured.revision(),capturedDigest=captured.digest();
                try{facade.prepareArchiveUpdatePreview(source,note.itemId,duplicateDestination,captured);fail("read-only preparation must reject duplicate destination IDs");}
                catch(java.io.IOException invalid){assertEquals("UPDATE_MAPPING_NOT_ONE_TO_ONE",invalid.getMessage());}
                StreamingGroupStore.Snapshot afterRejectedPrepare=facade.openGroup(entry.id);
                assertEquals(capturedRevision,afterRejectedPrepare.revision);assertEquals(capturedDigest,afterRejectedPrepare.digest);
                try(ManualUpdatePreviewSession session=facade.prepareArchiveUpdatePreview(source,note.itemId,map,captured)) {
                    assertEquals(source.archiveSha256(),session.archiveSha256());
                    assertEquals(note.itemId,session.sourceNoteItemId());
                    assertEquals(entry.id,session.targetToken().localId());
                    assertEquals(capturedRevision,session.targetToken().revision());
                    assertEquals(capturedDigest,session.targetToken().digest());
                    assertEquals(map,session.explicitMapping());
                    JSONObject previewBody=new JSONObject(new String(session.destinationBodyUtf8(),StandardCharsets.UTF_8));
                    assertEquals(entry.id,previewBody.getString("id"));
                    assertTrue("preview includes complete ink",previewBody.getJSONArray("strokes").length()>0);
                    assertTrue("preview includes text flow",previewBody.getJSONArray("textFlows").length()>0);
                    assertEquals("source SHA is bound separately from destination body projection",
                            source.manifest().updateProfiles.get(0).bodySha256,session.sourceBodySha256());
                    assertTrue(session.incomingResources().stream().anyMatch(r->"pdf".equals(r.role)&&r.isPresent()));
                    assertTrue(session.incomingResources().stream().anyMatch(r->"cover".equals(r.role)&&r.isPresent()));
                    assertTrue(session.incomingResources().stream().anyMatch(r->"video".equals(r.role)&&r.isPresent()&&r.metadataSha256!=null));
                    assertTrue(session.incomingResources().stream().anyMatch(r->"vault".equals(r.role)&&r.isPresent()&&r.metadataSha256!=null));
                    assertTrue("random explicit material IDs make the current target video an explicit removal preview",
                            session.targetOnlyResources().stream().anyMatch(r->"video".equals(r.role)));
                    assertTrue("random explicit material IDs make the current target Vault item an explicit removal preview",
                            session.targetOnlyResources().stream().anyMatch(r->"vault".equals(r.role)));
                    assertTrue("the target snapshot includes internal provenance for preservation",
                            session.targetResources().stream().anyMatch(r->"source-provenance".equals(r.role)));
                    assertTrue("removal preview excludes provenance and component-state bookkeeping",
                            session.targetOnlyResources().stream().noneMatch(r->"source-provenance".equals(r.role)
                                    || r.role.endsWith("-state")));
                    assertTrue("only visible replaceable resources are listed as removals",
                            session.targetOnlyResources().stream().allMatch(r->"pdf".equals(r.role)
                                    || "cover".equals(r.role) || "video".equals(r.role) || "vault".equals(r.role)));
                    ManualUpdatePreviewSession.PreviewBinding binding=session.binding();
                    ManualUpdatePreviewSession.PreviewBinding restoredBinding=
                            ManualUpdatePreviewSession.PreviewBinding.restore(binding.toJson());
                    try(ManualUpdatePreviewSession reopened=facade.reopenArchiveUpdatePreview(source,restoredBinding)) {
                        assertEquals("reopened preview retains the exact full input binding",binding.bindingDigest(),reopened.binding().bindingDigest());
                        assertEquals(binding.explicitMapping(),reopened.explicitMapping());
                    }
                    JSONObject tamperedRecord=binding.toJson();tamperedRecord.put("source_item_id","i-ffffffffffffffffffffffffffffffff");
                    try {ManualUpdatePreviewSession.PreviewBinding.restore(tamperedRecord);fail("saved preview rejects a substituted source item");}
                    catch(java.io.IOException changed){assertEquals("UPDATE_PREVIEW_BINDING_CHANGED",changed.getMessage());}
                    JSONObject changedPreviewRecord=binding.toJson();changedPreviewRecord.put("preview_digest",sha("different-preview".getBytes(StandardCharsets.UTF_8)));
                    try {ManualUpdatePreviewSession.PreviewBinding.restore(changedPreviewRecord);fail("saved preview rejects a changed preview digest");}
                    catch(java.io.IOException changed){assertEquals("UPDATE_PREVIEW_BINDING_CHANGED",changed.getMessage());}
                    Map<String,String> changedMapping=new LinkedHashMap<>(binding.explicitMapping());
                    String changedKey=changedMapping.keySet().iterator().next();
                    changedMapping.put(changedKey,changedMapping.get(changedKey)+"-changed");
                    try {
                        ManualUpdatePreviewSession.PreviewBinding.restore(binding.archiveSha256(),binding.sourceNoteItemId(),
                                binding.sourceLineage(),binding.sourceRevision(),binding.sourceBodySha256(),
                                binding.destinationBodySha256(),binding.previewDigest(),binding.targetToken(),
                                changedMapping,binding.bindingDigest());
                        fail("a saved binding cannot be partially rewritten to a different destination mapping");
                    } catch(java.io.IOException changed){assertEquals("UPDATE_PREVIEW_BINDING_CHANGED",changed.getMessage());}
                    ManualUpdatePreviewSession.TargetToken changedTarget=ManualUpdatePreviewSession.TargetToken.restore(
                            binding.targetToken().localId(),binding.targetToken().lineage(),binding.targetToken().revision(),
                            sha("different-target-digest".getBytes(StandardCharsets.UTF_8)));
                    try {
                        ManualUpdatePreviewSession.PreviewBinding.restore(binding.archiveSha256(),binding.sourceNoteItemId(),
                                binding.sourceLineage(),binding.sourceRevision(),binding.sourceBodySha256(),
                                binding.destinationBodySha256(),binding.previewDigest(),changedTarget,
                                binding.explicitMapping(),binding.bindingDigest());
                        fail("a saved binding cannot be partially rewritten to a different target digest");
                    } catch(java.io.IOException changed){assertEquals("UPDATE_PREVIEW_BINDING_CHANGED",changed.getMessage());}
                    try {
                        ManualUpdatePreviewSession.PreviewBinding.restore(binding.archiveSha256(),"i-ffffffffffffffffffffffffffffffff",
                                binding.sourceLineage(),binding.sourceRevision(),binding.sourceBodySha256(),
                                binding.destinationBodySha256(),binding.previewDigest(),binding.targetToken(),
                                binding.explicitMapping(),binding.bindingDigest());
                        fail("a saved binding cannot be partially rewritten to another source item");
                    } catch(java.io.IOException changed){assertEquals("UPDATE_PREVIEW_BINDING_CHANGED",changed.getMessage());}
                    try {
                        ManualUpdatePreviewSession.PreviewBinding.restore("0000000000000000000000000000000000000000000000000000000000000000",binding.sourceNoteItemId(),
                                binding.sourceLineage(),binding.sourceRevision(),binding.sourceBodySha256(),
                                binding.destinationBodySha256(),binding.previewDigest(),binding.targetToken(),
                                binding.explicitMapping(),binding.bindingDigest());
                        fail("a saved binding cannot be partially rewritten to another archive digest");
                    } catch(java.io.IOException changed){assertEquals("UPDATE_PREVIEW_BINDING_CHANGED",changed.getMessage());}
                    ManualUpdatePreviewSession.ResourceFact removedVideo=findTargetContent(session,"video");
                    assertNotNull(removedVideo);
                    ByteArrayOutputStream removedVideoBytes=new ByteArrayOutputStream();
                    session.copyTargetResource(removedVideo,removedVideoBytes,LibraryBackupManifest.MAX_VIDEO_BYTES);
                    assertArrayEquals("target-only video preview is the exact current immutable member",videoBytes,removedVideoBytes.toByteArray());
                    ManualUpdatePreviewSession.ResourceFact removedVault=findTargetContent(session,"vault");
                    assertNotNull(removedVault);
                    ByteArrayOutputStream removedVaultBytes=new ByteArrayOutputStream();
                    session.copyTargetResource(removedVault,removedVaultBytes,LibraryBackupManifest.MAX_VAULT_BYTES);
                    assertArrayEquals("target-only Vault preview is the exact current immutable member",expectedVaultBytes,removedVaultBytes.toByteArray());
                    ByteArrayOutputStream oldBody=new ByteArrayOutputStream();
                    session.copyTargetMember("body.bin",oldBody,StreamingGroupStore.BODY_MAX);
                    JSONObject oldPreviewBody=new JSONObject(new String(oldBody.toByteArray(),StandardCharsets.UTF_8));
                    assertNoteBodyFields(oldPreviewBody,baselineStored,entry.id);
                    assertArrayEquals("preview can stream the verified source PDF",expectedPdf,
                            copyIncoming(session,"pdf",entry.id,LibraryBackupManifest.MAX_PDF_BYTES));
                    assertArrayEquals("preview can stream the verified source cover",expectedCover,
                            copyIncoming(session,"cover",entry.id,LibraryBackupManifest.MAX_PNG_BYTES));
                    ManualUpdatePreviewSession.ResourceFact incomingVideoFact=findPreviewResource(session,"video");
                    assertNotNull(incomingVideoFact);
                    assertArrayEquals("preview can stream the verified source MP4",videoBytes,
                            copyIncoming(session,"video",incomingVideoFact.destinationId,LibraryBackupManifest.MAX_VIDEO_BYTES));
                    File originalStageResource=incomingVideoFact.stagedFile;
                    File heldStageResource=new File(originalStageResource.getParentFile(),"held-"+UUID.randomUUID());
                    File poisonedTarget=new File(root,"untrusted-stage-replacement.bin");
                    byte[] poisonedBytes=videoBytes.clone();poisonedBytes[0]=(byte)(poisonedBytes[0]^0x5a);
                    Files.write(poisonedTarget.toPath(),poisonedBytes);
                    ByteArrayOutputStream rejectedBytes=new ByteArrayOutputStream();
                    Files.move(originalStageResource.toPath(),heldStageResource.toPath());
                    try {
                        Os.symlink(poisonedTarget.getAbsolutePath(),originalStageResource.getAbsolutePath());
                        try {
                            session.copyIncomingResource(incomingVideoFact,rejectedBytes,LibraryBackupManifest.MAX_VIDEO_BYTES);
                            fail("a staged-resource symlink replacement must fail before any bytes are exposed");
                        } catch(java.io.IOException rejected) { assertEquals(0,rejectedBytes.size()); }
                    } finally {
                        Files.deleteIfExists(originalStageResource.toPath());
                        Files.move(heldStageResource.toPath(),originalStageResource.toPath());
                    }
                    ManualUpdatePreviewSession.ResourceFact sourceVault=findPreviewResource(session,"vault");
                    assertNotNull(sourceVault);
                    assertArrayEquals("preview can stream the verified projected Vault Markdown",expectedVaultBytes,
                            copyIncoming(session,"vault",sourceVault.destinationId,LibraryBackupManifest.MAX_VAULT_BYTES));
                    StreamingGroupStore.Snapshot unchanged=facade.openGroup(entry.id);
                    assertEquals("read-only preparation does not publish a revision",capturedRevision,unchanged.revision);
                    assertEquals(capturedDigest,unchanged.digest);
                    try {
                    facade.updateFromArchive(source,note.itemId,entry.id,map,(current,staged)->{
                        try(LibraryBackupArchive.StagedArchive competingSource=LibraryBackupArchive.stage(winner,archiveStage,null,null)){
                            LibraryBackupManifest.Note competingNote=onlyNote(competingSource.manifest());
                            facade.updateFromArchive(competingSource,competingNote.itemId,entry.id,
                                    explicitMap(competingNote,competingSource.manifest(),entry.id),(base,preview)->true);
                        }
                        assertFullMemberSet(staged);
                        try{session.requireSelectedTargetStillCurrent(facade);fail("reopened preview must reject a changed target instead of refreshing its token");}
                        catch(java.io.IOException conflict){assertEquals("BASE_CAS_CONFLICT",conflict.getMessage());}
                        try{facade.reopenArchiveUpdatePreview(source,binding);fail("re-preparing must not silently capture the new target revision");}
                        catch(java.io.IOException conflict){assertEquals("BASE_CAS_CONFLICT",conflict.getMessage());}
                        return true;
                    });
                    fail("a preview that lost the target CAS must not publish the stale incoming archive");
                    } catch(java.io.IOException conflict){assertEquals("BASE_CAS_CONFLICT",conflict.getMessage());}
                }
                assertTrue("closing the preview releases its scratch without closing the caller-owned staged archive",
                        source.resourceFile(note.noteResourceId).isFile());
            }
            StreamingGroupStore.Snapshot winnerCurrent=facade.openGroup(entry.id);assertNotNull(winnerCurrent);
            JSONObject winnerBody=new JSONObject(new String(winnerCurrent.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX),StandardCharsets.UTF_8));
            assertEquals("preview winner",winnerBody.getString("title"));assertMemberBytes(winnerCurrent,"pdf.bin",expectedPdf);
            assertMemberBytes(winnerCurrent,"cover.bin",expectedCover);assertRoleBytes(winnerCurrent,"video/",videoBytes);assertRoleBytes(winnerCurrent,"vault/",expectedVaultBytes);

            String winningRevision=winnerCurrent.revision,winningDigest=winnerCurrent.digest;
            try(LibraryBackupArchive.StagedArchive source=LibraryBackupArchive.stage(incoming,archiveStage,null,null)){
                LibraryBackupManifest.Note note=onlyNote(source.manifest());
                JSONObject incomingBody=new JSONObject(new String(Files.readAllBytes(source.resourceFile(note.noteResourceId).toPath()),StandardCharsets.UTF_8));
                byte[] incomingPdf=Files.readAllBytes(source.resourceFile(note.pdfResourceId).toPath());
                byte[] incomingCover=Files.readAllBytes(source.resourceFile(note.coverResourceId).toPath());
                LibraryBackupManifest.VideoAttachment videoRow=source.manifest().videoAttachments.get(0);
                byte[] incomingVideo=Files.readAllBytes(source.resourceFile(videoRow.resourceId).toPath());
                String projectedVideoId=ArchiveManualUpdateAdapter.videoSourceMaterialId(videoRow,source.manifest().updateProfiles.get(0));
                assertTrue("video source identity follows the canonical metadata UUID projection",
                        projectedVideoId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
                assertFalse("video archive row ID stays distinct from its storage material UUID",projectedVideoId.equals(videoRow.itemId));
                assertTrue("explicit mapping is still authored against the archive row ID",
                        explicitMap(note,source.manifest(),entry.id).containsKey(videoRow.itemId));
                LibraryBackupManifest.VaultEntry vaultRow=source.manifest().vaultEntries.get(0);
                JSONObject vaultPayload=new JSONObject(new String(Files.readAllBytes(source.resourceFile(vaultRow.resourceId).toPath()),StandardCharsets.UTF_8));
                LibraryBackupManifest.Resource vaultStorageResource=source.manifest().resourceById(vaultRow.sourceStorageResourceId);
                ArchiveUpdateProfile updateProfile=source.manifest().updateProfiles.get(0);
                String sourceMaterialId=ArchiveUpdateProfile.vaultSourceMaterialId(updateProfile,vaultRow,vaultStorageResource);
                assertTrue("archive UUID projection is a canonical material identity",sourceMaterialId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
                assertFalse("manifest row ID remains separate from storage material UUID",sourceMaterialId.equals(vaultRow.itemId));
                assertTrue("explicit user mapping continues to use the manifest row ID",explicitMap(note,source.manifest(),entry.id).containsKey(vaultRow.itemId));
                assertEquals("profile rule deterministically projects the same source material identity",sourceMaterialId,
                        ArchiveUpdateProfile.vaultSourceMaterialId(updateProfile,vaultRow,vaultStorageResource));
                byte[] incomingVault=Files.readAllBytes(source.resourceFile(vaultRow.sourceStorageResourceId).toPath());
                assertArrayEquals("v2 source projection preserves the exact Android Vault file including frontmatter",expectedVaultBytes,incomingVault);
                String sourceVaultText=new String(incomingVault,StandardCharsets.UTF_8);
                String sourceBinding="note-id: "+vaultRow.sourceNoteId;
                assertEquals("full source Vault has exactly one active note binding",1,sourceVaultText.split(java.util.regex.Pattern.quote(sourceBinding),-1).length-1);
                byte[] destinationVault=sourceVaultText.replace(sourceBinding,"note-id: "+entry.id).getBytes(StandardCharsets.UTF_8);
                Map<String,String> updateMapping=explicitMap(note,source.manifest(),entry.id);
                String destinationVideoId=updateMapping.get(videoRow.itemId);
                assertNotNull("the explicit archive-row mapping selects a destination video identity",destinationVideoId);
                assertTrue("destination material identity is a canonical UUID",
                        destinationVideoId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
                StreamingGroupStore.Snapshot updated=facade.updateFromArchive(source,note.itemId,entry.id,
                        updateMapping,(current,staged)->{assertFullMemberSet(staged);return true;});
                assertStoredNoteBody(updated,incomingBody,entry.id);assertFullMemberSet(updated);
                assertMemberBytes(updated,"pdf.bin",incomingPdf);assertMemberBytes(updated,"cover.bin",incomingCover);
                assertRoleBytes(updated,"video/",incomingVideo);assertRoleBytes(updated,"vault/",destinationVault);
                // Legacy UI reads must resolve the committed group revision, while
                // legacy media writers must fail closed instead of creating stale siblings.
                assertEquals(incomingBody.getString("title"),NoteStore.load(context,entry.id).getString("title"));
                NoteStore.Entry shelfEntry=null;for(NoteStore.Entry candidate:NoteStore.list(context))if(candidate.id.equals(entry.id))shelfEntry=candidate;
                assertNotNull("updated note remains in the ordinary bookshelf",shelfEntry);
                assertEquals(incomingBody.getString("title"),shelfEntry.title);
                assertArrayEquals("ordinary PDF reader sees the committed PDF",incomingPdf,Files.readAllBytes(NoteStore.pdfFile(context,entry.id).toPath()));
                File projectedCover=GroupAuthorityBridge.coverProjectionOrNull(context,entry.id);
                assertNotNull("ordinary cover reader resolves the committed cover",projectedCover);
                assertArrayEquals("ordinary cover reader sees the committed bytes",incomingCover,Files.readAllBytes(projectedCover.toPath()));
                VaultStore ordinaryVault=new VaultStore(context);VaultStore.VaultNote projectedVault=null;int ordinaryVaultCount=0;
                for(VaultStore.VaultNote item:ordinaryVault.list())if(entry.id.equals(item.noteId)){ordinaryVaultCount++;projectedVault=item;}
                assertEquals("ordinary Vault reader exposes only the committed group material",1,ordinaryVaultCount);
                assertNotNull("ordinary Vault reader resolves the committed group Markdown",projectedVault);
                assertArrayEquals("ordinary Vault reader returns exact committed Markdown bytes",destinationVault,
                        ordinaryVault.readBounded(projectedVault.fileName,16*1024*1024).getBytes(StandardCharsets.UTF_8));
                VideoAttachmentStore ordinaryVideo=new VideoAttachmentStore(context);
                List<VideoAttachmentStore.Attachment> visibleVideos=ordinaryVideo.listForNote(entry.id);
                assertEquals("ordinary video list reads the committed group revision",1,visibleVideos.size());
                assertEquals("ordinary video id follows the explicit destination mapping",destinationVideoId,visibleVideos.get(0).id);
                assertEquals("ordinary video stored name follows its projected descriptor id",
                        "video-"+destinationVideoId+".mp4",visibleVideos.get(0).storedName);
                assertArrayEquals("ordinary verified video open returns the exact committed object",incomingVideo,
                        Files.readAllBytes(ordinaryVideo.openVerified(visibleVideos.get(0)).toPath()));
                File ordinaryArchive=new File(root,"ordinary-fullmedia.padnote-library");
                File ordinaryStage=new File(context.getFilesDir(),"ordinary-export-stage");
                assertTrue("ordinary archive staging parent is created",ordinaryStage.mkdirs());
                try(LibraryBackupSnapshot snapshot=LibraryBackupSnapshot.create(context,Collections.singleton(entry.id),null,null)){
                    assertEquals("ordinary backup remains the existing copy-only profile",1,snapshot.manifest.formatVersion);
                    snapshot.write(ordinaryArchive,null,null);
                }
                try(LibraryBackupArchive.StagedArchive exported=LibraryBackupArchive.stage(ordinaryArchive,
                        ordinaryStage,null,null)){
                    LibraryBackupManifest.Note exportedNote=onlyNote(exported.manifest());
                    assertNotNull("ordinary archive includes the committed PDF",exportedNote.pdfResourceId);
                    assertNotNull("ordinary archive includes the committed cover",exportedNote.coverResourceId);
                    assertEquals(1,exported.manifest().videoAttachments.size());
                    assertEquals(1,exported.manifest().vaultEntries.size());
                    assertArrayEquals("ordinary archive captures the exact committed PDF",incomingPdf,
                            Files.readAllBytes(exported.resourceFile(exportedNote.pdfResourceId).toPath()));
                    assertArrayEquals("ordinary archive captures the exact committed cover",incomingCover,
                            Files.readAllBytes(exported.resourceFile(exportedNote.coverResourceId).toPath()));
                    LibraryBackupManifest.VideoAttachment exportedVideo=exported.manifest().videoAttachments.get(0);
                    assertArrayEquals("ordinary archive captures the exact committed video",incomingVideo,
                            Files.readAllBytes(exported.resourceFile(exportedVideo.resourceId).toPath()));
                    LibraryBackupManifest.VaultEntry exportedVault=exported.manifest().vaultEntries.get(0);
                    JSONObject exportedVaultPayload=new JSONObject(new String(Files.readAllBytes(
                            exported.resourceFile(exportedVault.resourceId).toPath()),StandardCharsets.UTF_8));
                    assertEquals("Update fixture Vault",exportedVaultPayload.getString("title"));
                    assertEquals("portable Vault Markdown preserves the exact incoming payload",
                            vaultPayload.getString("markdown"),exportedVaultPayload.getString("markdown"));
                }
                String committedRevision=updated.revision,committedDigest=updated.digest;
                JSONObject unsafeLegacyEdit=NoteStore.load(context,entry.id);unsafeLegacyEdit.put("title","must not replace group");
                try{NoteStore.save(context,entry.id,"must not replace group",unsafeLegacyEdit.toString());fail("legacy body save must be blocked for group-managed notes");}
                catch(java.io.IOException blocked){assertEquals("GROUP_NOTE_REQUIRES_FULL_REVISION_SAVE",blocked.getMessage());}
                try{new VaultStore(context).write(entry.id,"blocked",1,0,Collections.singletonList("stale"));fail("legacy Vault writer must be blocked for group-managed notes");}
                catch(java.io.IOException blocked){assertEquals("GROUP_NOTE_REQUIRES_FULL_REVISION_SAVE",blocked.getMessage());}
                try{new VideoAttachmentStore(context).attach(entry.id,0,"","task-blocked","artifact-blocked","blocked","video/mp4",1,sha(new byte[]{1}),sourceVideo);fail("legacy video writer must be blocked for group-managed notes");}
                catch(java.io.IOException blocked){assertEquals("GROUP_NOTE_REQUIRES_FULL_REVISION_SAVE",blocked.getMessage());}
                StreamingGroupStore.Snapshot afterBlockedWrites=facade.openGroup(entry.id);
                assertEquals("rejected legacy writers cannot supersede the committed revision",committedRevision,afterBlockedWrites.revision);
                assertEquals(committedDigest,afterBlockedWrites.digest);
                StreamingGroupStore.Snapshot retainedWinner=facade.openGroupRevision(entry.id,winningRevision,winningDigest);
                assertNotNull("winning pre-update revision remains immutable and readable",retainedWinner);
                assertStoredNoteBody(retainedWinner,winnerBody,entry.id);assertMemberBytes(retainedWinner,"pdf.bin",expectedPdf);
                assertMemberBytes(retainedWinner,"cover.bin",expectedCover);assertRoleBytes(retainedWinner,"video/",videoBytes);assertRoleBytes(retainedWinner,"vault/",expectedVaultBytes);
                assertNotEquals("archive update changes the source identity while keeping the same destination note",old.identity,updated.identity);
                StreamingGroupStore.Snapshot restored=facade.restoreGroupRevision(entry.id,old.revision,old.digest,
                        updated.revision,updated.digest,(current,archived)->{assertFullMemberSet(archived);assertStoredNoteBody(archived,baselineStored,entry.id);assertMemberBytes(archived,"pdf.bin",expectedPdf);assertMemberBytes(archived,"cover.bin",expectedCover);assertRoleBytes(archived,"video/",videoBytes);assertRoleBytes(archived,"vault/",expectedVaultBytes);return true;});
                assertNotEquals("restore must publish a fresh CAS revision",old.revision,restored.revision);assertStoredNoteBody(restored,baselineStored,entry.id);
                assertEquals("restored source revision remains readable",old.revision,facade.openGroupRevision(entry.id,old.revision,old.digest).revision);
                assertFullMemberSet(restored);assertMemberBytes(restored,"pdf.bin",expectedPdf);assertMemberBytes(restored,"cover.bin",expectedCover);
                assertRoleBytes(restored,"video/",videoBytes);assertRoleBytes(restored,"vault/",expectedVaultBytes);
                StreamingGroupStore.Snapshot retainedOld=facade.openGroupRevision(entry.id,old.revision,old.digest);
                assertStoredNoteBody(retainedOld,baselineStored,entry.id);assertMemberBytes(retainedOld,"pdf.bin",expectedPdf);
                assertMemberBytes(retainedOld,"cover.bin",expectedCover);assertRoleBytes(retainedOld,"video/",videoBytes);assertRoleBytes(retainedOld,"vault/",expectedVaultBytes);

                // Export the actual current HISTORY_RESTORE_V1 generation through the production
                // snapshot and ZIP writer. The portable v2 profile is derived from the exact
                // current body/material projection; local history/source pointers stay private.
                File restoredV2=new File(root,"history-restored-current-v2.padnote-library");
                StreamingGroupStore.Snapshot afterCaptureEdit;
                try(LibraryBackupSnapshot snapshot=LibraryBackupSnapshot.createForManualUpdate(
                        context,Collections.singleton(entry.id),null,null)){
                    assertEquals("grouped current snapshots use the existing strict v2 profile",2,
                            snapshot.manifest.formatVersion);
                    assertEquals(1,snapshot.manifest.updateProfiles.size());
                    JSONObject afterCaptureBody=new JSONObject(new String(
                            restored.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX),StandardCharsets.UTF_8));
                    afterCaptureBody.put("title","editor write after archive capture");
                    afterCaptureEdit=facade.saveEditorRevision(entry.id,restored.revision,restored.digest,
                            "editor write after archive capture",NoteJsonCodec.stringify(afterCaptureBody));
                    assertEquals(afterCaptureEdit.revision,facade.openGroup(entry.id).revision);
                    snapshot.write(restoredV2,null,null);
                }
                try(LibraryBackupArchive.StagedArchive roundtrip=LibraryBackupArchive.stage(
                        restoredV2,archiveStage,null,null)){
                    assertEquals(2,roundtrip.manifest().formatVersion);
                    assertEquals(1,roundtrip.manifest().updateProfiles.size());
                    LibraryBackupManifest.Note portable=onlyNote(roundtrip.manifest());
                    ArchiveUpdateProfile profile=roundtrip.manifest().updateProfiles.get(0);
                    byte[] portableBody=Files.readAllBytes(roundtrip.resourceFile(portable.noteResourceId).toPath());
                    assertArrayEquals("export binds profile to the current restored body bytes",
                            restored.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX),portableBody);
                    assertEquals(entry.id,profile.sourceNoteId);
                    assertEquals(sha(portableBody),profile.bodySha256);
                    ArchiveUpdateProfile.requireExactTimes(new String(portableBody,StandardCharsets.UTF_8),
                            entry.id,profile.timestamps);
                    assertNotNull(portable.pdfResourceId);assertNotNull(portable.coverResourceId);
                    assertEquals(1,roundtrip.manifest().videoAttachments.size());
                    assertEquals(1,roundtrip.manifest().vaultEntries.size());
                    LibraryBackupManifest.VideoAttachment portableVideo=roundtrip.manifest().videoAttachments.get(0);
                    LibraryBackupManifest.VaultEntry portableVault=roundtrip.manifest().vaultEntries.get(0);
                    assertTrue(portable.itemId.equals(portableVideo.noteItemId));
                    assertTrue(portable.itemId.equals(portableVault.noteItemId));
                    assertArrayEquals(expectedPdf,Files.readAllBytes(roundtrip.resourceFile(portable.pdfResourceId).toPath()));
                    assertArrayEquals(expectedCover,Files.readAllBytes(roundtrip.resourceFile(portable.coverResourceId).toPath()));
                    assertArrayEquals(videoBytes,Files.readAllBytes(roundtrip.resourceFile(portableVideo.resourceId).toPath()));
                    assertArrayEquals("profile carries the exact current Vault Markdown",
                            expectedVaultBytes,Files.readAllBytes(roundtrip.resourceFile(portableVault.sourceStorageResourceId).toPath()));

                    // Reimport through the production archive adapter with explicit destination
                    // mappings, then verify a new complete current revision and retained history.
                    StreamingGroupStore.Snapshot reimported=facade.updateFromArchive(roundtrip,
                            portable.itemId,entry.id,explicitMap(portable,roundtrip.manifest(),entry.id),
                            (current,incomingRoundtrip)->{assertFullMemberSet(incomingRoundtrip);return true;});
                    assertNotEquals(restored.revision,reimported.revision);
                    assertFullMemberSet(reimported);
                    assertStoredNoteBody(reimported,new JSONObject(new String(portableBody,StandardCharsets.UTF_8)),entry.id);
                    assertMemberBytes(reimported,"pdf.bin",expectedPdf);
                    assertMemberBytes(reimported,"cover.bin",expectedCover);
                    assertRoleBytes(reimported,"video/",videoBytes);
                    assertRoleBytes(reimported,"vault/",expectedVaultBytes);
                    StreamingGroupStore.Snapshot historyAfterReimport=facade.openGroupRevision(entry.id,
                            restored.revision,restored.digest);
                    assertNotNull("portable reimport keeps the history-restored immutable generation",historyAfterReimport);
                    assertStoredNoteBody(historyAfterReimport,baselineStored,entry.id);
                    assertMemberBytes(historyAfterReimport,"pdf.bin",expectedPdf);
                    assertMemberBytes(historyAfterReimport,"cover.bin",expectedCover);
                    assertRoleBytes(historyAfterReimport,"video/",videoBytes);
                    assertRoleBytes(historyAfterReimport,"vault/",expectedVaultBytes);
                    StreamingGroupStore.Snapshot interveningHistory=facade.openGroupRevision(entry.id,
                            afterCaptureEdit.revision,afterCaptureEdit.digest);
                    assertNotNull("the edit concurrent with ZIP writing remains in immutable history",interveningHistory);
                    JSONObject interveningBody=new JSONObject(new String(interveningHistory.readSmall("body.bin",
                            (int)StreamingGroupStore.BODY_MAX),StandardCharsets.UTF_8));
                    assertEquals("editor write after archive capture",interveningBody.getString("title"));
                    assertMemberBytes(interveningHistory,"pdf.bin",expectedPdf);
                    assertMemberBytes(interveningHistory,"cover.bin",expectedCover);
                    assertRoleBytes(interveningHistory,"video/",videoBytes);
                    assertRoleBytes(interveningHistory,"vault/",expectedVaultBytes);
                }
                emitSyntheticArchiveEvidence(context,restoredV2);
            }
            assertNotEquals("the preview winner was a real distinct group revision",old.revision,winningRevision);
        } finally { deleteTree(root); }
    }

    /** Keeps only this synthetic production ZIP in a fresh app-cache UUID leaf for external readers. */
    private static void emitSyntheticArchiveEvidence(Context context,File archive)throws Exception {
        final long maximumBytes=64L*1024L*1024L;
        long expectedBytes=archive.length();
        assertTrue("synthetic archive must be nonempty and bounded",expectedBytes>0&&expectedBytes<=maximumBytes);
        String leaf=UUID.randomUUID().toString();
        File evidenceDir=new File(new File(context.getCacheDir(),"padnote-native-evidence"),leaf);
        assertTrue("fresh synthetic evidence leaf is created",evidenceDir.mkdirs());
        String name="grouped-current-v2.zip";
        File retained=new File(evidenceDir,name);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        long copied=0;
        try(InputStream input=new java.io.FileInputStream(archive);FileOutputStream output=new FileOutputStream(retained,false)){
            byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1){
                copied+=count;
                if(copied>maximumBytes)throw new java.io.IOException("SYNTHETIC_ARCHIVE_EVIDENCE_TOO_LARGE");
                digest.update(buffer,0,count);output.write(buffer,0,count);
            }
            output.flush();output.getFD().sync();
        }
        assertEquals("bounded copy preserves exact production ZIP length",expectedBytes,copied);
        StringBuilder hex=new StringBuilder();for(byte value:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        String relative="cache/padnote-native-evidence/"+leaf+"/"+name;
        JSONObject receipt=new JSONObject();receipt.put("status","SYNTHETIC_PRODUCTION_ZIP_RETAINED");
        receipt.put("path",relative);receipt.put("bytes",copied);receipt.put("sha256",hex.toString());
        String safe="R53_ARCHIVE_EVIDENCE "+receipt;System.out.println(safe);
        android.os.Bundle status=new android.os.Bundle();status.putString("stream",safe);
        InstrumentationRegistry.getInstrumentation().sendStatus(2,status);
    }

    private static void writeSnapshot(Context context,String noteId,File destination)throws Exception {
        try(LibraryBackupSnapshot snapshot=LibraryBackupSnapshot.createForManualUpdate(context,Collections.singleton(noteId),null,null)){snapshot.write(destination,null,null);}
    }
    private static byte[] copyIncoming(ManualUpdatePreviewSession session,String role,String destinationId,long cap)throws Exception {
        ManualUpdatePreviewSession.ResourceFact found=null;
        for(ManualUpdatePreviewSession.ResourceFact fact:session.incomingResources())
            if(role.equals(fact.role)&&java.util.Objects.equals(destinationId,fact.destinationId)){found=fact;break;}
        assertNotNull("incoming preview member exists: "+role,found);
        ByteArrayOutputStream out=new ByteArrayOutputStream();session.copyIncomingResource(found,out,cap);return out.toByteArray();
    }
    private static ManualUpdatePreviewSession.ResourceFact findPreviewResource(ManualUpdatePreviewSession session,String role)throws Exception {
        for(ManualUpdatePreviewSession.ResourceFact fact:session.incomingResources())if(role.equals(fact.role))return fact;
        return null;
    }
    private static ManualUpdatePreviewSession.ResourceFact findTargetContent(ManualUpdatePreviewSession session,String role)throws Exception {
        for(ManualUpdatePreviewSession.ResourceFact fact:session.targetOnlyResources())
            if(role.equals(fact.role)&&fact.memberPath!=null&&fact.memberPath.endsWith("/content.bin"))return fact;
        return null;
    }
    private static void assertProfileRejectsUnsafeVariants(LibraryBackupManifest manifest)throws Exception {
        JSONObject base=new JSONObject(new String(manifest.toJsonBytes(),StandardCharsets.UTF_8));
        JSONObject missing=new JSONObject(base.toString());missing.remove("update_profiles");
        try{LibraryBackupManifest.parse(missing.toString().getBytes(StandardCharsets.UTF_8));fail("v2 without profile set must fail closed");}catch(java.io.IOException expected){}
        JSONObject digest=new JSONObject(base.toString());digest.getJSONArray("update_profiles").getJSONObject(0).put("group_sha256","0000000000000000000000000000000000000000000000000000000000000000");
        try{LibraryBackupManifest.parse(digest.toString().getBytes(StandardCharsets.UTF_8));fail("profile group digest mismatch must be rejected");}catch(java.io.IOException expected){}
        JSONObject duplicate=new JSONObject(base.toString());JSONArray profiles=duplicate.getJSONArray("update_profiles");profiles.put(new JSONObject(profiles.getJSONObject(0).toString()));
        try{LibraryBackupManifest.parse(duplicate.toString().getBytes(StandardCharsets.UTF_8));fail("duplicate note profile must be rejected");}catch(java.io.IOException expected){}
        JSONObject owner=new JSONObject(base.toString());JSONArray videos=owner.getJSONArray("video_attachments");
        if(videos.length()>0){JSONObject row=videos.getJSONObject(0);row.put("note_item_id",owner.getJSONArray("notes").getJSONObject(0).getString("item_id").equals(row.optString("note_item_id"))?"i-ffffffffffffffffffffffffffffffff":owner.getJSONArray("notes").getJSONObject(0).getString("item_id"));
            try{LibraryBackupManifest.parse(owner.toString().getBytes(StandardCharsets.UTF_8));fail("linked material owner mismatch must be rejected");}catch(java.io.IOException expected){}}
        else {JSONObject vaultOwner=new JSONObject(base.toString());JSONArray vaults=vaultOwner.getJSONArray("vault_entries");assertTrue(vaults.length()>0);vaults.getJSONObject(0).put("note_item_id","i-ffffffffffffffffffffffffffffffff");
            try{LibraryBackupManifest.parse(vaultOwner.toString().getBytes(StandardCharsets.UTF_8));fail("linked material owner mismatch must be rejected");}catch(java.io.IOException expected){}}
    }
    private static LibraryBackupManifest.Note onlyNote(LibraryBackupManifest manifest){assertEquals(1,manifest.notes.size());return manifest.notes.get(0);}
    private static Map<String,String> explicitMap(LibraryBackupManifest.Note note,LibraryBackupManifest manifest,String target){
        Map<String,String> result=new LinkedHashMap<>();result.put(note.sourceNoteId,target);
        for(LibraryBackupManifest.VideoAttachment row:manifest.videoAttachments)if(note.itemId.equals(row.noteItemId)&&"linked_note".equals(row.sourceState))result.put(row.itemId,UUID.randomUUID().toString());
        for(LibraryBackupManifest.VaultEntry row:manifest.vaultEntries)if(note.itemId.equals(row.noteItemId)&&"linked_note".equals(row.sourceState))result.put(row.itemId,UUID.randomUUID().toString());
        return result;
    }
    private static void assertFullMemberSet(StreamingGroupStore.VerifiedGroup group)throws Exception {
        assertEquals("present\n",new String(group.readSmall("pdf.state",16),StandardCharsets.US_ASCII));
        assertEquals("present\n",new String(group.readSmall("cover.state",16),StandardCharsets.US_ASCII));
        assertEquals("present\n",new String(group.readSmall("video.state",16),StandardCharsets.US_ASCII));
        assertEquals("present\n",new String(group.readSmall("vault.state",16),StandardCharsets.US_ASCII));
        assertEquals(1,contentMemberCount(group,"video/"));assertEquals(1,contentMemberCount(group,"vault/"));
        assertTrue(group.memberSizes().get("pdf.bin")>0);assertTrue(group.memberSizes().get("cover.bin")>0);
    }
    private static void assertFullMemberSet(StreamingGroupStore.Snapshot group)throws Exception {
        assertEquals("present\n",new String(group.readSmall("pdf.state",16),StandardCharsets.US_ASCII));
        assertEquals("present\n",new String(group.readSmall("cover.state",16),StandardCharsets.US_ASCII));
        assertEquals("present\n",new String(group.readSmall("video.state",16),StandardCharsets.US_ASCII));
        assertEquals("present\n",new String(group.readSmall("vault.state",16),StandardCharsets.US_ASCII));
        assertEquals(1,contentMemberCount(group,"video/"));assertEquals(1,contentMemberCount(group,"vault/"));
        assertTrue(group.memberSizes().get("pdf.bin")>0);assertTrue(group.memberSizes().get("cover.bin")>0);
    }
    private static void assertStoredNoteBody(StreamingGroupStore.Snapshot group,JSONObject expected,String localId)throws Exception {
        JSONObject actual=new JSONObject(new String(group.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX),StandardCharsets.UTF_8));
        assertNoteBodyFields(actual,expected,localId);
    }
    private static void assertMemberBytes(StreamingGroupStore.Snapshot group,String member,byte[] expected)throws Exception {
        byte[] actual=group.readSmall(member,Math.max(expected.length,1));assertEquals("exact member bytes: "+member,sha(expected),sha(actual));
    }
    private static void assertRoleBytes(StreamingGroupStore.Snapshot group,String prefix,byte[] expected)throws Exception {
        String found=null;for(String member:group.memberSizes().keySet())if(member.startsWith(prefix)&&member.endsWith("/content.bin")){if(found!=null)fail("expected one member in "+prefix);found=member;}
        assertNotNull("content member under "+prefix,found);assertMemberBytes(group,found,expected);
    }
    private static void assertStoredNoteBody(StreamingGroupStore.VerifiedGroup group,JSONObject expected,String localId)throws Exception {
        JSONObject actual=new JSONObject(new String(group.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX),StandardCharsets.UTF_8));
        assertNoteBodyFields(actual,expected,localId);
    }
    private static void assertMemberBytes(StreamingGroupStore.VerifiedGroup group,String member,byte[] expected)throws Exception {
        byte[] actual=group.readSmall(member,Math.max(expected.length,1));assertEquals("exact member bytes: "+member,sha(expected),sha(actual));
    }
    private static void assertRoleBytes(StreamingGroupStore.VerifiedGroup group,String prefix,byte[] expected)throws Exception {
        String found=null;for(String member:group.memberSizes().keySet())if(member.startsWith(prefix)&&member.endsWith("/content.bin")){if(found!=null)fail("expected one member in "+prefix);found=member;}
        assertNotNull("content member under "+prefix,found);assertMemberBytes(group,found,expected);
    }
    private static void assertNoteBodyFields(JSONObject actual,JSONObject expected,String localId)throws Exception {
        assertEquals(localId,actual.getString("id"));assertEquals(expected.getString("title"),actual.getString("title"));
        assertEquals(expected.getLong("updatedAt"),actual.getLong("updatedAt"));
        for(String field:new String[]{"createdAt","pageCount","pdfPageCount","schemaVersion"})if(expected.has(field))assertEquals("preserve note field "+field,expected.get(field).toString(),actual.get(field).toString());
        assertEquals("preserve complete handwriting geometry and timestamps",expected.getJSONArray("strokes").toString(),actual.getJSONArray("strokes").toString());
        String expectedFlows=expected.optJSONArray("textFlows")==null?"[]":expected.getJSONArray("textFlows").toString();
        String actualFlows=actual.optJSONArray("textFlows")==null?"[]":actual.getJSONArray("textFlows").toString();
        assertEquals("preserve complete text-flow content",expectedFlows,actualFlows);
    }
    private static int contentMemberCount(StreamingGroupStore.VerifiedGroup group,String prefix){int count=0;for(String p:group.memberSizes().keySet())if(p.startsWith(prefix)&&p.endsWith("/content.bin"))count++;return count;}
    private static int contentMemberCount(StreamingGroupStore.Snapshot group,String prefix)throws Exception{int count=0;for(String p:group.memberSizes().keySet())if(p.startsWith(prefix)&&p.endsWith("/content.bin"))count++;return count;}
    private static void writePdf(File file)throws Exception {PdfDocument doc=new PdfDocument();PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(144,144,1).create());page.getCanvas().drawColor(Color.WHITE);Paint p=new Paint();p.setColor(Color.BLACK);page.getCanvas().drawText("source group",10,40,p);doc.finishPage(page);try(FileOutputStream out=new FileOutputStream(file)){doc.writeTo(out);out.flush();out.getFD().sync();}finally{doc.close();}}
    private static String sha(byte[] bytes)throws Exception {byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static void deleteTree(File root)throws Exception {if(!root.exists())return;try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(root.toPath())){for(java.nio.file.Path path:paths.sorted(Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new))Files.deleteIfExists(path);}}
    private static final class FixtureContext extends ContextWrapper {
        private final File files;FixtureContext(Context base,File root){super(base);files=new File(root,"app-files");if(!files.mkdirs())throw new IllegalStateException("fixture_files_create");}
        @Override public File getFilesDir(){return files;}
        @Override public File getDir(String name,int mode){if(name==null||!name.matches("[A-Za-z0-9._-]{1,64}"))throw new IllegalArgumentException("fixture_dir_name");File dir=new File(files,"app_"+name);if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("fixture_dir_create");return dir;}
        @Override public Context getApplicationContext(){return this;}
    }
}
