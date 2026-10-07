package com.padnote.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.io.InputStream;
import java.util.ArrayList;

/** Device verification for app-private snapshot consistency and archive roundtrip. */
@RunWith(AndroidJUnit4.class)
public final class LibraryBackupSnapshotInstrumentedTest {
    @Test public void v2MixedRestoreRebackupAndZeroNoteMaterialRestore() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File sourceRoot=new File(target.getCacheDir(),"v2-mixed-source-"+UUID.randomUUID());assertTrue(sourceRoot.mkdir());
        Context source=isolatedContext(target,sourceRoot);File sourceStage=new File(source.getFilesDir(),"library-backup");assertTrue(sourceStage.mkdirs());
        File input=new File(sourceRoot,"application-valid.zip");
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("library-backup-r1/valid-library.zip");FileOutputStream out=new FileOutputStream(input)){
            byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.flush();out.getFD().sync();
        }
        File restoredRoot=new File(target.getCacheDir(),"v2-mixed-restored-"+UUID.randomUUID());assertTrue(restoredRoot.mkdir());
        Context restored=isolatedContext(target,restoredRoot);File restoredStage=new File(restored.getFilesDir(),"library-backup");assertTrue(restoredStage.mkdirs());
        File secondRoot=new File(target.getCacheDir(),"v2-zero-note-destination-"+UUID.randomUUID());assertTrue(secondRoot.mkdir());
        Context second=isolatedContext(target,secondRoot);File secondStage=new File(second.getFilesDir(),"library-backup");assertTrue(secondStage.mkdirs());
        try{
            Map<String,String> imported;
            try(LibraryBackupArchive.StagedArchive stage=LibraryBackupArchive.stage(input,sourceStage,null,null)){
                assertEquals(2,stage.manifest().notes.size());assertEquals(2,stage.manifest().videoAttachments.size());
                imported=new LibraryRestoreTransaction(restored,stage).restoreNotes(null,null);
            }
            assertEquals(2,NoteStore.list(restored).size());
            RestoredLibraryMaterialStore.ListResult materialRows=new RestoredLibraryMaterialStore(restored.getFilesDir()).listDetailed(
                    RestoredLibraryMaterialStore.committedV2Gate(restored.getFilesDir()));
            assertTrue("unexpected restored material diagnostics: "+materialRows.diagnostics,materialRows.diagnostics.isEmpty());
            assertEquals(5,materialRows.records.size());
            String restoredNote=imported.values().iterator().next();assertTrue(CoverStore.has(restored,restoredNote));
            for(LibraryBackupManifest.Note n:stageManifest(input,sourceStage).notes){if(n.pdfResourceId!=null){String mapped=imported.get(n.itemId);assertTrue(NoteStore.pdfFile(restored,mapped).isFile());}}
            File recapture=new File(sourceRoot,"recaptured.zip");
            try(LibraryBackupSnapshot snapshot=LibraryBackupSnapshot.create(restored,new HashSet<>(imported.values()),null,null)){
                snapshot.write(recapture,null,null);
            }
            try(LibraryBackupArchive.StagedArchive validated=LibraryBackupArchive.stage(recapture,sourceStage,null,null)){
                assertEquals(2,validated.manifest().notes.size());assertEquals(2,validated.manifest().vaultEntries.size());
                assertEquals(2,validated.manifest().videoAttachments.size());assertEquals(1,validated.manifest().coverPresets.size());
                new LibraryRestoreTransaction(second,validated).restoreNotes(null,null);
            }
            assertEquals(2,NoteStore.list(second).size());
            for(NoteStore.Entry n:new ArrayList<>(NoteStore.list(restored)))NoteStore.delete(restored,n.id);
            File zeroZip=new File(sourceRoot,"zero-note-materials.zip");
            try(LibraryBackupSnapshot snapshot=LibraryBackupSnapshot.create(restored,Collections.emptySet(),null,null)){
                assertEquals(0,snapshot.manifest.notes.size());assertEquals(5,snapshot.manifest.resources.size());snapshot.write(zeroZip,null,null);
            }
            try(LibraryBackupArchive.StagedArchive zero=LibraryBackupArchive.stage(zeroZip,sourceStage,null,null)){
                assertTrue(zero.manifest().notes.isEmpty());assertEquals(2,zero.manifest().vaultEntries.size());
                LibraryRestoreTransaction emptyNotes=new LibraryRestoreTransaction(second,zero);
                assertTrue(emptyNotes.localNoteIds().isEmpty());emptyNotes.restoreNotes(null,null);
            }
            int zeroMaterials=new RestoredLibraryMaterialStore(second.getFilesDir()).listDetailed(
                    RestoredLibraryMaterialStore.committedV2Gate(second.getFilesDir())).records.size();
            assertEquals(10,zeroMaterials);
            File external=target.getExternalFilesDir("library-restore-v2-reference");assertNotNull(external);if(!external.exists())assertTrue(external.mkdirs());
            File deliverable=new File(external,"zero-note-material-roundtrip.zip");copyBounded(zeroZip,deliverable,2L*1024*1024);
            String sha=sha256(read(deliverable));File evidence=new File(external,"zero-note-material-roundtrip.json");
            JSONObject report=new JSONObject().put("schema_version",1).put("archive_file",deliverable.getName()).put("byte_length",deliverable.length()).put("sha256",sha).put("notes",0).put("vault_entries",2).put("video_attachments",2).put("cover_presets",1);
            try(FileOutputStream out=new FileOutputStream(evidence)){out.write(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
            System.out.println("V2_REFERENCE_ARCHIVE path="+deliverable.getAbsolutePath()+" bytes="+deliverable.length()+" sha256="+sha);
        }finally{deleteTree(sourceRoot);deleteTree(restoredRoot);deleteTree(secondRoot);}
    }

    @Test public void v2OwnerCommittedBeforeMarkerAndInterruptedRollbackResumeSafely() throws Exception {
        RestoreFixture f=restoreFixture("v2-owner-rollback-resume");final boolean[] ownerCrash={false},rollbackCrash={false};
        try{
            LibraryBackupArchive.StagedArchive stage=LibraryBackupArchive.stage(f.archive,f.stageParent,null,null);
            LibraryRestoreTransaction tx=new LibraryRestoreTransaction(f.context,stage,new LibraryRestoreTransaction.FaultInjector(){
                public void afterResourcesPromoted(String groupId){}
                @Override public void afterNoteOwnerCommittedBeforeGroupMarker(String groupId)throws Exception{if(!ownerCrash[0]){ownerCrash[0]=true;throw new java.io.IOException("TEST_OWNER_COMMITTED_BEFORE_GROUP_MARKER");}}
                @Override public void afterRollbackMemberProcessed(String groupId,String memberId)throws Exception{if(!rollbackCrash[0]){rollbackCrash[0]=true;throw new java.io.IOException("TEST_ROLLBACK_INTERRUPTED");}}
            });
            Map<String,String> ids=tx.localNoteIds();File transactionDirectory=tx.transactionDirectory();
            try{tx.restoreNotes(null,null);fail("owner/group commit boundary fault expected");}
            catch(java.io.IOException expected){assertEquals("TEST_OWNER_COMMITTED_BEFORE_GROUP_MARKER",expected.getMessage());}
            assertTrue(ownerCrash[0]);assertEquals(2,NoteStore.list(f.context).size());
            String hidden=ids.values().iterator().next();try{NoteStore.load(f.context,hidden);fail("group gate must hide owner-committed note");}
            catch(IllegalStateException expected){assertEquals("RESTORE_NOTE_PENDING",expected.getMessage());}
            try{tx.rollbackUncommitted();fail("rollback interruption expected");}
            catch(java.io.IOException expected){assertEquals("TEST_ROLLBACK_INTERRUPTED",expected.getMessage());}
            assertTrue(rollbackCrash[0]);
            LibraryRestoreTransaction resumed=LibraryRestoreTransaction.resume(f.context,transactionDirectory);
            assertEquals(ids,resumed.localNoteIds());resumed.rollbackUncommitted();
            assertEquals(2,NoteStore.list(f.context).size());
            for(String original:f.originalIds)assertEquals(original,NoteStore.load(f.context,original).getString("id"));
            for(String id:ids.values())assertFalse(NoteStore.noteFileForBackup(f.context,id).exists());
        }finally{f.cleanup();}
    }

    @Test public void falseMemberVerificationCannotPublishGroupMarker() throws Exception {
        RestoreFixture f=restoreFixture("v2-false-verifier");final LibraryRestoreTransaction[] holder={null};final boolean[] rejectCommitVerifier={true};
        try{
            LibraryBackupArchive.StagedArchive stage=LibraryBackupArchive.stage(f.archive,f.stageParent,null,null);
            holder[0]=new LibraryRestoreTransaction(f.context,stage,new LibraryRestoreTransaction.FaultInjector(){
                public void afterResourcesPromoted(String groupId){}
                @Override public boolean allowCommitVerifier(String groupId,String memberId){return !rejectCommitVerifier[0];}
            });
            try{holder[0].restoreNotes(null,null);fail("false member verifier must stop commit");}
            catch(java.io.IOException expected){assertEquals("RESTORE_V2_MEMBER_VERIFY_FAILED",expected.getMessage());}
            assertEquals(2,NoteStore.list(f.context).size());
            try{NoteStore.load(f.context,holder[0].localNoteIds().values().iterator().next());fail("owner commit without group marker must remain hidden");}
            catch(IllegalStateException expected){assertEquals("RESTORE_NOTE_PENDING",expected.getMessage());}
            rejectCommitVerifier[0]=false;
            holder[0].restoreNotes(null,null);assertEquals(4,NoteStore.list(f.context).size());
        }finally{f.cleanup();}
    }

    @Test public void durableMarkerPayloadStaysHiddenUntilExclusiveDirectoryCommitAndResumes() throws Exception {
        RestoreFixture f=restoreFixture("v2-commit-path-reservation");final LibraryRestoreTransaction[] holder={null};final boolean[] injected={false};
        try{
            LibraryBackupArchive.StagedArchive stage=LibraryBackupArchive.stage(f.archive,f.stageParent,null,null);
            holder[0]=new LibraryRestoreTransaction(f.context,stage,new LibraryRestoreTransaction.FaultInjector(){
                public void afterResourcesPromoted(String groupId){}
                @Override public void afterCommitReservationDurable(String groupId){}
                @Override public void afterCommitPathReservationDurable(String groupId)throws Exception{if(!injected[0]){injected[0]=true;throw new java.io.IOException("TEST_AFTER_COMMIT_PATH_RESERVATION");}}
            });
            try{holder[0].restoreNotes(null,null);fail("injected crash before exclusive directory commit point expected");}
            catch(java.io.IOException expected){assertEquals("TEST_AFTER_COMMIT_PATH_RESERVATION",expected.getMessage());}
            assertTrue(injected[0]);assertEquals(2,NoteStore.list(f.context).size());
            try{NoteStore.load(f.context,holder[0].localNoteIds().values().iterator().next());fail("reservation sentinel must not be a commit point");}
            catch(IllegalStateException expected){assertEquals("RESTORE_NOTE_PENDING",expected.getMessage());}
            LibraryRestoreTransaction resumed=LibraryRestoreTransaction.resume(f.context,holder[0].transactionDirectory());
            resumed.restoreNotes(null,null);assertEquals(4,NoteStore.list(f.context).size());
        }finally{f.cleanup();}
    }

    @Test public void directoryCommitSurvivesCrashBeforeParentSyncAndResume() throws Exception {
        RestoreFixture f=restoreFixture("v2-commit-directory-fsync");final LibraryRestoreTransaction[] holder={null};final boolean[] injected={false};
        try{
            LibraryBackupArchive.StagedArchive stage=LibraryBackupArchive.stage(f.archive,f.stageParent,null,null);
            holder[0]=new LibraryRestoreTransaction(f.context,stage,new LibraryRestoreTransaction.FaultInjector(){
                public void afterResourcesPromoted(String groupId){}
                @Override public void afterCommitDirectoryCreated(String groupId)throws Exception{if(!injected[0]){injected[0]=true;throw new java.io.IOException("TEST_AFTER_COMMIT_DIRECTORY_MKDIR");}}
            });
            try{holder[0].restoreNotes(null,null);fail("fault after atomic directory commit point expected");}
            catch(java.io.IOException expected){assertEquals("TEST_AFTER_COMMIT_DIRECTORY_MKDIR",expected.getMessage());}
            assertTrue(injected[0]);assertEquals(3,NoteStore.list(f.context).size());
            int visible=0,notInstalled=0;for(String id:holder[0].localNoteIds().values()){
                File noteFile=NoteStore.noteFileForBackup(f.context,id);
                if(noteFile.isFile()){assertNotNull(NoteStore.load(f.context,id));visible++;}
                else {assertFalse("unstarted second group must not have a note file",noteFile.exists());
                    assertFalse("unstarted second group must not appear in the visible shelf",containsNoteId(NoteStore.list(f.context),id));notInstalled++;}
            }
            assertEquals("only the first group crossed its atomic commit point",1,visible);assertEquals(1,notInstalled);
            LibraryRestoreTransaction resumed=LibraryRestoreTransaction.resume(f.context,holder[0].transactionDirectory());
            resumed.restoreNotes(null,null);assertEquals(4,NoteStore.list(f.context).size());
        }finally{f.cleanup();}
    }

    @Test public void legacySchemaV1JournalStillResumesWithoutV2Inference() throws Exception {
        RestoreFixture f=restoreFixture("legacy-v1-resume");
        try{
            LibraryBackupArchive.StagedArchive stage=LibraryBackupArchive.stage(f.archive,f.stageParent,null,null);
            LibraryRestoreTransaction tx=new LibraryRestoreTransaction(f.context,stage);File directory=tx.transactionDirectory();
            JSONObject v2=new JSONObject(new String(read(new File(directory,"journal.json")),java.nio.charset.StandardCharsets.UTF_8));
            org.json.JSONArray rows=new org.json.JSONArray();org.json.JSONArray groups=v2.getJSONArray("groups");
            for(int i=0;i<groups.length();i++){JSONObject g=groups.getJSONObject(i);if(g.isNull("note_item_id"))continue;
                rows.put(new JSONObject().put("item_id",g.getString("note_item_id")).put("local_note_id",g.getString("local_note_id"))
                        .put("state","staged").put("updated_at",g.getLong("updated_at")).put("note_sha256",g.getString("note_sha256"))
                        .put("pdf_sha256",g.isNull("pdf_sha256")?org.json.JSONObject.NULL:g.getString("pdf_sha256"))
                        .put("cover_sha256",g.isNull("cover_sha256")?org.json.JSONObject.NULL:g.getString("cover_sha256")));
            }
            JSONObject legacy=new JSONObject().put("schema_version",1).put("transaction_id",v2.getString("transaction_id"))
                    .put("archive_sha256",v2.getString("archive_sha256")).put("stage_directory",v2.getString("stage_directory"))
                    .put("transfer_marker_sha256",v2.getString("transfer_marker_sha256")).put("phase","staged").put("groups",rows);
            try(FileOutputStream out=new FileOutputStream(new File(directory,"journal.json"),false)){out.write(legacy.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
            LibraryRestoreTransaction resumed=LibraryRestoreTransaction.resume(f.context,directory);
            assertEquals(2,resumed.localNoteIds().size());resumed.restoreNotes(null,null);assertEquals(4,NoteStore.list(f.context).size());
        }finally{f.cleanup();}
    }

    private static LibraryBackupManifest stageManifest(File archive,File stageParent)throws Exception{
        try(LibraryBackupArchive.StagedArchive staged=LibraryBackupArchive.stage(archive,stageParent,null,null)){return staged.manifest();}
    }
    private static void copyBounded(File from,File to,long max)throws Exception{
        if(from.length()>max)throw new java.io.IOException("TEST_ARCHIVE_LIMIT");
        try(FileInputStream in=new FileInputStream(from);FileOutputStream out=new FileOutputStream(to)){byte[] b=new byte[16384];long total=0;int n;while((n=in.read(b))!=-1){total+=n;if(total>max)throw new java.io.IOException("TEST_ARCHIVE_LIMIT");out.write(b,0,n);}out.flush();out.getFD().sync();}
    }

    @Test public void snapshotFreezesExactNoteBytesAndWriterStagesSameDigest() throws Exception {
        File sandbox = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getCacheDir(), "library-backup-test-" + UUID.randomUUID());
        assertTrue(sandbox.mkdir());
        Context context = isolatedContext(InstrumentationRegistry.getInstrumentation().getTargetContext(), sandbox);
        NoteStore.Entry note = NoteStore.create(context, "快照固定材料");
        try {
            JSONObject original = NoteStore.load(context, note.id);
            original.put("title", "快照固定材料");
            NoteStore.save(context, note.id, "快照固定材料", original.toString());
            byte[] sourceAtSnapshot = read(NoteStore.noteFileForBackup(context, note.id));
            Set<String> selected = new HashSet<>();
            selected.add(note.id);
            File output = new File(sandbox, "snapshot.padnote-library.zip");
            File stageParent = new File(sandbox, "verified-stage");
            assertTrue(stageParent.mkdir());
            try (LibraryBackupSnapshot snapshot = LibraryBackupSnapshot.create(
                    context, selected, null, null)) {
                assertEquals(1, snapshot.manifest.notes.size());
                LibraryBackupManifest.Note descriptor = snapshot.manifest.notes.get(0);
                File payload = snapshot.resourceFiles.get(descriptor.noteResourceId);
                assertArrayEquals(sourceAtSnapshot, read(payload));
                String digest = sha256(sourceAtSnapshot);
                assertEquals(digest, snapshot.manifest.resourceById(descriptor.noteResourceId).sha256);

                JSONObject changed = NoteStore.load(context, note.id);
                changed.put("title", "在快照后编辑");
                NoteStore.save(context, note.id, "在快照后编辑", changed.toString());
                LibraryBackupArchive.ArchiveResult written = snapshot.write(output, null, null);
                assertEquals(output.length(), written.byteLength);
                assertTrue(output.length() > 0);
            }
            try (LibraryBackupArchive.StagedArchive staged = LibraryBackupArchive.stage(
                    output, stageParent, null, null)) {
                LibraryBackupManifest.Note descriptor = staged.manifest().notes.get(0);
                assertEquals(1, staged.manifest().notes.size());
                assertEquals(sha256(sourceAtSnapshot), staged.manifest()
                        .resourceById(descriptor.noteResourceId).sha256);
                assertArrayEquals(sourceAtSnapshot, read(staged.resourceFile(descriptor.noteResourceId)));
            }
        } finally {
            NoteStore.delete(context, note.id);
            deleteTree(sandbox);
        }
    }

    @Test public void symlinkedNotesDirectoryIsRejectedBeforeNoteStoreTraversal() throws Exception {
        File sandbox = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getCacheDir(), "library-backup-link-test-" + UUID.randomUUID());
        assertTrue(sandbox.mkdir());
        Context context = isolatedContext(InstrumentationRegistry.getInstrumentation().getTargetContext(), sandbox);
        File redirected = new File(sandbox, "redirected");
        assertTrue(redirected.mkdir());
        File notes = new File(context.getFilesDir(), NoteStore.notesDirectoryName());
        Os.symlink(redirected.getAbsolutePath(), notes.getAbsolutePath());
        try {
            try {
                LibraryBackupSnapshot.create(context, Collections.singleton("missing-note"), null, null);
                fail("snapshot must reject a linked notes root");
            } catch (Exception expected) {
                assertTrue(expected.getMessage() == null || expected.getMessage().contains("BACKUP_DIRECTORY_UNSAFE"));
            }
            assertEquals(0, redirected.list().length);
        } finally {
            Os.remove(notes.getAbsolutePath());
            deleteTree(sandbox);
        }
    }

    @Test public void restoresCopyAndCanCaptureWriteAndRestoreThatCopyAgain() throws Exception {
        File sandbox = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getCacheDir(), "library-backup-recapture-test-" + UUID.randomUUID());
        assertTrue(sandbox.mkdir());
        Context context = isolatedContext(InstrumentationRegistry.getInstrumentation().getTargetContext(), sandbox);
        File stageParent = new File(context.getFilesDir(), "library-backup"); assertTrue(stageParent.mkdirs());
        NoteStore.Entry original = NoteStore.create(context, "整库往返原笔记");
        try {
            File firstZip = new File(sandbox, "first.zip");
            try (LibraryBackupSnapshot snapshot=LibraryBackupSnapshot.create(context,
                    Collections.singleton(original.id),null,null)) { snapshot.write(firstZip,null,null); }
            String firstSource = null;
            MapHolder first;
            try (LibraryBackupArchive.StagedArchive staged=LibraryBackupArchive.stage(firstZip,stageParent,null,null)) {
                firstSource=staged.manifest().notes.get(0).sourceNoteId;
                LibraryRestoreTransaction transaction=new LibraryRestoreTransaction(context,staged);
                assertTransactionUnder(context,transaction);
                first=new MapHolder(transaction.restoreNotes(null,null));
            }
            String restoredId=first.ids.values().iterator().next();
            assertNotEquals(original.id,restoredId);
            assertEquals(restoredId,NoteStore.load(context,restoredId).getString("id"));
            File secondZip=new File(sandbox,"second.zip");
            try(LibraryBackupSnapshot snapshot=LibraryBackupSnapshot.create(context,
                    Collections.singleton(restoredId),null,null)){snapshot.write(secondZip,null,null);}
            try(LibraryBackupArchive.StagedArchive second=LibraryBackupArchive.stage(secondZip,stageParent,null,null)){
                assertEquals(restoredId,second.manifest().notes.get(0).sourceNoteId);
                assertEquals(1,second.manifest().notes.size());
                assertEquals(firstSource,NoteStore.load(context,original.id).getString("id"));
                LibraryRestoreTransaction transaction=new LibraryRestoreTransaction(context,second);
                assertTransactionUnder(context,transaction);
                Map<String,String> third=transaction.restoreNotes(null,null);
                String thirdId=third.values().iterator().next();
                assertNotEquals(restoredId,thirdId);
                assertEquals(thirdId,NoteStore.load(context,thirdId).getString("id"));
            }
        } finally {
            for(NoteStore.Entry entry:NoteStore.list(context)) NoteStore.delete(context,entry.id);
            deleteTree(sandbox);
        }
    }

    @Test public void partialRestoreResumesWithSameIdsAndKeepsCommittedGroup() throws Exception {
        RestoreFixture f=restoreFixture("resume");
        try{
            LibraryBackupArchive.StagedArchive stage=LibraryBackupArchive.stage(f.archive,f.stageParent,null,null);
            final int[] seen={0};LibraryRestoreTransaction tx=new LibraryRestoreTransaction(f.context,stage,
                    group->{if(++seen[0]==2)throw new java.io.IOException("TEST_CRASH_AFTER_RESOURCES");});
            assertTransactionUnder(f.context,tx);
            Map<String,String> ids=tx.localNoteIds();File txDir=tx.transactionDirectory();
            try{tx.restoreNotes(null,null);fail("injected interruption expected");}catch(java.io.IOException expected){assertEquals("TEST_CRASH_AFTER_RESOURCES",expected.getMessage());}
            assertEquals(3,NoteStore.list(f.context).size());
            LibraryRestoreTransaction resumed=LibraryRestoreTransaction.resume(f.context,txDir);
            assertTransactionUnder(f.context,resumed);
            assertEquals(ids,resumed.localNoteIds());resumed.restoreNotes(null,null);
            assertEquals(4,NoteStore.list(f.context).size());
            for(String id:ids.values())assertEquals(id,NoteStore.load(f.context,id).getString("id"));
        }finally{f.cleanup();}
    }

    @Test public void noteAndIndexStayHiddenUntilAtomicRestoreGateAndResumeSameId() throws Exception {
        RestoreFixture f=restoreFixture("visible-gate");
        try{
            LibraryBackupArchive.StagedArchive stage=LibraryBackupArchive.stage(f.archive,f.stageParent,null,null);
            LibraryRestoreTransaction tx=new LibraryRestoreTransaction(f.context,stage,new LibraryRestoreTransaction.FaultInjector(){
                public void afterResourcesPromoted(String groupId){}
                public void afterNoteIndexedBeforeVisibleCommit(String groupId)throws Exception{throw new java.io.IOException("TEST_CRASH_BEFORE_VISIBLE_COMMIT");}
            });
            assertTransactionUnder(f.context,tx);
            Map<String,String> ids=tx.localNoteIds();File txDir=tx.transactionDirectory();String firstId=ids.values().iterator().next();
            try{tx.restoreNotes(null,null);fail("injected crash expected");}catch(java.io.IOException expected){assertEquals("TEST_CRASH_BEFORE_VISIBLE_COMMIT",expected.getMessage());}
            assertEquals(2,NoteStore.list(f.context).size());
            try{NoteStore.load(f.context,firstId);fail("pending note must not be readable");}
            catch(IllegalStateException expected){assertEquals("RESTORE_NOTE_PENDING",expected.getMessage());}
            try{NoteStore.delete(f.context,firstId);fail("pending note must not be deletable through the library API");}
            catch(IllegalStateException expected){assertEquals("RESTORE_NOTE_PENDING",expected.getMessage());}
            LibraryRestoreTransaction resumed=LibraryRestoreTransaction.resume(f.context,txDir);
            assertTransactionUnder(f.context,resumed);
            assertEquals(ids,resumed.localNoteIds());resumed.restoreNotes(null,null);
            assertEquals(4,NoteStore.list(f.context).size());
            assertEquals(firstId,NoteStore.load(f.context,firstId).getString("id"));
        }finally{f.cleanup();}
    }

    @Test public void danglingRestoreOwnerMarkerIsReportedPerRowWithoutBlockingOtherNotes() throws Exception {
        File sandbox=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                "library-backup-bad-owner-"+UUID.randomUUID());assertTrue(sandbox.mkdir());
        Context context=isolatedContext(InstrumentationRegistry.getInstrumentation().getTargetContext(),sandbox);
        NoteStore.Entry good=NoteStore.create(context,"正常笔记"),bad=NoteStore.create(context,"待检查笔记");
        File marker=new File(new File(context.getFilesDir(),NoteStore.notesDirectoryName()),bad.id+".restore-owner");
        File missingTarget=new File(sandbox,"missing-owner-target");Os.symlink(missingTarget.getAbsolutePath(),marker.getAbsolutePath());
        try{
            java.util.List<NoteStore.Entry> visible=NoteStore.list(context);
            assertEquals(1,visible.size());assertEquals(good.id,visible.get(0).id);
            assertEquals(good.id,NoteStore.load(context,good.id).getString("id"));
            assertErrorCode("RESTORE_OWNER_INVALID",()->NoteStore.load(context,bad.id));
            assertErrorCode("RESTORE_OWNER_INVALID",()->NoteStore.save(context,bad.id,"待检查笔记","{}"));
            assertErrorCode("RESTORE_OWNER_INVALID",()->NoteStore.delete(context,bad.id));
            boolean reported=false;for(NoteStore.RecoveryEntry entry:NoteStore.listRecovery(context))
                if(entry.noteId.equals(bad.id)&&entry.label.equals("导入状态需要检查"))reported=true;
            assertTrue("bad row must remain diagnosable",reported);
            assertTrue("unknown marker must remain untouched",OsConstants.S_ISLNK(Os.lstat(marker.getAbsolutePath()).st_mode));
        }finally{try{NoteStore.delete(context,good.id);}catch(Exception ignored){}deleteTree(sandbox);}
    }

    @Test public void ownerTemporarySyncedBeforeCrashIsRecoveredOnResumeWithSameIds() throws Exception {
        RestoreFixture f=restoreFixture("owner-temp-resume");
        try{
            LibraryBackupArchive.StagedArchive stage=LibraryBackupArchive.stage(f.archive,f.stageParent,null,null);
            final boolean[] injected={false};LibraryRestoreTransaction tx=new LibraryRestoreTransaction(f.context,stage,
                    new LibraryRestoreTransaction.FaultInjector(){
                        public void afterResourcesPromoted(String groupId){}
                        public void afterRestoreOwnerTemporarySynced(String groupId)throws Exception{
                            if(!injected[0]){injected[0]=true;throw new java.io.IOException("TEST_CRASH_AFTER_OWNER_TEMP_SYNC");}
                        }
                    });
            assertTransactionUnder(f.context,tx);Map<String,String> ids=tx.localNoteIds();File directory=tx.transactionDirectory();
            String localId=ids.values().iterator().next();File ownerTemp=new File(new File(f.context.getFilesDir(),NoteStore.notesDirectoryName()),localId+".restore-owner.tmp");
            try{tx.restoreNotes(null,null);fail("injected crash expected");}
            catch(java.io.IOException expected){assertEquals("TEST_CRASH_AFTER_OWNER_TEMP_SYNC",expected.getMessage());}
            assertTrue(injected[0]);assertTrue(ownerTemp.isFile());assertEquals(2,NoteStore.list(f.context).size());
            LibraryRestoreTransaction resumed=LibraryRestoreTransaction.resume(f.context,directory);
            assertEquals(ids,resumed.localNoteIds());resumed.restoreNotes(null,null);
            assertFalse(ownerTemp.exists());assertEquals(4,NoteStore.list(f.context).size());
            for(String id:ids.values())assertEquals(id,NoteStore.load(f.context,id).getString("id"));
        }finally{f.cleanup();}
    }

    @Test public void exactCommittedOwnerTemporaryCanBeRolledBackWithoutTouchingOriginals() throws Exception {
        RestoreFixture f=restoreFixture("owner-temp-rollback");
        try{
            LibraryBackupArchive.StagedArchive stage=LibraryBackupArchive.stage(f.archive,f.stageParent,null,null);
            LibraryRestoreTransaction tx=new LibraryRestoreTransaction(f.context,stage,new LibraryRestoreTransaction.FaultInjector(){
                private boolean injected;
                public void afterResourcesPromoted(String groupId){}
                public void afterRestoreOwnerTemporarySynced(String groupId)throws Exception{
                    if(!injected){injected=true;throw new java.io.IOException("TEST_CRASH_AFTER_OWNER_TEMP_SYNC");}
                }
            });
            Map<String,String> ids=tx.localNoteIds();File transactionDirectory=tx.transactionDirectory();String localId=ids.values().iterator().next();
            File ownerTemp=new File(new File(f.context.getFilesDir(),NoteStore.notesDirectoryName()),localId+".restore-owner.tmp");
            try{tx.restoreNotes(null,null);fail("injected crash expected");}
            catch(java.io.IOException expected){assertEquals("TEST_CRASH_AFTER_OWNER_TEMP_SYNC",expected.getMessage());}
            assertTrue(ownerTemp.isFile());assertEquals(2,NoteStore.list(f.context).size());
            LibraryRestoreTransaction resumed=LibraryRestoreTransaction.resume(f.context,transactionDirectory);
            assertEquals(ids,resumed.localNoteIds());resumed.rollbackUncommitted();
            assertFalse(ownerTemp.exists());assertFalse(NoteStore.noteFileForBackup(f.context,localId).exists());
            assertEquals(2,NoteStore.list(f.context).size());
            for(String originalId:f.originalIds)assertEquals(originalId,NoteStore.load(f.context,originalId).getString("id"));
        }finally{f.cleanup();}
    }

    @Test public void unknownOwnerTemporaryIsPreservedAndCannotAuthorizeRollback() throws Exception {
        RestoreFixture f=restoreFixture("owner-temp-unknown");
        try{
            LibraryBackupArchive.StagedArchive stage=LibraryBackupArchive.stage(f.archive,f.stageParent,null,null);
            LibraryRestoreTransaction tx=new LibraryRestoreTransaction(f.context,stage,new LibraryRestoreTransaction.FaultInjector(){
                private boolean injected;
                public void afterResourcesPromoted(String groupId){}
                public void afterRestoreOwnerTemporarySynced(String groupId)throws Exception{
                    if(!injected){injected=true;throw new java.io.IOException("TEST_CRASH_AFTER_OWNER_TEMP_SYNC");}
                }
            });
            Map<String,String> ids=tx.localNoteIds();File transactionDirectory=tx.transactionDirectory();String localId=ids.values().iterator().next();
            File ownerTemp=new File(new File(f.context.getFilesDir(),NoteStore.notesDirectoryName()),localId+".restore-owner.tmp");
            try{tx.restoreNotes(null,null);fail("injected crash expected");}
            catch(java.io.IOException expected){assertEquals("TEST_CRASH_AFTER_OWNER_TEMP_SYNC",expected.getMessage());}
            try(FileOutputStream out=new FileOutputStream(ownerTemp,false)){out.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
            byte[] unknown=read(ownerTemp);
            LibraryRestoreTransaction resumed=LibraryRestoreTransaction.resume(f.context,transactionDirectory);
            assertEquals(ids,resumed.localNoteIds());
            assertErrorCode("RESTORE_OWNER_TEMP_MISMATCH",()->resumed.restoreNotes(null,null));
            assertErrorCode("RESTORE_OWNER_TEMP_MISMATCH",resumed::rollbackUncommitted);
            assertArrayEquals(unknown,read(ownerTemp));assertEquals(2,NoteStore.list(f.context).size());
            assertTrue(NoteStore.noteFileForBackup(f.context,localId).isFile());
        }finally{f.cleanup();}
    }

    @Test public void changedPendingOwnerMarkerIsNotOverwrittenByCommittedTemporary() throws Exception {
        RestoreFixture f=restoreFixture("owner-marker-race");
        try{
            LibraryBackupArchive.StagedArchive stage=LibraryBackupArchive.stage(f.archive,f.stageParent,null,null);
            final String[] localId={null};final boolean[] replaced={false};
            LibraryRestoreTransaction tx=new LibraryRestoreTransaction(f.context,stage,new LibraryRestoreTransaction.FaultInjector(){
                public void afterResourcesPromoted(String groupId){}
                public void afterRestoreOwnerTemporarySynced(String groupId)throws Exception{
                    if(!replaced[0]){
                        replaced[0]=true;File marker=new File(new File(f.context.getFilesDir(),NoteStore.notesDirectoryName()),localId[0]+".restore-owner");
                        try(FileOutputStream out=new FileOutputStream(marker,false)){out.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
                    }
                }
            });
            Map<String,String> ids=tx.localNoteIds();localId[0]=ids.values().iterator().next();
            File ownerTemp=new File(new File(f.context.getFilesDir(),NoteStore.notesDirectoryName()),localId[0]+".restore-owner.tmp");
            File marker=new File(new File(f.context.getFilesDir(),NoteStore.notesDirectoryName()),localId[0]+".restore-owner");
            try{tx.restoreNotes(null,null);fail("replaced pending marker must not be overwritten");}
            catch(java.io.IOException expected){assertEquals("RESTORE_OWNER_CHANGED",expected.getMessage());}
            assertTrue(replaced[0]);assertTrue(ownerTemp.isFile());assertArrayEquals("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),read(marker));
            assertEquals(2,NoteStore.list(f.context).size());
        }finally{f.cleanup();}
    }

    @Test public void rollbackRemovesOnlyIncompleteGroupAndPreservesOriginalAndCommittedCopy() throws Exception {
        RestoreFixture f=restoreFixture("rollback");
        try{
            LibraryBackupArchive.StagedArchive stage=LibraryBackupArchive.stage(f.archive,f.stageParent,null,null);
            final int[] seen={0};LibraryRestoreTransaction tx=new LibraryRestoreTransaction(f.context,stage,
                    group->{if(++seen[0]==2)throw new java.io.IOException("TEST_CRASH_AFTER_RESOURCES");});
            assertTransactionUnder(f.context,tx);
            Map<String,String> ids=tx.localNoteIds();
            try{tx.restoreNotes(null,null);fail("injected interruption expected");}catch(java.io.IOException expected){}
            tx.rollbackUncommitted();
            assertEquals(3,NoteStore.list(f.context).size());
            for(String original:f.originalIds)assertEquals(original,NoteStore.load(f.context,original).getString("id"));
            int copies=0;for(String id:ids.values())if(new File(f.context.getFilesDir(),"notes/"+id+".json").exists())copies++;
            assertEquals(1,copies);
        }finally{f.cleanup();}
    }

    @Test public void inconsistentJournalRefusesResumeWithoutDeletingNotes() throws Exception {
        RestoreFixture f=restoreFixture("journal");
        try{
            LibraryBackupArchive.StagedArchive stage=LibraryBackupArchive.stage(f.archive,f.stageParent,null,null);
            LibraryRestoreTransaction tx=new LibraryRestoreTransaction(f.context,stage);
            assertTransactionUnder(f.context,tx);
            File journal=new File(tx.transactionDirectory(),"journal.json");
            try(FileOutputStream out=new FileOutputStream(journal,false)){out.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
            try{LibraryRestoreTransaction.resume(f.context,tx.transactionDirectory());fail("inconsistent journal must fail closed");}
            catch(Exception expected){assertNotNull(expected);}
            assertEquals(2,NoteStore.list(f.context).size());
            for(String original:f.originalIds)assertEquals(original,NoteStore.load(f.context,original).getString("id"));
        }finally{f.cleanup();}
    }

    @Test public void journalCannotReplaceSourceIdOrDuplicateAllocatedIds() throws Exception {
        RestoreFixture f=restoreFixture("journal-id");
        try{
            LibraryBackupArchive.StagedArchive stage=LibraryBackupArchive.stage(f.archive,f.stageParent,null,null);
            LibraryRestoreTransaction tx=new LibraryRestoreTransaction(f.context,stage);
            assertTransactionUnder(f.context,tx);
            rewriteJournalIds(tx.transactionDirectory(),f.originalIds.get(0),null);
            try{LibraryRestoreTransaction.resume(f.context,tx.transactionDirectory());fail("source ID substitution must be rejected");}
            catch(Exception expected){assertNotNull(expected);}
            for(String original:f.originalIds)assertEquals(original,NoteStore.load(f.context,original).getString("id"));
        }finally{f.cleanup();}

        RestoreFixture duplicate=restoreFixture("journal-duplicate");
        try{
            LibraryBackupArchive.StagedArchive stage=LibraryBackupArchive.stage(duplicate.archive,duplicate.stageParent,null,null);
            LibraryRestoreTransaction tx=new LibraryRestoreTransaction(duplicate.context,stage);
            assertTransactionUnder(duplicate.context,tx);
            rewriteJournalIds(tx.transactionDirectory(),null,0);
            try{LibraryRestoreTransaction.resume(duplicate.context,tx.transactionDirectory());fail("duplicate local IDs must be rejected");}
            catch(Exception expected){assertNotNull(expected);}
            assertEquals(2,NoteStore.list(duplicate.context).size());
            for(String original:duplicate.originalIds)assertEquals(original,NoteStore.load(duplicate.context,original).getString("id"));
        }finally{duplicate.cleanup();}
    }

    /** Temporary device probe: only fixed app-private prefixes and synthetic destination paths are printed. */
    @Test public void androidPrivateRootAliasIsValidatedInApplicationNamespace() throws Exception {
        File alias=new File("/data/user/0");
        System.out.println("ANDROID_ROOT_PROBE app_uid="+Os.getuid());
        StructStat aliasStat=Os.lstat(alias.getAbsolutePath());
        File canonicalAlias=alias.getCanonicalFile();
        String expectedAppData;
        if(OsConstants.S_ISLNK(aliasStat.st_mode)){
            String target=Os.readlink(alias.getAbsolutePath());
            assertEquals("/data/data",target);expectedAppData=target;
            System.out.println("ANDROID_ROOT_PROBE alias_readlink="+target);
        }else{
            assertTrue(OsConstants.S_ISDIR(aliasStat.st_mode));
            assertEquals(alias.getAbsolutePath(),canonicalAlias.getAbsolutePath());
            expectedAppData=alias.getAbsolutePath();
            System.out.println("ANDROID_ROOT_PROBE alias_readlink=ordinary_directory");
        }
        for(String path:new String[]{"/data","/data/user","/data/user/0","/data/data"}){
            try{StructStat s=Os.lstat(path);System.out.println("ANDROID_ROOT_PROBE path="+path+" mode=0x"+Integer.toHexString(s.st_mode)+
                    " uid="+s.st_uid+" gid="+s.st_gid+" dev="+s.st_dev+" ino="+s.st_ino+
                    " isDir="+OsConstants.S_ISDIR(s.st_mode)+" isLink="+OsConstants.S_ISLNK(s.st_mode)+" canonical="+new File(path).getCanonicalPath());}
            catch(android.system.ErrnoException error){System.out.println("ANDROID_ROOT_PROBE path="+path+" errno="+error.errno);}
        }
        File appFiles=InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir();
        logMode("app-files",appFiles);
        logMode("app-cache",InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        logMode("app-data-root",appFiles.getParentFile());
        File canonical=LibraryBackupArchive.AndroidSafeFiles.canonicalPrivateAndroidDirectory(appFiles.getParentFile());
        assertEquals(expectedAppData+"/"+InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName(),canonical.getAbsolutePath());
        System.out.println("ANDROID_ROOT_PROBE app_data_root_helper=ok canonical="+canonical.getAbsolutePath());
    }

    @Test public void diagnoseSyntheticHardlinkAndSnapshotParentDevices() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File sandbox=new File(target.getCacheDir(),"library-backup-link-probe-"+UUID.randomUUID());
        assertTrue(sandbox.mkdir());
        File source=new File(sandbox,"link-source"),destination=new File(sandbox,"link-destination");
        NoteStore.Entry note=null;
        Context context=null;
        try{
            FileDescriptor fd=Os.open(source.getAbsolutePath(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600);
            try(FileOutputStream output=new FileOutputStream(fd)){output.write(0);output.getFD().sync();}
            StructStat sourceParent=Os.lstat(source.getParentFile().getAbsolutePath());
            StructStat destinationParent=Os.lstat(destination.getParentFile().getAbsolutePath());
            logMode("link-source-parent",source.getParentFile());
            try{Os.link(source.getAbsolutePath(),destination.getAbsolutePath());System.out.println("ANDROID_LINK_PROBE synthetic_link=ok");}
            catch(android.system.ErrnoException error){System.out.println("ANDROID_LINK_PROBE synthetic_link_errno="+error.errno);}
            System.out.println("ANDROID_LINK_PROBE synthetic_source_parent_dev="+sourceParent.st_dev+" synthetic_destination_parent_dev="+destinationParent.st_dev);
            if(destination.exists())Os.remove(destination.getAbsolutePath());

            context=isolatedContext(target,sandbox);
            logMode("snapshot-destination-parent",sandbox);
            note=NoteStore.create(context,"合成设备号探针");
            logMode("sandbox-notes",new File(context.getFilesDir(),NoteStore.notesDirectoryName()));
            try(LibraryBackupSnapshot snapshot=LibraryBackupSnapshot.create(context,Collections.singleton(note.id),null,null)){
                LibraryBackupManifest.Note descriptor=snapshot.manifest.notes.get(0);
                File staged=snapshot.resourceFiles.get(descriptor.noteResourceId);
                File archiveDestination=new File(sandbox,"snapshot-destination.zip");
                StructStat stagedParent=Os.lstat(staged.getParentFile().getAbsolutePath());
                StructStat archiveParent=Os.lstat(archiveDestination.getParentFile().getAbsolutePath());
                logMode("snapshot-stage-parent",staged.getParentFile());
                System.out.println("ANDROID_LINK_PROBE snapshot_source_parent_dev="+stagedParent.st_dev+
                        " snapshot_destination_parent_dev="+archiveParent.st_dev);
            }
        }finally{
            if(destination.exists())try{Os.remove(destination.getAbsolutePath());}catch(Exception ignored){}
            if(note!=null&&context!=null)try{NoteStore.delete(context,note.id);}catch(Exception ignored){}
            deleteTree(sandbox);
        }
    }

    private static void rewriteJournalIds(File transactionDirectory,String replacement,Integer duplicateIndex)throws Exception{
        File file=new File(transactionDirectory,"journal.json");JSONObject journal=new JSONObject(new String(read(file),java.nio.charset.StandardCharsets.UTF_8));
        org.json.JSONArray groups=journal.getJSONArray("groups");
        if(replacement!=null)groups.getJSONObject(0).put("local_note_id",replacement);
        else groups.getJSONObject(1).put("local_note_id",groups.getJSONObject(duplicateIndex).getString("local_note_id"));
        try(FileOutputStream out=new FileOutputStream(file,false)){out.write(journal.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
    }

    private static RestoreFixture restoreFixture(String label)throws Exception{
        File sandbox=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"library-backup-"+label+"-"+UUID.randomUUID());
        assertTrue(sandbox.mkdir());Context context=isolatedContext(InstrumentationRegistry.getInstrumentation().getTargetContext(),sandbox);
        File parent=new File(context.getFilesDir(),"library-backup");assertTrue(parent.mkdirs());
        NoteStore.Entry a=NoteStore.create(context,"恢复组甲"),b=NoteStore.create(context,"恢复组乙");
        File archive=new File(sandbox,"input.zip");try(LibraryBackupSnapshot snapshot=LibraryBackupSnapshot.create(context,new HashSet<>(java.util.Arrays.asList(a.id,b.id)),null,null)){snapshot.write(archive,null,null);}
        return new RestoreFixture(sandbox,context,parent,archive,java.util.Arrays.asList(a.id,b.id));
    }

    private static final class RestoreFixture{
        final File sandbox,stageParent,archive;final Context context;final java.util.List<String> originalIds;
        RestoreFixture(File s,Context c,File p,File a,java.util.List<String> i){sandbox=s;context=c;stageParent=p;archive=a;originalIds=i;}
        void cleanup(){try{for(NoteStore.Entry e:NoteStore.list(context))NoteStore.delete(context,e.id);}catch(Exception ignored){}deleteTree(sandbox);}
    }

    private static final class MapHolder { final java.util.Map<String,String> ids; MapHolder(java.util.Map<String,String> ids){this.ids=ids;} }
    private interface CheckedAction { void run() throws Exception; }
    private static boolean containsNoteId(java.util.List<NoteStore.Entry> entries,String id){for(NoteStore.Entry entry:entries)if(id.equals(entry.id))return true;return false;}
    private static void assertErrorCode(String code,CheckedAction action)throws Exception{
        try{action.run();fail("expected "+code);}catch(Exception error){assertEquals(code,error.getMessage());}
    }

    private static Context isolatedContext(Context base, File sandbox) throws Exception {
        File files = new File(sandbox, "files");
        File cache = new File(sandbox, "cache");
        assertTrue(files.mkdirs());
        assertTrue(cache.mkdirs());
        logMode("sandbox-files",files);
        logMode("sandbox-cache",cache);
        return new ContextWrapper(base) {
            @Override public File getFilesDir() { return files; }
            @Override public File getCacheDir() { return cache; }
            @Override public Context getApplicationContext() { return this; }
            @Override public File getDir(String name, int mode) {
                File directory = new File(files, "app_" + name);
                if (!directory.exists()) directory.mkdirs();
                return directory;
            }
        };
    }

    private static void assertTransactionUnder(Context context,LibraryRestoreTransaction transaction)throws Exception{
        File expected=new File(context.getFilesDir(),"library-backup/transactions").getCanonicalFile();
        File actual=transaction.transactionDirectory().getCanonicalFile();
        assertEquals(expected,actual.getParentFile());
        assertTrue(actual.getPath().startsWith(expected.getPath()+File.separator));
        logMode("transaction-directory",transaction.transactionDirectory());
        logMode("transaction-journal",new File(transaction.transactionDirectory(),"journal.json"));
    }

    private static void logMode(String label,File path)throws Exception{
        StructStat s=Os.lstat(path.getAbsolutePath());
        System.out.println("ANDROID_MODE_PROBE label="+label+" mode=0x"+Integer.toHexString(s.st_mode)+
                " uid="+s.st_uid+" gid="+s.st_gid+" dev="+s.st_dev+" ino="+s.st_ino+
                " dir="+OsConstants.S_ISDIR(s.st_mode)+" reg="+OsConstants.S_ISREG(s.st_mode)+" link="+OsConstants.S_ISLNK(s.st_mode));
    }

    private static byte[] read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder();
        for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 255));
        return result.toString();
    }

    private static void deleteTree(File directory) {
        if (directory == null || !directory.exists()) return;
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) {
            try {
                android.system.StructStat stat = Os.lstat(child.getAbsolutePath());
                if (android.system.OsConstants.S_ISDIR(stat.st_mode)) deleteTree(child);
                else Os.remove(child.getAbsolutePath());
            } catch (Exception ignored) { }
        }
        try { Os.remove(directory.getAbsolutePath()); } catch (Exception ignored) { }
    }
}
