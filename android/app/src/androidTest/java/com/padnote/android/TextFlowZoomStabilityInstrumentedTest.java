package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real TextFlow/WebView regression for display-only canvas zoom. */
@RunWith(AndroidJUnit4.class)
public final class TextFlowZoomStabilityInstrumentedTest {
    private static final long READY_TIMEOUT_MS = 25_000L;
    private static final float[] SCALES = {0.75f, 1.35f, 2f, 1f};

    @Test
    public void multiPageFlowKeepsCanonicalLinesGeometryAndSourceAcrossZoomAndRecreation()
            throws Exception {
        try (Session session = Session.open()) {
            AtomicReference<String> flowId = new AtomicReference<>();
            AtomicReference<String> fullSource = new AtomicReference<>();
            session.scenario.onActivity(activity -> {
                String source = sourceFixture();
                NoteTextBox first = session.canvas.addTextBoxAt(
                        NoteTextBox.Format.MARKDOWN, source, dp(activity, 16), dp(activity, 16));
                assertNotNull("canvas did not create the text flow", first);
                List<NoteTextBox> fragments = fragments(session.canvas, first.flowId);
                assertTrue("fixture should paginate to at least four fragments: " + fragments.size(),
                        fragments.size() >= 4);
                flowId.set(first.flowId);
                fullSource.set(source);
                for (NoteTextBox box : fragments) {
                    assertEquals("every page fragment retains the complete author source",
                            source, box.source);
                }
            });

            // Warm the first, middle, and last real page fragments at canonical scale. This
            // preserves the first genuine WebView height correction before testing zoom.
            warmCanonicalSamples(session, flowId.get());

            List<String> baselineFlow = flowSnapshot(readFragments(session, flowId.get()));
            List<NoteTextBox> baselineFragments = readFragments(session, flowId.get());
            List<String> sampleIds = sampleIds(baselineFragments);
            List<String> baselineDom = new ArrayList<>();
            for (String id : sampleIds) {
                NoteTextBox box = find(readFragments(session, flowId.get()), id);
                try (Bound bound = bind(session, box, 1f)) {
                    awaitReady(bound.webView);
                    baselineDom.add(domSnapshot(bound.webView).toString());
                }
            }
            baselineFlow = flowSnapshot(readFragments(session, flowId.get()));
            List<String> baselineAuthorFlow = readFlowMetadata(session);

            // A single already-rendered WebView must also survive successive zooms
            // beyond the old 140 ms rerender delay. This detects delayed scale-only
            // recompilation that a recreate-per-scale test cannot see.
            String continuousId = sampleIds(readFragments(session, flowId.get())).get(0);
            NoteTextBox continuousModel = find(readFragments(session, flowId.get()), continuousId);
            try (Bound continuous = bind(session, continuousModel, 1f)) {
                awaitReady(continuous.webView);
                String continuousBaseline = domSnapshot(continuous.webView).toString();
                for (float scale : SCALES) {
                    NoteTextBox current = find(readFragments(session, flowId.get()), continuousId);
                    session.scenario.onActivity(activity -> continuous.view.bind(current, scale,
                            dp(activity, 12) - current.x * scale,
                            dp(activity, 12) - current.y * scale, false));
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    SystemClock.sleep(220L);
                    assertEquals("same WebView must keep canonical DOM after settled zoom " + scale,
                            continuousBaseline, domSnapshot(continuous.webView).toString());
                    assertEquals("same WebView zoom must not mutate the settled flow",
                            baselineFlow, flowSnapshot(readFragments(session, flowId.get())));
                    assertEquals("same WebView zoom must preserve authored style and anchor",
                            baselineAuthorFlow, readFlowMetadata(session));
                    if (scale == 1f) saveScreenshot(continuous.webView, "text-flow-zoom-1x.png");
                    if (scale == 2f) saveScreenshot(continuous.webView, "text-flow-zoom-2x.png");
                }
                NoteTextBox replacementModel = find(readFragments(session, flowId.get()), continuousId);
                AtomicReference<WebView> replacementRef = new AtomicReference<>();
                AtomicReference<CompiledTextWebView> goneRef = new AtomicReference<>();
                session.scenario.onActivity(activity -> {
                    continuous.view.bind(replacementModel, 2f,
                            dp(activity, 12) - replacementModel.x * 2f,
                            dp(activity, 12) - replacementModel.y * 2f, false);
                    CompiledTextWebView old = (CompiledTextWebView) childWebView(continuous.view);
                    goneRef.set(old);
                    old.simulateRenderProcessGoneForTest();
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                session.scenario.onActivity(activity -> {
                    try {
                        assertTrue("test hook should mark old renderer process gone",
                                goneRef.get().isProcessGone());
                        WebView replacement = childWebView(continuous.view);
                        assertNotNull("process-gone callback should replace the child", replacement);
                        assertTrue("replacement must be a new WebView instance",
                                replacement != goneRef.get());
                        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) replacement.getLayoutParams();
                        assertEquals("replacement child gets canonical backing width",
                                Math.round(replacementModel.width), params.width);
                        assertEquals("replacement child keeps current display scale", 2f,
                                replacement.getScaleX(), 0.001f);
                        replacementRef.set(replacement);
                        java.lang.reflect.Method retry = NoteTextBoxView.class
                                .getDeclaredMethod("retryCompiledRender");
                        retry.setAccessible(true);
                        retry.invoke(continuous.view);
                    } catch (Exception error) {
                        throw new AssertionError("simulated renderer replacement/retry failed", error);
                    }
                });
                awaitReady(replacementRef.get());
                assertEquals("replacement keeps identical canonical DOM",
                        continuousBaseline, domSnapshot(replacementRef.get()).toString());
            }

            for (float scale : SCALES) {
                for (int i = 0; i < sampleIds.size(); i++) {
                    NoteTextBox box = find(readFragments(session, flowId.get()), sampleIds.get(i));
                    try (Bound bound = bind(session, box, scale)) {
                        awaitReady(bound.webView);
                        JSONObject state = domSnapshot(bound.webView);
                        assertEquals("DOM line rectangles/height/CSS viewport must be canonical at scale "
                                + scale, baselineDom.get(i), state.toString());
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                        assertEquals("layout/source/page geometry changed at zoom " + scale,
                                baselineFlow, flowSnapshot(readFragments(session, flowId.get())));
                        assertEquals("zoom must not alter the flow's authored style/anchor",
                                baselineAuthorFlow, readFlowMetadata(session));
                        assertEquals("WebView backing layout width stays in paper pixels",
                                Math.round(box.width), bound.webView.getWidth());
                        assertEquals("only the view transform follows canvas zoom",
                                scale, bound.webView.getScaleX(), 0.001f);
                    }
                }
            }

            assertEquals("source object remains unchanged", fullSource.get(),
                    find(readFragments(session, flowId.get()), sampleIds.get(0)).source);
            assertEquals("zoom leaves settled fragment count unchanged", baselineFlow.size(),
                    readFragments(session, flowId.get()).size());

            AtomicReference<List<String>> savedAuthorState = new AtomicReference<>();
            AtomicReference<List<String>> originalAuthorState = new AtomicReference<>();
            session.scenario.onActivity(activity -> {
                try {
                    originalAuthorState.set(flowMetadataSnapshot(session.canvas.getTextFlows()));
                    JSONObject roundTrip = new JSONObject(NoteJsonCodec.stringify(
                            session.canvas.toJsonDocument("zoom-stability", "zoom stability")));
                    NoteCanvasView reopened = new NoteCanvasView(activity);
                    reopened.loadJsonDocument(roundTrip);
                    savedAuthorState.set(flowMetadataSnapshot(reopened.getTextFlows()));
                } catch (Exception error) {
                    throw new AssertionError("note JSON round-trip failed", error);
                }
            });
            assertEquals("persisted flow source/style/anchor must survive a real note JSON reopen",
                    originalAuthorState.get(), savedAuthorState.get());
        }
    }

    @Test
    public void inlineEditorKeepsPreviewBelowToolbarAndCancelRestoresCanonicalViewport()
            throws Exception {
        try (Session session = Session.open()) {
            AtomicReference<NoteTextBox> model = new AtomicReference<>();
            session.scenario.onActivity(activity -> {
                NoteTextBox box = session.canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                        "编辑预览\n\nA stable preview line.", dp(activity, 16), dp(activity, 16));
                assertNotNull(box); model.set(box);
            });
            try (Bound bound = bind(session, model.get(), 1f)) {
                awaitReady(bound.webView);
                session.scenario.onActivity(activity -> {
                    bound.view.beginInlineEditing();
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                            bound.webView.getLayoutParams();
                    assertTrue("inline preview must remain below the editor toolbar",
                            params.topMargin > 0);
                    assertEquals("editable preview stays at readable screen scale", 1f,
                            bound.webView.getScaleX(), 0.001f);
                    bound.view.bind(model.get(), 1.35f, dp(activity, 12), dp(activity, 12), true);
                    FrameLayout.LayoutParams rebound = (FrameLayout.LayoutParams)
                            bound.webView.getLayoutParams();
                    assertTrue("zoom bind must keep the editor preview below its toolbar",
                            rebound.topMargin > 0);
                    assertTrue(rebound.height > 0);
                    assertEquals("zoom bind must not shrink the editor preview", 1f,
                            bound.webView.getScaleY(), 0.001f);
                    bound.view.cancelInlineEditing();
                    FrameLayout.LayoutParams restored = (FrameLayout.LayoutParams)
                            bound.webView.getLayoutParams();
                    assertEquals("finished paper render returns to zero top margin", 0,
                            restored.topMargin);
                    assertEquals("finished paper render uses canonical model width",
                            Math.round(model.get().width), restored.width);
                    assertEquals("finished paper render follows display zoom only by view scale",
                            1.35f, bound.webView.getScaleX(), 0.001f);
                    bound.view.beginInlineEditing();
                    bound.view.commitInlineEditing();
                    FrameLayout.LayoutParams committed = (FrameLayout.LayoutParams)
                            bound.webView.getLayoutParams();
                    assertEquals("Done exits editor preview back to paper origin", 0,
                            committed.topMargin);
                    assertEquals("Done preserves canonical paper width", Math.round(model.get().width),
                            committed.width);
                    assertEquals("Done keeps current display zoom", 1.35f,
                            bound.webView.getScaleX(), 0.001f);
                });
            }
        }
    }

