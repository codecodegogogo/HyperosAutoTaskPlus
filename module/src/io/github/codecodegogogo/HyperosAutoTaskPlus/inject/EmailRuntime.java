package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.location.Location;
import android.os.PowerManager;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 邮件准备在设备工作线程串行收束，SMTP 网络操作在独立线程执行。 */
final class EmailRuntime {
    private static final int MAX_PENDING = 3;
    private static final ArrayDeque<Session> PENDING = new ArrayDeque<>();
    private static Session current;
    private static final ExecutorService SMTP = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "hyper_auto_smtp");
        thread.setDaemon(true);
        return thread;
    });

    private EmailRuntime() {}

    /** 由 DeviceActionRuntime 的工作线程调用，每次使用配置快照，不修改任务对象。 */
    static void send(Context context, EmailConfig config) {
        if (config == null || !config.isValid()) {
            DeviceActionRuntime.notice(context, "邮件配置不完整，请重新编辑发送邮件结果");
            return;
        }
        if (current != null && PENDING.size() >= MAX_PENDING) {
            DeviceActionRuntime.notice(context, "待发邮件较多，本次未加入队列，请稍后");
            return;
        }
        PENDING.addLast(new Session(context, config.copy()));
        startNext();
    }

    private static void startNext() {
        if (current != null || PENDING.isEmpty()) return;
        current = PENDING.removeFirst();
        current.start();
    }

    private static final class Session {
        final Context context;
        final EmailConfig config;
        PowerManager.WakeLock wakeLock;
        String secret;
        String body;
        boolean closed;

        Session(Context context, EmailConfig config) {
            this.context = context.getApplicationContext() == null ? context : context.getApplicationContext();
            this.config = config;
        }

        void start() {
            try {
                secret = EmailCredentialStore.read(context, config);
                if (!EmailConfig.validSecret(secret)) throw new IllegalStateException("邮件授权码不可用");
                PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyperAutoEnh:email");
                wakeLock.acquire(130_000L); // 定位 30+6 秒、拍照 15 秒、SMTP 最多 60 秒。
                body = LocationMessage.text(config.body);
                if (config.sendLocation) {
                    SmsLocationRuntime.request(context, new SmsLocationRuntime.Callback() {
                        @Override public void ready(Location position, String place) {
                            if (closed) return;
                            try {
                                body = LocationMessage.append(config.body, position.getLongitude(), position.getLatitude(), place);
                                preparePhoto();
                            } catch (Exception error) { failPreparation(); }
                        }
                        @Override public void failed(String reason) { finish(reason + "；邮件未发送"); }
                    });
                } else preparePhoto();
            } catch (Exception error) {
                Log.w(DeviceActionRuntime.TAG, "email preparation failed: " + error.getClass().getSimpleName());
                finish("邮件准备失败，请检查邮箱授权码、系统权限；清除应用数据或更换设备后需重新配置授权码");
            }
        }

        void preparePhoto() {
            if (closed) return;
            if (!config.sendPhoto) {
                submit(null);
                return;
            }
            PhotoRuntime.captureForMessage(context, new PhotoRuntime.Callback() {
                @Override public void ready(byte[] jpeg) { if (!closed) submit(jpeg); }
                @Override public void failed(String reason) { finish(reason + "；邮件未发送"); }
            });
        }

        void submit(byte[] jpeg) {
            try {
                SMTP.execute(() -> {
                    String result;
                    try {
                        SmtpClient.send(config, secret, body, jpeg);
                        result = "邮件已交给发件服务器，请在收件邮箱查看";
                        Log.i(DeviceActionRuntime.TAG, "email accepted by SMTP server");
                    } catch (SmtpClient.MailException error) {
                        result = error.getMessage();
                        Log.w(DeviceActionRuntime.TAG, "email send failed: " + error.getCode());
                    } catch (Exception error) {
                        result = "邮件发送失败，请检查网络、SMTP 配置及邮箱授权码";
                        Log.w(DeviceActionRuntime.TAG, "email send failed: " + error.getClass().getSimpleName());
                    }
                    final String notice = result;
                    DeviceActionRuntime.worker().post(() -> finish(notice));
                });
            } catch (RuntimeException error) { failPreparation(); }
        }

        void failPreparation() { finish("邮件准备失败，邮件未发送，请检查位置和相机状态"); }

        void finish(String notice) {
            if (closed) return;
            closed = true;
            secret = null;
            if (wakeLock != null) {
                try { if (wakeLock.isHeld()) wakeLock.release(); }
                catch (RuntimeException error) { Log.w(DeviceActionRuntime.TAG, "release email wake lock"); }
                wakeLock = null;
            }
            DeviceActionRuntime.notice(context, notice);
            if (current == this) current = null;
            DeviceActionRuntime.worker().post(EmailRuntime::startNext);
        }
    }
}
