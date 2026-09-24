package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.app.Activity;
import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 功能二：新增「首次启动应用 / 首次离开应用」两个条件。
 *
 * 原生「启动应用 / 离开应用」看的是前台切换（ForegroundInfo）；新条件看的是进程
 * 是否在内存里：进程被创建时触发「首次启动」，所有进程被清出内存时触发「首次离开」。
 * 条件项本身（inject 包）会被注入到安全中心的 ClassLoader，这里只负责把它们
 * 接到安全中心的各个分发点上：
 *
 *   g2.M0.j()          条件列表（放在原「离开应用」之后）
 *   g2.M0.B(key)       key -> 新实例（条件列表、编辑页）
 *   g2.M0.h(key)       key -> Class（数据库 Gson 反序列化）
 *   g2.M0.s(key)       退出条件的反向 key（首次启动 <-> 首次离开）
 *   g2.M0.t(item)      由条件生成退出条件
 *   g2.M0.e(item,list) 条件编辑后同步退出条件的应用列表
 *   AddConditionFragment.q1(item)  点击条件 -> 打开选应用页
 *   SelectAppActivity.K0()         选应用页标题 + 允许多选
 *   b2.j.<init>        引擎就绪 -> 启动进程跟踪
 *   b2.j.p(item)       任务启用时注册条件项
 *   b2.j.b1(uuid,list) 任务停用时反注册
 */
final class FirstAppConditionHook {

    private static final String TAG = MainHook.TAG;

    private static final String CLASS_TASK_ITEM = "com.miui.autotask.taskitem.TaskItem";
    private static final String CLASS_LUNCH_APP_ITEM = "com.miui.autotask.taskitem.LunchAppItem";
    private static final String CLASS_ADD_CONDITION_FRAGMENT = "com.miui.autotask.fragment.AddConditionFragment";
    private static final String CLASS_SELECT_APP_ACTIVITY = "com.miui.autotask.activity.SelectAppActivity";

    /** AddBaseFragment.b，选应用页返回时用的 requestCode */
    private static final int REQUEST_ADD_CONDITION = 102;

    private FirstAppConditionHook() {
    }

