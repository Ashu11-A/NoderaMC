package dev.nodera.storage.event;

import dev.nodera.storage.StoreFixtures;
import dev.nodera.core.Bytes;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.storage.Checkpoint;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.GenesisManifest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeerSyncFlowTest {

    private EventSourcedWorldStore newStore() {
        return new EventSourcedWorldStore(
                new GenesisManifest(1L, 1, 1L, StoreFixtures.root("genesis")), StoreFixtures.HASHES);
    }

    private void appendCertified(EventSourcedWorldStore store, RegionId region, long eventId,
                                 SnapshotVersion version, StateRoot prevRoot, StateRoot resultingRoot) {
        var cert = StoreFixtures.certificate(region, version, resultingRoot);
        store.certificateStore().put(cert);
        Bytes certHash = store.certificateStore().contentId(cert).hash();
        store.events().append(StoreFixtures.event(region, eventId, version, prevRoot, resultingRoot, certHash));
    }

    @Test
    void newPeerSyncsFromGenesisWhenNoCheckpoint() {
        EventSourcedWorldStore store = newStore();
        RegionId region = StoreFixtures.REGION;
        StateRoot g = store.genesis().genesisRoot();
        StateRoot r1 = StoreFixtures.root("1");
        StateRoot r2 = StoreFixtures.root("2");
        appendCertified(store, region, 0, new SnapshotVersion(1), g, r1);
        appendCertified(store, region, 1, new SnapshotVersion(2), r1, r2);

        PeerSyncFlow.Outcome o = PeerSyncFlow.syncForward(store, region, -1L);

        assertThat(o.isAccepted()).isTrue();
        assertThat(o.accepted().finalRoot()).isEqualTo(r2);
        assertThat(o.accepted().eventsApplied()).isEqualTo(2);
        assertThat(o.accepted().lastCertifiedEventId()).isEqualTo(1);
    }

    @Test
    void returningPeerSyncsForwardFromCheckpoint() {
        EventSourcedWorldStore store = newStore();
        RegionId region = StoreFixtures.REGION;
        StateRoot g = store.genesis().genesisRoot();
        StateRoot r1 = StoreFixtures.root("1");
        StateRoot r2 = StoreFixtures.root("2");
        StateRoot r3 = StoreFixtures.root("3");

        appendCertified(store, region, 0, new SnapshotVersion(1), g, r1);
        appendCertified(store, region, 1, new SnapshotVersion(2), r1, r2);

        // A checkpoint is taken at event 1 (root r2).
        ContentId snap = store.content().put("snapshot-at-r2".getBytes());
        store.checkpoints().put(new Checkpoint(region, new SnapshotVersion(2), r2, snap, 200L, 1L, Bytes.empty()));

        // ...then more certified events arrive on the network after the checkpoint.
        appendCertified(store, region, 2, new SnapshotVersion(3), r2, r3);

        PeerSyncFlow.Outcome o = PeerSyncFlow.syncForward(store, region, 1L);

        assertThat(o.isAccepted()).isTrue();
        assertThat(o.accepted().finalRoot()).isEqualTo(r3);
        assertThat(o.accepted().eventsApplied()).isEqualTo(1); // only event 2 replayed after the checkpoint
        assertThat(o.accepted().lastCertifiedEventId()).isEqualTo(2);
    }

    @Test
    void uncertifiedNetworkSuffixDoesNotAdvancePeer() {
        EventSourcedWorldStore store = newStore();
        RegionId region = StoreFixtures.REGION;
        StateRoot g = store.genesis().genesisRoot();
        StateRoot r1 = StoreFixtures.root("1");
        appendCertified(store, region, 0, new SnapshotVersion(1), g, r1);
        // An uncertified event dangles on the network (no backing certificate).
        store.events().append(StoreFixtures.event(region, 1, new SnapshotVersion(2), r1,
                StoreFixtures.root("2"), StoreFixtures.HASHES.sha256("nope".getBytes())));

        PeerSyncFlow.Outcome o = PeerSyncFlow.syncForward(store, region, -1L);

        assertThat(o.isAccepted()).isTrue();
        // Peer lands on the last CERTIFIED root — the uncertified tail is not adopted.
        assertThat(o.accepted().finalRoot()).isEqualTo(r1);
        assertThat(o.accepted().lastCertifiedEventId()).isEqualTo(0);
    }
}
