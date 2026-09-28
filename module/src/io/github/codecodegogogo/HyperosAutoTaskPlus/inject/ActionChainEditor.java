package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import miuix.appcompat.app.AlertDialog;

/**
 * 动作链的两层界面（主进程）：
 *   第一层 pickChain —— 动作库：列出已录制的链（单选 + 编辑），底部「录制新动作」；确定写回结果项。
 *   第二层 editChain —— 步骤编辑器：改名、整链重复、逐步增删改、试运行、重录、删除。
 *
 * 界面用 miuix AlertDialog + 动态构建的 LinearLayout，风格与现有编辑框一致。
 * 编辑始终在链的副本上进行，点保存才写回动作库。
 */
final class ActionChainEditor {
    private ActionChainEditor() {}

    // ------------------------------------------------------------------ 第一层：动作库

    static void pickChain(Context context, ActionChainResultItem item, Runnable confirm) {
        Activity activity = activity(context);
        if (activity == null || activity.isFinishing()) {
            DeviceActionRuntime.notice(context, DeviceActionRuntime.text("请在任务编辑页选择", "Choose from the task editor"));
            return;
        }
        showLibrary(activity, item, confirm);
    }

    private static void showLibrary(Activity activity, ActionChainResultItem item, Runnable confirm) {
        List<ActionChain> chains = ActionChainStore.list(activity);
        final String[] selectedId = {item.getChainId()};

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = InjectUi.dp(activity, 20);
        column.setPadding(pad, pad / 2, pad, 0);

        final LinearLayout listHolder = new LinearLayout(activity);
        listHolder.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = innerScroll(activity);
        scroll.addView(listHolder);
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, stepListHeight(activity)));

        TextView hint = new TextView(activity);
        hint.setText(DeviceActionRuntime.text("长按某条动作可删除", "Long-press an action to delete it"));
        hint.setTextSize(12);
        hint.setTextColor(ChainUi.textSecondary(activity));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = InjectUi.dp(activity, 6);
        column.addView(hint, hintParams);

        Button record = new Button(activity);
        record.setText(DeviceActionRuntime.text("＋ 录制新动作", "+ Record new action"));
        ChainUi.primary(record, activity);
        LinearLayout.LayoutParams recordParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        recordParams.topMargin = InjectUi.dp(activity, 12);
        int rv = InjectUi.dp(activity, 12);
        record.setPadding(0, rv, 0, rv);
        column.addView(record, recordParams);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(DeviceActionRuntime.text("动作链", "Action chains"))
                .setView(column)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        hideKeyboard(dialog);

        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            List<ActionChain> latest = ActionChainStore.list(activity);
            populateLibrary(activity, listHolder, latest, selectedId, refresh[0], dialog, item, confirm);
        };
        populateLibrary(activity, listHolder, chains, selectedId, refresh[0], dialog, item, confirm);

        record.setOnClickListener(v -> {
            dialog.dismiss();
            ActionChainRecorder.start(activity, chain -> whenForeground(activity, () -> {
                if (chain == null) {
                    // 空录制 / 取消：重新打开动作库
                    showLibrary(activity, item, confirm);
                } else {
                    // 录完直接进编辑器
                    editChain(activity, chain, item, confirm, true);
                }
            }));
        });

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (selectedId[0] == null || selectedId[0].isEmpty()) {
                DeviceActionRuntime.notice(activity, DeviceActionRuntime.text("请选择或录制一条动作链", "Select or record an action chain"));
                return;
            }
            ActionChain chosen = ActionChainStore.load(activity, selectedId[0]);
            if (chosen == null) {
                DeviceActionRuntime.notice(activity, DeviceActionRuntime.text("该动作链已失效", "That action chain is gone"));
                return;
            }
            item.setChain(chosen.id(), chosen.name());
            if (confirm != null) confirm.run();
            dialog.dismiss();
        });
    }

    private static void populateLibrary(Activity activity, LinearLayout holder, List<ActionChain> chains,
                                        String[] selectedId, Runnable refresh, AlertDialog libraryDialog,
                                        ActionChainResultItem item, Runnable confirm) {
        holder.removeAllViews();
        if (chains.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText(DeviceActionRuntime.text("还没有动作链，点下方录制", "No action chains yet; record one below"));
            empty.setTextColor(ChainUi.textSecondary(activity));
            empty.setMinHeight(InjectUi.dp(activity, 120));
            empty.setGravity(Gravity.CENTER);
            holder.addView(empty);
            return;
        }
        for (ActionChain chain : chains) {
            holder.addView(libraryRow(activity, chain, selectedId, refresh, libraryDialog, item, confirm));
        }
    }

    private static View libraryRow(Activity activity, ActionChain chain, String[] selectedId, Runnable refresh,
                                   AlertDialog libraryDialog, ActionChainResultItem item, Runnable confirm) {
        boolean selected = chain.id().equals(selectedId[0]);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(InjectUi.dp(activity, 60));
        // 圆角卡片；选中的填淡蓝并描主色边，未选中填卡片灰
        if (selected) {
            row.setBackground(ChainUi.roundStroke(ChainUi.selectedBg(activity), 16, ChainUi.ACCENT, 1, activity));
        } else {
            row.setBackground(ChainUi.round(ChainUi.cardBg(activity), 16, activity));
        }
        int ph = InjectUi.dp(activity, 14);
        int pv = InjectUi.dp(activity, 10);
        row.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = InjectUi.dp(activity, 8);
        row.setLayoutParams(rowParams);

        CheckBox radio = new CheckBox(activity);
        radio.setChecked(selected);
        radio.setClickable(false);
        row.addView(radio);

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = InjectUi.dp(activity, 6);
        TextView name = new TextView(activity);
        name.setText(chain.name().isEmpty() ? DeviceActionRuntime.text("未命名动作", "Unnamed action") : chain.name());
        name.setTextSize(16);
        name.setTextColor(ChainUi.textPrimary(activity));
        TextView sub = new TextView(activity);
        long sec = Math.max(1, chain.estimatedDurationMs() / 1000);
        int used = ActionChainStore.usage(activity, chain.id()).size();
        String usage = used == 0 ? "" : DeviceActionRuntime.text(" · " + used + " 个任务在用", " · used by " + used);
        sub.setText(chain.totalStepCount() + DeviceActionRuntime.text(" 步 · 约 ", " steps · ~") + sec
                + DeviceActionRuntime.text(" 秒", "s") + usage);
        sub.setTextSize(12);
        sub.setTextColor(ChainUi.textSecondary(activity));
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = InjectUi.dp(activity, 2);
        text.addView(name);
        text.addView(sub, subParams);
        row.addView(text, textParams);

        Button edit = new Button(activity);
        edit.setText(DeviceActionRuntime.text("编辑", "Edit"));
        ChainUi.tonal(edit, activity);
        row.addView(edit);

        row.setOnClickListener(v -> {
            selectedId[0] = chain.id();
            refresh.run();
        });
        edit.setOnClickListener(v -> {
            libraryDialog.dismiss();
            ActionChain copy = ActionChainStore.load(activity, chain.id());
            if (copy != null) editChain(activity, copy, item, confirm, false);
        });
        row.setOnLongClickListener(v -> {
            confirmDelete(activity, chain, () -> {
                if (chain.id().equals(selectedId[0])) selectedId[0] = null;
                refresh.run();
            });
            return true;
        });
        return row;
    }

    // ------------------------------------------------------------------ 第二层：步骤编辑器

    /** justRecorded 为 true 表示刚录完，保存 / 返回后仍回到动作库 */
    static void editChain(Activity activity, ActionChain chain, ActionChainResultItem item,
                          Runnable confirm, boolean justRecorded) {
        final ActionChain draft = chain.copy();

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = InjectUi.dp(activity, 20);
        column.setPadding(pad, pad / 2, pad, 0);

        addLabel(activity, column, DeviceActionRuntime.text("名称", "Name"), 0);
        EditText nameEdit = new EditText(activity);
        nameEdit.setSingleLine(true);
        nameEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        nameEdit.setText(draft.name());
        ChainUi.field(nameEdit, activity);
        column.addView(nameEdit);

        // 重复次数与每轮间隔并排一行，省高度
        LinearLayout repeatRow = new LinearLayout(activity);
        repeatRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams repeatRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        repeatRowParams.topMargin = InjectUi.dp(activity, 12);
        column.addView(repeatRow, repeatRowParams);

        LinearLayout repeatCol = new LinearLayout(activity);
        repeatCol.setOrientation(LinearLayout.VERTICAL);
        addLabel(activity, repeatCol, DeviceActionRuntime.text("整链重复次数", "Repeat times"), 0);
        EditText repeatEdit = new EditText(activity);
        repeatEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        repeatEdit.setText(String.valueOf(draft.repeatCount()));
        ChainUi.field(repeatEdit, activity);
        repeatCol.addView(repeatEdit);
        LinearLayout.LayoutParams repeatColParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        repeatColParams.rightMargin = InjectUi.dp(activity, 6);
        repeatRow.addView(repeatCol, repeatColParams);

        LinearLayout intervalCol = new LinearLayout(activity);
        intervalCol.setOrientation(LinearLayout.VERTICAL);
        addLabel(activity, intervalCol, DeviceActionRuntime.text("每轮间隔（毫秒）", "Interval (ms)"), 0);
        EditText intervalEdit = new EditText(activity);
        intervalEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalEdit.setText(String.valueOf(draft.repeatIntervalMs()));
        ChainUi.field(intervalEdit, activity);
        intervalCol.addView(intervalEdit);
        LinearLayout.LayoutParams intervalColParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        intervalColParams.leftMargin = InjectUi.dp(activity, 6);
        repeatRow.addView(intervalCol, intervalColParams);

        // 试运行 / 重新录制 / 删除 放在步骤列表上方：步骤再多也不会被挤出对话框
        final AlertDialog[] dialogRef = new AlertDialog[1];
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, InjectUi.dp(activity, 12), 0, 0);
        addActionButton(activity, actions, DeviceActionRuntime.text("试运行", "Test run"), false, () -> {
            applyHeader(activity, draft, nameEdit, repeatEdit, intervalEdit);
            if (draft.isValid()) ActionChainTester.run(activity, draft);
            else DeviceActionRuntime.notice(activity, DeviceActionRuntime.text("动作链为空", "Chain is empty"));
        });
        addActionButton(activity, actions, DeviceActionRuntime.text("重新录制", "Re-record"), false, () -> {
            applyHeader(activity, draft, nameEdit, repeatEdit, intervalEdit);
            dialogRef[0].dismiss();
            ActionChainRecorder.start(activity, recorded -> whenForeground(activity, () -> {
                if (recorded == null) editChain(activity, draft, item, confirm, justRecorded);
                else {
                    // 保留 id 与名称，替换步骤
                    ActionChain merged = replaceSteps(draft, recorded);
                    editChain(activity, merged, item, confirm, justRecorded);
                }
            }));
        });
        addActionButton(activity, actions, DeviceActionRuntime.text("删除", "Delete"), true, () ->
                confirmDelete(activity, draft, () -> {
                    dialogRef[0].dismiss();
                    showLibrary(activity, item, confirm);
                }));
        column.addView(actions);

        addLabel(activity, column, DeviceActionRuntime.text("步骤", "Steps"), 12);
        final LinearLayout stepsHolder = new LinearLayout(activity);
        stepsHolder.setOrientation(LinearLayout.VERTICAL);
        stepsHolder.setPadding(0, 0, 0, InjectUi.dp(activity, 8));
        ScrollView scroll = innerScroll(activity);
        scroll.addView(stepsHolder);
        // 步骤区固定一个高度（按屏幕高度取，避免小屏放不下），超出时只在这块区域内部上下滚动。
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, stepListHeight(activity)));

        Runnable[] renderSteps = new Runnable[1];
        renderSteps[0] = () -> renderStepList(activity, stepsHolder, draft, renderSteps[0]);
        renderSteps[0].run();

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(DeviceActionRuntime.text("编辑动作链", "Edit action chain"))
                .setView(column)
                .setPositiveButton(DeviceActionRuntime.text("保存", "Save"), null)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        dialogRef[0] = dialog;
        hideKeyboard(dialog);

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            applyHeader(activity, draft, nameEdit, repeatEdit, intervalEdit);
            if (!draft.isValid()) {
                DeviceActionRuntime.notice(activity, DeviceActionRuntime.text("动作链为空或过长", "Chain is empty or too long"));
                return;
            }
            draft.setUpdatedAt(System.currentTimeMillis());
            try {
                ActionChainStore.save(activity, draft);
                item.setChain(draft.id(), draft.name());
                if (confirm != null) confirm.run();
                dialog.dismiss();
            } catch (Throwable t) {
                Log.e(DeviceActionRuntime.TAG, "save chain failed", t);
                DeviceActionRuntime.notice(activity, DeviceActionRuntime.text("保存失败", "Save failed"));
            }
        });
    }

    private static void applyHeader(Context context, ActionChain draft, EditText nameEdit,
                                    EditText repeatEdit, EditText intervalEdit) {
        draft.setName(nameEdit.getText().toString().trim());
        draft.setRepeat(parseInt(repeatEdit.getText().toString(), 1), parseLong(intervalEdit.getText().toString(), 0));
    }

    private static ActionChain replaceSteps(ActionChain keep, ActionChain recorded) {
        return new ActionChain(keep.id(), keep.name(), keep.createdAt(), System.currentTimeMillis(),
                recorded.rotation(), recorded.screenWidthPx(), recorded.screenHeightPx(),
                keep.repeatCount(), keep.repeatIntervalMs(), recorded.steps());
    }

    /** 步骤滚动区高度：取屏幕高度的约 30%，并夹在 160~300dp，保证整个对话框放得下，列表底部不被裁掉。 */
    private static int stepListHeight(Context context) {
        int screenPx = context.getResources().getDisplayMetrics().heightPixels;
        int wanted = (int) (screenPx * 0.30f);
        int min = InjectUi.dp(context, 160);
        int max = InjectUi.dp(context, 300);
        return Math.max(min, Math.min(wanted, max));
    }

    // ------------------------------------------------------------------ 步骤列表渲染

    private static void renderStepList(Activity activity, LinearLayout holder, ActionChain draft, Runnable rerender) {
        holder.removeAllViews();
        List<ActionStep> steps = draft.steps();
        if (steps.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText(DeviceActionRuntime.text("暂无步骤", "No steps"));
            empty.setTextColor(ChainUi.textSecondary(activity));
            empty.setMinHeight(InjectUi.dp(activity, 60));
            empty.setGravity(Gravity.CENTER);
            holder.addView(empty);
        }
        for (int i = 0; i < steps.size(); i++) {
            holder.addView(stepRow(activity, draft, i, rerender));
        }
        Button add = new Button(activity);
        add.setText(DeviceActionRuntime.text("＋ 插入步骤", "+ Insert step"));
        ChainUi.tonal(add, activity);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addParams.topMargin = InjectUi.dp(activity, 8);
        int av = InjectUi.dp(activity, 10);
        add.setPadding(0, av, 0, av);
        add.setOnClickListener(v -> showInsertMenu(activity, draft, steps.size(), rerender));
        holder.addView(add, addParams);
    }

    private static View stepRow(Activity activity, ActionChain draft, int position, Runnable rerender) {
        List<ActionStep> steps = draft.steps();
        ActionStep step = steps.get(position);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(InjectUi.dp(activity, 50));
        row.setBackground(ChainUi.pressable(ChainUi.cardBg(activity), ChainUi.cardPressed(activity), 14, activity));
        int ph = InjectUi.dp(activity, 12);
        int pv = InjectUi.dp(activity, 4);
        row.setPadding(ph, pv, InjectUi.dp(activity, 4), pv);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = InjectUi.dp(activity, 8);
        row.setLayoutParams(rowParams);

        // 类型色圆点，一眼区分点击 / 滑动 / 延迟 / 重复
        View dot = new View(activity);
        dot.setBackground(ChainUi.round(stepColor(step.type()), 6, activity));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                InjectUi.dp(activity, 8), InjectUi.dp(activity, 8));
        dotParams.rightMargin = InjectUi.dp(activity, 10);
        row.addView(dot, dotParams);

        TextView desc = new TextView(activity);
        desc.setText(describe(step, draft));
        desc.setTextSize(14);
        desc.setTextColor(ChainUi.textPrimary(activity));
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(desc, descParams);

        TextView menu = new TextView(activity);
        menu.setText("⋮");
        menu.setTextSize(20);
        menu.setTextColor(ChainUi.textSecondary(activity));
        menu.setGravity(Gravity.CENTER);
        int mw = InjectUi.dp(activity, 40);
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(mw, mw);
        row.addView(menu, menuParams);

        row.setOnClickListener(v -> editStep(activity, draft, position, rerender));
        menu.setOnClickListener(v -> showStepMenu(activity, draft, position, rerender));
        return row;
    }

    /** 步骤类型对应的圆点颜色 */
    private static int stepColor(ActionStep.Type type) {
        switch (type) {
            case TAP: case LONG_PRESS: return 0xFF3482FF;   // 蓝：点击类
            case SWIPE: case MULTI: return 0xFF12B886;       // 绿：手势类
            case DELAY: return 0xFFF08C00;                   // 橙：等待
            case KEY: return 0xFF9775FA;                     // 紫：按键
            case REPEAT: return 0xFFE8503A;                  // 红：重复块
            default: return 0xFF868E96;
        }
    }

    private static void showStepMenu(Activity activity, ActionChain draft, int position, Runnable rerender) {
        List<ActionStep> steps = draft.steps();
        ActionStep step = steps.get(position);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        labels.add(DeviceActionRuntime.text("上移", "Move up"));
        actions.add(() -> { if (position > 0) { swap(steps, position, position - 1); rerender.run(); } });
        labels.add(DeviceActionRuntime.text("下移", "Move down"));
        actions.add(() -> { if (position < steps.size() - 1) { swap(steps, position, position + 1); rerender.run(); } });
        labels.add(DeviceActionRuntime.text("在其后插入", "Insert after"));
        actions.add(() -> showInsertMenu(activity, draft, position + 1, rerender));
        if (step.type() != ActionStep.Type.REPEAT && step.type() != ActionStep.Type.MULTI) {
            labels.add(DeviceActionRuntime.text("复制", "Duplicate"));
            actions.add(() -> { steps.add(position + 1, step); rerender.run(); });
        }
        if (step.type() == ActionStep.Type.REPEAT) {
            labels.add(DeviceActionRuntime.text("取消重复块", "Ungroup repeat"));
            actions.add(() -> { steps.remove(position); steps.addAll(position, step.children()); rerender.run(); });
        } else {
            labels.add(DeviceActionRuntime.text("包成重复块", "Wrap as repeat"));
            actions.add(() -> wrapAsRepeat(activity, draft, position, rerender));
        }
        labels.add(DeviceActionRuntime.text("删除", "Delete"));
        actions.add(() -> { steps.remove(position); rerender.run(); });

        showMenu(activity, describe(step, draft), labels, actions);
    }

    private static void showInsertMenu(Activity activity, ActionChain draft, int at, Runnable rerender) {
        List<ActionStep> steps = draft.steps();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(DeviceActionRuntime.text("延迟", "Delay"));
        actions.add(() -> editNewDelay(activity, draft, at, rerender));
        labels.add(DeviceActionRuntime.text("返回键", "Back key"));
        actions.add(() -> { steps.add(clamp(at, steps), ActionStep.key(ActionStep.KEY_BACK)); rerender.run(); });
        labels.add(DeviceActionRuntime.text("主页键", "Home key"));
        actions.add(() -> { steps.add(clamp(at, steps), ActionStep.key(ActionStep.KEY_HOME)); rerender.run(); });
        labels.add(DeviceActionRuntime.text("最近任务", "Recents"));
        actions.add(() -> { steps.add(clamp(at, steps), ActionStep.key(ActionStep.KEY_RECENTS)); rerender.run(); });
        showMenu(activity, DeviceActionRuntime.text("插入步骤", "Insert step"), labels, actions);
    }

    // ------------------------------------------------------------------ 单步编辑

    private static void editStep(Activity activity, ActionChain draft, int position, Runnable rerender) {
        ActionStep step = draft.steps().get(position);
        switch (step.type()) {
            case TAP: case LONG_PRESS:
                editPoint(activity, draft, position, rerender);
                break;
            case SWIPE:
                editSwipe(activity, draft, position, rerender);
                break;
            case DELAY:
                editDelay(activity, draft, position, rerender);
                break;
            case REPEAT:
                editRepeat(activity, draft, position, rerender);
                break;
            case MULTI:
                DeviceActionRuntime.notice(activity, DeviceActionRuntime.text(
                        "多指手势只能删除，不能编辑", "Multi-touch gestures can only be deleted"));
                break;
            default:
                break;
        }
    }

    private static void editPoint(Activity activity, ActionChain draft, int position, Runnable rerender) {
        ActionStep step = draft.steps().get(position);
        int w = draft.screenWidthPx();
        int h = draft.screenHeightPx();
        LinearLayout column = verticalColumn(activity);
        addLabel(activity, column, DeviceActionRuntime.text(
                "位置（像素，屏幕 " + w + " × " + h + "，左上角为 0,0）",
                "Position (px, screen " + w + " × " + h + ", top-left is 0,0)"), 0);
        LinearLayout xy = new LinearLayout(activity);
        xy.setOrientation(LinearLayout.HORIZONTAL);
        EditText xEdit = numberField(activity, toPx(step.x(), w));
        EditText yEdit = numberField(activity, toPx(step.y(), h));
        LinearLayout.LayoutParams xParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        xParams.rightMargin = InjectUi.dp(activity, 6);
        LinearLayout.LayoutParams yParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        yParams.leftMargin = InjectUi.dp(activity, 6);
        xEdit.setHint("X");
        yEdit.setHint("Y");
        xy.addView(xEdit, xParams);
        xy.addView(yEdit, yParams);
        column.addView(xy);
        addLabel(activity, column, DeviceActionRuntime.text("按住时长（毫秒）", "Press duration (ms)"), 12);
        EditText duration = numberField(activity, step.durationMs());
        column.addView(duration);
        showFieldDialog(activity, describe(step, draft), column, () -> {
            // 像素按录制时的屏幕尺寸换回比例；回放时再按当前屏幕尺寸还原成像素
            float nx = w > 0 ? (parseLong(xEdit.getText().toString(), toPx(step.x(), w)) + 0.5f) / w : step.x();
            float ny = h > 0 ? (parseLong(yEdit.getText().toString(), toPx(step.y(), h)) + 0.5f) / h : step.y();
            long ms = parseLong(duration.getText().toString(), step.durationMs());
            ActionStep moved = step.type() == ActionStep.Type.LONG_PRESS
                    ? ActionStep.longPress(nx, ny, ms) : ActionStep.tap(nx, ny, ms);
            draft.steps().set(position, moved);
            rerender.run();
        });
    }

    /** 比例坐标 → 录制屏幕上的像素（取整到像素格） */
    private static long toPx(float ratio, int size) {
        if (size <= 0) return 0;
        return Math.max(0, Math.min(size - 1, (long) Math.floor(ratio * size)));
    }

    private static void editSwipe(Activity activity, ActionChain draft, int position, Runnable rerender) {
        ActionStep step = draft.steps().get(position);
        LinearLayout column = verticalColumn(activity);
        addLabel(activity, column, DeviceActionRuntime.text("滑动总时长（毫秒）", "Swipe duration (ms)"), 0);
        EditText duration = numberField(activity, ActionStep.gestureDuration(step.frames()));
        column.addView(duration);
        showFieldDialog(activity, DeviceActionRuntime.text("滑动", "Swipe"), column, () -> {
            draft.steps().set(position, step.withSwipeDuration(
                    parseLong(duration.getText().toString(), ActionStep.gestureDuration(step.frames()))));
            rerender.run();
        });
    }

    private static void editDelay(Activity activity, ActionChain draft, int position, Runnable rerender) {
        ActionStep step = draft.steps().get(position);
        LinearLayout column = verticalColumn(activity);
        addLabel(activity, column, DeviceActionRuntime.text("延迟（毫秒）", "Delay (ms)"), 0);
        EditText ms = numberField(activity, step.durationMs());
        column.addView(ms);
        showFieldDialog(activity, DeviceActionRuntime.text("延迟", "Delay"), column, () -> {
            draft.steps().set(position, ActionStep.delay(parseLong(ms.getText().toString(), step.durationMs())));
            rerender.run();
        });
    }

    private static void editNewDelay(Activity activity, ActionChain draft, int at, Runnable rerender) {
        LinearLayout column = verticalColumn(activity);
        addLabel(activity, column, DeviceActionRuntime.text("延迟（毫秒）", "Delay (ms)"), 0);
        EditText ms = numberField(activity, 500);
        column.addView(ms);
        showFieldDialog(activity, DeviceActionRuntime.text("插入延迟", "Insert delay"), column, () -> {
            draft.steps().add(clamp(at, draft.steps()), ActionStep.delay(parseLong(ms.getText().toString(), 500)));
            rerender.run();
        });
    }

    private static void editRepeat(Activity activity, ActionChain draft, int position, Runnable rerender) {
        ActionStep step = draft.steps().get(position);
        LinearLayout column = verticalColumn(activity);
        addLabel(activity, column, DeviceActionRuntime.text("重复次数", "Repeat count"), 0);
        EditText count = numberField(activity, step.repeatCount());
        column.addView(count);
        addLabel(activity, column, DeviceActionRuntime.text("每次间隔（毫秒）", "Interval (ms)"), 12);
        EditText interval = numberField(activity, step.intervalMs());
        column.addView(interval);
        showFieldDialog(activity, DeviceActionRuntime.text("重复块", "Repeat block"), column, () -> {
            try {
                draft.steps().set(position, step.withRepeat(
                        parseInt(count.getText().toString(), step.repeatCount()),
                        parseLong(interval.getText().toString(), step.intervalMs())));
                rerender.run();
            } catch (IllegalArgumentException e) {
                DeviceActionRuntime.notice(activity, DeviceActionRuntime.text("重复次数需为 1~999", "Repeat count must be 1-999"));
            }
        });
    }

    private static void wrapAsRepeat(Activity activity, ActionChain draft, int position, Runnable rerender) {
        // 第一版：只把当前这一步包成重复块（默认 2 次）。连续多步的包裹留待多选交互。
        ActionStep step = draft.steps().get(position);
        try {
            List<ActionStep> children = new ArrayList<>();
            children.add(step);
            draft.steps().set(position, ActionStep.repeat(2, 0, children));
            rerender.run();
        } catch (IllegalArgumentException e) {
            DeviceActionRuntime.notice(activity, DeviceActionRuntime.text("该步骤不能重复", "This step cannot be repeated"));
        }
    }

    // ------------------------------------------------------------------ 删除确认

    private static void confirmDelete(Activity activity, ActionChain draft, Runnable onDeleted) {
        List<String> tasks = ActionChainStore.usage(activity, draft.id());
        String message;
        if (tasks.isEmpty()) {
            message = DeviceActionRuntime.text("确定删除这条动作链？", "Delete this action chain?");
        } else {
            StringBuilder sb = new StringBuilder(DeviceActionRuntime.text(
                    "以下任务正在使用这条动作链，删除后它们将无法执行：\n", "These tasks use this chain and will break:\n"));
            for (String t : tasks) sb.append("· ").append(t).append('\n');
            message = sb.toString();
        }
        new AlertDialog.Builder(activity)
                .setTitle(DeviceActionRuntime.text("删除动作链", "Delete action chain"))
                .setMessage(message)
                .setPositiveButton(DeviceActionRuntime.text("删除", "Delete"), (d, w) -> {
                    ActionChainStore.delete(activity, draft.id());
                    DeviceActionRuntime.notice(activity, DeviceActionRuntime.text("已删除", "Deleted"));
                    onDeleted.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ 录制后回到前台

    /**
     * 录制结束时本应用还在后台：先把任务切回前台，等 Activity 真正拿到窗口焦点再弹对话框。
     * 在后台直接弹框，窗口拿不到正确的 insets，回来后底部会多出一截空白。
     */
    private static void whenForeground(Activity activity, Runnable show) {
        if (activity.isFinishing()) return;
        bringToFront(activity);
        View decor = activity.getWindow().getDecorView();
        // 等焦点：每 150ms 查一次；用户迟迟不回来也一直等，回来那一刻再弹
        decor.post(new Runnable() {
            @Override public void run() {
                if (activity.isFinishing()) return;
                if (decor.hasWindowFocus()) show.run();
                else decor.postDelayed(this, 150);
            }
        });
    }

    private static void bringToFront(Activity activity) {
        try {
            ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            am.moveTaskToFront(activity.getTaskId(), 0);
            return;
        } catch (Throwable t) {
            Log.w(DeviceActionRuntime.TAG, "moveTaskToFront failed, fallback to reorder", t);
        }
        try {
            Intent intent = new Intent(activity, activity.getClass())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(intent);
        } catch (Throwable t) {
            Log.w(DeviceActionRuntime.TAG, "bring activity to front failed", t);
        }
    }

    /**
     * 对话框里的列表外层还有 miuix 的滚动容器。必须在 dispatchTouchEvent 最开头就禁止外层拦截：
     * 手指多半落在步骤卡片上，卡片先接走事件，挂在 ScrollView 上的 OnTouchListener 根本收不到。
     */
    private static ScrollView innerScroll(Context context) {
        ScrollView scroll = new ScrollView(context) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return super.dispatchTouchEvent(event);
            }
        };
        scroll.setVerticalScrollBarEnabled(true);
        return scroll;
    }

    /** 打开编辑框时不自动弹输入法，避免输入法占位把对话框顶出一截空白 */
    private static void hideKeyboard(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    // ------------------------------------------------------------------ 通用小工具

    private static void showMenu(Activity activity, String title, List<String> labels, List<Runnable> actions) {
        CharSequence[] items = labels.toArray(new CharSequence[0]);
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setItems(items, (dialog, which) -> {
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showFieldDialog(Activity activity, String title, View content, Runnable onOk) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            onOk.run();
            dialog.dismiss();
        });
    }

    private static LinearLayout verticalColumn(Activity activity) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = InjectUi.dp(activity, 20);
        column.setPadding(pad, pad / 2, pad, pad);
        return column;
    }

    private static EditText numberField(Context context, long value) {
        EditText edit = new EditText(context);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        edit.setText(String.valueOf(value));
        ChainUi.field(edit, context);
        return edit;
    }

    private static void addLabel(Context context, LinearLayout column, String text, int topGap) {
        TextView label = new TextView(context);
        label.setText(text);
        ChainUi.label(label, context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = InjectUi.dp(context, topGap);
        params.bottomMargin = InjectUi.dp(context, 6);
        column.addView(label, params);
    }

    /** 底部一排等宽圆角按钮，danger=true 用红色（删除） */
    private static void addActionButton(Context context, LinearLayout row, String text, boolean danger, Runnable action) {
        Button button = new Button(context);
        button.setText(text);
        if (danger) ChainUi.danger(button, context);
        else ChainUi.tonal(button, context);
        int pv = InjectUi.dp(context, 9);
        button.setPadding(0, pv, 0, pv);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = InjectUi.dp(context, 4);
        params.rightMargin = InjectUi.dp(context, 4);
        button.setOnClickListener(v -> action.run());
        row.addView(button, params);
    }

    private static String describe(ActionStep step, ActionChain chain) {
        int w = chain.screenWidthPx();
        int h = chain.screenHeightPx();
        switch (step.type()) {
            case TAP:
                return DeviceActionRuntime.text("点击 ", "Tap ") + pixel(step.x(), step.y(), w, h)
                        + " · " + step.durationMs() + "ms";
            case LONG_PRESS:
                return DeviceActionRuntime.text("长按 ", "Long press ") + pixel(step.x(), step.y(), w, h)
                        + " · " + step.durationMs() + "ms";
            case SWIPE: {
                List<GestureFrame> frames = step.frames();
                GestureFrame first = frames.get(0);
                GestureFrame last = frames.get(frames.size() - 1);
                return DeviceActionRuntime.text("滑动 ", "Swipe ") + pixel(first.xs[0], first.ys[0], w, h)
                        + " → " + pixel(last.xs[0], last.ys[0], w, h)
                        + " · " + ActionStep.gestureDuration(frames) + "ms";
            }
            case MULTI:
                return DeviceActionRuntime.text("多指手势 · ", "Multi-touch · ") + step.frames().size()
                        + DeviceActionRuntime.text(" 帧", " frames");
            case DELAY:
                return DeviceActionRuntime.text("等待 ", "Wait ") + step.durationMs() + "ms";
            case KEY:
                return keyName(step.keyCode());
            case REPEAT:
                return DeviceActionRuntime.text("重复 ", "Repeat ") + step.repeatCount()
                        + DeviceActionRuntime.text(" 次 ▸ ", "x ▸ ") + step.children().size()
                        + DeviceActionRuntime.text(" 步", " steps");
            default:
                return "?";
        }
    }

    /** 显示为录制屏幕上的像素坐标，如 (540, 1200) */
    private static String pixel(float x, float y, int w, int h) {
        return "(" + toPx(x, w) + ", " + toPx(y, h) + ")";
    }

    private static String keyName(int keyCode) {
        if (keyCode == ActionStep.KEY_BACK) return DeviceActionRuntime.text("返回键", "Back key");
        if (keyCode == ActionStep.KEY_HOME) return DeviceActionRuntime.text("主页键", "Home key");
        if (keyCode == ActionStep.KEY_RECENTS) return DeviceActionRuntime.text("最近任务", "Recents");
        return DeviceActionRuntime.text("按键", "Key");
    }

    private static void swap(List<ActionStep> list, int a, int b) {
        ActionStep tmp = list.get(a);
        list.set(a, list.get(b));
        list.set(b, tmp);
    }

    private static int clamp(int at, List<?> list) {
        return at < 0 ? 0 : (at > list.size() ? list.size() : at);
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    private static long parseLong(String s, long fallback) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return fallback; }
    }

    private static Activity activity(Context context) {
        for (int i = 0; context != null && i < 16; i++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) return null;
            context = next;
        }
        return null;
    }
}
