package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class AiRequestStateTest {
    @Test public void retryKeepsOneUserTurnAndFoldsAcceptedTranscriptOnce() {
        List<OpenAiCompatibleClient.Message> messages = new ArrayList<>();
        messages.add(new OpenAiCompatibleClient.Message("assistant", "earlier"));
        messages.add(new OpenAiCompatibleClient.Message("user", "解释这里"));

        MainActivity.replaceLastUserTurn(messages, "校对后的 x = 2");

        assertEquals(2, messages.size());
        assertEquals("user", messages.get(1).role);
        assertEquals("解释这里\n\n【圈选手写内容的文字转写】\n校对后的 x = 2",
                messages.get(1).content);
    }

    @Test public void cancelledPendingReviewReturnsToReviewWithoutRepeatingOcr() {
        assertEquals(MainActivity.SplitResumeStep.REVIEW,
                MainActivity.splitResumeStep("已有识别文本", false, true));
        assertEquals(MainActivity.SplitResumeStep.ANSWER,
                MainActivity.splitResumeStep("已采用文本", true, true));
        assertEquals(MainActivity.SplitResumeStep.TRANSCRIBE,
                MainActivity.splitResumeStep(null, false, true));
    }

    @Test public void retryRequiresSameSessionSelectionAndNoStartedTools() {
        assertTrue(MainActivity.mayRetryAiRequest(false, 7, 7, true));
        assertFalse(MainActivity.mayRetryAiRequest(true, 7, 7, true));
        assertFalse(MainActivity.mayRetryAiRequest(false, 7, 8, true));
        assertFalse(MainActivity.mayRetryAiRequest(false, 7, 7, false));
    }

    @Test public void documentWriteGuardIncludesNoteIdentityAndRevision() {
        assertTrue(MainActivity.sameAiDocument("note-a", "note-a", 11, 11));
        assertFalse(MainActivity.sameAiDocument("note-a", "note-b", 11, 11));
        assertFalse(MainActivity.sameAiDocument("note-a", "note-a", 11, 12));
    }

    @Test public void legacyFallbackUsesFrozenPermissionAndDocumentRevision() {
        assertTrue(MainActivity.mayApplyLegacyFallback(
                NoteTool.Permission.CREATE_IN_FREE_SPACE, false, true));
        assertFalse(MainActivity.mayApplyLegacyFallback(
                NoteTool.Permission.READ_ONLY, false, true));
        assertFalse(MainActivity.mayApplyLegacyFallback(
                NoteTool.Permission.CREATE_IN_FREE_SPACE, false, false));
        assertFalse(MainActivity.mayApplyLegacyFallback(
                NoteTool.Permission.CREATE_IN_FREE_SPACE, true, true));
    }

    @Test public void oldRevisionCannotExecuteToolsOrLegacyFallback() {
        boolean mayExecuteTools = MainActivity.sameAiDocument(
                "note-a", "note-a", 11, 12);
        assertFalse(mayExecuteTools);
        assertFalse(MainActivity.mayApplyLegacyFallback(
                NoteTool.Permission.CREATE_IN_FREE_SPACE, false, mayExecuteTools));
    }

    @Test public void recipientIdentityIncludesEndpointModelAndCredential() {
        AiConfigStore.Config frozen = new AiConfigStore.Config(
                "https://a.example/v1", "model-a", "secret-a");
        assertTrue(MainActivity.sameAiConfig(frozen, new AiConfigStore.Config(
                "https://a.example/v1", "model-a", "secret-a")));
        assertFalse(MainActivity.sameAiConfig(frozen, new AiConfigStore.Config(
                "https://b.example/v1", "model-a", "secret-a")));
        assertFalse(MainActivity.sameAiConfig(frozen, new AiConfigStore.Config(
                "https://a.example/v1", "model-b", "secret-a")));
        assertFalse(MainActivity.sameAiConfig(frozen, new AiConfigStore.Config(
                "https://a.example/v1", "model-a", "secret-b")));
    }
}
