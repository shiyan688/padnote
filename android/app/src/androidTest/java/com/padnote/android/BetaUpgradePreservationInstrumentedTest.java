package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.swipeLeft;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Beta6-to-beta8 preservation using a pre-existing schema-7 fixture and real Main UI routes. */
@RunWith(AndroidJUnit4.class)
public final class BetaUpgradePreservationInstrumentedTest {
    private static final String NOTE_ID = "fixture-schema-7";
    private static final String NOTE_TITLE = "Schema 7 构造回归样例";
    private static final String SOURCE = "SCHEMA7_PDF_ANNOTATION";
    private static final String EDITED = SOURCE + "\n原地升级 UI 保存标记 beta8";
    private static final String SINK_AUTHORITY = "com.padnote.android.beta.test.contentoutline";

    @Test public void existingSchemaSevenNoteSurvivesUpgradeUiEditReopenAndPdfExport() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context test = InstrumentationRegistry.getInstrumentation().getContext();
        File noteFile = NoteStore.noteFileForBackup(target, NOTE_ID);
        File pdfFile = NoteStore.pdfFile(target, NOTE_ID);
        File coverFile = CoverStore.coverFile(noteFile.getParentFile(), NOTE_ID);
        File videoRoot = new File(target.getFilesDir(), "video-attachments");
        File videoIndex = new File(videoRoot, "index.json");
        JSONObject before = NoteStore.load(target, NOTE_ID);
        assertEquals("fixture must be injected before beta8 install", 7, before.getInt("schemaVersion"));
        assertEquals(NOTE_TITLE, before.getString("title"));
        assertEquals(SOURCE, onlyFlow(before).getString("source"));
        assertTrue(pdfFile.isFile() && pdfFile.length() > 0);
        assertTrue(coverFile.isFile() && coverFile.length() > 0);
        assertTrue(videoIndex.isFile());
        byte[] originalPdf = Files.readAllBytes(pdfFile.toPath());
        byte[] originalCover = Files.readAllBytes(coverFile.toPath());
        byte[] originalVideoIndex = Files.readAllBytes(videoIndex.toPath());
        String pdfSha = sha(originalPdf), coverSha = sha(originalCover), videoIndexSha = sha(originalVideoIndex);
        String inkSha = inkSha(before.getJSONArray("strokes"));
        List<String> initialIds = noteIds(NoteStore.list(target));
        assertTrue("fixture must appear in the real bookshelf", initialIds.contains(NOTE_ID));
        VideoAttachmentStore attachments = new VideoAttachmentStore(target);
        List<VideoAttachmentStore.Attachment> linked = attachments.listForNote(NOTE_ID);
        assertEquals("fixture must have exactly one real linked video", 1, linked.size());
        VideoAttachmentStore.Attachment originalAttachment = linked.get(0);
        File videoFile = new File(videoRoot, originalAttachment.storedName);
        assertEquals(originalAttachment.sizeBytes, videoFile.length());
        String videoSha = sha(Files.readAllBytes(videoFile.toPath()));
        assertEquals(originalAttachment.sha256, videoSha);

