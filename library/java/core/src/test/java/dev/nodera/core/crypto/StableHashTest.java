package dev.nodera.core.crypto;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link StableHash}. Verifies determinism, order sensitivity, string/UUID stability,
 * and the documented "never use JDK hashCode" properties.
 */
class StableHashTest {

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

    /**
     * The golden vectors. These are <b>literals</b>, and that is the entire point of this test.
     *
     * <p>It used to hold {@code EXPECTED_NODERA = StableHash.of("nodera")} — the function under
     * test, called at class-init — and then assert {@code StableHash.of("nodera")} equalled it.
     * That assertion is {@code x == x}: it holds for every possible implementation of
     * {@code StableHash}, including one whose mixing constants had been changed, and its Javadoc
     * claimed it "detects any accidental change to the mixing algorithm". It detected nothing.
     *
     * <p>{@code StableHash}'s own Javadoc says it is a wire/consensus contract that every
     * reimplementation must reproduce bit-identically. A contract needs bytes somebody wrote down,
     * so here they are, one per input shape, captured from the SplitMix64 definition in that
     * Javadoc. A future port — Rust, TypeScript, anything — should assert against exactly these
     * numbers; there is no second implementation of {@code StableHash} in this repository today.
     *
     * <p>If this test fails, the mixing algorithm changed. That is a network split, not a nit:
     * placement scores and RNG seeds derived from it would disagree between an updated node and
     * every node that has not updated.
     */
    @Test
    void ofMatchesTheGoldenVectors() {
        // of(long...) — including the empty sequence, whose final avalanche of the bare SEED is
        // the one value a "hash of nothing" could plausibly be got wrong on.
        assertThat(StableHash.of()).isEqualTo(0xE220A8397B1DCDAFL);
        assertThat(StableHash.of(1L)).isEqualTo(0xDCE423FC82C0D5B8L);
        assertThat(StableHash.of(1L, 2L, 3L)).isEqualTo(0x9DBB23E8DECE0464L);

        // of(long, long) — the hand-unrolled two-argument overload, pinned separately from the
        // varargs path it is supposed to equal.
        assertThat(StableHash.of(1L, 2L)).isEqualTo(0x30CFBC46A7E35530L);
        assertThat(StableHash.of(2L, 1L)).isEqualTo(0x2BA98B4A92374867L);

        // of(String) — UTF-8 byte length, then each byte. "é" is two UTF-8 bytes, so it pins the
        // "bytes, not chars" half of the contract: a port hashing UTF-16 code units would agree
        // on every ASCII vector above and differ here.
        assertThat(StableHash.of("")).isEqualTo(0x48218226FF3CD4BFL);
        assertThat(StableHash.of("a")).isEqualTo(0x1581E75CAB1277DAL);
        assertThat(StableHash.of("nodera")).isEqualTo(0x7F4D5E2BA93DC3B2L);
        assertThat(StableHash.of("é")).isEqualTo(0xF9D494F314F33C84L);

        // of(UUID) — most-significant bits first, then least-significant.
        assertThat(StableHash.of(new UUID(0x0123456789ABCDEFL, 0xFEDCBA9876543210L)))
                .isEqualTo(0x3BFB451C57BB4186L);
        assertThat(StableHash.of(new UUID(0xFEDCBA9876543210L, 0x0123456789ABCDEFL)))
                .isEqualTo(0xE600E0519C21E85FL);

        // mix(state, value) — one accumulation step from the documented SEED.
        assertThat(StableHash.mix(0x9E3779B97F4A7C15L, 7L)).isEqualTo(0x63CBE1E459320DD7L);
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
