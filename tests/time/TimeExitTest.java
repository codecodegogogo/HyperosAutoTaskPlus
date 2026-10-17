package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.miui.autotask.taskitem.CustomTimeConditionItem;
import com.miui.autotask.taskitem.TaskItem;
import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class TimeExitTest {
    private static final Gson GSON = new Gson();
    private static final String NATIVE = "{\"a\":\"task-1\",\"b\":0,\"c\":{\"a\":127},"
            + "\"d\":480,\"e\":540,\"f\":1080,\"g\":2,\"instanceId\":\"source\",\"taskChannel\":\"sc\"}";
    private static int checks;

    public static void main(String[] args) throws Exception {
        migrationAndDeletion();
        persistenceAndEdits();
        unrelatedAndPointConditions();
        nativeTimingAndRestoration();
        System.out.println("Time exit checks passed: " + checks);
    }

    private static void migrationAndDeletion() {
        List<TaskItem> sources = new ArrayList<>(Collections.singletonList(GSON.fromJson(NATIVE, CustomTimeConditionItem.class)));
        FlagCondition other = new FlagCondition("other", true);
        FlagCondition add = new FlagCondition("key_condition_list", false);
        List<TaskItem> exits = new ArrayList<>(Arrays.asList(other, add));
        check(TimeExitRuntime.allowsTimeExit(sources, exits, false), "unmigrated time range retains native end exit");
        check(TimeExitRuntime.reconcile(sources, exits, false) == 3, "migration requests a visible enabled time exit");
        check(sources.get(0) instanceof ManagedTimeConditionItem, "native trigger is upgraded in place in the editor list");
        check(((ManagedTimeConditionItem) sources.get(0)).isTimeExitManaged(), "explicit-exit policy persists on trigger");
        check(exits.size() == 3 && exits.get(2) == add, "time exit is inserted before the add row");
        check(!other.k(), "migration must not enable exits disabled by the old global setting");
        TimePeriodExitConditionItem end = (TimePeriodExitConditionItem) exits.get(1);
        check(end.k() && "task-1".equals(end.j()), "generated exit is checked and owns the same task");
        check(end.matches(540, 1080) && end.g().contains("18:00"), "exit displays the actual end time");
        check(TimeExitRuntime.reconcile(sources, exits, true) == 0 && exits.size() == 3, "rebind cannot duplicate an exit");
        check(TimeExitRuntime.allowsTimeExit(sources, exits, true), "checked time exit permits the native alarm");
        end.p(false);
        check(!TimeExitRuntime.allowsTimeExit(sources, exits, true), "unchecked time exit blocks the alarm");
        end.p(true);
        check(!TimeExitRuntime.allowsTimeExit(sources, exits, false), "global exit switch also controls time exit");
        exits.remove(end);
        other.p(true);
        check(TimeExitRuntime.reconcile(sources, exits, true) == 0, "deleting the exit does not regenerate it");
        check(!TimeExitRuntime.allowsTimeExit(sources, exits, true), "deleted time exit rejects already-scheduled alarms");
        check(other.k() && other.m(), "other checked exit can still trigger independently");
        check(!TimeExitRuntime.hasImplicitTimeExit(sources), "managed triggers never silently regain implicit exit");
        JsonObject copied = GSON.toJsonTree(sources.get(0)).getAsJsonObject();
        copied.remove("timeExitManaged");
        check(copied.equals(GSON.fromJson(NATIVE, JsonObject.class)), "migration preserves every native timing/repeat/identity field");
    }

    private static void persistenceAndEdits() throws Exception {
        List<TaskItem> sources = sources();
        List<TaskItem> exits = new ArrayList<>();
        TimeExitRuntime.reconcile(sources, exits, false);
        ManagedTimeConditionItem original = (ManagedTimeConditionItem) sources.get(0);
        TimePeriodExitConditionItem end = (TimePeriodExitConditionItem) exits.get(0);
        end.p(false);
        ManagedTimeConditionItem edited = serialized(original);
        edited.A(1140);
        sources.set(0, edited);
        check(TimeExitRuntime.reconcile(sources, exits, true) == 1, "editing trigger end updates its existing exit");
        check(end.matches(540, 1140) && !end.k(), "time sync preserves the checkbox state");
        end.p(true);
        check(TimeExitRuntime.allowsTimeExit(sources, exits, true), "edited window drives end-alarm policy");
        TimePeriodExitConditionItem restoredEnd = GSON.fromJson(GSON.toJson(end), TimePeriodExitConditionItem.class);
        check(restoredEnd.k() && restoredEnd.matches(540, 1140), "exit payload round-trips through host Gson");
        exits.clear();
        ManagedTimeConditionItem reopened = GSON.fromJson(GSON.toJson(edited), ManagedTimeConditionItem.class);
        sources.set(0, reopened);
        check(TimeExitRuntime.reconcile(sources, exits, true) == 0 && exits.isEmpty(), "saving and reopening preserves deletion");
        reopened.A(1200);
        check(TimeExitRuntime.reconcile(sources, exits, true) == 0 && exits.isEmpty(), "editing an end time cannot undo deletion");
        sources.set(0, GSON.fromJson(NATIVE, ManagedTimeConditionItem.class));
        check(TimeExitRuntime.reconcile(sources, exits, true) == 3, "removing and re-adding a time trigger creates a fresh exit");
        ((CustomTimeConditionItem) sources.get(0)).F(1);
        check(TimeExitRuntime.reconcile(sources, exits, true) == 1 && exits.isEmpty(), "switching to a time point removes the end event");
        ((CustomTimeConditionItem) sources.get(0)).F(2);
        check(TimeExitRuntime.reconcile(sources, exits, true) == 3, "switching back to a range creates its end event");
        sources.clear();
        check(TimeExitRuntime.reconcile(sources, exits, true) == 1 && exits.isEmpty(), "removing time trigger cleans up its exit");
    }

    private static void unrelatedAndPointConditions() {
        List<TaskItem> sources = new ArrayList<>();
        FlagCondition other = new FlagCondition("battery", true);
        List<TaskItem> exits = new ArrayList<>(Collections.singletonList(other));
        check(TimeExitRuntime.reconcile(sources, exits, false) == 0 && other.k(), "non-time tasks stay unchanged");
        ManagedTimeConditionItem point = GSON.fromJson(NATIVE, ManagedTimeConditionItem.class);
        point.F(1);
        sources.add(point);
        check(TimeExitRuntime.reconcile(sources, exits, true) == 0 && exits.size() == 1, "point-time trigger has no invented end exit");
        check(!TimeExitRuntime.hasTimeRange(sources) && !TimeExitRuntime.allowsTimeExit(sources, exits, true),
                "point-time trigger is not treated as a period");
        check(TimeExitRuntime.itemClass(FirstAppKeys.KEY_CUSTOM_TIME_CONDITION) == ManagedTimeConditionItem.class,
                "stored native key resolves to compatible subclass");
        check(TimeExitRuntime.itemClass(FirstAppKeys.KEY_TIME_PERIOD_EXIT) == TimePeriodExitConditionItem.class,
                "generated exit can be restored by the host factory");
        check(TimeExitRuntime.itemClass("unknown") == null && TimeExitRuntime.newItem("unknown") == null,
                "factory does not intercept unrelated keys");
    }

    private static void nativeTimingAndRestoration() throws Exception {
        List<TaskItem> sources = sources();
        List<TaskItem> exits = new ArrayList<>();
        TimeExitRuntime.reconcile(sources, exits, false);
        ManagedTimeConditionItem time = (ManagedTimeConditionItem) sources.get(0);
        TimePeriodExitConditionItem end = (TimePeriodExitConditionItem) exits.get(0);
        time.E(1380);
        time.A(420);
        TimeExitRuntime.reconcile(sources, exits, true);
        check(end.matches(1380, 420) && end.g().contains("07:00"), "cross-midnight end time remains native");
        check(!end.m(), "time exit is not evaluated as another normal time trigger");
        CustomTimeConditionItem.nativeMatches = false;
        check(!time.m(), "managed trigger preserves native outside-window result");
        CustomTimeConditionItem.nativeMatches = true;
        check(time.m(), "managed trigger preserves native inside-window result");
        check(TimeExitRuntime.hasTimeRange(sources), "delayed-execution recovery recognizes the time subclass");
        Method restore = ThenRuntime.class.getDeclaredMethod("expectsRestore", Object.class);
        restore.setAccessible(true);
        check((Boolean) restore.invoke(null, new Bean(sources, true)), "other explicit exits retain delayed-action undo snapshots");
        exits.clear();
        check(!(Boolean) restore.invoke(null, new Bean(sources, false)), "no exits means no hidden delayed-action restoration");
        List<TaskItem> legacy = new ArrayList<>(Collections.singletonList(GSON.fromJson(NATIVE, CustomTimeConditionItem.class)));
        check((Boolean) restore.invoke(null, new Bean(legacy, false)), "legacy time tasks retain delayed-action restore behavior");
        end.setWindow(540, 1080);
        exits.add(end);
        check(!TimeExitRuntime.allowsTimeExit(sources, exits, true), "stale end-window payload cannot authorize an alarm");
    }

    private static List<TaskItem> sources() {
        return new ArrayList<>(Collections.singletonList(GSON.fromJson(NATIVE, ManagedTimeConditionItem.class)));
    }

    @SuppressWarnings("unchecked")
    private static <T> T serialized(T item) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(item); }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }

    public static final class Bean {
        private final List<TaskItem> conditions;
        private final boolean exitEnabled;
        Bean(List<TaskItem> conditions, boolean exitEnabled) { this.conditions = conditions; this.exitEnabled = exitEnabled; }
        public List<TaskItem> b() { return conditions; }
        public boolean p() { return exitEnabled; }
    }

    private static final class FlagCondition extends TaskItem {
        private final String key;
        FlagCondition(String key, boolean checked) { this.key = key; p(checked); }
        @Override public String e() { return key; }
        @Override public String h() { return key; }
        @Override public String g() { return key; }
        @Override public boolean l() { return true; }
        @Override public boolean m() { return true; }
        @Override public int b() { return 0; }
        @Override public int c() { return 0; }
        @Override public int i() { return 0; }
    }
}
