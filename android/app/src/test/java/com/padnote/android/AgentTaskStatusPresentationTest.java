package com.padnote.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AgentTaskStatusPresentationTest {
    @Test public void submittingWithKnownRemoteIdMeansComputerReceivedIt() {
        assertTrue(AgentTaskDialogs.computerHasReceipt(AgentTaskStore.Status.SUBMITTING, "remote-uuid"));
        assertFalse(AgentTaskDialogs.computerHasReceipt(AgentTaskStore.Status.SUBMITTING, ""));
        assertFalse(AgentTaskDialogs.computerHasReceipt(AgentTaskStore.Status.SUBMITTING, null));
        assertFalse(AgentTaskDialogs.computerHasReceipt(AgentTaskStore.Status.RUNNING, "remote-uuid"));
    }
}
