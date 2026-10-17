package com.miui.autotask.taskitem;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

/** JVM-only native persistence fixture; never packaged in the APK. */
public abstract class TaskItem implements Serializable {
    @SerializedName("b") public int code;
    @SerializedName("a") private String uuid;
    private String instanceId = "0";
    private String taskChannel = "sc";
    public boolean a() { return true; }
    public abstract int b();
    public abstract int c();
    public String d() { return instanceId; }
    public abstract String e();
    public abstract String g();
    public abstract String h();
    public abstract int i();
    public String j() { return uuid; }
    public boolean k() { return code == 4; }
    public abstract boolean l();
    public boolean m() { return false; }
    public void n() { throw new AssertionError("Unexpected action execution"); }
    public void o() { throw new AssertionError("Unexpected action restoration"); }
    public void p(boolean checked) { code = checked ? 4 : 0; }
    public void q(String value) { instanceId = value; }
    public void r(String value) { taskChannel = value; }
    public void s(String value) { uuid = value; }
}
