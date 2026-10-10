package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Narrow UI regression for the real MainActivity cover-thumb composition and touch target. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 27)
public final class CoverThumbCompositionInstrumentedTest {
    @Test public void productionCoverThumbDisplaysPixelsAndRoutesTapToSelectionCallbackOnce() {
        final int expectedColor=Color.rgb(31,137,211);
        final String label="缩略图像素回归";
        final String description="封面："+label;
        Bitmap source=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);
        source.eraseColor(expectedColor);
        AtomicReference<View> thumbRef=new AtomicReference<>();
        AtomicReference<AlertDialog> dialogRef=new AtomicReference<>();
        AtomicInteger callbackCount=new AtomicInteger();
        AtomicReference<String> selectedLabel=new AtomicReference<>();

        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Runnable onPicked=() -> {
                    selectedLabel.set(label);
                    callbackCount.incrementAndGet();
                };
                View thumb=invokeProductionCoverThumb(activity,source,label,onPicked);
                thumbRef.set(thumb);
                AlertDialog dialog=new AlertDialog.Builder(activity)
                        .setTitle("封面选择回归")
                        .setView(thumb)
                        .setNegativeButton("关闭",null)
                        .create();
                dialogRef.set(dialog);
                dialog.show();
                assertTrue("the owned test dialog is actually displayed",dialog.isShowing());
            });

            onView(withContentDescription(description)).inRoot(isDialog())
                    .check(matches(isDisplayed()));

            scenario.onActivity(activity -> {
                View thumb=thumbRef.get();
                assertNotNull("the production helper returned a thumbnail container",thumb);
                assertTrue("thumbnail is laid out in the shown dialog",thumb.isLaidOut());
                assertTrue("thumbnail has a real draw area",thumb.getWidth()>0&&thumb.getHeight()>0);
                View image=findByContentDescription(thumb,description);
                assertNotNull("cover image is attached to the clickable thumbnail",image);
                Bitmap rendered=Bitmap.createBitmap(thumb.getWidth(),thumb.getHeight(),Bitmap.Config.ARGB_8888);
                try {
                    thumb.draw(new Canvas(rendered));
                    assertEquals("the displayed composition renders the supplied cover pixels",
                            expectedColor,rendered.getPixel(thumb.getWidth()/2,thumb.getHeight()/2));
                } finally { rendered.recycle(); }
            });

            // Espresso taps the real described image inside the displayed dialog. The parent
            // FrameLayout's production listener must receive that gesture exactly once.
            onView(withContentDescription(description)).inRoot(isDialog()).perform(click());
            assertEquals("one tap invokes the selection callback once",1,callbackCount.get());
            assertEquals(label,selectedLabel.get());

            scenario.onActivity(activity -> {
                AlertDialog dialog=dialogRef.get();
                if(dialog!=null&&dialog.isShowing())dialog.dismiss();
            });
        } finally {
            source.recycle();
        }
    }

    private static View invokeProductionCoverThumb(MainActivity activity,Bitmap bitmap,
            String label,Runnable onPicked) {
        try {
            Method method=MainActivity.class.getDeclaredMethod("coverThumb",Bitmap.class,String.class,Runnable.class);
            method.setAccessible(true);
            return (View)method.invoke(activity,bitmap,label,onPicked);
        } catch(ReflectiveOperationException failure) {
            throw new AssertionError("could not invoke the production cover UI helper",failure);
        }
    }

    private static View findByContentDescription(View root,String expected) {
        CharSequence actual=root.getContentDescription();
        if(actual!=null&&expected.contentEquals(actual))return root;
        if(root instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)root;
            for(int index=0;index<group.getChildCount();index++) {
                View match=findByContentDescription(group.getChildAt(index),expected);
                if(match!=null)return match;
            }
        }
        return null;
    }
}
