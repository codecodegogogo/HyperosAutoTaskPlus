package com.miui.autotask.taskitem;

import java.util.List;

/**
 * 编译桩：安全服务 12.3.5 中 com.miui.autotask.taskitem.LunchAppItem 的签名。
 * 「启动应用 / 离开应用 / 打开应用(结果)」共同的基类，持有所选应用的包名和名称列表。
 * 只参与 javac，不打进 dex。
 */
public abstract class LunchAppItem extends TaskItem {

    /** setAppName */
    public void A(String str) {
        throw new RuntimeException("stub");
    }

    /** setAppNames */
    public void B(List list) {
        throw new RuntimeException("stub");
    }

    /** setPkgName */
    public void D(String str) {
        throw new RuntimeException("stub");
    }

    /** setPkgNames */
    public void E(List list) {
        throw new RuntimeException("stub");
    }

    @Override
    public int b() {
        throw new RuntimeException("stub");
    }

    @Override
    public int c() {
        throw new RuntimeException("stub");
    }

    @Override
    public String g() {
        throw new RuntimeException("stub");
    }

    @Override
    public int i() {
        throw new RuntimeException("stub");
    }

    @Override
    public boolean l() {
        throw new RuntimeException("stub");
    }

    /** 摘要格式串的资源 id */
    protected int t() {
        throw new RuntimeException("stub");
    }

    /** appName，为空时退回 appNames 的第一个 */
    public String u() {
        throw new RuntimeException("stub");
    }

    /** appNames，永不为 null */
    public List v() {
        throw new RuntimeException("stub");
    }

    /** 未选择应用时的占位文案 */
    protected String x() {
        throw new RuntimeException("stub");
    }

    /** pkgName，为空时退回 pkgNames 的第一个 */
    public String y() {
        throw new RuntimeException("stub");
    }

    /** pkgNames，永不为 null */
    public List z() {
        throw new RuntimeException("stub");
    }
}
