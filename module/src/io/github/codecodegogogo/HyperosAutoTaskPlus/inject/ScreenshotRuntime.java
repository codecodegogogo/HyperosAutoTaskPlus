package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

/** 使用宿主已有的系统截屏权限直接取帧，不经过带提示音的系统截图广播。 */
final class ScreenshotRuntime {
    private ScreenshotRuntime() {}

    // 调用方已在设备操作工作线程串行执行；不创建/停止投屏，也不改系统音量或截图设置。
    static void capture(Context context) {
        Bitmap bitmap = null;
        MediaOutput output = null;
        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyperAutoEnh:screenshot");
            wakeLock.acquire(15_000L);
            WindowManager windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display display = windows.getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            Display.class.getMethod("getRealMetrics", DisplayMetrics.class).invoke(display, metrics);
            if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) throw new IllegalStateException("无法取得屏幕尺寸");
            Rect crop = new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
            if (Build.VERSION.SDK_INT >= 34) {
                bitmap = windowCapture(crop);
            } else if (Build.VERSION.SDK_INT >= 31) {
                bitmap = surfaceCapture(crop);
            } else {
                // Android 10/11 提供同步 Bitmap 截屏接口，同样没有快门或截图声音。
                bitmap = (Bitmap) Class.forName("android.view.SurfaceControl")
                        .getMethod("screenshot", Rect.class, int.class, int.class, int.class)
                        .invoke(null, crop, metrics.widthPixels, metrics.heightPixels, display.getRotation());
            }
            if (bitmap == null) throw new IllegalStateException("系统未返回截图画面");
            output = MediaOutput.create(context, "screenshots");
            output.writePng(bitmap);
            MediaOutput.SavedFile file = output.commit();
            MediaFileNotification.show(context, DeviceActionRuntime.text("截图已保存", "Screenshot saved"), file);
        } catch (Throwable error) {
            Log.w(DeviceActionRuntime.TAG, "silent screenshot failed", error);
            DeviceActionRuntime.notice(context, DeviceActionRuntime.text(
                    "无声截图失败，请检查系统截屏权限或受保护画面；未执行有声截图",
                    "Silent screenshot failed; check screen capture permissions or protected content"));
        } finally {
            if (output != null) output.abort();
            if (bitmap != null) bitmap.recycle();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        }
    }

    /** 安全服务 12.3.5 的 DisplayCaptureUtils 也使用此 Android 14+ 接口。 */
    private static Bitmap windowCapture(Rect crop) throws Exception {
        Class<?> captures = Class.forName("android.window.ScreenCapture");
        Class<?> argsClass = Class.forName("android.window.ScreenCapture$CaptureArgs");
        Class<?> builderClass = Class.forName("android.window.ScreenCapture$CaptureArgs$Builder");
        Object builder = builderClass.getConstructor().newInstance();
        builderClass.getMethod("setSourceCrop", Rect.class).invoke(builder, crop);
        // 不请求捕获安全图层或受保护缓冲区，继续遵循系统的受保护画面规则。
        Object args = builderClass.getMethod("build").invoke(builder);
        Object listener = captures.getMethod("createSyncCaptureListener").invoke(null);
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "window");
        if (binder == null) throw new IllegalStateException("系统窗口服务不可用");
        Object manager = Class.forName("android.view.IWindowManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Class.forName("android.view.IWindowManager").getMethod("captureDisplay", int.class, argsClass,
                Class.forName("android.window.ScreenCapture$ScreenCaptureListener"))
                .invoke(manager, 0, args, listener);
        // 从公开的父类型查方法，避免匿名监听器类导致反射访问失败。
        Object buffer = Class.forName("android.window.ScreenCapture$SynchronousScreenCaptureListener")
                .getMethod("getBuffer").invoke(listener);
        return copyHardwareBitmap(buffer);
    }

    /** Android 12/13 的同类接口仍位于 SurfaceControl。 */
    private static Bitmap surfaceCapture(Rect crop) throws Exception {
        Class<?> surface = Class.forName("android.view.SurfaceControl");
        IBinder token = (IBinder) surface.getMethod("getInternalDisplayToken").invoke(null);
        if (token == null) throw new IllegalStateException("主屏幕不可用");
        Class<?> builderClass = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs$Builder");
        Object builder = builderClass.getConstructor(IBinder.class).newInstance(token);
        builderClass.getMethod("setSourceCrop", Rect.class).invoke(builder, crop);
        builderClass.getMethod("setSize", int.class, int.class).invoke(builder, crop.width(), crop.height());
        Object args = builderClass.getMethod("build").invoke(builder);
        Object buffer = surface.getMethod("captureDisplay", Class.forName("android.view.SurfaceControl$DisplayCaptureArgs"))
                .invoke(null, args);
        return copyHardwareBitmap(buffer);
    }

    private static Bitmap copyHardwareBitmap(Object buffer) throws Exception {
        if (buffer == null) throw new IllegalStateException("系统截屏超时或未返回缓冲区");
        Object hardwareBuffer = null;
        Bitmap wrapped = null;
        try {
            hardwareBuffer = buffer.getClass().getMethod("getHardwareBuffer").invoke(buffer);
            wrapped = (Bitmap) buffer.getClass().getMethod("asBitmap").invoke(buffer);
            if (wrapped == null) throw new IllegalStateException("截图缓冲区不可读");
            Bitmap copy = wrapped.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) throw new IllegalStateException("无法复制截图画面");
            return copy;
        } finally {
            if (wrapped != null) wrapped.recycle();
            if (hardwareBuffer != null) {
                try { hardwareBuffer.getClass().getMethod("close").invoke(hardwareBuffer); }
                catch (Exception error) { Log.w(DeviceActionRuntime.TAG, "release screenshot buffer", error); }
            }
        }
    }
}
