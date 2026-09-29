package com.aaravlabs.engram.replay;

import com.aaravlabs.engram.proto.EngramProto;

/**
 * One publish captured in a recording: when it happened and what it carried.
 */
public final class Sample {

    private final long relTimeUs;
    private final Object value;

    Sample(long relTimeUs, Object value) {
        this.relTimeUs = relTimeUs;
        this.value = value;
    }

    /** Microseconds since the start of the recording. */
    public long timeUs() {
        return relTimeUs;
    }

    /** The decoded value; {@link Values#UNRECORDED} if it could not be encoded. */
    public Object value() {
        return value;
    }

    /** Whether this sample carries a real value rather than the unrecorded sentinel. */
    public boolean hasValue() {
        return value != Values.UNRECORDED;
    }

    @Override
    public String toString() {
        return timeUs() + "us=" + Values.toText(value);
    }
}
