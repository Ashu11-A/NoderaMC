package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Multi-bootstrap tries configured → cached → invitation in order and returns the first that
 * answers. The whole point is that no single mechanism (including the original host) can lock a
 * peer out of a world permanently.
 *
 * <p>Thread-context: single test thread.
 */
final class BootstrapClientTest {

    private static final SignatureService SIGNATURES = new SignatureService();

    private static BootstrapClient client(List<String> config, CachedPeerStore cached) {
        return new BootstrapClient(config, cached, SIGNATURES);
    }

    @Test
    void prefersConfiguredThenCachedThenInvitationByFreshness() {
        Bytes world = DiscoveryFixtures.worldHash("a");
        CachedPeerStore cached = new CachedPeerStore();
        cached.remember(new CachedPeerStore.CachedPeer(world, DiscoveryFixtures.node(2), "cached:2", 1L));
        BootstrapClient c = client(List.of("configured:1"), cached);

        List<BootstrapClient.Candidate> candidates = c.candidates(world, null);

        assertThat(candidates).extracting(BootstrapClient.Candidate::route)
                .containsExactly("configured:1", "cached:2");
        assertThat(candidates).extracting(BootstrapClient.Candidate::source)
                .containsExactly(BootstrapClient.Source.CONFIGURED, BootstrapClient.Source.CACHED);
    }

    @Test
    void deDuplicatesRoutesPreservingEarliestPosition() {
        Bytes world = DiscoveryFixtures.worldHash("a");
        CachedPeerStore cached = new CachedPeerStore();
        // Same route in config and cache → dialed once, at its configured position.
        cached.remember(new CachedPeerStore.CachedPeer(world, DiscoveryFixtures.node(1), "shared:1", 1L));
        BootstrapClient c = client(List.of("shared:1", "other:2"), cached);

        assertThat(c.candidates(world, null)).extracting(BootstrapClient.Candidate::route)
                .containsExactly("shared:1", "other:2");
    }

    @Test
    void triesEveryMechanismUntilOneAnswersAndReportsWhichWorked() {
        Bytes world = DiscoveryFixtures.worldHash("a");
        CachedPeerStore cached = new CachedPeerStore();
        cached.remember(new CachedPeerStore.CachedPeer(world, DiscoveryFixtures.node(1), "cached:1", 1L));
        BootstrapClient c = client(List.of("down:1"), cached);

        // The configured host is down; a dead address must not abort the bootstrap.
        Optional<BootstrapClient.Candidate> winner = c.join(world, null, candidate -> {
            throw new RuntimeException("connection refused");
        });
        assertThat(winner).isEmpty();   // every candidate threw → join fails, but returns cleanly

        // Now let the cached route succeed; record the full dial order.
        java.util.List<String> dialed = new java.util.ArrayList<>();
        winner = c.join(world, null, candidate -> {
            dialed.add(candidate.route());
            return candidate.route().startsWith("cached");
        });
        assertThat(winner).isPresent();
        assertThat(winner.get().source()).isEqualTo(BootstrapClient.Source.CACHED);
        // Configured is tried first, then cached — proving the order is freshness-based and the
        // dead configured host did not prevent reaching the cached one.
        assertThat(dialed).containsExactly("down:1", "cached:1");
    }

    @Test
    void joinsViaAnInvitationWhenConfigAndCacheAreEmpty() {
        Bytes world = DiscoveryFixtures.worldHash("a");
        NodeIdentity friend = NodeIdentity.generate();
        String blob = InvitationCodec.encode(friend, java.util.UUID.randomUUID(), world,
                List.of("friend.example:25565"));

        BootstrapClient c = client(List.of(), new CachedPeerStore());
        AtomicInteger attempts = new AtomicInteger();
        Optional<BootstrapClient.Candidate> winner = c.join(world, blob, candidate -> {
            attempts.incrementAndGet();
            return true;
        });

        assertThat(winner).isPresent();
        assertThat(winner.get().source()).isEqualTo(BootstrapClient.Source.INVITATION);
        assertThat(winner.get().route()).isEqualTo("friend.example:25565");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void anInvitationForADifferentWorldIsRefused() {
        Bytes a = DiscoveryFixtures.worldHash("a");
        Bytes b = DiscoveryFixtures.worldHash("b");
        NodeIdentity friend = NodeIdentity.generate();
        String blob = InvitationCodec.encode(friend, java.util.UUID.randomUUID(), b, List.of("h:1"));

        BootstrapClient c = client(List.of(), new CachedPeerStore());
        assertThatThrownBy(() -> c.candidates(a, blob))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not ");
    }

    @Test
    void returnsEmptyWhenEveryMechanismFails() {
        Bytes world = DiscoveryFixtures.worldHash("a");
        BootstrapClient c = client(List.of(), new CachedPeerStore());
        assertThat(c.join(world, null, candidate -> false)).isEmpty();
    }
}
