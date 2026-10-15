package com.padnote.android;

import android.content.Context;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Conservative, presentation-only capability summary. It never widens client eligibility. */
final class AgentCapabilitySummary {
    private enum TextId {
        RUN_SUBMISSION, RUN_STATUS, RUN_STOP, APPROVAL, BUNDLE, VIDEO_TASK,
        VIDEO_OPS, VIDEO_PRODUCTION, RUNTIME, ARTIFACTS, UNKNOWN, OLD_INFO,
        LEGACY_VERIFIED, LEGACY_SUPPORTED, UNKNOWN_MORE, REPORTED, UNREPORTED,
        RECENT_PASS, RECENT_FAIL, SUPPORTED, NOT_SUPPORTED, NEXT_STEP,
        HERMES_NEXT, HERMES_ENTRY, OPENCLAW_NEXT, VIDEO_NEXT_VERIFIED,
        VIDEO_NEXT_UNVERIFIED, UNKNOWN_KIND, FOOTER
    }

    private interface Copy { String get(TextId id); }

    private static final class Capability {
        final String key;
        final TextId label;
        Capability(String key, TextId label) { this.key = key; this.label = label; }
    }

    private static final List<Capability> KNOWN = Collections.unmodifiableList(Arrays.asList(
            new Capability("run_submission", TextId.RUN_SUBMISSION),
            new Capability("run_status", TextId.RUN_STATUS),
            new Capability("run_stop", TextId.RUN_STOP),
            new Capability("run_approval_response", TextId.APPROVAL),
            new Capability("task_bundle", TextId.BUNDLE),
            new Capability("video_task_submission", TextId.VIDEO_TASK),
            new Capability("video_operations", TextId.VIDEO_OPS),
            new Capability("video_production", TextId.VIDEO_PRODUCTION),
            new Capability("runtime_verified", TextId.RUNTIME),
            new Capability("artifacts", TextId.ARTIFACTS)
    ));

    private AgentCapabilitySummary() { }

    static String describe(Context context, AgentConnectionStore.Kind kind,
                           AgentConnectionStore.Transport transport, boolean verified,
                           List<String> capabilities, boolean featureMapAvailable,
                           Map<String, Boolean> featureMap) {
        return describe(kind, transport, verified, capabilities, featureMapAvailable, featureMap,
                id -> localized(context, id));
    }

    private static String localized(Context context, TextId id) {
        switch (id) {
            case RUN_SUBMISSION: return context.getString(R.string.flow_cap_run_submission);
            case RUN_STATUS: return context.getString(R.string.flow_cap_run_status);
            case RUN_STOP: return context.getString(R.string.flow_cap_run_stop);
            case APPROVAL: return context.getString(R.string.flow_cap_approval);
            case BUNDLE: return context.getString(R.string.flow_cap_bundle);
            case VIDEO_TASK: return context.getString(R.string.flow_cap_video_task);
            case VIDEO_OPS: return context.getString(R.string.flow_cap_video_ops);
            case VIDEO_PRODUCTION: return context.getString(R.string.flow_cap_video_prod);
            case RUNTIME: return context.getString(R.string.flow_cap_runtime);
            case ARTIFACTS: return context.getString(R.string.flow_cap_artifacts);
            case UNKNOWN: return context.getString(R.string.flow_cap_unknown);
            case OLD_INFO: return context.getString(R.string.flow_cap_old_info);
            case LEGACY_VERIFIED: return context.getString(R.string.flow_cap_legacy_verified);
            case LEGACY_SUPPORTED: return context.getString(R.string.flow_cap_legacy_supported);
            case UNKNOWN_MORE: return context.getString(R.string.flow_cap_unknown_more);
            case REPORTED: return context.getString(R.string.flow_cap_reported);
            case UNREPORTED: return context.getString(R.string.flow_cap_unreported);
            case RECENT_PASS: return context.getString(R.string.flow_cap_recent_pass);
            case RECENT_FAIL: return context.getString(R.string.flow_cap_recent_fail);
            case SUPPORTED: return context.getString(R.string.flow_cap_supported);
            case NOT_SUPPORTED: return context.getString(R.string.flow_cap_not_supported);
            case NEXT_STEP: return context.getString(R.string.flow_cap_next_step);
            case HERMES_NEXT: return context.getString(R.string.flow_cap_hermes_next);
            case HERMES_ENTRY: return context.getString(R.string.flow_cap_hermes_entry);
            case OPENCLAW_NEXT: return context.getString(R.string.flow_cap_openclaw_next);
            case VIDEO_NEXT_VERIFIED: return context.getString(R.string.flow_cap_video_next_verified);
            case VIDEO_NEXT_UNVERIFIED: return context.getString(R.string.flow_cap_video_next_unverified);
            case UNKNOWN_KIND: return context.getString(R.string.flow_cap_unknown_kind);
            default: return context.getString(R.string.flow_cap_footer);
        }
    }

