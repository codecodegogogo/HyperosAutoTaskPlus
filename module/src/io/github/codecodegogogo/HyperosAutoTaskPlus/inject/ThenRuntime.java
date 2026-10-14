package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.miui.autotask.taskitem.TaskItem;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/** 延迟只负责分发，原生动作的异步操作仍由原生实现负责。 */
public final class ThenRuntime {
    private static final String TAG = "HyperAutoEnh";
    private static final String PREFERENCES = "hyper_auto_task_then_undo_v1";
    private static final Object LOCK = new Object();
    private static final Map<String, State> STATES = new HashMap<>();
    private static final Alarms ALARMS = new Alarms("HyperAutoEnh:then:");
    private static Context context;
    private static Handler handler;
    private static Map<?, ?> taskBeans;
    private static SharedPreferences preferences;
    private static String stateSetter;
    private static Class<?> activeFactory;

    private ThenRuntime() {}

    public static Class<?> itemClass(String key) {
        return FirstAppKeys.KEY_THEN_EXECUTION_RESULT.equals(key) ? ThenResultItem.class : null;
    }

    public static Object newItem(String key) {
        return itemClass(key) == null ? null : new ThenResultItem();
    }

    public static boolean isItem(Object item) { return item instanceof ThenResultItem; }

    public static void start(Context app, Object engine, Class<?> factory) {
        synchronized (LOCK) {
            try {
                if (activeFactory != factory) {
                    ThenActionCodec.configure(factory);
                    activeFactory = factory;
                }
                if (app == null || engine == null || handler != null) return;
                Field thread = findField(engine.getClass(), "e", Handler.class);
                Field tasks = findField(engine.getClass(), "q", Map.class);
                Handler candidate = (Handler) thread.get(engine);
                Object values = tasks.get(engine);
                if (candidate == null || !(values instanceof Map)) {
                    throw new IllegalStateException("自动任务引擎尚未就绪");
                }
                context = app.getApplicationContext() == null ? app : app.getApplicationContext();
                if (!ALARMS.init(context)) throw new IllegalStateException("精确计时不可用");
                preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
                taskBeans = (Map<?, ?>) values;
                stateSetter = "b2.j".equals(engine.getClass().getName()) ? "E" : "D";
                handler = candidate;
            } catch (Throwable t) {
                // 旧任务也会经过引擎 hook；只在实际使用再执行时显示失败提示。
                Log.e(TAG, "then runtime initialization failed", t);
            }
        }
    }

    public static void execute(ThenResultItem item) {
        if (item == null) return;
        String uuid = item.j();
        synchronized (LOCK) {
            try {
                requireReady(uuid);
                State previous = STATES.get(uuid);
                if (previous != null) cancelPending(previous);
                if (!item.hasGroups()) return;
                if (!item.l()) throw new IllegalArgumentException("再执行配置不完整");
                List<PreparedGroup> groups = prepare(item, uuid);
                State state = stateFor(item);
                boolean restoreOnExit = expectsRestore(taskBeans.get(uuid));
                if (!restoreOnExit) {
                    state.progress.clearUndo();
                    removeSaved(uuid);
                }
                state.executionId = UUID.randomUUID().toString();
                long token = state.progress.begin(groups.size());
                state.run = new Run(state, groups, token, restoreOnExit);
                if (restoreOnExit) save(state);
                schedule(state.run);
            } catch (Throwable t) {
                State state = STATES.get(uuid);
                if (state != null) cancelPending(state);
                fail("再执行未开始，请检查配置和存储空间", t);
            }
        }
    }

