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

    /**
     * This node's identity, stable across the "restarts" below.
     *
     * <p>Stable on purpose: a restart that came back as a different node would not be a restart, and
     * — since a claim is only <i>this node's</i> when it names this node — every ownership assertion
     * here would pass or fail for the wrong reason.
     */
    private final NodeIdentity self = NodeIdentity.generate();

    private WorldHostingService newService(WorldRegistryStore registry) {
        return newService(self, registry);
    }

    private WorldHostingService newService(NodeIdentity identity, WorldRegistryStore registry) {
        return new WorldHostingService(identity, NodeCapabilities.initial(),
                () -> "127.0.0.1:25620",
                new dev.nodera.peer.discovery.TrackerClient(List.of(), NodeIdentity.generate()),
                List.of(), worldId -> List.of(), registry);
    }

    /**
     * A service wired to a world-key store, so ownership can be re-bound from a surviving key
     * (W-DUP-2). Mirrors the production wiring in {@code HeadlessPeerMain}.
     */
    private WorldHostingService newServiceWithKeys(NodeIdentity identity, WorldRegistryStore registry,
                                                   WorldKeyStore keys) {
        return new WorldHostingService(identity, NodeCapabilities.initial(),
                () -> "127.0.0.1:25620",
                new dev.nodera.peer.discovery.TrackerClient(List.of(), NodeIdentity.generate()),
                List.of(), worldId -> List.of(), registry, keys);
    }

    private WorldRegistryStore registry() {
        return new WorldRegistryStore(dir.resolve("worlds.dat"));
    }

    private static String worldId(String seed) {
        return new dev.nodera.core.crypto.HashService().sha256(seed.getBytes()).toHex();
    }

    /** A verifying ownership claim for {@code worldIdHex}, minted by {@code owner}. */
    private static Bytes ownershipFor(String worldIdHex, NodeIdentity owner) {
        PersistedWorldKey key = PersistedWorldKey.generate(Bytes.fromHex(worldIdHex));
        CanonicalWriter w = new CanonicalWriter();
        WorldOwnership.create(owner, key, 1000L).encode(w);
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
            // UNKNOWN, not zero. Nothing is in this world to count anybody, and the two answers
            // used to share the value 0 — which is how every peer that was not hosting the game
            // published a confident "nobody is playing" for a world full of people.
            assertThat(world0.players()).isEqualTo(WorldHostingService.PLAYERS_UNKNOWN);
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
        Bytes claim = ownershipFor(world, self);
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
            service.bindOwnership(world, ownershipFor(world, self));

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
    @DisplayName("holding somebody else's ownership claim does not make this node the administrator")
    void anotherPeersClaimIsNotOurs() {
        // The live failure, 2026-07-27: two players in one session, one world, and BOTH companion
        // apps showing "You administer this" on it — with the administrator-only actions offered to
        // both. Neither of them had authored it: the claim on disk named a third node entirely. The
        // registry persists a claim for every world a peer keeps available, precisely so it can say
        // WHO runs the world, and "we have a claim" had been read as "the claim is ours".
        String world = worldId("someone-elses");
        NodeIdentity author = NodeIdentity.generate();
        Bytes theirClaim = ownershipFor(world, author);

        try (WorldHostingService service = newService(registry())) {
            service.bindOwnership(world, theirClaim);
            WorldHostingService.HostedWorld held = service.hostedWorlds().iterator().next();

            assertThat(held.owned())
                    .as("a claim naming another node is not this node's authority")
                    .isFalse();
            assertThat(service.administers(world)).isFalse();
            // The claim is still kept and still readable — that is what it is stored for.
            assertThat(held.ownershipRecord()).isEqualTo(theirClaim);
            assertThat(held.worldPublicKey()).isNotEqualTo(Bytes.empty());
        }

        // And it is still not ours after a restart, which is where the live symptom appeared: the
        // registry restore path bound the record without ever asking whose it was.
        try (WorldHostingService restarted = newService(registry())) {
            assertThat(restarted.hostedWorlds().iterator().next().owned()).isFalse();
            assertThat(restarted.administers(world)).isFalse();
        }

        // The author's own node reads the same bytes as authority, from the same file.
        try (WorldHostingService theirs = newService(author, registry())) {
            assertThat(theirs.hostedWorlds().iterator().next().owned()).isTrue();
            assertThat(theirs.administers(world)).isTrue();
        }
    }

    @Test
    @DisplayName("a forged ownership claim administers nothing")
    void anUnverifiableClaimIsRefused() {
        String world = worldId("forged");
        try (WorldHostingService service = newService(registry())) {
            // Right shape, wrong bytes: signatures that cover nothing. Ownership is decided by
            // verify(), not by the record being present and well-formed.
            service.bindOwnership(world, Bytes.fromHex("00112233445566778899aabbccddeeff"));

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

    /**
     * W-DUP-1: a registry row whose world this node can no longer serve stops being announced
     * within one refresh cycle, but its claim is retained for repair.
     */
    @Test
    @DisplayName("a world this node can no longer serve is suppressed but its row is kept (W-DUP-1)")
    void anUnservableWorldIsSuppressedWithinOneCycle() {
        String world = worldId("stale");
        try (WorldHostingService svc = newService(registry())) {
            assertThat(svc.host(world, "W", "{}")).isNull();
            assertThat(svc.hostedWorlds().iterator().next().announceSuppressed())
                    .as("a servable world is announced")
                    .isFalse();

            // The world's bytes vanished from this node (a moved save, a drained content store): it
            // can no longer honour a join, so it must stop being announced.
            svc.bindServability(id -> !id.equals(world));
            svc.reconcile();

            assertThat(svc.hostedWorlds().iterator().next().announceSuppressed())
                    .as("an unservable world is suppressed within one refresh cycle")
                    .isTrue();
            // The claim is retained for repair — the row is NOT dropped from the live set ...
            assertThat(svc.hostedWorlds()).hasSize(1);
            // ... nor from the registry a restart would read.
            assertThat(new WorldRegistryStore(dir.resolve("worlds.dat")).find(world)).isPresent();

            // A repair makes it servable again: the announce resumes, under the SAME identity (no
            // second row, no re-derivation).
            svc.bindServability(id -> true);
            svc.reconcile();
            assertThat(svc.hostedWorlds().iterator().next().announceSuppressed()).isFalse();
            assertThat(svc.hostedWorlds()).hasSize(1);
        }
    }

    /**
     * The servability test is about the bytes at rest. A world a game is serving RIGHT NOW is
     * servable whatever the content plane says, or the worker would stop announcing a world people
     * are playing in the moment its archive stream lagged.
     */
    @Test
    @DisplayName("a world with a live game route is never suppressed (W-DUP-1)")
    void aLiveWorldIsNeverSuppressed() {
        String world = worldId("live");
        try (WorldHostingService svc = newService(registry())) {
            assertThat(svc.host(world, "W", "{\"mc\":\"127.0.0.1:25565\"}")).isNull();

            svc.bindServability(id -> false); // nothing is servable at rest
            svc.reconcile();

            assertThat(svc.hostedWorlds().iterator().next().announceSuppressed())
                    .as("a running game is serving the world regardless of the content plane")
                    .isFalse();
        }
    }

    /**
     * W-DUP-2: {@code stop} then re-{@code seed} a world whose {@code .worldkey} exists on disk;
     * the registry row still reads owned.
     */
    @Test
    @DisplayName("stop then re-seed a world whose key exists reads as owned again (W-DUP-2)")
    void ownershipSurvivesStopAndReSeed() {
        String world = worldId("mine");
        WorldKeyStore keys = new WorldKeyStore(dir.resolve("world-keys"));
        // This node authored the world — its key is minted and persists on disk.
        keys.loadOrGenerate(world);

        try (WorldHostingService svc = newServiceWithKeys(self, registry(), keys)) {
            assertThat(svc.host(world, "Mine", "{}")).isNull();
            assertThat(svc.hostedWorlds().iterator().next().owned())
                    .as("host re-binds ownership from the surviving key")
                    .isTrue();

            // stop drops the in-memory world AND the registry row, but the .worldkey is NOT removed
            // (see WorldRegistryStore.remove / WorldHostingService.stop).
            assertThat(svc.stop(world)).isNull();
            assertThat(svc.hostedWorlds()).isEmpty();
            assertThat(keys.administers(world))
                    .as("the key survives stop on disk")
                    .isTrue();

            // re-seed recreates the row carrying no ownership record in hand — the fix re-binds it
            // from the key the stop never removed, so an administered world stays administered.
            assertThat(svc.seed(world, "Mine")).isNull();
            assertThat(svc.hostedWorlds().iterator().next().owned())
                    .as("an administered world reads as owned after stop → re-seed")
                    .isTrue();
            // The registry a restart would read agrees with the live world.
            assertThat(new WorldRegistryStore(dir.resolve("worlds.dat")).find(world)
                    .orElseThrow().owned()).isTrue();
        }
    }

    @Test
    @DisplayName("re-binding ownership from the key store never invents authority for a foreign world")
    void aForeignWorldIsNotReAdministered() {
        String foreign = worldId("theirs");
        try (WorldHostingService svc =
                     newServiceWithKeys(self, registry(), new WorldKeyStore(dir.resolve("k")))) {
            // No key exists for this world, so it must read as supported, not owned.
            assertThat(svc.seed(foreign, "Their World")).isNull();
            assertThat(svc.hostedWorlds().iterator().next().owned()).isFalse();
            assertThat(svc.administers(foreign)).isFalse();
        }
    }
}
