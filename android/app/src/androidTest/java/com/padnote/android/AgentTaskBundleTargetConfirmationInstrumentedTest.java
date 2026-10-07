package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class AgentTaskBundleTargetConfirmationInstrumentedTest {
    private static final String FIRST_ID = "11111111-1111-4111-8111-111111111111";
    private static final String SECOND_ID = "22222222-2222-4222-8222-222222222222";
    private static final String FIRST_INSTANCE = "instance-first-r4";
    private static final String SECOND_INSTANCE = "instance-second-r4";

    @Test public void selectingSameNameSecondThenCancelDoesNotBuildOrSubmitBundle() throws Exception {
        runFlow(false);
    }

    @Test public void selectingSameNameSecondConfirmsAndPersistsSubmitsExactProfileIdentity() throws Exception {
        runFlow(true);
    }

    private void runFlow(boolean confirm) throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String fixtureId=UUID.randomUUID().toString();
        File fixtureRoot=new File(target.getFilesDir(),"ui-fixtures/"+fixtureId);
        assertFalse("refusing an existing fixture UUID",fixtureRoot.exists());
        String prefPrefix="ui-fixture-"+fixtureId+"-";
        ActivityScenario<IsolatedLibraryRestoreActivity> scenario=null;
        ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        AtomicReference<AgentTaskStore> taskStore=new AtomicReference<>();
        AtomicInteger bundleFactoryCalls=new AtomicInteger();
        AtomicInteger clientFactoryCalls=new AtomicInteger();
        AtomicInteger postCalls=new AtomicInteger();
        AtomicInteger statusCalls=new AtomicInteger();
        AtomicReference<AgentHttpTransport.Request> submitted=new AtomicReference<>();
        CountDownLatch statusFinished=new CountDownLatch(1);
        try {
            Intent intent=new Intent(target,IsolatedLibraryRestoreActivity.class).putExtra("fixture_id",fixtureId);
            scenario=ActivityScenario.launch(intent);
            ActivityScenario<IsolatedLibraryRestoreActivity> active=scenario;
            List<AgentConnectionStore.Config> profiles=profiles();
            TaskSource source=new TaskSource(profiles);
            AgentTaskClient.Factory clientFactory=()->{
                clientFactoryCalls.incrementAndGet();
                return new AgentTaskClient(request->{
                    if("POST".equals(request.method)){
                        postCalls.incrementAndGet();submitted.set(request);
                        JSONObject response=new JSONObject().put("task_id","remote-r4-selected")
                                .put("instance_id",SECOND_INSTANCE).put("status","completed");
                        return response(202,request,response);
                    }
                    if("GET".equals(request.method)){
                        statusCalls.incrementAndGet();
                        JSONObject response=new JSONObject().put("task_id","remote-r4-selected")
                                .put("instance_id",SECOND_INSTANCE).put("status","completed")
                                .put("output","synthetic completion").put("conversation_id","remote-r4-selected")
                                .put("parent_task_id","").put("followup_available",false)
                                .put("followup_reason","fixture has no follow-up");
                        statusFinished.countDown();
                        return response(200,request,response);
                    }
                    throw new AssertionError("unexpected synthetic request method: "+request.method);
                });
            };
            AgentConnectionStore isolatedStore=new AgentConnectionStore(new MemoryBackend(),()->1700000000000L);
            scenario.onActivity(activity->{
                taskStore.set(new AgentTaskStore(activity));
                AgentTaskDialogs dialogs=new AgentTaskDialogs(activity,isolatedStore,worker,null,
                        taskStore.get(),VideoTaskDialogs::new,null,clientFactory,source);
                dialogs.chooseAndSubmitBundle("合成材料包","synthetic instruction","synthetic-note",7L,()->{
                    bundleFactoryCalls.incrementAndGet();return syntheticBundle();
                });
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            String firstLabel=AgentTaskDialogs.destinationLabel(profiles.get(0));
            String secondLabel=AgentTaskDialogs.destinationLabel(profiles.get(1));
            assertNotEquals(firstLabel,secondLabel);
            onView(withText(firstLabel)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(secondLabel)).inRoot(isDialog()).check(matches(isDisplayed()));
            assertNotSelectable(profiles.get(2));
            assertNotSelectable(profiles.get(3));
            assertNotSelectable(profiles.get(4));

            onView(withText(secondLabel)).inRoot(isDialog()).perform(click());
            onView(withText("目标："+secondLabel+"\n材料：合成材料包\n确认后会将所选材料发送给此电脑 Agent。"))
                    .inRoot(isDialog()).check(matches(isDisplayed()));
            saveScreenshot(active,fixtureId,confirm?"confirm-selected-target":"cancel-selected-target");

            if(!confirm){
                onView(withText("取消")).inRoot(isDialog()).perform(click());
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                assertEquals(0,bundleFactoryCalls.get());
                assertEquals(0,clientFactoryCalls.get());
                assertEquals(0,postCalls.get());
                assertTrue(taskStore.get().list().isEmpty());
            }else{
                onView(withText("发送到此 Agent")).inRoot(isDialog()).perform(click());
                assertTrue("fake status response did not complete",statusFinished.await(10,TimeUnit.SECONDS));
                waitFor(()->!taskStore.get().list().isEmpty()&&
                        taskStore.get().list().get(0).status==AgentTaskStore.Status.COMPLETED,"persisted completed task");
                AgentTaskStore.Task saved=taskStore.get().list().get(0);
                AgentConnectionStore.Config selected=profiles.get(1);
                assertEquals(1,bundleFactoryCalls.get());
                assertEquals(1,postCalls.get());
                assertEquals(1,statusCalls.get());
                assertEquals(selected.id,saved.connectionId);
                assertEquals(selected.revision,saved.connectionRevision);
                assertEquals(selected.endpoint,saved.origin);
                assertEquals(selected.bridgeId,saved.bridgeId);
                assertEquals(selected.instanceId,saved.instanceId);
                assertEquals("remote-r4-selected",saved.remoteTaskId);
                assertTrue(new JSONObject(saved.submissionJson).has("bundle_base64"));
                assertNotNull(submitted.get());
                assertEquals("synthetic-token-second",submitted.get().bearer);
                assertTrue(submitted.get().url.toString().contains(SECOND_INSTANCE));
                assertFalse(submitted.get().url.toString().contains(FIRST_INSTANCE));
                captureScreenshot(active,fixtureId,"submitted-selected-target");
            }
        } finally {
            worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS);
            if(scenario!=null)scenario.close();
            cleanupFixture(target,fixtureId,prefPrefix);
        }
    }

    private static void assertNotSelectable(AgentConnectionStore.Config profile) {
        try {
            onView(withText(AgentTaskDialogs.destinationLabel(profile))).inRoot(isDialog())
                    .check(matches(isDisplayed()));
            fail("ineligible target was shown: "+profile.id);
        } catch(NoMatchingViewException expected) { }
    }

    private static List<AgentConnectionStore.Config> profiles() {
        long verifiedAt=1700000000000L;
        AgentConnectionStore.Config first=config(FIRST_ID,"same-name-first",FIRST_INSTANCE,7L,
                verifiedAt,Arrays.asList("task_bundle","run_submission","run_status"));
        AgentConnectionStore.Config second=config(SECOND_ID,"same-name-second",SECOND_INSTANCE,11L,
                verifiedAt,Arrays.asList("task_bundle","run_submission","run_status"));
        AgentConnectionStore.Config missingBundle=config("33333333-3333-4333-8333-333333333333",
                "ineligible-missing-bundle","instance-no-bundle",1L,verifiedAt,
                Arrays.asList("run_submission","run_status"));
        AgentConnectionStore.Config unverified=config("44444444-4444-4444-8444-444444444444",
                "ineligible-unverified","instance-unverified",2L,0L,
                Arrays.asList("task_bundle","run_submission","run_status"));
        AgentConnectionStore.Config builtinVideoWithoutCapability=new AgentConnectionStore.Config(
                "55555555-5555-4555-8555-555555555555","同名电脑",AgentConnectionStore.Kind.BUILTIN_VIDEO,
                "https://video-r4.example.test:8643","synthetic-video-token","synthetic-video-ref",
                AgentConnectionStore.Transport.BRIDGE,"bridge-video-r4","instance-video-r4",3L,verifiedAt,
                Collections.singletonList("task_bundle"));
        return Arrays.asList(first,second,missingBundle,unverified,builtinVideoWithoutCapability);
    }

    private static AgentConnectionStore.Config config(String id,String bridge,String instance,long revision,
                                                       long verifiedAt,List<String> capabilities) {
        return new AgentConnectionStore.Config(id,"同名电脑",AgentConnectionStore.Kind.HERMES,
                "https://computer-r4.example.test:8741","synthetic-token-"+(id.equals(SECOND_ID)?"second":"first"),
                "synthetic-credential-"+id,AgentConnectionStore.Transport.BRIDGE,bridge,instance,
                revision,verifiedAt,capabilities);
    }

    private static AgentHttpTransport.Response response(int status,AgentHttpTransport.Request request,
                                                         JSONObject body) {
        return new AgentHttpTransport.Response(status,request.url,request.url,
                body.toString().getBytes(StandardCharsets.UTF_8),Collections.emptyMap());
    }

    private static byte[] syntheticBundle() throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        VideoTaskBundleIO.write(output,"synthetic-note",7L,"synthetic title","# synthetic source",
                "student","synthetic goal",60,"voice",1.0f);
        return output.toByteArray();
    }

    private static void saveScreenshot(ActivityScenario<IsolatedLibraryRestoreActivity> scenario,
                                       String fixtureId,String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("UI screenshot unavailable",bitmap);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes)); }
        finally { bitmap.recycle(); }
        byte[] png=bytes.toByteArray();AtomicReference<File> output=new AtomicReference<>();
        scenario.onActivity(activity->{try{output.set(activity.writeEvidenceScreenshot(name+".png",png));}
            catch(Exception error){throw new AssertionError("unable to save UUID-owned UI evidence",error);}});
        assertNotNull(output.get());
        System.out.println("FIRST_AGENT_TARGET_R4_SCREENSHOT="+output.get().getAbsolutePath());
    }

    private static void captureScreenshot(ActivityScenario<IsolatedLibraryRestoreActivity> scenario,
                                         String fixtureId,String name) throws Exception {
        saveScreenshot(scenario,fixtureId,name);
    }

    private static void waitFor(Condition condition,String what) throws Exception {
        long deadline=SystemClock.uptimeMillis()+10000L;
        while(SystemClock.uptimeMillis()<deadline){if(condition.check())return;SystemClock.sleep(50L);}
        throw new AssertionError("timed out waiting for "+what);
    }

    private static void cleanupFixture(Context target,String id,String prefix) {
        File root=new File(target.getFilesDir(),"ui-fixtures/"+id);
        File evidence=new File(root,"evidence");
        File[] owned=root.listFiles();
        if(owned!=null)for(File file:owned)if(!file.equals(evidence))deleteTree(file);
        File prefs=new File(target.getApplicationInfo().dataDir,"shared_prefs");
        File[] scoped=prefs.listFiles((dir,name)->name.startsWith(prefix)&&name.endsWith(".xml"));
        if(scoped!=null)for(File file:scoped){
            String name=file.getName().substring(0,file.getName().length()-4);
            target.deleteSharedPreferences(name);
            assertFalse("UUID preference remains: "+name,file.exists());
        }
        File[] leftovers=prefs.listFiles((dir,name)->name.startsWith(prefix));
        assertTrue("UUID preference files remain",leftovers==null||leftovers.length==0);
        File[] data=root.listFiles();
        if(data!=null)for(File file:data)assertEquals("only evidence may remain",evidence,file);
        if(evidence.exists())System.out.println("FIRST_AGENT_TARGET_R4_EVIDENCE_ROOT="+evidence.getAbsolutePath());
    }

    private static void deleteTree(File file) {
        if(file==null||!file.exists())return;
        File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);
        if(!file.delete()&&file.exists())throw new AssertionError("fixture cleanup failed: "+file);
    }

    private interface Condition { boolean check() throws Exception; }

    private static final class TaskSource implements AgentTaskDialogs.TaskConnectionSource {
        private final Map<String,AgentConnectionStore.Config> byId=new HashMap<>();
        private final List<AgentConnectionStore.Config> profiles;
        TaskSource(List<AgentConnectionStore.Config> profiles){
            this.profiles=Collections.unmodifiableList(new ArrayList<>(profiles));
            for(AgentConnectionStore.Config profile:profiles)byId.put(profile.id,profile);
        }
        @Override public List<AgentConnectionStore.Config> list(){return profiles;}
        @Override public AgentConnectionStore.Config get(String id){return byId.get(id);}
    }

    private static final class MemoryBackend implements AgentConnectionStore.Backend {
        private final Object lock=new Object();
        private final Map<String,String> strings=new HashMap<>();
        private final Map<String,Boolean> booleans=new HashMap<>();
        @Override public Object transactionLock(){return lock;}
        @Override public String string(String key,String fallback){synchronized(lock){return strings.getOrDefault(key,fallback);}}
        @Override public boolean bool(String key,boolean fallback){synchronized(lock){return booleans.getOrDefault(key,fallback);}}
        @Override public boolean contains(String key){synchronized(lock){return strings.containsKey(key)||booleans.containsKey(key);}}
        @Override public boolean commit(Map<String,String> values,Map<String,Boolean> bools,java.util.Set<String> removals){
            synchronized(lock){for(String key:removals){strings.remove(key);booleans.remove(key);}strings.putAll(values);booleans.putAll(bools);return true;}
        }
    }
}
