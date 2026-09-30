package com.aaravlabs.engram.recorder.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an OpMode as recorded and configures how.
 *
 * <p>Optional. Starting a session with {@link
 * com.aaravlabs.engram.recorder.EngramSession#start} already enables recording;
 * this annotation only carries settings, and
 * {@link com.aaravlabs.engram.recorder.EngramSession} reads it reflectively
 * from the OpMode class so the settings do not have to be repeated in code.
 *
 * <pre>{@code
 * @Recorded(label = "TeleOp Drive", flushIntervalMs = 50)
 * @TeleOp(name = "TeleOp Drive")
 * public class MyTeleOp extends SafeOpMode { ... }
 * }</pre>
 *
 * <p>Recorded via reflection, so {@link RetentionPolicy#RUNTIME} is required.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Recorded {

    /**
     * Name recorded in the file header and used to build the filename.
     * Defaults to the OpMode class's simple name.
     */
    String label() default "";

    /**
     * Gap between background flushes, in milliseconds. Lower means less data
     * lost if the process dies and more frequent writes.
     */
    long flushIntervalMs() default com.aaravlabs.engram.recorder.RecorderConfig.DEFAULT_FLUSH_INTERVAL_MS;

    /**
     * Queued events that trigger an immediate flush instead of waiting for the
     * interval.
     */
    int maxEventsPerFlush() default com.aaravlabs.engram.recorder.RecorderConfig.DEFAULT_MAX_EVENTS_PER_FLUSH;

    /**
     * Pins the capture strategy: {@code "auto"} (default), {@code "decorator"},
     * or {@code "publish-listener"}. Only useful when diagnosing which strategy
     * a given Synapse build selects.
     */
    String strategy() default "auto";

    /**
     * Maximum total bytes the output directory may hold in recordings; 0 (the
     * default) for no size limit.
     *
     * <p>Set this or {@link #retentionMaxRecordings()} to opt in to pruning
     * old recordings. Both default to 0, which disables retention entirely: a
     * recorder that deletes files nobody asked it to delete is a worse surprise
     * than a full SD card.
     */
    long retentionMaxBytes() default 0L;

    /**
     * Maximum number of recordings to keep; 0 (the default) for no count
     * limit. See {@link #retentionMaxBytes()}.
     */
    int retentionMaxRecordings() default 0;

    /**
     * Recordings kept whatever the limits say, so an over-tight limit degrades
     * into "kept more than asked for" rather than "no recordings at all".
     */
    int retentionMinRetained() default com.aaravlabs.engram.recorder.RetentionPolicy.DEFAULT_MIN_RETAINED;
}
