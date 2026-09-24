package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * 按版本解析安全中心里随混淆变化的类。
 *
 * 只列已知版本还不够：同名类的含义也可能变化，所以每个候选类都要通过关键方法签名
 * 校验后才采用。解析失败只影响调用它的那一组 hook，由 HookUtils.step() 隔离。
 */
final class TargetResolver {

    private static final String TAG = MainHook.TAG;
    private static final String CLASS_TASK_ITEM = "com.miui.autotask.taskitem.TaskItem";

    /** 12.3.x / 12.8.x；13.5.x / 13.6.x */
    private static final String[] FACTORY = {"g2.M0", "Z1.I0", "i6.l2", "m6.h2"};
    private static final String[] EDITOR = {"g2.K0", "Z1.G0", "i6.j2", "m6.f2"};
    private static final String[] ENGINE = {"b2.j", "U1.j", "d6.m", "h6.m"};
    private static final String[] ADAPTER = {"Y1.v", "Y1.u", "R1.v", "a6.v", "e6.v"};

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private interface Validator {
        boolean isValid(Class<?> cls);
    }

    private TargetResolver() {
    }

    /** 条件 / 结果工厂：h(String)->Class、B(String)->TaskItem、j()->Map 等。 */
    static Class<?> factory(ClassLoader cl) {
        Class<?> taskItem = load(cl, CLASS_TASK_ITEM);
        return find(cl, FACTORY, cls -> hasMethod(cls, "h", Class.class, String.class)
                && hasMethod(cls, "B", taskItem, String.class)
                && hasMethod(cls, "j", Map.class));
    }

    /** 编辑页分发：F0 条件编辑、G0 结果编辑。 */
    static Class<?> editor(ClassLoader cl) {
        Class<?> taskItem = load(cl, CLASS_TASK_ITEM);
        return find(cl, EDITOR, cls -> isEditorDispatch(cls, taskItem, "F0")
                && isEditorDispatch(cls, taskItem, "G0"));
    }

    /** 任务引擎：p/t(TaskItem)、b1/d1/o1(String,List)、K/O(ConcurrentHashMap)。 */
    static Class<?> engine(ClassLoader cl) {
        Class<?> taskItem = load(cl, CLASS_TASK_ITEM);
        return find(cl, ENGINE, cls -> (hasMethod(cls, "p", void.class, taskItem)
                    || hasMethod(cls, "t", void.class, taskItem))
                && (hasMethod(cls, "b1", void.class, String.class, List.class)
                    || hasMethod(cls, "d1", void.class, String.class, List.class)
                    || hasMethod(cls, "o1", void.class, String.class, List.class))
                && (hasMethod(cls, "K", void.class, ConcurrentHashMap.class)
                    || hasMethod(cls, "O", void.class, ConcurrentHashMap.class)));
    }

    /** 退出条件列表适配器。 */
    static Class<?> adapter(ClassLoader cl) {
        return find(cl, ADAPTER, TargetResolver::isAdapter);
    }

    /** 退出条件列表 ViewHolder，也就是适配器内部的 c。 */
    static Class<?> adapterHolder(ClassLoader cl) {
        Class<?> adapter = adapter(cl);
        Class<?> holder = holderOf(adapter);
        if (holder == null) {
            throw new IllegalStateException(adapter.getName() + " 里没有 ViewHolder c");
        }
        return holder;
    }

    private static boolean isEditorDispatch(Class<?> cls, Class<?> taskItem, String name) {
        for (Method m : cls.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (name.equals(m.getName()) && !m.isSynthetic() && !m.isBridge()
                    && m.getReturnType() == void.class && Modifier.isStatic(m.getModifiers())
                    && p.length == 4 && p[0] == Context.class && p[1] == taskItem
                    && p[3] == int.class
                    && ("F0".equals(name)
                        ? p[2].isInterface() && hasMethod(p[2], "a", void.class, int.class)
                        : hasMethod(p[2], "notifyItemChanged", void.class, int.class))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAdapter(Class<?> cls) {
        Class<?> holder = holderOf(cls);
        if (holder == null
                || !hasField(cls, "a", List.class)
                || !hasField(cls, "e", boolean.class)) {
            return false;
        }
        return hasMethod(cls, "o", void.class, holder, int.class)
                || hasMethod(cls, "m", void.class, holder, int.class)
                || hasMethod(cls, "onBindViewHolder", void.class, holder, int.class);
    }

    private static Class<?> holderOf(Class<?> adapter) {
        for (Class<?> nested : adapter.getDeclaredClasses()) {
            if ("c".equals(nested.getSimpleName()) || nested.getName().endsWith("$c")) {
                return nested;
            }
        }
        return null;
    }

    private static boolean hasMethod(Class<?> cls, String name, Class<?> returnType, Class<?>... params) {
        try {
            Method method = cls.getDeclaredMethod(name, params);
            return method.getReturnType() == returnType;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean hasField(Class<?> cls, String name, Class<?> type) {
        for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                if (field.getType() == type) {
                    return true;
                }
            } catch (NoSuchFieldException ignored) {
                // continue in super classes
            }
        }
        return false;
    }

    private static Class<?> find(ClassLoader cl, String[] names, Validator validator) {
        for (String name : names) {
            try {
                Class<?> cls = Class.forName(name, false, cl);
                if (validator.isValid(cls)) {
                    if (LOGGED.add(names[0])) {
                        XposedBridge.log(TAG + ": target " + names[0] + " -> " + name);
                    }
                    return cls;
                }
            } catch (Throwable ignored) {
                // try next known version
            }
        }
        throw new IllegalStateException("无法解析目标类 " + Arrays.toString(names));
    }

    private static Class<?> load(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            throw new IllegalStateException("无法加载目标类 " + name, t);
        }
    }
}
