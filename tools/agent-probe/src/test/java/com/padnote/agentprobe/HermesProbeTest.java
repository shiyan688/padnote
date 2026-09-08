package com.padnote.agentprobe;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HermesProbeTest {
    @Test
    public void probesRequiredRunCapabilities() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/capabilities", exchange -> {
            assertEquals("Bearer local-test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            JSONObject features = new JSONObject()
                    .put("run_submission", true)
                    .put("run_status", true)
                    .put("run_events_sse", true)
                    .put("run_stop", true)
                    .put("run_approval_response", true);
            byte[] body = new JSONObject()
                    .put("object", "hermes.api_server.capabilities")
                    .put("platform", "hermes-agent")
                    .put("model", "hermes-agent")
                    .put("features", features)
                    .toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            JSONObject result = new HermesProbe(endpoint, "local-test-token").probe();
            assertTrue(result.getBoolean("ready"));
            assertEquals("hermes", result.getString("backend"));
            assertEquals(5, result.getJSONArray("capabilities").length());
        } finally {
            server.stop(0);
        }
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsChatOnlyHermesServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/capabilities", exchange -> {
            byte[] body = new JSONObject()
                    .put("object", "hermes.api_server.capabilities")
                    .put("platform", "hermes-agent")
                    .put("features", new JSONObject().put("run_submission", true))
                    .toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            new HermesProbe(endpoint, "").probe();
        } finally {
            server.stop(0);
        }
    }
}
