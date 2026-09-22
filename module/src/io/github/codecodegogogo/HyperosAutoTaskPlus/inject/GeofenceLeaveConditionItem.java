package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.miui.autotask.taskitem.LeaveConditionItem;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/** 「离开地理围栏」条件：原生「离开某地」加可自定义半径，见 {@link GeofenceEnterConditionItem} */
public class GeofenceLeaveConditionItem extends LeaveConditionItem {

    private static final long serialVersionUID = 1L;

    private int radius = GeofenceRuntime.DEFAULT_RADIUS;

    @Override
    public String e() {
        return FirstAppKeys.KEY_GEOFENCE_LEAVE_CONDITION;
    }

    @Override
    public String h() {
        return GeofenceRuntime.title(false);
    }

    @Override
    public String g() {
        return GeofenceRuntime.summary(this, false);
    }

    public int getRadius() {
        return GeofenceRuntime.clampRadius(radius);
    }

    public void setRadius(int meters) {
        this.radius = GeofenceRuntime.clampRadius(meters);
    }
}
