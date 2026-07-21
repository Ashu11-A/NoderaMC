package dev.nodera.peer.validation;

import dev.nodera.coordinator.LeaseManager;
import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.GameAction;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.fallback.CrossRegionRouter;
import dev.nodera.fallback.RoutingDecision;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.PeerRuntimeConfig;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The L-48 / L-30 exit scenario: three <b>companion-only worker nodes</b> (no Minecraft process
 * anywhere) form a committee over the {@code PeerTransport} and validate region batches
 * out-of-game — the primary proposes, the validators re-execute with THE engine and vote over the
 * wire, quorum commits, and every worker ends at the byte-identical root with the co-signed
 * certificate persisted in its own store. Then the primary is lost, a validator is promoted under
 * a bumped epoch, and the surviving 2-member committee keeps committing. Finally the fallback lane
 * routes an unassigned-region action through the server lane and the Phase-4 soak ratio holds.
 */
final class WorkerQuorumValidationIT {

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
    void companionWorkersValidateInQuorumOverTheTransport() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity idA = NodeIdentity.generate();
        NodeIdentity idB = NodeIdentity.generate();
        NodeIdentity idC = NodeIdentity.generate();

        Worker a = worker(net, idA);
        Worker b = worker(net, idB);
        Worker c = worker(net, idC);
        List<Worker> all = List.of(a, b, c);
        for (Worker w : all) {
            for (Worker other : all) {
                if (w != other) {
                    w.service.registerPeer(other.id.nodeId(),
                            PeerAddress.of(other.id.nodeId(), "loopback"));
                }
            }
        }

        // --- committee: A primary, B/C validators, all replicas at the same base snapshot ---
        RegionSnapshot base = fullUniformSnapshot(region, 0);
        LeaseManager leases = new LeaseManager(200);
        RegionLease lease = leases.issue(region, idA.nodeId(),
                List.of(idB.nodeId(), idC.nodeId()), 0);
        for (Worker w : all) {
            w.service.activateRegion(base, lease);
        }

        // --- batch 1: distributed quorum commit ---
        List<ActionEnvelope> batch1 = List.of(
                place(1, 0, 5, 70, 5, 1),
                place(2, 0, 40, 100, 40, 4));
        Optional<StateRoot> committed1 = a.service.proposeBatch(region, 0, 1, batch1);
        assertThat(committed1).isPresent();

        // the reference engine agrees (one engine, one root)
        StateRoot expected1 = referenceRoot(base, lease, SnapshotVersion.INITIAL, 0, 1, batch1);
        assertThat(committed1).contains(expected1);

        // every worker converged on the same head root and persisted the co-signed certificate
        awaitHeads(all, expected1);
        for (Worker w : all) {
            var cert = w.service.latestCertificate(region).orElseThrow();
            assertThat(cert.resultingRoot()).isEqualTo(expected1);
            var certW = new dev.nodera.core.crypto.CanonicalWriter();
            cert.encode(certW);
            assertThat(w.certificates.has(
                    dev.nodera.storage.ContentId.of(hashes, certW.toByteArray()))).isTrue();
        }

        // --- primary loss: promote a validator (epoch + 1) on the survivors ---
        RegionLease promotedB = b.service.failover(region, leases, 100);
        RegionLease promotedC = c.service.failover(region, new LeaseManager(200), 100);
        assertThat(promotedB).isNotNull();
        assertThat(promotedB.epoch().value()).isEqualTo(1);
        assertThat(promotedB.primary()).isNotEqualTo(idA.nodeId());
        assertThat(promotedC.primary()).isEqualTo(promotedB.primary());

        // --- batch 2: the surviving 2-member committee keeps play going ---
        Worker newPrimary = promotedB.primary().equals(b.id.nodeId()) ? b : c;
        Worker survivor = newPrimary == b ? c : b;
        RegionSnapshot afterBatch1 = snapshotOf(newPrimary, expected1);
        List<ActionEnvelope> batch2 = List.of(place(100, 3, 12, 60, 12, 2));
        Optional<StateRoot> committed2 = newPrimary.service.proposeBatch(region, 2, 3, batch2);
        assertThat(committed2).isPresent();
        StateRoot expected2 = referenceRoot(afterBatch1, promotedB,
                SnapshotVersion.INITIAL.next(), 2, 3, batch2);
        assertThat(committed2).contains(expected2);
        awaitHeads(List.of(newPrimary, survivor), expected2);

