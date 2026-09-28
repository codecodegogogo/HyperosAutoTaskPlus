package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.util.List;

/**
 * 动作链回放。任务引擎在 remote 进程调用 TaskItem.n() 时进入这里。
 *
 * 一次只跑一条链（FIFO 队列 ActionChainQueue）。回放时：
 *   - 前置检查：屏幕亮、未锁屏、当前方向与录制时一致，否则跳过并通知；
 *   - 按 flatten 得到的扁平步骤序列，用 SystemClock.uptimeMillis 定时注入触摸 / 按键；
 *   - 屏幕上显示「停止」悬浮按钮，点击才中止（先补 CANCEL）；
 *   - 熄屏 / 锁屏也中止；
 *   - 中止或失败时通知说明原因，正常完成只清掉进行中通知。
 *
 * 所有回放状态只在自己的 HandlerThread 上读写，注入事件也在这个线程发出。
 */
final class ActionChainPlayer {
    private static final String CHANNEL = "hyper_auto_action_chain_v1";
    private static final int NOTIFICATION_ID = 0x48414301;

    private static final ActionChainQueue QUEUE = new ActionChainQueue();
    private static Handler sWorker;

    private ActionChainPlayer() {}

    private static synchronized Handler worker() {
        if (sWorker == null) {
            HandlerThread thread = new HandlerThread("hyper_auto_action_chain");
            thread.start();
            sWorker = new Handler(thread.getLooper());
        }
        return sWorker;
    }

    /** 引擎线程入口：把这次触发交给队列，真正执行放到 worker 线程。 */
    static void enqueue(Context context, String chainId, String taskUuid) {
        if (context == null || chainId == null || chainId.isEmpty()) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        worker().post(() -> {
            ActionChainQueue.Result result = QUEUE.submit(new ActionChainQueue.Request(chainId, taskUuid));
            if (result == ActionChainQueue.Result.STARTED) {
                runNext(app);
            } else if (result == ActionChainQueue.Result.QUEUED) {
                ActionChainQueue.Request current = QUEUE.running();
                notifyQueued(app, taskUuid, current == null ? null : current.taskUuid);
            } else {
                ActionChainNotice.toast(app, DeviceActionRuntime.text(
                        "动作链队列已满，本次已忽略", "Action chain queue is full; this trigger was skipped"));
            }
        });
    }

    private static void runNext(Context context) {
        ActionChainQueue.Request request = QUEUE.running();
        if (request == null) return;
        ActionChain chain = ActionChainStore.load(context, request.chainId);
        if (chain == null || !chain.isValid()) {
            ActionChainNotice.toast(context, DeviceActionRuntime.text(
                    "动作链已被删除或损坏，未执行", "The action chain was deleted or is corrupt; not executed"));
            finishAndContinue(context);
            return;
        }
        String skip = precheck(context, chain);
        if (skip != null) {
            ActionChainNotice.notify(context, CHANNEL, NOTIFICATION_ID, chainTitle(chain), skip, false);
            finishAndContinue(context);
            return;
        }
        Playback playback = new Playback(context, chain);
        playback.start();
    }

