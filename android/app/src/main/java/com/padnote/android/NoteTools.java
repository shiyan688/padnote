package com.padnote.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * The v1 tool set.
 *
 * <p>Scope is deliberately small. There is no tool to delete anything, none to
 * touch handwriting, and none to re-flow content the user already arranged:
 * without recognition the model cannot know what a stroke cluster means, so
 * granting it authority over the user's own marks would be authority it has no
 * basis to exercise.
 *
 * <p>Note also what is <em>not</em> here: nothing lets the model ask "is this page
 * full?" or "add a page". Text flows paginate themselves, so the model writes the
 * whole passage in one call and reads back where it landed. Asking a model to
 * predict layout invites errors it cannot detect; reporting measured results does
 * not.
 */
final class NoteTools {

    /**
     * The narrow read-only surface the knowledge-base tools get.
     *
     * <p>Deliberately not {@link VaultStore} itself: the tool layer states what
     * it needs (list entries, read one file) and storage implements it, so tests
     * can supply an in-memory vault and a future store swap touches nothing here.
     * There is no write method — the model must never edit the vault.
     */
    interface VaultReader {
        List<VaultStore.VaultNote> list();

        String read(String fileName) throws Exception;
    }

    private NoteTools() {
    }

    /** Document tools alone, without knowledge-base access. */
    static NoteToolRegistry createDefault() {
        NoteToolRegistry registry = new NoteToolRegistry();
        registerCore(registry);
        return registry;
    }

    /**
     * Core tools plus the knowledge-base readers.
     *
     * @param vault read-only access to digitized notes; may expose an empty
     *              library, which the readers report as such instead of failing
     */
    static NoteToolRegistry createDefault(VaultReader vault) {
        NoteToolRegistry registry = new NoteToolRegistry();
        registerCore(registry);
        if (vault != null) {
            registry.register(new SearchVault(vault));
            registry.register(new ReadVaultNote(vault));
        }
        return registry;
    }

    private static void registerCore(NoteToolRegistry registry) {
        registry.register(new ReadPageMap());
        registry.register(new WriteText());
        registry.register(new DrawDiagram());
        registry.register(new SetTextFlowStyle());
        registry.register(new MoveTextFlow());
    }

    /** Diagrams share the editable, persisted Markdown flow and its placement contract. */
    private static final class DrawDiagram implements NoteTool {
        @Override public String name() { return "draw_diagram"; }

        @Override public String description() {
            return "在笔记页面插入可编辑的 Mermaid 示意图，适合流程、概念关系、时序和状态变化。"
                    + "先读取页面结构，优先放在相关原文下方的空白处；也可指定左右侧。"
                    + "每张图建议不超过 8 个节点，使用简短中文标签，不画装饰图。"
                    + "图表整体适配 4:3 区域，不拆页；过密时拆成多张。";
        }

        @Override public JSONObject parameterSchema() throws JSONException {
            return new JSONObject().put("type", "object")
                    .put("properties", new JSONObject()
                            .put("code", new JSONObject().put("type", "string")
                                    .put("description", "纯 Mermaid 源码，不含 Markdown 围栏。以 flowchart、graph、"
                                            + "sequenceDiagram 或 stateDiagram-v2 开头；禁止 HTML、配置指令和点击链接。"))
                            .put("placement", placementSchema()))
                    .put("required", new JSONArray().put("code").put("placement"));
        }

        @Override public Permission permission() { return Permission.CREATE_IN_FREE_SPACE; }

        @Override public Result execute(JSONObject arguments, NoteToolContext context) {
            String code = arguments.optString("code", "").trim();
            if (code.length() > 8000 || !code.matches(
                    "(?s)^(?:flowchart\\s+(?:TD|TB|BT|LR|RL)|graph\\s+(?:TD|TB|BT|LR|RL)|sequenceDiagram|stateDiagram-v2)(?:\\s|;).*")) {
                return Result.error("请提供简短的 Mermaid flowchart / sequenceDiagram / stateDiagram-v2 源码");
            }
            if (code.contains("```") || code.contains("%%{") || code.matches("(?is).*<[a-z!/].*")
                    || code.matches("(?is).*\\b(?:click|href)\\b.*")) {
                return Result.error("示意图不支持围栏、HTML、配置指令或点击链接");
            }
            try {
                return new WriteText().execute(new JSONObject()
                        .put("content", "```mermaid\n" + code + "\n```")
                        .put("format", "markdown")
                        .put("placement", arguments.optJSONObject("placement")), context);
            } catch (JSONException error) {
                throw new IllegalStateException(error);
            }
        }
    }

