package com.aaravlabs.engram.replay;

import com.aaravlabs.engram.proto.EngramProto;
import com.google.protobuf.ByteString;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds recording files directly from protobuf messages, with no dependency on
 * the recorder.
 *
 * <p>Deliberate: it means the reader is tested against input it did not
 * produce, so a matching mistake in the writer and the reader cannot hide.
 */
final class TestRecordings {

    private TestRecordings() {
    }

    static EngramProto.TopicPublish publish(int topicId, EngramProto.TopicValue value) {
        return EngramProto.TopicPublish.newBuilder().setTopicId(topicId).setValue(value).build();
    }

    static EngramProto.RecordingEvent sample(long timeUs, int topicId, EngramProto.TopicValue value) {
        return EngramProto.RecordingEvent.newBuilder()
                .setRelTimeUs(timeUs)
                .setPublish(publish(topicId, value))
                .build();
    }

    static EngramProto.TopicDeclaration declare(int id, String name, String javaType,
                                                EngramProto.ValueType valueType) {
        return EngramProto.TopicDeclaration.newBuilder()
                .setTopicId(id)
                .setName(name)
                .setJavaType(javaType)
                .setValueType(valueType)
                .build();
    }

    static EngramProto.RecordingEvent declaration(EngramProto.TopicDeclaration declaration, long timeUs) {
        return EngramProto.RecordingEvent.newBuilder()
                .setRelTimeUs(timeUs)
                .setTopicDeclaration(declaration)
                .build();
    }

    static EngramProto.RecordingEvent lifecycle(long timeUs, EngramProto.LifecycleEvent.Type type) {
        return EngramProto.RecordingEvent.newBuilder()
                .setRelTimeUs(timeUs)
                .setLifecycle(EngramProto.LifecycleEvent.newBuilder().setType(type))
                .build();
    }

    static EngramProto.TopicValue d(double v) {
        return EngramProto.TopicValue.newBuilder().setDoubleVal(v).build();
    }

    static EngramProto.TopicValue f(float v) {
        return EngramProto.TopicValue.newBuilder().setFloatVal(v).build();
    }

    static EngramProto.TopicValue i(int v) {
        return EngramProto.TopicValue.newBuilder().setInt32Val(v).build();
    }

    static EngramProto.TopicValue l(long v) {
        return EngramProto.TopicValue.newBuilder().setInt64Val(v).build();
    }

    static EngramProto.TopicValue b(boolean v) {
        return EngramProto.TopicValue.newBuilder().setBoolVal(v).build();
    }

    static EngramProto.TopicValue s(String v) {
        return EngramProto.TopicValue.newBuilder().setStringVal(v).build();
    }

    static EngramProto.TopicValue bytes(byte[] v) {
        return EngramProto.TopicValue.newBuilder().setBytesVal(ByteString.copyFrom(v)).build();
    }

    static EngramProto.TopicValue unset() {
        return EngramProto.TopicValue.getDefaultInstance();
    }

    static EngramProto.RecordingHeader header(String opMode, int version, long epochMs) {
        return EngramProto.RecordingHeader.newBuilder()
                .setOpmodeName(opMode)
                .setFormatVersion(version)
                .setStartEpochMs(epochMs)
                .build();
    }

    /** Serialises a header plus events into a length-delimited stream. */
    static byte[] file(EngramProto.RecordingHeader header, List<EngramProto.RecordingEvent> events)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        header.writeDelimitedTo(out);
        for (EngramProto.RecordingEvent event : events) {
            event.writeDelimitedTo(out);
        }
        return out.toByteArray();
    }

/**
     * Appends a deliberately partial message to a complete file.
     *
     * <p>Deterministic where slicing a file at an arbitrary offset is not: a
     * cut can land on a message boundary, which is a clean end of stream rather
     * than truncation. A length prefix promising more bytes than actually
     * follow is unambiguously a truncated tail.
     *
     * @param promised bytes the varint prefix claims; one fewer is written
     */
    static byte[] withTruncatedTail(byte[] complete, int promised) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(complete, 0, complete.length);
        out.write(promised);                       // varint length prefix
        for (int i = 0; i < promised - 1; i++) {  // ...but under-deliver
            out.write(0x00);
        }
        return out.toByteArray();
    }

    /** A small, fully populated recording used by most reader tests. */
    static byte[] sampleFile() throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(lifecycle(0, EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT));
        events.add(declaration(declare(0, "drive/power", "java.lang.Double",
                EngramProto.ValueType.VALUE_TYPE_DOUBLE), 100));
        events.add(sample(100, 0, d(0.25)));
        events.add(sample(200, 0, d(0.5)));
        events.add(declaration(declare(1, "g1/a", "java.lang.Boolean",
                EngramProto.ValueType.VALUE_TYPE_BOOL), 150));
        events.add(sample(150, 1, b(true)));
        events.add(sample(400, 0, d(-0.75)));
        events.add(lifecycle(500, EngramProto.LifecycleEvent.Type.LIFECYCLE_START));
        events.add(sample(600, 1, b(false)));
        events.add(lifecycle(1000, EngramProto.LifecycleEvent.Type.LIFECYCLE_STOP));
        return file(header("Fixture", 1, 1_700_000_000_000L), events);
    }
}
