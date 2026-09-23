package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.os.SystemClock;
import android.telephony.SmsManager;
import android.util.Log;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 发送成功后确认已发记录；不改变短信发送行为、默认短信应用或 AppOps。 */
final class SmsSentHistory {
    static final long CLAIM_TTL_MS = 300_000L;
    private static final Claims CLAIMS = new Claims();
    private static final String[] ROW_COLUMNS = {"_id", "address", "body", "sub_id", "type"};
    private static final String SMS_URI = "content://sms";
    private final Context context;
    private final String number;
    private final String body;
    private final long preparedAt;
    private final Object owner = new Object();
    private final Set<Long> providerIds = new LinkedHashSet<>();
    private int subscription = -1;
    private long baseline;
    private boolean baselineKnown;
    private boolean finished;
    private boolean recorded;

    private SmsSentHistory(Context context, String number, String body) {
        this.context = context;
        this.number = number;
        this.body = body;
        preparedAt = System.currentTimeMillis();
    }

    /** 在调用 SmsManager 之前取得快照；读取失败只禁用盲补写，不阻止真实短信发送。 */
    static SmsSentHistory prepare(Context context, SmsManager manager, String number, String body) {
        SmsSentHistory history = new SmsSentHistory(context, number, body);
        try {
            if (context == null || manager == null || number == null || body == null) return history;
            // 使用本次实际选中的 SmsManager，不能在发送结束时再读取可能已切换的默认卡。
            history.subscription = (Integer) SmsManager.class.getMethod("getSubscriptionId").invoke(manager);
            if (history.subscription < 0 || !history.canReadAll()) return history;
            try (Cursor cursor = context.getContentResolver().query(Uri.parse(SMS_URI),
                    new String[]{"_id"}, null, null, "_id DESC")) {
                if (cursor == null) return history;
                history.baseline = cursor.moveToFirst() ? cursor.getLong(0) : 0;
                history.baselineKnown = history.baseline >= 0;
            }
        } catch (Exception error) {
            logUnavailable();
        }
        return history;
    }

    /** uri 来自 SmsManager 的成功 PendingIntent；这里只接受短信记录的规范 URI。 */
    void noteProviderUri(String uri) {
        try {
            long id = recordId(uri);
            if (id <= 0) return;
            synchronized (CLAIMS) {
                if (!finished) providerIds.add(id);
            }
        } catch (Exception ignored) { /* 无法识别的系统扩展不影响真实发送结果。 */ }
    }

    /** 仅在全部分段均成功时调用；失败或无法确认保存时返回 false，绝不重发短信。 */
    boolean ensureRecorded() {
        synchronized (CLAIMS) {
            if (finished) return recorded;
            // 即使插入结果未知也不能再次尝试，否则一次请求可能产生多条记录。
            finished = true;
            try {
                if (context == null || number == null || body == null || subscription < 0) return false;
                long now = SystemClock.elapsedRealtime();
                long sentAt = System.currentTimeMillis();
                for (long id : providerIds) {
                    if (matchesRow(id) && CLAIMS.claim(id, owner, now)) {
                        recorded = true;
                        return true;
                    }
                }
                // 无可靠基线或没有读取权限时，不能把“看不到记录”当作“系统未入库”。
                if (!baselineKnown || !canReadAll()) return false;
                ContentResolver resolver = context.getContentResolver();
                String selection = "_id > ? AND type = ? AND address = ? AND body = ? AND sub_id = ?";
                String[] args = {Long.toString(baseline), "2", number, body, Integer.toString(subscription)};
                try (Cursor cursor = resolver.query(Uri.parse(SMS_URI), ROW_COLUMNS, selection, args, "_id ASC")) {
                    if (cursor == null) return false;
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(0);
                        if (matches(cursor) && CLAIMS.claim(id, owner, now)) {
                            recorded = true;
                            return true;
                        }
                    }
                }
                // Provider 自动计算会话 thread_id 并通知默认短信应用；不直接碰短信数据库文件。
                ContentValues values = new ContentValues();
                values.put("address", number);
                values.put("body", body);
                values.put("sub_id", subscription);
                values.put("type", 2);
                values.put("read", 1);
                values.put("seen", 1);
                values.put("status", -1); // 已发出，不冒充对方已送达回执。
                values.put("date", preparedAt);
                values.put("date_sent", sentAt);
                Uri inserted = resolver.insert(Uri.parse(SMS_URI + "/sent"), values);
                long id = inserted == null ? -1 : recordId(inserted.toString());
                if (id <= 0 || !CLAIMS.claim(id, owner, now)) return false;
                recorded = matchesRow(id);
                return recorded;
            } catch (Exception error) {
                logUnavailable();
                return false;
            } finally {
                providerIds.clear();
            }
        }
    }

    private boolean matchesRow(long id) {
        try (Cursor cursor = context.getContentResolver().query(
                Uri.parse(SMS_URI + "/" + id), ROW_COLUMNS, null, null, null)) {
            return cursor != null && cursor.moveToFirst() && cursor.getLong(0) == id && matches(cursor);
        } catch (Exception error) {
            return false;
        }
    }

    private boolean matches(Cursor cursor) {
        return number.equals(cursor.getString(1)) && body.equals(cursor.getString(2))
                && !cursor.isNull(3) && cursor.getInt(3) == subscription && cursor.getInt(4) == 2;
    }

    private boolean canReadAll() {
        try {
            if (context.checkCallingOrSelfPermission("android.permission.READ_SMS") != PackageManager.PERMISSION_GRANTED) return false;
            Object appOps = context.getSystemService("appops");
            if (appOps == null) return false;
            // SMS Provider 的 AppOp 拒绝可能只返回空游标；提前排除这种情况，避免盲插入。
            Class<?> type = Class.forName("android.app.AppOpsManager");
            int mode = (Integer) type.getMethod("checkOpNoThrow", String.class, int.class, String.class)
                    .invoke(appOps, "android:read_sms", Process.myUid(), context.getPackageName());
            return mode == 0; // MODE_ALLOWED；不尝试修改任何授权或操作模式。
        } catch (Exception error) {
            return false;
        }
    }

    private static long recordId(String value) {
        if (value == null) return -1;
        try {
            Uri uri = Uri.parse(value);
            if (!"content".equals(uri.getScheme()) || !"sms".equals(uri.getAuthority())
                    || uri.getQuery() != null || uri.getFragment() != null) return -1;
            String path = uri.getPath();
            if (path == null || !path.matches("/[1-9][0-9]*")) return -1;
            return Long.parseLong(path.substring(1));
        } catch (Exception error) { return -1; }
    }

    private static void logUnavailable() {
        // 数据库异常可能带 selectionArgs；只记固定提示，避免泄露号码与正文。
        Log.w(DeviceActionRuntime.TAG, "SMS sent history could not be confirmed");
    }

    /** 单进程短期认领，不将号码、正文或 Context 保存在静态集合中。 */
    static final class Claims {
        private final Map<Long, Claim> entries = new HashMap<>();

        synchronized boolean claim(long id, Object owner, long nowMillis) {
            if (id <= 0 || owner == null) return false;
            Iterator<Map.Entry<Long, Claim>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Claim value = iterator.next().getValue();
                if (nowMillis < value.time || nowMillis - value.time >= CLAIM_TTL_MS) iterator.remove();
            }
            Claim previous = entries.get(id);
            if (previous != null) return previous.owner == owner;
            entries.put(id, new Claim(owner, nowMillis));
            return true;
        }

        private static final class Claim {
            final Object owner;
            final long time;
            Claim(Object owner, long time) { this.owner = owner; this.time = time; }
        }
    }
}
