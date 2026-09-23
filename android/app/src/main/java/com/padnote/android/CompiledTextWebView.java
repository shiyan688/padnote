package com.padnote.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline, non-networked renderer for persistent LaTeX and Markdown note boxes. */
@SuppressLint("ViewConstructor")
final class CompiledTextWebView extends WebView {
    private static final String ASSET_BASE = "file:///android_asset/katex/";
    private static final int READY_POLL_LIMIT = 100;
    private static final long READY_POLL_INTERVAL_MS = 50L;
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern UNORDERED = Pattern.compile("^\\s*[-*+]\\s+(.+)$");
    private static final Pattern ORDERED = Pattern.compile("^\\s*(\\d+)[.)]\\s+(.+)$");
    /** Invalidates readiness polls left behind by a previous render. */
    private int renderGeneration;
    private ExportReadyListener exportReadyListener;
    private int exportReadyGeneration = -1;
    private boolean renderingCancelled;

    CompiledTextWebView(Context context, NoteTextBox.Format format, String source) {
        this(context);
        render(format, source, 16f, TextFlow.DEFAULT_LINE_HEIGHT);
    }

    /** Empty renderer used by export so no superseded initial load can fail it. */
    @SuppressLint("SetJavaScriptEnabled")
    CompiledTextWebView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        setVerticalScrollBarEnabled(true);
        setHorizontalScrollBarEnabled(true);

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
                if (exportReadyListener != null) {
                    failExportRender(renderGeneration, "文字资源加载失败");
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                if (exportReadyListener != null) {
                    failExportRender(renderGeneration, "文字资源加载失败");
                }
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                if (!renderingCancelled) {
                    pollRenderedHeightWhenReady(renderGeneration, 0);
                }
            }
        });
    }

    /** Receives the height the content actually needed, in CSS px (= dp). */
    interface HeightListener {
        void onMeasuredHeight(float heightDp);
    }

    interface ExportReadyListener {
        void onReady(float measuredHeightPx, float visualHeightPx);

        void onFailure(String message);
    }

    private HeightListener heightListener;

    void setHeightListener(HeightListener listener) {
        this.heightListener = listener;
    }

    /** Invalidates delayed JS/compositor callbacks before an export WebView dies. */
    void cancelPendingRendering() {
        renderingCancelled = true;
        renderGeneration += 1;
        exportReadyGeneration = -1;
        exportReadyListener = null;
        heightListener = null;
        stopLoading();
    }

    void render(NoteTextBox.Format format, String source) {
        render(format, source, 16f, TextFlow.DEFAULT_LINE_HEIGHT);
    }

    void render(NoteTextBox.Format format, String source, float fontSizeSp,
                float lineHeight) {
        renderingCancelled = false;
        renderGeneration += 1;
        loadDataWithBaseURL(ASSET_BASE, buildHtml(format, source, fontSizeSp, lineHeight),
                "text/html", "UTF-8", null);
    }

    /** Renders an attached offscreen export view and reports a compositor-ready frame. */
    void renderForExport(NoteTextBox.Format format, String source, float fontSizeSp,
                         float lineHeight, ExportReadyListener listener) {
        exportReadyListener = listener;
        render(format, source, fontSizeSp, lineHeight);
        exportReadyGeneration = renderGeneration;
    }

    /**
     * Renders a whole vault note: one scrolling Markdown document with KaTeX
     * math and Mermaid diagrams, both from bundled offline assets.
     *
     * <p>Separate from {@link #render} on purpose: flow fragments paginate by an
     * estimator that replicates {@link #buildHtml}, so that path must stay
     * byte-stable. Vault documents scroll naturally and are never measured.
     */
    void renderDocument(String markdown) {
        renderingCancelled = false;
        loadDataWithBaseURL(ASSET_BASE, buildDocumentHtml(markdown),
                "text/html", "UTF-8", null);
    }

    /**
     * Builds the offline reader document for one vault note.
     *
     * <p>Mermaid blocks become {@code <pre class="mermaid">} and are drawn by the
     * bundled mermaid.min.js; everything else goes through the same Markdown
     * pipeline the note boxes use, so a digitized note reads identically in both
     * places. The script tag sits under the katex asset base via a relative
     * parent path — no network, no file access beyond bundled assets.
     */
    static String buildDocumentHtml(String markdown) {
        String body = markdownToHtml(markdown == null ? "" : markdown, true);
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
                "style-src 'self' 'unsafe-inline'; font-src 'self'; script-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data:\">" +
                "<link rel=\"stylesheet\" href=\"katex.min.css\">" +
                "<style>html,body{margin:0;padding:0;background:#ffffff;color:#17212b;" +
                "font-family:sans-serif;font-size:16px;line-height:1.55}" +
                ".content{padding:18px 20px 32px;overflow-wrap:anywhere}" +
                ".math-display{display:block;text-align:center;overflow-x:auto;margin:10px 0}" +
                ".math-inline{display:inline-block;margin:0 2px}" +
                "h1,h2,h3,h4,h5,h6{margin:18px 0 8px;line-height:1.25}" +
                "h1{font-size:1.5em}h2{font-size:1.3em}h3{font-size:1.15em}" +
                "p{margin:8px 0}ul,ol{margin:8px 0;padding-left:24px}" +
                "blockquote{margin:10px 0;padding:8px 12px;border-left:3px solid #7894b8;" +
                "background:#eef3f8}code{font-family:monospace;background:#eef0f2;" +
                "border-radius:4px;padding:1px 4px}" +
                "pre{white-space:pre-wrap;margin:10px 0;background:#eef0f2;border-radius:7px;" +
                "padding:10px;overflow-x:auto}" +
                "pre.mermaid{background:#ffffff;text-align:center}" +
                ".md-link{color:#285ea8}.katex-error{color:#8f2f2b}</style></head>" +
                "<body><div class=\"content\">" + body + "</div>" +
                "<script src=\"katex.min.js\"></script>" +
                "<script src=\"../mermaid/mermaid.min.js\"></script><script>" +
                "document.querySelectorAll('[data-tex]').forEach(function(el){" +
                "katex.render(el.getAttribute('data-tex'),el,{displayMode:el.getAttribute('data-display')==='1'," +
                "throwOnError:false,strict:'ignore',trust:false,output:'htmlAndMathml'});});" +
                "if(window.mermaid){mermaid.initialize({startOnLoad:true,theme:'neutral'," +
                "securityLevel:'strict',flowchart:{useMaxWidth:true}});}" +
                "</script></body></html>";
    }

    /**
     * Asks the page how tall it really is.
     *
     * <p>The paginator sizes fragments from an estimate, and an estimate that comes
     * in low silently clips the tail of a fragment — a matrix or an unbreakable
     * token is enough. Reporting the measured height lets the layout correct
     * itself instead of relying on the safety factor absorbing every case.
     *
     * <p>Measured via {@code evaluateJavascript} rather than a JavaScript bridge:
     * this WebView has network, file and storage access switched off, and adding an
     * injected object would widen that surface for one number.
     */
    private void pollRenderedHeightWhenReady(int generation, int attempt) {
        if (renderingCancelled || generation != renderGeneration) {
            return;
        }
        // The page flips __padnoteReady only after KaTeX fonts and every Mermaid
        // render have settled. Polling the state avoids device-dependent timeout
        // guesses while keeping the WebView free of a JavaScript bridge.
        evaluateJavascript(
                "(function(){return window.__padnoteError?'!':window.__padnoteReady?" +
                        "String(window.__padnoteHeight||0)+'|'+" +
                        "String(window.__padnoteVisualHeight||window.__padnoteHeight||0):'';})()",
                value -> {
                    if (renderingCancelled || generation != renderGeneration) {
                        return;
                    }
                    String normalized = value == null ? "" : value.replace("\"", "").trim();
                    if ("!".equals(normalized)) {
                        failExportRender(generation, "文字脚本渲染失败");
                        return;
                    }
                    if (normalized.isEmpty()) {
                        if (attempt + 1 < READY_POLL_LIMIT) {
                            postDelayed(() -> pollRenderedHeightWhenReady(
                                    generation, attempt + 1), READY_POLL_INTERVAL_MS);
                        } else {
                            failExportRender(generation, "文字资源渲染超时");
                        }
                        return;
                    }
                    try {
                        String[] parts = normalized.split("\\|", 2);
                        float measured = Float.parseFloat(parts[0]);
                        float visual = parts.length == 2 ? Float.parseFloat(parts[1]) : measured;
                        if (heightListener != null && measured > 0f) {
                            heightListener.onMeasuredHeight(measured);
                        }
                        signalExportFrameWhenVisible(generation, measured, visual);
                    } catch (NumberFormatException malformed) {
                        // A page that cannot report its height keeps the estimate.
                        failExportRender(generation, "文字渲染高度无效");
                    }
                });
    }

    private void signalExportFrameWhenVisible(int generation, float measuredHeightPx,
                                               float visualHeightPx) {
        if (exportReadyListener == null || exportReadyGeneration != generation ||
                generation != renderGeneration) {
            return;
        }
        ExportReadyListener expected = exportReadyListener;
        postVisualStateCallback(generation, new VisualStateCallback() {
            @Override
            public void onComplete(long requestId) {
                if (exportReadyListener != expected || exportReadyGeneration != generation ||
                        generation != renderGeneration) {
                    return;
                }
                exportReadyListener = null;
                expected.onReady(measuredHeightPx, visualHeightPx);
            }
        });
        // postVisualStateCallback has no failure callback. Bound it separately so
        // a detached or failed compositor cannot make PDF export wait forever.
        postDelayed(() -> {
            if (exportReadyListener == expected && exportReadyGeneration == generation) {
                exportReadyListener = null;
                expected.onFailure("文字画面提交超时");
            }
        }, READY_POLL_LIMIT * READY_POLL_INTERVAL_MS);
    }

    private void failExportRender(int generation, String message) {
        if (exportReadyListener == null || exportReadyGeneration != generation) {
            return;
        }
        ExportReadyListener listener = exportReadyListener;
        exportReadyListener = null;
        listener.onFailure(message);
    }

    /**
     * Builds the offline document for one fragment.
     *
     * <p>Line height is a parameter rather than a constant because
     * {@code NoteCanvasView} estimates fragment heights from the same value. If
     * the two ever disagree, pagination breaks pages in the wrong place.
     */
    static String buildHtml(NoteTextBox.Format format, String source, float fontSizeSp,
                            float lineHeight) {
        NoteTextBox.Format safeFormat = format == null ? NoteTextBox.Format.LATEX : format;
        String safeSource = source == null ? "" : source;
        // 10-32sp is the range a user may *author* in, but the rendered size also
        // carries the canvas zoom, so a magnified box legitimately asks for more.
        // Clamping to the authoring range here would stop text growing past 2x.
        float safeFontSize = Math.max(4f, Math.min(400f, fontSizeSp));
        float safeLineHeight = TextFlow.clampLineHeight(lineHeight);
        // Gaps between blocks scale with the leading, so tightening line height
        // also tightens paragraph spacing; fixed gaps would otherwise dominate
        // once the lines themselves sit closer together.
        int blockGap = Math.max(2, Math.round(safeFontSize * (safeLineHeight - 1f) * 0.55f));
        int headingTop = blockGap + Math.max(1, Math.round(safeFontSize * 0.14f));
        int mathGap = blockGap + 2;
        int listIndent = Math.round(safeFontSize * 1.5f);
        String body = safeFormat == NoteTextBox.Format.MARKDOWN
                ? markdownToHtml(safeSource, true)
                : "<div class=\"latex-root\" data-display=\"1\" data-tex=\"" +
                escapeHtml(normalizeLatexSource(safeSource)) + "\"></div>";
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,user-scalable=no\">" +
                "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
                "style-src 'self' 'unsafe-inline'; font-src 'self'; script-src 'self' 'unsafe-inline'\">" +
                "<link rel=\"stylesheet\" href=\"katex.min.css\">" +
                "<style>html,body{margin:0;padding:0;background:transparent;color:#17212b;" +
                "font-family:system-ui,-apple-system,sans-serif;font-size:" + safeFontSize + "px;" +
                "line-height:" + safeLineHeight + "}" +
                ".content{padding:10px 12px;overflow-wrap:anywhere}.latex-root{text-align:center;" +
                "padding:" + mathGap + "px 4px;overflow:visible}.math-display{display:block;" +
                "text-align:center;overflow:visible;margin:" + mathGap + "px 0}" +
                ".math-inline{display:inline-block;margin:0 2px}" +
                ".latex-root .katex-html,.math-display .katex-html{white-space:normal}" +
                ".latex-root .katex-html>.base,.math-display .katex-html>.base{" +
                "display:inline-block;white-space:nowrap;max-width:100%}" +
                "h1,h2,h3,h4,h5,h6{margin:" + headingTop + "px 0 " + blockGap +
                "px;line-height:1.2;color:#1f2933}h1{font-size:1.55em}h2{font-size:1.32em}" +
                "h3{font-size:1.16em}h4{font-size:1em}h5,h6{font-size:.92em}" +
                "p{margin:" + blockGap + "px 0}" +
                "ul,ol{margin:" + blockGap + "px 0;padding-left:" + listIndent + "px}" +
                "li{margin:0 0 " + Math.max(1, blockGap / 2) + "px}blockquote{margin:" + mathGap + "px 0;padding:" +
                blockGap + "px 10px;" +
                "border-left:3px solid #7894b8;background:#eef3f8}code{font-family:monospace;" +
                "background:#eef0f2;border-radius:4px;padding:1px 4px}pre{white-space:pre-wrap;" +
                "margin:" + blockGap + "px 0;" +
                "background:#eef0f2;border-radius:7px;padding:8px}.md-link{color:#285ea8}" +
                "pre.mermaid{padding:8px 0;overflow:visible;background:transparent;text-align:center}" +
                // A diagram is one indivisible source block. Keep its real viewBox
                // ratio, but contain unusually tall diagrams inside the paper
                // fragment instead of leaving an invisible clipped tail.
                "pre.mermaid svg{display:block;width:auto;height:auto;margin:0 auto;" +
                "max-width:100%!important;object-fit:contain}" +
                ".diagram-error{display:block;padding:12px;white-space:pre-wrap;color:#8f2f2b}" +
                ".katex-error{color:#8f2f2b}</style></head><body><div class=\"content\">" +
                body + "</div><script src=\"katex.min.js\"></script><script>" +
                "window.__padnoteReady=false;window.__padnoteError='';window.__padnoteHeight=0;" +
                "window.__padnoteVisualHeight=0;window.addEventListener('error',function(){" +
                "window.__padnoteError='resource';});" +
                "window.__padnoteDiagramExtra=0;window.__padnoteUnclippedExtra=0;" +
                "document.querySelectorAll('[data-tex]').forEach(function(el){" +
                "katex.render(el.getAttribute('data-tex'),el,{displayMode:el.getAttribute('data-display')==='1'," +
                "throwOnError:false,strict:'ignore',trust:false,output:'htmlAndMathml'});});" +
                "function __padnoteFitMath(){window.__padnoteUnclippedExtra=window.__padnoteDiagramExtra||0;" +
                "document.querySelectorAll('.latex-root,.math-display').forEach(function(el){" +
                "var k=el.querySelector('.katex');if(!k)return;k.style.fontSize='1em';" +
                "var available=el.clientWidth;var bases=k.querySelectorAll('.katex-html>.base');" +
                "bases.forEach(function(base){base.style.fontSize='1em';var baseWidth=base.scrollWidth;" +
                "if(baseWidth>available&&available>0){base.style.fontSize=(available/baseWidth*.995)+'em';}});" +
                "var r=k.getBoundingClientRect();" +
                "var availableHeight=Math.max(12,window.innerHeight-el.getBoundingClientRect().top-12);" +
                "var scale=Math.min(1,available/Math.max(1,Math.max(k.scrollWidth,r.width))," +
                "availableHeight/Math.max(1,r.height));" +
                "if(scale<1){window.__padnoteUnclippedExtra+=r.height*(1-scale);" +
                "k.style.fontSize=(scale*.995)+'em';}});}" +
                // Record the settled height so the native side can correct a
                // pagination estimate that came in too low and would clip the tail.
                "function __padnoteMeasure(){var c=document.querySelector('.content');" +
                "if(!c)return;" +
                "window.__padnoteVisualHeight=Math.ceil(Math.max(c.getBoundingClientRect().height,c.scrollHeight));" +
                "window.__padnoteHeight=Math.ceil(window.__padnoteVisualHeight+" +
                "(window.__padnoteUnclippedExtra||0));window.__padnoteReady=true;}" +
                "function __padnoteSettle(){var fonts=document.fonts&&document.fonts.ready?" +
                "document.fonts.ready:Promise.resolve();fonts.then(function(){" +
                "requestAnimationFrame(function(){requestAnimationFrame(function(){" +
                "__padnoteFitMath();__padnoteMeasure();});});});}" +
                "</script>" + (body.contains("class=\"mermaid\"") ?
                "<script src=\"../mermaid/mermaid.min.js\"></script><script>" +
                "mermaid.initialize({startOnLoad:false,theme:'base',securityLevel:'strict'," +
                "themeVariables:{primaryColor:'#e9f1ff',primaryBorderColor:'#527bbe',primaryTextColor:'#17212b'," +
                "lineColor:'#64748b',secondaryColor:'#e7f6ed',tertiaryColor:'#fff2d3',fontFamily:'sans-serif'}," +
                "suppressErrorRendering:true,flowchart:{htmlLabels:false}});" +
                "(async function(){var nodes=document.querySelectorAll('pre.mermaid');" +
                "for(var i=0;i<nodes.length;i++){var el=nodes[i],code=el.textContent;el.textContent='';" +
                "try{var result=await mermaid.render('padnote-diagram-'+i,code);el.innerHTML=result.svg;" +
                "var svg=el.querySelector('svg');svg.setAttribute('preserveAspectRatio','xMidYMid meet');" +
                "var vb=svg.viewBox&&svg.viewBox.baseVal;var naturalWidth=vb&&vb.width?vb.width:svg.getBoundingClientRect().width;" +
                "var naturalHeight=vb&&vb.height?vb.height:svg.getBoundingClientRect().height;" +
                "var maxWidth=el.clientWidth;var maxHeight=Math.max(72,window.innerHeight-el.getBoundingClientRect().top-18);" +
                "var widthScale=Math.min(1,maxWidth/Math.max(1,naturalWidth));" +
                "var diagramScale=Math.min(widthScale,maxHeight/Math.max(1,naturalHeight));" +
                "window.__padnoteDiagramExtra+=Math.max(0,naturalHeight*(widthScale-diagramScale));" +
                "svg.removeAttribute('width');svg.removeAttribute('height');" +
                "svg.style.width=Math.max(1,naturalWidth*diagramScale)+'px';" +
                "svg.style.height=Math.max(1,naturalHeight*diagramScale)+'px';}" +
                "catch(error){var msg=document.createElement('span');msg.className='diagram-error';" +
                "msg.textContent='示意图语法有误，请编辑 Mermaid 源码或让 AI 重新生成。';el.appendChild(msg);}}" +
                "__padnoteSettle();})();</script>" :
                "<script>__padnoteSettle();</script>") + "</body></html>";
    }

    static String normalizeLatexSource(String source) {
        String trimmed = source.trim();
        String[][] wrappers = new String[][]{
                {"\\[", "\\]"}, {"$$", "$$"}, {"\\(", "\\)"}, {"$", "$"}
        };
        for (String[] wrapper : wrappers) {
            if (trimmed.startsWith(wrapper[0]) && trimmed.endsWith(wrapper[1]) &&
                    trimmed.length() >= wrapper[0].length() + wrapper[1].length()) {
                return trimmed.substring(wrapper[0].length(),
                        trimmed.length() - wrapper[1].length()).trim();
            }
        }
        return trimmed;
    }

    private static String markdownToHtml(String source) {
        return markdownToHtml(source, false);
    }

    /**
     * Converts the supported Markdown subset to HTML.
     *
     * @param mermaidFences when true, a {@code ```mermaid} fence becomes
     *                      {@code <pre class="mermaid">} for the bundled diagram
     *                      renderer instead of a plain code block. Flow fragments
     *                      always pass false: their pagination estimator mirrors
     *                      that output exactly.
     */
    private static String markdownToHtml(String source, boolean mermaidFences) {
        StringBuilder html = new StringBuilder();
        StringBuilder code = new StringBuilder();
        boolean fencedCode = false;
        boolean mermaidBlock = false;
        boolean unorderedList = false;
        boolean orderedList = false;
        String normalizedSource = flattenDisplayMathBlocks(
                source.replace("\r\n", "\n").replace('\r', '\n'));
        String[] lines = normalizedSource.split("\n", -1);
        for (String line : lines) {
            if (line.trim().startsWith("```")) {
                if (fencedCode) {
                    if (mermaidBlock) {
                        html.append("<pre class=\"mermaid\">").append(escapeHtml(code.toString()))
                                .append("</pre>");
                        mermaidBlock = false;
                    } else {
                        html.append("<pre><code>").append(escapeHtml(code.toString()))
                                .append("</code></pre>");
                    }
                    code.setLength(0);
                } else {
                    if (unorderedList) {
                        html.append("</ul>");
                        unorderedList = false;
                    }
                    if (orderedList) {
                        html.append("</ol>");
                        orderedList = false;
                    }
                    mermaidBlock = mermaidFences
                            && "mermaid".equalsIgnoreCase(line.trim().substring(3).trim());
                }
                fencedCode = !fencedCode;
                continue;
            }
            if (fencedCode) {
                if (code.length() > 0) {
                    code.append('\n');
                }
                code.append(line);
                continue;
            }

            Matcher unordered = UNORDERED.matcher(line);
            Matcher ordered = ORDERED.matcher(line);
            boolean isUnordered = unordered.matches();
            boolean isOrdered = ordered.matches();
            if (!isUnordered && unorderedList) {
                html.append("</ul>");
                unorderedList = false;
            }
            if (!isOrdered && orderedList) {
                html.append("</ol>");
                orderedList = false;
            }
            if (isUnordered) {
                if (!unorderedList) {
                    html.append("<ul>");
                    unorderedList = true;
                }
                html.append("<li>").append(markdownInline(unordered.group(1))).append("</li>");
                continue;
            }
            if (isOrdered) {
                if (!orderedList) {
                    // A flow may be split between any two list items, and blank
                    // lines intentionally close a list run. Preserve the first
                    // marker of every run so a later fragment does not restart
                    // numbered steps at 1.
                    html.append("<ol start=\"").append(ordered.group(1)).append("\">");
                    orderedList = true;
                }
                html.append("<li>").append(markdownInline(ordered.group(2))).append("</li>");
                continue;
            }
            if (line.trim().isEmpty()) {
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                int level = heading.group(1).length();
                html.append("<h").append(level).append('>')
                        .append(markdownInline(heading.group(2)))
                        .append("</h").append(level).append('>');
            } else if (line.startsWith(">")) {
                html.append("<blockquote>")
                        .append(markdownInline(line.substring(1).trim()))
                        .append("</blockquote>");
            } else {
                html.append("<p>").append(markdownInline(line)).append("</p>");
            }
        }
        if (fencedCode) {
            if (mermaidBlock) {
                html.append("<pre class=\"mermaid\">").append(escapeHtml(code.toString()))
                        .append("</pre>");
            } else {
                html.append("<pre><code>").append(escapeHtml(code.toString())).append("</code></pre>");
            }
        }
        if (unorderedList) {
            html.append("</ul>");
        }
        if (orderedList) {
            html.append("</ol>");
        }
        if (html.length() == 0) {
            return "<p></p>";
        }
        return html.toString();
    }

    private static String flattenDisplayMathBlocks(String source) {
        return flattenDelimitedBlock(flattenDelimitedBlock(source, "\\[", "\\]"),
                "$$", "$$");
    }

    private static String flattenDelimitedBlock(String source, String open, String close) {
        StringBuilder normalized = new StringBuilder();
        int cursor = 0;
        while (cursor < source.length()) {
            int openIndex = findUnescaped(source, open, cursor);
            if (openIndex < 0) {
                normalized.append(source.substring(cursor));
                break;
            }
            int contentStart = openIndex + open.length();
            int closeIndex = findUnescaped(source, close, contentStart);
            if (closeIndex < 0) {
                normalized.append(source.substring(cursor));
                break;
            }
            normalized.append(source, cursor, contentStart);
            String content = source.substring(contentStart, closeIndex)
                    .replaceAll("\\s*\\n\\s*", " ");
            normalized.append(content).append(close);
            cursor = closeIndex + close.length();
        }
        return normalized.toString();
    }

    private static String markdownInline(String value) {
        StringBuilder html = new StringBuilder();
        int cursor = 0;
        while (cursor < value.length()) {
            MathDelimiter delimiter = nextMathDelimiter(value, cursor);
            if (delimiter == null) {
                html.append(markdownPlain(value.substring(cursor)));
                break;
            }
            int formulaStart = delimiter.start + delimiter.open.length();
            int formulaEnd = findUnescaped(value, delimiter.close, formulaStart);
            if (formulaEnd < 0) {
                html.append(markdownPlain(value.substring(cursor)));
                break;
            }
            html.append(markdownPlain(value.substring(cursor, delimiter.start)));
            String tag = delimiter.display ? "div" : "span";
            html.append('<').append(tag).append(" class=\"")
                    .append(delimiter.display ? "math-display" : "math-inline")
                    .append("\" data-display=\"").append(delimiter.display ? '1' : '0')
                    .append("\" data-tex=\"")
                    .append(escapeHtml(value.substring(formulaStart, formulaEnd).trim()))
                    .append("\"></").append(tag).append('>');
            cursor = formulaEnd + delimiter.close.length();
        }
        return html.toString();
    }

    private static String markdownPlain(String value) {
        String escaped = escapeHtml(value);
        escaped = escaped.replaceAll("`([^`]+)`", "<code>$1</code>");
        escaped = escaped.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        escaped = escaped.replaceAll("__([^_]+)__", "<strong>$1</strong>");
        escaped = escaped.replaceAll("~~([^~]+)~~", "<del>$1</del>");
        escaped = escaped.replaceAll("\\*([^*]+)\\*", "<em>$1</em>");
        escaped = escaped.replaceAll("_([^_]+)_", "<em>$1</em>");
        escaped = escaped.replaceAll("\\[([^]]+)]\\([^)]+\\)",
                "<span class=\"md-link\">$1</span>");
        return escaped;
    }

    private static final class MathDelimiter {
        final int start;
        final String open;
        final String close;
        final boolean display;

        MathDelimiter(int start, String open, String close, boolean display) {
            this.start = start;
            this.open = open;
            this.close = close;
            this.display = display;
        }
    }

    private static MathDelimiter nextMathDelimiter(String value, int from) {
        String[][] pairs = new String[][]{
                {"\\[", "\\]", "1"}, {"\\(", "\\)", "0"},
                {"$$", "$$", "1"}, {"$", "$", "0"}
        };
        MathDelimiter best = null;
        for (String[] pair : pairs) {
            int start = findUnescaped(value, pair[0], from);
            if (start >= 0 && (best == null || start < best.start ||
                    (start == best.start && pair[0].length() > best.open.length()))) {
                best = new MathDelimiter(start, pair[0], pair[1], "1".equals(pair[2]));
            }
        }
        return best;
    }

    private static int findUnescaped(String value, String target, int from) {
        int cursor = Math.max(0, from);
        while (cursor <= value.length() - target.length()) {
            int found = value.indexOf(target, cursor);
            if (found < 0) {
                return -1;
            }
            int slashes = 0;
            for (int index = found - 1; index >= 0 && value.charAt(index) == '\\'; index--) {
                slashes += 1;
            }
            if (slashes % 2 == 0) {
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
}
