package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.PowerManager;
import android.util.Log;
import android.view.WindowManager;

import java.util.List;

/** 多选时依次拍摄后置、前置，每张完成或失败后释放相机，再执行下一张。 */
@SuppressWarnings("deprecation")
final class PhotoRuntime {
    private static Shot sShot;

    private PhotoRuntime() {}

    interface Callback {
        void ready(byte[] jpeg);
        void failed(String reason);
    }

    static void captureForMessage(Context context, Callback callback) {
        if (sShot != null) {
            callback.failed("相机正在使用，请稍后");
            return;
        }
        Shot shot = new Shot(context, DeviceActionResultItem.CAMERA_REAR, callback);
        sShot = shot;
        shot.startSafely();
    }

    static void capture(Context context, int cameras) {
        if (!DeviceActionResultItem.validCameraSelection(cameras)) throw new IllegalArgumentException("无效的摄像头选择");
        if (sShot != null) {
            DeviceActionRuntime.notice(context, DeviceActionRuntime.text("正在拍照，请稍后", "A photo is already being taken"));
            return;
        }
        Shot shot = new Shot(context, cameras, null);
        sShot = shot;
        shot.startSafely();
    }

    private static final class Shot {
        final Context context;
        final boolean front;
        final int remainingCameras;
        final Callback callback;
        Camera camera;
        SurfaceTexture texture;
        PowerManager.WakeLock wakeLock;
        boolean taking;
        boolean closed;
        final Runnable timeout = () -> fail(new IllegalStateException("相机响应超时"));
        final Runnable take = this::take;

        Shot(Context context, int cameras, Callback callback) {
            this.context = context;
            this.callback = callback;
            front = (cameras & DeviceActionResultItem.CAMERA_REAR) == 0;
            remainingCameras = cameras & ~(front ? DeviceActionResultItem.CAMERA_FRONT : DeviceActionResultItem.CAMERA_REAR);
        }

        void startSafely() {
            if (sShot != this || closed) return;
            try { start(); }
            catch (Throwable t) { fail(t); }
        }

