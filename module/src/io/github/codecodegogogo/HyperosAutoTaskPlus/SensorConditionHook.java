package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.content.Context;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 功能八：条件列表新增「传感器」大类，里面是「设备动作」（翻转 / 摇晃）和「光线」两个条件。
 *
 * 添加条件页的分类来自 xml/activity_new_condition.xml 里固定的四个 PreferenceCategory
 * （情境 / 设置项 / 事件 / 通信），M0.j() 返回的 map 按 key 往里填。所以这里做两件事：
 *   1. setPreferencesFromResource 之后往 PreferenceScreen 里插一个 key 为
 *      key_sensor_condition_category 的 PreferenceCategory，排在「通信」前面；
 *   2. M0.j() 的结果里加这一组的 key 列表。
 * 传感器监听与对话框在 inject/SensorRuntime。
 */
final class SensorConditionHook {

    private static final String TAG = MainHook.TAG;
    static final String RUNTIME = "SensorRuntime";

    private static final String CLASS_PREF_FRAGMENT = "androidx.preference.PreferenceFragmentCompat";
    private static final String CLASS_PREF_CATEGORY = "androidx.preference.PreferenceCategory";

    private SensorConditionHook() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        final Class<?> taskItem = XposedHelpers.findClass(ConditionHookSupport.CLASS_TASK_ITEM, cl);

        HookUtils.step("M0(sensor)", () -> {
            ConditionHookSupport.hookFactory(cl, RUNTIME, FirstAppKeys::isSensorKey);
            ConditionHookSupport.hookCategory(cl, RUNTIME, FirstAppKeys.CATEGORY_SENSOR, null, false,
                    FirstAppKeys.KEY_DEVICE_MOTION_CONDITION, FirstAppKeys.KEY_LIGHT_CONDITION);
            ConditionHookSupport.hookOpposite(cl, taskItem, RUNTIME);
        });
        HookUtils.step("category(sensor)", () -> hookCategoryScreen(cl));
        HookUtils.step("dialogs(sensor)", () -> ConditionHookSupport.hookDialogs(cl, taskItem, RUNTIME));
        HookUtils.step("engine(sensor)", () -> ConditionHookSupport.hookEngine(cl, taskItem, RUNTIME));
    }

    /** 在 AddConditionFragment 加载完 xml 之后插入「传感器」分类 */
    private static void hookCategoryScreen(ClassLoader cl) {
        final Class<?> fragment = XposedHelpers.findClass(ConditionHookSupport.CLASS_ADD_CONDITION_FRAGMENT, cl);
        final Class<?> prefFragment = XposedHelpers.findClass(CLASS_PREF_FRAGMENT, cl);
        final Class<?> categoryCls = XposedHelpers.findClass(CLASS_PREF_CATEGORY, cl);

        XposedHelpers.findAndHookMethod(prefFragment, "setPreferencesFromResource", int.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!fragment.isInstance(param.thisObject) || ConditionHookSupport.runtime(RUNTIME) == null) {
                            return;
                        }
                        try {
                            addSensorCategory(param.thisObject, categoryCls);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": 插入「传感器」分类失败");
                            XposedBridge.log(t);
                        }
                    }
                });
    }

    private static void addSensorCategory(Object fragment, Class<?> categoryCls) {
        Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
        if (screen == null) {
            return;
        }
        if (XposedHelpers.callMethod(screen, "findPreference", FirstAppKeys.CATEGORY_SENSOR) != null) {
            return;
        }
        Object communication = XposedHelpers.callMethod(screen, "findPreference", FirstAppKeys.CATEGORY_COMMUNICATION);
        Context context = (Context) XposedHelpers.callMethod(screen, "getContext");
        Object category = XposedHelpers.newInstance(categoryCls, context);
        XposedHelpers.callMethod(category, "setKey", FirstAppKeys.CATEGORY_SENSOR);
        XposedHelpers.callMethod(category, "setTitle", title());

        // xml 里的分类按加入顺序拿到 0,1,2,3…，把「通信」及其后面的都往后挪一位，腾出位置
        if (communication != null) {
            int slot = (Integer) XposedHelpers.callMethod(communication, "getOrder");
            int count = (Integer) XposedHelpers.callMethod(screen, "getPreferenceCount");
            for (int i = 0; i < count; i++) {
                Object p = XposedHelpers.callMethod(screen, "getPreference", i);
                int order = (Integer) XposedHelpers.callMethod(p, "getOrder");
                if (order >= slot) {
                    XposedHelpers.callMethod(p, "setOrder", order + 1);
                }
            }
            XposedHelpers.callMethod(category, "setOrder", slot);
        }
        XposedHelpers.callMethod(screen, "addPreference", category);
    }

    private static String title() {
        return "zh".equals(java.util.Locale.getDefault().getLanguage()) ? "传感器" : "Sensors";
    }
}
