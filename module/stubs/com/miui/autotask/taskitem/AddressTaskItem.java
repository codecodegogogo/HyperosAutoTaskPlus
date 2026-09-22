package com.miui.autotask.taskitem;

/**
 * 编译桩：安全服务 12.3.5 中「到达 / 离开某地」条件的基类。
 * 引擎按 uuid 把地理围栏进出状态写进 b2.j.y0()，m() 拿 w() 去比对。
 */
public abstract class AddressTaskItem extends TaskItem {

    public static final int TYPE_IN_RANGE = 1043;
    public static final int TYPE_OUT_RANGE = 1044;

    /** 地址名 */
    public String t() {
        throw new RuntimeException("stub");
    }

    /** 纬度 */
    public double u() {
        throw new RuntimeException("stub");
    }

    /** 经度 */
    public double v() {
        throw new RuntimeException("stub");
    }

    /** TYPE_IN_RANGE / TYPE_OUT_RANGE */
    public abstract int w();

    /** 经纬度都不为 0 */
    @Override
    public boolean l() {
        throw new RuntimeException("stub");
    }

    /** 引擎 y0() 里该 uuid 的围栏状态是否等于 w() */
    @Override
    public boolean m() {
        throw new RuntimeException("stub");
    }

    public void x(String addressName) {
        throw new RuntimeException("stub");
    }

    public void y(String cityName) {
        throw new RuntimeException("stub");
    }

    public void z(double latitude) {
        throw new RuntimeException("stub");
    }

    public void A(double longitude) {
        throw new RuntimeException("stub");
    }

    public void B(String provinceName) {
        throw new RuntimeException("stub");
    }
}
