package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;

/** 动态接收器兼容 API 33+；仅接受本应用 PendingIntent，不向其它应用开放操作入口。 */
final class ActionReceivers {
    private ActionReceivers() {}

    static void register(Context context, BroadcastReceiver receiver, String action, Handler handler) throws Exception {
        IntentFilter filter = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= 33) {
            Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class,
                    String.class, Handler.class, int.class).invoke(context, receiver, filter, null, handler, 4);
        } else {
            context.registerReceiver(receiver, filter, null, handler);
        }
    }
}
