package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.membership.WorldDeletionGossip;
import dev.nodera.protocol.membership.WorldRevivalGossip;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.storage.PersistedWorldKey;
import dev.nodera.storage.WorldOwnership;
import dev.nodera.storage.WorldRegistry;
import dev.nodera.storage.WorldRevival;
import dev.nodera.storage.WorldTombstone;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A world's registry entry over its whole life: created, hosted, deleted, revived.
 *
 * <p>Six sibling classes over one subject. Deletion and revival are the same owner-signed record
 * read in two directions, and the registry store is what both of them write to — a peer that got
 * one of the three right and another wrong is exactly how a world became unshareable forever, which
 * is the failure {@code WorldRevivalServiceTest} exists for.
 */
final class WorldLifecycleTest {

    /**
     * The peer's durable answer to "what am I keeping on the network".
     *
     * <p>Every test here is written against a <b>second store reading the same file</b> rather than
     * against the first store's memory, because the only interesting property is the one the worker
     * needed and did not have: that the answer survives the process.
     */
    @Nested
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

    /**
     * The one-shot repair for a registry that already holds duplicate rows for one save (W-DUP-4).
     *
     * <p>Each test builds a registry directly in the canonical wire form (so exact-duplicate ids can be
     * written, which the live {@link WorldRegistryStore} deduplicates on load) and asserts the merge
     * leaves one row per save, the survivor chosen by the persisted {@code nodera-world.dat} pin.
     */
    @Nested
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

    /**
     * What a peer does when somebody tells it to destroy a world.
     *
     * <p>The interesting cases are the refusals. A deletion is the only instruction on this network that
     * a receiver cannot undo, so "did the owner really ask for this" has to be answered from the request
     * itself, by a node that may never have heard of the world and has nobody to ask. These tests drive
     * a real {@link WorldHostingService} and a real registry file so a refusal is checked by looking at
     * what the node still serves, not at a return value.
     */
    @Nested
    final class WorldDeletionServiceTest {

        @TempDir
        Path dir;

        /** Captures relays instead of sending them; a deletion's fan-out is part of its behaviour. */
        private static final class CapturingTransport implements PeerTransport {
            final List<PeerAddress> sentTo = new ArrayList<>();

            @Override
            public void send(PeerAddress to, byte[] frame) {
                sentTo.add(to);
            }

            @Override
            public void sendStream(PeerAddress to, long streamId, byte[] payload) {
                sentTo.add(to);
            }

            @Override
            public void setHandler(dev.nodera.transport.MessageHandler handler) {
            }

            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }
        }

        private final CapturingTransport transport = new CapturingTransport();
        private final NodeIdentity self = NodeIdentity.generate();

        private WorldRegistryStore registry;
        private WorldHostingService hosting;

        private WorldHostingService hosting() {
            if (hosting == null) {
                registry = new WorldRegistryStore(dir.resolve("worlds.dat"));
                hosting = new WorldHostingService(self, NodeCapabilities.initial(),
                        () -> "127.0.0.1:25620",
                        new dev.nodera.peer.discovery.TrackerClient(List.of(), self),
                        List.of(), worldId -> List.of(), registry);
            }
            return hosting;
        }

        private WorldDeletionService service(List<PeerEntry> members) {
            WorldDeletionService lane = new WorldDeletionService(self.nodeId(), transport,
                    () -> members, hosting(), registry, null, null, null);
            lane.attachStore(new WorldTombstoneStore(dir.resolve("deleted")));
            hosting().refuseDeletedWorlds(lane::isDeleted);
            return lane;
        }

        /** An owner, their world's key, and the claim binding the two. */
        private record World(NodeIdentity owner, PersistedWorldKey key, WorldOwnership ownership) {
            static World create(String seed) {
                NodeIdentity owner = NodeIdentity.generate();
                PersistedWorldKey key = PersistedWorldKey.generate(
                        new dev.nodera.core.crypto.HashService().sha256(seed.getBytes()));
                return new World(owner, key, WorldOwnership.create(owner, key, 1_000L));
            }

            String idHex() {
                return ownership.worldId().toHex();
            }

