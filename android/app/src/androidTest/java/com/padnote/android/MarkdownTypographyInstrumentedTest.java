package com.padnote.android;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.view.View;
import android.view.ViewGroup;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Full-paper WebView measurement and per-flow schema migration regression. */
@RunWith(AndroidJUnit4.class)
public final class MarkdownTypographyInstrumentedTest {
    @Test public void exportsSyntheticV1V2PaperRastersAndProductionPdfs() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File qaDir = new File(context.getCacheDir(), "c38-typography-qa-r5");
        if (!qaDir.mkdir()) throw new AssertionError("C38 QA artifact directory already exists or cannot be created");
        JSONArray inventoryRows = new JSONArray();
        JSONObject inventory = new JSONObject().put("schema", "padnote-c38-typography-artifacts.v1")
                .put("synthetic_only", true).put("test", "exportsSyntheticV1V2PaperRastersAndProductionPdfs")
                .put("complete", false);
        boolean complete = false;
        try {
            String source = syntheticTypographySource();
            JSONObject v1 = renderTypographyArtifacts(qaDir, 9, 1, source, "v1", inventoryRows);
            JSONObject v2 = renderTypographyArtifacts(qaDir, 10, 2, source, "v2", inventoryRows);
            assertEquals("same synthetic source must be compared", v1.getString("source_sha256"),
                    v2.getString("source_sha256"));
            assertTrue("paper layout should span multiple pages", v1.getInt("page_count") >= 2);
            assertTrue("both profiles must export the complete multi-page flow", v2.getInt("page_count") >= 2);
            assertTrue("v1 and v2 actual raster output must differ", v2.getInt("changed_pixels") > 0);
            assertEquals(1, v1.getInt("effective_profile"));
            assertEquals(2, v2.getInt("effective_profile"));
            complete = true;
        } finally {
            inventoryRows = scanQaArtifacts(qaDir);
            inventory.put("files", inventoryRows).put("complete", complete);
            writeExclusive(new File(qaDir, "inventory.json"),
                    (inventory.toString(2) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static JSONObject renderTypographyArtifacts(File qaDir,
            int schemaVersion, int expectedProfile, String source, String label,
            JSONArray inventoryRows) throws Exception {
        final int[] pageCount = {0};
        final String[] flowId = {null};
        final String sourceSha = sha256(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (Session session = Session.open()) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    JSONObject initial = emptyLegacyDocument().put("schemaVersion", schemaVersion);
                    session.canvas.loadJsonDocument(initial);
                    NoteTextBox box = session.canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                            source, 36f, 42f);
                    if (box == null) throw new AssertionError("synthetic typography flow was not created");
                    TextFlow flow = session.canvas.getTextFlows().stream()
                            .filter(value -> box.flowId.equals(value.id)).findFirst()
                            .orElseThrow(AssertionError::new);
                    if (flow.effectiveMarkdownProfileVersion() != expectedProfile)
                        throw new AssertionError("unexpected profile for schema " + schemaVersion);
                    flowId[0] = flow.id;
                } catch (Exception error) { throw new AssertionError(error); }
            });
            if (flowId[0] == null) throw new AssertionError("synthetic flow id missing after setup");
            measureFlowUsingProductionRenderer(session, flowId[0]);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    pageCount[0] = session.canvas.toJsonDocument("c38-synthetic-typography", label)
                            .getInt("pageCount");
                } catch (Exception error) { throw new AssertionError(error); }
            });
            AtomicReference<NoteCanvasView.PdfExportSnapshot> snapshotRef = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try { snapshotRef.set(session.canvas.createPdfExportSnapshot()); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            NoteCanvasView.PdfExportSnapshot snapshot = snapshotRef.get();
            if (snapshot == null) throw new AssertionError("paper export snapshot missing");
            try {
            int pages = snapshot.getPageCount();
            for (int page = 0; page < pages; page++) {
                Bitmap rendered = renderPageAndAwait(snapshot, page, session.host);
                writeBitmap(new File(qaDir, label + "-page-" + (page + 1) + ".png"), rendered,
                        label + "-page-" + (page + 1), inventoryRows);
                rendered.recycle();
            }
            int changedPixels = 0;
            if ("v2".equals(label)) {
                // Compare first-page output against the retained v1 artifact from the same run.
                Bitmap before = BitmapFactory.decodeFile(new File(qaDir, "v1-page-1.png").getAbsolutePath());
                Bitmap after = BitmapFactory.decodeFile(new File(qaDir, "v2-page-1.png").getAbsolutePath());
                if (before == null || after == null) throw new AssertionError("paper PNG decode failed");
                if (before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight())
                    throw new AssertionError("v1/v2 paper image geometry differs");
                int[] left = new int[before.getWidth() * before.getHeight()];
                int[] right = new int[left.length];
                before.getPixels(left, 0, before.getWidth(), 0, 0, before.getWidth(), before.getHeight());
                after.getPixels(right, 0, after.getWidth(), 0, 0, after.getWidth(), after.getHeight());
                for (int i = 0; i < left.length; i++) if (left[i] != right[i]) changedPixels++;
                before.recycle(); after.recycle();
            }
            File pdfFile = new File(qaDir, label + ".pdf");
            try (FileOutputStream output = new FileOutputStream(pdfFile, false)) {
                AtomicReference<Exception> failure = new AtomicReference<>();
                CountDownLatch done = new CountDownLatch(1);
                Thread worker = new Thread(() -> {
                    try {
                        PdfNoteIO.exportFlattenedPdf(snapshot, session.host, output,
                                new Handler(Looper.getMainLooper()), (complete, total) -> { });
                    } catch (Exception error) { failure.set(error); }
                    finally { done.countDown(); }
                }, "c38-synthetic-pdf-" + label);
                worker.start();
                if (!done.await(180, TimeUnit.SECONDS)) {
                    worker.interrupt();
                    worker.join(10000);
                    throw new AssertionError("production PDF export timed out for " + label);
                }
                if (failure.get() != null) throw failure.get();
            }
            byte[] header = new byte[5];
            try (java.io.FileInputStream input = new java.io.FileInputStream(pdfFile)) {
                if (input.read(header) != header.length || !"%PDF-".equals(new String(header,
                        java.nio.charset.StandardCharsets.US_ASCII)))
                    throw new AssertionError("production PDF output signature missing");
            }
            addArtifact(inventoryRows, pdfFile, label + "-production-pdf");
            int rasterPages = pages;
            if (pageCount[0] != rasterPages) throw new AssertionError("paper snapshot page count changed");
            return new JSONObject().put("source_sha256", sourceSha).put("page_count", pages)
                    .put("effective_profile", expectedProfile).put("changed_pixels", changedPixels);
            } finally {
                if (!snapshot.isClosed()) snapshot.close();
            }
        }
    }

    private static String syntheticTypographySource() {
        StringBuilder source = new StringBuilder("# Synthetic typography proof\n\n")
                .append("**strong outer with $x^2+\\frac{a}{b}$ and tail**\n\n")
                .append("*emphasis outer with **nested strong $\\sqrt{x}$** tail*\n\n")
                .append("**$\\int_0^1 x^2 dx$**\n\n***triple emphasis $y_1$***\n\n---\n\n")
                .append("`` `code * remains literal*` ``\n\n");
        for (int i = 0; i < 32; i++) {
            source.append("Section ").append(i + 1).append(": **bold $a_1+b_2$** remains part of the synthetic paper flow. ")
                    .append("Normal text follows the formula and continues long enough to paginate naturally.\n\n");
        }
        return source.toString();
    }

    private static Bitmap renderPageAndAwait(NoteCanvasView.PdfExportSnapshot snapshot,
            int page, FrameLayout host) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Bitmap> bitmap = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                snapshot.renderPageWithTextAsync(page, 1000, host,
                        new NoteCanvasView.PdfExportSnapshot.PageCallback() {
                            @Override public void onRendered(Bitmap output) { bitmap.set(output); done.countDown(); }
                            @Override public void onFailure(Exception error) { failure.set(error); done.countDown(); }
                        }));
        assertTrue("paper page render timed out", done.await(45, TimeUnit.SECONDS));
        if (failure.get() != null) throw failure.get();
        if (bitmap.get() == null || bitmap.get().isRecycled()) throw new AssertionError("paper page bitmap missing");
        return bitmap.get();
    }

    /**
     * Seeds the same exact WebView height measurements that the live text-box
     * overlay reports to the paginator before PDF export. The test canvas is a
     * deliberately isolated fixture, so it has no MainActivity overlay manager;
     * without this step a long synthetic flow would bypass the app's normal
     * measurement/reflow contract and could correctly trip the export overflow
     * guard on an estimate-only fragment.
     */
    private static void measureFlowUsingProductionRenderer(Session session, String flowId)
            throws Exception {
        for (int pass = 0; pass < 8; pass++) {
            AtomicReference<MeasurementSnapshot> snapshotRef = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                TextFlow flow = session.canvas.getTextFlows().stream()
                        .filter(value -> flowId.equals(value.id)).findFirst()
                        .orElseThrow(AssertionError::new);
                java.util.List<NoteTextBox> fragments = new java.util.ArrayList<>();
                for (NoteTextBox box : session.canvas.getTextBoxes()) {
                    if (flowId.equals(box.flowId)) fragments.add(box.copy());
                }
                try {
                    int pageHeight = session.canvas.toJsonDocument("c38-synthetic-measure", "measure")
                            .getInt("pageHeight");
                    snapshotRef.set(new MeasurementSnapshot(flow.renderLayoutEpoch,
                            pageHeight, fragments));
                } catch (Exception error) { throw new AssertionError(error); }
            });
            MeasurementSnapshot snapshot = snapshotRef.get();
            if (snapshot == null) throw new AssertionError("measurement snapshot missing");
            int beforeEpoch = snapshot.epoch;
            java.util.List<NoteTextBox> fragments = snapshot.fragments;
            if (fragments.isEmpty()) throw new AssertionError("synthetic flow lost its fragments");
            for (NoteTextBox fragment : fragments) {
                CountDownLatch measuredLatch = new CountDownLatch(1);
                AtomicReference<Float> measuredHeight = new AtomicReference<>();
                AtomicReference<CompiledTextWebView> renderer = new AtomicReference<>();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    CompiledTextWebView view = new CompiledTextWebView(session.host.getContext());
                    renderer.set(view);
                    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                            Math.max(1, Math.round(fragment.width)),
                            Math.max(1, Math.round(snapshot.pageHeight * 2f)),
                            android.view.Gravity.TOP | android.view.Gravity.LEFT);
                    view.setVisibility(View.VISIBLE);
                    view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                    view.getSettings().setOffscreenPreRaster(true);
                    session.host.addView(view, params);
                    int width = Math.max(1, Math.round(fragment.width));
                    int height = Math.max(1, Math.round(snapshot.pageHeight * 2f));
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    view.layout(0, 0, width, height);
                    view.setHeightListener(measuredCssHeight -> {
                        measuredHeight.set(measuredCssHeight);
                        measuredLatch.countDown();
                    });
                    view.render(fragment.format, fragment.displaySource(), fragment.fontSizeSp,
                            fragment.lineHeight, fragment.effectiveMarkdownProfileVersion());
                });
                try {
                    assertTrue("production WebView measurement timed out for " + fragment.id,
                            measuredLatch.await(20, TimeUnit.SECONDS));
                    Float exactHeight = measuredHeight.get();
                    assertNotNull("production WebView returned no fragment height", exactHeight);
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                            session.canvas.applyMeasuredFragmentHeight(fragment.id,
                                    fragment.renderLayoutEpoch, exactHeight));
                } finally {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                        CompiledTextWebView view = renderer.get();
                        if (view != null) {
                            session.host.removeView(view);
                            view.destroy();
                        }
                    });
                }
            }
            AtomicReference<Integer> afterEpoch = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                TextFlow after = session.canvas.getTextFlows().stream()
                        .filter(value -> flowId.equals(value.id)).findFirst()
                        .orElseThrow(AssertionError::new);
                afterEpoch.set(after.renderLayoutEpoch);
            });
            if (afterEpoch.get() == beforeEpoch) return;
        }
        throw new AssertionError("exact WebView measurements did not converge after eight passes");
    }

    private static final class MeasurementSnapshot {
        final int epoch;
        final int pageHeight;
        final java.util.List<NoteTextBox> fragments;
        MeasurementSnapshot(int epoch, int pageHeight, java.util.List<NoteTextBox> fragments) {
            this.epoch = epoch;
            this.pageHeight = pageHeight;
            this.fragments = fragments;
        }
    }

    private static JSONArray scanQaArtifacts(File qaDir) throws Exception {
        JSONArray rows = new JSONArray();
        File[] files = qaDir.listFiles();
        if (files == null) return rows;
        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
        for (File file : files) {
            if ("inventory.json".equals(file.getName())) continue;
            if (java.nio.file.Files.isSymbolicLink(file.toPath()) || !file.isFile()) throw new AssertionError("unexpected QA artifact entry");
            String name = file.getName();
            if (!name.matches("v[12](-page-[1-9][0-9]*\\.png|\\.pdf)"))
                throw new AssertionError("unexpected QA artifact filename");
            String label = name.endsWith(".pdf") ? name.substring(0, 2) + "-production-pdf"
                    : name.substring(0, 2) + "-page-" + name.substring(name.indexOf("page-") + 5, name.length() - 4);
            addArtifact(rows, file, label);
        }
        return rows;
    }

    private static void writeBitmap(File target, Bitmap bitmap, String label, JSONArray rows) throws Exception {
        if (target.exists()) throw new AssertionError("refusing to overwrite QA artifact " + target.getName());
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                throw new AssertionError("PNG encode failed for " + target.getName());
        }
        addArtifact(rows, target, label);
    }

    private static void addArtifact(JSONArray rows, File file, String label) throws Exception {
        rows.put(new JSONObject().put("filename", file.getName()).put("label", label)
                .put("bytes", file.length()).put("sha256", sha256File(file)));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String sha256File(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    private static void writeExclusive(File file, byte[] bytes) throws Exception {
        if (file.exists()) throw new AssertionError("refusing to overwrite " + file.getName());
        try (FileOutputStream output = new FileOutputStream(file, false)) { output.write(bytes); output.getFD().sync(); }
    }

    @Test public void newFootprintDeltaDoesNotCancelEqualCountCollisionAndPdfInk() throws Exception {
        RectF oldA = new RectF(0, 0, 20, 20);
        RectF candidateB = new RectF(40, 0, 60, 20);
        java.util.List<RectF> newAreas = NoteCanvasView.newFootprintRegions(
                candidateB, java.util.Collections.singletonList(oldA), 0f);
        assertFalse(newAreas.isEmpty());
        assertFalse(intersectsAny(newAreas, oldA));
        assertTrue(intersectsAny(newAreas, candidateB));
        assertEquals("one old A collision and one candidate B collision cancel by count only",
                1, intersectCount(java.util.Collections.singletonList(oldA), oldA));
        assertEquals(1, intersectCount(newAreas, candidateB));
        RectF paper = new RectF(0, 0, 100, 100);
        RectF oldCropA = new RectF(-20, 10, 10, 30);
        RectF candidateCropB = new RectF(90, 10, 120, 30);
        assertTrue("an old crop disappearing cannot cancel a new crop elsewhere",
                NoteCanvasView.hasNewOutsideArea(candidateCropB,
                        java.util.Collections.singletonList(oldCropA), paper));

        try (Session session = Session.open()) {
            File pdf = new File(session.host.getContext().getCacheDir(), "typography-roi-" +
                    java.util.UUID.randomUUID() + ".pdf");
            writeTypographyPdf(pdf);
            PdfBackground background = new PdfBackground(pdf, session.canvas);
            try {
                assertFalse("white-only PDF ROI should permit a profile footprint",
                        background.hasInkInPageRect(0, new RectF(500, 100, 600, 200), 720, 960));
                assertTrue("black PDF content in a new candidate area must be detected",
                        background.hasInkInPageRect(0, new RectF(240, 100, 270, 160), 720, 960));
                assertTrue("colored PDF content must also count as occupied paper",
                        background.hasInkInPageRect(0, new RectF(420, 100, 455, 160), 720, 960));
                java.util.List<RectF> expandedDelta = NoteCanvasView.newFootprintRegions(
                        new RectF(80, 100, 280, 160), java.util.Collections.singletonList(
                                new RectF(80, 100, 120, 160)), 4f);
                assertTrue("newly expanded area beyond an old PDF ROI must be checked",
                        expandedDelta.stream().anyMatch(area -> RectF.intersects(area,
                                new RectF(240, 100, 270, 160))));
                assertTrue(background.hasInkInPageRect(0, new RectF(240, 100, 270, 160), 720, 960));
            } finally { background.close(); pdf.delete(); }
            File missing = new File(pdf.getParentFile(), "missing-" + java.util.UUID.randomUUID() + ".pdf");
            try {
                new PdfBackground(missing, session.canvas);
                fail("missing PDF must fail closed");
            } catch (java.io.IOException expected) { }
        }
    }

    @Test public void fullPaperCandidateDoesNotMutateUntilAcceptedAndReopensAsV2() throws Exception {
        try (Session session = Session.open()) {
            AtomicReference<NoteTextBox> first = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    session.canvas.loadJsonDocument(emptyLegacyDocument());
                    first.set(session.canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                            "**before $x^2$ after**\n\n---\n\n" +
                                    "A paragraph that continues across the paper without changing its source.",
                            36f, 42f));
                } catch (Exception error) { throw new AssertionError(error); }
            });
            assertNotNull(first.get());
            AtomicReference<NoteCanvasView.TypographySwitchCandidate> candidateRef =
                    new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    candidateRef.set(session.canvas.prepareTypographySwitch(first.get().flowId, 2));
                    JSONObject unchanged = session.canvas.toJsonDocument("typography-test", "test");
                    assertEquals(9, NoteStore.schemaVersion(unchanged));
                    assertFalse(unchanged.getJSONArray("textFlows").getJSONObject(0)
                            .has("markdownProfileVersion"));
                } catch (Exception error) { throw new AssertionError(error); }
            });
            NoteCanvasView.TypographySwitchCandidate candidate = candidateRef.get();
            assertNotNull(candidate);
            prepareTypographyCandidateAndAwait(session, candidate);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertTrue("all candidate fragments must measure without clipping",
                        session.canvas.validateTypographyMeasurements(candidate, candidate.after));
                JSONObject stillOriginal;
                try {
                    stillOriginal = session.canvas.toJsonDocument("typography-test", "test");
                    assertEquals(9, NoteStore.schemaVersion(stillOriginal));
                }
                catch (Exception error) { throw new AssertionError(error); }
                assertTrue(session.canvas.commitTypographySwitch(candidate).committed);
                try {
                    JSONObject committed = session.canvas.toJsonDocument("typography-test", "test");
                    assertEquals(10, NoteStore.schemaVersion(committed));
                    JSONObject flow = committed.getJSONArray("textFlows").getJSONObject(0);
                    assertEquals(2, flow.getInt("markdownProfileVersion"));
                    assertEquals(first.get().source, flow.getString("source"));
                    NoteCanvasView reopened = new NoteCanvasView(session.host.getContext());
                    reopened.loadJsonDocument(committed);
                    assertEquals(2, reopened.getTextFlows().get(0).effectiveMarkdownProfileVersion());
                } catch (Exception error) { throw new AssertionError(error); }
            });
            candidate.before.close();
            candidate.after.close();

            AtomicReference<NoteCanvasView.TypographySwitchCandidate> backCandidateRef =
                    new AtomicReference<>();
            final int[] pageCountAfterV2 = {0};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    JSONObject current = session.canvas.toJsonDocument("typography-test", "test");
                    assertEquals(10, NoteStore.schemaVersion(current));
                    pageCountAfterV2[0] = current.getInt("pageCount");
                    backCandidateRef.set(session.canvas.prepareTypographySwitch(first.get().flowId, 1));
                    JSONObject unchanged = session.canvas.toJsonDocument("typography-test", "test");
                    assertEquals(10, NoteStore.schemaVersion(unchanged));
                    assertEquals(2, unchanged.getJSONArray("textFlows").getJSONObject(0)
                            .getInt("markdownProfileVersion"));
                } catch (Exception error) { throw new AssertionError(error); }
            });
            NoteCanvasView.TypographySwitchCandidate backCandidate = backCandidateRef.get();
            assertNotNull(backCandidate);
            prepareTypographyCandidateAndAwait(session, backCandidate);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertTrue(session.canvas.validateTypographyMeasurements(backCandidate,
                        backCandidate.after));
                assertTrue("switching layout must not shrink the committed paper",
                        backCandidate.candidatePageCount >= pageCountAfterV2[0]);
                try {
                    JSONObject beforeCommit = session.canvas.toJsonDocument("typography-test", "test");
                    assertEquals(10, NoteStore.schemaVersion(beforeCommit));
                    JSONObject beforeFlow = beforeCommit.getJSONArray("textFlows").getJSONObject(0);
                    assertEquals(2, beforeFlow.getInt("markdownProfileVersion"));
                    assertTrue(session.canvas.commitTypographySwitch(backCandidate).committed);
                    JSONObject legacyStyle = session.canvas.toJsonDocument("typography-test", "test");
                    assertEquals(10, NoteStore.schemaVersion(legacyStyle));
                    assertTrue("profile 1 must be represented sparsely", !legacyStyle
                            .getJSONArray("textFlows").getJSONObject(0)
                            .has("markdownProfileVersion"));
                    JSONObject afterFlow = legacyStyle.getJSONArray("textFlows").getJSONObject(0);
                    assertEquals(beforeFlow.getString("id"), afterFlow.getString("id"));
                    assertEquals(beforeFlow.getString("source"), afterFlow.getString("source"));
                    assertEquals(beforeFlow.getInt("anchorPageIndex"), afterFlow.getInt("anchorPageIndex"));
                    assertEquals(beforeFlow.getDouble("anchorXInPage"),
                            afterFlow.getDouble("anchorXInPage"), 0d);
                    assertEquals(beforeFlow.getDouble("anchorYInPage"),
                            afterFlow.getDouble("anchorYInPage"), 0d);
                    assertTrue("stored page count must never shrink when switching styles",
                            legacyStyle.getInt("pageCount") >= pageCountAfterV2[0]);
                    if (beforeFlow.has("agentResult"))
                        assertEquals(beforeFlow.getJSONObject("agentResult").toString(),
                                afterFlow.getJSONObject("agentResult").toString());
                    NoteCanvasView reopenedOldStyle = new NoteCanvasView(session.host.getContext());
                    reopenedOldStyle.loadJsonDocument(legacyStyle);
                    assertEquals(1, reopenedOldStyle.getTextFlows().get(0)
                            .effectiveMarkdownProfileVersion());
                } catch (Exception error) { throw new AssertionError(error); }
            });
            backCandidate.before.close();
            backCandidate.after.close();
        }
    }

    @Test public void staleCandidateCannotReplaceConcurrentFlowEdit() throws Exception {
        try (Session session = Session.open()) {
            AtomicReference<NoteTextBox> first = new AtomicReference<>();
            AtomicReference<NoteCanvasView.TypographySwitchCandidate> candidate = new AtomicReference<>();
            AtomicReference<String> fragment = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    session.canvas.loadJsonDocument(emptyLegacyDocument());
                    first.set(session.canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                            "**before $x$ after**", 36f, 42f));
                    fragment.set(session.canvas.getTextBoxes().get(0).id);
                    candidate.set(session.canvas.prepareTypographySwitch(first.get().flowId, 2));
                } catch (Exception error) { throw new AssertionError(error); }
            });
            prepareTypographyCandidateAndAwait(session, candidate.get());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    assertTrue(session.canvas.updateTextBox(fragment.get(), NoteTextBox.Format.MARKDOWN,
                            "Concurrent edit", 16f, 1.35f).committed);
                    assertFalse(session.canvas.commitTypographySwitch(candidate.get()).committed);
                    JSONObject current = session.canvas.toJsonDocument("typography-test", "test");
                    assertEquals(9, NoteStore.schemaVersion(current));
                    JSONObject flow = current.getJSONArray("textFlows").getJSONObject(0);
                    assertFalse(flow.has("markdownProfileVersion"));
                    assertEquals("Concurrent edit", flow.getString("source"));

                    NoteTextBox currentFragment = session.canvas.getTextBoxes().get(0);
                    TextFlow currentFlow = session.canvas.getTextFlows().stream()
                            .filter(value -> currentFragment.flowId.equals(value.id))
                            .findFirst().orElseThrow(AssertionError::new);
                    int epochBeforeCasFailure = currentFlow.renderLayoutEpoch;
                    float correctionBeforeCasFailure = currentFlow.heightCorrection;
                    float density = session.host.getResources().getDisplayMetrics().density;
                    float deferredMeasuredHeightDp = currentFragment.height / density * 1.5f;
                    session.canvas.setTypographyCasInFlight(true);
                    session.canvas.applyMeasuredFragmentHeight(currentFragment.id,
                            currentFragment.renderLayoutEpoch, deferredMeasuredHeightDp);
                    assertEquals("CAS must freeze measured geometry until outcome",
                            epochBeforeCasFailure,
                            session.canvas.getTextFlows().get(0).renderLayoutEpoch);
                    assertEquals(correctionBeforeCasFailure,
                            session.canvas.getTextFlows().get(0).heightCorrection, 0f);
                    session.canvas.setTypographyCasInFlight(false);
                    TextFlow replayedFlow = session.canvas.getTextFlows().stream()
                            .filter(value -> currentFragment.flowId.equals(value.id))
                            .findFirst().orElseThrow(AssertionError::new);
                    assertTrue("failed CAS must replay the still-current deferred measurement",
                            replayedFlow.renderLayoutEpoch > epochBeforeCasFailure &&
                                    replayedFlow.heightCorrection > correctionBeforeCasFailure);
                    JSONObject afterReplay = session.canvas.toJsonDocument("typography-test", "test");
                    assertEquals("Concurrent edit", afterReplay.getJSONArray("textFlows")
                            .getJSONObject(0).getString("source"));
                } catch (Exception error) { throw new AssertionError(error); }
            });
            candidate.get().before.close(); candidate.get().after.close();
        }
    }

    private static void prepareTypographyCandidateAndAwait(Session session,
            NoteCanvasView.TypographySwitchCandidate candidate) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicReference<NoteCanvasView.TypographySwitchCandidate> ready = new AtomicReference<>();
        AtomicReference<NoteCanvasView.TypographyPreviewHandle> handle = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                handle.set(session.canvas.prepareTypographySwitchPreviewAsync(candidate, session.host,
                        new NoteCanvasView.TypographyPreparationCallback() {
                            @Override public void onReady(
                                    NoteCanvasView.TypographySwitchCandidate value) {
                                ready.set(value);
                                done.countDown();
                            }
                            @Override public void onFailure(Exception error) {
                                failure.set(error);
                                done.countDown();
                            }
                        })));
        if (!done.await(180, TimeUnit.SECONDS)) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (handle.get() != null) handle.get().cancel();
                candidate.before.close();
                candidate.after.close();
            });
            throw new AssertionError("candidate exact measurement/refinement timed out");
        }
        if (failure.get() != null) throw failure.get();
        assertSame(candidate, ready.get());
        assertTrue("only a refined candidate can be confirmed", candidate.measured);
    }

    private static JSONObject emptyLegacyDocument() throws Exception {
        return new JSONObject().put("schemaVersion", 9).put("id", "typography-test")
                .put("title", "Typography test").put("pageWidth", 720).put("pageHeight", 960)
                .put("canvasWidth", 720).put("canvasHeight", 960).put("pageGap", 24)
                .put("pageCount", 1).put("pdfPageCount", 0).put("strokes", new JSONArray())
                .put("images", new JSONArray()).put("textFlows", new JSONArray())
                .put("textBoxes", new JSONArray());
    }

    private static boolean intersectsAny(java.util.List<RectF> regions, RectF target) {
        return intersectCount(regions, target) > 0;
    }

    private static int intersectCount(java.util.List<RectF> regions, RectF target) {
        int count = 0;
        for (RectF region : regions) if (RectF.intersects(region, target)) count++;
        return count;
    }

    private static void writeTypographyPdf(File target) throws Exception {
        PdfDocument document = new PdfDocument();
        try {
            PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(720, 960, 1).create());
            page.getCanvas().drawColor(Color.WHITE);
            Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG); ink.setColor(Color.BLACK);
            page.getCanvas().drawRect(100, 120, 108, 145, ink);
            page.getCanvas().drawRect(250, 120, 258, 145, ink);
            ink.setColor(Color.RED);
            page.getCanvas().drawRect(430, 120, 438, 145, ink);
            document.finishPage(page);
            try (FileOutputStream out = new FileOutputStream(target)) { document.writeTo(out); }
        } finally { document.close(); }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class Session implements AutoCloseable {
        final ActivityScenario<MainActivity> scenario;
        final FrameLayout host;
        final NoteCanvasView canvas;
        Session(ActivityScenario<MainActivity> scenario, FrameLayout host, NoteCanvasView canvas) {
            this.scenario = scenario; this.host = host; this.canvas = canvas;
        }
        static Session open() {
            ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
            AtomicReference<FrameLayout> hostRef = new AtomicReference<>();
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
                hostRef.set(host); canvasRef.set(canvas);
            });
            return new Session(scenario, hostRef.get(), canvasRef.get());
        }
        @Override public void close() { scenario.close(); }
    }
}
