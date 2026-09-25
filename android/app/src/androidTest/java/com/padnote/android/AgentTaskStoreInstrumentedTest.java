package com.padnote.android;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;

@RunWith(AndroidJUnit4.class)
public final class AgentTaskStoreInstrumentedTest {
    @Test public void taskPersistsSyntheticSourceAndTerminalCannotRegress() throws Exception {
        AgentConnectionStore.Config connection = new AgentConnectionStore.Config(
                "connection-test", "Test computer", AgentConnectionStore.Kind.HERMES,
                "https://bridge.test", "synthetic-token", "credential-test",
                AgentConnectionStore.Transport.BRIDGE, "bridge-test", "instance-test",
                3L, 10L, Arrays.asList("run_submission", "run_status"));
        AgentTaskStore store = new AgentTaskStore(InstrumentationRegistry.getInstrumentation()
                .getTargetContext());
        AgentTaskStore.Task created = store.create(connection, "Text task", "do this",
                "", 1L, null);
        assertTrue(created.noteId.startsWith("text-"));
        assertEquals(created.noteId,
                new JSONObject(created.submissionJson).getJSONObject("source").getString("note_id"));
        assertEquals("Test computer", store.get(created.clientTaskId).connectionName);

        assertTrue(store.applySubmission(created.clientTaskId, connection.id,
                connection.revision, "remote-test", AgentTaskStore.Status.RUNNING));
        AgentTaskClient.RemoteStatus completed = new AgentTaskClient.RemoteStatus(
                AgentTaskStore.Status.COMPLETED, "done", "", "", "", "",
                Collections.emptyList());
        assertTrue(store.applyStatus(created.clientTaskId, connection.id,
                connection.revision, "remote-test", completed));
        AgentTaskClient.RemoteStatus late = new AgentTaskClient.RemoteStatus(
                AgentTaskStore.Status.RUNNING, "", "", "", "", "",
                Collections.emptyList());
        assertFalse(store.applyStatus(created.clientTaskId, connection.id,
                connection.revision, "remote-test", late));
        assertEquals(AgentTaskStore.Status.COMPLETED,
                store.get(created.clientTaskId).status);
    }
}
