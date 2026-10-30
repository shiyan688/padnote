package com.padnote.android;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** Non-rendering provenance attached to a text flow created from a saved Agent result. */
final class AgentResultProvenance {
    static final int MAX_UTF16_UNITS = 100_000;
    static final int MAX_UTF8_BYTES = 400_000;

    final String applicationId, taskId, connectionId, instanceId, agentKind, transport;
    final String sourceNoteId, resultSha256;
    final long sourceNoteRevision, addedAt;

    AgentResultProvenance(String taskId, String connectionId, String instanceId,
                          String agentKind, String transport, String sourceNoteId,
                          long sourceNoteRevision, String resultSha256, long addedAt) {
        this(taskId, connectionId, instanceId, agentKind, transport, sourceNoteId,
                sourceNoteRevision, resultSha256, addedAt,
                calculateApplicationId(taskId, connectionId, instanceId, agentKind,
                        transport, resultSha256));
    }

    private AgentResultProvenance(String taskId, String connectionId, String instanceId,
                                  String agentKind, String transport, String sourceNoteId,
                                  long sourceNoteRevision, String resultSha256, long addedAt,
                                  String applicationId) {
        this.taskId = taskId;
        this.connectionId = connectionId;
        this.instanceId = instanceId;
        this.agentKind = agentKind;
        this.transport = transport;
        this.sourceNoteId = sourceNoteId;
        this.sourceNoteRevision = sourceNoteRevision;
        this.resultSha256 = resultSha256;
        this.addedAt = addedAt;
        this.applicationId = applicationId;
        validate();
    }

    static String calculateApplicationId(String taskId, String connectionId, String instanceId,
                                         String agentKind, String transport, String resultSha256) {
        String material = "padnote.agent-result.v1\n" + taskId.toLowerCase(Locale.ROOT) + "\n"
                + connectionId + "\n" + (instanceId == null ? "" : instanceId) + "\n"
                + agentKind.toLowerCase(Locale.ROOT) + "\n"
                + transport.toLowerCase(Locale.ROOT) + "\n" + resultSha256;
        return sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    static AgentResultProvenance create(AgentTaskStore.Task task, String sourceNoteId,
                                        long sourceNoteRevision, String output) {
        if (task == null || output == null || output.trim().isEmpty()
                || output.length() > MAX_UTF16_UNITS || !wellFormedUtf16(output)) throw new IllegalArgumentException("AGENT_RESULT_INVALID");
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_UTF8_BYTES) throw new IllegalArgumentException("AGENT_RESULT_INVALID");
        String instanceId = task.instanceId == null || task.instanceId.isEmpty() ? null : task.instanceId;
        String normalizedSourceNoteId = sourceNoteId == null || sourceNoteId.isEmpty() ? null : sourceNoteId;
        return new AgentResultProvenance(task.clientTaskId, task.connectionId, instanceId,
                task.kind.name().toLowerCase(Locale.ROOT), task.transport.name().toLowerCase(Locale.ROOT),
                normalizedSourceNoteId, sourceNoteRevision, sha256(bytes), System.currentTimeMillis());
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("schemaVersion", 1).put("applicationId", applicationId)
                .put("taskId", taskId).put("connectionId", connectionId)
                .put("instanceId", instanceId == null ? JSONObject.NULL : instanceId)
                .put("agentKind", agentKind).put("transport", transport)
                .put("sourceNoteId", sourceNoteId == null ? JSONObject.NULL : sourceNoteId)
                .put("sourceNoteRevision", sourceNoteRevision).put("resultSha256", resultSha256)
                .put("addedAt", addedAt);
    }

    static AgentResultProvenance fromJson(JSONObject json) throws JSONException {
        if (json == null) throw new JSONException("agent_result_required");
        requireExactKeys(json);
        if (strictLong(json, "schemaVersion") != 1) throw new JSONException("agent_result_schema_invalid");
        Object instance = json.get("instanceId"), source = json.get("sourceNoteId");
        if (!(instance instanceof String || instance == JSONObject.NULL)
                || !(source instanceof String || source == JSONObject.NULL))
            throw new JSONException("agent_result_nullable_identifier_invalid");
        try {
            return new AgentResultProvenance(json.getString("taskId"), json.getString("connectionId"),
                    instance == JSONObject.NULL ? null : (String) instance, json.getString("agentKind"),
                    json.getString("transport"), source == JSONObject.NULL ? null : (String) source,
                    strictLong(json, "sourceNoteRevision"), json.getString("resultSha256"),
                    strictLong(json, "addedAt"), json.getString("applicationId"));
        } catch (IllegalArgumentException invalid) {
            throw new JSONException("agent_result_invalid:" + invalid.getMessage());
        }
    }

