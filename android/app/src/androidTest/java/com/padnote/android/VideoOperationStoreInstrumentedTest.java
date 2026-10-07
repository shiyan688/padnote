package com.padnote.android;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class VideoOperationStoreInstrumentedTest {
    @Test public void persistsSameKeyAndUnknownBlocksNewActionAcrossReopen() throws Exception {
        AgentTaskStore.Task task=task();File dir=directory();VideoOperationStore first=new VideoOperationStore(dir);
        VideoOperationStore.Operation reserved=first.reserve(task,"initialize",new JSONObject());
        assertEquals(reserved.key,reserved.clientOperationId);
        first.update(task,reserved.clientOperationId,"","submission_uncertain","",null,true);
        VideoOperationStore reopened=new VideoOperationStore(dir);VideoOperationStore.Operation restored=reopened.latest(task);
        assertEquals("submission_uncertain",restored.status);assertEquals(reserved.key,restored.key);
        assertEquals(reserved.clientOperationId,restored.clientOperationId);
        reopened.update(task,restored.clientOperationId,"18ed9804-fbdb-4a3a-96f9-05d56e4b4e52","unknown","worker_unknown",null,false);
        VideoOperationStore afterUnknown=new VideoOperationStore(dir);
        assertEquals("unknown",afterUnknown.latest(task).status);
        try{afterUnknown.reserve(task,"storyboard",new JSONObject().put("revision",1).put("event_cursor",1));fail();}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("尚未确认"));}
        assertEquals(reserved.key,afterUnknown.latest(task).key);
    }

    @Test public void unknownCanOnlyAdvanceToConfirmedSuccessAndRejectsStaleResponse() throws Exception {
        AgentTaskStore.Task task=task();File dir=directory();VideoOperationStore store=new VideoOperationStore(dir);
        VideoOperationStore.Operation intent=store.reserve(task,"initialize",new JSONObject());
        VideoOperationStore.Operation unknown=store.updateIfCurrent(task,intent,
                "18ed9804-fbdb-4a3a-96f9-05d56e4b4e52","unknown","worker_interrupted",null,false);
        assertEquals("unknown",unknown.status);
        VideoOperationStore reopened=new VideoOperationStore(dir);
        VideoOperationStore.Operation persisted=reopened.latest(task);
        for(String forbidden:new String[]{"queued","running","failed","cancelled"}){
            try{reopened.updateIfCurrent(task,persisted,persisted.operationId,forbidden,"worker_failed",null,false);fail("unknown cannot become "+forbidden);}
            catch(IllegalStateException expected){}
        }
        assertEquals("unknown",reopened.latest(task).status);
        JSONObject result=new JSONObject().put("protocol_version",1).put("task_id",task.remoteTaskId)
                .put("status","initialized").put("phase","idle").put("event_cursor",1);
        VideoOperationStore.Operation success=reopened.updateIfCurrent(task,persisted,persisted.operationId,
                "succeeded","",result,false);
        assertEquals("succeeded",success.status);
        assertNull("late unknown response must lose the snapshot CAS",reopened.updateIfCurrent(task,persisted,
                persisted.operationId,"unknown","worker_interrupted",null,false));
        VideoOperationStore.Operation finalRecord=new VideoOperationStore(dir).latest(task);
        assertEquals("succeeded",finalRecord.status);assertEquals(1,finalRecord.result.getInt("event_cursor"));
    }

    @Test public void sameKeyRetryMayReturnAlreadyFinishedOrUnknownOperation() throws Exception {
        for(String responseStatus:new String[]{"succeeded","unknown"}){
            AgentTaskStore.Task task=task();File dir=directory();VideoOperationStore store=new VideoOperationStore(dir);
            VideoOperationStore.Operation prepared=store.reserve(task,"initialize",new JSONObject());
            VideoOperationStore.Operation uncertain=store.updateIfCurrent(task,prepared,"","submission_uncertain","",null,true);
            JSONObject result="succeeded".equals(responseStatus)?new JSONObject().put("protocol_version",1)
                    .put("task_id",task.remoteTaskId).put("status","initialized").put("phase","idle").put("event_cursor",1):null;
            VideoOperationStore.Operation recovered=store.updateIfCurrent(task,uncertain,
                    "18ed9804-fbdb-4a3a-96f9-05d56e4b4e52",responseStatus,
                    "unknown".equals(responseStatus)?"worker_unknown":"",result,false);
            assertEquals("same-key response may settle from uncertain",responseStatus,recovered.status);
            assertEquals(prepared.key,new VideoOperationStore(dir).latest(task).key);
        }
    }

    @Test public void runningStoryboardCancelIntentSurvivesReopenAndOnlyVerifiedReceiptTerminatesIt() throws Exception {
        AgentTaskStore.Task task=task();File dir=directory();VideoOperationStore first=new VideoOperationStore(dir);
        VideoOperationStore.Operation prepared=first.reserve(task,"storyboard",new JSONObject().put("revision",1).put("event_cursor",1));
        VideoOperationStore.Operation running=first.updateIfCurrent(task,prepared,"18ed9804-fbdb-4a3a-96f9-05d56e4b4e52","running","",null,false);
        VideoOperationStore.Operation requested=first.prepareRunningCancel(task,running);
        assertEquals("pending",requested.cancelControl.getString("status"));
        VideoOperationStore reopened=new VideoOperationStore(dir);VideoOperationStore.Operation saved=reopened.latest(task);
        assertEquals("running",saved.status);assertEquals("pending",saved.cancelControl.getString("status"));
        JSONObject unconfirmed=new JSONObject().put("object","padnote.video.cancel_request").put("protocol_version",1)
                .put("operation_id",saved.operationId).put("task_id",task.remoteTaskId).put("status","unconfirmed").put("requested_at",10).put("updated_at",11);
        VideoOperationStore.Operation uncertain=reopened.applyCancelControl(task,saved,unconfirmed);
        assertEquals("running",uncertain.status);assertEquals("unconfirmed",uncertain.cancelControl.getString("status"));
        assertNull("stale running response cannot overwrite stop update",reopened.updateIfCurrent(task,saved,saved.operationId,"running","",null,false));
        VideoOperationStore again=new VideoOperationStore(dir);VideoOperationStore.Operation current=again.latest(task);
        JSONObject verified=new JSONObject(unconfirmed.toString()).put("status","verified_cancelled").put("updated_at",unconfirmed.getLong("updated_at")+1);
        VideoOperationStore.Operation cancelled=again.applyCancelControl(task,current,verified);
        assertEquals("cancelled",cancelled.status);assertEquals("verified_cancelled",cancelled.cancelControl.getString("status"));
        assertTrue("local intent uses milliseconds",cancelled.cancelControl.getLong("intent_at_ms")>1_000_000_000_000L);
        assertTrue("server timestamps remain seconds",cancelled.cancelControl.getLong("updated_at")<1_000_000_000_000L);
        VideoOperationStore cold=new VideoOperationStore(dir);assertEquals("cancelled",cold.latest(task).status);
        try{cold.reserve(task,"storyboard",new JSONObject().put("revision",2).put("event_cursor",5));fail("verified stop must prevent starting a new storyboard in this task");}
        catch(IllegalStateException expected){}
        VideoOperationStore.Operation coldCancelled=cold.latest(task);
        JSONObject repeatedGet=new JSONObject().put("object","padnote.video.cancel_request").put("protocol_version",1)
                .put("operation_id",coldCancelled.operationId).put("task_id",task.remoteTaskId).put("status","verified_cancelled").put("requested_at",10).put("updated_at",12);
        VideoOperationStore.Operation duplicate=cold.applyCancelControl(task,coldCancelled,repeatedGet);
        assertEquals("repeated GET after verified cancellation is idempotent",coldCancelled.cancelControl.toString(),duplicate.cancelControl.toString());
        assertEquals(coldCancelled.updatedAt,duplicate.updatedAt);
        try{cold.updateIfCurrent(task,cold.latest(task),cancelled.operationId,"unknown","worker_unknown",null,false);fail("cancelled cannot regress");}
        catch(IllegalStateException expected){}
    }

    @Test public void onlyRunningStoryboardCanPersistAStopIntent() throws Exception {
        AgentTaskStore.Task task=task();VideoOperationStore store=new VideoOperationStore(directory());
        VideoOperationStore.Operation initialize=store.reserve(task,"initialize",new JSONObject());
        VideoOperationStore.Operation running=store.updateIfCurrent(task,initialize,"18ed9804-fbdb-4a3a-96f9-05d56e4b4e52","running","",null,false);
        try{store.prepareRunningCancel(task,running);fail("initialize cannot be stopped");}catch(IllegalStateException expected){}
        try{store.prepareRunningCancel(task,new VideoOperationStore.Operation("storyboard",running.key,running.clientOperationId,running.operationId,
                "queued","",new JSONObject().put("revision",1).put("event_cursor",1),null,false,running.createdAt,running.updatedAt));fail("queued cannot use running stop");}
        catch(IllegalStateException expected){}

        AgentTaskStore.Task queuedTask=task();VideoOperationStore queuedStore=new VideoOperationStore(directory());
        VideoOperationStore.Operation queued=queuedStore.reserve(queuedTask,"initialize",new JSONObject());
        VideoOperationStore.Operation cancelled=queuedStore.updateIfCurrent(queuedTask,queued,"18ed9804-fbdb-4a3a-96f9-05d56e4b4e52","cancelled","cancelled",null,false);
        assertNull("ordinary queued cancellation has no running-stop control",cancelled.cancelControl);
        assertNotNull("ordinary queued cancellation must retain the existing storyboard path",
                queuedStore.reserve(queuedTask,"storyboard",new JSONObject().put("revision",1).put("event_cursor",1)));
    }

    @Test public void unknownPendingStopCanBeConfirmedAndReconciledWithoutRemoteControlTimes() throws Exception {
        AgentTaskStore.Task task=task();File dir=directory();VideoOperationStore store=new VideoOperationStore(dir);
        VideoOperationStore.Operation prepared=store.reserve(task,"storyboard",new JSONObject().put("revision",1).put("event_cursor",1));
        VideoOperationStore.Operation running=store.updateIfCurrent(task,prepared,"18ed9804-fbdb-4a3a-96f9-05d56e4b4e52","running","",null,false);
        VideoOperationStore.Operation pending=store.prepareRunningCancel(task,running);
        VideoOperationStore.Operation unknown=store.updateIfCurrent(task,pending,pending.operationId,"unknown","worker_unknown",null,false);
        JSONObject verified=new JSONObject().put("object","padnote.video.cancel_request").put("protocol_version",1)
                .put("operation_id",unknown.operationId).put("task_id",task.remoteTaskId).put("status","verified_cancelled")
                .put("requested_at",100).put("updated_at",101);
        VideoOperationStore.Operation cancelled=store.applyCancelControl(task,unknown,verified);
        assertEquals("unknown",unknown.status);assertNotNull("unknown operation should accept its verified stop response",cancelled);assertEquals("cancelled",cancelled.status);
        assertTrue(cancelled.cancelControl.has("requested_at"));assertFalse(cancelled.cancelControl.has("confirmed_at_ms"));

        File secondDir=directory();VideoOperationStore second=new VideoOperationStore(secondDir);
        VideoOperationStore.Operation p=second.reserve(task,"storyboard",new JSONObject().put("revision",1).put("event_cursor",1));
        VideoOperationStore.Operation r=second.updateIfCurrent(task,p,"18ed9804-fbdb-4a3a-96f9-05d56e4b4e52","running","",null,false);
        VideoOperationStore.Operation intent=second.prepareRunningCancel(task,r);
        VideoOperationStore.Operation indeterminate=second.updateIfCurrent(task,intent,intent.operationId,"unknown","worker_unknown",null,false);
        VideoOperationStore.Operation reconciled=second.updateReconciledIfCurrent(task,indeterminate,indeterminate.operationId,"cancelled","cancelled",null);
        assertNotNull("unknown operation should accept reconciliation proof",reconciled);assertEquals("cancelled",reconciled.status);assertEquals("verified_cancelled",reconciled.cancelControl.getString("status"));
        assertFalse("reconcile receipt carries no remote control timestamps",reconciled.cancelControl.has("requested_at"));
        assertTrue(reconciled.cancelControl.getLong("confirmed_at_ms")>=reconciled.cancelControl.getLong("intent_at_ms"));
        assertEquals("cancelled",new VideoOperationStore(secondDir).latest(task).status);
    }

    @Test public void staleRemoteStopTimestampCannotRegressSavedControl() throws Exception {
        AgentTaskStore.Task task=task();VideoOperationStore store=new VideoOperationStore(directory());
        VideoOperationStore.Operation prepared=store.reserve(task,"storyboard",new JSONObject().put("revision",1).put("event_cursor",1));
        VideoOperationStore.Operation running=store.updateIfCurrent(task,prepared,"18ed9804-fbdb-4a3a-96f9-05d56e4b4e52","running","",null,false);
        VideoOperationStore.Operation pending=store.prepareRunningCancel(task,running);
        JSONObject requested=new JSONObject().put("object","padnote.video.cancel_request").put("protocol_version",1)
                .put("operation_id",pending.operationId).put("task_id",task.remoteTaskId).put("status","requested").put("requested_at",100).put("updated_at",102);
        VideoOperationStore.Operation saved=store.applyCancelControl(task,pending,requested);
        JSONObject stale=new JSONObject(requested.toString()).put("updated_at",101);
        assertNull("older remote control projection must not be persisted",store.applyCancelControl(task,saved,stale));
        JSONObject changedIntent=new JSONObject(requested.toString()).put("requested_at",99).put("updated_at",103);
        assertNull("a later projection cannot change the original request time",store.applyCancelControl(task,saved,changedIntent));
        assertEquals(102,store.latest(task).cancelControl.getLong("updated_at"));
    }

    @Test public void persistedProduceConsentAndReceiptRequireBooleanTrue() throws Exception {
        assertStoredProduceConsent(Boolean.TRUE, Boolean.TRUE, true);
        for(Object malformed:new Object[]{"true",1,Boolean.FALSE}){
            assertStoredProduceConsent(malformed,Boolean.TRUE,false);
            assertStoredProduceConsent(Boolean.TRUE,malformed,false);
        }
    }

    private static void assertStoredProduceConsent(Object parameterConsent,Object receiptConsent,
                                                   boolean shouldLoad)throws Exception{
        AgentTaskStore.Task task=videoTask();File dir=directory();VideoOperationStore store=new VideoOperationStore(dir);
        store.reserve(task,"initialize",new JSONObject());File file=new File(dir,task.clientTaskId+".json");
        JSONObject root=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        JSONObject raw=root.getJSONArray("operations").getJSONObject(0);
        String operationId="18ed9804-fbdb-4a3a-96f9-05d56e4b4e52",reviewHash=repeat('b',64),lessonHash=repeat('c',64);
        JSONObject parameters=new JSONObject().put("revision",1).put("event_cursor",4)
                .put("review_sha256",reviewHash).put("lesson_ir_sha256",lessonHash)
                .put("allow_cloud_tts",parameterConsent);
        JSONObject receipt=new JSONObject().put("operation_id",operationId)
                .put("attempt_id","33333333-3333-4333-8333-333333333333").put("action","produce")
                .put("payload_digest",repeat('d',64)).put("source_snapshot_digest",repeat('e',64))
                .put("request_sha256",repeat('f',64)).put("input_event_cursor",4)
                .put("result_event_cursor",10).put("revision",1).put("review_sha256",reviewHash)
                .put("lesson_ir_sha256",lessonHash).put("allow_cloud_tts",receiptConsent);
        JSONObject result=new JSONObject().put("protocol_version",1).put("task_id",task.remoteTaskId)
                .put("status","completed").put("phase","completed").put("event_cursor",10)
                .put("revision",1).put("review_sha256",reviewHash).put("lesson_ir_sha256",lessonHash)
                .put("receipt",receipt);
        raw.put("action","produce").put("status","succeeded").put("operation_id",operationId)
                .put("error","").put("submission_uncertain",false).put("parameters",parameters)
                .put("result",result);
        java.nio.file.Files.write(file.toPath(),root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(shouldLoad){
            assertEquals("succeeded",new VideoOperationStore(dir).latest(task).status);
        }else{
            try{new VideoOperationStore(dir).latest(task);fail("stored consent must be a JSON boolean true");}
            catch(IllegalStateException expected){}
        }
    }

    @Test public void legacyOperationWithoutOptionalCancelControlStillLoads() throws Exception {
        AgentTaskStore.Task task=task();File dir=directory();VideoOperationStore store=new VideoOperationStore(dir);
        store.reserve(task,"initialize",new JSONObject());File file=new File(dir,task.clientTaskId+".json");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(java.io.FileInputStream input=new java.io.FileInputStream(file)){byte[] chunk=new byte[1024];int count;while((count=input.read(chunk))>=0)bytes.write(chunk,0,count);}
        JSONObject root=new JSONObject(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
        root.getJSONArray("operations").getJSONObject(0).remove("cancel_control");
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(file)){output.write(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        VideoOperationStore.Operation legacy=new VideoOperationStore(dir).latest(task);
        assertNull(legacy.cancelControl);assertEquals("prepared",legacy.status);
    }

    @Test public void sharedInstancesReserveOneDurableIntent() throws Exception {
        AgentTaskStore.Task task=task();File dir=directory();VideoOperationStore a=new VideoOperationStore(dir),b=new VideoOperationStore(dir);
        CountDownLatch start=new CountDownLatch(1),done=new CountDownLatch(2);AtomicReference<String> first=new AtomicReference<>(),second=new AtomicReference<>();
        Thread one=new Thread(()->reserve(a,task,start,done,first));Thread two=new Thread(()->reserve(b,task,start,done,second));one.start();two.start();start.countDown();
        assertTrue(done.await(5,TimeUnit.SECONDS));assertNotNull(first.get());assertEquals(first.get(),second.get());
        assertEquals(1,new VideoOperationStore(dir).operations(task).size());
    }

    @Test public void corruptOrReboundVideoRecordFailsClosed() throws Exception {
        AgentTaskStore.Task task=task();File dir=directory();VideoOperationStore store=new VideoOperationStore(dir);
        store.reserve(task,"initialize",new JSONObject());
        File file=new File(dir,task.clientTaskId+".json");
        AgentTaskStore.Task changed=new AgentTaskStore.Task(task.clientTaskId,task.connectionId,task.connectionRevision+1,
                task.origin,task.kind,task.transport,task.credentialRef,task.bridgeId,task.instanceId,task.title,
                task.connectionName,task.noteId,task.noteRevision,task.submissionJson,task.submissionSha256,task.remoteTaskId,
                task.status,task.output,task.error,task.approvalId,task.approvalTitle,task.approvalDescription,task.artifacts,
                task.createdAt,task.updatedAt);
        try{store.operations(changed);fail();}catch(IllegalStateException expected){}
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){out.write("{".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        try{new VideoOperationStore(dir).operations(task);fail();}catch(Exception expected){}
    }

    @Test public void approvalReserveCasRejectsReviewChangedByAnotherStoreInstance() throws Exception {
        AgentTaskStore.Task task=videoTask();File dir=directory();
        VideoOperationStore first=new VideoOperationStore(dir),second=new VideoOperationStore(dir);
        JSONObject oldReview=review(task,1,3,'b','c');first.saveReview(task,oldReview);
        JSONObject staleParams=approvalParameters(oldReview);
        JSONObject currentReview=review(task,2,5,'d','e');second.saveReview(task,currentReview);
        try{first.reserve(task,"approve",staleParams,oldReview);fail("stale approval must not be reserved");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("变化"));}
        assertTrue(first.operations(task).isEmpty());
        VideoOperationStore.Operation accepted=first.reserve(task,"approve",approvalParameters(currentReview),currentReview);
        assertEquals("prepared",accepted.status);assertEquals(2,accepted.parameters.getInt("revision"));
    }

    private static void reserve(VideoOperationStore store,AgentTaskStore.Task task,CountDownLatch start,
                                CountDownLatch done,AtomicReference<String> output){
        try{start.await();output.set(store.reserve(task,"initialize",new JSONObject()).key);}catch(Exception e){output.set("error:"+e.getMessage());}finally{done.countDown();}
    }
    private static File directory(){return new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"video-ops-"+System.nanoTime());}
    private static AgentTaskStore.Task task()throws Exception{
        AgentConnectionStore.Config c=new AgentConnectionStore.Config("video-connection","Bridge",AgentConnectionStore.Kind.HERMES,
                "https://bridge.test","synthetic-token","credential-video",AgentConnectionStore.Transport.BRIDGE,
                "bridge-video","instance-video",9,1,Arrays.asList("video_operations"));
        AgentTaskStore store=new AgentTaskStore(new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"video-tasks-"+System.nanoTime()));
        AgentTaskStore.Task created=store.create(c,"video","synthetic request","note-video",1,null);
        assertTrue(store.applySubmission(created.clientTaskId,c.id,c.revision,"remote-video",AgentTaskStore.Status.RUNNING));
        return store.get(created.clientTaskId);
    }
    private static AgentTaskStore.Task videoTask()throws Exception{
        AgentConnectionStore.Config c=new AgentConnectionStore.Config("video-connection-cas","Bridge",AgentConnectionStore.Kind.HERMES,
                "https://bridge.test","synthetic-token","credential-video-cas",AgentConnectionStore.Transport.BRIDGE,
                "bridge-cas","instance-cas",9,1,Arrays.asList("task_bundle","video_operations"));
        ByteArrayOutputStream bundle=new ByteArrayOutputStream();VideoTaskBundleIO.write(bundle,"cas-note",1,"video","material","audience","goal",60,"voice",1f);
        AgentTaskStore store=new AgentTaskStore(new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"video-cas-tasks-"+System.nanoTime()));
        AgentTaskStore.Task created=store.create(c,"video","video bundle","cas-note",1,bundle.toByteArray());
        assertTrue(store.applySubmission(created.clientTaskId,c.id,c.revision,"remote-video-cas",AgentTaskStore.Status.COMPLETED));
        return store.get(created.clientTaskId);
    }
    private static JSONObject approvalParameters(JSONObject review)throws Exception{return new JSONObject().put("revision",review.getInt("revision")).put("event_cursor",review.getInt("event_cursor")).put("review_sha256",review.getString("review_sha256")).put("lesson_ir_sha256",review.getString("lesson_ir_sha256"));}
    private static JSONObject review(AgentTaskStore.Task task,int revision,int cursor,char reviewChar,char irChar)throws Exception{
        String reviewHash=repeat(reviewChar,64),irHash=repeat(irChar,64);JSONObject scene=new JSONObject().put("id","scene-cas")
                .put("learning_objective","goal").put("narration","words").put("screen_text",new JSONArray()).put("visual_kind","title");
        JSONObject value=new JSONObject().put("object","padnote.video.review").put("protocol_version",1).put("task_id",task.remoteTaskId)
                .put("worker_task_id",workerId(task)).put("status","awaiting_storyboard_review").put("event_cursor",cursor).put("revision",revision)
                .put("review_sha256",reviewHash).put("lesson_ir_sha256",irHash)
                .put("episode",new JSONObject().put("title","title").put("audience","audience").put("learning_goal","goal").put("language","zh-CN"));
        String previewId=VideoTaskClient.previewId(value,scene);scene.put("preview",new JSONObject().put("id",previewId).put("media_type","image/png")
                .put("size_bytes",1).put("sha256",repeat('a',64)).put("width",1).put("height",1));value.put("scenes",new JSONArray().put(scene));
        VideoTaskClient.validateReview(value,task);return value;
    }
    private static String workerId(AgentTaskStore.Task task)throws Exception{
        JSONObject submission=new JSONObject(task.submissionJson);byte[] zip=android.util.Base64.decode(submission.getString("bundle_base64"),android.util.Base64.NO_WRAP);
        java.util.zip.ZipInputStream in=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip));java.util.zip.ZipEntry entry;
        while((entry=in.getNextEntry())!=null)if("request.json".equals(entry.getName())){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int n;while((n=in.read(buffer))>=0)out.write(buffer,0,n);return new JSONObject(new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8)).getString("task_id");}
        throw new AssertionError("request.json missing");
    }
    private static String repeat(char c,int count){char[] values=new char[count];java.util.Arrays.fill(values,c);return new String(values);}
}
