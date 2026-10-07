package com.padnote.android;

import org.json.JSONObject;

import java.net.URI;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import javax.net.ssl.SSLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Read-only capability probe for direct Hermes and paired Bridge profiles. */
final class AgentConnectionClient {
    static final class ProbeFailureException extends Exception {
        final AgentConnectionStore.ProbeFailure failure;
        ProbeFailureException(AgentConnectionStore.ProbeFailure failure, String safeMessage) {
            super(safeMessage);
            this.failure = failure;
        }
    }

    /** Maps exception types only. Raw messages, URLs, headers and response bodies are discarded. */
    static AgentConnectionStore.ProbeFailure safeFailure(Throwable error) {
        if (error instanceof ProbeFailureException) return ((ProbeFailureException) error).failure;
        if (error instanceof SocketTimeoutException) return AgentConnectionStore.ProbeFailure.TIMEOUT;
        if (error instanceof SSLException) return AgentConnectionStore.ProbeFailure.TLS_IDENTITY;
        if (error instanceof UnknownHostException || error instanceof ConnectException ||
                error instanceof IOException) return AgentConnectionStore.ProbeFailure.NETWORK;
        if (error instanceof IllegalStateException) return AgentConnectionStore.ProbeFailure.PROTOCOL;
        return AgentConnectionStore.ProbeFailure.UNKNOWN;
    }

    static final class ProbeResult {
        final String message;
        final String bridgeId;
        final String instanceId;
        final List<String> capabilities;
        /** null means this result came from a legacy caller without a full feature map. */
        final Map<String, Boolean> featureMap;

        ProbeResult(String message, String instanceId, List<String> capabilities) {
            this(message, "", instanceId, capabilities);
        }

        ProbeResult(String message, String bridgeId, String instanceId,
                    List<String> capabilities) {
            this(message, bridgeId, instanceId, capabilities, null);
        }

        ProbeResult(String message, String bridgeId, String instanceId,
                    Map<String, Boolean> featureMap) {
            this(message, bridgeId, instanceId, trueCapabilities(featureMap), featureMap);
        }

        ProbeResult(String message, String bridgeId, String instanceId,
                    List<String> capabilities, Map<String, Boolean> featureMap) {
            this.message = message == null ? "已验证" : message;
            this.bridgeId = bridgeId == null ? "" : bridgeId;
            this.instanceId = instanceId == null ? "" : instanceId;
            this.featureMap = featureMap == null ? null : strictFeatureMap(featureMap);
            this.capabilities = Collections.unmodifiableList(new ArrayList<>(this.featureMap == null
                    ? (capabilities == null ? Collections.emptyList() : capabilities)
                    : trueCapabilities(this.featureMap)));
        }

        private static Map<String, Boolean> strictFeatureMap(Map<String, Boolean> values) {
            if (values == null) return null;
            Map<String, Boolean> copy = new TreeMap<>();
            for (Map.Entry<String, Boolean> entry : values.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isEmpty() &&
                        entry.getValue() instanceof Boolean) copy.put(entry.getKey(), entry.getValue());
            }
            return Collections.unmodifiableMap(copy);
        }

        private static List<String> trueCapabilities(Map<String, Boolean> values) {
            Map<String, Boolean> strict = strictFeatureMap(values);
            if (strict == null) return Collections.emptyList();
            List<String> result = new ArrayList<>();
            for (Map.Entry<String, Boolean> entry : strict.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) result.add(entry.getKey());
            }
            return Collections.unmodifiableList(result);
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
                "GET", url, config.token, Collections.emptyMap(), null, MAX_RESPONSE,
                config.certSha256));
        rejectRedirect(response);
        if (response.status < 200 || response.status >= 300) {
            if (response.status == 401 || response.status == 403) {
                throw new ProbeFailureException(AgentConnectionStore.ProbeFailure.CREDENTIALS,
                        "连接凭据未通过验证");
            }
            throw new IllegalStateException("Agent 健康检查失败（HTTP " + response.status + "）");
        }
        JSONObject object;
        try { object = new JSONObject(response.utf8()); }
        catch (org.json.JSONException malformed) {
            throw new ProbeFailureException(AgentConnectionStore.ProbeFailure.PROTOCOL,
                    "电脑返回的能力信息格式无效");
        }
        JSONObject features = object.optJSONObject("features");
        if (features == null) throw new IllegalStateException("Agent capabilities 缺少 features");
        Map<String, Boolean> featureMap = booleanFeatures(features);
        if (config.transport == AgentConnectionStore.Transport.BRIDGE) {
            if (!"padnote.agent.capabilities".equals(object.optString("object")) ||
                    object.optInt("protocol_version", 0) != 1) {
                throw new IllegalStateException("响应不是 PadNote Bridge capabilities");
            }
            String instanceId = object.optString("instance_id", "");
            String bridgeId = object.optString("bridge_id", "");
            String expectedKind = config.kind == AgentConnectionStore.Kind.HERMES
                    ? "hermes" : config.kind == AgentConnectionStore.Kind.OPENCLAW
                    ? "openclaw" : "builtin_video";
            if (!config.instanceId.equals(instanceId) ||
                    (!config.bridgeId.isEmpty() && !config.bridgeId.equals(bridgeId)) ||
                    !expectedKind.equals(object.optString("kind", ""))) {
                throw new ProbeFailureException(AgentConnectionStore.ProbeFailure.TLS_IDENTITY,
                        "电脑或 Agent 实例与已配对连接不匹配");
            }
            if (config.kind == AgentConnectionStore.Kind.BUILTIN_VIDEO &&
                    Boolean.TRUE.equals(features.opt("run_submission"))) {
                throw new IllegalStateException("内置视频工作流能力与协议不匹配");
            }
            return new ProbeResult("连接助手已验证", bridgeId, instanceId, featureMap);
        }
        if (!"hermes.api_server.capabilities".equals(object.optString("object")) ||
                !"hermes-agent".equals(object.optString("platform"))) {
            throw new IllegalStateException("响应不是 Hermes Agent capabilities");
        }
        for (String required : DIRECT_REQUIRED) {
            if (!Boolean.TRUE.equals(featureMap.get(required))) {
                throw new IllegalStateException("Hermes 缺少能力：" + required);
            }
        }
        return new ProbeResult("Hermes 已验证", "", object.optString("instance_id", ""), featureMap);
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

    private static Map<String, Boolean> booleanFeatures(JSONObject features) {
        Map<String, Boolean> result = new TreeMap<>();
        for (java.util.Iterator<String> keys = features.keys(); keys.hasNext();) {
            String key = keys.next();
            Object value = features.opt(key);
            if (value instanceof Boolean) result.put(key, (Boolean) value);
        }
        return result;
    }

    static String path(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }
}
