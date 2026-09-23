package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 已保存媒体的系统通知。使用 Activity PendingIntent，宿主进程重启后按钮仍可打开原文件。 */
final class MediaFileNotification {
    // 渠道声音创建后不能再由应用修改，用固定新渠道迁移旧版有声的保存通知。
    private static final String CHANNEL = "hyper_auto_saved_files_silent_high_v2";
    private static final int NOTIFICATION_ID = 0x48414600;

    private MediaFileNotification() {}

    static void show(Context context, String title, MediaOutput.SavedFile file) {
        // 通知失败不能倒退媒体保存事务，更不能删除已保存的文件。
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) throw new IllegalStateException("系统通知服务不可用");
            if (!(Boolean) NotificationManager.class.getMethod("areNotificationsEnabled").invoke(manager)) {
                blocked(context);
                return;
            }
            Class<?> channelType = Class.forName("android.app.NotificationChannel");
            Object channel = channelType.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CHANNEL, DeviceActionRuntime.text("自动任务保存的文件", "Auto task saved files"), 4); // IMPORTANCE_HIGH
            channelType.getMethod("setSound", android.net.Uri.class, Class.forName("android.media.AudioAttributes"))
                    .invoke(channel, null, null);
            channelType.getMethod("enableVibration", boolean.class).invoke(channel, false);
            NotificationManager.class.getMethod("createNotificationChannel", channelType).invoke(manager, channel);
            Object current = NotificationManager.class.getMethod("getNotificationChannel", String.class).invoke(manager, CHANNEL);
            if (current != null && (Integer) channelType.getMethod("getImportance").invoke(current) == 0) {
                blocked(context);
                return;
            }

            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mimeType)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            view.setClipData(ClipData.newRawUri(file.name, file.uri));
            // data + MIME 参与 PendingIntent 的身份比较：每条通知始终打开自己的文件。
            // 直接启动查看器，不经广播接收器跳转，以兼容 Android 12+ 的通知启动限制。
            PendingIntent open = PendingIntent.getActivity(context, NOTIFICATION_ID, view,
                    PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000); // FLAG_IMMUTABLE
            Notification.Builder builder = Notification.Builder.class.getConstructor(Context.class, String.class)
                    .newInstance(context, CHANNEL);
            String details = file.name + "\n" + file.relativePath;
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(file.name)
                    .setStyle(new Notification.BigTextStyle().bigText(details))
                    .setContentIntent(open)
                    .addAction(android.R.drawable.ic_menu_view, DeviceActionRuntime.text("查看文件", "View file"), open)
                    .setAutoCancel(true)
                    .setOngoing(false);
            manager.notify("hyper_auto_saved_file:" + file.uri, NOTIFICATION_ID, NotificationAttention.buildSilent(builder));
        } catch (Throwable t) {
            Log.w(DeviceActionRuntime.TAG, "saved file notification unavailable", t);
            DeviceActionRuntime.notice(context, DeviceActionRuntime.text(
                    "文件已保存，但无法显示查看文件通知", "File saved, but its notification could not be shown"));
        }
    }

    private static void blocked(Context context) {
        Log.w(DeviceActionRuntime.TAG, "file saved, but saved file notifications are disabled");
        DeviceActionRuntime.notice(context, DeviceActionRuntime.text(
                "文件已保存，请在系统设置中开启安全服务的通知及“自动任务保存的文件”类别",
                "File saved. Enable Security notifications and the Auto task saved files category in system settings"));
    }
}
