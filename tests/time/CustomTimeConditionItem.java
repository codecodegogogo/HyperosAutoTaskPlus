package com.miui.autotask.taskitem;

import com.google.gson.annotations.SerializedName;

/** Mirrors native serialized fields while keeping Android clocks out of the tests. */
public class CustomTimeConditionItem extends TaskItem {
    @SerializedName("f") private int endTime;
    @SerializedName("d") private int excludeTime;
    @SerializedName("c") private Object repeat;
    @SerializedName("e") private int startTime;
    @SerializedName("g") private int timeType;
    public static boolean nativeMatches;
    public int u() { return endTime; }
    public int y() { return startTime; }
    public int z() { return timeType; }
    public void A(int value) { endTime = value; }
    public void E(int value) { startTime = value; }
    public void F(int value) { timeType = value; }
    @Override public String e() { return "key_custom_time_condition_item"; }
    @Override public String g() { return "native repeat"; }
    @Override public String h() { return "native time window"; }
    @Override public int b() { return 1; }
    @Override public int c() { return 2; }
    @Override public int i() { return 3; }
    @Override public boolean l() { return (timeType == 1 || timeType == 2) && repeat != null; }
    @Override public boolean m() { return nativeMatches; }
}
