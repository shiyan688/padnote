package com.padnote.android;

import static org.junit.Assert.*;
import org.junit.Test;

public class DiagramRenderingTest {
    @Test public void diagramLoadsOfflineRendererAndFitsViewport() {
        String html = CompiledTextWebView.buildHtml(NoteTextBox.Format.MARKDOWN,
                "```mermaid\nflowchart LR\nA[输入] --> B[输出]\n```", 16f, 1.35f);
        assertTrue(html.contains("class=\"mermaid\""));
        assertTrue(html.contains("../mermaid/mermaid.min.js"));
        assertTrue(html.contains("securityLevel:'strict'"));
        assertTrue(html.contains("preserveAspectRatio"));
        assertTrue(html.contains("示意图语法有误"));
        assertTrue(html.contains("padding:75% 0 0"));
    }

    @Test public void ordinaryTextDoesNotLoadMermaid() {
        String html = CompiledTextWebView.buildHtml(NoteTextBox.Format.MARKDOWN,
                "```java\nSystem.out.println(1);\n```", 16f, 1.35f);
        assertFalse(html.contains("mermaid.min.js"));
        assertTrue(html.contains("<pre><code>"));
    }

    @Test public void diagramSourceIsEscapedBeforeRendering() {
        String html = CompiledTextWebView.buildHtml(NoteTextBox.Format.MARKDOWN,
                "```mermaid\nflowchart LR\nA[<script>alert(1)</script>]\n```", 16f, 1.35f);
        assertFalse(html.contains("<script>alert(1)</script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }
}
