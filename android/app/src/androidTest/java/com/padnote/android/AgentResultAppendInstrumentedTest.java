package com.padnote.android;

import static org.junit.Assert.*;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Real Canvas layout and schema checks using an in-memory note; never writes app note storage. */
@RunWith(AndroidJUnit4.class)
public final class AgentResultAppendInstrumentedTest {
    @Test public void appendPreflightsAtDocumentEndAndCommitsExactlyOnce() throws Exception {
        try (CanvasSession session = CanvasSession.open()) {
            AtomicReference<String> originalFlowSource = new AtomicReference<>();
            AtomicReference<String> originalFlowId = new AtomicReference<>();
            onActivityAndRethrow(session.scenario, activity -> {
                NoteTextBox existing = session.canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                        "Existing handwritten context", dp(activity, 24), dp(activity, 24));
                assertNotNull(existing);
                originalFlowSource.set(existing.source);
                originalFlowId.set(existing.flowId);
            });

            String taskId = UUID.randomUUID().toString();
            String output = "Result text from an isolated completed task.";
            AgentTaskStore.Task task = new AgentTaskStore.Task(taskId, "fixture-connection", 1L,
                    "fixture-origin", AgentConnectionStore.Kind.HERMES, AgentConnectionStore.Transport.BRIDGE,
                    "fixture-credential", "fixture-bridge", "fixture-instance", "Fixture task", "Fixture",
                    "fixture-note", 10L, "{}", AgentResultProvenance.sha256("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "fixture-remote", AgentTaskStore.Status.COMPLETED, output, "", "", "", "",
                    Collections.emptyList(), 10L, 11L);
            AgentResultProvenance provenance = AgentResultProvenance.create(task, "fixture-note", 10L, output);
            AtomicReference<NoteCanvasView.AgentResultAppendCandidate> candidateRef = new AtomicReference<>();

            onActivityAndRethrow(session.scenario, activity -> {
                JSONObject before = session.canvas.toJsonDocument("fixture-note", "Fixture");
                JSONArray beforeFlows = before.getJSONArray("textFlows");
                assertEquals(1, beforeFlows.length());
                NoteCanvasView.AgentResultAppendCandidate candidate = session.canvas.prepareAgentResultAppend(
                        "fixture-note", "Fixture", output, provenance);
                assertFalse(candidate.alreadyApplied);
                NoteStore.validateDocument(candidate.document);
                assertEquals(9, candidate.document.getInt("schemaVersion"));
                assertEquals(2, candidate.document.getJSONArray("textFlows").length());
                assertEquals("the existing flow remains byte-for-byte the same source",
                        originalFlowSource.get(), candidate.document.getJSONArray("textFlows")
                                .getJSONObject(0).getString("source"));
                assertEquals("preflight does not mutate the live canvas", 1,
                        session.canvas.toJsonDocument("fixture-note", "Fixture")
                                .getJSONArray("textFlows").length());
                candidateRef.set(candidate);
            });

            onActivityAndRethrow(session.scenario, activity -> {
                NoteTextBox last = session.canvas.commitAgentResultAppend(candidateRef.get());
                assertNotNull("the in-memory commit happens only after caller durability", last);
                assertEquals(originalFlowId.get(), session.canvas.toJsonDocument("fixture-note", "Fixture")
                        .getJSONArray("textFlows").getJSONObject(0).getString("id"));
                JSONObject saved = session.canvas.toJsonDocument("fixture-note", "Fixture");
                assertEquals(9, saved.getInt("schemaVersion"));
                assertEquals(2, saved.getJSONArray("textFlows").length());
                JSONObject resultFlow = saved.getJSONArray("textFlows").getJSONObject(1);
                assertEquals(output, resultFlow.getString("source"));
                assertEquals(provenance.applicationId, resultFlow.getJSONObject("agentResult")
                        .getString("applicationId"));
                NoteStore.validateDocument(saved);
                NoteCanvasView.AgentResultAppendCandidate retry = session.canvas.prepareAgentResultAppend(
                        "fixture-note", "Fixture", output,
                        AgentResultProvenance.create(task, "fixture-note", 10L, output));
                assertTrue("same application identity is recognized even when retry timestamp differs",
                        retry.alreadyApplied);
                assertEquals("duplicate result is not appended", 2,
                        retry.document.getJSONArray("textFlows").length());
                JSONObject afterUndoOfLastReceipt = new JSONObject(saved.toString())
                        .put("schemaVersion", 9).put("textFlows", new JSONArray());
                try {
                    session.canvas.loadJsonDocument(afterUndoOfLastReceipt);
                    assertEquals("a valid schema-9 note remains readable after its last receipt flow is removed",
                            8, session.canvas.toJsonDocument("fixture-note", "Fixture").getInt("schemaVersion"));
                } catch (org.json.JSONException invalid) {
                    throw new AssertionError("schema-9 empty receipt note should be accepted", invalid);
                }
            });
        }
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private interface ActivityWork { void run(MainActivity activity) throws Throwable; }

    private static void onActivityAndRethrow(ActivityScenario<MainActivity> scenario,
                                             ActivityWork work) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        scenario.onActivity(activity -> {
            try { work.run(activity); }
            catch (Throwable error) { failure.set(error); }
        });
        Throwable error = failure.get();
        if (error == null) return;
        if (error instanceof Error) throw (Error) error;
        if (error instanceof Exception) throw (Exception) error;
        throw new AssertionError("Activity assertion failed", error);
    }

    private static final class CanvasSession implements AutoCloseable {
        final ActivityScenario<MainActivity> scenario;
        final NoteCanvasView canvas;

        private CanvasSession(ActivityScenario<MainActivity> scenario, NoteCanvasView canvas) {
            this.scenario = scenario;
            this.canvas = canvas;
        }

        static CanvasSession open() {
            ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
            AtomicReference<NoteCanvasView> canvasRef = new AtomicReference<>();
            scenario.onActivity(activity -> {
                int width = dp(activity, 768), height = dp(activity, 1024);
                FrameLayout root = new FrameLayout(activity);
                NoteCanvasView canvas = new NoteCanvasView(activity);
                root.addView(canvas, new FrameLayout.LayoutParams(width, height));
                activity.setContentView(root, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                canvas.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                canvas.layout(0, 0, width, height);
                canvasRef.set(canvas);
            });
            return new CanvasSession(scenario, canvasRef.get());
        }

        @Override public void close() { scenario.close(); }
    }
}
