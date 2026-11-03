package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** MainActivity bookshelf-entry checks on UUID-isolated storage, with an in-memory connection store. */
@RunWith(AndroidJUnit4.class)
public final class AgentConnectionDialogsTest {
    private static final String PROFILE_ID = "11111111-1111-4111-8111-111111111111";
    private static final String PROFILE_NAME = "UI fixture profile";

    @Test public void isolatedBookshelfEntryShowsAndOpensConnectionPaths() throws Exception {
        Fixture fixture = new Fixture(false);
        ActivityScenario<IsolatedLibraryRestoreActivity> scenario = null;
        try {
            scenario = fixture.launch();
            installFixtureStore(scenario, fixture);

            openAgentMenu();
            onView(withText(R.string.flow_view_tasks)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(R.string.flow_manual_add)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(R.string.flow_scan_pair)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(R.string.flow_paste_pair)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(R.string.flow_connect_other)).inRoot(isDialog()).check(matches(isDisplayed()));

            onView(withText(R.string.flow_connect_other)).inRoot(isDialog()).perform(click());
            onView(withText(R.string.widget_guide_close)).inRoot(isDialog()).check(matches(isDisplayed()));
            pressBack();

            openAgentMenu();
            onView(withText(R.string.flow_manual_add)).inRoot(isDialog()).perform(click());
            onView(withText(R.string.flow_add_agent)).inRoot(isDialog()).check(matches(isDisplayed()));
            pressBack();

            openAgentMenu();
            onView(withText(R.string.flow_paste_pair)).inRoot(isDialog()).perform(click());
            onView(withText(R.string.flow_pairing_title)).inRoot(isDialog()).check(matches(isDisplayed()));
            pressBack();

            openAgentMenu();
            onView(withText(R.string.flow_view_tasks)).inRoot(isDialog()).perform(click());
            onView(withText(R.string.flow_computer_tasks)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(R.string.flow_task_list_empty)).inRoot(isDialog()).check(matches(isDisplayed()));
            pressBack();
        } finally {
            try {
                if (scenario != null) scenario.close();
            } finally {
                fixture.cleanup();
            }
        }
    }

    @Test public void isolatedBookshelfEntryShowsVerifiedProfileActions() throws Exception {
        Fixture fixture = new Fixture(true);
        ActivityScenario<IsolatedLibraryRestoreActivity> scenario = null;
        try {
            scenario = fixture.launch();
            installFixtureStore(scenario, fixture);

            openAgentMenu();
            onView(withText(containsString(PROFILE_NAME))).inRoot(isDialog()).perform(click());
            onView(withText(R.string.flow_connection_actions)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(R.string.flow_send_text_task)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(R.string.flow_capabilities)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(R.string.flow_test_connection)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(R.string.flow_edit)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(R.string.flow_delete)).inRoot(isDialog()).check(matches(isDisplayed()));
            pressBack();
        } finally {
            try {
                if (scenario != null) scenario.close();
            } finally {
                fixture.cleanup();
            }
        }
    }

    private static void openAgentMenu() {
        onView(withText(R.string.core_agent)).perform(click());
    }

    private static void installFixtureStore(ActivityScenario<IsolatedLibraryRestoreActivity> scenario,
                                            Fixture fixture) {
        scenario.onActivity(activity -> {
            assertEquals("isolated activity owns the expected UUID root",
                    fixture.root.getAbsolutePath(), activity.fixtureRoot().getAbsolutePath());
            try {
                Field field = MainActivity.class.getDeclaredField("agentConnectionStore");
                field.setAccessible(true);
                field.set(activity, fixture.store);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("could not install fixture-only connection backend", error);
            }
        });
    }

    private static final class Fixture {
        private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        private final String id = UUID.randomUUID().toString();
        private final String preferencePrefix = "ui-fixture-" + id + "-";
        private final File root = new File(target.getFilesDir(), "ui-fixtures/" + id);
        private final File preferences = new File(target.getApplicationInfo().dataDir, "shared_prefs");
        private final AgentConnectionStore store;

        Fixture(boolean withVerifiedProfile) throws Exception {
            assertFalse("refusing a reused fixture UUID", root.exists());
            assertNoFixturePreferences();
            MemoryBackend backend = new MemoryBackend();
            if (withVerifiedProfile) backend.seedVerifiedProfile();
            store = new AgentConnectionStore(backend, () -> 1_700_000_000_000L);
        }

        ActivityScenario<IsolatedLibraryRestoreActivity> launch() {
            Intent intent = new Intent(target, IsolatedLibraryRestoreActivity.class)
                    .putExtra("fixture_id", id);
            return ActivityScenario.launch(intent);
        }

        void cleanup() {
            assertTrue("fixture root remains under this UUID", isOwnedFixtureRoot(root, id));
            deleteTree(root);
            File[] files = preferences.listFiles((directory, name) -> name.startsWith(preferencePrefix));
            if (files != null) {
                for (File file : files) {
                    assertTrue("preference cleanup is restricted to this UUID prefix",
                            file.getName().startsWith(preferencePrefix));
                    assertTrue("could not remove isolated preference fixture", file.delete() || !file.exists());
                }
            }
            assertNoFixturePreferences();
            assertFalse("isolated fixture root remains", root.exists());
        }

        private void assertNoFixturePreferences() {
            File[] files = preferences.listFiles((directory, name) -> name.startsWith(preferencePrefix));
            assertTrue("refusing a reused preference UUID", files == null || files.length == 0);
        }
    }

    private static boolean isOwnedFixtureRoot(File root, String id) {
        try {
            File expectedParent = new File(root.getParentFile().getParentFile(), "ui-fixtures").getCanonicalFile();
            return id.matches("[0-9a-f-]{36}") && root.getName().equals(id) &&
                    root.getCanonicalFile().getParentFile().equals(expectedParent);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete() && file.exists()) throw new AssertionError("isolated fixture cleanup failed");
    }

    private static final class MemoryBackend implements AgentConnectionStore.Backend {
        private final Object lock = new Object();
        private final Map<String, String> strings = new LinkedHashMap<>();
        private final Map<String, Boolean> booleans = new HashMap<>();

        void seedVerifiedProfile() throws Exception {
            JSONObject profile = new JSONObject()
                    .put("id", PROFILE_ID)
                    .put("name", PROFILE_NAME)
                    .put("kind", AgentConnectionStore.Kind.HERMES.name())
                    .put("endpoint", "https://fixture.invalid")
                    .put("credentialRef", "fixture-only-credential-ref")
                    .put("transport", AgentConnectionStore.Transport.DIRECT.name())
                    .put("revision", 1)
                    .put("verifiedAt", 1_700_000_000_000L)
                    .put("capabilities", new JSONArray().put("run_submission").put("run_status"));
            strings.put("connections.v2", new JSONObject().put("schemaVersion", 2)
                    .put("defaultId", PROFILE_ID).put("connections", new JSONArray().put(profile)).toString());
        }

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
        @Override public boolean commit(Map<String, String> additions, Map<String, Boolean> flags,
                                        Set<String> removals) {
            synchronized (lock) {
                for (String key : new LinkedHashSet<>(removals)) {
                    strings.remove(key);
                    booleans.remove(key);
                }
                strings.putAll(additions);
                booleans.putAll(flags);
                return true;
            }
        }
    }
}