    private static String sourceFixture() {
        StringBuilder source = new StringBuilder("# 中英文长文分页与缩放\n\n");
        source.append("同一段回答应保留完整原文。A long English sentence uses predictable words and punctuation, "
                + "and 中文内容需要在窄纸面边界处自然换行。这里加入接近行宽的混合文本：" 
                + "PadNote-zoom-boundary-0123456789 与公式 $\\sum_{i=1}^{12} x_i^2$。\n\n");
        source.append("```java\npublic final class StableLayout {\n"
                + "  static double value(int index) { return index * 1.25; }\n"
                + "}\n```\n\n");
        for (int i = 0; i < 90; i++) {
            source.append("第").append(i + 1).append("段：先观察纸面宽度，再比较 zoom 0.75、1.35、2.0 和 1.0。")
                    .append("The line should wrap at the same canonical positions after the view is recreated. ")
                    .append("中文短句与英文句子交替出现，行尾附近加入一段长标识：stable-flow-boundary-")
                    .append(String.format(Locale.ROOT, "%03d", i))
                    .append("-abcdefghijklmno。\n\n");
        }
        source.append("## 最后片段仍可见\n\n末尾公式 $\\frac{a+b}{c+d}$ 与中文收尾段。");
        return source.toString();
    }

    private static List<String> sampleIds(List<NoteTextBox> boxes) {
        assertTrue(boxes.size() >= 4);
        List<String> ids = new ArrayList<>();
        ids.add(boxes.get(0).id);
        ids.add(boxes.get(boxes.size() / 2).id);
        ids.add(boxes.get(boxes.size() - 1).id);
        for (NoteTextBox box : boxes) {
            if (box.fragmentSource.contains("```java") && !ids.contains(box.id)) ids.add(box.id);
            if (box.fragmentSource.contains("$\\sum") && !ids.contains(box.id)) ids.add(box.id);
        }
        return ids;
    }

