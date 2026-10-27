package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.google.gson.Gson;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/** 应用使用时长条件的双时间默认值、单位换算、边界和持久化回归。 */
public final class AppUsageConditionTest {

    private AppUsageConditionTest() {
    }

    public static void main(String[] args) {
        AppUsageConditionItem item = new AppUsageConditionItem();
        check(FirstAppKeys.KEY_APP_USAGE_CONDITION.equals(item.e()), "条件 key");
        check(item.getRangeAmount() == 1, "默认统计范围数值");
        check(item.getRangeUnit() == AppUsageConditionItem.UNIT_DAY, "默认统计范围单位");
        check(item.getUsageAmount() == 30, "默认使用时长数值");
        check(item.getUsageUnit() == AppUsageConditionItem.UNIT_MINUTE, "默认使用时长单位");
        check(item.rangeMillis() == 86_400_000L, "默认统计范围换算");
        check(item.usageMillis() == 30L * 60_000L, "默认使用时长换算");

        check(AppUsageConditionItem.durationMillis(1, AppUsageConditionItem.UNIT_MINUTE)
                == 60_000L, "分钟换算");
        check(AppUsageConditionItem.durationMillis(2, AppUsageConditionItem.UNIT_HOUR)
                == 7_200_000L, "小时换算");
        check(AppUsageConditionItem.durationMillis(1, AppUsageConditionItem.UNIT_DAY)
                == 86_400_000L, "天换算");
        check(AppUsageConditionItem.durationMillis(1, AppUsageConditionItem.UNIT_WEEK)
                == 604_800_000L, "周换算");
        check(AppUsageConditionItem.durationMillis(1, AppUsageConditionItem.UNIT_MONTH)
                == 2_592_000_000L, "月按 30 天换算");

        item.setRange(3, AppUsageConditionItem.UNIT_WEEK);
        item.setUsage(5, AppUsageConditionItem.UNIT_HOUR);
        check(item.rangeMillis() == 3L * 604_800_000L, "统计范围设置");
        check(item.usageMillis() == 5L * 3_600_000L, "使用时长设置");

        item.setRange(0, 99);
        item.setUsage(0, 99);
        check(item.getRangeAmount() == 1, "统计范围数值下限");
        check(item.getRangeUnit() == AppUsageConditionItem.UNIT_MONTH, "统计范围单位上限");
        check(item.getUsageAmount() == 1, "使用时长数值下限");
        check(item.getUsageUnit() == AppUsageConditionItem.UNIT_MONTH, "使用时长单位上限");

        Gson gson = new Gson();
        AppUsageConditionItem restored = gson.fromJson(gson.toJson(item), AppUsageConditionItem.class);
        check(restored.getRangeAmount() == item.getRangeAmount(), "统计范围数值持久化");
        check(restored.getRangeUnit() == item.getRangeUnit(), "统计范围单位持久化");
        check(restored.getUsageAmount() == item.getUsageAmount(), "使用时长数值持久化");
        check(restored.getUsageUnit() == item.getUsageUnit(), "使用时长单位持久化");

        check(AppUsageRuntime.overlap(1_000L, 5_000L, 2_000L, 4_000L) == 2_000L,
                "使用区间裁剪到统计范围");
        check(AppUsageRuntime.overlap(1_000L, 2_000L, 3_000L, 4_000L) == 0L,
                "范围外区间不计入");
        check(AppUsageRuntime.overlap(1_000L, 5_000L, 0L, 10_000L) == 4_000L,
                "完整区间计入");

        System.out.println("AppUsageConditionTest: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
