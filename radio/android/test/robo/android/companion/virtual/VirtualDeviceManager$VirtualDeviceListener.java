package android.companion.virtual;

/**
 * Only for the tests: Robolectric 4.18.1 names this Android class in one of its stand-ins, but its Android 14 library
 * does not have it, so every screen test would stop with NoClassDefFoundError. It is not part of the app.
 */
public interface VirtualDeviceManager$VirtualDeviceListener {
    default void onVirtualDeviceCreated(int deviceId) {
    }

    default void onVirtualDeviceClosed(int deviceId) {
    }
}
