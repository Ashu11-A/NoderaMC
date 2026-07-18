package dev.nodera.storage.event;

import dev.nodera.core.Bytes;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.storage.GenesisManifest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventReplayerTest {

    private final EventSourcedWorldStore store = new EventSourcedWorldStore(
            new GenesisManifest(1L, 1, 1L, StorageFixtures.root("genesis")),
            StorageFixtures.HASHES);

    @Test
    void certifiedChainReplaysToFinalRoot() {
        RegionId region = StorageFixtures.REGION;
        StateRoot g = store.genesis().genesisRoot();
        StateRoot r1 = StorageFixtures.root("1");
        StateRoot r2 = StorageFixtures.root("2");

        // Two certified events chaining g → r1 → r2.
        appendCertified(region, 0, new SnapshotVersion(1), g, r1);
        appendCertified(region, 1, new SnapshotVersion(2), r1, r2);

        EventReplayer.ReplayResult result = EventReplayer.replay(
                store.events(), store.certificates(), region, g, 0L);

        assertThat(result.finalRoot()).isEqualTo(r2);
        assertThat(result.eventsApplied()).isEqualTo(2);
        assertThat(result.lastCertifiedEventId()).isEqualTo(1);
        assertThat(result.stoppedAtUncertified()).isFalse();
    }

    @Test
    void uncertifiedSuffixStopsReplay() {
        RegionId region = StorageFixtures.REGION;
        StateRoot g = store.genesis().genesisRoot();
        StateRoot r1 = StorageFixtures.root("1");
        StateRoot r2 = StorageFixtures.root("2");

        appendCertified(region, 0, new SnapshotVersion(1), g, r1);
        // Second event references a certificate hash nobody has → uncertified suffix.
        store.events().append(StorageFixtures.event(region, 1, new SnapshotVersion(2), r1, r2,
                StorageFixtures.HASHES.sha256("missing".getBytes())));

        EventReplayer.ReplayResult result = EventReplayer.replay(
                store.events(), store.certificates(), region, g, 0L);

        assertThat(result.eventsApplied()).isEqualTo(1); // only the certified event folded in
        assertThat(result.lastCertifiedEventId()).isEqualTo(0);
        assertThat(result.stoppedAtUncertified()).isTrue();
        assertThat(result.finalRoot()).isEqualTo(r1); // stopped at the last certified root
    }

    @Test
    void appendTimeValidationRejectsBrokenChain() {
        // The store enforces Invariant 3 at write time: a non-chaining event cannot be appended.
        RegionId region = StorageFixtures.REGION;
        StateRoot g = store.genesis().genesisRoot();
        StateRoot r1 = StorageFixtures.root("1");
        appendCertified(region, 0, new SnapshotVersion(1), g, r1);
        assertThatThrownBy(() -> store.events().append(StorageFixtures.event(region, 1,
                new SnapshotVersion(2), StorageFixtures.root("WRONG"), StorageFixtures.root("2"), Bytes.empty())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replayerChainCheckIsReadSideDefense() {
        // A log loaded by some path that bypassed append-time validation (e.g. a corrupt segment
        // imported from a peer) still must not replay: the replayer's own chain check is a defense.
        RegionId region = StorageFixtures.REGION;
        StateRoot g = store.genesis().genesisRoot();
        StateRoot r1 = StorageFixtures.root("1");
        StateRoot r2 = StorageFixtures.root("2");

        // Store a real certificate for event 0's root so the replayer treats event 0 as certified
        // and proceeds to event 1, where the chain breaks.
        var cert0 = StorageFixtures.certificate(region, new SnapshotVersion(1), r1);
        store.certificateStore().put(cert0);
        Bytes certHash = store.certificateStore().contentId(cert0).hash();

        dev.nodera.storage.RegionEventStore broken = new dev.nodera.storage.RegionEventStore() {
            private final java.util.List<dev.nodera.core.event.CommittedEventEnvelope> events = new java.util.ArrayList<>();
            {
                events.add(StorageFixtures.event(region, 0, new SnapshotVersion(1), g, r1, certHash));
                // prevRoot deliberately does not chain to r1.
                events.add(StorageFixtures.event(region, 1, new SnapshotVersion(2),
                        StorageFixtures.root("WRONG"), r2, certHash));
            }
            @Override public void append(dev.nodera.core.event.CommittedEventEnvelope e) { events.add(e); }
            @Override public java.util.List<dev.nodera.core.event.CommittedEventEnvelope> readFrom(
                    dev.nodera.core.region.RegionId r, long fromEventId) {
                return events.stream().filter(e -> e.eventId() >= fromEventId).toList();
            }
            @Override public long lastEventId(dev.nodera.core.region.RegionId r) { return events.size() - 1; }
            @Override public java.util.Optional<StateRoot> headRoot(dev.nodera.core.region.RegionId r) {
                return java.util.Optional.of(r2);
            }
            @Override public java.util.List<dev.nodera.core.region.RegionId> regions() { return java.util.List.of(region); }
        };

        assertThatThrownBy(() -> EventReplayer.replay(broken, store.certificates(), region, g, 0L))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Append one event AND its finalising certificate; the event's certRef is the cert's hash. */
    private void appendCertified(RegionId region, long eventId, SnapshotVersion version,
                                 StateRoot prevRoot, StateRoot resultingRoot) {
        var cert = StorageFixtures.certificate(region, version, resultingRoot);
        store.certificateStore().put(cert);
        Bytes certHash = store.certificateStore().contentId(cert).hash();
        store.events().append(StorageFixtures.event(region, eventId, version, prevRoot, resultingRoot, certHash));
    }
}
