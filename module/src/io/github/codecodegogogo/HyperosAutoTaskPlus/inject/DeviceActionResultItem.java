package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.miui.autotask.taskitem.TaskItem;
import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/**
 * 设备操作共用参数模型。使用独立字段名，避免与 TaskItem 的 Gson 字段 a/b 冲突。
 * 不保存 Context、录制句柄等运行状态；Gson 和 Serializable 只负责配置。
 */
public class DeviceActionResultItem extends TaskItem {
    private static final long serialVersionUID = 1L;
    public static final int CAMERA_REAR = 1;
    public static final int CAMERA_FRONT = 2;

    private String actionKey;
    private boolean startRecording = true;
    private boolean frontCamera;
    /** 0 表示旧版单选配置，按 frontCamera 恢复；新版使用前后摄像头位集合。 */
    private int cameraSelection;
    private String phoneNumber = "";
    private String message = "";
    private boolean sendLocation;
    private boolean sendEnvironmentPhoto;
    private String audioUri = "";
    private String audioName = "";
    private String notificationText = "";
    private EmailConfig emailConfig;

    /** Gson 使用无参构造；actionKey 从持久化字段恢复。 */
    public DeviceActionResultItem() {}

    public DeviceActionResultItem(String key) {
        if (!FirstAppKeys.isDeviceActionKey(key)) throw new IllegalArgumentException("未知设备操作");
        actionKey = key;
    }

    @Override public String e() { return actionKey; }
    @Override public String h() { return DeviceActionRuntime.title(actionKey); }
    @Override public String g() { return DeviceActionRuntime.summary(this); }
    @Override public int b() { return DeviceActionRuntime.icon(actionKey, 1); }
    @Override public int c() { return DeviceActionRuntime.icon(actionKey, 0); }
    @Override public int i() { return DeviceActionRuntime.icon(actionKey, 2); }

    @Override public boolean l() {
        if (!FirstAppKeys.isDeviceActionKey(actionKey)) return false;
        if (isEmail()) return emailConfig != null && emailConfig.isValid();
        if (isAudioPlayback()) return validAudioUri(audioUri);
        if (FirstAppKeys.KEY_NOTIFICATION_RESULT.equals(actionKey)) return validNotificationText(notificationText);
        if (FirstAppKeys.KEY_PHOTO_RESULT.equals(actionKey)) return validCameraSelection(getCameraSelection());
        if (FirstAppKeys.KEY_CALL_RESULT.equals(actionKey)) return validNumber(phoneNumber);
        if (FirstAppKeys.KEY_SMS_RESULT.equals(actionKey)) {
            return validNumber(phoneNumber) && validMessage(message, sendLocation, sendEnvironmentPhoto);
        }
        return true;
    }

    @Override public void n() { DeviceActionRuntime.execute(this, false); }

    /** 一次性结果和「停止录制」不恢复；录制/音频只停止本任务拥有的会话。 */
    @Override public void o() {
        if (restoresOnExit()) DeviceActionRuntime.execute(this, true);
    }

    public boolean isRecording() {
        return FirstAppKeys.KEY_AUDIO_RECORD_RESULT.equals(actionKey)
                || FirstAppKeys.KEY_SCREEN_RECORD_RESULT.equals(actionKey);
    }

    public boolean restoresRecording() { return isRecording() && startRecording; }
    public boolean isAudioPlayback() { return FirstAppKeys.KEY_PLAY_AUDIO_RESULT.equals(actionKey); }
    public boolean isEmail() { return FirstAppKeys.KEY_EMAIL_RESULT.equals(actionKey); }
    public EmailConfig getEmailConfig() { return emailConfig == null ? new EmailConfig() : emailConfig.copy(); }
    public void setEmailConfig(EmailConfig value) {
        if (value == null || !value.isValid()) throw new IllegalArgumentException("邮件配置不完整");
        emailConfig = value.copy();
    }
    /** 仅供编辑事务恢复既有配置；包括尚未配置的 null 和旧版无效草稿。 */
    EmailConfig snapshotEmailConfig() { return emailConfig == null ? null : emailConfig.copy(); }
    void restoreEmailConfig(EmailConfig snapshot) { emailConfig = snapshot == null ? null : snapshot.copy(); }
    public boolean restoresOnExit() { return restoresRecording() || isAudioPlayback(); }
    public boolean isStartRecording() { return startRecording; }
    public void setStartRecording(boolean value) { startRecording = value; }
    public boolean isFrontCamera() { return (getCameraSelection() & CAMERA_FRONT) != 0; }
    public boolean isRearCamera() { return (getCameraSelection() & CAMERA_REAR) != 0; }
    public void setFrontCamera(boolean value) { setCameras(!value, value); }

    public int getCameraSelection() {
        return cameraSelection == 0 ? (frontCamera ? CAMERA_FRONT : CAMERA_REAR) : cameraSelection;
    }

    public void setCameras(boolean rear, boolean front) {
        int selection = (rear ? CAMERA_REAR : 0) | (front ? CAMERA_FRONT : 0);
        if (!validCameraSelection(selection)) throw new IllegalArgumentException("至少选择一个摄像头");
        cameraSelection = selection;
        frontCamera = front;
    }

    public static boolean validCameraSelection(int selection) {
        return selection > 0 && (selection & ~(CAMERA_REAR | CAMERA_FRONT)) == 0;
    }
    public String getPhoneNumber() { return phoneNumber == null ? "" : phoneNumber; }
    public String getMessage() { return message == null ? "" : message; }
    public boolean isSendLocation() { return sendLocation; }
    public boolean isSendEnvironmentPhoto() { return sendEnvironmentPhoto; }
    public void setMessageAttachments(boolean location, boolean photo) {
        sendLocation = location;
        sendEnvironmentPhoto = photo;
    }

    public static boolean validMessage(String text, boolean location, boolean photo) {
        return location || photo || (text != null && !text.trim().isEmpty());
    }
    public String getAudioUri() { return audioUri == null ? "" : audioUri; }
    public String getAudioName() { return audioName == null ? "" : audioName; }
    public String getNotificationText() { return notificationText == null ? "" : notificationText; }

    public void setNotificationText(String text) {
        if (!validNotificationText(text)) throw new IllegalArgumentException("请输入通知内容");
        notificationText = text;
    }

    public static boolean validNotificationText(String text) {
        return text != null && !text.trim().isEmpty();
    }

    public void setAudioFile(String uri, String name) {
        if (!validAudioUri(uri)) throw new IllegalArgumentException("请选择本地音频文件");
        audioUri = uri;
        audioName = name == null ? "" : name;
    }

    /** 只接受文件选择器的内容 URI，不将网络地址或任意文件路径作为播放来源。 */
    public static boolean validAudioUri(String uri) {
        if (uri == null || uri.isEmpty()) return false;
        try {
            java.net.URI parsed = new java.net.URI(uri);
            return "content".equals(parsed.getScheme()) && parsed.getRawAuthority() != null
                    && !parsed.getRawAuthority().isEmpty() && parsed.getRawPath() != null
                    && !parsed.getRawPath().isEmpty();
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    public void setContact(String number, String text) {
        phoneNumber = normalizeNumber(number);
        message = text == null ? "" : text;
    }

    public static String normalizeNumber(String number) {
        return number == null ? "" : number.replaceAll("[\\s()\\-]", "");
    }

    /** 支持国际区号、固定电话和服务号码；不把 USSD、多个收件人当成电话号码。 */
    public static boolean validNumber(String number) {
        return normalizeNumber(number).matches("\\+?[0-9]{1,20}");
    }
}
