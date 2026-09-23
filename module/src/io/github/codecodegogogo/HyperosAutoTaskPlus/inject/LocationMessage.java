package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import java.util.Locale;

/** Android 定位使用 WGS-84；高德 marker 链接使用 GCJ-02。 */
final class LocationMessage {
    static final String HEADER = "本设备触发HyperosAutoTaskPlus行为";
    static final String UNKNOWN_PLACE = "地点暂无法解析";

    private LocationMessage() {}

    static String text(String message) {
        return withSupplement(HEADER, message);
    }

    static String append(String message, double longitude, double latitude) {
        return append(message, longitude, latitude, UNKNOWN_PLACE);
    }

    static String append(String message, double longitude, double latitude, String place) {
        double[] position = toAmap(longitude, latitude);
        String link = String.format(Locale.US,
                "https://uri.amap.com/marker?position=%.6f,%.6f&name=%%E5%%BD%%93%%E5%%89%%8D%%E4%%BD%%8D%%E7%%BD%%AE&coordinate=gaode",
                position[0], position[1]);
        String safePlace = place == null || place.trim().isEmpty() ? UNKNOWN_PLACE : place.trim();
        String location = HEADER + "，我的地址为（" + safePlace + "）（"
                + String.format(Locale.US, "经度：%.6f，纬度：%.6f", longitude, latitude) + "）（【" + link + "】）";
        return withSupplement(location, message);
    }

    private static String withSupplement(String prefix, String message) {
        String body = message == null ? "" : message;
        // 用户手填固定开头时不再重复；补充文字的空格、换行和表情保持原样。
        if (body.startsWith(HEADER)) {
            body = body.substring(HEADER.length());
            if (body.startsWith("，") || body.startsWith(",")) body = body.substring(1);
            if (body.startsWith("\r\n")) body = body.substring(2);
            else if (body.startsWith("\n")) body = body.substring(1);
        }
        return body.trim().isEmpty() ? prefix : prefix + "\n" + body;
    }

    static double[] toAmap(double longitude, double latitude) {
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("定位坐标无效");
        }
        if (longitude < 72.004 || longitude > 137.8347 || latitude < 0.8293 || latitude > 55.8271) {
            return new double[]{longitude, latitude};
        }
        double x = longitude - 105, y = latitude - 35;
        double wave = (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        double dLat = -100 + 2 * x + 3 * y + .2 * y * y + .1 * x * y + .2 * Math.sqrt(Math.abs(x)) + wave;
        dLat += (20 * Math.sin(y * Math.PI) + 40 * Math.sin(y / 3 * Math.PI)) * 2 / 3;
        dLat += (160 * Math.sin(y / 12 * Math.PI) + 320 * Math.sin(y * Math.PI / 30)) * 2 / 3;
        double dLon = 300 + x + 2 * y + .1 * x * x + .1 * x * y + .1 * Math.sqrt(Math.abs(x)) + wave;
        dLon += (20 * Math.sin(x * Math.PI) + 40 * Math.sin(x / 3 * Math.PI)) * 2 / 3;
        dLon += (150 * Math.sin(x / 12 * Math.PI) + 300 * Math.sin(x / 30 * Math.PI)) * 2 / 3;
        double rad = latitude / 180 * Math.PI;
        double magic = 1 - .00669342162296594323 * Math.pow(Math.sin(rad), 2);
        double root = Math.sqrt(magic);
        dLat = dLat * 180 / ((6378245 * (1 - .00669342162296594323)) / (magic * root) * Math.PI);
        dLon = dLon * 180 / (6378245 / root * Math.cos(rad) * Math.PI);
        return new double[]{longitude + dLon, latitude + dLat};
    }

    static boolean usable(long ageMillis, float accuracy) {
        return ageMillis >= 0 && ageMillis <= 15_000 && Float.isFinite(accuracy) && accuracy > 0 && accuracy <= 200;
    }
}
