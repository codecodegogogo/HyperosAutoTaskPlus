package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.ObjectStreamField;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 验证配置、凭据绑定和任务生命周期，不调用 Android API 或发送真实消息。 */
public final class EmailConfigTest {
    private static final Gson GSON = new Gson();
    private static final String CREDENTIAL_ID = "12345678-abcd-4321-9876-1234567890ab";
    private static final String SUBJECT = "  自动任务通知：上海 📍  ";
    private static final String BODY = "  请查看设备位置与照片。\r\n第二行：中文、é 和 📷\n保留结尾空格  ";
    private static int checks;

    public static void main(String[] args) throws Exception {
        validation();
        credentials();
        credentialBinding();
        persistence();
        editingIsolation();
        lifecycle();
        System.out.println("邮件配置回归测试通过：" + checks + " 项断言");
    }

    private static void validation() {
        check(validConfig().isValid(), "完整配置允许 UTF-8 主题及含换行的正文");
        for (String host : new String[]{"smtp.example.com", "SMTP.Example.COM", "smtp-01.example.com", "192.0.2.1"}) {
            check(EmailConfig.validHost(host), "接受有效 SMTP 主机");
            EmailConfig config = validConfig();
            config.smtpHost = host;
            check(config.isValid(), "有效 SMTP 主机通过完整配置校验");
        }
        for (String host : new String[]{null, "", " smtp.example.com", "smtp.example.com ",
                "https://smtp.example.com", "smtp://smtp.example.com", "smtp.example.com:465",
                "smtp.example.com/path", "user@smtp.example.com", "smtp.example.com?x=1",
                ".example.com", "smtp..example.com", "smtp.example.com.", "-smtp.example.com",
                "smtp-.example.com", "smtp_server.example.com", "a".repeat(64) + ".example.com",
                ("a".repeat(63) + ".").repeat(3) + "a".repeat(63),
                "smtp.example.com\r\nRCPT TO:<other@example.com>", "smtp.example.com\n", "smtp.example.com\0"}) {
            check(!EmailConfig.validHost(host), "拒绝网址、端口、非法域名和 SMTP 命令注入");
            reject(config -> config.smtpHost = host, "非法 SMTP 主机不能保存");
        }
        for (int port : new int[]{1, 25, 465, 587, 65535}) {
            EmailConfig config = validConfig();
            config.smtpPort = port;
            check(config.isValid(), "允许有效范围内的 SMTP 端口");
        }
        for (int port : new int[]{Integer.MIN_VALUE, -1, 0, 65536, Integer.MAX_VALUE}) {
            reject(config -> config.smtpPort = port, "拒绝范围外 SMTP 端口");
        }
        for (String address : new String[]{"user@example.com", "user+task@example.com", "first.last@example-domain.cn"}) {
            check(EmailConfig.validAddress(address), "接受单个邮箱地址");
            EmailConfig config = validConfig();
            config.from = address;
            config.to = address;
            check(config.isValid(), "发件人和收件人均接受单个有效邮箱");
        }
        for (String address : new String[]{null, "", "user", "@example.com", "user@", "user@@example.com",
                " user@example.com", "user@example.com ", "Name <user@example.com>",
                "a@example.com,b@example.com", "a@example.com;b@example.com", "a@example.com b@example.com",
                "user name@example.com", ".user@example.com", "user.@example.com", "first..last@example.com",
                "a".repeat(65) + "@example.com", "user@example.com:465", "user@https://example.com",
                "user@example.com\r\nBcc: other@example.com", "user\n@example.com", "user\0@example.com"}) {
            check(!EmailConfig.validAddress(address), "拒绝多收件人、显示名称和邮箱头注入");
            reject(config -> config.from = address, "非法发件地址不能保存");
            reject(config -> config.to = address, "非法收件地址不能保存");
        }
        for (String control : new String[]{"\r", "\n", "\r\n", "\t", "\0", "\u007f"}) {
            reject(config -> config.subject = "主题" + control + "Bcc: other@example.com", "主题不能注入邮件头");
            reject(config -> config.username = "user" + control + "AUTH LOGIN", "SMTP 用户名不能注入协议命令");
        }
        for (String subject : new String[]{null, "", "   ", "文".repeat(201)}) {
            reject(config -> config.subject = subject, "空白或超长主题不能保存");
        }
        reject(config -> config.username = "u".repeat(321), "拒绝超长 SMTP 用户名");
        reject(config -> config.body = "a\0b", "拒绝正文中的 NUL");
        reject(config -> config.body = "文".repeat(100_001), "拒绝超长正文");
        EmailConfig boundary = validConfig();
        boundary.subject = "文".repeat(200);
        boundary.body = "文".repeat(100_000);
        boundary.username = "u".repeat(320);
        check(boundary.isValid(), "允许长度边界内的主题、正文和用户名");
        boundary.username = "";
        boundary.body = "";
        check(boundary.isValid(), "默认邮箱用户名和空补充正文可以保存");
    }

