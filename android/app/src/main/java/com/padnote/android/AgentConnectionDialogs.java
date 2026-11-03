package com.padnote.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Multi-profile Agent settings kept out of MainActivity. */
final class AgentConnectionDialogs {
    interface QrLauncher { void launch(); }
    interface TaskDialogsFactory { AgentTaskDialogs create(); }
    interface SavedProfileProbe { AgentConnectionClient.ProbeResult probe(AgentConnectionStore.Config profile) throws Exception; }
    enum ProbeState { READY, NEEDS_ATTENTION, STALE }
    static final class ProbeOutcome {
        final ProbeState state;
        final AgentConnectionStore.ProbeFailure failure;
        final boolean persistenceFailure;
        ProbeOutcome(ProbeState state, AgentConnectionStore.ProbeFailure failure) {
            this(state,failure,false);
        }
        ProbeOutcome(ProbeState state, AgentConnectionStore.ProbeFailure failure,boolean persistenceFailure) {
            this.state=state;this.failure=failure;this.persistenceFailure=persistenceFailure;
        }
    }

    static ProbeOutcome probeSavedProfile(AgentConnectionStore store,
                                           AgentConnectionStore.Config saved,
                                           String attemptId, SavedProfileProbe probe) {
        if(store==null||saved==null||attemptId==null||attemptId.isEmpty()||probe==null)
            return new ProbeOutcome(ProbeState.STALE,null);
        try {
            AgentConnectionClient.ProbeResult result=probe.probe(saved);
            if(result==null)throw new IllegalStateException("Probe returned no result");
            boolean applied=store.applyProbeSuccess(saved.id,saved.revision,attemptId,result);
            if(!applied)return new ProbeOutcome(ProbeState.STALE,null);
            return result!=null&&result.partialFailure!=null
                    ?new ProbeOutcome(ProbeState.NEEDS_ATTENTION,result.partialFailure)
                    :new ProbeOutcome(ProbeState.READY,null);
        } catch(Exception error) {
            AgentConnectionStore.ProbeFailure failure=AgentConnectionClient.safeFailure(error);
            try {
                boolean applied=store.applyProbeFailure(saved.id,saved.revision,attemptId,failure);
                return applied?new ProbeOutcome(ProbeState.NEEDS_ATTENTION,failure)
                        :new ProbeOutcome(ProbeState.STALE,null);
            } catch(RuntimeException storageFailure) {
                return new ProbeOutcome(ProbeState.NEEDS_ATTENTION,
                        AgentConnectionStore.ProbeFailure.UNKNOWN,true);
            }
        }
    }

    static boolean mayDeliverProbeUi(Activity activity) {
        return activity!=null&&!activity.isFinishing()&&!activity.isDestroyed();
    }

    private final Activity activity;
    private final AgentConnectionStore store;
    private final ExecutorService worker;
    private final Runnable changed;
    private final QrLauncher qrLauncher;
    private final AgentTaskDialogs.ArtifactSaveLauncher artifactSaveLauncher;
    private final TaskDialogsFactory taskDialogsFactory;
    private final SavedProfileProbe savedProfileProbe;
    private final java.util.Set<String> automaticProbeIds=Collections.newSetFromMap(
            new ConcurrentHashMap<String,Boolean>());
    private final ConcurrentHashMap<String,Long> automaticProbeSetupFailures=new ConcurrentHashMap<>();
    private WeakReference<AlertDialog> connectionListDialog=new WeakReference<>(null);
    private ConnectionListState connectionListState;

    AgentConnectionDialogs(Activity activity, AgentConnectionStore store,
                           ExecutorService worker, Runnable changed) {
        this(activity, store, worker, changed, null, null, null);
    }

    AgentConnectionDialogs(Activity activity, AgentConnectionStore store,
                           ExecutorService worker, Runnable changed, QrLauncher qrLauncher,
                           AgentTaskDialogs.ArtifactSaveLauncher artifactSaveLauncher) {
        this(activity,store,worker,changed,qrLauncher,artifactSaveLauncher,null);
    }

