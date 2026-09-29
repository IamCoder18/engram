package com.aaravlabs.engram.recorder;

import com.aaravlabs.engram.proto.EngramProto;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueEncoderTest {

    private final List<String> warnings = new ArrayList<>();

    private ValueEncoder encoder() {
        return encoder(RecorderConfig.builder().withLog(warnings::add).build());
    }

    private ValueEncoder encoder(RecorderConfig config) {
        return new ValueEncoder(config);
    }

    @Test
    void encodesBoxedPrimitivesToTheirNativeFields() {
        ValueEncoder e = encoder();

        assertEquals(EngramProto.ValueType.VALUE_TYPE_DOUBLE, e.encode(1.5).valueType());
        assertEquals(1.5, e.encode(1.5).value().getDoubleVal());
        assertEquals(EngramProto.ValueType.VALUE_TYPE_FLOAT, e.encode(1.5f).valueType());
        assertEquals(1.5f, e.encode(1.5f).value().getFloatVal());
        assertEquals(EngramProto.ValueType.VALUE_TYPE_BOOL, e.encode(true).valueType());
        assertTrue(e.encode(true).value().getBoolVal());
        assertEquals(EngramProto.ValueType.VALUE_TYPE_STRING, e.encode("hi").valueType());
        assertEquals("hi", e.encode("hi").value().getStringVal());
    }

    @Test
    void encodesIntegerWidthsCorrectly() {
        ValueEncoder e = encoder();

        assertEquals(EngramProto.ValueType.VALUE_TYPE_INT32, e.encode(42).valueType());
        assertEquals(42, e.encode(42).value().getInt32Val());
        assertEquals(7, e.encode((short) 7).value().getInt32Val());
        assertEquals(3, e.encode((byte) 3).value().getInt32Val());

        assertEquals(EngramProto.ValueType.VALUE_TYPE_INT64, e.encode(9_000_000_000L).valueType());
        assertEquals(9_000_000_000L, e.encode(9_000_000_000L).value().getInt64Val());
    }

    @Test
    void negativeIntegersSurviveZigZagEncoding() {
        ValueEncoder e = encoder();
        // The whole reason sint32/sint64 are used instead of int32/int64.
        for (int v : new int[]{0, -1, 1, -64, 63, -128, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertEquals(v, e.encode(v).value().getInt32Val(), "int32 round trip for " + v);
        }
        for (long v : new long[]{0L, -1L, 1L, Long.MIN_VALUE, Long.MAX_VALUE, -1_000_000_000L}) {
            assertEquals(v, e.encode(v).value().getInt64Val(), "int64 round trip for " + v);
        }
    }

    @Test
    void encodesEnumsByName() {
        ValueEncoder e = encoder();
        ValueEncoder.Encoded encoded = e.encode(Season.QUALIFIER);
        assertEquals(EngramProto.ValueType.VALUE_TYPE_STRING, encoded.valueType());
        assertEquals("QUALIFIER", encoded.value().getStringVal());
        assertEquals(Season.class.getName(), encoded.javaType());
    }

    @Test
    void encodesCharactersAndBigNumbersAsStrings() {
        ValueEncoder e = encoder();
        assertEquals("A", e.encode('A').value().getStringVal());
        assertEquals("123456789012345678901234567890",
                e.encode(new BigInteger("123456789012345678901234567890")).value().getStringVal());
        assertEquals("1.0000000001", e.encode(new BigDecimal("1.0000000001")).value().getStringVal());
    }

    @Test
    void encodesByteArraysVerbatim() {
        ValueEncoder e = encoder();
        byte[] raw = {1, 2, 3, (byte) 0xff};
        ValueEncoder.Encoded encoded = e.encode(raw);
        assertEquals(EngramProto.ValueType.VALUE_TYPE_BYTES, encoded.valueType());
        assertArrayEquals(raw, encoded.value().getBytesVal().toByteArray());
    }

    @Test
    void reportsTheRuntimeClassOfTheValue() {
        ValueEncoder e = encoder();
        assertEquals("java.lang.Double", e.encode(1.0).javaType());
        assertEquals("java.lang.String", e.encode("x").javaType());
    }

    @Test
    void unencodableTypeYieldsEmptyBytesAndOneWarningPerType() {
        ValueEncoder e = encoder();

        ValueEncoder.Encoded first = e.encode(new Widget(1));
        assertFalse(first.encoded());
        assertEquals(EngramProto.ValueType.VALUE_TYPE_BYTES, first.valueType());
        assertTrue(first.value().getBytesVal().isEmpty());

        e.encode(new Widget(2));
        e.encode(new Widget(3));

        assertEquals(1, warnings.size(), "a repeated type must warn only once: " + warnings);
        assertTrue(warnings.get(0).contains("Widget"), warnings.get(0));
    }

    @Test
    void nullIsHandledWithoutThrowing() {
        ValueEncoder e = encoder();
        ValueEncoder.Encoded encoded = e.encode(null);
        assertFalse(encoded.encoded());
        assertEquals(EngramProto.ValueType.VALUE_TYPE_BYTES, encoded.valueType());
    }

    @Test
    void registeredCodecEncodesCustomTypes() {
        ValueEncoder e = encoder(RecorderConfig.builder()
                .withCodec(new WidgetCodec())
                .withLog(warnings::add)
                .build());

        ValueEncoder.Encoded encoded = e.encode(new Widget(99));
        assertTrue(encoded.encoded());
        assertEquals(EngramProto.ValueType.VALUE_TYPE_BYTES, encoded.valueType());
        assertEquals("w:99", encoded.value().getBytesVal().toStringUtf8());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void codecThrowingFromSupportsIsSkippedNotFatal() {
        // Registered first so it is consulted first; the second codec must
        // still get a chance, because a broken codec cannot be allowed to take
        // down the run.
        ValueEncoder e = encoder(RecorderConfig.builder()
                .withCodec(new ValueCodec() {
                    @Override
                    public boolean supports(Class<?> type) {
                        throw new IllegalStateException("boom");
                    }

                    @Override
                    public void encode(Object value, ByteArrayOutputStream out) {
                    }
                })
                .withCodec(new WidgetCodec())
                .withLog(warnings::add)
                .build());

        ValueEncoder.Encoded encoded = e.encode(new Widget(3));
        assertTrue(encoded.encoded(), "a later codec must still encode the value");
        assertEquals("w:3", encoded.value().getBytesVal().toStringUtf8());
        assertEquals(1, warnings.size(), "the throwing codec should be reported once: " + warnings);
        assertTrue(warnings.get(0).contains("supports"), warnings.get(0));
    }

    @Test
    void codecThrowingFromEncodeIsReportedNotFatal() {
        ValueEncoder e = encoder(RecorderConfig.builder()
                .withCodec(new ValueCodec() {
                    @Override
                    public boolean supports(Class<?> type) {
                        return type == Widget.class;
                    }

                    @Override
                    public void encode(Object value, ByteArrayOutputStream out) throws IOException {
                        throw new IOException("nope");
                    }
                })
                .withLog(warnings::add)
                .build());

        ValueEncoder.Encoded encoded = e.encode(new Widget(1));
        assertFalse(encoded.encoded());
        assertTrue(encoded.value().getBytesVal().isEmpty());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("nope"), warnings.get(0));
    }

    @Test
    void javaSerializationFallbackIsOffByDefault() {
        ValueEncoder e = encoder();
        assertFalse(e.encode(new SerialBlob(5)).encoded());
    }

    @Test
    void javaSerializationFallbackCapturesSerializableValuesWhenEnabled() {
        ValueEncoder e = encoder(RecorderConfig.builder()
                .withJavaSerializationFallback(true)
                .withLog(warnings::add)
                .build());

        ValueEncoder.Encoded encoded = e.encode(new SerialBlob(5));
        assertTrue(encoded.encoded());
        assertFalse(encoded.value().getBytesVal().isEmpty());
    }

    @Test
    void codecIsPreferredOverJavaSerialization() {
        ValueEncoder e = encoder(RecorderConfig.builder()
                .withJavaSerializationFallback(true)
                .withCodec(new WidgetCodec())
                .withLog(warnings::add)
                .build());

        assertEquals("w:5", e.encode(new Widget(5)).value().getBytesVal().toStringUtf8());
    }

    @Test
    void warnedTypeCountTracksDistinctFailures() {
        ValueEncoder e = encoder();
        e.encode(new Widget(1));
        e.encode(new SerialBlob(1));
        e.encode(null);
        assertEquals(3, e.warnedTypeCount());
    }

    // ---- fixtures --------------------------------------------------------

    enum Season { QUALIFIER, MEET }

    enum SampleOp { DRIVE, LIFT }

    static final class Widget {
        final int id;

        Widget(int id) {
            this.id = id;
        }
    }

    static final class SerialBlob implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        final int value;

        SerialBlob(int value) {
            this.value = value;
        }
    }

    static final class WidgetCodec implements ValueCodec {
        @Override
        public boolean supports(Class<?> type) {
            return Widget.class.isAssignableFrom(type);
        }

        @Override
        public void encode(Object value, ByteArrayOutputStream out) throws IOException {
            Widget w = (Widget) value;
            out.write(("w:" + w.id).getBytes(StandardCharsets.UTF_8));
        }
    }
}
