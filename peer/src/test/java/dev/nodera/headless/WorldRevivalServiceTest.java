package dev.nodera.headless;

import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.membership.WorldDeletionGossip;
import dev.nodera.protocol.membership.WorldRevivalGossip;
import dev.nodera.storage.PersistedWorldKey;
import dev.nodera.storage.WorldOwnership;
import dev.nodera.storage.WorldRevival;
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
