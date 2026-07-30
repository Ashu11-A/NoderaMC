package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.distribution.WorldArchive;
import dev.nodera.protocol.content.WorldManifestAnswer;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Knowing a version exists is not the same as being able to obtain it.
 *
 * <p>The live failure behind this: {@code 2 seeder(s), 2 routable} followed by
 * {@code archive fetch stalled at 0/73 piece(s) after 120s with no progress}. Routing was healthy
 * and the requests went out; nothing came back, because the version being requested was one nobody
 * online held. A live host archives a new version every couple of minutes and then closes its game,
 * which leaves the newest version known to every peer and held by none of them.
 *
 * <p>Two rules follow, and the fetch now applies both: a version is chosen because a seeder is
 * advertised as holding it (the tracker's seeder rows), not merely because it is the highest number
 * anybody answered with; and a complete copy already on this disk is opened rather than downloaded,
 * whichever version it happens to be.
 *
 * <p>Note what this test does <em>not</em> assert. Under L-55 a node that learns of a newer version
 * evicts its older complete copies, so the "open the copy you already have" path is narrower than it
 * looks — see {@code SupersededManifestEvictionTest} for the rule that takes precedence and
 * {@code docs/worker/LIMITATIONS.md} W-REPL-2 for the availability cost.
 */
final class HeldVersionBeatsAnUnreachableNewerOneTest {

    private final HashService hashes = new HashService();
    private WorldArchiveService service;
    private LoopbackTransport transport;
    private InMemoryContentStore store;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
        if (transport != null) {
            transport.stop();
        }
    }

    private static byte[] blob(long seed, int size) {
        byte[] raw = new byte[size];
        new java.util.Random(seed).nextBytes(raw);
        return raw;
    }

    private static WorldManifestAnswer answerCarrying(Bytes worldId, PieceManifest manifest) {
        CanonicalWriter w = new CanonicalWriter();
        manifest.encode(w);
        return new WorldManifestAnswer(worldId, List.of(w.toBytes()));
    }

    @Test
    @DisplayName("learning of a newer version is not the same as being able to get it")
    void knowingIsNotHolding() {
        NodeIdentity identity = NodeIdentity.generate();
        transport = LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId());
        transport.start();
        store = new InMemoryContentStore(hashes);
        service = new WorldArchiveService(identity, transport, store, List.of());

        Bytes worldId = hashes.sha256("two-version-world".getBytes());
        String worldIdHex = worldId.toHex();

        byte[] v2Bytes = blob(1L, 200_000);
        PieceManifest v2 = service.seedArchive(worldIdHex, v2Bytes);
        assertThat(service.newestCompleteLocally(worldIdHex)).contains(v2);

        // The host archived once more and then closed its game. This node learns v3 exists; nobody
        // reachable holds it.
        PieceManifest v3 = WorldArchive.manifestFor(v2.version().value() + 1, blob(2L, 200_000));
        service.onMessage(PeerAddress.of(NodeIdentity.generate().nodeId(), "loopback"),
                answerCarrying(worldId, v3));
        assertThat(service.newestManifest(worldIdHex).orElseThrow().version().value())
                .as("v3 is the newest KNOWN version")
                .isEqualTo(v3.version().value());
    }

    @Test
    @DisplayName("a world genuinely absent still goes to the network")
    void nothingHeldStillFetches() {
        NodeIdentity identity = NodeIdentity.generate();
        transport = LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId());
        transport.start();
        store = new InMemoryContentStore(hashes);
        service = new WorldArchiveService(identity, transport, store, List.of());

        Bytes worldId = hashes.sha256("absent-world".getBytes());
        String worldIdHex = worldId.toHex();

        assertThat(service.newestCompleteLocally(worldIdHex)).isEmpty();
        // The guard is narrow: with nothing held, the fetch still has to go out and still fails
        // honestly when there is nobody to ask.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.fetchArchiveFrom(worldIdHex, Set.of(),
                        Duration.ofSeconds(2)))
                .hasMessageContaining("seeder");
    }
}
