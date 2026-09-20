package io.github.hyperosauto.unlockapp.inject;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

import miuix.appcompat.app.AlertDialog;

/**
 * 「隐身模式」结果的运行时：真正去切换隐身模式，以及给编辑页弹「开启/关闭」单选框。
 *
 * 隐身模式属于权限中心（com.miui.permcenter），系统设置页里那个开关
 * （InvisibleModeActivity）和内置磁贴（InvisibleModeTileService）切换时做的事完全一样：
 *   1. com.miui.permcenter.v$a.d(Context, boolean)
 *        -> Settings.Secure "key_invisible_mode_state" = 1/0
 *        -> persist.sys.invisible_mode = "1"/"0"
 *      （内部先看 v.n：本机是否支持隐身模式，不支持则什么都不做）
 *   2. startService(InvisibleModeService)
 *        服务读回上面的开关，对相机/麦克风/定位等 AppOps 下发用户限制，并发通知、同步状态栏
 *   3. 广播 miui.security.invisible.switch（仅本应用内）
 *        让正开着的设置页 / 磁贴刷新自己的勾选状态
 * 这里按同样的顺序做一遍。v$a 是混淆名，找不到时退回直接写设置项 + 属性，
 * 效果与 v$a.d 一致。InvisibleModeService 和自动任务引擎同在 remote 进程。
 *
 * 这个类被注入到安全中心的 ClassLoader 里，hook 侧只能通过反射调用这里的 public static 方法，
 * 参数类型统一用 Context / Object / Runnable，避免跨 ClassLoader 的类型不匹配。
 */
public final class InvisibleModeRuntime {

    private static final String TAG = "HyperAutoEnh";

    private static final String SETTING_STATE = "key_invisible_mode_state";
    private static final String PROP_STATE = "persist.sys.invisible_mode";
    /** 静态字段 n：本机是否支持隐身模式 */
    private static final String CLASS_FEATURE = "com.miui.permcenter.v";
    /** d(Context, boolean) 写开关；b(Context) 读开关 */
    private static final String CLASS_STATE = "com.miui.permcenter.v$a";
    private static final String CLASS_SERVICE = "com.miui.permcenter.service.InvisibleModeService";
    private static final String ACTION_SWITCHED = "miui.security.invisible.switch";

    /** 单选框里的两项，顺序与 SwitchTypeItem.u() 一致：0 = 开启，1 = 关闭 */
    private static final int INDEX_ON = 0;

    private static Context sContext;
    private static volatile int sIcon;

    private InvisibleModeRuntime() {
    }

    // ------------------------------------------------------------------ 给 hook 侧用的入口

    /** 注入完成后由 hook 侧调用一次，传入 Application 的 Context */
    public static void init(Context context) {
        if (context != null && sContext == null) {
            Context app = context.getApplicationContext();
            sContext = app != null ? app : context;
        }
    }

    public static Class<?> itemClass() {
        return InvisibleModeResultItem.class;
    }

    public static Object newItem() {
        return new InvisibleModeResultItem();
    }

    public static boolean isItem(Object item) {
        return item instanceof InvisibleModeResultItem;
    }

