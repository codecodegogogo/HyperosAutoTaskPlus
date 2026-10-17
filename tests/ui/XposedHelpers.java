package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** JVM-only reflection shim; never included in the module APK. */
public final class XposedHelpers {
    private static final Map<Object, Map<String, Object>> FIELDS = new WeakHashMap<>();

    public static Class<?> findClass(String name, ClassLoader loader) {
        String type;
        switch (name) {
            case "com.miui.autotask.view.RecyclerViewPreference": type = "Content"; break;
            case "androidx.preference.PreferenceCategory": type = "Group"; break;
            case "com.miui.permcenter.settings.model.FlexiblePreference": type = "Header"; break;
            default: return Object.class;
        }
        try {
            return Class.forName("io.github.codecodegogogo.HyperosAutoTaskPlus.TaskSectionCardTest$" + type);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    public static Object newInstance(Class<?> type, Object... args) {
        try {
            for (Constructor<?> constructor : type.getConstructors()) {
                if (matches(constructor.getParameterTypes(), args)) return constructor.newInstance(args);
            }
            throw new NoSuchMethodException(type.getName());
        } catch (ReflectiveOperationException e) {
            throw failure(e);
        }
    }

    public static Object callMethod(Object receiver, String name, Object... args) {
        try {
            for (Method method : receiver.getClass().getMethods()) {
                if (method.getName().equals(name) && matches(method.getParameterTypes(), args)) {
                    return method.invoke(receiver, args);
                }
            }
            throw new NoSuchMethodException(name);
        } catch (ReflectiveOperationException e) {
            throw failure(e);
        }
    }

    public static Object setAdditionalInstanceField(Object object, String key, Object value) {
        return FIELDS.computeIfAbsent(object, ignored -> new HashMap<>()).put(key, value);
    }

    public static Object getAdditionalInstanceField(Object object, String key) {
        Map<String, Object> fields = FIELDS.get(object);
        return fields == null ? null : fields.get(key);
    }

    public static Object removeAdditionalInstanceField(Object object, String key) {
        Map<String, Object> fields = FIELDS.get(object);
        return fields == null ? null : fields.remove(key);
    }

    private static boolean matches(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i] == int.class ? Integer.class
                    : types[i] == boolean.class ? Boolean.class : types[i];
            if (args[i] != null && !type.isInstance(args[i])) return false;
        }
        return true;
    }

    private static RuntimeException failure(ReflectiveOperationException e) {
        Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
        return cause instanceof RuntimeException ? (RuntimeException) cause : new IllegalStateException(cause);
    }
}
