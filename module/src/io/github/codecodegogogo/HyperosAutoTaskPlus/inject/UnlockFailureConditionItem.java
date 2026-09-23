package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.miui.autotask.taskitem.TaskItem;
import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/** 配置随 TaskItem 存库；实际计数由 SystemUI 采集，不能放在单个任务里自行累加。 */
public class UnlockFailureConditionItem extends TaskItem {
    private static final long serialVersionUID = 1L;
    private int threshold = 3;
    private boolean resetCondition;

    @Override public String e() { return FirstAppKeys.KEY_UNLOCK_FAILURE_CONDITION; }
    @Override public String h() { return UnlockFailureRuntime.title(resetCondition); }
    @Override public String g() { return UnlockFailureRuntime.summary(this); }
    @Override public int b() { return UnlockFailureRuntime.icon(1); }
    @Override public int c() { return UnlockFailureRuntime.icon(0); }
    @Override public int i() { return UnlockFailureRuntime.icon(2); }
    @Override public boolean l() { return threshold >= 1 && threshold <= 999; }
    @Override public boolean m() { return UnlockFailureRuntime.evaluate(this); }

    public int getThreshold() { return threshold; }
    public boolean isResetCondition() { return resetCondition; }

    public void setThreshold(int value) {
        if (value < 1 || value > 999) throw new IllegalArgumentException("次数必须为 1～999");
        threshold = value;
    }

    UnlockFailureConditionItem opposite() {
        UnlockFailureConditionItem result = new UnlockFailureConditionItem();
        result.threshold = threshold;
        result.resetCondition = !resetCondition;
        result.s(j());
        return result;
    }
}
