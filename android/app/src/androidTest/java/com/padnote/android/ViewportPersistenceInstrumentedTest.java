package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.padnote.android.streaming.StreamingGroupStore;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** MainActivity persistence regressions for view-only changes, separate from authored revisions. */
@RunWith(AndroidJUnit4.class)
public final class ViewportPersistenceInstrumentedTest {
    @Test public void leavingShelfPersistsZoomWithoutCreatingANoteRevisionAndReopenRestoresIt()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        NoteStore.Entry entry = createManagedFixture(context, "R29 viewport shelf ");
        NoteGroupFacade facade = new NoteGroupFacade(context);
        StreamingGroupStore.Snapshot before = facade.openGroup(entry.id);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            open(scenario, entry);
            AtomicReference<NoteCanvasView.PersistedViewport> expected = new AtomicReference<>();
            scenario.onActivity(activity -> {
                NoteCanvasView canvas = activity.canvasForTest();
                zoomCanvas(canvas);
                expected.set(canvas.capturePersistedViewport());
                invokeForAssertion(activity, "leaveEditorToBookshelf");
            });
            await(scenario, "editorVisible", false);
            awaitViewportWrite(context, entry.id);
            StreamingGroupStore.Snapshot afterLeave = facade.openGroup(entry.id);
            assertEquals("viewport-only leave must not publish a paper/source revision",
                    before.revision, afterLeave.revision);
            assertEquals(before.digest, afterLeave.digest);

            open(scenario, entry);
            AtomicReference<NoteCanvasView.PersistedViewport> restored = new AtomicReference<>();
            scenario.onActivity(activity -> restored.set(activity.canvasForTest().capturePersistedViewport()));
            assertStateEquals(expected.get(), restored.get());
            assertEquals("reopening the UI-only viewport must not create a group revision",
                    before.revision, facade.openGroup(entry.id).revision);

            AtomicReference<NoteCanvasView.PersistedViewport> resized = new AtomicReference<>();
            scenario.onActivity(activity -> {
                NoteCanvasView canvas = activity.canvasForTest();
                int width = Math.max(320, canvas.getWidth() - 160);
                int height = Math.max(480, canvas.getHeight() - 180);
                canvas.layout(0, 0, width, height);
                resized.set(canvas.capturePersistedViewport());
                invokeForAssertion(activity, "leaveEditorToBookshelf");
            });
            await(scenario, "editorVisible", false);
            awaitStoredState(context, entry.id, resized.get());
            assertEquals("resize-corrected viewport remains outside the author revision",
                    before.revision, facade.openGroup(entry.id).revision);
            open(scenario, entry);
            AtomicReference<NoteCanvasView.PersistedViewport> resizedRestored = new AtomicReference<>();
            scenario.onActivity(activity -> resizedRestored.set(
                    activity.canvasForTest().capturePersistedViewport()));
            assertStateEquals(resized.get(), resizedRestored.get());

