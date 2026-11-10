package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.content.Context;
import android.os.Bundle;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 在「自动任务 → 右上角设置」页面追加「增强功能」分类和「屏幕下方弹出提示」开关。
 *
 * 页面本身是原生 PreferenceFragment，这里在 onCreatePreferences 之后动态插入一个
 * CheckBoxPreference，不修改宿主的 XML 资源。开关状态由 {@link ToastGate} 统一读取。
 */
final class ToastSettingHook {
    private static final String TAG = MainHook.TAG;
    private static final String CLASS_SETTING =
            "com.miui.autotask.fragment.NewTaskSettingFragment";
    private static final String KEY_SHOW_TOAST = "hyper_auto_show_toast";
    private static final String KEY_CATEGORY = "hyper_auto_settings_category";

    private ToastSettingHook() {}

    static void install(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> fragment = XposedHelpers.findClass(CLASS_SETTING, lp.classLoader);
        XposedHelpers.findAndHookMethod(fragment, "onCreatePreferences", Bundle.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable()) return;
                        try {
                            addSwitch(param.thisObject, lp.classLoader);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": 添加提示开关失败");
                            XposedBridge.log(t);
                        }
                    }
                });
        hookToggle(lp.classLoader);
    }

    private static void addSwitch(Object fragment, ClassLoader loader) {
        Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
        if (screen == null) return;
        if (XposedHelpers.callMethod(screen, "findPreference", KEY_SHOW_TOAST) != null) return;
        Context context = (Context) XposedHelpers.callMethod(fragment, "getActivity");
        if (context == null) return;

        Object category = XposedHelpers.newInstance(
                XposedHelpers.findClass("androidx.preference.PreferenceCategory", loader), context);
        XposedHelpers.callMethod(category, "setKey", KEY_CATEGORY);
        XposedHelpers.callMethod(category, "setTitle", "增强功能");

        Object checkbox = XposedHelpers.newInstance(
                XposedHelpers.findClass("androidx.preference.CheckBoxPreference", loader), context);
        XposedHelpers.callMethod(checkbox, "setKey", KEY_SHOW_TOAST);
        XposedHelpers.callMethod(checkbox, "setTitle", "屏幕下方弹出提示");
        XposedHelpers.callMethod(checkbox, "setSummary", "关闭后不显示模块产生的 Toast 提示");
        XposedHelpers.callMethod(checkbox, "setPersistent", false);
        XposedHelpers.callMethod(checkbox, "setChecked", ToastGate.enabled(context));

        // 分类先加入屏幕，再加入子项，Preference 才能拿到 PreferenceManager。
        XposedHelpers.callMethod(screen, "addPreference", category);
        XposedHelpers.callMethod(category, "addPreference", checkbox);
    }

    /**
     * 宿主的 androidx.preference 被混淆，OnPreferenceChangeListener 实际叫 Preference$c，
     * 不能按原类名动态代理。改为钩 Preference.performClick()：点击后读取复选框的新状态。
     */
    private static void hookToggle(ClassLoader loader) {
        try {
            Class<?> preference = XposedHelpers.findClass("androidx.preference.Preference", loader);
            Method click = preference.getDeclaredMethod("performClick");
            XposedBridge.hookMethod(click, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object key = XposedHelpers.callMethod(param.thisObject, "getKey");
                        if (!KEY_SHOW_TOAST.equals(key)) return;
                        Context context = (Context) XposedHelpers.callMethod(param.thisObject, "getContext");
                        if (context == null) return;
                        Object checked = XposedHelpers.callMethod(param.thisObject, "isChecked");
                        ToastGate.setEnabled(context, Boolean.TRUE.equals(checked));
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": 保存提示开关失败");
                        XposedBridge.log(t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 安装提示开关点击监听失败");
            XposedBridge.log(t);
        }
    }
}
