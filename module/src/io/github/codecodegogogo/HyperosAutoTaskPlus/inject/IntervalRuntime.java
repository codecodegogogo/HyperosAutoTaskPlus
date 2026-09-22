package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import com.miui.autotask.taskitem.TaskItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/**
 * 「时间间隔」条件的运行时：每个已启用的条件项各自一个反复设置的精确闹钟。
 *
 * 到点后：记下触发时刻 → 通知引擎重新判定这个条件项 → 立刻设下一次闹钟。
 * m() 在触发时刻之后 {@link #WINDOW_MILLIS} 内为 true。
 */
public final class IntervalRuntime {

    private static final String TAG = "HyperAutoEnh";
    /** 到点后多久内 m() 算满足；引擎在自己的线程里异步判定，留几秒余量 */
    private static final long WINDOW_MILLIS = 5_000L;

    private static final Object LOCK = new Object();
    private static final ConcurrentHashMap<String, IntervalConditionItem> ITEMS = new ConcurrentHashMap<>();
    /** uuid -> 最近一次到点时刻（elapsedRealtime） */
    private static final Map<String, Long> FIRED = new ConcurrentHashMap<>();
    private static final Alarms ALARMS = new Alarms("HyperAutoEnh:interval:");

    private static volatile boolean sStarted;
    private static volatile int[] sIcons;

    private IntervalRuntime() {
    }

    // ------------------------------------------------------------------ 给 hook 侧用的入口

    public static Class<?> itemClass(String key) {
        return FirstAppKeys.KEY_INTERVAL_CONDITION.equals(key) ? IntervalConditionItem.class : null;
    }

    public static Object newItem(String key) {
        return FirstAppKeys.KEY_INTERVAL_CONDITION.equals(key) ? new IntervalConditionItem() : null;
    }

    public static boolean isItem(Object item) {
        return item instanceof IntervalConditionItem;
    }

    /** 周期触发没有「相反状态」，不能作为退出条件 */
    public static Object opposite(Object item) {
        return null;
    }

    public static int syncOpposite(Object item, List list) {
        return -1;
    }

    public static void register(Object item) {
        if (!(item instanceof IntervalConditionItem)) {
            return;
        }
        IntervalConditionItem it = (IntervalConditionItem) item;
        String uuid = it.j();
        if (TextUtils.isEmpty(uuid) || it.k()) {
            return;
        }
        ITEMS.put(uuid, it);
        Log.i(TAG, "register interval " + it.intervalMillis() + "ms uuid=" + uuid);
        if (sStarted) {
            synchronized (LOCK) {
                schedule(uuid, it);
            }
        }
    }

    public static void unregister(String uuid) {
        if (uuid == null || ITEMS.remove(uuid) == null) {
            return;
        }
        FIRED.remove(uuid);
        synchronized (LOCK) {
            ALARMS.cancel(uuid);
        }
        Log.i(TAG, "unregister interval uuid=" + uuid);
    }

    public static void start(Context context, Object engine) {
        synchronized (LOCK) {
            if (sStarted) {
                return;
            }
            sStarted = true;
        }
        try {
            Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            EngineBridge.attach(engine);
            if (!ALARMS.init(app)) {
                Log.e(TAG, "interval alarms unavailable");
                return;
            }
            synchronized (LOCK) {
                for (Map.Entry<String, IntervalConditionItem> e : ITEMS.entrySet()) {
                    schedule(e.getKey(), e.getValue());
                }
            }
            Log.i(TAG, "interval runtime started, items=" + ITEMS.size());
        } catch (Throwable t) {
            Log.e(TAG, "start interval runtime failed", t);
        }
    }

    // ------------------------------------------------------------------ 判定 / 计时

    static boolean evaluate(IntervalConditionItem item) {
        String uuid = item.j();
        Long at = uuid == null ? null : FIRED.get(uuid);
        return at != null && SystemClock.elapsedRealtime() - at <= WINDOW_MILLIS;
    }

    private static void schedule(final String uuid, final IntervalConditionItem item) {
        long due = SystemClock.elapsedRealtime() + item.intervalMillis();
        ALARMS.set(uuid, due, new Runnable() {
            @Override
            public void run() {
                IntervalConditionItem current = ITEMS.get(uuid);
                if (current == null) {
                    return;
                }
                FIRED.put(uuid, SystemClock.elapsedRealtime());
                synchronized (LOCK) {
                    schedule(uuid, current);
                }
                Map<String, TaskItem> hit = new HashMap<>();
                hit.put(uuid, current);
                EngineBridge.notify(hit, "interval fired");
            }
        });
    }

    // ------------------------------------------------------------------ 编辑对话框

    public static void pickAndApply(final Context context, Object itemObj, final Runnable onConfirm) {
        if (context == null || !(itemObj instanceof IntervalConditionItem)) {
            return;
        }
        final IntervalConditionItem item = (IntervalConditionItem) itemObj;
        List<CardPicker.Spec> specs = new ArrayList<>();
        specs.add(new CardPicker.Spec(cardTitle(), InjectUi.durationUnits(), item.getAmount(), item.getUnit()));
        CardPicker.show(context, title(), specs, 0, new CardPicker.OnPicked() {
            @Override
            public void onPicked(int index, int amount, int unit) {
                if (amount <= 0) {
                    return;
                }
                item.set(amount, unit);
                if (onConfirm != null) {
                    onConfirm.run();
                }
            }
        });
    }

    // ------------------------------------------------------------------ 文案 / 图标

    static String title() {
        return InjectUi.zh() ? "时间间隔" : "Time interval";
    }

    private static String cardTitle() {
        return InjectUi.zh() ? "每隔" : "Every";
    }

    static String summary(IntervalConditionItem item) {
        String unit = InjectUi.durationUnits()[Math.min(Math.max(item.getUnit(), 0), 3)];
        return (InjectUi.zh() ? "每 " : "Every ") + item.getAmount() + " " + unit;
    }

    static int icon(int which) {
        int[] icons = sIcons;
        if (icons == null) {
            icons = InjectUi.icons("auto_task_icon_custom_time", android.R.drawable.ic_menu_recent_history);
            if (InjectUi.context() != null) {
                sIcons = icons;
            }
        }
        return icons[Math.min(Math.max(which, 0), 2)];
    }
}
