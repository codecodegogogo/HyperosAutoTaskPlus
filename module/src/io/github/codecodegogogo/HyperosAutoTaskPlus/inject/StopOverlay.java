package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

/**
 * 回放 / 试运行期间显示在屏幕上的「停止」悬浮按钮。点它才中止，不再靠碰屏幕判断。
 *
 * 悬浮按钮自己会收到触摸，但它不属于被回放的应用，也不影响注入的事件；
 * 注入的触摸落在按钮以外的地方，正常打到目标应用。
 * 所有操作切回主线程执行（WindowManager 需要主线程）。
 */
final class StopOverlay {
    private final Context context;
    private final Handler main;
    private final Runnable onStop;
    private WindowManager windowManager;
    private View button;
    private boolean shown;

    StopOverlay(Context context, Runnable onStop) {
        this.context = context;
        this.main = new Handler(context.getMainLooper());
        this.onStop = onStop;
    }

    void show() {
        main.post(() -> {
            if (shown) return;
            try {
                windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                Button stop = new Button(context);
                stop.setText(DeviceActionRuntime.text("停止动作链", "Stop chain"));
                ChainUi.danger(stop, context);
                int ph = InjectUi.dp(context, 22);
                int pv = InjectUi.dp(context, 11);
                stop.setPadding(ph, pv, ph, pv);
                stop.setOnClickListener(v -> {
                    if (onStop != null) onStop.run();
                });
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        OverlayWindow.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);
                // 放在顶部居中，避开大多数录制内容常落的中下部
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.y = InjectUi.dp(context, 40);
                windowManager.addView(stop, lp);
                button = stop;
                shown = true;
            } catch (Throwable t) {
                Log.w(DeviceActionRuntime.TAG, "show stop overlay failed", t);
            }
        });
    }

    void dismiss() {
        main.post(() -> {
            if (button != null && windowManager != null) {
                try { windowManager.removeView(button); } catch (Throwable ignored) {}
            }
            button = null;
            shown = false;
        });
    }
}
