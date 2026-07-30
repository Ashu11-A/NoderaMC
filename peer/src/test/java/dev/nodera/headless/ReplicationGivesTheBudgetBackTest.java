package dev.nodera.headless;

import dev.nodera.headless.WorldReplicationService.Placement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The replication sweep only ever grew. `withinBounds` stops adopting once the byte budget is used
 * and nothing ever freed a byte of it, so a node's placement was decided once and for all by
 * whichever worlds happened to exist when it first filled up.
 *
 * <p>Placement is not a one-time fact. It is a deterministic function of the peer set, and the peer
 * set changes whenever anyone joins or leaves — so a node that filled its budget in a five-peer
 * swarm and then watched that swarm reach fifty was holding worlds no policy expected of it and
 * refusing every world the policy did expect, permanently. Worse, it looked correct: a full node's
 * sweep reports "past the bounds", which is what a working bound also reports.
 *
 * <p>These assertions are about the release rule's <b>safety</b> rather than its usefulness. Giving
 * content up is the one thing this lane does that cannot be undone locally, so each case below is a
 * way the rule could have destroyed a replica the swarm still needed.
 */
final class ReplicationGivesTheBudgetBackTest {

    @Test
    @DisplayName("a full node gives up a world the policy no longer places on it")
    void aFullNodeReleasesWhatItIsNoLongerPlacedFor() {
        assertThat(WorldReplicationService.shouldRelease(true, Placement.NOT_PLACED, true, 0))
                .isTrue();
    }

    @Test
    @DisplayName("a tracker outage is not an eviction notice")
    void anUnknownPlacementKeepsTheWorld() {
        // UNKNOWN is "no tracker answered, no peers were listed, or the peer set was malformed".
        // Reading that as NOT_PLACED would empty every full node on the network during one outage
        // and have them all re-fetch when it ended — the failure mode being fixed, amplified.
        assertThat(WorldReplicationService.shouldRelease(true, Placement.UNKNOWN, true, 0))
                .isFalse();
    }

    @Test
    @DisplayName("a node inside its budget releases nothing at all")
    void releaseIsAPressureValveNotTidiness() {
        // No pressure, no release: a node that is not full behaves exactly as it did before this
        // rule existed, so a wrong placement answer costs an unfull node nothing.
        assertThat(WorldReplicationService.shouldRelease(true, Placement.NOT_PLACED, false, 0))
                .isFalse();
    }

    @Test
    @DisplayName("this node's own worlds are never released")
    void ahostedWorldIsNotVolunteeredContent() {
        assertThat(WorldReplicationService.shouldRelease(false, Placement.NOT_PLACED, true, 0))
                .isFalse();
    }

    @Test
    @DisplayName("one world per sweep, so a systematically wrong answer drains a node slowly")
    void releasesAreBounded() {
        assertThat(WorldReplicationService.shouldRelease(true, Placement.NOT_PLACED, true, 1))
                .as("the second release of the same sweep is refused")
                .isFalse();
    }

    @Test
    @DisplayName("being placed is a reason to keep, whatever the pressure")
    void aPlacedWorldSurvivesAFullBudget() {
        assertThat(WorldReplicationService.shouldRelease(true, Placement.PLACED, true, 0))
                .isFalse();
    }
}
