package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/** 首次离开应用：所选应用的进程被彻底清出内存（后台被杀、上滑清理等）时满足。 */
public class FirstLeaveAppConditionItem extends FirstAppConditionItem {

    private static final long serialVersionUID = 1L;

    @Override
    public boolean isStart() {
        return false;
    }

    @Override
    public String e() {
        return FirstAppKeys.KEY_FIRST_LEAVE;
    }
}
