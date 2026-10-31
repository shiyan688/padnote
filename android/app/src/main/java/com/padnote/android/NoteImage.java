package com.padnote.android;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.zip.CRC32;
import java.util.UUID;

/** Page-relative image geometry; immutable pixels are shared by undo snapshots. */
final class NoteImage {
    static final class StoredImageInfo {
        final int width;
        final int height;
        final int encodedLength;

        StoredImageInfo(int width, int height, int encodedLength) {
            this.width = width;
            this.height = height;
            this.encodedLength = encodedLength;
        }
    }
    static final int MAX_EDGE = 1600;
    static final int MAX_DOCUMENT_PIXELS = 12 * 1024 * 1024;
    static final int MAX_DOCUMENT_ENCODED = 20 * 1024 * 1024;
    final String id;
    final Bitmap bitmap;
    final String png;
    final AgentImageReceipt agentImage;
    int page;
    /** Canonical serialized binary64 geometry; float fields below are render projections. */
    double x64, y64, width64, height64;
    float x, y, width, height;

    NoteImage(String id, Bitmap bitmap, String png, int page,
              double x, double y, double width, double height) {
        this(id,bitmap,png,page,x,y,width,height,null);
    }

    NoteImage(String id, Bitmap bitmap, String png, int page,
              double x, double y, double width, double height, AgentImageReceipt agentImage) {
        this.id = id; this.bitmap = bitmap; this.png = png; this.page = page;
        this.agentImage=agentImage;
        setGeometry(x, y, width, height);
    }

    void setGeometry(double x, double y, double width, double height) {
        double checkedX = finiteNonnegative(x, "x");
        double checkedY = finiteNonnegative(y, "y");
        double checkedWidth = finiteNonnegative(width, "width");
        double checkedHeight = finiteNonnegative(height, "height");
        float renderX = PersistedGeometry.renderFloat(checkedX);
        float renderY = PersistedGeometry.renderFloat(checkedY);
        float renderWidth = PersistedGeometry.renderFloat(checkedWidth);
        float renderHeight = PersistedGeometry.renderFloat(checkedHeight);
        this.x64 = checkedX;
        this.y64 = checkedY;
        this.width64 = checkedWidth;
        this.height64 = checkedHeight;
        this.x = renderX;
        this.y = renderY;
        this.width = renderWidth;
        this.height = renderHeight;
    }

    private static double finiteNonnegative(double value, String key) {
        if (!Double.isFinite(value) || value < 0d) throw new IllegalArgumentException("image " + key + " invalid");
        return value;
    }

    NoteImage copy(boolean newId) {
        return new NoteImage(newId ? UUID.randomUUID().toString() : id,
                bitmap, png, page, x64, y64, width64, height64,newId?null:agentImage);
    }

