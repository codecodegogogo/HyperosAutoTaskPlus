package io.github.codecodegogogo.HyperosAutoTaskPlus;

/**
 * 模块自定义条件项的 key。安全中心用 key 作为持久化时的 condition_result_key，
 * 也用它在各处 switch 分发，所以格式沿用 key_xxx_condition_item。
 *
 * 只含编译期常量，hook 侧和注入到安全中心 ClassLoader 的那一侧都能直接引用，
 * javac 会内联，不会产生跨 ClassLoader 的类依赖。
 */
public final class FirstAppKeys {

    public static final String KEY_START_ACTIVITY_CONDITION = "key_start_activity_condition_item";
    public static final String KEY_LEAVE_ACTIVITY_CONDITION = "key_leave_activity_condition_item";
    public static final String KEY_START_ACTIVITY_RESULT = "key_start_activity_result_item";

    /** 首次启动应用：应用进程从无到有（被加载进内存） */
    public static final String KEY_FIRST_START = "key_first_start_activity_condition_item";
    /** 首次离开应用：应用所有进程都被清出内存 */
    public static final String KEY_FIRST_LEAVE = "key_first_leave_activity_condition_item";

    /** 隐身模式结果：开启/关闭权限中心的隐身模式（拒绝所有应用录音、定位和拍照） */
    public static final String KEY_INVISIBLE_MODE_RESULT = "key_invisible_mode_result_item";
    /** 原生「定位」结果，隐身模式紧跟在它之后 */
    public static final String KEY_LOCATION_RESULT = "key_location_result_item";

    /** 屏幕状态条件：亮屏 / 息屏持续了指定时长 */
    public static final String KEY_SCREEN_STATE_CONDITION = "key_screen_state_condition_item";
    /** 原生「锁屏」条件，屏幕状态紧跟在它之后 */
    public static final String KEY_LOCK_SCREEN_CONDITION = "key_lock_screen_condition_item";

    /** AddConditionFragment 里「事件」分类的 preference key，原「启动应用/离开应用」就在这一组 */
    public static final String CATEGORY_EVENT = "key_event_condition_category";
    /** AddResultFragment 里「设置项」分类的 preference key（蓝牙/WLAN/飞行/定位/热点……） */
    public static final String CATEGORY_RESULT_SETTING = "key_setting_item_result_category";

    private FirstAppKeys() {
    }

    public static boolean isFirstAppKey(String key) {
        return KEY_FIRST_START.equals(key) || KEY_FIRST_LEAVE.equals(key);
    }

    public static boolean isInvisibleModeKey(String key) {
        return KEY_INVISIBLE_MODE_RESULT.equals(key);
    }

    public static boolean isScreenStateKey(String key) {
        return KEY_SCREEN_STATE_CONDITION.equals(key);
    }

    public static String opposite(String key) {
        if (KEY_FIRST_START.equals(key)) {
            return KEY_FIRST_LEAVE;
        }
        if (KEY_FIRST_LEAVE.equals(key)) {
            return KEY_FIRST_START;
        }
        return key;
    }
}
