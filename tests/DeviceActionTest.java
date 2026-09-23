package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import com.google.gson.Gson;
import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/** JVM 回归：配置持久化与录制归属，不调用相机、麦克风、拨号或短信接口。 */
public final class DeviceActionTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        String[] keys = {
                FirstAppKeys.KEY_AUDIO_RECORD_RESULT, FirstAppKeys.KEY_SCREEN_RECORD_RESULT,
                FirstAppKeys.KEY_SCREENSHOT_RESULT, FirstAppKeys.KEY_PHOTO_RESULT,
                FirstAppKeys.KEY_CALL_RESULT, FirstAppKeys.KEY_SMS_RESULT,
                FirstAppKeys.KEY_PLAY_AUDIO_RESULT, FirstAppKeys.KEY_NOTIFICATION_RESULT};
        Gson gson = new Gson();
        for (String key : keys) {
            DeviceActionResultItem item = (DeviceActionResultItem) DeviceActionRuntime.newItem(key);
            check(item != null && key.equals(item.e()), "工厂必须保留每个结果的独立 key");
            item.setContact("+86 (138) 0013-8000", "第一行\n第二行 🌟");
            item.setNotificationText("  自定义通知\n第二行 🔔  ");
            item.setStartRecording(false);
            item.setFrontCamera(true);
            if (item.isAudioPlayback()) item.setAudioFile("content://local.audio/document/beep", "提示音.mp3");
            item.code = 2;
            Class<?> type = DeviceActionRuntime.itemClass(key);
            DeviceActionResultItem restored = (DeviceActionResultItem) gson.fromJson(gson.toJson(item), type);
            assertConfig(restored, key);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(item); }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                assertConfig((DeviceActionResultItem) in.readObject(), key);
            }
        }
        check(DeviceActionRuntime.newItem("unknown") == null, "未知结果不劫持原生工厂");
        check(DeviceActionRuntime.itemClass(null) == null, "null key 不劫持原生反序列化");
        check(!gson.fromJson("{}", DeviceActionResultItem.class).l(), "损坏配置不得执行");
        check(!gson.fromJson("{\"actionKey\":\"unknown\"}", DeviceActionResultItem.class).l(), "未知操作不得执行");

        String[] valid = {"10086", "010-12345678", "+86 (138) 0013-8000", "0012025550100"};
        for (String number : valid) check(DeviceActionResultItem.validNumber(number), "有效号码 " + number);
        String[] invalid = {null, "", "   ", "+", "++86138", "138;139", "*#06#", "123a", "123456789012345678901"};
        for (String number : invalid) check(!DeviceActionResultItem.validNumber(number), "拒绝无效号码");
        DeviceActionResultItem call = new DeviceActionResultItem(FirstAppKeys.KEY_CALL_RESULT);
        check(!call.l(), "拨号必须填写号码");
        call.setContact("10086", "");
        check(call.l(), "拨号无需短信正文");
        DeviceActionResultItem sms = new DeviceActionResultItem(FirstAppKeys.KEY_SMS_RESULT);
        sms.setContact("10086", " \n ");
        check(!sms.l(), "拒绝空白短信");
        sms.setContact("10086", "  保留空格\n换行  ");
        check(sms.l() && "  保留空格\n换行  ".equals(sms.getMessage()), "保留用户输入的正文格式");

        for (String key : keys) {
            DeviceActionResultItem item = new DeviceActionResultItem(key);
            boolean recording = key.equals(FirstAppKeys.KEY_AUDIO_RECORD_RESULT) || key.equals(FirstAppKeys.KEY_SCREEN_RECORD_RESULT);
            check(item.restoresRecording() == recording, "只有开始录制支持恢复");
            item.setStartRecording(false);
            check(!item.restoresRecording(), "停止操作不反向开启录制");
            item.o(); // 一次性/停止结果退出时必须是空操作，不能碰 Android 运行时。
        }
        cameraSelections(gson);
        audioFiles(gson);
        notifications(gson);
        ownership();
        System.out.println("设备操作回归测试通过：" + checks + " 项断言");
    }


    private static void notifications(Gson gson) {
        DeviceActionResultItem item = new DeviceActionResultItem(FirstAppKeys.KEY_NOTIFICATION_RESULT);
        check(!item.l(), "未填写内容不能保存通知结果");
        item.setContact("10086", "短信内容");
        check(!item.l(), "通知内容不能误用短信正文");
        String content = "  第一行 🔔\n第二行  ";
        item.setNotificationText(content);
        check(item.l() && content.equals(item.getNotificationText()), "通知保留空格、换行和表情");
        check(!item.restoresOnExit(), "通知只在触发时发出，退出不重复发送或撤回");
        item.o(); // 有效通知的恢复也不能触及 Android 通知服务。
        for (String invalid : new String[]{null, "", " \n\t\r "}) {
            boolean rejected = false;
            try { item.setNotificationText(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected && content.equals(item.getNotificationText()), "无效编辑不覆盖已保存的通知");
        }
        String prefix = "{\"actionKey\":\"" + FirstAppKeys.KEY_NOTIFICATION_RESULT + "\"";
        check(!gson.fromJson(prefix + "}", DeviceActionResultItem.class).l(), "缺失通知内容的配置不执行");
        check(!gson.fromJson(prefix + ",\"notificationText\":null}", DeviceActionResultItem.class).l(), "null 通知配置不执行");
        check(!gson.fromJson(prefix + ",\"notificationText\":\"   \"}", DeviceActionResultItem.class).l(), "空白通知配置不执行");
        item.setNotificationText("🔔".repeat(41));
        String summary = DeviceActionRuntime.summary(item);
        check(summary.endsWith("🔔".repeat(40) + "…"), "通知摘要截断时不拆开表情");
        check(item.getNotificationText().codePointCount(0, item.getNotificationText().length()) == 41, "摘要不截断已保存的正文");
    }

    private static void audioFiles(Gson gson) throws Exception {
        DeviceActionResultItem item = new DeviceActionResultItem(FirstAppKeys.KEY_PLAY_AUDIO_RESULT);
        check(!item.l(), "没有选择音频文件不能保存结果");
        check(item.restoresOnExit() && !item.restoresRecording(), "播放音频有独立的退出停止行为");
        String uri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2Falarm.mp3";
        item.setAudioFile(uri, "提示音.mp3");
        check(item.l() && uri.equals(item.getAudioUri()), "接受文件管理器返回的文档 URI");
        DeviceActionResultItem restored = gson.fromJson(gson.toJson(item), DeviceActionResultItem.class);
        check(uri.equals(restored.getAudioUri()) && "提示音.mp3".equals(restored.getAudioName()), "音频文件引用与名称随任务保存");
        String[] invalidUris = {null, "", "https://example.com/audio.mp3", "file:///sdcard/Music/a.mp3", "/sdcard/a.mp3", "content:/missing-provider", "content://provider", "content://provider/a b"};
        for (String invalid : invalidUris) {
            check(!DeviceActionResultItem.validAudioUri(invalid), "不接受网络地址或不完整文件引用");
            boolean rejected = false;
            try { item.setAudioFile(invalid, "bad"); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected && uri.equals(item.getAudioUri()), "无效选择不覆盖原音频文件");
        }
        for (String extension : new String[]{"mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr"}) {
            check(AudioPickerFragment.accepts("application/octet-stream", "AUDIO." + extension.toUpperCase(java.util.Locale.ROOT)), "常见格式扩展名兼容");
        }
        check(AudioPickerFragment.accepts("audio/mpeg", "renamed"), "按文件提供器的音频 MIME 接受无扩展名文件");
        check(!AudioPickerFragment.accepts("image/jpeg", "picture.jpg"), "拒绝图片");
        check(!AudioPickerFragment.accepts(null, "notes.txt"), "拒绝普通文档");
        check(!AudioPickerFragment.accepts("video/mp4", "video.mp4"), "拒绝视频");

        // 用尚未分配硬件资源的会话测试归属判断，不真实播放音频。
        Class<?> sessionClass = Class.forName(AudioPlaybackRuntime.class.getName() + "$Session");
        Constructor<?> constructor = sessionClass.getDeclaredConstructor(Context.class, String.class, String.class);
        constructor.setAccessible(true);
        Field sessionField = AudioPlaybackRuntime.class.getDeclaredField("sSession");
        sessionField.setAccessible(true);
        Object session = constructor.newInstance(null, "task-b", "test.mp3");
        sessionField.set(null, session);
        AudioPlaybackRuntime.stopOwned("task-a");
        check(sessionField.get(null) == session, "旧任务退出不能停止新任务的播放");
        AudioPlaybackRuntime.stopOwned(null);
        check(sessionField.get(null) == session, "空任务归属不能停止播放");
        AudioPlaybackRuntime.stopOwned("task-b");
        check(sessionField.get(null) == null, "播放所属任务退出可以停止");
    }

    private static void cameraSelections(Gson gson) throws Exception {
        String prefix = "{\"actionKey\":\"" + FirstAppKeys.KEY_PHOTO_RESULT + "\",\"frontCamera\":";
        DeviceActionResultItem oldRear = gson.fromJson(prefix + "false}", DeviceActionResultItem.class);
        DeviceActionResultItem oldFront = gson.fromJson(prefix + "true}", DeviceActionResultItem.class);
        check(oldRear.isRearCamera() && !oldRear.isFrontCamera() && oldRear.l(), "旧版后置单选保持后置");
        check(oldFront.isFrontCamera() && !oldFront.isRearCamera() && oldFront.l(), "旧版前置单选保持前置");
        DeviceActionResultItem photo = new DeviceActionResultItem(FirstAppKeys.KEY_PHOTO_RESULT);
        for (int selection = 1; selection <= 3; selection++) {
            boolean rear = (selection & DeviceActionResultItem.CAMERA_REAR) != 0;
            boolean front = (selection & DeviceActionResultItem.CAMERA_FRONT) != 0;
            photo.setCameras(rear, front);
            check(photo.l() && photo.isRearCamera() == rear && photo.isFrontCamera() == front, "前置、后置及多选都有效");
            DeviceActionResultItem restored = gson.fromJson(gson.toJson(photo), DeviceActionResultItem.class);
            check(restored.getCameraSelection() == selection, "Gson 保留摄像头多选");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(photo); }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                check(((DeviceActionResultItem) in.readObject()).getCameraSelection() == selection, "页面传参保留摄像头多选");
            }
        }
        boolean rejected = false;
        try { photo.setCameras(false, false); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected && photo.isFrontCamera() && photo.isRearCamera(), "空选择不覆盖已保存的多选");
        DeviceActionResultItem malformed = gson.fromJson(prefix + "false,\"cameraSelection\":4}", DeviceActionResultItem.class);
        check(!malformed.l(), "未知摄像头配置不得执行");
        photo.setFrontCamera(false);
        check(photo.isRearCamera() && !photo.isFrontCamera(), "旧单选写入接口仍表示单选");
    }

    private static void assertConfig(DeviceActionResultItem item, String key) {
        check(key.equals(item.e()), "恢复独立操作 key");
        check(item.l(), "恢复后配置有效");
        check(!item.isStartRecording() && item.isFrontCamera(), "恢复录制动作和摄像头朝向");
        check("+8613800138000".equals(item.getPhoneNumber()), "恢复规范号码");
        check("第一行\n第二行 🌟".equals(item.getMessage()), "恢复 Unicode 多行短信");
        check("  自定义通知\n第二行 🔔  ".equals(item.getNotificationText()), "Gson/Serializable 保留独立通知正文");
        check(item.code == 2, "保留原生 TaskItem code");
        if (item.isAudioPlayback()) check("content://local.audio/document/beep".equals(item.getAudioUri())
                && "提示音.mp3".equals(item.getAudioName()), "Gson/Serializable 保留音频引用和文件名");
    }

    private static void ownership() throws Exception {
        check(!RecordingRuntime.owns(null, null), "空归属不能匹配");
        check(!RecordingRuntime.owns("task-a", null), "空 UUID 不能停止其它任务");
        check(!RecordingRuntime.owns("task-a", "task-b"), "任务归属隔离");
        Class<?> sessionClass = Class.forName(RecordingRuntime.class.getName() + "$Session");
        Constructor<?> constructor = sessionClass.getDeclaredConstructor(Context.class, boolean.class, String.class);
        constructor.setAccessible(true);
        Field sessionsField = RecordingRuntime.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        Object[] sessions = (Object[]) sessionsField.get(null);
        // 无硬件资源的会话：直接验证 apply 的恢复分支，防止 null 被误当成全局停止。
        for (int i = 0; i < 2; i++) {
            boolean screen = i == 1;
            Object session = constructor.newInstance(null, screen, "task-a");
            sessions[i] = session;
            RecordingRuntime.apply(null, screen, false, true, null);
            check(sessions[i] == session, "缺失 UUID 的恢复不能停止录制");
            RecordingRuntime.apply(null, screen, false, true, "task-b");
            check(sessions[i] == session, "其它任务恢复不能停止录制");
            RecordingRuntime.apply(null, screen, false, true, "task-a");
            check(sessions[i] == null, "拥有录制的任务可以恢复");
            sessions[i] = constructor.newInstance(null, screen, "task-a");
            RecordingRuntime.apply(null, screen, false, false, "task-b");
            check(sessions[i] == null, "独立停止结果可以停止模块录制");
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
