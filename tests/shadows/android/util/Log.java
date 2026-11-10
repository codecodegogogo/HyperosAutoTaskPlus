package android.util;

/** JVM 回归测试用的空日志实现，避免 android.jar 的桩方法抛异常。 */
public final class Log {

    private Log() {
    }

    public static int v(String tag, String msg) {
        return 0;
    }

    public static int d(String tag, String msg) {
        return 0;
    }

    public static int i(String tag, String msg) {
        return 0;
    }

    public static int w(String tag, String msg) {
        return 0;
    }

    public static int e(String tag, String msg) {
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        return 0;
    }
}
