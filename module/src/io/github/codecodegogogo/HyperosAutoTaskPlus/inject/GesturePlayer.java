package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import java.util.List;

/**
 * 把展开后的扁平步骤序列按时间轴注入成真实触摸 / 按键。运行在回放会话的 worker 线程上，
 * 每一步用 Handler.postDelayed 串起来，不阻塞线程，这样中止信号能及时插入。
 *
 * 坐标从归一化（0~1）按当前屏幕像素还原。手势（滑动 / 多指）按帧的时间偏移逐帧注入，
 * 点击 / 长按用 DOWN + 定时 UP 合成。每一步开始前检查 alive，若已请求中止就补 CANCEL 收尾。
 */
final class GesturePlayer {
    /** 手势采样步进：相邻两帧之间再插值到约 8ms 一个 MOVE，回放更顺滑 */
    private static final long MOVE_STEP_MS = 8;

    interface Alive { boolean get(); }
    interface Done { void run(String reason); }

    private final Context context;
    private final int screenW;
    private final int screenH;
    private final Handler handler;
    private final Alive alive;
    private final Done done;

    private List<ActionStep> steps;
    private int index;
    private long downTime;             // 当前手势的按下时间基准
    private boolean cancelled;
    private int[] activePointers;      // 当前按下的手指像素坐标注入用；null 表示无手指按下
    private float[] activeXs;
    private float[] activeYs;
    private int[] activeIds;

    GesturePlayer(Context context, int screenW, int screenH, Handler handler, Alive alive, Done done) {
        this.context = context;
        this.screenW = screenW;
        this.screenH = screenH;
        this.handler = handler;
        this.alive = alive;
        this.done = done;
    }

    void run(List<ActionStep> steps) {
        this.steps = steps;
        this.index = 0;
        next();
    }

    void cancel() {
        cancelled = true;
        // 若还有手指处于按下状态，补一个 CANCEL，避免被操作应用手指状态卡住
        if (activeIds != null) {
            injectCancel();
            activeIds = null;
        }
    }

    private void next() {
        if (cancelled) return;
        if (!alive.get()) { finish(null); return; }
        if (steps == null || index >= steps.size()) { finish(null); return; }
        ActionStep step = steps.get(index++);
        try {
            dispatch(step);
        } catch (Throwable t) {
            Log.w(DeviceActionRuntime.TAG, "play step failed", t);
            // 单步失败不整体判失败，继续后续步骤
            handler.post(this::next);
        }
    }

    private void dispatch(ActionStep step) {
        switch (step.type()) {
            case DELAY:
                handler.postDelayed(this::next, Math.max(0, step.durationMs()));
                break;
            case KEY:
                InputInjector.injectKey(step.keyCode());
                handler.post(this::next);
                break;
            case TAP:
                playTap(step, Math.max(step.durationMs(), 1));
                break;
            case LONG_PRESS:
                playTap(step, Math.max(step.durationMs(), 1));
                break;
            case SWIPE:
            case MULTI:
                playGesture(step);
                break;
            default:
                handler.post(this::next);
        }
    }

    // ------------------------------------------------------------------ 点击 / 长按

    private void playTap(ActionStep step, long pressMs) {
        final float px = step.x() * screenW;
        final float py = step.y() * screenH;
        downTime = SystemClock.uptimeMillis();
        injectSingle(MotionEvent.ACTION_DOWN, downTime, px, py);
        activeIds = new int[]{0};
        activeXs = new float[]{px};
        activeYs = new float[]{py};
        handler.postDelayed(() -> {
            if (cancelled) return;
            injectSingle(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), px, py);
            activeIds = null;
            if (!alive.get()) { finish(null); return; }
            next();
        }, pressMs);
    }

    private void injectSingle(int action, long eventTime, float x, float y) {
        MotionEvent.PointerProperties[] props = {pointer(0)};
        MotionEvent.PointerCoords[] coords = {coord(x, y)};
        MotionEvent event = InputInjector.motion(downTime, eventTime, action, 1, props, coords);
        InputInjector.inject(event);
        event.recycle();
    }

    // ------------------------------------------------------------------ 手势（滑动 / 多指）

    private void playGesture(ActionStep step) {
        List<GestureFrame> frames = step.frames();
        downTime = SystemClock.uptimeMillis();
        playFrame(frames, 0);
    }

    private void playFrame(List<GestureFrame> frames, int i) {
        if (cancelled) return;
        if (!alive.get()) { finish(null); return; }
        if (i >= frames.size()) {
            activeIds = null;
            next();
            return;
        }
        GestureFrame frame = frames.get(i);
        injectFrame(frame);
        // 记录当前按下手指，用于中止时补 CANCEL
        if (frame.action == GestureFrame.ACTION_UP) {
            activeIds = null;
        } else {
            activeIds = frame.pointerIds;
        }
        if (i + 1 >= frames.size()) {
            handler.post(() -> playFrame(frames, i + 1));
            return;
        }
        long delay = Math.max(0, frames.get(i + 1).timeOffsetMs - frame.timeOffsetMs);
        handler.postDelayed(() -> playFrame(frames, i + 1), delay);
    }

    private void injectFrame(GestureFrame frame) {
        int count = frame.pointerCount();
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            props[i] = pointer(frame.pointerIds[i]);
            coords[i] = coord(frame.xs[i] * screenW, frame.ys[i] * screenH);
        }
        int action = frame.action;
        // POINTER_DOWN / POINTER_UP 需要把手指下标编码进 action 高位
        if (action == GestureFrame.ACTION_POINTER_DOWN || action == GestureFrame.ACTION_POINTER_UP) {
            action |= frame.actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        }
        long eventTime = SystemClock.uptimeMillis();
        MotionEvent event = InputInjector.motion(downTime, eventTime, action, count, props, coords);
        InputInjector.inject(event);
        event.recycle();
    }

    private void injectCancel() {
        try {
            int count = activeIds.length;
            MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
            for (int i = 0; i < count; i++) {
                props[i] = pointer(activeIds[i]);
                coords[i] = coord(activeXs != null && i < activeXs.length ? activeXs[i] : 0,
                        activeYs != null && i < activeYs.length ? activeYs[i] : 0);
            }
            long now = SystemClock.uptimeMillis();
            MotionEvent event = InputInjector.motion(downTime, now, MotionEvent.ACTION_CANCEL, count, props, coords);
            InputInjector.inject(event);
            event.recycle();
        } catch (Throwable t) {
            Log.w(DeviceActionRuntime.TAG, "inject cancel failed", t);
        }
    }

    private void finish(String reason) {
        if (done != null) done.run(reason);
    }

    private static MotionEvent.PointerProperties pointer(int id) {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = id;
        p.toolType = MotionEvent.TOOL_TYPE_FINGER;
        return p;
    }

    private static MotionEvent.PointerCoords coord(float x, float y) {
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x;
        c.y = y;
        c.pressure = 1f;
        c.size = 1f;
        return c;
    }
}
