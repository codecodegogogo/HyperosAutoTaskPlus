package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import java.io.Serializable;

/** 邮件任务配置。授权码仅通过 credentialId 引用，不写入任务数据库或导出内容。 */
public final class EmailConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    public String smtpHost = "";
    public int smtpPort = 465;
    public boolean startTls;
    public String from = "";
    public String to = "";
    public String username = "";
    public String credentialId = "";
    public String subject = "HyperosAutoTaskPlus 自动任务通知";
    public String body = "";
    public boolean sendLocation = true;
    public boolean sendPhoto = true;

    public EmailConfig copy() {
        EmailConfig result = new EmailConfig();
        result.smtpHost = safe(smtpHost);
        result.smtpPort = smtpPort;
        result.startTls = startTls;
        result.from = safe(from);
        result.to = safe(to);
        result.username = safe(username);
        result.credentialId = safe(credentialId);
        result.subject = safe(subject);
        result.body = safe(body);
        result.sendLocation = sendLocation;
        result.sendPhoto = sendPhoto;
        return result;
    }

    public boolean isValid() { return validationError(true) == null; }

    public String validationError(boolean requireCredential) {
        if (!validHost(smtpHost)) return "请输入有效的 SMTP 服务器地址，不要填写网址或端口";
        if (smtpPort < 1 || smtpPort > 65535) return "SMTP 端口应为 1～65535";
        if (!validAddress(from)) return "请输入有效的单个发件邮箱地址";
        if (!validAddress(to)) return "请输入有效的单个收件邮箱地址";
        if (safe(username).length() > 320 || hasControl(safe(username))) return "SMTP 用户名格式无效";
        if (safe(subject).trim().isEmpty() || safe(subject).length() > 200 || hasControl(safe(subject))) {
            return "邮件主题不能为空、不能换行，且不能超过 200 个字符";
        }
        if (safe(body).length() > 100_000 || safe(body).indexOf('\0') >= 0) return "邮件正文过长或包含无效字符";
        if (requireCredential && !validCredentialId(credentialId)) return "请配置邮箱 SMTP 授权码或应用密码";
        return null;
    }

    public static boolean validHost(String host) {
        if (host == null || host.isEmpty() || host.length() > 253 || !host.equals(host.trim())) return false;
        for (String label : host.split("\\.", -1)) {
            if (label.length() > 63 || !label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) return false;
        }
        return true;
    }

    public static boolean validAddress(String address) {
        if (address == null || address.length() > 254) return false;
        int at = address.indexOf('@');
        if (at < 1 || at > 64 || at != address.lastIndexOf('@')) return false;
        String local = address.substring(0, at);
        return !local.startsWith(".") && !local.endsWith(".") && !local.contains("..")
                && local.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+") && validHost(address.substring(at + 1));
    }

    public static boolean validCredentialId(String id) {
        return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    public static boolean validSecret(String secret) {
        return secret != null && !secret.trim().isEmpty() && secret.length() <= 4096 && !hasControl(secret);
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) == 127) return true;
        return false;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
