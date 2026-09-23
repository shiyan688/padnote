package com.padnote.android;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/** Read-only health check. Task submission is intentionally a later capability. */
final class AgentConnectionClient {
    private AgentConnectionClient() {
    }

    static String probe(AgentConnectionStore.Config config) throws Exception {
        if (config.kind == AgentConnectionStore.Kind.OPENCLAW) {
            throw new IllegalStateException("OpenClaw 连接需要 Gateway WebSocket Bridge，当前版本暂未开启");
        }
        URL url = new URL(withCapabilitiesPath(config.endpoint));
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("Agent 地址必须使用 HTTPS");
        }
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Bearer " + config.token);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = stream == null ? "" : readLimited(stream);
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Hermes 健康检查失败（HTTP " + status + "）");
            }
            JSONObject capabilities = new JSONObject(response);
            if (!"hermes.api_server.capabilities".equals(capabilities.optString("object"))
                    || !"hermes-agent".equals(capabilities.optString("platform"))) {
                throw new IllegalStateException("响应不是 Hermes Agent capabilities");
            }
            JSONObject features = capabilities.optJSONObject("features");
            String[] required = {"run_submission", "run_status", "run_events_sse",
                    "run_stop", "run_approval_response"};
            if (features == null) throw new IllegalStateException("Hermes capabilities 缺少 features");
            for (String feature : required) {
                if (!features.optBoolean(feature, false)) {
                    throw new IllegalStateException("Hermes 缺少能力：" + feature);
                }
            }
            return "Hermes 已连接";
        } finally {
            connection.disconnect();
        }
    }

    private static String withCapabilitiesPath(String endpoint) {
        String normalized = endpoint.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized.endsWith("/v1/capabilities") ? normalized : normalized + "/v1/capabilities";
    }

    private static String readLimited(InputStream stream) throws Exception {
        byte[] buffer = new byte[8192];
        StringBuilder result = new StringBuilder();
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            result.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            if (result.length() > 512 * 1024) throw new IllegalStateException("Agent 响应过大");
        }
        return result.toString();
    }
}