    public static void restore(ThenResultItem item) {
        if (item == null) return;
        synchronized (LOCK) {
            try {
                requireReady(item.j());
                State pending = STATES.get(item.j());
                if (pending != null) cancelPending(pending);
                State state = stateFor(item);
                cancelPending(state);
                Throwable failure = null;
                for (Undo entry : state.progress.undoReverse()) {
                    try {
                        entry.item.o();
                        state.progress.removeUndo(entry.key);
                        save(state);
                    } catch (Throwable t) {
                        if (failure == null) failure = t; else failure.addSuppressed(t);
                    }
                }
                if (!state.progress.hasUndo()) {
                    removeSaved(state.uuid);
                    STATES.remove(state.uuid);
                }
                if (failure != null) fail("部分再执行项目恢复失败，请检查设备状态", failure);
            } catch (Throwable t) {
                fail("再执行恢复失败，请检查设备状态", t);
            }
        }
    }

    /** 停用、删除、编辑与重新启用都不继承上一次等待或恢复记录。 */
    public static void cancel(String owner) {
        String uuid = normalUuid(owner);
        if (uuid == null) return;
        synchronized (LOCK) {
            State state = STATES.get(uuid);
            if (state != null) cancelPending(state);
            State invalidated = new State(uuid, "");
            STATES.put(uuid, invalidated);
            try {
                if (preferences != null) {
                    removeSaved(uuid);
                    STATES.remove(uuid);
                }
            } catch (Throwable t) {
                fail("无法清除再执行恢复记录", t);
            }
        }
    }

    /** 只恢复已执行子动作的退出资格；等待中的分组从不在进程重启后补跑。 */
    public static void taskLoaded(Object bean) {
        if (bean == null) return;
        synchronized (LOCK) {
            try {
                if (handler == null) return;
                String uuid = (String) call(bean, "n");
                if (uuid == null || uuid.isEmpty()) return;
                Object result = call(bean, "a");
                if (!(result instanceof List)) return;
                for (Object action : (List<?>) result) {
                    if (!(action instanceof ThenResultItem)) continue;
                    boolean enabled = (Boolean) call(bean, "o");
                    boolean explicitExit = (Boolean) call(bean, "p");
                    boolean timeRange = hasTimeRange(bean);
                    boolean nativeExecuted = (Boolean) call(bean, "q");
                    if (!shouldKeepRestoration(enabled, explicitExit, timeRange, nativeExecuted)) {
                        // 原生已结束的时间窗口不能把旧快照带入下一轮，即使配置完全相同。
                        cancel(uuid);
                        return;
                    }
                    if (STATES.containsKey(uuid)) return;
                    ThenResultItem item = (ThenResultItem) action;
                    if (!uuid.equals(item.j()) || !item.l()) return;
                    State restored = load(item);
                    if (!restored.progress.hasUndo()) return;
                    // 活跃时间段的退出资格由原生维护；普通退出条件才需要恢复执行态。
                    if (!timeRange && !nativeExecuted) call(bean, stateSetter, int.class, 2);
                    STATES.put(uuid, restored);
                    Log.i(TAG, "then restore snapshots loaded: " + uuid);
                    return;
                }
            } catch (Throwable t) {
                fail("再执行恢复记录无法载入", t);
            }
        }
    }

    static boolean shouldKeepRestoration(boolean enabled, boolean explicitExit,
                                         boolean timeRange, boolean nativeExecuted) {
        return enabled && (timeRange ? nativeExecuted : explicitExit);
    }

    public static String title() { return DeviceActionRuntime.text("再执行", "Then execute"); }

    public static String summary(ThenResultItem item) {
        if (item == null || !item.hasGroups()) return title();
        List<ThenGroup> groups = item.getGroups();
        if (groups.size() == 1 && groups.get(0) != null) {
            ThenGroup group = groups.get(0);
            String unit = group.getDelayUnit() == ThenGroup.UNIT_MINUTES
                    ? DeviceActionRuntime.text("分钟", "min") : DeviceActionRuntime.text("秒", "sec");
            return group.getDelayAmount() + " " + unit
                    + DeviceActionRuntime.text("后再执行", " later");
        }
        return title() + " " + groups.size() + DeviceActionRuntime.text(" 组", " groups");
    }

    public static int icon(int which) {
        return InjectUi.icons("auto_task_icon_custom_time", android.R.drawable.ic_menu_recent_history)
                [Math.max(0, Math.min(which, 2))];
    }

