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

    @Test
    void rehostRefreshesTheGameEndpointAndPlayerCount() {
        try (WorldHostingService svc = newService()) {
            // The mod's first HOST carries the open game endpoint + player count.
            assertThat(svc.host("abcdef01", "My World",
                    "{\"listed\":true,\"mc\":\"192.168.0.9:25565\",\"players\":3}")).isNull();
            WorldHostingService.HostedWorld world = svc.hostedWorlds().iterator().next();
            assertThat(world.mcRoute()).isEqualTo("192.168.0.9:25565");
            assertThat(world.players()).isEqualTo(3);

            // Game closes → the mod re-HOSTs without the endpoint; the world stays hosted but the
            // joinability signal drops.
            assertThat(svc.host("abcdef01", "My World", "{\"listed\":true}")).isNull();
            world = svc.hostedWorlds().iterator().next();
            assertThat(world.mcRoute()).isNull();
            assertThat(world.players()).isZero();
        }
    }

    @Test
    void optionsJsonFieldExtractionIsForgiving() {
        assertThat(WorldHostingService.jsonStringField(null, "mc")).isNull();
        assertThat(WorldHostingService.jsonStringField("{}", "mc")).isNull();
        assertThat(WorldHostingService.jsonStringField("{\"mc\":\"\"}", "mc")).isNull();
        assertThat(WorldHostingService.jsonStringField(
                "{\"mc\" : \"a.b:1\"}", "mc")).isEqualTo("a.b:1");
        assertThat(WorldHostingService.jsonLongField("{\"players\":42}", "players")).isEqualTo(42);
        assertThat(WorldHostingService.jsonLongField("not json", "players")).isZero();
    }
}
