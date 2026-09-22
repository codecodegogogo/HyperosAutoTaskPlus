package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.content.res.Resources;

import java.util.Locale;

/** 注入侧共用的小工具：取安全中心自己的资源、dp 换算、语言判断、拿 Application Context */
final class InjectUi {

    private static volatile Context sContext;

    private InjectUi() {
    }

    static Context context() {
        if (sContext != null) {
            return sContext;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                sContext = (Context) app;
            }
        } catch (Throwable ignored) {
            // 没有就返回 null
        }
        return sContext;
    }

    static int appDrawable(Context c, String name) {
        try {
            return c.getResources().getIdentifier(name, "drawable", c.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    static int appColor(Context c, String name, int fallback) {
        try {
            Resources res = c.getResources();
            int id = res.getIdentifier(name, "color", c.getPackageName());
            return id == 0 ? fallback : res.getColor(id);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 0 正常、1 灰色、2 半透明；借安全中心某个原生条件的三态图，缺了就用系统图标兜底 */
    static int[] icons(String base, int fallback) {
        Context c = context();
        int[] icons = new int[]{
                c == null ? 0 : appDrawable(c, base),
                c == null ? 0 : appDrawable(c, base + "_grey"),
                c == null ? 0 : appDrawable(c, base + "_tran")};
        for (int i = 0; i < icons.length; i++) {
            if (icons[i] == 0) {
                icons[i] = fallback;
            }
        }
        return icons;
    }

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static boolean zh() {
        return "zh".equals(Locale.getDefault().getLanguage());
    }

    static String[] durationUnits() {
        return zh()
                ? new String[]{"秒", "分钟", "小时", "天"}
                : new String[]{"sec", "min", "hour", "day"};
    }
}
