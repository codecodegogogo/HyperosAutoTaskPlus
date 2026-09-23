package android.hardware.display;

/** API 19+ 编译桩；本项目的 android.jar 较旧，不打进 dex。 */
public final class VirtualDisplay {
    public void release() { throw new RuntimeException("stub"); }
    public static abstract class Callback {
        public void onPaused() {}
        public void onResumed() {}
        public void onStopped() {}
    }
}