    AgentConnectionDialogs(Activity activity, AgentConnectionStore store,
                           ExecutorService worker, Runnable changed, QrLauncher qrLauncher,
                           AgentTaskDialogs.ArtifactSaveLauncher artifactSaveLauncher,
                           TaskDialogsFactory taskDialogsFactory) {
        this(activity,store,worker,changed,qrLauncher,artifactSaveLauncher,taskDialogsFactory,
                AgentConnectionClient::probeResult);
    }

    AgentConnectionDialogs(Activity activity, AgentConnectionStore store,
                           ExecutorService worker, Runnable changed, QrLauncher qrLauncher,
                           AgentTaskDialogs.ArtifactSaveLauncher artifactSaveLauncher,
                           TaskDialogsFactory taskDialogsFactory, SavedProfileProbe savedProfileProbe) {
        this.activity = activity; this.store = store; this.worker = worker;
        this.changed = changed == null ? () -> { } : changed;
        this.qrLauncher = qrLauncher;
        this.artifactSaveLauncher = artifactSaveLauncher;
        this.taskDialogsFactory = taskDialogsFactory;
        this.savedProfileProbe=savedProfileProbe==null?AgentConnectionClient::probeResult:savedProfileProbe;
    }

    private AgentTaskDialogs taskDialogs() {
        AgentTaskDialogs dialogs=taskDialogsFactory==null?null:taskDialogsFactory.create();
        return dialogs==null?new AgentTaskDialogs(activity,store,worker,artifactSaveLauncher):dialogs;
    }

