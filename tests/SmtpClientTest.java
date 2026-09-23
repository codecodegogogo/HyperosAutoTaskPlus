package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** 仅监听本机回环地址，证书在测试时生成；不访问外部邮箱、不使用真实凭据。 */
public final class SmtpClientTest {
    private static final String USER = "sender@example.test";
    private static final String SECRET = "local-test-only";
    private static final String BODY = "位置 📍\n.\n..正文\r单独回车\r\n最后一行";
    private static int checks;
    private static SSLContext serverContext;
    private static SSLSocketFactory trustedFactory;

    public static void main(String[] args) throws Exception {
        createLocalTls();
        implicitLoginAndMime();
        startTlsPlainAndPhoto();
        tlsFailures();
        authenticationFailures();
        deliveryOutcomes();
        timeoutsAndCancellation();
        inputLimits();
        System.out.println("邮件协议回归测试通过：" + checks + " 项断言");
    }

    private static void implicitLoginAndMime() throws Exception {
        try (FakeServer server = new FakeServer(wire -> exchange(wire, false, "LOGIN", 0))) {
            EmailConfig config = config(server, false);
            config.username = "";
            config.subject = "中文标题📍".repeat(18);
            send(config, BODY, null);
            server.await();
            check(config.subject.equals(subject(server.message)), "UTF-8 长标题按完整字符折行且可完整解码");
            check(normalized(BODY).equals(textBody(server.message)), "纯文本正文保留中文、emoji、独立点行和换行");
            check(!server.message.contains("multipart/mixed") && !server.message.contains("image/jpeg"), "不选照片时发送纯文本 MIME");
            check(server.message.contains("From: <" + USER + ">\r\n"), "邮件头发送者与 SMTP 信封一致");
            check(server.message.contains("Message-ID: <"), "邮件包含独立 Message-ID");
            assertMimeLines(server.message);
        }
    }

    private static void startTlsPlainAndPhoto() throws Exception {
        byte[] photo = new byte[2049];
        for (int i = 0; i < photo.length; i++) photo[i] = (byte) (i * 29);
        photo[0] = (byte) 0xff; photo[1] = (byte) 0xd8;
        for (String method : new String[]{"PLAIN", "PLAIN-CHALLENGE"}) {
            try (FakeServer server = new FakeServer(wire -> exchange(wire, true, method, 0))) {
                send(config(server, true), BODY, photo);
                server.await();
                check(server.message.contains("multipart/mixed; boundary=\"hyperos_"), "照片邮件使用独立 multipart 边界");
                check(normalized(BODY).equals(textBody(server.message)), "附件邮件中的中文正文可以解码");
                Matcher part = Pattern.compile("Content-Type: image/jpeg; name=\"photo.jpg\"\\r\\n"
                        + "Content-Disposition: attachment; filename=\"photo.jpg\"\\r\\n"
                        + "Content-Transfer-Encoding: base64\\r\\n\\r\\n([A-Za-z0-9+/=\\r\\n]*)").matcher(server.message);
                check(part.find() && Arrays.equals(photo, Base64.getMimeDecoder().decode(part.group(1))), "JPEG 附件 Base64 可无损还原");
                assertMimeLines(server.message);
            }
        }
    }

