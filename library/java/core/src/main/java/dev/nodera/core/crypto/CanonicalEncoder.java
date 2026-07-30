package dev.nodera.core.crypto;

import dev.nodera.core.Bytes;

import java.util.Arrays;

/**
 * Documented entry point and convenience façade for Nodera's canonical binary encoding
 * (Task 2). The low-level primitives live in {@link CanonicalWriter} / {@link CanonicalReader};
 * this class wraps the common "fresh writer → encode → bytes" flow and restates the wire format
 * so callers have one authoritative place to consult.
 *
 * <p><b>Canonical format (frozen wire/hash contract — never change without a version bump):</b>
 * <ul>
 *   <li>All integers are <b>big-endian, fixed-width</b>: u8, u16, u32, u64. No varints, no
 *       little-endian, no 7-bit-encoded-ints.</li>
 *   <li><b>No floating-point</b> values appear in hashed or signed state for the MVP.</li>
 *   <li>Every {@link Encodable} body opens with a <b>u16 typeTag + u16 version</b> frame (see
 *       {@link TypeTags} and {@link Encodable#ENCODING_VERSION}), so a single stream of
 *       {@code Encodable}s is self-describing and version-aware.</li>
 *   <li><b>String</b>: u32 UTF-8 byte-length, then the UTF-8 bytes.</li>
 *   <li><b>bytes</b>: u32 length, then the raw bytes.</li>
 *   <li><b>list</b>: u32 element count, then each element. Sets and maps MUST be sorted by a
 *       documented key BEFORE writing — iteration order is explicitly NOT a contract.</li>
 *   <li><b>optional / boolean</b>: a single u8 (0 = absent/false, 1 = present/true).</li>
 *   <li>{@code null} is never written directly; absence is signalled by a u8=0 presence marker,
 *       and the payload is omitted.</li>
 * </ul>
 *
 * <p>Two encodings of the same {@link Encodable} value MUST be byte-identical — see
 * {@link #byteStable(Encodable)} for a self-check. Any type that reads non-deterministic state
 * (wall clocks, RNGs, unordered maps, reused mutable buffers) during encoding violates this
 * contract and will break consensus.
 *
 * <p>Thread-context: any thread; each call allocates its own {@link CanonicalWriter}.
 */
public final class CanonicalEncoder {

    private CanonicalEncoder() {}

    /**
     * Canonical-encode {@code value} via a fresh {@link CanonicalWriter} and return the result
     * as an immutable {@link Bytes}.
     */
    public static Bytes encode(Encodable value) {
        CanonicalWriter writer = new CanonicalWriter();
        value.encode(writer);
        return writer.toBytes();
    }

    /**
     * Canonical-encode {@code value} and return a fresh, caller-owned byte array.
     */
    public static byte[] encodeBytes(Encodable value) {
        CanonicalWriter writer = new CanonicalWriter();
        value.encode(writer);
        return writer.toByteArray();
    }

    /**
     * Self-check helper: encodes {@code value} twice and reports whether the two encodings are
     * byte-identical. Useful as an invariant assertion in tests for new {@link Encodable} types.
     */
    public static boolean byteStable(Encodable value) {
        byte[] first = encodeBytes(value);
        byte[] second = encodeBytes(value);
        return Arrays.equals(first, second);
    }
}
