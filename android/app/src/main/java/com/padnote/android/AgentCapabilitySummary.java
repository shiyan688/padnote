package com.padnote.android;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Conservative, presentation-only capability summary. It never widens client eligibility. */
final class AgentCapabilitySummary {
    private static final class Capability {
        final String key;
        final String label;
        Capability(String key, String label) { this.key = key; this.label = label; }
    }

    private static final List<Capability> KNOWN = Collections.unmodifiableList(Arrays.asList(
            new Capability("run_submission", "提交文字任务"),
            new Capability("run_status", "查看任务进度"),
            new Capability("run_stop", "请求停止任务"),
            new Capability("run_approval_response", "回复待处理审批"),
            new Capability("task_bundle", "接收任务包"),
            new Capability("video_task_submission", "接受视频任务"),
            new Capability("video_operations", "执行视频工作流操作"),
            new Capability("video_production", "启动视频生产"),
            new Capability("runtime_verified", "电脑视频环境检查"),
            new Capability("artifacts", "读取任务产物")
    ));

    private AgentCapabilitySummary() { }

    static boolean canSubmitHermesText(AgentConnectionStore.Kind kind, boolean verified,
                                       List<String> capabilities) {
        return kind == AgentConnectionStore.Kind.HERMES && verified &&
                capabilities != null && capabilities.contains("run_submission");
    }

    static String describe(AgentConnectionStore.Kind kind,
                           AgentConnectionStore.Transport transport,
                           boolean verified, List<String> capabilities,
                           boolean featureMapAvailable, Map<String, Boolean> featureMap) {
        Set<String> enabled = new HashSet<>(capabilities == null
                ? Collections.emptyList() : capabilities);
        StringBuilder out = new StringBuilder();
        if (!verified) {
            out.append("尚未验证，能力状态未知。请先测试连接。\n");
        } else if (!featureMapAvailable) {
            out.append("这是旧版验证信息，完整能力状态尚未保存。\n");
            for (Capability capability : KNOWN) {
                if (enabled.contains(capability.key)) {
                    out.append("• ").append(capability.label).append("：")
                            .append("runtime_verified".equals(capability.key)
                                    ? "此前报告通过" : "此前报告支持").append("\n");
                }
            }
            out.append("未列项目状态未知；重新测试可查看完整状态。\n");
        } else {
            out.append("电脑报告的功能状态：\n");
            for (Capability capability : KNOWN) {
                Boolean reported = featureMap == null ? null : featureMap.get(capability.key);
                String state;
                if ("runtime_verified".equals(capability.key)) {
                    state = reported == null ? "未报告" : reported ? "最近报告通过" : "最近报告未通过";
                } else {
                    state = reported == null ? "未报告" : reported ? "已报告支持" : "电脑明确报告不支持";
                }
                out.append("• ").append(capability.label).append("：").append(state).append("\n");
            }
        }

        if (kind == AgentConnectionStore.Kind.HERMES) {
            if (canSubmitHermesText(kind, verified, capabilities)) {
                out.append("\n下一步：在这条连接的操作菜单中选择“发送文本任务”。");
            } else {
                out.append("\n下一步：只有已验证且电脑明确报告支持提交文字任务的 Hermes 连接，才会显示文本任务入口；请先测试连接。");
            }
        } else if (kind == AgentConnectionStore.Kind.OPENCLAW) {
            out.append("\n连接身份可检查；当前版本不支持提交 OpenClaw 任务。可重新测试、编辑或删除连接。");
        } else if (kind == AgentConnectionStore.Kind.BUILTIN_VIDEO) {
            if (verified && transport == AgentConnectionStore.Transport.BRIDGE &&
                    enabled.contains("task_bundle") && enabled.contains("video_task_submission")) {
                out.append("\n下一步：打开一份笔记，选择“视频任务与成果”→“生成讲解视频”；准备好材料后可发送到电脑或导出任务包。需先有已整理文本，未准备时界面会提示整理。此连接不提供文本任务入口。");
            } else {
                out.append("\n下一步：已有笔记可选择“视频任务与成果”→“生成讲解视频”，准备好材料后可发送到电脑或导出任务包。只有已验证且电脑报告支持任务包与视频任务的连接助手，才能作为发送目标；此连接不提供文本任务入口。");
            }
        } else {
            out.append("\n连接类型未识别；不会开放任务提交入口。");
        }

        out.append("\n\n最近一次检查只反映当时结果，不代表当前在线，也不能代替提交前的运行环境实时预检。视频任务接收不等于工作流或生产能力已确认。云端账号和费用由你按服务方流程确认；语音功能需另行明确批准。");
        return out.toString();
    }
}
