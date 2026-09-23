package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;

import java.util.UUID;

/** 使用系统通知模板；独立渠道保留用户的提醒设置，按任务更新且不占用录制/播放通知。 */
final class NotificationRuntime {
    // 旧渠道的重要级别无法由应用提高；使用固定的新 ID 完成一次升级，后续保留系统中的用户设置。
    private static final String CHANNEL = "hyper_auto_task_notifications_high_v1";
    private static final String TAG_PREFIX = "hyper_auto_task_notification:";
    private static final int NOTIFICATION_ID = 0x48414e00;

    private NotificationRuntime() {}

    static void send(Context context, String owner, String text) throws Exception {
        if (!DeviceActionResultItem.validNotificationText(text)) throw new IllegalArgumentException("通知内容为空");
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) throw new IllegalStateException("系统通知服务不可用");
        if (!(Boolean) NotificationManager.class.getMethod("areNotificationsEnabled").invoke(manager)) {
            blocked(context);
            return;
        }
        // 和现有录制/播放通知一样，通过反射调用较新 API，兼容仓库的编译工具链。
        Class<?> channelType = Class.forName("android.app.NotificationChannel");
        Object channel = channelType.getConstructor(String.class, CharSequence.class, int.class)
                .newInstance(CHANNEL, DeviceActionRuntime.text("自动任务通知", "Auto task notifications"), 4); // IMPORTANCE_HIGH
        NotificationManager.class.getMethod("createNotificationChannel", channelType).invoke(manager, channel);
        Object current = NotificationManager.class.getMethod("getNotificationChannel", String.class).invoke(manager, CHANNEL);
        if (current != null && (Integer) channelType.getMethod("getImportance").invoke(current) == 0) {
            blocked(context);
            return;
        }
        Notification.Builder builder = Notification.Builder.class.getConstructor(Context.class, String.class)
                .newInstance(context, CHANNEL);
        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(DeviceActionRuntime.text("自动任务", "Auto task"))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setWhen(System.currentTimeMillis())
                .setOngoing(false);
        String tag = TAG_PREFIX + (owner == null || owner.isEmpty() ? UUID.randomUUID().toString() : owner);
        manager.notify(tag, NOTIFICATION_ID, NotificationAttention.build(builder));
    }

    private static void blocked(Context context) {
        Log.w(DeviceActionRuntime.TAG, "task notifications disabled");
        DeviceActionRuntime.notice(context, DeviceActionRuntime.text(
                "无法发出通知，请在系统设置中开启安全服务的通知及“自动任务通知”类别",
                "Enable Security notifications and the Auto task notifications category in system settings"));
    }
}
