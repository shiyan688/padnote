package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Device-level layout regressions for the offline paper renderer. */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class PaperLayoutTest {
    private static final String TAG = "PaperLayoutTest";
    private static final int PAPER_WIDTH_DP = 336;
    private static final long READY_TIMEOUT_MS = 19_000L;
    private static final float EDGE_TOLERANCE_CSS_PX = 2f;

    private static final String LONG_FORMULA =
            "# 长公式排版回归\n\n" +
            "对十八项观测值作归一化，先写出完整推导：\n\n" +
            "$$\\displaystyle S_{18}=" +
            "\\frac{a_1}{1+b_1^2}+\\frac{a_2}{1+b_2^2}+" +
            "\\frac{a_3}{1+b_3^2}+\\frac{a_4}{1+b_4^2}+" +
            "\\frac{a_5}{1+b_5^2}+\\frac{a_6}{1+b_6^2}+" +
            "\\frac{a_7}{1+b_7^2}+\\frac{a_8}{1+b_8^2}+" +
            "\\frac{a_9}{1+b_9^2}+\\frac{a_{10}}{1+b_{10}^2}+" +
            "\\frac{a_{11}}{1+b_{11}^2}+\\frac{a_{12}}{1+b_{12}^2}+" +
            "\\frac{a_{13}}{1+b_{13}^2}+\\frac{a_{14}}{1+b_{14}^2}+" +
            "\\frac{a_{15}}{1+b_{15}^2}+\\frac{a_{16}}{1+b_{16}^2}+" +
            "\\frac{a_{17}}{1+b_{17}^2}+\\frac{a_{18}}{1+b_{18}^2}$$\n\n" +
            "再令 $\\bar{x}=S_{18}/18$，代回方差公式。\n\n" +
            "因此每一项都应留在纸面内部，末项和下方文字也必须可见。";

    private static final String TALL_MERMAID =
            "# 纵向流程图回归\n\n" +
            "下面五个步骤必须完整显示：\n\n" +
            "```mermaid\n" +
            "flowchart TD\n" +
            "    A[采集原始数据] --> B[清洗与归一化]\n" +
            "    B --> C[建立十八项求和模型]\n" +
            "    C --> D[检查边界与分页]\n" +
            "    D --> E[输出最终笔记]\n" +
            "```\n\n" +
            "流程图之后的文字也不能被裁掉。";

    @Test
    public void aLongFormulaKeepsEveryRenderedBaseInside336DpPaper() throws Exception {
        try (RendererSession session = RendererSession.open(LONG_FORMULA, 640)) {
            JSONObject ready = awaitRendererReady(session.webView);
            assertTrue("renderer did not publish a positive settled height",
                    ready.getDouble("height") > 0d);

            JSONObject bounds = evaluateJson(session.webView,
                    "(function(){" +
                    "var content=document.querySelector('.content');" +
                    "var bases=Array.prototype.slice.call(document.querySelectorAll(" +
                    "'.latex-root .katex-html>.base,.math-display .katex-html>.base'));" +
                    "var displayBases=document.querySelectorAll('.math-display .katex-html>.base');" +
                    "var leftOverflow=0,rightOverflow=0,visibleRects=0;" +
                    "bases.forEach(function(base){" +
                    "var owner=base.closest('.latex-root,.math-display');" +
                    "var limit=owner.getBoundingClientRect();" +
                    "var nodes=[base].concat(Array.prototype.slice.call(base.querySelectorAll('*')));" +
                    "nodes.forEach(function(node){var style=getComputedStyle(node);" +
                    "var r=node.getBoundingClientRect();" +
                    "if(style.display==='none'||style.visibility==='hidden'||r.width<=0||r.height<=0)return;" +
                    "visibleRects++;leftOverflow=Math.max(leftOverflow,limit.left-r.left);" +
                    "rightOverflow=Math.max(rightOverflow,r.right-limit.right);});});" +
                    "return {href:location.href,ready:window.__padnoteReady===true," +
                    "height:Number(window.__padnoteHeight||0),viewportWidth:innerWidth," +
                    "documentWidth:document.documentElement.scrollWidth,contentWidth:content.scrollWidth," +
                    "baseCount:bases.length,displayBaseCount:displayBases.length,visibleRects:visibleRects," +
                    "leftOverflow:leftOverflow,rightOverflow:rightOverflow};})()");

            assertTrue(bounds.getBoolean("ready"));
            assertTrue("renderer must remain on packaged assets",
                    bounds.getString("href").startsWith("file:///android_asset/katex/"));
            assertEquals(PAPER_WIDTH_DP, bounds.getDouble("viewportWidth"), 1.5d);
            assertTrue("fixture did not render any KaTeX base", bounds.getInt("baseCount") > 0);
            assertTrue("the eighteen-term display formula did not render",
                    bounds.getInt("displayBaseCount") > 0);
            assertTrue("fixture did not expose rendered KaTeX glyph bounds",
                    bounds.getInt("visibleRects") > bounds.getInt("baseCount"));
            assertTrue("KaTeX glyphs crossed the left paper edge: " + bounds,
                    bounds.getDouble("leftOverflow") <= EDGE_TOLERANCE_CSS_PX);
            assertTrue("KaTeX glyphs crossed the right paper edge: " + bounds,
                    bounds.getDouble("rightOverflow") <= EDGE_TOLERANCE_CSS_PX);
            assertTrue("formula introduced horizontal document scrolling: " + bounds,
                    bounds.getDouble("documentWidth") <= bounds.getDouble("viewportWidth") +
                            EDGE_TOLERANCE_CSS_PX);
            assertTrue("settled height omitted rendered content: " + bounds,
                    bounds.getDouble("height") > 120d);

            captureScreenshot(session.webView);
        }
    }

    @Test
    public void bTallMermaidPreservesViewBoxAndFitsWholeDiagram() throws Exception {
        // A short viewport deliberately exercises the height-fit path. The native
        // paginator may later allocate __padnoteHeight, but this first frame must
        // already show the complete SVG rather than clip its tail.
        try (RendererSession session = RendererSession.open(TALL_MERMAID, 320)) {
            awaitRendererReady(session.webView);
            JSONObject diagram = evaluateJson(session.webView,
                    "(function(){" +
                    "var svg=document.querySelector('pre.mermaid svg');" +
                    "var pre=document.querySelector('pre.mermaid');" +
                    "var content=document.querySelector('.content');" +
                    "if(!svg||!pre)return {found:false};" +
                    "var r=svg.getBoundingClientRect(),p=pre.getBoundingClientRect();" +
                    "var vb=svg.viewBox&&svg.viewBox.baseVal;" +
                    "var vbWidth=vb?vb.width:0,vbHeight=vb?vb.height:0;" +
                    "var viewRatio=vbHeight>0?vbWidth/vbHeight:0;" +
                    "var shownRatio=r.height>0?r.width/r.height:0;" +
                    "return {found:true,ready:window.__padnoteReady===true," +
                    "height:Number(window.__padnoteHeight||0),contentHeight:content.scrollHeight," +
                    "viewportWidth:innerWidth,viewportHeight:innerHeight," +
                    "viewBoxWidth:vbWidth,viewBoxHeight:vbHeight,shownWidth:r.width,shownHeight:r.height," +
                    "ratioError:viewRatio>0?Math.abs(shownRatio-viewRatio)/viewRatio:1," +
                    "leftOverflow:p.left-r.left,rightOverflow:r.right-p.right," +
                    "top:r.top,bottom:r.bottom,nodeCount:svg.querySelectorAll('g.node').length," +
                    "preserve:svg.getAttribute('preserveAspectRatio')||''};})()");

            assertTrue("Mermaid did not produce an SVG: " + diagram, diagram.getBoolean("found"));
            assertTrue(diagram.getBoolean("ready"));
            assertEquals(PAPER_WIDTH_DP, diagram.getDouble("viewportWidth"), 1.5d);
            assertTrue("Mermaid viewBox is empty: " + diagram,
                    diagram.getDouble("viewBoxWidth") > 0d && diagram.getDouble("viewBoxHeight") > 0d);
            assertTrue("Mermaid SVG has no displayed area: " + diagram,
                    diagram.getDouble("shownWidth") > 0d && diagram.getDouble("shownHeight") > 0d);
            assertEquals("xMidYMid meet", diagram.getString("preserve"));
            assertTrue("displayed SVG distorted its viewBox ratio: " + diagram,
                    diagram.getDouble("ratioError") < 0.03d);
            assertTrue("diagram crossed the left edge: " + diagram,
                    diagram.getDouble("leftOverflow") <= EDGE_TOLERANCE_CSS_PX);
            assertTrue("diagram crossed the right edge: " + diagram,
                    diagram.getDouble("rightOverflow") <= EDGE_TOLERANCE_CSS_PX);
            assertTrue("diagram top was clipped: " + diagram,
                    diagram.getDouble("top") >= -EDGE_TOLERANCE_CSS_PX);
            assertTrue("diagram tail was clipped by the WebView: " + diagram,
                    diagram.getDouble("bottom") <= diagram.getDouble("viewportHeight") +
                            EDGE_TOLERANCE_CSS_PX);
            assertTrue("expected all five Mermaid nodes: " + diagram,
                    diagram.getInt("nodeCount") >= 5);
            assertTrue("settled height must include the full paper content: " + diagram,
                    diagram.getDouble("height") >= diagram.getDouble("contentHeight"));

            captureScreenshot(session.webView);
        }
    }

    @Test
    public void cNativePaginatorKeepsShortDiagramAndFollowingTextOnTheSamePaper() {
        String source = "# 同页标题\n\n" +
                "```mermaid\nflowchart LR\nA[开始]-->B[完成]\n```\n\n" +
                "图后的正文仍应接在同一张纸上。";
        try (CanvasSession session = CanvasSession.open()) {
            AtomicReference<List<NoteTextBox>> result = new AtomicReference<>();
            session.scenario.onActivity(activity -> {
                session.canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN, source,
                        dp(activity, 16), dp(activity, 16));
                result.set(session.canvas.getTextBoxes());
            });

            NoteTextBox heading = fragmentStarting(result.get(), "# 同页标题");
            NoteTextBox diagram = fragmentStarting(result.get(), "```mermaid");
            NoteTextBox following = fragmentStarting(result.get(), "图后的正文");
            assertNotNull(heading);
            assertNotNull(diagram);
            assertNotNull(following);
            assertEquals(heading.pageIndex, diagram.pageIndex);
            assertEquals(diagram.pageIndex, following.pageIndex);
            assertTrue("heading overlaps diagram", heading.y + heading.height <= diagram.y + 1f);
            assertTrue("diagram overlaps following text",
                    diagram.y + diagram.height <= following.y + 1f);
        }
    }

    @Test
    public void dNativePaginatorKeepsDifferentDiagramMeasurementsIndependent() {
        String shortDiagram = "```mermaid\nflowchart LR\nA-->B\n```";
        String tallDiagram = "```mermaid\nflowchart TD\nA-->B\nB-->C\nC-->D\nD-->E\n```";
        try (CanvasSession session = CanvasSession.open()) {
            AtomicReference<List<NoteTextBox>> result = new AtomicReference<>();
            session.scenario.onActivity(activity -> {
                session.canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                        shortDiagram + "\n\n" + tallDiagram,
                        dp(activity, 16), dp(activity, 16));
                NoteTextBox shortBox = fragmentStarting(session.canvas.getTextBoxes(), shortDiagram);
                assertNotNull(shortBox);
                session.canvas.applyMeasuredFragmentHeight(shortBox.id, 120f);
                NoteTextBox tallBox = fragmentStarting(session.canvas.getTextBoxes(), tallDiagram);
                assertNotNull(tallBox);
                session.canvas.applyMeasuredFragmentHeight(tallBox.id, 440f);
                result.set(session.canvas.getTextBoxes());
            });

            NoteTextBox shortBox = fragmentStarting(result.get(), shortDiagram);
            NoteTextBox tallBox = fragmentStarting(result.get(), tallDiagram);
            assertNotNull(shortBox);
            assertNotNull(tallBox);
            assertEquals(shortBox.pageIndex, tallBox.pageIndex);
            assertTrue("short diagram inherited tall diagram reservation",
                    shortBox.height * 2f < tallBox.height);
            assertTrue("diagram fragments overlap", shortBox.y + shortBox.height <= tallBox.y + 1f);
        }
    }

    @Test
    public void eNativePaginatorMovesPageTailHeadingTogetherWithDiagram() {
        String source = "# 页尾标题\n\n```mermaid\nflowchart LR\nA-->B\n```";
        try (CanvasSession session = CanvasSession.open()) {
            AtomicReference<List<NoteTextBox>> result = new AtomicReference<>();
            session.scenario.onActivity(activity -> {
                try {
                    float pageHeight = (float) session.canvas.toJsonDocument()
                            .optDouble("pageHeight", dp(activity, 900));
                    session.canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN, source,
                            dp(activity, 16), pageHeight - dp(activity, 220));
                    result.set(session.canvas.getTextBoxes());
                } catch (Exception error) {
                    throw new AssertionError("could not prepare page-tail fixture", error);
                }
            });

            NoteTextBox heading = fragmentStarting(result.get(), "# 页尾标题");
            NoteTextBox diagram = fragmentStarting(result.get(), "```mermaid");
            assertNotNull(heading);
            assertNotNull(diagram);
            assertTrue("fixture did not force the pair to the following page",
                    heading.pageIndex > 0);
            assertEquals("heading was orphaned from its diagram",
                    heading.pageIndex, diagram.pageIndex);
            assertTrue(heading.y + heading.height <= diagram.y + 1f);
        }
    }

    private static NoteTextBox fragmentStarting(List<NoteTextBox> fragments, String prefix) {
        if (fragments == null) return null;
        for (NoteTextBox fragment : fragments) {
            if (fragment.fragmentSource != null && fragment.fragmentSource.startsWith(prefix)) {
                return fragment;
            }
        }
        return null;
    }

    private static JSONObject awaitRendererReady(WebView webView) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();
        AtomicReference<String> lastValue = new AtomicReference<>("<no callback>");
        long deadline = SystemClock.uptimeMillis() + READY_TIMEOUT_MS - 250L;

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Runnable[] poll = new Runnable[1];
            poll[0] = () -> webView.evaluateJavascript(
                    "(function(){return {ready:window.__padnoteReady===true," +
                            "height:Number(window.__padnoteHeight||0)," +
                            "fonts:!document.fonts||document.fonts.status==='loaded'};})()",
                    value -> {
                        lastValue.set(value);
                        try {
                            JSONObject state = new JSONObject(value);
                            if (state.optBoolean("ready") && state.optBoolean("fonts") &&
                                    state.optDouble("height", 0d) > 0d) {
                                result.set(state);
                                finished.countDown();
                                return;
                            }
                        } catch (Exception ignored) {
                            // Navigation can briefly return null before the asset page exists.
                        }
                        if (SystemClock.uptimeMillis() < deadline && webView.isAttachedToWindow()) {
                            webView.postDelayed(poll[0], 50L);
                        } else {
                            finished.countDown();
                        }
                    });
            poll[0].run();
        });

        assertTrue("renderer readiness exceeded 19 seconds; last value=" + lastValue.get(),
                finished.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertNotNull("renderer never became ready; last value=" + lastValue.get(), result.get());
        return result.get();
    }

    private static JSONObject evaluateJson(WebView webView, String script) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                webView.evaluateJavascript(script, result -> {
                    value.set(result);
                    finished.countDown();
                }));
        assertTrue("JavaScript evaluation timed out", finished.await(3, TimeUnit.SECONDS));
        assertNotNull("JavaScript evaluation returned null", value.get());
        return new JSONObject(value.get());
    }

    private static void captureScreenshot(WebView webView) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(120L);
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .takeScreenshot();
        if (bitmap == null) {
            AtomicReference<Bitmap> fallback = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Bitmap drawn = Bitmap.createBitmap(webView.getWidth(), webView.getHeight(),
                        Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(drawn);
                canvas.drawColor(Color.WHITE);
                webView.draw(canvas);
                fallback.set(drawn);
            });
            bitmap = fallback.get();
        }
        assertNotNull("could not capture WebView screenshot", bitmap);

        File output = new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir(), "paper-layout.png");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue("PNG encoder failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally {
            bitmap.recycle();
        }
        Log.i(TAG, "layout screenshot: " + output.getAbsolutePath());
        assertTrue("screenshot was not written", output.isFile() && output.length() > 0L);
    }

    private static int dp(MainActivity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class RendererSession implements AutoCloseable {
        final ActivityScenario<MainActivity> scenario;
        final CompiledTextWebView webView;

        private RendererSession(ActivityScenario<MainActivity> scenario,
                                CompiledTextWebView webView) {
            this.scenario = scenario;
            this.webView = webView;
        }

        static RendererSession open(String markdown, int heightDp) {
            ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
            AtomicReference<CompiledTextWebView> view = new AtomicReference<>();
            try {
                scenario.onActivity(activity -> {
                    FrameLayout host = new FrameLayout(activity);
                    host.setBackgroundColor(Color.WHITE);
                    CompiledTextWebView renderer = new CompiledTextWebView(
                            activity, NoteTextBox.Format.MARKDOWN, markdown);
                    assertTrue("paper renderer must block network loads",
                            renderer.getSettings().getBlockNetworkLoads());
                    assertFalse("paper renderer must not expose content providers",
                            renderer.getSettings().getAllowContentAccess());
                    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                            dp(activity, PAPER_WIDTH_DP), dp(activity, heightDp));
                    params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    host.addView(renderer, params);
                    activity.setContentView(host, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                    view.set(renderer);
                });
                assertNotNull("MainActivity did not attach the renderer", view.get());
                return new RendererSession(scenario, view.get());
            } catch (RuntimeException | Error error) {
                scenario.close();
                throw error;
            }
        }

        @Override
        public void close() {
            scenario.close();
        }
    }

    /** Real Android View geometry without involving the editor overlay layer. */
    private static final class CanvasSession implements AutoCloseable {
        final ActivityScenario<MainActivity> scenario;
        final NoteCanvasView canvas;

        private CanvasSession(ActivityScenario<MainActivity> scenario, NoteCanvasView canvas) {
            this.scenario = scenario;
            this.canvas = canvas;
        }

        static CanvasSession open() {
            ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
            AtomicReference<NoteCanvasView> view = new AtomicReference<>();
            try {
                scenario.onActivity(activity -> {
                    int width = dp(activity, 768);
                    int height = dp(activity, 1024);
                    FrameLayout host = new FrameLayout(activity);
                    NoteCanvasView canvas = new NoteCanvasView(activity);
                    host.addView(canvas, new FrameLayout.LayoutParams(width, height));
                    activity.setContentView(host, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                    canvas.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    canvas.layout(0, 0, width, height);
                    view.set(canvas);
                });
                assertNotNull("MainActivity did not attach the canvas", view.get());
                return new CanvasSession(scenario, view.get());
            } catch (RuntimeException | Error error) {
                scenario.close();
                throw error;
            }
        }

        @Override
        public void close() {
            scenario.close();
        }
    }
}
