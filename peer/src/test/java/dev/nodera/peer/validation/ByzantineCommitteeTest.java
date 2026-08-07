package dev.nodera.peer.validation;

import dev.nodera.coordinator.LeaseManager;
import dev.nodera.coordinator.PipelineState;
import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.fallback.CrossRegionRouter;
import dev.nodera.fallback.Route;
import dev.nodera.fallback.RoutingDecision;
import dev.nodera.fallback.RoutingReason;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.simulationmsg.RegionProposal;
import dev.nodera.protocol.simulationmsg.ValidationVote;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.peer.Await;
import dev.nodera.testkit.peer.MeshNode;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.testkit.peer.RegionFixtures;
import dev.nodera.testkit.peer.ValidationNode;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A committee with a member that is lying, gone, or merely wrong.
 *
 * <p>Three sibling classes over one subject: a byzantine mesh, the collapse when too few honest
 * members remain, and the divergence counter that has to notice. They were three files over the
 * same worker mesh and the same {@code SignedVote}/{@code VoteDecision} import block, and the three
 * belong together — a divergence that is counted but never collapses the committee is a metric, and
 * a collapse with nothing counted is a guess.
 *
 * <p>Each nest keeps the class Javadoc naming what it was written from, and JUnit reports every
 * {@code @Nested @Test} individually, so the count this file contributes is unchanged.
 */
final class ByzantineCommitteeTest {

    /**
     * L-18's last literal exit clause: <b>Byzantine ITs green under adversarial peers</b>.
     *
     * <p>The peers here are genuinely adversarial: a raw {@link MessageHandler} on the mesh that reads
     * the primary's {@link RegionProposal} and answers it dishonestly. Everything the honest members do
     * is the production path — {@link dev.nodera.peer.validation.WorkerValidationService} and the
     * {@code VoteCollector} it drives, not a test double.
     *
     * <p>This is now the <b>only</b> Byzantine coverage there is. A sibling suite,
     * {@code ByzantineWorkerTest}, used to fold lying ballots into an engine-side {@code CommitteeSession}
     * by handing them in directly — no adversary ever spoke the wire — and both it and the session it
     * drove were deleted on 2026-08-06 (Plan 11 round 2, issue #210) as unreachable from any production
     * entry point. Nothing was lost that this file does not cover over the real transport.
     *
     * <p>Three attacks, three defences that already had to hold:
     * <ul>
     *   <li>a validator that votes for a root it never computed — outvoted, and the certificate carries
     *       the engine-correct root;</li>
     *   <li>a validator that forges a vote in an honest member's name — refused at the address/signature
     *       gate, so it cannot make quorum out of thin air;</li>
     *   <li>a validator that equivocates — one voter, one seat, so a double-vote buys nothing.</li>
     * </ul>
     */
    @Nested
    final class ByzantineMeshIT {
        /**
         * Two seconds, not the harness's five: two of these three cases assert that a round <b>does
         * not</b> commit, so the vote timeout is how long each test spends proving it.
         */
        private static final long VOTE_TIMEOUT_MILLIS = 2_000L;

        private final PeerTestHarness harness = PeerTestHarness.create();
        private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);

        @AfterEach
        void tearDown() {
            harness.close();
        }

        private ValidationNode worker() {
            return harness.validationNode().voteTimeoutMillis(VOTE_TIMEOUT_MILLIS).build();
        }

