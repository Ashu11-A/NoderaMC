package dev.nodera.simulation;

import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden-value pins for {@link DeterministicRandom} — the engine's ONLY permitted entropy source.
 * The whole determinism bet rests on L64X128MixRandom producing identical streams on every
 * replica: these literals were captured once on the pinned toolchain (JDK 21, 2026-07-23) and
 * must reproduce forever. A JDK vendor swap or factory-name typo that changes the stream breaks
 * consensus; this test is the tripwire.
 *
 * <p>Thread-context: single test thread.
 */
final class DeterministicRandomTest {

    private static final long WORLD_SEED = 12345L;

    @Test
    void seed42LongStreamMatchesGoldenValues() {
        DeterministicRandom r = new DeterministicRandom(42L);
        assertThat(r.nextLong()).isEqualTo(-5600175640509174766L);
        assertThat(r.nextLong()).isEqualTo(-6068984182338578160L);
        assertThat(r.nextLong()).isEqualTo(-5395223390448918216L);
        assertThat(r.nextLong()).isEqualTo(-3406073520336850452L);
    }

    @Test
    void seed42IntStreamAfterFourLongsMatchesGoldenValues() {
        DeterministicRandom r = new DeterministicRandom(42L);
        r.nextLong();
        r.nextLong();
        r.nextLong();
        r.nextLong();
        assertThat(r.nextInt(16)).isEqualTo(14);
        assertThat(r.nextInt(16)).isEqualTo(15);
        assertThat(r.nextInt(16)).isEqualTo(14);
        assertThat(r.nextInt(16)).isEqualTo(15);
    }

    @Test
    void sameSeedProducesIdenticalStreams() {
        DeterministicRandom a = new DeterministicRandom(9876L);
        DeterministicRandom b = new DeterministicRandom(9876L);
        for (int i = 0; i < 64; i++) {
            assertThat(b.nextLong()).isEqualTo(a.nextLong());
        }
    }

    @Test
    void differentSeedsDiverge() {
        DeterministicRandom a = new DeterministicRandom(1L);
        DeterministicRandom b = new DeterministicRandom(2L);
        boolean diverged = false;
        for (int i = 0; i < 8 && !diverged; i++) {
            diverged = a.nextLong() != b.nextLong();
        }
        assertThat(diverged).isTrue();
    }

    @Test
    void seedForMatchesGoldenValuesIncludingNegativeRegions() {
        RegionExecutionContext origin = context(TestFixtures.region(0, 0));
        RegionExecutionContext negative = context(TestFixtures.region(-1, -1));
        assertThat(DeterministicRandom.seedFor(origin, WORLD_SEED, 1L))
                .isEqualTo(831464023739363553L);
        assertThat(DeterministicRandom.seedFor(negative, WORLD_SEED, 7L))
                .isEqualTo(-8119055324551158698L);
    }

    @Test
    void seedForIsPureAndSequenceSensitive() {
        RegionExecutionContext ctx = context(TestFixtures.region(0, 0));
        assertThat(DeterministicRandom.seedFor(ctx, WORLD_SEED, 1L))
                .isEqualTo(DeterministicRandom.seedFor(ctx, WORLD_SEED, 1L));
        assertThat(DeterministicRandom.seedFor(ctx, WORLD_SEED, 2L))
                .isNotEqualTo(DeterministicRandom.seedFor(ctx, WORLD_SEED, 1L));
    }

    @Test
    void nextIntRejectsNonPositiveBound() {
        DeterministicRandom r = new DeterministicRandom(1L);
        assertThatThrownBy(() -> r.nextInt(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static RegionExecutionContext context(dev.nodera.core.region.RegionId region) {
        return new RegionExecutionContext(
                region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0L, 2L, WORLD_SEED,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
    }
}
