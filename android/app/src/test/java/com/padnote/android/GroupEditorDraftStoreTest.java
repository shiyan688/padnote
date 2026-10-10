package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class GroupEditorDraftStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private File canonicalFolder(String name) throws Exception {
        return temporary.newFolder(name).getCanonicalFile();
    }

    @Test public void durableDraftRoundTripsAndOnlyExactSuccessfulAttemptCanClearIt()
            throws Exception {
        File filesRoot = canonicalFolder("private-files");
        String noteId = "note-draft-fixture";
        String body = body(noteId, "unsaved text");
        String session = UUID.randomUUID().toString();
        GroupEditorDraftStore.Draft first = draft(noteId, 7, body, session, 1, null);
        GroupEditorDraftStore.writeToFilesRoot(filesRoot, first);

        GroupEditorDraftStore.Draft reopened = GroupEditorDraftStore.readFromFilesRoot(
                filesRoot, noteId);
        assertNotNull(reopened);
        assertEquals(body, reopened.bodyJson);
        assertEquals(first.bodySha256, reopened.bodySha256);
        assertEquals(first.editorSessionId, reopened.editorSessionId);
        assertEquals(first.captureSequence, reopened.captureSequence);
        assertNull(reopened.supersedesAttemptId);
        assertTrue(reopened.matchesBase(noteId, first.lineage, first.baseRevision,
                first.baseDigest));
        assertFalse(reopened.matchesBase(noteId, first.lineage, "newer-revision",
                first.baseDigest));

        GroupEditorDraftStore.Draft newer = draft(noteId, 8,
                body(noteId, "newer unsaved text"), session, 2, null);
        GroupEditorDraftStore.writeToFilesRoot(filesRoot, newer);
        assertTrue("a later complete capture covers the earlier same-session capture",
                newer.covers(first));
        assertFalse("an earlier capture does not cover a later one",first.covers(newer));
        File directory = new File(filesRoot, "group-editor-drafts");
        JSONObject priorHeader = new JSONObject(new String(Files.readAllBytes(
                new File(directory, sha(noteId) + ".json.bak").toPath()), StandardCharsets.UTF_8));
        assertTrue("atomic header backup keeps its referenced full body", new File(directory,
                priorHeader.getString("bodyFile")).isFile());
        assertFalse("an older successful CAS must not clear a newer draft",
                GroupEditorDraftStore.clearIfMatchesFromFilesRoot(filesRoot, first));
        assertEquals(newer.attemptId, GroupEditorDraftStore.readFromFilesRoot(
                filesRoot, noteId).attemptId);
        assertTrue(GroupEditorDraftStore.clearIfMatchesFromFilesRoot(filesRoot, newer));
        assertNull(GroupEditorDraftStore.readFromFilesRoot(filesRoot, noteId));
        assertEquals("exact cleanup removes current and retained backup bodies", 0,
                new File(filesRoot, "group-editor-drafts").list().length);
    }

    @Test public void captureSequenceWinsWhenExecutorCompletionOrderIsReversed()
            throws Exception {
        File filesRoot = canonicalFolder("reverse-completion");
        String noteId = "note-draft-fixture";
        String session = UUID.randomUUID().toString();
        GroupEditorDraftStore.Draft capturedFirst = draft(noteId, 11,
                body(noteId, "captured first"), session, 1, null);
        GroupEditorDraftStore.Draft capturedLater = draft(noteId, 12,
                body(noteId, "captured later"), session, 2, null);

        // Model the Activity's two executors: the older captured body is held behind
        // a latch while the newer storage task reaches the journal first.
        CountDownLatch releaseOlderWorker = new CountDownLatch(1);
        ExecutorService draftExecutor = Executors.newSingleThreadExecutor();
        ExecutorService storageExecutor = Executors.newSingleThreadExecutor();
        Future<?> olderWrite = null;
        Future<?> newerWrite = null;
        Throwable primaryFailure = null;
        try {
            olderWrite = draftExecutor.submit(() -> {
                if (!releaseOlderWorker.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("older worker latch timed out");
                GroupEditorDraftStore.writeToFilesRoot(filesRoot, capturedFirst);
                return null;
            });
            newerWrite = storageExecutor.submit(() -> {
                GroupEditorDraftStore.writeToFilesRoot(filesRoot, capturedLater);
                return null;
            });
            newerWrite.get(5, TimeUnit.SECONDS);
            releaseOlderWorker.countDown();
            Future<?> completedOlderWrite = olderWrite;
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> completedOlderWrite.get(5, TimeUnit.SECONDS));
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            releaseOlderWorker.countDown();
            Throwable draftStopFailure = shutdownAndAwait(draftExecutor, olderWrite);
            Throwable storageStopFailure = shutdownAndAwait(storageExecutor, newerWrite);
            Throwable cleanupFailure = combine(draftStopFailure, storageStopFailure);
            if (primaryFailure instanceof InterruptedException
                    || cleanupFailure instanceof InterruptedException
                    || (cleanupFailure != null && cleanupFailure.getSuppressed().length > 0
                    && cleanupFailure.getSuppressed()[0] instanceof InterruptedException)) {
                Thread.currentThread().interrupt();
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else if (cleanupFailure instanceof Error) {
                    throw (Error) cleanupFailure;
                } else {
                    throw new AssertionError("race-test executor did not terminate before fixture cleanup",
                            cleanupFailure);
                }
            }
        }
        GroupEditorDraftStore.Draft retained = GroupEditorDraftStore.readFromFilesRoot(
                filesRoot, noteId);
        assertEquals(capturedLater.attemptId, retained.attemptId);
        assertEquals("captured later", new JSONObject(retained.bodyJson).getString("title"));
        assertTrue(GroupEditorDraftStore.clearIfMatchesFromFilesRoot(filesRoot, capturedLater));
        assertThrows("a cleared successful attempt must leave an in-process ordering tombstone",
                Exception.class, () -> GroupEditorDraftStore.writeToFilesRoot(
                        filesRoot, capturedFirst));
        assertThrows("a cleared attempt cannot be re-enqueued under its old sequence",
                Exception.class, () -> GroupEditorDraftStore.writeToFilesRoot(
                        filesRoot, capturedLater));
        assertNull(GroupEditorDraftStore.readFromFilesRoot(filesRoot, noteId));
    }

    @Test public void laterCaptureCanReplaceEarlierCaptureInSameSession() throws Exception {
        File filesRoot = canonicalFolder("forward-completion");
        String noteId = "note-draft-fixture";
        String session = UUID.randomUUID().toString();
        GroupEditorDraftStore.Draft earlier = draft(noteId, 20,
                body(noteId, "earlier"), session, 4, null);
        GroupEditorDraftStore.Draft later = draft(noteId, 21,
                body(noteId, "later"), session, 5, null);
        GroupEditorDraftStore.writeToFilesRoot(filesRoot, earlier);
        GroupEditorDraftStore.writeToFilesRoot(filesRoot, later);
        assertEquals(later.attemptId, GroupEditorDraftStore.readFromFilesRoot(
                filesRoot, noteId).attemptId);
    }

    @Test public void newSessionNeedsExplicitSameBaseSupersession() throws Exception {
        File filesRoot = canonicalFolder("session-boundary");
        String noteId = "note-draft-fixture";
        GroupEditorDraftStore.Draft oldSession = draft(noteId, 30,
                body(noteId, "old session"), UUID.randomUUID().toString(), 1, null);
        GroupEditorDraftStore.writeToFilesRoot(filesRoot, oldSession);

        GroupEditorDraftStore.Draft unaccepted = draft(noteId, 31,
                body(noteId, "unaccepted new session"), UUID.randomUUID().toString(), 1, null);
        assertThrows(Exception.class, () -> GroupEditorDraftStore.writeToFilesRoot(
                filesRoot, unaccepted));
        GroupEditorDraftStore.Draft retainedOld = GroupEditorDraftStore.readFromFilesRoot(
                filesRoot, noteId);
        assertEquals(oldSession.attemptId, retainedOld.attemptId);
        assertFalse("an unrelated prior-session body does not cover the rejected capture",
                retainedOld.covers(unaccepted));

        GroupEditorDraftStore.Draft accepted = GroupEditorDraftStore.create(noteId,
                oldSession.lineage, oldSession.baseRevision, oldSession.baseDigest, 32,
                UUID.randomUUID().toString(), 1, oldSession.attemptId,
                body(noteId, "explicitly loaded and edited"));
        GroupEditorDraftStore.writeToFilesRoot(filesRoot, accepted);
        assertEquals(accepted.attemptId, GroupEditorDraftStore.readFromFilesRoot(
                filesRoot, noteId).attemptId);

        GroupEditorDraftStore.Draft changedBase = GroupEditorDraftStore.create(noteId,
                oldSession.lineage, "newer-revision", oldSession.baseDigest, 33,
                UUID.randomUUID().toString(), 1, accepted.attemptId,
                body(noteId, "wrong base"));
        assertThrows(Exception.class, () -> GroupEditorDraftStore.writeToFilesRoot(
                filesRoot, changedBase));
        assertEquals(accepted.attemptId, GroupEditorDraftStore.readFromFilesRoot(
                filesRoot, noteId).attemptId);
    }

    @Test public void precisionParserRetainsGeometryNegativeZeroFromDraftJson() throws Exception {
        JSONObject parsed = NotePrecisionJsonParser.parseObject(
                "{\"id\":\"note-draft-fixture\",\"viewportZoom\":-0,"
                        + "\"updatedAt\":9007199254740993}");
        Object zoom = parsed.get("viewportZoom");
        assertTrue(zoom instanceof Double);
        assertEquals(Double.doubleToRawLongBits(-0.0d),
                Double.doubleToRawLongBits(((Number) zoom).doubleValue()));
        assertEquals("large timestamp integer remains exact", 9007199254740993L,
                parsed.getLong("updatedAt"));
    }

    @Test public void canonicalizesContextFilesRootAliasAndKeepsManagedChildrenScoped()
            throws Exception {
        File target = canonicalFolder("context-files-root");
        File alias = new File(target.getParentFile(), "context-files-alias");
        Files.createSymbolicLink(alias.toPath(), target.toPath());
        File normalized = GroupEditorDraftStore.canonicalContextFilesRoot(alias);
        assertEquals(target.getCanonicalFile(), normalized);
        String noteId = "note-draft-fixture";
        GroupEditorDraftStore.Draft draft = draft(noteId, 1, body(noteId, "alias root"),
                UUID.randomUUID().toString(), 1, null);
        GroupEditorDraftStore.writeToFilesRoot(normalized, draft);
        assertEquals(draft.attemptId, GroupEditorDraftStore.readFromFilesRoot(
                normalized, noteId).attemptId);
    }

    @Test public void refusesRedirectedDraftDirectoryAndLeaf() throws Exception {
        File parent = canonicalFolder("private-files");
        File outside = canonicalFolder("outside");
        File noteRoot = new File(parent, "group-editor-drafts");
        java.nio.file.Files.createSymbolicLink(noteRoot.toPath(), outside.toPath());
        String noteId = "note-draft-fixture";
        GroupEditorDraftStore.Draft draft = draft(noteId, 1, body(noteId, "safe"),
                UUID.randomUUID().toString(), 1, null);
        assertThrows(Exception.class,
                () -> GroupEditorDraftStore.writeToFilesRoot(parent, draft));

        File fresh = canonicalFolder("fresh-files");
        GroupEditorDraftStore.writeToFilesRoot(fresh, draft);
        File leafDirectory = new File(fresh, "group-editor-drafts");
        JSONObject header = new JSONObject(new String(Files.readAllBytes(new File(
                leafDirectory, sha(noteId) + ".json").toPath()), StandardCharsets.UTF_8));
        File bodyFile = new File(leafDirectory, header.getString("bodyFile"));
        File outsideFile = new File(outside, "target.json");
        Files.write(outsideFile.toPath(), new byte[] {1, 2, 3});
        Files.delete(bodyFile.toPath());
        Files.createSymbolicLink(bodyFile.toPath(), outsideFile.toPath());
        assertThrows(Exception.class,
                () -> GroupEditorDraftStore.writeToFilesRoot(fresh, draft));
        assertEquals(3, Files.size(outsideFile.toPath()));
    }

    @Test public void draftRejectsChangedBodyAndUnboundNote() throws Exception {
        File filesRoot = canonicalFolder("private-files");
        String noteId = "note-draft-fixture";
        GroupEditorDraftStore.Draft original = draft(noteId, 1, body(noteId, "preserve me"),
                UUID.randomUUID().toString(), 1, null);
        GroupEditorDraftStore.writeToFilesRoot(filesRoot, original);
        File directory = new File(filesRoot, "group-editor-drafts");
        File headerFile = new File(directory, sha(noteId) + ".json");
        JSONObject encoded = new JSONObject(new String(Files.readAllBytes(headerFile.toPath()),
                StandardCharsets.UTF_8));
        encoded.put("noteId", "note-other");
        Files.write(headerFile.toPath(), encoded.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class,
                () -> GroupEditorDraftStore.readFromFilesRoot(filesRoot, noteId));

        File bodyRoot = canonicalFolder("body-files");
        GroupEditorDraftStore.writeToFilesRoot(bodyRoot, original);
        File bodyDirectory = new File(bodyRoot, "group-editor-drafts");
        File bodyHeader = new File(bodyDirectory, sha(noteId) + ".json");
        JSONObject validHeader = new JSONObject(new String(Files.readAllBytes(bodyHeader.toPath()),
                StandardCharsets.UTF_8));
        File bodyFile = new File(bodyDirectory, validHeader.getString("bodyFile"));
        Files.write(bodyFile.toPath(), body(noteId, "tampered").getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class,
                () -> GroupEditorDraftStore.readFromFilesRoot(bodyRoot, noteId));
    }

    private static GroupEditorDraftStore.Draft draft(String noteId, long revision, String body,
                                                      String session, long sequence,
                                                      String supersedes)
            throws Exception {
        return GroupEditorDraftStore.create(noteId,
                "12345678-1234-4234-9234-123456789abc", "rev-base",
                repeat('a', 64), revision, session, sequence, supersedes, body);
    }

    private static String body(String noteId, String title) throws Exception {
        JSONObject value = new JSONObject();
        value.put("schemaVersion", 1);
        value.put("id", noteId);
        value.put("title", title);
        value.put("updatedAt", System.currentTimeMillis());
        value.put("pageCount", 1);
        value.put("strokes", new org.json.JSONArray());
        return value.toString();
    }

    private static String sha(String value) throws Exception {
        byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return result.toString();
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }

    private static Throwable shutdownAndAwait(ExecutorService executor, Future<?> future) {
        if (future != null && !future.isDone()) future.cancel(true);
        executor.shutdownNow();
        try {
            if (executor.awaitTermination(5, TimeUnit.SECONDS)) return null;
            executor.shutdownNow();
            if (executor.awaitTermination(5, TimeUnit.SECONDS)) return null;
            return new AssertionError("race-test executor still owns a worker at fixture cleanup");
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            return interrupted;
        }
    }

    private static Throwable combine(Throwable first, Throwable second) {
        if (first == null) return second;
        if (second != null) first.addSuppressed(second);
        return first;
    }
}
