package dev.nodera.coordinator;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.consensuscert.ServerAuthorityCertificate;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.core.Bytes;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.coordinator.interference.InterferenceBuffer;
import dev.nodera.coordinator.interference.InterferenceCommitter;
import dev.nodera.coordinator.interference.InterferenceStats;
import dev.nodera.coordinator.interference.MutationGuard;
import dev.nodera.simulation.RegionExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Task 11 headless acceptance flows (#1 guard conversion, #3 ordering): a scripted foreign
 * write into a delegated region is CONVERTed into a certified {@code ExternalDelta} whose applied
 * form re-extracts to the live world's root on every replica; in STRICT mode the write is blocked
 * and the world unchanged; interference arriving while the pipeline is mid-batch is held and
 * committed after the decision, so a stale-base rejection is impossible by construction.
 */
class InterferenceCommitterTest {

    private final RegionId region = CoordFixtures.region(0, 0);
    private final NodeIdentity server = NodeIdentity.generate();
    private final SignatureService signatures = new SignatureService();

    private InMemoryWorldView world;
    private InterferenceBuffer buffer;
    private InterferenceStats stats;
    private List<RegionDelta> sinkDeltas;
    private List<ServerAuthorityCertificate> sinkCerts;
    private InterferenceCommitter committer;

    @BeforeEach
    void setUp() {
        world = new InMemoryWorldView();
        world.load(CoordFixtures.fullUniformSnapshot(region, 0)); // AIR
        buffer = new InterferenceBuffer();
        stats = new InterferenceStats(100);
        sinkDeltas = new ArrayList<>();
        sinkCerts = new ArrayList<>();
        committer = new InterferenceCommitter(
                buffer,
                (r, v, bodyVersion) -> rootOf(world, r, v, bodyVersion),
                world::setSnapshotBodyVersion,
                (delta, cert) -> {
                    sinkDeltas.add(delta);
                    sinkCerts.add(cert);
                },
                server);
        committer.onCommittedVersion(region, SnapshotVersion.INITIAL);
    }

    @Test
    void convertedForeignWriteCommitsCertifiedDeltaAndReplicaMatchesWorldRoot() {
        MutationGuard guard = new MutationGuard(
                r -> r.equals(region), MutationGuard.Mode.CONVERT, buffer, stats);
        NBlockPos pos = new NBlockPos(5, 70, 5);

        // A foreign mod writes into the delegated region: CONVERT lets it land, records it.
        int prev = world.getBlock(region, pos);
        assertThat(guard.verdict(region, pos, prev, 7)).isEqualTo(MutationGuard.Verdict.CONVERT);
        world.setBlock(region, pos, 7);

        List<RegionDelta> emitted = committer.onTickEnd(r -> PipelineState.ACTIVE);

        assertThat(emitted).hasSize(1);
        RegionDelta delta = emitted.get(0);
        ServerAuthorityCertificate cert = sinkCerts.get(0);
        assertThat(cert.reason()).isEqualTo(ServerAuthorityCertificate.Reason.EXTERNAL_MUTATION);
        assertThat(cert.baseVersion()).isEqualTo(SnapshotVersion.INITIAL);
        assertThat(cert.resultingVersion()).isEqualTo(SnapshotVersion.INITIAL.next());
        assertThat(cert.transitionRoot()).isEqualTo(StateRoot.of(CoordFixtures.hashes().hash(delta)));
        assertThat(signatures.verify(server.publicKeyBytes(), cert.signedPortion(), cert.serverSignature()))
                .isTrue();

        // Every replica applies the delta without voting and lands on the live world's root.
        InMemoryWorldView replica = new InMemoryWorldView();
        replica.load(CoordFixtures.fullUniformSnapshot(region, 0));
        WorldMutationApplier.ApplyResult applied = new WorldMutationApplier(replica).apply(delta);
        assertThat(applied.committed()).isTrue();
        assertThat(rootOf(replica, region, cert.resultingVersion()))
                .isEqualTo(cert.resultingRoot())
                .isEqualTo(rootOf(world, region, cert.resultingVersion()));
    }

    @Test
    void legacyBlockInterferencePromotesSnapshotEncodingBeforeCertification() {
        RegionSnapshot current = CoordFixtures.fullUniformSnapshot(region, 0);
        RegionSnapshot legacy = new RegionSnapshot(
                region, current.version(), current.tick(), current.chunks(), List.of(), 1);
        world.load(legacy);
        NBlockPos pos = new NBlockPos(5, 70, 5);
        world.setBlock(region, pos, 7);
        buffer.record(region, new dev.nodera.coordinator.interference.RecordedMutation(
                pos, 0, 7, dev.nodera.coordinator.interference.MutationSource.UNKNOWN));
        RegionDelta delta = committer.onTickEnd(r -> PipelineState.ACTIVE).getFirst();
        InMemoryWorldView replica = new InMemoryWorldView();
        replica.load(legacy);
        assertThat(new WorldMutationApplier(replica).apply(delta).committed()).isTrue();
        assertThat(rootOf(replica, region, delta.resultingVersion()))
                .isEqualTo(delta.resultingRoot());
        assertThat(replica.reExtract(region, delta.resultingVersion(), 0).bodyVersion()).isEqualTo(2);
    }

    @Test
    void strictModeBlocksTheWriteAndTheWorldIsUnchanged() {
        MutationGuard guard = new MutationGuard(
                r -> r.equals(region), MutationGuard.Mode.STRICT, buffer, stats);
        NBlockPos pos = new NBlockPos(5, 70, 5);
        StateRoot before = rootOf(world, region, SnapshotVersion.INITIAL);

        assertThat(guard.verdict(region, pos, 0, 7)).isEqualTo(MutationGuard.Verdict.BLOCK);
        // BLOCK means the caller cancels the write: nothing lands, nothing is buffered.
        assertThat(committer.onTickEnd(r -> PipelineState.ACTIVE)).isEmpty();
        assertThat(world.getBlock(region, pos)).isZero();
        assertThat(rootOf(world, region, SnapshotVersion.INITIAL)).isEqualTo(before);
        assertThat(guard.blockedWrites()).isEqualTo(1);
    }

    @Test
    void interferenceDuringVotingIsHeldAndCommitsAfterTheDecisionWithoutStaleBase() {
        MutationGuard guard = new MutationGuard(
                r -> r.equals(region), MutationGuard.Mode.CONVERT, buffer, stats);
        RegionSnapshot base = CoordFixtures.fullUniformSnapshot(region, 0);

        // A batch is in flight (dispatched, not yet decided) …
        ActionBatch batch = CoordFixtures.batch(region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL,
                0, 1, List.of(CoordFixtures.place(region, 1, 0, 40, 100, 40, 4)));
        RegionExecutionResult engineResult = CoordFixtures.engine().execute(CoordFixtures.request(base, batch));

        // … when a foreign write lands in the same region at a different position.
        NBlockPos foreign = new NBlockPos(5, 70, 5);
        assertThat(guard.verdict(region, foreign, 0, 7)).isEqualTo(MutationGuard.Verdict.CONVERT);
        world.setBlock(region, foreign, 7);

        // Tick end while VOTING: the buffer is held, nothing is emitted.
        assertThat(committer.onTickEnd(r -> PipelineState.AWAITING_VERIFICATION)).isEmpty();
        assertThat(sinkDeltas).isEmpty();

        // The decision commits the batch through the applier scope (the one legal writer).
        WorldMutationApplier applier = new WorldMutationApplier(world);
        guard.applierScope(() -> assertThat(applier.apply(engineResult.delta()).committed()).isTrue());
        SnapshotVersion postBatch = engineResult.delta().resultingVersion();
        committer.onCommittedVersion(region, postBatch);

        // After the decision — before the next batch is assembled — the held buffer commits.
        RegionDelta external = committer.onPipelineDecision(region).orElseThrow();
        assertThat(external.baseVersion()).isEqualTo(postBatch);
        assertThat(external.resultingVersion()).isEqualTo(postBatch.next());

        // A replica that applied the batch delta applies the external delta with no CAS abort —
        // the STALE_BASE interference race is impossible by construction.
        InMemoryWorldView replica = new InMemoryWorldView();
        replica.load(base);
        WorldMutationApplier replicaApplier = new WorldMutationApplier(replica);
        assertThat(replicaApplier.apply(engineResult.delta()).committed()).isTrue();
        assertThat(replicaApplier.apply(external).committed()).isTrue();
        assertThat(rootOf(replica, region, external.resultingVersion()))
                .isEqualTo(external.resultingRoot())
                .isEqualTo(rootOf(world, region, external.resultingVersion()));

        // Batch N+1 bases on the post-interference version.
        assertThat(committer.trackedVersion(region)).contains(postBatch.next());
    }

    @Test
    void ghostEntityChangeUsesSameCertifiedExternalDeltaAndReplicaPath() {
        PersistedEntityState ghost = new PersistedEntityState(
                new NetworkEntityId(7), EntityKind.GHOST, 54,
                FixedVec3.ofBlock(5, 70, 5), FixedVec3.ZERO,
                0, PersistedEntityState.NEVER_DESPAWN, Bytes.empty());

        // Vanilla mob capture updates the live view first, then records the exact external change.
        world.setEntity(region, ghost);
        buffer.recordEntity(region, null, ghost);
        RegionDelta external = committer.onTickEnd(r -> PipelineState.ACTIVE).getFirst();

        assertThat(external.blockMutations()).isEmpty();
        assertThat(external.entityMutations()).singleElement().satisfies(mutation ->
                assertThat(mutation.newState()).isEqualTo(ghost));
        assertThat(sinkCerts.getFirst().reason())
                .isEqualTo(ServerAuthorityCertificate.Reason.EXTERNAL_MUTATION);

        InMemoryWorldView replica = new InMemoryWorldView();
        replica.load(CoordFixtures.fullUniformSnapshot(region, 0));
        assertThat(new WorldMutationApplier(replica).apply(external).committed()).isTrue();
        assertThat(rootOf(replica, region, external.resultingVersion()))
                .isEqualTo(rootOf(world, region, external.resultingVersion()));
    }

    @Test
    void zombieGhostAndDoorBreakShareOneCertifiedExternalDelta() {
        PersistedEntityState zombie = new PersistedEntityState(
                new NetworkEntityId(54), EntityKind.GHOST, 54,
                FixedVec3.ofBlock(5, 70, 5), FixedVec3.ZERO,
                1, PersistedEntityState.NEVER_DESPAWN, Bytes.fromHex("01"));
        NBlockPos doorSection = new NBlockPos(16, 64, 16);
        world.setEntity(region, zombie);
        world.setBlock(region, doorSection, 0);
        buffer.recordEntity(region, null, zombie);
        buffer.record(region, new dev.nodera.coordinator.interference.RecordedMutation(
                doorSection, 7, 0,
                dev.nodera.coordinator.interference.MutationSource.ENTITY));

        RegionDelta external = committer.onTickEnd(r -> PipelineState.ACTIVE).getFirst();

        assertThat(external.entityMutations()).hasSize(1);
        assertThat(external.blockMutations()).hasSize(1);
        assertThat(sinkCerts.getFirst().reason())
                .isEqualTo(ServerAuthorityCertificate.Reason.EXTERNAL_MUTATION);
        InMemoryWorldView replica = new InMemoryWorldView();
        RegionSnapshot base = CoordFixtures.fullUniformSnapshot(region, 0);
        replica.load(base);
        replica.setBlock(region, doorSection, 7);
        assertThat(new WorldMutationApplier(replica).apply(external).committed()).isTrue();
        assertThat(rootOf(replica, region, external.resultingVersion()))
                .isEqualTo(rootOf(world, region, external.resultingVersion()));
    }

    @Test
    void sinkFailureRetainsMutationAndDoesNotAdvanceVersion() {
        RegionSnapshot current = CoordFixtures.fullUniformSnapshot(region, 0);
        world.load(new RegionSnapshot(
                region, current.version(), current.tick(), current.chunks(), List.of(), 1));
        NBlockPos pos = new NBlockPos(5, 70, 5);
        world.setBlock(region, pos, 7);
        buffer.record(region, new dev.nodera.coordinator.interference.RecordedMutation(
                pos, 0, 7, dev.nodera.coordinator.interference.MutationSource.UNKNOWN));
        int[] attempts = {0};
        InterferenceCommitter retrying = new InterferenceCommitter(
                buffer,
                (r, v, bodyVersion) -> rootOf(world, r, v, bodyVersion),
                world::setSnapshotBodyVersion,
                (delta, certificate) -> {
                    if (attempts[0]++ == 0) {
                        throw new IllegalStateException("broadcast unavailable");
                    }
                },
                server);
        retrying.onCommittedVersion(region, SnapshotVersion.INITIAL);

        assertThatThrownBy(() -> retrying.onTickEnd(r -> PipelineState.ACTIVE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broadcast unavailable");
        assertThat(retrying.trackedVersion(region)).contains(SnapshotVersion.INITIAL);
        assertThat(buffer.isEmpty(region)).isFalse();
        assertThat(world.reExtract(region, SnapshotVersion.INITIAL, 0).bodyVersion()).isEqualTo(1);

        assertThat(retrying.onTickEnd(r -> PipelineState.ACTIVE)).hasSize(1);
        assertThat(retrying.trackedVersion(region)).contains(SnapshotVersion.INITIAL.next());
        assertThat(buffer.isEmpty(region)).isTrue();
        assertThat(world.reExtract(region, SnapshotVersion.INITIAL.next(), 0).bodyVersion()).isEqualTo(2);
    }

    /** The shared extraction convention for this test: canonical snapshot at (version, tick 0). */
    private static StateRoot rootOf(InMemoryWorldView view, RegionId region, SnapshotVersion version) {
        return StateRoot.of(CoordFixtures.hashes().hash(view.reExtract(region, version, 0L)));
    }

    private static StateRoot rootOf(
            InMemoryWorldView view, RegionId region, SnapshotVersion version, int bodyVersion) {
        RegionSnapshot extracted = view.reExtract(region, version, 0L);
        RegionSnapshot encoded = new RegionSnapshot(
                region, version, extracted.tick(), extracted.chunks(), extracted.entities(), bodyVersion);
        return StateRoot.of(CoordFixtures.hashes().hash(encoded));
    }
}
