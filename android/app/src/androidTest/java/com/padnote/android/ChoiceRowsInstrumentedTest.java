package com.padnote.android;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.hamcrest.Matchers.anything;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Component smoke for real option dialogs and Spinner interaction. */
@RunWith(AndroidJUnit4.class)
public final class ChoiceRowsInstrumentedTest {
    private static final String PREFS = "choice_rows_smoke";
    private static final String TAG = "ChoiceRowsSmoke";

    @Test public void menuSelectionPersistsBackWorksAndSpinnerOwnerSelectionPersists() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        AtomicInteger selected = new AtomicInteger(-1);
        AtomicBoolean dismissed = new AtomicBoolean(false);
        AtomicReference<AlertDialog> choiceDialog = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                String[] labels = {"Agent A", "Agent B：这是较长的多行选项，用于检查较大字体下文字是否仍完整可读"};
                AlertDialog dialog = ChoiceRows.builder(activity).setTitle("选择接收 Agent")
                        .setAdapter(ChoiceRows.adapter(activity, labels), (shown, which) -> {
                            selected.set(which);
                            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                    .putInt("selected_index", which).commit();
                        }).create();
                dialog.setOnDismissListener(ignored -> dismissed.set(true));
                choiceDialog.set(dialog);
                dialog.show();
            });

            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            saveScreen(app, "choice-menu.png");
            scenario.onActivity(activity -> {
                ListView list = choiceDialog.get().getListView();
                TextView second = (TextView) list.getAdapter().getView(1, null, list);
                assertTrue(second.getMaxLines() > 1);
                assertTrue(second.getMinHeight() >= dp(activity, 48));
                second.measure(View.MeasureSpec.makeMeasureSpec(dp(activity, 176), View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                assertTrue("long label should wrap with the configured large font scale",
                        second.getLineCount() >= 2);
                list.performItemClick(second, 1, list.getAdapter().getItemId(1));
            });

            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(1, selected.get());
            assertEquals(1, app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt("selected_index", -1));
            assertTrue("selection menu should dismiss after choosing an item", dismissed.get());

            AtomicReference<AlertDialog> backDialog = new AtomicReference<>();
            scenario.onActivity(activity -> {
                AlertDialog dialog = ChoiceRows.builder(activity).setTitle("返回键检查")
                        .setAdapter(ChoiceRows.adapter(activity, new String[]{"保留当前选择"}), (shown, which) -> {})
                        .create();
                backDialog.set(dialog);
                dialog.show();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertFalse(backDialog.get().isShowing()));

            AtomicReference<Spinner> spinnerRef = new AtomicReference<>();
            AtomicReference<AlertDialog> spinnerDialog = new AtomicReference<>();
            int[] spinnerId = new int[1];
            scenario.onActivity(activity -> {
                Spinner spinner = new Spinner(activity);
                spinner.setId(View.generateViewId());
                spinnerId[0] = spinner.getId();
                ChoiceRows.styleSpinner(spinner);
                spinner.setAdapter(ChoiceRows.spinnerAdapter(spinner,
                        new String[]{"默认模型", "视觉模型 · 第二项"}));
                spinner.setSelection(0);
                LinearLayout body = new LinearLayout(activity);
                body.setOrientation(LinearLayout.VERTICAL);
                body.setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 8));
                body.addView(spinner, new LinearLayout.LayoutParams(-1, -2));
                AlertDialog dialog = ChoiceRows.builder(activity).setTitle("模型选择")
                        .setView(body).setNegativeButton("关闭", null).create();
                spinnerRef.set(spinner);
                spinnerDialog.set(dialog);
                dialog.show();
            });

            onView(withId(spinnerId[0])).perform(click());
            onData(anything()).inRoot(isPlatformPopup()).atPosition(1).perform(click());
            scenario.onActivity(activity -> assertEquals(1, spinnerRef.get().getSelectedItemPosition()));
            onView(withId(spinnerId[0])).perform(click());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            saveScreen(app, "spinner-selected-popup.png");
            scenario.onActivity(activity -> assertTrue(spinnerDialog.get().isShowing()));
        }
    }

    private static void saveScreen(Context app, String name) throws IOException {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("device screenshot unavailable", bitmap);
        File directory = app.getExternalFilesDir("selection-smoke");
        assertNotNull("app external files directory unavailable", directory);
        assertTrue(directory.exists() || directory.mkdirs());
        File file = new File(directory, name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
        Log.i(TAG, "Saved actual selector UI screenshot: " + file.getAbsolutePath());
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
