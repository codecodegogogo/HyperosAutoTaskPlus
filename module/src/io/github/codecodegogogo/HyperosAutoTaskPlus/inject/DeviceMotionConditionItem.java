package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.miui.autotask.taskitem.TaskItem;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/**
 * 「设备动作」条件：翻转或摇晃。
 *
 * motion 随 Gson 持久化：0 翻转、1 摇晃。两种都是瞬时事件：翻转指正反面掉了个（不分哪面朝上），
 * 事件发生后的几秒窗口内 m() 为 true，没有相反状态，不能作为「退出时恢复」的退出条件。
 * 判定在 {@link SensorRuntime}。
 */
public class DeviceMotionConditionItem extends TaskItem {

    private static final long serialVersionUID = 1L;

    public static final int MOTION_FLIP = 0;
    public static final int MOTION_SHAKE = 1;

    private int motion = MOTION_FLIP;

    @Override
    public String e() {
        return FirstAppKeys.KEY_DEVICE_MOTION_CONDITION;
    }

    @Override
    public String h() {
        return SensorRuntime.motionTitle();
    }

    @Override
    public String g() {
        return SensorRuntime.motionSummary(this);
    }

    @Override
    public int b() {
        return SensorRuntime.motionIcon(1);
    }

    @Override
    public int c() {
        return SensorRuntime.motionIcon(0);
    }

    @Override
    public int i() {
        return SensorRuntime.motionIcon(2);
    }

    @Override
    public boolean l() {
        return motion >= MOTION_FLIP && motion <= MOTION_SHAKE;
    }

    @Override
    public boolean m() {
        return SensorRuntime.evaluate(this);
    }

    public int getMotion() {
        return motion;
    }

    public void setMotion(int value) {
        this.motion = Math.min(Math.max(value, MOTION_FLIP), MOTION_SHAKE);
    }

    public boolean isShake() {
        return motion == MOTION_SHAKE;
    }
}
