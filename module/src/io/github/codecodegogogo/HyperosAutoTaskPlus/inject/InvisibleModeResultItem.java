package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.miui.autotask.taskitem.SwitchTypeItem;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/**
 * 「隐身模式」结果项：任务触发时开启/关闭权限中心的隐身模式，
 * 退出时恢复成相反状态——和原生 WLAN/定位等开关型结果的行为一致。
 *
 * 继承 SwitchTypeItem 是为了复用它的 switchValue（Gson 字段名 "c"）和 t()/u()/v()，
 * 这样持久化、编辑页的单选框都能沿用原生逻辑。开关的实际执行在 {@link InvisibleModeRuntime}。
 *
 * 这个类会通过 addDexPath 注入到安全中心的 ClassLoader 里加载，
 * 所以可以直接 extends 安全中心的类；反过来它不能引用任何 Xposed API。
 */
public class InvisibleModeResultItem extends SwitchTypeItem {

    private static final long serialVersionUID = 1L;

    @Override
    public String e() {
        return FirstAppKeys.KEY_INVISIBLE_MODE_RESULT;
    }

    @Override
    public String h() {
        return InvisibleModeRuntime.title();
    }

    @Override
    public String g() {
        return InvisibleModeRuntime.summary(t());
    }

    // 安全中心的图标资源里没有隐身模式专用的三态图标，三个状态都用同一张
    @Override
    public int b() {
        return InvisibleModeRuntime.icon();
    }

    @Override
    public int c() {
        return InvisibleModeRuntime.icon();
    }

    @Override
    public int i() {
        return InvisibleModeRuntime.icon();
    }

    /** 任务触发：按设定值切换 */
    @Override
    public void n() {
        InvisibleModeRuntime.apply(t());
    }

    /** 任务退出恢复：切到相反状态 */
    @Override
    public void o() {
        InvisibleModeRuntime.apply(!t());
    }
}
