package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 再执行的引擎生命周期与退出顺序接入，不接管原生动作执行循环。 */
final class ThenExecutionEngineHook {
    private static final String RUNTIME = "ThenRuntime";
    private static final String FRAME_KEY = "hyper_auto_then_execution_frame";
    private static final ThreadLocal<Deque<Frame>> EXECUTIONS = ThreadLocal.withInitial(ArrayDeque::new);

    private ThenExecutionEngineHook() {}

    static void install(XC_LoadPackage.LoadPackageParam lp) {
        // 持久恢复记录只由真正运行自动任务的进程读写。
        if (!"com.miui.securitycenter.remote".equals(lp.processName)) return;
        Class<?> engine = TargetResolver.engine(lp.classLoader);
        Class<?> factory = TargetResolver.factory(lp.classLoader);
        Class<?> bean = XposedHelpers.findClass("com.miui.autotask.bean.r", lp.classLoader);
        Method execute = HookUtils.findMethod(engine, new String[]{"i0", "n0"}, void.class, bean);
        Method unregister = HookUtils.findMethod(engine, new String[]{"b1", "d1", "o1"},
                void.class, String.class, List.class);
        Method load = HookUtils.findMethod(engine, new String[]{"u", "y"}, void.class, bean, boolean.class);
        if (execute == null || unregister == null || load == null) {
            throw new IllegalStateException("再执行所需的引擎方法未找到");
        }

        XposedBridge.hookAllConstructors(engine, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                start(param.thisObject, factory);
            }
        });

        XposedBridge.hookMethod(execute, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                start(param.thisObject, factory);
                Object task = param.args[0];
                Frame frame = new Frame(task, (String) XposedHelpers.callMethod(task, "n"),
                        (Boolean) XposedHelpers.callMethod(task, "q"));
                EXECUTIONS.get().push(frame);
                param.setObjectExtra(FRAME_KEY, frame);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object frame = param.getObjectExtra(FRAME_KEY);
                if (frame == null) return;
                Deque<Frame> stack = EXECUTIONS.get();
                stack.remove(frame);
                if (stack.isEmpty()) EXECUTIONS.remove();
            }
        });

        XposedHelpers.findAndHookMethod(bean, "a", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Deque<Frame> stack = EXECUTIONS.get();
                Frame frame = stack.peek();
                if (frame == null || frame.bean != param.thisObject || !frame.restoring
                        || !(param.getResult() instanceof List)) return;
                List<?> original = (List<?>) param.getResult();
                List<Object> reordered = new ArrayList<>(original.size());
                for (Object action : original) if (isThen(action)) reordered.add(action);
                if (reordered.isEmpty()) return;
                for (Object action : original) if (!isThen(action)) reordered.add(action);
                // 延迟亮度等先退回普通执行后的状态，再让普通结果恢复最初状态。
                param.setResult(reordered);
            }
        });

        XposedBridge.hookMethod(unregister, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                String uuid = (String) param.args[0];
                if (!insideExecution(uuid)) invoke("cancel", uuid);
            }
        });

        XposedBridge.hookMethod(load, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                start(param.thisObject, factory);
                if ((Boolean) param.args[1]) {
                    String uuid = (String) XposedHelpers.callMethod(param.args[0], "n");
                    if (!insideExecution(uuid)) invoke("cancel", uuid);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!param.hasThrowable() && !(Boolean) param.args[1]) invoke("taskLoaded", param.args[0]);
            }
        });
    }

    private static boolean insideExecution(String value) {
        if (value == null) return false;
        String uuid = value.endsWith("/-") ? value.substring(0, value.length() - 2) : value;
        for (Frame frame : EXECUTIONS.get()) {
            // 单次定时任务会在 n() 之后自动停用；此时不能取消刚建立的等待。
            if (uuid.equals(frame.uuid)) return true;
        }
        return false;
    }

    private static void start(Object engine, Class<?> factory) {
        Class<?> runtime = DexInjector.runtime(RUNTIME);
        Context context = DexInjector.appContext();
        if (runtime != null && context != null) {
            XposedHelpers.callStaticMethod(runtime, "start", context, engine, factory);
        }
    }

    private static boolean isThen(Object action) {
        return action != null && FirstAppKeys.KEY_THEN_EXECUTION_RESULT.equals(
                XposedHelpers.callMethod(action, "e"));
    }

    private static void invoke(String name, Object value) {
        Class<?> runtime = DexInjector.runtime(RUNTIME);
        if (runtime != null) XposedHelpers.callStaticMethod(runtime, name, value);
    }

    private static final class Frame {
        final Object bean;
        final String uuid;
        final boolean restoring;
        Frame(Object bean, String uuid, boolean restoring) {
            this.bean = bean;
            this.uuid = uuid;
            this.restoring = restoring;
        }
    }
}
