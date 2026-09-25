package com.padnote.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground digitization with durable page checkpoints; no background-service promise. */
final class DigitizationController implements AutoCloseable {
    static final int EXPORT_DRAFT_REQUEST = 7110;
    private static final int MAX_PNG_BYTES = 12 * 1024 * 1024;
    private static final String PROMPT = "请将这页笔记准确转写为 Markdown。数学公式用 LaTeX，"
            + "流程图可用 Mermaid；保留标题、列表和页内顺序。模糊内容标为【无法辨认】，"
            + "复杂插图用【图形】说明，不猜测、不回答题目、不加开场白。"
            + "若页面确实没有内容，只返回【空白页】。";

    interface Transcriber {
        String transcribe(AiConfigStore.Config config, byte[] png, String prompt,
                          OpenAiCompatibleClient.Cancellation cancellation) throws Exception;
    }

    private final Activity activity;
    private final VaultStore vault;
    private final DigitizationStore store;
    private final Transcriber transcriber;
    private Runnable configureAI;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<AlertDialog> dialogs = new ArrayList<>();
    private int generation;
    private boolean closed;
    private Capture preparing;
    private Run active;
    private String pendingExport;

    DigitizationController(Activity activity, VaultStore vault, Runnable configureAI) {
        this(activity, vault, new DigitizationStore(activity),
                OpenAiCompatibleClient::transcribeWithPrompt);
        this.configureAI = configureAI;
    }

    DigitizationController(Activity activity, VaultStore vault, DigitizationStore store,
                           Transcriber transcriber) {
        this.activity = activity;
        this.vault = vault;
        this.store = store;
        this.transcriber = transcriber;
    }

    boolean isBusy() { return active != null || preparing != null; }

    /** Called on the UI thread after the inline editor has committed. */
    void open(NoteCanvasView canvas, String noteID, String title, String profileID,
              AiConfigStore.Config config, long sourceUpdatedAt) {
        if (closed || preparing != null || active != null) return;
        final int serial = ++generation;
        final Capture capture;
        try {
            JSONObject document = canvas.toJsonDocument(noteID, title);
            List<TextFlow> flows = new ArrayList<>();
            for (TextFlow flow : canvas.getTextFlows()) flows.add(flow.copy());
            NoteCanvasView.PdfExportSnapshot snapshot = null;
            if (config != null) {
                try { snapshot = canvas.createPdfExportSnapshot(); }
                catch (Exception unavailableSource) { /* Existing drafts remain readable. */ }
            }
            capture = new Capture(document, snapshot, flows,
                    document.optInt("pdfPageCount", 0) > 0 ? NoteStore.pdfFile(activity, noteID) : null,
                    profileID, config, sourceUpdatedAt);
            preparing = capture;
        } catch (Exception error) {
            message("无法准备数字化：" + safe(error));
            return;
        }
        AlertDialog progress = dialog(new AlertDialog.Builder(activity)
                .setTitle("整理到知识库").setMessage("正在核对原文和已保存的进度…")
                .setNegativeButton("取消", (d, which) -> pause()).create());
        progress.setCancelable(false);
        worker.execute(() -> {
            try {
                List<DigitizationStore.Snapshot> history = store.listForNote(noteID);
                List<DigitizationStore.CorruptEntry> corrupt = store.listCorrupt();
                DigitizationStore.Source preparedSource = null;
                if (config != null && capture.snapshot != null) {
                    try {
                        String pdfHash = capture.pdf == null ? "" : DigitizationStore.sha256File(
                                capture.pdf, (int) PdfNoteIO.MAX_PDF_BYTES);
                        preparedSource = DigitizationStore.Source.from(capture.document, pdfHash,
                                capture.snapshot.getPageCount(), profileID, config.endpoint, config.model);
                    } catch (Exception invalidSource) {
                        // An unavailable PDF/configuration must not hide already saved transcription.
                    }
                }
                final DigitizationStore.Source source = preparedSource;
                main.post(() -> {
                    progress.dismiss();
                    if (!current(serial) || preparing != capture) { capture.close(); return; }
                    showPlan(capture, source, history, corrupt, serial);
                });
            } catch (Exception error) {
                main.post(() -> {
                    progress.dismiss();
                    if (preparing == capture) preparing = null;
                    capture.close();
                    if (current(serial)) message("无法读取整理进度：" + safe(error));
                });
            }
        });
    }

