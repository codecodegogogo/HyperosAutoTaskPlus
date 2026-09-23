package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.content.DialogInterface;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.miui.autotask.taskitem.TaskItem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;
import io.github.codecodegogogo.HyperosAutoTaskPlus.UnlockFailureState;
import miuix.appcompat.app.AlertDialog;

/**
 * 观察 SystemUI 写入的 Secure 设置，沿用 EngineBridge 通知原生引擎。
 * 仅在是否满足阈值发生变化时通知：第 N 次满足，N+1 次不会再由本条件通知一遍。
 * 成功解锁清零时恢复条件满足；初始化/损坏数据不等同于一次成功解锁。
 */
public final class UnlockFailureRuntime {
    private static final String TAG = "HyperAutoEnh";
    private static final ConcurrentHashMap<String, UnlockFailureConditionItem> ITEMS = new ConcurrentHashMap<>();
    private static volatile UnlockFailureState sState;
    private static volatile Handler sHandler;
    private static Context sContext;
    private static ContentObserver sObserver;

    private UnlockFailureRuntime() {}

    public static Class<?> itemClass(String key) {
        return FirstAppKeys.isUnlockFailureKey(key) ? UnlockFailureConditionItem.class : null;
    }

    public static Object newItem(String key) {
        return FirstAppKeys.isUnlockFailureKey(key) ? new UnlockFailureConditionItem() : null;
    }

    public static boolean isItem(Object item) { return item instanceof UnlockFailureConditionItem; }

    public static Object opposite(Object item) {
        return isItem(item) ? ((UnlockFailureConditionItem) item).opposite() : null;
    }

    public static int syncOpposite(Object item, List list) {
        if (!isItem(item) || list == null) return -1;
        UnlockFailureConditionItem source = (UnlockFailureConditionItem) item;
        for (int i = 0; i < list.size(); i++) {
            Object value = list.get(i);
            if (isItem(value)) {
                UnlockFailureConditionItem target = (UnlockFailureConditionItem) value;
                if (source.isResetCondition() != target.isResetCondition()) {
                    target.setThreshold(source.getThreshold());
                    return i;
                }
            }
        }
        return -1;
    }

    public static void register(Object value) {
        if (!isItem(value)) return;
        UnlockFailureConditionItem item = (UnlockFailureConditionItem) value;
        String uuid = item.j();
        if (uuid == null || uuid.isEmpty()) return;
        ITEMS.put(uuid, item);
        Handler handler = sHandler;
        if (handler != null) handler.post(() -> {
            if (ITEMS.get(uuid) != item || !evaluate(item)) return;
            Map<String, TaskItem> hit = new HashMap<>();
            hit.put(uuid, item);
            EngineBridge.notify(hit, "unlock failure condition registered");
        });
    }

    public static void unregister(String uuid) {
        if (uuid != null) ITEMS.remove(uuid);
    }