            /** Issued now, because the retention window is measured against the wall clock. */
            WorldTombstone delete() {
                return WorldTombstone.create(owner, key, ownership, "", System.currentTimeMillis());
            }
        }

        private static WorldDeletionGossip gossip(WorldTombstone tombstone) {
            return gossip(tombstone, tombstone.worldId());
        }

        private static WorldDeletionGossip gossip(WorldTombstone tombstone, Bytes envelopeWorldId) {
            CanonicalWriter w = new CanonicalWriter();
            tombstone.encode(w);
            return new WorldDeletionGossip(envelopeWorldId, w.toBytes());
        }

        private static PeerAddress peer(int n) {
            return PeerAddress.of(new NodeId(new java.util.UUID(0L, n)), "127.0.0.1:2560" + n);
        }

        private static PeerEntry member(NodeId id, String route) {
            return new PeerEntry(id, route, NodeCapabilities.initial(), false);
        }

        @Test
        @DisplayName("the owner's deletion stops this node serving the world")
        void aVerifiedDeletionIsApplied() {
            World world = World.create("doomed");
            assertThat(hosting().seed(world.idHex(), "Doomed")).isNull();
            WorldDeletionService lane = service(List.of());

            lane.onMessage(peer(1), gossip(world.delete()));

            assertThat(hosting().hostedWorlds()).isEmpty();
            assertThat(registry.find(world.idHex())).isEmpty();
            assertThat(lane.isDeleted(world.idHex())).isTrue();
        }

        @Test
        @DisplayName("a forged deletion changes nothing and is not passed on")
        void aForgedDeletionIsRefusedAndNotRelayed() {
            World world = World.create("mine");
            NodeIdentity attacker = NodeIdentity.generate();
            WorldTombstone genuine = world.delete();
            WorldTombstone forged = new WorldTombstone(genuine.worldId(), genuine.ownershipRecord(),
                    genuine.issuedAtEpoch(), "", attacker.sign(genuine.signedPortion()),
                    attacker.sign(genuine.signedPortion()));
            assertThat(hosting().seed(world.idHex(), "Mine")).isNull();
            WorldDeletionService lane = service(List.of(
                    member(new NodeId(new java.util.UUID(0L, 9)), "127.0.0.1:25609")));

            lane.onMessage(peer(1), gossip(forged));

            assertThat(hosting().hostedWorlds()).hasSize(1);
            assertThat(lane.isDeleted(world.idHex())).isFalse();
            // Not relaying is the half that is easy to forget: a peer that forwarded what it could not
            // verify would make itself an amplifier for whatever an attacker injected anywhere.
            assertThat(transport.sentTo).isEmpty();
        }

        @Test
        @DisplayName("a real deletion pointed at a different world is refused")
        void theEnvelopeCannotRedirectAValidProof() {
            World mine = World.create("mine");
            World theirs = World.create("theirs");
            assertThat(hosting().seed(theirs.idHex(), "Theirs")).isNull();
            WorldDeletionService lane = service(List.of());

            lane.onMessage(peer(1), gossip(mine.delete(), theirs.ownership().worldId()));

            assertThat(hosting().hostedWorlds()).hasSize(1);
            assertThat(lane.isDeleted(theirs.idHex())).isFalse();
        }

        @Test
        @DisplayName("a verified deletion is relayed once, and never back to the sender")
        void aVerifiedDeletionIsFloodedButNotEchoed() {
            World world = World.create("doomed");
            PeerEntry sender = member(peer(1).nodeId(), "127.0.0.1:25601");
            PeerEntry other = member(peer(2).nodeId(), "127.0.0.1:25602");
            WorldDeletionService lane = service(List.of(sender, other,
                    member(self.nodeId(), "127.0.0.1:25620")));
            WorldDeletionGossip request = gossip(world.delete());

            lane.onMessage(peer(1), request);
            lane.onMessage(peer(1), request); // a duplicate must terminate, or the flood never stops

            assertThat(transport.sentTo).extracting(PeerAddress::nodeId)
                    .containsExactly(other.nodeId());
        }

