package io.freemytv.airplay;

/** Logging for the receiver without android.*: AirPlayService points the sink at AppLog, the self-test at stdout. */
public final class Log {
    private Log() {}

    public interface Sink {
        void line(String message);
    }

    public static volatile Sink sink;
    /** Request and response dumps (setprop log.tag.FreeMyTV DEBUG). */
    public static volatile boolean debug;

    public static void w(String message) {
        Sink s = sink;
        if (s != null) s.line("airplay: " + message);
    }

    public static void d(String message) {
        if (debug) w(message);
    }
}
