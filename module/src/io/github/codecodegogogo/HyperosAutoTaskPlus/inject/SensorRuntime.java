package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import com.miui.autotask.taskitem.TaskItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/**
 * 「设备动作」与「光线」两个传感器条件的运行时。
 *
 * 只在有已启用的对应条件项时才注册传感器监听（加速度计 / 光线），全部停用后立即注销，
 * 不常驻耗电。优先拿 wake-up 版本的传感器，息屏时也能收到事件。
 *
 * 翻转：加速度计 z 轴 < -7 视为正面朝下，> 7 视为正面朝上，中间区域保持上一状态；
 *       朝向从一面切到另一面就算一次「翻转」（不分哪面朝上），之后 {@link #EVENT_WINDOW_MILLIS} 内 m() 为 true。
 * 摇晃：合加速度超过 2.7g 记一次，500ms 内连续 3 次视为一次摇晃，2 秒内不重复计；
 *       摇晃后 {@link #EVENT_WINDOW_MILLIS} 内 m() 为 true。
 * 光线：每次读数按各条件项算一次「是否满足」，与上次结果不同才通知引擎。
 */
public final class SensorRuntime {

    private static final String TAG = "HyperAutoEnh";

    private static final float FACE_THRESHOLD = 7f;
    private static final float SHAKE_G = 2.7f;
    private static final long SHAKE_SLOP_MILLIS = 500L;
    private static final int SHAKE_COUNT = 3;
    private static final long SHAKE_COOLDOWN_MILLIS = 2_000L;
    private static final long EVENT_WINDOW_MILLIS = 5_000L;

    private static final Object LOCK = new Object();
    private static final ConcurrentHashMap<String, DeviceMotionConditionItem> MOTION_ITEMS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LightConditionItem> LIGHT_ITEMS = new ConcurrentHashMap<>();
    /** 光线条件项上次判定结果，用来只在变化时通知 */
    private static final Map<String, Boolean> LIGHT_LAST = new ConcurrentHashMap<>();

    private static volatile boolean sStarted;
    private static SensorManager sSensors;
    private static Sensor sAccel;
    private static Sensor sLight;
    private static boolean sAccelOn;
    private static boolean sLightOn;

    /** null = 还不知道朝向 */
    private static volatile Boolean sFaceDown;
    private static volatile long sFlipAt = -1;
    private static volatile long sShakeAt = -1;
    private static int sShakeCount;
    private static long sShakeSampleAt;
    private static volatile float sLux = -1f;

    private static volatile int[] sMotionIcons;
    private static volatile int[] sLightIcons;

    private SensorRuntime() {
    }

    // ------------------------------------------------------------------ 给 hook 侧用的入口

    public static Class<?> itemClass(String key) {
        if (FirstAppKeys.KEY_DEVICE_MOTION_CONDITION.equals(key)) {
            return DeviceMotionConditionItem.class;
        }
        if (FirstAppKeys.KEY_LIGHT_CONDITION.equals(key)) {
            return LightConditionItem.class;
        }
        return null;
    }

    public static Object newItem(String key) {
        if (FirstAppKeys.KEY_DEVICE_MOTION_CONDITION.equals(key)) {
            return new DeviceMotionConditionItem();
        }
        if (FirstAppKeys.KEY_LIGHT_CONDITION.equals(key)) {
            return new LightConditionItem();
        }
        return null;
    }

    public static boolean isItem(Object item) {
        return item instanceof DeviceMotionConditionItem || item instanceof LightConditionItem;
    }

    /** 设备动作是瞬时事件，没有相反状态；光线有 */
    public static Object opposite(Object item) {
        if (item instanceof LightConditionItem) {
            return ((LightConditionItem) item).opposite();
        }
        return null;
    }

