package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One delay followed by an ordered set of native actions. */
public final class ThenGroup implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int UNIT_SECONDS = 0;
    public static final int UNIT_MINUTES = 1;
    public static final int MAX_DELAY_AMOUNT = 999_999_999;

    private String id;
    // Boxed values keep an explicit persisted null invalid instead of restoring defaults.
    private Integer delayAmount = 1;
    private Integer delayUnit = UNIT_SECONDS;
    // Boxed on purpose: null（旧版本没有这个字段）视为启用，只有显式 false 才停用本组。
    private Boolean enabled = Boolean.TRUE;
    private List<ThenAction> actions = new ArrayList<>();

    public ThenGroup() { id = UUID.randomUUID().toString(); }

    private ThenGroup(ThenGroup source) {
        id = source.id;
        delayAmount = source.delayAmount;
        delayUnit = source.delayUnit;
        enabled = source.enabled;
        actions = copyActions(source.actions);
    }

    public String getId() { return id == null ? "" : id; }
    public int getDelayAmount() { return delayAmount == null ? 0 : delayAmount; }
    public int getDelayUnit() { return delayUnit == null ? -1 : delayUnit; }

    /** 停用的分组保留在配置里但不参与执行；null（旧数据）视为启用。 */
    public boolean isEnabled() { return enabled == null || enabled; }

    public void setEnabled(boolean value) { enabled = value; }

    public void setDelay(int amount, int unit) {
        if (!validDelay(amount, unit)) throw new IllegalArgumentException("延迟请输入 1 至 999999999 秒或分钟");
        delayAmount = amount;
        delayUnit = unit;
    }

    public long delayMillis() {
        if (!hasValidDelay()) throw new IllegalStateException("延迟配置无效");
        return (long) delayAmount * (delayUnit == UNIT_MINUTES ? 60_000L : 1_000L);
    }

    public List<ThenAction> getActions() {
        return actions == null ? new ArrayList<>() : copyActions(actions);
    }

    /** Drafts may be empty or incomplete; committing/executing must check isValid(). */
    public void setActions(List<ThenAction> values) { actions = copyActions(values); }

    public ThenGroup copy() { return new ThenGroup(this); }

    /** 停用的分组只需要有效延迟；启用中的分组还必须有完整可用的执行项目。 */
    public boolean isValid() {
        if (id == null || id.trim().isEmpty() || !hasValidDelay()) return false;
        if (!isEnabled()) return true;
        if (actions == null || actions.isEmpty()) return false;
        for (ThenAction action : actions) if (action == null || !action.isValid()) return false;
        return true;
    }

    private boolean hasValidDelay() {
        return delayAmount != null && delayUnit != null && validDelay(delayAmount, delayUnit);
    }

    private static boolean validDelay(int amount, int unit) {
        return amount >= 1 && amount <= MAX_DELAY_AMOUNT && (unit == UNIT_SECONDS || unit == UNIT_MINUTES);
    }

    private static List<ThenAction> copyActions(List<ThenAction> values) {
        if (values == null) return null;
        List<ThenAction> result = new ArrayList<>(values.size());
        for (ThenAction action : values) result.add(action == null ? null : action.copy());
        return result;
    }
}