    /** Reads document structure: occupancy, free capacity, ink clusters, sources. */
    private static final class ReadPageMap implements NoteTool {
        @Override
        public String name() {
            return "read_page_map";
        }

        @Override
        public String description() {
            return "读取笔记结构：每页的占用与空白区域（空白以可容纳行数和中文字符数表示）、"
                    + "手写笔迹的位置聚类（不含文字识别）、已有文字流的完整源码，以及当前选区所在位置。"
                    + "规划写入位置前先调用它。位置一律用 band 表示，不要使用像素坐标。";
        }

        @Override
        public JSONObject parameterSchema() throws JSONException {
            JSONObject page = new JSONObject()
                    .put("type", "integer")
                    .put("description", "关注的页码，从 1 开始；省略则以当前选区所在页为中心");
            return new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject().put("page", page))
                    .put("required", new JSONArray());
        }

        @Override
        public Permission permission() {
            return Permission.READ_ONLY;
        }

        @Override
        public Result execute(JSONObject arguments, NoteToolContext context) {
            int focus = arguments.has("page")
                    ? Math.max(0, arguments.optInt("page", 1) - 1)
                    : Math.max(0, context.selectionPageIndex());
            JSONObject map = context.readPageMap(focus, true);
            return Result.ok(map, "已读取第 " + (focus + 1) + " 页及相邻页的结构");
        }
    }

    /**
     * Writes text. Pagination is automatic, so one call may span several pages.
     */
    private static final class WriteText implements NoteTool {
        @Override
        public String name() {
            return "write_text";
        }

        @Override
        public String description() {
            return "在笔记页面上写入文字。通过写入工具生成的内容才会出现在笔记里；"
                    + "你在回复中说的话只显示在对话卡片，不会写进笔记。"
                    + "内容过长会自动跨页排版，不需要你判断某页是否写满，也不需要手动加页。"
                    + "返回值会告知实际落在哪几页、是否新增页面、以及是否推移了已有内容。";
        }

        @Override
        public JSONObject parameterSchema() throws JSONException {
            JSONObject properties = new JSONObject();
            properties.put("content", new JSONObject()
                    .put("type", "string")
                    .put("description", "要写入笔记的内容。默认 Markdown，数学用标准 LaTeX："
                            + "行间 \\[...\\]，行内 \\(...\\)。只写日后重读仍需要的内容，"
                            + "不要写寒暄、辨认过程或对用户的说明。"));
            properties.put("placement", placementSchema());
            properties.put("format", new JSONObject()
                    .put("type", "string")
                    .put("enum", new JSONArray().put("markdown").put("latex"))
                    .put("description", "默认 markdown；整块是单个公式时用 latex"));
            return new JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", new JSONArray().put("content").put("placement"));
        }

        @Override
        public Permission permission() {
            return Permission.CREATE_IN_FREE_SPACE;
        }

        @Override
        public Result execute(JSONObject arguments, NoteToolContext context) {
            String content = arguments.optString("content", "").trim();
            if (content.isEmpty()) {
                return Result.error("content 不能为空");
            }
            if (content.length() > TextFlow.MAX_SOURCE_LENGTH) {
                return Result.error("content 超出单个文本流上限");
            }
            NoteTextBox.Format format = "latex".equalsIgnoreCase(
                    arguments.optString("format", "markdown"))
                    ? NoteTextBox.Format.LATEX : NoteTextBox.Format.MARKDOWN;
            JSONObject report = context.createTextFlow(content, format,
                    resolvePlacement(arguments, context));
            if (report == null) {
                return Result.error("页面正被操作，稍后重试");
            }
            return Result.mutated(report, summaryOf(report));
        }

        private static String summaryOf(JSONObject report) {
            int pages = report.optJSONArray("occupiedPages") == null
                    ? 1 : report.optJSONArray("occupiedPages").length();
            String base = "已写入 " + pages + " 页";
            if (report.optBoolean("addedPage", false)) {
                base += "（新增页面）";
            }
            JSONArray shifted = report.optJSONArray("shiftedFlows");
            if (shifted != null && shifted.length() > 0) {
                base += "，推移了 " + shifted.length() + " 处已有内容";
            }
            return base;
        }
    }

    /** Restyles a flow. Size and leading both re-paginate the whole flow. */
    private static final class SetTextFlowStyle implements NoteTool {
        @Override
        public String name() {
            return "set_text_flow_style";
        }

        @Override
        public String description() {
            return "调整已有文字流的字号、行距或行宽，整组会重新排版。"
                    + "内容装不下时缩小字号是一个选项，但公式推导等场合应保持可读字号。";
        }

        @Override
        public JSONObject parameterSchema() throws JSONException {
            JSONObject properties = new JSONObject();
            properties.put("flowId", new JSONObject()
                    .put("type", "string")
                    .put("description", "来自 read_page_map 的 flowId"));
            properties.put("fontSizeSp", new JSONObject()
                    .put("type", "number")
                    .put("description", "10 到 32"));
            properties.put("lineHeight", new JSONObject()
                    .put("type", "number")
                    .put("description", "1.1 到 2.0，默认 1.35"));
            properties.put("widthDp", new JSONObject().put("type", "number"));
            return new JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", new JSONArray().put("flowId"));
        }

        @Override
        public Permission permission() {
            return Permission.MODIFY_EXISTING;
        }

        @Override
        public Result execute(JSONObject arguments, NoteToolContext context) {
            String flowId = arguments.optString("flowId", "").trim();
            if (flowId.isEmpty()) {
                return Result.error("缺少 flowId");
            }
            JSONObject report = context.styleTextFlow(flowId,
                    optionalFloat(arguments, "fontSizeSp"),
                    optionalFloat(arguments, "lineHeight"),
                    optionalFloat(arguments, "widthDp"));
            if (report == null) {
                return Result.error("找不到文字流：" + flowId);
            }
            return Result.mutated(report, "已调整 " + flowId + " 的排版");
        }
    }

    /** Moves a flow. Page-relative anchors make "next page" a page index change. */
    private static final class MoveTextFlow implements NoteTool {
        @Override
        public String name() {
            return "move_text_flow";
        }

        @Override
        public String description() {
            return "把已有文字流移动到新位置。跨页移动就是换一个页码，"
                    + "不需要计算坐标。";
        }

        @Override
        public JSONObject parameterSchema() throws JSONException {
            JSONObject properties = new JSONObject();
            properties.put("flowId", new JSONObject().put("type", "string"));
            properties.put("placement", placementSchema());
            return new JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", new JSONArray().put("flowId").put("placement"));
        }

        @Override
        public Permission permission() {
            return Permission.MODIFY_EXISTING;
        }

        @Override
        public Result execute(JSONObject arguments, NoteToolContext context) {
            String flowId = arguments.optString("flowId", "").trim();
            if (flowId.isEmpty()) {
                return Result.error("缺少 flowId");
            }
            JSONObject report = context.moveTextFlow(flowId,
                    resolvePlacement(arguments, context));
            if (report == null) {
                return Result.error("找不到文字流：" + flowId);
            }
            return Result.mutated(report, "已移动 " + flowId);
        }
    }

    /**
     * Shared placement schema. Coordinates are absent by design — see
     * {@link PlacementResolver}.
     */
    private static JSONObject placementSchema() throws JSONException {
        JSONObject properties = new JSONObject();
        properties.put("relativeTo", new JSONObject()
                .put("type", "string")
                .put("description", "锚点：\"selection\" 表示用户圈选的区域，"
                        + "也可用 read_page_map 返回的 clusterId 或 flowId"));
        properties.put("position", new JSONObject()
                .put("type", "string")
                .put("enum", new JSONArray().put("below").put("above")
                        .put("right").put("left"))
                .put("description", "相对锚点的方位，默认 below"));
        properties.put("page", new JSONObject()
                .put("type", "integer")
                .put("description", "页码，从 1 开始；与 bands 或 slot 搭配使用"));
        properties.put("bands", new JSONObject()
                .put("type", "string")
                .put("description", "页内纵向条带，共 " + PageMap.BAND_COUNT
                        + " 条，band 1 在顶部。形如 \"3\" 或 \"3-5\""));
        properties.put("slot", new JSONObject()
                .put("type", "string")
                .put("description", "让引擎挑位置，如 \"free.largest\""));
        properties.put("widthDp", new JSONObject()
                .put("type", "number")
                .put("description", "行宽，省略则用默认宽度"));
        return new JSONObject()
                .put("type", "object")
                .put("description", "位置约束，不要给像素坐标。"
                        + "最常用的是 relativeTo=selection、position=below。")
                .put("properties", properties);
    }

    private static PlacementResolver.Placement resolvePlacement(JSONObject arguments,
                                                                NoteToolContext context) {
        // Resolution belongs to the context, which owns the real page geometry and
        // the anchor names; tools only pass the constraint through.
        return context.resolvePlacement(arguments.optJSONObject("placement"));
    }

    private static Float optionalFloat(JSONObject arguments, String key) {
        if (!arguments.has(key)) {
            return null;
        }
        double value = arguments.optDouble(key, Double.NaN);
        return Double.isNaN(value) ? null : (float) value;
    }

    /**
     * Line search over the digitized knowledge base.
     *
     * <p>Deliberately line-based rather than building a real index: the vault is
     * a handful of Markdown files, and a scan is fast enough while staying
     * trivially correct. Matches carry the note, page heading and the original
     * line so the model can judge relevance before paying for a full read.
     */
    private static final class SearchVault implements NoteTool {
        /** Cap on excerpt lines, so one broad query cannot flood the context. */
        private static final int MAX_EXCERPT_LINES = 30;
        private static final int MAX_LINE_SNIPPET = 160;

        private final VaultReader vault;

        SearchVault(VaultReader vault) {
            this.vault = vault;
        }

        @Override
        public String name() {
            return "search_vault";
        }

        @Override
        public String description() {
            return "在知识库中按关键词检索。知识库是用户手写笔记数字化成的 Markdown 格式笔记集合"
                    + "（公式为 LaTeX、流程图为 Mermaid，按页分节）。"
                    + "当问题涉及用户以往的笔记、需要跨笔记对照，或用户提到「我之前记过…」时先调用它；"
                    + "命中后再用 read_vault_note 读取完整上下文。"
                    + "返回每处匹配所在笔记、页码和原文行。";
        }

        @Override
        public JSONObject parameterSchema() throws JSONException {
            return new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject().put("query", new JSONObject()
                            .put("type", "string")
                            .put("description", "关键词或短语；英文忽略大小写。"
                                    + "一次一个词，可多次调用换词检索")))
                    .put("required", new JSONArray().put("query"));
        }

        @Override
        public Permission permission() {
            return Permission.READ_ONLY;
        }

        @Override
        public Result execute(JSONObject arguments, NoteToolContext context) {
            String query = arguments.optString("query", "").trim();
            if (query.isEmpty()) {
                return Result.error("query 不能为空");
            }
            List<VaultStore.VaultNote> notes = vault.list();
            String needle = query.toLowerCase(Locale.ROOT);
            JSONArray matchedNotes = new JSONArray();
            JSONArray excerpts = new JSONArray();
            int totalMatches = 0;
            boolean excerptCapped = false;
            for (VaultStore.VaultNote note : notes) {
                String content;
                try {
                    content = vault.read(note.fileName);
                } catch (Exception corrupted) {
                    // One damaged file must not hide the rest of the library.
                    continue;
                }
                int noteMatches = 0;
                int page = 0;
                for (String rawLine : content.split("\n")) {
                    String line = rawLine.trim();
                    if (line.startsWith("## 第") && line.endsWith("页")) {
                        page = parsePageHeading(line);
                    }
                    if (line.isEmpty() || !line.toLowerCase(Locale.ROOT).contains(needle)) {
                        continue;
                    }
                    noteMatches += 1;
                    totalMatches += 1;
                    if (excerpts.length() < MAX_EXCERPT_LINES) {
                        try {
                            excerpts.put(new JSONObject()
                                    .put("note", displayName(note))
                                    .put("file", note.fileName)
                                    .put("page", Math.max(0, page))
                                    .put("line", snippet(line, MAX_LINE_SNIPPET)));
                        } catch (JSONException ignored) {
                            // A lost excerpt is acceptable; totals stay correct.
                        }
                    } else {
                        excerptCapped = true;
                    }
                }
                if (noteMatches > 0) {
                    try {
                        matchedNotes.put(new JSONObject()
                                .put("title", displayName(note))
                                .put("file", note.fileName)
                                .put("matches", noteMatches));
                    } catch (JSONException ignored) {
                        // The per-excerpt list still shows the note.
                    }
                }
            }
            JSONObject payload = new JSONObject();
            try {
                payload.put("query", query)
                        .put("noteCount", notes.size())
                        .put("matchedNotes", matchedNotes)
                        .put("excerpts", excerpts)
                        .put("totalMatches", totalMatches)
                        .put("excerptsCapped", excerptCapped);
                if (totalMatches == 0 && !notes.isEmpty()) {
                    payload.put("hint", "没有命中。换更短的关键词再试；"
                            + "确认相关手写笔记是否已在书架执行过「转为格式笔记」。");
                }
            } catch (JSONException ignored) {
                // Summary still reports the outcome.
            }
            String summary = totalMatches == 0
                    ? "知识库中未找到「" + query + "」"
                    : "在 " + matchedNotes.length() + " 本格式笔记中找到 " + totalMatches + " 处匹配"
                            + (excerptCapped ? "（仅列出前 " + MAX_EXCERPT_LINES + " 处）" : "");
            return Result.ok(payload, summary);
        }
    }

    /** Reads one digitized note, whole or by page section. */
    private static final class ReadVaultNote implements NoteTool {
        /** Context guard: a whole library should never ride in one tool result. */
        private static final int MAX_READ_CHARS = 20000;

        private final VaultReader vault;

        ReadVaultNote(VaultReader vault) {
            this.vault = vault;
        }

        @Override
        public String name() {
            return "read_vault_note";
        }

        @Override
        public String description() {
            return "读取一本知识库格式笔记的 Markdown 原文（含元数据与全部页节）。"
                    + "先用 search_vault 找到目标再读，避免整本读入无关内容；"
                    + "只需要某一页时传 page。内容里公式是 LaTeX、流程图是 Mermaid 代码块。";
        }

        @Override
        public JSONObject parameterSchema() throws JSONException {
            JSONObject properties = new JSONObject();
            properties.put("title", new JSONObject()
                    .put("type", "string")
                    .put("description", "笔记标题或文件名（可省略 .md）；"
                            + "精确匹配优先，也接受唯一子串"));
            properties.put("page", new JSONObject()
                    .put("type", "integer")
                    .put("description", "只读取第 N 页的小节，从 1 开始；省略则读全文"));
            return new JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", new JSONArray().put("title"));
        }

        @Override
        public Permission permission() {
            return Permission.READ_ONLY;
        }

        @Override
        public Result execute(JSONObject arguments, NoteToolContext context) {
            String title = arguments.optString("title", "").trim();
            if (title.isEmpty()) {
                return Result.error("title 不能为空");
            }
            List<VaultStore.VaultNote> notes = vault.list();
            if (notes.isEmpty()) {
                JSONObject payload = new JSONObject();
                try {
                    payload.put("error", "vault_empty")
                            .put("hint", "知识库还没有内容。用户在书架对一本手写笔记"
                                    + "执行「转为格式笔记」后才会有可读的条目。");
                } catch (JSONException ignored) {
                    // Summary carries the reason.
                }
                return Result.rejected(payload, "知识库为空");
            }

            // Exact on filename or title first, then a unique substring; ambiguity
            // is a refusal that lists the candidates instead of a guess.
            VaultStore.VaultNote match = null;
            List<VaultStore.VaultNote> candidates = new java.util.ArrayList<>();
            String needle = title.toLowerCase(Locale.ROOT);
            for (VaultStore.VaultNote note : notes) {
                String file = note.fileName;
                if (file.equals(title) || file.equals(title + ".md")
                        || displayName(note).equals(title)) {
                    match = note;
                    break;
                }
            }
            if (match == null) {
                for (VaultStore.VaultNote note : notes) {
                    String name = displayName(note);
                    if (name.toLowerCase(Locale.ROOT).contains(needle)
                            || note.fileName.toLowerCase(Locale.ROOT).contains(needle)) {
                        candidates.add(note);
                    }
                }
                if (candidates.size() == 1) {
                    match = candidates.get(0);
                }
            }
            if (match == null) {
                JSONObject payload = new JSONObject();
                JSONArray names = new JSONArray();
                for (VaultStore.VaultNote note : (candidates.isEmpty() ? notes : candidates)) {
                    names.put(displayName(note));
                }
                try {
                    payload.put("error", candidates.isEmpty() ? "not_found" : "ambiguous")
                            .put("availableTitles", names);
                } catch (JSONException ignored) {
                    // Summary carries the reason.
                }
                String kind = candidates.isEmpty() ? "找不到格式笔记「" + title + "」"
                        : "「" + title + "」匹配到多本笔记";
                return Result.rejected(payload, kind);
            }

            String content;
            try {
                content = vault.read(match.fileName);
            } catch (Exception unreadable) {
                return Result.error("读取「" + displayName(match) + "」失败");
            }

            Integer page = arguments.has("page")
                    ? (int) arguments.optDouble("page", 0) : null;
            String body = content;
            if (page != null) {
                if (page < 1 || (match.pageCount > 0 && page > match.pageCount)) {
                    JSONObject payload = new JSONObject();
                    try {
                        payload.put("error", "page_out_of_range")
                                .put("pages", match.pageCount);
                    } catch (JSONException ignored) {
                        // Summary carries the reason.
                    }
                    return Result.rejected(payload, "「" + displayName(match)
                            + "」共 " + match.pageCount + " 页，没有第 " + page + " 页");
                }
                String section = extractPageSection(content, page);
                if (section == null) {
                    return Result.error("找不到第 " + page + " 页的小节");
                }
                body = section;
            }

            boolean truncated = false;
            if (body.length() > MAX_READ_CHARS) {
                body = body.substring(0, MAX_READ_CHARS) + "\n…（已截断）";
                truncated = true;
            }

            JSONObject payload = new JSONObject();
            try {
                payload.put("file", match.fileName)
                        .put("title", displayName(match))
                        .put("pages", match.pageCount)
                        .put("digitized", VaultStore.frontValue(content, "digitized"))
                        .put("content", body)
                        .put("truncated", truncated);
                if (truncated) {
                    payload.put("hint", "内容过长已截断。用 page 参数按页读取其余部分。");
                }
            } catch (JSONException ignored) {
                // Summary still reports the outcome.
            }
            String scope = page == null
                    ? "全文" + (truncated ? "（已截断）" : "") : "第 " + page + " 页";
            return Result.ok(payload, "已读取《" + displayName(match) + "》" + scope);
        }
    }

    private static String displayName(VaultStore.VaultNote note) {
        return note.title == null || note.title.isEmpty()
                ? note.fileName.replaceAll("\\.md$", "") : note.title;
    }

    private static String snippet(String line, int limit) {
        return line.length() > limit ? line.substring(0, limit) + "…" : line;
    }

    /** "## 第 12 页" → 12; anything unparseable maps to 0. */
    private static int parsePageHeading(String heading) {
        String digits = heading.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException oversized) {
            return 0;
        }
    }

    /**
     * Body of one "## 第 N 页" section, heading excluded.
     *
     * @return {@code null} when the section does not exist
     */
    private static String extractPageSection(String content, int page) {
        StringBuilder selected = new StringBuilder();
        boolean collecting = false;
        for (String rawLine : content.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("## 第") && line.endsWith("页")) {
                if (collecting) {
                    break;
                }
                collecting = parsePageHeading(line) == page;
                continue;
            }
            if (collecting) {
                selected.append(rawLine).append('\n');
            }
        }
        return collecting ? selected.toString().trim() : null;
    }
}
