package com.padnote.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Explicit file-delivery expectations and the separate result of evaluating them. */
final class AgentDelivery {
    // Match the Bridge contract's sorted array representation. The UI keeps its own
    // user-facing order and does not depend on this wire order.
    static final List<String> KINDS = Collections.unmodifiableList(Arrays.asList(
            "audio", "file", "image", "pdf", "video"));

    enum Status { NOT_REQUESTED, PENDING, DELIVERED, MISSING }

    static final class Summary {
        final Status status;
        final List<String> expected;
        final List<String> missing;
        final int artifactCount;
        final boolean reportedByServer;

        Summary(Status status, List<String> expected, List<String> missing,
                int artifactCount, boolean reportedByServer) {
            this.status = status;
            this.expected = immutable(expected);
            this.missing = immutable(missing);
            this.artifactCount = Math.max(0, artifactCount);
            this.reportedByServer = reportedByServer;
        }

        JSONObject toJson() {
            JSONArray expectedJson = new JSONArray();
            for (String value : expected) expectedJson.put(value);
            JSONArray missingJson = new JSONArray();
            for (String value : missing) missingJson.put(value);
            try {
                return new JSONObject().put("status", wire(status))
                        .put("expected", expectedJson).put("missing", missingJson)
                        .put("artifact_count", artifactCount)
                        .put("reported_by_server", reportedByServer);
            } catch (org.json.JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        static Summary fromJson(JSONObject json, List<String> fallbackExpected,
                                int fallbackArtifactCount) {
            if (json == null) return initial(fallbackExpected, fallbackArtifactCount);
            try {
                Object rawStatus=json.opt("status");
                if(!(rawStatus instanceof String))throw new IllegalArgumentException("delivery_status_type");
                Status status = parseStatus((String)rawStatus);
                List<String> expected = parseArray(json.getJSONArray("expected"));
                List<String> missing = parseArray(json.getJSONArray("missing"));
                int count = exactNonnegativeInt(json.opt("artifact_count"));
                Object reported = json.opt("reported_by_server");
                if (json.has("reported_by_server") && !(reported instanceof Boolean))
                    throw new IllegalArgumentException("delivery_source_type");
                boolean server = reported instanceof Boolean && (Boolean) reported;
                return new Summary(status, expected, missing, count, server);
            } catch (Exception malformed) {
                throw new IllegalArgumentException("saved_delivery_invalid", malformed);
            }
        }
    }

    private AgentDelivery() {}

    static boolean contractSupported(AgentConnectionStore.Config connection) {
        return connection != null && connection.transport == AgentConnectionStore.Transport.BRIDGE &&
                connection.featureMap != null &&
                Boolean.TRUE.equals(connection.featureMap.get("delivery_contract_v1"));
    }

    static List<String> normalize(Collection<String> values) {
        LinkedHashSet<String> input = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || !KINDS.contains(value) || !input.add(value)) {
                    throw new IllegalArgumentException("交付类型无效或重复");
                }
            }
        }
        List<String> normalized = new ArrayList<>();
        for (String kind : KINDS) if (input.contains(kind)) normalized.add(kind);
        if (normalized.size() > 5) throw new IllegalArgumentException("交付类型过多");
        return immutable(normalized);
    }

    static List<String> parseArray(JSONArray array) {
        if (array.length() > KINDS.size()) throw new IllegalArgumentException("too_many_delivery_kinds");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (!(value instanceof String)) throw new IllegalArgumentException("delivery_kind_type");
            values.add((String) value);
        }
        return normalize(values);
    }

    static Summary initial(List<String> expected, int artifactCount) {
        List<String> normalized = normalize(expected);
        return new Summary(normalized.isEmpty() ? Status.NOT_REQUESTED : Status.PENDING,
                normalized, Collections.emptyList(), artifactCount, false);
    }

    static Summary forCurrentTask(List<String> expected, AgentTaskStore.Status execution,
                                  List<AgentTaskStore.Artifact> artifacts) {
        List<String> normalized = normalize(expected);
        int count = artifacts == null ? 0 : artifacts.size();
        if (normalized.isEmpty()) return new Summary(Status.NOT_REQUESTED, normalized,
                Collections.emptyList(), count, false);
        if (execution != AgentTaskStore.Status.COMPLETED) {
            return new Summary(Status.PENDING, normalized, Collections.emptyList(), count, false);
        }
        List<String> missing = new ArrayList<>();
        for (String kind : normalized) {
            boolean found = false;
            for (AgentTaskStore.Artifact artifact : artifacts == null
                    ? Collections.<AgentTaskStore.Artifact>emptyList() : artifacts) {
                if (artifact.sizeBytes > 0 && accepts(kind, artifact.mediaType)) { found = true; break; }
            }
            if (!found) missing.add(kind);
        }
        return new Summary(missing.isEmpty() ? Status.DELIVERED : Status.MISSING,
                normalized, missing, count, false);
    }

    static Summary fromServer(JSONObject json, List<String> taskExpected,
                              AgentTaskStore.Status execution,
                              List<AgentTaskStore.Artifact> artifacts) {
        if (json == null) throw new IllegalStateException("delivery_summary_missing");
        try {
            Object rawStatus=json.opt("status");
            if(!(rawStatus instanceof String))throw new IllegalArgumentException("delivery_status_type");
            Status status = parseStatus((String)rawStatus);
            List<String> expected = parseArray(json.getJSONArray("expected"));
            List<String> missing = parseArray(json.getJSONArray("missing"));
            int count = exactNonnegativeInt(json.opt("artifact_count"));
            List<String> wanted = normalize(taskExpected);
            if (!expected.equals(wanted) || count != (artifacts == null ? 0 : artifacts.size()) ||
                    !wanted.containsAll(missing)) {
                throw new IllegalArgumentException("delivery_summary_mismatch");
            }
            if (wanted.isEmpty()) {
                if (status != Status.NOT_REQUESTED || !missing.isEmpty())
                    throw new IllegalArgumentException("delivery_not_requested_mismatch");
            } else if (execution == AgentTaskStore.Status.COMPLETED) {
                Summary local = forCurrentTask(wanted, execution, artifacts);
                if (!local.missing.equals(missing) || status != local.status)
                    throw new IllegalArgumentException("delivery_terminal_mismatch");
            } else if (status != Status.PENDING) {
                throw new IllegalArgumentException("delivery_nonterminal_mismatch");
            }
            return new Summary(status, expected, missing, count, true);
        } catch (Exception malformed) {
            throw new IllegalStateException("Agent returned an invalid delivery summary", malformed);
        }
    }

    static Summary fromServerOrLocal(JSONObject json,List<String> taskExpected,
                                     AgentTaskStore.Status execution,
                                     List<AgentTaskStore.Artifact> artifacts) {
        try {
            return fromServer(json,taskExpected,execution,artifacts);
        } catch (RuntimeException unusableServerSummary) {
            // The delivery block is additive. A bad summary must never erase a valid
            // task ID, status, or already-validated artifact descriptors.
            return forCurrentTask(taskExpected,execution,artifacts);
        }
    }

    static void validateStored(Summary summary, List<String> expected,
                               AgentTaskStore.Status execution,
                               List<AgentTaskStore.Artifact> artifacts) {
        Summary local=forCurrentTask(expected,execution,artifacts);
        if(summary==null||summary.artifactCount!=(artifacts==null?0:artifacts.size())||
                !summary.expected.equals(local.expected)||summary.status!=local.status||
                !summary.missing.equals(local.missing))
            throw new IllegalArgumentException("saved_delivery_summary_mismatch");
    }

    static Status parseStatus(String raw) {
        switch (raw) {
            case "not_requested": return Status.NOT_REQUESTED;
            case "pending": return Status.PENDING;
            case "delivered": return Status.DELIVERED;
            case "missing": return Status.MISSING;
            default: throw new IllegalArgumentException("unknown_delivery_status");
        }
    }

    static String wire(Status status) {
        switch (status) {
            case NOT_REQUESTED: return "not_requested";
            case PENDING: return "pending";
            case DELIVERED: return "delivered";
            case MISSING: return "missing";
            default: throw new IllegalArgumentException("unknown_delivery_status");
        }
    }

    static JSONArray requestArray(List<String> expected) {
        JSONArray result = new JSONArray();
        for (String value : normalize(expected)) result.put(value);
        return result;
    }

    static String displayType(String kind) {
        switch (kind) {
            case "file": return "file";
            case "image": return "image";
            case "pdf": return "pdf";
            case "video": return "video";
            case "audio": return "audio";
            default: return "";
        }
    }

    private static boolean accepts(String kind, String mediaType) {
        String type = mediaType == null ? "" : mediaType.trim().toLowerCase(Locale.ROOT);
        switch (kind) {
            case "file": return !type.isEmpty() && type.indexOf('/') > 0 && type.indexOf(' ') < 0;
            case "image": return "image/png".equals(type) || "image/jpeg".equals(type);
            case "pdf": return "application/pdf".equals(type);
            case "video": return type.startsWith("video/") && type.length() > 6 && type.indexOf(' ') < 0;
            case "audio": return type.startsWith("audio/") && type.length() > 6 && type.indexOf(' ') < 0;
            default: return false;
        }
    }

    private static int exactNonnegativeInt(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer ||
                value instanceof Long)) throw new IllegalArgumentException("delivery_count_type");
        long asLong = ((Number) value).longValue();
        if (asLong < 0 || asLong > Integer.MAX_VALUE)
            throw new IllegalArgumentException("delivery_count_value");
        return (int) asLong;
    }

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values == null
                ? Collections.emptyList() : values));
    }
}
