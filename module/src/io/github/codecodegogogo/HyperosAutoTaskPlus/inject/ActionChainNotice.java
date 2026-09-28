package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

/**
 * 动作链回放期间的通知与提示。执行中显示持续通知（点按无停止按钮时按普通通知处理），
 * 结束 / 中止 / 排队用普通通知说明。声音等提醒沿用 NotificationAttention 的高优先级处理。
 *
 * 通知归属安全服务，使用独立渠道；关闭该渠道不影响其它录制 / 播放通知。
 */
final class ActionChainNotice {
    private ActionChainNotice() {}

    static void notify(Context context, String channelId, int notificationId,
                       String title, String text, boolean ongoing) {
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) { toast(context, text); return; }
            if (!(Boolean) NotificationManager.class.getMethod("areNotificationsEnabled").invoke(manager)) {
                toast(context, text);
                return;
            }
            Class<?> channelType = Class.forName("android.app.NotificationChannel");
            Object channel = channelType.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(channelId, DeviceActionRuntime.text("自动任务动作链", "Auto task action chain"), 4);
            NotificationManager.class.getMethod("createNotificationChannel", channelType).invoke(manager, channel);
            Object current = NotificationManager.class.getMethod("getNotificationChannel", String.class)
                    .invoke(manager, channelId);
            if (current != null && (Integer) channelType.getMethod("getImportance").invoke(current) == 0) {
                toast(context, text);
                return;
            }
            Notification.Builder builder = Notification.Builder.class.getConstructor(Context.class, String.class)
                    .newInstance(context, channelId);
            builder.setSmallIcon(android.R.drawable.ic_menu_send)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setWhen(System.currentTimeMillis())
                    .setOngoing(ongoing);
            manager.notify(notificationId, NotificationAttention.build(builder));
        } catch (Throwable t) {
            Log.w(DeviceActionRuntime.TAG, "action chain notify failed", t);
            toast(context, text);
        }
    }

    static void cancel(Context context, int notificationId) {
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(notificationId);
        } catch (Throwable ignored) {}
    }

    static void toast(Context context, String message) {
        try {
            new Handler(context.getMainLooper()).post(() ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show());
        } catch (Throwable ignored) {}
    }
}
