package com.padnote.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.webkit.RenderProcessGoneDetail;

import java.util.ArrayList;
import java.util.List;

final class AiMathWebView extends WebView {
    private static final String ASSET_BASE = "file:///android_asset/katex/";
    private static final int MAX_SOURCE_BYTES = 512 * 1024;
    private static final int READY_POLL_LIMIT = 100;
    private static final long READY_POLL_INTERVAL_MS = 50L;
    private boolean disposed = false;
    private int renderGeneration;
    private int settledGeneration = -1;
    private String activeDigest = "";
    private String answer = "";
    private CompiledTextWebView.RenderStateListener renderStateListener;
    private boolean processGone;
    private boolean destroyed;

    private static final class Delimiter {
        final int start;
        final String open;
        final String close;
        final boolean display;

        Delimiter(int start, String open, String close, boolean display) {
            this.start = start;
            this.open = open;
            this.close = close;
            this.display = display;
        }
    }

    public AiMathWebView(Context context) {
        this(context, "");
    }

    @SuppressLint("SetJavaScriptEnabled")
    AiMathWebView(Context context, String answer) {
        this(context, answer, true);
    }

    @SuppressLint("SetJavaScriptEnabled")
    AiMathWebView(Context context, String answer, boolean renderNow) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFocusable(false);

        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setDefaultTextEncodingName("UTF-8");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                // Token-bound page state reports subresource failures.
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse response) {
                // Token-bound page state reports subresource failures.
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pollReady(renderGeneration, activeDigest, 0);
            }

            @Override
            @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O)
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                reportProcessGone();
                return true;
            }
        });
        this.answer = answer == null ? "" : answer;
        if (renderNow) renderAnswer(this.answer);
    }

    void setRenderStateListener(CompiledTextWebView.RenderStateListener listener) {
        renderStateListener = listener;
    }

    void retryCurrentRender() { renderAnswer(answer); }

    void renderDisplayCopy(String editedAnswer) { renderAnswer(editedAnswer); }

    String displaySource() { return answer; }

    private void renderAnswer(String replacement) {
        if (processGone || destroyed) throw new IllegalStateException("失效的公式组件不能重新使用");
        disposed = false;
        answer = replacement == null ? "" : replacement;
        renderGeneration += 1;
        settledGeneration = -1;
        int generation = renderGeneration;
        activeDigest = CompiledTextWebView.inputDigest(NoteTextBox.Format.MARKDOWN,
                answer, 14f, 1.55f, Math.max(0, getWidth()), true);
        String digest = activeDigest;
        if (answer.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            post(() -> fail(generation, digest, CompiledTextWebView.FailureKind.TOO_LARGE,
                    "回答过长，已停止公式显示"));
            return;
        }
        String token = generation + "-" + digest;
        loadDataWithBaseURL(ASSET_BASE, buildHtml(answer, token), "text/html", "UTF-8",
                "about:blank#padnote-answer-" + generation);
        postDelayed(() -> {
            if (!disposed && generation == renderGeneration && generation != settledGeneration
                    && digest.equals(activeDigest)) {
                fail(generation, digest, CompiledTextWebView.FailureKind.TIMEOUT,
                        "公式显示超时");
            }
        }, READY_POLL_LIMIT * READY_POLL_INTERVAL_MS);
    }

    private void pollReady(int generation, String digest, int attempt) {
        if (disposed || generation != renderGeneration || !digest.equals(activeDigest)) return;
        evaluateJavascript("(function(){return String(window.__padnoteToken||'')+'~'+" +
                        "(window.__padnoteError?'!'+window.__padnoteError:" +
                        "window.__padnoteReady?'ready':'');})()", value -> {
            if (disposed || generation != renderGeneration || !digest.equals(activeDigest)) return;
            String normalized = value == null ? "" : value.replace("\"", "").trim();
            String token = generation + "-" + digest;
            if (!normalized.startsWith(token + "~")) return;
            String state = normalized.substring(token.length() + 1);
            if (state.startsWith("!")) {
                String code = state.substring(1);
                fail(generation, digest, "syntax".equals(code)
                                ? CompiledTextWebView.FailureKind.SYNTAX
                                : "resource".equals(code)
                                ? CompiledTextWebView.FailureKind.RESOURCE
                                : CompiledTextWebView.FailureKind.SCRIPT,
                        "syntax".equals(code) ? "回答中的公式语法有误"
                                : "resource".equals(code) ? "本地公式资源无法载入"
                                : "本地公式显示失败");
                return;
            }
            if (!"ready".equals(state)) {
                if (attempt + 1 < READY_POLL_LIMIT) {
                    postDelayed(() -> pollReady(generation, digest, attempt + 1),
                            READY_POLL_INTERVAL_MS);
                }
                return;
            }
            settledGeneration = generation;
            resizeToContent(generation, digest);
            if (renderStateListener != null) renderStateListener.onRenderState(
                    new CompiledTextWebView.RenderState(generation, digest, null, ""));
        });
    }

    private void fail(int generation, String digest, CompiledTextWebView.FailureKind kind,
                      String message) {
        if (disposed || generation != renderGeneration || generation == settledGeneration
                || !digest.equals(activeDigest)) return;
        settledGeneration = generation;
        if (renderStateListener != null) renderStateListener.onRenderState(
                new CompiledTextWebView.RenderState(generation, digest, kind, message));
    }

    private void reportProcessGone() {
        if (processGone) return;
        processGone = true;
        disposed = true;
        if (renderStateListener != null) renderStateListener.onRenderState(
                new CompiledTextWebView.RenderState(renderGeneration, activeDigest,
                        CompiledTextWebView.FailureKind.PROCESS_GONE,
                        "公式显示进程已退出"));
    }

    /** Test hook for the owner replacement path; does not emulate Chromium death itself. */
    void simulateRenderProcessGoneForTest() { reportProcessGone(); }

    static boolean containsMath(String answer) {
        if (answer == null || answer.isEmpty()) {
            return false;
        }
        for (String[] pair : delimiterPairs()) {
            int start = findUnescaped(answer, pair[0], 0);
            if (start >= 0 && findUnescaped(answer, pair[1], start + pair[0].length()) >= 0) {
                return true;
            }
        }
        return false;
    }

    private void resizeToContent(int generation, String digest) {
        if (disposed || generation != renderGeneration || !digest.equals(activeDigest)) {
            return;
        }
        evaluateJavascript("Math.max(document.body.scrollHeight,document.documentElement.scrollHeight)",
                value -> {
                    if (disposed || generation != renderGeneration
                            || !digest.equals(activeDigest)) {
                        return;
                    }
                    try {
                        String normalized = value == null ? "" : value.replace("\"", "");
                        float cssPixels = Float.parseFloat(normalized);
                        int desired = Math.max(dp(54), Math.min(dp(6000),
                                Math.round(cssPixels * getResources().getDisplayMetrics().density)));
                        ViewGroup.LayoutParams params = getLayoutParams();
                        if (params != null && Math.abs(params.height - desired) > dp(2)) {
                            params.height = desired;
                            setLayoutParams(params);
                        }
                    } catch (Exception ignored) {
                        // Keep the conservative initial height when WebView has not measured yet.
                    }
                });
    }

    @Override
    public void destroy() {
        destroyed = true;
        if (processGone) {
            renderStateListener = null;
            return;
        }
        disposed = true;
        renderGeneration += 1;
        renderStateListener = null;
        stopLoading();
        loadUrl("about:blank");
        clearHistory();
        removeAllViews();
        super.destroy();
    }

    static String buildHtml(String answer) {
        return buildHtml(answer, "static");
    }

    private static String buildHtml(String answer, String token) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,user-scalable=no\">" +
                "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
                "style-src 'self' 'unsafe-inline'; font-src 'self'; script-src 'self' 'unsafe-inline'\">" +
                CompiledTextWebView.renderBootstrap(token) +
                "<link rel=\"stylesheet\" href=\"katex.min.css\">" +
                "<style>html,body{margin:0;padding:0;background:transparent;color:#17212b;" +
                "font-family:sans-serif;font-size:14px;line-height:1.55;overflow:hidden}" +
                ".answer{padding:9px 12px;overflow-wrap:anywhere}.plain{white-space:pre-wrap}" +
                ".math-display{display:block;overflow-x:auto;overflow-y:hidden;margin:8px 0;" +
                "padding:5px 2px;text-align:center}.math-inline{display:inline-block;margin:0 2px}" +
                ".katex-error,.math-error{color:#8f2f2b}</style></head><body><div class=\"answer\">" +
                answerToHtml(answer == null ? "" : answer) +
                "</div><script src=\"katex.min.js\"></script><script>" +
                "document.querySelectorAll('[data-tex]').forEach(function(el){try{" +
                "katex.render(el.getAttribute('data-tex'),el,{displayMode:el.getAttribute('data-display')==='1'," +
                "throwOnError:true,strict:'ignore',trust:false,output:'htmlAndMathml'});" +
                "}catch(error){window.__padnoteError='syntax';el.classList.add('math-error');" +
                "el.textContent='公式语法有误，可查看源码后编辑并重新显示。';}});" +
                "var fonts=document.fonts&&document.fonts.ready?document.fonts.ready:Promise.resolve();" +
                "fonts.then(function(){window.__padnoteReady=true;});" +
                "</script></body></html>";
    }

    private static String answerToHtml(String answer) {
        StringBuilder html = new StringBuilder();
        int cursor = 0;
        while (cursor < answer.length()) {
            Delimiter next = nextDelimiter(answer, cursor);
            if (next == null) {
                appendPlain(html, answer.substring(cursor));
                break;
            }
            int formulaStart = next.start + next.open.length();
            int formulaEnd = findUnescaped(answer, next.close, formulaStart);
            if (formulaEnd < 0) {
                appendPlain(html, answer.substring(cursor));
                break;
            }
            appendPlain(html, answer.substring(cursor, next.start));
            String formula = answer.substring(formulaStart, formulaEnd).trim();
            String tag = next.display ? "div" : "span";
            html.append('<').append(tag)
                    .append(" class=\"")
                    .append(next.display ? "math-display" : "math-inline")
                    .append("\" data-display=\"")
                    .append(next.display ? '1' : '0')
                    .append("\" data-tex=\"")
                    .append(escapeHtml(formula))
                    .append("\"></").append(tag).append('>');
            cursor = formulaEnd + next.close.length();
        }
        if (answer.isEmpty()) {
            html.append("<span class=\"plain\"></span>");
        }
        return html.toString();
    }

    private static void appendPlain(StringBuilder html, String value) {
        if (!value.isEmpty()) {
            html.append("<span class=\"plain\">")
                    .append(escapeHtml(value))
                    .append("</span>");
        }
    }

    private static Delimiter nextDelimiter(String answer, int fromIndex) {
        Delimiter best = null;
        for (String[] pair : delimiterPairs()) {
            int start = findUnescaped(answer, pair[0], fromIndex);
            if (start < 0) {
                continue;
            }
            boolean display = "\\[".equals(pair[0]) || "$$".equals(pair[0]);
            if (best == null || start < best.start ||
                    (start == best.start && pair[0].length() > best.open.length())) {
                best = new Delimiter(start, pair[0], pair[1], display);
            }
        }
        return best;
    }

    private static List<String[]> delimiterPairs() {
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[]{"\\[", "\\]"});
        pairs.add(new String[]{"\\(", "\\)"});
        pairs.add(new String[]{"$$", "$$"});
        pairs.add(new String[]{"$", "$"});
        return pairs;
    }

    private static int findUnescaped(String value, String target, int fromIndex) {
        int cursor = Math.max(0, fromIndex);
        while (cursor <= value.length() - target.length()) {
            int found = value.indexOf(target, cursor);
            if (found < 0) {
                return -1;
            }
            int backslashes = 0;
            for (int index = found - 1; index >= 0 && value.charAt(index) == '\\'; index--) {
                backslashes += 1;
            }
            if (backslashes % 2 == 0) {
                return found;
            }
            cursor = found + target.length();
        }
        return -1;
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
