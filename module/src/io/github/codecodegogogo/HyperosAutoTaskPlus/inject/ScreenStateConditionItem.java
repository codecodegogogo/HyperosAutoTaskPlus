package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.miui.autotask.taskitem.TaskItem;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/**
 * 「屏幕状态」条件：亮屏 / 息屏持续了指定时长后触发。
 *
 * 三个字段随 Gson 一起持久化（字段名即 json 键）：
 *   screenOn  true = 亮屏时间，false = 息屏时间
 *   amount    时长数值，0 表示「一进入该状态就触发」
 *   unit      时长单位下标：0 秒、1 分钟、2 小时、3 天
 *
 * 判定和计时在 {@link ScreenStateRuntime}：它监听亮/灭屏广播记录状态切换时刻，
 * 到点后让引擎重新判定；m() 只看「当前状态是否匹配且已持续足够久」。
 * 作为退出条件时（勾选「退出时恢复」），生成的是相反状态、时长 0 的副本，
 * 即「息屏 10 分钟 → 亮屏时恢复」。
 *
 * 这个类会通过 addDexPath 注入到安全中心的 ClassLoader 里加载，
 * 所以可以直接 extends 安全中心的类；反过来它不能引用任何 Xposed API。
 */
public class ScreenStateConditionItem extends TaskItem {

    private static final long serialVersionUID = 1L;

    public static final int UNIT_SECOND = 0;
    public static final int UNIT_MINUTE = 1;
    public static final int UNIT_HOUR = 2;
    public static final int UNIT_DAY = 3;

    private static final long[] UNIT_MILLIS = {1000L, 60_000L, 3_600_000L, 86_400_000L};

    private boolean screenOn = true;
    private int amount = 5;
    private int unit = UNIT_MINUTE;

    @Override
    public String e() {
        return FirstAppKeys.KEY_SCREEN_STATE_CONDITION;
    }

    @Override
    public String h() {
        return ScreenStateRuntime.title();
    }

    @Override
    public String g() {
        return ScreenStateRuntime.summary(this);
    }

    // 借用原生「锁屏」条件的三态图标，主题相近
    @Override
    public int b() {
        return ScreenStateRuntime.icon(1);
    }

    @Override
    public int c() {
        return ScreenStateRuntime.icon(0);
    }

    @Override
    public int i() {
        return ScreenStateRuntime.icon(2);
    }

    @Override
    public boolean l() {
        return amount >= 0 && unit >= UNIT_SECOND && unit <= UNIT_DAY;
    }

    @Override
    public boolean m() {
        return ScreenStateRuntime.evaluate(this);
    }

    // ---- 取值 / 赋值 ----

    public boolean isScreenOn() {
        return screenOn;
    }

    public int getAmount() {
        return amount;
    }

    public int getUnit() {
        return unit;
    }

    public void set(boolean on, int amountValue, int unitIndex) {
        this.screenOn = on;
        this.amount = Math.max(0, amountValue);
        this.unit = Math.min(Math.max(unitIndex, UNIT_SECOND), UNIT_DAY);
    }

    /** 需要持续的时长，毫秒 */
    public long durationMillis() {
        int u = Math.min(Math.max(unit, UNIT_SECOND), UNIT_DAY);
        return Math.max(0, amount) * UNIT_MILLIS[u];
    }

    /** 退出条件：相反状态、时长 0，uuid 相同 */
    ScreenStateConditionItem opposite() {
        ScreenStateConditionItem o = new ScreenStateConditionItem();
        o.set(!screenOn, 0, UNIT_SECOND);
        o.s(j());
        return o;
    }
}
