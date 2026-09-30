package com.aaravlabs.engram.recorder;

/**
 * Immutable recorder settings. Build with {@link #builder()}.
 *
 * <p>The defaults are tuned for an FTC match: a 150 second run at 60 Hz on two
 * gamepads plus a handful of sensor topics produces roughly 5 MB on disk, and
 * the synchronous cost per publish is a few microseconds.
 *
 * <p>{@link #retention()} defaults to {@link RetentionPolicy#disabled()}, so an
 * unconfigured recorder never deletes anything. Retention is opt-in on purpose:
 * a recorder that quietly removes files from a team member's robot is a worse
 * surprise than a full SD card, and the storage problem only appears after a
 * season of practice.
 */
public final class RecorderConfig {

    /** Default gap between background flushes, in milliseconds. */
    public static final long DEFAULT_FLUSH_INTERVAL_MS = 100;

    /** Default number of queued events that forces an immediate flush. */
    public static final int DEFAULT_MAX_EVENTS_PER_FLUSH = 500;

    private final long flushIntervalMs;
    private final int maxEventsPerFlush;
    private final ValueCodec[] codecs;
    private final boolean javaSerializationFallback;
    private final RecorderLog log;
    private final RetentionPolicy retention;

    private RecorderConfig(Builder b) {
        this.flushIntervalMs = b.flushIntervalMs;
        this.maxEventsPerFlush = b.maxEventsPerFlush;
        this.codecs = b.codecs.toArray(new ValueCodec[0]);
        this.javaSerializationFallback = b.javaSerializationFallback;
        this.log = b.log;
        this.retention = b.retention;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A configuration using every default. */
    public static RecorderConfig defaults() {
        return builder().build();
    }

    public long flushIntervalMs() {
        return flushIntervalMs;
    }

    public int maxEventsPerFlush() {
        return maxEventsPerFlush;
    }

    public ValueCodec[] codecs() {
        return codecs.clone();
    }

    /**
     * Whether values that implement {@link java.io.Serializable} but have no
     * registered codec are encoded with Java serialization.
     *
     * <p>Off by default. Java serialization bloats files and is brittle across
     * versions, but the escape hatch is useful for a quick experiment.
     */
    public boolean javaSerializationFallback() {
        return javaSerializationFallback;
    }

    /**
     * How many old recordings may be kept. Never null;
     * {@link RetentionPolicy#disabled()} by default.
     */
    public RetentionPolicy retention() {
        return retention;
    }

    RecorderLog log() {
        return log;
    }

    /** Mutable builder for {@link RecorderConfig}. Not thread-safe. */
    public static final class Builder {

        private long flushIntervalMs = DEFAULT_FLUSH_INTERVAL_MS;
        private int maxEventsPerFlush = DEFAULT_MAX_EVENTS_PER_FLUSH;
        private final java.util.List<ValueCodec> codecs = new java.util.ArrayList<>();
        private boolean javaSerializationFallback;
        private RecorderLog log = RecorderLog.SILENT;
        private RetentionPolicy retention = RetentionPolicy.disabled();

        private Builder() {
        }

        /**
         * Gap between background flushes.
         *
         * <p>Also the worst-case data loss if the process dies abnormally:
         * events buffered since the last flush are gone. Normal OpMode
         * shutdown always flushes.
         *
         * @throws IllegalArgumentException if not positive
         */
        public Builder withFlushIntervalMs(long millis) {
            if (millis <= 0) throw new IllegalArgumentException("flushIntervalMs must be > 0, got " + millis);
            this.flushIntervalMs = millis;
            return this;
        }

        /**
         * Queue depth that triggers an immediate flush instead of waiting for
         * the interval.
         *
         * @throws IllegalArgumentException if not positive
         */
        public Builder withMaxEventsPerFlush(int max) {
            if (max <= 0) throw new IllegalArgumentException("maxEventsPerFlush must be > 0, got " + max);
            this.maxEventsPerFlush = max;
            return this;
        }

        /** Adds a codec, consulted after the built-in mappings. */
        public Builder withCodec(ValueCodec codec) {
            if (codec == null) throw new IllegalArgumentException("codec must not be null");
            this.codecs.add(codec);
            return this;
        }

        /**
         * Enables the {@link java.io.Serializable} fallback for values with no
         * registered codec. Off by default.
         */
        public Builder withJavaSerializationFallback(boolean enabled) {
            this.javaSerializationFallback = enabled;
            return this;
        }

        /** Where warnings go. Defaults to discarding them. */
        public Builder withLog(RecorderLog log) {
            this.log = log == null ? RecorderLog.SILENT : log;
            return this;
        }

        /**
         * Bounds how many old recordings the output directory may keep.
         *
         * <p>Null is treated as {@link RetentionPolicy#disabled()}, so a caller
         * that passes null gets the "delete nothing" default rather than a
         * policy that could surprise.
         */
        public Builder withRetention(RetentionPolicy retention) {
            this.retention = retention == null ? RetentionPolicy.disabled() : retention;
            return this;
        }

        public RecorderConfig build() {
            return new RecorderConfig(this);
        }
    }
}
