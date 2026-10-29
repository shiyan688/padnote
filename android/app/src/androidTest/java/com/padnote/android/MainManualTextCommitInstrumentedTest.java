package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.view.ViewGroup;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.File;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.action.ViewActions.click;

/** Regressions for MainActivity's real overlay listener and synchronous Canvas rebind. */
@RunWith(AndroidJUnit4.class)
public final class MainManualTextCommitInstrumentedTest {
    private static String repeatForTest(String value, int count) {
        if (count < 0) throw new IllegalArgumentException("count");
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
    @Test public void inputLimitAllowsRecoverableDraftAndRejectsOversizeReplacementWhole() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<String> previous = new AtomicReference<>();
            scenario.onActivity(activity -> {
                EditText input = new EditText(activity);
                input.setFilters(new android.text.InputFilter[]{new ManualDraftInputFilter(activity)});
                String recoverable = repeatForTest("x", 120_001);
                input.setText(recoverable);
                assertEquals(120_001, input.length());
                previous.set(input.getText().toString());
                input.setSelection(0, input.length());
                input.getText().replace(0, input.length(), repeatForTest("y", 1_000_001));
                assertEquals("an over-limit whole-field replacement is rejected atomically",
                        previous.get(), input.getText().toString());
            });
        }
    }

    @Test public void restoredDraftUsesStableFlowIdentityWhenFragmentIdsWereRebuilt() throws Exception {
        File recoveryDir = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getCacheDir(), "manual-draft-flow-recovery-test-" + java.util.UUID.randomUUID());
        try {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<String> continueLabel = new AtomicReference<>();
            scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = installSyntheticMainCanvas(activity);
                    String source = canvas.getTextFlows().get(0).source;
                    String oldFragmentId = canvas.getTextBoxes().get(0).id;
                    JSONObject saved = canvas.toJsonDocument("synthetic-main-manual-commit", "Synthetic");
                    canvas.loadJsonDocument(saved);
                    String rebuiltFragmentId = canvas.getTextBoxes().get(0).id;
                    assertNotEquals("canonical reload regenerates ephemeral fragment IDs",
                            oldFragmentId, rebuiltFragmentId);
                    ManualTextDraftStore store = new ManualTextDraftStore(recoveryDir);
                    ManualTextDraftStore.Draft draft = new ManualTextDraftStore.Draft(
                            "synthetic-main-manual-commit", "synthetic-long-flow", oldFragmentId,
                            "Recovered full-source draft.", NoteTextBox.Format.MARKDOWN.name(),
                            18f, 1.28f, true, ManualTextDraftStore.sourceFingerprint(source),
                            java.util.UUID.randomUUID().toString(), 1L);
                    assertEquals(ManualTextDraftStore.SaveStatus.SAVED,
                            store.save(draft, null).status);
                    set(activity, "manualTextDraftStore", store);
                    Method offer = MainActivity.class.getDeclaredMethod("offerManualDraftRecovery", String.class);
                    offer.setAccessible(true);
                    set(activity, "editorVisible", true);
                    offer.invoke(activity, "synthetic-main-manual-commit");
                    continueLabel.set(activity.getString(R.string.core_manual_draft_restore));
                    set(activity, "editorVisible", false);
                } catch (Throwable error) {
                    throw new AssertionError(error);
                }
            });
            onView(withText(continueLabel.get())).inRoot(isDialog()).perform(click());
            AtomicReference<String> restoredSource = new AtomicReference<>();
            scenario.onActivity(activity -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, NoteTextBoxView> views =
                            (Map<String, NoteTextBoxView>) get(activity, "textBoxViews");
                    for (NoteTextBoxView view : views.values()) {
                        if (!view.isInlineEditing()) continue;
                        EditText editor = (EditText) get(view, "sourceEditor");
                        restoredSource.set(editor.getText().toString());
                    }
                    assertEquals("Recovered full-source draft.", restoredSource.get());
                    @SuppressWarnings("unchecked")
                    Set<String> newlyInserted =
                            (Set<String>) get(activity, "newlyInsertedTextBoxIds");
                    assertTrue("untrusted new-placeholder flag cannot delete a nonempty existing flow",
                            newlyInserted.isEmpty());
                } catch (Throwable error) {
                    throw new AssertionError(error);
                }
            });
        }
        } finally {
            deleteTree(recoveryDir);
        }
    }

    @Test public void rejectedDoneCheckpointsTheUnmodifiedManualDraft() throws Exception {
        File recoveryDir = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getCacheDir(), "manual-draft-rejected-done-test-" + java.util.UUID.randomUUID());
        AtomicInteger manualDraftWriteStages = new AtomicInteger();
        try {
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                AtomicReference<String> attemptedSource = new AtomicReference<>();
                scenario.onActivity(activity -> {
                    try {
                        installSyntheticMainCanvas(activity);
                        ManualTextDraftStore store = new ManualTextDraftStore(recoveryDir,
                                stage -> {
                                    if (stage == NoteStore.WriteStage.TEMP_SYNCED) {
                                        manualDraftWriteStages.incrementAndGet();
                                    }
                                });
                        set(activity, "manualTextDraftStore", store);
                        @SuppressWarnings("unchecked")
                        Map<String, NoteTextBoxView> views = (Map<String, NoteTextBoxView>)
                                get(activity, "textBoxViews");
                        NoteTextBoxView editorView = views.values().iterator().next();
                        editorView.beginInlineEditing();
                        String oversizedTable = "| id | detail |\n| --- | --- |\n| 1 | `"
                                + repeatForTest("protected-cell-content-", 500) + "` |";
                        attemptedSource.set(oversizedTable);
                        EditText editor = (EditText) get(editorView, "sourceEditor");
                        editor.setText(oversizedTable);
                        assertFalse(editorView.commitInlineEditing());
                        assertTrue(editorView.isInlineEditing());
                        ManualTextDraftStore.LoadResult saved = store.load(
                                "synthetic-main-manual-commit");
                        assertEquals(ManualTextDraftStore.LoadStatus.READY, saved.status);
                        assertEquals(attemptedSource.get(), saved.draft.source);
                        Method checkpoint = MainActivity.class.getDeclaredMethod(
                                "checkpointActiveManualEditor");
                        checkpoint.setAccessible(true);
                        assertTrue("an explicit outer checkpoint remains successful",
                                (Boolean) checkpoint.invoke(activity));
                        assertEquals("the failed Done callback wrote one temporary record only",
                                1, manualDraftWriteStages.get());
                    } catch (Throwable error) {
                        throw new AssertionError(error);
                    }
                });
            }
            assertEquals("scenario close adds no test draft write", 1, manualDraftWriteStages.get());
        } finally {
            deleteTree(recoveryDir);
        }
    }

    @Test public void rejectedDoneKeepsDraftForRetryAndExplicitCancel() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            scenario.onActivity(activity -> {
                FrameLayout host = new FrameLayout(activity);
                NoteTextBoxView[] holder = new NoteTextBoxView[1];
                AtomicInteger commits = new AtomicInteger();
                AtomicInteger successes = new AtomicInteger();
                AtomicInteger cancels = new AtomicInteger();
                NoteTextBoxView.Listener listener = new NoteTextBoxView.Listener() {
                    @Override public void onSelect(NoteTextBox box) { }
                    @Override public void onEditRequested(NoteTextBox box) { }
                    @Override public void onMove(NoteTextBox box, float x, float y) { }
                    @Override public void onDragPreview(NoteTextBox box, float x, float y) { }
                    @Override public void onDragPreviewEnded() { }
                    @Override public void onResize(NoteTextBox box, float w, float h) { }
                    @Override public void onScaleToArea(NoteTextBox box, float w, float h) { }
                    @Override public void onCommit(NoteTextBox box, NoteTextBox.Format f,
                                                  String source, float font, float line) { }
                    @Override public NoteCanvasView.TextEditResult onCommitResult(
                            NoteTextBox box, NoteTextBox.Format f, String source,
                            float font, float line) {
                        commits.incrementAndGet();
                        return "retry succeeds".equals(source)
                                ? NoteCanvasView.TextEditResult.committed()
                                : NoteCanvasView.TextEditResult.rejected(TextFlowCapacity.Failure.PAGE_LIMIT);
                    }
                    @Override public void onCommitSucceeded(NoteTextBox box) {
                        assertFalse(holder[0].isInlineEditing());
                        successes.incrementAndGet();
                    }
                    @Override public void onFontSizeChanged(NoteTextBox box, float size) { }
                    @Override public void onMeasuredHeight(NoteTextBox box, float height) { }
                    @Override public void onCancel(NoteTextBox box) { cancels.incrementAndGet(); }
                    @Override public void onDelete(NoteTextBox box) { }
                };
                holder[0] = new NoteTextBoxView(activity, listener);
                host.addView(holder[0], new FrameLayout.LayoutParams(700, 500));
                activity.setContentView(host);
                holder[0].bind(new NoteTextBox("manual-ui-fragment", "manual-ui-flow", 0, 1,
                        NoteTextBox.Format.MARKDOWN, "base", "base", 18f, 1.28f,
                        0, 20f, 20f, 650f, 420f), 1f, 0f, 0f, false);
                holder[0].measure(View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
                holder[0].layout(0, 0, 700, 500);
                holder[0].beginInlineEditing();
                try {
                    EditText editor = (EditText) get(holder[0], "sourceEditor");
                    editor.setText("keep complete draft");
                    assertFalse(holder[0].commitInlineEditing());
                    assertTrue(holder[0].isInlineEditing());
                    assertEquals("keep complete draft", editor.getText().toString());
                    editor.setText("retry succeeds");
                    assertTrue(holder[0].commitInlineEditing());
                    assertFalse(holder[0].isInlineEditing());
                    assertTrue(holder[0].commitInlineEditing());
                    assertEquals(2, commits.get());
                    assertEquals(1, successes.get());

                    holder[0].bind(new NoteTextBox("manual-ui-fragment-2", "manual-ui-flow-2",
                            0, 1, NoteTextBox.Format.MARKDOWN, "base", "base", 18f, 1.28f,
                            0, 20f, 20f, 650f, 420f), 1f, 0f, 0f, false);
                    holder[0].beginInlineEditing();
                    editor = (EditText) get(holder[0], "sourceEditor");
                    editor.setText("cancel after failure");
                    assertFalse(holder[0].commitInlineEditing());
                    assertTrue(holder[0].isInlineEditing());
                    holder[0].cancelInlineEditing();
                    assertFalse(holder[0].isInlineEditing());
                    assertEquals(1, cancels.get());
                    holder[0].dispose();
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }

    @Test public void shorteningLaterFragmentFinishesBeforeOverlayDisposal() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = installSyntheticMainCanvas(activity);
                    FrameLayout host = (FrameLayout) get(activity, "canvasFrame");
                    host.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
                    host.layout(0, 0, 900, 1200);
                    canvas.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
                    canvas.layout(0, 0, 900, 1200);

                    List<NoteTextBox> boxes = canvas.getTextBoxes();
                    assertTrue("fixture must paginate into several fragments", boxes.size() >= 4);
                    NoteTextBox tail = boxes.get(boxes.size() - 1);
                    canvas.goToPage(tail.pageIndex);
                    Method reconcile = MainActivity.class.getDeclaredMethod(
                            "updateTextBoxOverlays", List.class);
                    reconcile.setAccessible(true);
                    reconcile.invoke(activity, canvas.getTextBoxes());
                    @SuppressWarnings("unchecked")
                    Map<String, NoteTextBoxView> views = (Map<String, NoteTextBoxView>)
                            get(activity, "textBoxViews");
                    NoteTextBoxView tailView = views.get(tail.id);
                    assertNotNull("tail fragment overlay must be materialized", tailView);
                    tailView.beginInlineEditing();
                    EditText editor = (EditText) get(tailView, "sourceEditor");
                    editor.setText("A short replacement.");
                    int undoBefore = canvas.undoDepthForTest();
                    assertTrue("successful commit must complete despite synchronous reflow disposal",
                            tailView.commitInlineEditing());
                    assertFalse(tailView.isInlineEditing());
                    assertEquals("shortening should remove the former tail fragment", 1,
                            canvas.getTextBoxes().size());
                    assertEquals("A short replacement.", canvas.getTextFlows().get(0).source);
                    assertEquals(undoBefore + 1, canvas.undoDepthForTest());
                    assertFalse("obsolete tail overlay should be disposed after commit callback",
                            views.containsKey(tail.id));
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }

    private static JSONObject document() throws Exception {
        return new JSONObject().put("schemaVersion", 8).put("id", "synthetic-main-manual-commit")
                .put("title", "Synthetic manual commit")
                .put("pageWidth", 800d).put("pageHeight", 520d).put("pageGap", 24d)
                .put("pageCount", 1).put("strokes", new JSONArray()).put("images", new JSONArray())
                .put("textBoxes", new JSONArray()).put("textFlows", new JSONArray().put(new JSONObject()
                        .put("id", "synthetic-long-flow").put("format", "markdown")
                        .put("source", longSource()).put("fontSizeSp", 18d)
                        .put("lineHeight", 1.28d).put("width", 740d).put("anchorPageIndex", 0)
                        .put("anchorXInPage", 24d).put("anchorYInPage", 24d)));
    }

    private static String longSource() {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 160; i++) source.append("Paragraph ").append(i)
                .append(" keeps this synthetic flow long enough to create several page fragments. "
                        + "This test uses no saved note data.\n\n");
        return source.toString();
    }

    private static NoteCanvasView installSyntheticMainCanvas(MainActivity activity) throws Exception {
        Method createContent = MainActivity.class.getDeclaredMethod("createContentView");
        createContent.setAccessible(true);
        android.view.View content = (android.view.View) createContent.invoke(activity);
        activity.setContentView(content, new ViewGroup.LayoutParams(900, 1200));
        NoteCanvasView canvas = (NoteCanvasView) get(activity, "canvasView");
        FrameLayout host = (FrameLayout) get(activity, "canvasFrame");
        host.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        host.layout(0, 0, 900, 1200);
        canvas.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        canvas.layout(0, 0, 900, 1200);
        set(activity, "currentNoteId", "synthetic-main-manual-commit");
        set(activity, "restoreCompleted", true);
        set(activity, "editorVisible", false);
        canvas.loadJsonDocument(document());
        return canvas;
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        // Test-private cache only.
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
