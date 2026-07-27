package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.storage.PersistedWorldKey;
import dev.nodera.storage.WorldOwnership;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The regression this whole lane exists for: an always-on peer must survive its own restart.</b>
 *
 * <p>Reproduced against the shipped worker on 2026-07-26 — share a world, restart the worker with
 * the same identity and archive directory, ask it for {@code NODERA-STATE}, and
 * {@code connected_worlds} comes back {@code []}. The node kept the world's bytes on disk while
 * telling every tracker it had none, and the companion app showed an empty world list that no
 * amount of waiting would fill. It looked like the app could not read the worker. The worker had
 * forgotten.
 *
 * <p>Each test constructs a <b>second</b> {@link WorldHostingService} over the same registry file,
 * which is what a restart is. Runs with no discovery endpoints so the announce paths are no-ops and
 * nothing here touches the network.
 */
final class WorldHostingPersistenceTest {

    @TempDir
    Path dir;

    private WorldHostingService newService(WorldRegistryStore registry) {
        return new WorldHostingService(NodeIdentity.generate(), NodeCapabilities.initial(),
                () -> "127.0.0.1:25620",
                new dev.nodera.peer.discovery.TrackerClient(List.of(), NodeIdentity.generate()),
                List.of(), worldId -> List.of(), registry);
    }

    private WorldRegistryStore registry() {
        return new WorldRegistryStore(dir.resolve("worlds.dat"));
    }

    private static String worldId(String seed) {
        return new dev.nodera.core.crypto.HashService().sha256(seed.getBytes()).toHex();
    }

    private static Bytes ownershipFor(String worldIdHex) {
        PersistedWorldKey key = PersistedWorldKey.generate(Bytes.fromHex(worldIdHex));
        CanonicalWriter w = new CanonicalWriter();
        WorldOwnership.create(NodeIdentity.generate(), key, 1000L).encode(w);
        return w.toBytes();
    }

    @Test
    @DisplayName("a hosted world is still hosted after the worker restarts")
    void aHostedWorldSurvivesTheWorker() {
        String world = worldId("shared");
        try (WorldHostingService first = newService(registry())) {
            assertThat(first.host(world, "My World", "{\"mc\":\"127.0.0.1:25599\",\"players\":3}"))
                    .isNull();
        }

        try (WorldHostingService restarted = newService(registry())) {
            assertThat(restarted.hostedWorlds())
                    .extracting(WorldHostingService.HostedWorld::name)
                    .containsExactly("My World");
            assertThat(restarted.hostedWorlds())
                    .extracting(WorldHostingService.HostedWorld::seeding)
                    .containsExactly(false);
        }
    }

    @Test
    @DisplayName("a supported world is still supported after the worker restarts")
    void aSeededWorldSurvivesTheWorker() {
        String world = worldId("supported");
        try (WorldHostingService first = newService(registry())) {
            assertThat(first.seed(world, "Someone else's")).isNull();
        }

        try (WorldHostingService restarted = newService(registry())) {
            assertThat(restarted.hostedWorlds())
                    .extracting(WorldHostingService.HostedWorld::seeding)
                    .as("a node that adopted a world keeps serving it after a restart")
                    .containsExactly(true);
        }
    }

    @Test
    @DisplayName("liveness is NOT restored: a restored world is 'shared, game closed'")
    void livenessIsNotRestoredFromDisk() {
        String world = worldId("live");
        try (WorldHostingService first = newService(registry())) {
            first.host(world, "W", "{\"mc\":\"127.0.0.1:25599\",\"players\":4}");
            assertThat(first.hostedWorlds().iterator().next().mcRoute()).isEqualTo("127.0.0.1:25599");
        }

        try (WorldHostingService restarted = newService(registry())) {
            WorldHostingService.HostedWorld world0 = restarted.hostedWorlds().iterator().next();
            // The game endpoint and the player count describe a RUNNING game. Restoring them would
            // advertise a world as joinable when the thing that made it joinable is gone.
            assertThat(world0.mcRoute()).isNull();
            assertThat(world0.players()).isZero();
        }
    }

    @Test
    @DisplayName("stopping a share is durable — it does not come back at the next start")
    void aStoppedWorldStaysStopped() {
        String world = worldId("stopped");
        try (WorldHostingService first = newService(registry())) {
            first.host(world, "W", "{}");
            first.stop(world);
        }

        try (WorldHostingService restarted = newService(registry())) {
            assertThat(restarted.hostedWorlds()).isEmpty();
        }
    }

    @Test
    @DisplayName("the date a world entered the network is not reset by a restart")
    void addedAtSurvives() throws Exception {
        String world = worldId("dated");
        long added;
        try (WorldHostingService first = newService(registry())) {
            first.host(world, "W", "{}");
            added = first.hostedWorlds().iterator().next().addedAtEpochMillis();
        }
        Thread.sleep(5);

        try (WorldHostingService restarted = newService(registry())) {
            assertThat(restarted.hostedWorlds().iterator().next().addedAtEpochMillis())
                    .isEqualTo(added);
        }
    }

    @Test
    @DisplayName("administration survives the restart, and the world key comes back with it")
    void ownershipSurvives() {
        String world = worldId("mine");
        Bytes claim = ownershipFor(world);
        try (WorldHostingService first = newService(registry())) {
            first.host(world, "Mine", "{}");
            first.bindOwnership(world, claim);
            assertThat(first.hostedWorlds().iterator().next().owned()).isTrue();
        }

        try (WorldHostingService restarted = newService(registry())) {
            WorldHostingService.HostedWorld back = restarted.hostedWorlds().iterator().next();
            assertThat(back.owned()).isTrue();
            assertThat(back.worldPublicKey()).isNotEqualTo(Bytes.empty());
            assertThat(restarted.administers(world)).isTrue();
        }
    }

    @Test
    @DisplayName("a world can be administered before it is ever shared")
    void ownershipDoesNotNeedAShareFirst() {
        String world = worldId("authored-not-shared");
        try (WorldHostingService service = newService(registry())) {
            service.bindOwnership(world, ownershipFor(world));

            // Visible immediately, not only after the next restart: the live list and the file
            // behind it must agree.
            assertThat(service.hostedWorlds())
                    .extracting(WorldHostingService.HostedWorld::owned)
                    .containsExactly(true);
        }

        try (WorldHostingService restarted = newService(registry())) {
            assertThat(restarted.administers(world)).isTrue();
        }
    }

    @Test
    @DisplayName("hosting somebody else's world does not make this node its administrator")
    void hostingIsNotOwning() {
        String world = worldId("theirs");
        try (WorldHostingService service = newService(registry())) {
            service.host(world, "Their World", "{}");

            // The distinction the Home screen is built on: this node serves the world, and has
            // nothing to say about who runs it.
            assertThat(service.hostedWorlds().iterator().next().owned()).isFalse();
            assertThat(service.administers(world)).isFalse();
        }
    }

    @Test
    @DisplayName("a service with no registry still works, it just forgets")
    void aMemoryOnlyServiceIsStillValid() {
        try (WorldHostingService service = newService(null)) {
            assertThat(service.host(worldId("ephemeral"), "W", "{}")).isNull();
            assertThat(service.hostedWorlds()).hasSize(1);
        }
    }
}
