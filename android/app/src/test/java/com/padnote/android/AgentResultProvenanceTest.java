package com.padnote.android;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

/** Contract tests for stable Agent result identity and optional identifiers. */
public final class AgentResultProvenanceTest {
    private static final String TASK_ID = "6e89e32f-6861-4598-80f8-2f5dfe2fb080";
    private static final String OUTPUT = "  推导完成：\n面积 = πr² ✅🧪  ";
    private static final String EMPTY_NOTE = "{\"schemaVersion\":8,\"id\":\"note-fixture\",\"title\":\"Fixture\","
            + "\"updatedAt\":16,\"pageWidth\":768.0,\"pageHeight\":1086.0,\"canvasWidth\":768.0,"
            + "\"canvasHeight\":1086.0,\"pageGap\":24.0,\"pageCount\":1,\"pdfPageCount\":0,"
            + "\"pageTopologyRevision\":0,\"authorPageEditSerial\":7,\"authorPageTopologySerial\":3,"
            + "\"viewportZoom\":1.0,\"viewportCenterX\":384.0,\"viewportCenterY\":543.0,"
            + "\"strokes\":[],\"textFlows\":[],\"images\":[],\"pageStyle\":{\"paper\":\"ruled\","
            + "\"ratio\":\"screen\",\"landscape\":false},\"textBoxes\":[]}";

    @Test public void factoryNormalizesEmptyOptionalIdsToJsonNullAndHashesThemAsEmpty() throws Exception {
        AgentTaskStore.Task task = task(TASK_ID, "连接-电脑-二", "", OUTPUT);
        AgentResultProvenance receipt = AgentResultProvenance.create(task, "", 0L, OUTPUT);

        assertNull(receipt.instanceId);
        assertNull(receipt.sourceNoteId);
        assertEquals("8bb7a561ae2237876d70a82b5d3efb08ea8f2575f9bc4e60ba37a724d3e6e753",
                receipt.resultSha256);
        assertEquals("7cf2425fe83d63ae1e7ed661fab1d036f5eaa3d6588eb458b9d819190e160dbf",
                receipt.applicationId);
        JSONObject json = receipt.toJson();
        assertSame(JSONObject.NULL, json.get("instanceId"));
        assertSame(JSONObject.NULL, json.get("sourceNoteId"));
        AgentResultProvenance reopened = AgentResultProvenance.fromJson(json);
        assertNull(reopened.instanceId);
        assertNull(reopened.sourceNoteId);
        assertTrue(receipt.sameMetadata(reopened));

        JSONObject nonCanonical = new JSONObject(json.toString()).put("instanceId", "");
        assertThrows("empty optional identifiers must be canonical JSON null",
                org.json.JSONException.class, () -> AgentResultProvenance.fromJson(nonCanonical));
    }

    @Test public void duplicateIdentityIgnoresAddedAtButKeepsFirstTimestampInJson() throws Exception {
        AgentTaskStore.Task task = task(TASK_ID, "conn-workstation-01",
                "ad8b76d9-27fd-4471-8cae-046bd306c20d", "  Result: 2 + 2 = 4\n");
        AgentResultProvenance first = AgentResultProvenance.create(task,
                "91f69081-29ab-4888-9400-f39db2e7cb41", 17L, task.output);
        AgentResultProvenance retry = new AgentResultProvenance(first.taskId, first.connectionId,
                first.instanceId, first.agentKind, first.transport, first.sourceNoteId,
                first.sourceNoteRevision, first.resultSha256, first.addedAt + 9999L);

        assertTrue(first.sameMetadata(retry));
        assertNotEquals(first.addedAt, retry.addedAt);
        assertEquals(first.addedAt, first.toJson().getLong("addedAt"));
    }

    @Test public void acceptsBothPersistedTaskIdFormatsAndRejectsOtherIds() {
        AgentTaskStore.Task canonical = task(TASK_ID, "conn", "", OUTPUT);
        assertEquals(TASK_ID, AgentResultProvenance.create(canonical, null, 0L, OUTPUT).taskId);
        String storedId = "padnote-0123456789abcdef0123456789abcdef";
        AgentTaskStore.Task generatedFormat = task(storedId, "conn", "", OUTPUT);
        assertEquals(storedId, AgentResultProvenance.create(generatedFormat, null, 0L, OUTPUT).taskId);
        assertThrows("task IDs outside either persisted format remain invalid",
                IllegalArgumentException.class,
                () -> AgentResultProvenance.create(task("task-0123456789abcdef0123456789abcdef",
                        "conn", "", OUTPUT), null, 0L, OUTPUT));
        assertThrows("uppercase task IDs remain noncanonical", IllegalArgumentException.class,
                () -> AgentResultProvenance.create(task("padnote-0123456789ABCDEF0123456789abcdef",
                        "conn", "", OUTPUT), null, 0L, OUTPUT));
    }

