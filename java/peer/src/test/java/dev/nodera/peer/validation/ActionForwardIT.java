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
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.coordinator.LeaseManager;
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
 * The no-host submission rule, proven over the transport: an action captured on a member that is
 * <b>not</b> the region's owner is forwarded ({@code ActionForward}, tag 53) to the region's
 * primary — another <i>player's</i> node — which proposes it to the committee; the forwarder
 * re-executes and votes like any validator, and both members converge on the byte-identical
 * committed root. The capture point is a courier; only the owner proposes; no member has host
 * authority.
 */
final class ActionForwardIT {

    private static final long WORLD_SEED = 0x4E4F4445_5241L;
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
    void capturedActionIsForwardedToTheOwningPlayerAndCommitsInQuorum() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity owner = NodeIdentity.generate();     // the player who OWNS the region
        NodeIdentity capturer = NodeIdentity.generate();  // the member that merely captured
        NodeIdentity actor = NodeIdentity.generate();     // the acting player identity

        Worker ownerNode = worker(net, owner);
        Worker capturerNode = worker(net, capturer);
        for (Worker w : List.of(ownerNode, capturerNode)) {
            w.service.registerActor(actor.nodeId(), actor.publicKeyBytes());
        }
        ownerNode.service.registerPeer(capturer.nodeId(),
                PeerAddress.of(capturer.nodeId(), "loopback"), capturer.publicKeyBytes());
        capturerNode.service.registerPeer(owner.nodeId(),
                PeerAddress.of(owner.nodeId(), "loopback"), owner.publicKeyBytes());

        // The OWNER is the primary; the capturer is the (only) validator — quorum 2-of-2.
        RegionSnapshot base = fullUniformSnapshot(region, 0);
        RegionLease lease = new LeaseManager(200).issue(
                region, owner.nodeId(), List.of(capturer.nodeId()), 0);
        ownerNode.service.activateRegion(base, lease);
        capturerNode.service.activateRegion(base, lease);

        // A signed action lands on the NON-owner: it must be forwarded, not proposed locally.
        ActionEnvelope unsigned = new ActionEnvelope(
                actor.nodeId(), 1, 1, 1, region,
                new PlaceBlockAction(new NBlockPos(5, 70, 5), 1, 1), Bytes.empty());
        ActionEnvelope signed = new ActionEnvelope(
                actor.nodeId(), 1, 1, 1, region,
                new PlaceBlockAction(new NBlockPos(5, 70, 5), 1, 1),
                actor.sign(unsigned.signedPortion()));
        assertThat(capturerNode.service.forwardToPrimary(signed))
                .as("the non-owner forwards instead of proposing").isTrue();
        assertThat(ownerNode.service.forwardToPrimary(signed))
                .as("the owner never forwards its own region").isFalse();

        // The owner proposes, the capturer votes, quorum commits — on both members.
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            var head = ownerNode.service.headRoot(region);
            var mirrored = capturerNode.service.headRoot(region);
            if (head.isPresent() && mirrored.isPresent() && head.equals(mirrored)
                    && ownerNode.service.currentSnapshot(region).orElseThrow().version()
                            .value() > SnapshotVersion.INITIAL.value()) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(ownerNode.service.currentSnapshot(region).orElseThrow().version().value())
                .as("the owner committed the forwarded action")
                .isGreaterThan(SnapshotVersion.INITIAL.value());
        assertThat(capturerNode.service.headRoot(region))
                .as("the capturer converged on the identical committed root")
                .isEqualTo(ownerNode.service.headRoot(region));
        assertThat(ownerNode.service.latestCertificate(region))
                .as("a co-signed quorum certificate exists").isPresent();
        assertThat(ownerNode.service.snapshot().committeeCommits()).isGreaterThan(0);
    }

    @Test
    void forwardedActionWithSkewedSignedTickStillCommits() throws Exception {
        // Clean-slate skew repro (issue #33 / L-50): the actor signed targetTick against the
        // CAPTURER's replica clock, which differs from the primary's next tick. The primary must
        // bracket its batch window around the signed tick instead of silently rejecting the
        // forward — the capturer may already have suppressed the vanilla outcome.
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity owner = NodeIdentity.generate();
        NodeIdentity capturer = NodeIdentity.generate();
        NodeIdentity actor = NodeIdentity.generate();

        Worker ownerNode = worker(net, owner);
        Worker capturerNode = worker(net, capturer);
        for (Worker w : List.of(ownerNode, capturerNode)) {
            w.service.registerActor(actor.nodeId(), actor.publicKeyBytes());
        }
        ownerNode.service.registerPeer(capturer.nodeId(),
                PeerAddress.of(capturer.nodeId(), "loopback"), capturer.publicKeyBytes());
        capturerNode.service.registerPeer(owner.nodeId(),
                PeerAddress.of(owner.nodeId(), "loopback"), owner.publicKeyBytes());

        RegionSnapshot base = fullUniformSnapshot(region, 0);
        RegionLease lease = new LeaseManager(200).issue(
                region, owner.nodeId(), List.of(capturer.nodeId()), 0);
        ownerNode.service.activateRegion(base, lease);
        capturerNode.service.activateRegion(base, lease);

        // Signed tick 7: the primary's own next tick is 1 (base snapshot tick 0).
        ActionEnvelope unsigned = new ActionEnvelope(
                actor.nodeId(), 1, 1, 7, region,
                new PlaceBlockAction(new NBlockPos(5, 70, 5), 1, 1), Bytes.empty());
        ActionEnvelope signed = new ActionEnvelope(
                actor.nodeId(), 1, 1, 7, region,
                new PlaceBlockAction(new NBlockPos(5, 70, 5), 1, 1),
                actor.sign(unsigned.signedPortion()));
        assertThat(capturerNode.service.forwardToPrimary(signed)).isTrue();

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (ownerNode.service.currentSnapshot(region).orElseThrow().version()
                    .value() > SnapshotVersion.INITIAL.value()) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(ownerNode.service.currentSnapshot(region).orElseThrow().version().value())
                .as("the skewed-tick forward must commit, not be silently dropped")
                .isGreaterThan(SnapshotVersion.INITIAL.value());
        assertThat(capturerNode.service.headRoot(region))
                .isEqualTo(ownerNode.service.headRoot(region));
    }

    // --- fixture -----------------------------------------------------------------------------

    private record Worker(NodeIdentity id, WorkerValidationService service) {
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
