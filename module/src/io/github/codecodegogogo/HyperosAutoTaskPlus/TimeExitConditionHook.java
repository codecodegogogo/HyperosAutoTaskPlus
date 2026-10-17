package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Exposes the native implicit end-of-period exit without changing start conditions. */
final class TimeExitConditionHook {
    static final String RUNTIME = "TimeExitRuntime";

    private TimeExitConditionHook() {}

    static void install(XC_LoadPackage.LoadPackageParam lp) {
        ClassLoader cl = lp.classLoader;
        // Resolve the engine contract before exposing controls that depend on it.
        TimeExitEngineHook.install(cl);
        ConditionHookSupport.hookFactory(cl, RUNTIME, key ->
                FirstAppKeys.KEY_CUSTOM_TIME_CONDITION.equals(key) || FirstAppKeys.KEY_TIME_PERIOD_EXIT.equals(key));
        XposedHelpers.findAndHookMethod(TargetResolver.factory(cl), "s", String.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (FirstAppKeys.KEY_CUSTOM_TIME_CONDITION.equals(param.args[0])) {
                    param.setResult(FirstAppKeys.KEY_TIME_PERIOD_EXIT);
                }
            }
        });
        Class<?> fragment = XposedHelpers.findClass("com.miui.autotask.fragment.NewTaskFragment", cl);
        XposedHelpers.findAndHookMethod(fragment, "onCreatePreferences", Bundle.class, String.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!param.hasThrowable()) reconcile(param.thisObject);
                    }
                });
        XposedHelpers.findAndHookMethod(fragment, "onActivityResult", int.class, int.class, Intent.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!param.hasThrowable() && (Integer) param.args[1] == Activity.RESULT_OK) {
                            reconcile(param.thisObject);
                        }
                    }
                });
    }

    private static void reconcile(Object fragment) {
        Class<?> runtime = DexInjector.runtime(RUNTIME);
        if (runtime == null) return;
        try {
            Object conditions = XposedHelpers.getObjectField(fragment, "a");
            Object exits = XposedHelpers.getObjectField(fragment, "c");
            Object checkbox = XposedHelpers.getObjectField(fragment, "p");
            if (conditions == null || exits == null || checkbox == null) return;
            List<?> exitItems = (List<?>) XposedHelpers.getObjectField(exits, "b");
            int changed = (Integer) XposedHelpers.callStaticMethod(runtime, "reconcile",
                    XposedHelpers.getObjectField(conditions, "b"), exitItems,
                    XposedHelpers.callMethod(checkbox, "isChecked"));
            if (changed == 0) return;
            if ((changed & 2) != 0) {
                XposedHelpers.callMethod(checkbox, "setChecked", true);
                XposedHelpers.callMethod(exits, "setVisible", true);
            }
            for (Object item : exitItems) {
                if ("key_condition_list".equals(XposedHelpers.callMethod(item, "e"))) {
                    XposedHelpers.callMethod(item, "u", exitItems.size() == 1);
                }
            }
            XposedHelpers.callMethod(XposedHelpers.getObjectField(exits, "c"), "notifyDataSetChanged");
            XposedHelpers.callMethod(exits, "D");
        } catch (Throwable error) {
            XposedBridge.log(MainHook.TAG + ": time exit editor failed");
            XposedBridge.log(error);
            Context context = (Context) XposedHelpers.callMethod(fragment, "getActivity");
            if (context != null) Toast.makeText(context, "时间退出条件加载失败，请重新打开任务", Toast.LENGTH_LONG).show();
        }
    }
}
