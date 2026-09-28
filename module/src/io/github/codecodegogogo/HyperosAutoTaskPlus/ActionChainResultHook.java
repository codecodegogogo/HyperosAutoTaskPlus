package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 「动作链」结果的接入，沿用 DeviceActionResultHook 的路径：工厂 M0.h/B/A、
 * 添加页 u2、编辑页 G0。运行时由 DexInjector 按简名加载 ActionChainRuntime。
 * 追加到原生「功能」分类末尾，不改动原生结果。
 */
final class ActionChainResultHook {
    private static final String RUNTIME = "ActionChainRuntime";

    private ActionChainResultHook() {}

    static void install(XC_LoadPackage.LoadPackageParam lp) {
        ClassLoader cl = lp.classLoader;
        Class<?> item = XposedHelpers.findClass(ConditionHookSupport.CLASS_TASK_ITEM, cl);
        HookUtils.step("M0(action chain)", () -> hookFactory(cl));
        HookUtils.step("AddResult(action chain)", () -> hookAdd(cl, item));
        HookUtils.step("G0(action chain)", () -> hookEdit(cl, item));
    }

    private static Class<?> runtime() { return DexInjector.runtime(RUNTIME); }

    private static boolean isItem(Object item) {
        Class<?> rt = runtime();
        return rt != null && item != null && (Boolean) XposedHelpers.callStaticMethod(rt, "isItem", item);
    }

    private static void hookFactory(ClassLoader cl) {
        Class<?> m0 = TargetResolver.factory(cl);
        ConditionHookSupport.hookFactory(cl, RUNTIME, FirstAppKeys::isActionChainKey);
        XposedHelpers.findAndHookMethod(m0, "A", Context.class, new XC_MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam param) {
                if (runtime() == null || !(param.getResult() instanceof Map)) return;
                Map<Object, Object> categories = (Map<Object, Object>) param.getResult();
                Object original = categories.get(FirstAppKeys.CATEGORY_RESULT_FUNCTION);
                if (!(original instanceof List)) return;
                List<String> keys = new ArrayList<>((List<String>) original);
                if (!keys.contains(FirstAppKeys.KEY_ACTION_CHAIN_RESULT)) {
                    keys.add(FirstAppKeys.KEY_ACTION_CHAIN_RESULT);
                }
                categories.put(FirstAppKeys.CATEGORY_RESULT_FUNCTION, keys);
            }
        });
    }

    private static void hookAdd(ClassLoader cl, Class<?> item) {
        Class<?> fragment = XposedHelpers.findClass("com.miui.autotask.fragment.AddResultFragment", cl);
        Method click = HookUtils.findMethod(fragment, new String[]{"u2", "r2"}, void.class, item);
        Method applyResult = HookUtils.findMethod(fragment, new String[]{"w0", "t0"}, void.class, item);
        if (click == null || applyResult == null) {
            throw new IllegalStateException("AddResultFragment.u2/r2 或 w0/t0 未找到");
        }
        XposedBridge.hookMethod(click, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object value = param.args[0];
                if (!isItem(value)) return;
                param.setResult(null);
                Object self = param.thisObject;
                Object activity = XposedHelpers.callMethod(self, "getActivity");
                if (activity == null) return;
                Runnable confirm = () -> HookUtils.invokeMethod(applyResult, self, value);
                XposedHelpers.callStaticMethod(runtime(), "pickAndApply", activity, value, confirm);
            }
        });
    }

    private static void hookEdit(ClassLoader cl, Class<?> item) {
        Class<?> k0 = TargetResolver.editor(cl);
        Method edit = InvisibleModeResultHook.findEditMethod(k0, item);
        if (edit == null) throw new IllegalStateException("K0.G0 未找到");
        XposedBridge.hookMethod(edit, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object value = param.args[1];
                if (!isItem(value)) return;
                param.setResult(null);
                Object adapter = param.args[2];
                int position = (Integer) param.args[3];
                if (param.args[0] == null || adapter == null) return;
                Runnable confirm = () -> XposedHelpers.callMethod(adapter, "notifyItemChanged", position);
                XposedHelpers.callStaticMethod(runtime(), "pickAndApply", param.args[0], value, confirm);
            }
        });
    }
}
