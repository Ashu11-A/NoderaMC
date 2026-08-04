package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.core.identity.SessionDelegation;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.PeerRuntimeConfig;
import dev.nodera.peer.control.ControlProtocol;
import dev.nodera.peer.control.ControlServer;
import dev.nodera.peer.discovery.TrackerClient;
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
 * {@code NODERA-DELEGATE} over the real control endpoint — the verb that ends a world's author being
 * treated as a stranger in their own world.
 *
 * <p>The game generates a throwaway keypair per session and announces it. Permissions are anchored
 * to the worker's persistent key. Those two facts alone meant every player was evaluated as a
 * member, forever. This verb is the bridge: the worker signs that the session key speaks for it.
 */
final class DelegateVerbIT {

    @TempDir
    Path dir;

    private ControlServer control;
    private WorldHostingService hosting;
    private TrackerClient tracker;
    private PeerRuntime runtime;
    private LoopbackTransport transport;
    private NodeIdentity identity;

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
        hosting = new WorldHostingService(identity, caps, runtime::selfRoute, tracker, List.of(),
                worldId -> List.of(), new WorldRegistryStore(dir.resolve("worlds.dat")));
        WorkerControlHandler handler = new WorkerControlHandler("delegate-test", identity, caps,
                runtime, new dev.nodera.diagnostics.metric.TrafficMeter(), hosting, null, null,
                null, null, null, null, new WorldKeyStore(dir.resolve("world-keys")));
        control = new ControlServer("127.0.0.1", 0, handler);
        control.start();
    }

    @Test
    @DisplayName("the worker vouches for a session key, and the delegation names the worker")
    void aDelegationBindsTheSessionKeyToTheWorkerIdentity() throws Exception {
        bootWorker();
        NodeIdentity session = NodeIdentity.generate();
        String worldIdHex = "00112233445566778899aabbccddeeff";

        SessionDelegation delegation = ask(worldIdHex, session, 3600);

        assertThat(delegation.workerNodeId())
                .as("permissions resolve against this, not the session key")
                .isEqualTo(identity.nodeId());
        assertThat(delegation.workerPublicKey()).isEqualTo(identity.publicKeyBytes());
        assertThat(delegation.isValidFor(session.publicKeyBytes(), Bytes.fromHex(worldIdHex),
                System.currentTimeMillis())).isTrue();
    }

    @Test
    @DisplayName("a delegation is inert for any other session key")
    void itDoesNotVouchForAKeyItWasNotAskedAbout() throws Exception {
        bootWorker();
        NodeIdentity session = NodeIdentity.generate();
        NodeIdentity attacker = NodeIdentity.generate();
        String worldIdHex = "00112233445566778899aabbccddeeff";

        SessionDelegation delegation = ask(worldIdHex, session, 3600);

        assertThat(delegation.isValidFor(attacker.publicKeyBytes(), Bytes.fromHex(worldIdHex),
                System.currentTimeMillis())).isFalse();
    }

    @Test
    @DisplayName("a delegation is inert in any other world")
    void itIsScopedToTheWorldItWasMintedFor() throws Exception {
        bootWorker();
        NodeIdentity session = NodeIdentity.generate();

        SessionDelegation delegation =
                ask("00112233445566778899aabbccddeeff", session, 3600);

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
        SessionDelegation delegation =
                ask("00112233445566778899aabbccddeeff", session, 10L * 365 * 24 * 3600);

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
        String reply = request(ControlProtocol.DELEGATE + " 2 00112233445566778899aabbccddeeff");

        assertThat(reply).startsWith(ControlProtocol.ERR);
    }

    private SessionDelegation ask(String worldIdHex, NodeIdentity session, long ttlSeconds)
            throws Exception {
        String reply = request(ControlProtocol.DELEGATE + " 2 " + worldIdHex + " "
                + Base64.getEncoder().encodeToString(session.publicKeyBytes().toArray())
                + " " + ttlSeconds);
        assertThat(reply).as("expected an OK reply").startsWith(ControlProtocol.OK + " ");
        String payload = reply.substring(ControlProtocol.OK.length() + 1).trim();
        return SessionDelegation.decode(new CanonicalReader(
                Bytes.unsafeWrap(Base64.getDecoder().decode(payload))));
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
