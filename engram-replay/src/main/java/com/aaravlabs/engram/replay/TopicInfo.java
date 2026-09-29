package com.aaravlabs.engram.replay;

import com.aaravlabs.engram.proto.EngramProto;

/**
 * What a recording knows about one topic: its identity, its declared type, and
 * how often it was published to.
 */
public final class TopicInfo {

    private final int id;
    private final String name;
    private final String javaType;
    private final EngramProto.ValueType declaredValueType;
    private final int publishCount;
    private final long firstTimeUs;
    private final long lastTimeUs;
    private final int unrecordedCount;

    TopicInfo(int id,
              String name,
              String javaType,
              EngramProto.ValueType declaredValueType,
              int publishCount,
              long firstTimeUs,
              long lastTimeUs,
              int unrecordedCount) {
        this.id = id;
        this.name = name;
        this.javaType = javaType;
        this.declaredValueType = declaredValueType;
        this.publishCount = publishCount;
        this.firstTimeUs = firstTimeUs;
        this.lastTimeUs = lastTimeUs;
        this.unrecordedCount = unrecordedCount;
    }

    /** Dense id assigned by the recorder, unique within this recording. */
    public int id() {
        return id;
    }

    /** Full topic name. */
    public String name() {
        return name;
    }

    /** Fully qualified Java type of the first value seen, e.g. {@code java.lang.Double}. */
    public String javaType() {
        return javaType;
    }

    /**
     * How the first value was encoded.
     *
     * <p>Advisory only. A topic declared as {@code Number} may carry an
     * {@code Integer} and later a {@code Double}; the per-event encoding is
     * always the authority.
     */
    public EngramProto.ValueType declaredValueType() {
        return declaredValueType;
    }

    /** Number of publishes to this topic in the recording. */
    public int publishCount() {
        return publishCount;
    }

    /** Timestamp of the first publish, or -1 if the topic was never published to. */
    public long firstTimeUs() {
        return firstTimeUs;
    }

    /** Timestamp of the last publish, or -1 if the topic was never published to. */
    public long lastTimeUs() {
        return lastTimeUs;
    }

    /** Publishes whose value could not be encoded, recorded as empty bytes. */
    public int unrecordedCount() {
        return unrecordedCount;
    }

    /** Gap between the first and last publish, in microseconds. */
    public long activeSpanUs() {
        return firstTimeUs < 0 || lastTimeUs < 0 ? 0 : lastTimeUs - firstTimeUs;
    }

    @Override
    public String toString() {
        return "TopicInfo{id=" + id
                + ", name=" + name
                + ", type=" + javaType
                + ", valueType=" + declaredValueType
                + ", publishes=" + publishCount
                + (unrecordedCount > 0 ? ", unrecorded=" + unrecordedCount : "")
                + '}';
    }
}
