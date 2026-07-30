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

    @org.junit.jupiter.api.Test
    void steadyStateReExecutionShareForProvenCommitteesIsAtMostTwoPercent() {
        // The L-22 exit measurement: over a long committed-version run, a proven committee
        // (reliability >= 0.99, N = 64) must cost the server <= 2% re-execution. The sampling
        // hash is uniform, so the empirical share converges on 1/64 ~ 1.56%; assert the ledger
        // criterion (2%) with room for hash variance across regions and secrets.
        dev.nodera.core.region.RegionId[] regions = {
                new dev.nodera.core.region.RegionId(
                        dev.nodera.core.region.DimensionKey.overworld(), 0, 0),
                new dev.nodera.core.region.RegionId(
                        dev.nodera.core.region.DimensionKey.overworld(), -3, 7),
                new dev.nodera.core.region.RegionId(
                        dev.nodera.core.region.DimensionKey.overworld(), 12, -9),
        };
        long[] secrets = {0xA5A5_5A5AL, 0x1234_5678_9ABCL};
        int checks = 0;
        int total = 0;
        for (dev.nodera.core.region.RegionId region : regions) {
            for (long secret : secrets) {
                for (long version = 0; version < 20_000; version++) {
                    total++;
                    if (SpotCheckPolicy.shouldCheck(region, version, secret, 0.995)) {
                        checks++;
                    }
                }
            }
        }
        double share = (double) checks / total;
        assertThat(share)
                .as("steady-state server re-execution share for proven committees")
                .isLessThanOrEqualTo(0.02)
                .isGreaterThan(0.005); // sanity: sampling still happens (~1/64)
    }

    @org.junit.jupiter.api.Test
    void freshCommitteesKeepTheTightSamplingFloor() {
        // Below the assignment floor the divisor stays 4 (~25%): trust is earned, not assumed.
        dev.nodera.core.region.RegionId region = new dev.nodera.core.region.RegionId(
                dev.nodera.core.region.DimensionKey.overworld(), 0, 0);
        int checks = 0;
        for (long version = 0; version < 20_000; version++) {
            if (SpotCheckPolicy.shouldCheck(region, version, 0xBEEFL, 0.0)) {
                checks++;
            }
        }
        assertThat((double) checks / 20_000).isBetween(0.2, 0.3);
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
