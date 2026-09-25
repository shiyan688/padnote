package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/** Real bookshelf dialog checks: AlertDialog silently hides items when message is also set. */
@RunWith(AndroidJUnit4.class)
public final class AgentConnectionDialogsTest {
    private AgentConnectionStore store;

    @Before public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new AgentConnectionStore(context);
        store.clear();
    }

    @After public void tearDown() {
        store.clear();
    }

    @Test public void bookshelfEntryShowsAndOpensEveryConnectionEntryPoint() {
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            openConnections();
            onView(withText("查看电脑任务")).check(matches(isDisplayed()));
            onView(withText("＋ 手动添加连接")).check(matches(isDisplayed()));
            onView(withText("扫码添加连接")).check(matches(isDisplayed()));
            onView(withText("粘贴连接助手配对内容")).check(matches(isDisplayed()));
            onView(withText("如何连接另一台电脑？")).check(matches(isDisplayed()));

            onView(withText("如何连接另一台电脑？")).perform(click());
            onView(withText("连接电脑上的 Agent")).check(matches(isDisplayed()));
            onView(withText(containsString("Windows / WSL2")))
                    .perform(scrollTo()).check(matches(isDisplayed()));
            pressBack();

            openConnections();
            onView(withText("＋ 手动添加连接")).perform(click());
            onView(withText("添加 Agent")).check(matches(isDisplayed()));
            pressBack();

            openConnections();
            onView(withText("粘贴连接助手配对内容")).perform(click());
            onView(withText("连接助手配对")).check(matches(isDisplayed()));
            pressBack();

            openConnections();
            onView(withText("查看电脑任务")).perform(click());
            onView(withText("电脑任务")).check(matches(isDisplayed()));
            // Do not leave a dialog window in transition while ActivityScenario
            // tears its activity down; the instrumentation runner may start the
            // next class before WindowManager has reassigned focus.
            pressBack();
        }
    }

    @Test public void verifiedProfileOpensItsActionRows() throws Exception {
        AgentConnectionStore.Config profile = store.add("UI Fixture Hermes",
                AgentConnectionStore.Kind.HERMES, AgentConnectionStore.Transport.DIRECT,
                "https://fixture.invalid", "fixture-token");
        store.applyProbeSuccess(profile.id, profile.revision,
                new AgentConnectionClient.ProbeResult("ok", "ui-instance",
                        Arrays.asList("run_submission", "run_status")));

        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            openConnections();
            onView(withText(containsString("UI Fixture Hermes"))).perform(click());
            onView(withText("发送文本任务")).check(matches(isDisplayed()));
            onView(withText("测试连接")).check(matches(isDisplayed()));
            onView(withText("编辑")).check(matches(isDisplayed()));
            onView(withText("删除")).check(matches(isDisplayed()));
            pressBack();
        }
    }

    private static void openConnections() {
        onView(withText("电脑 Agent")).perform(click());
    }
}