    private void validate() {
        validateIdentifier(taskId, false);
        if (!canonicalTaskId(taskId)) throw new IllegalArgumentException("AGENT_RESULT_TASK_ID_INVALID");
        validateIdentifier(connectionId, false);
        validateIdentifier(instanceId, true);
        validateIdentifier(sourceNoteId, true);
        validateIdentifier(agentKind, false);
        validateIdentifier(transport, false);
        if (!agentKind.matches("[a-z0-9._-]{1,64}") || !transport.matches("[a-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("AGENT_RESULT_TOKEN_INVALID");
        if (!isSha256(applicationId) || !isSha256(resultSha256)
                || !applicationId.equals(calculateApplicationId(taskId, connectionId, instanceId,
                    agentKind, transport, resultSha256)))
            throw new IllegalArgumentException("AGENT_RESULT_DIGEST_INVALID");
        if (sourceNoteRevision < 0 || addedAt < 0) throw new IllegalArgumentException("AGENT_RESULT_TIME_INVALID");
    }

    private static void validateIdentifier(String value, boolean nullable) {
        if (value == null) { if (!nullable) throw new IllegalArgumentException("AGENT_RESULT_IDENTIFIER_REQUIRED"); return; }
        if (!wellFormedUtf16(value)) throw new IllegalArgumentException("AGENT_RESULT_IDENTIFIER_UNICODE_INVALID");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > 256) throw new IllegalArgumentException("AGENT_RESULT_IDENTIFIER_SIZE_INVALID");
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i); i += Character.charCount(cp);
            if (cp <= 0x1f || (cp >= 0x7f && cp <= 0x9f)) throw new IllegalArgumentException("AGENT_RESULT_IDENTIFIER_CONTROL_INVALID");
        }
    }

    private static boolean canonicalUuid(String value) {
        if (value == null || value.length() != 36 || !value.equals(value.toLowerCase(Locale.ROOT))) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    /** Accepts both persisted task ID formats already issued by Android task stores. */
    private static boolean canonicalTaskId(String value) {
        return canonicalUuid(value) || value != null && value.matches("padnote-[0-9a-f]{32}");
    }

    static boolean wellFormedUtf16(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    private static boolean isSha256(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static long strictLong(JSONObject value, String key) throws JSONException {
        Object raw = value.get(key);
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long))
            throw new JSONException("agent_result_integer_invalid:" + key);
        return ((Number) raw).longValue();
    }
    private static void requireExactKeys(JSONObject json) throws JSONException {
        String[] expected = {"schemaVersion", "applicationId", "taskId", "connectionId", "instanceId",
                "agentKind", "transport", "sourceNoteId", "sourceNoteRevision", "resultSha256", "addedAt"};
        if (json.length() != expected.length) throw new JSONException("agent_result_keys_invalid");
        for (String key : expected) if (!json.has(key)) throw new JSONException("agent_result_keys_invalid");
    }

    boolean sameMetadata(AgentResultProvenance other) {
        return other != null && applicationId.equals(other.applicationId) && taskId.equals(other.taskId)
                && connectionId.equals(other.connectionId) && equal(instanceId, other.instanceId)
                && agentKind.equals(other.agentKind) && transport.equals(other.transport)
                && equal(sourceNoteId, other.sourceNoteId) && sourceNoteRevision == other.sourceNoteRevision
                && resultSha256.equals(other.resultSha256);
    }

    byte[] canonicalBytes() {
        String[] fields = {"1", applicationId, taskId, connectionId, instanceId == null ? "" : instanceId,
                agentKind, transport, sourceNoteId == null ? "" : sourceNoteId,
                Long.toString(sourceNoteRevision), resultSha256, Long.toString(addedAt)};
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < fields.length; i++) { if (i > 0) value.append('\n'); value.append(fields[i]); }
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static boolean equal(Object a, Object b) { return a == null ? b == null : a.equals(b); }
}
