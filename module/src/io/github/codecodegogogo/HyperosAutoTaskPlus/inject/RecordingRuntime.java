package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * 注入侧录制会话，只在 DeviceActionRuntime.worker 上读写。
 * 录音使用 MediaRecorder；录屏通过安全服务 system UID 的投屏权限创建本模块自己的
 * MediaProjection，再用 MediaRecorder 编码。停止只作用于持有的句柄，不发送全局停止命令。
 * 系统录屏/投屏已经运行时不抢占；系统撤销投屏、编码出错、手动停止均走同一清理路径。
 */
final class RecordingRuntime {
    private static final Session[] SESSIONS = new Session[2];
    // 新渠道一次性升级为高重要级别；录音、录屏共用此渠道。
    private static final String CHANNEL = "hyper_auto_recording_high_v1";
    private static final int NOTIFICATION_BASE = 0x48415200;

    private RecordingRuntime() {}

    static void apply(Context context, boolean screen, boolean start, boolean restore, String owner) throws Exception {
        int index = screen ? 1 : 0;
        Session current = SESSIONS[index];
        if (!start) {
            if (current != null && (!restore || owns(current.owner, owner))) finish(current);
            return;
        }
        if (owner == null || owner.isEmpty()) throw new IllegalStateException("任务 UUID 为空");
        if (current != null) {
            if (!owns(current.owner, owner)) DeviceActionRuntime.notice(context,
                    DeviceActionRuntime.text("已有其他任务正在录制", "Another task is already recording"));
            return;
        }
        Session session = new Session(context, screen, owner);
        SESSIONS[index] = session;
        try {
            session.start();
        } catch (Exception e) {
            SESSIONS[index] = null;
            session.close(false);
            throw e;
        } catch (LinkageError e) {
            SESSIONS[index] = null;
            session.close(false);
            throw e;
        }
    }

    static boolean owns(String current, String requested) {
        return current != null && !current.isEmpty() && current.equals(requested);
    }

    private static void finish(Session session) {
        int index = session.screen ? 1 : 0;
        // 过期的编码回调/通知按钮不得停止后续新启动的录制。
        if (SESSIONS[index] != session) return;
        SESSIONS[index] = null;
        session.close(true);
    }

    private static final class Session {
        final Context context;
        final boolean screen;
        final String owner;
        final int notificationId;
        MediaRecorder recorder;
        MediaProjection projection;
        VirtualDisplay display;
        Surface surface;
        MediaOutput output;
        PowerManager.WakeLock wakeLock;
        BroadcastReceiver stopReceiver;
        PendingIntent stopIntent;
        boolean started;
        boolean notified;

        final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
            @Override public void onStop() { finish(Session.this); }
        };

        Session(Context context, boolean screen, String owner) {
            this.context = context;
            this.screen = screen;
            this.owner = owner;
            notificationId = NOTIFICATION_BASE + (screen ? 1 : 0);
        }

