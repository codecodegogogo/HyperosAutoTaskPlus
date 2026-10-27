package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.miui.autotask.taskitem.TaskItem;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/**
 * 「应用使用时长」的运行时。
 *
 * 统计模型：
 *   - 第一个时间是滚动统计范围，例如最近 1 天；
 *   - 第二个时间是该范围内累计使用时长，例如 30 分钟；
 *   - 应用第一次进入前台且进程存活后开始一段使用区间；
 *   - 进程进入后台不结束区间，只有进程被杀掉或强制停止才结束；
 *   - 多个所选应用的存活区间可以同时累计。
 *
 * 使用区间写入安全服务私有 SharedPreferences，进程重启后仍能按原统计范围计算。
 * 到点使用精确闹钟通知自动任务引擎。
 */
public final class AppUsageRuntime {

    private static final String TAG = "HyperAutoEnh";
    private static final String PREFS = "hyper_auto_app_usage";
    private static final long DEFAULT_KEEP_MILLIS = 30L * 24L * 60L * 60_000L;

    private static final Object LOCK = new Object();
    private static final ConcurrentHashMap<String, AppUsageConditionItem> ITEMS = new ConcurrentHashMap<>();
    private static final Alarms ALARMS = new Alarms("HyperAutoEnh:app-usage:");
    private static final Map<String, List<Slice>> HISTORY = new HashMap<>();
    private static final Map<String, Long> ACTIVE_SINCE = new HashMap<>();
    private static final Set<String> LOADED = new HashSet<>();

    private static volatile boolean sStarted;
    private static Context sContext;
    private static SharedPreferences sPrefs;
    private static String sForegroundPkg = "";

    private AppUsageRuntime() {
    }

    // ------------------------------------------------------------------ 给 hook 侧用的入口

    public static Class<?> itemClass(String key) {
        return FirstAppKeys.KEY_APP_USAGE_CONDITION.equals(key) ? AppUsageConditionItem.class : null;
    }

    public static Object newItem(String key) {
        return FirstAppKeys.KEY_APP_USAGE_CONDITION.equals(key) ? new AppUsageConditionItem() : null;
    }

    public static boolean isItem(Object item) {
        return item instanceof AppUsageConditionItem;
    }

    /** 使用时长没有自然的反向状态，不生成退出条件。 */
    public static Object opposite(Object item) {
        return null;
    }

    public static int syncOpposite(Object item, List list) {
        return -1;
    }

    public static void register(Object item) {
        if (!(item instanceof AppUsageConditionItem)) {
            return;
        }
        AppUsageConditionItem usage = (AppUsageConditionItem) item;
        String uuid = usage.j();
        if (TextUtils.isEmpty(uuid) || usage.k()) {
            return;
        }

        Map<String, TaskItem> hits = new HashMap<>();
        long now = now();
        synchronized (LOCK) {
            ITEMS.put(uuid, usage);
            for (String pkg : usage.selectedPackages()) {
                ensureLoadedLocked(pkg);
                if (FirstAppRuntime.isPackageAlive(pkg)) {
                    startActiveLocked(pkg, now);
                }
            }
            if (sStarted) {
                scheduleAllLocked(now, hits);
            }
        }
        Log.i(TAG, "register app usage range=" + usage.rangeMillis()
                + " usage=" + usage.usageMillis() + " uuid=" + uuid);
        notifyHits(hits, "app usage registered");
    }

    public static void unregister(String uuid) {
        if (uuid == null || ITEMS.remove(uuid) == null) {
            return;
        }
        synchronized (LOCK) {
            ALARMS.cancel(uuid);
        }
        Log.i(TAG, "unregister app usage uuid=" + uuid);
    }

    /** 在自动任务引擎构造完成后调用。 */
    public static void start(Context context, Object engine) {
        synchronized (LOCK) {
            if (sStarted) {
                return;
            }
            sStarted = true;
        }
        try {
            sContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            sPrefs = sContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            EngineBridge.attach(engine);
            ALARMS.init(sContext);

            Map<String, TaskItem> hits = new HashMap<>();
            long now = now();
            synchronized (LOCK) {
                sForegroundPkg = currentForegroundPackage();
                for (AppUsageConditionItem item : ITEMS.values()) {
                    for (String pkg : item.selectedPackages()) {
                        ensureLoadedLocked(pkg);
                        if (FirstAppRuntime.isPackageAlive(pkg)) {
                            startActiveLocked(pkg, now);
                        }
                    }
                }
                scheduleAllLocked(now, hits);
            }
            notifyHits(hits, "app usage started");
            Log.i(TAG, "app usage tracker started, foreground=" + sForegroundPkg);
        } catch (Throwable t) {
            Log.e(TAG, "start app usage tracker failed", t);
        }
    }