    private static void credentials() {
        check(EmailConfig.validCredentialId(CREDENTIAL_ID), "凭据引用接受 UUID");
        check(!EmailConfig.validCredentialId(CREDENTIAL_ID.toUpperCase(Locale.ROOT)), "凭据引用大小写与加密存储键严格一致");
        for (String id : new String[]{null, "", "password-instead-of-id", "../credentials", CREDENTIAL_ID + "\n",
                "12345678-abcd-4321-9876-1234567890ag", " " + CREDENTIAL_ID}) {
            check(!EmailConfig.validCredentialId(id), "非法凭据引用不能作为已保存凭据");
            EmailConfig config = validConfig();
            config.credentialId = id;
            check(!config.isValid() && config.validationError(true) != null, "可执行邮件必须关联有效凭据引用");
            check(config.validationError(false) == null, "录入新授权码前可以单独校验其余草稿字段");
        }
        for (String secret : new String[]{"app-password", "abcd efgh ijkl mnop", "a".repeat(4096)}) {
            check(EmailConfig.validSecret(secret), "允许非空授权码和长度边界");
        }
        for (String secret : new String[]{null, "", "   ", "a".repeat(4097), "auth\rnext", "auth\nnext",
                "auth\r\nAUTH LOGIN", "auth\tnext", "auth\0next", "auth\u007fnext"}) {
            check(!EmailConfig.validSecret(secret), "拒绝空白、超长或含控制字符的授权码");
        }
    }

