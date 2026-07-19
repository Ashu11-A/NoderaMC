package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.discovery.InventoryAdvertisement;
import dev.nodera.protocol.discovery.ManifestSeeders;
import dev.nodera.protocol.discovery.TrackerQuery;
import dev.nodera.protocol.discovery.TrackerResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 20 acceptance #1: a tracker indexing a 5-peer mesh across <b>two</b> worlds answers a
 * {@code TrackerQuery} for one world with exactly that world's peers and their held manifests —
 * counts and reliability match live state, and the other world never leaks in.
 *
 * <p>Headless: the tracker is transport-agnostic, so this drives the directory and inventory
 * directly the way membership gossip and inventory advertisements would feed them on a real mesh.
 *
 * <p>Thread-context: single test thread.
 */
final class TrackerIT {

    @Test
    void trackerAnswersPerWorldWithExactPeersAndSeeders() {
        Bytes worldA = DiscoveryFixtures.worldHash("world-a");
        Bytes worldB = DiscoveryFixtures.worldHash("world-b");

        PeerDirectory directory = new PeerDirectory();
        ArchiveInventory inventory = new ArchiveInventory();
        TrackerService tracker = new TrackerService(directory, inventory, 3);
        tracker.registerWorld(worldA, TrackerService.WorldInfo.named("Survival"));
        tracker.registerWorld(worldB, TrackerService.WorldInfo.named("Creative"));

        // 5 peers; peers 1-3 are in world A, peers 3-5 in world B (peer 3 is in both — legal, a
        // network may host several torrent worlds).
        NodeId p1 = DiscoveryFixtures.node(1);
        NodeId p2 = DiscoveryFixtures.node(2);
        NodeId p3 = DiscoveryFixtures.node(3);
        NodeId p4 = DiscoveryFixtures.node(4);
        NodeId p5 = DiscoveryFixtures.node(5);
        directory.seen(worldA, DiscoveryFixtures.entry(p1, 0.90), 1000L);
        directory.seen(worldA, DiscoveryFixtures.entry(p2, 1.00), 1000L);
        directory.seen(worldA, DiscoveryFixtures.entry(p3, 0.80), 1000L);
        directory.seen(worldB, DiscoveryFixtures.entry(p3, 0.80), 1000L);
        directory.seen(worldB, DiscoveryFixtures.entry(p4, 0.70), 1000L);
        directory.seen(worldB, DiscoveryFixtures.entry(p5, 0.60), 1000L);

        // Seeders, fed as the gossip advertisements would arrive.
        Bytes aManifest = DiscoveryFixtures.manifestHash(101);
        inventory.absorb(new InventoryAdvertisement(worldA, p1, List.of(
                new ManifestHolding(aManifest, DiscoveryFixtures.bitmapOf(Set.of(0, 1, 2))))));
        inventory.absorb(new InventoryAdvertisement(worldA, p2, List.of(
                new ManifestHolding(aManifest, DiscoveryFixtures.bitmapOf(Set.of(2, 3, 4))))));
        inventory.absorb(new InventoryAdvertisement(worldB, p4, List.of(
                new ManifestHolding(DiscoveryFixtures.manifestHash(201),
                        DiscoveryFixtures.bitmapOf(Set.of(0))))));

        // --- query world A ---------------------------------------------------------------
        TrackerResponse a = tracker.answer(new TrackerQuery(worldA), 1000L);
        assertThat(a.worldName()).isEqualTo("Survival");
        assertThat(a.peers()).extracting(e -> e.nodeId())
                .containsExactlyInAnyOrder(p1, p2, p3);   // exactly world A, never B-only peers
        assertThat(a.worldPlayerCount()).isEqualTo(3);
        assertThat(a.seeders()).hasSize(1);
        ManifestSeeders aSeeded = a.seeders().get(0);
        assertThat(aSeeded.manifestRoot()).isEqualTo(aManifest);
        assertThat(aSeeded.seeders()).containsExactlyInAnyOrder(p1, p2);
        // distinct held pieces of aManifest: {0,1,2,3,4} = 5
        assertThat(a.storedChunks()).isEqualTo(5L);
        // mean reliability (0.90 + 1.00 + 0.80)/3 ≈ 0.9 → 9000 bps
        assertThat(a.reliabilityBps()).isEqualTo(9000);
        // 2 seeders < floor 3 → under-replicated. (Health classification itself is unit-tested in
        // TrackerServiceTest; this IT pins per-world isolation and exact counts.)
        assertThat(a.health()).isEqualTo(WorldHealth.DEGRADED);

        // --- query world B ---------------------------------------------------------------
        TrackerResponse b = tracker.answer(new TrackerQuery(worldB), 1000L);
        assertThat(b.peers()).extracting(e -> e.nodeId())
                .containsExactlyInAnyOrder(p3, p4, p5);
        assertThat(b.seeders()).extracting(ManifestSeeders::manifestRoot)
                .containsExactly(DiscoveryFixtures.manifestHash(201));
        assertThat(b.storedChunks()).isEqualTo(1L);
        // B has 1 seeder < floor 3 → DEGRADED (under-replicated).
        assertThat(b.health()).isEqualTo(WorldHealth.DEGRADED);
    }

    @Test
    void aPeerThatLeavesIsForgottenByBothDirectoryAndInventory() {
        Bytes world = DiscoveryFixtures.worldHash("solo");
        NodeId seeder = DiscoveryFixtures.node(1);
        NodeId leaver = DiscoveryFixtures.node(2);

        PeerDirectory directory = new PeerDirectory();
        ArchiveInventory inventory = new ArchiveInventory();
        TrackerService tracker = new TrackerService(directory, inventory, 1);
        directory.seen(world, DiscoveryFixtures.entry(seeder, false), 0L);
        directory.seen(world, DiscoveryFixtures.entry(leaver, false), 0L);
        Bytes manifest = DiscoveryFixtures.manifestHash(1);
        inventory.record(world, manifest, seeder, DiscoveryFixtures.bitmapOf(Set.of(0)));
        inventory.record(world, manifest, leaver, DiscoveryFixtures.bitmapOf(Set.of(1)));

        directory.forget(world, leaver);
        inventory.forgetHolder(leaver);

        TrackerResponse r = tracker.answer(new TrackerQuery(world), 0L);
        assertThat(r.peers()).extracting(e -> e.nodeId()).containsExactly(seeder);
        assertThat(r.seeders().get(0).seeders()).containsExactly(seeder);
        assertThat(r.storedChunks()).isEqualTo(1L);   // only seeder's piece 0 remains
    }
}