        // --- fallback lane: an unassigned-region action commits through the server lane ---
        RegionId elsewhere = new RegionId(DimensionKey.overworld(), 7, 7);
        RegionSnapshot elsewhereBase = fullUniformSnapshot(elsewhere, 0);
        NBlockPos inElsewhere = new NBlockPos(
                elsewhere.originChunkX() * 16 + 5, 70, elsewhere.originChunkZ() * 16 + 5);
        ActionEnvelope stray = new ActionEnvelope(new NodeId(new UUID(0L, 999L)), 999, 999, 5,
                elsewhere, new PlaceBlockAction(inElsewhere, 3, 1), Bytes.empty());
        RoutingDecision decision = newPrimary.service.routeAndMaybeFallback(
                stray, CrossRegionRouter.RegionStatus.UNASSIGNED, elsewhereBase);
        assertThat(decision.isFallback()).isTrue();
        assertThat(newPrimary.service.snapshot().fallbackCommits()).isEqualTo(1);

        // the committee lane dominates: the Phase-4 soak ratio holds on the router
        for (int i = 0; i < 50; i++) {
            newPrimary.service.routeAndMaybeFallback(place(2000 + i, 10, 6 + (i % 100), 70, 6, 1),
                    CrossRegionRouter.RegionStatus.DELEGATED_HEALTHY, elsewhereBase);
        }
        assertThat(newPrimary.service.soakMetrics().meetsPhase4ExitCriterion()).isTrue();

        // telemetry counters moved — the worker STATE JSON has real validation data to report
        WorkerValidationService.Snapshot telemetry = newPrimary.service.snapshot();
        assertThat(telemetry.committeeCommits()).isEqualTo(2);
        assertThat(telemetry.votesCast()).isGreaterThanOrEqualTo(2);
    }

    // --- worker assembly -----------------------------------------------------------------

    private record Worker(NodeIdentity id, WorkerValidationService service,
                          InMemoryCertificateStore certificates) {
    }

    private Worker worker(LoopbackNetwork net, NodeIdentity id) {
        LoopbackTransport tx = net.register(id.nodeId());
        PeerRuntime runtime = PeerRuntime.bootstrap(id, NodeCapabilities.initial(), tx,
                () -> "loopback",
                new PeerRuntimeConfig(Duration.ofMillis(100), Duration.ofMillis(500)), null);
        runtimes.add(runtime);
        InMemoryCertificateStore certs = new InMemoryCertificateStore(hashes);
        WorkerValidationService service = new WorkerValidationService(id, tx,
                engine(), hashes, certs, WORLD_SEED,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), 5_000L);
        runtime.onApplicationMessage(service::onMessage);
        return new Worker(id, service, certs);
    }

    private FlatWorldRegionEngine engine() {
        return new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);
    }

    // --- helpers -------------------------------------------------------------------------

    private void awaitHeads(List<Worker> workers, StateRoot expected) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            boolean all = workers.stream()
                    .allMatch(w -> w.service.headRoot(region).map(expected::equals).orElse(false));
            if (all) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Worker w : workers) {
            assertThat(w.service.headRoot(region)).contains(expected);
        }
    }

    private StateRoot referenceRoot(RegionSnapshot base, RegionLease lease, SnapshotVersion version,
                                    long tickFrom, long tickTo, List<ActionEnvelope> actions) {
        var batch = new dev.nodera.core.action.ActionBatch(region, lease.epoch(), version,
                tickFrom, tickTo, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(region, lease.epoch(), version,
                tickFrom, tickTo, WORLD_SEED, FlatWorldRules.RULES_VERSION,
                FlatWorldRules.registryFingerprint());
        return engine().execute(new RegionExecutionRequest(ctx, base, batch)).resultingRoot();
    }

    private RegionSnapshot snapshotOf(Worker w, StateRoot expectedHead) {
        assertThat(w.service.headRoot(region)).contains(expectedHead);
        // The replica advanced; rebuild the same snapshot deterministically from a fresh re-run so
        // the reference-engine comparison uses identical bytes. (The service itself holds the
        // advanced snapshot internally.)
        return w.service.currentSnapshot(region).orElseThrow();
    }

    private ActionEnvelope place(long seq, long tick, int x, int y, int z, int stateId) {
        GameAction a = new PlaceBlockAction(new NBlockPos(x, y, z), stateId, 1);
        return new ActionEnvelope(new NodeId(new UUID(0L, seq)), seq, seq, tick, region, a,
                Bytes.empty());
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
