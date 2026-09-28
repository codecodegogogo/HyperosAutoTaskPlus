package android.view;

import android.os.Looper;

/**
 * 编译桩：android.view.InputEventReceiver。运行时用系统真实类，本桩不进 dex。
 *
 * 本模块继承它来接收监视到的输入事件：录制时读触摸，回放时监听真实触摸以中止。
 * 收到事件后必须调用 finishInputEvent 归还给系统。dispose 释放通道。
 * 只保留本模块用到的构造与方法签名，与 AOSP 一致。
 */
public class InputEventReceiver {

    public InputEventReceiver(InputChannel inputChannel, Looper looper) {
        throw new RuntimeException("stub");
    }

    /** 收到一个输入事件；子类重写处理后需调用 finishInputEvent。 */
    public void onInputEvent(InputEvent event) {
        throw new RuntimeException("stub");
    }

    public void finishInputEvent(InputEvent event, boolean handled) {
        throw new RuntimeException("stub");
    }

    public void dispose() {
        throw new RuntimeException("stub");
    }
}
