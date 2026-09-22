package io.github.codecodegogogo.HyperosAutoTaskPlus;

import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 功能七：新增「时间间隔」条件（每隔 N 秒/分钟/小时/天触发一次），放在「情境」分类的「自定义时间」之后。
 * 计时与对话框在 inject/IntervalRuntime。不能作为退出条件：M0.t 返回 null，
 * 添加退出条件页里由 ExitConditionHook 置灰。
 */
final class IntervalConditionHook {

    static final String RUNTIME = "IntervalRuntime";

    private IntervalConditionHook() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        final Class<?> taskItem = XposedHelpers.findClass(ConditionHookSupport.CLASS_TASK_ITEM, cl);

        HookUtils.step("M0(interval)", () -> {
            ConditionHookSupport.hookFactory(cl, RUNTIME, FirstAppKeys::isIntervalKey);
            ConditionHookSupport.hookCategory(cl, RUNTIME, FirstAppKeys.CATEGORY_SITUATION,
                    FirstAppKeys.KEY_CUSTOM_TIME_CONDITION, false, FirstAppKeys.KEY_INTERVAL_CONDITION);
            ConditionHookSupport.hookOpposite(cl, taskItem, RUNTIME);
        });
        HookUtils.step("dialogs(interval)", () -> ConditionHookSupport.hookDialogs(cl, taskItem, RUNTIME));
        HookUtils.step("engine(interval)", () -> ConditionHookSupport.hookEngine(cl, taskItem, RUNTIME));
    }
}
