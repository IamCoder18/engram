package com.aaravlabs.engram.replay;

import com.aaravlabs.engram.proto.EngramProto;

import java.nio.charset.StandardCharsets;

/**
 * Decodes a {@link EngramProto.TopicValue} into a plain Java object.
 *
 * <p>Returns {@link Double}, {@link Float}, {@link Integer}, {@link Long},
 * {@link Boolean}, {@link String}, or {@code byte[]}. A value with no field
 * set decodes to {@link #UNRECORDED} rather than null, so callers can tell
 * "the recorder could not represent this" apart from a legitimate value.
 */
public final class Values {

    /**
     * Sentinel for a value the recorder had no encoding for. Distinct from
     * null and from every encoded type.
     */
    public static final Object UNRECORDED = new Object() {
        @Override
        public String toString() {
            return "<unrecorded>";
        }
    };

    private Values() {
    }

    /** Decodes one value. Never returns null. */
    public static Object decode(EngramProto.TopicValue value) {
        switch (value.getValCase()) {
            case DOUBLE_VAL:
                return value.getDoubleVal();
            case FLOAT_VAL:
                return value.getFloatVal();
            case INT32_VAL:
                return value.getInt32Val();
            case INT64_VAL:
                return value.getInt64Val();
            case BOOL_VAL:
                return value.getBoolVal();
            case STRING_VAL:
                return value.getStringVal();
            case BYTES_VAL:
                return value.getBytesVal().toByteArray();
            case VAL_NOT_SET:
            default:
                return UNRECORDED;
        }
    }

    /**
     * Decodes to a {@code double} when the value is numeric, otherwise
     * {@link Double#NaN}. Useful for min/max and range queries on sensor
     * topics.
     */
    public static double asDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.NaN;
    }

    /**
     * Renders a decoded value for text output. Byte arrays become a
     * {@code base64:} prefixed string rather than an unreadable list of
     * numbers.
     */
    public static String toText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof byte[]) {
            return "base64:" + java.util.Base64.getEncoder().encodeToString((byte[]) value);
        }
        return String.valueOf(value);
    }

    /** Decodes byte values as UTF-8 text, for string-ish custom encodings. */
    public static String bytesAsText(Object value) {
        if (value instanceof byte[]) {
            return new String((byte[]) value, StandardCharsets.UTF_8);
        }
        return toText(value);
    }
}
