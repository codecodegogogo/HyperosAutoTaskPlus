package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.app.ActivityManager;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 仅在 SystemUI 主进程采集锁屏失败。
 * PIN/密码/图案共用 LockPatternUtils.reportFailedPasswordAttempt；指纹和人脸各挂
 * KeyguardUpdateMonitor 的一个汇总入口，不再监听下游 N 个 UI callback，避免重复计数。
 * 不 hook help/error/cancel，系统的锁定/超时提示不会被当成新失败。
 */
final class UnlockFailureSourceHook {
    static final String SYSTEM_UI = "com.android.systemui";
    private static volatile Context sContext;
    private static volatile Handler sHandler;
    private static boolean sInstalled;

    private UnlockFailureSourceHook() {}

    static synchronized void install(XC_LoadPackage.LoadPackageParam lp) {
        if (sInstalled || !SYSTEM_UI.equals(lp.processName)) return;
        sInstalled = true;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                initialize((Context) param.args[0]);
            }
        });
        HookUtils.step("unlock credential source", () -> {
            Class<?> utils = XposedHelpers.findClass("com.android.internal.widget.LockPatternUtils", lp.classLoader);
            XposedHelpers.findAndHookMethod(utils, "reportFailedPasswordAttempt", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    failed((Integer) param.args[0], "credential");
                }
            });
            XposedBridge.log(MainHook.TAG + ": 已接入 PIN/密码/图案失败统计");
        });
        HookUtils.step("unlock fingerprint source", () -> hookBiometric(lp.classLoader, "handleFingerprintAuthFailed", "fingerprint"));
        HookUtils.step("unlock face source", () -> hookBiometric(lp.classLoader, "handleFaceAuthFailed", "face"));
    }

    private static void hookBiometric(ClassLoader loader, String name, String source) throws Exception {
        Class<?> monitor = XposedHelpers.findClass("com.android.keyguard.KeyguardUpdateMonitor", loader);
        // 多个无参 void 方法签名相同，不能按签名猜测认证入口。
        Method method = monitor.getDeclaredMethod(name);
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                failed(-1, source);
            }
        });
        XposedBridge.log(MainHook.TAG + ": 已接入 " + source + " 锁屏失败统计");
    }

    private static synchronized void initialize(Context context) {
        if (sHandler != null) return;
        try {
            sContext = context;
            HandlerThread thread = new HandlerThread("hyper_auto_unlock_counter");
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            sHandler = handler;
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    if (!Intent.ACTION_USER_PRESENT.equals(intent.getAction())) return;
                    try {
                        int user = (Integer) BroadcastReceiver.class.getMethod("getSendingUserId").invoke(this);
                        if (user >= 0) update(user, true, "user present");
                    } catch (Throwable t) {
                        logFailure("读取成功解锁用户失败", t);
                    }
                }
            };
            // USER_PRESENT 是系统保护广播；按用户存储，防止双用户之间混计。
            Class<?> userHandle = Class.forName("android.os.UserHandle");
            Context.class.getMethod("registerReceiverAsUser", BroadcastReceiver.class, userHandle,
                    IntentFilter.class, String.class, Handler.class).invoke(context, receiver,
                    userHandle.getField("ALL").get(null), new IntentFilter(Intent.ACTION_USER_PRESENT), null, handler);
            handler.post(() -> {
                try {
                    int user = currentUser();
                    UnlockFailureState state = read(user);
                    if (state == null) write(user, new UnlockFailureState(0, 0));
                    // SystemUI 在已解锁时重启：补上停机期间漏掉的成功解锁事件。
                    else if (state.count > 0 && !locked()) update(user, true, "already unlocked");
                } catch (Throwable t) { logFailure("初始化解锁统计失败", t); }
            });
        } catch (Throwable t) {
            // 成功解锁接收器没有装好就不计数，不能让连续次数永远不清零。
            Handler handler = sHandler;
            sHandler = null;
            if (handler != null) handler.getLooper().quit();
            logFailure("初始化 SystemUI 解锁监听失败", t);
        }
    }

    private static void failed(int attemptedUser, String source) {
        Handler handler = sHandler;
        if (handler == null) return;
        try {
            int user = currentUser();
            int attempt = attemptedUser < 0 ? user : attemptedUser;
            if (!UnlockFailureState.shouldCount(locked(), user, attempt)) return;
            // 每个实际失败回调加一次，不用时间防抖吞掉用户连续的两次尝试。
            handler.post(() -> update(user, false, source));
        } catch (Throwable t) { logFailure("读取锁屏失败事件失败", t); }
    }

    private static int currentUser() throws Exception {
        return (Integer) ActivityManager.class.getMethod("getCurrentUser").invoke(null);
    }

    private static boolean locked() {
        KeyguardManager manager = (KeyguardManager) sContext.getSystemService(Context.KEYGUARD_SERVICE);
        return manager != null && manager.isKeyguardLocked();
    }

    private static UnlockFailureState read(int user) throws Exception {
        String value = (String) Settings.Secure.class.getMethod("getStringForUser", ContentResolver.class, String.class, int.class)
                .invoke(null, sContext.getContentResolver(), UnlockFailureState.SETTING_KEY, user);
        return UnlockFailureState.decode(value);
    }

    private static void write(int user, UnlockFailureState state) throws Exception {
        boolean saved = (Boolean) Settings.Secure.class.getMethod("putStringForUser", ContentResolver.class,
                String.class, String.class, int.class).invoke(null, sContext.getContentResolver(),
                UnlockFailureState.SETTING_KEY, state.encode(), user);
        if (!saved) throw new IllegalStateException("无法保存解锁失败次数");
    }

    private static void update(int user, boolean success, String source) {
        try {
            UnlockFailureState state = read(user);
            if (state == null) state = new UnlockFailureState(0, 0);
            state = success ? state.unlocked() : state.failed();
            write(user, state);
            XposedBridge.log(MainHook.TAG + ": unlock " + source + " user=" + user + " count=" + state.count);
        } catch (Throwable t) { logFailure("保存解锁统计失败", t); }
    }

    private static void logFailure(String message, Throwable t) {
        XposedBridge.log(MainHook.TAG + ": " + message);
        XposedBridge.log(t);
    }
}
