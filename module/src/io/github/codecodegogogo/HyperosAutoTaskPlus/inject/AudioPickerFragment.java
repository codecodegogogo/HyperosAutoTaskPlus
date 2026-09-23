package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;

import java.util.Locale;

/**
 * 无界面的原生 Fragment 承接系统文档选择器结果，不占用宿主 102～106 的 requestCode。
 * 类由安全服务的 ClassLoader 加载，Android 重建 Fragment 时也能找到无参构造。
 * 临时授权在选择页关闭后会失效，必须取得持久读取授权才能把文件保存到任务。
 */
@SuppressWarnings("deprecation")
public final class AudioPickerFragment extends Fragment {
    private static final String TAG = "hyper_auto_audio_picker";
    private static final int REQUEST_AUDIO = 0x4841;
    private DeviceActionResultItem item;
    private Runnable confirm;
    private boolean launched;

    public AudioPickerFragment() {}

    static void pick(Context context, DeviceActionResultItem item, Runnable confirm) {
        Activity activity = activity(context);
        if (activity == null || activity.isFinishing()) {
            DeviceActionRuntime.notice(context, DeviceActionRuntime.text("请在任务编辑页选择音频", "Choose audio from the task editor"));
            return;
        }
        try {
            FragmentManager manager = activity.getFragmentManager();
            if (manager.findFragmentByTag(TAG) != null) return;
            AudioPickerFragment picker = new AudioPickerFragment();
            picker.item = item;
            picker.confirm = confirm;
            manager.beginTransaction().add(picker, TAG).commit();
            manager.executePendingTransactions();
        } catch (Throwable t) {
            DeviceActionRuntime.failure(context, DeviceActionRuntime.text("选择音频", "Choose audio"), t);
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        launched = state != null && state.getBoolean("launched");
    }

    @Override public void onActivityCreated(Bundle state) {
        super.onActivityCreated(state);
        if (launched) return;
        launched = true;
        try {
            Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT")
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
                    .putExtra("android.intent.extra.LOCAL_ONLY", true)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | 0x00000040);
            startActivityForResult(intent, REQUEST_AUDIO);
        } catch (Throwable t) {
            DeviceActionRuntime.failure(getActivity(), DeviceActionRuntime.text("打开文件选择器", "Open file picker"), t);
            remove();
        }
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putBoolean("launched", launched);
        super.onSaveInstanceState(state);
    }

    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_AUDIO) return;
        final Activity host = getActivity();
        if (result != Activity.RESULT_OK || data == null || data.getData() == null || host == null) {
            remove();
            return;
        }
        // 原生编辑页若在选择期间被系统重建，旧对象/旧 adapter 已无效，不能误写到其它草稿。
        if (item == null || confirm == null) {
            DeviceActionRuntime.notice(host, DeviceActionRuntime.text("编辑页已重建，请重新选择音频", "The editor was recreated; please select the audio again"));
            remove();
            return;
        }
        final Uri uri = data.getData();
        final int flags = data.getFlags();
        DeviceActionRuntime.worker().post(() -> {
            try {
                String name = validateAndKeepPermission(host, uri, flags);
                new Handler(host.getMainLooper()).post(() -> {
                    try {
                        if (getActivity() == host && !host.isFinishing() && item != null && confirm != null) {
                            item.setAudioFile(uri.toString(), name);
                            confirm.run();
                        }
                    } catch (Throwable t) {
                        DeviceActionRuntime.failure(host, DeviceActionRuntime.text("保存音频选择", "Save audio selection"), t);
                    } finally { remove(); }
                });
            } catch (Throwable t) {
                DeviceActionRuntime.failure(host, DeviceActionRuntime.text("选择音频", "Choose audio"), t);
                new Handler(host.getMainLooper()).post(this::remove);
            }
        });
    }

    private static String validateAndKeepPermission(Context context, Uri uri, int flags) throws Exception {
        if (!DeviceActionResultItem.validAudioUri(uri.toString())) throw new IllegalArgumentException("文件选择器没有返回内容 URI");
        ContentResolver resolver = context.getContentResolver();
        String name = uri.getLastPathSegment();
        Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (cursor != null) {
            try {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) name = cursor.getString(index);
            } finally { cursor.close(); }
        }
        if (!accepts(resolver.getType(uri), name)) throw new IllegalArgumentException("请选择音频文件");
        if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) throw new SecurityException("文件管理器没有提供读取授权");
        // 先确认文件可读；失败/取消时不覆盖任务原来的音频配置。
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
            if (descriptor == null) throw new IllegalStateException("音频文件不可读");
        }
        ContentResolver.class.getMethod("takePersistableUriPermission", Uri.class, int.class)
                .invoke(resolver, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return name == null || name.isEmpty() ? DeviceActionRuntime.text("音频文件", "Audio file") : name;
    }

    static boolean accepts(String mime, String name) {
        String type = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (type.startsWith("audio/")) return true;
        if (!type.isEmpty() && !"application/octet-stream".equals(type)
                && !"application/ogg".equals(type)) return false;
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String suffix : new String[]{".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".oga", ".opus", ".amr", ".3gp"}) {
            if (lower.endsWith(suffix)) return true;
        }
        return false;
    }

    private static Activity activity(Context context) {
        for (int i = 0; context != null && i < 16; i++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) return null;
            context = next;
        }
        return null;
    }

    private void remove() {
        item = null;
        confirm = null;
        try {
            FragmentManager manager = getFragmentManager();
            if (manager != null) manager.beginTransaction().remove(this).commitAllowingStateLoss();
        } catch (Throwable t) { Log.w(DeviceActionRuntime.TAG, "remove audio picker", t); }
    }
}