    private void showPlan(Capture capture, DigitizationStore.Source source,
                          List<DigitizationStore.Snapshot> history,
                          List<DigitizationStore.CorruptEntry> corrupt, int serial) {
        LinearLayout body = body();
        addText(body, "逐页整理手写、图片和 PDF 原文。已有文字按原文保留。每页完成即保存；"
                + "取消或退出后，可以回来继续未完成的页。");
        if (source != null) {
            addText(body, "上传范围：整本笔记，共 " + capture.snapshot.getPageCount() + " 页。\n模型："
                    + capture.config.model + "\n目标：" + safeTarget(capture.config.endpoint));
        } else {
            addText(body, capture.config == null
                    ? "先在 AI 设置中配置模型，才能发送新页面。已有草稿仍可查看和导出。"
                    : "无法验证当前原文或模型配置，暂不能发送页面。已有草稿仍可查看和导出，请检查 PDF 原文与模型地址。");
            if (configureAI != null) addButton(body, "AI 设置", () -> { pause(); configureAI.run(); });
        }
        DigitizationStore.Snapshot matching = null;
        for (DigitizationStore.Snapshot draft : history) {
            if (source != null && draft.matches(source) && matching == null) matching = draft;
        }
        final DigitizationStore.Snapshot resume = matching;
        AlertDialog plan = new AlertDialog.Builder(activity).setTitle("整理「"
                + capture.document.optString("title", "笔记") + "」")
                .setView(scroll(body)).setNegativeButton("关闭", null).create();
        if (resume != null) {
            addText(body, "这份原文已有进度：" + resume.completedCount() + " / "
                    + resume.totalPages + " 页。" + ("COMPLETED".equals(resume.state.name())
                    ? "已保存到知识库。" : "未完成草稿不会覆盖知识库。"));
            if (resume.inFlightPage >= 0) addText(body, "第 " + (resume.inFlightPage + 1)
                    + " 页上次已尝试发送，但没有保存到返回结果。服务商可能已经处理并计费；"
                    + "确认继续会重新发送这一页，可能再次计费。");
            if (!"COMPLETED".equals(resume.state.name())) {
                addButton(body, resume.nextPendingPage() < 0 ? "保存已完成的转写" : "确认范围并继续未完成页",
                        () -> start(capture, source, resume, serial, plan));
            }
        } else if (!history.isEmpty()) {
            addText(body, "原文、PDF、页数或模型配置与旧批次不同。旧草稿会保留，新批次不会混用旧结果。");
        }
        if (source != null) {
            addButton(body, history.isEmpty() ? "确认范围并开始" : "新建整理批次", () -> {
                if (history.isEmpty()) start(capture, source, null, serial, plan);
                else dialog(new AlertDialog.Builder(activity).setTitle("重新整理整本笔记？")
                        .setMessage("将重新发送全部页面，可能再次产生模型费用。已有草稿会保留。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("新建并开始", (d, which) -> start(capture, source, null, serial, plan))
                        .create());
            });
        }
        if (!history.isEmpty()) addButton(body, "查看已保存的批次（" + history.size() + "）", () -> showHistory(history));
        if (!corrupt.isEmpty()) {
            addText(body, "发现 " + corrupt.size() + " 份无法读取的检查点，原文件已保留；未作为可继续进度使用。");
            addButton(body, "查看损坏检查点", () -> {
                StringBuilder description = new StringBuilder("以下文件未被删除，也没有用于续跑：\n\n");
                for (DigitizationStore.CorruptEntry item : corrupt) description.append(item.fileName).append('\n');
                dialog(new AlertDialog.Builder(activity).setTitle("无法读取的整理稿")
                        .setMessage(description.toString()).setPositiveButton("关闭", null).create());
            });
        }
        dialog(plan, () -> {
            if (preparing == capture) { preparing = null; capture.close(); }
        });
    }

    private void start(Capture capture, DigitizationStore.Source source,
                       DigitizationStore.Snapshot resume, int serial, AlertDialog plan) {
        if (!current(serial) || preparing != capture || active != null) return;
        preparing = null;
        Run run = new Run(capture, serial);
        active = run;
        plan.dismiss();
        run.progress = dialog(new AlertDialog.Builder(activity).setTitle("正在整理到知识库")
                .setMessage("准备中…").setNegativeButton("取消整理", null).setCancelable(false).create());
        run.progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> cancel(run));
        worker.execute(() -> execute(run, source, resume));
    }