    private static void persistence() throws Exception {
        for (int selection = 0; selection < 4; selection++) {
            for (boolean startTls : new boolean[]{false, true}) {
                EmailConfig config = validConfig();
                config.sendLocation = (selection & 1) != 0;
                config.sendPhoto = (selection & 2) != 0;
                config.startTls = startTls;
                check(config.isValid(), "两个附件开关的所有组合与两种 TLS 模式均可保存");
                DeviceActionResultItem item = new DeviceActionResultItem(FirstAppKeys.KEY_EMAIL_RESULT);
                item.setEmailConfig(config);
                DeviceActionResultItem json = GSON.fromJson(GSON.toJson(item), DeviceActionResultItem.class);
                assertRestored(json, config, "Gson");
                assertRestored(roundTrip(item), config, "Serializable");
                sameConfig(roundTrip(config), config, "独立邮件配置也支持 Serializable");
            }
        }

        // 模拟导入旧版或外部 JSON 中的明文密码字段，后续保存不得继续携带这些字段。
        String secretMarker = "MUST_NOT_PERSIST_TEST_SMTP_SECRET_938172";
        JsonObject importedJson = GSON.toJsonTree(validConfig()).getAsJsonObject();
        for (String name : new String[]{"password", "smtpPassword", "authorizationCode", "secret"}) {
            importedJson.addProperty(name, secretMarker);
        }
        EmailConfig imported = GSON.fromJson(importedJson, EmailConfig.class);
        DeviceActionResultItem item = new DeviceActionResultItem(FirstAppKeys.KEY_EMAIL_RESULT);
        item.setEmailConfig(imported);
        String json = GSON.toJson(item);
        check(json.contains(CREDENTIAL_ID) && !json.contains(secretMarker), "Gson 仅保存授权码引用，不保留导入的明文授权码");
        String serialized = new String(serialize(item), StandardCharsets.ISO_8859_1);
        check(serialized.contains(CREDENTIAL_ID) && !serialized.contains(secretMarker), "Serializable 仅携带授权码引用");
        for (Class<?> type : new Class<?>[]{EmailConfig.class, DeviceActionResultItem.class}) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    check(!isSecretField(field.getName()), "任务模型不得声明明文密码或授权码字段");
                }
            }
            for (ObjectStreamField field : ObjectStreamClass.lookup(type).getFields()) {
                check(!isSecretField(field.getName()), "Java 序列化字段不得含明文密码或授权码");
            }
        }
    }

    private static void credentialBinding() throws Exception {
        EmailConfig original = validConfig();
        byte[] aad = EmailCredentialStore.associatedData(original, CREDENTIAL_ID);
        EmailConfig sameAccount = original.copy();
        sameAccount.smtpHost = original.smtpHost.toUpperCase(Locale.ROOT);
        sameAccount.from = "sender+task@EXAMPLE.COM";
        sameAccount.to = "another@example.net";
        sameAccount.subject = "更改主题";
        sameAccount.body = "更改正文";
        sameAccount.sendPhoto = false;
        sameAccount.smtpPort = 465;
        sameAccount.startTls = false;
        check(Arrays.equals(aad, EmailCredentialStore.associatedData(sameAccount, CREDENTIAL_ID)),
                "域名大小写及非账号配置变化可以继续使用原授权码");
        EmailConfig implicit = original.copy();
        implicit.username = "";
        EmailConfig explicit = implicit.copy();
        explicit.username = explicit.from;
        check(Arrays.equals(EmailCredentialStore.associatedData(implicit, CREDENTIAL_ID),
                        EmailCredentialStore.associatedData(explicit, CREDENTIAL_ID)),
                "默认发件账号与显式填写同一 SMTP 账号使用一致的凭据绑定");

        SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");
        GCMParameterSpec parameters = new GCMParameterSpec(128, new byte[12]);
        Cipher encryption = Cipher.getInstance("AES/GCM/NoPadding");
        encryption.init(Cipher.ENCRYPT_MODE, key, parameters);
        encryption.updateAAD(aad);
        byte[] encrypted = encryption.doFinal("local-test-only".getBytes(StandardCharsets.UTF_8));
        Cipher correct = Cipher.getInstance("AES/GCM/NoPadding");
        correct.init(Cipher.DECRYPT_MODE, key, parameters);
        correct.updateAAD(EmailCredentialStore.associatedData(sameAccount, CREDENTIAL_ID));
        check("local-test-only".equals(new String(correct.doFinal(encrypted), StandardCharsets.UTF_8)),
                "原 SMTP 账号可以解密自身凭据");
        for (int changed = 0; changed < 4; changed++) {
            EmailConfig altered = original.copy();
            String id = CREDENTIAL_ID;
            if (changed == 0) altered.smtpHost = "another.example.com";
            if (changed == 1) altered.from = "another@example.com";
            if (changed == 2) altered.username = "another-user@example.com";
            if (changed == 3) id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
            Cipher decryption = Cipher.getInstance("AES/GCM/NoPadding");
            decryption.init(Cipher.DECRYPT_MODE, key, parameters);
            decryption.updateAAD(EmailCredentialStore.associatedData(altered, id));
            boolean rejected = false;
            try { decryption.doFinal(encrypted); }
            catch (AEADBadTagException expected) { rejected = true; }
            check(rejected, "修改主机、发件人、账号或凭据编号后无法解密原授权码");
        }
    }

    private static void editingIsolation() {
        EmailConfig original = validConfig();
        EmailConfig draft = original.copy();
        check(draft != original, "复制配置返回独立对象");
        sameConfig(draft, original, "复制配置保留所有设置");
        mutate(draft);
        sameConfig(original, validConfig(), "丢弃编辑副本不会更改原始配置");

        DeviceActionResultItem item = new DeviceActionResultItem(FirstAppKeys.KEY_EMAIL_RESULT);
        item.setEmailConfig(original);
        mutate(original);
        sameConfig(item.getEmailConfig(), validConfig(), "保存后修改传入对象不影响已保存任务");
        EmailConfig cancelledDraft = item.getEmailConfig();
        check(cancelledDraft != item.getEmailConfig(), "每次读取均返回独立配置对象");
        mutate(cancelledDraft);
        sameConfig(item.getEmailConfig(), validConfig(), "取消编辑不会更改已保存任务");
        for (EmailConfig invalid : new EmailConfig[]{null, new EmailConfig(), cancelledDraft}) {
            boolean rejected = false;
            try { item.setEmailConfig(invalid); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "无效邮件配置必须被拒绝");
            sameConfig(item.getEmailConfig(), validConfig(), "校验失败不能覆盖原邮件配置");
        }
        EmailConfig edited = validConfig();
        edited.subject = "修改后的主题";
        edited.sendPhoto = false;
        item.setEmailConfig(edited);
        sameConfig(item.getEmailConfig(), edited, "确认有效编辑后完整保存新设置");

        DeviceActionResultItem fresh = new DeviceActionResultItem(FirstAppKeys.KEY_EMAIL_RESULT);
        EmailConfig before = fresh.snapshotEmailConfig();
        check(before == null, "首次编辑前保留未配置状态");
        fresh.setEmailConfig(validConfig());
        fresh.restoreEmailConfig(before);
        check(!fresh.l() && fresh.snapshotEmailConfig() == null, "首次确认失败能恢复原始未配置状态");
        EmailConfig snapshot = item.snapshotEmailConfig();
        item.setEmailConfig(validConfig());
        item.restoreEmailConfig(snapshot);
        mutate(snapshot);
        sameConfig(item.getEmailConfig(), edited, "编辑回滚恢复独立快照，不受回调后修改影响");
    }

    private static void lifecycle() {
        check(FirstAppKeys.isDeviceActionKey(FirstAppKeys.KEY_EMAIL_RESULT), "邮件 key 加入设备操作注册");
        check(DeviceActionRuntime.itemClass(FirstAppKeys.KEY_EMAIL_RESULT) == DeviceActionResultItem.class,
                "邮件 key 使用设备操作配置反序列化");
        DeviceActionResultItem fresh = (DeviceActionResultItem) DeviceActionRuntime.newItem(FirstAppKeys.KEY_EMAIL_RESULT);
        check(fresh != null && fresh.isEmail() && FirstAppKeys.KEY_EMAIL_RESULT.equals(fresh.e()), "工厂创建独立邮件结果");
        check(!fresh.l(), "新邮件任务未配置前不能执行");
        EmailConfig unsaved = fresh.getEmailConfig();
        check(unsaved.sendLocation && unsaved.sendPhoto, "新邮件默认勾选当前位置和环境照片");
        unsaved.smtpHost = "smtp.example.com";
        check(!fresh.l(), "读取并修改草稿不能让未保存任务变为可执行");

        String prefix = "{\"actionKey\":\"" + FirstAppKeys.KEY_EMAIL_RESULT + "\"";
        for (String json : new String[]{"{}", prefix + "}", prefix + ",\"emailConfig\":null}",
                prefix + ",\"emailConfig\":{}}"}) {
            DeviceActionResultItem old = GSON.fromJson(json, DeviceActionResultItem.class);
            check(!old.l(), "旧空配置、缺失或损坏邮件配置均不能执行");
            old.o();
        }
        DeviceActionResultItem oldSms = GSON.fromJson("{\"actionKey\":\"" + FirstAppKeys.KEY_SMS_RESULT
                + "\",\"phoneNumber\":\"10086\",\"message\":\"旧短信\"}", DeviceActionResultItem.class);
        check(oldSms.l() && !oldSms.isEmail(), "既有短信任务无需邮件配置且不被当成邮件");
        fresh.setEmailConfig(validConfig());
        check(fresh.l() && !fresh.restoresRecording() && !fresh.restoresOnExit(), "邮件为单次触发操作，不注册退出恢复");
        fresh.o();
        DeviceActionRuntime.execute(fresh, true);
        check(fresh.l(), "有效邮件退出和恢复入口均为空操作，不触及 Android 或再次发送");
    }

    private static EmailConfig validConfig() {
        EmailConfig config = new EmailConfig();
        config.smtpHost = "smtp.example.com";
        config.smtpPort = 587;
        config.startTls = true;
        config.from = "sender+task@example.com";
        config.to = "recipient@example.net";
        config.username = "smtp-user@example.com";
        config.credentialId = CREDENTIAL_ID;
        config.subject = SUBJECT;
        config.body = BODY;
        config.sendLocation = false;
        config.sendPhoto = true;
        return config;
    }

    private static void mutate(EmailConfig config) {
        config.smtpHost = "changed.example.com";
        config.smtpPort = 0;
        config.startTls = !config.startTls;
        config.from = "changed-from@example.com";
        config.to = "changed-to@example.com";
        config.username = "changed-user";
        config.credentialId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        config.subject = "草稿主题";
        config.body = "草稿正文";
        config.sendLocation = !config.sendLocation;
        config.sendPhoto = !config.sendPhoto;
    }

    private static void reject(Consumer<EmailConfig> edit, String message) {
        EmailConfig config = validConfig();
        edit.accept(config);
        check(!config.isValid() && config.validationError(true) != null && config.validationError(false) != null, message);
    }

    private static void assertRestored(DeviceActionResultItem item, EmailConfig expected, String format) {
        check(item.isEmail() && FirstAppKeys.KEY_EMAIL_RESULT.equals(item.e()) && item.l(), format + " 恢复有效邮件任务");
        sameConfig(item.getEmailConfig(), expected, format + " 保留服务器、凭据引用、中文正文、TLS 和独立附件开关");
        check(SUBJECT.equals(item.getEmailConfig().subject) && BODY.equals(item.getEmailConfig().body),
                format + " 精确保留 UTF-8 主题、正文换行、表情及空格");
    }

    private static void sameConfig(EmailConfig actual, EmailConfig expected, String message) {
        check(GSON.toJsonTree(actual).equals(GSON.toJsonTree(expected)), message);
    }

    private static boolean isSecretField(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("secret") || lower.contains("authorizationcode")
                || lower.contains("authcode");
    }

    private static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(value); }
        return bytes.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialize(value)))) {
            return (T) input.readObject();
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
