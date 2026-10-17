package io.github.codecodegogogo.HyperosAutoTaskPlus;

import com.google.gson.Gson;
import com.miui.autotask.taskitem.AddressTaskItem;
import com.miui.autotask.taskitem.CustomTimeConditionItem;
import com.miui.autotask.taskitem.TaskItem;
import de.robv.android.xposed.XposedBridge;
import io.github.codecodegogogo.HyperosAutoTaskPlus.inject.ManagedTimeConditionItem;
import io.github.codecodegogogo.HyperosAutoTaskPlus.inject.TimeExitRuntime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TimeExitEngineTest {
    private static int checks;

    public static void main(String[] args) {
        TimeExitEngineHook.install(TimeExitEngineTest.class.getClassLoader());
        deletedExitAndRecovery();
        selectedAndLegacyExits();
        System.out.println("Time exit engine hook checks passed: " + checks);
    }

    private static void deletedExitAndRecovery() {
        Bean task = new Bean(true);
        task.exits.clear();
        Event otherExit = new Event(true);
        task.exits.add(otherExit);
        Engine engine = new Engine();
        engine.q.put(task.n(), task);
        engine.t(task.time());
        check(engine.endAlarms == 0, "deleted time row prevents scheduling its end alarm");
        engine.k0(task.n());
        check(task.state == 2 && engine.resets == 0, "stale end alarm cannot exit the task");
        engine.expired = true;
        engine.u(task, false);
        check(task.state == 2, "restart after the old deadline retains active task state");
        check(engine.registered.contains(otherExit), "remaining exit listeners are restored");
        check("task/-".equals(otherExit.j()), "restored exit events route to the exit dispatcher");
        check(!engine.registered.contains(task.trigger), "restart cannot replay a currently true normal trigger");
        check(engine.N.updates == 1, "host observers are refreshed after re-registering exits");
        engine.otherExit(otherExit);
        check(task.state == 0 && engine.resets == 1, "remaining exit condition can restore the task after deadline");
        check(engine.registered.contains(task.trigger) && engine.startAlarms == 1,
                "next cycle re-registers normal triggers and retains its native start alarm");

        Bean disabled = new Bean(true);
        disabled.enabled = false;
        engine.q.put(disabled.n(), disabled);
        engine.t(disabled.time());
        engine.k0(disabled.n());
        check(engine.endAlarms == 0 && engine.resets == 1, "disabled task cannot exit through an obsolete end alarm");

        Bean updated = new Bean(true);
        updated.exits.clear();
        updated.exits.add(new Event(true));
        Engine updating = new Engine();
        updating.u(updated, true);
        check(updated.state == 0 && updating.registered.contains(updated.trigger), "editing retains native reset/re-registration behavior");

        Bean removed = new Bean(true);
        removed.exits.clear();
        Engine removal = new Engine();
        removal.u(removed, false);
        removal.b1(removed.n(), removed.conditions);
        removed.state = 0;
        removal.s(removed.time());
        check(removal.registered.isEmpty(), "unregister removes deferred triggers instead of resurrecting a deleted task");
    }

    private static void selectedAndLegacyExits() {
        Bean task = new Bean(true);
        Engine engine = new Engine();
        engine.u(task, false);
        check(engine.endAlarms == 1, "selected time exit preserves native end scheduling");
        check(task.state == 2, "active selected-time task survives native recovery");
        task.exits.get(0).p(false);
        engine.k0(task.n());
        check(engine.resets == 0, "unchecking the row blocks an already-pending alarm");
        task.exits.get(0).p(true);
        task.exitEnabled = false;
        engine.k0(task.n());
        check(engine.resets == 0, "global exit setting gates the pending alarm");
        task.exitEnabled = true;
        engine.k0(task.n());
        check(engine.resets == 1, "selected end condition still restores normally");

        Bean legacy = new Bean(false);
        legacy.exitEnabled = false;
        Engine old = new Engine();
        old.u(legacy, false);
        old.k0(legacy.n());
        check(old.endAlarms == 1 && old.resets == 1, "unmigrated tasks keep their legacy implicit time exit");
        check(old.registered.isEmpty(), "legacy task recovery is not otherwise altered");
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
        checks++;
    }

    public static final class Factory {
        public static String v(String uuid) { return uuid + "/-"; }
    }

    public static final class Observer {
        int updates;
        public void a(boolean value) { updates++; }
    }

    public static final class Bean {
        final List<TaskItem> conditions = new ArrayList<>();
        final List<TaskItem> exits = new ArrayList<>();
        final Event trigger = new Event(false);
        boolean enabled = true;
        boolean exitEnabled = true;
        int state = 2;
        Bean(boolean managed) {
            CustomTimeConditionItem time = new Gson().fromJson(
                    "{\"a\":\"task\",\"g\":2,\"e\":540,\"f\":1080,\"c\":{\"a\":127}}",
                    managed ? ManagedTimeConditionItem.class : CustomTimeConditionItem.class);
            trigger.s("task");
            conditions.add(time);
            conditions.add(trigger);
            if (managed) TimeExitRuntime.reconcile(conditions, exits, false);
        }
        CustomTimeConditionItem time() { return (CustomTimeConditionItem) conditions.get(0); }
        public String n() { return "task"; }
        public List<TaskItem> b() { return conditions; }
        public List<TaskItem> e() { return exits; }
        public boolean o() { return enabled; }
        public boolean p() { return exitEnabled; }
        public boolean q() { return state == 2; }
        public int l() { return state; }
    }

    /** Mirrors only the native paths verified in check_time_exit_hosts.py. */
    public static final class Engine {
        public final Map<String, Bean> q = new HashMap<>();
        public final Observer N = new Observer();
        final List<TaskItem> registered = new ArrayList<>();
        int endAlarms;
        int startAlarms;
        int resets;
        boolean expired;
        public void t(CustomTimeConditionItem time) {
            XposedBridge.invoke(this, "t", new Class<?>[]{CustomTimeConditionItem.class}, new Object[]{time},
                    () -> { endAlarms++; return null; });
        }
        public void s(CustomTimeConditionItem time) {
            XposedBridge.invoke(this, "s", new Class<?>[]{CustomTimeConditionItem.class}, new Object[]{time},
                    () -> { startAlarms++; return null; });
        }
        public boolean R0(CustomTimeConditionItem time) {
            return (Boolean) XposedBridge.invoke(this, "R0", new Class<?>[]{CustomTimeConditionItem.class},
                    new Object[]{time}, () -> expired);
        }
        public void k0(String uuid) {
            XposedBridge.invoke(this, "k0", new Class<?>[]{String.class}, new Object[]{uuid}, () -> {
                Bean task = q.get(uuid);
                if (task != null && task.q()) { task.state = 0; resets++; s(task.time()); }
                return null;
            });
        }
        public void u(Bean task, boolean updated) {
            XposedBridge.invoke(this, "u", new Class<?>[]{Bean.class, boolean.class}, new Object[]{task, updated}, () -> {
                q.put(task.n(), task);
                if (task.state == 2 && !updated && !R0(task.time())) { t(task.time()); return null; }
                task.state = 0;
                if (task.enabled) {
                    for (TaskItem condition : task.conditions) if (!(condition instanceof CustomTimeConditionItem)) p(condition);
                    if (task.exitEnabled) for (TaskItem exit : task.exits) if (exit.k()) { exit.s(Factory.v(task.n())); p(exit); }
                }
                return null;
            });
        }
        public void p(TaskItem item) { registered.add(item); }
        public void q(AddressTaskItem item) { registered.add(item); }
        public void b1(String uuid, List<?> items) {
            XposedBridge.invoke(this, "b1", new Class<?>[]{String.class, List.class}, new Object[]{uuid, items}, () -> null);
        }
        void otherExit(TaskItem condition) {
            if (!condition.k() || !condition.m()) return;
            Bean task = q.get(condition.j().replace("/-", ""));
            if (task != null && task.q()) { task.state = 0; resets++; s(task.time()); }
        }
    }

    public static final class Event extends TaskItem {
        Event(boolean exit) { p(exit); }
        @Override public String e() { return "test_event_condition"; }
        @Override public boolean m() { return true; }
        @Override public boolean l() { return true; }
        @Override public String h() { return e(); }
        @Override public String g() { return e(); }
        @Override public int b() { return 0; }
        @Override public int c() { return 0; }
        @Override public int i() { return 0; }
    }
}
