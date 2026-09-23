package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class BookshelfSmokeTest {
    @Rule
    public final ActivityScenarioRule<MainActivity> activityRule =
            new ActivityScenarioRule<>(MainActivity.class);

    @Test
    public void bookshelfShowsPrimaryActions() {
        onView(withText("PadNote")).check(matches(isDisplayed()));
        onView(withText("导入笔记")).check(matches(isDisplayed()));
        onView(withText("＋ 新建笔记")).check(matches(isDisplayed()));
    }

    @Test
    public void createNoteDialogOpens() {
        onView(withText("＋ 新建笔记")).perform(click());
        onView(withText("新建笔记")).check(matches(isDisplayed()));
        onView(withText("创建")).check(matches(isDisplayed()));
    }
}
