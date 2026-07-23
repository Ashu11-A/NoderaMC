package dev.nodera.peer.validation;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.EntityTransferCertificate;
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
import dev.nodera.core.state.EntityTransferRecord;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.entity.EntityTransferCoordinator;
import dev.nodera.coordinator.entity.JointTransferApprover;
import dev.nodera.storage.GenesisManifest;
import dev.nodera.storage.event.EventReplayer;
import dev.nodera.storage.event.EventSourcedWorldStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
