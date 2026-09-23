package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.PowerManager;
import android.util.Log;

import java.util.UUID;

/**
 * 本地音频播放：使用系统解码器和媒体音量，默认播放一次。
 * 新播放替换本模块上一段音频；退出恢复只停止对应任务，不能误停其它任务的新播放。
 * 所有状态都在 DeviceActionRuntime.worker 上访问，完成、失败、失去焦点都释放播放器。
 */
final class AudioPlaybackRuntime {
    // 使用固定的新渠道，使旧版本安装后也能获得高重要级别的默认设置。
    private static final String CHANNEL = "hyper_auto_audio_playback_high_v1";
    private static final int NOTIFICATION_ID = 0x48415000;
    private static Session sSession;

    private AudioPlaybackRuntime() {}

    static void play(Context context, String owner, String uri, String name) {
        if (owner == null || owner.isEmpty() || !DeviceActionResultItem.validAudioUri(uri)) {
            throw new IllegalArgumentException("无效的任务或音频文件");
        }
        if (sSession != null) finish(sSession);
        Session session = new Session(context, owner, name);
        sSession = session;
        try { session.prepare(Uri.parse(uri)); }
        catch (Throwable t) { session.fail(t); }
    }

    static void stopOwned(String owner) {
        Session session = sSession;
        if (session != null && RecordingRuntime.owns(session.owner, owner)) finish(session);
    }

    private static void finish(Session session) {
        if (sSession != session) return;
        sSession = null;
        session.close();
    }

    private static final class Session {
        final Context context;
        final String owner;
        final String name;
        MediaPlayer player;
        AudioManager audio;
        boolean focusHeld;
        boolean preparing;
        BroadcastReceiver stopReceiver;
        PendingIntent stopIntent;
        boolean notified;
        final Runnable timeout = () -> fail(new IllegalStateException("音频准备超时"));
        final AudioManager.OnAudioFocusChangeListener focusListener = change -> DeviceActionRuntime.worker().post(() -> {
            if (sSession != this || player == null) return;
            try {
                if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) player.setVolume(0.2f, 0.2f);
                else if (change == AudioManager.AUDIOFOCUS_GAIN) player.setVolume(1f, 1f);
                else if (change < 0) finish(this);
            } catch (Throwable t) { fail(t); }
        });

        Session(Context context, String owner, String name) {
            this.context = context;
            this.owner = owner;
            this.name = name == null || name.isEmpty() ? DeviceActionRuntime.text("音频文件", "Audio file") : name;
        }

        void prepare(Uri uri) throws Exception {
            audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) throw new IllegalStateException("音频服务不可用");
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
            player.setLooping(false);
            player.setOnCompletionListener(mp -> finish(this));
            player.setOnErrorListener((mp, what, extra) -> {
                fail(new IllegalStateException("音频解码/播放错误 " + what + "/" + extra));
                return true;
            });
            player.setOnPreparedListener(mp -> {
                if (sSession != this) return;
                DeviceActionRuntime.worker().removeCallbacks(timeout);
                preparing = false;
                try {
                    focusHeld = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                    if (!focusHeld) throw new IllegalStateException("当前无法获得音频焦点");
                    try { showNotification(); }
                    catch (Throwable t) {
                        Log.w(DeviceActionRuntime.TAG, "audio stop notification unavailable", t);
                        hideNotification();
                    }
                    mp.start();
                    Log.i(DeviceActionRuntime.TAG, "audio playback started, task=" + owner);
                } catch (Throwable t) { fail(t); }
            });
            player.setDataSource(context, uri);
            DeviceActionRuntime.worker().postDelayed(timeout, 30_000L);
            preparing = true;
            player.prepareAsync();
        }

        void fail(Throwable error) {
            if (sSession != this) return;
            DeviceActionRuntime.failure(context, DeviceActionRuntime.text("播放音频", "Play audio"), error);
            finish(this);
        }

        void close() {
            if (preparing) {
                DeviceActionRuntime.worker().removeCallbacks(timeout);
                preparing = false;
            }
            if (player != null) {
                try { player.release(); }
                catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "release audio player", t); }
                player = null;
            }
            if (focusHeld) {
                try { audio.abandonAudioFocus(focusListener); }
                catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "release audio focus", t); }
                focusHeld = false;
            }
            hideNotification();
        }

        private void showNotification() throws Exception {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null || !(Boolean) NotificationManager.class.getMethod("areNotificationsEnabled").invoke(manager)) return;
            Class<?> channelType = Class.forName("android.app.NotificationChannel");
            Object channel = channelType.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CHANNEL, DeviceActionRuntime.text("自动任务音频播放", "Auto task audio playback"), 4); // IMPORTANCE_HIGH
            NotificationManager.class.getMethod("createNotificationChannel", channelType).invoke(manager, channel);
            Object current = NotificationManager.class.getMethod("getNotificationChannel", String.class).invoke(manager, CHANNEL);
            if (current != null && (Integer) channelType.getMethod("getImportance").invoke(current) == 0) return;
            String action = context.getPackageName() + ".hyper_auto.STOP_AUDIO_" + UUID.randomUUID();
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) { finish(Session.this); }
            };
            ActionReceivers.register(context, receiver, action, DeviceActionRuntime.worker());
            stopReceiver = receiver;
            stopIntent = PendingIntent.getBroadcast(context, NOTIFICATION_ID,
                    new Intent(action).setPackage(context.getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
            Notification.Builder builder = Notification.Builder.class.getConstructor(Context.class, String.class)
                    .newInstance(context, CHANNEL);
            builder.setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(DeviceActionRuntime.text("正在播放：", "Playing: ") + name)
                    .setContentText(DeviceActionRuntime.text("点按停止播放", "Tap to stop playback"))
                    .setContentIntent(stopIntent).setOngoing(true);
            manager.notify(NOTIFICATION_ID, NotificationAttention.build(builder));
            notified = true;
        }

        private void hideNotification() {
            if (stopReceiver != null) {
                try { context.unregisterReceiver(stopReceiver); }
                catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "unregister audio stop receiver", t); }
                stopReceiver = null;
            }
            if (stopIntent != null) {
                try { stopIntent.cancel(); }
                catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "cancel audio stop action", t); }
                stopIntent = null;
            }
            if (notified) {
                try { ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID); }
                catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "cancel audio notification", t); }
                notified = false;
            }
        }
    }
}
