package com.padnote.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
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
        catch (RuntimeException error) { error("无法读取连接：" + message(error)); return; }
        List<String> labels = new ArrayList<>();
        labels.add("查看电脑任务");
        String defaultId = store.defaultId();
        for (AgentConnectionStore.Config profile : profiles) {
            labels.add((profile.id.equals(defaultId) ? "★ " : "") + profile.name + "\n" +
                    kind(profile) + " · " + profile.statusLabel());
        }
        int manualIndex = labels.size();
        labels.add("＋ 手动添加连接");
        int qrIndex = -1;
        if (qrLauncher != null) {
            qrIndex = labels.size();
            labels.add("扫码添加连接");
        }
        int pasteIndex = labels.size();
        labels.add("粘贴连接助手配对内容");
        int guideIndex = labels.size();
        labels.add("如何连接另一台电脑？");
        final int finalQrIndex = qrIndex;
        new AlertDialog.Builder(activity).setTitle("电脑 Agent")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) new AgentTaskDialogs(activity, store, worker,
                            artifactSaveLauncher).showList();
                    else if (which <= profiles.size()) showActions(profiles.get(which - 1));
                    else if (which == manualIndex) showEditor(null);
                    else if (which == finalQrIndex) qrLauncher.launch();
                    else if (which == pasteIndex) showPairingPaste();
                    else if (which == guideIndex) AgentConnectionGuide.show(activity);
                })
                .setNegativeButton("关闭", null).show();
    }

    private void showActions(AgentConnectionStore.Config summary) {
        AgentConnectionStore.Config profile = store.get(summary.id);
        if (profile == null) { error("连接已不存在"); return; }
        boolean isDefault = profile.id.equals(store.defaultId());
        String[] actions = isDefault
                ? new String[]{"发送文本任务", "测试连接", "编辑", "删除"}
                : new String[]{"设为默认", "发送文本任务", "测试连接", "编辑", "删除"};
        new AlertDialog.Builder(activity).setTitle(profile.name + " · " + profile.statusLabel())
                .setItems(actions, (dialog, which) -> {
                    String action = actions[which];
                    if ("设为默认".equals(action)) {
                        if (!store.setDefault(profile.id)) error("无法设置默认连接");
                        else { changed.run(); show(); }
                    } else if ("发送文本任务".equals(action)) {
                        if (!profile.verified()) error("请先测试并验证这条连接");
                        else new AgentTaskDialogs(activity, store, worker,
                                artifactSaveLauncher).showNewText(profile);
                    } else if ("测试连接".equals(action)) test(profile);
                    else if ("编辑".equals(action)) showEditor(profile);
                    else confirmDelete(profile);
                }).setNegativeButton("返回", (dialog, which) -> show()).show();
    }

    private void showEditor(AgentConnectionStore.Config existing) {
        LinearLayout body = panel();
        EditText name = field("名称，例如：家用电脑 Hermes", false);
        Spinner kind = spinner(new String[]{"Hermes", "OpenClaw（仅 Bridge 检测）"});
        Spinner transport = spinner(new String[]{"直接 HTTPS", "电脑连接助手 Bridge"});
        EditText endpoint = field("https://computer.example", false);
        EditText token = field(existing == null ? "连接令牌" : "留空保持原令牌", true);
        if (existing != null) {
            name.setText(existing.name); endpoint.setText(existing.endpoint);
            kind.setSelection(existing.kind == AgentConnectionStore.Kind.OPENCLAW ? 1 : 0);
            transport.setSelection(existing.transport == AgentConnectionStore.Transport.BRIDGE ? 1 : 0);
            if (existing.transport == AgentConnectionStore.Transport.BRIDGE) {
                endpoint.setEnabled(false); kind.setEnabled(false); transport.setEnabled(false);
            }
        }
        addLabeled(body, "名称", name); addLabeled(body, "协议", kind);
        addLabeled(body, "连接方式", transport); addLabeled(body, "HTTPS 地址", endpoint);
        addLabeled(body, "令牌", token);
        TextView note = text(existing != null && existing.transport == AgentConnectionStore.Transport.BRIDGE
                ? "Bridge 的地址、协议和实例身份需重新配对后更换；此处只可改名称或令牌。"
                : "修改地址、协议或令牌会产生新的连接修订并清除原验证状态。", 12);
        body.addView(note);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(existing == null ? "添加 Agent" : "编辑 Agent")
                .setView(scroll(body)).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
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
                                throw new IllegalArgumentException("Bridge 连接请使用配对内容添加");
                            }
                            store.add(name.getText().toString(), selectedKind, selectedTransport,
                                    endpoint.getText().toString(), secret);
                        } else {
                            store.update(existing.id, existing.revision, name.getText().toString(),
                                    selectedKind, selectedTransport, endpoint.getText().toString(),
                                    secret);
                        }
                        dialog.dismiss(); changed.run(); show();
                    } catch (Exception error) { note.setText("保存失败：" + message(error)); }
                }));
        dialog.show();
    }

    private void test(AgentConnectionStore.Config profile) {
        AlertDialog progress = new AlertDialog.Builder(activity).setTitle("测试 " + profile.name)
                .setMessage("正在检查协议、实例身份和能力…").setCancelable(false).create();
        progress.show();
        worker.execute(() -> {
            try {
                AgentConnectionClient.ProbeResult result = AgentConnectionClient.probeResult(profile);
                boolean applied = store.applyProbeSuccess(profile.id, profile.revision, result);
                activity.runOnUiThread(() -> {
                    progress.dismiss(); changed.run();
                    Toast.makeText(activity, applied ? result.message : "连接已被修改，本次结果已忽略",
                            Toast.LENGTH_LONG).show(); show();
                });
            } catch (Exception error) {
                boolean applied = store.applyProbeFailure(profile.id, profile.revision);
                activity.runOnUiThread(() -> {
                    progress.dismiss(); changed.run();
                    Toast.makeText(activity, applied ? "测试失败：" + message(error) :
                            "连接已被修改，本次失败结果已忽略", Toast.LENGTH_LONG).show(); show();
                });
            }
        });
    }

    private void confirmDelete(AgentConnectionStore.Config profile) {
        new AlertDialog.Builder(activity).setTitle("删除连接？")
                .setMessage("将删除“" + profile.name + "”在本机保存的令牌。已有任务记录会保留，且不会声称已停止电脑上的任务。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    if (!store.delete(profile.id)) error("连接删除失败");
                    changed.run(); show();
                }).show();
    }

    private void showPairingPaste() {
        LinearLayout body = panel();
        EditText payload = field("粘贴系统相机/电脑显示的 padnote-pair JSON", false);
        payload.setSingleLine(false); payload.setMinLines(6);
        body.addView(payload);
        TextView status = text("内容只会先解析并显示电脑地址；点击申请后仍需在电脑上明确批准。", 12);
        body.addView(status);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("连接助手配对")
                .setView(scroll(body)).setNegativeButton("取消", null)
                .setPositiveButton("检查并申请", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    final AgentPairingClient.PairingPayload parsed;
                    try { parsed = AgentPairingClient.parse(payload.getText().toString()); }
                    catch (Exception error) { status.setText("无法解析：" + message(error)); return; }
                    new AlertDialog.Builder(activity).setTitle("向这台电脑申请连接？")
                            .setMessage("地址：" + parsed.endpoint + "\n电脑标识：" + parsed.bridgeId +
                                    "\n\n批准前不会取得任何 Agent 令牌。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("申请", (confirm, which) -> {
                                dialog.dismiss(); beginPairing(parsed);
                            }).show();
                }));
        dialog.show();
    }

    void showPairingPayload(String raw) {
        final AgentPairingClient.PairingPayload parsed;
        try { parsed = AgentPairingClient.parse(raw); }
        catch (Exception error) { error("二维码内容无效：" + message(error)); return; }
        new AlertDialog.Builder(activity).setTitle("向这台电脑申请连接？")
                .setMessage("地址：" + parsed.endpoint + "\n电脑标识：" + parsed.bridgeId +
                        "\n\n批准前不会取得任何 Agent 令牌。")
                .setNegativeButton("取消", null)
                .setPositiveButton("申请", (dialog, which) -> beginPairing(parsed)).show();
    }

    private void beginPairing(AgentPairingClient.PairingPayload payload) {
        AtomicBoolean cancelled = new AtomicBoolean();
        AlertDialog waiting = new AlertDialog.Builder(activity).setTitle("等待电脑批准")
                .setMessage("请在电脑连接助手中核对设备和 Agent 后批准。")
                .setNegativeButton("取消", (dialog, which) -> cancelled.set(true))
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
                        throw new IllegalStateException(claim.state == AgentPairingClient.ClaimState.DENIED
                                ? "电脑已拒绝本次配对" : "配对已过期，请重新生成配对码");
                    }
                    int saved = 0; List<String> failures = new ArrayList<>();
                    for (AgentPairingClient.ApprovedConnection item : claim.connections) {
                        if (item.kind == AgentConnectionStore.Kind.OPENCLAW) {
                            failures.add(item.name + "：当前版本尚不能执行 OpenClaw 任务"); continue;
                        }
                        try {
                            store.addBridge(item.name, item.kind, payload.endpoint,
                                    payload.bridgeId, item.instanceId, item.token); saved++;
                        } catch (Exception error) { failures.add(item.name + "：" + message(error)); }
                    }
                    int completed = saved; String detail = String.join("\n", failures);
                    activity.runOnUiThread(() -> {
                        waiting.dismiss(); changed.run();
                        Toast.makeText(activity, "已保存 " + completed + " 个 Agent" +
                                (detail.isEmpty() ? "" : "\n未完成：\n" + detail),
                                Toast.LENGTH_LONG).show(); show();
                    });
                    return;
                }
                if (!cancelled.get()) throw new IllegalStateException("配对已过期，请重新生成配对码");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                if (!cancelled.get()) activity.runOnUiThread(() -> {
                    waiting.dismiss(); AgentConnectionDialogs.this.error("配对失败：" + message(error));
                });
            } finally {
                if (cancelled.get()) activity.runOnUiThread(waiting::dismiss);
            }
        });
    }

    private static String kind(AgentConnectionStore.Config profile) {
        return (profile.kind == AgentConnectionStore.Kind.HERMES ? "Hermes" : "OpenClaw") +
                (profile.transport == AgentConnectionStore.Transport.BRIDGE ? " · Bridge" : " · 直连");
    }
    private LinearLayout panel() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(20), dp(8), dp(20), dp(8)); return v; }
    private ScrollView scroll(View child) { ScrollView s = new ScrollView(activity); s.addView(child); return s; }
    private Spinner spinner(String[] values) { Spinner s = new Spinner(activity); ArrayAdapter<String> a = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, values); a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(a); return s; }
    private EditText field(String hint, boolean secret) { EditText f = new EditText(activity); f.setHint(hint); f.setSingleLine(true); f.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0)); return f; }
    private void addLabeled(LinearLayout body, String label, View field) { TextView t = text(label, 12); t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); body.addView(t); body.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); }
    private TextView text(String value, int size) { TextView t = new TextView(activity); t.setText(value); t.setTextSize(size); t.setPadding(0, dp(6), 0, dp(6)); return t; }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private void error(String value) { new AlertDialog.Builder(activity).setTitle("电脑 Agent").setMessage(value).setPositiveButton("好", null).show(); }
    private static String message(Throwable error) { String m = error.getMessage(); return m == null || m.trim().isEmpty() ? "未知错误" : m; }
}
