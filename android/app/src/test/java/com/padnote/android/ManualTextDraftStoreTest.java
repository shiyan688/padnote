package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.UUID;

public final class ManualTextDraftStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void unicodeCheckpointOver100KiBLoadsFromNewStoreInstance() throws Exception {
        File root = temporary.newFolder("files");
        ManualTextDraftStore store = testStore(root);
        String source = "纸面🌿e\u0301".repeat(35_000);
        assertTrue(source.getBytes(StandardCharsets.UTF_8).length > 100 * 1024);
        ManualTextDraftStore.Draft first = draft("note-unicode", source, UUID.randomUUID().toString(), 1);
        ManualTextDraftStore.SaveResult saved = store.save(first, null);
        assertEquals(ManualTextDraftStore.SaveStatus.SAVED, saved.status);

        ManualTextDraftStore.LoadResult loaded = testStore(root).load("note-unicode");
        assertEquals(ManualTextDraftStore.LoadStatus.READY, loaded.status);
        assertEquals(source, loaded.draft.source);
        assertEquals(first.baseSourceFingerprint, loaded.draft.baseSourceFingerprint);
        assertEquals(first.token(), loaded.token);
    }

    @Test public void checkpointAcceptsMoreThanOneHundredThousandUtf16UnitsWithinStoreLimit() throws Exception {
        File root = temporary.newFolder("large-source");
        ManualTextDraftStore store = testStore(root);
        String source = "界".repeat(120_000);
        assertTrue(source.length() > 100_000);
        assertTrue(source.getBytes(StandardCharsets.UTF_8).length < ManualTextDraftStore.MAX_SOURCE_UTF8_BYTES);
        assertEquals(ManualTextDraftStore.SaveStatus.SAVED,
                store.save(draft("note-large", source, UUID.randomUUID().toString(), 1), null).status);
        assertEquals(source, testStore(root).load("note-large").draft.source);
    }

    @Test public void saveAndClearAreExactCasAcrossSessions() throws Exception {
        File root = temporary.newFolder("cas");
        ManualTextDraftStore store = testStore(root);
        String sessionA = UUID.randomUUID().toString();
        ManualTextDraftStore.Draft a1 = draft("note-cas", "first", sessionA, 1);
        ManualTextDraftStore.DraftToken tokenA1 = store.save(a1, null).token;
        ManualTextDraftStore.Draft a2 = draft("note-cas", "second", sessionA, 2);
        ManualTextDraftStore.DraftToken tokenA2 = store.save(a2, tokenA1).token;
        assertEquals(ManualTextDraftStore.SaveStatus.CONFLICT,
                store.save(draft("note-cas", "stale", sessionA, 3), tokenA1).status);

        ManualTextDraftStore.Draft otherSession = draft("note-cas", "new session",
                UUID.randomUUID().toString(), 1);
        ManualTextDraftStore.DraftToken newToken = store.save(otherSession, tokenA2).token;
        assertEquals(ManualTextDraftStore.ClearStatus.STALE,
                store.clearIfMatches("note-cas", tokenA2));
        assertEquals("new session", store.load("note-cas").draft.source);
        assertEquals("cancellable valid-draft cleanup leaves the current checkpoint intact",
                ManualTextDraftStore.ClearStatus.CANCELLED,
                store.clearIfMatches("note-cas", newToken, () -> true));
        assertEquals("new session", store.load("note-cas").draft.source);
        assertEquals(ManualTextDraftStore.ClearStatus.CLEARED,
                store.clearIfMatches("note-cas", newToken));
        assertEquals(ManualTextDraftStore.LoadStatus.NONE, store.load("note-cas").status);
    }

    @Test public void oversizedAndMalformedRecordsAreRetainedUntilExactExplicitDiscard() throws Exception {
        File root = temporary.newFolder("invalid");
        ManualTextDraftStore store = testStore(root);
        String large = "x".repeat(ManualTextDraftStore.MAX_SOURCE_UTF16_UNITS + 1);
        assertEquals(ManualTextDraftStore.SaveStatus.TOO_LARGE,
                store.save(draft("note-invalid", large, UUID.randomUUID().toString(), 1), null).status);
        File target = recordFile(root, "note-invalid");
        assertTrue(target.getParentFile().mkdirs());
        byte[] malformed = "{\"schemaVersion\":1,\"source\":\"unfinished\"}".getBytes(StandardCharsets.UTF_8);
        Files.write(target.toPath(), malformed);
        ManualTextDraftStore.LoadResult invalid = store.load("note-invalid");
        assertEquals(ManualTextDraftStore.LoadStatus.INVALID, invalid.status);
        assertNotNull(invalid.rawSha256);
        assertEquals(ManualTextDraftStore.SaveStatus.INVALID,
                store.save(draft("note-invalid", "replacement", UUID.randomUUID().toString(), 1), null).status);
        assertEquals(ManualTextDraftStore.ClearStatus.STALE,
                store.clearInvalidIfMatches("note-invalid", "0".repeat(64)));
        assertTrue("invalid bytes remain until explicit matching discard", target.exists());
        assertEquals(ManualTextDraftStore.ClearStatus.CLEARED,
                store.clearInvalidIfMatches("note-invalid", invalid.rawSha256));
        assertFalseExists(target);
    }

    @Test public void atomicWriteFaultKeepsPreviousCheckpointReadable() throws Exception {
        File root = temporary.newFolder("atomic-fault");
        ManualTextDraftStore normal = testStore(root);
        ManualTextDraftStore.Draft old = draft("note-fault", "previous source",
                UUID.randomUUID().toString(), 1);
        ManualTextDraftStore.DraftToken oldToken = normal.save(old, null).token;
        ManualTextDraftStore failing = testStore(root, stage -> {
            if (stage == NoteStore.WriteStage.OLD_VERSION_BACKED_UP) throw new java.io.IOException("injected");
        });
        ManualTextDraftStore.SaveResult failed = failing.save(draft("note-fault", "replacement",
                oldToken.sessionId, 2), oldToken);
        assertEquals(ManualTextDraftStore.SaveStatus.IO_ERROR, failed.status);
        ManualTextDraftStore.LoadResult recovered = normal.load("note-fault");
        assertEquals(ManualTextDraftStore.LoadStatus.READY, recovered.status);
        assertEquals("previous source", recovered.draft.source);
        assertEquals(oldToken, recovered.token);
    }

    @Test public void identicalCheckpointReturnsCurrentTokenWithoutEncodingOrWriting() throws Exception {
        File root = temporary.newFolder("idempotent-checkpoint");
        ManualTextDraftStore normal = testStore(root);
        String session = UUID.randomUUID().toString();
        ManualTextDraftStore.Draft first = draft("note-idempotent", "same complete draft", session, 1);
        ManualTextDraftStore.DraftToken token = normal.save(first, null).token;
        java.util.concurrent.atomic.AtomicInteger writeStages = new java.util.concurrent.atomic.AtomicInteger();
        ManualTextDraftStore faulting = testStore(root, stage -> {
            writeStages.incrementAndGet();
            throw new java.io.IOException("checkpoint should not write");
        });

        ManualTextDraftStore.SaveResult duplicate = faulting.checkpointIfChanged(
                draft("note-idempotent", "same complete draft", session, 2), token);
        assertEquals(ManualTextDraftStore.SaveStatus.SAVED, duplicate.status);
        assertEquals("idempotent success returns the current durable token", token, duplicate.token);
        assertEquals("no write transaction stage ran", 0, writeStages.get());
        assertEquals(token, normal.load("note-idempotent").token);

        java.util.concurrent.atomic.AtomicInteger strictWriteStages = new java.util.concurrent.atomic.AtomicInteger();
        ManualTextDraftStore strictFaulting = testStore(root, stage -> {
            strictWriteStages.incrementAndGet();
            throw new java.io.IOException("legacy strict save must still write");
        });
        assertEquals("the original save API retains strict write behavior",
                ManualTextDraftStore.SaveStatus.IO_ERROR,
                strictFaulting.save(draft("note-idempotent", "same complete draft", session, 2), token).status);
        assertTrue(strictWriteStages.get() > 0);

        ManualTextDraftStore.Draft changed = draft("note-idempotent", "next source revision", session, 3);
        ManualTextDraftStore.SaveResult failure = faulting.checkpointIfChanged(changed, token);
        assertEquals("a changed source must attempt a real write", ManualTextDraftStore.SaveStatus.IO_ERROR,
                failure.status);
        assertTrue(writeStages.get() > 0);
        assertEquals("the failed write leaves the prior durable draft intact", token,
                normal.load("note-idempotent").token);
        assertEquals("same complete draft", normal.load("note-idempotent").draft.source);

        ManualTextDraftStore.SaveResult saved = normal.checkpointIfChanged(changed, token);
        assertEquals(ManualTextDraftStore.SaveStatus.SAVED, saved.status);
        org.junit.Assert.assertNotEquals(token, saved.token);
        assertEquals(ManualTextDraftStore.ClearStatus.STALE,
                normal.clearIfMatches("note-idempotent", token));
        assertEquals("next source revision", normal.load("note-idempotent").draft.source);
    }

    @Test public void changedStyleAndSessionDoNotUseIdempotentFastPath() throws Exception {
        for (String change : new String[]{"style", "session"}) {
            File root = temporary.newFolder("changed-" + change);
            ManualTextDraftStore normal = testStore(root);
            String session = UUID.randomUUID().toString();
            ManualTextDraftStore.Draft original = draft("note-" + change, "same source", session, 1);
            ManualTextDraftStore.DraftToken token = normal.save(original, null).token;
            ManualTextDraftStore.Draft candidate = "style".equals(change)
                    ? new ManualTextDraftStore.Draft(original.noteId, original.flowId,
                    original.fragmentId, original.source, NoteTextBox.Format.LATEX.name(),
                    19f, 1.4f, original.newlyInserted, original.baseSourceFingerprint,
                    original.sessionId, 2L)
                    : new ManualTextDraftStore.Draft(original.noteId, original.flowId,
                    original.fragmentId, original.source, original.format, original.fontSize,
                    original.lineHeight, original.newlyInserted, original.baseSourceFingerprint,
                    UUID.randomUUID().toString(), 1L);
            java.util.concurrent.atomic.AtomicInteger writeStages = new java.util.concurrent.atomic.AtomicInteger();
            ManualTextDraftStore faulting = testStore(root, stage -> {
                writeStages.incrementAndGet();
                throw new java.io.IOException("changed payload must write");
            });
            ManualTextDraftStore.SaveResult rejectedWrite = faulting.checkpointIfChanged(candidate, token);
            assertEquals(change + " change is not reported saved when its write fails",
                    ManualTextDraftStore.SaveStatus.IO_ERROR, rejectedWrite.status);
            assertTrue(change + " change attempted atomic write", writeStages.get() > 0);
            assertEquals(token, normal.load(original.noteId).token);
            assertEquals(original.format, normal.load(original.noteId).draft.format);

            ManualTextDraftStore.SaveResult changed = normal.checkpointIfChanged(candidate, token);
            assertEquals(ManualTextDraftStore.SaveStatus.SAVED, changed.status);
            org.junit.Assert.assertNotEquals("new content/style/session advances record identity", token,
                    changed.token);
            assertEquals(ManualTextDraftStore.ClearStatus.STALE,
                    normal.clearIfMatches(original.noteId, token));
            assertEquals(candidate.sessionId, normal.load(original.noteId).draft.sessionId);
            assertEquals(candidate.format, normal.load(original.noteId).draft.format);
        }
    }

    @Test public void idempotentCheckpointRejectsMissingCorruptAndStaleState() throws Exception {
        File root = temporary.newFolder("checkpoint-fail-closed");
        ManualTextDraftStore store = testStore(root);
        ManualTextDraftStore.Draft other = draft("note-other", "other note", UUID.randomUUID().toString(), 1);
        ManualTextDraftStore.DraftToken otherToken = store.save(other, null).token;
        ManualTextDraftStore.Draft missing = draft("note-missing", "same", otherToken.sessionId, 2);
        assertEquals(ManualTextDraftStore.SaveStatus.CONFLICT,
                store.checkpointIfChanged(missing, otherToken).status);

        File corrupt = recordFile(root, "note-corrupt-checkpoint");
        assertTrue(corrupt.getParentFile().exists() || corrupt.getParentFile().mkdirs());
        Files.write(corrupt.toPath(), "not a draft".getBytes(StandardCharsets.UTF_8));
        assertEquals(ManualTextDraftStore.SaveStatus.INVALID,
                store.checkpointIfChanged(draft("note-corrupt-checkpoint", "replacement",
                        UUID.randomUUID().toString(), 1), null).status);

        String session = UUID.randomUUID().toString();
        ManualTextDraftStore.Draft first = draft("note-stale-checkpoint", "same payload", session, 1);
        ManualTextDraftStore.DraftToken stale = store.save(first, null).token;
        ManualTextDraftStore.Draft newer = draft("note-stale-checkpoint", "same payload",
                UUID.randomUUID().toString(), 1);
        ManualTextDraftStore.DraftToken current = store.save(newer, stale).token;
        ManualTextDraftStore.SaveResult staleAttempt = store.checkpointIfChanged(
                draft("note-stale-checkpoint", "same payload", newer.sessionId, 2), stale);
        assertEquals(ManualTextDraftStore.SaveStatus.CONFLICT, staleAttempt.status);
        assertEquals(current, store.load("note-stale-checkpoint").token);
    }

    @Test public void corruptHeadIsFailClosedAndExplicitDiscardCannotResurrectBackup() throws Exception {
        File root = temporary.newFolder("corrupt-head");
        ManualTextDraftStore store = testStore(root);
        ManualTextDraftStore.Draft draft = draft("note-corrupt-head", "valid older draft",
                UUID.randomUUID().toString(), 1);
        assertEquals(ManualTextDraftStore.SaveStatus.SAVED, store.save(draft, null).status);
        File head = recordFile(root, "note-corrupt-head");
        File backup = new File(head.getPath() + ".bak");
        Files.copy(head.toPath(), backup.toPath());
        byte[] damaged = "{damaged current record}".getBytes(StandardCharsets.UTF_8);
        Files.write(head.toPath(), damaged);

        ManualTextDraftStore.LoadResult result = testStore(root)
                .load("note-corrupt-head");
        assertEquals("a valid .bak must not conceal a damaged present head",
                ManualTextDraftStore.LoadStatus.INVALID, result.status);
        assertEquals(hex(sha(new String(damaged, StandardCharsets.UTF_8))), result.rawSha256);
        assertEquals(ManualTextDraftStore.ClearStatus.CLEARED,
                store.clearInvalidIfMatches("note-corrupt-head", result.rawSha256));
        assertEquals("clearing the known record set removes its backup too",
                ManualTextDraftStore.LoadStatus.NONE,
                testStore(root).load("note-corrupt-head").status);
    }

    @Test public void sourceFingerprintUsesExactUtf8AndRejectsMalformedSurrogates() {
        assertEquals(hex(sha("A\u0301")), ManualTextDraftStore.sourceFingerprint("A\u0301"));
        assertEquals(hex(sha("Á")), ManualTextDraftStore.sourceFingerprint("Á"));
        org.junit.Assert.assertNotEquals(ManualTextDraftStore.sourceFingerprint("A\u0301"),
                ManualTextDraftStore.sourceFingerprint("Á"));
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> ManualTextDraftStore.sourceFingerprint("\ud800"));
    }

    @Test public void canonicalizesTrustedFilesRootAliasButRejectsInternalDirectoryAndRecordSymlinks()
            throws Exception {
        File realRoot = temporary.newFolder("real-files-root");
        File rootAlias = new File(temporary.getRoot(), "files-root-alias");
        Files.createSymbolicLink(rootAlias.toPath(), realRoot.toPath());
        ManualTextDraftStore aliasedStore = testStore(rootAlias);
        ManualTextDraftStore.Draft saved = draft("note-root-alias", "kept", UUID.randomUUID().toString(), 1);
        assertEquals(ManualTextDraftStore.SaveStatus.SAVED, aliasedStore.save(saved, null).status);
        assertEquals("kept", testStore(realRoot).load("note-root-alias").draft.source);

        File directoryRoot = temporary.newFolder("directory-symlink-root");
        File externalDirectory = temporary.newFolder("external-draft-directory");
        Files.createSymbolicLink(new File(directoryRoot, "manual-text-drafts").toPath(),
                externalDirectory.toPath());
        assertEquals("a symlink inside the trusted root is rejected",
                ManualTextDraftStore.LoadStatus.IO_ERROR,
                testStore(directoryRoot).load("note-directory-link").status);

        File recordRoot = temporary.newFolder("record-symlink-root");
        File draftDirectory = new File(recordRoot, "manual-text-drafts");
        assertTrue(draftDirectory.mkdir());
        File target = new File(temporary.getRoot(), "outside-record-target.json");
        Files.write(target.toPath(), "outside".getBytes(StandardCharsets.UTF_8));
        File linkedRecord = recordFile(recordRoot, "note-record-link");
        Files.createSymbolicLink(linkedRecord.toPath(), target.toPath());
        assertEquals("a symlinked record inside the draft directory is rejected",
                ManualTextDraftStore.LoadStatus.IO_ERROR,
                testStore(recordRoot).load("note-record-link").status);
    }

    @Test public void oversizedInvalidHeadWithoutRawShaCanBeExplicitlyInspectedAndCleared() throws Exception {
        File root = temporary.newFolder("large-invalid-head");
        String noteId = "note-large-invalid-head";
        File head = recordFile(root, noteId);
        assertTrue(head.getParentFile().mkdirs());
        try (java.io.RandomAccessFile sparse = new java.io.RandomAccessFile(head, "rw")) {
            sparse.setLength(ManualTextDraftStore.MAX_RECORD_BYTES + 1L);
        }
        ManualTextDraftStore store = testStore(root);
        ManualTextDraftStore.LoadResult invalid = store.load(noteId);
        assertEquals(ManualTextDraftStore.LoadStatus.INVALID, invalid.status);
        assertEquals("legacy bounded decode has no raw digest for oversized records", null,
                invalid.rawSha256);

        ManualTextDraftStore.Inspection inspected = store.inspectInvalidForDiscard(noteId, () -> false);
        assertEquals(ManualTextDraftStore.InspectionStatus.READY, inspected.status);
        assertNotNull(inspected.token);
        assertTrue(inspected.totalBytes > ManualTextDraftStore.MAX_RECORD_BYTES);
        assertEquals(ManualTextDraftStore.ClearStatus.CLEARED,
                store.clearInvalidIfMatches(noteId, inspected.token, () -> false));
        assertEquals("no .bak/.tmp can resurrect a discarded family", ManualTextDraftStore.LoadStatus.NONE,
                testStore(root).load(noteId).status);
        assertTrue(!head.exists());
    }

    @Test public void oversizedSidecarIsIncludedInTokenAndRemovedOnlyByExactDiscard() throws Exception {
        File root = temporary.newFolder("large-invalid-sidecar");
        String noteId = "note-large-invalid-sidecar";
        File head = recordFile(root, noteId);
        assertTrue(head.getParentFile().mkdirs());
        Files.write(head.toPath(), "damaged-head".getBytes(StandardCharsets.UTF_8));
        File backup = new File(head.getPath() + ".bak");
        try (java.io.RandomAccessFile sparse = new java.io.RandomAccessFile(backup, "rw")) {
            sparse.setLength(ManualTextDraftStore.MAX_RECORD_BYTES + 4096L);
        }
        ManualTextDraftStore store = testStore(root);
        ManualTextDraftStore.Inspection inspected = store.inspectInvalidForDiscard(noteId, () -> false);
        assertEquals(ManualTextDraftStore.InspectionStatus.READY, inspected.status);
        assertTrue(inspected.totalBytes > backup.length());
        assertEquals(ManualTextDraftStore.ClearStatus.CLEARED,
                store.clearInvalidIfMatches(noteId, inspected.token, () -> false));
        assertEquals(ManualTextDraftStore.LoadStatus.NONE, testStore(root).load(noteId).status);
        assertTrue(!head.exists());
        assertTrue(!backup.exists());
    }

    @Test public void validHeadClearChecksOversizedBackupAndTempAsOneExactFamily() throws Exception {
        File root = temporary.newFolder("valid-head-large-sidecars");
        String noteId = "note-valid-head-large-sidecars";
        ManualTextDraftStore store = testStore(root);

        ManualTextDraftStore.Draft first = draft(noteId, "prior valid source",
                UUID.randomUUID().toString(), 1);
        ManualTextDraftStore.DraftToken staleToken = store.save(first, null).token;
        ManualTextDraftStore.Draft current = draft(noteId, "current valid source",
                UUID.randomUUID().toString(), 1);
        ManualTextDraftStore.DraftToken exactToken = store.save(current, staleToken).token;
        assertNotNull(exactToken);
        assertEquals("the head is a valid record before sidecars are added",
                ManualTextDraftStore.LoadStatus.READY, store.load(noteId).status);

        File head = recordFile(root, noteId);
        File backup = new File(head.getPath() + ".bak");
        File temp = new File(head.getPath() + ".tmp");
        try (java.io.RandomAccessFile sparse = new java.io.RandomAccessFile(backup, "rw")) {
            sparse.setLength(ManualTextDraftStore.MAX_RECORD_BYTES + 4096L);
        }
        try (java.io.RandomAccessFile sparse = new java.io.RandomAccessFile(temp, "rw")) {
            sparse.setLength(ManualTextDraftStore.MAX_RECORD_BYTES + 8192L);
        }

        assertEquals("an obsolete valid checkpoint token cannot clear any family member",
                ManualTextDraftStore.ClearStatus.STALE, store.clearIfMatches(noteId, staleToken));
        assertTrue(head.isFile());
        assertTrue(backup.isFile());
        assertTrue(temp.isFile());

        assertEquals("the exact current head token clears the entire valid family",
                ManualTextDraftStore.ClearStatus.CLEARED, store.clearIfMatches(noteId, exactToken));
        assertEquals("neither large sidecar nor valid head can resurrect the checkpoint",
                ManualTextDraftStore.LoadStatus.NONE, testStore(root).load(noteId).status);
        assertTrue(!head.exists());
        assertTrue(!backup.exists());
        assertTrue(!temp.exists());
    }

    @Test public void invalidDiscardTokenDetectsSidecarPresenceContentAndAbsenceChanges() throws Exception {
        for (String change : new String[]{"appeared", "changed", "disappeared"}) {
            File root = temporary.newFolder("family-change-" + change);
            String noteId = "note-family-" + change;
            File head = recordFile(root, noteId);
            assertTrue(head.getParentFile().mkdirs());
            Files.write(head.toPath(), "invalid head".getBytes(StandardCharsets.UTF_8));
            File backup = new File(head.getPath() + ".bak");
            if ("disappeared".equals(change) || "changed".equals(change)) {
                Files.write(backup.toPath(), "old sidecar".getBytes(StandardCharsets.UTF_8));
            }
            ManualTextDraftStore store = testStore(root);
            ManualTextDraftStore.Inspection inspected = store.inspectInvalidForDiscard(noteId, () -> false);
            assertEquals(change, ManualTextDraftStore.InspectionStatus.READY, inspected.status);
            assertNotNull(inspected.token);

            if ("appeared".equals(change)) {
                Files.write(new File(head.getPath() + ".tmp").toPath(),
                        "new temp sidecar".getBytes(StandardCharsets.UTF_8));
            } else if ("changed".equals(change)) {
                Files.write(backup.toPath(), "different sidecar contents".getBytes(StandardCharsets.UTF_8));
            } else {
                assertTrue(backup.delete());
            }

            assertEquals(change, ManualTextDraftStore.ClearStatus.STALE,
                    store.clearInvalidIfMatches(noteId, inspected.token, () -> false));
            assertTrue("the invalid head is never removed after the family changes", head.exists());
            assertEquals(ManualTextDraftStore.LoadStatus.INVALID, store.load(noteId).status);
        }
    }

    @Test public void replacedHeadOrNewValidDraftCannotBeDeletedByOldInspectionToken() throws Exception {
        File root = temporary.newFolder("head-replaced-invalid");
        String noteId = "note-head-replaced";
        File head = recordFile(root, noteId);
        assertTrue(head.getParentFile().mkdirs());
        Files.write(head.toPath(), "first damaged head".getBytes(StandardCharsets.UTF_8));
        ManualTextDraftStore store = testStore(root);
        ManualTextDraftStore.Inspection inspected = store.inspectInvalidForDiscard(noteId, () -> false);
        assertEquals(ManualTextDraftStore.InspectionStatus.READY, inspected.status);
        Files.write(head.toPath(), "replacement damaged head".getBytes(StandardCharsets.UTF_8));
        assertEquals(ManualTextDraftStore.ClearStatus.STALE,
                store.clearInvalidIfMatches(noteId, inspected.token, () -> false));
        assertEquals("replacement damaged head", new String(Files.readAllBytes(head.toPath()),
                StandardCharsets.UTF_8));

        File validRoot = temporary.newFolder("valid-new-draft");
        ManualTextDraftStore validStore = testStore(validRoot);
        assertEquals(ManualTextDraftStore.SaveStatus.SAVED,
                validStore.save(draft(noteId, "new valid draft", UUID.randomUUID().toString(), 1), null).status);
        Files.copy(recordFile(validRoot, noteId).toPath(), head.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals("a valid authoritative head makes an old invalid-discard token stale",
                ManualTextDraftStore.ClearStatus.STALE,
                store.clearInvalidIfMatches(noteId, inspected.token, () -> false));
        ManualTextDraftStore.LoadResult stillValid = testStore(root).load(noteId);
        assertEquals(ManualTextDraftStore.LoadStatus.READY, stillValid.status);
        assertEquals("new valid draft", stillValid.draft.source);
    }

    @Test public void cancelledAndOverBudgetInspectionNeverDeleteWithoutACompleteToken() throws Exception {
        File root = temporary.newFolder("inspect-cancel-limit");
        String cancelId = "note-inspect-cancel";
        File cancelHead = recordFile(root, cancelId);
        assertTrue(cancelHead.getParentFile().mkdirs());
        Files.write(cancelHead.toPath(), "damaged".getBytes(StandardCharsets.UTF_8));
        ManualTextDraftStore store = testStore(root);
        ManualTextDraftStore.Inspection cancelled = store.inspectInvalidForDiscard(cancelId, () -> true);
        assertEquals(ManualTextDraftStore.InspectionStatus.CANCELLED, cancelled.status);
        assertEquals(null, cancelled.token);
        assertTrue(cancelHead.exists());
        ManualTextDraftStore.Inspection ready = store.inspectInvalidForDiscard(cancelId, () -> false);
        assertEquals(ManualTextDraftStore.InspectionStatus.READY, ready.status);
        assertEquals(ManualTextDraftStore.ClearStatus.CANCELLED,
                store.clearInvalidIfMatches(cancelId, ready.token, () -> true));
        assertTrue("cancel before delete preserves the entire family", cancelHead.exists());

        String largeId = "note-over-family-budget";
        File largeHead = recordFile(root, largeId);
        assertTrue(largeHead.getParentFile().exists());
        Files.write(largeHead.toPath(), "damaged".getBytes(StandardCharsets.UTF_8));
        File hugeTemp = new File(largeHead.getPath() + ".tmp");
        try (java.io.RandomAccessFile sparse = new java.io.RandomAccessFile(hugeTemp, "rw")) {
            sparse.setLength(ManualTextDraftStore.MAX_INVALID_FAMILY_BYTES + 1L);
        }
        ManualTextDraftStore.Inspection tooLarge = store.inspectInvalidForDiscard(largeId, () -> false);
        assertEquals(ManualTextDraftStore.InspectionStatus.TOO_LARGE, tooLarge.status);
        assertEquals(null, tooLarge.token);
        assertEquals(ManualTextDraftStore.ClearStatus.INVALID,
                store.clearInvalidIfMatches(largeId, tooLarge.token, () -> false));
        assertTrue(largeHead.exists());
        assertTrue(hugeTemp.exists());
    }

    @Test public void validDraftInspectionHasNoDiscardTokenAndLegacySaveRemainsStrict() throws Exception {
        File root = temporary.newFolder("valid-inspect-legacy-save");
        ManualTextDraftStore store = testStore(root);
        String noteId = "note-valid-inspect";
        ManualTextDraftStore.Draft first = draft(noteId, "valid draft", UUID.randomUUID().toString(), 1);
        ManualTextDraftStore.DraftToken token = store.save(first, null).token;
        ManualTextDraftStore.Inspection inspection = store.inspectInvalidForDiscard(noteId, () -> false);
        assertEquals(ManualTextDraftStore.InspectionStatus.VALID, inspection.status);
        assertEquals(null, inspection.token);
        assertEquals(ManualTextDraftStore.ClearStatus.INVALID,
                store.clearInvalidIfMatches(noteId, inspection.token, () -> false));
        assertEquals("valid draft", store.load(noteId).draft.source);

        ManualTextDraftStore.Draft identical = draft(noteId, "valid draft", first.sessionId, 2);
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        ManualTextDraftStore faulting = testStore(root, stage -> {
            writes.incrementAndGet();
            throw new IOException("strict save must still write");
        });
        ManualTextDraftStore.SaveResult strictSave = faulting.save(identical, token);
        assertEquals("strict save retains write/error semantics",
                ManualTextDraftStore.SaveStatus.IO_ERROR, strictSave.status);
        assertTrue("strict save attempted a write", writes.get() > 0);
        assertEquals("failed strict save retains the previous valid checkpoint", token,
                store.load(noteId).token);
    }

    @Test public void invalidDiscardTokenIsBoundToCanonicalDraftDirectory() throws Exception {
        File rootA = temporary.newFolder("discard-root-a");
        File rootAlias = new File(temporary.getRoot(), "discard-root-a-alias");
        Files.createSymbolicLink(rootAlias.toPath(), rootA.toPath());
        String noteId = "note-bound-token";
        File headA = recordFile(rootA, noteId);
        assertTrue(headA.getParentFile().mkdirs());
        byte[] invalidBytes = "invalid but inspectable".getBytes(StandardCharsets.UTF_8);
        Files.write(headA.toPath(), invalidBytes);

        ManualTextDraftStore storeA = testStore(rootA);
        ManualTextDraftStore.Inspection inspection = storeA.inspectInvalidForDiscard(noteId, () -> false);
        assertEquals(ManualTextDraftStore.InspectionStatus.READY, inspection.status);

        File rootB = temporary.newFolder("discard-root-b");
        File headB = recordFile(rootB, noteId);
        assertTrue(headB.getParentFile().mkdirs());
        Files.write(headB.toPath(), invalidBytes);
        ManualTextDraftStore storeB = testStore(rootB);
        assertEquals("identical note and bytes in another root cannot use this token",
                ManualTextDraftStore.ClearStatus.STALE,
                storeB.clearInvalidIfMatches(noteId, inspection.token, () -> false));
        assertTrue(headB.exists());

        assertEquals("canonical alias to the same root remains valid",
                ManualTextDraftStore.ClearStatus.CLEARED,
                testStore(rootAlias).clearInvalidIfMatches(noteId, inspection.token, () -> false));
        assertEquals(ManualTextDraftStore.LoadStatus.NONE, storeA.load(noteId).status);
    }

    @Test public void sameBytesAtomicHeadReplacementInvalidatesOldDiscardToken() throws Exception {
        File root = temporary.newFolder("same-bytes-replacement");
        String noteId = "note-same-bytes-replacement";
        File head = recordFile(root, noteId);
        assertTrue(head.getParentFile().mkdirs());
        byte[] bytes = "same invalid bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(head.toPath(), bytes);
        File backup = new File(head.getPath() + ".bak");
        File temp = new File(head.getPath() + ".tmp");
        byte[] backupBytes = "backup remains".getBytes(StandardCharsets.UTF_8);
        byte[] tempBytes = "temp remains".getBytes(StandardCharsets.UTF_8);
        Files.write(backup.toPath(), backupBytes);
        Files.write(temp.toPath(), tempBytes);
        java.nio.file.attribute.FileTime originalMtime = Files.getLastModifiedTime(head.toPath());

        ManualTextDraftStore store = testStore(root);
        ManualTextDraftStore.Inspection inspection = store.inspectInvalidForDiscard(noteId, () -> false);
        assertEquals(ManualTextDraftStore.InspectionStatus.READY, inspection.status);

        File replacement = new File(head.getPath() + ".new-inode");
        Files.write(replacement.toPath(), bytes);
        Files.setLastModifiedTime(replacement.toPath(), originalMtime);
        Files.move(replacement.toPath(), head.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);

        assertEquals("same content and mtime on a replacement inode still invalidates the token",
                ManualTextDraftStore.ClearStatus.STALE,
                store.clearInvalidIfMatches(noteId, inspection.token, () -> false));
        assertTrue(head.exists());
        org.junit.Assert.assertArrayEquals(bytes, Files.readAllBytes(head.toPath()));
        assertTrue(backup.exists());
        assertTrue(temp.exists());
        org.junit.Assert.assertArrayEquals(backupBytes, Files.readAllBytes(backup.toPath()));
        org.junit.Assert.assertArrayEquals(tempBytes, Files.readAllBytes(temp.toPath()));
    }

    @Test public void inspectionDeadlineAndUnavailableIdentityFailClosed() throws Exception {
        File root = temporary.newFolder("inspection-deadline");
        String noteId = "note-deadline";
        File head = recordFile(root, noteId);
        assertTrue(head.getParentFile().mkdirs());
        Files.write(head.toPath(), "invalid but retained".getBytes(StandardCharsets.UTF_8));

        java.util.concurrent.atomic.AtomicBoolean delayed = new java.util.concurrent.atomic.AtomicBoolean();
        ManualTextDraftStore.FileIdentityReader slowReader = file -> {
            if (delayed.compareAndSet(false, true)) {
                try {
                    Thread.sleep(5_100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("identity read interrupted", interrupted);
                }
            }
            return readIdentity(file);
        };
        ManualTextDraftStore timed = new ManualTextDraftStore(root, NoteStore.NO_WRITE_FAULTS, slowReader);
        ManualTextDraftStore.Inspection timedOut = timed.inspectInvalidForDiscard(noteId, () -> false);
        assertEquals(ManualTextDraftStore.InspectionStatus.TIMED_OUT, timedOut.status);
        assertEquals(null, timedOut.token);
        assertTrue("deadline failure preserves the damaged head", head.exists());

        ManualTextDraftStore unavailable = new ManualTextDraftStore(root, NoteStore.NO_WRITE_FAULTS,
                file -> null);
        ManualTextDraftStore.Inspection identityError =
                unavailable.inspectInvalidForDiscard(noteId, () -> false);
        assertEquals(ManualTextDraftStore.InspectionStatus.IO_ERROR, identityError.status);
        assertEquals(null, identityError.token);
        assertTrue("missing identity for an existing file is not treated as absence", head.exists());
    }

    @Test public void validBackupCheckpointCanBeClearedWhenHeadIsAbsent() throws Exception {
        File root = temporary.newFolder("valid-backup-recovery");
        ManualTextDraftStore store = testStore(root);
        String noteId = "note-valid-backup";
        assertEquals(ManualTextDraftStore.SaveStatus.SAVED,
                store.save(draft(noteId, "backup draft", UUID.randomUUID().toString(), 1), null).status);
        File head = recordFile(root, noteId);
        File backup = new File(head.getPath() + ".bak");
        Files.move(head.toPath(), backup.toPath());

        ManualTextDraftStore.LoadResult recovered = store.load(noteId);
        assertEquals(ManualTextDraftStore.LoadStatus.READY, recovered.status);
        assertEquals("backup draft", recovered.draft.source);
        assertEquals(backup, recovered.observedFile);
        assertEquals(ManualTextDraftStore.ClearStatus.CLEARED,
                store.clearIfMatches(noteId, recovered.token, () -> false));
        assertEquals(ManualTextDraftStore.LoadStatus.NONE, testStore(root).load(noteId).status);
        assertTrue(!backup.exists());
    }

    @Test public void validTempCanBeClearedAfterInvalidBackupRecovery() throws Exception {
        File root = temporary.newFolder("valid-temp-recovery");
        ManualTextDraftStore store = testStore(root);
        String noteId = "note-valid-temp";
        assertEquals(ManualTextDraftStore.SaveStatus.SAVED,
                store.save(draft(noteId, "temp draft", UUID.randomUUID().toString(), 1), null).status);
        File head = recordFile(root, noteId);
        File backup = new File(head.getPath() + ".bak");
        File temp = new File(head.getPath() + ".tmp");
        Files.move(head.toPath(), temp.toPath());
        Files.write(backup.toPath(), "damaged backup".getBytes(StandardCharsets.UTF_8));

        ManualTextDraftStore.LoadResult recovered = store.load(noteId);
        assertEquals(ManualTextDraftStore.LoadStatus.READY, recovered.status);
        assertEquals("temp draft", recovered.draft.source);
        assertEquals(temp, recovered.observedFile);
        assertEquals(ManualTextDraftStore.ClearStatus.CLEARED,
                store.clearIfMatches(noteId, recovered.token, () -> false));
        assertEquals(ManualTextDraftStore.LoadStatus.NONE, testStore(root).load(noteId).status);
        assertTrue(!temp.exists());
        assertTrue(!backup.exists());
    }

    @Test public void newAuthoritativeHeadPreventsClearingPreviouslyLoadedValidSidecar() throws Exception {
        File root = temporary.newFolder("sidecar-new-head");
        ManualTextDraftStore store = testStore(root);
        String noteId = "note-sidecar-new-head";
        assertEquals(ManualTextDraftStore.SaveStatus.SAVED,
                store.save(draft(noteId, "recovered sidecar", UUID.randomUUID().toString(), 1), null).status);
        File head = recordFile(root, noteId);
        File backup = new File(head.getPath() + ".bak");
        Files.move(head.toPath(), backup.toPath());
        ManualTextDraftStore.LoadResult recovered = store.load(noteId);
        assertEquals(ManualTextDraftStore.LoadStatus.READY, recovered.status);
        assertEquals(backup, recovered.observedFile);

        Files.write(head.toPath(), validRecordBytes(noteId, "new authoritative head"));
        assertEquals(ManualTextDraftStore.ClearStatus.STALE,
                store.clearIfMatches(noteId, recovered.token, () -> false));
        assertEquals("new authoritative head", testStore(root).load(noteId).draft.source);
        assertTrue("the old backup is retained when a newer head appears", backup.exists());
    }

    private static ManualTextDraftStore.Draft draft(String noteId, String source,
                                                    String sessionId, long generation) {
        return new ManualTextDraftStore.Draft(noteId, "flow-1", "fragment-1", source,
                NoteTextBox.Format.MARKDOWN.name(), 18f, 1.28f, false,
                ManualTextDraftStore.sourceFingerprint("base source"), sessionId, generation);
    }

    private static ManualTextDraftStore testStore(File root) {
        return testStore(root, NoteStore.NO_WRITE_FAULTS);
    }

    private static ManualTextDraftStore testStore(File root, NoteStore.WriteFaultInjector faultInjector) {
        return new ManualTextDraftStore(root, faultInjector, ManualTextDraftStoreTest::readIdentity);
    }

    private static ManualTextDraftStore.FileIdentity readIdentity(File file) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            Object key = attributes.fileKey();
            if (key == null) {
                try {
                    java.util.Map<String, Object> unix = Files.readAttributes(file.toPath(),
                            "unix:dev,ino", LinkOption.NOFOLLOW_LINKS);
                    key = unix.get("dev") + ":" + unix.get("ino");
                } catch (UnsupportedOperationException | IllegalArgumentException unsupported) {
                    throw new IOException("filesystem does not expose stable file identity", unsupported);
                }
            }
            return new ManualTextDraftStore.FileIdentity(attributes.isRegularFile(),
                    attributes.isSymbolicLink(), attributes.size(),
                    attributes.lastModifiedTime().toMillis() / 1000L, String.valueOf(key));
        } catch (java.nio.file.NoSuchFileException missing) {
            return null;
        }
    }

    private byte[] validRecordBytes(String noteId, String source) throws Exception {
        File otherRoot = temporary.newFolder("record-source-" + UUID.randomUUID());
        ManualTextDraftStore store = testStore(otherRoot);
        assertEquals(ManualTextDraftStore.SaveStatus.SAVED,
                store.save(draft(noteId, source, UUID.randomUUID().toString(), 1), null).status);
        return Files.readAllBytes(recordFile(otherRoot, noteId).toPath());
    }

    private static File recordFile(File root, String noteId) throws Exception {
        return new File(new File(root, "manual-text-drafts"),
                hex(MessageDigest.getInstance("SHA-256").digest(noteId.getBytes(StandardCharsets.UTF_8))) + ".json");
    }

    private static byte[] sha(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static void assertFalseExists(File file) { assertTrue(!file.exists()); }

}
