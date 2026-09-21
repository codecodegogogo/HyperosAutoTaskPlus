package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 功能五：退出条件可自定义——可以增加额外的退出条件，也可以修改 / 删除自动生成的那条。
 *
 * 原生逻辑：任务编辑页的「退出条件」列表（key_exit_condition_list）只读，
 * 每加一个触发条件就自动塞进它的反向条件（M0.t），删触发条件时同步删掉；
 * 列表没有「添加」行（G(false)）、没有删除按钮（H(false)），点击项目除电量外一律无响应。
 * 引擎侧其实对退出条件一视同仁（p() 注册、m() 判定、任一满足即恢复），
 * 所以放开的只是界面：
 *
 *   NewTaskFragment.onCreatePreferences   退出列表打开「添加」行和删除按钮，分类常驻显示
 *   NewTaskFragment.M0(int)               退出条件数量变化时不再隐藏分类 / 取消勾选
 *   NewTaskFragment.onActivityResult      requestCode 106：从「添加条件」页选回来的项直接进退出列表
 *   RecyclerViewPreference$a.onItemClick  退出列表里点「添加」行 -> 打开添加条件页（106）；
 *                                         点已有项 -> 借用触发列表的编辑分发（临时把 h 置 false）
 *   RecyclerViewPreference.B(int,int,Intent)
 *                                         102：触发条件新增时仍自动加反向条件，但要插在「添加」行之前；
 *                                         104/105：退出项自己编辑回来的结果替换原位置
 *   Y1.v.o(holder,int)                    「添加」行不显示勾选框和删除；只剩一条退出条件时不显示勾选框
 *
 * 退出条件之间是「或」：勾选的任一条件满足即退出（原生文案「满足以下选中的任一条件则自动退出任务」）。
 * 「自定义时间」作为退出条件引擎不会设闹钟，添加页里把它禁掉。
 */
final class ExitConditionHook {

    private static final String TAG = MainHook.TAG;

    private static final String CLASS_NEW_TASK_FRAGMENT = "com.miui.autotask.fragment.NewTaskFragment";
    private static final String CLASS_RECYCLER_PREF = "com.miui.autotask.view.RecyclerViewPreference";
    private static final String CLASS_RECYCLER_PREF_LISTENER = "com.miui.autotask.view.RecyclerViewPreference$a";
    private static final String CLASS_ADAPTER = "Y1.v";
    private static final String CLASS_ADAPTER_HOLDER = "Y1.v$c";
    private static final String CLASS_TASK_ITEM = "com.miui.autotask.taskitem.TaskItem";
    private static final String CLASS_DEFAULT_TASK_ITEM = "com.miui.autotask.taskitem.DefaultTaskItem";
    private static final String CLASS_ADD_BASE_ACTIVITY = "com.miui.autotask.activity.AddBaseActivity";
    private static final String CLASS_ADD_CONDITION_ACTIVITY = "com.miui.autotask.activity.AddConditionActivity";

    /** 列表里「添加条件」占位行的 key */
    private static final String KEY_ADD_CONDITION_ROW = "key_condition_list";
    private static final String KEY_CUSTOM_TIME_CONDITION = "key_custom_time_condition_item";

    /** 原生用 102/103 添加条件/结果，104/105 编辑；106 留给「添加退出条件」 */
    private static final int REQUEST_ADD_EXIT_CONDITION = 106;
    private static final int REQUEST_ADD_CONDITION = 102;
    private static final int REQUEST_EDIT_CONDITION = 104;
    private static final int REQUEST_EDIT_APP_CONDITION = 105;

    private ExitConditionHook() {
    }

    static void install(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        HookUtils.step("NewTaskFragment(exit)", () -> hookNewTaskFragment(cl));
        HookUtils.step("RecyclerViewPreference(exit)", () -> hookRecyclerPreference(cl));
        HookUtils.step("adapter(exit)", () -> hookAdapter(cl));
    }

    // ------------------------------------------------------------------ NewTaskFragment

