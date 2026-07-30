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
import dev.nodera.storage.WorldAdminProof;
import dev.nodera.storage.WorldIdentity;
import dev.nodera.storage.WorldOwnership;
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
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * World ownership over the real control endpoint — the path the companion app and the mod use.
 *
 * <p>The claim under test is not "the verbs parse". It is that <b>creating a world makes this peer
 * its provable administrator</b>: minting a world identity mints the world's key pair, the app can
 * see which worlds are administered here, and a challenge from anyone gets a signature that only
 * the holder of that world's private key could produce — verifiable with nothing but the world's
 * public key.
 */
final class WorldOwnershipVerbIT {

    private final HashService hashes = new HashService();

    @TempDir
    Path dir;

    private ControlServer control;
    private WorldHostingService hosting;
    private TrackerClient tracker;
    private PeerRuntime runtime;
    private LoopbackTransport transport;
    private NodeIdentity identity;
    private WorldKeyStore keys;

    @AfterEach
    void tearDown() {
        for (Runnable close : List.<Runnable>of(
                () -> control.close(),
                () -> hosting.close(),
                () -> tracker.close(),
                () -> runtime.stop(),
                () -> transport.stop())) {
            try {
                close.run();
            } catch (RuntimeException ignored) {
                // best-effort teardown
            }
        }
    }

