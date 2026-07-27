package dev.nodera.peer.validation;

import dev.nodera.committee.CommitteeFailover;
import dev.nodera.committee.CommitteeMember;
import dev.nodera.consensus.Decision;
import dev.nodera.consensus.EquivocationDetector;
import dev.nodera.consensus.MajorityQuorumPolicy;
import dev.nodera.consensus.ProposalKey;
import dev.nodera.consensus.VoteCollector;
import dev.nodera.coordinator.InMemoryWorldView;
import dev.nodera.coordinator.LagHandoffPolicy;
import dev.nodera.coordinator.LeaseManager;
import dev.nodera.coordinator.MutableWorldView;
import dev.nodera.coordinator.RegionPipeline;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.coordinator.entity.EntityTransferCoordinator;
import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.DropItemAction;
import dev.nodera.core.action.PickupItemAction;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.EntityTransferCertificate;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.consensuscert.ServerAuthorityCertificate;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.crypto.StableHash;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.event.EntityTransferAcceptedEvent;
import dev.nodera.core.event.EntityTransferCommittedEvent;
import dev.nodera.core.event.EntityTransferPreparedEvent;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.EntityMutation;
import dev.nodera.core.state.EntityTransferDescriptor;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.committee.MemberBallot;
import dev.nodera.committee.VotePersistence;
import dev.nodera.fallback.FallbackExecutor;
import dev.nodera.fallback.FallbackRouter;
import dev.nodera.fallback.CrossRegionRouter;
import dev.nodera.fallback.RoutingDecision;
import dev.nodera.fallback.SoakMetrics;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.simulationmsg.ActionBatchMsg;
import dev.nodera.protocol.simulationmsg.CommitAnnounce;
import dev.nodera.protocol.simulationmsg.EntityTransferAccept;
import dev.nodera.protocol.simulationmsg.EntityTransferCommit;
import dev.nodera.protocol.simulationmsg.EntityTransferPrepare;
import dev.nodera.protocol.simulationmsg.ExternalDelta;
import dev.nodera.protocol.simulationmsg.RegionProposal;
import dev.nodera.protocol.simulationmsg.RegionRefusal;
import dev.nodera.protocol.simulationmsg.ValidationVote;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.shadow.SnapshotDeltaApplier;
import dev.nodera.storage.CertificateStore;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
 *       persists the certificate, applies through {@link WorldMutationApplier}, and advances its
 *       replica — certified region state flowing peer-to-peer.</li>
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
        final RegionPipeline pipeline;
        // snapshot/headRoot/lastCertificate are read by observer threads (control probes, test
        // pollers) while the worldExecutor/proposer threads write them — volatile so an observer
        // that sees the new head also sees the certificate committed before it.
        volatile RegionSnapshot snapshot;
        volatile StateRoot headRoot;
        MemberBallot pendingBallot;
        ActionBatch pendingBatch;
        RegionProposal pendingProposal;
        long pendingTickTo;
        volatile QuorumCertificate lastCertificate;

        Replica(RegionSnapshot base, RegionLease lease) {
            this.lease = lease;
            this.snapshot = base;
            this.headRoot = StateRoot.of(hashes.hash(base));
            this.pipeline = new RegionPipeline(base.region());
            pipeline.assign(lease.epoch());
            pipeline.snapshotSynced(base.version());
            if (world instanceof InMemoryWorldView inMemory) {
                inMemory.load(base);
            } else if (!world.isRegionLoaded(base.region()) || !applier.matchesSnapshot(base)) {
                throw new IllegalStateException(
                        "live world does not match activated snapshot for " + base.region());
            }
        }
    }

    /**
     * One in-flight primary-side vote round. {@code ownRoot} is what THIS node computed for the
     * batch — the thing an incoming vote is compared against to detect divergence (issue #5).
     */
    private record Round(VoteCollector collector, RegionLease lease, StateRoot batchRoot,
                         StateRoot ownRoot,
                         CountDownLatch done,
                          AtomicReference<Decision> decision,
                         java.util.Map<NodeId, StateRoot> votes) {
        Round(VoteCollector collector, RegionLease lease, StateRoot batchRoot, StateRoot ownRoot,
              CountDownLatch done, AtomicReference<Decision> decision) {
            this(collector, lease, batchRoot, ownRoot, done, decision,
                    new java.util.concurrent.ConcurrentHashMap<>());
        }
    }

    /** One in-flight dual-committee transfer-accept round. */
    private static final class TransferRound {
        private final EntityTransferDescriptor descriptor;
        private final RegionDelta sourceDelta;
        private final RegionDelta targetDelta;
        private final RegionLease sourceLease;
        private final RegionLease targetLease;
        private final Map<NodeId, SignedVote> sourceVotes = new ConcurrentHashMap<>();
        private final Map<NodeId, SignedVote> targetVotes = new ConcurrentHashMap<>();
        private final CountDownLatch done = new CountDownLatch(1);

        private TransferRound(
                EntityTransferDescriptor descriptor,
                RegionDelta sourceDelta,
                RegionDelta targetDelta,
                RegionLease sourceLease,
                RegionLease targetLease) {
            this.descriptor = descriptor;
            this.sourceDelta = sourceDelta;
            this.targetDelta = targetDelta;
            this.sourceLease = sourceLease;
            this.targetLease = targetLease;
        }
    }

    private final NodeIdentity identity;
    private final PeerTransport transport;
    private final CommitteeMember member;
    private final RegionEngine engine;
    private final HashService hashes;
    private final SignatureService signatures = new SignatureService();
    private final CertificateStore certificates;
    private final MutableWorldView world;
    private final WorldMutationApplier applier;
    private final EntityTransferCoordinator.TransferJournal transferJournal;
    private final EntityTransferCoordinator transferCoordinator;
    private final ActionReservationPersistence actionPersistence;
    private final Consumer<Runnable> worldExecutor;
    // Not final: a worker boots with a placeholder seed (it has no world yet) and is bound to the
    // real one when a world meshes it. The seed feeds DeterministicRandom, so a validator running
    // the wrong seed re-executes to a different root and votes against every batch it is sent —
    // a committee that can never reach quorum, with no error anywhere to explain it.
    private volatile long worldSeed;
    private final int rulesVersion;
    private final long registryFingerprint;
    private final long voteTimeoutMillis;
    private volatile ExternalCommitListener externalCommitListener;

    private final Map<RegionId, Replica> replicas = new ConcurrentHashMap<>();
    private final Map<RegionId, RegionLease> knownLeases = new ConcurrentHashMap<>();
    private final Map<NodeId, PeerAddress> peers = new ConcurrentHashMap<>();
    private final Map<NodeId, Bytes> peerKeys = new ConcurrentHashMap<>();
    private final Map<NodeId, java.util.Set<Bytes>> actorKeys = new ConcurrentHashMap<>();
    private final Map<StateRoot, ActionBatch> reservedBatches = new java.util.HashMap<>();
    private final Map<NodeId, Long> highestReservedPlayerSequence = new java.util.HashMap<>();
    private long highestReservedServerSequence = -1L;
    private final AtomicReference<Round> activeRound = new AtomicReference<>();
    private final AtomicReference<TransferRound> activeTransferRound = new AtomicReference<>();
    private final Map<Long, EntityTransferDescriptor> acceptedTransfers = new ConcurrentHashMap<>();
    private final ActionAdmission actionAdmission;

    private final FallbackRouter router = new FallbackRouter();
    private final AtomicLong proposalsSent = new AtomicLong();
    private final AtomicLong votesCast = new AtomicLong();
    private final AtomicLong votesReceived = new AtomicLong();
    private final AtomicLong committeeCommits = new AtomicLong();
    private final AtomicLong fallbackCommits = new AtomicLong();

    /**
     * Re-executions that did not agree (issue #5 — the Phase-1 exit gate's measurement).
     *
     * <p>Counted from both chairs: a validator whose own root differs from the primary's proposal,
     * and a primary receiving a vote whose resulting root differs from its own. Zero over hours of
     * multi-client play is the gate; a non-zero value is the divergence hunt starting.
     */
    private final AtomicLong divergences = new AtomicLong();

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaWorker");

    /**
     * Per-peer event-relay accounting (live TPS investigation, 2026-07-24): who captures, who
     * forwards to whom, who does whose proposal work, and how long each takes. Read by the mod's
     * {@code /nodera debug relay} + verbose console stream.
     */
    private final dev.nodera.diagnostics.metric.RelayMetrics relayMetrics =
            new dev.nodera.diagnostics.metric.RelayMetrics();

    /** @return the lane's per-peer relay metrics (never null). */
    public dev.nodera.diagnostics.metric.RelayMetrics relayMetrics() {
        return relayMetrics;
    }

    /** Live-server policy seam for reach, inventory ownership, and other actor-specific checks. */
    @FunctionalInterface
    public interface ActionAdmission {
        boolean authorize(ActionEnvelope action, RegionSnapshot base);

        /** Headless default: block and break actions are safe; inventory/entity actions need live proof. */
        ActionAdmission HEADLESS = (action, base) ->
                !(action.action() instanceof DropItemAction)
                        && !(action.action() instanceof PickupItemAction);
    }

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
        this(identity, transport, engine, hashes, certificates, worldSeed, rulesVersion,
                registryFingerprint, voteTimeoutMillis, VotePersistence.none(),
                ActionAdmission.HEADLESS, new InMemoryWorldView(),
                EntityTransferCoordinator.TransferJournal.NOOP);
    }

    /** Crash-safe/action-aware constructor used by live worker wiring. */
    public WorkerValidationService(NodeIdentity identity, PeerTransport transport,
                                   RegionEngine engine, HashService hashes,
                                   CertificateStore certificates, long worldSeed,
                                   int rulesVersion, long registryFingerprint,
                                   long voteTimeoutMillis, VotePersistence persistence,
                                   ActionAdmission actionAdmission) {
        this(identity, transport, engine, hashes, certificates, worldSeed, rulesVersion,
                registryFingerprint, voteTimeoutMillis, persistence, actionAdmission,
                new InMemoryWorldView(), EntityTransferCoordinator.TransferJournal.NOOP);
    }

    /** Full live constructor with a server-backed canonical world and durable transfer journal. */
    public WorkerValidationService(NodeIdentity identity, PeerTransport transport,
                                   RegionEngine engine, HashService hashes,
                                   CertificateStore certificates, long worldSeed,
                                   int rulesVersion, long registryFingerprint,
                                   long voteTimeoutMillis, VotePersistence persistence,
                                   ActionAdmission actionAdmission, MutableWorldView world,
                                   EntityTransferCoordinator.TransferJournal transferJournal) {
        this(identity, transport, engine, hashes, certificates, worldSeed, rulesVersion,
                registryFingerprint, voteTimeoutMillis, persistence, actionAdmission, world,
                transferJournal, ActionReservationPersistence.none());
    }

    /** Full crash-safe constructor including durable action sequence reservations. */
    public WorkerValidationService(NodeIdentity identity, PeerTransport transport,
                                   RegionEngine engine, HashService hashes,
                                   CertificateStore certificates, long worldSeed,
                                   int rulesVersion, long registryFingerprint,
                                   long voteTimeoutMillis, VotePersistence persistence,
                                   ActionAdmission actionAdmission, MutableWorldView world,
                                   EntityTransferCoordinator.TransferJournal transferJournal,
                                   ActionReservationPersistence actionPersistence) {
        this(identity, transport, engine, hashes, certificates, worldSeed, rulesVersion,
                registryFingerprint, voteTimeoutMillis, persistence, actionAdmission, world,
                transferJournal, actionPersistence, Runnable::run);
    }

    /** Full live constructor with server-main dispatch for world-mutating inbound commits. */
    public WorkerValidationService(NodeIdentity identity, PeerTransport transport,
                                   RegionEngine engine, HashService hashes,
                                   CertificateStore certificates, long worldSeed,
                                   int rulesVersion, long registryFingerprint,
                                   long voteTimeoutMillis, VotePersistence persistence,
                                   ActionAdmission actionAdmission, MutableWorldView world,
                                   EntityTransferCoordinator.TransferJournal transferJournal,
                                   ActionReservationPersistence actionPersistence,
                                   Consumer<Runnable> worldExecutor) {
        if (identity == null || transport == null || engine == null || hashes == null
                || certificates == null || persistence == null || actionAdmission == null
                || world == null || transferJournal == null || actionPersistence == null
                || worldExecutor == null) {
            throw new IllegalArgumentException("no argument may be null");
        }
        this.identity = identity;
        this.transport = transport;
        this.engine = engine;
        this.hashes = hashes;
        this.certificates = certificates;
        this.world = world;
        this.applier = new WorldMutationApplier(world);
        this.transferJournal = transferJournal;
        this.actionPersistence = actionPersistence;
        this.worldExecutor = worldExecutor;
        this.member = new CommitteeMember(identity, engine, persistence);
        this.worldSeed = worldSeed;
        this.rulesVersion = rulesVersion;
        this.registryFingerprint = registryFingerprint;
        this.voteTimeoutMillis = voteTimeoutMillis;
        this.actionAdmission = actionAdmission;
        this.transferCoordinator = new EntityTransferCoordinator(
                applier, new NetworkTransferApprovals(), transferJournal);
        restoreActionWatermarks(actionPersistence.retained());
    }

    /** @return the region ids with an active replica on this node (diagnostics/ownership HUD). */
    public List<dev.nodera.core.region.RegionId> activeRegionIds() {
        return List.copyOf(replicas.keySet());
    }

    /**
     * Bind this worker's validation lane to the world it has been meshed into, so its re-execution
     * uses the same {@code worldSeed} — and therefore the same {@link
     * dev.nodera.simulation.DeterministicRandom} stream — as the region primaries it votes with.
     *
     * <p>Refused once replicas are live: changing the seed under an active committee would silently
     * fork this node's execution from the rest of the committee mid-round.
     *
     * @param seed the hosted world's seed.
     * @return {@code true} if the lane was (re)bound, {@code false} if replicas are already active.
     * @Thread-context any thread.
     */
    public boolean bindWorld(long seed) {
        if (worldSeed == seed) {
            return true;
        }
        if (!replicas.isEmpty()) {
            return false;
        }
        worldSeed = seed;
        return true;
    }

    /** @return the world seed this lane re-executes against. */
    public long worldSeed() {
        return worldSeed;
    }

    /** Register a committee peer's authenticated transport address and Ed25519 public key. */
    public void registerPeer(NodeId id, PeerAddress address, Bytes publicKey) {
        if (id == null || address == null || publicKey == null || !id.equals(address.nodeId())) {
            throw new IllegalArgumentException("peer id, address, and public key must agree");
        }
        peers.put(id, address);
        peerKeys.put(id, publicKey);
    }

    /**
     * Register an actor key after session authentication; unregistered actors cannot submit work.
     * Additive (L-50 per-joiner identities): an actor may carry several admissible signer keys —
     * its own member node's key plus, while the vanilla-session capture point remains, the
     * session's interim signer. A signature verifying against ANY registered key admits the
     * action; the capture-point migration (T16) then simply stops registering the interim key.
     */
    public void registerActor(NodeId actor, Bytes publicKey) {
        if (actor == null || publicKey == null) {
            throw new IllegalArgumentException("actor and publicKey must not be null");
        }
        actorKeys.computeIfAbsent(actor, ignored -> ConcurrentHashMap.newKeySet()).add(publicKey);
    }

    private boolean actorSignatureValid(ActionEnvelope action) {
        java.util.Set<Bytes> keys = actorKeys.get(action.actor());
        if (keys == null || keys.isEmpty()) {
            return false;
        }
        for (Bytes key : keys) {
            if (signatures.verify(key, action.signedPortion(), action.signature())) {
                return true;
            }
        }
        return false;
    }

    /** Activate a region replica on this worker under {@code lease}. */
    public void activateRegion(RegionSnapshot base, RegionLease lease) {
        if (refused.contains(base.region())) {
            // A refusal outlives the moment it was observed: re-activating a region that a peer
            // (or this node) has already established cannot be validated would restart exactly the
            // work the refusal exists to stop. (This class carries no logger by design — the
            // observing node logs the refusal where a player can see it.)
            return;
        }
        registerLease(lease);
        replicas.put(base.region(), new Replica(base, lease));
    }

    /** Resume durable transfer stages after leases, peer keys, and region snapshots are restored. */
    public synchronized void recoverTransfers(
            List<EntityTransferCoordinator.TransferRecovery> recoverable,
            List<EntityTransferCoordinator.TransferResult> completed) {
        if (recoverable == null || completed == null) {
            throw new IllegalArgumentException("transfer recovery lists must not be null");
        }
        for (EntityTransferCoordinator.TransferResult result : completed) {
            transferCoordinator.restoreCompleted(result);
        }
        for (EntityTransferCoordinator.TransferRecovery recovery : recoverable) {
            EntityTransferDescriptor descriptor = recovery.plan().descriptor();
            Replica source = replicas.get(descriptor.sourceRegion());
            Replica target = replicas.get(descriptor.targetRegion());
            if (source == null || target == null) {
                throw new IllegalStateException(
                        "transfer recovery requires both region replicas: " + descriptor.transferId());
            }
            boolean atBase = source.snapshot.version().equals(descriptor.sourceBaseVersion())
                    && target.snapshot.version().equals(descriptor.targetBaseVersion());
            boolean atResult = source.snapshot.version().equals(descriptor.sourceResultingVersion())
                    && target.snapshot.version().equals(descriptor.targetResultingVersion())
                    && source.headRoot.equals(descriptor.sourceResultingRoot())
                    && target.headRoot.equals(descriptor.targetResultingRoot());
            EntityTransferCoordinator.TransferResult result;
            if (atBase) {
                EntityTransferCoordinator.TransferOutcome outcome = transferCoordinator.restorePending(
                        recovery, source.pipeline, target.pipeline);
                if (!(outcome instanceof EntityTransferCoordinator.TransferResult restored)) {
                    throw new IllegalStateException(
                            "durable transfer recovery aborted: " + descriptor.transferId());
                }
                result = restored;
            } else if (atResult && recovery.certificate() != null) {
                if (recovery.stage() == EntityTransferCoordinator.TransferStage.ACCEPTED) {
                    transferJournal.applied(recovery.plan(), recovery.certificate());
                }
                transferJournal.committed(recovery.plan(), recovery.certificate());
                result = recoveredResult(recovery);
                transferCoordinator.restoreCompleted(result);
            } else {
                throw new IllegalStateException(
                        "transfer recovery snapshots are neither base nor result: "
                                + descriptor.transferId());
            }
            source.snapshot = world.reExtract(
                    descriptor.sourceRegion(), descriptor.sourceResultingVersion(), descriptor.tick());
            target.snapshot = world.reExtract(
                    descriptor.targetRegion(), descriptor.targetResultingVersion(), descriptor.tick());
            if (!StateRoot.of(hashes.hash(source.snapshot)).equals(descriptor.sourceResultingRoot())
                    || !StateRoot.of(hashes.hash(target.snapshot))
                    .equals(descriptor.targetResultingRoot())) {
                throw new IllegalStateException(
                        "recovered transfer world roots do not match certificate");
            }
            source.headRoot = descriptor.sourceResultingRoot();
            target.headRoot = descriptor.targetResultingRoot();
            if (!result.certificate().descriptor().equals(descriptor)) {
                throw new IllegalStateException("recovered transfer certificate changed descriptor");
            }
        }
    }

    private static EntityTransferCoordinator.TransferResult recoveredResult(
            EntityTransferCoordinator.TransferRecovery recovery) {
        EntityTransferCoordinator.TransferPlan plan = recovery.plan();
        return new EntityTransferCoordinator.TransferResult(
                plan.descriptor().transferId(), true, plan.sourceDelta(), plan.targetDelta(),
                recovery.certificate(), plan.sourcePrepared(), plan.targetPrepared(),
                plan.sourceAccepted(), plan.targetAccepted(), plan.sourceCommitted(),
                plan.targetCommitted(), WorldMutationApplier.ApplyResult.committedReplay(),
                plan.descriptor().tick());
    }

    /** Register current committee assignment even when this worker does not hold its replica. */
    public void registerLease(RegionLease lease) {
        if (lease == null) {
            throw new IllegalArgumentException("lease must not be null");
        }
        knownLeases.compute(lease.region(), (region, current) -> {
            if (current != null && lease.epoch().value() < current.epoch().value()) {
                throw new IllegalArgumentException("cannot register a stale region lease");
            }
            return lease;
        });
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

    /** Current coordinator pipeline state, or IDLE when this worker has no replica. */
    public dev.nodera.coordinator.PipelineState pipelineState(RegionId region) {
        Replica replica = replicas.get(region);
        return replica == null
                ? dev.nodera.coordinator.PipelineState.IDLE : replica.pipeline.state();
    }

    /** Gracefully revoke one entity-blocked region from further committee work. */
    public void revokeRegion(RegionId region) {
        Replica replica = replicas.get(region);
        if (replica != null) {
            replica.pipeline.revoke();
        }
    }

    /** Regions refused outright: no replica of these is activated again this session (L-60). */
    private final java.util.Set<RegionId> refused =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Refuse a region for a reason that holds on <b>every</b> node, and tell the mesh (L-60).
     *
     * <p>Revoking is not enough when the observing node holds no replica — which is the normal case
     * under field-of-view ownership, where entities spawn on a session server that owns nothing. So
     * this both revokes whatever this node has and announces the refusal to every peer it knows,
     * because the node that can see the disqualifying condition and the nodes holding the region are
     * usually different machines.
     *
     * <p>The announcement carries no authority. Acting on it can only <i>stop</i> validation of one
     * region, never commit anything, so a lying peer costs a region its validated lane and nothing
     * else — the same thing that peer could achieve by simply not participating.
     *
     * @return {@code true} if this was the first refusal of the region (callers log once).
     */
    public boolean refuseRegion(RegionId region, RegionRefusal.Reason reason) {
        java.util.Objects.requireNonNull(region, "region");
        java.util.Objects.requireNonNull(reason, "reason");
        boolean first = refused.add(region);
        revokeRegion(region);
        replicas.remove(region);
        if (first) {
            RegionRefusal announcement = new RegionRefusal(region, reason);
            for (PeerAddress address : peers.values()) {
                transport.send(address, MessageCodec.encode(announcement));
            }
        }
        return first;
    }

    /** @return whether {@code region} has been refused and must not be activated again. */
    public boolean isRefused(RegionId region) {
        return refused.contains(region);
    }

    private void onRegionRefusal(PeerAddress from, RegionRefusal refusal) {
        if (from == null) {
            return;
        }
        if (refusal.reason() == RegionRefusal.Reason.UNKNOWN) {
            // A peer newer than this build refused the region for a cause this build cannot name.
            // A refusal is advisory — the rule is that the recipient re-checks the condition
            // against its own config before revoking — and a condition it cannot name is one it
            // cannot re-check, so declining is the only correct response. Logged, not silent: a
            // node revoking nothing because it is out of date should say so.
            LOG.info("ignoring a refusal of {} from {}: the reason is newer than this build",
                    refusal.region(), from);
            return;
        }
        if (!refused.add(refusal.region())) {
            return;
        }
        // Say it out loud. A refusal that arrives from a peer used to be absorbed in silence: the
        // region was revoked here and NOTHING recorded that it had been, so on a node that learned
        // second the only trace was a region quietly no longer being validated. Worse, the local
        // path logs only on the FIRST refusal, so once a peer's announcement had landed, the
        // node's own later revocation of the same region printed nothing either — which is exactly
        // what a live mob drive sees as "the lane said nothing".
        LOG.info("entity lane revoked {} — {} (refusal received from {})",
                refusal.region(), refusal.reason(), from);
        revokeRegion(refusal.region());
        replicas.remove(refusal.region());
    }

    /** @return the most recently committed quorum certificate for an activated region. */
    public Optional<QuorumCertificate> latestCertificate(RegionId region) {
        Replica r = replicas.get(region);
        return r == null ? Optional.empty() : Optional.ofNullable(r.lastCertificate);
    }

    // L-16: every committed snapshot streams to one observer (the client's LocalReplicaView) so
    // the prediction overlay reconciles against consensus truth the moment it lands.
    private volatile java.util.function.BiConsumer<RegionSnapshot, StateRoot> commitObserver;

    /** Register the single commit observer (predict/rollback view); replaces any previous one. */
    public void onCommit(java.util.function.BiConsumer<RegionSnapshot, StateRoot> observer) {
        this.commitObserver = observer;
    }

    private void notifyCommit(RegionSnapshot committed, StateRoot root) {
        var observer = commitObserver;
        if (observer != null) {
            observer.accept(committed, root);
        }
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
        // Relay accounting: a batch proposed off the forward executor is ANOTHER player's work
        // (counted at recordForwardProcessed); anything else is this node's own capture entering
        // the lane as the primary (the self-proposed path — forwarded captures were counted at
        // forwardToPrimary). Actor NodeIds are player-derived, not mesh ids, so the executing
        // thread is the reliable discriminator here.
        if (!"nodera-forward-propose".equals(Thread.currentThread().getName())) {
            relayMetrics.recordLocalSubmitted();
            relayMetrics.recordLocalProposed();
        }
        ActionBatch batch = new ActionBatch(region, lease.epoch(), replica.snapshot.version(),
                tickFrom, tickTo, actions);
        MemberBallot ownBallot = replica.pendingBallot;
        if (ownBallot != null && !batch.equals(replica.pendingBatch)) {
            throw new IllegalStateException("region already has a signed ballot for this version");
        }
        if (!validBatch(replica, batch)) {
            throw new IllegalArgumentException("batch contains unauthenticated or inadmissible actions");
        }
        RegionExecutionRequest request = requestFor(replica, batch);

        if (ownBallot == null) {
            ownBallot = member.computeAndVote(request);
            replica.pendingBallot = ownBallot;
            replica.pendingBatch = batch;
        }
        votesCast.incrementAndGet();

        ProposalKey key = new ProposalKey(region, lease.epoch(), replica.snapshot.version());
        StateRoot batchRoot = StateRoot.of(hashes.hash(batch));
        // Quorum is a strict majority of the LEASE's committee (primary + validators), not a fixed
        // 2-of-3: a decentralized FOV plan produces committees of 1 (solo host), 2 (one joiner in
        // view), or 3+ — a hardcoded mvp() profile made any committee smaller than 3 time out and
        // revoke on every batch.
        VoteCollector collector = new VoteCollector(key,
                MajorityQuorumPolicy.sizedTo(1 + lease.validators().size()),
                replica.headRoot, voteTimeoutMillis);
        collector.submit(ownBallot.vote());
        Round round = new Round(
                collector, lease, batchRoot, ownBallot.root(),
                new CountDownLatch(1), new AtomicReference<>());
        activeRound.set(round);

        CanonicalWriter deltaW = new CanonicalWriter();
        ownBallot.delta().encode(deltaW);
        RegionProposal unsignedProposal = new RegionProposal(region, lease.epoch(),
                replica.snapshot.version(), tickFrom, tickTo, replica.headRoot,
                ownBallot.root(), deltaW.toBytes(), batchRoot, Bytes.empty());
        RegionProposal proposal = new RegionProposal(region, lease.epoch(),
                replica.snapshot.version(), tickFrom, tickTo, replica.headRoot,
                ownBallot.root(), deltaW.toBytes(), batchRoot,
                identity.sign(unsignedProposal.signedPortion()));
        for (NodeId validator : lease.validators()) {
            PeerAddress addr = peers.get(validator);
            if (addr != null) {
                try {
                    transport.send(addr, MessageCodec.encode(proposal));
                    transport.send(addr, MessageCodec.encode(new ActionBatchMsg(batch)));
                } catch (dev.nodera.transport.TransportException unreachable) {
                    // A dead validator is an absent vote, not a failed proposal: quorum is a
                    // strict majority of the committee, so the round proceeds with whoever is
                    // reachable and times out honestly if a majority is not (Task 8 acceptance:
                    // kill 2/3 falls back within the lease — not with an exception).
                }
            }
        }
        proposalsSent.incrementAndGet();

        try {
            round.done().await(voteTimeoutMillis + 500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abortUncommitted(replica, batch);
            return Optional.empty();
        } finally {
            activeRound.set(null);
        }
        Decision decision = round.decision().get();
        if (decision == null) {
            decision = collector.decide();
        }
        if (!(decision instanceof Decision.Commit commit)) {
            abortUncommitted(replica, batch);
            return Optional.empty();
        }
        QuorumCertificate cert = commit.certificate();
        if (!certificateMatches(replica, cert, replica.pendingBallot)) {
            abortUncommitted(replica, batch);
            return Optional.empty();
        }
        // Reputation is written HERE, where the committed root is finally known — the ledger has
        // existed since Task 6 with nothing ever writing to it. Our own vote counts too: a primary
        // whose root lost is the node that was wrong.
        java.util.Map<NodeId, StateRoot> roundVotes =
                new java.util.LinkedHashMap<>(round.votes());
        roundVotes.put(identity.nodeId(), round.ownRoot());
        for (dev.nodera.coordinator.CommitteeScoring.Outcome outcome
                : dev.nodera.coordinator.CommitteeScoring.apply(
                        reliability, cert.resultingRoot(), roundVotes)) {
            if (!outcome.agreed()) {
                LOG.info("reliability: {} disagreed with the committed root in {} (score now {})",
                        outcome.node(), region, String.format(java.util.Locale.ROOT, "%.4f",
                                reliability.score(outcome.node())));
            }
        }
        if (!replica.pendingBallot.delta().transferIntents().isEmpty()) {
            return commitTransfer(replica, cert, tickTo);
        }
        commitLocally(replica, cert, tickTo);
        // Certified state flows peer-to-peer: every member gets the co-signed certificate.
        CanonicalWriter certW = new CanonicalWriter();
        cert.encode(certW);
        CommitAnnounce announce = new CommitAnnounce(region, cert.version().next(),
                cert.resultingRoot(), certW.toBytes());
        for (NodeId validator : lease.validators()) {
            PeerAddress addr = peers.get(validator);
            if (addr != null) {
                try {
                    transport.send(addr, MessageCodec.encode(announce));
                } catch (dev.nodera.transport.TransportException unreachable) {
                    // The certificate is already durable; an unreachable member resyncs later.
                }
            }
        }
        return Optional.of(cert.resultingRoot());
    }

    /**
     * The application-message entry point — attach via
     * {@code runtime.onApplicationMessage(service::onMessage)}.
     */
    public void onMessage(PeerAddress from, NoderaMessage message) {
        if (message instanceof dev.nodera.protocol.assignment.RegionAssigned m) { onRegionAssigned(m);
        } else if (message instanceof ActionBatchMsg m) { onBatch(from, m.batch());
        } else if (message instanceof dev.nodera.protocol.simulationmsg.ActionForward f) { onActionForward(f);
        } else if (message instanceof RegionProposal p) { onProposal(from, p);
        } else if (message instanceof ValidationVote v) { onVote(from, v);
        } else if (message instanceof CommitAnnounce c) { worldExecutor.accept(() -> onCommitAnnounce(from, c));
        } else if (message instanceof EntityTransferPrepare p) { onTransferPrepare(from, p);
        } else if (message instanceof EntityTransferAccept a) { onTransferAccept(from, a);
        } else if (message instanceof EntityTransferCommit c) { worldExecutor.accept(() -> onTransferCommit(from, c));
        } else if (message instanceof ExternalDelta e) { worldExecutor.accept(() -> onExternalDelta(from, e));
        } else if (message instanceof RegionRefusal r) { onRegionRefusal(from, r);
        } else { /* not a validation message */ }
    }

    /**
     * Serializes forwarded-action proposals: {@code proposeBatch} blocks awaiting the committee's
     * votes and holds the single active-round slot, so forwarded submissions must run off the
     * runtime state thread, one at a time.
     */
    private final java.util.concurrent.ExecutorService forwardProposals =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "nodera-forward-propose");
                t.setDaemon(true);
                return t;
            });

    /**
     * Primary-side receipt of a forwarded action (the no-host submission path): any member may
     * capture an action, but only the region's primary — a player's node like every other —
     * proposes it. The envelope's actor signature and admission are re-verified inside
     * {@code proposeBatch}'s batch validation; the forwarder is a courier, not an authority.
     */
    private void onActionForward(dev.nodera.protocol.simulationmsg.ActionForward forward) {
        Replica replica = replicas.get(forward.region());
        if (replica == null || !identity.nodeId().equals(replica.lease.primary())) {
            return; // not ours to propose
        }
        ActionEnvelope envelope;
        try {
            envelope = ActionEnvelope.decode(
                    new dev.nodera.core.crypto.CanonicalReader(forward.encodedEnvelope().toArray()));
        } catch (RuntimeException malformed) {
            return;
        }
        forwardProposals.execute(() -> {
            try {
                Replica current = replicas.get(forward.region());
                if (current == null) {
                    return;
                }
                // The envelope's targetTick was signed by the actor against the SENDER's replica
                // clock, which on a fresh store can be arbitrarily skewed from this primary's
                // (issue #33 / L-50: the clean-slate vanish — a [t+1, t+1] window that excludes
                // the signed tick silently rejected every forwarded action after the capturing
                // node had already suppressed the vanilla outcome). The signature cannot be
                // re-stamped, so bracket the batch window to include both the signed tick and
                // this replica's next tick; the resulting snapshot tick (tickTo) stays monotonic.
                long next = current.snapshot.tick() + 1;
                long tickFrom = Math.min(next, envelope.targetTick());
                long tickTo = Math.max(next, envelope.targetTick());
                long startedAt = System.nanoTime();
                proposeBatch(forward.region(), tickFrom, tickTo, List.of(envelope));
                relayMetrics.recordForwardProcessed(envelope.actor(), System.nanoTime() - startedAt);
            } catch (RuntimeException rejected) {
                // A rejected forward is not this node's failure to report; the committee decided.
            }
        });
    }

    /**
     * Route a locally-captured action to its region's primary over the transport (the no-host
     * submission path). No-op when this node <i>is</i> the primary or the primary has no
     * registered address.
     *
     * @param envelope the signed action.
     * @return {@code true} if the action was sent to a remote primary.
     * @Thread-context any thread.
     */
    public boolean forwardToPrimary(ActionEnvelope envelope) {
        Replica replica = replicas.get(envelope.region());
        if (replica == null) {
            return false;
        }
        return forwardTo(replica.lease.primary(), envelope);
    }

    /**
     * Forward a signed action to a named primary this node holds <b>no replica for</b> — the
     * observer path (minecraft L-80).
     *
     * <p>Under field-of-view ownership the node that SEES an event is routinely not a node that
     * holds the region: a dedicated server watches forty players edit regions that all belong to
     * their own peers. {@link #forwardToPrimary} cannot help there, because it starts from a local
     * replica's lease. This entry point starts from the ownership plan instead, which every node
     * that computes the plan already knows.
     *
     * @param primary  the region's primary, from the caller's ownership plan.
     * @param envelope the signed action.
     * @return {@code true} if the action was sent; {@code false} when the target is this node or
     *         has no registered address (an unaddressable peer is not a failure, just a no-op).
     * @Thread-context any thread.
     */
    public boolean forwardTo(NodeId primary, ActionEnvelope envelope) {
        if (primary == null || envelope == null) {
            return false;
        }
        if (identity.nodeId().equals(primary)) {
            return false;
        }
        PeerAddress address = peers.get(primary);
        if (address == null) {
            return false;
        }
        CanonicalWriter w = new CanonicalWriter();
        envelope.encode(w);
        transport.send(address, MessageCodec.encode(
                new dev.nodera.protocol.simulationmsg.ActionForward(envelope.region(), w.toBytes())));
        relayMetrics.recordLocalSubmitted();
        relayMetrics.recordForwardedTo(primary);
        // Issue #46.1: the moment a forwarded action goes unanswered is the live, observable
        // definition of "the primary of this region cannot keep up" — it is exactly what the
        // player at the region boundary is waiting on. Timed from the FIRST unanswered forward,
        // so a burst of actions does not reset the clock.
        oldestUnansweredForwardNanos.putIfAbsent(envelope.region(), System.nanoTime());
        return true;
    }

    /** First unanswered forwarded action per region (issue #46.1 lag signal); cleared on commit. */
    private final java.util.concurrent.ConcurrentMap<RegionId, Long> oldestUnansweredForwardNanos =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * How far this region's primary is behind, expressed as the age of the oldest forwarded action
     * it has not committed yet (issue #46.1).
     *
     * @param region   the region.
     * @param nowNanos the current monotonic time (supplied so the math is testable).
     * @return skew in {@link LagHandoffPolicy#TICK_BASIS_POINTS}; {@code 0} when nothing is pending.
     * @Thread-context any thread.
     */
    public long forwardLagTickBps(RegionId region, long nowNanos) {
        Long since = oldestUnansweredForwardNanos.get(region);
        if (since == null) {
            return 0L;
        }
        long elapsedMillis = Math.max(0L, (nowNanos - since) / 1_000_000L);
        return (elapsedMillis / MILLIS_PER_TICK) * LagHandoffPolicy.TICK_BASIS_POINTS;
    }

    /** Vanilla tick budget: the unit the lag signal is expressed in. */
    private static final long MILLIS_PER_TICK = 50L;

    /** Validator-side: re-execute the primary's batch on the local replica and vote. */
    private void onBatch(PeerAddress from, ActionBatch batch) {
        Replica replica = replicas.get(batch.region());
        if (replica == null || !isAuthenticatedMember(from, replica.lease.primary())) {
            return; // not a replica of this region
        }
        RegionProposal proposal = replica.pendingProposal;
        if (proposal == null
                || !proposal.region().equals(batch.region())
                || !proposal.epoch().equals(batch.epoch())
                || !proposal.baseVersion().equals(batch.baseVersion())
                || proposal.tickFrom() != batch.tickFrom()
                || proposal.tickTo() != batch.tickTo()) {
            return;
        }
        if (!validBatch(replica, batch)) {
            return;
        }
        if (!proposal.batchRoot().equals(StateRoot.of(hashes.hash(batch)))) {
            return;
        }
        RegionExecutionRequest request = requestFor(replica, batch);
        long reExecStartedAt = System.nanoTime();
        MemberBallot ballot = member.computeAndVote(request);
        // Relay accounting: this node just re-executed the PRIMARY's batch — another player's
        // events processed here (the "how much of my tick goes to validating whom" number).
        relayMetrics.recordProposalProcessed(replica.lease.primary(),
                System.nanoTime() - reExecStartedAt);
        CanonicalWriter deltaWriter = new CanonicalWriter();
        ballot.delta().encode(deltaWriter);
        if (!proposal.resultingRoot().equals(ballot.root())
                || !proposal.encodedDelta().equals(deltaWriter.toBytes())) {
            // THE Phase-1 signal (issue #5). Two nodes re-executed the same batch on the same base
            // and did not agree: this node simply declines to vote, which is correct — but it used
            // to do so in total silence, so the project's hard gate ("hours of play, zero
            // unexplained divergences") had nothing to read. Divergence is not an error the node
            // can fix; it is the measurement the soak exists to take, so it is counted and named.
            divergences.incrementAndGet();
            boolean rootsAgree = proposal.resultingRoot().equals(ballot.root());
            LOG.warn("DIVERGENCE in {} v{} from primary {} — {}: theirs {}, ours {}",
                    batch.region(), batch.baseVersion().value(), replica.lease.primary(),
                    rootsAgree ? "identical roots but different deltas" : "different state roots",
                    proposal.resultingRoot().hash().toShortHex(8),
                    ballot.root().hash().toShortHex(8));
            return;
        }
        replica.pendingProposal = null;
        replica.pendingBallot = ballot;
        replica.pendingBatch = batch;
        replica.pendingTickTo = batch.tickTo();
        votesCast.incrementAndGet();
        transport.send(from, MessageCodec.encode(
                new ValidationVote(batch.region(), batch.epoch(), batch.baseVersion(),
                        ballot.vote())));
    }

    /** Primary-side: fold a validator's vote into the active round. */
    private void onVote(PeerAddress from, ValidationVote vote) {
        Round round = activeRound.get();
        if (round == null) {
            return; // stale vote after a completed round
        }
        ProposalKey key = round.collector().key();
        if (!vote.region().equals(key.region())
                || !vote.epoch().equals(key.epoch())
                || !vote.version().equals(key.version())
                || !isAuthenticatedMember(from, vote.vote().voter())
                || !committeeMembers(round.lease()).contains(vote.vote().voter())
                || !vote.region().equals(vote.vote().region())
                || !vote.epoch().equals(vote.vote().epoch())
                || !vote.version().equals(vote.vote().baseVersion())
                || !round.batchRoot().equals(vote.vote().batchRoot())) {
            return;
        }
        Bytes publicKey = publicKey(vote.vote().voter());
        if (publicKey == null || !signatures.verify(
                publicKey, vote.vote().signedPortion(), vote.vote().signature())) {
            return;
        }
        if (!round.ownRoot().equals(vote.vote().resultingRoot())) {
            // The same disagreement seen from the primary's chair: a validator re-executed our
            // batch and got a different world. Counted here too, because whether a divergence is
            // observable at all otherwise depends on which node happens to hold the seat.
            divergences.incrementAndGet();
            LOG.warn("DIVERGENCE in {} v{} — validator {} reports {}, we computed {}",
                    vote.region(), vote.version().value(), vote.vote().voter(),
                    vote.vote().resultingRoot().hash().toShortHex(8),
                    round.ownRoot().hash().toShortHex(8));
        }
        votesReceived.incrementAndGet();
        relayMetrics.recordVote(vote.vote().voter());
        // Kept for reliability scoring at commit: what each member computed, against what the
        // committee finally certified. Recorded here because this is the only place a vote is both
        // authenticated and attributed.
        round.votes().put(vote.vote().voter(), vote.vote().resultingRoot());
        round.collector().submit(vote.vote());
        Decision decision = round.collector().decide();
        if (!(decision instanceof Decision.Unresolved)) {
            round.decision().set(decision);
            round.done().countDown();
        }
    }

    /** Validator-side: the committee committed — persist the cert, then apply the pending delta. */
    private void onCommitAnnounce(PeerAddress from, CommitAnnounce announce) {
        Replica replica = replicas.get(announce.region());
        if (replica == null || replica.pendingBallot == null
                || !isAuthenticatedMember(from, replica.lease.primary())) {
            return;
        }
        // A revoked replica has deliberately withdrawn from committee work (the player walked out
        // of the region, or the entity lane gave it up). A commit announce already in flight when
        // that happened is ordinary, not a fault — applying it would throw an illegal pipeline
        // transition out of the peer state thread and kill it. Drop it: the region will resync
        // from the committed head if it is ever re-assigned.
        if (replica.pipeline.state() == dev.nodera.coordinator.PipelineState.REVOKED) {
            return;
        }
        relayMetrics.recordCommit(replica.lease.primary());
        QuorumCertificate cert;
        try {
            CanonicalReader certificateReader = new CanonicalReader(announce.certificateBytes());
            cert = QuorumCertificate.decode(certificateReader);
            if (certificateReader.available() != 0) {
                return;
            }
        } catch (RuntimeException malformed) {
            return;
        }
        if (!announce.region().equals(cert.region())
                || !announce.version().equals(cert.version().next())
                || !announce.resultingRoot().equals(cert.resultingRoot())
                || !certificateMatches(replica, cert, replica.pendingBallot)) {
            // This member disagreed with the committed root; it must resync, not apply blindly.
            return;
        }
        commitLocally(replica, cert, replica.pendingTickTo);
    }

    /** Persist the prepared candidate/certificate, then apply and advance the replica. */
    private synchronized void commitLocally(Replica replica, QuorumCertificate cert, long tick) {
        MemberBallot ballot = replica.pendingBallot;
        ActionBatch batch = replica.pendingBatch;
        member.markCommitted(cert);
        certificates.put(cert);
        RegionSnapshot expected = SnapshotDeltaApplier.apply(replica.snapshot, ballot.delta(), tick);
        WorldMutationApplier.ApplyResult applied = applier.apply(ballot.delta());
        if (!applied.committed()) {
            throw new IllegalStateException("certified delta failed to apply for "
                    + replica.snapshot.region() + " at " + applied.failedAt());
        }
        SnapshotVersion next = replica.snapshot.version().next();
        RegionSnapshot committed = world.reExtract(replica.snapshot.region(), next, tick);
        StateRoot extractedRoot = StateRoot.of(hashes.hash(committed));
        if (!committed.equals(expected) || !extractedRoot.equals(cert.resultingRoot())) {
            throw new IllegalStateException("certified commit did not reproduce resulting root");
        }
        // Publish order matters for lock-free observers (control probes poll snapshot/head/cert
        // without this lock): certificate and head first, the advanced snapshot LAST, so anyone
        // who sees the new version also sees the certificate that committed it.
        replica.lastCertificate = cert;
        replica.headRoot = cert.resultingRoot();
        replica.snapshot = committed;
        notifyCommit(committed, cert.resultingRoot());
        replica.pipeline.committeeCommitted(next);
        recordCommittedSequences(batch);
        replica.pendingBallot = null;
        replica.pendingBatch = null;
        // The primary answered: whatever this node forwarded has landed, so the lag clock stops.
        oldestUnansweredForwardNanos.remove(committed.region());
        committeeCommits.incrementAndGet();
    }

    /** Primary-side terminal path for a source delta carrying one border-transfer intent. */
    private Optional<StateRoot> commitTransfer(
            Replica source, QuorumCertificate sourceActionCertificate, long tick) {
        RegionDelta sourceDelta = source.pendingBallot.delta();
        if (sourceDelta.transferIntents().size() != 1) {
            return Optional.empty();
        }
        RegionId targetRegion = sourceDelta.transferIntents().getFirst().targetRegion();
        Replica target = replicas.get(targetRegion);
        if (target == null || target.snapshot.tick() != tick) {
            return Optional.empty();
        }
        long transferId = StableHash.of(
                sourceDelta.transferIntents().getFirst().entityId().value(),
                source.snapshot.version().value(), StableHash.of(targetRegion.toString()));

        member.markCommitted(sourceActionCertificate);
        certificates.put(sourceActionCertificate);
        EntityTransferCoordinator.TransferOutcome outcome = transferCoordinator.transfer(
                transferId, source.pipeline, target.pipeline, source.snapshot, target.snapshot,
                sourceDelta, tick);
        if (!(outcome instanceof EntityTransferCoordinator.TransferResult result)) {
            return Optional.empty();
        }
        RegionSnapshot sourceCommitted = world.reExtract(
                source.snapshot.region(), sourceDelta.resultingVersion(), tick);
        RegionSnapshot targetCommitted = world.reExtract(
                target.snapshot.region(), result.targetDelta().resultingVersion(), tick);
        // Certificate + heads before the snapshots (see commitLocally's publish-order note).
        source.lastCertificate = sourceActionCertificate;
        source.headRoot = result.certificate().descriptor().sourceResultingRoot();
        target.headRoot = result.certificate().descriptor().targetResultingRoot();
        source.snapshot = sourceCommitted;
        target.snapshot = targetCommitted;
        recordCommittedSequences(source.pendingBatch);
        source.pendingBallot = null;
        source.pendingBatch = null;
        committeeCommits.incrementAndGet();

        EntityTransferCommit commit = new EntityTransferCommit(
                result.certificate(), sourceActionCertificate,
                result.sourceDelta(), result.targetDelta());
        for (NodeId participant : transferParticipants(source.lease, target.lease)) {
            if (!participant.equals(identity.nodeId())) {
                sendTo(participant, commit);
            }
        }
        return Optional.of(source.headRoot);
    }

    /** Committee-side validation and signed acceptance of one transfer descriptor. */
    private void onTransferPrepare(PeerAddress from, EntityTransferPrepare prepare) {
        EntityTransferDescriptor descriptor = prepare.descriptor();
        RegionLease sourceLease = knownLeases.get(descriptor.sourceRegion());
        RegionLease targetLease = knownLeases.get(descriptor.targetRegion());
        Replica source = replicas.get(descriptor.sourceRegion());
        Replica target = replicas.get(descriptor.targetRegion());
        boolean sourceValid = source != null && validateTransferSide(prepare, source, true);
        boolean targetValid = target != null && validateTransferSide(prepare, target, false);
        if (sourceLease == null || targetLease == null || (!sourceValid && !targetValid)
                || !sourceLease.epoch().equals(descriptor.sourceEpoch())
                || !targetLease.epoch().equals(descriptor.targetEpoch())
                || !isAuthenticatedMember(from, sourceLease.primary())) {
            return;
        }
        EntityTransferDescriptor prior = acceptedTransfers.putIfAbsent(
                descriptor.transferId(), descriptor);
        if (prior != null && !prior.equals(descriptor)) {
            return;
        }
        EntityTransferCoordinator.TransferPlan plan = transferPlan(
                descriptor, prepare.sourceDelta(), prepare.targetDelta());
        try {
            transferJournal.prepared(plan);
        } catch (RuntimeException persistenceFailure) {
            return;
        }
        if (sourceValid && committeeMembers(sourceLease).contains(identity.nodeId())) {
            sendTransferAcceptance(from, descriptor, descriptor.sourceRegion());
        }
        if (targetValid && committeeMembers(targetLease).contains(identity.nodeId())) {
            sendTransferAcceptance(from, descriptor, descriptor.targetRegion());
        }
    }

    private void sendTransferAcceptance(
            PeerAddress primary,
            EntityTransferDescriptor descriptor,
            RegionId side) {
        SignedVote vote = signTransferVote(descriptor, side);
        votesCast.incrementAndGet();
        transport.send(primary, MessageCodec.encode(
                new EntityTransferAccept(descriptor.transferId(), side, vote)));
    }

    /** Primary-side collection of source and target committee acceptances. */
    private void onTransferAccept(PeerAddress from, EntityTransferAccept accept) {
        TransferRound round = activeTransferRound.get();
        if (round == null || accept.transferId() != round.descriptor.transferId()
                || !isAuthenticatedMember(from, accept.vote().voter())
                || !transferVoteMatches(round.descriptor, accept.side(), accept.vote())) {
            return;
        }
        Map<NodeId, SignedVote> votes;
        Set<NodeId> members;
        if (accept.side().equals(round.descriptor.sourceRegion())) {
            votes = round.sourceVotes;
            members = committeeMembers(round.sourceLease);
        } else if (accept.side().equals(round.descriptor.targetRegion())) {
            votes = round.targetVotes;
            members = committeeMembers(round.targetLease);
        } else {
            return;
        }
        if (!members.contains(accept.vote().voter())) {
            return;
        }
        Bytes key = publicKey(accept.vote().voter());
        if (key == null || !signatures.verify(
                key, accept.vote().signedPortion(), accept.vote().signature())) {
            return;
        }
        votes.putIfAbsent(accept.vote().voter(), accept.vote());
        votesReceived.incrementAndGet();
        if (transferQuorumReached(round)) {
            round.done.countDown();
        }
    }

    /** Apply a fully joint-certified transfer on every member holding both replicas. */
    private void onTransferCommit(PeerAddress from, EntityTransferCommit commit) {
        EntityTransferDescriptor descriptor = commit.certificate().descriptor();
        RegionLease sourceLease = knownLeases.get(descriptor.sourceRegion());
        RegionLease targetLease = knownLeases.get(descriptor.targetRegion());
        Replica source = replicas.get(descriptor.sourceRegion());
        Replica target = replicas.get(descriptor.targetRegion());
        if ((source == null && target == null) || sourceLease == null || targetLease == null
                || !isAuthenticatedMember(from, sourceLease.primary())
                || !new NetworkTransferApprovals().verify(commit.certificate())
                || !validateTransferCommit(
                commit, sourceLease, targetLease, source, target)) {
            return;
        }
        EntityTransferCoordinator.TransferPlan plan = transferPlan(
                descriptor, commit.sourceDelta(), commit.targetDelta());
        try {
            transferJournal.accepted(plan, commit.certificate());
            if (source != null
                    && source.pipeline.state() == dev.nodera.coordinator.PipelineState.ACTIVE) {
                source.pipeline.pauseForCrossRegion();
            }
            if (target != null
                    && target.pipeline.state() == dev.nodera.coordinator.PipelineState.ACTIVE) {
                target.pipeline.pauseForCrossRegion();
            }
            List<RegionDelta> localDeltas = source != null && target != null
                    ? List.of(commit.sourceDelta(), commit.targetDelta())
                    : List.of(source != null ? commit.sourceDelta() : commit.targetDelta());
            WorldMutationApplier.ApplyResult applied = applier.recoverTransfer(localDeltas);
            if (!applied.committed()) {
                throw new IllegalStateException(
                        "certified transfer failed recovery apply: " + applied.failure());
            }
            transferJournal.applied(plan, commit.certificate());
            transferJournal.committed(plan, commit.certificate());
            if (source != null && source.pendingBallot != null
                    && certificateMatches(source, commit.sourceActionCertificate(),
                    source.pendingBallot)) {
                member.markCommitted(commit.sourceActionCertificate());
                certificates.put(commit.sourceActionCertificate());
                recordCommittedSequences(source.pendingBatch);
                source.pendingBallot = null;
                source.pendingBatch = null;
            }
            if (source != null) {
                source.pipeline.crossRegionCommitted(descriptor.sourceResultingVersion());
                RegionSnapshot sourceCommitted = world.reExtract(
                        descriptor.sourceRegion(), descriptor.sourceResultingVersion(), descriptor.tick());
                // Certificate + head before the snapshot (commitLocally's publish-order note).
                source.lastCertificate = commit.sourceActionCertificate();
                source.headRoot = descriptor.sourceResultingRoot();
                source.snapshot = sourceCommitted;
            }
            if (target != null) {
                target.pipeline.crossRegionCommitted(descriptor.targetResultingVersion());
                RegionSnapshot targetCommitted = world.reExtract(
                        descriptor.targetRegion(), descriptor.targetResultingVersion(), descriptor.tick());
                target.headRoot = descriptor.targetResultingRoot();
                target.snapshot = targetCommitted;
            }
            committeeCommits.incrementAndGet();
        } catch (RuntimeException invalidOrUnavailable) {
            // Fail closed: paused pipelines require durable recovery/resync before more work.
        }
    }

    /** Host-side commit of a vanilla-authoritative ghost/block external delta. */
    public synchronized void commitExternal(
            RegionDelta delta, ServerAuthorityCertificate certificate, long tick) {
        Replica replica = replicas.get(delta.region());
        if (replica == null || !identity.nodeId().equals(replica.lease.primary())
                || !externalCertificateMatches(
                delta, certificate, identity.publicKeyBytes())) {
            throw new IllegalArgumentException("external delta is not authorised for active primary");
        }
        applyExternal(replica, delta, certificate, tick);
        CanonicalWriter deltaWriter = new CanonicalWriter();
        CanonicalWriter certificateWriter = new CanonicalWriter();
        delta.encode(deltaWriter);
        certificate.encode(certificateWriter);
        ExternalDelta message = new ExternalDelta(
                delta.region(), delta.baseVersion(), deltaWriter.toBytes(),
                certificateWriter.toBytes(), tick);
        for (NodeId member : committeeMembers(replica.lease)) {
            if (!member.equals(identity.nodeId())) {
                sendTo(member, message);
            }
        }
    }

    private void onExternalDelta(PeerAddress from, ExternalDelta message) {
        Replica replica = replicas.get(message.region());
        if (replica == null || !isAuthenticatedMember(from, replica.lease.primary())) {
            return;
        }
        RegionDelta delta;
        ServerAuthorityCertificate certificate;
        try {
            CanonicalReader deltaReader = new CanonicalReader(message.encodedDelta());
            CanonicalReader certificateReader = new CanonicalReader(message.certificateBytes());
            delta = RegionDelta.decode(deltaReader);
            certificate = ServerAuthorityCertificate.decode(certificateReader);
            if (deltaReader.available() != 0 || certificateReader.available() != 0) {
                return;
            }
        } catch (RuntimeException malformed) {
            return;
        }
        Bytes key = publicKey(replica.lease.primary());
        if (!message.baseVersion().equals(replica.snapshot.version())
                || !externalCertificateMatches(delta, certificate, key)) {
            return;
        }
        try {
            applyExternal(replica, delta, certificate, message.tick());
        } catch (RuntimeException requiresResync) {
            // Drop malformed/stale authority update; normal snapshot resync owns recovery.
        }
    }

    private void applyExternal(
            Replica replica, RegionDelta delta,
            ServerAuthorityCertificate certificate, long tick) {
        WorldMutationApplier.ApplyResult applied = applier.recoverAll(List.of(delta));
        if (!applied.committed()) {
            throw new IllegalStateException("external delta failed apply: " + applied.failure());
        }
        RegionSnapshot snapshot = world.reExtract(
                delta.region(), delta.resultingVersion(), tick);
        if (!StateRoot.of(hashes.hash(snapshot)).equals(certificate.resultingRoot())) {
            throw new IllegalStateException("external delta did not reproduce certified root");
        }
        replica.snapshot = snapshot;
        replica.headRoot = certificate.resultingRoot();
        replica.pipeline.externalCommitted(delta.resultingVersion());
        ExternalCommitListener listener = externalCommitListener;
        if (listener != null) {
            // Durability seam (issue #34 / L-50): external commits were memory-applied only, so a
            // session reopen lost them. The listener persists the certified resulting snapshot;
            // a persistence failure must pause the lane rather than silently fork the reopen
            // state, so it propagates like any other apply failure.
            listener.externalCommitted(snapshot, certificate);
        }
    }

    /**
     * Install the durable sink for applied external commits (primary and validator side). The
     * listener is invoked after the delta is applied, root-verified, and the pipeline advanced;
     * see {@link WorldStoreExternalHeads} for the durable implementation.
     */
    public void setExternalCommitListener(ExternalCommitListener listener) {
        this.externalCommitListener = listener;
    }

    /** Observer for applied external (server-authoritative) commits. */
    @FunctionalInterface
    public interface ExternalCommitListener {
        void externalCommitted(RegionSnapshot snapshot, ServerAuthorityCertificate certificate);
    }

    private boolean externalCertificateMatches(
            RegionDelta delta, ServerAuthorityCertificate certificate, Bytes publicKey) {
        return publicKey != null
                && delta.transferIntents().isEmpty()
                && certificate.reason() == ServerAuthorityCertificate.Reason.EXTERNAL_MUTATION
                && certificate.region().equals(delta.region())
                && certificate.baseVersion().equals(delta.baseVersion())
                && certificate.resultingVersion().equals(delta.resultingVersion())
                && certificate.resultingRoot().equals(delta.resultingRoot())
                && certificate.transitionRoot().equals(StateRoot.of(hashes.hash(delta)))
                && signatures.verify(
                publicKey, certificate.signedPortion(), certificate.serverSignature());
    }

    private boolean validateTransferPlan(
            EntityTransferPrepare prepare, Replica source, Replica target) {
        EntityTransferDescriptor descriptor = prepare.descriptor();
        if (!descriptor.sourceEpoch().equals(source.lease.epoch())
                || !descriptor.targetEpoch().equals(target.lease.epoch())
                || !descriptor.sourceBaseVersion().equals(source.snapshot.version())
                || !descriptor.targetBaseVersion().equals(target.snapshot.version())
                || !descriptor.sourcePrevRoot().equals(source.headRoot)
                || !descriptor.targetPrevRoot().equals(target.headRoot)
                || target.snapshot.tick() != descriptor.tick()
                || !descriptor.sourceTransitionRoot().equals(
                StateRoot.of(hashes.hash(prepare.sourceDelta())))
                || !descriptor.targetTransitionRoot().equals(
                StateRoot.of(hashes.hash(prepare.targetDelta())))) {
            return false;
        }
        try {
            RegionSnapshot sourceAfter = SnapshotDeltaApplier.apply(
                    source.snapshot, prepare.sourceDelta(), descriptor.tick());
            RegionSnapshot targetAfter = SnapshotDeltaApplier.apply(
                    target.snapshot, prepare.targetDelta(), descriptor.tick());
            return StateRoot.of(hashes.hash(sourceAfter)).equals(descriptor.sourceResultingRoot())
                    && StateRoot.of(hashes.hash(targetAfter)).equals(descriptor.targetResultingRoot());
        } catch (RuntimeException invalidDelta) {
            return false;
        }
    }

    private boolean validateTransferSide(
            EntityTransferPrepare prepare, Replica replica, boolean sourceSide) {
        EntityTransferDescriptor descriptor = prepare.descriptor();
        RegionDelta delta = sourceSide ? prepare.sourceDelta() : prepare.targetDelta();
        RegionId region = sourceSide ? descriptor.sourceRegion() : descriptor.targetRegion();
        var epoch = sourceSide ? descriptor.sourceEpoch() : descriptor.targetEpoch();
        var base = sourceSide
                ? descriptor.sourceBaseVersion() : descriptor.targetBaseVersion();
        StateRoot previous = sourceSide
                ? descriptor.sourcePrevRoot() : descriptor.targetPrevRoot();
        StateRoot resulting = sourceSide
                ? descriptor.sourceResultingRoot() : descriptor.targetResultingRoot();
        StateRoot transition = sourceSide
                ? descriptor.sourceTransitionRoot() : descriptor.targetTransitionRoot();
        if (!replica.snapshot.region().equals(region)
                || !replica.lease.epoch().equals(epoch)
                || !replica.snapshot.version().equals(base)
                || !replica.headRoot.equals(previous)
                || !delta.region().equals(region)
                || !delta.baseVersion().equals(base)
                || !delta.resultingRoot().equals(resulting)
                || !transition.equals(StateRoot.of(hashes.hash(delta)))
                || (!sourceSide && replica.snapshot.tick() != descriptor.tick())) {
            return false;
        }
        try {
            RegionSnapshot after = SnapshotDeltaApplier.apply(
                    replica.snapshot, delta, descriptor.tick());
            return StateRoot.of(hashes.hash(after)).equals(resulting);
        } catch (RuntimeException invalidDelta) {
            return false;
        }
    }

    private boolean validateTransferCommit(
            EntityTransferCommit commit,
            RegionLease sourceLease,
            RegionLease targetLease,
            Replica source,
            Replica target) {
        EntityTransferDescriptor descriptor = commit.certificate().descriptor();
        EntityTransferPrepare prepare = new EntityTransferPrepare(
                descriptor, commit.sourceDelta(), commit.targetDelta());
        if (!descriptor.sourceEpoch().equals(sourceLease.epoch())
                || !descriptor.targetEpoch().equals(targetLease.epoch())
                || !descriptor.sourceTransitionRoot().equals(
                StateRoot.of(hashes.hash(commit.sourceDelta())))
                || !descriptor.targetTransitionRoot().equals(
                StateRoot.of(hashes.hash(commit.targetDelta())))
                || !sourceActionCertificateMatches(
                sourceLease, descriptor, commit.sourceActionCertificate(), commit.sourceDelta())
                || (source != null && !validateTransferSide(prepare, source, true))
                || (target != null && !validateTransferSide(prepare, target, false))) {
            return false;
        }
        if (source != null && source.pendingBallot != null
                && !certificateMatches(
                source, commit.sourceActionCertificate(), source.pendingBallot)) {
            return false;
        }
        return true;
    }

    private boolean sourceActionCertificateMatches(
            RegionLease sourceLease,
            EntityTransferDescriptor descriptor,
            QuorumCertificate certificate,
            RegionDelta delta) {
        Set<NodeId> members = committeeMembers(sourceLease);
        StateRoot transitionRoot = StateRoot.of(hashes.hash(delta));
        if (!certificate.region().equals(descriptor.sourceRegion())
                || !certificate.epoch().equals(descriptor.sourceEpoch())
                || !certificate.version().equals(descriptor.sourceBaseVersion())
                || !certificate.prevRoot().equals(descriptor.sourcePrevRoot())
                || !certificate.resultingRoot().equals(delta.resultingRoot())
                || certificate.votes().size()
                < MajorityQuorumPolicy.requiredForMajority(members.size())) {
            return false;
        }
        Set<NodeId> seen = new HashSet<>();
        StateRoot batchRoot = certificate.votes().getFirst().batchRoot();
        return batchRoot != null && certificate.votes().stream().allMatch(vote ->
                seen.add(vote.voter()) && members.contains(vote.voter())
                        && vote.decision() == VoteDecision.ACCEPT
                        && vote.region().equals(certificate.region())
                        && vote.epoch().equals(certificate.epoch())
                        && vote.baseVersion().equals(certificate.version())
                        && vote.batchRoot().equals(batchRoot)
                        && vote.resultingRoot().equals(certificate.resultingRoot())
                        && vote.transitionRoot().equals(transitionRoot)
                        && publicKey(vote.voter()) != null
                        && signatures.verify(publicKey(vote.voter()),
                        vote.signedPortion(), vote.signature()));
    }

    private SignedVote signTransferVote(EntityTransferDescriptor descriptor, RegionId side) {
        boolean source = side.equals(descriptor.sourceRegion());
        if (!source && !side.equals(descriptor.targetRegion())) {
            throw new IllegalArgumentException("vote side is not part of transfer");
        }
        var epoch = source ? descriptor.sourceEpoch() : descriptor.targetEpoch();
        var base = source ? descriptor.sourceBaseVersion() : descriptor.targetBaseVersion();
        var resulting = source
                ? descriptor.sourceResultingRoot() : descriptor.targetResultingRoot();
        var transition = source
                ? descriptor.sourceTransitionRoot() : descriptor.targetTransitionRoot();
        StateRoot approvalRoot = StateRoot.of(hashes.hash(descriptor));
        SignedVote unsigned = new SignedVote(
                identity.nodeId(), side, epoch, base, approvalRoot,
                resulting, transition, VoteDecision.ACCEPT, Bytes.empty());
        return new SignedVote(
                identity.nodeId(), side, epoch, base, approvalRoot,
                resulting, transition, VoteDecision.ACCEPT,
                identity.sign(unsigned.signedPortion()));
    }

    private boolean transferVoteMatches(
            EntityTransferDescriptor descriptor, RegionId side, SignedVote vote) {
        boolean source = side.equals(descriptor.sourceRegion());
        if (!source && !side.equals(descriptor.targetRegion())) {
            return false;
        }
        return vote.bodyVersion() >= 3 && vote.decision() == VoteDecision.ACCEPT
                && vote.region().equals(side)
                && vote.epoch().equals(source ? descriptor.sourceEpoch() : descriptor.targetEpoch())
                && vote.baseVersion().equals(source
                ? descriptor.sourceBaseVersion() : descriptor.targetBaseVersion())
                && vote.batchRoot().equals(StateRoot.of(hashes.hash(descriptor)))
                && vote.resultingRoot().equals(source
                ? descriptor.sourceResultingRoot() : descriptor.targetResultingRoot())
                && vote.transitionRoot().equals(source
                ? descriptor.sourceTransitionRoot() : descriptor.targetTransitionRoot());
    }

    private boolean transferQuorumReached(TransferRound round) {
        int sourceRequired = MajorityQuorumPolicy.requiredForMajority(
                committeeMembers(round.sourceLease).size());
        int targetRequired = MajorityQuorumPolicy.requiredForMajority(
                committeeMembers(round.targetLease).size());
        return round.sourceVotes.size() >= sourceRequired
                && round.targetVotes.size() >= targetRequired;
    }

    private static Set<NodeId> transferParticipants(
            RegionLease source, RegionLease target) {
        Set<NodeId> participants = new HashSet<>(committeeMembers(source));
        participants.addAll(committeeMembers(target));
        return participants;
    }

    private void sendTo(NodeId peer, NoderaMessage message) {
        PeerAddress address = peers.get(peer);
        if (address != null) {
            try {
                transport.send(address, MessageCodec.encode(message));
            } catch (dev.nodera.transport.TransportException unreachable) {
                // Best-effort fan-out: an unreachable member is an absent recipient, and every
                // certified path re-converges via resync rather than by aborting the sender.
            }
        }
    }

    private boolean certificateMatches(
            Replica replica, QuorumCertificate certificate, MemberBallot ballot) {
        if (!certificate.region().equals(replica.snapshot.region())
                || !certificate.epoch().equals(replica.lease.epoch())
                || !certificate.version().equals(replica.snapshot.version())
                || !certificate.prevRoot().equals(replica.headRoot)
                || certificate.votes().size() < MajorityQuorumPolicy.requiredForMajority(
                committeeMembers(replica.lease).size())) {
            return false;
        }
        StateRoot transitionRoot = StateRoot.of(hashes.hash(ballot.delta()));
        StateRoot batchRoot = StateRoot.of(hashes.hash(replica.pendingBatch));
        Set<NodeId> seen = new HashSet<>();
        return certificate.resultingRoot().equals(ballot.root())
                && certificate.votes().getFirst().transitionRoot().equals(transitionRoot)
                && certificate.votes().stream().allMatch(vote ->
                seen.add(vote.voter())
                        && committeeMembers(replica.lease).contains(vote.voter())
                        && vote.decision() == dev.nodera.core.consensuscert.VoteDecision.ACCEPT
                        && vote.resultingRoot().equals(certificate.resultingRoot())
                        && vote.transitionRoot().equals(transitionRoot)
                        && certificate.region().equals(vote.region())
                        && certificate.epoch().equals(vote.epoch())
                        && certificate.version().equals(vote.baseVersion())
                        && batchRoot.equals(vote.batchRoot())
                        && publicKey(vote.voter()) != null
                        && signatures.verify(publicKey(vote.voter()),
                        vote.signedPortion(), vote.signature()));
    }

    private void onProposal(PeerAddress from, RegionProposal proposal) {
        Replica replica = replicas.get(proposal.region());
        if (replica == null || !isAuthenticatedMember(from, replica.lease.primary())) {
            return;
        }
        Bytes primaryKey = publicKey(replica.lease.primary());
        if (primaryKey == null
                || proposal.bodyVersion() != RegionProposal.PROPOSAL_ENCODING_VERSION
                || !proposal.epoch().equals(replica.lease.epoch())
                || !proposal.baseVersion().equals(replica.snapshot.version())
                || !proposal.prevRoot().equals(replica.headRoot)
                || !signatures.verify(primaryKey, proposal.signedPortion(), proposal.proposerSig())) {
            return;
        }
        CanonicalReader reader = new CanonicalReader(proposal.encodedDelta());
        dev.nodera.core.state.RegionDelta delta;
        try {
            delta = dev.nodera.core.state.RegionDelta.decode(reader);
        } catch (RuntimeException malformed) {
            return;
        }
        if (reader.available() != 0
                || !delta.region().equals(proposal.region())
                || !delta.baseVersion().equals(proposal.baseVersion())
                || !delta.resultingRoot().equals(proposal.resultingRoot())) {
            return;
        }
        MemberBallot pendingBallot = replica.pendingBallot;
        if (pendingBallot != null) {
            CanonicalWriter pendingDelta = new CanonicalWriter();
            pendingBallot.delta().encode(pendingDelta);
            if (proposal.resultingRoot().equals(pendingBallot.root())
                    && proposal.encodedDelta().equals(pendingDelta.toBytes())
                    && proposal.batchRoot().equals(pendingBallot.vote().batchRoot())) {
                transport.send(from, MessageCodec.encode(new ValidationVote(
                        proposal.region(), proposal.epoch(), proposal.baseVersion(),
                        pendingBallot.vote())));
            }
            return;
        }
        if (replica.pendingProposal != null) {
            return;
        }
        replica.pendingProposal = proposal;
    }

    /**
     * Take a committee seat handed out by the hosting world (the headless half of the no-host
     * ownership plan). The mod plans regions from player views, tops the leftover validator seats
     * up with the session's playerless members, and sends each of them a
     * {@link dev.nodera.protocol.assignment.RegionAssigned} — this is where an always-on peer
     * stops being a bystander and starts re-executing the world.
     *
     * <p>Everything needed is reconstructible: the lease from the message, and the base snapshot
     * from {@link EntityLaneBootstrap#initialSnapshot} — byte-identical to the primary's, which is
     * what makes the first {@code prevRoot} comparison in {@link #onProposal} line up without any
     * state transfer.
     *
     * <p>Ignored unless the assignment names this node as a VALIDATOR. A worker is never a
     * primary: primacy is geometric and a peer with no player view is nowhere. Re-assignment at an
     * epoch this node already holds is also ignored — the mod re-plans on every membership or
     * movement change, and replacing a live replica would rewind its head root mid-round and make
     * every subsequent proposal fail its {@code prevRoot} check.
     */
    private void onRegionAssigned(dev.nodera.protocol.assignment.RegionAssigned assigned) {
        if (assigned.role() != dev.nodera.core.region.RegionReplicaRole.VALIDATOR
                || assigned.committee().isEmpty()
                || !assigned.committee().contains(identity.nodeId())) {
            return;
        }
        Replica existing = replicas.get(assigned.region());
        if (existing != null && existing.lease.epoch().value() >= assigned.epoch().value()) {
            return; // already seated at this epoch or a newer one
        }
        NodeId primary = assigned.committee().get(0);
        if (primary.equals(identity.nodeId())) {
            return; // a resident validator must never be handed primacy
        }
        List<NodeId> validators = assigned.committee().subList(1, assigned.committee().size());
        RegionLease lease = new RegionLease(
                assigned.region(), assigned.epoch(), primary, validators,
                assigned.leaseExpiryTick() - dev.nodera.core.NoderaConstants.LEASE_LENGTH_TICKS,
                assigned.leaseExpiryTick());
        if (existing != null) {
            // A re-seat of a LIVE replica (committee rotation, lag handoff, ownership re-plan) is a
            // change of committee, not of state: keep the committed snapshot. Re-activating from the
            // genesis snapshot here would rewind this member to v0, and every subsequent proposal
            // would fail its prevRoot check against the rest of the committee.
            registerLease(lease);
            replicas.put(assigned.region(), adopt(existing, lease));
            return;
        }
        activateRegion(EntityLaneBootstrap.initialSnapshot(assigned.region()), lease);
    }

    /**
     * Sustained-lag handoff on the LIVE mesh (issue #46.1): observe one skew window for a region
     * this node validates and, when the primary has been behind the network reference for
     * {@link LagHandoffPolicy} consecutive windows, take primacy at {@code epoch + 1} and tell the
     * rest of the committee.
     *
     * <p>Before this, a player whose client fell far behind held its regions hostage: every
     * cross-border action into them waited on a primary that could not keep up, and nothing on the
     * live lane ever consulted the Task 25 policy that exists precisely for this. The handoff is
     * the boundary-independence half — the region's work moves to a member that can do it.
     *
     * <p>Only the <b>deterministic successor</b> — {@code validators.get(0)} of the current lease,
     * the node {@link CommitteeFailover#promoteOnPrimaryLoss} would promote — may initiate, so a
     * committee cannot split-brain into two competing epochs from one slow window. The lagging
     * primary and every other validator adopt the result from the broadcast
     * {@code RegionAssigned}.
     *
     * @param region      the region being observed.
     * @param skewTickBps the primary's skew in tick-basis-points ({@link LagHandoffPolicy#TICK_BASIS_POINTS} = 1 tick).
     * @param leases      the lease manager holding this region's epoch.
     * @param reliability the ledger that records the handoff penalty.
     * @param nowTick     the current tick.
     * @return the promoted lease when a handoff happened, otherwise {@code null}.
     * @Thread-context the lane's tick thread (one caller).
     */
    public RegionLease observeSkew(RegionId region, long skewTickBps, LeaseManager leases,
                                   dev.nodera.coordinator.ReliabilityLedger reliability,
                                   long nowTick) {
        Replica replica = replicas.get(region);
        if (replica == null) {
            return null;
        }
        RegionLease current = replica.lease;
        if (identity.nodeId().equals(current.primary())
                || current.validators().isEmpty()
                || !identity.nodeId().equals(current.validators().get(0))) {
            return null; // not the successor this committee would agree on
        }
        var decision = lagPolicy.observe(current, skewTickBps, nowTick);
        if (decision.isEmpty()) {
            return null;
        }
        // The lease came from the deterministic FOV plan, not from this manager: install it so the
        // guarded handoff can compare the decision against the lease this node is actually running.
        leases.adopt(current);
        RegionLease promoted =
                CommitteeFailover.promoteOnLag(decision.get(), leases, reliability, nowTick);
        if (promoted == null) {
            return null;
        }
        registerLease(promoted);
        replicas.put(region, adopt(replica, promoted));
        announceSeats(promoted);
        return promoted;
    }

    /** Tell every other committee member to re-seat under {@code lease} (its committee order). */
    private void announceSeats(RegionLease lease) {
        List<NodeId> committee = new java.util.ArrayList<>();
        committee.add(lease.primary());
        committee.addAll(lease.validators());
        var assigned = new dev.nodera.protocol.assignment.RegionAssigned(
                lease.region(), lease.epoch(), dev.nodera.core.region.RegionReplicaRole.VALIDATOR,
                replicas.get(lease.region()).snapshot.version(), lease.expiresAtTick(), committee);
        byte[] frame = MessageCodec.encode(assigned);
        for (NodeId member : committee) {
            if (identity.nodeId().equals(member)) {
                continue;
            }
            PeerAddress address = peers.get(member);
            if (address == null) {
                continue;
            }
            try {
                transport.send(address, frame);
            } catch (dev.nodera.transport.TransportException unreachable) {
                // An unreachable member re-seats when the next plan or assignment reaches it;
                // one dead peer must never stop the handoff that is fixing the lag.
            }
        }
    }

    /** Sustained-lag detection for the regions this node validates (Task 25 policy, live lane). */
    private final LagHandoffPolicy lagPolicy = new LagHandoffPolicy();

    /** Epoch bookkeeping for handoffs this node initiates (the FOV plan issues leases elsewhere). */
    private final LeaseManager handoffLeases =
            new LeaseManager(dev.nodera.core.NoderaConstants.LEASE_LENGTH_TICKS);

    /**
     * This node's view of who can be trusted with a region. One ledger, two kinds of evidence: a
     * committed round writes agreement/disagreement through {@link CommitteeScoring}, and a lag
     * handoff writes its one-shot penalty. They were separate before — the handoff wrote to a
     * private ledger and committee outcomes were written nowhere at all — which meant a node that
     * consistently computed the wrong world kept a spotless reputation as long as it answered
     * quickly. Local by construction: reputation is a view, never consensus state (nothing derived
     * from it may enter a root).
     */
    private volatile dev.nodera.coordinator.ReliabilityLedger reliability =
            new dev.nodera.coordinator.ReliabilityLedger();

    /** Set when a caller gives this lane somewhere durable to keep its view; may stay null. */
    private volatile DurableCoordinatorState durableState;

    /**
     * @return this node's reliability view, for diagnostics and for an assignment planner that
     *         wants to skip a member below the floor. Never consensus state.
     */
    public dev.nodera.coordinator.ReliabilityLedger reliability() {
        return reliability;
    }

    /**
     * Give this lane a durable home for its epochs and reputations, and adopt whatever it already
     * remembers. Without this the view is a session counter: every restart forgets who behaved.
     *
     * @param durable the state file wrapper; {@code null} detaches (the view stays in memory).
     */
    public void attachDurableState(DurableCoordinatorState durable) {
        this.durableState = durable;
        if (durable != null) {
            this.reliability = durable.reliability();
        }
    }

    /**
     * Write the reliability view and epochs to disk when a durable state is attached; a no-op
     * otherwise. Cheap: a few hundred bytes for a normal committee.
     */
    public void persistState() {
        DurableCoordinatorState durable = durableState;
        if (durable != null) {
            durable.flush();
        }
    }

    /** Ticks between lag windows — three of these must be unhealthy before a region hands off. */
    private static final long LAG_WINDOW_TICKS = 100L;

    private long lastLagWindowTick = Long.MIN_VALUE;

    /**
     * Drive the live lag lane once per server tick (issue #46.1): every {@value #LAG_WINDOW_TICKS}
     * ticks, each region this node validates is measured by how long its primary has left this
     * node's forwarded actions unanswered, and a sustained offender loses primacy.
     *
     * <p>Cheap and side-effect-free between windows, so it is safe on the tick path.
     *
     * @param nowTick  the current server tick.
     * @param nowNanos the current monotonic time.
     * @return the regions handed off in this window (usually empty).
     * @Thread-context the server tick thread.
     */
    public List<RegionId> tickLagHandoff(long nowTick, long nowNanos) {
        if (nowTick - lastLagWindowTick < LAG_WINDOW_TICKS) {
            return List.of();
        }
        lastLagWindowTick = nowTick;
        List<RegionId> handedOff = new java.util.ArrayList<>();
        for (RegionId region : replicas.keySet()) {
            RegionLease promoted = observeSkew(region, forwardLagTickBps(region, nowNanos),
                    handoffLeases, reliability, Math.max(0L, nowTick));
            if (promoted != null) {
                handedOff.add(region);
            }
        }
        return handedOff;
    }

    private boolean isAuthenticatedMember(PeerAddress from, NodeId expected) {
        PeerAddress registered = peers.get(expected);
        return from != null && registered != null && registered.equals(from);
    }

    private Bytes publicKey(NodeId nodeId) {
        return identity.nodeId().equals(nodeId) ? identity.publicKeyBytes() : peerKeys.get(nodeId);
    }

    private static Set<NodeId> committeeMembers(RegionLease lease) {
        Set<NodeId> members = new HashSet<>(lease.validators());
        members.add(lease.primary());
        return members;
    }

    private synchronized boolean validBatch(Replica replica, ActionBatch batch) {
        if (!batch.region().equals(replica.snapshot.region())
                || !batch.epoch().equals(replica.lease.epoch())
                || !batch.baseVersion().equals(replica.snapshot.version())
                || batch.tickFrom() < 0 || batch.tickTo() < batch.tickFrom()) {
            return false;
        }
        StateRoot batchRoot = StateRoot.of(hashes.hash(batch));
        ActionBatch reserved = reservedBatches.get(batchRoot);
        if (reserved != null) {
            return reserved.equals(batch);
        }
        Set<Long> batchServerSequences = new HashSet<>();
        Map<NodeId, Set<Long>> batchPlayerSequences = new java.util.HashMap<>();
        Map<NodeId, Long> lastBatchPlayerSequence = new java.util.HashMap<>();
        long lastBatchServerSequence = -1L;
        for (ActionEnvelope action : batch.actions()) {
            Set<Long> actorBatch = batchPlayerSequences.computeIfAbsent(
                    action.actor(), ignored -> new HashSet<>());
            long lastPlayerSequence = lastBatchPlayerSequence.getOrDefault(action.actor(), -1L);
            if (!action.region().equals(batch.region())
                    || action.targetTick() < batch.tickFrom() || action.targetTick() > batch.tickTo()
                    || action.serverSeq() < 0
                    || action.serverSeq() <= highestReservedServerSequence
                    || action.serverSeq() <= lastBatchServerSequence
                    || !batchServerSequences.add(action.serverSeq())
                    || action.playerSeq() < 0
                    || action.playerSeq() <= highestReservedPlayerSequence.getOrDefault(
                    action.actor(), -1L)
                    || action.playerSeq() <= lastPlayerSequence
                    || !actorBatch.add(action.playerSeq())
                    || !actorSignatureValid(action)
                    || !actionAdmission.authorize(action, replica.snapshot)) {
                return false;
            }
            lastBatchServerSequence = action.serverSeq();
            lastBatchPlayerSequence.put(action.actor(), action.playerSeq());
        }
        actionPersistence.reserve(batch.actions());
        reservedBatches.put(batchRoot, batch);
        if (!batch.actions().isEmpty()) {
            highestReservedServerSequence = lastBatchServerSequence;
            for (Map.Entry<NodeId, Long> entry : lastBatchPlayerSequence.entrySet()) {
                highestReservedPlayerSequence.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }
        return true;
    }

    private synchronized void recordCommittedSequences(ActionBatch batch) {
        if (batch == null) {
            return;
        }
        actionPersistence.commit(batch.actions());
        reservedBatches.remove(StateRoot.of(hashes.hash(batch)));
    }

    private synchronized void abortUncommitted(Replica replica, ActionBatch batch) {
        actionPersistence.abort(batch.actions());
        reservedBatches.remove(StateRoot.of(hashes.hash(batch)));
        replica.pendingBallot = null;
        replica.pendingBatch = null;
        replica.pendingProposal = null;
        replica.pipeline.revoke();
    }

    /**
     * Route one action; committee-lane actions are counted, fallback-lane actions are executed
     * immediately against {@code base} through the server lane ({@link FallbackExecutor}).
     */
    public RoutingDecision routeAndMaybeFallback(ActionEnvelope env,
                                                 CrossRegionRouter.RegionStatus status,
                                                 RegionSnapshot base) {
        RoutingDecision decision = router.route(env, status);
        if (decision.reason() == dev.nodera.fallback.RoutingReason.CROSS_REGION) {
            // A cross-region action must execute against the TARGET region's state — executing it
            // here against the envelope's owning-region base would feed the engine a foreign
            // position (it throws "cross-region action reached the engine"). This layer only
            // classifies; the server-lane owner of the target region executes the routed action
            // atomically in its own single lane (Task 8 CrossRegionPlan, live half).
            return decision;
        }
        if (decision.isFallback()) {
            if (!env.region().equals(base.region())
                    || env.serverSeq() < 0
                    || env.playerSeq() < 0
                    || !actorSignatureValid(env)
                    || !actionAdmission.authorize(env, base)) {
                throw new IllegalArgumentException("fallback action is not authenticated or admissible");
            }
            if (!reserveFallback(env)) {
                throw new IllegalArgumentException("fallback action sequence is stale or reserved");
            }
            InMemoryWorldView world = new InMemoryWorldView();
            world.load(base);
            FallbackExecutor executor = new FallbackExecutor(engine, new WorldMutationApplier(world));
            ActionBatch batch = new ActionBatch(env.region(), replicaEpochOrInitial(env.region()),
                    base.version(), env.targetTick(), env.targetTick(), List.of(env));
            FallbackExecutor.FallbackResult result =
                    executor.execute(new RegionExecutionRequest(contextFor(batch), base, batch));
            if (result.committed()) {
                recordCommittedSequences(batch);
                fallbackCommits.incrementAndGet();
            } else {
                actionPersistence.abort(batch.actions());
            }
        }
        return decision;
    }

    private synchronized boolean reserveFallback(ActionEnvelope action) {
        if (action.serverSeq() <= highestReservedServerSequence
                || action.playerSeq() <= highestReservedPlayerSequence.getOrDefault(
                action.actor(), -1L)) {
            return false;
        }
        actionPersistence.reserve(List.of(action));
        highestReservedServerSequence = action.serverSeq();
        highestReservedPlayerSequence.put(action.actor(), action.playerSeq());
        return true;
    }

    private synchronized void restoreActionWatermarks(List<ActionEnvelope> retained) {
        for (ActionEnvelope action : retained) {
            highestReservedServerSequence = Math.max(
                    highestReservedServerSequence, action.serverSeq());
            highestReservedPlayerSequence.merge(
                    action.actor(), action.playerSeq(), Math::max);
        }
    }

    /** Promote a surviving validator after primary loss (epoch + 1) and adopt the new lease. */
    public RegionLease failover(RegionId region, LeaseManager leases, long nowTick) {
        Replica replica = replicas.get(region);
        if (replica == null) {
            return null;
        }
        RegionLease promoted = CommitteeFailover.promoteOnPrimaryLoss(replica.lease, leases, nowTick);
        if (promoted != null) {
            registerLease(promoted);
            replicas.put(region, adopt(replica, promoted));
        }
        return promoted;
    }

    private Replica adopt(Replica old, RegionLease lease) {
        if (old.pendingBatch != null) {
            synchronized (this) {
                reservedBatches.remove(StateRoot.of(hashes.hash(old.pendingBatch)));
            }
        }
        Replica next = new Replica(old.snapshot, lease);
        return next;
    }

    /** Real transport-backed joint approval provider used by the transfer coordinator. */
    private final class NetworkTransferApprovals
            implements EntityTransferCoordinator.TransferApprovalProvider {

        @Override
        public EntityTransferCertificate approve(
                EntityTransferDescriptor descriptor,
                RegionDelta sourceDelta,
                RegionDelta targetDelta) {
            Replica source = replicas.get(descriptor.sourceRegion());
            Replica target = replicas.get(descriptor.targetRegion());
            if (source == null || target == null) {
                throw new IllegalStateException("both transfer replicas must be active");
            }
            EntityTransferPrepare prepare = new EntityTransferPrepare(
                    descriptor, sourceDelta, targetDelta);
            if (!validateTransferPlan(prepare, source, target)) {
                throw new IllegalArgumentException("local transfer plan validation failed");
            }
            TransferRound round = new TransferRound(
                    descriptor, sourceDelta, targetDelta, source.lease, target.lease);
            if (!activeTransferRound.compareAndSet(null, round)) {
                throw new IllegalStateException("another transfer approval is in flight");
            }
            try {
                acceptedTransfers.merge(descriptor.transferId(), descriptor, (prior, current) -> {
                    if (!prior.equals(current)) {
                        throw new IllegalArgumentException(
                                "transfer id reused with a different descriptor");
                    }
                    return prior;
                });
                if (committeeMembers(source.lease).contains(identity.nodeId())) {
                    round.sourceVotes.put(
                            identity.nodeId(), signTransferVote(descriptor, descriptor.sourceRegion()));
                }
                if (committeeMembers(target.lease).contains(identity.nodeId())) {
                    round.targetVotes.put(
                            identity.nodeId(), signTransferVote(descriptor, descriptor.targetRegion()));
                }
                for (NodeId participant : transferParticipants(source.lease, target.lease)) {
                    if (!participant.equals(identity.nodeId())) {
                        sendTo(participant, prepare);
                    }
                }
                if (transferQuorumReached(round)) {
                    round.done.countDown();
                }
                try {
                    round.done.await(voteTimeoutMillis + 500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("transfer approval interrupted", e);
                }
                if (!transferQuorumReached(round)) {
                    throw new IllegalStateException("both transfer committees did not reach quorum");
                }
                QuorumCertificate sourceProof = new QuorumCertificate(
                        descriptor.sourceRegion(), descriptor.sourceEpoch(),
                        descriptor.sourceBaseVersion(), descriptor.sourcePrevRoot(),
                        descriptor.sourceResultingRoot(), List.copyOf(round.sourceVotes.values()));
                QuorumCertificate targetProof = new QuorumCertificate(
                        descriptor.targetRegion(), descriptor.targetEpoch(),
                        descriptor.targetBaseVersion(), descriptor.targetPrevRoot(),
                        descriptor.targetResultingRoot(), List.copyOf(round.targetVotes.values()));
                EntityTransferCertificate certificate = new EntityTransferCertificate(
                        descriptor, sourceProof, targetProof);
                if (!verify(certificate)) {
                    throw new IllegalStateException("assembled transfer certificate did not verify");
                }
                return certificate;
            } finally {
                activeTransferRound.compareAndSet(round, null);
            }
        }

        @Override
        public boolean verify(EntityTransferCertificate certificate) {
            EntityTransferDescriptor descriptor = certificate.descriptor();
            RegionLease sourceLease = knownLeases.get(descriptor.sourceRegion());
            RegionLease targetLease = knownLeases.get(descriptor.targetRegion());
            if (sourceLease == null || targetLease == null
                    || !sourceLease.epoch().equals(descriptor.sourceEpoch())
                    || !targetLease.epoch().equals(descriptor.targetEpoch())) {
                return false;
            }
            Set<NodeId> sourceMembers = committeeMembers(sourceLease);
            Set<NodeId> targetMembers = committeeMembers(targetLease);
            Map<NodeId, Bytes> sourceKeys = new java.util.HashMap<>();
            Map<NodeId, Bytes> targetKeys = new java.util.HashMap<>();
            for (NodeId member : sourceMembers) {
                Bytes key = publicKey(member);
                if (key != null) {
                    sourceKeys.put(member, key);
                }
            }
            for (NodeId member : targetMembers) {
                Bytes key = publicKey(member);
                if (key != null) {
                    targetKeys.put(member, key);
                }
            }
            int sourceRequired = MajorityQuorumPolicy.requiredForMajority(sourceMembers.size());
            int targetRequired = MajorityQuorumPolicy.requiredForMajority(targetMembers.size());
            return certificate.sourceProof().votes().stream()
                    .allMatch(vote -> sourceMembers.contains(vote.voter()))
                    && certificate.targetProof().votes().stream()
                    .allMatch(vote -> targetMembers.contains(vote.voter()))
                    && certificate.verify(
                    sourceKeys, sourceRequired, targetKeys, targetRequired);
        }
    }

    private static EntityTransferCoordinator.TransferPlan transferPlan(
            EntityTransferDescriptor descriptor,
            RegionDelta sourceDelta,
            RegionDelta targetDelta) {
        EntityMutation sourceMutation = sourceDelta.entityMutations().stream()
                .filter(mutation -> mutation.id().equals(descriptor.entityId())
                        && mutation.expectedPrevious() != null && mutation.newState() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "transfer source removal is missing"));
        EntityMutation targetMutation = targetDelta.entityMutations().stream()
                .filter(mutation -> mutation.id().equals(descriptor.entityId())
                        && mutation.expectedPrevious() == null && mutation.newState() != null)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "transfer target creation is missing"));
        PersistedEntityState sourceState = sourceMutation.expectedPrevious();
        PersistedEntityState targetState = targetMutation.newState();
        return new EntityTransferCoordinator.TransferPlan(
                descriptor, sourceDelta, targetDelta,
                new EntityTransferPreparedEvent(
                        descriptor.transferId(), descriptor.targetRegion(), sourceState),
                new EntityTransferPreparedEvent(
                        descriptor.transferId(), descriptor.sourceRegion(), targetState),
                new EntityTransferAcceptedEvent(
                        descriptor.transferId(), descriptor.targetRegion(), descriptor.entityId()),
                new EntityTransferAcceptedEvent(
                        descriptor.transferId(), descriptor.sourceRegion(), descriptor.entityId()),
                new EntityTransferCommittedEvent(
                        descriptor.transferId(), descriptor.targetRegion(), descriptor.entityId()),
                new EntityTransferCommittedEvent(
                        descriptor.transferId(), descriptor.sourceRegion(), descriptor.entityId()));
    }

    /** The router's Phase-4 soak metrics (committee-commit ratio). */
    public SoakMetrics soakMetrics() {
        return router.metrics();
    }

    /** Live counters for the worker STATE telemetry. */
    /** @return how many disagreeing re-executions this node has observed (issue #5). */
    public long divergences() {
        return divergences.get();
    }

    public Snapshot snapshot() {
        return new Snapshot(replicas.size(), proposalsSent.get(), votesCast.get(),
                votesReceived.get(), committeeCommits.get(), fallbackCommits.get(),
                divergences.get());
    }

    /** Immutable counter snapshot. */
    public record Snapshot(int activeRegions, long proposalsSent, long votesCast,
                           long votesReceived, long committeeCommits, long fallbackCommits,
                           long divergences) {
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
