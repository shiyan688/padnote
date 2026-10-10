package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;
import static org.hamcrest.Matchers.anything;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.allOf;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.view.View;
import android.view.ViewParent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.padnote.android.streaming.StreamingGroupStore;

import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real bookshelf rename/delete paths preserve full group revisions and history. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 27)
public final class GroupRenameDeleteInstrumentedTest {
    @Test public void bookshelfCoverAssignAndRemovePublishWholeCasRevisionsAndKeepHistory() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Fixture fixture=createCompleteGroup(context,"R56 cover revisions "+UUID.randomUUID());
        ActivityScenario<MainActivity> scenario=null;
        try {
            NoteGroupFacade facade=new NoteGroupFacade(context);
            StreamingGroupStore.Snapshot original=facade.openGroup(fixture.id);
            JSONObject precisionBody=NotePrecisionJsonParser.parseObject(new String(
                    original.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX),StandardCharsets.UTF_8));
            precisionBody.put("viewportCenterX",-0.0d)
                    .put("viewportCenterY",9007199254740993L)
                    .put("pageGap",768.00001d);
            original=facade.saveEditorRevision(fixture.id,original.revision,original.digest,
                    fixture.title,NoteJsonCodec.stringify(precisionBody));
            assertCoverPrecisionBody(original);
            byte[] originalCover=readMember(original,"cover.bin");
            File legacyCover=CoverStore.coverFile(new File(context.getFilesDir(),NoteStore.notesDirectoryName()),fixture.id);
            byte[] legacyCoverBefore=Files.readAllBytes(legacyCover.toPath());
            scenario=ActivityScenario.launch(MainActivity.class);

            clickMoreForNote(fixture,scenario,new ShelfProbe());
            int priorShelfAttempt=currentShelfListAttempt(scenario);
            assertTrue("the first fully rendered catalog has a diagnostic attempt",priorShelfAttempt>=0);
            onView(withText("更换封面")).perform(click());
            waitDisplayed(withText("更换封面"));
            onView(withContentDescription("封面：浅蓝方格")).perform(scrollTo(),click());
            awaitCoverMutation(scenario,"PUBLISHED");
            StreamingGroupStore.Snapshot assigned=awaitDifferentRevision(facade,fixture.id,original.revision);
            assertCoverPrecisionBody(assigned);
            assertTrue("cover selection publishes a changed cover member",
                    !java.util.Arrays.equals(originalCover,readMember(assigned,"cover.bin")));
            assertCoverAssignedMembersEqual(original,assigned,fixture.id,originalCover);
            assertArrayEquals("managed cover selection never writes the legacy sidecar",legacyCoverBefore,
                    Files.readAllBytes(legacyCover.toPath()));
            StreamingGroupStore.Snapshot retainedOriginal=facade.openGroupRevision(fixture.id,
                    original.revision,original.digest);
            assertSameMembersAndBytes(original,retainedOriginal);

