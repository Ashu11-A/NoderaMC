package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.distribution.WorldArchive;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.PeerRuntimeConfig;
import dev.nodera.peer.control.ControlProtocol;
import dev.nodera.peer.control.ControlServer;
import dev.nodera.storage.WorldIdentity;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.testkit.LoopbackTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.EnumSet;

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
 * over a real loopback {@link ControlServer} and proves the crypto + identity round trip — no
 * Minecraft types. Authorship is enforced by the signature (a wrong-author identity is rejected).
 */
final class RekeyVerbIT {

    private final HashService hashes = new HashService();
    private ControlServer control;
    private WorldArchiveService archive;
    private WorldHostingService hosting;
    private PeerRuntime runtime;
    private LoopbackTransport transport;
    private InMemoryContentStore store;

    @AfterEach
    void tearDown() {
        if (control != null) {
            control.close();
        }
        if (archive != null) {
            archive.close();
        }
        if (hosting != null) {
            try {
                hosting.close();
            } catch (RuntimeException ignored) {
                // teardown
            }
        }
        if (runtime != null) {
            try {
                runtime.stop();
            } catch (RuntimeException ignored) {
                // teardown
            }
        }
        if (transport != null) {
            transport.stop();
        }
    }

    private NodeIdentity author() throws java.io.IOException {
        NodeIdentity identity = NodeIdentity.generate();
        NodeCapabilities caps = NodeCapabilities.initial().withRoles(
                EnumSet.of(PeerRole.FULL_ARCHIVE, PeerRole.BOOTSTRAP));
        transport = LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId());
        transport.start();
        runtime = PeerRuntime.bootstrap(identity, caps, transport,
                () -> "loopback", PeerRuntimeConfig.defaults(), null);
        store = new InMemoryContentStore(hashes);
        archive = new WorldArchiveService(identity, transport, store, java.util.List.of());
        hosting = new WorldHostingService(identity, caps, runtime::selfRoute,
                java.util.List.of(), java.util.List.of(), archive::holdingsFor);
        WorkerControlHandler handler = new WorkerControlHandler("rekey-test", identity, caps,
                runtime, new dev.nodera.diagnostics.metric.TrafficMeter(), hosting, null, archive);
        control = new ControlServer("127.0.0.1", 0, handler);
        control.start();
        return identity;
    }

    /** Send one control line, return the single reply line. */
    private String request(String line) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", control.boundPort()), 2000);
            s.setSoTimeout(60_000);
            OutputStream out = s.getOutputStream();
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                    .readLine();
        }
    }

    private static Bytes b64ToBytes(String b64) {
        return Bytes.unsafeWrap(Base64.getDecoder().decode(b64.trim()));
    }

    private static String b64(Bytes bytes) {
        return Base64.getEncoder().encodeToString(bytes.toArray());
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String b64(String s) {
        return b64(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Bytes encodeIdentity(WorldIdentity id) {
        CanonicalWriter w = new CanonicalWriter();
        id.encode(w);
        return w.toBytes();
    }

    @Test
    void rekeyEncryptsUnderNewPasswordAndResignsIdentity(@TempDir Path tmp) throws Exception {
        NodeIdentity authorIdentity = author();

        // A freshly-packed plaintext blob (the mod's job) + the current signed identity.
        byte[] blob = new byte[300_000];
        new java.util.Random(42L).nextBytes(blob);
        Path archiveFile = tmp.resolve("packed.nar");
        Files.write(archiveFile, blob);

        Bytes genesisRoot = hashes.sha256("genesis".getBytes());
        WorldIdentity current = WorldIdentity.create(authorIdentity, genesisRoot, 1L,
                true, true, false, Bytes.empty());
        String worldIdHex = current.worldId().toHex();

        String pwd = "new-password-#37";
        String reply = request(ControlProtocol.REKEY + " 2 " + worldIdHex
                + " " + b64(archiveFile.toString())
                + " " + b64(pwd)
                + " " + b64(encodeIdentity(current)));

        assertTrue(reply.startsWith(ControlProtocol.OK + " "), "expected OK, got: " + reply);
        WorldIdentity reSigned = WorldIdentity.decode(
                new CanonicalReader(b64ToBytes(reply.substring(ControlProtocol.OK.length() + 1))));

        // worldId is stable; encrypted flag flips; manifestRef is the new manifestRoot; signature verifies.
        assertEquals(current.worldId(), reSigned.worldId());
        assertTrue(reSigned.encrypted());
        assertTrue(reSigned.verifySignature());
        assertFalse(reSigned.manifestRef().isEmpty());

        PieceManifest newest = archive.newestManifest(worldIdHex).orElseThrow();
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
    void rekeyRejectsAWrongAuthorIdentity(@TempDir Path tmp) throws Exception {
        NodeIdentity authorIdentity = author(); // the worker's identity
        String worldIdHex = hashes.sha256("rekey-world-2".getBytes()).toHex();

        byte[] blob = new byte[64_000];
        Path archiveFile = tmp.resolve("packed.nar");
        Files.write(archiveFile, blob);

        // An identity minted by a DIFFERENT identity than the worker — resign must reject it.
        NodeIdentity stranger = NodeIdentity.generate();
        Bytes genesisRoot = hashes.sha256("genesis2".getBytes());
        WorldIdentity notOurs = WorldIdentity.create(stranger, genesisRoot, 1L,
                true, true, false, Bytes.empty());
        // worldIdHex passed in the verb must match the identity's derived worldId for the defense-in-depth
        // check to pass and reach the author gate; use the identity's own worldId.
        String hex = notOurs.worldId().toHex();

        String reply = request(ControlProtocol.REKEY + " 2 " + hex
                + " " + b64(archiveFile.toString())
                + " " + b64("whatever")
                + " " + b64(encodeIdentity(notOurs)));

        assertNotNull(reply);
        assertTrue(reply.startsWith(ControlProtocol.ERR), "expected ERR, got: " + reply);
        assertTrue(reply.contains("not the author"));
        // No manifest was seeded for this world.
        assertTrue(archive.newestManifest(hex).isEmpty());
    }
}