    static void install(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;

        // 尽早注入：Application.attach 是应用拿到 Context 的第一刻，早于任何业务代码
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                DexInjector.inject((Context) param.args[0], cl);
            }
        });

        Class<?> taskItem = XposedHelpers.findClass(CLASS_TASK_ITEM, cl);
        Class<?> lunchAppItem = XposedHelpers.findClass(CLASS_LUNCH_APP_ITEM, cl);

        HookUtils.step("M0", () -> hookM0(cl, taskItem));
        HookUtils.step("AddConditionFragment", () -> hookAddConditionFragment(cl, taskItem, lunchAppItem));
        HookUtils.step("SelectAppActivity", () -> hookSelectAppActivity(cl, lunchAppItem));
        HookUtils.step("engine", () -> hookEngine(cl, taskItem));
    }

    // ------------------------------------------------------------------ g2.M0

    private static void hookM0(ClassLoader cl, Class<?> taskItem) {
        Class<?> m0 = TargetResolver.factory(cl);

        // Class h(String key)：数据库里存的是 key + json，靠它找回具体类
        XposedHelpers.findAndHookMethod(m0, "h", String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String key = (String) param.args[0];
                if (FirstAppKeys.isFirstAppKey(key) && DexInjector.isReady()) {
                    Object cls = XposedHelpers.callStaticMethod(DexInjector.runtime(), "classForKey", key);
                    if (cls != null) {
                        param.setResult(cls);
                    }
                }
            }
        });

        // TaskItem B(String key)：条件列表、默认任务等处按 key 造实例
        XposedHelpers.findAndHookMethod(m0, "B", String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String key = (String) param.args[0];
                if (FirstAppKeys.isFirstAppKey(key) && DexInjector.isReady()) {
                    Object item = XposedHelpers.callStaticMethod(DexInjector.runtime(), "newItem", key);
                    if (item != null) {
                        param.setResult(item);
                    }
                }
            }
        });

        // Map<String, List<String>> j()：添加条件页的分类 -> key 列表
        XposedHelpers.findAndHookMethod(m0, "j", new XC_MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam param) {
                if (!DexInjector.isReady()) {
                    return;
                }
                Object result = param.getResult();
                if (!(result instanceof Map)) {
                    return;
                }
                Object list = ((Map<Object, Object>) result).get(FirstAppKeys.CATEGORY_EVENT);
                if (!(list instanceof List)) {
                    XposedBridge.log(TAG + ": 条件分类里没有 " + FirstAppKeys.CATEGORY_EVENT + "，新条件未加入列表");
                    return;
                }
                List<String> keys = (List<String>) list;
                if (keys.contains(FirstAppKeys.KEY_FIRST_START)) {
                    return;
                }
                // 紧跟在原「启动应用 / 离开应用」之后
                int at = keys.indexOf(FirstAppKeys.KEY_LEAVE_ACTIVITY_CONDITION);
                if (at < 0) {
                    at = keys.indexOf(FirstAppKeys.KEY_START_ACTIVITY_CONDITION);
                }
                at = at < 0 ? keys.size() : at + 1;
                keys.add(at, FirstAppKeys.KEY_FIRST_START);
                keys.add(at + 1, FirstAppKeys.KEY_FIRST_LEAVE);
            }
        });

        // String s(String key)：退出条件对应的反向 key
        XposedHelpers.findAndHookMethod(m0, "s", String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String key = (String) param.args[0];
                if (FirstAppKeys.isFirstAppKey(key)) {
                    param.setResult(FirstAppKeys.opposite(key));
                }
            }
        });

        // TaskItem t(TaskItem item)：勾选「退出时恢复」后由条件生成退出条件
        XposedHelpers.findAndHookMethod(m0, "t", taskItem, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object item = param.args[0];
                if (isFirstAppItem(item)) {
                    param.setResult(XposedHelpers.callStaticMethod(DexInjector.runtime(), "opposite", item));
                }
            }
        });

        // int e(TaskItem item, List list)：条件被编辑后同步退出条件列表里的对应项
        XposedHelpers.findAndHookMethod(m0, "e", taskItem, List.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object item = param.args[0];
                if (isFirstAppItem(item)) {
                    param.setResult(XposedHelpers.callStaticMethod(
                            DexInjector.runtime(), "syncOpposite", item, param.args[1]));
                }
            }
        });
    }

    // ------------------------------------------------------------------ 添加条件页

    private static void hookAddConditionFragment(ClassLoader cl, Class<?> taskItem, Class<?> lunchAppItem) {
        Class<?> fragment = XposedHelpers.findClass(CLASS_ADD_CONDITION_FRAGMENT, cl);
        final Class<?> selectApp = XposedHelpers.findClass(CLASS_SELECT_APP_ACTIVITY, cl);

        // static void d1/e1/S0(Activity, LunchAppItem, int)：打开选应用页
        final Method openSelectApp = HookUtils.findMethod(selectApp, new String[]{"d1", "e1", "S0"},
                void.class, Activity.class, lunchAppItem, int.class);
        // private void q1/n1(TaskItem)：点击某个条件后的分发
        Method onConditionClick = HookUtils.findMethod(fragment, new String[]{"q1", "n1"},
                void.class, taskItem);
        if (openSelectApp == null || onConditionClick == null) {
            XposedBridge.log(TAG + ": AddConditionFragment/SelectAppActivity 方法未找到，新条件无法从列表进入");
            return;
        }

        XposedBridge.hookMethod(onConditionClick, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object item = param.args[0];
                if (!isFirstAppItem(item)) {
                    return;
                }
                Object activity = XposedHelpers.callMethod(param.thisObject, "getActivity");
                if (activity == null) {
                    return;
                }
                openSelectApp.invoke(null, activity, item, REQUEST_ADD_CONDITION);
                param.setResult(null);
            }
        });
    }

    // ------------------------------------------------------------------ 选应用页

    private static void hookSelectAppActivity(ClassLoader cl, Class<?> lunchAppItem) {
        Class<?> selectApp = XposedHelpers.findClass(CLASS_SELECT_APP_ACTIVITY, cl);

        // protected String K0/L0/y0()：按条件 key 决定标题，原生 start/leave 分支里还会把多选开关 j 置 true
        Method title = HookUtils.findMethod(selectApp, new String[]{"K0", "L0", "y0"}, String.class);
        final Field itemField = HookUtils.findField(selectApp, "h", lunchAppItem);
        final Field multiSelectField = HookUtils.findField(selectApp, "j", boolean.class);
        if (title == null || itemField == null || multiSelectField == null) {
            XposedBridge.log(TAG + ": SelectAppActivity 成员未找到，新条件的选应用页标题/多选不可用");
            return;
        }

        XposedBridge.hookMethod(title, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object item = itemField.get(param.thisObject);
                if (!isFirstAppItem(item)) {
                    return;
                }
                multiSelectField.setBoolean(param.thisObject, true);
                param.setResult(XposedHelpers.callMethod(item, "h"));
            }
        });
    }

    // ------------------------------------------------------------------ 引擎 b2.j

    private static void hookEngine(ClassLoader cl, Class<?> taskItem) {
        Class<?> engine = TargetResolver.engine(cl);

        // 单例构造完成 -> 启动进程跟踪（有一个 synthetic 构造器会套着调私有构造器，start 内部幂等）
        XposedBridge.hookAllConstructors(engine, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!DexInjector.isReady()) {
                    XposedBridge.log(TAG + ": 引擎已创建但模块 dex 未注入，进程跟踪未启动");
                    return;
                }
                Context ctx = DexInjector.appContext();
                if (ctx == null) {
                    ctx = AndroidAppHelper.currentApplication();
                }
                if (ctx == null) {
                    XposedBridge.log(TAG + ": 拿不到 Context，进程跟踪未启动");
                    return;
                }
                XposedHelpers.callStaticMethod(DexInjector.runtime(), "start", ctx, param.thisObject);
            }
        });

        // private void p(TaskItem)：按 key 把条件项塞进各自的 map，自己的 key 它不认识
        Method register = HookUtils.findMethod(engine, new String[]{"p", "t"},
                void.class, taskItem);
        // private void b1/d1/o1(String uuid, List items)：任务停用/删除时把 uuid 从所有 map 移除
        Method unregister = HookUtils.findMethod(engine, new String[]{"b1", "d1", "o1"},
                void.class, String.class, List.class);
        if (register == null || unregister == null) {
            XposedBridge.log(TAG + ": b2.j 的注册/反注册方法未找到，新条件不会被引擎触发");
            return;
        }

        XposedBridge.hookMethod(register, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object item = param.args[0];
                if (isFirstAppItem(item)) {
                    XposedHelpers.callStaticMethod(DexInjector.runtime(), "register", item);
                }
            }
        });

        XposedBridge.hookMethod(unregister, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (DexInjector.isReady() && param.args[0] instanceof String) {
                    XposedHelpers.callStaticMethod(DexInjector.runtime(), "unregister", (String) param.args[0]);
                }
            }
        });
    }

    // ------------------------------------------------------------------ 工具

    private static boolean isFirstAppItem(Object item) {
        return item != null && DexInjector.isReady()
                && (Boolean) XposedHelpers.callStaticMethod(DexInjector.runtime(), "isFirstAppItem", item);
    }
}