        void start() throws Exception {
            int facing = front ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;
            int id = -1;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == facing) { id = i; break; }
            }
            if (id < 0) throw new IllegalStateException("设备没有所选摄像头");
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyperAutoEnh:photo");
            wakeLock.acquire(20_000L);
            DeviceActionRuntime.worker().postDelayed(timeout, 15_000L);
            camera = Camera.open(id);
            // API 17+ 每个相机会话分别关闭快门音，前后置及彩信拍照共用此路径。
            // 不调整手机音量；系统拒绝静音时取消本次拍照，不能偷偷回退到有声拍摄。
            try {
                if (!Boolean.TRUE.equals(Camera.class.getMethod("enableShutterSound", boolean.class).invoke(camera, false))) {
                    throw new IllegalStateException("相机拒绝关闭快门音");
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new SilentPhotoUnavailableException(e);
            }
            camera.setErrorCallback((error, source) -> fail(new IllegalStateException("相机错误 " + error)));
            Camera.Parameters parameters = camera.getParameters();
            parameters.setPictureFormat(ImageFormat.JPEG);
            parameters.setJpegQuality(95);
            if (callback != null) {
                // 彩信无需传输全尺寸照片，先限制相机输出，降低内存和后续压缩开销。
                Camera.Size best = null;
                List<Camera.Size> sizes = parameters.getSupportedPictureSizes();
                if (sizes != null) for (Camera.Size size : sizes) {
                    long area = (long) size.width * size.height;
                    long current = best == null ? Long.MAX_VALUE : (long) best.width * best.height;
                    if (best == null || (current > 1280L * 960 && area < current)
                            || (area <= 1280L * 960 && area > current)) best = size;
                }
                if (best != null) parameters.setPictureSize(best.width, best.height);
            }
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation() * 90;
            parameters.setRotation((info.orientation + (front ? rotation : -rotation) + 360) % 360);
            List<String> modes = parameters.getSupportedFocusModes();
            boolean autofocus = modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_AUTO);
            if (autofocus) parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            camera.setParameters(parameters);
            texture = new SurfaceTexture(0);
            camera.setPreviewTexture(texture);
            camera.setOneShotPreviewCallback((bytes, source) -> {
                if (sShot != this) return;
                // 第一帧到达后让曝光稳定，对焦失败也在 1.5 秒后拍摄。
                DeviceActionRuntime.worker().postDelayed(take, autofocus ? 1500L : 500L);
                if (autofocus) {
                    try { source.autoFocus((success, focused) -> {
                        if (sShot == this) DeviceActionRuntime.worker().postDelayed(take, 300L);
                    }); }
                    catch (RuntimeException ignored) { /* 到时直接拍摄 */ }
                }
            });
            camera.startPreview();
        }

        void take() {
            if (sShot != this || taking || camera == null) return;
            taking = true;
            DeviceActionRuntime.worker().removeCallbacks(take);
            try {
                camera.takePicture(null, null, (jpeg, source) -> {
                    if (sShot != this) return;
                    if (callback != null) {
                        close();
                        if (jpeg == null || jpeg.length == 0) callback.failed("相机未返回有效照片");
                        else callback.ready(jpeg);
                        return;
                    }
                    MediaOutput output = null;
                    try {
                        output = MediaOutput.create(context, "images");
                        output.write(jpeg);
                        MediaOutput.SavedFile file = output.commit();
                        String title = label() + DeviceActionRuntime.text("照片已保存", " photo saved");
                        MediaFileNotification.show(context, title, file);
                        DeviceActionRuntime.notice(context, title);
                    } catch (Throwable t) {
                        DeviceActionRuntime.failure(context, label() + DeviceActionRuntime.text("拍照", " photo"), t);
                    } finally {
                        if (output != null) output.abort();
                        close();
                    }
                });
            } catch (Throwable t) { fail(t); }
        }

        void fail(Throwable t) {
            if (sShot != this) return;
            close();
            String reason = t instanceof SilentPhotoUnavailableException
                    ? "系统不允许关闭快门音，已取消拍照"
                    : "环境照片拍摄失败，请检查相机隐私开关、相机占用及系统权限限制";
            if (callback != null) {
                Log.w(DeviceActionRuntime.TAG, "message camera failed", t);
                callback.failed(reason);
            } else if (t instanceof SilentPhotoUnavailableException) {
                Log.w(DeviceActionRuntime.TAG, "silent photo unavailable", t);
                DeviceActionRuntime.notice(context, reason);
            } else {
                DeviceActionRuntime.failure(context, label() + DeviceActionRuntime.text("拍照", " photo"), t);
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            DeviceActionRuntime.worker().removeCallbacks(timeout);
            DeviceActionRuntime.worker().removeCallbacks(take);
            if (camera != null) {
                try { camera.release(); } catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "release camera", t); }
                camera = null;
            }
            if (texture != null) {
                try { texture.release(); } catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "release photo texture", t); }
                texture = null;
            }
            if (wakeLock != null) {
                try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "release photo wake lock", t); }
                wakeLock = null;
            }
            if (sShot == this) {
                if (remainingCameras != 0) {
                    // 先预留下一张，防止其它任务在两张照片之间插入相机操作。
                    Shot next = new Shot(context, remainingCameras, null);
                    sShot = next;
                    DeviceActionRuntime.worker().post(next::startSafely);
                } else {
                    sShot = null;
                }
            }
        }

        String label() { return front ? DeviceActionRuntime.text("前置", "Front") : DeviceActionRuntime.text("后置", "Rear"); }
    }

    private static final class SilentPhotoUnavailableException extends Exception {
        SilentPhotoUnavailableException(Throwable cause) { super("系统不允许关闭快门音，已取消拍照", cause); }
    }
}
