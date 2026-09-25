package com.padnote.android;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

public final class AgentProtocolTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void endpointRejectsAuthorityAndRoutingAmbiguity() throws Exception {
        assertEquals("https://example.test/base",
                AgentConnectionClient.normalizeEndpoint(" https://example.test/base/ "));
        for (String value : new String[]{"http://example.test", "https://u:p@example.test",
                "https://example.test?q=1", "https://example.test/#fragment"}) {
            try { AgentConnectionClient.normalizeEndpoint(value); fail(value); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void bridgeProbeBindsBridgeInstanceAndCapabilities() throws Exception {
        AgentConnectionStore.Config connection = config(Arrays.asList("run_submission"));
        AgentHttpTransport transport = request -> {
            assertEquals("https://bridge.test/padnote/v1/agents/agent-a/capabilities",
                    request.url.toExternalForm());
            String json = "{\"object\":\"padnote.agent.capabilities\",\"protocol_version\":1," +
                    "\"bridge_id\":\"bridge-a\",\"instance_id\":\"agent-a\",\"kind\":\"hermes\"," +
                    "\"features\":{\"run_submission\":true,\"run_status\":true," +
                    "\"run_stop\":true,\"run_approval_response\":true,\"artifacts\":true}}";
            return response(request.url, 200, json);
        };
        AgentConnectionClient.ProbeResult result = new AgentConnectionClient(transport)
                .probeDetails(connection);
        assertEquals("bridge-a", result.bridgeId);
        assertEquals("agent-a", result.instanceId);
        assertTrue(result.capabilities.contains("artifacts"));
    }

    @Test public void pairingClaimIsBoundToDeviceAndCanReturnSeveralAgents() throws Exception {
        AgentPairingClient.PairingPayload payload = AgentPairingClient.parse(
                "{\"type\":\"padnote-pair\",\"version\":1,\"url\":\"https://bridge.test\"," +
                        "\"bridge_id\":\"bridge-a\",\"code\":\"0123456789abcdef\"}");
        final int[] calls = {0};
        AgentPairingClient client = new AgentPairingClient(request -> {
            calls[0]++;
            if (request.url.getPath().endsWith("/request")) return response(request.url, 202,
                    "{\"request_id\":\"request-a\",\"poll_token\":\"poll-a\"," +
                            "\"expires_at\":1999999999,\"status\":\"pending\"}");
            return response(request.url, 200,
                    "{\"bridge_id\":\"bridge-a\",\"device_id\":\"device-a\",\"connections\":[" +
                            "{\"instance_id\":\"one\",\"kind\":\"hermes\",\"name\":\"One\",\"token\":\"t1\"}," +
                            "{\"instance_id\":\"two\",\"kind\":\"hermes\",\"name\":\"Two\",\"token\":\"t2\"}]}" );
        });
        AgentPairingClient.Session session = client.request(payload, "device-a", "Tablet");
        AgentPairingClient.Claim claim = client.claim(session, "device-a");
        assertEquals(AgentPairingClient.ClaimState.APPROVED, claim.state);
        assertEquals(2, claim.connections.size());
        assertEquals(2, calls[0]);
    }

    @Test public void taskSubmissionKeepsBodyAndIdempotencyKey() throws Exception {
        AgentConnectionStore.Config connection = config(Arrays.asList("run_submission"));
        AgentTaskStore.Task task = task(Collections.emptyList(), "", AgentTaskStore.Status.SUBMITTING);
        AtomicReference<AgentHttpTransport.Request> captured = new AtomicReference<>();
        AgentTaskClient client = new AgentTaskClient(request -> {
            captured.set(request);
            return response(request.url, 202,
                    "{\"task_id\":\"remote-a\",\"instance_id\":\"agent-a\",\"status\":\"running\"}");
        });
        AgentTaskClient.Submission result = client.submit(task, connection);
        assertEquals("remote-a", result.remoteTaskId);
        assertEquals(task.clientTaskId, captured.get().headers.get("Idempotency-Key"));
        assertEquals(task.submissionJson,
                new String(captured.get().body, StandardCharsets.UTF_8));
    }

    @Test public void unknownRemoteStatusIsNotTreatedAsRunning() throws Exception {
        AgentConnectionStore.Config connection = config(Arrays.asList("run_status"));
        AgentTaskStore.Task task = task(Collections.emptyList(), "remote-a", AgentTaskStore.Status.RUNNING);
        AgentTaskClient client = new AgentTaskClient(request -> response(request.url, 200,
                "{\"task_id\":\"remote-a\",\"instance_id\":\"agent-a\",\"status\":\"mystery\"}"));
        try { client.status(task, connection); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("未知")); }
    }

    @Test public void artifactIsStreamedAndMustMatchLengthAndDigest() throws Exception {
        byte[] bytes = "verified artifact".getBytes(StandardCharsets.UTF_8);
        String sha = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        AgentTaskStore.Artifact artifact = new AgentTaskStore.Artifact(
                "artifact-a", "result.txt", "text/plain", bytes.length, sha);
        AgentTaskStore.Task task = task(Collections.singletonList(artifact), "remote-a",
                AgentTaskStore.Status.COMPLETED);
        AgentConnectionStore.Config connection = config(Arrays.asList("artifacts"));
        AgentArtifactDownloader downloader = new AgentArtifactDownloader((url, token) ->
                new AgentArtifactDownloader.OpenResponse(200, url, url, bytes.length,
                        new ByteArrayInputStream(bytes), null));
        File file = downloader.download(task, connection, artifact, temporary.newFolder());
        assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(file.toPath()));

        AgentArtifactDownloader corrupt = new AgentArtifactDownloader((url, token) ->
                new AgentArtifactDownloader.OpenResponse(200, url, url, bytes.length,
                        new ByteArrayInputStream("different data!!!".getBytes(StandardCharsets.UTF_8)), null));
        File directory = temporary.newFolder();
        try { corrupt.download(task, connection, artifact, directory); fail(); }
        catch (IllegalStateException expected) { }
        assertEquals(0, directory.listFiles().length);
    }

    private static AgentConnectionStore.Config config(java.util.List<String> capabilities) {
        return new AgentConnectionStore.Config("connection-a", "Bridge", AgentConnectionStore.Kind.HERMES,
                "https://bridge.test", "token", "credential-a", AgentConnectionStore.Transport.BRIDGE,
                "bridge-a", "agent-a", 7L, 1L, capabilities);
    }

    private static AgentTaskStore.Task task(java.util.List<AgentTaskStore.Artifact> artifacts,
                                             String remote, AgentTaskStore.Status status) throws Exception {
        String payload = "{\"client_task_id\":\"padnote-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"input\":\"go\"}";
        return new AgentTaskStore.Task("padnote-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "connection-a", 7L,
                "https://bridge.test", AgentConnectionStore.Kind.HERMES,
                AgentConnectionStore.Transport.BRIDGE, "credential-a", "bridge-a", "agent-a",
                "Task", "Bridge", "note-a", 1L, payload,
                AgentTaskStore.sha256(payload.getBytes(StandardCharsets.UTF_8)), remote, status,
                "", "", "", "", "", artifacts, 1L, 1L);
    }

    private static AgentHttpTransport.Response response(URL url, int status, String body) {
        return new AgentHttpTransport.Response(status, url, url,
                body.getBytes(StandardCharsets.UTF_8), Collections.emptyMap());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
}
