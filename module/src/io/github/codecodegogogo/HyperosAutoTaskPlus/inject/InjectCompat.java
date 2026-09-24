package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.util.Log;

import java.lang.reflect.Field;

/**
 * 条件项里的应用字段兼容层。
 *
 * LunchAppItem 的 setter 名称会随安全服务版本变化，12.8.6 还把 C/D 两个 setter
 * 的语义调整过。这里不依赖混淆方法名，而是按稳定的字段名复制：
 * appName、appNames、pkgName、pkgNames。
 */
public final class InjectCompat {

    private static final String TAG = "HyperAutoEnh";

    private static final String[] APP_FIELDS = {
            "appName", "appNames", "pkgName", "pkgNames"
    };

    private InjectCompat() {
    }

    /**
     * 把源对象里的应用字段复制到目标对象。
     *
     * @return 所有字段都成功复制时返回 true，否则返回 false 并写日志
     */
    public static boolean copy(Object src, Object dst) {
        if (src == null || dst == null) {
            return false;
        }

        boolean ok = true;
        for (String name : APP_FIELDS) {
            Field from = findField(src.getClass(), name);
            Field to = findField(dst.getClass(), name);
            if (from == null || to == null) {
                Log.w(TAG, "InjectCompat: 找不到字段 " + name
                        + " (" + src.getClass().getName() + " -> " + dst.getClass().getName() + ")");
                ok = false;
                continue;
            }
            try {
                to.set(dst, from.get(src));
            } catch (Throwable t) {
                Log.w(TAG, "InjectCompat: 复制字段 " + name + " 失败", t);
                ok = false;
            }
        }
        return ok;
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // continue in super classes
            } catch (Throwable t) {
                Log.w(TAG, "InjectCompat: 访问字段 " + name + " 失败", t);
                return null;
            }
        }
        return null;
    }
}