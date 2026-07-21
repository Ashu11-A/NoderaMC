package dev.nodera.consensus;

import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class SpotCheckPolicyTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 3, -5);

    @Test
    void divisorThresholds() {
        assertThat(SpotCheckPolicy.divisorFor(0.5)).isEqualTo(4);
        assertThat(SpotCheckPolicy.divisorFor(0.98)).isEqualTo(8);
        assertThat(SpotCheckPolicy.divisorFor(0.995)).isEqualTo(64);
        assertThat(SpotCheckPolicy.divisorFor(0.0)).isEqualTo(4);
        assertThat(SpotCheckPolicy.divisorFor(0.949)).isEqualTo(4);
        assertThat(SpotCheckPolicy.divisorFor(0.95)).isEqualTo(8);
        assertThat(SpotCheckPolicy.divisorFor(0.989)).isEqualTo(8);
        assertThat(SpotCheckPolicy.divisorFor(0.99)).isEqualTo(64);
        assertThat(SpotCheckPolicy.divisorFor(1.0)).isEqualTo(64);
    }

    @Test
    void deterministicSelection() {
        boolean a = SpotCheckPolicy.shouldCheck(REGION, 42L, 0xCAFEBABEL, 0.98);
        boolean b = SpotCheckPolicy.shouldCheck(REGION, 42L, 0xCAFEBABEL, 0.98);
        boolean c = SpotCheckPolicy.shouldCheck(REGION, 42L, 0xCAFEBABEL, 0.98);
        assertThat(a).isEqualTo(b);
        assertThat(b).isEqualTo(c);
    }

    @Test
    void samplingRateMatchesDivisorForReliability() {
        final long secret = 0x123456789L;
        final double reliability = 0.98;
        final int n = SpotCheckPolicy.divisorFor(reliability);
        assertThat(n).isEqualTo(8);

        int hits = 0;
        int total = 10_000;
        for (long version = 0; version < total; version++) {
            if (SpotCheckPolicy.shouldCheck(REGION, version, secret, reliability)) {
                hits++;
            }
        }
        assertThat(hits)
                .as("expected ~%d hits for N=8 over %d versions", total / n, total)
                .isBetween(1000, 1500);
    }
}
