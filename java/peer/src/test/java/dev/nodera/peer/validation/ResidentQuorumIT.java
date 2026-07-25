package dev.nodera.peer.validation;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.PlayerView;
import dev.nodera.core.region.RegionClaim;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.region.ViewOwnershipPlanner;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #45's exit, headless: <b>2 workers + 1 player-node mesh — killing the player leaves
 * committees at full strength via the workers.</b>
 *
 * <p>Before the resident lane, a region's committee was exactly the set of <i>players</i> whose
 * field of view covered it, so a two-player world's committees shrank to one member the moment
 * someone walked away or logged out — permanently DEGRADED no matter how many always-on peers were
 * running on the network. Here the same disconnect happens with two standing workers on the mesh:
 * the re-plan hands the vacated validator seats to the residents, the committee stays at
 * {@link NoderaConstants#QUORUM_MVP_SIZE}, and the next action still commits with an independently
 * co-signed certificate — from a peer that has no Minecraft process at all.
 */
final class ResidentQuorumIT {

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
    void aDepartedPlayersSeatsGoToTheStandingWorkersInsteadOfShrinkingTheCommittee() {
        NodeId playerA = node("0a");
        NodeId playerB = node("0b");
        NodeId worker1 = node("f1");
        NodeId worker2 = node("f2");
        List<NodeId> residents = List.of(worker1, worker2);

        // Both players stand in region (0,0): the committee is capped at QUORUM_MVP_SIZE, so
        // geometry supplies the primary + one validator and a resident takes the last seat.
        Map<NodeId, PlayerView> both = new LinkedHashMap<>();
        both.put(playerA, view(4, 4));
        both.put(playerB, view(6, 6));
        RegionClaim withBoth = ViewOwnershipPlanner
                .plan(both, NoderaConstants.QUORUM_MVP_SIZE, residents).get(region);
        assertThat(committee(withBoth)).hasSize(NoderaConstants.QUORUM_MVP_SIZE);
        assertThat(committee(withBoth)).contains(playerA, playerB);

        // Player B logs out. The re-plan draws from the FULL mesh population, not the connected
        // players, so both freed seats are filled by the standing workers.
        Map<NodeId, PlayerView> aloneNow = Map.of(playerA, view(4, 4));
        RegionClaim afterLogout = ViewOwnershipPlanner
                .plan(aloneNow, NoderaConstants.QUORUM_MVP_SIZE, residents).get(region);
        assertThat(afterLogout.primary())
                .as("primacy is geometric — a resident never becomes primary")
                .isEqualTo(playerA);
        assertThat(committee(afterLogout))
                .as("the committee holds full strength through a player's disconnect")
                .hasSize(NoderaConstants.QUORUM_MVP_SIZE)
                .containsExactlyInAnyOrder(playerA, worker1, worker2);

        // The counterfactual: without residents on the mesh this is the observed DEGRADED shape.
        RegionClaim withoutResidents = ViewOwnershipPlanner
                .plan(aloneNow, NoderaConstants.QUORUM_MVP_SIZE).get(region);
        assertThat(committee(withoutResidents))
                .as("the pre-#45 behaviour the exit is measured against")
                .containsExactly(playerA);
    }

    @Test
    void aWorldKeepsCommittingAfterTheSecondPlayerLeavesBecauseWorkersHoldTheSeats() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity playerA = NodeIdentity.generate();
        NodeIdentity playerB = NodeIdentity.generate();
        NodeIdentity worker1 = NodeIdentity.generate();
        NodeIdentity worker2 = NodeIdentity.generate();
        NodeIdentity actor = NodeIdentity.generate();

        Worker a = worker(net, playerA);
        Worker b = worker(net, playerB);
        Worker w1 = worker(net, worker1);
        Worker w2 = worker(net, worker2);
        List<Worker> all = List.of(a, b, w1, w2);
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

        // Phase 1 — two players in the same region, one worker on the third seat.
        LeaseManager leases = new LeaseManager(200);
        RegionSnapshot base = fullUniformSnapshot(region, 0);
        RegionLease epoch0 = leases.issue(region, playerA.nodeId(),
                List.of(playerB.nodeId(), worker1.nodeId()), 0);
        for (Worker w : List.of(a, b, w1)) {
            w.service.activateRegion(base, epoch0);
        }
        a.service.proposeBatch(region, 1, 1, List.of(place(actor, 5, 70, 5, 1)));
        awaitVersionAbove(a, SnapshotVersion.INITIAL.value());
        long afterFirst = a.service.currentSnapshot(region).orElseThrow().version().value();
        assertThat(afterFirst).isGreaterThan(SnapshotVersion.INITIAL.value());

        // Phase 2 — player B leaves the world for good.
        b.service.revokeRegion(region);
        stop(b);

        // The re-plan seats BOTH workers; the region reopens at the next epoch on the survivors.
        RegionSnapshot head = a.service.currentSnapshot(region).orElseThrow();
        RegionLease epoch1 = leases.issue(region, playerA.nodeId(),
                List.of(worker1.nodeId(), worker2.nodeId()), 100);
        assertThat(1 + epoch1.validators().size()).isEqualTo(NoderaConstants.QUORUM_MVP_SIZE);
        for (Worker w : List.of(a, w1, w2)) {
            w.service.activateRegion(head, epoch1);
        }

        // Phase 3 — the world commits again with no second player anywhere on it.
        a.service.proposeBatch(region, head.tick() + 1, head.tick() + 1,
                List.of(place(actor, 6, 70, 6, 2)));
        awaitVersionAbove(a, afterFirst);

        assertThat(a.service.currentSnapshot(region).orElseThrow().version().value())
                .as("the surviving player's region still commits after the other player left")
                .isGreaterThan(afterFirst);
        var certificate = a.service.latestCertificate(region).orElseThrow();
        assertThat(certificate.votes().stream().map(v -> v.voter()).distinct())
                .as("the quorum is co-signed by peers that are not the proposer")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(certificate.votes().stream().map(v -> v.voter()))
                .as("a standing worker — no Minecraft process — carried the quorum")
                .containsAnyOf(worker1.nodeId(), worker2.nodeId());
    }

    // --- fixture -----------------------------------------------------------------------------

    private static List<NodeId> committee(RegionClaim claim) {
        List<NodeId> members = new ArrayList<>();
        members.add(claim.primary());
        members.addAll(claim.validators());
        return members;
    }

    private static NodeId node(String suffix) {
        return new NodeId(java.util.UUID.fromString("00000000-0000-0000-0000-0000000000" + suffix));
    }

    private static PlayerView view(int chunkX, int chunkZ) {
        return new PlayerView(DimensionKey.overworld(), chunkX, chunkZ, 4);
    }

    private ActionEnvelope place(NodeIdentity actor, int x, int y, int z, long seq) {
        long tick = seq;
        ActionEnvelope unsigned = new ActionEnvelope(actor.nodeId(), seq, seq, tick, region,
                new PlaceBlockAction(new NBlockPos(x, y, z), 1, 1), Bytes.empty());
        return new ActionEnvelope(actor.nodeId(), seq, seq, tick, region,
                new PlaceBlockAction(new NBlockPos(x, y, z), 1, 1),
                actor.sign(unsigned.signedPortion()));
    }

    private void awaitVersionAbove(Worker w, long version) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            var snap = w.service.currentSnapshot(region);
            if (snap.isPresent() && snap.get().version().value() > version) {
                return;
            }
            Thread.sleep(50);
        }
    }

    private record Worker(NodeIdentity id, WorkerValidationService service, PeerRuntime runtime) {
    }

    private void stop(Worker w) {
        w.runtime.stop();
        runtimes.remove(w.runtime);
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
        return new Worker(id, service, runtime);
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
