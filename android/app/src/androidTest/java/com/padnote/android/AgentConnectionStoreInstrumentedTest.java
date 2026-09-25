package com.padnote.android;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public final class AgentConnectionStoreInstrumentedTest {
    private AgentConnectionStore store;

    @Before public void setUp() {
        store = new AgentConnectionStore(InstrumentationRegistry.getInstrumentation()
                .getTargetContext());
        store.clear();
    }

    @After public void tearDown() { store.clear(); }

    @Test public void credentialsRevisionsAndProbeResultsStayProfileScoped() throws Exception {
        AgentConnectionStore.Config one = store.add("One", AgentConnectionStore.Kind.HERMES,
                AgentConnectionStore.Transport.DIRECT, "https://one.test", "token-one");
        AgentConnectionStore.Config two = store.add("Two", AgentConnectionStore.Kind.HERMES,
                AgentConnectionStore.Transport.DIRECT, "https://two.test", "token-two");
        assertNotEquals(one.id, two.id);
        assertNotEquals(one.credentialRef, two.credentialRef);
        assertEquals("token-one", store.get(one.id).token);
        assertEquals("token-two", store.get(two.id).token);

        AgentConnectionStore.Config renamed = store.update(one.id, one.revision, "Home",
                one.kind, one.transport, one.endpoint, "");
        assertEquals(one.revision, renamed.revision);
        assertEquals("token-one", renamed.token);

        AgentConnectionStore.Config moved = store.update(one.id, renamed.revision, renamed.name,
                renamed.kind, renamed.transport, "https://new-one.test", "new-token");
        assertEquals(renamed.revision + 1L, moved.revision);
        assertFalse(moved.verified());
        assertFalse(store.applyProbeSuccess(one.id, renamed.revision,
                new AgentConnectionClient.ProbeResult("stale", "old", Arrays.asList("run_status"))));
        assertTrue(store.applyProbeSuccess(one.id, moved.revision,
                new AgentConnectionClient.ProbeResult("ok", "new-instance",
                        Arrays.asList("run_status", "run_submission"))));
        assertTrue(store.get(one.id).verified());

        assertTrue(store.delete(one.id));
        assertNull(store.get(one.id));
        assertEquals("token-two", store.get(two.id).token);
    }
}
