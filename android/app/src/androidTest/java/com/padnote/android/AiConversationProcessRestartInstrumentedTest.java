package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Process;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in two-process fixture for real on-disk AI conversation recovery.
 *
 * <p>The ordinary suite excludes this class. Run the seed method with phase=seed, force-stop
 * the target package, then run the verify method with phase=verify. A failed verify deliberately
 * keeps the note, profile and manifest for diagnosis; phase=cleanup removes that retained fixture.
 */
@RunWith(AndroidJUnit4.class)
public final class AiConversationProcessRestartInstrumentedTest {
    private static final String ARG_PHASE = "padnoteConversationPhase";
    private static final String MANIFEST = "ai-conversation-process-restart-fixture.json";
    private static final String MARKER = "PROCESS_RESTART_WIRE_731";
    private static final String EXECUTOR = "回答模型 · 进程重启夹具";

    @Test
    public void seedConversationForProcessRestart() throws Exception {
        requirePhase("seed");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File manifest = manifestFile(context);
        assertFalse("已有进程重启夹具；请先运行 cleanup phase，避免覆盖诊断现场",
                manifest.exists());

        AiConfigStore profiles = new AiConfigStore(context);
        String originalActive = profiles.activeProfileId();
        SharedPreferences appPreferences =
                context.getSharedPreferences("padnote-tools", Context.MODE_PRIVATE);
        boolean originalInlinePresent = appPreferences.contains("aiOutputInline");
        boolean originalInline = appPreferences.getBoolean("aiOutputInline", true);
        AiConfigStore.Profile profile = new AiConfigStore.Profile();
        profile.id = "process-restart-" + UUID.randomUUID();
        profile.name = "进程重启夹具";
        profile.directEndpoint = "https://fixture.invalid/v1";
        profile.directModel = "fixture-model";
        profiles.saveProfile(profile, null, null, null);
        profiles.setActiveProfileId(profile.id);
        assertEquals(profile.id, profiles.activeProfileId());

        NoteStore.Entry note = null;
        boolean manifestCommitted = false;
        try {
            note = NoteStore.create(context, "AI 进程重启夹具 " + UUID.randomUUID());
            SeedEvidence evidence;
            try (ActivityScenario<MainActivity> scenario =
                         ActivityScenario.launch(MainActivity.class)) {
                clickNoteWhenVisible(note.title);
                awaitEditorReady(scenario);
                NoteCanvasView.AiSelectionSnapshot selection = selectionFixture();
                scenario.onActivity(activity -> activity.startAiConversationForTest(selection));
                awaitConversationReady(scenario);
                final SeedEvidence[] holder = new SeedEvidence[1];
                scenario.onActivity(activity -> {
                    try {
                        activity.setAiOutputInline(true, true);
                        assertTrue(readBoolean(activity, "aiOutputInline"));
                        NoteCanvasView canvas = activity.canvasForTest();
                        NoteCanvasView.AiEditRecord record =
                                canvas.newAiEditRecord("process-restart-owner");
                        NoteTool.Result result = activity.applyAiToolForTest(
                                "write_text", writeArguments("进程重启后仍可撤销的 AI 文字"), record);
                        assertTrue(result.summary, result.ok);
                        assertTrue(result.mutatedDocument);
                        assertTrue(record.hasChanges());
                        activity.addCompletedAiTurnForTest(
                                "进程重启问题 " + MARKER,
                                "进程重启回答 " + MARKER, EXECUTOR, false);
                        activity.addCompletedAiEditForTest(record, EXECUTOR);
                        activity.awaitAiConversationStorageForTest();
                        AiConversationStore.Snapshot snapshot = activity.conversationForTest();
                        assertNotNull(snapshot);
                        assertTrue("结果凭据未进入持久时间线", hasPersistedReceipt(snapshot));
                        assertWireContains(activity.nextWireHistoryForTest(), MARKER);
                        holder[0] = new SeedEvidence(record.changedFlowIds().get(0));
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
                evidence = holder[0];
                assertNotNull(evidence);
                onView(withContentDescription("立即保存")).perform(scrollTo(), click());
                awaitDocumentSaved(scenario);
            }

            JSONObject value = new JSONObject()
                    .put("schema", 1)
                    .put("note_id", note.id)
                    .put("note_title", note.title)
                    .put("profile_id", profile.id)
                    .put("original_active_profile_id",
                            originalActive == null ? "" : originalActive)
                    .put("original_inline_present", originalInlinePresent)
                    .put("original_inline", originalInline)
                    .put("marker", MARKER)
                    .put("flow_id", evidence.flowId)
                    .put("seed_pid", Process.myPid());
            writeManifest(manifest, value);
            manifestCommitted = true;
            System.out.println("PADNOTE_PROCESS_RESTART_SEED pid=" + Process.myPid()
                    + " note=" + note.id);
        } finally {
            if (!manifestCommitted) {
                if (note != null) {
                    try { NoteStore.delete(context, note.id); } catch (Exception ignored) { }
                    try { new AiConversationStore(context).clear(note.id); }
                    catch (Exception ignored) { }
                }
                profiles.deleteProfile(profile.id);
                profiles.setActiveProfileId(originalActive == null ? "" : originalActive);
                restoreInlinePreference(appPreferences,
                        originalInlinePresent, originalInline);
            }
        }
    }

    @Test
    public void verifyConversationAfterProcessRestart() throws Exception {
        requirePhase("verify");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject fixture = readRequiredManifest(context);
        assertNotEquals("verify 必须运行在 force-stop 后的新进程",
                fixture.getInt("seed_pid"), Process.myPid());
        assertEquals(MARKER, fixture.getString("marker"));
        assertEquals("seed使用的接收者配置未跨进程保留",
                fixture.getString("profile_id"), new AiConfigStore(context).activeProfileId());

        String noteTitle = fixture.getString("note_title");
        String flowId = fixture.getString("flow_id");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            clickNoteWhenVisible(noteTitle);
            awaitEditorReady(scenario);
            awaitConversationReady(scenario);
            scenario.onActivity(activity -> {
                assertWireContains(activity.nextWireHistoryForTest(), MARKER);
                assertTrue("冷恢复会话缺少结果凭据",
                        hasPersistedReceipt(activity.conversationForTest()));
                assertNotNull(findFlow(activity.canvasForTest(), flowId));
            });

            onView(withContentDescription("打开 AI 对话卡")).perform(scrollTo(), click());
            onView(withText("进程重启回答 " + MARKER))
                    .perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText(EXECUTOR)).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withContentDescription("AI 修改状态"))
                    .perform(scrollTo()).check(matches(isDisplayed()));
            onView(withContentDescription("撤销或重新应用本次 AI 修改"))
                    .perform(scrollTo()).check(matches(isDisplayed())).check(matches(isEnabled()))
                    .perform(click());
            scenario.onActivity(activity ->
                    assertTrue(findFlow(activity.canvasForTest(), flowId) == null));
            onView(withText("重新应用本次 AI 修改"))
                    .perform(scrollTo()).check(matches(isEnabled())).perform(click());
            scenario.onActivity(activity ->
                    assertNotNull(findFlow(activity.canvasForTest(), flowId)));
            onView(withContentDescription("AI 修改状态"))
                    .perform(scrollTo()).check(matches(isDisplayed()));
            saveScreenshot(context);
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Thread.sleep(750);

        System.out.println("PADNOTE_PROCESS_RESTART_VERIFY seedPid="
                + fixture.getInt("seed_pid") + " verifyPid=" + Process.myPid());
        writeResult(context, fixture);
        cleanupFixture(context, fixture);
    }

    @Test
    public void cleanupPreservedProcessRestartFixture() throws Exception {
        requirePhase("cleanup");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        cleanupFixture(context, readRequiredManifest(context));
    }

    private static void requirePhase(String expected) {
        String actual = InstrumentationRegistry.getArguments().getString(ARG_PHASE, "");
        Assume.assumeTrue("仅在显式两阶段进程重启验收中运行", !actual.isEmpty());
        assertEquals("必须显式传入 -e " + ARG_PHASE + " " + expected, expected, actual);
    }

    private static void cleanupFixture(Context context, JSONObject fixture) throws Exception {
        String noteId = fixture.getString("note_id");
        String profileId = fixture.getString("profile_id");
        String originalActive = fixture.optString("original_active_profile_id", "");
        NoteStore.delete(context, noteId);
        new AiConversationStore(context).clear(noteId);
        AiConfigStore profiles = new AiConfigStore(context);
        profiles.deleteProfile(profileId);
        profiles.setActiveProfileId(originalActive);
        restoreInlinePreference(context.getSharedPreferences(
                        "padnote-tools", Context.MODE_PRIVATE),
                fixture.optBoolean("original_inline_present", false),
                fixture.optBoolean("original_inline", true));
        assertTrue("无法删除进程重启夹具manifest", manifestFile(context).delete());
    }

    private static void restoreInlinePreference(SharedPreferences preferences,
                                                boolean wasPresent, boolean value) {
        SharedPreferences.Editor editor = preferences.edit();
        if (wasPresent) editor.putBoolean("aiOutputInline", value);
        else editor.remove("aiOutputInline");
        assertTrue("无法恢复原AI写入权限偏好", editor.commit());
    }

    private static JSONObject readRequiredManifest(Context context) throws Exception {
        File file = manifestFile(context);
        assertTrue("缺少seed manifest；请先成功运行seed phase", file.isFile());
        long length = file.length();
        assertTrue("seed manifest异常过大", length > 0 && length <= 16 * 1024);
        byte[] bytes = new byte[(int) length];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) fail("seed manifest读取不完整");
                offset += count;
            }
            assertEquals("seed manifest读取后仍有额外字节", -1, input.read());
        }
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static File manifestFile(Context context) {
        return new File(context.getFilesDir(), MANIFEST);
    }

    private static void writeManifest(File target, JSONObject value) throws Exception {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("无法替换旧进程重启验收结果");
        }
        if (!temporary.renameTo(target)) {
            throw new IllegalStateException("无法提交进程重启夹具manifest");
        }
    }

    private static void writeResult(Context context, JSONObject fixture) throws Exception {
        JSONObject result = new JSONObject()
                .put("seed_pid", fixture.getInt("seed_pid"))
                .put("verify_pid", Process.myPid())
                .put("same_pid", fixture.getInt("seed_pid") == Process.myPid())
                .put("note_id", fixture.getString("note_id"))
                .put("result", "passed");
        writeManifest(new File(context.getCacheDir(),
                "ai-conversation-process-restart-result.json"), result);
    }

    private static void saveScreenshot(Context context) throws Exception {
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().takeScreenshot();
        assertNotNull("无法截取冷恢复结果卡", screenshot);
        File target = new File(context.getCacheDir(), "ai-conversation-cold-restore.png");
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
            output.getFD().sync();
        } finally {
            screenshot.recycle();
        }
        assertTrue(target.isFile() && target.length() > 0);
    }

    private static NoteCanvasView.AiSelectionSnapshot selectionFixture() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFFFFFFFF);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        return new NoteCanvasView.AiSelectionSnapshot(bitmap, output.toByteArray(),
                new RectF(40, 60, 180, 160), 1, 2, false);
    }

    private static JSONObject writeArguments(String content) throws Exception {
        return new JSONObject().put("content", content)
                .put("format", "markdown")
                .put("placement", new JSONObject()
                        .put("relativeTo", "selection")
                        .put("position", "below"));
    }

    private static TextFlow findFlow(NoteCanvasView canvas, String flowId) {
        for (TextFlow flow : canvas.getTextFlows()) {
            if (flowId.equals(flow.id)) return flow;
        }
        return null;
    }

    private static boolean hasPersistedReceipt(AiConversationStore.Snapshot snapshot) {
        if (snapshot == null) return false;
        for (AiConversationStore.VisibleEntry entry : snapshot.visibleTimeline) {
            if ("result".equals(entry.kind) && !entry.receiptJson.isEmpty()
                    && !entry.receiptDigest.isEmpty() && !entry.receiptObjectIds.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void assertWireContains(List<OpenAiCompatibleClient.Message> messages,
                                           String marker) {
        try {
            JSONObject request = OpenAiCompatibleClient.buildRequest(
                    "fixture-model", null, new ArrayList<>(messages),
                    new JSONArray(), new JSONObject());
            assertTrue("恢复后的实际下一请求JSON缺少旧wire", request.toString().contains(marker));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static boolean readBoolean(MainActivity activity, String name) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(activity);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void awaitConversationReady(ActivityScenario<MainActivity> scenario)
            throws Exception {
        await(() -> {
            AtomicBoolean ready = new AtomicBoolean();
            scenario.onActivity(activity -> ready.set(!activity.aiConversationLoadingForTest()
                    && activity.conversationForTest() != null));
            return ready.get();
        }, "进程重启后AI会话没有从磁盘恢复");
    }

    private static void awaitEditorReady(ActivityScenario<MainActivity> scenario)
            throws Exception {
        await(() -> {
            AtomicBoolean ready = new AtomicBoolean();
            scenario.onActivity(activity -> ready.set(activity.canvasForTest() != null
                    && activity.canvasForTest().isEnabled()));
            return ready.get();
        }, "进程重启夹具笔记编辑器没有完成恢复");
    }

    private static void awaitDocumentSaved(ActivityScenario<MainActivity> scenario)
            throws Exception {
        await(() -> {
            AtomicBoolean saved = new AtomicBoolean();
            scenario.onActivity(activity -> {
                try {
                    Field current = MainActivity.class.getDeclaredField("documentRevision");
                    Field persisted = MainActivity.class.getDeclaredField("lastSavedRevision");
                    Field inFlight = MainActivity.class.getDeclaredField("saveInFlight");
                    current.setAccessible(true);
                    persisted.setAccessible(true);
                    inFlight.setAccessible(true);
                    saved.set(!inFlight.getBoolean(activity)
                            && persisted.getLong(activity) == current.getLong(activity));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
            return saved.get();
        }, "seed笔记没有完成本地保存");
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
        }, "书架没有显示进程重启夹具笔记");
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

    private static final class SeedEvidence {
        final String flowId;
        SeedEvidence(String flowId) { this.flowId = flowId; }
    }
}
