package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isRoot;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;

import android.app.Activity;
import android.app.ProgressDialog;
import android.graphics.Bitmap;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import android.os.Bundle;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public final class LibraryBackupLifecycleInstrumentedTest {
    @Test public void materialExportRequestSurvivesRecreationAndCancellationConsumesIt() throws Exception {
        String fixtureId=UUID.randomUUID().toString();
        try (ActivityScenario<IsolatedLibraryRestoreActivity> scenario=launchFixture(fixtureId)) {
            scenario.onActivity(activity->{
                set(activity,"pendingRestoredMaterialId","lifecycle-material");
                set(activity,"pendingRestoredMaterialShare",true);
                set(activity,"pendingRestoredMaterialRequestNonce",UUID.randomUUID().toString());
            });
            scenario.recreate();
            scenario.onActivity(activity->{
                assertEquals("lifecycle-material",get(activity,"pendingRestoredMaterialId"));
                assertEquals(Boolean.TRUE,get(activity,"pendingRestoredMaterialShare"));
                assertNotNull(get(activity,"pendingRestoredMaterialRequestNonce"));
                try {
                    Method result=MainActivity.class.getDeclaredMethod("onActivityResult",int.class,int.class,Intent.class);
                    result.setAccessible(true);result.invoke(activity,7114,Activity.RESULT_CANCELED,null);
                } catch (Exception error) { throw new AssertionError(error); }
                assertEquals(null,get(activity,"pendingRestoredMaterialId"));
                assertEquals(Boolean.FALSE,get(activity,"pendingRestoredMaterialShare"));
                assertEquals(null,get(activity,"pendingRestoredMaterialRequestNonce"));
            });
        } finally { cleanupFixture(fixtureId); }
    }

    @Test public void stagedPreviewAndDurableTransactionPointerSurviveActivityRecreation() throws Exception {
        final String fixtureId=UUID.randomUUID().toString();
        final String[] stagePath=new String[1];final String[] stageParentPath=new String[1];
        final String[] transactionPath=new String[1];final String[] inputPath=new String[1];
        try (ActivityScenario<IsolatedLibraryRestoreActivity> scenario=launchFixture(fixtureId)) {
            scenario.onActivity(activity->{
                try {
                    File input=new File(activity.getCacheDir(),"lifecycle-valid-library-"+fixtureId+".zip");
                    inputPath[0]=input.getAbsolutePath();
                    try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("library-backup-r1/valid-library.zip");FileOutputStream out=new FileOutputStream(input)){
                        byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.getFD().sync();
                    }
                    File stageParent=new File(activity.getFilesDir(),"library-backup/lifecycle-stages-"+fixtureId);
                    assertTrue(stageParent.mkdirs()||stageParent.isDirectory());
                    stageParentPath[0]=stageParent.getAbsolutePath();
                    LibraryBackupArchive.StagedArchive staged=LibraryBackupArchive.stage(input,stageParent,null,null);
                    set(activity,"pendingLibraryRestoreStage",staged);stagePath[0]=staged.directory().getAbsolutePath();
                    File transaction=new File(new File(activity.getFilesDir(),"library-backup/transactions-"+fixtureId),UUID.randomUUID().toString());
                    assertTrue(transaction.mkdirs());
                    try(FileOutputStream marker=new FileOutputStream(new File(transaction,"journal.json"))){marker.write("synthetic lifecycle pointer fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));marker.getFD().sync();}
                    transactionPath[0]=transaction.getAbsolutePath();
                    Method persist=MainActivity.class.getDeclaredMethod("persistPendingRestoreDirectoryForActivity",File.class);persist.setAccessible(true);
                    assertEquals(Boolean.TRUE,persist.invoke(activity,transaction));
                } catch (Exception error) { throw new AssertionError(error); }
            });
            scenario.recreate();
            onView(withText("备份内容预览")).inRoot(isDialog()).check(matches(isDisplayed()));
            scenario.onActivity(activity->{
                LibraryBackupArchive.StagedArchive retained=(LibraryBackupArchive.StagedArchive)get(activity,"pendingLibraryRestoreStage");
                assertNotNull(retained);assertEquals(stagePath[0],retained.directory().getAbsolutePath());assertTrue(retained.directory().isDirectory());
                assertEquals(transactionPath[0],activity.getSharedPreferences("padnote-library-restore",Activity.MODE_PRIVATE).getString("transaction_directory",null));
                assertTrue(new File(transactionPath[0],"journal.json").isFile());
                try{
                    Method clear=MainActivity.class.getDeclaredMethod("clearPendingRestoreDirectory",File.class);clear.setAccessible(true);
                    clear.invoke(activity,new File(transactionPath[0]));
                }catch(Exception error){throw new AssertionError(error);}
                assertFalse(activity.getSharedPreferences("padnote-library-restore",Activity.MODE_PRIVATE).contains("transaction_directory"));
            });
        } finally {
            clearOwnedPointer(fixtureId,transactionPath[0]);
            if(stagePath[0]!=null)deleteTree(new File(stagePath[0]));
            if(transactionPath[0]!=null)deleteTree(new File(transactionPath[0]));
            if(stageParentPath[0]!=null)deleteTree(new File(stageParentPath[0]));
            if(inputPath[0]!=null)new File(inputPath[0]).delete();
            cleanupFixture(fixtureId);
        }
    }

    @Test public void commitReservedTransactionOffersContinueWithoutRollback() throws Exception {
        final String fixtureId=UUID.randomUUID().toString();
        final LibraryRestoreTransaction[] transaction={null};final LibraryBackupArchive.StagedArchive[] staged={null};
        final IsolatedLibraryRestoreActivity[] host={null};final String[] transactionPath=new String[1];final String[] ledgerPath=new String[1];
        try (ActivityScenario<IsolatedLibraryRestoreActivity> scenario=launchFixture(fixtureId)) {
            scenario.onActivity(activity->{
                try {
                    host[0]=activity;
                    File input=copySyntheticArchive(activity,"reserved-recovery-"+fixtureId+".zip");
                    File stageParent=new File(activity.getFilesDir(),"library-backup");assertTrue(stageParent.mkdirs()||stageParent.isDirectory());
                    staged[0]=LibraryBackupArchive.stage(input,stageParent,null,null);
                    transaction[0]=new LibraryRestoreTransaction(activity,staged[0],new LibraryRestoreTransaction.FaultInjector(){
                        public void afterResourcesPromoted(String groupId){}
                        @Override public void afterCommitReservationDurable(String groupId)throws Exception{throw new java.io.IOException("TEST_AFTER_REAL_COMMIT_RESERVATION");}
                    });
                    transactionPath[0]=transaction[0].transactionDirectory().getAbsolutePath();
                    assertEquals(new File(new File(activity.getFilesDir(),"library-backup/transactions").getCanonicalPath()),transaction[0].transactionDirectory().getCanonicalFile().getParentFile());
                }catch(Exception error){throw new AssertionError(error);}
            });
            try{transaction[0].restoreNotes(null,null);throw new AssertionError("real reservation fault was not reached");}
            catch(java.io.IOException expected){assertEquals("TEST_AFTER_REAL_COMMIT_RESERVATION",expected.getMessage());}
            JSONObject journal=new JSONObject(new String(readFile(new File(transactionPath[0],"journal.json")),java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(2,journal.getInt("schema_version"));
            String transactionId=journal.getString("transaction_id");assertEquals(new File(transactionPath[0]).getName(),transactionId);
            JSONArray groups=journal.getJSONArray("groups");boolean foundReservation=false;
            for(int i=0;i<groups.length();i++){
                JSONObject group=groups.getJSONObject(i);String groupId=group.getString("group_id");
                File reservation=new File(host[0].getFilesDir(),"library-backup-v2/transactions/"+transactionId+"/groups/"+groupId+"/commit-reservation.json");
                if(reservation.isFile()&&reservation.length()>0){foundReservation=true;ledgerPath[0]=new File(host[0].getFilesDir(),"library-backup-v2/transactions/"+transactionId).getAbsolutePath();break;}
            }
            assertTrue("production transaction did not leave a durable reservation",foundReservation);
            scenario.onActivity(activity->{
                try{
                    Method persist=MainActivity.class.getDeclaredMethod("persistPendingRestoreDirectoryForActivity",File.class);persist.setAccessible(true);
                    assertEquals(Boolean.TRUE,persist.invoke(activity,new File(transactionPath[0])));
                    Method classify=MainActivity.class.getDeclaredMethod("restoreRecoveryUiState",File.class);classify.setAccessible(true);
                    assertEquals("commit",classify.invoke(activity,new File(transactionPath[0])));
                    Method show=MainActivity.class.getDeclaredMethod("showPendingRestoreActions");show.setAccessible(true);show.invoke(activity);
                }catch(Exception error){throw new AssertionError(error);}
            });
            onView(withText("恢复提交需要继续")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("继续提交")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("安全回滚")).inRoot(isDialog()).check(doesNotExist());
        }finally{
            clearOwnedPointer(fixtureId,transactionPath[0]);
            if(staged[0]!=null)try{staged[0].close();}catch(Exception ignored){}
            cleanupFixture(fixtureId);
        }
    }

    @Test public void validationInFlightSurvivesRecreationAndCancellationClosesOwnedStage() throws Exception {
        final String id=UUID.randomUUID().toString();final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final String[] stagePath=new String[1];
        final ProgressDialog[] oldProgress={null};
        try(ActivityScenario<IsolatedLibraryRestoreActivity> scenario=launchFixture(id)){
            scenario.onActivity(activity->{
                try{
                    File input=copySyntheticArchive(activity,"blocked-validation-"+id+".zip");
                    activity.restoreStageValidator=(source,parent)->{entered.countDown();if(!release.await(20,TimeUnit.SECONDS))throw new java.io.IOException("fixture validation timeout");return LibraryBackupArchive.stage(source,parent,null,null);};
                    Method start=MainActivity.class.getDeclaredMethod("stageLibraryRestore",Uri.class);start.setAccessible(true);start.invoke(activity,Uri.fromFile(input));
                    Object state=get(activity,"restorePreviewState");Field progressField=state.getClass().getDeclaredField("progress");progressField.setAccessible(true);oldProgress[0]=(ProgressDialog)progressField.get(state);
                }catch(Exception error){throw new AssertionError(error);}
            });
            assertTrue("validator never reached deterministic barrier",entered.await(10,TimeUnit.SECONDS));
            scenario.recreate();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->assertFalse("old Activity validation dialog survived configuration recreation",oldProgress[0].isShowing()));
            release.countDown();
            waitFor(()->{
                final boolean[] ready={false};scenario.onActivity(a->{LibraryBackupArchive.StagedArchive s=(LibraryBackupArchive.StagedArchive)get(a,"pendingLibraryRestoreStage");ready[0]=s!=null;if(s!=null)stagePath[0]=s.directory().getAbsolutePath();});return ready[0];
            },"retained preview stage");
            onView(withText("备份内容预览")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("取消")).inRoot(isDialog()).perform(androidx.test.espresso.action.ViewActions.click());
            waitFor(()->stagePath[0]!=null&&!new File(stagePath[0]).exists(),"owned stage cleanup after cancel");
        }finally{release.countDown();cleanupFixture(id);}
    }

    @Test public void syntheticArchiveRestoresCopyAndShowsBookshelfAndMaterialEntries() throws Exception {
        final String id=UUID.randomUUID().toString();final File[] archive={null};
        try(ActivityScenario<IsolatedLibraryRestoreActivity> scenario=launchFixture(id)){
            scenario.onActivity(activity->{
                try{
                    archive[0]=copySyntheticArchive(activity,"restore-flow-"+id+".zip");
                    Method start=MainActivity.class.getDeclaredMethod("stageLibraryRestore",Uri.class);start.setAccessible(true);start.invoke(activity,Uri.fromFile(archive[0]));
                }catch(Exception error){throw new AssertionError(error);}
            });
            waitFor(()->{
                final boolean[] ready={false};scenario.onActivity(a->ready[0]=get(a,"pendingLibraryRestoreStage")!=null);return ready[0];
            },"async preview stage before UI assertion");
            onView(withText("备份内容预览")).inRoot(isDialog()).check(matches(isDisplayed()));
            saveSyntheticUiScreenshot(id,"restore-preview");
            onView(withText("恢复为副本")).inRoot(isDialog()).perform(androidx.test.espresso.action.ViewActions.click());
            waitFor(()->{
                final boolean[] found={false};scenario.onActivity(a->{TextView title=(TextView)get(a,"shelfSectionTitle");found[0]=title!=null&&title.getText().toString().equals("最近笔记 · 2");});return found[0];
            },"two restored synthetic notes on bookshelf");
            scenario.onActivity(activity->{
                try{Method actions=MainActivity.class.getDeclaredMethod("showLibraryActions");actions.setAccessible(true);actions.invoke(activity);}
                catch(Exception error){throw new AssertionError(error);}
            });
            onView(withText("查看离线恢复材料")).inRoot(isDialog()).perform(androidx.test.espresso.action.ViewActions.click());
            onView(withText("封面预设 · 合成预设")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(org.hamcrest.Matchers.containsString("Vault · null"))).inRoot(isDialog()).check(doesNotExist());
            onView(isRoot()).inRoot(isDialog()).check((rootView,noMatch)->{
                assertEquals("first synthetic Vault title is visible exactly once",1,countVisibleText(rootView,"知识库资料 · 旧修订知识库"));
                assertEquals("second synthetic Vault title is visible exactly once",1,countVisibleText(rootView,"知识库资料 · 已删除来源知识库"));
            });
            onView(isRoot()).inRoot(isDialog()).check((rootView,noMatch)->assertEquals("both synthetic video material rows must be visible",2,countVisibleText(rootView,"离线视频 · synthetic-video.mp4")));
            saveSyntheticUiScreenshot(id,"restored-materials");
            scenario.onActivity(activity->{assertTrue(archive[0].isFile());assertTrue(activity.fixtureRoot().isDirectory());});
        }finally{cleanupFixture(id);}
    }

    @Test public void finalFinishDuringBlockedValidationDismissesProgressAndClosesLateStage() throws Exception {
        final String id=UUID.randomUUID().toString();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),stageReturned=new CountDownLatch(1);
        final AtomicReference<String> stagePath=new AtomicReference<>();
        final ProgressDialog[] progress={null};
        ActivityScenario<IsolatedLibraryRestoreActivity> scenario=launchFixture(id);
        try{
            scenario.onActivity(activity->{
                try{
                    File input=copySyntheticArchive(activity,"finish-blocked-validation-"+id+".zip");
                    activity.restoreStageValidator=(source,parent)->{
                        entered.countDown();
                        if(!release.await(20,TimeUnit.SECONDS))throw new java.io.IOException("fixture validation timeout");
                        LibraryBackupArchive.StagedArchive result=LibraryBackupArchive.stage(source,parent,null,null);
                        stagePath.set(result.directory().getAbsolutePath());stageReturned.countDown();return result;
                    };
                    Method start=MainActivity.class.getDeclaredMethod("stageLibraryRestore",Uri.class);start.setAccessible(true);start.invoke(activity,Uri.fromFile(input));
                    Object state=get(activity,"restorePreviewState");Field progressField=state.getClass().getDeclaredField("progress");progressField.setAccessible(true);progress[0]=(ProgressDialog)progressField.get(state);
                    assertNotNull(progress[0]);assertTrue(progress[0].isShowing());
                }catch(Exception error){throw new AssertionError(error);}
            });
            assertTrue("validator never reached deterministic barrier",entered.await(10,TimeUnit.SECONDS));
            scenario.close();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->assertFalse("validation dialog survived final Activity finish",progress[0].isShowing()));
            release.countDown();
            assertTrue("validator did not produce its late synthetic stage",stageReturned.await(20,TimeUnit.SECONDS));
            waitFor(()->stagePath.get()!=null&&!new File(stagePath.get()).exists(),"late stage cleanup after final Activity finish");
        }finally{
            release.countDown();scenario.close();cleanupFixture(id);
        }
    }

    private interface Condition { boolean check() throws Exception; }
    private static void waitFor(Condition condition,String what)throws Exception{
        long end=SystemClock.uptimeMillis()+30000;
        while(SystemClock.uptimeMillis()<end){if(condition.check())return;SystemClock.sleep(100);}
        throw new AssertionError("timed out waiting for "+what);
    }
    private static File copySyntheticArchive(Activity activity,String filename)throws Exception{
        File input=new File(activity.getCacheDir(),filename);
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("library-backup-r1/valid-library.zip");FileOutputStream out=new FileOutputStream(input)){
            byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.getFD().sync();
        }
        return input;
    }
    private static void saveSyntheticUiScreenshot(String fixtureId,String label)throws Exception{
        File directory=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"padnote-ui-r14-screenshots/"+fixtureId);
        assertTrue(directory.mkdirs()||directory.isDirectory());
        File output=new File(directory,label+".png");assertFalse("refuse screenshot overwrite",output.exists());
        Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull("screen capture unavailable",screenshot);
        try(FileOutputStream stream=new FileOutputStream(output)){assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG,100,stream));stream.getFD().sync();}
        finally{screenshot.recycle();}
        System.out.println("UI_R14_SYNTHETIC_SCREENSHOT="+output.getAbsolutePath());
    }
    private static int countVisibleText(View view,String expected){
        int count=view instanceof TextView&&expected.contentEquals(((TextView)view).getText())&&view.getVisibility()==View.VISIBLE?1:0;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)count+=countVisibleText(group.getChildAt(i),expected);}
        return count;
    }
    private static byte[] readFile(File file)throws Exception{try(InputStream input=new java.io.FileInputStream(file);java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);return output.toByteArray();}}

    private static ActivityScenario<IsolatedLibraryRestoreActivity> launchFixture(String id){
        File root=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(),"ui-fixtures/"+id);
        assertFalse("fixture root already exists",root.exists());
        Intent intent=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),IsolatedLibraryRestoreActivity.class).putExtra("fixture_id",id);
        return ActivityScenario.launch(intent);
    }
    private static void clearOwnedPointer(String id,String expected){
        if(expected==null)return;
        android.content.SharedPreferences p=InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences("ui-fixture-"+id+"-padnote-library-restore",Activity.MODE_PRIVATE);
        String current=p.getString("transaction_directory",null);
        if(expected.equals(current)){p.edit().remove("transaction_directory").commit();assertFalse(p.contains("transaction_directory"));}
    }
    private static void cleanupFixture(String id){
        android.content.Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix="ui-fixture-"+id+"-";
        String prefName=prefix+"padnote-library-restore";
        android.content.SharedPreferences p=target.getSharedPreferences(prefName,Activity.MODE_PRIVATE);
        String value=p.getString("transaction_directory",null);
        String ownedRoot=new File(target.getFilesDir(),"ui-fixtures/"+id+"/files/library-backup/transactions").getAbsolutePath()+File.separator;
        if(value!=null&&value.startsWith(ownedRoot)){p.edit().remove("transaction_directory").commit();assertFalse(p.contains("transaction_directory"));}
        File root=new File(target.getFilesDir(),"ui-fixtures/"+id);
        if(root.exists())deleteTree(root);
        File prefsDirectory=new File(target.getApplicationInfo().dataDir,"shared_prefs");
        File[] scoped=prefsDirectory.listFiles((dir,name)->name.startsWith(prefix)&&name.endsWith(".xml"));
        if(scoped!=null)for(File file:scoped){
            String name=file.getName().substring(0,file.getName().length()-4);
            target.deleteSharedPreferences(name);
            assertFalse("fixture preference remained: "+file.getName(),file.exists());
        }
        File[] leftovers=prefsDirectory.listFiles((dir,name)->name.startsWith(prefix));
        assertTrue("fixture preference files remain",leftovers==null||leftovers.length==0);
    }
    private static void deleteTree(File file){
        if(file==null||!file.exists())return;
        File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);
        if(!file.delete()&&file.exists())throw new AssertionError("fixture cleanup failed: "+file.getName());
    }

    private static Object get(Activity activity,String name){try{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(activity);}catch(Exception error){throw new AssertionError(error);}}
    private static void set(Activity activity,String name,Object value){try{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(activity,value);}catch(Exception error){throw new AssertionError(error);}}
}
