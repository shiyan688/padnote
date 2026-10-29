package com.padnote.android;

import java.nio.charset.StandardCharsets;

/** One fail-closed policy for accepting a complete text flow into a note. */
final class TextFlowCapacity {
    static final int MAX_RENDER_SOURCE_BYTES = 512 * 1024;
    static final int MAX_PAGES = 500;

    enum Failure {
        NONE,
        SOURCE_TOO_LONG,
        RENDER_SOURCE_TOO_LARGE,
        PAGE_LIMIT,
        UNPLACEABLE_BLOCK,
        UNSUPPORTED_TABLE,
        TABLE_EXPANSION_LIMIT
    }

    static final class Rejection extends IllegalArgumentException {
        final Failure failure;

        Rejection(Failure failure) {
            super(message(failure));
            this.failure = failure;
        }

        Rejection(Failure failure, String localizedMessage) {
            super(localizedMessage == null || localizedMessage.trim().isEmpty()
                    ? message(failure) : localizedMessage);
            this.failure = failure;
        }
    }

    private TextFlowCapacity() {}

    static Failure checkSource(String source) {
        String value = source == null ? "" : source;
        if (value.length() > TextFlow.MAX_SOURCE_LENGTH) return Failure.SOURCE_TOO_LONG;
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_RENDER_SOURCE_BYTES) {
            return Failure.RENDER_SOURCE_TOO_LARGE;
        }
        return Failure.NONE;
    }

    static Failure checkLayout(String source, int lastPageCount, boolean complete) {
        return checkLayout(source, lastPageCount, complete, TextFlow.LayoutFailureReason.NONE);
    }

    static Failure checkLayout(String source, int lastPageCount, boolean complete,
                               TextFlow.LayoutFailureReason layoutFailure) {
        Failure sourceFailure = checkSource(source);
        if (sourceFailure != Failure.NONE) return sourceFailure;
        if (lastPageCount > MAX_PAGES) return Failure.PAGE_LIMIT;
        if (!complete) {
            if (layoutFailure == TextFlow.LayoutFailureReason.UNPLACEABLE_BLOCK) {
                return Failure.UNPLACEABLE_BLOCK;
            }
            if (layoutFailure == TextFlow.LayoutFailureReason.UNSUPPORTED_TABLE) {
                return Failure.UNSUPPORTED_TABLE;
            }
            if (layoutFailure == TextFlow.LayoutFailureReason.TABLE_EXPANSION_LIMIT) {
                return Failure.TABLE_EXPANSION_LIMIT;
            }
            return Failure.PAGE_LIMIT;
        }
        return Failure.NONE;
    }

    static String message(Failure failure) {
        switch (failure) {
            case SOURCE_TOO_LONG:
                return "回答超过单条文字流的 100,000 字符上限，未写入笔记。可从 AI 结果卡复制后分段插入；若对话提示未保存，请先复制完整内容。";
            case RENDER_SOURCE_TOO_LARGE:
                return "回答超过本机文字渲染容量，未写入笔记。可从 AI 结果卡复制后分段插入；若对话提示未保存，请先复制完整内容。";
            case PAGE_LIMIT:
                return "回答无法在 500 页内完整排版，未写入笔记。可从 AI 结果卡复制后分段插入。";
            case UNPLACEABLE_BLOCK:
                return "部分内容无法安全排版，未写入笔记。原文仍保留在 AI 结果卡中。";
            case UNSUPPORTED_TABLE:
                return "表格无法按当前规则安全分页，未写入笔记。原文仍保留在 AI 结果卡中。";
            case TABLE_EXPANSION_LIMIT:
                return "表格超过安全分页处理上限，未写入笔记。原文仍保留在 AI 结果卡中。";
            default:
                return "";
        }
    }
}
