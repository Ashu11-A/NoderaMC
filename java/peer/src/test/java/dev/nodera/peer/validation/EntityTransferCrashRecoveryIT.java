package dev.nodera.peer.validation;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.event.EntityTransferAcceptedEvent;
import dev.nodera.core.event.EntityTransferCommittedEvent;
import dev.nodera.core.event.EntityTransferPreparedEvent;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.EntityMutation;
import dev.nodera.core.state.EntityTransferDescriptor;
import dev.nodera.core.state.EntityTransferIntent;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.InMemoryWorldView;
import dev.nodera.coordinator.RegionPipeline;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.coordinator.entity.EntityTransferCoordinator;
import dev.nodera.coordinator.entity.JointTransferApprover;
import dev.nodera.simulation.entity.ItemEntityRules;
import dev.nodera.storage.GenesisManifest;
import dev.nodera.storage.rocksdb.RocksWorldStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Invariant 11: process death after durable prepare cannot duplicate or lose transferred entity. */
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
