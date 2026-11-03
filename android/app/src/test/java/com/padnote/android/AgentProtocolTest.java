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
import java.util.concurrent.atomic.AtomicInteger;
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
                    "\"run_stop\":true,\"run_approval_response\":true,\"artifacts\":true,"+
                    "\"delivery_contract_v1\":true}}";
            return response(request.url, 200, json);
        };
        AgentConnectionClient.ProbeResult result = new AgentConnectionClient(transport)
                .probeDetails(connection);
        assertEquals("bridge-a", result.bridgeId);
        assertEquals("agent-a", result.instanceId);
        assertTrue(result.capabilities.contains("artifacts"));
        assertTrue(Boolean.TRUE.equals(result.featureMap.get("run_submission")));
        assertTrue(Boolean.TRUE.equals(result.featureMap.get("delivery_contract_v1")));
        assertFalse(result.featureMap.containsKey("not_reported"));
    }

    @Test public void bridgeRunDeliverySummaryMatchesOnlyReturnedPositiveSizeArtifacts() throws Exception {
        java.util.Map<String,Boolean> features=new java.util.LinkedHashMap<>();
        features.put("run_submission",true);features.put("run_status",true);
        features.put("delivery_contract_v1",true);
        AgentConnectionStore.Config connection=new AgentConnectionStore.Config("connection-a","Bridge",
                AgentConnectionStore.Kind.HERMES,"https://bridge.test","token","credential-a",
                AgentConnectionStore.Transport.BRIDGE,"bridge-a","agent-a","",7L,1L,
                Arrays.asList("run_status"),features);
        String id="padnote-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String submission="{\"client_task_id\":\""+id+"\",\"input\":\"make a PDF\",\"expected_outputs\":[\"pdf\"]}";
        AgentTaskStore.Task task=new AgentTaskStore.Task(id,connection.id,connection.revision,
                connection.endpoint,connection.kind,connection.transport,connection.credentialRef,
                connection.bridgeId,connection.instanceId,"PDF task",connection.name,"note-a",1L,
                submission,AgentTaskStore.sha256(submission.getBytes(StandardCharsets.UTF_8)),
                "remote-a",AgentTaskStore.Status.RUNNING,"","","","","",Collections.emptyList(),
                1L,1L,"","","","",false,"",Collections.singletonList("pdf"),true,
                AgentDelivery.initial(Collections.singletonList("pdf"),0));
        String valid="{\"task_id\":\"remote-a\",\"instance_id\":\"agent-a\",\"status\":\"completed\","+
                "\"output\":\"\",\"conversation_id\":\"remote-a\",\"parent_task_id\":\"\",\"followup_available\":false,\"followup_reason\":\"\","+
                "\"artifacts\":[{\"id\":\"a\",\"name\":\"result.pdf\",\"media_type\":\"application/pdf\",\"size_bytes\":32,\"sha256\":\""+"a".repeat(64)+"\"}],"+
                "\"delivery\":{\"status\":\"delivered\",\"expected\":[\"pdf\"],\"missing\":[],\"artifact_count\":1}}";
        AgentTaskClient client=new AgentTaskClient(request->response(request.url,200,valid));
        AgentTaskClient.RemoteStatus parsed=client.status(task,connection);
        assertEquals(AgentTaskStore.Status.COMPLETED,parsed.status);
        assertEquals(AgentDelivery.Status.DELIVERED,parsed.delivery.status);
        assertTrue(parsed.delivery.reportedByServer);
        assertEquals(1,parsed.artifacts.size());

        String zeroByte=valid.replace("\"size_bytes\":32","\"size_bytes\":0");
        AgentTaskClient acceptsTaskStateButRejectsZeroByteDelivery=
                new AgentTaskClient(request->response(request.url,200,zeroByte));
        AgentTaskClient.RemoteStatus zeroStatus=acceptsTaskStateButRejectsZeroByteDelivery.status(task,connection);
        assertEquals(AgentTaskStore.Status.COMPLETED,zeroStatus.status);
        assertTrue(zeroStatus.artifacts.isEmpty());
        assertEquals(AgentDelivery.Status.MISSING,zeroStatus.delivery.status);
        assertFalse(zeroStatus.delivery.reportedByServer);

        String pendingJson="{\"client_task_id\":\""+id+"\",\"input\":\"make a PDF\",\"expected_outputs\":[\"pdf\"]}";
        AgentTaskStore.Task pending=new AgentTaskStore.Task(id,connection.id,connection.revision,
                connection.endpoint,connection.kind,connection.transport,connection.credentialRef,
                connection.bridgeId,connection.instanceId,"PDF task",connection.name,"note-a",1L,
                pendingJson,AgentTaskStore.sha256(pendingJson.getBytes(StandardCharsets.UTF_8)),"",
                AgentTaskStore.Status.SUBMITTING,"","","","","",Collections.emptyList(),1L,1L,
                "","","","",false,"",Collections.singletonList("pdf"),true,
                AgentDelivery.initial(Collections.singletonList("pdf"),0));
        String acceptedWithoutSummary="{\"task_id\":\"remote-a\",\"instance_id\":\"agent-a\",\"status\":\"running\","+
                "\"conversation_id\":\"remote-a\",\"parent_task_id\":\"\"}";
        AgentTaskClient.Submission accepted=new AgentTaskClient(request->{
            assertEquals("POST",request.method);
            return response(request.url,202,acceptedWithoutSummary);
        }).submit(pending,connection);
        assertEquals("remote-a",accepted.remoteTaskId);
        assertEquals(AgentTaskStore.Status.RUNNING,accepted.status);
        assertEquals(AgentDelivery.Status.PENDING,accepted.delivery.status);
        assertFalse(accepted.delivery.reportedByServer);
    }

    @Test public void hermesBridgeReadinessReadyUsesExplicitProbeHeader() throws Exception {
        AgentConnectionStore.Config connection = config(Arrays.asList("run_submission"));
        AgentConnectionClient client = new AgentConnectionClient(request -> {
            assertEquals("1", request.headers.get("X-PadNote-Agent-Probe"));
            return response(request.url, 200, "{\"object\":\"padnote.agent.capabilities\",\"protocol_version\":1," +
                    "\"bridge_id\":\"bridge-a\",\"instance_id\":\"agent-a\",\"kind\":\"hermes\"," +
                    "\"features\":{\"run_submission\":true,\"run_status\":true,\"run_stop\":true}," +
                    "\"readiness_probe_supported\":true,\"readiness\":{\"bridge_reachable\":true," +
                    "\"agent_status\":\"ready\",\"source\":\"live\",\"fresh\":true,\"checked_at\":123}}");
        });
        AgentConnectionClient.ProbeResult result = client.probeDetails(connection);
        assertNull(result.partialFailure);
        assertTrue(Boolean.TRUE.equals(result.featureMap.get("run_submission")));
    }

    @Test public void hermesBridgeUnavailableDisablesFutureSubmissionButKeepsReadOnlyCapabilities() throws Exception {
        AgentConnectionStore.Config connection = config(Arrays.asList("run_submission", "task_bundle", "note_context_bundle"));
        AgentConnectionClient.ProbeResult result = new AgentConnectionClient(request ->
                response(request.url, 200, "{\"object\":\"padnote.agent.capabilities\",\"protocol_version\":1," +
                        "\"bridge_id\":\"bridge-a\",\"instance_id\":\"agent-a\",\"kind\":\"hermes\"," +
                        "\"features\":{\"run_submission\":true,\"run_status\":true,\"run_stop\":true," +
                        "\"task_bundle\":true,\"note_context_bundle\":true,\"artifacts\":true,"+
                        "\"delivery_contract_v1\":true}," +
                        "\"readiness_probe_supported\":true,\"readiness\":{\"bridge_reachable\":true," +
                        "\"agent_status\":\"unavailable\",\"source\":\"live\",\"fresh\":true,\"checked_at\":123}}"))
                .probeDetails(connection);
        assertEquals(AgentConnectionStore.ProbeFailure.AGENT_UNAVAILABLE, result.partialFailure);
        assertFalse(Boolean.TRUE.equals(result.featureMap.get("run_submission")));
        assertFalse(Boolean.TRUE.equals(result.featureMap.get("task_bundle")));
        assertFalse(Boolean.TRUE.equals(result.featureMap.get("note_context_bundle")));
        assertFalse(Boolean.TRUE.equals(result.featureMap.get("delivery_contract_v1")));
        assertTrue(Boolean.TRUE.equals(result.featureMap.get("run_status")));
        assertTrue(Boolean.TRUE.equals(result.featureMap.get("artifacts")));
    }

    @Test public void builtinBridgeProbeAllowsReadOnlyCapabilitiesWithoutWorkerOrKey() throws Exception {
        AgentConnectionStore.Config connection = builtinConfig();
        AgentConnectionClient.ProbeResult result = new AgentConnectionClient(request ->
                response(request.url, 200, "{\"object\":\"padnote.agent.capabilities\"," +
                        "\"protocol_version\":1,\"bridge_id\":\"bridge-a\"," +
                        "\"instance_id\":\"agent-a\",\"kind\":\"builtin_video\"," +
                        "\"features\":{\"run_status\":true,\"artifacts\":true," +
                        "\"video_task_submission\":false,\"video_operations\":false}}"))
                .probeDetails(connection);
        assertEquals(Arrays.asList("artifacts", "run_status"), result.capabilities);
        assertEquals(Boolean.FALSE, result.featureMap.get("video_task_submission"));
        assertEquals(Boolean.FALSE, result.featureMap.get("video_operations"));
        assertFalse(result.capabilities.contains("video_task_submission"));
        assertFalse(result.capabilities.contains("video_operations"));
    }

    @Test public void bridgeProbeKeepsOnlyStrictBooleanValues() throws Exception {
        AgentConnectionStore.Config connection = config(Collections.emptyList());
        AgentConnectionClient.ProbeResult result = new AgentConnectionClient(request ->
                response(request.url, 200, "{\"object\":\"padnote.agent.capabilities\",\"protocol_version\":1," +
                        "\"bridge_id\":\"bridge-a\",\"instance_id\":\"agent-a\",\"kind\":\"hermes\"," +
                        "\"features\":{\"run_submission\":\"true\",\"run_status\":true," +
                        "\"run_stop\":1,\"runtime_verified\":false,\"bad_null\":null}}"))
                .probeDetails(connection);
        assertEquals(Collections.singletonList("run_status"), result.capabilities);
        assertEquals(Boolean.FALSE, result.featureMap.get("runtime_verified"));
        assertTrue(result.featureMap.containsKey("run_status"));
        assertFalse(result.featureMap.containsKey("run_submission"));
        assertFalse(result.featureMap.containsKey("run_stop"));
        assertFalse(result.featureMap.containsKey("bad_null"));
    }

    @Test public void directHermesRequiredCapabilityRejectsStringTrue() throws Exception {
        AgentConnectionStore.Config direct = new AgentConnectionStore.Config("direct-a", "Hermes",
                AgentConnectionStore.Kind.HERMES, "https://hermes.test", "token", "credential-a",
                AgentConnectionStore.Transport.DIRECT, "instance-a", 1L, 0L,
                Collections.emptyList());
        AgentConnectionClient client = new AgentConnectionClient(request -> response(request.url, 200,
                "{\"object\":\"hermes.api_server.capabilities\",\"platform\":\"hermes-agent\"," +
                        "\"instance_id\":\"instance-a\",\"features\":{\"run_submission\":\"true\"," +
                        "\"run_status\":true,\"run_stop\":true,\"run_approval_response\":true}}"));
        try { client.probeDetails(direct); fail("string true must not satisfy required Boolean capability"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("缺少能力")); }
    }

    @Test public void builtinBridgeProbeRejectsGenericRunSubmission() throws Exception {
        AgentConnectionStore.Config connection = builtinConfig();
        AgentConnectionClient client = new AgentConnectionClient(request -> response(request.url,
                200, "{\"object\":\"padnote.agent.capabilities\"," +
                        "\"protocol_version\":1,\"bridge_id\":\"bridge-a\"," +
                        "\"instance_id\":\"agent-a\",\"kind\":\"builtin_video\"," +
                        "\"features\":{\"run_submission\":true," +
                        "\"run_status\":true}}"));
        try { client.probeDetails(connection); fail(); }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("协议不匹配"));
        }
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

    @Test public void acceptedPostReceiptSurvivesMalformedOptionalMetadata() throws Exception {
        AgentConnectionStore.Config connection=config(Arrays.asList("run_submission"));
        String taskId="padnote-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String payload="{\"client_task_id\":\""+taskId+"\",\"input\":\"go\",\"expected_outputs\":[\"image\"]}";
        AgentTaskStore.Task task=new AgentTaskStore.Task(taskId,connection.id,connection.revision,
                connection.endpoint,connection.kind,connection.transport,connection.credentialRef,
                connection.bridgeId,connection.instanceId,"Task",connection.name,"note-a",1L,payload,
                AgentTaskStore.sha256(payload.getBytes(StandardCharsets.UTF_8)),"",
                AgentTaskStore.Status.SUBMITTING,"","","","","",Collections.emptyList(),1L,1L,
                "","","","",false,"",Collections.singletonList("image"),true,
                AgentDelivery.initial(Collections.singletonList("image"),0));
        java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
        String body="{\"task_id\":\"remote-a\",\"instance_id\":\"agent-a\",\"status\":\"completed\","+
                "\"output\":\"accepted answer\",\"conversation_id\":7,\"parent_task_id\":[],"+
                "\"followup_available\":\"true\",\"followup_reason\":5,"+
                "\"delivery\":{\"status\":\"delivered\",\"expected\":[\"image\"],"+
                "\"missing\":[],\"artifact_count\":0}}";
        AgentTaskClient.Submission accepted=new AgentTaskClient(request->{
            requests.incrementAndGet();assertEquals("POST",request.method);
            return response(request.url,202,body);
        }).submit(task,connection);
        assertEquals("remote-a",accepted.remoteTaskId);
        assertEquals(AgentTaskStore.Status.COMPLETED,accepted.status);
        assertEquals("accepted answer",accepted.acceptedStatus.output);
        assertFalse(accepted.acceptedStatus.followupAvailable);
        assertEquals(AgentDelivery.Status.MISSING,accepted.delivery.status);
        assertFalse(accepted.delivery.reportedByServer);
        assertEquals("a valid acceptance receipt needs no recovery GET",1,requests.get());
    }

    @Test public void acceptedPostApprovalFieldsAreCarriedWithoutAnotherRequest() throws Exception {
        AgentConnectionStore.Config connection=config(Arrays.asList("run_submission"));
        AgentTaskStore.Task task=task(Collections.emptyList(),"",AgentTaskStore.Status.SUBMITTING);
        java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
        AgentTaskClient.Submission accepted=new AgentTaskClient(request->{
            requests.incrementAndGet();assertEquals("POST",request.method);
            return response(request.url,202,"{\"task_id\":\"remote-a\",\"instance_id\":\"agent-a\","+
                    "\"status\":\"waiting_for_approval\",\"output\":\"before approval\","+
                    "\"approval\":{\"approval_id\":\"approval-a\",\"title\":\"Review\","+
                    "\"description\":\"Synthetic permission check\"}}");
        }).submit(task,connection);
        assertEquals(AgentTaskStore.Status.WAITING_FOR_APPROVAL,accepted.acceptedStatus.status);
        assertEquals("before approval",accepted.acceptedStatus.output);
        assertEquals("approval-a",accepted.acceptedStatus.approvalId);
        assertEquals("Review",accepted.acceptedStatus.approvalTitle);
        assertEquals("Synthetic permission check",accepted.acceptedStatus.approvalDescription);
        assertEquals("one POST acceptance was parsed",1,requests.get());
    }

    @Test public void notePaperSubmissionRechecksRequiredCapabilityBeforeAnyRequest() throws Exception {
        String id="padnote-cccccccccccccccccccccccccccccccc";
        String payload="{\"client_task_id\":\""+id+"\",\"input\":\"go\",\"required_capability\":\"note_context_bundle\"}";
        AgentTaskStore.Task task=new AgentTaskStore.Task(id,"connection-a",7L,
                "https://bridge.test",AgentConnectionStore.Kind.HERMES,
                AgentConnectionStore.Transport.BRIDGE,"credential-a","bridge-a","agent-a",
                "Paper task","Bridge","note-a",4L,payload,
                AgentTaskStore.sha256(payload.getBytes(StandardCharsets.UTF_8)),"",
                AgentTaskStore.Status.SUBMITTING,"","","","","",Collections.emptyList(),1L,1L);
        java.util.Map<String,Boolean> missing=new java.util.HashMap<>();
        missing.put("task_bundle",true);missing.put("run_submission",true);
        AgentConnectionStore.Config changed=new AgentConnectionStore.Config("connection-a","Bridge",
                AgentConnectionStore.Kind.HERMES,"https://bridge.test","token","credential-a",
                AgentConnectionStore.Transport.BRIDGE,"bridge-a","agent-a","",7L,1L,
                Arrays.asList("task_bundle","run_submission"),missing);
        final int[] requests={0};
        try{new AgentTaskClient(request->{requests[0]++;throw new AssertionError("must not request");}).submit(task,changed);fail();}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("纸面上下文"));}
        assertEquals("capability drift must fail before network",0,requests[0]);
    }

    @Test public void followupSubmissionUsesPersistedParentAndRejectsWrongLineage() throws Exception {
        AgentConnectionStore.Config connection = config(Arrays.asList("run_submission"));
        String id="padnote-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String payload="{\"client_task_id\":\""+id+"\",\"parent_task_id\":\"remote-root\",\"input\":\"next\"}";
        AgentTaskStore.Task child = new AgentTaskStore.Task(id,"connection-a",7L,
                "https://bridge.test",AgentConnectionStore.Kind.HERMES,
                AgentConnectionStore.Transport.BRIDGE,"credential-a","bridge-a","agent-a",
                "Task","Bridge","note-a",1L,payload,
                AgentTaskStore.sha256(payload.getBytes(StandardCharsets.UTF_8)),"",
                AgentTaskStore.Status.SUBMITTING,"","","","","",Collections.emptyList(),
                1L,1L,"remote-root","remote-root","padnote-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "",false,"pending");
        AtomicReference<AgentHttpTransport.Request> captured = new AtomicReference<>();
        AgentTaskClient.Submission result = new AgentTaskClient(request -> {
            captured.set(request);
            return response(request.url,202,"{\"task_id\":\"remote-child\",\"instance_id\":\"agent-a\",\"status\":\"running\",\"conversation_id\":\"remote-root\",\"parent_task_id\":\"remote-root\"}");
        }).submit(child,connection);
        assertEquals(child.clientTaskId,captured.get().headers.get("Idempotency-Key"));
        assertEquals(payload,new String(captured.get().body,StandardCharsets.UTF_8));
        assertEquals("remote-root",result.conversationId);
        assertEquals("remote-root",result.parentTaskId);

        AtomicInteger missingLineageRequests = new AtomicInteger();
        AgentTaskClient.Submission missingOptionalLineage = new AgentTaskClient(request -> {
            missingLineageRequests.incrementAndGet();
            return response(request.url,202,
                    "{\"task_id\":\"remote-child\",\"instance_id\":\"agent-a\",\"status\":\"completed\",\"output\":\"child result\"}");
        }).submit(child,connection);
        assertEquals("remote-child",missingOptionalLineage.remoteTaskId);
        assertEquals(AgentTaskStore.Status.COMPLETED,missingOptionalLineage.status);
        assertEquals("remote-root",missingOptionalLineage.conversationId);
        assertEquals("remote-root",missingOptionalLineage.parentTaskId);
        assertNotNull(missingOptionalLineage.acceptedStatus);
        assertEquals("child result",missingOptionalLineage.acceptedStatus.output);
        assertFalse(missingOptionalLineage.acceptedStatus.followupAvailable);
        assertEquals("missing optional lineage must retain the accepted receipt without another request",
                1,missingLineageRequests.get());

        AgentTaskClient bad = new AgentTaskClient(request -> response(request.url,202,
                "{\"task_id\":\"remote-child\",\"instance_id\":\"agent-a\",\"status\":\"running\",\"conversation_id\":\"other-root\",\"parent_task_id\":\"other-parent\"}"));
        try { bad.submit(child,connection); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("父任务")); }
    }

    @Test public void statusRequiresChildLineageAndLegacyStatusCannotEnableFollowup() throws Exception {
        AgentConnectionStore.Config connection = config(Arrays.asList("run_status"));
        AgentTaskStore.Task root=task(Collections.emptyList(),"remote-a",AgentTaskStore.Status.RUNNING);
        AgentTaskClient.RemoteStatus legacy=new AgentTaskClient(request -> response(request.url,200,
                "{\"task_id\":\"remote-a\",\"instance_id\":\"agent-a\",\"status\":\"completed\"}"))
                .status(root,connection);
        assertFalse(legacy.followupAvailable);
        assertFalse(legacy.followupReason.isEmpty());
        AgentTaskClient.RemoteStatus partial=new AgentTaskClient(request -> response(request.url,200,
                "{\"task_id\":\"remote-a\",\"instance_id\":\"agent-a\",\"status\":\"completed\",\"conversation_id\":\"remote-a\",\"parent_task_id\":\"\",\"followup_available\":true}"))
                .status(root,connection);
        assertFalse(partial.followupAvailable);

        AgentTaskStore.Task child = new AgentTaskStore.Task("padnote-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "connection-a",7L,"https://bridge.test",AgentConnectionStore.Kind.HERMES,
                AgentConnectionStore.Transport.BRIDGE,"credential-a","bridge-a","agent-a","Task",
                "Bridge","note-a",1L,"{}","digest","remote-child",AgentTaskStore.Status.RUNNING,
                "","","","","",Collections.emptyList(),1L,1L,"remote-root","remote-root",
                "padnote-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","",false,"");
        AgentTaskClient bad = new AgentTaskClient(request -> response(request.url,200,
                "{\"task_id\":\"remote-child\",\"instance_id\":\"agent-a\",\"status\":\"running\",\"conversation_id\":\"remote-root\",\"parent_task_id\":\"wrong-parent\"}"));
        try { bad.status(child,connection); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("父任务")); }
    }

    @Test public void unknownRemoteStatusIsNotTreatedAsRunning() throws Exception {
        AgentConnectionStore.Config connection = config(Arrays.asList("run_status"));
        AgentTaskStore.Task task = task(Collections.emptyList(), "remote-a", AgentTaskStore.Status.RUNNING);
        AgentTaskClient client = new AgentTaskClient(request -> response(request.url, 200,
                "{\"task_id\":\"remote-a\",\"instance_id\":\"agent-a\",\"status\":\"mystery\"}"));
        try { client.status(task, connection); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("未知")); }
    }

    @Test public void bridgeStopDeliveryAcceptsOnlySevenProtocolValues() throws Exception {
        AgentConnectionStore.Config connection=config(Arrays.asList("run_status"));
        AgentTaskStore.Task task=taskWithApproval("approval-a");
        assertEquals("approval-a",task.approvalId);
        for(String delivery:new String[]{"queued","sending","unknown","confirmed","rejected","not_sent","superseded"}) {
            AgentTaskClient.RemoteStatus remote=new AgentTaskClient(request->response(request.url,200,
                    "{\"task_id\":\"remote-a\",\"instance_id\":\"agent-a\",\"status\":\"running\",\"stop_delivery\":\""+delivery+"\"}"))
                    .status(task,connection);
            assertEquals(delivery,remote.stopDelivery);
        }
        for(String delivery:new String[]{"", "delivered", "QUEUED", "true"}) {
            try { new AgentTaskClient(request->response(request.url,200,
                    "{\"task_id\":\"remote-a\",\"instance_id\":\"agent-a\",\"status\":\"running\",\"stop_delivery\":\""+delivery+"\"}"))
                    .status(task,connection); fail(delivery); }
            catch(IllegalStateException expected) { }
        }
    }

    @Test public void directStatusIgnoresStopDeliveryField() throws Exception {
        AgentTaskStore.Task task=directTask("remote-a",AgentTaskStore.Status.RUNNING,"",0L);
        AgentConnectionStore.Config connection=directConfig(Arrays.asList("run_status"));
        AgentTaskClient.RemoteStatus remote=new AgentTaskClient(request->response(request.url,200,
                "{\"run_id\":\"remote-a\",\"status\":\"running\",\"stop_delivery\":\"bogus\"}"))
                .status(task,connection);
        assertEquals("",remote.stopDelivery);
    }

    @Test public void controlPostsUseBridgeReceiptHeaderAndRejectMismatchedProjections() throws Exception {
        AgentConnectionStore.Config bridge=config(Arrays.asList("run_stop","run_approval_response"));
        AgentTaskStore.Task task=taskWithApproval("approval-a");
        for(String action:new String[]{"stop","approval"}) {
            AgentTaskClient client=new AgentTaskClient(request->{
                assertEquals("1",request.headers.get("X-PadNote-Control-Receipt"));
                return response(request.url,200,"{\"task_id\":\"remote-a\",\"instance_id\":\"agent-a\",\"status\":\"running\"}");
            });
            if("stop".equals(action))client.stop(task,bridge);
            else client.approve(task,bridge,"once");
        }
        for(String badJson:new String[]{
                "{\"task_id\":\"wrong\",\"instance_id\":\"agent-a\",\"status\":\"running\"}",
                "{\"task_id\":\"remote-a\",\"instance_id\":\"wrong\",\"status\":\"running\"}"}) {
            try { new AgentTaskClient(request->response(request.url,200,badJson)).stop(task,bridge); fail(); }
            catch(IllegalStateException expected) { }
        }
        AgentTaskStore.Task direct=directTask("remote-a",AgentTaskStore.Status.WAITING_FOR_APPROVAL,
                AgentApprovalIdentity.encode("prompt-a",1L),1L);
        assertEquals(AgentApprovalIdentity.encode("prompt-a",1L),direct.approvalId);
        AgentConnectionStore.Config directConnection=directConfig(Arrays.asList("run_stop","run_approval_response"));
        for(String action:new String[]{"stop","approval"}) {
            AgentTaskClient client=new AgentTaskClient(request->{
                assertFalse(request.headers.containsKey("X-PadNote-Control-Receipt"));
                return response(request.url,200,"{\"run_id\":\"remote-a\",\"status\":\"running\"}");
            });
            if("stop".equals(action))client.stop(direct,directConnection);
            else client.approve(direct,directConnection,"once");
        }
    }

    @Test public void artifactIsStreamedAndMustMatchLengthAndDigest() throws Exception {
        byte[] bytes = "verified artifact".getBytes(StandardCharsets.UTF_8);
        String sha = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        AgentTaskStore.Artifact artifact = new AgentTaskStore.Artifact(
                "artifact-a", "result.txt", "text/plain", bytes.length, sha);
        AgentTaskStore.Task task = task(Collections.singletonList(artifact), "remote-a",
                AgentTaskStore.Status.COMPLETED);
        AgentConnectionStore.Config connection = config(Arrays.asList("artifacts"));
        AgentArtifactDownloader downloader = new AgentArtifactDownloader((url, token, pin) ->
                new AgentArtifactDownloader.OpenResponse(200, url, url, bytes.length,
                        new ByteArrayInputStream(bytes), null));
        File file = downloader.download(task, connection, artifact, temporary.newFolder());
        assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(file.toPath()));

        AgentArtifactDownloader corrupt = new AgentArtifactDownloader((url, token, pin) ->
                new AgentArtifactDownloader.OpenResponse(200, url, url, bytes.length,
                        new ByteArrayInputStream("different data!!!".getBytes(StandardCharsets.UTF_8)), null));
        File directory = temporary.newFolder();
        try { corrupt.download(task, connection, artifact, directory); fail(); }
        catch (IllegalStateException expected) { }
        assertEquals(0, directory.listFiles().length);

        for(AgentTaskStore.Artifact descriptor:new AgentTaskStore.Artifact[]{
                new AgentTaskStore.Artifact("artifact-a","renamed.txt","text/plain",bytes.length,sha),
                new AgentTaskStore.Artifact("artifact-a","result.txt","image/png",bytes.length,sha)}){
            File rejectDirectory=temporary.newFolder();
            try{downloader.download(task,connection,descriptor,rejectDirectory);fail("changed descriptor must not be accepted");}
            catch(IllegalArgumentException expected){}
            assertEquals(0,rejectDirectory.listFiles().length);
        }
        AgentTaskStore.Artifact oversized=new AgentTaskStore.Artifact("artifact-a","large.png","image/png",
                AgentArtifactDownloader.MAX_IMAGE_BYTES+1,"a".repeat(64));
        AgentTaskStore.Task oversizedTask=task(Collections.singletonList(oversized),"remote-a",AgentTaskStore.Status.COMPLETED);
        File oversizedDirectory=temporary.newFolder();
        try{downloader.downloadImage(oversizedTask,connection,oversized,oversizedDirectory,
                new AgentArtifactDownloader.Cancellation());fail("Agent image byte cap must be enforced before streaming");}
        catch(IllegalArgumentException expected){}
        assertEquals(0,oversizedDirectory.listFiles().length);
    }

    private static AgentConnectionStore.Config config(java.util.List<String> capabilities) {
        return new AgentConnectionStore.Config("connection-a", "Bridge", AgentConnectionStore.Kind.HERMES,
                "https://bridge.test", "token", "credential-a", AgentConnectionStore.Transport.BRIDGE,
                "bridge-a", "agent-a", 7L, 1L, capabilities);
    }

    private static AgentConnectionStore.Config builtinConfig() {
        return new AgentConnectionStore.Config("connection-a", "Built-in",
                AgentConnectionStore.Kind.BUILTIN_VIDEO, "https://bridge.test", "token",
                "credential-a", AgentConnectionStore.Transport.BRIDGE, "bridge-a", "agent-a",
                "a".repeat(64), 7L, 1L, Collections.emptyList());
    }

    private static AgentConnectionStore.Config directConfig(java.util.List<String> capabilities) {
        return new AgentConnectionStore.Config("connection-a","Hermes",AgentConnectionStore.Kind.HERMES,
                "https://hermes.test","token","credential-a",AgentConnectionStore.Transport.DIRECT,
                "","",7L,1L,capabilities);
    }

    private static AgentTaskStore.Task directTask(String remote,AgentTaskStore.Status status,
                                                   String approvalId,long generation) throws Exception {
        String payload="{\"client_task_id\":\"padnote-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"input\":\"go\"}";
        return new AgentTaskStore.Task("padnote-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","connection-a",7L,
                "https://hermes.test",AgentConnectionStore.Kind.HERMES,AgentConnectionStore.Transport.DIRECT,
                "credential-a","","", "Task","Hermes","",0L,payload,
                AgentTaskStore.sha256(payload.getBytes(StandardCharsets.UTF_8)),remote,status,
                "","",approvalId,"","",Collections.emptyList(),1L,1L);
    }

    private static AgentTaskStore.Task taskWithApproval(String approvalId) throws Exception {
        String payload="{\"client_task_id\":\"padnote-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"input\":\"go\"}";
        return new AgentTaskStore.Task("padnote-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","connection-a",7L,
                "https://bridge.test",AgentConnectionStore.Kind.HERMES,AgentConnectionStore.Transport.BRIDGE,
                "credential-a","bridge-a","agent-a","Task","Bridge","note-a",1L,payload,
                AgentTaskStore.sha256(payload.getBytes(StandardCharsets.UTF_8)),"remote-a",
                AgentTaskStore.Status.RUNNING,"","",approvalId,"title","description",Collections.emptyList(),1L,1L);
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
