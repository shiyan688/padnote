package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class NoteStoreRecoveryTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private static final NoteStore.StoredFileValidator VERSION = value -> {
        if (!(value.equals("old") || value.equals("new"))) throw new Exception("invalid");
    };

    @Test public void recoveryValidatesBeforeChoosingAndPreservesCorruptTarget() throws Exception {
        File target = temporary.newFile("note.json");
        write(target, "broken");
        File backup = new File(target.getParentFile(), target.getName() + ".bak");
        File pending = new File(target.getParentFile(), target.getName() + ".tmp");
        write(backup, "old");
        write(pending, "new");

        NoteStore.recoverAtomicFile(target, VERSION);

        assertEquals("old", read(target));
        assertFalse(backup.exists());
        assertFalse(pending.exists());
        assertTrue(hasFileStartingWith(target.getParentFile(), "note.json.corrupt-"));
        assertTrue(hasFileStartingWith(target.getParentFile(), "note.json.tmp.corrupt-"));
    }

    @Test public void everyInterruptedCommitReopensOldOrNewCompleteRevision() throws Exception {
        for (NoteStore.WriteStage failedStage : NoteStore.WriteStage.values()) {
            File directory = temporary.newFolder(failedStage.name());
            File target = new File(directory, "note.json");
            NoteStore.writeAtomic(target, "old", VERSION, NoteStore.NO_WRITE_FAULTS);
            try {
                NoteStore.writeAtomic(target, "new", VERSION, stage -> {
                    if (stage == failedStage) throw new Exception("injected " + stage);
                });
                fail("fault should interrupt commit");
            } catch (Exception expected) {
                assertTrue(expected.getMessage().contains("injected"));
            }
            NoteStore.recoverAtomicFile(target, VERSION);
            String reopened = read(target);
            if (failedStage == NoteStore.WriteStage.NEW_VERSION_PROMOTED) {
                assertEquals("new", reopened);
            } else {
                assertEquals("old", reopened);
            }
        }
    }

    @Test public void noValidCandidateIsKeptForManualRecovery() throws Exception {
        File target = temporary.newFile("bad.json");
        write(target, "bad-target");
        File backup = new File(target.getParentFile(), target.getName() + ".bak");
        write(backup, "bad-backup");
        try {
            NoteStore.recoverAtomicFile(target, VERSION);
            fail("invalid candidates must not be treated as recovered");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("原文件已保留"));
        }
        assertEquals("bad-target", read(target));
        assertEquals("bad-backup", read(backup));
    }

    @Test public void utf8LimitCountsEncodedBytesIncludingChinese() {
        assertTrue(NoteStore.encodedLengthWithinLimit("中", 3));
        assertFalse(NoteStore.encodedLengthWithinLimit("中", 2));
        assertTrue(NoteStore.encodedLengthWithinLimit("a中", 4));
        assertFalse(NoteStore.encodedLengthWithinLimit("a中", 3));
    }

    @Test public void oversizedUtf8ValueCannotReplaceReadableRevision() throws Exception {
        File target = new File(temporary.newFolder("limit"), "note.json");
        NoteStore.writeAtomic(target, "old", VERSION, NoteStore.NO_WRITE_FAULTS);
        try {
            NoteStore.writeAtomic(target, "中文", value -> { },
                    NoteStore.NO_WRITE_FAULTS, 5);
            fail("six UTF-8 bytes must exceed the injected five-byte limit");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("50 MB"));
        }
        assertEquals("old", read(target));
        NoteStore.recoverAtomicFile(target, VERSION);
        assertEquals("old", read(target));
    }

    private static void write(File file, String value) throws Exception {
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static boolean hasFileStartingWith(File directory, String prefix) {
        File[] files = directory.listFiles((ignored, name) -> name.startsWith(prefix));
        return files != null && files.length > 0;
    }
}
