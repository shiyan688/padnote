package com.padnote.android;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public final class AgentConnectionStoreTest {
    @Test public void sameIdentityEditRetainsAttemptPolicyAndIdentityEditInvalidatesIt() {
        MemoryBackend backend = backendWithProfile(",\"featureMap\":null");
        AgentConnectionStore store = new AgentConnectionStore(backend, () -> 77L);
        String attempt = store.beginProbe("c1", 4L);
        assertEquals(attempt, AgentConnectionStore.attemptAfterUpdate(attempt, false));
        assertTrue(store.applyProbeSuccess("c1", 4L,
                AgentConnectionStore.attemptAfterUpdate(attempt, false),
                new AgentConnectionClient.ProbeResult("ok", "bridge-a", "agent-a",
                        features("run_status", true))));

        String failureAttempt = store.beginProbe("c1", 4L);
        assertEquals(failureAttempt,
                AgentConnectionStore.attemptAfterUpdate(failureAttempt, false));
        assertTrue(store.applyProbeFailure("c1", 4L,
                AgentConnectionStore.attemptAfterUpdate(failureAttempt, false),
                AgentConnectionStore.ProbeFailure.TIMEOUT));

        String staleAttempt = store.beginProbe("c1", 4L);
        assertEquals("", AgentConnectionStore.attemptAfterUpdate(staleAttempt, true));
        String replacementAttempt = store.beginProbe("c1", 4L);
        assertFalse(store.applyProbeFailure("c1", 4L, staleAttempt,
                AgentConnectionStore.ProbeFailure.TIMEOUT));
        assertTrue(store.applyProbeSuccess("c1", 4L, replacementAttempt,
                new AgentConnectionClient.ProbeResult("ok", "bridge-a", "agent-a",
                        features("run_status", true))));
    }

    @Test public void legacyConnectionMigratesOnceWithStableIdentity() {
        MemoryBackend backend = new MemoryBackend();
        backend.strings.put("kind", "HERMES");
        backend.strings.put("endpoint", "https://legacy.test");
        backend.booleans.put("connected", true);
        AgentConnectionStore first = new AgentConnectionStore(backend, () -> 1234L);
        AgentConnectionStore.Config migrated = first.load();
        assertFalse(migrated.id.isEmpty());
        assertEquals("https://legacy.test", migrated.endpoint);
        assertEquals(1234L, migrated.verifiedAt);
        assertFalse(migrated.featureMapAvailable);
        assertFalse(backend.strings.containsKey("endpoint"));

        AgentConnectionStore second = new AgentConnectionStore(backend, () -> 9999L);
        assertEquals(migrated.id, second.load().id);
        assertEquals(1, second.list().size());
    }

    @Test public void failedMigrationLeavesLegacyBytesForRetry() {
        MemoryBackend backend = new MemoryBackend();
        backend.strings.put("endpoint", "https://legacy.test");
        backend.strings.put("token", "encrypted-bytes");
        backend.strings.put("iv", "iv-bytes");
        backend.failNextCommit = true;
        try { new AgentConnectionStore(backend, () -> 1L); fail(); }
        catch (IllegalStateException expected) { }
        assertEquals("encrypted-bytes", backend.strings.get("token"));
        assertEquals("iv-bytes", backend.strings.get("iv"));
        assertFalse(backend.strings.containsKey("connections.v2"));

        new AgentConnectionStore(backend, () -> 2L);
        assertTrue(backend.strings.containsKey("connections.v2"));
        assertFalse(backend.strings.containsKey("token"));
    }

    @Test public void corruptedRegistryIsNotOverwrittenByMutation() {
        MemoryBackend backend = new MemoryBackend();
        backend.strings.put("connections.v2", "not-json");
        AgentConnectionStore store = new AgentConnectionStore(backend, () -> 1L);
        int commits = backend.commits;
        try { store.setDefault("anything"); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("损坏")); }
        assertEquals(commits, backend.commits);
        assertEquals("not-json", backend.strings.get("connections.v2"));
    }

    @Test public void probeCasPreservesBoundIdentityOnFailure() {
        MemoryBackend backend = new MemoryBackend();
        backend.strings.put("connections.v2", "{\"schemaVersion\":2,\"defaultId\":\"c1\"," +
                "\"connections\":[{\"id\":\"c1\",\"name\":\"Bridge\",\"kind\":\"HERMES\"," +
                "\"endpoint\":\"https://bridge.test\",\"credentialRef\":\"\",\"transport\":\"BRIDGE\"," +
                "\"bridgeId\":\"bridge-a\",\"instanceId\":\"agent-a\",\"revision\":4," +
                "\"verifiedAt\":0,\"capabilities\":[],\"featureMap\":{\"run_status\":false}}]}" );
        AgentConnectionStore store = new AgentConnectionStore(backend, () -> 55L);
        assertFalse(store.applyProbeSuccess("c1", 3L,
                new AgentConnectionClient.ProbeResult("ok", "bridge-a", "agent-a", features("run_status", true))));
        assertEquals(0, backend.commits);
        assertTrue(store.get("c1").featureMapAvailable);
        assertEquals(Boolean.FALSE, store.get("c1").featureMap.get("run_status"));
        assertTrue(store.applyProbeSuccess("c1", 4L,
                new AgentConnectionClient.ProbeResult("ok", "bridge-a", "agent-a", features("run_status", true))));
        assertEquals(55L, store.get("c1").verifiedAt);
        assertTrue(store.get("c1").featureMapAvailable);
        assertEquals(Boolean.TRUE, store.get("c1").featureMap.get("run_status"));
        assertTrue(store.applyProbeFailure("c1", 4L));
        AgentConnectionStore.Config failed = store.get("c1");
        assertEquals(0L, failed.verifiedAt);
        assertEquals("bridge-a", failed.bridgeId);
        assertEquals("agent-a", failed.instanceId);
        assertFalse(failed.featureMapAvailable);
        assertTrue(failed.capabilities.isEmpty());
    }

    @Test public void strictProbeMapRoundTripsAndIsAuthoritativeOverLegacyTrueNames() throws Exception {
        MemoryBackend backend = backendWithProfile(",\"featureMap\":null");
        AgentConnectionStore store = new AgentConnectionStore(backend, () -> 200L);
        Map<String, Boolean> serverMap = features("run_submission", false,
                "run_status", true, "mystery_future", true);
        AgentConnectionClient.ProbeResult result = new AgentConnectionClient.ProbeResult(
                "ok", "bridge-a", "agent-a", serverMap);
        serverMap.put("run_submission", true);
        assertEquals(Boolean.FALSE, result.featureMap.get("run_submission"));
        assertTrue(store.applyProbeSuccess("c1", 4L, result));

        AgentConnectionStore.Config saved = store.get("c1");
        assertTrue(saved.featureMapAvailable);
        assertEquals(Boolean.FALSE, saved.featureMap.get("run_submission"));
        assertEquals(Boolean.TRUE, saved.featureMap.get("run_status"));
        assertEquals(Boolean.TRUE, saved.featureMap.get("mystery_future"));
        assertEquals(Arrays.asList("mystery_future", "run_status"), saved.capabilities);
        assertFalse(AgentCapabilitySummary.canSubmitHermesText(saved.kind, saved.verified(),
                saved.capabilities));
        assertThrows(UnsupportedOperationException.class,
                () -> saved.featureMap.put("run_submission", true));

        JSONObject profile = new JSONObject(backend.strings.get("connections.v2"))
                .getJSONArray("connections").getJSONObject(0);
        assertEquals(2, new JSONObject(backend.strings.get("connections.v2")).getInt("schemaVersion"));
        assertEquals(Boolean.FALSE, profile.getJSONObject("featureMap").get("run_submission"));
        assertEquals(Boolean.TRUE, profile.getJSONObject("featureMap").get("run_status"));
        assertEquals(Arrays.asList("mystery_future", "run_status"),
                jsonStrings(profile.getJSONArray("capabilities")));

        AgentConnectionStore reopened = new AgentConnectionStore(backend, () -> 201L);
        AgentConnectionStore.Config reloaded = reopened.get("c1");
        assertEquals(saved.capabilities, reloaded.capabilities);
        assertEquals(saved.featureMap, reloaded.featureMap);
        assertTrue(reloaded.featureMapAvailable);
        assertTrue(reloaded.withoutToken().featureMapAvailable);
        assertEquals(saved.featureMap, reloaded.withoutToken().featureMap);
        reopened.setConnected(true);
        AgentConnectionStore.Config compatibilityVerified = reopened.get("c1");
        assertEquals("bridge-a", compatibilityVerified.bridgeId);
        assertEquals(saved.featureMap, compatibilityVerified.featureMap);
        assertTrue(compatibilityVerified.featureMapAvailable);
    }

    @Test public void legacyListAndNullOrNonObjectMapRemainUnknownWithoutInventingFalse() {
        for (String mapJson : new String[]{"", ",\"featureMap\":null", ",\"featureMap\":[]"}) {
            MemoryBackend backend = backendWithProfile(mapJson);
            AgentConnectionStore store = new AgentConnectionStore(backend, () -> 300L);
            AgentConnectionStore.Config legacy = store.get("c1");
            assertTrue(legacy.verified());
            assertFalse(legacy.featureMapAvailable);
            assertEquals(Collections.singletonList("run_submission"), legacy.capabilities);
            String detail = AgentCapabilitySummary.describe(legacy.kind, legacy.transport,
                    legacy.verified(), legacy.capabilities, legacy.featureMapAvailable, legacy.featureMap);
            assertTrue(detail.contains("旧版验证信息"));
            assertFalse(detail.contains("电脑明确报告不支持"));
        }
    }

    @Test public void objectMapStrictlyDropsNonBooleanValuesAndClearsConflictingLegacyAllowlist() {
        MemoryBackend backend = backendWithProfile(",\"featureMap\":{\"run_submission\":\"true\",\"run_status\":null,\"run_stop\":1}");
        AgentConnectionStore store = new AgentConnectionStore(backend, () -> 300L);
        AgentConnectionStore.Config saved = store.get("c1");
        assertTrue(saved.featureMapAvailable);
        assertTrue(saved.featureMap.isEmpty());
        assertTrue(saved.capabilities.isEmpty());
        assertFalse(AgentCapabilitySummary.canSubmitHermesText(saved.kind, saved.verified(),
                saved.capabilities));

        MemoryBackend explicitFalse = backendWithProfile(",\"featureMap\":{\"run_submission\":false}");
        AgentConnectionStore.Config falseValue = new AgentConnectionStore(explicitFalse, () -> 300L).get("c1");
        assertTrue(falseValue.featureMapAvailable);
        assertEquals(Boolean.FALSE, falseValue.featureMap.get("run_submission"));
        assertTrue(falseValue.capabilities.isEmpty());

        MemoryBackend emptyMap = backendWithProfile(",\"featureMap\":{}");
        AgentConnectionStore.Config empty = new AgentConnectionStore(emptyMap, () -> 300L).get("c1");
        assertTrue(empty.featureMapAvailable);
        assertTrue(empty.featureMap.isEmpty());
        assertTrue(empty.capabilities.isEmpty());
    }

    @Test public void failedFeatureMapCommitLeavesPreviousMapAfterReopen() {
        MemoryBackend backend = backendWithProfile(",\"featureMap\":{\"run_submission\":false,\"run_status\":true}");
        AgentConnectionStore store = new AgentConnectionStore(backend, () -> 400L);
        int commitsBefore = backend.commits;
        backend.failNextCommit = true;
        assertFalse(store.applyProbeSuccess("c1", 4L,
                new AgentConnectionClient.ProbeResult("ok", "bridge-a", "agent-a",
                        features("run_submission", true))));
        assertEquals(commitsBefore + 1, backend.commits);
        AgentConnectionStore.Config reopened = new AgentConnectionStore(backend, () -> 401L).get("c1");
        assertEquals(Boolean.FALSE, reopened.featureMap.get("run_submission"));
        assertEquals(Boolean.TRUE, reopened.featureMap.get("run_status"));
        assertTrue(reopened.capabilities.contains("run_status"));
        assertFalse(reopened.capabilities.contains("run_submission"));
    }

    @Test public void probeFailurePersistsByProfileAndOnlyLatestAttemptCanFinish() throws Exception {
        MemoryBackend backend = backendWithProfile(",\"featureMap\":{\"run_submission\":true}");
        JSONObject registry = new JSONObject(backend.strings.get("connections.v2"));
        JSONObject second = new JSONObject(registry.getJSONArray("connections").getJSONObject(0).toString());
        second.put("id", "c2").put("name", "Other").put("revision", 9L)
                .put("endpoint", "https://other.test").put("credentialRef", "credential-two")
                .put("verifiedAt", 88L);
        registry.getJSONArray("connections").put(second);
        backend.strings.put("connections.v2", registry.toString());

        AgentConnectionStore firstStore = new AgentConnectionStore(backend, () -> 400L);
        String oldAttempt = firstStore.beginProbe("c1", 4L);
        String latestAttempt = firstStore.beginProbe("c1", 4L);
        assertFalse(oldAttempt.isEmpty());
        assertFalse(latestAttempt.isEmpty());
        assertNotEquals(oldAttempt, latestAttempt);
        assertFalse(firstStore.applyProbeFailure("c1", 4L, oldAttempt,
                AgentConnectionStore.ProbeFailure.CREDENTIALS));
        assertNull(firstStore.get("c1").probeFailure);

        String otherAttempt = firstStore.beginProbe("c2", 9L);
        String secretSentinel = "Bearer SECRET https://user:pass@host.test/path?key=PRIVATE note=PRIVATE";
        AgentConnectionStore.ProbeFailure safe = AgentConnectionClient.safeFailure(
                new AgentConnectionClient.ProbeFailureException(
                        AgentConnectionStore.ProbeFailure.CREDENTIALS, secretSentinel));
        assertTrue(firstStore.applyProbeFailure("c1", 4L, latestAttempt, safe));
        assertTrue(firstStore.applyProbeFailure("c2", 9L, otherAttempt,
                AgentConnectionStore.ProbeFailure.TIMEOUT));

        AgentConnectionStore reopened = new AgentConnectionStore(backend, () -> 401L);
        assertEquals(AgentConnectionStore.ProbeFailure.CREDENTIALS,
                reopened.get("c1").probeFailure);
        assertEquals(AgentConnectionStore.ProbeFailure.TIMEOUT,
                reopened.get("c2").probeFailure);
        String persisted = backend.strings.get("connections.v2");
        assertFalse(persisted.contains("SECRET"));
        assertFalse(persisted.contains("user:pass"));
        assertFalse(persisted.contains("PRIVATE"));

        String successAttempt = reopened.beginProbe("c1", 4L);
        assertTrue(reopened.applyProbeSuccess("c1", 4L, successAttempt,
                new AgentConnectionClient.ProbeResult("ok", "bridge-a", "agent-a",
                        features("run_submission", true))));
        assertNull(reopened.get("c1").probeFailure);
        assertTrue(reopened.get("c1").verified());
        assertEquals(AgentConnectionStore.ProbeFailure.TIMEOUT, reopened.get("c2").probeFailure);
        assertTrue(reopened.delete("c2"));
        assertNull(reopened.get("c2"));
    }

    @Test public void oldRecordsRemainCompatibleAndUnknownStoredCodeIsBounded() {
        MemoryBackend old = backendWithProfile("");
        AgentConnectionStore oldStore = new AgentConnectionStore(old, () -> 1L);
        assertNull(oldStore.get("c1").probeFailure);

        MemoryBackend newerUnknown = backendWithProfile(",\"probeFailureCode\":\"Bearer secret raw detail\"");
        AgentConnectionStore parsed = new AgentConnectionStore(newerUnknown, () -> 2L);
        assertEquals(AgentConnectionStore.ProbeFailure.UNKNOWN, parsed.get("c1").probeFailure);
        assertFalse(parsed.get("c1").statusLabel().contains("secret"));
    }

    @Test public void failureClassifierUsesExceptionTypesAndNeverTheMessage() {
        String privateDetail = "Authorization: Bearer secret https://u:p@host/path?q=key private note";
        assertEquals(AgentConnectionStore.ProbeFailure.CREDENTIALS,
                AgentConnectionClient.safeFailure(new AgentConnectionClient.ProbeFailureException(
                        AgentConnectionStore.ProbeFailure.CREDENTIALS, privateDetail)));
        assertEquals(AgentConnectionStore.ProbeFailure.UNKNOWN,
                AgentConnectionClient.safeFailure(new RuntimeException(privateDetail)));
        assertEquals(AgentConnectionStore.ProbeFailure.PROTOCOL,
                AgentConnectionClient.safeFailure(new IllegalStateException(privateDetail)));
        assertEquals(AgentConnectionStore.ProbeFailure.TIMEOUT,
                AgentConnectionClient.safeFailure(new java.net.SocketTimeoutException(privateDetail)));
        assertEquals(AgentConnectionStore.ProbeFailure.TLS_IDENTITY,
                AgentConnectionClient.safeFailure(new javax.net.ssl.SSLException(privateDetail)));
        assertEquals(AgentConnectionStore.ProbeFailure.NETWORK,
                AgentConnectionClient.safeFailure(new java.io.IOException(privateDetail)));
    }

    private static MemoryBackend backendWithProfile(String optionalFeatureMap) {
        MemoryBackend backend = new MemoryBackend();
        backend.strings.put("connections.v2", "{\"schemaVersion\":2,\"defaultId\":\"c1\",\"connections\":[{" +
                "\"id\":\"c1\",\"name\":\"Bridge\",\"kind\":\"HERMES\",\"endpoint\":\"https://bridge.test\"," +
                "\"credentialRef\":\"\",\"transport\":\"BRIDGE\",\"bridgeId\":\"bridge-a\"," +
                "\"instanceId\":\"agent-a\",\"revision\":4,\"verifiedAt\":77," +
                "\"capabilities\":[\"run_submission\"]" + optionalFeatureMap + "}]}");
        return backend;
    }

    private static Map<String, Boolean> features(Object... pairs) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            if (pairs[index + 1] instanceof Boolean) map.put(String.valueOf(pairs[index]), (Boolean) pairs[index + 1]);
        }
        return map;
    }

    private static java.util.List<String> jsonStrings(JSONArray values) {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < values.length(); i++) result.add(values.optString(i, ""));
        return result;
    }

    private static final class MemoryBackend implements AgentConnectionStore.Backend {
        final Object lock = new Object();
        final Map<String,String> strings = new LinkedHashMap<>();
        final Map<String,Boolean> booleans = new LinkedHashMap<>();
        boolean failNextCommit;
        int commits;
        @Override public Object transactionLock() { return lock; }
        @Override public String string(String key,String fallback){return strings.getOrDefault(key,fallback);}
        @Override public boolean bool(String key,boolean fallback){return booleans.getOrDefault(key,fallback);}
        @Override public boolean contains(String key){return strings.containsKey(key)||booleans.containsKey(key);}
        @Override public boolean commit(Map<String,String> additions,Map<String,Boolean> flags,
                                        Set<String> removals){
            commits++;
            if(failNextCommit){failNextCommit=false;return false;}
            for(String key:new LinkedHashSet<>(removals)){strings.remove(key);booleans.remove(key);}
            strings.putAll(additions);booleans.putAll(flags);return true;
        }
    }
}