        ActivityScenario<MainActivity> scenario = null;
        String session = UUID.randomUUID().toString();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor pdfPicker = null;
        boolean sinkStarted = false;
        try {
            scenario = ActivityScenario.launch(MainActivity.class);
            clickBookshelfNote(NOTE_TITLE);
            awaitEditor(scenario);
            openFlowInEditor();
            onView(withContentDescription("文字格式工具栏，可横向滚动")).perform(swipeLeft());
            onView(withContentDescription("文字格式工具栏，可横向滚动")).check(matches(isDisplayed()));
            onView(withContentDescription("完成文字输入")).perform(click());
            onView(withContentDescription("立即保存")).perform(scrollTo(), click());
            assertTrue("edited source must be saved as schema 8", awaitSaved(target, EDITED, 20000));
            assertPreserved(target, NOTE_ID, EDITED, inkSha, pdfSha, coverSha, videoIndexSha,
                    originalAttachment, videoFile, videoSha);

            onView(withContentDescription("返回书架")).perform(click());
            clickBookshelfNote(NOTE_TITLE);
            awaitEditor(scenario);
            assertEquals(EDITED, onlyFlow(NoteStore.load(target, NOTE_ID)).getString("source"));
            scenario.onActivity(activity -> {
                assertNotNull(activity.canvasForTest());
                assertEquals(EDITED, activity.canvasForTest().getTextFlows().get(0).source);
            });
            assertPreserved(target, NOTE_ID, EDITED, inkSha, pdfSha, coverSha, videoIndexSha,
                    originalAttachment, videoFile, videoSha);

            ContentOutlineSinkProvider.beginSession(test, session);
            sinkStarted = true;
            Uri sinkUri = Uri.parse("content://" + SINK_AUTHORITY + "/" + session + "/export.pdf");
            Intent result = new Intent().setData(sinkUri).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            IntentFilter pdfFilter = new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);
            pdfFilter.addCategory(Intent.CATEGORY_OPENABLE);
            pdfFilter.addDataType("application/pdf");
            pdfPicker = instrumentation.addMonitor(pdfFilter,
                    new Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, result), true);
            onView(withContentDescription("导出笔记")).perform(scrollTo(), click());
            onView(withText("标准 PDF（页面已合并）")).inRoot(isDialog()).perform(click());
            final Instrumentation.ActivityMonitor expectedPdfPicker = pdfPicker;
            await(() -> instrumentation.checkMonitorHit(expectedPdfPicker, 1),
                    "real ACTION_CREATE_DOCUMENT application/pdf flow must be intercepted");
            ContentOutlineSinkProvider.Capture capture =
                    ContentOutlineSinkProvider.awaitCapture(test, session, 30000);
            assertTrue("PDF export writer must reach EOF: " + capture.failure, capture.complete);
            assertNull(capture.failure);
            assertEquals(1, capture.openCount);
            assertEquals(1, capture.eofCount);
            assertNotNull(capture.bytes);
            assertTrue("exported PDF must be non-empty", capture.bytes.length > 8);
            assertTrue("export must contain a PDF header",
                    new String(capture.bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"));
            File exported = new File(target.getCacheDir(), "beta-upgrade-export-" + session + ".pdf");
            try {
                try (FileOutputStream output = new FileOutputStream(exported)) {
                    output.write(capture.bytes); output.getFD().sync();
                }
                try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(exported,
                        ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer renderer = new PdfRenderer(descriptor)) {
                    assertEquals("all two note pages must be present in the standard export",
                            2, renderer.getPageCount());
                    for (int i = 0; i < renderer.getPageCount(); i++) {
                        try (PdfRenderer.Page page = renderer.openPage(i)) {
                            assertTrue(page.getWidth() > 0 && page.getHeight() > 0);
                        }
                    }
                }
            } finally { if (exported.exists()) assertTrue(exported.delete()); }
            assertPreserved(target, NOTE_ID, EDITED, inkSha, pdfSha, coverSha, videoIndexSha,
                    originalAttachment, videoFile, videoSha);
            assertEquals("UI flow must not create or delete any note", initialIds, noteIds(NoteStore.list(target)));
        } finally {
            if (pdfPicker != null) instrumentation.removeMonitor(pdfPicker);
            if (scenario != null) scenario.close();
            if (sinkStarted) ContentOutlineSinkProvider.cleanupSession(test, session);
        }
    }

    private static void openFlowInEditor() {
        onView(withContentDescription("按页查看文字、公式、图片和手写状态")).perform(scrollTo(), click());
        onView(withText(containsString(SOURCE))).inRoot(isDialog()).check(matches(isDisplayed())).perform(click());
        onView(withContentDescription("完成文字输入")).check(matches(isDisplayed()));
        onView(allOf(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.EditText.class),
                isDisplayed(), withHint("直接输入 Markdown，可包含 $...$ 公式")))
                .perform(replaceText(EDITED), closeSoftKeyboard());
    }

    private static void clickBookshelfNote(String title) {
        onView(withContentDescription("打开笔记 " + title + "。长按可重命名或更换封面。"))
                .perform(scrollTo(), click());
    }

    private static void awaitEditor(ActivityScenario<MainActivity> scenario) throws Exception {
        await(() -> {
            final boolean[] ready = {false};
            scenario.onActivity(activity -> ready[0] = activity.canvasForTest() != null);
            return ready[0];
        }, "editor did not restore fixture");
    }

    private static boolean awaitSaved(Context context, String source, long timeout) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            JSONObject current = NoteStore.load(context, NOTE_ID);
            if (current.optInt("schemaVersion") == 8 && source.equals(onlyFlow(current).getString("source"))) return true;
            SystemClock.sleep(100);
        }
        return false;
    }

    private static void assertPreserved(Context context, String id, String source, String inkSha,
                                        String pdfSha, String coverSha, String videoIndexSha,
                                        VideoAttachmentStore.Attachment original,
                                        File videoFile, String videoSha) throws Exception {
        JSONObject document = NoteStore.load(context, id);
        assertEquals(8, document.getInt("schemaVersion"));
        assertEquals(source, onlyFlow(document).getString("source"));
        assertEquals(2, document.getInt("pageCount"));
        assertEquals(inkSha, inkSha(document.getJSONArray("strokes")));
        assertEquals(pdfSha, sha(Files.readAllBytes(NoteStore.pdfFile(context, id).toPath())));
        File cover = CoverStore.coverFile(NoteStore.noteFileForBackup(context, id).getParentFile(), id);
        assertEquals(coverSha, sha(Files.readAllBytes(cover.toPath())));
        File index = new File(new File(context.getFilesDir(), "video-attachments"), "index.json");
        assertEquals(videoIndexSha, sha(Files.readAllBytes(index.toPath())));
        List<VideoAttachmentStore.Attachment> linked = new VideoAttachmentStore(context).listForNote(id);
        assertEquals(1, linked.size());
        assertEquals(original.id, linked.get(0).id);
        assertEquals(original.sha256, linked.get(0).sha256);
        assertEquals(original.storedName, linked.get(0).storedName);
        assertEquals(videoSha, sha(Files.readAllBytes(videoFile.toPath())));
    }

    private static JSONObject onlyFlow(JSONObject document) throws Exception {
        JSONArray flows = document.getJSONArray("textFlows");
        assertEquals(1, flows.length());
        return flows.getJSONObject(0);
    }

    private static String inkSha(JSONArray strokes) throws Exception {
        JSONArray canonical = new JSONArray();
        for (int i = 0; i < strokes.length(); i++) {
            JSONObject stroke = strokes.getJSONObject(i);
            JSONObject item = new JSONObject().put("id", stroke.getString("id"))
                    .put("color", stroke.getString("color")).put("baseWidth", stroke.getDouble("baseWidth"));
            JSONArray points = new JSONArray();
            JSONArray inputPoints = stroke.getJSONArray("points");
            for (int j = 0; j < inputPoints.length(); j++) {
                JSONObject point = inputPoints.getJSONObject(j);
                points.put(new JSONObject().put("x", point.getDouble("x")).put("y", point.getDouble("y"))
                        .put("timestamp", point.getLong("timestamp")).put("pressure", point.getDouble("pressure")));
            }
            canonical.put(item.put("points", points));
        }
        return sha(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> noteIds(List<NoteStore.Entry> entries) {
        List<String> ids = new ArrayList<>(); for (NoteStore.Entry entry : entries) ids.add(entry.id);
        Collections.sort(ids); return ids;
    }

    private static String sha(byte[] raw) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private interface Condition { boolean check() throws Exception; }
    private static void await(Condition condition, String message) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.check()) return;
            SystemClock.sleep(75);
        }
        fail(message);
    }
}
