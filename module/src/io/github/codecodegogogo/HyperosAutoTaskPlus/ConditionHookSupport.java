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

/**
 * 注入式条件项接入安全中心各分发点的通用 hook。
 *
 * 每个 Runtime（inject 包，由安全中心 ClassLoader 加载）约定提供这些 public static 方法：
 *   Class  itemClass(String key)           key -> Class（数据库 Gson 反序列化）
 *   Object newItem(String key)             key -> 新实例
 *   boolean isItem(Object item)
 *   Object opposite(Object item)           由条件生成退出条件，没有就返回 null
 *   int    syncOpposite(Object item, List) 条件编辑后同步退出条件列表
 *   void   pickAndApply(Context, Object item, Runnable onConfirm)   编辑对话框
 *   void   start(Context, Object engine) / register(Object item) / unregister(String uuid)
 *
 * 这里按需把它们挂到 g2.M0.h/B/j/s/t/e、AddConditionFragment.q1、g2.K0.F0、b2.j 上。
 * 同一个原生方法可以被多个功能各挂一次，各自只认自己的 key / 类型。
 */
final class ConditionHookSupport {

    private static final String TAG = MainHook.TAG;

    static final String CLASS_TASK_ITEM = "com.miui.autotask.taskitem.TaskItem";
    static final String CLASS_ADD_CONDITION_FRAGMENT = "com.miui.autotask.fragment.AddConditionFragment";

    interface KeyMatcher {
        boolean matches(String key);
    }

    private ConditionHookSupport() {
    }

    static Class<?> runtime(String runtimeName) {
        return DexInjector.runtime(runtimeName);
    }

    static boolean isItem(String runtimeName, Object item) {
        Class<?> rt = item == null ? null : runtime(runtimeName);
        return rt != null && (Boolean) XposedHelpers.callStaticMethod(rt, "isItem", item);
    }

    // ------------------------------------------------------------------ g2.M0

