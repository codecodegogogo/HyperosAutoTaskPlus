package io.github.codecodegogogo.HyperosAutoTaskPlus;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 功能一：解锁使用「启动应用 / 离开应用」条件时，结果列表中「打开应用」被置灰的限制。
 *
 * 限制来源（安全服务 12.3.5，com.miui.securitycenter）：
 *   g2.M0.J(String) 返回互斥 key 列表，其中
 *     key_start_activity_condition_item
 *     key_leave_activity_condition_item
 *     key_start_activity_result_item
 *   三者互为一组。该 List 经 g2.M0.K(List) 汇总后作为 unableKeyList
 *   传给 AddResultActivity，由 AddResultFragment.T0() 置灰对应项。
 *
 * 引擎层（b2.j / StartActivityResultItem）对此没有任何限制，
 * 纯粹是界面拦截，因此只需在 J() 返回后剔除 result key。
 *
 * 顺带为「首次启动应用 / 首次离开应用」补上和原生一致的互斥：
 * 同一任务里不能同时选这两个条件。
 */
final class UnlockAppResultHook {

    private static final String TAG = MainHook.TAG;

    private static final String CLASS_M0 = "g2.M0";

    private UnlockAppResultHook() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> m0 = XposedHelpers.findClass(CLASS_M0, lpparam.classLoader);
        Method target = findConflictMethod(m0);

        if (target == null) {
            XposedBridge.log(TAG + ": 未找到目标方法 J(String)，本版本可能已变更");
            return;
        }

        XposedBridge.log(TAG + ": hook " + target);

        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam param) {
                Object arg = param.args[0];
                if (!(arg instanceof String)) {
                    return;
                }
                String key = (String) arg;

                if (FirstAppKeys.isFirstAppKey(key)) {
                    param.setResult(new ArrayList<>(Arrays.asList(FirstAppKeys.opposite(key))));
                    return;
                }

                // 只放开「应用条件 → 打开应用结果」这一组。
                // condition ↔ condition 的互斥保留：同一 App 不能同时
                // 用「启动时」和「离开时」触发，那是真冲突。
                if (!FirstAppKeys.KEY_START_ACTIVITY_CONDITION.equals(key)
                        && !FirstAppKeys.KEY_LEAVE_ACTIVITY_CONDITION.equals(key)) {
                    return;
                }

                Object result = param.getResult();
                if (!(result instanceof List)) {
                    return;
                }

                List<String> original = (List<String>) result;
                if (!original.contains(FirstAppKeys.KEY_START_ACTIVITY_RESULT)) {
                    return;
                }

                // 原方法返回 Arrays.asList()，是固定长度视图，
                // 直接 remove 会抛 UnsupportedOperationException。
                List<String> patched = new ArrayList<>(original);
                patched.remove(FirstAppKeys.KEY_START_ACTIVITY_RESULT);
                param.setResult(patched);

                XposedBridge.log(TAG + ": " + key + " -> 已解锁「打开应用」结果项");
            }
        });
    }

    /**
     * 定位 J(String)List。
     * 方法名可能随版本混淆变化，所以按签名筛选（private static，入参 String，返回 List），
     * 再用已知输入探测：J("key_start_activity_condition_item") 的返回值
     * 应当包含 key_start_activity_result_item。探测失败时退回按名字 "J" 匹配。
     */
    private static Method findConflictMethod(Class<?> m0) {
        Method byName = null;
        for (Method m : m0.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 1
                    || params[0] != String.class
                    || !Modifier.isStatic(m.getModifiers())
                    || !List.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            m.setAccessible(true);
            try {
                Object probe = m.invoke(null, FirstAppKeys.KEY_START_ACTIVITY_CONDITION);
                if (probe instanceof List
                        && ((List<?>) probe).contains(FirstAppKeys.KEY_START_ACTIVITY_RESULT)) {
                    return m;
                }
            } catch (Throwable ignored) {
                // 探测失败不致命，继续看下一个候选
            }
            if ("J".equals(m.getName())) {
                byName = m;
            }
        }
        return byName;
    }
}
