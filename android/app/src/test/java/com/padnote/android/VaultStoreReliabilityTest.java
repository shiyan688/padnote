package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class VaultStoreReliabilityTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void sameTitleDifferentSourcesNeverOverwriteEachOther() throws Exception {
        VaultStore store = new VaultStore(temporary.newFolder("same-title"));
        String first = store.write("note-first", "同名笔记", 1, 1,
                Arrays.asList("第一份"));
        String second = store.write("note-second", "同名笔记", 1, 2,
                Arrays.asList("第二份"));

        assertNotEquals(first, second);
        assertTrue(store.read(first).contains("第一份"));
        assertTrue(store.read(second).contains("第二份"));
        assertEquals(2, store.list().size());
    }

    @Test public void renamedSourceIsRetiredOnlyAfterNewCommitSucceeds() throws Exception {
        File root = temporary.newFolder("rename");
        AtomicReference<NoteStore.WriteStage> armed = new AtomicReference<>();
        VaultStore store = new VaultStore(root, stage -> {
            if (armed.compareAndSet(stage, null)) throw new Exception("injected " + stage);
        });
        String oldName = "旧标题.md";
        Files.write(new File(root, oldName).toPath(),
                markdown("note-rename", "旧标题", "旧内容").getBytes(StandardCharsets.UTF_8));
        armed.set(NoteStore.WriteStage.OLD_VERSION_BACKED_UP);
        try {
            store.write("note-rename", "新标题", 1, 2, Arrays.asList("新内容"));
            fail("injected failure expected");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("injected"));
        }
        assertTrue(new File(root, oldName).isFile());
        assertTrue(store.read(oldName).contains("旧内容"));

        String newName = store.write("note-rename", "新标题", 1, 2,
                Arrays.asList("新内容"));
        assertTrue(store.read(newName).contains("新内容"));
        assertFalse(new File(root, oldName).exists());
        assertEquals(1, store.list().size());
    }

    @Test public void failedSameTargetUpdateReopensReadableOldRevision() throws Exception {
        File root = temporary.newFolder("same-target");
        AtomicReference<NoteStore.WriteStage> armed = new AtomicReference<>();
        VaultStore store = new VaultStore(root, stage -> {
            if (armed.compareAndSet(stage, null)) throw new Exception("injected " + stage);
        });
        String fileName = store.write("note-stable", "标题", 1, 1,
                Arrays.asList("旧内容"));
        armed.set(NoteStore.WriteStage.OLD_VERSION_BACKED_UP);
        try {
            store.write("note-stable", "标题", 1, 2, Arrays.asList("新内容"));
            fail("injected failure expected");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("injected"));
        }
        VaultStore reopened = new VaultStore(root);
        assertTrue(reopened.read(fileName).contains("旧内容"));
        assertEquals("note-stable", reopened.list().get(0).noteId);
    }

    @Test public void failedManualSourceEditKeepsLastReadableDocument() throws Exception {
        File root = temporary.newFolder("manual-edit");
        AtomicReference<NoteStore.WriteStage> armed = new AtomicReference<>();
        VaultStore store = new VaultStore(root, stage -> {
            if (armed.compareAndSet(stage, null)) throw new Exception("injected " + stage);
        });
        String fileName = store.write("note-edit", "可编辑源码", 1, 1,
                Arrays.asList("原正文"));
        armed.set(NoteStore.WriteStage.OLD_VERSION_BACKED_UP);
        try {
            store.replaceRaw(fileName, markdown("note-edit", "可编辑源码", "修改后正文"));
            fail("injected edit failure expected");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("injected"));
        }
        assertTrue(new VaultStore(root).read(fileName).contains("原正文"));

        store.replaceRaw(fileName, markdown("note-edit", "可编辑源码", "修改后正文"));
        assertTrue(new VaultStore(root).read(fileName).contains("修改后正文"));

        try {
            store.replaceRaw(fileName, markdown("another-note", "可编辑源码", "错误来源"));
            fail("manual edit changed the frozen source identity");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("来源 ID"));
        }
        assertTrue(new VaultStore(root).read(fileName).contains("修改后正文"));
    }

    @Test public void legacyTitleFileRemainsReadableUntilItsSourceIsReplaced() throws Exception {
        File root = temporary.newFolder("legacy");
        File legacy = new File(root, "旧格式.md");
        Files.write(legacy.toPath(), markdown("legacy-note", "旧格式", "旧正文")
                .getBytes(StandardCharsets.UTF_8));
        VaultStore store = new VaultStore(root);
        assertEquals("legacy-note", store.list().get(0).noteId);
        assertTrue(store.read("旧格式.md").contains("旧正文"));

        String replacement = store.write("legacy-note", "新格式", 1, 2,
                Arrays.asList("新正文"));
        assertFalse(legacy.exists());
        assertTrue(store.read(replacement).contains("新正文"));
    }

    @Test public void deletingUpdatedNoteRemovesBackupSoRestartCannotRestoreIt() throws Exception {
        File root = temporary.newFolder("delete-family");
        VaultStore store = new VaultStore(root);
        String fileName = store.write("note-delete", "待删除", 1, 1,
                Arrays.asList("第一版"));
        store.write("note-delete", "待删除", 1, 2, Arrays.asList("第二版"));
        assertTrue(new File(root, fileName + ".bak").isFile());

        store.delete(fileName);

        VaultStore reopened = new VaultStore(root);
        assertTrue(reopened.list().isEmpty());
        assertFalse(new File(root, fileName).exists());
        assertFalse(new File(root, fileName + ".bak").exists());
        assertFalse(new File(root, fileName + ".tmp").exists());
    }

    @Test public void unicodeTitleUsesSafeIdentityNameAndLegacyUnicodeRemainsReadable() throws Exception {
        File root = temporary.newFolder("unicode-title");
        VaultStore store = new VaultStore(root);
        String fileName = store.write("note-unicode", "Lecture 1 📝（上）", 1, 1,
                Arrays.asList("新正文"));
        assertTrue(fileName.matches("note-[a-f0-9]{64}\\.md"));
        assertTrue(store.read(fileName).contains("📝（上）"));

        String legacyName = "Lecture 1 📝（旧）.md";
        Files.write(new File(root, legacyName).toPath(),
                markdown("legacy-unicode", "Lecture 1 📝（旧）", "旧正文")
                        .getBytes(StandardCharsets.UTF_8));
        assertTrue(store.read(legacyName).contains("旧正文"));
        assertEquals(2, store.list().size());
        assertFails(() -> store.read("../" + legacyName));
    }

    private static String markdown(String noteId, String title, String body) {
        return "---\n" + "title: " + title + "\n" + "note-id: " + noteId + "\n"
                + "pages: 1\n" + "digitized-epoch: 1\n" + "source-modified: 1\n"
                + "---\n\n# " + title + "\n\n" + body + "\n";
    }

    private interface ThrowingAction { void run() throws Exception; }

    private static void assertFails(ThrowingAction action) throws Exception {
        try { action.run(); fail("expected unsafe path rejection"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("文件")); }
    }
}