    @Test public void outputHashUsesExactUtf8BytesAndRejectsMalformedUtf16() {
        AgentTaskStore.Task task = task(TASK_ID, "conn", "", OUTPUT);
        AgentResultProvenance receipt = AgentResultProvenance.create(task, null, 0L, OUTPUT);
        assertEquals("8bb7a561ae2237876d70a82b5d3efb08ea8f2575f9bc4e60ba37a724d3e6e753",
                receipt.resultSha256);
        assertNotEquals(receipt.resultSha256,
                AgentResultProvenance.sha256("推导完成：\n面积 = πr² ✅🧪".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> AgentResultProvenance.create(task, null, 0L, "bad\ud800"));
    }

    @Test public void schemaNineProjectionAllowsEmptyReceiptAndEditableReceiptFlowFormats() throws Exception {
        JSONObject schemaEight = new JSONObject(EMPTY_NOTE);
        assertNotNull(TypedNoteTimeProjection.nativeAndroid(schemaEight.toString()));
        JSONObject legacyFlow = new JSONObject().put("id", "legacy-flow")
                .put("format", "markdown").put("source", "Legacy flow with omitted defaults")
                .put("fontSizeSp", 16.0).put("width", 600.0)
                .put("anchorPageIndex", 0).put("anchorXInPage", 16.0).put("anchorYInPage", 16.0);
        JSONObject legacyWithoutLineHeight = new JSONObject(EMPTY_NOTE)
                .put("textFlows", new org.json.JSONArray().put(legacyFlow));
        NoteStore.validateDocument(legacyWithoutLineHeight);
        JSONObject emptySchemaNine = new JSONObject(EMPTY_NOTE).put("schemaVersion", 9);
        NoteStore.validateDocument(emptySchemaNine);
        assertNotNull("undo may remove the final provenance flow without invalidating the note",
                TypedNoteTimeProjection.nativeAndroid(emptySchemaNine.toString()));
        AgentTaskStore.Task task = task(TASK_ID, "conn", "", OUTPUT);
        AgentResultProvenance receipt = AgentResultProvenance.create(task, "note-fixture", 16L, OUTPUT);
        JSONObject flow = new JSONObject().put("id", "user-copied-flow-id")
                .put("format", "markdown").put("source", "Edited after the Agent result was added")
                .put("fontSizeSp", 16.0).put("lineHeight", 1.4).put("width", 600.0)
                .put("anchorPageIndex", 0).put("anchorXInPage", 16.0).put("anchorYInPage", 16.0)
                .put("agentResult", receipt.toJson());
        JSONObject schemaNine = new JSONObject(EMPTY_NOTE).put("schemaVersion", 9)
                .put("textFlows", new org.json.JSONArray().put(flow));
        NoteStore.validateDocument(schemaNine);
        assertNotNull("later flow edits do not invalidate the original result digest",
                TypedNoteTimeProjection.nativeAndroid(schemaNine.toString()));
        JSONObject latexFlow = new JSONObject(flow.toString()).put("format", "latex");
        JSONObject editableLatexReceipt = new JSONObject(EMPTY_NOTE).put("schemaVersion", 9)
                .put("textFlows", new org.json.JSONArray().put(latexFlow));
        NoteStore.validateDocument(editableLatexReceipt);
        assertNotNull("provenance remains valid if the user changes its renderer format",
                TypedNoteTimeProjection.nativeAndroid(editableLatexReceipt.toString()));

        JSONObject illegalSchemaEight = new JSONObject(EMPTY_NOTE)
                .put("textFlows", new org.json.JSONArray().put(flow));
        assertThrows(IllegalArgumentException.class, () -> NoteStore.validateDocument(illegalSchemaEight));
    }

    private static AgentTaskStore.Task task(String id, String connectionId,
                                             String instanceId, String output) {
        return new AgentTaskStore.Task(id, connectionId, 3L, "test-origin",
                AgentConnectionStore.Kind.HERMES, AgentConnectionStore.Transport.DIRECT,
                "credential", "", instanceId, "Task", "Connection", "", 0L,
                "{}", AgentResultProvenance.sha256("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "", AgentTaskStore.Status.COMPLETED,
                output, "", "", "", "", Collections.emptyList(), 10L, 11L);
    }
}
