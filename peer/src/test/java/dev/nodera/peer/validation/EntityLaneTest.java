package dev.nodera.peer.validation;

import dev.nodera.coordinator.InMemoryWorldView;
import dev.nodera.coordinator.RegionPipeline;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.coordinator.entity.EntityTransferCoordinator;
import dev.nodera.coordinator.entity.JointTransferApprover;
import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.consensuscert.EntityTransferCertificate;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.ServerAuthorityCertificate;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.event.EntityTransferAcceptedEvent;
import dev.nodera.core.event.EntityTransferCommittedEvent;
import dev.nodera.core.event.EntityTransferPreparedEvent;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.PlayerView;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.EntityMutation;
import dev.nodera.core.state.EntityTransferDescriptor;
import dev.nodera.core.state.EntityTransferIntent;
import dev.nodera.core.state.EntityTransferRecord;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.entity.ItemEntityRules;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.storage.GenesisManifest;
import dev.nodera.storage.event.EventReplayer;
import dev.nodera.storage.event.EventSourcedWorldStore;
import dev.nodera.storage.rocksdb.RocksWorldStore;
import dev.nodera.testkit.FakeRegion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The entity lane on a validating peer: how it starts, how it comes back, and what a crash in the
 * middle of a transfer leaves behind.
 *
 * <p>Three sibling classes over one subject, all three built on a world store, a genesis manifest
 * and an entity-lane activation. They were three files whose import blocks were the same dozen
 * lines; the imports move here and the fixtures stay per-nest, because "never started", "resumed"
 * and "crashed mid-transfer" are three different starting states and a shared one would have been a
 * default quietly settling that.
 *
 * <p>Each nest keeps the class Javadoc naming what it was written from, and JUnit reports every
 * {@code @Nested @Test} individually, so the count this file contributes is unchanged.
 */
final class EntityLaneTest {

    @Nested
    final class EntityLaneBootstrapTest {
        private static final HashService HASHES = new HashService();

        private static final NodeId NODE_A =
                new NodeId(new UUID(0x0000000000000001L, 0x0000000000000001L));
        private static final NodeId NODE_B =
                new NodeId(new UUID(0x0000000000000002L, 0x0000000000000002L));

        @Test
        void genesisIsDeterministicAndPinnedToFlatWorldRules() {
            GenesisManifest first = EntityLaneBootstrap.genesis(42L, HASHES);
            GenesisManifest second = EntityLaneBootstrap.genesis(42L, HASHES);

            assertThat(first).isEqualTo(second);
            assertThat(first.worldSeed()).isEqualTo(42L);
            assertThat(first.rulesVersion()).isEqualTo(FlatWorldRules.RULES_VERSION);
            assertThat(first.registryFingerprint()).isEqualTo(FlatWorldRules.registryFingerprint());

            CanonicalWriter w1 = new CanonicalWriter();
            first.encode(w1);
            CanonicalWriter w2 = new CanonicalWriter();
            second.encode(w2);
            assertThat(w1.toBytes()).isEqualTo(w2.toBytes());
        }

        @Test
        void genesisRootChangesWithTheSeed() {
            assertThat(EntityLaneBootstrap.genesis(1L, HASHES).genesisRoot())
                    .isNotEqualTo(EntityLaneBootstrap.genesis(2L, HASHES).genesisRoot());
        }

