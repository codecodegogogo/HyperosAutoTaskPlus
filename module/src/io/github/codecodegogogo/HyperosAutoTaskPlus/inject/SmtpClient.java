package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** 最小 SMTP submission 客户端；不发送明文凭据，不重试已经提交的邮件。 */
final class SmtpClient {
    static final int TOTAL_TIMEOUT_MS = 60_000;
    private static final int IO_TIMEOUT_MS = 15_000;
    private static final int MAX_REPLY_LINE = 2048;
    private static final int MAX_REPLY_LINES = 100;

    enum Code {
        INVALID_CONFIG, MESSAGE_TOO_LARGE, TLS_REQUIRED, TLS_FAILED, AUTH_UNSUPPORTED,
        AUTH_FAILED, RECIPIENT_REJECTED, SERVER_REJECTED, TIMEOUT, NETWORK, PROTOCOL, RESULT_UNKNOWN
    }

    static final class MailException extends Exception {
        final Code code;
        MailException(Code code) { super(message(code)); this.code = code; }
        Code getCode() { return code; }
    }

    private SmtpClient() {}

    static void send(EmailConfig config, String secret, String body, byte[] jpeg) throws Exception {
        send(config, secret, body, jpeg, (SSLSocketFactory) SSLSocketFactory.getDefault(),
                IO_TIMEOUT_MS, TOTAL_TIMEOUT_MS);
    }

    /** 同包测试注入本地证书；生产入口只使用系统工厂，所有工厂均强制主机名与 TLS 版本校验。 */
    static void send(EmailConfig config, String secret, String body, byte[] jpeg,
                     SSLSocketFactory tlsFactory, int ioTimeoutMs, int totalTimeoutMs) throws Exception {
        if (config == null) throw failure(Code.INVALID_CONFIG);
        EmailConfig snapshot = config.copy();
        validate(snapshot, secret, body, jpeg);
        if (tlsFactory == null || ioTimeoutMs <= 0 || totalTimeoutMs <= 0) throw failure(Code.INVALID_CONFIG);
        Session session = new Session(snapshot, secret, body, jpeg, tlsFactory, ioTimeoutMs);
        FutureTask<Void> result = new FutureTask<>(() -> { session.run(); return null; });
        Thread worker = new Thread(result, "HyperAutoTask-SMTP");
        worker.setDaemon(true);
        worker.start();
        try {
            // DNS、TLS 握手及套接字写入都纳入总时限；取消后关闭底层连接并阻止后续连接。
            result.get(totalTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            session.close();
            if (session.confirmed) return;
            throw failure(session.uncertain() ? Code.RESULT_UNKNOWN : Code.TIMEOUT);
        } catch (InterruptedException e) {
            session.close();
            Thread.currentThread().interrupt();
            if (session.confirmed) return;
            throw failure(session.uncertain() ? Code.RESULT_UNKNOWN : Code.NETWORK);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof MailException) throw (MailException) e.getCause();
            throw failure(session.uncertain() ? Code.RESULT_UNKNOWN : Code.NETWORK);
        } finally {
            session.close();
            result.cancel(true);
        }
    }

    private static void validate(EmailConfig config, String secret, String body, byte[] jpeg) throws MailException {
        if (config == null || config.validationError(false) != null || !safe(config.smtpHost)
                || !safe(config.from) || !safe(config.to) || !safe(config.subject)
                || !safe(config.username) || !safe(secret) || secret == null || secret.isEmpty()
                || secret.length() > 4096) throw failure(Code.INVALID_CONFIG);
        // 运行时会追加位置文字，因此传输层按实际 UTF-8 大小再次设上限。
        if ((body != null && (body.length() > MimeMessageWriter.MAX_BODY_BYTES
                || body.getBytes(StandardCharsets.UTF_8).length > MimeMessageWriter.MAX_BODY_BYTES))
                || (jpeg != null && (jpeg.length == 0 || jpeg.length > MimeMessageWriter.MAX_PHOTO_BYTES))) {
            throw failure(Code.MESSAGE_TOO_LARGE);
        }
    }

    private static boolean safe(String text) {
        return text == null || (text.indexOf('\r') < 0 && text.indexOf('\n') < 0 && text.indexOf('\0') < 0);
    }

    private static MailException failure(Code code) { return new MailException(code); }

