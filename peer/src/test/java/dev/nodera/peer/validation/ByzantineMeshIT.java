package dev.nodera.peer.validation;

import dev.nodera.core.Bytes;
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
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.simulationmsg.RegionProposal;
import dev.nodera.protocol.simulationmsg.ValidationVote;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.peer.Await;
import dev.nodera.testkit.peer.MeshNode;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.testkit.peer.RegionFixtures;
import dev.nodera.testkit.peer.ValidationNode;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-18's last literal exit clause: <b>Byzantine ITs green under adversarial peers</b>.
 *
 * <p>{@code ByzantineWorkerTest} proves the engine's {@code CommitteeSession} folds lying ballots
 * correctly, but it hands those ballots in directly — no adversary ever spoke the wire. The peers
 * here are genuinely adversarial: a raw {@link MessageHandler} on the mesh that reads the primary's
 * {@link RegionProposal} and answers it dishonestly. Everything the honest members do is the
 * production path.
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