    private static void tlsFailures() throws Exception {
        try (FakeServer server = new FakeServer(wire -> {
            try { wire.secure(); throw new AssertionError("不可信证书不应完成 TLS 握手"); }
            catch (IOException expected) { /* 客户端拒绝本地自签名证书，可能以 TLS alert 或断线结束。 */ }
        })) {
            expect(SmtpClient.Code.TLS_FAILED, () -> SmtpClient.send(config(server, false), SECRET, BODY, null));
            server.await();
        }
        try (FakeServer server = new FakeServer(wire -> {
            try { wire.secure(); throw new AssertionError("主机名不匹配不应完成 TLS 握手"); }
            catch (IOException expected) { /* 证书只包含 localhost，不包含 IP。 */ }
        })) {
            EmailConfig wrongHost = config(server, false);
            wrongHost.smtpHost = "127.0.0.1";
            expect(SmtpClient.Code.TLS_FAILED, () -> send(wrongHost, BODY, null));
            server.await();
        }
        try (FakeServer server = new FakeServer(wire -> {
            wire.write("220 loopback\r\n"); wire.expect("EHLO hyperosautotaskplus.invalid");
            wire.write("250-loopback\r\n250 AUTH LOGIN\r\n");
            check(wire.eof(), "缺少 STARTTLS 时直接关闭连接，没有发送 AUTH 或邮件");
        })) {
            expect(SmtpClient.Code.TLS_REQUIRED, () -> send(config(server, true), BODY, null));
            server.await();
        }
        try (FakeServer server = new FakeServer(wire -> {
            startTlsGreeting(wire);
            wire.write("454 TLS unavailable\r\n");
            check(wire.eof(), "STARTTLS 被拒绝时不会降级明文发送");
        })) {
            expect(SmtpClient.Code.TLS_FAILED, () -> send(config(server, true), BODY, null));
            server.await();
        }
        try (FakeServer server = new FakeServer(wire -> {
            startTlsGreeting(wire);
            wire.write("220 switch\r\n");
            try { wire.secure(); throw new AssertionError("STARTTLS 必须校验证书"); }
            catch (IOException expected) {}
        })) {
            expect(SmtpClient.Code.TLS_FAILED, () -> SmtpClient.send(config(server, true), SECRET, BODY, null));
            server.await();
        }
    }

    private static void authenticationFailures() throws Exception {
        try (FakeServer server = new FakeServer(wire -> exchange(wire, true, "LOGIN", 1))) {
            SmtpClient.MailException error = expect(SmtpClient.Code.AUTH_FAILED, () -> send(config(server, true), BODY, null));
            check(!error.getMessage().contains(USER) && !error.getMessage().contains(SECRET), "认证错误不会回显服务器返回的邮箱和授权码");
            server.await();
        }
        try (FakeServer server = new FakeServer(wire -> {
            startTlsGreeting(wire); wire.write("220 switch\r\n"); wire.secure();
            wire.expect("EHLO hyperosautotaskplus.invalid");
            wire.write("250-loopback\r\n250 AUTH XOAUTH2\r\n");
            check(wire.eof(), "TLS 后不沿用明文阶段的 LOGIN 能力，也不尝试不支持的认证");
        })) {
            expect(SmtpClient.Code.AUTH_UNSUPPORTED, () -> send(config(server, true), BODY, null));
            server.await();
        }
    }

    private static void deliveryOutcomes() throws Exception {
        try (FakeServer server = new FakeServer(wire -> exchange(wire, false, "LOGIN", 2))) {
            SmtpClient.MailException error = expect(SmtpClient.Code.RESULT_UNKNOWN, () -> send(config(server, false), BODY, null));
            check(error.getMessage().contains("结果未知") && error.getMessage().contains("避免重复发送"), "提交后断线明确提示结果未知而非建议直接重试");
            server.await();
        }
        try (FakeServer server = new FakeServer(wire -> exchange(wire, false, "LOGIN", 3))) {
            expect(SmtpClient.Code.SERVER_REJECTED, () -> send(config(server, false), BODY, null));
            server.await();
        }
        try (FakeServer server = new FakeServer(wire -> exchange(wire, false, "LOGIN", 4))) {
            send(config(server, false), BODY, null);
            server.await();
            check(true, "DATA 后已收到 250，即使 QUIT 返回失败也保持成功");
        }
        try (FakeServer server = new FakeServer(wire -> exchange(wire, false, "LOGIN", 5))) {
            expect(SmtpClient.Code.RESULT_UNKNOWN, () -> send(config(server, false), BODY, null));
            server.await();
        }
        try (FakeServer server = new FakeServer(wire -> exchange(wire, false, "LOGIN", 6))) {
            expect(SmtpClient.Code.RECIPIENT_REJECTED, () -> send(config(server, false), BODY, null));
            server.await();
        }
    }

