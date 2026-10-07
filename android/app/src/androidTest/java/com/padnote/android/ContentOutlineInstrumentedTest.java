package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.swipeLeft;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;
import static org.hamcrest.Matchers.allOf;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.Espresso;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real outline/editor/copy flow plus isolated native-cancel and synthetic-URI export checks. */
@RunWith(AndroidJUnit4.class)
public final class ContentOutlineInstrumentedTest {
    private static final String FIRST_SOURCE = "第一页：先核对对象起始页。";
    private static final String SECOND_SOURCE = "第三页：待从大纲定位并编辑。";
    private static final String EDITED_SOURCE = "第三页：已从大纲定位并持久保存。";

    @Test public void outlineTargetsExistingFlowPersistsCopyAndExportsUtf8() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        NoteStore.Entry note = seedMixedNote(context);
        ActivityScenario<MainActivity> scenario = null;
        String sinkSession = UUID.randomUUID().toString();
        boolean sinkSessionStarted = false;
        try {
            scenario = ActivityScenario.launch(MainActivity.class);
            clickNoteWhenVisible(note.title);
            awaitEditor(scenario);

            openOutline();
            onView(withText(containsString(SECOND_SOURCE))).inRoot(isDialog())
                    .check(matches(isDisplayed())).perform(click());
            awaitCurrentPage(scenario, 3);
            onView(allOf(isAssignableFrom(EditText.class), withText(SECOND_SOURCE)))
                    .perform(replaceText(EDITED_SOURCE));
            Espresso.closeSoftKeyboard();
            onView(allOf(withContentDescription("文字格式工具栏，可横向滚动"), isDisplayed()))
                    .perform(swipeLeft());
            onView(allOf(withContentDescription("增大行距"), isDisplayed()))
                    .perform(click());
            onView(allOf(withContentDescription("完成文字输入"), isDisplayed()))
                    .check((view, noViewFoundException) -> {
                        Rect buttonBounds = new Rect();
                        assertTrue("Done button must have visible screen bounds",
                                view.getGlobalVisibleRect(buttonBounds));
                        assertEquals("Done button must not be clipped horizontally",
                                view.getWidth(), buttonBounds.width());
                        assertEquals("Done button must not be clipped vertically",
                                view.getHeight(), buttonBounds.height());
                        ViewGroup actions = (ViewGroup) view.getParent();
                        ViewGroup toolbar = (ViewGroup) actions.getParent();
                        Rect toolbarBounds = new Rect();
                        assertTrue("editor toolbar must be visible",
                                toolbar.getGlobalVisibleRect(toolbarBounds));
                        assertTrue("Done button must be wholly inside the visible toolbar",
                                toolbarBounds.contains(buttonBounds));
                    })
                    .perform(click());
            awaitSavedFlow(context, note.id, "flow-second", EDITED_SOURCE);
            JSONObject savedAfterEdit = NoteStore.load(context, note.id);
            JSONArray savedFlows = savedAfterEdit.getJSONArray("textFlows");
            JSONObject savedSecond = findFlow(savedFlows, "flow-second");
            assertTrue("scrolled line-height control must update and persist the existing flow",
                    savedSecond.getDouble("lineHeight") > TextFlow.DEFAULT_LINE_HEIGHT);

            scenario.recreate();
            clickNoteWhenVisible(note.title);
            awaitEditor(scenario);
            assertFlowState(context, note.id, "flow-second", EDITED_SOURCE);
            assertEquals(2, NoteStore.load(context, note.id).getJSONArray("textFlows").length());

            openOutline();
            onView(withText("第 2 页 · 图片（未添加说明）")).inRoot(isDialog())
                    .check(matches(isDisplayed()));
            onView(withText("第 2 页 · 手写笔迹尚未识别")).inRoot(isDialog())
                    .check(matches(isDisplayed()));
            onView(withText("复制 Markdown")).inRoot(isDialog()).perform(click());
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            assertNotNull("clipboard service must exist on the test device", clipboard);
            ClipData clip = clipboard.getPrimaryClip();
            assertNotNull(clip);
            assertEquals("PadNote 内容大纲", clip.getDescription().getLabel());
            String markdown = clip.getItemAt(0).coerceToText(context).toString();
            assertTrue(markdown.contains(FIRST_SOURCE));
            assertTrue(markdown.contains(EDITED_SOURCE));
            assertEquals("the updated flow appears once", 1, occurrences(markdown, EDITED_SOURCE));
            assertTrue(markdown.contains("图片（未添加说明；图片文件未包含在此 Markdown 中）"));
            assertTrue(markdown.contains("手写笔迹尚未识别"));

            JSONObject beforeCancel = NoteStore.load(context, note.id);
            openOutline();
            onView(withText("导出 Markdown")).inRoot(isDialog()).perform(click());
            assertTrue("native ACTION_CREATE_DOCUMENT picker must become foreground", awaitDocumentsUi());
            UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
            assertTrue("Back must dismiss the actual platform picker",
                    automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK));
            awaitEditor(scenario);
            awaitPendingCleared(scenario);
            JSONObject afterCancel = NoteStore.load(context, note.id);
            beforeCancel.remove("updatedAt");
            afterCancel.remove("updatedAt");
            assertEquals("native picker cancellation must not change note authoring data",
                    beforeCancel.toString(), afterCancel.toString());

