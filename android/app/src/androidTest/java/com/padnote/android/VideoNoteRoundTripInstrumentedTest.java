package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

import com.padnote.android.streaming.StreamingGroupStore;

import android.content.Context;
import android.graphics.Bitmap;
import android.app.UiAutomation;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.View;
import android.widget.VideoView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.espresso.Root;
import androidx.test.espresso.ViewAssertion;
import androidx.test.espresso.matcher.RootMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.hamcrest.Matcher;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.hamcrest.Matchers;

/** End-to-end note entry, frozen-input, verified attachment and offline-open UI flow. */
@RunWith(AndroidJUnit4.class)
public final class VideoNoteRoundTripInstrumentedTest {
    private static final String STAGE_TAG = "VideoRoundTripStage";
    private static final String NOTE_TITLE = "原笔记视频闭环测试";
    private static final String VISIBLE_EDIT = "尚未保存的可见修改";
    private static final String FIRST_MATERIAL = "第一版整理材料-旧快照";
    private static final String SECOND_MATERIAL = "第二版整理材料-显式保留";
    private static final String THIRD_MATERIAL = "第三版整理材料-不得自动替换";

    @Test public void noteEntryFreezesExplicitSnapshotAndReopensOnlyVerifiedOfflineVideo() throws Exception {
        runRoundTrip(false);
    }

