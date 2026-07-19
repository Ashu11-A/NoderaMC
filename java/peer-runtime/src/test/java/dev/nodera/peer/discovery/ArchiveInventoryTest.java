package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.discovery.InventoryAdvertisement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The archive inventory is the swarm's "who has what" — read by the tracker, the downloader, and
 * the placement audit — so it must merge advertisements correctly, keep piece-level granularity,
 * and refuse to grow unbounded from remote input (acceptance #4).
 *
 * <p>Thread-context: single test thread.
 */
final class ArchiveInventoryTest {

    @Test
    void mergesAdvertisementsAndKeepsPieceLevelGranularity() {
        ArchiveInventory inv = new ArchiveInventory();
        Bytes world = DiscoveryFixtures.worldHash("a");
        Bytes manifest = DiscoveryFixtures.manifestHash(1);

        inv.record(world, manifest, DiscoveryFixtures.node(1), DiscoveryFixtures.bitmapOf(Set.of(0, 1, 2)));
        inv.record(world, manifest, DiscoveryFixtures.node(2), DiscoveryFixtures.bitmapOf(Set.of(2, 3)));

        assertThat(inv.holdersOf(manifest))
                .containsExactlyInAnyOrder(DiscoveryFixtures.node(1), DiscoveryFixtures.node(2));

        Map<NodeId, Set<Integer>> set = inv.holderSet(manifest);
        assertThat(set.get(DiscoveryFixtures.node(1))).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(set.get(DiscoveryFixtures.node(2))).containsExactlyInAnyOrder(2, 3);
    }

    @Test
    void aLaterAdvertisementReplacesThatPeersHoldings() {
        ArchiveInventory inv = new ArchiveInventory();
        Bytes world = DiscoveryFixtures.worldHash("a");
        Bytes manifest = DiscoveryFixtures.manifestHash(1);
        NodeId peer = DiscoveryFixtures.node(1);

        inv.record(world, manifest, peer, DiscoveryFixtures.bitmapOf(Set.of(0, 1, 2)));
        // The peer evicted pieces 1 and 2 (Task 22 quota): its claim must shrink, not stay stale.
        inv.record(world, manifest, peer, DiscoveryFixtures.bitmapOf(Set.of(0)));

        assertThat(inv.holderSet(manifest).get(peer)).containsExactly(0);
    }

    @Test
    void anEmptyBitmapIsARetractionThatDropsThePeer() {
        ArchiveInventory inv = new ArchiveInventory();
        Bytes world = DiscoveryFixtures.worldHash("a");
        Bytes manifest = DiscoveryFixtures.manifestHash(1);
        NodeId peer = DiscoveryFixtures.node(1);

        inv.record(world, manifest, peer, DiscoveryFixtures.bitmapOf(Set.of(0, 1)));
        inv.record(world, manifest, peer, dev.nodera.protocol.content.PieceBitmap.empty());

        assertThat(inv.holdersOf(manifest)).isEmpty();
        assertThat(inv.trackedManifests()).isZero();
    }

    @Test
    void forgetHolderRemovesAPeerFromEveryManifest() {
        ArchiveInventory inv = new ArchiveInventory();
        Bytes world = DiscoveryFixtures.worldHash("a");
        NodeId leaver = DiscoveryFixtures.node(1);
        inv.record(world, DiscoveryFixtures.manifestHash(1), leaver, DiscoveryFixtures.bitmapOf(Set.of(0)));
        inv.record(world, DiscoveryFixtures.manifestHash(2), leaver, DiscoveryFixtures.bitmapOf(Set.of(0)));
        inv.record(world, DiscoveryFixtures.manifestHash(3), DiscoveryFixtures.node(2), DiscoveryFixtures.bitmapOf(Set.of(0)));

        inv.forgetHolder(leaver);

        assertThat(inv.holdersOf(DiscoveryFixtures.manifestHash(1))).isEmpty();
        assertThat(inv.holdersOf(DiscoveryFixtures.manifestHash(2))).isEmpty();
        assertThat(inv.holdersOf(DiscoveryFixtures.manifestHash(3)))
                .containsExactly(DiscoveryFixtures.node(2));
    }

    @Test
    void storedPiecesCountsDistinctHeldPiecesAcrossManifestsInOneWorld() {
        ArchiveInventory inv = new ArchiveInventory();
        Bytes a = DiscoveryFixtures.worldHash("a");
        Bytes b = DiscoveryFixtures.worldHash("b");
        inv.record(a, DiscoveryFixtures.manifestHash(1), DiscoveryFixtures.node(1), DiscoveryFixtures.bitmapOf(Set.of(0, 1, 2)));
        inv.record(a, DiscoveryFixtures.manifestHash(1), DiscoveryFixtures.node(2), DiscoveryFixtures.bitmapOf(Set.of(2, 3))); // 0,1,2,3 = 4 distinct
        inv.record(a, DiscoveryFixtures.manifestHash(2), DiscoveryFixtures.node(1), DiscoveryFixtures.bitmapOf(Set.of(0, 4)));   // +2 = 6
        inv.record(b, DiscoveryFixtures.manifestHash(9), DiscoveryFixtures.node(1), DiscoveryFixtures.bitmapOf(Set.of(0, 1)));   // other world, ignored

        assertThat(inv.storedPieces(a)).isEqualTo(6L);
    }

    @Test
    void aRemotePeerCannotGrowTheInventoryPastItsBound() {
        ArchiveInventory inv = new ArchiveInventory(8, 4);
        Bytes world = DiscoveryFixtures.worldHash("a");

        // One peer advertises 100k fabricated manifests — the receiver must cap at 8.
        for (int i = 0; i < 100_000; i++) {
            inv.absorb(new InventoryAdvertisement(world, DiscoveryFixtures.node(1), List.of(
                    new ManifestHolding(DiscoveryFixtures.manifestHash(i),
                            DiscoveryFixtures.bitmapOf(Set.of(0))))));
        }
        assertThat(inv.trackedManifests()).isEqualTo(8);
        assertThat(inv.maxManifests()).isEqualTo(8);
    }

    @Test
    void indexesByWorldForTheTracker() {
        ArchiveInventory inv = new ArchiveInventory();
        Bytes a = DiscoveryFixtures.worldHash("a");
        Bytes b = DiscoveryFixtures.worldHash("b");
        inv.record(a, DiscoveryFixtures.manifestHash(2), DiscoveryFixtures.node(1), DiscoveryFixtures.bitmapOf(Set.of(0)));
        inv.record(a, DiscoveryFixtures.manifestHash(1), DiscoveryFixtures.node(2), DiscoveryFixtures.bitmapOf(Set.of(0)));
        inv.record(b, DiscoveryFixtures.manifestHash(3), DiscoveryFixtures.node(1), DiscoveryFixtures.bitmapOf(Set.of(0)));

        Map<Bytes, List<NodeId>> aManifests = inv.manifestsOfWorld(a);
        assertThat(aManifests.keySet()).containsExactly(
                DiscoveryFixtures.manifestHash(1), DiscoveryFixtures.manifestHash(2));   // sorted by root hex
        assertThat(inv.seedersOfWorld(a)).containsExactlyInAnyOrder(
                DiscoveryFixtures.node(1), DiscoveryFixtures.node(2));
        assertThat(inv.manifestsOfWorld(b)).hasSize(1);
    }
}
