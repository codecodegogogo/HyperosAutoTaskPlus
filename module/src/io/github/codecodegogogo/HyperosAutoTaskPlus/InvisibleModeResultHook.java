package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.content.Context;
import android.graphics.drawable.Drawable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 功能三：新增「隐身模式」结果，可在任务里开启/关闭权限中心的隐身模式。
 *
 * 隐身模式在系统里只有设置页那一个开关（内置磁贴 InvisibleModeTileService 默认是禁用的），
 * 自动任务原生结果列表里没有它。结果项本身（inject 包）会被注入到安全中心的 ClassLoader，
 * 这里只负责把它接到安全中心的各个分发点上：
 *
 *   g2.M0.A(Context)    结果列表（放在「设置项」分类的「定位」之后）
 *   g2.M0.B(key)        key -> 新实例（结果列表、编辑页）
 *   g2.M0.h(key)        key -> Class（数据库 Gson 反序列化）
 *   AddResultFragment.v1()                列表建好后把被禁用的隐身模式图标压成半透明
 *   AddResultFragment.u2(item)            添加结果页点击 -> 弹「开启/关闭」单选框 -> w0(item) 返回
 *   g2.K0.G0(ctx,item,adapter,pos)        任务编辑页点击已添加的结果 -> 同样的单选框 -> 刷新该行
 *
 * 引擎（b2.j）执行结果时对所有 TaskItem 一视同仁地调 n()/o()，不需要额外接入。
 * 互斥表（M0.J）对未知 key 返回空列表，隐身模式不与任何条件/结果冲突。
 */
final class InvisibleModeResultHook {

    private static final String TAG = MainHook.TAG;

    private static final String CLASS_TASK_ITEM = "com.miui.autotask.taskitem.TaskItem";
    private static final String CLASS_ADD_RESULT_FRAGMENT = "com.miui.autotask.fragment.AddResultFragment";

    /** 原生 *_tran 图标的透明度是 0.3 */
    private static final int DISABLED_ALPHA = 77;

    private InvisibleModeResultHook() {
    }

    static void install(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        final Class<?> taskItem = XposedHelpers.findClass(CLASS_TASK_ITEM, cl);

        HookUtils.step("M0(result)", () -> hookM0(cl));
        HookUtils.step("AddResultFragment", () -> hookAddResultFragment(cl, taskItem));
        HookUtils.step("K0.G0", () -> hookEditDialog(cl, taskItem));
    }

    // ------------------------------------------------------------------ g2.M0

