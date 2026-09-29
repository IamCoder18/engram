package com.aaravlabs.engram.recorder;

import com.aaravlabs.engram.proto.EngramProto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts an arbitrary published value into a {@link EngramProto.TopicValue}.
 *
 * <p>Built-in mappings cover the boxed primitives plus {@link String},
 * {@link Character}, {@link Enum}, {@link BigInteger} and {@link BigDecimal}.
 * Anything else goes through a registered {@link ValueCodec}, then optionally
 * through Java serialization, and finally is recorded with empty bytes and a
 * single warning per type.
 *
 * <p>Instances are thread-safe and stateless apart from the warning
 * de-duplication set, so a single encoder is shared by every publishing thread.
 */
public final class ValueEncoder {

    /** Outcome of encoding one value. */
    public static final class Encoded {

        private final EngramProto.TopicValue value;
        private final EngramProto.ValueType valueType;
        private final String javaType;
        private final boolean encoded;

        Encoded(EngramProto.TopicValue value, EngramProto.ValueType valueType, String javaType, boolean encoded) {
            this.value = value;
            this.valueType = valueType;
            this.javaType = javaType;
            this.encoded = encoded;
        }

        /** The encoded value, never null. */
        public EngramProto.TopicValue value() {
            return value;
        }

        /**
         * How the value is stored.
         *
         * <p>Advisory: it reflects the first value seen for a topic. A topic
         * declared as {@code Number} may legitimately carry an
         * {@code Integer} and then a {@code Double}, so the {@code oneof} in
         * {@link EngramProto.TopicValue} is always the authority, not this.
         */
        public EngramProto.ValueType valueType() {
            return valueType;
        }

        /** Fully qualified runtime class name of the value. */
        public String javaType() {
            return javaType;
        }

        /** False when the value could not be represented and bytes are empty. */
        public boolean encoded() {
            return encoded;
        }
    }

    private final ValueCodec[] codecs;
    private final boolean javaSerializationFallback;
    private final RecorderLog log;
    private final Set<String> warnedTypes = ConcurrentHashMap.newKeySet();

    public ValueEncoder(RecorderConfig config) {
        this.codecs = config.codecs();
        this.javaSerializationFallback = config.javaSerializationFallback();
        this.log = config.log();
    }

    /**
     * Encodes one value. Never throws: a value that cannot be represented is
     * recorded as empty bytes with one warning per type, because aborting a
     * publish mid-match would cost far more than the missing value.
     */
    public Encoded encode(Object value) {
        String javaType = value == null ? "null" : value.getClass().getName();

        if (value == null) {
            warnOnce(javaType, "null value published");
            return new Encoded(EngramProto.TopicValue.getDefaultInstance(),
                    EngramProto.ValueType.VALUE_TYPE_BYTES, javaType, false);
        }

        if (value instanceof Double) {
            return new Encoded(EngramProto.TopicValue.newBuilder().setDoubleVal((Double) value).build(),
                    EngramProto.ValueType.VALUE_TYPE_DOUBLE, javaType, true);
        }
        if (value instanceof Float) {
            return new Encoded(EngramProto.TopicValue.newBuilder().setFloatVal((Float) value).build(),
                    EngramProto.ValueType.VALUE_TYPE_FLOAT, javaType, true);
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            int n = ((Number) value).intValue();
            return new Encoded(EngramProto.TopicValue.newBuilder().setInt32Val(n).build(),
                    EngramProto.ValueType.VALUE_TYPE_INT32, javaType, true);
        }
        if (value instanceof Long) {
            return new Encoded(EngramProto.TopicValue.newBuilder().setInt64Val((Long) value).build(),
                    EngramProto.ValueType.VALUE_TYPE_INT64, javaType, true);
        }
        if (value instanceof Boolean) {
            return new Encoded(EngramProto.TopicValue.newBuilder().setBoolVal((Boolean) value).build(),
                    EngramProto.ValueType.VALUE_TYPE_BOOL, javaType, true);
        }
        if (value instanceof String) {
            return new Encoded(EngramProto.TopicValue.newBuilder().setStringVal((String) value).build(),
                    EngramProto.ValueType.VALUE_TYPE_STRING, javaType, true);
        }
        if (value instanceof Character) {
            return new Encoded(EngramProto.TopicValue.newBuilder().setStringVal(value.toString()).build(),
                    EngramProto.ValueType.VALUE_TYPE_STRING, javaType, true);
        }
        // Enums are common on a robot (modes, states, team-defined enums) and
        // their name is all a recording needs to be useful.
        if (value instanceof Enum) {
            return new Encoded(EngramProto.TopicValue.newBuilder().setStringVal(((Enum<?>) value).name()).build(),
                    EngramProto.ValueType.VALUE_TYPE_STRING, javaType, true);
        }
        if (value instanceof BigInteger || value instanceof BigDecimal) {
            return new Encoded(EngramProto.TopicValue.newBuilder().setStringVal(value.toString()).build(),
                    EngramProto.ValueType.VALUE_TYPE_STRING, javaType, true);
        }
        if (value instanceof byte[]) {
            return new Encoded(EngramProto.TopicValue.newBuilder()
                            .setBytesVal(com.google.protobuf.ByteString.copyFrom((byte[]) value)).build(),
                    EngramProto.ValueType.VALUE_TYPE_BYTES, javaType, true);
        }

        Class<?> type = value.getClass();
        for (ValueCodec codec : codecs) {
            boolean supported;
            try {
                supported = codec.supports(type);
            } catch (RuntimeException e) {
                warnOnce("codec:" + codec.getClass().getName(),
                        "codec threw from supports(" + javaType + "): " + e);
                continue;
            }
            if (!supported) continue;

            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
            try {
                codec.encode(value, bytes);
                return new Encoded(EngramProto.TopicValue.newBuilder()
                                .setBytesVal(com.google.protobuf.ByteString.copyFrom(bytes.toByteArray())).build(),
                        EngramProto.ValueType.VALUE_TYPE_BYTES, javaType, true);
            } catch (IOException | RuntimeException e) {
                warnOnce("codec:" + codec.getClass().getName(),
                        "codec failed to encode " + javaType + ": " + e);
                return new Encoded(EngramProto.TopicValue.getDefaultInstance(),
                        EngramProto.ValueType.VALUE_TYPE_BYTES, javaType, false);
            }
        }

        if (javaSerializationFallback && value instanceof java.io.Serializable) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
                try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                    out.writeObject(value);
                }
                return new Encoded(EngramProto.TopicValue.newBuilder()
                                .setBytesVal(com.google.protobuf.ByteString.copyFrom(bytes.toByteArray())).build(),
                        EngramProto.ValueType.VALUE_TYPE_BYTES, javaType, true);
            } catch (IOException | RuntimeException e) {
                warnOnce(javaType, "java serialization fallback failed: " + e);
            }
        } else if (javaSerializationFallback) {
            warnOnce(javaType, "no codec for " + javaType
                    + "; it is not Serializable and the fallback does not apply");
        } else {
            warnOnce(javaType, "no ValueCodec registered for " + javaType
                    + "; recorded with empty bytes. Register one with"
                    + " RecorderConfig.builder().withCodec(...) to capture it.");
        }

        return new Encoded(EngramProto.TopicValue.getDefaultInstance(),
                EngramProto.ValueType.VALUE_TYPE_BYTES, javaType, false);
    }

    private void warnOnce(String key, String message) {
        if (warnedTypes.add(key)) {
            log.warn(message);
        }
    }

    /** Number of distinct types that have produced a warning. Used by tests and diagnostics. */
    int warnedTypeCount() {
        return warnedTypes.size();
    }
}