    /** M0.h(key) / M0.B(key)：key -> Class / 新实例 */
    static void hookFactory(ClassLoader cl, final String runtimeName, final KeyMatcher keys) {
        Class<?> m0 = TargetResolver.factory(cl);

        XposedHelpers.findAndHookMethod(m0, "h", String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String key = (String) param.args[0];
                Class<?> rt = keys.matches(key) ? runtime(runtimeName) : null;
                if (rt != null) {
                    Object cls = XposedHelpers.callStaticMethod(rt, "itemClass", key);
                    if (cls != null) {
                        param.setResult(cls);
                    }
                }
            }
        });

        XposedHelpers.findAndHookMethod(m0, "B", String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String key = (String) param.args[0];
                Class<?> rt = keys.matches(key) ? runtime(runtimeName) : null;
                if (rt != null) {
                    Object item = XposedHelpers.callStaticMethod(rt, "newItem", key);
                    if (item != null) {
                        param.setResult(item);
                    }
                }
            }
        });
    }

    /**
     * M0.j()：添加条件页的分类 -> key 列表。把 insertKeys 插到 category 分类里 anchorKey 之后；
     * anchorKey 为 null 或不存在时追加到末尾。requireAnchor 为 true 时锚点不存在就不加
     * （比如地理围栏依赖原生「到达/离开某地」是否可用）。分类不存在时新建一组。
     */
    static void hookCategory(ClassLoader cl, final String runtimeName, final String category,
                             final String anchorKey, final boolean requireAnchor, final String... insertKeys) {
        Class<?> m0 = TargetResolver.factory(cl);
        XposedHelpers.findAndHookMethod(m0, "j", new XC_MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam param) {
                if (runtime(runtimeName) == null || !(param.getResult() instanceof Map)) {
                    return;
                }
                Map<Object, Object> map = (Map<Object, Object>) param.getResult();
                Object list = map.get(category);
                if (!(list instanceof List)) {
                    if (requireAnchor) {
                        return;
                    }
                    list = new java.util.ArrayList<String>();
                    map.put(category, list);
                }
                List<String> keys = (List<String>) list;
                if (keys.contains(insertKeys[0])) {
                    return;
                }
                int at = anchorKey == null ? -1 : keys.indexOf(anchorKey);
                if (at < 0 && requireAnchor) {
                    return;
                }
                at = at < 0 ? keys.size() : at + 1;
                for (int i = 0; i < insertKeys.length; i++) {
                    keys.add(at + i, insertKeys[i]);
                }
            }
        });
    }

    /** M0.s(key)：退出条件对应的反向 key（用 FirstAppKeys.opposite） */
    static void hookOppositeKey(ClassLoader cl, final KeyMatcher keys) {
        Class<?> m0 = TargetResolver.factory(cl);
        XposedHelpers.findAndHookMethod(m0, "s", String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String key = (String) param.args[0];
                if (keys.matches(key)) {
                    param.setResult(FirstAppKeys.opposite(key));
                }
            }
        });
    }

    /** M0.t(item) / M0.e(item, list)：「退出时恢复」生成 / 同步退出条件 */
    static void hookOpposite(ClassLoader cl, Class<?> taskItem, final String runtimeName) {
        Class<?> m0 = TargetResolver.factory(cl);

        XposedHelpers.findAndHookMethod(m0, "t", taskItem, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isItem(runtimeName, param.args[0])) {
                    param.setResult(XposedHelpers.callStaticMethod(runtime(runtimeName), "opposite", param.args[0]));
                }
            }
        });

        XposedHelpers.findAndHookMethod(m0, "e", taskItem, List.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isItem(runtimeName, param.args[0])) {
                    param.setResult(XposedHelpers.callStaticMethod(
                            runtime(runtimeName), "syncOpposite", param.args[0], param.args[1]));
                }
            }
        });
    }

    // ------------------------------------------------------------------ 编辑对话框

    /** AddConditionFragment.q1/n1(item) 与 K0.F0(ctx,item,cb,pos)：都弹 Runtime.pickAndApply */
    static void hookDialogs(ClassLoader cl, Class<?> taskItem, final String runtimeName) {
        Class<?> fragment = XposedHelpers.findClass(CLASS_ADD_CONDITION_FRAGMENT, cl);
        Method onConditionClick = HookUtils.findMethod(fragment, new String[]{"q1", "n1"},
                void.class, taskItem);
        Method applyCondition = HookUtils.findMethod(fragment, new String[]{"w0", "t0"},
                void.class, taskItem);
        if (onConditionClick == null || applyCondition == null) {
            XposedBridge.log(TAG + ": AddConditionFragment.q1/n1 或 w0/t0 未找到，"
                    + runtimeName + " 无法从列表添加");
        } else {
            XposedBridge.hookMethod(onConditionClick, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    final Object item = param.args[0];
                    if (!isItem(runtimeName, item)) {
                        return;
                    }
                    param.setResult(null);
                    Object activity = XposedHelpers.callMethod(param.thisObject, "getActivity");
                    if (activity == null) {
                        return;
                    }
                    final Object self = param.thisObject;
                    Runnable onConfirm = () -> HookUtils.invokeMethod(applyCondition, self, item);
                    XposedHelpers.callStaticMethod(runtime(runtimeName), "pickAndApply", activity, item, onConfirm);
                }
            });
        }

        Class<?> k0 = TargetResolver.editor(cl);
        Method editClick = findEditMethod(k0, taskItem);
        if (editClick == null) {
            XposedBridge.log(TAG + ": K0.F0 未找到，" + runtimeName + " 在任务编辑页里不能再次修改");
            return;
        }
        XposedBridge.hookMethod(editClick, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                final Object item = param.args[1];
                if (!isItem(runtimeName, item)) {
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
                XposedHelpers.callStaticMethod(runtime(runtimeName), "pickAndApply", context, item, onConfirm);
            }
        });
    }

    /**
     * public static void F0(Context, TaskItem, RecyclerViewPreference$c, int)。
     * 结果那边的 G0 形状相同，区别在第三个参数：这里是 RecyclerViewPreference 的回调接口（只有 a(int)）。
     */
    static Method findEditMethod(Class<?> k0, Class<?> taskItem) {
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

    /** 引擎 <init> / p(item) / b1|d1(uuid, list)：引擎就绪、任务启用、任务停用 */
    static void hookEngine(ClassLoader cl, Class<?> taskItem, final String runtimeName) {
        Class<?> engine = TargetResolver.engine(cl);

        XposedBridge.hookAllConstructors(engine, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Class<?> rt = runtime(runtimeName);
                if (rt == null) {
                    XposedBridge.log(TAG + ": 引擎已创建但 " + runtimeName + " 未注入");
                    return;
                }
                Context ctx = DexInjector.appContext();
                if (ctx == null) {
                    ctx = AndroidAppHelper.currentApplication();
                }
                if (ctx != null) {
                    XposedHelpers.callStaticMethod(rt, "start", ctx, param.thisObject);
                }
            }
        });

        Method register = HookUtils.findMethod(engine, new String[]{"p", "t"},
                void.class, taskItem);
        Method unregister = HookUtils.findMethod(engine, new String[]{"b1", "d1", "o1"},
                void.class, String.class, List.class);
        if (register == null || unregister == null) {
            XposedBridge.log(TAG + ": b2.j 的注册/反注册方法未找到，" + runtimeName + " 不会被引擎触发");
            return;
        }

        XposedBridge.hookMethod(register, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (isItem(runtimeName, param.args[0])) {
                    XposedHelpers.callStaticMethod(runtime(runtimeName), "register", param.args[0]);
                }
            }
        });

        XposedBridge.hookMethod(unregister, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Class<?> rt = runtime(runtimeName);
                if (rt != null && param.args[0] instanceof String) {
                    XposedHelpers.callStaticMethod(rt, "unregister", (String) param.args[0]);
                }
            }
        });
    }
}
