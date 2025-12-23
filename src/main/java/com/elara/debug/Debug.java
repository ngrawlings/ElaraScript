package com.elara.debug;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Global debug hub for all Elara components.
 *
 * - Singleton access via Debug.get()
 * - Pluggable sink via setSink(...)
 * - Safe default (no-op) if no sink installed
 */
public final class Debug {

    private static final Debug INSTANCE = new Debug();

    private static final DebugSink NOOP = (level, tag, message, error) -> {
        // intentionally empty
    };

    private final AtomicReference<DebugSink> sinkRef = new AtomicReference<>(NOOP);

    private Debug() {}

    public static Debug get() {
        return INSTANCE;
    }

    public void setSink(DebugSink sink) {
        sinkRef.set(sink == null ? NOOP : sink);
    }

    public DebugSink getSink() {
        return sinkRef.get();
    }

    // Convenience methods
    public void t(String tag, String msg) { log(DebugLevel.TRACE, tag, msg, null); }
    public void d(String tag, String msg) { log(DebugLevel.DEBUG, tag, msg, null); }
    public void i(String tag, String msg) { log(DebugLevel.INFO,  tag, msg, null); }
    public void w(String tag, String msg) { log(DebugLevel.WARN,  tag, msg, null); }
    public void e(String tag, String msg) { log(DebugLevel.ERROR, tag, msg, null); }
    public void e(String tag, String msg, Throwable err) { log(DebugLevel.ERROR, tag, msg, err); }

    public void log(DebugLevel level, String tag, String message, Throwable error) {
        sinkRef.get().log(level, tag, message, error);
    }
}
