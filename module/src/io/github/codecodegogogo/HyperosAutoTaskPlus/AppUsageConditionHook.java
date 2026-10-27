package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.app.Activity;
import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 新增「应用使用时长」条件。
 *
 * 添加流程与原生应用条件一致：先设置统计范围和使用时长，再进入原生选应用页。
 * 条件项在任务编辑页再次点按时只修改两个时长，重新选择应用可删除后重加。
 * 运行时见 inject/AppUsageRuntime。
 */
final class AppUsageConditionHook {

    private static final String TAG = MainHook.TAG;
    private static final String RUNTIME = "AppUsageRuntime";
    private static final String CLASS_SELECT_APP_ACTIVITY = "com.miui.autotask.activity.SelectAppActivity";
    private static final String CLASS_LUNCH_APP_ITEM = "com.miui.autotask.taskitem.LunchAppItem";
    private static final int REQUEST_ADD_CONDITION = 102;

    private AppUsageConditionHook() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        final Class<?> taskItem = XposedHelpers.findClass(ConditionHookSupport.CLASS_TASK_ITEM, cl);

        HookUtils.step("M0(app usage)", () -> {
            ConditionHookSupport.hookFactory(cl, RUNTIME, FirstAppKeys::isAppUsageKey);
            ConditionHookSupport.hookCategory(cl, RUNTIME, FirstAppKeys.CATEGORY_EVENT,
                    FirstAppKeys.KEY_LEAVE_ACTIVITY_CONDITION, false,
                    FirstAppKeys.KEY_APP_USAGE_CONDITION);
            ConditionHookSupport.hookOpposite(cl, taskItem, RUNTIME);
        });
        HookUtils.step("add dialog(app usage)", () -> hookAddCondition(cl, taskItem));
        HookUtils.step("select app(app usage)", () -> hookSelectAppActivity(cl));
        HookUtils.step("edit dialog(app usage)", () -> hookEditDialog(cl, taskItem));
        HookUtils.step("engine(app usage)", () -> ConditionHookSupport.hookEngine(cl, taskItem, RUNTIME));
        HookUtils.step("foreground(app usage)", () -> hookForeground(cl));
    }

    /**
     * AddConditionFragment.q1(item)：先弹统计范围 / 使用时长卡片，确定后打开原生选应用页。
     * 选应用页返回的 requestCode 与原生条件一致，AddBaseFragment 会直接把 item 回传给任务页。
     */
    private static void hookAddCondition(ClassLoader cl, Class<?> taskItem) {
        Class<?> fragment = XposedHelpers.findClass(ConditionHookSupport.CLASS_ADD_CONDITION_FRAGMENT, cl);
        Class<?> selectApp = XposedHelpers.findClass(CLASS_SELECT_APP_ACTIVITY, cl);
        Class<?> lunchAppItem = XposedHelpers.findClass(CLASS_LUNCH_APP_ITEM, cl);
        Method onConditionClick = HookUtils.findMethod(fragment, new String[]{"q1", "n1"},
                void.class, taskItem);
        Method openSelectApp = HookUtils.findMethod(selectApp, new String[]{"d1", "e1", "S0"},
                void.class, Activity.class, lunchAppItem, int.class);
        if (onConditionClick == null || openSelectApp == null) {
            XposedBridge.log(TAG + ": 应用使用时长无法从添加条件页进入");
            return;
        }

        XposedBridge.hookMethod(onConditionClick, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                final Object item = param.args[0];
                if (!ConditionHookSupport.isItem(RUNTIME, item)) {
                    return;
                }
                param.setResult(null);
                Object activityObj = XposedHelpers.callMethod(param.thisObject, "getActivity");
                if (!(activityObj instanceof Activity)) {
                    return;
                }
                final Activity activity = (Activity) activityObj;
                Runnable onConfirm = () -> {
                    try {
                        openSelectApp.invoke(null, activity, item, REQUEST_ADD_CONDITION);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": 打开应用使用时长选应用页失败");
                        XposedBridge.log(t);
                    }
                };
                XposedHelpers.callStaticMethod(ConditionHookSupport.runtime(RUNTIME),
                        "pickAndApply", activity, item, onConfirm);
            }
        });
    }

    /** 原生选应用页接受 LunchAppItem；本条件继承它，因此只需补标题和多选开关。 */
    private static void hookSelectAppActivity(ClassLoader cl) {
        Class<?> selectApp = XposedHelpers.findClass(CLASS_SELECT_APP_ACTIVITY, cl);
        Class<?> lunchAppItem = XposedHelpers.findClass(CLASS_LUNCH_APP_ITEM, cl);
        Method title = HookUtils.findMethod(selectApp, new String[]{"K0", "L0", "y0"}, String.class);
        final Field itemField = HookUtils.findField(selectApp, "h", lunchAppItem);
        final Field multiSelectField = HookUtils.findField(selectApp, "j", boolean.class);
        if (title == null || itemField == null || multiSelectField == null) {
            XposedBridge.log(TAG + ": 应用使用时长选应用页标题/多选不可用");
            return;
        }

        XposedBridge.hookMethod(title, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object item = itemField.get(param.thisObject);
                if (!ConditionHookSupport.isItem(RUNTIME, item)) {
                    return;
                }
                multiSelectField.setBoolean(param.thisObject, true);
                param.setResult(XposedHelpers.callMethod(item, "h"));
            }
        });
    }

    /** 任务编辑页点已有条件：修改统计范围和使用时长。 */
    private static void hookEditDialog(ClassLoader cl, Class<?> taskItem) {
        Class<?> editor = TargetResolver.editor(cl);
        Method editClick = ConditionHookSupport.findEditMethod(editor, taskItem);
        if (editClick == null) {
            XposedBridge.log(TAG + ": 应用使用时长在任务编辑页里不能再次修改");
            return;
        }

        XposedBridge.hookMethod(editClick, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object item = param.args[1];
                if (!ConditionHookSupport.isItem(RUNTIME, item)) {
                    return;
                }
                param.setResult(null);
                Context context = (Context) param.args[0];
                final Object callback = param.args[2];
                final int position = (Integer) param.args[3];
                if (context == null || callback == null) {
                    return;
                }
                Runnable onConfirm = () -> XposedHelpers.callMethod(callback, "a", position);
                XposedHelpers.callStaticMethod(ConditionHookSupport.runtime(RUNTIME),
                        "pickAndApply", context, item, onConfirm);
            }
        });
    }

    /**
     * 自动任务引擎本来就会收到 ForegroundInfo。找出所有单参数为 ForegroundInfo 的方法，
     * 把同一个回调转给运行时；重复回调由运行时按包名去重。
     */
    private static void hookForeground(ClassLoader cl) {
        Class<?> engine = TargetResolver.engine(cl);
        Class<?> foregroundInfo;
        try {
            foregroundInfo = Class.forName("miui.process.ForegroundInfo", false, cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 找不到 miui.process.ForegroundInfo，应用使用时长不会计时");
            return;
        }

        int count = 0;
        for (Method method : engine.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0] != foregroundInfo) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Class<?> runtime = ConditionHookSupport.runtime(RUNTIME);
                    if (runtime == null || param.args[0] == null) {
                        return;
                    }
                    XposedHelpers.callStaticMethod(runtime, "onForegroundInfo", param.args[0]);
                }
            });
            count++;
        }
        if (count == 0) {
            XposedBridge.log(TAG + ": 自动任务引擎没有 ForegroundInfo 回调，应用使用时长不会计时");
        }
    }
}
