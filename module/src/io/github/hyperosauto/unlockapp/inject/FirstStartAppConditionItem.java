package io.github.hyperosauto.unlockapp.inject;

import io.github.hyperosauto.unlockapp.FirstAppKeys;

/** 首次启动应用：所选应用的进程被创建（从未运行到运行，包括被后台唤起）时满足。 */
public class FirstStartAppConditionItem extends FirstAppConditionItem {

    private static final long serialVersionUID = 1L;

    @Override
    public boolean isStart() {
        return true;
    }

    @Override
    public String e() {
        return FirstAppKeys.KEY_FIRST_START;
    }
}
