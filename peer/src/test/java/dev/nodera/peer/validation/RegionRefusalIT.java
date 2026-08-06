package dev.nodera.peer.validation;

import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.coordinator.LeaseManager;
import dev.nodera.coordinator.PipelineState;
import dev.nodera.protocol.simulationmsg.RegionRefusal;
import dev.nodera.testkit.peer.Await;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.testkit.peer.RegionFixtures;
import dev.nodera.testkit.peer.ValidationNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-60 — a region that cannot be validated must stop being validated on the nodes that <b>own</b>
 * it, even when the node that noticed owns none of it.
 *
 * <p>The condition this exists for is a mob in a dimension that never opted into
 * {@code entity.mobCaptureDimensions}. That fact is visible wherever the entity spawns — the
 * session server — and under field-of-view ownership the session server owns nothing: every region
 * belongs to a player's node. The old code gated the refusal behind `delegated(region)`, so the one
 * node that could see the problem was the one node forbidden from acting on it, and the region
 * simply carried on unvalidated and silent.
 *
 * <p>{@code RegionRefusal} (tag 61) closes that gap: the observer announces, the owners drop the
 * region.
 */
final class RegionRefusalIT {

    private final PeerTestHarness harness = PeerTestHarness.create();
    private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void anObserverThatOwnsNothingStillStopsTheRegionEverywhere() {
        ValidationNode owner = harness.validationNode().build();     // a player's node: holds the seat
        ValidationNode validator = harness.validationNode().build(); // a second seat on the committee
        ValidationNode observer = harness.validationNode().build();  // the session server: owns NOTHING
        ValidationNode.mesh(List.of(owner, validator, observer));

        RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
        RegionLease lease = new LeaseManager(200).issue(
                region, owner.nodeId(), List.of(validator.nodeId()), 0);
        owner.service().activateRegion(base, lease);
        validator.service().activateRegion(base, lease);
        assertThat(owner.service().pipelineState(region)).isNotEqualTo(PipelineState.REVOKED);

        // The observer holds no replica of the region at all — exactly the dedicated server's
        // position in every live topology ("no regions fall to this node").
        assertThat(observer.service().currentSnapshot(region)).isEmpty();

        boolean first = observer.service().refuseRegion(
                region, RegionRefusal.Reason.NON_DELEGABLE_ENTITY);
        assertThat(first).as("the first refusal of a region is the one that logs").isTrue();
        assertThat(observer.service().refuseRegion(
                region, RegionRefusal.Reason.NON_DELEGABLE_ENTITY))
                .as("a dimension full of mobs must not announce once per spawn")
                .isFalse();

        Await.quietly(10_000, () ->
                owner.service().isRefused(region) && validator.service().isRefused(region));

        assertThat(owner.service().isRefused(region))
                .as("the owning player's node acted on a refusal it could not have observed itself")
                .isTrue();
        assertThat(validator.service().isRefused(region)).isTrue();
        assertThat(owner.service().currentSnapshot(region))
                .as("the replica is dropped, not merely paused")
                .isEmpty();
        assertThat(owner.service().pipelineState(region)).isEqualTo(PipelineState.IDLE);
    }

    @Test
    void aRefusedRegionIsNotActivatedAgain() {
        ValidationNode owner = harness.validationNode().build();

        RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
        RegionLease lease = new LeaseManager(200).issue(region, owner.nodeId(), List.of(), 0);

        owner.service().refuseRegion(region, RegionRefusal.Reason.NON_DELEGABLE_ENTITY);
        owner.service().activateRegion(base, lease);

        assertThat(owner.service().currentSnapshot(region))
                .as("re-activating a refused region would restart exactly the work the refusal "
                        + "exists to stop — the player crossing the boundary re-plans constantly")
                .isEmpty();
        assertThat(owner.service().isRefused(region)).isTrue();
    }

    @Test
    void refusingOneRegionLeavesItsNeighboursAlone() {
        ValidationNode owner = harness.validationNode().build();
        RegionId neighbour = new RegionId(DimensionKey.overworld(), 1, 0);

        NodeIdentity id = owner.identity();
        RegionLease lease = new LeaseManager(200).issue(region, id.nodeId(), List.of(), 0);
        RegionLease neighbourLease =
                new LeaseManager(200).issue(neighbour, id.nodeId(), List.of(), 0);
        owner.service().activateRegion(RegionFixtures.fullUniformSnapshot(region, 0), lease);
        owner.service().activateRegion(
                RegionFixtures.fullUniformSnapshot(neighbour, 0), neighbourLease);

        owner.service().refuseRegion(region, RegionRefusal.Reason.NON_DELEGABLE_ENTITY);

        assertThat(owner.service().currentSnapshot(region)).isEmpty();
        assertThat(owner.service().currentSnapshot(neighbour))
                .as("a mob in one region says nothing about the region next to it")
                .isPresent();
        assertThat(owner.service().isRefused(neighbour)).isFalse();
    }
}
