package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.miui.autotask.taskitem.TaskItem;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/**
 * 「时间间隔」条件：每隔 N 秒 / 分钟 / 小时 / 天触发一次。
 *
 * 字段随 Gson 持久化：amount 数值，unit 单位下标（0 秒、1 分钟、2 小时、3 天）。
 * 计时在 {@link IntervalRuntime}：任务启用后按间隔反复设精确闹钟，到点让引擎判定；
 * m() 只在刚到点的几秒钟窗口内为 true，这样引擎因为其它条件变化顺带判定时不会误触发。
 *
 * 引擎自带 20 秒内连续执行 5 次就弹「重复提醒」并拦截的保护，所以最小间隔定为 5 秒。
 * 不能作为退出条件（对「退出时恢复」返回 null，添加退出条件页里也置灰）。
 */
public class IntervalConditionItem extends TaskItem {

    private static final long serialVersionUID = 1L;

    public static final int UNIT_SECOND = 0;
    public static final int UNIT_DAY = 3;
    public static final long MIN_INTERVAL_MILLIS = 5_000L;

    private static final long[] UNIT_MILLIS = {1000L, 60_000L, 3_600_000L, 86_400_000L};

    private int amount = 30;
    private int unit = 1;

    @Override
    public String e() {
        return FirstAppKeys.KEY_INTERVAL_CONDITION;
    }

    @Override
    public String h() {
        return IntervalRuntime.title();
    }

    @Override
    public String g() {
        return IntervalRuntime.summary(this);
    }

    @Override
    public int b() {
        return IntervalRuntime.icon(1);
    }

    @Override
    public int c() {
        return IntervalRuntime.icon(0);
    }

    @Override
    public int i() {
        return IntervalRuntime.icon(2);
    }

    @Override
    public boolean l() {
        return amount > 0 && unit >= UNIT_SECOND && unit <= UNIT_DAY;
    }

    @Override
    public boolean m() {
        return IntervalRuntime.evaluate(this);
    }

    public int getAmount() {
        return amount;
    }

    public int getUnit() {
        return unit;
    }

    public void set(int amountValue, int unitIndex) {
        this.amount = Math.max(1, amountValue);
        this.unit = Math.min(Math.max(unitIndex, UNIT_SECOND), UNIT_DAY);
    }

    public long intervalMillis() {
        int u = Math.min(Math.max(unit, UNIT_SECOND), UNIT_DAY);
        return Math.max(MIN_INTERVAL_MILLIS, Math.max(1, amount) * UNIT_MILLIS[u]);
    }
}
