package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;

/** 私有彩信临时文件桥接。只有系统安全服务能创建/写入，系统彩信服务凭单文件 URI 授权读取。 */
public final class MmsAttachmentProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.codecodegogogo.HyperosAutoTaskPlus.mms";
    private static final String HOST = "com.miui.securitycenter";
    private static final String PHONE = "com.android.phone";
    private static final long RETENTION_MS = 24 * 60 * 60 * 1000L;
    private static final int FILE_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    @Override public boolean onCreate() { return true; }

    private void requireHost() {
        try {
            ApplicationInfo host = getContext().getPackageManager().getApplicationInfo(HOST, 0);
            if (Binder.getCallingUid() == host.uid && (host.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return;
        } catch (PackageManager.NameNotFoundException ignored) {}
        throw new SecurityException("仅允许系统安全服务管理彩信附件");
    }

    private File directory() {
        return new File(getContext().getCacheDir(), "outgoing_mms");
    }

    public static boolean validName(String name) {
        return name != null && name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdu");
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (!AUTHORITY.equals(uri.getAuthority()) || uri.getPathSegments().size() != 1
                || !validName(uri.getLastPathSegment())) throw new FileNotFoundException("无效的彩信附件");
        return new File(directory(), uri.getLastPathSegment());
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        requireHost();
        if (!"create".equals(method)) throw new IllegalArgumentException("未知附件操作");
        try {
            ApplicationInfo phone = getContext().getPackageManager().getApplicationInfo(PHONE, 0);
            if ((phone.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                throw new IllegalStateException("未找到系统彩信服务");
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("未找到系统彩信服务", e);
        }
        File dir = directory();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("无法创建彩信缓存目录");
        // 宿主/模块进程中途退出的残留文件，下次发送时过期清理；不扫描其它缓存目录。
        File[] old = dir.listFiles();
        if (old != null) for (File file : old) {
            if (validName(file.getName()) && System.currentTimeMillis() - file.lastModified() > RETENTION_MS) {
                if (file.delete()) revoke(Uri.parse("content://" + AUTHORITY + "/" + file.getName()));
            }
        }
        String name = UUID.randomUUID() + ".pdu";
        File file = new File(dir, name);
        try {
            if (!file.createNewFile()) throw new IOException("附件重名");
        } catch (IOException e) { throw new IllegalStateException("无法创建彩信附件", e); }
        Uri uri = Uri.parse("content://" + AUTHORITY + "/" + name);
        long token = Binder.clearCallingIdentity();
        try {
            // Manifest 的签名权限禁止其它应用自行转授 URI。宿主仅获得这一份文件的读写
            // 权限。安全中心使用 system UID，Android 禁止该 UID 直接转授 URI，因此必须
            // 由模块自己的普通 UID 给 MMS Broker 使用的系统电话包及唯一运营商服务读授权。
            getContext().grantUriPermission(HOST, uri, FILE_GRANTS);
            getContext().grantUriPermission(PHONE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String carrier = extras == null ? null : extras.getString("carrierPackage");
            if (carrier != null && !carrier.isEmpty()) {
                getContext().grantUriPermission(carrier, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (RuntimeException error) {
            getContext().revokeUriPermission(uri, FILE_GRANTS);
            file.delete();
            throw error;
        } finally { Binder.restoreCallingIdentity(token); }
        Bundle result = new Bundle();
        result.putString("uri", uri.toString());
        return result;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        if ("r".equals(mode)) {
            if (getContext().checkCallingOrSelfUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    != PackageManager.PERMISSION_GRANTED) requireHost();
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        requireHost();
        if (!"w".equals(mode) && !"wt".equals(mode)) throw new FileNotFoundException("不支持的附件访问模式");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE);
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        requireHost();
        try {
            boolean deleted = resolve(uri).delete();
            revoke(uri);
            return deleted ? 1 : 0;
        } catch (FileNotFoundException e) { return 0; }
    }

    private void revoke(Uri uri) {
        long token = Binder.clearCallingIdentity();
        try { getContext().revokeUriPermission(uri, FILE_GRANTS); }
        finally { Binder.restoreCallingIdentity(token); }
    }

    @Override public String getType(Uri uri) { return "application/vnd.wap.mms-message"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        throw new UnsupportedOperationException("不支持枚举彩信附件");
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
