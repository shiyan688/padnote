package com.padnote.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.padnote.android.streaming.StreamingGroupStore;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Verifies the real editor save failure prevents digitization/provider startup. */
@RunWith(AndroidJUnit4.class)
public final class DigitizationSaveAcknowledgementInstrumentedTest {
    @Test public void staleEditorSaveFailureKeepsDraftAndDoesNotStartDigitization() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String title="digitization save gate "+UUID.randomUUID();
        NoteStore.Entry entry=NoteStore.create(context,title);
        NoteGroupFacade facade=new NoteGroupFacade(context);
        StreamingGroupStore.Snapshot base=facade.adoptLegacyNote(entry.id,(old,staged)->true);
        AtomicInteger providerCalls=new AtomicInteger();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            openSyntheticNote(scenario,title);
            JSONObject concurrent=new JSONObject(new String(base.readSmall("body.bin",
                    (int)StreamingGroupStore.BODY_MAX),StandardCharsets.UTF_8));
            concurrent.put("title","concurrent winner");
            StreamingGroupStore.Snapshot winner=facade.saveEditorRevision(entry.id,base.revision,
                    base.digest,"concurrent winner",NoteJsonCodec.stringify(concurrent));
            scenario.onActivity(activity->{
                NoteCanvasView canvas=activity.canvasForTest();
                dispatchStylusStroke(canvas);
                DigitizationController controller=new DigitizationController(activity,
                        new VaultStore(activity),new DigitizationStore(activity),
                        (profile,png,prompt,cancellation)->{
                            providerCalls.incrementAndGet();return "must not be requested";
                        });
                activity.installDigitizationControllerForTest(controller);
                activity.requestDigitizationForTest();
            });
            awaitSaveFailure(scenario);
            assertEquals("save CAS failure must prevent model calls",0,providerCalls.get());
            AtomicInteger strokes=new AtomicInteger();
            scenario.onActivity(activity->{
                assertFalse(activity.saveStatusForTest().isEmpty());
                assertNotNull(activity.canvasForTest());
                try{strokes.set(activity.canvasForTest().toJsonDocument(entry.id,title)
                        .getJSONArray("strokes").length());}
                catch(Exception error){throw new AssertionError(error);}
            });
            assertTrue("the failed edit remains on the live canvas",strokes.get()>0);
            GroupEditorDraftStore.Draft retained=GroupEditorDraftStore.read(context,entry.id);
            assertNotNull("the failed edit remains available as a durable recovery draft",retained);
            JSONObject draftBody=NotePrecisionJsonParser.parseObject(retained.bodyJson);
            assertEquals(strokes.get(),draftBody.getJSONArray("strokes").length());
            assertTrue("no digitization checkpoint was created before save acknowledgment",
                    new DigitizationStore(context).listForNote(entry.id).isEmpty());
            StreamingGroupStore.Snapshot stillWinner=facade.openGroup(entry.id);
            assertEquals(winner.revision,stillWinner.revision);
            assertEquals(winner.digest,stillWinner.digest);
            scenario.onActivity(activity->{
                DigitizationController controller=activity.digitizationControllerForTest();
                if(controller!=null)controller.close();
            });
        }
    }

    private static void openSyntheticNote(ActivityScenario<MainActivity> scenario,String title)throws Exception{
        String description="打开笔记 "+title+"。长按可重命名或更换封面。";
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        Throwable last=null;
        while(System.nanoTime()<deadline){
            try{androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers
                    .withContentDescription(description)).perform(androidx.test.espresso.action.ViewActions.scrollTo(),
                    androidx.test.espresso.action.ViewActions.click());break;}
            catch(RuntimeException|AssertionError unavailable){last=unavailable;Thread.sleep(100);}
        }
        if(System.nanoTime()>=deadline)throw new AssertionError("synthetic note did not appear",last);
        long readyDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<readyDeadline){
            java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();
            scenario.onActivity(activity->ready.set(activity.canvasForTest()!=null
                    &&activity.canvasForTest().isEnabled()));
            if(ready.get())return;Thread.sleep(100);
        }
        throw new AssertionError("synthetic editor did not finish restoring");
    }

    private static void awaitSaveFailure(ActivityScenario<MainActivity> scenario)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline){
            java.util.concurrent.atomic.AtomicBoolean failed=new java.util.concurrent.atomic.AtomicBoolean();
            scenario.onActivity(activity->failed.set(activity.saveStatusForTest().startsWith("保存失败")));
            if(failed.get())return;Thread.sleep(100);
        }
        throw new AssertionError("save failure was not surfaced by the actual editor path");
    }

    private static void dispatchStylusStroke(NoteCanvasView canvas){
        long down=android.os.SystemClock.uptimeMillis();float x=canvas.getWidth()*.5f,y=canvas.getHeight()*.5f;
        dispatch(canvas,down,down,MotionEvent.ACTION_DOWN,x,y);
        dispatch(canvas,down,down+20,MotionEvent.ACTION_MOVE,x+12,y+16);
        dispatch(canvas,down,down+40,MotionEvent.ACTION_UP,x+24,y+32);
    }
    private static void dispatch(NoteCanvasView canvas,long down,long at,int action,float x,float y){
        MotionEvent.PointerProperties properties=new MotionEvent.PointerProperties();properties.id=0;
        properties.toolType=MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords coordinates=new MotionEvent.PointerCoords();coordinates.x=x;coordinates.y=y;
        coordinates.pressure=.7f;coordinates.size=.08f;
        MotionEvent event=MotionEvent.obtain(down,at,action,1,new MotionEvent.PointerProperties[]{properties},
                new MotionEvent.PointerCoords[]{coordinates},0,MotionEvent.BUTTON_STYLUS_PRIMARY,
                1f,1f,0,0,InputDevice.SOURCE_STYLUS,0);
        try{assertTrue(canvas.dispatchTouchEvent(event));}finally{event.recycle();}
    }
}
