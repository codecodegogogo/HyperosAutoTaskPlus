package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「应用使用时长」运行时的 JVM 回归：
 *   1. 进程只是被系统缓存、从未进入前台时不能开始计时；
 *   2. 应用打开后才开始累计，退到后台继续累计；
 *   3. 进程被杀掉后结束区间，重新打开重新开始；
 *   4. 安全服务重启时，同一次开机内仍存活的区间从原起点续算；
 *   5. 设备重启后残留区间直接丢弃，不把关机时间算成使用时长。
 */
public final class AppUsageRuntimeTest {

    private static final long MINUTE = 60_000L;
    private static final long DAY = 24L * 60L * MINUTE;

    private static final FakeClock CLOCK = new FakeClock();
    private static final Map<String, Object> PREFS = new HashMap<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        installTestState();
        cachedProcessDoesNotCount();
        foregroundThenBackgroundKeepsCounting();
        processDeathEndsInterval();
        sameBootResume();
        rebootDropsStaleInterval();
        metConditionDoesNotRetrigger();
        upgradeClearsLegacyIntervals();
        System.out.println("App usage runtime checks passed: " + checks);
    }

    /** 复现「刚打开就提示到时」：被系统缓存但从未打开的应用不能先开始计时。 */
    private static void cachedProcessDoesNotCount() throws Exception {
        String pkg = "com.example.cached";
        setAlive(pkg);
        FakeItem item = new FakeItem("uuid-cached", pkg, 30 * MINUTE, DAY);

        AppUsageRuntime.register(item);
        check(!activeSince().containsKey(pkg), "仅进程存活时注册条件不应建立使用区间");
        CLOCK.advance(31 * MINUTE);
        check(!AppUsageRuntime.evaluate(item), "仅进程存活、从未打开时不应开始计时");

        AppUsageRuntime.onForegroundInfo(new FakeForegroundInfo(pkg));
        check(activeSince().containsKey(pkg), "进入前台后建立使用区间");
        check(!AppUsageRuntime.evaluate(item), "刚打开时未达到阈值");
        CLOCK.advance(29 * MINUTE);
        check(!AppUsageRuntime.evaluate(item), "差一分钟时不触发");
        CLOCK.advance(2 * MINUTE);
        check(AppUsageRuntime.evaluate(item), "前台累计达到阈值后触发");
    }

    private static void foregroundThenBackgroundKeepsCounting() throws Exception {
        String pkg = "com.example.background";
        setAlive(pkg);
        FakeItem item = new FakeItem("uuid-background", pkg, 30 * MINUTE, DAY);

        AppUsageRuntime.onForegroundInfo(new FakeForegroundInfo(pkg));
        AppUsageRuntime.register(item);
        check(!AppUsageRuntime.evaluate(item), "打开后立即判定未到阈值");

        CLOCK.advance(10 * MINUTE);
        AppUsageRuntime.onForegroundInfo(new FakeForegroundInfo("com.example.other"));
        CLOCK.advance(21 * MINUTE);
        check(AppUsageRuntime.evaluate(item), "退到后台后进程仍存活，继续累计");
    }

    private static void processDeathEndsInterval() throws Exception {
        String pkg = "com.example.dead";
        setAlive(pkg);
        FakeItem item = new FakeItem("uuid-dead", pkg, 30 * MINUTE, DAY);

        AppUsageRuntime.onForegroundInfo(new FakeForegroundInfo(pkg));
        AppUsageRuntime.register(item);
        CLOCK.advance(10 * MINUTE);
        clearAlive(pkg);
        AppUsageRuntime.onPackageAlive(pkg, false);
        CLOCK.advance(2 * 60 * MINUTE);
        check(!AppUsageRuntime.evaluate(item), "进程结束后不再累计");

        AppUsageRuntime.onForegroundInfo(new FakeForegroundInfo("com.example.other"));
        setAlive(pkg);
        AppUsageRuntime.onPackageAlive(pkg, true);
        check(!AppUsageRuntime.evaluate(item), "进程重启但不在前台时不开始计时");

        AppUsageRuntime.onForegroundInfo(new FakeForegroundInfo(pkg));
        CLOCK.advance(21 * MINUTE);
        check(AppUsageRuntime.evaluate(item), "重新打开后累计到阈值");
    }

    private static void sameBootResume() throws Exception {
        String pkg = "com.example.resume";
        long startWall = CLOCK.wall;
        PREFS.put("active:" + pkg, startWall);
        PREFS.put("active_elapsed:" + pkg, CLOCK.elapsed);
        setAlive(pkg);
        CLOCK.advance(100_000L);

        FakeItem item = new FakeItem("uuid-resume", pkg, 2 * MINUTE, DAY);
        AppUsageRuntime.register(item);
        check(!AppUsageRuntime.evaluate(item), "同一次开机内从原起点续算");
        CLOCK.advance(30_000L);
        check(AppUsageRuntime.evaluate(item), "续算满 2 分钟后触发");
    }

    private static void rebootDropsStaleInterval() throws Exception {
        String pkg = "com.example.reboot";
        long startWall = CLOCK.wall;
        PREFS.put("active:" + pkg, startWall);
        PREFS.put("active_elapsed:" + pkg, CLOCK.elapsed + 30 * MINUTE);
        setAlive(pkg);
        // 模拟重启：elapsedRealtime 归零后重新走到 5 分钟，墙钟已经过去 5 小时
        CLOCK.elapsed = 5 * MINUTE;
        CLOCK.wall = startWall + 5 * 60 * MINUTE + 5 * MINUTE;

        FakeItem item = new FakeItem("uuid-reboot", pkg, 30 * MINUTE, DAY);
        AppUsageRuntime.register(item);
        check(!AppUsageRuntime.evaluate(item), "重启后的残留区间不计入使用时长");
        check(PREFS.get("active:" + pkg) == null, "重启后的残留区间被清理");
    }

    /** 条件持续满足时，重新打开应用不能再次通知引擎执行任务。 */
    private static void metConditionDoesNotRetrigger() throws Exception {
        String pkg = "com.example.retrigger";
        FakeEngine engine = new FakeEngine();
        EngineBridge.attach(engine);

        setAlive(pkg);
        FakeItem item = new FakeItem("uuid-retrigger", pkg, 30 * MINUTE, DAY);
        AppUsageRuntime.onForegroundInfo(new FakeForegroundInfo(pkg));
        AppUsageRuntime.register(item);
        check(engine.notifications == 0, "未达到阈值时不通知引擎");

        CLOCK.advance(31 * MINUTE);
        clearAlive(pkg);
        AppUsageRuntime.onPackageAlive(pkg, false);
        check(engine.notifications == 1, "首次达到阈值通知一次");

        setAlive(pkg);
        AppUsageRuntime.onPackageAlive(pkg, true);
        AppUsageRuntime.onForegroundInfo(new FakeForegroundInfo(pkg));
        CLOCK.advance(5 * MINUTE);
        check(engine.notifications == 1, "条件持续满足时重新打开不重复通知");
    }

    /** 旧版本把缓存时间算进时长，升级后必须清空这些不可信数据。 */
    private static void upgradeClearsLegacyIntervals() throws Exception {
        String pkg = "com.example.upgrade";
        PREFS.put("version", 1);
        PREFS.put("history:" + pkg, "1000,2000");
        PREFS.put("active:" + pkg, 3000L);
        setAlive(pkg);

        FakeItem item = new FakeItem("uuid-upgrade", pkg, 1 * MINUTE, DAY);
        AppUsageRuntime.register(item);
        check(!AppUsageRuntime.evaluate(item), "升级后旧区间不再计入");
        check(Integer.valueOf(2).equals(PREFS.get("version")), "升级后写入新版本号");
        check(PREFS.get("history:" + pkg) == null, "旧历史被清空");
        check(PREFS.get("active:" + pkg) == null, "旧活动区间被清空");
    }

    // ------------------------------------------------------------------ 测试替身

    private static void installTestState() throws Exception {
        PREFS.put("version", 2);
        AppUsageRuntime.setClockForTest(CLOCK);
        setStatic(AppUsageRuntime.class, "sStarted", true);
        setStatic(AppUsageRuntime.class, "sPrefs", fakePrefs(PREFS));
        setStatic(FirstAppRuntime.class, "sTrackerReady", true);
    }

    private static void setStatic(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> aliveMap() throws Exception {
        Field field = FirstAppRuntime.class.getDeclaredField("PKG_ALIVE");
        field.setAccessible(true);
        return (Map<String, Integer>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> activeSince() throws Exception {
        Field field = AppUsageRuntime.class.getDeclaredField("ACTIVE_SINCE");
        field.setAccessible(true);
        return (Map<String, Long>) field.get(null);
    }

    private static void setAlive(String pkg) throws Exception {
        aliveMap().put(pkg, 1);
    }

    private static void clearAlive(String pkg) throws Exception {
        aliveMap().remove(pkg);
    }

    private static SharedPreferences fakePrefs(final Map<String, Object> store) {
        return (SharedPreferences) Proxy.newProxyInstance(
                AppUsageRuntimeTest.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("getString".equals(name)) {
                            Object value = store.get(args[0]);
                            return value instanceof String ? value : args[1];
                        }
                        if ("getLong".equals(name)) {
                            Object value = store.get(args[0]);
                            return value instanceof Long ? value : args[1];
                        }
                        if ("getInt".equals(name)) {
                            Object value = store.get(args[0]);
                            return value instanceof Integer ? value : args[1];
                        }
                        if ("contains".equals(name)) {
                            return store.containsKey(args[0]);
                        }
                        if ("edit".equals(name)) {
                            return fakeEditor(store);
                        }
                        if ("getAll".equals(name)) {
                            return new HashMap<>(store);
                        }
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(name)) {
                            return proxy == args[0];
                        }
                        if ("toString".equals(name)) {
                            return "FakeSharedPreferences";
                        }
                        return defaultValue(method.getReturnType());
                    }
                });
    }

    private static SharedPreferences.Editor fakeEditor(final Map<String, Object> store) {
        return (SharedPreferences.Editor) Proxy.newProxyInstance(
                AppUsageRuntimeTest.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("putLong".equals(name) || "putString".equals(name) || "putInt".equals(name)
                                || "putBoolean".equals(name) || "putFloat".equals(name)
                                || "putStringSet".equals(name)) {
                            store.put((String) args[0], args[1]);
                            return proxy;
                        }
                        if ("remove".equals(name)) {
                            store.remove(args[0]);
                            return proxy;
                        }
                        if ("clear".equals(name)) {
                            store.clear();
                            return proxy;
                        }
                        if ("commit".equals(name)) {
                            return true;
                        }
                        if ("apply".equals(name)) {
                            return null;
                        }
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(name)) {
                            return proxy == args[0];
                        }
                        if ("toString".equals(name)) {
                            return "FakeEditor";
                        }
                        return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakeClock implements AppUsageRuntime.Clock {
        long wall = 1_700_000_000_000L;
        long elapsed = 5_000_000L;

        @Override
        public long wallNow() {
            return wall;
        }

        @Override
        public long elapsedNow() {
            return elapsed;
        }

        void advance(long millis) {
            wall += millis;
            elapsed += millis;
        }
    }

    /** 引擎替身：只统计被通知了多少次。 */
    public static final class FakeEngine {
        int notifications;

        public void K(java.util.concurrent.ConcurrentHashMap items) {
            notifications++;
        }
    }

    public static final class FakeForegroundInfo {
        public String mForegroundPackageName;

        FakeForegroundInfo(String pkg) {
            mForegroundPackageName = pkg;
        }
    }

    private static final class FakeItem extends AppUsageConditionItem {
        private final String uuid;
        private final String pkg;
        private final long usage;
        private final long range;

        FakeItem(String uuid, String pkg, long usage, long range) {
            this.uuid = uuid;
            this.pkg = pkg;
            this.usage = usage;
            this.range = range;
        }

        @Override
        public String j() {
            return uuid;
        }

        @Override
        public boolean k() {
            return false;
        }

        @Override
        public boolean matches(String value) {
            return pkg.equals(value);
        }

        @Override
        public List<String> selectedPackages() {
            return Collections.singletonList(pkg);
        }

        @Override
        public long usageMillis() {
            return usage;
        }

        @Override
        public long rangeMillis() {
            return range;
        }
    }
}