    private void bootWorker() throws Exception {
        identity = NodeIdentity.generate();
        NodeCapabilities caps = NodeCapabilities.initial()
                .withRoles(EnumSet.of(PeerRole.FULL_ARCHIVE));
        transport = LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId());
        transport.start();
        runtime = PeerRuntime.bootstrap(identity, caps, transport, () -> "loopback",
                PeerRuntimeConfig.defaults(), null);
        tracker = new TrackerClient(List.of(), identity);
        keys = new WorldKeyStore(dir.resolve("world-keys"));
        hosting = new WorldHostingService(identity, caps, runtime::selfRoute, tracker, List.of(),
                worldId -> List.of(), new WorldRegistryStore(dir.resolve("worlds.dat")));
        WorkerControlHandler handler = new WorkerControlHandler("ownership-test", identity, caps,
                runtime, new dev.nodera.diagnostics.metric.TrafficMeter(), hosting, null, null,
                null, null, null, null, keys);
        control = new ControlServer("127.0.0.1", 0, handler);
        control.start();
    }

    @Test
    @DisplayName("creating a world makes this peer its administrator, provably")
    void mintingAWorldIdentityMintsItsKeyAndAClaim() throws Exception {
        bootWorker();

        // The mod's "open this world to Nodera" call.
        String worldIdentityB64 = okPayload(request(ControlProtocol.WORLDID + " 2 "
                + b64("genesis-root") + " 1000 1 1 0 "));
        WorldIdentity world = WorldIdentity.decode(
                new CanonicalReader(Bytes.unsafeWrap(Base64.getDecoder().decode(worldIdentityB64))));
        String worldIdHex = world.worldId().toHex();

        // The world now has a key of its own, held here and nowhere else.
        assertThat(keys.administers(worldIdHex)).isTrue();

        // …and the app can see it, without the game running and without a mesh.
        String worlds = request(ControlProtocol.WORLDS + " 2");
        assertThat(worlds).contains("\"world_id\":\"" + worldIdHex + "\"");
        assertThat(worlds).contains("\"owned\":true");
        assertThat(worlds).doesNotContain("\"world_public_key\":\"\"");
    }

    @Test
    @DisplayName("the proof verifies under the world's public key, and only for its own challenge")
    void aProofIsVerifiableAndSingleUse() throws Exception {
        bootWorker();
        String worldIdentityB64 = okPayload(request(ControlProtocol.WORLDID + " 2 "
                + b64("genesis-root") + " 1000 1 1 0 "));
        WorldIdentity world = WorldIdentity.decode(
                new CanonicalReader(Bytes.unsafeWrap(Base64.getDecoder().decode(worldIdentityB64))));
        String worldIdHex = world.worldId().toHex();
        Bytes worldPublicKey = keys.load(worldIdHex).orElseThrow().x509Public();

        Bytes challenge = hashes.sha256("a verifier's nonce".getBytes(StandardCharsets.UTF_8));
        String proofB64 = okPayload(request(ControlProtocol.PROVE + " 2 " + worldIdHex + " "
                + Base64.getEncoder().encodeToString(challenge.toArray())));
        WorldAdminProof proof = WorldAdminProof.decode(
                new CanonicalReader(Bytes.unsafeWrap(Base64.getDecoder().decode(proofB64))));

        assertThat(proof.verify(worldPublicKey, challenge, identity.nodeId()))
                .as("the verifier needs nothing but the world's public key")
                .isTrue();
        assertThat(proof.verify(worldPublicKey,
                hashes.sha256("a different nonce".getBytes(StandardCharsets.UTF_8)),
                identity.nodeId()))
                .as("a captured proof does not answer the next challenge")
                .isFalse();
    }

    @Test
    @DisplayName("a peer refuses to prove administration of a world it did not create")
    void anUnownedWorldCannotBeProved() throws Exception {
        bootWorker();
        // A world this node merely serves for somebody else.
        String someoneElses = hashes.sha256("not mine".getBytes(StandardCharsets.UTF_8)).toHex();
        hosting.seed(someoneElses, "Their World");

        String reply = request(ControlProtocol.PROVE + " 2 " + someoneElses + " " + b64("nonce"));

        assertThat(reply).startsWith(ControlProtocol.ERR);
        assertThat(reply).contains("does not administer");
        assertThat(request(ControlProtocol.WORLDS + " 2"))
                .as("it is listed as supported, and honestly not owned")
                .contains("\"role\":\"supported\"")
                .contains("\"owned\":false");
    }

    @Test
    @DisplayName("an empty challenge is refused rather than signed")
    void anEmptyChallengeIsRefused() throws Exception {
        bootWorker();
        String worldIdentityB64 = okPayload(request(ControlProtocol.WORLDID + " 2 "
                + b64("genesis-root") + " 1000 1 1 0 "));
        WorldIdentity world = WorldIdentity.decode(
                new CanonicalReader(Bytes.unsafeWrap(Base64.getDecoder().decode(worldIdentityB64))));

        String reply = request(ControlProtocol.PROVE + " 2 " + world.worldId().toHex() + " ");

        assertThat(reply).startsWith(ControlProtocol.ERR);
        assertThat(reply).contains("challenge");
    }

    @Test
    @DisplayName("the claim the app shows is the one a peer would verify off the wire")
    void theStoredClaimIsSelfAuthenticating() throws Exception {
        bootWorker();
        String worldIdentityB64 = okPayload(request(ControlProtocol.WORLDID + " 2 "
                + b64("genesis-root") + " 1000 1 1 0 "));
        WorldIdentity world = WorldIdentity.decode(
                new CanonicalReader(Bytes.unsafeWrap(Base64.getDecoder().decode(worldIdentityB64))));

        Bytes stored = hosting.hostedWorlds().iterator().next().ownershipRecord();
        WorldOwnership claim = WorldOwnership.decode(new CanonicalReader(stored));

        assertThat(claim.verify()).isTrue();
        assertThat(claim.worldId()).isEqualTo(world.worldId());
        assertThat(claim.isOwner(identity.nodeId())).isTrue();
    }

    @Test
    @DisplayName("re-creating the same world does not mint it a second key")
    void theKeyIsMintedOnce() throws Exception {
        bootWorker();
        String first = okPayload(request(ControlProtocol.WORLDID + " 2 " + b64("genesis-root")
                + " 1000 1 1 0 "));
        WorldIdentity world = WorldIdentity.decode(
                new CanonicalReader(Bytes.unsafeWrap(Base64.getDecoder().decode(first))));
        Bytes keyBefore = keys.load(world.worldId().toHex()).orElseThrow().x509Public();

        // The same genesis root and creation time derive the same world id — a re-share, not a new
        // world. A second key here would silently hand the world to a different administrator.
        okPayload(request(ControlProtocol.WORLDID + " 2 " + b64("genesis-root") + " 1000 1 1 0 "));

        assertThat(keys.load(world.worldId().toHex()).orElseThrow().x509Public())
                .isEqualTo(keyBefore);
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Strip the {@code NODERA-OK } prefix, failing loudly on an error reply. */
    private static String okPayload(String reply) {
        assertThat(reply).as("expected an OK reply").startsWith(ControlProtocol.OK + " ");
        return reply.substring(ControlProtocol.OK.length() + 1).trim();
    }

    /** Send one control line, return the single reply line. */
    private String request(String line) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", control.boundPort()), 2000);
            s.setSoTimeout(15_000);
            OutputStream out = s.getOutputStream();
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
    }
}
