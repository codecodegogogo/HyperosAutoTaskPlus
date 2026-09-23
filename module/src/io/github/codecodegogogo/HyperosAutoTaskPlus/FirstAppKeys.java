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

    /** 设备操作结果：参数和其它 TaskItem 一样随任务保存。 */
    public static final String KEY_AUDIO_RECORD_RESULT = "key_audio_record_result_item";
    public static final String KEY_SCREEN_RECORD_RESULT = "key_screen_record_result_item";
    public static final String KEY_SCREENSHOT_RESULT = "key_screenshot_result_item";
    public static final String KEY_PHOTO_RESULT = "key_photo_result_item";
    public static final String KEY_CALL_RESULT = "key_call_result_item";
    public static final String KEY_SMS_RESULT = "key_sms_result_item";
    public static final String KEY_EMAIL_RESULT = "key_email_result_item";
    public static final String KEY_PLAY_AUDIO_RESULT = "key_play_audio_result_item";
    public static final String KEY_NOTIFICATION_RESULT = "key_notification_result_item";
    public static final String CATEGORY_RESULT_FUNCTION = "key_function_result_category";

    /** 屏幕状态条件：亮屏 / 息屏持续了指定时长 */
    public static final String KEY_SCREEN_STATE_CONDITION = "key_screen_state_condition_item";
    /** 原生「锁屏」条件，屏幕状态紧跟在它之后 */
    public static final String KEY_LOCK_SCREEN_CONDITION = "key_lock_screen_condition_item";

    /** 当前设备用户连续解锁失败达到指定次数，成功解锁后清零。 */
    public static final String KEY_UNLOCK_FAILURE_CONDITION = "key_unlock_failure_condition_item";

    /** 时间间隔条件：每隔 N 秒/分钟/小时/天触发一次 */
    public static final String KEY_INTERVAL_CONDITION = "key_interval_condition_item";
    /** 原生「自定义时间」条件，时间间隔紧跟在它之后 */
    public static final String KEY_CUSTOM_TIME_CONDITION = "key_custom_time_condition_item";

    /** 到达 / 离开地理围栏：原生「到达 / 离开某地」加可自定义的半径 */
    public static final String KEY_GEOFENCE_ENTER_CONDITION = "key_geofence_enter_condition_item";
    public static final String KEY_GEOFENCE_LEAVE_CONDITION = "key_geofence_leave_condition_item";
    /** 原生「到达某地 / 离开某地」条件，地理围栏紧跟在「离开某地」之后 */
    public static final String KEY_TO_SOMEWHERE_CONDITION = "key_to_somewhere_condition_item";
    public static final String KEY_LEAVE_SOMEWHERE_CONDITION = "key_leave_condition_item";

    /** 传感器：设备动作（翻转 / 摇晃）与光线 */
    public static final String KEY_DEVICE_MOTION_CONDITION = "key_device_motion_condition_item";
    public static final String KEY_LIGHT_CONDITION = "key_light_sensor_condition_item";

    /** AddConditionFragment 里「情境」分类的 preference key，自定义时间 / 到达某地在这一组 */
    public static final String CATEGORY_SITUATION = "key_situation_condition_category";
    /** 「通信」分类，新加的「传感器」分类排在它前面 */
    public static final String CATEGORY_COMMUNICATION = "key_comminication_condition_category";
    /** 模块新增的「传感器」分类 */
    public static final String CATEGORY_SENSOR = "key_sensor_condition_category";

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

    public static boolean isDeviceActionKey(String key) {
        return KEY_AUDIO_RECORD_RESULT.equals(key) || KEY_SCREEN_RECORD_RESULT.equals(key)
                || KEY_SCREENSHOT_RESULT.equals(key) || KEY_PHOTO_RESULT.equals(key)
                || KEY_CALL_RESULT.equals(key) || KEY_SMS_RESULT.equals(key) || KEY_EMAIL_RESULT.equals(key)
                || KEY_PLAY_AUDIO_RESULT.equals(key) || KEY_NOTIFICATION_RESULT.equals(key);
    }

    public static boolean isScreenStateKey(String key) {
        return KEY_SCREEN_STATE_CONDITION.equals(key);
    }

    public static boolean isUnlockFailureKey(String key) {
        return KEY_UNLOCK_FAILURE_CONDITION.equals(key);
    }

    public static boolean isIntervalKey(String key) {
        return KEY_INTERVAL_CONDITION.equals(key);
    }

    public static boolean isGeofenceKey(String key) {
        return KEY_GEOFENCE_ENTER_CONDITION.equals(key) || KEY_GEOFENCE_LEAVE_CONDITION.equals(key);
    }

    public static boolean isSensorKey(String key) {
        return KEY_DEVICE_MOTION_CONDITION.equals(key) || KEY_LIGHT_CONDITION.equals(key);
    }

    public static String opposite(String key) {
        if (KEY_FIRST_START.equals(key)) {
            return KEY_FIRST_LEAVE;
        }
        if (KEY_FIRST_LEAVE.equals(key)) {
            return KEY_FIRST_START;
        }
        if (KEY_GEOFENCE_ENTER_CONDITION.equals(key)) {
            return KEY_GEOFENCE_LEAVE_CONDITION;
        }
        if (KEY_GEOFENCE_LEAVE_CONDITION.equals(key)) {
            return KEY_GEOFENCE_ENTER_CONDITION;
        }
        return key;
    }
}
