package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/**
 * 动作链界面统一的圆角、配色与控件样式，全部用代码构建（GradientDrawable / StateListDrawable），
 * 不依赖资源文件，跟随系统深浅主题。原生 new 出来的控件是方正的系统默认样式，这里统一套成
 * miuix 风格的圆角卡片 / 胶囊按钮。
 */
final class ChainUi {
    static final int ACCENT = 0xFF3482FF;
    private static final int ACCENT_PRESSED = 0xFF2A6AD6;
    private static final int DANGER = 0xFFE8503A;
    private static final int DANGER_PRESSED = 0xFFC8402D;
    private static final int WHITE = 0xFFFFFFFF;

    // 悬浮层（浮在其它应用上）固定深色，不跟随主题，保证任意背景下都清晰
    static final int OVERLAY_BG = 0xF01F2024;

    private ChainUi() {}

    static boolean night(Context c) {
        try {
            int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return mode == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 主题色

    static int cardBg(Context c) { return night(c) ? 0xFF2C2C31 : 0xFFF4F5F7; }
    static int cardPressed(Context c) { return night(c) ? 0xFF3A3A41 : 0xFFE6E8EC; }
    static int selectedBg(Context c) { return night(c) ? 0xFF1C3350 : 0xFFEAF2FF; }
    static int textPrimary(Context c) { return night(c) ? 0xFFECECEF : 0xFF1A1A1F; }
    static int textSecondary(Context c) { return night(c) ? 0xFF9A9DA3 : 0xFF84878D; }

    // ------------------------------------------------------------------ 圆角背景

    static GradientDrawable round(int color, int radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(InjectUi.dp(c, radiusDp));
        return d;
    }

    static GradientDrawable roundStroke(int color, int radiusDp, int strokeColor, int strokeDp, Context c) {
        GradientDrawable d = round(color, radiusDp, c);
        d.setStroke(InjectUi.dp(c, strokeDp), strokeColor);
        return d;
    }

    static Drawable pressable(int normal, int pressed, int radiusDp, Context c) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, round(pressed, radiusDp, c));
        sl.addState(new int[]{}, round(normal, radiusDp, c));
        return sl;
    }

    // ------------------------------------------------------------------ 按钮

    /** 主色填充圆角按钮（确定 / 开始一类主操作） */
    static void primary(Button b, Context c) { fill(b, ACCENT, ACCENT_PRESSED, WHITE, c); }

    /** 危险填充按钮（停止 / 删除） */
    static void danger(Button b, Context c) { fill(b, DANGER, DANGER_PRESSED, WHITE, c); }

    /** 浅色填充、主色文字的次按钮（编辑、试运行……） */
    static void tonal(Button b, Context c) { fill(b, cardBg(c), cardPressed(c), ACCENT, c); }

    private static void fill(Button b, int bg, int pressed, int textColor, Context c) {
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(14);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setBackground(pressable(bg, pressed, 20, c));
        int ph = InjectUi.dp(c, 18);
        int pv = InjectUi.dp(c, 9);
        b.setPadding(ph, pv, ph, pv);
        flatten(b);
    }

    /** 悬浮条上的透明描边白字按钮（深色背景用） */
    static void ghostOnDark(Button b, Context c) {
        b.setAllCaps(false);
        b.setTextColor(WHITE);
        b.setTextSize(14);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, round(0x33FFFFFF, 20, c));
        sl.addState(new int[]{}, roundStroke(0x00000000, 20, 0x66FFFFFF, 1, c));
        b.setBackground(sl);
        int ph = InjectUi.dp(c, 16);
        int pv = InjectUi.dp(c, 8);
        b.setPadding(ph, pv, ph, pv);
        flatten(b);
    }

    // ------------------------------------------------------------------ 输入框 / 文字

    static void field(EditText e, Context c) {
        e.setTextColor(textPrimary(c));
        e.setHintTextColor(textSecondary(c));
        e.setTextSize(15);
        e.setBackground(round(cardBg(c), 12, c));
        int ph = InjectUi.dp(c, 14);
        int pv = InjectUi.dp(c, 11);
        e.setPadding(ph, pv, ph, pv);
    }

    /** 分组小标题 */
    static void label(TextView t, Context c) {
        t.setTextColor(textSecondary(c));
        t.setTextSize(13);
    }

    /** 去掉按钮在部分主题下的抬升阴影（setStateListAnimator 是 API 21+，反射调用）。 */
    private static void flatten(View v) {
        try {
            View.class.getMethod("setStateListAnimator", Class.forName("android.animation.StateListAnimator"))
                    .invoke(v, (Object) null);
        } catch (Throwable ignored) {
            // 旧系统没有这个方法，本就没有抬升阴影
        }
    }

    /** 给悬浮卡片加投影（setElevation 是 API 21+，反射调用，兼容旧 android.jar 编译）。 */
    static void elevate(View v, int dp) {
        try {
            View.class.getMethod("setElevation", float.class).invoke(v, (float) InjectUi.dp(v.getContext(), dp));
        } catch (Throwable ignored) {
            // 旧系统无 setElevation，省略阴影即可
        }
    }
}