    /**
     * 弹出「开启 / 关闭」单选框，默认选中 item 当前的值。
     * 点「确定」才把选择写回 item 并回调 onConfirm；取消则 item 原样不动。
     * 对话框样式沿用安全中心自己的 miuix AlertDialog，和原生 WLAN/定位的一致。
     */
    public static void pickAndApply(Context context, Object itemObj, final Runnable onConfirm) {
        if (context == null || !(itemObj instanceof InvisibleModeResultItem)) {
            return;
        }
        final InvisibleModeResultItem item = (InvisibleModeResultItem) itemObj;
        final int[] picked = {item.u()};
        new AlertDialog.Builder(context)
                .setTitle(title())
                .setSingleChoiceItems(new CharSequence[]{onText(), offText()}, picked[0],
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                picked[0] = which;
                            }
                        })
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        item.v(picked[0] == INDEX_ON);
                        if (onConfirm != null) {
                            onConfirm.run();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ 切换隐身模式

    /** 当前隐身模式是否开启（与 v$a.b 相同的判据） */
    public static boolean isOn() {
        Context ctx = context();
        return ctx != null
                && Settings.Secure.getInt(ctx.getContentResolver(), SETTING_STATE, 0) == 1;
    }

    static void apply(boolean on) {
        Context ctx = context();
        if (ctx == null) {
            Log.e(TAG, "invisible mode: no context, skip");
            return;
        }
        if (!supported()) {
            Log.w(TAG, "invisible mode: not supported on this device (v.n == false), skip");
            return;
        }
        if (!writeState(ctx, on)) {
            Settings.Secure.putInt(ctx.getContentResolver(), SETTING_STATE, on ? 1 : 0);
            setProp(PROP_STATE, on ? "1" : "0");
        }
        try {
            ctx.startService(new Intent().setClassName(ctx.getPackageName(), CLASS_SERVICE));
        } catch (Throwable t) {
            Log.e(TAG, "invisible mode: start InvisibleModeService failed", t);
        }
        try {
            ctx.sendBroadcast(new Intent(ACTION_SWITCHED).setPackage(ctx.getPackageName()));
        } catch (Throwable t) {
            Log.w(TAG, "invisible mode: notify switch failed", t);
        }
        Log.i(TAG, "invisible mode -> " + (on ? "on" : "off"));
    }

    /** 优先走安全中心自己的 v$a.d()，它内部带 v.n 判断；返回 false 表示这个混淆名已失效 */
    private static boolean writeState(Context ctx, boolean on) {
        try {
            Class<?> state = Class.forName(CLASS_STATE, true, InvisibleModeRuntime.class.getClassLoader());
            Method d = state.getMethod("d", Context.class, boolean.class);
            d.invoke(null, ctx, on);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "invisible mode: " + CLASS_STATE + ".d unavailable, writing settings directly: " + t);
            return false;
        }
    }

    private static boolean supported() {
        try {
            Class<?> feature = Class.forName(CLASS_FEATURE, true, InvisibleModeRuntime.class.getClassLoader());
            Field n = feature.getField("n");
            return n.getBoolean(null);
        } catch (Throwable t) {
            // 混淆名变了就无从判断，交给后面的 v$a.d / InvisibleModeService 自己把关
            return true;
        }
    }

    private static void setProp(String key, String value) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class).invoke(null, key, value);
        } catch (Throwable t) {
            Log.e(TAG, "invisible mode: set " + key + " failed", t);
        }
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
            // 没有就返回 null，调用方自行处理
        }
        return sContext;
    }

    // ------------------------------------------------------------------ 文案 / 图标

    /**
     * 文案和图标都尽量借安全中心自己的资源（按名字找，避免写死随版本漂移的 id），
     * 找不到再退回按系统语言给的中/英两套。
     */
    static String title() {
        String s = appString("cetus_invisible_mode");
        if (s != null) {
            return s;
        }
        return zh() ? "隐身模式" : "Incognito";
    }

    static String summary(boolean on) {
        if (zh()) {
            return (on ? onText() : offText()) + title();
        }
        return (on ? "Turn on " : "Turn off ") + title();
    }

    private static String onText() {
        String s = appString("auto_task_operation_open");
        return s != null ? s : (zh() ? "开启" : "On");
    }

    private static String offText() {
        String s = appString("auto_task_operation_close");
        return s != null ? s : (zh() ? "关闭" : "Off");
    }

    /**
     * 自动任务的结果图标都是 28dp 圆角方块 + 白色图形；安全中心里没有隐身模式的这种图标，
     * 借用同风格的「隐私保护」logo。都找不到时用系统自带的锁图标兜底，绝不能返回 0
     * （Preference.setIcon(0) 会抛 NotFoundException）。
     */
    static int icon() {
        int id = sIcon;
        if (id != 0) {
            return id;
        }
        id = appDrawable("privacy_settings_logo");
        if (id == 0) {
            id = appDrawable("perm_group_location_icon");
        }
        if (id == 0) {
            id = android.R.drawable.ic_secure;
        }
        sIcon = id;
        return id;
    }

    private static String appString(String name) {
        Context ctx = context();
        if (ctx == null) {
            return null;
        }
        try {
            Resources res = ctx.getResources();
            int id = res.getIdentifier(name, "string", ctx.getPackageName());
            return id == 0 ? null : res.getString(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int appDrawable(String name) {
        Context ctx = context();
        if (ctx == null) {
            return 0;
        }
        try {
            return ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean zh() {
        return "zh".equals(Locale.getDefault().getLanguage());
    }
}
