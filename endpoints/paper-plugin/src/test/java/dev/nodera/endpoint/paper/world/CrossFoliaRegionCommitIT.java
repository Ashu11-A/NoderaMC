package dev.nodera.endpoint.paper.world;

import dev.nodera.core.consensuscert.EntityTransferCertificate;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
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
import dev.nodera.coordinator.InMemoryWorldView;
import dev.nodera.coordinator.PipelineState;
import dev.nodera.coordinator.RegionPipeline;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.coordinator.entity.EntityTransferCoordinator;
import dev.nodera.coordinator.entity.JointTransferApprover;
import dev.nodera.simulation.entity.ItemEntityRules;
import dev.nodera.storage.event.InMemoryTransferStore;
import dev.nodera.testkit.engine.EngineFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L-64's exit suite: a prepare/commit across two region threads, certified with joint transfer
 * certificates and journalled with durable transfer stages, commits atomically — and a failure on
 * one side commits <b>neither</b>.
 *
 * <h2>What this proves, and what it does not — read this before quoting it as evidence</h2>
 *
 * <p><b>Proved here, on real threads.</b> Two Nodera regions that
 * {@link NoderaFoliaRegionMap#shareExecutionThread} says do <i>not</i> share an execution thread
 * are parked from two genuinely separate threads, committed on a third, and resumed on their own
 * threads again; the certificate carries an independent source and target quorum proof; the durable
 * {@link dev.nodera.storage.TransferStore} walks PREPARED → ACCEPTED → APPLIED → COMMITTED; and two
 * distinct one-sided failures — the target's state moving under the commit, and a region thread
 * never reaching its park point — leave both regions byte-identical to how they started, with
 * neither version advanced.
 *
 * <p><b>Not proved here.</b> That those two threads are <i>Folia's</i>. Folia publishes no build of
 * the Minecraft version the mod pins (docs/server/LIMITATIONS.md L-66), and nothing on the plugin
 * path delegates a region yet (server tasks 2 and 3), so there is no live cross-region delta on a
 * regionised server to drive. The property that separates a Folia thread from an ordinary one is
 * that the regioniser refuses recursive operation, and what this suite therefore asserts in its
 * place is the design consequence: the commit body never runs on a region thread, and no region
 * thread ever waits for another.
 *
 * <p><b>There is no {@code assumeTrue} in this file and there must never be one.</b> A suite that
 * skips itself when an artefact is missing is how five suites in this repository came to assert
 * nothing at all. Everything here runs on every machine.
 */
final class CrossFoliaRegionCommitIT {

    private static final DimensionKey OVERWORLD = DimensionKey.overworld();
    private static final HashService HASHES = new HashService();

    /**
     * Grid exponent 4, and two regions two sections apart.
     *
     * <p>Region (0,0) starts at chunk 0 and region (2,0) at chunk 16, so at 16 chunks per section
     * they are in different Folia sections and — with no ownership probe to say otherwise — do not
     * share a thread. That is precisely the span L-64 says is refused today.
     */
    private static final int GRID_EXPONENT = 4;

    private final RegionId sourceRegion = new RegionId(OVERWORLD, 0, 0);
    private final RegionId targetRegion = new RegionId(OVERWORLD, 2, 0);

    private final InMemoryWorldView world = new InMemoryWorldView();
    private final InMemoryTransferStore transfers = new InMemoryTransferStore();
    private final Map<RegionId, RegionPipeline> pipelines = new HashMap<>();
    private final Map<RegionId, RegionSnapshot> snapshots = new HashMap<>();
    private final Map<RegionId, ExecutorService> regionThreads = new HashMap<>();
    private final ThreadLocal<Boolean> onRegionThread = ThreadLocal.withInitial(() -> false);
    private final Map<RegionId, String> parkedOn = new ConcurrentHashMap<>();
    private final Map<RegionId, String> resumedOn = new ConcurrentHashMap<>();
    private final Map<RegionId, RegionDelta> projected = new ConcurrentHashMap<>();
    private final List<String> log = new ArrayList<>();
    private final AtomicBoolean commitSawRegionThread = new AtomicBoolean(true);
    private final AtomicBoolean parkRefused = new AtomicBoolean();

    private PersistedEntityState sourceEntity;
    private PersistedEntityState targetEntity;
    private RegionDelta certifiedSourceDelta;
    private JointTransferApprover approvals;
    private Runnable duringApproval = () -> { };
    private CrossRegionCommit commit;
    private NoderaFoliaRegionMap regions;

    @BeforeEach
    void setUp() {
        sourceEntity = item(FixedVec3.ofBlock(127, 5, 1), 3);
        targetEntity = new PersistedEntityState(
                sourceEntity.id(), sourceEntity.kind(), sourceEntity.typeId(),
                FixedVec3.ofBlock(256, 5, 1), new FixedVec3(1, -2, 3),
                sourceEntity.ageTicks() + 1, sourceEntity.despawnTick(), sourceEntity.payload());

        RegionSnapshot sourceBlocks = EngineFixtures.fullUniformSnapshot(sourceRegion, 0);
        snapshots.put(sourceRegion, new RegionSnapshot(
                sourceRegion, SnapshotVersion.INITIAL, 0, sourceBlocks.chunks(),
                List.of(sourceEntity)));
        snapshots.put(targetRegion, EngineFixtures.fullUniformSnapshot(targetRegion, 0));
        world.load(snapshots.get(sourceRegion));
        world.load(snapshots.get(targetRegion));
        pipelines.put(sourceRegion, active(sourceRegion));
        pipelines.put(targetRegion, active(targetRegion));
        certifiedSourceDelta = sourceDelta();

        regionThreads.put(sourceRegion, regionThread("region-0-0"));
        regionThreads.put(targetRegion, regionThread("region-2-0"));

        approvals = JointTransferApprover.mvp(members(), members());
        regions = NoderaFoliaRegionMap.regionised(
                GRID_EXPONENT, NoderaFoliaRegionMap.ExecutionOwnership.UNANSWERABLE);
        commit = CrossRegionCommit.joint(
                regions, dispatcher(), authority(), new WorldMutationApplier(world),
                recordingApprovals(), transfers, log::add);
    }

    @AfterEach
    void tearDown() {
        commit.close();
        regionThreads.values().forEach(ExecutorService::shutdownNow);
    }

    // -----------------------------------------------------------------------------------------
    // The span itself
    // -----------------------------------------------------------------------------------------

    @Test
    void theSpanUnderTestIsGenuinelyCrossThreadAndStageOneWouldRefuseIt() {
        assertThat(regions.shareExecutionThread(sourceRegion, targetRegion)).isFalse();
        assertThatThrownBy(() -> CrossRegionCommit.refusing(regions, transfers)
                .requireJointCriticalSection(1L, sourceRegion, targetRegion))
                .isInstanceOf(CrossRegionRefusedException.class);
        assertThat(transfers.all()).isEmpty();
    }

    // -----------------------------------------------------------------------------------------
    // The happy path
    // -----------------------------------------------------------------------------------------

    @Test
    void jointTransferAcrossTwoRegionThreadsCommitsAtomically() throws Exception {
        EntityTransferCoordinator.TransferOutcome outcome = await(commit.commit(
                77L, sourceRegion, targetRegion, certifiedSourceDelta, 0L, Duration.ofSeconds(20)));

        assertThat(outcome).isInstanceOf(EntityTransferCoordinator.TransferResult.class);
        EntityTransferCoordinator.TransferResult result =
                (EntityTransferCoordinator.TransferResult) outcome;

        // Both regions moved, in one paired compare-and-set.
        assertThat(world.getEntity(sourceRegion, sourceEntity.id())).isNull();
        assertThat(world.getEntity(targetRegion, sourceEntity.id())).isEqualTo(targetEntity);
        assertThat(pipelines.get(sourceRegion).lastCommittedVersion())
                .isEqualTo(new SnapshotVersion(1));
        assertThat(pipelines.get(targetRegion).lastCommittedVersion())
                .isEqualTo(new SnapshotVersion(1));

        // Two independent committee proofs over the one descriptor — the joint certificate.
        EntityTransferCertificate certificate = result.certificate();
        assertThat(approvals.verify(certificate)).isTrue();
        assertThat(certificate.sourceProof().region()).isEqualTo(sourceRegion);
        assertThat(certificate.targetProof().region()).isEqualTo(targetRegion);
        assertThat(certificate.sourceProof().voteCount()).isEqualTo(3);
        assertThat(certificate.targetProof().voteCount()).isEqualTo(3);
        assertThat(certificate.sourceProof().resultingRoot())
                .isNotEqualTo(certificate.targetProof().resultingRoot());

        // Durable transfer stages: the second half of the exit clause.
        EntityTransferRecord record = transfers.get(77L).orElseThrow();
        assertThat(record.stage()).isEqualTo(EntityTransferRecord.Stage.COMMITTED);
        assertThat(record.certificate()).isEqualTo(certificate);
        assertThat(transfers.recoverable()).isEmpty();

        // Each region parked and resumed on its OWN thread, and the commit ran on neither.
        assertThat(parkedOn.get(sourceRegion)).isEqualTo("region-0-0");
        assertThat(parkedOn.get(targetRegion)).isEqualTo("region-2-0");
        awaitResume(sourceRegion, targetRegion);
        assertThat(resumedOn.get(sourceRegion)).isEqualTo("region-0-0");
        assertThat(resumedOn.get(targetRegion)).isEqualTo("region-2-0");
        assertThat(commitSawRegionThread).isFalse();

        // Each thread projected its own certified delta and nothing else.
        assertThat(projected.get(sourceRegion)).isEqualTo(result.sourceDelta());
        assertThat(projected.get(targetRegion)).isEqualTo(result.targetDelta());
        assertThat(log).anyMatch(line -> line.contains("committed on both threads"));
    }

    // -----------------------------------------------------------------------------------------
    // A failure on one side commits NEITHER
    // -----------------------------------------------------------------------------------------

    @Test
    void targetStateMovingUnderTheCommitCommitsNeitherSide() throws Exception {
        // Between certification and the paired CAS, the target region acquires the entity id from
        // somewhere else — the one-sided failure the joint path exists to survive.
        duringApproval = () -> world.setEntity(targetRegion, item(FixedVec3.ofBlock(300, 5, 1), 9));

        EntityTransferCoordinator.TransferOutcome outcome = await(commit.commit(
                88L, sourceRegion, targetRegion, certifiedSourceDelta, 0L, Duration.ofSeconds(20)));

        assertThat(outcome).isInstanceOf(EntityTransferCoordinator.TransferFailure.class);
        assertThat(((EntityTransferCoordinator.TransferFailure) outcome).applyResult().committed())
                .isFalse();

        // NEITHER side committed: the source still holds the entity and no version advanced.
        assertThat(world.getEntity(sourceRegion, sourceEntity.id())).isEqualTo(sourceEntity);
        assertThat(pipelines.get(sourceRegion).lastCommittedVersion())
                .isEqualTo(SnapshotVersion.INITIAL);
        assertThat(pipelines.get(targetRegion).lastCommittedVersion())
                .isEqualTo(SnapshotVersion.INITIAL);
        assertThat(projected).isEmpty();

        // The durable stages record the attempt and its termination, not a half-commit.
        EntityTransferRecord record = transfers.get(88L).orElseThrow();
        assertThat(record.stage()).isEqualTo(EntityTransferRecord.Stage.ABORTED);
        assertThat(record.failure()).isNotBlank();
        assertThat(transfers.recoverable()).isEmpty();

        // Both regions were handed their authority back, so both keep ticking.
        awaitResume(sourceRegion, targetRegion);
        assertThat(pipelines.get(sourceRegion).state()).isEqualTo(PipelineState.ACTIVE);
        assertThat(pipelines.get(targetRegion).state()).isEqualTo(PipelineState.ACTIVE);
        assertThat(log).anyMatch(line -> line.contains("NEITHER region advanced"));
    }

    @Test
    void aRegionThreadThatNeverParksAbandonsTheAttemptAndFreesTheOther() {
        parkRefused.set(true);

        CompletableFuture<EntityTransferCoordinator.TransferOutcome> attempt = commit.commit(
                99L, sourceRegion, targetRegion, certifiedSourceDelta, 0L, Duration.ofSeconds(20));

        assertThatThrownBy(() -> attempt.get(20, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("target region is not delegated here");

        // Nothing was ever journalled: the refusal happened before PREPARED.
        assertThat(transfers.all()).isEmpty();
        assertThat(world.getEntity(sourceRegion, sourceEntity.id())).isEqualTo(sourceEntity);
        assertThat(world.getEntity(targetRegion, sourceEntity.id())).isNull();
        assertThat(projected).isEmpty();

        // BOTH sides are resumed, not just the one that parked. Resuming only the parked side would
        // leave a region wedged whenever the failure is the park timeout rather than a refusal: the
        // other region's park task can still be queued and would pause it a moment later.
        awaitResume(sourceRegion, targetRegion);
        assertThat(resumedOn).containsOnlyKeys(sourceRegion, targetRegion);
        assertThat(pipelines.get(sourceRegion).state()).isEqualTo(PipelineState.ACTIVE);
        assertThat(pipelines.get(targetRegion).state()).isEqualTo(PipelineState.ACTIVE);
        assertThat(log).anyMatch(line -> line.contains("resumed untouched"));
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    private CrossRegionCommit.RegionDispatcher dispatcher() {
        return new CrossRegionCommit.RegionDispatcher() {
            @Override
            public void execute(RegionId region, Runnable task) {
                regionThreads.get(region).execute(task);
            }

            @Override
            public boolean onRegionThread() {
                return onRegionThread.get();
            }
        };
    }

    private CrossRegionCommit.RegionAuthority authority() {
        return new CrossRegionCommit.RegionAuthority() {
            @Override
            public CrossRegionCommit.RegionPark park(RegionId region) {
                if (parkRefused.get() && region.equals(targetRegion)) {
                    throw new IllegalStateException("target region is not delegated here");
                }
                parkedOn.put(region, Thread.currentThread().getName());
                return new CrossRegionCommit.RegionPark(
                        pipelines.get(region), snapshots.get(region));
            }

            @Override
            public void resume(RegionId region, RegionDelta certified) {
                if (certified != null) {
                    projected.put(region, certified);
                }
                RegionPipeline pipeline = pipelines.get(region);
                if (pipeline.state() == PipelineState.PAUSED_FOR_XR) {
                    pipeline.crossRegionAborted();
                }
                resumedOn.put(region, Thread.currentThread().getName());
            }
        };
    }

    /**
     * The approvals provider, wrapped so the suite can observe which thread the commit body runs on
     * and can perturb the world at the exact point between certification and the paired CAS.
     */
    private EntityTransferCoordinator.TransferApprovalProvider recordingApprovals() {
        return new EntityTransferCoordinator.TransferApprovalProvider() {
            @Override
            public EntityTransferCertificate approve(
                    EntityTransferDescriptor descriptor, RegionDelta source, RegionDelta target) {
                commitSawRegionThread.set(onRegionThread.get());
                EntityTransferCertificate certificate =
                        approvals.approve(descriptor, source, target);
                duringApproval.run();
                return certificate;
            }

            @Override
            public boolean verify(EntityTransferCertificate certificate) {
                return approvals.verify(certificate);
            }
        };
    }

    private ExecutorService regionThread(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(() -> {
                onRegionThread.set(true);
                runnable.run();
            }, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static EntityTransferCoordinator.TransferOutcome await(
            CompletableFuture<EntityTransferCoordinator.TransferOutcome> attempt) throws Exception {
        return attempt.get(30, TimeUnit.SECONDS);
    }

    /** Resume is fire-and-forget on a region thread, so the assertion has to let it land. */
    private void awaitResume(RegionId... wanted) {
        for (RegionId region : wanted) {
            try {
                regionThreads.get(region).submit(() -> null).get(20, TimeUnit.SECONDS);
            } catch (Exception interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("region thread never drained", interrupted);
            }
        }
    }

    private RegionDelta sourceDelta() {
        RegionSnapshot base = snapshots.get(sourceRegion);
        RegionSnapshot after = new RegionSnapshot(
                sourceRegion, base.version().next(), 0, base.chunks(), List.of());
        return new RegionDelta(
                sourceRegion, base.version(), base.version().next(), List.of(),
                StateRoot.of(HASHES.hash(after)),
                List.of(new EntityMutation(sourceEntity.id(), sourceEntity, null)), List.of(),
                List.of(new EntityTransferIntent(targetRegion, targetEntity)));
    }

    private static RegionPipeline active(RegionId region) {
        RegionPipeline pipeline = new RegionPipeline(region);
        pipeline.assign(new RegionEpoch(1));
        pipeline.snapshotSynced();
        return pipeline;
    }

    private static PersistedEntityState item(FixedVec3 position, int count) {
        return new PersistedEntityState(
                new NetworkEntityId(7), EntityKind.ITEM, 42,
                position, FixedVec3.ZERO, 3, 6_000,
                ItemEntityRules.payload(42, count));
    }

    private static List<NodeIdentity> members() {
        return List.of(NodeIdentity.generate(), NodeIdentity.generate(), NodeIdentity.generate());
    }
}
