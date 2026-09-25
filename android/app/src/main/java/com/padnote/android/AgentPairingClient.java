package com.padnote.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** PadNote Bridge v1 pairing. Pairing material is kept only for the active flow. */
final class AgentPairingClient {
    enum ClaimState { PENDING, APPROVED, DENIED, EXPIRED }

    static final class PairingPayload {
        final String endpoint;
        final String bridgeId;
        final String code;
        PairingPayload(String endpoint, String bridgeId, String code) {
            this.endpoint = endpoint; this.bridgeId = bridgeId; this.code = code;
        }
    }

    static final class Session {
        final PairingPayload pairing;
        final String requestId;
        final String pollToken;
        final long expiresAtMillis;
        Session(PairingPayload pairing, String requestId, String pollToken,
                long expiresAtMillis) {
            this.pairing = pairing; this.requestId = requestId;
            this.pollToken = pollToken; this.expiresAtMillis = expiresAtMillis;
        }
    }

    static final class ApprovedConnection {
        final String instanceId;
        final AgentConnectionStore.Kind kind;
        final String name;
        final String token;
        ApprovedConnection(String instanceId, AgentConnectionStore.Kind kind,
                           String name, String token) {
            this.instanceId = instanceId; this.kind = kind; this.name = name; this.token = token;
        }
    }

    static final class Claim {
        final ClaimState state;
        final List<ApprovedConnection> connections;
        Claim(ClaimState state, List<ApprovedConnection> connections) {
            this.state = state;
            this.connections = Collections.unmodifiableList(new ArrayList<>(connections));
        }
    }

    private static final int MAX_JSON = 512 * 1024;
    private final AgentHttpTransport transport;
    AgentPairingClient() { this(AgentHttpTransport.production()); }
    AgentPairingClient(AgentHttpTransport transport) { this.transport = transport; }

    static PairingPayload parse(String text) throws Exception {
        JSONObject json = new JSONObject(text == null ? "" : text.trim());
        if (!"padnote-pair".equals(json.optString("type")) ||
                json.optInt("version", 0) != 1) {
            throw new IllegalArgumentException("不是 PadNote 连接助手配对内容");
        }
        String endpoint = AgentConnectionClient.normalizeEndpoint(json.optString("url", ""));
        String bridgeId = required(json, "bridge_id");
        String code = required(json, "code");
        if (code.length() < 16 || code.length() > 4096) {
            throw new IllegalArgumentException("配对码长度无效");
        }
        return new PairingPayload(endpoint, bridgeId, code);
    }

    Session request(PairingPayload pairing, String deviceId, String deviceName) throws Exception {
        JSONObject body = new JSONObject().put("code", pairing.code)
                .put("device_id", deviceId).put("device_name", deviceName);
        AgentHttpTransport.Response response = post(pairing.endpoint +
                "/padnote/v1/pair/request", body);
        AgentConnectionClient.rejectRedirect(response);
        if (response.status != 202) throw http("配对申请失败", response);
        JSONObject json = new JSONObject(response.utf8());
        if (!"pending".equals(json.optString("status"))) {
            throw new IllegalStateException("配对申请响应状态无效");
        }
        long expires = parseEpochMillis(json.optLong("expires_at", 0L));
        return new Session(pairing, required(json, "request_id"),
                required(json, "poll_token"), expires);
    }

    Claim claim(Session session, String deviceId) throws Exception {
        JSONObject body = new JSONObject().put("request_id", session.requestId)
                .put("poll_token", session.pollToken);
        AgentHttpTransport.Response response = post(session.pairing.endpoint +
                "/padnote/v1/pair/claim", body);
        AgentConnectionClient.rejectRedirect(response);
        if (response.status == 202) return new Claim(ClaimState.PENDING, Collections.emptyList());
        if (response.status == 403) return new Claim(ClaimState.DENIED, Collections.emptyList());
        if (response.status == 410) return new Claim(ClaimState.EXPIRED, Collections.emptyList());
        if (response.status != 200) throw http("领取连接失败", response);
        JSONObject json = new JSONObject(response.utf8());
        if (!session.pairing.bridgeId.equals(json.optString("bridge_id")) ||
                !deviceId.equals(json.optString("device_id"))) {
            throw new IllegalStateException("配对响应不属于本设备或本次电脑");
        }
        JSONArray values = json.optJSONArray("connections");
        if (values == null || values.length() == 0 || values.length() > 32) {
            throw new IllegalStateException("配对响应没有可用 Agent");
        }
        List<ApprovedConnection> connections = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) throw new IllegalStateException("配对连接格式无效");
            String kindValue = required(value, "kind");
            AgentConnectionStore.Kind kind;
            if ("hermes".equals(kindValue)) kind = AgentConnectionStore.Kind.HERMES;
            else if ("openclaw".equals(kindValue)) kind = AgentConnectionStore.Kind.OPENCLAW;
            else throw new IllegalStateException("配对返回未知 Agent 协议");
            // OpenClaw can be listed for diagnosis, but this client must not claim
            // it is executable until the adapter is implemented.
            connections.add(new ApprovedConnection(required(value, "instance_id"), kind,
                    required(value, "name"), required(value, "token")));
        }
        return new Claim(ClaimState.APPROVED, connections);
    }

    private AgentHttpTransport.Response post(String url, JSONObject body) throws Exception {
        return transport.execute(new AgentHttpTransport.Request("POST", new URL(url), "",
                Collections.emptyMap(), body.toString().getBytes(StandardCharsets.UTF_8), MAX_JSON));
    }

    private static IllegalStateException http(String prefix, AgentHttpTransport.Response response) {
        String message = "";
        try {
            JSONObject error = new JSONObject(response.utf8()).optJSONObject("error");
            if (error != null) message = error.optString("message", "");
        } catch (Exception ignored) { }
        return new IllegalStateException(prefix + "（HTTP " + response.status + "）" +
                (message.isEmpty() ? "" : "：" + message));
    }

    private static String required(JSONObject json, String key) {
        String value = json.optString(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("配对数据缺少 " + key);
        return value;
    }

    private static long parseEpochMillis(long value) {
        if (value <= 0L) return System.currentTimeMillis() + 5 * 60_000L;
        return value < 10_000_000_000L ? value * 1000L : value;
    }
}
