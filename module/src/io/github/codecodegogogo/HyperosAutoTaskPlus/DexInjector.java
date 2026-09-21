package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dalvik.system.BaseDexClassLoader;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 把本模块的 APK 追加到安全中心的 ClassLoader 里（BaseDexClassLoader.addDexPath）。
 *
 * 为什么需要：新条件项要 extends 安全中心的 LunchAppItem，而 LSPosed 给模块的
 * ClassLoader 看不到应用的类；反过来，安全中心用 Gson 存库、用 Intent(Serializable)
 * 在页面间传条件项，反序列化时都是拿应用自己的 ClassLoader 按类名找类，
 * 所以这些类必须能被应用的 ClassLoader 直接找到。注入后 inject 包下的类
 * 由安全中心的 ClassLoader 加载，hook 侧只能通过这里拿到的 Class 反射调用。
 */
final class DexInjector {

    private static final String TAG = MainHook.TAG;

    /** 模块自己的包名，与 AndroidManifest 一致 */
    static final String MODULE_PKG = "io.github.codecodegogogo.HyperosAutoTaskPlus";

    private static final String RUNTIME_CLASS = MODULE_PKG + ".inject.FirstAppRuntime";
    private static final String INVISIBLE_RUNTIME_CLASS = MODULE_PKG + ".inject.InvisibleModeRuntime";
    private static final String SCREEN_RUNTIME_CLASS = MODULE_PKG + ".inject.ScreenStateRuntime";

    private static volatile Class<?> sRuntime;
    private static volatile Class<?> sInvisibleRuntime;
    private static volatile Class<?> sScreenRuntime;
    private static volatile Context sAppContext;

    private DexInjector() {
    }

    static boolean isReady() {
        return sRuntime != null;
    }

    /** 注入后的 FirstAppRuntime（由安全中心 ClassLoader 加载的那一份） */
    static Class<?> runtime() {
        return sRuntime;
    }

    /** 注入后的 InvisibleModeRuntime，注入失败时为 null */
    static Class<?> invisibleRuntime() {
        return sInvisibleRuntime;
    }

    /** 注入后的 ScreenStateRuntime，注入失败时为 null */
    static Class<?> screenRuntime() {
        return sScreenRuntime;
    }

    static Context appContext() {
        return sAppContext;
    }

    static synchronized void inject(Context context, ClassLoader appLoader) {
        if (sRuntime != null) {
            return;
        }
        sAppContext = context;
        try {
            if (!(appLoader instanceof BaseDexClassLoader)) {
                XposedBridge.log(TAG + ": 应用 ClassLoader 不是 BaseDexClassLoader，无法注入: " + appLoader);
                return;
            }
            String apk = findModuleApk(context);
            if (apk == null) {
                XposedBridge.log(TAG + ": 找不到模块 APK 路径，无法注入");
                return;
            }
            XposedHelpers.callMethod(appLoader, "addDexPath", apk);
            sRuntime = Class.forName(RUNTIME_CLASS, true, appLoader);
            XposedBridge.log(TAG + ": 已注入 " + apk + " -> " + appLoader.getClass().getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 注入模块 dex 失败，「首次启动/离开应用」条件不可用");
            XposedBridge.log(t);
            return;
        }
        try {
            Class<?> cls = Class.forName(INVISIBLE_RUNTIME_CLASS, true, appLoader);
            XposedHelpers.callStaticMethod(cls, "init", context);
            sInvisibleRuntime = cls;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 加载 InvisibleModeRuntime 失败，「隐身模式」结果不可用");
            XposedBridge.log(t);
        }
        try {
            sScreenRuntime = Class.forName(SCREEN_RUNTIME_CLASS, true, appLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 加载 ScreenStateRuntime 失败，「屏幕状态」条件不可用");
            XposedBridge.log(t);
        }
    }

    private static String findModuleApk(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(MODULE_PKG, 0);
            if (info != null && info.sourceDir != null) {
                return info.sourceDir;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": PackageManager 查不到模块自身: " + t);
        }
        // LSPosed 的模块 ClassLoader toString() 里带有 APK 路径，作为兜底
        String desc = String.valueOf(DexInjector.class.getClassLoader());
        Matcher m = Pattern.compile("(/[^\\s\"\\[\\],]+\\.apk)").matcher(desc);
        return m.find() ? m.group(1) : null;
    }
}
