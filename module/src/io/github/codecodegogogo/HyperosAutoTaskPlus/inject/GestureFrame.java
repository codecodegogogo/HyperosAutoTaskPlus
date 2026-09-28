package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import java.io.Serializable;

/**
 * 录制到的一帧触摸采样。坐标已归一化（normalize，把像素坐标除以屏幕宽高换算到 0~1），
 * 这样不同分辨率的设备回放时按各自屏幕尺寸还原即可。
 *
 * 只用于滑动（SWIPE）和多指手势（MULTI）；点击 / 长按不存帧序列，直接由坐标和时长生成。
 * action 取值与 Android 的 MotionEvent 常量一致，但这里自带常量，纯 JVM 测试不依赖 android.jar。
 */
public final class GestureFrame implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 与 MotionEvent.ACTION_* 相同：稳定的 ABI 常量 */
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_POINTER_DOWN = 5;
    public static final int ACTION_POINTER_UP = 6;

    /** 相对本手势第一帧的时间偏移（毫秒） */
    public final long timeOffsetMs;
    /** actionMasked（不含 pointer index 的高位） */
    public final int action;
    /** POINTER_DOWN / POINTER_UP 影响的手指在数组中的下标；其它 action 为 0 */
    public final int actionIndex;
    public final int[] pointerIds;
    /** 归一化 X，与 pointerIds 一一对应 */
    public final float[] xs;
    public final float[] ys;

    public GestureFrame(long timeOffsetMs, int action, int actionIndex, int[] pointerIds, float[] xs, float[] ys) {
        if (timeOffsetMs < 0) throw new IllegalArgumentException("时间偏移不能为负");
        if (pointerIds == null || xs == null || ys == null
                || pointerIds.length == 0 || pointerIds.length != xs.length || pointerIds.length != ys.length) {
            throw new IllegalArgumentException("手指数据长度不一致");
        }
        if (actionIndex < 0 || actionIndex >= pointerIds.length) throw new IllegalArgumentException("actionIndex 越界");
        this.timeOffsetMs = timeOffsetMs;
        this.action = action;
        this.actionIndex = actionIndex;
        this.pointerIds = pointerIds.clone();
        this.xs = clamp(xs);
        this.ys = clamp(ys);
    }

    public int pointerCount() { return pointerIds.length; }

    /** 归一化坐标越界（例如屏幕边缘外的采样）夹到 0~1，回放时不会落到屏幕外 */
    private static float[] clamp(float[] values) {
        float[] copy = values.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < 0f) copy[i] = 0f;
            else if (copy[i] > 1f) copy[i] = 1f;
        }
        return copy;
    }

    /** 缩放时间轴：滑动改总时长时，每帧偏移按比例映射，保持轨迹形状 */
    GestureFrame withTimeOffset(long newOffsetMs) {
        return new GestureFrame(newOffsetMs, action, actionIndex, pointerIds, xs, ys);
    }
}
