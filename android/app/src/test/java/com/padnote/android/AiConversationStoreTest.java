package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;

public final class AiConversationStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private static AiConversationStore.Binding binding() {
        return new AiConversationStore.Binding("author", "pdf", "profile", 3,
                NoteTool.Permission.READ_ONLY.name(), "");
    }

    @Test public void visibleTimelineAndWireHistoryRoundTripSeparately() throws Exception {
        AiConversationStore store = new AiConversationStore(temporary.newFolder(), null);
        AiConversationStore.Snapshot created = store.create("note-a",
                Collections.singletonList(new AiConversationStore.VisibleEntry(
                        "message", "assistant", "只用于查看", "模型 A", false)),
                Collections.singletonList(new OpenAiCompatibleClient.Message(
                        "user", "实际发送")), null, null, binding(), null, false);

        AiConversationStore.Snapshot loaded = store.load("note-a");
        assertEquals(created.conversationId, loaded.conversationId);
        assertEquals("只用于查看", loaded.visibleTimeline.get(0).text);
        assertEquals("实际发送", loaded.wireHistory.get(0).content);
        assertFalse(loaded.toJson().toString().contains("apiKey"));
    }

    @Test public void clearRejectsLateSnapshotAndNewConversationGetsNewIdentity()
            throws Exception {
        AiConversationStore store = new AiConversationStore(temporary.newFolder(), null);
        AiConversationStore.Snapshot old = store.create("note-a", Collections.emptyList(),
                Collections.emptyList(), null, null, binding(), null, false);
        store.clear("note-a");
        assertNull(store.load("note-a"));
        assertThrows(Exception.class, () -> store.save(old.next(Collections.emptyList(),
                Collections.emptyList(), null, null, binding(), null, false)));
        assertThrows(Exception.class, () -> store.preserveUnsaved(old));
        File retained = store.recoveryFile("note-a");
        assertTrue(retained == null || !retained.getName().contains("unsaved"));

        AiConversationStore.Snapshot fresh = store.create("note-a", Collections.emptyList(),
                Collections.emptyList(), null, null, binding(), null, false);
        assertTrue(fresh.generation > old.generation);
        assertFalse(fresh.conversationId.equals(old.conversationId));
    }

    @Test public void corruptSessionIsRetainedAndCreateWillNotOverwriteIt() throws Exception {
        File directory = temporary.newFolder();
        Files.write(new File(directory, "note-a.json").toPath(),
                "{broken".getBytes(StandardCharsets.UTF_8));
        AiConversationStore store = new AiConversationStore(directory, null);

        assertThrows(Exception.class, () -> store.load("note-a"));
        File recovery = store.recoveryFile("note-a");
        assertTrue(recovery != null && recovery.isFile());
        assertThrows(Exception.class, () -> store.create("note-a",
                Collections.emptyList(), Collections.emptyList(), null, null,
                binding(), null, false));
    }

    @Test public void unsavedRecoveryIsRetainedAndCreateWillNotHideIt() throws Exception {
        File directory = temporary.newFolder();
        Files.write(new File(directory, "note-a.json.unsaved").toPath(),
                "{retained recovery}".getBytes(StandardCharsets.UTF_8));
        AiConversationStore store = new AiConversationStore(directory, null);

        assertThrows(Exception.class, () -> store.create("note-a",
                Collections.emptyList(), Collections.emptyList(), null, null,
                binding(), null, false));
        assertTrue(new File(directory, "note-a.json.unsaved").isFile());
    }

    @Test public void encodedConversationBudgetCountsEscapingAndArguments() throws Exception {
        char[] chars = new char[1_100_000];
        Arrays.fill(chars, '\n'); // JSON escaping doubles the encoded size.
        String escaped = new String(chars);
        AiConversationStore.Snapshot value = new AiConversationStore.Snapshot("note-a",
                "conversation-test", 1, 1, 1,
                Collections.singletonList(new AiConversationStore.VisibleEntry(
                        "message", "assistant", escaped, "", false)),
                Collections.emptyList(), null, null, binding(), null, false);
        assertThrows(Exception.class, value::toJson);

        org.json.JSONObject encoded = new org.json.JSONObject()
                .put("schemaVersion", AiConversationStore.SCHEMA_VERSION)
                .put("noteId", "note-a")
                .put("conversationId", "conversation-test")
                .put("generation", 1).put("revision", 1).put("updatedAt", 1)
                .put("binding", binding().toJson()).put("uploadConfirmed", false)
                .put("visibleTimeline", new org.json.JSONArray().put(
                        new AiConversationStore.VisibleEntry(
                                "message", "assistant", escaped, "", false).toJson()))
                .put("wireHistory", new org.json.JSONArray());
        assertThrows(Exception.class,
                () -> AiConversationStore.Snapshot.fromJson(encoded));
        String recovery = value.toRecoveryJson().toString();
        assertTrue(recovery.contains("\\n\\n"));
        assertTrue(recovery.contains("\"recoveryExport\":true"));
    }

    @Test public void explicitToolRejectionRequiresFieldAndClearClientError() {
        assertTrue(OpenAiCompatibleClient.explicitlyRejectsTools(400,
                "unknown parameter: tool_choice is unsupported"));
        assertFalse(OpenAiCompatibleClient.explicitlyRejectsTools(429,
                "tools request rate limited"));
        assertFalse(OpenAiCompatibleClient.explicitlyRejectsTools(400,
                "model not found"));
    }

    @Test public void visibleAndWireShareOneTwoHundredMessageLimit() throws Exception {
        java.util.List<AiConversationStore.VisibleEntry> visible = new ArrayList<>();
        java.util.List<OpenAiCompatibleClient.Message> wire = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            visible.add(new AiConversationStore.VisibleEntry(
                    "message", "assistant", "v" + index, "", false));
            wire.add(new OpenAiCompatibleClient.Message("user", "w" + index));
        }
        AiConversationStore.Snapshot value = new AiConversationStore.Snapshot("note-a",
                "conversation-test", 1, 1, 1, visible, wire,
                null, null, binding(), null, false);
        assertThrows(Exception.class, value::toJson);
    }

    @Test public void maximumGenerationIsRejectedButExplicitClearRecovers() throws Exception {
        File directory = temporary.newFolder();
        AiConversationStore store = new AiConversationStore(directory, null);
        AiConversationStore.Snapshot created = store.create("note-a", Collections.emptyList(),
                Collections.emptyList(), null, null, binding(), null, false);
        org.json.JSONObject corrupted = created.toJson();
        corrupted.put("generation", Long.MAX_VALUE);
        Files.write(new File(directory, "note-a.json").toPath(),
                corrupted.toString().getBytes(StandardCharsets.UTF_8));
        new File(directory, "note-a.json.bak").delete();

        assertThrows(Exception.class, () -> store.load("note-a"));
        store.clear("note-a");
        AiConversationStore.Snapshot fresh = store.create("note-a", Collections.emptyList(),
                Collections.emptyList(), null, null, binding(), null, false);
        assertTrue(fresh.generation > 0);
    }

    @Test public void interruptedSaveReopensOneCompleteConversationRevision() throws Exception {
        for (NoteStore.WriteStage failedStage : NoteStore.WriteStage.values()) {
            File directory = temporary.newFolder("conversation-" + failedStage.name());
            AiConversationStore normal = new AiConversationStore(directory, null);
            AiConversationStore.Snapshot old = normal.create("note-a",
                    Collections.singletonList(new AiConversationStore.VisibleEntry(
                            "message", "assistant", "old", "model", false)),
                    Collections.emptyList(), null, null, binding(), null, false);
            AiConversationStore.Snapshot next = old.next(
                    Collections.singletonList(new AiConversationStore.VisibleEntry(
                            "message", "assistant", "new", "model", false)),
                    Collections.emptyList(), null, null, binding(), null, false);
            AiConversationStore interrupted = new AiConversationStore(directory, stage -> {
                if (stage == failedStage) throw new IOException("injected " + stage);
            });
            assertThrows(Exception.class, () -> interrupted.save(next));

            AiConversationStore.Snapshot reopened =
                    new AiConversationStore(directory, null).load("note-a");
            assertEquals(failedStage == NoteStore.WriteStage.NEW_VERSION_PROMOTED
                    ? "new" : "old", reopened.visibleTimeline.get(0).text);
        }
    }
}
