package dev.nodera.peer;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.socket.SocketPeerTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Nodera debugger's headless P2P-continuity scenario over <b>real TCP sockets</b> (issue #17;
 * Plan §7.5 {@code base-peer-disconnection}). This is the executable stand-in for the deliverable's
 * manual scenario — "two players connect to a NeoForge server acting as a peer and stay connected
 * when that peer server goes offline" — with the players modelled as headless {@link PeerRuntime}s
 * on real loopback sockets (no GUI / MinecraftServer needed).
 *
 * <p>Boot a bootstrap peer + two player peers on {@code 127.0.0.1}, let them mesh, kill the
 * bootstrap process' transport, and assert the two players (a) detect the loss, (b) deterministically
 * elect the same successor gateway, and (c) keep exchanging traffic over their direct socket — the
 * players remain connected to each other with the bootstrap gone.
 */
final class SessionContinuityIT {

    private final PeerRuntimeConfig fast =
            new PeerRuntimeConfig(Duration.ofMillis(150), Duration.ofMillis(800));
    private final List<PeerRuntime> runtimes = new ArrayList<>();
    private final List<SocketPeerTransport> transports = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (PeerRuntime rt : runtimes) {
            rt.stop();
        }
        for (SocketPeerTransport tx : transports) {
            tx.stop();
        }
    }

    private SocketPeerTransport socket(NodeIdentity id) {
        SocketPeerTransport tx = new SocketPeerTransport(id.nodeId(), "127.0.0.1", 0, "127.0.0.1");
        transports.add(tx);
        return tx;
    }

    @Test
    void twoPlayersStayConnectedWhenTheBootstrapServerGoesOffline() {
        NodeIdentity bootId = NodeIdentity.generate();
        NodeIdentity p1Id = NodeIdentity.generate();
        NodeIdentity p2Id = NodeIdentity.generate();

        SocketPeerTransport bootTx = socket(bootId);
        SocketPeerTransport p1Tx = socket(p1Id);
        SocketPeerTransport p2Tx = socket(p2Id);

        RecordingListener bootL = new RecordingListener();
        RecordingListener p1L = new RecordingListener();
        RecordingListener p2L = new RecordingListener();

        // Bootstrap first, so its listen route is bound before the players dial it.
        PeerRuntime boot = PeerRuntime.bootstrap(bootId, NodeCapabilities.initial(),
                bootTx, bootTx::listenRoute, fast, bootL);
        runtimes.add(boot);
        String bootRoute = boot.selfRoute();
        assertThat(bootRoute).matches("127\\.0\\.0\\.1:\\d+");

        PeerAddress bootAddr = PeerAddress.of(null, bootRoute); // socket routes by host:port
        PeerRuntime p1 = PeerRuntime.peer(p1Id, NodeCapabilities.initial(),
                p1Tx, p1Tx::listenRoute, bootAddr, fast, p1L);
        PeerRuntime p2 = PeerRuntime.peer(p2Id, NodeCapabilities.initial(),
                p2Tx, p2Tx::listenRoute, bootAddr, fast, p2L);
        runtimes.add(p1);
        runtimes.add(p2);

        // 1. Mesh forms over real sockets: everyone sees three members, bootstrap is gateway.
        Await.until("3-member session over TCP", 15_000,
                () -> p1.sessionView().size() == 3 && p2.sessionView().size() == 3
                        && boot.sessionView().size() == 3);
        Await.until("bootstrap is gateway", 15_000,
                () -> bootId.nodeId().equals(p1.gatewayId())
                        && bootId.nodeId().equals(p2.gatewayId()));

        // 2. Direct player↔player keep-alives flow (a real socket independent of the bootstrap).
        Await.until("direct player keep-alives", 15_000,
                () -> p1L.keepAlivesFrom(p2Id.nodeId()) > 0
                        && p2L.keepAlivesFrom(p1Id.nodeId()) > 0);

        long p1FromP2 = p1L.keepAlivesFrom(p2Id.nodeId());
        long p2FromP1 = p2L.keepAlivesFrom(p1Id.nodeId());

        // 3. The bootstrap server goes offline.
        boot.stop();
        bootTx.stop();

        // 4. Both players drop it and converge on the SAME successor gateway at the next epoch.
        Await.until("bootstrap dropped by both players", 15_000,
                () -> p1.sessionView().size() == 2 && p2.sessionView().size() == 2);
        Await.until("deterministic gateway migration agreed", 15_000,
                () -> p1.gatewayId() != null
                        && p1.gatewayId().equals(p2.gatewayId())
                        && !p1.gatewayId().equals(bootId.nodeId())
                        && p1.sessionView().epoch() >= 1
                        && p1.sessionView().epoch() == p2.sessionView().epoch());

        // 5. Continuity: the direct player↔player socket keeps carrying keep-alives.
        Await.until("keep-alives continue after the bootstrap is gone", 15_000,
                () -> p1L.keepAlivesFrom(p2Id.nodeId()) > p1FromP2 + 1
                        && p2L.keepAlivesFrom(p1Id.nodeId()) > p2FromP1 + 1);

        assertThat(p1.gatewayId()).isEqualTo(p2.gatewayId());
        assertThat(p1.gatewayId()).isIn(p1Id.nodeId(), p2Id.nodeId());
        assertThat(p1.isGateway() ^ p2.isGateway()).as("exactly one player is the new gateway").isTrue();
    }
}
