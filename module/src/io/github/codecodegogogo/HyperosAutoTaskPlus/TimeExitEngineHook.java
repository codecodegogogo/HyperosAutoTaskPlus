package io.github.codecodegogogo.HyperosAutoTaskPlus;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Gates only native time-exit paths; other exit events still use the host's OR dispatcher. */
final class TimeExitEngineHook {
    private static final String RESTORE_LISTENERS = "hyperauto.timeExit.restoreListeners";
    private static final String DEFERRED_TRIGGERS = "hyperauto.timeExit.deferredTriggers";

    private TimeExitEngineHook() {}

    static void install(ClassLoader cl) {
        Class<?> engine = TargetResolver.engine(cl);
        Class<?> time = XposedHelpers.findClass("com.miui.autotask.taskitem.CustomTimeConditionItem", cl);
        Class<?> bean = XposedHelpers.findClass("com.miui.autotask.bean.r", cl);
        Class<?> item = XposedHelpers.findClass(ConditionHookSupport.CLASS_TASK_ITEM, cl);
        Class<?> address = XposedHelpers.findClass("com.miui.autotask.taskitem.AddressTaskItem", cl);
        Method schedule = HookUtils.findMethod(engine, new String[]{"t", "x"}, void.class, time);
        Method nextStart = HookUtils.findMethod(engine, new String[]{"s", "w"}, void.class, time);
        Method expired = HookUtils.findMethod(engine, new String[]{"R0", "T0", "Z0"}, boolean.class, time);
        Method dispatch = HookUtils.findMethod(engine, new String[]{"k0", "p0"}, void.class, String.class);
        Method load = HookUtils.findMethod(engine, new String[]{"u", "y"}, void.class, bean, boolean.class);
        Method register = HookUtils.findMethod(engine, new String[]{"p", "t"}, void.class, item);
        Method registerAddress = HookUtils.findMethod(engine, new String[]{"q", "u"}, void.class, address);
        Method unregister = HookUtils.findMethod(engine, new String[]{"b1", "d1", "o1"},
                void.class, String.class, List.class);
        if (schedule == null || nextStart == null || expired == null || dispatch == null || load == null || unregister == null
                || register == null || registerAddress == null) {
            throw new IllegalStateException("Time exit engine methods unavailable");
        }

        XposedBridge.hookMethod(schedule, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object task = taskForTime(param.thisObject, param.args[0]);
                if (blocksTimeExit(task)) param.setResult(null);
            }
        });
        XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                // Also reject a previously scheduled alarm after deleting/unchecking its row.
                if (blocksTimeExit(task(param.thisObject, (String) param.args[0]))) param.setResult(null);
            }
        });
        XposedBridge.hookMethod(expired, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object task = taskForTime(param.thisObject, param.args[0]);
                if (!param.hasThrowable() && blocksTimeExit(task)) param.setResult(false);
            }
        });
        XposedBridge.hookMethod(load, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object task = param.args[0];
                if (task == null) return;
                String uuid = (String) XposedHelpers.callMethod(task, "n");
                if (uuid == null || uuid.isEmpty()) return;
                deferred(param.thisObject).remove(uuid);
                if (!(Boolean) param.args[1] && enabled(task) && managed(task)
                        && (Integer) XposedHelpers.callMethod(task, "l") == 2) {
                    param.setObjectExtra(RESTORE_LISTENERS, Boolean.TRUE);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object task = param.args[0];
                if (param.hasThrowable() || param.getObjectExtra(RESTORE_LISTENERS) == null
                        || (Integer) XposedHelpers.callMethod(task, "l") != 2) return;
                // Register exits now, but defer normal triggers until this run exits: some
                // injected trigger registrations immediately notify and would replay the active task.
                deferred(param.thisObject).add((String) XposedHelpers.callMethod(task, "n"));
                if ((Boolean) XposedHelpers.callMethod(task, "p")) {
                    String uuid = (String) XposedHelpers.callMethod(task, "n");
                    String exitUuid = (String) XposedHelpers.callStaticMethod(TargetResolver.factory(cl), "v", uuid);
                    for (Object condition : list(task, "e")) {
                        if (!(Boolean) XposedHelpers.callMethod(condition, "k")) continue;
                        XposedHelpers.callMethod(condition, "s", exitUuid);
                        HookUtils.invokeMethod(address.isInstance(condition) ? registerAddress : register,
                                param.thisObject, condition);
                    }
                }
                XposedHelpers.callMethod(XposedHelpers.getObjectField(param.thisObject, "N"), "a", true);
            }
        });
        XposedBridge.hookMethod(nextStart, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                Object task = taskForTime(param.thisObject, param.args[0]);
                if (task == null || !enabled(task) || (Boolean) XposedHelpers.callMethod(task, "q")) return;
                if (!deferred(param.thisObject).remove((String) XposedHelpers.callMethod(task, "n"))) return;
                for (Object condition : list(task, "b")) {
                    if (!time.isInstance(condition)) {
                        HookUtils.invokeMethod(address.isInstance(condition) ? registerAddress : register,
                                param.thisObject, condition);
                    }
                }
                XposedHelpers.callMethod(XposedHelpers.getObjectField(param.thisObject, "N"), "a", true);
            }
        });
        XposedBridge.hookMethod(unregister, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                String uuid = (String) param.args[0];
                if (uuid != null) deferred(param.thisObject).remove(uuid);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static synchronized Set<String> deferred(Object engine) {
        Object existing = XposedHelpers.getAdditionalInstanceField(engine, DEFERRED_TRIGGERS);
        if (existing instanceof Set) return (Set<String>) existing;
        Set<String> result = ConcurrentHashMap.newKeySet();
        XposedHelpers.setAdditionalInstanceField(engine, DEFERRED_TRIGGERS, result);
        return result;
    }

    private static Object taskForTime(Object engine, Object time) {
        return task(engine, (String) XposedHelpers.callMethod(time, "j"));
    }

    private static Object task(Object engine, String uuid) {
        if (uuid == null || uuid.isEmpty()) return null;
        Object tasks = XposedHelpers.getObjectField(engine, "q");
        return tasks instanceof Map ? ((Map<?, ?>) tasks).get(uuid) : null;
    }

    private static boolean enabled(Object task) {
        return task != null && (Boolean) XposedHelpers.callMethod(task, "o");
    }

    private static boolean managed(Object task) {
        Class<?> runtime = DexInjector.runtime(TimeExitConditionHook.RUNTIME);
        return task != null && runtime != null && (Boolean) XposedHelpers.callStaticMethod(
                runtime, "hasManagedPeriod", list(task, "b"));
    }

    private static boolean blocksTimeExit(Object task) {
        if (!managed(task)) return false;
        return !enabled(task) || !(Boolean) XposedHelpers.callStaticMethod(
                DexInjector.runtime(TimeExitConditionHook.RUNTIME), "allowsTimeExit",
                list(task, "b"), list(task, "e"), XposedHelpers.callMethod(task, "p"));
    }

    private static List<?> list(Object task, String getter) {
        return (List<?>) XposedHelpers.callMethod(task, getter);
    }
}
