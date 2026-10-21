package io.freemytv;

import android.util.Log;

/** The app's log (adb logcat -s FreeMyTV). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("FreeMyTV", message);
    }

    /** More detail, only after `adb shell setprop log.tag.FreeMyTV DEBUG`. */
    static boolean debug() {
        return Log.isLoggable("FreeMyTV", Log.DEBUG);
    }

    static void d(String message) {
        if (debug()) Log.d("FreeMyTV", message);
    }
}
