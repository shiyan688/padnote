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
        assertFalse(html.contains("padding:75% 0 0"));
        assertTrue(html.contains("width:auto;height:auto"));
        assertTrue(html.contains("naturalWidth"));
        assertTrue(html.contains("widthScale=Math.min(1"));
        assertTrue(html.contains("diagramScale=Math.min(widthScale"));
        assertTrue(html.contains("maxHeight=Math.max(72"));
        assertFalse(html.contains("pre.mermaid svg{display:block;width:100%"));
    }

    @Test public void longDisplayMathWrapsBetweenKatexBasesThenFitsAtomicBases() {
        String html = CompiledTextWebView.buildHtml(NoteTextBox.Format.MARKDOWN,
                "\\[a_1+a_2+a_3+a_4+a_5+a_6+a_7+a_8=a_9\\]", 16f, 1.35f);
        assertTrue(html.contains(".katex-html{white-space:normal}"));
        assertTrue(html.contains(".katex-html>.base"));
        assertTrue(html.contains("available/baseWidth"));
        assertFalse(html.contains("Math.max(.62"));
        assertFalse(html.contains(".math-display{display:block;text-align:center;overflow-x:auto"));
    }

    @Test public void heightIsReportedOnlyAfterFontsAndDiagramsSettle() {
        String html = CompiledTextWebView.buildHtml(NoteTextBox.Format.MARKDOWN,
                "```mermaid\nflowchart TD\nA-->B\nB-->C\n```", 16f, 1.35f);
        assertTrue(html.contains("window.__padnoteReady=false"));
        assertTrue(html.contains("document.fonts.ready"));
        assertTrue(html.contains("__padnoteSettle();"));
        assertTrue(html.contains("Math.max(c.getBoundingClientRect().height,c.scrollHeight)"));
        assertFalse(html.contains("c.scrollHeight+parseFloat"));
    }

    @Test public void veryTallDiagramReportsNaturalNeedWhileDisplayingWholeViewBox() {
        String html = CompiledTextWebView.buildHtml(NoteTextBox.Format.MARKDOWN,
                "```mermaid\nflowchart TD\nA-->B\nB-->C\nC-->D\nD-->E\nE-->F\nF-->G\nG-->H\n```",
                16f, 1.35f);
        assertTrue(html.contains("naturalHeight*(widthScale-diagramScale)"));
        assertTrue(html.contains("__padnoteDiagramExtra"));
        assertTrue(html.contains("preserveAspectRatio','xMidYMid meet"));
        assertTrue(html.contains("svg.style.height=Math.max(1,naturalHeight*diagramScale)+'px'"));
    }

    @Test public void headingScaleMatchesPaginatorContract() {
        String html = CompiledTextWebView.buildHtml(NoteTextBox.Format.MARKDOWN,
                "# 一级\n## 二级\n### 三级", 16f, 1.35f);
        assertTrue(html.contains("h1{font-size:1.55em}"));
        assertTrue(html.contains("h2{font-size:1.32em}"));
        assertTrue(html.contains("h3{font-size:1.16em}"));
        assertTrue(html.contains("line-height:1.2"));
    }

    @Test public void orderedListPreservesExplicitStartAndItemText() {
        String html = CompiledTextWebView.buildHtml(NoteTextBox.Format.MARKDOWN,
                "9. 第九步\n10. 第十步", 16f, 1.35f);
        assertTrue(html.contains("<ol start=\"9\"><li>第九步</li><li>第十步</li></ol>"));
        assertFalse(html.contains("<li>9</li>"));

        String automatic = CompiledTextWebView.buildHtml(NoteTextBox.Format.MARKDOWN,
                "1. 第一步\n1. 第二步\n1. 第三步", 16f, 1.35f);
        assertTrue(automatic.contains("<ol start=\"1\"><li>第一步</li><li>第二步</li>" +
                "<li>第三步</li></ol>"));
        assertEquals(1, occurrences(automatic, "<ol start="));
    }

    @Test public void separatedAndPaginatedOrderedRunsKeepTheirSourceNumbers() {
        String separated = CompiledTextWebView.buildHtml(NoteTextBox.Format.MARKDOWN,
                "3. 第三步\n\n4. 第四步", 16f, 1.35f);
        assertTrue(separated.contains("<ol start=\"3\"><li>第三步</li></ol>"));
        assertTrue(separated.contains("<ol start=\"4\"><li>第四步</li></ol>"));

        // Pagination renders each fragment independently, so exercise the later
        // fragment exactly as buildHtml receives it after a page break.
        String laterFragment = CompiledTextWebView.buildHtml(NoteTextBox.Format.MARKDOWN,
                "12. 第十二步\n13. 第十三步", 16f, 1.35f);
        assertTrue(laterFragment.contains(
                "<ol start=\"12\"><li>第十二步</li><li>第十三步</li></ol>"));
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(token, index)) >= 0; index += token.length()) {
            count += 1;
        }
        return count;
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