    private static List<PreparedGroup> prepare(ThenResultItem item, String owner) {
        List<PreparedGroup> result = new ArrayList<>();
        for (ThenGroup group : item.getGroups()) {
            if (group == null || !group.isValid()) throw new IllegalArgumentException("再执行分组无效");
            List<TaskItem> actions = ThenActionCodec.decodeAll(group.getActions());
            if (actions.isEmpty()) throw new IllegalArgumentException("再执行分组没有执行项目");
            for (TaskItem action : actions) {
                action.s(owner);
                action.p(false);
            }
            result.add(new PreparedGroup(group.delayMillis(), actions));
        }
        return result;
    }

    private static void schedule(Run run) {
        State state = run.state;
        int index = state.progress.nextIndex(run.token);
        if (state.run != run || index < 0) return;
        if (index == run.groups.size()) {
            if (!state.progress.hasUndo()) removeSaved(state.uuid);
            state.progress.finish(run.token);
            state.run = null;
            if (!state.progress.hasUndo()) STATES.remove(state.uuid);
            return;
        }
        long delay = run.groups.get(index).delay;
        long due = Math.addExact(SystemClock.elapsedRealtime(), delay);
        Runnable callback = () -> fire(run, index);
        run.fallback = callback;
        boolean alarmSet = ALARMS.set(state.uuid, due, () -> {
            if (!handler.post(callback)) stopped(run, "再执行分发线程已停止", null);
        });
        if (!alarmSet || !handler.postDelayed(callback, delay)) {
            cancelPending(state);
            throw new IllegalStateException("再执行精确计时失败");
        }
    }

    private static void fire(Run run, int index) {
        State state = run.state;
        synchronized (LOCK) {
            if (state.run != run || !state.progress.take(run.token, index)) return;
            cancelTimer(state);
        }
        try {
            for (TaskItem action : run.groups.get(index).actions) {
                synchronized (LOCK) {
                    if (state.run != run || !state.progress.current(run.token)) return;
                    boolean remember = run.restoreOnExit && hasRestoration(action)
                            && !state.progress.containsUndo(action.e());
                    // 先验证序列化和磁盘可写，避免修改设置后才发现恢复记录无法保存。
                    if (remember) {
                        ThenActionCodec.encode(action);
                        save(state);
                    }
                    action.n();
                    if (remember) {
                        try {
                            Undo undo = new Undo(action, ThenActionCodec.encode(action));
                            state.progress.rememberFirst(undo.key, undo);
                            save(state);
                        } catch (Throwable t) {
                            state.progress.removeUndo(action.e());
                            try { action.o(); } catch (Throwable rollback) { t.addSuppressed(rollback); }
                            throw t;
                        }
                    }
                }
            }
            synchronized (LOCK) { schedule(run); }
        } catch (Throwable t) {
            stopped(run, "再执行已停止，请检查配置、权限或存储空间", t);
        }
    }

