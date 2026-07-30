package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The peer directory is the tracker's liveness source, so its staleness and bounds must be exactly
 * deterministic — "online after N ms" is arithmetic here, never a sleep.
 *
 * <p>Thread-context: single test thread.
 */
final class PeerDirectoryTest {

    @Test
    void reportsASeenPeerOnlineUntilItGoesStale() {
        PeerDirectory dir = new PeerDirectory();
        Bytes world = DiscoveryFixtures.worldHash("a");
        NodeId p = DiscoveryFixtures.node(1);
        dir.seen(world, DiscoveryFixtures.entry(p, false), 1000L);

        assertThat(dir.online(world, 1000L)).hasSize(1);
        assertThat(dir.online(world, 1000L + 59_000L)).hasSize(1);
        assertThat(dir.online(world, 1000L + 61_000L)).isEmpty();   // stale → offline
        assertThat(dir.size(world)).isEqualTo(1);                    // but still remembered
    }

    @Test
    void isolatesWorldsByGenesisHash() {
        PeerDirectory dir = new PeerDirectory();
        Bytes a = DiscoveryFixtures.worldHash("a");
        Bytes b = DiscoveryFixtures.worldHash("b");
        dir.seen(a, DiscoveryFixtures.entry(DiscoveryFixtures.node(1), false), 0L);
        dir.seen(a, DiscoveryFixtures.entry(DiscoveryFixtures.node(2), false), 0L);
        dir.seen(b, DiscoveryFixtures.entry(DiscoveryFixtures.node(3), false), 0L);

        assertThat(dir.online(a, 0L)).hasSize(2);
        assertThat(dir.online(b, 0L)).hasSize(1);
        assertThat(dir.worlds()).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void onlineListIsSortedByNodeIdForCanonicalTrackerAnswers() {
        PeerDirectory dir = new PeerDirectory();
        Bytes world = DiscoveryFixtures.worldHash("a");
        // Insert out of id order; the answer must come back sorted so two directories agree.
        dir.seen(world, DiscoveryFixtures.entry(DiscoveryFixtures.node(3), false), 0L);
        dir.seen(world, DiscoveryFixtures.entry(DiscoveryFixtures.node(1), false), 0L);
        dir.seen(world, DiscoveryFixtures.entry(DiscoveryFixtures.node(2), false), 0L);

        var online = dir.online(world, 0L);
        assertThat(online).extracting(PeerDirectory.Known::nodeId)
                .containsExactly(
                        DiscoveryFixtures.node(1),
                        DiscoveryFixtures.node(2),
                        DiscoveryFixtures.node(3));
    }

    @Test
    void evictsLeastRecentlySeenWhenTheWorldBoundIsHit() {
        PeerDirectory dir = new PeerDirectory(2, Long.MAX_VALUE);
        Bytes world = DiscoveryFixtures.worldHash("a");
        NodeId first = DiscoveryFixtures.node(1);
        dir.seen(world, DiscoveryFixtures.entry(first, false), 100L);
        dir.seen(world, DiscoveryFixtures.entry(DiscoveryFixtures.node(2), false), 200L);

        // Touching node 1 again makes node 2 the least-recently-seen → evicted on overflow.
        dir.seen(world, DiscoveryFixtures.entry(first, false), 300L);
        dir.seen(world, DiscoveryFixtures.entry(DiscoveryFixtures.node(3), false), 400L);

        var online = dir.all(world);
        assertThat(online).extracting(PeerDirectory.Known::nodeId)
                .containsExactlyInAnyOrder(first, DiscoveryFixtures.node(3));
    }

    @Test
    void forgetRemovesAPeerAndCollapsesEmptyWorlds() {
        PeerDirectory dir = new PeerDirectory();
        Bytes world = DiscoveryFixtures.worldHash("a");
        NodeId p = DiscoveryFixtures.node(1);
        dir.seen(world, DiscoveryFixtures.entry(p, false), 0L);

        dir.forget(world, p);

        assertThat(dir.size(world)).isZero();
        assertThat(dir.worlds()).doesNotContain(world);
    }

    @Test
    void rejectsNonPositiveBounds() {
        assertThatThrownBy(() -> new PeerDirectory(0, 1000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeerDirectory(10, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
