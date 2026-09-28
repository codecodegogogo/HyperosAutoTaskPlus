package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/**
 * 动作链结果的运行时入口。方法签名与 DeviceActionRuntime 保持一致，供 hook 侧反射调用：
 *   itemClass / newItem / isItem   工厂与类型判断
 *   pickAndApply                   打开动作库弹窗（编辑页复用）
 *   play                           引擎触发时回放（remote 进程）
 *   title / summary / icon         列表展示
 *
 * 这个类被注入到安全服务的 ClassLoader，参数统一用 Context / Object / Runnable。
 */
public final class ActionChainRuntime {

    private ActionChainRuntime() {}

    public static Class<?> itemClass(String key) {
        return FirstAppKeys.isActionChainKey(key) ? ActionChainResultItem.class : null;
    }

    public static Object newItem(String key) {
        return FirstAppKeys.isActionChainKey(key) ? new ActionChainResultItem(key) : null;
    }

    public static boolean isItem(Object item) {
        return item instanceof ActionChainResultItem;
    }

    /** 打开动作库弹窗；选定并确定后写回 item 并回调 confirm。 */
    public static void pickAndApply(Context context, Object value, Runnable confirm) {
        if (context == null || !(value instanceof ActionChainResultItem)) return;
        ActionChainEditor.pickChain(context, (ActionChainResultItem) value, confirm);
    }

    /** 引擎触发：把这条链交给回放队列。 */
    public static void play(Object value) {
        if (!(value instanceof ActionChainResultItem)) return;
        ActionChainResultItem item = (ActionChainResultItem) value;
        Context context = InjectUi.context();
        if (context == null) return;
        ActionChainPlayer.enqueue(context, item.getChainId(), item.j());
    }

    // ------------------------------------------------------------------ 展示

    public static String title() {
        return DeviceActionRuntime.text("动作链", "Action chain");
    }

    static String summary(ActionChainResultItem item) {
        Context context = InjectUi.context();
        String name = item.getChainName();
        ActionChain chain = context == null ? null : ActionChainStore.load(context, item.getChainId());
        if (item.getChainId().isEmpty()) {
            return title() + " · " + DeviceActionRuntime.text("选择动作链", "Choose an action chain");
        }
        if (chain == null) {
            return title() + " · " + (name.isEmpty()
                    ? DeviceActionRuntime.text("动作链缺失", "Action chain missing")
                    : name + DeviceActionRuntime.text("（缺失）", " (missing)"));
        }
        String display = chain.name().isEmpty() ? (name.isEmpty() ? title() : name) : chain.name();
        long sec = Math.max(1, chain.estimatedDurationMs() / 1000);
        return display + " · " + chain.totalStepCount() + DeviceActionRuntime.text(" 步 · 约 ", " steps · ~")
                + sec + DeviceActionRuntime.text(" 秒", "s");
    }

    static int icon(int which) {
        // 借原生「点按」三态图标（auto_task_icon_touch），缺失时用系统图标兜底
        return InjectUi.icons("auto_task_icon_touch", android.R.drawable.ic_menu_edit)
                [Math.max(0, Math.min(which, 2))];
    }
}
