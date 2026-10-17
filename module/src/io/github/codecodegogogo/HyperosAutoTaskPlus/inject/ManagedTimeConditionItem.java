package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.miui.autotask.taskitem.CustomTimeConditionItem;

/** Retains native time semantics; the extra field remembers an explicitly deleted exit. */
public class ManagedTimeConditionItem extends CustomTimeConditionItem {
    private static final long serialVersionUID = 1L;
    private boolean timeExitManaged;

    public boolean isTimeExitManaged() { return timeExitManaged; }
    public void setTimeExitManaged(boolean value) { timeExitManaged = value; }
}
