package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.PeerRuntimeConfig;
import dev.nodera.peer.control.ControlProtocol;
import dev.nodera.peer.control.ControlServer;
import dev.nodera.peer.discovery.TrackerClient;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.storage.WorldIdentity;
import dev.nodera.testkit.LoopbackTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
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

    private final HashService hashes = new HashService();

    @TempDir
    Path dir;

    private final List<Runnable> teardown = new ArrayList<>();
    private final LoopbackTransport.LoopbackNetwork network =
            LoopbackTransport.LoopbackNetwork.newNetwork();

    /** Everything one worker is made of, so a test can hold two of them. */
    private record Worker(NodeIdentity identity, WorldHostingService hosting, WorldKeyStore keys,
                          WorldDeletionService deletions, ControlServer control) {

        String request(String line) throws Exception {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", control.boundPort()), 2000);
                s.setSoTimeout(15_000);
                OutputStream out = s.getOutputStream();
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                return new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                        .readLine();
            }
        }
    }

    @AfterEach
    void tearDown() {
        for (Runnable close : teardown) {
            try {
                close.run();
            } catch (RuntimeException ignored) {
                // best-effort teardown
            }
        }
    }

    /**
     * Boot one worker with its own state directory.
     *
     * @param name    the directory to keep this worker's state in.
     * @param members the peers its deletion lane relays to.
     */
    private Worker boot(String name, List<PeerEntry> members) throws Exception {
        Path home = dir.resolve(name);
        NodeIdentity identity = NodeIdentity.generate();
        NodeCapabilities caps = NodeCapabilities.initial()
                .withRoles(EnumSet.of(PeerRole.FULL_ARCHIVE));
        LoopbackTransport transport = network.register(identity.nodeId());
        transport.start();
        PeerRuntime runtime = PeerRuntime.bootstrap(identity, caps, transport, () -> "loopback",
                PeerRuntimeConfig.defaults(), null);
        TrackerClient tracker = new TrackerClient(List.of(), identity);
        WorldKeyStore keys = new WorldKeyStore(home.resolve("world-keys"));
        WorldRegistryStore registry = new WorldRegistryStore(home.resolve("worlds.dat"));
        WorldHostingService hosting = new WorldHostingService(identity, caps, runtime::selfRoute,
                tracker, List.of(), worldId -> List.of(), registry);
        WorldDeletionService deletions = new WorldDeletionService(identity.nodeId(), transport,
                () -> members, hosting, registry, null, null, null);
        deletions.attachStore(new WorldTombstoneStore(home.resolve("deleted")));
        hosting.refuseDeletedWorlds(deletions::isDeleted);
        runtime.onApplicationMessage(deletions::onMessage);
        WorkerControlHandler handler = new WorkerControlHandler("deletion-test", identity, caps,
                runtime, new dev.nodera.diagnostics.metric.TrafficMeter(), hosting, null, null,
                null, null, null, null, keys);
        handler.attachDeletion(deletions);
        ControlServer control = new ControlServer("127.0.0.1", 0, handler);
        control.start();

        teardown.add(control::close);
        teardown.add(hosting::close);
        teardown.add(tracker::close);
        teardown.add(runtime::stop);
        teardown.add(transport::stop);
        return new Worker(identity, hosting, keys, deletions, control);
    }

    /** Create a world on a worker through the same verb the mod uses, and return its id. */
    private String createWorld(Worker worker, String genesisSeed) throws Exception {
        String reply = worker.request(ControlProtocol.WORLDID + " 2 "
                + b64(genesisSeed) + " 1000 1 1 0 ");
        assertThat(reply).startsWith(ControlProtocol.OK + " ");
        WorldIdentity world = WorldIdentity.decode(new CanonicalReader(Bytes.unsafeWrap(
                Base64.getDecoder().decode(reply.substring(ControlProtocol.OK.length() + 1).trim()))));
        return world.worldId().toHex();
    }

    @Test
    @DisplayName("the owner deletes a world and the peer supporting it forgets too")
    void aDeletionReachesTheSupportingPeer() throws Exception {
        Worker supporter = boot("supporter", List.of());
        Worker owner = boot("owner", List.of(member(supporter)));
        String worldId = createWorld(owner, "a world worth deleting");
        // The supporter keeps the world alive for the owner: it holds the bytes and none of the
        // authority, which is exactly the peer a deletion has to convince.
        assertThat(supporter.hosting().seed(worldId, "Their World")).isNull();

        String reply = owner.request(ControlProtocol.DELETE + " 2 " + worldId + " "
                + b64("finished with it"));

        assertThat(reply).startsWith(ControlProtocol.OK);
        assertThat(owner.hosting().hostedWorlds()).isEmpty();
        awaitDeleted(supporter, worldId);
        assertThat(supporter.deletions().isDeleted(worldId))
                .as("the supporter verified the record itself and acted on it")
                .isTrue();
        assertThat(supporter.hosting().hostedWorlds()).isEmpty();
    }

    @Test
    @DisplayName("a peer cannot delete a world it merely supports")
    void aSupporterCannotDeleteSomebodyElsesWorld() throws Exception {
        Worker owner = boot("owner", List.of());
        Worker supporter = boot("supporter", List.of());
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
        Worker owner = boot("owner", List.of());
        String worldId = createWorld(owner, "gone for good");
        assertThat(owner.request(ControlProtocol.DELETE + " 2 " + worldId))
                .startsWith(ControlProtocol.OK);

        assertThat(owner.hosting().host(worldId, "Back Again", "{}"))
                .isEqualTo("this world was deleted by its owner");

        // A restart is a new service over the same directory; the deletion is on disk, so the
        // world does not come back with it.
        WorldDeletionService restarted = new WorldDeletionService(owner.identity().nodeId(),
                network.register(NodeIdentity.generate().nodeId()), List::of, owner.hosting(),
                null, null, null, null);
        restarted.attachStore(new WorldTombstoneStore(dir.resolve("owner").resolve("deleted")));
        assertThat(restarted.isDeleted(worldId)).isTrue();
        assertThat(restarted.tombstone(worldId))
                .hasValueSatisfying(t -> assertThat(t.verify()).isTrue());
    }

    @Test
    @DisplayName("deleting an unknown world is refused rather than half-done")
    void anUnknownWorldIsRefused() throws Exception {
        Worker owner = boot("owner", List.of());
        String strangersWorld = hashes.sha256("never seen".getBytes(StandardCharsets.UTF_8)).toHex();

        String reply = owner.request(ControlProtocol.DELETE + " 2 " + strangersWorld);

        assertThat(reply).startsWith(ControlProtocol.ERR);
        assertThat(owner.deletions().isDeleted(strangersWorld))
                .as("a refused deletion must not leave a tombstone behind")
                .isFalse();
    }

    /**
     * Wait for a peer to have applied a deletion.
     *
     * <p>Relay is a send; application happens on the receiver's own state thread. Asserting
     * immediately would be testing the scheduler, and would pass or fail depending on the machine.
     */
    private static void awaitDeleted(Worker worker, String worldIdHex) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && !worker.deletions().isDeleted(worldIdHex)) {
            Thread.sleep(20);
        }
    }

    private static PeerEntry member(Worker worker) {
        return new PeerEntry(worker.identity().nodeId(), "loopback",
                NodeCapabilities.initial(), false);
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
