package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.storage.PersistedWorldKey;
import dev.nodera.storage.WorldOwnership;
import dev.nodera.storage.WorldRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one-shot repair for a registry that already holds duplicate rows for one save (W-DUP-4).
 *
 * <p>Each test builds a registry directly in the canonical wire form (so exact-duplicate ids can be
 * written, which the live {@link WorldRegistryStore} deduplicates on load) and asserts the merge
 * leaves one row per save, the survivor chosen by the persisted {@code nodera-world.dat} pin.
 */
final class WorldRegistryMergeToolTest {

    @TempDir
    Path dir;

    private Path registry() {
        return dir.resolve("worlds.dat");
    }

    private static String worldId(String seed) {
        return new dev.nodera.core.crypto.HashService().sha256(seed.getBytes()).toHex();
    }

    private static WorldRegistry.Entry row(String idHex, String name, boolean supporting,
                                           Bytes ownership) {
        return new WorldRegistry.Entry(Bytes.fromHex(idHex), name, supporting, 1000L, 1000L,
                ownership);
    }

    private static Bytes ownershipFor(String worldIdHex) {
        PersistedWorldKey key = PersistedWorldKey.generate(Bytes.fromHex(worldIdHex));
        CanonicalWriter w = new CanonicalWriter();
        WorldOwnership.create(NodeIdentity.generate(), key, 1000L).encode(w);
        return w.toBytes();
    }

    /** Write rows in the canonical wire form (allows exact-duplicate ids, unlike the live store). */
    private void writeRegistry(Path file, List<WorldRegistry.Entry> rows) {
        CanonicalWriter w = new CanonicalWriter(512);
        new WorldRegistry(rows).encode(w);
        LocalFiles.writeAtomically(file, w.toByteArray());
    }

    private List<WorldRegistry.Entry> readRegistry(Path file) throws Exception {
        return WorldRegistry.decode(new CanonicalReader(Files.readAllBytes(file))).entries();
    }

    @Test
    @DisplayName("a stale duplicate mint is removed, leaving one row per save (W-DUP-4)")
    void leavesOneRowPerSaveSurvivorChosenByThePin() throws Exception {
        String canonical = worldId("canonical"); // the id the save's nodera-world.dat pins
        String stale = worldId("stale-mint");     // an orphaned re-derivation this node keyed
        String supported = worldId("theirs");      // a supported world this node does not own
        writeRegistry(registry(), List.of(
                row(canonical, "My World", false, ownershipFor(canonical)),
                row(stale, "My World", false, ownershipFor(stale)),
                row(supported, "Their World", true, Bytes.empty())));

        WorldRegistryMergeTool.MergeOutcome out = WorldRegistryMergeTool.merge(registry(),
                Map.of("MyWorld", canonical),
                Set.of(canonical, stale),
                false);

        assertThat(out.rowsBefore()).isEqualTo(3);
        assertThat(out.rowsAfter()).isEqualTo(2);
        assertThat(out.removed()).extracting(WorldRegistryMergeTool.RemovedRow::worldIdHex)
                .containsExactly(stale);
        assertThat(out.kept()).extracting(e -> e.worldIdHex().toLowerCase())
                .containsExactlyInAnyOrder(canonical, supported);
        assertThat(out.backupFile()).exists();

        // The merged registry on disk has one row per save + the supported world.
        assertThat(readRegistry(registry())).extracting(e -> e.worldIdHex().toLowerCase())
                .containsExactlyInAnyOrder(canonical, supported);
    }

    @Test
    @DisplayName("dry-run reports the plan without writing or backing up")
    void dryRunWritesNothing() throws Exception {
        String canonical = worldId("canonical");
        String stale = worldId("stale-mint");
        byte[] before = canonicalRegistryBytes(canonical, stale);

        WorldRegistryMergeTool.MergeOutcome out = WorldRegistryMergeTool.merge(registry(),
                Map.of("MyWorld", canonical), Set.of(canonical, stale), true);

        assertThat(out.dryRun()).isTrue();
        assertThat(out.backupFile()).isNull();
        assertThat(out.removed()).extracting(WorldRegistryMergeTool.RemovedRow::worldIdHex)
                .containsExactly(stale);
        // The file is byte-identical to the original.
        assertThat(Files.readAllBytes(registry())).isEqualTo(before);
    }

    @Test
    @DisplayName("exact-duplicate rows for one id collapse to a single survivor")
    void exactDuplicatesCollapse() throws Exception {
        String id = worldId("dup");
        Bytes verifying = ownershipFor(id);
        writeRegistry(registry(), List.of(
                row(id, "W", false, Bytes.empty()),
                row(id, "W", false, verifying),
                row(id, "W", true, Bytes.empty())));

        WorldRegistryMergeTool.MergeOutcome out = WorldRegistryMergeTool.merge(registry(),
                Map.of("MyWorld", id), Set.of(id), false);

        assertThat(out.rowsBefore()).isEqualTo(3);
        assertThat(out.rowsAfter()).isEqualTo(1);
        assertThat(out.kept()).hasSize(1);
        // The survivor is the variant whose ownership verifies, and hosting wins over seeding.
        WorldRegistry.Entry survivor = out.kept().get(0);
        assertThat(survivor.ownershipRecord()).isEqualTo(verifying);
        assertThat(survivor.supporting()).isFalse();
    }

