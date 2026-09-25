package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Device coverage for generation-bound failure and the compact paper recovery UI. */
@RunWith(AndroidJUnit4.class)
public final class RenderRecoveryInstrumentedTest {
    private static final long TIMEOUT_MS = 20_000L;

    @Test
    public void invalidMermaidFailsThenExplicitNewInputBecomesReady() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<CompiledTextWebView> renderer = new AtomicReference<>();
            CountDownLatch failed = new CountDownLatch(1);
            AtomicReference<CompiledTextWebView.RenderState> failure = new AtomicReference<>();
            scenario.onActivity(activity -> {
                FrameLayout host = new FrameLayout(activity);
                host.setBackgroundColor(Color.WHITE);
                CompiledTextWebView webView = new CompiledTextWebView(activity);
                webView.setRenderStateListener(state -> {
                    if (!state.isReady()) {
                        failure.set(state);
                        failed.countDown();
                    }
                });
                host.addView(webView, new FrameLayout.LayoutParams(dp(activity, 336),
                        dp(activity, 300), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
                activity.setContentView(host);
                renderer.set(webView);
                webView.render(NoteTextBox.Format.MARKDOWN,
                        "```mermaid\nflowchart TD\nA -->\n```", 16f, 1.35f);
            });
            assertTrue("invalid Mermaid never reported a bounded failure",
                    failed.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertEquals(CompiledTextWebView.FailureKind.SYNTAX, failure.get().failure);

            CountDownLatch ready = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                renderer.get().setRenderStateListener(state -> {
                    if (state.isReady()) ready.countDown();
                });
                renderer.get().render(NoteTextBox.Format.MARKDOWN,
                        "# 已修复\n\n现在可以正常显示。", 16f, 1.35f);
            });
            assertTrue("explicit new input did not recover the same live renderer",
                    ready.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            CountDownLatch newestReady = new CountDownLatch(1);
            AtomicReference<CompiledTextWebView.RenderState> staleFailure =
                    new AtomicReference<>();
            scenario.onActivity(activity -> {
                renderer.get().setRenderStateListener(state -> {
                    if (state.isReady()) newestReady.countDown();
                    else staleFailure.compareAndSet(null, state);
                });
                renderer.get().render(NoteTextBox.Format.MARKDOWN,
                        "```mermaid\nflowchart TD\nBROKEN -->\n```", 16f, 1.35f);
                renderer.get().render(NoteTextBox.Format.MARKDOWN,
                        "最终一代输入正常。", 16f, 1.35f);
            });
            assertTrue("newest generation did not complete after superseding bad input",
                    newestReady.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            SystemClock.sleep(500);
            assertTrue("superseded render callback polluted the newest input: "
                    + staleFailure.get(), staleFailure.get() == null);
        }
    }

    @Test
    public void badChatFormulaReleasesFailureAndAcceptsEditedDisplayCopy() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<AiMathWebView> renderer = new AtomicReference<>();
            CountDownLatch failed = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                FrameLayout host = new FrameLayout(activity);
                AiMathWebView webView = new AiMathWebView(activity, "", false);
                webView.setRenderStateListener(state -> {
                    if (state.failure == CompiledTextWebView.FailureKind.SYNTAX) {
                        failed.countDown();
                    }
                });
                host.addView(webView, new FrameLayout.LayoutParams(dp(activity, 336),
                        dp(activity, 180), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
                activity.setContentView(host);
                renderer.set(webView);
                webView.renderDisplayCopy("坏公式 $\\badcommand{x}$");
            });
            assertTrue("bad chat formula was reported as a successful display",
                    failed.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            CountDownLatch ready = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                renderer.get().setRenderStateListener(state -> {
                    if (state.isReady()) ready.countDown();
                });
                renderer.get().renderDisplayCopy("已修复 $x^2+y^2=z^2$");
            });
            assertTrue("edited local display copy stayed blocked behind the old error",
                    ready.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void shortFailedFormulaKeepsWritingSurfaceAndOpensFullFlowRecovery()
            throws Exception {
        String fullSource = "\\badcommand{x}\n后续片段仍属于同一个文字流";
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<NoteTextBoxView> overlay = new AtomicReference<>();
            scenario.onActivity(activity -> {
                FrameLayout host = new FrameLayout(activity);
                NoteTextBoxView view = new NoteTextBoxView(activity, noOpListener());
                NoteTextBox fragment = new NoteTextBox("box-short", "flow-short", 0, 2,
                        NoteTextBox.Format.LATEX, fullSource, "\\badcommand{x}",
                        16f, 1.35f, 0, 20, 20, 84, 32);
                host.addView(view, new FrameLayout.LayoutParams(dp(activity, 84),
                        dp(activity, 32), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
                activity.setContentView(host);
                view.bind(fragment, 1f, 0f, 0f, false);
                overlay.set(view);
            });
            awaitVisible(scenario, "显示失败，点按查看原因和修复");

            AtomicReference<CompiledTextWebView> dead = new AtomicReference<>();
            scenario.onActivity(activity -> {
                CompiledTextWebView webView = findCompiled(overlay.get());
                assertNotNull(webView);
                dead.set(webView);
                webView.simulateRenderProcessGoneForTest();
            });
            long replacementDeadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
            while (SystemClock.uptimeMillis() < replacementDeadline) {
                AtomicReference<CompiledTextWebView> current = new AtomicReference<>();
                scenario.onActivity(activity -> current.set(findCompiled(overlay.get())));
                if (current.get() != null && current.get() != dead.get()) break;
                SystemClock.sleep(40);
            }
            scenario.onActivity(activity -> assertTrue(
                    "dead WebView remained attached instead of being replaced by its owner",
                    findCompiled(overlay.get()) != dead.get()));

            scenario.onActivity(activity -> {
                NoteTextBoxView view = overlay.get();
                MotionEvent down = MotionEvent.obtain(SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, 1f, 0f, 0);
                try {
                    assertFalse("failed unselected text swallowed pen input outside its marker",
                            view.dispatchTouchEvent(down));
                } finally {
                    down.recycle();
                }
            });

            onView(withContentDescription("显示失败，点按查看原因和修复"))
                    .check(matches(isDisplayed())).perform(click());
            onView(withText("查看源码")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("重新显示")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("查看源码")).inRoot(isDialog()).perform(click());
            onView(withText(fullSource)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("关闭")).inRoot(isDialog()).perform(click());

            onView(withContentDescription("显示失败，点按查看原因和修复"))
                    .perform(click());
            onView(withText("重新显示")).inRoot(isDialog())
                    .check(matches(isDisplayed())).perform(click());
        }
    }

    private static NoteTextBoxView.Listener noOpListener() {
        return new NoteTextBoxView.Listener() {
            @Override public void onSelect(NoteTextBox value) { }
            @Override public void onEditRequested(NoteTextBox value) { }
            @Override public void onMove(NoteTextBox value, float x, float y) { }
            @Override public void onDragPreview(NoteTextBox value, float x, float y) { }
            @Override public void onDragPreviewEnded() { }
            @Override public void onResize(NoteTextBox value, float width, float height) { }
            @Override public void onScaleToArea(NoteTextBox value, float width, float height) { }
            @Override public void onCommit(NoteTextBox value, NoteTextBox.Format format,
                                           String source, float size, float lineHeight) { }
            @Override public void onFontSizeChanged(NoteTextBox value, float size) { }
            @Override public void onMeasuredHeight(NoteTextBox value, float height) { }
            @Override public void onCancel(NoteTextBox value) { }
            @Override public void onDelete(NoteTextBox value) { }
        };
    }

    private static void awaitVisible(ActivityScenario<MainActivity> scenario,
                                     String description) throws Exception {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<View> found = new AtomicReference<>();
            scenario.onActivity(activity -> found.set(findByDescription(
                    activity.getWindow().getDecorView(), description)));
            if (found.get() != null && found.get().isShown()) return;
            SystemClock.sleep(80);
        }
        throw new AssertionError("view never became visible: " + description);
    }

    private static View findByDescription(View root, String description) {
        if (root == null) return null;
        CharSequence value = root.getContentDescription();
        if (value != null && value.toString().equals(description)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View match = findByDescription(group.getChildAt(index), description);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static CompiledTextWebView findCompiled(View root) {
        if (root instanceof CompiledTextWebView) return (CompiledTextWebView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                CompiledTextWebView match = findCompiled(group.getChildAt(index));
                if (match != null) return match;
            }
        }
        return null;
    }

    private static int dp(MainActivity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