    /** 前台变化只用于确认“应用已打开”，进入后台后不会结束使用区间。 */
    public static void onForegroundInfo(Object foregroundInfo) {
        if (foregroundInfo == null) {
            return;
        }
        String pkg = stringField(foregroundInfo, "mForegroundPackageName");
        if (TextUtils.isEmpty(pkg)) {
            return;
        }

        Map<String, TaskItem> hits = new HashMap<>();
        long now = now();
        synchronized (LOCK) {
            if (!sStarted) {
                return;
            }
            sForegroundPkg = pkg;
            if (hasItemForPackageLocked(pkg) && FirstAppRuntime.isPackageAlive(pkg)) {
                ensureLoadedLocked(pkg);
                if (startActiveLocked(pkg, now)) {
                    scheduleAllLocked(now, hits);
                }
            }
        }
        notifyHits(hits, "foreground " + pkg);
    }

    /** 由 FirstAppRuntime 的进程存活变化调用；后台存活继续计时，进程消失时结束区间。 */
    public static void onPackageAlive(String pkg, boolean alive) {
        if (TextUtils.isEmpty(pkg)) {
            return;
        }
        Map<String, TaskItem> hits = new HashMap<>();
        long now = now();
        synchronized (LOCK) {
            if (!sStarted) {
                return;
            }
            if (!hasItemForPackageLocked(pkg) && !ACTIVE_SINCE.containsKey(pkg)) {
                return;
            }
            ensureLoadedLocked(pkg);
            boolean changed;
            if (alive) {
                changed = hasItemForPackageLocked(pkg)
                        && TextUtils.equals(sForegroundPkg, pkg)
                        && startActiveLocked(pkg, now);
            } else {
                changed = endActiveLocked(pkg, now);
            }
            if (changed) {
                scheduleAllLocked(now, hits);
            }
        }
        notifyHits(hits, "process " + (alive ? "alive" : "gone") + " " + pkg);
    }

