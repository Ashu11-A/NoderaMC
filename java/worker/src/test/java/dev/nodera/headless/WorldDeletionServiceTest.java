package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.membership.WorldDeletionGossip;
import dev.nodera.storage.PersistedWorldKey;
import dev.nodera.storage.WorldOwnership;
import dev.nodera.storage.WorldTombstone;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a peer does when somebody tells it to destroy a world.
 *
 * <p>The interesting cases are the refusals. A deletion is the only instruction on this network that
 * a receiver cannot undo, so "did the owner really ask for this" has to be answered from the request
 * itself, by a node that may never have heard of the world and has nobody to ask. These tests drive
 * a real {@link WorldHostingService} and a real registry file so a refusal is checked by looking at
 * what the node still serves, not at a return value.
 */
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
