package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

import android.content.Context;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.padnote.android.streaming.StreamingGroupStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Exercises the actual MainActivity typography action, full paper dialog, cancellation and save CAS. */
@RunWith(AndroidJUnit4.class)
public final class MarkdownTypographyActivityInstrumentedTest {
    @Test public void actualTypographyDialogCancelsThenRejectsStaleAndCommitsOnConfirmation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Fixture fixture = Fixture.create(context);
        NoteGroupFacade groups = new NoteGroupFacade(context);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            open(scenario, fixture.entry, fixture.flowId);
            fixture.initial = awaitEditorStable(scenario, fixture, groups,
                    fixture.source, 1);
            triggerTypographyButton(scenario, fixture);
            awaitDialogReady(scenario, fixture, groups, fixture.source, 1);
            java.util.concurrent.atomic.AtomicReference<String> pageBefore = new java.util.concurrent.atomic.AtomicReference<>();
            onView(withTagValue(is("padnote.typography-preview.page-label"))).inRoot(isDialog())
                    .check((view, noView) -> pageBefore.set(((android.widget.TextView) view).getText().toString()));
            onView(withContentDescription(R.string.canvas_typography_next_page)).inRoot(isDialog()).perform(click());
            onView(withTagValue(is("padnote.typography-preview.page-label"))).inRoot(isDialog())
                    .check((view, noView) -> assertNotEquals(pageBefore.get(),
                            ((android.widget.TextView) view).getText().toString()));
            onView(withText(R.string.canvas_typography_keep)).inRoot(isDialog()).perform(click());
            StreamingGroupStore.Snapshot afterCancel = groups.openGroup(fixture.entry.id);
            assertEquals("cancel leaves the group revision unchanged", fixture.initial.revision, afterCancel.revision);
            assertEquals(fixture.initial.digest, afterCancel.digest);
            assertLegacyUnchanged(groups, fixture);

            awaitEditorStable(scenario, fixture, groups, fixture.source, 1);
            triggerTypographyButton(scenario, fixture);
            awaitDialogReady(scenario, fixture, groups, fixture.source, 1);
            scenario.onActivity(activity -> {
                NoteCanvasView canvas = activity.canvasForTest();
                NoteTextBox box = canvas.getTextBoxes().stream()
                        .filter(value -> fixture.flowId.equals(value.flowId)).findFirst()
                        .orElseThrow(AssertionError::new);
                NoteCanvasView.TextEditResult edit = canvas.updateTextBox(box.id,
                        NoteTextBox.Format.MARKDOWN, fixture.source + "\n\nconcurrent edit",
                        box.fontSizeSp, box.lineHeight);
                assertTrue(edit.committed);
            });
            onView(withText(R.string.canvas_typography_apply_new)).inRoot(isDialog()).perform(click());
            StreamingGroupStore.Snapshot staleSaved = waitForStoredSource(groups,
                    fixture.entry.id, fixture.initial.revision,
                    fixture.source + "\n\nconcurrent edit");
            assertNotEquals("stale-save test must observe a new durable group revision",
                    fixture.initial.revision, staleSaved.revision);
            JSONObject staleState = readGroupDocument(staleSaved);
            assertEquals(9, NoteStore.schemaVersion(staleState));
            JSONObject staleFlow = findFlow(staleState, fixture.flowId);
            assertFalse("a stale preview cannot set the profile", staleFlow.has("markdownProfileVersion"));
            assertEquals(fixture.anchorPageIndex, staleFlow.getInt("anchorPageIndex"));
            assertEquals(fixture.receiptJson.toString(), staleFlow.getJSONObject("agentResult").toString());

