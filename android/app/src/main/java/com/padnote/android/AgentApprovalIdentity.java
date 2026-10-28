package com.padnote.android;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** Codec for binding direct-Hermes approval requests to a prompt generation. */
final class AgentApprovalIdentity {
    private static final long MAX_GENERATION = 9007199254740991L;

    static final class Identity {
        final String promptId;
        final long generation;

        Identity(String promptId, long generation) {
            this.promptId = promptId;
            this.generation = generation;
        }
    }

    static final class Parsed {
        final String id;
        final String title;
        final String description;
        final String message;

        Parsed(String id, String title, String description, String message) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.message = message;
        }
    }

    private AgentApprovalIdentity() {}

    static String encode(String promptId, long generation) {
        validatePromptId(promptId);
        if (generation < 1 || generation > MAX_GENERATION) {
            throw new IllegalArgumentException("invalid execution generation");
        }
        String token = "pc1:" + generation + ":" + promptId;
        if (token.getBytes(StandardCharsets.UTF_8).length > 256) {
            throw new IllegalArgumentException("approval identity is too long");
        }
        return token;
    }

    static Identity decode(String token) {
        if (token == null || token.getBytes(StandardCharsets.UTF_8).length > 256) {
            throw new IllegalArgumentException("invalid approval identity");
        }
        String[] parts = token.split(":", 3);
        if (parts.length != 3 || !"pc1".equals(parts[0]) || parts[1].isEmpty()
                || (parts[1].length() > 1 && parts[1].charAt(0) == '0')) {
            throw new IllegalArgumentException("invalid approval identity");
        }
        for (int i = 0; i < parts[1].length(); i++) {
            if (parts[1].charAt(i) < '0' || parts[1].charAt(i) > '9') {
                throw new IllegalArgumentException("invalid approval generation");
            }
        }
        final long generation;
        try {
            generation = Long.parseLong(parts[1]);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid approval generation", error);
        }
        validatePromptId(parts[2]);
        if (generation < 1 || generation > MAX_GENERATION) {
            throw new IllegalArgumentException("invalid approval generation");
        }
        return new Identity(parts[2], generation);
    }

    static JSONObject requestBody(String token, String decision) {
        if (!"once".equals(decision) && !"deny".equals(decision)) {
            throw new IllegalArgumentException("invalid approval decision");
        }
        Identity identity = decode(token);
        try {
            return new JSONObject().put("request_id", identity.promptId)
                    .put("execution_generation", identity.generation)
                    .put("choice", decision);
        } catch (JSONException impossible) {
            throw new IllegalStateException("could not encode approval request", impossible);
        }
    }

    static Parsed parse(JSONObject run) {
        if (run == null || !"waiting_for_approval".equals(run.optString("status", ""))) {
            return new Parsed("", "", "", "");
        }
        String fallback = "审批请求信息不完整，需在电脑助手中处理";
        if (run.has("pending_controls")) {
            JSONArray controls = run.optJSONArray("pending_controls");
            Object generationValue = run.opt("execution_generation");
            if (controls == null || controls.length() > 128 || !isInteger(generationValue)) {
                return new Parsed("", "需要电脑处理", "", fallback);
            }
            long generation = ((Number) generationValue).longValue();
            if (generation < 1 || generation > MAX_GENERATION) {
                return new Parsed("", "需要电脑处理", "", fallback);
            }
            for (int i = 0; i < controls.length(); i++) {
                JSONObject control = controls.optJSONObject(i);
                if (control == null || !"approval".equals(control.optString("kind", ""))) continue;
                Object itemGeneration = control.opt("execution_generation");
                if (itemGeneration != null && !JSONObject.NULL.equals(itemGeneration)
                        && (!isInteger(itemGeneration)
                        || ((Number) itemGeneration).longValue() != generation)) {
                    return new Parsed("", "需要电脑处理", "", fallback);
                }
                Object promptValue = control.opt("prompt_id");
                if (!(promptValue instanceof String)) {
                    return new Parsed("", "需要电脑处理", "", fallback);
                }
                String token;
                try {
                    token = encode((String) promptValue, generation);
                } catch (IllegalArgumentException invalid) {
                    return new Parsed("", "需要电脑处理", "", fallback);
                }
                JSONObject payload = control.optJSONObject("payload");
                String title = payload == null ? "" : cleanString(payload.opt("title"));
                String description = payload == null ? "" : allMeaningfulStrings(payload,
                        "description", "command", "reason");
                if (description.isEmpty()) {
                    JSONObject display = payload == null ? control : payload;
                    JSONObject safe = sanitizedDetails(display);
                    if (safe == null || safe.length() == 0) {
                        return new Parsed("", "需要电脑处理", "", fallback);
                    }
                    description = safe.toString();
                }
                if (title.length() > 256 || description.length() > 2000
                        || description.trim().isEmpty()) {
                    return new Parsed("", "需要电脑处理", "", fallback);
                }
                return new Parsed(token, title, description, "");
            }
            return new Parsed("", "需要电脑处理", "", fallback);
        }
        JSONObject legacy = run.optJSONObject("approval");
        if (legacy != null) {
            String title = legacy.optString("title", legacy.optString("tool", "需要批准"));
            String description = firstString(legacy, "description", "command", "reason");
            if (title.length() > 256 || description.length() > 2000) {
                title = "需要电脑处理";
                description = fallback;
            }
            return new Parsed("", title, description, "此审批尚未绑定到可验证的请求，请在电脑助手中处理");
        }
        return new Parsed("", "", "", "");
    }

    private static boolean isInteger(Object value) {
        return value instanceof Integer || value instanceof Long;
    }

    private static String firstString(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof String && !((String) value).isEmpty()) return (String) value;
        }
        return "";
    }

    private static String allMeaningfulStrings(JSONObject object, String... keys) {
        StringBuilder result = new StringBuilder();
        for (String key : keys) {
            Object raw = object.opt(key);
            if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) continue;
            if (result.length() > 0) result.append("\n");
            result.append(key).append(": ").append((String) raw);
        }
        return result.toString();
    }

    private static String cleanString(Object value) {
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static JSONObject sanitizedDetails(JSONObject source) {
        JSONObject result = new JSONObject();
        java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (isIdentityField(key) || "title".equals(key) || "tool_name".equals(key)
                    || "tool".equals(key)) continue;
            Object clean = sanitizeValue(source.opt(key));
            if (clean == null) continue;
            try {
                result.put(key, clean);
            } catch (JSONException malformed) {
                return null;
            }
        }
        return result;
    }

    private static Object sanitizeValue(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) return null;
        if (value instanceof String) {
            String text = (String) value;
            return text.trim().isEmpty() ? null : text;
        }
        if (value instanceof JSONObject) {
            JSONObject clean = sanitizedDetails((JSONObject) value);
            return clean == null || clean.length() == 0 ? null : clean;
        }
        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray) value;
            JSONArray clean = new JSONArray();
            for (int i = 0; i < source.length(); i++) {
                Object item = sanitizeValue(source.opt(i));
                if (item != null) clean.put(item);
            }
            return clean.length() == 0 ? null : clean;
        }
        return value;
    }

    private static boolean isIdentityField(String key) {
        return "kind".equals(key) || "prompt_id".equals(key) || "request_id".equals(key)
                || "execution_generation".equals(key) || "session_id".equals(key)
                || "admission_id".equals(key) || "approval_id".equals(key)
                || "run_id".equals(key) || "tool_call_id".equals(key);
    }

    private static void validatePromptId(String promptId) {
        if (promptId == null || promptId.isEmpty()) {
            throw new IllegalArgumentException("invalid approval prompt id");
        }
        for (int i = 0; i < promptId.length(); i++) {
            char ch = promptId.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= promptId.length() || !Character.isLowSurrogate(promptId.charAt(i + 1))) {
                    throw new IllegalArgumentException("invalid approval prompt id");
                }
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("invalid approval prompt id");
            }
            int codePoint = promptId.codePointAt(i);
            if (codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f)) {
                throw new IllegalArgumentException("invalid approval prompt id");
            }
        }
        int first = promptId.codePointAt(0);
        int last = promptId.codePointBefore(promptId.length());
        if (isUnicodeWhitespace(first) || isUnicodeWhitespace(last)) {
            throw new IllegalArgumentException("approval prompt id has surrounding whitespace");
        }
    }

    private static boolean isUnicodeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
