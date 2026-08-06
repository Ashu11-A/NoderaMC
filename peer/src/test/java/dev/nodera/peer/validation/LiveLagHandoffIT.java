package dev.nodera.peer.validation;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.coordinator.LagHandoffPolicy;
import dev.nodera.coordinator.LeaseManager;
import dev.nodera.coordinator.ReliabilityLedger;
import dev.nodera.testkit.peer.Await;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.testkit.peer.RegionFixtures;
import dev.nodera.testkit.peer.ValidationNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #46.1 — a laggy player must not wedge its regions for everyone else.
 *
 * <p>The Task 25 lane ({@code TickSkewMeter} → {@link LagHandoffPolicy} →
 * {@code CommitteeFailover.promoteOnLag}) existed and was tested, but nothing on the live
 * validation lane ever called it: a player whose client fell thousands of ticks behind stayed the
 * primary of every region its field of view covered, and every cross-border action into those
 * regions waited on it indefinitely. {@link WorkerValidationService#observeSkew} is the missing
 * call site — sustained skew moves primacy to a member that can do the work, at {@code epoch + 1},
 * and the region keeps committing.
 */
final class LiveLagHandoffIT {

    private static final long BADLY_BEHIND = 9L * LagHandoffPolicy.TICK_BASIS_POINTS;

    private final PeerTestHarness harness = PeerTestHarness.create();
    private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void sustainedSkewMovesPrimacyOffTheLaggingPlayerAndTheRegionKeepsCommitting() {
        NodeIdentity actor = NodeIdentity.generate();
        ValidationNode slow = harness.validationNode().build();   // the player that cannot keep up
        ValidationNode fast = harness.validationNode().build();   // the deterministic successor
        ValidationNode third = harness.validationNode().build();  // a standing worker on the committee
        List<ValidationNode> all = List.of(slow, fast, third);
        ValidationNode.mesh(all, actor);

        RegionLease epoch0 = new RegionLease(region, RegionEpoch.INITIAL,
                slow.nodeId(), List.of(fast.nodeId(), third.nodeId()), 0, 400);
        RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
        for (ValidationNode w : all) {
            w.service().activateRegion(base, epoch0);
        }

        // `RegionLease` canonically sorts its validators, so the successor every member agrees on
        // is whichever NodeId sorts first — the same choice `CommitteeFailover` would make.
        ValidationNode successor = byNode(all, epoch0.validators().get(0));
        ValidationNode bystander = byNode(all, epoch0.validators().get(1));

        // The laggard is still primary while its skew is inside the envelope.
        LeaseManager leases = new LeaseManager(400);
        ReliabilityLedger reliability = new ReliabilityLedger();
        assertThat(successor.service().observeSkew(region, LagHandoffPolicy.TICK_BASIS_POINTS,
                leases, reliability, 10))
                .as("a single tick of skew is normal play, not a handoff").isNull();

        // Only the deterministic successor may initiate — the other validator stays silent even
        // when it sees exactly the same skew, so one slow window cannot split the committee.
        for (int window = 0; window < LagHandoffPolicy.DEFAULT_CONSECUTIVE_UNHEALTHY_WINDOWS;
                window++) {
            assertThat(bystander.service().observeSkew(region, BADLY_BEHIND,
                    new LeaseManager(400), new ReliabilityLedger(), 20 + window))
                    .as("a non-successor validator never initiates a handoff").isNull();
        }

        RegionLease promoted = null;
        for (int window = 0; window < LagHandoffPolicy.DEFAULT_CONSECUTIVE_UNHEALTHY_WINDOWS;
                window++) {
            promoted = successor.service().observeSkew(
                    region, BADLY_BEHIND, leases, reliability, 30 + window);
        }
        assertThat(promoted).as("sustained skew hands the region off").isNotNull();
        assertThat(promoted.primary())
                .as("primacy moves to the member that can keep up")
                .isEqualTo(successor.nodeId());
        assertThat(promoted.epoch().value())
                .as("the handoff bumps the epoch so the laggard's in-flight work is stale")
                .isGreaterThan(epoch0.epoch().value());
        assertThat(reliability.score(slow.nodeId()))
                .as("the lagging primary pays the reliability penalty exactly once")
                .isLessThan(reliability.score(successor.nodeId()));

        // The other members re-seat from the broadcast assignment WITHOUT rewinding their state.
        Await.quietly(5_000, () -> bystander.service().lease(region).orElseThrow().epoch().value()
                > epoch0.epoch().value());
        assertThat(bystander.service().lease(region).orElseThrow().primary())
                .as("the other validator re-seated under the new primary")
                .isEqualTo(successor.nodeId());
        assertThat(bystander.service().currentSnapshot(region).orElseThrow().version())
                .as("re-seating a live replica must not rewind it to genesis")
                .isEqualTo(base.version());

        // The region is not wedged: the new primary commits with the survivors' quorum.
        successor.service().proposeBatch(region, 1, 1,
                List.of(RegionFixtures.place(actor, region, 1, 1, 5, 70, 5, 1)));
        Await.quietly(15_000, () -> successor.service().currentSnapshot(region)
                .map(s -> s.version().value() > SnapshotVersion.INITIAL.value()).orElse(false));
        assertThat(successor.service().currentSnapshot(region).orElseThrow().version().value())
                .as("play continues through the laggard's region after the handoff")
                .isGreaterThan(SnapshotVersion.INITIAL.value());
        assertThat(successor.service().latestCertificate(region)).isPresent();
    }

    @Test
    void theCooldownStopsAHandoffFromFlappingBetweenMembers() {
        ValidationNode slow = harness.validationNode().build();
        ValidationNode fast = harness.validationNode().build();
        ValidationNode third = harness.validationNode().build();
        List<ValidationNode> all = List.of(slow, fast, third);
        ValidationNode.mesh(all);

        RegionLease epoch0 = new RegionLease(region, RegionEpoch.INITIAL,
                slow.nodeId(), List.of(fast.nodeId(), third.nodeId()), 0, 400);
        ValidationNode successor = byNode(all, epoch0.validators().get(0));
        successor.service().activateRegion(RegionFixtures.fullUniformSnapshot(region, 0), epoch0);

        LeaseManager leases = new LeaseManager(400);
        ReliabilityLedger reliability = new ReliabilityLedger();
        RegionLease first = null;
        for (int window = 0; window < LagHandoffPolicy.DEFAULT_CONSECUTIVE_UNHEALTHY_WINDOWS;
                window++) {
            first = successor.service().observeSkew(region, BADLY_BEHIND, leases, reliability,
                    window);
        }
        assertThat(first).isNotNull();

        // This node is now the primary: it can never hand its own region off to itself, and the
        // policy's cooldown is holding besides.
        for (int window = 0; window < 10; window++) {
            assertThat(successor.service().observeSkew(
                    region, BADLY_BEHIND, leases, reliability, 10 + window))
                    .as("no second handoff inside the cooldown").isNull();
        }
        assertThat(successor.service().lease(region).orElseThrow().epoch())
                .isEqualTo(first.epoch());
    }

    @Test
    void theLagSignalIsTheAgeOfTheOldestForwardedActionThePrimaryHasNotAnswered() {
        NodeIdentity actor = NodeIdentity.generate();
        ValidationNode remotePrimary = harness.validationNode().build();
        ValidationNode local = harness.validationNode().build();
        remotePrimary.registerActor(actor);
        local.registerActor(actor);
        local.introduce(remotePrimary);

        RegionLease lease = new RegionLease(region, RegionEpoch.INITIAL,
                remotePrimary.nodeId(), List.of(local.nodeId()), 0, 400);
        local.service().activateRegion(RegionFixtures.fullUniformSnapshot(region, 0), lease);

        // Nothing forwarded yet: a quiet region is not a lagging one. This is the distinction the
        // naive "committed tick vs server tick" signal gets wrong — an idle region trails forever.
        long now = System.nanoTime();
        assertThat(local.service().forwardLagTickBps(region, now)).isZero();

        assertThat(local.service().forwardToPrimary(place(actor))).isTrue();
        long forwardedAt = System.nanoTime();

        // One second later, with no commit back, the primary is twenty ticks behind on our work.
        long oneSecondOn = forwardedAt + 1_000_000_000L;
        assertThat(local.service().forwardLagTickBps(region, oneSecondOn))
                .isGreaterThanOrEqualTo(19L * LagHandoffPolicy.TICK_BASIS_POINTS);
    }

    @Test
    void theLagWindowOnlyOpensOnceEveryHundredTicksSoTheTickPathStaysCheap() {
        ValidationNode local = harness.validationNode().build();

        // First call opens a window; the next 99 ticks must not. (Three windows are required for a
        // handoff, so a wedged region hands off after ~15 s rather than on one slow tick.)
        assertThat(local.service().tickLagHandoff(0, System.nanoTime())).isEmpty();
        for (long tick = 1; tick < 100; tick++) {
            assertThat(local.service().tickLagHandoff(tick, System.nanoTime())).isEmpty();
        }
        assertThat(local.service().tickLagHandoff(100, System.nanoTime())).isEmpty();
    }

    // --- fixture -----------------------------------------------------------------------------

    private ActionEnvelope place(NodeIdentity actor) {
        return RegionFixtures.place(actor, region, 1, 1, 5, 70, 5, 1);
    }

    private static ValidationNode byNode(List<ValidationNode> nodes, NodeId id) {
        return nodes.stream().filter(n -> n.nodeId().equals(id)).findFirst().orElseThrow();
    }
}