    private static String message(Code code) {
        switch (code) {
            case INVALID_CONFIG: return "邮件设置无效，请检查服务器、邮箱及授权码";
            case MESSAGE_TOO_LARGE: return "邮件内容过大或照片为空，照片需小于等于 5 MiB";
            case TLS_REQUIRED: return "邮件服务器不支持 STARTTLS，已停止发送";
            case TLS_FAILED: return "邮件安全连接失败，请检查服务器地址、证书及设备时间";
            case AUTH_UNSUPPORTED: return "邮件服务器不支持所需的安全登录方式";
            case AUTH_FAILED: return "邮箱登录失败，请检查 SMTP 服务及授权码";
            case RECIPIENT_REJECTED: return "邮件服务器拒绝收件人，请检查收件邮箱";
            case SERVER_REJECTED: return "邮件服务器拒绝本次发送，请检查邮箱设置或稍后再试";
            case TIMEOUT: return "邮件连接超时，请检查网络后再试";
            case NETWORK: return "邮件连接中断，请检查网络后再试";
            case PROTOCOL: return "邮件服务器响应异常，本次发送已停止";
            case RESULT_UNKNOWN: return "邮件已提交但未收到确认，发送结果未知；请先查看收件箱，避免重复发送";
            default: return "邮件发送失败";
        }
    }

    private static final class Session {
        private final EmailConfig config;
        private final String secret;
        private final String body;
        private final byte[] jpeg;
        private final SSLSocketFactory factory;
        private final int ioTimeout;
        private Socket socket;
        private Socket transport;
        private boolean closed;
        private InputStream in;
        private OutputStream out;
        // 终止符可能已经部分进入网络即不能安全重发；确认/明确拒绝都会清除此状态。
        private volatile boolean submitted;
        private volatile boolean confirmed;
        private volatile boolean rejected;

        Session(EmailConfig config, String secret, String body, byte[] jpeg,
                SSLSocketFactory factory, int ioTimeout) {
            this.config = config; this.secret = secret; this.body = body;
            this.jpeg = jpeg; this.factory = factory; this.ioTimeout = ioTimeout;
        }

        boolean uncertain() { return submitted && !confirmed && !rejected; }

        void run() throws MailException {
            try {
                Socket raw = new Socket();
                track(raw);
                InetSocketAddress address = new InetSocketAddress(config.smtpHost, config.smtpPort);
                if (Thread.currentThread().isInterrupted()) throw new IOException();
                raw.connect(address, ioTimeout);
                raw.setSoTimeout(ioTimeout);
                if (config.startTls) streams(raw);
                else secure(raw);
                expect(reply(), 220, Code.SERVER_REJECTED);
                Set<String> capabilities = ehlo();
                if (config.startTls) {
                    if (!capabilities.contains("STARTTLS")) throw failure(Code.TLS_REQUIRED);
                    command("STARTTLS");
                    expect(reply(), 220, Code.TLS_FAILED);
                    secure(raw);
                    capabilities = ehlo(); // TLS 前的认证能力不可信，握手后必须重新查询。
                }
                authenticate(capabilities);
                command("MAIL FROM:<" + config.from + ">");
                expect(reply(), 250, Code.SERVER_REJECTED);
                command("RCPT TO:<" + config.to + ">");
                Reply recipient = reply();
                if (recipient.code != 250 && recipient.code != 251) throw failure(Code.RECIPIENT_REJECTED);
                command("DATA");
                expect(reply(), 354, Code.SERVER_REJECTED);
                MimeMessageWriter.write(out, config, body, jpeg);
                // MIME 所有可控正文均为 Base64，不含以点开头的行；终止符永远独立一行。
                markSubmitted();
                command(".");
                Reply accepted = reply();
                if (accepted.code != 250) {
                    if (accepted.code >= 400 && accepted.code <= 599) {
                        rejected = true;
                        throw failure(Code.SERVER_REJECTED);
                    }
                    throw failure(Code.RESULT_UNKNOWN);
                }
                confirmed = true;
                // 邮件的成功由 DATA 后的 250 决定。QUIT 仅尽力发送，不等待其回复。
                try { command("QUIT"); } catch (IOException ignored) {}
            } catch (MailException e) {
                throw uncertain() ? failure(Code.RESULT_UNKNOWN) : e;
            } catch (SSLException e) {
                throw failure(uncertain() ? Code.RESULT_UNKNOWN : Code.TLS_FAILED);
            } catch (SocketTimeoutException e) {
                throw failure(uncertain() ? Code.RESULT_UNKNOWN : Code.TIMEOUT);
            } catch (IOException | RuntimeException e) {
                if (!confirmed) throw failure(uncertain() ? Code.RESULT_UNKNOWN : Code.NETWORK);
            } finally {
                close();
            }
        }

        private synchronized void track(Socket next) throws IOException {
            if (closed) { next.close(); throw new IOException(); }
            if (transport == null) transport = next;
            socket = next;
        }

        private synchronized void markSubmitted() throws IOException {
            if (closed) throw new IOException();
            submitted = true;
        }