        @Test
        void aValidatorVotingForARootItNeverComputedIsOutvotedAndTheCommitStaysCorrect() {
            NodeIdentity liarId = NodeIdentity.generate();
            NodeIdentity actor = NodeIdentity.generate();

            ValidationNode primary = worker();
            ValidationNode honest = worker();
            Liar liar = liar(liarId, wrongRoot(), null, null);

            ValidationNode.mesh(List.of(primary, honest), actor);
            primary.introduce(liarId);
            honest.introduce(liarId);

            RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
            RegionLease lease = new RegionLease(region, RegionEpoch.INITIAL, primary.nodeId(),
                    List.of(honest.nodeId(), liarId.nodeId()), 0, 400);
            primary.service().activateRegion(base, lease);
            honest.service().activateRegion(base, lease);

            Optional<StateRoot> committed = primary.service().proposeBatch(
                    region, 1, 1, List.of(place(actor, 5, 70, 5, 1)));

            assertThat(liar.votesSent).as("the adversary really did speak on the wire").isNotEmpty();
            assertThat(committed).as("an honest majority commits despite the liar").isPresent();
            assertThat(committed.get())
                    .as("the committed root is the engine's, never the liar's")
                    .isNotEqualTo(wrongRoot());

            var certificate = primary.service().latestCertificate(region).orElseThrow();
            assertThat(certificate.resultingRoot()).isEqualTo(committed.get());
            assertThat(certificate.votes())
                    .as("no vote in the certificate carries the fabricated root")
                    .noneMatch(v -> v.resultingRoot().equals(wrongRoot()));

            // The honest validator converged on the same root — the liar changed nothing anywhere.
            Await.quietly(10_000, () -> honest.headRoot(region).equals(committed));
            assertThat(honest.headRoot(region)).contains(committed.get());
        }

        @Test
        void aForgedVoteInAnHonestMembersNameCannotManufactureQuorum() {
            NodeIdentity absentId = NodeIdentity.generate();   // a real committee member that is offline
            NodeIdentity liarId = NodeIdentity.generate();
            NodeIdentity actor = NodeIdentity.generate();

            ValidationNode primary = worker();
            // The liar answers every proposal TWICE: once as itself, once impersonating the absent
            // member — the cheapest possible way to fake a majority if identity were unchecked.
            Liar liar = liar(liarId, wrongRoot(), absentId, null);

            primary.registerActor(actor);
            primary.introduce(absentId);
            primary.introduce(liarId);

            RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
            RegionLease lease = new RegionLease(region, RegionEpoch.INITIAL, primary.nodeId(),
                    List.of(absentId.nodeId(), liarId.nodeId()), 0, 400);
            primary.service().activateRegion(base, lease);

            Optional<StateRoot> committed = primary.service().proposeBatch(
                    region, 1, 1, List.of(place(actor, 5, 70, 5, 1)));

            assertThat(liar.votesSent).hasSizeGreaterThanOrEqualTo(2);
            assertThat(committed)
                    .as("a forged identity buys no seat: the round times out rather than commits")
                    .isEmpty();
            assertThat(primary.service().latestCertificate(region)).isEmpty();
            assertThat(primary.service().currentSnapshot(region).orElseThrow().version())
                    .isEqualTo(SnapshotVersion.INITIAL);
        }

        @Test
        void anEquivocatingValidatorGetsOneSeatNotTwo() {
            NodeIdentity absentId = NodeIdentity.generate();
            NodeIdentity liarId = NodeIdentity.generate();
            NodeIdentity actor = NodeIdentity.generate();

            ValidationNode primary = worker();
            // Same voter, two contradictory roots for one proposal — if the collector counted both,
            // the liar plus the primary would look like a two-vote majority for a fabricated root.
            Liar liar = liar(liarId, wrongRoot(), null, otherWrongRoot());

            primary.registerActor(actor);
            primary.introduce(absentId);
            primary.introduce(liarId);

            RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
            RegionLease lease = new RegionLease(region, RegionEpoch.INITIAL, primary.nodeId(),
                    List.of(absentId.nodeId(), liarId.nodeId()), 0, 400);
            primary.service().activateRegion(base, lease);

            Optional<StateRoot> committed = primary.service().proposeBatch(
                    region, 1, 1, List.of(place(actor, 5, 70, 5, 1)));

            assertThat(liar.votesSent).as("both contradictory votes were sent")
                    .hasSizeGreaterThanOrEqualTo(2);
            assertThat(committed).as("one voter, one seat — no quorum on a fabricated root").isEmpty();
            assertThat(primary.service().latestCertificate(region)).isEmpty();
        }

        // --- the adversary ------------------------------------------------------------------------

        /**
         * A peer that speaks the validation protocol dishonestly. It never runs the engine: it reads
         * the primary's proposal and answers with whatever roots it was constructed to claim.
         */
        private static final class Liar implements MeshNode.MessageSink {

