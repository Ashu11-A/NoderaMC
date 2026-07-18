package dev.nodera.storage.event;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.event.CommittedEventEnvelope;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.storage.Checkpoint;
import dev.nodera.storage.ContentId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryStoresTest {

    private final HashService hashes = new HashService();

    @Test
    void contentStoreDedupsAndVerifies() {
        InMemoryContentStore store = new InMemoryContentStore(hashes);
        byte[] blob = "snapshot".getBytes();
        ContentId id1 = store.put(blob);
        ContentId id2 = store.put(blob.clone()); // same bytes → same id, no new entry
        assertThat(id1).isEqualTo(id2);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.get(id1)).contains(blob);
        assertThat(store.has(id1)).isTrue();
        assertThat(store.get(ContentId.of(hashes, "other".getBytes()))).isEmpty();
    }

    @Test
    void eventLogAppendsMonotonicallyAndChainsRoots() {
        RegionId region = StorageFixtures.REGION;
        StateRoot r0 = StorageFixtures.root("g");
        StateRoot r1 = StorageFixtures.root("1");
        StateRoot r2 = StorageFixtures.root("2");

        InMemoryRegionEventStore store = new InMemoryRegionEventStore();
        store.append(StorageFixtures.event(region, 0, new SnapshotVersion(1), r0, r1, Bytes.empty()));
        store.append(StorageFixtures.event(region, 1, new SnapshotVersion(2), r1, r2, Bytes.empty()));

        assertThat(store.lastEventId(region)).isEqualTo(1);
        assertThat(store.headRoot(region)).contains(r2);
        assertThat(store.readFrom(region, 1)).hasSize(1);
        assertThat(store.regions()).containsExactly(region);
    }

    @Test
    void eventLogRejectsNonMonotonicId() {
        RegionId region = StorageFixtures.REGION;
        InMemoryRegionEventStore store = new InMemoryRegionEventStore();
        store.append(StorageFixtures.event(region, 0, new SnapshotVersion(1),
                StorageFixtures.root("g"), StorageFixtures.root("1"), Bytes.empty()));
        // next must be id 1; id 5 is out of order
        assertThatThrownBy(() -> store.append(StorageFixtures.event(region, 5, new SnapshotVersion(2),
                StorageFixtures.root("1"), StorageFixtures.root("2"), Bytes.empty())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void eventLogRejectsBrokenChain() {
        RegionId region = StorageFixtures.REGION;
        InMemoryRegionEventStore store = new InMemoryRegionEventStore();
        store.append(StorageFixtures.event(region, 0, new SnapshotVersion(1),
                StorageFixtures.root("g"), StorageFixtures.root("1"), Bytes.empty()));
        // prevRoot must equal current head (root("1")); root("WRONG") breaks the chain
        assertThatThrownBy(() -> store.append(StorageFixtures.event(region, 1, new SnapshotVersion(2),
                StorageFixtures.root("WRONG"), StorageFixtures.root("2"), Bytes.empty())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void checkpointStoreOrdersByVersionAndRequiresStrictGrowth() {
        RegionId region = StorageFixtures.REGION;
        InMemoryContentStore content = new InMemoryContentStore(hashes);
        ContentId blob = content.put("snap".getBytes());

        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        Checkpoint v10 = new Checkpoint(region, new SnapshotVersion(10), StorageFixtures.root("10"),
                blob, 100L, 9L, Bytes.empty());
        Checkpoint v20 = new Checkpoint(region, new SnapshotVersion(20), StorageFixtures.root("20"),
                blob, 200L, 19L, Bytes.empty());
        store.put(v10);
        store.put(v20);

        assertThat(store.latest(region)).contains(v20);
        assertThat(store.at(region, new SnapshotVersion(10))).contains(v10);
        assertThat(store.all(region)).containsExactly(v10, v20);

        // a checkpoint at version 15 (<= latest 20) is rejected
        Checkpoint stale = new Checkpoint(region, new SnapshotVersion(15), StorageFixtures.root("15"),
                blob, 150L, 14L, Bytes.empty());
        assertThatThrownBy(() -> store.put(stale)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void certificateStoreIsContentAddressed() {
        InMemoryCertificateStore store = new InMemoryCertificateStore(hashes);
        QuorumCertificate cert = StorageFixtures.certificate(StorageFixtures.REGION,
                new SnapshotVersion(1), StorageFixtures.root("1"));
        ContentId id = store.put(cert);

        assertThat(store.has(id)).isTrue();
        assertThat(store.get(id)).contains(cert);
        assertThat(store.getByHash(id.hash())).contains(cert);
        // idempotent
        assertThat(store.put(cert)).isEqualTo(id);
    }
}
