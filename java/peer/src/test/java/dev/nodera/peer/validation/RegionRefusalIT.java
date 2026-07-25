package dev.nodera.peer.validation;

import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.coordinator.LeaseManager;
import dev.nodera.coordinator.PipelineState;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.PeerRuntimeConfig;
import dev.nodera.protocol.simulationmsg.RegionRefusal;
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
    void anObserverThatOwnsNothingStillStopsTheRegionEverywhere() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity owner = NodeIdentity.generate();     // a player's node: holds the seat
        NodeIdentity validator = NodeIdentity.generate(); // a second seat on the committee
        NodeIdentity observer = NodeIdentity.generate();  // the session server: owns NOTHING

        Worker ownerNode = worker(net, owner);
        Worker validatorNode = worker(net, validator);
        Worker observerNode = worker(net, observer);
        mesh(List.of(ownerNode, validatorNode, observerNode));

        RegionSnapshot base = fullUniformSnapshot(region, 0);
        RegionLease lease = new LeaseManager(200).issue(
                region, owner.nodeId(), List.of(validator.nodeId()), 0);
        ownerNode.service.activateRegion(base, lease);
        validatorNode.service.activateRegion(base, lease);
        assertThat(ownerNode.service.pipelineState(region)).isNotEqualTo(PipelineState.REVOKED);

        // The observer holds no replica of the region at all — exactly the dedicated server's
        // position in every live topology ("no regions fall to this node").
        assertThat(observerNode.service.currentSnapshot(region)).isEmpty();

        boolean first = observerNode.service.refuseRegion(
                region, RegionRefusal.Reason.NON_DELEGABLE_ENTITY);
        assertThat(first).as("the first refusal of a region is the one that logs").isTrue();
        assertThat(observerNode.service.refuseRegion(
                region, RegionRefusal.Reason.NON_DELEGABLE_ENTITY))
                .as("a dimension full of mobs must not announce once per spawn")
                .isFalse();

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && !(ownerNode.service.isRefused(region) && validatorNode.service.isRefused(region))) {
            Thread.sleep(50);
        }

        assertThat(ownerNode.service.isRefused(region))
                .as("the owning player's node acted on a refusal it could not have observed itself")
                .isTrue();
        assertThat(validatorNode.service.isRefused(region)).isTrue();
        assertThat(ownerNode.service.currentSnapshot(region))
                .as("the replica is dropped, not merely paused")
                .isEmpty();
        assertThat(ownerNode.service.pipelineState(region)).isEqualTo(PipelineState.IDLE);
    }

    @Test
    void aRefusedRegionIsNotActivatedAgain() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity owner = NodeIdentity.generate();
        Worker ownerNode = worker(net, owner);

        RegionSnapshot base = fullUniformSnapshot(region, 0);
        RegionLease lease = new LeaseManager(200).issue(region, owner.nodeId(), List.of(), 0);

        ownerNode.service.refuseRegion(region, RegionRefusal.Reason.NON_DELEGABLE_ENTITY);
        ownerNode.service.activateRegion(base, lease);

        assertThat(ownerNode.service.currentSnapshot(region))
                .as("re-activating a refused region would restart exactly the work the refusal "
                        + "exists to stop — the player crossing the boundary re-plans constantly")
                .isEmpty();
        assertThat(ownerNode.service.isRefused(region)).isTrue();
    }

    @Test
    void refusingOneRegionLeavesItsNeighboursAlone() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity owner = NodeIdentity.generate();
        Worker ownerNode = worker(net, owner);
        RegionId neighbour = new RegionId(DimensionKey.overworld(), 1, 0);

        RegionLease lease = new LeaseManager(200).issue(region, owner.nodeId(), List.of(), 0);
        RegionLease neighbourLease =
                new LeaseManager(200).issue(neighbour, owner.nodeId(), List.of(), 0);
        ownerNode.service.activateRegion(fullUniformSnapshot(region, 0), lease);
        ownerNode.service.activateRegion(fullUniformSnapshot(neighbour, 0), neighbourLease);

        ownerNode.service.refuseRegion(region, RegionRefusal.Reason.NON_DELEGABLE_ENTITY);

        assertThat(ownerNode.service.currentSnapshot(region)).isEmpty();
        assertThat(ownerNode.service.currentSnapshot(neighbour))
                .as("a mob in one region says nothing about the region next to it")
                .isPresent();
        assertThat(ownerNode.service.isRefused(neighbour)).isFalse();
    }

    // --- fixture -----------------------------------------------------------------------------

    private record Worker(NodeIdentity id, WorkerValidationService service) {
    }

    private void mesh(List<Worker> workers) {
        for (Worker a : workers) {
            for (Worker b : workers) {
                if (!a.id().nodeId().equals(b.id().nodeId())) {
                    a.service().registerPeer(b.id().nodeId(),
                            PeerAddress.of(b.id().nodeId(), "loopback"), b.id().publicKeyBytes());
                }
            }
        }
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
