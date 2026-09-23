package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.app.Notification;
import android.os.Bundle;

import java.lang.reflect.Field;

/** 高重要级别渠道之外，再声明标准优先级和 HyperOS/MIUI 的悬浮横幅提示；仍遵循用户的通知设置。 */
final class NotificationAttention {
    private NotificationAttention() {}

    static Notification buildSilent(Notification.Builder builder) {
        Notification notification = build(builder.setDefaults(0).setSound(null).setVibrate(null));
        try {
            Bundle extras = (Bundle) Notification.class.getField("extras").get(notification);
            if (extras != null) extras.putBoolean("miui.enableSound", false);
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
        try {
            Object extra = Notification.class.getField("extraNotification").get(notification);
            if (extra != null) extra.getClass().getMethod("setEnableSound", boolean.class).invoke(extra, false);
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
        return notification;
    }

    static Notification build(Notification.Builder builder) {
        Notification notification = builder.setPriority(Notification.PRIORITY_HIGH).build();
        try {
            Field extrasField = Notification.class.getField("extras");
            Bundle extras = (Bundle) extrasField.get(notification);
            if (extras == null) {
                extras = new Bundle();
                extrasField.set(notification, extras);
            }
            // 安全服务 BubbleUpManager 先读取此键，再兼容旧版 extraNotification。
            extras.putBoolean("miui.enableFloat", true);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 厂商扩展被隐藏 API / 安全策略拒绝时，也必须保留已构建好的标准通知。
        }
        try {
            Object extra = Notification.class.getField("extraNotification").get(notification);
            if (extra != null) extra.getClass().getMethod("setEnableFloat", boolean.class).invoke(extra, true);
        } catch (ReflectiveOperationException | RuntimeException ignored) { /* 不同系统版本可能不提供 MIUI 扩展 */ }
        return notification;
    }
}