    private static void hookNewTaskFragment(ClassLoader cl) {
        final Class<?> fragment = XposedHelpers.findClass(CLASS_NEW_TASK_FRAGMENT, cl);
        final Class<?> defaultItem = XposedHelpers.findClass(CLASS_DEFAULT_TASK_ITEM, cl);

        XposedHelpers.findAndHookMethod(fragment, "onCreatePreferences", android.os.Bundle.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object exitList = XposedHelpers.getObjectField(param.thisObject, "c");
                        if (exitList == null) {
                            return;
                        }
                        // G(true)：末尾出现「添加条件」行；H(true)：每项带删除按钮
                        XposedHelpers.callMethod(exitList, "G", true);
                        XposedHelpers.callMethod(exitList, "H", true);
                        // 退出条件分类常驻，这样删光了还能再加
                        Object category = XposedHelpers.getObjectField(param.thisObject, "q");
                        if (category != null) {
                            XposedHelpers.callMethod(category, "setVisible", true);
                        }
                    }
                });

        // private synthetic void M0(int)：退出条件数量变化。原生数量为 0 时隐藏分类并取消勾选，
        // 这会把刚删光准备重加的用户挡在外面，改成只更新摘要
        Method onExitCountChanged = null;
        try {
            onExitCountChanged = fragment.getDeclaredMethod("M0", int.class);
        } catch (NoSuchMethodException ignored) {
            // fall through
        }
        if (onExitCountChanged != null) {
            XposedBridge.hookMethod(onExitCountChanged, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object category = XposedHelpers.getObjectField(param.thisObject, "q");
                    if (category != null) {
                        XposedHelpers.callMethod(category, "setVisible", true);
                    }
                    XposedHelpers.callMethod(param.thisObject, "T0", param.args[0]);
                    param.setResult(null);
                }
            });
        } else {
            XposedBridge.log(TAG + ": NewTaskFragment.M0(int) 未找到，删光退出条件后分类会被隐藏");
        }

        // 从「添加条件」页选回来的退出条件：直接进退出列表，勾选为启用
        XposedHelpers.findAndHookMethod(fragment, "onActivityResult", int.class, int.class, Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int request = (Integer) param.args[0];
                        int result = (Integer) param.args[1];
                        Intent data = (Intent) param.args[2];
                        if (request != REQUEST_ADD_EXIT_CONDITION || result != Activity.RESULT_OK || data == null) {
                            return;
                        }
                        Serializable extra = data.getSerializableExtra("taskItem");
                        Object exitList = XposedHelpers.getObjectField(param.thisObject, "c");
                        if (extra == null || exitList == null) {
                            return;
                        }
                        XposedHelpers.callMethod(extra, "p", true);
                        insertBeforeAddRow(exitList, extra, defaultItem);
                    }
                });
    }

    // ------------------------------------------------------------------ RecyclerViewPreference

    private static void hookRecyclerPreference(ClassLoader cl) {
        final Class<?> pref = XposedHelpers.findClass(CLASS_RECYCLER_PREF, cl);
        final Class<?> listener = XposedHelpers.findClass(CLASS_RECYCLER_PREF_LISTENER, cl);
        final Class<?> defaultItem = XposedHelpers.findClass(CLASS_DEFAULT_TASK_ITEM, cl);
        final Class<?> addBaseActivity = XposedHelpers.findClass(CLASS_ADD_BASE_ACTIVITY, cl);
        final Class<?> addConditionActivity = XposedHelpers.findClass(CLASS_ADD_CONDITION_ACTIVITY, cl);

        // 列表项点击
        XposedHelpers.findAndHookMethod(listener, "onItemClick", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object self = XposedHelpers.getObjectField(param.thisObject, "a");
                if (self == null || !XposedHelpers.getBooleanField(self, "h")) {
                    return;
                }
                int position = (Integer) param.args[0];
                List<?> items = (List<?>) XposedHelpers.getObjectField(self, "b");
                if (items == null || position < 0 || position >= items.size()) {
                    return;
                }
                Object item = items.get(position);
                String key = (String) XposedHelpers.callMethod(item, "e");
                if (KEY_ADD_CONDITION_ROW.equals(key)) {
                    // 「添加」行：打开添加条件页，已在退出列表里的和「自定义时间」置灰
                    param.setResult(null);
                    Activity activity = (Activity) XposedHelpers.getObjectField(self, "a");
                    if (activity == null) {
                        return;
                    }
                    ArrayList<String> unable = new ArrayList<>();
                    Object keys = XposedHelpers.callMethod(self, "u");
                    if (keys instanceof List) {
                        for (Object k : (List<?>) keys) {
                            unable.add(String.valueOf(k));
                        }
                    }
                    unable.add(KEY_CUSTOM_TIME_CONDITION);
                    XposedHelpers.callStaticMethod(addBaseActivity, "L0",
                            activity, unable, REQUEST_ADD_EXIT_CONDITION, addConditionActivity);
                    return;
                }
                // 已有项：借触发列表那套编辑分发（选应用页 / 各类对话框），临时把 h 置 false，
                // 原方法返回后在 after 里恢复。它会记下 e = position，编辑结果回来时按位置替换
                XposedHelpers.setBooleanField(self, "h", false);
                XposedHelpers.setAdditionalInstanceField(param.thisObject, "restoreExitFlag", Boolean.TRUE);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object restore = XposedHelpers.removeAdditionalInstanceField(param.thisObject, "restoreExitFlag");
                if (restore != null) {
                    Object self = XposedHelpers.getObjectField(param.thisObject, "a");
                    if (self != null) {
                        XposedHelpers.setBooleanField(self, "h", true);
                    }
                }
            }
        });

        // 编辑页把各种选择结果回灌给列表
        XposedHelpers.findAndHookMethod(pref, "B", int.class, int.class, Intent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object self = param.thisObject;
                if (!XposedHelpers.getBooleanField(self, "h")) {
                    return;
                }
                int request = (Integer) param.args[0];
                int result = (Integer) param.args[1];
                Intent data = (Intent) param.args[2];
                if (result != Activity.RESULT_OK || data == null) {
                    // 原方法在这种情况下会直接解引用 intent，替它把编辑位置清掉后拦下
                    XposedHelpers.setIntField(self, "e", -1);
                    param.setResult(null);
                    return;
                }
                Serializable extra = data.getSerializableExtra("taskItem");
                if (extra == null) {
                    param.setResult(null);
                    return;
                }
                if (request == REQUEST_ADD_CONDITION) {
                    // 触发条件新增 -> 自动加反向条件。原生只在没有「添加」行时这么做，这里自己来
                    param.setResult(null);
                    Object opposite = XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("g2.M0", cl), "t", extra);
                    if (opposite == null) {
                        return;
                    }
                    XposedHelpers.callMethod(opposite, "p", true);
                    insertBeforeAddRow(self, opposite, defaultItem);
                    return;
                }
                if (request == REQUEST_EDIT_CONDITION || request == REQUEST_EDIT_APP_CONDITION) {
                    int at = XposedHelpers.getIntField(self, "e");
                    if (at < 0) {
                        // 不是退出项自己在编辑，是触发条件改了要同步反向条件，交给原生 t(item)
                        return;
                    }
                    param.setResult(null);
                    XposedHelpers.setIntField(self, "e", -1);
                    List<Object> items = itemList(self);
                    if (items == null || at >= items.size() || defaultItem.isInstance(items.get(at))) {
                        return;
                    }
                    boolean checked = (Boolean) XposedHelpers.callMethod(items.get(at), "k");
                    XposedHelpers.callMethod(extra, "p", checked);
                    items.set(at, extra);
                    Object adapter = XposedHelpers.getObjectField(self, "c");
                    XposedHelpers.callMethod(adapter, "notifyItemChanged", at);
                }
            }
        });
    }

    // ------------------------------------------------------------------ 列表适配器 Y1.v

    private static void hookAdapter(ClassLoader cl) {
        Class<?> adapter = XposedHelpers.findClass(CLASS_ADAPTER, cl);
        Class<?> holder = XposedHelpers.findClass(CLASS_ADAPTER_HOLDER, cl);
        final Class<?> defaultItem = XposedHelpers.findClass(CLASS_DEFAULT_TASK_ITEM, cl);

        // public void o(v$c holder, int position)：真正的 onBindViewHolder
        Method bind = HookUtils.findMethod(adapter, "o", void.class, holder, int.class);
        if (bind == null) {
            XposedBridge.log(TAG + ": Y1.v 的 onBindViewHolder 未找到，退出列表「添加」行会带勾选框");
            return;
        }
        XposedBridge.hookMethod(bind, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object self = param.thisObject;
                // e：这是退出条件列表
                if (!XposedHelpers.getBooleanField(self, "e")) {
                    return;
                }
                List<?> items = (List<?>) XposedHelpers.getObjectField(self, "a");
                int position = (Integer) param.args[1];
                if (items == null || position < 0 || position >= items.size()) {
                    return;
                }
                Object viewHolder = param.args[0];
                CheckBox check = (CheckBox) XposedHelpers.getObjectField(viewHolder, "f");
                ImageView delete = (ImageView) XposedHelpers.getObjectField(viewHolder, "d");
                Object item = items.get(position);
                if (defaultItem.isInstance(item)) {
                    if (check != null) {
                        check.setVisibility(View.GONE);
                    }
                    if (delete != null) {
                        delete.setVisibility(View.GONE);
                    }
                    return;
                }
                int real = 0;
                for (Object o : items) {
                    if (!defaultItem.isInstance(o)) {
                        real++;
                    }
                }
                // 只有一条退出条件时没得选，隐藏勾选框并保证它是启用的（与原生一致）
                if (real <= 1) {
                    if (check != null) {
                        check.setVisibility(View.GONE);
                    }
                    XposedHelpers.callMethod(item, "p", true);
                }
            }
        });
    }

    // ------------------------------------------------------------------ 工具

    @SuppressWarnings("unchecked")
    private static List<Object> itemList(Object recyclerPref) {
        Object list = XposedHelpers.getObjectField(recyclerPref, "b");
        return list instanceof List ? (List<Object>) list : null;
    }

    /** 插到「添加」行之前，并像原生一样在列表从空变非空时把「添加」行切成小样式 */
    private static void insertBeforeAddRow(Object recyclerPref, Object item, Class<?> defaultItem) {
        List<Object> items = itemList(recyclerPref);
        if (items == null) {
            return;
        }
        boolean hasAddRow = !items.isEmpty() && defaultItem.isInstance(items.get(items.size() - 1));
        if (hasAddRow && items.size() == 1) {
            XposedHelpers.callMethod(recyclerPref, "I", false);
        }
        int at = hasAddRow ? items.size() - 1 : items.size();
        items.add(at, item);
        Object adapter = XposedHelpers.getObjectField(recyclerPref, "c");
        XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
        XposedHelpers.callMethod(recyclerPref, "D");
    }
}
