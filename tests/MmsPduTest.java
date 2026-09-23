package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 独立读取 WSP/MMS 字节，校验真实协议边界、中文内容和附件引用，不调用 Android 发送接口。 */
public final class MmsPduTest {
    private static int checks;

    public static void main(String[] args) {
        String number = "+86 (138) 0013-8000";
        String body = "本设备触发HyperosAutoTaskPlus行为，我的地址为（测试地点）（116.397470,39.908823）"
                + "（【https://uri.amap.com/marker?position=116.397470,39.908823&coordinate=gaode】）\n附加内容 🌟";
        for (int length : new int[]{127, 128, 16383, 16384, 300 * 1024}) {
            byte[] photo = new byte[length];
            for (int i = 0; i < photo.length; i++) photo[i] = (byte) (i * 31);
            Message decoded = decode(MmsPdu.compose(number, body, photo, "test-transaction-1"));
            check("+8613800138000/TYPE=PLMN".equals(decoded.recipient), "收件人按 PLMN 编码");
            check("test-transaction-1".equals(decoded.transaction), "事务号保留");
            check(decoded.parts.size() == 3, "正文和彩信图片由 SMIL 引用");
            Part text = decoded.parts.get("text.txt");
            Part image = decoded.parts.get("photo.jpg");
            Part smil = decoded.parts.get("smil.xml");
            check(text != null && "text/plain".equals(text.type) && text.charset == 106, "正文声明 UTF-8");
            check(body.equals(new String(text.data, StandardCharsets.UTF_8)), "中文、地图链接、换行及表情未损坏");
            check(image != null && "image/jpeg".equals(image.type), "照片为 JPEG 附件");
            check(Arrays.equals(photo, image.data), "照片跨 uintvar 长度边界完整保留：" + length);
            check(smil != null && "application/smil".equals(smil.type) && "<smil>".equals(smil.id), "根附件与 start 参数匹配");
            String presentation = new String(smil.data, StandardCharsets.UTF_8);
            check(presentation.contains("src=\"text.txt\"") && presentation.contains("src=\"photo.jpg\""), "SMIL 引用存在的附件");
            check(presentation.indexOf("<text ") < presentation.indexOf("<img "), "正文展示在照片前面");
            check(decoded.parts.keySet().toString().equals("[smil.xml, text.txt, photo.jpg]"), "附件顺序为 SMIL、正文、照片");
        }
        for (String bodyWithoutText : new String[]{null, "", " \n "}) {
            Message decoded = decode(MmsPdu.compose("12345678901234567890", bodyWithoutText, new byte[]{1}, "t"));
            check(decoded.parts.size() == 2 && !decoded.parts.containsKey("text.txt"), "无正文时只携带 SMIL 和图片");
            check(!new String(decoded.parts.get("smil.xml").data, StandardCharsets.UTF_8).contains("<text "), "空正文不产生失效 SMIL 引用");
        }
        rejected(() -> MmsPdu.compose("138;139", "正文", new byte[]{1}, "t"));
        rejected(() -> MmsPdu.compose("10086", "正文", null, "t"));
        rejected(() -> MmsPdu.compose("10086", "正文", new byte[0], "t"));
        rejected(() -> MmsPdu.compose("10086", "正文", new byte[]{1}, "bad\u0000transaction"));
        System.out.println("彩信协议回归测试通过：" + checks + " 项断言");
    }

    private static Message decode(byte[] data) {
        Wire wire = new Wire(data);
        Message message = new Message();
        wire.expect(0x8c); wire.expect(0x80); // M-Send.req
        wire.expect(0x98); message.transaction = wire.text();
        wire.expect(0x8d); wire.expect(0x92); // MMS 1.2
        wire.expect(0x89);
        Wire from = wire.value(); from.expect(0x81); from.end();
        wire.expect(0x97);
        Wire to = wire.value(); to.expect(0xea); message.recipient = to.text(); to.end();
        wire.expect(0x8a); wire.expect(0x80); // personal
        wire.expect(0x86); wire.expect(0x81); // no delivery report
        wire.expect(0x90); wire.expect(0x81); // no read report
        wire.expect(0x84);
        Wire type = wire.value(); type.expect(0xb3); // multipart/related
        Map<Integer, String> parameters = new LinkedHashMap<>();
        while (type.remaining() > 0) parameters.put(type.octet(), type.text());
        check("application/smil".equals(parameters.get(0x89)), "根 Content-Type 的 type 参数有效");
        check("<smil>".equals(parameters.get(0x8a)), "根 Content-Type 的 start 参数有效");
        int count = wire.uintvar();
        for (int i = 0; i < count; i++) {
            int headerLength = wire.uintvar();
            int dataLength = wire.uintvar();
            Wire header = wire.slice(headerLength);
            Part part = new Part();
            Wire contentType = header.value();
            part.type = contentType.text();
            String name = null;
            while (contentType.remaining() > 0) {
                int parameter = contentType.octet();
                if (parameter == 0x85) name = contentType.text();
                else if (parameter == 0x81) {
                    int charset = contentType.octet();
                    check((charset & 0x80) != 0, "字符集使用 WSP short-integer");
                    part.charset = charset & 0x7f;
                } else throw new AssertionError("未知 Content-Type 参数 " + parameter);
            }
            header.expect(0xc0); header.expect('"'); part.id = header.text();
            header.expect(0x8e); String location = header.text();
            header.end();
            check(location.equals(name), "Content-Location 与附件名称一致");
            part.data = wire.bytes(dataLength);
            check(message.parts.put(location, part) == null, "附件名称唯一");
        }
        wire.end();
        return message;
    }

    private static void rejected(Runnable action) {
        try { action.run(); throw new AssertionError("无效 PDU 输入必须拒绝"); }
        catch (IllegalArgumentException expected) { checks++; }
    }

    private static void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }

    private static final class Message {
        String transaction;
        String recipient;
        final Map<String, Part> parts = new LinkedHashMap<>();
    }

    private static final class Part {
        String type;
        String id;
        int charset;
        byte[] data;
    }

    private static final class Wire {
        final byte[] bytes;
        int cursor;

        Wire(byte[] bytes) { this.bytes = bytes; }
        int remaining() { return bytes.length - cursor; }
        int octet() {
            if (remaining() == 0) throw new AssertionError("PDU 意外结束");
            return bytes[cursor++] & 0xff;
        }
        void expect(int value) { check(octet() == value, "WSP 头部/标记 " + value); }
        void end() { check(remaining() == 0, "声明的字节边界与实际 PDU 一致"); }
        int uintvar() {
            int result = 0;
            for (int i = 0; i < 5; i++) {
                int next = octet();
                result = (result << 7) | (next & 0x7f);
                if ((next & 0x80) == 0) return result;
            }
            throw new AssertionError("WSP uintvar 过长");
        }
        byte[] bytes(int count) {
            if (count < 0 || count > remaining()) throw new AssertionError("附件长度越界");
            byte[] result = Arrays.copyOfRange(bytes, cursor, cursor + count);
            cursor += count;
            return result;
        }
        Wire slice(int count) { return new Wire(bytes(count)); }
        Wire value() {
            int length = octet();
            if (length == 31) length = uintvar();
            else if (length > 30) throw new AssertionError("无效 WSP value-length");
            return slice(length);
        }
        String text() {
            int start = cursor;
            while (octet() != 0) { /* WSP Text-string 以 NUL 结束 */ }
            return new String(bytes, start, cursor - start - 1, StandardCharsets.UTF_8);
        }
    }
}
