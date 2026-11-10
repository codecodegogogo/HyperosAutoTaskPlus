package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.content.Context;
import android.os.Handler;
import android.widget.Toast;

/**
 * 模块内所有 Toast 的统一出口。
 *
 * 开关位于「自动任务 → 右上角设置 → 增强功能」，关闭后模块产生的提示全部不显示。
 * 设置写在安全服务自己的私有偏好里，注入侧和 hook 侧读取同一份文件。
 */
public final class ToastGate {
    private static final String PREFERENCES = "hyper_auto_settings";
    private static final String KEY_SHOW_TOAST = "show_toast";

    private ToastGate() {}

    public static boolean enabled(Context context) {
        if (context == null) return true;
        try {
            Context app = context.getApplicationContext();
            return (app == null ? context : app)
                    .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .getBoolean(KEY_SHOW_TOAST, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) return;
        try {
            Context app = context.getApplicationContext();
            (app == null ? context : app)
                    .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_SHOW_TOAST, enabled).apply();
        } catch (Throwable ignored) {
            // 偏好写入失败时保持默认显示，不影响任务本身。
        }
    }

    public static void show(Context context, CharSequence message) {
        show(context, message, Toast.LENGTH_LONG);
    }

    public static void show(Context context, CharSequence message, int duration) {
        if (context == null || message == null || !enabled(context)) return;
        new Handler(context.getMainLooper())
                .post(() -> Toast.makeText(context, message, duration).show());
    }
}
