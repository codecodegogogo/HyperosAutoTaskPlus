package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;
import miuix.appcompat.app.AlertDialog;

/** 注入侧结果入口。编辑沿用 miuix；耗时设备操作放在专用线程，不能阻塞 auto_task。 */
public final class DeviceActionRuntime {
    static final String TAG = "HyperAutoEnh";
    private static Handler sWorker;

    private DeviceActionRuntime() {}

    static synchronized Handler worker() {
        if (sWorker == null) {
            HandlerThread thread = new HandlerThread("hyper_auto_device_actions");
            thread.start();
            sWorker = new Handler(thread.getLooper());
        }
        return sWorker;
    }

    public static Class<?> itemClass(String key) {
        return FirstAppKeys.isDeviceActionKey(key) ? DeviceActionResultItem.class : null;
    }

    public static Object newItem(String key) {
        return FirstAppKeys.isDeviceActionKey(key) ? new DeviceActionResultItem(key) : null;
    }

    public static boolean isItem(Object item) { return item instanceof DeviceActionResultItem; }

    public static void pickAndApply(Context context, Object value, Runnable confirm) {
        if (context == null || !(value instanceof DeviceActionResultItem)) return;
        final DeviceActionResultItem item = (DeviceActionResultItem) value;
        if (FirstAppKeys.KEY_SCREENSHOT_RESULT.equals(item.e())) {
            if (confirm != null) confirm.run();
        } else if (item.isEmail()) {
            EmailEditor.pick(context, item, confirm);
        } else if (item.isAudioPlayback()) {
            AudioPickerFragment.pick(context, item, confirm);
        } else if (FirstAppKeys.KEY_NOTIFICATION_RESULT.equals(item.e())) {
            pickNotification(context, item, confirm);
        } else if (FirstAppKeys.KEY_PHOTO_RESULT.equals(item.e())) {
            pickCameras(context, item, confirm);
        } else if (item.isRecording()) {
            CharSequence[] choices = {text("开始", "Start"), text("停止", "Stop")};
            final int[] picked = {item.isStartRecording() ? 0 : 1};
            new AlertDialog.Builder(context).setTitle(item.h())
                    .setSingleChoiceItems(choices, picked[0], (dialog, which) -> picked[0] = which)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        item.setStartRecording(picked[0] == 0);
                        if (confirm != null) confirm.run();
                    })
                    .setNegativeButton(android.R.string.cancel, null).show();
        } else {
            pickContact(context, item, confirm);
        }
    }

    private static void pickCameras(Context context, DeviceActionResultItem item, Runnable confirm) {
        boolean[] picked = {item.isRearCamera(), item.isFrontCamera()};
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(item.h())
                .setMultiChoiceItems(new CharSequence[]{text("后置摄像头", "Rear camera"), text("前置摄像头", "Front camera")},
                        picked, (whichDialog, which, checked) -> picked[which] = checked)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null).show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!picked[0] && !picked[1]) {
                notice(context, text("至少选择一个摄像头", "Select at least one camera"));
                return;
            }
            item.setCameras(picked[0], picked[1]);
            if (confirm != null) confirm.run();
            dialog.dismiss();
        });
    }

    private static void pickNotification(Context context, DeviceActionResultItem item, Runnable confirm) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = InjectUi.dp(context, 20);
        column.setPadding(padding, padding / 2, padding, 0);
        addInputLabel(context, column, text("通知内容", "Notification text"), 0);
        EditText body = new EditText(context);
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        body.setMinLines(3);
        body.setGravity(Gravity.TOP | Gravity.START);
        body.setHint(text("输入要显示的文字", "Enter the text to display"));
        body.setText(item.getNotificationText());
        column.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(column);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(item.h()).setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null).show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            String content = body.getText().toString();
            if (!DeviceActionResultItem.validNotificationText(content)) {
                body.setError(text("请输入通知内容", "Enter notification text"));
                body.requestFocus();
                return;
            }
            item.setNotificationText(content);
            if (confirm != null) confirm.run();
            dialog.dismiss();
        });
    }

    private static void pickContact(Context context, DeviceActionResultItem item, Runnable confirm) {
        boolean sms = FirstAppKeys.KEY_SMS_RESULT.equals(item.e());
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = InjectUi.dp(context, 20);
        column.setPadding(padding, padding / 2, padding, 0);
        if (sms) addInputLabel(context, column, text("电话号码", "Phone number"), 0);
        EditText number = new EditText(context);
        number.setInputType(InputType.TYPE_CLASS_PHONE);
        number.setSingleLine(true);
        number.setHint(text("电话号码", "Phone number"));
        number.setText(item.getPhoneNumber());
        column.addView(number, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        EditText message = new EditText(context);
        CheckBox location = new CheckBox(context);
        if (sms) {
            addInputLabel(context, column, text("短信内容", "Message"), 20);
            message.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            message.setMinLines(3);
            message.setGravity(Gravity.TOP | Gravity.START);
            message.setHint(text(LocationMessage.HEADER, "This device triggered HyperosAutoTaskPlus"));
            message.setText(item.getMessage());
            column.addView(message, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            location.setText(text("发送当前位置", "Send current location"));
            location.setChecked(item.isSendLocation());
            location.setTextSize(14);
            location.setMinHeight(InjectUi.dp(context, 48));
            column.addView(location);
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(column);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(item.h()).setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null).show();
        // 接管确定按钮，校验失败时不关闭；取消和返回键不写回原对象。
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            String phone = number.getText().toString();
            String body = sms ? message.getText().toString() : "";
            if (!DeviceActionResultItem.validNumber(phone)) {
                number.setError(text("请输入有效的单个电话号码", "Enter one valid phone number"));
                number.requestFocus();
                return;
            }
            if (sms && !DeviceActionResultItem.validMessage(body, location.isChecked(), false)) {
                message.setError(text("请输入内容或勾选当前位置", "Enter text or select current location"));
                message.requestFocus();
                return;
            }
            item.setContact(phone, body);
            if (sms) item.setMessageAttachments(location.isChecked(), false);
            if (confirm != null) confirm.run();
            dialog.dismiss();
        });
    }

    private static void addInputLabel(Context context, LinearLayout column, String text, int topGap) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = InjectUi.dp(context, topGap);
        params.bottomMargin = InjectUi.dp(context, 6);
        column.addView(label, params);
    }

    static void execute(DeviceActionResultItem item, boolean restore) {
        // 在引擎线程取得配置快照，任务之后被编辑也不能改变已经排队的操作。
        if (!item.l() || (restore && !item.restoresOnExit())) return;
        String key = item.e();
        String owner = item.j();
        boolean start = item.isStartRecording();
        int cameras = item.getCameraSelection();
        String phone = DeviceActionResultItem.normalizeNumber(item.getPhoneNumber());
        String message = item.getMessage();
        boolean sendLocation = item.isSendLocation();
        EmailConfig emailConfig = item.isEmail() ? item.getEmailConfig() : null;
        String audioUri = item.getAudioUri();
        String audioName = item.getAudioName();
        String notificationText = item.getNotificationText();
        worker().post(() -> {
            Context context = InjectUi.context();
            if (context == null) {
                Log.e(TAG, "device result: no application context");
                return;
            }
            try {
                if (FirstAppKeys.KEY_AUDIO_RECORD_RESULT.equals(key) || FirstAppKeys.KEY_SCREEN_RECORD_RESULT.equals(key)) {
                    RecordingRuntime.apply(context, FirstAppKeys.KEY_SCREEN_RECORD_RESULT.equals(key),
                            !restore && start, restore, owner);
                } else if (FirstAppKeys.KEY_SCREENSHOT_RESULT.equals(key)) {
                    ScreenshotRuntime.capture(context);
                } else if (FirstAppKeys.KEY_PHOTO_RESULT.equals(key)) {
                    PhotoRuntime.capture(context, cameras);
                } else if (FirstAppKeys.KEY_CALL_RESULT.equals(key)) {
                    context.startActivity(new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", phone, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } else if (FirstAppKeys.KEY_SMS_RESULT.equals(key)) {
                    // 彩信入口暂时停用；兼容旧配置时也不能被隐藏的照片标志切换为彩信。
                    SmsRuntime.send(context, phone, message, sendLocation, false);
                } else if (FirstAppKeys.KEY_EMAIL_RESULT.equals(key)) {
                    EmailRuntime.send(context, emailConfig);
                } else if (FirstAppKeys.KEY_PLAY_AUDIO_RESULT.equals(key)) {
                    if (restore) AudioPlaybackRuntime.stopOwned(owner);
                    else AudioPlaybackRuntime.play(context, owner, audioUri, audioName);
                } else if (FirstAppKeys.KEY_NOTIFICATION_RESULT.equals(key)) {
                    NotificationRuntime.send(context, owner, notificationText);
                }
            } catch (Throwable t) {
                failure(context, title(key), t);
            }
        });
    }

    static void failure(Context context, String action, Throwable error) {
        Log.e(TAG, "device result failed: " + action, error);
        notice(context, action + text("失败，请检查权限或设备状态", " failed; check permissions or device state"));
    }

    static void notice(Context context, String message) {
        new Handler(context.getMainLooper()).post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    static String text(String zh, String en) { return InjectUi.zh() ? zh : en; }

    public static String title(String key) {
        if (FirstAppKeys.KEY_AUDIO_RECORD_RESULT.equals(key)) return text("录音", "Audio recording");
        if (FirstAppKeys.KEY_SCREEN_RECORD_RESULT.equals(key)) return text("录屏", "Screen recording");
        if (FirstAppKeys.KEY_SCREENSHOT_RESULT.equals(key)) return text("截图", "Screenshot");
        if (FirstAppKeys.KEY_PHOTO_RESULT.equals(key)) return text("拍照", "Take photo");
        if (FirstAppKeys.KEY_CALL_RESULT.equals(key)) return text("打电话", "Phone call");
        if (FirstAppKeys.KEY_SMS_RESULT.equals(key)) return text("发送短信", "Send SMS");
        if (FirstAppKeys.KEY_EMAIL_RESULT.equals(key)) return text("发送邮件", "Send email");
        if (FirstAppKeys.KEY_PLAY_AUDIO_RESULT.equals(key)) return text("播放音频", "Play audio");
        if (FirstAppKeys.KEY_NOTIFICATION_RESULT.equals(key)) return text("发出通知", "Send notification");
        return text("设备操作", "Device action");
    }

    static String summary(DeviceActionResultItem item) {
        if (item.isEmail()) {
            EmailConfig config = item.getEmailConfig();
            String to = config.to.isEmpty() ? text("配置邮箱", "Configure email") : config.to;
            return item.h() + " · " + to
                    + (config.sendLocation ? text(" + 当前位置", " + current location") : "")
                    + (config.sendPhoto ? text(" + 环境照片", " + photo") : "");
        }
        if (FirstAppKeys.KEY_NOTIFICATION_RESULT.equals(item.e())) {
            String body = item.getNotificationText().replace('\n', ' ').replace('\r', ' ');
            if (body.codePointCount(0, body.length()) > 40) body = body.substring(0, body.offsetByCodePoints(0, 40)) + "…";
            return item.h() + " · " + (body.isEmpty() ? text("填写通知内容", "Enter notification text") : body);
        }
        if (item.isAudioPlayback()) return item.h() + " · " + (item.getAudioName().isEmpty()
                ? text("选择音频文件", "Choose an audio file") : item.getAudioName());
        if (item.isRecording()) return (item.isStartRecording() ? text("开始", "Start ") : text("停止", "Stop ")) + item.h();
        if (FirstAppKeys.KEY_PHOTO_RESULT.equals(item.e())) return text("拍照 · ", "Photo · ")
                + (item.isRearCamera() && item.isFrontCamera() ? text("后置 + 前置摄像头", "Rear + front cameras")
                        : item.isFrontCamera() ? text("前置摄像头", "Front camera") : text("后置摄像头", "Rear camera"));
        if (FirstAppKeys.KEY_CALL_RESULT.equals(item.e())) return item.h() + " · " + item.getPhoneNumber();
        if (FirstAppKeys.KEY_SMS_RESULT.equals(item.e())) {
            String body = item.getMessage().replace('\n', ' ');
            if (body.codePointCount(0, body.length()) > 40) body = body.substring(0, body.offsetByCodePoints(0, 40)) + "…";
            if (item.isSendLocation()) body += (body.isEmpty() ? "" : " + ") + text("当前位置", "Current location");
            return item.getPhoneNumber() + " · " + body;
        }
        return item.h();
    }

    private static final Map<String, int[]> sIcons = new HashMap<>();

    /** 0 正常、1 灰色、2 半透明；借自动任务原生条件/结果的三态图，缺了才用系统图标兜底 */
    static int icon(String key, int which) {
        int[] icons;
        synchronized (sIcons) {
            icons = sIcons.get(key);
            if (icons == null) {
                icons = loadIcons(key);
                if (InjectUi.context() != null) sIcons.put(key, icons);
            }
        }
        return icons[Math.max(0, Math.min(which, 2))];
    }

    private static int[] loadIcons(String key) {
        if (FirstAppKeys.KEY_EMAIL_RESULT.equals(key))
            return InjectUi.icons("auto_task_icon_start_activity", android.R.drawable.sym_def_app_icon);
        if (FirstAppKeys.KEY_NOTIFICATION_RESULT.equals(key))
            return InjectUi.icons("auto_task_icon_twinkle", android.R.drawable.ic_dialog_info);
        if (FirstAppKeys.KEY_PLAY_AUDIO_RESULT.equals(key))
            return InjectUi.icons("auto_task_icon_adjust_volume", android.R.drawable.ic_media_play);
        if (FirstAppKeys.KEY_AUDIO_RECORD_RESULT.equals(key))
            return InjectUi.icons("auto_task_icon_adjust_volume", android.R.drawable.ic_lock_silent_mode_off);
        if (FirstAppKeys.KEY_SCREEN_RECORD_RESULT.equals(key)
                || FirstAppKeys.KEY_SCREENSHOT_RESULT.equals(key)
                || FirstAppKeys.KEY_PHOTO_RESULT.equals(key))
            return InjectUi.icons("auto_task_icon_screen_display", android.R.drawable.presence_video_online);
        if (FirstAppKeys.KEY_CALL_RESULT.equals(key))
            return InjectUi.icons("auto_task_icon_incall", android.R.drawable.sym_action_call);
        return InjectUi.icons("auto_task_icon_dial_tone", android.R.drawable.sym_action_email);
    }
}
