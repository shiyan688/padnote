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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class LibraryRestoreGroupV2Test {
    @Rule public TemporaryFolder temp=new TemporaryFolder();

    @Test public void stableMemberIdsSeparateArchiveIdsFromLocalObjectsAndOneMarkerGatesWholeGroup() throws Exception {
        File root=temp.newFolder("tx");Fs fs=new Fs();String tx=UUID.randomUUID().toString(),archive=hash("archive"),note="i-11111111111111111111111111111111",video="a-22222222222222222222222222222222";
        List<LibraryRestoreGroupV2.Request> requests=new ArrayList<>();
        requests.add(new LibraryRestoreGroupV2.Request(note,"r-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",null,LibraryRestoreGroupV2.Kind.NOTE,12,hash("note-source"),hash("note-semantic")));
        requests.add(new LibraryRestoreGroupV2.Request(note,"r-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",note,LibraryRestoreGroupV2.Kind.PDF,40,hash("pdf-source"),hash("pdf-semantic")));
        requests.add(new LibraryRestoreGroupV2.Request(video,"r-cccccccccccccccccccccccccccccccc",note,LibraryRestoreGroupV2.Kind.VIDEO,96,hash("video-source"),hash("video-semantic")));
        LibraryRestoreGroupV2 journal=new LibraryRestoreGroupV2(root,fs);LibraryRestoreGroupV2.Plan plan=journal.ensurePlan(tx,archive,"note:"+note,requests);
        LibraryRestoreGroupV2.Plan restarted=new LibraryRestoreGroupV2(root,fs).ensurePlan(tx,archive,"note:"+note,requests);
        assertEquals(plan.groupId,restarted.groupId);assertEquals(3,plan.members.size());
        LibraryRestoreGroupV2.Member n=plan.member(note,LibraryRestoreGroupV2.Kind.NOTE),pdf=plan.member(note,LibraryRestoreGroupV2.Kind.PDF),v=plan.member(video,LibraryRestoreGroupV2.Kind.VIDEO);
        assertTrue(n.memberId.matches("[0-9a-f-]{36}"));assertTrue(n.localObjectId.startsWith("note-"));assertNotEquals(n.memberId,n.localObjectId);
        assertEquals("r-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",n.sourceResourceId);assertEquals(n.localObjectId,pdf.localObjectId);assertEquals(n.localObjectId,pdf.currentLocalNoteId);
        assertNotEquals(v.memberId,v.localObjectId);assertEquals(n.localObjectId,v.currentLocalNoteId);
        try(LibraryRestoreGroupV2.Lease lease=journal.acquire(plan)) {
            LibraryRestoreGroupV2.MemberProof np=proof(plan,n,hash("note-installed"));lease.registerBeforeInstall(np);
            try { lease.commit((m,p)->{}); fail("partial group became visible"); }
            catch(IOException expected){assertEquals("RESTORE_V2_GROUP_INCOMPLETE",expected.getMessage());}
            LibraryRestoreGroupV2.MemberProof pp=proof(plan,pdf,hash("pdf-installed"));lease.registerBeforeInstall(pp);
            RestoredLibraryMaterialStore.CommitProof vp=new RestoredLibraryMaterialStore.CommitProof(tx,plan.groupId,v.localObjectId,
                    RestoredLibraryMaterialStore.Kind.VIDEO,v.payloadSha256,hash("video-store-binding"),v.currentLocalNoteId);
            LibraryRestoreGroupV2.MemberProof vproof=new LibraryRestoreGroupV2.MemberProof(v.memberId,tx,plan.groupId,v.localObjectId,v.currentLocalNoteId,v.kind,v.payloadSha256,v.semanticBindingSha256,vp.bindingSha256);
            lease.registerBeforeInstall(vproof);
            final int[] verified={0};lease.commit((m,p)->{assertEquals(plan.groupId,p.groupId);verified[0]++;});assertEquals(3,verified[0]);
            assertTrue(lease.gate().isCommitted(vp));
            RestoredLibraryMaterialStore.CommitProof wrong=new RestoredLibraryMaterialStore.CommitProof(tx,plan.groupId,v.localObjectId,
                    vp.kind,vp.payloadSha256,hash("wrong-binding"),vp.currentLocalNoteId);
            assertFalse(lease.gate().isCommitted(wrong));
            try{lease.registerBeforeInstall(vproof);fail("new proof accepted after group commit");}
            catch(IOException expected){assertEquals("RESTORE_V2_ALREADY_COMMITTED",expected.getMessage());}
        }
    }

    @Test public void changedSemanticBindingAndUnknownMembersFailClosedWithoutReplacingPlan() throws Exception {
        File root=temp.newFolder("tx");Fs fs=new Fs();String tx=UUID.randomUUID().toString(),archive=hash("archive"),item="v-11111111111111111111111111111111";
        LibraryRestoreGroupV2.Request original=new LibraryRestoreGroupV2.Request(item,"r-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",null,LibraryRestoreGroupV2.Kind.VAULT,5,hash("payload"),hash("semantic"));
        LibraryRestoreGroupV2 journal=new LibraryRestoreGroupV2(root,fs);LibraryRestoreGroupV2.Plan plan=journal.ensurePlan(tx,archive,"detached:"+item,java.util.Collections.singletonList(original));
        LibraryRestoreGroupV2.Request changed=new LibraryRestoreGroupV2.Request(item,"r-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",null,LibraryRestoreGroupV2.Kind.VAULT,5,hash("payload"),hash("changed"));
        try{journal.ensurePlan(tx,archive,"detached:"+item,java.util.Collections.singletonList(changed));fail("semantic edit reused durable plan");}
        catch(IOException expected){assertEquals("RESTORE_V2_REQUEST_CHANGED",expected.getMessage());}
        File proofs=new File(new File(new File(new File(root,tx),"groups"),plan.groupId),"proofs");Files.write(new File(proofs,"foreign.json").toPath(),new byte[]{1});
        try(LibraryRestoreGroupV2.Lease lease=journal.acquire(plan)) {
            try{lease.commit((m,p)->{});fail("unknown proof file ignored");}
            catch(IOException expected){assertEquals("RESTORE_V2_PROOF_UNKNOWN",expected.getMessage());}
        }
        assertArrayEquals(new byte[]{1},Files.readAllBytes(new File(proofs,"foreign.json").toPath()));
    }

    @Test public void partialRollbackNeedsVerifierAndPreservesUnknownMemberResidue() throws Exception {
        File root=temp.newFolder("tx");Fs fs=new Fs();String tx=UUID.randomUUID().toString(),item="c-11111111111111111111111111111111";
        LibraryRestoreGroupV2.Request request=new LibraryRestoreGroupV2.Request(item,"r-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",null,LibraryRestoreGroupV2.Kind.COVER_PRESET,5,hash("payload"),hash("semantic"));
        LibraryRestoreGroupV2 journal=new LibraryRestoreGroupV2(root,fs);LibraryRestoreGroupV2.Plan plan=journal.ensurePlan(tx,hash("archive"),"detached:"+item,java.util.Collections.singletonList(request));
        try(LibraryRestoreGroupV2.Lease lease=journal.acquire(plan)) {
            lease.markRollbackIntent();File unknown=new File(new File(new File(root,tx),"unknown"),plan.members.get(0).memberId);unknown.getParentFile().mkdirs();Files.write(unknown.toPath(),new byte[]{7});
            try{lease.markRolledBack(m->{if(unknown.exists())throw new IOException("RESTORE_V2_RESIDUE_UNVERIFIED");});fail("rollback ignored residue");}
            catch(IOException expected){assertEquals("RESTORE_V2_RESIDUE_UNVERIFIED",expected.getMessage());}
            assertTrue(unknown.isFile());
        }
    }

    @Test public void rollbackStateReadbackValidatesSourceGroupKeyAndResumes() throws Exception {
        File root=temp.newFolder("rollback-state-source-key");Fs fs=new Fs();String tx=UUID.randomUUID().toString(),item="c-88888888888888888888888888888888";
        LibraryRestoreGroupV2.Request request=new LibraryRestoreGroupV2.Request(item,"r-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",null,LibraryRestoreGroupV2.Kind.COVER_PRESET,5,hash("payload"),hash("semantic"));
        LibraryRestoreGroupV2 journal=new LibraryRestoreGroupV2(root,fs);LibraryRestoreGroupV2.Plan plan=journal.ensurePlan(tx,hash("archive"),"detached:"+item,java.util.Collections.singletonList(request));
        try(LibraryRestoreGroupV2.Lease lease=journal.acquire(plan)){
            lease.markRollbackIntent();assertEquals(LibraryRestoreGroupV2.RecoveryPhase.ROLLBACK_IN_PROGRESS,lease.recoveryStatus());
            lease.markRolledBack(member->{});assertEquals(LibraryRestoreGroupV2.RecoveryPhase.ROLLED_BACK,lease.recoveryStatus());
        }
        try(LibraryRestoreGroupV2.Lease resumed=journal.acquire(plan)){assertEquals(LibraryRestoreGroupV2.RecoveryPhase.ROLLED_BACK,resumed.recoveryStatus());}
    }

    @Test public void commitReservationResumeOnlyVerifiesAndNeverRegistersOrInstallsAgain() throws Exception {
        File root=temp.newFolder("reserved");Fs fs=new Fs();String tx=UUID.randomUUID().toString(),item="c-33333333333333333333333333333333";
        LibraryRestoreGroupV2.Request request=new LibraryRestoreGroupV2.Request(item,"r-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",null,LibraryRestoreGroupV2.Kind.COVER_PRESET,5,hash("payload"),hash("semantic"));
        final boolean[] injected={false};LibraryRestoreGroupV2 journal=new LibraryRestoreGroupV2(root,fs,group->{if(!injected[0]){injected[0]=true;throw new IOException("TEST_AFTER_RESERVATION");}});
        LibraryRestoreGroupV2.Plan plan=journal.ensurePlan(tx,hash("archive"),"detached:"+item,java.util.Collections.singletonList(request));
        LibraryRestoreGroupV2.Member member=plan.members.get(0);LibraryRestoreGroupV2.MemberProof proof=proof(plan,member,hash("storage"));
        try(LibraryRestoreGroupV2.Lease lease=journal.acquire(plan)){
            lease.registerBeforeInstall(proof);
            try{lease.commit((m,p)->{});fail("fault after durable reservation expected");}catch(IOException expected){assertEquals("TEST_AFTER_RESERVATION",expected.getMessage());}
            assertEquals(LibraryRestoreGroupV2.RecoveryPhase.COMMIT_RESERVED,lease.recoveryStatus());
            try{lease.registerBeforeInstall(proof);fail("reserved group accepted another install proof");}
            catch(IOException expected){assertEquals("RESTORE_V2_REGISTRATION_CLOSED",expected.getMessage());}
            final int[] verify={0};lease.commit((m,p)->{verify[0]++;assertEquals(proof.memberId,p.memberId);});
            assertEquals(1,verify[0]);assertEquals(LibraryRestoreGroupV2.RecoveryPhase.COMMITTED,lease.recoveryStatus());
        }
    }

    @Test public void precommitReservationIsHiddenAndRetryPublishesAtomicDirectoryPoint() throws Exception {
        File root=temp.newFolder("commit-path-reservation");Fs fs=new Fs();String tx=UUID.randomUUID().toString(),item="c-55555555555555555555555555555555";
        LibraryRestoreGroupV2.Request request=new LibraryRestoreGroupV2.Request(item,"r-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",null,LibraryRestoreGroupV2.Kind.COVER_PRESET,5,hash("payload"),hash("semantic"));
        final boolean[] injected={false};LibraryRestoreGroupV2 journal=new LibraryRestoreGroupV2(root,fs,new LibraryRestoreGroupV2.FaultInjector(){
            public void afterCommitReservationDurable(String groupId){}
            @Override public void afterCommitPathReservationDurable(String groupId)throws IOException{if(!injected[0]){injected[0]=true;throw new IOException("TEST_AFTER_OWNED_PATH_RESERVATION");}}
        });
        LibraryRestoreGroupV2.Plan plan=journal.ensurePlan(tx,hash("archive"),"detached:"+item,java.util.Collections.singletonList(request));
        LibraryRestoreGroupV2.Member member=plan.members.get(0);LibraryRestoreGroupV2.MemberProof proof=proof(plan,member,hash("storage"));
        try(LibraryRestoreGroupV2.Lease lease=journal.acquire(plan)){
            lease.registerBeforeInstall(proof);
            try{lease.commit((m,p)->{});fail("fault before atomic commit point expected");}
            catch(IOException expected){assertEquals("TEST_AFTER_OWNED_PATH_RESERVATION",expected.getMessage());}
            assertFalse(lease.isCommitted());assertEquals(LibraryRestoreGroupV2.RecoveryPhase.COMMIT_RESERVED,lease.recoveryStatus());
            lease.commit((m,p)->{});assertTrue(lease.isCommitted());assertEquals(LibraryRestoreGroupV2.RecoveryPhase.COMMITTED,lease.recoveryStatus());
            lease.commit((m,p)->{});assertEquals(LibraryRestoreGroupV2.RecoveryPhase.COMMITTED,lease.recoveryStatus());
            assertTrue(new File(new File(new File(root,tx),"groups/"+plan.groupId),"committed.json").isDirectory());
            assertFalse(new File(new File(new File(root,tx),"groups/"+plan.groupId),"commit-temp.json").exists());
        }
        try(LibraryRestoreGroupV2.Lease resumed=journal.acquire(plan)){
            assertEquals(LibraryRestoreGroupV2.RecoveryPhase.COMMITTED,resumed.recoveryStatus());
            resumed.commit((m,p)->{});assertEquals(LibraryRestoreGroupV2.RecoveryPhase.COMMITTED,resumed.recoveryStatus());
        }
    }

    @Test public void mkdirCommitPointSurvivesParentSyncFailureAndResumeRetriesSync() throws Exception {
        File root=temp.newFolder("post-mkdir-fault");Fs fs=new Fs();String tx=UUID.randomUUID().toString(),item="c-66666666666666666666666666666666";
        LibraryRestoreGroupV2.Request request=new LibraryRestoreGroupV2.Request(item,"r-cccccccccccccccccccccccccccccccc",null,LibraryRestoreGroupV2.Kind.COVER_PRESET,5,hash("payload"),hash("semantic"));
        final boolean[] injected={false};LibraryRestoreGroupV2 journal=new LibraryRestoreGroupV2(root,fs,new LibraryRestoreGroupV2.FaultInjector(){
            public void afterCommitReservationDurable(String groupId){}
            @Override public void afterCommitDirectoryCreated(String groupId)throws IOException{if(!injected[0]){injected[0]=true;throw new IOException("TEST_AFTER_MKDIR");}}
        });
        LibraryRestoreGroupV2.Plan plan=journal.ensurePlan(tx,hash("archive"),"detached:"+item,java.util.Collections.singletonList(request));
        LibraryRestoreGroupV2.Member member=plan.members.get(0);LibraryRestoreGroupV2.MemberProof proof=proof(plan,member,hash("storage"));
        try(LibraryRestoreGroupV2.Lease lease=journal.acquire(plan)){
            lease.registerBeforeInstall(proof);try{lease.commit((m,p)->{});fail("fault after atomic mkdir expected");}catch(IOException expected){assertEquals("TEST_AFTER_MKDIR",expected.getMessage());}
            assertTrue(lease.isCommitted());assertEquals(LibraryRestoreGroupV2.RecoveryPhase.COMMITTED,lease.recoveryStatus());
            lease.commit((m,p)->{});assertEquals(LibraryRestoreGroupV2.RecoveryPhase.COMMITTED,lease.recoveryStatus());
        }
    }

    @Test public void foreignEmptyCommitDirectoryIsRejectedAndPreserved() throws Exception {
        File root=temp.newFolder("foreign-commit-dir");Fs fs=new Fs();String tx=UUID.randomUUID().toString(),item="c-77777777777777777777777777777777";
        LibraryRestoreGroupV2.Request request=new LibraryRestoreGroupV2.Request(item,"r-dddddddddddddddddddddddddddddddd",null,LibraryRestoreGroupV2.Kind.COVER_PRESET,5,hash("payload"),hash("semantic"));
        LibraryRestoreGroupV2 journal=new LibraryRestoreGroupV2(root,fs);LibraryRestoreGroupV2.Plan plan=journal.ensurePlan(tx,hash("archive"),"detached:"+item,java.util.Collections.singletonList(request));
        LibraryRestoreGroupV2.Member member=plan.members.get(0);LibraryRestoreGroupV2.MemberProof proof=proof(plan,member,hash("storage"));
        File commitPoint=new File(new File(new File(root,tx),"groups/"+plan.groupId),"committed.json");
        try(LibraryRestoreGroupV2.Lease lease=journal.acquire(plan)){
            lease.registerBeforeInstall(proof);
            assertTrue(commitPoint.mkdir());
            try{lease.commit((m,p)->{});fail("unbound preexisting directory accepted");}
            catch(IOException expected){assertTrue(expected.getMessage().startsWith("RESTORE_V2_COMMIT_DIRECTORY"));}
        }
        assertTrue(commitPoint.isDirectory());assertEquals(0,commitPoint.list().length);
    }

    @Test public void largeCommittedMarkerIsReadAsMarkerNotSmallReservation() throws Exception {
        File root=temp.newFolder("large-marker");Fs fs=new Fs();String tx=UUID.randomUUID().toString();List<LibraryRestoreGroupV2.Request> requests=new ArrayList<>();
        for(int i=0;i<36;i++){String suffix=String.format(java.util.Locale.ROOT,"%032x",i+1);requests.add(new LibraryRestoreGroupV2.Request("c-"+suffix,"r-"+suffix,null,LibraryRestoreGroupV2.Kind.COVER_PRESET,8,hash("payload"+i),hash("semantic"+i)));}
        LibraryRestoreGroupV2 journal=new LibraryRestoreGroupV2(root,fs);LibraryRestoreGroupV2.Plan plan=journal.ensurePlan(tx,hash("archive"),"large-marker",requests);
        try(LibraryRestoreGroupV2.Lease lease=journal.acquire(plan)){
            for(LibraryRestoreGroupV2.Member member:plan.members)lease.registerBeforeInstall(proof(plan,member,hash("store"+member.memberId)));
            lease.commit((m,p)->{});assertEquals(LibraryRestoreGroupV2.RecoveryPhase.COMMITTED,lease.recoveryStatus());
        }
        File group=new File(new File(new File(root,tx),"groups"),plan.groupId);File marker=new File(group,"commit-marker.json");assertTrue(marker.length()>4096);
        LibraryRestoreGroupV2.Member first=plan.members.get(0);LibraryRestoreGroupV2.MemberProof stored;
        try(LibraryRestoreGroupV2.Lease lease=journal.acquire(plan)){stored=lease.proof(first.memberId);}
        assertTrue(LibraryRestoreGroupV2.isCommittedReference(root,tx,plan.groupId,first.memberId,first.localObjectId,first.kind,
                first.payloadSha256,first.semanticBindingSha256,stored.storageBindingSha256,fs));
    }

    @Test public void missingTransactionAncestorsAreAbsentOnlyUnderOwnedRoot() throws Exception {
        File owned=temp.newFolder("owned-root"),transactionRoot=new File(owned,"library-backup-v2/transactions");
        LibraryRestoreGroupV2 journal=new LibraryRestoreGroupV2(transactionRoot,new AncestorSensitiveFs());
        String item="c-44444444444444444444444444444444";
        LibraryRestoreGroupV2.Plan plan=journal.ensurePlan(UUID.randomUUID().toString(),hash("archive"),"detached:"+item,
                java.util.Collections.singletonList(new LibraryRestoreGroupV2.Request(item,"r-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",null,
                        LibraryRestoreGroupV2.Kind.COVER_PRESET,5,hash("payload"),hash("semantic"))));
        assertTrue(new File(new File(new File(owned,"library-backup-v2"),"transactions"),plan.transactionId).isDirectory());

        File linkedRoot=temp.newFolder("linked-root"),foreign=temp.newFolder("foreign");File sentinel=new File(foreign,"sentinel");Files.write(sentinel.toPath(),new byte[]{42});
        Files.createSymbolicLink(new File(linkedRoot,"library-backup-v2").toPath(),foreign.toPath());
        LibraryRestoreGroupV2 linked=new LibraryRestoreGroupV2(new File(linkedRoot,"library-backup-v2/transactions"),new AncestorSensitiveFs());
        try{linked.ensurePlan(UUID.randomUUID().toString(),hash("archive"),"detached:"+item,
                java.util.Collections.singletonList(new LibraryRestoreGroupV2.Request(item,"r-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",null,
                        LibraryRestoreGroupV2.Kind.COVER_PRESET,5,hash("payload"),hash("semantic"))));fail("symlink ancestor accepted");}
        catch(IOException expected){assertEquals("TEST_DIR",expected.getMessage());}
        assertArrayEquals(new byte[]{42},Files.readAllBytes(sentinel.toPath()));
    }

    private static LibraryRestoreGroupV2.MemberProof proof(LibraryRestoreGroupV2.Plan plan,LibraryRestoreGroupV2.Member member,String storage){return new LibraryRestoreGroupV2.MemberProof(member.memberId,plan.transactionId,plan.groupId,member.localObjectId,member.currentLocalNoteId,member.kind,member.payloadSha256,member.semanticBindingSha256,storage);}
    private static String hash(String s)throws Exception {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b&255));return out.toString();}

    private static class Fs implements LibraryBackupArchive.SafeFiles {
        public PublishLock lockPublish(File dest)throws IOException {File lock=new File(dest.getParentFile(),".lock-"+dest.getName());try{Files.createFile(lock.toPath());}catch(java.nio.file.FileAlreadyExistsException ignored){}FileChannel channel=FileChannel.open(lock.toPath(),StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock held=channel.lock();return ()->{try{held.release();}finally{channel.close();}};}
        public LibraryBackupArchive.FileIdentity inspect(File p,boolean directory)throws IOException {BasicFileAttributes a=Files.readAttributes(p.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(a.isSymbolicLink()||(directory&&!a.isDirectory())||(!directory&&!a.isRegularFile()))throw new IOException("TEST_TYPE");long links=1;try{links=((Number)Files.getAttribute(p.toPath(),"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue();}catch(Exception ignored){}long inode=a.fileKey()==null?p.getAbsolutePath().hashCode():a.fileKey().hashCode(),time=a.lastModifiedTime().toMillis()*1000000L;return new LibraryBackupArchive.FileIdentity(1,inode,a.size(),time,time,links,directory);}
        public void validatePrivateDirectory(File p)throws IOException {BasicFileAttributes a=Files.readAttributes(p.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!a.isDirectory()||a.isSymbolicLink())throw new IOException("TEST_DIR");}
        public LibraryBackupArchive.Seekable openRead(File p)throws IOException {LibraryBackupArchive.FileIdentity identity=inspect(p,false);FileChannel channel=FileChannel.open(p.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);return new Seek(p,channel,identity);}
        public LibraryBackupArchive.FileIdentity inspectOwnedPair(File p)throws IOException{return inspect(p,false);} public LibraryBackupArchive.Seekable openOwnedPair(File p)throws IOException{return openRead(p);}
        public void linkNoReplace(File a,File b)throws IOException{Files.createLink(b.toPath(),a.toPath());}
        public boolean isAbsentNoFollow(File p)throws IOException{try{Files.readAttributes(p.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return false;}catch(java.nio.file.NoSuchFileException missing){return true;}}
        public FileOutputStream createExclusive(File p)throws IOException{Files.createFile(p.toPath());return new FileOutputStream(p);}
        public void mkdirExclusive(File p)throws IOException{Files.createDirectory(p.toPath());}
        public void syncDirectory(File p)throws IOException{validatePrivateDirectory(p);}
        public void syncExistingFile(File p,LibraryBackupArchive.FileIdentity expected)throws IOException{if(!expected.equals(inspect(p,false)))throw new IOException("TEST_CHANGED");try(FileChannel channel=FileChannel.open(p.toPath(),StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}}
        public void deleteOwnedTree(File p)throws IOException{if(!Files.exists(p.toPath(),LinkOption.NOFOLLOW_LINKS))return;Files.walkFileTree(p.toPath(),new java.nio.file.SimpleFileVisitor<java.nio.file.Path>(){@Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path f,BasicFileAttributes a)throws IOException{Files.delete(f);return java.nio.file.FileVisitResult.CONTINUE;}@Override public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path d,IOException e)throws IOException{if(e!=null)throw e;Files.delete(d);return java.nio.file.FileVisitResult.CONTINUE;}});}
        public void replaceOwnedMarker(File from,File to,byte[] marker,LibraryBackupArchive.FileIdentity id)throws IOException{Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        public void moveNoReplace(File from,File to,byte[] expected)throws IOException{if(to.exists())throw new IOException("DESTINATION_EXISTS");if(!java.util.Arrays.equals(Files.readAllBytes(from.toPath()),expected))throw new IOException("PUBLISH_SOURCE_UNSAFE");Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE);}
        public void unlink(File p)throws IOException{Files.delete(p.toPath());} public boolean matchesFile(File p,LibraryBackupArchive.FileIdentity id)throws IOException{LibraryBackupArchive.FileIdentity now=inspect(p,false);return id.device==now.device&&id.inode==now.inode;}
    }
    private static final class AncestorSensitiveFs extends Fs {
        @Override public boolean isAbsentNoFollow(File path)throws IOException{
            File parent=path.getAbsoluteFile().getParentFile();if(parent==null)throw new IOException("PATH_UNAVAILABLE");
            try{BasicFileAttributes a=Files.readAttributes(parent.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!a.isDirectory()||a.isSymbolicLink())throw new IOException("TEST_DIR");}
            catch(java.nio.file.NoSuchFileException missing){throw new IOException("PATH_UNAVAILABLE");}
            return super.isAbsentNoFollow(path);
        }
    }
    private static final class Seek implements LibraryBackupArchive.Seekable {final File path;final FileChannel channel;final LibraryBackupArchive.FileIdentity identity;Seek(File p,FileChannel c,LibraryBackupArchive.FileIdentity i){path=p;channel=c;identity=i;}public long size()throws IOException{return channel.size();}public void readFully(long pos,byte[] b,int off,int len)throws IOException{ByteBuffer target=ByteBuffer.wrap(b,off,len);while(target.hasRemaining()){int n=channel.read(target,pos);if(n<0)throw new IOException("TEST_EOF");pos+=n;}}public LibraryBackupArchive.FileIdentity openedIdentity(){return identity;}public LibraryBackupArchive.FileIdentity currentPathIdentity()throws IOException{return new Fs().inspect(path,false);}public void close()throws IOException{channel.close();}}
}
