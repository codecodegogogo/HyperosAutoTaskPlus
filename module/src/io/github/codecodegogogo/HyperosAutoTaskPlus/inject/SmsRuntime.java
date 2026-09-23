package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.telephony.SmsManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.UUID;

/** 使用默认短信 SIM；长短信按系统规则分段，并接收各段的发送结果。 */
final class SmsRuntime {
    private SmsRuntime() {}

    static void send(Context context, String number, String message, boolean location, boolean photo) {
        try {
            requirePermission(context, "android.permission.SEND_SMS", "请授予安全服务发送短信权限，消息未发送");
            if (photo) requirePermission(context, "android.permission.CAMERA", "安全服务相机访问受限，彩信未发送；请检查相机隐私开关及系统权限限制");
            SmsManager manager = defaultManager();
            if (location) {
                SmsLocationRuntime.request(context, new SmsLocationRuntime.Callback() {
                    @Override public void ready(Location position, String place) {
                        try {
                            dispatch(context, manager, number, LocationMessage.append(message,
                                    position.getLongitude(), position.getLatitude(), place), photo);
                        } catch (Exception e) { reportFailure(context, photo, e); }
                    }
                    @Override public void failed(String reason) { DeviceActionRuntime.notice(context, reason); }
                });
            } else dispatch(context, manager, number, LocationMessage.text(message), photo);
        } catch (Exception e) {
            reportFailure(context, photo, e);
        }
    }

    private static void requirePermission(Context context, String permission, String reason) {
        if (context.checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw new IllegalStateException(reason);
        }
    }

    private static void dispatch(Context context, SmsManager manager, String number, String message, boolean photo) {
        try {
            if (photo) {
                PhotoRuntime.captureForMessage(context, new PhotoRuntime.Callback() {
                    @Override public void ready(byte[] jpeg) {
                        try { MmsRuntime.send(context, manager, number, message, jpeg); }
                        catch (Exception e) { reportFailure(context, true, e); }
                    }
                    @Override public void failed(String reason) {
                        DeviceActionRuntime.notice(context, reason + "；彩信未发送");
                    }
                });
            } else sendText(context, manager, number, message);
        } catch (Exception e) { reportFailure(context, photo, e); }
    }

    private static void reportFailure(Context context, boolean photo, Exception error) {
        // 只输出异常类型，系统异常可能夹带号码、文件路径等信息。
        Log.w(DeviceActionRuntime.TAG, (photo ? "MMS" : "SMS") + " preparation/submission failed: " + error.getClass().getSimpleName());
        String reason = error instanceof IllegalStateException || error instanceof IllegalArgumentException
                ? error.getMessage() : null;
        DeviceActionRuntime.notice(context, reason == null ? (photo
                ? "彩信准备或提交失败，请检查安全服务权限、默认短信卡及运营商彩信配置"
                : "短信提交失败，请检查安全服务短信权限和默认短信卡") : reason);
    }

    private static SmsManager defaultManager() throws Exception {
        Class<?> subscriptions = Class.forName("android.telephony.SubscriptionManager");
        int subscription = (Integer) subscriptions.getMethod("getDefaultSmsSubscriptionId").invoke(null);
        if (subscription < 0) throw new IllegalStateException("请先设置默认短信 SIM 卡");
        return (SmsManager) SmsManager.class.getMethod("getSmsManagerForSubscriptionId", int.class)
                .invoke(null, subscription);
    }

    private static void sendText(Context context, SmsManager manager, String number, String message) throws Exception {
        ArrayList<String> parts = manager.divideMessage(message);
        if (parts == null || parts.isEmpty()) throw new IllegalArgumentException("短信内容为空");
        // 系统可能按运营商规则转换分段中的字符；历史记录与实际提交的完整正文一致。
        SmsSentHistory history = SmsSentHistory.prepare(context, manager, number, String.join("", parts));
        SentResult result = new SentResult(context, parts.size(), history);
        try {
            result.register();
            manager.sendMultipartTextMessage(number, null, parts, result.intents, null);
        } catch (Exception e) {
            result.close();
            throw e;
        }
        // 不在日志中记录电话号码或短信正文。
        Log.i(DeviceActionRuntime.TAG, "SMS submitted, parts=" + parts.size());
    }

    private static final class SentResult extends BroadcastReceiver {
        final Context context;
        final int parts;
        final SmsSendProgress progress;
        final SmsSentHistory history;
        final ArrayList<PendingIntent> intents = new ArrayList<>();
        final String action;
        final Runnable timeout;
        boolean registered;
        boolean closed;

        SentResult(Context context, int parts, SmsSentHistory history) {
            this.context = context;
            this.parts = parts;
            this.progress = new SmsSendProgress(parts);
            this.history = history;
            action = context.getPackageName() + ".hyper_auto.SMS_SENT_" + UUID.randomUUID();
            timeout = () -> {
                if (closed) return;
                DeviceActionRuntime.notice(context, DeviceActionRuntime.text("尚未收到短信发送结果", "SMS send result has not arrived"));
                close();
            };
        }

        void register() throws Exception {
            ActionReceivers.register(context, this, action, DeviceActionRuntime.worker());
            registered = true;
            for (int i = 0; i < parts; i++) {
                // 系统会在回调中填入已保存短信的 uri；IMMUTABLE 会丢弃这些信息。
                // 随机 action、固定包名和单次 PendingIntent 限制回调入口。
                int flags = PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 : 0);
                intents.add(PendingIntent.getBroadcast(context, i,
                        new Intent(action).setPackage(context.getPackageName()).putExtra("part", i),
                        flags));
            }
            DeviceActionRuntime.worker().postDelayed(timeout, 120_000L);
        }

        @Override public void onReceive(Context c, Intent intent) {
            if (closed || intent == null || !action.equals(intent.getAction())) return;
            int part = intent.getIntExtra("part", -1);
            SmsSendProgress.Result result = progress.accept(part, getResultCode() == Activity.RESULT_OK);
            if (result == SmsSendProgress.Result.IGNORED) return;
            if (result == SmsSendProgress.Result.FAILED) {
                Log.w(DeviceActionRuntime.TAG, "SMS send failed, code=" + getResultCode());
                DeviceActionRuntime.notice(context, "短信发送失败（错误码 " + getResultCode() + "），请检查默认短信卡、信号及短信权限");
                close();
                return;
            }
            history.noteProviderUri(intent.getStringExtra("uri"));
            if (result == SmsSendProgress.Result.SENT) {
                close();
                boolean recorded = history.ensureRecorded();
                DeviceActionRuntime.notice(context, recorded
                        ? DeviceActionRuntime.text("短信已发送并保存记录", "SMS sent and saved")
                        : DeviceActionRuntime.text("短信已发送，但未能保存到本机短信记录，请检查安全服务的短信访问权限",
                                "SMS sent, but the sent record could not be saved; check Security Center SMS access"));
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            progress.close();
            DeviceActionRuntime.worker().removeCallbacks(timeout);
            if (registered) {
                try { context.unregisterReceiver(this); } catch (IllegalArgumentException ignored) {}
                registered = false;
            }
            for (PendingIntent intent : intents) intent.cancel();
            intents.clear();
        }
    }
}
