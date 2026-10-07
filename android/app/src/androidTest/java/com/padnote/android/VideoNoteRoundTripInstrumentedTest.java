package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.app.UiAutomation;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.View;
import android.widget.VideoView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.Root;
import androidx.test.espresso.ViewAssertion;
import androidx.test.espresso.matcher.RootMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.hamcrest.Matcher;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.hamcrest.Matchers;

/** End-to-end note entry, frozen-input, verified attachment and offline-open UI flow. */
@RunWith(AndroidJUnit4.class)
public final class VideoNoteRoundTripInstrumentedTest {
    private static final String NOTE_TITLE = "原笔记视频闭环测试";
    private static final String VISIBLE_EDIT = "尚未保存的可见修改";
    private static final String FIRST_MATERIAL = "第一版整理材料-旧快照";
    private static final String SECOND_MATERIAL = "第二版整理材料-显式保留";
    private static final String THIRD_MATERIAL = "第三版整理材料-不得自动替换";

    @Test public void noteEntryFreezesExplicitSnapshotAndReopensOnlyVerifiedOfflineVideo() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        byte[] mp4;
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("video/roundtrip.mp4")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) >= 0) { if (n > 0) bytes.write(buffer, 0, n); }
            mp4 = bytes.toByteArray();
        }
        String mp4Sha = hex(MessageDigest.getInstance("SHA-256").digest(mp4));
        assertTrue(mp4.length > 0);

        AgentConnectionStore connections = new AgentConnectionStore(context);
        connections.clear();
        AgentConnectionStore.Config draft = connections.addBridge("本机测试 Bridge",
                AgentConnectionStore.Kind.HERMES, "https://bridge.test", "bridge-roundtrip",
                "instance-roundtrip", "synthetic-test-token");
        assertTrue(connections.applyProbeSuccess(draft.id, draft.revision,
                new AgentConnectionClient.ProbeResult("verified", "bridge-roundtrip",
                        "instance-roundtrip", Arrays.asList("task_bundle", "run_submission",
                                "run_status", "artifacts"))));

        NoteStore.Entry created = NoteStore.create(context, NOTE_TITLE);
        VaultStore vault = new VaultStore(context);
        ActivityScenario<MainActivity> scenario = null;
        AtomicInteger postCount = new AtomicInteger();
        AtomicInteger statusCount = new AtomicInteger();
        AtomicInteger downloadCount = new AtomicInteger();
        AtomicReference<Exception> attachFailure = new AtomicReference<>();
        AtomicReference<byte[]> submittedBody = new AtomicReference<>();
        AgentTaskStore tasks = new AgentTaskStore(context);
        final String[] submittedClientId = {""};
        final VideoAttachmentStore[] attachmentStore = {null};
        final VideoAttachmentStore.Attachment[] attached = {null};
        try {
            NoteStore.Entry initial = findNote(context, created.id);
            vault.write(created.id, initial.title, 1, initial.updatedAt,
                    Collections.singletonList(FIRST_MATERIAL));

            AgentHttpTransport transport = request -> {
                String path = request.url.getPath();
                if ("POST".equals(request.method) && path.endsWith("/runs")) {
                    postCount.incrementAndGet();
                    submittedBody.set(request.body.clone());
                    JSONObject payload = new JSONObject(new String(request.body, StandardCharsets.UTF_8));
                    submittedClientId[0] = payload.getString("client_task_id");
                    JSONObject accepted = new JSONObject().put("task_id", "remote-roundtrip")
                            .put("instance_id", "instance-roundtrip").put("status", "completed");
                    return response(request, 202, accepted.toString());
                }
                if ("GET".equals(request.method) && path.endsWith("/runs/remote-roundtrip")) {
                    statusCount.incrementAndGet();
                    JSONObject artifact = new JSONObject().put("id", "artifact-roundtrip")
                            .put("name", "roundtrip.mp4").put("media_type", "video/mp4")
                            .put("size_bytes", mp4.length).put("sha256", mp4Sha);
                    JSONObject status = new JSONObject().put("task_id", "remote-roundtrip")
                            .put("instance_id", "instance-roundtrip").put("status", "completed")
                            .put("output", "已完成测试视频").put("artifacts", new JSONArray().put(artifact))
                            .put("conversation_id", "remote-roundtrip").put("parent_task_id", "")
                            .put("followup_available", false).put("followup_reason", "fixture");
                    return response(request, 200, status.toString());
                }
                throw new AssertionError("unexpected local fixture request " + request.method + " " + path);
            };

            scenario = ActivityScenario.launch(MainActivity.class);
            ActivityScenario<MainActivity> activeScenario = scenario;
            scenario.onActivity(activity -> {
                activity.setVideoTaskClientFactoryForTest(() -> new AgentTaskClient(transport));
                activity.setVideoArtifactSourceForTest((url, bearer, pin) -> {
                    assertEquals("synthetic-test-token", bearer);
                    assertTrue(url.getPath().endsWith("/artifacts/artifact-roundtrip"));
                    downloadCount.incrementAndGet();
                    return new AgentArtifactDownloader.OpenResponse(200, url, url, mp4.length,
                            new ByteArrayInputStream(mp4), null);
                });
                activity.setVideoAttachmentFailureForTest(attachFailure::set);
                invokeOpenNote(activity, created);
                attachmentStore[0] = getVideoAttachmentStore(context);
            });
            waitForRestore(activeScenario);

            activeScenario.onActivity(activity -> {
                NoteCanvasView canvas = getCanvas(activity);
                assertNotNull("visible note canvas should be ready", canvas);
                assertNotNull("test edit must be added to the live canvas",
                        canvas.addTextBox(NoteTextBox.Format.MARKDOWN, VISIBLE_EDIT));
            });
            onView(withContentDescription("当前笔记的视频任务与成果")).perform(click());
            onView(withText(NOTE_TITLE)).inRoot(RootMatchers.isDialog()).check(matches(isDisplayed()));
            saveCheckpointScreenshot(context,"01-note-video-hub.png");
            waitUntil(() -> containsJson(NoteStore.load(context, created.id), VISIBLE_EDIT));
            assertTrue("video entry must save the visible edit before showing the hub",
                    containsJson(NoteStore.load(context, created.id), VISIBLE_EDIT));

            NoteStore.Entry savedEdit = findNote(context, created.id);
            vault.write(created.id, savedEdit.title, 1, savedEdit.updatedAt,
                    Collections.singletonList(FIRST_MATERIAL));
            startSendToComputer();
            awaitDialogText(FIRST_MATERIAL);

            updateSource(context, vault, created.id, "第二版标题", SECOND_MATERIAL);
            onView(withText("确认使用")).inRoot(RootMatchers.isDialog()).perform(click());
            awaitDialogText("原材料已更新");
            onView(withText("取消")).inRoot(RootMatchers.isDialog()).perform(click());
            assertEquals("cancelling after a source change must send no task request", 0, postCount.get());

            onView(withContentDescription("当前笔记的视频任务与成果")).perform(click());
            onView(withText("生成讲解视频")).inRoot(RootMatchers.isDialog()).perform(click());
            onView(withText("发送到电脑")).inRoot(RootMatchers.isDialog()).perform(click());
            awaitDialogText(SECOND_MATERIAL);
            saveCheckpointScreenshot(context,"02-frozen-material-preview.png");
            updateSource(context, vault, created.id, "第三版标题", THIRD_MATERIAL);
            onView(withText("确认使用")).inRoot(RootMatchers.isDialog()).perform(click());
            awaitDialogText("原材料已更新");
            onView(withText("使用旧快照")).inRoot(RootMatchers.isDialog()).perform(click());
            awaitDialogText("发送到哪一个 Agent");
            onView(withText("本机测试 Bridge")).inRoot(RootMatchers.isDialog()).perform(click());
            awaitDialogText("状态：已完成");
            assertEquals("explicitly keeping the frozen snapshot should submit once", 1, postCount.get());
            assertEquals(1, statusCount.get());
            String frozenMarkdown = bundleMarkdown(submittedBody.get());
            assertTrue(frozenMarkdown, frozenMarkdown.contains(SECOND_MATERIAL));
            assertFalse("third-version content must not silently replace the old snapshot",
                    frozenMarkdown.contains(THIRD_MATERIAL));

            awaitViewTextPrefix("保存产物：roundtrip.mp4");
            onView(withText(Matchers.startsWith("保存产物：roundtrip.mp4")))
                    .inRoot(RootMatchers.isDialog()).perform(scrollTo(), click());
            onView(withText("关联到来源笔记")).inRoot(RootMatchers.isDialog()).perform(click());
            awaitAttachmentOrFailure(attachmentStore[0], created.id, downloadCount, attachFailure);
            attached[0] = attachmentStore[0].listForNote(created.id).get(0);
            assertEquals(mp4Sha, attached[0].sha256);
            assertEquals("one streamed artifact download is expected", 1, downloadCount.get());
            assertEquals("verified MP4 bytes are copied to app-private storage", mp4.length,
                    new File(new File(context.getFilesDir(), "video-attachments"),
                            attached[0].storedName).length());

            onView(withText("打开来源手写笔记")).inRoot(RootMatchers.isDialog()).perform(scrollTo(), click());
            onView(withContentDescription("当前笔记的视频任务与成果")).perform(click());
            onView(withText("已关联视频")).inRoot(RootMatchers.isDialog()).perform(click());
            saveCheckpointScreenshot(context,"03-offline-attachment-list.png");
            onView(withText(Matchers.startsWith("roundtrip.mp4"))).inRoot(RootMatchers.isDialog()).perform(click());
            onView(withText("离线播放")).inRoot(RootMatchers.isDialog()).perform(click());
            VideoView playing=awaitPlayableVideo();
            saveCheckpointScreenshot(context,"04-offline-video-player.png");
            assertEquals("playback must use the app-private local file, without another download",
                    1, downloadCount.get());
            assertTrue("verified offline video should start playback",playing.isPlaying());

            onView(withText("关闭")).inRoot(RootMatchers.isDialog()).perform(click());
            assertTrue("closing the player must stop playback",!playing.isPlaying());
            AtomicReference<Runnable> delayedStart=new AtomicReference<>();
            activeScenario.onActivity(activity->{
                activity.setVideoPreparedStartForTest(delayedStart::set);
                invokePlayAttached(activity,attached[0]);
            });
            waitUntil(()->delayedStart.get()!=null);
            VideoView latePlayer=awaitPlayableVideo();
            assertTrue("test gate holds a prepared player before it starts",!latePlayer.isPlaying());
            onView(withText("关闭")).inRoot(RootMatchers.isDialog()).perform(click());
            activeScenario.onActivity(activity->delayedStart.get().run());
            Thread.sleep(500L);
            assertTrue("a prepared callback released after dismissal must not restart playback",
                    !latePlayer.isPlaying());
            activeScenario.onActivity(activity->activity.setVideoPreparedStartForTest(null));

            // Corruption after a previously valid offline open must fail closed and remain visible.
            File stored = new File(new File(context.getFilesDir(), "video-attachments"), attached[0].storedName);
            try (FileOutputStream output = new FileOutputStream(stored, false)) {
                output.write(new byte[]{0, 1, 2, 3}); output.getFD().sync();
            }
            activeScenario.onActivity(activity -> invokePlayAttached(activity, attached[0]));
            waitUntil(() -> attachFailure.get()!=null);
            assertTrue("corrupt offline attachment should reach the user warning path",
                    attachFailure.get().getMessage().contains("大小改变"));
            assertEquals("a corrupt file is not silently detached", 1,
                    attachmentStore[0].listForNote(created.id).size());
        } finally {
            if (scenario != null) scenario.close();
            if (attached[0] != null && attachmentStore[0] != null) {
                try { attachmentStore[0].remove(attached[0].id); } catch (Exception ignored) { }
            }
            if (!submittedClientId[0].isEmpty()) {
                File task = new File(new File(context.getFilesDir(), "agent-tasks"),
                        submittedClientId[0] + ".json");
                task.delete();
            }
            for (VaultStore.VaultNote note : vault.list()) {
                if (created.id.equals(note.noteId)) vault.delete(note.fileName);
            }
            NoteStore.delete(context, created.id);
            connections.delete(draft.id);
        }
    }

    private static void startSendToComputer() throws Exception {
        onView(withText("生成讲解视频")).inRoot(RootMatchers.isDialog()).perform(click());
        onView(withText("发送到电脑")).inRoot(RootMatchers.isDialog()).perform(click());
    }

    private static void saveCheckpointScreenshot(Context context,String name) throws Exception {
        File directory=context.getExternalFilesDir("roundtrip-evidence");
        if(directory==null||(!directory.isDirectory()&&!directory.mkdirs()))
            throw new AssertionError("cannot create synthetic screenshot directory");
        Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        if(screenshot==null)throw new AssertionError("cannot capture synthetic UI checkpoint");
        File destination=new File(directory,name);
        try(FileOutputStream output=new FileOutputStream(destination,false)){
            if(!screenshot.compress(Bitmap.CompressFormat.PNG,100,output))
                throw new AssertionError("cannot save synthetic UI checkpoint");
            output.flush();output.getFD().sync();
        }finally{screenshot.recycle();}
    }

    private static void updateSource(Context context, VaultStore vault, String noteId,
                                     String title, String material) throws Exception {
        NoteStore.Entry current = findNote(context, noteId);
        JSONObject document = NoteStore.load(context, noteId);
        while (System.currentTimeMillis() <= current.updatedAt) Thread.sleep(2L);
        NoteStore.Entry changed = NoteStore.save(context, noteId, title, document.toString());
        vault.write(noteId, title, 1, changed.updatedAt, Collections.singletonList(material));
    }

    private static NoteStore.Entry findNote(Context context, String id) throws Exception {
        for (NoteStore.Entry entry : NoteStore.list(context)) if (entry.id.equals(id)) return entry;
        throw new AssertionError("fixture note disappeared");
    }

    private static boolean containsJson(JSONObject document, String text) {
        return document.toString().contains(text);
    }

    private static void invokeOpenNote(MainActivity activity, NoteStore.Entry entry) {
        try {
            java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod("openNote", NoteStore.Entry.class);
            method.setAccessible(true); method.invoke(activity, entry);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static NoteCanvasView getCanvas(MainActivity activity) {
        try {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("canvasView");
            field.setAccessible(true); return (NoteCanvasView) field.get(activity);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static VideoAttachmentStore getVideoAttachmentStore(Context context) {
        return new VideoAttachmentStore(context);
    }

    private static void invokePlayAttached(MainActivity activity,
                                           VideoAttachmentStore.Attachment attachment) {
        try {
            java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod(
                    "playAttachedVideo", VideoAttachmentStore.Attachment.class);
            method.setAccessible(true); method.invoke(activity, attachment);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static void waitForRestore(ActivityScenario<MainActivity> scenario) throws Exception {
        waitUntil(() -> {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Field field = MainActivity.class.getDeclaredField("restoreCompleted");
                    field.setAccessible(true); ready.set(field.getBoolean(activity));
                } catch (Exception error) { throw new AssertionError(error); }
            });
            return ready.get();
        });
    }

    private static void awaitDialogText(String text) throws Exception {
        waitUntil(() -> {
            try {
                onView(withText(Matchers.containsString(text))).inRoot(RootMatchers.isDialog())
                        .check(matches(isDisplayed()));
                return true;
            } catch (Exception | AssertionError ignored) { return false; }
        });
    }

    private static void awaitViewTextPrefix(String text) throws Exception {
        waitUntil(() -> {
            try {
                onView(withText(Matchers.startsWith(text))).inRoot(RootMatchers.isDialog())
                        .perform(scrollTo()).check(matches(isDisplayed()));
                return true;
            } catch (Exception | AssertionError ignored) { return false; }
        });
    }

    private static VideoView awaitPlayableVideo() throws Exception {
        AtomicReference<VideoView> found=new AtomicReference<>();
        waitUntil(() -> {
            AtomicReference<Boolean> playable = new AtomicReference<>(false);
            try {
                onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(VideoView.class))
                        .inRoot(RootMatchers.isDialog()).check((view, noView) -> {
                            VideoView video = (VideoView) view;
                            found.set(video);
                            playable.set(video.getDuration() > 0 || video.isPlaying());
                        });
            } catch (Exception | AssertionError ignored) { }
            return playable.get();
        });
        return found.get();
    }

    private static void awaitAttachmentOrFailure(VideoAttachmentStore store, String noteId,
                                                 AtomicInteger downloads,
                                                 AtomicReference<Exception> attachFailure) throws Exception {
        long deadline = System.currentTimeMillis() + 15000L;
        while (System.currentTimeMillis() < deadline) {
            try { if (store.listForNote(noteId).size() == 1) return; }
            catch (Exception ignored) { }
            if (attachFailure.get()!=null) break;
            Thread.sleep(50L);
        }
        Exception captured=attachFailure.get();
        String detail=captured==null?"":captured.getClass().getSimpleName()+": "+captured.getMessage();
        assertTrue("attachment did not complete; downloads=" + downloads.get() +
                (detail.isEmpty()?"":"; exception="+detail), false);
    }

    private interface Check { boolean ok() throws Exception; }
    private static void waitUntil(Check check) throws Exception {
        long deadline = System.currentTimeMillis() + 15000L;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) return;
            Thread.sleep(50L);
        }
        assertTrue("condition did not become true within the UI test budget; visible UI: " +
                visibleUiSummary(), check.ok());
    }

    private static String visibleUiSummary() {
        try {
            UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
            StringBuilder out = new StringBuilder();
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                appendVisibleText(window.getRoot(), out, new int[]{0});
                if (out.length() >= 1800) break;
            }
            if (out.length() == 0) appendVisibleText(automation.getRootInActiveWindow(), out, new int[]{0});
            return out.toString();
        } catch (Exception error) { return "unavailable:" + error.getClass().getSimpleName(); }
    }

    private static void appendVisibleText(AccessibilityNodeInfo node, StringBuilder out, int[] count) {
        if (node == null || count[0]++ >= 100 || out.length() >= 1800) return;
        CharSequence text = node.getText();
        if (text != null && !text.toString().trim().isEmpty()) out.append(text).append(" | ");
        for (int i = 0; i < node.getChildCount(); i++) appendVisibleText(node.getChild(i), out, count);
    }

    private static AgentHttpTransport.Response response(AgentHttpTransport.Request request,
                                                          int status, String body) {
        return new AgentHttpTransport.Response(status, request.url, request.url,
                body.getBytes(StandardCharsets.UTF_8), Collections.emptyMap());
    }

    private static String bundleMarkdown(byte[] requestBody) throws Exception {
        assertNotNull("task request must be captured", requestBody);
        JSONObject payload = new JSONObject(new String(requestBody, StandardCharsets.UTF_8));
        byte[] zip = android.util.Base64.decode(payload.getString("bundle_base64"), android.util.Base64.DEFAULT);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String actual = hex(digest.digest(zip));
        assertEquals(payload.getString("bundle_sha256"), actual);
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!"input/content.md".equals(entry.getName())) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("frozen task bundle has no markdown entry");
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }
}
