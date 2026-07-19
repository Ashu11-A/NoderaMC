package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FixedVec3} — Q32.32 fixed-point vector (Task 12a): arithmetic is pure 64-bit integer math
 * (bit-identical across JVMs, the determinism rule), round-trips canonically, and the block
 * (integer) part is recoverable.
 */
final class FixedVec3Test {

    @Test
    void oneIsTwoToTheThirtyTwo() {
        assertThat(FixedVec3.ONE).isEqualTo(1L << 32);
        assertThat(FixedVec3.ofBlock(3, -2, 5).x()).isEqualTo(3L << 32);
    }

    @Test
    void addAndSubtractArePureIntegerMath() {
        FixedVec3 a = new FixedVec3(FixedVec3.ONE, 2 * FixedVec3.ONE, -FixedVec3.ONE);
        FixedVec3 b = new FixedVec3(FixedVec3.ONE, FixedVec3.ONE, FixedVec3.ONE);
        assertThat(a.add(b)).isEqualTo(new FixedVec3(2 * FixedVec3.ONE, 3 * FixedVec3.ONE, 0));
        assertThat(a.subtract(b)).isEqualTo(new FixedVec3(0, FixedVec3.ONE, -2 * FixedVec3.ONE));
    }

    @Test
    void blockPartIsRecoverableForNegatives() {
        FixedVec3 v = new FixedVec3((-3L << 32) + 7, (5L << 32) - 1, 0);
        assertThat(v.blockX()).isEqualTo(-3);
        assertThat(v.blockY()).isEqualTo(4); // (5<<32)-1 rounds down to block 4
    }

    @Test
    void roundTripsCanonicallyIncludingNegatives() {
        FixedVec3 original = new FixedVec3(-123456789L, 0x123456789L, -1L);
        CanonicalWriter w = new CanonicalWriter();
        original.encode(w);
        FixedVec3 decoded = FixedVec3.decode(new CanonicalReader(w.toBytes().toArray()));
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void determinismSameBitsAcrossInstances() {
        // Same construction ⇒ identical bits ⇒ identical encode (the property that lets entity
        // position live in the root without breaking the determinism bet).
        FixedVec3 a = new FixedVec3(0xABCDEF1212345678L, -42L, 99L);
        FixedVec3 b = new FixedVec3(0xABCDEF1212345678L, -42L, 99L);
        CanonicalWriter wa = new CanonicalWriter();
        CanonicalWriter wb = new CanonicalWriter();
        a.encode(wa);
        b.encode(wb);
        assertThat(wa.toBytes()).isEqualTo(wb.toBytes());
    }
}
