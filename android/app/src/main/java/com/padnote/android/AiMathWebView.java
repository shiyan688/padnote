package com.padnote.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;

final class AiMathWebView extends WebView {
    private static final String ASSET_BASE = "file:///android_asset/katex/";
    private boolean disposed = false;

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
            public void onPageFinished(WebView view, String url) {
                resizeToContent();
                postDelayed(AiMathWebView.this::resizeToContent, 120);
                postDelayed(AiMathWebView.this::resizeToContent, 420);
            }
        });
        loadDataWithBaseURL(ASSET_BASE, buildHtml(answer), "text/html", "UTF-8", null);
    }

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

    private void resizeToContent() {
        if (disposed) {
            return;
        }
        evaluateJavascript("Math.max(document.body.scrollHeight,document.documentElement.scrollHeight)",
                value -> {
                    if (disposed) {
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
        disposed = true;
        stopLoading();
        loadUrl("about:blank");
        clearHistory();
        removeAllViews();
        super.destroy();
    }

    private String buildHtml(String answer) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,user-scalable=no\">" +
                "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
                "style-src 'self' 'unsafe-inline'; font-src 'self'; script-src 'self' 'unsafe-inline'\">" +
                "<link rel=\"stylesheet\" href=\"katex.min.css\">" +
                "<style>html,body{margin:0;padding:0;background:transparent;color:#17212b;" +
                "font-family:sans-serif;font-size:14px;line-height:1.55;overflow:hidden}" +
                ".answer{padding:9px 12px;overflow-wrap:anywhere}.plain{white-space:pre-wrap}" +
                ".math-display{display:block;overflow-x:auto;overflow-y:hidden;margin:8px 0;" +
                "padding:5px 2px;text-align:center}.math-inline{display:inline-block;margin:0 2px}" +
                ".katex-error{color:#8f2f2b}</style></head><body><div class=\"answer\">" +
                answerToHtml(answer == null ? "" : answer) +
                "</div><script src=\"katex.min.js\"></script><script>" +
                "document.querySelectorAll('[data-tex]').forEach(function(el){" +
                "katex.render(el.getAttribute('data-tex'),el,{displayMode:el.getAttribute('data-display')==='1'," +
                "throwOnError:false,strict:'ignore',trust:false,output:'htmlAndMathml'});});" +
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