    static NoteImage read(ContentResolver resolver, Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalArgumentException("无法读取图片");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > MAX_EDGE) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap;
        try (InputStream input = resolver.openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) throw new IllegalArgumentException("图片格式不受支持");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalArgumentException("无法编码图片");
        return new NoteImage(UUID.randomUUID().toString(), bitmap,
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP), 0, 0, 0, 0, 0);
    }

    /** Bounded static PNG/JPEG normalization used only for a verified Agent artifact. */
    static NoteImage fromAgentArtifact(File file, AgentTaskStore.Task task,
                                       AgentConnectionStore.Config connection,
                                       AgentTaskStore.Artifact artifact,
                                       String sourceNoteId,long sourceNoteRevision) throws Exception {
        if(file==null||task==null||connection==null||artifact==null||!task.matches(connection)
                ||task.status!=AgentTaskStore.Status.COMPLETED||artifact.sizeBytes<1
                ||artifact.sizeBytes>AgentArtifactDownloader.MAX_IMAGE_BYTES
                ||sourceNoteRevision<0
                ||!("image/png".equals(artifact.mediaType)||"image/jpeg".equals(artifact.mediaType)))
            throw new IllegalArgumentException("AGENT_IMAGE_DESCRIPTOR_INVALID");
        boolean listed=false;for(AgentTaskStore.Artifact item:task.artifacts)
            if(item.id.equals(artifact.id)&&item.name.equals(artifact.name)&&item.mediaType.equals(artifact.mediaType)
                    &&item.sizeBytes==artifact.sizeBytes&&item.sha256.equalsIgnoreCase(artifact.sha256)){listed=true;break;}
        if(!listed)throw new IllegalArgumentException("AGENT_IMAGE_DESCRIPTOR_CHANGED");
        long fileLength=file.length();
        if(!file.isFile()||fileLength!=artifact.sizeBytes||fileLength>AgentArtifactDownloader.MAX_IMAGE_BYTES)
            throw new IllegalArgumentException("AGENT_IMAGE_LENGTH_INVALID");
        byte[] source;
        try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream((int)fileLength)){
            byte[] buffer=new byte[32*1024];int count;long total=0;
            while((count=in.read(buffer))>=0){if(count==0)continue;total+=count;
                if(total>AgentArtifactDownloader.MAX_IMAGE_BYTES)throw new IllegalArgumentException("AGENT_IMAGE_LENGTH_INVALID");
                out.write(buffer,0,count);}
            source=out.toByteArray();
        }
        String sourceSha=AgentImageReceipt.sha256(source);
        if(!sourceSha.equals(artifact.sha256.toLowerCase(java.util.Locale.ROOT)))
            throw new IllegalArgumentException("AGENT_IMAGE_DIGEST_INVALID");
        int orientation=1;
        if("image/png".equals(artifact.mediaType)){
            if(!isPng(source)||containsApngControlChunk(source))throw new IllegalArgumentException("AGENT_IMAGE_STATIC_PNG_REQUIRED");
        }else{
            if(!isJpeg(source)||!hasJpegEnd(source))throw new IllegalArgumentException("AGENT_IMAGE_JPEG_TRUNCATED_OR_MIME_MISMATCH");
            android.media.ExifInterface exif=new android.media.ExifInterface(new java.io.ByteArrayInputStream(source));
            orientation=exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
        }
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(source,0,source.length,bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>16384||bounds.outHeight>16384
                ||(long)bounds.outWidth*bounds.outHeight>64L*1024*1024)
            throw new IllegalArgumentException("AGENT_IMAGE_DIMENSIONS_INVALID");
        String detected=bounds.outMimeType;
        if(!artifact.mediaType.equals(detected))throw new IllegalArgumentException("AGENT_IMAGE_MIME_MISMATCH");
        int sample=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/sample>MAX_EDGE)sample*=2;
        BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=sample;
        Bitmap decoded=BitmapFactory.decodeByteArray(source,0,source.length,opts);
        if(decoded==null)throw new IllegalArgumentException("AGENT_IMAGE_DECODE_FAILED");
        Bitmap normalized=orientAndScale(decoded,orientation);
        if(normalized!=decoded&&!decoded.isRecycled())decoded.recycle();
        ByteArrayOutputStream pngOut=new ByteArrayOutputStream();
        if(!normalized.compress(Bitmap.CompressFormat.PNG,100,pngOut)){
            normalized.recycle();throw new IllegalArgumentException("AGENT_IMAGE_ENCODE_FAILED");}
        byte[] pngBytes=pngOut.toByteArray();
        String encoded=Base64.encodeToString(pngBytes,Base64.NO_WRAP);
        if(encoded.length()>MAX_DOCUMENT_ENCODED){normalized.recycle();throw new IllegalArgumentException("AGENT_IMAGE_NORMALIZED_TOO_LARGE");}
        AgentImageReceipt receipt=new AgentImageReceipt(task.clientTaskId,task.connectionId,
                task.instanceId==null||task.instanceId.isEmpty()?null:task.instanceId,
                task.kind.name().toLowerCase(java.util.Locale.ROOT),task.transport.name().toLowerCase(java.util.Locale.ROOT),
                sourceNoteId,sourceNoteRevision,artifact.id,artifact.name,artifact.mediaType,artifact.sizeBytes,
                sourceSha,AgentImageReceipt.sha256(pngBytes),System.currentTimeMillis());
        return new NoteImage("agent-image-"+receipt.applicationId,normalized,encoded,0,0,0,0,0,receipt);
    }

    private static Bitmap orientAndScale(Bitmap source,int orientation){
        Matrix matrix=new Matrix();
        switch(orientation){
            case 2: matrix.setScale(-1,1);break;
            case 3: matrix.setRotate(180);break;
            case 4: matrix.setScale(1,-1);break;
            case 5: matrix.setRotate(90);matrix.postScale(-1,1);break;
            case 6: matrix.setRotate(90);break;
            case 7: matrix.setRotate(-90);matrix.postScale(-1,1);break;
            case 8: matrix.setRotate(-90);break;
            default: break;
        }
        int longest=Math.max(source.getWidth(),source.getHeight());
        float scale=longest>MAX_EDGE?MAX_EDGE/(float)longest:1f;
        if(scale<1f)matrix.postScale(scale,scale);
        Bitmap transformed=Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),matrix,true);
        return transformed;
    }

    private static boolean isPng(byte[] b){return b.length>=8&&(b[0]&255)==137&&b[1]=='P'&&b[2]=='N'&&b[3]=='G'&&b[4]==13&&b[5]==10&&b[6]==26&&b[7]==10;}
    private static boolean isJpeg(byte[] b){return b.length>=3&&(b[0]&255)==255&&(b[1]&255)==216&&(b[2]&255)==255;}
    private static boolean containsApngControlChunk(byte[] b){
        int p=8;boolean first=true;
        while(p+12<=b.length){long n=((long)b[p]&255)<<24|((long)b[p+1]&255)<<16|((long)b[p+2]&255)<<8|((long)b[p+3]&255);
            if(n>Integer.MAX_VALUE||p+12L+n>b.length)throw new IllegalArgumentException("AGENT_IMAGE_PNG_TRUNCATED");
            if(first&&(!(b[p+4]=='I'&&b[p+5]=='H'&&b[p+6]=='D'&&b[p+7]=='R')||n!=13))
                throw new IllegalArgumentException("AGENT_IMAGE_PNG_STRUCTURE_INVALID");
            first=false;
            CRC32 crc=new CRC32();crc.update(b,p+4,(int)n+4);
            long stored=((long)b[p+8+(int)n]&255)<<24|((long)b[p+9+(int)n]&255)<<16
                    |((long)b[p+10+(int)n]&255)<<8|((long)b[p+11+(int)n]&255);
            if(crc.getValue()!=stored)throw new IllegalArgumentException("AGENT_IMAGE_PNG_CRC_INVALID");
            if(b[p+4]=='a'&&b[p+5]=='c'&&b[p+6]=='T'&&b[p+7]=='L')return true;
            String type=new String(b,p+4,4,java.nio.charset.StandardCharsets.US_ASCII);p+=(int)n+12;
            if("IEND".equals(type)){
                if(n!=0||p!=b.length)throw new IllegalArgumentException("AGENT_IMAGE_PNG_TRAILING_DATA");
                return false;
            }
        }
        throw new IllegalArgumentException("AGENT_IMAGE_PNG_TRUNCATED");
    }

    private static boolean hasJpegEnd(byte[] b){return b.length>=4&&(b[b.length-2]&255)==255&&(b[b.length-1]&255)==217;}

    JSONObject toJson() throws JSONException {
        JSONObject json=new JSONObject().put("id", id).put("png", png).put("page", page)
                .put("x", x64).put("y", y64).put("width", width64).put("height", height64);
        if(agentImage!=null)json.put("agentImage",agentImage.toJson());
        return json;
    }

    static NoteImage fromJson(JSONObject json) throws JSONException {
        String png = json.getString("png");
        byte[] bytes = decodeAndValidate(png);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) throw new JSONException("图片无法解码");
        AgentImageReceipt receipt=json.has("agentImage")?AgentImageReceipt.fromJson(json.getJSONObject("agentImage")):null;
        if(receipt!=null)receipt.validatePng(bytes);
        return new NoteImage(json.getString("id"), bitmap, png, json.getInt("page"),
                finite(json, "x"), finite(json, "y"), finite(json, "width"), finite(json, "height"),receipt);
    }

    /** Validates encoded storage without allocating the full pixel bitmap. */
    static StoredImageInfo inspectJson(JSONObject json) throws JSONException {
        String png = json.getString("png");
        byte[] bytes = decodeAndValidate(png);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        return new StoredImageInfo(bounds.outWidth, bounds.outHeight, png.length());
    }

    private static byte[] decodeAndValidate(String png) throws JSONException {
        if (png.length() > MAX_DOCUMENT_ENCODED) throw new JSONException("图片数据过大");
        byte[] bytes;
        try { bytes = Base64.decode(png, Base64.DEFAULT); }
        catch (IllegalArgumentException error) { throw new JSONException("图片编码无效"); }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > MAX_EDGE
                || bounds.outHeight > MAX_EDGE) throw new JSONException("图片尺寸无效");
        return bytes;
    }

    private static double finite(JSONObject json, String key) throws JSONException {
        double value = json.getDouble(key);
        if (!Double.isFinite(value) || value < 0) throw new JSONException("图片位置或尺寸无效");
        return value;
    }
}
