package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** 只生成 ASCII 传输内容；标题用 RFC 2047，正文和 JPEG 用 MIME Base64。 */
final class MimeMessageWriter {
    static final int MAX_BODY_BYTES = 1024 * 1024;
    static final int MAX_PHOTO_BYTES = 5 * 1024 * 1024;
    private static final byte[] CRLF = {'\r', '\n'};

    private MimeMessageWriter() {}

    static void write(OutputStream out, EmailConfig config, String body, byte[] jpeg) throws IOException {
        String id = UUID.randomUUID().toString();
        String boundary = "hyperos_" + id;
        line(out, "Date: " + new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(new Date()));
        line(out, "Message-ID: <" + id + "@hyperosautotaskplus.invalid>");
        line(out, "From: <" + config.from + ">");
        line(out, "To: <" + config.to + ">");
        subject(out, config.subject == null ? "" : config.subject);
        line(out, "MIME-Version: 1.0");
        if (jpeg == null) {
            textPart(out, body);
            return;
        }
        line(out, "Content-Type: multipart/mixed; boundary=\"" + boundary + "\"");
        line(out, "");
        line(out, "--" + boundary);
        textPart(out, body);
        line(out, "--" + boundary);
        line(out, "Content-Type: image/jpeg; name=\"photo.jpg\"");
        line(out, "Content-Disposition: attachment; filename=\"photo.jpg\"");
        line(out, "Content-Transfer-Encoding: base64");
        line(out, "");
        base64(out, jpeg);
        line(out, "--" + boundary + "--");
    }

    private static void textPart(OutputStream out, String body) throws IOException {
        line(out, "Content-Type: text/plain; charset=UTF-8");
        line(out, "Content-Transfer-Encoding: base64");
        line(out, "");
        String normalized = (body == null ? "" : body).replace("\r\n", "\n")
                .replace('\r', '\n').replace("\n", "\r\n");
        base64(out, normalized.getBytes(StandardCharsets.UTF_8));
    }

    private static void subject(OutputStream out, String value) throws IOException {
        if (value.isEmpty()) { line(out, "Subject:"); return; }
        // 不在 UTF-8 字符内部拆分 encoded-word；每个物理头部行不超过 77 字节。
        String prefix = "Subject: ";
        for (int start = 0; start < value.length();) {
            int end = start;
            int bytes = 0;
            while (end < value.length()) {
                int cp = value.codePointAt(end);
                int size = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
                if (bytes + size > 42) break;
                bytes += size;
                end += Character.charCount(cp);
            }
            String encoded = Base64.getEncoder().encodeToString(value.substring(start, end).getBytes(StandardCharsets.UTF_8));
            line(out, prefix + "=?UTF-8?B?" + encoded + "?=");
            prefix = " ";
            start = end;
        }
    }

    private static void base64(OutputStream out, byte[] data) throws IOException {
        // 57 原始字节恰好编码成 76 字符，避免一次分配整个照片的 Base64 副本。
        Base64.Encoder encoder = Base64.getEncoder();
        byte[] chunk = new byte[57];
        for (int offset = 0; offset < data.length; offset += chunk.length) {
            int count = Math.min(chunk.length, data.length - offset);
            byte[] part = count == chunk.length ? chunk : new byte[count];
            System.arraycopy(data, offset, part, 0, count);
            out.write(encoder.encode(part));
            out.write(CRLF);
        }
        if (data.length == 0) out.write(CRLF);
    }

    private static void line(OutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
    }
}
