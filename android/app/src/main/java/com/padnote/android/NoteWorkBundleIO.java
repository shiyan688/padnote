package com.padnote.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Frozen note-context task package. The PDF is the only representation of handwriting. */
final class NoteWorkBundleIO {
    static final int MAX_ZIP_BYTES = 8 * 1024 * 1024;
    static final int MAX_EXPANDED_BYTES = 32 * 1024 * 1024;
    private NoteWorkBundleIO() {}

    static final class Frozen {
        final byte[] zip;
        final String content, sha256;
        final long noteRevision;
        Frozen(byte[] zip, String content, String sha256, long revision) {
            this.zip=zip.clone(); this.content=content; this.sha256=sha256; this.noteRevision=revision;
        }
    }

    static Frozen freeze(String noteId, long revision, String title, String instruction,
                         String presetId, String stylePrompt, String content, byte[] paperPdf)
            throws Exception {
        if (noteId == null || noteId.isEmpty() || revision <= 0 || title == null || title.isEmpty())
            throw new IllegalArgumentException("来源笔记修订无效");
        if (paperPdf == null || paperPdf.length < 8 || paperPdf.length > MAX_EXPANDED_BYTES)
            throw new IllegalArgumentException("纸面 PDF 超过 32 MiB 或无效");
        if(paperPdf[0]!='%'||paperPdf[1]!='P'||paperPdf[2]!='D'||paperPdf[3]!='F'||paperPdf[4]!='-')
            throw new IllegalArgumentException("冻结纸面不是有效 PDF");
        byte[] contentBytes=(content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if (contentBytes.length == 0 || contentBytes.length > MAX_EXPANDED_BYTES-paperPdf.length)
            throw new IllegalArgumentException("可读正文与纸面 PDF 总量超过 32 MiB");
        if (instruction == null || instruction.trim().isEmpty() || instruction.trim().length() > 16_000 ||
                presetId == null || presetId.isEmpty() || presetId.length() > 120)
            throw new IllegalArgumentException("任务要求不能为空");
        if(stylePrompt!=null&&stylePrompt.length()>12_000)
            throw new IllegalArgumentException("任务要求或风格提示过长");

        String contentHash=sha256(contentBytes), pdfHash=sha256(paperPdf);
        String commitment="input/content.md\0"+contentBytes.length+"\0"+contentHash+"\n"+
                "input/paper.pdf\0"+paperPdf.length+"\0"+pdfHash+"\n";
        String bundleHash=sha256(commitment.getBytes(StandardCharsets.UTF_8));
        JSONArray files=new JSONArray()
                .put(file("input/content.md","text/markdown",contentBytes.length,contentHash))
                .put(file("input/paper.pdf","application/pdf",paperPdf.length,pdfHash));
        JSONObject source=new JSONObject().put("note_id",noteId).put("note_revision",revision)
                .put("title",title).put("entrypoint","input/content.md")
                .put("bundle_sha256",bundleHash);
        JSONObject brief=new JSONObject().put("instruction",instruction.trim())
                .put("preset_id",presetId);
        if(stylePrompt!=null&&!stylePrompt.trim().isEmpty())brief.put("style_prompt",stylePrompt.trim());
        JSONObject request=new JSONObject().put("schema_version","1.0")
                .put("task_type","note.work.v1")
                .put("task_id","padnote-"+UUID.randomUUID().toString().replace("-",""))
                .put("source",source).put("brief",brief);
        JSONObject manifest=new JSONObject().put("schema_version","1.0").put("files",files);
        byte[] requestBytes=request.toString(2).getBytes(StandardCharsets.UTF_8);
        byte[] manifestBytes=manifest.toString(2).getBytes(StandardCharsets.UTF_8);
        if((long)requestBytes.length+manifestBytes.length+contentBytes.length+paperPdf.length>
                MAX_EXPANDED_BYTES)throw new IllegalArgumentException("任务包展开后超过 32 MiB");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)) {
            put(zip,"request.json",requestBytes);
            put(zip,"input/manifest.json",manifestBytes);
            put(zip,"input/content.md",contentBytes);
            put(zip,"input/paper.pdf",paperPdf);
            put(zip,"work/.keep",new byte[0]);
            put(zip,"output/.keep",new byte[0]);
        }
        byte[] packed=bytes.toByteArray();
        if(packed.length>MAX_ZIP_BYTES)throw new IllegalArgumentException("任务包压缩后超过 8 MiB");
        return new Frozen(packed,content,bundleHash,revision);
    }

    private static JSONObject file(String path,String mime,int size,String sha) throws Exception {
        return new JSONObject().put("path",path).put("media_type",mime)
                .put("size_bytes",size).put("sha256",sha);
    }
    private static void put(ZipOutputStream zip,String name,byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();
    }
    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result=new StringBuilder(digest.length*2);
        for(byte value:digest)result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        return result.toString();
    }
}
