package com.padnote.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete set of actions a model may take on a note.
 *
 * <p>Registration is explicit: a model request that names an unregistered tool is
 * refused without executing anything. Keeping the list short is deliberate — v1
 * covers reading the page, adding text, restyling a flow and moving a flow.
 * Deleting anything, editing strokes and re-flowing content the user already
 * arranged are all absent on purpose.
 */
final class NoteToolRegistry {

    private final Map<String, NoteTool> tools = new LinkedHashMap<>();

    NoteToolRegistry() {
    }

    void register(NoteTool tool) {
        if (tool == null || tool.name() == null || tool.name().trim().isEmpty()) {
            throw new IllegalArgumentException("Tool must have a name");
        }
        if (tools.containsKey(tool.name())) {
            throw new IllegalArgumentException("Duplicate tool: " + tool.name());
        }
        tools.put(tool.name(), tool);
    }

    NoteTool find(String name) {
        return name == null ? null : tools.get(name);
    }

    List<NoteTool> all() {
        return Collections.unmodifiableList(new ArrayList<>(tools.values()));
    }

    boolean isEmpty() {
        return tools.isEmpty();
    }

    /**
     * PadNote's own description of the available tools.
     *
     * <p>Provider-specific shapes are produced from this by the client adapter,
     * so page and tool code never encodes one vendor's wire format.
     */
    JSONArray describe() throws JSONException {
        JSONArray described = new JSONArray();
        for (NoteTool tool : tools.values()) {
            JSONObject entry = new JSONObject();
            entry.put("name", tool.name());
            entry.put("description", tool.description());
            entry.put("parameters", tool.parameterSchema());
            described.put(entry);
        }
        return described;
    }

    /**
     * Executes a call after checking it is registered and permitted.
     *
     * @param granted the authority the user has allowed for this task; a tool
     *                needing more is refused rather than silently downgraded.
     */
    NoteTool.Result invoke(String name, JSONObject arguments, NoteToolContext context,
                           NoteTool.Permission granted) {
        NoteTool tool = find(name);
        if (tool == null) {
            return NoteTool.Result.error("未注册的工具：" + name);
        }
        if (!isPermitted(tool.permission(), granted)) {
            return NoteTool.Result.error(
                    "工具 " + name + " 需要更高权限，请先确认后再执行");
        }
        try {
            return tool.execute(arguments == null ? new JSONObject() : arguments, context);
        } catch (PlacementResolver.Failure failure) {
            // A constraint the engine could not honour is not a crash: hand the
            // reason back so the model can pick a different placement.
            JSONObject payload = new JSONObject();
            try {
                payload.put("error", "placement_unresolvable");
                payload.put("reason", failure.getMessage());
                payload.put("hint", "改用 relativeTo=selection position=below，"
                        + "或先调用 read_page_map 挑一个空白 band");
            } catch (JSONException ignored) {
                // Summary still carries the reason.
            }
            return NoteTool.Result.rejected(payload, failure.getMessage());
        } catch (RuntimeException failure) {
            String reason = failure.getMessage();
            return NoteTool.Result.error("工具 " + name + " 执行失败：" +
                    (reason == null || reason.isEmpty() ? "未知错误" : reason));
        }
    }

    private static boolean isPermitted(NoteTool.Permission required,
                                       NoteTool.Permission granted) {
        if (granted == null) {
            return required == NoteTool.Permission.READ_ONLY;
        }
        return rank(required) <= rank(granted);
    }

    private static int rank(NoteTool.Permission permission) {
        switch (permission) {
            case READ_ONLY: return 0;
            case CREATE_IN_FREE_SPACE: return 1;
            default: return 2;
        }
    }
}
