package io.github.hyperosauto.unlockapp.inject;

import android.text.TextUtils;

import com.miui.autotask.taskitem.LunchAppItem;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 「首次启动应用 / 首次离开应用」两个条件的共同基类。
 *
 * 和原生「启动应用 / 离开应用」一样继承 LunchAppItem，所以选应用页面、
 * 任务编辑页的图标（b/c/i）、应用列表存取（u/v/y/z）全部直接复用。
 * 区别只在于 m() 的判定依据：不看前台切换，而看进程是否存在于内存中，
 * 由 {@link FirstAppRuntime} 维护存活状态并在进程创建/销毁时通知引擎。
 *
 * 这个类会通过 addDexPath 注入到安全中心的 ClassLoader 里加载，
 * 所以可以直接 extends 安全中心的类；反过来它不能引用任何 Xposed API。
 */
public abstract class FirstAppConditionItem extends LunchAppItem {

    private static final long serialVersionUID = 1L;

    /** true = 首次启动，false = 首次离开 */
    public abstract boolean isStart();

    @Override
    public String h() {
        return title(isStart());
    }

    @Override
    public boolean m() {
        return FirstAppRuntime.evaluate(this);
    }

    /**
     * 原实现是 String.format(f(t()), w())，格式串来自安全中心的资源，
     * 我们没有对应资源 id，所以整个 g() 自己拼。
     */
    @Override
    public String g() {
        if (TextUtils.isEmpty(u())) {
            return x();
        }
        return String.format(summaryFormat(isStart()), joinAppNames());
    }

    @Override
    protected String x() {
        return emptyHint(isStart());
    }

    /** 所选应用里是否包含这个包名 */
    public boolean matches(String pkg) {
        if (TextUtils.isEmpty(pkg)) {
            return false;
        }
        return pkg.equals(y()) || z().contains(pkg);
    }

    /** 复刻 LunchAppItem.w()：appName + appNames 去重后用中文逗号拼接 */
    private String joinAppNames() {
        StringBuilder sb = new StringBuilder();
        List names = v();
        String first = u();
        if (!names.contains(first) && !TextUtils.isEmpty(first)) {
            sb.append(first).append("，");
        }
        Iterator it = names.iterator();
        while (it.hasNext()) {
            sb.append((String) it.next()).append("，");
        }
        return sb.length() > 0 ? sb.substring(0, sb.length() - 1) : "";
    }

    // ---- 文案：模块资源在安全中心进程里拿不到，按系统语言给中/英两套 ----

    private static boolean zh() {
        return "zh".equals(Locale.getDefault().getLanguage());
    }

    public static String title(boolean start) {
        if (zh()) {
            return start ? "冷启动应用" : "杀死应用";
        }
        return start ? "Cold start app" : "Kill app";
    }

    private static String summaryFormat(boolean start) {
        if (zh()) {
            return start ? "冷启动%s" : "杀死%s";
        }
        return start ? "Cold start %s" : "Kill %s";
    }

    private static String emptyHint(boolean start) {
        // 原生 StartActivityConditionItem 有占位文案，LeaveAppConditionItem 没有，保持一致
        if (!start) {
            return "";
        }
        return zh() ? "+设定冷启动应用" : "Set";
    }
}
