package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 只在发送时申请一次定位，超时或成功后立即移除监听；不发送陈旧的缓存位置。 */
final class SmsLocationRuntime implements LocationListener {
    interface Callback {
        void ready(Location location, String place);
        void failed(String reason);
    }

    // 系统地址服务可能阻塞；最多保留一个解析线程，不堵塞定位、拍照和录制共用线程。
    private static final ThreadPoolExecutor GEOCODER = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "hyper_auto_geocoder");
                thread.setDaemon(true);
                return thread;
            });
    private final Context context;
    private final LocationManager manager;
    private final Callback callback;
    private final PowerManager.WakeLock wakeLock;
    private boolean closed;
    private boolean resolving;
    private Future<?> geocoding;
    private Runnable addressTimeout;
    private final Runnable timeout = () -> fail("定位超时，未发送；请检查定位开关、精确定位及后台定位权限");

    private SmsLocationRuntime(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        wakeLock = ((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyperAutoEnh:sms_location");
    }

    static void request(Context context, Callback callback) {
        SmsLocationRuntime request = new SmsLocationRuntime(context, callback);
        try { request.start(); }
        catch (Exception e) { request.fail("无法获取位置，未发送；请检查安全服务的定位权限及定位开关"); }
    }

    private void start() {
        if (manager == null) throw new IllegalStateException("定位服务不可用");
        wakeLock.acquire(40_000L);
        DeviceActionRuntime.worker().postDelayed(timeout, 30_000L);
        int providers = 0;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            if (!manager.isProviderEnabled(provider)) continue;
            try {
                manager.requestLocationUpdates(provider, 0L, 0f, this, DeviceActionRuntime.worker().getLooper());
                providers++;
            } catch (SecurityException ignored) { /* 另一来源仍可能可用 */ }
        }
        if (providers == 0) fail("无法获取位置，未发送；请开启定位并授予安全服务定位权限");
    }

    @Override public void onLocationChanged(Location location) {
        if (closed || resolving || location == null || !location.hasAccuracy()) return;
        try {
            long nanos = (Long) Location.class.getMethod("getElapsedRealtimeNanos").invoke(location);
            long age = SystemClock.elapsedRealtime() - nanos / 1_000_000;
            if (!LocationMessage.usable(age, location.getAccuracy())) return;
            // 同时检查坐标，避免无效定位终止监听后才发现不能构造链接。
            LocationMessage.toAmap(location.getLongitude(), location.getLatitude());
        } catch (Exception ignored) { return; }
        resolveAddress(new Location(location));
    }

    private void resolveAddress(Location location) {
        resolving = true;
        try { manager.removeUpdates(this); } catch (Exception ignored) {}
        DeviceActionRuntime.worker().removeCallbacks(timeout);
        addressTimeout = () -> finish(location, LocationMessage.UNKNOWN_PLACE);
        DeviceActionRuntime.worker().postDelayed(addressTimeout, 6_000L);
        try {
            geocoding = GEOCODER.submit(() -> {
                String place = LocationMessage.UNKNOWN_PLACE;
                try {
                    if (Geocoder.isPresent()) {
                        List<Address> addresses = new Geocoder(context, Locale.SIMPLIFIED_CHINESE)
                                .getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                        if (addresses != null && !addresses.isEmpty()) place = addressText(addresses.get(0));
                    }
                } catch (Exception ignored) { /* 坐标有效时，地址服务失败不阻止发送坐标和链接。 */ }
                final String result = place;
                DeviceActionRuntime.worker().post(() -> finish(location, result));
            });
        } catch (RuntimeException e) {
            // 前次地址调用尚未返回时，不排队堆积请求。
            finish(location, LocationMessage.UNKNOWN_PLACE);
        }
    }

    private static String addressText(Address address) {
        if (address == null) return LocationMessage.UNKNOWN_PLACE;
        if (address.getMaxAddressLineIndex() >= 0) {
            String line = address.getAddressLine(0);
            if (line != null && !line.trim().isEmpty()) return line.trim();
        }
        StringBuilder place = new StringBuilder();
        for (String part : new String[]{address.getCountryName(), address.getAdminArea(), address.getSubAdminArea(),
                address.getLocality(), address.getSubLocality(), address.getThoroughfare(), address.getSubThoroughfare()}) {
            if (part != null && !part.trim().isEmpty() && place.indexOf(part.trim()) < 0) place.append(part.trim());
        }
        return place.length() == 0 ? LocationMessage.UNKNOWN_PLACE : place.toString();
    }

    private void finish(Location location, String place) {
        if (closed) return;
        close();
        callback.ready(location, place);
    }

    private void fail(String reason) {
        if (closed) return;
        close();
        callback.failed(reason);
    }

    private void close() {
        closed = true;
        DeviceActionRuntime.worker().removeCallbacks(timeout);
        if (addressTimeout != null) DeviceActionRuntime.worker().removeCallbacks(addressTimeout);
        if (geocoding != null) geocoding.cancel(true);
        try { if (manager != null) manager.removeUpdates(this); } catch (Exception ignored) {}
        // 自动到期和系统回收可能与主动释放并发；清理失败不能阻断发送方的结束回调。
        try { if (wakeLock.isHeld()) wakeLock.release(); } catch (RuntimeException ignored) {}
    }

    @Override public void onProviderDisabled(String provider) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
}
