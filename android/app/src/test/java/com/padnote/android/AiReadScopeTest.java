package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class AiReadScopeTest {
    private static final String A_BODY = "AUTHORIZED_A_BODY_42";
    private static final String B_TITLE = "PRIVATE_B_TITLE_73";
    private static final String B_BODY = "PRIVATE_B_BODY_91";
    private static final String TRANSCRIPT = "AUTHORIZED_SELECTION_TRANSCRIPT_64";

    private static final class CountingVault implements NoteTools.VaultReader {
        final List<VaultStore.VaultNote> notes = new ArrayList<>();
        final Map<String, String> contents = new HashMap<>();
        final Map<String, Integer> reads = new HashMap<>();

        CountingVault() {
            add("a.md", "note-a", "授权 A", A_BODY);
            add("b.md", "note-b", B_TITLE, B_BODY);
        }

        void add(String file, String id, String title, String content) {
            notes.add(new VaultStore.VaultNote(file, id, title, 1, 20L, 10L));
            contents.put(file, content);
        }

        @Override public List<VaultStore.VaultNote> list() { return notes; }

        @Override public String read(String fileName) {
            reads.put(fileName, reads.getOrDefault(fileName, 0) + 1);
            return contents.get(fileName);
        }
    }

    @Test
    public void captureReadsOnlyExplicitStableIdsAndFreezesContent() throws Exception {
        CountingVault vault = new CountingVault();
        AiVaultSnapshot snapshot = AiVaultSnapshot.capture(vault,
                new LinkedHashSet<>(Collections.singletonList("note-a")));
        assertEquals(1, vault.reads.getOrDefault("a.md", 0).intValue());
        assertEquals(0, vault.reads.getOrDefault("b.md", 0).intValue());
        assertEquals(Collections.singletonList("授权 A"), snapshot.titles());
        vault.contents.put("a.md", "UPDATED_AFTER_PREVIEW");
        assertEquals(A_BODY, snapshot.read("a.md"));
        assertThrows(SecurityException.class, () -> snapshot.read("b.md"));
    }

    @Test
    public void unselectedVaultIsAbsentFromSchemasAndExecution() throws Exception {
        NoteToolRegistry defaultTools = NoteTools.createDefault();
        String schemas = defaultTools.describe().toString();
        assertFalse(schemas.contains("search_vault"));
        assertFalse(schemas.contains("read_vault_note"));
        NoteTool.Result guessed = defaultTools.invoke("read_vault_note",
                new JSONObject().put("title", B_TITLE),
                new NoteToolRegistryTest.FakeContext(), NoteTool.Permission.READ_ONLY);
        assertFalse(guessed.ok);
        assertFalse(guessed.payload.toString().contains(B_TITLE));
    }

    @Test
    public void selectedSnapshotNeverListsOrReadsAnotherNote() throws Exception {
        CountingVault vault = new CountingVault();
        AiVaultSnapshot snapshot = AiVaultSnapshot.capture(vault,
                new LinkedHashSet<>(Collections.singletonList("note-a")));
        NoteToolRegistry tools = NoteTools.createDefault(snapshot);
        NoteTool.Result search = tools.invoke("search_vault",
                new JSONObject().put("query", "PRIVATE"),
                new NoteToolRegistryTest.FakeContext(), NoteTool.Permission.READ_ONLY);
        assertTrue(search.ok);
        assertEquals(0, search.payload.optInt("totalMatches"));
        NoteTool.Result guessed = tools.invoke("read_vault_note",
                new JSONObject().put("title", B_TITLE),
                new NoteToolRegistryTest.FakeContext(), NoteTool.Permission.READ_ONLY);
        assertFalse(guessed.ok);
        String allResults = search.payload.toString() + guessed.payload.toString();
        assertFalse(allResults.contains(B_TITLE));
        assertFalse(allResults.contains(B_BODY));
        assertEquals(0, vault.reads.getOrDefault("b.md", 0).intValue());
    }

    @Test
    public void realRequestSerializerContainsOnlyAuthorizedSnapshotResults() throws Exception {
        CountingVault vault = new CountingVault();
        AiReadScope scope = AiReadScope.selectionOnly("current-note", 9, "profile-a")
                .withVault(AiVaultSnapshot.capture(vault,
                        new LinkedHashSet<>(Collections.singletonList("note-a"))));
        NoteToolRegistry tools = scope.createToolRegistry();
        NoteTool.Result read = tools.invoke("read_vault_note",
                new JSONObject().put("title", "授权 A"),
                new NoteToolRegistryTest.FakeContext(), NoteTool.Permission.READ_ONLY);
        List<OpenAiCompatibleClient.Message> messages = Arrays.asList(
                new OpenAiCompatibleClient.Message("user", "请参考已选材料"),
                OpenAiCompatibleClient.Message.toolResult("call-a", read.payload.toString()));
        JSONObject layoutOnly = new JSONObject()
                .put("pageCount", 2)
                .put("textFlows", new JSONArray().put(new JSONObject()
                        .put("flowId", "flow-1").put("format", "markdown")));
        JSONObject request = OpenAiCompatibleClient.buildRequest(
                "fixture-model", null, messages, tools.describe(), layoutOnly);
        String wire = request.toString();
        assertTrue(wire.contains(A_BODY));
        assertFalse(wire.contains(B_TITLE));
        assertFalse(wire.contains(B_BODY));
        assertFalse(wire.contains("\"source\""));
    }

    @Test
    public void profileAndMaterialChangesProduceDifferentScopeIdentity() throws Exception {
        CountingVault vault = new CountingVault();
        AiReadScope base = AiReadScope.selectionOnly("note", 1, "profile-a");
        AiReadScope withA = base.withVault(AiVaultSnapshot.capture(vault,
                new LinkedHashSet<>(Collections.singletonList("note-a"))));
        assertFalse(base.identity().equals(withA.identity()));
        assertFalse(withA.identity().equals(withA.withProfile("profile-b").identity()));
    }

    @Test
    public void oversizedNoteIsRejectedByBoundedReaderBeforeOrdinaryRead() {
        NoteTools.VaultReader oversized = new NoteTools.VaultReader() {
            @Override public List<VaultStore.VaultNote> list() {
                return Collections.singletonList(new VaultStore.VaultNote(
                        "large.md", "large", "过大材料", 1, 1L, 1L));
            }

            @Override public String read(String fileName) {
                throw new AssertionError("不应无上限读取过大文件");
            }

            @Override public String readBounded(String fileName, int maximumBytes) {
                throw new IllegalArgumentException("格式笔记超出单项材料上限");
            }
        };
        assertThrows(IllegalArgumentException.class, () -> AiVaultSnapshot.capture(
                oversized, new LinkedHashSet<>(Collections.singletonList("large"))));
    }

    @Test
    public void splitAnswerAndFollowUpKeepSameAuthorizedSnapshot() throws Exception {
        CountingVault vault = new CountingVault();
        AiReadScope scope = AiReadScope.selectionOnly("current-note", 12, "split-profile")
                .withVault(AiVaultSnapshot.capture(vault,
                        new LinkedHashSet<>(Collections.singletonList("note-a"))));
        NoteToolRegistry tools = scope.createToolRegistry();
        NoteTool.Result read = tools.invoke("read_vault_note",
                new JSONObject().put("title", "授权 A"),
                new NoteToolRegistryTest.FakeContext(), NoteTool.Permission.READ_ONLY);

        List<OpenAiCompatibleClient.Message> answerMessages = Arrays.asList(
                new OpenAiCompatibleClient.Message("user",
                        "请讲解\n\n【圈选手写内容的文字转写】\n" + TRANSCRIPT),
                OpenAiCompatibleClient.Message.toolResult("read-a", read.payload.toString()));
        JSONObject answer = OpenAiCompatibleClient.buildRequest(
                "answer-model", null, answerMessages, tools.describe(), new JSONObject());
        String answerWire = answer.toString();
        assertTrue(answerWire.contains(TRANSCRIPT));
        assertTrue(answerWire.contains(A_BODY));
        assertFalse(answerWire.contains(B_TITLE));
        assertFalse(answerWire.contains(B_BODY));

        List<OpenAiCompatibleClient.Message> followUpMessages = new ArrayList<>(answerMessages);
        followUpMessages.add(new OpenAiCompatibleClient.Message("assistant", "上一轮答复"));
        followUpMessages.add(new OpenAiCompatibleClient.Message("user", "再说详细些"));
        String followUpWire = OpenAiCompatibleClient.buildRequest(
                "answer-model", null, followUpMessages, tools.describe(), new JSONObject())
                .toString();
        assertTrue(followUpWire.contains(TRANSCRIPT));
        assertTrue(followUpWire.contains(A_BODY));
        assertFalse(followUpWire.contains(B_TITLE));
        assertFalse(followUpWire.contains(B_BODY));
    }

    @Test
    public void shrinkingScopeRemovesOldToolResultsFromNextSerializedRequest() throws Exception {
        CountingVault vault = new CountingVault();
        AiReadScope selected = AiReadScope.selectionOnly("note", 2, "profile")
                .withVault(AiVaultSnapshot.capture(vault,
                        new LinkedHashSet<>(Collections.singletonList("note-a"))));
        NoteTool.Result oldRead = selected.createToolRegistry().invoke("read_vault_note",
                new JSONObject().put("title", "授权 A"),
                new NoteToolRegistryTest.FakeContext(), NoteTool.Permission.READ_ONLY);
        assertTrue(oldRead.payload.toString().contains(A_BODY));

        AiReadScope reduced = selected.withVault(null);
        List<OpenAiCompatibleClient.Message> freshConversation = Collections.singletonList(
                new OpenAiCompatibleClient.Message("user", "新范围下的追问"));
        String wire = OpenAiCompatibleClient.buildRequest("model", null, freshConversation,
                reduced.createToolRegistry().describe(), new JSONObject()).toString();
        assertFalse(wire.contains(A_BODY));
        assertFalse(wire.contains("search_vault"));
        assertFalse(wire.contains("read_vault_note"));
    }

    @Test
    public void pageMapNeverSerializesTextFlowSource() throws Exception {
        String privateFlowText = "SAME_PAGE_PRIVATE_FLOW_58";
        NoteTextBox fragment = new NoteTextBox(
                "fragment", "flow-private", 0, 1, NoteTextBox.Format.MARKDOWN,
                privateFlowText, privateFlowText, 16f, 1.35f,
                0, 20f, 30f, 300f, 80f);
        TextFlow flow = new TextFlow("flow-private", NoteTextBox.Format.MARKDOWN,
                privateFlowText, 16f, 1.35f, 300f, 0, 20f, 30f);
        JSONObject map = PageMap.build(Collections.emptyList(),
                Collections.singletonList(fragment), Collections.singletonList(flow),
                1, 360f, 640f, 20f, 1f, 0, null, true);
        String serialized = map.toString();
        assertFalse(serialized.contains(privateFlowText));
        assertFalse(serialized.contains("\"source\""));
        assertTrue(serialized.contains("flow-private"));

        JSONObject firstRequest = OpenAiCompatibleClient.buildRequest(
                "model", null,
                Collections.singletonList(new OpenAiCompatibleClient.Message(
                        "user", "FIRST_REQUEST_PROMPT")),
                NoteTools.createDefault().describe(), map);
        String wire = firstRequest.toString();
        assertTrue(wire.contains("FIRST_REQUEST_PROMPT"));
        assertTrue(wire.contains("flow-private"));
        assertFalse(wire.contains(privateFlowText));
        assertFalse(wire.contains("\"source\""));
        assertFalse(wire.contains("search_vault"));
    }
}
