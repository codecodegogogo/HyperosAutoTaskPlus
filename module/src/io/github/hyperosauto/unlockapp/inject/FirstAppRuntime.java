package io.github.hyperosauto.unlockapp.inject;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Log;

import com.miui.autotask.taskitem.LunchAppItem;
import com.miui.autotask.taskitem.TaskItem;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.hyperosauto.unlockapp.FirstAppKeys;

/**
 * 「首次启动 / 首次离开」条件的运行时：跟踪应用进程是否在内存中，
 * 并在变化时把相关条件项交给安全中心的自动任务引擎（b2.j）重新判定。
 *
 * 进程存活的来源是系统 API ActivityManager.addOnUidImportanceListener：
 *   - 一个 uid 第一次上报（任意 importance != GONE）= 该 uid 的第一个进程被创建；
 *   - importance == IMPORTANCE_GONE(1000)          = 该 uid 的最后一个进程已退出。
 * 安全中心自己判定音乐/地图类应用的「离开应用」用的正是这个回调，权限已具备。
 *
 * uid 会按包名聚合（双开的 999 用户和主用户算同一个应用），条件项里存的也是包名。
 *
 * 这个类和条件项一起被注入到安全中心的 ClassLoader 里，hook 侧只能通过反射调用
 * 这里的 public static 方法，参数类型统一用 Object / String / List，避免跨 ClassLoader 的类型不匹配。
 */
public final class FirstAppRuntime {

    private static final String TAG = "HyperAutoEnh";

    private static final int IMPORTANCE_GONE = 1000;
    private static final int PER_USER_RANGE = 100000;
    private static final int FIRST_APPLICATION_UID = 10000;
    private static final int LAST_APPLICATION_UID = 19999;

    private static final Object LOCK = new Object();
    /** 存活 uid -> 该 uid 下的包名（首次上报时从 PackageManager 取，退出时直接用缓存，包被卸载也能命中） */
    private static final Map<Integer, String[]> UID_PKGS = new HashMap<>();
    /** 包名 -> 存活 uid 个数，> 0 即视为该应用在内存中 */
    private static final Map<String, Integer> PKG_ALIVE = new HashMap<>();
    /** 引擎里已启用任务的条件项，key 为 TaskItem.j()，和 b2.j 自己那些 map 的键一致 */
    private static final ConcurrentHashMap<String, FirstAppConditionItem> ITEMS = new ConcurrentHashMap<>();

    private static volatile boolean sStarted;
    private static Context sContext;
    private static Object sEngine;
    private static Method sNotify;
    // ActivityManager 内部用 ArrayMap 持有 listener，这里再留一份强引用防止意外回收
    @SuppressWarnings("unused")
    private static Object sListener;

    private FirstAppRuntime() {
    }

    // ------------------------------------------------------------------ 给 hook 侧用的入口

    public static Class<?> classForKey(String key) {
        if (FirstAppKeys.KEY_FIRST_START.equals(key)) {
            return FirstStartAppConditionItem.class;
        }
        if (FirstAppKeys.KEY_FIRST_LEAVE.equals(key)) {
            return FirstLeaveAppConditionItem.class;
        }
        return null;
    }

    public static Object newItem(String key) {
        if (FirstAppKeys.KEY_FIRST_START.equals(key)) {
            return new FirstStartAppConditionItem();
        }
        if (FirstAppKeys.KEY_FIRST_LEAVE.equals(key)) {
            return new FirstLeaveAppConditionItem();
        }
        return null;
    }

    public static boolean isFirstAppItem(Object item) {
        return item instanceof FirstAppConditionItem;
    }

    /**
     * 对应 g2.M0.t()：由一个条件生成它的退出条件（首次启动 <-> 首次离开），
     * 应用列表和 uuid 原样复制。
     */
    public static Object opposite(Object item) {
        if (!(item instanceof FirstAppConditionItem)) {
            return null;
        }
        FirstAppConditionItem src = (FirstAppConditionItem) item;
        FirstAppConditionItem dst = src.isStart()
                ? new FirstLeaveAppConditionItem()
                : new FirstStartAppConditionItem();
        copyApps(src, dst);
        dst.s(src.j());
        return dst;
    }

