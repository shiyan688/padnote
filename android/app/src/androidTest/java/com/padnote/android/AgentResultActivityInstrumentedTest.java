package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Real task-detail → preview → confirmation → durable note flow, isolated to target-app fixtures. */
@RunWith(AndroidJUnit4.class)
public final class AgentResultActivityInstrumentedTest {
    @Test public void taskDetailAppendsDurablyReopensAndDoesNotDuplicate() throws Exception {
        try (Fixture fixture = Fixture.create();
             ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.showCachedAgentTaskForTest(
                    fixture.task.clientTaskId, fixture.tasks));
            openResultPreview();
            confirmResult();
            await(() -> {
                try {
                    JSONObject saved = NoteStore.load(fixture.context, fixture.note.id);
                    JSONArray flows = saved.optJSONArray("textFlows");
                    if (saved.optInt("schemaVersion") != 9 || flows == null
                            || countOrigin(flows, fixture.applicationId) != 1) return false;
                    final boolean[] idle = {false};
                    scenario.onActivity(activity -> idle[0] = activity.agentResultAppendIdleForTest());
                    return idle[0];
                } catch (Exception ignored) { return false; }
            }, "result was not durably saved");

            onView(withText(R.string.flow_close)).perform(click());
            onView(withContentDescription(R.string.core_back_shelf)).perform(click());
            clickNoteWhenVisible(fixture.context, fixture.note.title);
            await(() -> {
                final boolean[] ready = {false};
                scenario.onActivity(activity -> {
                    try {
                        JSONObject live = activity.canvasForTest().toJsonDocument(fixture.note.id,
                                fixture.note.title);
                        JSONArray flows = live.optJSONArray("textFlows");
                        ready[0] = flows != null && countOrigin(flows, fixture.applicationId) == 1;
                    } catch (Exception ignored) { }
                });
                return ready[0];
            }, "reopened paper did not contain the saved Agent flow");

            scenario.onActivity(activity -> activity.showCachedAgentTaskForTest(
                    fixture.task.clientTaskId, fixture.tasks));
            await(() -> {
                try {
                    onView(withText(R.string.flow_append_result_to_note)).check(matches(isDisplayed()));
                    return true;
                } catch (RuntimeException | AssertionError unavailable) { return false; }
            }, "completed task detail did not expose its result action");
            onView(withText(R.string.flow_append_result_to_note)).perform(click());
            onView(withText(R.string.flow_agent_result_preview_title)).check(doesNotExist());
            JSONObject afterDuplicate = NoteStore.load(fixture.context, fixture.note.id);
            assertEquals(9, afterDuplicate.getInt("schemaVersion"));
            assertEquals("a second add attempt is idempotent", 1,
                    countOrigin(afterDuplicate.getJSONArray("textFlows"), fixture.applicationId));
        }
    }

    @Test public void stalePreviewCannotOverwriteConcurrentNoteRevision() throws Exception {
        try (Fixture fixture = Fixture.create();
             ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.showCachedAgentTaskForTest(
                    fixture.task.clientTaskId, fixture.tasks));
            openResultPreview();

            JSONObject concurrent = NoteStore.load(fixture.context, fixture.note.id);
            String expected = NoteStore.legacyBodySha256(fixture.context, fixture.note.id);
            concurrent.put("title", fixture.note.title + " changed elsewhere");
            NoteStore.saveLegacyIfBodyMatches(fixture.context, fixture.note.id,
                    fixture.note.title + " changed elsewhere", NoteJsonCodec.stringify(concurrent), expected);

            confirmResult();
            await(() -> {
                try {
                    JSONObject saved = NoteStore.load(fixture.context, fixture.note.id);
                    if (!(fixture.note.title + " changed elsewhere").equals(saved.optString("title"))) return false;
                    final boolean[] idle = {false};
                    scenario.onActivity(activity -> idle[0] = activity.agentResultAppendIdleForTest());
                    return idle[0];
                } catch (Exception ignored) { return false; }
            }, "concurrent note revision was not preserved");
            JSONObject afterRejectedPreview = NoteStore.load(fixture.context, fixture.note.id);
            assertEquals("stale preview must not append to the changed target", 0,
                    afterRejectedPreview.getJSONArray("textFlows").length());
            assertEquals(fixture.note.title + " changed elsewhere", afterRejectedPreview.getString("title"));
        }
    }

    private static void openResultPreview() throws Exception {
        await(() -> {
            try {
                onView(withText(R.string.flow_append_result_to_note)).check(matches(isDisplayed()));
                return true;
            } catch (RuntimeException | AssertionError unavailable) { return false; }
        }, "task result action did not become visible");
        onView(withText(R.string.flow_append_result_to_note)).perform(scrollTo(), click());
        onView(withText(R.string.flow_agent_result_preview_title)).check(matches(isDisplayed()));
    }

    private static void confirmResult() {
        onView(withText(R.string.flow_agent_result_append_confirm)).perform(click());
    }

    private static int countOrigin(JSONArray flows, String applicationId) throws Exception {
        int count = 0;
        for (int i = 0; i < flows.length(); i++) {
            JSONObject receipt = flows.getJSONObject(i).optJSONObject("agentResult");
            if (receipt != null && applicationId.equals(receipt.optString("applicationId"))) count++;
        }
        return count;
    }

    private static void clickNoteWhenVisible(Context context, String title) throws Exception {
        String description = context.getString(R.string.core_note_card_open_desc, title);
        await(() -> {
            try { onView(withContentDescription(description)).perform(scrollTo(), click()); return true; }
            catch (RuntimeException | AssertionError unavailable) { return false; }
        }, "bookshelf did not show the isolated test note");
    }

    private static void await(BooleanSupplier condition, String failure) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            try { if (condition.getAsBoolean()) return; }
            catch (RuntimeException | AssertionError error) { last = error; }
            Thread.sleep(50L);
        }
        AssertionError timeout = new AssertionError(failure);
        if (last != null) timeout.initCause(last);
        throw timeout;
    }

    private static final class Fixture implements AutoCloseable {
        final Context context;
        final NoteStore.Entry note;
        final AgentTaskStore tasks;
        final AgentTaskStore.Task task;
        final String applicationId;
        final File taskDirectory;

        private Fixture(Context context, NoteStore.Entry note, AgentTaskStore tasks,
                        AgentTaskStore.Task task, String applicationId, File taskDirectory) {
            this.context = context; this.note = note; this.tasks = tasks; this.task = task;
            this.applicationId = applicationId; this.taskDirectory = taskDirectory;
        }

        static Fixture create() throws Exception {
            // The instrumented target app context is the only note-store authority used by this
            // test. The note ID is generated per run; no user filename or device path is assumed.
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            String suffix = UUID.randomUUID().toString();
            NoteStore.Entry note = NoteStore.create(context, "Agent result integration " + suffix);
            File taskDirectory = new File(context.getCacheDir(), "agent-result-ui-" + suffix);
            AgentTaskStore tasks = new AgentTaskStore(taskDirectory);
            AgentConnectionStore.Config connection = new AgentConnectionStore.Config(
                    "fixture-" + suffix.replace("-", ""), "Isolated fixture", AgentConnectionStore.Kind.HERMES,
                    "https://fixture.invalid", "synthetic-only", "fixture-credential",
                    AgentConnectionStore.Transport.BRIDGE, "fixture-bridge", "fixture-instance",
                    1L, 1L, Arrays.asList("run_submission", "run_status"));
            AgentTaskStore.Task task = tasks.create(connection, "Completed fixture task",
                    "Synthetic completed output", note.id, note.updatedAt, null);
            if (!tasks.applySubmission(task.clientTaskId, connection.id, connection.revision,
                    "fixture-remote", AgentTaskStore.Status.RUNNING))
                throw new IllegalStateException("FIXTURE_SUBMISSION_FAILED");
            AgentTaskClient.RemoteStatus completed = new AgentTaskClient.RemoteStatus(
                    AgentTaskStore.Status.COMPLETED, "Synthetic completed Agent result", "", "", "", "",
                    Collections.emptyList());
            if (!tasks.applyStatus(task.clientTaskId, connection.id, connection.revision,
                    "fixture-remote", completed)) throw new IllegalStateException("FIXTURE_COMPLETION_FAILED");
            AgentTaskStore.Task savedTask = tasks.get(task.clientTaskId);
            if (!savedTask.clientTaskId.matches("padnote-[0-9a-f]{32}"))
                throw new AssertionError("real AgentTaskStore ID format changed unexpectedly");
            AgentResultProvenance provenance = AgentResultProvenance.create(savedTask, note.id,
                    note.updatedAt, savedTask.output);
            if (!savedTask.clientTaskId.equals(provenance.taskId))
                throw new AssertionError("provenance must preserve the real stored task ID");
            return new Fixture(context, note, tasks, savedTask, provenance.applicationId, taskDirectory);
        }

        @Override public void close() throws Exception {
            try { NoteStore.delete(context, note.id); }
            finally { deleteTree(taskDirectory); }
        }

        private static void deleteTree(File file) {
            if (file == null || !file.exists()) return;
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
            if (!file.delete() && file.exists()) throw new IllegalStateException("FIXTURE_CLEANUP_FAILED");
        }
    }
}