            clickMoreForNote(fixture,scenario,new ShelfProbe(),priorShelfAttempt+1);
            onView(withText("移除封面")).perform(click());
            awaitCoverMutation(scenario,"PUBLISHED");
            StreamingGroupStore.Snapshot removed=awaitDifferentRevision(facade,fixture.id,assigned.revision);
            assertCoverPrecisionBody(removed);
            assertFalse("removal publishes a revision without the cover member",
                    removed.memberSizes().containsKey("cover.bin"));
            assertCoverRemovedMembersEqual(original,assigned,removed,fixture.id);
            assertArrayEquals("managed cover removal leaves legacy sidecar bytes untouched",legacyCoverBefore,
                    Files.readAllBytes(legacyCover.toPath()));
            assertSameMembersAndBytes(original,facade.openGroupRevision(fixture.id,
                    original.revision,original.digest));
            assertSameMembersAndBytes(assigned,facade.openGroupRevision(fixture.id,
                    assigned.revision,assigned.digest));
            expectCasConflict(()->facade.removeCoverRevision(fixture.id,
                    assigned.revision,assigned.digest));
            assertEquals("stale remove leaves the winning marker untouched",removed.revision,
                    facade.openGroup(fixture.id).revision);
        } finally {
            if(scenario!=null)scenario.close();
            fixture.cleanup();
        }
    }

    @Test public void legacyCoverWritersCannotCrossTheAdoptionMutationFence() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File fixtureRoot=new File(target.getFilesDir(),"r59-cover-fence-"+UUID.randomUUID());
        assertTrue("create a fresh private fixture root",fixtureRoot.mkdir());
        String suffix=UUID.randomUUID().toString();
        Thread assignment=null,removal=null;
        Bitmap assignedCover=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888);
        assignedCover.eraseColor(Color.MAGENTA);
        Bitmap existingCover=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888);
        existingCover.eraseColor(Color.GREEN);
        long started=android.os.SystemClock.elapsedRealtime();
        try {
            File fixtureFiles=new File(fixtureRoot,"app-files");
            assertTrue("create private app files root",fixtureFiles.mkdir());
            Context context=new CoverFenceFixtureContext(target,fixtureFiles);
            NoteStore.Entry assignmentOwner=NoteStore.create(context,"R59 cover assign fence "+suffix);
            NoteStore.Entry removalOwner=NoteStore.create(context,"R59 cover remove fence "+suffix);
            CoverStore.assign(context,removalOwner.id,existingCover);
            existingCover.recycle();existingCover=null;
            File assignedSidecar=CoverStore.coverFile(new File(context.getFilesDir(),NoteStore.notesDirectoryName()),assignmentOwner.id);
            File removalSidecar=CoverStore.coverFile(new File(context.getFilesDir(),NoteStore.notesDirectoryName()),removalOwner.id);
            byte[] removalBytes=Files.readAllBytes(removalSidecar.toPath());
            CountDownLatch entered=new CountDownLatch(2);
            CountDownLatch finished=new CountDownLatch(2);
            AtomicReference<Throwable> assignmentFailure=new AtomicReference<>();
            AtomicReference<Throwable> removalFailure=new AtomicReference<>();
            assignment=new Thread(()->{
                entered.countDown();
                try{CoverStore.assign(context,assignmentOwner.id,assignedCover);}
                catch(Throwable failure){assignmentFailure.set(failure);}
                finally{finished.countDown();}
            },"r59-cover-assign-fence");
            removal=new Thread(()->{
                entered.countDown();
                try{CoverStore.removeChecked(context,removalOwner.id);}
                catch(Throwable failure){removalFailure.set(failure);}
                finally{finished.countDown();}
            },"r59-cover-remove-fence");
            try {
                try(LegacyGroupMutationLock.Lease held=LegacyGroupMutationLock.acquire()) {
                    assignment.start();
                    removal.start();
                    assertTrue("both legacy actions entered while adoption fence is held",
                            entered.await(5,TimeUnit.SECONDS));
                    assertTrue("assign/remove wait on the shared mutation fence before validating legacy authority",
                            awaitThreadBlocked(assignment,5,TimeUnit.SECONDS)&&
                                    awaitThreadBlocked(removal,5,TimeUnit.SECONDS));
                    assertEquals(2,finished.getCount());
                    NoteGroupFacade facade=new NoteGroupFacade(context);
                    emitCoverFenceTiming("adoption_one_start",started);
                    StreamingGroupStore.Snapshot adoptedAssignment=facade.adoptLegacyNote(
                            assignmentOwner.id,(oldNote,staged)->true);
                    emitCoverFenceTiming("adoption_one_complete",started);
                    StreamingGroupStore.Snapshot adoptedRemoval=facade.adoptLegacyNote(
                            removalOwner.id,(oldNote,staged)->true);
                    emitCoverFenceTiming("adoption_two_complete",started);
                    assertFalse(adoptedAssignment.memberSizes().containsKey("cover.bin"));
                    assertTrue(adoptedRemoval.memberSizes().containsKey("cover.bin"));
                }
                assertTrue("legacy writers finish after the adoption fence is released",
                        finished.await(10,TimeUnit.SECONDS));
                assignment.join(TimeUnit.SECONDS.toMillis(5));
                removal.join(TimeUnit.SECONDS.toMillis(5));
                assertGroupFenceRejection(assignmentFailure.get());
                assertGroupFenceRejection(removalFailure.get());
                assertFalse("the waiting assign did not create a legacy sidecar",assignedSidecar.exists());
                assertArrayEquals("the waiting remove did not erase the cover captured by adoption",
                        removalBytes,Files.readAllBytes(removalSidecar.toPath()));
            } finally {
                if(assignment.isAlive())assignment.interrupt();
                if(removal.isAlive())removal.interrupt();
                if(assignment.isAlive())assignment.join(TimeUnit.SECONDS.toMillis(5));
                if(removal.isAlive())removal.join(TimeUnit.SECONDS.toMillis(5));
            }
        } finally {
            if(assignedCover!=null)assignedCover.recycle();
            if(existingCover!=null)existingCover.recycle();
            if((assignment==null||!assignment.isAlive())&&(removal==null||!removal.isAlive())){
                try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(fixtureRoot.toPath())){
                    for(java.nio.file.Path path:paths.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new))
                        Files.deleteIfExists(path);
                }
                assertFalse("cleanup is confined to this fresh R59 fixture",fixtureRoot.exists());
            }
        }
    }

    private static void emitCoverFenceTiming(String event,long started) {
        long elapsed=Math.max(0,android.os.SystemClock.elapsedRealtime()-started);
        String output="R59_COVER_FENCE_TIMING {\"event\":\""+event+"\",\"elapsed_ms\":"+elapsed+"}";
        System.out.println(output);
        android.os.Bundle status=new android.os.Bundle();status.putString("stream",output);
        InstrumentationRegistry.getInstrumentation().sendStatus(2,status);
    }

    private static final class CoverFenceFixtureContext extends ContextWrapper {
        private final File files;
        CoverFenceFixtureContext(Context base,File files){super(base);this.files=files;}
        @Override public File getFilesDir(){return files;}
        @Override public File getDir(String name,int mode){
            if(name==null||!name.matches("[A-Za-z0-9._-]{1,64}"))throw new IllegalArgumentException("fixture_dir_name");
            File dir=new File(files,"app_"+name);
            if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("fixture_dir_create_failed");
            if(!dir.isDirectory()||java.nio.file.Files.isSymbolicLink(dir.toPath()))throw new IllegalStateException("fixture_dir_unsafe");
            return dir;
        }
        @Override public Context getApplicationContext(){return this;}
    }

    @Test public void legacyCoverAssignReplaceAndCheckedRemoveOperateOnValidPngs() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        NoteStore.Entry entry=NoteStore.create(context,"R56 cover atomic writer "+UUID.randomUUID());
        File sidecar=CoverStore.coverFile(new File(context.getFilesDir(),NoteStore.notesDirectoryName()),entry.id);
        Bitmap first=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888);
        first.eraseColor(Color.BLUE);
        Bitmap second=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888);
        second.eraseColor(Color.YELLOW);
        try {
            assertFalse(sidecar.exists());
            CoverStore.assign(context,entry.id,first);
            byte[] firstBytes=Files.readAllBytes(sidecar.toPath());
            CoverStore.validatePng(firstBytes);
            assertTrue(firstBytes.length>0);

            CoverStore.assign(context,entry.id,second);
            byte[] replacement=Files.readAllBytes(sidecar.toPath());
            CoverStore.validatePng(replacement);
            assertFalse("replacement publishes new complete PNG bytes",
                    java.util.Arrays.equals(firstBytes,replacement));

            Bitmap recycled=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);
            recycled.recycle();
            try {
                CoverStore.assign(context,entry.id,recycled);
                fail("an unavailable bitmap cannot replace the previous cover");
            } catch(IllegalArgumentException expected) { }
            assertArrayEquals("a rejected assignment preserves the previous complete sidecar",
                    replacement,Files.readAllBytes(sidecar.toPath()));

            CoverStore.removeChecked(context,entry.id);
            assertFalse("checked removal deletes the assigned sidecar",sidecar.exists());
            CoverStore.removeChecked(context,entry.id);
            assertFalse("repeated removal of an absent sidecar is idempotent",sidecar.exists());
        } finally {
            first.recycle();
            second.recycle();
        }
    }

    @Test public void lateCoverPickerChoiceCannotOverwriteAConcurrentWholeGroupWinner() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Fixture fixture=createCompleteGroup(context,"R56 stale cover "+UUID.randomUUID());
        ActivityScenario<MainActivity> scenario=null;
        try {
            NoteGroupFacade facade=new NoteGroupFacade(context);
            StreamingGroupStore.Snapshot base=facade.openGroup(fixture.id);
            byte[] coverBefore=readMember(base,"cover.bin");
            scenario=ActivityScenario.launch(MainActivity.class);
            clickMoreForNote(fixture,scenario,new ShelfProbe());
            onView(withText("更换封面")).perform(click());
            waitDisplayed(withText("更换封面"));

            JSONObject winningBody=body(base);
            winningBody.put("title","R56 concurrent group winner");
            StreamingGroupStore.Snapshot winner=facade.saveEditorRevision(fixture.id,
                    base.revision,base.digest,"R56 concurrent group winner",
                    NoteJsonCodec.stringify(winningBody));

            // This is the actual picker choice callback, carrying the revision captured when
            // the chooser opened. It must fail rather than re-read and silently replace winner.
            onView(withContentDescription("封面：浅蓝方格")).perform(scrollTo(),click());
            awaitCoverMutation(scenario,"FAILED");
            java.util.concurrent.atomic.AtomicReference<String> failureEvidence=
                    new java.util.concurrent.atomic.AtomicReference<>("UNAVAILABLE");
            scenario.onActivity(activity->failureEvidence.set(activity.coverMutationFailureEvidenceForTest()));
            assertTrue("the delayed picker result is rejected by the captured whole-group CAS",
                    failureEvidence.get().contains("BASE_CAS_CONFLICT"));
            StreamingGroupStore.Snapshot stillWinner=facade.openGroup(fixture.id);
            assertEquals(winner.revision,stillWinner.revision);
            assertEquals(winner.digest,stillWinner.digest);
            assertArrayEquals("late cover choice cannot replace the winner's cover",
                    coverBefore,readMember(stillWinner,"cover.bin"));
            assertSameMembersAndBytes(winner,facade.openGroupRevision(fixture.id,
                    winner.revision,winner.digest));
            expectCasConflict(()->facade.replaceCoverRevision(fixture.id,base.revision,
                    base.digest,CoverStore.encodeForGroup(CoverStore.builtinPresets().get(0).bitmap)));
            assertEquals(winner.revision,facade.openGroup(fixture.id).revision);
        } finally {
            if(scenario!=null)scenario.close();
            fixture.cleanup();
        }
    }

    @Test public void legacyCoverPickerCannotFallbackAfterTargetBecomesManaged() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String title="R56 legacy-to-group cover race "+UUID.randomUUID();
        NoteStore.Entry entry=NoteStore.create(context,title);
        File unusedVideo=new File(context.getCacheDir(),"r56-unused-"+UUID.randomUUID());
        Fixture fixture=new Fixture(context,entry.id,title,null,unusedVideo);
        ActivityScenario<MainActivity> scenario=null;
        try {
            byte[] bodyBefore=Files.readAllBytes(NoteStore.noteFileForBackup(context,entry.id).toPath());
            scenario=ActivityScenario.launch(MainActivity.class);
            clickMoreForNote(fixture,scenario,new ShelfProbe());
            onView(withText("更换封面")).perform(click());
            waitDisplayed(withText("更换封面"));

            // Picker captured the legacy route above. Adopting the target before the user's
            // choice returns must make the legacy writer fail closed instead of downgrading.
            NoteGroupFacade facade=new NoteGroupFacade(context);
            StreamingGroupStore.Snapshot adopted=facade.adoptLegacyNote(entry.id,(old,staged)->true);
            onView(withContentDescription("封面：浅蓝方格")).perform(scrollTo(),click());
            awaitCoverMutation(scenario,"FAILED");
            java.util.concurrent.atomic.AtomicReference<String> failureEvidence=
                    new java.util.concurrent.atomic.AtomicReference<>("UNAVAILABLE");
            scenario.onActivity(activity->failureEvidence.set(activity.coverMutationFailureEvidenceForTest()));
            assertTrue("late legacy callback is rejected by the grouped-write guard",
                    failureEvidence.get().contains("GROUP_NOTE_REQUIRES_FULL_REVISION_SAVE"));
            StreamingGroupStore.Snapshot current=facade.openGroup(entry.id);
            assertEquals(adopted.revision,current.revision);
            assertEquals(adopted.digest,current.digest);
            assertFalse(current.memberSizes().containsKey("cover.bin"));
            assertArrayEquals("late picker callback did not change the original legacy body",bodyBefore,
                    Files.readAllBytes(NoteStore.noteFileForBackup(context,entry.id).toPath()));
        } finally {
            if(scenario!=null)scenario.close();
            fixture.cleanup();
        }
    }

    @Test public void legacyMutationTokenCannotDowngradeWhenTargetHasNoGroup() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        NoteStore.Entry entry=NoteStore.create(context,"R39b legacy CAS boundary "+UUID.randomUUID());
        File note=NoteStore.noteFileForBackup(context,entry.id);
        File index=new File(context.getFilesDir(),"padnote-index.json");
        File tombstone=new File(note.getParentFile(),note.getName()+".deleted");
        try {
            byte[] bodyBefore=Files.readAllBytes(note.toPath());
            byte[] indexBefore=Files.readAllBytes(index.toPath());
            expectCasConflict(()->NoteStore.rename(context,entry.id,"must reject revision-only token",
                    "captured-revision",null));
            expectCasConflict(()->NoteStore.rename(context,entry.id,"must reject digest-only token",
                    null,"0000000000000000000000000000000000000000000000000000000000000000"));
            expectCasConflict(()->NoteStore.delete(context,entry.id,"captured-revision",null));
            expectCasConflict(()->NoteStore.delete(context,entry.id,null,
                    "0000000000000000000000000000000000000000000000000000000000000000"));
            assertArrayEquals("rejected token-bearing rename/delete leave legacy note bytes exact",
                    bodyBefore,Files.readAllBytes(note.toPath()));
            assertArrayEquals("rejected token-bearing rename/delete leave shelf index bytes exact",
                    indexBefore,Files.readAllBytes(index.toPath()));
            assertTrue(NoteStore.list(context).stream().anyMatch(item->entry.id.equals(item.id)));

            String renamedTitle="R39b ordinary legacy rename "+UUID.randomUUID();
            NoteStore.rename(context,entry.id,renamedTitle);
            JSONObject renamed=NoteStore.load(context,entry.id);
            assertEquals("no-token legacy rename still works for an unmanaged note",renamedTitle,
                    renamed.getString("title"));
            byte[] renamedBytes=Files.readAllBytes(note.toPath());
            byte[] renamedIndex=Files.readAllBytes(index.toPath());
            expectCasConflict(()->NoteStore.delete(context,entry.id,"captured-revision",
                    "0000000000000000000000000000000000000000000000000000000000000000"));
            assertArrayEquals("rejected token-bearing delete leaves renamed body exact",
                    renamedBytes,Files.readAllBytes(note.toPath()));
            assertArrayEquals("rejected token-bearing delete leaves renamed index exact",
                    renamedIndex,Files.readAllBytes(index.toPath()));

            NoteStore.delete(context,entry.id);
            assertTrue("ordinary no-token delete still removes an unmanaged note from the shelf",
                    NoteStore.list(context).stream().noneMatch(item->entry.id.equals(item.id)));
            assertFalse("ordinary legacy delete removes the active note file",note.exists());
            assertFalse("legacy delete removes its staging tombstone after index publication",tombstone.exists());
        } finally {
            if(NoteStore.list(context).stream().anyMatch(item->entry.id.equals(item.id)))
                NoteStore.delete(context,entry.id);
            if(tombstone.exists())assertTrue("test tombstone cleanup",tombstone.delete());
        }
    }

    @Test public void repeatedCompleteGroupAdoptionAndLegacyReadsKeepDescriptorCountBounded() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.util.ArrayList<Fixture> fixtures=new java.util.ArrayList<>();
        try {
            Fixture warmup=createCompleteGroup(context,"R30 FD warmup "+UUID.randomUUID());fixtures.add(warmup);
            int baseline=openDescriptorCount();
            for(int fixtureIndex=0;fixtureIndex<2;fixtureIndex++){
                Fixture fixture=createCompleteGroup(context,"R30 FD retained "+UUID.randomUUID());fixtures.add(fixture);
                int afterAdoption=openDescriptorCount();
                System.out.println("R30_FD_ADOPTION {\"iteration\":"+fixtureIndex+",\"count\":"+afterAdoption+",\"baseline\":"+baseline+"}");
                assertTrue("complete adoption must release Os.open descriptors; baseline="+baseline+" current="+afterAdoption,
                        afterAdoption<=baseline+8);
                for(int read=0;read<12;read++){
                    StreamingGroupStore.Snapshot captured=new NoteGroupFacade(context).openGroup(warmup.id);
                    assertNotNull("an adopted note is read through its committed group authority",captured);
                    assertEquals(warmup.id,captured.localId);
                    int afterRead=openDescriptorCount();
                    assertTrue("repeated complete-group capture must release Os.open descriptors; baseline="+baseline+" current="+afterRead,
                            afterRead<=baseline+8);
                }
            }
        } finally {
            for(Fixture fixture:fixtures)fixture.cleanup();
        }
    }

    @Test public void bookshelfRenamePublishesWholeRevisionAndKeepsPreviousRevision() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ShelfProbe probe=new ShelfProbe();
        probe.emit("fixture_setup_start",null);
        final Fixture fixture;
        try {
            fixture=createCompleteGroup(context,"R20 rename "+UUID.randomUUID(),probe);
            probe.emit("fixture_setup_complete",null);
        } catch(Exception|AssertionError setupFailure) {
            probe.emit("fixture_setup_failed",null);
            throw setupFailure;
        }
        ActivityScenario<MainActivity> scenario=null;
        try {
            assertNoTokenGroupMutationsRemainBlocked(context,fixture);
            probe.emit("group_guard_checks_complete",null);
            probe.emit("activity_launch_start",null);
            scenario=ActivityScenario.launch(MainActivity.class);
            probe.emit("activity_launched",scenario);
            clickMoreForNote(fixture,scenario,probe);
            probe.emit("rename_menu_item_click_start",scenario);
            onView(withText("重命名")).perform(click());
            probe.emit("rename_menu_item_clicked",scenario);
            String renamedTitle="R20 renamed "+UUID.randomUUID();
            waitDisplayed(withHint("输入新的笔记名称"));
            onView(withHint("输入新的笔记名称")).perform(replaceText(renamedTitle));
            waitDisplayed(withText("重命名笔记"));
            probe.emit("rename_dialog_ready",scenario);
            boolean coversPending=coverPassPending(scenario);
            long renameCommitStarted=android.os.SystemClock.elapsedRealtime();
            java.util.concurrent.atomic.AtomicReference<String> renameButtonEvidence=
                    new java.util.concurrent.atomic.AtomicReference<>("NOT_CAPTURED");
            probe.emit("rename_confirm_click_start",scenario);
            onView(positiveDialogButton("保存")).perform(
                    clickDialogButtonWithGeometry(renameButtonEvidence,"保存"));
            probe.emit("rename_confirm_click_done",scenario);
            String renameState=renameDiagnostic(scenario);
            if(!renameState.startsWith("RENAME_")){
                emitRenameDiagnostic(scenario);
                emitDialogButtonDiagnostic("rename",renameButtonEvidence.get());
            }
            assertTrue("the visible rename dialog's positive-button callback must run; state="+renameState
                    +"; button="+renameButtonEvidence.get(),renameState.startsWith("RENAME_"));

            NoteGroupFacade facade=new NoteGroupFacade(context);
            StreamingGroupStore.Snapshot renamed;
            try {
                renamed=awaitDifferentRevision(facade,fixture.id,fixture.base.revision);
                probe.emit("rename_revision_observed",scenario);
            } catch(AssertionError timeout) {
                probe.emit("rename_revision_wait_failed",scenario);
                emitRenameDiagnostic(scenario);
                throw timeout;
            }
            long renameCommitElapsed=android.os.SystemClock.elapsedRealtime()-renameCommitStarted;
            if(coversPending)assertTrue("optional cover reads must not hold the storage queue during rename; elapsed_ms="+renameCommitElapsed,
                    renameCommitElapsed<TimeUnit.SECONDS.toMillis(10));
            JSONObject renamedBody=body(renamed);
            assertEquals(renamedTitle,renamedBody.getString("title"));
            assertRenameMembersEqual(fixture.base,renamed,fixture.id);
            assertSameMembersAndBytes(fixture.base,facade.openGroupRevision(fixture.id,
                    fixture.base.revision,fixture.base.digest));
            try {
                NoteStore.rename(context,fixture.id,"stale rename",fixture.base.revision,fixture.base.digest);
                fail("a rename opened on an old revision must not overwrite a newer group revision");
            } catch(java.io.IOException expected) { assertEquals("BASE_CAS_CONFLICT",expected.getMessage()); }

            waitDisplayed(withText(renamedTitle));
            clickNote(renamedTitle);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<deadline){
                java.util.concurrent.atomic.AtomicReference<String> revision=new java.util.concurrent.atomic.AtomicReference<>();
                scenario.onActivity(activity->revision.set(activity.groupRevisionForTest()));
                if(renamed.revision.equals(revision.get()))break;
                Thread.sleep(100);
            }
            scenario.onActivity(activity->assertEquals("reopened note uses renamed group revision",
                    renamed.revision,activity.groupRevisionForTest()));
            probe.emit("renamed_note_reopened",scenario);
        } finally {
            probe.emit("rename_test_final",scenario);
            if(scenario!=null)scenario.close();
            fixture.cleanup();
        }
    }

    @Test public void bookshelfDeletePublishesTombstoneAndRetainsRestorableWholeHistory() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ShelfProbe probe=new ShelfProbe();
        probe.emit("fixture_setup_start",null);
        final Fixture fixture;
        try {
            fixture=createCompleteGroup(context,"R20 delete "+UUID.randomUUID(),probe);
            probe.emit("fixture_setup_complete",null);
        } catch(Exception|AssertionError setupFailure) {
            probe.emit("fixture_setup_failed",null);
            throw setupFailure;
        }
        ActivityScenario<MainActivity> scenario=null;
        try {
            assertNoTokenGroupMutationsRemainBlocked(context,fixture);
            probe.emit("group_guard_checks_complete",null);
            probe.emit("activity_launch_start",null);
            scenario=ActivityScenario.launch(MainActivity.class);
            probe.emit("activity_launched",scenario);
            clickMoreForNote(fixture,scenario,probe);
            probe.emit("delete_menu_item_click_start",scenario);
            onView(withText("删除")).perform(click());
            probe.emit("delete_menu_item_clicked",scenario);
            waitDisplayed(withText(containsString("目前应用内的历史恢复入口尚未开放")));
            probe.emit("delete_dialog_ready",scenario);
            java.util.concurrent.atomic.AtomicReference<String> deleteButtonEvidence=
                    new java.util.concurrent.atomic.AtomicReference<>("NOT_CAPTURED");
            probe.emit("delete_confirm_click_start",scenario);
            onView(positiveDialogButton("删除")).perform(
                    clickDialogButtonWithGeometry(deleteButtonEvidence,"删除"));
            probe.emit("delete_confirm_click_done",scenario);
            String deleteState=deleteDiagnostic(scenario);
            if(!"CONFIRM_ENTERED".equals(deleteState)&&!deleteState.startsWith("DELETE_"))
                emitDialogButtonDiagnostic("delete",deleteButtonEvidence.get());
            assertTrue("the visible delete confirmation's positive-button callback must run; state="+deleteState,
                    "CONFIRM_ENTERED".equals(deleteState)||deleteState.startsWith("DELETE_"));

            NoteGroupFacade facade=new NoteGroupFacade(context);
            StreamingGroupStore.Snapshot tombstone;
            try {
                tombstone=awaitRetired(facade,fixture.id);
                probe.emit("retired_revision_observed",scenario);
            } catch(AssertionError timeout) {
                probe.emit("retired_revision_wait_failed",scenario);
                emitDeleteDiagnostic(scenario);
                throw timeout;
            }
            waitAbsent(withContentDescription("打开笔记 "+fixture.title+"。长按可重命名或更换封面。"));
            probe.emit("retired_card_absent",scenario);
            JSONObject retiredBody=body(tombstone);
            assertEquals("retired",retiredBody.getString("_padnoteShelfState"));
            assertTrue(retiredBody.getLong("_padnoteShelfRetiredAt")>0);
            assertRetirementMembersEqual(fixture.base,tombstone,fixture.id);
            assertTrue("retired group is omitted from the ordinary bookshelf list",
                    NoteStore.list(context).stream().noneMatch(entry->fixture.id.equals(entry.id)));
            try { NoteStore.load(context,fixture.id); fail("ordinary note loads must not reopen a retired group"); }
            catch(java.io.IOException expected) { assertEquals("GROUP_NOTE_RETIRED",expected.getMessage()); }
            try { facade.openGroup(fixture.id); fail("ordinary group open must reject a retired note"); }
            catch(java.io.IOException expected) { assertEquals("GROUP_NOTE_RETIRED",expected.getMessage()); }

            StreamingGroupStore.Snapshot original=facade.openGroupRevision(fixture.id,
                    fixture.base.revision,fixture.base.digest);
            assertSameMembersAndBytes(fixture.base,original);
            StreamingGroupStore.Snapshot restored=facade.restoreGroupRevision(fixture.id,
                    fixture.base.revision,fixture.base.digest,tombstone.revision,tombstone.digest,
                    (current,incoming)->true);
            assertEquals("explicit history restoration publishes a new visible whole revision",
                    fixture.base.localId,restored.localId);
            assertNotEquals("history restoration must not repoint the marker to an old CAS token",
                    fixture.base.revision,restored.revision);
            assertNotEquals(tombstone.revision,restored.revision);
            assertFalse(body(restored).has("_padnoteShelfState"));
            assertTrue(NoteStore.list(context).stream().anyMatch(entry->fixture.id.equals(entry.id)));
            assertFullMembersEqual(fixture.base,restored);
            try { facade.restoreGroupRevision(fixture.id,fixture.base.revision,fixture.base.digest,
                    tombstone.revision,tombstone.digest,(current,incoming)->true);
                fail("a stale history-restore confirmation must not become current again");
            } catch(java.io.IOException expected) { assertEquals("BASE_CAS_CONFLICT",expected.getMessage()); }
            StreamingGroupStore.Snapshot coldRead=new NoteGroupFacade(context).openGroup(fixture.id);
            assertEquals(restored.revision,coldRead.revision);assertFullMembersEqual(fixture.base,coldRead);
            try {
                facade.saveEditorRevision(fixture.id,fixture.base.revision,fixture.base.digest,fixture.title,
                        new String(fixture.base.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX),java.nio.charset.StandardCharsets.UTF_8));
                fail("a stale editor token must not become valid after history restore");
            } catch(java.io.IOException expected) { assertEquals("BASE_CAS_CONFLICT",expected.getMessage()); }
            try {
                NoteStore.delete(context,fixture.id,fixture.base.revision,fixture.base.digest);
                fail("a stale delete confirmation must not retire a restored winner");
            } catch(java.io.IOException expected) { assertEquals("BASE_CAS_CONFLICT",expected.getMessage()); }
            assertEquals(restored.revision,facade.openGroup(fixture.id).revision);
            probe.emit("history_restored_and_cold_read",scenario);
        } finally {
            probe.emit("delete_test_final",scenario);
            if(scenario!=null)scenario.close();
            fixture.cleanup();
        }
    }

    private static JSONObject unavailableDiagnostic() {
        JSONObject value=new JSONObject();try{value.put("available",false);}catch(Exception ignored){}
        return value;
    }

    /** Bounded timings and allowlisted MainActivity/NoteStore counters; never logs note data. */
    private static final class ShelfProbe {
        private final long started=android.os.SystemClock.elapsedRealtime();
        private long catalogWaitStarted=-1;
        synchronized void emit(String event,ActivityScenario<MainActivity> scenario) {
            if("catalog_readiness_wait_start".equals(event))catalogWaitStarted=android.os.SystemClock.elapsedRealtime();
            long now=android.os.SystemClock.elapsedRealtime();
            JSONObject value=new JSONObject();
            try {
                value.put("event",event);
                value.put("elapsed_ms",Math.max(0,now-started));
                if(catalogWaitStarted>=0)value.put("catalog_wait_elapsed_ms",Math.max(0,now-catalogWaitStarted));
                value.put("diagnostic",readBookshelfDiagnostic(scenario));
            } catch(Exception invalid) { return; }
            String output="R55_SHELF_TIMING "+value;
            System.out.println(output);
            android.os.Bundle status=new android.os.Bundle();status.putString("stream",output);
            InstrumentationRegistry.getInstrumentation().sendStatus(2,status);
        }
    }

    private static JSONObject readBookshelfDiagnostic(ActivityScenario<MainActivity> scenario) {
        if(scenario==null)return unavailableDiagnostic();
        java.util.concurrent.atomic.AtomicReference<String> raw=new java.util.concurrent.atomic.AtomicReference<>("UNAVAILABLE");
        try{scenario.onActivity(activity->raw.set(activity.bookshelfLoadDiagnosticForTest()));}
        catch(RuntimeException unavailable){return unavailableDiagnostic();}
        try{return new JSONObject(raw.get());}catch(Exception unavailable){return unavailableDiagnostic();}
    }

    private static int currentShelfListAttempt(ActivityScenario<MainActivity> scenario) throws Exception {
        JSONObject noteList=readBookshelfDiagnostic(scenario).optJSONObject("note_list");
        if(noteList==null)return -1;
        return noteList.optInt("attempt",-1);
    }

    private static final class Fixture {
        final Context context; final String id,title; final StreamingGroupStore.Snapshot base;
        final File sourceVideo;
        Fixture(Context context,String id,String title,StreamingGroupStore.Snapshot base,File sourceVideo){
            this.context=context;this.id=id;this.title=title;this.base=base;this.sourceVideo=sourceVideo;
        }
        void cleanup()throws Exception {
            if(sourceVideo.exists())assertTrue("synthetic video cleanup",sourceVideo.delete());
        }
    }

    private static Fixture createCompleteGroup(Context context,String title)throws Exception {
        return createCompleteGroup(context,title,null);
    }
    private static Fixture createCompleteGroup(Context context,String title,ShelfProbe probe)throws Exception {
        NoteStore.Entry entry=NoteStore.create(context,title);
        JSONObject body=new JSONObject(readAsset("legacy-notes/schema8.json"));
        body.put("id",entry.id).put("title",title).put("updatedAt",entry.updatedAt)
                .put("pageCount",2).put("pdfPageCount",1);
        JSONArray strokes=body.getJSONArray("strokes");
        if(strokes.length()==0)throw new AssertionError("schema8 fixture must contain ink");
        strokes.getJSONObject(0).put("id","r20-stroke-"+UUID.randomUUID()).put("createdAt",entry.updatedAt);
        strokes.getJSONObject(0).getJSONArray("points").getJSONObject(0).put("timestamp",entry.updatedAt);
        JSONArray flows=body.getJSONArray("textFlows");
        if(flows.length()==0)throw new AssertionError("schema8 fixture must contain text");
        flows.getJSONObject(0).put("id","r20-flow-"+UUID.randomUUID());
        NoteStore.save(context,entry.id,title,NoteJsonCodec.stringify(body));
        writePdf(NoteStore.pdfFile(context,entry.id));
        Bitmap cover=Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888);cover.eraseColor(Color.CYAN);
        CoverStore.assign(context,entry.id,cover);cover.recycle();
        File sourceVideo=new File(context.getCacheDir(),"r20-video-"+UUID.randomUUID()+".mp4");
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("video/roundtrip.mp4");
            FileOutputStream out=new FileOutputStream(sourceVideo)){
            byte[] buffer=new byte[16384];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);
            out.flush();out.getFD().sync();
        }
        byte[] video=Files.readAllBytes(sourceVideo.toPath());String videoHash=sha256(video);
        new VideoAttachmentStore(context).attach(entry.id,entry.updatedAt,videoHash,videoHash,
                "task-"+UUID.randomUUID(),"remote-"+UUID.randomUUID(),"connection-"+UUID.randomUUID(),1,
                "HERMES","BRIDGE","bridge-"+UUID.randomUUID(),"instance-"+UUID.randomUUID(),
                videoHash,"artifact-"+UUID.randomUUID(),"R20 preserved video","video/mp4",video.length,videoHash,sourceVideo);
        new VaultStore(context).write(entry.id,"R20 linked Vault",2,entry.updatedAt,
                Collections.singletonList("R20 Vault content must survive rename and delete."));
        if(probe!=null)probe.emit("adoption_start",null);
        StreamingGroupStore.Snapshot base=new NoteGroupFacade(context).adoptLegacyNote(entry.id,(old,staged)->true);
        if(probe!=null)probe.emit("adoption_complete",null);
        return new Fixture(context,entry.id,title,base,sourceVideo);
    }

    private static void assertNoTokenGroupMutationsRemainBlocked(Context context,Fixture fixture)throws Exception {
        NoteGroupFacade facade=new NoteGroupFacade(context);
        StreamingGroupStore.Snapshot before=facade.openGroup(fixture.id);
        assertEquals(fixture.base.revision,before.revision);
        assertEquals(fixture.base.digest,before.digest);
        try {
            NoteStore.rename(context,fixture.id,"no-token rename must remain blocked");
            fail("a grouped rename without the UI-captured revision and digest must stay blocked");
        } catch(java.io.IOException expected) {
            assertEquals("GROUP_NOTE_REQUIRES_FULL_REVISION_SAVE",expected.getMessage());
        }
        try {
            NoteStore.delete(context,fixture.id);
            fail("a grouped delete without the UI-captured revision and digest must stay blocked");
        } catch(java.io.IOException expected) {
            assertEquals("GROUP_NOTE_REQUIRES_FULL_REVISION_SAVE",expected.getMessage());
        }
        StreamingGroupStore.Snapshot after=facade.openGroup(fixture.id);
        assertEquals("rejected no-token callers cannot publish a group revision",before.revision,after.revision);
        assertEquals(before.digest,after.digest);
    }

    private interface CheckedOperation { void run() throws Exception; }
    private static void expectCasConflict(CheckedOperation operation)throws Exception {
        try { operation.run(); fail("a captured group token must not downgrade into a legacy mutation"); }
        catch(java.io.IOException expected) { assertEquals("BASE_CAS_CONFLICT",expected.getMessage()); }
    }

    private static void clickNote(String title)throws Exception {
        String description="打开笔记 "+title+"。长按可重命名或更换封面。";
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);Throwable last=null;
        while(System.nanoTime()<deadline){
            try{onView(withContentDescription(description)).perform(scrollTo(),click());return;}
            catch(RuntimeException|AssertionError unavailable){last=unavailable;Thread.sleep(120);}
        }
        throw new AssertionError("Bookshelf did not expose the expected note",last);
    }
    /** Selects the actual overflow button through its two direct parents: actions row → note card. */
    private static void clickMoreForNote(Fixture fixture,ActivityScenario<MainActivity> scenario,ShelfProbe probe) throws Exception {
        clickMoreForNote(fixture,scenario,probe,0);
    }

    private static void clickMoreForNote(Fixture fixture,ActivityScenario<MainActivity> scenario,
            ShelfProbe probe,int minimumListAttempt) throws Exception {
        String cardDescription="打开笔记 "+fixture.title+"。长按可重命名或更换封面。";
        // Keep the original 10-second catalog-readiness bound, but share it across
        // hierarchy appearance, scrolling, and the displayed check. Large fonts can
        // place a rendered card below the viewport, so visibility must follow scroll.
        org.hamcrest.Matcher<View> card=withContentDescription(cardDescription);
        probe.emit("catalog_readiness_wait_start",scenario);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        try{
            waitForFreshCatalogAndPresent(card,scenario,minimumListAttempt,deadline);
            probe.emit("catalog_card_present",scenario);
            onView(card).perform(scrollTo());
            probe.emit("catalog_card_scroll_complete",scenario);
            waitDisplayed(card,deadline);
            probe.emit("catalog_card_displayed",scenario);
        }catch(AssertionError timeout){probe.emit("catalog_readiness_wait_failed",scenario);emitBookshelfLoadDiagnostic(scenario);throw timeout;}
        catch(RuntimeException unavailable){
            probe.emit("catalog_readiness_scroll_failed",scenario);
            emitBookshelfLoadDiagnostic(scenario);
            throw new AssertionError("The rendered target note could not be scrolled into view",unavailable);
        }
        java.util.concurrent.atomic.AtomicReference<String> loadState=new java.util.concurrent.atomic.AtomicReference<>("UNAVAILABLE");
        scenario.onActivity(activity->loadState.set(activity.bookshelfLoadDiagnosticForTest()));
        assertTrue("the target card must be usable as soon as the catalog is rendered; state="+loadState.get(),
                loadState.get().contains("\"catalog_rendered\":true"));
        scenario.onActivity(activity->assertTrue("fixed paper cover placeholder must have stable visible geometry",
                activity.bookshelfCoverSlotReadyForTest(fixture.id)));
        onView(allOf(withContentDescription("笔记更多操作：重命名、导出、转为格式笔记、删除"),
                withParent(withParent(withContentDescription(cardDescription)))))
                .perform(scrollToWithGeometry(cardDescription),click());
        probe.emit("more_button_clicked",scenario);
    }

    private static void waitForFreshCatalogAndPresent(org.hamcrest.Matcher<View> card,
            ActivityScenario<MainActivity> scenario,int minimumListAttempt,long deadline)throws Exception {
        Throwable last=null;String lastDiagnostic="UNAVAILABLE";
        while(System.nanoTime()<deadline){
            JSONObject diagnostic=readBookshelfDiagnostic(scenario);
            lastDiagnostic=diagnostic.toString();
            JSONObject noteList=diagnostic.optJSONObject("note_list");
            boolean catalogReady=diagnostic.optBoolean("catalog_rendered",false)
                    &&noteList!=null&&noteList.optInt("attempt",-1)>=minimumListAttempt;
            if(catalogReady){
                try{onView(card).check(matches(anything()));return;}
                catch(RuntimeException|AssertionError unavailable){last=unavailable;}
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Fresh bookshelf catalog and target card did not become ready: "+lastDiagnostic,last);
    }

    private static boolean coverPassPending(ActivityScenario<MainActivity> scenario)throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> state=new java.util.concurrent.atomic.AtomicReference<>("{}");
        scenario.onActivity(activity->state.set(activity.bookshelfLoadDiagnosticForTest()));
        JSONObject diagnostic=new JSONObject(state.get());
        return "COVERS_LOADING".equals(diagnostic.optString("stage"))
                &&diagnostic.optInt("covers",0)<diagnostic.optInt("notes",0);
    }

    private static void emitBookshelfLoadDiagnostic(ActivityScenario<MainActivity> scenario) {
        java.util.concurrent.atomic.AtomicReference<String> receipt=new java.util.concurrent.atomic.AtomicReference<>("UNAVAILABLE");
        if(scenario!=null)try{scenario.onActivity(activity->receipt.set(activity.bookshelfLoadDiagnosticForTest()));}
        catch(RuntimeException ignored){receipt.set("ACTIVITY_UNAVAILABLE");}
        String safe="R34_BOOKSHELF_LOAD "+receipt.get();
        System.out.println(safe);
        android.os.Bundle status=new android.os.Bundle();status.putString("stream",safe);
        InstrumentationRegistry.getInstrumentation().sendStatus(2,status);
    }
    private static ViewAction scrollToWithGeometry(String targetCardDescription) {
        ViewAction actualScroll=scrollTo();
        return new ViewAction() {
            @Override public org.hamcrest.Matcher<View> getConstraints() {
                return actualScroll.getConstraints();
            }
            @Override public String getDescription() {
                return "record target card geometry and run Espresso scrollTo";
            }
            @Override public void perform(UiController controller,View view) {
                emitGeometry("before",view,targetCardDescription);
                try { actualScroll.perform(controller,view); }
                finally { emitGeometry("after",view,targetCardDescription); }
                ViewParent parent=view.getParent();
                assertTrue("More button is directly inside its actions row",parent instanceof android.view.ViewGroup);
                android.view.ViewGroup actions=(android.view.ViewGroup)parent;
                assertTrue("More button's laid-out bounds must fit inside its actions row: top="
                        +view.getTop()+" bottom="+view.getBottom()+" rowHeight="+actions.getHeight(),
                        view.getTop()>=0&&view.getBottom()<=actions.getHeight());
            }
        };
    }
    private static void emitGeometry(String phase,View target,String targetCardDescription) {
        try {
            JSONObject record=new JSONObject();record.put("phase",phase);
            JSONArray ancestors=new JSONArray();View current=target;
            for(int depth=0;current!=null&&depth<8;depth++){
                JSONObject item=new JSONObject();item.put("depth",depth);
                item.put("class",current.getClass().getName());item.put("visibility",current.getVisibility());
                item.put("shown",current.isShown());item.put("attached",current.isAttachedToWindow());
                item.put("hasWindowFocus",current.hasWindowFocus());
                item.put("windowVisibility",current.getWindowVisibility());
                item.put("width",current.getWidth());item.put("height",current.getHeight());
                item.put("scrollX",current.getScrollX());item.put("scrollY",current.getScrollY());
                item.put("canScrollUp",current.canScrollVertically(-1));
                item.put("canScrollDown",current.canScrollVertically(1));
                item.put("isTargetCard",targetCardDescription.equals(current.getContentDescription()));
                int[] screen=new int[2];current.getLocationOnScreen(screen);
                item.put("screenX",screen[0]);item.put("screenY",screen[1]);
                Rect visible=new Rect();boolean intersects=current.getGlobalVisibleRect(visible);
                item.put("hasGlobalVisibleRect",intersects);
                if(intersects){JSONArray bounds=new JSONArray();bounds.put(visible.left).put(visible.top).put(visible.right).put(visible.bottom);item.put("globalVisibleRect",bounds);}
                ancestors.put(item);
                ViewParent parent=current.getParent();current=parent instanceof View?(View)parent:null;
            }
            record.put("ancestors",ancestors);
            System.out.println("R29_LAYOUT_GEOMETRY "+record.toString());
        } catch(Exception diagnosticFailure) {
            System.out.println("R29_LAYOUT_GEOMETRY {\"phase\":\""+phase+"\",\"captureFailed\":true}");
        }
    }
    private static org.hamcrest.Matcher<android.view.View> positiveDialogButton(String label) {
        return allOf(withId(android.R.id.button1),withText(label),isDisplayed(),
                isAssignableFrom(android.widget.Button.class));
    }
    /** Capture and click in one ViewAction so dismissal cannot invalidate a second Espresso lookup. */
    private static ViewAction clickDialogButtonWithGeometry(
            java.util.concurrent.atomic.AtomicReference<String> evidence,String label) {
        ViewAction actualClick=click();
        return new ViewAction() {
            @Override public org.hamcrest.Matcher<View> getConstraints() {
                return actualClick.getConstraints();
            }
            @Override public String getDescription() {
                return "capture positive-dialog-button geometry and perform standard Espresso click";
            }
            @Override public void perform(UiController controller,View view) {
                captureDialogButton(evidence,label,"before").perform(controller,view);
                actualClick.perform(controller,view);
                captureDialogButton(evidence,label,"after").perform(controller,view);
            }
        };
    }
    private static ViewAction captureDialogButton(
            java.util.concurrent.atomic.AtomicReference<String> evidence,String label,String phase) {
        return new ViewAction() {
            @Override public org.hamcrest.Matcher<View> getConstraints() {
                return isAssignableFrom(android.widget.Button.class);
            }
            @Override public String getDescription() {
                return "capture fixed positive-dialog-button geometry "+phase;
            }
            @Override public void perform(UiController controller,View view) {
                try {
                    JSONObject pair="after".equals(phase)&&!"NOT_CAPTURED".equals(evidence.get())
                            ?new JSONObject(evidence.get()):new JSONObject();
                    pair.put(phase,new JSONObject(dialogButtonProjection(view,label)));
                    evidence.set(pair.toString());
                } catch(Exception invalid) { evidence.set("{\"captureFailed\":true}"); }
            }
        };
    }
    private static String dialogButtonProjection(View view,String label) {
        try {
            int[] location=new int[2];view.getLocationOnScreen(location);
            Rect visible=new Rect();boolean intersects=view.getGlobalVisibleRect(visible);
            JSONArray bounds=new JSONArray();
            if(intersects)bounds.put(visible.left).put(visible.top).put(visible.right).put(visible.bottom);
            JSONObject value=new JSONObject();
            value.put("label",label).put("class","Button").put("resource","android.R.id.button1")
                    .put("x",location[0]).put("y",location[1]).put("width",view.getWidth())
                    .put("height",view.getHeight()).put("visibleRect",bounds)
                    .put("hasGlobalVisibleRect",intersects).put("enabled",view.isEnabled())
                    .put("clickable",view.isClickable()).put("shown",view.isShown())
                    .put("attached",view.isAttachedToWindow()).put("windowFocus",view.hasWindowFocus())
                    .put("visibility",view.getVisibility());
            return value.toString();
        } catch(Exception invalid) { return "{\"captureFailed\":true}"; }
    }
    private static void emitDialogButtonDiagnostic(String action,String evidence) {
        String safe="R44_DIALOG_BUTTON {\"action\":\""+action+"\",\"control\":"+evidence+"}";
        System.out.println(safe);
        android.os.Bundle status=new android.os.Bundle();status.putString("stream",safe);
        InstrumentationRegistry.getInstrumentation().sendStatus(2,status);
    }
    private static String renameDiagnostic(ActivityScenario<MainActivity> scenario) {
        java.util.concurrent.atomic.AtomicReference<String> state=new java.util.concurrent.atomic.AtomicReference<>("ACTIVITY_UNAVAILABLE");
        if(scenario!=null)try{scenario.onActivity(activity->state.set(activity.bookshelfRenameDiagnosticForTest()));}
        catch(RuntimeException ignored){state.set("ACTIVITY_UNAVAILABLE");}
        return state.get();
    }
    private static String deleteDiagnostic(ActivityScenario<MainActivity> scenario) {
        java.util.concurrent.atomic.AtomicReference<String> state=new java.util.concurrent.atomic.AtomicReference<>("ACTIVITY_UNAVAILABLE");
        if(scenario!=null)try{scenario.onActivity(activity->state.set(activity.bookshelfDeleteDiagnosticForTest()));}
        catch(RuntimeException ignored){state.set("ACTIVITY_UNAVAILABLE");}
        return state.get();
    }
    private static String deleteFailureEvidence(ActivityScenario<MainActivity> scenario) {
        java.util.concurrent.atomic.AtomicReference<String> state=new java.util.concurrent.atomic.AtomicReference<>("ACTIVITY_UNAVAILABLE");
        if(scenario!=null)try{scenario.onActivity(activity->state.set(activity.bookshelfDeleteFailureEvidenceForTest()));}
        catch(RuntimeException ignored){state.set("ACTIVITY_UNAVAILABLE");}
        return state.get();
    }
    private static void emitDeleteDiagnostic(ActivityScenario<MainActivity> scenario) {
        String safe="R38_DELETE_RESULT {\"status\":\""+deleteDiagnostic(scenario)+"\",\"failure\":\""+deleteFailureEvidence(scenario)+"\"}";
        System.out.println(safe);
        android.os.Bundle status=new android.os.Bundle();status.putString("stream",safe);
        InstrumentationRegistry.getInstrumentation().sendStatus(2,status);
    }
    private static void waitUntilPresent(org.hamcrest.Matcher<android.view.View> matcher,long deadline)throws Exception {
        Throwable last=null;
        while(System.nanoTime()<deadline){try{onView(matcher).check(matches(anything()));return;}
            catch(RuntimeException|AssertionError unavailable){last=unavailable;Thread.sleep(100);}}
        throw new AssertionError("Expected UI control did not appear",last);
    }
    private static void waitDisplayed(org.hamcrest.Matcher<android.view.View> matcher)throws Exception {
        waitDisplayed(matcher,System.nanoTime()+TimeUnit.SECONDS.toNanos(10));
    }
    private static void waitDisplayed(org.hamcrest.Matcher<android.view.View> matcher,long deadline)throws Exception {
        Throwable last=null;
        while(System.nanoTime()<deadline){try{onView(matcher).check(matches(isDisplayed()));return;}
            catch(RuntimeException|AssertionError unavailable){last=unavailable;Thread.sleep(100);}}
        throw new AssertionError("Expected UI control did not appear",last);
    }
    private static void waitAbsent(org.hamcrest.Matcher<android.view.View> matcher)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);Throwable last=null;
        while(System.nanoTime()<deadline){try{onView(matcher).check(doesNotExist());return;}
            catch(RuntimeException|AssertionError stillVisible){last=stillVisible;Thread.sleep(100);}}
        throw new AssertionError("Deleted group note remained visible in the bookshelf",last);
    }
    private static StreamingGroupStore.Snapshot awaitDifferentRevision(NoteGroupFacade facade,String id,String old)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);StreamingGroupStore.Snapshot last=null;
        while(System.nanoTime()<deadline){last=facade.openGroup(id);if(last!=null&&!old.equals(last.revision))return last;Thread.sleep(100);}
        throw new AssertionError("rename menu did not publish a whole group revision");
    }
    private static void awaitCoverMutation(ActivityScenario<MainActivity> scenario,String expected)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline){
            java.util.concurrent.atomic.AtomicReference<String> state=new java.util.concurrent.atomic.AtomicReference<>("UNAVAILABLE");
            scenario.onActivity(activity->state.set(activity.coverMutationResultForTest()));
            if(expected.equals(state.get()))return;
            if("FAILED".equals(state.get())&&"PUBLISHED".equals(expected))
                throw new AssertionError("cover operation failed before whole-group publish");
            if("PUBLISHED".equals(state.get())&&"FAILED".equals(expected))
                throw new AssertionError("stale cover callback unexpectedly published");
            Thread.sleep(100);
        }
        throw new AssertionError("cover mutation did not reach expected state "+expected);
    }
    private static void assertCoverRemovedMembersEqual(StreamingGroupStore.Snapshot original,
            StreamingGroupStore.Snapshot previous,StreamingGroupStore.Snapshot removed,
            String localNoteId)throws Exception {
        java.util.Set<String> expected=new java.util.HashSet<>(original.memberSizes().keySet());
        expected.remove("cover.bin");
        assertEquals("cover removal changes only the cover member set",expected,removed.memberSizes().keySet());
        for(String member:expected){
            byte[] before=readMember(original,member),after=readMember(removed,member);
            if("body.bin".equals(member)){
                JSONObject beforeBody=new JSONObject(new String(before,StandardCharsets.UTF_8));
                JSONObject afterBody=new JSONObject(new String(after,StandardCharsets.UTF_8));
                beforeBody.remove("updatedAt");afterBody.remove("updatedAt");
                assertEquals("removal retains note content and ink",beforeBody.toString(),afterBody.toString());
            }else if("source-provenance.bin".equals(member)){
                assertNativeRenameProvenance(after,previous,localNoteId);
            }else if("cover.state".equals(member)){
                assertArrayEquals("the original revision records its cover as present",
                        "present\n".getBytes(StandardCharsets.US_ASCII),before);
                assertArrayEquals("the prior committed revision records its cover as present",
                        "present\n".getBytes(StandardCharsets.US_ASCII),readMember(previous,member));
                assertArrayEquals("cover removal publishes the exact absent state",
                        "absent\n".getBytes(StandardCharsets.US_ASCII),after);
            }else assertArrayEquals("cover removal preserves member bytes: "+member,before,after);
        }
    }
    private static void assertCoverAssignedMembersEqual(StreamingGroupStore.Snapshot previous,
            StreamingGroupStore.Snapshot assigned,String localNoteId,byte[] previousCover)throws Exception {
        assertEquals("assignment keeps the complete group member set",previous.memberSizes().keySet(),
                assigned.memberSizes().keySet());
        byte[] newCover=readMember(assigned,"cover.bin");
        assertFalse("assignment stores the selected cover as a changed group member",
                java.util.Arrays.equals(previousCover,newCover));
        assertArrayEquals("assigned group cover is a PNG",new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a},
                java.util.Arrays.copyOf(newCover,8));
        for(String member:previous.memberSizes().keySet()){
            byte[] before=readMember(previous,member),after=readMember(assigned,member);
            if("cover.bin".equals(member))continue;
            if("body.bin".equals(member)){
                JSONObject beforeBody=new JSONObject(new String(before,StandardCharsets.UTF_8));
                JSONObject afterBody=new JSONObject(new String(after,StandardCharsets.UTF_8));
                beforeBody.remove("updatedAt");afterBody.remove("updatedAt");
                assertEquals("cover assignment retains note content and ink",beforeBody.toString(),afterBody.toString());
            }else if("source-provenance.bin".equals(member)){
                assertNativeRenameProvenance(after,previous,localNoteId);
            }else assertArrayEquals("cover assignment preserves member bytes: "+member,before,after);
        }
    }
    private static void emitRenameDiagnostic(ActivityScenario<MainActivity> scenario) {
        java.util.concurrent.atomic.AtomicReference<String> state=new java.util.concurrent.atomic.AtomicReference<>("ACTIVITY_UNAVAILABLE");
        if(scenario!=null) {
            try { scenario.onActivity(activity->state.set(activity.bookshelfRenameDiagnosticForTest())); }
            catch(RuntimeException ignored) { state.set("ACTIVITY_UNAVAILABLE"); }
        }
        String safe="R38_RENAME_RESULT {\"status\":\""+state.get()+"\",\"failure\":\""+renameFailureEvidence(scenario)+"\"}";
        System.out.println(safe);
        android.os.Bundle status=new android.os.Bundle();status.putString("stream",safe);
        InstrumentationRegistry.getInstrumentation().sendStatus(2,status);
    }
    private static String renameFailureEvidence(ActivityScenario<MainActivity> scenario) {
        java.util.concurrent.atomic.AtomicReference<String> state=new java.util.concurrent.atomic.AtomicReference<>("ACTIVITY_UNAVAILABLE");
        if(scenario!=null)try{scenario.onActivity(activity->state.set(activity.bookshelfRenameFailureEvidenceForTest()));}
        catch(RuntimeException ignored){state.set("ACTIVITY_UNAVAILABLE");}
        return state.get();
    }
    private static StreamingGroupStore.Snapshot awaitRetired(NoteGroupFacade facade,String id)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);StreamingGroupStore.Snapshot last=null;
        while(System.nanoTime()<deadline){
            last=facade.openGroupIncludingRetired(id);
            if(last!=null&&"retired".equals(body(last).optString("_padnoteShelfState")))return last;
            Thread.sleep(100);
        }
        throw new AssertionError("delete menu did not publish a full-group tombstone");
    }
    private static JSONObject body(StreamingGroupStore.Snapshot snapshot)throws Exception {
        return new JSONObject(new String(snapshot.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX),StandardCharsets.UTF_8));
    }
    private static void assertCoverPrecisionBody(StreamingGroupStore.Snapshot snapshot)throws Exception {
        String raw=new String(snapshot.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX),StandardCharsets.UTF_8);
        assertTrue("cover revisions retain the geometry negative-zero token",raw.contains("\"viewportCenterX\":-0.0"));
        assertTrue("cover revisions retain the full integer token",raw.contains("\"viewportCenterY\":9007199254740993"));
        assertTrue("cover revisions retain the precise fractional token",raw.contains("\"pageGap\":768.00001"));
        JSONObject parsed=NotePrecisionJsonParser.parseObject(raw);
        assertEquals(Long.MIN_VALUE,Double.doubleToRawLongBits(parsed.getDouble("viewportCenterX")));
        assertEquals(9007199254740993L,parsed.getLong("viewportCenterY"));
        assertEquals(Double.doubleToRawLongBits(768.00001d),
                Double.doubleToRawLongBits(parsed.getDouble("pageGap")));
    }
    private static void assertGroupFenceRejection(Throwable failure) {
        assertNotNull("legacy mutation must observe the group adoption that won the fence",failure);
        Throwable current=failure;
        while(current!=null) {
            if("GROUP_NOTE_REQUIRES_FULL_REVISION_SAVE".equals(current.getMessage()))return;
            current=current.getCause();
        }
        fail("expected the grouped-write fence error, got "+failure.getClass().getSimpleName());
    }
    private static boolean awaitThreadBlocked(Thread thread,long timeout,TimeUnit unit)throws InterruptedException {
        long deadline=System.nanoTime()+unit.toNanos(timeout);
        while(System.nanoTime()<deadline) {
            if(thread.getState()==Thread.State.WAITING)return true;
            Thread.sleep(10);
        }
        return thread.getState()==Thread.State.WAITING;
    }
    private static String readAsset(String name)throws Exception {
        try(InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name);
            java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];for(int n;(n=input.read(buffer))!=-1;)output.write(buffer,0,n);
            return new String(output.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static void assertRetirementMembersEqual(StreamingGroupStore.Snapshot previous,
                                                       StreamingGroupStore.Snapshot retired,
                                                       String localNoteId)throws Exception {
        assertEquals("retirement must carry the complete exact member set",previous.memberSizes().keySet(),retired.memberSizes().keySet());
        for(String member:previous.memberSizes().keySet()){
            byte[] before=readMember(previous,member),after=readMember(retired,member);
            if("body.bin".equals(member)){
                JSONObject beforeBody=new JSONObject(new String(before,StandardCharsets.UTF_8));
                JSONObject afterBody=new JSONObject(new String(after,StandardCharsets.UTF_8));
                assertEquals("retirement retains the user-visible title",beforeBody.optString("title"),afterBody.optString("title"));
                assertTrue("retirement advances the body update time",afterBody.optLong("updatedAt",0)>beforeBody.optLong("updatedAt",0));
                assertEquals("retirement state is explicit","retired",afterBody.getString("_padnoteShelfState"));
                assertTrue("retirement timestamp is positive",afterBody.getLong("_padnoteShelfRetiredAt")>0);
                beforeBody.remove("title");beforeBody.remove("updatedAt");
                afterBody.remove("title");afterBody.remove("updatedAt");
                afterBody.remove("_padnoteShelfState");afterBody.remove("_padnoteShelfRetiredAt");
                assertEquals("canvas content and ink remain intact",beforeBody.toString(),afterBody.toString());
            }else if("source-provenance.bin".equals(member)){
                assertFalse("new retirement body receives newly bound provenance",java.util.Arrays.equals(before,after));
                assertNativeRenameProvenance(after,previous,localNoteId);
            }else assertArrayEquals("member bytes remain exact: "+member,before,after);
        }
    }

    private static void assertFullMembersEqual(StreamingGroupStore.Snapshot expected,
                                                StreamingGroupStore.Snapshot actual)throws Exception {
        assertEquals("rename/delete must keep the full exact member set",expected.memberSizes().keySet(),actual.memberSizes().keySet());
        for(String member:expected.memberSizes().keySet()){
            byte[] before=readMember(expected,member),after=readMember(actual,member);
            if("body.bin".equals(member)){
                JSONObject expectedBody=new JSONObject(new String(before,StandardCharsets.UTF_8));
                JSONObject actualBody=new JSONObject(new String(after,StandardCharsets.UTF_8));
                expectedBody.remove("title");expectedBody.remove("updatedAt");
                actualBody.remove("title");actualBody.remove("updatedAt");
                actualBody.remove("_padnoteShelfState");actualBody.remove("_padnoteShelfRetiredAt");
                assertEquals("body content and ink remain intact",expectedBody.toString(),actualBody.toString());
            }else assertArrayEquals("member bytes remain exact: "+member,before,after);
        }
    }
    private static void assertRenameMembersEqual(StreamingGroupStore.Snapshot previous,
                                                  StreamingGroupStore.Snapshot renamed,
                                                  String localNoteId)throws Exception {
        assertEquals("rename must keep the complete member set",previous.memberSizes().keySet(),renamed.memberSizes().keySet());
        for(String member:previous.memberSizes().keySet()){
            byte[] before=readMember(previous,member),after=readMember(renamed,member);
            if("body.bin".equals(member)){
                JSONObject beforeBody=new JSONObject(new String(before,StandardCharsets.UTF_8));
                JSONObject afterBody=new JSONObject(new String(after,StandardCharsets.UTF_8));
                beforeBody.remove("title");beforeBody.remove("updatedAt");
                afterBody.remove("title");afterBody.remove("updatedAt");
                assertEquals("rename keeps note content and ink",beforeBody.toString(),afterBody.toString());
            }else if("source-provenance.bin".equals(member)){
                assertNativeRenameProvenance(after,previous,localNoteId);
            }else assertArrayEquals("rename preserves media bytes: "+member,before,after);
        }
    }
    private static void assertNativeRenameProvenance(byte[] bytes,StreamingGroupStore.Snapshot previous,
                                                      String localNoteId)throws Exception {
        java.io.DataInputStream input=new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
        byte[] magic=new byte["PadNote/UpdateSourceProvenance/v2\0".length()];input.readFully(magic);
        assertEquals("PadNote/UpdateSourceProvenance/v2\0",new String(magic,StandardCharsets.US_ASCII));
        assertEquals("NATIVE_ANDROID",readProvenanceString(input));
        assertEquals("rename provenance is bound to the same source lineage",previous.lineage,readProvenanceString(input));
        assertEquals("rename provenance is bound to the local note",localNoteId,readProvenanceString(input));
        assertEquals("rename provenance records the exact prior base revision",previous.revision,readProvenanceString(input));
        assertTrue("typed provenance contains the body/media witness fields",input.available()>0);
    }
    private static String readProvenanceString(java.io.DataInputStream input)throws Exception {
        int length=input.readInt();assertTrue("bounded provenance string",length>=0&&length<=4096&&length<=input.available());
        byte[] value=new byte[length];input.readFully(value);return new String(value,StandardCharsets.UTF_8);
    }
    private static void assertSameMembersAndBytes(StreamingGroupStore.Snapshot expected,
                                                   StreamingGroupStore.Snapshot actual)throws Exception {
        assertEquals(expected.memberSizes().keySet(),actual.memberSizes().keySet());
        for(String member:expected.memberSizes().keySet())assertArrayEquals(member,readMember(expected,member),readMember(actual,member));
    }
    private static byte[] readMember(StreamingGroupStore.Snapshot group,String member)throws Exception {
        Long size=group.memberSizes().get(member);assertNotNull("member exists: "+member,size);
        assertTrue(size<=Integer.MAX_VALUE);return group.readSmall(member,Math.max(1,size.intValue()));
    }
    private static String sha256(byte[] bytes)throws Exception {
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result=new StringBuilder();for(byte value:hash)result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return result.toString();
    }
    private static int openDescriptorCount()throws Exception {
        String[] entries=new File("/proc/self/fd").list();
        if(entries==null)throw new AssertionError("proc fd count is unavailable");
        return entries.length;
    }
    private static void writePdf(File file)throws Exception {
        PdfDocument doc=new PdfDocument();PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(160,160,1).create());
        page.getCanvas().drawColor(Color.WHITE);Paint paint=new Paint();paint.setColor(Color.BLACK);page.getCanvas().drawText("R20 rename/delete",10,40,paint);
        doc.finishPage(page);try(FileOutputStream out=new FileOutputStream(file)){doc.writeTo(out);out.flush();out.getFD().sync();}finally{doc.close();}
    }
}
