package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;

import com.miui.autotask.taskitem.AddressTaskItem;

import java.util.ArrayList;
import java.util.List;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/**
 * 「到达 / 离开地理围栏」的注入侧入口：造实例、读写半径、弹半径对话框。
 * 围栏本身的注册 / 进出判定完全复用原生 AddressTaskItem 那套，hook 侧只改半径。
 */
public final class GeofenceRuntime {

    public static final int DEFAULT_RADIUS = 500;
    public static final int MIN_RADIUS = 50;
    public static final int MAX_RADIUS = 50_000;

    private GeofenceRuntime() {
    }

    public static Class<?> itemClass(String key) {
        if (FirstAppKeys.KEY_GEOFENCE_ENTER_CONDITION.equals(key)) {
            return GeofenceEnterConditionItem.class;
        }
        if (FirstAppKeys.KEY_GEOFENCE_LEAVE_CONDITION.equals(key)) {
            return GeofenceLeaveConditionItem.class;
        }
        return null;
    }

    public static Object newItem(String key) {
        if (FirstAppKeys.KEY_GEOFENCE_ENTER_CONDITION.equals(key)) {
            return new GeofenceEnterConditionItem();
        }
        if (FirstAppKeys.KEY_GEOFENCE_LEAVE_CONDITION.equals(key)) {
            return new GeofenceLeaveConditionItem();
        }
        return null;
    }

    public static boolean isItem(Object item) {
        return item instanceof GeofenceEnterConditionItem || item instanceof GeofenceLeaveConditionItem;
    }

    /** 不是围栏条件返回 -1（hook 侧据此决定是否改 setRadius 的参数） */
    public static int radiusOf(Object item) {
        if (item instanceof GeofenceEnterConditionItem) {
            return ((GeofenceEnterConditionItem) item).getRadius();
        }
        if (item instanceof GeofenceLeaveConditionItem) {
            return ((GeofenceLeaveConditionItem) item).getRadius();
        }
        return -1;
    }

    static int clampRadius(int meters) {
        return meters <= 0 ? DEFAULT_RADIUS : Math.min(Math.max(meters, MIN_RADIUS), MAX_RADIUS);
    }

    /**
     * 弹「围栏半径」对话框，确定后写回 item 并回调 onConfirm（hook 侧接着打开地址选择页）。
     */
    public static void pickRadius(final Context context, final Object itemObj, final Runnable onConfirm) {
        if (context == null || !isItem(itemObj)) {
            return;
        }
        List<CardPicker.Spec> specs = new ArrayList<>();
        CardPicker.Spec spec = new CardPicker.Spec(cardTitle(),
                new String[]{InjectUi.zh() ? "米" : "m"}, radiusOf(itemObj), 0);
        spec.maxDigits = 5;
        specs.add(spec);
        CardPicker.show(context, itemObj instanceof GeofenceEnterConditionItem ? title(true) : title(false),
                specs, 0, new CardPicker.OnPicked() {
                    @Override
                    public void onPicked(int index, int amount, int unit) {
                        if (itemObj instanceof GeofenceEnterConditionItem) {
                            ((GeofenceEnterConditionItem) itemObj).setRadius(amount);
                        } else {
                            ((GeofenceLeaveConditionItem) itemObj).setRadius(amount);
                        }
                        if (onConfirm != null) {
                            onConfirm.run();
                        }
                    }
                });
    }

    // ------------------------------------------------------------------ 文案

    static String title(boolean enter) {
        if (InjectUi.zh()) {
            return enter ? "到达地理围栏" : "离开地理围栏";
        }
        return enter ? "Enter geofence" : "Leave geofence";
    }

    private static String cardTitle() {
        return InjectUi.zh() ? "围栏半径" : "Radius";
    }

    static String summary(AddressTaskItem item, boolean enter) {
        String name = item.t() == null ? "" : item.t();
        int r = radiusOf(item);
        if (InjectUi.zh()) {
            return (enter ? "到达 " : "离开 ") + name + "（半径 " + r + " 米）";
        }
        return (enter ? "Enter " : "Leave ") + name + " (" + r + " m)";
    }
}
