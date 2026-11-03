package com.padnote.android;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class AgentDeliveryTest {
    @Test public void expectationsUseSortedWireOrderAndRejectUnknownOrDuplicateKinds() {
        assertEquals(Arrays.asList("audio", "file", "image", "pdf", "video"),
                AgentDelivery.normalize(Arrays.asList("video", "pdf", "audio", "image", "file")));
        assertEquals(Collections.emptyList(), AgentDelivery.normalize(Collections.emptyList()));
        expectIllegal(() -> AgentDelivery.normalize(Arrays.asList("pdf", "pdf")));
        expectIllegal(() -> AgentDelivery.normalize(Collections.singletonList("document")));
    }

    @Test public void localDeliveryRequiresPositiveSizeAndMatchingMimeType() {
        AgentTaskStore.Artifact zeroImage = artifact("image/png", 0L);
        AgentTaskStore.Artifact wrongImage = artifact("image/webp", 120L);
        AgentTaskStore.Artifact pdf = artifact("application/pdf", 20L);
        AgentDelivery.Summary missing = AgentDelivery.forCurrentTask(
                Arrays.asList("image", "pdf"), AgentTaskStore.Status.COMPLETED,
                Arrays.asList(zeroImage, wrongImage));
        assertEquals(AgentDelivery.Status.MISSING, missing.status);
        assertEquals(Arrays.asList("image", "pdf"), missing.missing);
        AgentDelivery.Summary delivered = AgentDelivery.forCurrentTask(
                Arrays.asList("pdf", "image"), AgentTaskStore.Status.COMPLETED,
                Arrays.asList(artifact("image/jpeg", 12L), pdf));
        assertEquals(AgentDelivery.Status.DELIVERED, delivered.status);
        assertFalse(delivered.reportedByServer);
        assertEquals(AgentDelivery.Status.PENDING, AgentDelivery.forCurrentTask(
                Collections.singletonList("pdf"), AgentTaskStore.Status.RUNNING,
                Collections.singletonList(pdf)).status);
        assertEquals(AgentDelivery.Status.NOT_REQUESTED, AgentDelivery.forCurrentTask(
                Collections.emptyList(), AgentTaskStore.Status.COMPLETED,
                Collections.emptyList()).status);
        AgentDelivery.Summary everyKind=AgentDelivery.forCurrentTask(AgentDelivery.KINDS,
                AgentTaskStore.Status.COMPLETED,Arrays.asList(
                        artifact("audio/wav",30L),artifact("application/octet-stream",31L),
                        artifact("image/png",32L),artifact("application/pdf",33L),
                        artifact("video/mp4",34L)));
        assertEquals(AgentDelivery.Status.DELIVERED,everyKind.status);
    }

    @Test public void serverSummaryMustMatchExpectationArtifactsAndActualFileKinds() throws Exception {
        java.util.List<AgentTaskStore.Artifact> artifacts = Collections.singletonList(
                artifact("image/png", 80L));
        JSONObject response = new JSONObject().put("status", "missing")
                .put("expected", new org.json.JSONArray(Arrays.asList("image", "pdf")))
                .put("missing", new org.json.JSONArray(Collections.singletonList("pdf")))
                .put("artifact_count", 1);
        AgentDelivery.Summary parsed = AgentDelivery.fromServer(response,
                Arrays.asList("pdf", "image"), AgentTaskStore.Status.COMPLETED, artifacts);
        assertEquals(AgentDelivery.Status.MISSING, parsed.status);
        assertEquals(Collections.singletonList("pdf"), parsed.missing);
        assertTrue(parsed.reportedByServer);

        JSONObject falseDelivery = new JSONObject().put("status", "delivered")
                .put("expected", new org.json.JSONArray(Collections.singletonList("pdf")))
                .put("missing", new org.json.JSONArray()) .put("artifact_count", 1);
        expectIllegalState(() -> AgentDelivery.fromServer(falseDelivery,
                Collections.singletonList("pdf"), AgentTaskStore.Status.COMPLETED, artifacts));
        JSONObject forged = new JSONObject().put("status", "delivered")
                .put("expected", new org.json.JSONArray(Collections.singletonList("image")))
                .put("missing", new org.json.JSONArray()) .put("artifact_count", 1);
        expectIllegalState(() -> AgentDelivery.fromServer(forged,
                Collections.singletonList("image"), AgentTaskStore.Status.COMPLETED,
                Collections.singletonList(artifact("image/png", 0L))));
        JSONObject duplicate = new JSONObject().put("status", "missing")
                .put("expected", new org.json.JSONArray(Arrays.asList("pdf", "pdf")))
                .put("missing", new org.json.JSONArray(Collections.singletonList("pdf")))
                .put("artifact_count", 0);
        expectIllegalState(() -> AgentDelivery.fromServer(duplicate,
                Collections.singletonList("pdf"), AgentTaskStore.Status.COMPLETED,
                Collections.emptyList()));
    }

    @Test public void missingOrUnusableServerSummaryFallsBackWithoutClaimingServerVerification() {
        java.util.List<AgentTaskStore.Artifact> files=Collections.singletonList(artifact("image/png",80L));
        AgentDelivery.Summary absent=AgentDelivery.fromServerOrLocal(null,
                Collections.singletonList("pdf"),AgentTaskStore.Status.COMPLETED,files);
        assertEquals(AgentDelivery.Status.MISSING,absent.status);
        assertFalse(absent.reportedByServer);
        JSONObject inconsistent=new JSONObject();
        try {
            inconsistent.put("status","delivered").put("expected",
                    new org.json.JSONArray(Collections.singletonList("pdf")))
                    .put("missing",new org.json.JSONArray()).put("artifact_count",1);
        } catch(org.json.JSONException error) { throw new AssertionError(error); }
        AgentDelivery.Summary fallback=AgentDelivery.fromServerOrLocal(inconsistent,
                Collections.singletonList("pdf"),AgentTaskStore.Status.COMPLETED,files);
        assertEquals(Collections.singletonList("pdf"),fallback.missing);
        assertFalse(fallback.reportedByServer);
    }

    private static AgentTaskStore.Artifact artifact(String mime, long size) {
        return new AgentTaskStore.Artifact("artifact", "result", mime, size, "a".repeat(64));
    }

    private static void expectIllegal(Runnable work) {
        try { work.run(); fail("expected invalid delivery input"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void expectIllegalState(Runnable work) {
        try { work.run(); fail("expected invalid server delivery summary"); }
        catch (IllegalStateException expected) { }
    }
}