    private void execute(Run run, DigitizationStore.Source source, DigitizationStore.Snapshot resume) {
        String result;
        boolean published = false;
        try {
            synchronized (run) {
                run.cancellation.throwIfCancelled();
                run.draft = resume == null ? store.create(source,
                        run.capture.document.optString("title", "笔记"), run.capture.sourceUpdatedAt) : store.load(resume.runId);
                if (!run.draft.matches(source)) throw new IllegalStateException("原文或接收者已改变，请新建整理批次");
                try { run.lease = store.acquireLease(run.draft.runId); }
                catch (Exception unavailableLease) {
                    run.localFailure = "这份批次仍在另一项操作中，请等它结束后再继续";
                    throw unavailableLease;
                }
                if (run.lease == null) {
                    run.localFailure = "这份批次仍在另一项操作中，请等它结束后再继续";
                    throw new IllegalStateException("Digitization checkpoint lease is busy");
                }
                run.draft = store.load(run.draft.runId);
                if (resume != null && run.draft.revision != resume.revision) {
                    run.localFailure = "这份批次的进度已经更新，请重新打开，核对已完成页和未确认请求";
                    run.skipFailureCommit = true;
                    throw new IllegalStateException("Checkpoint changed after confirmation");
                }
                if (!"COMPLETED".equals(run.draft.state.name())) run.draft = store.startAttempt(run.draft);
            }
            while (run.draft.nextPendingPage() >= 0) {
                run.cancellation.throwIfCancelled();
                int page = run.draft.nextPendingPage();
                update(run, "正在整理第 " + (page + 1) + " / " + run.draft.totalPages
                        + " 页 · 已保存 " + run.draft.completedCount() + " 页");
                Bitmap bitmap = run.capture.snapshot.renderBasePage(page, 1600);
                byte[] png;
                try {
                    BoundedPng output = new BoundedPng(MAX_PNG_BYTES);
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw new IllegalStateException("页面图像编码失败");
                    }
                    png = output.toByteArray();
                } finally { bitmap.recycle(); }
                synchronized (run) {
                    run.cancellation.throwIfCancelled();
                    run.draft = store.markPageStarted(run.draft, page);
                }
                String transcript = transcriber.transcribe(run.capture.config, png, PROMPT, run.cancellation);
                if (transcript == null || transcript.trim().isEmpty()) throw new IllegalStateException("模型返回空结果，请重试此页");
                String pageText = mergePageContent(transcript, run.capture.flows, page);
                synchronized (run) {
                    run.cancellation.throwIfCancelled();
                    try { run.draft = store.savePage(run.draft, page, pageText); }
                    catch (IllegalArgumentException limit) {
                        // Only local validation text is displayed; provider error bodies stay excluded.
                        run.localFailure = limit.getMessage();
                        run.limitFailure = true;
                        throw limit;
                    }
                }
            }
            long sourceTime = run.draft.sourceUpdatedAt;
            try {
                JSONObject persisted = NoteStore.load(activity, run.draft.noteId);
                if (DigitizationStore.canonicalSourceFingerprint(persisted)
                        .equals(run.draft.sourceFingerprint)) sourceTime = persisted.optLong("updatedAt", sourceTime);
            } catch (Exception unavailableOriginal) {
                // The completed transcription remains publishable with its captured provenance.
            }
            synchronized (run) {
                run.cancellation.throwIfCancelled();
                if (!"COMPLETED".equals(run.draft.state.name())) {
                    update(run, "全部页面已保存，正在写入知识库…");
                    String name = vault.write(run.draft.noteId, run.draft.title, run.draft.totalPages,
                            sourceTime, run.draft.pages);
                    // A crash after vault.write only repeats a local atomic write, never a model request.
                    run.draft = store.markPublished(run.draft, name);
                }
                published = true;
            }
            result = "已保存到知识库。";
        } catch (Exception error) {
            boolean cancelled = run.cancellation.isCancelled();
            String persistError = "";
            if (run.draft != null && run.lease != null && !run.skipFailureCommit) {
                try { run.draft = cancelled ? store.cancel(run.draft) : store.fail(run.draft, "本次整理未完成"); }
                catch (Exception saveError) { persistError = " 状态保存失败；下次会重新核对已落盘页面。"; }
            }
            result = (cancelled ? "已取消本地请求。已保存的页面可继续，服务端可能仍产生费用。"
                    : "整理未完成：" + (run.localFailure == null ? safe(error) : run.localFailure
                    + (run.limitFailure ? "，可先导出部分结果并拆分原笔记" : ""))
                    + "。已保存的页会保留。") + persistError;
        } finally {
            if (run.lease != null) try { run.lease.close(); } catch (Exception ignored) { }
            run.capture.close();
        }
        final String notice = result;
        final boolean complete = published;
        main.post(() -> {
            if (active == run) active = null;
            if (run.progress != null) run.progress.dismiss();
            if (!current(run.serial)) return;
            if (complete) message(notice);
            else {
                AlertDialog.Builder alert = new AlertDialog.Builder(activity).setTitle("整理未完成")
                        .setMessage(notice).setPositiveButton("关闭", null);
                if (run.draft != null && run.draft.completedCount() > 0) alert.setNeutralButton("查看部分结果",
                        (d, which) -> preview(run.draft));
                dialog(alert.create());
            }
        });
    }

    private void cancel(Run run) {
        synchronized (run) {
            if (run.draft != null && "COMPLETED".equals(run.draft.state.name())) return;
            run.cancellation.cancel();
        }
        if (run.progress != null && run.progress.isShowing()) {
            run.progress.setMessage("正在取消本地请求，保留已保存的页面…");
            run.progress.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        }
    }

    /** Losing the foreground pauses work; checkpoints remain in app-private storage. */
    void pause() {
        ++generation;
        if (preparing != null) { preparing.close(); preparing = null; }
        if (active != null) cancel(active);
        for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        dialogs.clear();
    }

    @Override public void close() { pause(); closed = true; worker.shutdown(); }

    private void showHistory(List<DigitizationStore.Snapshot> history) {
        String[] labels = new String[history.size()];
        for (int index = 0; index < labels.length; index++) {
            DigitizationStore.Snapshot draft = history.get(index);
            labels[index] = draft.completedCount() + "/" + draft.totalPages + " 页 · "
                    + ("COMPLETED".equals(draft.state.name()) ? "已存知识库" : "未完成")
                    + " · " + android.text.format.DateFormat.format("MM-dd HH:mm", draft.createdAt);
        }
        dialog(new AlertDialog.Builder(activity).setTitle("已保存的整理批次")
                .setItems(labels, (d, which) -> preview(history.get(which)))
                .setNegativeButton("关闭", null).create());
    }

    private void preview(DigitizationStore.Snapshot draft) {
        String markdown = draft.partialMarkdown();
        LinearLayout content = body();
        addText(content, "这是原文快照的转写稿。未完成页已标出；未保存到知识库的草稿不会参与知识库检索。");
        TextView text = addText(content, markdown.length() > 100_000
                ? markdown.substring(0, 100_000) + "\n\n预览已截短，导出可取得完整草稿。" : markdown);
        text.setTextIsSelectable(true);
        dialog(new AlertDialog.Builder(activity).setTitle("整理稿 · " + draft.completedCount() + "/" + draft.totalPages + " 页")
                .setView(scroll(content)).setPositiveButton("关闭", null)
                .setNeutralButton("导出 Markdown", (d, which) -> {
                    pendingExport = markdown;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("text/markdown").putExtra(Intent.EXTRA_TITLE,
                                    VaultStore.sanitizeTitle(draft.title) + "-整理稿.md");
                    activity.startActivityForResult(intent, EXPORT_DRAFT_REQUEST);
                }).create());
    }

    boolean activityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != EXPORT_DRAFT_REQUEST) return false;
        String markdown = pendingExport;
        pendingExport = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || markdown == null) return true;
        android.net.Uri uri = data.getData();
        worker.execute(() -> {
            try (OutputStream output = activity.getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("无法打开所选位置");
                output.write(markdown.getBytes(StandardCharsets.UTF_8));
                main.post(() -> { if (!closed) message("整理稿已导出"); });
            } catch (Exception error) { main.post(() -> { if (!closed) message("导出失败：" + safe(error)); }); }
        });
        return true;
    }

    private void update(Run run, String message) {
        main.post(() -> { if (current(run.serial) && active == run && !run.cancellation.isCancelled()) run.progress.setMessage(message); });
    }
    private boolean current(int serial) { return !closed && generation == serial && !activity.isFinishing() && !activity.isDestroyed(); }
    private void message(String value) { Toast.makeText(activity, value, Toast.LENGTH_LONG).show(); }
    private static String safe(Exception error) {
        // Provider bodies may contain private text. The network layer already categorizes its errors.
        if (error instanceof java.util.concurrent.CancellationException) return "请求已取消";
        if (error instanceof java.io.IOException) return "网络或文件读写失败，请检查连接与剩余空间后重试";
        return "请求或保存未完成，请检查模型配置与原文后重试";
    }
    private static String safeTarget(String endpoint) {
        try { java.net.URI uri = new java.net.URI(endpoint); return uri.getHost() == null ? "已配置的模型服务" : uri.getHost(); }
        catch (Exception ignored) { return "已配置的模型服务"; }
    }
    private AlertDialog dialog(AlertDialog dialog) { return dialog(dialog, () -> { }); }
    private AlertDialog dialog(AlertDialog dialog, Runnable dismissed) {
        dialogs.add(dialog);
        dialog.setOnDismissListener(d -> { dialogs.remove(dialog); dismissed.run(); });
        dialog.show();
        return dialog;
    }
    private LinearLayout body() {
        LinearLayout result = new LinearLayout(activity); result.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(activity.getResources().getDisplayMetrics().density * 20);
        result.setPadding(padding, padding, padding, padding); return result;
    }
    private TextView addText(LinearLayout body, String value) {
        TextView text = new TextView(activity); text.setText(value); text.setTextSize(15);
        text.setPadding(0, 8, 0, 16); body.addView(text); return text;
    }
    private void addButton(LinearLayout body, String label, Runnable action) {
        Button button = new Button(activity); button.setText(label); button.setOnClickListener(v -> action.run()); body.addView(button);
    }
    private ScrollView scroll(LinearLayout body) { ScrollView scroll = new ScrollView(activity); scroll.addView(body); return scroll; }
    private static String mergePageContent(String transcript, List<TextFlow> flows, int page) {
        StringBuilder result = new StringBuilder();
        for (TextFlow flow : flows) if (flow.anchorPageIndex == page) {
            String source = VaultStore.embedTextFlow(flow);
            if (!source.isEmpty()) result.append(source).append("\n\n");
        }
        return result.append(transcript.trim()).toString();
    }
    private static final class Capture implements AutoCloseable {
        final JSONObject document;
        final NoteCanvasView.PdfExportSnapshot snapshot;
        final List<TextFlow> flows;
        final File pdf;
        final AiConfigStore.Config config;
        final long sourceUpdatedAt;
        Capture(JSONObject document, NoteCanvasView.PdfExportSnapshot snapshot, List<TextFlow> flows,
                File pdf, String profileID, AiConfigStore.Config config, long sourceUpdatedAt) {
            this.document = document; this.snapshot = snapshot; this.flows = flows; this.pdf = pdf;
            this.config = config; this.sourceUpdatedAt = sourceUpdatedAt;
        }
        @Override public void close() { if (snapshot != null) snapshot.close(); }
    }
    private static final class Run {
        final Capture capture;
        final int serial;
        final OpenAiCompatibleClient.Cancellation cancellation = new OpenAiCompatibleClient.Cancellation();
        volatile DigitizationStore.Snapshot draft;
        String localFailure;
        boolean limitFailure;
        boolean skipFailureCommit;
        DigitizationStore.Lease lease;
        AlertDialog progress;
        Run(Capture capture, int serial) { this.capture = capture; this.serial = serial; }
    }
    private static final class BoundedPng extends ByteArrayOutputStream {
        final int maximum;
        BoundedPng(int maximum) { this.maximum = maximum; }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            if (length > maximum - count) throw new IllegalStateException("页面图像超出上传预算");
            super.write(bytes, offset, length);
        }
        @Override public synchronized void write(int value) {
            if (count >= maximum) throw new IllegalStateException("页面图像超出上传预算");
            super.write(value);
        }
    }
}
