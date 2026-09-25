package com.padnote.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure contracts shared by the paper, vault and AI-answer WebView renderers. */
public final class RenderRecoveryTest {
    @Test
    public void paperRendererPublishesTokenBoundSyntaxAndResourceFailures() {
        String html = CompiledTextWebView.buildHtml(NoteTextBox.Format.MARKDOWN,
                "```mermaid\nflowchart TD\nA -->\n```\n\n$\\badcommand{x}$", 16f, 1.35f);
        assertTrue(html.contains("window.__padnoteToken='static'"));
        assertTrue(html.contains("window.__padnoteError='resource'"));
        assertTrue(html.contains("throwOnError:true"));
        assertTrue(html.contains("catch(error){window.__padnoteError='syntax'"));
        assertTrue(html.contains("公式语法有误，可查看源码后编辑并重新显示"));
        assertTrue(html.contains("window.__padnoteError='syntax'"));
        assertTrue(html.contains("请编辑 Mermaid 源码后重新显示"));
        assertFalse(html.contains("请让 AI 修复"));
    }

    @Test
    public void vaultAndAnswerRenderersUseTheSameExplicitReadyContract() {
        String document = CompiledTextWebView.buildDocumentHtml("# 文档\n\n$\\badcommand{x}$");
        String answer = AiMathWebView.buildHtml("答案：$\\badcommand{x}$");
        for (String html : new String[]{document, answer}) {
            assertTrue(html.contains("window.__padnoteToken='static'"));
            assertTrue(html.contains("window.__padnoteReady=false"));
            assertTrue(html.contains("throwOnError:true"));
            assertTrue(html.contains(".math-error"));
            assertTrue(html.contains("window.__padnoteError='syntax'"));
            assertTrue(html.contains("document.fonts.ready"));
        }
    }

    @Test
    public void renderIdentityChangesForEveryLayoutRelevantInput() {
        String base = CompiledTextWebView.inputDigest(NoteTextBox.Format.MARKDOWN,
                "正文", 16f, 1.35f, 336, false);
        assertNotEquals(base, CompiledTextWebView.inputDigest(NoteTextBox.Format.MARKDOWN,
                "另一段正文", 16f, 1.35f, 336, false));
        assertNotEquals(base, CompiledTextWebView.inputDigest(NoteTextBox.Format.MARKDOWN,
                "正文", 17f, 1.35f, 336, false));
        assertNotEquals(base, CompiledTextWebView.inputDigest(NoteTextBox.Format.MARKDOWN,
                "正文", 16f, 1.35f, 320, false));
        assertNotEquals(base, CompiledTextWebView.inputDigest(NoteTextBox.Format.MARKDOWN,
                "正文", 16f, 1.35f, 336, true));
    }
}
