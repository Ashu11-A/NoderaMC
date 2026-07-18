package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 20 acceptance #2: with the original bootstrap <b>offline</b>, a brand-new client still
 * reaches the mesh through each of the three independent mechanisms — a configured alternate, a
 * cached address, and a pasted invitation. Any one suffices, so no single host is a gatekeeper.
 *
 * <p>Headless: the {@link BootstrapClient} is transport-agnostic; the {@link BootstrapClient.Dialer}
 * models "did the route answer" without opening a socket.
 *
 * <p>Thread-context: single test thread.
 */
final class MultiBootstrapIT {

    private static final SignatureService SIGNATURES = new SignatureService();
    private static final Bytes WORLD = DiscoveryFixtures.worldHash("mesh");

    /**
     * @param workingRoute the one route that actually answers; every other route "refuses".
     * @return a dialer that simulates a mesh where only {@code workingRoute} is live.
     */
    private static BootstrapClient.Dialer meshWith(String workingRoute) {
        return candidate -> candidate.route().equals(workingRoute);
    }

    @Test
    void joinsViaAConfiguredAlternateWhenTheOriginalBootstrapIsOffline() {
        // The configured list names the (dead) original host first, then a live alternate.
        CachedPeerStore empty = new CachedPeerStore();
        BootstrapClient client =
                new BootstrapClient(List.of("original.host:25565", "alternate.host:25565"), empty, SIGNATURES);

        Optional<BootstrapClient.Candidate> joined =
                client.join(WORLD, null, meshWith("alternate.host:25565"));

        assertThat(joined).isPresent();
        assertThat(joined.get().route()).isEqualTo("alternate.host:25565");
        assertThat(joined.get().source()).isEqualTo(BootstrapClient.Source.CONFIGURED);
    }

    @Test
    void joinsViaACachedAddressWhenConfigIsStale() {
        // Config points only at the dead original host; a prior session cached a live peer.
        CachedPeerStore cached = new CachedPeerStore();
        cached.remember(new CachedPeerStore.CachedPeer(WORLD, DiscoveryFixtures.node(7),
                "remembered.peer:25565", 1234L));
        BootstrapClient client =
                new BootstrapClient(List.of("original.host:25565"), cached, SIGNATURES);

        Optional<BootstrapClient.Candidate> joined =
                client.join(WORLD, null, meshWith("remembered.peer:25565"));

        assertThat(joined).isPresent();
        assertThat(joined.get().source()).isEqualTo(BootstrapClient.Source.CACHED);
    }

    @Test
    void joinsViaAPastedInvitationWithEmptyConfigAndEmptyCache() {
        NodeIdentity friend = NodeIdentity.generate();
        String blob = InvitationCodec.encode(friend, java.util.UUID.randomUUID(), WORLD,
                List.of("original.host:25565", "invited.peer:25565"));

        BootstrapClient client = new BootstrapClient(List.of(), new CachedPeerStore(), SIGNATURES);

        Optional<BootstrapClient.Candidate> joined =
                client.join(WORLD, blob, meshWith("invited.peer:25565"));

        assertThat(joined).isPresent();
        assertThat(joined.get().route()).isEqualTo("invited.peer:25565");
        assertThat(joined.get().source()).isEqualTo(BootstrapClient.Source.INVITATION);
    }

    @Test
    void everyMechanismAloneReachesTheMesh() {
        // For each mechanism in isolation (the other two empty), the join succeeds — proving no
        // single mechanism is load-bearing on another.
        BootstrapClient configOnly = new BootstrapClient(List.of("c:1"), new CachedPeerStore(), SIGNATURES);
        assertThat(configOnly.join(WORLD, null, meshWith("c:1"))).isPresent();

        CachedPeerStore oneCache = new CachedPeerStore();
        oneCache.remember(new CachedPeerStore.CachedPeer(WORLD, DiscoveryFixtures.node(1), "c:1", 1L));
        BootstrapClient cacheOnly = new BootstrapClient(List.of(), oneCache, SIGNATURES);
        assertThat(cacheOnly.join(WORLD, null, meshWith("c:1"))).isPresent();

        NodeIdentity friend = NodeIdentity.generate();
        String blob = InvitationCodec.encode(friend, java.util.UUID.randomUUID(), WORLD, List.of("c:1"));
        BootstrapClient inviteOnly = new BootstrapClient(List.of(), new CachedPeerStore(), SIGNATURES);
        assertThat(inviteOnly.join(WORLD, blob, meshWith("c:1"))).isPresent();
    }

    @Test
    void failsCleanlyWhenAllThreeMechanismsAreExhausted() {
        BootstrapClient client =
                new BootstrapClient(List.of("dead:1"), new CachedPeerStore(), SIGNATURES);
        assertThat(client.join(WORLD, null, candidate -> false)).isEmpty();
    }
}
