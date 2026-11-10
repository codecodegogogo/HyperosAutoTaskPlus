package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
    private static final String PREFS_SHELL = "hyper_auto_shell";
    private static final String PREF_ROOT_NOTICE_DISMISSED = "root_notice_dismissed";
    /** 长按问号弹出的常用命令；只放只读或可恢复的操作，不放重启、清数据这类危险命令。 */
    private static final String[][] SHELL_HELP = {
            {"打开网址", "am start -f 0x10000000 -a android.intent.action.VIEW -d \"http://example.com\""},
            {"打开应用（替换包名）", "monkey -p com.example.app -c android.intent.category.LAUNCHER 1"},
            {"打开系统设置", "am start -a android.settings.SETTINGS"},
            {"打开 WLAN 设置", "am start -a android.settings.WIFI_SETTINGS"},
            {"打开蓝牙设置", "am start -a android.settings.BLUETOOTH_SETTINGS"},
            {"打开应用详情（替换包名）", "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.example.app"},
            {"回到桌面", "input keyevent KEYCODE_HOME"},
            {"返回上一页", "input keyevent KEYCODE_BACK"},
            {"截屏到内部存储", "screencap -p /sdcard/DCIM/screenshot.png"},
            {"查看当前前台应用", "dumpsys activity activities | grep mResumedActivity"},
    };
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
        } else if (FirstAppKeys.KEY_SHELL_RESULT.equals(item.e())) {
            pickShell(context, item, confirm);
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

    /**
     * 「执行命令」首次点击先说明需要 root 权限。按钮倒计时结束后才能点，
     * 点击时主动发起 su 授权请求；「不再提示」放在左侧次要位置，点了以后不再显示说明。
     */
    private static void pickShell(Context context, DeviceActionResultItem item, Runnable confirm) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS_SHELL, Context.MODE_PRIVATE);
        if (preferences.getBoolean(PREF_ROOT_NOTICE_DISMISSED, false)) {
            editShell(context, item, confirm);
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(text("执行命令需要 root 权限", "Root permission required"))
                .setMessage(text(
                        "命令会以 root 身份执行。请手动授予安全服务 root 权限；"
                                + "点击「知道了」后会向 root 管理器发起授权请求。",
                        "Commands run as root. Manually grant the security service root permission; "
                                + "tapping \"Got it\" sends an authorization request to your root manager."))
                .setPositiveButton(text("知道了", "Got it"), null)
                .setNegativeButton(text("不再提示", "Don't show again"), (whichDialog, which) -> {
                    preferences.edit().putBoolean(PREF_ROOT_NOTICE_DISMISSED, true).apply();
                    editShell(context, item, confirm);
                })
                .show();
        Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        countdown(dialog, positive, 3, () -> {
            ShellRuntime.requestRoot(context);
            editShell(context, item, confirm);
            dialog.dismiss();
        });
    }

    /** 说明文字至少展示几秒，避免用户没看内容就点掉。 */
    private static void countdown(AlertDialog dialog, Button button, int seconds, Runnable onDone) {
        Handler handler = new Handler(Looper.getMainLooper());
        final int[] remaining = {seconds};
        button.setEnabled(false);
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!dialog.isShowing()) return;
                if (remaining[0] <= 0) {
                    button.setEnabled(true);
                    button.setText(text("知道了", "Got it"));
                    button.setOnClickListener(view -> onDone.run());
                    return;
                }
                button.setText(text("知道了（" + remaining[0] + "）", "Got it (" + remaining[0] + ")"));
                remaining[0]--;
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(tick);
    }

    private static void editShell(Context context, DeviceActionResultItem item, Runnable confirm) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = InjectUi.dp(context, 20);
        column.setPadding(padding, padding / 2, padding, 0);
        column.addView(shellLabelRow(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        EditText script = new EditText(context);
        script.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        script.setMinLines(6);
        script.setGravity(Gravity.TOP | Gravity.START);
        script.setTypeface(Typeface.MONOSPACE);
        script.setHint(text("长按问号查看常用命令", "Long-press ? for common commands"));
        script.setText(item.getShellScript());
        column.addView(script, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView hint = new TextView(context);
        hint.setText(text("命令以 root 身份执行。任务配置可以导出，请勿分享包含密码等敏感信息的命令。",
                "Commands run as root. Task configurations can be exported; don't share commands "
                        + "containing passwords or other secrets."));
        hint.setTextSize(12);
        hint.setPadding(0, InjectUi.dp(context, 12), 0, 0);
        column.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(column);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(item.h()).setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null).show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = script.getText().toString();
            if (!DeviceActionResultItem.validShellScript(value)) {
                script.setError(text("请输入要执行的命令", "Enter a command"));
                script.requestFocus();
                return;
            }
            item.setShellScript(value);
            if (confirm != null) confirm.run();
            dialog.dismiss();
        });
    }

    /** 标题行右侧的问号，长按打开常用命令帮助。 */
    private static View shellLabelRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, InjectUi.dp(context, 8), 0, InjectUi.dp(context, 6));
        TextView label = new TextView(context);
        label.setText(text("Shell 脚本", "Shell script"));
        label.setTextSize(14);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView help = new TextView(context);
        help.setText("?");
        help.setTextSize(18);
        help.setGravity(Gravity.CENTER);
        help.setTextColor(0xFF1A73E8);
        int size = InjectUi.dp(context, 32);
        help.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        help.setOnLongClickListener(view -> {
            showShellHelp(context);
            return true;
        });
        row.addView(help);
        return row;
    }

    private static void showShellHelp(Context context) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = InjectUi.dp(context, 20);
        column.setPadding(padding, padding / 2, padding, 0);
        for (String[] entry : SHELL_HELP) {
            TextView title = new TextView(context);
            title.setText(entry[0]);
            title.setTextSize(15);
            title.setPadding(0, InjectUi.dp(context, 12), 0, InjectUi.dp(context, 2));
            column.addView(title);
            TextView command = new TextView(context);
            command.setText(entry[1]);
            command.setTextSize(12);
            command.setTypeface(Typeface.MONOSPACE);
            command.setPadding(0, 0, 0, InjectUi.dp(context, 4));
            column.addView(command);
            View.OnClickListener copy = view -> copyCommand(context, entry[1]);
            title.setOnClickListener(copy);
            command.setOnClickListener(copy);
        }
        TextView hint = new TextView(context);
        hint.setText(text("点击命令即可复制，回到输入框粘贴后修改。",
                "Tap a command to copy it, then paste and edit in the input box."));
        hint.setTextSize(12);
        hint.setPadding(0, InjectUi.dp(context, 12), 0, 0);
        column.addView(hint);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(column);
        new AlertDialog.Builder(context)
                .setTitle(text("常用命令", "Common commands"))
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static void copyCommand(Context context, String command) {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("命令", command));
                notice(context, text("已复制到剪贴板", "Copied to clipboard"));
            }
        } catch (Throwable t) {
            Log.e(TAG, "copy command failed", t);
            notice(context, text("复制失败", "Copy failed"));
        }
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
        String shellScript = item.getShellScript();
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
                } else if (FirstAppKeys.KEY_SHELL_RESULT.equals(key)) {
                    ShellRuntime.run(context, shellScript);
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
        if (FirstAppKeys.KEY_SHELL_RESULT.equals(key)) return text("执行命令", "Run command");
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
        if (FirstAppKeys.KEY_SHELL_RESULT.equals(item.e())) {
            String body = item.getShellScript().replace('\n', ' ').replace('\r', ' ');
            if (body.codePointCount(0, body.length()) > 40) {
                body = body.substring(0, body.offsetByCodePoints(0, 40)) + "…";
            }
            return item.h() + (body.isEmpty() ? "" : " · " + body);
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
        if (FirstAppKeys.KEY_SHELL_RESULT.equals(key))
            return InjectUi.icons("auto_task_icon_twinkle", android.R.drawable.ic_menu_manage);
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
