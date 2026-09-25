package com.padnote.android;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

final class OpenAiCompatibleClient {

    /** One request-chain cancellation handle; safe to call from the UI thread. */
    static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private HttpsURLConnection activeConnection;
        private Thread activeThread;

        void cancel() {
            HttpsURLConnection connection;
            synchronized (this) {
                if (!cancelled.compareAndSet(false, true)) return;
                connection = activeConnection;
                // Interrupt while holding the same lock used by unbind. The
                // worker therefore either clears this token's interrupt before
                // returning to its pool, or has already unbound and is not
                // interrupted at all; a late cancel cannot poison a later task.
                if (activeThread != null) activeThread.interrupt();
            }
            if (connection != null) {
                // Some providers block in native/TLS cleanup. Keep cancel()
                // instant for the UI while still forcing the socket closed.
                Thread disconnect = new Thread(connection::disconnect,
                        "padnote-ai-disconnect");
                disconnect.setDaemon(true);
                disconnect.start();
            }
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        void throwIfCancelled() throws RequestCancelledException {
            if (cancelled.get()) throw new RequestCancelledException();
        }

        synchronized void bind(HttpsURLConnection connection)
                throws RequestCancelledException {
            throwIfCancelled();
            activeConnection = connection;
            activeThread = Thread.currentThread();
            if (cancelled.get()) {
                activeConnection = null;
                activeThread = null;
                connection.disconnect();
                throw new RequestCancelledException();
            }
        }

        synchronized void unbind(HttpsURLConnection connection) {
            if (activeConnection == connection) activeConnection = null;
            if (activeThread == Thread.currentThread()) activeThread = null;
            // Executor threads are reused. Clear only the interrupt associated
            // with this cancelled handle so it cannot poison the next request.
            if (cancelled.get()) Thread.interrupted();
        }
    }

    static final class RequestCancelledException extends IOException {
        RequestCancelledException() { super("请求已在本机取消"); }

        RequestCancelledException(Throwable cause) {
            super("请求已在本机取消", cause);
        }
    }

    /** Provider explicitly rejected the standard tools/function-calling request fields. */
    static final class ToolParameterRejectedException extends IOException {
        ToolParameterRejectedException(String message) { super(message); }
    }

    /**
     * One turn of conversation.
     *
     * <p>A turn is either ordinary text, an assistant turn requesting tool calls,
     * or a tool result being handed back. Keeping all three in one type means the
     * transcript stays a single ordered list, which is what providers expect.
     */
    static final class Message {
        final String role;
        final String content;
        /** Tool calls requested by the assistant; empty for ordinary turns. */
        final List<ToolCall> toolCalls;
        /** Set only on {@code role=tool} turns, linking back to the request. */
        final String toolCallId;

        Message(String role, String content) {
            this(role, content, Collections.emptyList(), null);
        }

        Message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
            this.role = role;
            this.content = content;
            this.toolCalls = toolCalls == null ? Collections.emptyList() : toolCalls;
            this.toolCallId = toolCallId;
        }

