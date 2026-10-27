package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.text.TextUtils;

import com.miui.autotask.taskitem.LunchAppItem;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/**
 * 「应用使用时长」条件。
 *
 * 第一个时长是累计统计范围，第二个时长是在该范围内需要达到的使用时长。
 * 应用打开后开始计时，只要进程没有被杀掉，即使进入后台也继续累计。
 * 单位为分钟、小时、天、周、月，其中月按 30 天计算。
 */
public class AppUsageConditionItem extends LunchAppItem {

    private static final long serialVersionUID = 1L;

    public static final int UNIT_MINUTE = 0;
    public static final int UNIT_HOUR = 1;
    public static final int UNIT_DAY = 2;
    public static final int UNIT_WEEK = 3;
    public static final int UNIT_MONTH = 4;

    private static final long MINUTE_MILLIS = 60_000L;
    private static final long[] UNIT_MILLIS = {
            MINUTE_MILLIS,
            60L * MINUTE_MILLIS,
            24L * 60L * MINUTE_MILLIS,
            7L * 24L * 60L * MINUTE_MILLIS,
            30L * 24L * 60L * MINUTE_MILLIS
    };

    /** 统计范围，默认最近 1 天 */
    private int rangeAmount = 1;
    private int rangeUnit = UNIT_DAY;
    /** 范围内累计使用时长，默认 30 分钟 */
    private int usageAmount = 30;
    private int usageUnit = UNIT_MINUTE;

    @Override
    public String e() {
        return FirstAppKeys.KEY_APP_USAGE_CONDITION;
    }

    @Override
    public String h() {
        return AppUsageRuntime.title();
    }

    @Override
    public String g() {
        return AppUsageRuntime.summary(this);
    }

    @Override
    public boolean l() {
        return hasSelectedApp()
                && rangeAmount > 0 && rangeUnit >= UNIT_MINUTE && rangeUnit <= UNIT_MONTH
                && usageAmount > 0 && usageUnit >= UNIT_MINUTE && usageUnit <= UNIT_MONTH
                && usageMillis() <= rangeMillis();
    }

    @Override
    public boolean m() {
        return AppUsageRuntime.evaluate(this);
    }

    public int getRangeAmount() {
        return rangeAmount;
    }

    public int getRangeUnit() {
        return rangeUnit;
    }

    public int getUsageAmount() {
        return usageAmount;
    }

    public int getUsageUnit() {
        return usageUnit;
    }

    public void setRange(int amountValue, int unitIndex) {
        this.rangeAmount = clampAmount(amountValue);
        this.rangeUnit = clampUnit(unitIndex);
    }

    public void setUsage(int amountValue, int unitIndex) {
        this.usageAmount = clampAmount(amountValue);
        this.usageUnit = clampUnit(unitIndex);
    }

    public long rangeMillis() {
        return durationMillis(rangeAmount, rangeUnit);
    }

    public long usageMillis() {
        return durationMillis(usageAmount, usageUnit);
    }

    public static long durationMillis(int amountValue, int unitIndex) {
        return clampAmount(amountValue) * UNIT_MILLIS[clampUnit(unitIndex)];
    }

    public boolean matches(String pkg) {
        if (TextUtils.isEmpty(pkg)) {
            return false;
        }
        return pkg.equals(y()) || z().contains(pkg);
    }

    public List<String> selectedPackages() {
        ArrayList<String> result = new ArrayList<>();
        String single = y();
        if (!TextUtils.isEmpty(single)) {
            result.add(single);
        }
        for (Object pkg : z()) {
            if (pkg instanceof String && !result.contains(pkg)) {
                result.add((String) pkg);
            }
        }
        return result;
    }

    public String appNamesSummary() {
        StringBuilder result = new StringBuilder();
        String single = u();
        if (!TextUtils.isEmpty(single)) {
            result.append(single);
        }
        for (Object name : v()) {
            if (!(name instanceof String) || TextUtils.equals(single, (String) name)) {
                continue;
            }
            if (result.length() > 0) {
                result.append("，");
            }
            result.append((String) name);
        }
        return result.toString();
    }

    private static int clampAmount(int value) {
        return Math.min(Math.max(value, 1), 99999);
    }

    private static int clampUnit(int value) {
        return Math.min(Math.max(value, UNIT_MINUTE), UNIT_MONTH);
    }

    private boolean hasSelectedApp() {
        if (!TextUtils.isEmpty(y())) {
            return true;
        }
        Iterator iterator = z().iterator();
        while (iterator.hasNext()) {
            Object pkg = iterator.next();
            if (pkg instanceof String && !TextUtils.isEmpty((String) pkg)) {
                return true;
            }
        }
        return false;
    }
}
