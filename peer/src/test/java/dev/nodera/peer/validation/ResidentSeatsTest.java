package dev.nodera.peer.validation;

import dev.nodera.coordinator.LeaseManager;
import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.PlayerView;
import dev.nodera.core.region.RegionClaim;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.region.RegionReplicaRole;
import dev.nodera.core.region.ViewOwnershipPlanner;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.protocol.assignment.RegionAssigned;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.storage.event.InMemoryCertificateStore;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.peer.Await;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.testkit.peer.RegionFixtures;
import dev.nodera.testkit.peer.ValidationNode;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resident seats: a world whose players have all left is still a world, because the workers that
 * hold seats on it keep committing.
 *
 * <p>Three sibling classes over one subject — the seat itself, the plan every peer has to agree on
 * before a seat means anything, and the quorum the seats add up to. They were three files over the
 * same worker mesh, and the three questions are one question: a seat nobody agrees on is not a
 * seat, and seats that do not reach quorum are not a committee.
 *
 * <p>Each nest keeps the class Javadoc naming what it was written from, and JUnit reports every
 * {@code @Nested @Test} individually, so the count this file contributes is unchanged.
 */
final class ResidentSeatsTest {

    /**
     * The headless half of "a world is validated by the peers on the network, not by whoever is logged
     * in": an always-on worker takes the committee seat a hosting world hands it over the P2P
     * transport, and starts re-executing that region out of game.
     *
     * <p>The seat arrives as a {@link RegionAssigned} because a headless peer has no Minecraft
     * connection to receive the client lane-plan payload over. Everything else is reconstructed
     * locally: the lease from the message, and the base snapshot from
     * {@link EntityLaneBootstrap#initialSnapshot} — byte-identical to the primary's, which is what
     * lets the first proposal's {@code prevRoot} line up with no state transfer.
     */
    @Nested
    final class ResidentCommitteeSeatTest {
        private final HashService hashes = new HashService();
        private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);
        private final NodeIdentity self = NodeIdentity.generate();
        private final NodeId primary = NodeIdentity.generate().nodeId();

