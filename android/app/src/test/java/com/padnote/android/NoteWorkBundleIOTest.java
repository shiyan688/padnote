package com.padnote.android;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class NoteWorkBundleIOTest {
    private static final byte[] PDF="%PDF-1.7\nmock-paper".getBytes(StandardCharsets.US_ASCII);

    @Test public void freezesRequiredPaperAndReadableEntriesWithHashesAndPreset() throws Exception {
        String content="# Note\n\nAI: continue this derivation";
        NoteWorkBundleIO.Frozen frozen=NoteWorkBundleIO.freeze("note-1",42,"Algebra",
                "Find gaps","find_gaps_and_practice","",content,PDF);
        assertTrue(frozen.zip.length<=NoteWorkBundleIO.MAX_ZIP_BYTES);
        JSONObject request=null,manifest=null;ByteArrayOutputStream pdf=new ByteArrayOutputStream();
        ByteArrayOutputStream md=new ByteArrayOutputStream();String[] expected={"request.json",
                "input/manifest.json","input/content.md","input/paper.pdf","work/.keep","output/.keep"};
        try(ZipInputStream zip=new ZipInputStream(new java.io.ByteArrayInputStream(frozen.zip))){
            for(String name:expected){ZipEntry entry=zip.getNextEntry();assertNotNull(entry);assertEquals(name,entry.getName());
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[512];int count;
                while((count=zip.read(buffer))!=-1)out.write(buffer,0,count);
                if(name.equals("request.json"))request=new JSONObject(out.toString("UTF-8"));
                if(name.equals("input/manifest.json"))manifest=new JSONObject(out.toString("UTF-8"));
                if(name.equals("input/content.md"))md=out;
                if(name.equals("input/paper.pdf"))pdf=out;
            }
            assertNull("no extra archive entries",zip.getNextEntry());
        }
        assertArrayEquals(PDF,pdf.toByteArray());assertEquals(content,md.toString("UTF-8"));
        assertEquals("note.work.v1",request.getString("task_type"));
        assertEquals("note-1",request.getJSONObject("source").getString("note_id"));
        assertEquals(42,request.getJSONObject("source").getLong("note_revision"));
        assertEquals("find_gaps_and_practice",request.getJSONObject("brief").getString("preset_id"));
        JSONArray files=manifest.getJSONArray("files");assertEquals(2,files.length());
        assertEntry(files.getJSONObject(0),"input/content.md",content.getBytes(StandardCharsets.UTF_8));
        assertEntry(files.getJSONObject(1),"input/paper.pdf",PDF);
        assertEquals("input/content.md",request.getJSONObject("source").getString("entrypoint"));
        String commitment="input/content.md\0"+content.getBytes(StandardCharsets.UTF_8).length+"\0"+
                files.getJSONObject(0).getString("sha256")+"\ninput/paper.pdf\0"+PDF.length+"\0"+
                files.getJSONObject(1).getString("sha256")+"\n";
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(commitment.getBytes(StandardCharsets.UTF_8))),
                request.getJSONObject("source").getString("bundle_sha256"));
        assertEquals(5,request.getJSONObject("source").length());
        assertEquals(2,request.getJSONObject("brief").length());
    }

    @Test public void enforcesDesktopBriefLimitsAndOptionalEditableStyle() throws Exception {
        String style="whiteboard";
        NoteWorkBundleIO.Frozen frozen=NoteWorkBundleIO.freeze("note",1,"Title","go","explain_video",style,"content",PDF);
        assertNotNull(frozen);
        try{NoteWorkBundleIO.freeze("note",1,"Title",repeat('x',16_001),"continue","","content",PDF);fail();}
        catch(IllegalArgumentException expected){}
        try{NoteWorkBundleIO.freeze("note",1,"Title","go",repeat('x',121),"","content",PDF);fail();}
        catch(IllegalArgumentException expected){}
        try{NoteWorkBundleIO.freeze("note",1,"Title","go","continue",repeat('x',12_001),"content",PDF);fail();}
        catch(IllegalArgumentException expected){}
    }

    @Test public void rejectsOversizeExpandedPaperAndEmptyInstruction() throws Exception {
        byte[] tooLarge=new byte[NoteWorkBundleIO.MAX_EXPANDED_BYTES+1];
        System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII),0,tooLarge,0,5);
        try{NoteWorkBundleIO.freeze("note",1,"Title","go","continue","","text",tooLarge);fail();}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("32 MiB"));}
        try{NoteWorkBundleIO.freeze("note",1,"Title"," ","continue","","text",PDF);fail();}
        catch(IllegalArgumentException expected){}
    }

    @Test public void rejectsZipThatCannotFitTheBridgeBundleLimit() throws Exception {
        byte[] random=new byte[NoteWorkBundleIO.MAX_ZIP_BYTES+1024];
        new java.util.Random(7123).nextBytes(random);
        System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII),0,random,0,5);
        try{NoteWorkBundleIO.freeze("note",1,"Title","go","continue","","text",random);fail();}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("8 MiB"));}
    }

    private static void assertEntry(JSONObject row,String path,byte[] data) throws Exception {
        assertEquals(path,row.getString("path"));assertEquals(data.length,row.getInt("size_bytes"));
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(data)),row.getString("sha256"));
    }
    private static String repeat(char value,int count){char[] chars=new char[count];Arrays.fill(chars,value);return new String(chars);}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b&255));return out.toString();}
}
