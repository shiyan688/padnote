package com.padnote.android;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class LibraryMaterialGroupJournalTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();

    @Test public void stableGroupAndMemberIdsPersistBeforePrepareAndExactProofSetCommits() throws Exception {
        Fixture f=fixture();File root=new File(f.root,"journal");RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new ProcessFiles(),new Media());
        LibraryMaterialGroupJournal journal=new LibraryMaterialGroupJournal(root,new ProcessFiles(),store);
        String tx=UUID.randomUUID().toString();List<LibraryMaterialGroupJournal.MemberRequest> req=new ArrayList<>();req.add(new LibraryMaterialGroupJournal.MemberRequest("v-11111111111111111111111111111111",RestoredLibraryMaterialStore.Kind.VIDEO,"restored-note"));req.add(new LibraryMaterialGroupJournal.MemberRequest("v-22222222222222222222222222222222",RestoredLibraryMaterialStore.Kind.VIDEO,null));
        LibraryMaterialGroupJournal.GroupPlan first=journal.ensurePendingGroup(tx,"note-group:i-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",req);
        assertTrue(new File(new File(root,tx),"source-"+sha("note-group:i-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")+".json").isFile());
        assertTrue(new File(new File(new File(new File(root,tx),"groups"),first.groupId),"members.json").isFile());
        LibraryMaterialGroupJournal.GroupPlan restarted=new LibraryMaterialGroupJournal(root,new ProcessFiles(),store).ensurePendingGroup(tx,"note-group:i-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",req);
        assertEquals(first.groupId,restarted.groupId);assertEquals(first.members.get(0).localId,restarted.members.get(0).localId);
        try{journal.ensurePendingGroup(tx,"note-group:i-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",requests("v-33333333333333333333333333333333"));fail("changed membership reused mapping");}
        catch(IOException expected){assertEquals("MATERIAL_GROUP_REQUEST_CHANGED",expected.getMessage());}

        LibraryMaterialGroupJournal.GroupLease lease=journal.acquire(first);
        try {
            LibraryBackupManifest.VideoAttachment descriptor=f.archive.manifest().videoAttachments.get(0);
            LibraryMaterialGroupJournal.Member member=first.members.get(0);
            RestoredLibraryMaterialStore.Prepared prepared=store.prepareVideo(tx,first.groupId,member.localId,"restored-note",descriptor.itemId,f.archive);
            RestoredLibraryMaterialStore.CommitProof proof=prepared.commitProof();
            lease.registerProof(proof); // Durable expected proof precedes promotion.
            try{lease.commit();fail("incomplete group became committed");}catch(IOException expected){assertEquals("MATERIAL_GROUP_INCOMPLETE",expected.getMessage());}
            store.promote(prepared);
            // One group requires every member; register/promote the second independently.
            LibraryBackupManifest.VideoAttachment secondDescriptor=f.archive.manifest().videoAttachments.get(1);
            LibraryMaterialGroupJournal.Member secondMember=first.members.get(1);
            RestoredLibraryMaterialStore.Prepared second=store.prepareVideo(tx,first.groupId,secondMember.localId,null,secondDescriptor.itemId,f.archive);
            lease.registerProof(second.commitProof());store.promote(second);
            lease.commit();
            assertTrue(lease.commitGate().isCommitted(proof));
            RestoredLibraryMaterialStore.CommitProof foreign=new RestoredLibraryMaterialStore.CommitProof(tx,first.groupId,member.localId,
                    proof.kind,proof.payloadSha256,"0000000000000000000000000000000000000000000000000000000000000000",proof.currentLocalNoteId);
            assertFalse(lease.commitGate().isCommitted(foreign));
        } finally {lease.close();f.archive.close();}
        try(LibraryMaterialGroupJournal.GroupLease reopened=journal.acquire(tx,first.groupId)) {
            assertTrue(reopened.commitGate().isCommitted(reopenedProof(root,first.members.get(0).localId,tx,first.groupId)));
        }
    }

    @Test public void rollbackPermitSharesHeldLeaseAndRolledBackRequiresActualAbsence() throws Exception {
        Fixture f=fixture();RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new ProcessFiles(),new Media());
        LibraryMaterialGroupJournal journal=new LibraryMaterialGroupJournal(new File(f.root,"journal"),new ProcessFiles(),store);
        String tx=UUID.randomUUID().toString();LibraryMaterialGroupJournal.GroupPlan plan=journal.ensurePendingGroup(tx,"detached:vaults",requests("v-11111111111111111111111111111111"));
        LibraryMaterialGroupJournal.GroupLease lease=journal.acquire(plan);
        try {
            LibraryBackupManifest.VideoAttachment descriptor=f.archive.manifest().videoAttachments.get(1);
            RestoredLibraryMaterialStore.Prepared prepared=store.prepareVideo(tx,plan.groupId,plan.members.get(0).localId,null,descriptor.itemId,f.archive);
            RestoredLibraryMaterialStore.CommitProof proof=prepared.commitProof();lease.registerProof(proof);store.promote(prepared);
            lease.markRollbackInProgress();
            store.rollback(proof,lease.commitGate(),lease.pendingRollbackGate());
            lease.markRolledBack();
            assertFalse(lease.commitGate().isCommitted(proof));
        } finally {lease.close();f.archive.close();}
    }

    @Test public void rollbackCanFinishWithZeroOrPartialRegisteredProofsOnlyAfterEveryStableMemberNamespaceIsAbsent() throws Exception {
        Fixture f=fixture();RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new ProcessFiles(),new Media());
        LibraryMaterialGroupJournal journal=new LibraryMaterialGroupJournal(new File(f.root,"journal"),new ProcessFiles(),store);
        String tx=UUID.randomUUID().toString();String one="a-11111111111111111111111111111111",two="a-22222222222222222222222222222222";
        LibraryMaterialGroupJournal.GroupPlan zero=journal.ensurePendingGroup(tx,"detached:zero",requests(one,two));
        try(LibraryMaterialGroupJournal.GroupLease lease=journal.acquire(zero)) {lease.markRollbackInProgress();lease.markRolledBack();}

        String tx2=UUID.randomUUID().toString();LibraryMaterialGroupJournal.GroupPlan partial=journal.ensurePendingGroup(tx2,"detached:partial",requests(one,two));
        LibraryMaterialGroupJournal.Member member=partial.members.get(0);
        LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(1);
        try(LibraryMaterialGroupJournal.GroupLease lease=journal.acquire(partial)) {
            RestoredLibraryMaterialStore.Prepared prepared=store.prepareVideo(tx2,partial.groupId,member.localId,null,d.itemId,f.archive);
            RestoredLibraryMaterialStore.CommitProof proof=prepared.commitProof();lease.registerProof(proof);lease.markRollbackInProgress();
            store.rollback(proof,lease.commitGate(),lease.pendingRollbackGate());
            lease.markRolledBack();
        }
        f.archive.close();
    }

    @Test public void unregisteredMemberNamespaceResiduePreventsTerminalRollbackAndIsPreserved() throws Exception {
        Fixture f=fixture();RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new ProcessFiles(),new Media());
        LibraryMaterialGroupJournal journal=new LibraryMaterialGroupJournal(new File(f.root,"journal"),new ProcessFiles(),store);
        String tx=UUID.randomUUID().toString();LibraryMaterialGroupJournal.GroupPlan plan=journal.ensurePendingGroup(tx,"detached:residue",requests("a-11111111111111111111111111111111"));
        File payloadRoot=new File(f.root,"materials/payloads");assertTrue(payloadRoot.mkdirs());
        File residue=new File(payloadRoot,plan.members.get(0).localId+".mp4");
        try(FileOutputStream out=new FileOutputStream(residue)){out.write(new byte[]{1});}
        try(LibraryMaterialGroupJournal.GroupLease lease=journal.acquire(plan)) {
            lease.markRollbackInProgress();
            try{lease.markRolledBack();fail("unregistered member residue accepted");}
            catch(IOException expected){assertEquals("MATERIAL_GROUP_RECOVERY_NEEDED",expected.getMessage());}
            assertTrue(residue.isFile());
        }
        f.archive.close();
    }

    @Test public void nullMemberRequestIsRejectedAsProtocolError() throws Exception {
        Fixture f=fixture();RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new ProcessFiles(),new Media());
        LibraryMaterialGroupJournal journal=new LibraryMaterialGroupJournal(new File(f.root,"journal"),new ProcessFiles(),store);
        List<LibraryMaterialGroupJournal.MemberRequest> requests=new ArrayList<>();requests.add(null);
        try{journal.ensurePendingGroup(UUID.randomUUID().toString(),"detached:null",requests);fail("null request accepted");}
        catch(IOException expected){assertEquals("MATERIAL_GROUP_MEMBER_INVALID",expected.getMessage());}
        List<LibraryMaterialGroupJournal.MemberRequest> nullId=new ArrayList<>();nullId.add(new LibraryMaterialGroupJournal.MemberRequest(null,RestoredLibraryMaterialStore.Kind.VIDEO,null));nullId.add(new LibraryMaterialGroupJournal.MemberRequest("a-22222222222222222222222222222222",RestoredLibraryMaterialStore.Kind.VIDEO,null));
        try{journal.ensurePendingGroup(UUID.randomUUID().toString(),"detached:null-id",nullId);fail("null source item ID accepted");}
        catch(IOException expected){assertEquals("MATERIAL_GROUP_MEMBER_INVALID",expected.getMessage());}
        List<LibraryMaterialGroupJournal.MemberRequest> invalid=new ArrayList<>();invalid.add(new LibraryMaterialGroupJournal.MemberRequest("invalid",RestoredLibraryMaterialStore.Kind.VIDEO,null));invalid.add(new LibraryMaterialGroupJournal.MemberRequest("a-22222222222222222222222222222222",RestoredLibraryMaterialStore.Kind.VIDEO,null));
        try{journal.ensurePendingGroup(UUID.randomUUID().toString(),"detached:invalid-id",invalid);fail("invalid source item ID accepted");}
        catch(IOException expected){assertEquals("MATERIAL_GROUP_MEMBER_INVALID",expected.getMessage());}
        finally{f.archive.close();}
    }

    @Test public void directoryCreationSyncsParentAndCanResumeAfterParentSyncFailure() throws Exception {
        Fixture f=fixture();File root=new File(f.root,"journal");FaultProcessFiles files=new FaultProcessFiles();files.failDirectoryName="groups";
        RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new ProcessFiles(),new Media());
        LibraryMaterialGroupJournal journal=new LibraryMaterialGroupJournal(root,files,store);String tx=UUID.randomUUID().toString();
        try{journal.ensurePendingGroup(tx,"detached:dirsync",requests("a-11111111111111111111111111111111"));fail("parent fsync failure hidden");}
        catch(IOException expected){assertEquals("INJECTED_GROUP_PARENT_DIRSYNC",expected.getMessage());}
        LibraryMaterialGroupJournal.GroupPlan resumed=new LibraryMaterialGroupJournal(root,files,store).ensurePendingGroup(tx,"detached:dirsync",requests("a-11111111111111111111111111111111"));
        assertTrue(new File(new File(new File(new File(root,tx),"groups"),resumed.groupId),"proofs").isDirectory());
        f.archive.close();
    }

    @Test public void exactDurableMappingIsReusableButPartialMappingIsPreservedForDiagnosis() throws Exception {
        Fixture f=fixture();RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new ProcessFiles(),new Media());
        LibraryMaterialGroupJournal journal=new LibraryMaterialGroupJournal(new File(f.root,"journal"),new ProcessFiles(),store);
        String tx=UUID.randomUUID().toString(),key="detached:durable-map";List<LibraryMaterialGroupJournal.MemberRequest> req=requests("a-11111111111111111111111111111111");
        LibraryMaterialGroupJournal.GroupPlan first=journal.ensurePendingGroup(tx,key,req);
        LibraryMaterialGroupJournal.GroupPlan resumed=new LibraryMaterialGroupJournal(new File(f.root,"journal"),new ProcessFiles(),store).ensurePendingGroup(tx,key,req);
        assertEquals(first.groupId,resumed.groupId);assertEquals(first.members.get(0).localId,resumed.members.get(0).localId);
        File mapping=new File(new File(f.root,"journal"+File.separator+tx),"source-"+sha(key)+".json");
        byte[] partial=java.util.Arrays.copyOf(Files.readAllBytes(mapping.toPath()),7);Files.write(mapping.toPath(),partial);
        try{journal.ensurePendingGroup(tx,key,req);fail("partial mapping silently replaced");}
        catch(IOException expected){assertTrue(expected.getMessage().startsWith("MATERIAL_GROUP_")||expected.getMessage().startsWith("JSON_"));}
        assertArrayEquals(partial,Files.readAllBytes(mapping.toPath()));f.archive.close();
    }

    @Test public void durableIntentCanRecreateOnlyItsAbsentCommitTempAfterRestart() throws Exception {
        Fixture f=fixture();String tx=UUID.randomUUID().toString();File journalRoot=new File(f.root,"journal");
        FaultProcessFiles firstFiles=new FaultProcessFiles();firstFiles.failCommitTempCreate=true;firstFiles.failCommitRename=false;
        RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new ProcessFiles(),new Media());
        LibraryMaterialGroupJournal first=new LibraryMaterialGroupJournal(journalRoot,firstFiles,store);
        LibraryMaterialGroupJournal.GroupPlan plan=first.ensurePendingGroup(tx,"detached:temp-recreate",requests("a-11111111111111111111111111111111"));
        RestoredLibraryMaterialStore.CommitProof proof;
        try(LibraryMaterialGroupJournal.GroupLease lease=first.acquire(plan)) {
            LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(1);LibraryMaterialGroupJournal.Member member=plan.members.get(0);
            RestoredLibraryMaterialStore.Prepared p=store.prepareVideo(tx,plan.groupId,member.localId,null,d.itemId,f.archive);
            proof=p.commitProof();lease.registerProof(proof);store.promote(p);
            try{lease.commit();fail("injected temp create failure hidden");}catch(IOException expected){assertEquals("INJECTED_COMMIT_TEMP_CREATE",expected.getMessage());}
        }
        File groupDir=new File(new File(new File(journalRoot,tx),"groups"),plan.groupId);
        assertTrue(new File(groupDir,"committed.json").isFile());
        assertEquals(0,groupDir.listFiles((dir,name)->name.startsWith(".commit-")&&name.endsWith(".tmp")).length);
        LibraryMaterialGroupJournal restarted=new LibraryMaterialGroupJournal(journalRoot,firstFiles,store);
        try(LibraryMaterialGroupJournal.GroupLease lease=restarted.acquire(tx,plan.groupId)) {
            lease.commit();assertTrue(lease.commitGate().isCommitted(proof));
        }
        f.archive.close();
    }

    @Test public void completeReservationFsyncFailureCanResumeButMalformedReservationIsPreserved() throws Exception {
        Fixture f=fixture();String tx=UUID.randomUUID().toString();File journalRoot=new File(f.root,"journal");
        FaultProcessFiles firstFiles=new FaultProcessFiles();firstFiles.failCommitReservationDirectorySync=true;firstFiles.failCommitRename=false;
        RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new ProcessFiles(),new Media());
        LibraryMaterialGroupJournal first=new LibraryMaterialGroupJournal(journalRoot,firstFiles,store);
        LibraryMaterialGroupJournal.GroupPlan plan=first.ensurePendingGroup(tx,"detached:reservation-sync",requests("a-11111111111111111111111111111111"));
        RestoredLibraryMaterialStore.CommitProof proof;
        try(LibraryMaterialGroupJournal.GroupLease lease=first.acquire(plan)) {
            LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(1);LibraryMaterialGroupJournal.Member member=plan.members.get(0);
            RestoredLibraryMaterialStore.Prepared p=store.prepareVideo(tx,plan.groupId,member.localId,null,d.itemId,f.archive);
            proof=p.commitProof();lease.registerProof(proof);store.promote(p);
            try{lease.commit();fail("reservation sync failure hidden");}catch(IOException expected){assertEquals("INJECTED_COMMIT_RESERVATION_DIRSYNC",expected.getMessage());}
        }
        LibraryMaterialGroupJournal restarted=new LibraryMaterialGroupJournal(journalRoot,new ProcessFiles(),store);
        try(LibraryMaterialGroupJournal.GroupLease lease=restarted.acquire(tx,plan.groupId)) {lease.commit();assertTrue(lease.commitGate().isCommitted(proof));}
        f.archive.close();
    }

    @Test public void changedReservedTempIsPreservedAndRefused() throws Exception {
        Fixture f=fixture();String tx=UUID.randomUUID().toString();File journalRoot=new File(f.root,"journal");
        FaultProcessFiles firstFiles=new FaultProcessFiles();firstFiles.failCommitTempCreate=true;firstFiles.failCommitRename=false;
        RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new ProcessFiles(),new Media());
        LibraryMaterialGroupJournal first=new LibraryMaterialGroupJournal(journalRoot,firstFiles,store);
        LibraryMaterialGroupJournal.GroupPlan plan=first.ensurePendingGroup(tx,"detached:bad-temp",requests("a-11111111111111111111111111111111"));
        try(LibraryMaterialGroupJournal.GroupLease lease=first.acquire(plan)) {
            LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(1);LibraryMaterialGroupJournal.Member member=plan.members.get(0);
            RestoredLibraryMaterialStore.Prepared p=store.prepareVideo(tx,plan.groupId,member.localId,null,d.itemId,f.archive);lease.registerProof(p.commitProof());store.promote(p);
            try{lease.commit();fail("injected temp creation failure hidden");}catch(IOException expected){assertEquals("INJECTED_COMMIT_TEMP_CREATE",expected.getMessage());}
        }
        File groupDir=new File(new File(new File(journalRoot,tx),"groups"),plan.groupId);File reservation=new File(groupDir,"committed.json");
        Map<String,Object> reserve=LibraryBackupJson.parseCheckedObject(Files.readAllBytes(reservation.toPath()),8192);
        File unexpected=new File(groupDir,(String)reserve.get("temporary_file"));Files.write(unexpected.toPath(),new byte[]{9,8,7});
        LibraryMaterialGroupJournal restarted=new LibraryMaterialGroupJournal(journalRoot,new ProcessFiles(),store);
        try(LibraryMaterialGroupJournal.GroupLease lease=restarted.acquire(tx,plan.groupId)) {
            try{lease.commit();fail("changed commit temp was overwritten");}catch(IOException expected){assertEquals("MATERIAL_GROUP_COMMIT_RECOVERY_NEEDED",expected.getMessage());}
        }
        assertArrayEquals(new byte[]{9,8,7},Files.readAllBytes(unexpected.toPath()));f.archive.close();
    }

    @Test public void exactCompleteTempIsRefsyncedBeforeFreshLeasePublishesIt() throws Exception {
        Fixture f=fixture();String tx=UUID.randomUUID().toString();File journalRoot=new File(f.root,"journal");
        FaultProcessFiles firstFiles=new FaultProcessFiles();firstFiles.failCommitTempDirectorySync=true;firstFiles.failCommitRename=false;
        RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new ProcessFiles(),new Media());
        LibraryMaterialGroupJournal first=new LibraryMaterialGroupJournal(journalRoot,firstFiles,store);
        LibraryMaterialGroupJournal.GroupPlan plan=first.ensurePendingGroup(tx,"detached:temp-fsync",requests("a-11111111111111111111111111111111"));
        RestoredLibraryMaterialStore.CommitProof proof;
        try(LibraryMaterialGroupJournal.GroupLease lease=first.acquire(plan)) {
            LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(1);LibraryMaterialGroupJournal.Member member=plan.members.get(0);
            RestoredLibraryMaterialStore.Prepared p=store.prepareVideo(tx,plan.groupId,member.localId,null,d.itemId,f.archive);
            proof=p.commitProof();lease.registerProof(proof);store.promote(p);
            try{lease.commit();fail("injected directory sync failure hidden");}catch(IOException expected){assertEquals("INJECTED_COMMIT_TEMP_DIRSYNC",expected.getMessage());}
        }
        LibraryMaterialGroupJournal restarted=new LibraryMaterialGroupJournal(journalRoot,firstFiles,store);
        try(LibraryMaterialGroupJournal.GroupLease lease=restarted.acquire(tx,plan.groupId)) {
            lease.commit();assertTrue(lease.commitGate().isCommitted(proof));
        }
        assertTrue(firstFiles.existingFileSyncs>0);
        f.archive.close();
    }

    @Test public void freshLeaseRefsyncsAlreadyRenamedCommitMarkerAfterParentSyncFailure() throws Exception {
        Fixture f=fixture();String tx=UUID.randomUUID().toString();File journalRoot=new File(f.root,"journal");
        FaultProcessFiles firstFiles=new FaultProcessFiles();firstFiles.failCommitRename=false;firstFiles.failAfterFinalCommitDirectorySync=true;
        RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new ProcessFiles(),new Media());
        LibraryMaterialGroupJournal first=new LibraryMaterialGroupJournal(journalRoot,firstFiles,store);
        LibraryMaterialGroupJournal.GroupPlan plan=first.ensurePendingGroup(tx,"detached:commit-dir-sync",requests("a-11111111111111111111111111111111"));
        RestoredLibraryMaterialStore.CommitProof proof;
        try(LibraryMaterialGroupJournal.GroupLease lease=first.acquire(plan)) {
            LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(1);LibraryMaterialGroupJournal.Member member=plan.members.get(0);
            RestoredLibraryMaterialStore.Prepared p=store.prepareVideo(tx,plan.groupId,member.localId,null,d.itemId,f.archive);
            proof=p.commitProof();lease.registerProof(proof);store.promote(p);
            try{lease.commit();fail("post-rename parent fsync failure hidden");}catch(IOException expected){assertEquals("INJECTED_FINAL_COMMIT_DIRSYNC",expected.getMessage());}
        }
        File groupDir=new File(new File(new File(journalRoot,tx),"groups"),plan.groupId);
        assertTrue("atomic rename already installed complete marker",new File(groupDir,"committed.json").isFile());
        LibraryMaterialGroupJournal restarted=new LibraryMaterialGroupJournal(journalRoot,firstFiles,store);
        try(LibraryMaterialGroupJournal.GroupLease lease=restarted.acquire(tx,plan.groupId)) {lease.commit();assertTrue(lease.commitGate().isCommitted(proof));}
        assertTrue("fresh lease re-synced existing marker",firstFiles.existingFileSyncs>0);
        f.archive.close();
    }

    @Test public void committedMarkerRejectsUnknownOrChangedProofRows() throws Exception {
        Fixture f=fixture();RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new ProcessFiles(),new Media());
        LibraryMaterialGroupJournal journal=new LibraryMaterialGroupJournal(new File(f.root,"journal"),new ProcessFiles(),store);
        String tx=UUID.randomUUID().toString();List<LibraryMaterialGroupJournal.MemberRequest> memberRequests=new ArrayList<>();memberRequests.add(new LibraryMaterialGroupJournal.MemberRequest("v-11111111111111111111111111111111",RestoredLibraryMaterialStore.Kind.VIDEO,"local-note"));LibraryMaterialGroupJournal.GroupPlan plan=journal.ensurePendingGroup(tx,"note:i-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",memberRequests);
        try(LibraryMaterialGroupJournal.GroupLease lease=journal.acquire(plan)) {
            LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);LibraryMaterialGroupJournal.Member m=plan.members.get(0);
            RestoredLibraryMaterialStore.Prepared p=store.prepareVideo(tx,plan.groupId,m.localId,"local-note",d.itemId,f.archive);
            lease.registerProof(p.commitProof());store.promote(p);lease.commit();
            File proof=new File(new File(new File(new File(new File(f.root,"journal"),tx),"groups"),plan.groupId),"proofs/proof-"+m.localId+".json");
            byte[] old=Files.readAllBytes(proof.toPath());byte[] changed=old.clone();changed[changed.length-2]^=1;Files.write(proof.toPath(),changed);
            try{lease.commitGate().isCommitted(p.commitProof());fail("mutated registered proof remained authorized");}
            catch(IOException expected){assertTrue(expected.getMessage().startsWith("MATERIAL_GROUP_"));}
        } finally {f.archive.close();}
    }

    @Test public void groupCommitResumesItsExactAtomicReservationInFreshJournalInstance() throws Exception {
        Fixture f=fixture();String tx=UUID.randomUUID().toString();File journalRoot=new File(f.root,"journal");
        RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new ProcessFiles(),new Media());
        List<LibraryMaterialGroupJournal.MemberRequest> req=new ArrayList<>();req.add(new LibraryMaterialGroupJournal.MemberRequest("v-11111111111111111111111111111111",RestoredLibraryMaterialStore.Kind.VIDEO,"restored-note"));
        LibraryMaterialGroupJournal firstJournal=new LibraryMaterialGroupJournal(journalRoot,new FaultProcessFiles(),store);
        LibraryMaterialGroupJournal.GroupPlan plan=firstJournal.ensurePendingGroup(tx,"detached:one-video",req);
        RestoredLibraryMaterialStore.CommitProof proof;
        try(LibraryMaterialGroupJournal.GroupLease lease=firstJournal.acquire(plan)) {
            LibraryBackupManifest.VideoAttachment d=f.archive.manifest().videoAttachments.get(0);LibraryMaterialGroupJournal.Member member=plan.members.get(0);
            RestoredLibraryMaterialStore.Prepared prepared=store.prepareVideo(tx,plan.groupId,member.localId,"restored-note",d.itemId,f.archive);
            proof=prepared.commitProof();lease.registerProof(proof);store.promote(prepared);
            try{lease.commit();fail("injected commit marker rename failure was hidden");}
            catch(IOException expected){assertEquals("INJECTED_GROUP_COMMIT_RENAME",expected.getMessage());}
        }
        File groupDir=new File(new File(new File(journalRoot,tx),"groups"),plan.groupId);
        assertTrue(new File(groupDir,"committed.json").isFile());
        assertTrue(groupDir.listFiles((dir,name)->name.startsWith(".commit-")&&name.endsWith(".tmp")).length==1);
        LibraryMaterialGroupJournal restarted=new LibraryMaterialGroupJournal(journalRoot,new ProcessFiles(),store);
        try(LibraryMaterialGroupJournal.GroupLease lease=restarted.acquire(tx,plan.groupId)) {
            lease.commit();
            assertTrue(lease.commitGate().isCommitted(proof));
        }
        assertEquals(0,groupDir.listFiles((dir,name)->name.startsWith(".commit-")&&name.endsWith(".tmp")).length);
        f.archive.close();
    }

    @Test public void groupLockSerializesSeparateJvmAndReopensAfterOwnerExit() throws Exception {
        Fixture f=fixture();File journalRoot=new File(f.root,"journal");RestoredLibraryMaterialStore store=new RestoredLibraryMaterialStore(new File(f.root,"materials"),new ProcessFiles(),new Media());
        LibraryMaterialGroupJournal journal=new LibraryMaterialGroupJournal(journalRoot,new ProcessFiles(),store);
        String tx=UUID.randomUUID().toString();LibraryMaterialGroupJournal.GroupPlan plan=journal.ensurePendingGroup(tx,"detached:vaults",requests("v-11111111111111111111111111111111"));
        File ready=new File(f.root,"child-ready"),release=new File(f.root,"child-release");
        String java=new File(System.getProperty("java.home"),"bin/java").getAbsolutePath();
        Process child=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),getClass().getName(),"hold-lock",journalRoot.getAbsolutePath(),tx,plan.groupId,ready.getAbsolutePath(),release.getAbsolutePath()).redirectErrorStream(true).start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(!ready.isFile()&&child.isAlive()&&System.nanoTime()<deadline)Thread.sleep(10);
            assertTrue("child lock holder failed",ready.isFile());
            CountDownLatch acquired=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread waiter=new Thread(()->{try(LibraryMaterialGroupJournal.GroupLease ignored=journal.acquire(tx,plan.groupId)){acquired.countDown();}catch(Throwable e){failure.set(e);}});
            waiter.start();assertFalse("second process bypassed group lock",acquired.await(200,TimeUnit.MILLISECONDS));
            Files.write(release.toPath(),new byte[]{1});
            assertTrue("lease did not reopen after owner exit",acquired.await(5,TimeUnit.SECONDS));
            waiter.join(5000);assertFalse(waiter.isAlive());if(failure.get()!=null)throw new AssertionError(failure.get());
            assertTrue(child.waitFor(5,TimeUnit.SECONDS));assertEquals(0,child.exitValue());
        } finally {
            if(child.isAlive()){Files.write(release.toPath(),new byte[]{1});child.destroy();if(!child.waitFor(2,TimeUnit.SECONDS))child.destroyForcibly().waitFor(2,TimeUnit.SECONDS);}
            f.archive.close();
        }
    }

    public static void main(String[] args)throws Exception {
        if(args.length==0||!"hold-lock".equals(args[0]))return;
        File root=new File(args[1]);String tx=args[2],group=args[3];File ready=new File(args[4]),release=new File(args[5]);
        LibraryMaterialGroupJournal journal=new LibraryMaterialGroupJournal(root,new ProcessFiles(),new RestoredLibraryMaterialStore(new File(root,"unused-materials"),new ProcessFiles(),new Media()));
        try(LibraryMaterialGroupJournal.GroupLease ignored=journal.acquire(tx,group)) {
            Files.write(ready.toPath(),new byte[]{1});long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
            while(!release.exists()&&System.nanoTime()<deadline)Thread.sleep(10);
            if(!release.exists())System.exit(3);
        }
    }

    private static List<LibraryMaterialGroupJournal.MemberRequest> requests(String...ids) {
        List<LibraryMaterialGroupJournal.MemberRequest> out=new ArrayList<>();for(String id:ids)out.add(new LibraryMaterialGroupJournal.MemberRequest(id,RestoredLibraryMaterialStore.Kind.VIDEO,null));return out;
    }
    private static String sha(String s)throws Exception {byte[] b=java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder x=new StringBuilder();for(byte a:b)x.append(String.format("%02x",a&255));return x.toString();}
    private static String shaUnchecked(String s)throws IOException {try{return sha(s);}catch(Exception failure){throw new IOException("TEST_HASH_FAILURE");}}
    private static RestoredLibraryMaterialStore.CommitProof reopenedProof(File root,String local,String tx,String group)throws Exception {
        File p=new File(new File(new File(new File(new File(root,tx),"groups"),group),"proofs"),"proof-"+local+".json");
        java.util.Map<String,Object> m=LibraryBackupJson.parseCheckedObject(Files.readAllBytes(p.toPath()),65536);
        return new RestoredLibraryMaterialStore.CommitProof((String)m.get("transaction_id"),(String)m.get("group_id"),(String)m.get("local_id"),
                RestoredLibraryMaterialStore.Kind.valueOf((String)m.get("kind")),(String)m.get("payload_sha256"),(String)m.get("binding_sha256"),(String)m.get("current_local_note_id"));
    }
    private Fixture fixture()throws Exception {
        File root=temp.newFolder();File cursor=new File(System.getProperty("user.dir")).getAbsoluteFile();String relative="docs/fixtures/library-backup-r1/application-valid/archive/valid-library.zip";File zip=null;
        for(int i=0;i<6&&cursor!=null;i++,cursor=cursor.getParentFile()){File candidate=new File(cursor,relative);if(candidate.isFile()){zip=candidate;break;}}
        if(zip==null)throw new IOException("APPLICATION_FIXTURE_MISSING");
        LibraryBackupArchive.StagedArchive archive=LibraryBackupArchive.stage(zip,root,null,null,new TestFiles());return new Fixture(root,archive);
    }
    private static final class Fixture {final File root;final LibraryBackupArchive.StagedArchive archive;Fixture(File r,LibraryBackupArchive.StagedArchive a){root=r;archive=a;}}
    private static final class Media implements RestoredLibraryMaterialStore.MediaValidator {
        public void validate(RestoredLibraryMaterialStore.Kind kind,File file)throws IOException {
            if(kind==RestoredLibraryMaterialStore.Kind.VIDEO) {byte[] h=new byte[12];try(java.io.FileInputStream in=new java.io.FileInputStream(file)){if(in.read(h)<12||h[4]!='f'||h[5]!='t'||h[6]!='y'||h[7]!='p')throw new IOException("TEST_MEDIA_INVALID");}}
        }
    }
    private static class TestFiles implements LibraryBackupArchive.SafeFiles {
        public LibraryBackupArchive.SafeFiles.PublishLock lockPublish(File destination)throws IOException {throw new IOException("TEST_LOCK_UNAVAILABLE");}
        public void replaceOwnedMarker(File from,File to,byte[] marker,LibraryBackupArchive.FileIdentity markerIdentity)throws IOException {
            LibraryBackupArchive.FileIdentity before=inspect(to,false);
            if(!before.equals(markerIdentity)||!java.security.MessageDigest.isEqual(Files.readAllBytes(to.toPath()),marker))throw new IOException("TEST_MARKER_CHANGED");
            try{Files.move(from.toPath(),to.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            catch(java.nio.file.AtomicMoveNotSupportedException unsupported){throw new IOException("TEST_ATOMIC_RENAME_UNAVAILABLE");}
        }
        public LibraryBackupArchive.FileIdentity inspect(File path,boolean directory)throws IOException {BasicFileAttributes a=Files.readAttributes(path.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(a.isSymbolicLink()||directory&&!a.isDirectory()||!directory&&!a.isRegularFile())throw new IOException("TEST_TYPE");long links=1;try{links=((Number)Files.getAttribute(path.toPath(),"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue();}catch(Exception ignored){}if(!directory&&links!=1)throw new IOException("TEST_LINK");long inode=a.fileKey()==null?path.getAbsolutePath().hashCode():a.fileKey().hashCode(),time=a.lastModifiedTime().toMillis()*1_000_000L;return new LibraryBackupArchive.FileIdentity(1,inode,a.size(),time,time,links,directory);}
        public void validatePrivateDirectory(File p)throws IOException {BasicFileAttributes a=Files.readAttributes(p.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!a.isDirectory()||a.isSymbolicLink())throw new IOException("TEST_DIR");}
        public LibraryBackupArchive.Seekable openRead(File p)throws IOException {LibraryBackupArchive.FileIdentity before=inspect(p,false);FileChannel c=FileChannel.open(p.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);return new Seek(p,c,before);}
        public LibraryBackupArchive.FileIdentity inspectOwnedPair(File p)throws IOException{return inspect(p,false);}
        public LibraryBackupArchive.Seekable openOwnedPair(File p)throws IOException{return openRead(p);}
        public FileOutputStream createExclusive(File p)throws IOException {Files.createFile(p.toPath());return new FileOutputStream(p);}
        public void mkdirExclusive(File p)throws IOException {Files.createDirectory(p.toPath());}
        public void syncDirectory(File p)throws IOException {validatePrivateDirectory(p);}
        public void syncExistingFile(File p,LibraryBackupArchive.FileIdentity expected)throws IOException {
            LibraryBackupArchive.FileIdentity before=inspect(p,false);if(!before.equals(expected)||before.links!=1)throw new IOException("TEST_FILE_CHANGED");
            try(FileChannel channel=FileChannel.open(p.toPath(),StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}
            if(!before.equals(inspect(p,false)))throw new IOException("TEST_FILE_CHANGED");
        }
        public void deleteOwnedTree(File p)throws IOException {if(!Files.exists(p.toPath(),LinkOption.NOFOLLOW_LINKS))return;Files.walkFileTree(p.toPath(),new java.nio.file.SimpleFileVisitor<java.nio.file.Path>(){@Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path q,BasicFileAttributes a)throws IOException{Files.delete(q);return java.nio.file.FileVisitResult.CONTINUE;}@Override public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path q,IOException e)throws IOException{if(e!=null)throw e;Files.delete(q);return java.nio.file.FileVisitResult.CONTINUE;}});}
        public void linkNoReplace(File a,File b)throws IOException {Files.createLink(b.toPath(),a.toPath());}
        public void unlink(File p)throws IOException {Files.delete(p.toPath());}
        public boolean matchesFile(File p,LibraryBackupArchive.FileIdentity i)throws IOException {return inspect(p,false).inode==i.inode;}
        public boolean isAbsentNoFollow(File p)throws IOException {try{Files.readAttributes(p.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return false;}catch(java.nio.file.NoSuchFileException missing){return true;}}
        private static final class Seek implements LibraryBackupArchive.Seekable {final File path;final FileChannel c;final LibraryBackupArchive.FileIdentity id;Seek(File p,FileChannel c,LibraryBackupArchive.FileIdentity i){path=p;this.c=c;id=i;}public long size()throws IOException{return c.size();}public void readFully(long pos,byte[] b,int off,int len)throws IOException{ByteBuffer buffer=ByteBuffer.wrap(b,off,len);while(buffer.hasRemaining()){int n=c.read(buffer,pos);if(n<0)throw new IOException("TEST_EOF");pos+=n;}}public LibraryBackupArchive.FileIdentity openedIdentity(){return id;}public LibraryBackupArchive.FileIdentity currentPathIdentity()throws IOException{return new TestFiles().inspect(path,false);}public void close()throws IOException{c.close();}}
    }
    private static class ProcessFiles extends TestFiles {
        @Override public LibraryBackupArchive.SafeFiles.PublishLock lockPublish(File destination)throws IOException {
            File path=new File(destination.getParentFile(),".test-lock-"+shaUnchecked(destination.getName()));
            try {Files.createFile(path.toPath());}catch(java.nio.file.FileAlreadyExistsException ignored){}
            final FileChannel channel=FileChannel.open(path.toPath(),StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            final FileLock lock;
            try {lock=channel.lock();}catch(IOException|RuntimeException failure){channel.close();throw failure;}
            return ()->{try{lock.release();}finally{channel.close();}};
        }
    }
    private static final class FaultProcessFiles extends ProcessFiles {
        boolean failCommitRename=true;
        boolean failCommitTempCreate;
        boolean failCommitTempDirectorySync;
        boolean failCommitReservationDirectorySync;
        boolean failAfterFinalCommitDirectorySync;
        String failDirectoryName;
        int existingFileSyncs;
        @Override public FileOutputStream createExclusive(File p)throws IOException {
            if(failCommitTempCreate&&p.getName().startsWith(".commit-")&&p.getName().endsWith(".tmp")){failCommitTempCreate=false;throw new IOException("INJECTED_COMMIT_TEMP_CREATE");}
            return super.createExclusive(p);
        }
        @Override public void syncDirectory(File p)throws IOException {
            if(failDirectoryName!=null&&failDirectoryName.equals(p.getName())){String failed=failDirectoryName;failDirectoryName=null;throw new IOException("INJECTED_GROUP_PARENT_DIRSYNC");}
            if(failAfterFinalCommitDirectorySync&&p.getParentFile()!=null&&"groups".equals(p.getParentFile().getName())) {
                File committed=new File(p,"committed.json");
                if(committed.isFile()) {
                    try {if(LibraryBackupJson.parseCheckedObject(Files.readAllBytes(committed.toPath()),8192).containsKey("proofs")){failAfterFinalCommitDirectorySync=false;throw new IOException("INJECTED_FINAL_COMMIT_DIRSYNC");}}
                    catch(IOException failure){if("INJECTED_FINAL_COMMIT_DIRSYNC".equals(failure.getMessage()))throw failure;}
                }
            }
            if(failCommitReservationDirectorySync&&"groups".equals(p.getParentFile()==null?"":p.getParentFile().getName())&&new File(p,"committed.json").isFile()) {
                File[] children=p.listFiles((dir,name)->name.startsWith(".commit-")&&name.endsWith(".tmp"));
                if(children!=null&&children.length==0){failCommitReservationDirectorySync=false;throw new IOException("INJECTED_COMMIT_RESERVATION_DIRSYNC");}
            }
            if(failCommitTempDirectorySync&&p.getName().matches("[0-9a-f-]{36}")&&p.getParentFile()!=null&&"groups".equals(p.getParentFile().getName())) {
                File[] children=p.listFiles((dir,name)->name.startsWith(".commit-")&&name.endsWith(".tmp"));
                if(children!=null&&children.length==1){failCommitTempDirectorySync=false;throw new IOException("INJECTED_COMMIT_TEMP_DIRSYNC");}
            }
            super.syncDirectory(p);
        }
        @Override public void syncExistingFile(File p,LibraryBackupArchive.FileIdentity expected)throws IOException {existingFileSyncs++;super.syncExistingFile(p,expected);}
        @Override public void replaceOwnedMarker(File from,File to,byte[] marker,LibraryBackupArchive.FileIdentity identity)throws IOException {
            if("committed.json".equals(to.getName())&&failCommitRename){failCommitRename=false;throw new IOException("INJECTED_GROUP_COMMIT_RENAME");}
            super.replaceOwnedMarker(from,to,marker,identity);
        }
    }
}
