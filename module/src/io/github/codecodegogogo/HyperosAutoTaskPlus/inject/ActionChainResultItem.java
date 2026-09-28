package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.miui.autotask.taskitem.TaskItem;
import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/**
 * 动作链结果项。任务里只保存动作库中的 chainId 引用，外加一个缓存名 chainName，
 * 这样动作库文件即使暂时读不到，任务摘要也还能显示名字。
 *
 * 字段用独立命名（chainId / chainName），避免与 TaskItem 的 Gson 字段 a/b 冲突；
 * 不保存 Context 或回放状态，Gson 和 Serializable 只负责这两个配置字段。
 */
public class ActionChainResultItem extends TaskItem {
    private static final long serialVersionUID = 1L;

    private String chainId = "";
    private String chainName = "";

    public ActionChainResultItem() {}

    public ActionChainResultItem(String key) {
        if (!FirstAppKeys.isActionChainKey(key)) throw new IllegalArgumentException("未知结果");
    }

    @Override public String e() { return FirstAppKeys.KEY_ACTION_CHAIN_RESULT; }
    @Override public String h() { return ActionChainRuntime.title(); }
    @Override public String g() { return ActionChainRuntime.summary(this); }
    @Override public int b() { return ActionChainRuntime.icon(1); }
    @Override public int c() { return ActionChainRuntime.icon(0); }
    @Override public int i() { return ActionChainRuntime.icon(2); }

    /** 参数完整 = 选了一条链。文件是否还在由回放时再确认，避免编辑页误判为未配置。 */
    @Override public boolean l() {
        return chainId != null && !chainId.isEmpty();
    }

    @Override public void n() { ActionChainRuntime.play(this); }

    /** 一次性结果，退出时不恢复。 */
    @Override public void o() {}

    public String getChainId() { return chainId == null ? "" : chainId; }
    public String getChainName() { return chainName == null ? "" : chainName; }

    public void setChain(String id, String name) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("请选择动作链");
        chainId = id;
        chainName = name == null ? "" : name;
    }
}