            private final NodeIdentity identity;
            private final LoopbackTransport transport;
            private final StateRoot claimedRoot;
            private final NodeIdentity impersonate;    // nullable — forge a vote in this member's name
            private final StateRoot secondClaimedRoot; // nullable — equivocate with a second root
            private final List<StateRoot> votesSent = new CopyOnWriteArrayList<>();

            private Liar(NodeIdentity identity, LoopbackTransport transport, StateRoot claimedRoot,
                         NodeIdentity impersonate, StateRoot secondClaimedRoot) {
                this.identity = identity;
                this.transport = transport;
                this.claimedRoot = claimedRoot;
                this.impersonate = impersonate;
                this.secondClaimedRoot = secondClaimedRoot;
            }

            @Override
            public void accept(PeerAddress from, NoderaMessage message) {
                if (message instanceof RegionProposal proposal) {
                    reply(from, proposal, identity, claimedRoot);
                    if (impersonate != null) {
                        // Forged: the vote NAMES the absent member but is signed by the liar.
                        reply(from, proposal, impersonate, claimedRoot);
                    }
                    if (secondClaimedRoot != null) {
                        reply(from, proposal, identity, secondClaimedRoot);
                    }
                }
            }

            private void reply(PeerAddress to, RegionProposal proposal, NodeIdentity claimAs,
                               StateRoot root) {
                SignedVote unsigned = new SignedVote(claimAs.nodeId(), proposal.region(),
                        proposal.epoch(), proposal.baseVersion(), proposal.batchRoot(),
                        root, root, VoteDecision.ACCEPT, Bytes.empty());
                // Always signed with the LIAR's key — that is exactly what makes the forged vote forged.
                SignedVote vote = new SignedVote(claimAs.nodeId(), proposal.region(),
                        proposal.epoch(), proposal.baseVersion(), proposal.batchRoot(),
                        root, root, VoteDecision.ACCEPT, identity.sign(unsigned.signedPortion()));
                votesSent.add(root);
                transport.send(to, WireCodec.encode(new ValidationVote(
                        proposal.region(), proposal.epoch(), proposal.baseVersion(), vote)));
            }
        }

        private Liar liar(NodeIdentity id, StateRoot claimedRoot, NodeIdentity impersonate,
                          StateRoot second) {
            // An adversary is a mesh node like any other; what makes it adversarial is its sink.
            return harness.<Liar>messageNode(id,
                    (identity, transport, peers) ->
                            new Liar(identity, transport, claimedRoot, impersonate, second),
                    sink -> sink).service();
        }

        // --- fixture ------------------------------------------------------------------------------

        private static StateRoot wrongRoot() {
            return rootOf((byte) 0x66);
        }

        private static StateRoot otherWrongRoot() {
            return rootOf((byte) 0x77);
        }

        private static StateRoot rootOf(byte fill) {
            byte[] raw = new byte[32];
            Arrays.fill(raw, fill);
            return new StateRoot(Bytes.unsafeWrap(raw));
        }

