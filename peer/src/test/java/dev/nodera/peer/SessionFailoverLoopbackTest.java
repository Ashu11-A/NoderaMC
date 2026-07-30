package dev.nodera.peer;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.LoopbackTransport.LoopbackNetwork;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full membership + gateway-migration cycle over the in-JVM {@link LoopbackTransport} — fast,
 * deterministic-timing coverage of the continuity logic that the real-socket
 * {@link SessionContinuityIT} then confirms over TCP.
 *
 * <p>Scenario: bootstrap + two player peers converge on a 3-member view with the bootstrap as
 * gateway; the two players exchange direct keep-alives; the bootstrap is killed; both players
 * detect the loss, re-elect the <b>same</b> non-bootstrap gateway at the next epoch, and keep
 * exchanging keep-alives — i.e. they remain connected to each other.
 */
final class SessionFailoverLoopbackTest {

    private final PeerRuntimeConfig fast =
            new PeerRuntimeConfig(Duration.ofMillis(100), Duration.ofMillis(500));
    private final List<PeerRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (PeerRuntime rt : runtimes) {
            rt.stop();
        }
    }

    @Test
    void playersStayConnectedAfterBootstrapGoesOffline() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();

        NodeIdentity bootId = NodeIdentity.generate();
        NodeIdentity p1Id = NodeIdentity.generate();
        NodeIdentity p2Id = NodeIdentity.generate();

        LoopbackTransport bootTx = net.register(bootId.nodeId());
        LoopbackTransport p1Tx = net.register(p1Id.nodeId());
        LoopbackTransport p2Tx = net.register(p2Id.nodeId());

        RecordingListener bootL = new RecordingListener();
        RecordingListener p1L = new RecordingListener();
        RecordingListener p2L = new RecordingListener();

        PeerRuntime boot = PeerRuntime.bootstrap(bootId, NodeCapabilities.initial(),
                bootTx, () -> "loopback", fast, bootL);
        runtimes.add(boot);

        PeerAddress bootAddr = PeerAddress.of(bootId.nodeId(), "loopback");
        PeerRuntime p1 = PeerRuntime.peer(p1Id, NodeCapabilities.initial(),
                p1Tx, () -> "loopback", bootAddr, fast, p1L);
        PeerRuntime p2 = PeerRuntime.peer(p2Id, NodeCapabilities.initial(),
                p2Tx, () -> "loopback", bootAddr, fast, p2L);
        runtimes.add(p1);
        runtimes.add(p2);

        // 1. Converge on a 3-member session with the bootstrap as gateway.
        Await.until("all peers see 3 members", 5_000,
                () -> p1L.memberCount() == 3 && p2L.memberCount() == 3 && bootL.memberCount() == 3);
        Await.until("bootstrap is the gateway on both players", 5_000,
                () -> bootId.nodeId().equals(p1.gatewayId())
                        && bootId.nodeId().equals(p2.gatewayId()));

        // 2. The two players exchange direct keep-alives.
        Await.until("players exchange direct keep-alives", 5_000,
                () -> p1L.keepAlivesFrom(p2Id.nodeId()) > 0
                        && p2L.keepAlivesFrom(p1Id.nodeId()) > 0);

        // 3. Kill the bootstrap peer.
        long p1FromP2 = p1L.keepAlivesFrom(p2Id.nodeId());
        long p2FromP1 = p2L.keepAlivesFrom(p1Id.nodeId());
        boot.stop();

        // 4. Both players drop the bootstrap and re-elect the SAME non-bootstrap gateway.
        Await.until("both players drop the bootstrap", 5_000,
                () -> p1.sessionView().size() == 2 && p2.sessionView().size() == 2);
        Await.until("both players agree on a new non-bootstrap gateway at epoch >= 1", 5_000,
                () -> p1.gatewayId() != null
                        && p1.gatewayId().equals(p2.gatewayId())
                        && !p1.gatewayId().equals(bootId.nodeId())
                        && p1.sessionView().epoch() >= 1
                        && p1.sessionView().epoch() == p2.sessionView().epoch());

        // 5. Continuity: keep-alives between the two players keep flowing after the migration.
        Await.until("keep-alives continue after failover", 5_000,
                () -> p1L.keepAlivesFrom(p2Id.nodeId()) > p1FromP2 + 1
                        && p2L.keepAlivesFrom(p1Id.nodeId()) > p2FromP1 + 1);

        assertThat(p1.gatewayId()).isEqualTo(p2.gatewayId());
        assertThat(p1.gatewayId()).isIn(p1Id.nodeId(), p2Id.nodeId());
    }
}