    private static boolean hasItemForPackageLocked(String pkg) {
        for (AppUsageConditionItem item : ITEMS.values()) {
            if (item.matches(pkg)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 条件判定 / 计时

    static boolean evaluate(AppUsageConditionItem item) {
        long now = now();
        synchronized (LOCK) {
            if (!sStarted) {
                return false;
            }
            for (String pkg : item.selectedPackages()) {
                ensureLoadedLocked(pkg);
            }
            return totalUsageLocked(item, now) >= item.usageMillis();
        }
    }

    private static boolean startActiveLocked(String pkg, long now) {
        if (ACTIVE_SINCE.containsKey(pkg)) {
            return false;
        }
        ACTIVE_SINCE.put(pkg, now);
        if (sPrefs != null) {
            sPrefs.edit().putLong(activeKey(pkg), now).apply();
        }
        return true;
    }

    private static boolean endActiveLocked(String pkg, long now) {
        Long start = ACTIVE_SINCE.remove(pkg);
        if (sPrefs != null) {
            sPrefs.edit().remove(activeKey(pkg)).apply();
        }
        if (start == null) {
            return false;
        }
        if (now > start) {
            List<Slice> slices = historyLocked(pkg);
            slices.add(new Slice(start, now));
            saveHistoryLocked(pkg, now);
        }
        return true;
    }

    private static long totalUsageLocked(AppUsageConditionItem item, long now) {
        long total = 0L;
        long range = item.rangeMillis();
        for (String pkg : item.selectedPackages()) {
            total += usageInWindowLocked(pkg, now, range);
        }
        return total;
    }

    /** 统计 [now-range, now] 内某个应用的使用区间，后台存活时间同样计入。 */
    private static long usageInWindowLocked(String pkg, long now, long range) {
        long windowStart = now - range;
        long total = 0L;
        List<Slice> slices = HISTORY.get(pkg);
        if (slices != null) {
            for (Slice slice : slices) {
                total += overlap(slice.start, slice.end, windowStart, now);
            }
        }
        Long active = ACTIVE_SINCE.get(pkg);
        if (active != null) {
            total += overlap(active, now, windowStart, now);
        }
        return Math.max(0L, total);
    }

    static long overlap(long start, long end, long windowStart, long windowEnd) {
        long from = Math.max(start, windowStart);
        long to = Math.min(end, windowEnd);
        return Math.max(0L, to - from);
    }

    private static void scheduleAllLocked(long now, Map<String, TaskItem> hits) {
        for (String uuid : ITEMS.keySet()) {
            ALARMS.cancel(uuid);
        }
        for (Map.Entry<String, AppUsageConditionItem> entry : ITEMS.entrySet()) {
            scheduleLocked(entry.getKey(), entry.getValue(), now, hits);
        }
    }

    private static void scheduleLocked(final String uuid, final AppUsageConditionItem item,
                                       long now, Map<String, TaskItem> hits) {
        ALARMS.cancel(uuid);
        long total = totalUsageLocked(item, now);
        long target = item.usageMillis();
        if (total >= target) {
            if (hits != null) {
                hits.put(uuid, item);
            }
            return;
        }

        int activeCount = 0;
        for (String pkg : item.selectedPackages()) {
            if (ACTIVE_SINCE.containsKey(pkg)) {
                activeCount++;
            }
        }
        if (activeCount == 0) {
            return;
        }

        long remaining = target - total;
        long delay = Math.max(1L, (remaining + activeCount - 1L) / activeCount);
        ALARMS.set(uuid, SystemClock.elapsedRealtime() + delay, new Runnable() {
            @Override
            public void run() {
                onAlarm(uuid);
            }
        });
    }

    private static void onAlarm(String uuid) {
        Map<String, TaskItem> hits = new HashMap<>();
        long now = now();
        synchronized (LOCK) {
            AppUsageConditionItem item = ITEMS.get(uuid);
            if (item != null) {
                if (totalUsageLocked(item, now) >= item.usageMillis()) {
                    hits.put(uuid, item);
                } else {
                    scheduleLocked(uuid, item, now, hits);
                }
            }
        }
        notifyHits(hits, "app usage timer");
    }

    private static void notifyHits(Map<String, TaskItem> hits, String reason) {
        if (!hits.isEmpty()) {
            EngineBridge.notify(hits, reason);
        }
    }

    // ------------------------------------------------------------------ 本地历史

    private static void ensureLoadedLocked(String pkg) {
        if (sPrefs == null || LOADED.contains(pkg)) {
            return;
        }
        LOADED.add(pkg);
        List<Slice> slices = historyLocked(pkg);
        String value = sPrefs.getString(historyKey(pkg), "");
        if (!TextUtils.isEmpty(value)) {
            for (String pair : value.split(";")) {
                int comma = pair.indexOf(',');
                if (comma <= 0) {
                    continue;
                }
                try {
                    long start = Long.parseLong(pair.substring(0, comma));
                    long end = Long.parseLong(pair.substring(comma + 1));
                    if (end > start) {
                        slices.add(new Slice(start, end));
                    }
                } catch (NumberFormatException ignored) {
                    // 损坏的单条记录直接跳过
                }
            }
        }
        long active = sPrefs.getLong(activeKey(pkg), 0L);
        if (active > 0L) {
            if (FirstAppRuntime.isPackageAlive(pkg)) {
                ACTIVE_SINCE.put(pkg, active);
            } else {
                sPrefs.edit().remove(activeKey(pkg)).apply();
            }
        }
    }

    private static List<Slice> historyLocked(String pkg) {
        List<Slice> slices = HISTORY.get(pkg);
        if (slices == null) {
            slices = new ArrayList<>();
            HISTORY.put(pkg, slices);
        }
        return slices;
    }

    private static void saveHistoryLocked(String pkg, long now) {
        if (sPrefs == null) {
            return;
        }
        List<Slice> slices = historyLocked(pkg);
        long keep = keepMillisLocked();
        long cutoff = now - keep;
        StringBuilder value = new StringBuilder();
        for (int i = slices.size() - 1; i >= 0; i--) {
            Slice slice = slices.get(i);
            if (slice.end <= cutoff) {
                slices.remove(i);
                continue;
            }
            if (value.length() > 0) {
                value.append(';');
            }
            value.append(slice.start).append(',').append(slice.end);
        }
        sPrefs.edit().putString(historyKey(pkg), value.toString()).apply();
    }

    private static long keepMillisLocked() {
        long keep = 0L;
        for (AppUsageConditionItem item : ITEMS.values()) {
            keep = Math.max(keep, item.rangeMillis());
        }
        return keep <= 0L ? DEFAULT_KEEP_MILLIS : keep;
    }

    private static String historyKey(String pkg) {
        return "history:" + pkg;
    }

    private static String activeKey(String pkg) {
        return "active:" + pkg;
    }

    // ------------------------------------------------------------------ 编辑对话框

    public static void pickAndApply(final Context context, Object itemObj, final Runnable onConfirm) {
        if (context == null || !(itemObj instanceof AppUsageConditionItem)) {
            return;
        }
        final AppUsageConditionItem item = (AppUsageConditionItem) itemObj;
        List<CardPicker.Spec> specs = new ArrayList<>();
        specs.add(new CardPicker.Spec(rangeTitle(), unitNames(),
                item.getRangeAmount(), item.getRangeUnit()));
        specs.add(new CardPicker.Spec(usageTitle(), unitNames(),
                item.getUsageAmount(), item.getUsageUnit()));
        CardPicker.showAll(context, title(), specs, 0, new CardPicker.OnPickedAll() {
            @Override
            public void onPicked(int[] amounts, int[] units) {
                int rangeAmount = amounts[0];
                int rangeUnit = units[0];
                int usageAmount = amounts[1];
                int usageUnit = units[1];
                if (rangeAmount <= 0 || usageAmount <= 0) {
                    return;
                }
                if (AppUsageConditionItem.durationMillis(usageAmount, usageUnit)
                        > AppUsageConditionItem.durationMillis(rangeAmount, rangeUnit)) {
                    Toast.makeText(context, InjectUi.zh()
                            ? "使用时长不能超过统计范围"
                            : "Usage time cannot exceed the range", Toast.LENGTH_LONG).show();
                    return;
                }
                item.setRange(rangeAmount, rangeUnit);
                item.setUsage(usageAmount, usageUnit);
                if (onConfirm != null) {
                    onConfirm.run();
                }
            }
        });
    }

    // ------------------------------------------------------------------ 文案

    static String title() {
        return InjectUi.zh() ? "应用使用时长" : "App usage time";
    }

    private static String rangeTitle() {
        return InjectUi.zh() ? "统计范围" : "Time range";
    }

    private static String usageTitle() {
        return InjectUi.zh() ? "使用时长" : "Usage time";
    }

    static String summary(AppUsageConditionItem item) {
        String range = item.getRangeAmount() + " "
                + unitNames()[clampUnit(item.getRangeUnit())];
        String usage = item.getUsageAmount() + " "
                + unitNames()[clampUnit(item.getUsageUnit())];
        String text = InjectUi.zh()
                ? range + "内累计使用 " + usage
                : "Use " + usage + " within " + range;
        String apps = item.appNamesSummary();
        if (TextUtils.isEmpty(apps)) {
            return text;
        }
        return text + (InjectUi.zh() ? "：" : ": ") + apps;
    }

    private static String[] unitNames() {
        return InjectUi.zh()
                ? new String[]{"分钟", "小时", "天", "周", "月"}
                : new String[]{"min", "hour", "day", "week", "month"};
    }

    private static int clampUnit(int unit) {
        return Math.min(Math.max(unit, AppUsageConditionItem.UNIT_MINUTE),
                AppUsageConditionItem.UNIT_MONTH);
    }

    // ------------------------------------------------------------------ 系统接口

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String currentForegroundPackage() {
        try {
            Class<?> manager = Class.forName("miui.process.ProcessManager", false,
                    AppUsageRuntime.class.getClassLoader());
            Method get = manager.getMethod("getForegroundInfo");
            Object info = get.invoke(null);
            return stringField(info, "mForegroundPackageName");
        } catch (Throwable t) {
            Log.w(TAG, "get current foreground app failed", t);
            return "";
        }
    }

    private static String stringField(Object target, String name) {
        if (target == null) {
            return "";
        }
        try {
            Field field = target.getClass().getField(name);
            Object value = field.get(target);
            return value instanceof String ? (String) value : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static final class Slice {
        final long start;
        final long end;

        Slice(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }
}
