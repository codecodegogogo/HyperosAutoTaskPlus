package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** 通过系统 MMS 服务发送，保留默认短信卡和运营商 APN，不修改默认短信应用。 */
final class MmsRuntime {
    private static final Uri STORE = Uri.parse("content://io.github.codecodegogogo.HyperosAutoTaskPlus.mms");
    private MmsRuntime() {}

    static void send(Context context, SmsManager manager, String number, String message, byte[] jpeg) throws Exception {
        if (message == null) message = "";
        if (jpeg == null || jpeg.length == 0) throw new IllegalArgumentException("环境照片为空，彩信未发送");
        Bundle config = (Bundle) SmsManager.class.getMethod("getCarrierConfigValues").invoke(manager);
        if (config != null && config.containsKey("enabledMMS") && !config.getBoolean("enabledMMS")) {
            throw new IllegalStateException("运营商未启用彩信");
        }
        int limit = bounded(config, "maxMessageSize", 300 * 1024, 2 * 1024 * 1024);
        int width = bounded(config, "maxImageWidth", 640, 1280);
        int height = bounded(config, "maxImageHeight", 480, 1280);
        int budget = limit - message.getBytes(StandardCharsets.UTF_8).length - 4096;
        if (budget < 4096) throw new IllegalArgumentException("彩信正文超过运营商大小限制，请缩短内容");
        byte[] photo = compress(context, jpeg, budget, width, height);
        byte[] pdu = MmsPdu.compose(number, message, photo, UUID.randomUUID().toString());
        if (pdu.length > limit) throw new IllegalArgumentException("彩信超过运营商大小限制");
        SentResult result = new SentResult(context);
        try {
            Bundle attachmentOptions = new Bundle();
            attachmentOptions.putString("carrierPackage", carrierPackage(context, manager));
            Bundle created = context.getContentResolver().call(STORE, "create", null, attachmentOptions);
            String reference = created == null ? null : created.getString("uri");
            if (reference == null) throw new IOException("无法创建彩信临时附件，请确认模块已更新");
            result.uri = Uri.parse(reference);
            try (OutputStream output = context.getContentResolver().openOutputStream(result.uri)) {
                if (output == null) throw new IOException("无法写入彩信临时附件");
                output.write(pdu);
            }
            result.register();
            SmsManager.class.getMethod("sendMultimediaMessage", Context.class, Uri.class, String.class,
                    Bundle.class, PendingIntent.class).invoke(manager, context, result.uri, null, null, result.intent);
            Log.i(DeviceActionRuntime.TAG, "MMS submitted");
        } catch (Exception e) {
            result.close();
            throw e;
        }
    }

    private static String carrierPackage(Context context, SmsManager manager) throws Exception {
        // 宿主具备系统电话权限，使用与 MmsServiceBroker 相同的 SIM 和服务查询条件。
        // 模块自身没有读取运营商权限，交由宿主查出唯一匹配包，再由 Provider 授权。
        int subscription = (Integer) SmsManager.class.getMethod("getSubscriptionId").invoke(manager);
        Object telephony = context.getSystemService(Context.TELEPHONY_SERVICE);
        Class<?> type = Class.forName("android.telephony.TelephonyManager");
        Object forSubscription = type.getMethod("createForSubscriptionId", int.class).invoke(telephony, subscription);
        List<?> packages = (List<?>) type.getMethod("getCarrierPackageNamesForIntent", Intent.class)
                .invoke(forSubscription, new Intent("android.service.carrier.CarrierMessagingService"));
        return packages != null && packages.size() == 1 ? (String) packages.get(0) : null;
    }

    private static int bounded(Bundle config, String key, int fallback, int ceiling) {
        int value = config == null ? fallback : config.getInt(key, fallback);
        return value > 0 ? Math.min(value, ceiling) : fallback;
    }

