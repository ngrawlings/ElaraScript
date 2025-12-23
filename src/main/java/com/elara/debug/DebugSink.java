package com.elara.debug;

/** Pluggable debug output target (Logcat, stdout, file, etc.). */
public interface DebugSink {
    void log(DebugLevel level, String tag, String message, Throwable error);
}
