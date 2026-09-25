package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Device checks for the real material picker and MainActivity context reset path. */
@RunWith(AndroidJUnit4.class)
public final class AiReadScopeInstrumentedTest {
    private static final String OLD_A = "OLD_AUTHORIZED_A_TOOL_RESULT_481";

    @Test
    public void materialPickerDisplaysCheckboxesAndReturnsOnlyCheckedIds() {
        AtomicReference<Set<String>> chosen = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                List<VaultStore.VaultNote> notes = Arrays.asList(
                        new VaultStore.VaultNote("a.md", "note-a", "材料 A", 1, 1L, 1L),
                        new VaultStore.VaultNote("b.md", "note-b", "材料 B", 1, 1L, 1L));
                AlertDialog dialog = activity.createAiMaterialPickerDialog(notes,
                        new String[]{"材料 A", "材料 B"}, new boolean[]{true, false}, chosen::set);
                dialog.show();
                assertTrue(dialog.getListView().isShown());
                assertTrue(dialog.getListView().getChoiceMode()
                        == android.widget.ListView.CHOICE_MODE_MULTIPLE);
                assertTrue(dialog.getListView().getCount() == 2);
            });
            // This assertion targets the actual dialog window. In a full suite,
            // a preceding ActivityScenario can leave its activity root visible
            // for one WindowManager focus transition even though this dialog's
            // ListView is already attached and shown.
            onView(withText("材料 A")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("材料 B")).inRoot(isDialog())
                    .check(matches(isDisplayed())).perform(click());
            onView(withText("冻结选定材料")).inRoot(isDialog()).perform(click());
            assertTrue(chosen.get().contains("note-a"));
            assertTrue(chosen.get().contains("note-b"));
        }
    }

    @Test
    public void productionScopeAndProfileResetDropOldConversationAndInvalidateCallbacks()
            throws Exception {
        SnapshotVault vault = new SnapshotVault();
        AiReadScope withA = AiReadScope.selectionOnly("note-current", 1, "profile-a")
                .withVault(AiVaultSnapshot.capture(vault,
                        new LinkedHashSet<>(Collections.singletonList("note-a"))));

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    activity.replaceAiReadScope(withA, "fixture");
                    messages(activity).add(OpenAiCompatibleClient.Message.toolResult(
                            "call-a", new JSONObject().put("content", OLD_A).toString()));
                    set(activity, "aiSessionTranscript", "OLD_TRANSCRIPT");
                    set(activity, "aiToolsHonoured", true);
                    set(activity, "aiUploadConfirmed", true);
                    int oldSession = (int) get(activity, "aiSessionSerial");

                    activity.replaceAiReadScope(withA.withVault(null), "收紧材料");
                    int narrowedSession = (int) get(activity, "aiSessionSerial");
                    assertTrue(narrowedSession > oldSession);
                    assertTrue(messages(activity).isEmpty());
                    assertNull(get(activity, "aiSessionTranscript"));
                    assertFalse((boolean) get(activity, "aiToolsHonoured"));
                    assertFalse((boolean) get(activity, "aiUploadConfirmed"));

                    AiReadScope narrowed = (AiReadScope) get(activity, "aiReadScope");
                    String narrowedWire = OpenAiCompatibleClient.buildRequest(
                            "model", null, new ArrayList<>(messages(activity)),
                            narrowed.createToolRegistry().describe(), new JSONObject()).toString();
                    assertFalse(narrowedWire.contains(OLD_A));
                    assertFalse(narrowedWire.contains("OLD_TRANSCRIPT"));
                    assertFalse(narrowedWire.contains("search_vault"));

                    messages(activity).add(new OpenAiCompatibleClient.Message("user", OLD_A));
                    set(activity, "aiSessionTranscript", "OLD_TRANSCRIPT");
                    set(activity, "aiToolsHonoured", true);
                    AiConfigStore.Profile changed = new AiConfigStore.Profile();
                    changed.id = "profile-b";
                    changed.directEndpoint = "https://example.invalid/v1";
                    changed.directModel = "new-model";
                    activity.resetAiContextForProfileChange(changed);
                    int profileSession = (int) get(activity, "aiSessionSerial");
                    assertTrue(profileSession > narrowedSession);
                    assertTrue(messages(activity).isEmpty());
                    assertNull(get(activity, "aiSessionTranscript"));
                    assertFalse((boolean) get(activity, "aiToolsHonoured"));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
        }
    }

    @Test
    public void busyRecipientChangesDoNotMutateStoreThenIdleSwitchRotatesWireContext()
            throws Exception {
        SnapshotVault vault = new SnapshotVault();
        AiVaultSnapshot frozenA = AiVaultSnapshot.capture(vault,
                new LinkedHashSet<>(Collections.singletonList("note-a")));

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                AiConfigStore store = null;
                String originalActive = null;
                AiConfigStore.Profile profileA = profile("scope-fixture-a", "model-a");
                AiConfigStore.Profile profileB = profile("scope-fixture-b", "model-b");
                try {
                    store = (AiConfigStore) get(activity, "aiConfigStore");
                    originalActive = store.activeProfileId();
                    store.deleteProfile(profileA.id);
                    store.deleteProfile(profileB.id);
                    store.saveProfile(profileA, null, null, null);
                    store.saveProfile(profileB, null, null, null);
                    store.setActiveProfileId(profileA.id);

                    activity.replaceAiReadScope(AiReadScope.selectionOnly(
                            "note-current", 1, profileA.id).withVault(frozenA), "fixture");
                    seedOldWireContext(activity);
                    int oldSession = (int) get(activity, "aiSessionSerial");

                    set(activity, "aiBusy", true);
                    assertFalse(activity.activateAiProfile(profileB));
                    assertEquals(profileA.id, store.activeProfileId());

                    AiConfigStore.Profile editedActive = profile(profileA.id, "model-edited");
                    // The helper derives recipient impact from the stored active ID;
                    // a stale dialog cannot bypass the gate by passing activate=false.
                    assertFalse(activity.saveAiProfile(
                            editedActive, null, null, null, false));
                    assertEquals("model-a", store.activeProfile().directModel);
                    assertFalse(activity.saveAiProfile(
                            editedActive, null, null, null, true));
                    assertEquals("model-a", store.activeProfile().directModel);
                    assertFalse(activity.deleteAiProfile(profileA));
                    assertNotNull(findProfile(store, profileA.id));
                    assertEquals(oldSession, get(activity, "aiSessionSerial"));
                    assertTrue(messages(activity).get(0).content.contains(OLD_A));

                    set(activity, "aiBusy", false);
                    set(activity, "aiMaterialLoading", true);
                    assertFalse(activity.activateAiProfile(profileB));
                    assertEquals(profileA.id, store.activeProfileId());

                    set(activity, "aiMaterialLoading", false);
                    assertTrue(activity.activateAiProfile(profileB));
                    assertEquals(profileB.id, store.activeProfileId());
                    assertTrue((int) get(activity, "aiSessionSerial") > oldSession);
                    assertWireContextCleared(activity);
                    AiReadScope switched = (AiReadScope) get(activity, "aiReadScope");
                    assertEquals(Collections.singleton("note-a"), switched.vaultNoteIds());
                    assertFalse(requestWire(activity, switched).contains(OLD_A));
                } catch (Exception error) {
                    throw new AssertionError(error);
                } finally {
                    if (store != null) {
                        store.deleteProfile(profileA.id);
                        store.deleteProfile(profileB.id);
                        store.setActiveProfileId(originalActive == null ? "" : originalActive);
                    }
                }
            });
        }
    }

    @Test
    public void permissionChangeRotatesWireContextButRetainsFrozenMaterials() throws Exception {
        SnapshotVault vault = new SnapshotVault();
        AiVaultSnapshot frozenA = AiVaultSnapshot.capture(vault,
                new LinkedHashSet<>(Collections.singletonList("note-a")));

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    set(activity, "currentNoteId", "note-current");
                    activity.replaceAiReadScope(AiReadScope.selectionOnly(
                            "note-current", 1, "profile-a").withVault(frozenA), "fixture");
                    seedOldWireContext(activity);
                    int oldSession = (int) get(activity, "aiSessionSerial");
                    boolean wasInline = (boolean) get(activity, "aiOutputInline");

                    activity.setAiOutputInline(!wasInline, false);

                    assertEquals(!wasInline, get(activity, "aiOutputInline"));
                    assertTrue((int) get(activity, "aiSessionSerial") > oldSession);
                    assertWireContextCleared(activity);
                    AiReadScope changed = (AiReadScope) get(activity, "aiReadScope");
                    assertEquals(Collections.singleton("note-a"), changed.vaultNoteIds());
                    String wire = requestWire(activity, changed);
                    assertFalse(wire.contains(OLD_A));
                    assertFalse(wire.contains("OLD_TRANSCRIPT"));
                    assertTrue(wire.contains("search_vault"));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
        }
    }

    private static AiConfigStore.Profile profile(String id, String model) {
        AiConfigStore.Profile profile = new AiConfigStore.Profile();
        profile.id = id;
        profile.name = "隔离夹具 " + id;
        profile.directEndpoint = "https://fixture.invalid/v1";
        profile.directModel = model;
        return profile;
    }

    private static AiConfigStore.Profile findProfile(AiConfigStore store, String id) {
        for (AiConfigStore.Profile profile : store.listProfiles()) {
            if (id.equals(profile.id)) return profile;
        }
        return null;
    }

    private static void seedOldWireContext(MainActivity activity) throws Exception {
        messages(activity).add(OpenAiCompatibleClient.Message.toolResult(
                "call-old", new JSONObject().put("content", OLD_A).toString()));
        executedToolCalls(activity).add("request-old\u0000call-old");
        set(activity, "aiSessionTranscript", "OLD_TRANSCRIPT");
        set(activity, "aiToolsHonoured", true);
        set(activity, "aiUploadConfirmed", true);
    }

    private static void assertWireContextCleared(MainActivity activity) throws Exception {
        assertTrue(messages(activity).isEmpty());
        assertTrue(executedToolCalls(activity).isEmpty());
        assertNull(get(activity, "aiSessionTranscript"));
        assertFalse((boolean) get(activity, "aiToolsHonoured"));
        assertFalse((boolean) get(activity, "aiUploadConfirmed"));
    }

    private static String requestWire(MainActivity activity, AiReadScope scope) throws Exception {
        return OpenAiCompatibleClient.buildRequest(
                "model", null, new ArrayList<>(messages(activity)),
                scope.createToolRegistry().describe(), new JSONObject()).toString();
    }

    @SuppressWarnings("unchecked")
    private static List<OpenAiCompatibleClient.Message> messages(MainActivity activity)
            throws Exception {
        return (List<OpenAiCompatibleClient.Message>) get(activity, "aiMessages");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> executedToolCalls(MainActivity activity) throws Exception {
        return (Set<String>) get(activity, "aiExecutedToolCallIds");
    }

    private static Object get(MainActivity activity, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }

    private static void set(MainActivity activity, String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(activity, value);
    }

    private static final class SnapshotVault implements NoteTools.VaultReader {
        @Override public List<VaultStore.VaultNote> list() {
            return Collections.singletonList(new VaultStore.VaultNote(
                    "a.md", "note-a", "材料 A", 1, 1L, 1L));
        }

        @Override public String read(String fileName) {
            return OLD_A;
        }
    }
}
