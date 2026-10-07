package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isClickable;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.app.Activity;
import android.os.SystemClock;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.text.Layout;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.ViewAssertion;
import androidx.test.espresso.matcher.BoundedMatcher;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.app.UiAutomation;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises actual platform dialogs while keeping all connection data synthetic and UUID-scoped. */
@RunWith(AndroidJUnit4.class)
public final class AgentSettingsLayoutInstrumentedTest {
    private String preferencesName;
    private boolean ownsPreferences;

    @After public void cleanupOnlyOwnedFixture() {
        if (preferencesName == null || !ownsPreferences) return;
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = target.getSharedPreferences(preferencesName,
                Context.MODE_PRIVATE);
        try {
            AgentConnectionStore store = new AgentConnectionStore(preferences,
                    System::currentTimeMillis);
            store.clear();
            assertTrue(store.list().isEmpty());
            assertEquals(Collections.singleton("connections.v2"), preferences.getAll().keySet());
            assertTrue(preferences.getString("connections.v2", "")
                    .contains("\"connections\":[]"));
        } finally {
            assertTrue("delete only this test's UUID-named preferences",
                    target.deleteSharedPreferences(preferencesName));
        }
    }

    @Test public void targetAndCapabilityDialogsRemainReadableAtDefaultFont() {
        exerciseAtFontScale(false);
    }

    @Test public void targetAndCapabilityDialogsRemainScrollableAtLocalTwoXFont() {
        exerciseAtFontScale(true);
    }

