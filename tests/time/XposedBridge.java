package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public final class XposedBridge {
    private static final Map<Method, XC_MethodHook> HOOKS = new HashMap<>();
    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook hook) {
        HOOKS.put((Method) method, hook);
        return hook.new Unhook();
    }
    public static Object invoke(Object target, String name, Class<?>[] types, Object[] args, Supplier<Object> original) {
        try {
            XC_MethodHook hook = HOOKS.get(target.getClass().getMethod(name, types));
            XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
            param.thisObject = target;
            param.args = args;
            if (hook != null) hook.beforeHookedMethod(param);
            if (!param.intercepted) {
                try { param.result = original.get(); }
                catch (Throwable error) { param.error = error; }
            }
            if (hook != null) hook.afterHookedMethod(param);
            if (param.error != null) throw param.error;
            return param.result;
        } catch (Throwable error) { throw new AssertionError(error); }
    }
}
