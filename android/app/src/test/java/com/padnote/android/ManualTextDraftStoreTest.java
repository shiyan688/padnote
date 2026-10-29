package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;

public final class ManualTextDraftStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void unicodeCheckpointOver100KiBLoadsFromNewStoreInstance() throws Exception {
        File root = temporary.newFolder("files");
        ManualTextDraftStore store = new ManualTextDraftStore(root);
        String source = "纸面🌿e\u0301".repeat(35_000);
        assertTrue(source.getBytes(StandardCharsets.UTF_8).length > 100 * 1024);
        ManualTextDraftStore.Draft first = draft("note-unicode", source, UUID.randomUUID().toString(), 1);
        ManualTextDraftStore.SaveResult saved = store.save(first, null);
        assertEquals(ManualTextDraftStore.SaveStatus.SAVED, saved.status);

        ManualTextDraftStore.LoadResult loaded = new ManualTextDraftStore(root).load("note-unicode");
        assertEquals(ManualTextDraftStore.LoadStatus.READY, loaded.status);
        assertEquals(source, loaded.draft.source);
        assertEquals(first.baseSourceFingerprint, loaded.draft.baseSourceFingerprint);
        assertEquals(first.token(), loaded.token);
    }

    @Test public void checkpointAcceptsMoreThanOneHundredThousandUtf16UnitsWithinStoreLimit() throws Exception {
        File root = temporary.newFolder("large-source");
        ManualTextDraftStore store = new ManualTextDraftStore(root);
        String source = "界".repeat(120_000);
        assertTrue(source.length() > 100_000);
        assertTrue(source.getBytes(StandardCharsets.UTF_8).length < ManualTextDraftStore.MAX_SOURCE_UTF8_BYTES);
        assertEquals(ManualTextDraftStore.SaveStatus.SAVED,
                store.save(draft("note-large", source, UUID.randomUUID().toString(), 1), null).status);
        assertEquals(source, new ManualTextDraftStore(root).load("note-large").draft.source);
    }

    @Test public void saveAndClearAreExactCasAcrossSessions() throws Exception {
        File root = temporary.newFolder("cas");
        ManualTextDraftStore store = new ManualTextDraftStore(root);
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
        assertEquals(ManualTextDraftStore.ClearStatus.CLEARED,
                store.clearIfMatches("note-cas", newToken));
        assertEquals(ManualTextDraftStore.LoadStatus.NONE, store.load("note-cas").status);
    }

    @Test public void oversizedAndMalformedRecordsAreRetainedUntilExactExplicitDiscard() throws Exception {
        File root = temporary.newFolder("invalid");
        ManualTextDraftStore store = new ManualTextDraftStore(root);
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
        ManualTextDraftStore normal = new ManualTextDraftStore(root);
        ManualTextDraftStore.Draft old = draft("note-fault", "previous source",
                UUID.randomUUID().toString(), 1);
        ManualTextDraftStore.DraftToken oldToken = normal.save(old, null).token;
        ManualTextDraftStore failing = new ManualTextDraftStore(root, stage -> {
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

    @Test public void corruptHeadIsFailClosedAndExplicitDiscardCannotResurrectBackup() throws Exception {
        File root = temporary.newFolder("corrupt-head");
        ManualTextDraftStore store = new ManualTextDraftStore(root);
        ManualTextDraftStore.Draft draft = draft("note-corrupt-head", "valid older draft",
                UUID.randomUUID().toString(), 1);
        assertEquals(ManualTextDraftStore.SaveStatus.SAVED, store.save(draft, null).status);
        File head = recordFile(root, "note-corrupt-head");
        File backup = new File(head.getPath() + ".bak");
        Files.copy(head.toPath(), backup.toPath());
        byte[] damaged = "{damaged current record}".getBytes(StandardCharsets.UTF_8);
        Files.write(head.toPath(), damaged);

        ManualTextDraftStore.LoadResult result = new ManualTextDraftStore(root)
                .load("note-corrupt-head");
        assertEquals("a valid .bak must not conceal a damaged present head",
                ManualTextDraftStore.LoadStatus.INVALID, result.status);
        assertEquals(hex(sha(new String(damaged, StandardCharsets.UTF_8))), result.rawSha256);
        assertEquals(ManualTextDraftStore.ClearStatus.CLEARED,
                store.clearInvalidIfMatches("note-corrupt-head", result.rawSha256));
        assertEquals("clearing the known record set removes its backup too",
                ManualTextDraftStore.LoadStatus.NONE,
                new ManualTextDraftStore(root).load("note-corrupt-head").status);
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
        ManualTextDraftStore aliasedStore = new ManualTextDraftStore(rootAlias);
        ManualTextDraftStore.Draft saved = draft("note-root-alias", "kept", UUID.randomUUID().toString(), 1);
        assertEquals(ManualTextDraftStore.SaveStatus.SAVED, aliasedStore.save(saved, null).status);
        assertEquals("kept", new ManualTextDraftStore(realRoot).load("note-root-alias").draft.source);

        File directoryRoot = temporary.newFolder("directory-symlink-root");
        File externalDirectory = temporary.newFolder("external-draft-directory");
        Files.createSymbolicLink(new File(directoryRoot, "manual-text-drafts").toPath(),
                externalDirectory.toPath());
        assertEquals("a symlink inside the trusted root is rejected",
                ManualTextDraftStore.LoadStatus.IO_ERROR,
                new ManualTextDraftStore(directoryRoot).load("note-directory-link").status);

        File recordRoot = temporary.newFolder("record-symlink-root");
        File draftDirectory = new File(recordRoot, "manual-text-drafts");
        assertTrue(draftDirectory.mkdir());
        File target = new File(temporary.getRoot(), "outside-record-target.json");
        Files.write(target.toPath(), "outside".getBytes(StandardCharsets.UTF_8));
        File linkedRecord = recordFile(recordRoot, "note-record-link");
        Files.createSymbolicLink(linkedRecord.toPath(), target.toPath());
        assertEquals("a symlinked record inside the draft directory is rejected",
                ManualTextDraftStore.LoadStatus.IO_ERROR,
                new ManualTextDraftStore(recordRoot).load("note-record-link").status);
    }

    private static ManualTextDraftStore.Draft draft(String noteId, String source,
                                                    String sessionId, long generation) {
        return new ManualTextDraftStore.Draft(noteId, "flow-1", "fragment-1", source,
                NoteTextBox.Format.MARKDOWN.name(), 18f, 1.28f, false,
                ManualTextDraftStore.sourceFingerprint("base source"), sessionId, generation);
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
