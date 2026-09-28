package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 录制新动作：跳到桌面，显示一个悬浮条（开始那一刻已在录），用户自己操作手机，
 * 点悬浮条上的「停止」结束。全程用 GestureMonitor 旁听真实触摸，落在悬浮条区域内的手势丢弃，
 * 所以拖动悬浮条、点停止不会被录进去。
 *
 * 停止后把原始帧按 GestureSegmenter 切成步骤，立即存进动作库，再回调让编辑器打开这条链。
 * 录制、分段、保存都在主进程。悬浮条自身在主线程，触摸监视在专用线程。
 */
final class ActionChainRecorder {
    private static final long MAX_DURATION_MS = 10 * 60 * 1000L;
    private static final int MAX_FRAMES_TOTAL = 60_000;
    private static final long MOVE_SAMPLE_MS = 8; // MOVE 事件至少间隔这么久才记一帧
    private static final long START_SETTLE_MS = 800; // 回桌面后等动画结束再开始动作

    interface Callback {
        /** 录制完成并已保存；chain 为保存后的动作链。取消 / 空录制时 chain 为 null。 */
        void onRecorded(ActionChain chain);
    }

    private final Context context;
    private final Handler main;
    private final Callback callback;
    private final int screenW;
    private final int screenH;
    private final int rotation;

    private WindowManager windowManager;
    private View bar;
    private TextView timerView;
    private Button actionButton;
    private Button cancelButton;
    private int[] barRect; // left, top, right, bottom（屏幕像素），落在此区域的手势忽略
    private GestureMonitor monitor;
    private Handler monitorHandler;
    private boolean recording;

    private final List<GestureSegmenter.RawGesture> gestures = new ArrayList<>();
    // 当前正在进行的手势
    private List<GestureFrame> current;
    private long currentStart;
    private long lastMoveTime;
    private int currentMaxPointers;
    private boolean ignoreCurrent;
    private int totalFrames;
    private long startedAt;
    private boolean finished;

    private ActionChainRecorder(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
        this.main = new Handler(context.getMainLooper());
        int[] size = ActionChainPlayer.screenSize(context);
        this.screenW = size[0];
        this.screenH = size[1];
        this.rotation = ActionChainPlayer.rotation(context);
    }

    static void start(Context context, Callback callback) {
        ActionChainRecorder recorder = new ActionChainRecorder(context, callback);
        if (recorder.screenW <= 0 || recorder.screenH <= 0) {
            DeviceActionRuntime.notice(context, DeviceActionRuntime.text("无法获取屏幕尺寸，无法录制", "Cannot read screen size"));
            callback.onRecorded(null);
            return;
        }
        recorder.begin();
    }

    private void begin() {
        // 回到桌面，让用户从桌面开始操作（包括自己打开应用）
        goHome();

        // 悬浮条先显示「开始录制」；点击后回桌面并真正开始采集。
        if (!showBar()) {
            DeviceActionRuntime.notice(context, DeviceActionRuntime.text(
                    "无法显示录制悬浮条，请检查悬浮窗权限", "Cannot show the recording bar; check overlay permission"));
            callback.onRecorded(null);
            return;
        }
    }

