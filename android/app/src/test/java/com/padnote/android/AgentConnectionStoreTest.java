package com.padnote.android;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class AgentConnectionStoreTest {
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
                "\"verifiedAt\":0,\"capabilities\":[]}]}" );
        AgentConnectionStore store = new AgentConnectionStore(backend, () -> 55L);
        assertFalse(store.applyProbeSuccess("c1", 3L,
                new AgentConnectionClient.ProbeResult("ok", "bridge-a", "agent-a", Arrays.asList("run_status"))));
        assertTrue(store.applyProbeSuccess("c1", 4L,
                new AgentConnectionClient.ProbeResult("ok", "bridge-a", "agent-a", Arrays.asList("run_status"))));
        assertEquals(55L, store.get("c1").verifiedAt);
        assertTrue(store.applyProbeFailure("c1", 4L));
        AgentConnectionStore.Config failed = store.get("c1");
        assertEquals(0L, failed.verifiedAt);
        assertEquals("bridge-a", failed.bridgeId);
        assertEquals("agent-a", failed.instanceId);
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
