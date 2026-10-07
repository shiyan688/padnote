package com.padnote.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.security.MessageDigest;
import java.io.FileInputStream;
import java.io.FileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

/**
 * Notebook covers: one optional PNG per note plus a preset library.
 *
 * <p>Convention over schema: a cover lives next to its note as
 * {@code notes/<noteId>.cover.png}, so {@link NoteStore}'s index format does
 * not change and renames/deletes need no migration. User-imported presets are
 * PNGs under the private {@code covers/} directory; the built-in presets are
 * drawn procedurally (design language: stationery realism, zero binaries).
 */
final class CoverStore {
    /** One selectable preset cover. */
    static final class Preset {
        final String id;
        final String label;
        final Bitmap bitmap;

        Preset(String id, String label, Bitmap bitmap) {
            this.id = id;
            this.label = label;
            this.bitmap = bitmap;
        }
    }

    static final int PRESET_WIDTH = 640;
    static final int PRESET_HEIGHT = 400;
    /** Covers are downscaled to at most this width when assigned/imported. */
    static final int MAX_STORED_WIDTH = 720;

    private static final String COVER_SUFFIX = ".cover.png";

    private CoverStore() {
    }

    /** Where a note's cover image lives; existence means the note has one. */
    static File coverFile(File notesDirectory, String noteId) {
        return new File(notesDirectory, noteId + COVER_SUFFIX);
    }

    private static File coverFile(Context context, String noteId) {
        return coverFile(new File(context.getFilesDir(), NoteStore.notesDirectoryName()),
                noteId);
    }

    static boolean has(Context context, String noteId) {
        try { NoteStore.requireRestoreGroupV2Visible(context,noteId); return coverFile(context, noteId).isFile(); }
        catch (Exception hidden) { return false; }
    }