    @Test
    @DisplayName("a supported world is never merged away")
    void aSupportedWorldIsKeptEvenWhenNotPinned() {
        String supported = worldId("theirs");
        writeRegistry(registry(), List.of(row(supported, "Their World", true, Bytes.empty())));

        WorldRegistryMergeTool.MergeOutcome out = WorldRegistryMergeTool.merge(registry(),
                Map.of(), Set.of(), false);

        assertThat(out.kept()).extracting(e -> e.worldIdHex().toLowerCase())
                .containsExactly(supported);
        assertThat(out.removed()).isEmpty();
        assertThat(out.backupFile()).isNull();
    }

    @Test
    @DisplayName("two saves pinning the same id are quarantined, not guessed")
    void ambiguousPinnedIdIsQuarantined() {
        String id = worldId("contested");
        writeRegistry(registry(), List.of(row(id, "W", false, Bytes.empty())));

        WorldRegistryMergeTool.MergeOutcome out = WorldRegistryMergeTool.merge(registry(),
                Map.of("SaveA", id, "SaveB", id), Set.of(id), false);

        assertThat(out.quarantined()).hasSize(1);
        assertThat(out.kept()).extracting(e -> e.worldIdHex().toLowerCase()).containsExactly(id);
        assertThat(out.removed()).isEmpty();
        assertThat(out.backupFile()).isNull();
    }

    @Test
    @DisplayName("one id with conflicting ownership is quarantined, not chosen between")
    void conflictingOwnershipIsQuarantined() {
        String id = worldId("conflict");
        // Two different world public keys recorded for one id — picking one is choosing an admin.
        writeRegistry(registry(), List.of(
                row(id, "W", false, ownershipFor(id)),
                row(id, "W", false, ownershipFor(id + "ff"))));

        WorldRegistryMergeTool.MergeOutcome out = WorldRegistryMergeTool.merge(registry(),
                Map.of("MyWorld", id), Set.of(id), false);

        assertThat(out.quarantined()).hasSize(1);
        assertThat(out.kept()).hasSize(2);
        assertThat(out.removed()).isEmpty();
    }

    @Test
    @DisplayName("a registry that cannot be decoded is left untouched, not overwritten")
    void anUndecodableRegistryAborts() throws Exception {
        Files.write(registry(), "not a canonical registry".getBytes());

        assertThatThrownBy(() -> WorldRegistryMergeTool.merge(registry(), Map.of(), Set.of(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refuses to overwrite");
        // The bad file is exactly as it was.
        assertThat(Files.readString(registry())).isEqualTo("not a canonical registry");
    }

    @Test
    @DisplayName("a registry with no duplicates is unchanged and not backed up")
    void aCleanRegistryIsLeftAlone() {
        String id = worldId("clean");
        writeRegistry(registry(), List.of(row(id, "W", false, Bytes.empty())));

        WorldRegistryMergeTool.MergeOutcome out = WorldRegistryMergeTool.merge(registry(),
                Map.of("MyWorld", id), Set.of(id), false);

        assertThat(out.rowsBefore()).isEqualTo(out.rowsAfter());
        assertThat(out.removed()).isEmpty();
        assertThat(out.quarantined()).isEmpty();
        assertThat(out.backupFile()).isNull();
    }

    /**
     * The exit test names the <b>persisted</b> {@code nodera-world.dat} as the chooser of the
     * survivor, so this drives the whole tool from disk: real pin files under a saves directory,
     * real {@code .worldkey} files under a world-keys directory, and no hand-fed maps.
     */
    @Test
    @DisplayName("the survivor is chosen by the persisted nodera-world.dat on disk (W-DUP-4)")
    void survivorIsChosenByTheOnDiskPin() throws Exception {
        NodeIdentity author = NodeIdentity.generate();
        String canonical = worldId("canonical");
        String stale = worldId("stale-mint");

        // A real save carrying a real signed pin.
        Path save = Files.createDirectories(dir.resolve("saves").resolve("MyWorld"));
        CanonicalWriter pin = new CanonicalWriter();
        dev.nodera.storage.WorldIdentity.createPinned(author, Bytes.fromHex(canonical), 1000L,
                true, true, false, Bytes.empty()).encode(pin);
        Files.write(save.resolve("nodera-world.dat"), pin.toByteArray());

        // Real world keys: this node minted both ids for the same save.
        Path keysDir = dir.resolve("world-keys");
        WorldKeyStore keys = new WorldKeyStore(keysDir);
        keys.loadOrGenerate(canonical);
        keys.loadOrGenerate(stale);

        writeRegistry(registry(), List.of(
                row(canonical, "My World", false, ownershipFor(canonical)),
                row(stale, "My World", false, ownershipFor(stale))));

        Map<String, String> pins = WorldRegistryMergeTool.discoverPinnedIds(dir.resolve("saves"));
        Set<String> administered = WorldRegistryMergeTool.discoverAdministeredIds(keysDir);
        assertThat(pins).containsExactly(Map.entry("MyWorld", canonical));
        assertThat(administered).containsExactlyInAnyOrder(canonical, stale);

        WorldRegistryMergeTool.MergeOutcome out =
                WorldRegistryMergeTool.merge(registry(), pins, administered, false);

        assertThat(out.rowsAfter()).isEqualTo(1);
        assertThat(readRegistry(registry())).extracting(e -> e.worldIdHex().toLowerCase())
                .containsExactly(canonical);
        // The key of the removed id is NOT destroyed — a rollback of the merge restores authority.
        assertThat(new WorldKeyStore(keysDir).administers(stale)).isTrue();
    }

    private byte[] canonicalRegistryBytes(String... ids) throws Exception {
        java.util.List<WorldRegistry.Entry> rows = new java.util.ArrayList<>();
        for (String id : ids) {
            rows.add(row(id, "W", false, Bytes.empty()));
        }
        writeRegistry(registry(), rows);
        return Files.readAllBytes(registry());
    }
}