    private static byte[] compress(Context context, byte[] jpeg, int budget, int width, int height) throws IOException {
        Bitmap bitmap = null;
        File source = File.createTempFile("hyper_mms_", ".jpg", context.getCacheDir());
        try {
            try (FileOutputStream stream = new FileOutputStream(source)) { stream.write(jpeg); }
            int orientation = new ExifInterface(source.getAbsolutePath())
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("照片格式无效");
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
            if (bitmap == null) throw new IOException("无法解码环境照片");
            Matrix matrix = new Matrix();
            switch (orientation) {
                case 2: matrix.setScale(-1, 1); break;
                case 3: matrix.setRotate(180); break;
                case 4: matrix.setScale(1, -1); break;
                case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                case 6: matrix.setRotate(90); break;
                case 7: matrix.setRotate(-90); matrix.postScale(-1, 1); break;
                case 8: matrix.setRotate(-90); break;
                default: break;
            }
            if (!matrix.isIdentity()) {
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) bitmap.recycle();
                bitmap = rotated;
            }
            double scale = Math.min(1, Math.min((double) width / bitmap.getWidth(), (double) height / bitmap.getHeight()));
            if (scale < 1) {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, (int) (bitmap.getWidth() * scale)),
                        Math.max(1, (int) (bitmap.getHeight() * scale)), true);
                if (scaled != bitmap) bitmap.recycle();
                bitmap = scaled;
            }
            for (int resize = 0; resize < 6; resize++) {
                for (int quality : new int[]{85, 70, 55, 40}) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bytes)) throw new IOException("照片压缩失败");
                    if (bytes.size() <= budget) return bytes.toByteArray();
                }
                if (Math.min(bitmap.getWidth(), bitmap.getHeight()) <= 80) break;
                Bitmap smaller = Bitmap.createScaledBitmap(bitmap, Math.max(1, bitmap.getWidth() * 3 / 4),
                        Math.max(1, bitmap.getHeight() * 3 / 4), true);
                if (smaller != bitmap) bitmap.recycle();
                bitmap = smaller;
            }
            throw new IOException("照片无法压缩到运营商彩信大小限制内");
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (!source.delete()) Log.w(DeviceActionRuntime.TAG, "temporary photo cleanup failed");
        }
    }

    private static final class SentResult extends BroadcastReceiver {
        final Context context;
        final String action;
        final Runnable timeout;
        PendingIntent intent;
        Uri uri;
        boolean registered;
        boolean closed;

        SentResult(Context context) {
            this.context = context;
            action = context.getPackageName() + ".hyper_auto.MMS_SENT_" + UUID.randomUUID();
            timeout = () -> {
                if (closed) return;
                try {
                    DeviceActionRuntime.notice(context, "尚未收到彩信发送结果，请检查网络和彩信配置；请勿立即重复发送");
                } finally {
                    // 超时不能证明发送失败，系统可能仍在排队或重试。只释放回调，不删除
                    // 待读取的 PDU；进程退出/无回调的残留由 Provider 在 24 小时后清理。
                    close(false);
                }
            };
        }

        void register() throws Exception {
            ActionReceivers.register(context, this, action, DeviceActionRuntime.worker());
            registered = true;
            intent = PendingIntent.getBroadcast(context, 0, new Intent(action).setPackage(context.getPackageName()),
                    PendingIntent.FLAG_ONE_SHOT | 0x04000000);
            DeviceActionRuntime.worker().postDelayed(timeout, 10 * 60_000L);
        }

        @Override public void onReceive(Context c, Intent data) {
            if (closed || data == null || !action.equals(data.getAction())) return;
            int result = getResultCode();
            try {
                if (result == Activity.RESULT_OK) DeviceActionRuntime.notice(context, "彩信已发送");
                else {
                    Log.w(DeviceActionRuntime.TAG, "MMS send failed, code=" + result);
                    DeviceActionRuntime.notice(context, "彩信发送失败（错误码 " + result + "），请检查默认短信卡、移动数据及运营商彩信 APN");
                }
            } finally { close(); }
        }

        void close() { close(true); }

        void close(boolean removeAttachment) {
            if (closed) return;
            closed = true;
            DeviceActionRuntime.worker().removeCallbacks(timeout);
            if (registered) {
                try { context.unregisterReceiver(this); } catch (IllegalArgumentException ignored) {}
                registered = false;
            }
            if (intent != null) intent.cancel();
            if (removeAttachment && uri != null) {
                try { context.getContentResolver().delete(uri, null, null); }
                catch (Exception e) { Log.w(DeviceActionRuntime.TAG, "MMS attachment cleanup failed"); }
            }
        }
    }
}
