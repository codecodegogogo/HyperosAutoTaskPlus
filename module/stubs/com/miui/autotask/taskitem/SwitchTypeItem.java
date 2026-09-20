package com.miui.autotask.taskitem;

/**
 * 编译桩：安全服务 12.3.5 中 com.miui.autotask.taskitem.SwitchTypeItem 的签名。
 * 所有「开启/关闭」型结果项（WLAN、定位、勿扰……）的共同基类，
 * 真实类里有一个 @SerializedName("c") 的 boolean switchValue，随 Gson 一起持久化。
 */
public abstract class SwitchTypeItem extends TaskItem {

    @Override
    public boolean l() {
        throw new RuntimeException("stub");
    }

    /** 开关值：true = 开启 */
    public boolean t() {
        throw new RuntimeException("stub");
    }

    /** 单选框索引：0 = 开启，1 = 关闭 */
    public int u() {
        throw new RuntimeException("stub");
    }

    public void v(boolean z) {
        throw new RuntimeException("stub");
    }
}
