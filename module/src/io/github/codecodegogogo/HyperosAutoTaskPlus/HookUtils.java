package io.github.codecodegogogo.HyperosAutoTaskPlus;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XposedBridge;

/**
 * 各 hook 共用的反射查找工具。混淆后的成员名随版本可能变化，
 * 这里先按名字精确找，找不到再按签名扫描并要求唯一命中。
 * 查找包含父类：部分版本把实际逻辑放在 AddBaseFragment 等基类中。
 */
final class HookUtils {

    private static final String TAG = MainHook.TAG;

    private HookUtils() {
    }

    /**
     * 先按名字精确找，找不到再按（返回值 + 参数表）扫描，且要求唯一命中。
     * 名字是混淆产物，签名相对稳定。
     */
    static Method findMethod(Class<?> cls, String name, Class<?> returnType, Class<?>... params) {
        return findMethod(cls, new String[]{name}, returnType, params);
    }

    /** 兼容同一个逻辑方法在已知版本里的多个混淆名，最后再按签名扫描。 */
    static Method findMethod(Class<?> cls, String[] names, Class<?> returnType, Class<?>... params) {
        for (String name : names) {
            Method m = findDeclaredMethod(cls, name, params);
            if (m != null && m.getReturnType() == returnType) {
                m.setAccessible(true);
                return m;
            }
        }

        for (Class<?> current = cls; current != null && current != Object.class;
                current = current.getSuperclass()) {
            Method found = null;
            for (Method m : current.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge()
                        || m.getReturnType() != returnType
                        || !java.util.Arrays.equals(m.getParameterTypes(), params)) {
                    continue;
                }
                if (found != null) {
                    XposedBridge.log(TAG + ": " + current.getSimpleName() + " 里签名 "
                            + java.util.Arrays.toString(params) + " -> " + returnType.getSimpleName()
                            + " 的方法不唯一，放弃");
                    return null;
                }
                found = m;
            }
            if (found != null) {
                found.setAccessible(true);
                XposedBridge.log(TAG + ": " + cls.getSimpleName() + "." + names[0] + " 改名为 "
                        + found.getName() + "（声明于 " + current.getSimpleName() + "）");
                return found;
            }
        }
        return null;
    }

    /** 在类继承链上按精确参数表查找，返回第一个声明匹配方法的父类。 */
    private static Method findDeclaredMethod(Class<?> cls, String name, Class<?>... params) {
        for (Class<?> current = cls; current != null && current != Object.class;
                current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                // continue with the superclass
            }
        }
        return null;
    }

    /** 调用已经解析到的 Method，避免对继承方法再次按名字反射时找不到。 */
    static void invokeMethod(Method method, Object receiver, Object... args) {
        try {
            method.invoke(receiver, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static Field findField(Class<?> cls, String name, Class<?> type) {
        try {
            Field f = cls.getDeclaredField(name);
            if (f.getType() == type) {
                f.setAccessible(true);
                return f;
            }
        } catch (NoSuchFieldException ignored) {
            // fall through
        }
        Field found = null;
        for (Field f : cls.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != type) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = f;
        }
        if (found != null) {
            found.setAccessible(true);
        }
        return found;
    }

    interface Step {
        void run() throws Throwable;
    }

    /** 各处 hook 互不影响：一处失败只丢那一处的能力，并把原因写进日志 */
    static void step(String what, Step step) {
        try {
            step.run();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + what + " 失败");
            XposedBridge.log(t);
        }
    }
}
