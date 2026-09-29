package com.aaravlabs.engram.recorder;

/**
 * Encodes a value type that the recorder has no built-in mapping for.
 *
 * <p>Values are written into {@link com.aaravlabs.engram.proto.EngramProto.TopicValue}'s
 * {@code bytes_val} field. The replay tool treats those bytes as opaque, so a codec
 * is free to choose any format. Keep it compact and self-describing; a run can
 * produce hundreds of thousands of these.
 *
 * <p>Register codecs with {@link RecorderConfig.Builder#withCodec(ValueCodec)}.
 * {@link #supports(Class)} is consulted only when the recorder first sees a
 * topic, so implementations should be cheap and must never throw.
 *
 * <p>Implementations must be thread-safe: one encoder instance is shared by
 * every publishing thread.
 */
public interface ValueCodec {

    /**
     * Whether this codec can encode the given runtime type.
     *
     * <p>Called once per topic, on whichever thread first published to it.
     * Must not throw.
     */
    boolean supports(Class<?> type);

    /**
     * Writes the encoded form of {@code value} to {@code out}.
     *
     * <p>Called on the publishing thread, so it should be fast. If it throws,
     * the recorder logs a warning, records the publish with empty bytes, and
     * carries on -- a broken codec must not take down the robot.
     */
    void encode(Object value, java.io.ByteArrayOutputStream out) throws java.io.IOException;
}
