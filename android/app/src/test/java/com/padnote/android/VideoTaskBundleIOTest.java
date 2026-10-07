package com.padnote.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class VideoTaskBundleIOTest {
    @Test public void frozenBundleBindsExactMarkdownAndRevisionAndReturnsDefensiveCopies() throws Exception {
        String markdown="# Lesson\n\nπ + x²\n";
        VideoTaskBundleIO.FrozenBundle frozen=VideoTaskBundleIO.freeze(
                "note-source",1700000000123L,"Lesson",markdown,"students","understand",45,"voice",1f);
        byte[] first=frozen.copyBytes(); byte[] expected=first.clone(); first[0]^=1;
        assertArrayEquals(expected,frozen.copyBytes());
        assertEquals(markdown,frozen.markdownPreview());
        Map<String,byte[]> entries=unzip(frozen.copyBytes());
        JSONObject request=new JSONObject(new String(entries.get("request.json"),java.nio.charset.StandardCharsets.UTF_8));
        JSONObject source=request.getJSONObject("source");
        assertEquals("note-source",source.getString("note_id"));
        assertEquals(1700000000123L,source.getLong("note_revision"));
        JSONObject manifest=new JSONObject(new String(entries.get("input/manifest.json"),java.nio.charset.StandardCharsets.UTF_8));
        String digest=manifest.getJSONArray("files").getJSONObject(0).getString("sha256");
        assertEquals(frozen.contentSha256,digest);
        assertArrayEquals(markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8),entries.get("input/content.md"));
        assertTrue(frozen.sizeBytes()>markdown.length());
        assertNotEquals(1L,source.getLong("note_revision"));
    }

    @Test public void invalidSourceRevisionIsRejectedInsteadOfCoercedToOne() throws Exception {
        try{VideoTaskBundleIO.freeze("note-source",0,"Lesson","body","students","understand",45,"voice",1f);org.junit.Assert.fail("invalid revision accepted");}
        catch(IllegalArgumentException expected){}
    }

    private static Map<String,byte[]> unzip(byte[] bytes)throws Exception {
        Map<String,byte[]> out=new HashMap<>();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){java.io.ByteArrayOutputStream value=new java.io.ByteArrayOutputStream();
                byte[] buffer=new byte[1024];int n;while((n=zip.read(buffer))>=0)if(n>0)value.write(buffer,0,n);
                out.put(entry.getName(),value.toByteArray());zip.closeEntry();}}
        return out;
    }
}
