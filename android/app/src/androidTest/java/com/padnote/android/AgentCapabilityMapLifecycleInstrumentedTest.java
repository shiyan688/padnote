package com.padnote.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Uses only one UUID-named private preference file and synthetic connection values. */
@RunWith(AndroidJUnit4.class)
public final class AgentCapabilityMapLifecycleInstrumentedTest {
    @Test public void renameAndSetConnectedPreserveMapWhileCredentialIdentityAndFailureInvalidateIt()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String preferenceName = "agent-capability-map-" + UUID.randomUUID();
        SharedPreferences preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE);
        AgentConnectionStore store = new AgentConnectionStore(preferences, System::currentTimeMillis);
        try {
            AgentConnectionStore.Config added = store.addBridge("Synthetic map test",
                    AgentConnectionStore.Kind.HERMES, "https://fixture.invalid", "bridge-before",
                    "instance-before", "synthetic-token-1");
            assertTrue(store.applyProbeSuccess(added.id, added.revision,
                    new AgentConnectionClient.ProbeResult("ok", "bridge-before", "instance-before",
                            map("run_submission", true, "run_stop", false))));
            AgentConnectionStore.Config probed = store.get(added.id);
            assertTrue(probed.featureMapAvailable);
            assertEquals(Boolean.FALSE, probed.featureMap.get("run_stop"));

            AgentConnectionStore.Config renamed = store.update(probed.id, probed.revision,
                    "Synthetic map renamed", probed.kind, probed.transport, probed.endpoint, "");
            assertEquals(probed.revision, renamed.revision);
            assertTrue(renamed.verified());
            assertEquals(probed.featureMap, renamed.featureMap);
            assertTrue(renamed.featureMapAvailable);

            store.setConnected(true);
            AgentConnectionStore.Config compatibilityVerified = store.get(added.id);
            assertEquals("bridge-before", compatibilityVerified.bridgeId);
            assertEquals("instance-before", compatibilityVerified.instanceId);
            assertEquals(renamed.featureMap, compatibilityVerified.featureMap);
            assertTrue(compatibilityVerified.featureMapAvailable);

            AgentConnectionStore.Config credentialChanged = store.update(compatibilityVerified.id,
                    compatibilityVerified.revision, compatibilityVerified.name,
                    compatibilityVerified.kind, compatibilityVerified.transport,
                    compatibilityVerified.endpoint, "synthetic-token-2");
            assertEquals(compatibilityVerified.revision + 1L, credentialChanged.revision);
            assertFalse(credentialChanged.verified());
            assertFalse(credentialChanged.featureMapAvailable);
            assertTrue(credentialChanged.featureMap.isEmpty());
            assertTrue(credentialChanged.capabilities.isEmpty());

            assertTrue(store.applyProbeSuccess(credentialChanged.id, credentialChanged.revision,
                    new AgentConnectionClient.ProbeResult("ok", "bridge-before", "instance-before",
                            map("run_status", true, "run_stop", false))));
            AgentConnectionStore.Config beforeMove = store.get(added.id);
            assertTrue(beforeMove.featureMapAvailable);
            String beforeStaleProbe = preferences.getString("connections.v2", "");
            long staleCredentialRevision = probed.revision;
            assertTrue("stale revision must precede the current revision",
                    staleCredentialRevision < beforeMove.revision);
            assertFalse(store.applyProbeSuccess(beforeMove.id, staleCredentialRevision,
                    new AgentConnectionClient.ProbeResult("stale", "bridge-stale", "instance-stale",
                            map("run_submission", true))));
            assertEquals(beforeStaleProbe, preferences.getString("connections.v2", ""));
            assertEquals(Boolean.TRUE, store.get(beforeMove.id).featureMap.get("run_status"));
            assertEquals("bridge-before", store.get(beforeMove.id).bridgeId);

            AgentConnectionStore.Config moved = store.update(beforeMove.id, beforeMove.revision,
                    beforeMove.name, beforeMove.kind, beforeMove.transport,
                    "https://moved.fixture.invalid", "synthetic-token-2");
            assertFalse(moved.verified());
            assertFalse(moved.featureMapAvailable);
            assertTrue(moved.capabilities.isEmpty());

            assertTrue(store.applyProbeSuccess(moved.id, moved.revision,
                    new AgentConnectionClient.ProbeResult("ok", "bridge-after", "instance-after",
                            map("run_submission", true, "runtime_verified", false))));
            AgentConnectionStore.Config current = store.get(moved.id);
            assertTrue(current.featureMapAvailable);
            assertFalse(store.applyProbeSuccess(moved.id, moved.revision - 1,
                    new AgentConnectionClient.ProbeResult("stale", "bridge-old", "instance-old",
                            map("run_submission", false))));
            assertEquals(Boolean.TRUE, store.get(current.id).featureMap.get("run_submission"));
            assertEquals("bridge-after", store.get(current.id).bridgeId);

            assertTrue(store.applyProbeFailure(current.id, current.revision));
            AgentConnectionStore.Config failed = store.get(current.id);
            assertFalse(failed.verified());
            assertFalse(failed.featureMapAvailable);
            assertTrue(failed.capabilities.isEmpty());
            assertTrue(store.delete(current.id));
            assertNull(store.get(current.id));
        } finally {
            try {
                store.clear();
                assertEquals(java.util.Collections.singleton("connections.v2"),
                        preferences.getAll().keySet());
                assertTrue(preferences.getString("connections.v2", "")
                        .contains("\"connections\":[]"));
            } finally {
                assertTrue("delete only this UUID-owned preference file",
                        context.deleteSharedPreferences(preferenceName));
            }
        }
    }

    private static Map<String, Boolean> map(Object... pairs) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            if (pairs[index + 1] instanceof Boolean) {
                result.put(String.valueOf(pairs[index]), (Boolean) pairs[index + 1]);
            }
        }
        return result;
    }
}
