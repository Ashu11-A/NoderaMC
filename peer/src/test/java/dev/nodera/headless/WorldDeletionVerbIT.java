package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.peer.control.ControlProtocol;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.storage.WorldIdentity;
import dev.nodera.testkit.peer.Await;
import dev.nodera.testkit.peer.MeshNode;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.testkit.peer.WorkerNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting a world across two real peers, over the control endpoint the app and the mod use.
 *
 * <p>The claim under test is the whole feature in one sentence: <b>the peer that created a world
 * can make the network forget it, and nobody else can</b>. So the owner deletes through
 * {@code NODERA-DELETE}, a second peer that is merely supporting the world applies the deletion
 * because the record verifies — not because of who sent it — and a third scenario shows the
 * supporter cannot originate one.
 *
 * <p>Both peers run over a shared loopback transport, so the deletion travels as encoded frames
 * exactly as it would on a socket. Nothing here reaches the network.
 */
final class WorldDeletionVerbIT {

    @TempDir
    Path dir;

    private final PeerTestHarness harness = PeerTestHarness.create();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    /**
     * Boot one worker with its own state directory.
     *
     * @param name    the directory to keep this worker's state in.
     * @param members the peers its deletion lane relays to.
     */
    private WorkerNode boot(String name, List<PeerEntry> members) throws Exception {
        return harness.workerNode("deletion-test")
                .stateDir(dir.resolve(name))
                .withDeletion(() -> members)
                .build();
    }

    /** Create a world on a worker through the same verb the mod uses, and return its id. */
    private String createWorld(WorkerNode worker, String genesisSeed) {
        String payload = WorkerNode.okPayload(worker.request(ControlProtocol.WORLDID + " 2 "
                + WorkerNode.b64(genesisSeed) + " 1000 1 1 0 "));
        WorldIdentity world = WorldIdentity.decode(new CanonicalReader(
                Bytes.unsafeWrap(Base64.getDecoder().decode(payload))));
        return world.worldId().toHex();
    }

    @Test
    @DisplayName("the owner deletes a world and the peer supporting it forgets too")
    void aDeletionReachesTheSupportingPeer() throws Exception {
        WorkerNode supporter = boot("supporter", List.of());
        WorkerNode owner = boot("owner", List.of(member(supporter)));
        String worldId = createWorld(owner, "a world worth deleting");
        // The supporter keeps the world alive for the owner: it holds the bytes and none of the
        // authority, which is exactly the peer a deletion has to convince.
        assertThat(supporter.hosting().seed(worldId, "Their World")).isNull();

        String reply = owner.request(ControlProtocol.DELETE + " 2 " + worldId + " "
                + WorkerNode.b64("finished with it"));

        assertThat(reply).startsWith(ControlProtocol.OK);
        assertThat(owner.hosting().hostedWorlds()).isEmpty();
        // Relay is a send; application happens on the receiver's own state thread. Asserting
        // immediately would be testing the scheduler, and would pass or fail depending on the machine.
        Await.quietly(5_000, () -> supporter.deletions().isDeleted(worldId));
        assertThat(supporter.deletions().isDeleted(worldId))
                .as("the supporter verified the record itself and acted on it")
                .isTrue();
        assertThat(supporter.hosting().hostedWorlds()).isEmpty();
    }

    @Test
    @DisplayName("a peer cannot delete a world it merely supports")
    void aSupporterCannotDeleteSomebodyElsesWorld() throws Exception {
        WorkerNode owner = boot("owner", List.of());
        WorkerNode supporter = boot("supporter", List.of());
        String worldId = createWorld(owner, "not the supporter's to delete");
        assertThat(supporter.hosting().seed(worldId, "Their World")).isNull();

        String reply = supporter.request(ControlProtocol.DELETE + " 2 " + worldId);

        // No key, no deletion. The refusal is the security property, not a UI nicety: without it,
        // hosting somebody's world would be the power to destroy it.
        assertThat(reply).startsWith(ControlProtocol.ERR);
        assertThat(reply).contains("does not administer");
        assertThat(supporter.hosting().hostedWorlds()).hasSize(1);
        assertThat(supporter.deletions().isDeleted(worldId)).isFalse();
    }

    @Test
    @DisplayName("a deleted world cannot be re-hosted, before or after a restart")
    void aDeletedWorldStaysDeleted() throws Exception {
        WorkerNode owner = boot("owner", List.of());
        String worldId = createWorld(owner, "gone for good");
        assertThat(owner.request(ControlProtocol.DELETE + " 2 " + worldId))
                .startsWith(ControlProtocol.OK);

        assertThat(owner.hosting().host(worldId, "Back Again", "{}"))
                .isEqualTo("this world was deleted by its owner");

        // A restart is a new service over the same directory; the deletion is on disk, so the
        // world does not come back with it.
        WorldDeletionService restarted = new WorldDeletionService(owner.nodeId(),
                harness.transport(NodeIdentity.generate()), List::of, owner.hosting(),
                null, null, null, null);
        restarted.attachStore(new WorldTombstoneStore(dir.resolve("owner").resolve("deleted")));
        assertThat(restarted.isDeleted(worldId)).isTrue();
        assertThat(restarted.tombstone(worldId))
                .hasValueSatisfying(t -> assertThat(t.verify()).isTrue());
    }

    @Test
    @DisplayName("deleting an unknown world is refused rather than half-done")
    void anUnknownWorldIsRefused() throws Exception {
        WorkerNode owner = boot("owner", List.of());
        String strangersWorld = harness.hashes()
                .sha256("never seen".getBytes(StandardCharsets.UTF_8)).toHex();

        String reply = owner.request(ControlProtocol.DELETE + " 2 " + strangersWorld);

        assertThat(reply).startsWith(ControlProtocol.ERR);
        assertThat(owner.deletions().isDeleted(strangersWorld))
                .as("a refused deletion must not leave a tombstone behind")
                .isFalse();
    }

    private static PeerEntry member(WorkerNode worker) {
        return new PeerEntry(worker.nodeId(), MeshNode.ROUTE, NodeCapabilities.initial(), false);
    }
}