            // This intentionally bypasses DocumentsUI only for the successful byte sink:
            // it is synthetic content:// URI delivery, not a native SAF save claim.
            ContentOutlineSinkProvider.beginSession(context, sinkSession);
            sinkSessionStarted = true;
            final String expectedMarkdown = markdown;
            final String sinkUri = ContentOutlineSinkProvider.uriString(
                    InstrumentationRegistry.getInstrumentation().getContext(), sinkSession);
            scenario.onActivity(activity -> deliverSyntheticUri(activity, expectedMarkdown, sinkUri));
            ContentOutlineSinkProvider.Capture capture =
                    ContentOutlineSinkProvider.awaitCapture(context, sinkSession, 10000);
            assertTrue("provider writer did not finish; open=" + capture.openCount
                    + " eof=" + capture.eofCount + " failure=" + capture.failure, capture.complete);
            assertNull("provider reader must finish without error", capture.failure);
            assertEquals("provider receives one output stream", 1, capture.openCount);
            assertEquals("provider observes EOF after close", 1, capture.eofCount);
            assertArrayEquals(expectedMarkdown.getBytes(StandardCharsets.UTF_8),
                    capture.bytes);
        } finally {
            if (scenario != null) scenario.close();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            NoteStore.delete(context, note.id);
            if (sinkSessionStarted) ContentOutlineSinkProvider.cleanupSession(context, sinkSession);
        }
    }

    private static NoteStore.Entry seedMixedNote(Context context) throws Exception {
        String id = UUID.randomUUID().toString();
        NoteStore.Entry note = NoteStore.create(context, "大纲合成测试 " + id.substring(0, 8));
        try {
            JSONObject document = NoteStore.load(context, note.id);
            document.put("pageWidth", 612).put("canvasWidth", 612)
                    .put("pageHeight", 792).put("canvasHeight", 792)
                    .put("pageGap", 28).put("pageCount", 3);
            JSONArray flows = new JSONArray();
            flows.put(new TextFlow("flow-first", NoteTextBox.Format.MARKDOWN, FIRST_SOURCE,
                    16, TextFlow.DEFAULT_LINE_HEIGHT, 420, 0, 24, 24).toJson());
            flows.put(new TextFlow("flow-second", NoteTextBox.Format.MARKDOWN, SECOND_SOURCE,
                    16, TextFlow.DEFAULT_LINE_HEIGHT, 420, 2, 24, 24).toJson());
            document.put("textFlows", flows);

            InkStroke stroke = new InkStroke("outline-stroke", Color.BLACK, 2f,
                    System.currentTimeMillis());
            stroke.points.add(new InkPoint(80, 830, 1, .8f));
            stroke.points.add(new InkPoint(130, 870, 2, .8f));
            JSONArray strokes = new JSONArray(); strokes.put(stroke.toJson());
            document.put("strokes", strokes);

            Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            String png;
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
                png = android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP);
            } finally { bitmap.recycle(); }
            JSONObject image = new JSONObject().put("id", "outline-image").put("png", png)
                    .put("page", 1).put("x", 32).put("y", 32).put("width", 18).put("height", 18);
            JSONArray images = new JSONArray(); images.put(image); document.put("images", images);
            NoteStore.save(context, note.id, note.title, document.toString());
            return note;
        } catch (Exception failure) {
            try { NoteStore.delete(context, note.id); } catch (Exception ignored) { }
            throw failure;
        }
    }

    private static void deliverSyntheticUri(MainActivity activity, String markdown, String uri) {
        try {
            Field pending = MainActivity.class.getDeclaredField("pendingContentOutlineMarkdown");
            pending.setAccessible(true);
            pending.set(activity, markdown);
            Method export = MainActivity.class.getDeclaredMethod("exportContentOutlineToUri", android.net.Uri.class);
            export.setAccessible(true);
            export.invoke(activity, android.net.Uri.parse(uri));
        } catch (Exception failure) {
            throw new AssertionError("synthetic URI delivery setup failed", failure);
        }
    }

    private static void openOutline() {
        onView(withText("内容大纲")).perform(scrollTo(), click());
        onView(withText("内容大纲 · 文字对象按起始页归组")).inRoot(isDialog())
                .check(matches(isDisplayed()));
    }

    private static void clickNoteWhenVisible(String title) {
        String description = "打开笔记 " + title + "。长按可重命名或更换封面。";
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            try { onView(withContentDescription(description)).perform(scrollTo(), click()); return; }
            catch (RuntimeException | AssertionError unavailable) { SystemClock.sleep(75); }
        }
        fail("synthetic NoteStore row never appeared on the bookshelf");
    }

    private static void awaitCurrentPage(ActivityScenario<MainActivity> scenario, int expected)
            throws Exception {
        await(() -> {
            java.util.concurrent.atomic.AtomicInteger page = new java.util.concurrent.atomic.AtomicInteger(-1);
            scenario.onActivity(activity -> page.set(activity.canvasForTest().getCurrentPage()));
            return page.get() == expected;
        }, "outline selection did not navigate to the flow's persisted starting page");
    }

    private static void awaitEditor(ActivityScenario<MainActivity> scenario) throws Exception {
        await(() -> {
            AtomicBoolean ready = new AtomicBoolean();
            scenario.onActivity(activity -> ready.set(activity.canvasForTest() != null));
            return ready.get();
        }, "editor did not restore the synthetic note");
    }

    private static JSONObject findFlow(JSONArray flows, String flowId) throws Exception {
        for (int i = 0; i < flows.length(); i++) {
            JSONObject flow = flows.getJSONObject(i);
            if (flowId.equals(flow.optString("id"))) return flow;
        }
        throw new AssertionError("expected flow id missing: " + flowId);
    }

    private static void awaitSavedFlow(Context context, String noteId, String flowId, String source)
            throws Exception {
        await(() -> {
            try { assertFlowState(context, noteId, flowId, source); return true; }
            catch (Throwable pending) { return false; }
        }, "edited flow did not reach durable NoteStore state");
    }

    private static void assertFlowState(Context context, String noteId, String flowId, String source)
            throws Exception {
        JSONArray flows = NoteStore.load(context, noteId).getJSONArray("textFlows");
        int matches = 0;
        for (int i = 0; i < flows.length(); i++) {
            JSONObject flow = flows.getJSONObject(i);
            if (flowId.equals(flow.getString("id"))) {
                matches++; assertEquals(source, flow.getString("source"));
            }
        }
        assertEquals("same persisted flow ID occurs exactly once", 1, matches);
    }

    private static boolean awaitDocumentsUi() {
        long deadline = SystemClock.uptimeMillis() + 10000;
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        AccessibilityServiceInfo originalInfo = automation.getServiceInfo();
        if (originalInfo == null) return false;
        int originalFlags = originalInfo.flags;
        try {
            originalInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(originalInfo);
            while (SystemClock.uptimeMillis() < deadline) {
                for (AccessibilityWindowInfo window : automation.getWindows()) {
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    CharSequence pkg = root.getPackageName();
                    boolean documentsUi = pkg != null
                            && "com.google.android.documentsui".contentEquals(pkg);
                    boolean foreground = window.isActive() || window.isFocused();
                    root.recycle();
                    if (documentsUi && foreground) return true;
                }
                SystemClock.sleep(75);
            }
            return false;
        } finally {
            AccessibilityServiceInfo restoreInfo = automation.getServiceInfo();
            if (restoreInfo != null) {
                restoreInfo.flags = originalFlags;
                automation.setServiceInfo(restoreInfo);
            }
        }
    }

    private static void awaitPendingCleared(ActivityScenario<MainActivity> scenario) throws Exception {
        await(() -> {
            AtomicBoolean cleared = new AtomicBoolean();
            scenario.onActivity(activity -> {
                try {
                    Field pending = MainActivity.class.getDeclaredField("pendingContentOutlineMarkdown");
                    pending.setAccessible(true);
                    cleared.set(pending.get(activity) == null);
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            return cleared.get();
        }, "picker cancellation did not clear pending outline data");
    }

    private interface Condition { boolean ready() throws Exception; }
    private static void await(Condition condition, String failure) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.ready()) return;
            SystemClock.sleep(50);
        }
        fail(failure);
    }

    private static int occurrences(String text, String needle) {
        int count = 0, cursor = 0;
        while ((cursor = text.indexOf(needle, cursor)) >= 0) { count++; cursor += needle.length(); }
        return count;
    }
}
