package dev.nodera.headless;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 32/33: the world-hosting bookkeeping the worker exposes to the mod + dashboard. Runs with
 * <b>no configured discovery endpoints</b> so it never touches the network — the tracker announce /
 * rendezvous register calls are guarded on an empty endpoint list, leaving pure hosted-world state
 * to assert.
 */
final class WorldHostingServiceTest {

    private WorldHostingService newService() {
        NodeIdentity identity = NodeIdentity.generate();
        NodeCapabilities caps = NodeCapabilities.initial();
        return new WorldHostingService(identity, caps, () -> "127.0.0.1:25620", List.of(), List.of());
    }

    @Test
    void hostThenStopTracksWorlds() {
        try (WorldHostingService svc = newService()) {
            assertThat(svc.hostedWorlds()).isEmpty();

            assertThat(svc.host("abcdef01", "My World", "{}")).isNull();
            assertThat(svc.hostedWorlds())
                    .extracting(WorldHostingService.HostedWorld::name)
                    .containsExactly("My World");
            assertThat(svc.hostedWorlds())
                    .extracting(WorldHostingService.HostedWorld::worldIdHex)
                    .containsExactly("abcdef01");

            assertThat(svc.stop("abcdef01")).isNull();
            assertThat(svc.hostedWorlds()).isEmpty();
        }
    }

    @Test
    void hostIsIdempotentPerWorldId() {
        try (WorldHostingService svc = newService()) {
            svc.host("aa", "First", "{}");
            svc.host("aa", "First-renamed", "{}");
            assertThat(svc.hostedWorlds()).hasSize(1);
            assertThat(svc.hostedWorlds())
                    .extracting(WorldHostingService.HostedWorld::name)
                    .containsExactly("First-renamed");
        }
    }

    @Test
    void rejectsMissingAndMalformedWorldId() {
        try (WorldHostingService svc = newService()) {
            assertThat(svc.host("", "x", "{}")).isEqualTo("missing worldId");
            assertThat(svc.host(null, "x", "{}")).isEqualTo("missing worldId");
            assertThat(svc.host("nothex!!", "x", "{}")).isEqualTo("malformed worldId");
            assertThat(svc.hostedWorlds()).isEmpty();
        }
    }

    @Test
    void stopUnknownWorldIsQuietlyOk() {
        try (WorldHostingService svc = newService()) {
            assertThat(svc.stop("deadbeef")).isNull();
        }
    }

    @Test
    void blankNameFallsBackToWorldId() {
        try (WorldHostingService svc = newService()) {
            svc.host("cafe", "", "{}");
            assertThat(svc.hostedWorlds())
                    .extracting(WorldHostingService.HostedWorld::name)
                    .containsExactly("cafe");
        }
    }

    @Test
    void healthListsMatchConfiguredEndpoints() {
        try (WorldHostingService svc = newService()) {
            // No endpoints configured in this test → empty health lists (never null).
            assertThat(svc.trackerHealth()).isEmpty();
            assertThat(svc.rendezvousHealth()).isEmpty();
        }
    }
}
