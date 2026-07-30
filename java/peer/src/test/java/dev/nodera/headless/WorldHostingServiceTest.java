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
    void aPaddedUpperCaseWorldIdCanStillBeStopped() {
        try (WorldHostingService svc = newService()) {
            assertThat(svc.host("  ABCDEF01 ", "My World", "{}")).isNull();

            // `host` and `seed` used to key the live map on the caller's exact string while `stop`
            // trimmed it and the registry lower-cased it. A padded or upper-cased id therefore made
            // an entry that `stop` could not remove and that kept announcing until the process died
            // — a duplicate on the network that outlived the thing that created it.
            assertThat(svc.hostedWorlds())
                    .extracting(WorldHostingService.HostedWorld::worldIdHex)
                    .containsExactly("abcdef01");
            assertThat(svc.stop("ABCDEF01")).isNull();
            assertThat(svc.hostedWorlds()).isEmpty();
        }
    }

    @Test
    void oneWorldIsOneEntryHoweverItIsSpelled() {
        try (WorldHostingService svc = newService()) {
            svc.host("abcdef01", "My World", "{}");
            svc.host("ABCDEF01", "My World", "{}");
            svc.seed(" abcdef01", "My World");

            assertThat(svc.hostedWorlds()).hasSize(1);
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

    /**
     * "Somebody here is playing in it" is a lease, not a flag.
     *
     * <p>The alternative — set on join, cleared on leave — is correct until one goodbye is lost,
     * and a game that crashes never sends one. The screen would then tell a player they are in a
     * world they closed hours ago, on the one screen that exists to say what their node is doing.
     */
    @Test
    void theConnectedClaimExpiresOnItsOwn() {
        try (WorldHostingService svc = newService()) {
            assertThat(svc.seed("abcdef01", "Their World")).isNull();
            WorldHostingService.HostedWorld world = svc.hostedWorlds().iterator().next();
            assertThat(world.connected())
                    .as("supporting a world is not being in it")
                    .isFalse();

            svc.markConnected("abcdef01", 60);
            assertThat(world.connected()).isTrue();

            // An expired lease reads false with no message having been sent — this is the whole
            // point of expressing it as a deadline.
            svc.markConnected("abcdef01", -1);
            assertThat(world.connected()).isFalse();

            // And a clean goodbye ends it immediately rather than waiting the lease out.
            svc.markConnected("abcdef01", 60);
            svc.markConnected("abcdef01", 0);
            assertThat(world.connected()).isFalse();

            // A lease on a world this node has never heard of describes nothing, so it does nothing.
            svc.markConnected("beef0000", 60);
            assertThat(svc.hostedWorlds()).hasSize(1);
        }
    }

    /**
     * A player count is an observation by a node that is in the world, and it expires.
     *
     * <p>The chronic complaint this replaces: every peer except the one hosting the game reported
     * "0 players" for a world with people standing in it, with the same confidence as the host
     * reporting the truth — because {@code players} was a bare {@code long} and "nobody told me"
     * and "nobody is there" were both {@code 0}.
     */
    @Test
    void aPlayerCountIsUnknownUntilSomebodyInTheWorldReportsIt() {
        try (WorldHostingService svc = newService()) {
            assertThat(svc.seed("abcdef01", "Their World")).isNull();
            WorldHostingService.HostedWorld world = svc.hostedWorlds().iterator().next();

            assertThat(world.players())
                    .as("a node holding only the bytes cannot see who is playing")
                    .isEqualTo(WorldHostingService.PLAYERS_UNKNOWN);

            // A node IN the world says it is empty. That is a real answer, and distinct.
            svc.markPlayers("abcdef01", 0, 60);
            assertThat(world.players()).isZero();

            svc.markPlayers("abcdef01", 3, 60);
            assertThat(world.players()).isEqualTo(3);

            // The reporter goes away without a word; the claim stops standing on its own.
            svc.markPlayers("abcdef01", 3, -1);
            assertThat(world.players()).isEqualTo(WorldHostingService.PLAYERS_UNKNOWN);
        }
    }

    /** An unsure caller must not be able to blank a number somebody else is standing behind. */
    @Test
    void aNegativeReportLeavesTheKnownCountAlone() {
        try (WorldHostingService svc = newService()) {
            assertThat(svc.seed("abcdef01", "W")).isNull();
            svc.markPlayers("abcdef01", 2, 60);

            svc.markPlayers("abcdef01", WorldHostingService.PLAYERS_UNKNOWN, 60);

            assertThat(svc.hostedWorlds().iterator().next().players()).isEqualTo(2);
        }
    }

    /**
     * A caller with no name to offer must not erase the name we have.
     *
     * <p>`seed` falls back to the world id when the name is blank, and then assigned it — so every
     * nameless call renamed the world to its own 64-character id. Harmless while nothing called it
     * that way; {@code NODERA-JOIN} now does, repeatedly, for as long as a player is in the world.
     */
    @Test
    void aNamelessSeedKeepsTheNameTheWorldAlreadyHas() {
        try (WorldHostingService svc = newService()) {
            assertThat(svc.seed("abcdef01", "Their World")).isNull();
            assertThat(svc.seed("abcdef01", "")).isNull();

            assertThat(svc.hostedWorlds())
                    .extracting(WorldHostingService.HostedWorld::name)
                    .containsExactly("Their World");

            // A world first learned about namelessly still lists under its id, and a name arriving
            // later replaces it.
            assertThat(svc.seed("beef0000", "")).isNull();
            assertThat(svc.hostedWorlds())
                    .extracting(WorldHostingService.HostedWorld::name)
                    .containsExactlyInAnyOrder("Their World", "beef0000");
            assertThat(svc.seed("beef0000", "Named Later")).isNull();
            assertThat(svc.hostedWorlds())
                    .extracting(WorldHostingService.HostedWorld::name)
                    .containsExactlyInAnyOrder("Their World", "Named Later");
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
