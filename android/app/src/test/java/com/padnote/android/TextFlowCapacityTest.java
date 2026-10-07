package com.padnote.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Boundary contract for text length, renderer bytes and page completeness. */
public final class TextFlowCapacityTest {
    @Test
    public void exactUtf16LimitAndPageLimitAreAcceptedOnlyWhenComplete() {
        String atLimit = repeat('a', TextFlow.MAX_SOURCE_LENGTH);
        assertEquals(TextFlowCapacity.Failure.NONE, TextFlowCapacity.checkSource(atLimit));
        assertEquals(TextFlowCapacity.Failure.NONE,
                TextFlowCapacity.checkLayout(atLimit, 500, true));
        assertEquals(TextFlowCapacity.Failure.PAGE_LIMIT,
                TextFlowCapacity.checkLayout(atLimit, 500, false));
        assertEquals(TextFlowCapacity.Failure.PAGE_LIMIT,
                TextFlowCapacity.checkLayout(atLimit, 501, true));
    }

    @Test
    public void oneUnitOverSourceLimitIsRejectedBeforeLayout() {
        String overLimit = repeat('a', TextFlow.MAX_SOURCE_LENGTH + 1);
        assertEquals(TextFlowCapacity.Failure.SOURCE_TOO_LONG,
                TextFlowCapacity.checkSource(overLimit));
        assertEquals(TextFlowCapacity.Failure.SOURCE_TOO_LONG,
                TextFlowCapacity.checkLayout(overLimit, 1, true));
    }

    @Test
    public void utf16LimitAndUtf8BudgetAreMeasuredAsDifferentUnits() {
        String astralAtLimit = repeat("🚀", TextFlow.MAX_SOURCE_LENGTH / 2);
        assertEquals(TextFlow.MAX_SOURCE_LENGTH, astralAtLimit.length());
        assertEquals(TextFlowCapacity.Failure.NONE,
                TextFlowCapacity.checkSource(astralAtLimit));
    }

    @Test
    public void explicitUtf8RendererCapHasStableMessage() {
        assertEquals("回答超过本机文字渲染容量，未写入笔记。可从 AI 结果卡复制后分段插入；若对话提示未保存，请先复制完整内容。",
                TextFlowCapacity.message(TextFlowCapacity.Failure.RENDER_SOURCE_TOO_LARGE));
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
