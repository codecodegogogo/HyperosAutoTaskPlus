package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.miui.autotask.taskitem.TaskItem;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/**
 * 「光线」条件：环境光暗于 / 亮于指定照度（lux）。
 *
 * 字段随 Gson 持久化：above true = 亮于，false = 暗于；lux 阈值。
 * 状态型条件，退出条件是相反的比较方向、同一阈值。判定在 {@link SensorRuntime}。
 */
public class LightConditionItem extends TaskItem {

    private static final long serialVersionUID = 1L;

    private boolean above = false;
    private int lux = 10;

    @Override
    public String e() {
        return FirstAppKeys.KEY_LIGHT_CONDITION;
    }

    @Override
    public String h() {
        return SensorRuntime.lightTitle();
    }

    @Override
    public String g() {
        return SensorRuntime.lightSummary(this);
    }

    @Override
    public int b() {
        return SensorRuntime.lightIcon(1);
    }

    @Override
    public int c() {
        return SensorRuntime.lightIcon(0);
    }

    @Override
    public int i() {
        return SensorRuntime.lightIcon(2);
    }

    @Override
    public boolean l() {
        return lux >= 0;
    }

    @Override
    public boolean m() {
        return SensorRuntime.evaluate(this);
    }

    public boolean isAbove() {
        return above;
    }

    public int getLux() {
        return lux;
    }

    public void set(boolean aboveValue, int luxValue) {
        this.above = aboveValue;
        this.lux = Math.max(0, luxValue);
    }

    LightConditionItem opposite() {
        LightConditionItem o = new LightConditionItem();
        o.set(!above, lux);
        o.s(j());
        return o;
    }
}
