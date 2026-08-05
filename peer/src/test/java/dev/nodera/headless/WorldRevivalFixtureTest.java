package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.protocol.membership.WorldRevivalGossip;
import dev.nodera.storage.PersistedWorldKey;
import dev.nodera.storage.WorldOwnership;
import dev.nodera.storage.WorldRevival;
import dev.nodera.storage.WorldTombstone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
