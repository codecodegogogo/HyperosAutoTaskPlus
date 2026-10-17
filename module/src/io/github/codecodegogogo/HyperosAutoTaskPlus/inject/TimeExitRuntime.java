package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.miui.autotask.taskitem.CustomTimeConditionItem;
import com.miui.autotask.taskitem.TaskItem;
import java.util.List;
import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/** Editor migration and end-alarm policy. Native start-time evaluation stays untouched. */
public final class TimeExitRuntime {
    private TimeExitRuntime() {}

    public static Class<?> itemClass(String key) {
        if (FirstAppKeys.KEY_CUSTOM_TIME_CONDITION.equals(key)) return ManagedTimeConditionItem.class;
        return FirstAppKeys.KEY_TIME_PERIOD_EXIT.equals(key) ? TimePeriodExitConditionItem.class : null;
    }

    public static Object newItem(String key) {
        if (FirstAppKeys.KEY_CUSTOM_TIME_CONDITION.equals(key)) return new ManagedTimeConditionItem();
        return FirstAppKeys.KEY_TIME_PERIOD_EXIT.equals(key) ? new TimePeriodExitConditionItem() : null;
    }

    /** bit 0: refresh lists; bit 1: expose/enable the newly migrated time exit. */
    public static int reconcile(List<TaskItem> conditions, List<TaskItem> exits, boolean exitsEnabled) {
        CustomTimeConditionItem time = findTime(conditions);
        if (time == null || time.z() != 2) {
            boolean changed = exits.removeIf(item -> item instanceof TimePeriodExitConditionItem);
            if (time instanceof ManagedTimeConditionItem && ((ManagedTimeConditionItem) time).isTimeExitManaged()) {
                ManagedTimeConditionItem copy = managedCopy(time);
                copy.setTimeExitManaged(false);
                conditions.set(conditions.indexOf(time), copy);
                changed = true;
            }
            return changed ? 1 : 0;
        }
        int changes = 0;
        boolean initialize = !(time instanceof ManagedTimeConditionItem)
                || !((ManagedTimeConditionItem) time).isTimeExitManaged();
        if (initialize) {
            ManagedTimeConditionItem copy = managedCopy(time);
            copy.setTimeExitManaged(true);
            conditions.set(conditions.indexOf(time), copy);
            time = copy;
            changes = 1;
        }
        TimePeriodExitConditionItem end = null;
        for (TaskItem item : exits) {
            if (item instanceof TimePeriodExitConditionItem) {
                end = (TimePeriodExitConditionItem) item;
                break;
            }
        }
        if (end == null && initialize) {
            // Enabling the formerly implicit time exit must not enable unrelated exits.
            if (!exitsEnabled) for (TaskItem item : exits) item.p(false);
            end = new TimePeriodExitConditionItem();
            end.p(true);
            end.s(time.j());
            int at = exits.size();
            for (int i = 0; i < exits.size(); i++) {
                if ("key_condition_list".equals(exits.get(i).e())) { at = i; break; }
            }
            exits.add(at, end);
            changes |= 3;
        }
        if (end != null && end.setWindow(time.y(), time.u())) changes |= 1;
        return changes;
    }

    private static ManagedTimeConditionItem managedCopy(CustomTimeConditionItem item) {
        return ThenActionCodec.readValue(ThenActionCodec.writeValue(item), ManagedTimeConditionItem.class);
    }

    public static boolean isManagedPeriod(Object value) {
        return value instanceof ManagedTimeConditionItem && ((ManagedTimeConditionItem) value).z() == 2
                && ((ManagedTimeConditionItem) value).isTimeExitManaged();
    }

    public static boolean hasManagedPeriod(List<?> conditions) { return isManagedPeriod(findTime(conditions)); }
    public static boolean hasTimeRange(List<?> conditions) {
        CustomTimeConditionItem time = findTime(conditions);
        return time != null && time.z() == 2;
    }
    public static boolean hasImplicitTimeExit(List<?> conditions) {
        return hasTimeRange(conditions) && !hasManagedPeriod(conditions);
    }

    public static boolean allowsTimeExit(List<?> conditions, List<?> exits, boolean enabled) {
        CustomTimeConditionItem time = findTime(conditions);
        if (time == null || time.z() != 2) return false;
        if (!isManagedPeriod(time)) return true; // Preserve tasks not yet edited with this feature.
        if (!enabled) return false;
        for (Object item : exits) {
            if (item instanceof TimePeriodExitConditionItem) {
                TimePeriodExitConditionItem end = (TimePeriodExitConditionItem) item;
                if (end.k() && end.matches(time.y(), time.u())) return true;
            }
        }
        return false;
    }

    private static CustomTimeConditionItem findTime(List<?> conditions) {
        for (Object item : conditions) if (item instanceof CustomTimeConditionItem) return (CustomTimeConditionItem) item;
        return null;
    }
}
