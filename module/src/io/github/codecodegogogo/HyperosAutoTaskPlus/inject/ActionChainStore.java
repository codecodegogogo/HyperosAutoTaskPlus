package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/**
 * 动作库：每条动作链一个文件，主进程负责录制 / 编辑 / 删除，remote 进程回放时只读。
 *
 * 两个进程 UID、数据目录相同，用文件共享即可。写入走"临时文件 + rename"原子替换，
 * 这样另一个进程读到的要么是旧的完整版、要么是新的完整版，不会读到写了一半的文件。
 *
 * "被哪些任务使用"直接只读打开安全服务自己的 auto_task.db 查询，SQL 里的表名 / 列名取自
 * 反编译（p003b2.c），不依赖随版本变化的混淆类名。
 */
final class ActionChainStore {
    private static final String DIR = "hyper_auto_action_chains";
    private static final String SUFFIX = ".chain";
    private static final String DB_NAME = "auto_task.db";
    /** 从 condition_result_json 里取 chainId 字段（Gson 默认用字段名，无 @SerializedName） */
    private static final Pattern CHAIN_ID = Pattern.compile("\"chainId\"\\s*:\\s*\"([^\"]*)\"");

    private ActionChainStore() {}

    static File dir(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) Log.w(DeviceActionRuntime.TAG, "action chain dir not created");
        return dir;
    }

    /** 全部动作链，按更新时间倒序（最近录制 / 编辑的排在最前）。损坏的文件跳过。 */
    static List<ActionChain> list(Context context) {
        List<ActionChain> chains = new ArrayList<>();
        File[] files = dir(context).listFiles((d, name) -> name.endsWith(SUFFIX));
        if (files == null) return chains;
        for (File file : files) {
            ActionChain chain = decodeFile(file);
            if (chain != null) chains.add(chain);
        }
        Collections.sort(chains, new Comparator<ActionChain>() {
            @Override public int compare(ActionChain a, ActionChain b) {
                return Long.compare(b.updatedAt(), a.updatedAt());
            }
        });
        return chains;
    }

    static ActionChain load(Context context, String id) {
        if (id == null || id.isEmpty()) return null;
        return decodeFile(file(context, id));
    }

    /** 原子保存：先写临时文件再 rename 覆盖，避免另一个进程读到半个文件。 */
    static void save(Context context, ActionChain chain) throws IOException {
        if (chain == null || !chain.isValid()) throw new IllegalArgumentException("动作链无效，不保存");
        File dir = dir(context);
        File target = new File(dir, chain.id() + SUFFIX);
        File tmp = new File(dir, chain.id() + SUFFIX + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(chain.encode());
            out.flush();
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            // 少数文件系统 rename 不覆盖已存在文件：删掉旧的再重试一次。
            if (!target.delete() || !tmp.renameTo(target)) {
                tmp.delete();
                throw new IOException("动作链保存失败: " + chain.id());
            }
        }
    }

    static boolean delete(Context context, String id) {
        if (id == null || id.isEmpty()) return false;
        File file = file(context, id);
        return !file.exists() || file.delete();
    }

    static boolean exists(Context context, String id) {
        return id != null && !id.isEmpty() && file(context, id).exists();
    }

    /**
     * 使用该动作链的任务标题列表（去重）。查不到标题的用占位名。
     * 出错（数据库被占用、结构变化）时返回空列表，调用方据此当作"无法确认使用情况"。
     */
    static List<String> usage(Context context, String chainId) {
        List<String> titles = new ArrayList<>();
        if (chainId == null || chainId.isEmpty()) return titles;
        File db = context.getDatabasePath(DB_NAME);
        if (db == null || !db.exists()) return titles;
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(db.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            List<String> uuids = new ArrayList<>();
            Cursor cursor = database.rawQuery(
                    "SELECT task_uuid, condition_result_json FROM task_condition_and_result_table"
                            + " WHERE condition_result_key = ?",
                    new String[]{FirstAppKeys.KEY_ACTION_CHAIN_RESULT});
            try {
                while (cursor.moveToNext()) {
                    String json = cursor.getString(1);
                    if (json == null) continue;
                    Matcher m = CHAIN_ID.matcher(json);
                    if (m.find() && chainId.equals(m.group(1))) {
                        String uuid = cursor.getString(0);
                        if (uuid != null && !uuids.contains(uuid)) uuids.add(uuid);
                    }
                }
            } finally { cursor.close(); }
            for (String uuid : uuids) {
                String title = title(database, uuid);
                titles.add(title == null || title.isEmpty()
                        ? DeviceActionRuntime.text("未命名任务", "Untitled task") : title);
            }
        } catch (Throwable t) {
            Log.w(DeviceActionRuntime.TAG, "query action chain usage failed", t);
        } finally {
            if (database != null) try { database.close(); } catch (Throwable ignored) {}
        }
        return titles;
    }

    /** 排队通知用：按任务 uuid 取任务标题，取不到返回 null。 */
    static String taskTitle(Context context, String uuid) {
        if (uuid == null || uuid.isEmpty()) return null;
        File db = context.getDatabasePath(DB_NAME);
        if (db == null || !db.exists()) return null;
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(db.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            return title(database, uuid);
        } catch (Throwable t) {
            Log.w(DeviceActionRuntime.TAG, "query task title failed", t);
            return null;
        } finally {
            if (database != null) try { database.close(); } catch (Throwable ignored) {}
        }
    }

    private static String title(SQLiteDatabase database, String uuid) {
        Cursor cursor = database.rawQuery(
                "SELECT task_title FROM auto_task_table WHERE task_uuid = ? LIMIT 1", new String[]{uuid});
        try {
            return cursor.moveToNext() ? cursor.getString(0) : null;
        } finally { cursor.close(); }
    }

    private static File file(Context context, String id) {
        return new File(dir(context), id + SUFFIX);
    }

    private static ActionChain decodeFile(File file) {
        if (file == null || !file.isFile() || file.length() == 0 || file.length() > 8 * 1024 * 1024) return null;
        try {
            byte[] data = new byte[(int) file.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            return ActionChain.decode(data);
        } catch (Throwable t) {
            Log.w(DeviceActionRuntime.TAG, "decode action chain file failed: " + file.getName(), t);
            return null;
        }
    }
}
