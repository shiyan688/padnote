package com.padnote.android;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class RestoredLibraryMaterialStoreTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();

    @Test public void multipleKindsRemainHiddenUntilGateAndKeepHistoricalIdentity() throws Exception {
        Fixture f=fixture();
        RestoredLibraryMaterialStore store=store(f.root);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString();
        List<RestoredLibraryMaterialStore.Record> records=new ArrayList<>();
        LibraryBackupManifest.VaultEntry linkedVault=f.archive.manifest().vaultEntries.get(0);
        records.add(promote(store,f.archive,f.archive.manifest(),tx,group,RestoredLibraryMaterialStore.Kind.VAULT,
                UUID.randomUUID().toString(),"note-restored-111",linkedVault));
        LibraryBackupManifest.VaultEntry deletedVault=f.archive.manifest().vaultEntries.get(1);
        records.add(promote(store,f.archive,f.archive.manifest(),tx,group,RestoredLibraryMaterialStore.Kind.VAULT,
                UUID.randomUUID().toString(),null,deletedVault));
        LibraryBackupManifest.VideoAttachment video=f.archive.manifest().videoAttachments.get(0);
        records.add(promote(store,f.archive,f.archive.manifest(),tx,group,RestoredLibraryMaterialStore.Kind.VIDEO,
                UUID.randomUUID().toString(),"note-restored-222",video));
        LibraryBackupManifest.VideoAttachment detached=f.archive.manifest().videoAttachments.get(1);
        records.add(promote(store,f.archive,f.archive.manifest(),tx,group,RestoredLibraryMaterialStore.Kind.VIDEO,
                UUID.randomUUID().toString(),null,detached));
        LibraryBackupManifest.CoverPreset preset=f.archive.manifest().coverPresets.get(0);
        records.add(promote(store,f.archive,f.archive.manifest(),tx,group,RestoredLibraryMaterialStore.Kind.COVER_PRESET,
                UUID.randomUUID().toString(),null,preset));

        RestoredLibraryMaterialStore.CommitGate gate=proof->false;
        assertTrue(store.list(gate).isEmpty());
        try { store.readVault(records.get(0).localId,gate); fail("uncommitted row was readable"); }
        catch(IOException expected){assertEquals("MATERIAL_NOT_COMMITTED",expected.getMessage());}
        Set<String> committed=new HashSet<>();
        for(RestoredLibraryMaterialStore.Record r:records)committed.add(proofKey(RestoredLibraryMaterialStore.proofFor(r)));
        RestoredLibraryMaterialStore.CommitGate committedGate=proof->committed.contains(proofKey(proof));
        assertEquals(5,store.list(committedGate).size());
        RestoredLibraryMaterialStore.Record restoredVideo=store.list(committedGate).stream().filter(r->r.kind==RestoredLibraryMaterialStore.Kind.VIDEO&&r.currentLocalNoteId!=null).findFirst().get();
        assertEquals("restored_archive",restoredVideo.originKind);
        assertEquals(video.sourceNoteId,restoredVideo.historicalSourceNoteId);
        assertNotEquals(restoredVideo.currentLocalNoteId,restoredVideo.historicalSourceNoteId);
        assertEquals(video.sourceRevisionPrecisionMs,restoredVideo.sourceRevisionPrecisionMs);
        byte[] vault=store.readVault(records.get(0).localId,committedGate);
        assertTrue(new String(vault,java.nio.charset.StandardCharsets.UTF_8).contains(linkedVault.sourceNoteId));
        File shared=new File(temp.getRoot(),"shared-video.mp4");
        store.copyForShare(restoredVideo.localId,shared,committedGate);
        assertEquals(restoredVideo.byteLength,shared.length());
        assertEquals(1L,((Number)Files.getAttribute(shared.toPath(),"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue());
        try { store.copyForShare(restoredVideo.localId,shared,committedGate); fail("overwrote existing share destination"); }
        catch(IOException expected){assertEquals("MATERIAL_SHARE_DESTINATION_EXISTS",expected.getMessage());}
        f.archive.close();
    }

    @Test public void rollbackNamespaceTreatsMissingOwnedAncestorsAsAbsentAndRejectsSymlinks() throws Exception {
        Fixture f=fixture();
        try {
            File materialRoot=new File(f.root,"materials");
            RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(materialRoot,
                    new MissingAncestorSensitiveFiles(),new SyntheticMedia());
            String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),local=UUID.randomUUID().toString();
            assertTrue(store.isMemberNamespaceAbsent(tx,group,local,RestoredLibraryMaterialStore.Kind.VIDEO));
            File pending=new File(materialRoot,"pending"),foreign=new File(f.root,"foreign");
            assertTrue(foreign.mkdir());File sentinel=new File(foreign,"sentinel");Files.write(sentinel.toPath(),new byte[]{4,5,6});
            File linkedTx=new File(pending,tx);Files.createSymbolicLink(linkedTx.toPath(),foreign.toPath());
            try { store.isMemberNamespaceAbsent(tx,group,local,RestoredLibraryMaterialStore.Kind.VIDEO);fail("symlink ancestor accepted"); }
            catch(IOException expected){assertNotNull(expected.getMessage());}
            assertTrue(Files.isSymbolicLink(linkedTx.toPath()));
            assertArrayEquals(new byte[]{4,5,6},Files.readAllBytes(sentinel.toPath()));
        } finally { f.archive.close(); }
    }

    @Test public void exactPromotionIsIdempotentAndRollbackCannotRemoveCommittedOrForeignRows() throws Exception {
        Fixture f=fixture();RestoredLibraryMaterialStore store=store(f.root);
        LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);
        LibraryBackupManifest.Resource res=f.archive.manifest().resourceById(d.resourceId);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        RestoredLibraryMaterialStore.Prepared prepared=store.prepareVideo(tx,group,id,"note-restored",d.itemId,f.archive);
        RestoredLibraryMaterialStore.CommitProof proof=prepared.commitProof();
        RestoredLibraryMaterialStore.Record once=store.promote(prepared),twice=store.promote(prepared);
        assertEquals(once.sha256,twice.sha256);
        RestoredLibraryMaterialStore.CommitGate open=candidate->true;
        try { store.rollback(proof,open,pendingGroup(true)); fail("committed row rolled back"); }
        catch(IOException expected){assertEquals("MATERIAL_ALREADY_COMMITTED",expected.getMessage());}
        RestoredLibraryMaterialStore.CommitProof foreign=new RestoredLibraryMaterialStore.CommitProof(UUID.randomUUID().toString(),
                proof.groupId,proof.localId,proof.kind,proof.payloadSha256,proof.bindingSha256,proof.currentLocalNoteId);
        try { store.rollback(foreign,candidate->false,pendingGroup(true)); fail("foreign transaction rolled back row"); }
        catch(IOException expected){assertEquals("MATERIAL_ROLLBACK_OWNERSHIP_MISMATCH",expected.getMessage());}
        RestoredLibraryMaterialStore.CommitGate closed=candidate->false;
        store.rollback(proof,closed,pendingGroup(true));
        assertTrue(store.list(closed).isEmpty());
        f.archive.close();
    }

    @Test public void promotionResumesExactReservationBeforeRenameAndAfterDirectorySyncFailure() throws Exception {
        Fixture f=fixture();LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        File storeRoot=new File(f.root,"materials");FaultFiles renameFault=new FaultFiles();renameFault.failRenameTarget=id+".mp4";
        RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(storeRoot,renameFault,new SyntheticMedia());
        RestoredLibraryMaterialStore.Prepared prepared=store.prepareVideo(tx,group,id,"note-restored-new",d.itemId,f.archive);
        File finalPayload=new File(new File(storeRoot,"payloads"),id+".mp4");
        try{store.promote(prepared);fail("injected rename failure hidden");}
        catch(IOException expected){assertEquals("INJECTED_RENAME_BEFORE_MOVE",expected.getMessage());}
        assertTrue(finalPayload.isFile());assertFalse(renameFault.lastRenameCompleted);
        assertFalse(new File(prepared.intent.getParentFile(),"payload-"+id+".bin").exists());
        RestoredLibraryMaterialStore.Record row=store.promote(prepared);
        assertEquals(d.sha256,row.sha256);assertTrue(renameFault.lastRenameCompleted);
        assertEquals(1L,((Number)Files.getAttribute(finalPayload.toPath(),"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue());
        assertTrue(store.list(proof->true).size()==1);
        f.archive.close();
    }

    @Test public void metadataReservationResumesAfterCrashBeforeRecordRename() throws Exception {
        Fixture f=fixture();LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        File root=new File(f.root,"materials");FaultFiles files=new FaultFiles();files.failRenameTarget=id+".json";
        RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(root,files,new SyntheticMedia());
        RestoredLibraryMaterialStore.Prepared p=store.prepareVideo(tx,group,id,"note-restored",d.itemId,f.archive);
        RestoredLibraryMaterialStore.CommitProof proof=p.commitProof();
        File recordPath=new File(new File(root,"records"),id+".json");
        try{store.promote(p);fail("record rename failure hidden");}
        catch(IOException expected){assertEquals("INJECTED_RENAME_BEFORE_MOVE",expected.getMessage());}
        assertTrue(recordPath.isFile());assertTrue(files.failedRename);assertFalse(files.lastRenameCompleted);
        RestoredLibraryMaterialStore.Record row=store.promote(p);
        assertEquals(proof.bindingSha256,RestoredLibraryMaterialStore.proofFor(row).bindingSha256);
        assertEquals(1,store.list(candidate->candidate.bindingSha256.equals(proof.bindingSha256)).size());
        f.archive.close();
    }

    @Test public void rollbackRemovesExactPublishMarkerAndAllKnownPromotionTemps() throws Exception {
        Fixture f=fixture();LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        File root=new File(f.root,"materials");FaultFiles files=new FaultFiles();files.failRenameTarget=id+".mp4";
        RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(root,files,new SyntheticMedia());
        RestoredLibraryMaterialStore.Prepared prepared=store.prepareVideo(tx,group,id,"note-restored",d.itemId,f.archive);
        RestoredLibraryMaterialStore.CommitProof proof=prepared.commitProof();
        File finalPayload=new File(new File(root,"payloads"),id+".mp4");
        try{store.promote(prepared);fail("injected payload rename failure hidden");}
        catch(IOException expected){assertEquals("INJECTED_RENAME_BEFORE_MOVE",expected.getMessage());}
        byte[] marker=Files.readAllBytes(finalPayload.toPath());assertTrue(marker.length>0);
        File pendingDir=prepared.intent.getParentFile();File promoteStage=new File(new File(root,"payloads"),"promote-"+id+"-"+prepared.record.promotionToken+".tmp");
        File token=new File(pendingDir,"promotion-"+id+".json");File record=new File(new File(root,"records"),id+".json");
        store.rollback(proof,candidate->false,pendingGroup(true));
        assertFalse(finalPayload.exists());assertFalse(promoteStage.exists());assertFalse(token.exists());assertFalse(record.exists());assertFalse(prepared.intent.exists());
        f.archive.close();
    }

    @Test public void rollbackRejectsChangedReservationOrTemporaryWithoutDeletingRecoveryState() throws Exception {
        Fixture f=fixture();LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        File root=new File(f.root,"materials");FaultFiles files=new FaultFiles();files.failRenameTarget=id+".json";
        RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(root,files,new SyntheticMedia());
        RestoredLibraryMaterialStore.Prepared prepared=store.prepareVideo(tx,group,id,"note-restored",d.itemId,f.archive);
        RestoredLibraryMaterialStore.CommitProof proof=prepared.commitProof();
        try{store.promote(prepared);fail("injected record rename failure hidden");}
        catch(IOException expected){assertEquals("INJECTED_RENAME_BEFORE_MOVE",expected.getMessage());}
        File stage=new File(new File(root,"records"),".stage-"+prepared.record.promotionToken+"-"+id+".json.tmp");
        File pending=new File(prepared.intent.getParentFile(),"payload-"+id+".bin");
        byte[] unknown=Files.readAllBytes(stage.toPath());unknown[unknown.length-2]^=1;Files.write(stage.toPath(),unknown);
        try{store.rollback(proof,candidate->false,pendingGroup(true));fail("changed metadata temp was deleted");}
        catch(IOException expected){assertEquals("MATERIAL_ROLLBACK_OBJECT_UNKNOWN",expected.getMessage());}
        assertTrue(stage.exists());assertTrue(pending.exists());assertTrue(prepared.intent.exists());
        assertTrue(new File(new File(root,"records"),id+".json").exists());
        f.archive.close();
    }

    @Test public void rollbackRemovesExactRecordReservationAndEveryKnownTemp() throws Exception {
        Fixture f=fixture();LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        File root=new File(f.root,"materials");FaultFiles files=new FaultFiles();files.failRenameTarget=id+".json";
        RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(root,files,new SyntheticMedia());
        RestoredLibraryMaterialStore.Prepared prepared=store.prepareVideo(tx,group,id,"note-restored",d.itemId,f.archive);
        RestoredLibraryMaterialStore.CommitProof proof=prepared.commitProof();
        try{store.promote(prepared);fail("injected record rename failure hidden");}
        catch(IOException expected){assertEquals("INJECTED_RENAME_BEFORE_MOVE",expected.getMessage());}
        File record=new File(new File(root,"records"),id+".json");
        File recordStage=new File(record.getParentFile(),".stage-"+prepared.record.promotionToken+"-"+record.getName()+".tmp");
        File token=new File(prepared.intent.getParentFile(),"promotion-"+id+".json");
        File pending=new File(prepared.intent.getParentFile(),"payload-"+id+".bin");
        assertTrue(recordStage.exists());assertTrue(token.exists());assertTrue(pending.exists());
        store.rollback(proof,candidate->false,pendingGroup(true));
        assertFalse(record.exists());assertFalse(recordStage.exists());assertFalse(token.exists());
        assertFalse(pending.exists());assertFalse(prepared.intent.exists());
        f.archive.close();
    }

    @Test public void rollbackRequiresPendingAuthorityAndDoesNotTreatUncommittedAsPending() throws Exception {
        Fixture f=fixture();RestoredLibraryMaterialStore store=store(f.root);
        LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        RestoredLibraryMaterialStore.Prepared prepared=store.prepareVideo(tx,group,id,"note-restored",d.itemId,f.archive);
        RestoredLibraryMaterialStore.CommitProof proof=prepared.commitProof();
        try{store.rollback(proof,candidate->false,pendingGroup(false));fail("missing authoritative pending state accepted");}
        catch(IOException expected){assertEquals("MATERIAL_ROLLBACK_NOT_PENDING",expected.getMessage());}
        assertTrue(prepared.intent.exists());assertTrue(prepared.payload.exists());
        f.archive.close();
    }

    @Test public void rollbackPreflightsTokenBeforeRemovingRecordOrIntent() throws Exception {
        Fixture f=fixture();LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        File root=new File(f.root,"materials");FaultFiles files=new FaultFiles();files.failRenameTarget=id+".json";
        RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(root,files,new SyntheticMedia());
        RestoredLibraryMaterialStore.Prepared prepared=store.prepareVideo(tx,group,id,"note-restored",d.itemId,f.archive);
        RestoredLibraryMaterialStore.CommitProof proof=prepared.commitProof();
        try{store.promote(prepared);fail("injected record rename failure hidden");}
        catch(IOException expected){assertEquals("INJECTED_RENAME_BEFORE_MOVE",expected.getMessage());}
        File record=new File(new File(root,"records"),id+".json");
        File token=new File(prepared.intent.getParentFile(),"promotion-"+id+".json");
        byte[] changed=Files.readAllBytes(token.toPath());changed[changed.length-2]^=1;Files.write(token.toPath(),changed);
        try{store.rollback(proof,candidate->false,pendingGroup(true));fail("changed token was removed");}
        catch(IOException expected){assertEquals("MATERIAL_ROLLBACK_OBJECT_UNKNOWN",expected.getMessage());}
        assertTrue(record.exists());assertTrue(prepared.intent.exists());assertTrue(token.exists());
        f.archive.close();
    }

    @Test public void promotionPreservesUnknownFinalAndDoesNotAuthorizeIt() throws Exception {
        Fixture f=fixture();LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        File root=new File(f.root,"materials");RestoredLibraryMaterialStore store=store(f.root);
        RestoredLibraryMaterialStore.Prepared prepared=store.prepareVideo(tx,group,id,"note-restored",d.itemId,f.archive);
        File finalPayload=new File(new File(root,"payloads"),id+".mp4");byte[] foreign={4,5,6};Files.write(finalPayload.toPath(),foreign);
        try{store.promote(prepared);fail("foreign target replaced");}
        catch(IOException expected){assertEquals("MATERIAL_PUBLISH_TARGET_UNKNOWN",expected.getMessage());}
        assertArrayEquals(foreign,Files.readAllBytes(finalPayload.toPath()));
        assertTrue(store.list(proof->true).isEmpty());f.archive.close();
    }

    @Test public void promotionRecoversAfterRenameBeforePayloadDirectorySync() throws Exception {
        Fixture f=fixture();LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        File root=new File(f.root,"materials");FaultFiles files=new FaultFiles();files.failPayloadSync=true;
        RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(root,files,new SyntheticMedia());
        RestoredLibraryMaterialStore.Prepared p=store.prepareVideo(tx,group,id,"note-restored",d.itemId,f.archive);
        File finalPayload=new File(new File(root,"payloads"),id+".mp4");
        try{store.promote(p);fail("sync failure hidden");}catch(IOException expected){assertEquals("INJECTED_PAYLOAD_DIR_SYNC",expected.getMessage());}
        assertTrue(finalPayload.isFile());assertFalse(new File(p.intent.getParentFile(),"payload-"+id+".bin").exists());
        RestoredLibraryMaterialStore.Record row=store.promote(p);assertEquals(d.sha256,row.sha256);
        assertTrue(store.list(proof->true).size()==1);f.archive.close();
    }

    @Test public void freshPrepareAfterRenameSyncFailureResumesFromDurableIntentAndRecord() throws Exception {
        Fixture f=fixture();LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        File root=new File(f.root,"materials");FaultFiles files=new FaultFiles();files.failPayloadSync=true;
        RestoredLibraryMaterialStore first=new RestoredLibraryMaterialStore(root,files,new SyntheticMedia());
        RestoredLibraryMaterialStore.Prepared original=first.prepareVideo(tx,group,id,"note-restored",d.itemId,f.archive);
        RestoredLibraryMaterialStore.CommitProof expected=original.commitProof();
        try{first.promote(original);fail("injected post-rename sync failure hidden");}
        catch(IOException failure){assertEquals("INJECTED_PAYLOAD_DIR_SYNC",failure.getMessage());}

        RestoredLibraryMaterialStore restarted=new RestoredLibraryMaterialStore(root,new TestFiles(),new SyntheticMedia());
        RestoredLibraryMaterialStore.Prepared fresh=restarted.prepareVideo(tx,group,id,"note-restored",d.itemId,f.archive);
        assertEquals(expected.bindingSha256,fresh.commitProof().bindingSha256);
        RestoredLibraryMaterialStore.Record recovered=restarted.promote(fresh);
        assertEquals(expected.bindingSha256,RestoredLibraryMaterialStore.proofFor(recovered).bindingSha256);
        assertEquals(1,restarted.list(proof->proof.bindingSha256.equals(expected.bindingSha256)).size());
        f.archive.close();
    }

    @Test public void freshPrepareAfterCompletedPromotionReusesExactSemanticRecordAndToken() throws Exception {
        Fixture f=fixture();LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        File root=new File(f.root,"materials");RestoredLibraryMaterialStore first=store(f.root);
        RestoredLibraryMaterialStore.Prepared original=first.prepareVideo(tx,group,id,"note-restored",d.itemId,f.archive);
        RestoredLibraryMaterialStore.Record initial=first.promote(original);
        RestoredLibraryMaterialStore.CommitProof expected=RestoredLibraryMaterialStore.proofFor(initial);
        File pendingDir=new File(new File(new File(root,"pending"),tx),group);
        File exactStage=new File(pendingDir,"payload-"+id+"-"+initial.promotionToken+".tmp");
        Files.copy(new File(new File(root,"payloads"),id+".mp4").toPath(),exactStage.toPath());

        RestoredLibraryMaterialStore restarted=new RestoredLibraryMaterialStore(root,new TestFiles(),new SyntheticMedia());
        RestoredLibraryMaterialStore.Prepared fresh=restarted.prepareVideo(tx,group,id,"note-restored",d.itemId,f.archive);
        assertEquals(expected.bindingSha256,fresh.commitProof().bindingSha256);
        assertFalse(exactStage.exists());
        RestoredLibraryMaterialStore.Record again=restarted.promote(fresh);
        assertEquals(expected.bindingSha256,RestoredLibraryMaterialStore.proofFor(again).bindingSha256);
        assertEquals(1,restarted.list(proof->proof.bindingSha256.equals(expected.bindingSha256)).size());
        f.archive.close();
    }

    @Test public void failedMediaValidationLeavesOnlyHiddenOwnedIntent() throws Exception {
        Fixture f=fixture();
        RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new TestFiles(),(kind,file)->{throw new IOException("SYNTHETIC_MEDIA_REJECT");});
        LibraryBackupManifest.CoverPreset d=f.archive.manifest().coverPresets.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        try {store.preparePreset(tx,group,id,d.itemId,f.archive);fail("invalid media accepted");}
        catch(IOException expected){assertEquals("SYNTHETIC_MEDIA_REJECT",expected.getMessage());}
        assertTrue(store.list(proof->true).isEmpty());
        f.archive.close();
    }

    @Test public void interruptedCopyWithExactIntentCanRetryAfterOwnedPayloadWasRemoved() throws Exception {
        Fixture f=fixture();LibraryBackupManifest.CoverPreset d=f.archive.manifest().coverPresets.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        RestoredLibraryMaterialStore rejecting=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new TestFiles(),(kind,file)->{throw new IOException("SYNTHETIC_MEDIA_REJECT");});
        try {rejecting.preparePreset(tx,group,id,d.itemId,f.archive);fail("invalid media accepted");}
        catch(IOException expected){assertEquals("SYNTHETIC_MEDIA_REJECT",expected.getMessage());}
        RestoredLibraryMaterialStore retry=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new TestFiles(),new SyntheticMedia());
        RestoredLibraryMaterialStore.Prepared prepared=retry.preparePreset(tx,group,id,d.itemId,f.archive);
        assertEquals(id,retry.promote(prepared).localId);
        assertEquals(1,retry.list(proof->true).size());
        f.archive.close();
    }

    @Test public void everyVisibilityAndReadPathRequiresAnExplicitGate() throws Exception {
        Fixture f=fixture();RestoredLibraryMaterialStore store=store(f.root);
        try {store.list(null);fail("list without gate accepted");}catch(IOException expected){assertEquals("MATERIAL_COMMIT_GATE_REQUIRED",expected.getMessage());}
        try {store.readVault(UUID.randomUUID().toString(),null);fail("read without gate accepted");}catch(IOException expected){assertEquals("MATERIAL_COMMIT_GATE_REQUIRED",expected.getMessage());}
        try {store.copyForShare(UUID.randomUUID().toString(),new File(temp.getRoot(),"x"),null);fail("copy without gate accepted");}catch(IOException expected){assertEquals("MATERIAL_COMMIT_GATE_REQUIRED",expected.getMessage());}
        f.archive.close();
    }

    @Test public void malformedRowIsReportedWithoutHidingOtherCommittedMaterialsAndGateErrorsPropagate() throws Exception {
        Fixture f=fixture();RestoredLibraryMaterialStore store=store(f.root);
        LibraryBackupManifest.CoverPreset preset=f.archive.manifest().coverPresets.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        RestoredLibraryMaterialStore.Record good=store.promote(store.preparePreset(tx,group,id,preset.itemId,f.archive));
        File recordRoot=new File(f.root,"materials/records");
        File corrupt=new File(recordRoot,UUID.randomUUID().toString()+".json");
        Files.write(corrupt.toPath(),"{\"schema_version\":1,\"transaction_id\":\"broken\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        RestoredLibraryMaterialStore.CommitProof goodProof=RestoredLibraryMaterialStore.proofFor(good);
        RestoredLibraryMaterialStore.ListResult result=store.listDetailed(proof->proofKey(proof).equals(proofKey(goodProof)));
        assertEquals(1,result.records.size());assertEquals(good.localId,result.records.get(0).localId);
        assertEquals(Integer.valueOf(1),result.diagnostics.get("material_record_fields"));
        try {store.listDetailed(proof->{throw new IOException("JOURNAL_UNAVAILABLE");});fail("gate failure was hidden");}
        catch(IOException expected){assertEquals("JOURNAL_UNAVAILABLE",expected.getMessage());}
        f.archive.close();
    }

    @Test public void persistentPublishLockIsIgnoredOnlyWhenItMatchesStrictLockShape() throws Exception {
        Fixture f=fixture();RestoredLibraryMaterialStore store=store(f.root);
        LibraryBackupManifest.CoverPreset preset=f.archive.manifest().coverPresets.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        RestoredLibraryMaterialStore.Record row=store.promote(store.preparePreset(tx,group,id,preset.itemId,f.archive));
        RestoredLibraryMaterialStore.CommitProof proof=RestoredLibraryMaterialStore.proofFor(row);
        File recordRoot=new File(f.root,"materials/records");String targetName=id+".json";
        String lockHash=hex(java.security.MessageDigest.getInstance("SHA-256").digest(targetName.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        File validLock=new File(recordRoot,".padnote-publish-"+lockHash+".lock");Files.createFile(validLock.toPath());
        Files.setPosixFilePermissions(validLock.toPath(),java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        RestoredLibraryMaterialStore.ListResult clean=store.listDetailed(candidate->proofKey(candidate).equals(proofKey(proof)));
        assertEquals(1,clean.records.size());assertTrue("valid durable publication locks are not material-row corruption: "+clean.diagnostics,clean.diagnostics.isEmpty());
        File unknownHashLock=new File(recordRoot,".padnote-publish-"+repeat('b',64)+".lock");Files.createFile(unknownHashLock.toPath());
        Files.setPosixFilePermissions(unknownHashLock.toPath(),java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        File nonemptyLock=new File(recordRoot,".padnote-publish-"+repeat('a',64)+".lock");Files.write(nonemptyLock.toPath(),new byte[]{1});
        String malformedName=UUID.randomUUID().toString()+".json";Files.write(new File(recordRoot,malformedName).toPath(),"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String malformedHash=hex(java.security.MessageDigest.getInstance("SHA-256").digest(malformedName.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        File malformedRecordLock=new File(recordRoot,".padnote-publish-"+malformedHash+".lock");Files.createFile(malformedRecordLock.toPath());
        Files.setPosixFilePermissions(malformedRecordLock.toPath(),java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        RestoredLibraryMaterialStore.ListResult corrupt=store.listDetailed(candidate->proofKey(candidate).equals(proofKey(proof)));
        assertEquals(Integer.valueOf(3),corrupt.diagnostics.get("record_name_invalid"));assertEquals(Integer.valueOf(1),corrupt.diagnostics.get("material_record_fields"));
        f.archive.close();
    }

    @Test public void commitProofBindsCurrentAssociationAndHistoricalSourceIndependently() throws Exception {
        Fixture f=fixture();RestoredLibraryMaterialStore store=store(f.root);
        LibraryBackupManifest.VideoAttachment descriptor=f.archive.manifest().videoAttachments.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString();
        RestoredLibraryMaterialStore.Record row=promote(store,f.archive,f.archive.manifest(),tx,group,
                RestoredLibraryMaterialStore.Kind.VIDEO,UUID.randomUUID().toString(),"new-local-note-id",descriptor);
        assertEquals("restored_archive",row.originKind);
        assertNotEquals(descriptor.sourceNoteId,row.currentLocalNoteId);
        assertEquals(descriptor.sourceNoteId,row.historicalSourceNoteId);
        assertEquals(descriptor.taskId,row.taskId); // Historical provenance is retained as data only.

        RestoredLibraryMaterialStore.CommitProof proof=RestoredLibraryMaterialStore.proofFor(row);
        RestoredLibraryMaterialStore.Record changedAssociation=copyRecord(row,"different-local-note-id",row.historicalSourceNoteId);
        RestoredLibraryMaterialStore.Record changedHistory=copyRecord(row,row.currentLocalNoteId,"different-history-id");
        assertNotEquals(proof.bindingSha256,RestoredLibraryMaterialStore.proofFor(changedAssociation).bindingSha256);
        assertNotEquals(proof.bindingSha256,RestoredLibraryMaterialStore.proofFor(changedHistory).bindingSha256);
        assertEquals(proof.bindingSha256,RestoredLibraryMaterialStore.proofFor(row).bindingSha256);
        assertEquals(1,store.list(candidate->proofKey(candidate).equals(proofKey(proof))).size());
        assertTrue(store.list(candidate->candidate.currentLocalNoteId.equals("different-local-note-id")).isEmpty());
        f.archive.close();
    }

    @Test public void corruptVaultRowDoesNotHideOtherCommittedKinds() throws Exception {
        Fixture f=fixture();RestoredLibraryMaterialStore store=store(f.root);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString();
        RestoredLibraryMaterialStore.Record vault=promote(store,f.archive,f.archive.manifest(),tx,group,
                RestoredLibraryMaterialStore.Kind.VAULT,UUID.randomUUID().toString(),null,
                f.archive.manifest().vaultEntries.get(1));
        RestoredLibraryMaterialStore.Record video=promote(store,f.archive,f.archive.manifest(),tx,group,
                RestoredLibraryMaterialStore.Kind.VIDEO,UUID.randomUUID().toString(),null,
                f.archive.manifest().videoAttachments.get(1));
        File vaultPayload=new File(new File(f.root,"materials/payloads"),vault.localId+".json");
        Files.write(vaultPayload.toPath(),"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        RestoredLibraryMaterialStore.ListResult listed=store.listDetailed(proof->
                proof.transactionId.equals(tx)&&proof.groupId.equals(group));
        assertEquals(1,listed.records.size());
        assertEquals(video.localId,listed.records.get(0).localId);
        assertEquals(Integer.valueOf(1),listed.diagnostics.get("material_file_invalid"));
        try {store.readVault(vault.localId,proof->true);fail("corrupt vault payload was readable");}
        catch(IOException expected){assertEquals("MATERIAL_FILE_INVALID",expected.getMessage());}
        f.archive.close();
    }

    @Test public void rollbackRequiresExactAssociationAndHistoricalBinding() throws Exception {
        for(String field:new String[]{"current_local_note_id","historical_source_note_id"}) {
            Fixture f=fixture();RestoredLibraryMaterialStore store=store(f.root);
            LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);
            String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
            RestoredLibraryMaterialStore.Prepared prepared=store.prepareVideo(tx,group,id,"note-restored",d.itemId,f.archive);
            RestoredLibraryMaterialStore.CommitProof proof=prepared.commitProof();
            RestoredLibraryMaterialStore.Record row=store.promote(prepared);
            File recordFile=new File(new File(f.root,"materials/records"),id+".json");
            String original=new String(Files.readAllBytes(recordFile.toPath()),java.nio.charset.StandardCharsets.UTF_8);
            String oldValue="current_local_note_id".equals(field)?row.currentLocalNoteId:row.historicalSourceNoteId;
            String needle="\""+field+"\":\""+oldValue+"\"";
            assertTrue(original.contains(needle));
            String modified=original.replace(needle,"\""+field+"\":\"tampered-history-association\"");
            assertNotEquals(original,modified);Files.write(recordFile.toPath(),modified.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try{store.rollback(proof,candidate->false,pendingGroup(true));fail("rollback accepted altered "+field);}
            catch(IOException expected){assertEquals("MATERIAL_ROLLBACK_OWNERSHIP_MISMATCH",expected.getMessage());}
            assertTrue(recordFile.isFile());
            assertTrue(new File(new File(f.root,"materials/payloads"),id+".mp4").isFile());
            f.archive.close();
        }
    }

    @Test public void rollbackIsIdempotentOnlyWhenAllExpectedStateIsAbsent() throws Exception {
        Fixture f=fixture();LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        RestoredLibraryMaterialStore store=store(f.root);
        RestoredLibraryMaterialStore.Prepared p=store.prepareVideo(tx,group,id,"note-restored",d.itemId,f.archive);
        RestoredLibraryMaterialStore.CommitProof proof=p.commitProof();
        File record=new File(new File(f.root,"materials/records"),id+".json");
        File finalPayload=new File(new File(f.root,"materials/payloads"),id+".mp4");
        Files.delete(p.intent.toPath());
        try {store.rollback(proof,candidate->false,pendingGroup(true));fail("rollback reported success with payload residue");}
        catch(IOException expected){assertEquals("MATERIAL_ROLLBACK_RECOVERY_NEEDED",expected.getMessage());}
        assertTrue(p.payload.isFile());
        f.archive.close();

        Fixture clean=fixture();LibraryBackupManifest.VideoAttachment cleanDescriptor=clean.archive.manifest().videoAttachments.get(0);
        RestoredLibraryMaterialStore cleanStore=store(clean.root);
        String cleanTx=UUID.randomUUID().toString(),cleanGroup=UUID.randomUUID().toString(),cleanId=UUID.randomUUID().toString();
        RestoredLibraryMaterialStore.Prepared cleanPrepared=cleanStore.prepareVideo(cleanTx,cleanGroup,cleanId,"note-restored",cleanDescriptor.itemId,clean.archive);
        RestoredLibraryMaterialStore.CommitProof cleanProof=cleanPrepared.commitProof();
        cleanStore.promote(cleanPrepared);
        cleanStore.rollback(cleanProof,candidate->false,pendingGroup(true));
        assertFalse(new File(new File(clean.root,"materials/records"),cleanId+".json").exists());
        assertFalse(new File(new File(clean.root,"materials/payloads"),cleanId+".mp4").exists());
        cleanStore.rollback(cleanProof,candidate->false,pendingGroup(true));
        clean.archive.close();
    }

    @Test public void malformedRollbackMetadataWithResidualPayloadNeverLooksLikeNoop() throws Exception {
        Fixture f=fixture();LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        RestoredLibraryMaterialStore store=store(f.root);
        RestoredLibraryMaterialStore.Prepared p=store.prepareVideo(tx,group,id,"note-restored",d.itemId,f.archive);
        RestoredLibraryMaterialStore.CommitProof proof=p.commitProof();
        File record=new File(new File(f.root,"materials/records"),id+".json");
        Files.write(record.toPath(),"not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(p.intent.toPath(),"not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {store.rollback(proof,candidate->false,pendingGroup(true));fail("malformed metadata was treated as absent");}
        catch(IOException expected){assertEquals("MATERIAL_ROLLBACK_RECOVERY_NEEDED",expected.getMessage());}
        assertTrue(record.isFile());assertTrue(p.intent.isFile());assertTrue(p.payload.isFile());
        f.archive.close();
    }

    @Test public void shareTempCleanupFailureIsReportedAndDoesNotMaskPrimaryFailure() throws Exception {
        Fixture f=fixture();LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);
        String tx=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        FaultFiles files=new FaultFiles();final boolean[] rejectShare={false};
        RestoredLibraryMaterialStore.MediaValidator media=(kind,file)->{
            if(rejectShare[0])throw new IOException("SYNTHETIC_MEDIA_REJECT");
            new SyntheticMedia().validate(kind,file);
        };
        RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(new File(f.root,"materials"),files,media);
        RestoredLibraryMaterialStore.Record row=store.promote(store.prepareVideo(tx,group,id,"note-restored",d.itemId,f.archive));
        rejectShare[0]=true;files.failShareTempUnlink=true;
        File destination=new File(temp.getRoot(),"share.mp4");
        try {store.copyForShare(row.localId,destination,proof->true);fail("share cleanup failure was reported as success");}
        catch(IOException expected) {
            assertEquals("SYNTHETIC_MEDIA_REJECT",expected.getMessage());
            assertEquals(1,expected.getSuppressed().length);
            assertEquals("MATERIAL_SHARE_TEMP_CLEANUP_FAILED",expected.getSuppressed()[0].getMessage());
        }
        File[] leftovers=temp.getRoot().listFiles((dir,name)->name.startsWith(".material-share-"));
        assertNotNull(leftovers);assertEquals(1,leftovers.length);assertFalse(destination.exists());
        f.archive.close();
    }

    private static RestoredLibraryMaterialStore.Record copyRecord(RestoredLibraryMaterialStore.Record r,
            String currentNote,String historicalSource) {
        return new RestoredLibraryMaterialStore.Record(r.transactionId,r.groupId,r.localId,r.kind,r.sourceItemId,
                currentNote,r.sourceState,historicalSource,r.sourceRevisionMs,r.sourceRevisionPrecisionMs,
                r.createdAtMs,r.byteLength,r.sha256,r.mediaType,r.displayName,r.digestKind,r.offlineState,
                r.originKind,r.sourceOriginKind,r.sourceBundleSha256,r.taskPayloadSha256,r.taskId,r.remoteTaskId,
                r.connectionId,r.connectionRevision,r.connectionKind,r.transport,r.bridgeId,r.instanceId,
                r.certificateSha256,r.artifactId,r.title,r.promotionToken);
    }

    private RestoredLibraryMaterialStore.Record promote(RestoredLibraryMaterialStore store,
            LibraryBackupArchive.StagedArchive archive,LibraryBackupManifest manifest,String tx,String group,
            RestoredLibraryMaterialStore.Kind kind,String id,String note,Object descriptor)throws Exception {
        String itemId=descriptor instanceof LibraryBackupManifest.VaultEntry?((LibraryBackupManifest.VaultEntry)descriptor).itemId:
                descriptor instanceof LibraryBackupManifest.VideoAttachment?((LibraryBackupManifest.VideoAttachment)descriptor).itemId:
                        ((LibraryBackupManifest.CoverPreset)descriptor).itemId;
        RestoredLibraryMaterialStore.Prepared prepared=kind==RestoredLibraryMaterialStore.Kind.VAULT
                ?store.prepareVault(tx,group,id,note,itemId,archive)
                :kind==RestoredLibraryMaterialStore.Kind.VIDEO
                ?store.prepareVideo(tx,group,id,note,itemId,archive)
                :store.preparePreset(tx,group,id,itemId,archive);
        return store.promote(prepared);
    }

    private RestoredLibraryMaterialStore store(File root){return new RestoredLibraryMaterialStore(new File(root,"materials"),new TestFiles(),new SyntheticMedia());}
    private static RestoredLibraryMaterialStore.PendingRollbackGate pendingGroup(boolean pending) {
        return proof -> new RestoredLibraryMaterialStore.PendingRollbackPermit() {
            private boolean closed;
            public boolean stillPending(RestoredLibraryMaterialStore.CommitProof actual) {
                return !closed&&pending&&proofKey(actual).equals(proofKey(proof));
            }
            public void close(){closed=true;}
        };
    }
    private static String proofKey(RestoredLibraryMaterialStore.CommitProof proof){return proof.transactionId+"/"+proof.groupId+"/"+proof.localId+"/"+proof.kind+"/"+proof.payloadSha256+"/"+proof.bindingSha256+"/"+proof.currentLocalNoteId;}
    private Fixture fixture()throws Exception {
        File base=temp.newFolder();File zip=fixtureZip();
        LibraryBackupArchive.StagedArchive staged=LibraryBackupArchive.stage(zip,base,null,null,new TestFiles());
        return new Fixture(base,staged);
    }
    private static File fixtureZip()throws IOException {
        File cursor=new File(System.getProperty("user.dir")).getAbsoluteFile();
        String relative="docs/fixtures/library-backup-r1/application-valid/archive/valid-library.zip";
        for(int i=0;i<6&&cursor!=null;i++,cursor=cursor.getParentFile()) {
            File candidate=new File(cursor,relative);
            if(candidate.isFile())return candidate;
        }
        throw new IOException("APPLICATION_FIXTURE_MISSING");
    }
    private static final class Fixture {final File root;final LibraryBackupArchive.StagedArchive archive;Fixture(File r,LibraryBackupArchive.StagedArchive a){root=r;archive=a;}}

    private static final class SyntheticMedia implements RestoredLibraryMaterialStore.MediaValidator {
        public void validate(RestoredLibraryMaterialStore.Kind kind,File file)throws IOException {
            byte[] head=new byte[12];try(FileInputStream in=new FileInputStream(file)){int n=in.read(head);if(kind==RestoredLibraryMaterialStore.Kind.COVER_PRESET&&(n<8||head[0]!=(byte)137||head[1]!=80||head[2]!=78||head[3]!=71))throw new IOException("TEST_PNG_INVALID");if(kind==RestoredLibraryMaterialStore.Kind.VIDEO&&(n<12||head[4]!='f'||head[5]!='t'||head[6]!='y'||head[7]!='p'))throw new IOException("TEST_VIDEO_INVALID");}
        }
    }

    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
    private static String repeat(char value,int count){StringBuilder out=new StringBuilder(count);for(int i=0;i<count;i++)out.append(value);return out.toString();}

    private static class TestFiles implements LibraryBackupArchive.SafeFiles {
        private static final java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.locks.ReentrantLock> LOCKS=new java.util.concurrent.ConcurrentHashMap<>();
        public LibraryBackupArchive.SafeFiles.PublishLock lockPublish(File destination)throws IOException {
            java.util.concurrent.locks.ReentrantLock lock=LOCKS.computeIfAbsent(destination.getAbsolutePath(),key->new java.util.concurrent.locks.ReentrantLock());lock.lock();return lock::unlock;
        }
        @Override public boolean isPersistentPublishLockArtifact(File path)throws IOException {
            if(path==null||!path.getName().matches("\\.padnote-publish-[0-9a-f]{64}\\.lock"))return false;
            LibraryBackupArchive.FileIdentity id=inspect(path,false);
            java.util.Set<java.nio.file.attribute.PosixFilePermission> expected=java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            return id.links==1&&id.size==0&&Files.getPosixFilePermissions(path.toPath(),LinkOption.NOFOLLOW_LINKS).equals(expected);
        }
        public void replaceOwnedMarker(File from,File to,byte[] marker,LibraryBackupArchive.FileIdentity markerIdentity)throws IOException {
            LibraryBackupArchive.FileIdentity before=inspect(to,false);
            if(!before.equals(markerIdentity)||!java.util.Arrays.equals(Files.readAllBytes(to.toPath()),marker))throw new IOException("PUBLISH_MARKER_CHANGED");
            try{Files.move(from.toPath(),to.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            catch(java.nio.file.AtomicMoveNotSupportedException e){throw new IOException("ATOMIC_RENAME_UNAVAILABLE");}
        }
        public LibraryBackupArchive.FileIdentity inspect(File path,boolean directory)throws IOException {
            return inspectLinks(path,directory,1);
        }
        @Override public LibraryBackupArchive.FileIdentity inspectOwnedPair(File path)throws IOException {return inspectLinks(path,false,2);}
        @Override public boolean isAbsentNoFollow(File path)throws IOException {try{Files.readAttributes(path.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return false;}catch(java.nio.file.NoSuchFileException missing){return true;}}
        private LibraryBackupArchive.FileIdentity inspectLinks(File path,boolean directory,long expectedLinks)throws IOException {
            BasicFileAttributes a=Files.readAttributes(path.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(a.isSymbolicLink()||directory&&!a.isDirectory()||!directory&&!a.isRegularFile())throw new IOException("TEST_FILE_TYPE");
            long links=1;try{links=((Number)Files.getAttribute(path.toPath(),"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue();}catch(Exception ignored){}
            if(!directory&&links!=expectedLinks)throw new IOException("MATERIAL_FILE_UNSAFE");
            long key=a.fileKey()==null?path.getAbsolutePath().hashCode():a.fileKey().hashCode();long time=a.lastModifiedTime().toMillis()*1_000_000L;
            return new LibraryBackupArchive.FileIdentity(1,key,a.size(),time,time,links,directory);
        }
        public void validatePrivateDirectory(File path)throws IOException {BasicFileAttributes a=Files.readAttributes(path.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!a.isDirectory()||a.isSymbolicLink())throw new IOException("TEST_DIRECTORY");}
        public LibraryBackupArchive.Seekable openRead(File path)throws IOException {LibraryBackupArchive.FileIdentity before=inspect(path,false);FileChannel channel=FileChannel.open(path.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);return new TestSeekable(path,channel,before);}
        @Override public LibraryBackupArchive.Seekable openOwnedPair(File path)throws IOException {LibraryBackupArchive.FileIdentity before=inspectOwnedPair(path);FileChannel channel=FileChannel.open(path.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);return new TestSeekable(path,channel,before);}
        public FileOutputStream createExclusive(File path)throws IOException {Files.createFile(path.toPath());return new FileOutputStream(path);}
        public void mkdirExclusive(File path)throws IOException {Files.createDirectory(path.toPath());}
        public void syncDirectory(File path)throws IOException {inspect(path,true);}
        public void deleteOwnedTree(File path)throws IOException {if(!Files.exists(path.toPath(),LinkOption.NOFOLLOW_LINKS))return;Files.walkFileTree(path.toPath(),new SimpleFileVisitor<Path>(){@Override public FileVisitResult visitFile(Path p,BasicFileAttributes a)throws IOException{Files.delete(p);return FileVisitResult.CONTINUE;}@Override public FileVisitResult postVisitDirectory(Path p,IOException e)throws IOException{if(e!=null)throw e;Files.delete(p);return FileVisitResult.CONTINUE;}});}
        public void linkNoReplace(File from,File to)throws IOException {Files.createLink(to.toPath(),from.toPath());}
        public void unlink(File path)throws IOException {Files.delete(path.toPath());}
        public boolean matchesFile(File path,LibraryBackupArchive.FileIdentity expected)throws IOException {try{return inspect(path,false).inode==expected.inode;}catch(IOException e){return false;}}
    }
    private static final class MissingAncestorSensitiveFiles extends TestFiles {
        @Override public boolean isAbsentNoFollow(File path)throws IOException {
            if(super.isAbsentNoFollow(path)){
                File parent=path.getAbsoluteFile().getParentFile();
                if(parent!=null&&!Files.exists(parent.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("MATERIAL_PATH_UNSAFE");
                return true;
            }
            return false;
        }
    }
    private static final class FaultFiles extends TestFiles {
        String failRenameTarget;boolean failedRename,lastRenameCompleted,failPayloadSync,failShareTempUnlink;
        @Override public void replaceOwnedMarker(File from,File to,byte[] marker,LibraryBackupArchive.FileIdentity identity)throws IOException {
            if(to.getName().equals(failRenameTarget)&&!failedRename){failedRename=true;throw new IOException("INJECTED_RENAME_BEFORE_MOVE");}
            super.replaceOwnedMarker(from,to,marker,identity);if(to.getName().equals(failRenameTarget))lastRenameCompleted=true;
        }
        @Override public void syncDirectory(File path)throws IOException {
            if(failPayloadSync&&"payloads".equals(path.getName())){failPayloadSync=false;throw new IOException("INJECTED_PAYLOAD_DIR_SYNC");}
            super.syncDirectory(path);
        }
        @Override public void unlink(File path)throws IOException {
            if(failShareTempUnlink&&path.getName().startsWith(".material-share-"))throw new IOException("INJECTED_SHARE_UNLINK");
            super.unlink(path);
        }
    }
    private static final class TestSeekable implements LibraryBackupArchive.Seekable {
        final File path;final FileChannel channel;final LibraryBackupArchive.FileIdentity identity;
        TestSeekable(File p,FileChannel c,LibraryBackupArchive.FileIdentity i){path=p;channel=c;identity=i;}
        public long size()throws IOException{return channel.size();}
        public void readFully(long pos,byte[] out,int offset,int length)throws IOException {ByteBuffer b=ByteBuffer.wrap(out,offset,length);while(b.hasRemaining()){int n=channel.read(b,pos);if(n<0)throw new IOException("TEST_EOF");if(n==0)continue;pos+=n;}}
        public LibraryBackupArchive.FileIdentity openedIdentity()throws IOException{return identity;}
        public LibraryBackupArchive.FileIdentity currentPathIdentity()throws IOException{return new TestFiles().inspectLinks(path,false,identity.links);}
        public void close()throws IOException{channel.close();}
    }
}
