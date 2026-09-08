package com.padnote.agentprobe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

final class HermesProbe {
    private static final String[] REQUIRED_FEATURES = {
            "run_submission", "run_status", "run_events_sse",
            "run_stop", "run_approval_response"
    };

    private final URI endpoint;
    private final String token;

    HermesProbe(URI endpoint, String token) {
        this.endpoint = EndpointPolicy.validate(endpoint, Set.of("http", "https"), !token.isBlank());
        this.token = token;
    }

    JSONObject probe() throws Exception {
        URI capabilitiesUri = capabilitiesUri(endpoint);
        HttpRequest.Builder request = HttpRequest.newBuilder(capabilitiesUri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", "PadNote-Agent-Probe/0.1")
                .GET();
        if (!token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Hermes capabilities 返回 HTTP " + response.statusCode());
        }

        JSONObject capabilities = new JSONObject(response.body());
        if (!"hermes.api_server.capabilities".equals(capabilities.getString("object"))
                || !"hermes-agent".equals(capabilities.getString("platform"))) {
            throw new IllegalStateException("目标不是 Hermes API Server capabilities 端点");
        }
        JSONObject features = capabilities.getJSONObject("features");
        JSONArray supported = new JSONArray();
        for (String feature : REQUIRED_FEATURES) {
            if (!features.optBoolean(feature, false)) {
                throw new IllegalStateException("Hermes 缺少 PadNote 所需能力：" + feature);
            }
            supported.put(feature);
        }

        return new JSONObject()
                .put("backend", "hermes")
                .put("ready", true)
                .put("protocol", "hermes-api")
                .put("model", capabilities.optString("model", ""))
                .put("capabilities", supported)
                .put("capabilitiesUrl", capabilitiesUri.toString());
    }

    static URI capabilitiesUri(URI endpoint) {
        String value = endpoint.toString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith("/v1/capabilities")) {
            return URI.create(value);
        }
        if (value.endsWith("/v1")) {
            return URI.create(value + "/capabilities");
        }
        return URI.create(value + "/v1/capabilities");
    }
}
