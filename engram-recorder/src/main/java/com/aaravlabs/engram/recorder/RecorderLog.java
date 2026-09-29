package com.aaravlabs.engram.recorder;

/**
 * Receives a diagnostic message from the recorder. Kept separate from
 * {@code java.util.logging} so the recorder does not force a logging
 * framework onto the host application, and so tests can capture warnings
 * without configuring anything.
 */
@FunctionalInterface
public interface RecorderLog {

    /** Receives a warning. Implementations must not throw. */
    void warn(String message);

    /** A log that discards everything. This is the default. */
    RecorderLog SILENT = message -> { };

    /** A log that writes to {@code System.err}, matching Synapse's default sink. */
    static RecorderLog stderr() {
        return message -> System.err.println("[engram] WARN " + message);
    }
}
