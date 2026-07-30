package dev.nodera.peer.validation;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.protocol.simulationmsg.RegionProposal;
import dev.nodera.protocol.simulationmsg.ValidationVote;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.storage.event.InMemoryCertificateStore;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.LoopbackTransport.LoopbackNetwork;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
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

    private static final long WORLD_SEED = 0x4E4F4445_5241L;
    private static final int MIN_Y = -64;
    private static final int SECTION_COUNT = 24;

    private final HashService hashes = new HashService();
    private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);
    private final List<dev.nodera.peer.PeerRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (dev.nodera.peer.PeerRuntime rt : runtimes) {
            rt.stop();
        }
        for (LoopbackTransport tx : adversaries) {
            tx.stop();
        }
    }

    @Test
    void aValidatorVotingForARootItNeverComputedIsOutvotedAndTheCommitStaysCorrect() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity primaryId = NodeIdentity.generate();
        NodeIdentity honestId = NodeIdentity.generate();
        NodeIdentity liarId = NodeIdentity.generate();
        NodeIdentity actor = NodeIdentity.generate();

        Worker primary = worker(net, primaryId);
        Worker honest = worker(net, honestId);
        Liar liar = liar(net, liarId, wrongRoot());

        introduce(primary, List.of(honestId, liarId));
        introduce(honest, List.of(primaryId, liarId));
        primary.service.registerActor(actor.nodeId(), actor.publicKeyBytes());
        honest.service.registerActor(actor.nodeId(), actor.publicKeyBytes());

        RegionSnapshot base = fullUniformSnapshot(region, 0);
        RegionLease lease = new RegionLease(region, RegionEpoch.INITIAL, primaryId.nodeId(),
                List.of(honestId.nodeId(), liarId.nodeId()), 0, 400);
        primary.service.activateRegion(base, lease);
        honest.service.activateRegion(base, lease);

        Optional<StateRoot> committed = primary.service.proposeBatch(
                region, 1, 1, List.of(place(actor, 5, 70, 5, 1)));

        assertThat(liar.votesSent).as("the adversary really did speak on the wire").isNotEmpty();
        assertThat(committed).as("an honest majority commits despite the liar").isPresent();
        assertThat(committed.get())
                .as("the committed root is the engine's, never the liar's")
                .isNotEqualTo(wrongRoot());

        var certificate = primary.service.latestCertificate(region).orElseThrow();
        assertThat(certificate.resultingRoot()).isEqualTo(committed.get());
        assertThat(certificate.votes())
                .as("no vote in the certificate carries the fabricated root")
                .noneMatch(v -> v.resultingRoot().equals(wrongRoot()));

        // The honest validator converged on the same root — the liar changed nothing anywhere.
        awaitHead(honest, committed.get());
        assertThat(honest.service.headRoot(region)).contains(committed.get());
    }

    @Test
    void aForgedVoteInAnHonestMembersNameCannotManufactureQuorum() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity primaryId = NodeIdentity.generate();
        NodeIdentity absentId = NodeIdentity.generate();   // a real committee member that is offline
        NodeIdentity liarId = NodeIdentity.generate();
        NodeIdentity actor = NodeIdentity.generate();

        Worker primary = worker(net, primaryId);
        // The liar answers every proposal TWICE: once as itself, once impersonating the absent
        // member — the cheapest possible way to fake a majority if identity were unchecked.
        Liar liar = liar(net, liarId, wrongRoot(), absentId);

        introduce(primary, List.of(absentId, liarId));
        primary.service.registerActor(actor.nodeId(), actor.publicKeyBytes());

        RegionSnapshot base = fullUniformSnapshot(region, 0);
        RegionLease lease = new RegionLease(region, RegionEpoch.INITIAL, primaryId.nodeId(),
                List.of(absentId.nodeId(), liarId.nodeId()), 0, 400);
        primary.service.activateRegion(base, lease);

        Optional<StateRoot> committed = primary.service.proposeBatch(
                region, 1, 1, List.of(place(actor, 5, 70, 5, 1)));

        assertThat(liar.votesSent).hasSizeGreaterThanOrEqualTo(2);
        assertThat(committed)
                .as("a forged identity buys no seat: the round times out rather than commits")
                .isEmpty();
        assertThat(primary.service.latestCertificate(region)).isEmpty();
        assertThat(primary.service.currentSnapshot(region).orElseThrow().version())
                .isEqualTo(SnapshotVersion.INITIAL);
    }

    @Test
    void anEquivocatingValidatorGetsOneSeatNotTwo() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity primaryId = NodeIdentity.generate();
        NodeIdentity absentId = NodeIdentity.generate();
        NodeIdentity liarId = NodeIdentity.generate();
        NodeIdentity actor = NodeIdentity.generate();

        Worker primary = worker(net, primaryId);
        // Same voter, two contradictory roots for one proposal — if the collector counted both,
        // the liar plus the primary would look like a two-vote majority for a fabricated root.
        Liar liar = liar(net, liarId, wrongRoot(), null, otherWrongRoot());

        introduce(primary, List.of(absentId, liarId));
        primary.service.registerActor(actor.nodeId(), actor.publicKeyBytes());

        RegionSnapshot base = fullUniformSnapshot(region, 0);
        RegionLease lease = new RegionLease(region, RegionEpoch.INITIAL, primaryId.nodeId(),
                List.of(absentId.nodeId(), liarId.nodeId()), 0, 400);
        primary.service.activateRegion(base, lease);

        Optional<StateRoot> committed = primary.service.proposeBatch(
                region, 1, 1, List.of(place(actor, 5, 70, 5, 1)));

        assertThat(liar.votesSent).as("both contradictory votes were sent").hasSizeGreaterThanOrEqualTo(2);
        assertThat(committed).as("one voter, one seat — no quorum on a fabricated root").isEmpty();
        assertThat(primary.service.latestCertificate(region)).isEmpty();
    }

    // --- the adversary ------------------------------------------------------------------------

    /**
     * A peer that speaks the validation protocol dishonestly. It never runs the engine: it reads
     * the primary's proposal and answers with whatever roots it was constructed to claim.
     */
    private final class Liar {
        private final NodeIdentity identity;
        private final LoopbackTransport transport;
        private final StateRoot claimedRoot;
        private final NodeIdentity impersonate;   // nullable — forge a vote in this member's name
        private final StateRoot secondClaimedRoot; // nullable — equivocate with a second root
        private final List<StateRoot> votesSent = new CopyOnWriteArrayList<>();

        private Liar(NodeIdentity identity, LoopbackTransport transport, StateRoot claimedRoot,
                     NodeIdentity impersonate, StateRoot secondClaimedRoot) {
            this.identity = identity;
            this.transport = transport;
            this.claimedRoot = claimedRoot;
            this.impersonate = impersonate;
            this.secondClaimedRoot = secondClaimedRoot;
            transport.setHandler(new MessageHandler() {
                @Override
                public void onMessage(PeerAddress from, byte[] frame) {
                    NoderaMessage message;
                    try {
                        message = WireCodec.decode(frame);
                    } catch (RuntimeException notForUs) {
                        return;
                    }
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

                @Override
                public void onPeerDown(PeerAddress peer) {
                    // an adversary has nothing to clean up
                }
            });
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

    private Liar liar(LoopbackNetwork net, NodeIdentity id, StateRoot claimedRoot) {
        return liar(net, id, claimedRoot, null, null);
    }

    private Liar liar(LoopbackNetwork net, NodeIdentity id, StateRoot claimedRoot,
                      NodeIdentity impersonate) {
        return liar(net, id, claimedRoot, impersonate, null);
    }

    private Liar liar(LoopbackNetwork net, NodeIdentity id, StateRoot claimedRoot,
                      NodeIdentity impersonate, StateRoot second) {
        LoopbackTransport tx = net.register(id.nodeId());
        Liar adversary = new Liar(id, tx, claimedRoot, impersonate, second);
        tx.start(); // an adversary is only routable once it is on the network, like any peer
        adversaries.add(tx);
        return adversary;
    }

    private final List<LoopbackTransport> adversaries = new ArrayList<>();

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

    private void introduce(Worker w, List<NodeIdentity> others) {
        for (NodeIdentity other : others) {
            w.service.registerPeer(other.nodeId(),
                    PeerAddress.of(other.nodeId(), "loopback"), other.publicKeyBytes());
        }
    }

    private ActionEnvelope place(NodeIdentity actor, int x, int y, int z, long seq) {
        ActionEnvelope unsigned = new ActionEnvelope(actor.nodeId(), seq, seq, seq, region,
                new PlaceBlockAction(new NBlockPos(x, y, z), 1, 1), Bytes.empty());
        return new ActionEnvelope(actor.nodeId(), seq, seq, seq, region,
                new PlaceBlockAction(new NBlockPos(x, y, z), 1, 1),
                actor.sign(unsigned.signedPortion()));
    }

    private void awaitHead(Worker w, StateRoot expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && !w.service.headRoot(region).equals(Optional.of(expected))) {
            Thread.sleep(25);
        }
    }

    private record Worker(NodeIdentity id, WorkerValidationService service) {
    }

    private Worker worker(LoopbackNetwork net, NodeIdentity id) {
        LoopbackTransport tx = net.register(id.nodeId());
        dev.nodera.peer.PeerRuntime runtime = dev.nodera.peer.PeerRuntime.bootstrap(
                id, NodeCapabilities.initial(), tx, () -> "loopback",
                new dev.nodera.peer.PeerRuntimeConfig(
                        Duration.ofMillis(100), Duration.ofMillis(500)), null);
        runtimes.add(runtime);
        WorkerValidationService service = new WorkerValidationService(id, tx,
                new FlatWorldRegionEngine(
                        FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes),
                hashes, new InMemoryCertificateStore(hashes), WORLD_SEED,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), 2_000L);
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
