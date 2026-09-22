package io.github.codecodegogogo.HyperosAutoTaskPlus;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hyper自动任务增强：为 HyperOS 安全服务（com.miui.securitycenter）的「自动任务」补功能。
 *
 * 目前包含：
 *   1. {@link UnlockAppResultHook}      使用「启动/离开应用」条件时允许选「打开应用」结果；
 *   2. {@link FirstAppConditionHook}    新增「首次启动应用 / 首次离开应用」两个条件，
 *      按进程是否在内存中触发，而不是前台切换；
 *   3. {@link InvisibleModeResultHook}  新增「隐身模式」结果，可在任务中开启/关闭权限中心的隐身模式；
 *   4. {@link ScreenStateConditionHook} 新增「屏幕状态」条件（亮屏 / 息屏持续指定时长）；
 *   5. {@link ExitConditionHook}        退出条件列表放开增删改；
 *   6. {@link IntervalConditionHook}    新增「时间间隔」条件（每隔 N 秒/分钟/小时/天）；
 *   7. {@link SensorConditionHook}      新增「传感器」分类：设备动作（翻转 / 摇晃）、光线；
 *   8. {@link GeofenceConditionHook}    新增「到达 / 离开地理围栏」条件（可自定义半径）。
 *
 * 目标版本：安全服务 12.3.5（260211.0.1）。混淆名（g2.M0、b2.j 等）随版本可能变化，
 * 各 hook 均按签名兜底并独立 try/catch，某一处失效不影响其余功能。
 */
public class MainHook implements IXposedHookLoadPackage {

    static final String TAG = "HyperAutoEnh";

    private static final String TARGET_PKG = "com.miui.securitycenter";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + ": load in " + lpparam.processName);

        try {
            UnlockAppResultHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 解锁「打开应用」结果 hook 失败");
            XposedBridge.log(t);
        }

        try {
            FirstAppConditionHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 「首次启动/离开应用」条件 hook 失败");
            XposedBridge.log(t);
        }

        try {
            InvisibleModeResultHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 「隐身模式」结果 hook 失败");
            XposedBridge.log(t);
        }

        try {
            ScreenStateConditionHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 「屏幕状态」条件 hook 失败");
            XposedBridge.log(t);
        }

        try {
            ExitConditionHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 「自定义退出条件」hook 失败");
            XposedBridge.log(t);
        }

        try {
            IntervalConditionHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 「时间间隔」条件 hook 失败");
            XposedBridge.log(t);
        }

        try {
            SensorConditionHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 「传感器」条件 hook 失败");
            XposedBridge.log(t);
        }

        try {
            GeofenceConditionHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 「地理围栏」条件 hook 失败");
            XposedBridge.log(t);
        }
    }
}
