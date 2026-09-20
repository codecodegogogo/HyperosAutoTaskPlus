package com.miui.autotask.taskitem;

import java.io.Serializable;

/**
 * 编译桩：安全服务 12.3.5 中 com.miui.autotask.taskitem.TaskItem 的签名。
 * 只参与 javac，不打进 dex，运行时使用安全中心自己的类。
 */
public abstract class TaskItem implements Serializable {

    public int code;

    public boolean a() {
        throw new RuntimeException("stub");
    }

    /** 灰色图标 */
    public abstract int b();

    /** 正常图标 */
    public abstract int c();

    /** instanceId */
    public String d() {
        throw new RuntimeException("stub");
    }

    /** 条件/结果 key，持久化时作为 condition_result_key */
    public abstract String e();

    /** 取安全中心自己的字符串资源 */
    protected String f(int i) {
        throw new RuntimeException("stub");
    }

    /** 任务编辑页里的摘要文案 */
    public abstract String g();

    /** 条件列表里的标题 */
    public abstract String h();

    /** 禁用态图标 */
    public abstract int i();

    /** uuid */
    public String j() {
        throw new RuntimeException("stub");
    }

    /** 是否为退出（恢复）条件，即 code == 4 */
    public boolean k() {
        throw new RuntimeException("stub");
    }

    /** 参数是否完整 */
    public abstract boolean l();

    /** 条件当前是否满足 */
    public boolean m() {
        throw new RuntimeException("stub");
    }

    public void n() {
        throw new RuntimeException("stub");
    }

    public void o() {
        throw new RuntimeException("stub");
    }

    public void p(boolean z) {
        throw new RuntimeException("stub");
    }

    public void q(String str) {
        throw new RuntimeException("stub");
    }

    public void r(String str) {
        throw new RuntimeException("stub");
    }

    /** 设置 uuid */
    public void s(String str) {
        throw new RuntimeException("stub");
    }
}