        @Test
        @DisplayName("this node cannot originate a deletion for somebody else's world")
        void publishingRequiresBeingTheOwner() {
            World world = World.create("not mine");
            WorldDeletionService lane = service(List.of());

            WorldDeletionService.Outcome outcome = lane.publish(world.delete());

            assertThat(outcome.error()).contains("not the owner");
            assertThat(lane.isDeleted(world.idHex())).isFalse();
        }

        @Test
        @DisplayName("the owner's own deletion applies here and reports its fan-out")
        void publishingAppliesLocallyAndCountsPeers() {
            PersistedWorldKey key = PersistedWorldKey.generate(
                    new dev.nodera.core.crypto.HashService().sha256("ours".getBytes()));
            WorldOwnership ownership = WorldOwnership.create(self, key, 1_000L);
            String idHex = ownership.worldId().toHex();
            assertThat(hosting().host(idHex, "Ours", "{}")).isNull();
            WorldDeletionService lane = service(List.of(
                    member(peer(1).nodeId(), "127.0.0.1:25601"),
                    member(peer(2).nodeId(), "127.0.0.1:25602")));

            WorldDeletionService.Outcome outcome =
                    lane.publish(WorldTombstone.create(self, key, ownership, "done with it", 2_000L));

            assertThat(outcome.error()).isNull();
            assertThat(outcome.peersNotified()).isEqualTo(2);
            assertThat(hosting().hostedWorlds()).isEmpty();
        }

        @Test
        @DisplayName("a deleted world cannot be put back by hosting or seeding it again")
        void aDeletedWorldIsRefusedReadmission() {
            World world = World.create("doomed");
            WorldDeletionService lane = service(List.of());
            lane.onMessage(peer(1), gossip(world.delete()));

            // Both ways back in: the control verbs a game drives, and the replication lane adopting a
            // world it was placed for. An announce from a peer that has not heard yet is ordinary, so
            // this is the common case, not the adversarial one.
            assertThat(hosting().host(world.idHex(), "Doomed", "{}"))
                    .isEqualTo("this world was deleted by its owner");
            assertThat(hosting().seed(world.idHex(), "Doomed"))
                    .isEqualTo("this world was deleted by its owner");
            assertThat(hosting().hostedWorlds()).isEmpty();
        }

        @Test
        @DisplayName("a restart does not undo a deletion")
        void deletionsSurviveTheWorker() {
            World world = World.create("doomed");
            WorldDeletionService lane = service(List.of());
            lane.onMessage(peer(1), gossip(world.delete()));

            // What a restart actually is: a second service over the same directory.
            WorldDeletionService restarted = new WorldDeletionService(self.nodeId(), transport,
                    List::of, hosting(), registry, null, null, null);
            restarted.attachStore(new WorldTombstoneStore(dir.resolve("deleted")));

            assertThat(restarted.isDeleted(world.idHex())).isTrue();
            assertThat(restarted.tombstone(world.idHex()))
                    .hasValueSatisfying(t -> assertThat(t.verify()).isTrue());
        }

        @Test
        @DisplayName("a stored deletion that has been edited is not honoured")
        void aTamperedRecordOnDiskIsNotTrusted() throws Exception {
            World world = World.create("doomed");
            WorldTombstoneStore store = new WorldTombstoneStore(dir.resolve("deleted"));
            store.save(world.delete());
            Path file = dir.resolve("deleted").resolve(world.idHex() + ".tombstone");
            byte[] bytes = java.nio.file.Files.readAllBytes(file);
            bytes[bytes.length - 1] ^= 0x01;
            java.nio.file.Files.write(file, bytes);

            // Our own disk gets no more trust than the wire: the file is ignored rather than obeyed.
            assertThat(store.load(System.currentTimeMillis())).isEmpty();
        }

        @Test
        @DisplayName("deletions older than the retention window are dropped")
        void oldDeletionsAreForgotten() {
            World world = World.create("ancient");
            WorldTombstoneStore store = new WorldTombstoneStore(dir.resolve("deleted"));
            store.save(WorldTombstone.create(world.owner(), world.key(), world.ownership(), "",
                    1_000_000L));

            long wellPastTheWindow = 1_000_000L + WorldTombstoneStore.RETENTION.toMillis() + 1;

            assertThat(store.load(1_000_000L)).hasSize(1);
            assertThat(store.load(wellPastTheWindow)).isEmpty();
        }

