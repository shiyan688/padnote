package com.padnote.android;

import org.json.JSONObject;

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only capability probe for direct Hermes and paired Bridge profiles. */
final class AgentConnectionClient {
    static final class ProbeResult {
        final String message;
        final String bridgeId;
        final String instanceId;
        final List<String> capabilities;

        ProbeResult(String message, String instanceId, List<String> capabilities) {
            this(message, "", instanceId, capabilities);
        }

        ProbeResult(String message, String bridgeId, String instanceId,
                    List<String> capabilities) {
            this.message = message == null ? "已验证" : message;
            this.bridgeId = bridgeId == null ? "" : bridgeId;
            this.instanceId = instanceId == null ? "" : instanceId;
            this.capabilities = Collections.unmodifiableList(new ArrayList<>(
                    capabilities == null ? Collections.emptyList() : capabilities));
        }
    }

    private static final int MAX_RESPONSE = 512 * 1024;
    private static final String[] DIRECT_REQUIRED = {
            "run_submission", "run_status", "run_stop", "run_approval_response"
    };
    private final AgentHttpTransport transport;

    private AgentConnectionClient() { this(AgentHttpTransport.production()); }
    AgentConnectionClient(AgentHttpTransport transport) { this.transport = transport; }

    static String probe(AgentConnectionStore.Config config) throws Exception {
        return new AgentConnectionClient().probeDetails(config).message;
    }

    static ProbeResult probeResult(AgentConnectionStore.Config config) throws Exception {
        return new AgentConnectionClient().probeDetails(config);
    }

    ProbeResult probeDetails(AgentConnectionStore.Config config) throws Exception {
        if (config == null || !config.complete()) {
            throw new IllegalArgumentException("请填写 Agent 地址和连接令牌");
        }
        String root = normalizeEndpoint(config.endpoint);
        URL url;
        if (config.transport == AgentConnectionStore.Transport.BRIDGE) {
            if (config.instanceId.isEmpty()) throw new IllegalArgumentException("连接缺少 Agent 实例标识");
            url = new URL(root + "/padnote/v1/agents/" + path(config.instanceId) +
                    "/capabilities");
        } else {
            if (config.kind == AgentConnectionStore.Kind.OPENCLAW) {
                throw new IllegalStateException("OpenClaw 需要通过电脑连接助手配对");
            }
            url = new URL(root.endsWith("/v1/capabilities")
                    ? root : root + "/v1/capabilities");
        }
        AgentHttpTransport.Response response = transport.execute(new AgentHttpTransport.Request(
                "GET", url, config.token, Collections.emptyMap(), null, MAX_RESPONSE));
        rejectRedirect(response);
        if (response.status < 200 || response.status >= 300) {
            throw new IllegalStateException("Agent 健康检查失败（HTTP " + response.status + "）");
        }
        JSONObject object = new JSONObject(response.utf8());
        JSONObject features = object.optJSONObject("features");
        if (features == null) throw new IllegalStateException("Agent capabilities 缺少 features");
        List<String> enabled = enabledFeatures(features);
        if (config.transport == AgentConnectionStore.Transport.BRIDGE) {
            if (!"padnote.agent.capabilities".equals(object.optString("object")) ||
                    object.optInt("protocol_version", 0) != 1) {
                throw new IllegalStateException("响应不是 PadNote Bridge capabilities");
            }
            String instanceId = object.optString("instance_id", "");
            String bridgeId = object.optString("bridge_id", "");
            String expectedKind = config.kind == AgentConnectionStore.Kind.HERMES
                    ? "hermes" : "openclaw";
            if (!config.instanceId.equals(instanceId) ||
                    (!config.bridgeId.isEmpty() && !config.bridgeId.equals(bridgeId)) ||
                    !expectedKind.equals(object.optString("kind", ""))) {
                throw new IllegalStateException("电脑或 Agent 实例与已配对连接不匹配");
            }
            return new ProbeResult("连接助手已验证", bridgeId, instanceId, enabled);
        }
        if (!"hermes.api_server.capabilities".equals(object.optString("object")) ||
                !"hermes-agent".equals(object.optString("platform"))) {
            throw new IllegalStateException("响应不是 Hermes Agent capabilities");
        }
        for (String required : DIRECT_REQUIRED) {
            if (!features.optBoolean(required, false)) {
                throw new IllegalStateException("Hermes 缺少能力：" + required);
            }
        }
        return new ProbeResult("Hermes 已验证", object.optString("instance_id", ""), enabled);
    }

    static String normalizeEndpoint(String endpoint) {
        try {
            String normalized = endpoint == null ? "" : endpoint.trim();
            while (normalized.endsWith("/")) normalized = normalized.substring(0,
                    normalized.length() - 1);
            URI uri = new URI(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                    uri.getHost().isEmpty() || uri.getRawUserInfo() != null ||
                    uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Agent 地址必须是无账号、查询或片段的 HTTPS 地址");
            }
            return normalized;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Agent 地址格式无效", error);
        }
    }

    static void rejectRedirect(AgentHttpTransport.Response response) {
        if (response.status >= 300 && response.status < 400 ||
                response.finalUrl == null || !response.requestedUrl.equals(response.finalUrl)) {
            throw new IllegalStateException("Agent 请求不允许重定向");
        }
    }

    private static List<String> enabledFeatures(JSONObject features) {
        List<String> result = new ArrayList<>();
        for (java.util.Iterator<String> keys = features.keys(); keys.hasNext();) {
            String key = keys.next();
            if (features.optBoolean(key, false)) result.add(key);
        }
        Collections.sort(result);
        return result;
    }

    static String path(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }
}
