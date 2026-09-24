package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.content.Context;
import android.graphics.drawable.Drawable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 沿用 InvisibleModeResultHook 的结果接入路径：M0.h/B/A、添加页 u2、编辑页 G0。
 * 运行时通过 DexInjector 按简名加载；引擎仍直接调用 TaskItem.n()/o()。
 * 设备操作追加到原生「功能」分类，不修改原生结果的执行逻辑。
 */
final class DeviceActionResultHook {
    private static final String RUNTIME = "DeviceActionRuntime";
    private static final String[] KEYS = {
            FirstAppKeys.KEY_AUDIO_RECORD_RESULT, FirstAppKeys.KEY_SCREEN_RECORD_RESULT,
            FirstAppKeys.KEY_SCREENSHOT_RESULT, FirstAppKeys.KEY_PHOTO_RESULT,
            FirstAppKeys.KEY_CALL_RESULT, FirstAppKeys.KEY_SMS_RESULT, FirstAppKeys.KEY_EMAIL_RESULT,
            FirstAppKeys.KEY_PLAY_AUDIO_RESULT, FirstAppKeys.KEY_NOTIFICATION_RESULT};

    private DeviceActionResultHook() {}

    static void install(XC_LoadPackage.LoadPackageParam lp) {
        ClassLoader cl = lp.classLoader;
        Class<?> item = XposedHelpers.findClass(ConditionHookSupport.CLASS_TASK_ITEM, cl);
        HookUtils.step("M0(device results)", () -> hookFactory(cl));
        HookUtils.step("AddResult(device results)", () -> hookAdd(cl, item));
        HookUtils.step("G0(device results)", () -> hookEdit(cl, item));
    }

    private static Class<?> runtime() { return DexInjector.runtime(RUNTIME); }

    private static boolean isItem(Object item) {
        Class<?> rt = runtime();
        return rt != null && item != null
                && (Boolean) XposedHelpers.callStaticMethod(rt, "isItem", item);
    }

    private static void hookFactory(ClassLoader cl) {
        Class<?> m0 = TargetResolver.factory(cl);
        // 和新增条件共用 key -> Class / 实例的工厂拦截。
        ConditionHookSupport.hookFactory(cl, RUNTIME, FirstAppKeys::isDeviceActionKey);
        XposedHelpers.findAndHookMethod(m0, "A", Context.class, new XC_MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam param) {
                if (runtime() == null || !(param.getResult() instanceof Map)) return;
                Map<Object, Object> categories = (Map<Object, Object>) param.getResult();
                Object original = categories.get(FirstAppKeys.CATEGORY_RESULT_FUNCTION);
                if (!(original instanceof List)) return;
                List<String> keys = new ArrayList<>((List<String>) original);
                for (String key : KEYS) if (!keys.contains(key)) keys.add(key);
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
        Method build = HookUtils.findMethod(fragment, new String[]{"v1", "s1"}, void.class);
        if (build != null) XposedBridge.hookMethod(build, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                dimDisabledIcons(param.thisObject);
            }
        });
    }

    private static void hookEdit(ClassLoader cl, Class<?> item) {
        Class<?> k0 = TargetResolver.editor(cl);
        // 使用现有的签名定位，避免把形状相似的条件编辑 F0 当成结果编辑。
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

    private static void dimDisabledIcons(Object fragment) {
        try {
            Class<?> rt = runtime();
            if (rt == null) return;
            Object category = XposedHelpers.callMethod(fragment, "findPreference", FirstAppKeys.CATEGORY_RESULT_FUNCTION);
            if (category == null) return;
            int count = (Integer) XposedHelpers.callMethod(category, "getPreferenceCount");
            for (int i = 0; i < count; i++) {
                Object pref = XposedHelpers.callMethod(category, "getPreference", i);
                if ((Boolean) XposedHelpers.callMethod(pref, "isEnabled")) continue;
                String title = String.valueOf(XposedHelpers.callMethod(pref, "getTitle"));
                for (String key : KEYS) {
                    if (!title.equals(XposedHelpers.callStaticMethod(rt, "title", key))) continue;
                    Drawable icon = (Drawable) XposedHelpers.callMethod(pref, "getIcon");
                    if (icon != null) {
                        icon = icon.mutate();
                        icon.setAlpha(77);
                        XposedHelpers.callMethod(pref, "setIcon", icon);
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": 设备操作禁用态图标处理失败: " + t);
        }
    }
}
