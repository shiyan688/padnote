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

    /**
     * Compatibility entry point for the existing shelf action. It captures a new ADD target;
     * replacement/update remains unavailable unless a future explicit destination UI supplies it.
     */
    void open(NoteCanvasView canvas,String noteID,String title,String profileID,
              AiConfigStore.Config config,long sourceUpdatedAt) {
        try {
            DigitizationStore.Target target=vault.captureDigitizationTarget(noteID,null);
            open(canvas,noteID,title,profileID,config,sourceUpdatedAt,target);
        } catch(Exception unavailableTarget) {
            message(activity.getString(R.string.widget_digit_target_missing, safeMessage(unavailableTarget)));
        }
    }

    /** Called on the UI thread after the inline editor has committed. */
    void open(NoteCanvasView canvas, String noteID, String title, String profileID,
              AiConfigStore.Config config, long sourceUpdatedAt,DigitizationStore.Target target) {
        if (closed || preparing != null || active != null) return;
        if(target==null||noteID==null||!noteID.equals(target.noteId)){
            message(activity.getString(R.string.widget_digit_target_invalid));
            return;
        }
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
                    profileID, config, sourceUpdatedAt,target);
            preparing = capture;
        } catch (Exception error) {
            message(activity.getString(R.string.widget_digit_prepare_failed, safeMessage(error)));
            return;
        }
        AlertDialog progress = dialog(new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.widget_digit_title)).setMessage(activity.getString(R.string.widget_digit_verifying))
                .setNegativeButton(activity.getString(R.string.widget_digit_cancel), (d, which) -> pause()).create());
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
                    if (current(serial)) message(activity.getString(R.string.widget_digit_read_failed) + safeMessage(error));
                });
            }
        });
    }

    private void showPlan(Capture capture, DigitizationStore.Source source,
                          List<DigitizationStore.Snapshot> history,
                          List<DigitizationStore.CorruptEntry> corrupt, int serial) {
        LinearLayout body = body();
        addText(body, activity.getString(R.string.widget_digit_intro));
        if (source != null) {
            addText(body, activity.getString(R.string.widget_digit_upload_scope, capture.snapshot.getPageCount(), capture.config.model, safeTarget(capture.config.endpoint)));
        } else {
            addText(body, capture.config == null
                    ? activity.getString(R.string.widget_digit_need_config)
                    : activity.getString(R.string.widget_digit_config_invalid));
            if (configureAI != null) addButton(body, activity.getString(R.string.widget_digit_ai_settings), () -> { pause(); configureAI.run(); });
        }
        DigitizationStore.Snapshot matching = null;
        for (DigitizationStore.Snapshot draft : history) {
            if (source != null && draft.matches(source) && draft.target!=null
                    &&draft.target.sameIntent(capture.target)){matching=draft;break;}
        }
        if(matching==null)for(DigitizationStore.Snapshot draft:history){
            if(source!=null&&draft.matches(source)&&draft.target==null){matching=draft;break;}
        }
        final DigitizationStore.Snapshot resume = matching;
        AlertDialog plan = new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.widget_digit_note_title_format,
                capture.document.optString("title", activity.getString(R.string.widget_digit_note_title))))
                .setView(scroll(body)).setNegativeButton(activity.getString(R.string.widget_digit_close), null).create();
        if (resume != null) {
            addText(body, activity.getString(R.string.widget_digit_resume_progress, resume.completedCount(), resume.totalPages) + ("COMPLETED".equals(resume.state.name())
                    ? activity.getString(R.string.widget_digit_saved) : activity.getString(R.string.widget_digit_draft_not_overwrite)));
            if (resume.inFlightPage >= 0) addText(body, activity.getString(R.string.widget_digit_uncertain_send, resume.inFlightPage + 1));
            if (!"COMPLETED".equals(resume.state.name())) {
                addButton(body, resume.nextPendingPage() < 0 ? activity.getString(R.string.widget_digit_save_transcript) : activity.getString(R.string.widget_digit_continue_pages),
                        () -> start(capture, source, resume, serial, plan));
            }
        } else if (!history.isEmpty()) {
            addText(body, activity.getString(R.string.widget_digit_old_batch_diff));
        }
        if(source!=null){
            for(DigitizationStore.Snapshot prior:history){
                if(!prior.matches(source)||prior.target==null
                        ||prior.target.sameIntent(capture.target)
                        ||prior.state==DigitizationStore.State.COMPLETED)continue;
                String destination=prior.target.kind==DigitizationStore.TargetKind.GROUP
                        ?(prior.target.operation==DigitizationStore.TargetOperation.ADD?activity.getString(R.string.widget_digit_new_group):activity.getString(R.string.widget_digit_old_group))
                        :activity.getString(R.string.widget_digit_local_material);
                addButton(body, activity.getString(R.string.widget_digit_resume_old_target, destination, prior.completedCount(), prior.totalPages), () -> {
                    capture.target=prior.target;
                    start(capture,source,prior,serial,plan);
                });
            }
        }
        if (source != null) {
            addButton(body, history.isEmpty() ? activity.getString(R.string.widget_digit_confirm_range_start) : activity.getString(R.string.widget_digit_new_batch), () -> {
                if (history.isEmpty()) start(capture, source, null, serial, plan);
                else dialog(new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.widget_digit_repeat_title))
                        .setMessage(activity.getString(R.string.widget_digit_repeat_body))
                        .setNegativeButton(activity.getString(R.string.widget_digit_cancel), null)
                        .setPositiveButton(activity.getString(R.string.widget_digit_start_new), (d, which) -> start(capture, source, null, serial, plan))
                        .create());
            });
        }
        if (!history.isEmpty()) addButton(body, activity.getString(R.string.widget_digit_history_count, history.size()), () -> showHistory(history));
        if (!corrupt.isEmpty()) {
            addText(body, activity.getString(R.string.widget_digit_corrupt_count, corrupt.size()));
            addButton(body, activity.getString(R.string.widget_digit_corrupt_action), () -> {
                StringBuilder description = new StringBuilder(activity.getString(R.string.widget_digit_corrupt_detail) + "\n\n");
                for (DigitizationStore.CorruptEntry item : corrupt) description.append(item.fileName).append('\n');
                dialog(new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.widget_digit_unreadable_draft))
                        .setMessage(description.toString()).setPositiveButton(activity.getString(R.string.widget_digit_close), null).create());
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
        run.progress = dialog(new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.widget_digit_running_title))
                .setMessage(activity.getString(R.string.widget_digit_preparing)).setNegativeButton(activity.getString(R.string.widget_digit_cancel_action), null).setCancelable(false).create());
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
                        run.capture.document.optString("title", activity.getString(R.string.widget_digit_note_title)), run.capture.sourceUpdatedAt,
                        run.capture.target) : store.load(resume.runId);
                if (!run.draft.matches(source)) throw new IllegalStateException("原文或接收者已改变，请新建整理批次");
                try { run.lease = store.acquireLease(run.draft.runId); }
                catch (Exception unavailableLease) {
                    run.localFailure = activity.getString(R.string.widget_digit_busy);
                    throw unavailableLease;
                }
                if (run.lease == null) {
                    run.localFailure = activity.getString(R.string.widget_digit_busy);
                    throw new IllegalStateException("Digitization checkpoint lease is busy");
                }
                run.draft = store.load(run.draft.runId);
                if (resume != null && run.draft.revision != resume.revision) {
                    run.localFailure = activity.getString(R.string.widget_digit_progress_changed);
                    run.skipFailureCommit = true;
                    throw new IllegalStateException("Checkpoint changed after confirmation");
                }
                if(run.draft.target==null)run.draft=store.bindTarget(run.draft,run.capture.target);
                else if(!run.draft.target.sameIntent(run.capture.target))
                    throw new IllegalStateException("整理稿已绑定其他知识库目标，请查看或导出原稿");
                if (!"COMPLETED".equals(run.draft.state.name())) run.draft = store.startAttempt(run.draft);
            }
            while (run.draft.nextPendingPage() >= 0) {
                run.cancellation.throwIfCancelled();
                int page = run.draft.nextPendingPage();
                update(run, activity.getString(R.string.widget_digit_page_progress, page + 1, run.draft.totalPages, run.draft.completedCount()));
                Bitmap bitmap = run.capture.snapshot.renderBasePage(page, 1600);
                byte[] png;
                try {
                    BoundedPng output = new BoundedPng(MAX_PNG_BYTES);
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw new IllegalStateException(activity.getString(R.string.widget_digit_page_encode_failed));
                    }
                    png = output.toByteArray();
                } finally { bitmap.recycle(); }
                synchronized (run) {
                    run.cancellation.throwIfCancelled();
                    run.draft = store.markPageStarted(run.draft, page);
                }
                String transcript = transcriber.transcribe(run.capture.config, png, PROMPT, run.cancellation);
                if (transcript == null || transcript.trim().isEmpty()) throw new IllegalStateException(activity.getString(R.string.widget_digit_empty_result));
                String pageText = mergePageContent(transcript, run.capture.flows, page);
                synchronized (run) {
                    run.cancellation.throwIfCancelled();
                    try { run.draft = store.savePage(run.draft, page, pageText); }
                    catch (IllegalArgumentException limit) {
                        // Only local validation text is displayed; provider error bodies stay excluded.
                        run.localFailure = activity.getString(R.string.widget_digit_limit_failure);
                        run.limitFailure = true;
                        throw limit;
                    }
                }
            }
            // Publication provenance belongs to the captured checkpoint. A later save of
            // identical canonical ink must not change a retry's immutable output bytes.
            long sourceTime = run.draft.sourceUpdatedAt;
            synchronized (run) {
                run.cancellation.throwIfCancelled();
                if (!"COMPLETED".equals(run.draft.state.name())) {
                    update(run, activity.getString(R.string.widget_digit_all_saved));
                    String name = vault.publishDigitization(run.draft.target,run.draft.title,
                            run.draft.totalPages,sourceTime,run.draft.pages);
                    run.draft = store.markPublished(run.draft, name);
                }
                published = true;
            }
            result = activity.getString(R.string.widget_digit_saved);
        } catch (Exception error) {
            boolean cancelled = run.cancellation.isCancelled();
            String persistError = "";
            if (run.draft != null && run.lease != null && !run.skipFailureCommit) {
                try { run.draft = cancelled ? store.cancel(run.draft) : store.fail(run.draft, "本次整理未完成"); }
                catch (Exception saveError) { persistError = " " + activity.getString(R.string.widget_digit_persist_failed); }
            }
            result = cancelled ? activity.getString(R.string.widget_digit_local_cancelled)
                    : activity.getString(R.string.widget_digit_failed, run.localFailure == null ? safeMessage(error) : run.localFailure,
                    (run.limitFailure ? ". " + activity.getString(R.string.widget_digit_limit_action) : "") + persistError);
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
                AlertDialog.Builder alert = new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.widget_digit_failed_title))
                        .setMessage(notice).setPositiveButton(activity.getString(R.string.widget_digit_close), null);
                if (run.draft != null && run.draft.completedCount() > 0) alert.setNeutralButton(activity.getString(R.string.widget_digit_partial),
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
            run.progress.setMessage(activity.getString(R.string.widget_digit_canceling));
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
            labels[index] = activity.getString(R.string.widget_digit_batch_label, draft.completedCount(), draft.totalPages,
                    ("COMPLETED".equals(draft.state.name()) ? activity.getString(R.string.widget_digit_stored) : activity.getString(R.string.widget_digit_incomplete)),
                    android.text.format.DateFormat.format("MM-dd HH:mm", draft.createdAt));
        }
        dialog(ChoiceRows.builder(activity).setTitle(activity.getString(R.string.widget_digit_saved_batches))
                .setAdapter(ChoiceRows.adapter(activity, labels), (d, which) -> preview(history.get(which)))
                .setNegativeButton(activity.getString(R.string.widget_digit_close), null).create());
    }

    private void preview(DigitizationStore.Snapshot draft) {
        String markdown = draft.partialMarkdown();
        LinearLayout content = body();
        addText(content, activity.getString(R.string.widget_digit_preview_intro));
        TextView text = addText(content, markdown.length() > 100_000
                ? markdown.substring(0, 100_000) + "\n\n" + activity.getString(R.string.widget_digit_preview_truncated) : markdown);
        text.setTextIsSelectable(true);
        dialog(new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.widget_digit_draft_title, draft.completedCount(), draft.totalPages))
                .setView(scroll(content)).setPositiveButton(activity.getString(R.string.widget_digit_close), null)
                .setNeutralButton(activity.getString(R.string.widget_digit_export_markdown), (d, which) -> {
                    pendingExport = markdown;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("text/markdown").putExtra(Intent.EXTRA_TITLE,
                                    VaultStore.sanitizeTitle(draft.title) + activity.getString(R.string.widget_digit_file_title));
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
                if (output == null) throw new IllegalStateException(activity.getString(R.string.widget_digit_file_unavailable));
                output.write(markdown.getBytes(StandardCharsets.UTF_8));
                main.post(() -> { if (!closed) message(activity.getString(R.string.widget_digit_exported)); });
            } catch (Exception error) { main.post(() -> { if (!closed) message(activity.getString(R.string.widget_digit_export_failed, safeMessage(error))); }); }
        });
        return true;
    }

    private void update(Run run, String message) {
        main.post(() -> { if (current(run.serial) && active == run && !run.cancellation.isCancelled()) run.progress.setMessage(message); });
    }
    private boolean current(int serial) { return !closed && generation == serial && !activity.isFinishing() && !activity.isDestroyed(); }
    private void message(String value) { Toast.makeText(activity, value, Toast.LENGTH_LONG).show(); }
    private String safeMessage(Exception error) {
        // Provider bodies may contain private text. The network layer already categorizes its errors.
        if (error instanceof java.util.concurrent.CancellationException) return activity.getString(R.string.widget_digit_cancelled);
        if (error instanceof java.io.IOException) return activity.getString(R.string.widget_digit_io_failed);
        return activity.getString(R.string.widget_digit_request_failed);
    }
    private String safeTarget(String endpoint) {
        try { java.net.URI uri = new java.net.URI(endpoint); return uri.getHost() == null ? activity.getString(R.string.widget_digit_configured_service) : uri.getHost(); }
        catch (Exception ignored) { return activity.getString(R.string.widget_digit_configured_service); }
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
        DigitizationStore.Target target;
        Capture(JSONObject document, NoteCanvasView.PdfExportSnapshot snapshot, List<TextFlow> flows,
                File pdf, String profileID, AiConfigStore.Config config, long sourceUpdatedAt,
                DigitizationStore.Target target) {
            this.document = document; this.snapshot = snapshot; this.flows = flows; this.pdf = pdf;
            this.config = config; this.sourceUpdatedAt = sourceUpdatedAt;this.target=target;
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
            if (length > maximum - count) throw new IllegalStateException("Page image exceeds upload budget");
            super.write(bytes, offset, length);
        }
        @Override public synchronized void write(int value) {
            if (count >= maximum) throw new IllegalStateException("Page image exceeds upload budget");
            super.write(value);
        }
    }
}
