package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

/**
 * 编译工具链使用的是 API 16 的 android.jar，缺 TYPE_APPLICATION_OVERLAY（API 26 引入）。
 * 这里写明常量值 2038，运行时的安全服务在新系统上，语义一致。
 */
final class OverlayWindow {
    /** WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY */
    static final int TYPE_APPLICATION_OVERLAY = 2038;

    private OverlayWindow() {}
}
