package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic, UUID-scoped checks for pairing follow-up probes and task delivery presets. */
@RunWith(AndroidJUnit4.class)
public final class AgentPairingAndDeliveryChoiceInstrumentedTest {
    @Test public void autoProbeRunsOncePersistsOutcomesAndDropsCallbacksAfterActivityClose() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id=UUID.randomUUID().toString();
        File fixtureRoot=new File(target.getFilesDir(),"ui-fixtures/"+id);
        assertFalse("refuse reused Activity fixture",fixtureRoot.exists());
        StoreFixture seeded=storeWithProfiles("ready-"+id,"failed-"+id,"stale-"+id,"held-"+id);
        AgentConnectionStore store=seeded.store;
        List<AgentConnectionStore.Config> createdProfiles=new java.util.ArrayList<>(seeded.profiles.values());
        ExecutorService worker=Executors.newFixedThreadPool(2);
        ActivityScenario<IsolatedLibraryRestoreActivity> scenario=null;
        CountDownLatch probeStarted=new CountDownLatch(3);
        CountDownLatch heldProbeStarted=new CountDownLatch(1);
        CountDownLatch probeFinished=new CountDownLatch(3);
        CountDownLatch heldProbeFinished=new CountDownLatch(1);
        CountDownLatch uiChanged=new CountDownLatch(3);
        CountDownLatch releaseHeld=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        AtomicInteger changedCalls=new AtomicInteger();
        AtomicBoolean firstBatchScheduled=new AtomicBoolean();
        AtomicBoolean heldBatchScheduled=new AtomicBoolean();
        AtomicReference<AgentConnectionDialogs> dialogs=new AtomicReference<>();
        AtomicReference<IsolatedLibraryRestoreActivity> activityRef=new AtomicReference<>();
        try {
            AgentConnectionStore.Config ready=seeded.profiles.get("ready-"+id);
            AgentConnectionStore.Config failed=seeded.profiles.get("failed-"+id);
            AgentConnectionStore.Config stale=seeded.profiles.get("stale-"+id);
            AgentConnectionStore.Config held=seeded.profiles.get("held-"+id);
            Intent launch=new Intent(target,IsolatedLibraryRestoreActivity.class).putExtra("fixture_id",id);
            scenario=ActivityScenario.launch(launch);
            scenario.onActivity(activity->{
                activityRef.set(activity);
                dialogs.set(new AgentConnectionDialogs(activity,store,worker,()->{
                    changedCalls.incrementAndGet();uiChanged.countDown();
                },null,null,null,profile->{
                    calls.incrementAndGet();probeStarted.countDown();
                    try {
                        if(profile.id.equals(ready.id))return probeResult(profile);
                        if(profile.id.equals(failed.id))throw new SocketTimeoutException("synthetic timeout");
                        if(profile.id.equals(stale.id)){
                            assertTrue("delete only this synthetic profile while its probe is in flight",store.delete(stale.id));
                            return probeResult(profile);
                        }
                        if(profile.id.equals(held.id)){
                            heldProbeStarted.countDown();
                            if(!releaseHeld.await(5,TimeUnit.SECONDS))throw new SocketTimeoutException("fixture timeout");
                            return probeResult(profile);
                        }
                        throw new AssertionError("unexpected profile probe");
                    } finally {
                        if(profile.id.equals(held.id))heldProbeFinished.countDown();
                        else probeFinished.countDown();
                    }
                }));
            });
            assertTrue("Activity accepts UI callbacks while alive",AgentConnectionDialogs.mayDeliverProbeUi(activityRef.get()));
            firstBatchScheduled.set(true);
            scenario.onActivity(activity->dialogs.get().scheduleAutomaticProbes(Arrays.asList(ready,failed,stale)));
            assertTrue("three synthetic probes enter",probeStarted.await(5,TimeUnit.SECONDS));
            assertTrue("three synthetic probes finish",probeFinished.await(5,TimeUnit.SECONDS));
            assertTrue("outcome refreshes are delivered only while Activity is alive",uiChanged.await(5,TimeUnit.SECONDS));
            assertEquals("each saved profile gets exactly one probe call",3,calls.get());
            AgentConnectionStore.Config readyStored=store.get(ready.id);
            assertTrue("successful probe is persisted",readyStored.verified());
            assertEquals("probe result preserves saved bridge identity",ready.bridgeId,readyStored.bridgeId);
            assertEquals("probe result preserves saved instance identity",ready.instanceId,readyStored.instanceId);
            assertEquals(Collections.singletonList("run_status"),readyStored.capabilities);
            assertNull("successful probe clears stale failure",readyStored.probeFailure);
            AgentConnectionStore.Config failedStored=store.get(failed.id);
            assertNotNull("failed probe leaves the paired profile stored",failedStored);
            assertEquals(AgentConnectionStore.ProbeFailure.TIMEOUT,failedStored.probeFailure);
            assertFalse("failed readiness check does not mark profile verified",failedStored.verified());
            assertNull("late result cannot recreate a deleted synthetic profile",store.get(stale.id));

            heldBatchScheduled.set(true);
            scenario.onActivity(activity->dialogs.get().scheduleAutomaticProbes(Collections.singletonList(held)));
            assertTrue("final probe entered",heldProbeStarted.await(5,TimeUnit.SECONDS));
            scenario.close();scenario=null;
            int callbacksBeforeRelease=changedCalls.get();
            releaseHeld.countDown();
            assertTrue("background probe completes after Activity closes",heldProbeFinished.await(5,TimeUnit.SECONDS));
            assertEquals("closed Activity receives no late UI callback",callbacksBeforeRelease,changedCalls.get());
            long deadline=SystemClock.uptimeMillis()+2_000L;
            while(!store.get(held.id).verified()&&SystemClock.uptimeMillis()<deadline)Thread.sleep(10L);
            assertTrue("background store update remains CAS-bound and completes",store.get(held.id).verified());
            assertEquals("late synthetic result preserves the saved bridge identity",held.bridgeId,store.get(held.id).bridgeId);
            assertEquals("late synthetic result preserves the saved instance identity",held.instanceId,store.get(held.id).instanceId);
            assertEquals("exactly four probes ran once each",4,calls.get());
        } finally {
            if(scenario!=null){scenario.close();scenario=null;}
            releaseHeld.countDown();
            if(firstBatchScheduled.get())probeFinished.await(5,TimeUnit.SECONDS);
            if(heldBatchScheduled.get())heldProbeFinished.await(5,TimeUnit.SECONDS);
            worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);
            for(AgentConnectionStore.Config profile:createdProfiles)store.delete(profile.id);
            cleanupScopedActivityPreferences(target,id);
            deleteTree(fixtureRoot);
        }
    }

    @Test public void presetDefaultsNeverOverwriteUserSelectionOrExplicitClear() {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id=UUID.randomUUID().toString();
        File fixtureRoot=new File(target.getFilesDir(),"ui-fixtures/"+id);
        assertFalse("refuse reused Activity fixture",fixtureRoot.exists());
        ActivityScenario<IsolatedLibraryRestoreActivity> scenario=null;
        try {
            Intent launch=new Intent(target,IsolatedLibraryRestoreActivity.class).putExtra("fixture_id",id);
            scenario=ActivityScenario.launch(launch);
            AtomicReference<IsolatedLibraryRestoreActivity> activityRef=new AtomicReference<>();
            scenario.onActivity(activity->{
                activityRef.set(activity);
                LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);
                AgentTaskDialogs.DeliverySelector selector=new AgentTaskDialogs.DeliverySelector(activity,
                        Collections.emptyList(),true);
                root.addView(selector.view());activity.setContentView(root);
                selector.setPresetDefaultIfUntouched(Collections.singletonList("video"));
                assertEquals(Collections.singletonList("video"),selector.selected());
                selector.setPresetDefaultIfUntouched(Collections.emptyList());
                assertTrue("preset may update a choice the user has not touched",selector.selected().isEmpty());
                selector.setPresetDefaultIfUntouched(Collections.singletonList("video"));
                TextView video=(TextView)selector.view().findViewWithTag("delivery-kind-video");
                assertNotNull(video);assertTrue(video.performClick());
                assertTrue("user can explicitly clear preset",selector.selected().isEmpty());
                selector.setPresetDefaultIfUntouched(Collections.emptyList());
                selector.setPresetDefaultIfUntouched(Collections.singletonList("video"));
                assertTrue("later presets do not undo an explicit clear",selector.selected().isEmpty());
                TextView image=(TextView)selector.view().findViewWithTag("delivery-kind-image");
                assertNotNull(image);assertTrue(image.performClick());
                assertEquals(Collections.singletonList("image"),selector.selected());
                selector.setPresetDefaultIfUntouched(Collections.singletonList("video"));
                selector.setServerContract(false);
                assertEquals("changing Agent capability hint does not change selection",
                        Collections.singletonList("image"),selector.selected());
                assertTrue("selection is marked user-owned",selector.userTouchedSelection());
            });
            assertTrue(AgentConnectionDialogs.mayDeliverProbeUi(activityRef.get()));
            scenario.close();scenario=null;
            assertFalse("late UI callbacks are suppressed after Activity destruction",
                    AgentConnectionDialogs.mayDeliverProbeUi(activityRef.get()));
        } finally {
            if(scenario!=null)scenario.close();
            cleanupScopedActivityPreferences(target,id);deleteTree(fixtureRoot);
        }
    }

    @Test public void visibleConnectionRowsRefreshToCurrentSuccessAndFailureWithRetryAction() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id=UUID.randomUUID().toString();
        File fixtureRoot=new File(target.getFilesDir(),"ui-fixtures/"+id);
        assertFalse("refuse reused Activity fixture",fixtureRoot.exists());
        StoreFixture seeded=storeWithProfiles("visible-ready-"+id,"visible-failed-"+id,"visible-storage-failed-"+id);
        AgentConnectionStore store=seeded.store;
        MemoryBackend backend=seeded.backend;
        ExecutorService worker=Executors.newSingleThreadExecutor();
        ActivityScenario<IsolatedLibraryRestoreActivity> scenario=null;
        CountDownLatch probeEntered=new CountDownLatch(2),releaseProbe=new CountDownLatch(1);
        CountDownLatch probeFinished=new CountDownLatch(2),changed=new CountDownLatch(2);
        AtomicReference<AgentConnectionDialogs> dialogs=new AtomicReference<>();
        try {
            AgentConnectionStore.Config ready=seeded.profiles.get("visible-ready-"+id);
            AgentConnectionStore.Config failed=seeded.profiles.get("visible-failed-"+id);
            AgentConnectionStore.Config storageFailed=seeded.profiles.get("visible-storage-failed-"+id);
            scenario=ActivityScenario.launch(new Intent(target,IsolatedLibraryRestoreActivity.class)
                    .putExtra("fixture_id",id));
            scenario.onActivity(activity->{
                dialogs.set(new AgentConnectionDialogs(activity,store,worker,changed::countDown,
                        null,null,null,saved->{
                    probeEntered.countDown();
                    try {
                        if(!releaseProbe.await(5,TimeUnit.SECONDS))throw new SocketTimeoutException("fixture timeout");
                        if(saved.id.equals(ready.id))return probeResult(saved);
                        throw new SocketTimeoutException("synthetic offline profile");
                    } finally {probeFinished.countDown();}
                }));
                dialogs.get().show();
            });
            scenario.onActivity(activity->dialogs.get().scheduleAutomaticProbes(Arrays.asList(ready,failed)));
            assertTrue("both one-shot probes enter",probeEntered.await(5,TimeUnit.SECONDS));
            String checking=target.getString(R.string.flow_probe_agent_checking_label);
            onView(allOf(withText(containsString(ready.name)),withText(containsString(checking))))
                    .inRoot(isDialog()).check(matches(isDisplayed()));
            onView(allOf(withText(containsString(failed.name)),withText(containsString(checking))))
                    .inRoot(isDialog()).check(matches(isDisplayed()));
            releaseProbe.countDown();
            assertTrue("probe result completes",probeFinished.await(5,TimeUnit.SECONDS));
            assertTrue("visible-list refresh is delivered after the store update",changed.await(5,TimeUnit.SECONDS));
            String failureLabel=target.getString(R.string.flow_probe_timeout_label);
            String successLabel=target.getString(R.string.flow_bridge_pairing_checked,1);
            onView(allOf(withText(containsString(ready.name)),withText(containsString(successLabel))))
                    .inRoot(isDialog()).check(matches(isDisplayed()));
            onView(allOf(withText(containsString(failed.name)),withText(containsString(failureLabel))))
                    .inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(containsString(failed.name))).inRoot(isDialog()).perform(click());
            onView(withText(containsString(failureLabel))).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(target.getString(R.string.flow_test_connection))).inRoot(isDialog())
                    .check(matches(isDisplayed()));
            assertNotNull("failed readiness check retains the saved connection",store.get(failed.id));
            assertEquals(AgentConnectionStore.ProbeFailure.TIMEOUT,store.get(failed.id).probeFailure);
            assertTrue("successful readiness status is stored before the row click",store.get(ready.id).verified());
            onView(withText(target.getString(R.string.flow_back))).inRoot(isDialog()).perform(click());
            onView(withText(target.getString(R.string.flow_close))).inRoot(isDialog()).perform(click());

            CountDownLatch storageFailureEntered=new CountDownLatch(1),storageFailureChanged=new CountDownLatch(1);
            AtomicReference<AgentConnectionDialogs> failureDialogs=new AtomicReference<>();
            scenario.onActivity(activity->{
                failureDialogs.set(new AgentConnectionDialogs(activity,store,worker,
                        storageFailureChanged::countDown,null,null,null,saved->{
                    storageFailureEntered.countDown();
                    backend.failNextCommit(new IllegalStateException("synthetic memory commit failure"));
                    throw new SocketTimeoutException("synthetic probe timeout");
                }));
                failureDialogs.get().show();
            });
            scenario.onActivity(activity->failureDialogs.get().scheduleAutomaticProbes(Collections.singletonList(storageFailed)));
            assertTrue("storage-failure probe enters",storageFailureEntered.await(5,TimeUnit.SECONDS));
            assertTrue("storage-failure probe still completes its batch",storageFailureChanged.await(5,TimeUnit.SECONDS));
            String checkingAfterFailedCommit=AgentConnectionStore.ProbeFailure.UNKNOWN.label(target);
            onView(allOf(withText(containsString(storageFailed.name)),withText(containsString(checkingAfterFailedCommit))))
                    .inRoot(isDialog()).check(matches(isDisplayed()));
            assertNotNull("storage exception leaves the saved synthetic profile intact",store.get(storageFailed.id));
            assertFalse("storage exception is not presented as verified",store.get(storageFailed.id).verified());
            onView(withText(target.getString(R.string.flow_close))).inRoot(isDialog()).perform(click());
            AgentConnectionStore.Config beforeManualRetry=store.get(storageFailed.id);
            String manualAttempt=store.beginProbe(beforeManualRetry.id,beforeManualRetry.revision);
            assertTrue("same-revision manual retry gets a fresh attempt ID",!manualAttempt.isEmpty());
            assertTrue("manual retry can replace the checking state with current success",
                    store.applyProbeSuccess(beforeManualRetry.id,beforeManualRetry.revision,manualAttempt,
                            probeResult(beforeManualRetry)));
            scenario.onActivity(activity->failureDialogs.get().show());
            String checked=target.getString(R.string.flow_bridge_pairing_checked,1);
            onView(allOf(withText(containsString(storageFailed.name)),withText(containsString(checked))))
                    .inRoot(isDialog()).check(matches(isDisplayed()));
            assertTrue("reopened list reflects same-revision manual success",store.get(storageFailed.id).verified());
        } finally {
            if(scenario!=null)scenario.close();
            releaseProbe.countDown();probeFinished.await(5,TimeUnit.SECONDS);
            worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);
            for(AgentConnectionStore.Config profile:seeded.profiles.values())store.delete(profile.id);
            cleanupScopedActivityPreferences(target,id);
            deleteTree(fixtureRoot);
        }
    }

    private static StoreFixture storeWithProfiles(String... names)throws Exception{
        JSONArray rows=new JSONArray();Map<String,String> ids=new HashMap<>();
        for(String name:names){
            String id=UUID.randomUUID().toString();ids.put(name,id);
            rows.put(new JSONObject().put("id",id).put("name",name).put("kind","HERMES")
                    .put("endpoint","https://pair-test.invalid").put("credentialRef","synthetic-ref-"+id)
                    .put("transport","BRIDGE").put("bridgeId","synthetic-bridge")
                    .put("instanceId","synthetic-instance-"+id).put("certSha256","")
                    .put("revision",1).put("verifiedAt",0).put("capabilities",new JSONArray()));
        }
        String defaultId=ids.isEmpty()?"":ids.values().iterator().next();
        MemoryBackend backend=new MemoryBackend(new JSONObject().put("schemaVersion",2)
                .put("defaultId",defaultId).put("connections",rows).toString());
        AgentConnectionStore store=new AgentConnectionStore(backend,()->1_800_000_000_000L);
        Map<String,AgentConnectionStore.Config> profiles=new HashMap<>();
        for(String name:names)profiles.put(name,store.get(ids.get(name)));
        return new StoreFixture(store,backend,profiles);
    }
    private static AgentConnectionClient.ProbeResult probeResult(AgentConnectionStore.Config saved){
        return new AgentConnectionClient.ProbeResult("synthetic ready",saved.bridgeId,saved.instanceId,
                Collections.singletonList("run_status"));
    }
    private static final class StoreFixture{
        final AgentConnectionStore store;final MemoryBackend backend;
        final Map<String,AgentConnectionStore.Config> profiles;
        StoreFixture(AgentConnectionStore store,MemoryBackend backend,Map<String,AgentConnectionStore.Config> profiles){
            this.store=store;this.backend=backend;this.profiles=profiles;
        }
    }
    private static final class MemoryBackend implements AgentConnectionStore.Backend{
        private final Object lock=new Object();private final Map<String,String> strings=new HashMap<>();
        private final Map<String,Boolean> booleans=new HashMap<>();private RuntimeException nextCommitFailure;
        MemoryBackend(String registry){strings.put("connections.v2",registry);}
        void failNextCommit(RuntimeException failure){synchronized(lock){nextCommitFailure=failure;}}
        @Override public Object transactionLock(){return lock;}
        @Override public String string(String key,String fallback){synchronized(lock){return strings.getOrDefault(key,fallback);}}
        @Override public boolean bool(String key,boolean fallback){synchronized(lock){return booleans.getOrDefault(key,fallback);}}
        @Override public boolean contains(String key){synchronized(lock){return strings.containsKey(key)||booleans.containsKey(key);}}
        @Override public boolean commit(Map<String,String> values,Map<String,Boolean> flags,Set<String> removals){
            synchronized(lock){
                if(nextCommitFailure!=null){RuntimeException failure=nextCommitFailure;nextCommitFailure=null;throw failure;}
                for(String key:removals){strings.remove(key);booleans.remove(key);}
                strings.putAll(values);booleans.putAll(flags);return true;
            }
        }
    }
    private static void cleanupScopedActivityPreferences(Context target,String fixtureId){
        File dir=new File(target.getApplicationInfo().dataDir,"shared_prefs");
        File[] files=dir.listFiles((parent,name)->name.startsWith("ui-fixture-"+fixtureId+"-")&&name.endsWith(".xml"));
        if(files!=null)for(File file:files)target.deleteSharedPreferences(file.getName().substring(0,file.getName().length()-4));
    }
    private static void deleteTree(File file){
        if(file==null||!file.exists())return;
        File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);
        assertTrue("remove only UUID-owned fixture file: "+file.getName(),file.delete());
    }
}
