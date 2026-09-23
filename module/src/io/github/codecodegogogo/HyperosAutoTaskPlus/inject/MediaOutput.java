package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** API 29+ 的 MediaStore 事务：完成前 is_pending=1，成功发布，失败删除半成品。 */
final class MediaOutput {
    private final Context context;
    private final Uri uri;
    private final String name;
    private final String mimeType;
    private final String relativePath;
    private ParcelFileDescriptor descriptor;
    private boolean finished;

    private MediaOutput(Context context, Uri uri, String name, String mimeType, String relativePath) {
        this.context = context;
        this.uri = uri;
        this.name = name;
        this.mimeType = mimeType;
        this.relativePath = relativePath;
    }

    /** 文件发布后传给通知的快照，不持有录制句柄。 */
    static final class SavedFile {
        final Uri uri;
        final String name;
        final String mimeType;
        final String relativePath;

        SavedFile(Uri uri, String name, String mimeType, String relativePath) {
            this.uri = uri;
            this.name = name;
            this.mimeType = mimeType;
            this.relativePath = relativePath;
        }
    }

    static MediaOutput create(Context context, String type) throws IOException {
        boolean audio = "audio".equals(type);
        boolean screenshot = "screenshots".equals(type);
        boolean image = "images".equals(type) || screenshot;
        String extension = screenshot ? ".png" : audio ? ".m4a" : image ? ".jpg" : ".mp4";
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        String name = (screenshot ? "Screenshot_HyperAuto_" : "HyperAuto_") + stamp + "_" + UUID.randomUUID().toString().substring(0, 8) + extension;
        String mimeType = screenshot ? "image/png" : audio ? "audio/mp4" : image ? "image/jpeg" : "video/mp4";
        String relativePath = screenshot ? "Pictures/Screenshots" : audio ? "Music/HyperosAutoTaskPlus" : "DCIM/HyperAutoTask";
        ContentValues values = new ContentValues();
        values.put("_display_name", name);
        values.put("mime_type", mimeType);
        values.put("relative_path", relativePath);
        values.put("is_pending", 1);
        Uri uri = context.getContentResolver().insert(Uri.parse("content://media/external/" + (image ? "images" : type) + "/media"), values);
        if (uri == null) throw new IOException("无法创建媒体文件");
        return new MediaOutput(context, uri, name, mimeType, relativePath);
    }

    java.io.FileDescriptor descriptor() throws IOException {
        descriptor = context.getContentResolver().openFileDescriptor(uri, "w");
        if (descriptor == null) throw new IOException("无法打开媒体文件");
        return descriptor.getFileDescriptor();
    }

    void write(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("照片内容为空");
        try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IOException("无法写入照片");
            output.write(bytes);
        }
    }

    void writePng(Bitmap bitmap) throws IOException {
        try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
            if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("无法保存截图");
            }
        }
    }

    SavedFile commit() throws IOException {
        if (finished) throw new IOException("媒体文件事务已结束");
        closeDescriptor();
        ContentValues values = new ContentValues();
        values.put("is_pending", 0);
        if (context.getContentResolver().update(uri, values, null, null) < 1) throw new IOException("媒体文件发布失败");
        finished = true;
        return new SavedFile(uri, name, mimeType, relativePath);
    }

    void abort() {
        try { closeDescriptor(); } catch (IOException e) { Log.w(DeviceActionRuntime.TAG, "close media output failed", e); }
        if (finished) return;
        try { context.getContentResolver().delete(uri, null, null); }
        catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "discard media output failed", t); }
        finished = true;
    }

    private void closeDescriptor() throws IOException {
        if (descriptor != null) {
            ParcelFileDescriptor old = descriptor;
            descriptor = null;
            old.close();
        }
    }
}