    private static void warmCanonicalSamples(Session session, String flowId) throws Exception {
        Set<String> warmed = new HashSet<>();
        for (int pass = 0; pass < 8; pass++) {
            List<String> targets = sampleIds(readFragments(session, flowId));
            String next = null;
            for (String target : targets) {
                if (!warmed.contains(target)) { next = target; break; }
            }
            if (next == null) return;
            NoteTextBox box = find(readFragments(session, flowId), next);
            try (Bound bound = bind(session, box, 1f)) {
                awaitReady(bound.webView);
            }
            warmed.add(next);
        }
        assertTrue("canonical measurement/reflow did not settle within the bounded warm-up",
                warmed.containsAll(sampleIds(readFragments(session, flowId))));
    }

    private static List<NoteTextBox> fragments(NoteCanvasView canvas, String flowId) {
        List<NoteTextBox> result = new ArrayList<>();
        for (NoteTextBox box : canvas.getTextBoxes()) {
            if (flowId.equals(box.flowId)) result.add(box);
        }
        result.sort((a, b) -> Integer.compare(a.flowIndex, b.flowIndex));
        return result;
    }

    private static List<NoteTextBox> readFragments(Session session, String flowId) {
        AtomicReference<List<NoteTextBox>> result = new AtomicReference<>();
        session.scenario.onActivity(activity -> result.set(fragments(session.canvas, flowId)));
        return result.get();
    }