    private void goHome() {
        try {
            android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(home);
        } catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "go home failed", t); }
    }

    /** 点「开始录制」后真正启动触摸采集与计时。 */
    private void startCapturing() {
        if (recording || finished) return;
        // 起点统一为桌面：录制前回桌面，并在动作链开头写入「主页键 + 等待」，
        // 回放时先回到同一个起点，后面的坐标才对得上。
        goHome();
        monitorHandler = actionChainHandler();
        monitor = GestureMonitor.start("hyper_auto_chain_record", 0, monitorHandler.getLooper(), this::onTouch);
        if (monitor == null) {
            monitorHandler.getLooper().quit();
            monitorHandler = null;
            removeBar();
            DeviceActionRuntime.notice(context, DeviceActionRuntime.text(
                    "无法监听触摸，录制不可用", "Cannot monitor touch; recording unavailable"));
            callback.onRecorded(null);
            return;
        }
        recording = true;
        startedAt = SystemClock.uptimeMillis();
        // 超时自动停止
        main.postDelayed(this::onTimeout, MAX_DURATION_MS);
        switchBarToRecording();
        startTimer();
    }

    private static Handler actionChainHandler() {
        android.os.HandlerThread thread = new android.os.HandlerThread("hyper_auto_chain_record");
        thread.start();
        return new Handler(thread.getLooper());
    }

    // ------------------------------------------------------------------ 悬浮条

    private boolean showBar() {
        try {
            windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.HORIZONTAL);
            // 深色圆角卡片，浮在任意应用上都清晰
            layout.setBackground(ChainUi.round(ChainUi.OVERLAY_BG, 24, context));
            int padH = InjectUi.dp(context, 16);
            int padV = InjectUi.dp(context, 10);
            layout.setPadding(padH, padV, padH, padV);
            layout.setGravity(Gravity.CENTER_VERTICAL);
            ChainUi.elevate(layout, 8);

            timerView = new TextView(context);
            timerView.setTextColor(Color.WHITE);
            timerView.setTextSize(15);
            timerView.setText(DeviceActionRuntime.text("准备录制", "Ready"));
            layout.addView(timerView);

            Button action = new Button(context);
            action.setText(DeviceActionRuntime.text("开始录制", "Start"));
            ChainUi.primary(action, context);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bp.leftMargin = InjectUi.dp(context, 14);
            layout.addView(action, bp);
            actionButton = action;
            // 未开始时点按 = 开始录制；开始后 = 停止并保存。
            action.setOnClickListener(v -> {
                if (recording) onStop(DeviceActionRuntime.text("录制完成", "Recording finished"));
                else startCapturing();
            });

            Button cancel = new Button(context);
            cancel.setText(DeviceActionRuntime.text("取消", "Cancel"));
            ChainUi.ghostOnDark(cancel, context);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cp.leftMargin = InjectUi.dp(context, 8);
            layout.addView(cancel, cp);
            cancelButton = cancel;
            // 仅未开始录制时可取消；点击直接放弃，不保存。
            cancel.setOnClickListener(v -> onCancel());

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    OverlayWindow.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = InjectUi.dp(context, 12);
            lp.y = InjectUi.dp(context, 60);
            makeDraggable(layout, lp);
            windowManager.addView(layout, lp);
            bar = layout;
            layout.post(this::updateBarRect);
            return true;
        } catch (Throwable t) {
            Log.e(DeviceActionRuntime.TAG, "show recording bar failed", t);
            return false;
        }
    }

    private void makeDraggable(View view, WindowManager.LayoutParams lp) {
        view.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX(); downY = event.getRawY();
                        startX = lp.x; startY = lp.y;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = startX + (int) (event.getRawX() - downX);
                        lp.y = startY + (int) (event.getRawY() - downY);
                        try { windowManager.updateViewLayout(view, lp); } catch (Throwable ignored) {}
                        updateBarRect();
                        return false;
                    default:
                        return false;
                }
            }
        });
    }

    private void updateBarRect() {
        if (bar == null) return;
        int[] loc = new int[2];
        bar.getLocationOnScreen(loc);
        barRect = new int[]{loc[0], loc[1], loc[0] + bar.getWidth(), loc[1] + bar.getHeight()};
    }

    /** 点「开始录制」后把悬浮条切成录制态：按钮变「停止」，隐藏取消，计时开始。 */
    private void switchBarToRecording() {
        main.post(() -> {
            if (actionButton != null) {
                actionButton.setText(DeviceActionRuntime.text("停止", "Stop"));
                ChainUi.danger(actionButton, context);
            }
            if (cancelButton != null) cancelButton.setVisibility(View.GONE);
            if (timerView != null) timerView.setText(DeviceActionRuntime.text("● 录制中 00:00", "● REC 00:00"));
            if (bar != null) bar.post(this::updateBarRect);
        });
    }

    /** 未开始录制时取消：移除悬浮条，按空录制返回（回到动作库）。 */
    private void onCancel() {
        if (finished) return;
        finished = true;
        main.removeCallbacksAndMessages(null);
        removeBar();
        callback.onRecorded(null);
    }

    private void startTimer() {
        main.post(new Runnable() {
            @Override public void run() {
                if (finished) return;
                long sec = (SystemClock.uptimeMillis() - startedAt) / 1000;
                String t = String.format(java.util.Locale.US, "%02d:%02d", sec / 60, sec % 60);
                if (timerView != null) timerView.setText(DeviceActionRuntime.text("● 录制中 ", "● REC ") + t);
                main.postDelayed(this, 1000);
            }
        });
    }

    private void removeBar() {
        if (bar != null && windowManager != null) {
            try { windowManager.removeView(bar); } catch (Throwable ignored) {}
        }
        bar = null;
    }

    // ------------------------------------------------------------------ 触摸采集（monitor 线程）

    private void onTouch(MotionEvent event) {
        if (finished) return;
        int action = event.getActionMasked();
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) {
            beginGesture(event, now);
        } else if (current != null) {
            addFrame(event, now);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            endGesture(now);
        }
    }

    private void beginGesture(MotionEvent event, long now) {
        current = new ArrayList<>();
        currentStart = now;
        lastMoveTime = 0;
        currentMaxPointers = event.getPointerCount();
        // 落在悬浮条上的手势整段忽略（拖动 / 点停止）
        ignoreCurrent = inBar(event.getX(), event.getY());
        if (!ignoreCurrent) addFrame(event, now);
    }

    private void addFrame(MotionEvent event, long now) {
        if (ignoreCurrent || current == null) return;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE && now - lastMoveTime < MOVE_SAMPLE_MS) return;
        if (action == MotionEvent.ACTION_MOVE) lastMoveTime = now;
        int count = event.getPointerCount();
        currentMaxPointers = Math.max(currentMaxPointers, count);
        int[] ids = new int[count];
        float[] xs = new float[count];
        float[] ys = new float[count];
        for (int i = 0; i < count; i++) {
            ids[i] = event.getPointerId(i);
            xs[i] = event.getX(i) / screenW;
            ys[i] = event.getY(i) / screenH;
        }
        try {
            current.add(new GestureFrame(now - currentStart, action, safeActionIndex(event, count), ids, xs, ys));
            totalFrames++;
        } catch (Throwable t) {
            Log.w(DeviceActionRuntime.TAG, "record frame failed", t);
        }
        if (totalFrames >= MAX_FRAMES_TOTAL) main.post(() -> onStop(DeviceActionRuntime.text(
                "录制内容过长，已自动停止", "Recording too long; stopped automatically")));
    }

    private int safeActionIndex(MotionEvent event, int count) {
        int idx = event.getActionIndex();
        return idx < 0 || idx >= count ? 0 : idx;
    }

    private void endGesture(long now) {
        if (current != null && !ignoreCurrent && current.size() >= 2) {
            gestures.add(new GestureSegmenter.RawGesture(current, currentStart, now, currentMaxPointers));
        }
        current = null;
        ignoreCurrent = false;
    }

    private boolean inBar(float x, float y) {
        int[] r = barRect;
        return r != null && x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3];
    }

    // ------------------------------------------------------------------ 停止与保存

    private void onTimeout() {
        onStop(DeviceActionRuntime.text("已达最长录制时间，自动停止", "Reached max recording time; stopped"));
    }

    private void onStop(String toast) {
        if (finished) return;
        finished = true;
        main.removeCallbacksAndMessages(null);
        if (monitor != null) { monitor.dispose(); monitor = null; }
        removeBar();
        if (monitorHandler != null) monitorHandler.getLooper().quit();
        if (toast != null) DeviceActionRuntime.notice(context, toast);
        saveAndReturn();
    }

    private void saveAndReturn() {
        long longPress = ViewConfiguration.getLongPressTimeout();
        float slop = (float) ViewConfiguration.get(context).getScaledTouchSlop() / Math.min(screenW, screenH);
        List<ActionStep> steps = GestureSegmenter.segment(gestures, longPress, slop, 50);
        if (steps.isEmpty()) {
            main.post(() -> callback.onRecorded(null));
            return;
        }
        // 开头：主页键 + 等桌面稳定，保证回放起点与录制一致（不需要可在编辑器里删掉）
        steps.add(0, ActionStep.key(ActionStep.KEY_HOME));
        steps.add(1, ActionStep.delay(START_SETTLE_MS));
        long nowWall = System.currentTimeMillis();
        String name = defaultName(nowWall);
        ActionChain chain = new ActionChain(UUID.randomUUID().toString(), name, nowWall, nowWall,
                rotation, screenW, screenH, 1, 0, steps);
        try {
            if (!chain.isValid()) throw new IllegalStateException("录制结果无效");
            ActionChainStore.save(context, chain);
            main.post(() -> callback.onRecorded(chain));
        } catch (Throwable t) {
            Log.e(DeviceActionRuntime.TAG, "save recorded chain failed", t);
            DeviceActionRuntime.notice(context, DeviceActionRuntime.text("保存录制失败", "Failed to save recording"));
            main.post(() -> callback.onRecorded(null));
        }
    }

    private String defaultName(long wallMs) {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                InjectUi.zh() ? "M月d日 HH:mm" : "MMM d HH:mm", java.util.Locale.getDefault());
        return DeviceActionRuntime.text("动作 ", "Action ") + fmt.format(new java.util.Date(wallMs));
    }
}
