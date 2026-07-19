package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/**
 * A Q32.32 fixed-point three-vector (Task 12a — the determinism rule: every continuous quantity
 * that enters hashed state is fixed-point, never a JVM float/double). Each axis is a 64-bit value
 * whose low 32 bits are the fraction and high 32 bits the integer part (two's-complement, so
 * negatives work). Arithmetic is pure 64-bit integer math — bit-identical across JVMs and
 * hardware, which is why entity position/velocity can live in the region root without breaking the
 * determinism bet (Plan §3, A-5).
 *
 * <p>Wire form: {@code [u16 FIXED_VEC3][u16 ENCODING_VERSION][i64 x][i64 y][i64 z]}.
 *
 * @Thread-context immutable, any thread.
 */
public record FixedVec3(long x, long y, long z) implements Encodable {

    /** One whole unit in the fixed-point scale (2^32). */
    public static final long ONE = 1L << 32;

    /** The zero vector. */
    public static final FixedVec3 ZERO = new FixedVec3(0L, 0L, 0L);

    /** Build a vector from an integer block coordinate (no fractional part). */
    public static FixedVec3 ofBlock(int x, int y, int z) {
        return new FixedVec3((long) x << 32, (long) y << 32, (long) z << 32);
    }

    /** Vector addition (component-wise, fixed-point). */
    public FixedVec3 add(FixedVec3 o) {
        return new FixedVec3(x + o.x, y + o.y, z + o.z);
    }

    /** Vector subtraction (component-wise, fixed-point). */
    public FixedVec3 subtract(FixedVec3 o) {
        return new FixedVec3(x - o.x, y - o.y, z - o.z);
    }

    /** Scale by a fixed-point multiplier (Q32.32); result is the high-32 product (rounding down). */
    public FixedVec3 scale(long fixedMultiplier) {
        return new FixedVec3(mul(x, fixedMultiplier), mul(y, fixedMultiplier), mul(z, fixedMultiplier));
    }

    /** The integer (block) part of each axis. */
    public int blockX() {
        return (int) (x >> 32);
    }

    /** The integer (block) part of each axis. */
    public int blockY() {
        return (int) (y >> 32);
    }

    /** The integer (block) part of each axis. */
    public int blockZ() {
        return (int) (z >> 32);
    }

    /** Signed fixed-point multiply, returning the Q32.32 product (full 64-bit high word). */
    private static long mul(long a, long b) {
        // Q32.32 * Q32.32 = Q64.64; take bits [32..95] as the Q32.32 result.
        return Math.multiplyHigh(a, b) << 32 | (a * b) >>> 32;
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.FIXED_VEC3).writeU16(ENCODING_VERSION);
        w.writeU64(x); // raw two's-complement i64 bits, big-endian
        w.writeU64(y);
        w.writeU64(z);
    }

    /**
     * Full-frame decode.
     *
     * @throws IllegalStateException if the next tag is not {@code FIXED_VEC3}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static FixedVec3 decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.FIXED_VEC3) {
            throw new IllegalStateException("expected FIXED_VEC3 tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        long x = r.readU64();
        long y = r.readU64();
        long z = r.readU64();
        return new FixedVec3(x, y, z);
    }
}
