package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the actual dialogs, page renderer and private checkpoint files; network is fake. */
@RunWith(AndroidJUnit4.class)
public final class DigitizationControllerTest {
    private static final AiConfigStore.Config CONFIG = new AiConfigStore.Config(
            "https://model.example/v1", "offline-fixture", "fixture-only-never-sent");

    @Test public void failedSecondPageReopensAndOnlyRequestsRemainingPages() throws Exception {
        Context isolated = isolatedContext();
        DigitizationStore store = new DigitizationStore(isolated);
        VaultStore vault = new VaultStore(isolated);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<DigitizationController> controller = new AtomicReference<>();
        AtomicReference<NoteCanvasView> canvas = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    NoteCanvasView paper = paper(activity, 3);
                    canvas.set(paper);
                    DigitizationController value = new DigitizationController(activity, vault, store,
                            (config, png, prompt, cancellation) -> {
                                int call = calls.incrementAndGet();
                                assertTrue(png.length > 0);
                                DigitizationStore.Snapshot beforeNetwork = store.findLatest("note-resume-fixture");
                                assertEquals(beforeNetwork.nextPendingPage(), beforeNetwork.inFlightPage);
                                if (call == 2) throw new IOException("fixture failure");
                                return "离线转写 " + call;
                            });
                    controller.set(value);
                    value.open(paper, "note-resume-fixture", "恢复样例", "profile-fixture", CONFIG, 123L);
                } catch (Exception error) { throw new AssertionError(error); }
            });
            clickWhenVisible("确认范围并开始");
            await(() -> {
                DigitizationStore.Snapshot draft = store.findLatest("note-resume-fixture");
                return draft != null && "FAILED".equals(draft.state.name());
            });
            assertEquals(1, store.findLatest("note-resume-fixture").completedCount());
            assertTrue(vault.list().isEmpty());
            clickWhenVisible("关闭");
            scenario.onActivity(activity -> controller.get().open(canvas.get(), "note-resume-fixture",
                    "恢复样例", "profile-fixture", CONFIG, 123L));
            clickWhenVisible("确认范围并继续未完成页");
            await(() -> "COMPLETED".equals(store.findLatest("note-resume-fixture").state.name()));
            assertEquals(4, calls.get());
            assertEquals(3, store.findLatest("note-resume-fixture").completedCount());
            assertEquals(-1, store.findLatest("note-resume-fixture").inFlightPage);
            assertEquals(1, vault.list().size());
            String markdown = vault.read(vault.list().get(0).fileName);
            assertTrue(markdown.contains("离线转写 1"));
            assertTrue(markdown.contains("离线转写 3"));
            assertTrue(markdown.contains("离线转写 4"));
            // A completed task can be inspected again without silently calling the provider.
            await(() -> {
                java.util.concurrent.atomic.AtomicBoolean idle = new java.util.concurrent.atomic.AtomicBoolean();
                scenario.onActivity(activity -> idle.set(!controller.get().isBusy()));
                return idle.get();
            });
            scenario.onActivity(activity -> controller.get().open(canvas.get(), "note-resume-fixture",
                    "恢复样例", "profile-fixture", CONFIG, 123L));
            clickWhenVisible("查看已保存的批次（1）");
            assertEquals(4, calls.get());
            scenario.onActivity(activity -> controller.get().close());
        } finally { delete(isolated.getFilesDir()); }
    }

    @Test public void cancellationRejectsLateResponseAndKeepsExistingVault() throws Exception {
        Context isolated = isolatedContext();
        DigitizationStore store = new DigitizationStore(isolated);
        VaultStore vault = new VaultStore(isolated);
        vault.write("note-cancel-fixture", "取消样例", 1, 1L, java.util.Collections.singletonList("已完成旧内容"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<DigitizationController> controller = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    DigitizationController value = new DigitizationController(activity, vault, store,
                            (config, png, prompt, cancellation) -> {
                                entered.countDown();
                                // Deliberately emulate a provider result arriving after local cancellation.
                                while (release.getCount() > 0) {
                                    try { release.await(100, TimeUnit.MILLISECONDS); }
                                    catch (InterruptedException ignored) { }
                                }
                                return "不应保存的晚到结果";
                            });
                    controller.set(value);
                    value.open(paper(activity, 1), "note-cancel-fixture", "取消样例", "profile-fixture", CONFIG, 2L);
                } catch (Exception error) { throw new AssertionError(error); }
            });
            clickWhenVisible("确认范围并开始");
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            clickWhenVisible("取消整理");
            release.countDown();
            await(() -> {
                DigitizationStore.Snapshot draft = store.findLatest("note-cancel-fixture");
                return draft != null && "CANCELLED".equals(draft.state.name());
            });
            assertEquals(0, store.findLatest("note-cancel-fixture").completedCount());
            assertEquals(0, store.findLatest("note-cancel-fixture").inFlightPage);
            assertTrue(vault.read(vault.list().get(0).fileName).contains("已完成旧内容"));
            assertFalse(vault.read(vault.list().get(0).fileName).contains("晚到"));
            scenario.onActivity(activity -> controller.get().close());
        } finally { release.countDown(); delete(isolated.getFilesDir()); }
    }

    @Test public void busyBatchLeaseDoesNotChangeCheckpointOrCallProvider() throws Exception {
        Context isolated = isolatedContext();
        DigitizationStore store = new DigitizationStore(isolated);
        VaultStore vault = new VaultStore(isolated);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<DigitizationController> controller = new AtomicReference<>();
        AtomicReference<DigitizationStore.Snapshot> before = new AtomicReference<>();
        AtomicReference<DigitizationStore.Lease> heldLease = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    NoteCanvasView paper = paper(activity, 1);
                    JSONObject document = paper.toJsonDocument(
                            "note-busy-fixture", "占用样例");
                    DigitizationStore.Source source = DigitizationStore.Source.from(document,
                            "", 1, "profile-fixture", CONFIG.endpoint, CONFIG.model);
                    DigitizationStore.Snapshot created = store.create(source, "占用样例", 456L);
                    before.set(created);
                    DigitizationStore.Lease lease = store.acquireLease(created.runId);
                    assertNotNull(lease);
                    heldLease.set(lease);
                    DigitizationController value = new DigitizationController(activity,
                            vault, store, (config, png, prompt, cancellation) -> {
                        calls.incrementAndGet();
                        return "不应调用";
                    });
                    controller.set(value);
                    value.open(paper, "note-busy-fixture", "占用样例",
                            "profile-fixture", CONFIG, 456L);
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });

            clickWhenVisible("确认范围并继续未完成页");
            await(() -> {
                AtomicBoolean idle = new AtomicBoolean();
                scenario.onActivity(activity -> idle.set(!controller.get().isBusy()));
                return idle.get();
            });

            DigitizationStore.Snapshot unchanged = store.load(before.get().runId);
            assertEquals(0, calls.get());
            assertEquals(before.get().revision, unchanged.revision);
            assertEquals(before.get().pages, unchanged.pages);
            assertEquals(before.get().inFlightPage, unchanged.inFlightPage);
            assertEquals(before.get().attempt, unchanged.attempt);
            assertEquals(before.get().state, unchanged.state);
            scenario.onActivity(activity -> controller.get().close());
        } finally {
            if (heldLease.get() != null) heldLease.get().close();
            delete(isolated.getFilesDir());
        }
    }

    private static NoteCanvasView paper(Context context, int pages) throws Exception {
        JSONObject document = new JSONObject().put("schemaVersion", 8).put("id", "note-fixture")
                .put("title", "恢复样例").put("pageWidth", 800).put("pageHeight", 1100)
                .put("pageCount", pages).put("strokes", new JSONArray()).put("images", new JSONArray())
                .put("textFlows", new JSONArray()).put("textBoxes", new JSONArray());
        NoteCanvasView canvas = new NoteCanvasView(context);
        canvas.layout(0, 0, 800, 1100);
        canvas.loadJsonDocument(document);
        return canvas;
    }
    private static Context isolatedContext() throws Exception {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(base.getCacheDir(), "digitize-test-" + UUID.randomUUID());
        if (!directory.mkdirs()) throw new IOException("Cannot create fixture directory");
        return new ContextWrapper(base) { @Override public File getFilesDir() { return directory; } };
    }
    private interface Condition { boolean met() throws Exception; }
    private static void await(Condition condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < end) {
            if (condition.met()) return;
            Thread.sleep(25);
        }
        fail("Timed out waiting for durable digitization state");
    }
    private static void clickWhenVisible(String label) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        Throwable last = null;
        while (System.nanoTime() < end) {
            try { onView(withText(label)).inRoot(isDialog()).perform(click()); return; }
            catch (androidx.test.espresso.NoMatchingViewException | androidx.test.espresso.NoMatchingRootException error) { last = error; Thread.sleep(50); }
        }
        throw new AssertionError("Missing dialog action: " + label, last);
    }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
