package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.app.AndroidAppHelper;
import android.content.Context;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 功能四：新增「屏幕状态」条件（亮屏 / 息屏持续指定时长）。
 *
 * 条件项本身（inject 包）会被注入到安全中心的 ClassLoader，这里只负责把它接到各分发点上：
 *
 *   g2.M0.j()           条件列表（放在「事件」分类的「锁屏」之后）
 *   g2.M0.B(key)        key -> 新实例
 *   g2.M0.h(key)        key -> Class（数据库 Gson 反序列化）
 *   g2.M0.t(item)       由条件生成退出条件（相反状态、时长 0）
 *   g2.M0.e(item,list)  条件编辑后同步退出条件
 *   AddConditionFragment.q1(item)         添加条件页点击 -> 弹卡片对话框 -> w0(item) 返回
 *   g2.K0.F0(ctx,item,callback,pos)       任务编辑页点击已添加的条件 -> 同样的对话框 -> callback.a(pos)
 *   b2.j.<init> / p(item) / b1(uuid,list) 引擎就绪、任务启用、任务停用
 *
 * M0.s(key) 对未知 key 原样返回，我们的退出条件与触发条件共用一个 key，正好合适。
 */
final class ScreenStateConditionHook {

    private static final String TAG = MainHook.TAG;

    private static final String CLASS_TASK_ITEM = "com.miui.autotask.taskitem.TaskItem";
    private static final String CLASS_ADD_CONDITION_FRAGMENT = "com.miui.autotask.fragment.AddConditionFragment";

    private ScreenStateConditionHook() {
    }

    static void install(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        final Class<?> taskItem = XposedHelpers.findClass(CLASS_TASK_ITEM, cl);

        HookUtils.step("M0(screen)", () -> hookM0(cl, taskItem));
        HookUtils.step("AddConditionFragment(screen)", () -> hookAddConditionFragment(cl, taskItem));
        HookUtils.step("K0.F0", () -> hookEditDialog(cl, taskItem));
        HookUtils.step("engine(screen)", () -> hookEngine(cl, taskItem));
    }

    // ------------------------------------------------------------------ g2.M0

