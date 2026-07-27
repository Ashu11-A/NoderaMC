package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.storage.PersistedWorldKey;
import dev.nodera.storage.WorldOwnership;
import dev.nodera.storage.WorldRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The peer's durable answer to "what am I keeping on the network".
 *
 * <p>Every test here is written against a <b>second store reading the same file</b> rather than
 * against the first store's memory, because the only interesting property is the one the worker
 * needed and did not have: that the answer survives the process.
 */
final class WorldRegistryStoreTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("worlds.dat");
    }

    private static String worldId(String seed) {
        return new dev.nodera.core.crypto.HashService().sha256(seed.getBytes()).toHex();
    }

    private static Bytes ownershipFor(String worldIdHex) {
        PersistedWorldKey key = PersistedWorldKey.generate(Bytes.fromHex(worldIdHex));
        WorldOwnership claim = WorldOwnership.create(NodeIdentity.generate(), key, 1000L);
        CanonicalWriter w = new CanonicalWriter();
        claim.encode(w);
        return w.toBytes();
    }

    @Test
    @DisplayName("a shared world is still there after the process that shared it is gone")
    void worldsSurviveARestart() {
        String world = worldId("survivor");
        new WorldRegistryStore(file()).put(world, "My World", false, Bytes.empty());

        WorldRegistryStore reopened = new WorldRegistryStore(file());

        assertThat(reopened.entries()).hasSize(1);
        WorldRegistry.Entry restored = reopened.find(world).orElseThrow();
        assertThat(restored.name()).isEqualTo("My World");
        assertThat(restored.supporting()).isFalse();
    }

    @Test
    @DisplayName("re-sharing a world keeps the date it first entered the network")
    void addedAtIsNotResetByAReShare() throws Exception {
        String world = worldId("kept");
        WorldRegistryStore store = new WorldRegistryStore(file());
        long first = store.put(world, "W", false, Bytes.empty()).addedAtEpochMillis();

        Thread.sleep(5);
        long second = store.put(world, "W renamed", false, Bytes.empty()).addedAtEpochMillis();

        // "Date added" is a fact about the world, not about the most recent share.
        assertThat(second).isEqualTo(first);
        assertThat(new WorldRegistryStore(file()).find(world).orElseThrow().name())
                .isEqualTo("W renamed");
    }

    @Test
    @DisplayName("hosting is the stronger claim: a later seed call cannot demote it")
    void hostingIsNeverDemotedBySeeding() {
        String world = worldId("hosted");
        WorldRegistryStore store = new WorldRegistryStore(file());
        store.put(world, "W", false, Bytes.empty());

        store.put(world, "W", true, Bytes.empty());

        assertThat(store.find(world).orElseThrow().supporting())
                .as("the replication lane adopting a world we host must not make us a mere seeder")
                .isFalse();
    }

    @Test
    @DisplayName("a world we only supported can be promoted to one we host")
    void seedingIsPromotedByHosting() {
        String world = worldId("adopted");
        WorldRegistryStore store = new WorldRegistryStore(file());
        store.put(world, "W", true, Bytes.empty());

        store.put(world, "W", false, Bytes.empty());

        assertThat(store.find(world).orElseThrow().supporting()).isFalse();
    }

    @Test
    @DisplayName("an ownership claim survives a restart and still verifies")
    void ownershipSurvivesARestart() {
        String world = worldId("mine");
        new WorldRegistryStore(file()).put(world, "Mine", false, ownershipFor(world));

        WorldRegistry.Entry restored = new WorldRegistryStore(file()).find(world).orElseThrow();

        assertThat(restored.owned()).isTrue();
        assertThat(restored.ownership()).isPresent();
        assertThat(restored.ownership().orElseThrow().verify()).isTrue();
    }

    @Test
    @DisplayName("a re-share without an ownership record does not drop the one already stored")
    void ownershipIsNotErasedByALaterPut() {
        String world = worldId("mine");
        WorldRegistryStore store = new WorldRegistryStore(file());
        store.put(world, "Mine", false, ownershipFor(world));

        // The mod re-hosts the world; that call knows nothing about keys.
        store.put(world, "Mine", false, Bytes.empty());

        assertThat(store.find(world).orElseThrow().owned())
                .as("a routine re-host must not cost the world its administrator")
                .isTrue();
    }

    @Test
    @DisplayName("a corrupt ownership record costs the badge, not the world")
    void anUnreadableOwnershipRecordIsNotOwnership() {
        String world = worldId("corrupt");
        WorldRegistryStore store = new WorldRegistryStore(file());
        store.put(world, "W", false, Bytes.unsafeWrap(new byte[]{9, 9, 9, 9}));

        WorldRegistry.Entry entry = new WorldRegistryStore(file()).find(world).orElseThrow();

        assertThat(entry.name()).isEqualTo("W");
        assertThat(entry.ownership()).as("nothing verifiable, so nothing claimed").isEmpty();
    }

    @Test
    @DisplayName("stopping a share removes the world from the next start")
    void removeIsDurable() {
        String world = worldId("stopped");
        WorldRegistryStore store = new WorldRegistryStore(file());
        store.put(world, "W", false, Bytes.empty());

        assertThat(store.remove(world)).isTrue();
        assertThat(store.remove(world)).as("removing twice is not an error").isFalse();
        assertThat(new WorldRegistryStore(file()).entries()).isEmpty();
    }

    @Test
    @DisplayName("a content change bumps last-updated and nothing else")
    void touchUpdatesOnlyTheTimestamp() {
        String world = worldId("touched");
        WorldRegistryStore store = new WorldRegistryStore(file());
        long added = store.put(world, "W", false, Bytes.empty()).addedAtEpochMillis();

        store.touch(world, added + 60_000);

        WorldRegistry.Entry entry = new WorldRegistryStore(file()).find(world).orElseThrow();
        assertThat(entry.updatedAtEpochMillis()).isEqualTo(added + 60_000);
        assertThat(entry.addedAtEpochMillis()).isEqualTo(added);
    }

    @Test
    @DisplayName("a registry that will not decode starts empty rather than stopping the worker")
    void aCorruptFileDoesNotStopTheNode() throws Exception {
        Files.write(file(), "not a canonical registry".getBytes());

        WorldRegistryStore store = new WorldRegistryStore(file());

        // A node that refuses to boot because one file went bad serves nothing at all; one that
        // boots having forgotten still serves everything a game re-shares.
        assertThat(store.entries()).isEmpty();
        assertThat(Files.exists(file())).as("the bad file is left for an operator to look at").isTrue();
    }

    @Test
    @DisplayName("the registry is written owner-only")
    void theFileIsNotWorldReadable() throws Exception {
        new WorldRegistryStore(file()).put(worldId("w"), "W", false, Bytes.empty());

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file());

        assertThat(permissions).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    @DisplayName("a fresh node has an empty registry and no file")
    void afreshNodeStartsEmpty() {
        WorldRegistryStore store = new WorldRegistryStore(file());

        assertThat(store.entries()).isEmpty();
        assertThat(store.find(worldId("nothing"))).isEmpty();
        assertThat(Files.exists(file())).as("nothing shared, nothing written").isFalse();
    }
}
