package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.hamcrest.Matchers.not;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

/** Device regressions for the real per-note AI conversation lifecycle. */
@RunWith(AndroidJUnit4.class)
public final class AiConversationInstrumentedTest {
    private static final String OLD_MARKER = "OLD_VISIBLE_AND_WIRE_731";

    @Test
    public void storeColdReopenIsPerNoteAndClearRejectsLateSave() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getCacheDir(), "ai-conversation-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
        String noteA = "note-a-" + UUID.randomUUID();
        String noteB = "note-b-" + UUID.randomUUID();
        try {
            AiConversationStore first = new AiConversationStore(root, NoteStore.NO_WRITE_FAULTS);
            AiConversationStore.Snapshot staleA = first.create(noteA,
                    Collections.singletonList(visible("A visible " + OLD_MARKER, "model-a")),
                    Collections.singletonList(new OpenAiCompatibleClient.Message(
                            "user", "A wire " + OLD_MARKER)),
                    null, null, binding("profile-a", 1), null, false);
            first.create(noteB,
                    Collections.singletonList(visible("B visible", "model-b")),
                    Collections.singletonList(new OpenAiCompatibleClient.Message(
                            "user", "B wire")),
                    null, null, binding("profile-b", 3), null, false);

            AiConversationStore reopened = new AiConversationStore(
                    root, NoteStore.NO_WRITE_FAULTS);
            assertEquals("A visible " + OLD_MARKER,
                    reopened.load(noteA).visibleTimeline.get(0).text);
            assertEquals("B visible", reopened.load(noteB).visibleTimeline.get(0).text);

            reopened.clear(noteA);
            assertNull(new AiConversationStore(root, NoteStore.NO_WRITE_FAULTS).load(noteA));
            assertNotNull(new AiConversationStore(root, NoteStore.NO_WRITE_FAULTS).load(noteB));

            AiConversationStore.Snapshot late = staleA.next(
                    Arrays.asList(visible("A visible " + OLD_MARKER, "model-a"),
                            visible("late result", "model-a")),
                    staleA.wireHistory, staleA.selection, staleA.vault,
                    staleA.binding, staleA.transcript, staleA.uploadConfirmed);
            try {
                reopened.save(late);
                fail("clear tombstone accepted a late conversation save");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("清空"));
            }
            assertNull(new AiConversationStore(root, NoteStore.NO_WRITE_FAULTS).load(noteA));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void selectionPngColdReopensAndOversizedHeaderIsRejectedBeforeFullDecode()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getCacheDir(), "ai-selection-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888);
        byte[] png;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            png = output.toByteArray();
        } finally {
            bitmap.recycle();
        }
        try {
            String noteId = "note-selection-" + UUID.randomUUID();
            AiConversationStore store = new AiConversationStore(root, NoteStore.NO_WRITE_FAULTS);
            store.create(noteId, Collections.singletonList(visible("selection", "model")),
                    Collections.emptyList(), new AiConversationStore.Selection(
                            png, new RectF(1, 2, 30, 20), 1, 2, true),
                    null, binding("profile", 1), null, false);

            AiConversationStore.Snapshot cold = new AiConversationStore(
                    root, NoteStore.NO_WRITE_FAULTS).load(noteId);
            assertNotNull(cold);
            assertNotNull(cold.selection);
            NoteCanvasView.AiSelectionSnapshot decoded = cold.selection.toCanvas();
            try {
                assertEquals(3, decoded.bitmap.getWidth());
                assertEquals(2, decoded.bitmap.getHeight());
                assertEquals(new RectF(1, 2, 30, 20), decoded.sourceBounds);
            } finally {
                decoded.bitmap.recycle();
            }

            assertOversizedPngHeaderRejected(png, 3000, 1);
            assertOversizedPngHeaderRejected(png, 2048, 2048);
            assertOversizedPngHeaderRejected(png, 2048, 2049);
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void mainKeepsEachNoteSeparateAndResumesAfterCollapseAndActivityRecreate()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        NoteStore.Entry other = NoteStore.create(context,
                "AI 会话隔离夹具 B " + UUID.randomUUID());
        try (ProfileFixture profile = ProfileFixture.install(context);
             EditorSession session = EditorSession.open("AI 会话生命周期夹具 A")) {
            session.scenario.onActivity(activity -> selectFixtureStroke(activity.canvasForTest()));
            onView(withContentDescription("打开 AI 对话卡")).perform(scrollTo(), click());
            awaitConversationReady(session.scenario);
            session.scenario.onActivity(activity -> {
                activity.addCompletedAiTurnForTest(
                        "用户问题 " + OLD_MARKER, "模型回答 " + OLD_MARKER,
                        "回答模型 · 固定执行者 A", false);
                activity.awaitAiConversationStorageForTest();
                assertWireContains(activity.nextWireHistoryForTest(), OLD_MARKER);
                activity.collapseAiCardForTest();
                assertEquals(1, activity.canvasForTest().getSelectionCount());
            });

            // A still-selected lasso must offer to continue the frozen old material.
            onView(withContentDescription("打开 AI 对话卡")).perform(scrollTo(), click());
            onView(withText("继续旧材料")).inRoot(isDialog()).perform(click());
            assertVisibleTurnAndWire(session.scenario, OLD_MARKER);

            // Clearing the live canvas selection also resumes the saved conversation.
            session.scenario.onActivity(activity -> {
                activity.collapseAiCardForTest();
                activity.canvasForTest().setTool(NoteCanvasView.Tool.PEN);
                assertEquals(0, activity.canvasForTest().getSelectionCount());
            });
            onView(withContentDescription("打开 AI 对话卡")).perform(scrollTo(), click());
            assertVisibleTurnAndWire(session.scenario, OLD_MARKER);

            // A second note must not inherit A's conversation.
            session.scenario.onActivity(MainActivity::collapseAiCardForTest);
            onView(withContentDescription("返回书架")).perform(click());
            clickNoteWhenVisible(other.title);
            awaitEditorReady(session.scenario);
            await(() -> {
                AtomicBoolean isolated = new AtomicBoolean();
                session.scenario.onActivity(activity -> isolated.set(
                        !activity.aiConversationLoadingForTest()
                                && activity.conversationForTest() == null));
                return isolated.get();
            }, "第二本笔记继承了第一本的 AI 会话");

            onView(withContentDescription("返回书架")).perform(click());
            clickNoteWhenVisible(session.note.title);
            awaitEditorReady(session.scenario);
            awaitConversationReady(session.scenario);

            // ActivityScenario recreation is an Activity rebuild, then the real shelf path
            // reopens the same UUID note and restores both timeline and wire context.
            session.scenario.recreate();
            clickNoteWhenVisible(session.note.title);
            awaitEditorReady(session.scenario);
            awaitConversationReady(session.scenario);
            onView(withContentDescription("打开 AI 对话卡")).perform(scrollTo(), click());
            onView(withText("模型回答 " + OLD_MARKER))
                    .perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("回答模型 · 固定执行者 A"))
                    .perform(scrollTo()).check(matches(isDisplayed()));
            assertVisibleTurnAndWire(session.scenario, OLD_MARKER);

            session.scenario.onActivity(activity -> {
                activity.clearAiConversationForTest();
                activity.awaitAiConversationStorageForTest();
                assertNull(activity.conversationForTest());
                assertTrue(activity.nextWireHistoryForTest().isEmpty());
            });
            session.scenario.recreate();
            clickNoteWhenVisible(session.note.title);
            awaitEditorReady(session.scenario);
            await(() -> {
                AtomicBoolean cleared = new AtomicBoolean();
                session.scenario.onActivity(activity -> cleared.set(
                        !activity.aiConversationLoadingForTest()
                                && activity.conversationForTest() == null));
                return cleared.get();
            }, "明确清空后 Activity 重建恢复了旧会话");
        } finally {
            try { NoteStore.delete(context, other.id); } catch (Exception ignored) { }
            try { new AiConversationStore(context).clear(other.id); } catch (Exception ignored) { }
        }
    }

    @Test
    public void collapseDuringFirstCreateClearsLoadingAndCanReloadPersistedConversation()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ProfileFixture profile = ProfileFixture.install(context);
             EditorSession session = EditorSession.open("AI 首建竞态夹具")) {
            session.scenario.onActivity(activity -> {
                activity.startAiConversationForTest(selectionFixture());
                assertTrue(activity.aiConversationLoadingForTest());
                activity.collapseAiCardForTest();
                activity.awaitAiConversationStorageForTest();
            });
            await(() -> {
                AtomicBoolean settled = new AtomicBoolean();
                session.scenario.onActivity(activity ->
                        settled.set(!activity.aiConversationLoadingForTest()));
                return settled.get();
            }, "首次会话创建完成后仍永久处于载入状态");
            session.scenario.onActivity(activity -> {
                activity.reloadAiConversationForTest();
                activity.awaitAiConversationStorageForTest();
            });
            awaitConversationReady(session.scenario);

            // Explicit clear is different from collapse: if it races the first create,
            // neither its late callback nor a disk reload may resurrect the session.
            session.scenario.onActivity(activity -> {
                activity.clearAiConversationForTest();
                activity.awaitAiConversationStorageForTest();
                activity.startAiConversationForTest(selectionFixture());
                assertTrue(activity.aiConversationLoadingForTest());
                activity.clearAiConversationForTest();
                activity.awaitAiConversationStorageForTest();
            });
            await(() -> {
                AtomicBoolean cleared = new AtomicBoolean();
                session.scenario.onActivity(activity -> cleared.set(
                        !activity.aiConversationLoadingForTest()
                                && activity.conversationForTest() == null
                                && activity.nextWireHistoryForTest().isEmpty()));
                return cleared.get();
            }, "首次会话创建的晚回调复活了已清空会话");
            assertNull(new AiConversationStore(context).load(session.note.id));
            session.scenario.onActivity(activity -> {
                activity.reloadAiConversationForTest();
                activity.awaitAiConversationStorageForTest();
            });
            await(() -> {
                AtomicBoolean absent = new AtomicBoolean();
                session.scenario.onActivity(activity -> absent.set(
                        !activity.aiConversationLoadingForTest()
                                && activity.conversationForTest() == null));
                return absent.get();
            }, "明确清空的首次会话从磁盘重新出现");
        }
    }

    @Test
    public void coldOrdinaryAnswerCanBeAdoptedOnceAfterSourceAndPermissionRecheck()
            throws Exception {
        final String marker = "COLD_ADOPT_ONCE_" + UUID.randomUUID();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ProfileFixture profile = ProfileFixture.install(context);
             EditorSession session = EditorSession.open("AI 普通回答采用夹具")) {
            startFixtureConversation(session.scenario);
            session.scenario.onActivity(activity -> {
                activity.setAiOutputInline(true, false);
                assertTrue(activity.addAdoptableAnswerForTest(
                        "## 已确认回答\n\n" + marker, "回答模型 · 普通文字"));
                activity.awaitAiConversationStorageForTest();
            });

            session.scenario.recreate();
            clickNoteWhenVisible(session.note.title);
            awaitEditorReady(session.scenario);
            awaitConversationReady(session.scenario);
            session.scenario.onActivity(activity -> activity.setAiOutputInline(true, false));
            onView(withContentDescription("打开 AI 对话卡")).perform(scrollTo(), click());
            onView(withContentDescription("将这条回答写入笔记"))
                    .perform(scrollTo()).check(matches(isDisplayed()))
                    .check(matches(isEnabled())).perform(click());
            awaitDocumentSaved(session.scenario);
            session.scenario.onActivity(activity -> assertEquals(1,
                    flowSourceOccurrences(activity.canvasForTest(), marker)));

            // The answer insertion may be durable before conversation metadata. Its
            // frozen pre-write source digest must still make a cold second adoption
            // unavailable, so a crash window cannot duplicate the same answer.
            session.scenario.recreate();
            clickNoteWhenVisible(session.note.title);
            awaitEditorReady(session.scenario);
            awaitConversationReady(session.scenario);
            session.scenario.onActivity(activity -> activity.setAiOutputInline(true, false));
            onView(withContentDescription("打开 AI 对话卡")).perform(scrollTo(), click());
            onView(withText("来源已变化，不能写入"))
                    .perform(scrollTo()).check(matches(isDisplayed()))
                    .check(matches(not(isEnabled())));
            session.scenario.onActivity(activity -> assertEquals(1,
                    flowSourceOccurrences(activity.canvasForTest(), marker)));
        }
    }

    @Test
    public void contextBoundariesKeepVisibleHistoryButNeverReuseOldWireOrCapability()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ProfileFixture profile = ProfileFixture.install(context);
             EditorSession session = EditorSession.open("AI 上下文边界夹具")) {
            startFixtureConversation(session.scenario);
            session.scenario.onActivity(activity -> {
                activity.addCompletedAiTurnForTest("u1 " + OLD_MARKER,
                        "a1 " + OLD_MARKER, "回答模型 · 旧执行者", false);
                assertEquals(AiConfigStore.ToolCapability.UNKNOWN,
                        activity.capabilityForTest());
                activity.rotateAiContextForTest("材料范围已变化");
                assertFalse(wireContains(activity.nextWireHistoryForTest(), OLD_MARKER));
                assertVisibleContains(activity.conversationForTest(), OLD_MARKER);

                activity.addCompletedAiTurnForTest("u2", "纯文本回答",
                        "回答模型 · 纯文本执行者", false);
                OpenAiCompatibleClient.Completion plain = new OpenAiCompatibleClient.Completion(
                        "纯文本回答", "纯文本回答", Collections.emptyList(), false);
                assertFalse(activity.recordToolEvidenceForTest(plain));
                assertEquals(AiConfigStore.ToolCapability.UNKNOWN,
                        activity.capabilityForTest());
                OpenAiCompatibleClient.Completion unknownTool =
                        new OpenAiCompatibleClient.Completion("", "",
                                Collections.singletonList(new OpenAiCompatibleClient.ToolCall(
                                        "call-unknown", "not_registered", new JSONObject())), true);
                assertFalse(activity.recordToolEvidenceForTest(unknownTool));
                assertEquals(AiConfigStore.ToolCapability.UNKNOWN,
                        activity.capabilityForTest());
                OpenAiCompatibleClient.Completion validTool =
                        new OpenAiCompatibleClient.Completion("", "",
                                Collections.singletonList(new OpenAiCompatibleClient.ToolCall(
                                        "call-valid", "read_page_map", new JSONObject())), true);
                assertTrue(activity.recordToolEvidenceForTest(validTool));
                activity.addCompletedAiTurnForTest("u3", "合法工具调用后的回答",
                        "回答模型 · 工具执行者", false);
                assertEquals(AiConfigStore.ToolCapability.CONFIRMED,
                        activity.capabilityForTest());
                activity.awaitAiConversationStorageForTest();
            });

            // Saving the active recipient increments its revision and resets capability;
            // the frozen executor label stays attached to the old visible turn.
            session.scenario.onActivity(activity -> {
                try {
                    AiConfigStore store = new AiConfigStore(activity);
                    AiConfigStore.Profile changed = profile(profile.profile.id, "model-new");
                    changed.name = "已更换配置";
                    assertTrue(activity.saveAiProfile(changed, null, null, null, false));
                    assertEquals(AiConfigStore.ToolCapability.UNKNOWN,
                            activity.capabilityForTest());
                    assertFalse(wireContains(activity.nextWireHistoryForTest(), OLD_MARKER));
                    assertVisibleContains(activity.conversationForTest(), OLD_MARKER);
                    assertEquals("model-new", store.activeProfile().directModel);
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
            onView(withText("回答模型 · 旧执行者"))
                    .perform(scrollTo()).check(matches(isDisplayed()));

            session.scenario.onActivity(activity -> {
                boolean inline = readBoolean(activity, "aiOutputInline");
                activity.addCompletedAiTurnForTest("permission " + OLD_MARKER,
                        "permission answer", "回答模型 · 已冻结", false);
                activity.setAiOutputInline(!inline, false);
                assertFalse(wireContains(activity.nextWireHistoryForTest(), OLD_MARKER));
                assertVisibleContains(activity.conversationForTest(), OLD_MARKER);
            });
        }
    }

    @Test
    public void aiOwnedBaselineCanAdvanceTwiceButExternalCanvasEditBreaksContinuation()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ProfileFixture profile = ProfileFixture.install(context);
             EditorSession session = EditorSession.open("AI 来源基线夹具")) {
            startFixtureConversation(session.scenario);
            session.scenario.onActivity(activity -> {
                try {
                    NoteCanvasView canvas = activity.canvasForTest();
                    if (!readBoolean(activity, "aiOutputInline")) {
                        activity.setAiOutputInline(true, false);
                    }
                    assertTrue(activity.canContinueAiWritesForTest());
                    NoteCanvasView.AiEditRecord first = canvas.newAiEditRecord("own-first");
                    NoteTool.Result firstResult = activity.applyAiToolForTest(
                            "write_text", writeArguments("AI 第一轮提交"), first);
                    assertTrue(firstResult.summary, firstResult.ok);
                    assertTrue(firstResult.mutatedDocument);
                    assertTrue(activity.canContinueAiWritesForTest());
                    NoteCanvasView.AiEditRecord second = canvas.newAiEditRecord("own-second");
                    NoteTool.Result secondResult = activity.applyAiToolForTest(
                            "write_text", writeArguments("AI 第二轮提交"), second);
                    assertTrue(secondResult.summary, secondResult.ok);
                    assertTrue(secondResult.mutatedDocument);
                    activity.addCompletedAiEditForTest(second, "回答模型 · 提交执行者");
                    assertTrue(activity.canContinueAiWritesForTest());
                    activity.awaitAiConversationStorageForTest();
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });

            onView(withContentDescription("立即保存")).perform(scrollTo(), click());
            awaitDocumentSaved(session.scenario);
            session.scenario.recreate();
            clickNoteWhenVisible(session.note.title);
            awaitEditorReady(session.scenario);
            awaitConversationReady(session.scenario);
            onView(withContentDescription("打开 AI 对话卡")).perform(scrollTo(), click());
            onView(withContentDescription("AI 修改状态"))
                    .perform(scrollTo()).check(matches(isDisplayed()));
            onView(withContentDescription("撤销或重新应用本次 AI 修改"))
                    .perform(scrollTo()).check(matches(isDisplayed()))
                    .check(matches(isEnabled()));
            session.scenario.onActivity(activity -> {
                NoteCanvasView canvas = activity.canvasForTest();
                assertTrue(activity.canContinueAiWritesForTest());
                canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                        "用户在 AI 之后编辑", 80, 420);
                assertFalse(activity.canContinueAiWritesForTest());
            });
        }
    }

    @Test
    public void editReceiptSurvivesCanvasJsonReopenAndSelectiveUndoKeepsUnrelatedInk()
            throws Exception {
        try (EditorSession session = EditorSession.open("AI 冷恢复凭据夹具")) {
            session.scenario.onActivity(activity -> {
                NoteCanvasView cold = null;
                try {
                    NoteCanvasView live = activity.canvasForTest();
                    NoteCanvasView.AiEditRecord record = live.newAiEditRecord("cold-receipt");
                    NoteCanvasView.AiEditSnapshot before = live.captureAiEditSnapshot();
                    live.beginAiUndoTransaction(record.ownerId);
                    boolean changed;
                    try {
                        assertNotNull(live.createToolContext(null).createTextFlow(
                                "冷恢复 AI 文字", NoteTextBox.Format.MARKDOWN,
                                new PlacementResolver.Placement(0, 48, 96, 520, "冷恢复")));
                    } finally {
                        changed = live.recordAiEditDelta(record, before);
                        live.endAiUndoTransaction(record.ownerId, changed);
                    }
                    assertTrue(changed);
                    String flowId = record.changedFlowIds().get(0);
                    drawStroke(live, 620, 820, 680, 860);
                    JSONObject receipt = live.serializeAiEditRecord(record);
                    JSONObject saved = live.toJsonDocument(session.note.id, session.note.title);

                    cold = new NoteCanvasView(activity);
                    cold.layout(0, 0, 800, 1100);
                    cold.loadJsonDocument(new JSONObject(saved.toString()));
                    NoteCanvasView.AiEditRecord restored = cold.restoreAiEditRecord(
                            new JSONObject(receipt.toString()));
                    assertNotNull(restored);
                    assertEquals(NoteCanvasView.AiEditState.APPLIED,
                            cold.aiEditState(restored));
                    assertEquals(NoteCanvasView.AiEditState.UNDONE,
                            cold.applyAiEdit(restored, false).state);
                    assertNull(findFlow(cold, flowId));
                    assertEquals(1, cold.getStrokeCount());
                    assertEquals(NoteCanvasView.AiEditState.APPLIED,
                            cold.applyAiEdit(restored, true).state);
                    assertNotNull(findFlow(cold, flowId));
                    assertEquals(1, cold.getStrokeCount());
                } catch (Exception error) {
                    throw new AssertionError(error);
                } finally {
                    if (cold != null) cold.onDetachedFromWindow();
                }
            });
        }
    }

    @Test
    public void coldReceiptCannotReapplyAiCreationAfterUserDeletesItsPage() throws Exception {
        try (EditorSession session = EditorSession.open("AI 冷恢复拓扑夹具")) {
            session.scenario.onActivity(activity -> {
                NoteCanvasView cold = null;
                try {
                    NoteCanvasView live = activity.canvasForTest();
                    live.addPage();
                    NoteCanvasView.AiEditRecord record = live.newAiEditRecord("cold-topology");
                    NoteCanvasView.AiEditSnapshot before = live.captureAiEditSnapshot();
                    live.beginAiUndoTransaction(record.ownerId);
                    boolean changed;
                    try {
                        assertNotNull(live.createToolContext(null).createTextFlow(
                                "只能位于后来删除页面的 AI 文字",
                                NoteTextBox.Format.MARKDOWN,
                                new PlacementResolver.Placement(1, 48, 96, 520, "第二页")));
                    } finally {
                        changed = live.recordAiEditDelta(record, before);
                        live.endAiUndoTransaction(record.ownerId, changed);
                    }
                    assertTrue(changed);
                    String flowId = record.changedFlowIds().get(0);
                    JSONObject receipt = live.serializeAiEditRecord(record);
                    assertEquals(NoteCanvasView.AiEditState.UNDONE,
                            live.applyAiEdit(record, false).state);
                    assertTrue(live.deletePage(1));
                    assertNull(findFlow(live, flowId));
                    JSONObject saved = live.toJsonDocument(session.note.id, session.note.title);

                    cold = new NoteCanvasView(activity);
                    cold.layout(0, 0, 800, 1100);
                    cold.loadJsonDocument(new JSONObject(saved.toString()));
                    NoteCanvasView.AiEditRecord restored = cold.restoreAiEditRecord(
                            new JSONObject(receipt.toString()));
                    assertNull("page topology change must invalidate cold reapply", restored);
                    assertNull(findFlow(cold, flowId));
                } catch (Exception error) {
                    throw new AssertionError(error);
                } finally {
                    if (cold != null) cold.onDetachedFromWindow();
                }
            });
        }
    }

    private static void assertOversizedPngHeaderRejected(byte[] validPng,
                                                          int width, int height) {
        byte[] oversized = validPng.clone();
        writeInt(oversized, 16, width);
        writeInt(oversized, 20, height);
        CRC32 crc = new CRC32();
        crc.update(oversized, 12, 17); // IHDR type plus its 13-byte payload.
        writeInt(oversized, 29, (int) crc.getValue());
        try {
            new AiConversationStore.Selection(oversized, new RectF(1, 1, 2, 2),
                    0, 0, false).toCanvas();
            fail("oversized PNG dimensions were accepted: " + width + "x" + height);
        } catch (Exception expected) {
            assertTrue("oversized header reached full bitmap decode: " + expected.getMessage(),
                    expected.getMessage() != null && expected.getMessage().contains("尺寸无效"));
        }
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static void startFixtureConversation(ActivityScenario<MainActivity> scenario)
            throws Exception {
        scenario.onActivity(activity -> {
            activity.startAiConversationForTest(selectionFixture());
            activity.awaitAiConversationStorageForTest();
        });
        awaitConversationReady(scenario);
    }

    private static NoteCanvasView.AiSelectionSnapshot selectionFixture() {
        Bitmap bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFFFFFFFF);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            return new NoteCanvasView.AiSelectionSnapshot(bitmap, output.toByteArray(),
                    new RectF(40, 60, 180, 160), 1, 2, false);
        } catch (IOException impossible) {
            bitmap.recycle();
            throw new AssertionError(impossible);
        }
    }

    private static void selectFixtureStroke(NoteCanvasView canvas) {
        canvas.setPenOnly(false);
        canvas.setTool(NoteCanvasView.Tool.PEN);
        drawStroke(canvas, 200, 200, 260, 240);
        assertEquals(1, canvas.getStrokeCount());

        canvas.setTool(NoteCanvasView.Tool.LASSO);
        long lasso = SystemClock.uptimeMillis();
        dispatchWorld(canvas, lasso, lasso, MotionEvent.ACTION_DOWN, 150, 150);
        dispatchWorld(canvas, lasso, lasso + 8, MotionEvent.ACTION_MOVE, 320, 150);
        dispatchWorld(canvas, lasso, lasso + 16, MotionEvent.ACTION_MOVE, 320, 300);
        dispatchWorld(canvas, lasso, lasso + 24, MotionEvent.ACTION_MOVE, 150, 300);
        dispatchWorld(canvas, lasso, lasso + 32, MotionEvent.ACTION_UP, 150, 150);
        assertEquals(1, canvas.getSelectionCount());
    }

    private static void drawStroke(NoteCanvasView canvas, float x1, float y1,
                                   float x2, float y2) {
        int before = canvas.getStrokeCount();
        canvas.setPenOnly(false);
        canvas.setTool(NoteCanvasView.Tool.PEN);
        long down = SystemClock.uptimeMillis();
        dispatchWorld(canvas, down, down, MotionEvent.ACTION_DOWN, x1, y1);
        dispatchWorld(canvas, down, down + 8, MotionEvent.ACTION_MOVE, x2, y2);
        dispatchWorld(canvas, down, down + 16, MotionEvent.ACTION_UP, x2, y2);
        assertEquals(before + 1, canvas.getStrokeCount());
    }

    private static void dispatchWorld(NoteCanvasView canvas, long downTime, long eventTime,
                                      int action, float worldX, float worldY) {
        float x = worldX * canvas.getViewportScale() + canvas.getViewportPanX();
        float y = worldY * canvas.getViewportScale() + canvas.getViewportPanY();
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        canvas.onTouchEvent(event);
        event.recycle();
    }

    private static void awaitConversationReady(ActivityScenario<MainActivity> scenario)
            throws Exception {
        await(() -> {
            AtomicBoolean ready = new AtomicBoolean();
            scenario.onActivity(activity -> ready.set(
                    !activity.aiConversationLoadingForTest()
                            && activity.conversationForTest() != null));
            return ready.get();
        }, "AI 会话没有完成创建或恢复");
    }

    private static void awaitEditorReady(ActivityScenario<MainActivity> scenario)
            throws Exception {
        await(() -> {
            AtomicBoolean ready = new AtomicBoolean();
            scenario.onActivity(activity -> ready.set(activity.canvasForTest() != null
                    && activity.canvasForTest().isEnabled()));
            return ready.get();
        }, "会话测试笔记编辑器没有完成本地恢复");
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
        }, "笔记内容没有完成本地保存");
    }

    private static void assertVisibleTurnAndWire(ActivityScenario<MainActivity> scenario,
                                                 String marker) {
        scenario.onActivity(activity -> {
            assertVisibleContains(activity.conversationForTest(), marker);
            assertWireContains(activity.nextWireHistoryForTest(), marker);
        });
    }

    private static void assertVisibleContains(AiConversationStore.Snapshot snapshot,
                                              String marker) {
        assertNotNull(snapshot);
        for (AiConversationStore.VisibleEntry entry : snapshot.visibleTimeline) {
            if (entry.text.contains(marker)) return;
        }
        fail("visible timeline lost marker " + marker);
    }

    private static boolean wireContains(List<OpenAiCompatibleClient.Message> messages,
                                        String marker) {
        try {
            JSONObject request = OpenAiCompatibleClient.buildRequest(
                    "fixture-model", null, new ArrayList<>(messages),
                    new JSONArray(), new JSONObject());
            return request.toString().contains(marker);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void assertWireContains(List<OpenAiCompatibleClient.Message> messages,
                                           String marker) {
        assertTrue("actual next request JSON lost marker", wireContains(messages, marker));
    }

    private static int flowSourceOccurrences(NoteCanvasView canvas, String marker) {
        int count = 0;
        for (TextFlow flow : canvas.getTextFlows()) {
            if (flow.source.contains(marker)) count++;
        }
        return count;
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

    private static AiConfigStore.Profile profile(String id, String model) {
        AiConfigStore.Profile profile = new AiConfigStore.Profile();
        profile.id = id;
        profile.name = "会话夹具 " + id;
        profile.directEndpoint = "https://fixture.invalid/v1";
        profile.directModel = model;
        return profile;
    }

    private static JSONObject writeArguments(String content) {
        try {
            return new JSONObject().put("content", content)
                    .put("format", "markdown")
                    .put("placement", new JSONObject()
                            .put("relativeTo", "selection")
                            .put("position", "below"));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static TextFlow findFlow(NoteCanvasView canvas, String flowId) {
        for (TextFlow flow : canvas.getTextFlows()) {
            if (flowId.equals(flow.id)) return flow;
        }
        return null;
    }

    private static AiConversationStore.VisibleEntry visible(String text, String executor) {
        return new AiConversationStore.VisibleEntry(
                "message", "assistant", text, executor, false);
    }

    private static AiConversationStore.Binding binding(String profileId, long revision) {
        return new AiConversationStore.Binding(
                "semantic-fixture", "", profileId, revision,
                NoteTool.Permission.READ_ONLY.name(), "");
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        assertTrue("failed to remove fixture " + file, file.delete());
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
        }, "书架没有显示会话测试笔记");
    }

    private static final class ProfileFixture implements AutoCloseable {
        final AiConfigStore store;
        final AiConfigStore.Profile profile;
        final String originalActiveId;

        private ProfileFixture(AiConfigStore store, AiConfigStore.Profile profile,
                               String originalActiveId) {
            this.store = store;
            this.profile = profile;
            this.originalActiveId = originalActiveId;
        }

        static ProfileFixture install(Context context) throws Exception {
            AiConfigStore store = new AiConfigStore(context);
            String original = store.activeProfileId();
            AiConfigStore.Profile profile = profile(
                    "conversation-fixture-" + UUID.randomUUID(), "model-fixture");
            store.saveProfile(profile, null, null, null);
            store.setActiveProfileId(profile.id);
            return new ProfileFixture(store, profile, original);
        }

        @Override public void close() {
            store.deleteProfile(profile.id);
            store.setActiveProfileId(originalActiveId == null ? "" : originalActiveId);
        }
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

        static EditorSession open(String prefix) throws Exception {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            NoteStore.Entry note = NoteStore.create(context, prefix + " " + UUID.randomUUID());
            ActivityScenario<MainActivity> scenario = null;
            try {
                scenario = ActivityScenario.launch(MainActivity.class);
                clickNoteWhenVisible(note.title);
                ActivityScenario<MainActivity> launched = scenario;
                await(() -> {
                    AtomicBoolean ready = new AtomicBoolean();
                    launched.onActivity(activity -> ready.set(
                            activity.canvasForTest() != null
                                    && activity.canvasForTest().isEnabled()));
                    return ready.get();
                }, "会话测试笔记编辑器没有完成本地恢复");
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
            Thread.sleep(750);
            NoteStore.delete(context, note.id);
            new AiConversationStore(context).clear(note.id);
        }
    }
}
