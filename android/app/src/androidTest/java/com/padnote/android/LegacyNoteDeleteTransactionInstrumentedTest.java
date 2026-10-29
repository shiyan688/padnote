package com.padnote.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Exercises the legacy delete adapter only inside a unique test-owned files root. */
@RunWith(AndroidJUnit4.class)
public final class LegacyNoteDeleteTransactionInstrumentedTest {
    @Test public void precommitJournalFailureLeavesNoteIndexAndEveryTestSidecarUntouched()
            throws Exception {
        try (Fixture fixture = Fixture.create()) {
            byte[] noteBefore = readBytes(fixture.note);
            byte[] pdfBefore = readBytes(fixture.pdf);
            byte[] coverBefore = readBytes(fixture.cover);
            byte[] draftBefore = readBytes(fixture.draftFile);

            try {
                LegacyNoteDeleteTransaction.deleteForTest(fixture.context, fixture.noteId,
                        stage -> {
                            if (stage == LegacyDeleteJournal.Stage.TEMP_SYNCED)
                                throw new java.io.IOException("INJECTED_PRECOMMIT");
                        }, null);
                fail("pre-commit journal fault must not report deletion success");
            } catch (java.io.IOException expected) {
                assertEquals("INJECTED_PRECOMMIT", expected.getMessage());
            }

            assertArrayEquals(noteBefore, readBytes(fixture.note));
            assertArrayEquals(pdfBefore, readBytes(fixture.pdf));
            assertArrayEquals(coverBefore, readBytes(fixture.cover));
            assertArrayEquals(draftBefore, readBytes(fixture.draftFile));
            assertTrue(NoteStore.indexedNoteIds(fixture.context).contains(fixture.noteId));
            assertNull(LegacyDeleteJournal.readCommitted(fixture.root, fixture.noteId,
                    LegacyDeleteJournal.androidIdentityReader(),
                    LegacyDeleteJournal.androidDirectorySync()));
        }
    }

    @Test public void committedCleanupFailureIsTypedAndMarkerPreventsBackupResurrection()
            throws Exception {
        try (Fixture fixture = Fixture.create()) {
            File index = new File(fixture.root, "padnote-index.json");
            File oldBackup = new File(fixture.root, "padnote-index.json.bak");
            byte[] preDeleteIndex = readBytes(index);
            byte[] pdfBefore = readBytes(fixture.pdf);
            byte[] coverBefore = readBytes(fixture.cover);
            byte[] draftBefore = readBytes(fixture.draftFile);

            NoteStore.DeleteResult result = LegacyNoteDeleteTransaction.deleteForTest(
                    fixture.context, fixture.noteId, null, stage -> {
                        if (stage == LegacyNoteDeleteTransaction.CleanupStage.INDEX_WRITE)
                            throw new java.io.IOException("INJECTED_INDEX_CLEANUP");
                    });

            assertEquals(NoteStore.DeleteCommitStatus.COMMITTED, result.commitStatus);
            assertTrue(result.cleanupPending);
            assertTrue(result.cleanupCode.contains("INJECTED_INDEX_CLEANUP"));
            assertTrue("canonical note may be cleaned after durable logical commit", !fixture.note.exists());
            assertArrayEquals(pdfBefore, readBytes(fixture.pdf));
            assertArrayEquals(coverBefore, readBytes(fixture.cover));
            assertArrayEquals(draftBefore, readBytes(fixture.draftFile));
            assertNotNull(LegacyDeleteJournal.readCommitted(fixture.root, fixture.noteId,
                    LegacyDeleteJournal.androidIdentityReader(),
                    LegacyDeleteJournal.androidDirectorySync()));

            // A valid pre-delete index backup is deliberately restored as the sole index copy.
            writeBytes(oldBackup, preDeleteIndex);
            assertTrue(index.delete());
            List<String> visible = NoteStore.indexedNoteIds(fixture.context);
            assertFalse("durable marker filters the id even when an old index backup is read",
                    visible.contains(fixture.noteId));

            NoteStore.DeleteResult retried = LegacyNoteDeleteTransaction.retryCommittedCleanup(
                    fixture.context, fixture.noteId);
            assertEquals(NoteStore.DeleteCommitStatus.COMMITTED, retried.commitStatus);
            assertTrue("uncaptured sidecars are retained and reported", retried.cleanupPending);
            assertTrue(retried.cleanupCode.contains("DELETE_NOTE_SIDECARS_RETAINED"));
            assertTrue(retried.cleanupCode.contains("DELETE_PRIVATE_MATERIAL_RETAINED"));
            assertArrayEquals(pdfBefore, readBytes(fixture.pdf));
            assertArrayEquals(coverBefore, readBytes(fixture.cover));
            assertArrayEquals(draftBefore, readBytes(fixture.draftFile));
            assertFalse(NoteStore.indexedNoteIds(fixture.context).contains(fixture.noteId));
        }
    }

