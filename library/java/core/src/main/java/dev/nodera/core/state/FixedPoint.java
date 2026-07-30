package dev.nodera.core.state;

/** Deterministic scalar operations for signed Q32.32 fixed-point values. */
public final class FixedPoint {

    private FixedPoint() {
    }

    /**
     * Multiply two signed Q32.32 values, returning bits 32 through 95 of the Q64.64 product.
     */
    public static long multiply(long left, long right) {
        return Math.multiplyHigh(left, right) << 32 | (left * right) >>> 32;
    }
}