    /** 返回跳过原因，null 表示可以执行。 */
    private static String precheck(Context context, ActionChain chain) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power != null && !isInteractive(power)) {
            return DeviceActionRuntime.text("屏幕已熄灭，跳过动作链", "Screen is off; action chain skipped");
        }
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) {
            return DeviceActionRuntime.text("设备已锁屏，跳过动作链", "Device is locked; action chain skipped");
        }
        if (rotation(context) != chain.rotation()) {
            return DeviceActionRuntime.text("屏幕方向与录制时不同，跳过动作链",
                    "Screen orientation differs from recording; action chain skipped");
        }
        return null;
    }

    private static void finishAndContinue(Context context) {
        ActionChainQueue.Request next = QUEUE.advance();
        if (next != null) runNext(context);
    }

    @SuppressWarnings("deprecation")
    private static boolean isInteractive(PowerManager power) {
        try {
            return (Boolean) PowerManager.class.getMethod("isInteractive").invoke(power);
        } catch (Throwable t) {
            try {
                return (Boolean) PowerManager.class.getMethod("isScreenOn").invoke(power);
            } catch (Throwable t2) {
                return true; // 读不到就不因熄屏判断而拦截
            }
        }
    }

    static int rotation(Context context) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            return wm == null ? 0 : wm.getDefaultDisplay().getRotation();
        } catch (Throwable t) {
            return 0;
        }
    }

    static int[] screenSize(Context context) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display display = wm.getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            Display.class.getMethod("getRealMetrics", DisplayMetrics.class).invoke(display, metrics);
            return new int[]{metrics.widthPixels, metrics.heightPixels};
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
    }

    private static String chainTitle(ActionChain chain) {
        String name = chain.name();
        return DeviceActionRuntime.text("动作链", "Action chain")
                + (name == null || name.isEmpty() ? "" : " · " + name);
    }

    private static void notifyQueued(Context context, String waitingTask, String runningTask) {
        String waiting = taskName(context, waitingTask);
        String running = taskName(context, runningTask);
        String msg = DeviceActionRuntime.text(
                "任务「" + waiting + "」的动作链已排队，等待「" + running + "」的动作链执行完",
                "Action chain from \"" + waiting + "\" is queued, waiting for \"" + running + "\" to finish");
        ActionChainNotice.notify(context, CHANNEL, NOTIFICATION_ID + 1,
                DeviceActionRuntime.text("动作链排队", "Action chain queued"), msg, false);
    }

    private static String taskName(Context context, String uuid) {
        String title = ActionChainStore.taskTitle(context, uuid);
        return title == null || title.isEmpty() ? DeviceActionRuntime.text("另一个任务", "another task") : title;
    }

    // ------------------------------------------------------------------ 一次回放会话

    private static final class Playback {
        final Context context;
        final ActionChain chain;
        final int screenW;
        final int screenH;
        StopOverlay stopOverlay;
        BroadcastReceiver screenReceiver;
        PowerManager.WakeLock wakeLock;
        boolean aborted;
        boolean finished;
        GesturePlayer gesture;

        Playback(Context context, ActionChain chain) {
            this.context = context;
            this.chain = chain;
            int[] size = screenSize(context);
            this.screenW = size[0];
            this.screenH = size[1];
        }

        void start() {
            if (screenW <= 0 || screenH <= 0) {
                ActionChainNotice.toast(context, DeviceActionRuntime.text("无法获取屏幕尺寸", "Cannot read screen size"));
                done(null);
                return;
            }
            try {
                PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyperAutoEnh:actionchain");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(10 * 60 * 1000L);
            } catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "acquire wakelock failed", t); }

            // 熄屏 / 锁屏中止
            screenReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    abort(DeviceActionRuntime.text("屏幕关闭，动作链已中止", "Screen turned off; action chain aborted"));
                }
            };
            try {
                // ACTION_SCREEN_OFF 是系统保护广播，用普通 registerReceiver 即可。
                IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
                context.registerReceiver(screenReceiver, filter, null, worker());
            } catch (Throwable ignored) {}

            // 屏幕上显示「停止」按钮，点了才中止；不再靠碰屏幕判断，避免注入触摸误触发。
            stopOverlay = new StopOverlay(context, () -> worker().post(() ->
                    abort(DeviceActionRuntime.text("已手动停止动作链", "Action chain stopped manually"))));
            stopOverlay.show();

            ActionChainNotice.notify(context, CHANNEL, NOTIFICATION_ID, chainTitle(chain),
                    DeviceActionRuntime.text("动作链执行中", "Running action chain"), true);

            List<ActionStep> steps = chain.flatten();
            gesture = new GesturePlayer(context, screenW, screenH, worker(),
                    () -> !aborted, this::done);
            gesture.run(steps);
        }

        void abort(String reason) {
            if (aborted || finished) return;
            aborted = true;
            if (gesture != null) gesture.cancel();
            done(reason);
        }

        /** reason == null 表示正常完成；非空表示中止 / 跳过并通知。 */
        void done(String reason) {
            if (finished) return;
            finished = true;
            if (gesture != null) gesture.cancel();
            if (stopOverlay != null) { stopOverlay.dismiss(); stopOverlay = null; }
            if (screenReceiver != null) {
                try { context.unregisterReceiver(screenReceiver); } catch (Throwable ignored) {}
                screenReceiver = null;
            }
            if (wakeLock != null) {
                try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
                wakeLock = null;
            }
            if (reason == null) {
                ActionChainNotice.cancel(context, NOTIFICATION_ID);
            } else {
                ActionChainNotice.notify(context, CHANNEL, NOTIFICATION_ID, chainTitle(chain), reason, false);
            }
            finishAndContinue(context);
        }
    }
}
