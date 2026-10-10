package com.padnote.android.streaming;

import static org.junit.Assert.*;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Device-only source. This candidate does not start a device or emulator. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion=27)
public final class AndroidStreamingStoreInstrumentedTest {
    private static final long MIB=1024L*1024L;
    private static final StreamingGroupStore.ProjectionVerifier VERIFIER=new StreamingGroupStore.ProjectionVerifier(){
        public void verifyBody(byte[] body)throws IOException{if(body==null||body.length==0)throw new IOException("BODY_EMPTY");}
        public void verifyMetadata(String role,String materialId,byte[] metadata)throws IOException{if(role==null||materialId==null||metadata==null||metadata.length>1<<20)throw new IOException("METADATA_INVALID");}
    };

    @Test public void contextFilesDirAnchorFeatureGateReadWriteAndScopeRefusals()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        TrustedFilesRoot trusted=TrustedFilesRoot.fromApplicationContext(context);trusted.verify();
        assertEquals(context.getFilesDir().getCanonicalFile().toPath().normalize(),trusted.path());
        Path owned=ownedRoot("context-anchor");boolean keep=false;
        try{
            Path storePath=owned.resolve("store");StreamingGroupStore.Store store=StreamingFeatureGate.openStore(context,storePath.toFile(),VERIFIER);
            StreamingGroupStore.Snapshot committed=store.commit(envelope("anchor-r1",null,null,null,StreamingGroupStore.Action.FIRST_IMPORT,"{\"body\":1}".getBytes(),null,null,member("anchor-video",inlineContent(owned,"anchor-video.mp4")),null,null),(before,incoming)->before==null);
            assertNotNull(committed);assertEquals("anchor-r1",store.read("origin-lineage").revision);
            assertArrayEquals("{\"body\":1}".getBytes(),store.read("origin-lineage").readSmall("body.bin",1024));

            File outside=context.getCacheDir();expectCode("PATH_OUTSIDE_TRUSTED_FILES_ROOT",()->StreamingFeatureGate.openStore(context,new File(outside,"streaming-outside-"+UUID.randomUUID()),VERIFIER));

            Path target=Files.createDirectory(owned.resolve("symlink-target"));Path alias=owned.resolve("symlink-parent");Os.symlink(target.toString(),alias.toString());
            expectCode("DIRECTORY_ANCESTOR_NOT_PLAIN",()->StreamingFeatureGate.openStore(context,alias.resolve("store").toFile(),VERIFIER));

            Path movedVisible=owned.resolve("visible-original");Files.move(storePath.resolve("visible"),movedVisible);Path replacement=Files.createDirectory(owned.resolve("replacement-visible"));Os.symlink(replacement.toString(),storePath.resolve("visible").toString());
            expectCode("DIRECTORY_ANCESTOR_NOT_PLAIN",()->store.read("origin-lineage"));
        }finally{if(!keep)deleteOwned(owned);}
    }

    @Test public void duplicateLocalIdCannotPublishASecondVisibleGroup()throws Exception{
        Path root=ownedRoot("duplicate-local-id");boolean keep=false;
        try{
            Path storePath=root.resolve("store");StreamingGroupStore.Store store=new StreamingGroupStore.Store(storePath,ops(storePath),VERIFIER);
            StreamingGroupStore.Snapshot first=store.commit(envelope("r1",null,null,null,StreamingGroupStore.Action.FIRST_IMPORT,
                    "{\"body\":1}".getBytes(),null,null,member("first",inlineContent(root,"first.mp4")),null,null),(before,incoming)->before==null);
            assertNotNull(first);byte[] markerBefore=Files.readAllBytes(storePath.resolve("visible/origin-lineage.marker"));
            Path duplicateVideo=writePattern(root.resolve("duplicate.mp4"),1024,(byte)0x55);
            StreamingGroupStore.Envelope duplicate=new StreamingGroupStore.Envelope(
                    new StreamingGroupStore.ImportIdentity("duplicate-lineage","source-b","local-b","duplicate-lineage",null,null),
                    "duplicate-r1",null,null,null,StreamingGroupStore.Action.FIRST_IMPORT,"{\"body\":2}".getBytes(),null,null,
                    Arrays.asList(member("duplicate",new StreamingGroupStore.ContentInput(duplicateVideo,1024,sha(duplicateVideo),null))),
                    java.util.Collections.emptyList());
            expectCode("DUPLICATE_LOCAL_ID",()->store.commit(duplicate,(before,incoming)->true));
            assertNull("the duplicate lineage has no visible marker",store.read("duplicate-lineage"));
            assertArrayEquals("the original visible marker remains exact",markerBefore,
                    Files.readAllBytes(storePath.resolve("visible/origin-lineage.marker")));
            assertEquals(first.digest,store.read("origin-lineage").digest);
        }finally{if(!keep)deleteOwned(root);}
    }

    @Test public void descriptorPathMutationHardlinkSymlinkDirectorySyncNoReplaceAndLocks()throws Exception{
        Path root=ownedRoot("identity");boolean keep=false;
        try{
            Path store=root.resolve("store"),dir=store.resolve("sub");AndroidIdentityOps ops=ops(store);ops.ensurePlainDirectory(dir);ops.requirePlainDirectory(dir);ops.syncDirectory(dir);
            Path original=write(dir.resolve("plain.bin"),new byte[]{1,2,3,4});StreamingGroupStore.StableInput in=ops.open(original,4);Path replacement=write(dir.resolve("replacement.bin"),new byte[]{5,6,7,8});
            Files.move(replacement,original,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            expectIo(()->ops.requireStable(original,in.pathBefore(),in.before(),in.after(),ops.identity(original)));in.close();
            Path hard=dir.resolve("hard.bin");boolean hardlinkCreated=false;
            try{Os.link(original.toString(),hard.toString());hardlinkCreated=true;}
            catch(ErrnoException denied){assertTrue("hard-link denial is an expected Android sandbox capability boundary",denied.errno==OsConstants.EACCES||denied.errno==OsConstants.EPERM);}
            if(hardlinkCreated){expectIo(()->ops.open(hard,4));Os.remove(hard.toString());}
            Path symbolic=dir.resolve("symbolic.bin");Os.symlink(original.toString(),symbolic.toString());expectIo(()->ops.open(symbolic,4));Os.remove(symbolic.toString());
            // Two simultaneous opens of one file keep distinct witness keys. A
            // completed stability check is repeatable until each exact open closes.
            StreamingGroupStore.StableInput stableFirst=ops.open(original,4),stableSecond=ops.open(original,4);
            ops.requireStable(original,stableFirst.pathBefore(),stableFirst.before(),stableFirst.after(),ops.identity(original));
            ops.requireStable(original,stableSecond.pathBefore(),stableSecond.before(),stableSecond.after(),ops.identity(original));
            ops.requireStable(original,stableFirst.pathBefore(),stableFirst.before(),stableFirst.after(),ops.identity(original));
            Path oldDir=root.resolve("sub-open-original"),directory=dir;
            Files.move(directory,oldDir);Files.createDirectory(directory);
            try{
                moveChildren(oldDir,directory);
                expectIo(()->ops.requireStable(original,stableFirst.pathBefore(),stableFirst.before(),stableFirst.after(),ops.identity(original)));
                expectIo(()->ops.requireStable(original,stableSecond.pathBefore(),stableSecond.before(),stableSecond.after(),ops.identity(original)));
            }finally{
                moveChildren(directory,oldDir);Files.delete(directory);Files.move(oldDir,directory);
            }
            // Restoring the directory name and child inodes does not restore
            // complete FileIdentity metadata (rename changes ctime). Both
            // previously opened inputs must remain invalid after the mutation.
            expectIo(()->ops.requireStable(original,stableFirst.pathBefore(),stableFirst.before(),stableFirst.after(),ops.identity(original)));
            expectIo(()->ops.requireStable(original,stableSecond.pathBefore(),stableSecond.before(),stableSecond.after(),ops.identity(original)));
            stableFirst.close();stableSecond.close();
            Path outOfScope=write(dir.resolve("stage.bin"),new byte[]{4,5});expectCode("NOREPLACE_PATH_SCOPE",()->ops.moveNoReplace(outOfScope,dir.resolve("foreign-installed.bin")));assertArrayEquals(new byte[]{4,5},Files.readAllBytes(outOfScope));
            try(StreamingGroupStore.LockLease first=ops.lock(dir.resolve("active.lock"))){assertTrue(!ops.tryLock(dir.resolve("active.lock")).isPresent());}
            Optional<StreamingGroupStore.LockLease> after=ops.tryLock(dir.resolve("active.lock"));assertTrue(after.isPresent());after.get().close();
        }finally{if(!keep)deleteOwned(root);}
    }

    @Test public void verifiedLeafLeaseSurvivesSiblingObjectInstall()throws Exception{
        Path root=ownedRoot("ancestor-sibling-install");boolean keep=false;
        try{
            Path storePath=root.resolve("store");AndroidIdentityOps ops=ops(storePath);
            StreamingGroupStore.Store store=new StreamingGroupStore.Store(storePath,ops,VERIFIER);
            Path source=write(root.resolve("first-video.bin"),new byte[]{1,3,5,7});
            java.util.concurrent.atomic.AtomicReference<StreamingGroupStore.VerifiedGroup> firstGroup=
                    new java.util.concurrent.atomic.AtomicReference<>();
            StreamingGroupStore.Snapshot first=store.commit(envelope("ancestor-revision-1",null,null,null,
                    StreamingGroupStore.Action.FIRST_IMPORT,"{\"body\":1}".getBytes(),null,null,
                    member("video-a",new StreamingGroupStore.ContentInput(source,4,sha(source),null)),null,null),
                    (before,incoming)->{assertNull(before);firstGroup.set(incoming);return true;});
            assertNotNull(first);assertNotNull(firstGroup.get());
            StreamingGroupStore.VerifiedContentLease lease=firstGroup.get().openVerifiedContentLease("video/video-a/content.bin");
            try{
                Path objectDirectory=storePath.resolve("objects/sha256");
                StreamingGroupStore.FileIdentity directoryBefore=ops.identity(objectDirectory);
                byte[] secondBody="{\"body\":2}".getBytes();
                StreamingGroupStore.Snapshot second=store.commit(envelope("ancestor-revision-2",
                        "ancestor-revision-1","ancestor-revision-1",first.digest,
                        StreamingGroupStore.Action.UPDATE,secondBody,null,null,
                        member("video-a",new StreamingGroupStore.ContentInput(null,4,sha(source),
                                first.reuseToken("video/video-a/content.bin"))),null,null),
                        (before,incoming)->before!=null&&before.revision.equals("ancestor-revision-1"));
                assertEquals("ancestor-revision-2",second.revision);
                Path sibling=objectDirectory.resolve(sha(secondBody));
                assertTrue("the update installed a distinct sibling object",Files.isRegularFile(sibling,LinkOption.NOFOLLOW_LINKS));
                StreamingGroupStore.FileIdentity directoryAfter=ops.identity(objectDirectory);
                assertFalse("creating the sibling must mutate directory metadata",directoryBefore.equals(directoryAfter));
                lease.verifyUnchanged();
                assertEquals("the original lease remains bound to the first video",sha(source),lease.sha256);
            }finally{lease.close();}
        }finally{if(!keep)deleteOwned(root);}
    }

    @Test public void nativeNoReplaceMovesTransactionObjectAcrossParentsAndPreservesCollision()throws Exception{
        Path root=ownedRoot("native-no-replace");boolean keep=false;
        try{
            Path store=root.resolve("store"),objectDir=store.resolve("objects/sha256");AndroidIdentityOps ops=ops(store);
            ops.ensurePlainDirectory(objectDir);Path transaction=store.resolve("transactions").resolve(UUID.randomUUID().toString());ops.ensurePlainDirectory(transaction);
            byte[] installedBytes=new byte[]{9,8,7};Path staged=write(transaction.resolve("object-"+UUID.randomUUID()+".tmp"),installedBytes);
            Path installed=objectDir.resolve(sha(installedBytes));ops.moveNoReplace(staged,installed);
            assertFalse("atomic move removes the transaction staging entry",Files.exists(staged));assertArrayEquals(installedBytes,Files.readAllBytes(installed));assertEquals(1,ops.identity(installed).links());
            Path collisionStage=write(transaction.resolve("object-"+UUID.randomUUID()+".tmp"),new byte[]{1});
            try{ops.moveNoReplace(collisionStage,installed);fail("existing destination was replaced");}
            catch(FileAlreadyExistsException expected){}
            assertTrue("failed collision retains its source",Files.exists(collisionStage));assertArrayEquals(new byte[]{1},Files.readAllBytes(collisionStage));
            assertArrayEquals("existing destination remains unchanged",installedBytes,Files.readAllBytes(installed));assertEquals(1,ops.identity(installed).links());
        }finally{if(!keep)deleteOwned(root);}
    }

    @Test public void largeResourcesStreamAndSmallBodyUpdateDoesNotRewriteObjects()throws Exception{
        Path root=ownedRoot("large");boolean keep=false;
        try{
            Path sourceDir=Files.createDirectory(root.resolve("sources"));Path va=writePattern(sourceDir.resolve("video-a.mp4"),100*MIB,(byte)0x31),vb=writePattern(sourceDir.resolve("video-b.mp4"),100*MIB,(byte)0x62),pdf=writePattern(sourceDir.resolve("note.pdf"),100*MIB,(byte)0x23);
            Path cover=writePattern(sourceDir.resolve("cover.bin"),8*MIB,(byte)0x73),vault=writePattern(sourceDir.resolve("vault.bin"),4*MIB,(byte)0x14);
            String ha=sha(va),hb=sha(vb),hp=sha(pdf),hc=sha(cover),hv=sha(vault);Path storePath=root.resolve("store");AndroidIdentityOps ops=ops(storePath);StreamingGroupStore.Store store=new StreamingGroupStore.Store(storePath,ops,VERIFIER);
            StreamingGroupStore.Envelope first=envelope("revision-1",null,null,null,StreamingGroupStore.Action.FIRST_IMPORT,"{\"body\":1}".getBytes(),new StreamingGroupStore.ContentInput(pdf,100*MIB,hp,null),new StreamingGroupStore.ContentInput(cover,8*MIB,hc,null),
                    member("a",new StreamingGroupStore.ContentInput(va,100*MIB,ha,null)),member("b",new StreamingGroupStore.ContentInput(vb,100*MIB,hb,null)),member("vault","vault-a",new StreamingGroupStore.ContentInput(vault,4*MIB,hv,null)));
            StreamingGroupStore.Snapshot one=store.commit(first,(before,incoming)->{
                assertNull(before);assertEquals(Long.valueOf(100*MIB),incoming.memberSizes().get("video/a/content.bin"));assertArrayEquals("{\"body\":1}".getBytes(),incoming.readSmall("body.bin",1024));assertArrayEquals(new byte[]{1,2,3},incoming.readSmall("video/a/metadata.bin",1024));
                try(InputStream content=incoming.openContent("video/a/content.bin")){assertEquals(0x31,content.read());}return true;
            });
            assertEquals("local-b",one.localId);assertEquals("origin-lineage",one.lineage);Path objects=storePath.resolve("objects/sha256");String[] hashes={ha,hb,hp,hc,hv};String[] identities=new String[hashes.length];for(int i=0;i<hashes.length;i++)identities[i]=fileIdentity(objects.resolve(hashes[i]));long writes=ops.metrics().objectInstalledBytes();
            StreamingGroupStore.Snapshot snapshot=store.readRevision("origin-lineage","revision-1",one.digest);
            StreamingGroupStore.Envelope second=envelope("revision-2","revision-1","revision-1",one.digest,StreamingGroupStore.Action.UPDATE,"{\"body\":2}".getBytes(),
                    new StreamingGroupStore.ContentInput(null,100*MIB,hp,snapshot.reuseToken("pdf.bin")),new StreamingGroupStore.ContentInput(null,8*MIB,hc,snapshot.reuseToken("cover.bin")),
                    member("a",new StreamingGroupStore.ContentInput(null,100*MIB,ha,snapshot.reuseToken("video/a/content.bin"))),member("b",new StreamingGroupStore.ContentInput(null,100*MIB,hb,snapshot.reuseToken("video/b/content.bin"))),member("vault","vault-a",new StreamingGroupStore.ContentInput(null,4*MIB,hv,snapshot.reuseToken("vault/vault-a/content.bin"))));
            StreamingGroupStore.Snapshot two=store.commit(second,(before,incoming)->before!=null&&before.revision.equals("revision-1"));assertEquals("revision-2",two.revision);assertTrue("body update installs no large content again",ops.metrics().objectInstalledBytes()-writes<1024);
            for(int i=0;i<hashes.length;i++)assertEquals("immutable media identity",identities[i],fileIdentity(objects.resolve(hashes[i])));
            assertEquals("old revision retained","revision-1",store.readRevision("origin-lineage","revision-1",one.digest).revision);assertTrue("read requests remain bounded",ops.metrics().maximumReadRequest()<=StreamingGroupStore.COPY_BUFFER_BYTES);
            StreamingGroupStore.Envelope stale=envelope("revision-stale","revision-1","revision-1","0000000000000000000000000000000000000000000000000000000000000000",StreamingGroupStore.Action.UPDATE,"{\"body\":3}".getBytes(),null,null,
                    member("a",new StreamingGroupStore.ContentInput(null,100*MIB,ha,two.reuseToken("video/a/content.bin"))),member("b",new StreamingGroupStore.ContentInput(null,100*MIB,hb,two.reuseToken("video/b/content.bin"))),member("vault-a",new StreamingGroupStore.ContentInput(null,4*MIB,hv,two.reuseToken("vault/vault-a/content.bin"))));
            expectFailure(()->store.commit(stale,(before,incoming)->true));assertEquals("stale CAS preserves current marker",two.digest,store.read("origin-lineage").digest);
        }finally{if(!keep)deleteOwned(root);}
    }

    @Test public void explicitRecoveryRespectsActiveLockAndDurablePreviewPhase()throws Exception{
        Path root=ownedRoot("recovery");boolean keep=false;
        try{
            Path storePath=root.resolve("store");AtomicBoolean injected=new AtomicBoolean(),activeRefused=new AtomicBoolean();StreamingGroupStore.Store[] holder=new StreamingGroupStore.Store[1];
            StreamingGroupStore.Store failing=new StreamingGroupStore.Store(storePath,ops(storePath),VERIFIER,phase->{if(phase==StreamingGroupStore.Phase.PREVIEW_APPROVED){injected.set(true);String tx=holder[0].transactionIDs().get(0);try{holder[0].recover(tx);fail("active transaction recovery accepted");}catch(IOException expected){assertEquals("TRANSACTION_ACTIVE",expected.getMessage());activeRefused.set(true);}throw new IOException("SIMULATED_AFTER_DURABLE_PREVIEW");}});holder[0]=failing;
            StreamingGroupStore.Envelope first=envelope("r1",null,null,null,StreamingGroupStore.Action.FIRST_IMPORT,"body".getBytes(),null,null,member("v",inlineContent(root,"video.bin")),null,null);
            expectFailure(()->failing.commit(first,(before,incoming)->true));assertTrue(injected.get());assertTrue(activeRefused.get());assertNull(failing.read("origin-lineage"));String tx=failing.transactionIDs().get(0);
            StreamingGroupStore.Store recovered=new StreamingGroupStore.Store(storePath,ops(storePath),VERIFIER);assertEquals(StreamingGroupStore.Phase.COMMITTED,recovered.recover(tx));assertEquals("r1",recovered.read("origin-lineage").revision);assertEquals(StreamingGroupStore.Phase.COMMITTED,recovered.recover(tx));
        }finally{if(!keep)deleteOwned(root);}
    }

    @Test public void immutableGroupNoReplaceAndPartialCrashPreserveOldMarker()throws Exception{
        Path root=ownedRoot("group-install");boolean keep=true;
        try{
            Path collisionRoot=root.resolve("collision-store");AndroidIdentityOps collisionOps=ops(collisionRoot);
            Path stage=collisionRoot.resolve("transactions/"+UUID.randomUUID()+"/group");collisionOps.ensurePlainDirectory(stage);write(stage.resolve("manifest.bin"),new byte[]{1,2});write(stage.resolve("owner.txt"),new byte[]{3});
            Path groupParent=collisionRoot.resolve("groups/collision-lineage");collisionOps.ensurePlainDirectory(groupParent);Path empty=groupParent.resolve("empty-revision");collisionOps.ensurePlainDirectory(empty);
            try{collisionOps.installImmutableGroup(stage,empty);fail("empty existing group destination was replaced");}catch(java.nio.file.FileAlreadyExistsException expected){}
            try(java.nio.file.DirectoryStream<Path> children=Files.newDirectoryStream(empty)){assertFalse("empty collision remains empty",children.iterator().hasNext());}
            Path nonempty=groupParent.resolve("nonempty-revision");collisionOps.ensurePlainDirectory(nonempty);write(nonempty.resolve("sentinel.bin"),new byte[]{8,9});
            try{collisionOps.installImmutableGroup(stage,nonempty);fail("nonempty existing group destination was replaced");}catch(java.nio.file.FileAlreadyExistsException expected){}
            assertArrayEquals(new byte[]{8,9},Files.readAllBytes(nonempty.resolve("sentinel.bin")));assertTrue(Files.exists(stage.resolve("manifest.bin")));assertArrayEquals(new byte[]{1,2},Files.readAllBytes(stage.resolve("manifest.bin")));
            Path foreignStage=root.resolve("foreign-stage");collisionOps.ensurePlainDirectory(foreignStage);write(foreignStage.resolve("manifest.bin"),new byte[]{1,2});write(foreignStage.resolve("owner.txt"),new byte[]{3});Path foreignDest=root.resolve("foreign-destination");collisionOps.ensurePlainDirectory(foreignDest);
            expectCode("IMMUTABLE_GROUP_PATH_SCOPE",()->collisionOps.installImmutableGroup(foreignStage,foreignDest));assertTrue(Files.exists(foreignStage.resolve("manifest.bin")));

            Path storePath=root.resolve("store");AndroidIdentityOps ops=ops(storePath);
            StreamingGroupStore.Store good=new StreamingGroupStore.Store(storePath,ops,VERIFIER);
            StreamingGroupStore.Snapshot first=good.commit(envelope("r1",null,null,null,StreamingGroupStore.Action.FIRST_IMPORT,"first".getBytes(),null,null,member("v",inlineContent(root,"small.mp4")),null,null),(before,incoming)->true);
            Path marker=storePath.resolve("visible/origin-lineage.marker");byte[] markerBefore=Files.readAllBytes(marker);AtomicBoolean interrupted=new AtomicBoolean();
            AndroidIdentityOps failingOps=ops(storePath,null,new AndroidIdentityOps.GroupInstallObserver(){
                public void afterDirectoryCreated(Path destination){}
                public void afterFileInstalled(Path destination,String member)throws IOException{if("manifest.bin".equals(member)){interrupted.set(true);throw new IOException("SIMULATED_AFTER_FIRST_GROUP_FILE");}}
            });
            StreamingGroupStore.Store failing=new StreamingGroupStore.Store(storePath,failingOps,VERIFIER);
            java.util.Set<String> transactionsBefore=new java.util.HashSet<>(failing.transactionIDs());
            expectFailure(()->failing.commit(envelope("r2","r1","r1",first.digest,StreamingGroupStore.Action.UPDATE,"second".getBytes(),null,null,member("v",new StreamingGroupStore.ContentInput(null,4096,sha(root.resolve("small.mp4")),first.reuseToken("video/v/content.bin"))),null,null),(before,incoming)->true));
            assertTrue(interrupted.get());assertArrayEquals("old marker is unchanged",markerBefore,Files.readAllBytes(marker));Path partial=null;
            try(java.nio.file.DirectoryStream<Path> groups=Files.newDirectoryStream(storePath.resolve("groups/origin-lineage"),"r2-*")){for(Path candidate:groups){if(Files.exists(candidate.resolve("manifest.bin"),LinkOption.NOFOLLOW_LINKS)){partial=candidate;break;}}}
            assertNotNull("partial destination is retained",partial);assertTrue(Files.exists(partial.resolve("manifest.bin")));assertFalse(Files.exists(partial.resolve("owner.txt")));
            java.util.List<String> transactionIDsAfter=failing.transactionIDs();java.util.Set<String> newTransactions=new java.util.HashSet<>(transactionIDsAfter);newTransactions.removeAll(transactionsBefore);assertEquals("exactly one r2 transaction was created",1,newTransactions.size());String tx=newTransactions.iterator().next();assertFalse(transactionsBefore.contains(tx));StreamingGroupStore.Store recovered=new StreamingGroupStore.Store(storePath,ops(storePath),VERIFIER);
            assertEquals(StreamingGroupStore.Phase.ABORTED,recovered.recover(tx));assertTrue(Files.exists(partial));assertFalse(Files.exists(partial.resolve("owner.txt")));assertArrayEquals(markerBefore,Files.readAllBytes(marker));assertEquals("r1",recovered.read("origin-lineage").revision);
        }finally{if(!keep)deleteOwned(root);}
    }

    @Test public void interruptedAfterAtomicInstallLeavesSingleLinkObjectOldMarkerAndUnknownRecovery()throws Exception{
        Path root=ownedRoot("link-crash");boolean keep=true;
        try{
            Path storePath=root.resolve("store");AndroidIdentityOps good=ops(storePath);StreamingGroupStore.Store store=new StreamingGroupStore.Store(storePath,good,VERIFIER);
            StreamingGroupStore.Snapshot first=store.commit(envelope("r1",null,null,null,StreamingGroupStore.Action.FIRST_IMPORT,"first".getBytes(),null,null,member("v",inlineContent(root,"video.bin")),null,null),(before,incoming)->true);
            Path marker=storePath.resolve("visible/origin-lineage.marker");byte[] markerBefore=Files.readAllBytes(marker);AtomicBoolean faulted=new AtomicBoolean();
            AndroidIdentityOps faultOps=ops(storePath,(stage,destination)->{faulted.set(true);throw new IOException("SIMULATED_AFTER_ATOMIC_NO_REPLACE");});StreamingGroupStore.Store faulty=new StreamingGroupStore.Store(storePath,faultOps,VERIFIER);
            java.util.Set<String> transactionsBefore=new java.util.HashSet<>(faulty.transactionIDs());
            expectFailure(()->faulty.commit(envelope("r2","r1","r1",first.digest,StreamingGroupStore.Action.UPDATE,"second".getBytes(),null,null,member("v",new StreamingGroupStore.ContentInput(null,4096,sha(root.resolve("video.bin")),first.reuseToken("video/v/content.bin"))),null,null),(before,incoming)->true));
            assertTrue(faulted.get());assertArrayEquals(markerBefore,Files.readAllBytes(marker));Path installed=storePath.resolve("objects/sha256").resolve(sha("second".getBytes()));
            assertTrue("atomic no-replace may leave its durable unreferenced content object",Files.exists(installed));assertEquals(1,faultOps.identity(installed).links());assertArrayEquals("second".getBytes(),Files.readAllBytes(installed));
            java.util.List<String> transactionIDsAfter=faulty.transactionIDs();java.util.Set<String> newTransactions=new java.util.HashSet<>(transactionIDsAfter);newTransactions.removeAll(transactionsBefore);assertEquals(1,newTransactions.size());String tx=newTransactions.iterator().next();
            Path txPath=storePath.resolve("transactions").resolve(tx),journal=txPath.resolve("journal.bin");
            assertEquals(StreamingGroupStore.IdentityOps.NoFollowState.MISSING,faultOps.noFollowState(journal));
            expectCode("RECOVERY_UNKNOWN_RETAINED",()->faulty.recover(tx));assertFalse(Files.exists(journal,LinkOption.NOFOLLOW_LINKS));
            assertArrayEquals("old marker remains selected",markerBefore,Files.readAllBytes(marker));assertEquals("r1",faulty.read("origin-lineage").revision);

            // A present symlink is never reclassified as absence, even if its target is valid.
            Os.symlink(txPath.resolve("active.lock").toString(),journal.toString());
            assertEquals(StreamingGroupStore.IdentityOps.NoFollowState.PRESENT,faultOps.noFollowState(journal));
            expectCode("MEMBER_NOT_PLAIN",()->faulty.recover(tx));assertArrayEquals(markerBefore,Files.readAllBytes(marker));
            Os.remove(journal.toString());

            // A present but malformed journal stays a corruption error and is not repaired/deleted.
            byte[] corruptJournal=new byte[]{0x42,0x41,0x44};write(journal,corruptJournal);
            String corruptIdentity=fileIdentity(journal);
            assertEquals(StreamingGroupStore.IdentityOps.NoFollowState.PRESENT,faultOps.noFollowState(journal));
            expectCode("JOURNAL_INVALID",()->faulty.recover(tx));assertArrayEquals(corruptJournal,Files.readAllBytes(journal));
            assertEquals(corruptIdentity,fileIdentity(journal));assertArrayEquals(markerBefore,Files.readAllBytes(marker));
        }finally{if(!keep)deleteOwned(root);}
    }

    private static StreamingGroupStore.Envelope envelope(String revision,String parent,String base,String digest,StreamingGroupStore.Action action,byte[] body,StreamingGroupStore.ContentInput pdf,StreamingGroupStore.ContentInput cover,StreamingGroupStore.MemberInput videoA,StreamingGroupStore.MemberInput videoB,StreamingGroupStore.MemberInput vault){
        List<StreamingGroupStore.MemberInput> videos=videoA==null?java.util.Collections.<StreamingGroupStore.MemberInput>emptyList():videoB==null?Arrays.asList(videoA):Arrays.asList(videoA,videoB);
        List<StreamingGroupStore.MemberInput> vaults=vault==null?java.util.Collections.<StreamingGroupStore.MemberInput>emptyList():Arrays.asList(vault);
        return new StreamingGroupStore.Envelope(new StreamingGroupStore.ImportIdentity("origin-lineage","source-a","local-b","origin-lineage",null,null),revision,parent,base,digest,action,body,pdf,cover,videos,vaults);
    }
    private static StreamingGroupStore.MemberInput member(String id,StreamingGroupStore.ContentInput content){return new StreamingGroupStore.MemberInput("video",id,"video/mp4",new byte[]{1,2,3},content);}
    private static StreamingGroupStore.MemberInput member(String role,String id,StreamingGroupStore.ContentInput content){return new StreamingGroupStore.MemberInput(role,id,"application/octet-stream",new byte[]{1,2,3},content);}
    private static StreamingGroupStore.ContentInput inlineContent(Path root,String filename)throws Exception{Path p=writePattern(root.resolve(filename),4096,(byte)0x31);return new StreamingGroupStore.ContentInput(p,4096,sha(p),null);}
    private static Path ownedRoot(String label)throws IOException{Context c=InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();Path root=TrustedFilesRoot.fromApplicationContext(c).path().resolve("streaming-group-candidate-"+label+"-"+UUID.randomUUID());return Files.createDirectory(root);}
    private static AndroidIdentityOps ops(Path storeRoot)throws IOException{return new AndroidIdentityOps(TrustedFilesRoot.fromApplicationContext(InstrumentationRegistry.getInstrumentation().getTargetContext()),storeRoot);}
    private static AndroidIdentityOps ops(Path storeRoot,AndroidIdentityOps.InstallObserver observer)throws IOException{return new AndroidIdentityOps(TrustedFilesRoot.fromApplicationContext(InstrumentationRegistry.getInstrumentation().getTargetContext()),storeRoot,observer);}
    private static AndroidIdentityOps ops(Path storeRoot,AndroidIdentityOps.InstallObserver observer,AndroidIdentityOps.GroupInstallObserver groupObserver)throws IOException{return new AndroidIdentityOps(TrustedFilesRoot.fromApplicationContext(InstrumentationRegistry.getInstrumentation().getTargetContext()),storeRoot,observer,groupObserver);}
    private static Path write(Path path,byte[] bytes)throws IOException{try(FileOutputStream out=new FileOutputStream(path.toFile())){out.write(bytes);out.getFD().sync();}return path;}
    private static void moveChildren(Path from,Path to)throws IOException{try(java.nio.file.DirectoryStream<Path> children=Files.newDirectoryStream(from)){for(Path child:children)Files.move(child,to.resolve(child.getFileName()));}}
    private static Path writePattern(Path path,long size,byte value)throws IOException{byte[] block=new byte[StreamingGroupStore.COPY_BUFFER_BYTES];Arrays.fill(block,value);try(FileChannel out=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){long count=0;while(count<size){int n=(int)Math.min(block.length,size-count);ByteBuffer b=ByteBuffer.wrap(block,0,n);while(b.hasRemaining())out.write(b);count+=n;}out.force(true);}return path;}
    private static String sha(Path p)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(p)){byte[] b=new byte[StreamingGroupStore.COPY_BUFFER_BYTES];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte x:digest.digest())s.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return s.toString();}
    private static String sha(byte[] bytes)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(bytes);StringBuilder s=new StringBuilder();for(byte x:digest.digest())s.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return s.toString();}
    private static String fileIdentity(Path p)throws Exception{StructStat stat=Os.lstat(p.toString());return stat.st_dev+":"+stat.st_ino+":"+stat.st_size+":"+stat.st_nlink+":"+stat.st_mode+":"+stat.st_mtim.tv_sec+":"+stat.st_mtim.tv_nsec+":"+stat.st_ctim.tv_sec+":"+stat.st_ctim.tv_nsec;}
    private static void expectFailure(Throwing action)throws Exception{try{action.run();fail("expected IOException");}catch(IOException expected){}}
    private static void expectCode(String code,Throwing action)throws Exception{try{action.run();fail("expected "+code);}catch(IOException expected){assertEquals(code,expected.getMessage());}}
    private static void expectIo(Throwing action)throws Exception{expectFailure(action);}
    private static void deleteOwned(Path root)throws IOException{if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;try(java.util.stream.Stream<Path> paths=Files.walk(root)){Path[] all=paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new);for(Path p:all)Files.deleteIfExists(p);}}
    private interface Throwing{void run()throws Exception;}
}
