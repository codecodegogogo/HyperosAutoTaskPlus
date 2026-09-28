package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.os.SystemClock;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * 底层输入事件注入。安全服务是 system UID（用户标识 1000），天然通过 INJECT_EVENTS
 * （注入输入事件）权限检查，可以用系统隐藏 API 注入触摸和按键。
 *
 * injectInputEvent 是隐藏方法：Android 14（API 34）起从 InputManager 迁到 InputManagerGlobal，
 * 这里两条路径都试，全程反射，找到一次就缓存。
 *
 * 注入的事件用固定 deviceId（DEVICE_ID）标记，回放时据此把"模块自己注入的触摸"和
 * "用户真实手指"区分开，实现"用户一碰屏幕就中止"。
 */
final class InputInjector {
    /** 与真实触摸屏（通常为正数 deviceId）区分的自定义值；负数不会与真实设备冲突 */
    static final int DEVICE_ID = -31415;
    static final int SOURCE_TOUCHSCREEN = 0x1002; // InputDevice.SOURCE_TOUCHSCREEN
    private static final int MODE_ASYNC = 0;       // INJECT_INPUT_EVENT_MODE_ASYNC

    private static Object sTarget;   // InputManager 或 InputManagerGlobal 实例
    private static Method sInject;   // injectInputEvent(InputEvent, int)
    private static boolean sResolved;

    private InputInjector() {}

    /** 注入一个已构造好的事件，成功返回 true。 */
    static synchronized boolean inject(InputEvent event) {
        if (!resolve()) return false;
        try {
            Object result = sInject.invoke(sTarget, event, MODE_ASYNC);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable t) {
            Log.w(DeviceActionRuntime.TAG, "injectInputEvent failed", t);
            return false;
        }
    }

    /** 注入一次按键（down + up）。 */
    static boolean injectKey(int keyCode) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                DEVICE_ID, 0, 0, 0x101 /* SOURCE_KEYBOARD */);
        KeyEvent up = new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, 0,
                DEVICE_ID, 0, 0, 0x101);
        return inject(down) & inject(up);
    }

    private static boolean resolve() {
        if (sResolved) return sInject != null;
        sResolved = true;
        // Android 14+：InputManagerGlobal.getInstance().injectInputEvent(...)
        if (tryResolve("android.hardware.input.InputManagerGlobal")) return true;
        // Android 13 及更早：InputManager.getInstance().injectInputEvent(...)
        if (tryResolve("android.hardware.input.InputManager")) return true;
        Log.e(DeviceActionRuntime.TAG, "no injectInputEvent entry found");
        return false;
    }

    private static boolean tryResolve(String className) {
        try {
            Class<?> cls = Class.forName(className);
            Object instance = cls.getMethod("getInstance").invoke(null);
            Method inject = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
            if (instance == null) return false;
            sTarget = instance;
            sInject = inject;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 构造一个多指 MotionEvent，坐标为像素。properties/coords 数组按当前按下的手指准备好。 */
    static MotionEvent motion(long downTime, long eventTime, int action, int pointerCount,
                              MotionEvent.PointerProperties[] props, MotionEvent.PointerCoords[] coords) {
        return MotionEvent.obtain(downTime, eventTime, action, pointerCount, props, coords,
                0, 0, 1f, 1f, DEVICE_ID, 0, SOURCE_TOUCHSCREEN, 0);
    }
}
