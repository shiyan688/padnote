package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class AiTranscriptReviewInstrumentedTest {
    @Test public void correctedTranscriptDialogReturnsEditedTextExactlyOnce() {
        AtomicReference<String> accepted = new AtomicReference<>();
        AtomicInteger cancelled = new AtomicInteger();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.createAiTranscriptReviewDialog(
                    "原识别 x = 1", accepted::set, cancelled::incrementAndGet).show());

            onView(withText("校对手写转写")).inRoot(isDialog())
                    .check(matches(isDisplayed()));
            onView(withContentDescription("可编辑的手写转写文本")).inRoot(isDialog())
                    .perform(replaceText("校对后 x = 2"));
            onView(withText("使用校对文本并继续")).inRoot(isDialog()).perform(click());

            assertEquals("校对后 x = 2", accepted.get());
            assertEquals(0, cancelled.get());
        }
    }
}
