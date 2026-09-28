package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 动作链数据层 JVM 回归：编解码往返、分段判定、展开顺序与上限、编辑操作、排队。
 * 不触碰 Android、不注入事件、不读写文件。
 */
public final class ActionChainTest {
    private static int checks;

    public static void main(String[] args) {
        testStepFactories();
        testCodecRoundTrip();
        testCodecRejectsBadData();
        testSegment();
        testFlatten();
        testEditing();
        testQueue();
        System.out.println("Action chain checks passed: " + checks);
    }

    // ------------------------------------------------------------------ 步骤工厂与校验

    private static void testStepFactories() {
        ActionStep tap = ActionStep.tap(0.5f, 0.5f, 50);
        check(tap.type() == ActionStep.Type.TAP && tap.durationMs() == 50, "点击时长保留");
        check(ActionStep.tap(-1f, 2f, 0).x() == 0f && ActionStep.tap(-1f, 2f, 0).y() == 1f, "坐标夹到 0~1");

        boolean rejectedNest = false;
        try {
            ActionStep inner = ActionStep.repeat(2, 0, Arrays.asList(ActionStep.tap(0.1f, 0.1f, 0)));
            ActionStep.repeat(2, 0, Arrays.asList(inner));
        } catch (IllegalArgumentException e) { rejectedNest = true; }
        check(rejectedNest, "重复块不能嵌套");

        boolean rejectedCount = false;
        try { ActionStep.repeat(0, 0, Arrays.asList(ActionStep.tap(0.1f, 0.1f, 0))); }
        catch (IllegalArgumentException e) { rejectedCount = true; }
        check(rejectedCount, "重复次数至少 1");

        boolean rejectedKey = false;
        try { ActionStep.key(999); } catch (IllegalArgumentException e) { rejectedKey = true; }
        check(rejectedKey, "拒绝未知按键");
    }

    // ------------------------------------------------------------------ 编解码

    private static ActionChain sample() {
        List<ActionStep> steps = new ArrayList<>();
        steps.add(ActionStep.tap(0.5f, 0.6f, 40));
        steps.add(ActionStep.delay(800));
        steps.add(ActionStep.longPress(0.2f, 0.3f, 1200));
        steps.add(ActionStep.swipe(swipeFrames(300)));
        steps.add(ActionStep.multi(pinchFrames()));
        steps.add(ActionStep.key(ActionStep.KEY_BACK));
        steps.add(ActionStep.repeat(3, 500, Arrays.asList(
                ActionStep.tap(0.1f, 0.1f, 0), ActionStep.delay(100))));
        return new ActionChain("chain-1", "动作 🌟", 1000L, 2000L, 0, 1080, 2400, 2, 1500, steps);
    }

    private static void testCodecRoundTrip() {
        ActionChain original = sample();
        check(original.isValid(), "样例链有效");
        ActionChain restored = ActionChain.decode(original.encode());
        check(restored != null, "编解码往返不为空");
        check(restored.id().equals("chain-1") && restored.name().equals("动作 🌟"), "名称与 id 保留");
        check(restored.rotation() == 0 && restored.screenWidthPx() == 1080 && restored.screenHeightPx() == 2400, "屏幕信息保留");
        check(restored.repeatCount() == 2 && restored.repeatIntervalMs() == 1500, "整链重复设置保留");
        check(restored.steps().size() == original.steps().size(), "步骤数一致");
        ActionStep repeat = restored.steps().get(6);
        check(repeat.type() == ActionStep.Type.REPEAT && repeat.repeatCount() == 3
                && repeat.children().size() == 2, "重复块结构保留");
        ActionStep swipe = restored.steps().get(3);
        check(swipe.type() == ActionStep.Type.SWIPE && swipe.frames().size() == 4, "滑动帧保留");
        ActionStep multi = restored.steps().get(4);
        check(multi.type() == ActionStep.Type.MULTI && multi.frames().size() == pinchFrames().size(), "多指帧保留");
    }

    private static void testCodecRejectsBadData() {
        check(ActionChain.decode(null) == null, "null 数据返回 null");
        check(ActionChain.decode(new byte[0]) == null, "空数据返回 null");
        check(ActionChain.decode(new byte[]{9, 9, 9, 9, 1, 2, 3}) == null, "未知版本返回 null");
        byte[] good = sample().encode();
        byte[] truncated = Arrays.copyOf(good, good.length / 2);
        check(ActionChain.decode(truncated) == null, "截断数据返回 null");
    }

    // ------------------------------------------------------------------ 分段

