package dev.nodera.core.crypto;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministic 64-bit hash function (SplitMix64-derived) used for rendezvous placement scoring
 * (Task 6) and RNG seeding (Task 3).
 *
 * <p><b>This is a wire/consensus contract.</b> Every node, every JVM vendor, every JDK version,
 * and every future reimplementation (including cross-language ports) MUST produce bit-identical
 * outputs for identical inputs. Therefore this class <b>NEVER</b> uses any of:
 * <ul>
 *   <li>{@link String#hashCode()} — a 32-bit JDK hash, not specified as cross-version stable;</li>
 *   <li>{@link Object#hashCode()} / identity hash — address- and GC-dependent;</li>
 *   <li>{@link UUID#hashCode()} — a 32-bit hash whose result depends on JDK-internal field order
 *       and is not a documented wire contract.</li>
 * </ul>
 *
 * <p><b>Algorithm (must match across all implementations):</b>
 * <pre>
 *     SEED  = 0x9E3779B97F4A7C15L            // golden-ratio (sqrt(5)-1)/2 fractional bits
 *     state = SEED
 *     for each u64 input value v, in order:
 *         state = state + v                  // unsigned 64-bit wrapping add (mod 2^64)
 *         state = mix64(state)
 *     return mix64(state)                    // one final avalanche with no further add
 *
 *     mix64(z):                              // Stafford variant of Steele/Allen SplitMix64
 *         z = (z xor (z &gt;&gt;&gt; 30)) * 0xBF58476D1CE4E5B9L
 *         z = (z xor (z &gt;&gt;&gt; 27)) * 0x94D049BB133111EBL
 *         return z xor (z &gt;&gt;&gt; 31)
 * </pre>
 * All additions and multiplications are performed modulo 2^64 (Java {@code long} arithmetic).
 * {@code &gt;&gt;&gt;} denotes a logical (unsigned) right shift. The two magic constants are the
 * canonical SplitMix64 mixing coefficients; they MUST NOT be changed.
 *
 * <p>Per-type input ordering (also part of the contract):
 * <ul>
 *   <li>{@code of(long...)}: the values are mixed in array order.</li>
 *   <li>{@code of(String)}: mix the UTF-8 byte count (as u64), then each UTF-8 byte (as u64, in
 *       byte order).</li>
 *   <li>{@code of(UUID)}: mix the {@link UUID#getMostSignificantBits() most-significant} 64 bits
 *       first, then the {@link UUID#getLeastSignificantBits() least-significant} 64 bits.
 *       Explicitly NOT {@code id.hashCode()}.</li>
 * </ul>
 *
 * <p>Thread-context: pure functions with no shared mutable state; safe for any thread.
 */
public final class StableHash {

    /** Golden-ratio fractional constant — the standard SplitMix64 seed. */
    private static final long SEED = 0x9E3779B97F4A7C15L;

    private StableHash() {}

    /**
     * The SplitMix64 mixing function ({@code mix64}). Exposed so callers building composite
     * hashes can apply the same avalanche step to their own running state. Defined as:
     * <pre>
     * z = (z xor (z &gt;&gt;&gt; 30)) * 0xBF58476D1CE4E5B9L
     * z = (z xor (z &gt;&gt;&gt; 27)) * 0x94D049BB133111EBL
     * z =  z xor (z &gt;&gt;&gt; 31)
     * </pre>
     */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * One per-input accumulation step: {@code mix64(state + value)}. Exposed for composability
     * so callers may hash an interleaved stream of longs with the same canonical semantics as
     * {@link #of(long...)}.
     *
     * @param state the running state (initial value should be {@code SEED})
     * @param value the next u64 input
     * @return the new running state
     */
    public static long mix(long state, long value) {
        return mix64(state + value);
    }

    /**
     * Mix a sequence of u64 values, in order, then apply a final avalanche. Returns the
     * canonical 64-bit hash. A {@code null} varargs array is treated as the empty sequence.
     */
    public static long of(long... values) {
        long state = SEED;
        if (values != null) {
            for (long v : values) {
                state = mix64(state + v);
            }
        }
        return mix64(state);
    }

    /** Two-value convenience equivalent to {@code of(new long[]{a, b})}. */
    public static long of(long a, long b) {
        long state = mix64(SEED + a);
        state = mix64(state + b);
        return mix64(state);
    }

    /**
     * Mix a string's UTF-8 encoding: the UTF-8 byte length (as u64) followed by each UTF-8 byte
     * (as u64, in byte order). Never uses {@link String#hashCode()}.
     */
    public static long of(String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        long state = mix64(SEED + utf8.length);
        for (byte b : utf8) {
            state = mix64(state + (b & 0xFFL));
        }
        return mix64(state);
    }

    /**
     * Mix a {@link UUID}: most-significant 64 bits first, then least-significant 64 bits, then
     * a final avalanche. Never uses {@link UUID#hashCode()} and makes the field ordering
     * explicit so cross-JVM ports match.
     */
    public static long of(UUID id) {
        long state = mix64(SEED + id.getMostSignificantBits());
        state = mix64(state + id.getLeastSignificantBits());
        return mix64(state);
    }
}
