package dev.nodera.peer.validation;

import dev.nodera.committee.CommitteeFailover;
import dev.nodera.committee.CommitteeMember;
import dev.nodera.consensus.Decision;
import dev.nodera.consensus.EquivocationDetector;
import dev.nodera.consensus.MajorityQuorumPolicy;
import dev.nodera.consensus.ProposalKey;
import dev.nodera.consensus.VoteCollector;
import dev.nodera.coordinator.InMemoryWorldView;
import dev.nodera.coordinator.LeaseManager;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.committee.MemberBallot;
import dev.nodera.fallback.FallbackExecutor;
import dev.nodera.fallback.FallbackRouter;
import dev.nodera.fallback.CrossRegionRouter;
import dev.nodera.fallback.RoutingDecision;
import dev.nodera.fallback.SoakMetrics;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.simulationmsg.ActionBatchMsg;
import dev.nodera.protocol.simulationmsg.CommitAnnounce;
import dev.nodera.protocol.simulationmsg.RegionProposal;
import dev.nodera.protocol.simulationmsg.ValidationVote;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.storage.CertificateStore;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The worker-side committee validation lane (L-48 / L-30): the always-on peer re-executes region
 * batches <b>out-of-game</b> and participates in quorum over the {@link PeerTransport} — the same
 * transport its membership session rides. This wires the previously runtime-unreferenced
 * {@code engine} validation stack ({@link CommitteeMember}, {@link VoteCollector},
 * {@link WorldMutationApplier}, {@link FallbackRouter}/{@link FallbackExecutor}) and the
 * previously unconsumed {@code simulationmsg} wire family ({@link ActionBatchMsg},
 * {@link RegionProposal}, {@link ValidationVote}, {@link CommitAnnounce}) into the live worker.
 *
 * <p>Flow per batch (the headless {@code CommitteeMvpIT} pipeline, distributed):
 * <ol>
 *   <li>the primary re-executes locally ({@link CommitteeMember#computeAndVote}), submits its own
 *       vote, and sends {@link ActionBatchMsg} + {@link RegionProposal} to every validator;</li>
 *   <li>each validator re-executes the batch against its <b>own</b> replica snapshot and answers
 *       with a signed {@link ValidationVote};</li>
 *   <li>the primary's {@link VoteCollector} commits on quorum; the certificate is broadcast as a
 *       {@link CommitAnnounce}; every member applies its own delta through its
 *       {@link WorldMutationApplier}, advances its replica, and persists the certificate in its
 *       {@link CertificateStore} — certified region state flowing peer-to-peer.</li>
 * </ol>
 *
 * <p>Actions for regions without an active committee take the fallback lane
 * ({@link #fallbackExecute}), classified by the {@link FallbackRouter} whose {@link SoakMetrics}
 * report the Phase-4 committee-commit ratio.
 *
 * <p>Thread-context: incoming messages arrive on the owning {@code PeerRuntime}'s state thread
 * (serialized); {@link #proposeBatch} may be called from any single proposer thread and blocks up
 * to the vote timeout.
 */
public final class WorkerValidationService {

    /** One activated region replica on this worker. */
    private final class Replica {
        final RegionLease lease;
        final InMemoryWorldView world = new InMemoryWorldView();
        final WorldMutationApplier applier = new WorldMutationApplier(world);
        RegionSnapshot snapshot;
        StateRoot headRoot;
        MemberBallot pendingBallot;
        QuorumCertificate lastCertificate;

        Replica(RegionSnapshot base, RegionLease lease) {
            this.lease = lease;
            this.snapshot = base;
            this.headRoot = StateRoot.of(hashes.hash(base));
            world.load(base);
        }
    }

    /** One in-flight primary-side vote round. */
    private record Round(VoteCollector collector, CountDownLatch done,
                         AtomicReference<Decision> decision) {
    }

    private final NodeIdentity identity;
    private final PeerTransport transport;
    private final CommitteeMember member;
    private final RegionEngine engine;
    private final HashService hashes;
    private final CertificateStore certificates;
    private final long worldSeed;
    private final int rulesVersion;
    private final long registryFingerprint;
    private final long voteTimeoutMillis;

    private final Map<RegionId, Replica> replicas = new ConcurrentHashMap<>();
    private final Map<NodeId, PeerAddress> peers = new ConcurrentHashMap<>();
    private final AtomicReference<Round> activeRound = new AtomicReference<>();

    private final FallbackRouter router = new FallbackRouter();
    private final AtomicLong proposalsSent = new AtomicLong();
    private final AtomicLong votesCast = new AtomicLong();
    private final AtomicLong votesReceived = new AtomicLong();
    private final AtomicLong committeeCommits = new AtomicLong();
    private final AtomicLong fallbackCommits = new AtomicLong();

    /**
     * @param identity            this worker's identity (signs its votes).
     * @param transport           the transport shared with the membership session; used for
     *                            sending only — receiving rides
     *                            {@code PeerRuntime.onApplicationMessage}.
     * @param engine              THE deterministic region engine (one implementation, Task 0 §3).
     * @param hashes              the canonical hash service.
     * @param certificates        where committed quorum certificates are persisted.
     * @param worldSeed           the world seed of the hosted world.
     * @param rulesVersion        the engine rule-set version (context pin).
     * @param registryFingerprint the palette registry fingerprint (context pin).
     * @param voteTimeoutMillis   how long a primary waits for quorum.
     */
    public WorkerValidationService(NodeIdentity identity, PeerTransport transport,
                                   RegionEngine engine, HashService hashes,
                                   CertificateStore certificates, long worldSeed,
                                   int rulesVersion, long registryFingerprint,
                                   long voteTimeoutMillis) {
        if (identity == null || transport == null || engine == null || hashes == null
                || certificates == null) {
            throw new IllegalArgumentException("no argument may be null");
        }
        this.identity = identity;
        this.transport = transport;
        this.engine = engine;
        this.hashes = hashes;
        this.certificates = certificates;
        this.member = new CommitteeMember(identity, engine);
        this.worldSeed = worldSeed;
        this.rulesVersion = rulesVersion;
        this.registryFingerprint = registryFingerprint;
        this.voteTimeoutMillis = voteTimeoutMillis;
    }

    /** Register a committee peer's transport address (from the membership session). */
    public void registerPeer(NodeId id, PeerAddress address) {
        peers.put(id, address);
    }

    /** Activate a region replica on this worker under {@code lease}. */
    public void activateRegion(RegionSnapshot base, RegionLease lease) {
        replicas.put(base.region(), new Replica(base, lease));
    }

    /** @return the current head root of an activated region replica. */
    public Optional<StateRoot> headRoot(RegionId region) {
        Replica r = replicas.get(region);
        return r == null ? Optional.empty() : Optional.of(r.headRoot);
    }

    /** @return the replica's lease (committee membership) for an activated region. */
    public Optional<RegionLease> lease(RegionId region) {
        Replica r = replicas.get(region);
        return r == null ? Optional.empty() : Optional.of(r.lease);
    }

    /** @return the replica's current (post-commit) snapshot for an activated region. */
    public Optional<RegionSnapshot> currentSnapshot(RegionId region) {
        Replica r = replicas.get(region);
        return r == null ? Optional.empty() : Optional.of(r.snapshot);
    }

    /** @return the most recently committed quorum certificate for an activated region. */
    public Optional<QuorumCertificate> latestCertificate(RegionId region) {
        Replica r = replicas.get(region);
        return r == null ? Optional.empty() : Optional.ofNullable(r.lastCertificate);
    }

    /**
     * Primary-side: run one distributed committee round for {@code actions}.
     *
     * @return the committed root, or empty when quorum was not reached in time.
     */
    public Optional<StateRoot> proposeBatch(RegionId region, long tickFrom, long tickTo,
                                            List<ActionEnvelope> actions) {
        Replica replica = replicas.get(region);
        if (replica == null) {
            throw new IllegalStateException("region not activated: " + region);
        }
        RegionLease lease = replica.lease;
        if (!identity.nodeId().equals(lease.primary())) {
            throw new IllegalStateException("not the primary of " + region);
        }
        ActionBatch batch = new ActionBatch(region, lease.epoch(), replica.snapshot.version(),
                tickFrom, tickTo, actions);
        RegionExecutionRequest request = requestFor(replica, batch);

        MemberBallot ownBallot = member.computeAndVote(request);
        replica.pendingBallot = ownBallot;
        votesCast.incrementAndGet();

        ProposalKey key = new ProposalKey(region, lease.epoch(), replica.snapshot.version());
        VoteCollector collector = new VoteCollector(key, MajorityQuorumPolicy.mvp(),
                replica.headRoot, voteTimeoutMillis);
        collector.submit(ownBallot.vote());
        Round round = new Round(collector, new CountDownLatch(1), new AtomicReference<>());
        activeRound.set(round);

        CanonicalWriter deltaW = new CanonicalWriter();
        ownBallot.delta().encode(deltaW);
        RegionProposal proposal = new RegionProposal(region, lease.epoch(),
                replica.snapshot.version(), tickFrom, tickTo, replica.headRoot,
                ownBallot.root(), deltaW.toBytes(),
                identity.sign(ownBallot.root().hash()));
        for (NodeId validator : lease.validators()) {
            PeerAddress addr = peers.get(validator);
            if (addr != null) {
                transport.send(addr, MessageCodec.encode(new ActionBatchMsg(batch)));
                transport.send(addr, MessageCodec.encode(proposal));
            }
        }
        proposalsSent.incrementAndGet();

        try {
            round.done().await(voteTimeoutMillis + 500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            activeRound.set(null);
        }
        Decision decision = round.decision().get();
        if (decision == null) {
            decision = collector.decide();
        }
        if (!(decision instanceof Decision.Commit commit)) {
            replica.pendingBallot = null;
            return Optional.empty();
        }
        QuorumCertificate cert = commit.certificate();
        commitLocally(replica, cert, tickTo);
        // Certified state flows peer-to-peer: every member gets the co-signed certificate.
        CanonicalWriter certW = new CanonicalWriter();
        cert.encode(certW);
        CommitAnnounce announce = new CommitAnnounce(region, cert.version(),
                cert.resultingRoot(), certW.toBytes());
        for (NodeId validator : lease.validators()) {
            PeerAddress addr = peers.get(validator);
            if (addr != null) {
                transport.send(addr, MessageCodec.encode(announce));
            }
        }
        return Optional.of(cert.resultingRoot());
    }

    /**
     * The application-message entry point — attach via
     * {@code runtime.onApplicationMessage(service::onMessage)}.
     */
    public void onMessage(PeerAddress from, NoderaMessage message) {
        switch (message) {
            case ActionBatchMsg m -> onBatch(from, m.batch());
            case ValidationVote v -> onVote(v);
            case CommitAnnounce c -> onCommitAnnounce(c);
            default -> { /* not a validation message */ }
        }
    }

    /** Validator-side: re-execute the primary's batch on the local replica and vote. */
    private void onBatch(PeerAddress from, ActionBatch batch) {
        Replica replica = replicas.get(batch.region());
        if (replica == null) {
            return; // not a replica of this region
        }
        RegionExecutionRequest request = requestFor(replica, batch);
        MemberBallot ballot = member.computeAndVote(request);
        replica.pendingBallot = ballot;
        votesCast.incrementAndGet();
        transport.send(from, MessageCodec.encode(
                new ValidationVote(batch.region(), batch.epoch(), batch.baseVersion(),
                        ballot.vote())));
    }

    /** Primary-side: fold a validator's vote into the active round. */
    private void onVote(ValidationVote vote) {
        Round round = activeRound.get();
        if (round == null) {
            return; // stale vote after a completed round
        }
        votesReceived.incrementAndGet();
        round.collector().submit(vote.vote());
        Decision decision = round.collector().decide();
        if (!(decision instanceof Decision.Unresolved)) {
            round.decision().set(decision);
            round.done().countDown();
        }
    }

    /** Validator-side: the committee committed — apply the pending delta and persist the cert. */
    private void onCommitAnnounce(CommitAnnounce announce) {
        Replica replica = replicas.get(announce.region());
        if (replica == null || replica.pendingBallot == null) {
            return;
        }
        QuorumCertificate cert =
                QuorumCertificate.decode(new CanonicalReader(announce.certificateBytes()));
        if (!cert.resultingRoot().equals(replica.pendingBallot.root())) {
            // This member disagreed with the committed root; it must resync, not apply blindly.
            replica.pendingBallot = null;
            return;
        }
        commitLocally(replica, cert, replica.snapshot.tick() + 1);
    }

    /** Apply the pending delta, advance the replica, persist the certificate. */
    private synchronized void commitLocally(Replica replica, QuorumCertificate cert, long tick) {
        MemberBallot ballot = replica.pendingBallot;
        replica.pendingBallot = null;
        WorldMutationApplier.ApplyResult applied = replica.applier.apply(ballot.delta());
        if (!applied.committed()) {
            throw new IllegalStateException("certified delta failed to apply for "
                    + replica.snapshot.region() + " at " + applied.failedAt());
        }
        SnapshotVersion next = replica.snapshot.version().next();
        replica.snapshot = replica.world.reExtract(replica.snapshot.region(), next, tick);
        replica.headRoot = cert.resultingRoot();
        replica.lastCertificate = cert;
        certificates.put(cert);
        committeeCommits.incrementAndGet();
    }

    /**
     * Route one action; committee-lane actions are counted, fallback-lane actions are executed
     * immediately against {@code base} through the server lane ({@link FallbackExecutor}).
     */
    public RoutingDecision routeAndMaybeFallback(ActionEnvelope env,
                                                 CrossRegionRouter.RegionStatus status,
                                                 RegionSnapshot base) {
        RoutingDecision decision = router.route(env, status);
        if (decision.isFallback()) {
            InMemoryWorldView world = new InMemoryWorldView();
            world.load(base);
            FallbackExecutor executor = new FallbackExecutor(engine, new WorldMutationApplier(world));
            ActionBatch batch = new ActionBatch(env.region(), replicaEpochOrInitial(env.region()),
                    base.version(), env.targetTick(), env.targetTick(), List.of(env));
            FallbackExecutor.FallbackResult result =
                    executor.execute(new RegionExecutionRequest(contextFor(batch), base, batch));
            if (result.committed()) {
                fallbackCommits.incrementAndGet();
            }
        }
        return decision;
    }

    /** Promote a surviving validator after primary loss (epoch + 1) and adopt the new lease. */
    public RegionLease failover(RegionId region, LeaseManager leases, long nowTick) {
        Replica replica = replicas.get(region);
        if (replica == null) {
            return null;
        }
        RegionLease promoted = CommitteeFailover.promoteOnPrimaryLoss(replica.lease, leases, nowTick);
        if (promoted != null) {
            replicas.put(region, adopt(replica, promoted));
        }
        return promoted;
    }

    private Replica adopt(Replica old, RegionLease lease) {
        Replica next = new Replica(old.snapshot, lease);
        return next;
    }

    /** The router's Phase-4 soak metrics (committee-commit ratio). */
    public SoakMetrics soakMetrics() {
        return router.metrics();
    }

    /** Live counters for the worker STATE telemetry. */
    public Snapshot snapshot() {
        return new Snapshot(replicas.size(), proposalsSent.get(), votesCast.get(),
                votesReceived.get(), committeeCommits.get(), fallbackCommits.get());
    }

    /** Immutable counter snapshot. */
    public record Snapshot(int activeRegions, long proposalsSent, long votesCast,
                           long votesReceived, long committeeCommits, long fallbackCommits) {
    }

    private dev.nodera.core.region.RegionEpoch replicaEpochOrInitial(RegionId region) {
        Replica r = replicas.get(region);
        return r == null ? dev.nodera.core.region.RegionEpoch.INITIAL : r.lease.epoch();
    }

    private RegionExecutionRequest requestFor(Replica replica, ActionBatch batch) {
        return new RegionExecutionRequest(contextFor(batch), replica.snapshot, batch);
    }

    private RegionExecutionContext contextFor(ActionBatch batch) {
        return new RegionExecutionContext(batch.region(), batch.epoch(), batch.baseVersion(),
                batch.tickFrom(), batch.tickTo(), worldSeed, rulesVersion, registryFingerprint);
    }
}
