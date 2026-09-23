package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import miuix.appcompat.app.AlertDialog;

/** 只有确认且凭据持久化成功才写回任务；编辑器不会发起邮件或设备操作。 */
public final class EmailEditor {
    private static final int SSL_ID = 0x48454d01;
    private static final int START_TLS_ID = 0x48454d02;

    private EmailEditor() {}

    public static void pick(Context context, DeviceActionResultItem item, Runnable confirm) {
        if (context == null || item == null) return;
        final Activity owner = activity(context);
        if (!alive(owner)) {
            DeviceActionRuntime.notice(context, "请在任务编辑页配置邮件");
            return;
        }
        final EmailConfig rollbackSnapshot = item.snapshotEmailConfig();
        final EmailConfig original = rollbackSnapshot == null ? new EmailConfig() : rollbackSnapshot.copy();
        // 页面启动只检查引用语法，不在 UI 线程读取磁盘或解密；确认时在后台严格验证。
        final boolean hasCredential = EmailConfig.validCredentialId(original.credentialId);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = InjectUi.dp(context, 20);
        column.setPadding(padding, padding / 2, padding, padding / 2);

        addHint(context, column, "请先在邮箱设置中开启 SMTP，并取得 SMTP 授权码或应用密码。"
                + "QQ、163 可选择下方预设，其它邮箱请填写服务商提供的参数。仅支持 SMTP 密码认证，不支持 OAuth 登录。", 0);
        Button preset = new Button(context);
        preset.setText("选择邮箱预设（可选）");
        column.addView(preset, fullWidth());

        EditText from = input(context, column, "发件邮箱", "例如 example@qq.com", original.from,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 12);
        EditText to = input(context, column, "收件邮箱", "填写一个收件邮箱", original.to,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 12);
        EditText host = input(context, column, "SMTP 服务器", "例如 smtp.qq.com", original.smtpHost,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, 12);
        EditText port = input(context, column, "SMTP 端口", "SSL/TLS 常用 465，STARTTLS 常用 587",
                String.valueOf(original.smtpPort), InputType.TYPE_CLASS_NUMBER, 12);

        label(context, column, "加密方式", 12);
        RadioGroup encryption = new RadioGroup(context);
        encryption.setOrientation(LinearLayout.VERTICAL);
        RadioButton ssl = new RadioButton(context);
        ssl.setId(SSL_ID);
        ssl.setText("SSL/TLS（连接时加密，常用 465）");
        RadioButton startTls = new RadioButton(context);
        startTls.setId(START_TLS_ID);
        startTls.setText("STARTTLS（升级为加密连接，常用 587）");
        encryption.addView(ssl, fullWidth());
        encryption.addView(startTls, fullWidth());
        encryption.check(original.startTls ? START_TLS_ID : SSL_ID);
        encryption.setOnCheckedChangeListener((group, checked) -> {
            String value = port.getText().toString().trim();
            if ("465".equals(value) || "587".equals(value)) port.setText(checked == START_TLS_ID ? "587" : "465");
        });
        column.addView(encryption, fullWidth());
        preset.setOnClickListener(view -> pickPreset(context, host, port, encryption));

        EditText username = input(context, column, "SMTP 登录账号", "留空则使用发件邮箱", original.username,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 12);
        EditText password = input(context, column, "SMTP 授权码 / 应用密码", hasCredential ? "已配置，留空验证后沿用" : "请输入授权码或应用密码", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 12);
        password.setSaveEnabled(false);
        password.setFreezesText(false);
        // 禁止输入法学习和系统自动填充；不把授权码带入 View 状态或账号数据库。
        password.setImeOptions(password.getImeOptions() | 0x01000000);
        try {
            android.view.View.class.getMethod("setImportantForAutofill", int.class).invoke(password, 2);
        } catch (Exception ignored) {}
        addHint(context, column, hasCredential
                ? "留空时将验证并沿用本机已保存的授权码。更换发件邮箱、登录账号或 SMTP 服务器后需重新输入。"
                : "授权码仅加密保存在本机，任务中只保存凭据编号。请勿将授权码发送到聊天或填写在正文中。", 4);

        EditText subject = input(context, column, "邮件主题", "输入邮件主题", original.subject,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 12);
        label(context, column, "补充正文（可选）", 12);
        EditText body = new EditText(context);
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        body.setMinLines(3);
        body.setGravity(Gravity.TOP | Gravity.START);
        body.setHint("固定通知与位置信息之后的补充文字");
        body.setText(original.body);
        column.addView(body, fullWidth());

        LinearLayout attachments = new LinearLayout(context);
        attachments.setOrientation(LinearLayout.HORIZONTAL);
        attachments.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox location = attachment(context, "发送当前位置", original.sendLocation);
        CheckBox photo = attachment(context, "发送环境照片", original.sendPhoto);
        attachments.addView(location, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        attachments.addView(photo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        column.addView(attachments, fullWidth());
        addHint(context, column, "正文自动添加“" + LocationMessage.HEADER + "”。勾选位置时包含地点、经纬度和高德地图链接；"
                + "勾选照片时拍摄后置环境照片作为附件。发送需要可用网络及邮箱 SMTP 服务，位置和照片需要相应权限。"
                + "确认仅保存配置，任务触发时才会发送。", 4);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(column);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle("发送邮件").setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null).show();
        AtomicBoolean dismissed = new AtomicBoolean(false);
        dialog.setOnDismissListener(whichDialog -> {
            dismissed.set(true);
            password.setText("");
        });
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!alive(owner)) {
                closeEditor(dialog, password, dismissed);
                return;
            }
            EmailConfig draft = original.copy();
            draft.from = from.getText().toString().trim();
            draft.to = to.getText().toString().trim();
            draft.smtpHost = host.getText().toString().trim();
            draft.username = username.getText().toString().trim();
            if (draft.username.isEmpty()) draft.username = draft.from;
            draft.startTls = encryption.getCheckedRadioButtonId() == START_TLS_ID;
            draft.subject = subject.getText().toString();
            draft.body = body.getText().toString();
            draft.sendLocation = location.isChecked();
            draft.sendPhoto = photo.isChecked();
            try {
                draft.smtpPort = Integer.parseInt(port.getText().toString().trim());
            } catch (NumberFormatException e) {
                port.setError("请输入 1 到 65535 之间的端口");
                port.requestFocus();
                return;
            }
            String error = draft.validationError(false);
            if (error != null) {
                DeviceActionRuntime.notice(context, error);
                return;
            }
            String secret = password.getText().toString().trim();
            if (!secret.isEmpty() && !EmailConfig.validSecret(secret)) {
                password.setError("授权码不能含换行或控制字符，且不能超过 4096 个字符");
                password.requestFocus();
                return;
            }
            if (secret.isEmpty() && (!hasCredential || changedAccount(original, draft))) {
                password.setError(hasCredential ? "更换账号或 SMTP 服务器后，请重新输入授权码" : "请输入 SMTP 授权码或应用密码");
                password.requestFocus();
                return;
            }
            // KeyStore 和磁盘提交可能耗时；等待期间仍可取消，完成后检查弹窗状态。
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText("保存中…");
            DeviceActionRuntime.worker().post(() -> save(context, owner, item, confirm, rollbackSnapshot, draft, secret,
                    dialog, password, dismissed));
        });
    }

    private static void save(Context context, Activity owner, DeviceActionResultItem item, Runnable confirm,
                             EmailConfig rollbackSnapshot, EmailConfig draft, String secret, AlertDialog dialog,
                             EditText password, AtomicBoolean dismissed) {
        if (dismissed.get()) return;
        String newId = null;
        try {
            if (!secret.isEmpty()) {
                newId = EmailCredentialStore.save(context, draft, secret);
                draft.credentialId = newId;
            } else if (!EmailCredentialStore.exists(context, draft)) {
                throw new IllegalStateException("原授权码已不可用，请重新输入");
            }
            String error = draft.validationError(true);
            if (error != null) throw new IllegalArgumentException(error);
        } catch (Exception ignored) {
            discard(context, newId);
            new Handler(context.getMainLooper()).post(() -> {
                if (dismissed.get() || !dialog.isShowing() || !alive(owner)) {
                    closeEditor(dialog, password, dismissed);
                    return;
                }
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(android.R.string.ok);
                password.setError(secret.isEmpty() ? "原授权码已不可用或与当前账号不匹配，请重新输入"
                        : "无法安全保存授权码，请检查设备存储后重试");
                password.requestFocus();
            });
            return;
        }
        final String createdId = newId;
        new Handler(context.getMainLooper()).post(() -> {
            // OnDismissListener 由消息队列派发；isShowing 也要检查，覆盖取消回调尚未送达的窗口。
            if (dismissed.get() || !dialog.isShowing() || !alive(owner)) {
                closeEditor(dialog, password, dismissed);
                if (createdId != null) DeviceActionRuntime.worker().post(() -> discard(context, createdId));
                return;
            }
            try {
                item.setEmailConfig(draft);
                if (confirm != null) confirm.run();
            } catch (Exception ignored) {
                item.restoreEmailConfig(rollbackSnapshot);
                // 回调可能已部分持久化，保留新凭据，避免磁盘中的任务引用被删除的 ID。
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(android.R.string.ok);
                DeviceActionRuntime.notice(context, "提交邮件配置时出错，请重新打开任务检查");
                return;
            }
            // 回调已完成，窗口销毁失败不能再回滚已经提交的配置。
            closeEditor(dialog, password, dismissed);
        });
    }

    private static boolean changedAccount(EmailConfig original, EmailConfig draft) {
        try {
            return !Arrays.equals(EmailCredentialStore.associatedData(original, original.credentialId),
                    EmailCredentialStore.associatedData(draft, original.credentialId));
        } catch (IllegalArgumentException ignored) {
            return true;
        }
    }

    private static Activity activity(Context context) {
        Context current = context;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current instanceof Activity) return (Activity) current;
            if (!(current instanceof ContextWrapper)) return null;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) return null;
            current = base;
        }
        return null;
    }

    private static boolean alive(Activity owner) {
        if (owner == null || owner.isFinishing()) return false;
        try {
            // 项目编译用 API 16 的桩，运行环境支持 API 17 起的 isDestroyed。
            return !((Boolean) Activity.class.getMethod("isDestroyed").invoke(owner));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void closeEditor(AlertDialog dialog, EditText password, AtomicBoolean dismissed) {
        dismissed.set(true);
        password.setText("");
        try {
            dialog.dismiss();
        } catch (Exception ignored) {
            // Activity 已销毁时窗口可能已移除；取消仍然生效，不能继续写回旧任务。
        }
    }

    private static void discard(Context context, String id) {
        if (id == null) return;
        try {
            EmailCredentialStore.remove(context, id);
        } catch (Exception ignored) {
            DeviceActionRuntime.notice(context, "未使用的邮件凭据清理失败，请检查设备存储");
        }
    }

    private static void pickPreset(Context context, EditText host, EditText port, RadioGroup encryption) {
        String current = host.getText().toString().trim();
        final int[] selected = {"smtp.qq.com".equalsIgnoreCase(current) ? 0 : "smtp.163.com".equalsIgnoreCase(current) ? 1 : 2};
        new AlertDialog.Builder(context).setTitle("邮箱预设")
                .setSingleChoiceItems(new CharSequence[]{"QQ 邮箱（smtp.qq.com）", "163 邮箱（smtp.163.com）", "其它邮箱（自定义）"},
                        selected[0], (dialog, which) -> selected[0] = which)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (selected[0] == 2) {
                        host.requestFocus();
                        return;
                    }
                    host.setText(selected[0] == 0 ? "smtp.qq.com" : "smtp.163.com");
                    encryption.check(SSL_ID);
                    port.setText("465");
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private static EditText input(Context context, LinearLayout column, String title, String hint,
                                  String value, int inputType, int topGap) {
        label(context, column, title, topGap);
        EditText input = new EditText(context);
        input.setInputType(inputType);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(value(value));
        column.addView(input, fullWidth());
        return input;
    }

    private static CheckBox attachment(Context context, String title, boolean checked) {
        CheckBox checkbox = new CheckBox(context);
        checkbox.setText(title);
        checkbox.setTextSize(14);
        checkbox.setMinHeight(InjectUi.dp(context, 48));
        checkbox.setChecked(checked);
        return checkbox;
    }

    private static void label(Context context, LinearLayout column, String title, int topGap) {
        TextView label = new TextView(context);
        label.setText(title);
        label.setTextSize(14);
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = InjectUi.dp(context, topGap);
        params.bottomMargin = InjectUi.dp(context, 6);
        column.addView(label, params);
    }

    private static void addHint(Context context, LinearLayout column, String text, int topGap) {
        TextView hint = new TextView(context);
        hint.setText(text);
        hint.setTextSize(12);
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = InjectUi.dp(context, topGap);
        params.bottomMargin = InjectUi.dp(context, 8);
        column.addView(hint, params);
    }

    private static LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static String value(String value) { return value == null ? "" : value; }
}
