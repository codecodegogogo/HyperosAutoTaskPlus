package io.github.hyperosauto.unlockapp.inject;

import android.util.Log;

import com.miui.autotask.taskitem.TaskItem;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 与自动任务引擎（b2.j 单例）之间的桥：把「这些条件项可能变了，请重新判定」交给引擎。
 *
 * 引擎的入口是 public void K(ConcurrentHashMap<String, TaskItem>)，内部转 H() 投递到它自己的
 * auto_task 线程，逐个调 m()，满足就执行/恢复任务——和它处理前台切换、WLAN 变化的路径完全相同。
 * 各运行时（进程跟踪、屏幕状态……）共用这一份，引擎实例由 hook 侧在 b2.j 构造完成后交进来。
 */
final class EngineBridge {

    private static final String TAG = "HyperAutoEnh";

    private static volatile Object sEngine;
    private static volatile Method sNotify;

    private EngineBridge() {
    }

    static synchronized void attach(Object engine) {
        if (sEngine != null || engine == null) {
            return;
        }
        try {
            sNotify = findNotifyMethod(engine);
            sEngine = engine;
        } catch (Throwable t) {
            Log.e(TAG, "engine notify method not found", t);
        }
    }

    static boolean isAttached() {
        return sEngine != null;
    }

    /** 让引擎重新判定这些条件项（key 为 TaskItem.j()，与 b2.j 自己那些 map 的键一致） */
    static void notify(Map<String, ? extends TaskItem> items, String reason) {
        Object engine = sEngine;
        Method notify = sNotify;
        if (engine == null || notify == null || items == null || items.isEmpty()) {
            return;
        }
        Log.i(TAG, reason + " -> " + items.keySet());
        try {
            notify.invoke(engine, new ConcurrentHashMap<>(items));
        } catch (Throwable t) {
            Log.e(TAG, "notify engine failed", t);
        }
    }

    private static Method findNotifyMethod(Object engine) throws NoSuchMethodException {
        Class<?> cls = engine.getClass();
        // K() 是 public 的对外入口；名字随版本混淆可能变化，按签名兜底
        try {
            return cls.getMethod("K", ConcurrentHashMap.class);
        } catch (NoSuchMethodException ignored) {
            // fall through
        }
        for (Method m : cls.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == ConcurrentHashMap.class
                    && m.getReturnType() == void.class
                    && Modifier.isPublic(m.getModifiers())) {
                return m;
            }
        }
        throw new NoSuchMethodException("b2.j#K(ConcurrentHashMap)");
    }
}
