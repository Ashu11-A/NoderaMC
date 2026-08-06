package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.SessionDelegation;
import dev.nodera.peer.control.ControlProtocol;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.testkit.peer.WorkerNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code NODERA-DELEGATE} over the real control endpoint — the verb that ends a world's author being
 * treated as a stranger in their own world.
 *
 * <p>The game generates a throwaway keypair per session and announces it. Permissions are anchored
 * to the worker's persistent key. Those two facts alone meant every player was evaluated as a
 * member, forever. This verb is the bridge: the worker signs that the session key speaks for it.
 */
final class DelegateVerbIT {

    private static final String WORLD = "00112233445566778899aabbccddeeff";

    @TempDir
    Path dir;

    private final PeerTestHarness harness = PeerTestHarness.create();
    private WorkerNode worker;

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private void bootWorker() throws Exception {
        worker = harness.workerNode("delegate-test").stateDir(dir).build();
    }

    @Test
    @DisplayName("the worker vouches for a session key, and the delegation names the worker")
    void aDelegationBindsTheSessionKeyToTheWorkerIdentity() throws Exception {
        bootWorker();
        NodeIdentity session = NodeIdentity.generate();

        SessionDelegation delegation = ask(WORLD, session, 3600);

        assertThat(delegation.workerNodeId())
                .as("permissions resolve against this, not the session key")
                .isEqualTo(worker.nodeId());
        assertThat(delegation.workerPublicKey()).isEqualTo(worker.identity().publicKeyBytes());
        assertThat(delegation.isValidFor(session.publicKeyBytes(), Bytes.fromHex(WORLD),
                System.currentTimeMillis())).isTrue();
    }

    @Test
    @DisplayName("a delegation is inert for any other session key")
    void itDoesNotVouchForAKeyItWasNotAskedAbout() throws Exception {
        bootWorker();
        NodeIdentity session = NodeIdentity.generate();
        NodeIdentity attacker = NodeIdentity.generate();

        SessionDelegation delegation = ask(WORLD, session, 3600);

        assertThat(delegation.isValidFor(attacker.publicKeyBytes(), Bytes.fromHex(WORLD),
                System.currentTimeMillis())).isFalse();
    }

    @Test
    @DisplayName("a delegation is inert in any other world")
    void itIsScopedToTheWorldItWasMintedFor() throws Exception {
        bootWorker();
        NodeIdentity session = NodeIdentity.generate();

        SessionDelegation delegation = ask(WORLD, session, 3600);

        assertThat(delegation.isValidFor(session.publicKeyBytes(),
                Bytes.fromHex("ffeeddccbbaa99887766554433221100"), System.currentTimeMillis()))
                .isFalse();
    }

    @Test
    @DisplayName("an oversized lifetime is clamped rather than honoured")
    void theWorkerDecidesHowLongItVouchesFor() throws Exception {
        bootWorker();
        NodeIdentity session = NodeIdentity.generate();

        // A session asking for a decade gets the worker's own ceiling. The expiry is the only thing
        // limiting a delegation that has been copied off a disk, so the asker does not set it.
        SessionDelegation delegation = ask(WORLD, session, 10L * 365 * 24 * 3600);

        assertThat(delegation.notAfterEpochMillis())
                .isLessThanOrEqualTo(System.currentTimeMillis()
                        + SessionDelegation.DEFAULT_TTL_MILLIS);
    }

    @Test
    @DisplayName("an empty session key is refused rather than signed")
    void anEmptySessionKeyIsRefused() throws Exception {
        bootWorker();

        // No key argument at all. Signing here would produce a statement naming no session, which
        // is a credential for whoever holds the bytes.
        String reply = worker.request(ControlProtocol.DELEGATE + " 2 " + WORLD);

        assertThat(reply).startsWith(ControlProtocol.ERR);
    }

    private SessionDelegation ask(String worldIdHex, NodeIdentity session, long ttlSeconds) {
        String payload = WorkerNode.okPayload(worker.request(ControlProtocol.DELEGATE + " 2 "
                + worldIdHex + " " + WorkerNode.b64(session.publicKeyBytes())
                + " " + ttlSeconds));
        return SessionDelegation.decode(new CanonicalReader(
                Bytes.unsafeWrap(Base64.getDecoder().decode(payload))));
    }
}
