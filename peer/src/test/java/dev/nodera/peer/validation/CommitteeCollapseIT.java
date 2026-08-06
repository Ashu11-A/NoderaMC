package dev.nodera.peer.validation;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.PipelineState;
import dev.nodera.fallback.CrossRegionRouter;
import dev.nodera.fallback.Route;
import dev.nodera.fallback.RoutingDecision;
import dev.nodera.fallback.RoutingReason;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.testkit.peer.RegionFixtures;
import dev.nodera.testkit.peer.ValidationNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 8 acceptance #2 (issue #8 / debugger intake #17): kill 2-of-3 committee members mid-lease
 * — the region must fall back to the server lane within that lease (quorum loss revokes, the
 * router classifies COMMITTEE_COLLAPSED, the fallback lane commits the action), and when
 * validators return the committee is rebuilt under a bumped epoch and commits in quorum again.
 */
final class CommitteeCollapseIT {

    /**
     * Short on purpose, and the one parameter of this suite that must never be defaulted away: the
     * proposal in the middle of this test CANNOT reach quorum, so the round has to time out inside
     * the test rather than after it. The harness default of five seconds would make the collapse
     * assertion wait on a wall clock instead of on the behaviour.
     */
    private static final long VOTE_TIMEOUT_MILLIS = 700L;

    private final PeerTestHarness harness = PeerTestHarness.create();
    private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);
    private NodeIdentity actor;

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private ValidationNode worker(NodeIdentity id) {
        return harness.validationNode(id).voteTimeoutMillis(VOTE_TIMEOUT_MILLIS).build();
    }

    @Test
    void killingTwoOfThreeFallsBackWithinTheLeaseAndTheRebuiltCommitteeCommitsAgain() {
        NodeIdentity idA = NodeIdentity.generate();
        NodeIdentity idB = NodeIdentity.generate();
        NodeIdentity idC = NodeIdentity.generate();
        actor = NodeIdentity.generate();

        ValidationNode a = worker(idA);
        ValidationNode b = worker(idB);
        ValidationNode c = worker(idC);
        ValidationNode.mesh(List.of(a, b, c), actor);

        RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
        RegionLease lease = new RegionLease(
                region, RegionEpoch.INITIAL, idA.nodeId(),
                List.of(idB.nodeId(), idC.nodeId()), 0, 200);
        for (ValidationNode w : List.of(a, b, c)) {
            w.service().activateRegion(base, lease);
        }

        // --- sanity: the healthy 3-member committee commits batch 1 in quorum ---
        Optional<StateRoot> committed1 =
                a.service().proposeBatch(region, 0, 1, List.of(place(1, 0, 5, 70, 5, 1)));
        assertThat(committed1).as("healthy committee commits").isPresent();
        RegionSnapshot afterBatch1 = a.service().currentSnapshot(region).orElseThrow();

        // --- kill 2 of 3: both validators die mid-lease ---
        b.stop();
        c.stop();

        // --- the next proposal cannot reach quorum: the region revokes WITHIN the lease ---
        Optional<StateRoot> committed2 =
                a.service().proposeBatch(region, 1, 2, List.of(place(2, 1, 6, 70, 6, 1)));
        assertThat(committed2).as("no quorum with 2/3 dead").isEmpty();
        assertThat(a.service().pipelineState(region))
                .as("quorum loss revokes the region inside the lease window")
                .isEqualTo(PipelineState.REVOKED);

        // --- the fallback lane owns the region now: COMMITTEE_COLLAPSED routes to the server
        //     lane and the action commits there (no committee, no partial state) ---
        ActionEnvelope fallbackAction = place(3, 2, 7, 70, 7, 1);
        RoutingDecision decision = a.service().routeAndMaybeFallback(
                fallbackAction, CrossRegionRouter.RegionStatus.COMMITTEE_COLLAPSED, afterBatch1);
        assertThat(decision.reason()).isEqualTo(RoutingReason.COMMITTEE_COLLAPSED);
        assertThat(decision.route()).isEqualTo(Route.SERVER_FALLBACK);
        assertThat(a.service().snapshot().fallbackCommits())
                .as("the server lane committed the action while the committee is down")
                .isEqualTo(1L);

        // --- validators return: rebuild the committee under a bumped epoch and commit again ---
        ValidationNode b2 = worker(idB);
        ValidationNode c2 = worker(idC);
        ValidationNode.mesh(List.of(a, b2, c2), actor);
        RegionLease rebuilt = new RegionLease(
                region, new RegionEpoch(RegionEpoch.INITIAL.value() + 1), idA.nodeId(),
                List.of(idB.nodeId(), idC.nodeId()), 0, 400);
        for (ValidationNode w : List.of(a, b2, c2)) {
            w.service().activateRegion(afterBatch1, rebuilt);
        }

        Optional<StateRoot> committed3 =
                a.service().proposeBatch(region, 2, 3, List.of(place(9, 2, 40, 100, 40, 4)));
        assertThat(committed3)
                .as("the rebuilt committee commits in quorum at epoch+1")
                .isPresent();
        assertThat(a.service().pipelineState(region)).isNotEqualTo(PipelineState.REVOKED);
        assertThat(a.service().latestCertificate(region)).isPresent();
        assertThat(a.service().latestCertificate(region).orElseThrow().epoch())
                .isEqualTo(new RegionEpoch(RegionEpoch.INITIAL.value() + 1));
    }

    @Test
    void crossRegionPlacementRoutesToTheServerLaneAtomically() {
        // Task 8 acceptance #1: a border placement whose target block lies OUTSIDE the envelope's
        // region never enters the committee lane — it routes CROSS_REGION to the server fallback
        // and commits there in one lane (single-writer ⇒ no partial commit by construction).
        NodeIdentity idA = NodeIdentity.generate();
        actor = NodeIdentity.generate();
        ValidationNode a = worker(idA);
        a.registerActor(actor);

        RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
        RegionLease lease = new RegionLease(
                region, RegionEpoch.INITIAL, idA.nodeId(), List.of(), 0, 200);
        a.service().activateRegion(base, lease);

        // x=130 lies in the neighbouring region (region 0,0 owns blocks 0..127).
        ActionEnvelope crossBorder = place(1, 1, 130, 70, 5, 1);
        RoutingDecision decision = a.service().routeAndMaybeFallback(
                crossBorder, CrossRegionRouter.RegionStatus.DELEGATED_HEALTHY, base);

        assertThat(decision.reason()).isEqualTo(RoutingReason.CROSS_REGION);
        assertThat(decision.route()).isEqualTo(Route.SERVER_FALLBACK);
        assertThat(a.service().snapshot().committeeCommits())
                .as("the committee lane never saw the cross-region action")
                .isZero();
        assertThat(a.service().snapshot().fallbackCommits())
                .as("no local execution against the wrong region's base — the target region's "
                        + "server-lane owner executes the routed action")
                .isZero();
    }

    private ActionEnvelope place(long seq, long tick, int x, int y, int z, int stateId) {
        return RegionFixtures.place(actor, region, seq, tick, x, y, z, stateId);
    }
}
