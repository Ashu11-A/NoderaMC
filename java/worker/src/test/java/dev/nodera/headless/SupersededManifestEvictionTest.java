package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.protocol.content.WorldManifestAnswer;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-55 — a superseded manifest version must stop existing on <b>every</b> seeder, not just on the
 * author's node.
 *
 * <p>A password re-key appends a new encrypted manifest under the same world id. The previous
 * ciphertext is still readable with the OLD password, so a seeder that keeps it is keeping a
 * revoked password usable. Eviction on the author alone is not enough: any peer that replicated the
 * world before the rotation would go on serving the old blob.
 *
 * <p>The fix needs no new protocol, because "only the newest version of a world is maintained" is a
 * policy each seeder can apply on its own the moment it learns a newer version exists — which it
 * already does, from the manifest exchange (tags 51/52) it is part of anyway.
 */
final class SupersededManifestEvictionTest {

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

    private WorldArchiveService replica() {
        NodeIdentity identity = NodeIdentity.generate();
        transport = LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId());
        transport.start();
        store = new InMemoryContentStore(hashes);
        service = new WorldArchiveService(identity, transport, store, List.of());
        return service;
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
    void learningANewerVersionEvictsTheOneThisSeederWasStillServing() {
        WorldArchiveService replica = replica();
        Bytes worldId = hashes.sha256("l55-world".getBytes());
        String worldIdHex = worldId.toHex();

        // This peer replicated the world before the author rotated its password.
        PieceManifest old = replica.seedArchive(worldIdHex, blob(1L, 80_000));
        assertThat(replica.heldVersions(worldIdHex)).hasSize(1);
        assertThat(store.has(old.blob())).isTrue();
        assertThat(replica.holdingsFor(worldIdHex)).hasSize(1);

        // The author re-keys; the newer manifest reaches this peer through the ordinary manifest
        // exchange. Note it holds no piece of v2 — it only learned that v2 exists, and that alone
        // is what makes v1 superseded.
        PieceManifest rotated = dev.nodera.distribution.WorldArchive.manifestFor(2L, blob(2L, 80_000));
        replica.onMessage(PeerAddress.of(NodeIdentity.generate().nodeId(), "loopback"),
                answerCarrying(worldId, rotated));

        assertThat(replica.heldVersions(worldIdHex))
                .as("only the newest known version is maintained")
                .hasSize(1);
        assertThat(replica.heldVersions(worldIdHex).get(0).version().value()).isEqualTo(2L);
        assertThat(store.has(old.blob()))
                .as("the superseded ciphertext is gone from this seeder's store, so the old "
                        + "password reads nothing here either")
                .isFalse();
        assertThat(replica.content().heldPieces(old.manifestRoot()).isEmpty()).isTrue();
        assertThat(replica.holdingsFor(worldIdHex))
                .as("and the next tracker announce advertises nothing of it")
                .noneMatch(h -> h.manifestRoot().equals(old.manifestRoot()));
    }

    @Test
    void learningTheVersionItAlreadyHoldsChangesNothing() {
        WorldArchiveService replica = replica();
        Bytes worldId = hashes.sha256("l55-world-2".getBytes());
        String worldIdHex = worldId.toHex();

        PieceManifest held = replica.seedArchive(worldIdHex, blob(3L, 40_000));
        replica.onMessage(PeerAddress.of(NodeIdentity.generate().nodeId(), "loopback"),
                answerCarrying(worldId, held));

        assertThat(replica.heldVersions(worldIdHex)).hasSize(1);
        assertThat(store.has(held.blob())).isTrue();
        assertThat(replica.content().heldPieces(held.manifestRoot()).isEmpty()).isFalse();
    }

    @Test
    void anOlderVersionArrivingLateNeverEvictsTheNewerOne() {
        // Answers are unordered on the wire: a slow seeder may deliver v1 after v2 is known. That
        // must not superseded-evict the newest, and must not resurrect v1 either.
        WorldArchiveService replica = replica();
        Bytes worldId = hashes.sha256("l55-world-3".getBytes());
        String worldIdHex = worldId.toHex();

        replica.seedArchive(worldIdHex, blob(9L, 10_000));                    // v1 locally…
        PieceManifest current = replica.seedArchive(worldIdHex, blob(4L, 50_000)); // …then v2
        replica.supersedeOlderVersions(worldIdHex);                           // v1 evicted
        assertThat(replica.heldVersions(worldIdHex)).hasSize(1);

        PieceManifest stale = dev.nodera.distribution.WorldArchive.manifestFor(1L, blob(5L, 10_000));
        replica.onMessage(PeerAddress.of(NodeIdentity.generate().nodeId(), "loopback"),
                answerCarrying(worldId, stale));

        assertThat(replica.newestManifest(worldIdHex).orElseThrow().manifestRoot())
                .isEqualTo(current.manifestRoot());
        assertThat(store.has(current.blob())).isTrue();
        assertThat(replica.heldVersions(worldIdHex))
                .as("the late arrival is dropped, not adopted")
                .allMatch(m -> m.version().value() >= current.version().value());
    }
}
