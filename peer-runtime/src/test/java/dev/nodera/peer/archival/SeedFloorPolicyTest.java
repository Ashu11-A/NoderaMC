package dev.nodera.peer.archival;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The floor/cap thresholds realise spec rules 1 and 3. The math must hit the spec's flat 25%/5% at
 * the crossover points and decay correctly, and the {@code FULL_ARCHIVE} host must be exempt.
 *
 * <p>Thread-context: single test thread.
 */
final class SeedFloorPolicyTest {

    @Test
    void floorIs25PercentUntilRnDecaysBelowIt() {
        // R=5, N=20: R/N = 25% exactly — the crossover point.
        assertThat(SeedFloorPolicy.floorFraction(5, 20)).isEqualTo(0.25);
        // Smaller N: flat 25% binds (R/N > 25%).
        assertThat(SeedFloorPolicy.floorFraction(5, 10)).isEqualTo(0.25);
        // Larger N: R/N decays below 25%.
        assertThat(SeedFloorPolicy.floorFraction(5, 200)).isEqualTo(0.025);
    }

    @Test
    void capIsTwoTimesRnUntilTheFlat5PercentBinds() {
        // R=5, N=20: 2·R/N = 50% > 5%, so cap = 50%.
        assertThat(SeedFloorPolicy.capFraction(5, 20)).isEqualTo(0.50);
        // R=5, N=200: 2·R/N = 5% = flat → cap = 5%.
        assertThat(SeedFloorPolicy.capFraction(5, 200)).isEqualTo(0.05);
        // Larger still: flat 5% binds.
        assertThat(SeedFloorPolicy.capFraction(5, 1000)).isEqualTo(0.05);
    }

    @Test
    void theFloorIsAlwaysBelowTheCapSoTheTwoCannotConflict() {
        for (int n = 1; n <= 1000; n++) {
            double floor = SeedFloorPolicy.floorFraction(5, n);
            double cap = SeedFloorPolicy.capFraction(5, n);
            assertThat(cap)
                    .as("cap > floor at N=%d", n)
                    .isGreaterThan(floor);
        }
    }

    @Test
    void basisPointAccessorsQuantiseWithoutFloatOnTheWire() {
        assertThat(SeedFloorPolicy.floorBps(5, 200)).isEqualTo(250);   // 2.5%
        assertThat(SeedFloorPolicy.capBps(5, 200)).isEqualTo(500);     // 5%
        assertThat(SeedFloorPolicy.capBps(5, 20)).isEqualTo(5000);     // 50%
    }

    @Test
    void classifiesAroundTheFloorAndCapAtNetworkSize20() {
        // N=20, R=5 → floor 25%, cap 50%.
        int total = 100;
        // Below floor (10/100 = 10% < 25%): free-rider.
        assertThat(SeedFloorPolicy.classify(10, total, 5, 20, false))
                .isEqualTo(SeedFloorPolicy.Classification.BELOW_FLOOR);
        // Within [25%, 50%].
        assertThat(SeedFloorPolicy.classify(30, total, 5, 20, false))
                .isEqualTo(SeedFloorPolicy.Classification.WITHIN);
        // Above cap (60/100 = 60% > 50%): redistribute.
        assertThat(SeedFloorPolicy.classify(60, total, 5, 20, false))
                .isEqualTo(SeedFloorPolicy.Classification.ABOVE_CAP);
    }

    @Test
    void classifiesAroundTheFloorAndCapAtNetworkSize200() {
        // N=200, R=5 → floor 2.5%, cap 5%.
        int total = 1000;
        assertThat(SeedFloorPolicy.classify(10, total, 5, 200, false))   // 1% < 2.5%
                .isEqualTo(SeedFloorPolicy.Classification.BELOW_FLOOR);
        assertThat(SeedFloorPolicy.classify(35, total, 5, 200, false))   // 3.5% within
                .isEqualTo(SeedFloorPolicy.Classification.WITHIN);
        assertThat(SeedFloorPolicy.classify(80, total, 5, 200, false))   // 8% > 5%
                .isEqualTo(SeedFloorPolicy.Classification.ABOVE_CAP);
    }

    @Test
    void theFullArchiveHostIsExemptEvenAt100Percent() {
        // 100/100 = 100% would be far above any cap, but the host is exempt.
        assertThat(SeedFloorPolicy.classify(100, 100, 5, 20, true))
                .isEqualTo(SeedFloorPolicy.Classification.EXEMPT);
    }

    @Test
    void rejectsNonPositiveInputs() {
        assertThatThrownBy(() -> SeedFloorPolicy.floorFraction(0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeedFloorPolicy.classify(1, 0, 5, 20, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeedFloorPolicy.classify(-1, 100, 5, 20, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
