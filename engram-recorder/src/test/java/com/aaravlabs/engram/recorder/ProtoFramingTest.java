package com.aaravlabs.engram.recorder;

import com.aaravlabs.engram.proto.EngramProto;
import com.google.protobuf.CodedOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The recorder frames messages itself rather than calling
 * {@code writeDelimitedTo}, so it has to produce byte-identical output. These
 * tests pin that against protobuf's own encoder across the size boundaries
 * where the varint prefix changes width.
 */
class ProtoFramingTest {

    @Test
    void varintSizeMatchesProtobufForBoundaryLengths() throws IOException {
        // Each varint width boundary: the prefix grows at 128, 16384, 2^21.
        int[] lengths = {0, 1, 126, 127, 128, 129, 16383, 16384, 16385, 2097151, 2097152};
        for (int length : lengths) {
            assertEquals(protobufPrefixLength(length), ProtoFraming.varintSize(length),
                    "varintSize(" + length + ")");
        }
    }

    @Test
    void framingIsByteIdenticalToProtobufLengthDelimitedOutput() throws IOException {
        for (int size : new int[]{0, 1, 50, 126, 127, 128, 200, 1000, 16383, 16384, 40000}) {
            byte[] payload = new byte[size];
            for (int i = 0; i < size; i++) {
                payload[i] = (byte) (i * 31);
            }

            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            CodedOutputStream coded = CodedOutputStream.newInstance(expected);
            coded.writeUInt32NoTag(payload.length);
            coded.writeRawBytes(payload);
            coded.flush();

            assertArrayEquals(expected.toByteArray(), ProtoFraming.delimit(payload),
                    "framing differs from protobuf at payload size " + size);
        }
    }

    @Test
    void framingMatchesWriteDelimitedToForRealMessages() throws IOException {
        EngramProto.RecordingHeader header = EngramProto.RecordingHeader.newBuilder()
                .setFormatVersion(1)
                .setOpmodeName("MyTeleOp")
                .setStartEpochMs(1_759_062_000_000L)
                .build();

        EngramProto.RecordingEvent event = EngramProto.RecordingEvent.newBuilder()
                .setRelTimeUs(1_234_567)
                .setPublish(EngramProto.TopicPublish.newBuilder()
                        .setTopicId(42)
                        .setValue(EngramProto.TopicValue.newBuilder().setDoubleVal(-0.125)))
                .build();

        // A short message, and one with a long string so the payload crosses
        // the 127-byte prefix boundary.
        EngramProto.RecordingEvent longEvent = EngramProto.RecordingEvent.newBuilder()
                .setRelTimeUs(2)
                .setTopicDeclaration(EngramProto.TopicDeclaration.newBuilder()
                        .setTopicId(7)
                        .setName("a/very/long/topic/name/that/pushes/the/serialized/message/past"
                                + "/the/one/hundred/twenty/seven/byte/varint/prefix/boundary/for/sure")
                        .setJavaType("java.lang.Double")
                        .setValueType(EngramProto.ValueType.VALUE_TYPE_DOUBLE))
                .build();

        for (com.google.protobuf.MessageLite message :
                new com.google.protobuf.MessageLite[]{header, event, longEvent}) {
            ByteArrayOutputStream reference = new ByteArrayOutputStream();
            message.writeDelimitedTo(reference);

            assertArrayEquals(reference.toByteArray(),
                    ProtoFraming.delimit(message.toByteArray()),
                    "framing differs from writeDelimitedTo for " + message.getClass().getSimpleName());
        }
    }

    @Test
    void delimitedPayloadIsRecoverableByProtobuf() throws IOException {
        EngramProto.RecordingEvent event = EngramProto.RecordingEvent.newBuilder()
                .setRelTimeUs(1_234_567)
                .setPublish(EngramProto.TopicPublish.newBuilder()
                        .setTopicId(42)
                        .setValue(EngramProto.TopicValue.newBuilder().setDoubleVal(-0.125)))
                .build();

        EngramProto.RecordingEvent parsed = EngramProto.RecordingEvent.parseDelimitedFrom(
                new ByteArrayInputStream(ProtoFraming.delimit(event.toByteArray())));

        assertEquals(event, parsed);
    }

    @Test
    void emptyPayloadFramesToASingleZeroByte() {
        assertArrayEquals(new byte[]{0}, ProtoFraming.delimit(new byte[0]));
    }

    /** Number of bytes protobuf itself uses for a length prefix of this size. */
    private static int protobufPrefixLength(int length) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CodedOutputStream coded = CodedOutputStream.newInstance(out);
        coded.writeUInt32NoTag(length);
        coded.flush();
        return out.size();
    }
}