    private void exerciseAtFontScale(boolean largeFont) {
        preferencesName = "agent-layout-" + UUID.randomUUID();
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File prefsFile = new File(new File(target.getApplicationInfo().dataDir, "shared_prefs"),
                preferencesName + ".xml");
        assertTrue("refuse to reuse any pre-existing UUID preferences file", !prefsFile.exists());
        ownsPreferences = true;
        SharedPreferences prefs = target.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
        assertTrue("fresh UUID preference namespace", prefs.getAll().isEmpty());
        Class<? extends Activity> fixtureActivity = largeFont
                ? AgentSettingsLayoutHarnessActivity.TwoX.class
                : AgentSettingsLayoutHarnessActivity.class;
        android.content.Intent intent = new android.content.Intent(target, fixtureActivity)
                .putExtra(AgentSettingsLayoutHarnessBaseActivity.EXTRA_PREFERENCES,
                        preferencesName);
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent)) {
            if (largeFont) {
                scenario.onActivity(activity -> assertEquals(2.0f,
                        activity.getResources().getConfiguration().fontScale, 0.0f));
            }
            AgentConnectionStore fixtureStore = new AgentConnectionStore(prefs,
                    System::currentTimeMillis);
            List<AgentConnectionStore.Config> profiles = fixtureStore.listSummaries();
            assertEquals(2, profiles.size());
            AgentConnectionStore.Config first = profiles.get(0);
            AgentConnectionStore.Config second = profiles.get(1);
            String firstLabel = AgentTaskDialogs.destinationLabel(first);
            String secondLabel = AgentTaskDialogs.destinationLabel(second);
            assertTrue(firstLabel.contains(
                    "same-computer-host-with-a-long-layout-name.fixture-android-testing.invalid:43127"));
            assertTrue(firstLabel.contains("实例 inst-alpha"));
            assertTrue(secondLabel.contains(
                    "same-computer-host-with-a-long-layout-name.fixture-android-testing.invalid:43128"));
            assertTrue(secondLabel.contains("实例 inst-bravo"));

            onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(ListView.class))
                    .check(matches(isDisplayed())).perform(selectListPosition(1));
            onView(withText(containsString(":43127")))
                    .check(matches(fullListRow(firstLabel)));
            onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(ListView.class))
                    .perform(selectListPosition(2));
            onView(withText(containsString(":43128")))
                    .check(matches(fullListRow(secondLabel)));
            capture(scenario, "list-second.png");
            onView(withText(containsString(":43128"))).perform(click());

            onView(withText("连接操作")).check(matches(isDisplayed()));
            AtomicReference<String> identityBeforeCapture = new AtomicReference<>();
            onView(withTagValue(equalTo("agent-target-identity")))
                    .perform(scrollTo(), waitForStableFullVisibility("target identity",
                            identityBeforeCapture))
                    .check(matches(fullUnellipsizedIdentity(secondLabel)))
                    .check(assertFullyVisibleBounds("target identity"));
            capture(scenario, "action-identity.png");
            AtomicReference<String> identityAfterCapture = new AtomicReference<>();
            onView(withTagValue(equalTo("agent-target-identity")))
                    .perform(waitForStableFullVisibility("target identity after screenshot",
                            identityAfterCapture));
            assertEquals("identity geometry stayed stable across screenshot capture",
                    identityBeforeCapture.get(), identityAfterCapture.get());

            AtomicReference<String> probeBeforeCapture = new AtomicReference<>();
            onView(withText("测试连接")).perform(scrollTo(),
                            waitForStableFullVisibility("probe action", probeBeforeCapture))
                    .check(matches(isDisplayed())).check(matches(isClickable()))
                    .check(assertFullyVisibleClickableBounds("测试连接"));
            capture(scenario, "action-probe.png");
            AtomicReference<String> probeAfterCapture = new AtomicReference<>();
            onView(withText("测试连接")).perform(waitForStableFullVisibility(
                    "probe action after screenshot", probeAfterCapture));
            assertEquals("probe-button geometry stayed stable across screenshot capture",
                    probeBeforeCapture.get(), probeAfterCapture.get());
            onView(withText("能力与下一步")).perform(scrollTo())
                    .check(matches(isDisplayed())).perform(click());

            onView(withText("能力与下一步")).check(matches(isDisplayed()));
            onView(withText(allOf(containsString("语音功能需另行明确批准"),
                    containsString("inst-bravo"))))
                    .check(matches(fullMessageContents(secondLabel,
                            "语音功能需另行明确批准")));
            onView(capabilityMessageScrollView())
                    .check(matches(isDisplayed()))
                    .check(assertAtTopAndScrollable(largeFont));
            capture(scenario, "capability-top.png");
            onView(capabilityMessageScrollView())
                    .perform(scrollToBottom())
                    .check(assertLastLineVisible());
            onView(withText("重新测试")).check(matches(isDisplayed()))
                    .check(matches(isClickable())).check(assertFullyVisibleClickableBounds("重新测试"));
            capture(scenario, "capability-bottom.png");
            onView(withText("知道了")).check(matches(isDisplayed()))
                    .check(matches(isClickable())).perform(click());

            awaitActivityWindowFocus(scenario, true);
            onView(withText(AgentSettingsLayoutHarnessActivity.FIXTURE_BUTTON))
                    .check(matches(isDisplayed()))
                    .check(assertHarnessControlBelowSystemBars())
                    .perform(click());
            awaitActivityWindowFocus(scenario, false);
            onView(withText("电脑 Agent"))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(matches(isDisplayed()));
            onView(withText("关闭")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(matches(isDisplayed()))
                    .check(matches(isClickable())).perform(click());
            awaitActivityWindowFocus(scenario, true);
            onView(withText("电脑 Agent"))
                    .check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            onView(withText(AgentSettingsLayoutHarnessActivity.FIXTURE_BUTTON))
                    .check(matches(isDisplayed()));
        }
    }

    private static void awaitActivityWindowFocus(ActivityScenario<Activity> scenario,
                                                 boolean expectedFocus) {
        long deadline = SystemClock.uptimeMillis() + 5000L;
        java.util.concurrent.atomic.AtomicBoolean observed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        while (SystemClock.uptimeMillis() < deadline) {
            observed.set(!expectedFocus);
            scenario.onActivity(activity -> observed.set(activity.getWindow() != null &&
                    !activity.isFinishing() && !activity.isDestroyed() &&
                    activity.getWindow().getDecorView().hasWindowFocus()));
            if (observed.get() == expectedFocus) {
                System.out.println("AGENT_LAYOUT_WINDOW activityWindowFocus=" +
                        observed.get() + " expected=" + expectedFocus);
                return;
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(25L);
        }
        throw new AssertionError("Activity window focus did not reach " + expectedFocus +
                " within bounded observation; observed=" + observed.get());
    }

    private static ViewAssertion assertHarnessControlBelowSystemBars() {
        return (view, noViewFoundException) -> {
            if (noViewFoundException != null) throw noViewFoundException;
            int[] screen = new int[2];
            view.getLocationOnScreen(screen);
            Rect safeFrame = new Rect();
            view.getRootView().getWindowVisibleDisplayFrame(safeFrame);
            Rect localVisible = new Rect();
            boolean fullyVisible = view.getLocalVisibleRect(localVisible) &&
                    localVisible.left == 0 && localVisible.top == 0 &&
                    localVisible.right == view.getWidth() &&
                    localVisible.bottom == view.getHeight();
            System.out.println("AGENT_LAYOUT_REOPEN_CONTROL screen=" + screen[0] + "," +
                    screen[1] + "+" + view.getWidth() + "x" + view.getHeight() +
                    " safeFrame=" + safeFrame + " localVisible=" + localVisible +
                    " full=" + fullyVisible);
            org.junit.Assert.assertTrue("reopen control must be wholly in the unobscured " +
                    "display frame before its single tap; screen=" + screen[0] + "," +
                    screen[1] + " safeFrame=" + safeFrame + " localVisible=" + localVisible,
                    fullyVisible && screen[1] >= safeFrame.top &&
                            screen[1] + view.getHeight() <= safeFrame.bottom);
        };
    }

    private static ViewAction selectListPosition(int position) {
        return new ViewAction() {
            @Override public org.hamcrest.Matcher<View> getConstraints() {
                return androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(ListView.class);
            }
            @Override public String getDescription() { return "select dialog list position " + position; }
            @Override public void perform(androidx.test.espresso.UiController ui, View view) {
                ((ListView) view).setSelection(position);
                ui.loopMainThreadUntilIdle();
            }
        };
    }

    private static Matcher<View> fullUnellipsizedIdentity(String expectedLabel) {
        return new BoundedMatcher<View, TextView>(TextView.class) {
            @Override public void describeTo(Description description) {
                description.appendText("complete, unellipsized target identity: ").appendText(expectedLabel);
            }
            @Override protected boolean matchesSafely(TextView view) {
                String expected = "目标\n" + expectedLabel;
                if (!view.getText().toString().startsWith(expected)) return false;
                return unellipsizedAndVisible(view);
            }
        };
    }

    private static Matcher<View> fullListRow(String expectedLabel) {
        return new BoundedMatcher<View, TextView>(TextView.class) {
            @Override public void describeTo(Description description) {
                description.appendText("complete list row without ellipsis: ").appendText(expectedLabel);
            }
            @Override protected boolean matchesSafely(TextView view) {
                return view.getText().toString().contains(expectedLabel) &&
                        unellipsizedAndVisible(view);
            }
        };
    }

    private static Matcher<View> fullMessageContents(String requiredStart, String requiredEnd) {
        return new BoundedMatcher<View, TextView>(TextView.class) {
            @Override public void describeTo(Description description) {
                description.appendText("complete scrollable message containing ")
                        .appendText(requiredStart).appendText(" and ").appendText(requiredEnd);
            }
            @Override protected boolean matchesSafely(TextView view) {
                String text = view.getText().toString();
                return text.contains(requiredStart) && text.contains(requiredEnd) &&
                        unellipsizedAndVisible(view);
            }
        };
    }

    private static boolean unellipsizedAndVisible(TextView view) {
        Layout layout = view.getLayout();
        if (layout == null) return false;
        for (int line = 0; line < layout.getLineCount(); line++) {
            if (layout.getEllipsisCount(line) != 0) return false;
        }
        Rect visible = new Rect();
        return view.getLocalVisibleRect(visible) && !visible.isEmpty();
    }

    private static ViewAssertion assertAtTopAndScrollable(boolean expectOverflow) {
        return (view, error) -> {
            if (error != null) throw error;
            ScrollView scroll = (ScrollView) view;
            assertTrue("capability content should start at the top",
                    !scroll.canScrollVertically(-1));
            Rect viewport = new Rect();
            assertTrue("capability viewport should have a local visible rect",
                    scroll.getLocalVisibleRect(viewport));
            View child = scroll.getChildAt(0);
            assertTrue("scroll view has content child", child != null);
            Rect childBounds = new Rect(0, 0, child.getWidth(), child.getHeight());
            scroll.offsetDescendantRectToMyCoords(child, childBounds);
            boolean hasOverflow = scroll.canScrollVertically(1);
            String geometry = "fontScale=" + scroll.getResources().getConfiguration().fontScale +
                    " viewportLocal=" + viewport + " contentInScrollLocal=" + childBounds +
                    " scrollSize=" + scroll.getWidth() + "x" + scroll.getHeight() +
                    " scrollY=" + scroll.getScrollY() + " contentSize=" +
                    child.getWidth() + "x" + child.getHeight();
            System.out.println("AGENT_LAYOUT_SCROLL_RANGE " + geometry +
                    " hasDownwardOverflow=" + hasOverflow);
            if (expectOverflow) {
                assertTrue("2.0x font should exercise real lower overflow; " + geometry,
                        hasOverflow && childBounds.bottom > viewport.bottom);
            } else if (!hasOverflow) {
                assertTrue("when normal font has no overflow, all content must fit the visible viewport; " + geometry,
                        childBounds.top >= viewport.top && childBounds.bottom <= viewport.bottom);
            } else {
                assertTrue("normal-font scroll overflow must be shown by content geometry; " + geometry,
                        childBounds.bottom > viewport.bottom);
            }
        };
    }

    private static Matcher<View> capabilityMessageScrollView() {
        return new BoundedMatcher<View, ScrollView>(ScrollView.class) {
            @Override public void describeTo(Description description) {
                description.appendText("the capability-message ScrollView containing its final safety note");
            }
            @Override protected boolean matchesSafely(ScrollView view) {
                return findFinalMessage(view) != null;
            }
        };
    }

    private static ViewAction scrollToBottom() {
        return new ViewAction() {
            @Override public org.hamcrest.Matcher<View> getConstraints() {
                return androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(ScrollView.class);
            }
            @Override public String getDescription() { return "scroll capability details to bottom"; }
            @Override public void perform(androidx.test.espresso.UiController ui, View view) {
                ScrollView scroll = (ScrollView) view;
                System.out.println("AGENT_LAYOUT_SCROLL phase=before " + scrollDiagnostics(scroll));
                scroll.fullScroll(View.FOCUS_DOWN);

                // fullScroll() may start a smooth-scrolling animation. Wait on observable
                // geometry, not merely Espresso idleness: accept completion only after the
                // view is at its actual lower bound and that position stays stable for
                // several UI frames. Fail within a fixed bound if it never settles.
                int previousY = Integer.MIN_VALUE;
                int stableBottomFrames = 0;
                for (int frame = 0; frame < 300; frame++) {
                    ui.loopMainThreadForAtLeast(16);
                    int currentY = scroll.getScrollY();
                    if (!scroll.canScrollVertically(1) && currentY == previousY) {
                        stableBottomFrames++;
                    } else {
                        stableBottomFrames = 0;
                    }
                    previousY = currentY;
                    if (stableBottomFrames >= 3) break;
                }
                String after = scrollDiagnostics(scroll);
                System.out.println("AGENT_LAYOUT_SCROLL phase=after stableBottomFrames=" +
                        stableBottomFrames + " " + after);
                if (stableBottomFrames < 3) {
                    throw new AssertionError("scroll did not settle at the real bottom within " +
                            "the bounded UI-frame wait; " + after);
                }
            }
        };
    }

    private static String scrollDiagnostics(ScrollView scroll) {
        Rect viewport = new Rect();
        boolean hasVisibleViewport = scroll.getLocalVisibleRect(viewport);
        View child = scroll.getChildAt(0);
        Rect content = new Rect();
        if (child != null) {
            content.set(0, 0, child.getWidth(), child.getHeight());
            scroll.offsetDescendantRectToMyCoords(child, content);
        }
        return "scrollY=" + scroll.getScrollY() + " canUp=" +
                scroll.canScrollVertically(-1) + " canDown=" +
                scroll.canScrollVertically(1) + " viewportVisible=" + hasVisibleViewport +
                " viewportLocal=" + viewport + " contentLocal=" + content +
                " size=" + scroll.getWidth() + "x" + scroll.getHeight();
    }

    private static ViewAssertion assertLastLineVisible() {
        return (view, error) -> {
            if (error != null) throw error;
            ScrollView scroll = (ScrollView) view;
            assertTrue("scroll reached bottom", !scroll.canScrollVertically(1));
            TextView message = findFinalMessage(scroll);
            assertTrue("capability message exists below scroll view", message != null);
            String text = message.getText().toString().trim();
            assertTrue("message contains its last safety note",
                    text.endsWith("语音功能需另行明确批准。"));
            Layout layout = message.getLayout();
            assertTrue("message has a laid out final line", layout != null && layout.getLineCount() > 0);
            int last = layout.getLineCount() - 1;
            // Layout coordinates are inside TextView's content area. Convert to this view's
            // local coordinates using its actual padding and internal scroll, then compare
            // against its local visible rect (which is clipped by the ScrollView ancestors).
            int contentTop = message.getTotalPaddingTop() - message.getScrollY();
            int contentLeft = message.getTotalPaddingLeft() - message.getScrollX();
            Rect finalLine = new Rect(
                    (int) Math.floor(contentLeft + layout.getLineLeft(last)),
                    contentTop + layout.getLineTop(last),
                    (int) Math.ceil(contentLeft + layout.getLineRight(last)),
                    contentTop + layout.getLineBottom(last));
            Rect visible = new Rect();
            assertTrue("message has a visible local rectangle", message.getLocalVisibleRect(visible));
            String geometry = "lineLocal=" + finalLine + " messageLocalVisible=" + visible +
                    " messageSize=" + message.getWidth() + "x" + message.getHeight() +
                    " textScroll=(" + message.getScrollX() + "," + message.getScrollY() + ")" +
                    " totalPadding=(" + message.getTotalPaddingLeft() + "," +
                    message.getTotalPaddingTop() + "," + message.getTotalPaddingRight() + "," +
                    message.getTotalPaddingBottom() + ")" +
                    " extendedPaddingTop=" + message.getExtendedPaddingTop() +
                    " scrollViewLocalScroll=(" + scroll.getScrollX() + "," + scroll.getScrollY() + ")";
            System.out.println("AGENT_LAYOUT_FINAL_LINE " + geometry);
            assertTrue("complete final rendered line is visible inside the clipped TextView; " + geometry,
                    finalLine.left >= visible.left && finalLine.top >= visible.top &&
                            finalLine.right <= visible.right && finalLine.bottom <= visible.bottom);
        };
    }

    private static TextView findFinalMessage(View view) {
        if (view instanceof TextView && ((TextView) view).getText() != null &&
                ((TextView) view).getText().toString().contains("语音功能需另行明确批准")) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView found = findFinalMessage(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void capture(ActivityScenario<Activity> scenario, String fileName) {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        Bitmap bitmap = automation.takeScreenshot();
        assertTrue("UI screenshot captured: " + fileName, bitmap != null);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue("PNG encoding succeeded", bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
        byte[] png = bytes.toByteArray();
        scenario.onActivity(activity -> {
            if (!(activity instanceof AgentSettingsLayoutHarnessBaseActivity)) {
                throw new AssertionError("Unexpected UI fixture Activity");
            }
            try {
                ((AgentSettingsLayoutHarnessBaseActivity) activity)
                        .writeFixtureScreenshot(fileName, png);
            } catch (IOException failure) {
                throw new RuntimeException("Unable to persist screenshot in target app sandbox", failure);
            }
        });
        System.out.println("AGENT_LAYOUT_SCREENSHOT fixture=" + preferencesName +
                " relative=ui-fixtures/" + preferencesName + "/" + fileName +
                " bytes=" + png.length);
        bitmap.recycle();
    }

    private static ViewAssertion assertFullyVisibleClickableBounds(String label) {
        return (view, error) -> {
            if (error != null) throw error;
            Rect localVisible = new Rect();
            boolean hasVisibleRect = view.getLocalVisibleRect(localVisible);
            Rect fullBounds = new Rect(0, 0, view.getWidth(), view.getHeight());
            String geometry = "label=" + label + " localVisible=" + localVisible +
                    " fullLocalBounds=" + fullBounds + " size=" + view.getWidth() + "x" +
                    view.getHeight() + " viewScroll=(" + view.getScrollX() + "," +
                    view.getScrollY() + ")" + " padding=(" + view.getPaddingLeft() + "," +
                    view.getPaddingTop() + "," + view.getPaddingRight() + "," +
                    view.getPaddingBottom() + ")";
            System.out.println("AGENT_LAYOUT_CLICKABLE_BOUNDS " + geometry);
            assertTrue("control must be fully visible and clickable; " + geometry,
                    view.isClickable() && hasVisibleRect && localVisible.equals(fullBounds));
        };
    }

    private static ViewAssertion assertFullyVisibleBounds(String label) {
        return (view, error) -> {
            if (error != null) throw error;
            Rect visible = new Rect();
            boolean hasVisible = view.getLocalVisibleRect(visible);
            Rect full = new Rect(0, 0, view.getWidth(), view.getHeight());
            String geometry = "label=" + label + " localVisible=" + visible +
                    " fullLocalBounds=" + full + " size=" + view.getWidth() + "x" +
                    view.getHeight() + " scrollY=" + nearestScrollY(view);
            System.out.println("AGENT_LAYOUT_FULL_BOUNDS " + geometry);
            assertTrue("view must be fully visible; " + geometry,
                    hasVisible && !full.isEmpty() && visible.equals(full));
        };
    }

    private static ViewAction waitForStableFullVisibility(String label,
                                                           AtomicReference<String> result) {
        return new ViewAction() {
            @Override public Matcher<View> getConstraints() {
                return androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(View.class);
            }
            @Override public String getDescription() {
                return "wait for full local bounds to remain stable for " + label;
            }
            @Override public void perform(androidx.test.espresso.UiController ui, View view) {
                String previous = null;
                int stableFrames = 0;
                String observed = "<no layout>";
                for (int frame = 0; frame < 300; frame++) {
                    ui.loopMainThreadForAtLeast(16);
                    Rect visible = new Rect();
                    boolean hasVisible = view.getLocalVisibleRect(visible);
                    Rect full = new Rect(0, 0, view.getWidth(), view.getHeight());
                    int[] locationInWindow = new int[2];
                    view.getLocationInWindow(locationInWindow);
                    int scrollY = nearestScrollY(view);
                    observed = "visible=" + hasVisible + "/" + visible + " full=" + full +
                            " locationInWindow=(" + locationInWindow[0] + "," +
                            locationInWindow[1] + ") scrollY=" + scrollY + " clickable=" +
                            view.isClickable();
                    boolean fullVisible = hasVisible && !full.isEmpty() && visible.equals(full);
                    boolean requiresClick = label.startsWith("probe action");
                    if (fullVisible && (!requiresClick || view.isClickable())) {
                        if (observed.equals(previous)) stableFrames++;
                        else stableFrames = 0;
                    } else {
                        stableFrames = 0;
                    }
                    previous = observed;
                    if (stableFrames >= 3) {
                        result.set(observed);
                        System.out.println("AGENT_LAYOUT_STABLE_BOUNDS label=" + label +
                                " stableFrames=" + stableFrames + " " + observed);
                        return;
                    }
                }
                System.out.println("AGENT_LAYOUT_STABLE_BOUNDS_TIMEOUT label=" + label +
                        " stableFrames=" + stableFrames + " " + observed);
                throw new AssertionError("full visible bounds did not settle within bounded UI " +
                        "frames for " + label + "; " + observed);
            }
        };
    }

    private static int nearestScrollY(View view) {
        android.view.ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if (parent instanceof ScrollView) return ((ScrollView) parent).getScrollY();
            parent = parent.getParent();
        }
        return 0;
    }
}
