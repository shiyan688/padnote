package com.padnote.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.padnote.android.streaming.StreamingGroupStore;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Real store-level commit/reopen/retry coverage for digitization destination intents. */
@RunWith(AndroidJUnit4.class)
public final class DigitizationPublishRetryInstrumentedTest {
    @Test public void groupedAddPublishesAtCapturedGenerationAndColdRetryIsIdempotent() throws Exception {
        assumeGroupStore();
        Context isolated=isolatedContext();
        try {
            NoteStore.Entry entry=NoteStore.create(isolated,"分组新增样例");
            VaultStore vault=new VaultStore(isolated);
            vault.write(entry.id,"既有材料",1,111L,Collections.singletonList("prior material"));
            NoteGroupFacade facade=new NoteGroupFacade(isolated);
            StreamingGroupStore.Snapshot base=facade.adoptLegacyNote(entry.id,(before,staged)->true);
            String previousMaterial=findOnlyMaterialId(base);
            byte[] oldMarkdown=GroupAuthorityBridge.vaultView(base,previousMaterial).markdown;

            DigitizationStore.Target target=vault.captureDigitizationTarget(entry.id,null);
            assertEquals(DigitizationStore.TargetKind.GROUP,target.kind);
            assertEquals(DigitizationStore.TargetOperation.ADD,target.operation);
            assertEquals(base.revision,target.groupRevision);
            assertEquals(base.digest,target.groupDigest);
            String result=new VaultStore(isolated).publishDigitization(target,"新增材料",1,222L,
                    Collections.singletonList("new operation page"));

            StreamingGroupStore.Snapshot committed=facade.openGroup(entry.id);
            assertNotEquals("ADD publishes one CAS generation",base.revision,committed.revision);
            List<GroupAuthorityBridge.VaultView> views=GroupAuthorityBridge.vaultViews(committed);
            assertEquals("ADD keeps the old material and publishes one new one",2,views.size());
            assertArrayEquals("old material bytes survive the whole-group commit",oldMarkdown,
                    GroupAuthorityBridge.vaultView(committed,previousMaterial).markdown);
            GroupAuthorityBridge.VaultView added=GroupAuthorityBridge.vaultView(committed,target.materialId);
            assertEquals(result,added.fileName);
            assertTrue(new String(added.markdown,StandardCharsets.UTF_8)
                    .contains("digitization-operation-id: "+target.operationId));
            String committedRevision=committed.revision;

            VaultStore coldVault=new VaultStore(isolated);
            String retried=coldVault.publishDigitization(target,"新增材料",1,222L,
                    Collections.singletonList("new operation page"));
            assertEquals("cold retry resolves the same material",result,retried);
            StreamingGroupStore.Snapshot afterRetry=new NoteGroupFacade(isolated).openGroup(entry.id);
            assertEquals("retry does not add another group revision",committedRevision,afterRetry.revision);
            assertEquals(2,GroupAuthorityBridge.vaultViews(afterRetry).size());
            assertArrayEquals(added.markdown,
                    GroupAuthorityBridge.vaultView(afterRetry,target.materialId).markdown);
        } finally { delete(isolated.getFilesDir()); }
    }

    @Test public void groupedReplaceUsesCapturedGenerationAndColdRetryPreservesHistory() throws Exception {
        assumeGroupStore();
        Context isolated=isolatedContext();
        try {
            NoteStore.Entry entry=NoteStore.create(isolated,"分组替换样例");
            VaultStore vault=new VaultStore(isolated);
            vault.write(entry.id,"待替换材料",1,333L,Collections.singletonList("selected prior bytes"));
            NoteGroupFacade facade=new NoteGroupFacade(isolated);
            StreamingGroupStore.Snapshot base=facade.adoptLegacyNote(entry.id,(before,staged)->true);
            List<GroupAuthorityBridge.VaultView> originalViews=GroupAuthorityBridge.vaultViews(base);
            assertEquals(1,originalViews.size());
            GroupAuthorityBridge.VaultView previous=originalViews.get(0);
            byte[] previousBytes=previous.markdown.clone();

            VaultStore.VaultNote selected=null;
            for(VaultStore.VaultNote row:vault.list())if(entry.id.equals(row.noteId))selected=row;
            assertNotNull(selected);
            DigitizationStore.Target target=vault.captureDigitizationTarget(entry.id,selected);
            assertEquals(DigitizationStore.TargetKind.GROUP,target.kind);
            assertEquals(DigitizationStore.TargetOperation.REPLACE,target.operation);
            assertEquals(previous.materialId,target.materialId);
            assertEquals(base.revision,target.groupRevision);
            assertEquals(base.digest,target.groupDigest);

            String result=vault.publishDigitization(target,"已替换材料",1,444L,
                    Collections.singletonList("replacement page"));
            StreamingGroupStore.Snapshot committed=facade.openGroup(entry.id);
            assertNotEquals(base.revision,committed.revision);
            GroupAuthorityBridge.VaultView replacement=GroupAuthorityBridge.vaultView(committed,target.materialId);
            assertEquals(result,replacement.fileName);
            assertTrue(new String(replacement.markdown,StandardCharsets.UTF_8)
                    .contains("digitization-operation-id: "+target.operationId));
            assertArrayEquals("the captured historical generation remains exact",previousBytes,
                    GroupAuthorityBridge.vaultView(facade.openGroupRevision(entry.id,base.revision,base.digest),
                            target.materialId).markdown);
            String revision=committed.revision;

            String retried=new VaultStore(isolated).publishDigitization(target,"已替换材料",1,444L,
                    Collections.singletonList("replacement page"));
            assertEquals(result,retried);
            StreamingGroupStore.Snapshot afterRetry=new NoteGroupFacade(isolated).openGroup(entry.id);
            assertEquals("replacement retry is not a second commit",revision,afterRetry.revision);
            assertEquals(1,GroupAuthorityBridge.vaultViews(afterRetry).size());
            assertArrayEquals(replacement.markdown,
                    GroupAuthorityBridge.vaultView(afterRetry,target.materialId).markdown);
        } finally { delete(isolated.getFilesDir()); }
    }