        private dev.nodera.core.action.ActionEnvelope place(NodeIdentity actor, int x, int y, int z,
                                                            long seq) {
            return RegionFixtures.place(actor, region, seq, seq, x, y, z, 1);
        }
    }

    /**
     * Task 8 acceptance #2 (issue #8 / debugger intake #17): kill 2-of-3 committee members mid-lease
     * — the region must fall back to the server lane within that lease (quorum loss revokes, the
     * router classifies COMMITTEE_COLLAPSED, the fallback lane commits the action), and when
     * validators return the committee is rebuilt under a bumped epoch and commits in quorum again.
     */
    @Nested
    final class CommitteeCollapseIT {
        /**
         * Short on purpose, and the one parameter of this suite that must never be defaulted away: the
         * proposal in the middle of this test CANNOT reach quorum, so the round has to time out inside
         * the test rather than after it. The harness default of five seconds would make the collapse
         * assertion wait on a wall clock instead of on the behaviour.
         */
        private static final long VOTE_TIMEOUT_MILLIS = 700L;

        private final PeerTestHarness harness = PeerTestHarness.create();
        private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);
        private NodeIdentity actor;

        @AfterEach
        void tearDown() {
            harness.close();
        }

        private ValidationNode worker(NodeIdentity id) {
            return harness.validationNode(id).voteTimeoutMillis(VOTE_TIMEOUT_MILLIS).build();
        }

        @Test
        void killingTwoOfThreeFallsBackWithinTheLeaseAndTheRebuiltCommitteeCommitsAgain() {
            NodeIdentity idA = NodeIdentity.generate();
            NodeIdentity idB = NodeIdentity.generate();
            NodeIdentity idC = NodeIdentity.generate();
            actor = NodeIdentity.generate();

            ValidationNode a = worker(idA);
            ValidationNode b = worker(idB);
            ValidationNode c = worker(idC);
            ValidationNode.mesh(List.of(a, b, c), actor);

            RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
            RegionLease lease = new RegionLease(
                    region, RegionEpoch.INITIAL, idA.nodeId(),
                    List.of(idB.nodeId(), idC.nodeId()), 0, 200);
            for (ValidationNode w : List.of(a, b, c)) {
                w.service().activateRegion(base, lease);
            }

            // --- sanity: the healthy 3-member committee commits batch 1 in quorum ---
            Optional<StateRoot> committed1 =
                    a.service().proposeBatch(region, 0, 1, List.of(place(1, 0, 5, 70, 5, 1)));
            assertThat(committed1).as("healthy committee commits").isPresent();
            RegionSnapshot afterBatch1 = a.service().currentSnapshot(region).orElseThrow();

            // --- kill 2 of 3: both validators die mid-lease ---
            b.stop();
            c.stop();

            // --- the next proposal cannot reach quorum: the region revokes WITHIN the lease ---
            Optional<StateRoot> committed2 =
                    a.service().proposeBatch(region, 1, 2, List.of(place(2, 1, 6, 70, 6, 1)));
            assertThat(committed2).as("no quorum with 2/3 dead").isEmpty();
            assertThat(a.service().pipelineState(region))
                    .as("quorum loss revokes the region inside the lease window")
                    .isEqualTo(PipelineState.REVOKED);

            // --- the fallback lane owns the region now: COMMITTEE_COLLAPSED routes to the server
            //     lane and the action commits there (no committee, no partial state) ---
            ActionEnvelope fallbackAction = place(3, 2, 7, 70, 7, 1);
            RoutingDecision decision = a.service().routeAndMaybeFallback(
                    fallbackAction, CrossRegionRouter.RegionStatus.COMMITTEE_COLLAPSED, afterBatch1);
            assertThat(decision.reason()).isEqualTo(RoutingReason.COMMITTEE_COLLAPSED);
            assertThat(decision.route()).isEqualTo(Route.SERVER_FALLBACK);
            assertThat(a.service().snapshot().fallbackCommits())
                    .as("the server lane committed the action while the committee is down")
                    .isEqualTo(1L);

            // --- validators return: rebuild the committee under a bumped epoch and commit again ---
            ValidationNode b2 = worker(idB);
            ValidationNode c2 = worker(idC);
            ValidationNode.mesh(List.of(a, b2, c2), actor);
            RegionLease rebuilt = new RegionLease(
                    region, new RegionEpoch(RegionEpoch.INITIAL.value() + 1), idA.nodeId(),
                    List.of(idB.nodeId(), idC.nodeId()), 0, 400);
            for (ValidationNode w : List.of(a, b2, c2)) {
                w.service().activateRegion(afterBatch1, rebuilt);
            }

            Optional<StateRoot> committed3 =
                    a.service().proposeBatch(region, 2, 3, List.of(place(9, 2, 40, 100, 40, 4)));
            assertThat(committed3)
                    .as("the rebuilt committee commits in quorum at epoch+1")
                    .isPresent();
            assertThat(a.service().pipelineState(region)).isNotEqualTo(PipelineState.REVOKED);
            assertThat(a.service().latestCertificate(region)).isPresent();
            assertThat(a.service().latestCertificate(region).orElseThrow().epoch())
                    .isEqualTo(new RegionEpoch(RegionEpoch.INITIAL.value() + 1));
        }

        @Test
        void crossRegionPlacementRoutesToTheServerLaneAtomically() {
            // Task 8 acceptance #1: a border placement whose target block lies OUTSIDE the envelope's
            // region never enters the committee lane — it routes CROSS_REGION to the server fallback
            // and commits there in one lane (single-writer ⇒ no partial commit by construction).
            NodeIdentity idA = NodeIdentity.generate();
            actor = NodeIdentity.generate();
            ValidationNode a = worker(idA);
            a.registerActor(actor);

            RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
            RegionLease lease = new RegionLease(
                    region, RegionEpoch.INITIAL, idA.nodeId(), List.of(), 0, 200);
            a.service().activateRegion(base, lease);

            // x=130 lies in the neighbouring region (region 0,0 owns blocks 0..127).
            ActionEnvelope crossBorder = place(1, 1, 130, 70, 5, 1);
            RoutingDecision decision = a.service().routeAndMaybeFallback(
                    crossBorder, CrossRegionRouter.RegionStatus.DELEGATED_HEALTHY, base);

            assertThat(decision.reason()).isEqualTo(RoutingReason.CROSS_REGION);
            assertThat(decision.route()).isEqualTo(Route.SERVER_FALLBACK);
            assertThat(a.service().snapshot().committeeCommits())
                    .as("the committee lane never saw the cross-region action")
                    .isZero();
            assertThat(a.service().snapshot().fallbackCommits())
                    .as("no local execution against the wrong region's base — the target region's "
                            + "server-lane owner executes the routed action")
                    .isZero();
        }

        private ActionEnvelope place(long seq, long tick, int x, int y, int z, int stateId) {
            return RegionFixtures.place(actor, region, seq, tick, x, y, z, stateId);
        }
    }

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
    @Nested
    final class DivergenceCountedIT {
        private final PeerTestHarness harness = PeerTestHarness.create();
        private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);

        @AfterEach
        void tearDown() {
            harness.close();
        }

        @Test
        void aValidatorThatComputesADifferentWorldIsCounted() {
            NodeIdentity actor = NodeIdentity.generate();
            ValidationNode primary = harness.validationNode().build();
            // This validator's engine reports a corrupted root for the same inputs — a stand-in for the
            // thing the gate hunts: different hardware, different answer.
            ValidationNode validator = harness.validationNode()
                    .engine(corrupting(harness.honestEngine()))
                    .build();
            ValidationNode.mesh(List.of(primary, validator), actor);

            RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
            RegionLease lease = new LeaseManager(200).issue(
                    region, primary.nodeId(), List.of(validator.nodeId()), 0);
            primary.service().activateRegion(base, lease);
            validator.service().activateRegion(base, lease);

            assertThat(validator.service().divergences()).isZero();
            primary.service().proposeBatch(region, 1, 1, List.of(signedPlace(actor, 1)));

            Await.quietly(10_000, () -> validator.service().divergences() > 0);
            assertThat(validator.service().divergences())
                    .as("the disagreement is a measurement, not a silent no-vote")
                    .isGreaterThan(0);
            assertThat(validator.service().snapshot().divergences())
                    .as("and it reaches the STATE reply a soak reads")
                    .isEqualTo(validator.service().divergences());
        }

        @Test
        void agreementNeverIncrementsTheCounter() {
            NodeIdentity actor = NodeIdentity.generate();
            ValidationNode primary = harness.validationNode().build();
            ValidationNode validator = harness.validationNode().build();
            ValidationNode.mesh(List.of(primary, validator), actor);

            RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
            RegionLease lease = new LeaseManager(200).issue(
                    region, primary.nodeId(), List.of(validator.nodeId()), 0);
            primary.service().activateRegion(base, lease);
            validator.service().activateRegion(base, lease);

            for (int i = 1; i <= 5; i++) {
                primary.service().proposeBatch(region, i, i, List.of(signedPlace(actor, i)));
            }

            assertThat(primary.service().snapshot().committeeCommits())
                    .as("the batches really did go through the committee")
                    .isGreaterThan(0);
            assertThat(primary.service().divergences()).isZero();
            assertThat(validator.service().divergences())
                    .as("healthy play must leave the gate's number at zero")
                    .isZero();
        }

        // --- fixture -----------------------------------------------------------------------------

        private ActionEnvelope signedPlace(NodeIdentity actor, long tick) {
            return RegionFixtures.place(actor, region, tick, tick, 5, 70, 5, 1);
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
    }
}
