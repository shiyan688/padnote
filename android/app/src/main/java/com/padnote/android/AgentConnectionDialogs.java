package com.padnote.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Multi-profile Agent settings kept out of MainActivity. */
final class AgentConnectionDialogs {
    interface QrLauncher { void launch(); }
    private final Activity activity;
    private final AgentConnectionStore store;
    private final ExecutorService worker;
    private final Runnable changed;
    private final QrLauncher qrLauncher;
    private final AgentTaskDialogs.ArtifactSaveLauncher artifactSaveLauncher;

    AgentConnectionDialogs(Activity activity, AgentConnectionStore store,
                           ExecutorService worker, Runnable changed) {
        this(activity, store, worker, changed, null, null);
    }

    AgentConnectionDialogs(Activity activity, AgentConnectionStore store,
                           ExecutorService worker, Runnable changed, QrLauncher qrLauncher,
                           AgentTaskDialogs.ArtifactSaveLauncher artifactSaveLauncher) {
        this.activity = activity; this.store = store; this.worker = worker;
        this.changed = changed == null ? () -> { } : changed;
        this.qrLauncher = qrLauncher;
        this.artifactSaveLauncher = artifactSaveLauncher;
    }

    void show() {
        List<AgentConnectionStore.Config> profiles;
        try { profiles = store.listSummaries(); }
        catch (RuntimeException error) { error(activity.getString(R.string.flow_cannot_read_connections, message(error))); return; }
        List<String> labels = new ArrayList<>();
        labels.add(activity.getString(R.string.flow_view_tasks));
        String defaultId = store.defaultId();
        for (AgentConnectionStore.Config profile : profiles) {
            labels.add((profile.id.equals(defaultId) ? "★ " : "") +
                    AgentTaskDestinationLabel.forProfile(activity,profile) + " · " + profile.statusLabel(activity));
        }
        int manualIndex = labels.size();
        labels.add(activity.getString(R.string.flow_manual_add));
        int qrIndex = -1;
        if (qrLauncher != null) {
            qrIndex = labels.size();
            labels.add(activity.getString(R.string.flow_scan_pair));
        }
        int pasteIndex = labels.size();
        labels.add(activity.getString(R.string.flow_paste_pair));
        int guideIndex = labels.size();
        labels.add(activity.getString(R.string.flow_connect_other));
        final int finalQrIndex = qrIndex;
        ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_agent_title))
                .setAdapter(ChoiceRows.adapter(activity, labels.toArray(new String[0])), (dialog, which) -> {
                    if (which == 0) new AgentTaskDialogs(activity, store, worker,
                            artifactSaveLauncher).showList();
                    else if (which <= profiles.size()) showActions(profiles.get(which - 1));
                    else if (which == manualIndex) showEditor(null);
                    else if (which == finalQrIndex) qrLauncher.launch();
                    else if (which == pasteIndex) showPairingPaste();
                    else if (which == guideIndex) AgentConnectionGuide.show(activity);
                })
                .setNegativeButton(activity.getString(R.string.flow_close), null).show();
    }

    private void showActions(AgentConnectionStore.Config summary) {
        AgentConnectionStore.Config profile = store.get(summary.id);
        if (profile == null) { error(activity.getString(R.string.flow_connection_missing)); return; }
        boolean isDefault = profile.id.equals(store.defaultId());
        List<String> actions = new ArrayList<>();
        if (!isDefault) actions.add(activity.getString(R.string.flow_set_default));
        if (AgentCapabilitySummary.canSubmitHermesText(profile.kind, profile.verified(),
                profile.capabilities)) actions.add(activity.getString(R.string.flow_send_text_task));
        actions.add(activity.getString(R.string.flow_capabilities));
        actions.add(activity.getString(R.string.flow_test_connection));
        actions.add(activity.getString(R.string.flow_edit));
        actions.add(activity.getString(R.string.flow_delete));
        LinearLayout body = panel();
        TextView identity = text(activity.getString(R.string.flow_target) + "\n" + AgentTaskDestinationLabel.forProfile(activity,profile) +
                "\n" + activity.getString(R.string.flow_connection_status_value, profile.statusLabel(activity)), 14);
        identity.setTag("agent-target-identity");
        body.addView(identity);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_connection_actions))
                .setView(scroll(body)).setNegativeButton(activity.getString(R.string.flow_back), (window, which) -> show()).create();
        for (String action : actions) {
            Button row = new Button(activity);
            row.setAllCaps(false);
            row.setText(action);
            body.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOnClickListener(view -> {
                dialog.dismiss();
                performAction(profile, action);
            });
        }
        dialog.show();
    }

    private void performAction(AgentConnectionStore.Config profile, String action) {
        if (activity.getString(R.string.flow_set_default).equals(action)) {
            if (!store.setDefault(profile.id)) error(activity.getString(R.string.flow_cannot_default));
            else { changed.run(); show(); }
        } else if (activity.getString(R.string.flow_send_text_task).equals(action)) {
            if (!AgentCapabilitySummary.canSubmitHermesText(profile.kind,
                    profile.verified(), profile.capabilities)) {
                error(activity.getString(R.string.flow_unsupported_hermes));
            } else new AgentTaskDialogs(activity, store, worker,
                    artifactSaveLauncher).showNewText(profile);
        } else if (activity.getString(R.string.flow_capabilities).equals(action)) {
            showCapabilitySummary(profile);
        } else if (activity.getString(R.string.flow_test_connection).equals(action)) test(profile);
        else if (activity.getString(R.string.flow_edit).equals(action)) showEditor(profile);
        else confirmDelete(profile);
    }

    private void showCapabilitySummary(AgentConnectionStore.Config profile) {
        StringBuilder body = new StringBuilder();
        if (profile.verified()) {
            String checked = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(profile.verifiedAt));
            body.append(activity.getString(R.string.flow_last_verified_value, checked))
                    .append("\n").append(activity.getString(R.string.flow_verified_note)).append("\n\n");
        }
        if (profile.probeFailure != null) {
            body.append(activity.getString(R.string.flow_last_test_value, profile.probeFailure.label(activity)))
                    .append("\n").append(activity.getString(R.string.flow_next_step_value, profile.probeFailure.nextStep(activity)))
                    .append("\n\n");
        } else if (!profile.verified()) {
            body.append(activity.getString(R.string.flow_not_verified_hint)).append("\n\n");
        }
        body.append(AgentCapabilitySummary.describe(activity, profile.kind, profile.transport,
                profile.verified(), profile.capabilities, profile.featureMapAvailable,
                profile.featureMap));
        new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_capabilities))
                .setMessage(activity.getString(R.string.flow_target) + ":\n" + AgentTaskDestinationLabel.forProfile(activity,profile) +
                        "\n\n" + body).setPositiveButton(activity.getString(R.string.flow_okay), null)
                .setNegativeButton(activity.getString(R.string.flow_retest), (dialog, which) -> test(profile)).show();
    }

    private void showEditor(AgentConnectionStore.Config existing) {
        LinearLayout body = panel();
        EditText name = field(activity.getString(R.string.flow_example_name), false);
        Spinner kind = spinner(new String[]{activity.getString(R.string.flow_hermes_option), activity.getString(R.string.flow_openclaw_option)});
        Spinner transport = spinner(new String[]{activity.getString(R.string.flow_direct_https), activity.getString(R.string.flow_computer_bridge)});
        EditText endpoint = field(activity.getString(R.string.flow_endpoint_hint), false);
        EditText token = field(existing == null ? activity.getString(R.string.flow_new_token_hint) : activity.getString(R.string.flow_keep_token_hint), true);
        if (existing != null) {
            name.setText(existing.name); endpoint.setText(existing.endpoint);
            kind.setSelection(existing.kind == AgentConnectionStore.Kind.OPENCLAW ? 1 : 0);
            transport.setSelection(existing.transport == AgentConnectionStore.Transport.BRIDGE ? 1 : 0);
            if (existing.transport == AgentConnectionStore.Transport.BRIDGE) {
                endpoint.setEnabled(false); kind.setEnabled(false); transport.setEnabled(false);
            }
        }
        addLabeled(body, activity.getString(R.string.flow_name), name); addLabeled(body, activity.getString(R.string.flow_protocol), kind);
        addLabeled(body, activity.getString(R.string.flow_connection_method), transport); addLabeled(body, activity.getString(R.string.flow_https_address), endpoint);
        addLabeled(body, activity.getString(R.string.flow_token), token);
        TextView note = text(existing != null && existing.transport == AgentConnectionStore.Transport.BRIDGE
                ? activity.getString(R.string.flow_bridge_edit_note)
                : activity.getString(R.string.flow_edit_revision_note), 12);
        body.addView(note);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(existing == null ? activity.getString(R.string.flow_add_agent) : activity.getString(R.string.flow_edit_agent))
                .setView(scroll(body)).setNegativeButton(activity.getString(R.string.flow_cancel), null)
                .setPositiveButton(activity.getString(R.string.flow_save), null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        AgentConnectionStore.Kind selectedKind = kind.getSelectedItemPosition() == 1
                                ? AgentConnectionStore.Kind.OPENCLAW : AgentConnectionStore.Kind.HERMES;
                        AgentConnectionStore.Transport selectedTransport =
                                transport.getSelectedItemPosition() == 1
                                        ? AgentConnectionStore.Transport.BRIDGE
                                        : AgentConnectionStore.Transport.DIRECT;
                        String secret = token.getText().toString().trim();
                        if (existing == null) {
                            if (selectedTransport == AgentConnectionStore.Transport.BRIDGE) {
                                throw new IllegalArgumentException(activity.getString(R.string.flow_bridge_pairing_required));
                            }
                            store.add(name.getText().toString(), selectedKind, selectedTransport,
                                    endpoint.getText().toString(), secret);
                        } else {
                            store.update(existing.id, existing.revision, name.getText().toString(),
                                    selectedKind, selectedTransport, endpoint.getText().toString(),
                                    secret);
                        }
                        dialog.dismiss(); changed.run(); show();
                    } catch (Exception error) { note.setText(activity.getString(R.string.flow_connection_save_failed, message(error))); }
                }));
        dialog.show();
    }

    private void test(AgentConnectionStore.Config profile) {
        String attemptId = store.beginProbe(profile.id, profile.revision);
        if (attemptId.isEmpty()) {
            error(activity.getString(R.string.flow_connection_state_changed));
            return;
        }
        AlertDialog progress = new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_test_profile, profile.name))
                .setMessage(activity.getString(R.string.flow_checking_connection)).setCancelable(false).create();
        progress.show();
        worker.execute(() -> {
            try {
                AgentConnectionClient.ProbeResult result = AgentConnectionClient.probeResult(profile);
                boolean applied = store.applyProbeSuccess(profile.id, profile.revision, attemptId, result);
                activity.runOnUiThread(() -> {
                    progress.dismiss(); changed.run();
                    String successMessage = result.partialFailure != null
                            ? result.partialFailure.label(activity) + "。" + result.partialFailure.nextStep(activity)
                            : profile.transport == AgentConnectionStore.Transport.BRIDGE &&
                                    profile.kind == AgentConnectionStore.Kind.HERMES
                                    ? activity.getString(R.string.flow_bridge_agent_ready) : result.message;
                    Toast.makeText(activity, applied ? successMessage :
                                    activity.getString(R.string.flow_test_result_ignored),
                            Toast.LENGTH_LONG).show(); show();
                });
            } catch (Exception error) {
                AgentConnectionStore.ProbeFailure failure = AgentConnectionClient.safeFailure(error);
                boolean applied = store.applyProbeFailure(profile.id, profile.revision,
                        attemptId, failure);
                activity.runOnUiThread(() -> {
                    progress.dismiss(); changed.run();
                    Toast.makeText(activity, applied ? activity.getString(R.string.flow_test_failed,
                                    failure.label(activity), failure.nextStep(activity)) :
                            activity.getString(R.string.flow_test_result_ignored),
                            Toast.LENGTH_LONG).show(); show();
                });
            }
        });
    }

    private void confirmDelete(AgentConnectionStore.Config profile) {
        new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_delete_connection))
                .setMessage(activity.getString(R.string.flow_delete_connection_body, profile.name))
                .setNegativeButton(activity.getString(R.string.flow_cancel), null)
                .setPositiveButton(activity.getString(R.string.flow_delete), (dialog, which) -> {
                    if (!store.delete(profile.id)) error(activity.getString(R.string.flow_cannot_delete));
                    changed.run(); show();
                }).show();
    }

    private void showPairingPaste() {
        LinearLayout body = panel();
        EditText payload = field(activity.getString(R.string.flow_pair_payload_hint), false);
        payload.setSingleLine(false); payload.setMinLines(6);
        body.addView(payload);
        TextView status = text(activity.getString(R.string.flow_pairing_privacy), 12);
        body.addView(status);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_pairing_title))
                .setView(scroll(body)).setNegativeButton(activity.getString(R.string.flow_cancel), null)
                .setPositiveButton(activity.getString(R.string.flow_check_apply), null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    final AgentPairingClient.PairingPayload parsed;
                    try { parsed = AgentPairingClient.parse(payload.getText().toString()); }
                    catch (Exception error) { status.setText(activity.getString(R.string.flow_parse_failed, message(error))); return; }
                    new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_pair_approve_title))
                            .setMessage(activity.getString(R.string.flow_address_value, parsed.endpoint) + "\n" + activity.getString(R.string.flow_computer_id_value, parsed.bridgeId) + "\n\n" + activity.getString(R.string.flow_no_token_until_approved))
                            .setNegativeButton(activity.getString(R.string.flow_cancel), null)
                            .setPositiveButton(activity.getString(R.string.flow_request), (confirm, which) -> {
                                dialog.dismiss(); beginPairing(parsed);
                            }).show();
                }));
        dialog.show();
    }

    void showPairingPayload(String raw) {
        final AgentPairingClient.PairingPayload parsed;
        try { parsed = AgentPairingClient.parse(raw); }
        catch (Exception error) { error(activity.getString(R.string.flow_qr_invalid, message(error))); return; }
        new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_pair_approve_title))
                .setMessage(activity.getString(R.string.flow_address_value, parsed.endpoint) + "\n" + activity.getString(R.string.flow_computer_id_value, parsed.bridgeId) + "\n\n" + activity.getString(R.string.flow_no_token_until_approved))
                .setNegativeButton(activity.getString(R.string.flow_cancel), null)
                .setPositiveButton(activity.getString(R.string.flow_request), (dialog, which) -> beginPairing(parsed)).show();
    }

    private void beginPairing(AgentPairingClient.PairingPayload payload) {
        AtomicBoolean cancelled = new AtomicBoolean();
        AlertDialog waiting = new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_waiting_approval))
                .setMessage(activity.getString(R.string.flow_approve_in_assistant))
                .setNegativeButton(activity.getString(R.string.flow_cancel), (dialog, which) -> cancelled.set(true))
                .setCancelable(false).create();
        waiting.show();
        worker.execute(() -> {
            try {
                String deviceId = AgentDeviceIdentity.id(activity);
                AgentPairingClient client = new AgentPairingClient();
                AgentPairingClient.Session session = client.request(payload, deviceId,
                        AgentDeviceIdentity.name());
                while (!cancelled.get() && System.currentTimeMillis() < session.expiresAtMillis) {
                    AgentPairingClient.Claim claim = client.claim(session, deviceId);
                    if (claim.state == AgentPairingClient.ClaimState.PENDING) {
                        Thread.sleep(2500L); continue;
                    }
                    if (claim.state != AgentPairingClient.ClaimState.APPROVED) {
                        throw new IllegalStateException(activity.getString(claim.state == AgentPairingClient.ClaimState.DENIED
                                ? R.string.flow_pair_denied : R.string.flow_pair_expired));
                    }
                    int saved = 0; List<String> failures = new ArrayList<>();
                    for (AgentPairingClient.ApprovedConnection item : claim.connections) {
                        if (item.kind == AgentConnectionStore.Kind.OPENCLAW) {
                            failures.add(item.name + "：" + activity.getString(R.string.flow_openclaw_task_unavailable)); continue;
                        }
                        try {
                            store.addBridge(item.name, item.kind, payload.endpoint,
                                    payload.bridgeId, item.instanceId, payload.certSha256,
                                    item.token); saved++;
                        } catch (Exception error) { failures.add(item.name + "：" + message(error)); }
                    }
                    int completed = saved; String detail = String.join("\n", failures);
                    activity.runOnUiThread(() -> {
                        waiting.dismiss(); changed.run();
                        Toast.makeText(activity, activity.getString(R.string.flow_saved_agents, completed) +
                                (detail.isEmpty() ? "" : "\n" + activity.getString(R.string.flow_not_completed, detail)),
                                Toast.LENGTH_LONG).show(); show();
                    });
                    return;
                }
                if (!cancelled.get()) throw new IllegalStateException(activity.getString(R.string.flow_pair_expired));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                if (!cancelled.get()) activity.runOnUiThread(() -> {
                    waiting.dismiss(); AgentConnectionDialogs.this.error(activity.getString(R.string.flow_pair_failed, message(error)));
                });
            } finally {
                if (cancelled.get()) activity.runOnUiThread(waiting::dismiss);
            }
        });
    }

    private LinearLayout panel() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(20), dp(8), dp(20), dp(8)); return v; }
    private ScrollView scroll(View child) { ScrollView s = new ScrollView(activity); s.addView(child); return s; }
    private Spinner spinner(String[] values) { Spinner s = new Spinner(activity); ChoiceRows.styleSpinner(s); s.setAdapter(ChoiceRows.spinnerAdapter(s, values)); return s; }
    private EditText field(String hint, boolean secret) { EditText f = new EditText(activity); f.setHint(hint); f.setSingleLine(true); f.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0)); return f; }
    private void addLabeled(LinearLayout body, String label, View field) { TextView t = text(label, 12); t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); body.addView(t); body.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); }
    private TextView text(String value, int size) { TextView t = new TextView(activity); t.setText(value); t.setTextSize(size); t.setPadding(0, dp(6), 0, dp(6)); return t; }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private void error(String value) { new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.flow_agent_title)).setMessage(value).setPositiveButton(activity.getString(R.string.flow_okay), null).show(); }
    private String message(Throwable error) { String m = error.getMessage(); return m == null || m.trim().isEmpty() ? activity.getString(R.string.flow_unknown_error) : m; }
}
