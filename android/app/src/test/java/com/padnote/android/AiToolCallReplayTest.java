package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class AiToolCallReplayTest {
    @Test public void sameRequestReusesCompleteResultAndRejectsChangedArguments()
            throws Exception {
        MainActivity.ToolCallReplayCache cache = new MainActivity.ToolCallReplayCache();
        OpenAiCompatibleClient.ToolCall original = new OpenAiCompatibleClient.ToolCall(
                "call-1", "write_text", new JSONObject().put("content", "A"));
        NoteTool.Result result = NoteTool.Result.mutated(
                new JSONObject().put("flowId", "flow-a"), "已写入");
        cache.record(original, result);

        MainActivity.CachedToolResult replay = cache.find(new OpenAiCompatibleClient.ToolCall(
                "call-1", "write_text", new JSONObject().put("content", "A")));
        assertTrue(replay.matches(original));
        assertTrue(replay.ok);
        assertEquals("flow-a", new JSONObject(replay.payload).getString("flowId"));

        OpenAiCompatibleClient.ToolCall changed = new OpenAiCompatibleClient.ToolCall(
                "call-1", "write_text", new JSONObject().put("content", "B"));
        assertFalse(replay.matches(changed));
    }

    @Test public void repeatedProviderIdInAnotherRequestIsNotSuppressed() throws Exception {
        OpenAiCompatibleClient.ToolCall call = new OpenAiCompatibleClient.ToolCall(
                "call-1", "write_text", new JSONObject().put("content", "A"));
        MainActivity.ToolCallReplayCache firstRequest = new MainActivity.ToolCallReplayCache();
        firstRequest.record(call, NoteTool.Result.ok(new JSONObject().put("ok", 1), "done"));

        MainActivity.ToolCallReplayCache secondRequest = new MainActivity.ToolCallReplayCache();
        assertNull(secondRequest.find(call));
    }
}