    private static List<String> readFlowMetadata(Session session) {
        AtomicReference<List<String>> result = new AtomicReference<>();
        session.scenario.onActivity(activity ->
                result.set(flowMetadataSnapshot(session.canvas.getTextFlows())));
        return result.get();
    }

    private static NoteTextBox find(List<NoteTextBox> boxes, String id) {
        for (NoteTextBox box : boxes) if (id.equals(box.id)) return box;
        throw new AssertionError("fragment disappeared: " + id);
    }

    private static List<String> flowSnapshot(List<NoteTextBox> boxes) {
        List<String> result = new ArrayList<>();
        for (NoteTextBox box : boxes) {
            result.add(box.id + "|" + box.flowIndex + "|" + box.flowCount + "|" + box.pageIndex
                    + "|" + box.format + "|" + Float.toString(box.fontSizeSp) + "|"
                    + Float.toString(box.lineHeight) + "|" + Float.toString(box.x) + "|" + Float.toString(box.y)
                    + "|" + Float.toString(box.width) + "|" + Float.toString(box.height)
                    + "|" + box.fragmentSource + "|" + box.source);
        }
        return result;
    }

    private static List<String> flowMetadataSnapshot(List<TextFlow> flows) {
        List<String> result = new ArrayList<>();
        for (TextFlow flow : flows) {
            result.add(flow.id + "|" + flow.format + "|" + flow.source + "|"
                    + Double.toHexString(flow.fontSizeSp64) + "|"
                    + Double.toHexString(flow.lineHeight64) + "|"
                    + Double.toHexString(flow.width64) + "|" + flow.anchorPageIndex + "|"
                    + Double.toHexString(flow.anchorXInPage64) + "|"
                    + Double.toHexString(flow.anchorYInPage64));
        }
        return result;
    }

    private static Bound bind(Session session, NoteTextBox box, float scale) {
        AtomicReference<Bound> result = new AtomicReference<>();
        session.scenario.onActivity(activity -> {
            NoteTextBoxView view = new NoteTextBoxView(activity, new NoteTextBoxView.Listener() {
                @Override public void onSelect(NoteTextBox textBox) { }
                @Override public void onEditRequested(NoteTextBox textBox) { }
                @Override public void onMove(NoteTextBox textBox, float x, float y) { }
                @Override public void onDragPreview(NoteTextBox textBox, float x, float y) { }
                @Override public void onDragPreviewEnded() { }
                @Override public void onResize(NoteTextBox textBox, float width, float height) { }
                @Override public void onScaleToArea(NoteTextBox textBox, float width, float height) { }
                @Override public void onCommit(NoteTextBox textBox, NoteTextBox.Format format,
                                               String source, float fontSize, float lineHeight) { }
                @Override public void onFontSizeChanged(NoteTextBox textBox, float fontSize) { }
                @Override public void onMeasuredHeight(NoteTextBox textBox, float heightDp) {
                    session.canvas.applyMeasuredFragmentHeight(textBox.id, heightDp);
                }
                @Override public void onCancel(NoteTextBox textBox) { }
                @Override public void onDelete(NoteTextBox textBox) { }
            });
            session.host.addView(view, new FrameLayout.LayoutParams(dp(activity, 360), dp(activity, 180)));
            view.bind(box, scale, dp(activity, 12) - box.x * scale,
                    dp(activity, 12) - box.y * scale, false);
            WebView web = childWebView(view);
            result.set(new Bound(view, web, session.host));
        });
        Bound bound = result.get();
        assertNotNull("NoteTextBoxView must own a WebView", bound);
        assertNotNull("compiled child WebView missing", bound.webView);
        return bound;
    }