    static boolean hasRestoration(TaskItem item) {
        if (item instanceof DeviceActionResultItem) return ((DeviceActionResultItem) item).restoresOnExit();
        if (item instanceof ActionChainResultItem || item instanceof ThenResultItem) return false;
        String key = item.e();
        if ("key_lock_screen_result_item".equals(key) || "key_absorbed_result_item".equals(key)) return false;
        try {
            return item.getClass().getMethod("o").getDeclaringClass() != TaskItem.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static void stopped(Run run, String message, Throwable error) {
        synchronized (LOCK) {
            if (run.state.run != run) return;
            cancelPending(run.state);
            fail(message, error);
        }
    }

    private static void cancelPending(State state) {
        cancelTimer(state);
        state.progress.cancel();
        state.run = null;
    }

    private static void cancelTimer(State state) {
        ALARMS.cancel(state.uuid);
        if (state.run != null && state.run.fallback != null && handler != null) {
            handler.removeCallbacks(state.run.fallback);
            state.run.fallback = null;
        }
    }

    private static State stateFor(ThenResultItem item) throws Exception {
        String fingerprint = fingerprint(item);
        State state = STATES.get(item.j());
        if (state != null && !fingerprint.equals(state.fingerprint)) {
            cancelPending(state);
            removeSaved(item.j());
            STATES.remove(item.j());
            state = null;
        }
        if (state == null) {
            state = load(item);
            STATES.put(item.j(), state);
        }
        return state;
    }

    private static State load(ThenResultItem item) throws Exception {
        State state = new State(item.j(), fingerprint(item));
        String json = preferences.getString(item.j(), null);
        if (json == null) return state;
        SavedState saved = ThenActionCodec.readValue(json, SavedState.class);
        if (saved == null || saved.version == null || saved.version != 1
                || !state.uuid.equals(saved.taskId) || !state.fingerprint.equals(saved.fingerprint)) {
            removeSaved(item.j());
            return state;
        }
        state.executionId = saved.executionId;
        UUID.fromString(state.executionId);
        if (saved.actions == null) throw new IllegalArgumentException("恢复记录缺少执行项目");
        for (ThenAction encoded : saved.actions) {
            TaskItem action = ThenActionCodec.decode(encoded);
            if (!state.uuid.equals(action.j()) || !hasRestoration(action)) {
                throw new IllegalArgumentException("恢复记录与原任务不匹配");
            }
            Undo undo = new Undo(action, encoded);
            if (!state.progress.rememberFirst(undo.key, undo)) {
                throw new IllegalArgumentException("恢复记录包含重复项目");
            }
        }
        return state;
    }

    private static void save(State state) throws Exception {
        SavedState saved = new SavedState();
        saved.version = 1;
        saved.taskId = state.uuid;
        saved.fingerprint = state.fingerprint;
        saved.executionId = state.executionId;
        saved.actions = new ArrayList<>();
        for (Undo undo : state.progress.undoInOrder()) {
            saved.actions.add(undo.snapshot.copy());
        }
        if (!preferences.edit().putString(state.uuid, ThenActionCodec.writeValue(saved)).commit()) {
            throw new IllegalStateException("保存再执行恢复记录失败");
        }
    }

    private static void removeSaved(String uuid) {
        if (preferences.contains(uuid) && !preferences.edit().remove(uuid).commit()) {
            throw new IllegalStateException("清除再执行恢复记录失败");
        }
    }

    private static String fingerprint(ThenResultItem item) throws Exception {
        if (!item.l()) throw new IllegalArgumentException("再执行配置不完整");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (ThenGroup group : item.getGroups()) {
            digestField(digest, group.getId());
            digestField(digest, String.valueOf(group.getDelayAmount()));
            digestField(digest, String.valueOf(group.getDelayUnit()));
            digestField(digest, String.valueOf(group.getActions().size()));
            for (ThenAction action : group.getActions()) {
                digestField(digest, action.getKey());
                digestField(digest, action.getJson());
            }
        }
        StringBuilder value = new StringBuilder();
        for (byte b : digest.digest()) {
            value.append(Character.forDigit((b >>> 4) & 15, 16));
            value.append(Character.forDigit(b & 15, 16));
        }
        return value.toString();
    }

    private static void digestField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
        digest.update(bytes);
    }

    private static boolean expectsRestore(Object bean) throws Exception {
        if (bean == null) throw new IllegalStateException("自动任务已不存在");
        return (Boolean) call(bean, "p") || hasTimeRange(bean);
    }

    private static boolean hasTimeRange(Object bean) throws Exception {
        for (Object item : (List<?>) call(bean, "b")) {
            if (item != null && "com.miui.autotask.taskitem.CustomTimeConditionItem".equals(item.getClass().getName())) {
                Field type = findField(item.getClass(), "timeType", int.class);
                if (type.getInt(item) == 2) return true;
            }
        }
        return false;
    }

    private static void requireReady(String uuid) {
        if (uuid == null || uuid.isEmpty()) throw new IllegalArgumentException("再执行缺少任务标识");
        if (handler == null || preferences == null || taskBeans == null) {
            throw new IllegalStateException("自动任务引擎尚未就绪");
        }
    }

    private static String normalUuid(String value) {
        if (value == null || value.isEmpty()) return null;
        return value.endsWith("/-") ? value.substring(0, value.length() - 2) : value;
    }

    private static Field findField(Class<?> type, String name, Class<?> expected) throws Exception {
        Field result = type.getDeclaredField(name);
        if (!expected.isAssignableFrom(result.getType()) || Modifier.isStatic(result.getModifiers())) {
            throw new NoSuchFieldException(name);
        }
        result.setAccessible(true);
        return result;
    }

    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        return method.invoke(target);
    }

