package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.widget.Toast;

import com.miui.autotask.taskitem.TaskItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 编辑 hook 与注入侧配置之间的桥；所有修改先保留在草稿，确定任务时才落库。 */
public final class ThenEditorSupport {
    private ThenEditorSupport() {}

    public static void configure(Class<?> factory) { ThenActionCodec.configure(factory); }

    public static Object newPlan() {
        ThenResultItem item = new ThenResultItem();
        item.code = 2;
        return item;
    }

    public static boolean isPlan(Object value) { return value instanceof ThenResultItem; }

    public static Object copyPlan(Object value) {
        return ThenActionCodec.copyTask(plan(value));
    }

    public static List<ThenGroup> groups(Object value) {
        ThenResultItem item = plan(value);
        if (!item.isSupportedVersion()) throw new IllegalArgumentException("再执行配置版本不可用");
        return item.getGroups();
    }

    public static Object newGroup() { return new ThenGroup(); }

    public static List<TaskItem> decodeActions(Object value) {
        return ThenActionCodec.decodeAll(group(value).getActions());
    }

    public static void setActions(Object value, List<?> items) {
        if (items == null) throw new IllegalArgumentException("执行项目列表缺失");
        List<ThenAction> actions = new ArrayList<>();
        for (Object candidate : items) {
            if (!(candidate instanceof TaskItem)) throw new IllegalArgumentException("执行项目类型不可用");
            TaskItem item = (TaskItem) candidate;
            if ("key_result_list".equals(item.e())) continue;
            actions.add(ThenActionCodec.encode(item));
        }
        group(value).setActions(actions);
    }

    public static void setGroupEnabled(Object value, boolean enabled) {
        group(value).setEnabled(enabled);
    }

    public static void setGroups(Object value, List<?> groups) {
        if (groups == null) throw new IllegalArgumentException("再执行分组缺失");
        List<ThenGroup> checked = new ArrayList<>();
        for (Object candidate : groups) checked.add(group(candidate));
        plan(value).setGroups(checked);
    }

    public static String delaySummary(Object value) {
        ThenGroup group = group(value);
        return "等待 " + group.getDelayAmount() + (group.getDelayUnit() == 1 ? " 分钟" : " 秒") + "后执行";
    }

    public static String validationError(Object value) {
        if (!(value instanceof ThenResultItem)) return "再执行配置不可用，请重新添加";
        ThenResultItem plan = (ThenResultItem) value;
        if (!plan.isSupportedVersion()) return "再执行配置版本不可用，请重新添加";
        List<ThenGroup> groups = plan.getGroups();
        if (groups.isEmpty()) return "";
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < groups.size(); i++) {
            ThenGroup group = groups.get(i);
            String prefix = "再执行 " + (i + 1) + "：";
            if (group == null) return prefix + "配置损坏，请删除后重新添加";
            if (!group.isEnabled()) {
                if (!group.isValid() || !ids.add(group.getId())) return prefix + "配置无效，请检查等待时长";
                continue;
            }
            if (group.getActions().isEmpty()) return prefix + "请至少添加一个执行项目，或关闭本组开关";
            if (!group.isValid() || !ids.add(group.getId())) return prefix + "配置无效，请检查等待时长";
            try {
                ThenActionCodec.decodeAll(group.getActions());
            } catch (RuntimeException e) {
                return prefix + "有不可用的执行项目，请重新配置或关闭本组开关";
            }
        }
        return plan.l() ? "" : "再执行配置版本不可用，请重新添加";
    }

    public static void pickDelay(Context context, Object value, Runnable confirm) {
        if (context == null) return;
        final ThenGroup group = group(value);
        // 复用原生「时间间隔」那套卡片选择器，外观与系统条件一致
        CardPicker.Spec spec = new CardPicker.Spec(
                InjectUi.zh() ? "等待" : "Wait",
                InjectUi.zh() ? new String[]{"秒", "分钟"} : new String[]{"sec", "min"},
                group.getDelayAmount(),
                group.getDelayUnit() == ThenGroup.UNIT_MINUTES ? ThenGroup.UNIT_MINUTES : ThenGroup.UNIT_SECONDS);
        spec.maxDigits = 9;
        List<CardPicker.Spec> specs = new ArrayList<>();
        specs.add(spec);
        CardPicker.show(context, InjectUi.zh() ? "等待时间" : "Waiting time", specs, 0, new CardPicker.OnPicked() {
            @Override
            public void onPicked(int index, int amount, int unit) {
                try {
                    group.setDelay(amount, unit);
                } catch (IllegalArgumentException e) {
                    Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
                if (confirm != null) confirm.run();
            }
        });
    }

    private static ThenResultItem plan(Object value) {
        if (!(value instanceof ThenResultItem)) throw new IllegalArgumentException("再执行配置不可用");
        return (ThenResultItem) value;
    }

    private static ThenGroup group(Object value) {
        if (!(value instanceof ThenGroup)) throw new IllegalArgumentException("再执行分组不可用");
        return (ThenGroup) value;
    }
}
