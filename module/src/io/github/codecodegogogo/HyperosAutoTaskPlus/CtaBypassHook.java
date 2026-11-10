package io.github.codecodegogogo.HyperosAutoTaskPlus;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 跳过「拒绝联网后弹出的隐私声明页」。
 *
 * 安全服务把「允许安全服务联网」这个系统属性同时当作隐私政策是否已同意：
 * 拒绝联网后该属性为 false，打开手机管家主页或「添加条件」页时会拉起
 * SystemAppPermissionDialogActivity 隐私声明页。这里让检查方法 D() 固定返回 true，
 * 页面不再启动。只改变进程内判断，不写回系统属性，也不会真的恢复网络。
 *
 * 各版本的隐私设置工具类混淆名不同，按候选名查找，并用同类的 W(boolean) 写入方法校验。
 */
final class CtaBypassHook {
    private static final String TAG = MainHook.TAG;

    /** 12.3.2/12.3.5、12.6.5、12.7.5、12.8.6、13.2.5、13.5.9/13.6.0 */
    private static final String[] CLASSES = {
            "Z7.z", "U7.z", "o8.B", "k8.C", "pi.x", "aj.z"};

    private CtaBypassHook() {}

    static void install(XC_LoadPackage.LoadPackageParam lp) {
        for (String name : CLASSES) {
            try {
                Class<?> cls = Class.forName(name, false, lp.classLoader);
                Method check = cls.getDeclaredMethod("D");
                if (!Modifier.isStatic(check.getModifiers())
                        || check.getReturnType() != boolean.class
                        || check.getParameterTypes().length != 0) {
                    continue;
                }
                // 同一个隐私设置类里还有 W(boolean) 写入方法，用来排除同名但含义不同的类。
                Method write = cls.getDeclaredMethod("W", boolean.class);
                if (write.getReturnType() != void.class) {
                    continue;
                }
                XposedBridge.hookMethod(check, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(true);
                    }
                });
                XposedBridge.log(TAG + ": 已跳过联网隐私声明页（" + name + "）");
                return;
            } catch (Throwable ignored) {
                // 该版本没有这个混淆名，继续试下一个
            }
        }
        throw new IllegalStateException("未找到隐私声明检查类");
    }
}
