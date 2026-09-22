package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.miui.autotask.taskitem.ToSomewhereConditionItem;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/**
 * 「到达地理围栏」条件：就是原生「到达某地」加一个可自定义的围栏半径（米）。
 *
 * 经纬度 / 地址名等字段全部继承自 AddressTaskItem，地址选择页、引擎注册围栏、
 * 围栏进出回调都按 AddressTaskItem 通用处理；只有 GeofenceManager 建围栏时那句
 * setRadius(500) 由 hook 侧换成这里的 radius。
 */
public class GeofenceEnterConditionItem extends ToSomewhereConditionItem {

    private static final long serialVersionUID = 1L;

    private int radius = GeofenceRuntime.DEFAULT_RADIUS;

    @Override
    public String e() {
        return FirstAppKeys.KEY_GEOFENCE_ENTER_CONDITION;
    }

    @Override
    public String h() {
        return GeofenceRuntime.title(true);
    }

    @Override
    public String g() {
        return GeofenceRuntime.summary(this, true);
    }

    public int getRadius() {
        return GeofenceRuntime.clampRadius(radius);
    }

    public void setRadius(int meters) {
        this.radius = GeofenceRuntime.clampRadius(meters);
    }
}
