package io.github.codecodegogogo.HyperosAutoTaskPlus;

import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 条件侧完全沿用现有的工厂、卡片编辑和引擎注册协议。 */
final class UnlockFailureConditionHook {
    private static final String RUNTIME = "UnlockFailureRuntime";

    private UnlockFailureConditionHook() {}

    static void install(XC_LoadPackage.LoadPackageParam lp) {
        ClassLoader cl = lp.classLoader;
        Class<?> item = XposedHelpers.findClass(ConditionHookSupport.CLASS_TASK_ITEM, cl);
        HookUtils.step("M0(unlock failure)", () -> {
            ConditionHookSupport.hookFactory(cl, RUNTIME, FirstAppKeys::isUnlockFailureKey);
            ConditionHookSupport.hookCategory(cl, RUNTIME, FirstAppKeys.CATEGORY_EVENT,
                    FirstAppKeys.KEY_LOCK_SCREEN_CONDITION, false, FirstAppKeys.KEY_UNLOCK_FAILURE_CONDITION);
            ConditionHookSupport.hookOpposite(cl, item, RUNTIME);
        });
        HookUtils.step("dialogs(unlock failure)", () -> ConditionHookSupport.hookDialogs(cl, item, RUNTIME));
        HookUtils.step("engine(unlock failure)", () -> ConditionHookSupport.hookEngine(cl, item, RUNTIME));
    }
}
