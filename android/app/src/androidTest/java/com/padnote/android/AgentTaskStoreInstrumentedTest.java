package com.padnote.android;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class AgentTaskStoreInstrumentedTest {
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
