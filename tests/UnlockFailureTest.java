package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.google.gson.Gson;
import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;
import io.github.codecodegogogo.HyperosAutoTaskPlus.UnlockFailureState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;

/** 连续失败、阈值边界、用户隔离及条件序列化回归；不调用真实认证或系统设置。 */
public final class UnlockFailureTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Gson gson = new Gson();
        UnlockFailureConditionItem item = new UnlockFailureConditionItem();
        check(item.getThreshold() == 3 && item.l(), "默认连续失败三次");
        check(!item.isResetCondition(), "默认是触发条件");
        check(UnlockFailureRuntime.itemClass(FirstAppKeys.KEY_UNLOCK_FAILURE_CONDITION) == UnlockFailureConditionItem.class, "原生工厂可反序列化");
        check(UnlockFailureRuntime.newItem(FirstAppKeys.KEY_UNLOCK_FAILURE_CONDITION) instanceof UnlockFailureConditionItem, "原生工厂可创建条件");
        check(UnlockFailureRuntime.newItem("unknown") == null && UnlockFailureRuntime.itemClass(null) == null, "不拦截其它条件");

        UnlockFailureState state = new UnlockFailureState(0, 0);
        check(!state.matches(3, false) && !state.matches(3, true), "初始零次不代表成功解锁");
        // 三种认证方式共用一个状态，连续快速的事件也不能被时间防抖丢弃。
        for (int i = 1; i <= 3; i++) {
            state = state.failed();
            check(state.count == i, "每个真实失败增加一次");
            check(UnlockFailureRuntime.matches(state, item) == (i == 3), "第三次即达到阈值");
        }
        UnlockFailureState fourth = state.failed();
        check(fourth.count == 4, "达到阈值后仍继续累计");
        check(state.matches(3, false) == fourth.matches(3, false), "第四次不产生新的阈值变化");
        check(!fourth.matches(3, true), "失败期间不恢复");
        state = fourth.unlocked();
        check(state.count == 0 && state.unlockSerial == 1, "成功解锁后清零");
        check(!state.matches(3, false) && state.matches(3, true), "清零使触发条件退出、恢复条件满足");
        check(state.failed().count == 1, "下一轮从第一次计数");
        check(!state.failed().matches(3, true), "新一轮失败不能沿用上次成功状态");

        check(UnlockFailureState.shouldCount(true, 0, 0), "当前设备用户锁屏时计数");
        check(!UnlockFailureState.shouldCount(false, 0, 0), "设备已解锁时不计数");
        check(!UnlockFailureState.shouldCount(true, 0, 10), "工作资料或其它用户不混计");
        check(!UnlockFailureState.shouldCount(true, -1, -1), "无法确定用户时不计数");
        check(UnlockFailureState.shouldCount(true, 10, 10), "切换到第二用户后统计该用户");

        state = new UnlockFailureState(7, 12);
        UnlockFailureState persisted = UnlockFailureState.decode(state.encode());
        check(persisted != null && persisted.count == 7 && persisted.unlockSerial == 12, "进程重启保留连续次数");
        String[] invalid = {null, "", "1", "1|1", "2|1|1", "1|-1|0", "1|0|-1", "1|x|0", "1|1|0|extra", "1|2147483648|0"};
        for (String value : invalid) check(UnlockFailureState.decode(value) == null, "未知/损坏状态不可用于判断");
        check(new UnlockFailureState(Integer.MAX_VALUE, Long.MAX_VALUE).failed().count == Integer.MAX_VALUE, "次数饱和而不溢出");
        check(new UnlockFailureState(8, Long.MAX_VALUE).unlocked().unlockSerial == Long.MAX_VALUE, "成功序号饱和而不溢出");
        check(!UnlockFailureRuntime.matches(null, item), "尚未启用 SystemUI 时条件不满足");

        for (int threshold : new int[]{1, 3, 10, 999}) {
            item.setThreshold(threshold);
            check(item.l(), "允许阈值 " + threshold);
            check(!new UnlockFailureState(threshold - 1, 0).matches(threshold, false), "边界前不满足");
            check(new UnlockFailureState(threshold, 0).matches(threshold, false), "边界处满足");
            UnlockFailureConditionItem copy = gson.fromJson(gson.toJson(item), UnlockFailureConditionItem.class);
            check(copy.getThreshold() == threshold && !copy.isResetCondition(), "Gson 保存用户选择");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(buffer)) { output.writeObject(item); }
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                check(((UnlockFailureConditionItem) input.readObject()).getThreshold() == threshold, "页面传参保留用户选择");
            }
        }
        for (int invalidThreshold : new int[]{0, -1, 1000, Integer.MAX_VALUE}) {
            boolean rejected = false;
            try { item.setThreshold(invalidThreshold); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "不保存无效阈值");
            UnlockFailureConditionItem malformed = gson.fromJson("{\"threshold\":" + invalidThreshold + "}", UnlockFailureConditionItem.class);
            check(!malformed.l() && !UnlockFailureRuntime.matches(new UnlockFailureState(9999, 1), malformed), "损坏配置不触发");
        }
        UnlockFailureConditionItem exit = gson.fromJson("{\"threshold\":3,\"resetCondition\":true}", UnlockFailureConditionItem.class);
        check(!UnlockFailureRuntime.matches(new UnlockFailureState(0, 0), exit), "新安装不能误报一次解锁");
        check(UnlockFailureRuntime.matches(new UnlockFailureState(0, 1), exit), "成功解锁满足退出条件");
        check(!UnlockFailureRuntime.matches(new UnlockFailureState(1, 1), exit), "已有失败不满足退出条件");
        item.setThreshold(7);
        check(UnlockFailureRuntime.syncOpposite(item, Arrays.asList("unrelated", exit)) == 1, "编辑时同步对应退出条件");
        check(exit.getThreshold() == 7 && exit.isResetCondition(), "同步保留退出条件语义");
        check(gson.fromJson(gson.toJson(exit), UnlockFailureConditionItem.class).isResetCondition(), "退出条件持久化保留");
        System.out.println("Unlock failure checks passed: " + checks);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
