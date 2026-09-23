package com.padnote.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * MiniMax M-series embeds its reasoning as an inline &lt;think&gt; block inside
 * the message content. The card must never show it, but the conversation
 * history must keep it (echoing back preserves multi-round tool quality), so
 * the split has to be exact.
 */
public class VisibleTextTest {

    @Test
    public void removesClosedThinkBlock() {
        assertEquals("已写入笔记。",
                OpenAiCompatibleClient.visibleText(
                        "<think>用户想要写入。\n</think>\n\n已写入笔记。"));
    }

    @Test
    public void keepsProseWithoutThinkBlocks() {
        String prose = "这是对圈选内容的讲解。";
        assertEquals(prose, OpenAiCompatibleClient.visibleText(prose));
    }

    @Test
    public void dropsUnclosedTruncatedBlock() {
        // Truncation mid-think: none of the tail was meant to be visible.
        assertEquals("", OpenAiCompatibleClient.visibleText(
                "<think>推理到一半被截断，还没有结束"));
    }

    @Test
    public void handlesMultipleBlocks() {
        assertEquals("前后正文",
                OpenAiCompatibleClient.visibleText("<think>一</think>前后<think>二</think>正文"));
    }

    @Test
    public void toleratesNullAndEmpty() {
        assertEquals("", OpenAiCompatibleClient.visibleText(null));
        assertEquals("", OpenAiCompatibleClient.visibleText(""));
    }

    @Test
    public void doesNotTouchLookalikeText() {
        String prose = "标签 <thinker> 是普通文本的一部分。";
        assertEquals(prose, OpenAiCompatibleClient.visibleText(prose));
    }
}