    @Test public void legacyAddAndSelectedReplaceKeepSiblingMaterialsAcrossColdRetry() throws Exception {
        Context isolated=isolatedContext();
        try {
            NoteStore.Entry entry=NoteStore.create(isolated,"旧格式多材料样例");
            VaultStore vault=new VaultStore(isolated);
            String firstName=vault.write(entry.id,"第一份旧材料",1,555L,
                    Collections.singletonList("first legacy bytes"));
            String firstBytes=vault.read(firstName);
            String siblingName="legacy-sibling-"+UUID.randomUUID()+".md";
            String sibling=VaultStore.buildDigitizedMarkdown(entry.id,"第二份旧材料",1,666L,
                    Collections.singletonList("second legacy bytes"),System.currentTimeMillis());
            writeLegacyFile(isolated,siblingName,sibling);
            assertEquals("both fixture materials are discoverable",2,
                    rowsFor(vault.list(),entry.id).size());

            DigitizationStore.Target add=vault.captureDigitizationTarget(entry.id,null);
            assertEquals(DigitizationStore.TargetKind.LEGACY,add.kind);
            String addedName=vault.publishDigitization(add,"新增旧格式材料",1,777L,
                    Collections.singletonList("legacy add result"));
            assertEquals(firstBytes,vault.read(firstName));
            assertEquals(sibling,vault.read(siblingName));
            String addedBytes=vault.read(addedName);
            String addRetry=new VaultStore(isolated).publishDigitization(add,"新增旧格式材料",1,777L,
                    Collections.singletonList("legacy add result"));
            assertEquals(addedName,addRetry);
            assertEquals(addedBytes,new VaultStore(isolated).read(addRetry));

            VaultStore.VaultNote selected=null;
            for(VaultStore.VaultNote row:vault.list())if(siblingName.equals(row.fileName))selected=row;
            assertNotNull("replace target is the explicitly selected sibling",selected);
            DigitizationStore.Target replace=vault.captureDigitizationTarget(entry.id,selected);
            assertEquals(DigitizationStore.TargetOperation.REPLACE,replace.operation);
            assertEquals(siblingName,replace.legacyFileName);
            String replacedName=vault.publishDigitization(replace,"替换选中旧格式材料",1,888L,
                    Collections.singletonList("legacy replacement result"));
            assertEquals(siblingName,replacedName);
            String replacedBytes=vault.read(replacedName);
            assertEquals("unselected first material stays byte-exact",firstBytes,vault.read(firstName));
            assertEquals("added material stays byte-exact",addedBytes,vault.read(addedName));
            assertEquals(3,rowsFor(vault.list(),entry.id).size());

            String replaceRetry=new VaultStore(isolated).publishDigitization(replace,"替换选中旧格式材料",1,888L,
                    Collections.singletonList("legacy replacement result"));
            assertEquals(replacedName,replaceRetry);
            assertEquals(replacedBytes,new VaultStore(isolated).read(replaceRetry));
            assertEquals("neither cold retry duplicates nor removes siblings",3,
                    rowsFor(new VaultStore(isolated).list(),entry.id).size());
            assertEquals(firstBytes,new VaultStore(isolated).read(firstName));
            assertEquals(addedBytes,new VaultStore(isolated).read(addedName));
        } finally { delete(isolated.getFilesDir()); }
    }

    private static void assumeGroupStore() {
        org.junit.Assume.assumeTrue("group fixture requires API 27",Build.VERSION.SDK_INT>=27);
    }

    private static String findOnlyMaterialId(StreamingGroupStore.Snapshot snapshot)throws Exception {
        List<GroupAuthorityBridge.VaultView> views=GroupAuthorityBridge.vaultViews(snapshot);
        assertEquals(1,views.size());
        return views.get(0).materialId;
    }

    private static List<VaultStore.VaultNote> rowsFor(List<VaultStore.VaultNote> rows,String noteId) {
        java.util.ArrayList<VaultStore.VaultNote> result=new java.util.ArrayList<>();
        for(VaultStore.VaultNote row:rows)if(noteId.equals(row.noteId))result.add(row);
        return result;
    }

    private static void writeLegacyFile(Context context,String name,String markdown)throws Exception {
        File directory=new File(context.getFilesDir(),"vault");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new java.io.IOException("fixture vault mkdir failed");
        File file=new File(directory,name);
        try(FileOutputStream output=new FileOutputStream(file,false)) {
            output.write(markdown.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static Context isolatedContext() throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory=new File(base.getCacheDir(),"digitize-publish-test-"+UUID.randomUUID());
        if(!directory.mkdirs())throw new java.io.IOException("fixture directory create failed");
        return new ContextWrapper(base){
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return directory;}
        };
    }

    private static void delete(File file) {
        if(file==null||!file.exists())return;
        File[] children=file.listFiles();
        if(children!=null)for(File child:children)delete(child);
        if(!file.delete())throw new AssertionError("fixture cleanup failed");
    }
}
