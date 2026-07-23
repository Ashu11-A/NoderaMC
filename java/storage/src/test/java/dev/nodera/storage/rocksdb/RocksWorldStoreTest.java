package dev.nodera.storage.rocksdb;

import dev.nodera.storage.StoreFixtures;
import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.event.CommittedEventEnvelope;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityTransferDescriptor;
import dev.nodera.core.state.EntityTransferRecord;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.storage.Checkpoint;
import dev.nodera.storage.Compression;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.GenesisManifest;
import dev.nodera.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RocksWorldStore}: the durable seam behaves exactly like the in-memory event-sourced store
 * (monotonic ids, unbroken chain, checkpoint ordering, content-addressed certificates) and its
 * state — heads included — survives close/reopen (the replay-on-boot head recovery).
 */
class RocksWorldStoreTest {

    private static final RegionId REGION = StoreFixtures.REGION;

    @TempDir
    Path dir;

    private RocksWorldStore open() {
        return RocksWorldStore.open(dir, StoreFixtures.GENESIS, StoreFixtures.HASHES, false);
    }

    @Test
    void genesisPersistsAndAForeignGenesisIsRejectedOnReopen() {
        try (RocksWorldStore store = open()) {
            assertThat(store.genesis()).isEqualTo(StoreFixtures.GENESIS);
        }
        GenesisManifest other = new GenesisManifest(1L, 2, 3L, StoreFixtures.root("other"));
        assertThatThrownBy(() -> RocksWorldStore.open(dir, other, StoreFixtures.HASHES, false))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("different world");
        try (RocksWorldStore reopened = open()) {
            assertThat(reopened.genesis()).isEqualTo(StoreFixtures.GENESIS);
        }
    }