    private static void timeoutsAndCancellation() throws Exception {
        try (FakeServer server = new FakeServer(wire -> check(wire.eof(), "总超时会关闭未给出问候的 TCP 连接"))) {
            long start = System.nanoTime();
            expect(SmtpClient.Code.TIMEOUT, () -> SmtpClient.send(config(server, true), SECRET, BODY, null,
                    trustedFactory, 4000, 200));
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000, "总时限不依赖较长的 socket 读取超时");
            server.await();
        }
        try (FakeServer server = new FakeServer(wire -> exchange(wire, false, "LOGIN", 7))) {
            expect(SmtpClient.Code.RESULT_UNKNOWN, () -> SmtpClient.send(config(server, false), SECRET, BODY, null,
                    trustedFactory, 1000, 4000));
            server.await();
        }
        try (FakeServer server = new FakeServer(wire -> {
            wire.write("220 malformed\n");
            check(wire.eof(), "非法换行响应会关闭连接");
        })) {
            expect(SmtpClient.Code.PROTOCOL, () -> send(config(server, true), BODY, null));
            server.await();
        }
    }

    private static void inputLimits() throws Exception {
        EmailConfig config = config(null, false);
        config.subject = "正常标题\r\nBcc: attacker@example.test";
        expect(SmtpClient.Code.INVALID_CONFIG, () -> send(config, BODY, null));
        config.subject = "测试";
        expect(SmtpClient.Code.INVALID_CONFIG, () -> SmtpClient.send(config, "bad\nsecret", BODY, null));
        config.to = "recipient@example.test\r\nDATA";
        expect(SmtpClient.Code.INVALID_CONFIG, () -> send(config, BODY, null));
        config.to = "recipient@example.test";
        expect(SmtpClient.Code.MESSAGE_TOO_LARGE, () -> send(config, "界".repeat(400_000), null));
        expect(SmtpClient.Code.MESSAGE_TOO_LARGE, () -> send(config, BODY, new byte[5 * 1024 * 1024 + 1]));
        expect(SmtpClient.Code.MESSAGE_TOO_LARGE, () -> send(config, BODY, new byte[0]));
    }

    private static void exchange(Wire wire, boolean startTls, String method, int mode) throws Exception {
        if (startTls) {
            startTlsGreeting(wire); wire.write("220 switch\r\n"); wire.secure();
        } else {
            wire.secure(); wire.write("220 loopback\r\n");
        }
        wire.expect("EHLO hyperosautotaskplus.invalid");
        wire.write("250-loopback\r\n250 AUTH " + (method.startsWith("PLAIN") ? "PLAIN" : "LOGIN") + "\r\n");
        if (method.startsWith("PLAIN")) {
            String command = wire.line();
            check(command.startsWith("AUTH PLAIN "), "按照加密后的 EHLO 能力选择 PLAIN");
            String token = command.substring(11);
            if (method.equals("PLAIN-CHALLENGE")) { wire.write("334 \r\n"); token = wire.line(); }
            check(("\0" + USER + "\0" + SECRET).equals(decoded(token)), "PLAIN 认证携带完整的用户名和授权码");
        } else {
            wire.expect("AUTH LOGIN"); wire.write("334 VXNlcm5hbWU6\r\n");
            check(USER.equals(decoded(wire.line())), "LOGIN 空用户名默认发件邮箱");
            wire.write("334 UGFzc3dvcmQ6\r\n");
            check(SECRET.equals(decoded(wire.line())), "LOGIN 认证授权码正确且在 TLS 内发送");
        }
        if (mode == 1) {
            wire.write("535 denied " + USER + " " + SECRET + "\r\n");
            check(wire.eof(), "认证失败后不发送 MAIL FROM");
            return;
        }
        wire.write("235 authenticated\r\n");
        wire.expect("MAIL FROM:<" + USER + ">"); wire.write("250 sender accepted\r\n");
        wire.expect("RCPT TO:<recipient@example.test>");
        if (mode == 6) {
            wire.write("550 rejected\r\n"); check(wire.eof(), "收件人被拒绝后不会提交 DATA"); return;
        }
        wire.write("250 recipient accepted\r\n");
        wire.expect("DATA"); wire.write("354 continue\r\n");
        StringBuilder message = new StringBuilder();
        for (;;) {
            String line = wire.line();
            if (line.equals(".")) break;
            message.append(line).append("\r\n");
            if (message.length() > 8 * 1024 * 1024) throw new AssertionError("测试邮件不应超出限制");
        }
        wire.owner.message = message.toString();
        if (mode == 2) return;
        if (mode == 3) { wire.write("550 not accepted\r\n"); check(wire.eof(), "DATA 明确拒绝后连接关闭"); return; }
        if (mode == 5) { wire.write("354 unexpected\r\n"); check(wire.eof(), "DATA 后非 250 不能当作成功"); return; }
        if (mode == 7) { check(wire.eof(), "DATA 确认超时后会关闭连接且不会重发"); return; }
        wire.write("250 queued\r\n");
        wire.expect("QUIT");
        if (mode == 4) {
            try { wire.write("500 quit failed\r\n"); } catch (IOException ignored) {}
        }
    }

    private static void startTlsGreeting(Wire wire) throws Exception {
        wire.write("220 loopback\r\n"); wire.expect("EHLO hyperosautotaskplus.invalid");
        wire.write("250-loopback\r\n250-STARTTLS\r\n250 AUTH LOGIN\r\n");
        wire.expect("STARTTLS");
    }

    private static EmailConfig config(FakeServer server, boolean startTls) {
        EmailConfig config = new EmailConfig();
        config.smtpHost = "localhost"; config.smtpPort = server == null ? 465 : server.port();
        config.startTls = startTls; config.from = USER; config.to = "recipient@example.test";
        config.username = USER; config.credentialId = "local-test"; config.subject = "本地测试📍"; config.body = "正文";
        return config;
    }

    private static void send(EmailConfig config, String body, byte[] photo) throws Exception {
        SmtpClient.send(config, SECRET, body, photo, trustedFactory, 2500, 8000);
    }

    private static String normalized(String value) { return value.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n"); }
    private static String decoded(String value) { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }

    private static String textBody(String message) {
        Matcher match = Pattern.compile("Content-Type: text/plain; charset=UTF-8\\r\\n"
                + "Content-Transfer-Encoding: base64\\r\\n\\r\\n([A-Za-z0-9+/=\\r\\n]*)").matcher(message);
        check(match.find(), "存在 UTF-8 Base64 文本部分");
        return new String(Base64.getMimeDecoder().decode(match.group(1)), StandardCharsets.UTF_8);
    }

    private static String subject(String message) {
        String header = message.substring(message.indexOf("Subject:"), message.indexOf("MIME-Version:"));
        Matcher words = Pattern.compile("=\\?UTF-8\\?B\\?([^?]*)\\?=").matcher(header);
        StringBuilder result = new StringBuilder();
        while (words.find()) result.append(decoded(words.group(1)));
        return result.toString();
    }

    private static void assertMimeLines(String message) {
        check(message.startsWith("Date: "), "邮件含有标准 Date 头部");
        for (String line : message.split("\r\n", -1)) {
            if (line.length() > 998) throw new AssertionError("MIME 行长超过 SMTP 限制");
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch >= 128 || ch == '\r' || ch == '\n') throw new AssertionError("传输内容须为 ASCII 且仅用 CRLF 换行");
            }
        }
        check(true, "所有 MIME 传输行均为 ASCII、合法长度和 CRLF");
    }

    private static SmtpClient.MailException expect(SmtpClient.Code code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("应当失败：" + code); }
        catch (SmtpClient.MailException error) {
            check(error.getCode() == code, "错误码应为 " + code + "，实际为 " + error.getCode());
            return error;
        }
    }

    private static synchronized void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private interface Action { void run() throws Exception; }
    private interface Handler { void run(Wire wire) throws Exception; }

    private static final class FakeServer implements AutoCloseable {
        private final ServerSocket listener;
        private final FutureTask<Void> task;
        private volatile Socket connected;
        volatile String message;
        FakeServer(Handler handler) throws Exception {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(10_000);
            task = new FutureTask<>(() -> {
                try (Socket raw = listener.accept()) {
                    connected = raw; raw.setSoTimeout(5000);
                    handler.run(new Wire(this, raw));
                } finally { listener.close(); }
                return null;
            });
            Thread server = new Thread(task, "Local-SMTP-Test");
            server.setDaemon(true); server.start();
        }
        int port() { return listener.getLocalPort(); }
        void await() throws Exception { task.get(10, TimeUnit.SECONDS); }
        public void close() throws IOException {
            listener.close();
            if (connected != null) connected.close();
            task.cancel(true);
        }
    }

    private static final class Wire {
        final FakeServer owner;
        private Socket socket;
        private InputStream in;
        private OutputStream out;
        Wire(FakeServer owner, Socket socket) throws IOException { this.owner = owner; set(socket); }
        private void set(Socket value) throws IOException {
            socket = value; in = new BufferedInputStream(socket.getInputStream()); out = socket.getOutputStream();
        }
        void secure() throws Exception {
            SSLSocket tls = (SSLSocket) serverContext.getSocketFactory().createSocket(socket, "localhost", owner.port(), true);
            tls.setUseClientMode(false);
            tls.setEnabledProtocols(new String[]{"TLSv1.2"});
            tls.setSoTimeout(5000); tls.startHandshake();
            set(tls);
            check("TLSv1.2".equals(tls.getSession().getProtocol()), "本地服务器通过实际 TLS 1.2 握手");
        }
        void write(String text) throws IOException { out.write(text.getBytes(StandardCharsets.US_ASCII)); out.flush(); }
        void expect(String expected) throws IOException { check(expected.equals(line()), "客户端命令顺序及内容符合 SMTP 协议"); }
        boolean eof() throws IOException { return in.read() == -1; }
        String line() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (;;) {
                int ch = in.read();
                if (ch < 0) throw new EOFException();
                if (ch == '\r') {
                    if (in.read() != '\n') throw new AssertionError("客户端必须使用 CRLF");
                    return bytes.toString(StandardCharsets.US_ASCII.name());
                }
                if (ch == '\n' || bytes.size() > 16_384) throw new AssertionError("非法 SMTP 行");
                bytes.write(ch);
            }
        }
    }

    private static void createLocalTls() throws Exception {
        Path directory = Files.createTempDirectory("hyperos-smtp-test-");
        Path storePath = directory.resolve("localhost.p12");
        char[] password = "local-test-password".toCharArray();
        try {
            Path tool = Paths.get(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
            Process keytool = new ProcessBuilder(tool.toString(), "-genkeypair", "-alias", "localhost",
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                    "-ext", "SAN=dns:localhost", "-storetype", "PKCS12", "-keystore", storePath.toString(),
                    "-storepass", new String(password), "-keypass", new String(password), "-noprompt")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!keytool.waitFor(20, TimeUnit.SECONDS)) {
                keytool.destroyForcibly(); throw new AssertionError("生成本地测试证书超时");
            }
            check(keytool.exitValue() == 0, "生成仅测试使用的 localhost 证书");
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(storePath)) { keys.load(input, password); }
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, password);
            serverContext = SSLContext.getInstance("TLS");
            serverContext.init(keyManagers.getKeyManagers(), null, null);
            KeyStore certificates = KeyStore.getInstance(KeyStore.getDefaultType());
            certificates.load(null, null); certificates.setCertificateEntry("localhost", keys.getCertificate("localhost"));
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(certificates);
            SSLContext client = SSLContext.getInstance("TLS");
            client.init(null, trust.getTrustManagers(), null);
            trustedFactory = client.getSocketFactory();
        } finally {
            Arrays.fill(password, '\0');
            Files.deleteIfExists(storePath); Files.deleteIfExists(directory);
        }
    }
}
