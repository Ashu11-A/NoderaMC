package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.protocol.membership.WorldDeletionGossip;
import dev.nodera.storage.PersistedWorldKey;
import dev.nodera.storage.WorldOwnership;
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