            AtomicReference<NoteCanvasView.PersistedViewport> backgroundPosition = new AtomicReference<>();
            scenario.onActivity(activity -> {
                zoomCanvas(activity.canvasForTest());
                backgroundPosition.set(activity.canvasForTest().capturePersistedViewport());
            });
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            awaitStoredState(context, entry.id, backgroundPosition.get());
            assertEquals("backgrounding a view-only zoom must not rewrite the note source",
                    before.revision, facade.openGroup(entry.id).revision);
        }
    }

    @Test public void viewportMoveAfterContentSaveSnapshotIsPersistedWithoutAnotherGroupRevision()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        NoteStore.Entry entry = createManagedFixture(context, "R29 viewport inflight ");
        NoteGroupFacade facade = new NoteGroupFacade(context);
        StreamingGroupStore.Snapshot before = facade.openGroup(entry.id);
        CountDownLatch storageBlocked = new CountDownLatch(1);
        CountDownLatch releaseStorage = new CountDownLatch(1);
        AtomicReference<String> paperHandoffTitle = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            open(scenario, entry);
            AtomicReference<Throwable> blockerFailure = new AtomicReference<>();
            scenario.onActivity(activity -> {
                try {
                    ExecutorService executor = (ExecutorService) field(activity, "storageExecutor");
                    executor.execute(() -> {
                        storageBlocked.countDown();
                        try { releaseStorage.await(10, TimeUnit.SECONDS); }
                        catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            blockerFailure.set(error);
                        }
                    });
                    activity.canvasForTest().addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                            "synthetic authored edit", 40f, 70f);
                    invoke(activity, "saveDocument", new Class<?>[]{boolean.class}, false);
                } catch (Throwable error) { blockerFailure.set(error); }
            });
            assertTrue("test blocker occupies only the isolated app storage executor",
                    storageBlocked.await(5, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                try {
                    assertTrue("content snapshot is still in flight while the viewport moves",
                            (Boolean) field(activity, "saveInFlight"));
                    paperHandoffTitle.set(activity.getString(R.string.core_paper_handoff));
                    invoke(activity, "showPaperHandoffForCurrentNote");
                    zoomCanvas(activity.canvasForTest());
                } catch (Exception error) { throw new AssertionError(error); }
            });
            releaseStorage.countDown();
            if (blockerFailure.get() != null) throw new AssertionError(blockerFailure.get());
            awaitSaveAndViewportAck(scenario);
            NoteViewportStore.State finalViewport = NoteViewportStore.read(context, entry.id);
            assertNotNull("the post-snapshot zoom must reach the UI-only sidecar", finalViewport);
            StreamingGroupStore.Snapshot firstCommit = facade.openGroup(entry.id);
            Thread.sleep(900);
            StreamingGroupStore.Snapshot settled = facade.openGroup(entry.id);
            assertEquals("viewport follow-up must not publish a second author/group revision",
                    firstCommit.revision, settled.revision);
            assertTrue("the one actual content edit did publish", !before.revision.equals(settled.revision));
            AtomicReference<NoteCanvasView.PersistedViewport> captured = new AtomicReference<>();
            scenario.onActivity(activity -> captured.set(activity.canvasForTest().capturePersistedViewport()));
            assertEquals(captured.get().relativeZoom, finalViewport.relativeZoom, 0.002d);
            assertEquals(captured.get().centerX, finalViewport.centerX, 0.5d);
            assertEquals(captured.get().centerY, finalViewport.centerY, 0.5d);
            onView(withText(paperHandoffTitle.get())).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(R.string.core_cancel)).inRoot(isDialog()).perform(click());
        } finally {
            releaseStorage.countDown();
        }
    }

    @Test public void contentSaveCannotAcknowledgePastAnOlderViewportSidecar() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        NoteStore.Entry entry = createManagedFixture(context, "R29 viewport stale-sidecar ");
        NoteGroupFacade facade = new NoteGroupFacade(context);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            open(scenario, entry);
            scenario.onActivity(activity -> {
                zoomCanvas(activity.canvasForTest());
                invokeForAssertion(activity, "leaveEditorToBookshelf");
            });
            await(scenario, "editorVisible", false);
            awaitViewportWrite(context, entry.id);
            NoteViewportStore.State olderSidecar = NoteViewportStore.read(context, entry.id);
            assertNotNull(olderSidecar);

            open(scenario, entry);
            AtomicReference<NoteCanvasView.PersistedViewport> latest = new AtomicReference<>();
            scenario.onActivity(activity -> {
                NoteCanvasView canvas = activity.canvasForTest();
                zoomCanvas(canvas);
                latest.set(canvas.capturePersistedViewport());
                assertNotNull(canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                        "synthetic content change beside the pan", 44f, 88f));
                try { invoke(activity, "saveDocument", new Class<?>[]{boolean.class}, false); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            awaitSaveAndViewportAck(scenario);
            NoteViewportStore.State currentSidecar = NoteViewportStore.read(context, entry.id);
            assertNotNull(currentSidecar);
            assertEquals("latest movement, not the older preexisting sidecar, is durable",
                    latest.get().relativeZoom, currentSidecar.relativeZoom, 0.002d);
            assertEquals(latest.get().centerX, currentSidecar.centerX, 0.5d);
            assertEquals(latest.get().centerY, currentSidecar.centerY, 0.5d);
            assertTrue("the sidecar was materially newer than the preexisting value",
                    Math.abs(olderSidecar.relativeZoom - currentSidecar.relativeZoom) > 0.01d);

            scenario.onActivity(activity -> invokeForAssertion(activity, "leaveEditorToBookshelf"));
            await(scenario, "editorVisible", false);
            open(scenario, entry);
            AtomicReference<NoteCanvasView.PersistedViewport> afterReopen = new AtomicReference<>();
            scenario.onActivity(activity -> afterReopen.set(
                    activity.canvasForTest().capturePersistedViewport()));
            assertStateEquals(latest.get(), afterReopen.get());
        }
    }

    @Test public void malformedViewportSidecarFallsBackToTheHealthyNoteBody() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        NoteStore.Entry entry = createManagedFixture(context, "R29 viewport corrupt ");
        NoteGroupFacade facade = new NoteGroupFacade(context);
        StreamingGroupStore.Snapshot before = facade.openGroup(entry.id);
        java.lang.reflect.Method key = NoteViewportStore.class.getDeclaredMethod("key", String.class);
        key.setAccessible(true);
        String preferenceKey = (String) key.invoke(null, entry.id);
        context.getSharedPreferences("padnote-note-viewports-v1", Context.MODE_PRIVATE)
                .edit().putString(preferenceKey, "{not-json").commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            open(scenario, entry);
            StreamingGroupStore.Snapshot after = facade.openGroup(entry.id);
            assertEquals("invalid UI metadata must not make a valid note unreadable",
                    before.revision, after.revision);
            assertEquals(before.digest, after.digest);
        }
        context.getSharedPreferences("padnote-note-viewports-v1", Context.MODE_PRIVATE)
                .edit().putInt(preferenceKey, 42).commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            open(scenario, entry);
            StreamingGroupStore.Snapshot after = facade.openGroup(entry.id);
            assertEquals("a wrong preference value type also falls back to the note JSON",
                    before.revision, after.revision);
            assertEquals(before.digest, after.digest);
        }
    }

    @Test public void queuedOlderActivitySnapshotCannotOverwriteTheLaterViewportTicket() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        NoteCanvasView maxZoomCanvas = new NoteCanvasView(context);
        try {
            maxZoomCanvas.layout(0, 0, 800, 600);
            set(maxZoomCanvas, "pageWidth", 10_000f);
            set(maxZoomCanvas, "pageHeight", 10_000f);
            set(maxZoomCanvas, "viewportScale", 4f);
            set(maxZoomCanvas, "viewportScale64", 4d);
            set(maxZoomCanvas, "viewportCenterDirty", true);
            NoteCanvasView.PersistedViewport maximum = maxZoomCanvas.capturePersistedViewport();
            assertNotNull("Canvas maximum zoom remains persistable at minimum fitted scale", maximum);
            assertTrue(maximum.relativeZoom > 8.88d && maximum.relativeZoom < 8.90d);
        } finally {
            maxZoomCanvas.onDetachedFromWindow();
        }
        assertTrue("Canvas max scale 4 / minimum fit scale 0.45 remains persistable",
                new NoteViewportStore.State(8.889d, 200d, 300d).isValid());
        assertTrue("values above the supported viewport bound remain rejected",
                !new NoteViewportStore.State(8.891d, 200d, 300d).isValid());
        String syntheticId = "R29-viewport-fifo-" + UUID.randomUUID();
        NoteViewportStore.State oldSnapshot = new NoteViewportStore.State(1.1d, 120d, 240d);
        NoteViewportStore.State newSnapshot = new NoteViewportStore.State(1.7d, 330d, 660d);
        CountDownLatch latestWritten = new CountDownLatch(1);
        NoteViewportStore.writeAsync(context, syntheticId, oldSnapshot, null);
        NoteViewportStore.writeAsync(context, syntheticId, newSnapshot,
                saved -> latestWritten.countDown());
        assertTrue("the process-wide metadata queue drains both Activity snapshots in order",
                latestWritten.await(5, TimeUnit.SECONDS));
        NoteViewportStore.State current = NoteViewportStore.read(context, syntheticId);
        assertNotNull(current);
        assertEquals(newSnapshot.relativeZoom, current.relativeZoom, 0d);
        assertEquals(newSnapshot.centerX, current.centerX, 0d);
        assertEquals(newSnapshot.centerY, current.centerY, 0d);
    }

    @Test public void reopenReadWaitsBehindAnAlreadyQueuedPreviousActivityWrite() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Field queueField = NoteViewportStore.class.getDeclaredField("WRITES");
        queueField.setAccessible(true);
        ExecutorService writes = (ExecutorService) queueField.get(null);
        CountDownLatch writerBlocked = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        writes.execute(() -> {
            writerBlocked.countDown();
            try { releaseWriter.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        assertTrue(writerBlocked.await(5, TimeUnit.SECONDS));

        String noteId = "R29-viewport-read-barrier-" + UUID.randomUUID();
        NoteViewportStore.State capturedByPreviousActivity =
                new NoteViewportStore.State(1.9d, 450d, 760d);
        NoteViewportStore.writeAsync(context, noteId, capturedByPreviousActivity, null);
        CountDownLatch readCompleted = new CountDownLatch(1);
        AtomicReference<NoteViewportStore.State> restored = new AtomicReference<>();
        AtomicReference<Throwable> readFailure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try { restored.set(NoteViewportStore.readAfterQueuedWrites(context, noteId)); }
            catch (Throwable error) { readFailure.set(error); }
            finally { readCompleted.countDown(); }
        }, "viewport-read-barrier-test");
        reader.start();
        boolean readWaited = !readCompleted.await(150, TimeUnit.MILLISECONDS);
        releaseWriter.countDown();
        assertTrue("the restore read queues behind the previous Activity write", readWaited);
        assertTrue("background restore read completes after the writer drains",
                readCompleted.await(5, TimeUnit.SECONDS));
        if (readFailure.get() != null) throw new AssertionError(readFailure.get());
        assertNotNull(restored.get());
        assertEquals(capturedByPreviousActivity.relativeZoom, restored.get().relativeZoom, 0d);
        assertEquals(capturedByPreviousActivity.centerX, restored.get().centerX, 0d);
        assertEquals(capturedByPreviousActivity.centerY, restored.get().centerY, 0d);

        Future<Boolean> writerSelfWaitGuard = writes.submit(() -> {
            try {
                NoteViewportStore.readAfterQueuedWrites(context, noteId);
                return false;
            } catch (IllegalStateException expected) {
                return true;
            } catch (Exception error) {
                return false;
            }
        });
        assertTrue("writer-thread callers fail fast instead of waiting on their own queue",
                writerSelfWaitGuard.get(1, TimeUnit.SECONDS));
    }

    private static NoteStore.Entry createManagedFixture(Context context, String prefix) throws Exception {
        NoteStore.Entry entry = NoteStore.create(context, prefix + UUID.randomUUID());
        new NoteGroupFacade(context).adoptLegacyNote(entry.id, (oldBody, stagedBody) -> true);
        return entry;
    }

    private static void open(ActivityScenario<MainActivity> scenario, NoteStore.Entry entry) throws Exception {
        scenario.onActivity(activity -> {
            try { invoke(activity, "openNote", new Class<?>[]{NoteStore.Entry.class, PageStyle.class}, entry, null); }
            catch (Throwable error) { throw new AssertionError(error); }
        });
        await(scenario, "restoreCompleted", true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            java.util.concurrent.atomic.AtomicBoolean laidOut = new java.util.concurrent.atomic.AtomicBoolean();
            scenario.onActivity(activity -> laidOut.set(activity.canvasForTest() != null &&
                    activity.canvasForTest().getWidth() > 0 && activity.canvasForTest().getHeight() > 0));
            if (laidOut.get()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("synthetic editor canvas did not receive a viewport");
    }

    private static void await(ActivityScenario<MainActivity> scenario, String fieldName, boolean expected)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            AtomicReference<Boolean> actual = new AtomicReference<>(!expected);
            scenario.onActivity(activity -> {
                try { actual.set((Boolean) field(activity, fieldName)); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            if (actual.get() == expected) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for MainActivity." + fieldName + "=" + expected);
    }

    private static void awaitViewportWrite(Context context, String noteId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            NoteViewportStore.State value = NoteViewportStore.read(context, noteId);
            if (value != null) return;
            Thread.sleep(50);
        }
        throw new AssertionError("viewport-only save did not write its synthetic note sidecar");
    }

    private static void awaitStoredState(Context context, String noteId,
                                         NoteCanvasView.PersistedViewport expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            NoteViewportStore.State value = NoteViewportStore.read(context, noteId);
            if (value != null && expected != null &&
                    Math.abs(value.relativeZoom - expected.relativeZoom) < 0.002d &&
                    Math.abs(value.centerX - expected.centerX) < 0.5d &&
                    Math.abs(value.centerY - expected.centerY) < 0.5d) return;
            Thread.sleep(50);
        }
        throw new AssertionError("onPause did not persist the final synthetic viewport");
    }

    private static void awaitSaveAndViewportAck(ActivityScenario<MainActivity> scenario) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            scenario.onActivity(activity -> {
                try {
                    long live = activity.canvasForTest().getViewportRevision();
                    ready.set(!(Boolean) field(activity, "saveInFlight") &&
                            !(Boolean) field(activity, "viewportSaveInFlight") &&
                            ((Number) field(activity, "savedViewportRevision")).longValue() >= live &&
                            ((Number) field(activity, "lastSavedRevision")).longValue() >=
                                    ((Number) field(activity, "documentRevision")).longValue());
                } catch (Exception error) { throw new AssertionError(error); }
            });
            if (ready.get()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("content and viewport snapshots did not settle");
    }

    private static void zoomCanvas(NoteCanvasView canvas) {
        assertNotNull(canvas);
        float focusX = Math.max(1f, canvas.getWidth() / 2f);
        float focusY = Math.max(1f, canvas.getHeight() / 2f);
        try {
            set(canvas, "navigationGesture", true);
            set(canvas, "navigationLastFocusX", focusX);
            set(canvas, "navigationLastFocusY", focusY);
            set(canvas, "navigationLastSpan", 180f);
            Method update = NoteCanvasView.class.getDeclaredMethod("updateNavigation", MotionEvent.class);
            update.setAccessible(true);
            MotionEvent event = pinch(focusX, focusY, 260f);
            try { update.invoke(canvas, event); }
            finally { event.recycle(); }
            Method finish = NoteCanvasView.class.getDeclaredMethod("finishNavigation", boolean.class);
            finish.setAccessible(true);
            finish.invoke(canvas, false);
            assertTrue("the genuine Canvas navigation path changed its viewport revision",
                    canvas.getViewportRevision() > 0);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static void invokeForAssertion(Object target, String name) {
        try {
            invoke(target, name);
        } catch (Exception error) {
            throw new AssertionError("reflection call failed: " + name, error);
        }
    }

    private static MotionEvent pinch(float focusX, float focusY, float span) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = focusX;
            coordinates[i].y = focusY + (i == 0 ? -span / 2f : span / 2f);
            coordinates[i].pressure = 0.5f;
            coordinates[i].size = 0.02f;
        }
        return MotionEvent.obtain(1L, 2L, MotionEvent.ACTION_MOVE, 2, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private static void assertStateEquals(NoteCanvasView.PersistedViewport expected,
                                          NoteCanvasView.PersistedViewport actual) {
        assertNotNull(expected);
        assertNotNull(actual);
        assertEquals(expected.relativeZoom, actual.relativeZoom, 0.002d);
        assertEquals(expected.centerX, actual.centerX, 0.5d);
        assertEquals(expected.centerY, actual.centerY, 0.5d);
    }

    private static Object field(Object target, String name) throws Exception {
        Field value = target.getClass().getDeclaredField(name);
        value.setAccessible(true);
        return value.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... values)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, values);
    }
}