    public static int syncOpposite(Object item, List list) {
        if (list == null || !(item instanceof LightConditionItem)) {
            return -1;
        }
        LightConditionItem src = (LightConditionItem) item;
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (o instanceof LightConditionItem) {
                ((LightConditionItem) o).set(!src.isAbove(), src.getLux());
                return i;
            }
        }
        return -1;
    }

    public static void register(Object item) {
        if (!(item instanceof TaskItem)) {
            return;
        }
        String uuid = ((TaskItem) item).j();
        if (TextUtils.isEmpty(uuid)) {
            return;
        }
        if (item instanceof DeviceMotionConditionItem) {
            MOTION_ITEMS.put(uuid, (DeviceMotionConditionItem) item);
            Log.i(TAG, "register motion " + ((DeviceMotionConditionItem) item).getMotion() + " uuid=" + uuid);
        } else if (item instanceof LightConditionItem) {
            LIGHT_ITEMS.put(uuid, (LightConditionItem) item);
            LIGHT_LAST.remove(uuid);
            Log.i(TAG, "register light uuid=" + uuid);
        } else {
            return;
        }
        syncListeners();
    }

    public static void unregister(String uuid) {
        if (uuid == null) {
            return;
        }
        boolean removed = MOTION_ITEMS.remove(uuid) != null;
        removed |= LIGHT_ITEMS.remove(uuid) != null;
        LIGHT_LAST.remove(uuid);
        if (removed) {
            Log.i(TAG, "unregister sensor uuid=" + uuid);
            syncListeners();
        }
    }

    public static void start(Context context, Object engine) {
        synchronized (LOCK) {
            if (sStarted) {
                return;
            }
            sStarted = true;
        }
        try {
            Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            EngineBridge.attach(engine);
            sSensors = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
            if (sSensors == null) {
                Log.e(TAG, "no SensorManager");
                return;
            }
            sAccel = pickSensor(Sensor.TYPE_ACCELEROMETER);
            sLight = pickSensor(Sensor.TYPE_LIGHT);
            Log.i(TAG, "sensor runtime started, accel=" + (sAccel != null) + " light=" + (sLight != null));
            syncListeners();
        } catch (Throwable t) {
            Log.e(TAG, "start sensor runtime failed", t);
        }
    }

    // ------------------------------------------------------------------ 判定

    static boolean evaluate(DeviceMotionConditionItem item) {
        long at = item.isShake() ? sShakeAt : sFlipAt;
        return at >= 0 && SystemClock.elapsedRealtime() - at <= EVENT_WINDOW_MILLIS;
    }

    static boolean evaluate(LightConditionItem item) {
        float lux = sLux;
        if (lux < 0) {
            return false;
        }
        return item.isAbove() ? lux > item.getLux() : lux < item.getLux();
    }

    // ------------------------------------------------------------------ 传感器

    private static Sensor pickSensor(int type) {
        // API 21 起有 getDefaultSensor(int, boolean wakeUp)，旧 android.jar 没有，反射
        try {
            Object s = SensorManager.class.getMethod("getDefaultSensor", int.class, boolean.class)
                    .invoke(sSensors, type, true);
            if (s instanceof Sensor) {
                return (Sensor) s;
            }
        } catch (Throwable ignored) {
            // 退回普通版本
        }
        return sSensors.getDefaultSensor(type);
    }

    private static void syncListeners() {
        if (!sStarted || sSensors == null) {
            return;
        }
        synchronized (LOCK) {
            boolean wantAccel = !MOTION_ITEMS.isEmpty() && sAccel != null;
            boolean wantLight = !LIGHT_ITEMS.isEmpty() && sLight != null;
            if (wantAccel && !sAccelOn) {
                sAccelOn = sSensors.registerListener(ACCEL_LISTENER, sAccel, SensorManager.SENSOR_DELAY_UI);
                sFaceDown = null;
                sShakeCount = 0;
                Log.i(TAG, "accelerometer listener on=" + sAccelOn);
            } else if (!wantAccel && sAccelOn) {
                sSensors.unregisterListener(ACCEL_LISTENER);
                sAccelOn = false;
                sFaceDown = null;
                Log.i(TAG, "accelerometer listener off");
            }
            if (wantLight && !sLightOn) {
                sLightOn = sSensors.registerListener(LIGHT_LISTENER, sLight, SensorManager.SENSOR_DELAY_NORMAL);
                sLux = -1f;
                Log.i(TAG, "light listener on=" + sLightOn);
            } else if (!wantLight && sLightOn) {
                sSensors.unregisterListener(LIGHT_LISTENER);
                sLightOn = false;
                sLux = -1f;
                Log.i(TAG, "light listener off");
            }
        }
    }

    private static final SensorEventListener ACCEL_LISTENER = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null || event.values == null || event.values.length < 3) {
                return;
            }
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            onFace(z);
            onShakeSample(x, y, z);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private static final SensorEventListener LIGHT_LISTENER = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null || event.values == null || event.values.length < 1) {
                return;
            }
            onLux(event.values[0]);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private static void onFace(float z) {
        Boolean now;
        if (z < -FACE_THRESHOLD) {
            now = Boolean.TRUE;
        } else if (z > FACE_THRESHOLD) {
            now = Boolean.FALSE;
        } else {
            return;
        }
        Boolean before = sFaceDown;
        if (before != null && before.booleanValue() == now.booleanValue()) {
            return;
        }
        sFaceDown = now;
        if (before == null) {
            // 刚开始监听时的第一次读数只是初始化朝向，不当作「翻转」事件
            return;
        }
        sFlipAt = SystemClock.elapsedRealtime();
        Map<String, TaskItem> hit = new HashMap<>();
        for (Map.Entry<String, DeviceMotionConditionItem> e : MOTION_ITEMS.entrySet()) {
            if (!e.getValue().isShake()) {
                hit.put(e.getKey(), e.getValue());
            }
        }
        EngineBridge.notify(hit, "flip");
    }

    private static void onShakeSample(float x, float y, float z) {
        double g = Math.sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH;
        if (g < SHAKE_G) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (now - sShakeSampleAt > SHAKE_SLOP_MILLIS) {
                sShakeCount = 0;
            }
            sShakeSampleAt = now;
            sShakeCount++;
            if (sShakeCount < SHAKE_COUNT || (sShakeAt >= 0 && now - sShakeAt < SHAKE_COOLDOWN_MILLIS)) {
                return;
            }
            sShakeCount = 0;
            sShakeAt = now;
        }
        Map<String, TaskItem> hit = new HashMap<>();
        for (Map.Entry<String, DeviceMotionConditionItem> e : MOTION_ITEMS.entrySet()) {
            if (e.getValue().isShake()) {
                hit.put(e.getKey(), e.getValue());
            }
        }
        EngineBridge.notify(hit, "shake");
    }

    private static void onLux(float lux) {
        sLux = Math.max(0f, lux);
        Map<String, TaskItem> hit = new HashMap<>();
        for (Map.Entry<String, LightConditionItem> e : LIGHT_ITEMS.entrySet()) {
            boolean ok = evaluate(e.getValue());
            Boolean last = LIGHT_LAST.put(e.getKey(), ok);
            if (last == null || last != ok) {
                hit.put(e.getKey(), e.getValue());
            }
        }
        EngineBridge.notify(hit, "light " + (int) lux + " lux");
    }

    // ------------------------------------------------------------------ 编辑对话框

    /** 「设备动作」：翻转 / 摇晃两张卡片，无输入 */
    public static void pickMotionAndApply(final Context context, Object itemObj, final Runnable onConfirm) {
        if (context == null || !(itemObj instanceof DeviceMotionConditionItem)) {
            return;
        }
        final DeviceMotionConditionItem item = (DeviceMotionConditionItem) itemObj;
        List<CardPicker.Spec> specs = new ArrayList<>();
        String[] names = motionNames();
        for (String n : names) {
            specs.add(new CardPicker.Spec(n));
        }
        CardPicker.show(context, motionTitle(), specs, item.getMotion(), new CardPicker.OnPicked() {
            @Override
            public void onPicked(int index, int amount, int unit) {
                item.setMotion(index);
                if (onConfirm != null) {
                    onConfirm.run();
                }
            }
        });
    }

    /** 「光线」：暗于 / 亮于两张卡片，各带 lux 输入 */
    public static void pickLightAndApply(final Context context, Object itemObj, final Runnable onConfirm) {
        if (context == null || !(itemObj instanceof LightConditionItem)) {
            return;
        }
        final LightConditionItem item = (LightConditionItem) itemObj;
        List<CardPicker.Spec> specs = new ArrayList<>();
        String[] unit = {"lux"};
        specs.add(new CardPicker.Spec(InjectUi.zh() ? "光线暗于" : "Darker than", unit, item.getLux(), 0));
        specs.add(new CardPicker.Spec(InjectUi.zh() ? "光线亮于" : "Brighter than", unit, item.getLux(), 0));
        CardPicker.show(context, lightTitle(), specs, item.isAbove() ? 1 : 0, new CardPicker.OnPicked() {
            @Override
            public void onPicked(int index, int amount, int unit) {
                item.set(index == 1, amount);
                if (onConfirm != null) {
                    onConfirm.run();
                }
            }
        });
    }

    /** hook 侧统一入口：按类型分发到对应对话框 */
    public static void pickAndApply(Context context, Object item, Runnable onConfirm) {
        if (item instanceof DeviceMotionConditionItem) {
            pickMotionAndApply(context, item, onConfirm);
        } else if (item instanceof LightConditionItem) {
            pickLightAndApply(context, item, onConfirm);
        }
    }

    // ------------------------------------------------------------------ 文案 / 图标

    static String motionTitle() {
        return InjectUi.zh() ? "设备动作" : "Device motion";
    }

    private static String[] motionNames() {
        return InjectUi.zh()
                ? new String[]{"翻转", "摇晃"}
                : new String[]{"Flip", "Shake"};
    }

    static String motionSummary(DeviceMotionConditionItem item) {
        String[] names = motionNames();
        return names[Math.min(Math.max(item.getMotion(), 0), names.length - 1)];
    }

    static String lightTitle() {
        return InjectUi.zh() ? "光线" : "Ambient light";
    }

    static String lightSummary(LightConditionItem item) {
        if (InjectUi.zh()) {
            return (item.isAbove() ? "光线亮于 " : "光线暗于 ") + item.getLux() + " lux";
        }
        return (item.isAbove() ? "Brighter than " : "Darker than ") + item.getLux() + " lux";
    }

    static int motionIcon(int which) {
        int[] icons = sMotionIcons;
        if (icons == null) {
            icons = InjectUi.icons("auto_task_icon_rotate_off", android.R.drawable.ic_menu_rotate);
            if (InjectUi.context() != null) {
                sMotionIcons = icons;
            }
        }
        return icons[Math.min(Math.max(which, 0), 2)];
    }

    static int lightIcon(int which) {
        int[] icons = sLightIcons;
        if (icons == null) {
            icons = InjectUi.icons("auto_task_icon_brightness", android.R.drawable.ic_menu_day);
            if (InjectUi.context() != null) {
                sLightIcons = icons;
            }
        }
        return icons[Math.min(Math.max(which, 0), 2)];
    }
}
