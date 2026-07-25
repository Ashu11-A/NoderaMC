package dev.nodera.peer.validation;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.coordinator.LagHandoffPolicy;
import dev.nodera.coordinator.LeaseManager;
import dev.nodera.coordinator.ReliabilityLedger;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.PeerRuntimeConfig;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.storage.event.InMemoryCertificateStore;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.LoopbackTransport.LoopbackNetwork;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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

    private static final long WORLD_SEED = 0x4E4F4445_5241L;
    private static final long BADLY_BEHIND = 9L * LagHandoffPolicy.TICK_BASIS_POINTS;
    private static final int MIN_Y = -64;
    private static final int SECTION_COUNT = 24;

    private final HashService hashes = new HashService();
    private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);
    private final List<PeerRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (PeerRuntime rt : runtimes) {
            rt.stop();
        }
    }

    @Test
    void sustainedSkewMovesPrimacyOffTheLaggingPlayerAndTheRegionKeepsCommitting() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity laggard = NodeIdentity.generate();  // the player that cannot keep up
        NodeIdentity healthy = NodeIdentity.generate();  // the deterministic successor
        NodeIdentity witness = NodeIdentity.generate();  // a standing worker on the committee
        NodeIdentity actor = NodeIdentity.generate();

        Worker slow = worker(net, laggard);
        Worker fast = worker(net, healthy);
        Worker third = worker(net, witness);
        List<Worker> all = List.of(slow, fast, third);
        for (Worker w : all) {
            w.service.registerActor(actor.nodeId(), actor.publicKeyBytes());
            for (Worker other : all) {
                if (other != w) {
                    w.service.registerPeer(other.id.nodeId(),
                            PeerAddress.of(other.id.nodeId(), "loopback"),
                            other.id.publicKeyBytes());
                }
            }
        }

        RegionLease epoch0 = new RegionLease(region, dev.nodera.core.region.RegionEpoch.INITIAL,
                laggard.nodeId(), List.of(healthy.nodeId(), witness.nodeId()), 0, 400);
        RegionSnapshot base = fullUniformSnapshot(region, 0);
        for (Worker w : all) {
            w.service.activateRegion(base, epoch0);
        }

        // `RegionLease` canonically sorts its validators, so the successor every member agrees on
        // is whichever NodeId sorts first — the same choice `CommitteeFailover` would make.
        Worker successor = byNode(all, epoch0.validators().get(0));
        Worker bystander = byNode(all, epoch0.validators().get(1));

        // The laggard is still primary while its skew is inside the envelope.
        LeaseManager leases = new LeaseManager(400);
        ReliabilityLedger reliability = new ReliabilityLedger();
        assertThat(successor.service.observeSkew(region, LagHandoffPolicy.TICK_BASIS_POINTS,
                leases, reliability, 10))
                .as("a single tick of skew is normal play, not a handoff").isNull();

        // Only the deterministic successor may initiate — the other validator stays silent even
        // when it sees exactly the same skew, so one slow window cannot split the committee.
        for (int window = 0; window < LagHandoffPolicy.DEFAULT_CONSECUTIVE_UNHEALTHY_WINDOWS; window++) {
            assertThat(bystander.service.observeSkew(region, BADLY_BEHIND,
                    new LeaseManager(400), new ReliabilityLedger(), 20 + window))
                    .as("a non-successor validator never initiates a handoff").isNull();
        }

        RegionLease promoted = null;
        for (int window = 0; window < LagHandoffPolicy.DEFAULT_CONSECUTIVE_UNHEALTHY_WINDOWS; window++) {
            promoted = successor.service.observeSkew(
                    region, BADLY_BEHIND, leases, reliability, 30 + window);
        }
        assertThat(promoted).as("sustained skew hands the region off").isNotNull();
        assertThat(promoted.primary())
                .as("primacy moves to the member that can keep up")
                .isEqualTo(successor.id.nodeId());
        assertThat(promoted.epoch().value())
                .as("the handoff bumps the epoch so the laggard's in-flight work is stale")
                .isGreaterThan(epoch0.epoch().value());
        assertThat(reliability.score(laggard.nodeId()))
                .as("the lagging primary pays the reliability penalty exactly once")
                .isLessThan(reliability.score(successor.id.nodeId()));

        // The other members re-seat from the broadcast assignment WITHOUT rewinding their state.
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && bystander.service.lease(region).orElseThrow().epoch().value()
                        <= epoch0.epoch().value()) {
            Thread.sleep(25);
        }
        assertThat(bystander.service.lease(region).orElseThrow().primary())
                .as("the other validator re-seated under the new primary")
                .isEqualTo(successor.id.nodeId());
        assertThat(bystander.service.currentSnapshot(region).orElseThrow().version())
                .as("re-seating a live replica must not rewind it to genesis")
                .isEqualTo(base.version());

        // The region is not wedged: the new primary commits with the survivors' quorum.
        successor.service.proposeBatch(region, 1, 1, List.of(place(actor, 5, 70, 5, 1)));
        long committedDeadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < committedDeadline) {
            var snap = successor.service.currentSnapshot(region);
            if (snap.isPresent() && snap.get().version().value() > SnapshotVersion.INITIAL.value()) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(successor.service.currentSnapshot(region).orElseThrow().version().value())
                .as("play continues through the laggard's region after the handoff")
                .isGreaterThan(SnapshotVersion.INITIAL.value());
        assertThat(successor.service.latestCertificate(region)).isPresent();
    }

    @Test
    void theCooldownStopsAHandoffFromFlappingBetweenMembers() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity laggard = NodeIdentity.generate();
        NodeIdentity healthy = NodeIdentity.generate();
        NodeIdentity witness = NodeIdentity.generate();

        Worker slow = worker(net, laggard);
        Worker fast = worker(net, healthy);
        Worker third = worker(net, witness);
        for (Worker w : List.of(slow, fast, third)) {
            for (Worker other : List.of(slow, fast, third)) {
                if (other != w) {
                    w.service.registerPeer(other.id.nodeId(),
                            PeerAddress.of(other.id.nodeId(), "loopback"),
                            other.id.publicKeyBytes());
                }
            }
        }
        RegionLease epoch0 = new RegionLease(region, dev.nodera.core.region.RegionEpoch.INITIAL,
                laggard.nodeId(), List.of(healthy.nodeId(), witness.nodeId()), 0, 400);
        Worker successor = byNode(List.of(slow, fast, third), epoch0.validators().get(0));
        successor.service.activateRegion(fullUniformSnapshot(region, 0), epoch0);

        LeaseManager leases = new LeaseManager(400);
        ReliabilityLedger reliability = new ReliabilityLedger();
        RegionLease first = null;
        for (int window = 0; window < LagHandoffPolicy.DEFAULT_CONSECUTIVE_UNHEALTHY_WINDOWS; window++) {
            first = successor.service.observeSkew(region, BADLY_BEHIND, leases, reliability, window);
        }
        assertThat(first).isNotNull();

        // This node is now the primary: it can never hand its own region off to itself, and the
        // policy's cooldown is holding besides.
        for (int window = 0; window < 10; window++) {
            assertThat(successor.service.observeSkew(
                    region, BADLY_BEHIND, leases, reliability, 10 + window))
                    .as("no second handoff inside the cooldown").isNull();
        }
        assertThat(successor.service.lease(region).orElseThrow().epoch())
                .isEqualTo(first.epoch());
    }

    @Test
    void theLagSignalIsTheAgeOfTheOldestForwardedActionThePrimaryHasNotAnswered() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity primary = NodeIdentity.generate();
        NodeIdentity self = NodeIdentity.generate();
        NodeIdentity actor = NodeIdentity.generate();

        Worker remotePrimary = worker(net, primary);
        Worker local = worker(net, self);
        for (Worker w : List.of(remotePrimary, local)) {
            w.service.registerActor(actor.nodeId(), actor.publicKeyBytes());
        }
        local.service.registerPeer(primary.nodeId(),
                PeerAddress.of(primary.nodeId(), "loopback"), primary.publicKeyBytes());

        RegionLease lease = new RegionLease(region, dev.nodera.core.region.RegionEpoch.INITIAL,
                primary.nodeId(), List.of(self.nodeId()), 0, 400);
        local.service.activateRegion(fullUniformSnapshot(region, 0), lease);

        // Nothing forwarded yet: a quiet region is not a lagging one. This is the distinction the
        // naive "committed tick vs server tick" signal gets wrong — an idle region trails forever.
        long now = System.nanoTime();
        assertThat(local.service.forwardLagTickBps(region, now)).isZero();

        assertThat(local.service.forwardToPrimary(place(actor, 5, 70, 5, 1))).isTrue();
        long forwardedAt = System.nanoTime();

        // One second later, with no commit back, the primary is twenty ticks behind on our work.
        long oneSecondOn = forwardedAt + 1_000_000_000L;
        assertThat(local.service.forwardLagTickBps(region, oneSecondOn))
                .isGreaterThanOrEqualTo(19L * LagHandoffPolicy.TICK_BASIS_POINTS);
    }

    @Test
    void theLagWindowOnlyOpensOnceEveryHundredTicksSoTheTickPathStaysCheap() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        Worker local = worker(net, NodeIdentity.generate());

        // First call opens a window; the next 99 ticks must not. (Three windows are required for a
        // handoff, so a wedged region hands off after ~15 s rather than on one slow tick.)
        assertThat(local.service.tickLagHandoff(0, System.nanoTime())).isEmpty();
        for (long tick = 1; tick < 100; tick++) {
            assertThat(local.service.tickLagHandoff(tick, System.nanoTime())).isEmpty();
        }
        assertThat(local.service.tickLagHandoff(100, System.nanoTime())).isEmpty();
    }

    // --- fixture -----------------------------------------------------------------------------

    private ActionEnvelope place(NodeIdentity actor, int x, int y, int z, long seq) {
        ActionEnvelope unsigned = new ActionEnvelope(actor.nodeId(), seq, seq, seq, region,
                new PlaceBlockAction(new NBlockPos(x, y, z), 1, 1), Bytes.empty());
        return new ActionEnvelope(actor.nodeId(), seq, seq, seq, region,
                new PlaceBlockAction(new NBlockPos(x, y, z), 1, 1),
                actor.sign(unsigned.signedPortion()));
    }

    private record Worker(NodeIdentity id, WorkerValidationService service) {
    }

    private static Worker byNode(List<Worker> workers, dev.nodera.core.identity.NodeId id) {
        return workers.stream().filter(w -> w.id.nodeId().equals(id)).findFirst().orElseThrow();
    }

    private Worker worker(LoopbackNetwork net, NodeIdentity id) {
        LoopbackTransport tx = net.register(id.nodeId());
        PeerRuntime runtime = PeerRuntime.bootstrap(id, NodeCapabilities.initial(), tx,
                () -> "loopback",
                new PeerRuntimeConfig(Duration.ofMillis(100), Duration.ofMillis(500)), null);
        runtimes.add(runtime);
        WorkerValidationService service = new WorkerValidationService(id, tx,
                new FlatWorldRegionEngine(
                        FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes),
                hashes, new InMemoryCertificateStore(hashes), WORLD_SEED,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), 5_000L);
        runtime.onApplicationMessage(service::onMessage);
        return new Worker(id, service);
    }

    private ChunkColumnState uniformColumn(int chunkX, int chunkZ, int stateId) {
        int[] palette = new int[SECTION_COUNT];
        Arrays.fill(palette, stateId);
        return new ChunkColumnState(chunkX, chunkZ, palette, MIN_Y, SECTION_COUNT);
    }

    private RegionSnapshot fullUniformSnapshot(RegionId r, int stateId) {
        int ox = r.originChunkX();
        int oz = r.originChunkZ();
        List<ChunkColumnState> cols = new ArrayList<>(64);
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                cols.add(uniformColumn(ox + dx, oz + dz, stateId));
            }
        }
        return new RegionSnapshot(r, SnapshotVersion.INITIAL, 0L, cols);
    }
}