        @Test
        void genesisRejectsNullHashService() {
            assertThatThrownBy(() -> EntityLaneBootstrap.genesis(1L, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void initialSnapshotMatchesTheCanonicalFlatFixture() {
            RegionId region = FakeRegion.overworldRegion(3, -2);

            assertThat(EntityLaneBootstrap.initialSnapshot(region))
                    .isEqualTo(FakeRegion.emptyFlatSnapshot(region, SnapshotVersion.INITIAL, 0L));
        }

        @Test
        void initialSnapshotRejectsNullRegion() {
            assertThatThrownBy(() -> EntityLaneBootstrap.initialSnapshot(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void planMarksLocallyPrimaryRegionsAndBuildsFirstEpochLeases() {
            PlayerView view = PlayerView.fromBlock(DimensionKey.overworld(), 0, 0, 8);
            List<EntityLaneBootstrap.PlannedRegion> plan = EntityLaneBootstrap.plan(
                    Map.of(NODE_A, view), NODE_A, 100L, NoderaConstants.QUORUM_MVP_SIZE);

            assertThat(plan).isNotEmpty();
            for (EntityLaneBootstrap.PlannedRegion planned : plan) {
                assertThat(planned.locallyPrimary()).isTrue();
                assertThat(planned.lease().region()).isEqualTo(planned.region());
                assertThat(planned.lease().epoch()).isEqualTo(new RegionEpoch(1));
                assertThat(planned.lease().primary()).isEqualTo(NODE_A);
                assertThat(planned.lease().validators()).isEmpty();
                assertThat(planned.lease().validFromTick()).isEqualTo(100L);
                assertThat(planned.lease().expiresAtTick())
                        .isEqualTo(100L + NoderaConstants.LEASE_LENGTH_TICKS);
            }
        }

        @Test
        void planOverlappingViewsFormCommitteesWithTheCloserNodePrimary() {
            // NODE_A stands at the origin; NODE_B one region east (chunk 8) — B's disc still covers the
            // origin region, but A is closer to its centre (chunk 3.5, 3.5), so A is primary there.
            PlayerView near = PlayerView.fromBlock(DimensionKey.overworld(), 0, 0, 8);
            PlayerView far = PlayerView.fromBlock(DimensionKey.overworld(), 128, 0, 8);
            List<EntityLaneBootstrap.PlannedRegion> plan = EntityLaneBootstrap.plan(
                    Map.of(NODE_A, near, NODE_B, far), NODE_B, 0L, NoderaConstants.QUORUM_MVP_SIZE);

            RegionId origin = near.centerRegion();
            EntityLaneBootstrap.PlannedRegion originPlan = plan.stream()
                    .filter(p -> p.region().equals(origin))
                    .findFirst().orElseThrow();
            assertThat(originPlan.lease().primary()).isEqualTo(NODE_A);
            assertThat(originPlan.lease().validators()).containsExactly(NODE_B);
            assertThat(originPlan.locallyPrimary()).isFalse();

            // The plan is deterministic regardless of map iteration order.
            assertThat(EntityLaneBootstrap.plan(
                    Map.of(NODE_B, far, NODE_A, near), NODE_B, 0L, NoderaConstants.QUORUM_MVP_SIZE))
                    .isEqualTo(plan);
        }

        @Test
        void planRejectsNullArguments() {
            assertThatThrownBy(() -> EntityLaneBootstrap.plan(null, NODE_A, 0L, 3))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> EntityLaneBootstrap.plan(Map.of(), null, 0L, 3))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void plannedRegionRejectsMismatchedLease() {
            List<EntityLaneBootstrap.PlannedRegion> plan = EntityLaneBootstrap.plan(
                    Map.of(NODE_A, PlayerView.fromBlock(DimensionKey.overworld(), 0, 0, 8)),
                    NODE_A, 0L, 3);
            RegionId other = FakeRegion.overworldRegion(1_000, 1_000);
            assertThatThrownBy(() -> new EntityLaneBootstrap.PlannedRegion(
                    other, plan.get(0).lease(), true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * Reopen-resume proof for issue #34 / L-50: the store head — quorum-committed or
     * external-committed — survives a journal restart and wins over the INITIAL genesis snapshot.
     */
    @Nested
    final class EntityLaneResumeTest {
        private static final HashService HASHES = new HashService();
        private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);

        @TempDir
        Path dir;

        @Test
        void externalHeadSurvivesJournalRestart() {
            EventSourcedWorldStore store = store();
            Path file = dir.resolve("external-heads.bin");
            RegionSnapshot snapshot = snapshotAt(2);
            ServerAuthorityCertificate certificate = externalCertificate(snapshot);
            new WorldStoreExternalHeads(store, HASHES, file)
                    .externalCommitted(snapshot, certificate);

            WorldStoreExternalHeads restarted = new WorldStoreExternalHeads(store, HASHES, file);

            assertThat(restarted.head(REGION)).contains(snapshot);
            assertThat(restarted.headVersion(REGION)).contains(new SnapshotVersion(2));
        }

        @Test
        void externalHeadRejectsSnapshotCertificateMismatch() {
            EventSourcedWorldStore store = store();
            WorldStoreExternalHeads heads = new WorldStoreExternalHeads(
                    store, HASHES, dir.resolve("external-heads.bin"));
            RegionSnapshot snapshot = snapshotAt(2);
            ServerAuthorityCertificate wrong = externalCertificate(snapshotAt(3));

            assertThatThrownBy(() -> heads.externalCommitted(snapshot, wrong))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match");
        }

        @Test
        void laterExternalCommitSupersedesEarlierHead() {
            EventSourcedWorldStore store = store();
            WorldStoreExternalHeads heads = new WorldStoreExternalHeads(
                    store, HASHES, dir.resolve("external-heads.bin"));
            heads.externalCommitted(snapshotAt(2), externalCertificate(snapshotAt(2)));
            heads.externalCommitted(snapshotAt(5), externalCertificate(snapshotAt(5)));

            assertThat(heads.headVersion(REGION)).contains(new SnapshotVersion(5));
        }

        @Test
        void quorumCommittedSnapshotIsResumableAfterRestart() {
            EventSourcedWorldStore store = store();
            Path file = dir.resolve("votes.bin");
            RegionExecutionRequest request = request();
            RegionExecutionResult result = engine().execute(request);
            WorldStoreVotePersistence votes = new WorldStoreVotePersistence(store, HASHES, file);
            votes.prepare(request, result);
            votes.commit(certificate(request, result));

            WorldStoreVotePersistence restarted = new WorldStoreVotePersistence(store, HASHES, file);

            assertThat(restarted.latestCommittedSnapshot(REGION))
                    .hasValueSatisfying(snapshot -> {
                        assertThat(snapshot.version()).isEqualTo(SnapshotVersion.INITIAL.next());
                        assertThat(StateRoot.of(HASHES.hash(snapshot)))
                                .isEqualTo(result.resultingRoot());
                    });
        }

        @Test
        void preparedButUncommittedCandidateIsNotResumable() {
            EventSourcedWorldStore store = store();
            WorldStoreVotePersistence votes = new WorldStoreVotePersistence(
                    store, HASHES, dir.resolve("votes.bin"));
            RegionExecutionRequest request = request();
            votes.prepare(request, engine().execute(request));

            assertThat(votes.latestCommittedSnapshot(REGION)).isEmpty();
        }

        @Test
        void resumeHeadPicksHighestVersionAcrossBothSources() {
            EventSourcedWorldStore store = store();
            WorldStoreVotePersistence votes = new WorldStoreVotePersistence(
                    store, HASHES, dir.resolve("votes.bin"));
            WorldStoreExternalHeads externals = new WorldStoreExternalHeads(
                    store, HASHES, dir.resolve("external-heads.bin"));
            RegionExecutionRequest request = request();
            RegionExecutionResult result = engine().execute(request);
            votes.prepare(request, result);
            votes.commit(certificate(request, result));

            // Quorum head is v1; an external commit at v4 must win.
            externals.externalCommitted(snapshotAt(4), externalCertificate(snapshotAt(4)));
            assertThat(EntityLaneResume.resumeHead(votes, externals, REGION))
                    .hasValueSatisfying(snapshot ->
                            assertThat(snapshot.version()).isEqualTo(new SnapshotVersion(4)));
        }

        @Test
        void resumeHeadEmptyWhenNothingEverCommitted() {
            EventSourcedWorldStore store = store();
            WorldStoreVotePersistence votes = new WorldStoreVotePersistence(
                    store, HASHES, dir.resolve("votes.bin"));
            WorldStoreExternalHeads externals = new WorldStoreExternalHeads(
                    store, HASHES, dir.resolve("external-heads.bin"));

            assertThat(EntityLaneResume.resumeHead(votes, externals, REGION)).isEmpty();
        }

        private static EventSourcedWorldStore store() {
            return new EventSourcedWorldStore(
                    new GenesisManifest(1, FlatWorldRules.RULES_VERSION,
                            FlatWorldRules.registryFingerprint(), StateRoot.zero()), HASHES);
        }

        private static FlatWorldRegionEngine engine() {
            return new FlatWorldRegionEngine(
                    FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), HASHES);
        }

        private static RegionSnapshot snapshotAt(long version) {
            return new RegionSnapshot(REGION, new SnapshotVersion(version), version, List.of());
        }

        private static ServerAuthorityCertificate externalCertificate(RegionSnapshot snapshot) {
            return new ServerAuthorityCertificate(
                    snapshot.region(),
                    new SnapshotVersion(snapshot.version().value() - 1),
                    snapshot.version(),
                    StateRoot.of(HASHES.hash(snapshot)),
                    StateRoot.zero(),
                    ServerAuthorityCertificate.Reason.EXTERNAL_MUTATION,
                    Bytes.empty());
        }

        private static RegionExecutionRequest request() {
            RegionSnapshot snapshot = new RegionSnapshot(
                    REGION, SnapshotVersion.INITIAL, 0, List.of());
            ActionBatch batch = new ActionBatch(
                    REGION, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 1, 1, List.of());
            return new RegionExecutionRequest(new RegionExecutionContext(
                    REGION, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 1, 1,
                    1, FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint()),
                    snapshot, batch);
        }

        private static QuorumCertificate certificate(
                RegionExecutionRequest request, RegionExecutionResult result) {
            StateRoot batchRoot = StateRoot.of(HASHES.hash(request.batch()));
            StateRoot transitionRoot = StateRoot.of(HASHES.hash(result.delta()));
            SignedVote first = new SignedVote(
                    new NodeId(new UUID(0, 1)), REGION, RegionEpoch.INITIAL,
                    SnapshotVersion.INITIAL, batchRoot, result.resultingRoot(), transitionRoot,
                    VoteDecision.ACCEPT, Bytes.empty());
            SignedVote second = new SignedVote(
                    new NodeId(new UUID(0, 2)), REGION, RegionEpoch.INITIAL,
                    SnapshotVersion.INITIAL, batchRoot, result.resultingRoot(), transitionRoot,
                    VoteDecision.ACCEPT, Bytes.empty());
            return new QuorumCertificate(
                    REGION, RegionEpoch.INITIAL, SnapshotVersion.INITIAL,
                    StateRoot.of(HASHES.hash(request.snapshot())), result.resultingRoot(),
                    List.of(first, second));
        }
    }

    /** Invariant 11: process death after durable prepare cannot duplicate or lose transferred entity. */
    @Nested
    final class EntityTransferCrashRecoveryIT {
        private static final HashService HASHES = new HashService();
        private static final RegionId SOURCE = new RegionId(DimensionKey.overworld(), 0, 0);
        private static final RegionId TARGET = new RegionId(DimensionKey.overworld(), 1, 0);
        private static final GenesisManifest GENESIS = new GenesisManifest(
                12L, 1, 99L, StateRoot.of(HASHES.sha256("entity-transfer-crash".getBytes())));

        @TempDir
        Path dir;

        @Test
        void killedAfterPrepareResumesToOneEntityAndOnePairedHistory() throws Exception {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process victim = new ProcessBuilder(
                    java,
                    "--enable-native-access=ALL-UNNAMED",
                    "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                    "-cp", System.getProperty("java.class.path"),
                    PreparedVictim.class.getName(), dir.toString())
                    .redirectErrorStream(true)
                    .start();
            BufferedReader output = new BufferedReader(new InputStreamReader(victim.getInputStream()));
            String line;
            int guard = 0;
            while ((line = output.readLine()) != null && !line.equals("READY")) {
                assertThat(++guard).as("victim output before READY: %s", line).isLessThan(50);
            }
            assertThat(line).isEqualTo("READY");
            victim.destroyForcibly();
            assertThat(victim.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(victim.exitValue()).isNotZero();

            TransferFixture fixture = fixture();
            InMemoryWorldView world = new InMemoryWorldView();
            world.load(fixture.source());
            world.load(fixture.target());
            JointTransferApprover approvals = JointTransferApprover.mvp(members(), members());
            EntityTransferCoordinator.TransferResult recovered;
            try (RocksWorldStore store = open()) {
                WorldStoreTransferJournal journal = new WorldStoreTransferJournal(store);
                assertThat(journal.recoverable()).singleElement().satisfies(pending ->
                        assertThat(pending.stage())
                                .isEqualTo(EntityTransferCoordinator.TransferStage.PREPARED));
                EntityTransferCoordinator coordinator = new EntityTransferCoordinator(
                        new WorldMutationApplier(world), approvals, journal);
                recovered = (EntityTransferCoordinator.TransferResult) coordinator.restorePending(
                        journal.recoverable().getFirst(), active(SOURCE), active(TARGET));

                assertThat(recovered.replay()).isFalse();
                assertThat(recovered.applyResult().applied()).isEqualTo(2);
                assertThat(world.getEntity(SOURCE, fixture.sourceEntity().id())).isNull();
                assertThat(world.getEntity(TARGET, fixture.sourceEntity().id()))
                        .isEqualTo(fixture.targetEntity());
                assertThat(journal.recoverable()).isEmpty();
                assertThat(store.events().readFrom(SOURCE, 0)).hasSize(3);
                assertThat(store.events().readFrom(TARGET, 0)).hasSize(3);
            }

            try (RocksWorldStore store = open()) {
                WorldStoreTransferJournal journal = new WorldStoreTransferJournal(store);
                assertThat(journal.completed()).hasSize(1);
                EntityTransferCoordinator restarted = new EntityTransferCoordinator(
                        new WorldMutationApplier(world), approvals,
                        EntityTransferCoordinator.TransferJournal.NOOP);
                restarted.restoreCompleted(journal.completed().getFirst());
                restarted.restoreCompleted(journal.completed().getFirst());
                assertThat(world.getEntity(SOURCE, fixture.sourceEntity().id())).isNull();
                assertThat(world.getEntity(TARGET, fixture.sourceEntity().id()))
                        .isEqualTo(fixture.targetEntity());
                assertThat(store.events().readFrom(SOURCE, 0)).hasSize(3);
                assertThat(store.events().readFrom(TARGET, 0)).hasSize(3);
            }
        }

        private RocksWorldStore open() {
            return RocksWorldStore.open(dir, GENESIS, HASHES, false);
        }

        private static RegionPipeline active(RegionId region) {
            RegionPipeline pipeline = new RegionPipeline(region);
            pipeline.assign(RegionEpoch.INITIAL);
            pipeline.snapshotSynced();
            return pipeline;
        }

        private static List<NodeIdentity> members() {
            return List.of(NodeIdentity.generate(), NodeIdentity.generate(), NodeIdentity.generate());
        }

        private static TransferFixture fixture() {
            NetworkEntityId id = new NetworkEntityId(77);
            PersistedEntityState sourceEntity = new PersistedEntityState(
                    id, EntityKind.ITEM, 42, FixedVec3.ofBlock(127, 5, 1), FixedVec3.ZERO,
                    10, ItemEntityRules.DESPAWN_AGE_TICKS, ItemEntityRules.payload(42, 3));
            PersistedEntityState targetEntity = new PersistedEntityState(
                    id, EntityKind.ITEM, 42, FixedVec3.ofBlock(128, 5, 1), FixedVec3.ZERO,
                    11, ItemEntityRules.DESPAWN_AGE_TICKS, ItemEntityRules.payload(42, 3));
            RegionSnapshot source = new RegionSnapshot(
                    SOURCE, SnapshotVersion.INITIAL, 10, List.of(), List.of(sourceEntity));
            RegionSnapshot target = new RegionSnapshot(
                    TARGET, SnapshotVersion.INITIAL, 10, List.of(), List.of());
            SnapshotVersion next = SnapshotVersion.INITIAL.next();
            RegionSnapshot sourceAfter = new RegionSnapshot(SOURCE, next, 11, List.of(), List.of());
            RegionSnapshot targetAfter = new RegionSnapshot(
                    TARGET, next, 11, List.of(), List.of(targetEntity));
            RegionDelta sourceDelta = new RegionDelta(
                    SOURCE, SnapshotVersion.INITIAL, next, List.of(),
                    StateRoot.of(HASHES.hash(sourceAfter)),
                    List.of(new EntityMutation(id, sourceEntity, null)), List.of(),
                    List.of(new EntityTransferIntent(TARGET, targetEntity)));
            RegionDelta targetDelta = new RegionDelta(
                    TARGET, SnapshotVersion.INITIAL, next, List.of(),
                    StateRoot.of(HASHES.hash(targetAfter)),
                    List.of(new EntityMutation(id, null, targetEntity)), List.of());
            EntityTransferDescriptor descriptor = new EntityTransferDescriptor(
                    12L, SOURCE, TARGET, RegionEpoch.INITIAL, RegionEpoch.INITIAL, id,
                    SnapshotVersion.INITIAL, next, StateRoot.of(HASHES.hash(source)),
                    sourceDelta.resultingRoot(), StateRoot.of(HASHES.hash(sourceDelta)),
                    SnapshotVersion.INITIAL, next, StateRoot.of(HASHES.hash(target)),
                    targetDelta.resultingRoot(), StateRoot.of(HASHES.hash(targetDelta)), 11);
            EntityTransferCoordinator.TransferPlan plan = new EntityTransferCoordinator.TransferPlan(
                    descriptor, sourceDelta, targetDelta,
                    new EntityTransferPreparedEvent(12L, TARGET, sourceEntity),
                    new EntityTransferPreparedEvent(12L, SOURCE, targetEntity),
                    new EntityTransferAcceptedEvent(12L, TARGET, id),
                    new EntityTransferAcceptedEvent(12L, SOURCE, id),
                    new EntityTransferCommittedEvent(12L, TARGET, id),
                    new EntityTransferCommittedEvent(12L, SOURCE, id));
            return new TransferFixture(source, target, sourceEntity, targetEntity, plan);
        }

        private record TransferFixture(
                RegionSnapshot source,
                RegionSnapshot target,
                PersistedEntityState sourceEntity,
                PersistedEntityState targetEntity,
                EntityTransferCoordinator.TransferPlan plan) {
        }

        /** Victim persists PREPARED, then waits to be killed without closing RocksDB. */
        public static final class PreparedVictim {
            private PreparedVictim() {
            }

            public static void main(String[] args) throws Exception {
                RocksWorldStore store = RocksWorldStore.open(Path.of(args[0]), GENESIS, HASHES, false);
                new WorldStoreTransferJournal(store).prepared(fixture().plan());
                System.out.println("READY");
                System.out.flush();
                Thread.sleep(Long.MAX_VALUE);
            }
        }
    }

    @Nested
    final class WorldStoreTransferJournalTest {
        private static final HashService HASHES = new HashService();
        private static final RegionId SOURCE = new RegionId(DimensionKey.overworld(), 0, 0);
        private static final RegionId TARGET = new RegionId(DimensionKey.overworld(), 1, 0);

        private EventSourcedWorldStore store;
        private WorldStoreTransferJournal journal;
        private EntityTransferCoordinator.TransferPlan plan;
        private EntityTransferCertificate certificate;

        @BeforeEach
        void setUp() {
            plan = plan();
            store = new EventSourcedWorldStore(
                    new GenesisManifest(1, 1, 1, plan.descriptor().sourcePrevRoot()), HASHES);
            journal = new WorldStoreTransferJournal(store);
            JointTransferApprover approvals = JointTransferApprover.mvp(members(), members());
            certificate = approvals.approve(
                    plan.descriptor(), plan.sourceDelta(), plan.targetDelta());
        }

        @Test
        void commitPersistsStagesCertificateAndPairedRegionHistories() {
            journal.prepared(plan);
            journal.accepted(plan, certificate);
            journal.applied(plan, certificate);
            journal.committed(plan, certificate);

            assertThat(store.transfers().get(7).orElseThrow().stage())
                    .isEqualTo(EntityTransferRecord.Stage.COMMITTED);
            assertThat(store.events().readFrom(SOURCE, 0)).hasSize(3);
            assertThat(store.events().readFrom(TARGET, 0)).hasSize(3);
            Bytes certificateRef = store.events().readFrom(SOURCE, 0).getFirst().certificateRef();
            assertThat(store.certificates().getTransferByHash(certificateRef)).contains(certificate);
            assertThat(EventReplayer.replay(
                    store.events(), store.certificates(), SOURCE,
                    plan.descriptor().sourcePrevRoot(), 0).finalRoot())
                    .isEqualTo(plan.descriptor().sourceResultingRoot());
            assertThat(EventReplayer.replay(
                    store.events(), store.certificates(), TARGET,
                    plan.descriptor().targetPrevRoot(), 0).finalRoot())
                    .isEqualTo(plan.descriptor().targetResultingRoot());
            assertThat(journal.completed()).hasSize(1);

            journal.committed(plan, certificate);
            assertThat(store.events().readFrom(SOURCE, 0)).hasSize(3);
            assertThat(store.events().readFrom(TARGET, 0)).hasSize(3);
        }

        @Test
        void startupReturnsLatestNonTerminalStageOnly() {
            journal.prepared(plan);
            journal.accepted(plan, certificate);
            assertThat(journal.recoverable()).singleElement().satisfies(recovery -> {
                assertThat(recovery.stage())
                        .isEqualTo(EntityTransferCoordinator.TransferStage.ACCEPTED);
                assertThat(recovery.certificate()).isEqualTo(certificate);
                assertThat(recovery.plan()).isEqualTo(plan);
            });
            journal.aborted(7, "ENTITY_CAS");
            assertThat(journal.recoverable()).isEmpty();
        }

        private static EntityTransferCoordinator.TransferPlan plan() {
            PersistedEntityState sourceEntity = entity(FixedVec3.ofBlock(127, 5, 1));
            PersistedEntityState targetEntity = entity(FixedVec3.ofBlock(128, 5, 1));
            SnapshotVersion base = SnapshotVersion.INITIAL;
            SnapshotVersion next = base.next();
            StateRoot sourcePrev = root(1);
            StateRoot sourceResult = root(2);
            StateRoot targetPrev = root(3);
            StateRoot targetResult = root(4);
            RegionDelta sourceDelta = new RegionDelta(
                    SOURCE, base, next, List.of(), sourceResult,
                    List.of(new EntityMutation(sourceEntity.id(), sourceEntity, null)), List.of(),
                    List.of(new EntityTransferIntent(TARGET, targetEntity)));
            RegionDelta targetDelta = new RegionDelta(
                    TARGET, base, next, List.of(), targetResult,
                    List.of(new EntityMutation(targetEntity.id(), null, targetEntity)), List.of());
            EntityTransferDescriptor descriptor = new EntityTransferDescriptor(
                    7, SOURCE, TARGET, new RegionEpoch(1), new RegionEpoch(2), sourceEntity.id(),
                    base, next, sourcePrev, sourceResult, StateRoot.of(HASHES.hash(sourceDelta)),
                    base, next, targetPrev, targetResult, StateRoot.of(HASHES.hash(targetDelta)), 50);
            return new EntityTransferCoordinator.TransferPlan(
                    descriptor, sourceDelta, targetDelta,
                    new EntityTransferPreparedEvent(7, TARGET, sourceEntity),
                    new EntityTransferPreparedEvent(7, SOURCE, targetEntity),
                    new EntityTransferAcceptedEvent(7, TARGET, sourceEntity.id()),
                    new EntityTransferAcceptedEvent(7, SOURCE, sourceEntity.id()),
                    new EntityTransferCommittedEvent(7, TARGET, sourceEntity.id()),
                    new EntityTransferCommittedEvent(7, SOURCE, sourceEntity.id()));
        }

        private static PersistedEntityState entity(FixedVec3 position) {
            return new PersistedEntityState(
                    new NetworkEntityId(9), EntityKind.ITEM, 4, position, FixedVec3.ZERO,
                    2, 6_000, Bytes.unsafeWrap(new byte[]{0, 0, 0, 4, 1}));
        }

        private static List<NodeIdentity> members() {
            return List.of(NodeIdentity.generate(), NodeIdentity.generate(), NodeIdentity.generate());
        }

        private static StateRoot root(int fill) {
            byte[] bytes = new byte[32];
            java.util.Arrays.fill(bytes, (byte) fill);
            return StateRoot.of(Bytes.unsafeWrap(bytes));
        }
    }
}
