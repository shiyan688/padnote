package com.padnote.android;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Real-endpoint driver for the AI routes, run from a JVM instead of a device.
 *
 * <p>What it validates that offline tests cannot: the actual request shape our
 * client sends, the response/tool-call parsing against a live OpenAI-compatible
 * provider, the multi-round tool loop, and (since 0.16.0) the knowledge-base
 * tools reaching a real model. What it deliberately does not cover: canvas,
 * WebView rendering and anything only a tablet can show.
 *
 * <p>Secrets arrive via the E2E_API_KEY environment variable; the wrapper
 * scripts read them from the user's own provider store. Nothing here prints or
 * persists the key.
 *
 * <p>Usage: {@code E2E_API_KEY=... java com.padnote.android.E2eDriver [direct|split|vault|all]}
 */
public final class E2eDriver {

    private static final int MAX_ROUNDS = 4;

    private AiConfigStore.Config config;
    private NoteToolRegistry registry;
    private JSONArray toolDescriptions;
    private JSONObject pageMap;
    private byte[] samplePng;
    private final List<String> writtenToPage = new ArrayList<>();
    private int failures = 0;
    private int diagramWrites = 0;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "all";
        String endpoint = envOr("E2E_ENDPOINT", "https://openrouter.ai/api/v1");
        String model = envOr("E2E_MODEL", "stealth/ox-alpha");
        String key = System.getenv("E2E_API_KEY");
        if (key == null || key.trim().isEmpty()) {
            System.err.println("E2E_API_KEY is not set; refusing to run.");
            System.exit(2);
        }

        E2eDriver driver = new E2eDriver();
        driver.config = new AiConfigStore.Config(endpoint, model, key.trim());
        driver.registry = NoteTools.createDefault(TempVault.create());
        driver.toolDescriptions = driver.registry.describe();
        driver.pageMap = samplePageMap();
        driver.samplePng = driver.renderSampleImage();