    void show() {
        ConnectionListState state=new ConnectionListState();
        String[] rows=connectionListRows(state);
        if(rows==null)return;
        state.adapter=ChoiceRows.mutableAdapter(activity,rows);
        AlertDialog dialog=ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_agent_title))
                .setAdapter(state.adapter, (window,which)->onConnectionListChoice(state,which))
                .setNegativeButton(activity.getString(R.string.flow_close), null).create();
        connectionListDialog=new WeakReference<>(dialog);connectionListState=state;
        dialog.setOnDismissListener(ignored->{
            if(connectionListDialog.get()==dialog){connectionListDialog.clear();connectionListState=null;}
        });
        dialog.show();
    }

    private String[] connectionListRows(ConnectionListState state) {
        final List<AgentConnectionStore.Config> profiles;
        try {profiles=store.listSummaries();}
        catch(RuntimeException error){
            error(activity.getString(R.string.flow_cannot_read_connections,message(error)));return null;
        }
        state.profileIds.clear();List<String> labels=new ArrayList<>();
        labels.add(activity.getString(R.string.flow_view_tasks));
        String defaultId=store.defaultId();
        for(AgentConnectionStore.Config profile:profiles){
            state.profileIds.add(profile.id);
            labels.add((profile.id.equals(defaultId)?"★ ":"")+AgentTaskDestinationLabel.forProfile(activity,profile)+
                    " · "+statusLabel(profile));
        }
        state.manualIndex=labels.size();labels.add(activity.getString(R.string.flow_manual_add));
        state.qrIndex=-1;
        if(qrLauncher!=null){state.qrIndex=labels.size();labels.add(activity.getString(R.string.flow_scan_pair));}
        state.pasteIndex=labels.size();labels.add(activity.getString(R.string.flow_paste_pair));
        state.guideIndex=labels.size();labels.add(activity.getString(R.string.flow_connect_other));
        return labels.toArray(new String[0]);
    }

    private void onConnectionListChoice(ConnectionListState state,int which) {
        if(which==0){taskDialogs().showList();return;}
        if(which>0&&which<=state.profileIds.size()){
            String profileId=state.profileIds.get(which-1);
            AgentConnectionStore.Config current=store.get(profileId);
            if(current==null){error(activity.getString(R.string.flow_connection_missing));return;}
            showActions(current);return;
        }
        if(which==state.manualIndex)showEditor(null);
        else if(which==state.qrIndex&&qrLauncher!=null)qrLauncher.launch();
        else if(which==state.pasteIndex)showPairingPaste();
        else if(which==state.guideIndex)AgentConnectionGuide.show(activity);
    }

    private void refreshConnectionListIfVisible() {
        AlertDialog dialog=connectionListDialog.get();ConnectionListState state=connectionListState;
        if(dialog==null||state==null||!dialog.isShowing())return;
        String[] rows=connectionListRows(state);
        if(rows!=null&&state.adapter!=null){
            state.adapter.clear();state.adapter.addAll(Arrays.asList(rows));state.adapter.notifyDataSetChanged();
        }
    }

    private static final class ConnectionListState {
        final List<String> profileIds=new ArrayList<>();
        ArrayAdapter<String> adapter;
        int manualIndex,pasteIndex,guideIndex,qrIndex=-1;
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
                "\n" + activity.getString(R.string.flow_connection_status_value, statusLabel(profile)), 14);
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
            } else taskDialogs().showNewText(profile);
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
        automaticProbeSetupFailures.remove(profile.id);
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
                .setCancelable(true).create();
        waiting.setOnCancelListener(dialog -> cancelled.set(true));
        waiting.show();
        worker.execute(() -> {
            try {
                String deviceId = AgentDeviceIdentity.id(activity);
                AgentPairingClient client = new AgentPairingClient();
                AgentPairingClient.Session session = client.request(payload, deviceId,
                        AgentDeviceIdentity.name());
                while (!cancelled.get() && System.currentTimeMillis() < session.expiresAtMillis) {
                    AgentPairingClient.Claim claim = client.claim(session, deviceId);
                    if (cancelled.get()) return;
                    if (claim.state == AgentPairingClient.ClaimState.PENDING) {
                        Thread.sleep(2500L); continue;
                    }
                    if (claim.state != AgentPairingClient.ClaimState.APPROVED) {
                        throw new IllegalStateException(activity.getString(claim.state == AgentPairingClient.ClaimState.DENIED
                                ? R.string.flow_pair_denied : R.string.flow_pair_expired));
                    }
                    List<AgentConnectionStore.Config> savedProfiles=new ArrayList<>();
                    List<String> failures = new ArrayList<>();
                    for (AgentPairingClient.ApprovedConnection item : claim.connections) {
                        if (item.kind == AgentConnectionStore.Kind.OPENCLAW) {
                            failures.add(item.name + "：" + activity.getString(R.string.flow_openclaw_task_unavailable)); continue;
                        }
                        try {
                            AgentConnectionStore.Config saved=store.addBridge(item.name, item.kind, payload.endpoint,
                                    payload.bridgeId, item.instanceId, payload.certSha256,
                                    item.token);
                            savedProfiles.add(saved);
                        } catch (Exception error) { failures.add(item.name + "：" + message(error)); }
                    }
                    String detail = String.join("\n", failures);
                    PreparedProbeBatch probeBatch=savedProfiles.isEmpty()?null:prepareAutomaticProbes(savedProfiles);
                    runOnUiIfAlive(() -> {
                        waiting.dismiss(); changed.run();
                        int messageId=savedProfiles.isEmpty()?R.string.flow_saved_agents:R.string.flow_pairing_saved_checking;
                        Toast.makeText(activity, activity.getString(messageId, savedProfiles.size()) +
                                (detail.isEmpty() ? "" : "\n" + activity.getString(R.string.flow_not_completed, detail)),
                                Toast.LENGTH_LONG).show(); show();
                    });
                    if(probeBatch!=null)launchAutomaticProbes(probeBatch);
                    return;
                }
                if (!cancelled.get()) throw new IllegalStateException(activity.getString(R.string.flow_pair_expired));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                if (!cancelled.get()) runOnUiIfAlive(() -> {
                    waiting.dismiss(); AgentConnectionDialogs.this.error(activity.getString(R.string.flow_pair_failed, message(error)));
                });
            } finally {
                if (cancelled.get()) runOnUiIfAlive(waiting::dismiss);
            }
        });
    }

    private String statusLabel(AgentConnectionStore.Config profile) {
        if(automaticProbeIds.contains(profile.id))return activity.getString(R.string.flow_probe_agent_checking_label);
        Long setupFailureRevision=automaticProbeSetupFailures.get(profile.id);
        if(setupFailureRevision!=null){
            if(setupFailureRevision==profile.revision&&!profile.verified()&&profile.probeFailure==null)
                return AgentConnectionStore.ProbeFailure.UNKNOWN.label(activity);
            automaticProbeSetupFailures.remove(profile.id,setupFailureRevision);
        }
        return profile.statusLabel(activity);
    }

    void scheduleAutomaticProbes(List<AgentConnectionStore.Config> profiles) {
        launchAutomaticProbes(prepareAutomaticProbes(profiles));
    }

    private PreparedProbeBatch prepareAutomaticProbes(List<AgentConnectionStore.Config> profiles) {
        ProbeBatch batch=new ProbeBatch(profiles.size());
        List<PendingProbe> pending=new ArrayList<>();
        for(AgentConnectionStore.Config profile:profiles) {
            if(!automaticProbeIds.add(profile.id)) {
                batch.record(new ProbeOutcome(ProbeState.STALE,null));
                continue;
            }
            automaticProbeSetupFailures.remove(profile.id);
            final String attemptId;
            try {attemptId=store.beginProbe(profile.id,profile.revision);}
            catch(RuntimeException error) {
                automaticProbeIds.remove(profile.id);
                if(profileStillCurrent(profile)){
                    recordProbeSetupFailure(profile);
                    batch.record(new ProbeOutcome(ProbeState.NEEDS_ATTENTION,AgentConnectionStore.ProbeFailure.UNKNOWN));
                } else batch.record(new ProbeOutcome(ProbeState.STALE,null));
                continue;
            }
            if(attemptId.isEmpty()) {
                automaticProbeIds.remove(profile.id);
                if(profileStillCurrent(profile)){
                    recordProbeSetupFailure(profile);
                    batch.record(new ProbeOutcome(ProbeState.NEEDS_ATTENTION,AgentConnectionStore.ProbeFailure.UNKNOWN));
                } else batch.record(new ProbeOutcome(ProbeState.STALE,null));
                continue;
            }
            pending.add(new PendingProbe(profile,attemptId));
        }
        runOnUiIfAlive(this::refreshConnectionListIfVisible);
        if(!pending.isEmpty())try {
            batch.executor=Executors.newFixedThreadPool(Math.min(3,pending.size()),r->{
                Thread thread=new Thread(r,"padnote-agent-readiness");thread.setDaemon(true);return thread;
            });
        } catch(RuntimeException unavailable) {
            for(PendingProbe item:pending) {
                try {store.applyProbeFailure(item.profile.id,item.profile.revision,item.attemptId,
                        AgentConnectionStore.ProbeFailure.UNKNOWN);} catch(RuntimeException ignored) { }
                automaticProbeIds.remove(item.profile.id);
                batch.record(new ProbeOutcome(ProbeState.NEEDS_ATTENTION,
                        AgentConnectionStore.ProbeFailure.UNKNOWN));
            }
            pending.clear();
        }
        return new PreparedProbeBatch(batch,pending);
    }

    private void launchAutomaticProbes(PreparedProbeBatch prepared) {
        ProbeBatch batch=prepared.batch;
        for(PendingProbe pending:prepared.pending) {
            AgentConnectionStore.Config profile=pending.profile;
            String attemptId=pending.attemptId;
            try {
                batch.executor.execute(()->{
                    ProbeOutcome outcome;
                    try {outcome=probeSavedProfile(store,profile,attemptId,savedProfileProbe);}
                    catch(RuntimeException storageOrProbeFailure) {
                        automaticProbeSetupFailures.put(profile.id,profile.revision);
                        outcome=new ProbeOutcome(ProbeState.NEEDS_ATTENTION,
                                AgentConnectionStore.ProbeFailure.UNKNOWN);
                    } finally {
                        automaticProbeIds.remove(profile.id);
                    }
                    if(outcome.persistenceFailure)
                        automaticProbeSetupFailures.put(profile.id,profile.revision);
                    try {completeProbeBatch(batch,outcome);} catch(RuntimeException ignored) { }
                    try {changedIfActivityAlive();} catch(RuntimeException ignored) { }
                });
            } catch(RuntimeException rejected) {
                AgentConnectionStore.ProbeFailure failure=AgentConnectionStore.ProbeFailure.UNKNOWN;
                boolean applied=false;
                try {applied=store.applyProbeFailure(profile.id,profile.revision,attemptId,failure);}
                catch(RuntimeException storageFailure) {automaticProbeSetupFailures.put(profile.id,profile.revision);}
                automaticProbeIds.remove(profile.id);
                try {changedIfActivityAlive();} catch(RuntimeException ignored) { }
                batch.record(applied?new ProbeOutcome(ProbeState.NEEDS_ATTENTION,failure)
                        :new ProbeOutcome(ProbeState.NEEDS_ATTENTION,failure));
            }
        }
        completeProbeBatch(batch,null);
    }

    private void changedIfActivityAlive() {
        runOnUiIfAlive(()->{
            try {refreshConnectionListIfVisible();} catch(RuntimeException ignored) { }
            try {changed.run();} catch(RuntimeException ignored) { }
        });
    }

    private void recordProbeSetupFailure(AgentConnectionStore.Config profile) {
        automaticProbeSetupFailures.put(profile.id,profile.revision);
        try {store.applyProbeFailure(profile.id,profile.revision);} catch(RuntimeException ignored) { }
    }

    private boolean profileStillCurrent(AgentConnectionStore.Config profile) {
        try {return profile.sameIdentityRevision(store.get(profile.id));}
        catch(RuntimeException ignored) {return true;}
    }

    private void completeProbeBatch(ProbeBatch batch,ProbeOutcome outcome) {
        if(outcome!=null)batch.record(outcome);
        if(!batch.isComplete()||!batch.reported.compareAndSet(false,true))return;
        if(batch.executor!=null)batch.executor.shutdown();
        runOnUiIfAlive(()->Toast.makeText(activity,activity.getString(
                R.string.flow_auto_probe_summary,batch.ready.get(),batch.attention.get(),batch.stale.get()),
                Toast.LENGTH_LONG).show());
    }

    private static final class PendingProbe {
        final AgentConnectionStore.Config profile;final String attemptId;
        PendingProbe(AgentConnectionStore.Config profile,String attemptId){this.profile=profile;this.attemptId=attemptId;}
    }

    private static final class PreparedProbeBatch {
        final ProbeBatch batch;final List<PendingProbe> pending;
        PreparedProbeBatch(ProbeBatch batch,List<PendingProbe> pending){this.batch=batch;this.pending=pending;}
    }

    private void runOnUiIfAlive(Runnable callback) {
        if(!mayDeliverProbeUi(activity))return;
        activity.runOnUiThread(()->{if(mayDeliverProbeUi(activity))callback.run();});
    }

    private static final class ProbeBatch {
        private final AtomicInteger remaining;
        final AtomicInteger ready=new AtomicInteger();
        final AtomicInteger attention=new AtomicInteger();
        final AtomicInteger stale=new AtomicInteger();
        final AtomicBoolean reported=new AtomicBoolean();
        ExecutorService executor;
        ProbeBatch(int count){remaining=new AtomicInteger(count);}
        void record(ProbeOutcome outcome){
            if(outcome.state==ProbeState.READY)ready.incrementAndGet();
            else if(outcome.state==ProbeState.NEEDS_ATTENTION)attention.incrementAndGet();
            else stale.incrementAndGet();
            remaining.decrementAndGet();
        }
        boolean isComplete(){return remaining.get()==0;}
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
