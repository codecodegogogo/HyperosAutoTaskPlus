package io.github.hyperosauto.unlockapp.inject;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.miui.autotask.taskitem.TaskItem;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import miuix.appcompat.app.AlertDialog;

/**
 * 「屏幕状态」条件的运行时：记录亮/灭屏切换时刻，到点让引擎重新判定；以及编辑用的对话框。
 *
 * 计时：监听 ACTION_SCREEN_ON / ACTION_SCREEN_OFF，记下切换时刻（elapsedRealtime）。
 * 每次切换后，为所有「目标状态 = 当前状态」的条件项各设一个精确闹钟（AlarmManager.setExact +
 * OnAlarmListener，安全中心是 system uid，不受 Doze 限制；编译用的 android.jar 太老，
 * 这几个 API 走反射），到点把条件项交给引擎；
 * 时长为 0 的直接通知。m() 只看当前状态是否匹配且已持续足够久，所以引擎因其它条件变化
 * 而顺带重新判定时，结论也是对的。
 *
 * 对话框：仿原生「电量」条件的两张卡片（亮屏时间 / 息屏时间），点哪张选哪张，
 * 选中的那张展开数值输入框和「秒 / 分钟 / 小时 / 天」四个单位。
 *
 * 这个类被注入到安全中心的 ClassLoader 里，hook 侧只能通过反射调用这里的 public static 方法。
 */
public final class ScreenStateRuntime {

    private static final String TAG = "HyperAutoEnh";

    private static final Object LOCK = new Object();
    /** 引擎里已启用任务的条件项，key 为 TaskItem.j() */
    private static final ConcurrentHashMap<String, ScreenStateConditionItem> ITEMS = new ConcurrentHashMap<>();
    /** uuid -> 已设的闹钟（AlarmManager.OnAlarmListener 的动态代理），切屏时统一取消重设 */
    private static final Map<String, Object> ALARMS = new HashMap<>();

    private static volatile boolean sStarted;
    private static Context sContext;
    private static AlarmManager sAlarm;
    private static Handler sHandler;
    private static Class<?> sListenerCls;
    private static Method sSetExact;
    private static Method sCancel;
    private static volatile boolean sScreenOn = true;
    /** 当前状态开始的时刻，SystemClock.elapsedRealtime() */
    private static volatile long sSince;
    private static volatile int[] sIcons;

    private ScreenStateRuntime() {
    }

    // ------------------------------------------------------------------ 给 hook 侧用的入口

    public static Class<?> itemClass() {
        return ScreenStateConditionItem.class;
    }

    public static Object newItem() {
        return new ScreenStateConditionItem();
    }

    public static boolean isItem(Object item) {
        return item instanceof ScreenStateConditionItem;
    }

    /** 对应 g2.M0.t()：由条件生成退出条件（相反状态、时长 0） */
    public static Object opposite(Object item) {
        return item instanceof ScreenStateConditionItem ? ((ScreenStateConditionItem) item).opposite() : null;
    }