        void start() throws Exception {
            // 先显示可停止的通知；没有可用通知时不启动录制。
            showNotification();
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyperAutoEnh:recording");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            try {
                recorder = MediaRecorder.class.getConstructor(Context.class).newInstance(context);
            } catch (NoSuchMethodException e) {
                recorder = new MediaRecorder();
            }
            recorder.setOnErrorListener((mr, what, extra) -> DeviceActionRuntime.worker().post(() -> {
                if (SESSIONS[screen ? 1 : 0] != this) return;
                DeviceActionRuntime.failure(context, label(), new IllegalStateException("编码错误 " + what + "/" + extra));
                finish(this);
            }));
            recorder.setOnInfoListener((mr, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                        || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    DeviceActionRuntime.worker().post(() -> finish(this));
                }
            });
            if (screen) recorder.setVideoSource(2); // MediaRecorder.VideoSource.SURFACE，API 21+
            else recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            DisplayMetrics metrics = null;
            if (screen) {
                metrics = screenMetrics();
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                recorder.setVideoSize(metrics.widthPixels, metrics.heightPixels);
                recorder.setVideoFrameRate(30);
                recorder.setVideoEncodingBitRate(8_000_000);
            } else {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioChannels(1);
                recorder.setAudioSamplingRate(44100);
                recorder.setAudioEncodingBitRate(96_000);
            }
            output = MediaOutput.create(context, screen ? "video" : "audio");
            recorder.setOutputFile(output.descriptor());
            recorder.prepare();
            if (screen) {
                surface = (Surface) MediaRecorder.class.getMethod("getSurface").invoke(recorder);
                projection = createProjection(context);
                projection.registerCallback(projectionCallback, DeviceActionRuntime.worker());
                display = projection.createVirtualDisplay("HyperAutoTask", metrics.widthPixels,
                        metrics.heightPixels, metrics.densityDpi, 16, surface, null, DeviceActionRuntime.worker());
                if (display == null) throw new IllegalStateException("无法创建录屏显示");
            }
            recorder.start();
            started = true;
            Log.i(DeviceActionRuntime.TAG, "recording started: " + (screen ? "screen" : "audio") + " task=" + owner);
        }

        private DisplayMetrics screenMetrics() throws Exception {
            WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display deviceDisplay = manager.getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            Display.class.getMethod("getRealMetrics", DisplayMetrics.class).invoke(deviceDisplay, metrics);
            // 限制长边到 1920 且保持比例，避免部分设备编码器不接受面板原始分辨率。
            double scale = Math.min(1d, 1920d / Math.max(metrics.widthPixels, metrics.heightPixels));
            metrics.widthPixels = Math.max(2, ((int) (metrics.widthPixels * scale)) & ~1);
            metrics.heightPixels = Math.max(2, ((int) (metrics.heightPixels * scale)) & ~1);
            return metrics;
        }

        private void showNotification() throws Exception {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) throw new IllegalStateException("通知服务不可用");
            if (!(Boolean) NotificationManager.class.getMethod("areNotificationsEnabled").invoke(manager)) {
                throw new IllegalStateException("请开启安全服务通知以控制录制");
            }
            Class<?> channelClass = Class.forName("android.app.NotificationChannel");
            Object channel = channelClass.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CHANNEL, DeviceActionRuntime.text("自动任务录制", "Auto task recording"), 4); // IMPORTANCE_HIGH
            NotificationManager.class.getMethod("createNotificationChannel", channelClass).invoke(manager, channel);
            Object existing = NotificationManager.class.getMethod("getNotificationChannel", String.class).invoke(manager, CHANNEL);
            if (existing != null && (Integer) channelClass.getMethod("getImportance").invoke(existing) == 0) {
                throw new IllegalStateException("请开启自动任务录制通知");
            }
            String action = context.getPackageName() + ".hyper_auto.STOP_" + UUID.randomUUID();
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) { finish(Session.this); }
            };
            ActionReceivers.register(context, receiver, action, DeviceActionRuntime.worker());
            stopReceiver = receiver;
            stopIntent = PendingIntent.getBroadcast(context, notificationId,
                    new Intent(action).setPackage(context.getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
            Notification.Builder builder = Notification.Builder.class.getConstructor(Context.class, String.class)
                    .newInstance(context, CHANNEL);
            builder.setSmallIcon(screen ? android.R.drawable.presence_video_online : android.R.drawable.ic_btn_speak_now)
                    .setContentTitle(DeviceActionRuntime.text("自动任务正在", "Auto task: ") + label())
                    .setContentText(DeviceActionRuntime.text("点按停止并保存", "Tap to stop and save"))
                    .setContentIntent(stopIntent).setOngoing(true);
            manager.notify(notificationId, NotificationAttention.build(builder));
            notified = true;
        }

        void close(boolean save) {
            boolean valid = save && started;
            MediaOutput.SavedFile savedFile = null;
            Throwable failure = null;
            if (recorder != null) {
                if (started) {
                    try { recorder.stop(); }
                    catch (Throwable t) { valid = false; failure = t; }
                }
                try { recorder.release(); } catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "release recorder", t); }
                recorder = null;
            }
            started = false;
            if (display != null) {
                try { display.release(); } catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "release virtual display", t); }
                display = null;
            }
            if (surface != null) {
                try { surface.release(); } catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "release surface", t); }
                surface = null;
            }
            if (projection != null) {
                try { projection.unregisterCallback(projectionCallback); projection.stop(); }
                catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "stop own projection", t); }
                projection = null;
            }
            if (output != null) {
                try { if (valid) savedFile = output.commit(); }
                catch (Throwable t) { failure = t; valid = false; }
                finally { output.abort(); output = null; }
            }
            if (stopReceiver != null) {
                try { context.unregisterReceiver(stopReceiver); } catch (Throwable ignored) {}
                stopReceiver = null;
            }
            if (stopIntent != null) {
                try { stopIntent.cancel(); } catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "cancel recording action", t); }
                stopIntent = null;
            }
            if (notified) {
                try { ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(notificationId); }
                catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "cancel recording notification", t); }
                notified = false;
            }
            if (wakeLock != null) {
                try { if (wakeLock.isHeld()) wakeLock.release(); }
                catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "release recording wake lock", t); }
                wakeLock = null;
            }
            if (failure != null && save) DeviceActionRuntime.failure(context, label(), failure);
            else if (savedFile != null) {
                String title = label() + DeviceActionRuntime.text("已保存", " saved");
                MediaFileNotification.show(context, title, savedFile);
                DeviceActionRuntime.notice(context, title);
            }
        }

        private String label() { return screen ? DeviceActionRuntime.text("录屏", "screen recording") : DeviceActionRuntime.text("录音", "audio recording"); }
    }

    private static MediaProjection createProjection(Context context) throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) serviceManager.getMethod("getService", String.class).invoke(null, "media_projection");
        if (binder == null) throw new IllegalStateException("系统投屏服务不可用");
        Class<?> managerClass = Class.forName("android.media.projection.IMediaProjectionManager");
        Class<?> stubClass = Class.forName("android.media.projection.IMediaProjectionManager$Stub");
        Object manager = stubClass.getMethod("asInterface", IBinder.class).invoke(null, binder);
        if (managerClass.getMethod("getActiveProjectionInfo").invoke(manager) != null) {
            throw new IllegalStateException("已有录屏或投屏正在运行");
        }
        // 只使用安全服务已有的系统权限，不修改 AppOps、不自动授予权限。
        Object token;
        try {
            Method create = managerClass.getMethod("createProjection", int.class, String.class, int.class, boolean.class);
            token = create.invoke(manager, android.os.Process.myUid(), context.getPackageName(), 0, false);
        } catch (NoSuchMethodException e) {
            // Android 16 新增 displayId，第 0 号显示是主屏幕。
            Method create = managerClass.getMethod("createProjection", int.class, String.class, int.class, boolean.class, int.class);
            token = create.invoke(manager, android.os.Process.myUid(), context.getPackageName(), 0, false, 0);
        }
        if (token == null) throw new IllegalStateException("系统未授予录屏会话");
        Class<?> tokenClass = Class.forName("android.media.projection.IMediaProjection");
        try {
            return MediaProjection.class.getConstructor(Context.class, tokenClass).newInstance(context, token);
        } catch (Exception e) {
            // 构造中途失败也不能把已经启动的投屏令牌留在系统里。
            try {
                try { tokenClass.getMethod("stop").invoke(token); }
                catch (NoSuchMethodException newerApi) {
                    int reason = Class.forName("android.media.projection.StopReason").getField("STOP_HOST_APP").getInt(null);
                    tokenClass.getMethod("stop", int.class).invoke(token, reason);
                }
            }
            catch (Exception cleanup) { Log.w(DeviceActionRuntime.TAG, "release projection token", cleanup); }
            throw e;
        }
    }
}
