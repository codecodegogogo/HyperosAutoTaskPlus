package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.List;

/**
 * 编辑器里的「试运行」：回到桌面，悬浮倒计时 3 秒，然后按回放逻辑执行一遍当前草稿。
 * 与正式回放共用 GesturePlayer；结束后把编辑页切回前台。
 *
 * 试运行在主进程执行，不进回放队列，也不查任务归属；纯粹让用户预览效果。
 */
final class ActionChainTester {
    private ActionChainTester() {}

    static void run(Activity activity, ActionChain chain) {
        final Context app = activity.getApplicationContext();
        final int taskId = activity.getTaskId();
        int[] size = ActionChainPlayer.screenSize(app);
        final int screenW = size[0];
        final int screenH = size[1];
        if (screenW <= 0 || screenH <= 0) {
            DeviceActionRuntime.notice(app, DeviceActionRuntime.text("无法获取屏幕尺寸", "Cannot read screen size"));
            return;
        }
        if (ActionChainPlayer.rotation(app) != chain.rotation()) {
            DeviceActionRuntime.notice(app, DeviceActionRuntime.text(
                    "屏幕方向与录制时不同，试运行取消", "Orientation differs from recording; test cancelled"));
            return;
        }
        // 回桌面，让被操作的应用在前台
        try {
            android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(home);
        } catch (Throwable ignored) {}

        new Countdown(app, chain, screenW, screenH, taskId).begin();
    }

    private static final class Countdown {
        final Context context;
        final ActionChain chain;
        final int screenW;
        final int screenH;
        final int taskId;
        final Handler main;
        WindowManager windowManager;
        TextView view;
        int remaining = 3;

        Countdown(Context context, ActionChain chain, int screenW, int screenH, int taskId) {
            this.context = context;
            this.chain = chain;
            this.screenW = screenW;
            this.screenH = screenH;
            this.taskId = taskId;
            this.main = new Handler(context.getMainLooper());
        }

        void begin() {
            try {
                windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                view = new TextView(context);
                view.setBackground(ChainUi.round(ChainUi.OVERLAY_BG, 22, context));
                view.setTextColor(Color.WHITE);
                view.setTextSize(16);
                ChainUi.elevate(view, 8);
                int padH = InjectUi.dp(context, 22);
                int padV = InjectUi.dp(context, 14);
                view.setPadding(padH, padV, padH, padV);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        OverlayWindow.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.y = InjectUi.dp(context, 80);
                windowManager.addView(view, lp);
            } catch (Throwable t) {
                Log.w(DeviceActionRuntime.TAG, "test run overlay failed", t);
                play();
                return;
            }
            tick();
        }

        void tick() {
            if (remaining <= 0) {
                removeOverlay();
                play();
                return;
            }
            view.setText(DeviceActionRuntime.text("动作链试运行 ", "Test run in ") + remaining + "…");
            remaining--;
            main.postDelayed(this::tick, 1000);
        }

        void play() {
            Handler worker = new Handler(context.getMainLooper());
            List<ActionStep> steps = chain.flatten();
            boolean[] alive = {true};
            int oldShowTouches = showTouches(1);
            StopOverlay stop = new StopOverlay(context, () -> alive[0] = false);
            stop.show();
            GesturePlayer player = new GesturePlayer(context, screenW, screenH, worker,
                    () -> alive[0], reason -> {
                        alive[0] = false;
                        stop.dismiss();
                        if (oldShowTouches >= 0) showTouches(oldShowTouches);
                        DeviceActionRuntime.notice(context, DeviceActionRuntime.text("试运行结束", "Test run finished"));
                        backToEditor();
                    });
            player.run(steps);
        }

        /**
         * 试运行时临时打开系统「显示点按操作」（开发者选项里那个），每次注入的点都会显示白点，
         * 一眼就能看出点在了哪。返回原值，写失败返回 -1（不影响试运行）。
         */
        int showTouches(int value) {
            try {
                android.content.ContentResolver cr = context.getContentResolver();
                int old = android.provider.Settings.System.getInt(cr, "show_touches", 0);
                android.provider.Settings.System.putInt(cr, "show_touches", value);
                return old;
            } catch (Throwable t) {
                Log.w(DeviceActionRuntime.TAG, "toggle show_touches failed", t);
                return -1;
            }
        }

        void backToEditor() {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager)
                        context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) am.moveTaskToFront(taskId, 0);
            } catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "return to editor failed", t); }
        }

        void removeOverlay() {
            if (view != null && windowManager != null) {
                try { windowManager.removeView(view); } catch (Throwable ignored) {}
            }
            view = null;
        }
    }
}
