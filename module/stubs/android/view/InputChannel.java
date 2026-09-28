package android.view;

/**
 * 编译桩：android.view.InputChannel。运行时用系统真实类（同名同包），本桩不进 dex。
 * 本模块只把它当作 InputEventReceiver 构造参数的类型引用，不调用它的方法。
 */
public final class InputChannel {
    public InputChannel() {
        throw new RuntimeException("stub");
    }
}
