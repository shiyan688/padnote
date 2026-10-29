package com.padnote.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

public final class AgentTaskStatusPresentationTest {
    @Test public void submittingWithKnownRemoteIdMeansComputerReceivedIt() {
        assertTrue(AgentTaskDialogs.computerHasReceipt(AgentTaskStore.Status.SUBMITTING, "remote-uuid"));
        assertFalse(AgentTaskDialogs.computerHasReceipt(AgentTaskStore.Status.SUBMITTING, ""));
        assertFalse(AgentTaskDialogs.computerHasReceipt(AgentTaskStore.Status.SUBMITTING, null));
        assertFalse(AgentTaskDialogs.computerHasReceipt(AgentTaskStore.Status.RUNNING, "remote-uuid"));
    }

    @Test public void uncertainBridgeCanRecoverOnlyAfterAttemptEndsAndWithoutRemoteReceipt() {
        AgentTaskStore.Task bridge=task(AgentConnectionStore.Transport.BRIDGE,"",AgentTaskStore.Status.SUBMITTING);
        assertTrue(AgentTaskDialogs.canOfferSameTaskRecovery(bridge,1,false));
        assertTrue("legacy/interrupted attempt marker is still unknown, never a fresh send", 
                AgentTaskDialogs.canOfferSameTaskRecovery(bridge,-1,false));
        assertFalse(AgentTaskDialogs.canOfferSameTaskRecovery(bridge,1,true));
        assertFalse(AgentTaskDialogs.canOfferSameTaskRecovery(
                task(AgentConnectionStore.Transport.BRIDGE,"remote",AgentTaskStore.Status.SUBMITTING),1,false));
        assertFalse(AgentTaskDialogs.canOfferSameTaskRecovery(
                task(AgentConnectionStore.Transport.BRIDGE,"",AgentTaskStore.Status.COMPLETED),1,false));
        assertFalse(AgentTaskDialogs.canRefreshRemoteStatus(bridge));
        assertTrue(AgentTaskDialogs.canRefreshRemoteStatus(
                task(AgentConnectionStore.Transport.BRIDGE,"remote",AgentTaskStore.Status.RUNNING)));
    }

    @Test public void directUnknownIsNotPromisedIdempotentButSavedUnsentTaskCanBeSent() {
        AgentTaskStore.Task direct=task(AgentConnectionStore.Transport.DIRECT,"",AgentTaskStore.Status.SUBMITTING);
        assertFalse(AgentTaskDialogs.canOfferSameTaskRecovery(direct,1,false));
        assertFalse(AgentTaskDialogs.canSendSavedTask(direct,1,false));
        assertTrue(AgentTaskDialogs.canSendSavedTask(direct,0,false));
        assertFalse(AgentTaskDialogs.canSendSavedTask(direct,0,true));
    }

    @Test public void cachedProgressIsNotShownAfterSubmissionLaneBecomesIdle() {
        assertEquals(AgentTaskSubmissionExecutor.Phase.CONNECTING,
                AgentTaskSubmissionExecutor.visibleProgressPhase(true,
                        AgentTaskSubmissionExecutor.Phase.CONNECTING));
        assertNull("stale CONNECTING cache is ignored after an early gate return",
                AgentTaskSubmissionExecutor.visibleProgressPhase(false,
                        AgentTaskSubmissionExecutor.Phase.CONNECTING));
        assertNull("terminal progress is represented by persisted task state",
                AgentTaskSubmissionExecutor.visibleProgressPhase(true,
                        AgentTaskSubmissionExecutor.Phase.RESULT_UNCONFIRMED));
    }

    @Test public void idleOrTerminalTaskDoesNotKeepDialogRefreshAliveInBackground() {
        assertFalse(AgentTaskDialogs.shouldContinueObserving(
                AgentTaskStore.Status.SUBMITTING,"",false));
        assertFalse(AgentTaskDialogs.shouldContinueObserving(
                AgentTaskStore.Status.COMPLETED,"",false));
        assertTrue("a genuinely active local submission stays observable",
                AgentTaskDialogs.shouldContinueObserving(
                        AgentTaskStore.Status.SUBMITTING,"",true));
        assertTrue("a remote task continues status polling",
                AgentTaskDialogs.shouldContinueObserving(
                        AgentTaskStore.Status.RUNNING,"remote-task",false));
    }