    private static void testSegment() {
        // 点击：短时长、无位移
        GestureSegmenter.RawGesture tap = gesture(tapFrames(0.5f, 0.5f, 30), 1000, 1030, 1);
        // 长按：长时长、无位移
        GestureSegmenter.RawGesture longPress = gesture(tapFrames(0.5f, 0.5f, 700), 2000, 2700, 1);
        // 滑动：明显位移
        GestureSegmenter.RawGesture swipe = gesture(swipeAbs(), 3000, 3300, 1);
        // 多指
        GestureSegmenter.RawGesture pinch = gesture(pinchFrames(), 4000, 4300, 2);

        List<ActionStep> steps = GestureSegmenter.segment(
                Arrays.asList(tap, longPress, swipe, pinch), 500, 0.02f, 50);
        // tap, delay, longPress, delay, swipe, delay, multi
        check(steps.get(0).type() == ActionStep.Type.TAP, "短触点归为点击");
        check(steps.get(1).type() == ActionStep.Type.DELAY && steps.get(1).durationMs() == 970, "手势间空档变延迟");
        check(steps.get(2).type() == ActionStep.Type.LONG_PRESS, "长时长无位移归为长按");
        check(steps.get(4).type() == ActionStep.Type.SWIPE, "有位移归为滑动");
        check(steps.get(6).type() == ActionStep.Type.MULTI, "多指归为多指手势");

        // 小于 minDelay 的空档不生成延迟
        GestureSegmenter.RawGesture a = gesture(tapFrames(0.5f, 0.5f, 20), 0, 20, 1);
        GestureSegmenter.RawGesture b = gesture(tapFrames(0.5f, 0.5f, 20), 40, 60, 1);
        check(GestureSegmenter.segment(Arrays.asList(a, b), 500, 0.02f, 50).size() == 2, "过短空档不加延迟");
    }

    // ------------------------------------------------------------------ 展开

    private static void testFlatten() {
        List<ActionStep> steps = new ArrayList<>();
        steps.add(ActionStep.tap(0.1f, 0.1f, 0));
        steps.add(ActionStep.repeat(3, 0, Arrays.asList(ActionStep.tap(0.2f, 0.2f, 0))));
        // 整链重复 2 次：每轮 = tap + 3*tap = 4 步；2 轮 = 8 步（外加轮间延迟，间隔为 0 不产生）
        ActionChain chain = new ActionChain("c", "", 0, 0, 0, 1080, 2400, 2, 0, steps);
        List<ActionStep> flat = chain.flatten();
        check(flat.size() == 8, "展开步数 = 每轮 4 步 * 2 轮");
        for (ActionStep s : flat) check(s.type() != ActionStep.Type.REPEAT, "展开后不含重复块");

        // 轮间隔 > 0 时插入延迟
        ActionChain withGap = new ActionChain("c", "", 0, 0, 0, 1080, 2400, 2, 1000, steps);
        List<ActionStep> flatGap = withGap.flatten();
        check(flatGap.size() == 9, "两轮之间插入一个轮间延迟");
        check(flatGap.get(4).type() == ActionStep.Type.DELAY && flatGap.get(4).durationMs() == 1000, "轮间延迟为设定值");

        check(chain.totalStepCount() == 2, "顶层步数统计含重复块子步骤但不乘次数");
    }

    // ------------------------------------------------------------------ 编辑

    private static void testEditing() {
        ActionStep swipe = ActionStep.swipe(swipeFrames(300));
        check(ActionStep.gestureDuration(swipe.frames()) == 300, "原滑动时长 300");
        ActionStep slower = swipe.withSwipeDuration(600);
        check(ActionStep.gestureDuration(slower.frames()) == 600, "改滑动时长后时间轴翻倍");
        check(slower.frames().get(0).xs[0] == swipe.frames().get(0).xs[0], "缩放时长不改坐标");

        ActionStep tap = ActionStep.tap(0.5f, 0.5f, 40);
        ActionStep moved = tap.withCoords(0.8f, 0.2f);
        check(moved.x() == 0.8f && moved.y() == 0.2f && moved.durationMs() == 40, "改点击坐标保留时长");

        ActionStep repeat = ActionStep.repeat(2, 100, Arrays.asList(ActionStep.tap(0.1f, 0.1f, 0)));
        check(repeat.withRepeat(5, 200).repeatCount() == 5, "改重复次数");

        boolean rejected = false;
        try { tap.withSwipeDuration(100); } catch (IllegalStateException e) { rejected = true; }
        check(rejected, "点击不能当滑动改时长");
    }

    // ------------------------------------------------------------------ 队列

