package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
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