    @Test public void uncertainPublishedMarkerLeavesMaterialsUntilVerifiedRetry()
            throws Exception {
        try (Fixture fixture = Fixture.create()) {
            byte[] noteBefore = readBytes(fixture.note);
            byte[] indexBefore = readBytes(new File(fixture.root, "padnote-index.json"));
            byte[] pdfBefore = readBytes(fixture.pdf);
            byte[] coverBefore = readBytes(fixture.cover);
            byte[] draftBefore = readBytes(fixture.draftFile);
            NoteStore.DeleteResult uncertain = LegacyNoteDeleteTransaction.deleteForTest(
                    fixture.context, fixture.noteId, stage -> {
                        if (stage == LegacyDeleteJournal.Stage.CANONICAL_PROMOTED)
                            throw new java.io.IOException("INJECTED_POST_PROMOTION");
                    }, null);

            assertEquals(NoteStore.DeleteCommitStatus.UNCERTAIN, uncertain.commitStatus);
            assertTrue(fixture.note.isFile());
            assertArrayEquals(noteBefore, readBytes(fixture.note));
            assertArrayEquals(indexBefore, readBytes(new File(fixture.root, "padnote-index.json")));
            assertArrayEquals(pdfBefore, readBytes(fixture.pdf));
            assertArrayEquals(coverBefore, readBytes(fixture.cover));
            assertArrayEquals(draftBefore, readBytes(fixture.draftFile));
            assertFalse("verified commit marker hides the note from the public index projection",
                    NoteStore.indexedNoteIds(fixture.context).contains(fixture.noteId));

            NoteStore.DeleteResult retried = LegacyNoteDeleteTransaction.retryCommittedCleanup(
                    fixture.context, fixture.noteId);
            assertEquals(NoteStore.DeleteCommitStatus.COMMITTED, retried.commitStatus);
            assertFalse(fixture.note.exists());
            assertArrayEquals(pdfBefore, readBytes(fixture.pdf));
            assertArrayEquals(coverBefore, readBytes(fixture.cover));
            assertArrayEquals(draftBefore, readBytes(fixture.draftFile));
            assertFalse(NoteStore.indexedNoteIds(fixture.context).contains(fixture.noteId));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final File root;
        final File appDataRoot;
        final Context context;
        final String noteId;
        final File note, pdf, cover, draftFile;

        private Fixture(File root, File appDataRoot, Context context, String noteId,
                        File note, File pdf, File cover, File draftFile) {
            this.root = root;
            this.appDataRoot = appDataRoot;
            this.context = context;
            this.noteId = noteId;
            this.note = note;
            this.pdf = pdf;
            this.cover = cover;
            this.draftFile = draftFile;
        }

        static Fixture create() throws Exception {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            File appDataRoot = new File(base.getCacheDir(), "legacy-delete-" + UUID.randomUUID());
            if (!appDataRoot.mkdir()) throw new java.io.IOException("TEST_ROOT_CREATE_FAILED");
            File root = new File(appDataRoot, "files");
            File cache = new File(appDataRoot, "cache");
            if (!root.mkdir() || !cache.mkdir()) {
                deleteTree(appDataRoot);
                throw new java.io.IOException("TEST_APP_DIR_CREATE_FAILED");
            }
            if (!appDataRoot.setReadable(false, false) || !appDataRoot.setWritable(false, false)
                    || !appDataRoot.setExecutable(false, false) || !appDataRoot.setReadable(true, true)
                    || !appDataRoot.setWritable(true, true) || !appDataRoot.setExecutable(true, true)
                    || !root.setReadable(false, false) || !root.setWritable(false, false)
                    || !root.setExecutable(false, false) || !root.setReadable(true, true)
                    || !root.setWritable(true, true) || !root.setExecutable(true, true)) {
                deleteTree(appDataRoot);
                throw new java.io.IOException("TEST_APP_DIR_PERMISSIONS_FAILED");
            }
            ApplicationInfo applicationInfo = new ApplicationInfo(base.getApplicationInfo());
            applicationInfo.dataDir = appDataRoot.getAbsolutePath();
            Context isolated = new ContextWrapper(base) {
                @Override public File getFilesDir() { return root; }
                @Override public File getCacheDir() { return cache; }
                @Override public File getDataDir() { return appDataRoot; }
                @Override public ApplicationInfo getApplicationInfo() { return applicationInfo; }
                @Override public Context getApplicationContext() { return this; }
            };
            try {
                NoteStore.Entry entry = NoteStore.create(isolated, "Synthetic delete transaction");
                File note = NoteStore.noteFileForBackup(isolated, entry.id);
                File pdf = NoteStore.pdfFile(isolated, entry.id);
                byte[] pdfBytes = "%PDF-1.4 synthetic isolated sidecar".getBytes(StandardCharsets.US_ASCII);
                writeBytes(pdf, pdfBytes);

                Bitmap bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(0xff2878c8);
                try { CoverStore.assign(isolated, entry.id, bitmap); }
                finally { bitmap.recycle(); }
                File cover = CoverStore.coverFile(
                        new File(root, NoteStore.notesDirectoryName()), entry.id);

                ManualTextDraftStore drafts = new ManualTextDraftStore(isolated);
                ManualTextDraftStore.Draft draft = new ManualTextDraftStore.Draft(
                        entry.id, "test-flow", "test-fragment", "synthetic saved draft",
                        NoteTextBox.Format.MARKDOWN.name(), 16f, 1.3f, true,
                        ManualTextDraftStore.sourceFingerprint(""), UUID.randomUUID().toString(), 1L);
                ManualTextDraftStore.SaveResult saved = drafts.save(draft, null);
                assertTrue("fixture draft must be durably created", saved.saved());
                File draftFile = new File(new File(root, "manual-text-drafts"), sha256(entry.id) + ".json");
                assertTrue(draftFile.isFile());
                assertTrue(NoteStore.indexedNoteIds(isolated).contains(entry.id));
                return new Fixture(root, appDataRoot, isolated, entry.id, note, pdf, cover, draftFile);
            } catch (Throwable error) {
                deleteTree(appDataRoot);
                throw error;
            }
        }

        @Override public void close() {
            deleteTree(appDataRoot);
        }
    }

    private static String sha256(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(64);
        for (byte valueByte : hash) out.append(String.format(Locale.ROOT, "%02x", valueByte & 0xff));
        return out.toString();
    }

    private static void writeBytes(File file, byte[] data) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new java.io.IOException("TEST_PARENT_CREATE_FAILED");
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(data);
            out.flush();
            out.getFD().sync();
        }
    }

    private static byte[] readBytes(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) >= 0;) {
                if (count > 0) out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static void deleteTree(File root) {
        if (root == null || !root.exists()) return;
        File[] children = root.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        // This is a UUID-owned temporary test root, never the application's real files directory.
        root.delete();
    }
}
