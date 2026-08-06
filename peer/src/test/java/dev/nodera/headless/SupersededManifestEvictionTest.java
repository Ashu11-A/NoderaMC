package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.distribution.WorldArchive;
import dev.nodera.storage.ContentStore;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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

    private final ArchiveMesh mesh = ArchiveMesh.loopback(1);
    private final WorldArchiveService replica = mesh.node(0).service();
    private final ContentStore store = mesh.node(0).store();

    @AfterEach
    void tearDown() {
        mesh.close();
    }

    /** Somebody else's node, as the sender of an inbound manifest answer. */
    private static PeerAddress anotherPeer() {
        return PeerAddress.of(NodeIdentity.generate().nodeId(), "loopback");
    }

    @Test
    void learningANewerVersionEvictsTheOneThisSeederWasStillServing() {
        Bytes worldId = mesh.worldId("l55-world");
        String worldIdHex = worldId.toHex();

        // This peer replicated the world before the author rotated its password. ENCRYPTED, because
        // that is what the rule is about: the superseded blob is ciphertext the OLD password still
        // opens. The fixture used a plaintext archive, which has no password to revoke — so the test
        // was asserting the rule against content the rule does not protect, and that mismatch is why
        // eviction had been applied to plaintext worlds too, destroying the only copies a swarm had.
        PieceManifest old = replica.seedEncryptedArchive(worldIdHex,
                ArchiveMesh.blob(1L, 80_000), "old-password".toCharArray());
        assertThat(replica.heldVersions(worldIdHex)).hasSize(1);
        assertThat(store.has(old.blob())).isTrue();
        assertThat(replica.holdingsFor(worldIdHex)).hasSize(1);

        // The author re-keys; the newer manifest reaches this peer through the ordinary manifest
        // exchange. Note it holds no piece of v2 — it only learned that v2 exists, and that alone
        // is what makes v1 superseded.
        PieceManifest rotated = WorldArchive.manifestFor(2L, ArchiveMesh.blob(2L, 80_000));
        replica.onMessage(anotherPeer(), ArchiveMesh.answerCarrying(worldId, rotated));

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
        Bytes worldId = mesh.worldId("l55-world-2");
        String worldIdHex = worldId.toHex();

        PieceManifest held = replica.seedEncryptedArchive(worldIdHex,
                ArchiveMesh.blob(3L, 40_000), "pw".toCharArray());
        replica.onMessage(anotherPeer(), ArchiveMesh.answerCarrying(worldId, held));

        assertThat(replica.heldVersions(worldIdHex)).hasSize(1);
        assertThat(store.has(held.blob())).isTrue();
        assertThat(replica.content().heldPieces(held.manifestRoot()).isEmpty()).isFalse();
    }

    @Test
    void anOlderVersionArrivingLateNeverEvictsTheNewerOne() {
        // Answers are unordered on the wire: a slow seeder may deliver v1 after v2 is known. That
        // must not superseded-evict the newest, and must not resurrect v1 either.
        Bytes worldId = mesh.worldId("l55-world-3");
        String worldIdHex = worldId.toHex();

        replica.seedEncryptedArchive(worldIdHex,
                ArchiveMesh.blob(9L, 10_000), "pw".toCharArray());                          // v1…
        PieceManifest current = replica.seedEncryptedArchive(worldIdHex,
                ArchiveMesh.blob(4L, 50_000), "pw".toCharArray());                          // v2
        replica.supersedeOlderVersions(worldIdHex);                           // v1 evicted
        assertThat(replica.heldVersions(worldIdHex)).hasSize(1);

        PieceManifest stale = WorldArchive.manifestFor(1L, ArchiveMesh.blob(5L, 10_000));
        replica.onMessage(anotherPeer(), ArchiveMesh.answerCarrying(worldId, stale));

        assertThat(replica.newestManifest(worldIdHex).orElseThrow().manifestRoot())
                .isEqualTo(current.manifestRoot());
        assertThat(store.has(current.blob())).isTrue();
        assertThat(replica.heldVersions(worldIdHex))
                .as("the late arrival is dropped, not adopted")
                .allMatch(m -> m.version().value() >= current.version().value());
    }
}
