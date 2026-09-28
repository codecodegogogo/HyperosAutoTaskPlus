package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.os.Looper;
import android.util.Log;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * 全局触摸监视器。安全服务是 system UID，可以用系统隐藏 API monitorGestureInput
 * 「旁听」整块屏幕的触摸，而不拦截它——被监视的应用仍能正常响应。
 *
 * 用途有两个：录制时读取用户的触摸；回放时监听真实手指以便立即中止。
 * monitorGestureInput 的入口随版本不同：
 *   Android 12+  InputManager.getInstance().monitorGestureInput(name, displayId) -> InputMonitor
 *                InputMonitor.getInputChannel() -> InputChannel
 * 全程反射，拿不到就返回 null，调用方据此提示功能不可用。
 */
final class GestureMonitor {

    interface Listener {
        /** 收到一个触摸事件（已在 handler 线程）。返回后事件会被 finish 掉。 */
        void onTouch(MotionEvent event);
    }

    private Object inputMonitor;       // android.view.InputMonitor
    private InputEventReceiver receiver;
    private final Listener listener;

    private GestureMonitor(Listener listener) {
        this.listener = listener;
    }

    /**
     * 在指定 Looper 上开启监视。name 仅用于系统调试标识，displayId 0 为主屏。
     * 失败返回 null。
     */
    static GestureMonitor start(String name, int displayId, Looper looper, Listener listener) {
        GestureMonitor monitor = new GestureMonitor(listener);
        try {
            Object inputManager = Class.forName("android.hardware.input.InputManager")
                    .getMethod("getInstance").invoke(null);
            if (inputManager == null) throw new IllegalStateException("InputManager 不可用");
            Method monitorGesture = inputManager.getClass()
                    .getMethod("monitorGestureInput", String.class, int.class);
            Object inputMonitor = monitorGesture.invoke(inputManager, name, displayId);
            if (inputMonitor == null) throw new IllegalStateException("monitorGestureInput 返回 null");
            InputChannel channel = (InputChannel) inputMonitor.getClass()
                    .getMethod("getInputChannel").invoke(inputMonitor);
            monitor.inputMonitor = inputMonitor;
            monitor.receiver = new Receiver(channel, looper, monitor);
            return monitor;
        } catch (Throwable t) {
            Log.e(DeviceActionRuntime.TAG, "start gesture monitor failed", t);
            monitor.dispose();
            return null;
        }
    }

    void dispose() {
        if (receiver != null) {
            try { receiver.dispose(); } catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "dispose receiver", t); }
            receiver = null;
        }
        if (inputMonitor != null) {
            try { inputMonitor.getClass().getMethod("dispose").invoke(inputMonitor); }
            catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "dispose input monitor", t); }
            inputMonitor = null;
        }
    }

    private static final class Receiver extends InputEventReceiver {
        private final GestureMonitor owner;

        Receiver(InputChannel channel, Looper looper, GestureMonitor owner) {
            super(channel, looper);
            this.owner = owner;
        }

        @Override public void onInputEvent(InputEvent event) {
            boolean handled = false;
            try {
                if (event instanceof MotionEvent && owner.listener != null) {
                    owner.listener.onTouch((MotionEvent) event);
                }
            } catch (Throwable t) {
                Log.w(DeviceActionRuntime.TAG, "handle monitored touch failed", t);
            } finally {
                // 监视器不消费事件：始终以 handled=false 归还，避免影响被监视应用。
                try { finishInputEvent(event, handled); } catch (Throwable ignored) {}
            }
        }
    }
}
