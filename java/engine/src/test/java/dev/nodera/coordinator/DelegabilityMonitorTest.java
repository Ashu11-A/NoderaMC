package dev.nodera.coordinator;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DelegabilityMonitor} hysteresis (Task 11 acceptance #5/#6, headless): revoke is immediate,
 * restore waits out the cooldown, and an oscillating condition produces exactly one revoke — no
 * flapping.
 */
class DelegabilityMonitorTest {

    private static final long COOLDOWN = 10;

    private final RegionId region = CoordFixtures.region(0, 0);
    private final DelegabilityPolicy policy = new DelegabilityPolicy(3, true);
    private final DelegabilityMonitor monitor = new DelegabilityMonitor(policy, COOLDOWN);

    private static DelegabilityPolicy.Inputs clean() {
        return DelegabilityPolicy.Inputs.delegableFlatMvp(3);
    }

    private static DelegabilityPolicy.Inputs withEntity() {
        return new DelegabilityPolicy.Inputs(true, true, 3, false, true, true, false,
                true, false, false, 0);
    }

    private static DelegabilityPolicy.Inputs withNeighborUnsupported() {
        return new DelegabilityPolicy.Inputs(true, true, 3, false, true, true, false,
                false, true, false, 0);
    }

    private static DelegabilityPolicy.Inputs withInterference(long rate) {
        return new DelegabilityPolicy.Inputs(true, true, 3, false, true, true, false,
                false, false, false, rate);
    }

    @Test
    void entityPresenceRevokesOnTheNextEvaluation() {
        assertThat(monitor.evaluate(region, clean(), 0).transition())
                .isEqualTo(DelegabilityMonitor.Transition.DELEGATED);
        DelegabilityMonitor.Decision d = monitor.evaluate(region, withEntity(), 40);
        assertThat(d.transition()).isEqualTo(DelegabilityMonitor.Transition.REVOKE);
        assertThat(d.reasons()).containsExactly(DelegabilityPolicy.Reason.ENTITY_PRESENT);
        assertThat(monitor.isDelegated(region)).isFalse();
    }

    @Test
    void restoreOnlyAfterAFullCleanCooldown() {
        monitor.evaluate(region, clean(), 0);
        monitor.evaluate(region, withEntity(), 40); // REVOKE
        assertThat(monitor.evaluate(region, clean(), 45).transition())
                .isEqualTo(DelegabilityMonitor.Transition.REVOKED); // clean streak starts at 45
        assertThat(monitor.evaluate(region, clean(), 45 + COOLDOWN - 1).transition())
                .isEqualTo(DelegabilityMonitor.Transition.REVOKED);
        assertThat(monitor.evaluate(region, clean(), 45 + COOLDOWN).transition())
                .isEqualTo(DelegabilityMonitor.Transition.RESTORE);
        assertThat(monitor.isDelegated(region)).isTrue();
    }

    @Test
    void oscillatingEntityNeverFlaps() {
        long revokes = 0;
        long restores = 0;
        // Entity in/out every 4 ticks — faster than the cooldown can ever elapse.
        for (long tick = 0; tick < 40 * COOLDOWN; tick += 4) {
            boolean entityIn = (tick / 4) % 2 == 0;
            DelegabilityMonitor.Transition t =
                    monitor.evaluate(region, entityIn ? withEntity() : clean(), tick).transition();
            if (t == DelegabilityMonitor.Transition.REVOKE) {
                revokes++;
            }
            if (t == DelegabilityMonitor.Transition.RESTORE) {
                restores++;
            }
        }
        assertThat(revokes).isEqualTo(1); // exactly one demotion
        assertThat(restores).isZero();    // never restored while oscillating
        assertThat(monitor.isDelegated(region)).isFalse();
    }

    @Test
    void interferenceStormRevokesAndQuietPeriodAutoRestores() {
        monitor.evaluate(region, clean(), 0);
        DelegabilityMonitor.Decision storm = monitor.evaluate(
                region, withInterference(NoderaConstants.INTERFERENCE_REVOKE_RATE + 1), 100);
        assertThat(storm.transition()).isEqualTo(DelegabilityMonitor.Transition.REVOKE);
        assertThat(storm.reasons()).containsExactly(DelegabilityPolicy.Reason.INTERFERENCE_RATE_HIGH);

        // Dirty evaluations keep resetting the streak.
        assertThat(monitor.evaluate(region, withInterference(NoderaConstants.INTERFERENCE_REVOKE_RATE + 5), 105)
                .transition()).isEqualTo(DelegabilityMonitor.Transition.REVOKED);

        // Quiet period: clean from tick 110 → restore at 110 + COOLDOWN.
        monitor.evaluate(region, clean(), 110);
        assertThat(monitor.evaluate(region, clean(), 110 + COOLDOWN).transition())
                .isEqualTo(DelegabilityMonitor.Transition.RESTORE);
    }

    @Test
    void unsupportedNeighborDemotesTheRegionBeforeBoundaryBleedCanBeSetUp() {
        monitor.evaluate(region, clean(), 0);
        // A redstone torch placed in the adjacent region flips the neighbor-ring palette check:
        // the delegated region is demoted on the next evaluation, so the piston boundary-bleed
        // scenario can never be assembled against a delegated region.
        DelegabilityMonitor.Decision d = monitor.evaluate(region, withNeighborUnsupported(), 40);
        assertThat(d.transition()).isEqualTo(DelegabilityMonitor.Transition.REVOKE);
        assertThat(d.reasons()).containsExactly(DelegabilityPolicy.Reason.NEIGHBOR_UNSUPPORTED);
    }
}
