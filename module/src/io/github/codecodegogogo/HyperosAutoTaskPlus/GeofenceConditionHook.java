package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.app.Activity;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 功能九：「到达地理围栏 / 离开地理围栏」条件，即原生「到达 / 离开某地」加可自定义的围栏半径，
 * 放在「情境」分类的「离开某地」之后（原生这两项不可用时——没有小米地图选点组件——也不加）。
 *
 * 条件项是 ToSomewhereConditionItem / LeaveConditionItem 的子类（inject 包），所以：
 *   - 引擎注册围栏（b2.j.q → OperationListCollectService.u → GeofenceManager）、
 *     围栏进出回调（PolarisGeofenceReceiver → b2.j.N）、m() 判定、任务编辑页点击
 *     打开地址选择页（RecyclerViewPreference 按 instanceof AddressTaskItem 分发）全部原样复用；
 *   - 只需补三处：M0 的 key 分发、添加条件页点击、以及建围栏时 MiGeofence.setRadius(500)
 *     换成条件项自己的半径（按围栏 id "auto_task_<uuid>" 反查引擎里的条件项）。
 * 半径对话框在打开地址选择页之前弹（hook AddressSelectActivity.j1），确定后再选地址。
 */
final class GeofenceConditionHook {

    private static final String TAG = MainHook.TAG;
    static final String RUNTIME = "GeofenceRuntime";

    private static final String CLASS_ADDRESS_ITEM = "com.miui.autotask.taskitem.AddressTaskItem";
    private static final String CLASS_ADDRESS_SELECT = "com.miui.autotask.activity.AddressSelectActivity";
    private static final String CLASS_MI_GEOFENCE = "com.xiaomi.gnss.polaris.geofence.MiGeofence";
    private static final String GEOFENCE_ID_PREFIX = "auto_task_";
    /** AddBaseFragment.b，添加条件页用的 requestCode */
    private static final int REQUEST_ADD_CONDITION = 102;
    private static final String FLAG_RADIUS_PICKED = "hyperAutoRadiusPicked";

    private GeofenceConditionHook() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        final Class<?> taskItem = XposedHelpers.findClass(ConditionHookSupport.CLASS_TASK_ITEM, cl);
        final Class<?> addressItem = XposedHelpers.findClass(CLASS_ADDRESS_ITEM, cl);

        HookUtils.step("M0(geofence)", () -> {
            ConditionHookSupport.hookFactory(cl, RUNTIME, FirstAppKeys::isGeofenceKey);
            ConditionHookSupport.hookCategory(cl, RUNTIME, FirstAppKeys.CATEGORY_SITUATION,
                    FirstAppKeys.KEY_LEAVE_SOMEWHERE_CONDITION, true,
                    FirstAppKeys.KEY_GEOFENCE_ENTER_CONDITION, FirstAppKeys.KEY_GEOFENCE_LEAVE_CONDITION);
            ConditionHookSupport.hookOppositeKey(cl, FirstAppKeys::isGeofenceKey);
        });
        HookUtils.step("AddressSelect(geofence)", () -> hookAddressSelect(cl, taskItem, addressItem));
        HookUtils.step("radius(geofence)", () -> hookRadius(cl));
    }

    // ------------------------------------------------------------------ 添加 / 编辑入口

    private static void hookAddressSelect(ClassLoader cl, Class<?> taskItem, Class<?> addressItem) {
        Class<?> addressSelect = XposedHelpers.findClass(CLASS_ADDRESS_SELECT, cl);
        final Method open = HookUtils.findMethod(addressSelect, new String[]{"j1", "k1", "X0"},
                void.class, Activity.class, addressItem, int.class);
        if (open == null) {
            XposedBridge.log(TAG + ": AddressSelectActivity.j1/k1/X0 未找到，地理围栏条件无法添加");
            return;
        }

        // 添加条件页：原生 q1/n1 的 switch 不认识我们的 key，这里直接打开地址选择页
        Class<?> fragment = XposedHelpers.findClass(ConditionHookSupport.CLASS_ADD_CONDITION_FRAGMENT, cl);
        Method onConditionClick = HookUtils.findMethod(fragment, new String[]{"q1", "n1"},
                void.class, taskItem);
        if (onConditionClick != null) {
            XposedBridge.hookMethod(onConditionClick, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object item = param.args[0];
                    if (!ConditionHookSupport.isItem(RUNTIME, item)) {
                        return;
                    }
                    param.setResult(null);
                    Object activity = XposedHelpers.callMethod(param.thisObject, "getActivity");
                    if (activity != null) {
                        open.invoke(null, activity, item, REQUEST_ADD_CONDITION);
                    }
                }
            });
        } else {
            XposedBridge.log(TAG + ": AddConditionFragment.q1/n1 未找到，地理围栏无法从列表添加");
        }

        // 打开地址选择页之前先问半径；确定后带标记再走一遍原方法
        XposedBridge.hookMethod(open, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                final Object activity = param.args[0];
                final Object item = param.args[1];
                final int request = (Integer) param.args[2];
                if (!(activity instanceof Activity) || !ConditionHookSupport.isItem(RUNTIME, item)) {
                    return;
                }
                if (XposedHelpers.removeAdditionalInstanceField(item, FLAG_RADIUS_PICKED) != null) {
                    return;
                }
                param.setResult(null);
                Runnable then = () -> {
                    XposedHelpers.setAdditionalInstanceField(item, FLAG_RADIUS_PICKED, Boolean.TRUE);
                    try {
                        open.invoke(null, activity, item, request);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": 打开地址选择页失败");
                        XposedBridge.log(t);
                    }
                };
                XposedHelpers.callStaticMethod(ConditionHookSupport.runtime(RUNTIME), "pickRadius", activity, item, then);
            }
        });
    }

    // ------------------------------------------------------------------ 围栏半径

    private static void hookRadius(ClassLoader cl) {
        Class<?> miGeofence = XposedHelpers.findClass(CLASS_MI_GEOFENCE, cl);
        final Class<?> engine = TargetResolver.engine(cl);
        final Method singleton = findSingleton(engine);
        final Method lookup = HookUtils.findMethod(engine, new String[]{"l0", "q0"},
                XposedHelpers.findClass(CLASS_ADDRESS_ITEM, cl), String.class);
        if (singleton == null || lookup == null) {
            XposedBridge.log(TAG + ": 引擎单例或 l0/q0 未找到，地理围栏半径固定为 500 米");
            return;
        }

        XposedHelpers.findAndHookMethod(miGeofence, "setRadius", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object id = XposedHelpers.callMethod(param.thisObject, "getId");
                if (!(id instanceof String) || !((String) id).startsWith(GEOFENCE_ID_PREFIX)) {
                    return;
                }
                Class<?> rt = ConditionHookSupport.runtime(RUNTIME);
                if (rt == null) {
                    return;
                }
                Object eng = singleton.invoke(null);
                Object item = eng == null ? null : lookup.invoke(eng, ((String) id).substring(GEOFENCE_ID_PREFIX.length()));
                if (item == null) {
                    return;
                }
                int radius = (Integer) XposedHelpers.callStaticMethod(rt, "radiusOf", item);
                if (radius > 0) {
                    XposedBridge.log(TAG + ": 围栏 " + id + " 半径 " + param.args[0] + " -> " + radius);
                    param.args[0] = radius;
                }
            }
        });
    }

    /** public static j z0() / A0() / F0()：引擎单例 */
    private static Method findSingleton(Class<?> engine) {
        return HookUtils.findMethod(engine, new String[]{"z0", "A0", "F0"}, engine);
    }
}
