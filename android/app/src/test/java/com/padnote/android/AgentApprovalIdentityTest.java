package com.padnote.android;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Iterator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AgentApprovalIdentityTest {
    @Test public void roundTripsPromptIdsContainingColons() {
        String token = AgentApprovalIdentity.encode("prompt:with:colons", 42);
        AgentApprovalIdentity.Identity decoded = AgentApprovalIdentity.decode(token);
        assertEquals("prompt:with:colons", decoded.promptId);
        assertEquals(42, decoded.generation);
    }

    @Test public void rejectsInvalidGenerationAndPromptIds() {
        assertThrows(IllegalArgumentException.class, () -> AgentApprovalIdentity.encode("id", 0));
        assertThrows(IllegalArgumentException.class, () -> AgentApprovalIdentity.decode("pc1:01:id"));
        assertThrows(IllegalArgumentException.class, () -> AgentApprovalIdentity.decode("pc1:1:bad\u0085id"));
        assertThrows(IllegalArgumentException.class, () -> AgentApprovalIdentity.decode("pc1:1:bad\ud800id"));
        assertEquals(256, AgentApprovalIdentity.encode("x".repeat(250), 1)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThrows(IllegalArgumentException.class, () -> AgentApprovalIdentity.encode("x".repeat(251), 1));
        String unicodeBoundary = "😀".repeat(62);
        assertEquals(254, AgentApprovalIdentity.encode(unicodeBoundary, 1)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThrows(IllegalArgumentException.class,
                () -> AgentApprovalIdentity.encode("😀".repeat(63), 1));
    }

    @Test public void identityOnlyAndEmptyPayloadCannotBecomeActionable() throws Exception {
        String identityOnly = "{\"status\":\"waiting_for_approval\",\"execution_generation\":4,"
                + "\"pending_controls\":[{\"kind\":\"approval\",\"prompt_id\":\"p:1\","
                + "\"execution_generation\":4,\"request_id\":\"raw\",\"approval_id\":\"raw-a\","
                + "\"run_id\":\"run-a\",\"session_id\":\"session-a\",\"admission_id\":\"admission-a\"}]}";
        AgentApprovalIdentity.Parsed identity = AgentApprovalIdentity.parse(new JSONObject(identityOnly));
        assertEquals("", identity.id);
        assertFalse(identity.message.isEmpty());

        String emptyPayload = "{\"status\":\"waiting_for_approval\",\"execution_generation\":4,"
                + "\"pending_controls\":[{\"kind\":\"approval\",\"prompt_id\":\"p:1\","
                + "\"execution_generation\":4,\"payload\":{}}]}";
        AgentApprovalIdentity.Parsed empty = AgentApprovalIdentity.parse(new JSONObject(emptyPayload));
        assertEquals("", empty.id);
        assertFalse(empty.message.isEmpty());
    }

    @Test public void titleOnlyToolOnlyAndEmptyMetadataRemainNonActionableButArgumentsAreShown() throws Exception {
        String[] unsafe = {
                "{\"title\":\"Review\"}",
                "{\"tool_name\":\"Review\"}",
                "{\"command\":\"  \",\"reason\":\"\",\"details\":{}}",
                "{\"title\":\"Review\",\"command\":\" \"}"
        };
        for (String payload : unsafe) {
            JSONObject run = approvalRun(payload);
            AgentApprovalIdentity.Parsed parsed = AgentApprovalIdentity.parse(run);
            assertEquals(payload, "", parsed.id);
            assertFalse(parsed.message.isEmpty());
        }

        JSONObject run = approvalRun("{\"title\":\"Review\",\"tool_args\":{\"path\":\"note.txt\"},"
                + "\"arguments\":[\"--check\"]}");
        AgentApprovalIdentity.Parsed parsed = AgentApprovalIdentity.parse(run);
        assertFalse(parsed.id.isEmpty());
        assertTrue(parsed.description.contains("note.txt"));
        assertTrue(parsed.description.contains("--check"));

        JSONObject allFieldsRun = approvalRun("{\"title\":\"Review\","
                + "\"description\":\"safe\",\"command\":\"dangerous command\","
                + "\"reason\":\"requires review\"}");
        AgentApprovalIdentity.Parsed allFields = AgentApprovalIdentity.parse(allFieldsRun);
        assertTrue(allFields.description.contains("description: safe"));
        assertTrue(allFields.description.contains("command: dangerous command"));
        assertTrue(allFields.description.contains("reason: requires review"));

        JSONObject spacingRun = approvalRun("{\"title\":\"Review\","
                + "\"arguments\":\"  --path note.txt  \"}");
        AgentApprovalIdentity.Parsed spacing = AgentApprovalIdentity.parse(spacingRun);
        assertTrue(spacing.description.contains("  --path note.txt  "));
    }

    private static JSONObject approvalRun(String payload) throws Exception {
        return new JSONObject("{\"status\":\"waiting_for_approval\",\"execution_generation\":2,"
                + "\"pending_controls\":[{\"kind\":\"approval\",\"prompt_id\":\"prompt-2\","
                + "\"execution_generation\":2,\"payload\":" + payload + "}]}");
    }

    @Test public void requestBodyHasExactlyThreeBoundFields() throws Exception {
        JSONObject body = AgentApprovalIdentity.requestBody(
                AgentApprovalIdentity.encode("prompt:7", 7), "once");
        assertEquals(3, body.length());
        assertEquals("prompt:7", body.getString("request_id"));
        assertEquals(7, body.getLong("execution_generation"));
        assertEquals("once", body.getString("choice"));
        Iterator<String> keys = body.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!"request_id".equals(key) && !"execution_generation".equals(key)
                    && !"choice".equals(key)) throw new AssertionError("unexpected key: " + key);
        }
        assertThrows(IllegalArgumentException.class,
                () -> AgentApprovalIdentity.requestBody("legacy-raw-id", "once"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentApprovalIdentity.requestBody(AgentApprovalIdentity.encode("id", 1), "approve"));
    }
}
