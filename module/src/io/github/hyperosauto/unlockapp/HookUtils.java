package io.github.hyperosauto.unlockapp;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XposedBridge;

/**
 * 各 hook 共用的反射查找工具。混淆后的成员名随版本可能变化，
 * 这里先按名字精确找，找不到再按签名扫描并要求唯一命中。
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
        try {
            Method m = cls.getDeclaredMethod(name, params);
            if (m.getReturnType() == returnType) {
                m.setAccessible(true);
                return m;
            }
        } catch (NoSuchMethodException ignored) {
            // fall through
        }
        Method found = null;
        for (Method m : cls.getDeclaredMethods()) {
            if (m.isSynthetic() || m.isBridge()
                    || m.getReturnType() != returnType
                    || !java.util.Arrays.equals(m.getParameterTypes(), params)) {
                continue;
            }
            if (found != null) {
                XposedBridge.log(TAG + ": " + cls.getSimpleName() + " 里签名 " + java.util.Arrays.toString(params)
                        + " -> " + returnType.getSimpleName() + " 的方法不唯一，放弃");
                return null;
            }
            found = m;
        }
        if (found != null) {
            found.setAccessible(true);
            XposedBridge.log(TAG + ": " + cls.getSimpleName() + "." + name + " 改名为 " + found.getName());
        }
        return found;
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
