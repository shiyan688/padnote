package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.padnote.android.streaming.StreamingGroupStore;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.Matchers;

/** Real Vault reader/menu/editor/export callbacks stay bound to a captured group revision. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 27)
public final class VaultUiRouteCasInstrumentedTest {
    @Test public void staleEditKeepsDraftAndDoesNotChangeWinningVault() throws Exception {
        Fixture fixture=createFixture("R68 stale edit");
        ActivityScenario<MainActivity> scenario=null;
        try {
            AtomicReference<String> routeResult=new AtomicReference<>();
            scenario=ActivityScenario.launch(MainActivity.class);
            ActivityScenario<MainActivity> active=scenario;
            awaitShelfCatalog(active);
            active.onActivity(activity->{activity.setVaultStoreForTest(fixture.vault);
                activity.setVaultRouteHooksForTest(null,null,routeResult::set);
                activity.openVaultForTest(fixture.selected);});
            awaitVaultReaderEditAction();
            onView(withText("编辑源码")).check(matches(isDisplayed())).perform(click());
            String draft=withBody(fixture.initialMarkdown,"R68 user draft retained after conflict");
            onView(isAssignableFrom(android.widget.EditText.class)).inRoot(isDialog())
                    .perform(replaceText(draft),closeSoftKeyboard());

            StreamingGroupStore.Snapshot before=fixture.facade.openGroup(fixture.note.id);
            String winnerMarkdown=withBody(fixture.initialMarkdown,"R68 concurrent winner remains current");
            StreamingGroupStore.Snapshot winner=fixture.facade.replaceVaultRevision(fixture.note.id,
                    before.revision,before.digest,fixture.selected.materialId,bytes(winnerMarkdown));

            onView(withText("保存")).inRoot(isDialog()).perform(click());
            awaitResult(routeResult,"edit_failed_draft_retained");
            onView(withText(Matchers.containsString("R68 user draft retained after conflict")))
                    .inRoot(isDialog()).check(matches(isDisplayed()));
            StreamingGroupStore.Snapshot current=fixture.facade.openGroup(fixture.note.id);
            assertEquals("stale callback did not replace the winning revision",winner.revision,current.revision);
            assertEquals(winnerMarkdown,markdown(current,fixture.selected.materialId));
            assertEquals("the earlier immutable revision remains readable",fixture.initialMarkdown,
                    markdown(fixture.facade.openGroupRevision(fixture.note.id,fixture.initial.revision,
                            fixture.initial.digest),fixture.selected.materialId));
        } finally {
            if(scenario!=null)scenario.close();
            fixture.close();
        }
    }

    @Test public void staleDeleteDoesNotReportSuccessOrChangeCurrentOrHistory() throws Exception {
        Fixture fixture=createFixture("R68 stale delete");
        ActivityScenario<MainActivity> scenario=null;
        try {
            AtomicReference<String> routeResult=new AtomicReference<>();
            scenario=ActivityScenario.launch(MainActivity.class);
            ActivityScenario<MainActivity> active=scenario;
            active.onActivity(activity->{activity.setVaultStoreForTest(fixture.vault);
                activity.setVaultRouteHooksForTest(null,null,routeResult::set);
                activity.showVaultMoreForTest(fixture.selected);});
            onView(withText("删除")).inRoot(isDialog()).perform(click());
            onView(withText("删除格式笔记？")).inRoot(isDialog()).check(matches(isDisplayed()));

            StreamingGroupStore.Snapshot before=fixture.facade.openGroup(fixture.note.id);
            String winnerMarkdown=withBody(fixture.initialMarkdown,"R68 delete race winner");
            StreamingGroupStore.Snapshot winner=fixture.facade.replaceVaultRevision(fixture.note.id,
                    before.revision,before.digest,fixture.selected.materialId,bytes(winnerMarkdown));
            onView(withText("删除")).inRoot(isDialog()).perform(click());
            awaitResult(routeResult,"delete_failed");
            assertNotEquals("the stale route never announces a commit","delete_committed",routeResult.get());
            StreamingGroupStore.Snapshot current=fixture.facade.openGroup(fixture.note.id);
            assertEquals(winner.revision,current.revision);
            assertEquals(winnerMarkdown,markdown(current,fixture.selected.materialId));
            assertEquals("the original revision and Vault member remain in history",fixture.initialMarkdown,
                    markdown(fixture.facade.openGroupRevision(fixture.note.id,fixture.initial.revision,
                            fixture.initial.digest),fixture.selected.materialId));
        } finally {
            if(scenario!=null)scenario.close();
            fixture.close();
        }
    }

    @Test public void legacyDeleteUsesContentCapturedBeforeConfirmation() throws Exception {
        Fixture fixture=createLegacyFixture("R68b legacy delete");
        ActivityScenario<MainActivity> scenario=null;
        try {
            AtomicReference<String> routeResult=new AtomicReference<>();
            scenario=ActivityScenario.launch(MainActivity.class);
            ActivityScenario<MainActivity> active=scenario;
            active.onActivity(activity->{activity.setVaultStoreForTest(fixture.vault);
                activity.setVaultRouteHooksForTest(null,null,routeResult::set);
                activity.showVaultMoreForTest(fixture.selected);});
            onView(withText("删除")).inRoot(isDialog()).perform(click());
            onView(withText("删除格式笔记？")).inRoot(isDialog()).check(matches(isDisplayed()));

            String winnerMarkdown=withBody(fixture.initialMarkdown,"R68b legacy concurrent replacement");
            fixture.vault.replaceRaw(fixture.selected.fileName,winnerMarkdown);
            onView(withText("删除")).inRoot(isDialog()).perform(click());
            awaitResult(routeResult,"delete_failed");
            assertTrue("legacy content conflict leaves the selected file intact",
                    fixture.vault.fileForBackup(fixture.selected.fileName).isFile());
            assertEquals("the concurrent legacy replacement remains current",winnerMarkdown,
                    fixture.vault.read(fixture.selected.fileName));
            assertNotEquals("a stale legacy selection never reports a delete commit",
                    "delete_committed",routeResult.get());
        } finally {
            if(scenario!=null)scenario.close();
            fixture.close();
        }
    }

    @Test public void legacySelectionRejectsSameFilenameReplacedByDifferentOwner() throws Exception {
        Fixture fixture=createLegacyFixture("R68d selected legacy owner");
        try {
            NoteStore.Entry other=NoteStore.create(fixture.context,"R68d replacement owner "+UUID.randomUUID());
            String otherFile=fixture.vault.write(other.id,"Other owner",1,other.updatedAt,
                    Collections.singletonList("different owner's valid Markdown"));
            byte[] replacement=Files.readAllBytes(fixture.vault.fileForBackup(otherFile).toPath());
            Path selectedPath=fixture.vault.fileForBackup(fixture.selected.fileName).toPath();
            Files.write(selectedPath,replacement);
            try {
                fixture.vault.captureSelection(fixture.selected);
                fail("same filename replacement must not retarget the selected owner");
            } catch (java.io.IOException expected) {
                assertEquals("selected-row owner is checked against freshly read frontmatter",
                        "VAULT_SELECTION_CHANGED",expected.getMessage());
            }
        } finally { fixture.close(); }
    }

    @Test public void exportPickerCompletionWritesThePreviouslySelectedBytes() throws Exception {
        Fixture fixture=createFixture("R68 export snapshot");
        ActivityScenario<MainActivity> scenario=null;
        try {
            AtomicReference<Intent> launched=new AtomicReference<>();
            AtomicReference<byte[]> exported=new AtomicReference<>();
            AtomicReference<String> routeResult=new AtomicReference<>();
            scenario=ActivityScenario.launch(MainActivity.class);
            ActivityScenario<MainActivity> active=scenario;
            active.onActivity(activity->{activity.setVaultStoreForTest(fixture.vault);
                activity.setVaultRouteHooksForTest(launched::set,exported::set,routeResult::set);
                activity.launchVaultExportForTest(fixture.selected);});
            awaitIntent(launched);
            assertEquals(Intent.ACTION_CREATE_DOCUMENT,launched.get().getAction());
            assertEquals("text/markdown",launched.get().getType());

            StreamingGroupStore.Snapshot before=fixture.facade.openGroup(fixture.note.id);
            String newMarkdown=withBody(fixture.initialMarkdown,"R68 later version must not leak into export");
            fixture.facade.replaceVaultRevision(fixture.note.id,before.revision,before.digest,
                    fixture.selected.materialId,bytes(newMarkdown));
            active.onActivity(activity->activity.completeVaultExportForTest(
                    new Intent().setData(android.net.Uri.parse("content://vault-ui-test/export"))));
            awaitResult(routeResult,"export_committed");
            assertArrayEquals("the system picker cannot retarget export to a later same-name revision",
                    bytes(fixture.initialMarkdown),exported.get());
        } finally {
            if(scenario!=null)scenario.close();
            fixture.close();
        }
    }

    @Test public void retiredGroupShelfRowCannotReusePreviouslyListedMarkdown() throws Exception {
        Fixture fixture=createFixture("R68c retired stale shelf row");
        try {
            StreamingGroupStore.Snapshot current=fixture.facade.openGroup(fixture.note.id);
            fixture.facade.retireGroup(fixture.note.id,current.revision,current.digest);
            try {
                fixture.vault.captureSelection(fixture.selected);
                fail("a row listed before retirement must not retain selectable old bytes");
            } catch (java.io.IOException expected) {
                assertTrue("retired group is rejected by fresh selection validation",
                        String.valueOf(expected.getMessage()).contains("RETIRED"));
            }
            try { fixture.vault.read(fixture.selected.fileName); fail("retired group never falls back to legacy bytes"); }
            catch (Exception expected) {
                assertTrue(String.valueOf(expected.getMessage()).contains("RETIRED")
                        ||String.valueOf(expected.getMessage()).contains("MARKER"));
            }
        } finally { fixture.close(); }
    }

    @Test public void consecutiveEditsUseTheCommittedSnapshotToken() throws Exception {
        Fixture fixture=createFixture("R68c consecutive edits");
        ActivityScenario<MainActivity> scenario=null;
        try {
            AtomicReference<String> routeResult=new AtomicReference<>();
            scenario=ActivityScenario.launch(MainActivity.class);
            ActivityScenario<MainActivity> active=scenario;
            awaitShelfCatalog(active);
            active.onActivity(activity->{activity.setVaultStoreForTest(fixture.vault);
                activity.setVaultRouteHooksForTest(null,null,routeResult::set);
                activity.openVaultForTest(fixture.selected);});
            awaitVaultReaderEditAction();
            onView(withText("编辑源码")).check(matches(isDisplayed())).perform(click());
            String first=withBody(fixture.initialMarkdown,"R68c first committed edit");
            onView(isAssignableFrom(android.widget.EditText.class)).inRoot(isDialog())
                    .perform(replaceText(first),closeSoftKeyboard());
            onView(withText("保存")).inRoot(isDialog()).perform(click());
            awaitResult(routeResult,"edit_committed");

            routeResult.set(null);
            onView(withText("编辑源码")).inRoot(isDialog()).check(matches(isDisplayed())).perform(click());
            String second=withBody(first,"R68c second committed edit");
            onView(isAssignableFrom(android.widget.EditText.class)).inRoot(isDialog())
                    .perform(replaceText(second),closeSoftKeyboard());
            onView(withText("保存")).inRoot(isDialog()).perform(click());
            awaitResult(routeResult,"edit_committed");
            StreamingGroupStore.Snapshot current=fixture.facade.openGroup(fixture.note.id);
            assertEquals("the second edit follows the first committed token",second,
                    markdown(current,fixture.selected.materialId));
        } finally {
            if(scenario!=null)scenario.close();
            fixture.close();
        }
    }

    private static Fixture createLegacyFixture(String title)throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path root=target.getFilesDir().toPath().resolve("vault-ui-r68-"+UUID.randomUUID());
        Files.createDirectory(root);Context context=new FixtureContext(target,root.toFile());
        VaultStore vault=new VaultStore(context);NoteStore.Entry note=NoteStore.create(context,title+" "+UUID.randomUUID());
        String file=vault.write(note.id,title,1,note.updatedAt,Collections.singletonList("initial legacy body"));
        List<VaultStore.VaultNote> rows=vault.listForShelf(Collections.singletonList(note));
        VaultStore.VaultNote selected=null;for(VaultStore.VaultNote row:rows)if(note.id.equals(row.noteId))selected=row;
        assertNotNull("proven legacy owner yields a Vault row",selected);assertFalse(selected.groupManaged);
        String markdown=vault.read(file);
        return new Fixture(root,context,vault,new NoteGroupFacade(context),note,selected,null,markdown);
    }

    private static Fixture createFixture(String title)throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path root=target.getFilesDir().toPath().resolve("vault-ui-r68-"+UUID.randomUUID());
        Files.createDirectory(root);
        Context context=new FixtureContext(target,root.toFile());
        VaultStore vault=new VaultStore(context);
        NoteStore.Entry note=NoteStore.create(context,title+" "+UUID.randomUUID());
        String file=vault.write(note.id,title,1,note.updatedAt,Collections.singletonList("initial vault body"));
        NoteGroupFacade facade=new NoteGroupFacade(context);
        StreamingGroupStore.Snapshot adopted=facade.adoptLegacyNote(note.id,(old,staged)->true);
        List<VaultStore.VaultNote> rows=vault.listForShelf(NoteStore.list(context));
        VaultStore.VaultNote selected=null;
        for(VaultStore.VaultNote row:rows)if(note.id.equals(row.noteId))selected=row;
        assertNotNull("group adoption yields a typed Vault row",selected);
        assertTrue(selected.groupManaged);
        assertTrue("group row uses the virtual Vault locator",GroupAuthorityBridge.isVirtualVaultName(selected.fileName));
        VaultStore.VaultSelection captured=vault.captureSelection(selected);
        return new Fixture(root,context,vault,facade,note,selected,adopted,
                new String(captured.markdown,StandardCharsets.UTF_8));
    }


    private static String markdown(StreamingGroupStore.Snapshot snapshot,String materialId)throws Exception {
        for(GroupAuthorityBridge.VaultView view:GroupAuthorityBridge.vaultViews(snapshot))
            if(materialId.equals(view.materialId))return new String(view.markdown,StandardCharsets.UTF_8);
        throw new AssertionError("expected Vault material is absent from verified snapshot");
    }
    private static String withBody(String markdown,String body){
        int end=markdown.indexOf("\n---\n");
        if(end<0)throw new AssertionError("fixture Vault frontmatter is malformed");
        return markdown.substring(0,end+5)+"\n"+body+"\n";
    }
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static void awaitResult(AtomicReference<String> result,String expected)throws Exception {
        long until=android.os.SystemClock.uptimeMillis()+10_000;
        while(android.os.SystemClock.uptimeMillis()<until){if(expected.equals(result.get()))return;Thread.sleep(25);}
        fail("UI callback did not report expected route result: "+expected+", actual="+result.get());
    }
    private static void awaitIntent(AtomicReference<Intent> result)throws Exception {
        long until=android.os.SystemClock.uptimeMillis()+10_000;
        while(android.os.SystemClock.uptimeMillis()<until){if(result.get()!=null)return;Thread.sleep(25);}
        fail("export route did not launch the document picker");
    }
    private static void awaitVaultReaderEditAction()throws Exception {
        long until=android.os.SystemClock.uptimeMillis()+10_000L;
        androidx.test.espresso.NoMatchingViewException lastMissing=null;
        while(android.os.SystemClock.uptimeMillis()<until){
            try {
                onView(withText("编辑源码")).check(matches(isDisplayed()));
                return;
            } catch(androidx.test.espresso.NoMatchingViewException missing) {
                lastMissing=missing;
            }
            Thread.sleep(25L);
        }
        AssertionError failure=new AssertionError("asynchronous Vault reader did not become visible within 10 seconds");
        if(lastMissing!=null)failure.initCause(lastMissing);
        throw failure;
    }
    private static void awaitShelfCatalog(ActivityScenario<MainActivity> scenario)throws Exception {
        long until=android.os.SystemClock.uptimeMillis()+15_000L;
        while(android.os.SystemClock.uptimeMillis()<until){
            AtomicReference<String> diagnostic=new AtomicReference<>();
            scenario.onActivity(activity->diagnostic.set(activity.bookshelfLoadDiagnosticForTest()));
            JSONObject state=new JSONObject(diagnostic.get());
            if(state.optBoolean("catalog_rendered"))return;
            if("FAILED".equals(state.optString("stage")))
                fail("initial bookshelf catalog failed before the Vault route opened");
            Thread.sleep(25L);
        }
        fail("initial bookshelf catalog did not finish within the bounded test setup window");
    }

    private static final class Fixture implements AutoCloseable {
        final Path root;final Context context;final VaultStore vault;final NoteGroupFacade facade;
        final NoteStore.Entry note;final VaultStore.VaultNote selected;
        final StreamingGroupStore.Snapshot initial;final String initialMarkdown;
        Fixture(Path root,Context context,VaultStore vault,NoteGroupFacade facade,NoteStore.Entry note,
                VaultStore.VaultNote selected,StreamingGroupStore.Snapshot initial,String markdown){
            this.root=root;this.context=context;this.vault=vault;this.facade=facade;this.note=note;
            this.selected=selected;this.initial=initial;this.initialMarkdown=markdown;
        }
        @Override public void close()throws Exception {
            if(!Files.exists(root))return;
            try(java.util.stream.Stream<Path> paths=Files.walk(root)){
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path->{
                    try {if(!Files.isSymbolicLink(path))Files.deleteIfExists(path);}
                    catch(Exception failure){throw new RuntimeException(failure);}
                });
            }
        }
    }
    private static final class FixtureContext extends ContextWrapper {
        private final File files;
        FixtureContext(Context base,File files){super(base);this.files=files;}
        @Override public File getFilesDir(){return files;}
        @Override public File getDir(String name,int mode){
            if(name==null||!name.matches("[A-Za-z0-9._-]{1,64}"))throw new IllegalArgumentException("fixture_dir_name");
            File dir=new File(files,"app_"+name);
            if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("fixture_dir_create_failed");
            if(!dir.isDirectory()||Files.isSymbolicLink(dir.toPath()))throw new IllegalStateException("fixture_dir_unsafe");
            return dir;
        }
        @Override public Context getApplicationContext(){return this;}
    }
}
