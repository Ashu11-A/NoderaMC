package dev.nodera.peer.validation;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.LeaseManager;
import dev.nodera.testkit.peer.Await;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.testkit.peer.RegionFixtures;
import dev.nodera.testkit.peer.ValidationNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The no-host submission rule, proven over the transport: an action captured on a member that is
 * <b>not</b> the region's owner is forwarded ({@code ActionForward}, tag 53) to the region's
 * primary — another <i>player's</i> node — which proposes it to the committee; the forwarder
 * re-executes and votes like any validator, and both members converge on the byte-identical
 * committed root. The capture point is a courier; only the owner proposes; no member has host
 * authority.
 */
final class ActionForwardIT {

    private final PeerTestHarness harness = PeerTestHarness.create();
    private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void capturedActionIsForwardedToTheOwningPlayerAndCommitsInQuorum() {
        NodeIdentity actor = NodeIdentity.generate();     // the acting player identity
        ValidationNode owner = harness.validationNode().build();     // the player who OWNS the region
        ValidationNode capturer = harness.validationNode().build();  // the member that merely captured
        ValidationNode.mesh(List.of(owner, capturer), actor);

        // The OWNER is the primary; the capturer is the (only) validator — quorum 2-of-2.
        RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
        RegionLease lease = new LeaseManager(200).issue(
                region, owner.nodeId(), List.of(capturer.nodeId()), 0);
        owner.service().activateRegion(base, lease);
        capturer.service().activateRegion(base, lease);

        // A signed action lands on the NON-owner: it must be forwarded, not proposed locally.
        ActionEnvelope signed = RegionFixtures.place(actor, region, 1, 1, 5, 70, 5, 1);
        assertThat(capturer.service().forwardToPrimary(signed))
                .as("the non-owner forwards instead of proposing").isTrue();
        assertThat(owner.service().forwardToPrimary(signed))
                .as("the owner never forwards its own region").isFalse();

        // L-16: the commit observer (the client's LocalReplicaView feed) sees every commit.
        AtomicReference<StateRoot> observed = new AtomicReference<>();
        owner.service().onCommit((snapshot, root) -> observed.set(root));

        // The owner proposes, the capturer votes, quorum commits — on both members.
        awaitConvergedCommit(owner, capturer, 10_000);
        assertThat(owner.service().currentSnapshot(region).orElseThrow().version().value())
                .as("the owner committed the forwarded action")
                .isGreaterThan(SnapshotVersion.INITIAL.value());
        assertThat(capturer.headRoot(region))
                .as("the capturer converged on the identical committed root")
                .isEqualTo(owner.headRoot(region));
        assertThat(owner.service().latestCertificate(region))
                .as("a co-signed quorum certificate exists").isPresent();
        assertThat(owner.service().snapshot().committeeCommits()).isGreaterThan(0);
        assertThat(observed.get())
                .as("the L-16 commit observer received the committed root")
                .isEqualTo(owner.headRoot(region).orElseThrow());
    }

    @Test
    void forwardedActionWithSkewedSignedTickStillCommits() {
        // Clean-slate skew repro (issue #33 / L-50): the actor signed targetTick against the
        // CAPTURER's replica clock, which differs from the primary's next tick. The primary must
        // bracket its batch window around the signed tick instead of silently rejecting the
        // forward — the capturer may already have suppressed the vanilla outcome.
        NodeIdentity actor = NodeIdentity.generate();
        ValidationNode owner = harness.validationNode().build();
        ValidationNode capturer = harness.validationNode().build();
        ValidationNode.mesh(List.of(owner, capturer), actor);

        RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
        RegionLease lease = new LeaseManager(200).issue(
                region, owner.nodeId(), List.of(capturer.nodeId()), 0);
        owner.service().activateRegion(base, lease);
        capturer.service().activateRegion(base, lease);

        // Signed tick 7: the primary's own next tick is 1 (base snapshot tick 0).
        ActionEnvelope signed = RegionFixtures.place(actor, region, 1, 7, 5, 70, 5, 1);
        assertThat(capturer.service().forwardToPrimary(signed)).isTrue();

        // Wait for BOTH members to converge (the capturer applies the commit asynchronously —
        // waiting on the owner alone races the capturer's vote/apply on slow runners).
        awaitConvergedCommit(owner, capturer, 15_000);
        assertThat(owner.service().currentSnapshot(region).orElseThrow().version().value())
                .as("the skewed-tick forward must commit, not be silently dropped")
                .isGreaterThan(SnapshotVersion.INITIAL.value());
        assertThat(capturer.headRoot(region)).isEqualTo(owner.headRoot(region));
    }

    /** Both members hold the same head, and it is past genesis. */
    private void awaitConvergedCommit(ValidationNode owner, ValidationNode capturer, long millis) {
        Await.quietly(millis, () -> {
            var head = owner.headRoot(region);
            var mirrored = capturer.headRoot(region);
            return head.isPresent() && head.equals(mirrored)
                    && owner.service().currentSnapshot(region).orElseThrow().version().value()
                            > SnapshotVersion.INITIAL.value();
        });
    }
}
