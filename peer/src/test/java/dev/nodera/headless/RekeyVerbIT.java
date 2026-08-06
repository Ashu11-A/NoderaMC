package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.distribution.WorldArchive;
import dev.nodera.peer.control.ControlProtocol;
import dev.nodera.storage.WorldIdentity;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.testkit.peer.WorkerNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #37 / L-51: the {@code NODERA-REKEY} control verb wires the full password re-key pipeline —
 * re-pack is the mod's job, but here the worker re-encrypts the archive under a fresh Argon2id salt
 * (new key → new ciphertext → new {@code manifestRoot} + bumped version), re-signs the
 * {@link WorldIdentity} with the new {@code manifestRef}, and returns it. This IT drives the verb
 * over a real loopback control endpoint and proves the crypto + identity round trip — no Minecraft
 * types. Authorship is enforced by the signature (a wrong-author identity is rejected).
 */
final class RekeyVerbIT {

    /**
     * A minute, not the harness default of thirty seconds: this verb derives an Argon2id key on the
     * request thread, and it is the only verb in the tree that can legitimately take that long.
     */
    private static final Duration REKEY_TIMEOUT = Duration.ofSeconds(60);

    private final PeerTestHarness harness = PeerTestHarness.create();
    private final InMemoryContentStore store = new InMemoryContentStore(harness.hashes());
    private WorkerNode worker;

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private NodeIdentity author() throws java.io.IOException {
        worker = harness.workerNode("rekey-test")
                .roles(PeerRole.FULL_ARCHIVE, PeerRole.BOOTSTRAP)
                .archive(store)
                .controlTimeout(REKEY_TIMEOUT)
                .build();
        return worker.identity();
    }

    /**
     * The identity half of an {@code NODERA-OK <identityB64> <version>} payload. The version token
     * is what an encrypted refresh records as the save's seeded version (L-59) — SEED's reply is no
     * longer the only place a version comes from.
     */
    private static Bytes b64ToBytes(String b64) {
        return Bytes.unsafeWrap(Base64.getDecoder().decode(b64.trim().split("\\s+")[0]));
    }

    /** The version token of an {@code NODERA-OK <identityB64> <version>} payload. */
    private static long replyVersion(String reply) {
        String[] parts = WorkerNode.okPayload(reply).split("\\s+");
        assertEquals(2, parts.length, "the reply must carry the seeded version: " + reply);
        return Long.parseLong(parts[1]);
    }

    private static Bytes encodeIdentity(WorldIdentity id) {
        CanonicalWriter w = new CanonicalWriter();
        id.encode(w);
        return w.toBytes();
    }

    /** The re-key verb line: world id, the packed archive's path, the new password, the identity. */
    private String rekey(String worldIdHex, Path archiveFile, String password, WorldIdentity id) {
        return worker.request(ControlProtocol.REKEY + " 2 " + worldIdHex
                + " " + WorkerNode.b64(archiveFile.toString())
                + " " + WorkerNode.b64(password)
                + " " + WorkerNode.b64(encodeIdentity(id)));
    }

    private static WorldIdentity decodeIdentity(String reply) {
        return WorldIdentity.decode(new CanonicalReader(b64ToBytes(WorkerNode.okPayload(reply))));
    }

    @Test
    void rekeyEncryptsUnderNewPasswordAndResignsIdentity(@TempDir Path tmp) throws Exception {
        NodeIdentity authorIdentity = author();

        // A freshly-packed plaintext blob (the mod's job) + the current signed identity.
        byte[] blob = new byte[300_000];
        new java.util.Random(42L).nextBytes(blob);
        Path archiveFile = tmp.resolve("packed.nar");
        Files.write(archiveFile, blob);

        Bytes genesisRoot = harness.hashes().sha256("genesis".getBytes());
        WorldIdentity current = WorldIdentity.create(authorIdentity, genesisRoot, 1L,
                true, true, false, Bytes.empty());
        String worldIdHex = current.worldId().toHex();

        String pwd = "new-password-#37";
        String reply = rekey(worldIdHex, archiveFile, pwd, current);

        assertTrue(reply.startsWith(ControlProtocol.OK + " "), "expected OK, got: " + reply);
        assertTrue(replyVersion(reply) > 0,
                "the reply reports the archive version now seeded: " + reply);
        WorldIdentity reSigned = decodeIdentity(reply);

        // worldId is stable; encrypted flag flips; manifestRef is the new manifestRoot; signature verifies.
        assertEquals(current.worldId(), reSigned.worldId());
        assertTrue(reSigned.encrypted());
        assertTrue(reSigned.verifySignature());
        assertFalse(reSigned.manifestRef().isEmpty());

        PieceManifest newest = worker.archive().newestManifest(worldIdHex).orElseThrow();
        assertTrue(newest.encrypted());
        assertEquals(reSigned.manifestRef(), newest.manifestRoot());
        assertEquals(1, newest.version().value()); // first seed → v1

        // The published ciphertext blob (keyed by its ContentId hash) decrypts to the original under
        // the NEW password only — proving the key actually changed.
        byte[] cipherBlob = store.get(newest.blob()).orElseThrow();
        assertArrayEquals(blob, WorldArchive.decryptArchive(newest, cipherBlob,
                pwd.toCharArray()).orElseThrow());
        assertTrue(WorldArchive.decryptArchive(newest, cipherBlob,
                "wrong".toCharArray()).isEmpty());
    }

