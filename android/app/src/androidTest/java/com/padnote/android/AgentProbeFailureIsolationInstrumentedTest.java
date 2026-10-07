package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** UUID-owned instrumentation for durable probe failures; never uses the default connection store. */
@RunWith(AndroidJUnit4.class)
public final class AgentProbeFailureIsolationInstrumentedTest {
    private static final String KEY_ALIAS = "padnote-agent-token-v1";
    private static final String TOKEN = "synthetic-probe-r3-token";

    @Test public void isolatedFailureSurvivesReopenShowsGuidanceAndRetrySupersedesOldAttempts()
            throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String fixtureId = UUID.randomUUID().toString();
        String preferencesName = "probe-failure-r3-" + fixtureId;
        String activityPreferencesPrefix = "ui-fixture-" + fixtureId + "-";
        File fixtureRoot = new File(target.getFilesDir(), "ui-fixtures/" + fixtureId);
        File preferencesFile = new File(new File(target.getApplicationInfo().dataDir,
                "shared_prefs"), preferencesName + ".xml");
        assertFalse("refusing existing UUID fixture", fixtureRoot.exists());
        assertFalse("refusing existing UUID preferences", preferencesFile.exists());
        Set<String> aliasesBefore = keyAliases();
        SharedPreferences preferences = target.getSharedPreferences(preferencesName,
                Context.MODE_PRIVATE);
        Object sharedLock = new Object();
        ScopedPreferencesBackend backend = new ScopedPreferencesBackend(preferences, sharedLock);
        AgentConnectionStore store = new AgentConnectionStore(backend, () -> 1_800_000_000_000L);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ActivityScenario<IsolatedLibraryRestoreActivity> scenario = null;
        File evidence = null;
        try {
            AgentConnectionStore.Config profile = store.add("Probe R3 Fixture",
                    AgentConnectionStore.Kind.HERMES, AgentConnectionStore.Transport.DIRECT,
                    "https://probe-r3.invalid", TOKEN);
            AgentConnectionStore.Config other = store.add("Probe R3 Other",
                    AgentConnectionStore.Kind.HERMES, AgentConnectionStore.Transport.DIRECT,
                    "https://other-r3.invalid", "synthetic-other-r3-token");

            String oldAttempt = store.beginProbe(profile.id, profile.revision);
            profile = store.update(profile.id, profile.revision, "Probe R3 Fixture Renamed",
                    profile.kind, profile.transport, profile.endpoint, "");
            assertEquals(1L, profile.revision);
            assertTrue(store.applyProbeSuccess(profile.id, profile.revision, oldAttempt,
                    new AgentConnectionClient.ProbeResult("synthetic", "", "probe-r3-instance",
                            Arrays.asList("run_status"))));

            String staleAttempt = store.beginProbe(profile.id, profile.revision);
            String latestSuccess = store.beginProbe(profile.id, profile.revision);
            assertFalse(staleAttempt.isEmpty());
            assertFalse(latestSuccess.isEmpty());
            assertNotEquals(staleAttempt, latestSuccess);
            assertTrue(store.applyProbeSuccess(profile.id, profile.revision, latestSuccess,
                    new AgentConnectionClient.ProbeResult("synthetic", "", "probe-r3-instance",
                            Arrays.asList("run_status"))));
            assertFalse(store.applyProbeFailure(profile.id, profile.revision, staleAttempt,
                    AgentConnectionStore.ProbeFailure.TIMEOUT));
            assertTrue(store.get(profile.id).verified());
            assertTrue(store.get(profile.id).capabilities.contains("run_status"));
            assertNull(store.get(profile.id).probeFailure);

            String identityBoundAttempt = store.beginProbe(profile.id, profile.revision);
            long priorRevision = profile.revision;
            profile = store.update(profile.id, profile.revision, profile.name, profile.kind,
                    profile.transport, "https://probe-r3-new.invalid", TOKEN);
            assertEquals(priorRevision + 1L, profile.revision);
            assertFalse(store.applyProbeFailure(profile.id, priorRevision, identityBoundAttempt,
                    AgentConnectionStore.ProbeFailure.TIMEOUT));
            assertFalse(store.get(profile.id).verified());

            String failureAttempt = store.beginProbe(profile.id, profile.revision);
            profile = store.update(profile.id, profile.revision, "Probe R3 Fixture Renamed Again",
                    profile.kind, profile.transport, profile.endpoint, "");
            assertTrue(store.applyProbeFailure(profile.id, profile.revision, failureAttempt,
                    AgentConnectionStore.ProbeFailure.TIMEOUT));

            failureAttempt = store.beginProbe(profile.id, profile.revision);
            String sentinel = "Bearer PRIVATE_R3 https://user:password@private.invalid/path?key=PRIVATE_R3 note=PRIVATE_R3";
            AgentConnectionStore.ProbeFailure safeFailure = AgentConnectionClient.safeFailure(
                    new java.net.SocketTimeoutException(sentinel));
            assertEquals(AgentConnectionStore.ProbeFailure.TIMEOUT, safeFailure);
            assertTrue(store.applyProbeFailure(profile.id, profile.revision, failureAttempt,
                    safeFailure));
            assertFalse(store.get(profile.id).verified());
            assertTrue(store.get(profile.id).capabilities.isEmpty());
            assertNull(store.get(other.id).probeFailure);

            // Reopen over the same UUID preferences and lock as a process-restart persistence check.
            store = reopen(preferences, sharedLock);
            AgentConnectionStore.Config reloaded = store.get(profile.id);
            assertEquals(AgentConnectionStore.ProbeFailure.TIMEOUT, reloaded.probeFailure);
            assertEquals(TOKEN, reloaded.token);
            assertFalse(reloaded.verified());
            assertTrue(reloaded.capabilities.isEmpty());
            String registryBytes = preferences.getString("connections.v2", "");
            assertFalse(registryBytes.contains(TOKEN));
            assertFalse(registryBytes.contains(sentinel));
            assertFalse(registryBytes.contains("PRIVATE_R3"));
            assertFalse(registryBytes.contains("user:password"));

            Intent launch = new Intent(target, IsolatedLibraryRestoreActivity.class)
                    .putExtra("fixture_id", fixtureId);
            scenario = ActivityScenario.launch(launch);
            ActivityScenario<IsolatedLibraryRestoreActivity> active = scenario;
            AgentConnectionStore dialogStore = store;
            scenario.onActivity(activity -> new AgentConnectionDialogs(activity, dialogStore,
                    worker, () -> { }).show());

            onView(withText(containsString("Probe R3 Fixture"))).inRoot(isDialog())
                    .check(matches(isDisplayed())).perform(click());
            onView(withText("能力与下一步")).inRoot(isDialog()).check(matches(isDisplayed()))
                    .perform(click());
            onView(withText(containsString("上次测试：连接超时"))).inRoot(isDialog())
                    .check(matches(isDisplayed()));
            onView(withText(containsString("检查电脑是否开机及网络是否可达")))
                    .inRoot(isDialog()).check(matches(isDisplayed()));
            captureScreenshot(active, "probe-failure-timeout-guidance", fixtureId);

            // A pending attempt UUID survives reopen but never blocks a new retry generation.
            String interruptedAttempt = store.beginProbe(profile.id, profile.revision);
            assertFalse(interruptedAttempt.isEmpty());
            AgentConnectionStore afterRestart = reopen(preferences, sharedLock);
            assertEquals(AgentConnectionStore.ProbeFailure.TIMEOUT,
                    afterRestart.get(profile.id).probeFailure);
            String retryAttempt = afterRestart.beginProbe(profile.id, profile.revision);
            assertFalse(retryAttempt.isEmpty());
            assertNotEquals(interruptedAttempt, retryAttempt);
            assertFalse(afterRestart.applyProbeFailure(profile.id, profile.revision,
                    interruptedAttempt, AgentConnectionStore.ProbeFailure.NETWORK));
            assertTrue(afterRestart.applyProbeSuccess(profile.id, profile.revision, retryAttempt,
                    new AgentConnectionClient.ProbeResult("synthetic", "", "probe-r3-instance",
                            Collections.singletonList("run_status"))));
            assertNull(afterRestart.get(profile.id).probeFailure);
            assertTrue(afterRestart.get(profile.id).verified());
            assertNull(afterRestart.get(other.id).probeFailure);
            assertEquals(TOKEN, afterRestart.get(profile.id).token);
            assertEquals("synthetic-other-r3-token", afterRestart.get(other.id).token);
            AgentConnectionStore verifySuccessAfterReopen = reopen(preferences, sharedLock);
            assertNull(verifySuccessAfterReopen.get(profile.id).probeFailure);
            assertTrue(verifySuccessAfterReopen.get(profile.id).verified());
        } finally {
            if (scenario != null) scenario.close();
            worker.shutdownNow();
            assertTrue("UUID preference cleanup commit failed",
                    preferences.edit().clear().commit());
            target.deleteSharedPreferences(preferencesName);
            assertFalse("UUID preference remains", preferencesFile.exists());
            cleanupScopedActivityPreferences(target, activityPreferencesPrefix);
            File[] children = fixtureRoot.listFiles();
            if (children != null) {
                for (File child : children) {
                    if ("evidence".equals(child.getName())) evidence = child;
                    else deleteTree(child);
                }
            }
            if (fixtureRoot.exists()) {
                File[] remaining = fixtureRoot.listFiles();
                assertTrue("only UUID-owned evidence may remain", remaining == null ||
                        (remaining.length == 1 && evidence != null && remaining[0].equals(evidence)));
            }
            cleanupOnlyNewAgentKey(aliasesBefore);
            System.out.println("AGENT_PROBE_R3_FIXTURE=" + fixtureId);
            System.out.println("AGENT_PROBE_R3_EVIDENCE=" +
                    (evidence == null ? "none" : evidence.getAbsolutePath()));
        }
    }

    private static AgentConnectionStore reopen(SharedPreferences preferences, Object lock) {
        return new AgentConnectionStore(new ScopedPreferencesBackend(preferences, lock),
                () -> 1_800_000_000_001L);
    }

    private static Set<String> keyAliases() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        Set<String> result = new HashSet<>();
        Enumeration<String> aliases = keys.aliases();
        while (aliases.hasMoreElements()) result.add(aliases.nextElement());
        return result;
    }

    private static void cleanupOnlyNewAgentKey(Set<String> aliasesBefore) throws Exception {
        Set<String> after = keyAliases();
        Set<String> added = new HashSet<>(after);
        added.removeAll(aliasesBefore);
        Set<String> unexpected = new HashSet<>(added);
        unexpected.remove(KEY_ALIAS);
        assertTrue("unexpected AndroidKeyStore aliases created: " + unexpected, unexpected.isEmpty());
        boolean removed = false;
        if (!aliasesBefore.contains(KEY_ALIAS) && after.contains(KEY_ALIAS)) {
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
            keys.load(null);
            keys.deleteEntry(KEY_ALIAS);
            removed = true;
        }
        Set<String> finalAliases = keyAliases();
        assertEquals("test must not leave aliases it created", aliasesBefore, finalAliases);
        System.out.println("AGENT_PROBE_R3_KEYSTORE_ADDED=" + added);
        System.out.println("AGENT_PROBE_R3_KEYSTORE_CLEANED_NEW_APP_ALIAS=" + removed);
    }

    private static void cleanupScopedActivityPreferences(Context target, String prefix) {
        File directory = new File(target.getApplicationInfo().dataDir, "shared_prefs");
        File[] files = directory.listFiles((parent, name) -> name.startsWith(prefix) &&
                name.endsWith(".xml"));
        if (files != null) for (File file : files) {
            String name = file.getName().substring(0, file.getName().length() - 4);
            target.deleteSharedPreferences(name);
            assertFalse("isolated Activity preference remains: " + name, file.exists());
        }
        File[] leftovers = directory.listFiles((parent, name) -> name.startsWith(prefix));
        assertTrue("isolated Activity preference residue", leftovers == null || leftovers.length == 0);
    }

    private static void captureScreenshot(ActivityScenario<IsolatedLibraryRestoreActivity> scenario,
                                         String name, String fixtureId) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("UI screenshot unavailable", bitmap);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); }
        finally { bitmap.recycle(); }
        byte[] png = output.toByteArray();
        assertTrue("screenshot must be bounded", png.length > 8 && png.length <= 16 * 1024 * 1024);
        String[] saved = new String[1];
        scenario.onActivity(activity -> {
            try { saved[0] = activity.writeEvidenceScreenshot(name + ".png", png).getAbsolutePath(); }
            catch (Exception error) { throw new AssertionError("screenshot save failed", error); }
        });
        assertNotNull(saved[0]);
        assertTrue(saved[0].contains("/ui-fixtures/" + fixtureId + "/evidence/"));
        System.out.println("AGENT_PROBE_R3_SCREENSHOT=" + saved[0]);
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete() && file.exists()) throw new AssertionError("fixture cleanup failed: " + file);
    }

    private static final class ScopedPreferencesBackend implements AgentConnectionStore.Backend {
        private final SharedPreferences preferences;
        private final Object lock;
        ScopedPreferencesBackend(SharedPreferences preferences, Object lock) {
            this.preferences = preferences; this.lock = lock;
        }
        @Override public Object transactionLock() { return lock; }
        @Override public String string(String key, String fallback) {
            synchronized (lock) { return preferences.getString(key, fallback); }
        }
        @Override public boolean bool(String key, boolean fallback) {
            synchronized (lock) { return preferences.getBoolean(key, fallback); }
        }
        @Override public boolean contains(String key) {
            synchronized (lock) { return preferences.contains(key); }
        }
        @Override public boolean commit(Map<String, String> strings,
                                        Map<String, Boolean> booleans, Set<String> removals) {
            synchronized (lock) {
                SharedPreferences.Editor editor = preferences.edit();
                for (String key : removals) editor.remove(key);
                for (Map.Entry<String, String> entry : strings.entrySet())
                    editor.putString(entry.getKey(), entry.getValue());
                for (Map.Entry<String, Boolean> entry : booleans.entrySet())
                    editor.putBoolean(entry.getKey(), entry.getValue());
                return editor.commit();
            }
        }
    }
}
