package de.robv.android.xposed;

import java.util.HashMap;
import java.util.Map;

/** JVM callback fixture; matches only the Xposed API used by the time-exit hook. */
public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public final class Unhook {}

    public static final class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        boolean intercepted;
        Object result;
        Throwable error;
        private final Map<String, Object> extras = new HashMap<>();
        public void setResult(Object value) { result = value; intercepted = true; error = null; }
        public Object getResult() { return result; }
        public boolean hasThrowable() { return error != null; }
        public void setObjectExtra(String key, Object value) { extras.put(key, value); }
        public Object getObjectExtra(String key) { return extras.get(key); }
    }
}
