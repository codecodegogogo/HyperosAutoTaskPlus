package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import java.util.ArrayList;
import java.util.List;

/**
 * 把录制到的原始触摸帧流切成 ActionStep 序列。纯函数，不依赖 Android，JVM 可测。
 *
 * 一次完整手势 = 从第一根手指按下（DOWN）到最后一根手指抬起（UP）之间的所有帧。
 * 手势之间的空档变成 DELAY 步骤。每个手势按下面规则归类：
 *   - 全程只有一根手指，且位移不超过 touchSlop：时长 < 长按阈值算 TAP，否则 LONG_PRESS
 *   - 一根手指但有明显位移：SWIPE
 *   - 出现过多根手指：MULTI（原样保留帧，回放时照搬）
 *
 * 分段参数（长按超时、touchSlop）由调用方从 ViewConfiguration 取得后传入，测试时可给固定值。
 */
final class GestureSegmenter {

    private GestureSegmenter() {}

    /** 一个手势的原始帧 + 起止时间（绝对毫秒） */
    static final class RawGesture {
        final List<GestureFrame> frames; // 时间偏移已相对本手势第一帧归零
        final long startMs;
        final long endMs;
        final int maxPointers;
        RawGesture(List<GestureFrame> frames, long startMs, long endMs, int maxPointers) {
            this.frames = frames;
            this.startMs = startMs;
            this.endMs = endMs;
            this.maxPointers = maxPointers;
        }
    }

    /**
     * @param gestures     已经按手势分好组的原始数据（录制器负责按 DOWN..UP 切分并归一化坐标）
     * @param longPressMs  长按判定阈值（ViewConfiguration.getLongPressTimeout）
     * @param slop         归一化后的移动容差（touchSlop / 屏幕尺寸），超过算滑动
     * @param minDelayMs   小于此值的手势间空档忽略，不生成延迟步骤
     */
    static List<ActionStep> segment(List<RawGesture> gestures, long longPressMs, float slop, long minDelayMs) {
        List<ActionStep> steps = new ArrayList<>();
        long prevEnd = -1;
        for (RawGesture g : gestures) {
            if (prevEnd >= 0) {
                long gap = g.startMs - prevEnd;
                if (gap >= minDelayMs && gap > 0) steps.add(ActionStep.delay(gap));
            }
            steps.add(classify(g, longPressMs, slop));
            prevEnd = g.endMs;
        }
        return steps;
    }

    private static ActionStep classify(RawGesture g, long longPressMs, float slop) {
        if (g.maxPointers > 1 || g.frames.size() > 2 && hasPointerChange(g.frames)) {
            return ActionStep.multi(g.frames);
        }
        // 单指：看位移
        float[] start = firstPoint(g.frames);
        float travel = maxTravel(g.frames, start[0], start[1]);
        long duration = g.endMs - g.startMs;
        if (travel > slop) {
            return ActionStep.swipe(g.frames);
        }
        if (duration >= longPressMs) {
            return ActionStep.longPress(start[0], start[1], duration);
        }
        return ActionStep.tap(start[0], start[1], duration);
    }

    private static boolean hasPointerChange(List<GestureFrame> frames) {
        for (GestureFrame f : frames) {
            if (f.action == GestureFrame.ACTION_POINTER_DOWN || f.action == GestureFrame.ACTION_POINTER_UP) {
                return true;
            }
        }
        return false;
    }

    private static float[] firstPoint(List<GestureFrame> frames) {
        GestureFrame f = frames.get(0);
        return new float[]{f.xs[0], f.ys[0]};
    }

    private static float maxTravel(List<GestureFrame> frames, float x0, float y0) {
        float max = 0f;
        for (GestureFrame f : frames) {
            for (int i = 0; i < f.pointerCount(); i++) {
                float dx = f.xs[i] - x0;
                float dy = f.ys[i] - y0;
                float d = (float) Math.hypot(dx, dy);
                if (d > max) max = d;
            }
        }
        return max;
    }
}