        @Test
        @DisplayName("a world id that is not hex never becomes a path")
        void theStoreRefusesUnsafeWorldIds() {
            WorldTombstoneStore store = new WorldTombstoneStore(dir.resolve("deleted"));
            World world = World.create("mine");
            WorldTombstone genuine = world.delete();
            // Not reachable through a verifying path — worldId is bytes on the wire — but the store's
            // own guard is what makes that true rather than incidental.
            WorldTombstone escaping = new WorldTombstone(Bytes.unsafeWrap("../../etc".getBytes()),
                    genuine.ownershipRecord(), genuine.issuedAtEpoch(), "", genuine.worldSignature(),
                    genuine.ownerSignature());

            assertThat(escaping.verify()).isFalse();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.save(escaping))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * The cross-language golden fixture for a world deletion.
     *
     * <p>A deletion is verified independently by two implementations — the Java peer and the Rust
     * tracker — and both have to reach the same verdict from the same bytes, or a world is deleted on
     * half the network. Field-level agreement is not enough: both sides check Ed25519 signatures over
     * the record's <b>canonical prefix</b>, so a one-byte difference in how either side reconstructs
     * that prefix turns a genuine deletion into a forgery on one side of the network and nowhere else.
     *
     * <p>So this writes {@code fixtures/wire/world-deletion-gossip.bin} from the Java encoder, and the
     * Rust crate's {@code tests/fixtures.rs} decodes it, <b>verifies both signatures</b>, and re-encodes
     * it byte-for-byte.
     *
     * <p>Everything here is fixed — the key material, the world id, the timestamps — because Ed25519 is
     * deterministic, so identical inputs must produce an identical file on every machine. The keys are
     * throwaway pairs generated once for this test and are not used anywhere else.
     */
    @Nested
    final class WorldDeletionFixtureTest {

        private static final String OWNER_PKCS8 = "302e020100300506032b657004220420"
                + "b16484188764759e48e79463c88efd28761bad427820ccd99eef4a64d443d775";
        private static final String OWNER_X509 = "302a300506032b6570032100"
                + "7fe09a3d05d0bb8c9cdba05cf8fd5914f6d9156b023cf8c099eed2e27af4b47c";
        private static final String WORLD_PKCS8 = "302e020100300506032b657004220420"
                + "19a70dcf74fa99e4f26fca2a9f68170e4696139bed852d97760f911635b6eb92";
        private static final String WORLD_X509 = "302a300506032b6570032100"
                + "7da266bacefcf11981233665087fe39c5b1c61034ae1f4791792ee9465bc026e";

        /** A fixed 32-byte world id; the value is arbitrary, its stability is not. */
        private static final Bytes WORLD_ID = Bytes.fromHex(
                "5c9f1e2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6");

        private static final long CREATED_AT = 1_700_000_000_000L;
        private static final long ISSUED_AT = 1_700_000_600_000L;

        private static NodeIdentity fixedOwner() throws Exception {
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            return NodeIdentity.fromKeys(
                    new NodeId(new UUID(0x1122334455667788L, 0x99aabbccddeeff00L)),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(Bytes.fromHex(OWNER_PKCS8).toArray())),
                    factory.generatePublic(new X509EncodedKeySpec(Bytes.fromHex(OWNER_X509).toArray())));
        }

        private static byte[] goldenFrame() throws Exception {
            NodeIdentity owner = fixedOwner();
            PersistedWorldKey worldKey = new PersistedWorldKey(WORLD_ID,
                    Bytes.fromHex(WORLD_PKCS8), Bytes.fromHex(WORLD_X509));
            WorldOwnership ownership = WorldOwnership.create(owner, worldKey, CREATED_AT);
            WorldTombstone tombstone = WorldTombstone.create(owner, worldKey, ownership,
                    "the owner asked the network to forget this world", ISSUED_AT);
            CanonicalWriter w = new CanonicalWriter();
            tombstone.encode(w);
            // A consensus kind: the tolerant plane routes it, and its strict canonical bytes cross
            // inside one opaque field, untouched. What the Rust side verifies is that payload.
            return WireCodec.encode(new WorldDeletionGossip(WORLD_ID, w.toBytes()));
        }

