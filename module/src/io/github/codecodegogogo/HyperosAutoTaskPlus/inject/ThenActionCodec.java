package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.miui.autotask.taskitem.TaskItem;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** 用宿主工厂恢复具体结果类型，不把抽象 TaskItem 或任意类名写进配置。 */
public final class ThenActionCodec {
    private static final int MAX_ACTION_JSON_LENGTH = 256 * 1024;
    private static volatile Method sFactory;
    private static volatile Json sJson;

    private ThenActionCodec() {}

    public static synchronized void configure(Class<?> factory) {
        if (factory == null) throw new IllegalArgumentException("执行项目工厂不可用");
        try {
            Method method = factory.getDeclaredMethod("h", String.class);
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != Class.class) {
                throw new IllegalArgumentException("执行项目工厂不兼容");
            }
            method.setAccessible(true);
            json();
            sFactory = method;
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("执行项目工厂不兼容", e);
        }
    }

    public static ThenAction encode(TaskItem item) {
        if (item == null || item instanceof ThenResultItem || !item.l()) {
            throw new IllegalArgumentException("执行项目尚未配置完整");
        }
        String key = item.e();
        Class<?> type = resolve(key);
        if (!type.isInstance(item)) throw new IllegalArgumentException("执行项目与类型不匹配");
        String payload = json().write(item);
        checkPayload(payload);
        ThenAction action = new ThenAction(key, payload);
        if (!action.isValid()) throw new IllegalArgumentException("执行项目配置无效");
        return action;
    }

    public static TaskItem decode(ThenAction action) {
        if (action == null || !action.isValid()) throw new IllegalArgumentException("执行项目配置无效");
        checkPayload(action.getJson());
        Class<?> type = resolve(action.getKey());
        Object decoded = json().read(action.getJson(), type);
        if (!(decoded instanceof TaskItem) || decoded instanceof ThenResultItem) {
            throw new IllegalArgumentException("执行项目类型不可用");
        }
        TaskItem item = (TaskItem) decoded;
        if (!action.getKey().equals(item.e()) || !item.l()) {
            throw new IllegalArgumentException("执行项目尚未配置完整");
        }
        return item;
    }

    public static List<TaskItem> decodeAll(List<ThenAction> actions) {
        if (actions == null) throw new IllegalArgumentException("执行项目列表缺失");
        List<TaskItem> result = new ArrayList<>(actions.size());
        for (ThenAction action : actions) result.add(decode(action));
        return result;
    }

    /** 草稿复制不要求参数完整，也不改变宿主的 uuid、instanceId 和原始状态字段。 */
    public static TaskItem copyTask(TaskItem item) {
        if (item == null) throw new IllegalArgumentException("执行项目缺失");
        return (TaskItem) json().read(json().write(item), item.getClass());
    }

    static ThenResultItem readPlan(String payload) {
        if (payload == null) throw new IllegalArgumentException("再执行配置缺失");
        Object value = json().read(payload, ThenResultItem.class);
        if (!(value instanceof ThenResultItem)) throw new IllegalArgumentException("再执行配置损坏");
        return (ThenResultItem) value;
    }

    static String writeValue(Object value) { return json().write(value); }

    static <T> T readValue(String payload, Class<T> type) {
        return type.cast(json().read(payload, type));
    }

    private static Class<?> resolve(String key) {
        if (key == null || !new ThenAction(key, "{}").isValid()) {
            throw new IllegalArgumentException("只能添加已有执行项目，不能嵌套再执行");
        }
        Method factory = sFactory;
        if (factory == null) throw new IllegalStateException("执行项目工厂尚未初始化");
        try {
            Object value = factory.invoke(null, key);
            if (!(value instanceof Class)) throw new IllegalArgumentException("执行项目已不可用");
            Class<?> type = (Class<?>) value;
            if (!TaskItem.class.isAssignableFrom(type) || ThenResultItem.class.isAssignableFrom(type)
                    || Modifier.isAbstract(type.getModifiers())) {
                throw new IllegalArgumentException("执行项目类型不可用");
            }
            return type;
        } catch (ReflectiveOperationException e) {
            throw failure("读取执行项目类型失败", e);
        }
    }

    private static void checkPayload(String payload) {
        if (payload == null || payload.length() > MAX_ACTION_JSON_LENGTH) {
            throw new IllegalArgumentException("执行项目配置过大或缺失");
        }
        String trimmed = payload.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IllegalArgumentException("执行项目配置损坏");
        }
    }

    private static Json json() {
        Json result = sJson;
        if (result != null) return result;
        synchronized (ThenActionCodec.class) {
            if (sJson == null) sJson = new Json();
            return sJson;
        }
    }

    private static IllegalArgumentException failure(String message, ReflectiveOperationException error) {
        Throwable cause = error instanceof InvocationTargetException
                ? ((InvocationTargetException) error).getTargetException() : error;
        return new IllegalArgumentException(message, cause);
    }

    private static final class Json {
        final Object gson;
        final Method write;
        final Method read;

        Json() {
            try {
                Class<?> type = Class.forName("com.google.gson.Gson", true, TaskItem.class.getClassLoader());
                gson = type.getConstructor().newInstance();
                write = type.getMethod("toJson", Object.class);
                read = type.getMethod("fromJson", String.class, Class.class);
            } catch (ReflectiveOperationException e) {
                throw failure("宿主配置序列化不可用", e);
            }
        }

        String write(Object value) {
            try {
                return (String) write.invoke(gson, value);
            } catch (ReflectiveOperationException e) {
                throw failure("保存执行项目配置失败", e);
            }
        }

        Object read(String payload, Class<?> type) {
            try {
                return read.invoke(gson, payload, type);
            } catch (ReflectiveOperationException e) {
                throw failure("读取执行项目配置失败", e);
            }
        }
    }
}
