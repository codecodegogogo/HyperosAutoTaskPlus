package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * 精确闹钟的小封装：AlarmManager.setExact(ELAPSED_REALTIME_WAKEUP, …, OnAlarmListener, Handler)。
 * 安全中心是 system uid，不受 Doze 限制。编译用的 android.jar 太老，OnAlarmListener 走反射 + 动态代理。
 * 每个实例各管一组 tag -> 闹钟，同一 tag 再次设置会先取消旧的。
 */
final class Alarms {

    private static final String TAG = "HyperAutoEnh";

    private final String prefix;
    private final Map<String, Object> listeners = new HashMap<>();
    private AlarmManager alarm;
    private Handler handler;
    private Class<?> listenerCls;
    private Method setExact;
    private Method cancel;

    Alarms(String prefix) {
        this.prefix = prefix;
    }

    boolean init(Context context) {
        try {
            alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            handler = new Handler(Looper.getMainLooper());
            listenerCls = Class.forName("android.app.AlarmManager$OnAlarmListener");
            setExact = AlarmManager.class.getMethod("setExact",
                    int.class, long.class, String.class, listenerCls, Handler.class);
            cancel = AlarmManager.class.getMethod("cancel", listenerCls);
            return alarm != null;
        } catch (Throwable t) {
            Log.e(TAG, "init alarms failed", t);
            return false;
        }
    }

    boolean ready() {
        return alarm != null && setExact != null;
    }

    /** 在 elapsedRealtime 到达 dueElapsed 时执行 onAlarm；调用方自己做同步 */
    void set(final String tag, long dueElapsed, final Runnable onAlarm) {
        cancel(tag);
        if (!ready()) {
            return;
        }
        final Object[] self = new Object[1];
        final Object listener = newListener(new Runnable() {
            @Override
            public void run() {
                synchronized (Alarms.this) {
                    // 只有还是当前那一次的闹钟才算数，已被取消/重设的直接忽略
                    if (listeners.get(tag) != self[0]) {
                        return;
                    }
                    listeners.remove(tag);
                }
                onAlarm.run();
            }
        });
        self[0] = listener;
        try {
            setExact.invoke(alarm, AlarmManager.ELAPSED_REALTIME_WAKEUP, dueElapsed, prefix + tag, listener, handler);
            synchronized (this) {
                listeners.put(tag, listener);
            }
        } catch (Throwable t) {
            Log.e(TAG, "set alarm failed: " + tag, t);
        }
    }

    void cancel(String tag) {
        Object old;
        synchronized (this) {
            old = listeners.remove(tag);
        }
        if (old != null && cancel != null) {
            try {
                cancel.invoke(alarm, old);
            } catch (Throwable ignored) {
                // 已触发过的闹钟取消会失败，无所谓
            }
        }
    }

    private Object newListener(final Runnable onAlarm) {
        return Proxy.newProxyInstance(Alarms.class.getClassLoader(), new Class<?>[]{listenerCls},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("onAlarm".equals(name)) {
                            try {
                                onAlarm.run();
                            } catch (Throwable t) {
                                Log.e(TAG, "alarm callback failed", t);
                            }
                            return null;
                        }
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(name)) {
                            return args != null && args.length == 1 && proxy == args[0];
                        }
                        if ("toString".equals(name)) {
                            return "Alarms." + prefix;
                        }
                        return null;
                    }
                });
    }
}
