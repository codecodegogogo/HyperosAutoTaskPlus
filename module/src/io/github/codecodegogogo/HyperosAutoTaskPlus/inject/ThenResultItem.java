package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.miui.autotask.taskitem.TaskItem;
import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A versioned configuration container; pending callbacks never enter persistence. */
public class ThenResultItem extends TaskItem {
    private static final long serialVersionUID = 1L;
    public static final int SCHEMA_VERSION = 1;

    private Integer schemaVersion = SCHEMA_VERSION;
    private List<ThenGroup> groups = new ArrayList<>();

    public ThenResultItem() {}

    public ThenResultItem(String key) {
        if (!FirstAppKeys.KEY_THEN_EXECUTION_RESULT.equals(key)) throw new IllegalArgumentException("未知结果");
    }

    @Override public String e() { return FirstAppKeys.KEY_THEN_EXECUTION_RESULT; }
    @Override public String h() { return ThenRuntime.title(); }
    @Override public String g() { return ThenRuntime.summary(this); }
    @Override public int b() { return ThenRuntime.icon(1); }
    @Override public int c() { return ThenRuntime.icon(0); }
    @Override public int i() { return ThenRuntime.icon(2); }

    @Override public boolean l() {
        if (!isSupportedVersion() || groups == null || groups.isEmpty()) return false;
        Set<String> ids = new HashSet<>();
        long totalDelay = 0;
        for (ThenGroup group : groups) {
            if (group == null || !group.isValid() || !ids.add(group.getId())) return false;
            long delay = group.delayMillis();
            if (Long.MAX_VALUE - totalDelay < delay) return false;
            totalDelay += delay;
        }
        return true;
    }

    @Override public void n() { ThenRuntime.execute(this); }
    @Override public void o() { ThenRuntime.restore(this); }

    public boolean isSupportedVersion() { return schemaVersion != null && schemaVersion == SCHEMA_VERSION; }
    public boolean hasGroups() { return groups != null && !groups.isEmpty(); }

    public List<ThenGroup> getGroups() {
        return groups == null ? new ArrayList<>() : copyGroups(groups);
    }

    public void setGroups(List<ThenGroup> values) { groups = copyGroups(values); }

    public ThenResultItem copy() {
        ThenResultItem result = new ThenResultItem();
        result.schemaVersion = schemaVersion;
        result.groups = copyGroups(groups);
        // Preserve host-owned UUID/instanceId/channel fields without invoking stub methods.
        try {
            for (Field field : TaskItem.class.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) continue;
                field.setAccessible(true);
                field.set(result, field.get(this));
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("无法复制任务标识", error);
        }
        return result;
    }

    private static List<ThenGroup> copyGroups(List<ThenGroup> values) {
        if (values == null) return null;
        List<ThenGroup> result = new ArrayList<>(values.size());
        for (ThenGroup group : values) result.add(group == null ? null : group.copy());
        return result;
    }
}
