package com.padnote.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.io.File;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class AgentTaskStoreInstrumentedTest {
    @Test public void acceptedPostSnapshotPersistsThroughExecutorAndLegacyEmptyOutputBackfillsOnce() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.util.Map<String,Boolean> features=new java.util.LinkedHashMap<>();
        features.put("run_submission",true);features.put("run_status",true);
        features.put("delivery_contract_v1",true);
        AgentConnectionStore.Config profile=new AgentConnectionStore.Config("accepted-post-"+System.nanoTime(),
                "Synthetic accepted response",AgentConnectionStore.Kind.HERMES,
                "https://bridge.test","synthetic-token","accepted-post-credential",
                AgentConnectionStore.Transport.BRIDGE,"accepted-post-bridge","accepted-post-instance","",
                8L,System.currentTimeMillis(),Arrays.asList("run_submission","run_status"),features);
        File directory=new File(context.getCacheDir(),"accepted-post-"+System.nanoTime());
        AgentTaskStore store=new AgentTaskStore(directory);
        AgentTaskStore.Task draft=store.create(profile,"accepted post","synthetic prompt",
                "synthetic-accepted-note",1L,null,"",Collections.singletonList("image"));
        AtomicInteger posts=new AtomicInteger(),gets=new AtomicInteger();
        String postBody="{\"task_id\":\"remote-accepted\",\"instance_id\":\"accepted-post-instance\","+
                "\"status\":\"completed\",\"output\":\"accepted POST answer\",\"error\":\"accepted warning\","+
                "\"conversation_id\":\"remote-accepted\",\"parent_task_id\":\"\","+
                "\"followup_available\":true,\"followup_reason\":\"\",\"artifacts\":[],"+
                "\"delivery\":{\"status\":\"missing\",\"expected\":[\"image\"],"+
                "\"missing\":[\"image\"],\"artifact_count\":0}}";
        AgentTaskClient.Factory clients=()->new AgentTaskClient(request->{
            if("POST".equals(request.method))posts.incrementAndGet();
            else if("GET".equals(request.method))gets.incrementAndGet();
            return response(request,202,postBody);
        });
        AgentTaskDialogs.TaskConnectionSource source=new AgentTaskDialogs.TaskConnectionSource(){
            @Override public List<AgentConnectionStore.Config> list(){return Collections.singletonList(profile);}
            @Override public AgentConnectionStore.Config get(String id){return profile.id.equals(id)?profile:null;}
        };
        try {
            assertTrue("submission is queued once",AgentTaskSubmissionExecutor.submit(draft,store,source,
                    clients,context.getApplicationContext()));
            long deadline=SystemClock.uptimeMillis()+10000L;
            while(AgentTaskSubmissionExecutor.isInFlight(draft.clientTaskId)&&
                    SystemClock.uptimeMillis()<deadline)SystemClock.sleep(20L);
            assertFalse("accepted submission worker completed",AgentTaskSubmissionExecutor.isInFlight(draft.clientTaskId));
            AgentTaskStore.Task accepted=store.get(draft.clientTaskId);
            assertEquals("remote-accepted",accepted.remoteTaskId);
            assertEquals(AgentTaskStore.Status.COMPLETED,accepted.status);
            assertEquals("accepted POST answer",accepted.output);
            assertEquals("accepted warning",accepted.error);
            assertTrue("POST follow-up eligibility is durable",accepted.followupAvailable);
            assertEquals("the returned missing delivery state is durable",AgentDelivery.Status.MISSING,
                    accepted.delivery.status);
            assertEquals("POST was not repeated",1,posts.get());
            assertEquals("saving a POST response does not issue a follow-up GET",0,gets.get());

            AgentTaskStore.Task legacy=store.create(profile,"legacy terminal","synthetic prompt",
                    "synthetic-legacy-note",1L,null);
            assertTrue(store.applySubmission(legacy.clientTaskId,profile.id,profile.revision,
                    "remote-legacy",AgentTaskStore.Status.COMPLETED,"remote-legacy","",
                    Collections.emptyList(),AgentDelivery.forCurrentTask(legacy.expectedOutputs,
                            AgentTaskStore.Status.COMPLETED,Collections.emptyList())));
            AgentTaskClient getClient=new AgentTaskClient(request->{
                assertEquals("same-id compatibility lookup is GET only","GET",request.method);
                int readNumber=gets.incrementAndGet();
                String getOutput=readNumber==1?"backfilled legacy answer":"replacement must not overwrite";
                return response(request,200,"{\"task_id\":\"remote-legacy\",\"instance_id\":"+
                        "\"accepted-post-instance\",\"status\":\"completed\",\"output\":\""+
                        getOutput+"\",\"conversation_id\":\"remote-legacy\","+
                        "\"parent_task_id\":\"\",\"followup_available\":false,"+
                        "\"followup_reason\":\"not allowed\",\"artifacts\":[]}");
            });
            AgentTaskClient.RemoteStatus firstRead=getClient.status(store.get(legacy.clientTaskId),profile);
            assertTrue(store.applyStatus(legacy.clientTaskId,profile.id,profile.revision,
                    "remote-legacy",firstRead));
            assertEquals("backfilled legacy answer",store.get(legacy.clientTaskId).output);
            AgentTaskClient.RemoteStatus laterRead=getClient.status(store.get(legacy.clientTaskId),profile);
            assertTrue(store.applyStatus(legacy.clientTaskId,profile.id,profile.revision,
                    "remote-legacy",laterRead));
            assertEquals("legacy output is filled once, then frozen","backfilled legacy answer",
                    store.get(legacy.clientTaskId).output);
            assertEquals("only two explicit GET reads occurred",2,gets.get());
            assertEquals("there was no extra POST",1,posts.get());
        } finally {
            long cleanupDeadline=SystemClock.uptimeMillis()+5000L;
            while(AgentTaskSubmissionExecutor.isInFlight(draft.clientTaskId)&&
                    SystemClock.uptimeMillis()<cleanupDeadline)SystemClock.sleep(20L);
            if(!AgentTaskSubmissionExecutor.isInFlight(draft.clientTaskId))deleteTree(directory);
        }
    }

    @Test public void deliveryExpectationsPersistAcrossRetryAndFollowupKeepsParentFrozen() throws Exception {
        java.util.Map<String,Boolean> advertised=new java.util.LinkedHashMap<>();
        advertised.put("run_submission",true);advertised.put("run_status",true);
        advertised.put("delivery_contract_v1",true);
        AgentConnectionStore.Config connection=new AgentConnectionStore.Config("delivery-connection",
                "Delivery test",AgentConnectionStore.Kind.HERMES,"https://bridge.test","synthetic-token",
                "delivery-credential",AgentConnectionStore.Transport.BRIDGE,"bridge-test","instance-test",
                "",10L,20L,Arrays.asList("run_submission","run_status"),advertised);
        File directory=new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getCacheDir(),"delivery-store-"+System.nanoTime());
        AgentTaskStore store=new AgentTaskStore(directory);
        AgentTaskStore.Task parent=store.create(connection,"Delivery task","make files","note-delivery",7L,
                null,"",Arrays.asList("pdf","image"));
        String frozenRequest=parent.submissionJson;
        assertTrue(parent.deliveryContractSupported);
        assertEquals(Arrays.asList("image","pdf"),parent.expectedOutputs);
        assertEquals(Arrays.asList("image","pdf"),
                AgentDelivery.parseArray(new JSONObject(frozenRequest).getJSONArray("expected_outputs")));

        java.util.Map<String,Boolean> legacyFeatures=new java.util.LinkedHashMap<>();
        legacyFeatures.put("run_submission",true);legacyFeatures.put("run_status",true);
        AgentConnectionStore.Config capabilityChanged=new AgentConnectionStore.Config(connection.id,
                connection.name,connection.kind,connection.endpoint,connection.token,connection.credentialRef,
                connection.transport,connection.bridgeId,connection.instanceId,connection.certSha256,
                connection.revision,connection.verifiedAt,connection.capabilities,
                legacyFeatures);
        assertTrue("feature-map change does not rewrite an existing request",parent.matches(capabilityChanged));
        AgentTaskStore reopenedBeforeSend=new AgentTaskStore(directory);
        AgentTaskStore.Task retry=reopenedBeforeSend.get(parent.clientTaskId);
        assertEquals(frozenRequest,retry.submissionJson);
        assertTrue(retry.deliveryContractSupported);
        assertTrue(AgentTaskSubmissionExecutor.sameSubmissionSnapshot(parent,retry));
        JSONObject changedPolicy=parent.toJson().put("deliveryContractSupported",false);
        assertFalse(AgentTaskSubmissionExecutor.sameSubmissionSnapshot(parent,
                AgentTaskStore.Task.fromJson(changedPolicy)));
        JSONObject changedExpectation=parent.toJson();
        changedExpectation.put("expectedOutputs",new org.json.JSONArray(Collections.singletonList("pdf")));
        changedExpectation.getJSONObject("delivery").put("expected",
                new org.json.JSONArray(Collections.singletonList("pdf")));
        assertFalse(AgentTaskSubmissionExecutor.sameSubmissionSnapshot(parent,
                AgentTaskStore.Task.fromJson(changedExpectation)));

        AgentDelivery.Summary runningDelivery=AgentDelivery.fromServerOrLocal(null,
                parent.expectedOutputs,AgentTaskStore.Status.RUNNING,Collections.emptyList());
        assertFalse(runningDelivery.reportedByServer);
        assertTrue(store.applySubmission(parent.clientTaskId,connection.id,connection.revision,
                "remote-delivery",AgentTaskStore.Status.RUNNING,"remote-delivery","",
                Collections.emptyList(),runningDelivery));
        assertEquals("remote-delivery",store.get(parent.clientTaskId).remoteTaskId);
        AgentTaskStore.Artifact image=new AgentTaskStore.Artifact("image-artifact","graph.png",
                "image/png",128L,"a".repeat(64));
        AgentDelivery.Summary missingPdf=new AgentDelivery.Summary(AgentDelivery.Status.MISSING,
                parent.expectedOutputs,Collections.singletonList("pdf"),1,true);
        AgentTaskClient.RemoteStatus completed=new AgentTaskClient.RemoteStatus(
                AgentTaskStore.Status.COMPLETED,"Graph ready","","","","",
                Collections.singletonList(image),"remote-delivery","",true,"",
                "",missingPdf);
        assertTrue(store.applyStatus(parent.clientTaskId,connection.id,connection.revision,
                "remote-delivery",completed));
        AgentTaskStore.Task terminal=store.get(parent.clientTaskId);
        assertEquals(AgentDelivery.Status.MISSING,terminal.delivery.status);
        String parentOutput=terminal.output;
        AgentTaskStore.Artifact latePdf=new AgentTaskStore.Artifact("late-pdf","late.pdf",
                "application/pdf",48L,"d".repeat(64));
        AgentDelivery.Summary changedTerminal=new AgentDelivery.Summary(AgentDelivery.Status.DELIVERED,
                parent.expectedOutputs,Collections.emptyList(),2,true);
        AgentTaskClient.RemoteStatus lateTerminal=new AgentTaskClient.RemoteStatus(
                AgentTaskStore.Status.COMPLETED,"changed output","","","","",
                Arrays.asList(image,latePdf),"remote-delivery","",true,"","",changedTerminal);
        assertTrue(store.applyStatus(parent.clientTaskId,connection.id,connection.revision,
                "remote-delivery",lateTerminal));
        assertEquals(1,store.get(parent.clientTaskId).artifacts.size());
        assertEquals(AgentDelivery.Status.MISSING,store.get(parent.clientTaskId).delivery.status);
        assertEquals(parentOutput,store.get(parent.clientTaskId).output);
        AgentTaskStore.Task child=store.createFollowup(terminal,connection,
                "Add the missing PDF",Collections.singletonList("pdf"));
        assertEquals(Collections.singletonList("pdf"),child.expectedOutputs);
        assertEquals(Collections.singletonList("pdf"),AgentDelivery.parseArray(
                new JSONObject(child.submissionJson).getJSONArray("expected_outputs")));
        JSONObject childRequest=new JSONObject(child.submissionJson);
        assertFalse(childRequest.has("bundle_base64"));
        assertFalse(childRequest.has("required_capability"));
        assertEquals("remote-delivery",childRequest.getString("parent_task_id"));
        assertEquals(7L,childRequest.getJSONObject("source").getLong("note_revision"));
        assertEquals(AgentDelivery.Status.MISSING,store.get(parent.clientTaskId).delivery.status);
        assertEquals(parentOutput,store.get(parent.clientTaskId).output);

        AgentTaskStore afterRestart=new AgentTaskStore(directory);
        AgentTaskStore.Task loadedChild=afterRestart.get(child.clientTaskId);
        assertEquals(child.submissionJson,loadedChild.submissionJson);
        assertTrue(loadedChild.deliveryContractSupported);
        assertEquals(Collections.singletonList("pdf"),loadedChild.expectedOutputs);
        assertEquals(AgentDelivery.Status.PENDING,loadedChild.delivery.status);
        assertEquals(AgentDelivery.Status.MISSING,afterRestart.get(parent.clientTaskId).delivery.status);

        AgentTaskStore.Task legacy=store.create(capabilityChanged,"Legacy task","make PDF","note-old",3L,
                null,"",Collections.singletonList("pdf"));
        assertFalse(legacy.deliveryContractSupported);
        assertEquals(Collections.singletonList("pdf"),legacy.expectedOutputs);
        assertFalse(new JSONObject(legacy.submissionJson).has("expected_outputs"));
        java.util.Map<String,Boolean> upgradedFeatures=new java.util.LinkedHashMap<>();
        upgradedFeatures.put("run_submission",true);upgradedFeatures.put("run_status",true);
        upgradedFeatures.put("delivery_contract_v1",true);
        AgentConnectionStore.Config capabilityUpgraded=new AgentConnectionStore.Config(connection.id,
                connection.name,connection.kind,connection.endpoint,connection.token,connection.credentialRef,
                connection.transport,connection.bridgeId,connection.instanceId,connection.certSha256,
                connection.revision,connection.verifiedAt,connection.capabilities,upgradedFeatures);
        assertTrue(legacy.matches(capabilityUpgraded));
        AgentTaskStore.Task retryLegacy=new AgentTaskStore(directory).get(legacy.clientTaskId);
        assertEquals(legacy.submissionJson,retryLegacy.submissionJson);
        assertFalse(retryLegacy.deliveryContractSupported);
        assertFalse(new JSONObject(retryLegacy.submissionJson).has("expected_outputs"));
        assertTrue(store.applySubmission(legacy.clientTaskId,capabilityChanged.id,
                capabilityChanged.revision,"legacy-remote",AgentTaskStore.Status.RUNNING));
        AgentTaskStore.Artifact emptyPdf=new AgentTaskStore.Artifact("empty-pdf","empty.pdf",
                "application/pdf",0L,"b".repeat(64));
        AgentTaskClient.RemoteStatus localCompletion=new AgentTaskClient.RemoteStatus(
                AgentTaskStore.Status.COMPLETED,"finished","","","","",
                Collections.singletonList(emptyPdf));
        assertTrue(store.applyStatus(legacy.clientTaskId,capabilityChanged.id,
                capabilityChanged.revision,"legacy-remote",localCompletion));
        AgentTaskStore.Task localResult=new AgentTaskStore(directory).get(legacy.clientTaskId);
        assertEquals(AgentDelivery.Status.MISSING,localResult.delivery.status);
        assertFalse(localResult.delivery.reportedByServer);
        deleteTree(directory);
    }

    @Test public void stopDeliveryControlsWhenStoppingAndRejectsStaleControlCas() throws Exception {
        AgentConnectionStore.Config bridge=connection();
        for(String delivery:new String[]{"not_sent","rejected","superseded"}) {
            AgentTaskStore store=new AgentTaskStore(new File(InstrumentationRegistry.getInstrumentation()
                    .getTargetContext().getCacheDir(),"stop-recovery-"+delivery+"-"+System.nanoTime()));
            AgentTaskStore.Task task=store.create(bridge,"Stop test","go","",1L,null);
            assertTrue(store.applySubmission(task.clientTaskId,bridge.id,bridge.revision,"remote-stop",
                    AgentTaskStore.Status.RUNNING));
            assertTrue(store.markLocal(task.clientTaskId,AgentTaskStore.Status.STOPPING,""));
            AgentTaskClient.RemoteStatus recovered=new AgentTaskClient.RemoteStatus(
                    AgentTaskStore.Status.RUNNING,"","","","","",Collections.emptyList(),
                    "","",false,"",delivery);
            assertTrue(delivery,store.applyStatus(task.clientTaskId,bridge.id,bridge.revision,
                    "remote-stop",recovered));
            assertEquals(AgentTaskStore.Status.RUNNING,store.get(task.clientTaskId).status);
        }
        for(String delivery:new String[]{"unknown","confirmed"}) {
            AgentTaskStore store=new AgentTaskStore(new File(InstrumentationRegistry.getInstrumentation()
                    .getTargetContext().getCacheDir(),"stop-retain-"+delivery+"-"+System.nanoTime()));
            AgentTaskStore.Task task=store.create(bridge,"Stop test","go","",1L,null);
            assertTrue(store.applySubmission(task.clientTaskId,bridge.id,bridge.revision,"remote-stop",
                    AgentTaskStore.Status.RUNNING));
            assertTrue(store.markLocal(task.clientTaskId,AgentTaskStore.Status.STOPPING,""));
            AgentTaskClient.RemoteStatus response=new AgentTaskClient.RemoteStatus(
                    AgentTaskStore.Status.RUNNING,"","","","","",Collections.emptyList(),
                    "","",false,"",delivery);
            assertFalse(delivery,store.applyStatus(task.clientTaskId,bridge.id,bridge.revision,
                    "remote-stop",response));
            assertEquals(AgentTaskStore.Status.STOPPING,store.get(task.clientTaskId).status);
        }
        AgentConnectionStore.Config direct=new AgentConnectionStore.Config("connection-direct","Direct",
                AgentConnectionStore.Kind.HERMES,"https://hermes.test","token","credential-direct",
                AgentConnectionStore.Transport.DIRECT,"","",3L,10L,Arrays.asList("run_submission","run_status"));
        AgentTaskStore directStore=new AgentTaskStore(new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir(),"stop-direct-"+System.nanoTime()));
        AgentTaskStore.Task directTask=directStore.create(direct,"Direct stop","go","",1L,null);
        assertTrue(directStore.applySubmission(directTask.clientTaskId,direct.id,direct.revision,"direct-run",
                AgentTaskStore.Status.RUNNING));
        assertTrue(directStore.markLocal(directTask.clientTaskId,AgentTaskStore.Status.STOPPING,""));
        AgentTaskClient.RemoteStatus forged=new AgentTaskClient.RemoteStatus(AgentTaskStore.Status.RUNNING,
                "","","","","",Collections.emptyList(),"","",false,"","not_sent");
        assertFalse(directStore.applyStatus(directTask.clientTaskId,direct.id,direct.revision,"direct-run",forged));
        assertEquals(AgentTaskStore.Status.STOPPING,directStore.get(directTask.clientTaskId).status);

        AgentTaskStore.Task expected=directStore.get(directTask.clientTaskId);
        assertTrue(directStore.markLocal(directTask.clientTaskId,AgentTaskStore.Status.RUNNING,"newer poll"));
        AgentTaskClient.RemoteStatus stale=new AgentTaskClient.RemoteStatus(AgentTaskStore.Status.COMPLETED,
                "old response","","","","",Collections.emptyList());
        assertFalse(directStore.applyControlStatus(expected,stale));
        assertEquals(AgentTaskStore.Status.RUNNING,directStore.get(directTask.clientTaskId).status);
        assertEquals("newer poll",directStore.get(directTask.clientTaskId).error);
    }

    @Test public void taskPersistsSyntheticSourceAndTerminalCannotRegress() throws Exception {
        AgentConnectionStore.Config connection = new AgentConnectionStore.Config(
                "connection-test", "Test computer", AgentConnectionStore.Kind.HERMES,
                "https://bridge.test", "synthetic-token", "credential-test",
                AgentConnectionStore.Transport.BRIDGE, "bridge-test", "instance-test",
                3L, 10L, Arrays.asList("run_submission", "run_status"));
        AgentTaskStore store = new AgentTaskStore(new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir(),"task-store-"+System.nanoTime()));
        AgentTaskStore.Task created = store.create(connection, "Text task", "do this",
                "", 1L, null);
        assertTrue(created.noteId.startsWith("text-"));
        assertEquals(created.noteId,
                new JSONObject(created.submissionJson).getJSONObject("source").getString("note_id"));
        assertEquals("Test computer", store.get(created.clientTaskId).connectionName);

        assertTrue(store.applySubmission(created.clientTaskId, connection.id,
                connection.revision, "remote-test", AgentTaskStore.Status.RUNNING));
        AgentTaskClient.RemoteStatus completed = new AgentTaskClient.RemoteStatus(
                AgentTaskStore.Status.COMPLETED, "done", "", "", "", "",
                Collections.emptyList());
        assertTrue(store.applyStatus(created.clientTaskId, connection.id,
                connection.revision, "remote-test", completed));
        AgentTaskClient.RemoteStatus late = new AgentTaskClient.RemoteStatus(
                AgentTaskStore.Status.RUNNING, "", "", "", "", "",
                Collections.emptyList());
        assertFalse(store.applyStatus(created.clientTaskId, connection.id,
                connection.revision, "remote-test", late));
        assertEquals(AgentTaskStore.Status.COMPLETED,
                store.get(created.clientTaskId).status);
    }

    @Test public void followupIsPersistedOnceAndReopensWithExactBodyAndLineage() throws Exception {
        AgentConnectionStore.Config connection = connection();
        File directory=new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getCacheDir(),"followup-store-"+System.nanoTime());
        AgentTaskStore store=new AgentTaskStore(directory);
        AgentTaskStore.Task parent=store.create(connection,"Same source","first prompt","note-x",4L,null);
        assertTrue(store.applySubmission(parent.clientTaskId,connection.id,connection.revision,
                "remote-root",AgentTaskStore.Status.RUNNING));
        AgentTaskClient.RemoteStatus completed=new AgentTaskClient.RemoteStatus(
                AgentTaskStore.Status.COMPLETED,"first result","","","","",
                Collections.emptyList(),"remote-root","",true,"");
        assertTrue(store.applyStatus(parent.clientTaskId,connection.id,connection.revision,
                "remote-root",completed));

        AgentTaskStore.Task child=store.createFollowup(store.get(parent.clientTaskId),connection,"second prompt");
        String body=child.submissionJson;
        assertEquals("remote-root",new JSONObject(body).getString("parent_task_id"));
        assertEquals("note-x",new JSONObject(body).getJSONObject("source").getString("note_id"));
        assertEquals(4L,new JSONObject(body).getJSONObject("source").getLong("note_revision"));
        assertFalse(new JSONObject(body).has("bundle_base64"));
        assertEquals(child.clientTaskId,new JSONObject(body).getString("client_task_id"));
        assertEquals("first result",store.get(parent.clientTaskId).output);

        AgentTaskStore reopened=new AgentTaskStore(directory);
        AgentTaskStore.Task restored=reopened.createFollowup(reopened.get(parent.clientTaskId),connection,"second prompt");
        assertEquals(child.clientTaskId,restored.clientTaskId);
        assertEquals(body,restored.submissionJson);
        assertEquals(child.clientTaskId,reopened.get(parent.clientTaskId).childClientTaskId);
        try { reopened.createFollowup(reopened.get(parent.clientTaskId),connection,"different double tap"); fail(); }
        catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("新文字未发送")); }
    }

    @Test public void versionOneTaskLoadsWithFollowupDisabled() throws Exception {
        AgentConnectionStore.Config connection=connection();
        AgentTaskStore store=new AgentTaskStore(new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir(),"followup-v1-"+System.nanoTime()));
        AgentTaskStore.Task task=store.create(connection,"old task","old input","note-v1",1L,null);
        JSONObject legacy=task.toJson().put("schemaVersion",1);
        legacy.remove("conversationId");legacy.remove("parentTaskId");legacy.remove("parentClientTaskId");
        legacy.remove("childClientTaskId");legacy.remove("followupAvailable");legacy.remove("followupReason");
        AgentTaskStore.Task restored=AgentTaskStore.Task.fromJson(legacy);
        assertEquals(task.clientTaskId,restored.clientTaskId);
        assertFalse(restored.followupAvailable);
        assertTrue(restored.conversationId.isEmpty());
        assertTrue(restored.parentTaskId.isEmpty());
    }

    @Test public void followupRejectsRunningAndChangedConnectionButReusesExistingChildOnlyOnBoundConnection() throws Exception {
        AgentConnectionStore.Config connection=connection();
        AgentTaskStore store=new AgentTaskStore(new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir(),"followup-guard-"+System.nanoTime()));
        AgentTaskStore.Task parent=store.create(connection,"title","first","note-x",1L,null);
        try { store.createFollowup(parent,connection,"too soon"); fail(); }
        catch(IllegalStateException expected) { }
        AgentConnectionStore.Config changed=new AgentConnectionStore.Config(connection.id,connection.name,
                connection.kind,"https://other.test","token",connection.credentialRef,
                connection.transport,connection.bridgeId,connection.instanceId,connection.revision,
                connection.verifiedAt,connection.capabilities);
        try { store.createFollowup(parent,changed,"wrong connection"); fail(); }
        catch(IllegalStateException expected) { }
    }

    @Test public void concurrentFollowupTapsReserveOneChild() throws Exception {
        AgentConnectionStore.Config connection=connection();
        AgentTaskStore store=new AgentTaskStore(new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir(),"followup-race-"+System.nanoTime()));
        AgentTaskStore.Task parent=store.create(connection,"title","first","note-race",1L,null);
        assertTrue(store.applySubmission(parent.clientTaskId,connection.id,connection.revision,
                "remote-race",AgentTaskStore.Status.RUNNING));
        assertTrue(store.applyStatus(parent.clientTaskId,connection.id,connection.revision,
                "remote-race",new AgentTaskClient.RemoteStatus(AgentTaskStore.Status.COMPLETED,
                "kept result","","","","",Collections.emptyList(),"remote-race","",true,"")));
        CountDownLatch start=new CountDownLatch(1),done=new CountDownLatch(2);
        AtomicReference<String> first=new AtomicReference<>(),second=new AtomicReference<>();
        for(AtomicReference<String> result:new AtomicReference[]{first,second})new Thread(()->{
            try{start.await();result.set(store.createFollowup(parent,connection,"race").clientTaskId);}
            catch(Exception error){result.set("error:"+error.getMessage());}finally{done.countDown();}
        }).start();
        start.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));
        assertEquals(first.get(),second.get());assertEquals(1,store.children(parent.clientTaskId).size());
        assertEquals("kept result",store.get(parent.clientTaskId).output);
    }

    @Test public void terminalRefreshUpdatesFollowupAndChildCreationPreservesHistory() throws Exception {
        AgentConnectionStore.Config connection=connection();
        AgentTaskStore store=new AgentTaskStore(new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir(),"followup-terminal-"+System.nanoTime()));
        AgentTaskStore.Task parent=store.create(connection,"title","first","note-terminal",1L,null);
        assertTrue(store.applySubmission(parent.clientTaskId,connection.id,connection.revision,
                "remote-terminal",AgentTaskStore.Status.COMPLETED));
        AgentTaskClient.RemoteStatus allowed=new AgentTaskClient.RemoteStatus(
                AgentTaskStore.Status.COMPLETED,"confirmed result","","","","",
                Collections.emptyList(),"remote-terminal","",true,"");
        assertTrue(store.applyStatus(parent.clientTaskId,connection.id,connection.revision,
                "remote-terminal",allowed));
        assertTrue(store.get(parent.clientTaskId).followupAvailable);
        AgentTaskStore.Task child=store.createFollowup(store.get(parent.clientTaskId),connection,"next");
        AgentTaskClient.RemoteStatus noFurther=new AgentTaskClient.RemoteStatus(
                AgentTaskStore.Status.COMPLETED,"","","","","",Collections.emptyList(),
                "remote-terminal","",false,"已有后续轮");
        assertTrue(store.applyStatus(parent.clientTaskId,connection.id,connection.revision,
                "remote-terminal",noFurther));
        assertFalse(store.get(parent.clientTaskId).followupAvailable);
        assertEquals("confirmed result",store.get(parent.clientTaskId).output);
        assertEquals(child.clientTaskId,store.get(parent.clientTaskId).childClientTaskId);
        assertFalse(store.applyStatus(parent.clientTaskId,connection.id,connection.revision,
                "remote-terminal",new AgentTaskClient.RemoteStatus(AgentTaskStore.Status.RUNNING,
                "stale","","","","",Collections.emptyList(),"remote-terminal","",true,"")));
        assertFalse(store.markLocal(parent.clientTaskId,AgentTaskStore.Status.INTERRUPTED,"late identity change"));
        assertEquals(AgentTaskStore.Status.COMPLETED,store.get(parent.clientTaskId).status);
    }

    @Test public void completedMissingImageRefreshAddsSessionMetadataButKeepsParentDeliveryFrozen() throws Exception {
        java.util.Map<String,Boolean> features=new java.util.LinkedHashMap<>();
        features.put("run_submission",true);features.put("run_status",true);
        features.put("delivery_contract_v1",true);
        AgentConnectionStore.Config connection=new AgentConnectionStore.Config("missing-refresh-connection",
                "Missing image refresh",AgentConnectionStore.Kind.HERMES,"https://bridge.test",
                "synthetic-token","missing-refresh-credential",AgentConnectionStore.Transport.BRIDGE,
                "bridge-refresh","instance-refresh","",10L,20L,
                Arrays.asList("run_submission","run_status"),features);
        File directory=new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir(),"completed-missing-refresh-"+System.nanoTime());
        AgentTaskStore store=new AgentTaskStore(directory);
        AgentTaskStore.Task draft=store.create(connection,"Missing image task","C42_FIXTURE:missing-image",
                "synthetic-note-refresh",9L,null,"",Collections.singletonList("image"));
        AgentDelivery.Summary missing=AgentDelivery.forCurrentTask(draft.expectedOutputs,
                AgentTaskStore.Status.COMPLETED,Collections.emptyList());
        assertEquals(AgentDelivery.Status.MISSING,missing.status);
        assertTrue(store.applySubmission(draft.clientTaskId,connection.id,connection.revision,
                "remote-missing-image",AgentTaskStore.Status.COMPLETED,"","",
                Collections.emptyList(),missing));
        assertTrue("record the original terminal answer before testing metadata-only refresh",
                store.applyStatus(draft.clientTaskId,connection.id,connection.revision,
                        "remote-missing-image",new AgentTaskClient.RemoteStatus(
                                AgentTaskStore.Status.COMPLETED,"original completed answer","","","","",
                                Collections.emptyList(),"","",false,"", "",missing)));
        AgentTaskStore.Task original=store.get(draft.clientTaskId);
        assertEquals(AgentTaskStore.Status.COMPLETED,original.status);
        assertEquals("",original.conversationId);
        assertFalse(original.followupAvailable);
        assertEquals(AgentDelivery.Status.MISSING,original.delivery.status);

        AgentTaskStore.Artifact lateImage=new AgentTaskStore.Artifact("late-image","late.png",
                "image/png",8L,"a".repeat(64));
        AgentDelivery.Summary changedDelivery=new AgentDelivery.Summary(AgentDelivery.Status.DELIVERED,
                original.expectedOutputs,Collections.emptyList(),1,true);
        AgentTaskClient.RemoteStatus explicitRefresh=new AgentTaskClient.RemoteStatus(
                AgentTaskStore.Status.COMPLETED,"changed remote output","changed remote error",
                "changed-approval","changed title","changed description",
                Collections.singletonList(lateImage),"remote-missing-image","",true,"",
                "",changedDelivery);
        assertFalse("a response from a changed connection cannot repair the terminal session",
                store.applyStatus(original.clientTaskId,connection.id+"-changed",connection.revision,
                        original.remoteTaskId,explicitRefresh));
        assertEquals("rejected connection identity leaves the terminal record unchanged",
                "",store.get(original.clientTaskId).conversationId);
        assertTrue("same terminal status can add the missing Bridge conversation metadata",
                store.applyStatus(original.clientTaskId,connection.id,connection.revision,
                        original.remoteTaskId,explicitRefresh));

        AgentTaskStore.Task refreshed=store.get(original.clientTaskId);
        assertEquals("explicit refresh updates the existing record",original.clientTaskId,refreshed.clientTaskId);
        assertEquals("remote-missing-image",refreshed.remoteTaskId);
        assertEquals(AgentTaskStore.Status.COMPLETED,refreshed.status);
        assertEquals("remote-missing-image",refreshed.conversationId);
        assertTrue(refreshed.followupAvailable);
        assertEquals("",refreshed.followupReason);
        assertEquals("terminal output remains frozen",original.output,refreshed.output);
        assertEquals("terminal error remains frozen",original.error,refreshed.error);
        assertEquals("late artifact metadata is not merged into the completed snapshot",
                original.artifacts.size(),refreshed.artifacts.size());
        assertEquals("delivery remains the original missing-image result",
                AgentDelivery.Status.MISSING,refreshed.delivery.status);
        assertEquals(original.expectedOutputs,refreshed.expectedOutputs);
        assertTrue(store.children(original.clientTaskId).isEmpty());

        AgentTaskStore.Task child=store.createFollowup(refreshed,connection,
                "C42_FIXTURE:repair-image",Collections.singletonList("image"));
        assertEquals("a follow-up is a new linked child",original.clientTaskId,child.parentClientTaskId);
        assertEquals(Collections.singletonList("image"),child.expectedOutputs);
        AgentTaskStore.Task parentAfterChild=store.get(original.clientTaskId);
        assertEquals(original.clientTaskId,parentAfterChild.clientTaskId);
        assertEquals(AgentTaskStore.Status.COMPLETED,parentAfterChild.status);
        assertEquals("original terminal output remains frozen",original.output,parentAfterChild.output);
        assertTrue(parentAfterChild.artifacts.isEmpty());
        assertEquals(AgentDelivery.Status.MISSING,parentAfterChild.delivery.status);
        assertEquals(Collections.singletonList("image"),parentAfterChild.expectedOutputs);
        assertEquals(child.clientTaskId,parentAfterChild.childClientTaskId);
        deleteTree(directory);
    }

    @Test public void followupDialogCreatesChildAndNavigatesBackToHistory() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        AgentConnectionStore connections=new AgentConnectionStore(context);
        AgentConnectionStore.Config saved=null;
        androidx.test.core.app.ActivityScenario<MainActivity> scenario=null;
        NoOpExecutor worker=new NoOpExecutor();
        File directory=new File(context.getCacheDir(),"followup-ui-"+System.nanoTime());
        try {
            saved=connections.addBridge("Followup UI test",AgentConnectionStore.Kind.HERMES,
                    "https://bridge.test","bridge-ui","instance-ui","synthetic-token");
            assertTrue(connections.applyProbeSuccess(saved.id,saved.revision,
                    new AgentConnectionClient.ProbeResult("verified","bridge-ui","instance-ui",
                            Arrays.asList("run_submission","run_status"))));
            AgentConnectionStore.Config verified=connections.get(saved.id);
            AgentTaskStore store=new AgentTaskStore(directory);
            AgentTaskStore.Task parent=store.create(verified,"UI task","first prompt","note-ui",2L,null);
            assertTrue(store.applySubmission(parent.clientTaskId,verified.id,verified.revision,
                    "remote-ui",AgentTaskStore.Status.RUNNING));
            assertTrue(store.applyStatus(parent.clientTaskId,verified.id,verified.revision,
                    "remote-ui",new AgentTaskClient.RemoteStatus(AgentTaskStore.Status.COMPLETED,
                    "first output","","","","",Collections.emptyList(),"remote-ui","",true,"")));
            final AgentTaskDialogs[] dialogs={null};
            scenario=androidx.test.core.app.ActivityScenario.launch(MainActivity.class);
            awaitWindowFocus(scenario);
            scenario.onActivity(activity->{
                assertFalse(activity.isFinishing());assertFalse(activity.isDestroyed());
                dialogs[0]=new AgentTaskDialogs(activity,connections,worker,null,store);
                dialogs[0].showDetail(parent.clientTaskId);
            });
            androidx.test.espresso.Espresso.onView(org.hamcrest.Matchers.allOf(
                    androidx.test.espresso.matcher.ViewMatchers.withText("继续沟通"),
                    androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.Button.class)))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                    .perform(androidx.test.espresso.action.ViewActions.click());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(
                    android.widget.EditText.class)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.typeText("next prompt"),
                            androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("发送"))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.click());
            AgentTaskStore.Task child=store.children(parent.clientTaskId).get(0);
            assertEquals("remote-ui",new JSONObject(child.submissionJson).getString("parent_task_id"));
            scenario.onActivity(activity->dialogs[0].showList());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(
                    org.hamcrest.Matchers.containsString("UI task\n已完成")))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                    .perform(androidx.test.espresso.action.ViewActions.click());
            String childLabel="打开后续轮 · 提交中/结果待确认 · next prompt";
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(childLabel))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                    .perform(androidx.test.espresso.action.ViewActions.click());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("上一轮"))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                    .perform(androidx.test.espresso.action.ViewActions.click());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(childLabel))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                    .perform(androidx.test.espresso.action.ViewActions.click());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("上一轮"))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
        } finally {
            worker.shutdownNow();
            try {
                if(scenario!=null)scenario.close();
            } finally {
                try {
                    if(saved!=null)connections.delete(saved.id);
                } finally { deleteTree(directory); }
            }
        }
    }

    @Test public void missingDeliveryShowsExplicitRepairPreviewAndCancelKeepsTaskUnchanged() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        AgentConnectionStore connections=new AgentConnectionStore(context);
        AgentConnectionStore.Config saved=null;
        androidx.test.core.app.ActivityScenario<MainActivity> scenario=null;
        NoOpExecutor worker=new NoOpExecutor();
        File directory=new File(context.getCacheDir(),"delivery-repair-ui-"+System.nanoTime());
        try {
            saved=connections.addBridge("Delivery UI test",AgentConnectionStore.Kind.HERMES,
                    "https://bridge.test","bridge-ui","instance-ui","synthetic-token");
            java.util.Map<String,Boolean> features=new java.util.LinkedHashMap<>();
            features.put("run_submission",true);features.put("run_status",true);
            features.put("delivery_contract_v1",true);
            assertTrue(connections.applyProbeSuccess(saved.id,saved.revision,
                    new AgentConnectionClient.ProbeResult("verified","bridge-ui","instance-ui",
                            features)));
            AgentConnectionStore.Config verified=connections.get(saved.id);
            AgentTaskStore store=new AgentTaskStore(directory);
            AgentTaskStore.Task parent=store.create(verified,"Delivery UI task","create an image and PDF",
                    "note-delivery-ui",2L,null,"",Arrays.asList("image","pdf"));
            assertTrue(store.applySubmission(parent.clientTaskId,verified.id,verified.revision,
                    "remote-delivery-ui",AgentTaskStore.Status.RUNNING,"remote-delivery-ui","",
                    Collections.emptyList(),new AgentDelivery.Summary(AgentDelivery.Status.PENDING,
                            parent.expectedOutputs,Collections.emptyList(),0,true)));
            AgentTaskStore.Artifact image=new AgentTaskStore.Artifact("image-ui","answer.png",
                    "image/png",64L,"c".repeat(64));
            AgentDelivery.Summary missing=new AgentDelivery.Summary(AgentDelivery.Status.MISSING,
                    parent.expectedOutputs,Collections.singletonList("pdf"),1,true);
            AgentTaskClient.RemoteStatus completed=new AgentTaskClient.RemoteStatus(
                    AgentTaskStore.Status.COMPLETED,"Image returned","","","","",
                    Collections.singletonList(image),"remote-delivery-ui","",true,"","",missing);
            assertTrue(store.applyStatus(parent.clientTaskId,verified.id,verified.revision,
                    "remote-delivery-ui",completed));

            final AgentTaskDialogs[] dialogs={null};
            scenario=androidx.test.core.app.ActivityScenario.launch(MainActivity.class);
            awaitWindowFocus(scenario);
            scenario.onActivity(activity->{dialogs[0]=new AgentTaskDialogs(activity,connections,worker,null,store);
                dialogs[0].showDetail(parent.clientTaskId);});
            String missingSummary=context.getString(R.string.flow_delivery_missing,
                    context.getString(R.string.flow_delivery_kind_pdf),
                    context.getString(R.string.flow_delivery_kind_image))+"\n"+
                    context.getString(R.string.flow_delivery_server_checked);
            androidx.test.espresso.Espresso.onView(org.hamcrest.Matchers.allOf(
                    androidx.test.espresso.matcher.ViewMatchers.withText(missingSummary),
                    androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.TextView.class)))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(
                            context.getString(R.string.flow_delivery_repair_button)))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.click());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(
                    android.widget.EditText.class)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.withText(
                                    context.getString(R.string.flow_delivery_repair_prefill,
                                            context.getString(R.string.flow_delivery_kind_pdf)))));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(
                            "✓ "+context.getString(R.string.flow_delivery_kind_pdf)))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.scrollTo())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            assertTrue(store.children(parent.clientTaskId).isEmpty());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(
                            "✓ "+context.getString(R.string.flow_delivery_kind_pdf)))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.click());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(
                            context.getString(R.string.flow_send)))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.click());
            assertTrue("repair form stays open unless every missing type remains selected",
                    store.children(parent.clientTaskId).isEmpty());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(
                            context.getString(R.string.flow_delivery_kind_pdf)))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.scrollTo(),
                            androidx.test.espresso.action.ViewActions.click());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(
                            "✓ "+context.getString(R.string.flow_delivery_kind_pdf)))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(
                            context.getString(R.string.flow_cancel)))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.click());
            assertTrue(store.children(parent.clientTaskId).isEmpty());
            assertEquals(AgentTaskStore.Status.COMPLETED,store.get(parent.clientTaskId).status);
            assertEquals(AgentDelivery.Status.MISSING,store.get(parent.clientTaskId).delivery.status);
            assertEquals("Image returned",store.get(parent.clientTaskId).output);
        } finally {
            worker.shutdownNow();
            try { if(scenario!=null)scenario.close(); }
            finally { try { if(saved!=null)connections.delete(saved.id); }
                finally { deleteTree(directory); } }
        }
    }

    private static void awaitWindowFocus(androidx.test.core.app.ActivityScenario<MainActivity> scenario) {
        long deadline=android.os.SystemClock.uptimeMillis()+5000L;
        java.util.concurrent.atomic.AtomicBoolean focused=new java.util.concurrent.atomic.AtomicBoolean();
        while(android.os.SystemClock.uptimeMillis()<deadline) {
            focused.set(false);
            scenario.onActivity(activity->focused.set(!activity.isFinishing()&&!activity.isDestroyed()&&
                    activity.getWindow()!=null&&activity.getWindow().getDecorView().hasWindowFocus()));
            if(focused.get())return;
            android.os.SystemClock.sleep(50L);
        }
        assertTrue("MainActivity did not receive window focus before showing task dialogs",focused.get());
    }

    private static void deleteTree(File file) {
        if(file==null||!file.exists())return;
        File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);
        file.delete();
    }

    private static AgentHttpTransport.Response response(AgentHttpTransport.Request request,int status,
                                                        String body) {
        try { return new AgentHttpTransport.Response(status,request.url,request.url,
                body.getBytes(StandardCharsets.UTF_8),Collections.emptyMap()); }
        catch(Exception error){throw new AssertionError(error);}
    }

    private static AgentConnectionStore.Config connection() {
        return new AgentConnectionStore.Config("connection-test", "Test computer",
                AgentConnectionStore.Kind.HERMES,"https://bridge.test","synthetic-token",
                "credential-test",AgentConnectionStore.Transport.BRIDGE,"bridge-test",
                "instance-test",3L,10L,Arrays.asList("run_submission","run_status"));
    }

    private static final class NoOpExecutor extends AbstractExecutorService {
        private volatile boolean stopped;
        @Override public void shutdown(){stopped=true;}
        @Override public java.util.List<Runnable> shutdownNow(){stopped=true;return Collections.emptyList();}
        @Override public boolean isShutdown(){return stopped;}
        @Override public boolean isTerminated(){return stopped;}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return stopped;}
        @Override public void execute(Runnable command){if(stopped)throw new java.util.concurrent.RejectedExecutionException();}
    }
}
