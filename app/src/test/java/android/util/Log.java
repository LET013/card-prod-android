package android.util;

/**
 * JVM unit-test replacement for the Android logging facade.
 *
 * <p>The production implementation is supplied by Android. Local unit tests run without an
 * Android runtime, so logging must stay side-effect free instead of aborting the simulator
 * callback thread.</p>
 */
public final class Log {
    private Log() { }

    public static int d(String tag, String message) {
        return 0;
    }

    public static int e(String tag, String message) {
        return 0;
    }

    public static int e(String tag, String message, Throwable throwable) {
        return 0;
    }

    public static int i(String tag, String message) {
        return 0;
    }

    public static int w(String tag, String message) {
        return 0;
    }

    public static int w(String tag, String message, Throwable throwable) {
        return 0;
    }
}