        synchronized void close() {
            closed = true;
            // 先关闭底层 TCP，以打断 SSL 写入，避免 close_notify 与超时清理互相等待。
            if (transport != null) try { transport.close(); } catch (IOException ignored) {}
            if (socket != null) try { socket.close(); } catch (IOException ignored) {}
        }

        private void streams(Socket connection) throws IOException {
            in = new BufferedInputStream(connection.getInputStream());
            out = new BufferedOutputStream(connection.getOutputStream(), 16 * 1024);
        }

        private void secure(Socket raw) throws IOException, MailException {
            SSLSocket tls = (SSLSocket) factory.createSocket(raw, config.smtpHost, config.smtpPort, true);
            track(tls);
            tls.setUseClientMode(true);
            tls.setSoTimeout(ioTimeout);
            List<String> protocols = new ArrayList<>();
            for (String protocol : tls.getSupportedProtocols()) {
                if ("TLSv1.2".equals(protocol) || "TLSv1.3".equals(protocol)) protocols.add(protocol);
            }
            if (protocols.isEmpty()) throw failure(Code.TLS_FAILED);
            tls.setEnabledProtocols(protocols.toArray(new String[0]));
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.startHandshake();
            streams(tls);
        }

        private Set<String> ehlo() throws IOException, MailException {
            command("EHLO hyperosautotaskplus.invalid");
            Reply response = reply();
            expect(response, 250, Code.PROTOCOL);
            Set<String> capabilities = new HashSet<>();
            // 第一行是服务器问候，不是扩展声明。
            for (int i = 1; i < response.lines.size(); i++) {
                String line = response.lines.get(i).toUpperCase(Locale.ROOT).trim();
                if (line.equals("STARTTLS")) capabilities.add("STARTTLS");
                if (line.startsWith("AUTH ") || line.startsWith("AUTH=")) {
                    for (String method : line.substring(5).trim().split("\\s+")) capabilities.add("AUTH " + method);
                }
            }
            return capabilities;
        }

        private void authenticate(Set<String> capabilities) throws IOException, MailException {
            String username = config.username == null || config.username.trim().isEmpty() ? config.from : config.username;
            if (capabilities.contains("AUTH PLAIN")) {
                String token = encoded("\0" + username + "\0" + secret);
                command("AUTH PLAIN " + token);
                Reply auth = reply();
                if (auth.code == 334) { command(token); auth = reply(); }
                expect(auth, 235, Code.AUTH_FAILED);
            } else if (capabilities.contains("AUTH LOGIN")) {
                command("AUTH LOGIN");
                expect(reply(), 334, Code.AUTH_FAILED);
                command(encoded(username));
                expect(reply(), 334, Code.AUTH_FAILED);
                command(encoded(secret));
                expect(reply(), 235, Code.AUTH_FAILED);
            } else throw failure(Code.AUTH_UNSUPPORTED);
        }

        private String encoded(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }

        private void command(String command) throws IOException {
            out.write(command.getBytes(StandardCharsets.US_ASCII));
            out.write('\r'); out.write('\n');
            out.flush();
        }

        private Reply reply() throws IOException, MailException {
            List<String> lines = new ArrayList<>();
            int code = -1;
            for (int count = 0; count < MAX_REPLY_LINES; count++) {
                String line = readLine();
                if (line.length() < 4 || line.charAt(0) < '2' || line.charAt(0) > '5'
                        || line.charAt(1) < '0' || line.charAt(1) > '9'
                        || line.charAt(2) < '0' || line.charAt(2) > '9'
                        || (line.charAt(3) != ' ' && line.charAt(3) != '-')) throw failure(Code.PROTOCOL);
                int current = (line.charAt(0) - '0') * 100 + (line.charAt(1) - '0') * 10 + line.charAt(2) - '0';
                if (code != -1 && code != current) throw failure(Code.PROTOCOL);
                code = current;
                lines.add(line.substring(4));
                if (line.charAt(3) == ' ') return new Reply(code, lines);
            }
            throw failure(Code.PROTOCOL);
        }

        private String readLine() throws IOException, MailException {
            StringBuilder result = new StringBuilder();
            while (result.length() <= MAX_REPLY_LINE) {
                int ch = in.read();
                if (ch == -1) throw new EOFException();
                if (ch == '\r') {
                    if (in.read() != '\n') throw failure(Code.PROTOCOL);
                    return result.toString();
                }
                if (ch == '\n' || ch == 0) throw failure(Code.PROTOCOL);
                result.append((char) ch);
            }
            throw failure(Code.PROTOCOL);
        }

        private void expect(Reply response, int code, Code failure) throws MailException {
            if (response.code != code) throw failure(failure);
        }
    }

    private static final class Reply {
        final int code;
        final List<String> lines;
        Reply(int code, List<String> lines) { this.code = code; this.lines = lines; }
    }
}
