package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Toast;

import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Separate native result lists backed by one serializable delayed-execution plan. */
final class ThenExecutionHook {
    private static final String PREFIX = "hyperauto.then.";
    private static final String STATE = PREFIX + "state";
    private static final String GROUP = PREFIX + "group";
    private static final String RESTORE = PREFIX + "restore";
    private static final String SNAPSHOT = PREFIX + "draft";
    private static final String INIT_ERROR = PREFIX + "initializationError";
    private static final String FLASHING = PREFIX + "flashing";
    private static final String RUNTIME = "ThenEditorSupport";
    private static final String ADD_KEY = "key_result_list";
    private static final int REQUEST_ADD = 0x6A11;
    private static final int REQUEST_EDIT_APP = 0x6A12;
    private static final String SECTION_TITLE = "\u518d\u6267\u884c";
    private static final String CHECKBOX_WIDGET = "miuix_preference_widget_checkbox";
    private static final String HEADER_CLASS = "com.miui.permcenter.settings.model.FlexiblePreference";
    /** 「添加再执行」按钮变蓝后停留多久再回到灰色 */
    private static final long FLASH_MS = 500L;

    private static volatile int sCheckboxWidgetId;

    private ThenExecutionHook() {}

    static void install(XC_LoadPackage.LoadPackageParam lp) {
        ClassLoader cl = lp.classLoader;
        Class<?> fragment = XposedHelpers.findClass("com.miui.autotask.fragment.NewTaskFragment", cl);
        Class<?> activity = XposedHelpers.findClass("com.miui.autotask.activity.NewTaskActivity", cl);
        Class<?> preference = XposedHelpers.findClass("com.miui.autotask.view.RecyclerViewPreference", cl);
        Class<?> listener = XposedHelpers.findClass("com.miui.autotask.view.RecyclerViewPreference$a", cl);
        Method save = HookUtils.findMethod(fragment, new String[]{"S0", "P0"}, void.class);
        Method unchanged = HookUtils.findMethod(fragment, new String[]{"R0", "O0"}, boolean.class);
        Method refresh = HookUtils.findMethod(fragment, new String[]{"G0", "D0"}, void.class);
        if (save == null || unchanged == null || refresh == null) {
            throw new IllegalStateException("NewTaskFragment delayed editor methods unavailable");
        }

        XposedHelpers.findAndHookMethod(preference, "v", String.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                State state = state(param.thisObject);
                if (state == null || !state.ready || param.thisObject != state.results
                        || !(param.getResult() instanceof List)) {
                    return;
                }
                Object plan;
                try {
                    plan = buildPlan(state);
                } catch (Throwable t) {
                    state.error = failure(t);
                    plan = support("copyPlan", state.plan);
                }
                if (!((Boolean) XposedHelpers.callMethod(plan, "hasGroups"))) return;
                XposedHelpers.callMethod(plan, "s", param.args[0]);
                List<Object> result = new ArrayList<>((List<?>) param.getResult());
                result.add(plan);
                param.setResult(result);
            }
        });

        XposedHelpers.findAndHookMethod(preference, "D", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                State state = state(param.thisObject);
                if (state != null) updateCount(state);
            }
        });

        XposedHelpers.findAndHookMethod(listener, "onItemClick", int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object preference = XposedHelpers.getObjectField(param.thisObject, "a");
                GroupUi group = group(preference);
                State state = state(preference);
                if (group == null || state == null) return;
                List<Object> items = rawItems(preference);
                int at = (Integer) param.args[0];
                if (at < 0 || at >= items.size()) {
                    param.setResult(null);
                    return;
                }
                Object item = items.get(at);
                if (state.defaultItem.isInstance(item)) {
                    param.setResult(null);
                    safely(state, () -> openAdd(state, group));
                } else if (state.lunchApp.isInstance(item)) {
                    param.setResult(null);
                    safely(state, () -> openAppEditor(state, group, at, item));
                }
                // Other rows use the existing G0 dispatcher, including injected result editors.
            }
        });

        HookUtils.step("再执行添加按钮", () -> {
            Class<?> textPreference = XposedHelpers.findClass("miuix.preference.TextPreference", cl);
            Method bind = findBindMethod(textPreference);
            if (bind == null) throw new IllegalStateException("TextPreference.onBindViewHolder 未找到");
            XposedBridge.hookMethod(bind, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    State state = state(param.thisObject);
                    if (state == null || !state.ready || param.thisObject != state.add) return;
                    View view = itemView(param.args[0]);
                    if (view == null) return;
                    bindAddSwitch(state, view);
                }
            });
            // 点添加行：androidx 绑定时给 itemView 挂的桥最终调 performClick()，这里截下来走开关的闪蓝添加
            XposedHelpers.findAndHookMethod("androidx.preference.Preference", cl, "performClick",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            State state = state(param.thisObject);
                            if (state == null || !state.ready || param.thisObject != state.add) return;
                            param.setResult(null);
                            flashOrAdd(state);
                        }
                    });
        });

        HookUtils.step("再执行表头开关", () -> {
            Class<?> header = XposedHelpers.findClass(HEADER_CLASS, cl);
            Method bind = findBindMethod(header);
            if (bind == null) throw new IllegalStateException("FlexiblePreference.onBindViewHolder 未找到");
            XposedBridge.hookMethod(bind, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    State state = state(param.thisObject);
                    GroupUi group = group(param.thisObject);
                    if (state == null || !state.ready || group == null || !state.groups.contains(group)) return;
                    View view = itemView(param.args[0]);
                    if (view == null) return;
                    bindGroupSwitch(state, group, view);
                }
            });
        });

        XposedHelpers.findAndHookMethod(fragment, "onActivityResult", int.class, int.class,
                Intent.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        int request = (Integer) param.args[0];
                        if (request != REQUEST_ADD && request != REQUEST_EDIT_APP) return;
                        param.setResult(null);
                        State state = state(param.thisObject);
                        if (state == null || state.pendingRequest != request) return;
                        safely(state, () -> receive(state, request, (Integer) param.args[1],
                                (Intent) param.args[2]));
                    }
                });

        XposedBridge.hookMethod(unchanged, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (XposedHelpers.getAdditionalInstanceField(param.thisObject, INIT_ERROR) != null) {
                    param.setResult(false);
                    return;
                }
                State state = state(param.thisObject);
                if (state == null) return;
                if (state.error != null || (XposedHelpers.getObjectField(param.thisObject, "g") == null
                        && !state.groups.isEmpty())) {
                    param.setResult(false);
                }
            }
        });

        XposedBridge.hookMethod(save, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object initializationError = XposedHelpers.getAdditionalInstanceField(param.thisObject, INIT_ERROR);
                if (initializationError != null) {
                    Context context = (Context) XposedHelpers.callMethod(param.thisObject, "getActivity");
                    if (context != null) Toast.makeText(context, String.valueOf(initializationError), Toast.LENGTH_LONG).show();
                    param.setResult(null);
                    return;
                }
                State state = state(param.thisObject);
                if (state == null) return;
                try {
                    String error = (String) support("validationError", buildPlan(state));
                    if (error != null && !error.isEmpty()) {
                        toast(state, error);
                        param.setResult(null);
                    }
                } catch (Throwable t) {
                    toast(state, failure(t));
                    param.setResult(null);
                }
            }
        });

        hookLifecycle(activity, fragment, cl);
        XposedHelpers.findAndHookMethod(fragment, "onCreatePreferences", Bundle.class,
                String.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        State previous = state(param.thisObject);
                        if (previous == null || !previous.ready) return;
                        try {
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, RESTORE, snapshot(previous));
                        } catch (Throwable t) {
                            previous.error = failure(t);
                        }
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable()) return;
                        boolean protect = requiresInitializationProtection(param.thisObject,
                                (Bundle) param.args[0], cl);
                        try {
                            if (DexInjector.runtime(RUNTIME) == null) {
                                if (!protect) return;
                                throw new IllegalStateException("Delayed editor runtime unavailable");
                            }
                            support("configure", TargetResolver.factory(cl));
                            attach(param.thisObject, (Bundle) param.args[0], cl, refresh);
                            XposedHelpers.removeAdditionalInstanceField(param.thisObject, INIT_ERROR);
                        } catch (Throwable t) {
                            XposedBridge.log(MainHook.TAG + ": delayed editor initialization failed");
                            XposedBridge.log(t);
                            String error = recordInitializationFailure(param.thisObject, protect);
                            Context context = (Context) XposedHelpers.callMethod(param.thisObject, "getActivity");
                            if (context != null) Toast.makeText(context, error,
                                    protect ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    /** Inspect before attach can hide the storage item or replace the previous editor state. */
    private static boolean requiresInitializationProtection(Object fragment, Bundle saved, ClassLoader cl) {
        try {
            Object results = XposedHelpers.getObjectField(fragment, "b");
            if (results == null) return true;
            for (Object item : rawItems(results)) {
                if (FirstAppKeys.KEY_THEN_EXECUTION_RESULT.equals(XposedHelpers.callMethod(item, "e"))) return true;
            }
            Object activity = XposedHelpers.callMethod(fragment, "getActivity");
            if (protectState(state(fragment)) || protectState(state(activity))) return true;
            if (protectRestoredDraft(XposedHelpers.getAdditionalInstanceField(fragment, RESTORE), cl)) return true;
            if (activity != null && protectRestoredDraft(
                    XposedHelpers.getAdditionalInstanceField(activity, RESTORE), cl)) return true;
            if (saved != null) {
                saved.setClassLoader(cl);
                if (saved.containsKey(SNAPSHOT)) {
                    Bundle draft = saved.getBundle(SNAPSHOT);
                    if (draft == null || protectRestoredDraft(draft, cl)) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": unable to determine delayed draft contents; protect save");
            XposedBridge.log(t);
            return true;
        }
    }

    private static boolean protectState(State state) {
        return state != null && (state.error != null || state.pendingRequest != 0
                || !state.groups.isEmpty() || protectPlan(state.plan));
    }

    private static boolean protectRestoredDraft(Object value, ClassLoader cl) {
        if (value == null) return false;
        if (!(value instanceof Bundle)) return true;
        Bundle draft = (Bundle) value;
        draft.setClassLoader(cl);
        return !draft.containsKey("plan") || draft.getInt("pendingRequest", 0) != 0
                || protectPlan(draft.getSerializable("plan"));
    }

    private static boolean protectPlan(Object plan) {
        return plan == null
                || !FirstAppKeys.KEY_THEN_EXECUTION_RESULT.equals(XposedHelpers.callMethod(plan, "e"))
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(plan, "isSupportedVersion"))
                || !Boolean.FALSE.equals(XposedHelpers.callMethod(plan, "hasGroups"));
    }

    private static String recordInitializationFailure(Object fragment, boolean protect) {
        if (protect) {
            String error = "\u65e0\u6cd5\u8f7d\u5165\u518d\u6267\u884c\uff0c\u5df2\u4fdd\u7559\u539f\u4efb\u52a1\uff1b\u4e3a\u9632\u6b62\u6570\u636e\u4e22\u5931\uff0c\u6682\u65f6\u4e0d\u80fd\u4fdd\u5b58";
            XposedHelpers.setAdditionalInstanceField(fragment, INIT_ERROR, error);
            return error;
        }
        XposedHelpers.removeAdditionalInstanceField(fragment, INIT_ERROR);
        State stale = state(fragment);
        if (stale != null) {
            stale.ready = false;
            XposedHelpers.removeAdditionalInstanceField(fragment, STATE);
            XposedHelpers.removeAdditionalInstanceField(stale.results, STATE);
            Activity activity = stale.activity.get();
            if (activity != null && state(activity) == stale) XposedHelpers.removeAdditionalInstanceField(activity, STATE);
            try {
                if (stale.add != null) XposedHelpers.callMethod(stale.screen, "removePreference", stale.add);
            } catch (Throwable t) {
                XposedBridge.log(MainHook.TAG + ": unable to detach unavailable delayed editor entry");
                XposedBridge.log(t);
            }
        }
        return "\u518d\u6267\u884c\u6682\u4e0d\u53ef\u7528\uff0c\u5176\u4ed6\u529f\u80fd\u4ecd\u53ef\u7f16\u8f91";
    }

    private static void hookLifecycle(Class<?> activityClass, Class<?> fragmentClass, ClassLoader cl) {
        XposedHelpers.findAndHookMethod(activityClass, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Bundle saved = (Bundle) param.args[0];
                if (saved == null) return;
                saved.setClassLoader(cl);
                Bundle draft = saved.getBundle(SNAPSHOT);
                if (draft != null) {
                    draft.setClassLoader(cl);
                    XposedHelpers.setAdditionalInstanceField(param.thisObject, RESTORE, draft);
                }
            }
        });
        Method saveState = HookUtils.findMethod(activityClass, "onSaveInstanceState", void.class, Bundle.class);
        if (saveState == null) return;
        XposedBridge.hookMethod(saveState, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!activityClass.isInstance(param.thisObject)) return;
                State state = state(param.thisObject);
                if (state == null || !state.ready) return;
                try {
                    ((Bundle) param.args[0]).putBundle(SNAPSHOT, snapshot(state));
                } catch (Throwable t) {
                    XposedBridge.log(MainHook.TAG + ": delayed editor draft snapshot failed");
                    XposedBridge.log(t);
                }
            }
        });
        Method destroy = HookUtils.findMethod(fragmentClass, "onDestroy", void.class);
        if (destroy != null) XposedBridge.hookMethod(destroy, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!fragmentClass.isInstance(param.thisObject)) return;
                State state = state(param.thisObject);
                if (state == null || !state.ready) return;
                Activity activity = state.activity.get();
                if (activity == null || activity.isFinishing() || state(activity) != state) return;
                try {
                    // NewTaskActivity replaces even a fragment restored by its superclass.
                    XposedHelpers.setAdditionalInstanceField(activity, RESTORE, snapshot(state));
                } catch (Throwable t) {
                    XposedBridge.log(MainHook.TAG + ": delayed editor fragment handoff failed");
                    XposedBridge.log(t);
                }
            }
        });
    }

    private static void attach(Object fragment, Bundle fragmentSaved, ClassLoader cl, Method refresh) {
        Activity activity = (Activity) XposedHelpers.callMethod(fragment, "getActivity");
        if (activity == null) return;
        Object results = XposedHelpers.getObjectField(fragment, "b");
        Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
        if (results == null || screen == null) return;
        State current = state(fragment);
        if (current != null && current.ready && current.screen == screen) return;
        Object plan = null;
        for (Object item : rawItems(results)) {
            if ((Boolean) support("isPlan", item)) {
                if (plan != null) throw new IllegalStateException("Multiple delayed execution storage items");
                plan = item;
            }
        }
        if (plan != null && !((Boolean) XposedHelpers.callMethod(plan, "hasGroups"))) {
            throw new IllegalStateException("Empty stored delayed execution plan");
        }
        Bundle saved = (Bundle) XposedHelpers.getAdditionalInstanceField(fragment, RESTORE);
        if (saved == null) {
            State previous = state(activity);
            if (previous != null && previous.ready && previous.fragment.get() != null
                    && previous.fragment.get() != fragment) saved = snapshot(previous);
        }
        if (saved == null) saved = (Bundle) XposedHelpers.getAdditionalInstanceField(activity, RESTORE);
        if (saved == null && fragmentSaved != null) saved = fragmentSaved.getBundle(SNAPSHOT);
        if (saved != null) {
            saved.setClassLoader(cl);
            Object savedPlan = saved.getSerializable("plan");
            if (savedPlan != null && (Boolean) support("isPlan", savedPlan)) plan = savedPlan;
        }
        plan = plan == null ? support("newPlan") : support("copyPlan", plan);
        State state = new State(fragment, activity, results, screen, plan, cl, refresh);
        int count = (Integer) XposedHelpers.callMethod(screen, "getPreferenceCount");
        List<Integer> oldOrders = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Object pref = XposedHelpers.callMethod(screen, "getPreference", i);
            state.originalPreferences.add(pref);
            oldOrders.add((Integer) XposedHelpers.callMethod(pref, "getOrder"));
        }
        Set<String> ids = new HashSet<>();
        for (Object model : (List<?>) support("groups", plan)) {
            GroupUi group = createGroup(state, model);
            if (group.id.isEmpty() || "null".equals(group.id) || !ids.add(group.id)) {
                throw new IllegalStateException("Invalid delayed execution group identifier");
            }
            state.groups.add(group);
        }
        state.add = newPreference(state, "miuix.preference.TextPreference", PREFIX + "add",
                "\u6dfb\u52a0\u518d\u6267\u884c");
        int switchWidget = checkboxWidgetId(activity);
        if (switchWidget != 0) XposedHelpers.callMethod(state.add, "setWidgetLayoutResource", switchWidget);
        // 不挂 preference 级点击监听：miuix 适配器按「有 intent / fragment / 点击监听」才显示行末箭头，
        // 不挂就保持原生 GONE；点行由 Preference.performClick 钩子接管，改为闪一下行末开关再加一组
        associate(state.add, STATE, state);

        // The hidden storage item is removed only inside a rollback-capable UI transaction.
        List<Object> originalItems = new ArrayList<>(rawItems(results));
        try {
            for (GroupUi group : state.groups) mount(state, group);
            XposedHelpers.callMethod(screen, "addPreference", state.add);
            rawItems(results).removeIf(item -> (Boolean) support("isPlan", item));
            associate(fragment, STATE, state);
            associate(activity, STATE, state);
            associate(results, STATE, state);
            if (saved != null) restoreNative(state, saved);
            reorder(state);
            state.ready = true;
            updateCount(state);
            XposedHelpers.removeAdditionalInstanceField(fragment, RESTORE);
            XposedHelpers.removeAdditionalInstanceField(activity, RESTORE);
        } catch (Throwable t) {
            state.ready = false;
            rawItems(results).clear();
            rawItems(results).addAll(originalItems);
            XposedHelpers.removeAdditionalInstanceField(fragment, STATE);
            XposedHelpers.removeAdditionalInstanceField(activity, STATE);
            XposedHelpers.removeAdditionalInstanceField(results, STATE);
            for (GroupUi group : state.groups) detach(state, group);
            XposedHelpers.callMethod(screen, "removePreference", state.add);
            for (int i = 0; i < oldOrders.size(); i++) {
                XposedHelpers.callMethod(state.originalPreferences.get(i), "setOrder", oldOrders.get(i));
            }
            notifyList(results);
            throw t;
        }
    }

    private static GroupUi createGroup(State state, Object model) {
        String id = String.valueOf(XposedHelpers.callMethod(model, "getId"));
        GroupUi group = new GroupUi(model, id);
        // 表头复用原生「如果运行 / 就执行」那个 FlexiblePreference，字号颜色完全一致；标题不带序号
        group.header = XposedHelpers.newInstance(state.header, state.activity.get());
        XposedHelpers.callMethod(group.header, "setTitle", SECTION_TITLE);
        XposedHelpers.callMethod(group.header, "setSelectable", false);
        XposedHelpers.callMethod(group.header, "setPersistent", false);
        int switchWidget = checkboxWidgetId(state.activity.get());
        if (switchWidget != 0) XposedHelpers.callMethod(group.header, "setWidgetLayoutResource", switchWidget);
        associate(group.header, STATE, state);
        associate(group.header, GROUP, group);
        // 卡片本身对应一个无标题的原生分类（原生 exit_category 就是分类里装 RecyclerViewPreference）。
        // 宿主的 PreferenceFragment 装饰只给分类的子项合并画一张卡片背景，所以标题交给上面的表头。
        group.section = XposedHelpers.newInstance(state.category, state.activity.get());
        XposedHelpers.callMethod(group.section, "setKey", PREFIX + id + ".section");
        XposedHelpers.callMethod(group.section, "setPersistent", false);
        group.delay = newPreference(state, "miuix.preference.TextPreference", PREFIX + id + ".delay",
                "\u7b49\u5f85\u65f6\u95f4");
        refreshDelay(group);
        click(group.delay, () -> safely(state, () -> support("pickDelay", state.activity.get(), model,
                (Runnable) () -> {
                    if (!state.groups.contains(group)) return;
                    refreshDelay(group);
                    updateCount(state);
                })));

        Activity activity = state.activity.get();
        group.results = XposedHelpers.newInstance(state.preference, activity);
        XposedHelpers.callMethod(group.results, "setKey", PREFIX + id + ".results");
        XposedHelpers.callMethod(group.results, "setPersistent", false);
        int layout = activity.getResources().getIdentifier("auto_task_item_recycler_view", "layout",
                activity.getPackageName());
        if (layout == 0) throw new IllegalStateException("Native result list layout unavailable");
        XposedHelpers.callMethod(group.results, "setLayoutResource", layout);
        XposedHelpers.callMethod(group.results, "E", activity);
        XposedHelpers.callMethod(group.results, "G", true);
        XposedHelpers.callMethod(group.results, "H", true);
        rawItems(group.results).addAll((List<?>) support("decodeActions", model));
        ensureAddRow(state, group.results);
        associate(group.results, STATE, state);
        associate(group.results, GROUP, group);

        int order = 0;
        for (Object child : group.children()) XposedHelpers.callMethod(child, "setOrder", order++);
        return group;
    }

    private static Object newPreference(State state, String className, String key, String title) {
        Object pref = XposedHelpers.newInstance(XposedHelpers.findClass(className, state.loader), state.activity.get());
        XposedHelpers.callMethod(pref, "setKey", key);
        XposedHelpers.callMethod(pref, "setTitle", title);
        XposedHelpers.callMethod(pref, "setPersistent", false);
        return pref;
    }

    private static void mount(State state, GroupUi group) {
        // 表头与分类先挂到已附加的 PreferenceScreen 上，子项再加进去才能拿到 PreferenceManager
        XposedHelpers.callMethod(state.screen, "addPreference", group.header);
        XposedHelpers.callMethod(state.screen, "addPreference", group.section);
        for (Object child : group.children()) XposedHelpers.callMethod(group.section, "addPreference", child);
    }

    /** 先摘子项再摘分类和表头，避免宿主分类移除时不清理子项状态 */
    private static void detach(State state, GroupUi group) {
        for (Object child : group.children()) {
            try {
                XposedHelpers.callMethod(group.section, "removePreference", child);
            } catch (Throwable t) {
                XposedBridge.log(MainHook.TAG + ": unable to detach delayed group child");
                XposedBridge.log(t);
            }
        }
        XposedHelpers.callMethod(state.screen, "removePreference", group.section);
        XposedHelpers.callMethod(state.screen, "removePreference", group.header);
    }

    private static void reorder(State state) {
        int order = 0;
        for (Object original : state.originalPreferences) {
            XposedHelpers.callMethod(original, "setOrder", order++);
            if (original != state.results) continue;
            for (GroupUi group : state.groups) {
                XposedHelpers.callMethod(group.header, "setOrder", order++);
                XposedHelpers.callMethod(group.section, "setOrder", order++);
            }
            XposedHelpers.callMethod(state.add, "setOrder", order++);
        }
    }

    private static void refreshDelay(GroupUi group) {
        XposedHelpers.callMethod(group.delay, "setSummary", support("delaySummary", group.model));
    }

    /** 删除整组：确认后移除；弹窗以任何方式收起且组还在时（取消 / 返回 / 点弹窗外）执行 onCancel */
    private static void confirmRemove(State state, GroupUi group, Runnable onCancel) {
        Activity activity = state.activity.get();
        if (activity == null || activity.isFinishing()) return;
        Object builder = XposedHelpers.newInstance(
                XposedHelpers.findClass("miuix.appcompat.app.AlertDialog$Builder", state.loader), activity);
        XposedHelpers.callMethod(builder, "setTitle", "\u5220\u9664\u518d\u6267\u884c");
        XposedHelpers.callMethod(builder, "setMessage",
                "\u8fd9\u7ec4\u7684\u7b49\u5f85\u65f6\u95f4\u548c\u6240\u6709\u6267\u884c\u9879\u76ee\u90fd\u4f1a\u88ab\u5220\u9664\u3002");
        DialogInterface.OnClickListener confirm = (dialog, which) -> safely(state, () -> {
            if (!state.groups.remove(group)) return;
            detach(state, group);
            reorder(state);
            updateCount(state);
        });
        Class<?>[] signature = {int.class, DialogInterface.OnClickListener.class};
        XposedHelpers.callMethod(builder, "setPositiveButton", signature, android.R.string.ok, confirm);
        XposedHelpers.callMethod(builder, "setNegativeButton", signature, android.R.string.cancel, null);
        Object dialogObject = XposedHelpers.callMethod(builder, "show");
        if (onCancel != null) {
            Class<?>[] dismissSignature = {DialogInterface.OnDismissListener.class};
            XposedHelpers.callMethod(dialogObject, "setOnDismissListener", dismissSignature,
                    (DialogInterface.OnDismissListener) dismissed -> {
                        if (state.groups.contains(group)) onCancel.run();
                    });
        }
    }

    private static void openAdd(State state, GroupUi group) {
        Activity activity = state.activity.get();
        if (activity == null || activity.isFinishing()) return;
        ArrayList<String> keys = new ArrayList<>();
        for (Object item : actions(state, group.results)) keys.add((String) XposedHelpers.callMethod(item, "e"));
        Object blocked = XposedHelpers.callStaticMethod(TargetResolver.factory(state.loader), "K", keys);
        Intent intent = new Intent(activity,
                XposedHelpers.findClass("com.miui.autotask.activity.AddResultActivity", state.loader));
        intent.putStringArrayListExtra("unableKeyList", (ArrayList<String>) blocked);
        pending(state, group, REQUEST_ADD, -1);
        try {
            activity.startActivityForResult(intent, REQUEST_ADD);
        } catch (Throwable t) {
            clearPending(state);
            throw t;
        }
    }

    private static void openAppEditor(State state, GroupUi group, int at, Object item) {
        Activity activity = state.activity.get();
        if (activity == null || activity.isFinishing()) return;
        Class<?> selector = XposedHelpers.findClass("com.miui.autotask.activity.SelectAppActivity", state.loader);
        Method open = HookUtils.findMethod(selector, new String[]{"d1", "e1", "S0"}, void.class,
                Activity.class, state.lunchApp, int.class);
        if (open == null) throw new IllegalStateException("Native application result editor unavailable");
        pending(state, group, REQUEST_EDIT_APP, at);
        try {
            HookUtils.invokeMethod(open, null, activity, item, REQUEST_EDIT_APP);
        } catch (Throwable t) {
            clearPending(state);
            throw t;
        }
    }

    private static void receive(State state, int request, int result, Intent data) {
        String id = state.pendingId;
        int at = state.pendingPosition;
        clearPending(state);
        GroupUi group = null;
        for (GroupUi candidate : state.groups) if (candidate.id.equals(id)) group = candidate;
        if (group == null || result != Activity.RESULT_OK || data == null) return;
        data.setExtrasClassLoader(state.loader);
        Object item = data.getSerializableExtra("taskItem");
        if (!state.taskItem.isInstance(item) || state.defaultItem.isInstance(item)
                || (Boolean) support("isPlan", item)) return;
        List<Object> items = rawItems(group.results);
        if (request == REQUEST_ADD) {
            String key = (String) XposedHelpers.callMethod(item, "e");
            for (Object old : actions(state, group.results)) {
                if (key.equals(XposedHelpers.callMethod(old, "e"))) return;
            }
            int insert = items.size();
            if (insert > 0 && state.defaultItem.isInstance(items.get(insert - 1))) insert--;
            items.add(insert, item);
        } else {
            if (at < 0 || at >= items.size() || state.defaultItem.isInstance(items.get(at))) return;
            items.set(at, item);
        }
        XposedHelpers.setIntField(group.results, "e", -1);
        ensureAddRow(state, group.results);
        notifyList(group.results);
        XposedHelpers.callMethod(group.results, "D");
    }

    private static void pending(State state, GroupUi group, int request, int at) {
        state.pendingId = group.id;
        state.pendingRequest = request;
        state.pendingPosition = at;
    }

    private static void clearPending(State state) {
        state.pendingId = null;
        state.pendingRequest = 0;
        state.pendingPosition = -1;
    }

    private static Object buildPlan(State state) {
        List<Object> groups = new ArrayList<>();
        for (GroupUi group : state.groups) {
            support("setActions", group.model, actions(state, group.results));
            groups.add(group.model);
        }
        Object plan = support("copyPlan", state.plan);
        support("setGroups", plan, groups);
        state.error = null;
        return plan;
    }

    private static void updateCount(State state) {
        if (!state.ready) return;
        Object fragment = state.fragment.get();
        if (fragment == null) return;
        int count = actions(state, state.results).size();
        for (GroupUi group : state.groups) {
            if (!actions(state, group.results).isEmpty()) {
                count++;
                break;
            }
        }
        XposedHelpers.setIntField(fragment, "f", count);
        HookUtils.invokeMethod(state.refresh, fragment);
    }

    private static Bundle snapshot(State state) {
        Bundle saved = new Bundle();
        Object fragment = state.fragment.get();
        saved.putSerializable("plan", (Serializable) buildPlan(state));
        saved.putString("pendingId", state.pendingId);
        saved.putInt("pendingRequest", state.pendingRequest);
        saved.putInt("pendingPosition", state.pendingPosition);
        if (fragment == null) return saved;
        saved.putString("title", (String) XposedHelpers.callMethod(XposedHelpers.getObjectField(fragment, "d"), "getText"));
        for (String field : new String[]{"a", "b", "c"}) {
            Object pref = XposedHelpers.getObjectField(fragment, field);
            saved.putSerializable("items." + field, new ArrayList<>(actions(state, pref)));
            saved.putInt("editPosition." + field, XposedHelpers.getIntField(pref, "e"));
        }
        for (String field : new String[]{"m", "n", "o", "p"}) {
            saved.putBoolean("checked." + field,
                    (Boolean) XposedHelpers.callMethod(XposedHelpers.getObjectField(fragment, field), "isChecked"));
        }
        // Native editors share item instances with taskBean. Retain the original dirty-check baselines.
        for (String field : new String[]{"j", "k", "l"}) {
            Object map = XposedHelpers.getObjectField(fragment, field);
            if (map instanceof HashMap) saved.putSerializable("baseline." + field, new HashMap<>((HashMap<?, ?>) map));
        }
        return saved;
    }

    private static void restoreNative(State state, Bundle saved) {
        Object fragment = state.fragment.get();
        if (fragment == null) return;
        state.pendingId = saved.getString("pendingId");
        state.pendingRequest = saved.getInt("pendingRequest");
        state.pendingPosition = saved.getInt("pendingPosition", -1);
        if (saved.containsKey("title")) {
            XposedHelpers.callMethod(XposedHelpers.getObjectField(fragment, "d"), "setText", saved.getString("title"));
        }
        for (String field : new String[]{"a", "b", "c"}) {
            Object restored = saved.getSerializable("items." + field);
            if (!(restored instanceof List)) continue;
            Object pref = XposedHelpers.getObjectField(fragment, field);
            List<Object> items = rawItems(pref);
            items.clear();
            items.addAll((List<?>) restored);
            XposedHelpers.setIntField(pref, "e", saved.getInt("editPosition." + field, -1));
            if (XposedHelpers.getBooleanField(pref, "f")) ensureAddRow(state, pref);
            notifyList(pref);
        }
        for (String field : new String[]{"m", "n", "o", "p"}) {
            if (saved.containsKey("checked." + field)) XposedHelpers.callMethod(
                    XposedHelpers.getObjectField(fragment, field), "setChecked", saved.getBoolean("checked." + field));
        }
        for (String field : new String[]{"j", "k", "l"}) {
            Object map = saved.getSerializable("baseline." + field);
            if (map instanceof HashMap) XposedHelpers.setObjectField(fragment, field, new HashMap<>((HashMap<?, ?>) map));
        }
        XposedHelpers.setIntField(fragment, "e", actions(state, XposedHelpers.getObjectField(fragment, "a")).size());
        XposedHelpers.callMethod(XposedHelpers.getObjectField(fragment, "c"), "setVisible",
                (Boolean) XposedHelpers.callMethod(XposedHelpers.getObjectField(fragment, "p"), "isChecked"));
    }

    private static void ensureAddRow(State state, Object preference) {
        List<Object> items = rawItems(preference);
        Object add = null;
        for (Object item : items) if (state.defaultItem.isInstance(item)) add = item;
        if (add == null) {
            String key = (String) XposedHelpers.callMethod(preference, "getKey");
            String row = key != null && key.contains("_condition_") ? "key_condition_list" : ADD_KEY;
            add = XposedHelpers.callStaticMethod(TargetResolver.factory(state.loader), "B", row);
            items.add(add);
        }
        XposedHelpers.callMethod(add, "u", actions(state, preference).isEmpty());
    }

    private static void notifyList(Object preference) {
        XposedHelpers.callMethod(XposedHelpers.getObjectField(preference, "c"), "notifyDataSetChanged");
    }

    private static List<Object> actions(State state, Object preference) {
        List<Object> result = new ArrayList<>();
        for (Object item : rawItems(preference)) {
            if (!state.defaultItem.isInstance(item) && !(Boolean) support("isPlan", item)) result.add(item);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rawItems(Object preference) {
        return (List<Object>) XposedHelpers.getObjectField(preference, "b");
    }

    private static Object support(String method, Object... args) {
        Class<?> runtime = DexInjector.runtime(RUNTIME);
        if (runtime == null) throw new IllegalStateException("Delayed editor runtime unavailable");
        return XposedHelpers.callStaticMethod(runtime, method, args);
    }

    private static void click(Object preference, Runnable clicked) {
        Method setter = null;
        for (Method method : preference.getClass().getMethods()) {
            if ("setOnPreferenceClickListener".equals(method.getName()) && method.getParameterTypes().length == 1) {
                setter = method;
                break;
            }
        }
        if (setter == null) throw new IllegalStateException("Preference click listener unavailable");
        Class<?> type = setter.getParameterTypes()[0];
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if ("onPreferenceClick".equals(method.getName())) {
                clicked.run();
                return true;
            }
            if ("hashCode".equals(method.getName())) return System.identityHashCode(self);
            if ("equals".equals(method.getName())) return self == args[0];
            if ("toString".equals(method.getName())) return "ThenPreferenceClick";
            return null;
        });
        HookUtils.invokeMethod(setter, preference, proxy);
    }

    /** 直接取声明的 onBindViewHolder，避免依赖被混淆的 PreferenceViewHolder 类名 */
    private static Method findBindMethod(Class<?> preferenceClass) {
        for (Class<?> current = preferenceClass; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!"onBindViewHolder".equals(method.getName()) || method.getParameterTypes().length != 1) {
                    continue;
                }
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    /** androidx.preference.PreferenceViewHolder.itemView，字段名变了就退化成扫描第一个 View 字段 */
    private static View itemView(Object holder) {
        if (holder == null) return null;
        try {
            Object view = XposedHelpers.getObjectField(holder, "itemView");
            if (view instanceof View) return (View) view;
        } catch (Throwable ignored) {
            // 版本差异，继续扫描字段
        }
        for (Class<?> current = holder.getClass(); current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        || !View.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    if (value instanceof View) return (View) value;
                } catch (Throwable ignored) {
                    // 这个字段读不了，继续找下一个
                }
            }
        }
        return null;
    }

    /** 「添加再执行」行：点行末开关或点这一行其它位置都加一组，按钮闪一下蓝色再自动回灰 */
    private static void bindAddSwitch(State state, View view) {
        try {
            View found = view.findViewById(android.R.id.checkbox);
            if (!(found instanceof CompoundButton)) return;
            final CompoundButton button = (CompoundButton) found;
            if (XposedHelpers.getAdditionalInstanceField(view, FLASHING) == null) button.setChecked(false);
            state.addView = new WeakReference<>(view);
            XposedHelpers.callMethod(button, "setOnPerformCheckedChangeListener",
                    (CompoundButton.OnCheckedChangeListener) (source, checked) -> {
                        if (!checked) {
                            XposedHelpers.removeAdditionalInstanceField(view, FLASHING);
                            return;
                        }
                        flashAdd(state, view, button);
                    });
            // 不覆盖 itemView 的点击监听：androidx 的 performClick 桥要留着，点行的添加走 performClick 钩子（见 install）
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": delayed add switch unavailable");
            XposedBridge.log(t);
        }
    }

    /** 点添加行（走 Preference.performClick 钩子，见 install）：也闪一下行末开关再加一组，两种点法反馈一致 */
    private static void flashOrAdd(State state) {
        View view = state.addView == null ? null : state.addView.get();
        View found = view == null ? null : view.findViewById(android.R.id.checkbox);
        if (view == null || !(found instanceof CompoundButton)) {
            safely(state, () -> addGroup(state));
            return;
        }
        flashAdd(state, view, (CompoundButton) found);
    }

    /** 行末开关闪蓝 500ms 并加一组；FLASHING 置位期间重绑不清掉蓝色 */
    private static void flashAdd(State state, View view, CompoundButton button) {
        XposedHelpers.setAdditionalInstanceField(view, FLASHING, Boolean.TRUE);
        button.setChecked(true);
        safely(state, () -> addGroup(state));
        view.postDelayed(() -> {
            XposedHelpers.removeAdditionalInstanceField(view, FLASHING);
            button.setChecked(false);
        }, FLASH_MS);
    }

    private static void addGroup(State state) {
        GroupUi group = createGroup(state, support("newGroup"));
        state.groups.add(group);
        mount(state, group);
        reorder(state);
        updateCount(state);
    }

    /** 「再执行」表头行末开关：关闭时确认后删除整组（取消则弹回），长按标题行同样删除整组 */
    private static void bindGroupSwitch(State state, GroupUi group, View view) {
        try {
            View found = view.findViewById(android.R.id.checkbox);
            if (!(found instanceof CompoundButton)) return;
            final CompoundButton button = (CompoundButton) found;
            button.setChecked((Boolean) XposedHelpers.callMethod(group.model, "isEnabled"));
            XposedHelpers.callMethod(button, "setOnPerformCheckedChangeListener",
                    (CompoundButton.OnCheckedChangeListener) (source, checked) -> {
                        if (!state.groups.contains(group)) return;
                        if (checked) {
                            // 旧版本保存的停用分组可以重新打开；新配置里这个开关只用来删组
                            safely(state, () -> support("setGroupEnabled", group.model, true));
                            return;
                        }
                        safely(state, () -> confirmRemove(state, group, () -> button.setChecked(true)));
                    });
            view.setOnLongClickListener(clicked -> {
                safely(state, () -> confirmRemove(state, group, null));
                return true;
            });
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": delayed group switch unavailable");
            XposedBridge.log(t);
        }
    }

    private static int checkboxWidgetId(Context context) {
        int cached = sCheckboxWidgetId;
        if (cached == 0) {
            cached = context.getResources().getIdentifier(CHECKBOX_WIDGET, "layout", context.getPackageName());
            sCheckboxWidgetId = cached;
        }
        return cached;
    }

    private static void associate(Object target, String key, Object value) {
        XposedHelpers.setAdditionalInstanceField(target, key, new WeakReference<>(value));
    }

    private static State state(Object target) {
        Object value = target == null ? null : XposedHelpers.getAdditionalInstanceField(target, STATE);
        return value instanceof WeakReference ? (State) ((WeakReference<?>) value).get() : null;
    }

    private static GroupUi group(Object target) {
        Object value = target == null ? null : XposedHelpers.getAdditionalInstanceField(target, GROUP);
        return value instanceof WeakReference ? (GroupUi) ((WeakReference<?>) value).get() : null;
    }

    private static void safely(State state, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            toast(state, failure(t));
        }
    }

    private static String failure(Throwable t) {
        XposedBridge.log(MainHook.TAG + ": delayed editor operation failed");
        XposedBridge.log(t);
        return "\u65e0\u6cd5\u7f16\u8f91\u518d\u6267\u884c\uff0c\u672c\u6b21\u4fee\u6539\u672a\u4fdd\u5b58";
    }

    private static void toast(State state, String text) {
        Context context = state.activity.get();
        if (context != null) Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }

    private static final class State {
        final WeakReference<Object> fragment;
        final WeakReference<Activity> activity;
        final Object results;
        final Object screen;
        final Object plan;
        final ClassLoader loader;
        final Class<?> preference;
        final Class<?> category;
        final Class<?> header;
        final Class<?> taskItem;
        final Class<?> defaultItem;
        final Class<?> lunchApp;
        final Method refresh;
        final List<Object> originalPreferences = new ArrayList<>();
        final List<GroupUi> groups = new ArrayList<>();
        Object add;
        WeakReference<View> addView;
        String pendingId;
        int pendingRequest;
        int pendingPosition = -1;
        String error;
        boolean ready;

        State(Object fragment, Activity activity, Object results, Object screen, Object plan,
                ClassLoader loader, Method refresh) {
            this.fragment = new WeakReference<>(fragment);
            this.activity = new WeakReference<>(activity);
            this.results = results;
            this.screen = screen;
            this.plan = plan;
            this.loader = loader;
            this.refresh = refresh;
            this.preference = XposedHelpers.findClass("com.miui.autotask.view.RecyclerViewPreference", loader);
            this.category = XposedHelpers.findClass("androidx.preference.PreferenceCategory", loader);
            this.header = XposedHelpers.findClass(HEADER_CLASS, loader);
            this.taskItem = XposedHelpers.findClass("com.miui.autotask.taskitem.TaskItem", loader);
            this.defaultItem = XposedHelpers.findClass("com.miui.autotask.taskitem.DefaultTaskItem", loader);
            this.lunchApp = XposedHelpers.findClass("com.miui.autotask.taskitem.LunchAppItem", loader);
        }
    }

    private static final class GroupUi {
        final Object model;
        final String id;
        /** 与「如果运行 / 就执行」同款的表头行 */
        Object header;
        /** 承载整组卡片的无标题分类 */
        Object section;
        Object delay;
        Object results;

        GroupUi(Object model, String id) {
            this.model = model;
            this.id = id;
        }

        /** 分类里的两个子项，顺序即界面顺序 */
        Object[] children() { return new Object[]{delay, results}; }
    }
}
