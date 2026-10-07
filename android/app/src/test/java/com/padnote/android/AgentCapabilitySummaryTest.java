package com.padnote.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public final class AgentCapabilitySummaryTest {
    @Test public void onlyVerifiedHermesWithExactSubmissionCapabilityCanUseTextAction() {
        assertTrue(AgentCapabilitySummary.canSubmitHermesText(AgentConnectionStore.Kind.HERMES,
                true, Collections.singletonList("run_submission")));
        assertFalse(AgentCapabilitySummary.canSubmitHermesText(AgentConnectionStore.Kind.HERMES,
                false, Collections.singletonList("run_submission")));
        assertFalse(AgentCapabilitySummary.canSubmitHermesText(AgentConnectionStore.Kind.HERMES,
                true, Collections.emptyList()));
        assertFalse(AgentCapabilitySummary.canSubmitHermesText(AgentConnectionStore.Kind.HERMES,
                true, Collections.singletonList("run_submission_extra")));
    }

    @Test public void openClawAndBuiltinVideoNeverBecomeGenericTextTargets() {
        assertFalse(AgentCapabilitySummary.canSubmitHermesText(AgentConnectionStore.Kind.OPENCLAW,
                true, Collections.singletonList("run_submission")));
        assertFalse(AgentCapabilitySummary.canSubmitHermesText(AgentConnectionStore.Kind.BUILTIN_VIDEO,
                true, Collections.singletonList("run_submission")));
    }

    @Test public void detailExplainsOpenClawAndBuiltinVideoExistingPaths() {
        String hermes = AgentCapabilitySummary.describe(AgentConnectionStore.Kind.HERMES,
                AgentConnectionStore.Transport.DIRECT, true,
                Collections.singletonList("run_submission"), true,
                features("run_submission", true));
        assertTrue(hermes.contains("提交文字任务"));
        assertTrue(hermes.contains("在这条连接的操作菜单中选择“发送文本任务”"));
        assertFalse(hermes.contains("查看电脑任务”发送"));

        String openClaw = AgentCapabilitySummary.describe(AgentConnectionStore.Kind.OPENCLAW,
                AgentConnectionStore.Transport.BRIDGE, true,
                Collections.singletonList("run_submission"), true,
                features("run_submission", true));
        assertTrue(openClaw.contains("当前版本不支持提交 OpenClaw 任务"));
        assertFalse(openClaw.contains("发送文本任务”"));

        String video = AgentCapabilitySummary.describe(AgentConnectionStore.Kind.BUILTIN_VIDEO,
                AgentConnectionStore.Transport.BRIDGE, true,
                Arrays.asList("task_bundle", "video_task_submission"), true,
                features("task_bundle", true, "video_task_submission", true));
        assertTrue(video.contains("“视频任务与成果”→“生成讲解视频”"));
        assertTrue(video.contains("发送到电脑或导出任务包"));
        assertTrue(video.contains("需先有已整理文本"));
        assertTrue(video.contains("此连接不提供文本任务入口"));
        assertFalse(video.contains("创建视频任务入口"));
    }

    @Test public void unknownCapabilityDoesNotUnlockActionsOrBecomeAClaim() {
        String summary = AgentCapabilitySummary.describe(AgentConnectionStore.Kind.HERMES,
                AgentConnectionStore.Transport.DIRECT, true,
                Collections.singletonList("future_magic_execution"), true,
                features("future_magic_execution", true));
        assertFalse(AgentCapabilitySummary.canSubmitHermesText(AgentConnectionStore.Kind.HERMES,
                true, Collections.singletonList("future_magic_execution")));
        assertFalse(summary.contains("future_magic_execution"));
        assertTrue(summary.contains("提交文字任务：未报告"));
    }

    @Test public void absentCapabilityIsNotLabeledUnsupportedAndBoundariesAreDistinct() {
        String summary = AgentCapabilitySummary.describe(AgentConnectionStore.Kind.BUILTIN_VIDEO,
                AgentConnectionStore.Transport.BRIDGE, true,
                Collections.singletonList("video_task_submission"), true,
                features("video_task_submission", true, "video_operations", false,
                        "runtime_verified", false));
        assertTrue(summary.contains("电脑明确报告不支持"));
        assertTrue(summary.contains("电脑视频环境检查：最近报告未通过"));
        assertTrue(summary.contains("最近一次检查只反映当时结果，不代表当前在线"));
        assertTrue(summary.contains("不能代替提交前的运行环境实时预检"));
        assertTrue(summary.contains("云端账号和费用由你按服务方流程确认；语音功能需另行明确批准"));
        assertFalse(summary.contains("run_submission"));
        assertFalse(summary.contains("task_bundle"));
        assertFalse(summary.contains("video_task_submission"));
    }

    @Test public void legacyListShowsUnknownInsteadOfInventingFalseStates() {
        String summary = AgentCapabilitySummary.describe(AgentConnectionStore.Kind.HERMES,
                AgentConnectionStore.Transport.DIRECT, true,
                Collections.singletonList("run_submission"), false, Collections.emptyMap());
        assertTrue(summary.contains("旧版验证信息"));
        assertTrue(summary.contains("提交文字任务：此前报告支持"));
        assertTrue(summary.contains("未列项目状态未知"));
        assertFalse(summary.contains("电脑明确报告不支持"));
    }

    @Test public void fullMapDistinguishesExplicitFalseFromMissingAndKeepsActionGateExact() {
        Map<String, Boolean> map = features("run_submission", false,
                "run_status", true, "runtime_verified", true);
        String summary = AgentCapabilitySummary.describe(AgentConnectionStore.Kind.HERMES,
                AgentConnectionStore.Transport.DIRECT, true,
                Collections.emptyList(), true, map);
        assertTrue(summary.contains("提交文字任务：电脑明确报告不支持"));
        assertTrue(summary.contains("查看任务进度：已报告支持"));
        assertTrue(summary.contains("回复待处理审批：未报告"));
        assertTrue(summary.contains("电脑视频环境检查：最近报告通过"));
        assertFalse(AgentCapabilitySummary.canSubmitHermesText(AgentConnectionStore.Kind.HERMES,
                true, Collections.emptyList()));
    }

    @Test public void unverifiedHidesStaleMapClaims() {
        String summary = AgentCapabilitySummary.describe(AgentConnectionStore.Kind.HERMES,
                AgentConnectionStore.Transport.DIRECT, false,
                Collections.singletonList("run_submission"), true,
                features("run_submission", true));
        assertTrue(summary.contains("尚未验证，能力状态未知"));
        assertFalse(summary.contains("提交文字任务：已报告支持"));
    }

    private static Map<String, Boolean> features(Object... pairs) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] instanceof Boolean) {
                result.put(String.valueOf(pairs[i]), (Boolean) pairs[i + 1]);
            }
        }
        return result;
    }
}