    @Test
    void aSecondRekeySupersedesTheOldCiphertextSoTheOldPasswordStopsWorking(@TempDir Path tmp)
            throws Exception {
        // L-55: a re-key used to APPEND a manifest version and leave the previous one seeded. The
        // superseded blob is still decryptable with the OLD password, so changing the password
        // revoked nothing on this node — a holder of the old password kept reading the pre-re-key
        // world from it. Superseding evicts the old version: manifest table, tracker holdings, and
        // the content store itself.
        NodeIdentity authorIdentity = author();

        byte[] blob = new byte[120_000];
        new java.util.Random(7L).nextBytes(blob);
        Path archiveFile = tmp.resolve("packed.nar");
        Files.write(archiveFile, blob);

        Bytes genesisRoot = harness.hashes().sha256("genesis-l55".getBytes());
        WorldIdentity current = WorldIdentity.create(authorIdentity, genesisRoot, 1L,
                true, true, false, Bytes.empty());
        String worldIdHex = current.worldId().toHex();

        String firstPassword = "first-password";
        String firstReply = rekey(worldIdHex, archiveFile, firstPassword, current);
        assertTrue(firstReply.startsWith(ControlProtocol.OK + " "), firstReply);
        WorldIdentity afterFirst = decodeIdentity(firstReply);
        PieceManifest firstManifest = worker.archive().newestManifest(worldIdHex).orElseThrow();
        byte[] firstCipher = store.get(firstManifest.blob()).orElseThrow();
        assertArrayEquals(blob, WorldArchive.decryptArchive(firstManifest, firstCipher,
                firstPassword.toCharArray()).orElseThrow());

        // The author changes the password again.
        String secondPassword = "second-password";
        String secondReply = rekey(worldIdHex, archiveFile, secondPassword, afterFirst);
        assertTrue(secondReply.startsWith(ControlProtocol.OK + " "), secondReply);

        PieceManifest newest = worker.archive().newestManifest(worldIdHex).orElseThrow();
        assertEquals(2, newest.version().value());
        assertFalse(newest.manifestRoot().equals(firstManifest.manifestRoot()),
                "a re-key must mint a new manifest root");

        // The superseded version is gone: not in the manifest table, not advertised, not stored.
        assertEquals(1, worker.archive().heldVersions(worldIdHex).size(),
                "only the newest version survives a re-key");
        assertEquals(newest.manifestRoot(),
                worker.archive().heldVersions(worldIdHex).get(0).manifestRoot());
        assertTrue(worker.archive().holdingsFor(worldIdHex).stream()
                        .noneMatch(h -> h.manifestRoot().equals(firstManifest.manifestRoot())),
                "the next announce must not advertise the superseded manifest");
        assertTrue(worker.archive().content().heldPieces(firstManifest.manifestRoot()).isEmpty(),
                "no piece of the superseded manifest is held any more");
        assertFalse(store.has(firstManifest.blob()),
                "the old ciphertext is evicted from the content store, "
                        + "so the OLD password no longer reads anything from this node");

        // And the surviving ciphertext answers to the new password only.
        byte[] secondCipher = store.get(newest.blob()).orElseThrow();
        assertArrayEquals(blob, WorldArchive.decryptArchive(newest, secondCipher,
                secondPassword.toCharArray()).orElseThrow());
        assertTrue(WorldArchive.decryptArchive(newest, secondCipher,
                firstPassword.toCharArray()).isEmpty());
    }

    @Test
    void rekeyRejectsAWrongAuthorIdentity(@TempDir Path tmp) throws Exception {
        author(); // the worker's identity

        byte[] blob = new byte[64_000];
        Path archiveFile = tmp.resolve("packed.nar");
        Files.write(archiveFile, blob);

        // An identity minted by a DIFFERENT identity than the worker — resign must reject it.
        NodeIdentity stranger = NodeIdentity.generate();
        Bytes genesisRoot = harness.hashes().sha256("genesis2".getBytes());
        WorldIdentity notOurs = WorldIdentity.create(stranger, genesisRoot, 1L,
                true, true, false, Bytes.empty());
        // The world id passed in the verb must match the identity's derived worldId for the
        // defence-in-depth check to pass and reach the author gate; use the identity's own.
        String hex = notOurs.worldId().toHex();

        String reply = rekey(hex, archiveFile, "whatever", notOurs);

        assertNotNull(reply);
        assertTrue(reply.startsWith(ControlProtocol.ERR), "expected ERR, got: " + reply);
        assertTrue(reply.contains("not the author"));
        // No manifest was seeded for this world.
        assertTrue(worker.archive().newestManifest(hex).isEmpty());
    }
}