    public static synchronized void start(Context context, Object engine) {
        EngineBridge.attach(engine);
        if (sObserver != null) return;
        try {
            sContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            Handler handler = new Handler(Looper.getMainLooper());
            ContentObserver observer = new ContentObserver(handler) {
                @Override public void onChange(boolean selfChange) { refresh(); }
            };
            sContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(UnlockFailureState.SETTING_KEY), false, observer);
            sObserver = observer;
            sHandler = handler;
            handler.post(UnlockFailureRuntime::refresh);
            Log.i(TAG, "unlock failure observer started");
        } catch (Throwable t) {
            Log.e(TAG, "start unlock failure observer failed", t);
        }
    }

    private static UnlockFailureState read(Context context) {
        try {
            return UnlockFailureState.decode(Settings.Secure.getString(context.getContentResolver(), UnlockFailureState.SETTING_KEY));
        } catch (Throwable t) {
            Log.w(TAG, "read unlock failure state failed", t);
            return null;
        }
    }

    private static void refresh() {
        UnlockFailureState old = sState;
        UnlockFailureState current = read(sContext);
        sState = current;
        Map<String, TaskItem> changed = new HashMap<>();
        for (Map.Entry<String, UnlockFailureConditionItem> entry : ITEMS.entrySet()) {
            if (matches(old, entry.getValue()) != matches(current, entry.getValue())) {
                changed.put(entry.getKey(), entry.getValue());
            }
        }
        EngineBridge.notify(changed, "unlock failure threshold changed");
    }

    static boolean evaluate(UnlockFailureConditionItem item) { return matches(sState, item); }

    static boolean matches(UnlockFailureState state, UnlockFailureConditionItem item) {
        return state != null && item.l() && state.matches(item.getThreshold(), item.isResetCondition());
    }

    public static void pickAndApply(Context context, Object value, Runnable onConfirm) {
        if (context == null || !isItem(value)) return;
        UnlockFailureConditionItem item = (UnlockFailureConditionItem) value;
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = InjectUi.dp(context, 20);
        column.setPadding(padding, padding / 2, padding, 0);
        EditText count = new EditText(context);
        if (!item.isResetCondition()) {
            count.setInputType(InputType.TYPE_CLASS_NUMBER);
            count.setSingleLine(true);
            count.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
            count.setHint(text("失败次数（1～999）", "Attempts (1–999)"));
            count.setText(String.valueOf(item.getThreshold()));
            column.addView(count, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView hint = new TextView(context);
        hint.setText(item.isResetCondition()
                ? text("成功解锁后，连续失败次数清零并恢复任务。", "Restore after a successful unlock resets the failure count.")
                : text("连续失败达到设定次数时满足；成功解锁后清零。密码、图案、指纹和人脸合并计数。",
                        "Matches when consecutive failures reach this number; a successful unlock resets the count. All lock screen authentication methods share the count."));
        column.addView(hint);
        // 历史计数可能在取消作用域后仍然保留；不能用它判断是否需要展示设置说明。
        TextView setup = new TextView(context);
        setup.setPadding(0, padding / 2, 0, 0);
        setup.setTextSize(13);
        setup.setText(text("所需 Hook 作用域：安全服务（com.miui.securitycenter）＋系统界面（com.android.systemui）。"
                        + "请在 LSPosed 中为本模块勾选这两项，修改后重启手机。无需勾选系统桌面。",
                "Required LSPosed scopes: Security (com.miui.securitycenter) and System UI (com.android.systemui). "
                        + "Enable both for this module and reboot after changing scopes. The launcher scope is not required."));
        column.addView(setup);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(item.h())
                .setView(column).setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null).show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!item.isResetCondition()) {
                int threshold;
                try { threshold = Integer.parseInt(count.getText().toString()); }
                catch (NumberFormatException e) { threshold = 0; }
                if (threshold < 1 || threshold > 999) {
                    count.setError(text("请输入 1～999 的整数", "Enter an integer from 1 to 999"));
                    count.requestFocus();
                    return;
                }
                item.setThreshold(threshold);
            }
            if (onConfirm != null) onConfirm.run();
            dialog.dismiss();
        });
    }

    static String title() { return title(false); }

    static String title(boolean reset) {
        return reset ? text("成功解锁时", "On successful unlock") : text("解锁失败次数", "Failed unlock attempts");
    }

    static String summary(UnlockFailureConditionItem item) {
        return item.isResetCondition() ? text("成功解锁时", "On successful unlock")
                : text("连续解锁失败 ≥ ", "Consecutive unlock failures ≥ ") + item.getThreshold() + text(" 次", "");
    }

    static int icon(int which) {
        return InjectUi.icons("auto_task_icon_lock_screen", android.R.drawable.ic_lock_lock)[Math.max(0, Math.min(which, 2))];
    }

    private static String text(String zh, String en) { return InjectUi.zh() ? zh : en; }
}