    private static String chinese(TextId id) {
        switch (id) {
            case RUN_SUBMISSION: return "提交文字任务";
            case RUN_STATUS: return "查看任务进度";
            case RUN_STOP: return "请求停止任务";
            case APPROVAL: return "回复待处理审批";
            case BUNDLE: return "接收任务包";
            case VIDEO_TASK: return "接受视频任务";
            case VIDEO_OPS: return "执行视频工作流操作";
            case VIDEO_PRODUCTION: return "启动视频生产";
            case RUNTIME: return "电脑视频环境检查";
            case ARTIFACTS: return "读取任务产物";
            case UNKNOWN: return "尚未验证，能力状态未知。请先测试连接。";
            case OLD_INFO: return "这是旧版验证信息，完整能力状态尚未保存。";
            case LEGACY_VERIFIED: return "此前报告通过";
            case LEGACY_SUPPORTED: return "此前报告支持";
            case UNKNOWN_MORE: return "未列项目状态未知；重新测试可查看完整状态。";
            case REPORTED: return "电脑报告的功能状态：";
            case UNREPORTED: return "未报告";
            case RECENT_PASS: return "最近报告通过";
            case RECENT_FAIL: return "最近报告未通过";
            case SUPPORTED: return "已报告支持";
            case NOT_SUPPORTED: return "电脑明确报告不支持";
            case NEXT_STEP: return "下一步：";
            case HERMES_NEXT: return "在这条连接的操作菜单中选择“发送文本任务”。";
            case HERMES_ENTRY: return "只有已验证且电脑明确报告支持提交文字任务的 Hermes 连接，才会显示文本任务入口；请先测试连接。";
            case OPENCLAW_NEXT: return "连接身份可检查；当前版本不支持提交 OpenClaw 任务。可重新测试、编辑或删除连接。";
            case VIDEO_NEXT_VERIFIED: return "打开一份笔记，选择“视频任务与成果”→“生成讲解视频”；准备好材料后可发送到电脑或导出任务包。需先有已整理文本，未准备时界面会提示整理。此连接不提供文本任务入口。";
            case VIDEO_NEXT_UNVERIFIED: return "已有笔记可选择“视频任务与成果”→“生成讲解视频”，准备好材料后可发送到电脑或导出任务包。只有已验证且电脑报告支持任务包与视频任务的连接助手，才能作为发送目标；此连接不提供文本任务入口。";
            case UNKNOWN_KIND: return "连接类型未识别；不会开放任务提交入口。";
            default: return "最近一次检查只反映当时结果，不代表当前在线，也不能代替提交前的运行环境实时预检。视频任务接收不等于工作流或生产能力已确认。云端账号和费用由你按服务方流程确认；语音功能需另行明确批准。";
        }
    }

    static boolean canSubmitHermesText(AgentConnectionStore.Kind kind, boolean verified,
                                       List<String> capabilities) {
        return kind == AgentConnectionStore.Kind.HERMES && verified &&
                capabilities != null && capabilities.contains("run_submission");
    }

    static String describe(AgentConnectionStore.Kind kind,
                           AgentConnectionStore.Transport transport,
                           boolean verified, List<String> capabilities,
                           boolean featureMapAvailable, Map<String, Boolean> featureMap) {
        return describe(kind, transport, verified, capabilities, featureMapAvailable, featureMap,
                AgentCapabilitySummary::chinese);
    }

    private static String describe(AgentConnectionStore.Kind kind,
                                   AgentConnectionStore.Transport transport,
                                   boolean verified, List<String> capabilities,
                                   boolean featureMapAvailable, Map<String, Boolean> featureMap,
                                   Copy copy) {
        Set<String> enabled = new HashSet<>(capabilities == null
                ? Collections.emptyList() : capabilities);
        StringBuilder out = new StringBuilder();
        if (!verified) {
            out.append(copy.get(TextId.UNKNOWN)).append("\n");
        } else if (!featureMapAvailable) {
            out.append(copy.get(TextId.OLD_INFO)).append("\n");
            for (Capability capability : KNOWN) {
                if (enabled.contains(capability.key)) {
                    out.append("• ").append(copy.get(capability.label)).append("：")
                            .append("runtime_verified".equals(capability.key)
                                    ? copy.get(TextId.LEGACY_VERIFIED)
                                    : copy.get(TextId.LEGACY_SUPPORTED)).append("\n");
                }
            }
            out.append(copy.get(TextId.UNKNOWN_MORE)).append("\n");
        } else {
            out.append(copy.get(TextId.REPORTED)).append("\n");
            for (Capability capability : KNOWN) {
                Boolean reported = featureMap == null ? null : featureMap.get(capability.key);
                String state;
                if ("runtime_verified".equals(capability.key)) {
                    state = reported == null ? copy.get(TextId.UNREPORTED)
                            : reported ? copy.get(TextId.RECENT_PASS) : copy.get(TextId.RECENT_FAIL);
                } else {
                    state = reported == null ? copy.get(TextId.UNREPORTED)
                            : reported ? copy.get(TextId.SUPPORTED) : copy.get(TextId.NOT_SUPPORTED);
                }
                out.append("• ").append(copy.get(capability.label)).append("：")
                        .append(state).append("\n");
            }
        }
        if (kind == AgentConnectionStore.Kind.HERMES) {
            if (canSubmitHermesText(kind, verified, capabilities)) {
                out.append("\n").append(copy.get(TextId.NEXT_STEP))
                        .append(copy.get(TextId.HERMES_NEXT));
            } else {
                out.append("\n").append(copy.get(TextId.NEXT_STEP))
                        .append(copy.get(TextId.HERMES_ENTRY));
            }
        } else if (kind == AgentConnectionStore.Kind.OPENCLAW) {
            out.append("\n").append(copy.get(TextId.OPENCLAW_NEXT));
        } else if (kind == AgentConnectionStore.Kind.BUILTIN_VIDEO) {
            TextId next = verified && transport == AgentConnectionStore.Transport.BRIDGE &&
                    enabled.contains("task_bundle") && enabled.contains("video_task_submission")
                    ? TextId.VIDEO_NEXT_VERIFIED : TextId.VIDEO_NEXT_UNVERIFIED;
            out.append("\n").append(copy.get(TextId.NEXT_STEP)).append(copy.get(next));
        } else {
            out.append("\n").append(copy.get(TextId.UNKNOWN_KIND));
        }
        out.append("\n\n").append(copy.get(TextId.FOOTER));
        return out.toString();
    }
}