    private static void hookM0(ClassLoader cl) {
        Class<?> m0 = TargetResolver.factory(cl);

        // Class h(String key)：数据库里存的是 key + json，靠它找回具体类
        XposedHelpers.findAndHookMethod(m0, "h", String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FirstAppKeys.isInvisibleModeKey((String) param.args[0]) && ready()) {
                    param.setResult(XposedHelpers.callStaticMethod(runtime(), "itemClass"));
                }
            }
        });

        // TaskItem B(String key)：结果列表、编辑页等处按 key 造实例
        XposedHelpers.findAndHookMethod(m0, "B", String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FirstAppKeys.isInvisibleModeKey((String) param.args[0]) && ready()) {
                    param.setResult(XposedHelpers.callStaticMethod(runtime(), "newItem"));
                }
            }
        });

        // Map<String, List<String>> A(Context)：添加结果页的分类 -> key 列表，每次调用都新建，可直接改
        XposedHelpers.findAndHookMethod(m0, "A", Context.class, new XC_MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam param) {
                if (!ready()) {
                    return;
                }
                Object result = param.getResult();
                if (!(result instanceof Map)) {
                    return;
                }
                Object list = ((Map<Object, Object>) result).get(FirstAppKeys.CATEGORY_RESULT_SETTING);
                if (!(list instanceof List)) {
                    XposedBridge.log(TAG + ": 结果分类里没有 " + FirstAppKeys.CATEGORY_RESULT_SETTING + "，隐身模式未加入列表");
                    return;
                }
                List<String> keys = (List<String>) list;
                if (keys.contains(FirstAppKeys.KEY_INVISIBLE_MODE_RESULT)) {
                    return;
                }
                // 紧跟在「定位」之后：隐身模式管的正是定位/相机/麦克风
                int at = keys.indexOf(FirstAppKeys.KEY_LOCATION_RESULT);
                keys.add(at < 0 ? keys.size() : at + 1, FirstAppKeys.KEY_INVISIBLE_MODE_RESULT);
            }
        });
    }

    // ------------------------------------------------------------------ 添加结果页

    private static void hookAddResultFragment(ClassLoader cl, Class<?> taskItem) {
        Class<?> fragment = XposedHelpers.findClass(CLASS_ADD_RESULT_FRAGMENT, cl);

        // private void v1/s1()：按 M0.A 的分类建列表，被互斥的项 setEnabled(false) 并换成 i() 的半透明图标。
        // 隐身模式没有专门的半透明图，三态都是同一张，所以列表建好后把被禁用的那一项图标压成 30% 透明。
        Method buildList = HookUtils.findMethod(fragment, new String[]{"v1", "s1"}, void.class);
        if (buildList != null) {
            XposedBridge.hookMethod(buildList, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (ready()) {
                        dimDisabledIcon(param.thisObject);
                    }
                }
            });
        }

        // private void u2/r2(TaskItem)：点击某个结果后的分发，按 key switch，不认识的 key 什么都不做
        Method onResultClick = HookUtils.findMethod(fragment, new String[]{"u2", "r2"},
                void.class, taskItem);
        Method applyResult = HookUtils.findMethod(fragment, new String[]{"w0", "t0"},
                void.class, taskItem);
        if (onResultClick == null || applyResult == null) {
            XposedBridge.log(TAG + ": AddResultFragment 的点击/确认方法未找到，隐身模式无法从列表添加");
            return;
        }

        XposedBridge.hookMethod(onResultClick, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                final Object item = param.args[0];
                if (!isInvisibleItem(item)) {
                    return;
                }
                param.setResult(null);
                Object activity = XposedHelpers.callMethod(param.thisObject, "getActivity");
                if (activity == null) {
                    return;
                }
                // 和原生一样，新添加时单选框默认停在「开启」
                XposedHelpers.callMethod(item, "v", true);
                final Object self = param.thisObject;
                Runnable onConfirm = () -> {
                    // AddBaseFragment.w0/t0(TaskItem)：把结果塞进 Intent 返回给任务编辑页
                    HookUtils.invokeMethod(applyResult, self, item);
                };
                XposedHelpers.callStaticMethod(runtime(), "pickAndApply", activity, item, onConfirm);
            }
        });
    }

    // ------------------------------------------------------------------ 任务编辑页

    private static void hookEditDialog(ClassLoader cl, Class<?> taskItem) {
        Class<?> k0 = TargetResolver.editor(cl);

        // public static void G0(Context, TaskItem, RecyclerView.Adapter, int)：
        // 编辑页点击已添加的结果，按 key 弹各自的对话框，改完 notifyItemChanged(pos)
        Method editClick = findEditMethod(k0, taskItem);
        if (editClick == null) {
            XposedBridge.log(TAG + ": K0.G0 未找到，隐身模式在任务编辑页里不能再次修改开关（删掉重加仍可用）");
            return;
        }

        XposedBridge.hookMethod(editClick, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                final Object item = param.args[1];
                if (!isInvisibleItem(item)) {
                    return;
                }
                param.setResult(null);
                Context context = (Context) param.args[0];
                final Object adapter = param.args[2];
                final int position = (Integer) param.args[3];
                if (context == null || adapter == null) {
                    return;
                }
                Runnable onConfirm = () -> XposedHelpers.callMethod(adapter, "notifyItemChanged", position);
                XposedHelpers.callStaticMethod(runtime(), "pickAndApply", context, item, onConfirm);
            }
        });
    }

    /**
     * 先按名字 G0 找；找不到再扫 (Context, TaskItem, ?, int) -> void 的 public static 方法。
     * 条件项的编辑分发 F0 形状相同，区别在第三个参数：结果用的是 RecyclerView.Adapter
     * （有 notifyItemChanged(int)），条件用的是 RecyclerViewPreference 的回调接口。
     */
    static Method findEditMethod(Class<?> k0, Class<?> taskItem) {
        Method found = null;
        for (Method m : k0.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.isSynthetic() || m.getReturnType() != void.class
                    || !Modifier.isStatic(m.getModifiers())
                    || p.length != 4 || p[0] != Context.class || p[1] != taskItem || p[3] != int.class
                    || !hasNotifyItemChanged(p[2])) {
                continue;
            }
            if ("G0".equals(m.getName())) {
                m.setAccessible(true);
                return m;
            }
            if (found != null) {
                XposedBridge.log(TAG + ": K0 里结果编辑分发方法不唯一: " + found.getName() + " / " + m.getName());
                return null;
            }
            found = m;
        }
        if (found != null) {
            found.setAccessible(true);
            XposedBridge.log(TAG + ": K0.G0 改名为 " + found.getName());
        }
        return found;
    }

    private static boolean hasNotifyItemChanged(Class<?> cls) {
        try {
            cls.getMethod("notifyItemChanged", int.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 在「设置项」分类里找到被禁用的「隐身模式」，把它的图标压成半透明，和原生禁用态一致 */
    private static void dimDisabledIcon(Object fragment) {
        try {
            Object category = XposedHelpers.callMethod(fragment, "findPreference", FirstAppKeys.CATEGORY_RESULT_SETTING);
            if (category == null) {
                return;
            }
            String title = String.valueOf(XposedHelpers.callStaticMethod(runtime(), "title"));
            int count = (Integer) XposedHelpers.callMethod(category, "getPreferenceCount");
            for (int i = 0; i < count; i++) {
                Object pref = XposedHelpers.callMethod(category, "getPreference", i);
                if ((Boolean) XposedHelpers.callMethod(pref, "isEnabled")
                        || !title.contentEquals((CharSequence) XposedHelpers.callMethod(pref, "getTitle"))) {
                    continue;
                }
                Drawable icon = (Drawable) XposedHelpers.callMethod(pref, "getIcon");
                if (icon != null) {
                    Drawable dimmed = icon.mutate();
                    dimmed.setAlpha(DISABLED_ALPHA);
                    XposedHelpers.callMethod(pref, "setIcon", dimmed);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 压暗隐身模式禁用态图标失败: " + t);
        }
    }

    private static boolean ready() {
        return DexInjector.invisibleRuntime() != null;
    }

    private static Class<?> runtime() {
        return DexInjector.invisibleRuntime();
    }

    private static boolean isInvisibleItem(Object item) {
        return item != null && ready()
                && (Boolean) XposedHelpers.callStaticMethod(runtime(), "isItem", item);
    }
}
