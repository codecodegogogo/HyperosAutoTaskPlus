package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条动作链：录制得到的一串步骤，加上整链重复设置和录制时的屏幕信息。
 *
 * 用自带版本号的二进制格式编解码（DataOutputStream），不依赖宿主的 Gson 或 org.json，
 * JVM 测试也能直接跑。损坏或未知版本的数据 decode 返回 null，思路同 UnlockFailureState。
 *
 * 归一化坐标（0~1）配合录制时的 rotation / 屏幕像素尺寸保存：回放时若方向不一致会被拒绝，
 * 方向一致时按当前屏幕尺寸还原像素坐标。
 */
public final class ActionChain {
    /** 编解码格式版本；结构变化时递增，旧版本 decode 返回 null */
    private static final int FORMAT = 1;
    static final int MAX_STEPS = 500;          // 顶层步骤数上限
    static final int MAX_FLATTENED_STEPS = 20_000; // 展开后总步数上限，防止近乎跑不完的链

    private final String id;
    private String name;
    private final long createdAt;
    private long updatedAt;
    private final int rotation;       // Surface.ROTATION_0..3
    private final int screenWidthPx;
    private final int screenHeightPx;
    private int repeatCount;
    private long repeatIntervalMs;
    private final List<ActionStep> steps;

    public ActionChain(String id, String name, long createdAt, long updatedAt, int rotation,
                       int screenWidthPx, int screenHeightPx, int repeatCount, long repeatIntervalMs,
                       List<ActionStep> steps) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("动作链缺少 id");
        this.id = id;
        this.name = name == null ? "" : name;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.rotation = rotation;
        this.screenWidthPx = Math.max(0, screenWidthPx);
        this.screenHeightPx = Math.max(0, screenHeightPx);
        this.repeatCount = ActionStep.bound(repeatCount, 1, ActionStep.MAX_REPEAT_COUNT) == 0
                ? 1 : (int) ActionStep.bound(repeatCount, 1, ActionStep.MAX_REPEAT_COUNT);
        this.repeatIntervalMs = ActionStep.bound(repeatIntervalMs, 0, ActionStep.MAX_DURATION_MS);
        this.steps = new ArrayList<>(steps == null ? Collections.emptyList() : steps);
    }

    // ------------------------------------------------------------------ 读写

    public String id() { return id; }
    public String name() { return name; }
    public void setName(String value) { name = value == null ? "" : value; }
    public long createdAt() { return createdAt; }
    public long updatedAt() { return updatedAt; }
    public void setUpdatedAt(long value) { updatedAt = value; }
    public int rotation() { return rotation; }
    public int screenWidthPx() { return screenWidthPx; }
    public int screenHeightPx() { return screenHeightPx; }
    public int repeatCount() { return repeatCount; }
    public long repeatIntervalMs() { return repeatIntervalMs; }

    public void setRepeat(int count, long intervalMs) {
        repeatCount = count < 1 ? 1 : (count > ActionStep.MAX_REPEAT_COUNT ? ActionStep.MAX_REPEAT_COUNT : count);
        repeatIntervalMs = ActionStep.bound(intervalMs, 0, ActionStep.MAX_DURATION_MS);
    }

    /** 顶层步骤，可编辑列表（编辑器直接操作副本） */
    public List<ActionStep> steps() { return steps; }

    public int totalStepCount() {
        int n = 0;
        for (ActionStep s : steps) n += s.type() == ActionStep.Type.REPEAT ? s.children().size() : 1;
        return n;
    }

    public ActionChain copy() {
        ActionChain c = decode(encode());
        if (c == null) throw new IllegalStateException("动作链复制失败");
        return c;
    }

    // ------------------------------------------------------------------ 校验

    /** 结构是否可安全保存 / 回放：非空、步数在上限内、展开后不超限 */
    public boolean isValid() {
        if (steps.isEmpty() || steps.size() > MAX_STEPS) return false;
        for (ActionStep s : steps) {
            if (s.type() == ActionStep.Type.REPEAT) {
                if (s.children().isEmpty()) return false;
                for (ActionStep child : s.children()) {
                    if (child.type() == ActionStep.Type.REPEAT) return false; // 不允许嵌套
                }
            }
        }
        return flattenCount() <= MAX_FLATTENED_STEPS;
    }

    /** 展开后（含整链重复和重复块）的可执行步骤总数 */
    long flattenCount() {
        long once = 0;
        for (ActionStep s : steps) {
            if (s.type() == ActionStep.Type.REPEAT) once += (long) s.children().size() * s.repeatCount();
            else once++;
        }
        return once * repeatCount;
    }

    /** 展开成扁平的可执行步骤序列：重复块和整链重复都摊开，其中不再有 REPEAT */
    public List<ActionStep> flatten() {
        List<ActionStep> out = new ArrayList<>();
        for (int round = 0; round < repeatCount; round++) {
            if (round > 0 && repeatIntervalMs > 0) out.add(ActionStep.delay(repeatIntervalMs));
            for (ActionStep s : steps) {
                if (s.type() != ActionStep.Type.REPEAT) { out.add(s); continue; }
                for (int r = 0; r < s.repeatCount(); r++) {
                    if (r > 0 && s.intervalMs() > 0) out.add(ActionStep.delay(s.intervalMs()));
                    out.addAll(s.children());
                }
            }
            if (out.size() > MAX_FLATTENED_STEPS) break;
        }
        return out;
    }

    public long estimatedDurationMs() {
        long once = 0;
        for (ActionStep s : steps) once += s.estimatedDurationMs();
        return once * repeatCount + repeatIntervalMs * Math.max(0, repeatCount - 1);
    }

    // ------------------------------------------------------------------ 二进制编解码

    public byte[] encode() {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeInt(FORMAT);
            out.writeUTF(id);
            out.writeUTF(name);
            out.writeLong(createdAt);
            out.writeLong(updatedAt);
            out.writeInt(rotation);
            out.writeInt(screenWidthPx);
            out.writeInt(screenHeightPx);
            out.writeInt(repeatCount);
            out.writeLong(repeatIntervalMs);
            out.writeInt(steps.size());
            for (ActionStep s : steps) writeStep(out, s);
            out.flush();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("动作链编码失败", e);
        }
    }

    /** 缺失、损坏或未知版本返回 null，不能据此回放 */
    public static ActionChain decode(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != FORMAT) return null;
            String id = in.readUTF();
            String name = in.readUTF();
            long createdAt = in.readLong();
            long updatedAt = in.readLong();
            int rotation = in.readInt();
            int width = in.readInt();
            int height = in.readInt();
            int repeatCount = in.readInt();
            long repeatIntervalMs = in.readLong();
            int stepCount = in.readInt();
            if (stepCount < 0 || stepCount > MAX_STEPS) return null;
            List<ActionStep> steps = new ArrayList<>(stepCount);
            for (int i = 0; i < stepCount; i++) steps.add(readStep(in, true));
            return new ActionChain(id, name, createdAt, updatedAt, rotation, width, height,
                    repeatCount, repeatIntervalMs, steps);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeStep(DataOutputStream out, ActionStep s) throws IOException {
        out.writeInt(s.type().ordinal());
        switch (s.type()) {
            case TAP: case LONG_PRESS:
                out.writeFloat(s.x()); out.writeFloat(s.y()); out.writeLong(s.durationMs());
                break;
            case SWIPE: case MULTI:
                writeFrames(out, s.frames());
                break;
            case DELAY:
                out.writeLong(s.durationMs());
                break;
            case KEY:
                out.writeInt(s.keyCode());
                break;
            case REPEAT:
                out.writeInt(s.repeatCount()); out.writeLong(s.intervalMs());
                out.writeInt(s.children().size());
                for (ActionStep child : s.children()) writeStep(out, child);
                break;
            default:
                throw new IOException("未知步骤类型");
        }
    }

    private static ActionStep readStep(DataInputStream in, boolean allowRepeat) throws IOException {
        int ordinal = in.readInt();
        ActionStep.Type[] types = ActionStep.Type.values();
        if (ordinal < 0 || ordinal >= types.length) throw new IOException("未知步骤类型 " + ordinal);
        switch (types[ordinal]) {
            case TAP: return ActionStep.tap(in.readFloat(), in.readFloat(), in.readLong());
            case LONG_PRESS: return ActionStep.longPress(in.readFloat(), in.readFloat(), in.readLong());
            case SWIPE: return ActionStep.swipe(readFrames(in));
            case MULTI: return ActionStep.multi(readFrames(in));
            case DELAY: return ActionStep.delay(in.readLong());
            case KEY: return ActionStep.key(in.readInt());
            case REPEAT:
                if (!allowRepeat) throw new IOException("重复块不能嵌套");
                int count = in.readInt();
                long interval = in.readLong();
                int childCount = in.readInt();
                if (childCount < 0 || childCount > MAX_STEPS) throw new IOException("重复块步数越界");
                List<ActionStep> children = new ArrayList<>(childCount);
                for (int i = 0; i < childCount; i++) children.add(readStep(in, false));
                return ActionStep.repeat(count, interval, children);
            default: throw new IOException("未知步骤类型");
        }
    }

    private static void writeFrames(DataOutputStream out, List<GestureFrame> frames) throws IOException {
        out.writeInt(frames.size());
        for (GestureFrame f : frames) {
            out.writeLong(f.timeOffsetMs);
            out.writeInt(f.action);
            out.writeInt(f.actionIndex);
            out.writeInt(f.pointerCount());
            for (int i = 0; i < f.pointerCount(); i++) {
                out.writeInt(f.pointerIds[i]);
                out.writeFloat(f.xs[i]);
                out.writeFloat(f.ys[i]);
            }
        }
    }

    private static List<GestureFrame> readFrames(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 2 || count > ActionStep.MAX_FRAMES) throw new IOException("帧数越界 " + count);
        List<GestureFrame> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long offset = in.readLong();
            int action = in.readInt();
            int actionIndex = in.readInt();
            int pointers = in.readInt();
            if (pointers < 1 || pointers > 16) throw new IOException("手指数越界 " + pointers);
            int[] ids = new int[pointers];
            float[] xs = new float[pointers];
            float[] ys = new float[pointers];
            for (int p = 0; p < pointers; p++) {
                ids[p] = in.readInt();
                xs[p] = in.readFloat();
                ys[p] = in.readFloat();
            }
            frames.add(new GestureFrame(offset, action, actionIndex, ids, xs, ys));
        }
        return frames;
    }
}
