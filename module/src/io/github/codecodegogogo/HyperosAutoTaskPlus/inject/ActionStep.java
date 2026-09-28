package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 动作链里的一步。不可变：编辑某一步等于用新实例替换列表里的旧元素，
 * 这样撤销、拷贝、比较都简单，纯函数也方便 JVM 测试。
 *
 * 七种类型：
 *   TAP        点击：归一化坐标 + 按住时长（毫秒，0 表示瞬时点击）
 *   LONG_PRESS 长按：归一化坐标 + 按住时长
 *   SWIPE      滑动：一串手势帧；改时长时整体缩放时间轴，轨迹形状不变
 *   MULTI      多指手势：一串手势帧；只能整体删除，不能改参数
 *   DELAY      延迟：等待指定毫秒
 *   KEY        按键：返回 / 主页 / 最近任务
 *   REPEAT     重复块：把 children 连续执行 repeatCount 轮，每轮间隔 intervalMs；children 内不能再套 REPEAT
 */
public final class ActionStep implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type { TAP, LONG_PRESS, SWIPE, MULTI, DELAY, KEY, REPEAT }

    /** 与 KeyEvent.KEYCODE_* 一致的稳定常量 */
    public static final int KEY_HOME = 3;
    public static final int KEY_BACK = 4;
    public static final int KEY_RECENTS = 187; // KEYCODE_APP_SWITCH

    static final long MAX_DURATION_MS = 3_600_000L; // 单步时长 / 延迟上限 1 小时
    static final long MAX_TAP_PRESS_MS = 60_000L;
    static final int MAX_REPEAT_COUNT = 999;
    static final int MAX_FRAMES = 4000; // 单个手势的帧数上限，防止畸形数据

    private final Type type;
    private final float x;
    private final float y;
    private final long durationMs;
    private final List<GestureFrame> frames;
    private final int keyCode;
    private final int repeatCount;
    private final long intervalMs;
    private final List<ActionStep> children;

    private ActionStep(Type type, float x, float y, long durationMs, List<GestureFrame> frames,
                       int keyCode, int repeatCount, long intervalMs, List<ActionStep> children) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.durationMs = durationMs;
        this.frames = frames == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(frames));
        this.keyCode = keyCode;
        this.repeatCount = repeatCount;
        this.intervalMs = intervalMs;
        this.children = children == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(children));
    }

    // ------------------------------------------------------------------ 工厂

    public static ActionStep tap(float x, float y, long pressMs) {
        return new ActionStep(Type.TAP, clamp01(x), clamp01(y),
                bound(pressMs, 0, MAX_TAP_PRESS_MS), null, 0, 0, 0, null);
    }

    public static ActionStep longPress(float x, float y, long holdMs) {
        return new ActionStep(Type.LONG_PRESS, clamp01(x), clamp01(y),
                bound(holdMs, 1, MAX_DURATION_MS), null, 0, 0, 0, null);
    }

    public static ActionStep swipe(List<GestureFrame> frames) {
        return gesture(Type.SWIPE, frames);
    }

    public static ActionStep multi(List<GestureFrame> frames) {
        return gesture(Type.MULTI, frames);
    }

    private static ActionStep gesture(Type type, List<GestureFrame> frames) {
        if (frames == null || frames.size() < 2 || frames.size() > MAX_FRAMES) {
            throw new IllegalArgumentException("手势帧数不合法");
        }
        return new ActionStep(type, 0, 0, gestureDuration(frames), frames, 0, 0, 0, null);
    }

    public static ActionStep delay(long delayMs) {
        return new ActionStep(Type.DELAY, 0, 0, bound(delayMs, 0, MAX_DURATION_MS), null, 0, 0, 0, null);
    }

    public static ActionStep key(int keyCode) {
        if (keyCode != KEY_HOME && keyCode != KEY_BACK && keyCode != KEY_RECENTS) {
            throw new IllegalArgumentException("不支持的按键");
        }
        return new ActionStep(Type.KEY, 0, 0, 0, null, keyCode, 0, 0, null);
    }

    /** 重复块：children 里出现 REPEAT 直接拒绝，第一版不允许嵌套 */
    public static ActionStep repeat(int count, long intervalMs, List<ActionStep> children) {
        if (count < 1 || count > MAX_REPEAT_COUNT) throw new IllegalArgumentException("重复次数 1~999");
        if (children == null || children.isEmpty()) throw new IllegalArgumentException("重复块不能为空");
        for (ActionStep child : children) {
            if (child.type == Type.REPEAT) throw new IllegalArgumentException("重复块不能嵌套");
        }
        return new ActionStep(Type.REPEAT, 0, 0, 0, null, 0, count, bound(intervalMs, 0, MAX_DURATION_MS), children);
    }

    // ------------------------------------------------------------------ 编辑（返回新实例）

    /** 改点击 / 长按坐标 */
    public ActionStep withCoords(float nx, float ny) {
        requirePoint();
        return new ActionStep(type, clamp01(nx), clamp01(ny), durationMs, null, 0, 0, 0, null);
    }

    /** 改点击 / 长按 / 延迟的时长 */
    public ActionStep withDuration(long ms) {
        long lo = type == Type.LONG_PRESS ? 1 : 0;
        long hi = type == Type.TAP ? MAX_TAP_PRESS_MS : MAX_DURATION_MS;
        if (type != Type.TAP && type != Type.LONG_PRESS && type != Type.DELAY) {
            throw new IllegalStateException("该步骤没有时长");
        }
        return new ActionStep(type, x, y, bound(ms, lo, hi), frames, keyCode, repeatCount, intervalMs, children);
    }

    /** 改滑动总时长：把每帧的时间偏移按比例缩放，轨迹坐标不变 */
    public ActionStep withSwipeDuration(long newTotalMs) {
        if (type != Type.SWIPE) throw new IllegalStateException("只有滑动可以改时长");
        long clamped = bound(newTotalMs, 1, MAX_DURATION_MS);
        long old = gestureDuration(frames);
        List<GestureFrame> scaled = new ArrayList<>(frames.size());
        for (GestureFrame f : frames) {
            long mapped = old == 0 ? 0 : Math.round((double) f.timeOffsetMs * clamped / old);
            scaled.add(f.withTimeOffset(mapped));
        }
        return new ActionStep(type, 0, 0, gestureDuration(scaled), scaled, 0, 0, 0, null);
    }

    /** 改重复次数 / 间隔，children 不变 */
    public ActionStep withRepeat(int count, long intervalMs) {
        if (type != Type.REPEAT) throw new IllegalStateException("不是重复块");
        return repeat(count, intervalMs, children);
    }

    /** 改重复块内的步骤 */
    public ActionStep withChildren(List<ActionStep> newChildren) {
        if (type != Type.REPEAT) throw new IllegalStateException("不是重复块");
        return repeat(repeatCount, intervalMs, newChildren);
    }

    // ------------------------------------------------------------------ 读取

    public Type type() { return type; }
    public float x() { return x; }
    public float y() { return y; }
    public long durationMs() { return durationMs; }
    public List<GestureFrame> frames() { return frames; }
    public int keyCode() { return keyCode; }
    public int repeatCount() { return repeatCount; }
    public long intervalMs() { return intervalMs; }
    public List<ActionStep> children() { return children; }

    /** 展开成回放计划时这一步（单轮）占用的时长估算，用于摘要里的"约 X 秒" */
    long estimatedDurationMs() {
        switch (type) {
            case TAP: return Math.max(durationMs, 1);
            case LONG_PRESS: return durationMs;
            case SWIPE: case MULTI: return gestureDuration(frames);
            case DELAY: return durationMs;
            case KEY: return 1;
            case REPEAT:
                long once = 0;
                for (ActionStep child : children) once += child.estimatedDurationMs();
                return once * repeatCount + intervalMs * Math.max(0, repeatCount - 1);
            default: return 0;
        }
    }

    private void requirePoint() {
        if (type != Type.TAP && type != Type.LONG_PRESS) throw new IllegalStateException("该步骤没有坐标");
    }

    static long gestureDuration(List<GestureFrame> frames) {
        long max = 0;
        for (GestureFrame f : frames) if (f.timeOffsetMs > max) max = f.timeOffsetMs;
        return max;
    }

    static float clamp01(float v) {
        if (v < 0f) return 0f;
        return v > 1f ? 1f : v;
    }

    static long bound(long v, long lo, long hi) {
        if (v < lo) return lo;
        return v > hi ? hi : v;
    }
}