    @Test public void submissionWorkerRechecksTheConfirmedTaskBeforeNetwork() {
        AgentTaskStore.Task bridge=task(AgentConnectionStore.Transport.BRIDGE,"",AgentTaskStore.Status.SUBMITTING);
        assertTrue(AgentTaskSubmissionExecutor.canSubmitStored(bridge,bridge,true,1));
        assertFalse("a receipt arriving after the dialog opened invalidates recovery",
                AgentTaskSubmissionExecutor.canSubmitStored(
                        task(AgentConnectionStore.Transport.BRIDGE,"remote",AgentTaskStore.Status.RUNNING),
                        bridge,true,1));
        AgentTaskStore.Task changedPayload=new AgentTaskStore.Task(bridge.clientTaskId,
                bridge.connectionId,bridge.connectionRevision,bridge.origin,bridge.kind,bridge.transport,
                bridge.credentialRef,bridge.bridgeId,bridge.instanceId,bridge.title,bridge.connectionName,
                bridge.noteId,bridge.noteRevision,"{\"changed\":true}","different",bridge.remoteTaskId,
                bridge.status,bridge.output,bridge.error,bridge.approvalId,bridge.approvalTitle,
                bridge.approvalDescription,bridge.artifacts,bridge.createdAt,bridge.updatedAt);
        assertFalse("a different payload cannot use the confirmed task ID",
                AgentTaskSubmissionExecutor.canSubmitStored(changedPayload,bridge,true,1));
        AgentTaskStore.Task payloadWithoutMatchingDigest=new AgentTaskStore.Task(bridge.clientTaskId,
                bridge.connectionId,bridge.connectionRevision,bridge.origin,bridge.kind,bridge.transport,
                bridge.credentialRef,bridge.bridgeId,bridge.instanceId,bridge.title,bridge.connectionName,
                bridge.noteId,bridge.noteRevision,"{\"changed\":true}",bridge.submissionSha256,
                bridge.remoteTaskId,bridge.status,bridge.output,bridge.error,bridge.approvalId,
                bridge.approvalTitle,bridge.approvalDescription,bridge.artifacts,bridge.createdAt,bridge.updatedAt);
        assertFalse("stored payload bytes are part of the confirmation binding even if its digest field is stale",
                AgentTaskSubmissionExecutor.canSubmitStored(payloadWithoutMatchingDigest,bridge,true,1));
        AgentTaskStore.Task changedTarget=new AgentTaskStore.Task(bridge.clientTaskId,
                bridge.connectionId,bridge.connectionRevision,bridge.origin,bridge.kind,bridge.transport,
                bridge.credentialRef,"different-bridge",bridge.instanceId,bridge.title,bridge.connectionName,
                bridge.noteId,bridge.noteRevision,bridge.submissionJson,bridge.submissionSha256,
                bridge.remoteTaskId,bridge.status,bridge.output,bridge.error,bridge.approvalId,
                bridge.approvalTitle,bridge.approvalDescription,bridge.artifacts,bridge.createdAt,bridge.updatedAt);
        assertFalse("a changed target identity cannot inherit confirmation",
                AgentTaskSubmissionExecutor.canSubmitStored(changedTarget,bridge,true,1));
        AgentTaskStore.Task direct=task(AgentConnectionStore.Transport.DIRECT,"",AgentTaskStore.Status.SUBMITTING);
        assertTrue(AgentTaskSubmissionExecutor.canSubmitStored(direct,direct,false,0));
        assertFalse("a direct attempt that became uncertain while confirmation was open must not send",
                AgentTaskSubmissionExecutor.canSubmitStored(direct,direct,false,1));
        assertFalse("generic submission is only for tasks explicitly marked unsent",
                AgentTaskSubmissionExecutor.canSubmitStored(direct,null,false,1));
    }

    private static AgentTaskStore.Task task(AgentConnectionStore.Transport transport,String remote,
                                            AgentTaskStore.Status status) {
        return new AgentTaskStore.Task("task-id","connection-id",1L,"https://example.test",
                AgentConnectionStore.Kind.HERMES,transport,"credential-ref","bridge-id","instance-id",
                "Task","Computer","note-id",1L,"{}","sha",remote,status,"","","","","",
                Collections.emptyList(),1L,2L);
    }
}
