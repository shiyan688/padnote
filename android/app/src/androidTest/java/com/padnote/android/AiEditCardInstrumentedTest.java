package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Device checks for the real AI edit result card in {@link MainActivity}. */
@RunWith(AndroidJUnit4.class)
public final class AiEditCardInstrumentedTest {
    private static final String CARD_STATUS = "AI 修改状态";
    private static final String CARD_ACTION = "撤销或重新应用本次 AI 修改";
    private static final String CARD_LOCATE = "定位本次 AI 修改结果";
    private static final String AI_TEXT = "AI 生成的本地夹具：F = ma";
    private static final String USER_TEXT = "用户已经改写：F = m · a";

    @Test
    public void cardAndToolbarUndoRedoStaySynchronized() throws Exception {
        try (EditorSession session = EditorSession.open()) {
            AtomicReference<NoteCanvasView.AiEditRecord> record = new AtomicReference<>();
            session.scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = resetFixture(activity, session.note.id,
                            session.note.title);
                    record.set(installLocalAiEdit(activity, canvas, session.note.id,
                            "fixture-card-history"));
                    assertEquals(NoteCanvasView.AiEditState.APPLIED,
                            canvas.aiEditState(record.get()));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });

            assertCard("本次 AI 修改已写入笔记 · 1处文字修改", "撤销本次 AI 修改", true);
            assertLocateEnabled(true);
            onView(withContentDescription(CARD_LOCATE)).perform(scrollTo(), click());
            onView(withText("已定位本次 AI 修改")).check(matches(isDisplayed()));
            takeFixtureScreenshot();

            onView(withContentDescription(CARD_ACTION)).perform(scrollTo(), click());
            assertCard("本次 AI 修改已撤销", "重新应用本次 AI 修改", true);
            assertLocateEnabled(false);
            assertFlowCount(session, 0);

            onView(withContentDescription("撤销")).perform(scrollTo(), click());
            assertCard("本次 AI 修改已写入笔记 · 1处文字修改", "撤销本次 AI 修改", true);
            assertLocateEnabled(true);
            assertFlowCount(session, 1);

            onView(withContentDescription("重做")).perform(scrollTo(), click());
            assertCard("本次 AI 修改已撤销", "重新应用本次 AI 修改", true);
            assertLocateEnabled(false);
            assertFlowCount(session, 0);

