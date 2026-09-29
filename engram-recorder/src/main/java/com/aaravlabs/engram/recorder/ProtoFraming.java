package com.aaravlabs.engram.recorder;

/**
 * Length-delimits a serialized protobuf message.
 *
 * <p>Mirrors the framing that {@code CodedOutputStream#writeDelimitedTo}
 * produces: a base-128 varint byte length followed by the payload. Encoding it
 * here rather than calling {@code writeDelimitedTo(OutputStream)} lets the
 * recorder hand the writer thread a finished {@code byte[]} instead of a
 * message it would have to serialize off the hot path.
 */
final class ProtoFraming {

    private ProtoFraming() {
    }

    /** Number of bytes a varint encoding of {@code value} occupies. */
    static int varintSize(int value) {
        int n = 1;
        long v = value & 0xFFFFFFFFL;
        while ((v & ~0x7FL) != 0) {
            v >>>= 7;
            n++;
        }
        return n;
    }

    /** Returns {@code payload} prefixed with its varint byte length. */
    static byte[] delimit(byte[] payload) {
        int length = payload.length;
        int prefix = varintSize(length);
        byte[] out = new byte[prefix + length];

        long v = length & 0xFFFFFFFFL;
        int i = 0;
        while ((v & ~0x7FL) != 0) {
            out[i++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out[i] = (byte) v;

        System.arraycopy(payload, 0, out, prefix, length);
        return out;
    }
}