    private static void call(Object target, String name, Class<?> type, Object value) throws Exception {
        target.getClass().getMethod(name, type).invoke(target, value);
    }

    private static void fail(String message, Throwable error) {
        if (error == null) Log.e(TAG, message); else Log.e(TAG, message, error);
        Context app = context == null ? InjectUi.context() : context;
        if (app != null) ActionChainNotice.toast(app, message);
    }

    private static final class PreparedGroup {
        final long delay;
        final List<TaskItem> actions;
        PreparedGroup(long delay, List<TaskItem> actions) { this.delay = delay; this.actions = actions; }
    }

    private static final class State {
        final String uuid;
        final String fingerprint;
        final ExecutionState<Undo> progress = new ExecutionState<>();
        String executionId = UUID.randomUUID().toString();
        Run run;
        State(String uuid, String fingerprint) { this.uuid = uuid; this.fingerprint = fingerprint; }
    }

    private static final class Run {
        final State state;
        final List<PreparedGroup> groups;
        final long token;
        final boolean restoreOnExit;
        Runnable fallback;
        Run(State state, List<PreparedGroup> groups, long token, boolean restoreOnExit) {
            this.state = state;
            this.groups = groups;
            this.token = token;
            this.restoreOnExit = restoreOnExit;
        }
    }

    private static final class Undo {
        final String key;
        final TaskItem item;
        final ThenAction snapshot;
        Undo(TaskItem item, ThenAction snapshot) {
            this.key = item.e();
            this.item = item;
            this.snapshot = snapshot;
        }
    }

    private static final class SavedState {
        Integer version;
        String taskId;
        String fingerprint;
        String executionId;
        List<ThenAction> actions;
    }

    /** 不依赖 Android 的代次、顺序和恢复集合，供 JVM 回归验证。 */
    static final class ExecutionState<T> {
        private final LinkedHashMap<String, T> undo = new LinkedHashMap<>();
        private long generation;
        private int next;
        private int count;
        private boolean active;

        synchronized long begin(int groups) {
            if (groups <= 0) throw new IllegalArgumentException("groups must be positive");
            generation++;
            count = groups;
            next = 0;
            active = true;
            return generation;
        }

        synchronized boolean current(long token) { return active && generation == token; }

        synchronized boolean take(long token, int group) {
            if (!current(token) || group != next || next >= count) return false;
            next++;
            return true;
        }

        synchronized int nextIndex(long token) { return current(token) ? next : -1; }
        synchronized void finish(long token) { if (current(token)) active = false; }
        synchronized void cancel() { generation++; active = false; }

        synchronized boolean rememberFirst(String key, T value) {
            if (key == null || key.isEmpty() || value == null) throw new IllegalArgumentException("invalid undo");
            if (undo.containsKey(key)) return false;
            undo.put(key, value);
            return true;
        }

        synchronized boolean containsUndo(String key) { return undo.containsKey(key); }
        synchronized T removeUndo(String key) { return undo.remove(key); }
        synchronized List<T> undoInOrder() { return new ArrayList<>(undo.values()); }
        synchronized List<T> undoReverse() {
            List<T> result = undoInOrder();
            Collections.reverse(result);
            return result;
        }
        synchronized void clearUndo() { undo.clear(); }
        synchronized boolean hasUndo() { return !undo.isEmpty(); }
    }
}