    private static void hookM0(ClassLoader cl, Class<?> taskItem) {
        Class<?> m0 = TargetResolver.factory(cl);

        XposedHelpers.findAndHookMethod(m0, "h", String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FirstAppKeys.isScreenStateKey((String) param.args[0]) && ready()) {
                    param.setResult(XposedHelpers.callStaticMethod(runtime(), "itemClass"));
                }
            }
        });

        XposedHelpers.findAndHookMethod(m0, "B", String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FirstAppKeys.isScreenStateKey((String) param.args[0]) && ready()) {
                    param.setResult(XposedHelpers.callStaticMethod(runtime(), "newItem"));
                }
            }
        });

        // Map<String, List<String>> j()：添加条件页的分类 -> key 列表
        XposedHelpers.findAndHookMethod(m0, "j", new XC_MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam param) {
                if (!ready() || !(param.getResult() instanceof Map)) {
                    return;
                }
                Object list = ((Map<Object, Object>) param.getResult()).get(FirstAppKeys.CATEGORY_EVENT);
                if (!(list instanceof List)) {
                    XposedBridge.log(TAG + ": 条件分类里没有 " + FirstAppKeys.CATEGORY_EVENT + "，屏幕状态未加入列表");
                    return;
                }
                List<String> keys = (List<String>) list;
                if (keys.contains(FirstAppKeys.KEY_SCREEN_STATE_CONDITION)) {
                    return;
                }
                int at = keys.indexOf(FirstAppKeys.KEY_LOCK_SCREEN_CONDITION);
                keys.add(at < 0 ? keys.size() : at + 1, FirstAppKeys.KEY_SCREEN_STATE_CONDITION);
            }
        });

        // TaskItem t(TaskItem)：勾选「退出时恢复」后由条件生成退出条件
        XposedHelpers.findAndHookMethod(m0, "t", taskItem, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isScreenItem(param.args[0])) {
                    param.setResult(XposedHelpers.callStaticMethod(runtime(), "opposite", param.args[0]));
                }
            }
        });

        // int e(TaskItem, List)：条件被编辑后同步退出条件列表里的对应项
        XposedHelpers.findAndHookMethod(m0, "e", taskItem, List.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isScreenItem(param.args[0])) {
                    param.setResult(XposedHelpers.callStaticMethod(
                            runtime(), "syncOpposite", param.args[0], param.args[1]));
                }
            }
        });
    }

    // ------------------------------------------------------------------ 添加条件页

    private static void hookAddConditionFragment(ClassLoader cl, Class<?> taskItem) {
        Class<?> fragment = XposedHelpers.findClass(CLASS_ADD_CONDITION_FRAGMENT, cl);
        Method onConditionClick = HookUtils.findMethod(fragment, new String[]{"q1", "n1"},
                void.class, taskItem);
        Method applyCondition = HookUtils.findMethod(fragment, new String[]{"w0", "t0"},
                void.class, taskItem);
        if (onConditionClick == null || applyCondition == null) {
            XposedBridge.log(TAG + ": AddConditionFragment.q1/n1 或 w0/t0 未找到，屏幕状态无法从列表添加");
            return;
        }

        XposedBridge.hookMethod(onConditionClick, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                final Object item = param.args[0];
                if (!isScreenItem(item)) {
                    return;
                }
                param.setResult(null);
                Object activity = XposedHelpers.callMethod(param.thisObject, "getActivity");
                if (activity == null) {
                    return;
                }
                final Object self = param.thisObject;
                Runnable onConfirm = () -> HookUtils.invokeMethod(applyCondition, self, item);
                XposedHelpers.callStaticMethod(runtime(), "pickAndApply", activity, item, onConfirm);
            }
        });
    }

    // ------------------------------------------------------------------ 任务编辑页

    private static void hookEditDialog(ClassLoader cl, Class<?> taskItem) {
        Class<?> k0 = TargetResolver.editor(cl);
        Method editClick = findEditMethod(k0, taskItem);
        if (editClick == null) {
            XposedBridge.log(TAG + ": K0.F0 未找到，屏幕状态在任务编辑页里不能再次修改（删掉重加仍可用）");
            return;
        }

        XposedBridge.hookMethod(editClick, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                final Object item = param.args[1];
                if (!isScreenItem(item)) {
                    return;
                }
                param.setResult(null);
                Context context = (Context) param.args[0];
                final Object callback = param.args[2];
                final int position = (Integer) param.args[3];
                if (context == null || callback == null) {
                    return;
                }
                // RecyclerViewPreference.c.a(int)：刷新该行并同步退出条件
                Runnable onConfirm = () -> XposedHelpers.callMethod(callback, "a", position);
                XposedHelpers.callStaticMethod(runtime(), "pickAndApply", context, item, onConfirm);
            }
        });
    }

    /**
     * public static void F0(Context, TaskItem, RecyclerViewPreference$c, int)。
     * 结果那边的 G0 形状相同，区别在第三个参数：这里是 RecyclerViewPreference 的回调接口（只有 a(int)）。
     */
    private static Method findEditMethod(Class<?> k0, Class<?> taskItem) {
        Method found = null;
        for (Method m : k0.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.isSynthetic() || m.getReturnType() != void.class
                    || !Modifier.isStatic(m.getModifiers())
                    || p.length != 4 || p[0] != Context.class || p[1] != taskItem || p[3] != int.class
                    || !p[2].isInterface() || !hasCallback(p[2])) {
                continue;
            }
            if ("F0".equals(m.getName())) {
                m.setAccessible(true);
                return m;
            }
            if (found != null) {
                XposedBridge.log(TAG + ": K0 里条件编辑分发方法不唯一: " + found.getName() + " / " + m.getName());
                return null;
            }
            found = m;
        }
        if (found != null) {
            found.setAccessible(true);
            XposedBridge.log(TAG + ": K0.F0 改名为 " + found.getName());
        }
        return found;
    }

    private static boolean hasCallback(Class<?> cls) {
        try {
            cls.getMethod("a", int.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 引擎 b2.j

    private static void hookEngine(ClassLoader cl, Class<?> taskItem) {
        Class<?> engine = TargetResolver.engine(cl);

        XposedBridge.hookAllConstructors(engine, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!ready()) {
                    XposedBridge.log(TAG + ": 引擎已创建但 ScreenStateRuntime 未注入，屏幕状态跟踪未启动");
                    return;
                }
                Context ctx = DexInjector.appContext();
                if (ctx == null) {
                    ctx = AndroidAppHelper.currentApplication();
                }
                if (ctx == null) {
                    return;
                }
                XposedHelpers.callStaticMethod(runtime(), "start", ctx, param.thisObject);
            }
        });

        Method register = HookUtils.findMethod(engine, new String[]{"p", "t"},
                void.class, taskItem);
        Method unregister = HookUtils.findMethod(engine, new String[]{"b1", "d1", "o1"},
                void.class, String.class, List.class);
        if (register == null || unregister == null) {
            XposedBridge.log(TAG + ": b2.j 的注册/反注册方法未找到，屏幕状态条件不会被引擎触发");
            return;
        }

        XposedBridge.hookMethod(register, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (isScreenItem(param.args[0])) {
                    XposedHelpers.callStaticMethod(runtime(), "register", param.args[0]);
                }
            }
        });

        XposedBridge.hookMethod(unregister, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (ready() && param.args[0] instanceof String) {
                    XposedHelpers.callStaticMethod(runtime(), "unregister", (String) param.args[0]);
                }
            }
        });
    }

    // ------------------------------------------------------------------ 工具

    private static boolean ready() {
        return DexInjector.screenRuntime() != null;
    }

    private static Class<?> runtime() {
        return DexInjector.screenRuntime();
    }

    private static boolean isScreenItem(Object item) {
        return item != null && ready()
                && (Boolean) XposedHelpers.callStaticMethod(runtime(), "isItem", item);
    }
}
