package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 授权码只写入宿主的私有偏好设置；任务只保存随机凭据 ID。 */
public final class EmailCredentialStore {
    private static final String PREFERENCES = "hyper_auto_email_credentials_v1";
    private static final String KEY_ALIAS = "hyper_auto_email_aes_gcm_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private EmailCredentialStore() {}

    /** 使用新 ID，失败不会覆盖其它任务可能仍在使用的凭据。 */
    public static String save(Context context, EmailConfig config, String secret) throws Exception {
        if (!EmailConfig.validSecret(secret)) {
            throw new IllegalArgumentException("请输入有效的 SMTP 授权码或应用密码");
        }
        if (config == null || config.validationError(false) != null) {
            throw new IllegalArgumentException("邮件配置不完整，无法保存授权码");
        }
        EmailConfig snapshot = config.copy();
        String id = UUID.randomUUID().toString();
        byte[] plain = secret.getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            cipher.updateAAD(associatedData(snapshot, id));
            byte[] encrypted = cipher.doFinal(plain);
            String record = "2:" + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                    + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            SharedPreferences preferences = preferences(context);
            if (!preferences.edit().putString(id, record).commit()) {
                // commit 失败后内存中也可能已有值；尽力撤销，不返回无持久保证的 ID。
                preferences.edit().remove(id).commit();
                throw new IOException("无法持久保存邮件授权码，请检查设备存储后重试");
            }
            return id;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // 不传播平台异常的参数或正文，调用方即使记录错误也不会泄露凭据。
            throw new GeneralSecurityException("无法安全保存邮件授权码，请稍后重试");
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    public static String read(Context context, EmailConfig config) throws Exception {
        byte[] plain = null;
        try {
            plain = decrypt(context, config);
            return new String(plain, StandardCharsets.UTF_8);
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    /** 仅在后台调用：严格验证绑定和密文，缺失、篡改、失效均不可用，不返回明文。 */
    public static boolean exists(Context context, EmailConfig config) {
        byte[] plain = null;
        try {
            plain = decrypt(context, config);
            return plain.length > 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    public static void remove(Context context, String id) throws Exception {
        if (!validId(id)) return;
        try {
            if (!preferences(context).edit().remove(id).commit()) {
                throw new IOException("无法删除未使用的邮件授权码");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("无法删除未使用的邮件授权码");
        }
    }

    private static byte[] decrypt(Context context, EmailConfig config) throws Exception {
        if (config == null || !validId(config.credentialId)) {
            throw new IOException("邮件授权码不存在，请重新输入");
        }
        EmailConfig snapshot = config.copy();
        String id = snapshot.credentialId;
        try {
            String record = preferences(context).getString(id, null);
            if (record == null) throw new IOException("邮件授权码不存在，请重新输入");
            if (record.length() > 32768) throw new GeneralSecurityException();
            String[] parts = record.split(":", -1);
            // 旧 v1 记录没有账号绑定，不降级解密，必须重新输入授权码。
            if (parts.length != 3 || !"2".equals(parts[0])) {
                throw new GeneralSecurityException();
            }
            byte[] iv = Base64.decode(parts[1], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[2], Base64.NO_WRAP);
            if (iv.length != 12 || encrypted.length < 17) throw new GeneralSecurityException();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
            cipher.updateAAD(associatedData(snapshot, id));
            return cipher.doFinal(encrypted);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException("邮件授权码已失效或无法解密，请重新输入");
        }
    }

    private static SharedPreferences preferences(Context context) {
        if (context == null) throw new IllegalArgumentException("无法获取安全服务的私有存储");
        Context application = context.getApplicationContext();
        return (application == null ? context : application).getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    /** 与 SMTP 实际认证值保持一致：空白账号回退发件地址，其余账号保留原始大小写和空格。 */
    static String effectiveUsername(EmailConfig config) {
        return config.username == null || config.username.trim().isEmpty() ? config.from : config.username;
    }

    /**
     * 纯 Java 的凭据绑定规范，供编辑比较与回归测试复用。每项按 UTF-8 字节长度前缀编码，
     * 主机和发件地址域名不区分大小写；本地部分、实际登录账号保留原样。
     * 不绑定收件人和正文，允许任务修改发送内容；修改发件身份或服务器必须重新授权。
     */
    static byte[] associatedData(EmailConfig config, String id) {
        if (config == null || !validId(id) || !EmailConfig.validHost(config.smtpHost)
                || !EmailConfig.validAddress(config.from)) {
            throw new IllegalArgumentException("邮件凭据绑定参数无效");
        }
        String username = effectiveUsername(config);
        if (username == null || username.length() > 320) throw new IllegalArgumentException("邮件凭据绑定参数无效");
        for (int i = 0; i < username.length(); i++) {
            if (username.charAt(i) < 32 || username.charAt(i) == 127) {
                throw new IllegalArgumentException("邮件凭据绑定参数无效");
            }
        }
        int separator = config.from.indexOf('@');
        String sender = config.from.substring(0, separator + 1)
                + config.from.substring(separator + 1).toLowerCase(Locale.ROOT);
        String[] values = {"hyper_auto_email_credentials_v2", id,
                config.smtpHost.toLowerCase(Locale.ROOT), sender, username};
        byte[][] encoded = new byte[values.length][];
        int size = values.length * 4;
        for (int i = 0; i < values.length; i++) {
            encoded[i] = values[i].getBytes(StandardCharsets.UTF_8);
            size += encoded[i].length;
        }
        ByteBuffer result = ByteBuffer.allocate(size);
        for (byte[] value : encoded) result.putInt(value.length).put(value);
        return result.array();
    }

    private static synchronized SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        if (!create || store.containsAlias(KEY_ALIAS)) throw new GeneralSecurityException("邮件加密密钥不可用");

        // 仅此 API 使用反射，兼容项目旧 android.jar；运行环境为 Android 10 及以上。
        Class<?> builderClass = Class.forName("android.security.keystore.KeyGenParameterSpec$Builder");
        Object builder = builderClass.getConstructor(String.class, int.class).newInstance(KEY_ALIAS, 1 | 2);
        builderClass.getMethod("setBlockModes", String[].class).invoke(builder, (Object) new String[]{"GCM"});
        builderClass.getMethod("setEncryptionPaddings", String[].class)
                .invoke(builder, (Object) new String[]{"NoPadding"});
        builderClass.getMethod("setKeySize", int.class).invoke(builder, 256);
        builderClass.getMethod("setRandomizedEncryptionRequired", boolean.class).invoke(builder, true);
        // 锁屏时仍需执行自动任务；安全性由宿主私有存储和不可导出的 KeyStore 密钥提供。
        builderClass.getMethod("setUserAuthenticationRequired", boolean.class).invoke(builder, false);
        AlgorithmParameterSpec spec = (AlgorithmParameterSpec) builderClass.getMethod("build").invoke(builder);
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(spec);
        return generator.generateKey();
    }
}
