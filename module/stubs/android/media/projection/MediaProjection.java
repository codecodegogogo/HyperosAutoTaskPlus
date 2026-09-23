package android.media.projection;

import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.view.Surface;

/** API 21+ 公开方法编译桩；实际使用系统 MediaProjection，不打进 dex。 */
public final class MediaProjection {
    public void registerCallback(Callback callback, Handler handler) { throw new RuntimeException("stub"); }
    public void unregisterCallback(Callback callback) { throw new RuntimeException("stub"); }
    public void stop() { throw new RuntimeException("stub"); }
    public VirtualDisplay createVirtualDisplay(String name, int width, int height, int dpi, int flags,
            Surface surface, VirtualDisplay.Callback callback, Handler handler) { throw new RuntimeException("stub"); }
    public static abstract class Callback {
        public void onStop() {}
    }
}