    /** 对应 g2.M0.e()：条件被编辑后，把相反状态同步到退出条件列表里的对应项 */
    public static int syncOpposite(Object item, List list) {
        if (!(item instanceof ScreenStateConditionItem) || list == null) {
            return -1;
        }
        ScreenStateConditionItem src = (ScreenStateConditionItem) item;
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (o instanceof ScreenStateConditionItem) {
                ((ScreenStateConditionItem) o).set(!src.isScreenOn(), 0, ScreenStateConditionItem.UNIT_SECOND);
                return i;
            }
        }
        return -1;
    }

    /** 对应 b2.j.p()：任务启用时注册条件项 */
    public static void register(Object item) {
        if (!(item instanceof ScreenStateConditionItem)) {
            return;
        }
        ScreenStateConditionItem it = (ScreenStateConditionItem) item;
        String uuid = it.j();
        if (TextUtils.isEmpty(uuid)) {
            return;
        }
        ITEMS.put(uuid, it);
        Log.i(TAG, "register screen " + (it.isScreenOn() ? "on" : "off") + " " + it.durationMillis() + "ms uuid=" + uuid);
        if (sStarted) {
            synchronized (LOCK) {
                schedule(uuid, it);
            }
        }
    }

    /** 对应 b2.j.b1()：任务停用/删除时反注册 */
    public static void unregister(String uuid) {
        if (uuid == null || ITEMS.remove(uuid) == null) {
            return;
        }
        synchronized (LOCK) {
            cancel(uuid);
        }
        Log.i(TAG, "unregister screen uuid=" + uuid);
    }

    /** 在 b2.j 构造完成后调用：接上引擎、开始监听亮/灭屏 */
    public static void start(Context context, Object engine) {
        synchronized (LOCK) {
            if (sStarted) {
                return;
            }
            sStarted = true;
        }
        try {
            sContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            EngineBridge.attach(engine);
            sAlarm = (AlarmManager) sContext.getSystemService(Context.ALARM_SERVICE);
            sHandler = new Handler(Looper.getMainLooper());
            // API 24 起：setExact(int, long, String, OnAlarmListener, Handler) / cancel(OnAlarmListener)
            sListenerCls = Class.forName("android.app.AlarmManager$OnAlarmListener");
            sSetExact = AlarmManager.class.getMethod("setExact",
                    int.class, long.class, String.class, sListenerCls, Handler.class);
            sCancel = AlarmManager.class.getMethod("cancel", sListenerCls);
            PowerManager pm = (PowerManager) sContext.getSystemService(Context.POWER_SERVICE);
            sScreenOn = pm == null || isInteractive(pm);
            sSince = SystemClock.elapsedRealtime();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            sContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent != null && intent.getAction() != null) {
                        onScreen(Intent.ACTION_SCREEN_ON.equals(intent.getAction()));
                    }
                }
            }, filter);
            synchronized (LOCK) {
                for (Map.Entry<String, ScreenStateConditionItem> e : ITEMS.entrySet()) {
                    schedule(e.getKey(), e.getValue());
                }
            }
            Log.i(TAG, "screen tracker started, screenOn=" + sScreenOn);
        } catch (Throwable t) {
            Log.e(TAG, "start screen tracker failed", t);
        }
    }

    // ------------------------------------------------------------------ 判定 / 计时

    static boolean evaluate(ScreenStateConditionItem item) {
        if (!sStarted || item.isScreenOn() != sScreenOn) {
            return false;
        }
        return SystemClock.elapsedRealtime() - sSince >= item.durationMillis();
    }

    private static void onScreen(boolean on) {
        Map<String, TaskItem> immediate = new HashMap<>();
        synchronized (LOCK) {
            if (sScreenOn == on) {
                return;
            }
            sScreenOn = on;
            sSince = SystemClock.elapsedRealtime();
            for (String uuid : ITEMS.keySet()) {
                cancel(uuid);
            }
            for (Map.Entry<String, ScreenStateConditionItem> e : ITEMS.entrySet()) {
                ScreenStateConditionItem it = e.getValue();
                if (it.isScreenOn() != on) {
                    continue;
                }
                if (it.durationMillis() == 0) {
                    immediate.put(e.getKey(), it);
                } else {
                    schedule(e.getKey(), it);
                }
            }
        }
        EngineBridge.notify(immediate, "screen " + (on ? "on" : "off"));
    }

    /** 目标状态 = 当前状态时，按剩余时长设闹钟；已经到点的直接通知 */
    private static void schedule(final String uuid, final ScreenStateConditionItem item) {
        cancel(uuid);
        if (sAlarm == null || item.isScreenOn() != sScreenOn) {
            return;
        }
        long due = sSince + item.durationMillis();
        long now = SystemClock.elapsedRealtime();
        if (due <= now) {
            Map<String, TaskItem> hit = new HashMap<>();
            hit.put(uuid, item);
            EngineBridge.notify(hit, "screen state already lasted");
            return;
        }
        final Runnable onAlarm = new Runnable() {
            @Override
            public void run() {
                synchronized (LOCK) {
                    ALARMS.remove(uuid);
                }
                if (!ITEMS.containsKey(uuid)) {
                    return;
                }
                Map<String, TaskItem> hit = new HashMap<>();
                hit.put(uuid, item);
                EngineBridge.notify(hit, "screen timer fired");
            }
        };
        try {
            Object listener = newAlarmListener(onAlarm);
            sSetExact.invoke(sAlarm, AlarmManager.ELAPSED_REALTIME_WAKEUP, due, "HyperAutoEnh:" + uuid, listener, sHandler);
            ALARMS.put(uuid, listener);
        } catch (Throwable t) {
            Log.e(TAG, "set screen alarm failed", t);
        }
    }

    private static void cancel(String uuid) {
        Object old = ALARMS.remove(uuid);
        if (old != null && sAlarm != null && sCancel != null) {
            try {
                sCancel.invoke(sAlarm, old);
            } catch (Throwable ignored) {
                // 已经触发过的闹钟取消会失败，无所谓
            }
        }
    }

    /** OnAlarmListener 只有一个 onAlarm()，用动态代理实现；AlarmManager 内部按引用存取，hashCode/equals 用恒等 */
    private static Object newAlarmListener(final Runnable onAlarm) {
        return Proxy.newProxyInstance(ScreenStateRuntime.class.getClassLoader(), new Class<?>[]{sListenerCls},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("onAlarm".equals(name)) {
                            try {
                                onAlarm.run();
                            } catch (Throwable t) {
                                Log.e(TAG, "screen alarm callback failed", t);
                            }
                            return null;
                        }
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(name)) {
                            return args != null && args.length == 1 && proxy == args[0];
                        }
                        if ("toString".equals(name)) {
                            return "ScreenStateRuntime.AlarmListener";
                        }
                        return null;
                    }
                });
    }

    /** PowerManager.isInteractive() 是 API 20，旧 android.jar 里没有；找不到就退回 isScreenOn() */
    private static boolean isInteractive(PowerManager pm) {
        try {
            return (Boolean) PowerManager.class.getMethod("isInteractive").invoke(pm);
        } catch (Throwable t) {
            return pm.isScreenOn();
        }
    }

    // ------------------------------------------------------------------ 编辑对话框

    /**
     * 弹「亮屏时间 / 息屏时间」两张卡片，默认选中 item 当前的状态并填入它的时长。
     * 点「确定」才写回 item 并回调 onConfirm；取消则 item 原样不动。
     */
    public static void pickAndApply(final Context context, Object itemObj, final Runnable onConfirm) {
        if (context == null || !(itemObj instanceof ScreenStateConditionItem)) {
            return;
        }
        final ScreenStateConditionItem item = (ScreenStateConditionItem) itemObj;
        final Card on = new Card(context, true, item);
        final Card off = new Card(context, false, item);
        on.other = off;
        off.other = on;
        (item.isScreenOn() ? on : off).select(true);
        (item.isScreenOn() ? off : on).select(false);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(context, 7), 0, 0);
        column.addView(on.root, cardParams(context));
        View gap = new View(context);
        column.addView(gap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 11)));
        column.addView(off.root, cardParams(context));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(column);

        new AlertDialog.Builder(context)
                .setTitle(title())
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Card picked = on.selected ? on : off;
                        item.set(picked.screenOn, picked.amount(), picked.unit);
                        if (onConfirm != null) {
                            onConfirm.run();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static LinearLayout.LayoutParams cardParams(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(c, 13);
        lp.rightMargin = dp(c, 13);
        return lp;
    }

    /** 一张可选中的卡片：勾选图标 + 标题，展开后是「数值输入框 + 单位」 */
    private static final class Card {
        final boolean screenOn;
        final LinearLayout root;
        final ImageView check;
        final TextView title;
        final LinearLayout body;
        final EditText input;
        final TextView[] units = new TextView[4];
        int unit;
        boolean selected;
        Card other;

        Card(final Context c, boolean on, ScreenStateConditionItem item) {
            screenOn = on;
            unit = item.getUnit();

            root = new LinearLayout(c);
            root.setOrientation(LinearLayout.VERTICAL);
            int bg = appDrawable(c, "auto_task_select_address_item");
            if (bg != 0) {
                root.setBackgroundResource(bg);
            }

            FrameLayout header = new FrameLayout(c);
            check = new ImageView(c);
            int checkIcon = appDrawable(c, "auto_task_select_icon");
            if (checkIcon != 0) {
                check.setImageResource(checkIcon);
            }
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
            cp.leftMargin = dp(c, 16);
            header.addView(check, cp);
            title = new TextView(c);
            title.setText(on ? onTitle() : offTitle());
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
            tp.leftMargin = dp(c, 45);
            tp.rightMargin = dp(c, 16);
            tp.topMargin = dp(c, 17);
            tp.bottomMargin = dp(c, 17);
            header.addView(title, tp);
            root.addView(header);

            body = new LinearLayout(c);
            body.setOrientation(LinearLayout.HORIZONTAL);
            body.setGravity(Gravity.CENTER_VERTICAL);
            body.setPadding(dp(c, 45), 0, dp(c, 16), dp(c, 18));
            input = new EditText(c);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
            input.setSingleLine();
            input.setGravity(Gravity.CENTER);
            input.setHint("0");
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            input.setSelectAllOnFocus(true);
            // 两张卡片都预填当前的时长，切换卡片时不用重新输入
            input.setText(String.valueOf(item.getAmount()));
            body.addView(input, new LinearLayout.LayoutParams(dp(c, 72), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout chips = new LinearLayout(c);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            String[] names = unitNames();
            for (int i = 0; i < 4; i++) {
                final int idx = i;
                TextView chip = new TextView(c);
                chip.setText(names[i]);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                chip.setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5));
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        unit = idx;
                        styleUnits(c);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = dp(c, 6);
                chips.addView(chip, lp);
                units[i] = chip;
            }
            styleUnits(c);
            body.addView(chips, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(body);

            root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!selected) {
                        select(true);
                        if (other != null) {
                            other.select(false);
                        }
                    }
                }
            });
        }

        void select(boolean sel) {
            selected = sel;
            Context c = root.getContext();
            root.setSelected(sel);
            check.setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
            body.setVisibility(sel ? View.VISIBLE : View.GONE);
            int normal = appColor(c, "task_default_task_title_color", Color.BLACK);
            int accent = appColor(c, "task_default_add_action_text_color", 0xFF0D84FF);
            title.setTextColor(sel ? accent : normal);
        }

        void styleUnits(Context c) {
            int accent = appColor(c, "task_address_select_text_color", 0xFF0D84FF);
            int muted = appColor(c, "task_un_select_text_color", 0x4D000000);
            for (int i = 0; i < units.length; i++) {
                GradientDrawable d = new GradientDrawable();
                d.setCornerRadius(dp(c, 14));
                if (i == unit) {
                    d.setColor(accent);
                    units[i].setTextColor(Color.WHITE);
                } else {
                    d.setColor(Color.TRANSPARENT);
                    d.setStroke(dp(c, 1), muted);
                    units[i].setTextColor(muted);
                }
                units[i].setBackground(d);
            }
        }

        int amount() {
            try {
                String s = input.getText() == null ? "" : input.getText().toString().trim();
                return s.isEmpty() ? 0 : Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    // ------------------------------------------------------------------ 文案 / 图标

    static String title() {
        return zh() ? "屏幕状态" : "Screen state";
    }

    static String summary(ScreenStateConditionItem item) {
        boolean on = item.isScreenOn();
        if (item.durationMillis() == 0) {
            if (zh()) {
                return on ? "亮屏时" : "息屏时";
            }
            return on ? "When screen turns on" : "When screen turns off";
        }
        String dur = item.getAmount() + (zh() ? " " : " ") + unitNames()[item.getUnit()];
        if (zh()) {
            return (on ? "亮屏 " : "息屏 ") + dur;
        }
        return (on ? "Screen on for " : "Screen off for ") + dur;
    }

    private static String onTitle() {
        return zh() ? "亮屏时间" : "Screen on for";
    }

    private static String offTitle() {
        return zh() ? "息屏时间" : "Screen off for";
    }

    private static String[] unitNames() {
        return zh()
                ? new String[]{"秒", "分钟", "小时", "天"}
                : new String[]{"sec", "min", "hour", "day"};
    }

    /** 0 正常、1 灰色、2 半透明（列表里被禁用时），借原生「锁屏」条件的三张图 */
    static int icon(int which) {
        int[] icons = sIcons;
        if (icons == null) {
            Context c = context();
            icons = new int[]{
                    c == null ? 0 : appDrawable(c, "auto_task_icon_lock_screen"),
                    c == null ? 0 : appDrawable(c, "auto_task_icon_lock_screen_grey"),
                    c == null ? 0 : appDrawable(c, "auto_task_icon_lock_screen_tran")};
            for (int i = 0; i < icons.length; i++) {
                if (icons[i] == 0) {
                    icons[i] = android.R.drawable.ic_lock_idle_lock;
                }
            }
            if (c != null) {
                sIcons = icons;
            }
        }
        return icons[Math.min(Math.max(which, 0), 2)];
    }

    private static Context context() {
        if (sContext != null) {
            return sContext;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                sContext = (Context) app;
            }
        } catch (Throwable ignored) {
            // 没有就返回 null
        }
        return sContext;
    }

    private static int appDrawable(Context c, String name) {
        try {
            return c.getResources().getIdentifier(name, "drawable", c.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int appColor(Context c, String name, int fallback) {
        try {
            Resources res = c.getResources();
            int id = res.getIdentifier(name, "color", c.getPackageName());
            return id == 0 ? fallback : res.getColor(id);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static boolean zh() {
        return "zh".equals(Locale.getDefault().getLanguage());
    }
}