    private static void testQueue() {
        ActionChainQueue queue = new ActionChainQueue();
        check(queue.submit(new ActionChainQueue.Request("a", "task-a")) == ActionChainQueue.Result.STARTED, "首个立即开始");
        check(queue.submit(new ActionChainQueue.Request("b", "task-b")) == ActionChainQueue.Result.QUEUED, "第二个排队");
        for (int i = 0; i < ActionChainQueue.MAX_WAITING - 1; i++) {
            queue.submit(new ActionChainQueue.Request("x", "task-x"));
        }
        check(queue.submit(new ActionChainQueue.Request("z", "task-z")) == ActionChainQueue.Result.DROPPED, "超过上限丢弃");
        check(queue.running().chainId.equals("a"), "当前跑 a");
        check(queue.advance().chainId.equals("b"), "先进先出，下一个是 b");
        queue.clear();
        check(queue.running() == null && queue.waitingCount() == 0, "清空后空闲");
        check(queue.submit(new ActionChainQueue.Request("c", "task-c")) == ActionChainQueue.Result.STARTED, "清空后可重新开始");
    }

    // ------------------------------------------------------------------ 帧构造辅助

    private static GestureSegmenter.RawGesture gesture(List<GestureFrame> frames, long start, long end, int maxPointers) {
        return new GestureSegmenter.RawGesture(frames, start, end, maxPointers);
    }

    /** 单指按下-抬起，无位移，指定按住时长 */
    private static List<GestureFrame> tapFrames(float x, float y, long pressMs) {
        List<GestureFrame> frames = new ArrayList<>();
        frames.add(new GestureFrame(0, GestureFrame.ACTION_DOWN, 0, new int[]{0}, new float[]{x}, new float[]{y}));
        frames.add(new GestureFrame(pressMs, GestureFrame.ACTION_UP, 0, new int[]{0}, new float[]{x}, new float[]{y}));
        return frames;
    }

    /** 单指从左到右滑动，用于分段判定（有明显位移） */
    private static List<GestureFrame> swipeAbs() {
        List<GestureFrame> frames = new ArrayList<>();
        frames.add(new GestureFrame(0, GestureFrame.ACTION_DOWN, 0, new int[]{0}, new float[]{0.2f}, new float[]{0.5f}));
        frames.add(new GestureFrame(150, GestureFrame.ACTION_MOVE, 0, new int[]{0}, new float[]{0.5f}, new float[]{0.5f}));
        frames.add(new GestureFrame(300, GestureFrame.ACTION_UP, 0, new int[]{0}, new float[]{0.8f}, new float[]{0.5f}));
        return frames;
    }

    /** 指定总时长的 4 帧滑动，用于编解码 / 缩放 */
    private static List<GestureFrame> swipeFrames(long total) {
        List<GestureFrame> frames = new ArrayList<>();
        frames.add(new GestureFrame(0, GestureFrame.ACTION_DOWN, 0, new int[]{0}, new float[]{0.2f}, new float[]{0.5f}));
        frames.add(new GestureFrame(total / 3, GestureFrame.ACTION_MOVE, 0, new int[]{0}, new float[]{0.4f}, new float[]{0.5f}));
        frames.add(new GestureFrame(total * 2 / 3, GestureFrame.ACTION_MOVE, 0, new int[]{0}, new float[]{0.6f}, new float[]{0.5f}));
        frames.add(new GestureFrame(total, GestureFrame.ACTION_UP, 0, new int[]{0}, new float[]{0.8f}, new float[]{0.5f}));
        return frames;
    }

    /** 双指捏合：含 POINTER_DOWN/UP，用于多指判定 */
    private static List<GestureFrame> pinchFrames() {
        List<GestureFrame> frames = new ArrayList<>();
        frames.add(new GestureFrame(0, GestureFrame.ACTION_DOWN, 0, new int[]{0}, new float[]{0.3f}, new float[]{0.5f}));
        frames.add(new GestureFrame(20, GestureFrame.ACTION_POINTER_DOWN, 1, new int[]{0, 1},
                new float[]{0.3f, 0.7f}, new float[]{0.5f, 0.5f}));
        frames.add(new GestureFrame(150, GestureFrame.ACTION_MOVE, 0, new int[]{0, 1},
                new float[]{0.4f, 0.6f}, new float[]{0.5f, 0.5f}));
        frames.add(new GestureFrame(280, GestureFrame.ACTION_POINTER_UP, 1, new int[]{0, 1},
                new float[]{0.45f, 0.55f}, new float[]{0.5f, 0.5f}));
        frames.add(new GestureFrame(300, GestureFrame.ACTION_UP, 0, new int[]{0}, new float[]{0.45f}, new float[]{0.5f}));
        return frames;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
