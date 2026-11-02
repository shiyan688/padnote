package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Deque;
import java.util.Collections;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;

/** Native proof that a 500-page overflow is rejected before changing a note. */
@RunWith(AndroidJUnit4.class)
public final class LongAiTextCapacityInstrumentedTest {
    @Test
    public void longNestedMarkdownScopesSurviveRealPaginationSaveReloadAndPdfExport()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File qaDir = new File(context.getCacheDir(), "c40-markdown-pagination-r5");
        assertFalse("synthetic QA output is exclusive and cannot overwrite an earlier run",
                qaDir.exists());
        assertTrue("could not create the C40-only synthetic QA directory", qaDir.mkdirs());
        NoteStore.Entry durableEntry = NoteStore.create(context,
                "C40 pagination fixture " + UUID.randomUUID());
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        NoteCanvasView.PdfExportSnapshot snapshot = null;
        try {
            AtomicReference<PdfHarness> harnessRef = new AtomicReference<>();
            AtomicReference<NoteCanvasView.PdfExportSnapshot> snapshotRef = new AtomicReference<>();
            AtomicReference<JSONObject> documentRef = new AtomicReference<>();
            AtomicReference<JSONArray> fragmentFactsRef = new AtomicReference<>();
            AtomicReference<String> flowIdRef = new AtomicReference<>();
            JSONArray measurementFacts = new JSONArray();
            scenario.onActivity(activity -> {
                harnessRef.set(attachedCanvasWithPdfHost(activity));
            });
            PdfHarness harness = harnessRef.get();
            StringBuilder source = new StringBuilder("**outer scope ");
            for (int i = 0; i < 120; i++) {
                source.append("nested *emphasis* with [a link](https://example.invalid/ref) ")
                        .append("and `code $literal$` beside $x_i$ math; ");
            }
            source.append("outer scope ends**");
            String authoredSource = source.toString();
            scenario.onActivity(activity -> {
                NoteCanvasView canvas = harness.canvas;
                canvas.addAiResultTextBox(NoteTextBox.Format.MARKDOWN, authoredSource, null);
                List<NoteTextBox> fragments = canvas.getTextBoxes();
                assertTrue("the production paginator must make multiple paper fragments",
                        fragments.size() > 1);
                TextFlow flow = canvas.getTextFlows().get(0);
                assertEquals(authoredSource, flow.source);
                flowIdRef.set(flow.id);
                String authoredVisible = visibleMarkdownText(
                        MarkdownV2Parser.parseInline(authoredSource));
                int visibleCursor = 0;
                int priorVisibleEnd = -1;
                List<String> html = new java.util.ArrayList<>();
                JSONArray fragmentFacts = new JSONArray();
                int codeSpans = 0;
                int mathSpans = 0;
                for (int index = 0; index < fragments.size(); index++) {
                    NoteTextBox fragment = fragments.get(index);
                    String compiled = CompiledTextWebView.buildHtml(fragment.format,
                            fragment.displaySource(), fragment.fontSizeSp, fragment.lineHeight,
                            fragment.effectiveMarkdownProfileVersion());
                    assertTrue("a spanning strong scope remains active on every page",
                            compiled.contains("<strong class=\"md-strong\">"));
                    assertFalse("pagination must not expose raw strong delimiters",
                            compiled.contains("**"));
                    html.add(compiled);
                    List<MarkdownV2Parser.Node> inline = MarkdownV2Parser.parseInline(
                            fragment.displaySource());
                    String fragmentVisible = visibleMarkdownText(inline);
                    int visibleStart = authoredVisible.indexOf(fragmentVisible, visibleCursor);
                    assertTrue("rendered fragment must map to the next source-visible range: fragment="
                                    + index + " cursor=" + visibleCursor + " found=" + visibleStart
                                    + " fragmentChars=" + fragmentVisible.length()
                                    + " sourceChars=" + authoredVisible.length(),
                            !fragmentVisible.isEmpty() && visibleStart >= visibleCursor);
                    String skipped = authoredVisible.substring(visibleCursor, visibleStart);
                    assertTrue("pagination may skip only trimmed whitespace between fragments",
                            skipped.trim().isEmpty());
                    if (priorVisibleEnd >= 0 && isLatinWordPoint(authoredVisible.codePointBefore(priorVisibleEnd))
                            && isLatinWordPoint(authoredVisible.codePointAt(visibleStart))) {
                        assertTrue("pagination split a fitting Latin word between paper fragments",
                                !skipped.isEmpty());
                    }
                    visibleCursor = visibleStart + fragmentVisible.length();
                    priorVisibleEnd = visibleCursor;
                    codeSpans += countMarkdownNodes(inline, MarkdownV2Parser.Kind.CODE);
                    mathSpans += countMarkdownNodes(inline, MarkdownV2Parser.Kind.MATH);
                    try {
                        fragmentFacts.put(new JSONObject().put("index", index)
                                .put("pageIndex", fragment.pageIndex)
                                .put("visibleStart", visibleStart).put("visibleEnd", visibleCursor)
                                .put("x", fragment.x).put("y", fragment.y)
                                .put("width", fragment.width).put("height", fragment.height)
                                .put("strongHtml", compiled.contains("<strong class=\"md-strong\">"))
                                .put("emphasisHtml", compiled.contains("<em>emphasis</em>"))
                                .put("linkHtml", compiled.contains("class=\"md-link\""))
                                .put("codeNodes", countMarkdownNodes(inline, MarkdownV2Parser.Kind.CODE))
                                .put("mathNodes", countMarkdownNodes(inline, MarkdownV2Parser.Kind.MATH)));
                    } catch (org.json.JSONException error) {
                        throw new AssertionError("fragment evidence must serialize", error);
                    }
                }
                assertTrue("nested emphasis must survive on the paper fragments",
                        html.stream().anyMatch(value -> value.contains("<em>emphasis</em>")));
                assertTrue("link styling must survive page fragmentation",
                        html.stream().anyMatch(value -> value.contains("class=\"md-link\"")));
                assertEquals("opaque code spans must remain whole and occur once", 120, codeSpans);
                assertEquals("opaque inline math spans must remain whole and occur once", 120, mathSpans);
                assertEquals("the rendered fragments must cover the complete visible source",
                        authoredVisible.length(), visibleCursor);

                fragmentFactsRef.set(fragmentFacts);
            });
            measureFlowWithProductionRenderer(scenario, harnessRef.get(), qaDir, flowIdRef.get(),
                    "before-save", authoredSource, measurementFacts);
            writeMeasurementFacts(qaDir, "before-save", measurementFacts, flowIdRef.get(),
                    authoredSource);
            scenario.onActivity(activity -> assertVisibleFlowCoverage(
                    harnessRef.get().canvas, flowIdRef.get(), authoredSource));
            scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = harnessRef.get().canvas;
                    JSONObject saved = canvas.toJsonDocument(durableEntry.id, durableEntry.title);
                    assertEquals("the persisted author source must remain byte-for-byte text",
                            authoredSource, saved.getJSONArray("textFlows").getJSONObject(0)
                                    .getString("source"));
                    documentRef.set(saved);
                } catch (Exception error) {
                    throw new AssertionError("measured long styled flow must serialize", error);
                }
            });
            NoteStore.save(context, durableEntry.id, durableEntry.title,
                    NoteJsonCodec.stringify(documentRef.get()));
            JSONObject durable = NoteStore.load(context, durableEntry.id);
            assertEquals("durable note reload must retain the complete authored Markdown source",
                    authoredSource, durable.getJSONArray("textFlows").getJSONObject(0)
                            .getString("source"));
            scenario.onActivity(activity -> {
                try {
                    harnessRef.get().canvas.loadJsonDocument(durable);
                    assertEquals(authoredSource, harnessRef.get().canvas.getTextFlows().get(0).source);
                } catch (Exception error) {
                    throw new AssertionError("reopened styled flow must reach actual measurement", error);
                }
            });
            measureFlowWithProductionRenderer(scenario, harnessRef.get(), qaDir, flowIdRef.get(),
                    "after-reopen", authoredSource, measurementFacts);
            writeMeasurementFacts(qaDir, "after-reopen", measurementFacts, flowIdRef.get(),
                    authoredSource);
            scenario.onActivity(activity -> assertVisibleFlowCoverage(
                    harnessRef.get().canvas, flowIdRef.get(), authoredSource));
            scenario.onActivity(activity -> {
                try {
                    snapshotRef.set(harnessRef.get().canvas.createPdfExportSnapshot());
                } catch (Exception error) {
                    throw new AssertionError("measured reopened flow must reach PDF export", error);
                }
            });
            snapshot = snapshotRef.get();
            assertNotNull(snapshot);
            assertTrue("the saved source must remain a multi-page paper flow",
                    snapshot.getPageCount() > 1);
            ByteArrayOutputStream pdf = new ByteArrayOutputStream();
            PdfNoteIO.exportFlattenedPdf(snapshot, harness.renderHost, pdf,
                    new Handler(Looper.getMainLooper()), null);
            assertTrue("production PDF renderer must preserve a multipage document", pdf.size() > 100);
            assertEquals("%PDF", new String(pdf.toByteArray(), 0, 4,
                    java.nio.charset.StandardCharsets.US_ASCII));
            File pdfFile = new File(qaDir, "confirmed.pdf");
            try (FileOutputStream output = new FileOutputStream(pdfFile, false)) {
                output.write(pdf.toByteArray());
                output.getFD().sync();
            }
            JSONArray artifacts = new JSONArray().put(artifactRow(pdfFile, "confirmed.pdf"));
            JSONArray paperImages = renderAdjacentPdfPages(pdfFile, qaDir);
            for (int i = 0; i < paperImages.length(); i++) {
                artifacts.put(paperImages.getJSONObject(i));
            }
            JSONObject firstContentInk = firstNonFinalContentInkBounds(pdfFile);
            float firstContentCoverage = (float) firstContentInk.getDouble("coverage");
            File measurementFile = new File(qaDir, "measurements.json");
            JSONArray artifactsWithMeasurements = new JSONArray()
                    .put(artifactRow(measurementFile, "measurements.json"));
            for (int i = 0; i < artifacts.length(); i++) {
                artifactsWithMeasurements.put(artifacts.getJSONObject(i));
            }
            JSONObject manifest = new JSONObject().put("schema", "padnote.c40-pagination-qa.v5")
                    .put("syntheticFixture", true).put("realAgent", false)
                    .put("productionPdfExport", true)
                    .put("productionWebViewMeasurements", true)
                    .put("firstNonFinalContentInk", firstContentInk)
                    .put("sourceUtf8Bytes", authoredSource.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8).length)
                    .put("sourceSha256", sha256(authoredSource.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8)))
                    .put("fragmentFacts", fragmentFactsRef.get())
                    .put("artifacts", artifactsWithMeasurements);
            File manifestFile = new File(qaDir, "manifest.json");
            try (FileOutputStream output = new FileOutputStream(manifestFile, false)) {
                output.write(manifest.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            assertTrue("first non-final content page should use at least 60% of printable height",
                    firstContentCoverage >= 0.60f);
        } finally {
            if (snapshot != null) {
                NoteCanvasView.PdfExportSnapshot close = snapshot;
                InstrumentationRegistry.getInstrumentation().runOnMainSync(close::close);
            }
            scenario.close();
            NoteStore.delete(context, durableEntry.id);
        }
    }

    private static void measureFlowWithProductionRenderer(
            ActivityScenario<MainActivity> scenario, PdfHarness harness, File qaDir, String flowId,
            String phase, String authoredSource, JSONArray measurementFacts) throws Exception {
        assertNotNull("synthetic flow identity must be retained", flowId);
        final float[] pageHeightNative = new float[1];
        scenario.onActivity(activity -> {
            NoteCanvasView.PdfExportSnapshot geometry = null;
            try {
                geometry = harness.canvas.createPdfExportSnapshot();
                pageHeightNative[0] = geometry.getPageHeight();
            } catch (Exception error) {
                throw new AssertionError("synthetic flow must provide production paper geometry", error);
            } finally {
                if (geometry != null) geometry.close();
            }
        });
        assertTrue("production page height must be finite and positive",
                Float.isFinite(pageHeightNative[0]) && pageHeightNative[0] > 0f);

        long deadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(2);
        Set<String> measuredIdsInEpoch = new LinkedHashSet<>();
        for (int pass = 0; pass < 8; pass++) {
            AtomicReference<List<NoteTextBox>> fragmentsRef = new AtomicReference<>();
            AtomicReference<Integer> epochRef = new AtomicReference<>();
            AtomicReference<Boolean> unresolvedRef = new AtomicReference<>(false);
            scenario.onActivity(activity -> {
                TextFlow flow = findFlow(harness.canvas, flowId);
                List<NoteTextBox> fragments = new ArrayList<>();
                for (NoteTextBox box : harness.canvas.getTextBoxes()) {
                    if (flowId.equals(box.flowId)) fragments.add(box);
                }
                assertTrue("synthetic long flow must retain fragments during real measurement",
                        !fragments.isEmpty());
                fragmentsRef.set(fragments);
                epochRef.set(flow.renderLayoutEpoch);
                unresolvedRef.set(flow.lastRenderHeightUnresolved);
            });
            List<NoteTextBox> fragments = fragmentsRef.get();
            int epoch = epochRef.get();
            boolean restartedAfterReflow = false;
            for (int index = 0; index < fragments.size(); index++) {
                NoteTextBox box = fragments.get(index);
                if (measuredIdsInEpoch.contains(box.id)) continue;
                float density = InstrumentationRegistry.getInstrumentation()
                        .getTargetContext().getResources().getDisplayMetrics().density;
                float exactWidth = Math.max(1f, Math.round(box.width));
                float exportViewportHeight = Math.max(1f,
                        Math.round(pageHeightNative[0] * 2f));
                long remainingMs = deadline - SystemClock.elapsedRealtime();
                if (remainingMs <= 0L) {
                    writeMeasurementFacts(qaDir, phase, measurementFacts, flowId, authoredSource);
                    throw new AssertionError("synthetic production measurements exceeded two minutes");
                }
                ProductionHeight measured = measureExportHeight(harness.renderHost, box,
                        (int) exactWidth, (int) exportViewportHeight,
                        Math.min(TimeUnit.SECONDS.toMillis(20), remainingMs));
                AtomicReference<Boolean> currentRef = new AtomicReference<>(false);
                AtomicReference<Boolean> epochChangedRef = new AtomicReference<>(false);
                AtomicReference<Boolean> unresolvedAfterRef = new AtomicReference<>(true);
                scenario.onActivity(activity -> {
                    TextFlow live = findFlow(harness.canvas, flowId);
                    NoteTextBox current = findFragment(harness.canvas, box.id);
                    if (current == null || current.renderLayoutEpoch != box.renderLayoutEpoch
                            || live.renderLayoutEpoch != box.renderLayoutEpoch) {
                        currentRef.set(false);
                        epochChangedRef.set(true);
                        unresolvedAfterRef.set(live.lastRenderHeightUnresolved);
                        return;
                    }
                    currentRef.set(true);
                    harness.canvas.applyMeasuredFragmentHeight(current.id,
                            current.renderLayoutEpoch, measured.visualHeightCssPixels);
                    TextFlow after = findFlow(harness.canvas, flowId);
                    epochChangedRef.set(after.renderLayoutEpoch != epoch);
                    unresolvedAfterRef.set(after.lastRenderHeightUnresolved);
                });
                JSONObject row = new JSONObject();
                try {
                    row.put("phase", phase).put("pass", pass).put("fragmentIndex", index)
                            .put("fragmentIdSha256", sha256(box.id.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8)))
                            .put("pageIndex", box.pageIndex).put("renderLayoutEpoch", epoch)
                            .put("nativeWidth", (int) exactWidth)
                            .put("nativePageHeight", pageHeightNative[0])
                            .put("density", density)
                            .put("exportViewportHeightNative", (int) exportViewportHeight)
                            .put("reservedHeightNative", box.height)
                            .put("measuredHeightCss", measured.measuredCssPixels)
                            .put("measuredHeightNative", measured.visualHeightCssPixels * density)
                            .put("reservedHeightWithinPdfGuard",
                                    measured.visualHeightCssPixels * density <= box.height + density * 2f)
                            .put("canvasAcceptedMeasurement", currentRef.get()
                                    && !unresolvedAfterRef.get())
                            .put("currentFragmentStillMatched", currentRef.get())
                            .put("layoutEpochChanged", epochChangedRef.get())
                            .put("markdownProfileVersion", box.effectiveMarkdownProfileVersion())
                            .put("format", box.format.storageValue())
                            .put("heightMeasure", "CompiledTextWebView.renderForExport");
                    measurementFacts.put(row);
                } catch (JSONException error) {
                    throw new AssertionError("synthetic measurement fact must serialize", error);
                }
                if (measurementFacts.length() > 2000) {
                    writeMeasurementFacts(qaDir, phase, measurementFacts, flowId, authoredSource);
                    throw new AssertionError("synthetic measurement evidence exceeded its bound");
                }
                writeMeasurementFacts(qaDir, phase, measurementFacts, flowId, authoredSource);
                if (epochChangedRef.get()) {
                    measuredIdsInEpoch.clear();
                    restartedAfterReflow = true;
                    break;
                }
                if (!currentRef.get()) {
                    measuredIdsInEpoch.clear();
                    restartedAfterReflow = true;
                    break;
                }
                measuredIdsInEpoch.add(box.id);
            }
            writeMeasurementFacts(qaDir, phase, measurementFacts, flowId, authoredSource);
            if (restartedAfterReflow) continue;
            AtomicReference<Boolean> completeRef = new AtomicReference<>(false);
            scenario.onActivity(activity -> {
                TextFlow flow = findFlow(harness.canvas, flowId);
                Set<String> currentIds = new LinkedHashSet<>();
                for (NoteTextBox box : harness.canvas.getTextBoxes()) {
                    if (flowId.equals(box.flowId)) currentIds.add(box.id);
                }
                completeRef.set(epoch == flow.renderLayoutEpoch
                        && measuredIdsInEpoch.containsAll(currentIds)
                        && !currentIds.isEmpty() && !flow.lastRenderHeightUnresolved);
            });
            if (completeRef.get()) return;
            if (Boolean.TRUE.equals(unresolvedRef.get())) {
                throw new AssertionError("production measurement remains unresolved for synthetic flow");
            }
            measuredIdsInEpoch.clear();
        }
        writeMeasurementFacts(qaDir, phase, measurementFacts, flowId, authoredSource);
        throw new AssertionError("synthetic production measurement did not settle within eight layout passes");
    }

    private static TextFlow findFlow(NoteCanvasView canvas, String flowId) {
        for (TextFlow flow : canvas.getTextFlows()) {
            if (flowId.equals(flow.id)) return flow;
        }
        throw new AssertionError("synthetic target flow disappeared during measurement");
    }

    private static NoteTextBox findFragment(NoteCanvasView canvas, String fragmentId) {
        for (NoteTextBox box : canvas.getTextBoxes()) {
            if (fragmentId.equals(box.id)) return box;
        }
        return null;
    }

    private static ProductionHeight measureExportHeight(FrameLayout host, NoteTextBox box,
                                                        int nativeWidth,
                                                        int nativeHeight,
                                                        long timeoutMs) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<CompiledTextWebView> viewRef = new AtomicReference<>();
        AtomicReference<Float> visualHeightRef = new AtomicReference<>();
        AtomicReference<Float> measuredHeightRef = new AtomicReference<>();
        AtomicReference<String> failureRef = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CompiledTextWebView view = null;
            try {
                view = new CompiledTextWebView(host.getContext());
                viewRef.set(view);
                view.setVisibility(View.VISIBLE);
                view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                view.getSettings().setOffscreenPreRaster(true);
                host.addView(view, new FrameLayout.LayoutParams(nativeWidth, nativeHeight));
                view.measure(View.MeasureSpec.makeMeasureSpec(nativeWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(nativeHeight, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, nativeWidth, nativeHeight);
                view.renderForExport(box.format, box.displaySource(), box.fontSizeSp,
                        box.lineHeight, box.effectiveMarkdownProfileVersion(),
                        new CompiledTextWebView.ExportReadyListener() {
                            @Override public void onReady(float measuredCss, float visualCss) {
                                measuredHeightRef.set(measuredCss);
                                visualHeightRef.set(visualCss);
                                latch.countDown();
                            }
                            @Override public void onFailure(String message) {
                                failureRef.set(message == null ? "render-failed" : message);
                                latch.countDown();
                            }
                        });
            } catch (RuntimeException error) {
                failureRef.set(error.getClass().getSimpleName());
                latch.countDown();
            }
        });
        try {
            if (timeoutMs <= 0L || !latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("synthetic production WebView height measurement timed out");
            }
            if (failureRef.get() != null) {
                throw new AssertionError("synthetic production WebView measurement failed: "
                        + failureRef.get());
            }
            Float visual = visualHeightRef.get();
            Float measured = measuredHeightRef.get();
            if (visual == null || measured == null || !Float.isFinite(visual)
                    || !Float.isFinite(measured) || visual <= 0f || measured <= 0f) {
                throw new AssertionError("synthetic production WebView returned invalid dimensions");
            }
            return new ProductionHeight(measured, visual);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                CompiledTextWebView view = viewRef.get();
                if (view == null) return;
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                view.destroy();
                viewRef.set(null);
            });
        }
    }

    private static void assertVisibleFlowCoverage(NoteCanvasView canvas, String flowId,
                                                  String authoredSource) {
        String visibleSource = visibleMarkdownText(MarkdownV2Parser.parseInline(authoredSource));
        int cursor = 0;
        int priorEnd = -1;
        int fragmentCount = 0;
        for (NoteTextBox box : canvas.getTextBoxes()) {
            if (!flowId.equals(box.flowId)) continue;
            String fragmentVisible = visibleMarkdownText(
                    MarkdownV2Parser.parseInline(box.displaySource()));
            int start = visibleSource.indexOf(fragmentVisible, cursor);
            assertTrue("actual-measured fragments map to the next authored visible range",
                    !fragmentVisible.isEmpty() && start >= cursor);
            String skipped = visibleSource.substring(cursor, start);
            assertTrue("actual-measured pagination skips only trimmed whitespace",
                    skipped.trim().isEmpty());
            if (priorEnd > 0 && isLatinWordPoint(visibleSource.codePointBefore(priorEnd))
                    && isLatinWordPoint(visibleSource.codePointAt(start))) {
                assertTrue("actual-measured pagination does not split a fitting Latin word",
                        !skipped.isEmpty());
            }
            cursor = start + fragmentVisible.length();
            priorEnd = cursor;
            fragmentCount++;
        }
        assertTrue("actual-measured note remains multipage", fragmentCount > 1);
        assertEquals("actual-measured fragments preserve all authored visible source",
                visibleSource.length(), cursor);
    }

    private static void writeMeasurementFacts(File qaDir, String phase,
                                             JSONArray facts, String flowId,
                                             String authoredSource) {
        try {
            JSONObject root = new JSONObject().put("schema", "padnote.c40-pagination-measurements.v5")
                    .put("syntheticFixture", true).put("realAgent", false)
                    .put("phase", phase)
                    .put("sourceUtf8Bytes", authoredSource.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8).length)
                    .put("sourceSha256", sha256(authoredSource.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8)))
                    .put("flowIdSha256", sha256(flowId.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8)))
                    .put("measurements", facts);
            File output = new File(qaDir, "measurements.json");
            try (FileOutputStream stream = new FileOutputStream(output, false)) {
                stream.write(root.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                stream.getFD().sync();
            }
        } catch (Exception error) {
            throw new AssertionError("synthetic measurement facts must be preserved", error);
        }
    }

    private static final class ProductionHeight {
        final float measuredCssPixels;
        final float visualHeightCssPixels;
        ProductionHeight(float measuredCssPixels, float visualHeightCssPixels) {
            this.measuredCssPixels = measuredCssPixels;
            this.visualHeightCssPixels = visualHeightCssPixels;
        }
    }

    private static JSONArray renderAdjacentPdfPages(File pdfFile, File qaDir) throws Exception {
        JSONArray rows = new JSONArray();
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(pdfFile,
                ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            int pages = Math.min(2, renderer.getPageCount());
            for (int index = 0; index < pages; index++) {
                try (PdfRenderer.Page page = renderer.openPage(index)) {
                    int width = Math.max(1, page.getWidth() * 2);
                    int height = Math.max(1, page.getHeight() * 2);
                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    try {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        String name = String.format(java.util.Locale.ROOT,
                                "paper-page-%02d.png", index + 1);
                        File image = new File(qaDir, name);
                        try (FileOutputStream output = new FileOutputStream(image, false)) {
                            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                throw new java.io.IOException("PDF page PNG encoding failed");
                            }
                            output.getFD().sync();
                        }
                        rows.put(artifactRow(image, name));
                    } finally {
                        bitmap.recycle();
                    }
                }
            }
        }
        return rows;
    }

    private static JSONObject firstNonFinalContentInkBounds(File pdfFile) throws Exception {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(pdfFile,
                ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            for (int index = 0; index < renderer.getPageCount() - 1; index++) {
                try (PdfRenderer.Page page = renderer.openPage(index)) {
                    int width = Math.max(1, page.getWidth());
                    int height = Math.max(1, page.getHeight());
                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    try {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        int minY = height, maxY = -1, darkPixels = 0;
                        int[] row = new int[width];
                        for (int y = 0; y < height; y++) {
                            bitmap.getPixels(row, 0, width, 0, y, width, 1);
                            for (int color : row) {
                                int red = android.graphics.Color.red(color);
                                int green = android.graphics.Color.green(color);
                                int blue = android.graphics.Color.blue(color);
                                if (red < 112 && green < 112 && blue < 112) {
                                    minY = Math.min(minY, y);
                                    maxY = Math.max(maxY, y);
                                    darkPixels++;
                                }
                            }
                        }
                        if (darkPixels >= 40 && maxY >= minY) {
                            float coverage = (maxY - minY + 1f) / height;
                            return new JSONObject().put("pageIndex", index)
                                    .put("pageHeightPixels", height).put("inkTopPixels", minY)
                                    .put("inkBottomPixels", maxY).put("darkPixelCount", darkPixels)
                                    .put("coverage", coverage);
                        }
                    } finally {
                        bitmap.recycle();
                    }
                }
            }
        }
        throw new AssertionError("production PDF has no non-final page with readable text ink");
    }

    private static String visibleMarkdownText(List<MarkdownV2Parser.Node> nodes) {
        StringBuilder visible = new StringBuilder();
        appendVisibleMarkdownText(nodes, visible);
        return visible.toString();
    }

    private static void appendVisibleMarkdownText(List<MarkdownV2Parser.Node> nodes,
                                                  StringBuilder visible) {
        for (MarkdownV2Parser.Node node : nodes) {
            switch (node.kind) {
                case TEXT:
                    for (int index = 0; index < node.value.length();) {
                        int codePoint = node.value.codePointAt(index);
                        if (Character.isWhitespace(codePoint)) {
                            if (visible.length() > 0 && visible.charAt(visible.length() - 1) != ' ') {
                                visible.append(' ');
                            }
                        } else {
                            visible.appendCodePoint(codePoint);
                        }
                        index += Character.charCount(codePoint);
                    }
                    break;
                case CODE:
                case MATH:
                    visible.append(node.value);
                    break;
                case STRONG:
                case EMPHASIS:
                case STRIKE:
                case LINK:
                    appendVisibleMarkdownText(node.children, visible);
                    break;
            }
        }
    }

    private static boolean isLatinWordPoint(int codePoint) {
        if (codePoint < 0) return false;
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return (script == Character.UnicodeScript.LATIN || Character.isDigit(codePoint))
                && (Character.isLetterOrDigit(codePoint) || Character.getType(codePoint)
                == Character.NON_SPACING_MARK || Character.getType(codePoint)
                == Character.COMBINING_SPACING_MARK);
    }

    private static JSONObject artifactRow(File file, String relativePath) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            for (int count; (count = input.read(buffer)) >= 0;) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        return new JSONObject().put("path", relativePath).put("bytes", file.length())
                .put("sha256", hex(digest.digest()));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String hex(byte[] digest) {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT,
                "%02x", value & 0xff));
        return hex.toString();
    }

    private static int countMarkdownNodes(List<MarkdownV2Parser.Node> nodes,
                                          MarkdownV2Parser.Kind kind) {
        int count = 0;
        for (MarkdownV2Parser.Node node : nodes) {
            if (node.kind == kind) count++;
            count += countMarkdownNodes(node.children, kind);
        }
        return count;
    }

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
