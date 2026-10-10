package com.padnote.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.UUID;

/** Creates a retained, synthetic Android-produced typed-v2 archive for cross-platform readers. */
@RunWith(AndroidJUnit4.class)
public final class ArchiveV2FixtureExporterInstrumentedTest {
    @Test public void exportsAndRetainsFullMediaAndroidV2Archive() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File outputDirectory=LibraryBackupArchive.createPrivateCacheDirectory(context.getCacheDir(),"r10-archive-export");
        android.system.StructStat outputStat=android.system.Os.lstat(outputDirectory.getAbsolutePath());
        assertTrue("export directory belongs to this app",outputStat.st_uid==android.system.Os.getuid());
        assertEquals("export directory has no group/world write bits",0,outputStat.st_mode&0022);
        File archive=new File(outputDirectory,"application-valid-v2.padnote-library");
        File receipt=new File(outputDirectory,"fixture-receipt.json");

        NoteStore.Entry entry=NoteStore.create(context,"R10 Android v2 fixture");
        JSONObject body=new JSONObject(new String(readAsset("legacy-notes/schema8.json"),StandardCharsets.UTF_8));
        body.put("id",entry.id).put("title","R10 Android v2 fixture").put("updatedAt",entry.updatedAt)
                .put("pdfPageCount",1).put("pageCount",2);
        JSONArray strokes=body.getJSONArray("strokes");
        strokes.getJSONObject(0).put("id","stroke-"+UUID.randomUUID()).put("createdAt",entry.updatedAt);
        strokes.getJSONObject(0).getJSONArray("points").getJSONObject(0).put("timestamp",entry.updatedAt);
        JSONArray flows=body.getJSONArray("textFlows");
        flows.getJSONObject(0).put("id","flow-"+UUID.randomUUID());
        NoteStore.save(context,entry.id,"R10 Android v2 fixture",NoteJsonCodec.stringify(body));