    private static WebView childWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = childWebView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JSONObject domSnapshot(WebView webView) throws Exception {
        String script = "(function(){var c=document.querySelector('.content');"
                + "var walker=document.createTreeWalker(c,NodeFilter.SHOW_TEXT);var lines={};"
                + "while(walker.nextNode()){var n=walker.currentNode;if(!n.nodeValue.trim())continue;"
                + "var r=document.createRange();r.selectNodeContents(n);"
                + "Array.prototype.forEach.call(r.getClientRects(),function(x){if(x.width<=0||x.height<=0)return;"
                + "var top=Math.round(x.top*1000)/1000;var row=lines[top]||{top:top,left:x.left,right:x.right};"
                + "row.left=Math.min(row.left,x.left);row.right=Math.max(row.right,x.right);lines[top]=row;});}"
                + "return {ready:window.__padnoteReady===true,width:innerWidth,height:Number(window.__padnoteHeight||0),"
                + "font:getComputedStyle(c).fontSize,padding:getComputedStyle(c).padding,"
                + "lines:Object.keys(lines).sort(function(a,b){return Number(a)-Number(b)}).map(function(k){return lines[k]})};})()";
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                webView.evaluateJavascript(script, raw -> {
                    try { result.set(new JSONObject(raw)); } catch (Exception error) {
                        result.set(null);
                    }
                    latch.countDown();
                }));
        assertTrue("DOM geometry query timed out", latch.await(5, TimeUnit.SECONDS));
        assertNotNull("DOM geometry query returned no JSON", result.get());
        assertTrue("renderer not ready", result.get().getBoolean("ready"));
        return result.get();
    }

    private static void awaitReady(WebView webView) throws Exception {
        long deadline = SystemClock.uptimeMillis() + READY_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    webView.evaluateJavascript("Boolean(window.__padnoteReady===true&&window.__padnoteHeight>0)",
                            value -> {
                                ready.set("true".equals(value));
                                latch.countDown();
                            }));
            assertTrue("renderer readiness query timed out", latch.await(4, TimeUnit.SECONDS));
            if (ready.get()) return;
            SystemClock.sleep(100L);
        }
        throw new AssertionError("canonical renderer did not settle");
    }

    private static void saveScreenshot(WebView view, String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(250L);
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("screenshot capture failed", bitmap);
        File directory = new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir(), "android-text-zoom-stability");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File output = new File(directory, name);
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally {
            bitmap.recycle();
        }
        android.util.Log.i("TextFlowZoomStability", "screenshot=" + output.getAbsolutePath());
        assertTrue(output.length() > 0);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class Bound implements AutoCloseable {
        final NoteTextBoxView view; final WebView webView; final FrameLayout host;
        Bound(NoteTextBoxView view, WebView webView, FrameLayout host) {
            this.view = view; this.webView = webView; this.host = host;
        }
        @Override public void close() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                view.dispose(); host.removeView(view);
            });
        }
    }

    private static final class Session implements AutoCloseable {
        final ActivityScenario<MainActivity> scenario; final FrameLayout host; final NoteCanvasView canvas;
        Session(ActivityScenario<MainActivity> scenario, FrameLayout host, NoteCanvasView canvas) {
            this.scenario = scenario; this.host = host; this.canvas = canvas;
        }
        static Session open() {
            ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
            AtomicReference<FrameLayout> hostRef = new AtomicReference<>();
            AtomicReference<NoteCanvasView> canvasRef = new AtomicReference<>();
            scenario.onActivity(activity -> {
                int width = dp(activity, 768), height = dp(activity, 1024);
                FrameLayout host = new FrameLayout(activity);
                NoteCanvasView canvas = new NoteCanvasView(activity);
                host.addView(canvas, new FrameLayout.LayoutParams(width, height));
                activity.setContentView(host, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                canvas.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                canvas.layout(0, 0, width, height);
                hostRef.set(host); canvasRef.set(canvas);
            });
            return new Session(scenario, hostRef.get(), canvasRef.get());
        }
        @Override public void close() { scenario.close(); }
    }
}