    @Test
    void eventLogSurvivesReopenAndHeadRecoveryFeedsValidation() {
        try (RocksWorldStore store = open()) {
            for (long i = 0; i < 3; i++) {
                store.events().append(StoreFixtures.chainedEvent(REGION, i));
            }
        }
        try (RocksWorldStore reopened = open()) {
            assertThat(reopened.events().lastEventId(REGION)).isEqualTo(2);
            assertThat(reopened.events().headRoot(REGION)).contains(StoreFixtures.chainRoot(2));
            assertThat(reopened.events().regions()).containsExactly(REGION);

            List<CommittedEventEnvelope> all = reopened.events().readFrom(REGION, 0);
            assertThat(all).hasSize(3);
            assertThat(all.get(0)).isEqualTo(StoreFixtures.chainedEvent(REGION, 0));
            assertThat(reopened.events().readFrom(REGION, 2)).hasSize(1);

            // The recovered head drives append validation: a gap or a broken chain still throws.
            assertThatThrownBy(() -> reopened.events().append(StoreFixtures.chainedEvent(REGION, 5)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("non-monotonic");
            assertThatThrownBy(() -> reopened.events().append(StoreFixtures.event(
                    REGION, 3, StoreFixtures.root("not-the-head"), StoreFixtures.chainRoot(3))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("broken event chain");
            // Continuing from the true head succeeds.
            reopened.events().append(StoreFixtures.chainedEvent(REGION, 3));
            assertThat(reopened.events().lastEventId(REGION)).isEqualTo(3);
        }
    }

    @Test
    void distinctRegionsKeepSeparateLogs() {
        RegionId other = new RegionId(DimensionKey.overworld(), 4, -3);
        try (RocksWorldStore store = open()) {
            store.events().append(StoreFixtures.chainedEvent(REGION, 0));
            store.events().append(StoreFixtures.chainedEvent(other, 0));
            assertThat(store.events().readFrom(REGION, 0)).hasSize(1);
            assertThat(store.events().readFrom(other, 0)).hasSize(1);
            assertThat(store.events().regions()).containsExactly(REGION, other);
        }
    }

    @Test
    void checkpointOrderingEnforcedAndIndexSurvivesReopen() {
        Checkpoint v10 = checkpoint(10);
        Checkpoint v20 = checkpoint(20);
        try (RocksWorldStore store = open()) {
            store.checkpoints().put(v10);
            store.checkpoints().put(v20);
            assertThatThrownBy(() -> store.checkpoints().put(checkpoint(20)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not greater");
        }
        try (RocksWorldStore reopened = open()) {
            assertThat(reopened.checkpoints().latest(REGION)).contains(v20);
            assertThat(reopened.checkpoints().at(REGION, new SnapshotVersion(10))).contains(v10);
            assertThat(reopened.checkpoints().at(REGION, new SnapshotVersion(15))).isEmpty();
            assertThat(reopened.checkpoints().all(REGION)).containsExactly(v10, v20);
        }
    }

    @Test
    void certificatesAreContentAddressedAndIdempotent() {
        QuorumCertificate cert = StoreFixtures.certificate(StoreFixtures.chainRoot(0));
        ContentId id;
        try (RocksWorldStore store = open()) {
            id = store.certificates().put(cert);
            assertThat(store.certificates().put(cert)).isEqualTo(id);
        }
        try (RocksWorldStore reopened = open()) {
            assertThat(reopened.certificates().has(id)).isTrue();
            assertThat(reopened.certificates().get(id)).contains(cert);
            assertThat(reopened.certificates().getByHash(id.hash())).contains(cert);
            assertThat(reopened.certificates().getByHash(Bytes.fromHex("00"))).isEmpty();
        }
    }

    @Test
    void pairedEventAppendIsAllOrNothing() {
        RegionId target = new RegionId(DimensionKey.overworld(), 1, 0);
        try (RocksWorldStore store = open()) {
            CommittedEventEnvelope source = StoreFixtures.chainedEvent(REGION, 0);
            CommittedEventEnvelope invalidTarget = StoreFixtures.event(
                    target, 2, StoreFixtures.GENESIS.genesisRoot(), StoreFixtures.root("target"));

            assertThatThrownBy(() -> store.events().appendAtomic(List.of(source, invalidTarget)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("non-monotonic");
            assertThat(store.events().lastEventId(REGION)).isEqualTo(-1);
            assertThat(store.events().lastEventId(target)).isEqualTo(-1);

            store.events().appendAtomic(List.of(
                    source, StoreFixtures.event(target, 0,
                            StoreFixtures.GENESIS.genesisRoot(), StoreFixtures.root("target"))));
            assertThat(store.events().lastEventId(REGION)).isZero();
            assertThat(store.events().lastEventId(target)).isZero();
        }
    }

    @Test
    void recoverableTransferStageSurvivesReopen() {
        EntityTransferRecord prepared = preparedTransfer();
        try (RocksWorldStore store = open()) {
            store.transfers().put(prepared);
            assertThat(store.transfers().recoverable()).containsExactly(prepared);
        }
        try (RocksWorldStore reopened = open()) {
            assertThat(reopened.transfers().get(prepared.transferId())).contains(prepared);
            assertThat(reopened.transfers().recoverable()).containsExactly(prepared);
            reopened.transfers().put(new EntityTransferRecord(
                    EntityTransferRecord.Stage.ABORTED, prepared.descriptor(),
                    prepared.sourceDelta(), prepared.targetDelta(), null, "host stopped"));
        }
        try (RocksWorldStore reopened = open()) {
            assertThat(reopened.transfers().recoverable()).isEmpty();
            assertThat(reopened.transfers().get(prepared.transferId()).orElseThrow().stage())
                    .isEqualTo(EntityTransferRecord.Stage.ABORTED);
        }
    }

    private static Checkpoint checkpoint(long version) {
        return new Checkpoint(REGION, new SnapshotVersion(version),
                StoreFixtures.chainRoot(version),
                new ContentId(StoreFixtures.root("snap-" + version).hash(), 64L, Compression.ZSTD),
                version * 10L, version - 1, Bytes.empty());
    }

    private static EntityTransferRecord preparedTransfer() {
        RegionId target = new RegionId(DimensionKey.overworld(), 1, 0);
        SnapshotVersion base = SnapshotVersion.INITIAL;
        SnapshotVersion next = base.next();
        RegionDelta sourceDelta = new RegionDelta(
                REGION, base, next, List.of(), StoreFixtures.root("source-result"));
        RegionDelta targetDelta = new RegionDelta(
                target, base, next, List.of(), StoreFixtures.root("target-result"));
        EntityTransferDescriptor descriptor = new EntityTransferDescriptor(
                12L, REGION, target, RegionEpoch.INITIAL, RegionEpoch.INITIAL,
                new NetworkEntityId(7), base, next, StoreFixtures.root("source-prev"),
                sourceDelta.resultingRoot(), StoreFixtures.root("source-transition"),
                base, next, StoreFixtures.root("target-prev"), targetDelta.resultingRoot(),
                StoreFixtures.root("target-transition"), 10L);
        return new EntityTransferRecord(
                EntityTransferRecord.Stage.PREPARED, descriptor,
                sourceDelta, targetDelta, null, "");
    }
}