    /**
     * 对应 g2.M0.e()：条件被编辑后，把新的应用列表同步到退出条件列表里的对应项。
     * 返回被同步项的下标，找不到返回 -1。
     */
    public static int syncOpposite(Object item, List list) {
        if (!(item instanceof FirstAppConditionItem) || list == null) {
            return -1;
        }
        FirstAppConditionItem src = (FirstAppConditionItem) item;
        String wanted = FirstAppKeys.opposite(src.e());
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (o instanceof LunchAppItem && wanted.equals(((TaskItem) o).e())) {
                copyApps(src, (LunchAppItem) o);
                return i;
            }
        }
        return -1;
    }

    private static void copyApps(LunchAppItem src, LunchAppItem dst) {
        dst.E(src.z());
        dst.D(src.y());
        dst.B(src.v());
        dst.A(src.u());
    }

    /** 对应 b2.j.p()：任务启用时引擎会逐个注册条件项，自己的 key 它不认识，这里接住 */
    public static void register(Object item) {
        if (!(item instanceof FirstAppConditionItem)) {
            return;
        }
        FirstAppConditionItem it = (FirstAppConditionItem) item;
        String uuid = it.j();
        if (TextUtils.isEmpty(uuid)) {
            return;
        }
        ITEMS.put(uuid, it);
        Log.i(TAG, "register " + it.e() + " uuid=" + uuid + " pkgs=" + it.z());
    }

    /** 对应 b2.j.b1()：任务停用/删除时按 uuid 反注册 */
    public static void unregister(String uuid) {
        if (uuid != null && ITEMS.remove(uuid) != null) {
            Log.i(TAG, "unregister uuid=" + uuid);
        }
    }

    /**
     * 启动进程跟踪。在 b2.j 构造完成后调用，engine 即那个实例；
     * 后续通知通过 engine.K(ConcurrentHashMap) 投递到引擎自己的工作线程。
     */
    public static void start(Context context, Object engine) {
        synchronized (LOCK) {
            if (sStarted) {
                return;
            }
            sStarted = true;
        }
        try {
            sContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            sEngine = engine;
            sNotify = findNotifyMethod(engine);
            registerUidListener();
            bootstrapAlive();
            Log.i(TAG, "process tracker started, alive pkgs=" + PKG_ALIVE.size());
        } catch (Throwable t) {
            Log.e(TAG, "start process tracker failed", t);
        }
    }

    // ------------------------------------------------------------------ 条件判定

    /**
     * 首次启动：所选应用任一在内存中。
     * 首次离开：作为退出条件（k() 为 true）时要求所选应用全部不在内存，
     *           作为普通触发条件时任一不在内存即可——单选时两者等价，
     *           多选时前者避免还有应用在跑就提前恢复，后者保证任一应用退出都能触发。
     */
    static boolean evaluate(FirstAppConditionItem item) {
        List pkgs = allPkgs(item);
        if (pkgs.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            int alive = 0;
            for (Object o : pkgs) {
                if (PKG_ALIVE.containsKey((String) o)) {
                    alive++;
                }
            }
            if (item.isStart()) {
                return alive > 0;
            }
            return item.k() ? alive == 0 : alive < pkgs.size();
        }
    }

    private static List allPkgs(LunchAppItem item) {
        ArrayList<String> result = new ArrayList<>();
        String single = item.y();
        if (!TextUtils.isEmpty(single)) {
            result.add(single);
        }
        for (Object o : item.z()) {
            if (o instanceof String && !result.contains(o)) {
                result.add((String) o);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ uid 事件

    private static void registerUidListener() throws Exception {
        ActivityManager am = (ActivityManager) sContext.getSystemService(Context.ACTIVITY_SERVICE);
        final Class<?> listenerCls = Class.forName("android.app.ActivityManager$OnUidImportanceListener");
        Method add = ActivityManager.class.getMethod("addOnUidImportanceListener", listenerCls, int.class);
        Object listener = Proxy.newProxyInstance(
                FirstAppRuntime.class.getClassLoader(),
                new Class<?>[]{listenerCls},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("onUidImportance".equals(name) && args != null && args.length == 2) {
                            try {
                                onUidImportance((Integer) args[0], (Integer) args[1]);
                            } catch (Throwable t) {
                                // 跑在 binder 线程上，绝不能把异常抛回给系统
                                Log.e(TAG, "onUidImportance failed", t);
                            }
                            return null;
                        }
                        // ActivityManager 用 ArrayMap 保存 listener，会调 hashCode/equals
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(name)) {
                            return args != null && args.length == 1 && proxy == args[0];
                        }
                        if ("toString".equals(name)) {
                            return "FirstAppRuntime.UidListener";
                        }
                        return null;
                    }
                });
        // cutpoint 用 IMPORTANCE_FOREGROUND(100)，与安全中心自己的监听一致：
        // uid 创建时必有一次上报；即便系统实现有差异，最晚也会在应用第一次进前台时补上。
        add.invoke(am, listener, 100);
        sListener = listener;
    }

    /** 监听只报变化，先把已经在跑的进程记下来，避免它们下一次状态变化被当成「首次启动」 */
    private static void bootstrapAlive() {
        ActivityManager am = (ActivityManager) sContext.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null) {
            return;
        }
        synchronized (LOCK) {
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (!isAppUid(p.uid) || p.pkgList == null || p.pkgList.length == 0) {
                    continue;
                }
                String[] known = UID_PKGS.get(p.uid);
                if (known == null) {
                    UID_PKGS.put(p.uid, p.pkgList);
                    for (String pkg : p.pkgList) {
                        incAlive(pkg);
                    }
                } else {
                    // 同一 uid 的多个进程，pkgList 可能不同（共享 uid），合并
                    Set<String> merged = new HashSet<>();
                    for (String s : known) {
                        merged.add(s);
                    }
                    for (String pkg : p.pkgList) {
                        if (merged.add(pkg)) {
                            incAlive(pkg);
                        }
                    }
                    UID_PKGS.put(p.uid, merged.toArray(new String[0]));
                }
            }
        }
    }

    private static void onUidImportance(int uid, int importance) {
        if (!isAppUid(uid)) {
            return;
        }
        List<String> started = new ArrayList<>();
        List<String> stopped = new ArrayList<>();
        synchronized (LOCK) {
            if (importance == IMPORTANCE_GONE) {
                String[] pkgs = UID_PKGS.remove(uid);
                boolean tracked = pkgs != null;
                if (!tracked) {
                    pkgs = packagesForUid(uid);
                }
                if (pkgs == null) {
                    return;
                }
                for (String pkg : pkgs) {
                    if (tracked) {
                        if (decAlive(pkg)) {
                            stopped.add(pkg);
                        }
                    } else if (!PKG_ALIVE.containsKey(pkg)) {
                        // 没跟踪到的 uid（极少见）：计数不能动。只有在我们眼里它本来就不在内存，
                        // 才把这次退出当成事件通知出去；否则等已知的那些 uid 退出时再说。
                        stopped.add(pkg);
                    }
                }
            } else {
                if (UID_PKGS.containsKey(uid)) {
                    return;
                }
                String[] pkgs = packagesForUid(uid);
                if (pkgs == null) {
                    return;
                }
                UID_PKGS.put(uid, pkgs);
                for (String pkg : pkgs) {
                    if (incAlive(pkg)) {
                        started.add(pkg);
                    }
                }
            }
        }
        for (String pkg : started) {
            notifyEngine(pkg, true);
        }
        for (String pkg : stopped) {
            notifyEngine(pkg, false);
        }
    }

    /** 返回 true 表示这个包从「不在内存」变成「在内存」 */
    private static boolean incAlive(String pkg) {
        Integer c = PKG_ALIVE.get(pkg);
        int n = c == null ? 1 : c + 1;
        PKG_ALIVE.put(pkg, n);
        return n == 1;
    }

    /** 返回 true 表示这个包已经没有任何存活 uid */
    private static boolean decAlive(String pkg) {
        Integer c = PKG_ALIVE.get(pkg);
        if (c == null || c <= 1) {
            PKG_ALIVE.remove(pkg);
            return true;
        }
        PKG_ALIVE.put(pkg, c - 1);
        return false;
    }

    private static String[] packagesForUid(int uid) {
        try {
            PackageManager pm = sContext.getPackageManager();
            String[] pkgs = pm.getPackagesForUid(uid);
            return pkgs == null || pkgs.length == 0 ? null : pkgs;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只关心普通应用 uid，跳过系统 uid、隔离进程、SDK 沙箱等 */
    private static boolean isAppUid(int uid) {
        int appId = uid % PER_USER_RANGE;
        return appId >= FIRST_APPLICATION_UID && appId <= LAST_APPLICATION_UID;
    }

    // ------------------------------------------------------------------ 通知引擎

    /**
     * 挑出包含该包名、且方向匹配的条件项，交给引擎的 K(ConcurrentHashMap)。
     * 引擎会在自己的 auto_task 线程里逐个调 m()，满足就执行/恢复任务，
     * 与它处理前台切换（V()/Q()）的路径完全相同。
     */
    private static void notifyEngine(String pkg, boolean started) {
        if (sEngine == null || sNotify == null || ITEMS.isEmpty()) {
            return;
        }
        ConcurrentHashMap<String, TaskItem> hit = new ConcurrentHashMap<>();
        for (Map.Entry<String, FirstAppConditionItem> e : ITEMS.entrySet()) {
            FirstAppConditionItem item = e.getValue();
            if (item.isStart() == started && item.matches(pkg)) {
                hit.put(e.getKey(), item);
            }
        }
        if (hit.isEmpty()) {
            return;
        }
        Log.i(TAG, (started ? "process started: " : "process gone: ") + pkg + " -> " + hit.keySet());
        try {
            sNotify.invoke(sEngine, hit);
        } catch (Throwable t) {
            Log.e(TAG, "notify engine failed", t);
        }
    }

    private static Method findNotifyMethod(Object engine) throws NoSuchMethodException {
        Class<?> cls = engine.getClass();
        // K() 是 public 的对外入口，内部转 H() 投递到工作线程；名字随版本混淆可能变化，按签名兜底
        try {
            return cls.getMethod("K", ConcurrentHashMap.class);
        } catch (NoSuchMethodException ignored) {
            // fall through
        }
        for (Method m : cls.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == ConcurrentHashMap.class
                    && m.getReturnType() == void.class
                    && java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                return m;
            }
        }
        throw new NoSuchMethodException("b2.j#K(ConcurrentHashMap)");
    }
}