        File pdf=NoteStore.pdfFile(context,entry.id);writePdf(pdf);
        Bitmap cover=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888);cover.eraseColor(Color.MAGENTA);
        CoverStore.assign(context,entry.id,cover);cover.recycle();

        File sourceVideo=new File(context.getCacheDir(),"r10-video-input-"+UUID.randomUUID()+".mp4");
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("video/roundtrip.mp4");
            FileOutputStream out=new FileOutputStream(sourceVideo)){
            byte[] buffer=new byte[16384];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);out.flush();out.getFD().sync();
        }
        byte[] videoBytes=Files.readAllBytes(sourceVideo.toPath());String videoSha=sha(videoBytes);
        new VideoAttachmentStore(context).attach(entry.id,entry.updatedAt,videoSha,videoSha,
                "task-"+UUID.randomUUID(),"remote-"+UUID.randomUUID(),"connection-"+UUID.randomUUID(),1,
                "HERMES","BRIDGE","bridge-"+UUID.randomUUID(),"instance-"+UUID.randomUUID(),
                sha("fixture-certificate".getBytes(StandardCharsets.UTF_8)),"artifact-"+UUID.randomUUID(),
                "R10 synthetic video","video/mp4",videoBytes.length,videoSha,sourceVideo);
        assertTrue("only the retained archive and receipt remain in the export directory",sourceVideo.delete());
        new VaultStore(context).write(entry.id,"R10 fixture Vault",1,entry.updatedAt,
                Collections.singletonList("Synthetic Vault source from Android producer."));

        File stageParent=new File(context.getFilesDir(),"r10-export-stage");
        if(!stageParent.exists())assertTrue("staging parent is created",stageParent.mkdirs());
        try(LibraryBackupSnapshot snapshot=LibraryBackupSnapshot.createForManualUpdate(context,
                Collections.singleton(entry.id),null,null)){
            assertEquals(2,snapshot.manifest.formatVersion);
            assertEquals("android",snapshot.manifest.producer.platform);
            assertEquals(1,snapshot.manifest.notes.size());
            assertTrue(snapshot.manifest.resources.stream().anyMatch(r->"pdf_original".equals(r.role)));
            assertTrue(snapshot.manifest.resources.stream().anyMatch(r->"assigned_cover_png".equals(r.role)));
            assertTrue(snapshot.manifest.resources.stream().anyMatch(r->"video_attachment_mp4".equals(r.role)&&r.byteLength>0));
            assertTrue(snapshot.manifest.resources.stream().anyMatch(r->"vault_entry_json".equals(r.role)));
            assertTrue(snapshot.manifest.resources.stream().anyMatch(r->"vault_storage_markdown".equals(r.role)));
            ArchiveUpdateProfile profile=snapshot.manifest.updateProfiles.get(0);
            assertEquals(ArchiveUpdateProfile.SCHEMA_VERSION,profile.schemaVersion);
            snapshot.write(archive,null,null);
        }

        File ordinaryCheck=new File(outputDirectory,"r10-ordinary-backup-check-"+UUID.randomUUID()+".padnote-library");
        try(LibraryBackupSnapshot ordinary=LibraryBackupSnapshot.create(context,Collections.singleton(entry.id),null,null)){
            assertEquals("ordinary backup keeps the copy-only schema",1,ordinary.manifest.formatVersion);
            assertTrue(ordinary.manifest.resources.stream().anyMatch(r->"video_attachment_mp4".equals(r.role)));
            assertTrue(ordinary.manifest.resources.stream().anyMatch(r->"pdf_original".equals(r.role)));
            assertTrue(ordinary.manifest.resources.stream().anyMatch(r->"assigned_cover_png".equals(r.role)));
            ordinary.write(ordinaryCheck,null,null);
        }finally{if(ordinaryCheck.exists())assertTrue("temporary ordinary archive is removed",ordinaryCheck.delete());}

        LibraryBackupManifest parsed;
        try(LibraryBackupArchive.StagedArchive staged=LibraryBackupArchive.stage(archive,stageParent,null,null)){
            parsed=staged.manifest();
            assertEquals(2,parsed.formatVersion);assertEquals("android",parsed.producer.platform);
            assertEquals(1,parsed.notes.size());assertEquals(1,parsed.updateProfiles.size());
            assertTrue(parsed.resources.stream().anyMatch(r->"vault_storage_markdown".equals(r.role)));
            LibraryBackupManifest.VaultEntry vault=parsed.vaultEntries.get(0);
            byte[] rawVault=Files.readAllBytes(staged.resourceFile(vault.sourceStorageResourceId).toPath());
            String rawVaultText=new String(rawVault,StandardCharsets.UTF_8);
            assertTrue("the v2 source Vault resource retains its storage frontmatter",rawVaultText.startsWith("---\ntitle: R10 fixture Vault\n"));
            assertTrue("the exact source owner is present in Vault frontmatter",rawVaultText.contains("\nnote-id: "+vault.sourceNoteId+"\n"));
            JSONObject portableVault=new JSONObject(new String(Files.readAllBytes(staged.resourceFile(vault.resourceId).toPath()),StandardCharsets.UTF_8));
            assertTrue("portable payload contains the Vault body without storage frontmatter",
                    portableVault.getString("markdown").contains("Synthetic Vault source from Android producer."));
            byte[] storedVideo=Files.readAllBytes(staged.resourceFile(parsed.videoAttachments.get(0).resourceId).toPath());
            assertArrayEquals(videoBytes,storedVideo);
            JSONObject storedBody=new JSONObject(new String(Files.readAllBytes(staged.resourceFile(parsed.notes.get(0).noteResourceId).toPath()),StandardCharsets.UTF_8));
            assertTrue(storedBody.getJSONArray("strokes").length()>0);assertTrue(storedBody.getJSONArray("textFlows").length()>0);
        }
        byte[] archiveSha=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive.toPath()));
        JSONObject receiptDoc=new JSONObject().put("format","padnote-r10-archive-fixture-receipt-v1")
                .put("relative_path","cache/"+outputDirectory.getName()+"/"+archive.getName())
                .put("bytes",archive.length()).put("sha256",hex(archiveSha)).put("format_version",parsed.formatVersion)
                .put("producer_platform",parsed.producer.platform).put("retained",true)
                .put("resource_roles",new JSONArray().put("note_document").put("pdf_original")
                        .put("assigned_cover_png").put("video_attachment_mp4").put("vault_entry_json")
                        .put("vault_storage_markdown"));
        try(FileOutputStream out=new FileOutputStream(receipt,false)){
            out.write(receiptDoc.toString().getBytes(StandardCharsets.UTF_8));out.flush();out.getFD().sync();
        }
        System.out.println("R10_ARCHIVE_EXPORT_RECEIPT="+receiptDoc);
        System.out.println("R10_ARCHIVE_EXPORT_RECEIPT_PATH=cache/"+outputDirectory.getName()+"/"+receipt.getName());
    }

    private static byte[] readAsset(String name)throws Exception {
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)){
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[8192];
            for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();
        }
    }
    private static void writePdf(File file)throws Exception {
        PdfDocument doc=new PdfDocument();PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(144,144,1).create());
        page.getCanvas().drawColor(Color.WHITE);Paint paint=new Paint();paint.setColor(Color.BLACK);page.getCanvas().drawText("R10 full media fixture",10,40,paint);doc.finishPage(page);
        try(FileOutputStream out=new FileOutputStream(file)){doc.writeTo(out);out.flush();out.getFD().sync();}finally{doc.close();}
    }
    private static String sha(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
