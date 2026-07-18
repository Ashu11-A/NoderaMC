package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cached-peer store is bootstrap mechanism #2 and the reason a world outlives its original
 * host. Persistence must round-trip and survive a crash mid-save (atomic temp+move); eviction must
 * keep the most-recently-seen addresses, since those are most likely to answer.
 *
 * <p>Thread-context: single test thread.
 */
final class CachedPeerStoreTest {

    @Test
    void remembersAndReturnsMostRecentlySeenFirst() {
        CachedPeerStore store = new CachedPeerStore();
        Bytes world = DiscoveryFixtures.worldHash("a");
        store.remember(new CachedPeerStore.CachedPeer(world, DiscoveryFixtures.node(1), "h1:1", 100L));
        store.remember(new CachedPeerStore.CachedPeer(world, DiscoveryFixtures.node(2), "h2:2", 300L));
        store.remember(new CachedPeerStore.CachedPeer(world, DiscoveryFixtures.node(3), "h3:3", 200L));

        assertThat(store.forWorld(world))
                .extracting(CachedPeerStore.CachedPeer::route)
                .containsExactly("h2:2", "h3:3", "h1:1");   // 300, 200, 100
    }

    @Test
    void replacesAnEarlierEntryForTheSamePeer() {
        CachedPeerStore store = new CachedPeerStore();
        Bytes world = DiscoveryFixtures.worldHash("a");
        NodeId peer = DiscoveryFixtures.node(1);
        store.remember(new CachedPeerStore.CachedPeer(world, peer, "old:1", 100L));
        store.remember(new CachedPeerStore.CachedPeer(world, peer, "new:1", 500L));

        assertThat(store.forWorld(world)).hasSize(1);
        assertThat(store.forWorld(world).get(0).route()).isEqualTo("new:1");
    }

    @Test
    void evictsTheLeastRecentlySeenEntryUnderTheCap() {
        CachedPeerStore store = new CachedPeerStore(2);
        Bytes world = DiscoveryFixtures.worldHash("a");
        store.remember(new CachedPeerStore.CachedPeer(world, DiscoveryFixtures.node(1), "old:1", 100L));
        store.remember(new CachedPeerStore.CachedPeer(world, DiscoveryFixtures.node(2), "new:2", 500L));
        // Adding a third evicts node 1 (oldest lastSeen), keeping the two freshest.
        store.remember(new CachedPeerStore.CachedPeer(world, DiscoveryFixtures.node(3), "mid:3", 300L));

        assertThat(store.forWorld(world)).extracting(CachedPeerStore.CachedPeer::nodeId)
                .containsExactlyInAnyOrder(DiscoveryFixtures.node(2), DiscoveryFixtures.node(3));
    }

    @Test
    void rejectsABlankRouteBecauseItIsNotDialable() {
        CachedPeerStore store = new CachedPeerStore();
        Bytes world = DiscoveryFixtures.worldHash("a");
        assertThatThrownBy(() -> new CachedPeerStore.CachedPeer(world, DiscoveryFixtures.node(1), "  ", 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void persistsAndReloadsAcrossInstances(@TempDir Path dir) {
        Path file = dir.resolve("nested").resolve("peers.bin");
        CachedPeerStore store = new CachedPeerStore();
        Bytes a = DiscoveryFixtures.worldHash("a");
        Bytes b = DiscoveryFixtures.worldHash("b");
        store.remember(new CachedPeerStore.CachedPeer(a, DiscoveryFixtures.node(1), "h1:1", 10L));
        store.remember(new CachedPeerStore.CachedPeer(a, DiscoveryFixtures.node(2), "h2:2", 20L));
        store.remember(new CachedPeerStore.CachedPeer(b, DiscoveryFixtures.node(3), "h3:3", 30L));
        store.save(file);

        CachedPeerStore reloaded = CachedPeerStore.load(file);

        assertThat(reloaded.size()).isEqualTo(3);
        assertThat(reloaded.forWorld(a)).hasSize(2);
        assertThat(reloaded.forWorld(b)).extracting(CachedPeerStore.CachedPeer::route)
                .containsExactly("h3:3");
    }

    @Test
    void loadReturnsAnEmptyStoreForAFirstRun(@TempDir Path dir) {
        CachedPeerStore loaded = CachedPeerStore.load(dir.resolve("absent.bin"));
        assertThat(loaded.size()).isZero();
    }

    @Test
    void persistedEntryRoundTripsCanonically() {
        CachedPeerStore.CachedPeer original = new CachedPeerStore.CachedPeer(
                DiscoveryFixtures.worldHash("a"),
                new NodeId(new UUID(0x1234L, 0x5678L)),
                "10.0.0.1:25565", 999L);
        dev.nodera.core.crypto.CanonicalWriter w = new dev.nodera.core.crypto.CanonicalWriter();
        original.encode(w);
        CachedPeerStore.CachedPeer decoded = CachedPeerStore.CachedPeer.decode(
                new dev.nodera.core.crypto.CanonicalReader(w.toByteArray()));
        assertThat(decoded).isEqualTo(original);
    }
}