            open(scenario, fixture.entry, fixture.flowId);
            awaitEditorStable(scenario, fixture, groups,
                    fixture.source + "\n\nconcurrent edit", 1);
            triggerTypographyButton(scenario, fixture);
            awaitDialogReady(scenario, fixture, groups, fixture.source + "\n\nconcurrent edit", 1);
            onView(withText(R.string.canvas_typography_apply_new)).inRoot(isDialog()).perform(click());
            StreamingGroupStore.Snapshot finalSaved = waitForStoredProfile(groups,
                    fixture.entry.id, fixture.flowId, staleSaved.revision, 2);
            assertNotEquals("confirmed switch must create a newer durable group revision",
                    staleSaved.revision, finalSaved.revision);
            open(scenario, fixture.entry, fixture.flowId);
            awaitEditorStable(scenario, fixture, groups,
                    fixture.source + "\n\nconcurrent edit", 2);
            scenario.onActivity(activity -> {
                TextFlow flow = activity.canvasForTest().getTextFlows().stream()
                        .filter(value -> fixture.flowId.equals(value.id)).findFirst()
                        .orElseThrow(AssertionError::new);
                assertEquals(2, flow.effectiveMarkdownProfileVersion());
                assertEquals(fixture.source + "\n\nconcurrent edit", flow.source);
                assertNotNull("receipt remains attached after reopening", flow.agentResult);
            });
            JSONObject reopened = readGroupDocument(groups.openGroup(fixture.entry.id));
            assertEquals(10, NoteStore.schemaVersion(reopened));
            JSONObject finalFlow = findFlow(reopened, fixture.flowId);
            assertEquals(2, finalFlow.getInt("markdownProfileVersion"));
            assertEquals(fixture.source + "\n\nconcurrent edit", finalFlow.getString("source"));
            assertEquals(fixture.anchorPageIndex, finalFlow.getInt("anchorPageIndex"));
            assertEquals("receipt bytes survive profile migration", fixture.receiptJson.toString(),
                    finalFlow.getJSONObject("agentResult").toString());
        }
    }

    private static void triggerTypographyButton(ActivityScenario<MainActivity> scenario,
            Fixture fixture) throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean clicked =
                new java.util.concurrent.atomic.AtomicBoolean();
        scenario.onActivity(activity -> {
            try {
                if (!fixture.entry.id.equals(readStringField(activity, "currentNoteId")) ||
                        !readBooleanField(activity, "restoreCompleted"))
                    throw new AssertionError("target synthetic note is not the restored editor note");
                if (readBooleanField(activity, "saveInFlight") ||
                        readLongField(activity, "documentRevision") !=
                                readLongField(activity, "lastSavedRevision") ||
                        readBooleanField(activity, "agentResultAppendInFlight") ||
                        (Boolean) invoke(activity, "hasPendingManualDraftClear",
                                new Class<?>[]{String.class}, fixture.entry.id))
                    throw new AssertionError("editor save/append/draft guard was not ready before real action click");
                TextFlow flow = activity.canvasForTest().getTextFlows().stream()
                        .filter(value -> fixture.flowId.equals(value.id)).findFirst()
                        .orElseThrow(() -> new AssertionError("target flow is not in the restored canvas"));
                NoteTextBox first = activity.canvasForTest().getTextBoxes().stream()
                        .filter(box -> fixture.flowId.equals(box.flowId) && box.isFlowStart())
                        .findFirst().orElseThrow(() -> new AssertionError("target flow has no first fragment"));
                if (first.pageIndex < flow.anchorPageIndex)
                    throw new AssertionError("first fragment precedes its persisted flow anchor");
                activity.onTextBoxSelectionChanged(fixture.flowId);
                NoteTextBoxView selected = findBoundFlowStart(activity, first.id, fixture.flowId);
                if (selected == null || !selected.isAttachedToWindow() || !selected.isShown())
                    throw new AssertionError("target first fragment overlay is not visibly bound");
                Field buttonField = NoteTextBoxView.class.getDeclaredField("selectedTypographyButton");
                buttonField.setAccessible(true);
                View button = (View) buttonField.get(selected);
                if (button == null || button.getVisibility() != View.VISIBLE || !button.isShown())
                    throw new AssertionError("real typography action button is not visible");
                clicked.set(button.performClick());
            } catch (Exception error) { throw new AssertionError(error); }
        });
        assertTrue("real typography button click was not handled", clicked.get());
    }

    private static NoteTextBoxView findBoundFlowStart(MainActivity activity,
            String fragmentId, String flowId) throws Exception {
        try {
            Field viewsField = MainActivity.class.getDeclaredField("textBoxViews");
            viewsField.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String, NoteTextBoxView> views =
                    (Map<String, NoteTextBoxView>) viewsField.get(activity);
            for (NoteTextBoxView view : views.values()) {
                NoteTextBox bound = view.getBoundTextBox();
                if (bound != null && fragmentId.equals(bound.id) && flowId.equals(bound.flowId) &&
                        bound.isFlowStart()) return view;
            }
            return null;
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void awaitDialogReady(ActivityScenario<MainActivity> scenario,
            Fixture fixture, NoteGroupFacade groups, String expectedSource,
            int expectedProfile) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        String lastEspressoFailure = "no dialog observation yet";
        String lastLabel = "unavailable";
        while (System.nanoTime() < deadline) {
            try {
                java.util.concurrent.atomic.AtomicReference<String> label =
                        new java.util.concurrent.atomic.AtomicReference<>("unavailable");
                onView(withTagValue(is("padnote.typography-preview.page-label")))
                        .inRoot(isDialog()).check((view, noView) -> label.set(
                                ((android.widget.TextView) view).getText().toString()));
                lastLabel = label.get();
                if (isTerminalPreviewLabel(scenario, lastLabel))
                    throw new AssertionError("preview page label reached terminal failure: " + lastLabel);
                onView(withText(R.string.canvas_typography_apply_new)).inRoot(isDialog())
                        .check(matches(isEnabled()));
                onView(withTagValue(is("padnote.typography-preview.page-label")))
                        .inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withContentDescription(R.string.canvas_typography_next_page))
                        .inRoot(isDialog()).check(matches(isEnabled()));
                return;
            } catch (AssertionError | RuntimeException notReady) {
                lastEspressoFailure = notReady.getClass().getSimpleName() + ": " +
                        String.valueOf(notReady.getMessage());
                java.util.concurrent.atomic.AtomicReference<EditorObservation> observation =
                        new java.util.concurrent.atomic.AtomicReference<>();
                scenario.onActivity(activity -> {
                    try { observation.set(captureEditorObservation(activity, fixture,
                            expectedSource, expectedProfile)); }
                    catch (Exception ignored) { }
                });
                EditorObservation state = observation.get();
                String editorState = state == null ? "activity state unavailable" : state.diagnostic;
                boolean guardToast = false;
                try {
                    onView(withText(R.string.canvas_typography_save_first))
                            .check(matches(isDisplayed()));
                    guardToast = true;
                } catch (AssertionError | RuntimeException ignored) { }
                if (guardToast) lastEspressoFailure = "save-first guard toast appeared after button click";
                String durableState = "group unavailable";
                try {
                    StreamingGroupStore.Snapshot group = groups.openGroup(fixture.entry.id);
                    JSONObject doc = readGroupDocument(group);
                    JSONObject flow = findFlow(doc, fixture.flowId);
                    durableState = "groupRevisionPresent=" + (group.revision != null) +
                            ",groupDigestPresent=" + (group.digest != null) +
                            ",storedSchema=" + NoteStore.schemaVersion(doc) +
                            ",storedFlow=" + (flow != null) +
                            ",storedSourceMatches=" + (flow != null && expectedSource.equals(flow.optString("source"))) +
                            ",storedProfile=" + (flow == null ? -1 : flow.optInt("markdownProfileVersion", 1)) +
                            ",storedAnchorMatches=" + (flow != null &&
                                    flow.optInt("anchorPageIndex", -1) == fixture.anchorPageIndex);
                } catch (Exception storageFailure) {
                    durableState = "groupRead=" + storageFailure.getClass().getSimpleName();
                }
                lastLabel = editorState + "; " + durableState + "; pageLabel=" + lastLabel;
                if (guardToast) throw new AssertionError("real button hit save guard: " + lastLabel);
                if (isTerminalPreviewLabel(scenario, labelFrom(lastLabel)))
                    throw new AssertionError("typography preview failed: lastEspresso=" +
                            lastEspressoFailure + "; " + lastLabel);
                Thread.sleep(150);
            }
        }
        throw new AssertionError("typography preview not ready; lastEspresso=" +
                lastEspressoFailure + "; lastPageLabel=" + lastLabel);
    }

    private static String labelFrom(String diagnostic) {
        int marker = diagnostic.lastIndexOf("pageLabel=");
        return marker < 0 ? diagnostic : diagnostic.substring(marker + "pageLabel=".length());
    }

    private static boolean isTerminalPreviewLabel(ActivityScenario<MainActivity> scenario,
            String label) {
        if (label == null || "unavailable".equals(label)) return false;
        java.util.concurrent.atomic.AtomicReference<Boolean> terminal =
                new java.util.concurrent.atomic.AtomicReference<>(false);
        scenario.onActivity(activity -> {
            String measureFailed = activity.getString(R.string.canvas_typography_measurement_failed);
            String stale = activity.getString(R.string.canvas_typography_stale);
            String failedTemplate = activity.getString(R.string.canvas_typography_preview_failed);
            String failurePrefix = failedTemplate.contains("%s")
                    ? failedTemplate.substring(0, failedTemplate.indexOf("%s")) : failedTemplate;
            terminal.set(label.equals(measureFailed) || label.equals(stale) ||
                    (!failurePrefix.isEmpty() && label.startsWith(failurePrefix)));
        });
        return terminal.get();
    }

    private static StreamingGroupStore.Snapshot awaitEditorStable(
            ActivityScenario<MainActivity> scenario, Fixture fixture, NoteGroupFacade groups,
            String expectedSource, int expectedProfile) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
        String previous = null;
        long unchangedSince = 0;
        String lastDiagnostic = "not observed";
        while (System.nanoTime() < deadline) {
            java.util.concurrent.atomic.AtomicReference<EditorObservation> sampled =
                    new java.util.concurrent.atomic.AtomicReference<>();
            scenario.onActivity(activity -> {
                try { sampled.set(captureEditorObservation(activity, fixture,
                        expectedSource, expectedProfile)); }
                catch (Exception error) {
                    sampled.set(EditorObservation.failed(error.getClass().getSimpleName()));
                }
            });
            EditorObservation state = sampled.get();
            lastDiagnostic = state == null ? "no activity sample" : state.diagnostic;
            boolean durable = false;
            StreamingGroupStore.Snapshot current = null;
            try {
                current = groups.openGroup(fixture.entry.id);
                JSONObject stored = readGroupDocument(current);
                JSONObject flow = findFlow(stored, fixture.flowId);
                durable = state != null && state.editorStable && flow != null &&
                        state.currentNoteId.equals(fixture.entry.id) &&
                        current.revision.equals(state.groupRevision) &&
                        current.digest.equals(state.groupDigest) &&
                        NoteStore.schemaVersion(stored) == (expectedProfile == 2 ? 10 : 9) &&
                        expectedSource.equals(flow.optString("source")) &&
                        flow.optInt("anchorPageIndex", -1) == fixture.anchorPageIndex &&
                        (expectedProfile == 2
                                ? flow.optInt("markdownProfileVersion", -1) == 2
                                : !flow.has("markdownProfileVersion")) &&
                        fixture.receiptJson.toString().equals(
                                flow.getJSONObject("agentResult").toString());
            } catch (Exception ignored) { }
            lastDiagnostic += "; durableFlowMatches=" + durable;
            if (state != null && state.renderReady && durable) {
                String signature = state.signature + ":" + current.revision + ":" + current.digest;
                long now = System.nanoTime();
                if (signature.equals(previous)) {
                    if (unchangedSince > 0 && now - unchangedSince >=
                            TimeUnit.MILLISECONDS.toNanos(700)) return current;
                } else {
                    previous = signature;
                    unchangedSince = now;
                }
            } else {
                previous = null;
                unchangedSince = 0;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("editor did not settle before typography action: " + lastDiagnostic);
    }

    private static EditorObservation captureEditorObservation(MainActivity activity,
            Fixture fixture, String expectedSource, int expectedProfile) throws Exception {
        String noteId = readStringField(activity, "currentNoteId");
        boolean restored = readBooleanField(activity, "restoreCompleted");
        boolean saving = readBooleanField(activity, "saveInFlight");
        boolean saveQueued = readBooleanField(activity, "saveQueued");
        boolean viewportSaving = readBooleanField(activity, "viewportSaveInFlight");
        boolean appendSaving = readBooleanField(activity, "agentResultAppendInFlight");
        boolean draftClearPending = (Boolean) invoke(activity, "hasPendingManualDraftClear",
                new Class<?>[]{String.class}, fixture.entry.id);
        long revision = readLongField(activity, "documentRevision");
        long savedRevision = readLongField(activity, "lastSavedRevision");
        String groupRevision = readStringField(activity, "currentGroupRevision");
        String groupDigest = readStringField(activity, "currentGroupDigest");
        NoteCanvasView canvas = activity.canvasForTest();
        if (canvas == null) return EditorObservation.failed("canvas-unavailable");
        TextFlow flow = canvas.getTextFlows().stream().filter(v -> fixture.flowId.equals(v.id))
                .findFirst().orElse(null);
        NoteTextBox first = canvas.getTextBoxes().stream()
                .filter(box -> fixture.flowId.equals(box.flowId) && box.isFlowStart())
                .findFirst().orElse(null);
        NoteTextBoxView view = first == null ? null : findBoundFlowStart(activity, first.id, fixture.flowId);
        boolean overlayReady = view != null && view.isAttachedToWindow() && view.isShown();
        boolean renderReady = false;
        String renderState = "missing";
        if (overlayReady && first != null && view != null) {
            String renderedSource = (String) readObjectField(view, "renderedSource");
            int renderedProfile = readIntField(view, "renderedMarkdownProfileVersion");
            int renderedEpoch = readIntField(view, "renderedLayoutEpoch");
            float renderedWidth = readFloatField(view, "renderedWidth");
            boolean failed = readBooleanField(view, "renderFailed");
            boolean editing = readBooleanField(view, "inlineEditing");
            Object compiled = readObjectField(view, "compiledView");
            int generation = readIntField(compiled, "renderGeneration");
            int settled = readIntField(compiled, "settledGeneration");
            renderReady = !failed && !editing && first.displaySource().equals(renderedSource) &&
                    first.effectiveMarkdownProfileVersion() == renderedProfile &&
                    first.renderLayoutEpoch == renderedEpoch &&
                    Math.abs(first.width - renderedWidth) < 0.5f && generation == settled;
            renderState = "ready=" + renderReady + ",failed=" + failed + ",editing=" + editing +
                    ",epoch=" + renderedEpoch + "/" + first.renderLayoutEpoch +
                    ",generation=" + settled + "/" + generation;
        }
        boolean editorStable = fixture.entry.id.equals(noteId) && restored && !saving &&
                !saveQueued && !viewportSaving && !appendSaving && !draftClearPending &&
                revision == savedRevision &&
                flow != null && expectedSource.equals(flow.source) &&
                flow.effectiveMarkdownProfileVersion() == expectedProfile &&
                flow.anchorPageIndex == fixture.anchorPageIndex && overlayReady;
        String signature = noteId + "|" + revision + "|" + savedRevision + "|" +
                canvas.documentGeneration() + "|" + canvas.getPageCount() + "|" +
                (flow == null ? "no-flow" : flow.source + "|" + flow.effectiveMarkdownProfileVersion() +
                        "|" + flow.anchorPageIndex) + "|" +
                (first == null ? "no-start" : first.id + "|" + first.pageIndex + "|" +
                        first.renderLayoutEpoch + "|" + first.height) + "|" + renderState;
        String diagnostic = "noteMatch=" + fixture.entry.id.equals(noteId) + ",restored=" + restored +
                ",documentGeneration=" + canvas.documentGeneration() +
                ",pageCount=" + canvas.getPageCount() +
                ",firstPage=" + (first == null ? -1 : first.pageIndex) +
                ",saveInFlight=" + saving + ",saveQueued=" + saveQueued +
                ",viewportSaveInFlight=" + viewportSaving + ",appendInFlight=" + appendSaving +
                ",draftClearPending=" + draftClearPending +
                ",editorRevision=" + revision + "/" + savedRevision +
                ",groupRevisionPresent=" + (groupRevision != null) +
                ",groupDigestPresent=" + (groupDigest != null) + ",flow=" + (flow != null) +
                ",sourceMatches=" + (flow != null && expectedSource.equals(flow.source)) +
                ",profile=" + (flow == null ? -1 : flow.effectiveMarkdownProfileVersion()) +
                ",anchor=" + (flow == null ? -1 : flow.anchorPageIndex) +
                ",fragments=" + canvas.getTextBoxes().stream()
                        .filter(box -> fixture.flowId.equals(box.flowId)).count() +
                ",overlay=" + overlayReady + ",render=" + renderState;
        return new EditorObservation(editorStable, renderReady, noteId, groupRevision, groupDigest,
                signature, diagnostic);
    }

    private static final class EditorObservation {
        final boolean editorStable, renderReady;
        final String currentNoteId, groupRevision, groupDigest, signature, diagnostic;
        EditorObservation(boolean editorStable, boolean renderReady, String currentNoteId,
                String groupRevision, String groupDigest, String signature, String diagnostic) {
            this.editorStable=editorStable; this.renderReady=renderReady;
            this.currentNoteId=currentNoteId == null ? "" : currentNoteId;
            this.groupRevision=groupRevision; this.groupDigest=groupDigest;
            this.signature=signature; this.diagnostic=diagnostic;
        }
        static EditorObservation failed(String why) {
            return new EditorObservation(false,false,"",null,null,"failed",why);
        }
    }

    private static void open(ActivityScenario<MainActivity> scenario, NoteStore.Entry entry,
            String targetFlowId) throws Exception {
        scenario.onActivity(activity -> {
            try { invoke(activity, "openNote", new Class<?>[]{NoteStore.Entry.class, PageStyle.class}, entry, null); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        java.util.concurrent.atomic.AtomicReference<String> lastState =
                new java.util.concurrent.atomic.AtomicReference<>("not checked");
        while (System.nanoTime() < deadline) {
            java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
            scenario.onActivity(activity -> {
                try {
                    String currentId = readStringField(activity, "currentNoteId");
                    boolean restored = readBooleanField(activity, "restoreCompleted");
                    NoteCanvasView canvas = activity.canvasForTest();
                    TextFlow target = canvas == null ? null : canvas.getTextFlows().stream()
                            .filter(value -> targetFlowId.equals(value.id)).findFirst().orElse(null);
                    NoteTextBox first = canvas == null ? null : canvas.getTextBoxes().stream()
                            .filter(box -> targetFlowId.equals(box.flowId) && box.isFlowStart())
                            .findFirst().orElse(null);
                    if (!entry.id.equals(currentId) || !restored || target == null || first == null) {
                        lastState.set("current=" + entry.id.equals(currentId) + ",restored=" +
                                restored + ",flow=" + (target != null) + ",first=" + (first != null));
                        return;
                    }
                    int firstVisible = canvas.getFirstVisiblePageIndex();
                    int lastVisible = canvas.getLastVisiblePageIndex();
                    if (first.pageIndex < firstVisible - 1 || first.pageIndex > lastVisible + 1) {
                        canvas.goToPage(first.pageIndex);
                    }
                    activity.onTextBoxesChanged(canvas.getTextBoxes());
                    NoteTextBoxView bound = findBoundFlowStart(activity, first.id, targetFlowId);
                    boolean visible = bound != null && bound.isAttachedToWindow() && bound.isShown();
                    boolean inViewport = first.pageIndex >= canvas.getFirstVisiblePageIndex() - 1 &&
                            first.pageIndex <= canvas.getLastVisiblePageIndex() + 1;
                    ready.set(visible && inViewport);
                    lastState.set("current=" + entry.id.equals(currentId) + ",restored=" +
                            restored + ",flowFragments=" + canvas.getTextBoxes().stream()
                                    .filter(box -> targetFlowId.equals(box.flowId)).count() +
                            ",firstPage=" + first.pageIndex + ",visible=" + firstVisible + ".." +
                            lastVisible + ",bound=" + visible);
                } catch (Exception error) { throw new AssertionError(error); }
            });
            if (ready.get()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("synthetic note/flow-start overlay not ready: " + lastState.get());
    }

    private static String readStringField(MainActivity activity, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(activity);
        return value instanceof String ? (String) value : null;
    }

    private static boolean readBooleanField(MainActivity activity, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(activity);
    }

    private static Object readObjectField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static boolean readBooleanField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static int readIntField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static long readLongField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(target);
    }

    private static float readFloatField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getFloat(target);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static void assertLegacyUnchanged(NoteGroupFacade groups, Fixture fixture) throws Exception {
        StreamingGroupStore.Snapshot current = groups.openGroup(fixture.entry.id);
        assertEquals(fixture.initial.revision, current.revision);
        assertEquals(fixture.initial.digest, current.digest);
        JSONObject doc = readGroupDocument(current);
        assertEquals(9, NoteStore.schemaVersion(doc));
        JSONObject flow = findFlow(doc, fixture.flowId);
        assertFalse(flow.has("markdownProfileVersion"));
        assertEquals(fixture.anchorPageIndex, flow.getInt("anchorPageIndex"));
        assertEquals(fixture.source, flow.getString("source"));
        assertEquals(fixture.receiptJson.toString(), flow.getJSONObject("agentResult").toString());
    }

    private static StreamingGroupStore.Snapshot waitForStoredSource(NoteGroupFacade groups,
            String noteId, String previousRevision, String source) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            StreamingGroupStore.Snapshot current = groups.openGroup(noteId);
            JSONObject doc = readGroupDocument(current);
            JSONObject flow = findFlow(doc, null);
            if (!previousRevision.equals(current.revision) && flow != null &&
                    source.equals(flow.optString("source"))) return current;
            Thread.sleep(50);
        }
        throw new AssertionError("concurrent edit was not durably saved");
    }

    private static StreamingGroupStore.Snapshot waitForStoredProfile(NoteGroupFacade groups,
            String noteId, String flowId, String previousRevision, int version) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            StreamingGroupStore.Snapshot current = groups.openGroup(noteId);
            JSONObject doc = readGroupDocument(current);
            JSONObject flow = findFlow(doc, flowId);
            if (!previousRevision.equals(current.revision) && NoteStore.schemaVersion(doc) == 10 &&
                    flow != null && flow.optInt("markdownProfileVersion") == version) return current;
            Thread.sleep(50);
        }
        throw new AssertionError("confirmed typography profile was not durably saved");
    }

    private static JSONObject readGroupDocument(StreamingGroupStore.Snapshot group) throws Exception {
        byte[] body = group.readSmall("body.bin", (int) StreamingGroupStore.BODY_MAX);
        return NotePrecisionJsonParser.parseObject(new String(body, StandardCharsets.UTF_8));
    }

    private static JSONObject findFlow(JSONObject doc, String flowId) throws Exception {
        JSONArray flows = doc.getJSONArray("textFlows");
        for (int i = 0; i < flows.length(); i++) {
            JSONObject flow = flows.getJSONObject(i);
            if (flowId == null || flowId.equals(flow.optString("id"))) return flow;
        }
        return null;
    }

    private static final class Fixture {
        final NoteStore.Entry entry;
        final String flowId, source;
        final JSONObject receiptJson;
        StreamingGroupStore.Snapshot initial;
        final int anchorPageIndex;
        Fixture(NoteStore.Entry entry, String flowId, String source, JSONObject receiptJson,
                StreamingGroupStore.Snapshot initial, int anchorPageIndex) {
            this.entry = entry; this.flowId = flowId; this.source = source;
            this.receiptJson = receiptJson; this.initial = initial;
            this.anchorPageIndex = anchorPageIndex;
        }
        static Fixture create(Context context) throws Exception {
            String fixtureNonce = UUID.randomUUID().toString();
            NoteStore.Entry entry = NoteStore.create(context, "Typography Activity Fixture " + fixtureNonce);
            String noteId = entry.id;
            if (noteId == null || noteId.isEmpty())
                throw new AssertionError("synthetic note creation returned no durable note id");
            String flowId = UUID.randomUUID().toString();
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 45; i++) text.append("A long synthetic paragraph with **strong $x^2$ math** and enough words to verify full-paper pagination. ");
            String source = text.toString();
            AgentResultProvenance receipt = new AgentResultProvenance("padnote-0123456789abcdef0123456789abcdef",
                    "synthetic-connection", null, "fixture", "local", noteId, 1,
                    AgentResultProvenance.sha256(source.getBytes(StandardCharsets.UTF_8)), 1234);
            JSONObject receiptJson = receipt.toJson();
            TextFlow flow = new TextFlow(flowId, NoteTextBox.Format.MARKDOWN, source,
                    18, 1.35, 620, 0, 40, 40, receipt);
            JSONObject document = new JSONObject().put("schemaVersion", 9).put("id", noteId)
                    .put("title", entry.title).put("updatedAt", System.currentTimeMillis())
                    .put("pageWidth", 720).put("pageHeight", 960).put("canvasWidth", 720)
                    .put("canvasHeight", 960).put("pageGap", 24).put("pageCount", 2)
                    .put("pdfPageCount", 0).put("strokes", new JSONArray())
                    .put("images", new JSONArray()).put("textFlows", new JSONArray().put(flow.toJson()));
            NoteStore.save(context, noteId, entry.title, NoteJsonCodec.stringify(document));
            NoteGroupFacade groups = new NoteGroupFacade(context);
            groups.adoptLegacyNote(entry.id, (before, after) -> true);
            StreamingGroupStore.Snapshot initial = groups.openGroup(entry.id);
            JSONObject stored = readGroupDocument(initial);
            assertEquals("fixture document identity matches the opened library entry", entry.id,
                    stored.getString("id"));
            assertEquals("fixture receipt is attached to the intended flow", flowId,
                    stored.getJSONArray("textFlows").getJSONObject(0).getString("id"));
            return new Fixture(entry, flowId, source, receiptJson, initial,
                    stored.getJSONArray("textFlows").getJSONObject(0).getInt("anchorPageIndex"));
        }
    }
}
