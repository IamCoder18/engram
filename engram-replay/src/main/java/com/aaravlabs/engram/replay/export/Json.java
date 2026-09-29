package com.aaravlabs.engram.replay.export;

import java.io.IOException;
import java.io.Writer;

/**
 * Minimal JSON writing helpers.
 *
 * <p>Hand-rolled rather than pulled from a library: the replay tool's only
 * dependency is the protobuf runtime, and adding a JSON library to ship a
 * dependency-free reader would be a poor trade. Only the pieces needed to emit
 * valid JSON are implemented.
 */
public final class Json {

    private Json() {
    }

    /** Escapes a string for use as a JSON string body, including the quotes. */
    public static String quote(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Renders a number as JSON, or {@code null} for the non-finite values JSON
     * cannot represent. Keeping them out of the output beats emitting invalid
     * JSON that breaks a consumer's parser.
     */
    public static String number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "null";
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            long asLong = (long) d;
            return asLong + ".0";
        }
        return String.valueOf(d);
    }

    /** Renders a long as JSON. */
    public static String number(long v) {
        return String.valueOf(v);
    }

    /** Writes {@code {"key":value,...}} for an already-built key/value list. */
    public static void writeObject(Writer out, String... keysAndValues) throws IOException {
        out.write('{');
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (i > 0) {
                out.write(',');
            }
            out.write(quote(keysAndValues[i]));
            out.write(':');
            out.write(keysAndValues[i + 1]);
        }
        out.write('}');
    }
}
