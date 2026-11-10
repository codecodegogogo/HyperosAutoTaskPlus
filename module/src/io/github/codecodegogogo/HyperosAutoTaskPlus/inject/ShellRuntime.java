package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/**
 * 执行用户填写的 Shell 脚本，可选择普通应用身份或 root 身份。
 *
 * 普通应用身份直接使用安全服务自身的权限执行，不需要 root；root 身份通过 root 管理器的 su 提权。
 * 添加和编辑时不探测、不索要授权，只有任务真正触发时才调用 su，未授权时由系统弹出请求。
 * 独立线程执行，避免超时等待阻塞其它设备操作。
 */
final class ShellRuntime {
    private static final String TAG = "HyperAutoEnh";
    private static final int MAX_OUTPUT = 8192;
    private static final long TIMEOUT_MILLIS = 20_000L;

    private ShellRuntime() {}

    static void run(Context context, String script, boolean asRoot) {
        final Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> execute(app, script, asRoot), "hyper_auto_shell");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 用户在说明弹窗点「知道了」时调用，主动向 root 管理器发起一次授权请求。
     * 只执行固定的 id 命令，不执行用户脚本；授权结果用轻提示告知。
     */
    static void requestRoot(Context context) {
        final Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            Process process = null;
            try {
                process = new ProcessBuilder(findSu(), "-c", "id")
                        .redirectErrorStream(true).start();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                final Process target = process;
                Thread reader = new Thread(() -> drain(target, output), "hyper_auto_shell_auth");
                reader.setDaemon(true);
                reader.start();
                Integer exit = waitFor(process, 15_000L);
                if (exit == null) {
                    process.destroy();
                    DeviceActionRuntime.notice(app, DeviceActionRuntime.text(
                            "等待 root 授权超时，请到 root 管理器中允许安全服务",
                            "Root authorization timed out; allow the security service in your root manager"));
                } else if (exit != 0) {
                    DeviceActionRuntime.notice(app, DeviceActionRuntime.text(
                            "未获得 root 权限，命令执行时会再次请求",
                            "Root permission not granted; it will be requested again when the command runs"));
                } else {
                    DeviceActionRuntime.notice(app, DeviceActionRuntime.text(
                            "已获得 root 权限", "Root permission granted"));
                }
                reader.join(300);
            } catch (Throwable t) {
                Log.e(TAG, "request root failed", t);
                DeviceActionRuntime.notice(app, DeviceActionRuntime.text(
                        "未获得 root 权限，请到 root 管理器中允许安全服务",
                        "Root permission not granted; allow the security service in your root manager"));
            } finally {
                if (process != null) process.destroy();
            }
        }, "hyper_auto_shell_auth");
        thread.setDaemon(true);
        thread.start();
    }

    private static void execute(Context context, String script, boolean asRoot) {
        Process process = null;
        try {
            process = new ProcessBuilder(asRoot ? findSu() : findShell(), "-c", script)
                    .redirectErrorStream(true).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            final Process target = process;
            Thread reader = new Thread(() -> drain(target, output), "hyper_auto_shell_reader");
            reader.setDaemon(true);
            reader.start();

            Integer exit = waitFor(process, TIMEOUT_MILLIS);
            if (exit == null) process.destroy();
            reader.join(800);
            String result = summary(output);
            if (exit == null) {
                Log.w(TAG, "shell timeout: " + output);
                DeviceActionRuntime.notice(context, DeviceActionRuntime.text(
                        "命令超时：" + result, "Command timed out: " + result));
            } else if (exit != 0) {
                Log.w(TAG, "shell exit " + exit + ": " + output);
                DeviceActionRuntime.notice(context, DeviceActionRuntime.text(
                        "命令失败（" + exit + "）：" + result,
                        "Command failed (" + exit + "): " + result));
            } else {
                Log.i(TAG, "shell ok: " + output);
                DeviceActionRuntime.notice(context, DeviceActionRuntime.text(
                        "命令完成：" + result, "Command finished: " + result));
            }
        } catch (Throwable t) {
            Log.e(TAG, "shell failed", t);
            String message = t.getMessage();
            if (message == null || message.trim().isEmpty()) message = t.toString();
            DeviceActionRuntime.notice(context, DeviceActionRuntime.text(
                    "命令执行失败：" + summary(message), "Command failed: " + summary(message)));
        } finally {
            if (process != null) process.destroy();
        }
    }

    /** Toast 放不下长文本，保留开头并标明截断。 */
    private static String summary(ByteArrayOutputStream output) {
        return summary(output.toString());
    }

    private static String summary(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return DeviceActionRuntime.text("无输出", "no output");
        }
        if (text.codePointCount(0, text.length()) > 300) {
            text = text.substring(0, text.offsetByCodePoints(0, 300)) + "…";
        }
        return text;
    }

    private static void drain(Process process, ByteArrayOutputStream output) {
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) > 0) {
                if (output.size() < MAX_OUTPUT) output.write(buffer, 0, count);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 编译用的 android.jar 是 API 16，没有带超时的 waitFor，这里轮询 exitValue。 */
    private static Integer waitFor(Process process, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException running) {
                Thread.sleep(150);
            }
        }
        return null;
    }

    /** 不同 root 方案的 su 位置不同；都没有时交给 PATH 查找。 */
    private static String findSu() {
        String[] candidates = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su"};
        for (String path : candidates) {
            try {
                if (new File(path).canExecute()) return path;
            } catch (Throwable ignored) {
            }
        }
        return "su";
    }

    /** 普通应用身份直接调用系统 shell，不经过 su。 */
    private static String findShell() {
        String[] candidates = {"/system/bin/sh", "/system/xbin/sh", "/bin/sh"};
        for (String path : candidates) {
            try {
                if (new File(path).canExecute()) return path;
            } catch (Throwable ignored) {
            }
        }
        return "sh";
    }
}
