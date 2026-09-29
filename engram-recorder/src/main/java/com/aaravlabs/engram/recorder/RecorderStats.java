package com.aaravlabs.engram.recorder;

import com.aaravlabs.engram.proto.EngramProto;

import java.util.concurrent.atomic.AtomicLong;

/**
 * An immutable snapshot of a recorder's counters.
 *
 * <p>Useful after a run for confirming the recording captured what was
 * expected: a non-zero {@link #unencodableValues()} means some topic values
 * were dropped because no {@link ValueCodec} was registered for their type.
 */
public final class RecorderStats {

    private final long publishesRecorded;
    private final long publishesDropped;
    private final long unencodableValues;
    private final long eventsWritten;
    private final long bytesWritten;
    private final String failureMessage;

    RecorderStats(long publishesRecorded,
                  long publishesDropped,
                  long unencodableValues,
                  long eventsWritten,
                  long bytesWritten,
                  String failureMessage) {
        this.publishesRecorded = publishesRecorded;
        this.publishesDropped = publishesDropped;
        this.unencodableValues = unencodableValues;
        this.eventsWritten = eventsWritten;
        this.bytesWritten = bytesWritten;
        this.failureMessage = failureMessage;
    }

    /** Publishes successfully encoded. */
    public long publishesRecorded() {
        return publishesRecorded;
    }

    /**
     * Publishes discarded because the recorder had already failed, or because
     * encoding threw. Normally zero.
     */
    public long publishesDropped() {
        return publishesDropped;
    }

    /**
     * Publishes whose value type had no encoding, recorded as empty bytes.
     * Non-zero means a {@link ValueCodec} is missing.
     */
    public long unencodableValues() {
        return unencodableValues;
    }

    /** Total protobuf messages written, including lifecycle and declarations. */
    public long eventsWritten() {
        return eventsWritten;
    }

    /** Total bytes written to the recording file. */
    public long bytesWritten() {
        return bytesWritten;
    }

    /** Description of the first I/O failure, or null if the file closed cleanly. */
    public String failureMessage() {
        return failureMessage;
    }

    /** Whether the file closed without an I/O error. */
    public boolean isHealthy() {
        return failureMessage == null;
    }

    @Override
    public String toString() {
        return "RecorderStats{publishes=" + publishesRecorded
                + ", dropped=" + publishesDropped
                + ", unencodable=" + unencodableValues
                + ", events=" + eventsWritten
                + ", bytes=" + bytesWritten
                + (failureMessage == null ? "" : ", failure=" + failureMessage)
                + '}';
    }
}
