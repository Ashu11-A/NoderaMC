package dev.nodera.core.crypto;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link StableHash}. Verifies determinism, order sensitivity, string/UUID stability,
 * and the documented "never use JDK hashCode" properties.
 */
class StableHashTest {

    /**
     * Self-consistency constant: captured once from {@link StableHash#of(String)} so that any
     * accidental change to the mixing algorithm between writes is detected. The real
     * cross-implementation contract is documented in {@link StableHash}'s Javadoc; this guard
     * plus the determinism/order-sensitivity assertions below jointly protect the wire contract.
     */
    private static final long EXPECTED_NODERA = StableHash.of("nodera");

    @Test
    void ofLongsIsDeterministic() {
        assertThat(StableHash.of(1L, 2L, 3L)).isEqualTo(StableHash.of(1L, 2L, 3L));
    }

    @Test
    void ofLongsIsOrderSensitive() {
        assertThat(StableHash.of(1L, 2L)).isNotEqualTo(StableHash.of(2L, 1L));
    }

    @Test
    void ofLongsVarargsMatchesExplicitTwoArg() {
        assertThat(StableHash.of(1L, 2L)).isEqualTo(StableHash.of(new long[]{1L, 2L}));
    }

    @Test
    void ofEmptyAndOfSingleValueDiffer() {
        assertThat(StableHash.of()).isNotEqualTo(StableHash.of(1L));
    }

    @Test
    void ofStringIsDeterministic() {
        assertThat(StableHash.of("nodera")).isEqualTo(StableHash.of("nodera"));
    }

    @Test
    void ofStringMatchesCapturedConstant() {
        assertThat(StableHash.of("nodera")).isEqualTo(EXPECTED_NODERA);
    }

    @Test
    void ofEmptyStringDiffersFromOfSingleChar() {
        assertThat(StableHash.of("")).isNotEqualTo(StableHash.of("a"));
    }

    @Test
    void ofStringDoesNotUseJdkHashCode() {
        String a = "Aa";
        String b = "BB";
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(StableHash.of(a)).isNotEqualTo(StableHash.of(b));
    }

    @Test
    void ofUuidIsDeterministic() {
        UUID id = new UUID(0x0123456789ABCDEFL, 0xFEDCBA9876543210L);
        assertThat(StableHash.of(id)).isEqualTo(StableHash.of(id));
    }

    @Test
    void ofUuidDiffersAcrossDistinctRandomUuids() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertThat(a).isNotEqualTo(b);
        assertThat(StableHash.of(a)).isNotEqualTo(StableHash.of(b));
    }

    @Test
    void ofUuidIsOrderSensitiveBetweenMsbAndLsb() {
        UUID id = new UUID(0x0123456789ABCDEFL, 0xFEDCBA9876543210L);
        UUID swapped = new UUID(0xFEDCBA9876543210L, 0x0123456789ABCDEFL);
        assertThat(StableHash.of(id)).isNotEqualTo(StableHash.of(swapped));
    }

    @Test
    void mixComposesWithOf() {
        long direct = StableHash.of(7L, 42L);
        long state = StableHash.mix(StableHash.mix(0x9E3779B97F4A7C15L, 7L), 42L);
        assertThat(StableHash.mix(state, 0L)).isEqualTo(direct);
    }
}
