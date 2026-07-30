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
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.LeaseManager;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.PeerRuntimeConfig;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionResult;
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
 * Issue #5 — the Phase-1 exit gate is "hours of multi-client play, zero unexplained divergences",
 * which is a NUMBER, and until now nothing produced it.
 *
 * <p>Two nodes re-executing the same batch on the same base and disagreeing is the one outcome the
 * whole architecture is a bet against. The validator handled it correctly — it declines to vote —
 * but did so with a bare {@code return}, so a live soak could run for hours over a divergence and
 * report nothing but a quieter-than-usual mesh. These tests pin that a disagreement is counted from
 * both chairs, and that agreement leaves the counter alone (a divergence counter that drifts upward
 * during healthy play would be worse than none).
 */
final class DivergenceCountedIT {

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
    void aValidatorThatComputesADifferentWorldIsCounted() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity primary = NodeIdentity.generate();
        NodeIdentity validator = NodeIdentity.generate();
        NodeIdentity actor = NodeIdentity.generate();

        Worker primaryNode = worker(net, primary, honest());
        // This validator's engine reports a corrupted root for the same inputs — a stand-in for the
        // thing the gate hunts: different hardware, different answer.
        Worker validatorNode = worker(net, validator, corrupting(honest()));
        for (Worker w : List.of(primaryNode, validatorNode)) {
            w.service.registerActor(actor.nodeId(), actor.publicKeyBytes());
        }
        primaryNode.service.registerPeer(validator.nodeId(),
                PeerAddress.of(validator.nodeId(), "loopback"), validator.publicKeyBytes());
        validatorNode.service.registerPeer(primary.nodeId(),
                PeerAddress.of(primary.nodeId(), "loopback"), primary.publicKeyBytes());

        RegionSnapshot base = fullUniformSnapshot(region, 0);
        RegionLease lease = new LeaseManager(200).issue(
                region, primary.nodeId(), List.of(validator.nodeId()), 0);
        primaryNode.service.activateRegion(base, lease);
        validatorNode.service.activateRegion(base, lease);

        assertThat(validatorNode.service.divergences()).isZero();
        primaryNode.service.proposeBatch(region, 1, 1, List.of(signedPlace(actor, 1)));

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && validatorNode.service.divergences() == 0) {
            Thread.sleep(50);
        }
        assertThat(validatorNode.service.divergences())
                .as("the disagreement is a measurement, not a silent no-vote")
                .isGreaterThan(0);
        assertThat(validatorNode.service.snapshot().divergences())
                .as("and it reaches the STATE reply a soak reads")
                .isEqualTo(validatorNode.service.divergences());
    }

    @Test
    void agreementNeverIncrementsTheCounter() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity primary = NodeIdentity.generate();
        NodeIdentity validator = NodeIdentity.generate();
        NodeIdentity actor = NodeIdentity.generate();

        Worker primaryNode = worker(net, primary, honest());
        Worker validatorNode = worker(net, validator, honest());
        for (Worker w : List.of(primaryNode, validatorNode)) {
            w.service.registerActor(actor.nodeId(), actor.publicKeyBytes());
        }
        primaryNode.service.registerPeer(validator.nodeId(),
                PeerAddress.of(validator.nodeId(), "loopback"), validator.publicKeyBytes());
        validatorNode.service.registerPeer(primary.nodeId(),
                PeerAddress.of(primary.nodeId(), "loopback"), primary.publicKeyBytes());

        RegionSnapshot base = fullUniformSnapshot(region, 0);
        RegionLease lease = new LeaseManager(200).issue(
                region, primary.nodeId(), List.of(validator.nodeId()), 0);
        primaryNode.service.activateRegion(base, lease);
        validatorNode.service.activateRegion(base, lease);

        for (int i = 1; i <= 5; i++) {
            primaryNode.service.proposeBatch(region, i, i, List.of(signedPlace(actor, i)));
        }

        assertThat(primaryNode.service.snapshot().committeeCommits())
                .as("the batches really did go through the committee")
                .isGreaterThan(0);
        assertThat(primaryNode.service.divergences()).isZero();
        assertThat(validatorNode.service.divergences())
                .as("healthy play must leave the gate's number at zero")
                .isZero();
    }

    // --- fixture -----------------------------------------------------------------------------

    private ActionEnvelope signedPlace(NodeIdentity actor, long tick) {
        PlaceBlockAction place = new PlaceBlockAction(new NBlockPos(5, 70, 5), 1, 1);
        ActionEnvelope unsigned = new ActionEnvelope(
                actor.nodeId(), tick, tick, tick, region, place, Bytes.empty());
        return new ActionEnvelope(actor.nodeId(), tick, tick, tick, region, place,
                actor.sign(unsigned.signedPortion()));
    }

    private RegionEngine honest() {
        return new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);
    }

    private static RegionEngine corrupting(RegionEngine real) {
        return request -> {
            RegionExecutionResult r = real.execute(request);
            byte[] bytes = r.resultingRoot().hash().toArray();
            bytes[0] ^= (byte) 0xFF; // the delta stays honest; only the reported root diverges
            return new RegionExecutionResult(
                    r.delta(), StateRoot.of(Bytes.unsafeWrap(bytes)), r.stats());
        };
    }

    private record Worker(NodeIdentity id, WorkerValidationService service) {
    }

    private Worker worker(LoopbackNetwork net, NodeIdentity id, RegionEngine engine) {
        LoopbackTransport tx = net.register(id.nodeId());
        PeerRuntime runtime = PeerRuntime.bootstrap(id, NodeCapabilities.initial(), tx,
                () -> "loopback",
                new PeerRuntimeConfig(Duration.ofMillis(100), Duration.ofMillis(500)), null);
        runtimes.add(runtime);
        WorkerValidationService service = new WorkerValidationService(id, tx, engine,
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
