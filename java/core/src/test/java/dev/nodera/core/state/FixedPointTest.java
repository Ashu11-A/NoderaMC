package dev.nodera.core.state;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

final class FixedPointTest {

    @Property(tries = 10_000)
    void multiplyMatchesSignedFullPrecisionProduct(
            @ForAll long left, @ForAll long right) {
        long expected = BigInteger.valueOf(left)
                .multiply(BigInteger.valueOf(right))
                .shiftRight(32)
                .longValue();

        assertThat(FixedPoint.multiply(left, right)).isEqualTo(expected);
    }

    @Property(tries = 10_000)
    void multiplyingByOnePreservesEveryQ3232Value(@ForAll long value) {
        assertThat(FixedPoint.multiply(value, FixedVec3.ONE)).isEqualTo(value);
    }
}