            onView(withContentDescription(CARD_ACTION)).perform(scrollTo(), click());
            assertCard("本次 AI 修改已写入笔记 · 1处文字修改", "撤销本次 AI 修改", true);
            assertLocateEnabled(true);
            assertFlowCount(session, 1);
        }
    }

    @Test
    public void userEditedTargetShowsConflictAndCardCannotOverwriteIt() throws Exception {
        try (EditorSession session = EditorSession.open()) {
            AtomicReference<String> flowId = new AtomicReference<>();
            session.scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = resetFixture(activity, session.note.id,
                            session.note.title);
                    NoteCanvasView.AiEditRecord record = installLocalAiEdit(
                            activity, canvas, session.note.id, "fixture-card-conflict");
                    List<TextFlow> flows = canvas.getTextFlows();
                    assertEquals(1, flows.size());
                    flowId.set(flows.get(0).id);
                    NoteTextBox fragment = firstFragment(canvas, flowId.get());
                    assertNotNull(fragment);
                    canvas.updateTextBox(fragment.id, NoteTextBox.Format.MARKDOWN, USER_TEXT);
                    assertEquals(NoteCanvasView.AiEditState.CONFLICT,
                            canvas.aiEditState(record));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });

            assertCard("相关文字已被编辑或删除 · 已保护当前内容", "无法安全覆盖", false);
            assertLocateEnabled(true);
            session.scenario.onActivity(activity -> {
                TextFlow live = flow(activity.canvasForTest(), flowId.get());
                assertNotNull(live);
                assertEquals(USER_TEXT, live.source);
            });
        }
    }

    private static NoteCanvasView resetFixture(MainActivity activity, String noteId,
                                               String title) throws Exception {
        NoteCanvasView canvas = activity.canvasForTest();
        assertNotNull(canvas);
        canvas.loadJsonDocument(new JSONObject()
                .put("schemaVersion", 8)
                .put("id", noteId)
                .put("title", title)
                .put("pageWidth", 736)
                .put("pageHeight", 1040)
                .put("pageGap", 24)
                .put("pageCount", 1)
                .put("strokes", new JSONArray())
                .put("images", new JSONArray())
                .put("textFlows", new JSONArray())
                .put("textBoxes", new JSONArray()));
        return canvas;
    }

    private static NoteCanvasView.AiEditRecord installLocalAiEdit(
            MainActivity activity, NoteCanvasView canvas, String noteId,
            String ownerId) throws Exception {
        NoteCanvasView.AiEditRecord record = canvas.newAiEditRecord(ownerId);
        NoteCanvasView.AiEditSnapshot before = canvas.captureAiEditSnapshot();
        canvas.beginAiUndoTransaction(ownerId);
        boolean changed = false;
        try {
            JSONObject report = canvas.createToolContext(null).createTextFlow(
                    AI_TEXT, NoteTextBox.Format.MARKDOWN,
                    new PlacementResolver.Placement(0, 48, 96, 520,
                            "原生测试夹具位置"));
            assertNotNull(report);
            assertTrue(report.optString("flowId").startsWith("flow-ai-"));
        } finally {
            changed = canvas.recordAiEditDelta(record, before);
            canvas.endAiUndoTransaction(ownerId, changed);
        }
        assertTrue(changed);
        activity.installAiEditRecordForTest(noteId, record);
        return record;
    }

    private static void assertCard(String status, String action, boolean enabled) {
        onView(allOf(withContentDescription(CARD_STATUS), withText(status)))
                .perform(scrollTo()).check(matches(isDisplayed()));
        onView(allOf(withContentDescription(CARD_ACTION), withText(action)))
                .perform(scrollTo())
                .check(matches(enabled
                        ? allOf(isDisplayed(), isEnabled())
                        : allOf(isDisplayed(), not(isEnabled()))));
    }

    private static void assertLocateEnabled(boolean enabled) {
        onView(allOf(withContentDescription(CARD_LOCATE), withText("定位结果")))
                .perform(scrollTo())
                .check(matches(enabled
                        ? allOf(isDisplayed(), isEnabled())
                        : allOf(isDisplayed(), not(isEnabled()))));
    }

    private static void assertFlowCount(EditorSession session, int expected) {
        session.scenario.onActivity(activity ->
                assertEquals(expected, activity.canvasForTest().getTextFlows().size()));
    }

    private static NoteTextBox firstFragment(NoteCanvasView canvas, String flowId) {
        for (NoteTextBox fragment : canvas.getTextBoxes()) {
            if (flowId.equals(fragment.flowId)) return fragment;
        }
        return null;
    }

    private static TextFlow flow(NoteCanvasView canvas, String flowId) {
        for (TextFlow flow : canvas.getTextFlows()) {
            if (flowId.equals(flow.id)) return flow;
        }
        return null;
    }

    private static void takeFixtureScreenshot() throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File output = new File(context.getCacheDir(), "ai-edit-card.png");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            stream.flush();
        } finally {
            bitmap.recycle();
        }
        assertTrue(output.isFile() && output.length() > 0);
    }

    private interface Condition { boolean met() throws Exception; }

    private static void await(Condition condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.met()) return;
            } catch (Throwable failure) {
                last = failure;
            }
            Thread.sleep(50);
        }
        AssertionError timeout = new AssertionError(message);
        if (last != null) timeout.initCause(last);
        throw timeout;
    }

    private static void clickNoteWhenVisible(String title) throws Exception {
        String description = "打开笔记 " + title + "。长按可重命名或更换封面。";
        await(() -> {
            try {
                onView(withContentDescription(description)).perform(scrollTo(), click());
                return true;
            } catch (RuntimeException | AssertionError unavailable) {
                return false;
            }
        }, "书架没有显示测试笔记");
    }

    private static final class EditorSession implements AutoCloseable {
        final Context context;
        final NoteStore.Entry note;
        final ActivityScenario<MainActivity> scenario;

        private EditorSession(Context context, NoteStore.Entry note,
                              ActivityScenario<MainActivity> scenario) {
            this.context = context;
            this.note = note;
            this.scenario = scenario;
        }

        static EditorSession open() throws Exception {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            NoteStore.Entry note = NoteStore.create(context,
                    "AI 结果卡原生夹具 " + UUID.randomUUID());
            ActivityScenario<MainActivity> scenario = null;
            try {
                scenario = ActivityScenario.launch(MainActivity.class);
                clickNoteWhenVisible(note.title);
                ActivityScenario<MainActivity> launched = scenario;
                await(() -> {
                    AtomicBoolean ready = new AtomicBoolean();
                    launched.onActivity(activity -> ready.set(
                            activity.canvasForTest() != null &&
                                    activity.canvasForTest().isEnabled()));
                    return ready.get();
                }, "测试笔记编辑器没有完成本地恢复");
                return new EditorSession(context, note, scenario);
            } catch (Exception | Error failure) {
                if (scenario != null) scenario.close();
                try { NoteStore.delete(context, note.id); } catch (Exception ignored) { }
                throw failure;
            }
        }

        @Override public void close() throws Exception {
            scenario.close();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            // Activity shutdown permits an already queued save to finish. Delete
            // only after it has drained so the fixture cannot be recreated later.
            Thread.sleep(750);
            NoteStore.delete(context, note.id);
        }
    }
}