        static Message toolResult(String toolCallId, String payload) {
            return new Message("tool", payload, Collections.emptyList(), toolCallId);
        }
    }

    /** A single tool invocation requested by the model. */
    static final class ToolCall {
        final String id;
        final String name;
        final JSONObject arguments;

        ToolCall(String id, String name, JSONObject arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments == null ? new JSONObject() : arguments;
        }
    }

    /**
     * What the model returned: prose for the card, plus any tool calls.
     *
     * <p>The split is the mechanism that stops raw model output landing in the
     * note. {@link #content} is addressed to the user and only ever reaches the
     * conversation card; only an explicit {@code write_text} call puts anything on
     * a page. "Decide what belongs in the note" therefore becomes a decision the
     * model makes by choosing to call a tool, not a side effect of replying.
     */
    static final class Completion {
        final String content;
        /** {@link #content} minus inline reasoning; this is what the card shows. */
        final String displayContent;
        final List<ToolCall> toolCalls;
        final boolean wantsToolCalls;

        Completion(String content, String displayContent, List<ToolCall> toolCalls,
                   boolean wantsToolCalls) {
            this.content = content == null ? "" : content;
            this.displayContent = displayContent == null ? "" : displayContent;
            this.toolCalls = toolCalls == null ? Collections.emptyList() : toolCalls;
            this.wantsToolCalls = wantsToolCalls;
        }
    }

    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    /** Guards against a malformed response claiming an absurd number of calls. */
    private static final int MAX_TOOL_CALLS_PER_TURN = 8;
    /**
     * Explicit completion budget sent on every request. Reasoning-style models
     * spend completion tokens on their chain of thought before any visible text,
     * so relying on a provider's small default cap can truncate the answer to
     * literally nothing — observed live as an empty content with
     * {@code finish_reason=length}. Providers clamp this to the model maximum,
     * so a generous ceiling costs nothing.
     */
    private static final int MAX_COMPLETION_TOKENS = 8192;

    private OpenAiCompatibleClient() {
    }

    /** Text-only completion, kept for callers that do not expose tools. */
    static String complete(AiConfigStore.Config config, byte[] selectionPng,
                           List<Message> conversation) throws Exception {
        return completeWithTools(config, selectionPng, conversation, null, null).displayContent;
    }

    /**
     * Completion that may request tool calls.
     *
     * @param selectionPng     the lassoed selection image, or null for a
     *                         text-only request. The split route passes null on
     *                         the answer leg: the transcript already carries the
     *                         handwriting, so a text-only answer model never
     *                         needs (and often cannot accept) an image.
     * @param toolDescriptions PadNote's vendor-neutral tool list from
     *                         {@link NoteToolRegistry#describe()}, or null to
     *                         offer no tools
     * @param pageMapContext   structured page layout sent alongside the image.
     *                         Sent unconditionally rather than made a tool call,
     *                         because every request is a full non-streaming
     *                         round trip and forcing the model to ask "where am
     *                         I?" first would waste one.
     */
    static Completion completeWithTools(AiConfigStore.Config config, byte[] selectionPng,
                                        List<Message> conversation,
                                        JSONArray toolDescriptions,
                                        JSONObject pageMapContext) throws Exception {
        return completeWithTools(config, selectionPng, conversation, toolDescriptions,
                pageMapContext, new Cancellation());
    }

    static Completion completeWithTools(AiConfigStore.Config config, byte[] selectionPng,
                                        List<Message> conversation,
                                        JSONArray toolDescriptions,
                                        JSONObject pageMapContext,
                                        Cancellation cancellation) throws Exception {
        URL url = new URL(resolveChatCompletionsUrl(config.endpoint));
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("API 地址必须使用 HTTPS");
        }
        JSONObject request = buildRequest(config.model, selectionPng, conversation,
                toolDescriptions, pageMapContext);
        return post(config, url, request, cancellation);
    }

    /**
     * Turns the selection image into Markdown/LaTeX text before any reasoning
     * happens. This is the first leg of the split route: a transcription model
     * only has to read handwriting accurately, and the cheaper text-only answer
     * leg then reasons over the transcript instead of the pixels.
     */
    static String transcribe(AiConfigStore.Config config, byte[] selectionPng) throws Exception {
        return transcribe(config, selectionPng, new Cancellation());
    }

    static String transcribe(AiConfigStore.Config config, byte[] selectionPng,
                             Cancellation cancellation) throws Exception {
        return transcribeWithPrompt(config, selectionPng,
                "你是手写笔记转写器。把图片中的手写内容原样转写为 Markdown 文本："
                        + "数学公式用标准 LaTeX，行间公式放在 \\[ 与 \\] 之间，"
                        + "行内公式放在 \\( 与 \\) 之间；"
                        + "保留原有的标题、列表、段落和空间顺序；"
                        + "不要回答、讲解或补充图片里的内容，只输出转写结果；"
                        + "无法辨认的字符用【无法辨认】标注，不要臆测。", cancellation);
    }

    /** Same call with a caller-supplied contract; used by vault digitization. */
    static String transcribeWithPrompt(AiConfigStore.Config config, byte[] selectionPng,
                                       String systemPrompt) throws Exception {
        return transcribeWithPrompt(config, selectionPng, systemPrompt, new Cancellation());
    }

    static String transcribeWithPrompt(AiConfigStore.Config config, byte[] selectionPng,
                                       String systemPrompt, Cancellation cancellation)
            throws Exception {
        URL url = new URL(resolveChatCompletionsUrl(config.endpoint));
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("API 地址必须使用 HTTPS");
        }
        return post(config, url,
                buildTranscribeRequest(config.model, selectionPng, systemPrompt),
                cancellation).displayContent;
    }

    static Completion post(AiConfigStore.Config config, URL url,
                           JSONObject request, Cancellation cancellation) throws Exception {
        byte[] requestBytes = request.toString().getBytes(StandardCharsets.UTF_8);
        Cancellation active = cancellation == null ? new Cancellation() : cancellation;
        active.throwIfCancelled();
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        try {
            active.bind(connection);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(90_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "PadNote-Android/0.18");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(requestBytes.length);
            active.throwIfCancelled();
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBytes);
            }
            active.throwIfCancelled();
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String responseText;
            try (InputStream response = stream) {
                responseText = response == null ? "" : readLimited(response);
            }
            active.throwIfCancelled();
            if (status < 200 || status >= 300) {
                String providerMessage = providerError(status, responseText);
                if (request.has("tools") && explicitlyRejectsTools(status, responseText)) {
                    throw new ToolParameterRejectedException(providerMessage);
                }
                throw new IllegalStateException(providerMessage);
            }
            return extractCompletion(new JSONObject(responseText));
        } catch (Exception error) {
            if (active.isCancelled()) throw new RequestCancelledException(error);
            throw error;
        } finally {
            active.unbind(connection);
            connection.disconnect();
        }
    }

    static boolean explicitlyRejectsTools(int status, String responseText) {
        if (status < 400 || status >= 500) return false;
        String value = responseText == null ? "" : responseText.toLowerCase(java.util.Locale.ROOT);
        boolean mentionsField = value.contains("tool_choice") || value.contains("tools")
                || value.contains("function calling") || value.contains("function_call");
        boolean rejects = value.contains("unsupported") || value.contains("not support")
                || value.contains("unknown parameter") || value.contains("unrecognized")
                || value.contains("不支持") || value.contains("未知参数");
        return mentionsField && rejects;
    }

    static String resolveChatCompletionsUrl(String endpoint) {
        String normalized = endpoint == null ? "" : endpoint.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        return normalized + "/chat/completions";
    }

    static JSONObject buildRequest(String model, byte[] selectionPng,
                                   List<Message> conversation,
                                   JSONArray toolDescriptions,
                                   JSONObject pageMapContext) throws Exception {
        boolean toolsOffered = toolDescriptions != null && toolDescriptions.length() > 0;
        boolean vaultToolsOffered = hasTool(toolDescriptions, "search_vault")
                && hasTool(toolDescriptions, "read_vault_note");
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", toolsOffered
                ? toolSystemPrompt(vaultToolsOffered) : plainSystemPrompt());
        messages.put(system);

        if (pageMapContext != null) {
            // Layout travels with the first request instead of behind a tool call:
            // every exchange is a full non-streaming round trip, so making the
            // model ask "what does the page look like?" would cost a whole turn.
            JSONObject layout = new JSONObject();
            layout.put("role", "system");
            layout.put("content", "当前笔记结构（位置一律用 band 表示，band 1 在页面顶部）：\n"
                    + pageMapContext.toString());
            messages.put(layout);
        }

        boolean imageAttached = false;
        String imageUrl = selectionPng == null ? null
                : "data:image/png;base64," + Base64.encodeToString(selectionPng, Base64.NO_WRAP);
        for (Message message : conversation) {
            JSONObject item = new JSONObject();
            item.put("role", message.role);
            if ("tool".equals(message.role)) {
                // Tool results carry the id of the call they answer, so the model
                // can match a result to the request that produced it.
                item.put("tool_call_id", message.toolCallId == null ? "" : message.toolCallId);
                item.put("content", message.content);
                messages.put(item);
                continue;
            }
            if (!message.toolCalls.isEmpty()) {
                item.put("content", message.content == null ? "" : message.content);
                item.put("tool_calls", encodeToolCalls(message.toolCalls));
                messages.put(item);
                continue;
            }
            if ("user".equals(message.role) && !imageAttached && imageUrl != null) {
                JSONArray content = new JSONArray();
                JSONObject textPart = new JSONObject();
                textPart.put("type", "text");
                textPart.put("text", message.content);
                content.put(textPart);
                JSONObject imagePart = new JSONObject();
                imagePart.put("type", "image_url");
                JSONObject image = new JSONObject();
                image.put("url", imageUrl);
                imagePart.put("image_url", image);
                content.put(imagePart);
                item.put("content", content);
                imageAttached = true;
            } else {
                item.put("content", message.content);
            }
            messages.put(item);
        }

        JSONObject request = new JSONObject();
        request.put("model", model);
        request.put("messages", messages);
        request.put("stream", false);
        request.put("max_tokens", MAX_COMPLETION_TOKENS);
        if (toolsOffered) {
            request.put("tools", toProviderTools(toolDescriptions));
            request.put("tool_choice", "auto");
        }
        return request;
    }

    private static JSONObject buildTranscribeRequest(String model, byte[] selectionPng,
                                                     String systemPrompt)
            throws Exception {
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", systemPrompt);
        messages.put(system);

        JSONObject user = new JSONObject();
        user.put("role", "user");
        JSONArray content = new JSONArray();
        JSONObject textPart = new JSONObject();
        textPart.put("type", "text");
        textPart.put("text", "请转写图片中的手写内容。");
        content.put(textPart);
        JSONObject imagePart = new JSONObject();
        imagePart.put("type", "image_url");
        JSONObject image = new JSONObject();
        image.put("url", "data:image/png;base64,"
                + Base64.encodeToString(selectionPng, Base64.NO_WRAP));
        imagePart.put("image_url", image);
        content.put(imagePart);
        user.put("content", content);
        messages.put(user);

        JSONObject request = new JSONObject();
        request.put("model", model);
        request.put("messages", messages);
        request.put("stream", false);
        request.put("max_tokens", MAX_COMPLETION_TOKENS);
        return request;
    }

    private static String plainSystemPrompt() {
        return "你是 PadNote 的笔记助手。请用中文回答，默认使用清晰的 Markdown；"
                + "数学公式必须使用标准 LaTeX，行间公式放在 \\[ 与 \\] 之间，"
                + "行内公式放在 \\( 与 \\) 之间；"
                + "忠实理解图片中的手写内容，对无法辨认的字符明确说明，不要臆测。";
    }

    /**
     * System prompt for tool-enabled turns.
     *
     * <p>The important instruction is the separation: replying and writing to the
     * note are different acts. Previously the entire reply was pasted onto the
     * page, so acknowledgements and transcription commentary became permanent note
     * content. Now prose stays in the card and only {@code write_text} reaches a
     * page, which makes "what deserves to be in the note" a judgement the model
     * makes deliberately.
     */
    private static String toolSystemPrompt(boolean vaultToolsOffered) {
        String prompt = "你是 PadNote 的笔记助手，工作在一个平板手写笔记应用里。用中文回答。\n\n"
                + "【最重要的规则】你的回复文字只会显示在对话卡片里，不会写进笔记。"
                + "只有调用 write_text 或 draw_diagram 工具，内容才会出现在笔记页面上。"
                + "所以请自己判断哪些内容值得留在笔记里：\n"
                + "- 该写进笔记的：讲解、解法、整理后的要点、公式推导、结论。\n"
                + "- 不该写进笔记的：寒暄、「我看到你圈的是……」这类辨认过程、"
                + "对自己行为的说明、征求意见的话。这些放在回复文字里就好。\n"
                + "- 如果用户只是问问题、或者内容不值得留存，就不要调用 write_text，"
                + "直接回答即可。\n"
                + "- 手写辨认存在歧义时，应当把你的辨认结果写进笔记，"
                + "因为用户日后需要知道你是怎么读的。\n\n"
                + "【位置】不要计算或猜测像素坐标，你看不到渲染结果也量不了高度。"
                + "用位置约束表达意图：最常用的是 relativeTo=\"selection\"、position=\"below\"，"
                + "表示写在用户圈选内容的下方；也可以用 page 加 bands 指定页内条带。\n\n"
                + "【分页】文字过长会自动跨页排版，你不需要判断某页是否写满，"
                + "也不需要手动加页。一次调用写完整段内容即可，"
                + "工具返回值会告诉你实际落在哪几页、是否新增了页面、"
                + "以及是否推移了已有内容。\n\n"
                + "【示意图】需要解释流程、层级、因果或时序关系时，可调用 draw_diagram，"
                + "用 Mermaid 代码生成可编辑图表，不只是把代码留在回复里。每图建议最多 8 个节点，"
                + "标签简短；不支持任意 JavaScript、Python 或外部图片。先看 page map，"
                + "优先选择原文下方空白区域；侧边足够宽时可用 position=right/left。"
                + "图表整体放置，当前页底放不下会移到下一页。不要遮盖已有手写，"
                + "也不要用图表重复已经足够清楚的一句话。\n\n";
        if (vaultToolsOffered) {
            prompt += "【已授权知识库材料】用户为本次对话明确选择了少量格式笔记。"
                    + "search_vault 和 read_vault_note 只能访问这份冻结快照。"
                    + "需要引用时先检索再读取并注明出处；不要整本抄入页面。\n\n";
        }
        return prompt
                + "【数学】公式用标准 LaTeX：行间放在 \\[ 与 \\] 之间，"
                + "行内放在 \\( 与 \\) 之间。无法辨认的字符要明确说明，不要臆测。";
    }

    private static boolean hasTool(JSONArray descriptions, String name) {
        if (descriptions == null) {
            return false;
        }
        for (int index = 0; index < descriptions.length(); index++) {
            JSONObject item = descriptions.optJSONObject(index);
            if (item != null && name.equals(item.optString("name"))) {
                return true;
            }
        }
        return false;
    }

    private static JSONArray encodeToolCalls(List<ToolCall> toolCalls) throws Exception {
        JSONArray encoded = new JSONArray();
        for (ToolCall call : toolCalls) {
            JSONObject function = new JSONObject();
            function.put("name", call.name);
            function.put("arguments", call.arguments.toString());
            JSONObject item = new JSONObject();
            item.put("id", call.id);
            item.put("type", "function");
            item.put("function", function);
            encoded.put(item);
        }
        return encoded;
    }

    /**
     * Converts PadNote's tool descriptions to the OpenAI wire shape.
     *
     * <p>Kept as a separate step so tool definitions stay vendor-neutral; a native
     * provider protocol would add another converter rather than change the tools.
     */
    private static JSONArray toProviderTools(JSONArray described) throws Exception {
        JSONArray tools = new JSONArray();
        for (int index = 0; index < described.length(); index++) {
            JSONObject source = described.getJSONObject(index);
            JSONObject function = new JSONObject();
            function.put("name", source.getString("name"));
            function.put("description", source.optString("description", ""));
            function.put("parameters", source.optJSONObject("parameters") == null
                    ? new JSONObject().put("type", "object") : source.getJSONObject("parameters"));
            JSONObject item = new JSONObject();
            item.put("type", "function");
            item.put("function", function);
            tools.put(item);
        }
        return tools;
    }

    private static Completion extractCompletion(JSONObject response) throws Exception {
        JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IllegalStateException("模型响应中没有 choices");
        }
        JSONObject choice = choices.getJSONObject(0);
        JSONObject message = choice.getJSONObject("message");
        String text = readContentText(message.opt("content"));
        List<ToolCall> toolCalls = readToolCalls(message.optJSONArray("tool_calls"));
        boolean wantsToolCalls = !toolCalls.isEmpty() ||
                "tool_calls".equals(choice.optString("finish_reason", ""));

        // A turn that only requests tool calls legitimately has no prose, so an
        // empty content field is not an error here.
        String visible = visibleText(text);
        if (visible.isEmpty() && toolCalls.isEmpty()) {
            String finishReason = choice.optString("finish_reason", "");
            String hint = "length".equals(finishReason)
                    ? "（finish_reason=length：补全在 token 上限处被截断）"
                    : "";
            throw new IllegalStateException("模型响应中没有可显示文本" + hint);
        }
        // History keeps the raw text (M-series models want their <think> block
        // echoed back); the card only ever sees the visible part.
        return new Completion(text, visible, toolCalls, wantsToolCalls);
    }

    /**
     * Strips inline {@code <think>…</think>} reasoning blocks from model prose.
     *
     * <p>MiniMax M-series embeds its chain of thought directly in {@code content}
     * instead of a separate field. The block must stay in the conversation
     * history (echoing it preserves multi-round tool quality) but must never be
     * shown to the user. An unclosed block — truncation mid-think — is dropped
     * with everything after it, since none of it was meant to be visible.
     */
    static String visibleText(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String result = content;
        int start = result.indexOf("<think>");
        while (start >= 0) {
            int end = result.indexOf("</think>", start);
            if (end < 0) {
                result = result.substring(0, start);
                break;
            }
            result = result.substring(0, start)
                    + result.substring(end + "</think>".length());
            start = result.indexOf("<think>");
        }
        return result.trim();
    }

    private static String readContentText(Object content) {
        if (content instanceof String) {
            return ((String) content).trim();
        }
        if (content instanceof JSONArray) {
            StringBuilder combined = new StringBuilder();
            JSONArray parts = (JSONArray) content;
            for (int index = 0; index < parts.length(); index++) {
                JSONObject part = parts.optJSONObject(index);
                if (part != null && "text".equals(part.optString("type"))) {
                    combined.append(part.optString("text"));
                }
            }
            return combined.toString().trim();
        }
        return "";
    }

    /**
     * Reads requested tool calls, skipping any entry whose arguments are not
     * valid JSON: one malformed call should not discard the rest of the turn.
     */
    private static List<ToolCall> readToolCalls(JSONArray encoded) {
        List<ToolCall> calls = new ArrayList<>();
        if (encoded == null) {
            return calls;
        }
        int limit = Math.min(encoded.length(), MAX_TOOL_CALLS_PER_TURN);
        for (int index = 0; index < limit; index++) {
            JSONObject item = encoded.optJSONObject(index);
            if (item == null) {
                continue;
            }
            JSONObject function = item.optJSONObject("function");
            if (function == null) {
                continue;
            }
            String name = function.optString("name", "").trim();
            if (name.isEmpty()) {
                continue;
            }
            JSONObject arguments = parseArguments(function.opt("arguments"));
            if (arguments == null) {
                continue;
            }
            String id = item.optString("id", "").trim();
            if (id.isEmpty()) {
                // A positional fallback such as "call-0" repeats across rounds, and
                // the caller treats a repeated id as a retry — which silently
                // dropped the second write of a task. Make it unique instead.
                id = "call-" + UUID.randomUUID();
            }
            calls.add(new ToolCall(id, name, arguments));
        }
        return calls;
    }

    /** Providers send arguments as a JSON string; some send an object directly. */
    private static JSONObject parseArguments(Object arguments) {
        if (arguments instanceof JSONObject) {
            return (JSONObject) arguments;
        }
        if (arguments instanceof String) {
            String text = ((String) arguments).trim();
            if (text.isEmpty()) {
                return new JSONObject();
            }
            try {
                return new JSONObject(text);
            } catch (Exception malformed) {
                return null;
            }
        }
        return arguments == null ? new JSONObject() : null;
    }

    private static String providerError(int status, String responseText) {
        try {
            JSONObject response = new JSONObject(responseText);
            JSONObject error = response.optJSONObject("error");
            if (error != null && !error.optString("message").isEmpty()) {
                return "API " + status + "：" + error.optString("message");
            }
        } catch (Exception ignored) {
            // Fall through to a bounded generic message.
        }
        String compact = responseText.replace('\n', ' ').replace('\r', ' ').trim();
        if (compact.length() > 240) {
            compact = compact.substring(0, 240) + "…";
        }
        return compact.isEmpty() ? "API 请求失败（HTTP " + status + '）'
                : "API " + status + "：" + compact;
    }

    private static String readLimited(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("模型响应超过 4 MB 限制");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
