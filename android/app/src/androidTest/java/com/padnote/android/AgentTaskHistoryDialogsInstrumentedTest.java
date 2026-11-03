package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isRoot;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.app.UiAutomation;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.text.Layout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class AgentTaskHistoryDialogsInstrumentedTest {
    @Test public void emptyTaskListOffersConnectPathWithoutSubmitting() throws Exception {
        runEmptyTaskFlow(false);
    }

    @Test public void emptyTaskListRoutesExistingUnverifiedProfileThroughCurrentGate() throws Exception {
        runEmptyTaskFlow(true);
    }

    @Test public void connectionTaskRouteKeepsImagePlacementCallbackAndSourceContext() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String fixtureId=java.util.UUID.randomUUID().toString();
        String preferencePrefix="ui-fixture-"+fixtureId+"-";
        File activityRoot=new File(target.getFilesDir(),"ui-fixtures/"+fixtureId);
        File taskDirectory=new File(new File(activityRoot,"cache"),"task-history");
        File prefsDirectory=new File(target.getApplicationInfo().dataDir,"shared_prefs");
        assertFalse("refuse a reused Activity fixture UUID",activityRoot.exists());
        assertFalse("refuse a reused task-cache UUID",taskDirectory.exists());
        assertNoPreferenceFiles(prefsDirectory,preferencePrefix);

        AgentConnectionStore.Config profile=new AgentConnectionStore.Config("profile-image-route-"+fixtureId,
                "合成电脑",AgentConnectionStore.Kind.HERMES,"https://fixture.invalid",
                "synthetic-token","synthetic-credential",AgentConnectionStore.Transport.BRIDGE,
                "fixture-bridge","fixture-instance","",3L,1700000000000L,
                Arrays.asList("run_submission","run_status"));
        AgentConnectionStore connections=isolatedStore(Collections.singletonList(profile));
        NoOpExecutor worker=new NoOpExecutor();
        ActivityScenario<IsolatedLibraryRestoreActivity> scenario=null;
        AtomicReference<AgentTaskStore> taskStore=new AtomicReference<>();
        AtomicInteger placementCalls=new AtomicInteger();
        AtomicReference<AgentTaskStore.Task> placedTask=new AtomicReference<>();
        AtomicReference<AgentTaskStore.Artifact> placedArtifact=new AtomicReference<>();
        AtomicReference<String> seededClientTaskId=new AtomicReference<>();
        String noteId="source-note-"+fixtureId;
        long noteRevision=37L;
        try {
            scenario=launch(target,fixtureId);
            scenario.onActivity(activity->{
                assertEquals("task cache belongs to the isolated Activity fixture",
                        taskDirectory.getAbsolutePath(),new File(activity.getCacheDir(),"task-history").getAbsolutePath());
                assertFalse("isolated task cache starts empty",taskDirectory.exists());
                AgentTaskStore tasks=new AgentTaskStore(taskDirectory);taskStore.set(tasks);
                try {
                    AgentTaskStore.Task draft=tasks.create(profile,"Synthetic image result",
                            "Synthetic-only result",noteId,noteRevision,null);
                    seededClientTaskId.set(draft.clientTaskId);
                    AgentTaskStore.Artifact artifact=new AgentTaskStore.Artifact("fixture-image",
                            "graph.png","image/png",128,
                            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
                    assertTrue("persist a completed synthetic PNG task",
                            tasks.applySubmission(draft.clientTaskId,profile.id,profile.revision,
                                    "fixture-remote-task",AgentTaskStore.Status.COMPLETED,"","",
                                    Collections.singletonList(artifact),null));
                } catch(Exception error){throw new AssertionError("unable to seed isolated task",error);}

                AgentTaskDialogs.ArtifactSaveLauncher save=(task,artifact)->{};
                AgentConnectionDialogs.TaskDialogsFactory factory=()->{
                    AgentTaskDialogs dialogs=new AgentTaskDialogs(activity,connections,worker,save,tasks,
                            VideoTaskDialogs::new,null,AgentTaskClient::new,
                            new AgentTaskDialogs.TaskConnectionSource(){
                                @Override public List<AgentConnectionStore.Config> list(){return Collections.singletonList(profile);}
                                @Override public AgentConnectionStore.Config get(String id){
                                    return profile.id.equals(id)?profile:null;
                                }
                            });
                    dialogs.setArtifactImagePlacementLauncher((task,artifact)->{
                        placementCalls.incrementAndGet();placedTask.set(task);placedArtifact.set(artifact);
                    });
                    return dialogs;
                };
                new AgentConnectionDialogs(activity,connections,worker,()->{},null,save,factory).show();
            });

            onView(withText("查看电脑任务")).inRoot(isDialog()).perform(click());
            onView(withText(containsString("Synthetic image result"))).inRoot(isDialog()).perform(click());
            onView(withText(containsString("放回纸面 · graph.png"))).inRoot(isDialog())
                    .perform(scrollTo(),click());
            assertEquals("the injected placement callback runs once",1,placementCalls.get());
            assertEquals(noteId,placedTask.get().noteId);
            assertEquals(noteRevision,placedTask.get().noteRevision);
            assertEquals(seededClientTaskId.get(),placedTask.get().clientTaskId);
            assertEquals("fixture-image",placedArtifact.get().id);
            assertEquals(1,taskStore.get().list().size());
            assertEquals(noteId,taskStore.get().list().get(0).noteId);
        } finally {
            worker.shutdownNow();
            try{if(scenario!=null)scenario.close();}
            finally{cleanupOwnedFixture(target,activityRoot,taskDirectory,prefsDirectory,preferencePrefix);}
        }
    }

    @Test public void resultActionsStayNearCollapsedAnswerAndHistoryShowsDeliveryCues() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String fixtureId=java.util.UUID.randomUUID().toString();
        String preferencePrefix="ui-fixture-"+fixtureId+"-";
        File activityRoot=new File(target.getFilesDir(),"ui-fixtures/"+fixtureId);
        File taskDirectory=new File(activityRoot,"cache/task-history");
        File prefsDirectory=new File(target.getApplicationInfo().dataDir,"shared_prefs");
        assertFalse("refuse reused Activity fixture UUID",activityRoot.exists());
        assertFalse("refuse reused task cache",taskDirectory.exists());
        assertNoPreferenceFiles(prefsDirectory,preferencePrefix);

        Map<String,Boolean> features=new HashMap<>();
        features.put("run_submission",true);features.put("run_status",true);
        features.put("delivery_contract_v1",true);
        AgentConnectionStore.Config profile=new AgentConnectionStore.Config(
                "result-layout-"+fixtureId,"Synthetic result Bridge",AgentConnectionStore.Kind.HERMES,
                "https://result-layout.example.test/v1","synthetic-token","credential-ref",
                AgentConnectionStore.Transport.BRIDGE,"bridge-result-layout","instance-result-layout",
                "",7L,System.currentTimeMillis(),Arrays.asList("run_submission","run_status"),features);
        String noteId="synthetic-note-"+fixtureId;
        String answerMarker="ANSWER_VISIBLE_ONCE_"+fixtureId;
        String technicalMarker="TECHNICAL_INPUT_ONLY_"+fixtureId;
        StringBuilder longAnswer=new StringBuilder(answerMarker);
        for(int i=1;i<=24;i++)longAnswer.append("\nSynthetic answer line ").append(i);
        AgentTaskStore store=new AgentTaskStore(taskDirectory);
        AgentTaskStore.Task parent;
        AgentTaskStore.Task child;
        try{
            AgentTaskStore.Task draft=store.create(profile,"Synthetic missing-file parent",
                    "synthetic parent input",noteId,12L,null,"",Collections.singletonList("image"));
            AgentDelivery.Summary missing=AgentDelivery.forCurrentTask(draft.expectedOutputs,
                    AgentTaskStore.Status.COMPLETED,Collections.emptyList());
            assertEquals(AgentDelivery.Status.MISSING,missing.status);
            assertTrue(store.applySubmission(draft.clientTaskId,profile.id,profile.revision,
                    "remote-parent-"+fixtureId,AgentTaskStore.Status.COMPLETED,
                    "remote-parent-"+fixtureId,"",Collections.emptyList(),missing));
            AgentTaskClient.RemoteStatus parentStatus=new AgentTaskClient.RemoteStatus(
                    AgentTaskStore.Status.COMPLETED,"Parent result","","","","",
                    Collections.emptyList(),"remote-parent-"+fixtureId,"",true,"","",missing);
            assertTrue(store.applyStatus(draft.clientTaskId,profile.id,profile.revision,
                    "remote-parent-"+fixtureId,parentStatus));
            parent=store.get(draft.clientTaskId);
            child=store.createFollowup(parent,profile,technicalMarker,Collections.singletonList("image"));
            AgentTaskStore.Artifact artifact=new AgentTaskStore.Artifact("synthetic-image-"+fixtureId,
                    "graph.png","image/png",128,
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            AgentDelivery.Summary delivered=AgentDelivery.forCurrentTask(child.expectedOutputs,
                    AgentTaskStore.Status.COMPLETED,Collections.singletonList(artifact));
            assertEquals(AgentDelivery.Status.DELIVERED,delivered.status);
            assertTrue(store.applySubmission(child.clientTaskId,profile.id,profile.revision,
                    "remote-child-"+fixtureId,AgentTaskStore.Status.COMPLETED,
                    "remote-parent-"+fixtureId,"remote-parent-"+fixtureId,
                    Collections.singletonList(artifact),delivered));
            AgentTaskClient.RemoteStatus acceptedAnswer=new AgentTaskClient.RemoteStatus(
                    AgentTaskStore.Status.COMPLETED,longAnswer.toString(),"","","","",
                    Collections.singletonList(artifact),"remote-parent-"+fixtureId,
                    "remote-parent-"+fixtureId,false,"","",delivered);
            assertTrue("backfill the accepted answer once into the legacy empty terminal snapshot",
                    store.applyStatus(child.clientTaskId,profile.id,profile.revision,
                            "remote-child-"+fixtureId,acceptedAnswer));
            child=store.get(child.clientTaskId);
            assertEquals("fixture contains the long accepted answer before opening UI",
                    longAnswer.toString(),child.output);
            assertEquals("fixture contains exactly one delivered image",1,child.artifacts.size());
            assertEquals(AgentDelivery.Status.DELIVERED,child.delivery.status);
        }catch(Exception error){throw new AssertionError("prepare isolated result-layout task history",error);}

        ActivityScenario<IsolatedLibraryRestoreActivity> scenario=null;
        NoOpExecutor worker=new NoOpExecutor();
        AtomicInteger appendCalls=new AtomicInteger(),saveCalls=new AtomicInteger(),placeCalls=new AtomicInteger();
        String childId=child.clientTaskId;
        String parentId=parent.clientTaskId;
        try{
            scenario=launch(target,fixtureId);
            scenario.onActivity(activity->{
                AgentTaskDialogs.TaskConnectionSource source=new AgentTaskDialogs.TaskConnectionSource(){
                    @Override public List<AgentConnectionStore.Config> list(){return Collections.singletonList(profile);}
                    @Override public AgentConnectionStore.Config get(String id){return profile.id.equals(id)?profile:null;}
                };
                AgentTaskDialogs dialogs=new AgentTaskDialogs(activity,isolatedStore(Collections.emptyList()),
                        worker,(task,artifact)->saveCalls.incrementAndGet(),store,
                        VideoTaskDialogs::new,note->true,AgentTaskClient::new,source);
                dialogs.setResultAppender(id->{assertEquals(childId,id);appendCalls.incrementAndGet();});
                dialogs.setArtifactImagePlacementLauncher((task,artifact)->{
                    assertEquals(childId,task.clientTaskId);assertEquals("graph.png",artifact.name);
                    placeCalls.incrementAndGet();
                });
                dialogs.showForNote(noteId);
            });

            String missingCue=target.getString(R.string.flow_history_delivery_missing,
                    target.getString(R.string.flow_delivery_kind_image));
            String deliveredCue=target.getString(R.string.flow_history_delivery_delivered,
                    target.getString(R.string.flow_delivery_kind_image));
            onView(withText(containsString(missingCue))).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(containsString(deliveredCue))).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(allOf(containsString("Synthetic missing-file parent"),
                    containsString(missingCue)))).inRoot(isDialog()).perform(click());
            onView(withText(containsString(deliveredCue))).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(target.getString(R.string.flow_close))).inRoot(isDialog()).perform(click());

            scenario.onActivity(activity->{
                AgentTaskDialogs.TaskConnectionSource source=new AgentTaskDialogs.TaskConnectionSource(){
                    @Override public List<AgentConnectionStore.Config> list(){return Collections.singletonList(profile);}
                    @Override public AgentConnectionStore.Config get(String id){return profile.id.equals(id)?profile:null;}
                };
                AgentTaskDialogs dialogs=new AgentTaskDialogs(activity,isolatedStore(Collections.emptyList()),
                        worker,(task,artifact)->saveCalls.incrementAndGet(),store,
                        VideoTaskDialogs::new,note->true,AgentTaskClient::new,source);
                dialogs.setResultAppender(id->{assertEquals(childId,id);appendCalls.incrementAndGet();});
                dialogs.setArtifactImagePlacementLauncher((task,artifact)->{
                    assertEquals(childId,task.clientTaskId);assertEquals("graph.png",artifact.name);
                    placeCalls.incrementAndGet();
                });
                dialogs.showDetail(childId);
            });

            String placeLabel=target.getString(R.string.agent_image_place_on_paper,"graph.png");
            String saveLabel=target.getString(R.string.flow_save_artifact,"graph.png","128 B");
            String appendLabel=target.getString(R.string.flow_append_result_to_note);
            String sourceLabel=target.getString(R.string.flow_return_to_note);
            String technicalLabel=target.getString(R.string.flow_expand_task_details);
            onView(withText(containsString(answerMarker))).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(isRoot()).inRoot(isDialog()).perform(assertVisibleTextCount(answerMarker,1));
            onView(isRoot()).inRoot(isDialog()).perform(assertTextMaxLines(answerMarker,8));
            onView(withText(target.getString(R.string.flow_expand_full_result))).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(placeLabel)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(saveLabel)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(appendLabel)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(sourceLabel)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(isRoot()).inRoot(isDialog()).perform(assertTextOrder(placeLabel,technicalLabel));
            onView(isRoot()).inRoot(isDialog()).perform(assertTextOrder(saveLabel,technicalLabel));
            onView(isRoot()).inRoot(isDialog()).perform(assertTextOrder(appendLabel,technicalLabel));
            onView(isRoot()).inRoot(isDialog()).perform(assertTextOrder(sourceLabel,technicalLabel));
            assertEquals("visibility does not invoke the placement callback",0,placeCalls.get());
            assertEquals("visibility does not invoke the save callback",0,saveCalls.get());
            assertEquals("visibility does not append the answer",0,appendCalls.get());

            onView(withText(target.getString(R.string.flow_expand_task_details))).inRoot(isDialog()).perform(click());
            onView(withText(containsString(technicalMarker))).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(containsString(answerMarker))).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(isRoot()).inRoot(isDialog()).perform(assertVisibleTextCount(answerMarker,1));
            onView(withText(target.getString(R.string.flow_expand_full_result))).inRoot(isDialog()).perform(click());
            onView(isRoot()).inRoot(isDialog()).perform(assertTextMaxLines(answerMarker,Integer.MAX_VALUE));
            assertEquals("one child task remains linked to its parent",1,store.children(parentId).size());
            assertEquals("the child still carries exactly one returned image artifact",1,
                    store.get(childId).artifacts.size());
            onView(withText(target.getString(R.string.flow_close))).inRoot(isDialog()).perform(click());
        }finally{
            worker.shutdownNow();
            try{if(scenario!=null)scenario.close();}
            finally{cleanupOwnedFixture(target,activityRoot,taskDirectory,prefsDirectory,preferencePrefix);}
        }
    }

    private void runEmptyTaskFlow(boolean hasProfile) throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String fixtureId = java.util.UUID.randomUUID().toString();
        String preferencePrefix = "ui-fixture-" + fixtureId + "-";
        File activityRoot = new File(target.getFilesDir(), "ui-fixtures/" + fixtureId);
        File screenshotRoot = new File(target.getFilesDir(), "ui-fixtures/task-history-" + fixtureId);
        File taskDirectory = new File(new File(activityRoot, "cache"), "task-history");
        File prefsDirectory = new File(target.getApplicationInfo().dataDir, "shared_prefs");
        assertFalse("refuse a reused Activity fixture UUID", activityRoot.exists());
        assertFalse("refuse a reused screenshot UUID namespace", screenshotRoot.exists());
        assertFalse("refuse a reused task-cache UUID", taskDirectory.exists());
        assertNoPreferenceFiles(prefsDirectory, preferencePrefix);
        ActivityScenario<IsolatedLibraryRestoreActivity> scenario = null;
        NoOpExecutor worker = new NoOpExecutor();
        AtomicReference<AgentTaskStore> taskStore = new AtomicReference<>();
        AtomicReference<AgentConnectionStore> connectionStore = new AtomicReference<>();
        try {
            scenario = launch(target, fixtureId);
            List<AgentConnectionStore.Config> profiles = hasProfile
                    ? Collections.singletonList(unverifiedProfile()) : Collections.emptyList();
            connectionStore.set(isolatedStore(profiles));
            scenario.onActivity(activity -> {
                assertEquals("Activity-owned fixture root uses this test UUID",
                        activityRoot.getAbsolutePath(), activity.fixtureRoot().getAbsolutePath());
                assertEquals("task store is under the isolated Activity cache",
                        taskDirectory.getAbsolutePath(),
                        new File(activity.getCacheDir(), "task-history").getAbsolutePath());
                assertFalse("isolated task cache starts empty", taskDirectory.exists());
                taskStore.set(new AgentTaskStore(taskDirectory));
                new AgentTaskDialogs(activity, connectionStore.get(), worker, null,
                        taskStore.get()).showList();
            });

            onView(withText(containsString("还没有电脑任务"))).inRoot(isDialog())
                    .check(matches(isDisplayed()));
            AtomicReference<String> emptyBeforeCapture = new AtomicReference<>();
            onView(withText(containsString("还没有电脑任务"))).inRoot(isDialog())
                    .perform(awaitStableTextGeometry("还没有电脑任务", emptyBeforeCapture));
            capture(scenario, target, fixtureId, screenshotRoot, hasProfile
                    ? "empty-existing-profile.png" : "empty-no-profile.png");
            AtomicReference<String> emptyAfterCapture = new AtomicReference<>();
            onView(withText(containsString("还没有电脑任务"))).inRoot(isDialog())
                    .perform(awaitStableTextGeometry("还没有电脑任务", emptyAfterCapture));
            assertEquals("empty-state geometry remained stable during screenshot capture",
                    emptyBeforeCapture.get(), emptyAfterCapture.get());
            onView(withText("选择或添加连接")).inRoot(isDialog()).perform(click());
            onView(withText("电脑 Agent")).inRoot(isDialog()).check(matches(isDisplayed()));
            assertTrue("opening the empty-state route never creates a task",
                    taskStore.get().list().isEmpty());
            assertEquals("no connection action is auto-submitted", 0, worker.executionCount.get());

            if (!hasProfile) {
                onView(withText("＋ 手动添加连接")).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText("粘贴连接助手配对内容")).inRoot(isDialog())
                        .check(matches(isDisplayed()));
                onView(withText("关闭")).inRoot(isDialog()).perform(click());
            } else {
                AgentConnectionStore.Config profile = profiles.get(0);
                String profileRow = "★ " + AgentTaskDialogs.destinationLabel(profile) +
                        " · 配置不完整";
                onView(withText(profileRow)).inRoot(isDialog()).perform(click());
                onView(withText("连接操作")).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText("测试连接")).inRoot(isDialog()).check(matches(isDisplayed()));
                assertTextAbsent("发送文本任务");
                onView(withText("返回")).inRoot(isDialog()).perform(click());
                onView(withText("关闭")).inRoot(isDialog()).perform(click());
            }
            assertTrue("routing never creates or sends a task", taskStore.get().list().isEmpty());
            assertEquals(0, worker.executionCount.get());
            assertScreenshotFiles(screenshotRoot, hasProfile
                    ? Collections.singletonList("empty-existing-profile.png")
                    : Collections.singletonList("empty-no-profile.png"));
        } finally {
            worker.shutdownNow();
            try {
                if (scenario != null) scenario.close();
            } finally {
                cleanupOwnedFixture(target, activityRoot, taskDirectory, prefsDirectory,
                        preferencePrefix);
            }
        }
    }

    @Test public void taskHistoryShowsPersistedTargetInsteadOfCurrentProfileAndRedactsUrl() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String fixtureId = java.util.UUID.randomUUID().toString();
        String preferencePrefix = "ui-fixture-" + fixtureId + "-";
        File activityRoot = new File(target.getFilesDir(), "ui-fixtures/" + fixtureId);
        File screenshotRoot = new File(target.getFilesDir(), "ui-fixtures/task-history-" + fixtureId);
        File taskDirectory = new File(new File(activityRoot, "cache"), "task-history");
        File prefsDirectory = new File(target.getApplicationInfo().dataDir, "shared_prefs");
        assertFalse("refuse a reused Activity fixture UUID", activityRoot.exists());
        assertFalse("refuse a reused screenshot UUID namespace", screenshotRoot.exists());
        assertFalse("refuse a reused task-cache UUID", taskDirectory.exists());
        assertNoPreferenceFiles(prefsDirectory, preferencePrefix);
        ActivityScenario<IsolatedLibraryRestoreActivity> scenario = null;
        NoOpExecutor worker = new NoOpExecutor();
        AtomicReference<AgentTaskStore> taskStore = new AtomicReference<>();
        AtomicReference<AgentTaskDialogs> dialogs = new AtomicReference<>();
        AtomicInteger currentProfileReads = new AtomicInteger();
        try {
            scenario = launch(target, fixtureId);
            AgentConnectionStore.Config oldSnapshot = new AgentConnectionStore.Config(
                    "profile-history-old", "家里旧电脑", AgentConnectionStore.Kind.HERMES,
                    "https://url-user:token-secret@historical.example.test:8443/private/path?auth=query-secret#fragment-secret",
                    "synthetic-token", "credential-secret-ref", AgentConnectionStore.Transport.BRIDGE,
                    "bridge-original", "inst-original", "cert-not-displayed", 4L, 1700000000000L,
                    Collections.singletonList("run_submission"));
            AgentConnectionStore.Config currentProfile = new AgentConnectionStore.Config(
                    oldSnapshot.id, "当前已改名电脑", AgentConnectionStore.Kind.HERMES,
                    "https://current.example.test:9444/current", "synthetic-current-token",
                    "synthetic-current-ref", AgentConnectionStore.Transport.BRIDGE,
                    "bridge-current", "instance-current", 5L, 0L, Collections.emptyList());
            scenario.onActivity(activity -> {
                assertEquals("Activity-owned fixture root uses this test UUID",
                        activityRoot.getAbsolutePath(), activity.fixtureRoot().getAbsolutePath());
                assertEquals("task store is under the isolated Activity cache",
                        taskDirectory.getAbsolutePath(),
                        new File(activity.getCacheDir(), "task-history").getAbsolutePath());
                assertFalse("isolated task cache starts empty", taskDirectory.exists());
                AgentTaskStore created = new AgentTaskStore(taskDirectory);
                try {
                    AgentTaskStore.Task saved = created.create(oldSnapshot, "Historical task",
                            "synthetic prompt", "synthetic-note", 1L, null);
                    assertEquals("profile-history-old", saved.connectionId);
                } catch (Exception error) {
                    throw new AssertionError("unable to create synthetic history snapshot", error);
                }
                AgentTaskStore reopened = new AgentTaskStore(taskDirectory);
                taskStore.set(reopened);
                AgentTaskDialogs.TaskConnectionSource source = new AgentTaskDialogs.TaskConnectionSource() {
                    @Override public List<AgentConnectionStore.Config> list() {
                        currentProfileReads.incrementAndGet();
                        return Collections.singletonList(currentProfile);
                    }
                    @Override public AgentConnectionStore.Config get(String id) {
                        currentProfileReads.incrementAndGet();
                        return currentProfile;
                    }
                };
                dialogs.set(new AgentTaskDialogs(activity, isolatedStore(Collections.emptyList()),
                        worker, null, reopened, VideoTaskDialogs::new, null,
                        AgentTaskClient::new, source));
                dialogs.get().showList();
            });

            String expected = AgentTaskDestinationLabel.forTask(taskStore.get().list().get(0));
            AtomicReference<String> rowGeometryBefore = new AtomicReference<>();
            onView(withText(allOf(containsString("Historical task\n提交中/结果待确认\n目标："),
                    containsString("家里旧电脑 · Hermes"),
                    containsString("historical.example.test:8443"),
                    containsString("助手 bridge-original"),
                    containsString("实例 inst-original"), containsString(expected))))
                    .inRoot(isDialog()).perform(awaitStableTextGeometry(expected, rowGeometryBefore))
                    .check(matches(historyRowIsComplete(expected)));
            assertEquals("rendering a history row must not look up the current profile",
                    0, currentProfileReads.get());
            assertTextAbsent("url-user");
            assertTextAbsent("token-secret");
            assertTextAbsent("query-secret");
            assertTextAbsent("fragment-secret");
            assertTextAbsent("current.example.test");
            capture(scenario, target, fixtureId, screenshotRoot, "history-list.png");
            AtomicReference<String> rowGeometryAfter = new AtomicReference<>();
            onView(withText(allOf(containsString("Historical task\n提交中/结果待确认\n目标："),
                    containsString("家里旧电脑 · Hermes"),
                    containsString("historical.example.test:8443"),
                    containsString("助手 bridge-original"),
                    containsString("实例 inst-original"), containsString(expected))))
                    .inRoot(isDialog()).perform(awaitStableTextGeometry(expected, rowGeometryAfter))
                    .check(matches(historyRowIsComplete(expected)));
            assertEquals("history row geometry remained stable during screenshot capture",
                    rowGeometryBefore.get(), rowGeometryAfter.get());
            onView(withText(allOf(containsString("Historical task\n提交中/结果待确认\n目标："),
                    containsString(expected)))).inRoot(isDialog()).perform(click());
            AtomicReference<String> detailGeometryBefore = new AtomicReference<>();
            onView(withText(containsString("目标：" + expected))).inRoot(isDialog())
                    .check(matches(isDisplayed()))
                    .check(matches(targetIdentityLinesVisible("目标：" + expected)))
                    .perform(awaitStableTextGeometry("目标：" + expected, detailGeometryBefore));
            assertEquals("detail may check current eligibility once but displays the stored snapshot",
                    1, currentProfileReads.get());
            assertTextAbsent("url-user");
            assertTextAbsent("token-secret");
            assertTextAbsent("query-secret");
            assertTextAbsent("fragment-secret");
            assertTextAbsent("current.example.test");
            capture(scenario, target, fixtureId, screenshotRoot, "history-detail.png");
            AtomicReference<String> detailGeometryAfter = new AtomicReference<>();
            onView(withText(containsString("目标：" + expected))).inRoot(isDialog())
                    .check(matches(targetIdentityLinesVisible("目标：" + expected)))
                    .perform(awaitStableTextGeometry("目标：" + expected, detailGeometryAfter));
            assertEquals("history detail identity geometry remained stable during screenshot capture",
                    detailGeometryBefore.get(), detailGeometryAfter.get());
            onView(withText("关闭")).inRoot(isDialog()).perform(click());
            assertEquals(1, taskStore.get().list().size());
            assertScreenshotFiles(screenshotRoot,
                    java.util.Arrays.asList("history-list.png", "history-detail.png"));
        } finally {
            worker.shutdownNow();
            try {
                if (scenario != null) scenario.close();
            } finally {
                cleanupOwnedFixture(target, activityRoot, taskDirectory, prefsDirectory,
                        preferencePrefix);
            }
        }
    }

    @Test public void uncertainBridgeTaskHasExplicitSameTaskRecoveryAndNoFalseRefresh() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String fixtureId=java.util.UUID.randomUUID().toString();
        File activityRoot=new File(target.getFilesDir(),"ui-fixtures/"+fixtureId);
        File taskDirectory=new File(activityRoot,"cache/task-history");
        File prefsDirectory=new File(target.getApplicationInfo().dataDir,"shared_prefs");
        String preferencePrefix="ui-fixture-"+fixtureId+"-";
        assertFalse("refuse reused fixture UUID",activityRoot.exists());
        ActivityScenario<IsolatedLibraryRestoreActivity> scenario=null;
        NoOpExecutor worker=new NoOpExecutor();
        AgentConnectionStore.Config profile=new AgentConnectionStore.Config(
                "bridge-recovery-"+fixtureId,"合成 Bridge",AgentConnectionStore.Kind.HERMES,
                "https://bridge.example.test/v1","synthetic-token","credential-ref",
                AgentConnectionStore.Transport.BRIDGE,"bridge-fixture","instance-fixture",
                "",7L,System.currentTimeMillis(),Arrays.asList("run_submission","run_status","task_bundle"));
        AtomicReference<AgentTaskStore> storeRef=new AtomicReference<>();
        AtomicReference<AgentTaskStore.Task> originalRef=new AtomicReference<>();
        AtomicReference<String> postedBody=new AtomicReference<>();
        AtomicReference<String> postedKey=new AtomicReference<>();
        AtomicInteger posts=new AtomicInteger(),gets=new AtomicInteger();
        CountDownLatch posted=new CountDownLatch(1);
        CountDownLatch statusQueried=new CountDownLatch(1);
        AgentTaskClient.Factory clients=()->new AgentTaskClient(request->{
            if("POST".equals(request.method)){
                posts.incrementAndGet();
                postedBody.set(new String(request.body,StandardCharsets.UTF_8));
                postedKey.set(request.headers.get("Idempotency-Key"));
                posted.countDown();
                return response(request,202,"{\"task_id\":\"remote-task-fixture\",\"status\":\"running\",\"instance_id\":\"instance-fixture\",\"conversation_id\":\"remote-task-fixture\"}");
            }
            gets.incrementAndGet();
            statusQueried.countDown();
            return response(request,200,"{\"task_id\":\"remote-task-fixture\",\"status\":\"running\",\"instance_id\":\"instance-fixture\"}");
        });
        try {
            scenario=launch(target,fixtureId);
            scenario.onActivity(activity->{
                AgentTaskStore store=new AgentTaskStore(taskDirectory);
                try{
                    AgentTaskStore.Task original=store.create(profile,"Synthetic paper task",
                            "synthetic prompt","synthetic-note-"+fixtureId,1L,null);
                    store.markSubmissionAttempted(original.clientTaskId);
                    store.markError(original.clientTaskId,original.updatedAt,"synthetic lost response");
                    originalRef.set(store.get(original.clientTaskId));
                }catch(Exception error){throw new AssertionError("prepare isolated unknown task",error);}
                storeRef.set(store);
                AgentTaskDialogs.TaskConnectionSource source=new AgentTaskDialogs.TaskConnectionSource(){
                    @Override public List<AgentConnectionStore.Config> list(){return Collections.singletonList(profile);}
                    @Override public AgentConnectionStore.Config get(String id){return profile.id.equals(id)?profile:null;}
                };
                new AgentTaskDialogs(activity,isolatedStore(Collections.emptyList()),worker,null,store,
                        VideoTaskDialogs::new,null,clients,source).showDetail(originalRef.get().clientTaskId);
            });

            onView(withText(containsString("结果未确认"))).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("确认电脑是否收到")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("刷新")).inRoot(isDialog()).check(doesNotExist());
            assertEquals("an unresolved task is not automatically resent",0,posts.get());
            assertEquals("without a remote ID no status request is attempted",0,gets.get());

            onView(withText("确认电脑是否收到")).inRoot(isDialog()).perform(click());
            onView(withText(containsString("不会另建任务"))).inRoot(isDialog())
                    .check(matches(isDisplayed()));
            onView(withText("继续找回")).inRoot(isDialog()).perform(click());
            assertTrue("explicit user confirmation starts same-task recovery",posted.await(5,TimeUnit.SECONDS));
            long deadline=SystemClock.uptimeMillis()+5000L;
            AgentTaskStore.Task recovered=null;
            while(SystemClock.uptimeMillis()<deadline){
                recovered=storeRef.get().get(originalRef.get().clientTaskId);
                if(recovered!=null&&!recovered.remoteTaskId.isEmpty())break;
                SystemClock.sleep(20L);
            }
            assertEquals("same persisted payload is used",originalRef.get().submissionJson,postedBody.get());
            assertEquals("Bridge idempotency key remains the original task ID",
                    originalRef.get().clientTaskId,postedKey.get());
            assertEquals("recovery retrieves the existing task receipt", "remote-task-fixture",
                    recovered==null?"":recovered.remoteTaskId);
            assertEquals("one explicit recovery request only",1,posts.get());
            assertTrue("normal remote status polling resumes after receipt",
                    statusQueried.await(5,TimeUnit.SECONDS));
            onView(withText("关闭")).inRoot(isDialog()).perform(click());
        } finally {
            worker.shutdownNow();
            try{if(scenario!=null)scenario.close();}
            finally{cleanupOwnedFixture(target,activityRoot,taskDirectory,prefsDirectory,preferencePrefix);}
        }
    }

    @Test public void completedMissingImageCanBeExplicitlyRefreshedIntoRepairWithoutChangingParentId() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String fixtureId=java.util.UUID.randomUUID().toString();
        File activityRoot=new File(target.getFilesDir(),"ui-fixtures/"+fixtureId);
        File taskDirectory=new File(activityRoot,"cache/task-history");
        File prefsDirectory=new File(target.getApplicationInfo().dataDir,"shared_prefs");
        String preferencePrefix="ui-fixture-"+fixtureId+"-";
        assertFalse("refuse reused Activity fixture UUID",activityRoot.exists());
        java.util.Map<String,Boolean> features=new java.util.LinkedHashMap<>();
        features.put("run_submission",true);features.put("run_status",true);
        features.put("delivery_contract_v1",true);
        AgentConnectionStore.Config profile=new AgentConnectionStore.Config(
                "completed-refresh-"+fixtureId,"Synthetic refresh Bridge",AgentConnectionStore.Kind.HERMES,
                "https://bridge.example.test/v1","synthetic-token","credential-refresh-"+fixtureId,
                AgentConnectionStore.Transport.BRIDGE,"bridge-refresh-"+fixtureId,"instance-refresh-"+fixtureId,
                "",7L,20L,Arrays.asList("run_submission","run_status"),features);
        AtomicReference<AgentTaskStore> storeRef=new AtomicReference<>();
        AtomicReference<AgentTaskStore.Task> originalRef=new AtomicReference<>();
        AtomicInteger gets=new AtomicInteger(),posts=new AtomicInteger();
        CountDownLatch statusRead=new CountDownLatch(1);
        AgentTaskClient.Factory clients=()->new AgentTaskClient(request->{
            if(!"GET".equals(request.method)){
                posts.incrementAndGet();
                return response(request,500,"{\"error\":{\"message\":\"unexpected method\"}}");
            }
            gets.incrementAndGet();
            statusRead.countDown();
            String remote="remote-missing-image";
            org.json.JSONObject artifact=new org.json.JSONObject().put("id","late-image")
                    .put("name","late.png").put("media_type","image/png")
                    .put("size_bytes",8L).put("sha256","a".repeat(64));
            org.json.JSONObject delivery=new org.json.JSONObject().put("status","delivered")
                    .put("expected",new org.json.JSONArray(Collections.singletonList("image")))
                    .put("missing",new org.json.JSONArray()).put("artifact_count",1)
                    .put("reported_by_server",true);
            org.json.JSONObject body=new org.json.JSONObject().put("task_id",remote)
                    .put("status","completed").put("instance_id",profile.instanceId)
                    .put("conversation_id",remote).put("parent_task_id","")
                    .put("followup_available",true).put("followup_reason","")
                    .put("output","late changed output").put("error","late changed error")
                    .put("approval",new org.json.JSONObject().put("approval_id","late-approval"))
                    .put("artifacts",new org.json.JSONArray().put(artifact)).put("delivery",delivery);
            return response(request,200,body.toString());
        });
        ActivityScenario<IsolatedLibraryRestoreActivity> scenario=null;
        NoOpExecutor worker=new NoOpExecutor();
        try{
            scenario=launch(target,fixtureId);
            scenario.onActivity(activity->{
                AgentTaskStore store=new AgentTaskStore(taskDirectory);
                try{
                    AgentTaskStore.Task draft=store.create(profile,"Missing image task",
                            "C42_FIXTURE:missing-image","synthetic-note-"+fixtureId,9L,
                            null,"",Collections.singletonList("image"));
                    AgentDelivery.Summary missing=AgentDelivery.forCurrentTask(draft.expectedOutputs,
                            AgentTaskStore.Status.COMPLETED,Collections.emptyList());
                    assertEquals(AgentDelivery.Status.MISSING,missing.status);
                    assertTrue(store.applySubmission(draft.clientTaskId,profile.id,profile.revision,
                            "remote-missing-image",AgentTaskStore.Status.COMPLETED,"","",
                            Collections.emptyList(),missing));
                    assertTrue("set the accepted terminal text before testing GET-only metadata refresh",
                            store.applyStatus(draft.clientTaskId,profile.id,profile.revision,
                                    "remote-missing-image",new AgentTaskClient.RemoteStatus(
                                            AgentTaskStore.Status.COMPLETED,"original accepted text","","","","",
                                            Collections.emptyList(),"","",false,"", "",missing)));
                    originalRef.set(store.get(draft.clientTaskId));
                }catch(Exception error){throw new AssertionError("prepare terminal missing-image task",error);}
                storeRef.set(store);
                AgentTaskDialogs.TaskConnectionSource source=new AgentTaskDialogs.TaskConnectionSource(){
                    @Override public List<AgentConnectionStore.Config> list(){return Collections.singletonList(profile);}
                    @Override public AgentConnectionStore.Config get(String id){return profile.id.equals(id)?profile:null;}
                };
                new AgentTaskDialogs(activity,isolatedStore(Collections.emptyList()),worker,null,store,
                        VideoTaskDialogs::new,null,clients,source).showDetail(originalRef.get().clientTaskId);
            });

            assertEquals(AgentTaskStore.Status.COMPLETED,originalRef.get().status);
            assertEquals(AgentDelivery.Status.MISSING,originalRef.get().delivery.status);
            assertFalse(originalRef.get().followupAvailable);
            assertTrue("completed Bridge receipt exposes an explicit status read",
                    AgentTaskDialogs.canRefreshRemoteStatus(originalRef.get()));
            onView(withText("刷新")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("继续补齐文件")).inRoot(isDialog()).check(matches(not(isDisplayed())));
            assertEquals("opening a completed task does not poll or submit",0,gets.get());
            assertEquals(0,posts.get());

            onView(withText("刷新")).inRoot(isDialog()).perform(click());
            assertTrue("one explicit click makes one bounded status read",statusRead.await(5,TimeUnit.SECONDS));
            long deadline=SystemClock.uptimeMillis()+5000L;
            AgentTaskStore.Task refreshed=null;
            while(SystemClock.uptimeMillis()<deadline){
                refreshed=storeRef.get().get(originalRef.get().clientTaskId);
                if(refreshed!=null&&refreshed.followupAvailable)break;
                SystemClock.sleep(25L);
            }
            assertTrue("GET supplies the missing session metadata",refreshed!=null);
            assertTrue(refreshed.followupAvailable);
            assertEquals(originalRef.get().clientTaskId,refreshed.clientTaskId);
            assertEquals("remote-missing-image",refreshed.remoteTaskId);
            assertEquals(AgentTaskStore.Status.COMPLETED,refreshed.status);
            assertEquals("remote-missing-image",refreshed.conversationId);
            assertEquals("terminal output stays frozen",originalRef.get().output,refreshed.output);
            assertEquals("terminal error stays frozen",originalRef.get().error,refreshed.error);
            assertTrue("late artifacts do not replace the terminal snapshot",refreshed.artifacts.isEmpty());
            assertEquals(AgentDelivery.Status.MISSING,refreshed.delivery.status);
            assertEquals(Collections.singletonList("image"),refreshed.expectedOutputs);
            assertEquals("refresh is GET-only",1,gets.get());
            assertEquals("refresh never submits or creates a task",0,posts.get());
            assertTrue(storeRef.get().children(originalRef.get().clientTaskId).isEmpty());
            onView(withText("继续补齐文件")).inRoot(isDialog()).check(matches(isDisplayed())).perform(click());
            onView(withText("继续补齐缺少的文件")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("取消")).inRoot(isDialog()).perform(click());
            assertTrue("canceling the repair prompt creates no child",
                    storeRef.get().children(originalRef.get().clientTaskId).isEmpty());
            AgentTaskStore.Task finalParent=storeRef.get().get(originalRef.get().clientTaskId);
            assertEquals(originalRef.get().clientTaskId,finalParent.clientTaskId);
            assertEquals(AgentTaskStore.Status.COMPLETED,finalParent.status);
            assertEquals(AgentDelivery.Status.MISSING,finalParent.delivery.status);
            onView(withText("关闭")).inRoot(isDialog()).perform(click());
        }finally{
            worker.shutdownNow();
            try{if(scenario!=null)scenario.close();}
            finally{cleanupOwnedFixture(target,activityRoot,taskDirectory,prefsDirectory,preferencePrefix);}
        }
    }

    @Test public void confirmationRechecksKnownBridgeReceiptAndDirectAttemptBeforeSending() throws Exception {
        runStaleRecoveryConfirmation(true);
        runStaleRecoveryConfirmation(false);
    }

    @Test public void submissionWorkerRechecksAReceiptThatArrivesAfterConfirmation() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String fixtureId=java.util.UUID.randomUUID().toString();
        File ownedRoot=new File(target.getFilesDir(),"ui-fixtures/"+fixtureId);
        File taskDirectory=new File(ownedRoot,"cache/task-history");
        assertFalse("refuse reused fixture UUID",ownedRoot.exists());
        AgentConnectionStore.Config profile=new AgentConnectionStore.Config(
                "queued-recovery-"+fixtureId,"Synthetic Bridge",AgentConnectionStore.Kind.HERMES,
                "https://queued.example.test/v1","synthetic-token","credential-ref",
                AgentConnectionStore.Transport.BRIDGE,"bridge-fixture","instance-fixture","",
                11L,System.currentTimeMillis(),Arrays.asList("run_submission","run_status","task_bundle"));
        AgentTaskStore store=new AgentTaskStore(taskDirectory);
        AgentTaskStore.Task original=store.create(profile,"Queued synthetic recovery","synthetic prompt",
                "synthetic-note-"+fixtureId,1L,null);
        store.markSubmissionAttempted(original.clientTaskId);
        store.markError(original.clientTaskId,original.updatedAt,"synthetic timeout");
        original=store.get(original.clientTaskId);
        AgentTaskStore.Task confirmedSnapshot=original;
        AtomicInteger posts=new AtomicInteger();
        AgentTaskClient.Factory clients=()->new AgentTaskClient(request->{
            if("POST".equals(request.method))posts.incrementAndGet();
            return response(request,202,"{\"task_id\":\"should-not-send\",\"status\":\"running\",\"instance_id\":\"instance-fixture\"}");
        });
        AgentTaskDialogs.TaskConnectionSource source=new AgentTaskDialogs.TaskConnectionSource(){
            @Override public List<AgentConnectionStore.Config> list(){return Collections.singletonList(profile);}
            @Override public AgentConnectionStore.Config get(String id){return profile.id.equals(id)?profile:null;}
        };
        String blockerId="executor-blocker-"+fixtureId;
        CountDownLatch blockerStarted=new CountDownLatch(1),releaseBlocker=new CountDownLatch(1);
        try{
            assertTrue("reserve the single executor lane with an isolated blocker",
                    AgentTaskSubmissionExecutor.execute(blockerId,()->{
                        blockerStarted.countDown();
                        try{releaseBlocker.await(5,TimeUnit.SECONDS);}
                        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                    }));
            assertTrue("blocker owns the executor lane",blockerStarted.await(5,TimeUnit.SECONDS));
            assertTrue("queue same-task recovery behind the blocker",AgentTaskSubmissionExecutor.recoverSameTask(
                    confirmedSnapshot,true,store,source,clients,target.getApplicationContext()));
            assertTrue("a newer remote receipt wins before the queued worker runs",store.applySubmission(
                    original.clientTaskId,profile.id,profile.revision,"remote-arrived-first",
                    AgentTaskStore.Status.RUNNING,"remote-arrived-first",""));
            releaseBlocker.countDown();
            long deadline=SystemClock.uptimeMillis()+5000L;
            while(AgentTaskSubmissionExecutor.isInFlight(original.clientTaskId)&&
                    SystemClock.uptimeMillis()<deadline)SystemClock.sleep(20L);
            assertFalse("queued task attempt has finished",AgentTaskSubmissionExecutor.isInFlight(original.clientTaskId));
            assertEquals("executor's last-moment gate prevents a stale POST",0,posts.get());
            assertEquals("the receipt that arrived first remains stored","remote-arrived-first",
                    store.get(original.clientTaskId).remoteTaskId);
        }finally{
            releaseBlocker.countDown();
            long deadline=SystemClock.uptimeMillis()+5000L;
            while((AgentTaskSubmissionExecutor.isInFlight(blockerId)||
                    AgentTaskSubmissionExecutor.isInFlight(original.clientTaskId))&&
                    SystemClock.uptimeMillis()<deadline)SystemClock.sleep(20L);
            deleteTree(taskDirectory);
            deleteTree(ownedRoot);
        }
    }

    private void runStaleRecoveryConfirmation(boolean bridge) throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String fixtureId=java.util.UUID.randomUUID().toString();
        File activityRoot=new File(target.getFilesDir(),"ui-fixtures/"+fixtureId);
        File taskDirectory=new File(activityRoot,"cache/task-history");
        File prefsDirectory=new File(target.getApplicationInfo().dataDir,"shared_prefs");
        String preferencePrefix="ui-fixture-"+fixtureId+"-";
        assertFalse("refuse reused fixture UUID",activityRoot.exists());
        ActivityScenario<IsolatedLibraryRestoreActivity> scenario=null;
        NoOpExecutor worker=new NoOpExecutor();
        AgentConnectionStore.Transport transport=bridge?AgentConnectionStore.Transport.BRIDGE:
                AgentConnectionStore.Transport.DIRECT;
        AgentConnectionStore.Config profile=new AgentConnectionStore.Config(
                "stale-recovery-"+fixtureId,"Synthetic target",AgentConnectionStore.Kind.HERMES,
                "https://recovery.example.test/v1","synthetic-token","credential-ref",transport,
                bridge?"bridge-fixture":"","instance-fixture","",9L,System.currentTimeMillis(),
                Arrays.asList("run_submission","run_status","task_bundle"));
        AtomicReference<AgentTaskStore> storeRef=new AtomicReference<>();
        AtomicReference<AgentTaskStore.Task> taskRef=new AtomicReference<>();
        AtomicInteger postCount=new AtomicInteger();
        AgentTaskClient.Factory clients=()->new AgentTaskClient(request->{
            if("POST".equals(request.method))postCount.incrementAndGet();
            String body=bridge?"{\"task_id\":\"remote-existing\",\"status\":\"running\",\"instance_id\":\"instance-fixture\"}":
                    "{\"run_id\":\"remote-existing\",\"status\":\"running\"}";
            return response(request,"POST".equals(request.method)?202:200,body);
        });
        try{
            scenario=launch(target,fixtureId);
            scenario.onActivity(activity->{
                AgentTaskStore store=new AgentTaskStore(taskDirectory);
                try{
                    AgentTaskStore.Task task=store.create(profile,"Synthetic retry confirmation",
                            "synthetic prompt","synthetic-note-"+fixtureId,1L,null);
                    if(bridge){
                        store.markSubmissionAttempted(task.clientTaskId);
                        store.markError(task.clientTaskId,task.updatedAt,"synthetic unknown response");
                    }
                    taskRef.set(store.get(task.clientTaskId));
                }catch(Exception error){throw new AssertionError("prepare isolated stale confirmation",error);}
                storeRef.set(store);
                AgentTaskDialogs.TaskConnectionSource source=new AgentTaskDialogs.TaskConnectionSource(){
                    @Override public List<AgentConnectionStore.Config> list(){return Collections.singletonList(profile);}
                    @Override public AgentConnectionStore.Config get(String id){return profile.id.equals(id)?profile:null;}
                };
                new AgentTaskDialogs(activity,isolatedStore(Collections.emptyList()),worker,null,store,
                        VideoTaskDialogs::new,null,clients,source).showDetail(taskRef.get().clientTaskId);
            });

            String action=bridge?"确认电脑是否收到":"发送已保存任务";
            onView(withText(action)).inRoot(isDialog()).check(matches(isDisplayed())).perform(click());
            onView(withText(bridge?"继续找回":"现在发送")).inRoot(isDialog()).check(matches(isDisplayed()));
            scenario.onActivity(activity->{
                try{
                    if(bridge){
                        assertTrue("inject receipt while confirmation is open",storeRef.get().applySubmission(
                                taskRef.get().clientTaskId,profile.id,profile.revision,"remote-existing",
                                AgentTaskStore.Status.RUNNING,"remote-existing",""));
                    }else{
                        storeRef.get().markSubmissionAttempted(taskRef.get().clientTaskId);
                        AgentTaskStore.Task latest=storeRef.get().get(taskRef.get().clientTaskId);
                        storeRef.get().markError(latest.clientTaskId,latest.updatedAt,"synthetic late attempt");
                    }
                }catch(Exception error){throw new AssertionError("mutate UUID-owned task before confirmation",error);}
            });
            onView(withText(bridge?"继续找回":"现在发送")).inRoot(isDialog()).perform(click());
            SystemClock.sleep(250L);
            assertEquals("a stale confirmation must never POST",0,postCount.get());
            if(bridge){
                assertEquals("the independently received bridge receipt is preserved","remote-existing",
                        storeRef.get().get(taskRef.get().clientTaskId).remoteTaskId);
                onView(withText("确认电脑是否收到")).inRoot(isDialog()).check(doesNotExist());
                onView(withText("刷新")).inRoot(isDialog()).check(matches(isDisplayed()));
            }else{
                assertTrue("DIRECT attempt is no longer eligible for same-ID send",
                        storeRef.get().submissionAttemptState(taskRef.get().clientTaskId)==1);
                onView(withText("发送已保存任务")).inRoot(isDialog()).check(doesNotExist());
                onView(withText(containsString("结果未确认"))).inRoot(isDialog()).check(matches(isDisplayed()));
            }
            onView(withText("关闭")).inRoot(isDialog()).perform(click());
        }finally{
            worker.shutdownNow();
            try{if(scenario!=null)scenario.close();}
            finally{cleanupOwnedFixture(target,activityRoot,taskDirectory,prefsDirectory,preferencePrefix);}
        }
    }

    private static androidx.test.core.app.ActivityScenario<IsolatedLibraryRestoreActivity> launch(
            Context target, String fixtureId) {
        Intent intent = new Intent(target, IsolatedLibraryRestoreActivity.class)
                .putExtra("fixture_id", fixtureId);
        return androidx.test.core.app.ActivityScenario.launch(intent);
    }

    private static AgentHttpTransport.Response response(AgentHttpTransport.Request request,int status,
                                                        String body) {
        try {
            URL url=request.url;
            return new AgentHttpTransport.Response(status,url,url,body.getBytes(StandardCharsets.UTF_8),
                    Collections.emptyMap());
        } catch(Exception error){throw new AssertionError(error);}
    }

    private static AgentConnectionStore isolatedStore(List<AgentConnectionStore.Config> profiles) {
        org.json.JSONArray records = new org.json.JSONArray();
        try {
            for (AgentConnectionStore.Config profile : profiles) {
                records.put(new org.json.JSONObject().put("id", profile.id)
                        .put("name", profile.name).put("kind", profile.kind.name())
                        .put("endpoint", profile.endpoint).put("credentialRef", "")
                        .put("transport", profile.transport.name()).put("bridgeId", profile.bridgeId)
                        .put("instanceId", profile.instanceId).put("certSha256", "")
                        .put("revision", profile.revision).put("verifiedAt", profile.verifiedAt)
                        .put("capabilities", new org.json.JSONArray(profile.capabilities)));
            }
            String defaultId = profiles.isEmpty() ? "" : profiles.get(0).id;
            String registry = new org.json.JSONObject().put("schemaVersion", 2)
                    .put("defaultId", defaultId).put("connections", records).toString();
            return new AgentConnectionStore(new MemoryBackend(registry), () -> 1700000000000L);
        } catch (org.json.JSONException error) {
            throw new AssertionError(error);
        }
    }

    private static AgentConnectionStore.Config unverifiedProfile() {
        return new AgentConnectionStore.Config("profile-unverified-fixture", "合成未验证电脑",
                AgentConnectionStore.Kind.HERMES, "https://unverified.example.test:8342",
                "", "", AgentConnectionStore.Transport.DIRECT, "", "", "", 2L, 0L,
                Collections.emptyList());
    }

    private static org.hamcrest.Matcher<android.view.View> historyRowIsComplete(
            String expectedIdentity) {
        return new org.hamcrest.TypeSafeMatcher<android.view.View>() {
            @Override public void describeTo(Description description) {
                description.appendText("multiline history row with visible destination and final line");
            }
            @Override protected boolean matchesSafely(android.view.View view) {
                if (!(view instanceof TextView)) return false;
                TextView row = (TextView) view;
                Layout layout = row.getLayout();
                if (row.getMaxLines() < 3 || layout == null ||
                        layout.getLineCount() < 3) return false;
                for (int line = 0; line < layout.getLineCount(); line++) {
                    if (layout.getEllipsisCount(line) != 0) return false;
                }
                int lastLine = layout.getLineCount() - 1;
                return textLinesFullyVisible(row, expectedIdentity) &&
                        textLineRangeFullyVisible(row, lastLine, lastLine) &&
                        !row.getText().toString().contains("https://") &&
                        !row.getText().toString().contains("token-secret");
            }
        };
    }

    private static ViewAction awaitStableTextGeometry(String expected,
                                                      AtomicReference<String> geometryOut) {
        return new ViewAction() {
            @Override public org.hamcrest.Matcher<View> getConstraints() {
                return androidx.test.espresso.matcher.ViewMatchers
                        .isAssignableFrom(TextView.class);
            }
            @Override public String getDescription() {
                return "observe complete identity geometry stable for three samples";
            }
            @Override public void perform(UiController ui, View view) {
                if (!(view instanceof TextView)) throw new AssertionError("expected TextView");
                long deadline = SystemClock.uptimeMillis() + 1800L;
                String previous = null;
                int consecutiveMatches = 0;
                String current = null;
                while (SystemClock.uptimeMillis() < deadline) {
                    ui.loopMainThreadForAtLeast(16L);
                    current = textGeometry((TextView) view, expected);
                    if (current != null && current.equals(previous)) consecutiveMatches++;
                    else consecutiveMatches = 0;
                    if (consecutiveMatches >= 2) {
                        geometryOut.set(current);
                        System.out.println("TASK_HISTORY_STABLE_TEXT_GEOMETRY " + current);
                        return;
                    }
                    previous = current;
                }
                throw new AssertionError("full text geometry did not stabilize within the bound; " +
                        "last=" + current);
            }
        };
    }

    private static String textGeometry(TextView view, String expected) {
        if (!textLinesFullyVisible(view, expected)) return null;
        String text = view.getText().toString();
        Layout layout = view.getLayout();
        int start = text.indexOf(expected);
        int firstLine = layout.getLineForOffset(start);
        int lastLine = layout.getLineForOffset(start + expected.length() - 1);
        Rect visible = new Rect();
        if (!view.getLocalVisibleRect(visible)) return null;
        int[] screen = new int[2];
        view.getLocationOnScreen(screen);
        int firstTop = view.getCompoundPaddingTop() + layout.getLineTop(firstLine);
        int lastBottom = view.getCompoundPaddingTop() + layout.getLineBottom(lastLine);
        return "screen=" + screen[0] + "," + screen[1] + " size=" + view.getWidth() + "x" +
                view.getHeight() + " localViewport=" + visible + " identityLines=" +
                firstLine + ".." + lastLine + " identityBoundsY=" + firstTop + ".." +
                lastBottom + " scroll=" + view.getScrollX() + "," + view.getScrollY() +
                " padding=" + view.getCompoundPaddingLeft() + "," +
                view.getCompoundPaddingTop() + "," + view.getCompoundPaddingRight() + "," +
                view.getCompoundPaddingBottom();
    }

    private static org.hamcrest.Matcher<android.view.View> targetIdentityLinesVisible(
            String expectedIdentity) {
        return new org.hamcrest.TypeSafeMatcher<android.view.View>() {
            @Override public void describeTo(Description description) {
                description.appendText("all wrapped lines of the stored destination identity are visible");
            }
            @Override protected boolean matchesSafely(android.view.View view) {
                return view instanceof TextView &&
                        textLinesFullyVisible((TextView) view, expectedIdentity);
            }
        };
    }

    private static boolean textLinesFullyVisible(TextView view, String expected) {
        Layout layout = view.getLayout();
        String text = view.getText() == null ? "" : view.getText().toString();
        int start = text.indexOf(expected);
        if (layout == null || start < 0 || expected.isEmpty()) return false;
        int firstLine = layout.getLineForOffset(start);
        int lastLine = layout.getLineForOffset(start + expected.length() - 1);
        for (int line = firstLine; line <= lastLine; line++) {
            if (layout.getEllipsisCount(line) != 0 ||
                    !textLineRangeFullyVisible(view, line, line)) return false;
        }
        return true;
    }

    private static boolean textLineRangeFullyVisible(TextView view, int firstLine, int lastLine) {
        Layout layout = view.getLayout();
        if (layout == null || firstLine < 0 || lastLine >= layout.getLineCount()) return false;
        Rect visible = new Rect();
        if (!view.getLocalVisibleRect(visible)) return false;
        for (int line = firstLine; line <= lastLine; line++) {
            float lineLeft = view.getCompoundPaddingLeft() +
                    Math.min(layout.getLineLeft(line), layout.getLineRight(line));
            float lineRight = view.getCompoundPaddingLeft() +
                    Math.max(layout.getLineLeft(line), layout.getLineRight(line));
            int lineTop = view.getCompoundPaddingTop() + layout.getLineTop(line);
            int lineBottom = view.getCompoundPaddingTop() + layout.getLineBottom(line);
            if (lineRight <= lineLeft || lineTop < visible.top ||
                    lineBottom > visible.bottom || lineLeft < visible.left ||
                    lineRight > visible.right) return false;
        }
        return true;
    }

    private static void capture(
            ActivityScenario<IsolatedLibraryRestoreActivity> scenario,
            Context target, String activityFixtureId, File screenshotRoot,
            String fileName) throws IOException {
        String[] allowed = {"empty-no-profile.png", "empty-existing-profile.png",
                "history-list.png", "history-detail.png"};
        boolean recognized = false;
        for (String candidate : allowed) if (candidate.equals(fileName)) recognized = true;
        assertTrue("screenshot name belongs to the closed UUID fixture set", recognized);
        File expectedRoot = new File(target.getFilesDir(),
                "ui-fixtures/task-history-" + activityFixtureId);
        assertEquals("screenshots use a separate UUID namespace from the Activity fixture",
                expectedRoot.getCanonicalPath(), screenshotRoot.getCanonicalPath());
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        Bitmap bitmap = automation.takeScreenshot();
        assertTrue("UI screenshot captured: " + fileName, bitmap != null);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue("PNG encoding succeeded", bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
        byte[] png = bytes.toByteArray();
        scenario.onActivity(activity -> {
            File output = new File(screenshotRoot, fileName);
            File parent = output.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) {
                throw new AssertionError("unable to create UUID-owned screenshot directory");
            }
            try (FileOutputStream stream = new FileOutputStream(output, false)) {
                stream.write(png);
                stream.getFD().sync();
            } catch (IOException failure) {
                throw new AssertionError("unable to persist screenshot in target app sandbox", failure);
            }
        });
        System.out.println("TASK_HISTORY_SCREENSHOT activityFixture=" + activityFixtureId +
                " screenshotFixture=" + screenshotRoot.getName() +
                " relative=ui-fixtures/" + screenshotRoot.getName() + "/" + fileName +
                " bytes=" + png.length);
        bitmap.recycle();
    }

    private static void assertScreenshotFiles(File root, List<String> expectedNames) {
        File[] files = root.listFiles();
        assertTrue("UUID screenshot directory exists", files != null);
        java.util.Set<String> actual = new java.util.HashSet<>();
        for (File file : files) {
            assertTrue("only the screenshot allowlist remains in the UUID directory",
                    expectedNames.contains(file.getName()) && file.isFile() && file.length() > 0);
            actual.add(file.getName());
        }
        assertEquals("expected UUID-owned screenshot set", new java.util.HashSet<>(expectedNames), actual);
    }

    private static void assertTextAbsent(String text) {
        onView(withText(containsString(text))).inRoot(isDialog()).check(doesNotExist());
    }

    private static ViewAction assertTextOrder(String first,String second) {
        return new ViewAction() {
            @Override public Matcher<View> getConstraints(){return isRoot();}
            @Override public String getDescription(){return "verify dialog layout order for result actions";}
            @Override public void perform(UiController controller,View root){
                List<String> texts=new ArrayList<>();collectText(root,texts);
                int firstIndex=texts.indexOf(first),secondIndex=texts.indexOf(second);
                assertTrue("first result action is in the dialog: "+first,firstIndex>=0);
                assertTrue("technical-details toggle is in the dialog: "+second,secondIndex>=0);
                assertTrue("result action precedes technical details",firstIndex<secondIndex);
            }
        };
    }

    private static ViewAction assertVisibleTextCount(String fragment,int expected) {
        return new ViewAction() {
            @Override public Matcher<View> getConstraints(){return isRoot();}
            @Override public String getDescription(){return "count visible dialog text containing a marker";}
            @Override public void perform(UiController controller,View root){
                AtomicInteger count=new AtomicInteger();countVisibleText(root,fragment,count);
                assertEquals("visible result marker occurrences",expected,count.get());
            }
        };
    }

    private static ViewAction assertTextMaxLines(String fragment,int expectedMaxLines) {
        return new ViewAction() {
            @Override public Matcher<View> getConstraints(){return isRoot();}
            @Override public String getDescription(){return "verify bounded or expanded result preview";}
            @Override public void perform(UiController controller,View root){
                AtomicInteger matches=new AtomicInteger();findTextMaxLines(root,fragment,expectedMaxLines,matches);
                assertEquals("one visible answer view has the expected max-lines policy",1,matches.get());
            }
        };
    }

    private static void collectText(View view,List<String> values){
        if(view instanceof TextView){CharSequence text=((TextView)view).getText();if(text!=null)values.add(text.toString());}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collectText(group.getChildAt(i),values);}
    }

    private static void countVisibleText(View view,String fragment,AtomicInteger count){
        if(view.getVisibility()==View.VISIBLE&&view.isShown()&&view instanceof TextView){
            CharSequence text=((TextView)view).getText();
            if(text!=null&&text.toString().contains(fragment))count.incrementAndGet();
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)countVisibleText(group.getChildAt(i),fragment,count);}
    }

    private static void findTextMaxLines(View view,String fragment,int expectedMaxLines,AtomicInteger matches){
        if(view.getVisibility()==View.VISIBLE&&view.isShown()&&view instanceof TextView){
            TextView textView=(TextView)view;CharSequence text=textView.getText();
            if(text!=null&&text.toString().contains(fragment)){
                assertEquals("result TextView maxLines",expectedMaxLines,textView.getMaxLines());
                matches.incrementAndGet();
            }
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)findTextMaxLines(group.getChildAt(i),fragment,expectedMaxLines,matches);}
    }

    private static void assertNoPreferenceFiles(File directory, String prefix) {
        File[] existing = directory.listFiles((parent, name) ->
                name.startsWith(prefix) || name.startsWith(prefix) && name.endsWith(".xml"));
        assertTrue("refuse pre-existing preference namespace",
                existing == null || existing.length == 0);
    }

    private static void cleanupOwnedFixture(Context target, File activityRoot,
                                            File taskDirectory, File prefsDirectory, String prefix) {
        File[] preferences = prefsDirectory.listFiles((parent, name) -> name.startsWith(prefix));
        if (preferences != null) {
            for (File file : preferences) {
                if (file.getName().endsWith(".xml")) {
                    String name = file.getName().substring(0, file.getName().length() - 4);
                    assertTrue("delete only UUID-owned preference " + name,
                            target.deleteSharedPreferences(name));
                }
            }
        }
        File[] leftovers = prefsDirectory.listFiles((parent, name) -> name.startsWith(prefix));
        assertTrue("UUID-owned preferences remain", leftovers == null || leftovers.length == 0);
        deleteTree(taskDirectory);
        assertFalse("UUID-owned task cache remains", taskDirectory.exists());
        deleteTree(activityRoot);
        assertFalse("UUID-owned isolated Activity root remains", activityRoot.exists());
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete() && file.exists()) throw new AssertionError("fixture cleanup failed: " + file);
    }

    private static final class MemoryBackend implements AgentConnectionStore.Backend {
        private final Object lock = new Object();
        private final Map<String, String> strings = new HashMap<>();
        private final Map<String, Boolean> booleans = new HashMap<>();
        MemoryBackend(String registry) { strings.put("connections.v2", registry); }
        @Override public Object transactionLock() { return lock; }
        @Override public String string(String key, String fallback) {
            synchronized (lock) { return strings.getOrDefault(key, fallback); }
        }
        @Override public boolean bool(String key, boolean fallback) {
            synchronized (lock) { return booleans.getOrDefault(key, fallback); }
        }
        @Override public boolean contains(String key) {
            synchronized (lock) { return strings.containsKey(key) || booleans.containsKey(key); }
        }
        @Override public boolean commit(Map<String, String> values, Map<String, Boolean> flags,
                                        Set<String> removals) {
            synchronized (lock) {
                for (String key : removals) { strings.remove(key); booleans.remove(key); }
                strings.putAll(values); booleans.putAll(flags); return true;
            }
        }
    }

    private static final class NoOpExecutor extends AbstractExecutorService {
        private boolean stopped;
        final AtomicInteger executionCount = new AtomicInteger();
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; return Collections.emptyList(); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
        @Override public void execute(Runnable command) {
            if (stopped) throw new java.util.concurrent.RejectedExecutionException();
            executionCount.incrementAndGet();
            throw new AssertionError("synthetic UX test must never execute background work");
        }
    }
}