    @Test public void failedAttachPreservesTargetInstalledByConcurrentWriter() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String noteId=java.util.UUID.randomUUID().toString();
        String materialId=java.util.UUID.randomUUID().toString();
        byte[] sourceBytes={1,3,5,7},foreignBytes={2,4,6,8,10};
        String digest=hex(MessageDigest.getInstance("SHA-256").digest(sourceBytes));
        File source=new File(context.getCacheDir(),"r66d-source-"+materialId+".mp4");
        File root=new File(context.getFilesDir(),"video-attachments-r66d-"+materialId);
        File target=new File(root,"video-"+materialId+".mp4");
        VideoAttachmentStore store=null;
        try {
            try(FileOutputStream out=new FileOutputStream(source)){out.write(sourceBytes);out.flush();out.getFD().sync();}
            AtomicInteger lookups=new AtomicInteger();
            store=new VideoAttachmentStore(root,id->{
                if(!noteId.equals(id))return null;
                if(lookups.incrementAndGet()==2){
                    try(FileOutputStream out=new FileOutputStream(target)){out.write(foreignBytes);out.flush();out.getFD().sync();}
                }
                return new NoteStore.Entry(noteId,"r66d-race",1,0,1);
            });
            VideoAttachmentStore.Attachment requested=fixtureAttachment(noteId,materialId,
                    "racing-result.mp4",sourceBytes,digest,1);
            try { store.attach(requested,source);fail("a target created during attach must not be replaced"); }
            catch(java.io.IOException expected) { assertEquals("无法安全保存视频附件",expected.getMessage()); }
            assertArrayEquals("the competing target remains byte-for-byte intact",foreignBytes,readFile(target));
            assertTrue("failed attach does not publish an attachment row",store.listForNote(noteId).isEmpty());
            File[] children=root.listFiles();assertNotNull("owned root remains inspectable",children);
            assertEquals("only the competing target remains",1,children.length);
            assertEquals(target.getName(),children[0].getName());
        } finally {
            if(target.exists())target.delete();
            if(root.exists())root.delete();
            source.delete();
        }
    }

    @Test @SdkSuppress(minSdkVersion=27)
    public void groupedNoteEntryAttachesAndRemovesThroughCapturedWholeRevision() throws Exception {
        runRoundTrip(true);
    }

    @Test @SdkSuppress(minSdkVersion=27)
    public void pendingCapacityFailurePrecedesDownloadAndLeavesExistingRecordsReadable() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String noteId=java.util.UUID.randomUUID().toString();byte[] bytes=new byte[]{1,2,3,4};String sha=hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        PendingVideoArtifactStore store=new PendingVideoArtifactStore(context);List<PendingVideoArtifactStore.Entry> created=new java.util.ArrayList<>();
        File original=new File(context.getFilesDir(),"r66b-capacity-"+noteId+".mp4");
        try(FileOutputStream out=new FileOutputStream(original)){out.write(bytes);out.flush();out.getFD().sync();}
        try{
            try{
                store.reserve(fixtureAttachment("../"+noteId,java.util.UUID.randomUUID().toString(),
                        "unsafe-note-id.mp4",bytes,sha,1),null,null,null);
                fail("note IDs remain filename-safe even when non-UUID IDs are supported");
            }catch(java.io.IOException expected){assertEquals("PENDING_VIDEO_ARGUMENT_INVALID",expected.getMessage());}
            for(int i=0;i<24;i++){
                String id=java.util.UUID.randomUUID().toString();
                created.add(store.reserve(fixtureAttachment(noteId,id,"capacity-"+i+".mp4",bytes,sha,1),null,null,null));
            }
            assertEquals("bounded reservations remain discoverable",24,
                    store.listAll().stream().filter(entry->entry.attachment!=null&&noteId.equals(entry.attachment.noteId)).count());
            try{
                store.reserve(fixtureAttachment(noteId,java.util.UUID.randomUUID().toString(),"overflow.mp4",bytes,sha,1),null,null,null);
                fail("capacity exhaustion must refuse before an attachment/download attempt");
            }catch(java.io.IOException expected){assertEquals("PENDING_VIDEO_CAPACITY_REACHED",expected.getMessage());}
            assertEquals("no reservation consumes or modifies the source artifact",sha,shaFile(original));
            assertTrue("the original verified result is still available to the task flow",original.isFile());
        }finally{
            for(PendingVideoArtifactStore.Entry entry:created)try{store.remove(entry);}catch(Exception ignored){}
            original.delete();
        }
    }

    @Test @SdkSuppress(minSdkVersion=27)
    public void interruptedPendingRemovalRecoversWithoutHidingOtherVerifiedResults() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String noteId=java.util.UUID.randomUUID().toString();byte[] payload=new byte[8192];Arrays.fill(payload,(byte)0x5a);
        String sha=hex(MessageDigest.getInstance("SHA-256").digest(payload));PendingVideoArtifactStore store=new PendingVideoArtifactStore(context);
        PendingVideoArtifactStore.Entry deleting=null,retained=null;File input=new File(context.getFilesDir(),"r66b-remove-input-"+noteId+".mp4");
        File tombstone=null,unknown=null;
        try(FileOutputStream out=new FileOutputStream(input)){out.write(payload);out.flush();out.getFD().sync();}
        try{
            deleting=completeFixture(store,noteId,"remove-half.mp4",payload,sha,input);
            retained=completeFixture(store,noteId,"keep-after-reopen.mp4",payload,sha,input);
            tombstone=new File(deleting.directory.getParentFile(),".deleting-"+deleting.operationId);
            assertTrue("deletion starts with the operation-directory tombstone",deleting.directory.renameTo(tombstone));
            assertTrue("fixture models process death after payload unlink",new File(tombstone,"payload.mp4").delete());
            unknown=new File(tombstone,"unknown-preserved.bin");
            try(FileOutputStream out=new FileOutputStream(unknown)){out.write(new byte[]{9,8,7});out.flush();out.getFD().sync();}
            List<PendingVideoArtifactStore.Entry> reopened=new PendingVideoArtifactStore(context).listAll();
            PendingVideoArtifactStore.Entry surviving=null;for(PendingVideoArtifactStore.Entry item:reopened)
                if(retained.operationId.equals(item.operationId))surviving=item;
            assertNotNull("restart recovery keeps unrelated pending result",surviving);
            assertTrue("unrelated payload is still verified after tombstone recovery",surviving.available);
            assertEquals(sha,shaFile(surviving.videoFile));
            PendingVideoArtifactStore.Entry interruptedDelete=null;for(PendingVideoArtifactStore.Entry item:reopened)
                if(deleting.operationId.equals(item.operationId))interruptedDelete=item;
            assertNotNull("malformed tombstone remains visible beside unrelated valid result",interruptedDelete);
            assertTrue("valid operation metadata stays available for recovery",interruptedDelete.attachment!=null);
            assertTrue("interrupted tombstone is clearly marked for cleanup",
                    PendingVideoArtifactStore.isDeleteIncomplete(interruptedDelete));
            assertTrue("tombstone preserves its task record until unknown data is handled",
                    new File(tombstone,"record.json").isFile());
            assertTrue("recovery never deletes an unknown child from a tombstone",unknown.isFile());
            assertTrue("unknown child leaves only its own deletion visibly incomplete",tombstone.isDirectory());
        }finally{
            if(unknown!=null&&unknown.exists())unknown.delete();
            try{new PendingVideoArtifactStore(context).listAll();}catch(Exception ignored){}
            if(deleting!=null)try{PendingVideoArtifactStore.Entry current=store.findOperation(deleting.operationId);if(current!=null)store.remove(current);}catch(Exception ignored){}
            if(retained!=null)try{store.remove(store.findOperation(retained.operationId));}catch(Exception ignored){}
            input.delete();
        }
    }

    @Test @SdkSuppress(minSdkVersion=27)
    public void verifiedDownloadSurvivesRestartBeforePendingPromotion() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String noteId=java.util.UUID.randomUUID().toString();byte[] payload=new byte[32768];Arrays.fill(payload,(byte)0x6b);
        String sha=hex(MessageDigest.getInstance("SHA-256").digest(payload));
        PendingVideoArtifactStore initial=new PendingVideoArtifactStore(context);
        VideoAttachmentStore.Attachment attachment=fixtureAttachment(noteId,java.util.UUID.randomUUID().toString(),
                "verified-before-promotion.mp4",payload,sha,1);
        PendingVideoArtifactStore.Entry reserved=initial.reserve(attachment,null,null,null);
        try{
            File privateOperation=initial.downloadDirectory(reserved);
            File verifiedPart=new File(privateOperation,"agent-artifact-"+attachment.id+".part");
            try(FileOutputStream out=new FileOutputStream(verifiedPart)){out.write(payload);out.flush();}
            // Simulate recovery of a complete-looking part whose writer did not confirm file fsync
            // before interruption. Recovery must verify and sync that exact file before marking it durable.
            java.io.FileDescriptor directory=android.system.Os.open(privateOperation.getAbsolutePath(),
                    android.system.OsConstants.O_RDONLY|android.system.OsConstants.O_CLOEXEC,0);
            try{android.system.Os.fsync(directory);}finally{android.system.Os.close(directory);}
            PendingVideoArtifactStore.Entry recovered=new PendingVideoArtifactStore(context).findOperation(attachment.id);
            assertNotNull("verified operation can be found after constructing a fresh store",recovered);
            assertTrue("matching bounded .part bytes remain exportable after restart",recovered.available);
            assertTrue("recovered bytes are durable only after the matched payload itself is synced",recovered.durable);
            assertTrue("recovered payload remains app-private",recovered.videoFile.getCanonicalPath()
                    .startsWith(context.getFilesDir().getCanonicalPath()+File.separator));
            assertEquals("recovery requires exact bytes, not just a filename",sha,shaFile(recovered.videoFile));
        }finally{
            PendingVideoArtifactStore.Entry current=initial.findOperation(attachment.id);
            if(current!=null)initial.remove(current);
        }
    }

    @Test @SdkSuppress(minSdkVersion=27)
    public void malformedTombstoneDoesNotHideOtherResultsOrEraseItsRecord() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String noteId=java.util.UUID.randomUUID().toString();byte[] payload=new byte[4096];Arrays.fill(payload,(byte)0x42);
        String sha=hex(MessageDigest.getInstance("SHA-256").digest(payload));PendingVideoArtifactStore store=new PendingVideoArtifactStore(context);
        PendingVideoArtifactStore.Entry damaged=null,retained=null;File input=new File(context.getFilesDir(),"r66c-malformed-input-"+noteId+".mp4");
        File tombstone=null,malformedPayload=null,foreignChild=null;
        try(FileOutputStream out=new FileOutputStream(input)){out.write(payload);out.flush();out.getFD().sync();}
        try{
            damaged=completeFixture(store,noteId,"malformed-tombstone.mp4",payload,sha,input);
            retained=completeFixture(store,noteId,"valid-sibling.mp4",payload,sha,input);
            tombstone=new File(damaged.directory.getParentFile(),".deleting-"+damaged.operationId);
            assertTrue(damaged.directory.renameTo(tombstone));
            malformedPayload=new File(tombstone,"payload.mp4");assertTrue(malformedPayload.delete());
            assertTrue("fixture replaces the recognized payload with an unsafe directory",malformedPayload.mkdir());
            foreignChild=new File(malformedPayload,"preserve.bin");try(FileOutputStream out=new FileOutputStream(foreignChild)){out.write(new byte[]{6,5,4});out.flush();out.getFD().sync();}

            List<PendingVideoArtifactStore.Entry> reopened=new PendingVideoArtifactStore(context).listAll();
            PendingVideoArtifactStore.Entry interrupted=null,valid=null;for(PendingVideoArtifactStore.Entry item:reopened){
                if(damaged.operationId.equals(item.operationId))interrupted=item;
                if(retained.operationId.equals(item.operationId))valid=item;
            }
            assertNotNull("malformed tombstone remains represented",interrupted);
            assertTrue("original task record survives unsafe sibling preflight",interrupted.attachment!=null);
            assertTrue("tombstone remains marked for cleanup",PendingVideoArtifactStore.isDeleteIncomplete(interrupted));
            assertTrue("malformed payload directory is not unlinked",malformedPayload.isDirectory());
            assertEquals("foreign child bytes survive",hex(MessageDigest.getInstance("SHA-256").digest(new byte[]{6,5,4})),shaFile(foreignChild));
            assertNotNull("one malformed tombstone does not hide another operation",valid);
            assertTrue("unrelated verified result remains exportable",valid.available);
            assertEquals(sha,shaFile(valid.videoFile));
        }finally{
            if(foreignChild!=null)foreignChild.delete();
            if(malformedPayload!=null)malformedPayload.delete();
            try{new PendingVideoArtifactStore(context).listAll();}catch(Exception ignored){}
            if(damaged!=null)try{PendingVideoArtifactStore.Entry current=store.findOperation(damaged.operationId);if(current!=null)store.remove(current);}catch(Exception ignored){}
            if(retained!=null)try{PendingVideoArtifactStore.Entry current=store.findOperation(retained.operationId);if(current!=null)store.remove(current);}catch(Exception ignored){}
            input.delete();
        }
    }

    @Test @SdkSuppress(minSdkVersion=27)
    public void pendingRetryReconcilesAnAlreadyPublishedExactMaterialWithoutDuplicateRevision() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        NoteStore.Entry note=NoteStore.create(context,"待处理结果确认测试");
        byte[] bytes;
        try(InputStream asset=InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("video/roundtrip.mp4");ByteArrayOutputStream buffer=new ByteArrayOutputStream()) {
            byte[] chunk=new byte[8192];int count;
            while((count=asset.read(chunk))!=-1)if(count>0)buffer.write(chunk,0,count);
            bytes=buffer.toByteArray();
        }
        assertTrue("pending retry fixture is the valid bundled MP4",bytes.length>0);
        String sha=hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        PendingVideoArtifactStore store=new PendingVideoArtifactStore(context);PendingVideoArtifactStore.Entry pending=null;
        ActivityScenario<MainActivity> scenario=null;StreamingGroupStore.Snapshot committed=null;
        File input=new File(context.getFilesDir(),"r66b-uncertain-input-"+note.id+".mp4");
        try(FileOutputStream out=new FileOutputStream(input)){out.write(bytes);out.flush();out.getFD().sync();}
        try{
            NoteStore.Entry source=findNote(context,note.id);StreamingGroupStore.Snapshot base=
                    new NoteGroupFacade(context).adoptLegacyNote(note.id,(old,staged)->true);
            VideoAttachmentStore.Attachment attachment=fixtureAttachment(note.id,java.util.UUID.randomUUID().toString(),"uncertain.mp4",bytes,sha,source.updatedAt);
            pending=completeFixture(store,note.id,"uncertain.mp4",bytes,sha,input,attachment,base);
            com.padnote.android.streaming.LegacyFileCapture.Result verified=
                    com.padnote.android.streaming.LegacyFileCapture.inspect(context,pending.videoFile,StreamingGroupStore.VIDEO_MAX);
            committed=new NoteGroupFacade(context).attachVideoRevision(note.id,base.revision,base.digest,attachment,verified);
            scenario=ActivityScenario.launch(MainActivity.class);ActivityScenario<MainActivity> active=scenario;
            emitStage("pending_retry_waiting_for_shelf");
            awaitShelfNote("待处理结果确认测试");
            emitStage("pending_retry_shelf_ready");
            onView(withContentDescription("查看、另存或删除待处理视频结果")).perform(click());
            emitStage("pending_retry_results_open");
            onView(withText(Matchers.startsWith("uncertain.mp4"))).inRoot(RootMatchers.isDialog()).perform(click());
            onView(withText("附加状态未确认；已校验视频保存在本机。重新关联前会要求你明确选择来源笔记并确认其当前版本。"))
                    .inRoot(RootMatchers.isDialog()).check(matches(isDisplayed()));
            onView(withText("重新选择来源笔记并关联")).inRoot(RootMatchers.isDialog()).check(matches(isDisplayed()));
            onView(withText("另存到文件")).inRoot(RootMatchers.isDialog()).check(matches(isDisplayed()));
            onView(withText("删除待处理副本")).inRoot(RootMatchers.isDialog()).check(matches(isDisplayed()));
            onView(withText("重新选择来源笔记并关联")).inRoot(RootMatchers.isDialog()).perform(click());
            onView(withText(Matchers.startsWith("任务来源身份固定为“待处理结果确认测试”")))
                    .inRoot(RootMatchers.isDialog()).check(matches(isDisplayed()));
            onView(withText("待处理结果确认测试"))
                    .inRoot(RootMatchers.isDialog()).perform(click());
            emitStage("pending_retry_source_selected");
            onView(withText("确认关联")).inRoot(RootMatchers.isDialog()).perform(click());
            emitStage("pending_retry_confirmation_clicked");
            waitUntil(()->store.listAll().stream().noneMatch(item->pendingId(item,attachment.id)));
            emitStage("pending_retry_reconciled");
            StreamingGroupStore.Snapshot after=new NoteGroupFacade(context).openGroup(note.id);
            assertEquals("exact publication recognition does not write another group revision",committed.revision,after.revision);
            assertEquals("exactly one copy of the fixed material UUID remains",1,
                    GroupAuthorityBridge.videoViews(after).stream().filter(item->attachment.id.equals(item.id)).count());
        }finally{
            if(scenario!=null)scenario.close();
            try{if(pending!=null){PendingVideoArtifactStore.Entry current=store.findOperation(pending.operationId);if(current!=null)store.remove(current);}}catch(Exception ignored){}
            try{StreamingGroupStore.Snapshot current=new NoteGroupFacade(context).openGroup(note.id);
                if(current!=null)new NoteGroupFacade(context).retireGroup(note.id,current.revision,current.digest);}catch(Exception ignored){}
            input.delete();
        }
    }

    private void runRoundTrip(boolean grouped) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        byte[] mp4;
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("video/roundtrip.mp4")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) >= 0) { if (n > 0) bytes.write(buffer, 0, n); }
            mp4 = bytes.toByteArray();
        }
        String mp4Sha = hex(MessageDigest.getInstance("SHA-256").digest(mp4));
        assertTrue(mp4.length > 0);

        AgentConnectionStore connections = new AgentConnectionStore(context);
        connections.clear();
        AgentConnectionStore.Config draft = connections.addBridge("本机测试 Bridge",
                AgentConnectionStore.Kind.HERMES, "https://bridge.test", "bridge-roundtrip",
                "instance-roundtrip", "synthetic-test-token");
        assertTrue(connections.applyProbeSuccess(draft.id, draft.revision,
                new AgentConnectionClient.ProbeResult("verified", "bridge-roundtrip",
                        "instance-roundtrip", Arrays.asList("task_bundle", "run_submission",
                                "run_status", "artifacts"))));

        NoteStore.Entry created = NoteStore.create(context, NOTE_TITLE);
        VaultStore vault = new VaultStore(context);
        ActivityScenario<MainActivity> scenario = null;
        AtomicInteger postCount = new AtomicInteger();
        AtomicInteger statusCount = new AtomicInteger();
        AtomicInteger downloadCount = new AtomicInteger();
        AtomicReference<Exception> attachFailure = new AtomicReference<>();
        AtomicReference<byte[]> submittedBody = new AtomicReference<>();
        AgentTaskStore tasks = new AgentTaskStore(context);
        final String[] submittedClientId = {""};
        final VideoAttachmentStore[] attachmentStore = {null};
        final VideoAttachmentStore.Attachment[] attached = {null};
        final StreamingGroupStore.Snapshot[] groupBeforeAttach = {null};
        final StreamingGroupStore.Snapshot[] groupAfterConcurrentEdit = {null};
        final StreamingGroupStore.Snapshot[] groupAfterAttach = {null};
        try {
            NoteStore.Entry initial = findNote(context, created.id);
            vault.write(created.id, initial.title, 1, initial.updatedAt,
                    Collections.singletonList(FIRST_MATERIAL));

            AgentHttpTransport transport = request -> {
                String path = request.url.getPath();
                if ("POST".equals(request.method) && path.endsWith("/runs")) {
                    postCount.incrementAndGet();
                    submittedBody.set(request.body.clone());
                    JSONObject payload = new JSONObject(new String(request.body, StandardCharsets.UTF_8));
                    submittedClientId[0] = payload.getString("client_task_id");
                    JSONObject accepted = new JSONObject().put("task_id", "remote-roundtrip")
                            .put("instance_id", "instance-roundtrip").put("status", "completed");
                    return response(request, 202, accepted.toString());
                }
                if ("GET".equals(request.method) && path.endsWith("/runs/remote-roundtrip")) {
                    statusCount.incrementAndGet();
                    JSONObject artifact = new JSONObject().put("id", "artifact-roundtrip")
                            .put("name", "roundtrip.mp4").put("media_type", "video/mp4")
                            .put("size_bytes", mp4.length).put("sha256", mp4Sha);
                    JSONObject status = new JSONObject().put("task_id", "remote-roundtrip")
                            .put("instance_id", "instance-roundtrip").put("status", "completed")
                            .put("output", "已完成测试视频").put("artifacts", new JSONArray().put(artifact))
                            .put("conversation_id", "remote-roundtrip").put("parent_task_id", "")
                            .put("followup_available", false).put("followup_reason", "fixture");
                    return response(request, 200, status.toString());
                }
                throw new AssertionError("unexpected local fixture request " + request.method + " " + path);
            };

            scenario = ActivityScenario.launch(MainActivity.class);
            ActivityScenario<MainActivity> activeScenario = scenario;
            emitStage(grouped ? "grouped_activity_launched" : "legacy_activity_launched");
            scenario.onActivity(activity -> {
                activity.setVideoTaskClientFactoryForTest(() -> new AgentTaskClient(transport));
                activity.setVideoArtifactSourceForTest((url, bearer, pin) -> {
                    assertEquals("synthetic-test-token", bearer);
                    assertTrue(url.getPath().endsWith("/artifacts/artifact-roundtrip"));
                    downloadCount.incrementAndGet();
                    return new AgentArtifactDownloader.OpenResponse(200, url, url, mp4.length,
                            new ByteArrayInputStream(mp4), null);
                });
                activity.setVideoAttachmentFailureForTest(attachFailure::set);
                invokeOpenNote(activity, created);
                attachmentStore[0] = getVideoAttachmentStore(context);
            });
            waitForRestore(activeScenario);
            emitStage(grouped ? "grouped_editor_ready" : "legacy_editor_ready");

            activeScenario.onActivity(activity -> {
                NoteCanvasView canvas = getCanvas(activity);
                assertNotNull("visible note canvas should be ready", canvas);
                assertNotNull("test edit must be added to the live canvas",
                        canvas.addTextBox(NoteTextBox.Format.MARKDOWN, VISIBLE_EDIT));
            });
            onView(withContentDescription("当前笔记的视频任务与成果")).perform(click());
            emitStage(grouped ? "grouped_hub_open" : "legacy_hub_open");
            onView(withText(NOTE_TITLE)).inRoot(RootMatchers.isDialog()).check(matches(isDisplayed()));
            saveCheckpointScreenshot(context,"01-note-video-hub.png");
            waitUntil(() -> containsJson(NoteStore.load(context, created.id), VISIBLE_EDIT));
            assertTrue("video entry must save the visible edit before showing the hub",
                    containsJson(NoteStore.load(context, created.id), VISIBLE_EDIT));

            NoteStore.Entry savedEdit = findNote(context, created.id);
            vault.write(created.id, savedEdit.title, 1, savedEdit.updatedAt,
                    Collections.singletonList(FIRST_MATERIAL));
            startSendToComputer();
            awaitDialogText(FIRST_MATERIAL);

            updateSource(context, vault, created.id, "第二版标题", SECOND_MATERIAL);
            onView(withText("确认使用")).inRoot(RootMatchers.isDialog()).perform(click());
            awaitDialogText("原材料已更新");
            onView(withText("取消")).inRoot(RootMatchers.isDialog()).perform(click());
            assertEquals("cancelling after a source change must send no task request", 0, postCount.get());

            onView(withContentDescription("当前笔记的视频任务与成果")).perform(click());
            onView(withText("生成讲解视频")).inRoot(RootMatchers.isDialog()).perform(click());
            onView(withText("发送到电脑")).inRoot(RootMatchers.isDialog()).perform(click());
            awaitDialogText(SECOND_MATERIAL);
            saveCheckpointScreenshot(context,"02-frozen-material-preview.png");
            updateSource(context, vault, created.id, "第三版标题", THIRD_MATERIAL);
            onView(withText("确认使用")).inRoot(RootMatchers.isDialog()).perform(click());
            awaitDialogText("原材料已更新");
            onView(withText("使用旧快照")).inRoot(RootMatchers.isDialog()).perform(click());
            awaitDialogText("发送到哪一个 Agent");
            onView(withText(Matchers.startsWith("本机测试 Bridge · Hermes · 主机 bridge.test / 助手 bridge-roundtrip / 实例 oundtrip")))
                    .inRoot(RootMatchers.isDialog()).perform(click());
            awaitDialogText("确认发送任务包");
            String expectedConfirmation = "目标：" + AgentTaskDialogs.destinationLabel(connections.get(draft.id))
                    + "\n材料：生成讲解视频 · 第二版标题\n确认后会将所选材料发送给此电脑 Agent。";
            onView(withText(expectedConfirmation)).inRoot(RootMatchers.isDialog()).check(matches(isDisplayed()));
            assertEquals("the final confirmation must not submit before the user accepts it", 0, postCount.get());
            assertEquals("no status request may begin before the user accepts the bundle", 0, statusCount.get());
            onView(withText("发送到此 Agent")).inRoot(RootMatchers.isDialog()).perform(click());
            awaitDialogText("状态：已完成");
            assertEquals("explicitly keeping the frozen snapshot should submit once", 1, postCount.get());
            assertEquals(1, statusCount.get());
            String frozenMarkdown = bundleMarkdown(submittedBody.get());
            assertTrue(frozenMarkdown, frozenMarkdown.contains(SECOND_MATERIAL));
            assertFalse("third-version content must not silently replace the old snapshot",
                    frozenMarkdown.contains(THIRD_MATERIAL));

            awaitViewTextPrefix("保存产物：roundtrip.mp4");
            onView(withText(Matchers.startsWith("保存产物：roundtrip.mp4")))
                    .inRoot(RootMatchers.isDialog()).perform(scrollTo(), click());
            emitStage(grouped ? "grouped_artifact_selected" : "legacy_artifact_selected");
            if(grouped){
                // Convert the still-legacy note only after its task source snapshot has been
                // completed. The real task-artifact menu callback must carry this exact group base.
                groupBeforeAttach[0]=new NoteGroupFacade(context).adoptLegacyNote(created.id,(old,staged)->true);
            }
            onView(withText("关联到来源笔记")).inRoot(RootMatchers.isDialog()).perform(click());
            if(grouped){
                assertNotNull("the actual UI shows explicit attachment confirmation",groupBeforeAttach[0]);
                awaitDialogText("关联已校验视频");
                onView(withText("下载并关联")).inRoot(RootMatchers.isDialog()).check(matches(isDisplayed()));
                emitStage("grouped_confirmation_shown");
                JSONObject originalBody=new JSONObject(new String(groupBeforeAttach[0].readSmall("body.bin",
                        (int)StreamingGroupStore.BODY_MAX),StandardCharsets.UTF_8));
                String editorBodyJson=NoteJsonCodec.stringify(originalBody);
                groupAfterConcurrentEdit[0]=new NoteGroupFacade(context).saveEditorRevision(created.id,
                        groupBeforeAttach[0].revision,groupBeforeAttach[0].digest,
                        originalBody.optString("title",NOTE_TITLE),editorBodyJson);
                StreamingGroupStore.Snapshot verifiedEditor=new NoteGroupFacade(context).openGroup(created.id);
                assertEquals(groupAfterConcurrentEdit[0].revision,verifiedEditor.revision);
                assertEquals(groupAfterConcurrentEdit[0].digest,verifiedEditor.digest);
                groupAfterConcurrentEdit[0]=verifiedEditor;
                assertMembersPreservedExceptBodyAndProvenance(groupBeforeAttach[0],groupAfterConcurrentEdit[0]);
                assertMemberKeysSame(groupBeforeAttach[0],groupAfterConcurrentEdit[0]);
                assertNoteJsonEquals("editor save changes no canvas field except updatedAt",withoutUpdatedAt(originalBody),
                        withoutUpdatedAt(NotePrecisionJsonParser.parseObject(new String(groupAfterConcurrentEdit[0].readSmall(
                                "body.bin",(int)StreamingGroupStore.BODY_MAX),StandardCharsets.UTF_8))));
                assertDerivedProvenance(groupBeforeAttach[0],groupAfterConcurrentEdit[0]);
                assertHistoricalProvenanceUnchanged(context,created.id,groupBeforeAttach[0]);
                onView(withText("下载并关联")).inRoot(RootMatchers.isDialog()).perform(click());
                emitStage("grouped_confirmation_clicked");
                waitUntil(()->attachFailure.get()!=null&&new PendingVideoArtifactStore(context)
                        .listForNote(created.id).size()==1);
                assertEquals("the delayed UI callback keeps its old revision and cannot rebind",
                        "BASE_CAS_CONFLICT",attachFailure.get().getMessage());
                emitStage("grouped_stale_cas_rejected");
                StreamingGroupStore.Snapshot afterStaleAttach=new NoteGroupFacade(context).openGroup(created.id);
                assertTrue("failed stale attach does not appear in current group",GroupAuthorityBridge.videoViews(afterStaleAttach).isEmpty());
                assertEquals(groupAfterConcurrentEdit[0].revision,afterStaleAttach.revision);
                assertEquals(groupAfterConcurrentEdit[0].digest,afterStaleAttach.digest);
                assertSnapshotMembersExact(groupAfterConcurrentEdit[0],afterStaleAttach);
                PendingVideoArtifactStore.Entry pending=new PendingVideoArtifactStore(context)
                        .listForNote(created.id).get(0);
                assertEquals("pending record binds the base captured before confirmation",
                        groupBeforeAttach[0].revision,pending.baseRevision);
                assertEquals(groupBeforeAttach[0].lineage,pending.baseLineage);
                assertEquals(groupBeforeAttach[0].digest,pending.baseDigest);
                assertEquals(mp4Sha,pending.attachment.sha256);
                assertTrue("pending bytes remain app-private",
                        pending.videoFile.getCanonicalPath().startsWith(context.getFilesDir().getCanonicalPath()+File.separator));
                assertMembersPreservedExceptBodyAndProvenance(groupBeforeAttach[0],groupAfterConcurrentEdit[0]);
                assertDerivedProvenance(groupBeforeAttach[0],groupAfterConcurrentEdit[0]);
                activeScenario.recreate();
                NoteStore.Entry reopenedNote=findNote(context,created.id);
                assertEquals("cold reopen must target the same note at its explicitly saved third revision",
                        "第三版标题",reopenedNote.title);
                String reopenedCardDescription="打开笔记 "+reopenedNote.title+"。长按可重命名或更换封面。";
                waitUntil(()->{
                    try{
                        onView(withContentDescription(reopenedCardDescription)).check(matches(isDisplayed()));
                        return true;
                    }catch(RuntimeException|AssertionError shelfStillLoading){return false;}
                });
                onView(withContentDescription(reopenedCardDescription)).perform(click());
                waitForRestore(activeScenario);
                // ActivityScenario.recreate() replaced the Activity that owned this test sink.
                // Rebind only the observer needed by the later corruption assertion; factories,
                // persisted authority, restore state, and pending bytes remain untouched.
                activeScenario.onActivity(activity ->
                        activity.setVideoAttachmentFailureForTest(attachFailure::set));
                // The task-detail dialog belonged to the destroyed Activity. Continue through the
                // restored note's real video toolbar, then choose its current pending-video list.
                onView(withContentDescription("当前笔记的视频任务与成果")).check(matches(isDisplayed())).perform(click());
                onView(withText("待处理关联视频")).inRoot(RootMatchers.isDialog()).perform(click());
                onView(withText(Matchers.startsWith("roundtrip.mp4"))).inRoot(RootMatchers.isDialog()).perform(click());
                onView(withText("重新选择来源笔记并关联")).inRoot(RootMatchers.isDialog()).perform(click());
                onView(withText(reopenedNote.title))
                        .inRoot(RootMatchers.isDialog()).perform(click());
                attachFailure.set(null);
                onView(withText("确认关联")).inRoot(RootMatchers.isDialog()).perform(click());
                emitStage("grouped_explicit_retry_clicked");
                groupAfterAttach[0]=awaitGroupVideo(context,created.id,downloadCount,attachFailure);
                emitStage("grouped_attach_published");
                assertNotEquals("explicit retry publishes a new immutable group revision",
                        groupAfterConcurrentEdit[0].revision,groupAfterAttach[0].revision);
                assertTrue("successful commit clears only the exact pending artifact",
                        new PendingVideoArtifactStore(context).listForNote(created.id).isEmpty());
                attached[0]=GroupAuthorityBridge.videoViews(groupAfterAttach[0]).get(0);
                assertMembersPreservedExceptBodyAndProvenance(groupAfterConcurrentEdit[0],groupAfterAttach[0]);
                assertMemberKeysAdded(groupAfterConcurrentEdit[0],groupAfterAttach[0],"video/"+attached[0].id+"/");
                assertBodySameExceptUpdatedAt(groupAfterConcurrentEdit[0],groupAfterAttach[0]);
                assertDerivedProvenance(groupAfterConcurrentEdit[0],groupAfterAttach[0]);
                assertHistoricalProvenanceUnchanged(context,created.id,groupAfterConcurrentEdit[0]);
                assertFalse("older immutable version remains unchanged",groupBeforeAttach[0].memberSizes()
                        .containsKey("video/"+attached[0].id+"/content.bin"));
                assertFalse("the explicit retry does not rewrite its immediate parent revision",
                        new NoteGroupFacade(context).openGroupRevision(created.id,groupAfterConcurrentEdit[0].revision,
                                groupAfterConcurrentEdit[0].digest).memberSizes().containsKey("video/"+attached[0].id+"/content.bin"));
                assertTrue("intervening winner remains a complete immutable revision",
                        !groupAfterConcurrentEdit[0].revision.equals(groupBeforeAttach[0].revision));
            }else{
                awaitDialogText("关联已校验视频");
                onView(withText("下载并关联")).inRoot(RootMatchers.isDialog()).check(matches(isDisplayed()));
                emitStage("legacy_confirmation_shown");
                assertEquals("legacy artifact is not downloaded before explicit confirmation",0,downloadCount.get());
                onView(withText("下载并关联")).inRoot(RootMatchers.isDialog()).perform(click());
                emitStage("legacy_confirmation_clicked");
                awaitAttachmentOrFailure(attachmentStore[0], created.id, downloadCount, attachFailure);
                emitStage("legacy_attach_published");
                attached[0] = attachmentStore[0].listForNote(created.id).get(0);
            }
            assertEquals(mp4Sha, attached[0].sha256);
            assertEquals("one streamed artifact download is expected", 1, downloadCount.get());
            File verifiedProjection=grouped
                    ?GroupAuthorityBridge.videoProjection(context,groupAfterAttach[0],attached[0])
                    :new File(new File(context.getFilesDir(), "video-attachments"),attached[0].storedName);
            assertEquals("verified MP4 bytes are stored under the winning authority",mp4.length,verifiedProjection.length());

            if (grouped) {
                // Cold reopen already selected this persisted note from the real bookshelf and
                // restored its editor. The task-detail dialog was destroyed by recreation, so
                // continue from the visible editor toolbar instead of invoking a dialog-only action.
                onView(withContentDescription("当前笔记的视频任务与成果")).check(matches(isDisplayed()));
            } else {
                // Legacy attachment still returns to its task detail dialog; preserve its real
                // source-note navigation path.
                onView(withText("打开来源手写笔记")).inRoot(RootMatchers.isDialog())
                        .perform(scrollTo(), click());
            }
            onView(withContentDescription("当前笔记的视频任务与成果")).perform(click());
            onView(withText("已关联视频")).inRoot(RootMatchers.isDialog()).perform(click());
            saveCheckpointScreenshot(context,"03-offline-attachment-list.png");
            onView(withText(Matchers.startsWith("roundtrip.mp4"))).inRoot(RootMatchers.isDialog()).perform(click());
            onView(withText("离线播放")).inRoot(RootMatchers.isDialog()).perform(click());
            VideoView playing=awaitPlayableVideo();
            saveCheckpointScreenshot(context,"04-offline-video-player.png");
            assertEquals("playback must use the app-private local file, without another download",
                    1, downloadCount.get());
            assertTrue("verified offline video should start playback",playing.isPlaying());

            onView(withText("关闭")).inRoot(RootMatchers.isDialog()).perform(click());
            assertTrue("closing the player must stop playback",!playing.isPlaying());
            AtomicReference<Runnable> delayedStart=new AtomicReference<>();
            activeScenario.onActivity(activity->{
                activity.setVideoPreparedStartForTest(delayedStart::set);
                invokePlayAttached(activity,selectionFor(activity,created.id,attached[0]));
            });
            waitUntil(()->delayedStart.get()!=null);
            VideoView latePlayer=awaitPlayableVideo();
            assertTrue("test gate holds a prepared player before it starts",!latePlayer.isPlaying());
            onView(withText("关闭")).inRoot(RootMatchers.isDialog()).perform(click());
            activeScenario.onActivity(activity->delayedStart.get().run());
            Thread.sleep(500L);
            assertTrue("a prepared callback released after dismissal must not restart playback",
                    !latePlayer.isPlaying());
            activeScenario.onActivity(activity->activity.setVideoPreparedStartForTest(null));

            // Corruption after a previously valid offline open must fail closed and remain visible.
            File stored=verifiedProjection;
            try (FileOutputStream output = new FileOutputStream(stored, false)) {
                output.write(new byte[]{0, 1, 2, 3}); output.getFD().sync();
            }
            activeScenario.onActivity(activity -> invokePlayAttached(activity,selectionFor(activity,created.id,attached[0])));
            waitUntil(() -> attachFailure.get()!=null);
            if(grouped){
                assertEquals("corrupt projection must fail closed without touching the group object",
                        "GROUP_PROJECTION_FILE_UNSAFE",attachFailure.get().getMessage());
                StreamingGroupStore.Snapshot stillAttached=new NoteGroupFacade(context).openGroup(created.id);
                assertEquals("a bad read projection never detaches immutable group media",1,
                        GroupAuthorityBridge.videoViews(stillAttached).size());
                assertArrayEquals("the captured winning object remains exact",mp4,
                        stillAttached.readSmall("video/"+attached[0].id+"/content.bin",(int)StreamingGroupStore.VIDEO_MAX));
                // Remove through the actual note video list and confirmation. The old full
                // revision remains readable after the current revision drops its three members.
                onView(withContentDescription("当前笔记的视频任务与成果")).perform(click());
                onView(withText("已关联视频")).inRoot(RootMatchers.isDialog()).perform(click());
                onView(withText(Matchers.startsWith("roundtrip.mp4"))).inRoot(RootMatchers.isDialog()).perform(click());
                onView(withText("移除本笔记关联")).inRoot(RootMatchers.isDialog()).perform(click());
                onView(withText("移除")).inRoot(RootMatchers.isDialog()).perform(click());
                waitUntil(()->GroupAuthorityBridge.videoViews(new NoteGroupFacade(context).openGroup(created.id)).isEmpty());
                StreamingGroupStore.Snapshot removed=new NoteGroupFacade(context).openGroup(created.id);
                assertFalse(removed.memberSizes().containsKey("video/"+attached[0].id+"/content.bin"));
                assertMembersPreservedExceptBodyAndProvenance(groupAfterAttach[0],removed,"video/"+attached[0].id+"/");
                assertMemberKeysRemoved(groupAfterAttach[0],removed,"video/"+attached[0].id+"/");
                assertBodySameExceptUpdatedAt(groupAfterAttach[0],removed);
                assertDerivedProvenance(groupAfterAttach[0],removed);
                assertHistoricalProvenanceUnchanged(context,created.id,groupAfterAttach[0]);
                StreamingGroupStore.Snapshot prior=new NoteGroupFacade(context).openGroupRevision(created.id,
                        groupAfterAttach[0].revision,groupAfterAttach[0].digest);
                assertArrayEquals("remove keeps the exact prior video bytes in history",mp4,
                        prior.readSmall("video/"+attached[0].id+"/content.bin",(int)StreamingGroupStore.VIDEO_MAX));
            }else{
                assertTrue("corrupt offline attachment should reach the user warning path",
                        attachFailure.get().getMessage().contains("大小改变"));
                assertEquals("a corrupt file is not silently detached", 1,
                        attachmentStore[0].listForNote(created.id).size());
            }
        } finally {
            if (scenario != null) scenario.close();
            if (attached[0] != null && attachmentStore[0] != null) {
                try { attachmentStore[0].remove(attached[0].id); } catch (Exception ignored) { }
            }
            if (!submittedClientId[0].isEmpty()) {
                File task = new File(new File(context.getFilesDir(), "agent-tasks"),
                        submittedClientId[0] + ".json");
                task.delete();
            }
            for (VaultStore.VaultNote note : vault.list()) {
                if (created.id.equals(note.noteId)) {
                    try{vault.delete(note.fileName);}catch(Exception blocked){if(!grouped)throw blocked;}
                }
            }
            if(grouped){
                try{StreamingGroupStore.Snapshot current=new NoteGroupFacade(context).openGroup(created.id);
                    if(current!=null)new NoteGroupFacade(context).retireGroup(created.id,current.revision,current.digest);
                }catch(Exception ignored){}
            }else NoteStore.delete(context, created.id);
            connections.delete(draft.id);
        }
    }

    private static VideoAttachmentStore.Attachment fixtureAttachment(String noteId,String materialId,
            String name,byte[] bytes,String sha,long sourceRevision){
        return new VideoAttachmentStore.Attachment(materialId,noteId,Math.max(1,sourceRevision),sha,sha,
                1,"HERMES","BRIDGE","","r66b-task-"+materialId,"r66b-remote-"+materialId,
                "r66b-connection","r66b-bridge","r66b-instance","artifact-"+materialId,name,"video/mp4",
                bytes.length,sha,"video-"+materialId+".mp4",System.currentTimeMillis(),"computer_task",
                "linked_note",noteId,1,"source_and_task_payload","verified_local_copy");
    }

    private static byte[] readFile(File file)throws Exception {
        try(InputStream input=new java.io.FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[1024];int count;while((count=input.read(buffer))>=0)if(count>0)output.write(buffer,0,count);
            return output.toByteArray();
        }
    }

    private static PendingVideoArtifactStore.Entry completeFixture(PendingVideoArtifactStore store,
            String noteId,String name,byte[] payload,String sha,File input)throws Exception{
        VideoAttachmentStore.Attachment attachment=fixtureAttachment(noteId,java.util.UUID.randomUUID().toString(),
                name,payload,sha,1);
        return completeFixture(store,noteId,name,payload,sha,input,attachment,null);
    }

    private static PendingVideoArtifactStore.Entry completeFixture(PendingVideoArtifactStore store,
            String noteId,String name,byte[] payload,String sha,File input,
            VideoAttachmentStore.Attachment attachment,StreamingGroupStore.Snapshot base)throws Exception{
        PendingVideoArtifactStore.Entry reservation=store.reserve(attachment,
                base==null?null:base.lineage,base==null?null:base.revision,base==null?null:base.digest);
        File directory=store.downloadDirectory(reservation);
        File partial=new File(directory,"agent-artifact-fixture-"+attachment.id+".part");
        try(InputStream in=new java.io.FileInputStream(input);FileOutputStream out=new FileOutputStream(partial)){
            byte[] buffer=new byte[4096];int count;while((count=in.read(buffer))!=-1)if(count>0)out.write(buffer,0,count);
            out.flush();out.getFD().sync();
        }
        return store.complete(reservation,partial);
    }

    private static boolean pendingId(PendingVideoArtifactStore.Entry entry,String id){
        return entry!=null&&id.equals(entry.operationId);
    }

    private static String shaFile(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new java.io.FileInputStream(file)){
            byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)if(count>0)digest.update(buffer,0,count);
        }
        return hex(digest.digest());
    }

    private static void startSendToComputer() throws Exception {
        onView(withText("生成讲解视频")).inRoot(RootMatchers.isDialog()).perform(click());
        onView(withText("发送到电脑")).inRoot(RootMatchers.isDialog()).perform(click());
    }

    private static void saveCheckpointScreenshot(Context context,String name) throws Exception {
        File directory=context.getExternalFilesDir("roundtrip-evidence");
        if(directory==null||(!directory.isDirectory()&&!directory.mkdirs()))
            throw new AssertionError("cannot create synthetic screenshot directory");
        Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        if(screenshot==null)throw new AssertionError("cannot capture synthetic UI checkpoint");
        File destination=new File(directory,name);
        try(FileOutputStream output=new FileOutputStream(destination,false)){
            if(!screenshot.compress(Bitmap.CompressFormat.PNG,100,output))
                throw new AssertionError("cannot save synthetic UI checkpoint");
            output.flush();output.getFD().sync();
        }finally{screenshot.recycle();}
    }

    private static void updateSource(Context context, VaultStore vault, String noteId,
                                     String title, String material) throws Exception {
        NoteStore.Entry current = findNote(context, noteId);
        JSONObject document = NoteStore.load(context, noteId);
        while (System.currentTimeMillis() <= current.updatedAt) Thread.sleep(2L);
        NoteStore.Entry changed = NoteStore.save(context, noteId, title, document.toString());
        vault.write(noteId, title, 1, changed.updatedAt, Collections.singletonList(material));
    }

    private static NoteStore.Entry findNote(Context context, String id) throws Exception {
        for (NoteStore.Entry entry : NoteStore.list(context)) if (entry.id.equals(id)) return entry;
        throw new AssertionError("fixture note disappeared");
    }

    private static boolean containsJson(JSONObject document, String text) {
        return document.toString().contains(text);
    }

    private static void invokeOpenNote(MainActivity activity, NoteStore.Entry entry) {
        try {
            java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod("openNote", NoteStore.Entry.class);
            method.setAccessible(true); method.invoke(activity, entry);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static NoteCanvasView getCanvas(MainActivity activity) {
        try {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("canvasView");
            field.setAccessible(true); return (NoteCanvasView) field.get(activity);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static VideoAttachmentStore getVideoAttachmentStore(Context context) {
        return new VideoAttachmentStore(context);
    }

    private static MainActivity.VideoActionSelection selectionFor(MainActivity activity,String noteId,
            VideoAttachmentStore.Attachment attachment) {
        try {
            java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod("captureVideoActionBase",String.class,String.class);
            method.setAccessible(true);MainActivity.VideoActionBase base=(MainActivity.VideoActionBase)method.invoke(activity,noteId,NOTE_TITLE);
            return new MainActivity.VideoActionSelection(base,attachment);
        }catch(Exception error){throw new AssertionError(error);}
    }

    private static void invokePlayAttached(MainActivity activity,
                                           MainActivity.VideoActionSelection selection) {
        try {
            java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod(
                    "playAttachedVideo", MainActivity.VideoActionSelection.class);
            method.setAccessible(true); method.invoke(activity, selection);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static void awaitShelfNote(String title) throws Exception {
        waitUntil(() -> {
            try {
                onView(withText(title)).check(matches(isDisplayed()));
                return true;
            } catch (Exception | AssertionError ignored) { return false; }
        });
    }

    private static void emitStage(String stage) {
        // Fixed test-only vocabulary; no IDs, body text, paths, or arbitrary exception text.
        switch (stage) {
            case "pending_retry_waiting_for_shelf": case "pending_retry_shelf_ready":
            case "pending_retry_results_open": case "pending_retry_source_selected":
            case "pending_retry_confirmation_clicked": case "pending_retry_reconciled":
            case "grouped_activity_launched": case "legacy_activity_launched":
            case "grouped_editor_ready": case "legacy_editor_ready":
            case "grouped_hub_open": case "legacy_hub_open":
            case "grouped_artifact_selected": case "legacy_artifact_selected":
            case "grouped_confirmation_shown": case "grouped_confirmation_clicked":
            case "grouped_stale_cas_rejected": case "grouped_explicit_retry_clicked":
            case "grouped_attach_published": case "legacy_confirmation_shown":
            case "legacy_confirmation_clicked": case "legacy_attach_published":
                android.util.Log.i(STAGE_TAG, stage);
                return;
            default: throw new AssertionError("unregistered fixed video test stage");
        }
    }

    private static void waitForRestore(ActivityScenario<MainActivity> scenario) throws Exception {
        waitUntil(() -> {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Field field = MainActivity.class.getDeclaredField("restoreCompleted");
                    field.setAccessible(true); ready.set(field.getBoolean(activity));
                } catch (Exception error) { throw new AssertionError(error); }
            });
            return ready.get();
        });
    }

    private static void awaitDialogText(String text) throws Exception {
        waitUntil(() -> {
            try {
                onView(withText(Matchers.containsString(text))).inRoot(RootMatchers.isDialog())
                        .check(matches(isDisplayed()));
                return true;
            } catch (Exception | AssertionError ignored) { return false; }
        });
    }

    private static void awaitViewTextPrefix(String text) throws Exception {
        waitUntil(() -> {
            try {
                onView(withText(Matchers.startsWith(text))).inRoot(RootMatchers.isDialog())
                        .perform(scrollTo()).check(matches(isDisplayed()));
                return true;
            } catch (Exception | AssertionError ignored) { return false; }
        });
    }

    private static VideoView awaitPlayableVideo() throws Exception {
        AtomicReference<VideoView> found=new AtomicReference<>();
        waitUntil(() -> {
            AtomicReference<Boolean> playable = new AtomicReference<>(false);
            try {
                onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(VideoView.class))
                        .inRoot(RootMatchers.isDialog()).check((view, noView) -> {
                            VideoView video = (VideoView) view;
                            found.set(video);
                            playable.set(video.getDuration() > 0 || video.isPlaying());
                        });
            } catch (Exception | AssertionError ignored) { }
            return playable.get();
        });
        return found.get();
    }

    private static void awaitAttachmentOrFailure(VideoAttachmentStore store, String noteId,
                                                 AtomicInteger downloads,
                                                 AtomicReference<Exception> attachFailure) throws Exception {
        long deadline = System.currentTimeMillis() + 15000L;
        while (System.currentTimeMillis() < deadline) {
            try { if (store.listForNote(noteId).size() == 1) return; }
            catch (Exception ignored) { }
            if (attachFailure.get()!=null) break;
            Thread.sleep(50L);
        }
        Exception captured=attachFailure.get();
        String detail=captured==null?"":captured.getClass().getSimpleName()+": "+captured.getMessage();
        assertTrue("attachment did not complete; downloads=" + downloads.get() +
                (detail.isEmpty()?"":"; exception="+detail), false);
    }

    private static StreamingGroupStore.Snapshot awaitGroupVideo(Context context,String noteId,
            AtomicInteger downloads,AtomicReference<Exception> failure)throws Exception {
        long deadline=System.currentTimeMillis()+15000L;Throwable last=null;
        while(System.currentTimeMillis()<deadline){
            try{
                StreamingGroupStore.Snapshot snapshot=new NoteGroupFacade(context).openGroup(noteId);
                if(snapshot!=null&&!GroupAuthorityBridge.videoViews(snapshot).isEmpty())return snapshot;
            }catch(Throwable error){last=error;}
            if(failure.get()!=null)break;Thread.sleep(50L);
        }
        throw new AssertionError("group video did not commit; downloads="+downloads.get()+
                (last==null?"":"; read="+last.getClass().getSimpleName()));
    }

    private static void assertMembersPreservedExceptBodyAndProvenance(StreamingGroupStore.Snapshot before,
            StreamingGroupStore.Snapshot after)throws Exception {
        assertMembersPreservedExceptBodyAndProvenance(before,after,null);
    }
    private static void assertMembersPreservedExceptBodyAndProvenance(StreamingGroupStore.Snapshot before,
            StreamingGroupStore.Snapshot after,String removedPrefix)throws Exception {
        for(String member:before.memberSizes().keySet()){
            if("body.bin".equals(member)||"source-provenance.bin".equals(member)
                    ||removedPrefix!=null&&member.startsWith(removedPrefix))continue;
            assertTrue("whole-group mutation retains member "+member,after.memberSizes().containsKey(member));
            assertEquals("whole-group mutation retains member size "+member,
                    before.memberSizes().get(member),after.memberSizes().get(member));
            assertEquals("whole-group mutation retains member SHA-256 "+member,
                    before.memberSha256(member),after.memberSha256(member));
        }
    }

    private static void assertSnapshotMembersExact(StreamingGroupStore.Snapshot expected,
            StreamingGroupStore.Snapshot actual)throws Exception {
        assertEquals(expected.memberSizes(),actual.memberSizes());
        for(String member:expected.memberSizes().keySet())
            assertEquals("failed operation leaves current member exact: "+member,
                    expected.memberSha256(member),actual.memberSha256(member));
        assertArrayEquals("failed operation leaves current provenance exact",
                expected.readSmall("source-provenance.bin",1<<20),actual.readSmall("source-provenance.bin",1<<20));
    }
    private static void assertMemberKeysSame(StreamingGroupStore.Snapshot before,
            StreamingGroupStore.Snapshot after)throws Exception {
        assertEquals("edit and stale attach retain the exact complete member set",before.memberSizes().keySet(),after.memberSizes().keySet());
    }
    private static void assertMemberKeysAdded(StreamingGroupStore.Snapshot before,
            StreamingGroupStore.Snapshot after,String addedPrefix)throws Exception {
        java.util.Set<String> expected=new java.util.HashSet<>(before.memberSizes().keySet());
        expected.add(addedPrefix+"content.bin");expected.add(addedPrefix+"media-type.txt");expected.add(addedPrefix+"metadata.bin");
        assertEquals("attach adds exactly the three members for its one video",expected,after.memberSizes().keySet());
    }
    private static void assertMemberKeysRemoved(StreamingGroupStore.Snapshot before,
            StreamingGroupStore.Snapshot after,String removedPrefix)throws Exception {
        java.util.Set<String> expected=new java.util.HashSet<>(before.memberSizes().keySet());
        assertTrue(expected.remove(removedPrefix+"content.bin"));assertTrue(expected.remove(removedPrefix+"media-type.txt"));
        assertTrue(expected.remove(removedPrefix+"metadata.bin"));
        assertEquals("remove removes exactly the selected video's three members",expected,after.memberSizes().keySet());
    }

    private static void assertBodySameExceptUpdatedAt(StreamingGroupStore.Snapshot before,
            StreamingGroupStore.Snapshot after)throws Exception {
        JSONObject prior=NotePrecisionJsonParser.parseObject(new String(before.readSmall("body.bin",
                (int)StreamingGroupStore.BODY_MAX),StandardCharsets.UTF_8));
        JSONObject current=NotePrecisionJsonParser.parseObject(new String(after.readSmall("body.bin",
                (int)StreamingGroupStore.BODY_MAX),StandardCharsets.UTF_8));
        assertNoteJsonEquals("the editor/video mutation preserves every body field except its monotonic save time",
                withoutUpdatedAt(prior),withoutUpdatedAt(current));
    }

    /** Current provenance is rebuilt for each revision; complete group verification checks every material witness. */
    private static void assertDerivedProvenance(StreamingGroupStore.Snapshot base,
            StreamingGroupStore.Snapshot current)throws Exception {
        byte[] oldBytes=base.readSmall("source-provenance.bin",1<<20);
        byte[] newBytes=current.readSmall("source-provenance.bin",1<<20);
        assertFalse("each whole-group mutation records its newly captured source revision",Arrays.equals(oldBytes,newBytes));
        byte[] domain="PadNote/UpdateSourceProvenance/v2\0".getBytes(StandardCharsets.US_ASCII);
        String destinationBodySha;
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(newBytes))){
            assertArrayEquals(domain,readExact(in,domain.length));
            assertEquals("NATIVE_ANDROID",readProvenanceText(in));
            assertEquals(base.lineage,readProvenanceText(in));
            assertEquals(base.localId,readProvenanceText(in));
            assertEquals("provenance binds the immediately captured parent",base.revision,readProvenanceText(in));
            String sourceBodySha=readProvenanceText(in);
            destinationBodySha=readProvenanceText(in);
            byte[] currentBody=current.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX);
            assertEquals("identity-mapped editor/video operation commits the exact body used as provenance source",
                    sha(currentBody),sourceBodySha);
            assertEquals("destination witness binds the committed canonical body",sha(currentBody),destinationBodySha);
            JSONObject afterBody=NotePrecisionJsonParser.parseObject(new String(currentBody,StandardCharsets.UTF_8));
            assertEquals(current.localId,afterBody.getString("id"));
            TypedNoteTimeProjection.Projection expectedTimes=TypedNoteTimeProjection.nativeAndroid(
                    new String(currentBody,StandardCharsets.UTF_8));
            int timeCount=in.readInt();assertEquals("all typed timestamp witnesses are retained",expectedTimes.times.size(),timeCount);
            for(int i=0;i<timeCount;i++){
                TypedNoteTimeProjection.Time expected=expectedTimes.times.get(i);
                assertEquals(expected.pointer,readProvenanceText(in));
                int tag=in.readUnsignedByte();assertEquals(expected.kind.ordinal(),tag);
                assertEquals(expected.kind==TypedNoteTimeProjection.Kind.I64?expected.integer:expected.doubleBits,in.readLong());
            }

            int mappingCount=in.readInt();assertTrue("bounded source-to-destination mapping",mappingCount>0&&mappingCount<=10000);
            java.util.Map<String,String> mapping=new java.util.TreeMap<>();java.util.Set<String> destinations=new java.util.HashSet<>();
            for(int i=0;i<mappingCount;i++){
                String source=readProvenanceText(in),destination=readProvenanceText(in);
                assertNull("source mapping has no duplicate key",mapping.put(source,destination));
                assertTrue("destination mapping is one-to-one",destinations.add(destination));
            }
            assertEquals(base.localId,mapping.get(base.localId));

            java.util.Map<String,Long> members=current.memberSizes();
            java.util.Set<String> expectedMaterials=new java.util.HashSet<>();
            for(String member:members.keySet())if((member.startsWith("video/")||member.startsWith("vault/"))&&member.endsWith("/content.bin"))
                expectedMaterials.add(member.substring(0,member.length()-"/content.bin".length()));
            int materialCount=in.readInt();assertEquals("provenance covers every current video and Vault material",expectedMaterials.size(),materialCount);
            java.util.Set<String> seenMaterials=new java.util.HashSet<>();
            for(int i=0;i<materialCount;i++){
                String role=readProvenanceText(in),sourceId=readProvenanceText(in),destinationId=readProvenanceText(in);
                String mediaType=readProvenanceText(in),platform=readProvenanceText(in),state=readProvenanceText(in);
                String sourceLineage=readNullableProvenanceText(in),ownerLineage=readNullableProvenanceText(in);
                long sourceSize=in.readLong();byte[] sourceSha=readExact(in,32);long destinationSize=in.readLong();byte[] destinationSha=readExact(in,32);
                readExact(in,32); // source metadata hash, verified by the production complete-group verifier.
                String linkedNote=readNullableProvenanceText(in);readExact(in,32); // destination descriptor witness.
                assertTrue(role.equals("VIDEO")||role.equals("VAULT"));
                assertTrue(platform.equals("android")||platform.equals("ios"));
                assertTrue("linked_note".equals(state)||"source_deleted".equals(state)||"source_not_selected".equals(state)||"independent".equals(state));
                assertTrue("source association remains bound to the group lineage and local note",NativeManualUpdatePlan.validSourceAssociation(
                        state,sourceLineage,ownerLineage,linkedNote,base.lineage,base.localId));
                assertEquals("material source maps to the published destination",destinationId,mapping.get(sourceId));
                String memberPrefix=role.equals("VIDEO")?"video/":"vault/";
                String materialKey=memberPrefix+destinationId;
                assertTrue("published material is represented by a complete-group member",expectedMaterials.contains(materialKey));
                assertTrue("material witness is unique",seenMaterials.add(materialKey));
                String mediaMember=materialKey+"/media-type.txt";
                assertEquals("material media type matches the committed member",mediaType,
                        new String(current.readSmall(mediaMember,256),StandardCharsets.UTF_8).replaceFirst("\n$",""));
                String contentMember=materialKey+"/content.bin";
                assertEquals("destination material size witness matches exact committed content",members.get(contentMember).longValue(),destinationSize);
                assertEquals("destination material hash witness matches exact committed content",current.memberSha256(contentMember),hex(destinationSha));
                assertTrue(sourceSize>=0);assertEquals(32,sourceSha.length);
            }
            assertEquals(expectedMaterials,seenMaterials);
            assertTrue("complete mapping includes every current media destination",destinations.containsAll(
                    expectedMaterials.stream().map(key->key.substring(key.indexOf('/')+1)).collect(java.util.stream.Collectors.toSet())));
            assertEquals("provenance v2 record has no unparsed suffix",-1,in.read());
        }
    }

    private static String readNullableProvenanceText(DataInputStream in)throws Exception {
        return in.readBoolean()?readProvenanceText(in):null;
    }
    private static JSONObject withoutUpdatedAt(JSONObject source)throws Exception {
        JSONObject copy=NotePrecisionJsonParser.parseObject(NoteJsonCodec.stringify(source));copy.remove("updatedAt");return copy;
    }
    private static void assertNoteJsonEquals(String message,Object expected,Object actual)throws Exception {
        assertTrue(message+" expected="+NoteJsonCodec.stringify((JSONObject)expected)+" actual="+NoteJsonCodec.stringify((JSONObject)actual),
                sameTypedJson(expected,actual));
    }
    private static boolean sameTypedJson(Object expected,Object actual)throws Exception {
        if(expected==JSONObject.NULL)return actual==JSONObject.NULL;
        if(expected instanceof JSONObject){
            if(!(actual instanceof JSONObject))return false;
            JSONObject left=(JSONObject)expected,right=(JSONObject)actual;
            java.util.Set<String> leftKeys=new java.util.HashSet<>(),rightKeys=new java.util.HashSet<>();
            java.util.Iterator<String> li=left.keys(),ri=right.keys();while(li.hasNext())leftKeys.add(li.next());while(ri.hasNext())rightKeys.add(ri.next());
            if(!leftKeys.equals(rightKeys))return false;
            for(String key:leftKeys)if(!sameTypedJson(left.get(key),right.get(key)))return false;
            return true;
        }
        if(expected instanceof JSONArray){
            if(!(actual instanceof JSONArray))return false;JSONArray left=(JSONArray)expected,right=(JSONArray)actual;
            if(left.length()!=right.length())return false;
            for(int i=0;i<left.length();i++)if(!sameTypedJson(left.get(i),right.get(i)))return false;
            return true;
        }
        if(expected instanceof Number){
            if(!(actual instanceof Number))return false;Number left=(Number)expected,right=(Number)actual;
            boolean leftFloat=left instanceof Double||left instanceof Float,rightFloat=right instanceof Double||right instanceof Float;
            if(leftFloat!=rightFloat)return false;
            return leftFloat?Double.doubleToRawLongBits(left.doubleValue())==Double.doubleToRawLongBits(right.doubleValue())
                    :left.longValue()==right.longValue();
        }
        return expected.getClass()==actual.getClass()&&expected.equals(actual);
    }
    private static String sha(byte[] bytes)throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void assertHistoricalProvenanceUnchanged(Context context,String noteId,
            StreamingGroupStore.Snapshot expected)throws Exception {
        StreamingGroupStore.Snapshot history=new NoteGroupFacade(context).openGroupRevision(
                noteId,expected.revision,expected.digest);
        assertNotNull("the parent full revision remains addressable in history",history);
        assertEquals(expected.memberSizes(),history.memberSizes());
        for(String member:expected.memberSizes().keySet())
            assertEquals("historical immutable member remains exact: "+member,
                    expected.memberSha256(member),history.memberSha256(member));
        assertArrayEquals("historical source provenance bytes remain exact",
                expected.readSmall("source-provenance.bin",1<<20),history.readSmall("source-provenance.bin",1<<20));
    }

    private static String readProvenanceText(DataInputStream in)throws Exception {
        int size=in.readInt();assertTrue("bounded provenance text field",size>=0&&size<=1<<20);
        return new String(readExact(in,size),StandardCharsets.UTF_8);
    }
    private static byte[] readExact(DataInputStream in,int count)throws Exception {
        byte[] value=new byte[count];in.readFully(value);return value;
    }

    private interface Check { boolean ok() throws Exception; }
    private static void waitUntil(Check check) throws Exception {
        long deadline = System.currentTimeMillis() + 15000L;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) return;
            Thread.sleep(50L);
        }
        assertTrue("condition did not become true within the UI test budget; visible UI: " +
                visibleUiSummary(), check.ok());
    }

    private static String visibleUiSummary() {
        try {
            UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
            StringBuilder out = new StringBuilder();
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                appendVisibleText(window.getRoot(), out, new int[]{0});
                if (out.length() >= 1800) break;
            }
            if (out.length() == 0) appendVisibleText(automation.getRootInActiveWindow(), out, new int[]{0});
            return out.toString();
        } catch (Exception error) { return "unavailable:" + error.getClass().getSimpleName(); }
    }

    private static void appendVisibleText(AccessibilityNodeInfo node, StringBuilder out, int[] count) {
        if (node == null || count[0]++ >= 100 || out.length() >= 1800) return;
        CharSequence text = node.getText();
        if (text != null && !text.toString().trim().isEmpty()) out.append(text).append(" | ");
        for (int i = 0; i < node.getChildCount(); i++) appendVisibleText(node.getChild(i), out, count);
    }

    private static AgentHttpTransport.Response response(AgentHttpTransport.Request request,
                                                          int status, String body) {
        return new AgentHttpTransport.Response(status, request.url, request.url,
                body.getBytes(StandardCharsets.UTF_8), Collections.emptyMap());
    }

    private static String bundleMarkdown(byte[] requestBody) throws Exception {
        assertNotNull("task request must be captured", requestBody);
        JSONObject payload = new JSONObject(new String(requestBody, StandardCharsets.UTF_8));
        byte[] zip = android.util.Base64.decode(payload.getString("bundle_base64"), android.util.Base64.DEFAULT);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String actual = hex(digest.digest(zip));
        assertEquals(payload.getString("bundle_sha256"), actual);
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!"input/content.md".equals(entry.getName())) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("frozen task bundle has no markdown entry");
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }
}