    /** Writes {@code source} as the note's cover, downscaled, replacing any old one. */
    static void assign(Context context, String noteId, Bitmap source) throws Exception {
        NoteStore.requireNoteMaterialAccess(context,noteId);
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("封面位图不可用");
        }
        File file = coverFile(context, noteId);
        File directory = file.getParentFile();
        if (directory != null && !directory.exists()) {
            directory.mkdirs();
        }
        Bitmap scaled = scaleToFit(source, MAX_STORED_WIDTH);
        FileOutputStream stream = new FileOutputStream(file);
        try {
            scaled.compress(Bitmap.CompressFormat.PNG, 90, stream);
        } finally {
            stream.close();
            if (scaled != source) {
                scaled.recycle();
            }
        }
    }

    /** Promotes a byte-exact, already archive-validated PNG to a new note without re-encoding. */
    static void restoreRaw(Context context, String noteId, File staged, long size, String sha256) throws Exception {
        File target=coverFile(context,noteId), parent=target.getParentFile();
        LibraryBackupArchive.AndroidSafeFiles.canonicalPrivateAndroidDirectory(parent);
        if(target.exists()){
            if(!matchesRaw(target,size,sha256))throw new java.io.IOException("RESTORE_COVER_TARGET_CONFLICT");
            return;
        }
        StructStat link=Os.lstat(staged.getAbsolutePath());
        if((link.st_mode&OsConstants.S_IFMT)!=OsConstants.S_IFREG||link.st_nlink!=1||link.st_size!=size||size<=0||size>8L*1024*1024)
            throw new java.io.IOException("RESTORE_COVER_UNSAFE");
        FileDescriptor inputFd=Os.open(staged.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);
        FileDescriptor outputFd=null;
        long outputDev=-1,outputIno=-1;
        try {
            StructStat before=Os.fstat(inputFd);
            if(before.st_dev!=link.st_dev||before.st_ino!=link.st_ino||before.st_size!=size||before.st_nlink!=1)
                throw new java.io.IOException("RESTORE_COVER_CHANGED");
            outputFd=Os.open(target.getAbsolutePath(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600);
            StructStat created=Os.fstat(outputFd);outputDev=created.st_dev;outputIno=created.st_ino;
            MessageDigest digest=MessageDigest.getInstance("SHA-256"); long total=0; byte[] buffer=new byte[16*1024];
            try(FileInputStream in=new FileInputStream(inputFd);FileOutputStream out=new FileOutputStream(outputFd)){
                int n;while((n=in.read(buffer))!=-1){total+=n;if(total>size)throw new java.io.IOException("RESTORE_COVER_CHANGED");digest.update(buffer,0,n);out.write(buffer,0,n);}out.flush();out.getFD().sync();
                StructStat after=Os.fstat(in.getFD());if(total!=size||after.st_dev!=before.st_dev||after.st_ino!=before.st_ino||after.st_size!=before.st_size||after.st_mtime!=before.st_mtime||after.st_ctime!=before.st_ctime)throw new java.io.IOException("RESTORE_COVER_CHANGED");
            }
            String actual=hex(digest.digest());if(!actual.equals(sha256))throw new java.io.IOException("RESTORE_COVER_SHA");
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(target.getAbsolutePath(),options);
            if(options.outWidth<=0||options.outHeight<=0||(long)options.outWidth*options.outHeight>NoteImage.MAX_DOCUMENT_PIXELS)throw new java.io.IOException("RESTORE_COVER_INVALID");
        } catch(Exception error){deleteIfIdentity(target,outputDev,outputIno);throw error;}
        finally{try{if(outputFd!=null)Os.close(outputFd);}catch(Exception ignored){} try{Os.close(inputFd);}catch(Exception ignored){}}
    }

    static boolean matchesRaw(File file,long size,String sha256)throws Exception{
        StructStat link=Os.lstat(file.getAbsolutePath());
        if((link.st_mode&OsConstants.S_IFMT)!=OsConstants.S_IFREG||link.st_nlink!=1||link.st_size!=size)return false;
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;byte[] buffer=new byte[16*1024];
        FileDescriptor fd=Os.open(file.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);
        try(FileInputStream in=new FileInputStream(fd)){int n;while((n=in.read(buffer))!=-1){total+=n;if(total>size)return false;digest.update(buffer,0,n);}StructStat after=Os.fstat(in.getFD());return total==size&&after.st_dev==link.st_dev&&after.st_ino==link.st_ino&&after.st_size==link.st_size&&hex(digest.digest()).equals(sha256);}
    }

    static void rollbackRestoredRaw(Context context,String noteId,String sha256,LibraryRestoreGroupV2.Lease lease,
            LibraryRestoreGroupV2.MemberProof proof)throws Exception {
        if(lease==null||proof==null||proof.kind!=LibraryRestoreGroupV2.Kind.ASSIGNED_COVER||
                !noteId.equals(proof.localObjectId)||!sha256.equals(proof.payloadSha256)||
                lease.recoveryStatus()==LibraryRestoreGroupV2.RecoveryPhase.COMMITTED)
            throw new java.io.IOException("RESTORE_COVER_ROLLBACK_AUTHORITY");
        File target=coverFile(context,noteId);LibraryBackupArchive.SafeFiles safe=new LibraryBackupArchive.AndroidSafeFiles();
        try(LibraryBackupArchive.SafeFiles.PublishLock ignored=safe.lockPublish(target)){
            android.system.StructStat current;
            try{current=Os.lstat(target.getAbsolutePath());}catch(android.system.ErrnoException missing){if(missing.errno==android.system.OsConstants.ENOENT)return;throw missing;}
            if(!OsConstants.S_ISREG(current.st_mode)||current.st_nlink!=1||!matchesRaw(target,current.st_size,sha256))
                throw new java.io.IOException("RESTORE_COVER_ROLLBACK_OWNERSHIP");
            android.system.StructStat recheck=Os.lstat(target.getAbsolutePath());
            if(current.st_dev!=recheck.st_dev||current.st_ino!=recheck.st_ino||current.st_size!=recheck.st_size||current.st_nlink!=recheck.st_nlink)
                throw new java.io.IOException("RESTORE_COVER_ROLLBACK_CHANGED");
            Os.remove(target.getAbsolutePath());safe.syncDirectory(target.getParentFile());
        }
    }

    static void restoreRawToFile(File staged,File target,long size,String sha256)throws Exception{
        if(target.exists())throw new java.io.IOException("RESTORE_RESOURCE_TARGET_EXISTS");
        StructStat link=Os.lstat(staged.getAbsolutePath());
        if((link.st_mode&OsConstants.S_IFMT)!=OsConstants.S_IFREG||link.st_nlink!=1||link.st_size!=size||size<=0||size>100L*1024*1024)
            throw new java.io.IOException("RESTORE_RESOURCE_UNSAFE");
        FileDescriptor inputFd=Os.open(staged.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);
        FileDescriptor outputFd=null;
        long outputDev=-1,outputIno=-1;
        try{
            StructStat before=Os.fstat(inputFd);
            if(before.st_dev!=link.st_dev||before.st_ino!=link.st_ino||before.st_size!=size||before.st_nlink!=1)throw new java.io.IOException("RESTORE_RESOURCE_CHANGED");
            outputFd=Os.open(target.getAbsolutePath(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600);
            StructStat created=Os.fstat(outputFd);outputDev=created.st_dev;outputIno=created.st_ino;
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;byte[] buffer=new byte[32768];
            try(FileInputStream in=new FileInputStream(inputFd);FileOutputStream out=new FileOutputStream(outputFd)){
                int n;while((n=in.read(buffer))!=-1){total+=n;if(total>size)throw new java.io.IOException("RESTORE_RESOURCE_CHANGED");digest.update(buffer,0,n);out.write(buffer,0,n);}out.flush();out.getFD().sync();
                StructStat after=Os.fstat(in.getFD());if(total!=size||after.st_dev!=before.st_dev||after.st_ino!=before.st_ino||after.st_size!=before.st_size||after.st_mtime!=before.st_mtime||after.st_ctime!=before.st_ctime)throw new java.io.IOException("RESTORE_RESOURCE_CHANGED");
            }
            if(!hex(digest.digest()).equals(sha256))throw new java.io.IOException("RESTORE_RESOURCE_SHA");
        }catch(Exception error){deleteIfIdentity(target,outputDev,outputIno);throw error;}
        finally{try{if(outputFd!=null)Os.close(outputFd);}catch(Exception ignored){}try{Os.close(inputFd);}catch(Exception ignored){}}
    }

    private static void deleteIfIdentity(File file,long dev,long ino){try{if(dev<0||ino<0)return;StructStat s=Os.lstat(file.getAbsolutePath());if((s.st_mode&OsConstants.S_IFMT)==OsConstants.S_IFREG&&s.st_nlink==1&&s.st_dev==dev&&s.st_ino==ino)Os.remove(file.getAbsolutePath());}catch(Exception ignored){}}

    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}

    static void remove(Context context, String noteId) {
        try { NoteStore.requireNoteMaterialAccess(context,noteId); } catch (Exception hidden) { return; }
        File file = coverFile(context, noteId);
        if (file.isFile()) {
            file.delete();
        }
    }

    /** Decodes a cover sampled near {@code targetWidth} to keep the shelf smooth. */
    static Bitmap load(Context context, String noteId, int targetWidth) {
        try { NoteStore.requireRestoreGroupV2Visible(context,noteId); } catch (Exception hidden) { return null; }
        File file = coverFile(context, noteId);
        if (!file.isFile()) {
            return null;
        }
        return decodeScaled(file, Math.max(1, targetWidth));
    }

    /** Built-in procedural covers; cheap enough to regenerate per dialog. */
    static List<Preset> builtinPresets() {
        List<Preset> presets = new ArrayList<>();
        presets.add(new Preset("builtin-ruled", "米白横线",
                drawRuled(Color.rgb(250, 248, 242), Color.rgb(206, 214, 222))));
        presets.add(new Preset("builtin-grid", "浅蓝方格",
                drawGrid(Color.rgb(245, 249, 253), Color.rgb(196, 216, 236))));
        presets.add(new Preset("builtin-dots", "墨绿点阵",
                drawDots(Color.rgb(235, 244, 240), Color.rgb(47, 128, 91))));
        presets.add(new Preset("builtin-blue", "书写蓝",
                drawSolid(Color.rgb(40, 94, 168), Color.rgb(232, 240, 250))));
        presets.add(new Preset("builtin-vault", "知识库绿",
                drawSolid(Color.rgb(47, 128, 91), Color.rgb(233, 243, 238))));
        presets.add(new Preset("builtin-ochre", "赭石",
                drawSolid(Color.rgb(164, 106, 42), Color.rgb(250, 242, 230))));
        return presets;
    }

    /** User-imported preset covers, oldest first. */
    static List<Preset> userPresets(Context context) {
        List<Preset> presets = new ArrayList<>();
        File directory = context.getDir("covers", Context.MODE_PRIVATE);
        File[] files = directory.listFiles();
        if (files == null) {
            return presets;
        }
        java.util.Arrays.sort(files);
        for (File file : files) {
            if (!file.getName().startsWith("preset-") || !file.getName().endsWith(".png")) {
                continue;
            }
            Bitmap bitmap = decodeScaled(file, 320);
            if (bitmap != null) {
                presets.add(new Preset("user:" + file.getName(), file.getName(), bitmap));
            }
        }
        return presets;
    }

    /** Copies an imported image into the user preset library. */
    static File addToPresets(Context context, Bitmap source) throws Exception {
        File directory = context.getDir("covers", Context.MODE_PRIVATE);
        File file = new File(directory, String.format(Locale.CHINA,
                "preset-%d.png", System.currentTimeMillis()));
        Bitmap scaled = scaleToFit(source, MAX_STORED_WIDTH);
        FileOutputStream stream = new FileOutputStream(file);
        try {
            scaled.compress(Bitmap.CompressFormat.PNG, 90, stream);
        } finally {
            stream.close();
            if (scaled != source) {
                scaled.recycle();
            }
        }
        return file;
    }

    /** Downsamples a stored image file toward {@code targetWidth}. */
    private static Bitmap decodeScaled(File file, int targetWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, targetWidth);
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    static int sampleSize(int sourceWidth, int targetWidth) {
        int sample = 1;
        while (sourceWidth / (sample * 2) >= targetWidth) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap scaleToFit(Bitmap source, int maxWidth) {
        if (source.getWidth() <= maxWidth) {
            return source;
        }
        float ratio = (float) maxWidth / source.getWidth();
        Bitmap scaled = Bitmap.createScaledBitmap(source, maxWidth,
                Math.round(source.getHeight() * ratio), true);
        return scaled == null ? source : scaled;
    }

    private static Bitmap drawRuled(int paperColor, int lineColor) {
        Bitmap bitmap = blank();
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(paperColor);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(lineColor);
        paint.setStrokeWidth(dp(1.5f));
        int step = Math.round(dp(26));
        for (int y = step; y < bitmap.getHeight(); y += step) {
            canvas.drawLine(dp(18), y, bitmap.getWidth() - dp(18), y, paint);
        }
        return bitmap;
    }

    private static Bitmap drawGrid(int paperColor, int lineColor) {
        Bitmap bitmap = blank();
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(paperColor);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(lineColor);
        paint.setStrokeWidth(dp(1));
        int step = Math.round(dp(22));
        for (int x = step; x < bitmap.getWidth(); x += step) {
            canvas.drawLine(x, dp(10), x, bitmap.getHeight() - dp(10), paint);
        }
        for (int y = step; y < bitmap.getHeight(); y += step) {
            canvas.drawLine(dp(10), y, bitmap.getWidth() - dp(10), y, paint);
        }
        return bitmap;
    }

    private static Bitmap drawDots(int paperColor, int dotColor) {
        Bitmap bitmap = blank();
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(paperColor);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(dotColor);
        int step = Math.round(dp(24));
        float radius = dp(2.2f);
        for (int x = step; x < bitmap.getWidth(); x += step) {
            for (int y = step; y < bitmap.getHeight(); y += step) {
                canvas.drawCircle(x, y, radius, paint);
            }
        }
        return bitmap;
    }

    private static Bitmap drawSolid(int baseColor, int bandColor) {
        Bitmap bitmap = blank();
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(baseColor);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(bandColor);
        canvas.drawRoundRect(new RectF(dp(24), dp(140),
                bitmap.getWidth() - dp(24), bitmap.getHeight() - dp(64)), dp(10), dp(10), paint);
        return bitmap;
    }

    private static Bitmap blank() {
        Bitmap bitmap = Bitmap.createBitmap(PRESET_WIDTH, PRESET_HEIGHT,
                Bitmap.Config.ARGB_8888);
        return bitmap;
    }

    private static float dp(float value) {
        return value * android.content.res.Resources.getSystem().getDisplayMetrics().density;
    }
}
