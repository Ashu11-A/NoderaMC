package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.peer.control.ControlProtocol;
import dev.nodera.storage.WorldAdminProof;
import dev.nodera.storage.WorldIdentity;
import dev.nodera.storage.WorldOwnership;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.testkit.peer.WorkerNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

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

    /** The mod's "open this world to Nodera" call, with a fixed genesis so the id is stable. */
    private static final String CREATE_WORLD =
            ControlProtocol.WORLDID + " 2 " + WorkerNode.b64("genesis-root") + " 1000 1 1 0 ";

    @TempDir
    Path dir;

    private final PeerTestHarness harness = PeerTestHarness.create();
    private WorkerNode worker;

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private void bootWorker() throws Exception {
        worker = harness.workerNode("ownership-test").stateDir(dir).build();
    }

    /** Create the world through the verb and decode the identity it answers with. */
    private WorldIdentity createWorld() {
        return WorldIdentity.decode(new CanonicalReader(Bytes.unsafeWrap(
                Base64.getDecoder().decode(WorkerNode.okPayload(worker.request(CREATE_WORLD))))));
    }

    @Test
    @DisplayName("creating a world makes this peer its administrator, provably")
    void mintingAWorldIdentityMintsItsKeyAndAClaim() throws Exception {
        bootWorker();

        WorldIdentity world = createWorld();
        String worldIdHex = world.worldId().toHex();

        // The world now has a key of its own, held here and nowhere else.
        assertThat(worker.keys().administers(worldIdHex)).isTrue();

        // …and the app can see it, without the game running and without a mesh.
        String worlds = worker.request(ControlProtocol.WORLDS + " 2");
        assertThat(worlds).contains("\"world_id\":\"" + worldIdHex + "\"");
        assertThat(worlds).contains("\"owned\":true");
        assertThat(worlds).doesNotContain("\"world_public_key\":\"\"");
    }

    @Test
    @DisplayName("the proof verifies under the world's public key, and only for its own challenge")
    void aProofIsVerifiableAndSingleUse() throws Exception {
        bootWorker();
        WorldIdentity world = createWorld();
        String worldIdHex = world.worldId().toHex();
        Bytes worldPublicKey = worker.keys().load(worldIdHex).orElseThrow().x509Public();

        Bytes challenge = harness.hashes()
                .sha256("a verifier's nonce".getBytes(StandardCharsets.UTF_8));
        String proofB64 = WorkerNode.okPayload(worker.request(ControlProtocol.PROVE + " 2 "
                + worldIdHex + " " + WorkerNode.b64(challenge)));
        WorldAdminProof proof = WorldAdminProof.decode(
                new CanonicalReader(Bytes.unsafeWrap(Base64.getDecoder().decode(proofB64))));

        assertThat(proof.verify(worldPublicKey, challenge, worker.nodeId()))
                .as("the verifier needs nothing but the world's public key")
                .isTrue();
        assertThat(proof.verify(worldPublicKey,
                harness.hashes().sha256("a different nonce".getBytes(StandardCharsets.UTF_8)),
                worker.nodeId()))
                .as("a captured proof does not answer the next challenge")
                .isFalse();
    }

    @Test
    @DisplayName("a peer refuses to prove administration of a world it did not create")
    void anUnownedWorldCannotBeProved() throws Exception {
        bootWorker();
        // A world this node merely serves for somebody else.
        String someoneElses = harness.hashes()
                .sha256("not mine".getBytes(StandardCharsets.UTF_8)).toHex();
        worker.hosting().seed(someoneElses, "Their World");

        String reply = worker.request(ControlProtocol.PROVE + " 2 " + someoneElses + " "
                + WorkerNode.b64("nonce"));

        assertThat(reply).startsWith(ControlProtocol.ERR);
        assertThat(reply).contains("does not administer");
        assertThat(worker.request(ControlProtocol.WORLDS + " 2"))
                .as("it is listed as supported, and honestly not owned")
                .contains("\"role\":\"supported\"")
                .contains("\"owned\":false");
    }

    @Test
    @DisplayName("an empty challenge is refused rather than signed")
    void anEmptyChallengeIsRefused() throws Exception {
        bootWorker();
        WorldIdentity world = createWorld();

        String reply = worker.request(ControlProtocol.PROVE + " 2 " + world.worldId().toHex() + " ");

        assertThat(reply).startsWith(ControlProtocol.ERR);
        assertThat(reply).contains("challenge");
    }

    @Test
    @DisplayName("the claim the app shows is the one a peer would verify off the wire")
    void theStoredClaimIsSelfAuthenticating() throws Exception {
        bootWorker();
        WorldIdentity world = createWorld();

        Bytes stored = worker.hosting().hostedWorlds().iterator().next().ownershipRecord();
        WorldOwnership claim = WorldOwnership.decode(new CanonicalReader(stored));

        assertThat(claim.verify()).isTrue();
        assertThat(claim.worldId()).isEqualTo(world.worldId());
        assertThat(claim.isOwner(worker.nodeId())).isTrue();
    }

    @Test
    @DisplayName("re-creating the same world does not mint it a second key")
    void theKeyIsMintedOnce() throws Exception {
        bootWorker();
        WorldIdentity world = createWorld();
        Bytes keyBefore = worker.keys().load(world.worldId().toHex()).orElseThrow().x509Public();

        // The same genesis root and creation time derive the same world id — a re-share, not a new
        // world. A second key here would silently hand the world to a different administrator.
        createWorld();

        assertThat(worker.keys().load(world.worldId().toHex()).orElseThrow().x509Public())
                .isEqualTo(keyBefore);
    }
}
