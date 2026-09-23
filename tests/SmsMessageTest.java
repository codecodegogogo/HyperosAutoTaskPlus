package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.google.gson.Gson;
import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Locale;

/** 不调用定位/相机/运营商接口，验证消息配置兼容性、正文格式和坐标转换。 */
public final class SmsMessageTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        attachments();
        formatting();
        coordinates();
        System.out.println("短信位置回归测试通过：" + checks + " 项断言");
    }

    private static void attachments() throws Exception {
        Gson gson = new Gson();
        String legacy = "{\"actionKey\":\"" + FirstAppKeys.KEY_SMS_RESULT
                + "\",\"phoneNumber\":\"10086\",\"message\":\"原有正文\"}";
        DeviceActionResultItem old = gson.fromJson(legacy, DeviceActionResultItem.class);
        check(old.l() && !old.isSendLocation() && !old.isSendEnvironmentPhoto(), "旧任务默认不添加位置或照片");
        for (int selection = 0; selection < 4; selection++) {
            boolean location = (selection & 1) != 0;
            boolean photo = (selection & 2) != 0;
            DeviceActionResultItem item = new DeviceActionResultItem(FirstAppKeys.KEY_SMS_RESULT);
            item.setContact("+86 (138) 0013-8000", "  补充文字\n第二行 📍  ");
            item.setMessageAttachments(location, photo);
            check(item.l(), "四种勾选组合均允许保存");
            DeviceActionResultItem json = gson.fromJson(gson.toJson(item), DeviceActionResultItem.class);
            checkAttachments(json, location, photo);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(item); }
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                checkAttachments((DeviceActionResultItem) input.readObject(), location, photo);
            }
            for (String empty : new String[]{null, "", " \n\t "}) {
                item.setContact("10086", empty);
                check(item.l() == (location || photo), "选位置或照片时正文可以留空，否则保留空正文校验");
            }
            item.setContact("", "补充内容");
            check(!item.l(), "附加位置或照片不能绕过收件号码校验");
            check(!item.restoresOnExit(), "退出任务不能再次发送短信或彩信");
            item.o();
        }
    }

    private static void checkAttachments(DeviceActionResultItem item, boolean location, boolean photo) {
        check(item.isSendLocation() == location && item.isSendEnvironmentPhoto() == photo,
                "两项勾选独立保存和恢复");
        check("+8613800138000".equals(item.getPhoneNumber()) && "  补充文字\n第二行 📍  ".equals(item.getMessage()),
                "位置和照片选项不覆盖原号码与正文");
        check(item.l(), "恢复后的短信或彩信配置有效");
    }

    private static void formatting() {
        String header = "本设备触发HyperosAutoTaskPlus行为";
        check(header.equals(LocationMessage.text(null)), "仅照片也包含设备行为说明");
        check(header.equals(LocationMessage.text(header)), "手填固定开头不重复");
        String supplement = "  请查看当前位置 📍\n第二行  ";
        check((header + "\n" + supplement).equals(LocationMessage.text(supplement)), "普通短信保留补充内容空格和换行");
        check((header + "\n" + supplement).equals(LocationMessage.text(header + "\n" + supplement)), "原有固定开头和补充内容只合并一次");
        String link = "https://uri.amap.com/marker?position=-0.127800,51.507400"
                + "&name=%E5%BD%93%E5%89%8D%E4%BD%8D%E7%BD%AE&coordinate=gaode";
        String expected = header + "，我的地址为（伦敦）（经度：-0.127800，纬度：51.507400）（【" + link + "】）";
        String body = LocationMessage.append("", -0.1278, 51.5074, "伦敦");
        check(expected.equals(body), "地址、经度、纬度、高德链接完全符合约定模板");
        check((expected + "\n" + supplement).equals(LocationMessage.append(supplement, -0.1278, 51.5074, "伦敦")),
                "补充内容跟随定位正文");
        check(expected.equals(LocationMessage.append(header, -0.1278, 51.5074, "伦敦")), "定位消息只有一个固定开头");
        check(LocationMessage.append("", 116.397128, 39.916527, null).contains("（地点暂无法解析）"), "地点解析失败不编造地名");
        check(LocationMessage.append("", 116.397128, 39.916527, "  ").contains("【https://uri.amap.com/marker?position="),
                "地点未知时仍保留有效坐标链接");
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            check(expected.equals(LocationMessage.append("", -0.1278, 51.5074, "伦敦")), "不同系统语言不改变坐标小数点或链接格式");
        } finally { Locale.setDefault(previous); }
    }

    private static void coordinates() {
        double[] beijing = LocationMessage.toAmap(116.397128, 39.916527);
        check(Math.abs(beijing[0] - 116.403372) < 0.000001 && Math.abs(beijing[1] - 39.917931) < 0.000001,
                "北京 WGS-84 坐标转为高德 GCJ-02，避免地图偏移");
        String message = LocationMessage.append("", 116.397128, 39.916527, "北京市");
        check(message.contains("经度：116.397128，纬度：39.916527"), "正文保留原始经纬度并标明顺序");
        check(message.contains("position=116.403372,39.917931"), "链接以高德经度、纬度顺序生成");
        double[] london = LocationMessage.toAmap(-0.1278, 51.5074);
        check(london[0] == -0.1278 && london[1] == 51.5074, "境外坐标不进行中国坐标偏移");
        for (double[] invalid : new double[][]{{Double.NaN, 0}, {0, Double.NaN}, {Double.POSITIVE_INFINITY, 0},
                {0, Double.NEGATIVE_INFINITY}, {180.1, 0}, {-180.1, 0}, {0, 90.1}, {0, -90.1}}) {
            boolean rejected = false;
            try { LocationMessage.toAmap(invalid[0], invalid[1]); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "无效坐标不能进入短信链接");
        }
        check(LocationMessage.usable(0, 1) && LocationMessage.usable(15_000, 200), "允许新鲜且精度足够的位置");
        check(!LocationMessage.usable(-1, 10) && !LocationMessage.usable(15_001, 10), "拒绝未来或陈旧的定位缓存");
        for (float accuracy : new float[]{0, -1, 201, Float.NaN, Float.POSITIVE_INFINITY}) {
            check(!LocationMessage.usable(100, accuracy), "拒绝无效或过低精度的位置");
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
