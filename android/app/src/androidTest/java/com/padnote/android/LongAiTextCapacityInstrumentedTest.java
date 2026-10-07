package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.Deque;
import java.util.Collections;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Native proof that a 500-page overflow is rejected before changing a note. */
@RunWith(AndroidJUnit4.class)
public final class LongAiTextCapacityInstrumentedTest {
    @Test
    public void saturatedAndDifferentFragmentMeasurementsKeepPdfBlocked() throws Exception {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            AtomicReference<NoteCanvasView> canvasRef = new AtomicReference<>();
            AtomicReference<Throwable> pdfFailure = new AtomicReference<>();
            AtomicReference<Float> densityRef = new AtomicReference<>();
            scenario.onActivity(activity -> canvasRef.set(attachedCanvas(activity)));
            NoteCanvasView canvas = canvasRef.get();
            scenario.onActivity(activity -> {
                densityRef.set(activity.getResources().getDisplayMetrics().density);
                canvas.addAiResultTextBox(NoteTextBox.Format.MARKDOWN,
                        repeatedLines(400), null);
                List<NoteTextBox> fragments = canvas.getTextBoxes();
                assertTrue("fixture must span multiple fragments", fragments.size() > 1);
                TextFlow flow = canvas.getTextFlows().get(0);
                flow.heightCorrection = maxHeightCorrection();
                assertTrue("fixture reaches the correction ceiling before pass limit",
                        flow.heightCorrectionPasses < 3);
                NoteTextBox first = fragments.get(0);
                int initialPasses = flow.heightCorrectionPasses;
                canvas.applyMeasuredFragmentHeight(first.id, first.renderLayoutEpoch,
                        first.height * 2f / densityRef.get());
                assertTrue("saturated correction must mark clipping unresolved",
                        flow.lastRenderHeightUnresolved);
                assertEquals("saturated correction must not advance correction passes",
                        initialPasses, flow.heightCorrectionPasses);
                NoteTextBox other = fragments.get(1);
                int epochBeforeFit = flow.renderLayoutEpoch;
                canvas.applyMeasuredFragmentHeight(other.id, other.renderLayoutEpoch,
                        other.height / densityRef.get());
                assertEquals("fitting measurement must not trigger a reflow",
                        epochBeforeFit, flow.renderLayoutEpoch);
                assertEquals(initialPasses, flow.heightCorrectionPasses);
                assertTrue("a fitting sibling fragment must not clear the failing fragment",
                        flow.lastRenderHeightUnresolved);
                try {
                    canvas.createPdfExportSnapshot();
                    fail("PDF must refuse a flow with any unresolved fragment");
                } catch (java.io.IOException expected) {
                    pdfFailure.set(expected);
                }
            });
            assertNotNull(pdfFailure.get());
        } finally {
            scenario.close();
        }
    }

    @Test
    public void allMeasuredStableMultipageFragmentsPermitPdfSnapshot() throws Exception {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            AtomicReference<NoteCanvasView> canvasRef = new AtomicReference<>();
            AtomicReference<NoteCanvasView.PdfExportSnapshot> snapshotRef = new AtomicReference<>();
            AtomicReference<Float> densityRef = new AtomicReference<>();
            scenario.onActivity(activity -> canvasRef.set(attachedCanvas(activity)));
            NoteCanvasView canvas = canvasRef.get();
            scenario.onActivity(activity -> {
                try {
                    densityRef.set(activity.getResources().getDisplayMetrics().density);
                    canvas.addAiResultTextBox(NoteTextBox.Format.MARKDOWN,
                            repeatedLines(300), null);
                    List<NoteTextBox> fragments = canvas.getTextBoxes();
                    assertTrue("fixture must span pages", fragments.size() > 1);
                    for (NoteTextBox fragment : fragments) {
                        canvas.applyMeasuredFragmentHeight(fragment.id, fragment.renderLayoutEpoch,
                                fragment.height * 1.01f / densityRef.get());
                    }
                    snapshotRef.set(canvas.createPdfExportSnapshot());
                } catch (java.io.IOException error) {
                    throw new AssertionError("stable measured multipage text should export", error);
                }
            });
            assertNotNull(snapshotRef.get());
            scenario.onActivity(activity -> snapshotRef.get().close());
        } finally {
            scenario.close();
        }
    }

    @Test
    public void callbackFromPriorLayoutEpochCannotMutateCurrentCorrectionState() throws Exception {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            AtomicReference<NoteCanvasView> canvasRef = new AtomicReference<>();
            AtomicReference<Float> densityRef = new AtomicReference<>();
            scenario.onActivity(activity -> {
                canvasRef.set(attachedCanvas(activity));
                densityRef.set(activity.getResources().getDisplayMetrics().density);
            });
            NoteCanvasView canvas = canvasRef.get();
            scenario.onActivity(activity -> {
                canvas.addAiResultTextBox(NoteTextBox.Format.MARKDOWN,
                        repeatedLines(400), null);
                NoteTextBox old = canvas.getTextBoxes().get(0);
                int oldEpoch = old.renderLayoutEpoch;
                TextFlow flow = canvas.getTextFlows().get(0);
                canvas.applyMeasuredFragmentHeight(old.id, oldEpoch,
                        old.height * 1.5f / densityRef.get());
                assertTrue("height correction must commit a new layout epoch",
                        flow.renderLayoutEpoch > oldEpoch);
                float correctionAfterCurrentCallback = flow.heightCorrection;
                int passesAfterCurrentCallback = flow.heightCorrectionPasses;
                canvas.applyMeasuredFragmentHeight(old.id, oldEpoch,
                        old.height * 10f / densityRef.get());
                assertEquals(correctionAfterCurrentCallback, flow.heightCorrection, 0f);
                assertEquals(passesAfterCurrentCallback, flow.heightCorrectionPasses);
            });
        } finally {
            scenario.close();
        }
    }

    @Test
    public void editAfterMeasuredFailureClearsOldLatchAndCompletesRealPdfExport() throws Exception {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        NoteCanvasView.PdfExportSnapshot snapshot = null;
        try {
            AtomicReference<PdfHarness> harnessRef = new AtomicReference<>();
            AtomicReference<NoteCanvasView.PdfExportSnapshot> snapshotRef = new AtomicReference<>();
            AtomicReference<Float> densityRef = new AtomicReference<>();
            scenario.onActivity(activity -> {
                harnessRef.set(attachedCanvasWithPdfHost(activity));
                densityRef.set(activity.getResources().getDisplayMetrics().density);
            });
            PdfHarness harness = harnessRef.get();
            scenario.onActivity(activity -> {
                NoteCanvasView canvas = harness.canvas;
                canvas.addAiResultTextBox(NoteTextBox.Format.MARKDOWN,
                        repeatedLines(90), null);
                TextFlow flow = canvas.getTextFlows().get(0);
                flow.heightCorrection = maxHeightCorrection();
                NoteTextBox fragment = canvas.getTextBoxes().get(0);
                canvas.applyMeasuredFragmentHeight(fragment.id, fragment.renderLayoutEpoch,
                        fragment.height * 2f / densityRef.get());
                assertTrue("fixture must first establish the old failure latch",
                        flow.lastRenderHeightUnresolved);
                canvas.setTextFlowFontSize(fragment.id, 10f);
                assertTrue("real font change must clear geometry learned from old content",
                        !flow.lastRenderHeightUnresolved);
                try { snapshotRef.set(canvas.createPdfExportSnapshot()); }
                catch (java.io.IOException error) { throw new AssertionError(error); }
            });
            snapshot = snapshotRef.get();
            assertNotNull("edited flow must reach the actual PDF export stage", snapshot);
            assertTrue("fixture must have an unvisited later page", snapshot.getPageCount() > 1);
            ByteArrayOutputStream pdf = new ByteArrayOutputStream();
            PdfNoteIO.exportFlattenedPdf(snapshot, harness.renderHost, pdf,
                    new Handler(Looper.getMainLooper()), null);
            assertTrue("real offscreen renderer did not produce a PDF", pdf.size() > 100);
            assertTrue("output is not a PDF document", new String(pdf.toByteArray(), 0, 4,
                    java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF"));
        } finally {
            if (snapshot != null) {
                NoteCanvasView.PdfExportSnapshot close = snapshot;
                InstrumentationRegistry.getInstrumentation().runOnMainSync(close::close);
            }
            scenario.close();
        }
    }

    @Test
    public void sameStyleAndMoveRetainFailureButContentEditClearsIt() throws Exception {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            AtomicReference<NoteCanvasView> canvasRef = new AtomicReference<>();
            AtomicReference<Float> densityRef = new AtomicReference<>();
            scenario.onActivity(activity -> {
                canvasRef.set(attachedCanvas(activity));
                densityRef.set(activity.getResources().getDisplayMetrics().density);
            });
            NoteCanvasView canvas = canvasRef.get();
            scenario.onActivity(activity -> {
                canvas.addAiResultTextBox(NoteTextBox.Format.MARKDOWN,
                        repeatedLines(90), null);
                TextFlow flow = canvas.getTextFlows().get(0);
                NoteTextBox first = canvas.getTextBoxes().get(0);
                flow.heightCorrection = maxHeightCorrection();
                canvas.applyMeasuredFragmentHeight(first.id, first.renderLayoutEpoch,
                        first.height * 2f / densityRef.get());
                assertTrue(flow.lastRenderHeightUnresolved);
                canvas.updateTextBox(first.id, flow.format, flow.source,
                        flow.fontSizeSp, flow.lineHeight);
                canvas.setTextFlowFontSize(first.id, flow.fontSizeSp);
                assertTrue("same-content/style no-op must keep prior failure", flow.lastRenderHeightUnresolved);
                canvas.moveTextFlowByPages(flow.id, 1);
                assertTrue("a page move must not erase measured clipping evidence",
                        flow.lastRenderHeightUnresolved);
                NoteTextBox current = canvas.getTextBoxes().get(0);
                canvas.updateTextBox(current.id, NoteTextBox.Format.MARKDOWN,
                        "短文本编辑已更改实际渲染内容", current.fontSizeSp, current.lineHeight);
                assertTrue("changed body must clear stale measurement from the previous body",
                        !flow.lastRenderHeightUnresolved);
                current = canvas.getTextBoxes().get(0);
                flow.heightCorrection = maxHeightCorrection();
                canvas.applyMeasuredFragmentHeight(current.id, current.renderLayoutEpoch,
                        current.height * 2f / densityRef.get());
                assertTrue(flow.lastRenderHeightUnresolved);
                canvas.resizeTextBox(current.id, current.width - dp(activity, 48),
                        current.height);
                assertTrue("real width change must clear prior measured failure",
                        !flow.lastRenderHeightUnresolved);
                current = canvas.getTextBoxes().get(0);
                flow.heightCorrection = maxHeightCorrection();
                canvas.applyMeasuredFragmentHeight(current.id, current.renderLayoutEpoch,
                        current.height * 2f / densityRef.get());
                assertTrue(flow.lastRenderHeightUnresolved);
                canvas.updateTextBox(current.id, NoteTextBox.Format.LATEX,
                        current.source, current.fontSizeSp, current.lineHeight);
                assertTrue("real format change must clear prior measured failure",
                        !flow.lastRenderHeightUnresolved);
            });
        } finally {
            scenario.close();
        }
    }

    /** Deliberately narrow snapshot reservation: prove the real exporter still rejects overflow. */
    @Test
    public void afterEditActualOffscreenOverflowStillFailsExportGuard() throws Exception {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        NoteCanvasView.PdfExportSnapshot snapshot = null;
        try {
            AtomicReference<PdfHarness> harnessRef = new AtomicReference<>();
            AtomicReference<NoteCanvasView.PdfExportSnapshot> snapshotRef = new AtomicReference<>();
            AtomicReference<Float> densityRef = new AtomicReference<>();
            scenario.onActivity(activity -> {
                harnessRef.set(attachedCanvasWithPdfHost(activity));
                densityRef.set(activity.getResources().getDisplayMetrics().density);
            });
            PdfHarness harness = harnessRef.get();
            scenario.onActivity(activity -> {
                NoteCanvasView canvas = harness.canvas;
                canvas.addAiResultTextBox(NoteTextBox.Format.MARKDOWN,
                        "initial body", null);
                NoteTextBox first = canvas.getTextBoxes().get(0);
                TextFlow flow = canvas.getTextFlows().get(0);
                flow.heightCorrection = maxHeightCorrection();
                canvas.applyMeasuredFragmentHeight(first.id, first.renderLayoutEpoch,
                        first.height * 2f / densityRef.get());
                assertTrue(flow.lastRenderHeightUnresolved);
                canvas.updateTextBox(first.id, NoteTextBox.Format.MARKDOWN,
                        repeatedLines(20), first.fontSizeSp, first.lineHeight);
                assertTrue("body edit should clear the prior latch before real rendering",
                        !flow.lastRenderHeightUnresolved);
                try { snapshotRef.set(canvas.createPdfExportSnapshot()); }
                catch (java.io.IOException error) { throw new AssertionError(error); }
            });
            snapshot = snapshotRef.get();
            assertNotNull(snapshot);
            narrowSnapshotTextReservation(snapshot, 2f);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                PdfNoteIO.exportFlattenedPdf(snapshot, harness.renderHost, output,
                        new Handler(Looper.getMainLooper()), null);
                fail("the actual offscreen renderer must reject the narrow reservation");
            } catch (java.io.IOException expected) {
                assertTrue("failure should be the production rendered-height guard: " + expected,
                        containsMessage(expected, "正文实高超过分页预留"));
                assertEquals("failed PDF export must not leave a partial document", 0, output.size());
            }
        } finally {
            if (snapshot != null) {
                NoteCanvasView.PdfExportSnapshot close = snapshot;
                InstrumentationRegistry.getInstrumentation().runOnMainSync(close::close);
            }
            scenario.close();
        }
    }

    @Test
    public void legacyOverPageFlowSurvivesSaveReopenAndStillBlocksPdf() throws Exception {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            AtomicReference<Throwable> pdfFailure = new AtomicReference<>();
            String noteId = "capacity-legacy-" + UUID.randomUUID();
            scenario.onActivity(activity -> {
                try {
                    NoteCanvasView first = attachedCanvas(activity);
                    JSONObject saved = first.toJsonDocument("old-flow", "old flow");
                    JSONArray flows = new JSONArray();
                    flows.put(new TextFlow("legacy-long-flow", NoteTextBox.Format.MARKDOWN,
                            repeatedLines(50_000), 16d, TextFlow.DEFAULT_LINE_HEIGHT,
                            400d, 0, 16d, 16d).toJson());
                    saved.put("textFlows", flows);
                    first.loadJsonDocument(saved);
                    assertEquals(repeatedLines(50_000), first.getTextFlows().get(0).source);
                    NoteStore.save(activity, noteId, "old flow",
                            first.toJsonDocument(noteId, "old flow").toString());

                    NoteCanvasView reopened = attachedCanvas(activity);
                    reopened.loadJsonDocument(NoteStore.load(activity, noteId));
                    assertEquals(repeatedLines(50_000), reopened.getTextFlows().get(0).source);
                    assertTrue("legacy flow must remain explicitly incomplete after reload",
                            !reopened.getTextFlows().get(0).lastLayoutComplete);
                    try {
                        reopened.createPdfExportSnapshot();
                        fail("incomplete legacy text must not be exported as a clipped PDF");
                    } catch (java.io.IOException expected) {
                        pdfFailure.set(expected);
                    }
                } catch (JSONException error) {
                    throw new AssertionError(error);
                } catch (Exception error) {
                    throw new AssertionError("legacy long note file save/reopen failed", error);
                }
            });
            assertNotNull(pdfFailure.get());
            scenario.onActivity(activity -> {
                try { NoteStore.delete(activity, noteId); }
                catch (Exception error) { throw new AssertionError(error); }
            });
        } finally {
            scenario.close();
        }
    }

    private static NoteCanvasView attachedCanvas(Activity activity) {
        int width = dp(activity, 768), height = dp(activity, 1024);
        FrameLayout host = new FrameLayout(activity);
        NoteCanvasView canvas = new NoteCanvasView(activity);
        host.addView(canvas, new FrameLayout.LayoutParams(width, height));
        activity.setContentView(host, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        canvas.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        canvas.layout(0, 0, width, height);
        return canvas;
    }

    private static PdfHarness attachedCanvasWithPdfHost(Activity activity) {
        int width = dp(activity, 768), height = dp(activity, 1024);
        FrameLayout root = new FrameLayout(activity);
        NoteCanvasView canvas = new NoteCanvasView(activity);
        FrameLayout renderHost = new FrameLayout(activity);
        renderHost.setVisibility(View.VISIBLE);
        renderHost.setAlpha(0.01f);
        renderHost.setClipChildren(false);
        renderHost.setClipToPadding(false);
        root.addView(canvas, new FrameLayout.LayoutParams(width, height));
        root.addView(renderHost, new FrameLayout.LayoutParams(1, 1));
        activity.setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        return new PdfHarness(canvas, renderHost);
    }

    private static void narrowSnapshotTextReservation(
            NoteCanvasView.PdfExportSnapshot snapshot, float height) {
        try {
            Field field = snapshot.getClass().getDeclaredField("textBoxes");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<NoteTextBox> boxes = (List<NoteTextBox>) field.get(snapshot);
            assertTrue("fixture needs ordinary body text", !boxes.isEmpty());
            boxes.get(0).height = height;
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("could not install the explicit narrow-reservation fixture", error);
        }
    }

    private static boolean containsMessage(Throwable error, String fragment) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(fragment)) return true;
        }
        return false;
    }

    private static final class PdfHarness {
        final NoteCanvasView canvas;
        final FrameLayout renderHost;
        PdfHarness(NoteCanvasView canvas, FrameLayout renderHost) {
            this.canvas = canvas;
            this.renderHost = renderHost;
        }
    }

    @Test
    public void rejectedPageOverflowAnswerRemainsColdReopenableInAiTimeline() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getCacheDir(), "ai-text-capacity-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
        String noteId = "capacity-note-" + UUID.randomUUID();
        String completeAnswer = repeatedLines(50_000);
        AiConversationStore first = new AiConversationStore(root, NoteStore.NO_WRITE_FAULTS);
        try {
            first.create(noteId, Collections.singletonList(
                            new AiConversationStore.VisibleEntry("message", "assistant",
                                    completeAnswer, "model", false)),
                    Collections.emptyList(), null, null,
                    new AiConversationStore.Binding("", "", "", 0, "", ""),
                    null, false);
            AiConversationStore.Snapshot cold = new AiConversationStore(
                    root, NoteStore.NO_WRITE_FAULTS).load(noteId);
            assertNotNull(cold);
            assertEquals("the complete answer must survive save/reopen without splitting or loss",
                    completeAnswer, cold.visibleTimeline.get(0).text);
        } finally {
            first.clear(noteId);
        }
    }

    @Test
    public void fiveHundredPageOverflowLeavesDocumentAndUndoStateUntouched() throws Exception {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            AtomicReference<NoteCanvasView> canvasRef = new AtomicReference<>();
            scenario.onActivity(activity -> {
                int width = dp(activity, 768), height = dp(activity, 1024);
                FrameLayout host = new FrameLayout(activity);
                NoteCanvasView canvas = new NoteCanvasView(activity);
                host.addView(canvas, new FrameLayout.LayoutParams(width, height));
                activity.setContentView(host, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                canvas.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                canvas.layout(0, 0, width, height);
                canvasRef.set(canvas);
            });

            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<Integer> beforeUndo = new AtomicReference<>();
            AtomicReference<Integer> beforeFlows = new AtomicReference<>();
            AtomicReference<Integer> beforeBoxes = new AtomicReference<>();
            AtomicReference<Integer> beforePages = new AtomicReference<>();
            NoteCanvasView canvas = canvasRef.get();
            scenario.onActivity(activity -> {
                beforeUndo.set(undoDepth(canvas));
                beforeFlows.set(canvas.getTextFlows().size());
                beforeBoxes.set(canvas.getTextBoxes().size());
                beforePages.set(canvas.getPageCount());
                String source = repeatedLines(50_000); // exactly 100,000 UTF-16 units
                try {
                    canvas.addAiResultTextBox(NoteTextBox.Format.MARKDOWN, source, null);
                    fail("a source requiring more than 500 pages must be rejected");
                } catch (TextFlowCapacity.Rejection expected) {
                    failure.set(expected);
                }
            });

            assertNotNull("capacity refusal must be explicit", failure.get());
            assertEquals(TextFlowCapacity.Failure.PAGE_LIMIT,
                    ((TextFlowCapacity.Rejection) failure.get()).failure);
            scenario.onActivity(activity -> {
                assertEquals((int) beforeUndo.get(), undoDepth(canvas));
                assertEquals((int) beforeFlows.get(), canvas.getTextFlows().size());
                assertEquals((int) beforeBoxes.get(), canvas.getTextBoxes().size());
                assertEquals((int) beforePages.get(), canvas.getPageCount());
            });
        } finally {
            scenario.close();
        }
    }

    @Test
    public void sourceLimitIsRejectedBeforeLayoutOrUndoMutation() throws Exception {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            AtomicReference<NoteCanvasView> canvasRef = new AtomicReference<>();
            scenario.onActivity(activity -> {
                int width = dp(activity, 768), height = dp(activity, 1024);
                FrameLayout host = new FrameLayout(activity);
                NoteCanvasView canvas = new NoteCanvasView(activity);
                host.addView(canvas, new FrameLayout.LayoutParams(width, height));
                activity.setContentView(host, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                canvas.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                canvas.layout(0, 0, width, height);
                canvasRef.set(canvas);
            });
            NoteCanvasView canvas = canvasRef.get();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<Integer> beforeUndo = new AtomicReference<>();
            scenario.onActivity(activity -> {
                beforeUndo.set(undoDepth(canvas));
                try {
                    canvas.addAiResultTextBox(NoteTextBox.Format.MARKDOWN,
                            repeatedLines(50_001), null);
                    fail("source over 100,000 UTF-16 units must be rejected");
                } catch (TextFlowCapacity.Rejection expected) {
                    failure.set(expected);
                }
                assertEquals((int) beforeUndo.get(), undoDepth(canvas));
            });
            assertTrue(failure.get() instanceof TextFlowCapacity.Rejection);
            assertEquals(TextFlowCapacity.Failure.SOURCE_TOO_LONG,
                    ((TextFlowCapacity.Rejection) failure.get()).failure);
        } finally {
            scenario.close();
        }
    }

    private static String repeatedLines(int count) {
        StringBuilder source = new StringBuilder(count * 2);
        for (int index = 0; index < count; index++) source.append("x\n");
        return source.toString();
    }

    private static int undoDepth(NoteCanvasView canvas) {
        try {
            Field field = NoteCanvasView.class.getDeclaredField("undoStack");
            field.setAccessible(true);
            return ((Deque<?>) field.get(canvas)).size();
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("could not inspect undo state", error);
        }
    }

    private static float maxHeightCorrection() {
        try {
            Field field = NoteCanvasView.class.getDeclaredField("MAX_HEIGHT_CORRECTION");
            field.setAccessible(true);
            return field.getFloat(null);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("could not inspect measured-height correction bound", error);
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
