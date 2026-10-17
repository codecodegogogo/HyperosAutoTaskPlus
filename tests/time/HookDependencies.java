package io.github.codecodegogogo.HyperosAutoTaskPlus;

import java.lang.reflect.Method;
import io.github.codecodegogogo.HyperosAutoTaskPlus.inject.TimeExitRuntime;

/** Host resolution is checked against APKs separately; these fixtures execute the real hook callbacks. */
final class TargetResolver {
    static Class<?> engine(ClassLoader ignored) { return TimeExitEngineTest.Engine.class; }
    static Class<?> factory(ClassLoader ignored) { return TimeExitEngineTest.Factory.class; }
}

final class DexInjector {
    static Class<?> runtime(String ignored) { return TimeExitRuntime.class; }
}

final class HookUtils {
    static Method findMethod(Class<?> type, String[] names, Class<?> result, Class<?>... args) {
        for (String name : names) {
            try {
                Method method = type.getMethod(name, args);
                if (method.getReturnType() == result) return method;
            } catch (NoSuchMethodException ignored) {}
        }
        throw new AssertionError("Missing fixture method: " + names[0]);
    }
    static void invokeMethod(Method method, Object receiver, Object... args) {
        try { method.invoke(receiver, args); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
