package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** OMA MMS 1.2 M-Send.req / WSP 二进制编码，仅发送单页 SMIL、UTF-8 正文和 JPEG。 */
final class MmsPdu {
    private MmsPdu() {}

    static byte[] compose(String number, String message, byte[] jpeg, String transaction) {
        if (!DeviceActionResultItem.validNumber(number)) throw new IllegalArgumentException("无效的彩信号码");
        if (jpeg == null || jpeg.length == 0) throw new IllegalArgumentException("彩信照片为空");
        if (transaction == null || !transaction.matches("[A-Za-z0-9-]{1,80}")) throw new IllegalArgumentException("无效的彩信事务");
        String body = message == null ? "" : message;
        boolean hasText = !body.trim().isEmpty();
        Wsp out = new Wsp();
        out.write(0x8c); out.write(0x80); // X-Mms-Message-Type: m-send-req
        out.write(0x98); out.text(transaction);
        out.write(0x8d); out.write(0x92); // X-Mms-MMS-Version: 1.2
        out.write(0x89); out.write(1); out.write(0x81); // From: insert-address-token
        out.write(0x97); // To: Encoded-string-value, UTF-8
        Wsp recipient = new Wsp();
        recipient.write(0xea); recipient.text(DeviceActionResultItem.normalizeNumber(number) + "/TYPE=PLMN");
        out.value(recipient);
        out.write(0x8a); out.write(0x80); // Message-Class: personal
        out.write(0x86); out.write(0x81); // Delivery-Report: no
        out.write(0x90); out.write(0x81); // Read-Report: no
        out.write(0x84); // Content-Type 必须是最后一个顶层头部
        Wsp contentType = new Wsp();
        contentType.write(0xb3); // application/vnd.wap.multipart.related
        contentType.write(0x89); contentType.text("application/smil"); // type
        contentType.write(0x8a); contentType.text("<smil>"); // start
        out.value(contentType);
        out.uintvar(hasText ? 3 : 2);
        String smil = "<smil><head><layout><root-layout width=\"320\" height=\"480\"/>"
                + (hasText ? "<region id=\"Text\" left=\"0\" top=\"0\" width=\"320\" height=\"160\"/>" : "")
                + "<region id=\"Image\" left=\"0\" top=\"" + (hasText ? 160 : 0)
                + "\" width=\"320\" height=\"" + (hasText ? 320 : 480) + "\" fit=\"meet\"/>"
                + "</layout></head><body><par dur=\"8000ms\">"
                + (hasText ? "<text src=\"text.txt\" region=\"Text\"/>" : "")
                + "<img src=\"photo.jpg\" region=\"Image\"/></par></body></smil>";
        part(out, "application/smil", "smil.xml", "smil", smil.getBytes(StandardCharsets.UTF_8), true);
        if (hasText) part(out, "text/plain", "text.txt", "text", body.getBytes(StandardCharsets.UTF_8), true);
        part(out, "image/jpeg", "photo.jpg", "photo", jpeg, false);
        return out.toByteArray();
    }

    private static void part(Wsp out, String mime, String name, String id, byte[] data, boolean utf8) {
        Wsp header = new Wsp();
        Wsp type = new Wsp();
        type.text(mime);
        type.write(0x85); type.text(name); // name parameter
        if (utf8) { type.write(0x81); type.write(0xea); } // charset = UTF-8 (106)
        header.value(type);
        header.write(0xc0); header.write('"'); header.text("<" + id + ">"); // Content-ID: quoted-string
        header.write(0x8e); header.text(name); // Content-Location
        out.uintvar(header.size()); out.uintvar(data.length);
        out.bytes(header.toByteArray()); out.bytes(data);
    }

    private static final class Wsp extends ByteArrayOutputStream {
        void bytes(byte[] data) { write(data, 0, data.length); }
        void text(String text) { bytes(text.getBytes(StandardCharsets.UTF_8)); write(0); }
        void value(Wsp value) {
            if (value.size() <= 30) write(value.size());
            else { write(31); uintvar(value.size()); }
            bytes(value.toByteArray());
        }
        void uintvar(int value) {
            int shift = 0;
            for (int rest = value; (rest >>>= 7) != 0;) shift += 7;
            for (; shift > 0; shift -= 7) write(((value >>> shift) & 0x7f) | 0x80);
            write(value & 0x7f);
        }
    }
}