        boolean ranDirect = false;
        boolean ranSplit = false;
        boolean ranVault = false;
        if (mode.equals("direct") || mode.equals("all")) {
            driver.scenarioDirect();
            ranDirect = true;
        }
        if (mode.equals("split") || mode.equals("all")) {
            driver.scenarioSplit();
            ranSplit = true;
        }
        if (mode.equals("vault") || mode.equals("all")) {
            driver.scenarioVault();
            ranVault = true;
        }
        if (mode.equals("dump-image")) {
            Files.write(Path.of(System.getProperty("java.io.tmpdir"),
                    "padnote-e2e-sample.png"), driver.samplePng);
            System.out.println("sample image written to " + Path.of(
                    System.getProperty("java.io.tmpdir"), "padnote-e2e-sample.png"));
            return;
        }
        if (mode.equals("diagram")) {
            List<OpenAiCompatibleClient.Message> messages = new ArrayList<>();
            messages.add(new OpenAiCompatibleClient.Message("user",
                    "请把科学探究的过程（观察、提出假设、实验、分析、修正假设）画成中文流程示意图。"
                            + "调用 draw_diagram 插入选区下方，先检查空白，不要仅回复代码。"));
            driver.runLoop(messages, false);
            driver.check("diagram inserted through tool", driver.diagramWrites > 0);
        } else if (!ranDirect && !ranSplit && !ranVault) {
            System.err.println("Unknown mode: " + mode);
            System.exit(2);
        }
        System.out.println();
        System.out.println("=== SUMMARY ===");
        System.out.println("write_text calls observed: "
                + driver.writtenToPage.size() + " -> " + driver.writtenToPage);
        System.out.println(driver.failures == 0 ? "ALL SCENARIOS PASSED"
                : (driver.failures + " CHECK(S) FAILED"));
        System.exit(driver.failures == 0 ? 0 : 1);
    }

    /** Direct multimodal route: one model sees the image and answers with tools. */
    private void scenarioDirect() throws Exception {
        section("SCENARIO direct — 多模态直连：看图讲解并写入");
        List<OpenAiCompatibleClient.Message> messages = new ArrayList<>();
        messages.add(new OpenAiCompatibleClient.Message("user",
                "图片是我圈选的手写内容。请先在回复里用一句话说明你认出了什么，"
                        + "然后把这道题的完整解法经工具写进笔记。"));
        long started = System.currentTimeMillis();
        runLoop(messages, true);
        reportTiming(started);
    }

    /**
     * Split route: transcription leg turns pixels into text, answer leg reasons
     * over the transcript with no image attached.
     */
    private void scenarioSplit() throws Exception {
        section("SCENARIO split — 两段式：转写腿（带图）→ 回答腿（纯文本）");
        String transcript = OpenAiCompatibleClient.transcribe(config, samplePng);
        System.out.println("[转写结果] " + transcript);
        check("transcription is non-empty", !transcript.isEmpty());

        // Mirror MainActivity.foldTranscriptIntoConversation: the wire copy of
        // the pending user turn gains the transcript; the bubble text does not.
        List<OpenAiCompatibleClient.Message> messages = new ArrayList<>();
        messages.add(new OpenAiCompatibleClient.Message("user",
                "请把这道题的解法整理后经工具写入笔记。\n\n【圈选手写内容的文字转写】\n" + transcript));
        long started = System.currentTimeMillis();
        runLoop(messages, false);
        reportTiming(started);
    }

    /** Knowledge-base readers: search → read → write, all through one loop. */
    private void scenarioVault() throws Exception {
        section("SCENARIO vault — 知识库读取：检索/读取数字化笔记后作答");
        List<OpenAiCompatibleClient.Message> messages = new ArrayList<>();
        messages.add(new OpenAiCompatibleClient.Message("user",
                "我之前数字化过的格式笔记里，有没有讲过「极限」相关的内容？"
                        + "如果有，请在回复里告诉我出处（哪本笔记第几页），"
                        + "并在选区下方用 write_text 写一行简短摘录。"));
        long started = System.currentTimeMillis();
        runLoop(messages, true);
        reportTiming(started);
    }

    /**
     * The same multi-round loop MainActivity.runAiToolCalls runs: execute every
     * requested tool call, feed results back as tool messages, repeat until the
     * model answers in prose or the round ceiling is hit.
     */
    private void runLoop(List<OpenAiCompatibleClient.Message> messages,
                         boolean withImage) throws Exception {
        byte[] png = withImage ? samplePng : null;
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            System.out.printf("[round %d] sending (%d messages)%n", round, messages.size());
            OpenAiCompatibleClient.Completion completion =
                    OpenAiCompatibleClient.completeWithTools(config, png, messages,
                            toolDescriptions, pageMap);
            if (!completion.displayContent.isEmpty()) {
                System.out.println("[assistant] " + completion.displayContent);
            }
            if (completion.toolCalls.isEmpty()) {
                check("model finished with prose on round " + round,
                        !completion.displayContent.isEmpty());
                return;
            }
            messages.add(new OpenAiCompatibleClient.Message("assistant",
                    completion.content, completion.toolCalls, null));
            for (OpenAiCompatibleClient.ToolCall call : completion.toolCalls) {
                NoteTool.Result result = registry.invoke(call.name, call.arguments,
                        new FakeToolContext(), NoteTool.Permission.CREATE_IN_FREE_SPACE);
                System.out.printf("[tool] %s(%s) -> %s | %s%n",
                        call.name, call.arguments, result.ok ? "ok" : "refused",
                        result.summary);
                if ("write_text".equals(call.name) && result.ok) {
                    writtenToPage.add(call.arguments.optString("content", ""));
                }
                if ("draw_diagram".equals(call.name) && result.ok) diagramWrites++;
                messages.add(OpenAiCompatibleClient.Message.toolResult(call.id,
                        result.payload.toString()));
            }
        }
        check("loop terminated within " + MAX_ROUNDS + " rounds", false);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * A stand-in for PageMap output: enough structure for the model to reason
     * about bands without dragging the canvas in. Shape mirrors what
     * NoteCanvasView's context emits.
     */
    private static JSONObject samplePageMap() throws Exception {
        return new JSONObject()
                .put("pageCount", 1)
                .put("bandsPerPage", PageMap.BAND_COUNT)
                .put("pages", new JSONArray().put(new JSONObject()
                        .put("pageIndex", 1)
                        .put("bands", new JSONArray()
                                .put(band(1, "ink", 0, 0))
                                .put(band(2, "empty", 6, 90))
                                .put(band(3, "ink-cluster", 0, 0))
                                .put(band(4, "empty", 8, 130))
                                .put(band(5, "empty", 8, 130))
                                .put(band(6, "empty", 8, 130))
                                .put(band(7, "empty", 8, 130))
                                .put(band(8, "empty", 8, 130)))
                        .put("clusters", new JSONArray().put(new JSONObject()
                                .put("clusterId", "cluster-1")
                                .put("band", "3")
                                .put("strokeCount", 14)))))
                ;
    }

    private static JSONObject band(int index, String occupancy, int freeLines,
                                   int freeChars) throws Exception {
        return new JSONObject()
                .put("band", index)
                .put("occupancy", occupancy)
                .put("freeLines", freeLines)
                .put("freeChars", freeChars);
    }

    /**
     * Renders a handwriting-like page: Chinese heading, the limit definition,
     * and an integral exercise. Uses the system CJK fallback font when present;
     * tofu boxes would still exercise the plumbing, just less convincingly.
     */
    private byte[] renderSampleImage() throws Exception {
        int width = 900;
        int height = 560;
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        Font cjk = findCjkFont(34);
        Font math = new Font(Font.SERIF, Font.ITALIC, 40);
        g.setColor(new Color(18, 42, 96));

        g.setFont(cjk);
        g.drawString("例题与定义（手写）", 40, 70);
        g.drawLine(40, 92, 420, 92);

        g.setFont(math);
        g.drawString("lim f(n) = 0 ,  n \u2192 \u221E", 60, 190);
        g.drawString("\u222B\u2080\u00B9 x\u00B2 dx = 1/3 ?", 60, 290);
        g.setFont(cjk);
        g.drawString("求：该积分的值，并说明含义。", 60, 400);

        g.setColor(new Color(200, 60, 40));
        g.setStroke(new BasicStroke(3f));
        g.drawRoundRect(30, 110, 780, 330, 24, 24); // the lasso circle
        g.dispose();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static Font findCjkFont(int size) {
        for (String candidate : new String[]{
                "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf"}) {
            File file = new File(candidate);
            if (file.isFile()) {
                try (FileInputStream input = new FileInputStream(file)) {
                    return Font.createFont(Font.TRUETYPE_FONT, input).deriveFont(
                            Font.PLAIN, size);
                } catch (Exception ignored) {
                    // fall through to logical fonts
                }
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, size);
    }

    /** In-memory vault standing in for VaultStore-backed storage. */
    private static final class TempVault implements NoteTools.VaultReader {
        private final List<VaultStore.VaultNote> notes = new ArrayList<>();
        private final java.util.Map<String, String> files = new java.util.HashMap<>();

        static TempVault create() throws Exception {
            TempVault vault = new TempVault();
            Path dir = Files.createTempDirectory("padnote-e2e-vault");
            vault.add("极限与连续.md", "vault-note-1", "极限与连续", 2,
                    "---\ntitle: 极限与连续\nnote-id: vault-note-1\npages: 2\n"
                            + "digitized: 2026-08-22 10:00\ndigitized-epoch: 1724286000000\n"
                            + "source-modified: 1724280000000\n---\n\n# 极限与连续\n\n"
                            + "## 第 1 页\n\n数列极限的 ε–N 定义：对任意 ε > 0，存在 N，"
                            + "当 n > N 时 |a_n − a| < ε。\n\n"
                            + "## 第 2 页\n\n- 收敛数列必有界\n"
                            + "- 夹逼准则：若 b_n ≤ a_n ≤ c_n 且 b_n、c_n 同趋于 L，则 a_n → L\n");
            vault.add("积分技巧.md", "vault-note-2", "积分技巧", 1,
                    "---\ntitle: 积分技巧\nnote-id: vault-note-2\npages: 1\n"
                            + "digitized: 2026-08-22 11:00\ndigitized-epoch: 1724289600000\n"
                            + "source-modified: 1724283000000\n---\n\n# 积分技巧\n\n"
                            + "## 第 1 页\n\n幂函数积分：∫ x² dx = x³/3 + C；"
                            + "定积分几何意义为曲线下面积。\n");
            System.out.println("[fixture] temp vault at " + dir);
            return vault;
        }

        void add(String fileName, String noteId, String title, int pages,
                 String content) {
            notes.add(new VaultStore.VaultNote(fileName, noteId, title, pages,
                    System.currentTimeMillis(), System.currentTimeMillis()));
            files.put(fileName, content);
        }

        @Override
        public List<VaultStore.VaultNote> list() {
            return notes;
        }

        @Override
        public String read(String fileName) throws Exception {
            String content = files.get(fileName);
            if (content == null) {
                throw new IllegalArgumentException("no such vault file: " + fileName);
            }
            return content;
        }
    }

    /** Same contract as the unit-test double: no android.graphics needed. */
    private static final class FakeToolContext implements NoteToolContext {
        @Override
        public JSONObject readPageMap(int focusPageIndex, boolean fullDetail) {
            return samplePageMapQuietly();
        }

        @Override
        public PlacementResolver.Placement resolvePlacement(JSONObject placement) {
            return new PlacementResolver.Placement(0, 16f, 120f, 360f, "选区下方");
        }

        @Override
        public int selectionPageIndex() {
            return 0;
        }

        @Override
        public android.graphics.RectF selectionBounds() {
            return null;
        }

        @Override
        public int pageCount() {
            return 1;
        }

        @Override
        public JSONObject createTextFlow(String content, NoteTextBox.Format format,
                                         PlacementResolver.Placement placement) {
            try {
                return new JSONObject()
                        .put("flowId", "flow-e2e-" + System.nanoTime())
                        .put("occupiedPages", new JSONArray().put(1))
                        .put("fragmentCount", 1)
                        .put("addedPage", false)
                        .put("shiftedFlows", new JSONArray())
                        .put("interpretation", placement.interpretation);
            } catch (Exception failure) {
                return new JSONObject();
            }
        }

        @Override
        public JSONObject styleTextFlow(String flowId, Float fontSizeSp,
                                        Float lineHeight, Float width) {
            return new JSONObject();
        }

        @Override
        public JSONObject moveTextFlow(String flowId,
                                       PlacementResolver.Placement placement) {
            return new JSONObject();
        }

        @Override
        public float measureContentHeight(String content, NoteTextBox.Format format,
                                          float widthDp, float fontSizeSp,
                                          float lineHeight) {
            return 120f;
        }

        @Override
        public float pageWidthDp() {
            return 360f;
        }

        @Override
        public float pageHeightDp() {
            return 640f;
        }

        private static JSONObject samplePageMapQuietly() {
            try {
                return samplePageMap();
            } catch (Exception never) {
                return new JSONObject();
            }
        }
    }

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------

    private static void section(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }

    private static void reportTiming(long started) {
        System.out.printf("[scenario done in %.1fs]%n",
                (System.currentTimeMillis() - started) / 1000.0);
    }

    private void check(String what, boolean condition) {
        if (condition) {
            System.out.println("[check ok] " + what);
        } else {
            failures++;
            System.out.println("[CHECK FAILED] " + what);
        }
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