        private static Path fixture() {
            // Same repo-root walk the transport fixture test uses: <root>/java/worker → <root>.
            Path root = Paths.get("").toAbsolutePath();
            while (root != null && !Files.isDirectory(root.resolve("fixtures").resolve("wire"))) {
                root = root.getParent();
            }
            assertThat(root).as("repo root containing fixtures/wire").isNotNull();
            return root.resolve("fixtures").resolve("wire").resolve("world-deletion-gossip.bin");
        }

        @Test
        @DisplayName("the deletion fixture is deterministic and still verifies")
        void theGoldenFrameIsStableAndValid() throws Exception {
            byte[] frame = goldenFrame();

            // Deterministic: two independent builds of the same inputs are the same bytes. Without this
            // the fixture would churn on every run and stop being evidence of anything.
            assertThat(goldenFrame()).isEqualTo(frame);
            WorldDeletionGossip decoded = (WorldDeletionGossip) WireCodec.decode(frame);
            assertThat(WorldTombstone.decode(
                    new dev.nodera.core.crypto.CanonicalReader(decoded.encodedTombstone())).verify())
                    .isTrue();
        }

        @Test
        @DisplayName("the committed fixture matches what this build emits")
        void theCommittedFixtureIsUpToDate() throws Exception {
            Path file = fixture();
            byte[] frame = goldenFrame();
            if (!Files.exists(file)) {
                Files.write(file, frame);
            }

            // A byte difference is a wire-contract change, not a stale file: it fails here so it is
            // reviewed, exactly as the transport fixtures do.
            assertThat(Files.readAllBytes(file))
                    .as("delete fixtures/wire/world-deletion-gossip.bin to regenerate deliberately")
                    .isEqualTo(frame);
        }
    }

    /**
     * Putting back a world you deleted.
     *
     * <p>A tombstone is remembered on purpose, so that a peer which was offline during the deletion
     * cannot resurrect the world from a stale announce. The same memory answered the <b>owner's</b>
     * re-share with a refusal, and because a world id is derived from the save, re-sharing produced the
     * same id every time: deleting a world made that save permanently unshareable, on the owner's own
     * worker, reported nowhere but a log line.
     *
     * <p>These tests drive the real hosting service, so "restored" means the node will serve the world
     * again — not that a flag flipped.
     */
    @Nested
    final class WorldRevivalServiceTest {

        @TempDir
        Path dir;

        /** Captures relays instead of sending them; the fan-out is part of the behaviour. */
        private static final class CapturingTransport implements PeerTransport {
            final List<PeerAddress> sentTo = new ArrayList<>();

            @Override
            public void send(PeerAddress to, byte[] frame) {
                sentTo.add(to);
            }

            @Override
            public void sendStream(PeerAddress to, long streamId, byte[] payload) {
                sentTo.add(to);
            }

            @Override
            public void setHandler(dev.nodera.transport.MessageHandler handler) {
            }

            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }
        }

        private final CapturingTransport transport = new CapturingTransport();
        private final NodeIdentity self = NodeIdentity.generate();

        private WorldRegistryStore registry;
        private WorldHostingService hosting;
        private WorldTombstoneStore store;

        private WorldHostingService hosting() {
            if (hosting == null) {
                registry = new WorldRegistryStore(dir.resolve("worlds.dat"));
                hosting = new WorldHostingService(self, NodeCapabilities.initial(),
                        () -> "127.0.0.1:25620",
                        new dev.nodera.peer.discovery.TrackerClient(List.of(), self),
                        List.of(), worldId -> List.of(), registry);
            }
            return hosting;
        }

        private WorldDeletionService service(List<PeerEntry> members) {
            WorldDeletionService lane = new WorldDeletionService(self.nodeId(), transport,
                    () -> members, hosting(), registry, null, null, null);
            store = new WorldTombstoneStore(dir.resolve("deleted"));
            lane.attachStore(store);
            hosting().refuseDeletedWorlds(lane::isDeleted);
            return lane;
        }

        /** A world this node owns: identity, key, and the claim binding the two. */
        private final class OwnWorld {
            final PersistedWorldKey key = PersistedWorldKey.generate(
                    new dev.nodera.core.crypto.HashService().sha256("re-shared".getBytes()));
            final WorldOwnership ownership = WorldOwnership.create(self, key, 1_000L);

            String idHex() {
                return ownership.worldId().toHex();
            }

            WorldTombstone deleteAt(long millis) {
                return WorldTombstone.create(self, key, ownership, "cleaning up", millis);
            }

            WorldRevival restoreAt(long millis) {
                return WorldRevival.create(self, key, ownership, "shared again", millis);
            }
        }

        private static WorldRevivalGossip gossip(WorldRevival revival) {
            CanonicalWriter w = new CanonicalWriter();
            revival.encode(w);
            return new WorldRevivalGossip(revival.worldId(), w.toBytes());
        }

        private static WorldDeletionGossip gossip(WorldTombstone tombstone) {
            CanonicalWriter w = new CanonicalWriter();
            tombstone.encode(w);
            return new WorldDeletionGossip(tombstone.worldId(), w.toBytes());
        }

        private static PeerAddress peer(int n) {
            return PeerAddress.of(new NodeId(new java.util.UUID(0L, n)), "127.0.0.1:2560" + n);
        }

        @Test
        @DisplayName("the owner's restore makes a deleted world hostable again")
        void aVerifiedRestoreUndoesTheDeletion() {
            OwnWorld world = new OwnWorld();
            WorldDeletionService lane = service(List.of());
            long now = System.currentTimeMillis();
            lane.publish(world.deleteAt(now));
            assertThat(hosting().host(world.idHex(), "Open", "{}"))
                    .as("the deletion stands until its owner says otherwise")
                    .isEqualTo("this world was deleted by its owner");

            WorldDeletionService.Outcome outcome = lane.publish(world.restoreAt(now + 1));

            assertThat(outcome.error()).isNull();
            assertThat(lane.isDeleted(world.idHex())).isFalse();
            assertThat(hosting().host(world.idHex(), "Open", "{}"))
                    .as("the world can be shared again")
                    .isNull();
            assertThat(hosting().hostedWorlds()).hasSize(1);
        }

        @Test
        @DisplayName("sharing a deleted world asks the revive hook; adopting content never does")
        void onlyTheShareVerbCanTriggerARestore() {
            OwnWorld world = new OwnWorld();
            WorldDeletionService lane = service(List.of());
            lane.publish(world.deleteAt(System.currentTimeMillis()));
            List<String> asked = new ArrayList<>();

            // The hook that declines: the world stays deleted and the refusal is unchanged.
            hosting().reviveOnOwnerReshare(id -> {
                asked.add(id);
                return false;
            });
            assertThat(hosting().host(world.idHex(), "Open", "{}"))
                    .isEqualTo("this world was deleted by its owner");
            assertThat(asked).containsExactly(world.idHex());

            // Replication adopting content is nobody's instruction, so it must not be able to
            // resurrect a world even on a node whose hook would say yes.
            asked.clear();
            hosting().reviveOnOwnerReshare(id -> {
                asked.add(id);
                return true;
            });
            assertThat(hosting().seed(world.idHex(), "Open"))
                    .isEqualTo("this world was deleted by its owner");
            assertThat(asked).as("seed() never asks").isEmpty();

            // And the share verb, on a node that can sign the restore, goes through.
            assertThat(hosting().host(world.idHex(), "Open", "{}")).isNull();
        }

        @Test
        @DisplayName("a deletion still circulating cannot undo a later restore")
        void aStaleDeletionDoesNotWinAgainstTheRestore() {
            OwnWorld world = new OwnWorld();
            WorldDeletionService lane = service(List.of());
            long now = System.currentTimeMillis();
            WorldTombstone deletion = world.deleteAt(now);
            lane.publish(deletion);
            lane.publish(world.restoreAt(now + 1));

            // Exactly what a peer that was offline during the restore will send, forever, whenever it
            // hears the world announced again.
            lane.onMessage(peer(1), gossip(deletion));

            assertThat(lane.isDeleted(world.idHex()))
                    .as("the owner's latest word is what this node holds")
                    .isFalse();
        }

        @Test
        @DisplayName("a restore that predates its deletion is refused")
        void aReplayedRestoreIsRefused() {
            OwnWorld world = new OwnWorld();
            WorldDeletionService lane = service(List.of());
            long now = System.currentTimeMillis();
            WorldRevival captured = world.restoreAt(now - 1);
            lane.publish(world.deleteAt(now));

            lane.onMessage(peer(1), gossip(captured));

            assertThat(lane.isDeleted(world.idHex()))
                    .as("a record captured before the deletion cannot reverse it")
                    .isTrue();
        }

        @Test
        @DisplayName("a restore for somebody else's world is refused")
        void onlyTheOwnerMayRestore() {
            OwnWorld world = new OwnWorld();
            WorldDeletionService lane = service(List.of());
            long now = System.currentTimeMillis();
            lane.publish(world.deleteAt(now));

            NodeIdentity attacker = NodeIdentity.generate();
            WorldRevival genuine = world.restoreAt(now + 1);
            WorldRevival forged = new WorldRevival(genuine.worldId(), genuine.ownershipRecord(),
                    genuine.issuedAtEpoch(), "", attacker.sign(genuine.signedPortion()),
                    attacker.sign(genuine.signedPortion()));

            lane.onMessage(peer(1), gossip(forged));

            assertThat(lane.isDeleted(world.idHex())).isTrue();
            assertThat(transport.sentTo).as("a forgery is never relayed").isEmpty();
        }

        @Test
        @DisplayName("a restart remembers the restore, not the deletion it replaced")
        void theRestoreSurvivesARestart() {
            OwnWorld world = new OwnWorld();
            WorldDeletionService lane = service(List.of());
            long now = System.currentTimeMillis();
            WorldTombstone deletion = world.deleteAt(now);
            lane.publish(deletion);
            lane.publish(world.restoreAt(now + 1));

            // A restart is a second service over the same directory.
            WorldDeletionService restarted = new WorldDeletionService(self.nodeId(), transport,
                    List::of, hosting(), registry, null, null, null);
            restarted.attachStore(new WorldTombstoneStore(dir.resolve("deleted")));

            assertThat(restarted.isDeleted(world.idHex()))
                    .as("the deletion file is gone and the restore is on disk in its place")
                    .isFalse();
            restarted.onMessage(peer(1), gossip(deletion));
            assertThat(restarted.isDeleted(world.idHex()))
                    .as("and the old deletion still cannot come back after the restart")
                    .isFalse();
        }
    }

    /**
     * The cross-language golden fixture for a world <b>restore</b> — the deletion fixture's mirror.
     *
     * <p>The same argument applies with the same force: a restore is verified independently by the Java
     * peer and by the Rust tracker, both check Ed25519 signatures over the record's canonical prefix,
     * and a one-byte disagreement about that prefix means a world comes back on half the network. The
     * asymmetry would be worse than for a deletion, because the tracker is the side that remembers the
     * deletion for 120 days: a restore the tracker refuses is a world its owner cannot re-list at all.
     *
     * <p>Same fixed key material and world id as {@code WorldDeletionFixtureTest}, so the pair of
     * fixtures describes one world's whole lifecycle — created, deleted, and put back — and the restore
     * is issued <b>after</b> the deletion, which is what makes it supersede it.
     */
    @Nested
    final class WorldRevivalFixtureTest {

        private static final String OWNER_PKCS8 = "302e020100300506032b657004220420"
                + "b16484188764759e48e79463c88efd28761bad427820ccd99eef4a64d443d775";
        private static final String OWNER_X509 = "302a300506032b6570032100"
                + "7fe09a3d05d0bb8c9cdba05cf8fd5914f6d9156b023cf8c099eed2e27af4b47c";
        private static final String WORLD_PKCS8 = "302e020100300506032b657004220420"
                + "19a70dcf74fa99e4f26fca2a9f68170e4696139bed852d97760f911635b6eb92";
        private static final String WORLD_X509 = "302a300506032b6570032100"
                + "7da266bacefcf11981233665087fe39c5b1c61034ae1f4791792ee9465bc026e";

        private static final Bytes WORLD_ID = Bytes.fromHex(
                "5c9f1e2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6");

        private static final long CREATED_AT = 1_700_000_000_000L;
        /** The deletion this restore undoes — the timestamp the fixture deletion carries. */
        private static final long DELETED_AT = 1_700_000_600_000L;
        private static final long RESTORED_AT = 1_700_003_600_000L;

        private static NodeIdentity fixedOwner() throws Exception {
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            return NodeIdentity.fromKeys(
                    new NodeId(new UUID(0x1122334455667788L, 0x99aabbccddeeff00L)),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(Bytes.fromHex(OWNER_PKCS8).toArray())),
                    factory.generatePublic(new X509EncodedKeySpec(Bytes.fromHex(OWNER_X509).toArray())));
        }

        private static PersistedWorldKey worldKey() {
            return new PersistedWorldKey(WORLD_ID,
                    Bytes.fromHex(WORLD_PKCS8), Bytes.fromHex(WORLD_X509));
        }

        private static byte[] goldenFrame() throws Exception {
            NodeIdentity owner = fixedOwner();
            WorldOwnership ownership = WorldOwnership.create(owner, worldKey(), CREATED_AT);
            WorldRevival revival = WorldRevival.create(owner, worldKey(), ownership,
                    "the owner shared this world again", RESTORED_AT);
            CanonicalWriter w = new CanonicalWriter();
            revival.encode(w);
            return WireCodec.encode(new WorldRevivalGossip(WORLD_ID, w.toBytes()));
        }

        private static Path fixture() {
            Path root = Paths.get("").toAbsolutePath();
            while (root != null && !Files.isDirectory(root.resolve("fixtures").resolve("wire"))) {
                root = root.getParent();
            }
            assertThat(root).as("repo root containing fixtures/wire").isNotNull();
            return root.resolve("fixtures").resolve("wire").resolve("world-revival-gossip.bin");
        }

        @Test
        @DisplayName("the restore fixture is deterministic and still verifies")
        void theGoldenFrameIsStableAndValid() throws Exception {
            byte[] frame = goldenFrame();

            assertThat(goldenFrame()).isEqualTo(frame);
            WorldRevivalGossip decoded = (WorldRevivalGossip) WireCodec.decode(frame);
            WorldRevival revival = WorldRevival.decode(
                    new dev.nodera.core.crypto.CanonicalReader(decoded.encodedRevival()));
            assertThat(revival.verify()).isTrue();
            assertThat(revival.issuedBy(fixedOwner().nodeId())).isTrue();
        }

        @Test
        @DisplayName("the restore supersedes the deletion it undoes, and no earlier one does")
        void theRestoreOutranksTheDeletion() throws Exception {
            NodeIdentity owner = fixedOwner();
            WorldOwnership ownership = WorldOwnership.create(owner, worldKey(), CREATED_AT);
            WorldTombstone deletion = WorldTombstone.create(owner, worldKey(), ownership,
                    "deleted", DELETED_AT);

            assertThat(WorldRevival.create(owner, worldKey(), ownership, "back", RESTORED_AT)
                    .supersedes(deletion))
                    .as("the owner's later word wins")
                    .isTrue();
            assertThat(WorldRevival.create(owner, worldKey(), ownership, "replayed", DELETED_AT - 1)
                    .supersedes(deletion))
                    .as("a restore captured before the deletion cannot be replayed to undo it")
                    .isFalse();
            assertThat(WorldRevival.create(owner, worldKey(), ownership, "tie", DELETED_AT)
                    .supersedes(deletion))
                    .as("a tie goes to the deletion — the answer that cannot lose somebody's world")
                    .isFalse();
        }

        @Test
        @DisplayName("the committed fixture matches what this build emits")
        void theCommittedFixtureIsUpToDate() throws Exception {
            Path file = fixture();
            byte[] frame = goldenFrame();
            if (!Files.exists(file)) {
                Files.write(file, frame);
            }

            assertThat(Files.readAllBytes(file))
                    .as("delete fixtures/wire/world-revival-gossip.bin to regenerate deliberately")
                    .isEqualTo(frame);
        }
    }
}
