package io.github.codecodegogogo.HyperosAutoTaskPlus;

/**
 * SystemUI 与安全服务之间共享的数据格式，不含 Android/Xposed 依赖。
 * 只保存连续失败次数和成功解锁序号，不记录凭据或生物特征。
 */
public final class UnlockFailureState {
    public static final String SETTING_KEY = "hyper_auto_unlock_failure_state";
    public final int count;
    public final long unlockSerial;

    public UnlockFailureState(int count, long unlockSerial) {
        if (count < 0 || unlockSerial < 0) throw new IllegalArgumentException("无效解锁统计");
        this.count = count;
        this.unlockSerial = unlockSerial;
    }

    public UnlockFailureState failed() {
        return new UnlockFailureState(count == Integer.MAX_VALUE ? count : count + 1, unlockSerial);
    }

    public UnlockFailureState unlocked() {
        return new UnlockFailureState(0, unlockSerial == Long.MAX_VALUE ? unlockSerial : unlockSerial + 1);
    }

    public boolean matches(int threshold, boolean resetCondition) {
        if (threshold < 1 || threshold > 999) return false;
        return resetCondition ? count == 0 && unlockSerial > 0 : count >= threshold;
    }

    public String encode() { return "1|" + count + "|" + unlockSerial; }

    /** 缺失、损坏或来自未知版本的数据返回 null，不能据此触发/恢复任务。 */
    public static UnlockFailureState decode(String value) {
        if (value == null) return null;
        String[] fields = value.split("\\|", -1);
        if (fields.length != 3 || !"1".equals(fields[0])) return null;
        try {
            return new UnlockFailureState(Integer.parseInt(fields[1]), Long.parseLong(fields[2]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 工作资料、应用锁等认证不能混入当前设备用户的锁屏统计。 */
    public static boolean shouldCount(boolean keyguardLocked, int currentUser, int attemptedUser) {
        return keyguardLocked && currentUser >= 0 && currentUser == attemptedUser;
    }
}