        private WorkerValidationService service() {
            LoopbackTransport tx = LoopbackTransport.LoopbackNetwork.newNetwork().register(self.nodeId());
            return new WorkerValidationService(self, tx,
                    new FlatWorldRegionEngine(FlatWorldRules.RULES_VERSION,
                            FlatWorldRules.registryFingerprint(), hashes),
                    hashes, new InMemoryCertificateStore(hashes), 1L,
                    FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), 100L);
        }

        private RegionAssigned seat(RegionReplicaRole role, List<NodeId> committee, long epoch) {
            return new RegionAssigned(region, new RegionEpoch(epoch), role, SnapshotVersion.INITIAL,
                    1_000L, committee);
        }

        private static PeerAddress from(NodeId who) {
            return PeerAddress.of(who, "loopback");
        }

        @Test
        void aValidatorSeatActivatesTheRegionOutOfGame() {
            WorkerValidationService service = service();
            assertThat(service.lease(region)).isEmpty();

            service.onMessage(from(primary),
                    seat(RegionReplicaRole.VALIDATOR, List.of(primary, self.nodeId()), 1));

            assertThat(service.lease(region)).isPresent();
            RegionLease lease = service.lease(region).orElseThrow();
            assertThat(lease.primary()).isEqualTo(primary);
            assertThat(lease.validators()).contains(self.nodeId());
            assertThat(lease.expiresAtTick()).isEqualTo(1_000L);
            // The base snapshot must be the deterministic one the primary also starts from.
            RegionSnapshot expected = EntityLaneBootstrap.initialSnapshot(region);
            assertThat(service.currentSnapshot(region).orElseThrow().version())
                    .isEqualTo(expected.version());
            assertThat(service.activeRegionIds()).containsExactly(region);
        }

        @Test
        void theLeaseWindowIsReconstructedFromTheAssignedExpiry() {
            WorkerValidationService service = service();
            service.onMessage(from(primary),
                    seat(RegionReplicaRole.VALIDATOR, List.of(primary, self.nodeId()), 1));

            RegionLease lease = service.lease(region).orElseThrow();
            assertThat(lease.expiresAtTick() - NoderaConstants.LEASE_LENGTH_TICKS)
                    .isEqualTo(lease.validFromTick());
        }

        @Test
        void aResidentIsNeverHandedPrimacy() {
            WorkerValidationService service = service();

            // Primacy is geometric — a peer with no player view is nowhere and must refuse it, even if
            // a malformed or hostile assignment names it first.
            service.onMessage(from(primary),
                    seat(RegionReplicaRole.PRIMARY, List.of(self.nodeId(), primary), 1));
            assertThat(service.lease(region)).isEmpty();

            service.onMessage(from(primary),
                    seat(RegionReplicaRole.VALIDATOR, List.of(self.nodeId(), primary), 1));
            assertThat(service.lease(region)).isEmpty();
        }

        @Test
        void anAssignmentThisNodeIsNotOnIsIgnored() {
            WorkerValidationService service = service();
            NodeId someoneElse = NodeIdentity.generate().nodeId();

            service.onMessage(from(primary),
                    seat(RegionReplicaRole.VALIDATOR, List.of(primary, someoneElse), 1));

            assertThat(service.lease(region)).isEmpty();
        }

        @Test
        void reAssignmentAtTheSameEpochDoesNotRewindALiveReplica() {
            WorkerValidationService service = service();
            service.onMessage(from(primary),
                    seat(RegionReplicaRole.VALIDATOR, List.of(primary, self.nodeId()), 1));

            // The hosting world re-plans on every membership or movement change and re-sends seats.
            // Replacing a live replica would reset its head root mid-round and make every subsequent
            // proposal fail its prevRoot check — so a same-epoch repeat must be a no-op.
            RegionSnapshot before = service.currentSnapshot(region).orElseThrow();
            service.onMessage(from(primary),
                    seat(RegionReplicaRole.VALIDATOR, List.of(primary, self.nodeId()), 1));

            assertThat(service.currentSnapshot(region).orElseThrow()).isSameAs(before);
            assertThat(service.lease(region).orElseThrow().epoch()).isEqualTo(new RegionEpoch(1));
        }

        @Test
        void aNewerEpochReseatsTheRegion() {
            WorkerValidationService service = service();
            service.onMessage(from(primary),
                    seat(RegionReplicaRole.VALIDATOR, List.of(primary, self.nodeId()), 1));
            NodeId newPrimary = NodeIdentity.generate().nodeId();

            service.onMessage(from(newPrimary),
                    seat(RegionReplicaRole.VALIDATOR, List.of(newPrimary, self.nodeId()), 2));

            assertThat(service.lease(region).orElseThrow().epoch()).isEqualTo(new RegionEpoch(2));
            assertThat(service.lease(region).orElseThrow().primary()).isEqualTo(newPrimary);
        }

        @Test
        void bindingTheWorldSeedIsWhatMakesAWorkersReExecutionMatchThePrimarys() {
            WorkerValidationService service = service();
            long worldSeed = 8_675_309L;

            // A worker boots with a placeholder seed — it has no world yet. Binding is the handoff
            // that makes its DeterministicRandom stream agree with the region primaries it votes with.
            assertThat(service.bindWorld(worldSeed)).isTrue();
            assertThat(service.worldSeed()).isEqualTo(worldSeed);
        }

        @Test
        void theSeedCannotBeChangedUnderALiveCommittee() {
            WorkerValidationService service = service();
            service.bindWorld(42L);
            service.onMessage(from(primary),
                    seat(RegionReplicaRole.VALIDATOR, List.of(primary, self.nodeId()), 1));

            // Re-binding mid-round would fork this node's execution from the rest of the committee.
            assertThat(service.bindWorld(99L)).isFalse();
            assertThat(service.worldSeed()).isEqualTo(42L);

            // Re-binding to the SAME seed is a harmless no-op (the mod re-meshes on every re-plan).
            assertThat(service.bindWorld(42L)).isTrue();
        }
    }

    /**
     * The plan is a shared computation, and a shared computation needs shared inputs (network L-30).
     *
     * <p>{@code EntityLaneBootstrap.plan} is pure, so two members derive identical leases from identical
     * inputs — that is the property the whole host-free ownership model rests on. The live defect these
     * tests pin is that the two sides were <b>not</b> given identical inputs: the host planned with
     * {@code residents.keySet()}, the joining client planned with the four-argument overload and
     * therefore with no residents at all, because the resident pool was never in the broadcast payload.
     *
     * <p>The consequence is not a crash but silence. The client primaries a region and computes a
     * committee of players only; the resident worker was seated by the host under a lease that names it
     * a validator, re-executes the batch and votes — and the vote arrives from a node the client's own
     * lease does not list, so it is dropped. Every symptom L-30 records follows from that: seats that
     * exist on one side, {@code votes_received=0} on the other, and no two comparable roots.
     *
     * <p>These are the assertions that make the divergence a test failure rather than a live mystery.
     * The fix is the {@code residents} field on {@code NoderaLanePlanPayload} plus the client passing it
     * to the five-argument overload; {@code ClientValidationLaneResidentPoolTest} covers the filter that
     * turns the broadcast pool into plan input.
     */
    @Nested
    final class ResidentPlanAgreementTest {
        private static final int COMMITTEE = 3;
        private static final long TICK = 1_000L;

        private static NodeId node(long id) {
            return new NodeId(new UUID(0, id));
        }

        private static PlayerView view(int blockX, int blockZ) {
            return PlayerView.fromBlock(DimensionKey.overworld(), blockX, blockZ, 8);
        }

        /** One player, one always-on worker: the topology every ordinary shared world starts in. */
        private static final Map<NodeId, PlayerView> ONE_PLAYER = Map.of(node(1), view(0, 0));

        @Test
        @DisplayName("same views, same residents, two members: byte-identical leases")
        void identicalInputsAgree() {
            List<NodeId> residents = List.of(node(90), node(91));

            List<EntityLaneBootstrap.PlannedRegion> host =
                    EntityLaneBootstrap.plan(ONE_PLAYER, node(1), TICK, COMMITTEE, residents);
            List<EntityLaneBootstrap.PlannedRegion> joiner =
                    EntityLaneBootstrap.plan(ONE_PLAYER, node(90), TICK, COMMITTEE, residents);

            assertThat(host).hasSameSizeAs(joiner);
            for (int i = 0; i < host.size(); i++) {
                assertThat(host.get(i).lease())
                        .as("the lease is the agreement; only locallyPrimary may differ by perspective")
                        .isEqualTo(joiner.get(i).lease());
            }
        }

        @Test
        @DisplayName("dropping the resident pool changes the committees — the L-30 divergence")
        void aMemberPlanningWithoutResidentsComputesDifferentLeases() {
            List<NodeId> residents = List.of(node(90), node(91));

            List<EntityLaneBootstrap.PlannedRegion> withPool =
                    EntityLaneBootstrap.plan(ONE_PLAYER, node(1), TICK, COMMITTEE, residents);
            // Exactly what the client used to do: the four-argument overload, which passes List.of().
            List<EntityLaneBootstrap.PlannedRegion> withoutPool =
                    EntityLaneBootstrap.plan(ONE_PLAYER, node(1), TICK, COMMITTEE);

            assertThat(withPool).hasSameSizeAs(withoutPool);
            assertThat(withPool)
                    .as("if these agreed there would have been no bug to fix")
                    .isNotEqualTo(withoutPool);
            for (int i = 0; i < withPool.size(); i++) {
                assertThat(withoutPool.get(i).lease().validators())
                        .as("the residents the host seated are absent from the lease the client held")
                        .doesNotContain(node(90), node(91));
                assertThat(withPool.get(i).lease().validators())
                        .as("and present in the one the host actually planned")
                        .contains(node(90), node(91));
            }
        }

        @Test
        @DisplayName("the dropped vote: a seated resident is not a validator in the poolless lease")
        void theResidentsVoteWouldBeRejected() {
            NodeId resident = node(90);
            List<EntityLaneBootstrap.PlannedRegion> hostPlan =
                    EntityLaneBootstrap.plan(ONE_PLAYER, node(1), TICK, COMMITTEE, List.of(resident));
            List<EntityLaneBootstrap.PlannedRegion> clientPlan =
                    EntityLaneBootstrap.plan(ONE_PLAYER, node(1), TICK, COMMITTEE);

            // The host seats the resident on a region…
            EntityLaneBootstrap.PlannedRegion seated = hostPlan.stream()
                    .filter(p -> p.lease().validators().contains(resident))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the host seated no resident at all"));

            // …and the lease the client holds for that same region does not know it.
            EntityLaneBootstrap.PlannedRegion sameRegion = clientPlan.stream()
                    .filter(p -> p.region().equals(seated.region()))
                    .findFirst()
                    .orElseThrow();
            assertThat(sameRegion.lease().validators())
                    .as("this is why the worker's vote was dropped as coming from outside the committee")
                    .doesNotContain(resident);
        }

        @Test
        @DisplayName("an empty pool is still agreement — a world with no worker is not broken")
        void noResidentsIsNotADivergence() {
            List<EntityLaneBootstrap.PlannedRegion> host =
                    EntityLaneBootstrap.plan(ONE_PLAYER, node(1), TICK, COMMITTEE, List.of());
            List<EntityLaneBootstrap.PlannedRegion> joiner =
                    EntityLaneBootstrap.plan(ONE_PLAYER, node(1), TICK, COMMITTEE);

            assertThat(host)
                    .as("the five-argument overload with an empty pool IS the four-argument one")
                    .isEqualTo(joiner);
        }

        @Test
        @DisplayName("pool order is part of the plan: two members must not sort it differently")
        void poolOrderIsAnInput() {
            List<EntityLaneBootstrap.PlannedRegion> forward = EntityLaneBootstrap.plan(
                    ONE_PLAYER, node(1), TICK, COMMITTEE, List.of(node(90), node(91)));
            List<EntityLaneBootstrap.PlannedRegion> reversed = EntityLaneBootstrap.plan(
                    ONE_PLAYER, node(1), TICK, COMMITTEE, List.of(node(91), node(90)));

            // Documents which way it is, so the client-side filter can be held to it. RegionLease sorts
            // its validators canonically, so a two-resident pool that fills two seats lands identically
            // whichever order it arrived in; the property that matters is that BOTH members answer the
            // same, and this test is what would fail if the planner ever became order-sensitive without
            // the broadcast order being made authoritative.
            assertThat(forward).isEqualTo(reversed);
        }
    }

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
    @Nested
    final class ResidentQuorumIT {
        private final PeerTestHarness harness = PeerTestHarness.create();
        private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);

        @AfterEach
        void tearDown() {
            harness.close();
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
        void aWorldKeepsCommittingAfterTheSecondPlayerLeavesBecauseWorkersHoldTheSeats() {
            NodeIdentity actor = NodeIdentity.generate();
            ValidationNode a = harness.validationNode().build();
            ValidationNode b = harness.validationNode().build();
            ValidationNode w1 = harness.validationNode().build();
            ValidationNode w2 = harness.validationNode().build();
            ValidationNode.mesh(List.of(a, b, w1, w2), actor);

            // Phase 1 — two players in the same region, one worker on the third seat.
            LeaseManager leases = new LeaseManager(200);
            RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
            RegionLease epoch0 = leases.issue(region, a.nodeId(),
                    List.of(b.nodeId(), w1.nodeId()), 0);
            for (ValidationNode w : List.of(a, b, w1)) {
                w.service().activateRegion(base, epoch0);
            }
            a.service().proposeBatch(region, 1, 1,
                    List.of(RegionFixtures.place(actor, region, 1, 1, 5, 70, 5, 1)));
            awaitVersionAbove(a, SnapshotVersion.INITIAL.value());
            long afterFirst = a.service().currentSnapshot(region).orElseThrow().version().value();
            assertThat(afterFirst).isGreaterThan(SnapshotVersion.INITIAL.value());

            // Phase 2 — player B leaves the world for good.
            b.service().revokeRegion(region);
            b.stop();

            // The re-plan seats BOTH workers; the region reopens at the next epoch on the survivors.
            RegionSnapshot head = a.service().currentSnapshot(region).orElseThrow();
            RegionLease epoch1 = leases.issue(region, a.nodeId(),
                    List.of(w1.nodeId(), w2.nodeId()), 100);
            assertThat(1 + epoch1.validators().size()).isEqualTo(NoderaConstants.QUORUM_MVP_SIZE);
            for (ValidationNode w : List.of(a, w1, w2)) {
                w.service().activateRegion(head, epoch1);
            }

            // Phase 3 — the world commits again with no second player anywhere on it.
            a.service().proposeBatch(region, head.tick() + 1, head.tick() + 1,
                    List.of(RegionFixtures.place(actor, region, 2, 2, 6, 70, 6, 1)));
            awaitVersionAbove(a, afterFirst);

            assertThat(a.service().currentSnapshot(region).orElseThrow().version().value())
                    .as("the surviving player's region still commits after the other player left")
                    .isGreaterThan(afterFirst);
            var certificate = a.service().latestCertificate(region).orElseThrow();
            assertThat(certificate.votes().stream().map(v -> v.voter()).distinct())
                    .as("the quorum is co-signed by peers that are not the proposer")
                    .hasSizeGreaterThanOrEqualTo(2);
            assertThat(certificate.votes().stream().map(v -> v.voter()))
                    .as("a standing worker — no Minecraft process — carried the quorum")
                    .containsAnyOf(w1.nodeId(), w2.nodeId());
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

        private void awaitVersionAbove(ValidationNode node, long version) {
            Await.quietly(15_000, () -> node.service().currentSnapshot(region)
                    .map(snapshot -> snapshot.version().value() > version).orElse(false));
        }
    }
}
